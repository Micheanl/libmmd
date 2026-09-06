#include "native/libmmd/src/pose.hpp"

#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <limits>
#include <numbers>
#include <vector>

namespace {

void expect_position(const libmmd::Pose& pose, const std::uint32_t index, const libmmd::Vector3 expected) {
    libmmd::BoneTransform transform;
    assert(pose.global_transform(index, transform));
    assert(std::abs(transform.position.x - expected.x) < 0.001f);
    assert(std::abs(transform.position.y - expected.y) < 0.001f);
    assert(std::abs(transform.position.z - expected.z) < 0.001f);
}

void test_ik() {
    std::vector<libmmd::pmx::Bone> bones(4);
    bones[0].name = "root";
    bones[0].parent_index = -1;
    bones[1].name = "link";
    bones[1].position = {0.0f, 1.0f, 0.0f};
    bones[1].parent_index = 0;
    bones[2].name = "effector";
    bones[2].position = {0.0f, 2.0f, 0.0f};
    bones[2].parent_index = 1;
    bones[3].name = "goal";
    bones[3].position = {1.0f, 1.0f, 0.0f};
    bones[3].parent_index = -1;
    bones[3].flags = 0x0020;
    bones[3].ik_target_index = 2;
    bones[3].ik_iteration_count = 10;
    bones[3].ik_angle_limit = 0.5f;
    bones[3].ik_links.push_back({.bone_index = 1});

    libmmd::Pose pose(bones);
    pose.evaluate();
    const auto matrices = pose.skinning_matrices();
    assert(matrices.size() == 64);
    const auto* effector = matrices.data() + 2 * 16;
    const auto x = effector[4] * 2.0f + effector[12];
    const auto y = effector[5] * 2.0f + effector[13];
    assert(std::abs(x - 1.0f) < 0.001f);
    assert(std::abs(y - 1.0f) < 0.001f);

    pose.evaluate();
    const auto repeated = pose.skinning_matrices();
    assert(std::abs(repeated[2 * 16 + 4] * 2.0f + repeated[2 * 16 + 12] - x) < 0.001f);
    const std::array<libmmd::BonePhysicsOverride, 1> overrides{{
        {1, {{0.0f, 1.0f, 0.0f}, {}}},
    }};
    assert(pose.apply_physics(overrides));
    expect_position(pose, 2, {0.0f, 2.0f, 0.0f});
    pose.evaluate();
    expect_position(pose, 2, {1.0f, 1.0f, 0.0f});
    pose.reset();
    const auto reset = pose.skinning_matrices();
    assert(std::abs(reset[2 * 16 + 4] * 2.0f + reset[2 * 16 + 12] - x) < 0.001f);

    bones[3].position = {0.0001f, 2.0f, 0.0f};
    libmmd::Pose nearly_aligned(bones);
    nearly_aligned.evaluate();
    const auto aligned = nearly_aligned.skinning_matrices();
    assert(std::abs(aligned[2 * 16 + 4] * 2.0f + aligned[2 * 16 + 12] - 0.0001f) < 0.000001f);
    assert(std::abs(aligned[2 * 16 + 5] * 2.0f + aligned[2 * 16 + 13] - 2.0f) < 0.000001f);

    bones.resize(2);
    bones[0].flags = 0;
    bones[1].flags = 0;
    libmmd::Pose simple_pose(bones);
    const auto half = std::numbers::pi_v<float> * 0.25f;
    assert(simple_pose.set_local_transform(0, {}, {0.0f, 0.0f, std::sin(half), std::cos(half)}));
    simple_pose.evaluate();
    const auto rotated = simple_pose.skinning_matrices();
    assert(std::abs(rotated[0]) < 0.001f);
    assert(std::abs(rotated[1] - 1.0f) < 0.001f);
    assert(!simple_pose.set_local_transform(4, {}, {}));
}

void test_physics_hierarchy() {
    std::vector<libmmd::pmx::Bone> bones(5);
    bones[0].parent_index = 2;
    bones[0].position = {0.0f, 2.0f, 0.0f};
    bones[1].parent_index = -1;
    bones[1].position = {8.0f, 1.0f, 4.0f};
    bones[2].parent_index = -1;
    bones[3].parent_index = 0;
    bones[3].position = {0.0f, 3.0f, 0.0f};
    bones[4].parent_index = 2;
    bones[4].position = {1.0f, 0.0f, 0.0f};
    libmmd::Pose pose(bones);
    const auto half = std::numbers::pi_v<float> * 0.25f;
    const libmmd::Quaternion quarter_turn{0.0f, 0.0f, std::sin(half), std::cos(half)};
    assert(pose.set_local_transform(0, {1.0f, 0.0f, 0.0f}, quarter_turn));
    assert(pose.set_local_transform(1, {2.0f, 3.0f, 4.0f}, quarter_turn));
    assert(pose.set_local_transform(2, {0.0f, 1.0f, 0.0f}, {}));
    assert(pose.set_local_transform(4, {0.0f, 0.0f, 1.0f}, quarter_turn));
    pose.evaluate();
    const auto* matrix_storage = pose.skinning_matrices().data();
    const std::vector<float> animated(matrix_storage, matrix_storage + pose.skinning_matrices().size());
    const std::array<libmmd::BonePhysicsOverride, 2> overrides{{
        {0, {{10.0f, 20.0f, 30.0f}, {}}},
        {2, {{5.0f, 6.0f, 7.0f}, quarter_turn}},
    }};
    assert(pose.apply_physics(overrides));
    assert(pose.skinning_matrices().data() == matrix_storage);
    expect_position(pose, 0, {10.0f, 20.0f, 30.0f});
    expect_position(pose, 1, {10.0f, 4.0f, 0.0f});
    expect_position(pose, 2, {5.0f, 6.0f, 7.0f});
    expect_position(pose, 3, {10.0f, 21.0f, 30.0f});
    expect_position(pose, 4, {5.0f, 7.0f, 8.0f});
    libmmd::BoneTransform sibling;
    assert(pose.global_transform(4, sibling));
    assert(std::abs(sibling.rotation.z - 1.0f) < 0.001f);
    assert(std::abs(sibling.rotation.w) < 0.001f);
    assert(std::equal(animated.begin() + 16, animated.begin() + 32, matrix_storage + 16));
    assert(std::abs(matrix_storage[12] - 10.0f) < 0.001f);
    assert(std::abs(matrix_storage[13] - 18.0f) < 0.001f);
    assert(std::abs(matrix_storage[14] - 30.0f) < 0.001f);
    const std::vector<float> physical(matrix_storage, matrix_storage + pose.skinning_matrices().size());
    assert(pose.apply_physics(overrides));
    assert(std::equal(physical.begin(), physical.end(), matrix_storage));
    auto reverse_overrides = overrides;
    std::reverse(reverse_overrides.begin(), reverse_overrides.end());
    assert(pose.apply_physics(reverse_overrides));
    assert(std::equal(physical.begin(), physical.end(), matrix_storage));
    pose.evaluate();
    assert(std::equal(animated.begin(), animated.end(), matrix_storage));
    assert(pose.apply_physics(overrides));
    assert(std::equal(physical.begin(), physical.end(), matrix_storage));
    assert(pose.apply_physics({}));
    assert(std::equal(animated.begin(), animated.end(), matrix_storage));
    pose.reset();
    expect_position(pose, 0, {0.0f, 2.0f, 0.0f});
    expect_position(pose, 1, {8.0f, 1.0f, -4.0f});
    expect_position(pose, 2, {});
    expect_position(pose, 3, {0.0f, 3.0f, 0.0f});
    expect_position(pose, 4, {1.0f, 0.0f, 0.0f});
    assert(pose.skinning_matrices().data() == matrix_storage);
}

void test_physics_rotation_only() {
    std::vector<libmmd::pmx::Bone> bones(3);
    bones[0].parent_index = -1;
    bones[1].parent_index = 0;
    bones[1].position = {0.0f, 2.0f, 0.0f};
    bones[2].parent_index = 1;
    bones[2].position = {0.0f, 3.0f, 0.0f};
    libmmd::Pose pose(bones);
    assert(pose.set_local_transform(0, {2.0f, 3.0f, 4.0f}, {}));
    assert(pose.set_local_transform(1, {1.0f, 0.0f, 0.0f}, {}));
    pose.evaluate();
    const auto half = std::numbers::pi_v<float> * 0.25f;
    const libmmd::Quaternion quarter_turn{0.0f, 0.0f, std::sin(half), std::cos(half)};
    const std::array<libmmd::BonePhysicsOverride, 2> overrides{{
        {1, {{100.0f, 200.0f, 300.0f}, {}}, true},
        {0, {{5.0f, 6.0f, 7.0f}, quarter_turn}},
    }};
    assert(pose.apply_physics(overrides));
    expect_position(pose, 1, {3.0f, 7.0f, 7.0f});
    expect_position(pose, 2, {3.0f, 8.0f, 7.0f});
    assert(pose.apply_physics(overrides));
    expect_position(pose, 1, {3.0f, 7.0f, 7.0f});
    const std::array<libmmd::BonePhysicsOverride, 1> root_only{{
        {0, {{100.0f, 200.0f, 300.0f}, quarter_turn}, true},
    }};
    assert(pose.apply_physics(root_only));
    expect_position(pose, 0, {2.0f, 3.0f, 4.0f});
    expect_position(pose, 1, {0.0f, 4.0f, 4.0f});
    expect_position(pose, 2, {-1.0f, 4.0f, 4.0f});
}

void test_physics_validation() {
    std::vector<libmmd::pmx::Bone> bones(2);
    bones[0].parent_index = -1;
    bones[1].parent_index = 0;
    bones[1].position = {0.0f, 1.0f, 0.0f};
    libmmd::Pose pose(bones);
    const std::array<libmmd::BonePhysicsOverride, 1> initial{{{0, {{2.0f, 3.0f, 4.0f}, {}}}}};
    assert(pose.apply_physics(initial));
    const auto* matrix_storage = pose.skinning_matrices().data();
    const std::vector<float> physical(matrix_storage, matrix_storage + pose.skinning_matrices().size());
    const auto infinity = std::numeric_limits<float>::infinity();
    const auto nan = std::numeric_limits<float>::quiet_NaN();
    const std::array<libmmd::BonePhysicsOverride, 5> invalid{{
        {2, {{}, {}}},
        {1, {{infinity, 0.0f, 0.0f}, {}}},
        {1, {{}, {nan, 0.0f, 0.0f, 1.0f}}},
        {1, {{}, {0.0f, 0.0f, 0.0f, 0.0f}}},
        {0, {{}, {}}},
    }};
    for (const auto& invalid_override : invalid) {
        const std::array<libmmd::BonePhysicsOverride, 2> overrides{{
            {0, {{9.0f, 8.0f, 7.0f}, {}}}, invalid_override,
        }};
        assert(!pose.apply_physics(overrides));
        assert(pose.skinning_matrices().data() == matrix_storage);
        assert(std::equal(physical.begin(), physical.end(), matrix_storage));
        expect_position(pose, 0, {2.0f, 3.0f, 4.0f});
        expect_position(pose, 1, {2.0f, 4.0f, 4.0f});
    }
    libmmd::BoneTransform untouched{{7.0f, 8.0f, 9.0f}, {}};
    assert(!pose.global_transform(2, untouched));
    assert(untouched.position.x == 7.0f && untouched.position.y == 8.0f && untouched.position.z == 9.0f);
    const auto maximum = std::numeric_limits<float>::max();
    for (const auto scale : {maximum, std::numeric_limits<float>::denorm_min(), 2.0f}) {
        const std::array<libmmd::BonePhysicsOverride, 1> scaled{{
            {0, {{1.0f, 2.0f, 3.0f}, {0.0f, 0.0f, 0.0f, scale}}},
        }};
        assert(pose.apply_physics(scaled));
        libmmd::BoneTransform transform;
        assert(pose.global_transform(0, transform));
        assert(transform.rotation.w == 1.0f);
        expect_position(pose, 1, {1.0f, 3.0f, 3.0f});
    }
    assert(pose.set_local_transform(1, {maximum, 0.0f, 0.0f}, {}));
    pose.evaluate();
    const std::vector<float> before_overflow(matrix_storage, matrix_storage + pose.skinning_matrices().size());
    const std::array<libmmd::BonePhysicsOverride, 1> overflow{{
        {0, {{maximum, 0.0f, 0.0f}, {}}},
    }};
    assert(!pose.apply_physics(overflow));
    assert(std::equal(before_overflow.begin(), before_overflow.end(), matrix_storage));
    libmmd::Pose empty({});
    assert(empty.apply_physics({}));
    assert(!empty.apply_physics(initial));
    assert(!empty.global_transform(0, untouched));
}

}

int main() {
    test_ik();
    test_physics_hierarchy();
    test_physics_rotation_only();
    test_physics_validation();
    return 0;
}
