#include "native/libmmd/src/pmx_reader.hpp"

#include <array>
#include <cassert>
#include <cstddef>
#include <cstring>
#include <string_view>
#include <variant>
#include <vector>

namespace {

std::array<std::byte, 17> valid_header() {
    std::array<std::byte, 17> bytes{};
    bytes[0] = std::byte{'P'};
    bytes[1] = std::byte{'M'};
    bytes[2] = std::byte{'X'};
    bytes[3] = std::byte{' '};
    const float version = 2.1f;
    std::memcpy(bytes.data() + 4, &version, sizeof(version));
    bytes[8] = std::byte{8};
    bytes[9] = std::byte{1};
    bytes[10] = std::byte{2};
    bytes[11] = std::byte{4};
    bytes[12] = std::byte{1};
    bytes[13] = std::byte{2};
    bytes[14] = std::byte{4};
    bytes[15] = std::byte{1};
    bytes[16] = std::byte{2};
    return bytes;
}

template <typename T>
void append(std::vector<std::byte>& bytes, const T value) {
    const auto offset = bytes.size();
    bytes.resize(offset + sizeof(T));
    std::memcpy(bytes.data() + offset, &value, sizeof(T));
}

void append_text(std::vector<std::byte>& bytes, const std::string_view value = {}) {
    append(bytes, static_cast<std::int32_t>(value.size()));
    for (const auto character : value) append(bytes, std::byte{static_cast<unsigned char>(character)});
}

void append_floats(std::vector<std::byte>& bytes, const std::size_t count, const float value = 0.0f) {
    for (std::size_t index = 0; index < count; ++index) append(bytes, value);
}

std::vector<std::byte> valid_model() {
    const auto header = valid_header();
    std::vector<std::byte> bytes(header.begin(), header.end());
    bytes[10] = std::byte{0};
    bytes[12] = std::byte{1};
    bytes[13] = std::byte{1};
    bytes[14] = std::byte{1};
    bytes[15] = std::byte{1};
    bytes[16] = std::byte{1};
    append_text(bytes, "model");
    append_text(bytes);
    append_text(bytes);
    append_text(bytes);

    append(bytes, std::int32_t{1});
    append_floats(bytes, 8);
    append(bytes, std::uint8_t{0});
    append(bytes, std::int8_t{0});
    append(bytes, 1.0f);

    append(bytes, std::int32_t{3});
    append(bytes, std::uint32_t{0});
    append(bytes, std::uint32_t{0});
    append(bytes, std::uint32_t{0});
    append(bytes, std::int32_t{0});

    append(bytes, std::int32_t{1});
    append_text(bytes, "material");
    append_text(bytes);
    append_floats(bytes, 11);
    append(bytes, std::uint8_t{0});
    append_floats(bytes, 5);
    append(bytes, std::int8_t{-1});
    append(bytes, std::int8_t{-1});
    append(bytes, std::uint8_t{0});
    append(bytes, std::uint8_t{1});
    append(bytes, std::uint8_t{0});
    append_text(bytes);
    append(bytes, std::int32_t{3});

    append(bytes, std::int32_t{1});
    append_text(bytes, "bone");
    append_text(bytes);
    append_floats(bytes, 3);
    append(bytes, std::int8_t{-1});
    append(bytes, std::int32_t{0});
    append(bytes, std::uint16_t{0});
    append_floats(bytes, 3);

    append(bytes, std::int32_t{1});
    append_text(bytes, "morph");
    append_text(bytes);
    append(bytes, std::uint8_t{1});
    append(bytes, std::uint8_t{1});
    append(bytes, std::int32_t{1});
    append(bytes, std::uint32_t{0});
    append_floats(bytes, 3, 0.25f);

    append(bytes, std::int32_t{0});
    append(bytes, std::int32_t{1});
    append_text(bytes, "body");
    append_text(bytes);
    append(bytes, std::int8_t{0});
    append(bytes, std::uint8_t{0});
    append(bytes, std::uint16_t{0});
    append(bytes, std::uint8_t{0});
    append_floats(bytes, 9, 1.0f);
    append_floats(bytes, 5, 0.5f);
    append(bytes, std::uint8_t{1});

    append(bytes, std::int32_t{1});
    append_text(bytes, "joint");
    append_text(bytes);
    append(bytes, std::uint8_t{0});
    append(bytes, std::int8_t{0});
    append(bytes, std::int8_t{0});
    append_floats(bytes, 24);
    append(bytes, std::int32_t{0});
    return bytes;
}

}

int main() {
    const auto bytes = valid_header();
    const auto result = libmmd::pmx::read_header(bytes);
    assert(std::holds_alternative<libmmd::pmx::Header>(result));
    const auto& header = std::get<libmmd::pmx::Header>(result);
    assert(header.version == 2.1f);
    assert(header.text_encoding == 1);
    assert(header.additional_uv_count == 2);
    assert(header.vertex_index_size == 4);

    const auto truncated = libmmd::pmx::read_header(std::span(bytes).first(8));
    assert(std::holds_alternative<libmmd::pmx::ParseError>(truncated));

    auto invalid = bytes;
    invalid[11] = std::byte{3};
    const auto invalid_result = libmmd::pmx::read_header(invalid);
    assert(std::holds_alternative<libmmd::pmx::ParseError>(invalid_result));

    const auto model_result = libmmd::pmx::read_model(valid_model());
    assert(std::holds_alternative<libmmd::pmx::Model>(model_result));
    const auto& model = std::get<libmmd::pmx::Model>(model_result);
    assert(model.vertices.size() == 1);
    assert(model.materials.size() == 1);
    assert(model.bones.size() == 1);
    assert(model.morphs.size() == 1);
    assert(model.morphs.front().vertices.front().translation[0] == 0.25f);
    assert(model.rigid_bodies.size() == 1);
    assert(model.rigid_bodies.front().mode == 1);
    assert(model.joints.size() == 1);

    auto trailing = valid_model();
    trailing.push_back(std::byte{0});
    assert(std::holds_alternative<libmmd::pmx::ParseError>(libmmd::pmx::read_model(trailing)));
    return 0;
}
