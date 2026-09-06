#include "native/libmmd/src/render_mesh.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>

namespace libmmd {
namespace {

constexpr std::size_t source_vertex_stride = 68;
constexpr std::size_t vertex_stride = 28;
constexpr std::size_t skinning_stride = 32;

template <typename T>
T read(const std::byte* source) {
    T value{};
    std::memcpy(&value, source, sizeof(T));
    return value;
}

template <typename T>
void write(std::byte* destination, const T value) {
    std::memcpy(destination, &value, sizeof(T));
}

std::int8_t pack_normal(const float value) {
    const auto clamped = std::clamp(value, -1.0f, 1.0f);
    return static_cast<std::int8_t>(clamped * 127.0f);
}

}

RenderMeshResult build_render_mesh(
    const std::span<const std::byte> vertices,
    const std::uint32_t vertex_count,
    const std::uint32_t bone_count,
    const std::span<const std::byte> indices,
    const std::uint32_t index_count) {
    if (vertices.size() != static_cast<std::size_t>(vertex_count) * source_vertex_stride ||
        indices.size() != static_cast<std::size_t>(index_count) * sizeof(std::uint32_t)) {
        return std::string("packed mesh buffer size is invalid");
    }

    if (index_count % 3 != 0) return std::string("packed mesh index count is not triangular");
    RenderMesh output{};
    output.vertex_count = vertex_count;
    output.index_count = index_count;
    output.vertices.resize(static_cast<std::size_t>(vertex_count) * vertex_stride);
    output.skinning.resize(static_cast<std::size_t>(vertex_count) * skinning_stride);

    for (std::uint32_t vertex_index = 0; vertex_index < vertex_count; ++vertex_index) {
        const auto* source = vertices.data() + static_cast<std::size_t>(vertex_index) * source_vertex_stride;
        auto* vertex = output.vertices.data() + static_cast<std::size_t>(vertex_index) * vertex_stride;
        auto* skinning = output.skinning.data() + static_cast<std::size_t>(vertex_index) * skinning_stride;

        for (std::size_t component = 0; component < 8; ++component) {
            if (!std::isfinite(read<float>(source + component * sizeof(float)))) {
                return std::string("packed mesh vertex contains a non-finite value");
            }
        }

        write(vertex, read<float>(source));
        write(vertex + 4, read<float>(source + 4));
        write(vertex + 8, -read<float>(source + 8));
        write(vertex + 12, read<float>(source + 24));
        write(vertex + 16, read<float>(source + 28));
        std::fill_n(vertex + 20, 4, std::byte{0xff});
        write(vertex + 24, pack_normal(read<float>(source + 12)));
        write(vertex + 25, pack_normal(read<float>(source + 16)));
        write(vertex + 26, pack_normal(-read<float>(source + 20)));
        vertex[27] = std::byte{0};

        std::array<std::int32_t, 4> bone_indices{};
        std::array<float, 4> weights{};
        float weight_sum = 0.0f;
        for (std::size_t influence = 0; influence < 4; ++influence) {
            const auto bone_index = read<std::int32_t>(source + 32 + influence * sizeof(std::int32_t));
            const auto weight = read<float>(source + 48 + influence * sizeof(float));
            if (bone_index >= 0 && std::isfinite(weight) && weight > 0.0f) {
                if (static_cast<std::uint32_t>(bone_index) >= bone_count) {
                    return std::string("packed mesh bone index is out of range");
                }
                bone_indices[influence] = bone_index + 1;
                weights[influence] = weight;
                weight_sum += weight;
            }
        }
        if (!std::isfinite(weight_sum) || weight_sum <= 0.0f) {
            bone_indices.fill(0);
            weights.fill(0.0f);
            weights[0] = 1.0f;
            weight_sum = 1.0f;
        }
        for (std::size_t influence = 0; influence < 4; ++influence) {
            write(skinning + influence * sizeof(std::int32_t), bone_indices[influence]);
            write(skinning + 16 + influence * sizeof(float), weights[influence] / weight_sum);
        }
    }

    std::uint32_t maximum_index = 0;
    for (std::uint32_t index = 0; index < index_count; ++index) {
        const auto value = read<std::uint32_t>(indices.data() + static_cast<std::size_t>(index) * sizeof(std::uint32_t));
        if (value >= vertex_count) return std::string("packed mesh index is out of range");
        maximum_index = std::max(maximum_index, value);
    }
    output.index_stride = maximum_index <= std::numeric_limits<std::uint16_t>::max() ? 2u : 4u;
    output.indices.resize(static_cast<std::size_t>(index_count) * output.index_stride);
    for (std::uint32_t triangle = 0; triangle + 2 < index_count; triangle += 3) {
        const std::array order{triangle, triangle + 2, triangle + 1};
        for (std::uint32_t corner = 0; corner < 3; ++corner) {
            const auto value = read<std::uint32_t>(
                indices.data() + static_cast<std::size_t>(order[corner]) * sizeof(std::uint32_t));
            auto* destination = output.indices.data() +
                static_cast<std::size_t>(triangle + corner) * output.index_stride;
            if (output.index_stride == 2) {
                write(destination, static_cast<std::uint16_t>(value));
            } else {
                write(destination, value);
            }
        }
    }
    return output;
}

}
