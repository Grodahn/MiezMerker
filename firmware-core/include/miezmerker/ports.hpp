#pragma once
#include <cstddef>
#include <cstdint>
#include <span>

namespace miezmerker {
struct Clock {
    virtual ~Clock() = default;
    virtual std::uint64_t monotonic_ms() const = 0;
};
struct Storage {
    virtual ~Storage() = default;
    virtual bool open() = 0;
};
struct RfidReader {
    virtual ~RfidReader() = default;
    virtual bool poll(std::span<std::byte> chip_id) = 0;
};
struct BleTransport {
    virtual ~BleTransport() = default;
    virtual bool send(std::span<const std::byte> message) = 0;
};
} // namespace miezmerker
