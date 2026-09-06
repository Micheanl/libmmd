#ifndef LIBMMD_SCENE_HPP_
#define LIBMMD_SCENE_HPP_

#include "native/libmmd/src/motion.hpp"
#include "native/libmmd/src/model_physics.hpp"

#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <vector>

namespace libmmd {

struct InstanceTransform {
    Vector3 position;
    Quaternion rotation;
    Vector3 scale{1.0f, 1.0f, 1.0f};
};

struct SceneStep {
    std::uint64_t frame_index;
    std::uint32_t instance_count;
    std::uint32_t animated_instance_count;
    bool dropped_time;
    float delta_seconds;
    float total_seconds;
};

class AnimationController final {
public:
    explicit AnimationController(std::span<const pmx::Bone> bones);

    [[nodiscard]] bool play(const MotionClip& motion, bool looping, float fade_seconds) noexcept;
    [[nodiscard]] bool stop(float fade_seconds) noexcept;
    [[nodiscard]] bool play_overlay(const MotionClip& motion, bool looping, float fade_seconds) noexcept;
    [[nodiscard]] bool stop_overlay(float fade_seconds) noexcept;
    [[nodiscard]] bool update(float delta_seconds) noexcept;
    void detach(const MotionClip& motion) noexcept;

    [[nodiscard]] const Pose& pose() const noexcept;
    [[nodiscard]] bool playing() const noexcept;
    [[nodiscard]] bool overlay_playing() const noexcept;
    [[nodiscard]] bool overlay_looping() const noexcept;
    [[nodiscard]] float overlay_playback_seconds() const noexcept;
    [[nodiscard]] float overlay_transition_weight() const noexcept;
    [[nodiscard]] bool looping() const noexcept;
    [[nodiscard]] float playback_seconds() const noexcept;
    [[nodiscard]] float transition_weight() const noexcept;
    [[nodiscard]] bool uses(const MotionClip& motion) const noexcept;
    [[nodiscard]] bool uses_overlay(const MotionClip& motion) const noexcept;

private:
    void compose_overlay() noexcept;
    Pose pose_;
    Pose base_pose_;
    Pose source_pose_;
    Pose target_pose_;
    const MotionClip* motion_ = nullptr;
    float playback_seconds_ = 0.0f;
    float transition_seconds_ = 0.0f;
    float transition_duration_ = 0.0f;
    bool looping_ = false;
    bool stopping_ = false;
    Pose overlay_source_pose_;
    Pose overlay_target_pose_;
    const MotionClip* overlay_motion_ = nullptr;
    float overlay_playback_seconds_ = 0.0f;
    float overlay_transition_seconds_ = 0.0f;
    float overlay_transition_duration_ = 0.0f;
    bool overlay_looping_ = false;
    bool overlay_stopping_ = false;
    std::vector<std::uint8_t> overlay_bone_mask_;
    std::vector<std::uint8_t> overlay_ik_mask_;
    std::vector<std::uint8_t> overlay_source_bone_mask_;
    std::vector<std::uint8_t> overlay_source_ik_mask_;
};

class ModelInstance final {
public:
    explicit ModelInstance(
        std::span<const pmx::Bone> bones,
        const pack::PhysicsAssets& assets = {},
        const std::optional<ModelPhysicsConfig>& physics_config = std::nullopt);

    [[nodiscard]] bool set_transform(InstanceTransform transform) noexcept;
    void set_visible(bool visible) noexcept;
    [[nodiscard]] bool update(float delta_seconds);
    void reset_physics();

    [[nodiscard]] const InstanceTransform& transform() const noexcept;
    [[nodiscard]] bool visible() const noexcept;
    [[nodiscard]] AnimationController& animation() noexcept;
    [[nodiscard]] const AnimationController& animation() const noexcept;
    [[nodiscard]] const Pose& pose() const noexcept;

private:
    InstanceTransform transform_;
    bool visible_ = true;
    AnimationController animation_;
    std::unique_ptr<ModelPhysics> physics_;
    std::optional<Pose> physics_pose_;
};

class Scene final {
public:
    explicit Scene(float maximum_delta_seconds, std::optional<ModelPhysicsConfig> physics_config = std::nullopt);

    ModelInstance& create_instance(std::span<const pmx::Bone> bones, const pack::PhysicsAssets& assets = {});
    void destroy_instance(ModelInstance& instance) noexcept;
    [[nodiscard]] SceneStep update(float delta_seconds);

    [[nodiscard]] std::uint32_t instance_count() const noexcept;

private:
    float maximum_delta_seconds_;
    std::optional<ModelPhysicsConfig> physics_config_;
    float total_seconds_ = 0.0f;
    std::uint64_t frame_index_ = 0;
    std::vector<std::unique_ptr<ModelInstance>> instances_;
};

}

#endif
