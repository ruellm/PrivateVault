// Shared helpers for the host tests: memory streams, short-read and faulting
// streams, a raw object builder for crafted negative cases, hashing, and
// scratch directories. Test-only code; nothing here is used by the library.
#ifndef PV_TESTS_SUPPORT_H
#define PV_TESTS_SUPPORT_H

#include "check.h"
#include "pv/pv.h"

#include <sodium.h>

#include <cstdint>
#include <cstring>
#include <filesystem>
#include <string>
#include <utility>
#include <vector>

namespace ts {

using Bytes = std::vector<uint8_t>;

// Synthetic, public test values. Labelled TEST ONLY; not secrets.
inline const uint8_t* test_vmk() {
    static const char* s = "PRIVATEVAULT TEST ONLY VMK  0001";
    return reinterpret_cast<const uint8_t*>(s);
}
inline const uint8_t* test_seed() {
    static const char* s = "PRIVATEVAULT TEST ONLY SEED 0001";
    return reinterpret_cast<const uint8_t*>(s);
}
inline Bytes other_vmk() {
    Bytes k(PV_VMK_BYTES, 0x5a);
    return k;
}

// Memory source. max_per_read limits each read; 0 means no limit. With
// cycle_short set, reads return 1 to 7 bytes in rotation.
struct MemSource {
    const Bytes* data;
    size_t pos = 0;
    size_t max_per_read = 0;
    bool cycle_short = false;
    size_t cycle = 0;
    pv_source as_source() { return pv_source{this, &MemSource::read}; }
    static int read(void* ctx, uint8_t* buf, size_t cap, size_t* out_n) {
        MemSource* s = static_cast<MemSource*>(ctx);
        size_t n = s->data->size() - s->pos;
        if (n > cap) n = cap;
        if (s->max_per_read && n > s->max_per_read) n = s->max_per_read;
        if (s->cycle_short) {
            size_t lim = 1 + (s->cycle++ % 7);
            if (n > lim) n = lim;
        }
        std::memcpy(buf, s->data->data() + s->pos, n);
        s->pos += n;
        *out_n = n;
        return 0;
    }
};

struct MemSink {
    Bytes data;
    pv_sink as_sink() { return pv_sink{this, &MemSink::write}; }
    static int write(void* ctx, const uint8_t* buf, size_t n) {
        MemSink* s = static_cast<MemSink*>(ctx);
        s->data.insert(s->data.end(), buf, buf + n);
        return 0;
    }
};

// Fails every read once fail_after bytes have been delivered.
struct FaultSource {
    const Bytes* data;
    size_t pos = 0;
    size_t fail_after;
    pv_source as_source() { return pv_source{this, &FaultSource::read}; }
    static int read(void* ctx, uint8_t* buf, size_t cap, size_t* out_n) {
        FaultSource* s = static_cast<FaultSource*>(ctx);
        if (s->pos >= s->fail_after) return -1;
        size_t n = s->data->size() - s->pos;
        if (n > cap) n = cap;
        if (s->pos + n > s->fail_after) n = s->fail_after - s->pos;
        std::memcpy(buf, s->data->data() + s->pos, n);
        s->pos += n;
        *out_n = n;
        return 0;
    }
};

// Fails the write that would carry total output past fail_after bytes.
struct FaultSink {
    size_t written = 0;
    size_t fail_after;
    pv_sink as_sink() { return pv_sink{this, &FaultSink::write}; }
    static int write(void* ctx, const uint8_t*, size_t n) {
        FaultSink* s = static_cast<FaultSink*>(ctx);
        if (s->written + n > s->fail_after) return -1;
        s->written += n;
        return 0;
    }
};

inline Bytes det_bytes(uint64_t length) {
    Bytes out(static_cast<size_t>(length));
    pv_source src{};
    CHECK_EQ(pv_source_deterministic_new(test_seed(), PV_SEED_BYTES, length, &src), PV_OK);
    size_t pos = 0;
    while (pos < out.size()) {
        size_t n = 0;
        CHECK_EQ(src.read(src.ctx, out.data() + pos, out.size() - pos, &n), 0);
        CHECK(n > 0);
        pos += n;
    }
    pv_source_deterministic_free(&src);
    return out;
}

inline Bytes blake2b(const Bytes& data) {
    Bytes h(PV_HASH_BYTES);
    crypto_generichash(h.data(), h.size(), data.data(), data.size(), nullptr, 0);
    return h;
}

inline std::string hex(const Bytes& b) {
    std::string s(2 * b.size() + 1, '\0');
    sodium_bin2hex(s.data(), s.size(), b.data(), b.size());
    s.resize(2 * b.size());
    return s;
}

inline Bytes encrypt_mem(const uint8_t* vmk, const Bytes& plain, uint32_t chunk_size,
                         const uint8_t* object_id = nullptr) {
    MemSource src{&plain};
    MemSink sink;
    pv_source s = src.as_source();
    pv_sink k = sink.as_sink();
    CHECK_EQ(pv_object_encrypt(vmk, PV_VMK_BYTES, &s, &k, chunk_size, object_id), PV_OK);
    return std::move(sink.data);
}

inline int decrypt_mem(const uint8_t* vmk, const Bytes& cipher, Bytes& plain_out,
                       bool short_reads = false, const uint8_t* expected_id = nullptr) {
    MemSource src{&cipher};
    src.cycle_short = short_reads;
    MemSink sink;
    pv_source s = src.as_source();
    pv_sink k = sink.as_sink();
    uint64_t len = 0;
    int rc = pv_object_decrypt(vmk, PV_VMK_BYTES, &s, &k, expected_id, &len);
    if (rc == PV_OK) {
        CHECK_EQ(len, sink.data.size());
    }
    plain_out = std::move(sink.data);
    return rc;
}

// Builds a raw object by hand so tests can produce chunk sequences the
// library never writes (wrong tags, short non-final chunks). Mirrors the
// layout in docs/PHASE1_DESIGN.md Section 3.
struct RawChunk {
    Bytes plain;
    unsigned char tag;
};

inline Bytes build_raw_object(const uint8_t* vmk, uint32_t chunk_size,
                              const std::vector<RawChunk>& chunks) {
    Bytes out(PV_HEADER_BYTES, 0);
    std::memcpy(out.data(), "PVOBJECT", 8);
    out[8] = 1; out[9] = 0;      // format_version
    out[10] = 1; out[11] = 0;    // suite_id
    for (int i = 0; i < 4; ++i) out[12 + i] = static_cast<uint8_t>(chunk_size >> (8 * i));
    randombytes_buf(out.data() + 16, 16);   // object_id
    randombytes_buf(out.data() + 32, 24);   // dek wrap nonce
    uint8_t dek[32];
    crypto_secretstream_xchacha20poly1305_keygen(dek);
    crypto_secretstream_xchacha20poly1305_state st;
    CHECK_EQ(crypto_secretstream_xchacha20poly1305_init_push(&st, out.data() + 104, dek), 0);
    unsigned long long clen = 0;
    CHECK_EQ(crypto_aead_xchacha20poly1305_ietf_encrypt(out.data() + 56, &clen, dek, 32,
                                                        out.data(), 56, nullptr,
                                                        out.data() + 32, vmk), 0);
    bool first = true;
    for (const RawChunk& ch : chunks) {
        Bytes c(ch.plain.size() + crypto_secretstream_xchacha20poly1305_ABYTES);
        CHECK_EQ(crypto_secretstream_xchacha20poly1305_push(
                     &st, c.data(), &clen, ch.plain.data(), ch.plain.size(),
                     first ? out.data() : nullptr, first ? PV_HEADER_BYTES : 0, ch.tag), 0);
        first = false;
        out.insert(out.end(), c.begin(), c.begin() + static_cast<ptrdiff_t>(clen));
    }
    sodium_memzero(dek, sizeof(dek));
    return out;
}

// Scratch directory handling. Tests run with their working directory set to
// a per-test folder under the build tree.
inline std::filesystem::path u8path(const std::string& utf8) {
    return std::filesystem::path(reinterpret_cast<const char8_t*>(utf8.c_str()));
}

inline void remove_tree(const std::string& utf8) {
    std::error_code ec;
    std::filesystem::remove_all(u8path(utf8), ec);
}

inline bool path_exists(const std::string& utf8) {
    std::error_code ec;
    return std::filesystem::exists(u8path(utf8), ec);
}

inline Bytes read_file(const std::string& utf8) {
    Bytes out;
    pv_source src{};
    CHECK_EQ(pv_file_source_open(utf8.c_str(), &src), PV_OK);
    uint8_t buf[65536];
    for (;;) {
        size_t n = 0;
        CHECK_EQ(src.read(src.ctx, buf, sizeof(buf), &n), 0);
        if (n == 0) break;
        out.insert(out.end(), buf, buf + n);
    }
    pv_file_source_close(&src);
    return out;
}

inline void write_file(const std::string& utf8, const Bytes& data) {
    remove_tree(utf8);
    pv_sink sink{};
    CHECK_EQ(pv_file_sink_create(utf8.c_str(), &sink), PV_OK);
    if (!data.empty()) CHECK_EQ(sink.write(sink.ctx, data.data(), data.size()), 0);
    CHECK_EQ(pv_file_sink_commit(&sink), PV_OK);
}

inline std::string id_hex(const uint8_t* id) {
    char h[2 * PV_OBJECT_ID_BYTES + 1];
    pv_object_id_to_hex(id, h, sizeof(h));
    return h;
}

}  // namespace ts

#endif
