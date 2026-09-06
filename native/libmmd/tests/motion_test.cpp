#include "native/libmmd/src/motion.hpp"

#include <cassert>
#include <cmath>
#include <cstring>
#include <limits>
#include <vector>

namespace {

template <std::size_t Size>
void name(std::array<std::byte, Size>& destination, const char* value) {
    std::memcpy(destination.data(), value, std::strlen(value));
}

libmmd::vmd::BoneKeyframe frame(const std::uint32_t index, const float x, const float y, const float z) {
    libmmd::vmd::BoneKeyframe value{};
    name(value.name, "bone");
    value.frame = index;
    value.translation = {x, y, z};
    value.rotation = {0.0f, 0.0f, 0.0f, 1.0f};
    for (std::size_t component = 0; component < 4; ++component) {
        value.interpolation[component + 8] = std::byte{127};
        value.interpolation[component + 12] = std::byte{127};
    }
    return value;
}

}

int main() {
    std::vector<libmmd::pmx::Bone> bones(1);
    bones.front().name = "bone";
    bones.front().parent_index = -1;
    libmmd::vmd::Motion source{};
    source.bone_keyframes.push_back(frame(0, 0.0f, 0.0f, 0.0f));
    source.bone_keyframes.push_back(frame(30, 1.0f, 2.0f, 3.0f));
    libmmd::MotionClip clip(bones, source);
    assert(clip.duration_frames() == 30);
    assert(clip.bound_bone_count() == 1);

    libmmd::Pose pose(bones);
    assert(clip.apply(0.5f, false, pose));
    const auto matrices = pose.skinning_matrices();
    assert(std::abs(matrices[12] - 0.5f) < 0.001f);
    assert(std::abs(matrices[13] - 1.0f) < 0.001f);
    assert(std::abs(matrices[14] + 1.5f) < 0.001f);
    assert(clip.apply(1.0f, true, pose));
    assert(std::abs(pose.skinning_matrices()[12]) < 0.001f);
    assert(!clip.apply(std::numeric_limits<float>::quiet_NaN(), false, pose));
    return 0;
}
