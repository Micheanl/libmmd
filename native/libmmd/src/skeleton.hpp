#ifndef LIBMMD_SKELETON_HPP_
#define LIBMMD_SKELETON_HPP_

#include "native/libmmd/src/mmdpack.hpp"
#include "native/libmmd/src/pmx_reader.hpp"

#include <cstddef>
#include <span>
#include <variant>
#include <vector>

namespace libmmd {

using SkeletonResult = std::variant<std::vector<pmx::Bone>, pack::Error>;

[[nodiscard]] SkeletonResult read_skeleton(
    std::span<const std::byte> bytes,
    const pack::Layout& layout);

}

#endif
