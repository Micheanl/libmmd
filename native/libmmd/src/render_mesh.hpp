#ifndef LIBMMD_RENDER_MESH_HPP_
#define LIBMMD_RENDER_MESH_HPP_

#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <variant>
#include <vector>

namespace libmmd {

struct RenderMesh {
    std::vector<std::byte> vertices;
    std::vector<std::byte> skinning;
    std::vector<std::byte> indices;
    std::uint32_t vertex_count = 0;
    std::uint32_t index_count = 0;
    std::uint32_t index_stride = 0;
};

using RenderMeshResult = std::variant<RenderMesh, std::string>;

[[nodiscard]] RenderMeshResult build_render_mesh(
    std::span<const std::byte> vertices,
    std::uint32_t vertex_count,
    std::uint32_t bone_count,
    std::span<const std::byte> indices,
    std::uint32_t index_count);

}

#endif
