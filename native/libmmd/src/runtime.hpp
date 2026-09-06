#ifndef LIBMMD_RUNTIME_HPP_
#define LIBMMD_RUNTIME_HPP_

#include <cstdint>
#include <memory>
#include <string>

namespace libmmd {

class PhysicsEngine;

class Runtime final {
public:
    explicit Runtime(std::uint32_t worker_threads);
    ~Runtime();

    [[nodiscard]] std::uint32_t worker_threads() const noexcept;
    [[nodiscard]] const std::string& last_error() const noexcept;
    [[nodiscard]] PhysicsEngine& physics() noexcept;
    void set_error(std::string message);

private:
    std::uint32_t worker_threads_;
    std::string last_error_;
    std::unique_ptr<PhysicsEngine> physics_;
};

}

#endif
