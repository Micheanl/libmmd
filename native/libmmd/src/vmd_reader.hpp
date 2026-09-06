#ifndef LIBMMD_VMD_READER_HPP_
#define LIBMMD_VMD_READER_HPP_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <variant>
#include <vector>

namespace libmmd::vmd {

struct ParseError {
    std::size_t offset;
    std::string message;
};

struct Limits {
    std::uint32_t max_keyframes = 10'000'000;
    std::uint32_t max_ik_states = 1'000'000;
};

struct BoneKeyframe {
    std::array<std::byte, 15> name{};
    std::uint32_t frame = 0;
    std::array<float, 3> translation{};
    std::array<float, 4> rotation{};
    std::array<std::byte, 64> interpolation{};
};

struct MorphKeyframe {
    std::array<std::byte, 15> name{};
    std::uint32_t frame = 0;
    float weight = 0.0f;
};

struct IkState {
    std::array<std::byte, 20> name{};
    bool enabled = true;
};

struct IkKeyframe {
    std::uint32_t frame = 0;
    bool visible = true;
    std::vector<IkState> states;
};

struct Motion {
    std::array<std::byte, 20> model_name{};
    std::vector<BoneKeyframe> bone_keyframes;
    std::vector<MorphKeyframe> morph_keyframes;
    std::uint32_t camera_keyframe_count = 0;
    std::uint32_t light_keyframe_count = 0;
    std::uint32_t shadow_keyframe_count = 0;
    std::uint32_t ik_keyframe_count = 0;
    std::vector<IkKeyframe> ik_keyframes;
};

using MotionResult = std::variant<Motion, ParseError>;

[[nodiscard]] MotionResult read_motion(std::span<const std::byte> bytes, Limits limits = {});

}

#endif
