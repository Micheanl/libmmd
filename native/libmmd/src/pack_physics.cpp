#include "native/libmmd/src/pack_physics.hpp"

#include "native/libmmd/src/skeleton.hpp"

#include <array>
#include <cmath>
#include <cstring>
#include <new>
#include <stdexcept>
#include <string_view>

namespace libmmd::pack {
namespace {

class Failure final : public std::runtime_error {
public:
    Failure(const std::size_t offset, std::string message)
        : std::runtime_error(std::move(message)), offset(offset) {}

    std::size_t offset;
};

class Reader final {
public:
    Reader(const std::span<const std::byte> bytes, const std::size_t offset, const pmx::Limits& limits)
        : bytes_(bytes), position_(offset), limits_(limits) {
        if (offset > bytes.size()) fail("mmdpack physics offset is invalid");
    }

    template <typename T>
    T read(const std::string_view label) {
        if (remaining() < sizeof(T)) fail(std::string(label) + " is truncated");
        T value{};
        std::memcpy(&value, bytes_.data() + position_, sizeof(T));
        position_ += sizeof(T);
        return value;
    }

    float scalar(const std::string_view label) {
        const auto value = read<float>(label);
        if (!std::isfinite(value)) fail(std::string(label) + " is non-finite");
        return value;
    }

    template <std::size_t Size>
    std::array<float, Size> floats(const std::string_view label) {
        std::array<float, Size> values{};
        for (auto& value : values) value = scalar(label);
        return values;
    }

    std::string string(const std::string_view label) {
        const auto size = string_size(label);
        std::string value(reinterpret_cast<const char*>(bytes_.data() + position_), size);
        position_ += size;
        return value;
    }

    void skip_string(const std::string_view label) {
        position_ += string_size(label);
    }

    void count(
        const std::uint32_t value,
        const std::uint32_t limit,
        const std::size_t minimum_size,
        const std::string_view label) const {
        if (value > limit) fail(std::string(label) + " exceeds the limit");
        if (minimum_size != 0 && value > remaining() / minimum_size) {
            fail(std::string(label) + " exceeds the remaining bytes");
        }
    }

    [[nodiscard]] std::size_t position() const noexcept { return position_; }
    [[nodiscard]] std::size_t remaining() const noexcept { return bytes_.size() - position_; }

    [[noreturn]] void fail(std::string message) const {
        throw Failure(position_, std::move(message));
    }

private:
    std::uint32_t string_size(const std::string_view label) {
        const auto size = read<std::uint32_t>(label);
        count(size, limits_.max_string_bytes, 1, label);
        return size;
    }

    std::span<const std::byte> bytes_;
    std::size_t position_;
    const pmx::Limits& limits_;
};

bool valid_index(const std::int32_t index, const std::uint32_t count, const bool nullable = false) {
    return (nullable && index == -1) || (index >= 0 && static_cast<std::uint32_t>(index) < count);
}

void validate_counts(Reader& reader, const Info& info, const pmx::Limits& limits) {
    reader.count(info.vertex_count, limits.max_vertices, 0, "mmdpack vertex count");
    reader.count(info.index_count, limits.max_indices, 0, "mmdpack index count");
    reader.count(info.texture_count, limits.max_textures, 0, "mmdpack texture count");
    reader.count(info.material_count, limits.max_materials, 0, "mmdpack material count");
    reader.count(info.bone_count, limits.max_bones, 0, "mmdpack bone count");
    reader.count(info.morph_count, limits.max_morphs, 0, "mmdpack morph count");
    reader.count(info.rigid_body_count, limits.max_rigid_bodies, 0, "mmdpack rigid body count");
    reader.count(info.joint_count, limits.max_joints, 0, "mmdpack joint count");
    reader.count(info.soft_body_count, limits.max_soft_bodies, 0, "mmdpack soft body count");
}

void skip_morphs(Reader& reader, const Info& info, const pmx::Limits& limits) {
    constexpr std::array<std::size_t, 11> offset_sizes{8, 16, 32, 20, 20, 20, 20, 20, 117, 8, 29};
    reader.count(info.morph_count, limits.max_morphs, 14, "mmdpack morph count");
    std::uint64_t total_offsets = 0;
    for (std::uint32_t index = 0; index < info.morph_count; ++index) {
        reader.skip_string("mmdpack morph name");
        reader.skip_string("mmdpack morph English name");
        const auto panel = reader.read<std::uint8_t>("mmdpack morph panel");
        const auto type = reader.read<std::uint8_t>("mmdpack morph type");
        if (panel > 4 || type >= offset_sizes.size()) reader.fail("mmdpack morph metadata is invalid");
        const auto count = reader.read<std::uint32_t>("mmdpack morph offset count");
        reader.count(count, limits.max_morph_offsets, offset_sizes[type], "mmdpack morph offset count");
        total_offsets += count;
        if (total_offsets > limits.max_morph_offsets) reader.fail("mmdpack morph offsets exceed the limit");
        for (std::uint32_t offset = 0; offset < count; ++offset) {
            if (type == 0 || type == 9) {
                const auto reference = reader.read<std::int32_t>("mmdpack group morph index");
                if (!valid_index(reference, info.morph_count)) reader.fail("mmdpack group morph index is invalid");
                static_cast<void>(reader.scalar("mmdpack group morph weight"));
            } else if (type == 1 || (type >= 3 && type <= 7)) {
                const auto reference = reader.read<std::uint32_t>("mmdpack morph vertex index");
                if (reference >= info.vertex_count) reader.fail("mmdpack morph vertex index is invalid");
                if (type == 1) static_cast<void>(reader.floats<3>("mmdpack vertex morph translation"));
                else static_cast<void>(reader.floats<4>("mmdpack UV morph offset"));
            } else if (type == 2) {
                const auto reference = reader.read<std::int32_t>("mmdpack morph bone index");
                if (!valid_index(reference, info.bone_count)) reader.fail("mmdpack morph bone index is invalid");
                static_cast<void>(reader.floats<7>("mmdpack bone morph offset"));
            } else if (type == 8) {
                const auto reference = reader.read<std::int32_t>("mmdpack morph material index");
                const auto operation = reader.read<std::uint8_t>("mmdpack material morph operation");
                if (!valid_index(reference, info.material_count, true) || operation > 1) {
                    reader.fail("mmdpack material morph metadata is invalid");
                }
                static_cast<void>(reader.floats<28>("mmdpack material morph offset"));
            } else {
                const auto reference = reader.read<std::int32_t>("mmdpack impulse rigid body index");
                const auto local = reader.read<std::uint8_t>("mmdpack impulse local flag");
                if (!valid_index(reference, info.rigid_body_count) || local > 1) {
                    reader.fail("mmdpack impulse morph metadata is invalid");
                }
                static_cast<void>(reader.floats<6>("mmdpack impulse morph offset"));
            }
        }
    }
}

pmx::RigidBody read_rigid_body(Reader& reader, const Info& info) {
    pmx::RigidBody body;
    body.name = reader.string("mmdpack rigid body name");
    body.english_name = reader.string("mmdpack rigid body English name");
    body.bone_index = reader.read<std::int32_t>("mmdpack rigid body bone");
    body.collision_group = reader.read<std::uint8_t>("mmdpack rigid body collision group");
    body.collision_mask = reader.read<std::uint16_t>("mmdpack rigid body collision mask");
    body.shape = reader.read<std::uint8_t>("mmdpack rigid body shape");
    body.size = reader.floats<3>("mmdpack rigid body size");
    body.position = reader.floats<3>("mmdpack rigid body position");
    body.rotation = reader.floats<3>("mmdpack rigid body rotation");
    body.mass = reader.scalar("mmdpack rigid body mass");
    body.linear_damping = reader.scalar("mmdpack rigid body linear damping");
    body.angular_damping = reader.scalar("mmdpack rigid body angular damping");
    body.restitution = reader.scalar("mmdpack rigid body restitution");
    body.friction = reader.scalar("mmdpack rigid body friction");
    body.mode = reader.read<std::uint8_t>("mmdpack rigid body mode");
    if (!valid_index(body.bone_index, info.bone_count, true) || body.collision_group > 15 ||
        body.shape > 2 || body.mode > 2) {
        reader.fail("mmdpack rigid body metadata is invalid");
    }
    return body;
}

pmx::Joint read_joint(Reader& reader, const Info& info) {
    pmx::Joint joint;
    joint.name = reader.string("mmdpack joint name");
    joint.english_name = reader.string("mmdpack joint English name");
    joint.type = reader.read<std::uint8_t>("mmdpack joint type");
    joint.first_rigid_body_index = reader.read<std::int32_t>("mmdpack joint first rigid body");
    joint.second_rigid_body_index = reader.read<std::int32_t>("mmdpack joint second rigid body");
    joint.position = reader.floats<3>("mmdpack joint position");
    joint.rotation = reader.floats<3>("mmdpack joint rotation");
    joint.translation_lower_limit = reader.floats<3>("mmdpack joint translation lower limit");
    joint.translation_upper_limit = reader.floats<3>("mmdpack joint translation upper limit");
    joint.rotation_lower_limit = reader.floats<3>("mmdpack joint rotation lower limit");
    joint.rotation_upper_limit = reader.floats<3>("mmdpack joint rotation upper limit");
    joint.translation_spring = reader.floats<3>("mmdpack joint translation spring");
    joint.rotation_spring = reader.floats<3>("mmdpack joint rotation spring");
    if (joint.type > 5 || !valid_index(joint.first_rigid_body_index, info.rigid_body_count, true) ||
        !valid_index(joint.second_rigid_body_index, info.rigid_body_count, true)) {
        reader.fail("mmdpack joint metadata is invalid");
    }
    return joint;
}

pmx::SoftBody read_soft_body(
    Reader& reader, const Info& info, const pmx::Limits& limits, std::uint64_t& total_elements) {
    pmx::SoftBody body;
    body.name = reader.string("mmdpack soft body name");
    body.english_name = reader.string("mmdpack soft body English name");
    body.shape = reader.read<std::uint8_t>("mmdpack soft body shape");
    body.material_index = reader.read<std::int32_t>("mmdpack soft body material");
    body.collision_group = reader.read<std::uint8_t>("mmdpack soft body collision group");
    body.collision_mask = reader.read<std::uint16_t>("mmdpack soft body collision mask");
    body.flags = reader.read<std::uint8_t>("mmdpack soft body flags");
    body.link_distance = reader.read<std::int32_t>("mmdpack soft body link distance");
    body.cluster_count = reader.read<std::int32_t>("mmdpack soft body cluster count");
    body.mass = reader.scalar("mmdpack soft body mass");
    body.collision_margin = reader.scalar("mmdpack soft body collision margin");
    body.aero_model = reader.read<std::int32_t>("mmdpack soft body aerodynamic model");
    body.configuration = reader.floats<12>("mmdpack soft body configuration");
    body.cluster_configuration = reader.floats<6>("mmdpack soft body cluster configuration");
    for (auto& iterations : body.solver_iterations) {
        iterations = reader.read<std::int32_t>("mmdpack soft body solver iterations");
        if (iterations < 0) reader.fail("mmdpack soft body solver iterations are invalid");
    }
    body.material_coefficients = reader.floats<3>("mmdpack soft body material coefficients");
    if (body.shape > 1 || !valid_index(body.material_index, info.material_count) ||
        body.collision_group > 15 || body.flags > 7 || body.link_distance < 0 ||
        body.cluster_count < 0 || body.aero_model < 0 || body.aero_model > 4) {
        reader.fail("mmdpack soft body metadata is invalid");
    }
    const auto anchor_count = reader.read<std::uint32_t>("mmdpack soft body anchor count");
    reader.count(anchor_count, limits.max_soft_body_elements, 9, "mmdpack soft body anchor count");
    total_elements += anchor_count;
    if (total_elements > limits.max_soft_body_elements) reader.fail("mmdpack soft body elements exceed the limit");
    body.anchors.reserve(anchor_count);
    for (std::uint32_t index = 0; index < anchor_count; ++index) {
        pmx::SoftBodyAnchor anchor;
        anchor.rigid_body_index = reader.read<std::int32_t>("mmdpack soft body anchor rigid body");
        anchor.vertex_index = reader.read<std::uint32_t>("mmdpack soft body anchor vertex");
        const auto near_mode = reader.read<std::uint8_t>("mmdpack soft body anchor near mode");
        if (!valid_index(anchor.rigid_body_index, info.rigid_body_count) ||
            anchor.vertex_index >= info.vertex_count || near_mode > 1) {
            reader.fail("mmdpack soft body anchor is invalid");
        }
        anchor.near_mode = near_mode == 1;
        body.anchors.push_back(anchor);
    }
    const auto pin_count = reader.read<std::uint32_t>("mmdpack soft body pin count");
    reader.count(pin_count, limits.max_soft_body_elements, 4, "mmdpack soft body pin count");
    total_elements += pin_count;
    if (total_elements > limits.max_soft_body_elements) reader.fail("mmdpack soft body elements exceed the limit");
    body.pinned_vertices.reserve(pin_count);
    for (std::uint32_t index = 0; index < pin_count; ++index) {
        const auto vertex = reader.read<std::uint32_t>("mmdpack soft body pinned vertex");
        if (vertex >= info.vertex_count) reader.fail("mmdpack soft body pinned vertex is invalid");
        body.pinned_vertices.push_back(vertex);
    }
    return body;
}

}

PhysicsAssetsResult read_physics_assets(
    const std::span<const std::byte> bytes,
    const Layout& layout,
    const pmx::Limits limits,
    std::optional<std::size_t> morph_offset) {
    try {
        if (layout.info.version != 2 && layout.info.version != format_version) {
            return Error{8, "mmdpack version is unsupported"};
        }
        if (layout.info.version == 2 && layout.info.soft_body_count != 0) {
            return Error{72, "mmdpack v2 cannot contain soft bodies"};
        }
        if (layout.indices.offset > bytes.size() || layout.indices.size > bytes.size() - layout.indices.offset) {
            return Error{layout.indices.offset, "mmdpack physics metadata offset is invalid"};
        }
        const auto metadata_offset = layout.indices.offset + layout.indices.size;
        Reader metadata_reader(bytes, metadata_offset, limits);
        validate_counts(metadata_reader, layout.info, limits);
        if (!morph_offset.has_value()) {
            metadata_reader.count(layout.info.bone_count, limits.max_bones, 34, "mmdpack bone count");
            std::size_t skeleton_end = 0;
            const auto skeleton_result = read_skeleton(bytes, layout, &skeleton_end);
            if (const auto* error = std::get_if<Error>(&skeleton_result)) return *error;
            morph_offset = skeleton_end;
        }
        if (*morph_offset < metadata_offset) return Error{*morph_offset, "mmdpack morph offset is invalid"};
        Reader reader(bytes, *morph_offset, limits);
        skip_morphs(reader, layout.info, limits);
        PhysicsAssets assets;
        reader.count(layout.info.rigid_body_count, limits.max_rigid_bodies, 73, "mmdpack rigid body count");
        assets.rigid_bodies.reserve(layout.info.rigid_body_count);
        for (std::uint32_t index = 0; index < layout.info.rigid_body_count; ++index) {
            assets.rigid_bodies.push_back(read_rigid_body(reader, layout.info));
        }
        reader.count(layout.info.joint_count, limits.max_joints, 113, "mmdpack joint count");
        assets.joints.reserve(layout.info.joint_count);
        for (std::uint32_t index = 0; index < layout.info.joint_count; ++index) {
            assets.joints.push_back(read_joint(reader, layout.info));
        }
        reader.count(layout.info.soft_body_count, limits.max_soft_bodies, 145, "mmdpack soft body count");
        assets.soft_bodies.reserve(layout.info.soft_body_count);
        std::uint64_t total_elements = 0;
        for (std::uint32_t index = 0; index < layout.info.soft_body_count; ++index) {
            assets.soft_bodies.push_back(read_soft_body(reader, layout.info, limits, total_elements));
        }
        if (reader.remaining() != 0) reader.fail("mmdpack physics payload has trailing bytes");
        return assets;
    } catch (const Failure& failure) {
        return Error{failure.offset, failure.what()};
    } catch (const std::bad_alloc&) {
        return Error{0, "mmdpack physics allocation failed"};
    } catch (const std::length_error&) {
        return Error{0, "mmdpack physics allocation exceeds container limits"};
    }
}

}
