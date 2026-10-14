// Builds provider 3 envelopes by hand so tests and the vector generator can
// produce layouts the library never writes (several slots, unknown slot
// types, wrong lengths). Mirrors docs/PHASE3A_DESIGN.md Section 2; a drift
// between this and the core shows up as a test failure. Test-only code.
#ifndef PV_TESTS_ENVELOPE3_BUILDER_H
#define PV_TESTS_ENVELOPE3_BUILDER_H

#include "pv/pv.h"

#include <sodium.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace e3 {

using Bytes = std::vector<uint8_t>;

constexpr size_t kHeaderBytes = 16;
constexpr size_t kSlotHeaderBytes = 4;
constexpr size_t kWrappedBytes = 48;
constexpr size_t kNonceBytes = 24;

struct Slot {
    uint16_t type;
    Bytes body;
};

inline void put16(Bytes& b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v & 0xff));
    b.push_back(static_cast<uint8_t>(v >> 8));
}
inline void put32(Bytes& b, uint32_t v) {
    for (int i = 0; i < 4; ++i) b.push_back(static_cast<uint8_t>(v >> (8 * i)));
}
inline void put64(Bytes& b, uint64_t v) {
    for (int i = 0; i < 8; ++i) b.push_back(static_cast<uint8_t>(v >> (8 * i)));
}

// PIN slot body with the wrapped field still zero. nonce may be null for a
// random one.
inline Bytes pin_body(uint16_t mechanism, const std::string& alias, uint32_t opslimit,
                      uint64_t memlimit, const uint8_t* salt16, const uint8_t* nonce24) {
    Bytes b;
    put16(b, mechanism);
    put16(b, static_cast<uint16_t>(alias.size()));
    b.insert(b.end(), alias.begin(), alias.end());
    put32(b, opslimit);
    put64(b, memlimit);
    b.insert(b.end(), salt16, salt16 + PV_PIN_SALT_BYTES);
    if (nonce24) {
        b.insert(b.end(), nonce24, nonce24 + kNonceBytes);
    } else {
        uint8_t n[kNonceBytes];
        randombytes_buf(n, sizeof(n));
        b.insert(b.end(), n, n + sizeof(n));
    }
    b.resize(b.size() + kWrappedBytes, 0);
    return b;
}

// Lays out header and slots, then wraps vmk into every PIN slot body of at
// least the fixed size using keks[i] for the i-th PIN slot. The additional
// data is the image before any wrapped field is filled.
inline Bytes build(uint16_t version, uint16_t provider, uint16_t slot_count_field,
                   uint16_t reserved, const std::vector<Slot>& slots, const uint8_t* vmk,
                   const std::vector<const uint8_t*>& keks) {
    Bytes out;
    const char* magic = "PVKEYENV";
    out.insert(out.end(), magic, magic + 8);
    put16(out, version);
    put16(out, provider);
    put16(out, slot_count_field);
    put16(out, reserved);
    std::vector<size_t> wrapped_offsets;
    for (const Slot& s : slots) {
        put16(out, s.type);
        put16(out, static_cast<uint16_t>(s.body.size()));
        size_t body_off = out.size();
        out.insert(out.end(), s.body.begin(), s.body.end());
        if (s.type == PV_SLOT_PIN && s.body.size() >= 104) {
            wrapped_offsets.push_back(body_off + s.body.size() - kWrappedBytes);
        }
    }
    Bytes ad = out;
    for (size_t i = 0; i < wrapped_offsets.size() && i < keks.size(); ++i) {
        size_t w = wrapped_offsets[i];
        unsigned long long clen = 0;
        crypto_aead_xchacha20poly1305_ietf_encrypt(out.data() + w, &clen, vmk, PV_VMK_BYTES,
                                                   ad.data(), ad.size(), nullptr,
                                                   out.data() + w - kNonceBytes, keks[i]);
    }
    return out;
}

}  // namespace e3

#endif
