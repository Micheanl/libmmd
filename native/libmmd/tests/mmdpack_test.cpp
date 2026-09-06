#include "native/libmmd/src/mmdpack.hpp"

#include <cassert>
#include <cstring>
#include <variant>

int main() {
    libmmd::pmx::Model model{};
    model.name = "test";
    model.vertices.resize(2);
    model.indices = {0, 1, 0};
    model.textures = {"body.png"};
    model.materials.resize(1);
    model.materials.front().index_count = 3;
    model.bones.resize(1);
    model.morphs.resize(1);
    model.rigid_bodies.resize(1);
    model.joints.resize(1);
    const auto bytes = libmmd::pack::build(model, {});
    const auto result = libmmd::pack::inspect(bytes);
    assert(std::holds_alternative<libmmd::pack::Info>(result));
    const auto& info = std::get<libmmd::pack::Info>(result);
    assert(info.vertex_count == 2);
    assert(info.index_count == 3);
    assert(info.texture_count == 1);
    assert(info.material_count == 1);
    assert(info.bone_count == 1);
    assert(info.morph_count == 1);
    assert(info.rigid_body_count == 1);
    assert(info.joint_count == 1);

    auto damaged = bytes;
    damaged.back() ^= std::byte{1};
    assert(std::holds_alternative<libmmd::pack::Error>(libmmd::pack::inspect(damaged)));

    auto legacy = bytes;
    const std::uint32_t legacy_version = 1;
    std::memcpy(legacy.data() + 8, &legacy_version, sizeof(legacy_version));
    assert(std::holds_alternative<libmmd::pack::Error>(libmmd::pack::inspect(legacy)));
    return 0;
}
