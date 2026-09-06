#include "native/libmmd/src/mmdpack.hpp"
#include "native/libmmd/src/pack_physics.hpp"
#include "native/libmmd/src/skeleton.hpp"

#include <cassert>
#include <cstring>
#include <limits>
#include <variant>

namespace {

template <typename T>
void overwrite(std::vector<std::byte>& bytes, const std::size_t offset, const T value) {
    assert(offset + sizeof(value) <= bytes.size());
    std::memcpy(bytes.data() + offset, &value, sizeof(value));
}

void refresh_header(std::vector<std::byte>& bytes, const std::size_t header_size = 76) {
    std::uint64_t hash = 14695981039346656037ull;
    for (const auto byte : std::span(bytes).subspan(header_size)) {
        hash ^= std::to_integer<std::uint8_t>(byte);
        hash *= 1099511628211ull;
    }
    overwrite(bytes, 24, hash);
    overwrite(bytes, 32, static_cast<std::uint64_t>(bytes.size()));
}

libmmd::pack::PhysicsAssets read_physics(const std::vector<std::byte>& bytes) {
    const auto layout_result = libmmd::pack::inspect_layout(bytes);
    assert(std::holds_alternative<libmmd::pack::Layout>(layout_result));
    const auto result = libmmd::pack::read_physics_assets(
        bytes, std::get<libmmd::pack::Layout>(layout_result));
    assert(std::holds_alternative<libmmd::pack::PhysicsAssets>(result));
    return std::get<libmmd::pack::PhysicsAssets>(result);
}

void expect_invalid_physics(
    const std::vector<std::byte>& bytes, const libmmd::pmx::Limits limits = {}) {
    const auto layout_result = libmmd::pack::inspect_layout(bytes);
    assert(std::holds_alternative<libmmd::pack::Layout>(layout_result));
    const auto result = libmmd::pack::read_physics_assets(
        bytes, std::get<libmmd::pack::Layout>(layout_result), limits);
    assert(std::holds_alternative<libmmd::pack::Error>(result));
}

libmmd::pmx::Model physics_model() {
    libmmd::pmx::Model model{};
    model.vertices.resize(3);
    model.bones.resize(1);
    model.materials.resize(1);
    for (std::uint8_t mode = 0; mode < 3; ++mode) {
        libmmd::pmx::RigidBody body;
        body.name = "body " + std::to_string(mode);
        body.english_name = "rigid body";
        body.bone_index = 0;
        body.collision_group = static_cast<std::uint8_t>(15 - mode);
        body.collision_mask = static_cast<std::uint16_t>(0xa55a + mode);
        body.shape = mode;
        body.size = {1.25f, 2.5f, 3.75f};
        body.position = {4.5f, -5.0f, 6.25f};
        body.rotation = {-0.25f, 0.5f, 0.75f};
        body.mass = 12.5f;
        body.linear_damping = 0.125f;
        body.angular_damping = 0.25f;
        body.restitution = 0.375f;
        body.friction = 0.5f;
        body.mode = mode;
        model.rigid_bodies.push_back(body);
    }
    for (std::uint8_t type = 0; type < 6; ++type) {
        libmmd::pmx::Joint joint;
        joint.name = "joint " + std::to_string(type);
        joint.english_name = "constraint";
        joint.type = type;
        joint.first_rigid_body_index = type % 3;
        joint.second_rigid_body_index = (type + 1) % 3;
        joint.position = {1.0f, 2.0f, 3.0f};
        joint.rotation = {0.25f, 0.5f, 0.75f};
        joint.translation_lower_limit = {-1.0f, -2.0f, -3.0f};
        joint.translation_upper_limit = {4.0f, 5.0f, 6.0f};
        joint.rotation_lower_limit = {-0.125f, -0.25f, -0.5f};
        joint.rotation_upper_limit = {0.5f, 0.75f, 1.0f};
        joint.translation_spring = {7.0f, 8.0f, 9.0f};
        joint.rotation_spring = {10.0f, 11.0f, 12.0f};
        model.joints.push_back(joint);
    }
    for (std::uint8_t shape = 0; shape < 2; ++shape) {
        libmmd::pmx::SoftBody body;
        body.name = "soft body " + std::to_string(shape);
        body.english_name = "cloth";
        body.shape = shape;
        body.material_index = 0;
        body.collision_group = 15;
        body.collision_mask = 0x5aa5;
        body.flags = 7;
        body.link_distance = 2;
        body.cluster_count = 3;
        body.mass = 8.5f;
        body.collision_margin = 0.125f;
        body.aero_model = 4;
        for (std::size_t index = 0; index < body.configuration.size(); ++index) {
            body.configuration[index] = static_cast<float>(index + 1) * 0.125f;
        }
        body.cluster_configuration = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f};
        body.solver_iterations = {1, 2, 3, 4};
        body.material_coefficients = {0.125f, 0.25f, 0.5f};
        body.anchors = {{0, 0, false}, {2, 2, true}};
        body.pinned_vertices = {0, 2};
        model.soft_bodies.push_back(body);
    }
    for (std::uint8_t type = 0; type <= 10; ++type) {
        libmmd::pmx::Morph morph;
        morph.name = "morph " + std::to_string(type);
        morph.english_name = "expression";
        morph.panel = 4;
        morph.type = type;
        if (type == 0 || type == 9) morph.groups.push_back({0, 0.25f});
        else if (type == 1) morph.vertices.push_back({0, {1.0f, 2.0f, 3.0f}});
        else if (type == 2) morph.bones.push_back({0, {}, {0.0f, 0.0f, 0.0f, 1.0f}});
        else if (type <= 7) morph.uvs.push_back({0, {0.25f, 0.5f, 0.75f, 1.0f}});
        else if (type == 8) morph.materials.push_back({});
        else morph.impulses.push_back({0, true, {1.0f, 2.0f, 3.0f}, {4.0f, 5.0f, 6.0f}});
        model.morphs.push_back(morph);
    }
    return model;
}

void test_render_metadata() {
    libmmd::pmx::Model model{};
    model.name = "test";
    model.vertices.resize(2);
    model.indices = {0, 1, 0};
    model.textures = {"body.png"};
    model.materials.resize(1);
    model.materials.front().name = "body";
    model.materials.front().diffuse = {1.0f, 0.5f, 0.25f, 1.0f};
    model.materials.front().texture_index = 0;
    model.materials.front().toon_texture_index = 10;
    model.materials.front().index_count = 3;
    model.bones.resize(1);
    model.morphs.resize(1);
    model.rigid_bodies.resize(1);
    model.joints.resize(1);
    const auto bytes = libmmd::pack::build(model, {});
    const auto result = libmmd::pack::inspect(bytes);
    assert(std::holds_alternative<libmmd::pack::Info>(result));
    const auto& info = std::get<libmmd::pack::Info>(result);
    assert(info.vertex_count == 2);
    assert(info.index_count == 3);
    assert(info.texture_count == 1);
    assert(info.material_count == 1);
    assert(info.bone_count == 1);
    assert(info.morph_count == 1);
    assert(info.rigid_body_count == 1);
    assert(info.joint_count == 1);
    const auto layout = std::get<libmmd::pack::Layout>(libmmd::pack::inspect_layout(bytes));
    const auto assets_result = libmmd::pack::read_render_assets(bytes, layout);
    assert(std::holds_alternative<libmmd::pack::RenderAssets>(assets_result));
    const auto& assets = std::get<libmmd::pack::RenderAssets>(assets_result);
    assert(assets.textures.size() == 1);
    assert(assets.textures.front() == "body.png");
    assert(assets.materials.size() == 1);
    assert(assets.materials.front().value.name == "body");
    assert(assets.materials.front().value.texture_index == 0);
    assert(assets.materials.front().first_index == 0);
    assert(assets.materials.front().value.index_count == 3);

    auto damaged = bytes;
    damaged.back() ^= std::byte{1};
    assert(std::holds_alternative<libmmd::pack::Error>(libmmd::pack::inspect(damaged)));

    auto legacy = bytes;
    const std::uint32_t legacy_version = 1;
    std::memcpy(legacy.data() + 8, &legacy_version, sizeof(legacy_version));
    assert(std::holds_alternative<libmmd::pack::Error>(libmmd::pack::inspect(legacy)));
}

void test_physics_roundtrip_and_legacy() {
    const auto model = physics_model();
    const auto bytes = libmmd::pack::build(model, {});
    const auto layout = std::get<libmmd::pack::Layout>(libmmd::pack::inspect_layout(bytes));
    assert(layout.info.version == 3);
    assert(layout.info.soft_body_count == model.soft_bodies.size());
    const auto assets = read_physics(bytes);
    assert(assets.rigid_bodies == model.rigid_bodies);
    assert(assets.joints == model.joints);
    assert(assets.soft_bodies == model.soft_bodies);

    std::size_t morph_offset = 0;
    assert(std::holds_alternative<std::vector<libmmd::pmx::Bone>>(
        libmmd::read_skeleton(bytes, layout, &morph_offset)));
    const auto direct_result = libmmd::pack::read_physics_assets(bytes, layout, {}, morph_offset);
    assert(std::holds_alternative<libmmd::pack::PhysicsAssets>(direct_result));
    assert(std::get<libmmd::pack::PhysicsAssets>(direct_result).soft_bodies == model.soft_bodies);

    auto legacy_model = model;
    legacy_model.soft_bodies.clear();
    auto legacy = libmmd::pack::build(legacy_model, {});
    legacy.erase(legacy.begin() + 72, legacy.begin() + 76);
    overwrite(legacy, 8, std::uint32_t{2});
    overwrite(legacy, 12, std::uint32_t{72});
    refresh_header(legacy, 72);
    const auto legacy_info = std::get<libmmd::pack::Info>(libmmd::pack::inspect(legacy));
    assert(legacy_info.version == 2);
    assert(legacy_info.soft_body_count == 0);
    const auto legacy_assets = read_physics(legacy);
    assert(legacy_assets.rigid_bodies == model.rigid_bodies);
    assert(legacy_assets.joints == model.joints);
    assert(legacy_assets.soft_bodies.empty());
    for (const std::uint32_t version : {1u, 4u}) {
        overwrite(legacy, 8, version);
        assert(std::holds_alternative<libmmd::pack::Error>(libmmd::pack::inspect(legacy)));
    }
}

void test_physics_validation() {
    const auto model = physics_model();
    const auto bytes = libmmd::pack::build(model, {});
    const auto check_model = [&](const auto& change) {
        auto invalid = model;
        change(invalid);
        expect_invalid_physics(libmmd::pack::build(invalid, {}));
    };
    check_model([](auto& value) { value.rigid_bodies[0].mode = 3; });
    check_model([](auto& value) { value.rigid_bodies[0].shape = 3; });
    check_model([](auto& value) { value.rigid_bodies[0].collision_group = 16; });
    check_model([](auto& value) { value.rigid_bodies[0].bone_index = 1; });
    check_model([](auto& value) { value.rigid_bodies[0].mass = std::numeric_limits<float>::infinity(); });
    check_model([](auto& value) { value.joints[0].type = 6; });
    check_model([](auto& value) { value.joints[0].first_rigid_body_index = 3; });
    check_model([](auto& value) { value.joints[0].rotation_spring[2] = std::numeric_limits<float>::quiet_NaN(); });
    check_model([](auto& value) { value.soft_bodies[0].shape = 2; });
    check_model([](auto& value) { value.soft_bodies[0].material_index = -1; });
    check_model([](auto& value) { value.soft_bodies[0].collision_group = 16; });
    check_model([](auto& value) { value.soft_bodies[0].flags = 8; });
    check_model([](auto& value) { value.soft_bodies[0].aero_model = 5; });
    check_model([](auto& value) { value.soft_bodies[0].link_distance = -1; });
    check_model([](auto& value) { value.soft_bodies[0].cluster_count = -1; });
    check_model([](auto& value) { value.soft_bodies[0].solver_iterations[2] = -1; });
    check_model([](auto& value) { value.soft_bodies[0].configuration[5] = std::numeric_limits<float>::infinity(); });
    check_model([](auto& value) { value.soft_bodies[0].cluster_configuration[2] = std::numeric_limits<float>::quiet_NaN(); });
    check_model([](auto& value) { value.soft_bodies[0].material_coefficients[1] = std::numeric_limits<float>::infinity(); });
    check_model([](auto& value) { value.soft_bodies[0].anchors[0].rigid_body_index = -1; });
    check_model([](auto& value) { value.soft_bodies[0].anchors[0].vertex_index = 3; });
    check_model([](auto& value) { value.soft_bodies[0].pinned_vertices[0] = 3; });
    check_model([](auto& value) { value.morphs[0].type = 11; });
    check_model([](auto& value) { value.morphs[0].panel = 5; });
    check_model([](auto& value) { value.morphs[1].vertices[0].vertex_index = 3; });
    check_model([](auto& value) { value.morphs[10].impulses[0].rigid_body_index = 3; });

    auto limits = libmmd::pmx::Limits{};
    limits.max_rigid_bodies = 2;
    expect_invalid_physics(bytes, limits);
    limits = {};
    limits.max_joints = 5;
    expect_invalid_physics(bytes, limits);
    limits = {};
    limits.max_soft_bodies = 1;
    expect_invalid_physics(bytes, limits);
    limits = {};
    limits.max_soft_body_elements = 7;
    expect_invalid_physics(bytes, limits);
    limits = {};
    limits.max_morph_offsets = 10;
    expect_invalid_physics(bytes, limits);
    limits = {};
    limits.max_string_bytes = 4;
    expect_invalid_physics(bytes, limits);

    auto extra_tail = bytes;
    extra_tail.push_back(std::byte{0});
    refresh_header(extra_tail);
    expect_invalid_physics(extra_tail);

    const auto layout = std::get<libmmd::pack::Layout>(libmmd::pack::inspect_layout(bytes));
    std::size_t morph_offset = 0;
    assert(std::holds_alternative<std::vector<libmmd::pmx::Bone>>(
        libmmd::read_skeleton(bytes, layout, &morph_offset)));
    for (std::size_t size = morph_offset; size < bytes.size(); ++size) {
        auto truncated = bytes;
        truncated.resize(size);
        refresh_header(truncated);
        expect_invalid_physics(truncated);
    }

    auto oversized = bytes;
    overwrite(oversized, 64, std::numeric_limits<std::uint32_t>::max());
    expect_invalid_physics(oversized);
    oversized = bytes;
    overwrite(oversized, 72, std::numeric_limits<std::uint32_t>::max());
    expect_invalid_physics(oversized);
    oversized = bytes;
    const auto first_offset_count = morph_offset + 4 + model.morphs[0].name.size() +
        4 + model.morphs[0].english_name.size() + 2;
    overwrite(oversized, first_offset_count, std::numeric_limits<std::uint32_t>::max());
    refresh_header(oversized);
    expect_invalid_physics(oversized);

    auto without_soft_bodies = model;
    without_soft_bodies.soft_bodies.clear();
    const auto first_soft_body = libmmd::pack::build(without_soft_bodies, {}).size();
    const auto first_anchor_count = first_soft_body + 137 + model.soft_bodies[0].name.size() +
        model.soft_bodies[0].english_name.size();
    auto invalid_near_mode = bytes;
    overwrite(invalid_near_mode, first_anchor_count + 12, std::uint8_t{2});
    refresh_header(invalid_near_mode);
    expect_invalid_physics(invalid_near_mode);
    auto oversized_anchors = bytes;
    overwrite(oversized_anchors, first_anchor_count, std::numeric_limits<std::uint32_t>::max());
    refresh_header(oversized_anchors);
    limits = {};
    limits.max_soft_body_elements = std::numeric_limits<std::uint32_t>::max();
    expect_invalid_physics(oversized_anchors, limits);
    auto oversized_pins = bytes;
    const auto first_pin_count = first_anchor_count + 4 + model.soft_bodies[0].anchors.size() * 9;
    overwrite(oversized_pins, first_pin_count, std::numeric_limits<std::uint32_t>::max());
    refresh_header(oversized_pins);
    expect_invalid_physics(oversized_pins, limits);

    assert(std::holds_alternative<libmmd::pack::Error>(
        libmmd::pack::read_physics_assets(bytes, layout, {}, bytes.size() + 1)));
    assert(std::holds_alternative<libmmd::pack::Error>(
        libmmd::pack::read_physics_assets(bytes, layout, {}, 0)));

    const auto empty_bytes = libmmd::pack::build({}, {});
    const auto empty_assets = read_physics(empty_bytes);
    assert(empty_assets.rigid_bodies.empty());
    assert(empty_assets.joints.empty());
    assert(empty_assets.soft_bodies.empty());
}

}

int main() {
    test_render_metadata();
    test_physics_roundtrip_and_legacy();
    test_physics_validation();
    return 0;
}
