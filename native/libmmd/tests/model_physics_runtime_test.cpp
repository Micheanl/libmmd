#include "libmmd/libmmd.h"
#include "native/libmmd/src/mmdpack.hpp"

#include <cassert>
#include <cmath>
#include <cstring>
#include <limits>

namespace {

libmmd::pmx::Model falling_model() {
    libmmd::pmx::Model model;
    model.vertices.resize(1);
    model.vertices.front().bone_indices[0] = 0;
    model.vertices.front().bone_weights[0] = 1.0f;
    model.indices = {0, 0, 0};
    model.materials.resize(1);
    model.materials.front().index_count = 3;
    model.bones.resize(1);
    model.bones.front().position = {0.0f, 2.0f, 0.0f};
    libmmd::pmx::RigidBody body;
    body.bone_index = 0;
    body.position = model.bones.front().position;
    body.size = {0.25f, 0.0f, 0.0f};
    body.mass = 1.0f;
    body.mode = 1;
    model.rigid_bodies.push_back(body);
    return model;
}

void joint_models_through_api(libmmd_runtime* runtime, const libmmd_model_physics_config& config) {
    for (std::uint8_t type = 1; type <= 5; ++type) {
        auto source = falling_model();
        libmmd::pmx::Joint joint;
        joint.type = type;
        joint.second_rigid_body_index = 0;
        joint.position = source.bones.front().position;
        source.joints.push_back(joint);
        const auto pack = libmmd::pack::build(source, {});
        libmmd_model* model = nullptr;
        assert(libmmd_model_load_pack(runtime, pack.data(), pack.size(), &model) == LIBMMD_STATUS_OK);
        libmmd_scene* scene = nullptr;
        assert(libmmd_scene_create_with_physics(runtime, nullptr, &config, &scene) == LIBMMD_STATUS_OK);
        libmmd_model_instance* instance = nullptr;
        const auto status = libmmd_model_instance_create(scene, model, &instance);
        libmmd_scene_update_info step{};
        step.abi_version = LIBMMD_ABI_VERSION;
        step.struct_size = sizeof(step);
        if (type <= 2) {
            assert(status == LIBMMD_STATUS_OK);
            libmmd_matrix_view matrices{};
            matrices.abi_version = LIBMMD_ABI_VERSION;
            matrices.struct_size = sizeof(matrices);
            assert(libmmd_model_instance_get_matrices(instance, &matrices) == LIBMMD_STATUS_OK);
            const auto* borrowed = matrices.data;
            for (int frame = 0; frame < 60; ++frame) {
                assert(libmmd_scene_update(scene, 1.0f / 60.0f, &step) == LIBMMD_STATUS_OK);
                assert(step.dropped_time == 0);
            }
            assert(step.instance_count == 1);
            assert(std::abs(borrowed[13]) < 0.001f);
            assert(libmmd_model_instance_reset_physics(instance) == LIBMMD_STATUS_OK);
            assert(libmmd_model_instance_get_matrices(instance, &matrices) == LIBMMD_STATUS_OK);
            assert(matrices.data == borrowed);
            assert(std::abs(borrowed[13]) < 0.0001f);
        } else {
            assert(status == LIBMMD_STATUS_INVALID_ARGUMENT);
            assert(instance == nullptr);
            assert(std::strstr(libmmd_last_error(runtime), "joint") != nullptr);
        }
        libmmd_model_destroy(model);
        assert(libmmd_scene_update(scene, 0.0f, &step) == LIBMMD_STATUS_OK);
        assert(step.instance_count == 0);
        libmmd_scene_destroy(scene);
    }
}

}

int main() {
    static_assert(sizeof(libmmd_model_physics_config) == 36);
    libmmd_runtime* runtime = nullptr;
    assert(libmmd_runtime_create(nullptr, &runtime) == LIBMMD_STATUS_OK);
    libmmd_model_physics_config physics_config{
        .abi_version = LIBMMD_ABI_VERSION,
        .struct_size = sizeof(libmmd_model_physics_config),
        .gravity = {0.0f, -9.81f, 0.0f},
        .meters_per_unit = 1.0f,
        .fixed_step_seconds = 1.0f / 120.0f,
        .maximum_substeps = 8,
        .solver_iterations = 10,
    };
    libmmd_scene* scene = nullptr;
    auto invalid_config = physics_config;
    invalid_config.fixed_step_seconds = 0.0f;
    assert(libmmd_scene_create_with_physics(runtime, nullptr, &invalid_config, &scene) ==
        LIBMMD_STATUS_INVALID_ARGUMENT);
    assert(scene == nullptr);
    invalid_config = physics_config;
    invalid_config.gravity[0] = std::numeric_limits<float>::infinity();
    assert(libmmd_scene_create_with_physics(runtime, nullptr, &invalid_config, &scene) ==
        LIBMMD_STATUS_INVALID_ARGUMENT);
    assert(scene == nullptr);
    invalid_config = physics_config;
    invalid_config.struct_size--;
    assert(libmmd_scene_create_with_physics(runtime, nullptr, &invalid_config, &scene) ==
        LIBMMD_STATUS_INVALID_ARGUMENT);
    invalid_config = physics_config;
    invalid_config.abi_version++;
    assert(libmmd_scene_create_with_physics(runtime, nullptr, &invalid_config, &scene) ==
        LIBMMD_STATUS_UNSUPPORTED_ABI);
    assert(scene == nullptr);
    assert(libmmd_scene_create_with_physics(runtime, nullptr, &physics_config, &scene) == LIBMMD_STATUS_OK);

    auto source = falling_model();
    const auto pack = libmmd::pack::build(source, {});
    libmmd_model* model = nullptr;
    assert(libmmd_model_load_pack(runtime, pack.data(), pack.size(), &model) == LIBMMD_STATUS_OK);
    libmmd_model_instance* first = nullptr;
    libmmd_model_instance* second = nullptr;
    assert(libmmd_model_instance_create(scene, model, &first) == LIBMMD_STATUS_OK);
    assert(libmmd_model_instance_create(scene, model, &second) == LIBMMD_STATUS_OK);
    assert(libmmd_model_instance_set_visible(first, 0) == LIBMMD_STATUS_OK);
    libmmd_matrix_view matrices{};
    matrices.abi_version = LIBMMD_ABI_VERSION;
    matrices.struct_size = sizeof(libmmd_matrix_view);
    assert(libmmd_model_instance_get_matrices(first, &matrices) == LIBMMD_STATUS_OK);
    const auto* borrowed_matrices = matrices.data;
    libmmd_scene_update_info step{};
    step.abi_version = LIBMMD_ABI_VERSION;
    step.struct_size = sizeof(libmmd_scene_update_info);
    for (int frame = 0; frame < 60; ++frame) {
        assert(libmmd_scene_update(scene, 1.0f / 60.0f, &step) == LIBMMD_STATUS_OK);
        assert(step.dropped_time == 0);
    }
    assert(step.instance_count == 2);
    assert(borrowed_matrices[13] < -4.0f);
    assert(libmmd_model_instance_get_matrices(first, &matrices) == LIBMMD_STATUS_OK);
    assert(matrices.data == borrowed_matrices);
    libmmd_render_packet packet{};
    packet.abi_version = LIBMMD_ABI_VERSION;
    packet.struct_size = sizeof(libmmd_render_packet);
    assert(libmmd_model_instance_get_render_packet(first, &packet) == LIBMMD_STATUS_OK);
    assert(packet.matrix_data == matrices.data);
    assert(packet.visible == 0);
    assert(libmmd_model_instance_reset_physics(first) == LIBMMD_STATUS_OK);
    assert(std::abs(borrowed_matrices[13]) < 0.0001f);
    assert(libmmd_model_instance_get_matrices(second, &matrices) == LIBMMD_STATUS_OK);
    assert(matrices.data[13] < -4.0f);
    assert(libmmd_scene_update(scene, 0.0f, &step) == LIBMMD_STATUS_OK);
    assert(std::abs(borrowed_matrices[13]) < 0.0001f);

    source.soft_bodies.resize(1);
    source.soft_bodies.front().material_index = 0;
    const auto soft_pack = libmmd::pack::build(source, {});
    libmmd_model* soft_model = nullptr;
    assert(libmmd_model_load_pack(runtime, soft_pack.data(), soft_pack.size(), &soft_model) == LIBMMD_STATUS_OK);
    libmmd_model_instance* unsupported = nullptr;
    assert(libmmd_model_instance_create(scene, soft_model, &unsupported) == LIBMMD_STATUS_INVALID_ARGUMENT);
    assert(unsupported == nullptr);
    assert(std::strstr(libmmd_last_error(runtime), "soft") != nullptr);
    assert(libmmd_scene_update(scene, 0.0f, &step) == LIBMMD_STATUS_OK);
    assert(step.instance_count == 2);
    libmmd_model_destroy(soft_model);

    libmmd_scene* animation_scene = nullptr;
    assert(libmmd_scene_create(runtime, nullptr, &animation_scene) == LIBMMD_STATUS_OK);
    libmmd_model_instance* animation_instance = nullptr;
    assert(libmmd_model_instance_create(animation_scene, model, &animation_instance) == LIBMMD_STATUS_OK);
    assert(libmmd_scene_update(animation_scene, 0.25f, &step) == LIBMMD_STATUS_OK);
    assert(libmmd_model_instance_get_matrices(animation_instance, &matrices) == LIBMMD_STATUS_OK);
    assert(std::abs(matrices.data[13]) < 0.0001f);
    assert(libmmd_model_instance_reset_physics(animation_instance) == LIBMMD_STATUS_INVALID_ARGUMENT);
    libmmd_scene_destroy(animation_scene);

    assert(libmmd_scene_update(scene, 0.25f, &step) == LIBMMD_STATUS_OK);
    assert(step.dropped_time == 1);
    libmmd_model_destroy(model);
    assert(libmmd_scene_update(scene, 0.0f, &step) == LIBMMD_STATUS_OK);
    assert(step.instance_count == 0);
    joint_models_through_api(runtime, physics_config);
    libmmd_runtime_destroy(runtime);
    return 0;
}
