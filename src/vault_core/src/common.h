// Internal helpers shared by the core sources. Not part of the C ABI.
#ifndef PV_CORE_COMMON_H
#define PV_CORE_COMMON_H

#include "pv/pv.h"

#include <sodium.h>

#include <cstdint>
#include <cstring>
#include <new>
#include <string>
#include <vector>

namespace pv {

// Runs sodium_init() if needed. Returns PV_OK or PV_ERR_INIT.
int ensure_init();

inline void put_u16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v & 0xff);
    p[1] = static_cast<uint8_t>(v >> 8);
}

inline void put_u32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) {
        p[i] = static_cast<uint8_t>(v >> (8 * i));
    }
}

inline void put_u64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) {
        p[i] = static_cast<uint8_t>(v >> (8 * i));
    }
}

inline uint16_t get_u16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (static_cast<uint16_t>(p[1]) << 8));
}

inline uint32_t get_u32(const uint8_t* p) {
    uint32_t v = 0;
    for (int i = 3; i >= 0; --i) {
        v = (v << 8) | p[i];
    }
    return v;
}

inline uint64_t get_u64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) {
        v = (v << 8) | p[i];
    }
    return v;
}

// Reads from src until buf holds want bytes or a read returns zero bytes.
// *got is the number of bytes collected. Returns PV_OK or PV_ERR_IO.
int fill(pv_source* src, uint8_t* buf, size_t want, size_t* got);

// Reads until one byte arrives or a read returns zero bytes.
int probe_more(pv_source* src, bool* has_more);

inline int write_all(pv_sink* dst, const uint8_t* p, size_t n) {
    if (n == 0) {
        return PV_OK;
    }
    return dst->write(dst->ctx, p, n) == 0 ? PV_OK : PV_ERR_IO;
}

// Byte buffer that is wiped when it goes out of scope.
class WipedBuffer {
public:
    explicit WipedBuffer(size_t n) : data_(n) {}
    ~WipedBuffer() {
        if (!data_.empty()) {
            sodium_memzero(data_.data(), data_.size());
        }
    }
    WipedBuffer(const WipedBuffer&) = delete;
    WipedBuffer& operator=(const WipedBuffer&) = delete;
    uint8_t* data() { return data_.data(); }
    size_t size() const { return data_.size(); }

private:
    std::vector<uint8_t> data_;
};

}  // namespace pv

#endif
