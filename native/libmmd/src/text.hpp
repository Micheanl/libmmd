#ifndef LIBMMD_TEXT_HPP_
#define LIBMMD_TEXT_HPP_

#include <cstddef>
#include <span>
#include <string>

namespace libmmd {

[[nodiscard]] std::string decode_windows31j(std::span<const std::byte> bytes);

}

#endif
