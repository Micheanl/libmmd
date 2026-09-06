#include "native/libmmd/src/scene.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace libmmd {
namespace {

bool finite(const Vector3 value) {
    return std::isfinite(value.x) && std::isfinite(value.y) && std::isfinite(value.z);
}

bool finite(const Quaternion value) {
    return std::isfinite(value.x) && std::isfinite(value.y) && std::isfinite(value.z) && std::isfinite(value.w);
}

float smoothstep(const float value) {
    const auto clamped = std::clamp(value, 0.0f, 1.0f);
    return clamped * clamped * (3.0f - 2.0f * clamped);
}

}

AnimationController::AnimationController(const std::span<const pmx::Bone> bones)
    : pose_(bones), source_pose_(bones), target_pose_(bones) {}

bool AnimationController::play(
    const MotionClip& motion,
    const bool looping,
    const float fade_seconds) noexcept {
    if (motion.bone_count() != pose_.bone_count() || !std::isfinite(fade_seconds) || fade_seconds < 0.0f) {
        return false;
    }
    source_pose_ = pose_;
    motion_ = &motion;
    playback_seconds_ = 0.0f;
    transition_seconds_ = 0.0f;
    transition_duration_ = fade_seconds;
    looping_ = looping;
    stopping_ = false;
    if (!motion_->apply(0.0f, looping_, target_pose_)) return false;
    if (fade_seconds == 0.0f) pose_ = target_pose_;
    return true;
}

bool AnimationController::stop(const float fade_seconds) noexcept {
    if (!std::isfinite(fade_seconds) || fade_seconds < 0.0f) return false;
    source_pose_ = pose_;
    target_pose_.reset();
    motion_ = nullptr;
    playback_seconds_ = 0.0f;
    transition_seconds_ = 0.0f;
    transition_duration_ = fade_seconds;
    looping_ = false;
    stopping_ = fade_seconds > 0.0f;
    if (!stopping_) pose_.reset();
    return true;
}

bool AnimationController::update(const float delta_seconds) noexcept {
    if (!std::isfinite(delta_seconds) || delta_seconds < 0.0f) return false;
    if (motion_ != nullptr) {
        playback_seconds_ += delta_seconds;
        if (!motion_->apply(playback_seconds_, looping_, target_pose_)) return false;
    }
    if (transition_duration_ > 0.0f && (motion_ != nullptr || stopping_)) {
        transition_seconds_ = std::min(transition_seconds_ + delta_seconds, transition_duration_);
        const auto progress = smoothstep(transition_seconds_ / transition_duration_);
        if (!pose_.blend(source_pose_, target_pose_, progress)) return false;
        if (transition_seconds_ >= transition_duration_) {
            transition_duration_ = 0.0f;
            transition_seconds_ = 0.0f;
            stopping_ = false;
        }
    } else if (motion_ != nullptr) {
        pose_ = target_pose_;
    }
    if (motion_ != nullptr && !looping_ && playback_seconds_ >= motion_->duration_seconds() &&
        transition_duration_ == 0.0f) {
        motion_ = nullptr;
    }
    return true;
}

void AnimationController::detach(const MotionClip& motion) noexcept {
    if (motion_ == &motion) static_cast<void>(stop(0.0f));
}

const Pose& AnimationController::pose() const noexcept { return pose_; }
bool AnimationController::playing() const noexcept { return motion_ != nullptr; }
bool AnimationController::looping() const noexcept { return motion_ != nullptr && looping_; }
float AnimationController::playback_seconds() const noexcept { return playback_seconds_; }

float AnimationController::transition_weight() const noexcept {
    if (transition_duration_ == 0.0f) return 1.0f;
    return smoothstep(transition_seconds_ / transition_duration_);
}

bool AnimationController::uses(const MotionClip& motion) const noexcept { return motion_ == &motion; }

ModelInstance::ModelInstance(
    const std::span<const pmx::Bone> bones,
    const pack::PhysicsAssets& assets,
    const std::optional<ModelPhysicsConfig>& physics_config) : animation_(bones) {
    if (physics_config.has_value()) {
        physics_ = std::make_unique<ModelPhysics>(bones, assets, *physics_config);
        physics_pose_.emplace(bones);
        reset_physics();
    }
}

bool ModelInstance::set_transform(const InstanceTransform transform) noexcept {
    if (!finite(transform.position) || !finite(transform.rotation) || !finite(transform.scale) ||
        transform.scale.x <= 0.0f || transform.scale.y <= 0.0f || transform.scale.z <= 0.0f) {
        return false;
    }
    const auto length_squared = transform.rotation.x * transform.rotation.x +
        transform.rotation.y * transform.rotation.y + transform.rotation.z * transform.rotation.z +
        transform.rotation.w * transform.rotation.w;
    if (length_squared <= 1.0e-12f) return false;
    const auto inverse = 1.0f / std::sqrt(length_squared);
    transform_ = transform;
    transform_.rotation = {
        transform.rotation.x * inverse,
        transform.rotation.y * inverse,
        transform.rotation.z * inverse,
        transform.rotation.w * inverse,
    };
    return true;
}

void ModelInstance::set_visible(const bool visible) noexcept { visible_ = visible; }

bool ModelInstance::update(const float delta_seconds) {
    if (!animation_.update(delta_seconds)) throw std::invalid_argument("scene animation update failed");
    return physics_ != nullptr && physics_->update(delta_seconds, animation_.pose(), *physics_pose_);
}

void ModelInstance::reset_physics() {
    if (physics_ == nullptr) throw std::invalid_argument("model instance physics is not enabled");
    physics_->reset(animation_.pose());
    *physics_pose_ = animation_.pose();
}

const InstanceTransform& ModelInstance::transform() const noexcept { return transform_; }
bool ModelInstance::visible() const noexcept { return visible_; }
AnimationController& ModelInstance::animation() noexcept { return animation_; }
const AnimationController& ModelInstance::animation() const noexcept { return animation_; }
const Pose& ModelInstance::pose() const noexcept {
    return physics_pose_.has_value() ? *physics_pose_ : animation_.pose();
}

Scene::Scene(const float maximum_delta_seconds, std::optional<ModelPhysicsConfig> physics_config)
    : maximum_delta_seconds_(maximum_delta_seconds), physics_config_(std::move(physics_config)) {
    if (!std::isfinite(maximum_delta_seconds) || maximum_delta_seconds <= 0.0f) {
        throw std::invalid_argument("scene maximum delta must be finite and positive");
    }
    if (physics_config_.has_value()) validate_model_physics_config(*physics_config_);
}

ModelInstance& Scene::create_instance(const std::span<const pmx::Bone> bones, const pack::PhysicsAssets& assets) {
    auto instance = std::make_unique<ModelInstance>(bones, assets, physics_config_);
    auto& result = *instance;
    instances_.push_back(std::move(instance));
    return result;
}

void Scene::destroy_instance(ModelInstance& instance) noexcept {
    std::erase_if(instances_, [&](const auto& value) { return value.get() == &instance; });
}

SceneStep Scene::update(const float delta_seconds) {
    if (!std::isfinite(delta_seconds) || delta_seconds < 0.0f) {
        throw std::invalid_argument("scene delta must be finite and non-negative");
    }
    const auto resolved_delta = std::min(delta_seconds, maximum_delta_seconds_);
    bool dropped_time = resolved_delta != delta_seconds;
    std::uint32_t animated = 0;
    for (const auto& instance : instances_) {
        const auto physics_dropped_time = instance->update(resolved_delta);
        dropped_time = dropped_time || physics_dropped_time;
        if (instance->animation().playing()) ++animated;
    }
    total_seconds_ += resolved_delta;
    ++frame_index_;
    return {
        .frame_index = frame_index_,
        .instance_count = static_cast<std::uint32_t>(instances_.size()),
        .animated_instance_count = animated,
        .dropped_time = dropped_time,
        .delta_seconds = resolved_delta,
        .total_seconds = total_seconds_,
    };
}

std::uint32_t Scene::instance_count() const noexcept {
    return static_cast<std::uint32_t>(instances_.size());
}

}
