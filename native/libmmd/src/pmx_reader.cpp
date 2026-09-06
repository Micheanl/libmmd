#include "native/libmmd/src/pmx_reader.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <new>
#include <stdexcept>
#include <string_view>

namespace libmmd::pmx {
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
        if (bytes_.size() - position_ < sizeof(T)) {
            fail(std::string(label) + " is truncated");
        }
        T value{};
        std::memcpy(&value, bytes_.data() + position_, sizeof(T));
        position_ += sizeof(T);
        return value;
    }

    std::span<const std::byte> bytes(const std::size_t count, const std::string_view label) {
        if (bytes_.size() - position_ < count) {
            fail(std::string(label) + " is truncated");
        }
        const auto value = bytes_.subspan(position_, count);
        position_ += count;
        return value;
    }

    void skip(const std::size_t count, const std::string_view label) {
        static_cast<void>(bytes(count, label));
    }

    [[nodiscard]] std::size_t position() const noexcept { return position_; }
    [[nodiscard]] std::size_t remaining() const noexcept { return bytes_.size() - position_; }

    [[noreturn]] void fail(std::string message) const {
        throw Failure(position_, std::move(message));
    }

private:
    std::span<const std::byte> bytes_;
    std::size_t position_ = 0;
};

bool valid_index_size(const std::uint8_t size) {
    return size == 1 || size == 2 || size == 4;
}

Header parse_header(Reader& reader) {
    const auto signature = reader.bytes(4, "PMX signature");
    constexpr std::array expected{std::byte{'P'}, std::byte{'M'}, std::byte{'X'}, std::byte{' '}};
    if (!std::equal(signature.begin(), signature.end(), expected.begin())) {
        throw Failure(0, "PMX signature is invalid");
    }

    Header header{};
    header.version = reader.read<float>("PMX version");
    if (header.version < 2.0f || header.version > 2.1f) {
        throw Failure(4, "PMX version is unsupported");
    }
    if (reader.read<std::uint8_t>("PMX globals size") != 8) {
        throw Failure(8, "PMX globals size is unsupported");
    }
    header.text_encoding = reader.read<std::uint8_t>("PMX text encoding");
    header.additional_uv_count = reader.read<std::uint8_t>("PMX additional UV count");
    header.vertex_index_size = reader.read<std::uint8_t>("PMX vertex index size");
    header.texture_index_size = reader.read<std::uint8_t>("PMX texture index size");
    header.material_index_size = reader.read<std::uint8_t>("PMX material index size");
    header.bone_index_size = reader.read<std::uint8_t>("PMX bone index size");
    header.morph_index_size = reader.read<std::uint8_t>("PMX morph index size");
    header.rigid_body_index_size = reader.read<std::uint8_t>("PMX rigid body index size");
    if (header.text_encoding > 1) {
        throw Failure(9, "PMX text encoding is invalid");
    }
    if (header.additional_uv_count > 4) {
        throw Failure(10, "PMX additional UV count is invalid");
    }
    if (!valid_index_size(header.vertex_index_size) || !valid_index_size(header.texture_index_size) ||
        !valid_index_size(header.material_index_size) || !valid_index_size(header.bone_index_size) ||
        !valid_index_size(header.morph_index_size) || !valid_index_size(header.rigid_body_index_size)) {
        throw Failure(11, "PMX index size is invalid");
    }
    return header;
}

void append_utf8(std::string& output, const std::uint32_t codepoint) {
    if (codepoint <= 0x7f) {
        output.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
}

std::string utf16le(const std::span<const std::byte> bytes, const std::size_t start) {
    if (bytes.size() % 2 != 0) {
        throw Failure(start, "PMX UTF-16 text has an odd byte length");
    }
    std::string output;
    output.reserve(bytes.size());
    for (std::size_t index = 0; index < bytes.size(); index += 2) {
        const auto first = std::to_integer<std::uint8_t>(bytes[index]) |
            (static_cast<std::uint32_t>(std::to_integer<std::uint8_t>(bytes[index + 1])) << 8);
        std::uint32_t codepoint = first;
        if (first >= 0xd800 && first <= 0xdbff) {
            if (index + 3 >= bytes.size()) {
                throw Failure(start + index, "PMX UTF-16 surrogate pair is truncated");
            }
            const auto second = std::to_integer<std::uint8_t>(bytes[index + 2]) |
                (static_cast<std::uint32_t>(std::to_integer<std::uint8_t>(bytes[index + 3])) << 8);
            if (second < 0xdc00 || second > 0xdfff) {
                throw Failure(start + index, "PMX UTF-16 surrogate pair is invalid");
            }
            codepoint = 0x10000 + ((first - 0xd800) << 10) + second - 0xdc00;
            index += 2;
        } else if (first >= 0xdc00 && first <= 0xdfff) {
            throw Failure(start + index, "PMX UTF-16 trailing surrogate is invalid");
        }
        append_utf8(output, codepoint);
    }
    return output;
}

std::string read_text(Reader& reader, const Header& header, const Limits& limits) {
    const auto length = reader.read<std::int32_t>("PMX text length");
    if (length < 0 || static_cast<std::uint32_t>(length) > limits.max_string_bytes) {
        reader.fail("PMX text length is invalid");
    }
    const auto start = reader.position();
    const auto data = reader.bytes(static_cast<std::size_t>(length), "PMX text");
    if (header.text_encoding == 0) {
        return utf16le(data, start);
    }
    return {reinterpret_cast<const char*>(data.data()), data.size()};
}

template <std::size_t Size>
std::array<float, Size> read_floats(Reader& reader, const std::string_view label) {
    std::array<float, Size> output{};
    for (auto& value : output) {
        value = reader.read<float>(label);
        if (!std::isfinite(value)) {
            reader.fail(std::string(label) + " contains a non-finite value");
        }
    }
    return output;
}

std::uint32_t read_count(Reader& reader, const std::uint32_t limit, const std::string_view label) {
    const auto value = reader.read<std::int32_t>(label);
    if (value < 0 || static_cast<std::uint32_t>(value) > limit) {
        reader.fail(std::string(label) + " is invalid");
    }
    return static_cast<std::uint32_t>(value);
}

std::int32_t signed_index(Reader& reader, const std::uint8_t size, const std::string_view label) {
    switch (size) {
        case 1: return reader.read<std::int8_t>(label);
        case 2: return reader.read<std::int16_t>(label);
        case 4: return reader.read<std::int32_t>(label);
        default: reader.fail(std::string(label) + " has an invalid width");
    }
}

std::uint32_t unsigned_index(Reader& reader, const std::uint8_t size, const std::string_view label) {
    switch (size) {
        case 1: return reader.read<std::uint8_t>(label);
        case 2: return reader.read<std::uint16_t>(label);
        case 4: return reader.read<std::uint32_t>(label);
        default: reader.fail(std::string(label) + " has an invalid width");
    }
}

void read_skinning(Reader& reader, const Header& header, Vertex& vertex) {
    const auto type = reader.read<std::uint8_t>("PMX vertex skinning type");
    const auto bone = [&] { return signed_index(reader, header.bone_index_size, "PMX vertex bone index"); };
    if (type == 0) {
        vertex.bone_indices[0] = bone();
        vertex.bone_weights[0] = 1.0f;
    } else if (type == 1 || type == 3) {
        vertex.bone_indices[0] = bone();
        vertex.bone_indices[1] = bone();
        vertex.bone_weights[0] = reader.read<float>("PMX BDEF2 weight");
        vertex.bone_weights[1] = 1.0f - vertex.bone_weights[0];
        if (type == 3) {
            reader.skip(9 * sizeof(float), "PMX SDEF parameters");
        }
    } else if (type == 2 || type == 4) {
        for (auto& index : vertex.bone_indices) {
            index = bone();
        }
        vertex.bone_weights = read_floats<4>(reader, "PMX BDEF4 weights");
    } else {
        reader.fail("PMX vertex skinning type is unsupported");
    }
}

float read_float(Reader& reader, const std::string_view label) {
    return read_floats<1>(reader, label)[0];
}

void read_bone_payload(Reader& reader, const Header& header, const Limits& limits, Bone& bone) {
    if ((bone.flags & 0x0001) != 0) {
        bone.connection_index = signed_index(reader, header.bone_index_size, "PMX bone connection index");
    } else {
        bone.connection_offset = read_floats<3>(reader, "PMX bone connection offset");
    }
    if ((bone.flags & 0x0300) != 0) {
        bone.inheritance_index = signed_index(reader, header.bone_index_size, "PMX append bone index");
        bone.inheritance_weight = read_float(reader, "PMX append ratio");
    }
    if ((bone.flags & 0x0400) != 0) {
        bone.fixed_axis = read_floats<3>(reader, "PMX fixed axis");
    }
    if ((bone.flags & 0x0800) != 0) {
        bone.local_x_axis = read_floats<3>(reader, "PMX local X axis");
        bone.local_z_axis = read_floats<3>(reader, "PMX local Z axis");
    }
    if ((bone.flags & 0x2000) != 0) {
        bone.external_parent_key = reader.read<std::int32_t>("PMX external parent key");
    }
    if ((bone.flags & 0x0020) != 0) {
        bone.ik_target_index = signed_index(reader, header.bone_index_size, "PMX IK target");
        bone.ik_iteration_count = reader.read<std::int32_t>("PMX IK loop count");
        if (bone.ik_iteration_count < 0) {
            reader.fail("PMX IK loop count is invalid");
        }
        bone.ik_angle_limit = read_float(reader, "PMX IK angle limit");
        const auto link_count = read_count(reader, limits.max_ik_links, "PMX IK link count");
        bone.ik_links.reserve(link_count);
        for (std::uint32_t link = 0; link < link_count; ++link) {
            Bone::IkLink value{};
            value.bone_index = signed_index(reader, header.bone_index_size, "PMX IK link bone");
            const auto limited = reader.read<std::uint8_t>("PMX IK link limit flag");
            if (limited > 1) {
                reader.fail("PMX IK link limit flag is invalid");
            }
            value.limited = limited == 1;
            if (value.limited) {
                value.lower_limit = read_floats<3>(reader, "PMX IK lower limit");
                value.upper_limit = read_floats<3>(reader, "PMX IK upper limit");
            }
            bone.ik_links.push_back(value);
        }
    }
}

void read_morph_offset(
    Reader& reader,
    const Header& header,
    const std::uint8_t type,
    const std::uint32_t vertex_count,
    const std::uint32_t bone_count,
    const std::uint32_t material_count,
    Morph& morph) {
    if (type == 0 || type == 9) {
        morph.groups.push_back({
            signed_index(reader, header.morph_index_size, "PMX morph group index"),
            read_float(reader, "PMX morph group weight"),
        });
    } else if (type == 1) {
        const auto index = unsigned_index(reader, header.vertex_index_size, "PMX morph vertex index");
        if (index >= vertex_count) reader.fail("PMX morph vertex index is out of range");
        morph.vertices.push_back({index, read_floats<3>(reader, "PMX vertex morph translation")});
    } else if (type == 2) {
        const auto index = signed_index(reader, header.bone_index_size, "PMX morph bone index");
        if (index < 0 || static_cast<std::uint32_t>(index) >= bone_count) {
            reader.fail("PMX morph bone index is out of range");
        }
        morph.bones.push_back({
            index,
            read_floats<3>(reader, "PMX bone morph translation"),
            read_floats<4>(reader, "PMX bone morph rotation"),
        });
    } else if (type >= 3 && type <= 7) {
        const auto index = unsigned_index(reader, header.vertex_index_size, "PMX UV morph vertex index");
        if (index >= vertex_count) reader.fail("PMX UV morph vertex index is out of range");
        morph.uvs.push_back({index, read_floats<4>(reader, "PMX UV morph offset")});
    } else if (type == 8) {
        MaterialMorphOffset offset{};
        offset.material_index = signed_index(reader, header.material_index_size, "PMX morph material index");
        if (offset.material_index < -1 ||
            (offset.material_index >= 0 && static_cast<std::uint32_t>(offset.material_index) >= material_count)) {
            reader.fail("PMX morph material index is out of range");
        }
        offset.operation = reader.read<std::uint8_t>("PMX material morph operation");
        if (offset.operation > 1) reader.fail("PMX material morph operation is invalid");
        offset.diffuse = read_floats<4>(reader, "PMX material morph diffuse");
        offset.specular = read_floats<3>(reader, "PMX material morph specular");
        offset.specular_strength = read_float(reader, "PMX material morph specular strength");
        offset.ambient = read_floats<3>(reader, "PMX material morph ambient");
        offset.edge_color = read_floats<4>(reader, "PMX material morph edge color");
        offset.edge_scale = read_float(reader, "PMX material morph edge scale");
        offset.texture_tint = read_floats<4>(reader, "PMX material morph texture tint");
        offset.sphere_tint = read_floats<4>(reader, "PMX material morph sphere tint");
        offset.toon_tint = read_floats<4>(reader, "PMX material morph toon tint");
        morph.materials.push_back(offset);
    } else if (type == 10) {
        const auto index = signed_index(reader, header.rigid_body_index_size, "PMX impulse rigid body index");
        const auto local = reader.read<std::uint8_t>("PMX impulse local flag");
        if (local > 1) reader.fail("PMX impulse local flag is invalid");
        morph.impulses.push_back({
            index,
            local == 1,
            read_floats<3>(reader, "PMX impulse velocity"),
            read_floats<3>(reader, "PMX impulse torque"),
        });
    } else {
        reader.fail("PMX morph type is unsupported");
    }
}

void skip_display_frames(
    Reader& reader,
    const Header& header,
    const Limits& limits,
    const std::uint32_t bone_count,
    const std::uint32_t morph_count) {
    const auto count = read_count(reader, limits.max_display_frames, "PMX display frame count");
    std::uint64_t total_elements = 0;
    for (std::uint32_t frame = 0; frame < count; ++frame) {
        static_cast<void>(read_text(reader, header, limits));
        static_cast<void>(read_text(reader, header, limits));
        const auto special = reader.read<std::uint8_t>("PMX display frame special flag");
        if (special > 1) reader.fail("PMX display frame special flag is invalid");
        const auto elements = read_count(reader, limits.max_display_elements, "PMX display element count");
        total_elements += elements;
        if (total_elements > limits.max_display_elements) reader.fail("PMX display elements exceed the limit");
        for (std::uint32_t element = 0; element < elements; ++element) {
            const auto type = reader.read<std::uint8_t>("PMX display element type");
            if (type == 0) {
                const auto index = signed_index(reader, header.bone_index_size, "PMX display bone index");
                if (index < 0 || static_cast<std::uint32_t>(index) >= bone_count) {
                    reader.fail("PMX display bone index is out of range");
                }
            } else if (type == 1) {
                const auto index = signed_index(reader, header.morph_index_size, "PMX display morph index");
                if (index < 0 || static_cast<std::uint32_t>(index) >= morph_count) {
                    reader.fail("PMX display morph index is out of range");
                }
            } else {
                reader.fail("PMX display element type is invalid");
            }
        }
    }
}

bool nullable_index_in_range(const std::int32_t index, const std::uint32_t count) {
    return index == -1 || (index >= 0 && static_cast<std::uint32_t>(index) < count);
}

void read_soft_bodies(Reader& reader, const Limits& limits, Model& model) {
    const auto& header = model.header;
    if (header.version < 2.1f) return;
    const auto count = read_count(reader, limits.max_soft_bodies, "PMX soft body count");
    constexpr std::size_t minimum_soft_body_bytes = 141;
    if (count > reader.remaining() / (minimum_soft_body_bytes + header.material_index_size)) {
        reader.fail("PMX soft body records are truncated");
    }
    model.soft_bodies.reserve(count);
    std::uint64_t total_elements = 0;
    for (std::uint32_t index = 0; index < count; ++index) {
        SoftBody body{};
        body.name = read_text(reader, header, limits);
        body.english_name = read_text(reader, header, limits);
        body.shape = reader.read<std::uint8_t>("PMX soft body shape");
        if (body.shape > 1) reader.fail("PMX soft body shape is invalid");
        body.material_index = signed_index(reader, header.material_index_size, "PMX soft body material index");
        if (body.material_index < 0 || static_cast<std::size_t>(body.material_index) >= model.materials.size()) {
            reader.fail("PMX soft body material index is out of range");
        }
        body.collision_group = reader.read<std::uint8_t>("PMX soft body collision group");
        if (body.collision_group > 15) reader.fail("PMX soft body collision group is invalid");
        body.collision_mask = reader.read<std::uint16_t>("PMX soft body collision mask");
        body.flags = reader.read<std::uint8_t>("PMX soft body flags");
        if ((body.flags & ~0x07) != 0) reader.fail("PMX soft body flags are invalid");
        body.link_distance = reader.read<std::int32_t>("PMX soft body link distance");
        if (body.link_distance < 0) reader.fail("PMX soft body link distance is invalid");
        body.cluster_count = reader.read<std::int32_t>("PMX soft body cluster count");
        if (body.cluster_count < 0) reader.fail("PMX soft body cluster count is invalid");
        body.mass = read_float(reader, "PMX soft body mass");
        body.collision_margin = read_float(reader, "PMX soft body margin");
        body.aero_model = reader.read<std::int32_t>("PMX soft body aero model");
        if (body.aero_model < 0 || body.aero_model > 4) reader.fail("PMX soft body aero model is invalid");
        body.configuration = read_floats<12>(reader, "PMX soft body configuration");
        body.cluster_configuration = read_floats<6>(reader, "PMX soft body cluster configuration");
        for (auto& iterations : body.solver_iterations) {
            iterations = reader.read<std::int32_t>("PMX soft body solver iterations");
            if (iterations < 0) reader.fail("PMX soft body solver iterations are invalid");
        }
        body.material_coefficients = read_floats<3>(reader, "PMX soft body material coefficients");
        const auto anchors = read_count(reader, limits.max_soft_body_elements, "PMX soft body anchor count");
        total_elements += anchors;
        if (total_elements > limits.max_soft_body_elements) reader.fail("PMX soft body elements exceed the limit");
        const auto anchor_bytes = header.rigid_body_index_size + header.vertex_index_size + 1;
        if (anchors > reader.remaining() / anchor_bytes) reader.fail("PMX soft body anchors are truncated");
        body.anchors.reserve(anchors);
        for (std::uint32_t anchor = 0; anchor < anchors; ++anchor) {
            SoftBodyAnchor value{};
            value.rigid_body_index = signed_index(reader, header.rigid_body_index_size, "PMX soft body anchor rigid body");
            if (value.rigid_body_index < 0 ||
                static_cast<std::size_t>(value.rigid_body_index) >= model.rigid_bodies.size()) {
                reader.fail("PMX soft body anchor rigid body index is out of range");
            }
            value.vertex_index = unsigned_index(reader, header.vertex_index_size, "PMX soft body anchor vertex");
            if (value.vertex_index >= model.vertices.size()) {
                reader.fail("PMX soft body anchor vertex index is out of range");
            }
            const auto near_mode = reader.read<std::uint8_t>("PMX soft body anchor mode");
            if (near_mode > 1) reader.fail("PMX soft body anchor mode is invalid");
            value.near_mode = near_mode == 1;
            body.anchors.push_back(value);
        }
        const auto pins = read_count(reader, limits.max_soft_body_elements, "PMX soft body pin count");
        total_elements += pins;
        if (total_elements > limits.max_soft_body_elements) reader.fail("PMX soft body elements exceed the limit");
        if (pins > reader.remaining() / header.vertex_index_size) reader.fail("PMX soft body pins are truncated");
        body.pinned_vertices.reserve(pins);
        for (std::uint32_t pin = 0; pin < pins; ++pin) {
            const auto vertex_index = unsigned_index(reader, header.vertex_index_size, "PMX soft body pin vertex");
            if (vertex_index >= model.vertices.size()) {
                reader.fail("PMX soft body pin vertex index is out of range");
            }
            body.pinned_vertices.push_back(vertex_index);
        }
        model.soft_bodies.push_back(std::move(body));
    }
}

Model parse_model(Reader& reader, const Limits& limits) {
    Model model{};
    model.header = parse_header(reader);
    model.name = read_text(reader, model.header, limits);
    model.english_name = read_text(reader, model.header, limits);
    model.description = read_text(reader, model.header, limits);
    model.english_description = read_text(reader, model.header, limits);

    const auto vertex_count = read_count(reader, limits.max_vertices, "PMX vertex count");
    model.vertices.reserve(vertex_count);
    for (std::uint32_t index = 0; index < vertex_count; ++index) {
        Vertex vertex{};
        vertex.position = read_floats<3>(reader, "PMX vertex position");
        vertex.normal = read_floats<3>(reader, "PMX vertex normal");
        vertex.uv = read_floats<2>(reader, "PMX vertex UV");
        reader.skip(static_cast<std::size_t>(model.header.additional_uv_count) * 4 * sizeof(float), "PMX additional UVs");
        read_skinning(reader, model.header, vertex);
        vertex.edge_scale = reader.read<float>("PMX edge scale");
        model.vertices.push_back(vertex);
    }

    const auto index_count = read_count(reader, limits.max_indices, "PMX index count");
    model.indices.reserve(index_count);
    for (std::uint32_t index = 0; index < index_count; ++index) {
        const auto value = unsigned_index(reader, model.header.vertex_index_size, "PMX vertex index");
        if (value >= vertex_count) {
            reader.fail("PMX vertex index is out of range");
        }
        model.indices.push_back(value);
    }

    const auto texture_count = read_count(reader, limits.max_textures, "PMX texture count");
    model.textures.reserve(texture_count);
    for (std::uint32_t index = 0; index < texture_count; ++index) {
        model.textures.push_back(read_text(reader, model.header, limits));
    }

    const auto material_count = read_count(reader, limits.max_materials, "PMX material count");
    model.materials.reserve(material_count);
    std::uint64_t covered_indices = 0;
    for (std::uint32_t index = 0; index < material_count; ++index) {
        Material material{};
        material.name = read_text(reader, model.header, limits);
        material.english_name = read_text(reader, model.header, limits);
        material.diffuse = read_floats<4>(reader, "PMX material diffuse");
        material.specular = read_floats<3>(reader, "PMX material specular");
        material.specular_strength = reader.read<float>("PMX material specular strength");
        material.ambient = read_floats<3>(reader, "PMX material ambient");
        material.flags = reader.read<std::uint8_t>("PMX material flags");
        material.edge_color = read_floats<4>(reader, "PMX material edge color");
        material.edge_size = reader.read<float>("PMX material edge size");
        material.texture_index = signed_index(reader, model.header.texture_index_size, "PMX material texture index");
        material.sphere_texture_index = signed_index(reader, model.header.texture_index_size, "PMX sphere texture index");
        material.sphere_mode = reader.read<std::uint8_t>("PMX sphere mode");
        const auto shared_toon = reader.read<std::uint8_t>("PMX shared toon flag");
        if (shared_toon == 0) {
            material.toon_texture_index = signed_index(reader, model.header.texture_index_size, "PMX toon texture index");
        } else if (shared_toon == 1) {
            material.toon_texture_index = reader.read<std::uint8_t>("PMX shared toon texture") + 10;
        } else {
            reader.fail("PMX shared toon flag is invalid");
        }
        material.metadata = read_text(reader, model.header, limits);
        material.index_count = read_count(reader, limits.max_indices, "PMX material index count");
        covered_indices += material.index_count;
        if (covered_indices > index_count) {
            reader.fail("PMX material ranges exceed the index buffer");
        }
        model.materials.push_back(std::move(material));
    }
    if (covered_indices != index_count) {
        reader.fail("PMX material ranges do not cover the index buffer");
    }

    const auto bone_count = read_count(reader, limits.max_bones, "PMX bone count");
    model.bones.reserve(bone_count);
    for (std::uint32_t index = 0; index < bone_count; ++index) {
        Bone bone{};
        bone.name = read_text(reader, model.header, limits);
        bone.english_name = read_text(reader, model.header, limits);
        bone.position = read_floats<3>(reader, "PMX bone position");
        bone.parent_index = signed_index(reader, model.header.bone_index_size, "PMX bone parent index");
        bone.deform_layer = reader.read<std::int32_t>("PMX bone deform layer");
        bone.flags = reader.read<std::uint16_t>("PMX bone flags");
        read_bone_payload(reader, model.header, limits, bone);
        model.bones.push_back(std::move(bone));
    }

    for (const auto& bone : model.bones) {
        if (!nullable_index_in_range(bone.parent_index, bone_count) ||
            ((bone.flags & 0x0001) != 0 && !nullable_index_in_range(bone.connection_index, bone_count)) ||
            ((bone.flags & 0x0300) != 0 && !nullable_index_in_range(bone.inheritance_index, bone_count)) ||
            ((bone.flags & 0x0020) != 0 && !nullable_index_in_range(bone.ik_target_index, bone_count))) {
            reader.fail("PMX bone index is out of range");
        }
        for (const auto& link : bone.ik_links) {
            if (!nullable_index_in_range(link.bone_index, bone_count)) {
                reader.fail("PMX IK link index is out of range");
            }
        }
    }

    const auto morph_count = read_count(reader, limits.max_morphs, "PMX morph count");
    model.morphs.reserve(morph_count);
    std::uint64_t total_morph_offsets = 0;
    for (std::uint32_t index = 0; index < morph_count; ++index) {
        Morph morph{};
        morph.name = read_text(reader, model.header, limits);
        morph.english_name = read_text(reader, model.header, limits);
        morph.panel = reader.read<std::uint8_t>("PMX morph panel");
        morph.type = reader.read<std::uint8_t>("PMX morph type");
        if (morph.panel > 4 || morph.type > (model.header.version >= 2.1f ? 10 : 8)) {
            reader.fail("PMX morph metadata is invalid");
        }
        const auto offset_count = read_count(reader, limits.max_morph_offsets, "PMX morph offset count");
        total_morph_offsets += offset_count;
        if (total_morph_offsets > limits.max_morph_offsets) reader.fail("PMX morph offsets exceed the limit");
        for (std::uint32_t offset = 0; offset < offset_count; ++offset) {
            read_morph_offset(
                reader,
                model.header,
                morph.type,
                vertex_count,
                bone_count,
                material_count,
                morph);
        }
        model.morphs.push_back(std::move(morph));
    }
    for (const auto& morph : model.morphs) {
        for (const auto& group : morph.groups) {
            if (group.morph_index < 0 || static_cast<std::uint32_t>(group.morph_index) >= morph_count) {
                reader.fail("PMX morph group index is out of range");
            }
        }
    }

    skip_display_frames(reader, model.header, limits, bone_count, morph_count);

    const auto rigid_body_count = read_count(reader, limits.max_rigid_bodies, "PMX rigid body count");
    model.rigid_bodies.reserve(rigid_body_count);
    for (std::uint32_t index = 0; index < rigid_body_count; ++index) {
        RigidBody body{};
        body.name = read_text(reader, model.header, limits);
        body.english_name = read_text(reader, model.header, limits);
        body.bone_index = signed_index(reader, model.header.bone_index_size, "PMX rigid body bone index");
        if (!nullable_index_in_range(body.bone_index, bone_count)) {
            reader.fail("PMX rigid body bone index is out of range");
        }
        body.collision_group = reader.read<std::uint8_t>("PMX rigid body collision group");
        if (body.collision_group > 15) reader.fail("PMX rigid body collision group is invalid");
        body.collision_mask = reader.read<std::uint16_t>("PMX rigid body collision mask");
        body.shape = reader.read<std::uint8_t>("PMX rigid body shape");
        if (body.shape > 2) reader.fail("PMX rigid body shape is invalid");
        body.size = read_floats<3>(reader, "PMX rigid body size");
        body.position = read_floats<3>(reader, "PMX rigid body position");
        body.rotation = read_floats<3>(reader, "PMX rigid body rotation");
        body.mass = read_float(reader, "PMX rigid body mass");
        body.linear_damping = read_float(reader, "PMX rigid body linear damping");
        body.angular_damping = read_float(reader, "PMX rigid body angular damping");
        body.restitution = read_float(reader, "PMX rigid body restitution");
        body.friction = read_float(reader, "PMX rigid body friction");
        body.mode = reader.read<std::uint8_t>("PMX rigid body mode");
        if (body.mode > 2) reader.fail("PMX rigid body mode is invalid");
        model.rigid_bodies.push_back(std::move(body));
    }
    for (const auto& morph : model.morphs) {
        for (const auto& impulse : morph.impulses) {
            if (impulse.rigid_body_index < 0 ||
                static_cast<std::uint32_t>(impulse.rigid_body_index) >= rigid_body_count) {
                reader.fail("PMX impulse rigid body index is out of range");
            }
        }
    }

    const auto joint_count = read_count(reader, limits.max_joints, "PMX joint count");
    model.joints.reserve(joint_count);
    for (std::uint32_t index = 0; index < joint_count; ++index) {
        Joint joint{};
        joint.name = read_text(reader, model.header, limits);
        joint.english_name = read_text(reader, model.header, limits);
        joint.type = reader.read<std::uint8_t>("PMX joint type");
        if (joint.type > (model.header.version >= 2.1f ? 5 : 0)) reader.fail("PMX joint type is invalid");
        joint.first_rigid_body_index =
            signed_index(reader, model.header.rigid_body_index_size, "PMX first joint rigid body index");
        joint.second_rigid_body_index =
            signed_index(reader, model.header.rigid_body_index_size, "PMX second joint rigid body index");
        if (!nullable_index_in_range(joint.first_rigid_body_index, rigid_body_count) ||
            !nullable_index_in_range(joint.second_rigid_body_index, rigid_body_count)) {
            reader.fail("PMX joint rigid body index is out of range");
        }
        joint.position = read_floats<3>(reader, "PMX joint position");
        joint.rotation = read_floats<3>(reader, "PMX joint rotation");
        joint.translation_lower_limit = read_floats<3>(reader, "PMX joint translation lower limit");
        joint.translation_upper_limit = read_floats<3>(reader, "PMX joint translation upper limit");
        joint.rotation_lower_limit = read_floats<3>(reader, "PMX joint rotation lower limit");
        joint.rotation_upper_limit = read_floats<3>(reader, "PMX joint rotation upper limit");
        joint.translation_spring = read_floats<3>(reader, "PMX joint translation spring");
        joint.rotation_spring = read_floats<3>(reader, "PMX joint rotation spring");
        model.joints.push_back(std::move(joint));
    }

    read_soft_bodies(reader, limits, model);
    if (reader.remaining() != 0) reader.fail("PMX contains trailing data");
    return model;
}

}

HeaderResult read_header(const std::span<const std::byte> bytes) {
    try {
        Reader reader(bytes);
        return parse_header(reader);
    } catch (const Failure& failure) {
        return ParseError{failure.position, failure.what()};
    }
}

ModelResult read_model(const std::span<const std::byte> bytes, const Limits limits) {
    try {
        Reader reader(bytes);
        return parse_model(reader, limits);
    } catch (const Failure& failure) {
        return ParseError{failure.position, failure.what()};
    } catch (const std::bad_alloc&) {
        return ParseError{0, "PMX allocation failed"};
    }
}

}
