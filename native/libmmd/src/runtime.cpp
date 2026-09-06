#include "libmmd/libmmd.h"

#include "native/libmmd/src/mmdpack.hpp"
#include "native/libmmd/src/motion.hpp"
#include "native/libmmd/src/physics.hpp"
#include "native/libmmd/src/pose.hpp"
#include "native/libmmd/src/render_mesh.hpp"
#include "native/libmmd/src/runtime.hpp"
#include "native/libmmd/src/skeleton.hpp"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <exception>
#include <new>
#include <optional>
#include <thread>
#include <unordered_set>
#include <variant>
#include <vector>

namespace {

thread_local std::string global_error;

std::uint32_t default_worker_threads() {
    return std::max(1u, std::thread::hardware_concurrency());
}

void set_global_error(std::string message) {
    global_error = std::move(message);
}

}

namespace libmmd {

Runtime::Runtime(const std::uint32_t worker_threads)
    : worker_threads_(worker_threads == 0 ? default_worker_threads() : worker_threads),
      physics_(std::make_unique<PhysicsEngine>()) {}

Runtime::~Runtime() = default;

std::uint32_t Runtime::worker_threads() const noexcept {
    return worker_threads_;
}

const std::string& Runtime::last_error() const noexcept {
    return last_error_;
}

PhysicsEngine& Runtime::physics() noexcept {
    return *physics_;
}

void Runtime::set_error(std::string message) {
    last_error_ = std::move(message);
}

}

struct libmmd_runtime {
    explicit libmmd_runtime(const std::uint32_t workers) : value(workers) {}

    libmmd::Runtime value;
    std::unordered_set<libmmd_physics_world*> physics_worlds;
};

struct libmmd_model {
    std::vector<std::byte> bytes;
    libmmd::pack::Layout layout;
    libmmd::RenderMesh render_mesh;
    std::vector<libmmd::pmx::Bone> bones;
    std::optional<libmmd::pmx::Model> source;
};

struct libmmd_pose {
    libmmd::Pose value;
    const libmmd_model* model;
};

struct libmmd_motion {
    libmmd::MotionClip value;
    const libmmd_model* model;
};

struct libmmd_physics_world {
    std::unique_ptr<libmmd::PhysicsWorld> value;
    libmmd_runtime* runtime;
    std::uint32_t requested_processor;
    std::unordered_set<libmmd_physics_body*> bodies;
    std::unordered_set<libmmd_physics_joint*> joints;
};

struct libmmd_physics_body {
    std::unique_ptr<libmmd::PhysicsBody> value;
    libmmd_physics_world* world;
};

struct libmmd_physics_joint {
    std::unique_ptr<libmmd::PhysicsJoint> value;
    libmmd_physics_world* world;
    libmmd_physics_body* body_a;
    libmmd_physics_body* body_b;
};

namespace {

template <typename Action>
libmmd_status physics_call(libmmd_runtime* runtime, Action&& action) {
    try {
        action();
        runtime->value.set_error({});
        return LIBMMD_STATUS_OK;
    } catch (const std::invalid_argument& error) {
        runtime->value.set_error(error.what());
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    } catch (const std::bad_alloc&) {
        runtime->value.set_error("physics allocation failed");
        return LIBMMD_STATUS_OUT_OF_MEMORY;
    } catch (const std::exception& error) {
        runtime->value.set_error(error.what());
        return LIBMMD_STATUS_INTERNAL_ERROR;
    } catch (...) {
        runtime->value.set_error("unknown physics failure");
        return LIBMMD_STATUS_INTERNAL_ERROR;
    }
}

libmmd_physics_world_config default_physics_config(const libmmd_runtime& runtime) {
    return {
        .abi_version = LIBMMD_ABI_VERSION,
        .struct_size = sizeof(libmmd_physics_world_config),
        .gravity = {0.0f, -9.81f, 0.0f},
        .fixed_step_seconds = 1.0f / 120.0f,
        .maximum_frame_seconds = 0.25f,
        .maximum_substeps = 8,
        .worker_threads = runtime.value.worker_threads(),
        .preferred_processor = LIBMMD_PHYSICS_PROCESSOR_AUTO,
        .allow_cpu_fallback = 1,
        .flags = LIBMMD_PHYSICS_WORLD_ENABLE_CCD | LIBMMD_PHYSICS_WORLD_ENHANCED_DETERMINISM,
    };
}

}

extern "C" {

std::uint32_t libmmd_abi_version() {
    return LIBMMD_ABI_VERSION;
}

const char* libmmd_version() {
    return "0.8.0";
}

libmmd_status libmmd_runtime_create(const libmmd_runtime_config* config, libmmd_runtime** output) {
    if (output == nullptr) {
        set_global_error("output is null");
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    *output = nullptr;
    if (config != nullptr) {
        if (config->struct_size < sizeof(libmmd_runtime_config)) {
            set_global_error("runtime config is truncated");
            return LIBMMD_STATUS_INVALID_ARGUMENT;
        }
        if (config->abi_version != LIBMMD_ABI_VERSION) {
            set_global_error("runtime ABI is unsupported");
            return LIBMMD_STATUS_UNSUPPORTED_ABI;
        }
    }
    try {
        const auto workers = config == nullptr ? 0u : config->worker_threads;
        *output = new libmmd_runtime(workers);
        global_error.clear();
        return LIBMMD_STATUS_OK;
    } catch (const std::bad_alloc&) {
        set_global_error("runtime allocation failed");
        return LIBMMD_STATUS_OUT_OF_MEMORY;
    } catch (const std::exception& error) {
        set_global_error(error.what());
        return LIBMMD_STATUS_INTERNAL_ERROR;
    } catch (...) {
        set_global_error("unknown runtime failure");
        return LIBMMD_STATUS_INTERNAL_ERROR;
    }
}

void libmmd_runtime_destroy(libmmd_runtime* runtime) {
    if (runtime != nullptr) {
        while (!runtime->physics_worlds.empty()) {
            libmmd_physics_world_destroy(*runtime->physics_worlds.begin());
        }
    }
    delete runtime;
}

std::uint32_t libmmd_runtime_worker_threads(const libmmd_runtime* runtime) {
    return runtime == nullptr ? 0u : runtime->value.worker_threads();
}

const char* libmmd_last_error(const libmmd_runtime* runtime) {
    return runtime == nullptr ? global_error.c_str() : runtime->value.last_error().c_str();
}

libmmd_status libmmd_model_load_pack(
    libmmd_runtime* runtime,
    const void* data,
    const std::size_t size,
    libmmd_model** output) {
    if (runtime == nullptr || data == nullptr || size == 0 || output == nullptr) {
        if (runtime != nullptr) {
            runtime->value.set_error("model pack arguments are invalid");
        } else {
            set_global_error("runtime is null");
        }
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    *output = nullptr;
    try {
        const auto input = std::span(static_cast<const std::byte*>(data), size);
        const auto result = libmmd::pack::inspect_layout(input);
        if (const auto* error = std::get_if<libmmd::pack::Error>(&result)) {
            runtime->value.set_error(
                "mmdpack error at byte " + std::to_string(error->offset) + ": " + error->message);
            return LIBMMD_STATUS_INVALID_DATA;
        }
        const auto layout = std::get<libmmd::pack::Layout>(result);
        const auto render_result = libmmd::build_render_mesh(
            input.subspan(layout.vertices.offset, layout.vertices.size),
            layout.vertices.count,
            layout.info.bone_count,
            input.subspan(layout.indices.offset, layout.indices.size),
            layout.indices.count);
        if (const auto* error = std::get_if<std::string>(&render_result)) {
            runtime->value.set_error(*error);
            return LIBMMD_STATUS_INVALID_DATA;
        }
        const auto skeleton_result = libmmd::read_skeleton(input, layout);
        if (const auto* error = std::get_if<libmmd::pack::Error>(&skeleton_result)) {
            runtime->value.set_error(
                "mmdpack skeleton error at byte " + std::to_string(error->offset) + ": " + error->message);
            return LIBMMD_STATUS_INVALID_DATA;
        }
        auto bytes = std::vector<std::byte>(input.begin(), input.end());
        *output = new libmmd_model{
            std::move(bytes),
            layout,
            std::get<libmmd::RenderMesh>(std::move(render_result)),
            std::get<std::vector<libmmd::pmx::Bone>>(std::move(skeleton_result)),
            std::nullopt,
        };
        runtime->value.set_error({});
        return LIBMMD_STATUS_OK;
    } catch (const std::bad_alloc&) {
        runtime->value.set_error("model pack allocation failed");
        return LIBMMD_STATUS_OUT_OF_MEMORY;
    } catch (const std::exception& error) {
        runtime->value.set_error(error.what());
        return LIBMMD_STATUS_INTERNAL_ERROR;
    } catch (...) {
        runtime->value.set_error("unknown model pack failure");
        return LIBMMD_STATUS_INTERNAL_ERROR;
    }
}

libmmd_status libmmd_model_load_pmx(
    libmmd_runtime* runtime,
    const void* data,
    const std::size_t size,
    libmmd_model** output) {
    if (runtime == nullptr || data == nullptr || size == 0 || output == nullptr) {
        if (runtime != nullptr) runtime->value.set_error("PMX model arguments are invalid");
        else set_global_error("runtime is null");
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    *output = nullptr;
    try {
        const auto input = std::span(static_cast<const std::byte*>(data), size);
        const auto result = libmmd::pmx::read_model(input);
        if (const auto* error = std::get_if<libmmd::pmx::ParseError>(&result)) {
            runtime->value.set_error("PMX error at byte " + std::to_string(error->offset) + ": " + error->message);
            return LIBMMD_STATUS_INVALID_DATA;
        }
        auto source = std::get<libmmd::pmx::Model>(std::move(result));
        const auto pack = libmmd::pack::build(source, input);
        const auto status = libmmd_model_load_pack(runtime, pack.data(), pack.size(), output);
        if (status == LIBMMD_STATUS_OK) (*output)->source = std::move(source);
        return status;
    } catch (const std::bad_alloc&) {
        runtime->value.set_error("PMX model allocation failed");
        return LIBMMD_STATUS_OUT_OF_MEMORY;
    } catch (const std::exception& error) {
        runtime->value.set_error(error.what());
        return LIBMMD_STATUS_INTERNAL_ERROR;
    } catch (...) {
        runtime->value.set_error("unknown PMX model failure");
        return LIBMMD_STATUS_INTERNAL_ERROR;
    }
}

void libmmd_model_destroy(libmmd_model* model) {
    delete model;
}

libmmd_status libmmd_model_get_info(const libmmd_model* model, libmmd_model_info* output) {
    if (model == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_model_info)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) {
        return LIBMMD_STATUS_UNSUPPORTED_ABI;
    }
    const auto size = output->struct_size;
    std::memset(output, 0, sizeof(libmmd_model_info));
    output->abi_version = LIBMMD_ABI_VERSION;
    output->struct_size = size;
    output->vertex_count = model->layout.info.vertex_count;
    output->index_count = model->layout.info.index_count;
    output->texture_count = model->layout.info.texture_count;
    output->material_count = model->layout.info.material_count;
    output->bone_count = model->layout.info.bone_count;
    output->morph_count = model->layout.info.morph_count;
    output->rigid_body_count = model->layout.info.rigid_body_count;
    output->joint_count = model->layout.info.joint_count;
    output->source_hash = model->layout.info.source_hash;
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_model_get_pack_view(const libmmd_model* model, libmmd_pack_view* output) {
    if (model == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_pack_view)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    const auto size = output->struct_size;
    std::memset(output, 0, sizeof(libmmd_pack_view));
    output->abi_version = LIBMMD_ABI_VERSION;
    output->struct_size = size;
    output->data = model->bytes.data();
    output->size = model->bytes.size();
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_model_get_mesh_view(const libmmd_model* model, libmmd_mesh_view* output) {
    if (model == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_mesh_view)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) {
        return LIBMMD_STATUS_UNSUPPORTED_ABI;
    }
    const auto size = output->struct_size;
    std::memset(output, 0, sizeof(libmmd_mesh_view));
    output->abi_version = LIBMMD_ABI_VERSION;
    output->struct_size = size;
    output->vertex_data = model->bytes.data() + model->layout.vertices.offset;
    output->vertex_size = model->layout.vertices.size;
    output->vertex_count = model->layout.vertices.count;
    output->vertex_stride = model->layout.vertices.stride;
    output->index_data = model->bytes.data() + model->layout.indices.offset;
    output->index_size = model->layout.indices.size;
    output->index_count = model->layout.indices.count;
    output->index_stride = model->layout.indices.stride;
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_model_get_render_mesh_view(
    const libmmd_model* model,
    libmmd_render_mesh_view* output) {
    if (model == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_render_mesh_view)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) {
        return LIBMMD_STATUS_UNSUPPORTED_ABI;
    }
    const auto size = output->struct_size;
    std::memset(output, 0, sizeof(libmmd_render_mesh_view));
    output->abi_version = LIBMMD_ABI_VERSION;
    output->struct_size = size;
    output->vertex_data = model->render_mesh.vertices.data();
    output->vertex_size = model->render_mesh.vertices.size();
    output->vertex_count = model->render_mesh.vertex_count;
    output->vertex_stride = 28;
    output->skinning_data = model->render_mesh.skinning.data();
    output->skinning_size = model->render_mesh.skinning.size();
    output->skinning_stride = 32;
    output->index_data = model->render_mesh.indices.data();
    output->index_size = model->render_mesh.indices.size();
    output->index_count = model->render_mesh.index_count;
    output->index_stride = model->render_mesh.index_stride;
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_model_get_bone(
    const libmmd_model* model,
    const std::uint32_t bone_index,
    libmmd_bone_info* output) {
    if (model == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_bone_info) ||
        bone_index >= model->bones.size()) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    const auto& bone = model->bones[bone_index];
    const auto size = output->struct_size;
    std::memset(output, 0, sizeof(libmmd_bone_info));
    output->abi_version = LIBMMD_ABI_VERSION;
    output->struct_size = size;
    output->name = bone.name.data();
    output->name_size = bone.name.size();
    output->english_name = bone.english_name.data();
    output->english_name_size = bone.english_name.size();
    output->parent_index = bone.parent_index;
    output->deform_layer = bone.deform_layer;
    output->flags = bone.flags;
    std::memcpy(output->position, bone.position.data(), sizeof(output->position));
    output->connection_index = bone.connection_index;
    std::memcpy(output->connection_offset, bone.connection_offset.data(), sizeof(output->connection_offset));
    output->inheritance_index = bone.inheritance_index;
    output->inheritance_weight = bone.inheritance_weight;
    std::memcpy(output->fixed_axis, bone.fixed_axis.data(), sizeof(output->fixed_axis));
    std::memcpy(output->local_x_axis, bone.local_x_axis.data(), sizeof(output->local_x_axis));
    std::memcpy(output->local_z_axis, bone.local_z_axis.data(), sizeof(output->local_z_axis));
    output->external_parent_key = bone.external_parent_key;
    output->ik_target_index = bone.ik_target_index;
    output->ik_iteration_count = bone.ik_iteration_count;
    output->ik_angle_limit = bone.ik_angle_limit;
    output->ik_link_count = bone.ik_links.size();
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_model_get_ik_link(
    const libmmd_model* model,
    const std::uint32_t bone_index,
    const std::uint32_t link_index,
    libmmd_ik_link_info* output) {
    if (model == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_ik_link_info) ||
        bone_index >= model->bones.size() || link_index >= model->bones[bone_index].ik_links.size()) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    const auto& link = model->bones[bone_index].ik_links[link_index];
    const auto size = output->struct_size;
    std::memset(output, 0, sizeof(libmmd_ik_link_info));
    output->abi_version = LIBMMD_ABI_VERSION;
    output->struct_size = size;
    output->bone_index = link.bone_index;
    output->limited = link.limited ? 1u : 0u;
    std::memcpy(output->lower_limit, link.lower_limit.data(), sizeof(output->lower_limit));
    std::memcpy(output->upper_limit, link.upper_limit.data(), sizeof(output->upper_limit));
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_pose_create(const libmmd_model* model, libmmd_pose** output) {
    if (model == nullptr || output == nullptr) return LIBMMD_STATUS_INVALID_ARGUMENT;
    *output = nullptr;
    try {
        *output = new libmmd_pose{libmmd::Pose(model->bones), model};
        global_error.clear();
        return LIBMMD_STATUS_OK;
    } catch (const std::bad_alloc&) {
        set_global_error("pose allocation failed");
        return LIBMMD_STATUS_OUT_OF_MEMORY;
    } catch (const std::exception& error) {
        set_global_error(error.what());
        return LIBMMD_STATUS_INVALID_DATA;
    } catch (...) {
        set_global_error("unknown pose creation failure");
        return LIBMMD_STATUS_INTERNAL_ERROR;
    }
}

void libmmd_pose_destroy(libmmd_pose* pose) {
    delete pose;
}

void libmmd_pose_reset(libmmd_pose* pose) {
    if (pose != nullptr) pose->value.reset();
}

libmmd_status libmmd_pose_set_local_transform(
    libmmd_pose* pose,
    const std::uint32_t bone_index,
    const float translation_x,
    const float translation_y,
    const float translation_z,
    const float rotation_x,
    const float rotation_y,
    const float rotation_z,
    const float rotation_w) {
    if (pose == nullptr) return LIBMMD_STATUS_INVALID_ARGUMENT;
    return pose->value.set_local_transform(
        bone_index,
        {translation_x, translation_y, translation_z},
        {rotation_x, rotation_y, rotation_z, rotation_w})
        ? LIBMMD_STATUS_OK
        : LIBMMD_STATUS_INVALID_ARGUMENT;
}

libmmd_status libmmd_pose_evaluate(libmmd_pose* pose) {
    if (pose == nullptr) return LIBMMD_STATUS_INVALID_ARGUMENT;
    pose->value.evaluate();
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_pose_get_matrices(const libmmd_pose* pose, libmmd_matrix_view* output) {
    if (pose == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_matrix_view)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    const auto matrices = pose->value.skinning_matrices();
    const auto size = output->struct_size;
    std::memset(output, 0, sizeof(libmmd_matrix_view));
    output->abi_version = LIBMMD_ABI_VERSION;
    output->struct_size = size;
    output->data = matrices.data();
    output->float_count = matrices.size();
    output->bone_count = pose->value.bone_count();
    output->matrix_stride = 16;
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_motion_create_vmd(
    const libmmd_model* model,
    const void* data,
    const std::size_t size,
    libmmd_motion** output) {
    if (model == nullptr || data == nullptr || size == 0 || output == nullptr) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    *output = nullptr;
    try {
        const auto result = libmmd::vmd::read_motion(std::span(static_cast<const std::byte*>(data), size));
        if (const auto* error = std::get_if<libmmd::vmd::ParseError>(&result)) {
            set_global_error("VMD error at byte " + std::to_string(error->offset) + ": " + error->message);
            return LIBMMD_STATUS_INVALID_DATA;
        }
        *output = new libmmd_motion{
            libmmd::MotionClip(model->bones, std::get<libmmd::vmd::Motion>(result)),
            model,
        };
        global_error.clear();
        return LIBMMD_STATUS_OK;
    } catch (const std::bad_alloc&) {
        set_global_error("motion allocation failed");
        return LIBMMD_STATUS_OUT_OF_MEMORY;
    } catch (const std::exception& error) {
        set_global_error(error.what());
        return LIBMMD_STATUS_INVALID_DATA;
    } catch (...) {
        set_global_error("unknown motion creation failure");
        return LIBMMD_STATUS_INTERNAL_ERROR;
    }
}

void libmmd_motion_destroy(libmmd_motion* motion) {
    delete motion;
}

libmmd_status libmmd_motion_get_info(const libmmd_motion* motion, libmmd_motion_info* output) {
    if (motion == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_motion_info)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    const auto size = output->struct_size;
    std::memset(output, 0, sizeof(libmmd_motion_info));
    output->abi_version = LIBMMD_ABI_VERSION;
    output->struct_size = size;
    output->duration_frames = motion->value.duration_frames();
    output->bound_bone_count = motion->value.bound_bone_count();
    output->bound_ik_count = motion->value.bound_ik_count();
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_motion_apply(
    const libmmd_motion* motion,
    const float time_seconds,
    const std::uint32_t looping,
    libmmd_pose* pose) {
    if (motion == nullptr || pose == nullptr || looping > 1 || motion->model != pose->model) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    return motion->value.apply(time_seconds, looping == 1, pose->value)
        ? LIBMMD_STATUS_OK
        : LIBMMD_STATUS_INVALID_ARGUMENT;
}

libmmd_status libmmd_physics_world_create(
    libmmd_runtime* runtime,
    const libmmd_physics_world_config* config,
    libmmd_physics_world** output) {
    if (runtime == nullptr || output == nullptr) return LIBMMD_STATUS_INVALID_ARGUMENT;
    *output = nullptr;
    auto resolved = default_physics_config(*runtime);
    if (config != nullptr) {
        if (config->struct_size < sizeof(libmmd_physics_world_config)) {
            runtime->value.set_error("physics world config is truncated");
            return LIBMMD_STATUS_INVALID_ARGUMENT;
        }
        if (config->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
        resolved = *config;
        if (resolved.allow_cpu_fallback > 1) return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (resolved.preferred_processor > LIBMMD_PHYSICS_PROCESSOR_CUDA) {
        runtime->value.set_error("physics processor is invalid");
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (resolved.preferred_processor == LIBMMD_PHYSICS_PROCESSOR_CUDA &&
        !runtime->value.physics().gpu_available() && !resolved.allow_cpu_fallback) {
        runtime->value.set_error("PhysX CUDA is unavailable on this runtime or device");
        return LIBMMD_STATUS_UNSUPPORTED_FEATURE;
    }
    return physics_call(runtime, [&] {
        auto world = std::make_unique<libmmd_physics_world>();
        world->runtime = runtime;
        world->requested_processor = resolved.preferred_processor;
        world->value = std::make_unique<libmmd::PhysicsWorld>(runtime->value.physics(), resolved);
        *output = world.release();
        runtime->physics_worlds.insert(*output);
    });
}

void libmmd_physics_world_destroy(libmmd_physics_world* world) {
    if (world == nullptr) return;
    while (!world->joints.empty()) libmmd_physics_joint_destroy(*world->joints.begin());
    while (!world->bodies.empty()) libmmd_physics_body_destroy(*world->bodies.begin());
    world->runtime->physics_worlds.erase(world);
    delete world;
}

libmmd_status libmmd_physics_world_get_device_info(
    const libmmd_physics_world* world,
    libmmd_physics_device_info* output) {
    if (world == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_physics_device_info)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    const auto size = output->struct_size;
    std::memset(output, 0, sizeof(libmmd_physics_device_info));
    output->abi_version = LIBMMD_ABI_VERSION;
    output->struct_size = size;
    output->requested_processor = world->requested_processor;
    output->active_processor = world->value->active_processor();
    output->gpu_available = world->value->gpu_available() ? 1u : 0u;
    output->body_count = world->value->body_count();
    output->joint_count = world->value->joint_count();
    return LIBMMD_STATUS_OK;
}

libmmd_status libmmd_physics_world_step(
    libmmd_physics_world* world,
    const float delta_seconds,
    libmmd_physics_step_info* output) {
    if (world == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_physics_step_info)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    const auto size = output->struct_size;
    return physics_call(world->runtime, [&] {
        const auto step = world->value->step(delta_seconds);
        std::memset(output, 0, sizeof(libmmd_physics_step_info));
        output->abi_version = LIBMMD_ABI_VERSION;
        output->struct_size = size;
        output->substep_count = step.substeps;
        output->dropped_time = step.dropped_time ? 1u : 0u;
        output->interpolation_alpha = step.interpolation_alpha;
        output->simulated_seconds = step.simulated_seconds;
    });
}

libmmd_status libmmd_physics_world_raycast(
    libmmd_physics_world* world,
    const float origin[3],
    const float direction[3],
    const float maximum_distance,
    const std::uint32_t collision_mask,
    libmmd_physics_raycast_hit* output) {
    if (world == nullptr || origin == nullptr || direction == nullptr || output == nullptr ||
        output->struct_size < sizeof(libmmd_physics_raycast_hit)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    const auto size = output->struct_size;
    return physics_call(world->runtime, [&] {
        const auto hit = world->value->raycast(
            {origin[0], origin[1], origin[2]},
            {direction[0], direction[1], direction[2]},
            maximum_distance,
            collision_mask);
        std::memset(output, 0, sizeof(libmmd_physics_raycast_hit));
        output->abi_version = LIBMMD_ABI_VERSION;
        output->struct_size = size;
        if (hit.body == nullptr) return;
        const auto iterator = std::find_if(world->bodies.begin(), world->bodies.end(), [&](const auto* body) {
            return body->value.get() == hit.body;
        });
        if (iterator == world->bodies.end()) return;
        output->body = *iterator;
        std::memcpy(output->position, hit.position.data(), sizeof(output->position));
        std::memcpy(output->normal, hit.normal.data(), sizeof(output->normal));
        output->distance = hit.distance;
    });
}

libmmd_status libmmd_physics_body_create(
    libmmd_physics_world* world,
    const libmmd_physics_body_desc* desc,
    libmmd_physics_body** output) {
    if (world == nullptr || desc == nullptr || output == nullptr ||
        desc->struct_size < sizeof(libmmd_physics_body_desc)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (desc->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    *output = nullptr;
    return physics_call(world->runtime, [&] {
        auto body = std::make_unique<libmmd_physics_body>();
        body->world = world;
        body->value = std::make_unique<libmmd::PhysicsBody>(*world->value, *desc);
        *output = body.release();
        world->bodies.insert(*output);
    });
}

void libmmd_physics_body_destroy(libmmd_physics_body* body) {
    if (body == nullptr) return;
    auto* world = body->world;
    std::vector<libmmd_physics_joint*> attached;
    for (auto* joint : world->joints) {
        if (joint->body_a == body || joint->body_b == body) attached.push_back(joint);
    }
    for (auto* joint : attached) libmmd_physics_joint_destroy(joint);
    world->bodies.erase(body);
    delete body;
}

libmmd_status libmmd_physics_body_get_state(
    const libmmd_physics_body* body,
    const float interpolation_alpha,
    libmmd_physics_body_state* output) {
    if (body == nullptr || output == nullptr || output->struct_size < sizeof(libmmd_physics_body_state)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (output->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    const auto size = output->struct_size;
    return physics_call(body->world->runtime, [&] {
        const auto state = body->value->state(interpolation_alpha);
        std::memset(output, 0, sizeof(libmmd_physics_body_state));
        output->abi_version = LIBMMD_ABI_VERSION;
        output->struct_size = size;
        output->transform = state.transform;
        std::memcpy(output->linear_velocity, state.linear_velocity.data(), sizeof(output->linear_velocity));
        std::memcpy(output->angular_velocity, state.angular_velocity.data(), sizeof(output->angular_velocity));
        output->sleeping = state.sleeping ? 1u : 0u;
    });
}

libmmd_status libmmd_physics_body_set_transform(
    libmmd_physics_body* body,
    const libmmd_transform* value,
    const std::uint32_t reset_velocity) {
    if (body == nullptr || value == nullptr || reset_velocity > 1) return LIBMMD_STATUS_INVALID_ARGUMENT;
    return physics_call(body->world->runtime, [&] {
        body->value->set_transform(*value, reset_velocity == 1);
    });
}

libmmd_status libmmd_physics_body_set_kinematic_target(
    libmmd_physics_body* body,
    const libmmd_transform* value) {
    if (body == nullptr || value == nullptr) return LIBMMD_STATUS_INVALID_ARGUMENT;
    return physics_call(body->world->runtime, [&] { body->value->set_kinematic_target(*value); });
}

libmmd_status libmmd_physics_body_set_velocity(
    libmmd_physics_body* body,
    const float linear[3],
    const float angular[3]) {
    if (body == nullptr || linear == nullptr || angular == nullptr) return LIBMMD_STATUS_INVALID_ARGUMENT;
    return physics_call(body->world->runtime, [&] {
        body->value->set_velocity(
            {linear[0], linear[1], linear[2]},
            {angular[0], angular[1], angular[2]});
    });
}

libmmd_status libmmd_physics_body_add_impulse(
    libmmd_physics_body* body,
    const float impulse[3]) {
    if (body == nullptr || impulse == nullptr) return LIBMMD_STATUS_INVALID_ARGUMENT;
    return physics_call(body->world->runtime, [&] {
        body->value->add_impulse({impulse[0], impulse[1], impulse[2]});
    });
}

libmmd_status libmmd_physics_joint_create_d6(
    libmmd_physics_world* world,
    const libmmd_physics_joint_desc* desc,
    libmmd_physics_joint** output) {
    if (world == nullptr || desc == nullptr || output == nullptr ||
        desc->struct_size < sizeof(libmmd_physics_joint_desc)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    if (desc->abi_version != LIBMMD_ABI_VERSION) return LIBMMD_STATUS_UNSUPPORTED_ABI;
    if ((desc->body_a != nullptr && desc->body_a->world != world) ||
        (desc->body_b != nullptr && desc->body_b->world != world)) {
        return LIBMMD_STATUS_INVALID_ARGUMENT;
    }
    *output = nullptr;
    return physics_call(world->runtime, [&] {
        auto joint = std::make_unique<libmmd_physics_joint>();
        joint->world = world;
        joint->body_a = desc->body_a;
        joint->body_b = desc->body_b;
        joint->value = std::make_unique<libmmd::PhysicsJoint>(
            *world->value,
            desc->body_a == nullptr ? nullptr : desc->body_a->value.get(),
            desc->body_b == nullptr ? nullptr : desc->body_b->value.get(),
            *desc);
        *output = joint.release();
        world->joints.insert(*output);
    });
}

void libmmd_physics_joint_destroy(libmmd_physics_joint* joint) {
    if (joint == nullptr) return;
    joint->world->joints.erase(joint);
    delete joint;
}

}
