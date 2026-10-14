// PIN stretching: argument checks, parameter bounds refused before the KDF
// runs, determinism, salt sensitivity, the known answer recorded in the
// fixtures, salt generation, and the wrong-PIN delay arithmetic.
#include "test_support.h"

#include <cstdio>
#include <fstream>
#include <sstream>

#ifndef PV_FIXTURES_DIR
#error "PV_FIXTURES_DIR must be defined"
#endif

namespace {

const uint8_t* test_pin() {
    static const char* s = "000000";
    return reinterpret_cast<const uint8_t*>(s);
}
const uint8_t* test_salt() {
    static const char* s = "PVTESTONLYSALT01";
    return reinterpret_cast<const uint8_t*>(s);
}

int stretch(const uint8_t* pin, size_t pin_len, const uint8_t* salt, uint64_t ops, uint64_t mem,
            ts::Bytes& out) {
    out.assign(PV_KEK_BYTES, 0);
    return pv_pin_stretch(pin, pin_len, salt, PV_PIN_SALT_BYTES, ops, mem, out.data(), out.size());
}

// Reads one "key value" line from the envelope fixture manifest.
std::string fixture_value(const char* key) {
    std::ifstream in(ts::u8path(std::string(PV_FIXTURES_DIR) + "/envelope3.txt"));
    CHECK(in.good());
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream s(line);
        std::string k, v;
        s >> k >> v;
        if (k == key) return v;
    }
    return std::string();
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    const uint64_t ops = PV_PIN_OPSLIMIT_MIN;
    const uint64_t mem = PV_PIN_MEMLIMIT_MIN;

    // Known answer, computed once by make_vectors and recorded with the
    // fixtures; the Android test checks the same value through JNI.
    ts::Bytes k;
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), ops, mem, k), PV_OK);
    CHECK_EQ(fixture_value("k_pin_hex"), ts::hex(k));
    CHECK_EQ(fixture_value("pin"), "000000");
    CHECK_EQ(fixture_value("opslimit"), std::to_string(ops));
    CHECK_EQ(fixture_value("memlimit"), std::to_string(mem));

    // Deterministic; salt, PIN, and parameters all change the output.
    ts::Bytes k2;
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), ops, mem, k2), PV_OK);
    CHECK(k == k2);
    uint8_t other_salt[PV_PIN_SALT_BYTES];
    std::memcpy(other_salt, test_salt(), sizeof(other_salt));
    other_salt[0] ^= 1;
    CHECK_EQ(stretch(test_pin(), 6, other_salt, ops, mem, k2), PV_OK);
    CHECK(k != k2);
    const uint8_t* pin2 = reinterpret_cast<const uint8_t*>("000001");
    CHECK_EQ(stretch(pin2, 6, test_salt(), ops, mem, k2), PV_OK);
    CHECK(k != k2);
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), ops + 1, mem, k2), PV_OK);
    CHECK(k != k2);
    // A 12-digit PIN and a longer passphrase-like input are accepted.
    const uint8_t* pin12 = reinterpret_cast<const uint8_t*>("123456789012");
    CHECK_EQ(stretch(pin12, 12, test_salt(), ops, mem, k2), PV_OK);
    ts::Bytes long_pin(200, 'x');
    CHECK_EQ(stretch(long_pin.data(), long_pin.size(), test_salt(), ops, mem, k2), PV_OK);

    // Bounds: refused before the KDF runs (these return at once).
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), PV_PIN_OPSLIMIT_MIN - 1, mem, k2),
             PV_ERR_ENVELOPE_KDF_PARAMS);
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), PV_PIN_OPSLIMIT_MAX + 1, mem, k2),
             PV_ERR_ENVELOPE_KDF_PARAMS);
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), 0, mem, k2), PV_ERR_ENVELOPE_KDF_PARAMS);
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), 0xffffffffffffffffull, mem, k2),
             PV_ERR_ENVELOPE_KDF_PARAMS);
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), ops, PV_PIN_MEMLIMIT_MIN - 1, k2),
             PV_ERR_ENVELOPE_KDF_PARAMS);
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), ops, PV_PIN_MEMLIMIT_MAX + 1, k2),
             PV_ERR_ENVELOPE_KDF_PARAMS);
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), ops, 0xffffffffffffffffull, k2),
             PV_ERR_ENVELOPE_KDF_PARAMS);
    CHECK_EQ(stretch(test_pin(), 6, test_salt(), ops, 8192, k2), PV_ERR_ENVELOPE_KDF_PARAMS);
    // The bounds equal the libsodium presets.
    CHECK_EQ(PV_PIN_OPSLIMIT_MIN, crypto_pwhash_OPSLIMIT_INTERACTIVE);
    CHECK_EQ(PV_PIN_OPSLIMIT_MAX, crypto_pwhash_OPSLIMIT_MODERATE);
    CHECK_EQ(PV_PIN_MEMLIMIT_MIN, crypto_pwhash_MEMLIMIT_INTERACTIVE);
    CHECK_EQ(PV_PIN_MEMLIMIT_MAX, crypto_pwhash_MEMLIMIT_MODERATE);
    // The approved defaults (CP-2): ops 2, 256 MiB.
    CHECK_EQ(PV_PIN_OPSLIMIT_DEFAULT, 2u);
    CHECK_EQ(PV_PIN_MEMLIMIT_DEFAULT, 256u * 1024u * 1024u);
    CHECK(PV_PIN_OPSLIMIT_DEFAULT >= PV_PIN_OPSLIMIT_MIN && PV_PIN_OPSLIMIT_DEFAULT <= PV_PIN_OPSLIMIT_MAX);
    CHECK(PV_PIN_MEMLIMIT_DEFAULT >= PV_PIN_MEMLIMIT_MIN && PV_PIN_MEMLIMIT_DEFAULT <= PV_PIN_MEMLIMIT_MAX);

    // Arguments.
    uint8_t out[PV_KEK_BYTES];
    CHECK_EQ(pv_pin_stretch(nullptr, 6, test_salt(), 16, ops, mem, out, sizeof(out)),
             PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_pin_stretch(test_pin(), 0, test_salt(), 16, ops, mem, out, sizeof(out)),
             PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_pin_stretch(test_pin(), 6, nullptr, 16, ops, mem, out, sizeof(out)),
             PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_pin_stretch(test_pin(), 6, test_salt(), 15, ops, mem, out, sizeof(out)),
             PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_pin_stretch(test_pin(), 6, test_salt(), 16, ops, mem, nullptr, sizeof(out)),
             PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_pin_stretch(test_pin(), 6, test_salt(), 16, ops, mem, out, 31),
             PV_ERR_INVALID_ARGUMENT);

    // Salt generation.
    uint8_t s1[PV_PIN_SALT_BYTES], s2[PV_PIN_SALT_BYTES];
    CHECK_EQ(pv_pin_salt_generate(s1, sizeof(s1)), PV_OK);
    CHECK_EQ(pv_pin_salt_generate(s2, sizeof(s2)), PV_OK);
    CHECK(std::memcmp(s1, s2, sizeof(s1)) != 0);
    CHECK_EQ(pv_pin_salt_generate(s1, 15), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_pin_salt_generate(nullptr, 16), PV_ERR_INVALID_ARGUMENT);

    // Delay table: five free attempts, then 30 s doubling, capped at one hour.
    const uint64_t expect[] = {0, 0, 0, 0, 0, 30000, 60000, 120000, 240000, 480000,
                               960000, 1920000, 3600000, 3600000, 3600000};
    for (uint32_t f = 0; f < sizeof(expect) / sizeof(expect[0]); ++f) {
        CHECK_EQ(pv_pin_delay_ms(f), expect[f]);
    }
    CHECK_EQ(pv_pin_delay_ms(1000), 3600000u);
    CHECK_EQ(pv_pin_delay_ms(0xffffffffu), 3600000u);

    // Remaining time. delay 30 s recorded at elapsed 100 000, wall 1 000 000.
    const uint64_t d = 30000;
    CHECK_EQ(pv_pin_remaining_ms(0, 0, 0, 0, 0, 0), 0u);
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 100000, 1000000, 1000000, 0), d);
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 110000, 1000000, 1010000, 0), 20000u);
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 130000, 1000000, 1030000, 0), 0u);
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 200000, 1000000, 1100000, 0), 0u);
    // Wall clock moved forward by an hour: the elapsed estimate still governs.
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 110000, 1000000, 4600000, 0), 20000u);
    // Wall clock moved back: the whole delay remains even though elapsed advanced.
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 110000, 1000000, 500000, 0), d);
    // Elapsed clock behind the recorded value (should not happen on one boot): whole delay.
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 90000, 1000000, 1030000, 0), d);
    // Reboot: the whole delay again whatever the wall clock says.
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 5, 1000000, 9000000, 1), d);
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 5, 1000000, 1000000, 1), d);
    // Negative wall values are accepted.
    CHECK_EQ(pv_pin_remaining_ms(d, 100000, 130000, -5000, -4000, 0), 29000u);

    std::printf("pin stretch ok\n");
    return 0;
}
