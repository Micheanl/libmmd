#ifndef LIBMMD_LIBMMD_H_
#define LIBMMD_LIBMMD_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(LIBMMD_BUILD_SHARED)
#define LIBMMD_API __declspec(dllexport)
#else
#define LIBMMD_API __declspec(dllimport)
#endif
#else
#define LIBMMD_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define LIBMMD_ABI_VERSION 3u

typedef struct libmmd_runtime libmmd_runtime;
typedef struct libmmd_model libmmd_model;
typedef struct libmmd_pose libmmd_pose;
typedef struct libmmd_motion libmmd_motion;
typedef struct libmmd_scene libmmd_scene;
typedef struct libmmd_model_instance libmmd_model_instance;
typedef struct libmmd_physics_world libmmd_physics_world;
typedef struct libmmd_physics_body libmmd_physics_body;
typedef struct libmmd_physics_joint libmmd_physics_joint;

typedef enum libmmd_status {
    LIBMMD_STATUS_OK = 0,
    LIBMMD_STATUS_INVALID_ARGUMENT = 1,
    LIBMMD_STATUS_UNSUPPORTED_ABI = 2,
    LIBMMD_STATUS_OUT_OF_MEMORY = 3,
    LIBMMD_STATUS_INTERNAL_ERROR = 4,
    LIBMMD_STATUS_INVALID_DATA = 5,
    LIBMMD_STATUS_UNSUPPORTED_FEATURE = 6,
} libmmd_status;

typedef struct libmmd_runtime_config {
    uint32_t abi_version;
    uint32_t struct_size;
    uint32_t worker_threads;
    uint32_t flags;
} libmmd_runtime_config;

typedef struct libmmd_model_info {
    uint32_t abi_version;
    uint32_t struct_size;
    uint32_t vertex_count;
    uint32_t index_count;
    uint32_t texture_count;
    uint32_t material_count;
    uint32_t bone_count;
    uint32_t morph_count;
    uint32_t rigid_body_count;
    uint32_t joint_count;
    uint64_t source_hash;
} libmmd_model_info;

typedef struct libmmd_mesh_view {
    uint32_t abi_version;
    uint32_t struct_size;
    const void* vertex_data;
    size_t vertex_size;
    uint32_t vertex_count;
    uint32_t vertex_stride;
    const void* index_data;
    size_t index_size;
    uint32_t index_count;
    uint32_t index_stride;
} libmmd_mesh_view;

typedef struct libmmd_pack_view {
    uint32_t abi_version;
    uint32_t struct_size;
    const void* data;
    size_t size;
} libmmd_pack_view;

typedef struct libmmd_render_mesh_view {
    uint32_t abi_version;
    uint32_t struct_size;
    const void* vertex_data;
    size_t vertex_size;
    uint32_t vertex_count;
    uint32_t vertex_stride;
    const void* skinning_data;
    size_t skinning_size;
    uint32_t skinning_stride;
    uint32_t reserved;
    const void* index_data;
    size_t index_size;
    uint32_t index_count;
    uint32_t index_stride;
} libmmd_render_mesh_view;

typedef struct libmmd_texture_info {
    uint32_t abi_version;
    uint32_t struct_size;
    const char* path;
    size_t path_size;
} libmmd_texture_info;

typedef struct libmmd_material_info {
    uint32_t abi_version;
    uint32_t struct_size;
    const char* name;
    size_t name_size;
    const char* english_name;
    size_t english_name_size;
    float diffuse[4];
    float specular[3];
    float specular_strength;
    float ambient[3];
    float edge_color[4];
    float edge_size;
    uint32_t flags;
    int32_t texture_index;
    int32_t sphere_texture_index;
    int32_t toon_texture_index;
    uint32_t sphere_mode;
    uint32_t first_index;
    uint32_t index_count;
} libmmd_material_info;

typedef struct libmmd_bone_info {
    uint32_t abi_version;
    uint32_t struct_size;
    const char* name;
    size_t name_size;
    const char* english_name;
    size_t english_name_size;
    int32_t parent_index;
    int32_t deform_layer;
    uint32_t flags;
    float position[3];
    int32_t connection_index;
    float connection_offset[3];
    int32_t inheritance_index;
    float inheritance_weight;
    float fixed_axis[3];
    float local_x_axis[3];
    float local_z_axis[3];
    int32_t external_parent_key;
    int32_t ik_target_index;
    int32_t ik_iteration_count;
    float ik_angle_limit;
    uint32_t ik_link_count;
} libmmd_bone_info;

typedef struct libmmd_ik_link_info {
    uint32_t abi_version;
    uint32_t struct_size;
    int32_t bone_index;
    uint32_t limited;
    float lower_limit[3];
    float upper_limit[3];
} libmmd_ik_link_info;

typedef struct libmmd_matrix_view {
    uint32_t abi_version;
    uint32_t struct_size;
    const float* data;
    size_t float_count;
    uint32_t bone_count;
    uint32_t matrix_stride;
} libmmd_matrix_view;

typedef struct libmmd_motion_info {
    uint32_t abi_version;
    uint32_t struct_size;
    uint32_t duration_frames;
    uint32_t bound_bone_count;
    uint32_t bound_ik_count;
    uint32_t reserved;
} libmmd_motion_info;

typedef struct libmmd_scene_config {
    uint32_t abi_version;
    uint32_t struct_size;
    float maximum_delta_seconds;
    uint32_t flags;
} libmmd_scene_config;

typedef struct libmmd_model_physics_config {
    uint32_t abi_version;
    uint32_t struct_size;
    float gravity[3];
    float meters_per_unit;
    float fixed_step_seconds;
    uint32_t maximum_substeps;
    uint32_t solver_iterations;
} libmmd_model_physics_config;

typedef struct libmmd_instance_transform {
    float position[3];
    float rotation[4];
    float scale[3];
} libmmd_instance_transform;

typedef struct libmmd_scene_update_info {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t frame_index;
    uint32_t instance_count;
    uint32_t animated_instance_count;
    uint32_t dropped_time;
    uint32_t reserved;
    float delta_seconds;
    float total_seconds;
} libmmd_scene_update_info;

typedef struct libmmd_instance_state {
    uint32_t abi_version;
    uint32_t struct_size;
    libmmd_instance_transform transform;
    uint32_t visible;
    uint32_t playing;
    uint32_t looping;
    uint32_t reserved;
    float playback_seconds;
    float transition_weight;
} libmmd_instance_state;

typedef struct libmmd_render_packet {
    uint32_t abi_version;
    uint32_t struct_size;
    uint32_t backend_mask;
    uint32_t visible;
    libmmd_instance_transform transform;
    const void* vertex_data;
    size_t vertex_size;
    uint32_t vertex_count;
    uint32_t vertex_stride;
    const void* skinning_data;
    size_t skinning_size;
    uint32_t skinning_stride;
    uint32_t reserved0;
    const void* index_data;
    size_t index_size;
    uint32_t index_count;
    uint32_t index_stride;
    const float* matrix_data;
    size_t matrix_float_count;
    uint32_t bone_count;
    uint32_t matrix_stride;
    uint32_t draw_count;
    uint32_t reserved1;
} libmmd_render_packet;

#define LIBMMD_RENDER_BACKEND_OPENGL 0x00000001u
#define LIBMMD_RENDER_BACKEND_VULKAN 0x00000002u

typedef enum libmmd_physics_processor {
    LIBMMD_PHYSICS_PROCESSOR_AUTO = 0,
    LIBMMD_PHYSICS_PROCESSOR_CPU = 1,
    LIBMMD_PHYSICS_PROCESSOR_CUDA = 2,
} libmmd_physics_processor;

typedef enum libmmd_physics_body_type {
    LIBMMD_PHYSICS_BODY_STATIC = 0,
    LIBMMD_PHYSICS_BODY_DYNAMIC = 1,
    LIBMMD_PHYSICS_BODY_KINEMATIC = 2,
} libmmd_physics_body_type;

typedef enum libmmd_physics_shape {
    LIBMMD_PHYSICS_SHAPE_SPHERE = 0,
    LIBMMD_PHYSICS_SHAPE_BOX = 1,
    LIBMMD_PHYSICS_SHAPE_CAPSULE = 2,
} libmmd_physics_shape;

typedef struct libmmd_transform {
    float position[3];
    float rotation[4];
} libmmd_transform;

typedef struct libmmd_physics_world_config {
    uint32_t abi_version;
    uint32_t struct_size;
    float gravity[3];
    float fixed_step_seconds;
    float maximum_frame_seconds;
    uint32_t maximum_substeps;
    uint32_t worker_threads;
    uint32_t preferred_processor;
    uint32_t allow_cpu_fallback;
    uint32_t flags;
} libmmd_physics_world_config;

typedef struct libmmd_physics_device_info {
    uint32_t abi_version;
    uint32_t struct_size;
    uint32_t requested_processor;
    uint32_t active_processor;
    uint32_t gpu_available;
    uint32_t body_count;
    uint32_t joint_count;
    uint32_t reserved;
} libmmd_physics_device_info;

typedef struct libmmd_physics_body_desc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint32_t body_type;
    uint32_t shape;
    libmmd_transform transform;
    float dimensions[3];
    float mass;
    float static_friction;
    float dynamic_friction;
    float restitution;
    float linear_damping;
    float angular_damping;
    uint32_t collision_group;
    uint32_t collision_mask;
    uint32_t solver_position_iterations;
    uint32_t solver_velocity_iterations;
    uint32_t flags;
} libmmd_physics_body_desc;

typedef struct libmmd_physics_body_state {
    uint32_t abi_version;
    uint32_t struct_size;
    libmmd_transform transform;
    float linear_velocity[3];
    float angular_velocity[3];
    uint32_t sleeping;
    uint32_t reserved;
} libmmd_physics_body_state;

typedef struct libmmd_physics_step_info {
    uint32_t abi_version;
    uint32_t struct_size;
    uint32_t substep_count;
    uint32_t dropped_time;
    float interpolation_alpha;
    float simulated_seconds;
} libmmd_physics_step_info;

typedef struct libmmd_physics_raycast_hit {
    uint32_t abi_version;
    uint32_t struct_size;
    libmmd_physics_body* body;
    float position[3];
    float normal[3];
    float distance;
    uint32_t reserved;
} libmmd_physics_raycast_hit;

typedef struct libmmd_physics_joint_desc {
    uint32_t abi_version;
    uint32_t struct_size;
    libmmd_physics_body* body_a;
    libmmd_physics_body* body_b;
    libmmd_transform local_frame_a;
    libmmd_transform local_frame_b;
    float linear_lower[3];
    float linear_upper[3];
    float angular_lower[3];
    float angular_upper[3];
    float stiffness;
    float damping;
    float break_force;
    float break_torque;
    uint32_t flags;
} libmmd_physics_joint_desc;

#define LIBMMD_PHYSICS_WORLD_ENABLE_CCD 0x00000001u
#define LIBMMD_PHYSICS_WORLD_ENHANCED_DETERMINISM 0x00000002u
#define LIBMMD_PHYSICS_BODY_ENABLE_CCD 0x00000001u
#define LIBMMD_PHYSICS_BODY_DISABLE_GRAVITY 0x00000002u
#define LIBMMD_PHYSICS_JOINT_COLLISION 0x00000001u

LIBMMD_API uint32_t libmmd_abi_version(void);
LIBMMD_API const char* libmmd_version(void);
LIBMMD_API libmmd_status libmmd_runtime_create(
    const libmmd_runtime_config* config,
    libmmd_runtime** output);
LIBMMD_API void libmmd_runtime_destroy(libmmd_runtime* runtime);
LIBMMD_API uint32_t libmmd_runtime_worker_threads(const libmmd_runtime* runtime);
LIBMMD_API const char* libmmd_last_error(const libmmd_runtime* runtime);
LIBMMD_API libmmd_status libmmd_model_load_pack(
    libmmd_runtime* runtime,
    const void* data,
    size_t size,
    libmmd_model** output);
LIBMMD_API libmmd_status libmmd_model_load_pmx(
    libmmd_runtime* runtime,
    const void* data,
    size_t size,
    libmmd_model** output);
LIBMMD_API void libmmd_model_destroy(libmmd_model* model);
LIBMMD_API libmmd_status libmmd_model_get_info(
    const libmmd_model* model,
    libmmd_model_info* output);
LIBMMD_API libmmd_status libmmd_model_get_pack_view(
    const libmmd_model* model,
    libmmd_pack_view* output);
LIBMMD_API libmmd_status libmmd_model_get_mesh_view(
    const libmmd_model* model,
    libmmd_mesh_view* output);
LIBMMD_API libmmd_status libmmd_model_get_render_mesh_view(
    const libmmd_model* model,
    libmmd_render_mesh_view* output);
LIBMMD_API libmmd_status libmmd_model_get_texture(
    const libmmd_model* model,
    uint32_t texture_index,
    libmmd_texture_info* output);
LIBMMD_API libmmd_status libmmd_model_get_material(
    const libmmd_model* model,
    uint32_t material_index,
    libmmd_material_info* output);
LIBMMD_API libmmd_status libmmd_model_get_bone(
    const libmmd_model* model,
    uint32_t bone_index,
    libmmd_bone_info* output);
LIBMMD_API libmmd_status libmmd_model_get_ik_link(
    const libmmd_model* model,
    uint32_t bone_index,
    uint32_t link_index,
    libmmd_ik_link_info* output);
LIBMMD_API libmmd_status libmmd_pose_create(
    const libmmd_model* model,
    libmmd_pose** output);
LIBMMD_API void libmmd_pose_destroy(libmmd_pose* pose);
LIBMMD_API void libmmd_pose_reset(libmmd_pose* pose);
LIBMMD_API libmmd_status libmmd_pose_set_local_transform(
    libmmd_pose* pose,
    uint32_t bone_index,
    float translation_x,
    float translation_y,
    float translation_z,
    float rotation_x,
    float rotation_y,
    float rotation_z,
    float rotation_w);
LIBMMD_API libmmd_status libmmd_pose_evaluate(libmmd_pose* pose);
LIBMMD_API libmmd_status libmmd_pose_get_matrices(
    const libmmd_pose* pose,
    libmmd_matrix_view* output);
LIBMMD_API libmmd_status libmmd_motion_create_vmd(
    const libmmd_model* model,
    const void* data,
    size_t size,
    libmmd_motion** output);
LIBMMD_API void libmmd_motion_destroy(libmmd_motion* motion);
LIBMMD_API libmmd_status libmmd_motion_get_info(
    const libmmd_motion* motion,
    libmmd_motion_info* output);
LIBMMD_API libmmd_status libmmd_motion_apply(
    const libmmd_motion* motion,
    float time_seconds,
    uint32_t looping,
    libmmd_pose* pose);
LIBMMD_API libmmd_status libmmd_scene_create(
    libmmd_runtime* runtime,
    const libmmd_scene_config* config,
    libmmd_scene** output);
LIBMMD_API void libmmd_scene_destroy(libmmd_scene* scene);
LIBMMD_API libmmd_status libmmd_scene_create_with_physics(
    libmmd_runtime* runtime,
    const libmmd_scene_config* config,
    const libmmd_model_physics_config* physics_config,
    libmmd_scene** output);
LIBMMD_API libmmd_status libmmd_scene_update(
    libmmd_scene* scene,
    float delta_seconds,
    libmmd_scene_update_info* output);
LIBMMD_API libmmd_status libmmd_model_instance_create(
    libmmd_scene* scene,
    libmmd_model* model,
    libmmd_model_instance** output);
LIBMMD_API void libmmd_model_instance_destroy(libmmd_model_instance* instance);
LIBMMD_API libmmd_status libmmd_model_instance_reset_physics(libmmd_model_instance* instance);
LIBMMD_API libmmd_status libmmd_model_instance_set_transform(
    libmmd_model_instance* instance,
    const libmmd_instance_transform* transform);
LIBMMD_API libmmd_status libmmd_model_instance_set_visible(
    libmmd_model_instance* instance,
    uint32_t visible);
LIBMMD_API libmmd_status libmmd_model_instance_play(
    libmmd_model_instance* instance,
    libmmd_motion* motion,
    uint32_t looping,
    float fade_seconds);
LIBMMD_API libmmd_status libmmd_model_instance_stop(
    libmmd_model_instance* instance,
    float fade_seconds);
LIBMMD_API libmmd_status libmmd_model_instance_get_state(
    const libmmd_model_instance* instance,
    libmmd_instance_state* output);
LIBMMD_API libmmd_status libmmd_model_instance_get_matrices(
    const libmmd_model_instance* instance,
    libmmd_matrix_view* output);
LIBMMD_API libmmd_status libmmd_model_instance_get_render_packet(
    const libmmd_model_instance* instance,
    libmmd_render_packet* output);
LIBMMD_API libmmd_status libmmd_physics_world_create(
    libmmd_runtime* runtime,
    const libmmd_physics_world_config* config,
    libmmd_physics_world** output);
LIBMMD_API void libmmd_physics_world_destroy(libmmd_physics_world* world);
LIBMMD_API libmmd_status libmmd_physics_world_get_device_info(
    const libmmd_physics_world* world,
    libmmd_physics_device_info* output);
LIBMMD_API libmmd_status libmmd_physics_world_step(
    libmmd_physics_world* world,
    float delta_seconds,
    libmmd_physics_step_info* output);
LIBMMD_API libmmd_status libmmd_physics_world_raycast(
    libmmd_physics_world* world,
    const float origin[3],
    const float direction[3],
    float maximum_distance,
    uint32_t collision_mask,
    libmmd_physics_raycast_hit* output);
LIBMMD_API libmmd_status libmmd_physics_body_create(
    libmmd_physics_world* world,
    const libmmd_physics_body_desc* desc,
    libmmd_physics_body** output);
LIBMMD_API void libmmd_physics_body_destroy(libmmd_physics_body* body);
LIBMMD_API libmmd_status libmmd_physics_body_get_state(
    const libmmd_physics_body* body,
    float interpolation_alpha,
    libmmd_physics_body_state* output);
LIBMMD_API libmmd_status libmmd_physics_body_set_transform(
    libmmd_physics_body* body,
    const libmmd_transform* transform,
    uint32_t reset_velocity);
LIBMMD_API libmmd_status libmmd_physics_body_set_kinematic_target(
    libmmd_physics_body* body,
    const libmmd_transform* transform);
LIBMMD_API libmmd_status libmmd_physics_body_set_velocity(
    libmmd_physics_body* body,
    const float linear[3],
    const float angular[3]);
LIBMMD_API libmmd_status libmmd_physics_body_add_impulse(
    libmmd_physics_body* body,
    const float impulse[3]);
LIBMMD_API libmmd_status libmmd_physics_joint_create_d6(
    libmmd_physics_world* world,
    const libmmd_physics_joint_desc* desc,
    libmmd_physics_joint** output);
LIBMMD_API void libmmd_physics_joint_destroy(libmmd_physics_joint* joint);

#ifdef __cplusplus
}
#endif

#endif
