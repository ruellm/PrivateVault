// Decrypts the committed golden vectors under the test key and compares the
// plaintext hash with the manifest. Any accidental format change fails here.
// Item vectors (manifest columns 6 to 11 present) go through the item sink
// and must report the recorded header fields.
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
    int item_count = 0;
    std::string line;
    while (std::getline(manifest, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream in(line);
        std::string name, hash_hex, id_hex;
        uint64_t length = 0;
        uint32_t chunk_size = 0;
        in >> name >> length >> chunk_size >> hash_hex >> id_hex;
        CHECK(!name.empty() && hash_hex.size() == 64 && id_hex.size() == 32);
        // Optional item columns.
        std::string media_type;
        bool is_item = false;
        pv_item_header expect_h{};
        if (in >> media_type && media_type != "-") {
            is_item = true;
            unsigned mt = 0, rot = 0;
            unsigned long long created = 0, payload = 0;
            std::istringstream mts(media_type);
            mts >> mt;
            in >> rot >> expect_h.width >> expect_h.height >> created >> payload;
            CHECK(in);
            expect_h.media_type = static_cast<uint16_t>(mt);
            expect_h.rotation_degrees = static_cast<uint16_t>(rot);
            expect_h.created_unix_ms = created;
            expect_h.payload_len = payload;
        }

        std::string path = dir + "/" + name + ".pvo";
        pv_source src{};
        CHECK_EQ(pv_file_source_open(path.c_str(), &src), PV_OK);
        pv_object_info info{};
        CHECK_EQ(pv_object_inspect(&src, &info), PV_OK);
        pv_file_source_close(&src);
        CHECK_EQ(info.chunk_size, chunk_size);
        CHECK_EQ(ts::id_hex(info.object_id), id_hex);

        // Decrypt into a hash sink with the expected ID enforced. Item vectors
        // go through the item sink, so the hash covers the payload only.
        CHECK_EQ(pv_file_source_open(path.c_str(), &src), PV_OK);
        pv_sink hash{};
        CHECK_EQ(pv_sink_blake2b_new(&hash), PV_OK);
        pv_sink item{};
        pv_sink* dst = &hash;
        if (is_item) {
            CHECK_EQ(pv_sink_item_new(&hash, pv_sink_blake2b_abort, &item), PV_OK);
            dst = &item;
        }
        uint64_t got_len = 0;
        int rc = pv_object_decrypt(ts::test_vmk(), PV_VMK_BYTES, &src, dst, info.object_id,
                                   &got_len);
        pv_file_source_close(&src);
        if (rc != PV_OK) {
            std::fprintf(stderr, "%s: %s\n", name.c_str(), pv_error_name(rc));
            return 1;
        }
        CHECK_EQ(got_len, length);
        uint64_t hashed_len = length;
        if (is_item) {
            pv_item_header h{};
            int item_rc = pv_sink_item_finish(&item, got_len, &h);
            if (item_rc != PV_OK) {
                std::fprintf(stderr, "%s: item %s\n", name.c_str(), pv_error_name(item_rc));
                return 1;
            }
            CHECK_EQ(h.item_version, PV_ITEM_VERSION);
            CHECK_EQ(h.header_len, PV_ITEM_HEADER_BYTES);
            CHECK_EQ(h.media_type, expect_h.media_type);
            CHECK_EQ(h.rotation_degrees, expect_h.rotation_degrees);
            CHECK_EQ(h.width, expect_h.width);
            CHECK_EQ(h.height, expect_h.height);
            CHECK_EQ(h.created_unix_ms, expect_h.created_unix_ms);
            CHECK_EQ(h.payload_len, expect_h.payload_len);
            CHECK_EQ(h.header_len + h.payload_len, length);
            hashed_len = h.payload_len;
            ++item_count;
        }
        ts::Bytes h(PV_HASH_BYTES);
        CHECK_EQ(pv_sink_blake2b_finish(&hash, h.data(), h.size()), PV_OK);
        CHECK_EQ(ts::hex(h), hash_hex);

        // The manifest hash is the hash of the deterministic stream of that length.
        ts::Bytes expect(PV_HASH_BYTES);
        CHECK_EQ(pv_hash_deterministic(ts::test_seed(), PV_SEED_BYTES, hashed_len, expect.data(),
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
        std::printf("%s ok (%llu bytes%s)\n", name.c_str(), static_cast<unsigned long long>(length),
                    is_item ? ", item" : "");
    }
    // The committed set has eight plain and four item vectors; an external
    // directory needs at least one vector.
    CHECK(count >= (argc > 1 ? 1 : 12));
    if (argc == 1) CHECK(item_count >= 4);
    return 0;
}
