#include "native/libmmd/src/pmx_reader.hpp"

#include <array>
#include <cassert>
#include <cstddef>
#include <cstring>
#include <limits>
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

struct IndexWidths {
    std::uint8_t vertex = 4;
    std::uint8_t material = 1;
    std::uint8_t rigid_body = 1;
};

void append_signed_index(std::vector<std::byte>& bytes, const std::int32_t value, const std::uint8_t width) {
    switch (width) {
        case 1: append(bytes, static_cast<std::int8_t>(value)); break;
        case 2: append(bytes, static_cast<std::int16_t>(value)); break;
        case 4: append(bytes, value); break;
        default: assert(false);
    }
}

void append_vertex_index(std::vector<std::byte>& bytes, const std::uint32_t value, const std::uint8_t width) {
    switch (width) {
        case 1: append(bytes, static_cast<std::uint8_t>(value)); break;
        case 2: append(bytes, static_cast<std::uint16_t>(value)); break;
        case 4: append(bytes, value); break;
        default: assert(false);
    }
}

std::vector<std::byte> valid_model(const IndexWidths widths = {}, const std::int32_t vertex_count = 1) {
    const auto header = valid_header();
    std::vector<std::byte> bytes(header.begin(), header.end());
    bytes[10] = std::byte{0};
    bytes[11] = std::byte{widths.vertex};
    bytes[12] = std::byte{1};
    bytes[13] = std::byte{widths.material};
    bytes[14] = std::byte{1};
    bytes[15] = std::byte{1};
    bytes[16] = std::byte{widths.rigid_body};
    append_text(bytes, "model");
    append_text(bytes);
    append_text(bytes);
    append_text(bytes);

    append(bytes, vertex_count);
    for (std::int32_t index = 0; index < vertex_count; ++index) {
        append_floats(bytes, 8);
        append(bytes, std::uint8_t{0});
        append(bytes, std::int8_t{0});
        append(bytes, 1.0f);
    }

    append(bytes, std::int32_t{3});
    append_vertex_index(bytes, 0, widths.vertex);
    append_vertex_index(bytes, 0, widths.vertex);
    append_vertex_index(bytes, 0, widths.vertex);
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
    append_vertex_index(bytes, 0, widths.vertex);
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
    append_signed_index(bytes, 0, widths.rigid_body);
    append_signed_index(bytes, 0, widths.rigid_body);
    append_floats(bytes, 24);
    append(bytes, std::int32_t{0});
    return bytes;
}

libmmd::pmx::SoftBody sample_soft_body(const std::uint8_t shape = 0) {
    return {
        .name = shape == 0 ? "mesh" : "rope",
        .english_name = shape == 0 ? "mesh english" : "rope english",
        .shape = shape,
        .material_index = 0,
        .collision_group = 15,
        .collision_mask = 0x8192,
        .flags = 7,
        .link_distance = 2,
        .cluster_count = 3,
        .mass = 12.5f,
        .collision_margin = 0.125f,
        .aero_model = 4,
        .configuration = {0.125f, 0.25f, 0.375f, 0.5f, -2.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f},
        .cluster_configuration = {3.0f, 3.25f, 3.5f, 3.75f, 4.0f, 4.25f},
        .solver_iterations = {0, 1, 2, 3},
        .material_coefficients = {4.5f, 4.75f, 5.0f},
        .anchors = {{0, 0, false}, {0, 0, true}},
        .pinned_vertices = {0},
    };
}

struct SoftBodyOffsets {
    std::size_t anchor_count = 0;
    std::size_t first_anchor_mode = 0;
    std::size_t pin_count = 0;
};

struct SoftBodyFixture {
    std::vector<std::byte> bytes;
    std::size_t count_offset = 0;
    std::vector<SoftBodyOffsets> bodies;
};

SoftBodyFixture soft_body_model(
    const std::span<const libmmd::pmx::SoftBody> bodies,
    const IndexWidths widths = {},
    const std::int32_t vertex_count = 1) {
    SoftBodyFixture fixture{.bytes = valid_model(widths, vertex_count)};
    auto& bytes = fixture.bytes;
    bytes.resize(bytes.size() - sizeof(std::int32_t));
    fixture.count_offset = bytes.size();
    append(bytes, static_cast<std::int32_t>(bodies.size()));
    for (const auto& body : bodies) {
        append_text(bytes, body.name);
        append_text(bytes, body.english_name);
        append(bytes, body.shape);
        append_signed_index(bytes, body.material_index, widths.material);
        append(bytes, body.collision_group);
        append(bytes, body.collision_mask);
        append(bytes, body.flags);
        append(bytes, body.link_distance);
        append(bytes, body.cluster_count);
        append(bytes, body.mass);
        append(bytes, body.collision_margin);
        append(bytes, body.aero_model);
        for (const auto value : body.configuration) append(bytes, value);
        for (const auto value : body.cluster_configuration) append(bytes, value);
        for (const auto value : body.solver_iterations) append(bytes, value);
        for (const auto value : body.material_coefficients) append(bytes, value);
        SoftBodyOffsets offsets{.anchor_count = bytes.size()};
        append(bytes, static_cast<std::int32_t>(body.anchors.size()));
        offsets.first_anchor_mode = bytes.size() + widths.rigid_body + widths.vertex;
        for (const auto& anchor : body.anchors) {
            append_signed_index(bytes, anchor.rigid_body_index, widths.rigid_body);
            append_vertex_index(bytes, anchor.vertex_index, widths.vertex);
            append(bytes, static_cast<std::uint8_t>(anchor.near_mode));
        }
        offsets.pin_count = bytes.size();
        append(bytes, static_cast<std::int32_t>(body.pinned_vertices.size()));
        for (const auto vertex : body.pinned_vertices) append_vertex_index(bytes, vertex, widths.vertex);
        fixture.bodies.push_back(offsets);
    }
    return fixture;
}

template <typename T>
void replace(std::vector<std::byte>& bytes, const std::size_t offset, const T value) {
    assert(offset + sizeof(value) <= bytes.size());
    std::memcpy(bytes.data() + offset, &value, sizeof(value));
}

void expect_error(
    const std::span<const std::byte> bytes,
    const std::string_view message,
    const libmmd::pmx::Limits limits = {}) {
    const auto result = libmmd::pmx::read_model(bytes, limits);
    assert(std::holds_alternative<libmmd::pmx::ParseError>(result));
    const auto& error = std::get<libmmd::pmx::ParseError>(result);
    assert(error.offset <= bytes.size());
    assert(error.message.find(message) != std::string::npos);
}

void test_soft_body_fields_and_index_widths() {
    const std::array bodies{sample_soft_body(), sample_soft_body(1)};
    constexpr std::array<std::uint8_t, 3> widths{1, 2, 4};
    for (const auto vertex : widths) {
        for (const auto material : widths) {
            for (const auto rigid_body : widths) {
                const auto fixture = soft_body_model(bodies, {vertex, material, rigid_body});
                const auto result = libmmd::pmx::read_model(fixture.bytes);
                assert(std::holds_alternative<libmmd::pmx::Model>(result));
                assert(std::get<libmmd::pmx::Model>(result).soft_bodies ==
                    std::vector<libmmd::pmx::SoftBody>(bodies.begin(), bodies.end()));
                for (const auto invalid_index : {-1, 1}) {
                    auto invalid_material = bodies;
                    invalid_material.front().material_index = invalid_index;
                    expect_error(soft_body_model(invalid_material, {vertex, material, rigid_body}).bytes,
                        "soft body material index");
                    auto invalid_anchor = bodies;
                    invalid_anchor.front().anchors.front().rigid_body_index = invalid_index;
                    expect_error(soft_body_model(invalid_anchor, {vertex, material, rigid_body}).bytes,
                        "anchor rigid body index");
                }
                auto invalid_vertex = bodies;
                invalid_vertex.front().anchors.front().vertex_index = 1;
                expect_error(soft_body_model(invalid_vertex, {vertex, material, rigid_body}).bytes,
                    "anchor vertex index");
                invalid_vertex = bodies;
                invalid_vertex.front().pinned_vertices.front() = 1;
                expect_error(soft_body_model(invalid_vertex, {vertex, material, rigid_body}).bytes,
                    "pin vertex index");
            }
        }
    }

    for (const auto width : widths) {
        auto body = sample_soft_body();
        const std::uint32_t vertex_index = width == 1 ? 255 : 32768;
        body.anchors.front().vertex_index = vertex_index;
        body.pinned_vertices.front() = vertex_index;
        const auto fixture = soft_body_model(std::span(&body, 1), {width, 1, 1},
            static_cast<std::int32_t>(vertex_index + 1));
        const auto result = libmmd::pmx::read_model(fixture.bytes);
        assert(std::holds_alternative<libmmd::pmx::Model>(result));
        assert(std::get<libmmd::pmx::Model>(result).soft_bodies.front() == body);
    }
}

void test_soft_body_invalid_parameters() {
    const auto invalid_body = [](const auto change, const std::string_view message) {
        auto body = sample_soft_body();
        change(body);
        expect_error(soft_body_model(std::span(&body, 1)).bytes, message);
    };
    invalid_body([](auto& body) { body.shape = 2; }, "shape");
    invalid_body([](auto& body) { body.collision_group = 16; }, "collision group");
    invalid_body([](auto& body) { body.flags = 8; }, "flags");
    invalid_body([](auto& body) { body.aero_model = -1; }, "aero model");
    invalid_body([](auto& body) { body.aero_model = 5; }, "aero model");
    invalid_body([](auto& body) { body.link_distance = -1; }, "link distance");
    invalid_body([](auto& body) { body.cluster_count = -1; }, "cluster count");
    for (std::size_t index = 0; index < 4; ++index) {
        invalid_body([index](auto& body) { body.solver_iterations[index] = -1; }, "solver iterations");
    }
    for (const auto value : {
        std::numeric_limits<float>::infinity(),
        -std::numeric_limits<float>::infinity(),
        std::numeric_limits<float>::quiet_NaN(),
    }) {
        invalid_body([value](auto& body) { body.mass = value; }, "non-finite");
        invalid_body([value](auto& body) { body.collision_margin = value; }, "non-finite");
        for (std::size_t index = 0; index < 12; ++index) {
            invalid_body([index, value](auto& body) { body.configuration[index] = value; }, "non-finite");
        }
        for (std::size_t index = 0; index < 6; ++index) {
            invalid_body([index, value](auto& body) { body.cluster_configuration[index] = value; }, "non-finite");
        }
        for (std::size_t index = 0; index < 3; ++index) {
            invalid_body([index, value](auto& body) { body.material_coefficients[index] = value; }, "non-finite");
        }
    }
    const auto body = sample_soft_body();
    auto fixture = soft_body_model(std::span(&body, 1));
    replace(fixture.bytes, fixture.bodies.front().first_anchor_mode, std::uint8_t{2});
    expect_error(fixture.bytes, "anchor mode");
}

void test_soft_body_limits_and_truncation() {
    const std::array bodies{sample_soft_body(), sample_soft_body(1)};
    const auto fixture = soft_body_model(bodies);
    for (const auto offset : {
        fixture.count_offset, fixture.bodies.front().anchor_count, fixture.bodies.front().pin_count,
    }) {
        auto negative_count = fixture.bytes;
        replace(negative_count, offset, std::int32_t{-1});
        expect_error(negative_count, "count is invalid");
    }
    auto oversized_count = fixture.bytes;
    replace(oversized_count, fixture.count_offset, std::int32_t{100'000});
    expect_error(oversized_count, "records are truncated");
    oversized_count = fixture.bytes;
    replace(oversized_count, fixture.bodies.front().anchor_count, std::int32_t{10'000'000});
    expect_error(oversized_count, "anchors are truncated");
    oversized_count = fixture.bytes;
    replace(oversized_count, fixture.bodies.front().pin_count, std::int32_t{9'999'998});
    expect_error(oversized_count, "pins are truncated");
    auto limits = libmmd::pmx::Limits{};
    limits.max_soft_bodies = 1;
    expect_error(fixture.bytes, "soft body count", limits);
    limits.max_soft_bodies = 2;
    limits.max_soft_body_elements = 6;
    assert(std::holds_alternative<libmmd::pmx::Model>(libmmd::pmx::read_model(fixture.bytes, limits)));
    limits.max_soft_body_elements = 5;
    expect_error(fixture.bytes, "elements exceed the limit", limits);
    limits.max_soft_body_elements = 3;
    expect_error(fixture.bytes, "elements exceed the limit", limits);
    limits.max_soft_body_elements = 2;
    expect_error(fixture.bytes, "elements exceed the limit", limits);
    limits.max_soft_body_elements = 1;
    expect_error(fixture.bytes, "anchor count", limits);
    auto pins_only = sample_soft_body();
    pins_only.anchors.clear();
    pins_only.pinned_vertices.push_back(0);
    expect_error(soft_body_model(std::span(&pins_only, 1)).bytes, "pin count", limits);

    for (std::size_t length = fixture.count_offset; length < fixture.bytes.size(); ++length) {
        expect_error(std::span(fixture.bytes).first(length), "truncated");
    }
    auto trailing = fixture.bytes;
    trailing.push_back(std::byte{0});
    expect_error(trailing, "trailing data");

    auto empty_body = sample_soft_body();
    empty_body.anchors.clear();
    empty_body.pinned_vertices.clear();
    const auto empty_fixture = soft_body_model(std::span(&empty_body, 1));
    limits.max_soft_body_elements = 0;
    assert(std::holds_alternative<libmmd::pmx::Model>(libmmd::pmx::read_model(empty_fixture.bytes, limits)));
    const auto empty_section = soft_body_model({});
    limits.max_soft_bodies = 0;
    assert(std::holds_alternative<libmmd::pmx::Model>(libmmd::pmx::read_model(empty_section.bytes, limits)));
}

void test_pmx_20_soft_body_compatibility() {
    auto bytes = valid_model();
    replace(bytes, 4, 2.0f);
    bytes.resize(bytes.size() - sizeof(std::int32_t));
    const auto result = libmmd::pmx::read_model(bytes);
    assert(std::holds_alternative<libmmd::pmx::Model>(result));
    assert(std::get<libmmd::pmx::Model>(result).soft_bodies.empty());
    append(bytes, std::int32_t{0});
    expect_error(bytes, "trailing data");
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

    assert(model.soft_bodies.empty());

    test_soft_body_fields_and_index_widths();
    test_soft_body_invalid_parameters();
    test_soft_body_limits_and_truncation();
    test_pmx_20_soft_body_compatibility();

    auto trailing = valid_model();
    trailing.push_back(std::byte{0});
    assert(std::holds_alternative<libmmd::pmx::ParseError>(libmmd::pmx::read_model(trailing)));
    return 0;
}
