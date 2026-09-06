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
    model.materials.front().name = "body";
    model.materials.front().diffuse = {1.0f, 0.5f, 0.25f, 1.0f};
    model.materials.front().texture_index = 0;
    model.materials.front().toon_texture_index = 10;
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
    const auto layout = std::get<libmmd::pack::Layout>(libmmd::pack::inspect_layout(bytes));
    const auto assets_result = libmmd::pack::read_render_assets(bytes, layout);
    assert(std::holds_alternative<libmmd::pack::RenderAssets>(assets_result));
    const auto& assets = std::get<libmmd::pack::RenderAssets>(assets_result);
    assert(assets.textures.size() == 1);
    assert(assets.textures.front() == "body.png");
    assert(assets.materials.size() == 1);
    assert(assets.materials.front().value.name == "body");
    assert(assets.materials.front().value.texture_index == 0);
    assert(assets.materials.front().first_index == 0);
    assert(assets.materials.front().value.index_count == 3);

    auto damaged = bytes;
    damaged.back() ^= std::byte{1};
    assert(std::holds_alternative<libmmd::pack::Error>(libmmd::pack::inspect(damaged)));

    auto legacy = bytes;
    const std::uint32_t legacy_version = 1;
    std::memcpy(legacy.data() + 8, &legacy_version, sizeof(legacy_version));
    assert(std::holds_alternative<libmmd::pack::Error>(libmmd::pack::inspect(legacy)));
    return 0;
}
