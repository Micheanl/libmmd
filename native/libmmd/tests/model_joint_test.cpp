#include "native/libmmd/src/model_physics.hpp"

#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <limits>
#include <stdexcept>

namespace {

using libmmd::BoneTransform;
using libmmd::ModelPhysics;
using libmmd::Pose;
using libmmd::Quaternion;
using libmmd::Vector3;
using libmmd::pack::PhysicsAssets;

constexpr float step_seconds = 1.0f / 120.0f;

libmmd::pmx::RigidBody sphere(
    const std::int32_t bone_index,
    const std::array<float, 3> position,
    const std::uint8_t mode = 1) {
    libmmd::pmx::RigidBody body;
    body.bone_index = bone_index;
    body.position = position;
    body.size = {0.2f, 0.0f, 0.0f};
    body.mass = 1.0f;
    body.collision_mask = 0xffff;
    body.mode = mode;
    return body;
}

BoneTransform transform(const Pose& pose, const std::uint32_t index = 0) {
    BoneTransform result;
    assert(pose.global_transform(index, result));
    return result;
}

bool near(const float first, const float second, const float tolerance = 1.0e-4f) {
    return std::abs(first - second) < tolerance;
}

void assert_position(const Vector3 actual, const Vector3 expected, const float tolerance = 0.015f) {
    if (!near(actual.x, expected.x, tolerance) || !near(actual.y, expected.y, tolerance) ||
        !near(actual.z, expected.z, tolerance)) {
        std::fprintf(stderr, "position: actual (%g, %g, %g), expected (%g, %g, %g), tolerance %g\n",
            actual.x, actual.y, actual.z, expected.x, expected.y, expected.z, tolerance);
    }
    assert(near(actual.x, expected.x, tolerance));
    assert(near(actual.y, expected.y, tolerance));
    assert(near(actual.z, expected.z, tolerance));
}

Vector3 rotate(const Quaternion rotation, const Vector3 position) {
    const Vector3 twice_cross{
        2.0f * (rotation.y * position.z - rotation.z * position.y),
        2.0f * (rotation.z * position.x - rotation.x * position.z),
        2.0f * (rotation.x * position.y - rotation.y * position.x)};
    return {
        position.x + rotation.w * twice_cross.x + rotation.y * twice_cross.z - rotation.z * twice_cross.y,
        position.y + rotation.w * twice_cross.y + rotation.z * twice_cross.x - rotation.x * twice_cross.z,
        position.z + rotation.w * twice_cross.z + rotation.x * twice_cross.y - rotation.y * twice_cross.x};
}

Vector3 anchor_position(const BoneTransform body, const Vector3 local_anchor) {
    const auto offset = rotate(body.rotation, local_anchor);
    return {body.position.x + offset.x, body.position.y + offset.y, body.position.z + offset.z};
}

void advance(ModelPhysics& physics, const Pose& animation, Pose& output, const int steps) {
    for (int index = 0; index < steps; ++index) assert(!physics.update(step_seconds, animation, output));
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

void ordinary_world_anchor_order() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 4.0f, 2.0f};
    for (const auto world_first : {false, true}) {
        PhysicsAssets assets;
        assets.rigid_bodies.push_back(sphere(0, bones[0].position));
        libmmd::pmx::Joint joint;
        joint.type = 1;
        joint.first_rigid_body_index = world_first ? -1 : 0;
        joint.second_rigid_body_index = world_first ? 0 : -1;
        joint.position = bones[0].position;
        assets.joints.push_back(joint);
        Pose animation(bones);
        assert(animation.set_local_transform(0, {0.4f, -0.7f, 0.2f},
            {0.0f, 0.0f, std::sin(0.15f), std::cos(0.15f)}));
        animation.evaluate();
        Pose output(bones);
        ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
        assert(!physics.update(0.0f, animation, output));
        assert(near(transform(output).position.x, 0.4f));
        advance(physics, animation, output, 120);
        assert_position(transform(output).position, {0.0f, 4.0f, -2.0f});
        assert(std::abs(transform(output).rotation.z) < 0.01f);
    }
}

void ordinary_linear_limits_and_free_axis() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 4.0f, 0.0f};
    for (const auto world_first : {false, true}) {
        for (const auto scale : {1.0f, 0.25f}) {
            PhysicsAssets assets;
            assets.rigid_bodies.push_back(sphere(0, bones[0].position));
            libmmd::pmx::Joint joint;
            joint.type = 1;
            joint.first_rigid_body_index = world_first ? -1 : 0;
            joint.second_rigid_body_index = world_first ? 0 : -1;
            joint.position = bones[0].position;
            joint.translation_upper_limit[2] = 1.0f;
            assets.joints.push_back(joint);
            Pose animation(bones);
            Pose output(bones);
            const auto direction = world_first ? -1.0f : 1.0f;
            const libmmd::ModelPhysicsConfig config{
                .gravity = {0.0f, 0.0f, direction * 9.81f}, .meters_per_unit = scale};
            ModelPhysics limited(bones, assets, config);
            advance(limited, animation, output, 180);
            assert_position(transform(output).position, {0.0f, 4.0f, direction});
            assets.joints[0].translation_lower_limit[2] = 2.0f;
            ModelPhysics free_axis(bones, assets, config);
            advance(free_axis, animation, output, 120);
            assert(transform(output).position.z * direction > 4.5f / scale);
        }
    }
}

float ordinary_pendulum_angle(const bool free_axis) {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 4.0f, 1.0f};
    PhysicsAssets assets;
    assets.rigid_bodies.push_back(sphere(0, bones[0].position));
    libmmd::pmx::Joint joint;
    joint.type = 1;
    joint.second_rigid_body_index = 0;
    joint.position = {0.0f, 4.0f, 0.0f};
    joint.rotation_lower_limit[0] = free_axis ? 1.0f : -0.25f;
    joint.rotation_upper_limit[0] = free_axis ? -1.0f : 0.25f;
    assets.joints.push_back(joint);
    Pose animation(bones);
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.meters_per_unit = 1.0f});
    advance(physics, animation, output, 60);
    const auto actual = transform(output);
    assert_position(anchor_position(actual, {0.0f, 0.0f, 1.0f}), {0.0f, 4.0f, 0.0f});
    assert(std::abs(actual.rotation.y) < 0.001f);
    assert(std::abs(actual.rotation.z) < 0.001f);
    return std::abs(2.0f * std::atan2(actual.rotation.x, actual.rotation.w));
}

void ordinary_angular_limits_and_free_axis() {
    assert(near(ordinary_pendulum_angle(false), 0.25f, 0.025f));
    assert(ordinary_pendulum_angle(true) > 0.7f);
}

void ordinary_ignores_springs() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 4.0f, 0.0f};
    PhysicsAssets assets;
    assets.rigid_bodies.push_back(sphere(0, bones[0].position));
    libmmd::pmx::Joint joint;
    joint.type = 1;
    joint.second_rigid_body_index = 0;
    joint.position = bones[0].position;
    joint.translation_lower_limit = {-10.0f, -10.0f, -10.0f};
    joint.translation_upper_limit = {10.0f, 10.0f, 10.0f};
    joint.rotation_lower_limit = {-1.0f, -1.0f, -1.0f};
    joint.rotation_upper_limit = {1.0f, 1.0f, 1.0f};
    assets.joints.push_back(joint);
    Pose animation(bones);
    assert(animation.set_local_transform(0, {}, {0.0f, 0.0f, std::sin(0.3f), std::cos(0.3f)}));
    animation.evaluate();
    Pose expected(bones);
    Pose output(bones);
    ModelPhysics ordinary(bones, assets, {.meters_per_unit = 1.0f});
    assets.joints[0].translation_spring = {80.0f, 80.0f, 80.0f};
    assets.joints[0].rotation_spring = {80.0f, 80.0f, 80.0f};
    ModelPhysics populated_springs(bones, assets, {.meters_per_unit = 1.0f});
    for (int step = 0; step < 120; ++step) {
        advance(ordinary, animation, expected, 1);
        advance(populated_springs, animation, output, 1);
        assert_position(transform(output).position, transform(expected).position, 0.0001f);
        assert(near(transform(output).rotation.z, transform(expected).rotation.z));
        assert(near(transform(output).rotation.w, transform(expected).rotation.w));
    }
    assert(transform(output).position.y < -0.5f);
    assert(near(transform(output).rotation.z, std::sin(0.3f)));
}

void point_anchor_pendulum_and_reset() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {1.0f, 4.0f, 2.0f};
    for (const auto world_first : {false, true}) {
        for (const auto scale : {1.0f, 0.25f}) {
            PhysicsAssets assets;
            assets.rigid_bodies.push_back(sphere(0, bones[0].position));
            libmmd::pmx::Joint joint;
            joint.type = 2;
            joint.first_rigid_body_index = world_first ? -1 : 0;
            joint.second_rigid_body_index = world_first ? 0 : -1;
            joint.position = {0.0f, 4.0f, 1.0f};
            joint.rotation = {0.3f, -0.4f, 0.7f};
            joint.translation_spring = {100.0f, 100.0f, 100.0f};
            joint.rotation_spring = {100.0f, 100.0f, 100.0f};
            assets.joints.push_back(joint);
            Pose animation(bones);
            Pose output(bones);
            ModelPhysics physics(bones, assets,
                {.gravity = {0.0f, -9.81f * scale, 0.0f}, .meters_per_unit = scale, .solver_iterations = 40});
            std::array<BoneTransform, 120> trajectory;
            for (auto& sample : trajectory) {
                advance(physics, animation, output, 1);
                sample = transform(output);
                assert_position(anchor_position(sample, {-1.0f, 0.0f, 1.0f}), {0.0f, 4.0f, -1.0f});
            }
            assert(trajectory[59].position.y < 3.5f);
            assert(trajectory[59].rotation.x < -0.1f);
            assert(trajectory[59].rotation.z < -0.1f);
            physics.reset(animation);
            assert(!physics.update(0.0f, animation, output));
            assert_position(transform(output).position, {1.0f, 4.0f, -2.0f});
            for (const auto& expected : trajectory) {
                advance(physics, animation, output, 1);
                const auto actual = transform(output);
                assert_position(actual.position, expected.position, 0.0001f);
                assert(near(actual.rotation.x, expected.rotation.x));
                assert(near(actual.rotation.y, expected.rotation.y));
                assert(near(actual.rotation.z, expected.rotation.z));
                assert(near(actual.rotation.w, expected.rotation.w));
            }
        }
    }
}

void point_two_dynamic_bodies() {
    std::array<libmmd::pmx::Bone, 2> bones{};
    bones[0].position = {1.0f, 4.0f, 1.0f};
    bones[1].position = {-1.0f, 4.0f, -1.0f};
    PhysicsAssets assets;
    assets.rigid_bodies = {sphere(0, bones[0].position), sphere(1, bones[1].position)};
    libmmd::pmx::Joint joint;
    joint.type = 2;
    joint.first_rigid_body_index = 0;
    joint.second_rigid_body_index = 1;
    joint.position = {0.0f, 4.0f, 0.0f};
    assets.joints.push_back(joint);
    Pose animation(bones);
    assert(animation.set_local_transform(0, {0.0f, 0.6f, 0.0f}, {}));
    animation.evaluate();
    Pose output(bones);
    ModelPhysics physics(bones, assets, {.gravity = {}, .meters_per_unit = 1.0f});
    advance(physics, animation, output, 60);
    const auto first = transform(output, 0);
    const auto second = transform(output, 1);
    assert_position(anchor_position(first, {-1.0f, 0.0f, 1.0f}),
        anchor_position(second, {1.0f, 0.0f, -1.0f}));
    assert(std::abs(first.rotation.x) > 0.01f);
    assert(std::abs(second.rotation.x) > 0.01f);
    assert(near((first.position.y + second.position.y) * 0.5f, 4.3f, 0.005f));
}

void heavy_body_anchor_stability() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    bones[0].position = {0.0f, 4.0f, 0.0f};
    for (const auto type : {std::uint8_t{1}, std::uint8_t{2}}) {
        for (const auto has_kinematic_anchor : {false, true}) {
            PhysicsAssets assets;
            auto heavy = sphere(0, bones[0].position);
            heavy.mass = 1.0e14f;
            heavy.linear_damping = 1.0f;
            heavy.angular_damping = 1.0f;
            assets.rigid_bodies.push_back(heavy);
            if (has_kinematic_anchor) assets.rigid_bodies.push_back(sphere(-1, bones[0].position, 0));
            libmmd::pmx::Joint joint;
            joint.type = type;
            joint.first_rigid_body_index = has_kinematic_anchor ? 1 : -1;
            joint.second_rigid_body_index = 0;
            joint.position = bones[0].position;
            assets.joints.push_back(joint);
            Pose animation(bones);
            Pose output(bones);
            ModelPhysics physics(bones, assets, {});
            advance(physics, animation, output, 120);
            assert_position(transform(output).position, {0.0f, 4.0f, 0.0f}, 0.001f);
        }
    }
}

void invalid_joints() {
    std::array<libmmd::pmx::Bone, 1> bones{};
    PhysicsAssets assets;
    assets.rigid_bodies.push_back(sphere(0, {}));
    const auto rejects_joint = [&](const libmmd::pmx::Joint joint) {
        auto invalid = assets;
        invalid.joints.push_back(joint);
        rejects([&] { ModelPhysics physics(bones, invalid, {}); });
    };
    for (const auto type : {std::uint8_t{1}, std::uint8_t{2}}) {
        libmmd::pmx::Joint joint;
        joint.type = type;
        rejects_joint(joint);
        joint.first_rigid_body_index = 0;
        joint.second_rigid_body_index = 0;
        rejects_joint(joint);
        for (const auto invalid_index : {-2, 1}) {
            joint.first_rigid_body_index = invalid_index;
            joint.second_rigid_body_index = 0;
            rejects_joint(joint);
            joint.first_rigid_body_index = 0;
            joint.second_rigid_body_index = invalid_index;
            rejects_joint(joint);
        }
        joint.first_rigid_body_index = -1;
        joint.second_rigid_body_index = 0;
        for (const auto field : {
                 &libmmd::pmx::Joint::position, &libmmd::pmx::Joint::rotation,
                 &libmmd::pmx::Joint::translation_lower_limit, &libmmd::pmx::Joint::translation_upper_limit,
                 &libmmd::pmx::Joint::rotation_lower_limit, &libmmd::pmx::Joint::rotation_upper_limit,
                 &libmmd::pmx::Joint::translation_spring, &libmmd::pmx::Joint::rotation_spring}) {
            for (const auto nonfinite : {std::numeric_limits<float>::quiet_NaN(),
                     std::numeric_limits<float>::infinity()}) {
                auto invalid = joint;
                (invalid.*field)[1] = nonfinite;
                rejects_joint(invalid);
            }
        }
    }
}

}

int main() try {
    ordinary_world_anchor_order();
    ordinary_linear_limits_and_free_axis();
    ordinary_angular_limits_and_free_axis();
    ordinary_ignores_springs();
    point_anchor_pendulum_and_reset();
    point_two_dynamic_bodies();
    heavy_body_anchor_stability();
    invalid_joints();
    return 0;
} catch (const std::exception& error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
}
