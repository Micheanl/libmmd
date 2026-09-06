#include "native/libmmd/src/mmdpack.hpp"
#include "native/libmmd/src/pack_physics.hpp"
#include "native/libmmd/src/pmx_reader.hpp"
#include "native/libmmd/src/pose.hpp"
#include "native/libmmd/src/render_mesh.hpp"
#include "native/libmmd/src/skeleton.hpp"
#include "native/libmmd/src/vmd_reader.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstddef>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <variant>
#include <vector>

namespace {

std::string utf8_path(const std::filesystem::path& path) {
    const auto encoded = path.generic_u8string();
    return {encoded.begin(), encoded.end()};
}

std::vector<std::byte> read_file(const std::filesystem::path& path) {
    const auto size = std::filesystem::file_size(path);
    std::vector<std::byte> bytes(size);
    std::ifstream input(path, std::ios::binary);
    if (!input || !input.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(size))) {
        throw std::runtime_error("cannot read " + utf8_path(path));
    }
    return bytes;
}

void write_file(const std::filesystem::path& path, const std::span<const std::byte> bytes) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output || !output.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()))) {
        throw std::runtime_error("cannot write " + utf8_path(path));
    }
}

std::string lowercase_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](const unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return value;
}

std::optional<std::filesystem::path> resolve_texture(const std::filesystem::path& model, std::string texture) {
    std::replace(texture.begin(), texture.end(), '\\', '/');
    const std::filesystem::path relative(std::u8string(texture.begin(), texture.end()));
    if (relative.is_absolute()) {
        return std::nullopt;
    }
    auto resolved = model.parent_path();
    for (const auto& component : relative) {
        if (component == ".") {
            continue;
        }
        if (component == "..") {
            return std::nullopt;
        }
        const auto exact = resolved / component;
        if (std::filesystem::exists(exact)) {
            resolved = exact;
            continue;
        }
        const auto expected = lowercase_ascii(utf8_path(component));
        std::optional<std::filesystem::path> match;
        if (!std::filesystem::is_directory(resolved)) {
            return std::nullopt;
        }
        for (const auto& entry : std::filesystem::directory_iterator(resolved)) {
            if (lowercase_ascii(utf8_path(entry.path().filename())) == expected) {
                if (match.has_value()) {
                    return std::nullopt;
                }
                match = entry.path();
            }
        }
        if (!match.has_value()) {
            return std::nullopt;
        }
        resolved = *match;
    }
    if (!std::filesystem::is_regular_file(resolved)) {
        return std::nullopt;
    }
    return resolved;
}

libmmd::pmx::Model load_pmx(const std::filesystem::path& path, std::vector<std::byte>& source) {
    source = read_file(path);
    auto result = libmmd::pmx::read_model(source);
    if (const auto* error = std::get_if<libmmd::pmx::ParseError>(&result)) {
        throw std::runtime_error("PMX error at byte " + std::to_string(error->offset) + ": " + error->message);
    }
    return std::get<libmmd::pmx::Model>(std::move(result));
}

void inspect_pmx(const std::filesystem::path& path) {
    std::vector<std::byte> source;
    const auto model = load_pmx(path, source);
    std::size_t missing_textures = 0;
    for (const auto& texture : model.textures) {
        if (!resolve_texture(path, texture).has_value()) {
            ++missing_textures;
        }
    }
    std::cout << "format=PMX\n"
              << "name=" << model.name << '\n'
              << "version=" << model.header.version << '\n'
              << "vertices=" << model.vertices.size() << '\n'
              << "indices=" << model.indices.size() << '\n'
              << "textures=" << model.textures.size() << '\n'
              << "missing_textures=" << missing_textures << '\n'
              << "materials=" << model.materials.size() << '\n'
              << "bones=" << model.bones.size() << '\n'
              << "morphs=" << model.morphs.size() << '\n'
              << "rigid_bodies=" << model.rigid_bodies.size() << '\n'
              << "joints=" << model.joints.size() << '\n'
              << "soft_bodies=" << model.soft_bodies.size() << '\n';
}

void inspect_vmd(const std::filesystem::path& path) {
    const auto source = read_file(path);
    const auto result = libmmd::vmd::read_motion(source);
    if (const auto* error = std::get_if<libmmd::vmd::ParseError>(&result)) {
        throw std::runtime_error("VMD error at byte " + std::to_string(error->offset) + ": " + error->message);
    }
    const auto& motion = std::get<libmmd::vmd::Motion>(result);
    std::cout << "format=VMD\n"
              << "bone_keyframes=" << motion.bone_keyframes.size() << '\n'
              << "morph_keyframes=" << motion.morph_keyframes.size() << '\n'
              << "camera_keyframes=" << motion.camera_keyframe_count << '\n'
              << "light_keyframes=" << motion.light_keyframe_count << '\n'
              << "shadow_keyframes=" << motion.shadow_keyframe_count << '\n'
              << "ik_keyframes=" << motion.ik_keyframe_count << '\n';
}

void inspect_pack(const std::filesystem::path& path) {
    const auto source = read_file(path);
    const auto result = libmmd::pack::inspect_layout(source);
    if (const auto* error = std::get_if<libmmd::pack::Error>(&result)) {
        throw std::runtime_error("mmdpack error at byte " + std::to_string(error->offset) + ": " + error->message);
    }
    const auto& layout = std::get<libmmd::pack::Layout>(result);
    const auto& info = layout.info;
    const auto render_result = libmmd::build_render_mesh(
        std::span(source).subspan(layout.vertices.offset, layout.vertices.size),
        layout.vertices.count,
        layout.info.bone_count,
        std::span(source).subspan(layout.indices.offset, layout.indices.size),
        layout.indices.count);
    if (const auto* error = std::get_if<std::string>(&render_result)) {
        throw std::runtime_error("render mesh error: " + *error);
    }
    const auto& render_mesh = std::get<libmmd::RenderMesh>(render_result);
    std::size_t morph_offset = 0;
    const auto skeleton_result = libmmd::read_skeleton(source, layout, &morph_offset);
    if (const auto* error = std::get_if<libmmd::pack::Error>(&skeleton_result)) {
        throw std::runtime_error(
            "skeleton error at byte " + std::to_string(error->offset) + ": " + error->message);
    }
    const auto& bones = std::get<std::vector<libmmd::pmx::Bone>>(skeleton_result);
    const auto physics_result = libmmd::pack::read_physics_assets(source, layout, {}, morph_offset);
    if (const auto* error = std::get_if<libmmd::pack::Error>(&physics_result)) {
        throw std::runtime_error(
            "physics error at byte " + std::to_string(error->offset) + ": " + error->message);
    }
    std::size_t ik_constraints = 0;
    std::size_t ik_links = 0;
    for (const auto& bone : bones) {
        if ((bone.flags & 0x0020) != 0) ++ik_constraints;
        ik_links += bone.ik_links.size();
    }
    libmmd::Pose pose(bones);
    pose.evaluate();
    const auto matrices = pose.skinning_matrices();
    if (!std::all_of(matrices.begin(), matrices.end(), [](const float value) { return std::isfinite(value); })) {
        throw std::runtime_error("pose contains a non-finite matrix");
    }
    std::cout << "format=MMDPACK\n"
              << "version=" << info.version << '\n'
              << "bytes=" << info.total_size << '\n'
              << "vertices=" << info.vertex_count << '\n'
              << "indices=" << info.index_count << '\n'
              << "textures=" << info.texture_count << '\n'
              << "materials=" << info.material_count << '\n'
              << "bones=" << info.bone_count << '\n'
              << "morphs=" << info.morph_count << '\n'
              << "rigid_bodies=" << info.rigid_body_count << '\n'
              << "joints=" << info.joint_count << '\n'
              << "soft_bodies=" << info.soft_body_count << '\n'
              << "render_vertex_bytes=" << render_mesh.vertices.size() << '\n'
              << "render_skinning_bytes=" << render_mesh.skinning.size() << '\n'
              << "render_index_stride=" << render_mesh.index_stride << '\n'
              << "ik_constraints=" << ik_constraints << '\n'
              << "ik_links=" << ik_links << '\n'
              << "pose_matrices=" << matrices.size() / 16 << '\n';
}

void inspect(const std::filesystem::path& path) {
    const auto extension = path.extension().string();
    if (extension == ".pmx" || extension == ".PMX") {
        inspect_pmx(path);
    } else if (extension == ".vmd" || extension == ".VMD") {
        inspect_vmd(path);
    } else if (extension == ".mmdpack") {
        inspect_pack(path);
    } else {
        throw std::runtime_error("unsupported file extension: " + extension);
    }
}

void pack(const std::filesystem::path& input, const std::filesystem::path& output) {
    std::vector<std::byte> source;
    auto model = load_pmx(input, source);
    for (auto& texture : model.textures) {
        const auto resolved = resolve_texture(input, texture);
        if (!resolved.has_value()) {
            throw std::runtime_error("missing texture: " + texture);
        }
        texture = utf8_path(std::filesystem::relative(*resolved, input.parent_path()));
    }
    const auto bytes = libmmd::pack::build(model, source);
    write_file(output, bytes);
    inspect_pack(output);
}

}

#if defined(_WIN32)
int wmain(const int argc, wchar_t** argv) {
#else
int main(const int argc, char** argv) {
#endif
    try {
        if (argc == 3 && std::filesystem::path(argv[1]) == "inspect") {
            inspect(argv[2]);
            return 0;
        }
        if (argc == 4 && std::filesystem::path(argv[1]) == "pack") {
            pack(argv[2], argv[3]);
            return 0;
        }
        std::cerr << "usage: libmmdc inspect <asset.pmx|motion.vmd|asset.mmdpack>\n"
                  << "       libmmdc pack <asset.pmx> <asset.mmdpack>\n";
        return 2;
    } catch (const std::exception& error) {
        std::cerr << "libmmdc: " << error.what() << '\n';
        return 1;
    }
}
