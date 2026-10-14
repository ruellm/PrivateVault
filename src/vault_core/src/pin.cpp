// PIN stretching with Argon2id. See docs/PHASE3A_DESIGN.md Section 3.
#include "common.h"

namespace pv {

static_assert(PV_PIN_SALT_BYTES == crypto_pwhash_SALTBYTES, "salt size");
static_assert(PV_KEK_BYTES == crypto_aead_xchacha20poly1305_ietf_KEYBYTES, "kek size");
static_assert(PV_PIN_OPSLIMIT_MIN == crypto_pwhash_OPSLIMIT_INTERACTIVE, "opslimit min");
static_assert(PV_PIN_OPSLIMIT_MAX == crypto_pwhash_OPSLIMIT_MODERATE, "opslimit max");
static_assert(PV_PIN_MEMLIMIT_MIN == crypto_pwhash_MEMLIMIT_INTERACTIVE, "memlimit min");
static_assert(PV_PIN_MEMLIMIT_MAX == crypto_pwhash_MEMLIMIT_MODERATE, "memlimit max");

bool pin_params_in_range(uint64_t opslimit, uint64_t memlimit) {
    return opslimit >= PV_PIN_OPSLIMIT_MIN && opslimit <= PV_PIN_OPSLIMIT_MAX &&
           memlimit >= PV_PIN_MEMLIMIT_MIN && memlimit <= PV_PIN_MEMLIMIT_MAX;
}

}  // namespace pv

extern "C" {

int pv_pin_stretch(const uint8_t* pin, size_t pin_len, const uint8_t* salt, size_t salt_len,
                   uint64_t opslimit, uint64_t memlimit, uint8_t* out, size_t out_len) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!pin || pin_len == 0 || !salt || salt_len != PV_PIN_SALT_BYTES || !out ||
        out_len != PV_KEK_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (!pv::pin_params_in_range(opslimit, memlimit)) {
        return PV_ERR_ENVELOPE_KDF_PARAMS;
    }
    // The algorithm is named explicitly so a change of the libsodium default
    // cannot alter existing envelopes.
    if (crypto_pwhash(out, out_len, reinterpret_cast<const char*>(pin), pin_len, salt,
                      opslimit, static_cast<size_t>(memlimit),
                      crypto_pwhash_ALG_ARGON2ID13) != 0) {
        sodium_memzero(out, out_len);
        return PV_ERR_OUT_OF_MEMORY;
    }
    return PV_OK;
}

int pv_pin_salt_generate(uint8_t* out, size_t out_len) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!out || out_len != PV_PIN_SALT_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    randombytes_buf(out, out_len);
    return PV_OK;
}

uint64_t pv_pin_delay_ms(uint32_t failures) {
    constexpr uint32_t kFreeAttempts = 5;
    constexpr uint64_t kFirstDelayS = 30;
    constexpr uint64_t kCapS = 3600;
    if (failures < kFreeAttempts) {
        return 0;
    }
    uint32_t doublings = failures - kFreeAttempts;
    if (doublings > 7) {
        return kCapS * 1000;
    }
    uint64_t s = kFirstDelayS << doublings;
    if (s > kCapS) {
        s = kCapS;
    }
    return s * 1000;
}

uint64_t pv_pin_remaining_ms(uint64_t delay_ms, uint64_t fail_elapsed_ms, uint64_t now_elapsed_ms,
                             int64_t fail_wall_ms, int64_t now_wall_ms, int boot_changed) {
    if (delay_ms == 0) {
        return 0;
    }
    if (boot_changed) {
        return delay_ms;
    }
    uint64_t by_elapsed = delay_ms;
    if (now_elapsed_ms >= fail_elapsed_ms) {
        uint64_t passed = now_elapsed_ms - fail_elapsed_ms;
        by_elapsed = passed >= delay_ms ? 0 : delay_ms - passed;
    }
    uint64_t by_wall = delay_ms;
    if (now_wall_ms >= fail_wall_ms) {
        uint64_t passed = static_cast<uint64_t>(now_wall_ms - fail_wall_ms);
        by_wall = passed >= delay_ms ? 0 : delay_ms - passed;
    }
    return by_elapsed > by_wall ? by_elapsed : by_wall;
}

}
