// 256 MB round trip through a vault directory. No plaintext file is written:
// the input is the deterministic source and the output goes to a hash sink.
// CTest label: large. The object file is removed afterwards.
#include "test_support.h"

#include <chrono>
#include <cstdio>

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    const uint64_t length = 256ull << 20;
    const std::string vault = "vault_large";
    ts::remove_tree(vault);
    CHECK_EQ(pv_vault_create(vault.c_str()), PV_OK);
    pv_vault* v = nullptr;
    CHECK_EQ(pv_vault_open(vault.c_str(), ts::test_vmk(), PV_VMK_BYTES, &v), PV_OK);

    pv_source src{};
    CHECK_EQ(pv_source_deterministic_new(ts::test_seed(), PV_SEED_BYTES, length, &src), PV_OK);
    auto t0 = std::chrono::steady_clock::now();
    uint8_t id[PV_OBJECT_ID_BYTES];
    CHECK_EQ(pv_vault_encrypt(v, &src, 0, id), PV_OK);
    pv_source_deterministic_free(&src);
    auto t1 = std::chrono::steady_clock::now();

    pv_sink sink{};
    CHECK_EQ(pv_sink_blake2b_new(&sink), PV_OK);
    uint64_t len = 0;
    CHECK_EQ(pv_vault_decrypt(v, id, &sink, &len), PV_OK);
    auto t2 = std::chrono::steady_clock::now();
    CHECK_EQ(len, length);
    ts::Bytes h(PV_HASH_BYTES);
    CHECK_EQ(pv_sink_blake2b_finish(&sink, h.data(), h.size()), PV_OK);
    ts::Bytes expect(PV_HASH_BYTES);
    CHECK_EQ(pv_hash_deterministic(ts::test_seed(), PV_SEED_BYTES, length, expect.data(),
                                   expect.size()), PV_OK);
    CHECK(h == expect);

    char path[1024];
    CHECK_EQ(pv_vault_object_path(v, id, path, sizeof(path)), PV_OK);
    std::error_code ec;
    uint64_t file_size = std::filesystem::file_size(ts::u8path(path), ec);
    CHECK_EQ(file_size, PV_HEADER_BYTES + length + 17 * (length / PV_DEFAULT_CHUNK_SIZE + 1));

    pv_vault_close(v);
    ts::remove_tree(vault);
    auto ms = [](auto a, auto b) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count();
    };
    std::printf("256 MB: encrypt %lld ms, decrypt+hash %lld ms\n",
                static_cast<long long>(ms(t0, t1)), static_cast<long long>(ms(t1, t2)));
    return 0;
}
