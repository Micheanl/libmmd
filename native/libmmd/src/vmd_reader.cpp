#include "native/libmmd/src/vmd_reader.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <new>
#include <stdexcept>
#include <string_view>

namespace libmmd::vmd {
namespace {

static_assert(std::endian::native == std::endian::little);

class Failure final : public std::runtime_error {
public:
    Failure(const std::size_t position, std::string message)
        : std::runtime_error(std::move(message)), position(position) {}

    std::size_t position;
};

class Reader final {
public:
    explicit Reader(const std::span<const std::byte> bytes) : bytes_(bytes) {}

    template <typename T>
    T read(const std::string_view label) {
        if (remaining() < sizeof(T)) {
            fail(std::string(label) + " is truncated");
        }
        T value{};
        std::memcpy(&value, bytes_.data() + position_, sizeof(T));
        position_ += sizeof(T);
        return value;
    }

    template <std::size_t Size>
    std::array<std::byte, Size> fixed(const std::string_view label) {
        if (remaining() < Size) {
            fail(std::string(label) + " is truncated");
        }
        std::array<std::byte, Size> value{};
        std::memcpy(value.data(), bytes_.data() + position_, Size);
        position_ += Size;
        return value;
    }

    void skip(const std::size_t count, const std::string_view label) {
        if (remaining() < count) {
            fail(std::string(label) + " is truncated");
        }
        position_ += count;
    }

    [[nodiscard]] std::size_t remaining() const noexcept { return bytes_.size() - position_; }
    [[nodiscard]] std::size_t position() const noexcept { return position_; }

    [[noreturn]] void fail(std::string message) const {
        throw Failure(position_, std::move(message));
    }

private:
    std::span<const std::byte> bytes_;
    std::size_t position_ = 0;
};

std::uint32_t count(Reader& reader, const Limits& limits, const std::string_view label) {
    const auto value = reader.read<std::uint32_t>(label);
    if (value > limits.max_keyframes) {
        reader.fail(std::string(label) + " exceeds the configured limit");
    }
    return value;
}

template <std::size_t Size>
std::array<float, Size> floats(Reader& reader, const std::string_view label) {
    std::array<float, Size> output{};
    for (auto& value : output) {
        value = reader.read<float>(label);
        if (!std::isfinite(value)) {
            reader.fail(std::string(label) + " contains a non-finite value");
        }
    }
    return output;
}

Motion parse(Reader& reader, const Limits& limits) {
    constexpr std::string_view signature = "Vocaloid Motion Data 0002";
    const auto header = reader.fixed<30>("VMD signature");
    if (!std::equal(signature.begin(), signature.end(), reinterpret_cast<const char*>(header.data()))) {
        throw Failure(0, "VMD signature is unsupported");
    }

    Motion motion{};
    motion.model_name = reader.fixed<20>("VMD model name");
    const auto bone_count = count(reader, limits, "VMD bone keyframe count");
    motion.bone_keyframes.reserve(bone_count);
    for (std::uint32_t index = 0; index < bone_count; ++index) {
        BoneKeyframe keyframe{};
        keyframe.name = reader.fixed<15>("VMD bone name");
        keyframe.frame = reader.read<std::uint32_t>("VMD bone frame");
        keyframe.translation = floats<3>(reader, "VMD bone translation");
        keyframe.rotation = floats<4>(reader, "VMD bone rotation");
        keyframe.interpolation = reader.fixed<64>("VMD bone interpolation");
        motion.bone_keyframes.push_back(keyframe);
    }
    if (reader.remaining() == 0) {
        return motion;
    }

    const auto morph_count = count(reader, limits, "VMD morph keyframe count");
    motion.morph_keyframes.reserve(morph_count);
    for (std::uint32_t index = 0; index < morph_count; ++index) {
        MorphKeyframe keyframe{};
        keyframe.name = reader.fixed<15>("VMD morph name");
        keyframe.frame = reader.read<std::uint32_t>("VMD morph frame");
        keyframe.weight = reader.read<float>("VMD morph weight");
        if (!std::isfinite(keyframe.weight)) {
            reader.fail("VMD morph weight is not finite");
        }
        motion.morph_keyframes.push_back(keyframe);
    }
    if (reader.remaining() == 0) {
        return motion;
    }

    motion.camera_keyframe_count = count(reader, limits, "VMD camera keyframe count");
    reader.skip(static_cast<std::size_t>(motion.camera_keyframe_count) * 61, "VMD camera keyframes");
    if (reader.remaining() == 0) {
        return motion;
    }

    motion.light_keyframe_count = count(reader, limits, "VMD light keyframe count");
    reader.skip(static_cast<std::size_t>(motion.light_keyframe_count) * 28, "VMD light keyframes");
    if (reader.remaining() == 0) {
        return motion;
    }

    motion.shadow_keyframe_count = count(reader, limits, "VMD shadow keyframe count");
    reader.skip(static_cast<std::size_t>(motion.shadow_keyframe_count) * 9, "VMD shadow keyframes");
    if (reader.remaining() == 0) {
        return motion;
    }

    motion.ik_keyframe_count = count(reader, limits, "VMD IK keyframe count");
    motion.ik_keyframes.reserve(motion.ik_keyframe_count);
    std::uint64_t total_states = 0;
    for (std::uint32_t frame = 0; frame < motion.ik_keyframe_count; ++frame) {
        IkKeyframe keyframe{};
        keyframe.frame = reader.read<std::uint32_t>("VMD IK frame");
        const auto visible = reader.read<std::uint8_t>("VMD model visibility");
        if (visible > 1) reader.fail("VMD model visibility flag is invalid");
        keyframe.visible = visible == 1;
        const auto state_count = reader.read<std::uint32_t>("VMD IK state count");
        total_states += state_count;
        if (total_states > limits.max_ik_states) {
            reader.fail("VMD IK state count exceeds the configured limit");
        }
        keyframe.states.reserve(state_count);
        for (std::uint32_t state_index = 0; state_index < state_count; ++state_index) {
            IkState state{};
            state.name = reader.fixed<20>("VMD IK bone name");
            const auto enabled = reader.read<std::uint8_t>("VMD IK enabled flag");
            if (enabled > 1) reader.fail("VMD IK enabled flag is invalid");
            state.enabled = enabled == 1;
            keyframe.states.push_back(state);
        }
        motion.ik_keyframes.push_back(std::move(keyframe));
    }
    if (reader.remaining() != 0) {
        reader.fail("VMD has trailing data");
    }
    return motion;
}

}

MotionResult read_motion(const std::span<const std::byte> bytes, const Limits limits) {
    try {
        Reader reader(bytes);
        return parse(reader, limits);
    } catch (const Failure& failure) {
        return ParseError{failure.position, failure.what()};
    } catch (const std::bad_alloc&) {
        return ParseError{0, "VMD allocation failed"};
    }
}

}
