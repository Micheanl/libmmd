#include "native/libmmd/src/skeleton.hpp"

#include <array>
#include <cmath>
#include <cstring>
#include <new>
#include <stdexcept>
#include <string_view>

namespace libmmd {
namespace {

class Failure final : public std::runtime_error {
public:
    Failure(const std::size_t offset, std::string message)
        : std::runtime_error(std::move(message)), offset(offset) {}

    std::size_t offset;
};

class Reader final {
public:
    Reader(const std::span<const std::byte> bytes, const std::size_t offset)
        : bytes_(bytes), position_(offset) {
        if (offset > bytes.size()) fail("mmdpack skeleton offset is invalid");
    }

    template <typename T>
    T read(const std::string_view label) {
        if (bytes_.size() - position_ < sizeof(T)) fail(std::string(label) + " is truncated");
        T value{};
        std::memcpy(&value, bytes_.data() + position_, sizeof(T));
        position_ += sizeof(T);
        return value;
    }

    void skip(const std::size_t size, const std::string_view label) {
        if (bytes_.size() - position_ < size) fail(std::string(label) + " is truncated");
        position_ += size;
    }

    std::string string(const std::string_view label) {
        const auto size = read<std::uint32_t>(label);
        if (bytes_.size() - position_ < size) fail(std::string(label) + " is truncated");
        std::string value(reinterpret_cast<const char*>(bytes_.data() + position_), size);
        position_ += size;
        return value;
    }

    void skip_string(const std::string_view label) {
        const auto size = read<std::uint32_t>(label);
        skip(size, label);
    }

    [[nodiscard]] std::size_t position() const noexcept { return position_; }

    [[noreturn]] void fail(std::string message) const {
        throw Failure(position_, std::move(message));
    }

private:
    std::span<const std::byte> bytes_;
    std::size_t position_;
};

template <std::size_t Size>
std::array<float, Size> floats(Reader& reader, const std::string_view label) {
    std::array<float, Size> values{};
    for (auto& value : values) {
        value = reader.read<float>(label);
        if (!std::isfinite(value)) reader.fail(std::string(label) + " contains a non-finite value");
    }
    return values;
}

float scalar(Reader& reader, const std::string_view label) {
    return floats<1>(reader, label)[0];
}

void skip_to_bones(Reader& reader, const pack::Info& info) {
    for (std::uint32_t texture = 0; texture < info.texture_count; ++texture) {
        reader.skip_string("mmdpack texture");
    }
    for (std::uint32_t material = 0; material < info.material_count; ++material) {
        reader.skip_string("mmdpack material name");
        reader.skip_string("mmdpack material English name");
        reader.skip(78, "mmdpack material");
        reader.skip_string("mmdpack material metadata");
        reader.skip(sizeof(std::uint32_t), "mmdpack material index count");
    }
}

pmx::Bone read_bone(Reader& reader) {
    pmx::Bone bone{};
    bone.name = reader.string("mmdpack bone name");
    bone.english_name = reader.string("mmdpack bone English name");
    bone.position = floats<3>(reader, "mmdpack bone position");
    bone.parent_index = reader.read<std::int32_t>("mmdpack bone parent");
    bone.deform_layer = reader.read<std::int32_t>("mmdpack bone deform layer");
    bone.flags = reader.read<std::uint16_t>("mmdpack bone flags");
    if ((bone.flags & 0x0001) != 0) {
        bone.connection_index = reader.read<std::int32_t>("mmdpack bone connection");
    } else {
        bone.connection_offset = floats<3>(reader, "mmdpack bone connection offset");
    }
    if ((bone.flags & 0x0300) != 0) {
        bone.inheritance_index = reader.read<std::int32_t>("mmdpack bone inheritance source");
        bone.inheritance_weight = scalar(reader, "mmdpack bone inheritance weight");
    }
    if ((bone.flags & 0x0400) != 0) bone.fixed_axis = floats<3>(reader, "mmdpack bone fixed axis");
    if ((bone.flags & 0x0800) != 0) {
        bone.local_x_axis = floats<3>(reader, "mmdpack bone local X axis");
        bone.local_z_axis = floats<3>(reader, "mmdpack bone local Z axis");
    }
    if ((bone.flags & 0x2000) != 0) {
        bone.external_parent_key = reader.read<std::int32_t>("mmdpack bone external parent");
    }
    if ((bone.flags & 0x0020) != 0) {
        bone.ik_target_index = reader.read<std::int32_t>("mmdpack IK target");
        bone.ik_iteration_count = reader.read<std::int32_t>("mmdpack IK iteration count");
        bone.ik_angle_limit = scalar(reader, "mmdpack IK angle limit");
        const auto link_count = reader.read<std::uint32_t>("mmdpack IK link count");
        if (link_count > 1'000'000) reader.fail("mmdpack IK link count is invalid");
        bone.ik_links.reserve(link_count);
        for (std::uint32_t link_index = 0; link_index < link_count; ++link_index) {
            pmx::Bone::IkLink link{};
            link.bone_index = reader.read<std::int32_t>("mmdpack IK link bone");
            const auto limited = reader.read<std::uint8_t>("mmdpack IK link limit flag");
            if (limited > 1) reader.fail("mmdpack IK link limit flag is invalid");
            link.limited = limited == 1;
            if (link.limited) {
                link.lower_limit = floats<3>(reader, "mmdpack IK lower limit");
                link.upper_limit = floats<3>(reader, "mmdpack IK upper limit");
            }
            bone.ik_links.push_back(link);
        }
    }
    return bone;
}

bool valid_index(const std::int32_t index, const std::uint32_t count) {
    return index == -1 || (index >= 0 && static_cast<std::uint32_t>(index) < count);
}

}

SkeletonResult read_skeleton(
    const std::span<const std::byte> bytes,
    const pack::Layout& layout,
    std::size_t* const end_offset) {
    try {
        Reader reader(bytes, layout.indices.offset + layout.indices.size);
        skip_to_bones(reader, layout.info);
        std::vector<pmx::Bone> bones;
        bones.reserve(layout.info.bone_count);
        for (std::uint32_t index = 0; index < layout.info.bone_count; ++index) {
            bones.push_back(read_bone(reader));
        }
        for (const auto& bone : bones) {
            if (!valid_index(bone.parent_index, layout.info.bone_count) ||
                ((bone.flags & 0x0001) != 0 && !valid_index(bone.connection_index, layout.info.bone_count)) ||
                ((bone.flags & 0x0300) != 0 && !valid_index(bone.inheritance_index, layout.info.bone_count)) ||
                ((bone.flags & 0x0020) != 0 && !valid_index(bone.ik_target_index, layout.info.bone_count)) ||
                bone.ik_iteration_count < 0 || bone.ik_angle_limit < 0.0f) {
                return pack::Error{reader.position(), "mmdpack bone metadata is invalid"};
            }
            for (const auto& link : bone.ik_links) {
                if (!valid_index(link.bone_index, layout.info.bone_count)) {
                    return pack::Error{reader.position(), "mmdpack IK link index is invalid"};
                }
            }
        }
        if (end_offset != nullptr) *end_offset = reader.position();
        return bones;
    } catch (const Failure& failure) {
        return pack::Error{failure.offset, failure.what()};
    } catch (const std::bad_alloc&) {
        return pack::Error{0, "mmdpack skeleton allocation failed"};
    }
}

}
