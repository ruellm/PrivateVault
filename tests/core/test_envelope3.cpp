// Provider 3 envelope: seal and open, parse, wrong KEK, every tampered field,
// slot rules (unknown alone, unknown beside, two PIN slots, bad lengths),
// truncation, size limit, the vault forms (create, open, write, stale
// partial, failed write keeps the old envelope), and destroy_files.
#include "envelope3_builder.h"
#include "test_support.h"

#include <cstdio>

namespace {

const uint8_t* kek1() {
    static const char* s = "PRIVATEVAULT TEST ONLY KEK  0001";
    return reinterpret_cast<const uint8_t*>(s);
}
ts::Bytes kek2() { return ts::Bytes(PV_KEK_BYTES, 0x3c); }
const uint8_t* salt() {
    static const char* s = "PVTESTONLYSALT01";
    return reinterpret_cast<const uint8_t*>(s);
}

pv_envelope3_params params(const char* alias = "privatevault.pin.testonly",
                           uint32_t ops = PV_PIN_OPSLIMIT_MIN,
                           uint64_t mem = PV_PIN_MEMLIMIT_MIN) {
    pv_envelope3_params p{};
    p.mechanism = PV_MECHANISM_ANDROID_KEYSTORE_HMAC;
    std::snprintf(p.alias, sizeof(p.alias), "%s", alias);
    p.opslimit = ops;
    p.memlimit = mem;
    std::memcpy(p.salt, salt(), PV_PIN_SALT_BYTES);
    return p;
}

ts::Bytes seal(const pv_envelope3_params& p, const uint8_t* kek = kek1()) {
    ts::Bytes out(PV_ENVELOPE3_MAX_BYTES);
    size_t n = 0;
    CHECK_EQ(pv_envelope3_seal(ts::test_vmk(), PV_VMK_BYTES, &p, kek, PV_KEK_BYTES, out.data(),
                               out.size(), &n), PV_OK);
    out.resize(n);
    return out;
}

int open_env(const ts::Bytes& env, const uint8_t* kek, ts::Bytes& vmk) {
    vmk.assign(PV_VMK_BYTES, 0);
    return pv_envelope3_open(env.data(), env.size(), kek, PV_KEK_BYTES, vmk.data(), vmk.size());
}

void expect(const ts::Bytes& env, int code, const char* what) {
    ts::Bytes vmk;
    int rc = open_env(env, kek1(), vmk);
    if (rc != code) {
        std::fprintf(stderr, "%s: expected %s, got %s\n", what, pv_error_name(code),
                     pv_error_name(rc));
        std::exit(1);
    }
    CHECK(std::memcmp(vmk.data(), ts::test_vmk(), PV_VMK_BYTES) != 0);
    // parse agrees with open for everything it can see.
    pv_envelope3_info info{};
    int prc = pv_envelope3_parse(env.data(), env.size(), &info);
    if (code == PV_ERR_ENVELOPE_UNWRAP_FAILED) {
        CHECK_EQ(prc, PV_OK);
    } else {
        CHECK_EQ(prc, code);
    }
}

void put16(ts::Bytes& b, size_t off, uint16_t v) {
    b[off] = static_cast<uint8_t>(v & 0xff);
    b[off + 1] = static_cast<uint8_t>(v >> 8);
}
void put32(ts::Bytes& b, size_t off, uint32_t v) {
    for (int i = 0; i < 4; ++i) b[off + i] = static_cast<uint8_t>(v >> (8 * i));
}
void put64(ts::Bytes& b, size_t off, uint64_t v) {
    for (int i = 0; i < 8; ++i) b[off + i] = static_cast<uint8_t>(v >> (8 * i));
}

// Offsets inside a one-slot envelope with an alias of n bytes.
struct Offsets {
    size_t slot_type = 16, slot_len = 18, mechanism = 20, alias_len = 22, alias = 24;
    size_t opslimit, memlimit, salt, nonce, wrapped, end;
    explicit Offsets(size_t alias_bytes) {
        opslimit = alias + alias_bytes;
        memlimit = opslimit + 4;
        salt = memlimit + 8;
        nonce = salt + 16;
        wrapped = nonce + 24;
        end = wrapped + 48;
    }
};

void vault_forms();
void destroy_files();

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    const pv_envelope3_params p = params();
    const size_t alias_bytes = std::strlen(p.alias);
    const Offsets off(alias_bytes);

    ts::Bytes env = seal(p);
    CHECK_EQ(env.size(), off.end);
    CHECK_EQ(std::memcmp(env.data(), "PVKEYENV", 8), 0);
    CHECK_EQ(env[10], 3);

    // Parse reports the fields without running anything.
    pv_envelope3_info info{};
    CHECK_EQ(pv_envelope3_parse(env.data(), env.size(), &info), PV_OK);
    CHECK_EQ(info.version, 1);
    CHECK_EQ(info.provider_id, 3);
    CHECK_EQ(info.slot_count, 1);
    CHECK_EQ(info.pin_slot_count, 1);
    CHECK_EQ(info.unknown_slot_count, 0);
    CHECK_EQ(info.pin_slot_index, 0);
    CHECK_EQ(info.pin.mechanism, PV_MECHANISM_ANDROID_KEYSTORE_HMAC);
    CHECK_EQ(std::string(info.pin.alias), std::string(p.alias));
    CHECK_EQ(info.pin.opslimit, p.opslimit);
    CHECK_EQ(info.pin.memlimit, p.memlimit);
    CHECK_EQ(std::memcmp(info.pin.salt, p.salt, PV_PIN_SALT_BYTES), 0);
    CHECK_EQ(pv_envelope3_parse(env.data(), env.size(), nullptr), PV_OK);

    // Round trip, wrong KEK, fresh nonce per seal.
    ts::Bytes vmk;
    CHECK_EQ(open_env(env, kek1(), vmk), PV_OK);
    CHECK_EQ(std::memcmp(vmk.data(), ts::test_vmk(), PV_VMK_BYTES), 0);
    CHECK_EQ(open_env(env, kek2().data(), vmk), PV_ERR_ENVELOPE_UNWRAP_FAILED);
    CHECK(vmk == ts::Bytes(PV_VMK_BYTES, 0));
    ts::Bytes env2 = seal(p);
    CHECK(env != env2);
    CHECK_EQ(open_env(env2, kek1(), vmk), PV_OK);

    // The builder reproduces the layout: same bytes apart from nonce and wrapped field.
    {
        e3::Bytes body = e3::pin_body(p.mechanism, p.alias, p.opslimit, p.memlimit, p.salt,
                                      env.data() + off.nonce);
        e3::Bytes built = e3::build(1, 3, 1, 0, {{PV_SLOT_PIN, body}}, ts::test_vmk(), {kek1()});
        CHECK(built == env);
    }

    // Every field, one at a time.
    auto flipped = [&](size_t o) {
        ts::Bytes e = env;
        e[o] ^= 1;
        return e;
    };
    expect(flipped(0), PV_ERR_ENVELOPE_MALFORMED, "magic");
    expect(flipped(8), PV_ERR_ENVELOPE_UNSUPPORTED, "version");
    expect(flipped(10), PV_ERR_ENVELOPE_UNSUPPORTED, "provider");
    {
        ts::Bytes e = env;
        e[10] = 1;
        expect(e, PV_ERR_ENVELOPE_UNSUPPORTED, "provider 1");
        e[10] = 2;
        expect(e, PV_ERR_ENVELOPE_UNSUPPORTED, "provider 2");
        e = env;
        put16(e, 12, 0);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "slot_count 0");
        put16(e, 12, 9);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "slot_count 9");
        put16(e, 12, 2);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "slot_count 2 with one slot");
        e = env;
        put16(e, 14, 1);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "reserved");
        e = env;
        put16(e, off.slot_type, 7);
        expect(e, PV_ERR_ENVELOPE_NO_SLOT, "only slot unknown");
        put16(e, off.slot_type, PV_SLOT_BIOMETRIC);
        expect(e, PV_ERR_ENVELOPE_NO_SLOT, "only slot biometric");
        e = env;
        put16(e, off.slot_len, static_cast<uint16_t>(104 + alias_bytes + 1));
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "slot_len too long");
        put16(e, off.slot_len, static_cast<uint16_t>(104 + alias_bytes - 1));
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "slot_len too short");
        e = env;
        put16(e, off.alias_len, static_cast<uint16_t>(alias_bytes + 1));
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "alias_len mismatch");
        e = env;
        e[off.alias] = 0;
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "NUL in alias");
    }
    expect(flipped(off.mechanism), PV_ERR_ENVELOPE_UNWRAP_FAILED, "mechanism");
    expect(flipped(off.alias + 1), PV_ERR_ENVELOPE_UNWRAP_FAILED, "alias byte");
    expect(flipped(off.salt), PV_ERR_ENVELOPE_UNWRAP_FAILED, "salt");
    expect(flipped(off.nonce), PV_ERR_ENVELOPE_UNWRAP_FAILED, "nonce");
    expect(flipped(off.wrapped), PV_ERR_ENVELOPE_UNWRAP_FAILED, "wrapped body");
    expect(flipped(off.end - 1), PV_ERR_ENVELOPE_UNWRAP_FAILED, "wrapped tag");
    {
        ts::Bytes e = env;
        put32(e, off.opslimit, PV_PIN_OPSLIMIT_MAX);
        expect(e, PV_ERR_ENVELOPE_UNWRAP_FAILED, "opslimit changed in range");
        put32(e, off.opslimit, PV_PIN_OPSLIMIT_MIN - 1);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "opslimit below min");
        put32(e, off.opslimit, PV_PIN_OPSLIMIT_MAX + 1);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "opslimit above max");
        put32(e, off.opslimit, 0xffffffffu);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "opslimit huge");
        e = env;
        put64(e, off.memlimit, PV_PIN_MEMLIMIT_MAX);
        expect(e, PV_ERR_ENVELOPE_UNWRAP_FAILED, "memlimit changed in range");
        put64(e, off.memlimit, PV_PIN_MEMLIMIT_MIN - 1);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "memlimit below min");
        put64(e, off.memlimit, static_cast<uint64_t>(PV_PIN_MEMLIMIT_MAX) + 1);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "memlimit above max");
        put64(e, off.memlimit, 0xffffffffffffffffull);
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "memlimit huge");
    }

    // Lengths: every truncation, a trailing byte, and the size limit.
    for (size_t n = 0; n < env.size(); ++n) {
        ts::Bytes e = env;
        e.resize(n);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "truncated envelope");
    }
    {
        ts::Bytes e = env;
        e.push_back(0);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "trailing byte");
        e = env;
        e.resize(PV_ENVELOPE3_MAX_BYTES + 1, 0);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "over the size limit");
    }

    // Slot rules with hand-built envelopes.
    {
        e3::Bytes body = e3::pin_body(p.mechanism, p.alias, p.opslimit, p.memlimit, p.salt, nullptr);
        e3::Bytes other_body(20, 0xaa);
        // Unknown slot alone.
        ts::Bytes e = e3::build(1, 3, 1, 0, {{7, other_body}}, ts::test_vmk(), {});
        expect(e, PV_ERR_ENVELOPE_NO_SLOT, "unknown alone");
        // Unknown before and after a PIN slot: accepted, counted, bound.
        e = e3::build(1, 3, 2, 0, {{7, other_body}, {PV_SLOT_PIN, body}}, ts::test_vmk(), {kek1()});
        CHECK_EQ(open_env(e, kek1(), vmk), PV_OK);
        CHECK_EQ(std::memcmp(vmk.data(), ts::test_vmk(), PV_VMK_BYTES), 0);
        CHECK_EQ(pv_envelope3_parse(e.data(), e.size(), &info), PV_OK);
        CHECK_EQ(info.slot_count, 2);
        CHECK_EQ(info.pin_slot_count, 1);
        CHECK_EQ(info.unknown_slot_count, 1);
        CHECK_EQ(info.pin_slot_index, 1);
        e[16 + 4 + 3] ^= 1;  // a byte inside the unknown slot
        expect(e, PV_ERR_ENVELOPE_UNWRAP_FAILED, "unknown slot byte");
        e = e3::build(1, 3, 2, 0, {{PV_SLOT_PIN, body}, {PV_SLOT_BIOMETRIC, other_body}},
                      ts::test_vmk(), {kek1()});
        CHECK_EQ(open_env(e, kek1(), vmk), PV_OK);
        CHECK_EQ(pv_envelope3_parse(e.data(), e.size(), &info), PV_OK);
        CHECK_EQ(info.pin_slot_index, 0);
        // Removing the trailing slot (count and bytes) is detected.
        ts::Bytes shorter = e3::build(1, 3, 1, 0, {{PV_SLOT_PIN, body}}, ts::test_vmk(), {kek1()});
        ts::Bytes cut = e;
        cut.resize(shorter.size());
        put16(cut, 12, 1);
        expect(cut, PV_ERR_ENVELOPE_UNWRAP_FAILED, "trailing slot removed");
        // An empty unknown slot is fine.
        e = e3::build(1, 3, 2, 0, {{PV_SLOT_PIN, body}, {9, {}}}, ts::test_vmk(), {kek1()});
        CHECK_EQ(open_env(e, kek1(), vmk), PV_OK);
        // Two PIN slots under different keys: the first one is used.
        ts::Bytes k2 = kek2();
        e = e3::build(1, 3, 2, 0, {{PV_SLOT_PIN, body}, {PV_SLOT_PIN, body}}, ts::test_vmk(),
                      {kek1(), k2.data()});
        CHECK_EQ(open_env(e, kek1(), vmk), PV_OK);
        CHECK_EQ(open_env(e, k2.data(), vmk), PV_ERR_ENVELOPE_UNWRAP_FAILED);
        CHECK_EQ(pv_envelope3_parse(e.data(), e.size(), &info), PV_OK);
        CHECK_EQ(info.pin_slot_count, 2);
        // Eight slots are the limit; nine are not.
        std::vector<e3::Slot> many(8, e3::Slot{7, other_body});
        many[3] = {PV_SLOT_PIN, body};
        e = e3::build(1, 3, 8, 0, many, ts::test_vmk(), {kek1()});
        CHECK_EQ(open_env(e, kek1(), vmk), PV_OK);
        many.push_back({7, other_body});
        e = e3::build(1, 3, 9, 0, many, ts::test_vmk(), {kek1()});
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "nine slots");
        // PIN slot bodies of the wrong shape.
        e3::Bytes short_body(body.begin(), body.begin() + 3);
        e = e3::build(1, 3, 1, 0, {{PV_SLOT_PIN, short_body}}, ts::test_vmk(), {});
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "pin body of 3 bytes");
        e3::Bytes zero_alias = e3::pin_body(1, "", p.opslimit, p.memlimit, p.salt, nullptr);
        e = e3::build(1, 3, 1, 0, {{PV_SLOT_PIN, zero_alias}}, ts::test_vmk(), {kek1()});
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "alias_len 0");
        e3::Bytes long_alias = e3::pin_body(1, std::string(65, 'a'), p.opslimit, p.memlimit,
                                            p.salt, nullptr);
        e = e3::build(1, 3, 1, 0, {{PV_SLOT_PIN, long_alias}}, ts::test_vmk(), {kek1()});
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "alias_len 65");
        e3::Bytes max_alias = e3::pin_body(1, std::string(64, 'a'), p.opslimit, p.memlimit,
                                           p.salt, nullptr);
        e = e3::build(1, 3, 1, 0, {{PV_SLOT_PIN, max_alias}}, ts::test_vmk(), {kek1()});
        CHECK_EQ(open_env(e, kek1(), vmk), PV_OK);
        // A slot whose length points past the end.
        e = env;
        put16(e, off.slot_len, 0xffff);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "slot_len past the end");
        // Structure is checked before the KDF range.
        e3::Bytes bad_kdf = e3::pin_body(1, p.alias, 1, p.memlimit, p.salt, nullptr);
        e = e3::build(1, 3, 1, 0, {{PV_SLOT_PIN, bad_kdf}}, ts::test_vmk(), {kek1()});
        expect(e, PV_ERR_ENVELOPE_KDF_PARAMS, "bad kdf, good structure");
        e.push_back(0);
        expect(e, PV_ERR_ENVELOPE_MALFORMED, "bad kdf, bad structure");
    }

    // seal and open arguments.
    {
        ts::Bytes out(PV_ENVELOPE3_MAX_BYTES);
        size_t n = 0;
        pv_envelope3_params bad = params("");
        CHECK_EQ(pv_envelope3_seal(ts::test_vmk(), PV_VMK_BYTES, &bad, kek1(), PV_KEK_BYTES,
                                   out.data(), out.size(), &n), PV_ERR_INVALID_ARGUMENT);
        // A 64-byte alias is the longest the struct can hold and is accepted.
        bad = params(std::string(64, 'b').c_str());
        CHECK_EQ(pv_envelope3_seal(ts::test_vmk(), PV_VMK_BYTES, &bad, kek1(), PV_KEK_BYTES,
                                   out.data(), out.size(), &n), PV_OK);
        bad = params("x", 1);
        CHECK_EQ(pv_envelope3_seal(ts::test_vmk(), PV_VMK_BYTES, &bad, kek1(), PV_KEK_BYTES,
                                   out.data(), out.size(), &n), PV_ERR_INVALID_ARGUMENT);
        bad = params("x", 2, 1 << 20);
        CHECK_EQ(pv_envelope3_seal(ts::test_vmk(), PV_VMK_BYTES, &bad, kek1(), PV_KEK_BYTES,
                                   out.data(), out.size(), &n), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_envelope3_seal(ts::test_vmk(), 16, &p, kek1(), PV_KEK_BYTES, out.data(),
                                   out.size(), &n), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_envelope3_seal(ts::test_vmk(), PV_VMK_BYTES, &p, kek1(), 16, out.data(),
                                   out.size(), &n), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_envelope3_seal(ts::test_vmk(), PV_VMK_BYTES, &p, kek1(), PV_KEK_BYTES,
                                   out.data(), env.size() - 1, &n), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_envelope3_seal(ts::test_vmk(), PV_VMK_BYTES, nullptr, kek1(), PV_KEK_BYTES,
                                   out.data(), out.size(), &n), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_envelope3_open(env.data(), env.size(), kek1(), 31, vmk.data(), vmk.size()),
                 PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_envelope3_open(env.data(), env.size(), kek1(), 32, vmk.data(), 31),
                 PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_envelope3_open(nullptr, 0, kek1(), 32, vmk.data(), 32), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_envelope3_parse(nullptr, 0, &info), PV_ERR_INVALID_ARGUMENT);
    }

    vault_forms();
    destroy_files();
    CHECK_EQ(std::string(pv_error_name(PV_ERR_ENVELOPE_NO_SLOT)), "PV_ERR_ENVELOPE_NO_SLOT");
    std::printf("envelope3 ok\n");
    return 0;
}

namespace {

ts::Bytes det(uint64_t n) { return ts::det_bytes(n); }

void vault_forms() {
    const std::string dir = "env3_vault";
    ts::remove_tree(dir);
    pv_envelope3_params p = params();
    pv_vault* v = nullptr;
    ts::Bytes k2 = kek2();

    // Open before create.
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), kek1(), PV_KEK_BYTES, &v), PV_ERR_NOT_FOUND);
    CHECK(v == nullptr);

    // Create: directories, envelope, open handle; a second create is refused.
    CHECK_EQ(pv_vault_create_envelope3(dir.c_str(), &p, kek1(), PV_KEK_BYTES, &v), PV_OK);
    CHECK(v != nullptr);
    CHECK(ts::path_exists(dir + "/objects"));
    CHECK(ts::path_exists(dir + "/vault.key"));
    CHECK(!ts::path_exists(dir + "/vault.key.partial"));
    ts::Bytes env1 = ts::read_file(dir + "/vault.key");
    pv_envelope3_info info{};
    CHECK_EQ(pv_envelope3_parse(env1.data(), env1.size(), &info), PV_OK);
    CHECK_EQ(std::string(info.pin.alias), std::string(p.alias));
    pv_vault* again = nullptr;
    CHECK_EQ(pv_vault_create_envelope3(dir.c_str(), &p, kek1(), PV_KEK_BYTES, &again),
             PV_ERR_DEST_EXISTS);
    CHECK(again == nullptr);

    // An object made with the created handle.
    ts::Bytes plain = det(10000);
    ts::MemSource src{&plain};
    pv_source s = src.as_source();
    uint8_t id[PV_OBJECT_ID_BYTES];
    CHECK_EQ(pv_vault_encrypt(v, &s, 0, id), PV_OK);
    pv_vault_close(v);
    v = nullptr;

    // Open with the KEK, verify; wrong KEK fails and leaves no handle.
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), k2.data(), PV_KEK_BYTES, &v),
             PV_ERR_ENVELOPE_UNWRAP_FAILED);
    CHECK(v == nullptr);
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), kek1(), PV_KEK_BYTES, &v), PV_OK);
    uint64_t len = 0;
    CHECK_EQ(pv_vault_verify(v, id, &len), PV_OK);
    CHECK_EQ(len, 10000u);

    // Rewrap under another KEK and alias: the file changes, the VMK does not.
    pv_envelope3_params p2 = params("privatevault.pin.second", PV_PIN_OPSLIMIT_MAX);
    p2.salt[0] ^= 0xff;
    CHECK_EQ(pv_vault_write_envelope3(v, &p2, k2.data(), PV_KEK_BYTES), PV_OK);
    CHECK(!ts::path_exists(dir + "/vault.key.partial"));
    ts::Bytes env2 = ts::read_file(dir + "/vault.key");
    CHECK(env2 != env1);
    CHECK_EQ(pv_envelope3_parse(env2.data(), env2.size(), &info), PV_OK);
    CHECK_EQ(std::string(info.pin.alias), "privatevault.pin.second");
    CHECK_EQ(info.pin.opslimit, PV_PIN_OPSLIMIT_MAX);
    // The handle in use keeps working; the object is unchanged.
    CHECK_EQ(pv_vault_verify(v, id, &len), PV_OK);
    pv_vault_close(v);
    v = nullptr;
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), kek1(), PV_KEK_BYTES, &v),
             PV_ERR_ENVELOPE_UNWRAP_FAILED);
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), k2.data(), PV_KEK_BYTES, &v), PV_OK);
    CHECK_EQ(pv_vault_verify(v, id, &len), PV_OK);
    // The old envelope bytes, put back, still open with the old KEK and the same VMK.
    ts::write_file(dir + "/vault.key", env1);
    pv_vault* old = nullptr;
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), kek1(), PV_KEK_BYTES, &old), PV_OK);
    CHECK_EQ(pv_vault_verify(old, id, &len), PV_OK);
    pv_vault_close(old);
    ts::write_file(dir + "/vault.key", env2);

    // A stale partial is removed by open and by write.
    ts::write_file(dir + "/vault.key.partial", ts::Bytes(5, 1));
    pv_vault* v2 = nullptr;
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), k2.data(), PV_KEK_BYTES, &v2), PV_OK);
    CHECK(!ts::path_exists(dir + "/vault.key.partial"));
    pv_vault_close(v2);
    ts::write_file(dir + "/vault.key.partial", ts::Bytes(5, 1));
    CHECK_EQ(pv_vault_write_envelope3(v, &p2, k2.data(), PV_KEK_BYTES), PV_OK);
    CHECK(!ts::path_exists(dir + "/vault.key.partial"));
    CHECK(ts::read_file(dir + "/vault.key") != env2);  // fresh nonce

    // A write that cannot create its partial file leaves the old envelope.
    ts::Bytes before = ts::read_file(dir + "/vault.key");
    CHECK_EQ(pv_vault_create((dir + "/vault.key.partial").c_str()), PV_OK);  // a directory in the way
    int rc = pv_vault_write_envelope3(v, &p2, k2.data(), PV_KEK_BYTES);
    CHECK(rc == PV_ERR_IO || rc == PV_ERR_DEST_EXISTS);
    CHECK(ts::read_file(dir + "/vault.key") == before);
    ts::remove_tree(dir + "/vault.key.partial");
    pv_vault_close(v);
    v = nullptr;
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), k2.data(), PV_KEK_BYTES, &v), PV_OK);
    CHECK_EQ(pv_vault_verify(v, id, &len), PV_OK);

    // Arguments on the vault forms.
    CHECK_EQ(pv_vault_write_envelope3(nullptr, &p2, k2.data(), PV_KEK_BYTES), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_vault_write_envelope3(v, &p2, k2.data(), 16), PV_ERR_INVALID_ARGUMENT);
    pv_envelope3_params bad = params("");
    CHECK_EQ(pv_vault_write_envelope3(v, &bad, k2.data(), PV_KEK_BYTES), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_vault_create_envelope3("", &p, kek1(), PV_KEK_BYTES, &again), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), kek1(), 16, &again), PV_ERR_INVALID_ARGUMENT);
    pv_vault_close(v);

    // A key file that is too long, a provider 2 file, and a missing file.
    ts::write_file(dir + "/vault.key", ts::Bytes(PV_ENVELOPE3_MAX_BYTES + 1, 0));
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), k2.data(), PV_KEK_BYTES, &v), PV_ERR_ENVELOPE_MALFORMED);
    ts::Bytes p2env = env2;
    p2env[10] = 2;
    ts::write_file(dir + "/vault.key", p2env);
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), k2.data(), PV_KEK_BYTES, &v), PV_ERR_ENVELOPE_UNSUPPORTED);
    CHECK_EQ(pv_file_delete((dir + "/vault.key").c_str()), PV_OK);
    CHECK_EQ(pv_vault_open_envelope3(dir.c_str(), k2.data(), PV_KEK_BYTES, &v), PV_ERR_NOT_FOUND);
    // Create refuses when the directory already holds a vault.key, even a bad one.
    ts::write_file(dir + "/vault.key", ts::Bytes(3, 0));
    CHECK_EQ(pv_vault_create_envelope3(dir.c_str(), &p, kek1(), PV_KEK_BYTES, &v), PV_ERR_DEST_EXISTS);
    ts::remove_tree(dir);
}

void destroy_files() {
    const std::string dir = "env3_destroy";
    ts::remove_tree(dir);
    // Missing directory: nothing to do.
    CHECK_EQ(pv_vault_destroy_files(dir.c_str()), PV_OK);
    pv_envelope3_params p = params();
    pv_vault* v = nullptr;
    CHECK_EQ(pv_vault_create_envelope3(dir.c_str(), &p, kek1(), PV_KEK_BYTES, &v), PV_OK);
    ts::Bytes plain = det(5000);
    for (int i = 0; i < 3; ++i) {
        ts::MemSource src{&plain};
        pv_source s = src.as_source();
        uint8_t id[PV_OBJECT_ID_BYTES];
        CHECK_EQ(pv_vault_encrypt(v, &s, 0, id), PV_OK);
    }
    pv_vault_close(v);
    ts::write_file(dir + "/objects/00000000000000000000000000000000.pvo.partial", ts::Bytes(9, 1));
    ts::write_file(dir + "/objects/stray.txt", ts::Bytes(9, 1));
    ts::write_file(dir + "/vault.key.partial", ts::Bytes(9, 1));
    CHECK_EQ(pv_vault_destroy_files(dir.c_str()), PV_OK);
    CHECK(!ts::path_exists(dir));
    CHECK_EQ(pv_vault_destroy_files(dir.c_str()), PV_OK);
    // A directory without objects/ or vault.key (interrupted setup) is removed too.
    CHECK_EQ(pv_vault_create(dir.c_str()), PV_OK);
    CHECK_EQ(pv_vault_destroy_files(dir.c_str()), PV_OK);
    CHECK(!ts::path_exists(dir));
    CHECK_EQ(pv_vault_destroy_files(""), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_vault_destroy_files(nullptr), PV_ERR_INVALID_ARGUMENT);
}

}  // namespace
