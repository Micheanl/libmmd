#include "native/libmmd/src/model_physics.hpp"

#include <array>
#include <cassert>
#include <cmath>
#include <limits>
#include <numbers>
#include <stdexcept>

namespace {

using libmmd::ModelPhysics;
using libmmd::ModelPhysicsConfig;
using libmmd::Pose;
using libmmd::pack::PhysicsAssets;

constexpr float step_seconds = 1.0f / 120.0f;

libmmd::pmx::RigidBody sphere(const std::int32_t bone_index, const float height, const std::uint8_t mode = 1) {
    libmmd::pmx::RigidBody body;
    body.bone_index = bone_index;
    body.size = {0.5f, 0.0f, 0.0f};
    body.position = {0.0f, height, 0.0f};
    body.mass = 1.0f;
    body.friction = 0.5f;
    body.mode = mode;
    return body;
}

libmmd::BoneTransform transform(const Pose& pose, const std::uint32_t index = 0) {
    libmmd::BoneTransform result;
    assert(pose.global_transform(index, result));
    return result;
}

bool near(const float first, const float second, const float tolerance = 1.0e-4f) {
    return std::abs(first - second) < tolerance;
}

template<typename Operation>
void rejects(Operation operation) {
    bool rejected = false;
    try {
        operation();
    } catch (const std::invalid_argument&) {
        rejected = true;
    }
    assert(rejected);
}

void advance(ModelPhysics& physics, const Pose& animation, Pose& output, const int steps) {
    for (int index = 0; index < steps; ++index) assert(!physics.update(step_seconds, animation, output));
}

void free_fall_and_reset() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 4.0f, 0.0f};
    PhysicsAssets assets;
    assets.rigid_bodies.push_back(sphere(0, 4.0f));
    Pose animation(bones);
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
    advance(physics, animation, output, 60);
    const auto fallen_height = transform(output).position.y;
    assert(fallen_height > 2.7f && fallen_height < 2.8f);
    assert(near(transform(animation).position.y, 4.0f));
    assert(!physics.update(0.0f, animation, output));
    assert(near(transform(output).position.y, fallen_height));
    physics.reset(animation);
    assert(!physics.update(0.0f, animation, output));
    assert(near(transform(output).position.y, 4.0f));
    advance(physics, animation, output, 60);
    assert(near(transform(output).position.y, fallen_height));

    ModelPhysics scaled(bones, assets, {.meters_per_unit = 0.5f});
    advance(scaled, animation, output, 60);
    assert(near(transform(output).position.y, 4.0f + (fallen_height - 4.0f) * 2.0f));
}

void bind_offsets_and_animation_modes() {
    std::array<libmmd::pmx::Bone, 3> bones{};
    for (std::size_t index = 0; index < bones.size(); ++index) {
        bones[index].position = {static_cast<float>(index) * 4.0f, 3.0f, 2.0f};
    }
    PhysicsAssets assets;
    for (std::uint8_t mode = 0; mode < 3; ++mode) {
        auto body = sphere(mode, 3.0f, mode);
        body.position = {static_cast<float>(mode) * 4.0f + 0.75f, 3.2f, 2.5f};
        body.rotation = {0.3f, -0.4f, 0.7f};
        body.collision_mask = 0xffff;
        assets.rigid_bodies.push_back(body);
    }
    Pose animation(bones);
    const libmmd::Quaternion rotation{0.0f, 0.0f, std::sin(0.35f), std::cos(0.35f)};
    for (std::uint32_t index = 0; index < bones.size(); ++index) {
        assert(animation.set_local_transform(index, {0.5f, 0.7f, -0.25f}, rotation));
    }
    animation.evaluate();
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.gravity = {}, .meters_per_unit = 1.0f});
    assert(!physics.update(0.0f, animation, output));
    for (std::uint32_t index = 0; index < bones.size(); ++index) {
        const auto expected = transform(animation, index);
        const auto actual = transform(output, index);
        assert(near(actual.position.x, expected.position.x));
        assert(near(actual.position.y, expected.position.y));
        assert(near(actual.position.z, expected.position.z));
        assert(near(actual.rotation.z, expected.rotation.z));
        assert(near(actual.rotation.w, expected.rotation.w));
    }
    const auto dynamic_start = transform(output, 1);
    for (std::uint32_t index = 0; index < bones.size(); ++index) {
        assert(animation.set_local_transform(index, {1.5f, 1.7f, 0.75f}, {}));
    }
    animation.evaluate();
    advance(physics, animation, output, 30);
    assert(near(transform(output, 0).position.x, transform(animation, 0).position.x));
    assert(near(transform(output, 0).rotation.w, 1.0f));
    assert(near(transform(output, 1).position.x, dynamic_start.position.x));
    assert(near(transform(output, 1).rotation.z, rotation.z));
    assert(near(transform(output, 2).position.x, transform(animation, 2).position.x));
    assert(near(transform(output, 2).position.y, transform(animation, 2).position.y));
    assert(near(transform(output, 2).rotation.z, rotation.z));
}

float collision_height(const std::uint16_t ground_mask, const std::uint16_t falling_mask, const std::uint8_t shape) {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 4.0f, 0.0f};
    PhysicsAssets assets;
    auto ground = sphere(-1, -0.5f);
    ground.shape = 1;
    ground.size = {5.0f, 0.5f, 5.0f};
    ground.mass = 0.0f;
    ground.collision_group = 15;
    ground.collision_mask = ground_mask;
    assets.rigid_bodies.push_back(ground);
    auto falling = sphere(0, 4.0f);
    falling.shape = shape;
    falling.size = {0.5f, 0.5f, 0.5f};
    falling.collision_group = 2;
    falling.collision_mask = falling_mask;
    assets.rigid_bodies.push_back(falling);
    Pose animation(bones);
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
    advance(physics, animation, output, 240);
    const auto height = transform(output).position.y;
    physics.reset(animation);
    advance(physics, animation, output, 240);
    assert(near(transform(output).position.y, height, 0.005f));
    return height;
}

void collision_masks_and_shapes() {
    assert(near(collision_height(0, 0, 0), 0.5f, 0.03f));
    assert(near(collision_height(0, 0, 1), 0.5f, 0.03f));
    assert(near(collision_height(0, 0, 2), 0.75f, 0.03f));
    assert(collision_height(1 << 2, 0, 0) < -10.0f);
    assert(collision_height(0, 1 << 15, 0) < -10.0f);
    assert(collision_height(1 << 3, 1 << 14, 0) > 0.45f);
}

void kinematic_collision() {
    std::array<libmmd::pmx::Bone, 2> bones{};
    bones[0].position = {0.0f, -0.5f, 0.0f};
    bones[1].position = {0.0f, 0.5f, 0.0f};
    PhysicsAssets assets;
    auto ground = sphere(0, -0.5f, 0);
    ground.shape = 1;
    ground.size = {5.0f, 0.5f, 5.0f};
    assets.rigid_bodies = {ground, sphere(1, 0.5f)};
    Pose animation(bones);
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
    for (int index = 0; index < 120; ++index) {
        assert(animation.set_local_transform(0, {0.0f, static_cast<float>(index) / 120.0f, 0.0f}, {}));
        animation.evaluate();
        assert(!physics.update(step_seconds, animation, output));
    }
    assert(transform(output, 1).position.y > 1.4f);
}

float joint_height(const bool world_first, const bool spring_enabled) {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 4.0f, 0.0f};
    PhysicsAssets assets;
    assets.rigid_bodies.push_back(sphere(0, 4.0f));
    libmmd::pmx::Joint joint;
    joint.first_rigid_body_index = world_first ? -1 : 0;
    joint.second_rigid_body_index = world_first ? 0 : -1;
    joint.position = {0.0f, 4.0f, 0.0f};
    if (spring_enabled) {
        joint.translation_lower_limit[1] = -10.0f;
        joint.translation_upper_limit[1] = 10.0f;
        joint.translation_spring[1] = 80.0f;
    }
    assets.joints.push_back(joint);
    Pose animation(bones);
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
    advance(physics, animation, output, 240);
    return transform(output).position.y;
}

void joints_and_rotation_mode() {
    assert(near(joint_height(true, false), 4.0f, 0.01f));
    assert(near(joint_height(false, false), 4.0f, 0.01f));
    const auto spring_height = joint_height(true, true);
    assert(spring_height > 3.5f && spring_height < 3.99f);

    std::array<libmmd::pmx::Bone, 2> bones{};
    bones[1].position = {0.0f, 3.0f, 0.0f};
    bones[1].parent_index = 0;
    PhysicsAssets assets;
    auto driven = sphere(1, 3.0f, 2);
    driven.rotation = {0.0f, 0.0f, 0.4f};
    assets.rigid_bodies.push_back(driven);
    libmmd::pmx::Joint joint;
    joint.second_rigid_body_index = 0;
    joint.position = {0.0f, 3.0f, 0.0f};
    joint.translation_lower_limit = {-10.0f, -10.0f, -10.0f};
    joint.translation_upper_limit = {10.0f, 10.0f, 10.0f};
    assets.joints.push_back(joint);
    Pose animation(bones);
    assert(animation.set_local_transform(0, {1.0f, 1.0f, 0.0f}, {}));
    assert(animation.set_local_transform(1, {}, {0.0f, 0.0f, std::sin(0.35f), std::cos(0.35f)}));
    animation.evaluate();
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
    advance(physics, animation, output, 120);
    assert(near(transform(output, 1).position.x, 1.0f));
    assert(near(transform(output, 1).position.y, 4.0f));
    assert(std::abs(transform(output, 1).rotation.z) < 0.02f);
}

void joint_axes_and_two_bodies() {
    std::array<libmmd::pmx::Bone, 2> bones{};
    bones[0].position = {0.0f, 4.0f, 0.0f};
    bones[1].position = {0.0f, 2.0f, 0.0f};
    PhysicsAssets assets;
    assets.rigid_bodies = {sphere(0, 4.0f, 0), sphere(1, 2.0f)};
    libmmd::pmx::Joint joint;
    joint.first_rigid_body_index = 0;
    joint.second_rigid_body_index = 1;
    joint.position = {0.0f, 3.0f, 0.0f};
    assets.joints.push_back(joint);
    Pose animation(bones);
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
    advance(physics, animation, output, 120);
    assert(near(transform(output, 1).position.y, 2.0f, 0.01f));
    assert(animation.set_local_transform(0, {0.0f, 1.0f, 0.0f}, {}));
    animation.evaluate();
    advance(physics, animation, output, 120);
    assert(near(transform(output, 1).position.y, 3.0f, 0.01f));

    assets.rigid_bodies.resize(1);
    assets.rigid_bodies[0].mode = 1;
    assets.joints[0].first_rigid_body_index = -1;
    assets.joints[0].second_rigid_body_index = 0;
    assets.joints[0].position = {0.0f, 4.0f, 0.0f};
    assets.joints[0].translation_upper_limit[2] = 1.0f;
    animation.reset();
    ModelPhysics reflected_limit(bones, assets, {.gravity = {0.0f, 0.0f, -9.81f}, .meters_per_unit = 1.0f});
    advance(reflected_limit, animation, output, 240);
    assert(near(transform(output).position.z, -1.0f, 0.01f));
    assets.joints[0].translation_lower_limit[2] = 2.0f;
    ModelPhysics free_axis(bones, assets, {.gravity = {0.0f, 0.0f, -9.81f}, .meters_per_unit = 1.0f});
    advance(free_axis, animation, output, 120);
    assert(transform(output).position.z < -4.5f);
}

void heavy_body_joint_stability() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 4.0f, 0.0f};
    for (const auto has_kinematic_anchor : {false, true}) {
        PhysicsAssets assets;
        auto heavy = sphere(0, 4.0f);
        heavy.mass = 1.0e14f;
        heavy.linear_damping = 1.0f;
        heavy.angular_damping = 1.0f;
        assets.rigid_bodies.push_back(heavy);
        if (has_kinematic_anchor) {
            auto anchor = sphere(-1, 4.0f, 0);
            anchor.collision_mask = 0xffff;
            assets.rigid_bodies.push_back(anchor);
        }
        libmmd::pmx::Joint joint;
        joint.first_rigid_body_index = has_kinematic_anchor ? 1 : -1;
        joint.second_rigid_body_index = 0;
        joint.position = {0.0f, 4.0f, 0.0f};
        assets.joints.push_back(joint);
        Pose animation(bones);
        Pose output(bones);
        ModelPhysics physics(bones, assets, {});
        advance(physics, animation, output, 120);
        assert(near(transform(output).position.y, 4.0f, 0.001f));
    }
}

void reflected_body_rotation() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 1.0f, 0.0f};
    PhysicsAssets assets;
    auto ramp = sphere(-1, -0.25f);
    ramp.shape = 1;
    ramp.size = {5.0f, 0.25f, 5.0f};
    ramp.rotation[0] = std::numbers::pi_v<float> / 12.0f;
    ramp.mass = 0.0f;
    ramp.friction = 0.0f;
    assets.rigid_bodies = {ramp, sphere(0, 1.0f)};
    Pose animation(bones);
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
    advance(physics, animation, output, 120);
    assert(transform(output).position.z < -0.7f);
}

void fixed_step_budget() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    PhysicsAssets assets;
    assets.rigid_bodies.push_back(sphere(0, 0.0f));
    ModelPhysicsConfig config{.meters_per_unit = 1.0f, .maximum_substeps = 2};
    Pose animation(bones);
    Pose output(bones);
    ModelPhysics physics(bones, assets, config);
    assert(!physics.update(step_seconds * 0.5f, animation, output));
    assert(near(transform(output).position.y, 0.0f));
    assert(!physics.update(step_seconds * 0.5f, animation, output));
    assert(transform(output).position.y < 0.0f);
    physics.reset(animation);
    assert(physics.update(step_seconds * 10.0f, animation, output));
    const auto limited_height = transform(output).position.y;
    ModelPhysics reference(bones, assets, config);
    Pose expected(bones);
    advance(reference, animation, expected, 2);
    assert(near(limited_height, transform(expected).position.y));
    assert(!physics.update(0.0f, animation, output));
    assert(near(transform(output).position.y, limited_height));
    physics.reset(animation);
    assert(!physics.update(step_seconds * 0.5f, animation, output));
    physics.reset(animation);
    assert(!physics.update(step_seconds * 0.5f, animation, output));
    assert(near(transform(output).position.y, 0.0f));
    assert(physics.update(std::numeric_limits<float>::max(), animation, output));
}

void animation_substep_consistency() {
    std::array<libmmd::pmx::Bone, 2> bones{};
    bones[0].position = {0.0f, 3.0f, 0.0f};
    bones[1].position = {0.0f, 1.0f, 0.0f};
    bones[1].parent_index = 0;
    PhysicsAssets assets;
    auto parent = sphere(0, 3.0f, 0);
    parent.collision_mask = 0xffff;
    auto child = sphere(1, 1.0f, 2);
    child.position[0] = 0.5f;
    child.collision_mask = 0xffff;
    assets.rigid_bodies = {parent, child};
    libmmd::pmx::Joint joint;
    joint.first_rigid_body_index = 0;
    joint.second_rigid_body_index = 1;
    joint.position = {0.0f, 2.0f, 0.0f};
    joint.rotation_lower_limit = {-1.0f, -1.0f, -1.0f};
    joint.rotation_upper_limit = {1.0f, 1.0f, 1.0f};
    joint.rotation_spring[2] = 10.0f;
    assets.joints.push_back(joint);
    const auto sample = [&](const float step_size, const int frames) {
        Pose animation(bones);
        Pose output(bones);
        ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
        physics.reset(animation);
        for (int frame = 1; frame <= frames; ++frame) {
            const auto weight = static_cast<float>(frame) / static_cast<float>(frames);
            const auto angle = 0.2f * weight;
            assert(animation.set_local_transform(0, {0.4f * weight, 0.0f, 0.0f},
                {0.0f, 0.0f, std::sin(angle * 0.5f), std::cos(angle * 0.5f)}));
            animation.evaluate();
            assert(!physics.update(step_size, animation, output));
        }
        return transform(output, 1);
    };
    const auto coarse = sample(step_seconds * 4.0f, 1);
    const auto fine = sample(step_seconds, 4);
    const auto partial = sample(step_seconds * 0.5f, 8);
    assert(std::abs(fine.rotation.z) > 0.001f);
    for (const auto& actual : {coarse, partial}) {
        assert(near(actual.position.x, fine.position.x));
        assert(near(actual.position.y, fine.position.y));
        assert(near(actual.rotation.x, fine.rotation.x));
        assert(near(actual.rotation.y, fine.rotation.y));
        assert(near(actual.rotation.z, fine.rotation.z));
        assert(near(actual.rotation.w, fine.rotation.w));
    }
}

void invalid_inputs_and_lifetime() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    PhysicsAssets assets;
    assets.rigid_bodies.push_back(sphere(0, 0.0f));
    const auto rejects_config = [&](const ModelPhysicsConfig config) {
        rejects([&] { ModelPhysics physics(bones, assets, config); });
    };
    rejects_config({.meters_per_unit = 0.0f});
    rejects_config({.fixed_step_seconds = -1.0f});
    rejects_config({.maximum_substeps = 0});
    rejects_config({.solver_iterations = 0});
    rejects_config({.gravity = {std::numeric_limits<float>::infinity(), 0.0f, 0.0f}});
    const auto rejects_body = [&](const libmmd::pmx::RigidBody body) {
        auto invalid = assets;
        invalid.rigid_bodies.push_back(body);
        rejects([&] { ModelPhysics physics(bones, invalid, {}); });
    };
    auto invalid = sphere(0, 0.0f);
    invalid.mass = -1.0f;
    rejects_body(invalid);
    invalid = sphere(0, 0.0f);
    invalid.linear_damping = 1.1f;
    rejects_body(invalid);
    invalid = sphere(0, 0.0f);
    invalid.size[0] = 0.0f;
    rejects_body(invalid);
    invalid = sphere(2, 0.0f);
    rejects_body(invalid);
    auto unsupported = assets;
    unsupported.soft_bodies.emplace_back();
    rejects([&] { ModelPhysics physics(bones, unsupported, {}); });
    unsupported = assets;
    unsupported.joints.emplace_back();
    unsupported.joints.back().type = 1;
    rejects([&] { ModelPhysics physics(bones, unsupported, {}); });
    unsupported.joints.back().type = 0;
    rejects([&] { ModelPhysics physics(bones, unsupported, {}); });
    unsupported.joints.back().second_rigid_body_index = 0;
    unsupported.joints.back().translation_spring[0] = -1.0f;
    rejects([&] { ModelPhysics physics(bones, unsupported, {}); });
    auto duplicate = assets;
    duplicate.rigid_bodies.push_back(sphere(0, 1.0f));
    for (int iteration = 0; iteration < 12; ++iteration) {
        Pose animation(bones);
        Pose output(bones);
        ModelPhysics physics(bones, duplicate, {});
        assert(!physics.update(0.0f, animation, output));
        rejects([&] { (void)physics.update(-1.0f, animation, output); });
        rejects([&] { (void)physics.update(0.0f, animation, animation); });
        rejects([&] { (void)physics.update(std::numeric_limits<float>::quiet_NaN(), animation, output); });
        const Pose wrong_skeleton(std::span<const libmmd::pmx::Bone>{});
        rejects([&] { physics.reset(wrong_skeleton); });
    }
    const std::array<libmmd::pmx::Bone, 0> no_bones{};
    Pose empty(no_bones);
    Pose output(no_bones);
    ModelPhysics empty_physics(no_bones, {}, {});
    assert(!empty_physics.update(step_seconds, empty, output));
}

}

int main() {
    free_fall_and_reset();
    bind_offsets_and_animation_modes();
    collision_masks_and_shapes();
    kinematic_collision();
    joints_and_rotation_mode();
    joint_axes_and_two_bodies();
    heavy_body_joint_stability();
    reflected_body_rotation();
    fixed_step_budget();
    animation_substep_consistency();
    invalid_inputs_and_lifetime();
    return 0;
}
