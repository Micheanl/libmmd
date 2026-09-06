#include "native/libmmd/src/render_mesh.hpp"

#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <variant>
#include <vector>

namespace {

template <typename T>
void write(std::vector<std::byte>& bytes, const std::size_t offset, const T value) {
    std::memcpy(bytes.data() + offset, &value, sizeof(T));
}

template <typename T>
T read(const std::vector<std::byte>& bytes, const std::size_t offset) {
    T value{};
    std::memcpy(&value, bytes.data() + offset, sizeof(T));
    return value;
}

}

int main() {
    std::vector<std::byte> vertices(3 * 68);
    for (std::size_t vertex = 0; vertex < 3; ++vertex) {
        for (std::size_t influence = 0; influence < 4; ++influence) {
            write(vertices, vertex * 68 + 32 + influence * 4, std::int32_t{-1});
        }
    }
    write(vertices, 0, 1.0f);
    write(vertices, 4, 2.0f);
    write(vertices, 8, 3.0f);
    write(vertices, 12, 0.5f);
    write(vertices, 16, -0.5f);
    write(vertices, 20, 0.25f);
    write(vertices, 24, 0.75f);
    write(vertices, 28, 0.125f);
    write(vertices, 32, std::int32_t{3});
    write(vertices, 48, 0.25f);

    std::vector<std::byte> indices(3 * sizeof(std::uint32_t));
    write(indices, 0, std::uint32_t{0});
    write(indices, 4, std::uint32_t{1});
    write(indices, 8, std::uint32_t{2});
    auto result = libmmd::build_render_mesh(vertices, 3, 4, indices, 3);
    assert(std::holds_alternative<libmmd::RenderMesh>(result));
    const auto& mesh = std::get<libmmd::RenderMesh>(result);
    assert(mesh.vertex_count == 3);
    assert(read<float>(mesh.vertices, 0) == 1.0f);
    assert(read<float>(mesh.vertices, 4) == 2.0f);
    assert(read<float>(mesh.vertices, 8) == -3.0f);
    assert(read<float>(mesh.vertices, 12) == 0.75f);
    assert(read<float>(mesh.vertices, 16) == 0.125f);
    assert(read<std::uint32_t>(mesh.vertices, 20) == 0xffffffffu);
    assert(read<std::int8_t>(mesh.vertices, 24) == 63);
    assert(read<std::int8_t>(mesh.vertices, 25) == -63);
    assert(read<std::int8_t>(mesh.vertices, 26) == -31);
    assert(read<std::int32_t>(mesh.skinning, 0) == 4);
    assert(read<float>(mesh.skinning, 16) == 1.0f);
    assert(mesh.index_stride == 2);
    assert(read<std::uint16_t>(mesh.indices, 0) == 0);
    assert(read<std::uint16_t>(mesh.indices, 2) == 2);
    assert(read<std::uint16_t>(mesh.indices, 4) == 1);

    write(vertices, 32, std::int32_t{4});
    assert(std::holds_alternative<std::string>(libmmd::build_render_mesh(vertices, 3, 4, indices, 3)));
    write(vertices, 32, std::int32_t{3});
    write(vertices, 0, std::numeric_limits<float>::quiet_NaN());
    assert(std::holds_alternative<std::string>(libmmd::build_render_mesh(vertices, 3, 4, indices, 3)));
    write(vertices, 0, 1.0f);

    indices.resize(4);
    write(indices, 0, std::uint32_t{3});
    const auto invalid = libmmd::build_render_mesh(vertices, 3, 4, indices, 1);
    assert(std::holds_alternative<std::string>(invalid));
    return 0;
}
