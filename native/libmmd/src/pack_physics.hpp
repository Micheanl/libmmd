#ifndef LIBMMD_PACK_PHYSICS_HPP_
#define LIBMMD_PACK_PHYSICS_HPP_

#include "native/libmmd/src/mmdpack.hpp"

#include <optional>

namespace libmmd::pack {

struct PhysicsAssets {
    std::vector<pmx::RigidBody> rigid_bodies;
    std::vector<pmx::Joint> joints;
    std::vector<pmx::SoftBody> soft_bodies;
};

using PhysicsAssetsResult = std::variant<PhysicsAssets, Error>;

[[nodiscard]] PhysicsAssetsResult read_physics_assets(
    std::span<const std::byte> bytes,
    const Layout& layout,
    pmx::Limits limits = {},
    std::optional<std::size_t> morph_offset = std::nullopt);

}

#endif
