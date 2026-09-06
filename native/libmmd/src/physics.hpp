#ifndef LIBMMD_PHYSICS_HPP_
#define LIBMMD_PHYSICS_HPP_

#include "libmmd/libmmd.h"

#include <array>
#include <cstdint>
#include <memory>

namespace libmmd {

class PhysicsBody;

struct PhysicsStep final {
    std::uint32_t substeps{};
    bool dropped_time{};
    float interpolation_alpha{};
    float simulated_seconds{};
};

struct PhysicsRaycast final {
    PhysicsBody* body{};
    std::array<float, 3> position{};
    std::array<float, 3> normal{};
    float distance{};
};

struct PhysicsBodyState final {
    libmmd_transform transform{};
    std::array<float, 3> linear_velocity{};
    std::array<float, 3> angular_velocity{};
    bool sleeping{};
};

class PhysicsEngine final {
public:
    PhysicsEngine();
    ~PhysicsEngine();

    PhysicsEngine(const PhysicsEngine&) = delete;
    PhysicsEngine& operator=(const PhysicsEngine&) = delete;

    [[nodiscard]] bool gpu_available() const noexcept;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;

    friend class PhysicsWorld;
    friend class PhysicsBody;
    friend class PhysicsJoint;
};

class PhysicsWorld final {
public:
    PhysicsWorld(PhysicsEngine& engine, const libmmd_physics_world_config& config);
    ~PhysicsWorld();

    PhysicsWorld(const PhysicsWorld&) = delete;
    PhysicsWorld& operator=(const PhysicsWorld&) = delete;

    [[nodiscard]] PhysicsStep step(float delta_seconds);
    [[nodiscard]] PhysicsRaycast raycast(
        const std::array<float, 3>& origin,
        const std::array<float, 3>& direction,
        float maximum_distance,
        std::uint32_t collision_mask) const;
    [[nodiscard]] std::uint32_t body_count() const noexcept;
    [[nodiscard]] std::uint32_t joint_count() const noexcept;
    [[nodiscard]] std::uint32_t active_processor() const noexcept;
    [[nodiscard]] bool gpu_available() const noexcept;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;

    void add(PhysicsBody& body);
    void remove(PhysicsBody& body) noexcept;
    void add_joint();
    void remove_joint() noexcept;

    friend class PhysicsBody;
    friend class PhysicsJoint;
};

class PhysicsBody final {
public:
    PhysicsBody(PhysicsWorld& world, const libmmd_physics_body_desc& desc);
    ~PhysicsBody();

    PhysicsBody(const PhysicsBody&) = delete;
    PhysicsBody& operator=(const PhysicsBody&) = delete;

    [[nodiscard]] PhysicsBodyState state(float interpolation_alpha) const;
    void set_transform(const libmmd_transform& transform, bool reset_velocity);
    void set_kinematic_target(const libmmd_transform& transform);
    void set_velocity(const std::array<float, 3>& linear, const std::array<float, 3>& angular);
    void add_impulse(const std::array<float, 3>& impulse);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;

    void capture_previous();
    void capture_current();

    friend class PhysicsWorld;
    friend class PhysicsJoint;
};

class PhysicsJoint final {
public:
    PhysicsJoint(
        PhysicsWorld& world,
        PhysicsBody* body_a,
        PhysicsBody* body_b,
        const libmmd_physics_joint_desc& desc);
    ~PhysicsJoint();

    PhysicsJoint(const PhysicsJoint&) = delete;
    PhysicsJoint& operator=(const PhysicsJoint&) = delete;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}

#endif
