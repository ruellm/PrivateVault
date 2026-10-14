// Viewer support in the core (docs/PHASE3B_DESIGN.md Section 7): the exact
// plaintext length without decrypting, decrypt into a caller buffer with a
// wipe on every failure after the stream started, the cancel flag, guarded
// memory, and delete of one object.
#include "test_support.h"

#include <cstdio>

namespace {

const std::string kVault = "viewer_vault";

std::string object_file(const uint8_t* id) {
    return kVault + "/objects/" + ts::id_hex(id) + ".pvo";
}

// Encrypts `length` deterministic bytes as one object and returns the plaintext.
ts::Bytes make_object(pv_vault* v, uint64_t length, uint32_t chunk_size, uint8_t* id_out) {
    ts::Bytes plain = ts::det_bytes(length);
    ts::MemSource src{&plain};
    pv_source s = src.as_source();
    CHECK_EQ(pv_vault_encrypt(v, &s, chunk_size, id_out), PV_OK);
    return plain;
}

bool all_zero(const ts::Bytes& b) {
    for (uint8_t x : b) {
        if (x != 0) return false;
    }
    return true;
}

bool all_equal(const ts::Bytes& b, uint8_t value) {
    for (uint8_t x : b) {
        if (x != value) return false;
    }
    return true;
}

// Decrypts into a buffer of `cap` bytes filled with 0xaa; returns the status
// and leaves the buffer for inspection.
int to_buffer(pv_vault* v, const uint8_t* id, uint64_t cap, ts::Bytes& buf, uint64_t* len,
              const volatile int* cancel = nullptr) {
    buf.assign(static_cast<size_t>(cap == 0 ? 1 : cap), 0xaa);
    return pv_vault_decrypt_to_buffer(v, id, buf.data(), cap, len, cancel);
}

void sizes_and_round_trips(pv_vault* v) {
    const uint32_t chunks[] = {PV_MIN_CHUNK_SIZE, PV_DEFAULT_CHUNK_SIZE};
    for (uint32_t c : chunks) {
        const uint64_t lengths[] = {0, 1, c - 1, c, c + 1, 2 * c, 2 * c + 1, 3 * c + 123};
        for (uint64_t n : lengths) {
            uint8_t id[PV_OBJECT_ID_BYTES];
            ts::Bytes plain = make_object(v, n, c, id);
            uint64_t size = 0;
            CHECK_EQ(pv_object_plaintext_size(v, id, &size), PV_OK);
            CHECK_EQ(size, n);
            uint64_t verified = 0;
            CHECK_EQ(pv_vault_verify(v, id, &verified), PV_OK);
            CHECK_EQ(verified, n);
            // The file form agrees.
            uint64_t size2 = 0;
            CHECK_EQ(pv_object_plaintext_size_file(object_file(id).c_str(), &size2), PV_OK);
            CHECK_EQ(size2, n);

            // Exact buffer.
            ts::Bytes buf;
            uint64_t got = 0;
            CHECK_EQ(to_buffer(v, id, n, buf, &got), PV_OK);
            CHECK_EQ(got, n);
            CHECK(ts::Bytes(buf.begin(), buf.begin() + static_cast<ptrdiff_t>(n)) == plain);
            // A larger buffer: bytes past the plaintext untouched.
            CHECK_EQ(to_buffer(v, id, n + 7, buf, &got), PV_OK);
            CHECK_EQ(got, n);
            CHECK(ts::Bytes(buf.begin(), buf.begin() + static_cast<ptrdiff_t>(n)) == plain);
            CHECK(all_equal(ts::Bytes(buf.begin() + static_cast<ptrdiff_t>(n), buf.end()), 0xaa));
            // out_len may be NULL.
            CHECK_EQ(to_buffer(v, id, n, buf, nullptr), PV_OK);
            // One byte short: refused before any byte is written.
            if (n > 0) {
                CHECK_EQ(to_buffer(v, id, n - 1, buf, &got), PV_ERR_BUFFER_TOO_SMALL);
                CHECK(all_equal(buf, 0xaa));
            }
            CHECK_EQ(pv_vault_delete_object(v, id), PV_OK);
        }
    }
}

void damaged_files(pv_vault* v) {
    const uint32_t c = PV_MIN_CHUNK_SIZE;
    uint8_t id[PV_OBJECT_ID_BYTES];
    const uint64_t n = 2 * c + 100;   // three chunks, the final one short
    ts::Bytes plain = make_object(v, n, c, id);
    const std::string path = object_file(id);
    const ts::Bytes good = ts::read_file(path);
    ts::Bytes buf;
    uint64_t got = 0;

    auto restore = [&]() { ts::write_file(path, good); };

    // Tamper in the first, middle, and final chunk: the buffer is wiped.
    const size_t offsets[] = {PV_HEADER_BYTES + 10, PV_HEADER_BYTES + (c + 17) + 10, good.size() - 3};
    for (size_t off : offsets) {
        ts::Bytes bad = good;
        bad[off] ^= 1;
        ts::write_file(path, bad);
        CHECK_EQ(to_buffer(v, id, n, buf, &got), PV_ERR_AUTH_FAILED);
        CHECK(all_zero(buf));
    }
    // Truncation inside the final chunk: the length looks plausible, the
    // stream refutes it (the cut final chunk fails its tag) and the buffer is
    // wiped. Truncation at a chunk boundary leaves bytes that form no whole
    // chunk, so the length check refuses it before anything is written.
    {
        ts::Bytes cut = good;
        cut.resize(good.size() - 5);
        ts::write_file(path, cut);
        uint64_t size = 0;
        CHECK_EQ(pv_object_plaintext_size(v, id, &size), PV_OK);
        CHECK_EQ(size, n - 5);
        CHECK_EQ(to_buffer(v, id, n, buf, &got), PV_ERR_AUTH_FAILED);
        CHECK(all_zero(buf));
        cut.resize(PV_HEADER_BYTES + 2 * (c + 17));
        ts::write_file(path, cut);
        CHECK_EQ(pv_object_plaintext_size(v, id, &size), PV_ERR_MALFORMED_CHUNK);
        CHECK_EQ(to_buffer(v, id, n, buf, &got), PV_ERR_MALFORMED_CHUNK);
        CHECK(all_equal(buf, 0xaa));
        // The Phase 1 decrypt itself reports the boundary cut as TRUNCATED.
        uint64_t len = 0;
        CHECK_EQ(pv_vault_verify(v, id, &len), PV_ERR_TRUNCATED);
    }
    // A trailing byte: refused and wiped.
    {
        ts::Bytes more = good;
        more.push_back(0);
        ts::write_file(path, more);
        int rc = to_buffer(v, id, n + 1, buf, &got);
        CHECK(rc == PV_ERR_AUTH_FAILED || rc == PV_ERR_TRAILING_DATA);
        CHECK(all_zero(buf));
    }
    // Bytes that form no whole chunk: the final piece holds 100 plaintext
    // bytes; padding it to c + 3 leaves a remainder no final chunk can have,
    // and the length is refused before anything runs.
    {
        ts::Bytes odd = good;
        odd.resize(good.size() + (c - 100) + 3, 0);
        ts::write_file(path, odd);
        uint64_t size = 0;
        CHECK_EQ(pv_object_plaintext_size(v, id, &size), PV_ERR_MALFORMED_CHUNK);
        CHECK_EQ(to_buffer(v, id, n + c, buf, &got), PV_ERR_MALFORMED_CHUNK);
        CHECK(all_equal(buf, 0xaa));
    }
    // Too short for a final chunk; too short for a header.
    {
        ts::Bytes tiny = good;
        tiny.resize(PV_HEADER_BYTES + 16);
        ts::write_file(path, tiny);
        uint64_t size = 0;
        CHECK_EQ(pv_object_plaintext_size(v, id, &size), PV_ERR_TRUNCATED);
        tiny.resize(100);
        ts::write_file(path, tiny);
        CHECK_EQ(pv_object_plaintext_size(v, id, &size), PV_ERR_MALFORMED_HEADER);
    }
    restore();
    // Renamed object: the header ID differs from the name.
    {
        uint8_t other[PV_OBJECT_ID_BYTES];
        std::memcpy(other, id, sizeof(other));
        other[0] ^= 0xff;
        ts::write_file(object_file(other), good);
        uint64_t size = 0;
        CHECK_EQ(pv_object_plaintext_size(v, other, &size), PV_ERR_MALFORMED_HEADER);
        CHECK_EQ(to_buffer(v, other, n, buf, &got), PV_ERR_MALFORMED_HEADER);
        CHECK(all_equal(buf, 0xaa));
        CHECK_EQ(pv_vault_delete_object(v, other), PV_OK);
    }
    // Wrong key.
    {
        pv_vault* w = nullptr;
        CHECK_EQ(pv_vault_open(kVault.c_str(), ts::other_vmk().data(), PV_VMK_BYTES, &w), PV_OK);
        uint64_t size = 0;
        CHECK_EQ(pv_object_plaintext_size(w, id, &size), PV_OK);   // needs no key
        CHECK_EQ(size, n);
        CHECK_EQ(to_buffer(w, id, n, buf, &got), PV_ERR_WRONG_VMK);
        CHECK(all_zero(buf));
        pv_vault_close(w);
    }
    // Missing object.
    {
        uint8_t missing[PV_OBJECT_ID_BYTES] = {7};
        uint64_t size = 0;
        CHECK_EQ(pv_object_plaintext_size(v, missing, &size), PV_ERR_NOT_FOUND);
        CHECK_EQ(to_buffer(v, missing, 10, buf, &got), PV_ERR_NOT_FOUND);
        CHECK(all_equal(buf, 0xaa));
    }
    // The cancel flag: the first chunk sees it; the buffer is wiped.
    {
        volatile int cancel = 1;
        CHECK_EQ(to_buffer(v, id, n, buf, &got, &cancel), PV_ERR_CANCELLED);
        CHECK(all_zero(buf));
        cancel = 0;
        CHECK_EQ(to_buffer(v, id, n, buf, &got, &cancel), PV_OK);
        CHECK_EQ(got, n);
    }
    // The good object still decrypts.
    CHECK_EQ(to_buffer(v, id, n, buf, &got), PV_OK);
    CHECK(ts::Bytes(buf.begin(), buf.begin() + static_cast<ptrdiff_t>(n)) == plain);
    CHECK_EQ(pv_vault_delete_object(v, id), PV_OK);
}

void guarded_memory() {
    void* p = nullptr;
    CHECK_EQ(pv_secure_alloc(0, &p), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_secure_alloc(16, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_secure_alloc(1, &p), PV_OK);
    CHECK(p != nullptr);
    static_cast<uint8_t*>(p)[0] = 42;
    CHECK_EQ(static_cast<uint8_t*>(p)[0], 42);
    pv_secure_free(p, 1);
    const size_t big = 32u << 20;
    CHECK_EQ(pv_secure_alloc(big, &p), PV_OK);
    CHECK(p != nullptr);
    std::memset(p, 0x5c, big);
    CHECK_EQ(static_cast<uint8_t*>(p)[big - 1], 0x5c);
    pv_secure_free(p, big);
    pv_secure_free(nullptr, 0);
    // Alloc after free works.
    CHECK_EQ(pv_secure_alloc(4096, &p), PV_OK);
    pv_secure_free(p, 4096);
}

void delete_objects(pv_vault* v) {
    uint8_t a[PV_OBJECT_ID_BYTES];
    uint8_t b[PV_OBJECT_ID_BYTES];
    make_object(v, 1000, 0, a);
    make_object(v, 2000, 0, b);
    // A stale partial beside the object goes with it.
    ts::write_file(object_file(a) + ".partial", ts::Bytes(10, 1));
    CHECK_EQ(pv_vault_delete_object(v, a), PV_OK);
    CHECK(!ts::path_exists(object_file(a)));
    CHECK(!ts::path_exists(object_file(a) + ".partial"));
    CHECK_EQ(pv_vault_delete_object(v, a), PV_ERR_NOT_FOUND);
    uint64_t len = 0;
    CHECK_EQ(pv_vault_verify(v, b, &len), PV_OK);
    CHECK_EQ(len, 2000u);
    // A lone partial: removed, and the object is still reported missing.
    ts::write_file(object_file(a) + ".partial", ts::Bytes(10, 1));
    CHECK_EQ(pv_vault_delete_object(v, a), PV_ERR_NOT_FOUND);
    CHECK(!ts::path_exists(object_file(a) + ".partial"));
    // Without a partial.
    CHECK_EQ(pv_vault_delete_object(v, b), PV_OK);
    CHECK(!ts::path_exists(object_file(b)));
    CHECK_EQ(pv_vault_verify(v, b, &len), PV_ERR_NOT_FOUND);
    // Arguments.
    CHECK_EQ(pv_vault_delete_object(nullptr, a), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_vault_delete_object(v, nullptr), PV_ERR_INVALID_ARGUMENT);
}

void arguments(pv_vault* v) {
    uint8_t id[PV_OBJECT_ID_BYTES] = {1};
    uint64_t size = 0;
    uint8_t buf[16];
    CHECK_EQ(pv_object_plaintext_size(nullptr, id, &size), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_object_plaintext_size(v, nullptr, &size), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_object_plaintext_size(v, id, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_object_plaintext_size_file(nullptr, &size), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_object_plaintext_size_file("", &size), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_object_plaintext_size_file("nothing.pvo", &size), PV_ERR_NOT_FOUND);
    CHECK_EQ(pv_vault_decrypt_to_buffer(nullptr, id, buf, sizeof(buf), nullptr, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_vault_decrypt_to_buffer(v, nullptr, buf, sizeof(buf), nullptr, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_vault_decrypt_to_buffer(v, id, nullptr, sizeof(buf), nullptr, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(std::string(pv_error_name(PV_ERR_BUFFER_TOO_SMALL)), "PV_ERR_BUFFER_TOO_SMALL");
    CHECK_EQ(std::string(pv_error_name(PV_ERR_CANCELLED)), "PV_ERR_CANCELLED");
}

// A 100 MB object: the length is exact and comes back at once.
void large(pv_vault* v) {
    const uint64_t n = 100ull << 20;
    pv_source src{};
    CHECK_EQ(pv_source_deterministic_new(ts::test_seed(), PV_SEED_BYTES, n, &src), PV_OK);
    uint8_t id[PV_OBJECT_ID_BYTES];
    CHECK_EQ(pv_vault_encrypt(v, &src, 0, id), PV_OK);
    pv_source_deterministic_free(&src);
    uint64_t size = 0;
    CHECK_EQ(pv_object_plaintext_size(v, id, &size), PV_OK);
    CHECK_EQ(size, n);
    // A small buffer is refused without decrypting.
    ts::Bytes buf;
    uint64_t got = 0;
    CHECK_EQ(to_buffer(v, id, 4096, buf, &got), PV_ERR_BUFFER_TOO_SMALL);
    CHECK(all_equal(buf, 0xaa));
    CHECK_EQ(pv_vault_delete_object(v, id), PV_OK);
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    ts::remove_tree(kVault);
    CHECK_EQ(pv_vault_create(kVault.c_str()), PV_OK);
    pv_vault* v = nullptr;
    CHECK_EQ(pv_vault_open(kVault.c_str(), ts::test_vmk(), PV_VMK_BYTES, &v), PV_OK);
    sizes_and_round_trips(v);
    damaged_files(v);
    guarded_memory();
    delete_objects(v);
    arguments(v);
    large(v);
    pv_vault_close(v);
    ts::remove_tree(kVault);
    std::printf("viewer buffer ok\n");
    return 0;
}
