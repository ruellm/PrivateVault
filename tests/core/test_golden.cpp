// Decrypts the committed golden vectors under the test key and compares the
// plaintext hash with the manifest. Any accidental format change fails here.
// Item vectors (manifest columns 6 to 11 present) go through the item sink
// and must report the recorded header fields. The provider 3 envelope
// vectors (envelope3.txt, when present) must open with the recorded KEK
// stand-in to the test VMK, and the test PIN must stretch to the recorded
// k_pin.
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

        // The viewer path (Phase 3B): the vector copied into a vault directory
        // reports its exact plaintext length without decrypting and decrypts
        // into a buffer of that length with the same payload hash.
        {
            const std::string vdir = "golden_vault";
            ts::remove_tree(vdir);
            CHECK_EQ(pv_vault_create(vdir.c_str()), PV_OK);
            ts::write_file(vdir + "/objects/" + id_hex + ".pvo", ts::read_file(path));
            pv_vault* v = nullptr;
            CHECK_EQ(pv_vault_open(vdir.c_str(), ts::test_vmk(), PV_VMK_BYTES, &v), PV_OK);
            uint64_t size = 0;
            CHECK_EQ(pv_object_plaintext_size(v, info.object_id, &size), PV_OK);
            CHECK_EQ(size, length);
            ts::Bytes buf(static_cast<size_t>(length) + 1, 0xaa);
            uint64_t got = 0;
            CHECK_EQ(pv_vault_decrypt_to_buffer(v, info.object_id, buf.data(), length, &got, nullptr), PV_OK);
            CHECK_EQ(got, length);
            CHECK_EQ(buf[static_cast<size_t>(length)], 0xaa);
            size_t skip = is_item ? PV_ITEM_HEADER_BYTES : 0;
            ts::Bytes payload(buf.begin() + static_cast<ptrdiff_t>(skip), buf.begin() + static_cast<ptrdiff_t>(length));
            CHECK_EQ(ts::hex(ts::blake2b(payload)), hash_hex);
            pv_vault_close(v);
            ts::remove_tree(vdir);
        }
        ++count;
        std::printf("%s ok (%llu bytes%s)\n", name.c_str(), static_cast<unsigned long long>(length),
                    is_item ? ", item" : "");
    }
    // The committed set has eight plain and four item vectors; an external
    // directory needs at least one vector.
    CHECK(count >= (argc > 1 ? 1 : 12));
    if (argc == 1) CHECK(item_count >= 4);

    // Video index vectors (Phase 3C). An external directory has none.
    std::ifstream vi_manifest(ts::u8path(dir + "/video_index.txt"));
    if (argc == 1) CHECK(vi_manifest.good());
    if (vi_manifest.good()) {
        int vectors = 0;
        int items = 0;
        while (std::getline(vi_manifest, line)) {
            if (line.empty() || line[0] == '#') continue;
            std::istringstream in(line);
            std::string kind, name;
            in >> kind >> name;
            if (kind == "vector") {
                unsigned segments = 0, audio = 0;
                size_t keys_total = 0;
                unsigned long long total = 0;
                std::string hash_hex;
                in >> segments >> keys_total >> total >> audio >> hash_hex;
                CHECK(in);
                ts::Bytes b = ts::read_file(dir + "/" + name + ".bin");
                CHECK_EQ(ts::hex(ts::blake2b(b)), hash_hex);
                pv_video_index_header h{};
                size_t kt = 0;
                CHECK_EQ(pv_video_index_decode_header(b.data(), b.size(), &h, &kt), PV_OK);
                CHECK_EQ(h.segment_count, segments);
                CHECK_EQ(kt, keys_total);
                CHECK_EQ(h.total_duration_us, total);
                CHECK_EQ(h.audio_codec, audio);
                std::vector<pv_video_segment> segs(segments);
                std::vector<uint32_t> keys(keys_total);
                size_t kl = 0;
                CHECK_EQ(pv_video_index_decode(b.data(), b.size(), &h, segs.data(), segs.size(), keys.data(), keys.size(), &kl), PV_OK);
                CHECK_EQ(kl, keys_total);
                CHECK_EQ(segs[0].start_us, 0u);
                CHECK_EQ(keys[0], 0u);
                // Re-encoding gives the same bytes.
                ts::Bytes again(b.size());
                size_t len = 0;
                CHECK_EQ(pv_video_index_encode(&h, segs.data(), keys.data(), keys.size(), again.data(), again.size(), &len), PV_OK);
                CHECK(again == b);
                ++vectors;
                std::printf("%s ok (%zu bytes, %u segments, %zu keys)\n", name.c_str(), b.size(), segments, keys_total);
            } else if (kind == "item") {
                std::string id_hex, hash_hex;
                in >> id_hex >> hash_hex;
                CHECK(in && id_hex.size() == 32 && hash_hex.size() == 64);
                uint8_t id[PV_OBJECT_ID_BYTES];
                CHECK_EQ(pv_object_id_from_hex(id_hex.c_str(), id), PV_OK);
                // Decrypt through the item sink into memory and decode the payload as an index.
                pv_source src{};
                CHECK_EQ(pv_file_source_open((dir + "/" + name + ".pvo").c_str(), &src), PV_OK);
                ts::MemSink payload;
                pv_sink ps = payload.as_sink();
                pv_sink item{};
                CHECK_EQ(pv_sink_item_new(&ps, nullptr, &item), PV_OK);
                uint64_t got_len = 0;
                CHECK_EQ(pv_object_decrypt(ts::test_vmk(), PV_VMK_BYTES, &src, &item, id, &got_len), PV_OK);
                pv_file_source_close(&src);
                pv_item_header ih{};
                CHECK_EQ(pv_sink_item_finish(&item, got_len, &ih), PV_OK);
                CHECK_EQ(ih.media_type, PV_MEDIA_VIDEO_INDEX);
                CHECK_EQ(ih.payload_len, payload.data.size());
                CHECK_EQ(ts::hex(ts::blake2b(payload.data)), hash_hex);
                pv_video_index_header h{};
                size_t kt = 0;
                CHECK_EQ(pv_video_index_decode_header(payload.data.data(), payload.data.size(), &h, &kt), PV_OK);
                CHECK_EQ(h.segment_count, 3u);
                CHECK_EQ(kt, 9u);
                ++items;
                std::printf("%s ok (item, index of %u segments)\n", name.c_str(), h.segment_count);
            }
        }
        CHECK_EQ(vectors, 3);
        CHECK_EQ(items, 1);
    }

    // Envelope vectors. An external directory (Android export) has none.
    std::ifstream env_manifest(ts::u8path(dir + "/envelope3.txt"));
    if (argc == 1) CHECK(env_manifest.good());
    if (env_manifest.good()) {
        std::string kek_hex, pin, salt_hex, k_pin_hex, alias;
        uint64_t ops = 0, mem = 0;
        unsigned mechanism = 0;
        std::vector<std::pair<std::string, unsigned>> vectors;
        while (std::getline(env_manifest, line)) {
            if (line.empty() || line[0] == '#') continue;
            std::istringstream in(line);
            std::string key;
            in >> key;
            if (key == "kek_hex") in >> kek_hex;
            else if (key == "pin") in >> pin;
            else if (key == "salt_hex") in >> salt_hex;
            else if (key == "opslimit") in >> ops;
            else if (key == "memlimit") in >> mem;
            else if (key == "k_pin_hex") in >> k_pin_hex;
            else if (key == "alias") in >> alias;
            else if (key == "mechanism") in >> mechanism;
            else if (key == "vector") {
                std::string name;
                unsigned slots = 0;
                in >> name >> slots;
                vectors.emplace_back(name, slots);
            }
        }
        CHECK(kek_hex.size() == 64 && salt_hex.size() == 32 && k_pin_hex.size() == 64);
        CHECK(!pin.empty() && !alias.empty() && vectors.size() == 2);
        ts::Bytes kek(PV_KEK_BYTES), salt(PV_PIN_SALT_BYTES), k_pin(PV_KEK_BYTES);
        CHECK_EQ(sodium_hex2bin(kek.data(), kek.size(), kek_hex.c_str(), kek_hex.size(), nullptr, nullptr, nullptr), 0);
        CHECK_EQ(sodium_hex2bin(salt.data(), salt.size(), salt_hex.c_str(), salt_hex.size(), nullptr, nullptr, nullptr), 0);
        CHECK_EQ(sodium_hex2bin(k_pin.data(), k_pin.size(), k_pin_hex.c_str(), k_pin_hex.size(), nullptr, nullptr, nullptr), 0);
        // The stretch of the test PIN equals the recorded answer.
        ts::Bytes got(PV_KEK_BYTES);
        CHECK_EQ(pv_pin_stretch(reinterpret_cast<const uint8_t*>(pin.data()), pin.size(), salt.data(),
                                salt.size(), ops, mem, got.data(), got.size()), PV_OK);
        CHECK(got == k_pin);
        for (const auto& v : vectors) {
            ts::Bytes env = ts::read_file(dir + "/" + v.first);
            pv_envelope3_info info{};
            CHECK_EQ(pv_envelope3_parse(env.data(), env.size(), &info), PV_OK);
            CHECK_EQ(info.slot_count, v.second);
            CHECK_EQ(info.pin_slot_count, 1);
            CHECK_EQ(std::string(info.pin.alias), alias);
            CHECK_EQ(info.pin.mechanism, mechanism);
            CHECK_EQ(info.pin.opslimit, ops);
            CHECK_EQ(info.pin.memlimit, mem);
            CHECK(ts::Bytes(info.pin.salt, info.pin.salt + PV_PIN_SALT_BYTES) == salt);
            ts::Bytes vmk(PV_VMK_BYTES);
            CHECK_EQ(pv_envelope3_open(env.data(), env.size(), kek.data(), kek.size(), vmk.data(), vmk.size()), PV_OK);
            CHECK_EQ(std::memcmp(vmk.data(), ts::test_vmk(), PV_VMK_BYTES), 0);
            CHECK_EQ(pv_envelope3_open(env.data(), env.size(), ts::other_vmk().data(), PV_KEK_BYTES, vmk.data(), vmk.size()),
                     PV_ERR_ENVELOPE_UNWRAP_FAILED);
            // The VMK that came out decrypts an object vector.
            ts::Bytes obj = ts::read_file(dir + "/one_byte.pvo");
            ts::Bytes plain;
            CHECK_EQ(pv_envelope3_open(env.data(), env.size(), kek.data(), kek.size(), vmk.data(), vmk.size()), PV_OK);
            CHECK_EQ(ts::decrypt_mem(vmk.data(), obj, plain), PV_OK);
            CHECK_EQ(plain.size(), 1u);
            std::printf("%s ok (%u slots)\n", v.first.c_str(), v.second);
        }
    }
    return 0;
}
