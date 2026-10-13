// Memory source over caller-owned parts: single and multiple parts, empty
// parts, empty source, part-boundary short reads, argument checks, and use as
// an encrypt input.
#include "test_support.h"

#include <cstdio>

// Drains a source with reads of `cap` bytes and returns everything it gave.
static ts::Bytes drain(pv_source& src, size_t cap, size_t* reads_out = nullptr) {
    ts::Bytes out;
    std::vector<uint8_t> buf(cap);
    size_t reads = 0;
    for (;;) {
        size_t n = 0;
        CHECK_EQ(src.read(src.ctx, buf.data(), cap, &n), 0);
        ++reads;
        CHECK(n <= cap);
        if (n == 0) break;
        out.insert(out.end(), buf.begin(), buf.begin() + static_cast<ptrdiff_t>(n));
    }
    if (reads_out) *reads_out = reads;
    return out;
}

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    ts::Bytes a = ts::det_bytes(1000);
    ts::Bytes b = ts::det_bytes(5000);
    ts::Bytes c = ts::det_bytes(3);

    // Single part, large reads and byte reads.
    {
        const uint8_t* parts[] = {a.data()};
        size_t lens[] = {a.size()};
        pv_source src{};
        CHECK_EQ(pv_source_memory_new(parts, lens, 1, &src), PV_OK);
        CHECK(drain(src, 4096) == a);
        pv_source_memory_free(&src);
        CHECK(src.ctx == nullptr && src.read == nullptr);
        CHECK_EQ(pv_source_memory_new(parts, lens, 1, &src), PV_OK);
        CHECK(drain(src, 1) == a);
        pv_source_memory_free(&src);
    }

    // Three parts: the stream is their concatenation; a read never crosses a
    // part boundary, so a 4096-byte read at the start returns 1000 bytes.
    {
        const uint8_t* parts[] = {a.data(), b.data(), c.data()};
        size_t lens[] = {a.size(), b.size(), c.size()};
        pv_source src{};
        CHECK_EQ(pv_source_memory_new(parts, lens, 3, &src), PV_OK);
        uint8_t buf[4096];
        size_t n = 0;
        CHECK_EQ(src.read(src.ctx, buf, sizeof(buf), &n), 0);
        CHECK_EQ(n, 1000u);
        CHECK_EQ(std::memcmp(buf, a.data(), 1000), 0);
        CHECK_EQ(src.read(src.ctx, buf, sizeof(buf), &n), 0);
        CHECK_EQ(n, 4096u);
        CHECK_EQ(src.read(src.ctx, buf, sizeof(buf), &n), 0);
        CHECK_EQ(n, 904u);
        CHECK_EQ(src.read(src.ctx, buf, sizeof(buf), &n), 0);
        CHECK_EQ(n, 3u);
        CHECK_EQ(src.read(src.ctx, buf, sizeof(buf), &n), 0);
        CHECK_EQ(n, 0u);
        // Stays at end.
        CHECK_EQ(src.read(src.ctx, buf, sizeof(buf), &n), 0);
        CHECK_EQ(n, 0u);
        pv_source_memory_free(&src);

        ts::Bytes expect = a;
        expect.insert(expect.end(), b.begin(), b.end());
        expect.insert(expect.end(), c.begin(), c.end());
        CHECK_EQ(pv_source_memory_new(parts, lens, 3, &src), PV_OK);
        CHECK(drain(src, 7) == expect);
        pv_source_memory_free(&src);
    }

    // Zero-length parts anywhere, including a NULL pointer for an empty part.
    {
        const uint8_t* parts[] = {nullptr, a.data(), c.data(), nullptr, b.data(), nullptr};
        size_t lens[] = {0, a.size(), 0, 0, b.size(), 0};
        pv_source src{};
        CHECK_EQ(pv_source_memory_new(parts, lens, 6, &src), PV_OK);
        ts::Bytes expect = a;
        expect.insert(expect.end(), b.begin(), b.end());
        CHECK(drain(src, 512) == expect);
        pv_source_memory_free(&src);
    }

    // Empty source: zero parts, or only empty parts.
    {
        pv_source src{};
        CHECK_EQ(pv_source_memory_new(nullptr, nullptr, 0, &src), PV_OK);
        size_t reads = 0;
        CHECK(drain(src, 16, &reads).empty());
        CHECK_EQ(reads, 1u);
        pv_source_memory_free(&src);
        const uint8_t* parts[] = {nullptr, nullptr};
        size_t lens[] = {0, 0};
        CHECK_EQ(pv_source_memory_new(parts, lens, 2, &src), PV_OK);
        CHECK(drain(src, 16).empty());
        pv_source_memory_free(&src);
    }

    // Zero-capacity read returns zero bytes without advancing.
    {
        const uint8_t* parts[] = {a.data()};
        size_t lens[] = {a.size()};
        pv_source src{};
        CHECK_EQ(pv_source_memory_new(parts, lens, 1, &src), PV_OK);
        uint8_t buf[1];
        size_t n = 7;
        CHECK_EQ(src.read(src.ctx, buf, 0, &n), 0);
        CHECK_EQ(n, 0u);
        CHECK(drain(src, 100) == a);
        pv_source_memory_free(&src);
    }

    // Argument checks.
    {
        pv_source src{};
        const uint8_t* parts[] = {a.data()};
        size_t lens[] = {a.size()};
        CHECK_EQ(pv_source_memory_new(parts, lens, 1, nullptr), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_source_memory_new(nullptr, lens, 1, &src), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_source_memory_new(parts, nullptr, 1, &src), PV_ERR_INVALID_ARGUMENT);
        const uint8_t* bad[] = {nullptr};
        size_t bad_len[] = {5};
        CHECK_EQ(pv_source_memory_new(bad, bad_len, 1, &src), PV_ERR_INVALID_ARGUMENT);
        pv_source_memory_free(&src);   // no-op on an unset source
        pv_source_memory_free(nullptr);
    }

    // As an encrypt input: the Phase 1 reader loops over the short reads at
    // part boundaries, so the object equals one made from a single buffer.
    {
        const uint8_t* parts[] = {a.data(), b.data(), c.data()};
        size_t lens[] = {a.size(), b.size(), c.size()};
        pv_source src{};
        CHECK_EQ(pv_source_memory_new(parts, lens, 3, &src), PV_OK);
        ts::MemSink sink;
        pv_sink k = sink.as_sink();
        CHECK_EQ(pv_object_encrypt(ts::test_vmk(), PV_VMK_BYTES, &src, &k, PV_MIN_CHUNK_SIZE,
                                   nullptr), PV_OK);
        pv_source_memory_free(&src);
        ts::Bytes expect = a;
        expect.insert(expect.end(), b.begin(), b.end());
        expect.insert(expect.end(), c.begin(), c.end());
        CHECK_EQ(sink.data.size(), PV_HEADER_BYTES + expect.size() + 17 * (expect.size() / PV_MIN_CHUNK_SIZE + 1));
        ts::Bytes out;
        CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), sink.data, out, true), PV_OK);
        CHECK(out == expect);
    }
    std::printf("memory source ok\n");
    return 0;
}
