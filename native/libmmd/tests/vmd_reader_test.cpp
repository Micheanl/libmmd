#include "native/libmmd/src/vmd_reader.hpp"

#include <array>
#include <cassert>
#include <cstddef>
#include <cstring>
#include <vector>

namespace {

template <typename T>
void append(std::vector<std::byte>& bytes, const T value) {
    const auto size = bytes.size();
    bytes.resize(size + sizeof(T));
    std::memcpy(bytes.data() + size, &value, sizeof(T));
}

void append_fixed(std::vector<std::byte>& bytes, const char* text, const std::size_t count) {
    const auto size = bytes.size();
    bytes.resize(size + count);
    std::memcpy(bytes.data() + size, text, std::strlen(text));
}

std::vector<std::byte> valid_motion() {
    std::vector<std::byte> bytes;
    append_fixed(bytes, "Vocaloid Motion Data 0002", 30);
    append_fixed(bytes, "model", 20);
    append<std::uint32_t>(bytes, 1);
    append_fixed(bytes, "bone", 15);
    append<std::uint32_t>(bytes, 12);
    for (int index = 0; index < 3; ++index) append<float>(bytes, static_cast<float>(index));
    append<float>(bytes, 0.0f);
    append<float>(bytes, 0.0f);
    append<float>(bytes, 0.0f);
    append<float>(bytes, 1.0f);
    bytes.resize(bytes.size() + 64);
    append<std::uint32_t>(bytes, 0);
    append<std::uint32_t>(bytes, 0);
    append<std::uint32_t>(bytes, 0);
    append<std::uint32_t>(bytes, 0);
    append<std::uint32_t>(bytes, 1);
    append<std::uint32_t>(bytes, 18);
    append<std::uint8_t>(bytes, 1);
    append<std::uint32_t>(bytes, 1);
    append_fixed(bytes, "leg ik", 20);
    append<std::uint8_t>(bytes, 0);
    return bytes;
}

}

int main() {
    const auto bytes = valid_motion();
    const auto result = libmmd::vmd::read_motion(bytes);
    assert(std::holds_alternative<libmmd::vmd::Motion>(result));
    const auto& motion = std::get<libmmd::vmd::Motion>(result);
    assert(motion.bone_keyframes.size() == 1);
    assert(motion.bone_keyframes.front().frame == 12);
    assert(motion.bone_keyframes.front().rotation[3] == 1.0f);
    assert(motion.ik_keyframes.size() == 1);
    assert(motion.ik_keyframes.front().frame == 18);
    assert(motion.ik_keyframes.front().visible);
    assert(motion.ik_keyframes.front().states.size() == 1);
    assert(!motion.ik_keyframes.front().states.front().enabled);

    const auto truncated = libmmd::vmd::read_motion(std::span(bytes).first(49));
    assert(std::holds_alternative<libmmd::vmd::ParseError>(truncated));
    return 0;
}
