#include "native/libmmd/src/text.hpp"

#include <algorithm>
#include <stdexcept>

#if defined(_WIN32)
#include <windows.h>
#else
#include <cerrno>
#include <iconv.h>
#endif

namespace libmmd {

std::string decode_windows31j(std::span<const std::byte> bytes) {
    const auto terminator = std::find(bytes.begin(), bytes.end(), std::byte{0});
    bytes = bytes.first(static_cast<std::size_t>(terminator - bytes.begin()));
    if (bytes.empty()) return {};
#if defined(_WIN32)
    const auto* input = reinterpret_cast<const char*>(bytes.data());
    const auto input_size = static_cast<int>(bytes.size());
    const auto wide_size = MultiByteToWideChar(932, MB_ERR_INVALID_CHARS, input, input_size, nullptr, 0);
    if (wide_size <= 0) throw std::invalid_argument("Windows-31J text is invalid");
    std::wstring wide(static_cast<std::size_t>(wide_size), L'\0');
    MultiByteToWideChar(932, MB_ERR_INVALID_CHARS, input, input_size, wide.data(), wide_size);
    const auto utf8_size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide.data(), wide_size, nullptr, 0, nullptr, nullptr);
    if (utf8_size <= 0) throw std::invalid_argument("Windows-31J text conversion failed");
    std::string output(static_cast<std::size_t>(utf8_size), '\0');
    WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide.data(), wide_size, output.data(), utf8_size, nullptr, nullptr);
    return output;
#else
    auto converter = iconv_open("UTF-8", "CP932");
    if (converter == reinterpret_cast<iconv_t>(-1)) converter = iconv_open("UTF-8", "SHIFT-JIS");
    if (converter == reinterpret_cast<iconv_t>(-1)) throw std::runtime_error("Windows-31J converter is unavailable");
    std::string output(bytes.size() * 4 + 4, '\0');
    auto* input = const_cast<char*>(reinterpret_cast<const char*>(bytes.data()));
    auto input_size = bytes.size();
    auto* destination = output.data();
    auto output_size = output.size();
    errno = 0;
    const auto result = iconv(converter, &input, &input_size, &destination, &output_size);
    iconv_close(converter);
    if (result == static_cast<std::size_t>(-1) || input_size != 0) {
        throw std::invalid_argument("Windows-31J text is invalid");
    }
    output.resize(output.size() - output_size);
    return output;
#endif
}

}
