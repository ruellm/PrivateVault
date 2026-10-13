// Desktop password envelope: seal and open, wrong password, every tampered
// field, KDF parameter bounds, unsupported version and provider, truncation,
// and the file form.
#include "envelope.h"
#include "test_support.h"

#include <cstdio>

namespace {

const char* kPassword = "correct horse battery staple";
const char* kWrong = "correct horse battery stable";

ts::Bytes seal(const uint8_t* vmk, const char* pw) {
    ts::Bytes out(pvd::kEnvelopeBytes);
    CHECK_EQ(pvd::envelope_seal(vmk, PV_VMK_BYTES, pw, std::strlen(pw), pvd::kdf_interactive(),
                                out.data(), out.size()), PV_OK);
    return out;
}

int open_env(const ts::Bytes& env, const char* pw, ts::Bytes& vmk_out) {
    vmk_out.assign(PV_VMK_BYTES, 0);
    return pvd::envelope_open(env.data(), env.size(), pw, std::strlen(pw), vmk_out.data(),
                              vmk_out.size());
}

void expect(const ts::Bytes& env, int code, const char* what) {
    ts::Bytes vmk;
    int rc = open_env(env, kPassword, vmk);
    if (rc != code) {
        std::fprintf(stderr, "%s: expected %s, got %s\n", what, pv_error_name(code),
                     pv_error_name(rc));
        std::exit(1);
    }
    CHECK(std::memcmp(vmk.data(), ts::test_vmk(), PV_VMK_BYTES) != 0);
}

void put_u32(ts::Bytes& b, size_t off, uint32_t v) {
    for (int i = 0; i < 4; ++i) b[off + i] = static_cast<uint8_t>(v >> (8 * i));
}
void put_u64(ts::Bytes& b, size_t off, uint64_t v) {
    for (int i = 0; i < 8; ++i) b[off + i] = static_cast<uint8_t>(v >> (8 * i));
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    ts::Bytes env = seal(ts::test_vmk(), kPassword);
    CHECK_EQ(env.size(), 112u);
    CHECK_EQ(std::memcmp(env.data(), "PVKEYENV", 8), 0);

    ts::Bytes vmk;
    CHECK_EQ(open_env(env, kPassword, vmk), PV_OK);
    CHECK_EQ(std::memcmp(vmk.data(), ts::test_vmk(), PV_VMK_BYTES), 0);

    // Wrong password and empty password.
    {
        ts::Bytes v2;
        CHECK_EQ(open_env(env, kWrong, v2), PV_ERR_ENVELOPE_UNWRAP_FAILED);
        CHECK_EQ(open_env(env, "", v2), PV_ERR_ENVELOPE_UNWRAP_FAILED);
    }

    // Two seals of the same key differ (fresh salt and nonce) and both open.
    ts::Bytes env2 = seal(ts::test_vmk(), kPassword);
    CHECK(env != env2);
    CHECK_EQ(open_env(env2, kPassword, vmk), PV_OK);

    auto flipped = [&](size_t off) {
        ts::Bytes e = env;
        e[off] ^= 1;
        return e;
    };
    expect(flipped(0), PV_ERR_ENVELOPE_MALFORMED, "magic");
    expect(flipped(8), PV_ERR_ENVELOPE_UNSUPPORTED, "version");
    expect(flipped(10), PV_ERR_ENVELOPE_UNSUPPORTED, "provider");
    {
        ts::Bytes e = env;
        e[10] = 2;  // Android provider id
        expect(e, PV_ERR_ENVELOPE_UNSUPPORTED, "android provider");
    }
    expect(flipped(24), PV_ERR_ENVELOPE_UNWRAP_FAILED, "salt");
    expect(flipped(40), PV_ERR_ENVELOPE_UNWRAP_FAILED, "nonce");
    expect(flipped(64), PV_ERR_ENVELOPE_UNWRAP_FAILED, "wrapped vmk body");
    expect(flipped(111), PV_ERR_ENVELOPE_UNWRAP_FAILED, "wrapped vmk tag");

    // KDF parameters: changing them within range breaks the AD binding;
    // outside the range they are rejected before the KDF runs.
    {
        ts::Bytes e = env;
        put_u32(e, 12, crypto_pwhash_OPSLIMIT_MODERATE);
        expect(e, PV_ERR_ENVELOPE_UNWRAP_FAILED, "opslimit changed in range");
        e = env;
        put_u32(e, 12, 1);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "opslimit below min");
        e = env;
        put_u32(e, 12, 17);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "opslimit above max");
        e = env;
        put_u32(e, 12, 0xffffffffu);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "opslimit huge");
        e = env;
        put_u64(e, 16, crypto_pwhash_MEMLIMIT_INTERACTIVE - 1);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "memlimit below min");
        e = env;
        put_u64(e, 16, static_cast<uint64_t>(crypto_pwhash_MEMLIMIT_SENSITIVE) + 1);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "memlimit above max");
        e = env;
        put_u64(e, 16, 0xffffffffffffffffull);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "memlimit huge");
        e = env;
        put_u64(e, 16, 8192);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "memlimit libsodium minimum but below ours");
    }

    // Length problems.
    for (size_t n : {size_t(0), size_t(7), size_t(11), size_t(12), size_t(64), size_t(111)}) {
        ts::Bytes e = env;
        e.resize(n);
        int code = n < 12 ? PV_ERR_ENVELOPE_MALFORMED : PV_ERR_ENVELOPE_MALFORMED;
        expect(e, code, "truncated envelope");
    }
    {
        ts::Bytes e = env;
        e.push_back(0);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "trailing byte");
    }

    // Seal rejects out-of-range parameters and bad arguments.
    {
        ts::Bytes out(pvd::kEnvelopeBytes);
        pvd::KdfParams bad{1, crypto_pwhash_MEMLIMIT_INTERACTIVE};
        CHECK_EQ(pvd::envelope_seal(ts::test_vmk(), PV_VMK_BYTES, kPassword, 5, bad, out.data(),
                                    out.size()), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pvd::envelope_seal(ts::test_vmk(), 16, kPassword, 5, pvd::kdf_interactive(),
                                    out.data(), out.size()), PV_ERR_INVALID_ARGUMENT);
        CHECK(pvd::kdf_params_in_range(pvd::kdf_moderate()));
        CHECK(pvd::kdf_params_in_range(pvd::kdf_interactive()));
    }

    // File form: atomic write, refusal to overwrite, read back.
    const std::string dir = "env_files";
    ts::remove_tree(dir);
    CHECK_EQ(pv_vault_create(dir.c_str()), PV_OK);
    const std::string path = dir + "/vault.key";
    CHECK_EQ(pvd::envelope_write_file(path, ts::test_vmk(), PV_VMK_BYTES, kPassword,
                                      std::strlen(kPassword), pvd::kdf_interactive()), PV_OK);
    CHECK(!ts::path_exists(path + ".partial"));
    CHECK_EQ(ts::read_file(path).size(), 112u);
    CHECK_EQ(pvd::envelope_write_file(path, ts::test_vmk(), PV_VMK_BYTES, kPassword,
                                      std::strlen(kPassword), pvd::kdf_interactive()),
             PV_ERR_DEST_EXISTS);
    CHECK_EQ(pvd::envelope_read_file(path, kPassword, std::strlen(kPassword), vmk.data(),
                                     vmk.size()), PV_OK);
    CHECK_EQ(std::memcmp(vmk.data(), ts::test_vmk(), PV_VMK_BYTES), 0);
    CHECK_EQ(pvd::envelope_read_file(path, kWrong, std::strlen(kWrong), vmk.data(), vmk.size()),
             PV_ERR_ENVELOPE_UNWRAP_FAILED);
    CHECK_EQ(pvd::envelope_read_file(dir + "/missing.key", kPassword, std::strlen(kPassword),
                                     vmk.data(), vmk.size()), PV_ERR_NOT_FOUND);
    ts::Bytes big(200, 1);
    ts::write_file(dir + "/big.key", big);
    CHECK_EQ(pvd::envelope_read_file(dir + "/big.key", kPassword, std::strlen(kPassword),
                                     vmk.data(), vmk.size()), PV_ERR_ENVELOPE_MALFORMED);

    std::printf("envelope ok\n");
    return 0;
}
