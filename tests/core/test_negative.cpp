// Every way an object can be wrong must fail closed with the expected code,
// with normal reads and with 1 to 7 byte reads, and without producing the
// full plaintext.
#include "test_support.h"

#include <cstdio>

namespace {

const uint32_t kChunk = PV_MIN_CHUNK_SIZE;
const uint64_t kLen = 3 * kChunk + 100;  // three full chunks plus a short FINAL

ts::Bytes g_plain;
ts::Bytes g_cipher;

// Runs a decrypt in both read modes and checks the code. Output must never
// equal the full plaintext.
void expect(const ts::Bytes& cipher, int code, const char* what) {
    for (int mode = 0; mode < 2; ++mode) {
        ts::Bytes out;
        int rc = ts::decrypt_mem(ts::test_vmk(), cipher, out, mode == 1);
        if (rc != code) {
            std::fprintf(stderr, "%s (short=%d): expected %s, got %s\n", what, mode,
                         pv_error_name(code), pv_error_name(rc));
            std::exit(1);
        }
        CHECK(out != g_plain);
    }
}

ts::Bytes flipped(size_t offset) {
    ts::Bytes c = g_cipher;
    c[offset] ^= 0x01;
    return c;
}

ts::Bytes truncated(size_t len) {
    ts::Bytes c = g_cipher;
    c.resize(len);
    return c;
}

ts::Bytes with_u16(size_t offset, uint16_t v) {
    ts::Bytes c = g_cipher;
    c[offset] = static_cast<uint8_t>(v);
    c[offset + 1] = static_cast<uint8_t>(v >> 8);
    return c;
}

ts::Bytes with_u32(size_t offset, uint32_t v) {
    ts::Bytes c = g_cipher;
    for (int i = 0; i < 4; ++i) c[offset + i] = static_cast<uint8_t>(v >> (8 * i));
    return c;
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    g_plain = ts::det_bytes(kLen);
    g_cipher = ts::encrypt_mem(ts::test_vmk(), g_plain, kChunk);
    const size_t full = kChunk + 17;
    const size_t chunk0 = PV_HEADER_BYTES;
    const size_t chunk1 = chunk0 + full;
    const size_t chunk2 = chunk1 + full;
    const size_t chunk3 = chunk2 + full;  // FINAL, 100 + 17 bytes
    CHECK_EQ(g_cipher.size(), chunk3 + 117);

    // Sanity: the untouched object decrypts.
    {
        ts::Bytes out;
        CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), g_cipher, out), PV_OK);
        CHECK(out == g_plain);
    }

    // Wrong key.
    {
        ts::Bytes out;
        CHECK_EQ(ts::decrypt_mem(ts::other_vmk().data(), g_cipher, out), PV_ERR_WRONG_VMK);
        CHECK(out.empty());
    }

    // Header fields, one class at a time.
    expect(flipped(0), PV_ERR_MALFORMED_HEADER, "magic");
    expect(with_u16(8, 2), PV_ERR_UNSUPPORTED_VERSION, "version 2");
    expect(with_u16(8, 0), PV_ERR_UNSUPPORTED_VERSION, "version 0");
    expect(with_u16(10, 2), PV_ERR_UNSUPPORTED_SUITE, "suite 2");
    expect(with_u32(12, PV_MIN_CHUNK_SIZE - 1), PV_ERR_CHUNK_SIZE_OUT_OF_RANGE, "chunk low");
    expect(with_u32(12, PV_MAX_CHUNK_SIZE + 1), PV_ERR_CHUNK_SIZE_OUT_OF_RANGE, "chunk high");
    expect(with_u32(12, 0), PV_ERR_CHUNK_SIZE_OUT_OF_RANGE, "chunk zero");
    expect(with_u32(12, 0xffffffffu), PV_ERR_CHUNK_SIZE_OUT_OF_RANGE, "chunk max u32");
    // In-range but wrong chunk size: the wrap-bound prefix changed.
    expect(with_u32(12, 2 * kChunk), PV_ERR_WRONG_VMK, "chunk size changed");
    expect(flipped(16), PV_ERR_WRONG_VMK, "object_id");
    expect(flipped(32), PV_ERR_WRONG_VMK, "dek wrap nonce");
    expect(flipped(56), PV_ERR_WRONG_VMK, "wrapped dek body");
    expect(flipped(103), PV_ERR_WRONG_VMK, "wrapped dek tag");
    expect(flipped(104), PV_ERR_AUTH_FAILED, "stream header");
    expect(flipped(127), PV_ERR_AUTH_FAILED, "stream header last byte");

    // Short header.
    expect(truncated(0), PV_ERR_MALFORMED_HEADER, "empty file");
    expect(truncated(7), PV_ERR_MALFORMED_HEADER, "7 bytes");
    expect(truncated(127), PV_ERR_MALFORMED_HEADER, "127 bytes");

    // Ciphertext in first, middle, and final chunk; tag bytes too.
    expect(flipped(chunk0), PV_ERR_AUTH_FAILED, "first chunk body");
    expect(flipped(chunk1 - 1), PV_ERR_AUTH_FAILED, "first chunk tag");
    expect(flipped(chunk1 + 100), PV_ERR_AUTH_FAILED, "middle chunk body");
    expect(flipped(chunk3 + 5), PV_ERR_AUTH_FAILED, "final chunk body");
    expect(flipped(g_cipher.size() - 1), PV_ERR_AUTH_FAILED, "final chunk tag");

    // Truncation.
    expect(truncated(chunk0), PV_ERR_TRUNCATED, "header only");
    expect(truncated(chunk0 + 16), PV_ERR_TRUNCATED, "16 bytes of first chunk");
    expect(truncated(chunk0 + 17), PV_ERR_AUTH_FAILED, "17 bytes of first chunk");
    expect(truncated(chunk1), PV_ERR_TRUNCATED, "boundary after chunk 0");
    expect(truncated(chunk1 + 1000), PV_ERR_AUTH_FAILED, "inside chunk 1");
    expect(truncated(chunk3), PV_ERR_TRUNCATED, "boundary before FINAL");
    expect(truncated(chunk3 + 16), PV_ERR_TRUNCATED, "16 bytes of FINAL");
    expect(truncated(chunk3 + 17), PV_ERR_AUTH_FAILED, "17 bytes of FINAL");
    expect(truncated(g_cipher.size() - 1), PV_ERR_AUTH_FAILED, "FINAL minus one");

    // Trailing data. The final chunk here is shorter than chunk_size, so the
    // reader cannot tell extra bytes from a damaged final chunk and reports
    // AUTH_FAILED (docs/PHASE1_DESIGN.md Section 3.2). Either way it fails.
    {
        ts::Bytes c = g_cipher;
        c.push_back(0);
        expect(c, PV_ERR_AUTH_FAILED, "one trailing byte after short FINAL");
        ts::Bytes c2 = g_cipher;
        c2.insert(c2.end(), g_cipher.begin(), g_cipher.end());
        expect(c2, PV_ERR_AUTH_FAILED, "object appended twice");
    }
    // With a full-size final chunk the trailing bytes are detected as such.
    {
        ts::Bytes plain_full = ts::det_bytes(2 * kChunk);
        ts::Bytes full_obj = ts::encrypt_mem(ts::test_vmk(), plain_full, kChunk);
        // Canonical form: MESSAGE, MESSAGE, empty FINAL (17 bytes).
        CHECK_EQ(full_obj.size(), chunk0 + 2 * full + 17);
        ts::Bytes out;
        CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), full_obj, out), PV_OK);
        // A crafted object whose FINAL carries a full chunk, then extra bytes.
        ts::Bytes a = ts::det_bytes(kChunk);
        ts::Bytes crafted = ts::build_raw_object(
            ts::test_vmk(), kChunk,
            {ts::RawChunk{a, crypto_secretstream_xchacha20poly1305_TAG_FINAL}});
        CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), crafted, out), PV_OK);
        ts::Bytes t1 = crafted;
        t1.push_back(0);
        g_plain = a;
        expect(t1, PV_ERR_TRAILING_DATA, "one trailing byte after full FINAL");
        ts::Bytes t2 = crafted;
        t2.insert(t2.end(), full, 0x42);
        expect(t2, PV_ERR_TRAILING_DATA, "whole extra chunk after full FINAL");
        g_plain = ts::det_bytes(kLen);
    }

    // Swapped chunks: the stream state rejects reordering.
    {
        ts::Bytes c = g_cipher;
        std::vector<uint8_t> a(c.begin() + chunk0, c.begin() + chunk1);
        std::vector<uint8_t> b(c.begin() + chunk1, c.begin() + chunk2);
        std::copy(b.begin(), b.end(), c.begin() + chunk0);
        std::copy(a.begin(), a.end(), c.begin() + chunk1);
        expect(c, PV_ERR_AUTH_FAILED, "swapped chunks");
    }

    // Crafted sequences the writer never produces.
    {
        using ts::RawChunk;
        ts::Bytes a = ts::det_bytes(kChunk);
        ts::Bytes b = ts::det_bytes(10);
        // Valid crafted object (canonical) decrypts.
        ts::Bytes ok = ts::build_raw_object(
            ts::test_vmk(), kChunk,
            {RawChunk{a, crypto_secretstream_xchacha20poly1305_TAG_MESSAGE},
             RawChunk{b, crypto_secretstream_xchacha20poly1305_TAG_FINAL}});
        ts::Bytes out;
        CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), ok, out), PV_OK);
        CHECK_EQ(out.size(), kChunk + 10);
        // Non-canonical but accepted: FINAL carrying a full chunk.
        ts::Bytes full_final = ts::build_raw_object(
            ts::test_vmk(), kChunk, {RawChunk{a, crypto_secretstream_xchacha20poly1305_TAG_FINAL}});
        CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), full_final, out), PV_OK);
        CHECK(out == a);
        // Short MESSAGE chunk at the end of the data: authenticates, wrong size.
        ts::Bytes short_msg_only = ts::build_raw_object(
            ts::test_vmk(), kChunk,
            {RawChunk{b, crypto_secretstream_xchacha20poly1305_TAG_MESSAGE}});
        g_plain = b;  // make expect() compare against something irrelevant
        expect(short_msg_only, PV_ERR_MALFORMED_CHUNK, "short MESSAGE chunk alone");
        // Short MESSAGE chunk followed by more data: both land in one buffer
        // and authentication fails.
        ts::Bytes short_msg = ts::build_raw_object(
            ts::test_vmk(), kChunk,
            {RawChunk{b, crypto_secretstream_xchacha20poly1305_TAG_MESSAGE},
             RawChunk{b, crypto_secretstream_xchacha20poly1305_TAG_FINAL}});
        expect(short_msg, PV_ERR_AUTH_FAILED, "short MESSAGE chunk then FINAL");
        // PUSH and REKEY tags.
        ts::Bytes push = ts::build_raw_object(
            ts::test_vmk(), kChunk,
            {RawChunk{a, crypto_secretstream_xchacha20poly1305_TAG_PUSH},
             RawChunk{b, crypto_secretstream_xchacha20poly1305_TAG_FINAL}});
        expect(push, PV_ERR_MALFORMED_CHUNK, "PUSH tag");
        ts::Bytes rekey = ts::build_raw_object(
            ts::test_vmk(), kChunk,
            {RawChunk{a, crypto_secretstream_xchacha20poly1305_TAG_REKEY},
             RawChunk{b, crypto_secretstream_xchacha20poly1305_TAG_FINAL}});
        expect(rekey, PV_ERR_MALFORMED_CHUNK, "REKEY tag");
        // No FINAL at all.
        ts::Bytes no_final = ts::build_raw_object(
            ts::test_vmk(), kChunk, {RawChunk{a, crypto_secretstream_xchacha20poly1305_TAG_MESSAGE}});
        expect(no_final, PV_ERR_TRUNCATED, "no FINAL");
        // A second chunk after a short FINAL: read as part of the final
        // buffer, so it fails authentication.
        ts::Bytes two_finals = ts::build_raw_object(
            ts::test_vmk(), kChunk,
            {RawChunk{b, crypto_secretstream_xchacha20poly1305_TAG_FINAL},
             RawChunk{b, crypto_secretstream_xchacha20poly1305_TAG_FINAL}});
        expect(two_finals, PV_ERR_AUTH_FAILED, "chunk after short FINAL");
    }

    std::printf("negative cases ok\n");
    return 0;
}
