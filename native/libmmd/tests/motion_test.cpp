#include "native/libmmd/src/motion.hpp"

#include <cassert>
#include <cmath>
#include <cstring>
#include <limits>
#include <numbers>
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
        value.interpolation[component * 16 + 8] = std::byte{127};
        value.interpolation[component * 16 + 12] = std::byte{127};
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

    auto last = frame(30, 2.0f, 4.0f, 6.0f);
    last.rotation = {0.0f, 0.0f, std::sqrt(0.5f), std::sqrt(0.5f)};
    last.interpolation[4] = std::byte{127};
    last.interpolation[16 + 12] = std::byte{0};
    for (const auto channel : {2u, 3u}) {
        const auto offset = channel * 16;
        last.interpolation[offset] = std::byte{20};
        last.interpolation[offset + 4] = std::byte{20};
        last.interpolation[offset + 8] = std::byte{107};
        last.interpolation[offset + 12] = std::byte{107};
    }
    source.bone_keyframes = {frame(0, 0.0f, 0.0f, 0.0f), last};
    libmmd::MotionClip independent_curves(bones, source);
    assert(independent_curves.apply(0.5f, false, pose));
    const auto interpolated = pose.skinning_matrices();
    assert(std::abs(interpolated[12] - 1.75f) < 0.001f);
    assert(std::abs(interpolated[13] - 0.5f) < 0.001f);
    assert(std::abs(interpolated[14] + 3.0f) < 0.001f);
    assert(std::abs(interpolated[0] - std::sqrt(0.5f)) < 0.001f);
    assert(std::abs(interpolated[1] - std::sqrt(0.5f)) < 0.001f);

    last.interpolation[48 + 12] = std::byte{0};
    last.interpolation[48] = std::byte{0};
    last.interpolation[48 + 4] = std::byte{0};
    last.interpolation[48 + 8] = std::byte{127};
    source.bone_keyframes.back() = last;
    libmmd::MotionClip eased_rotation(bones, source);
    assert(eased_rotation.apply(0.5f, false, pose));
    assert(std::abs(pose.skinning_matrices()[0] - std::cos(std::numbers::pi_v<float> / 16.0f)) < 0.001f);
    assert(std::abs(pose.skinning_matrices()[1] - std::sin(std::numbers::pi_v<float> / 16.0f)) < 0.001f);
    assert(eased_rotation.apply(0.0f, false, pose));
    assert(pose.skinning_matrices()[0] == 1.0f);
    assert(eased_rotation.apply(1.0f, false, pose));
    assert(std::abs(pose.skinning_matrices()[0]) < 0.001f);
    assert(std::abs(pose.skinning_matrices()[1] - 1.0f) < 0.001f);

    last.interpolation.fill(std::byte{0});
    for (std::size_t channel = 0; channel < 4; ++channel) {
        last.interpolation[channel + 4] = std::byte{127};
        last.interpolation[channel + 8] = std::byte{127};
        last.interpolation[channel + 12] = std::byte{127};
    }
    source.bone_keyframes.back() = last;
    libmmd::MotionClip compact_curves(bones, source);
    assert(compact_curves.apply(0.5f, false, pose));
    assert(std::abs(pose.skinning_matrices()[12] - 1.75f) < 0.001f);
    assert(std::abs(pose.skinning_matrices()[13] - 3.5f) < 0.001f);
    assert(std::abs(pose.skinning_matrices()[14] + 5.25f) < 0.001f);
    assert(std::abs(pose.skinning_matrices()[0] - std::cos(7.0f * std::numbers::pi_v<float> / 16.0f)) < 0.001f);
    assert(std::abs(pose.skinning_matrices()[1] - std::sin(7.0f * std::numbers::pi_v<float> / 16.0f)) < 0.001f);
    return 0;
}
