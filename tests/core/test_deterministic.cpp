// The deterministic source must give the same bytes regardless of read size.
#include "test_support.h"

#include <cstdio>

static ts::Bytes hash_with_read_size(uint64_t length, size_t read_size) {
    pv_source src{};
    CHECK_EQ(pv_source_deterministic_new(ts::test_seed(), PV_SEED_BYTES, length, &src), PV_OK);
    pv_sink sink{};
    CHECK_EQ(pv_sink_blake2b_new(&sink), PV_OK);
    std::vector<uint8_t> buf(read_size);
    uint64_t total = 0;
    for (;;) {
        size_t n = 0;
        CHECK_EQ(src.read(src.ctx, buf.data(), buf.size(), &n), 0);
        if (n == 0) break;
        CHECK_EQ(sink.write(sink.ctx, buf.data(), n), 0);
        total += n;
    }
    CHECK_EQ(total, length);
    pv_source_deterministic_free(&src);
    ts::Bytes h(PV_HASH_BYTES);
    CHECK_EQ(pv_sink_blake2b_finish(&sink, h.data(), h.size()), PV_OK);
    return h;
}

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    const uint64_t length = 1u << 20;
    ts::Bytes h1 = hash_with_read_size(length, 1);
    ts::Bytes h2 = hash_with_read_size(length, 4096);
    ts::Bytes h3 = hash_with_read_size(length, 1u << 20);
    ts::Bytes h4 = hash_with_read_size(length, 7);
    CHECK(h1 == h2);
    CHECK(h1 == h3);
    CHECK(h1 == h4);

    // pv_hash_deterministic agrees with the streaming path.
    ts::Bytes h5(PV_HASH_BYTES);
    CHECK_EQ(pv_hash_deterministic(ts::test_seed(), PV_SEED_BYTES, length, h5.data(),
                                   h5.size()), PV_OK);
    CHECK(h1 == h5);

    // Whole-stream generation with libsodium directly matches byte for byte.
    ts::Bytes direct(length, 0);
    uint8_t nonce[crypto_stream_chacha20_ietf_NONCEBYTES] = {0};
    CHECK_EQ(crypto_stream_chacha20_ietf(direct.data(), direct.size(), nonce, ts::test_seed()), 0);
    CHECK(ts::blake2b(direct) == h1);

    // Different seeds give different streams; an empty stream hashes as empty input.
    uint8_t seed2[PV_SEED_BYTES];
    std::memcpy(seed2, ts::test_seed(), PV_SEED_BYTES);
    seed2[0] ^= 1;
    ts::Bytes h6(PV_HASH_BYTES);
    CHECK_EQ(pv_hash_deterministic(seed2, PV_SEED_BYTES, length, h6.data(), h6.size()), PV_OK);
    CHECK(h6 != h1);
    ts::Bytes h7(PV_HASH_BYTES);
    CHECK_EQ(pv_hash_deterministic(ts::test_seed(), PV_SEED_BYTES, 0, h7.data(), h7.size()), PV_OK);
    CHECK(h7 == ts::blake2b(ts::Bytes()));

    std::printf("1 MiB test stream BLAKE2b: %s\n", ts::hex(h1).c_str());
    return 0;
}
