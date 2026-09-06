#include "native/libmmd/src/physics.hpp"

#include <PxPhysicsAPI.h>

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {

using namespace physx;

bool finite(const float value) {
    return std::isfinite(value);
}

bool finite3(const float values[3]) {
    return finite(values[0]) && finite(values[1]) && finite(values[2]);
}

PxVec3 vector(const float values[3]) {
    return {values[0], values[1], values[2]};
}

PxQuat rotation(const float values[4]) {
    const PxQuat value(values[0], values[1], values[2], values[3]);
    if (!value.isFinite() || value.magnitudeSquared() <= 1.0e-12f) {
        throw std::invalid_argument("physics rotation is invalid");
    }
    return value.getNormalized();
}

PxTransform transform(const libmmd_transform& value) {
    if (!finite3(value.position)) throw std::invalid_argument("physics position is invalid");
    return {vector(value.position), rotation(value.rotation)};
}

libmmd_transform transform(const PxTransform& value) {
    return {
        .position = {value.p.x, value.p.y, value.p.z},
        .rotation = {value.q.x, value.q.y, value.q.z, value.q.w},
    };
}

PxQuat interpolate(const PxQuat& from, const PxQuat& to, const float alpha) {
    const float sign = from.dot(to) < 0.0f ? -1.0f : 1.0f;
    PxQuat result(
        from.x + (to.x * sign - from.x) * alpha,
        from.y + (to.y * sign - from.y) * alpha,
        from.z + (to.z * sign - from.z) * alpha,
        from.w + (to.w * sign - from.w) * alpha);
    result.normalize();
    return result;
}

PxFilterFlags filter_shader(
    PxFilterObjectAttributes attributes0,
    PxFilterData data0,
    PxFilterObjectAttributes attributes1,
    PxFilterData data1,
    PxPairFlags& pair_flags,
    const void*,
    PxU32) {
    if ((data0.word0 & data1.word1) == 0 || (data1.word0 & data0.word1) == 0) {
        return PxFilterFlag::eSUPPRESS;
    }
    if (PxFilterObjectIsTrigger(attributes0) || PxFilterObjectIsTrigger(attributes1)) {
        pair_flags = PxPairFlag::eTRIGGER_DEFAULT;
    } else {
        pair_flags = PxPairFlag::eCONTACT_DEFAULT | PxPairFlag::eDETECT_CCD_CONTACT;
    }
    return PxFilterFlag::eDEFAULT;
}

PxGeometryHolder geometry(const libmmd_physics_body_desc& desc) {
    if (!finite3(desc.dimensions)) throw std::invalid_argument("physics dimensions are invalid");
    switch (desc.shape) {
        case LIBMMD_PHYSICS_SHAPE_SPHERE:
            if (desc.dimensions[0] <= 0.0f) throw std::invalid_argument("sphere radius must be positive");
            return PxGeometryHolder(PxSphereGeometry(desc.dimensions[0]));
        case LIBMMD_PHYSICS_SHAPE_BOX:
            if (desc.dimensions[0] <= 0.0f || desc.dimensions[1] <= 0.0f || desc.dimensions[2] <= 0.0f) {
                throw std::invalid_argument("box half extents must be positive");
            }
            return PxGeometryHolder(PxBoxGeometry(vector(desc.dimensions)));
        case LIBMMD_PHYSICS_SHAPE_CAPSULE:
            if (desc.dimensions[0] <= 0.0f || desc.dimensions[1] <= 0.0f) {
                throw std::invalid_argument("capsule radius and half height must be positive");
            }
            return PxGeometryHolder(PxCapsuleGeometry(desc.dimensions[0], desc.dimensions[1]));
        default:
            throw std::invalid_argument("physics shape is unsupported");
    }
}

bool valid_processor(const std::uint32_t value) {
    return value <= LIBMMD_PHYSICS_PROCESSOR_CUDA;
}

class QueryFilter final : public PxQueryFilterCallback {
public:
    explicit QueryFilter(const std::uint32_t mask) : mask_(mask) {}

    PxQueryHitType::Enum preFilter(
        const PxFilterData&,
        const PxShape* shape,
        const PxRigidActor*,
        PxHitFlags&) override {
        return (shape->getQueryFilterData().word0 & mask_) == 0
            ? PxQueryHitType::eNONE
            : PxQueryHitType::eBLOCK;
    }

    PxQueryHitType::Enum postFilter(
        const PxFilterData&,
        const PxQueryHit&,
        const PxShape*,
        const PxRigidActor*) override {
        return PxQueryHitType::eBLOCK;
    }

private:
    std::uint32_t mask_;
};

}

namespace libmmd {

struct PhysicsEngine::Impl final {
    PxDefaultAllocator allocator;
    PxDefaultErrorCallback error_callback;
    PxFoundation* foundation{};
    PxPhysics* physics{};
#ifdef LIBMMD_WITH_CUDA
    PxCudaContextManager* cuda_context{};
#endif

    ~Impl() {
        if (physics != nullptr) physics->release();
#ifdef LIBMMD_WITH_CUDA
        if (cuda_context != nullptr) cuda_context->release();
#endif
        if (foundation != nullptr) foundation->release();
    }
};

PhysicsEngine::PhysicsEngine() : impl_(std::make_unique<Impl>()) {
    impl_->foundation = PxCreateFoundation(PX_PHYSICS_VERSION, impl_->allocator, impl_->error_callback);
    if (impl_->foundation == nullptr) throw std::runtime_error("unable to create PhysX foundation");
#ifdef LIBMMD_WITH_CUDA
    PxCudaContextManagerDesc cuda_desc;
    impl_->cuda_context = PxCreateCudaContextManager(*impl_->foundation, cuda_desc, PxGetProfilerCallback());
    if (impl_->cuda_context != nullptr && !impl_->cuda_context->contextIsValid()) {
        impl_->cuda_context->release();
        impl_->cuda_context = nullptr;
    }
#endif
    impl_->physics = PxCreatePhysics(PX_PHYSICS_VERSION, *impl_->foundation, PxTolerancesScale(), true);
    if (impl_->physics == nullptr) throw std::runtime_error("unable to create PhysX runtime");
}

PhysicsEngine::~PhysicsEngine() = default;

bool PhysicsEngine::gpu_available() const noexcept {
#ifdef LIBMMD_WITH_CUDA
    return impl_->cuda_context != nullptr;
#else
    return false;
#endif
}

struct PhysicsWorld::Impl final {
    Impl(PhysicsEngine& value, const libmmd_physics_world_config& config)
        : engine(value),
          fixed_step(config.fixed_step_seconds),
          maximum_frame(config.maximum_frame_seconds),
          maximum_substeps(config.maximum_substeps) {}

    PhysicsEngine& engine;
    PxDefaultCpuDispatcher* dispatcher{};
    PxScene* scene{};
    std::vector<PhysicsBody*> bodies;
    float fixed_step{};
    float maximum_frame{};
    float accumulator{};
    std::uint32_t maximum_substeps{};
    std::uint32_t joints{};
    std::uint32_t active_processor{LIBMMD_PHYSICS_PROCESSOR_CPU};

    ~Impl() {
        if (scene != nullptr) scene->release();
        if (dispatcher != nullptr) dispatcher->release();
    }
};

PhysicsWorld::PhysicsWorld(PhysicsEngine& engine, const libmmd_physics_world_config& config)
    : impl_(std::make_unique<Impl>(engine, config)) {
    if (!valid_processor(config.preferred_processor)) throw std::invalid_argument("physics processor is invalid");
    if (!finite3(config.gravity)) throw std::invalid_argument("physics gravity is invalid");
    if (!finite(config.fixed_step_seconds) || config.fixed_step_seconds <= 0.0f) {
        throw std::invalid_argument("physics fixed step must be positive");
    }
    if (!finite(config.maximum_frame_seconds) || config.maximum_frame_seconds < config.fixed_step_seconds) {
        throw std::invalid_argument("physics maximum frame must cover one fixed step");
    }
    if (config.maximum_substeps == 0 || config.worker_threads == 0) {
        throw std::invalid_argument("physics worker and substep counts must be positive");
    }
    impl_->dispatcher = PxDefaultCpuDispatcherCreate(config.worker_threads);
    if (impl_->dispatcher == nullptr) throw std::runtime_error("unable to create PhysX CPU dispatcher");
    PxSceneDesc scene_desc(engine.impl_->physics->getTolerancesScale());
    scene_desc.gravity = vector(config.gravity);
    scene_desc.cpuDispatcher = impl_->dispatcher;
    scene_desc.filterShader = filter_shader;
    scene_desc.solverType = PxSolverType::eTGS;
    scene_desc.flags |= PxSceneFlag::eENABLE_STABILIZATION;
    scene_desc.flags |= PxSceneFlag::eENABLE_FRICTION_EVERY_ITERATION;
    if ((config.flags & LIBMMD_PHYSICS_WORLD_ENABLE_CCD) != 0) {
        scene_desc.flags |= PxSceneFlag::eENABLE_CCD;
    }
    if ((config.flags & LIBMMD_PHYSICS_WORLD_ENHANCED_DETERMINISM) != 0) {
        scene_desc.flags |= PxSceneFlag::eENABLE_ENHANCED_DETERMINISM;
    }
#ifdef LIBMMD_WITH_CUDA
    const bool use_cuda = config.preferred_processor != LIBMMD_PHYSICS_PROCESSOR_CPU && engine.gpu_available();
    if (use_cuda) {
        scene_desc.cudaContextManager = engine.impl_->cuda_context;
        scene_desc.flags |= PxSceneFlag::eENABLE_GPU_DYNAMICS;
        scene_desc.broadPhaseType = PxBroadPhaseType::eGPU;
        impl_->active_processor = LIBMMD_PHYSICS_PROCESSOR_CUDA;
    }
#endif
    impl_->scene = engine.impl_->physics->createScene(scene_desc);
#ifdef LIBMMD_WITH_CUDA
    if (impl_->scene == nullptr && impl_->active_processor == LIBMMD_PHYSICS_PROCESSOR_CUDA && config.allow_cpu_fallback != 0) {
        scene_desc.cudaContextManager = nullptr;
        scene_desc.flags &= ~PxSceneFlag::eENABLE_GPU_DYNAMICS;
        scene_desc.broadPhaseType = PxBroadPhaseType::eABP;
        impl_->active_processor = LIBMMD_PHYSICS_PROCESSOR_CPU;
        impl_->scene = engine.impl_->physics->createScene(scene_desc);
    }
#endif
    if (impl_->scene == nullptr) throw std::runtime_error("unable to create PhysX scene");
}

PhysicsWorld::~PhysicsWorld() = default;

PhysicsStep PhysicsWorld::step(const float delta_seconds) {
    if (!finite(delta_seconds) || delta_seconds < 0.0f) {
        throw std::invalid_argument("physics delta must be finite and non-negative");
    }
    impl_->accumulator = std::min(impl_->accumulator + delta_seconds, impl_->maximum_frame);
    PhysicsStep result{};
    while (impl_->accumulator >= impl_->fixed_step && result.substeps < impl_->maximum_substeps) {
        for (auto* body : impl_->bodies) body->capture_previous();
        impl_->scene->simulate(impl_->fixed_step);
        if (!impl_->scene->fetchResults(true)) throw std::runtime_error("PhysX simulation did not finish");
        for (auto* body : impl_->bodies) body->capture_current();
        impl_->accumulator -= impl_->fixed_step;
        ++result.substeps;
    }
    if (impl_->accumulator >= impl_->fixed_step) {
        impl_->accumulator = std::fmod(impl_->accumulator, impl_->fixed_step);
        result.dropped_time = true;
    }
    result.interpolation_alpha = std::clamp(impl_->accumulator / impl_->fixed_step, 0.0f, 1.0f);
    result.simulated_seconds = static_cast<float>(result.substeps) * impl_->fixed_step;
    return result;
}

PhysicsRaycast PhysicsWorld::raycast(
    const std::array<float, 3>& origin,
    const std::array<float, 3>& direction,
    const float maximum_distance,
    const std::uint32_t collision_mask) const {
    if (!finite3(origin.data()) || !finite3(direction.data()) || !finite(maximum_distance) || maximum_distance <= 0.0f) {
        throw std::invalid_argument("physics raycast arguments are invalid");
    }
    auto ray_direction = vector(direction.data());
    const auto length = ray_direction.magnitude();
    if (length <= 1.0e-8f) throw std::invalid_argument("physics raycast direction is zero");
    ray_direction /= length;
    PxRaycastBuffer hit;
    PxQueryFilterData filter;
    filter.flags = PxQueryFlag::eSTATIC | PxQueryFlag::eDYNAMIC | PxQueryFlag::ePREFILTER;
    QueryFilter callback(collision_mask);
    const bool found = impl_->scene->raycast(
        vector(origin.data()), ray_direction, maximum_distance, hit, PxHitFlag::eDEFAULT, filter, &callback);
    if (!found || !hit.hasBlock) return {};
    auto* body = hit.block.actor == nullptr ? nullptr : static_cast<PhysicsBody*>(hit.block.actor->userData);
    return {
        .body = body,
        .position = {hit.block.position.x, hit.block.position.y, hit.block.position.z},
        .normal = {hit.block.normal.x, hit.block.normal.y, hit.block.normal.z},
        .distance = hit.block.distance,
    };
}

std::uint32_t PhysicsWorld::body_count() const noexcept {
    return static_cast<std::uint32_t>(impl_->bodies.size());
}

std::uint32_t PhysicsWorld::joint_count() const noexcept {
    return impl_->joints;
}

std::uint32_t PhysicsWorld::active_processor() const noexcept {
    return impl_->active_processor;
}

bool PhysicsWorld::gpu_available() const noexcept {
    return impl_->engine.gpu_available();
}

void PhysicsWorld::add(PhysicsBody& body) {
    impl_->bodies.push_back(&body);
}

void PhysicsWorld::remove(PhysicsBody& body) noexcept {
    const auto iterator = std::find(impl_->bodies.begin(), impl_->bodies.end(), &body);
    if (iterator != impl_->bodies.end()) impl_->bodies.erase(iterator);
}

void PhysicsWorld::add_joint() {
    ++impl_->joints;
}

void PhysicsWorld::remove_joint() noexcept {
    if (impl_->joints != 0) --impl_->joints;
}

struct PhysicsBody::Impl final {
    explicit Impl(PhysicsWorld& value) : world(value) {}

    PhysicsWorld& world;
    PxRigidActor* actor{};
    PxRigidDynamic* dynamic{};
    PxTransform previous{PxIdentity};
    PxTransform current{PxIdentity};
    bool kinematic{};

    ~Impl() {
        if (actor != nullptr) actor->release();
    }
};

PhysicsBody::PhysicsBody(PhysicsWorld& world, const libmmd_physics_body_desc& desc)
    : impl_(std::make_unique<Impl>(world)) {
    if (desc.body_type > LIBMMD_PHYSICS_BODY_KINEMATIC) {
        throw std::invalid_argument("physics body type is invalid");
    }
    if (!finite(desc.static_friction) || desc.static_friction < 0.0f ||
        !finite(desc.dynamic_friction) || desc.dynamic_friction < 0.0f ||
        !finite(desc.restitution) || desc.restitution < 0.0f || desc.restitution > 1.0f ||
        !finite(desc.linear_damping) || desc.linear_damping < 0.0f ||
        !finite(desc.angular_damping) || desc.angular_damping < 0.0f) {
        throw std::invalid_argument("physics material is invalid");
    }
    const auto body_transform = transform(desc.transform);
    const auto body_geometry = geometry(desc);
    auto* material = world.impl_->engine.impl_->physics->createMaterial(
        desc.static_friction, desc.dynamic_friction, desc.restitution);
    if (material == nullptr) throw std::runtime_error("unable to create PhysX material");
    if (desc.body_type == LIBMMD_PHYSICS_BODY_STATIC) {
        impl_->actor = PxCreateStatic(
            *world.impl_->engine.impl_->physics, body_transform, body_geometry.any(), *material);
    } else {
        if (!finite(desc.mass) || desc.mass <= 0.0f) {
            material->release();
            throw std::invalid_argument("dynamic body mass must be positive");
        }
        impl_->dynamic = PxCreateDynamic(
            *world.impl_->engine.impl_->physics, body_transform, body_geometry.any(), *material, 1.0f);
        impl_->actor = impl_->dynamic;
        if (impl_->dynamic != nullptr) {
            PxRigidBodyExt::setMassAndUpdateInertia(*impl_->dynamic, desc.mass);
            impl_->dynamic->setLinearDamping(std::max(0.0f, desc.linear_damping));
            impl_->dynamic->setAngularDamping(std::max(0.0f, desc.angular_damping));
            impl_->dynamic->setSolverIterationCounts(
                std::max(1u, desc.solver_position_iterations),
                std::max(1u, desc.solver_velocity_iterations));
            impl_->kinematic = desc.body_type == LIBMMD_PHYSICS_BODY_KINEMATIC;
            impl_->dynamic->setRigidBodyFlag(PxRigidBodyFlag::eKINEMATIC, impl_->kinematic);
            impl_->dynamic->setRigidBodyFlag(
                PxRigidBodyFlag::eENABLE_CCD,
                (desc.flags & LIBMMD_PHYSICS_BODY_ENABLE_CCD) != 0 && !impl_->kinematic);
            impl_->dynamic->setActorFlag(
                PxActorFlag::eDISABLE_GRAVITY,
                (desc.flags & LIBMMD_PHYSICS_BODY_DISABLE_GRAVITY) != 0);
        }
    }
    material->release();
    if (impl_->actor == nullptr) throw std::runtime_error("unable to create PhysX rigid body");
    PxShape* shape{};
    if (impl_->actor->getShapes(&shape, 1) != 1 || shape == nullptr) {
        throw std::runtime_error("PhysX rigid body has no collision shape");
    }
    const PxFilterData filter(desc.collision_group, desc.collision_mask, 0, 0);
    shape->setSimulationFilterData(filter);
    shape->setQueryFilterData(filter);
    impl_->actor->userData = this;
    impl_->previous = body_transform;
    impl_->current = body_transform;
    if (!world.impl_->scene->addActor(*impl_->actor)) throw std::runtime_error("unable to add PhysX actor");
    world.add(*this);
}

PhysicsBody::~PhysicsBody() {
    impl_->world.remove(*this);
}

PhysicsBodyState PhysicsBody::state(const float interpolation_alpha) const {
    if (!finite(interpolation_alpha)) throw std::invalid_argument("physics interpolation alpha is invalid");
    const auto alpha = std::clamp(interpolation_alpha, 0.0f, 1.0f);
    const PxTransform pose(
        impl_->previous.p + (impl_->current.p - impl_->previous.p) * alpha,
        interpolate(impl_->previous.q, impl_->current.q, alpha));
    PhysicsBodyState result{.transform = transform(pose)};
    if (impl_->dynamic != nullptr) {
        const auto linear = impl_->dynamic->getLinearVelocity();
        const auto angular = impl_->dynamic->getAngularVelocity();
        result.linear_velocity = {linear.x, linear.y, linear.z};
        result.angular_velocity = {angular.x, angular.y, angular.z};
        result.sleeping = impl_->dynamic->isSleeping();
    }
    return result;
}

void PhysicsBody::set_transform(const libmmd_transform& value, const bool reset_velocity) {
    const auto pose = transform(value);
    impl_->actor->setGlobalPose(pose, true);
    impl_->previous = pose;
    impl_->current = pose;
    if (reset_velocity && impl_->dynamic != nullptr && !impl_->kinematic) {
        impl_->dynamic->setLinearVelocity(PxVec3(0.0f));
        impl_->dynamic->setAngularVelocity(PxVec3(0.0f));
    }
}

void PhysicsBody::set_kinematic_target(const libmmd_transform& value) {
    if (impl_->dynamic == nullptr || !impl_->kinematic) {
        throw std::invalid_argument("physics body is not kinematic");
    }
    impl_->dynamic->setKinematicTarget(transform(value));
}

void PhysicsBody::set_velocity(
    const std::array<float, 3>& linear,
    const std::array<float, 3>& angular) {
    if (impl_->dynamic == nullptr || impl_->kinematic || !finite3(linear.data()) || !finite3(angular.data())) {
        throw std::invalid_argument("physics body velocity is invalid");
    }
    impl_->dynamic->setLinearVelocity(vector(linear.data()));
    impl_->dynamic->setAngularVelocity(vector(angular.data()));
}

void PhysicsBody::add_impulse(const std::array<float, 3>& impulse) {
    if (impl_->dynamic == nullptr || impl_->kinematic || !finite3(impulse.data())) {
        throw std::invalid_argument("physics body impulse is invalid");
    }
    impl_->dynamic->addForce(vector(impulse.data()), PxForceMode::eIMPULSE, true);
}

void PhysicsBody::capture_previous() {
    impl_->previous = impl_->current;
}

void PhysicsBody::capture_current() {
    impl_->current = impl_->actor->getGlobalPose();
}

struct PhysicsJoint::Impl final {
    explicit Impl(PhysicsWorld& value) : world(value) {}

    PhysicsWorld& world;
    PxD6Joint* joint{};
    bool registered{};

    ~Impl() {
        if (joint != nullptr) joint->release();
        if (registered) world.remove_joint();
    }
};

PhysicsJoint::PhysicsJoint(
    PhysicsWorld& world,
    PhysicsBody* body_a,
    PhysicsBody* body_b,
    const libmmd_physics_joint_desc& desc)
    : impl_(std::make_unique<Impl>(world)) {
    if (body_a == nullptr && body_b == nullptr) throw std::invalid_argument("physics joint needs a body");
    if (!finite(desc.stiffness) || desc.stiffness < 0.0f ||
        !finite(desc.damping) || desc.damping < 0.0f) {
        throw std::invalid_argument("physics joint spring is invalid");
    }
    auto* actor_a = body_a == nullptr ? nullptr : body_a->impl_->actor;
    auto* actor_b = body_b == nullptr ? nullptr : body_b->impl_->actor;
    impl_->joint = PxD6JointCreate(
        *world.impl_->engine.impl_->physics,
        actor_a,
        transform(desc.local_frame_a),
        actor_b,
        transform(desc.local_frame_b));
    if (impl_->joint == nullptr) throw std::runtime_error("unable to create PhysX D6 joint");
    const PxSpring spring(std::max(0.0f, desc.stiffness), std::max(0.0f, desc.damping));
    const PxD6Axis::Enum linear_axes[] = {PxD6Axis::eX, PxD6Axis::eY, PxD6Axis::eZ};
    for (std::size_t index = 0; index < 3; ++index) {
        if (!finite(desc.linear_lower[index]) || !finite(desc.linear_upper[index]) ||
            desc.linear_lower[index] > desc.linear_upper[index]) {
            throw std::invalid_argument("physics joint linear limit is invalid");
        }
        impl_->joint->setMotion(linear_axes[index], PxD6Motion::eLIMITED);
        impl_->joint->setLinearLimit(
            linear_axes[index],
            PxJointLinearLimitPair(desc.linear_lower[index], desc.linear_upper[index], spring));
    }
    for (std::size_t index = 0; index < 3; ++index) {
        if (!finite(desc.angular_lower[index]) || !finite(desc.angular_upper[index]) ||
            desc.angular_lower[index] > desc.angular_upper[index]) {
            throw std::invalid_argument("physics joint angular limit is invalid");
        }
    }
    impl_->joint->setMotion(PxD6Axis::eTWIST, PxD6Motion::eLIMITED);
    impl_->joint->setTwistLimit(PxJointAngularLimitPair(
        desc.angular_lower[0], desc.angular_upper[0], spring));
    impl_->joint->setMotion(PxD6Axis::eSWING1, PxD6Motion::eLIMITED);
    impl_->joint->setMotion(PxD6Axis::eSWING2, PxD6Motion::eLIMITED);
    impl_->joint->setPyramidSwingLimit(PxJointLimitPyramid(
        desc.angular_lower[1],
        desc.angular_upper[1],
        desc.angular_lower[2],
        desc.angular_upper[2],
        spring));
    if (finite(desc.break_force) && finite(desc.break_torque) &&
        desc.break_force > 0.0f && desc.break_torque > 0.0f) {
        impl_->joint->setBreakForce(desc.break_force, desc.break_torque);
    }
    impl_->joint->setConstraintFlag(
        PxConstraintFlag::eCOLLISION_ENABLED,
        (desc.flags & LIBMMD_PHYSICS_JOINT_COLLISION) != 0);
    world.add_joint();
    impl_->registered = true;
}

PhysicsJoint::~PhysicsJoint() = default;

}
