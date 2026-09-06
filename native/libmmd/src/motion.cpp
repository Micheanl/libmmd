#include "native/libmmd/src/motion.hpp"

#include "native/libmmd/src/text.hpp"

#include <algorithm>
#include <cmath>
#include <unordered_map>

namespace libmmd {
namespace {

constexpr float frame_rate = 30.0f;

float component(const float time, const float first, const float second) {
    const auto inverse = 1.0f - time;
    return 3.0f * inverse * inverse * time * first +
        3.0f * inverse * time * time * second + time * time * time;
}

template <typename CurveType>
float transform(const CurveType& curve, const float progress) {
    const auto target = std::clamp(progress, 0.0f, 1.0f);
    if (target == 0.0f || target == 1.0f || (curve.x1 == curve.y1 && curve.x2 == curve.y2)) return target;
    auto low = 0.0f;
    auto high = 1.0f;
    for (int iteration = 0; iteration < 24; ++iteration) {
        const auto middle = (low + high) * 0.5f;
        if (component(middle, curve.x1, curve.x2) < target) low = middle; else high = middle;
    }
    return component((low + high) * 0.5f, curve.y1, curve.y2);
}

Quaternion normalized(const Quaternion value) {
    const auto length_squared = value.x * value.x + value.y * value.y + value.z * value.z + value.w * value.w;
    if (!std::isfinite(length_squared) || length_squared <= 1.0e-12f) return {};
    const auto inverse = 1.0f / std::sqrt(length_squared);
    return {value.x * inverse, value.y * inverse, value.z * inverse, value.w * inverse};
}

Quaternion slerp(Quaternion from, Quaternion to, const float amount) {
    from = normalized(from);
    to = normalized(to);
    auto cosine = from.x * to.x + from.y * to.y + from.z * to.z + from.w * to.w;
    if (cosine < 0.0f) {
        to = {-to.x, -to.y, -to.z, -to.w};
        cosine = -cosine;
    }
    if (cosine > 0.9995f) {
        return normalized({
            from.x + (to.x - from.x) * amount,
            from.y + (to.y - from.y) * amount,
            from.z + (to.z - from.z) * amount,
            from.w + (to.w - from.w) * amount,
        });
    }
    const auto angle = std::acos(std::clamp(cosine, -1.0f, 1.0f));
    const auto denominator = std::sin(angle);
    const auto from_scale = std::sin((1.0f - amount) * angle) / denominator;
    const auto to_scale = std::sin(amount * angle) / denominator;
    return normalized({
        from.x * from_scale + to.x * to_scale,
        from.y * from_scale + to.y * to_scale,
        from.z * from_scale + to.z * to_scale,
        from.w * from_scale + to.w * to_scale,
    });
}

template <typename FrameType>
void sort_unique_frames(std::vector<FrameType>& frames) {
    std::stable_sort(frames.begin(), frames.end(), [](const auto& left, const auto& right) {
        return left.frame < right.frame;
    });
    std::vector<FrameType> unique;
    unique.reserve(frames.size());
    for (auto& frame : frames) {
        if (!unique.empty() && unique.back().frame == frame.frame) unique.back() = frame;
        else unique.push_back(frame);
    }
    frames = std::move(unique);
}

}

MotionClip::MotionClip(const std::span<const pmx::Bone> bones, const vmd::Motion& motion)
    : bone_count_(static_cast<std::uint32_t>(bones.size())) {
    std::unordered_map<std::string, std::uint32_t> bone_indices;
    for (std::uint32_t index = 0; index < bones.size(); ++index) {
        bone_indices.try_emplace(bones[index].name, index);
        if (!bones[index].english_name.empty()) bone_indices.try_emplace(bones[index].english_name, index);
    }
    std::unordered_map<std::uint32_t, std::size_t> track_indices;
    for (const auto& source : motion.bone_keyframes) {
        const auto name = decode_windows31j(source.name);
        const auto binding = bone_indices.find(name);
        if (binding == bone_indices.end()) continue;
        const auto bone_index = binding->second;
        const auto [entry, inserted] = track_indices.try_emplace(bone_index, tracks_.size());
        if (inserted) tracks_.push_back({bone_index, {}});
        Frame frame{};
        frame.frame = source.frame;
        frame.translation = {source.translation[0], source.translation[1], -source.translation[2]};
        frame.rotation = normalized({-source.rotation[0], -source.rotation[1], source.rotation[2], source.rotation[3]});
        for (std::size_t component_index = 0; component_index < 4; ++component_index) {
            const auto value = [&](const std::size_t offset) {
                return std::min(127u, static_cast<unsigned>(std::to_integer<std::uint8_t>(source.interpolation[offset]))) / 127.0f;
            };
            frame.curves[component_index] = {
                value(component_index),
                value(component_index + 4),
                value(component_index + 8),
                value(component_index + 12),
            };
        }
        tracks_[entry->second].frames.push_back(frame);
        duration_frames_ = std::max(duration_frames_, source.frame);
    }
    for (auto& track : tracks_) sort_unique_frames(track.frames);

    std::unordered_map<std::uint32_t, std::size_t> ik_track_indices;
    for (const auto& source_frame : motion.ik_keyframes) {
        for (const auto& source_state : source_frame.states) {
            const auto name = decode_windows31j(source_state.name);
            const auto binding = bone_indices.find(name);
            if (binding == bone_indices.end() || (bones[binding->second].flags & 0x0020) == 0) continue;
            const auto bone_index = binding->second;
            const auto [entry, inserted] = ik_track_indices.try_emplace(bone_index, ik_tracks_.size());
            if (inserted) ik_tracks_.push_back({bone_index, {}});
            ik_tracks_[entry->second].frames.push_back({source_frame.frame, source_state.enabled});
            duration_frames_ = std::max(duration_frames_, source_frame.frame);
        }
    }
    for (auto& track : ik_tracks_) sort_unique_frames(track.frames);
}

bool MotionClip::apply(const float time_seconds, const bool looping, Pose& pose) const noexcept {
    if (!std::isfinite(time_seconds) || time_seconds < 0.0f || pose.bone_count() != bone_count_) return false;
    pose.reset();
    auto frame = time_seconds * frame_rate;
    if (duration_frames_ == 0) frame = 0.0f;
    else if (looping) frame = std::fmod(frame, static_cast<float>(duration_frames_));
    else frame = std::min(frame, static_cast<float>(duration_frames_));
    for (const auto& track : tracks_) {
        const auto next = std::lower_bound(track.frames.begin(), track.frames.end(), frame, [](const auto& keyframe, const float value) {
            return static_cast<float>(keyframe.frame) < value;
        });
        if (next == track.frames.begin()) {
            static_cast<void>(pose.set_local_transform(track.bone_index, next->translation, next->rotation));
        } else if (next == track.frames.end()) {
            const auto& value = track.frames.back();
            static_cast<void>(pose.set_local_transform(track.bone_index, value.translation, value.rotation));
        } else if (static_cast<float>(next->frame) == frame) {
            static_cast<void>(pose.set_local_transform(track.bone_index, next->translation, next->rotation));
        } else {
            const auto& previous = *(next - 1);
            const auto progress = (frame - static_cast<float>(previous.frame)) /
                static_cast<float>(next->frame - previous.frame);
            const Vector3 translation{
                previous.translation.x + (next->translation.x - previous.translation.x) * transform(next->curves[0], progress),
                previous.translation.y + (next->translation.y - previous.translation.y) * transform(next->curves[1], progress),
                previous.translation.z + (next->translation.z - previous.translation.z) * transform(next->curves[2], progress),
            };
            static_cast<void>(pose.set_local_transform(track.bone_index, translation,
                slerp(previous.rotation, next->rotation, transform(next->curves[3], progress))));
        }
    }
    for (const auto& track : ik_tracks_) {
        const auto next = std::upper_bound(track.frames.begin(), track.frames.end(), frame, [](const float value, const auto& keyframe) {
            return value < static_cast<float>(keyframe.frame);
        });
        if (next != track.frames.begin()) {
            static_cast<void>(pose.set_ik_enabled(track.bone_index, (next - 1)->enabled));
        }
    }
    pose.evaluate();
    return true;
}

std::uint32_t MotionClip::duration_frames() const noexcept { return duration_frames_; }
std::uint32_t MotionClip::bound_bone_count() const noexcept { return static_cast<std::uint32_t>(tracks_.size()); }
std::uint32_t MotionClip::bound_ik_count() const noexcept { return static_cast<std::uint32_t>(ik_tracks_.size()); }
std::uint32_t MotionClip::bone_count() const noexcept { return bone_count_; }
float MotionClip::duration_seconds() const noexcept { return static_cast<float>(duration_frames_) / frame_rate; }

}
