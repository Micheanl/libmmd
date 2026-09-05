#include "native/libmmd/src/pose.hpp"

#include <cassert>
#include <cmath>
#include <numbers>
#include <vector>

int main() {
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
    return 0;
}
