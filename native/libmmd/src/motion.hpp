#ifndef LIBMMD_MOTION_HPP_
#define LIBMMD_MOTION_HPP_

#include "native/libmmd/src/pose.hpp"
#include "native/libmmd/src/vmd_reader.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace libmmd {

class MotionClip final {
public:
    MotionClip(std::span<const pmx::Bone> bones, const vmd::Motion& motion);

    [[nodiscard]] bool apply(float time_seconds, bool looping, Pose& pose) const noexcept;
    [[nodiscard]] std::uint32_t duration_frames() const noexcept;
    [[nodiscard]] std::uint32_t bound_bone_count() const noexcept;
    [[nodiscard]] std::uint32_t bound_ik_count() const noexcept;

private:
    struct Curve {
        float x1;
        float y1;
        float x2;
        float y2;
    };

    struct Frame {
        std::uint32_t frame;
        Vector3 translation;
        Quaternion rotation;
        std::array<Curve, 4> curves;
    };

    struct Track {
        std::uint32_t bone_index;
        std::vector<Frame> frames;
    };

    struct IkFrame {
        std::uint32_t frame;
        bool enabled;
    };

    struct IkTrack {
        std::uint32_t bone_index;
        std::vector<IkFrame> frames;
    };

    std::uint32_t bone_count_ = 0;
    std::uint32_t duration_frames_ = 0;
    std::vector<Track> tracks_;
    std::vector<IkTrack> ik_tracks_;
};

}

#endif
