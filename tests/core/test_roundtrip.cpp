// Stream-level round trips over the size boundaries that matter for the
// chunk rules, with normal reads and with 1 to 7 byte reads.
#include "test_support.h"

#include <cstdio>

static void roundtrip(uint64_t length, uint32_t chunk_size) {
    ts::Bytes plain = ts::det_bytes(length);
    uint8_t id[PV_OBJECT_ID_BYTES];
    randombytes_buf(id, sizeof(id));
    ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), plain, chunk_size, id);

    // Size arithmetic from docs/PHASE1_DESIGN.md Section 3.3.
    uint64_t chunks = length / chunk_size + 1;
    CHECK_EQ(cipher.size(), PV_HEADER_BYTES + length + 17 * chunks);

    // Header fields are visible without a key.
    ts::MemSource hsrc{&cipher};
    pv_source hs = hsrc.as_source();
    pv_object_info info{};
    CHECK_EQ(pv_object_inspect(&hs, &info), PV_OK);
    CHECK_EQ(info.format_version, 1);
    CHECK_EQ(info.suite_id, 1);
    CHECK_EQ(info.chunk_size, chunk_size);
    CHECK_EQ(std::memcmp(info.object_id, id, sizeof(id)), 0);

    ts::Bytes out;
    CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), cipher, out, false, id), PV_OK);
    CHECK(out == plain);
    CHECK(ts::blake2b(out) == ts::blake2b(plain));

    ts::Bytes out2;
    CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), cipher, out2, true, id), PV_OK);
    CHECK(out2 == plain);

    // Expected ID mismatch is rejected before any key use.
    uint8_t wrong_id[PV_OBJECT_ID_BYTES];
    std::memcpy(wrong_id, id, sizeof(id));
    wrong_id[0] ^= 1;
    ts::Bytes out3;
    CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), cipher, out3, false, wrong_id),
             PV_ERR_MALFORMED_HEADER);
    CHECK(out3.empty());
}

static void roundtrip_short_read_encrypt(uint64_t length, uint32_t chunk_size) {
    ts::Bytes plain = ts::det_bytes(length);
    ts::MemSource src{&plain};
    src.cycle_short = true;
    ts::MemSink sink;
    pv_source s = src.as_source();
    pv_sink k = sink.as_sink();
    CHECK_EQ(pv_object_encrypt(ts::test_vmk(), PV_VMK_BYTES, &s, &k, chunk_size, nullptr), PV_OK);
    ts::Bytes out;
    CHECK_EQ(ts::decrypt_mem(ts::test_vmk(), sink.data, out), PV_OK);
    CHECK(out == plain);
}

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    const uint32_t c = PV_MIN_CHUNK_SIZE;
    const uint64_t sizes[] = {0, 1, c - 1, c, c + 1, 2 * c, 3 * c + 100, 7 * c - 1};
    for (uint64_t n : sizes) {
        roundtrip(n, c);
        roundtrip_short_read_encrypt(n, c);
    }
    // Default chunk size and the largest allowed one.
    roundtrip(100, PV_DEFAULT_CHUNK_SIZE);
    roundtrip(PV_DEFAULT_CHUNK_SIZE * 2 + 5, PV_DEFAULT_CHUNK_SIZE);
    roundtrip(PV_MAX_CHUNK_SIZE + 1, PV_MAX_CHUNK_SIZE);

    // chunk_size 0 selects the default.
    ts::Bytes plain = ts::det_bytes(10);
    ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), plain, 0);
    ts::MemSource hsrc{&cipher};
    pv_source hs = hsrc.as_source();
    pv_object_info info{};
    CHECK_EQ(pv_object_inspect(&hs, &info), PV_OK);
    CHECK_EQ(info.chunk_size, PV_DEFAULT_CHUNK_SIZE);

    // Two encryptions of the same input differ (fresh DEK, nonce, header).
    ts::Bytes cipher2 = ts::encrypt_mem(ts::test_vmk(), plain, 0);
    CHECK(cipher != cipher2);

    // Argument checks.
    ts::MemSource src{&plain};
    ts::MemSink sink;
    pv_source s = src.as_source();
    pv_sink k = sink.as_sink();
    CHECK_EQ(pv_object_encrypt(nullptr, PV_VMK_BYTES, &s, &k, 0, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_object_encrypt(ts::test_vmk(), 31, &s, &k, 0, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_object_encrypt(ts::test_vmk(), PV_VMK_BYTES, nullptr, &k, 0, nullptr),
             PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_object_encrypt(ts::test_vmk(), PV_VMK_BYTES, &s, &k, PV_MIN_CHUNK_SIZE - 1, nullptr),
             PV_ERR_CHUNK_SIZE_OUT_OF_RANGE);
    CHECK_EQ(pv_object_encrypt(ts::test_vmk(), PV_VMK_BYTES, &s, &k, PV_MAX_CHUNK_SIZE + 1, nullptr),
             PV_ERR_CHUNK_SIZE_OUT_OF_RANGE);
    std::printf("round trips ok\n");
    return 0;
}
