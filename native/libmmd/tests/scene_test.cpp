#include "native/libmmd/src/scene.hpp"

#include <array>
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

libmmd::vmd::BoneKeyframe frame(const std::uint32_t index, const float translation) {
    libmmd::vmd::BoneKeyframe value{};
    name(value.name, "root");
    value.frame = index;
    value.translation = {translation, 0.0f, 0.0f};
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
    bones.front().name = "root";
    bones.front().parent_index = -1;

    libmmd::vmd::Motion source{};
    source.bone_keyframes.push_back(frame(0, 0.0f));
    source.bone_keyframes.push_back(frame(30, 2.0f));
    libmmd::MotionClip motion(bones, source);

    libmmd::Scene scene(0.25f);
    auto& instance = scene.create_instance(bones);
    assert(scene.instance_count() == 1);
    assert(instance.set_transform({
        .position = {1.0f, 2.0f, 3.0f},
        .rotation = {0.0f, 0.0f, 0.0f, 2.0f},
        .scale = {0.1f, 0.1f, 0.1f},
    }));
    assert(instance.transform().rotation.w == 1.0f);
    assert(!instance.set_transform({
        .position = {},
        .rotation = {},
        .scale = {1.0f, 1.0f, 0.0f},
    }));

    assert(instance.animation().play(motion, false, 0.5f));
    const auto first = scene.update(0.25f);
    assert(first.frame_index == 1);
    assert(first.instance_count == 1);
    assert(first.animated_instance_count == 1);
    assert(!first.dropped_time);
    assert(std::abs(instance.animation().pose().skinning_matrices()[12] - 0.25f) < 0.001f);

    const auto second = scene.update(0.5f);
    assert(second.frame_index == 2);
    assert(second.dropped_time);
    assert(std::abs(second.delta_seconds - 0.25f) < 0.001f);
    assert(std::abs(instance.animation().pose().skinning_matrices()[12] - 1.0f) < 0.001f);
    assert(instance.animation().stop(0.25f));
    static_cast<void>(scene.update(0.25f));
    assert(std::abs(instance.animation().pose().skinning_matrices()[12]) < 0.001f);
    assert(!instance.animation().play(motion, true, std::numeric_limits<float>::quiet_NaN()));

    scene.destroy_instance(instance);
    assert(scene.instance_count() == 0);
    return 0;
}
