// Key envelope provider 3: PIN slots. Layout and rules in
// docs/PHASE3A_DESIGN.md Section 2.
#include "fs.h"
#include "vault_internal.h"

namespace pv {

bool pin_params_in_range(uint64_t opslimit, uint64_t memlimit);  // pin.cpp

namespace {

constexpr uint8_t kEnvMagic[8] = {'P', 'V', 'K', 'E', 'Y', 'E', 'N', 'V'};
constexpr uint16_t kEnvVersion = 1;
constexpr size_t kOffVersion = 8;
constexpr size_t kOffProvider = 10;
constexpr size_t kOffSlotCount = 12;
constexpr size_t kOffReserved = 14;
constexpr size_t kHeaderBytes = 16;
constexpr size_t kSlotHeaderBytes = 4;
constexpr size_t kKekBytes = PV_KEK_BYTES;
constexpr size_t kNonceBytes = crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;
constexpr size_t kWrappedBytes = PV_VMK_BYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES;
// PIN slot body without the alias: mechanism, alias_len, opslimit, memlimit,
// salt, nonce, wrapped VMK.
constexpr size_t kPinFixedBytes = 2 + 2 + 4 + 8 + PV_PIN_SALT_BYTES + kNonceBytes + kWrappedBytes;

static_assert(kKekBytes == crypto_aead_xchacha20poly1305_ietf_KEYBYTES, "kek size");
static_assert(kPinFixedBytes == 104, "pin slot size");

// One slot as found by the structural parse.
struct Slot {
    uint16_t type;
    size_t body_off;
    size_t body_len;
    // PIN slots only.
    size_t wrapped_off;
    pv_envelope3_params params;
};

struct Layout {
    uint16_t slot_count;
    std::vector<Slot> slots;
    int first_pin;  // index into slots, -1 if none
};

bool params_ok(const pv_envelope3_params* p) {
    if (!p) {
        return false;
    }
    size_t alias_len = std::strlen(p->alias);
    if (alias_len == 0 || alias_len > PV_ENVELOPE_ALIAS_MAX) {
        return false;
    }
    return pin_params_in_range(p->opslimit, p->memlimit);
}

// Structural parse. Checks magic, version, provider, header, every slot
// header, and every PIN slot body. Does not check the KDF range.
int parse_layout(const uint8_t* buf, size_t len, Layout& out) {
    if (len < kOffSlotCount || std::memcmp(buf, kEnvMagic, sizeof(kEnvMagic)) != 0) {
        return PV_ERR_ENVELOPE_MALFORMED;
    }
    if (get_u16(buf + kOffVersion) != kEnvVersion ||
        get_u16(buf + kOffProvider) != PV_ENVELOPE_PROVIDER_PIN) {
        return PV_ERR_ENVELOPE_UNSUPPORTED;
    }
    if (len < kHeaderBytes || len > PV_ENVELOPE3_MAX_BYTES) {
        return PV_ERR_ENVELOPE_MALFORMED;
    }
    uint16_t slot_count = get_u16(buf + kOffSlotCount);
    if (slot_count == 0 || slot_count > PV_ENVELOPE3_MAX_SLOTS ||
        get_u16(buf + kOffReserved) != 0) {
        return PV_ERR_ENVELOPE_MALFORMED;
    }
    out.slot_count = slot_count;
    out.slots.clear();
    out.first_pin = -1;
    size_t pos = kHeaderBytes;
    for (uint16_t i = 0; i < slot_count; ++i) {
        if (len - pos < kSlotHeaderBytes) {
            return PV_ERR_ENVELOPE_MALFORMED;
        }
        Slot s{};
        s.type = get_u16(buf + pos);
        size_t body_len = get_u16(buf + pos + 2);
        pos += kSlotHeaderBytes;
        if (len - pos < body_len) {
            return PV_ERR_ENVELOPE_MALFORMED;
        }
        s.body_off = pos;
        s.body_len = body_len;
        if (s.type == PV_SLOT_PIN) {
            const uint8_t* b = buf + pos;
            if (body_len < 4) {
                return PV_ERR_ENVELOPE_MALFORMED;
            }
            size_t alias_len = get_u16(b + 2);
            if (alias_len == 0 || alias_len > PV_ENVELOPE_ALIAS_MAX ||
                body_len != kPinFixedBytes + alias_len) {
                return PV_ERR_ENVELOPE_MALFORMED;
            }
            for (size_t k = 0; k < alias_len; ++k) {
                if (b[4 + k] == 0) {
                    return PV_ERR_ENVELOPE_MALFORMED;
                }
            }
            s.params.mechanism = get_u16(b);
            std::memcpy(s.params.alias, b + 4, alias_len);
            s.params.alias[alias_len] = '\0';
            size_t q = 4 + alias_len;
            s.params.opslimit = get_u32(b + q);
            q += 4;
            s.params.memlimit = get_u64(b + q);
            q += 8;
            std::memcpy(s.params.salt, b + q, PV_PIN_SALT_BYTES);
            q += PV_PIN_SALT_BYTES + kNonceBytes;
            s.wrapped_off = pos + q;
            if (out.first_pin < 0) {
                out.first_pin = static_cast<int>(out.slots.size());
            }
        }
        out.slots.push_back(s);
        pos += body_len;
    }
    if (pos != len) {
        return PV_ERR_ENVELOPE_MALFORMED;
    }
    return PV_OK;
}

// The additional data: the envelope with every PIN slot wrapped field zeroed.
std::vector<uint8_t> masked_image(const uint8_t* buf, size_t len, const Layout& l) {
    std::vector<uint8_t> ad(buf, buf + len);
    for (const Slot& s : l.slots) {
        if (s.type == PV_SLOT_PIN) {
            std::memset(ad.data() + s.wrapped_off, 0, kWrappedBytes);
        }
    }
    return ad;
}

int fill_info(const uint8_t* buf, size_t len, pv_envelope3_info* info) {
    Layout l;
    int rc = parse_layout(buf, len, l);
    if (rc != PV_OK) {
        return rc;
    }
    if (l.first_pin < 0) {
        return PV_ERR_ENVELOPE_NO_SLOT;
    }
    const Slot& pin = l.slots[static_cast<size_t>(l.first_pin)];
    if (!pin_params_in_range(pin.params.opslimit, pin.params.memlimit)) {
        return PV_ERR_ENVELOPE_KDF_PARAMS;
    }
    if (info) {
        std::memset(info, 0, sizeof(*info));
        info->version = kEnvVersion;
        info->provider_id = PV_ENVELOPE_PROVIDER_PIN;
        info->slot_count = l.slot_count;
        info->pin_slot_index = static_cast<uint16_t>(l.first_pin);
        for (const Slot& s : l.slots) {
            if (s.type == PV_SLOT_PIN) {
                info->pin_slot_count++;
            } else {
                info->unknown_slot_count++;
            }
        }
        info->pin = pin.params;
    }
    return PV_OK;
}

int seal(const uint8_t* vmk, const pv_envelope3_params* p, const uint8_t* kek,
         std::vector<uint8_t>& out) {
    size_t alias_len = std::strlen(p->alias);
    size_t body_len = kPinFixedBytes + alias_len;
    out.assign(kHeaderBytes + kSlotHeaderBytes + body_len, 0);
    uint8_t* b = out.data();
    std::memcpy(b, kEnvMagic, sizeof(kEnvMagic));
    put_u16(b + kOffVersion, kEnvVersion);
    put_u16(b + kOffProvider, PV_ENVELOPE_PROVIDER_PIN);
    put_u16(b + kOffSlotCount, 1);
    put_u16(b + kOffReserved, 0);
    size_t pos = kHeaderBytes;
    put_u16(b + pos, PV_SLOT_PIN);
    put_u16(b + pos + 2, static_cast<uint16_t>(body_len));
    pos += kSlotHeaderBytes;
    put_u16(b + pos, p->mechanism);
    put_u16(b + pos + 2, static_cast<uint16_t>(alias_len));
    std::memcpy(b + pos + 4, p->alias, alias_len);
    size_t q = pos + 4 + alias_len;
    put_u32(b + q, p->opslimit);
    q += 4;
    put_u64(b + q, p->memlimit);
    q += 8;
    std::memcpy(b + q, p->salt, PV_PIN_SALT_BYTES);
    q += PV_PIN_SALT_BYTES;
    uint8_t* nonce = b + q;
    randombytes_buf(nonce, kNonceBytes);
    q += kNonceBytes;
    uint8_t* wrapped = b + q;
    // The wrapped field is still zero here, so out is already the masked image.
    unsigned long long clen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_encrypt(wrapped, &clen, vmk, PV_VMK_BYTES, b,
                                                   out.size(), nullptr, nonce, kek) != 0 ||
        clen != kWrappedBytes) {
        return PV_ERR_INTERNAL;
    }
    return PV_OK;
}

int open(const uint8_t* buf, size_t len, const uint8_t* kek, uint8_t* vmk_out) {
    Layout l;
    int rc = parse_layout(buf, len, l);
    if (rc != PV_OK) {
        return rc;
    }
    if (l.first_pin < 0) {
        return PV_ERR_ENVELOPE_NO_SLOT;
    }
    const Slot& pin = l.slots[static_cast<size_t>(l.first_pin)];
    if (!pin_params_in_range(pin.params.opslimit, pin.params.memlimit)) {
        return PV_ERR_ENVELOPE_KDF_PARAMS;
    }
    std::vector<uint8_t> ad = masked_image(buf, len, l);
    const uint8_t* nonce = buf + pin.wrapped_off - kNonceBytes;
    unsigned long long mlen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(vmk_out, &mlen, nullptr,
                                                   buf + pin.wrapped_off, kWrappedBytes,
                                                   ad.data(), ad.size(), nonce, kek) != 0) {
        sodium_memzero(vmk_out, PV_VMK_BYTES);
        return PV_ERR_ENVELOPE_UNWRAP_FAILED;
    }
    return mlen == PV_VMK_BYTES ? PV_OK : PV_ERR_INTERNAL;
}

// Reads vault.key into buf. A file longer than the maximum is malformed.
int read_key_file(const std::string& dir, std::vector<uint8_t>& buf) {
    buf.assign(PV_ENVELOPE3_MAX_BYTES + 1, 0);
    size_t got = 0;
    int rc = pv_file_read_all(fs::join(dir, kKeyFileName).c_str(), buf.data(), buf.size(), &got);
    if (rc == PV_ERR_IO && got == buf.size()) {
        return PV_ERR_ENVELOPE_MALFORMED;
    }
    if (rc != PV_OK) {
        return rc;
    }
    buf.resize(got);
    return PV_OK;
}

int remove_if_present(const std::string& path) {
    int rc = fs::remove_file(path);
    return rc == PV_ERR_NOT_FOUND ? PV_OK : rc;
}

// Writes the envelope bytes through vault.key.partial and a rename over
// vault.key.
int write_key_file(const std::string& dir, const std::vector<uint8_t>& env) {
    std::string partial = fs::join(dir, kKeyPartialName);
    std::string final_path = fs::join(dir, kKeyFileName);
    int rc = remove_if_present(partial);
    if (rc != PV_OK) {
        return rc;
    }
    pv_sink sink{};
    rc = pv_file_sink_create(partial.c_str(), &sink);
    if (rc != PV_OK) {
        return rc;
    }
    rc = write_all(&sink, env.data(), env.size());
    if (rc != PV_OK) {
        pv_file_sink_abort(&sink);
        return rc;
    }
    rc = pv_file_sink_commit(&sink);
    if (rc != PV_OK) {
        fs::remove_file(partial);
        return rc;
    }
    rc = fs::rename_replace(partial, final_path);
    if (rc != PV_OK) {
        fs::remove_file(partial);
    }
    return rc;
}

int vault_open_envelope3(const std::string& dir, const uint8_t* kek, pv_vault** out) {
    int rc = remove_if_present(fs::join(dir, kKeyPartialName));
    if (rc != PV_OK) {
        return rc;
    }
    std::vector<uint8_t> env;
    rc = read_key_file(dir, env);
    if (rc != PV_OK) {
        return rc;
    }
    WipedBuffer vmk(PV_VMK_BYTES);
    rc = open(env.data(), env.size(), kek, vmk.data());
    if (rc != PV_OK) {
        return rc;
    }
    return vault_open_raw(dir, vmk.data(), out);
}

int vault_create_envelope3(const std::string& dir, const pv_envelope3_params* p,
                           const uint8_t* kek, pv_vault** out) {
    int rc = pv_vault_create(dir.c_str());
    if (rc != PV_OK) {
        return rc;
    }
    bool present = false;
    rc = fs::exists(fs::join(dir, kKeyFileName), &present);
    if (rc != PV_OK) {
        return rc;
    }
    if (present) {
        return PV_ERR_DEST_EXISTS;
    }
    WipedBuffer vmk(PV_VMK_BYTES);
    randombytes_buf(vmk.data(), vmk.size());
    std::vector<uint8_t> env;
    rc = seal(vmk.data(), p, kek, env);
    if (rc != PV_OK) {
        return rc;
    }
    rc = write_key_file(dir, env);
    if (rc != PV_OK) {
        return rc;
    }
    return vault_open_raw(dir, vmk.data(), out);
}

int vault_write_envelope3(pv_vault* v, const pv_envelope3_params* p, const uint8_t* kek) {
    std::vector<uint8_t> env;
    int rc = seal(v->vmk, p, kek, env);
    if (rc != PV_OK) {
        return rc;
    }
    return write_key_file(v->dir, env);
}

int vault_destroy_files(const std::string& dir) {
    bool is_dir = false;
    int rc = fs::dir_exists(dir, &is_dir);
    if (rc != PV_OK) {
        return rc;
    }
    if (!is_dir) {
        return PV_OK;
    }
    std::string objects = fs::join(dir, kObjectsDirName);
    bool have_objects = false;
    fs::dir_exists(objects, &have_objects);
    if (have_objects) {
        std::vector<std::string> names;
        rc = fs::list_dir(objects, names);
        if (rc != PV_OK) {
            return rc;
        }
        for (const std::string& n : names) {
            rc = remove_if_present(fs::join(objects, n));
            if (rc != PV_OK) {
                return rc;
            }
        }
        rc = fs::remove_dir(objects);
        if (rc != PV_OK && rc != PV_ERR_NOT_FOUND) {
            return rc;
        }
    }
    rc = remove_if_present(fs::join(dir, kKeyFileName));
    if (rc != PV_OK) {
        return rc;
    }
    rc = remove_if_present(fs::join(dir, kKeyPartialName));
    if (rc != PV_OK) {
        return rc;
    }
    rc = fs::remove_dir(dir);
    return rc == PV_ERR_NOT_FOUND ? PV_OK : rc;
}

}  // namespace
}  // namespace pv

extern "C" {

int pv_envelope3_parse(const uint8_t* buf, size_t len, pv_envelope3_info* out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!buf) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::fill_info(buf, len, out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_envelope3_seal(const uint8_t* vmk, size_t vmk_len, const pv_envelope3_params* p,
                      const uint8_t* kek, size_t kek_len, uint8_t* out, size_t out_cap,
                      size_t* out_len) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!vmk || vmk_len != PV_VMK_BYTES || !pv::params_ok(p) || !kek || kek_len != PV_KEK_BYTES ||
        !out || !out_len) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        std::vector<uint8_t> env;
        rc = pv::seal(vmk, p, kek, env);
        if (rc != PV_OK) {
            return rc;
        }
        if (env.size() > out_cap) {
            return PV_ERR_INVALID_ARGUMENT;
        }
        std::memcpy(out, env.data(), env.size());
        *out_len = env.size();
        return PV_OK;
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_envelope3_open(const uint8_t* buf, size_t len, const uint8_t* kek, size_t kek_len,
                      uint8_t* vmk_out, size_t vmk_out_len) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!buf || !kek || kek_len != PV_KEK_BYTES || !vmk_out || vmk_out_len != PV_VMK_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::open(buf, len, kek, vmk_out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_create_envelope3(const char* vault_dir, const pv_envelope3_params* p,
                              const uint8_t* kek, size_t kek_len, pv_vault** out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!vault_dir || !*vault_dir || !pv::params_ok(p) || !kek || kek_len != PV_KEK_BYTES ||
        !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    *out = nullptr;
    try {
        return pv::vault_create_envelope3(vault_dir, p, kek, out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_open_envelope3(const char* vault_dir, const uint8_t* kek, size_t kek_len,
                            pv_vault** out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!vault_dir || !*vault_dir || !kek || kek_len != PV_KEK_BYTES || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    *out = nullptr;
    try {
        return pv::vault_open_envelope3(vault_dir, kek, out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_write_envelope3(pv_vault* v, const pv_envelope3_params* p, const uint8_t* kek,
                             size_t kek_len) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!v || !v->vmk || !pv::params_ok(p) || !kek || kek_len != PV_KEK_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::vault_write_envelope3(v, p, kek);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_destroy_files(const char* vault_dir) {
    if (!vault_dir || !*vault_dir) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::vault_destroy_files(vault_dir);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

}
