#include "libmmd/libmmd.h"

#include "native/libmmd/src/mmdpack.hpp"
#include "native/libmmd/src/pmx_reader.hpp"

#include <array>
#include <cassert>
#include <cstring>
#include <string_view>
#include <vector>

namespace {

template <typename T>
void append(std::vector<std::byte>& bytes, const T value) {
    const auto offset = bytes.size();
    bytes.resize(offset + sizeof(T));
    std::memcpy(bytes.data() + offset, &value, sizeof(T));
}

void fixed(std::vector<std::byte>& bytes, const char* value, const std::size_t size) {
    const auto offset = bytes.size();
    bytes.resize(offset + size);
    std::memcpy(bytes.data() + offset, value, std::strlen(value));
}

std::vector<std::byte> motion_bytes() {
    std::vector<std::byte> bytes;
    fixed(bytes, "Vocaloid Motion Data 0002", 30);
    fixed(bytes, "model", 20);
    append(bytes, std::uint32_t{1});
    fixed(bytes, "root", 15);
    append(bytes, std::uint32_t{0});
    append(bytes, 2.0f);
    append(bytes, 0.0f);
    append(bytes, 0.0f);
    append(bytes, 0.0f);
    append(bytes, 0.0f);
    append(bytes, 0.0f);
    append(bytes, 1.0f);
    bytes.resize(bytes.size() + 64);
    for (int section = 0; section < 5; ++section) append(bytes, std::uint32_t{0});
    return bytes;
}

}

int main() {
    assert(libmmd_abi_version() == LIBMMD_ABI_VERSION);
    assert(std::strcmp(libmmd_version(), "0.8.0") == 0);

    libmmd_runtime* runtime = nullptr;
    const libmmd_runtime_config config{
        .abi_version = LIBMMD_ABI_VERSION,
        .struct_size = sizeof(libmmd_runtime_config),
        .worker_threads = 3,
        .flags = 0,
    };
    assert(libmmd_runtime_create(&config, &runtime) == LIBMMD_STATUS_OK);
    assert(runtime != nullptr);
    assert(libmmd_runtime_worker_threads(runtime) == 3);

    libmmd::pmx::Model source_model{};
    source_model.vertices.resize(2);
    source_model.vertices.front().position = {1.0f, 2.0f, 3.0f};
    source_model.indices = {0, 1, 0};
    source_model.textures = {"body.png"};
    source_model.materials.resize(1);
    source_model.materials.front().index_count = 3;
    source_model.bones.resize(1);
    source_model.bones.front().name = "root";
    source_model.bones.front().english_name = "Root";
    source_model.bones.front().position = {1.0f, 2.0f, 3.0f};
    source_model.bones.front().flags = 0x0021;
    source_model.bones.front().ik_target_index = 0;
    source_model.bones.front().ik_iteration_count = 2;
    source_model.bones.front().ik_angle_limit = 0.5f;
    source_model.bones.front().ik_links.push_back({
        .bone_index = 0,
        .limited = true,
        .lower_limit = {-0.1f, -0.2f, -0.3f},
        .upper_limit = {0.1f, 0.2f, 0.3f},
    });
    source_model.morphs.resize(1);
    source_model.rigid_bodies.resize(1);
    source_model.joints.resize(1);
    const auto pack = libmmd::pack::build(source_model, {});
    libmmd_model* model = nullptr;
    assert(libmmd_model_load_pack(runtime, pack.data(), pack.size(), &model) == LIBMMD_STATUS_OK);
    assert(model != nullptr);
    libmmd_model_info info{};
    info.abi_version = LIBMMD_ABI_VERSION;
    info.struct_size = sizeof(libmmd_model_info);
    assert(libmmd_model_get_info(model, &info) == LIBMMD_STATUS_OK);
    assert(info.vertex_count == 2);
    assert(info.index_count == 3);
    assert(info.texture_count == 1);
    assert(info.material_count == 1);
    assert(info.bone_count == 1);
    assert(info.morph_count == 1);
    assert(info.rigid_body_count == 1);
    assert(info.joint_count == 1);
    libmmd_pack_view pack_view{};
    pack_view.abi_version = LIBMMD_ABI_VERSION;
    pack_view.struct_size = sizeof(libmmd_pack_view);
    assert(libmmd_model_get_pack_view(model, &pack_view) == LIBMMD_STATUS_OK);
    assert(pack_view.data != nullptr);
    assert(pack_view.size == pack.size());
    assert(std::memcmp(pack_view.data, pack.data(), pack.size()) == 0);
    libmmd_mesh_view mesh{};
    mesh.abi_version = LIBMMD_ABI_VERSION;
    mesh.struct_size = sizeof(libmmd_mesh_view);
    assert(libmmd_model_get_mesh_view(model, &mesh) == LIBMMD_STATUS_OK);
    assert(mesh.vertex_data != nullptr);
    assert(mesh.vertex_count == 2);
    assert(mesh.vertex_stride == 68);
    assert(mesh.vertex_size == 136);
    assert(mesh.index_data != nullptr);
    assert(mesh.index_count == 3);
    assert(mesh.index_stride == sizeof(std::uint32_t));
    assert(mesh.index_size == 3 * sizeof(std::uint32_t));
    float first_position = 0.0f;
    std::memcpy(&first_position, mesh.vertex_data, sizeof(first_position));
    assert(first_position == 1.0f);
    std::uint32_t first_index = 1;
    std::memcpy(&first_index, mesh.index_data, sizeof(first_index));
    assert(first_index == 0);
    libmmd_render_mesh_view render_mesh{};
    render_mesh.abi_version = LIBMMD_ABI_VERSION;
    render_mesh.struct_size = sizeof(libmmd_render_mesh_view);
    assert(libmmd_model_get_render_mesh_view(model, &render_mesh) == LIBMMD_STATUS_OK);
    assert(render_mesh.vertex_count == 2);
    assert(render_mesh.vertex_stride == 28);
    assert(render_mesh.vertex_size == 56);
    assert(render_mesh.skinning_stride == 32);
    assert(render_mesh.skinning_size == 64);
    assert(render_mesh.index_count == 3);
    assert(render_mesh.index_stride == sizeof(std::uint16_t));
    assert(render_mesh.index_size == 3 * sizeof(std::uint16_t));
    std::array<float, 3> converted_position{};
    std::memcpy(converted_position.data(), render_mesh.vertex_data, sizeof(converted_position));
    assert(converted_position[0] == 1.0f);
    assert(converted_position[1] == 2.0f);
    assert(converted_position[2] == -3.0f);
    libmmd_bone_info bone{};
    bone.abi_version = LIBMMD_ABI_VERSION;
    bone.struct_size = sizeof(libmmd_bone_info);
    assert(libmmd_model_get_bone(model, 0, &bone) == LIBMMD_STATUS_OK);
    assert(std::string_view(bone.name, bone.name_size) == "root");
    assert(std::string_view(bone.english_name, bone.english_name_size) == "Root");
    assert(bone.parent_index == -1);
    assert(bone.position[2] == 3.0f);
    assert(bone.ik_target_index == 0);
    assert(bone.ik_link_count == 1);
    libmmd_ik_link_info link{};
    link.abi_version = LIBMMD_ABI_VERSION;
    link.struct_size = sizeof(libmmd_ik_link_info);
    assert(libmmd_model_get_ik_link(model, 0, 0, &link) == LIBMMD_STATUS_OK);
    assert(link.bone_index == 0);
    assert(link.limited == 1);
    assert(link.lower_limit[1] == -0.2f);
    libmmd_pose* pose = nullptr;
    assert(libmmd_pose_create(model, &pose) == LIBMMD_STATUS_OK);
    assert(pose != nullptr);
    libmmd_matrix_view matrices{};
    matrices.abi_version = LIBMMD_ABI_VERSION;
    matrices.struct_size = sizeof(libmmd_matrix_view);
    assert(libmmd_pose_get_matrices(pose, &matrices) == LIBMMD_STATUS_OK);
    assert(matrices.bone_count == 1);
    assert(matrices.matrix_stride == 16);
    assert(matrices.float_count == 16);
    assert(matrices.data[0] == 1.0f);
    assert(matrices.data[5] == 1.0f);
    assert(matrices.data[10] == 1.0f);
    assert(matrices.data[15] == 1.0f);
    assert(libmmd_pose_set_local_transform(pose, 0, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f) ==
        LIBMMD_STATUS_OK);
    assert(libmmd_pose_evaluate(pose) == LIBMMD_STATUS_OK);
    assert(libmmd_pose_get_matrices(pose, &matrices) == LIBMMD_STATUS_OK);
    assert(matrices.data[12] == 1.0f);
    const auto vmd = motion_bytes();
    libmmd_motion* motion = nullptr;
    assert(libmmd_motion_create_vmd(model, vmd.data(), vmd.size(), &motion) == LIBMMD_STATUS_OK);
    assert(motion != nullptr);
    libmmd_motion_info motion_info{};
    motion_info.abi_version = LIBMMD_ABI_VERSION;
    motion_info.struct_size = sizeof(libmmd_motion_info);
    assert(libmmd_motion_get_info(motion, &motion_info) == LIBMMD_STATUS_OK);
    assert(motion_info.duration_frames == 0);
    assert(motion_info.bound_bone_count == 1);
    assert(libmmd_motion_apply(motion, 0.0f, 0, pose) == LIBMMD_STATUS_OK);
    assert(libmmd_pose_get_matrices(pose, &matrices) == LIBMMD_STATUS_OK);
    assert(matrices.data[12] == 2.0f);
    libmmd_scene* scene = nullptr;
    const libmmd_scene_config scene_config{
        .abi_version = LIBMMD_ABI_VERSION,
        .struct_size = sizeof(libmmd_scene_config),
        .maximum_delta_seconds = 0.1f,
        .flags = 0,
    };
    assert(libmmd_scene_create(runtime, &scene_config, &scene) == LIBMMD_STATUS_OK);
    assert(scene != nullptr);
    libmmd_model_instance* instance = nullptr;
    assert(libmmd_model_instance_create(scene, model, &instance) == LIBMMD_STATUS_OK);
    assert(instance != nullptr);
    const libmmd_instance_transform transform{
        .position = {1.0f, 2.0f, 3.0f},
        .rotation = {0.0f, 0.0f, 0.0f, 2.0f},
        .scale = {0.1f, 0.1f, 0.1f},
    };
    assert(libmmd_model_instance_set_transform(instance, &transform) == LIBMMD_STATUS_OK);
    assert(libmmd_model_instance_play(instance, motion, 1, 0.2f) == LIBMMD_STATUS_OK);
    libmmd_scene_update_info update{};
    update.abi_version = LIBMMD_ABI_VERSION;
    update.struct_size = sizeof(libmmd_scene_update_info);
    assert(libmmd_scene_update(scene, 0.15f, &update) == LIBMMD_STATUS_OK);
    assert(update.frame_index == 1);
    assert(update.instance_count == 1);
    assert(update.animated_instance_count == 1);
    assert(update.dropped_time == 1);
    assert(update.delta_seconds == 0.1f);
    libmmd_instance_state state{};
    state.abi_version = LIBMMD_ABI_VERSION;
    state.struct_size = sizeof(libmmd_instance_state);
    assert(libmmd_model_instance_get_state(instance, &state) == LIBMMD_STATUS_OK);
    assert(state.transform.position[1] == 2.0f);
    assert(state.transform.rotation[3] == 1.0f);
    assert(state.visible == 1);
    assert(state.playing == 1);
    assert(state.looping == 1);
    assert(state.transition_weight > 0.0f && state.transition_weight < 1.0f);
    assert(libmmd_model_instance_get_matrices(instance, &matrices) == LIBMMD_STATUS_OK);
    assert(matrices.bone_count == 1);
    assert(libmmd_model_instance_stop(instance, 0.1f) == LIBMMD_STATUS_OK);
    libmmd_scene_destroy(scene);
    libmmd_motion_destroy(motion);
    libmmd_pose_destroy(pose);
    libmmd_model_destroy(model);

    auto damaged = pack;
    damaged.back() ^= std::byte{1};
    model = nullptr;
    assert(libmmd_model_load_pack(runtime, damaged.data(), damaged.size(), &model) == LIBMMD_STATUS_INVALID_DATA);
    assert(model == nullptr);
    assert(std::strlen(libmmd_last_error(runtime)) > 0);
    libmmd_runtime_destroy(runtime);

    auto invalid = config;
    invalid.abi_version++;
    runtime = nullptr;
    assert(libmmd_runtime_create(&invalid, &runtime) == LIBMMD_STATUS_UNSUPPORTED_ABI);
    assert(runtime == nullptr);
    assert(std::strlen(libmmd_last_error(nullptr)) > 0);
    assert(libmmd_runtime_create(nullptr, nullptr) == LIBMMD_STATUS_INVALID_ARGUMENT);
    return 0;
}
