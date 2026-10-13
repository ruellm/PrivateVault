#include "envelope.h"

#include <sodium.h>

#include <cstring>

namespace pvd {
namespace {

constexpr uint8_t kMagic[8] = {'P', 'V', 'K', 'E', 'Y', 'E', 'N', 'V'};
constexpr size_t kOffVersion = 8;
constexpr size_t kOffProvider = 10;
constexpr size_t kOffOps = 12;
constexpr size_t kOffMem = 16;
constexpr size_t kOffSalt = 24;
constexpr size_t kOffNonce = 40;
constexpr size_t kOffWrapped = 64;
constexpr size_t kKekBytes = crypto_aead_xchacha20poly1305_ietf_KEYBYTES;
constexpr size_t kWrappedBytes = PV_VMK_BYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES;

static_assert(kOffSalt + crypto_pwhash_SALTBYTES == kOffNonce, "layout");
static_assert(kOffNonce + crypto_aead_xchacha20poly1305_ietf_NPUBBYTES == kOffWrapped, "layout");
static_assert(kOffWrapped + kWrappedBytes == kEnvelopeBytes, "layout");

constexpr uint32_t kOpsMin = crypto_pwhash_OPSLIMIT_INTERACTIVE;
constexpr uint32_t kOpsMax = 16;
constexpr uint64_t kMemMin = crypto_pwhash_MEMLIMIT_INTERACTIVE;
constexpr uint64_t kMemMax = crypto_pwhash_MEMLIMIT_SENSITIVE;

void put_u16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
}
void put_u32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
void put_u64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
uint16_t get_u16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (static_cast<uint16_t>(p[1]) << 8));
}
uint32_t get_u32(const uint8_t* p) {
    uint32_t v = 0;
    for (int i = 3; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}
uint64_t get_u64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}

// Derives the KEK into kek. The algorithm is named explicitly so a change of
// libsodium's default cannot alter existing envelopes.
int derive_kek(const char* password, size_t password_len, const uint8_t* salt,
               const KdfParams& p, uint8_t* kek) {
    if (crypto_pwhash(kek, kKekBytes, password, password_len, salt, p.opslimit,
                      static_cast<size_t>(p.memlimit), crypto_pwhash_ALG_ARGON2ID13) != 0) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    return PV_OK;
}

}  // namespace

KdfParams kdf_moderate() {
    return {crypto_pwhash_OPSLIMIT_MODERATE, crypto_pwhash_MEMLIMIT_MODERATE};
}

KdfParams kdf_interactive() {
    return {crypto_pwhash_OPSLIMIT_INTERACTIVE, crypto_pwhash_MEMLIMIT_INTERACTIVE};
}

bool kdf_params_in_range(const KdfParams& p) {
    return p.opslimit >= kOpsMin && p.opslimit <= kOpsMax && p.memlimit >= kMemMin &&
           p.memlimit <= kMemMax;
}

int envelope_seal(const uint8_t* vmk, size_t vmk_len, const char* password,
                  size_t password_len, const KdfParams& params, uint8_t* out, size_t out_len) {
    if (pv_init() != PV_OK) {
        return PV_ERR_INIT;
    }
    if (!vmk || vmk_len != PV_VMK_BYTES || !password || !out || out_len != kEnvelopeBytes ||
        !kdf_params_in_range(params)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    std::memcpy(out, kMagic, sizeof(kMagic));
    put_u16(out + kOffVersion, kEnvelopeVersion);
    put_u16(out + kOffProvider, kProviderPassword);
    put_u32(out + kOffOps, params.opslimit);
    put_u64(out + kOffMem, params.memlimit);
    randombytes_buf(out + kOffSalt, crypto_pwhash_SALTBYTES);
    randombytes_buf(out + kOffNonce, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);

    uint8_t kek[kKekBytes];
    int rc = derive_kek(password, password_len, out + kOffSalt, params, kek);
    if (rc != PV_OK) {
        sodium_memzero(kek, sizeof(kek));
        return rc;
    }
    unsigned long long clen = 0;
    int enc = crypto_aead_xchacha20poly1305_ietf_encrypt(out + kOffWrapped, &clen, vmk, vmk_len,
                                                         out, kOffWrapped, nullptr,
                                                         out + kOffNonce, kek);
    sodium_memzero(kek, sizeof(kek));
    if (enc != 0 || clen != kWrappedBytes) {
        return PV_ERR_INTERNAL;
    }
    return PV_OK;
}

int envelope_open(const uint8_t* data, size_t len, const char* password, size_t password_len,
                  uint8_t* vmk_out, size_t vmk_out_len) {
    if (pv_init() != PV_OK) {
        return PV_ERR_INIT;
    }
    if (!data || !password || !vmk_out || vmk_out_len != PV_VMK_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (len < kOffOps || std::memcmp(data, kMagic, sizeof(kMagic)) != 0) {
        return PV_ERR_ENVELOPE_MALFORMED;
    }
    if (get_u16(data + kOffVersion) != kEnvelopeVersion ||
        get_u16(data + kOffProvider) != kProviderPassword) {
        return PV_ERR_ENVELOPE_UNSUPPORTED;
    }
    if (len != kEnvelopeBytes) {
        return PV_ERR_ENVELOPE_MALFORMED;
    }
    KdfParams p{get_u32(data + kOffOps), get_u64(data + kOffMem)};
    if (!kdf_params_in_range(p)) {
        return PV_ERR_ENVELOPE_KDF_PARAMS;
    }
    uint8_t kek[kKekBytes];
    int rc = derive_kek(password, password_len, data + kOffSalt, p, kek);
    if (rc != PV_OK) {
        sodium_memzero(kek, sizeof(kek));
        return rc;
    }
    unsigned long long mlen = 0;
    int dec = crypto_aead_xchacha20poly1305_ietf_decrypt(vmk_out, &mlen, nullptr,
                                                         data + kOffWrapped, kWrappedBytes,
                                                         data, kOffWrapped, data + kOffNonce,
                                                         kek);
    sodium_memzero(kek, sizeof(kek));
    if (dec != 0) {
        sodium_memzero(vmk_out, vmk_out_len);
        return PV_ERR_ENVELOPE_UNWRAP_FAILED;
    }
    return mlen == PV_VMK_BYTES ? PV_OK : PV_ERR_INTERNAL;
}

int envelope_write_file(const std::string& path, const uint8_t* vmk, size_t vmk_len,
                        const char* password, size_t password_len, const KdfParams& params) {
    uint8_t buf[kEnvelopeBytes];
    int rc = envelope_seal(vmk, vmk_len, password, password_len, params, buf, sizeof(buf));
    if (rc != PV_OK) {
        return rc;
    }
    return pv_file_write_atomic(path.c_str(), buf, sizeof(buf));
}

int envelope_read_file(const std::string& path, const char* password, size_t password_len,
                       uint8_t* vmk_out, size_t vmk_out_len) {
    uint8_t buf[kEnvelopeBytes + 1];
    size_t got = 0;
    int rc = pv_file_read_all(path.c_str(), buf, sizeof(buf), &got);
    if (rc == PV_ERR_IO && got == sizeof(buf)) {
        return PV_ERR_ENVELOPE_MALFORMED;
    }
    if (rc != PV_OK) {
        return rc;
    }
    return envelope_open(buf, got, password, password_len, vmk_out, vmk_out_len);
}

}  // namespace pvd
