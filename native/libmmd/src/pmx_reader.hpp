#ifndef LIBMMD_PMX_READER_HPP_
#define LIBMMD_PMX_READER_HPP_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <variant>
#include <vector>

namespace libmmd::pmx {

struct Header {
    float version;
    std::uint8_t text_encoding;
    std::uint8_t additional_uv_count;
    std::uint8_t vertex_index_size;
    std::uint8_t texture_index_size;
    std::uint8_t material_index_size;
    std::uint8_t bone_index_size;
    std::uint8_t morph_index_size;
    std::uint8_t rigid_body_index_size;
};

struct ParseError {
    std::size_t offset;
    std::string message;
};

struct Limits {
    std::uint32_t max_vertices = 10'000'000;
    std::uint32_t max_indices = 30'000'000;
    std::uint32_t max_textures = 100'000;
    std::uint32_t max_materials = 100'000;
    std::uint32_t max_bones = 100'000;
    std::uint32_t max_ik_links = 1'000'000;
    std::uint32_t max_morphs = 100'000;
    std::uint32_t max_morph_offsets = 10'000'000;
    std::uint32_t max_display_frames = 100'000;
    std::uint32_t max_display_elements = 1'000'000;
    std::uint32_t max_rigid_bodies = 100'000;
    std::uint32_t max_joints = 100'000;
    std::uint32_t max_soft_bodies = 100'000;
    std::uint32_t max_soft_body_elements = 10'000'000;
    std::uint32_t max_string_bytes = 16 * 1024 * 1024;
};

struct Vertex {
    std::array<float, 3> position{};
    std::array<float, 3> normal{};
    std::array<float, 2> uv{};
    std::array<std::int32_t, 4> bone_indices{-1, -1, -1, -1};
    std::array<float, 4> bone_weights{};
    float edge_scale = 0.0f;
};

struct Material {
    std::string name;
    std::string english_name;
    std::array<float, 4> diffuse{};
    std::array<float, 3> specular{};
    float specular_strength = 0.0f;
    std::array<float, 3> ambient{};
    std::uint8_t flags = 0;
    std::array<float, 4> edge_color{};
    float edge_size = 0.0f;
    std::int32_t texture_index = -1;
    std::int32_t sphere_texture_index = -1;
    std::uint8_t sphere_mode = 0;
    std::int32_t toon_texture_index = -1;
    std::string metadata;
    std::uint32_t index_count = 0;
};

struct Bone {
    std::string name;
    std::string english_name;
    std::array<float, 3> position{};
    std::int32_t parent_index = -1;
    std::int32_t deform_layer = 0;
    std::uint16_t flags = 0;
    std::int32_t connection_index = -1;
    std::array<float, 3> connection_offset{};
    std::int32_t inheritance_index = -1;
    float inheritance_weight = 0.0f;
    std::array<float, 3> fixed_axis{};
    std::array<float, 3> local_x_axis{};
    std::array<float, 3> local_z_axis{};
    std::int32_t external_parent_key = 0;
    std::int32_t ik_target_index = -1;
    std::int32_t ik_iteration_count = 0;
    float ik_angle_limit = 0.0f;
    struct IkLink {
        std::int32_t bone_index = -1;
        bool limited = false;
        std::array<float, 3> lower_limit{};
        std::array<float, 3> upper_limit{};
    };
    std::vector<IkLink> ik_links;
};

struct GroupMorphOffset {
    std::int32_t morph_index = -1;
    float weight = 0.0f;
};

struct VertexMorphOffset {
    std::uint32_t vertex_index = 0;
    std::array<float, 3> translation{};
};

struct BoneMorphOffset {
    std::int32_t bone_index = -1;
    std::array<float, 3> translation{};
    std::array<float, 4> rotation{};
};

struct UvMorphOffset {
    std::uint32_t vertex_index = 0;
    std::array<float, 4> offset{};
};

struct MaterialMorphOffset {
    std::int32_t material_index = -1;
    std::uint8_t operation = 0;
    std::array<float, 4> diffuse{};
    std::array<float, 3> specular{};
    float specular_strength = 0.0f;
    std::array<float, 3> ambient{};
    std::array<float, 4> edge_color{};
    float edge_scale = 0.0f;
    std::array<float, 4> texture_tint{};
    std::array<float, 4> sphere_tint{};
    std::array<float, 4> toon_tint{};
};

struct ImpulseMorphOffset {
    std::int32_t rigid_body_index = -1;
    bool local = false;
    std::array<float, 3> velocity{};
    std::array<float, 3> torque{};
};

struct Morph {
    std::string name;
    std::string english_name;
    std::uint8_t panel = 0;
    std::uint8_t type = 0;
    std::vector<GroupMorphOffset> groups;
    std::vector<VertexMorphOffset> vertices;
    std::vector<BoneMorphOffset> bones;
    std::vector<UvMorphOffset> uvs;
    std::vector<MaterialMorphOffset> materials;
    std::vector<ImpulseMorphOffset> impulses;
};

struct RigidBody {
    std::string name;
    std::string english_name;
    std::int32_t bone_index = -1;
    std::uint8_t collision_group = 0;
    std::uint16_t collision_mask = 0;
    std::uint8_t shape = 0;
    std::array<float, 3> size{};
    std::array<float, 3> position{};
    std::array<float, 3> rotation{};
    float mass = 0.0f;
    float linear_damping = 0.0f;
    float angular_damping = 0.0f;
    float restitution = 0.0f;
    float friction = 0.0f;
    std::uint8_t mode = 0;
};

struct Joint {
    std::string name;
    std::string english_name;
    std::uint8_t type = 0;
    std::int32_t first_rigid_body_index = -1;
    std::int32_t second_rigid_body_index = -1;
    std::array<float, 3> position{};
    std::array<float, 3> rotation{};
    std::array<float, 3> translation_lower_limit{};
    std::array<float, 3> translation_upper_limit{};
    std::array<float, 3> rotation_lower_limit{};
    std::array<float, 3> rotation_upper_limit{};
    std::array<float, 3> translation_spring{};
    std::array<float, 3> rotation_spring{};
};

struct Model {
    Header header{};
    std::string name;
    std::string english_name;
    std::string description;
    std::string english_description;
    std::vector<Vertex> vertices;
    std::vector<std::uint32_t> indices;
    std::vector<std::string> textures;
    std::vector<Material> materials;
    std::vector<Bone> bones;
    std::vector<Morph> morphs;
    std::vector<RigidBody> rigid_bodies;
    std::vector<Joint> joints;
};

using HeaderResult = std::variant<Header, ParseError>;
using ModelResult = std::variant<Model, ParseError>;

[[nodiscard]] HeaderResult read_header(std::span<const std::byte> bytes);
[[nodiscard]] ModelResult read_model(std::span<const std::byte> bytes, Limits limits = {});

}

#endif
