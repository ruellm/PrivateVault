// Decrypts the committed golden vectors under the test key and compares the
// plaintext hash with the manifest. Any accidental format change fails here.
#include "test_support.h"

#include <cstdio>
#include <fstream>
#include <sstream>

#ifndef PV_FIXTURES_DIR
#error "PV_FIXTURES_DIR must be defined"
#endif

int main(int argc, char** argv) {
    CHECK_EQ(pv_init(), PV_OK);
    // An optional argument names a different directory (used for vectors
    // pulled from an Android device).
    std::string dir = argc > 1 ? argv[1] : PV_FIXTURES_DIR;
    std::ifstream manifest(ts::u8path(dir + "/manifest.txt"));
    CHECK(manifest.good());

    int count = 0;
    std::string line;
    while (std::getline(manifest, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream in(line);
        std::string name, hash_hex, id_hex;
        uint64_t length = 0;
        uint32_t chunk_size = 0;
        in >> name >> length >> chunk_size >> hash_hex >> id_hex;
        CHECK(!name.empty() && hash_hex.size() == 64 && id_hex.size() == 32);

        std::string path = dir + "/" + name + ".pvo";
        pv_source src{};
        CHECK_EQ(pv_file_source_open(path.c_str(), &src), PV_OK);
        pv_object_info info{};
        CHECK_EQ(pv_object_inspect(&src, &info), PV_OK);
        pv_file_source_close(&src);
        CHECK_EQ(info.chunk_size, chunk_size);
        CHECK_EQ(ts::id_hex(info.object_id), id_hex);

        // Decrypt into a hash sink with the expected ID enforced.
        CHECK_EQ(pv_file_source_open(path.c_str(), &src), PV_OK);
        pv_sink sink{};
        CHECK_EQ(pv_sink_blake2b_new(&sink), PV_OK);
        uint64_t got_len = 0;
        int rc = pv_object_decrypt(ts::test_vmk(), PV_VMK_BYTES, &src, &sink, info.object_id,
                                   &got_len);
        pv_file_source_close(&src);
        if (rc != PV_OK) {
            std::fprintf(stderr, "%s: %s\n", name.c_str(), pv_error_name(rc));
            return 1;
        }
        ts::Bytes h(PV_HASH_BYTES);
        CHECK_EQ(pv_sink_blake2b_finish(&sink, h.data(), h.size()), PV_OK);
        CHECK_EQ(got_len, length);
        CHECK_EQ(ts::hex(h), hash_hex);

        // The manifest hash is the hash of the deterministic stream of that length.
        ts::Bytes expect(PV_HASH_BYTES);
        CHECK_EQ(pv_hash_deterministic(ts::test_seed(), PV_SEED_BYTES, length, expect.data(),
                                       expect.size()), PV_OK);
        CHECK(expect == h);

        // The wrong key fails.
        CHECK_EQ(pv_file_source_open(path.c_str(), &src), PV_OK);
        ts::MemSink junk;
        pv_sink js = junk.as_sink();
        CHECK_EQ(pv_object_decrypt(ts::other_vmk().data(), PV_VMK_BYTES, &src, &js, nullptr,
                                   nullptr), PV_ERR_WRONG_VMK);
        pv_file_source_close(&src);
        ++count;
        std::printf("%s ok (%llu bytes)\n", name.c_str(), static_cast<unsigned long long>(length));
    }
    // The committed set has eight vectors; an external directory needs at least one.
    CHECK(count >= (argc > 1 ? 1 : 6));
    return 0;
}
