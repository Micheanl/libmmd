#include "native/libmmd/src/model_physics.hpp"

#include <BulletCollision/BroadphaseCollision/btDbvtBroadphase.h>
#include <BulletCollision/CollisionDispatch/btDefaultCollisionConfiguration.h>
#include <BulletCollision/CollisionShapes/btBoxShape.h>
#include <BulletCollision/CollisionShapes/btCapsuleShape.h>
#include <BulletCollision/CollisionShapes/btSphereShape.h>
#include <BulletDynamics/ConstraintSolver/btGeneric6DofSpringConstraint.h>
#include <BulletDynamics/ConstraintSolver/btSequentialImpulseConstraintSolver.h>
#include <BulletDynamics/Dynamics/btDiscreteDynamicsWorld.h>
#include <LinearMath/btDefaultMotionState.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

namespace libmmd {
namespace {

bool finite(const Vector3 value) {
    return std::isfinite(value.x) && std::isfinite(value.y) && std::isfinite(value.z);
}

bool finite(const btVector3& value) {
    return std::isfinite(value.x()) && std::isfinite(value.y()) && std::isfinite(value.z());
}

bool finite(const std::array<float, 3>& value) {
    return std::all_of(value.begin(), value.end(), [](const float component) { return std::isfinite(component); });
}

btVector3 pmx_position(const std::array<float, 3>& value, const float scale) {
    const btVector3 converted{value[0] * scale, value[1] * scale, -value[2] * scale};
    if (!finite(converted)) throw std::invalid_argument("PMX position overflows the physics scale");
    return converted;
}

btTransform pmx_transform(
    const std::array<float, 3>& position,
    const std::array<float, 3>& rotation,
    const float scale) {
    btQuaternion quaternion;
    quaternion.setEulerZYX(rotation[2], rotation[1], rotation[0]);
    quaternion = {-quaternion.x(), -quaternion.y(), quaternion.z(), quaternion.w()};
    return btTransform{quaternion, pmx_position(position, scale)};
}

btTransform pose_transform(const Pose& pose, const std::uint32_t index, const float scale) {
    BoneTransform transform;
    if (!pose.global_transform(index, transform) || !finite(transform.position)) {
        throw std::invalid_argument("physics animation contains an invalid bone transform");
    }
    const btVector3 position{
        transform.position.x * scale, transform.position.y * scale, transform.position.z * scale};
    btQuaternion rotation{
        transform.rotation.x, transform.rotation.y, transform.rotation.z, transform.rotation.w};
    if (!finite(position) || !std::isfinite(rotation.length2()) || rotation.length2() <= 0.0f) {
        throw std::invalid_argument("physics animation transform overflows the physics scale");
    }
    rotation.normalize();
    return btTransform{rotation, position};
}

void validate_body(const pmx::RigidBody& body, const std::size_t bone_count) {
    const auto valid_index = body.bone_index == -1 ||
        (body.bone_index >= 0 && static_cast<std::size_t>(body.bone_index) < bone_count);
    if (!valid_index || body.collision_group >= 16 || body.shape > 2 || body.mode > 2 ||
        !finite(body.size) || !finite(body.position) || !finite(body.rotation) ||
        !std::isfinite(body.mass) || body.mass < 0.0f ||
        !std::isfinite(body.linear_damping) || body.linear_damping < 0.0f || body.linear_damping > 1.0f ||
        !std::isfinite(body.angular_damping) || body.angular_damping < 0.0f || body.angular_damping > 1.0f ||
        !std::isfinite(body.restitution) || body.restitution < 0.0f || body.restitution > 1.0f ||
        !std::isfinite(body.friction) || body.friction < 0.0f ||
        body.size[0] <= 0.0f || body.size[1] < 0.0f || body.size[2] < 0.0f ||
        (body.shape == 1 && (body.size[1] <= 0.0f || body.size[2] <= 0.0f))) {
        throw std::invalid_argument("PMX rigid body parameters are invalid");
    }
}

void validate_joint(const pmx::Joint& joint, const std::size_t body_count) {
    if (joint.type != 0) throw std::invalid_argument("model physics supports only PMX spring 6DoF joints (type 0)");
    const auto valid_index = [&](const std::int32_t index) {
        return index == -1 || (index >= 0 && static_cast<std::size_t>(index) < body_count);
    };
    if (!valid_index(joint.first_rigid_body_index) || !valid_index(joint.second_rigid_body_index) ||
        joint.first_rigid_body_index == joint.second_rigid_body_index ||
        !finite(joint.position) || !finite(joint.rotation) ||
        !finite(joint.translation_lower_limit) || !finite(joint.translation_upper_limit) ||
        !finite(joint.rotation_lower_limit) || !finite(joint.rotation_upper_limit) ||
        !finite(joint.translation_spring) || !finite(joint.rotation_spring)) {
        throw std::invalid_argument("PMX spring joint parameters are invalid");
    }
    for (std::size_t axis = 0; axis < 3; ++axis) {
        if (joint.translation_spring[axis] < 0.0f || joint.rotation_spring[axis] < 0.0f) {
            throw std::invalid_argument("PMX spring joint stiffness is invalid");
        }
    }
}

}

void validate_model_physics_config(const ModelPhysicsConfig& config) {
    if (!finite(config.gravity) || !std::isfinite(config.meters_per_unit) || config.meters_per_unit <= 0.0f ||
        !std::isfinite(config.fixed_step_seconds) || config.fixed_step_seconds <= 0.0f ||
        config.maximum_substeps == 0 || config.maximum_substeps > static_cast<std::uint32_t>(std::numeric_limits<int>::max()) ||
        config.solver_iterations == 0 || config.solver_iterations > static_cast<std::uint32_t>(std::numeric_limits<int>::max())) {
        throw std::invalid_argument("model physics configuration is invalid");
    }
}

struct ModelPhysics::Impl {
    struct Body {
        std::unique_ptr<btCollisionShape> shape;
        std::unique_ptr<btDefaultMotionState> motion_state;
        std::unique_ptr<btRigidBody> rigid_body;
        btTransform bind_transform;
        btTransform bone_offset;
        btTransform inverse_bone_offset;
        btTransform animation_target;
        std::int32_t bone_index = -1;
        std::uint8_t mode = 0;
    };

    ModelPhysicsConfig config;
    std::size_t bone_count;
    btDefaultCollisionConfiguration collision_configuration;
    btCollisionDispatcher dispatcher{&collision_configuration};
    btDbvtBroadphase broadphase;
    btSequentialImpulseConstraintSolver solver;
    btDiscreteDynamicsWorld world{&dispatcher, &broadphase, &solver, &collision_configuration};
    btRigidBody world_anchor{0.0f, nullptr, nullptr};
    std::vector<Body> bodies;
    std::vector<std::unique_ptr<btGeneric6DofSpringConstraint>> joints;
    std::vector<BonePhysicsOverride> overrides;
    std::vector<std::size_t> driving_body_indices;
    Pose previous_animation;
    Pose sampled_animation;
    double accumulated_seconds = 0.0;
    bool is_initialized = false;

    Impl(const ModelPhysicsConfig configuration, const std::span<const pmx::Bone> bones)
        : config(configuration), bone_count(bones.size()),
          driving_body_indices(bones.size(), std::numeric_limits<std::size_t>::max()),
          previous_animation(bones), sampled_animation(bones) {
        world.setGravity({config.gravity.x, config.gravity.y, config.gravity.z});
        world.getSolverInfo().m_numIterations = static_cast<int>(config.solver_iterations);
    }

    ~Impl() {
        for (const auto& joint : joints) world.removeConstraint(joint.get());
        for (const auto& body : bodies) world.removeRigidBody(body.rigid_body.get());
    }

    void initialize(const std::span<const pmx::Bone> bones, const pack::PhysicsAssets& assets) {
        if (!assets.soft_bodies.empty()) throw std::invalid_argument("model physics does not support PMX soft bodies yet");
        for (const auto& bone : bones) {
            if (!finite(bone.position)) throw std::invalid_argument("physics bone bind position is invalid");
        }
        for (const auto& body : assets.rigid_bodies) validate_body(body, bone_count);
        for (const auto& joint : assets.joints) validate_joint(joint, assets.rigid_bodies.size());
        bodies.reserve(assets.rigid_bodies.size());
        overrides.reserve(assets.rigid_bodies.size());
        for (const auto& source : assets.rigid_bodies) {
            Body body;
            body.bone_index = source.bone_index;
            body.mode = source.mode;
            body.bind_transform = pmx_transform(source.position, source.rotation, config.meters_per_unit);
            body.bone_offset = body.bind_transform;
            if (source.bone_index >= 0) {
                const btTransform bone_bind{btQuaternion::getIdentity(),
                    pmx_position(bones[source.bone_index].position, config.meters_per_unit)};
                body.bone_offset = bone_bind.inverse() * body.bind_transform;
            }
            body.inverse_bone_offset = body.bone_offset.inverse();
            const btVector3 dimensions{
                source.size[0] * config.meters_per_unit,
                source.size[1] * config.meters_per_unit,
                source.size[2] * config.meters_per_unit};
            if (!finite(dimensions) || dimensions.x() <= 0.0f ||
                (source.shape == 1 && (dimensions.y() <= 0.0f || dimensions.z() <= 0.0f))) {
                throw std::invalid_argument("PMX rigid body dimensions overflow the physics scale");
            }
            if (source.shape == 0) body.shape = std::make_unique<btSphereShape>(dimensions.x());
            else if (source.shape == 1) {
                body.shape = std::make_unique<btBoxShape>(dimensions);
                body.shape->setMargin(std::min(body.shape->getMargin(), dimensions[dimensions.minAxis()] * 0.1f));
            } else body.shape = std::make_unique<btCapsuleShape>(dimensions.x(), dimensions.y());
            const auto mass = source.mode == 0 ? 0.0f : source.mass;
            btVector3 inertia{0.0f, 0.0f, 0.0f};
            if (mass > 0.0f) body.shape->calculateLocalInertia(mass, inertia);
            if (!finite(inertia)) throw std::invalid_argument("PMX rigid body inertia overflows the physics scale");
            body.motion_state = std::make_unique<btDefaultMotionState>(body.bind_transform);
            btRigidBody::btRigidBodyConstructionInfo construction{mass, body.motion_state.get(), body.shape.get(), inertia};
            construction.m_linearDamping = source.linear_damping;
            construction.m_angularDamping = source.angular_damping;
            construction.m_restitution = source.restitution;
            construction.m_friction = source.friction;
            body.rigid_body = std::make_unique<btRigidBody>(construction);
            if (source.mode == 0) {
                body.rigid_body->setCollisionFlags(
                    (body.rigid_body->getCollisionFlags() & ~btCollisionObject::CF_STATIC_OBJECT) |
                    btCollisionObject::CF_KINEMATIC_OBJECT);
            }
            body.rigid_body->setActivationState(DISABLE_DEACTIVATION);
            bodies.push_back(std::move(body));
            if (source.mode != 0 && source.bone_index >= 0) {
                driving_body_indices[source.bone_index] = bodies.size() - 1;
            }
            world.addRigidBody(bodies.back().rigid_body.get(), 1 << source.collision_group,
                static_cast<int>(static_cast<std::uint16_t>(~source.collision_mask)));
        }
        joints.reserve(assets.joints.size());
        for (const auto& source : assets.joints) {
            auto& first = source.first_rigid_body_index < 0 ? world_anchor
                : *bodies[source.first_rigid_body_index].rigid_body;
            auto& second = source.second_rigid_body_index < 0 ? world_anchor
                : *bodies[source.second_rigid_body_index].rigid_body;
            const auto joint_transform = pmx_transform(source.position, source.rotation, config.meters_per_unit);
            auto joint = std::make_unique<btGeneric6DofSpringConstraint>(first, second,
                first.getWorldTransform().inverse() * joint_transform,
                second.getWorldTransform().inverse() * joint_transform, true);
            const auto scale = config.meters_per_unit;
            const btVector3 linear_lower{source.translation_lower_limit[0] * scale,
                source.translation_lower_limit[1] * scale, -source.translation_upper_limit[2] * scale};
            const btVector3 linear_upper{source.translation_upper_limit[0] * scale,
                source.translation_upper_limit[1] * scale, -source.translation_lower_limit[2] * scale};
            if (!finite(linear_lower) || !finite(linear_upper)) {
                throw std::invalid_argument("PMX spring joint limits overflow the physics scale");
            }
            joint->setLinearLowerLimit(linear_lower);
            joint->setLinearUpperLimit(linear_upper);
            joint->setAngularLowerLimit({-source.rotation_upper_limit[0], -source.rotation_upper_limit[1],
                source.rotation_lower_limit[2]});
            joint->setAngularUpperLimit({-source.rotation_lower_limit[0], -source.rotation_lower_limit[1],
                source.rotation_upper_limit[2]});
            for (int axis = 0; axis < 3; ++axis) {
                joint->enableSpring(axis, source.translation_spring[axis] > 0.0f);
                joint->setStiffness(axis, source.translation_spring[axis]);
                joint->enableSpring(axis + 3, source.rotation_spring[axis] > 0.0f);
                joint->setStiffness(axis + 3, source.rotation_spring[axis]);
            }
            joint->setEquilibriumPoint();
            joints.push_back(std::move(joint));
            world.addConstraint(joints.back().get(), false);
        }
    }

    void prepare_animation(const Pose& animation) {
        if (animation.bone_count() != bone_count) throw std::invalid_argument("physics animation skeleton has a different size");
        for (auto& body : bodies) {
            body.animation_target = body.bone_index < 0 ? body.bind_transform
                : pose_transform(animation, static_cast<std::uint32_t>(body.bone_index), config.meters_per_unit) * body.bone_offset;
        }
    }

    void write_pose(Pose& output) {
        overrides.clear();
        for (std::size_t index = 0; index < bodies.size(); ++index) {
            const auto& body = bodies[index];
            if (body.mode == 0 || body.bone_index < 0 || driving_body_indices[body.bone_index] != index) continue;
            const auto transform = body.rigid_body->getWorldTransform() * body.inverse_bone_offset;
            const auto position = transform.getOrigin() / config.meters_per_unit;
            const auto rotation = transform.getRotation();
            overrides.push_back({
                .bone_index = static_cast<std::uint32_t>(body.bone_index),
                .transform = {{position.x(), position.y(), position.z()},
                    {rotation.x(), rotation.y(), rotation.z(), rotation.w()}},
                .rotation_only = body.mode == 2,
            });
        }
        if (!output.apply_physics(overrides)) throw std::invalid_argument("model physics produced an invalid bone transform");
    }

    void align_rotation_bodies(const Pose& output) {
        for (auto& body : bodies) {
            if (body.mode != 2 || body.bone_index < 0) continue;
            const auto target = pose_transform(output, static_cast<std::uint32_t>(body.bone_index), config.meters_per_unit) * body.bone_offset;
            auto transform = body.rigid_body->getWorldTransform();
            transform.setOrigin(target.getOrigin());
            body.rigid_body->setCenterOfMassTransform(transform);
            body.rigid_body->setInterpolationWorldTransform(transform);
            body.motion_state->setWorldTransform(transform);
            world.updateSingleAabb(body.rigid_body.get());
        }
    }
};

ModelPhysics::ModelPhysics(
    const std::span<const pmx::Bone> bones,
    const pack::PhysicsAssets& assets,
    const ModelPhysicsConfig config) {
    validate_model_physics_config(config);
    impl_ = std::make_unique<Impl>(config, bones);
    impl_->initialize(bones, assets);
}

ModelPhysics::~ModelPhysics() = default;

void ModelPhysics::reset(const Pose& animation) {
    impl_->prepare_animation(animation);
    for (auto& body : impl_->bodies) {
        body.rigid_body->setCenterOfMassTransform(body.animation_target);
        body.rigid_body->setInterpolationWorldTransform(body.animation_target);
        body.motion_state->setWorldTransform(body.animation_target);
        body.rigid_body->setLinearVelocity({0.0f, 0.0f, 0.0f});
        body.rigid_body->setAngularVelocity({0.0f, 0.0f, 0.0f});
        body.rigid_body->setInterpolationLinearVelocity({0.0f, 0.0f, 0.0f});
        body.rigid_body->setInterpolationAngularVelocity({0.0f, 0.0f, 0.0f});
        body.rigid_body->clearForces();
        body.rigid_body->activate(true);
        if (auto* proxy = body.rigid_body->getBroadphaseHandle()) {
            impl_->broadphase.getOverlappingPairCache()->cleanProxyFromPairs(proxy, &impl_->dispatcher);
        }
        impl_->world.updateSingleAabb(body.rigid_body.get());
    }
    impl_->world.clearForces();
    for (const auto& joint : impl_->joints) {
        joint->internalSetAppliedImpulse(0.0f);
        joint->getTranslationalLimitMotor()->m_accumulatedImpulse.setZero();
        for (int axis = 0; axis < 3; ++axis) joint->getRotationalLimitMotor(axis)->m_accumulatedImpulse = 0.0f;
    }
    impl_->solver.reset();
    impl_->previous_animation = animation;
    impl_->sampled_animation = animation;
    impl_->accumulated_seconds = 0.0;
    impl_->is_initialized = true;
}

bool ModelPhysics::update(const float delta_seconds, const Pose& animation, Pose& output) {
    if (&animation == &output) throw std::invalid_argument("model physics animation and output poses must be distinct");
    if (!std::isfinite(delta_seconds) || delta_seconds < 0.0f) {
        throw std::invalid_argument("model physics frame duration must be finite and nonnegative");
    }
    if (!impl_->is_initialized) reset(animation);
    impl_->prepare_animation(animation);
    const auto fixed_step = static_cast<double>(impl_->config.fixed_step_seconds);
    impl_->accumulated_seconds += delta_seconds;
    const auto requested_steps = std::floor(impl_->accumulated_seconds / fixed_step);
    const auto dropped_time = requested_steps > impl_->config.maximum_substeps;
    const auto step_count = static_cast<std::uint32_t>(std::min(requested_steps,
        static_cast<double>(impl_->config.maximum_substeps)));
    const auto sample_duration = dropped_time ? step_count * fixed_step : impl_->accumulated_seconds;
    impl_->accumulated_seconds = std::fmod(impl_->accumulated_seconds, fixed_step);
    for (std::uint32_t step = 0; step < step_count; ++step) {
        const auto weight = static_cast<float>((step + 1) * fixed_step / sample_duration);
        if (!impl_->sampled_animation.blend(impl_->previous_animation, animation, weight)) {
            throw std::invalid_argument("model physics cannot interpolate the animation skeleton");
        }
        impl_->prepare_animation(impl_->sampled_animation);
        output = impl_->sampled_animation;
        for (auto& body : impl_->bodies) {
            if (body.mode != 0) continue;
            body.motion_state->setWorldTransform(body.animation_target);
        }
        impl_->world.stepSimulation(impl_->config.fixed_step_seconds, 1, impl_->config.fixed_step_seconds);
        impl_->write_pose(output);
        impl_->align_rotation_bodies(output);
    }
    if (step_count != 0) impl_->previous_animation = impl_->sampled_animation;
    output = animation;
    impl_->write_pose(output);
    return dropped_time;
}

}
