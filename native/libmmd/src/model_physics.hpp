#ifndef LIBMMD_MODEL_PHYSICS_HPP_
#define LIBMMD_MODEL_PHYSICS_HPP_

#include "native/libmmd/src/pack_physics.hpp"
#include "native/libmmd/src/pose.hpp"

#include <cstdint>
#include <memory>
#include <span>

namespace libmmd {

struct ModelPhysicsConfig {
    Vector3 gravity{0.0f, -9.81f, 0.0f};
    float meters_per_unit = 0.08f;
    float fixed_step_seconds = 1.0f / 120.0f;
    std::uint32_t maximum_substeps = 8;
    std::uint32_t solver_iterations = 10;
};

void validate_model_physics_config(const ModelPhysicsConfig& config);

class ModelPhysics final {
public:
    ModelPhysics(
        std::span<const pmx::Bone> bones,
        const pack::PhysicsAssets& assets,
        ModelPhysicsConfig config);
    ~ModelPhysics();

    ModelPhysics(const ModelPhysics&) = delete;
    ModelPhysics& operator=(const ModelPhysics&) = delete;

    void reset(const Pose& animation);
    [[nodiscard]] bool update(float delta_seconds, const Pose& animation, Pose& output);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}

#endif
