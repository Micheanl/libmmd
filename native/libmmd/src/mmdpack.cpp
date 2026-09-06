#include "native/libmmd/src/mmdpack.hpp"

#include <array>
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string_view>
#include <type_traits>

namespace libmmd::pack {
namespace {

static_assert(std::endian::native == std::endian::little);

constexpr std::array magic{
    std::byte{'M'}, std::byte{'M'}, std::byte{'D'}, std::byte{'P'},
    std::byte{'A'}, std::byte{'C'}, std::byte{'K'}, std::byte{0},
};
constexpr std::uint32_t legacy_header_size = 72;
constexpr std::uint32_t header_size = 76;
constexpr std::uint32_t vertex_stride = 68;
constexpr std::uint32_t index_stride = 4;
constexpr std::uint64_t fnv_offset = 14695981039346656037ull;
constexpr std::uint64_t fnv_prime = 1099511628211ull;

std::uint64_t hash(const std::span<const std::byte> bytes) {
    auto value = fnv_offset;
    for (const auto byte : bytes) {
        value ^= std::to_integer<std::uint8_t>(byte);
        value *= fnv_prime;
    }
    return value;
}

template <typename T>
void append(std::vector<std::byte>& output, const T& value) {
    static_assert(std::is_trivially_copyable_v<T>);
    const auto start = output.size();
    output.resize(start + sizeof(T));
    std::memcpy(output.data() + start, &value, sizeof(T));
}

template <typename T, std::size_t Size>
void append(std::vector<std::byte>& output, const std::array<T, Size>& values) {
    for (const auto& value : values) {
        append(output, value);
    }
}

void append_string(std::vector<std::byte>& output, const std::string& value) {
    if (value.size() > std::numeric_limits<std::uint32_t>::max()) {
        throw std::length_error("mmdpack string is too large");
    }
    append(output, static_cast<std::uint32_t>(value.size()));
    const auto start = output.size();
    output.resize(start + value.size());
    std::memcpy(output.data() + start, value.data(), value.size());
}

void append_bool(std::vector<std::byte>& output, const bool value) {
    append(output, static_cast<std::uint8_t>(value ? 1 : 0));
}

std::uint32_t morph_offset_count(const pmx::Morph& morph) {
    const auto count = morph.groups.size() + morph.vertices.size() + morph.bones.size() +
        morph.uvs.size() + morph.materials.size() + morph.impulses.size();
    if (count > std::numeric_limits<std::uint32_t>::max()) {
        throw std::length_error("mmdpack morph has too many offsets");
    }
    return static_cast<std::uint32_t>(count);
}

void overwrite(std::vector<std::byte>& output, const std::size_t offset, const std::uint64_t value) {
    std::memcpy(output.data() + offset, &value, sizeof(value));
}

class Reader final {
public:
    explicit Reader(const std::span<const std::byte> bytes, const std::size_t position = 0)
        : bytes_(bytes), position_(position) {}

    template <typename T>
    bool read(T& value) {
        if (bytes_.size() - position_ < sizeof(T)) {
            return false;
        }
        std::memcpy(&value, bytes_.data() + position_, sizeof(T));
        position_ += sizeof(T);
        return true;
    }

    bool read_magic() {
        if (bytes_.size() < magic.size()) {
            return false;
        }
        position_ += magic.size();
        return std::memcmp(bytes_.data(), magic.data(), magic.size()) == 0;
    }

    bool skip(const std::size_t size) {
        if (bytes_.size() - position_ < size) return false;
        position_ += size;
        return true;
    }

    bool read_string(std::string& value) {
        std::uint32_t size = 0;
        if (!read(size) || bytes_.size() - position_ < size) return false;
        value.assign(reinterpret_cast<const char*>(bytes_.data() + position_), size);
        position_ += size;
        return true;
    }

    [[nodiscard]] std::size_t position() const noexcept { return position_; }

private:
    std::span<const std::byte> bytes_;
    std::size_t position_ = 0;
};

template <std::size_t Size>
bool finite(const std::array<float, Size>& values) {
    return std::all_of(values.begin(), values.end(), [](const float value) { return std::isfinite(value); });
}

}

std::vector<std::byte> build(const pmx::Model& model, const std::span<const std::byte> source) {
    std::vector<std::byte> output;
    output.reserve(header_size + model.vertices.size() * 68 + model.indices.size() * 4);
    for (const auto byte : magic) append(output, byte);
    append(output, format_version);
    append(output, header_size);
    append(output, hash(source));
    append(output, std::uint64_t{0});
    append(output, std::uint64_t{0});
    append(output, static_cast<std::uint32_t>(model.vertices.size()));
    append(output, static_cast<std::uint32_t>(model.indices.size()));
    append(output, static_cast<std::uint32_t>(model.textures.size()));
    append(output, static_cast<std::uint32_t>(model.materials.size()));
    append(output, static_cast<std::uint32_t>(model.bones.size()));
    append(output, static_cast<std::uint32_t>(model.morphs.size()));
    append(output, static_cast<std::uint32_t>(model.rigid_bodies.size()));
    append(output, static_cast<std::uint32_t>(model.joints.size()));
    append(output, static_cast<std::uint32_t>(model.soft_bodies.size()));

    append_string(output, model.name);
    append_string(output, model.english_name);
    for (const auto& vertex : model.vertices) {
        append(output, vertex.position);
        append(output, vertex.normal);
        append(output, vertex.uv);
        append(output, vertex.bone_indices);
        append(output, vertex.bone_weights);
        append(output, vertex.edge_scale);
    }
    for (const auto index : model.indices) append(output, index);
    for (const auto& texture : model.textures) append_string(output, texture);
    for (const auto& material : model.materials) {
        append_string(output, material.name);
        append_string(output, material.english_name);
        append(output, material.diffuse);
        append(output, material.specular);
        append(output, material.specular_strength);
        append(output, material.ambient);
        append(output, material.flags);
        append(output, material.edge_color);
        append(output, material.edge_size);
        append(output, material.texture_index);
        append(output, material.sphere_texture_index);
        append(output, material.sphere_mode);
        append(output, material.toon_texture_index);
        append_string(output, material.metadata);
        append(output, material.index_count);
    }
    for (const auto& bone : model.bones) {
        append_string(output, bone.name);
        append_string(output, bone.english_name);
        append(output, bone.position);
        append(output, bone.parent_index);
        append(output, bone.deform_layer);
        append(output, bone.flags);
        if ((bone.flags & 0x0001) != 0) {
            append(output, bone.connection_index);
        } else {
            append(output, bone.connection_offset);
        }
        if ((bone.flags & 0x0300) != 0) {
            append(output, bone.inheritance_index);
            append(output, bone.inheritance_weight);
        }
        if ((bone.flags & 0x0400) != 0) append(output, bone.fixed_axis);
        if ((bone.flags & 0x0800) != 0) {
            append(output, bone.local_x_axis);
            append(output, bone.local_z_axis);
        }
        if ((bone.flags & 0x2000) != 0) append(output, bone.external_parent_key);
        if ((bone.flags & 0x0020) != 0) {
            append(output, bone.ik_target_index);
            append(output, bone.ik_iteration_count);
            append(output, bone.ik_angle_limit);
            append(output, static_cast<std::uint32_t>(bone.ik_links.size()));
            for (const auto& link : bone.ik_links) {
                append(output, link.bone_index);
                append_bool(output, link.limited);
                if (link.limited) {
                    append(output, link.lower_limit);
                    append(output, link.upper_limit);
                }
            }
        }
    }
    for (const auto& morph : model.morphs) {
        append_string(output, morph.name);
        append_string(output, morph.english_name);
        append(output, morph.panel);
        append(output, morph.type);
        append(output, morph_offset_count(morph));
        for (const auto& offset : morph.groups) {
            append(output, offset.morph_index);
            append(output, offset.weight);
        }
        for (const auto& offset : morph.vertices) {
            append(output, offset.vertex_index);
            append(output, offset.translation);
        }
        for (const auto& offset : morph.bones) {
            append(output, offset.bone_index);
            append(output, offset.translation);
            append(output, offset.rotation);
        }
        for (const auto& offset : morph.uvs) {
            append(output, offset.vertex_index);
            append(output, offset.offset);
        }
        for (const auto& offset : morph.materials) {
            append(output, offset.material_index);
            append(output, offset.operation);
            append(output, offset.diffuse);
            append(output, offset.specular);
            append(output, offset.specular_strength);
            append(output, offset.ambient);
            append(output, offset.edge_color);
            append(output, offset.edge_scale);
            append(output, offset.texture_tint);
            append(output, offset.sphere_tint);
            append(output, offset.toon_tint);
        }
        for (const auto& offset : morph.impulses) {
            append(output, offset.rigid_body_index);
            append_bool(output, offset.local);
            append(output, offset.velocity);
            append(output, offset.torque);
        }
    }
    for (const auto& body : model.rigid_bodies) {
        append_string(output, body.name);
        append_string(output, body.english_name);
        append(output, body.bone_index);
        append(output, body.collision_group);
        append(output, body.collision_mask);
        append(output, body.shape);
        append(output, body.size);
        append(output, body.position);
        append(output, body.rotation);
        append(output, body.mass);
        append(output, body.linear_damping);
        append(output, body.angular_damping);
        append(output, body.restitution);
        append(output, body.friction);
        append(output, body.mode);
    }
    for (const auto& joint : model.joints) {
        append_string(output, joint.name);
        append_string(output, joint.english_name);
        append(output, joint.type);
        append(output, joint.first_rigid_body_index);
        append(output, joint.second_rigid_body_index);
        append(output, joint.position);
        append(output, joint.rotation);
        append(output, joint.translation_lower_limit);
        append(output, joint.translation_upper_limit);
        append(output, joint.rotation_lower_limit);
        append(output, joint.rotation_upper_limit);
        append(output, joint.translation_spring);
        append(output, joint.rotation_spring);
    }
    for (const auto& body : model.soft_bodies) {
        append_string(output, body.name);
        append_string(output, body.english_name);
        append(output, body.shape);
        append(output, body.material_index);
        append(output, body.collision_group);
        append(output, body.collision_mask);
        append(output, body.flags);
        append(output, body.link_distance);
        append(output, body.cluster_count);
        append(output, body.mass);
        append(output, body.collision_margin);
        append(output, body.aero_model);
        append(output, body.configuration);
        append(output, body.cluster_configuration);
        append(output, body.solver_iterations);
        append(output, body.material_coefficients);
        append(output, static_cast<std::uint32_t>(body.anchors.size()));
        for (const auto& anchor : body.anchors) {
            append(output, anchor.rigid_body_index);
            append(output, anchor.vertex_index);
            append_bool(output, anchor.near_mode);
        }
        append(output, static_cast<std::uint32_t>(body.pinned_vertices.size()));
        for (const auto vertex : body.pinned_vertices) append(output, vertex);
    }

    overwrite(output, 24, hash(std::span(output).subspan(header_size)));
    overwrite(output, 32, output.size());
    return output;
}

InfoResult inspect(const std::span<const std::byte> bytes) {
    const auto result = inspect_layout(bytes);
    if (const auto* error = std::get_if<Error>(&result)) return *error;
    return std::get<Layout>(result).info;
}

LayoutResult inspect_layout(const std::span<const std::byte> bytes) {
    Reader reader(bytes);
    if (!reader.read_magic()) {
        return Error{0, "mmdpack signature is invalid"};
    }
    std::uint32_t stored_header_size = 0;
    Info info{};
    if (!reader.read(info.version) || !reader.read(stored_header_size) ||
        !reader.read(info.source_hash) || !reader.read(info.payload_hash) ||
        !reader.read(info.total_size) || !reader.read(info.vertex_count) ||
        !reader.read(info.index_count) || !reader.read(info.texture_count) ||
        !reader.read(info.material_count) || !reader.read(info.bone_count) ||
        !reader.read(info.morph_count) || !reader.read(info.rigid_body_count) ||
        !reader.read(info.joint_count)) {
        return Error{reader.position(), "mmdpack header is truncated"};
    }
    if (info.version != 2 && info.version != format_version) {
        return Error{8, "mmdpack version is unsupported"};
    }
    const auto expected_header_size = info.version == 2 ? legacy_header_size : header_size;
    if (stored_header_size != expected_header_size) {
        return Error{12, "mmdpack header size is invalid"};
    }
    if (info.version == format_version && !reader.read(info.soft_body_count)) {
        return Error{reader.position(), "mmdpack header is truncated"};
    }
    if (info.total_size != bytes.size()) {
        return Error{32, "mmdpack file size does not match its header"};
    }
    if (hash(bytes.subspan(stored_header_size)) != info.payload_hash) {
        return Error{24, "mmdpack payload checksum is invalid"};
    }
    for (std::uint32_t index = 0; index < 2; ++index) {
        std::uint32_t size = 0;
        if (!reader.read(size) || !reader.skip(size)) {
            return Error{reader.position(), "mmdpack model name is truncated"};
        }
    }
    if (info.vertex_count != 0 &&
        vertex_stride > std::numeric_limits<std::size_t>::max() / info.vertex_count) {
        return Error{40, "mmdpack vertex buffer size overflows"};
    }
    const auto vertex_size = static_cast<std::size_t>(info.vertex_count) * vertex_stride;
    const auto vertex_offset = reader.position();
    if (!reader.skip(vertex_size)) {
        return Error{vertex_offset, "mmdpack vertex buffer is truncated"};
    }
    if (info.index_count != 0 &&
        index_stride > std::numeric_limits<std::size_t>::max() / info.index_count) {
        return Error{44, "mmdpack index buffer size overflows"};
    }
    const auto index_size = static_cast<std::size_t>(info.index_count) * index_stride;
    const auto index_offset = reader.position();
    if (!reader.skip(index_size)) {
        return Error{index_offset, "mmdpack index buffer is truncated"};
    }
    return Layout{
        .info = info,
        .vertices = {vertex_offset, vertex_size, info.vertex_count, vertex_stride},
        .indices = {index_offset, index_size, info.index_count, index_stride},
    };
}

RenderAssetsResult read_render_assets(
    const std::span<const std::byte> bytes,
    const Layout& layout) {
    const auto start = layout.indices.offset + layout.indices.size;
    if (start > bytes.size()) return Error{start, "mmdpack render metadata offset is invalid"};
    Reader reader(bytes, start);
    RenderAssets output;
    output.textures.reserve(layout.info.texture_count);
    for (std::uint32_t index = 0; index < layout.info.texture_count; ++index) {
        std::string texture;
        if (!reader.read_string(texture)) {
            return Error{reader.position(), "mmdpack texture path is truncated"};
        }
        output.textures.push_back(std::move(texture));
    }

    output.materials.reserve(layout.info.material_count);
    std::uint64_t first_index = 0;
    for (std::uint32_t index = 0; index < layout.info.material_count; ++index) {
        pmx::Material material;
        if (!reader.read_string(material.name) || !reader.read_string(material.english_name) ||
            !reader.read(material.diffuse) || !reader.read(material.specular) ||
            !reader.read(material.specular_strength) || !reader.read(material.ambient) ||
            !reader.read(material.flags) || !reader.read(material.edge_color) ||
            !reader.read(material.edge_size) || !reader.read(material.texture_index) ||
            !reader.read(material.sphere_texture_index) || !reader.read(material.sphere_mode) ||
            !reader.read(material.toon_texture_index) || !reader.read_string(material.metadata) ||
            !reader.read(material.index_count)) {
            return Error{reader.position(), "mmdpack material is truncated"};
        }
        const auto valid_texture = [&](const std::int32_t texture) {
            return texture == -1 || (texture >= 0 && static_cast<std::uint32_t>(texture) < layout.info.texture_count);
        };
        const auto valid_toon = valid_texture(material.toon_texture_index) ||
            (material.toon_texture_index >= 10 && material.toon_texture_index <= 19);
        if (!finite(material.diffuse) || !finite(material.specular) ||
            !std::isfinite(material.specular_strength) || !finite(material.ambient) ||
            !finite(material.edge_color) || !std::isfinite(material.edge_size) ||
            !valid_texture(material.texture_index) || !valid_texture(material.sphere_texture_index) ||
            !valid_toon || material.sphere_mode > 3 || material.index_count % 3 != 0 ||
            first_index + material.index_count > layout.info.index_count) {
            return Error{reader.position(), "mmdpack material data is invalid"};
        }
        output.materials.push_back({std::move(material), static_cast<std::uint32_t>(first_index)});
        first_index += output.materials.back().value.index_count;
    }
    if (first_index != layout.info.index_count) {
        return Error{reader.position(), "mmdpack material ranges do not cover the index buffer"};
    }
    return output;
}

}
