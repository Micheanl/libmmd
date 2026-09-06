#ifndef LIBMMD_MMDPACK_HPP_
#define LIBMMD_MMDPACK_HPP_

#include "native/libmmd/src/pmx_reader.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <variant>
#include <vector>

namespace libmmd::pack {

inline constexpr std::uint32_t format_version = 2;

struct Info {
    std::uint32_t version;
    std::uint64_t source_hash;
    std::uint64_t payload_hash;
    std::uint64_t total_size;
    std::uint32_t vertex_count;
    std::uint32_t index_count;
    std::uint32_t texture_count;
    std::uint32_t material_count;
    std::uint32_t bone_count;
    std::uint32_t morph_count;
    std::uint32_t rigid_body_count;
    std::uint32_t joint_count;
};

struct Error {
    std::size_t offset;
    std::string message;
};

struct BufferView {
    std::size_t offset;
    std::size_t size;
    std::uint32_t count;
    std::uint32_t stride;
};

struct Layout {
    Info info;
    BufferView vertices;
    BufferView indices;
};

struct RenderMaterial {
    pmx::Material value;
    std::uint32_t first_index;
};

struct RenderAssets {
    std::vector<std::string> textures;
    std::vector<RenderMaterial> materials;
};

using InfoResult = std::variant<Info, Error>;
using LayoutResult = std::variant<Layout, Error>;
using RenderAssetsResult = std::variant<RenderAssets, Error>;

[[nodiscard]] std::vector<std::byte> build(
    const pmx::Model& model,
    std::span<const std::byte> source);
[[nodiscard]] InfoResult inspect(std::span<const std::byte> bytes);
[[nodiscard]] LayoutResult inspect_layout(std::span<const std::byte> bytes);
[[nodiscard]] RenderAssetsResult read_render_assets(
    std::span<const std::byte> bytes,
    const Layout& layout);

}

#endif
