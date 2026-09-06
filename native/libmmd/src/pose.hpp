#ifndef LIBMMD_POSE_HPP_
#define LIBMMD_POSE_HPP_

#include "native/libmmd/src/pmx_reader.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace libmmd {

struct Vector3 {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
};

struct Quaternion {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
    float w = 1.0f;
};

struct BoneTransform {
    Vector3 position;
    Quaternion rotation;
};

struct BonePhysicsOverride {
    std::uint32_t bone_index;
    BoneTransform transform;
    bool rotation_only = false;
};

class Pose final {
public:
    explicit Pose(std::span<const pmx::Bone> bones);

    void reset() noexcept;
    [[nodiscard]] bool set_local_transform(
        std::uint32_t bone_index,
        Vector3 translation,
        Quaternion rotation) noexcept;
    [[nodiscard]] bool set_ik_enabled(std::uint32_t bone_index, bool enabled) noexcept;
    [[nodiscard]] bool blend(const Pose& from, const Pose& to, float weight) noexcept;
    [[nodiscard]] bool blend_layer(
        const Pose& source, std::span<const std::uint8_t> bone_mask,
        std::span<const std::uint8_t> ik_mask, float weight) noexcept;
    void evaluate() noexcept;
    [[nodiscard]] bool global_transform(std::uint32_t bone_index, BoneTransform& output) const noexcept;
    [[nodiscard]] bool apply_physics(std::span<const BonePhysicsOverride> overrides) noexcept;

    [[nodiscard]] std::uint32_t bone_count() const noexcept;
    [[nodiscard]] std::span<const float> skinning_matrices() const noexcept;

private:
    struct RuntimeBone {
        std::int32_t parent_index;
        std::int32_t deform_layer;
        std::int32_t inheritance_index;
        float inheritance_weight;
        std::uint16_t flags;
        Vector3 bind_position;
        Vector3 bind_translation;
        Vector3 fixed_axis;
        std::int32_t ik_target_index;
        std::int32_t ik_iteration_count;
        float ik_angle_limit;
        std::vector<pmx::Bone::IkLink> ik_links;
    };

    std::vector<RuntimeBone> bones_;
    std::vector<std::uint32_t> evaluation_order_;
    std::vector<std::uint32_t> ik_order_;
    std::vector<Vector3> local_translations_;
    std::vector<Quaternion> local_rotations_;
    std::vector<Quaternion> working_rotations_;
    std::vector<bool> ik_enabled_;
    std::vector<Vector3> effective_translations_;
    std::vector<Quaternion> effective_rotations_;
    std::vector<Vector3> global_positions_;
    std::vector<Quaternion> global_rotations_;
    std::vector<std::size_t> physics_override_indices_;
    std::vector<BoneTransform> physics_transforms_;
    std::vector<float> skinning_matrices_;

    void evaluate_hierarchy() noexcept;
    void solve_ik(const RuntimeBone& constraint, std::uint32_t constraint_index) noexcept;
    void write_matrices() noexcept;
};

}

#endif
