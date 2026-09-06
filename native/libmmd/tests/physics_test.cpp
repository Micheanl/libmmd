#include "libmmd/libmmd.h"

#include <cassert>
#include <cmath>
#include <cstdint>

namespace {

libmmd_transform pose(const float x, const float y, const float z) {
    return {
        .position = {x, y, z},
        .rotation = {0.0f, 0.0f, 0.0f, 1.0f},
    };
}

libmmd_physics_body_desc body_desc(
    const std::uint32_t type,
    const std::uint32_t shape,
    const libmmd_transform transform) {
    return {
        .abi_version = LIBMMD_ABI_VERSION,
        .struct_size = sizeof(libmmd_physics_body_desc),
        .body_type = type,
        .shape = shape,
        .transform = transform,
        .dimensions = {0.5f, 0.5f, 0.5f},
        .mass = 1.0f,
        .static_friction = 0.6f,
        .dynamic_friction = 0.5f,
        .restitution = 0.0f,
        .linear_damping = 0.05f,
        .angular_damping = 0.1f,
        .collision_group = 1,
        .collision_mask = 0xffffffffu,
        .solver_position_iterations = 8,
        .solver_velocity_iterations = 2,
        .flags = LIBMMD_PHYSICS_BODY_ENABLE_CCD,
    };
}

}

int main() {
    libmmd_runtime* runtime{};
    assert(libmmd_runtime_create(nullptr, &runtime) == LIBMMD_STATUS_OK);

    libmmd_physics_world_config config{
        .abi_version = LIBMMD_ABI_VERSION,
        .struct_size = sizeof(libmmd_physics_world_config),
        .gravity = {0.0f, -9.81f, 0.0f},
        .fixed_step_seconds = 1.0f / 120.0f,
        .maximum_frame_seconds = 0.25f,
        .maximum_substeps = 8,
        .worker_threads = 2,
        .preferred_processor = LIBMMD_PHYSICS_PROCESSOR_AUTO,
        .allow_cpu_fallback = 1,
        .flags = LIBMMD_PHYSICS_WORLD_ENABLE_CCD | LIBMMD_PHYSICS_WORLD_ENHANCED_DETERMINISM,
    };
    libmmd_physics_world* world{};
    assert(libmmd_physics_world_create(runtime, &config, &world) == LIBMMD_STATUS_OK);

    auto ground_desc = body_desc(
        LIBMMD_PHYSICS_BODY_STATIC,
        LIBMMD_PHYSICS_SHAPE_BOX,
        pose(0.0f, -0.5f, 0.0f));
    ground_desc.dimensions[0] = 10.0f;
    ground_desc.dimensions[2] = 10.0f;
    libmmd_physics_body* ground{};
    assert(libmmd_physics_body_create(world, &ground_desc, &ground) == LIBMMD_STATUS_OK);

    auto sphere_desc = body_desc(
        LIBMMD_PHYSICS_BODY_DYNAMIC,
        LIBMMD_PHYSICS_SHAPE_SPHERE,
        pose(0.0f, 3.0f, 0.0f));
    libmmd_physics_body* sphere{};
    assert(libmmd_physics_body_create(world, &sphere_desc, &sphere) == LIBMMD_STATUS_OK);

    libmmd_physics_device_info device{};
    device.abi_version = LIBMMD_ABI_VERSION;
    device.struct_size = sizeof(libmmd_physics_device_info);
    assert(libmmd_physics_world_get_device_info(world, &device) == LIBMMD_STATUS_OK);
#ifdef LIBMMD_TEST_CUDA
    assert(device.active_processor == LIBMMD_PHYSICS_PROCESSOR_CUDA);
    assert(device.gpu_available == 1);
#else
    assert(device.active_processor == LIBMMD_PHYSICS_PROCESSOR_CPU);
    assert(device.gpu_available == 0);
#endif
    assert(device.body_count == 2);

    libmmd_physics_step_info step{};
    for (int frame = 0; frame < 180; ++frame) {
        step.abi_version = LIBMMD_ABI_VERSION;
        step.struct_size = sizeof(libmmd_physics_step_info);
        assert(libmmd_physics_world_step(world, 1.0f / 60.0f, &step) == LIBMMD_STATUS_OK);
        assert(step.substep_count == 2);
        assert(step.dropped_time == 0);
    }

    libmmd_physics_body_state state{};
    state.abi_version = LIBMMD_ABI_VERSION;
    state.struct_size = sizeof(libmmd_physics_body_state);
    assert(libmmd_physics_body_get_state(sphere, step.interpolation_alpha, &state) == LIBMMD_STATUS_OK);
    assert(state.transform.position[1] > 0.45f);
    assert(state.transform.position[1] < 0.6f);

    libmmd_physics_raycast_hit hit{};
    hit.abi_version = LIBMMD_ABI_VERSION;
    hit.struct_size = sizeof(libmmd_physics_raycast_hit);
    const float origin[] = {0.0f, 5.0f, 0.0f};
    const float direction[] = {0.0f, -1.0f, 0.0f};
    assert(libmmd_physics_world_raycast(
        world, origin, direction, 10.0f, 0xffffffffu, &hit) == LIBMMD_STATUS_OK);
    assert(hit.body == sphere);
    assert(hit.distance > 3.9f && hit.distance < 4.1f);

    libmmd_physics_joint_desc joint_desc{
        .abi_version = LIBMMD_ABI_VERSION,
        .struct_size = sizeof(libmmd_physics_joint_desc),
        .body_a = nullptr,
        .body_b = sphere,
        .local_frame_a = pose(0.0f, 0.5f, 0.0f),
        .local_frame_b = pose(0.0f, 0.0f, 0.0f),
        .linear_lower = {0.0f, 0.0f, 0.0f},
        .linear_upper = {0.0f, 0.0f, 0.0f},
        .angular_lower = {-0.2f, -0.2f, -0.2f},
        .angular_upper = {0.2f, 0.2f, 0.2f},
        .stiffness = 20.0f,
        .damping = 4.0f,
        .break_force = 0.0f,
        .break_torque = 0.0f,
        .flags = 0,
    };
    libmmd_physics_joint* joint{};
    assert(libmmd_physics_joint_create_d6(world, &joint_desc, &joint) == LIBMMD_STATUS_OK);
    device.abi_version = LIBMMD_ABI_VERSION;
    device.struct_size = sizeof(libmmd_physics_device_info);
    assert(libmmd_physics_world_get_device_info(world, &device) == LIBMMD_STATUS_OK);
    assert(device.joint_count == 1);
    libmmd_physics_joint_destroy(joint);

    config.preferred_processor = LIBMMD_PHYSICS_PROCESSOR_CUDA;
    config.allow_cpu_fallback = 0;
    libmmd_physics_world* gpu_world{};
#ifdef LIBMMD_TEST_CUDA
    assert(libmmd_physics_world_create(runtime, &config, &gpu_world) == LIBMMD_STATUS_OK);
    assert(gpu_world != nullptr);
    libmmd_physics_world_destroy(gpu_world);
#else
    assert(libmmd_physics_world_create(runtime, &config, &gpu_world) == LIBMMD_STATUS_UNSUPPORTED_FEATURE);
    assert(gpu_world == nullptr);
#endif

    libmmd_physics_world_destroy(world);
    libmmd_runtime_destroy(runtime);
    return 0;
}
