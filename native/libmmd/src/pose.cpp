#include "native/libmmd/src/pose.hpp"

#include <algorithm>
#include <cmath>
#include <functional>
#include <numbers>
#include <numeric>
#include <stdexcept>

namespace libmmd {
namespace {

constexpr float epsilon = 1.0e-6f;

Vector3 operator+(const Vector3 left, const Vector3 right) {
    return {left.x + right.x, left.y + right.y, left.z + right.z};
}

Vector3 operator-(const Vector3 left, const Vector3 right) {
    return {left.x - right.x, left.y - right.y, left.z - right.z};
}

Vector3 operator*(const Vector3 value, const float scale) {
    return {value.x * scale, value.y * scale, value.z * scale};
}

float dot(const Vector3 left, const Vector3 right) {
    return left.x * right.x + left.y * right.y + left.z * right.z;
}

Vector3 cross(const Vector3 left, const Vector3 right) {
    return {
        left.y * right.z - left.z * right.y,
        left.z * right.x - left.x * right.z,
        left.x * right.y - left.y * right.x,
    };
}

Vector3 normalized(const Vector3 value) {
    const auto length_squared = dot(value, value);
    if (!std::isfinite(length_squared) || length_squared <= epsilon * epsilon) return {};
    return value * (1.0f / std::sqrt(length_squared));
}

Quaternion operator*(const Quaternion left, const Quaternion right) {
    return {
        left.w * right.x + left.x * right.w + left.y * right.z - left.z * right.y,
        left.w * right.y - left.x * right.z + left.y * right.w + left.z * right.x,
        left.w * right.z + left.x * right.y - left.y * right.x + left.z * right.w,
        left.w * right.w - left.x * right.x - left.y * right.y - left.z * right.z,
    };
}

Quaternion normalized(const Quaternion value) {
    const auto length_squared = value.x * value.x + value.y * value.y + value.z * value.z + value.w * value.w;
    if (!std::isfinite(length_squared) || length_squared <= epsilon * epsilon) return {};
    const auto inverse = 1.0f / std::sqrt(length_squared);
    return {value.x * inverse, value.y * inverse, value.z * inverse, value.w * inverse};
}

Quaternion conjugate(const Quaternion value) {
    return {-value.x, -value.y, -value.z, value.w};
}

Vector3 rotate(const Quaternion rotation, const Vector3 value) {
    const Quaternion vector{value.x, value.y, value.z, 0.0f};
    const auto result = rotation * vector * conjugate(rotation);
    return {result.x, result.y, result.z};
}

Quaternion axis_angle(const Vector3 axis, const float angle) {
    const auto unit = normalized(axis);
    const auto half = angle * 0.5f;
    const auto sine = std::sin(half);
    return normalized({unit.x * sine, unit.y * sine, unit.z * sine, std::cos(half)});
}

Quaternion slerp_identity(Quaternion target, const float weight) {
    target = normalized(target);
    if (target.w < 0.0f) target = {-target.x, -target.y, -target.z, -target.w};
    const auto cosine = std::clamp(target.w, -1.0f, 1.0f);
    if (cosine > 0.9995f) {
        return normalized({target.x * weight, target.y * weight, target.z * weight, 1.0f + (target.w - 1.0f) * weight});
    }
    const auto angle = std::acos(cosine);
    const auto denominator = std::sin(angle);
    const auto target_scale = std::sin(weight * angle) / denominator;
    const auto identity_scale = std::sin((1.0f - weight) * angle) / denominator;
    return normalized({target.x * target_scale, target.y * target_scale, target.z * target_scale,
        identity_scale + target.w * target_scale});
}

Quaternion slerp(Quaternion from, Quaternion to, const float weight) {
    from = normalized(from);
    to = normalized(to);
    auto cosine = from.x * to.x + from.y * to.y + from.z * to.z + from.w * to.w;
    if (cosine < 0.0f) {
        to = {-to.x, -to.y, -to.z, -to.w};
        cosine = -cosine;
    }
    if (cosine > 0.9995f) {
        return normalized({
            from.x + (to.x - from.x) * weight,
            from.y + (to.y - from.y) * weight,
            from.z + (to.z - from.z) * weight,
            from.w + (to.w - from.w) * weight,
        });
    }
    const auto angle = std::acos(std::clamp(cosine, -1.0f, 1.0f));
    const auto denominator = std::sin(angle);
    const auto from_scale = std::sin((1.0f - weight) * angle) / denominator;
    const auto to_scale = std::sin(weight * angle) / denominator;
    return normalized({
        from.x * from_scale + to.x * to_scale,
        from.y * from_scale + to.y * to_scale,
        from.z * from_scale + to.z * to_scale,
        from.w * from_scale + to.w * to_scale,
    });
}

Vector3 quaternion_to_euler(const Quaternion value) {
    const auto rotation = normalized(value);
    const auto sin_x = 2.0f * (rotation.w * rotation.x + rotation.y * rotation.z);
    const auto cos_x = 1.0f - 2.0f * (rotation.x * rotation.x + rotation.y * rotation.y);
    const auto sin_y = 2.0f * (rotation.w * rotation.y - rotation.z * rotation.x);
    const auto sin_z = 2.0f * (rotation.w * rotation.z + rotation.x * rotation.y);
    const auto cos_z = 1.0f - 2.0f * (rotation.y * rotation.y + rotation.z * rotation.z);
    return {
        std::atan2(sin_x, cos_x),
        std::abs(sin_y) >= 1.0f ? std::copysign(std::numbers::pi_v<float> * 0.5f, sin_y) : std::asin(sin_y),
        std::atan2(sin_z, cos_z),
    };
}

Quaternion euler_to_quaternion(const Vector3 value) {
    const auto cx = std::cos(value.x * 0.5f);
    const auto sx = std::sin(value.x * 0.5f);
    const auto cy = std::cos(value.y * 0.5f);
    const auto sy = std::sin(value.y * 0.5f);
    const auto cz = std::cos(value.z * 0.5f);
    const auto sz = std::sin(value.z * 0.5f);
    return normalized({
        sx * cy * cz - cx * sy * sz,
        cx * sy * cz + sx * cy * sz,
        cx * cy * sz - sx * sy * cz,
        cx * cy * cz + sx * sy * sz,
    });
}

Quaternion fixed_axis_rotation(const Quaternion rotation, const Vector3 axis) {
    const auto unit = normalized(axis);
    if (dot(unit, unit) <= epsilon * epsilon) return rotation;
    const auto projected = dot({rotation.x, rotation.y, rotation.z}, unit);
    return normalized({unit.x * projected, unit.y * projected, unit.z * projected, rotation.w});
}

bool finite(const Vector3 value) {
    return std::isfinite(value.x) && std::isfinite(value.y) && std::isfinite(value.z);
}

bool finite(const Quaternion value) {
    return std::isfinite(value.x) && std::isfinite(value.y) && std::isfinite(value.z) && std::isfinite(value.w);
}

}

Pose::Pose(const std::span<const pmx::Bone> bones) {
    for (const auto& bone : bones) {
        const auto valid_index = [&](const std::int32_t index) {
            return index == -1 || (index >= 0 && static_cast<std::size_t>(index) < bones.size());
        };
        if (!valid_index(bone.parent_index) ||
            ((bone.flags & 0x0300) != 0 && !valid_index(bone.inheritance_index)) ||
            ((bone.flags & 0x0020) != 0 && (!valid_index(bone.ik_target_index) ||
                bone.ik_iteration_count < 0 || bone.ik_angle_limit < 0.0f))) {
            throw std::invalid_argument("bone metadata is invalid");
        }
        for (const auto& link : bone.ik_links) {
            if (!valid_index(link.bone_index)) throw std::invalid_argument("IK link index is invalid");
        }
    }
    bones_.reserve(bones.size());
    for (const auto& bone : bones) {
        const auto parent_position = bone.parent_index < 0
            ? Vector3{}
            : Vector3{bones[bone.parent_index].position[0], bones[bone.parent_index].position[1], -bones[bone.parent_index].position[2]};
        const Vector3 bind_position{bone.position[0], bone.position[1], -bone.position[2]};
        bones_.push_back({
            .parent_index = bone.parent_index,
            .deform_layer = bone.deform_layer,
            .inheritance_index = bone.inheritance_index,
            .inheritance_weight = bone.inheritance_weight,
            .flags = bone.flags,
            .bind_position = bind_position,
            .bind_translation = bind_position - parent_position,
            .fixed_axis = {bone.fixed_axis[0], bone.fixed_axis[1], -bone.fixed_axis[2]},
            .ik_target_index = bone.ik_target_index,
            .ik_iteration_count = bone.ik_iteration_count,
            .ik_angle_limit = bone.ik_angle_limit,
            .ik_links = bone.ik_links,
        });
    }

    std::vector<std::uint8_t> states(bones_.size());
    std::function<void(std::uint32_t)> visit = [&](const std::uint32_t index) {
        if (states[index] == 2) return;
        if (states[index] == 1) throw std::invalid_argument("bone dependency graph contains a cycle");
        states[index] = 1;
        const auto& bone = bones_[index];
        if (bone.parent_index >= 0) visit(static_cast<std::uint32_t>(bone.parent_index));
        if ((bone.flags & 0x0300) != 0 && bone.inheritance_index >= 0) {
            visit(static_cast<std::uint32_t>(bone.inheritance_index));
        }
        states[index] = 2;
        evaluation_order_.push_back(index);
    };
    for (std::uint32_t index = 0; index < bones_.size(); ++index) visit(index);

    ik_order_.resize(bones_.size());
    std::iota(ik_order_.begin(), ik_order_.end(), 0u);
    std::stable_sort(ik_order_.begin(), ik_order_.end(), [&](const auto left, const auto right) {
        if (bones_[left].deform_layer != bones_[right].deform_layer) {
            return bones_[left].deform_layer < bones_[right].deform_layer;
        }
        return left < right;
    });
    ik_order_.erase(std::remove_if(ik_order_.begin(), ik_order_.end(), [&](const auto index) {
        return (bones_[index].flags & 0x0020) == 0;
    }), ik_order_.end());

    local_translations_.resize(bones_.size());
    local_rotations_.resize(bones_.size());
    working_rotations_.resize(bones_.size());
    ik_enabled_.resize(bones_.size(), true);
    effective_translations_.resize(bones_.size());
    effective_rotations_.resize(bones_.size());
    global_positions_.resize(bones_.size());
    global_rotations_.resize(bones_.size());
    skinning_matrices_.resize(bones_.size() * 16);
    reset();
}

void Pose::reset() noexcept {
    std::fill(local_translations_.begin(), local_translations_.end(), Vector3{});
    std::fill(local_rotations_.begin(), local_rotations_.end(), Quaternion{});
    std::fill(working_rotations_.begin(), working_rotations_.end(), Quaternion{});
    std::fill(ik_enabled_.begin(), ik_enabled_.end(), true);
    evaluate();
}

bool Pose::set_local_transform(
    const std::uint32_t bone_index,
    const Vector3 translation,
    const Quaternion rotation) noexcept {
    if (bone_index >= bones_.size() || !finite(translation) || !finite(rotation)) return false;
    const auto normalized_rotation = normalized(rotation);
    if (!finite(normalized_rotation)) return false;
    local_translations_[bone_index] = translation;
    local_rotations_[bone_index] = normalized_rotation;
    return true;
}

bool Pose::set_ik_enabled(const std::uint32_t bone_index, const bool enabled) noexcept {
    if (bone_index >= bones_.size() || (bones_[bone_index].flags & 0x0020) == 0) return false;
    ik_enabled_[bone_index] = enabled;
    return true;
}

bool Pose::blend(const Pose& from, const Pose& to, const float weight) noexcept {
    if (from.bones_.size() != bones_.size() || to.bones_.size() != bones_.size() ||
        !std::isfinite(weight) || weight < 0.0f || weight > 1.0f) {
        return false;
    }
    for (std::size_t index = 0; index < bones_.size(); ++index) {
        const auto& left = from.local_translations_[index];
        const auto& right = to.local_translations_[index];
        local_translations_[index] = {
            left.x + (right.x - left.x) * weight,
            left.y + (right.y - left.y) * weight,
            left.z + (right.z - left.z) * weight,
        };
        local_rotations_[index] = slerp(from.local_rotations_[index], to.local_rotations_[index], weight);
        ik_enabled_[index] = weight < 0.5f ? from.ik_enabled_[index] : to.ik_enabled_[index];
    }
    evaluate();
    return true;
}

void Pose::evaluate_hierarchy() noexcept {
    for (const auto index : evaluation_order_) {
        const auto& bone = bones_[index];
        auto translation = local_translations_[index];
        auto rotation = working_rotations_[index];
        if ((bone.flags & 0x0300) != 0 && bone.inheritance_index >= 0) {
            const auto source = static_cast<std::uint32_t>(bone.inheritance_index);
            if ((bone.flags & 0x0200) != 0) {
                translation = translation + effective_translations_[source] * bone.inheritance_weight;
            }
            if ((bone.flags & 0x0100) != 0) {
                rotation = slerp_identity(effective_rotations_[source], bone.inheritance_weight) * rotation;
            }
        }
        if ((bone.flags & 0x0400) != 0) rotation = fixed_axis_rotation(rotation, bone.fixed_axis);
        effective_translations_[index] = translation;
        effective_rotations_[index] = normalized(rotation);
        const auto local_position = bone.bind_translation + translation;
        if (bone.parent_index < 0) {
            global_positions_[index] = local_position;
            global_rotations_[index] = effective_rotations_[index];
        } else {
            const auto parent = static_cast<std::uint32_t>(bone.parent_index);
            global_positions_[index] = global_positions_[parent] + rotate(global_rotations_[parent], local_position);
            global_rotations_[index] = normalized(global_rotations_[parent] * effective_rotations_[index]);
        }
    }
}

void Pose::solve_ik(const RuntimeBone& constraint, const std::uint32_t constraint_index) noexcept {
    if (constraint.ik_target_index < 0 || constraint.ik_links.empty()) return;
    const auto effector = static_cast<std::uint32_t>(constraint.ik_target_index);
    const auto maximum_step = std::max(0.0f, constraint.ik_angle_limit);
    for (std::int32_t iteration = 0; iteration < constraint.ik_iteration_count; ++iteration) {
        if (dot(global_positions_[effector] - global_positions_[constraint_index],
                global_positions_[effector] - global_positions_[constraint_index]) <= epsilon * epsilon) return;
        for (const auto& source_link : constraint.ik_links) {
            if (source_link.bone_index < 0) continue;
            const auto link = static_cast<std::uint32_t>(source_link.bone_index);
            const auto origin = global_positions_[link];
            const auto effector_direction = normalized(global_positions_[effector] - origin);
            const auto goal_direction = normalized(global_positions_[constraint_index] - origin);
            const auto cosine = std::clamp(dot(effector_direction, goal_direction), -1.0f, 1.0f);
            auto axis = cross(effector_direction, goal_direction);
            const auto sine = std::sqrt(dot(axis, axis));
            auto angle = std::atan2(sine, cosine);
            if (!std::isfinite(angle) || angle <= epsilon || sine <= epsilon) continue;
            angle = std::min(angle, maximum_step);
            axis = axis * (1.0f / sine);
            axis = rotate(conjugate(global_rotations_[link]), axis);
            working_rotations_[link] = normalized(working_rotations_[link] * axis_angle(axis, angle));
            if (source_link.limited) {
                auto euler = quaternion_to_euler(working_rotations_[link]);
                const Vector3 lower{-source_link.upper_limit[0], -source_link.upper_limit[1], source_link.lower_limit[2]};
                const Vector3 upper{-source_link.lower_limit[0], -source_link.lower_limit[1], source_link.upper_limit[2]};
                euler.x = std::clamp(euler.x, lower.x, upper.x);
                euler.y = std::clamp(euler.y, lower.y, upper.y);
                euler.z = std::clamp(euler.z, lower.z, upper.z);
                working_rotations_[link] = euler_to_quaternion(euler);
            }
            evaluate_hierarchy();
        }
    }
}

void Pose::write_matrices() noexcept {
    for (std::size_t index = 0; index < bones_.size(); ++index) {
        const auto rotation = global_rotations_[index];
        const auto translation = global_positions_[index] - rotate(rotation, bones_[index].bind_position);
        const auto xx = rotation.x * rotation.x;
        const auto yy = rotation.y * rotation.y;
        const auto zz = rotation.z * rotation.z;
        const auto xy = rotation.x * rotation.y;
        const auto xz = rotation.x * rotation.z;
        const auto yz = rotation.y * rotation.z;
        const auto wx = rotation.w * rotation.x;
        const auto wy = rotation.w * rotation.y;
        const auto wz = rotation.w * rotation.z;
        auto* matrix = skinning_matrices_.data() + index * 16;
        matrix[0] = 1.0f - 2.0f * (yy + zz);
        matrix[1] = 2.0f * (xy + wz);
        matrix[2] = 2.0f * (xz - wy);
        matrix[3] = 0.0f;
        matrix[4] = 2.0f * (xy - wz);
        matrix[5] = 1.0f - 2.0f * (xx + zz);
        matrix[6] = 2.0f * (yz + wx);
        matrix[7] = 0.0f;
        matrix[8] = 2.0f * (xz + wy);
        matrix[9] = 2.0f * (yz - wx);
        matrix[10] = 1.0f - 2.0f * (xx + yy);
        matrix[11] = 0.0f;
        matrix[12] = translation.x;
        matrix[13] = translation.y;
        matrix[14] = translation.z;
        matrix[15] = 1.0f;
    }
}

void Pose::evaluate() noexcept {
    std::copy(local_rotations_.begin(), local_rotations_.end(), working_rotations_.begin());
    evaluate_hierarchy();
    for (const auto index : ik_order_) {
        if (ik_enabled_[index]) solve_ik(bones_[index], index);
    }
    write_matrices();
}

std::uint32_t Pose::bone_count() const noexcept {
    return static_cast<std::uint32_t>(bones_.size());
}

std::span<const float> Pose::skinning_matrices() const noexcept {
    return skinning_matrices_;
}

}
