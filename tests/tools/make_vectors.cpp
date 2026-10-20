// Writes the golden vectors for format version 1 into a directory:
// objects encrypted under the test VMK with plaintext from the deterministic
// source, plus manifest.txt. Run once; the outputs are committed under
// tests/fixtures/v1. Regenerating produces different bytes (fresh keys and
// nonces) with the same plaintext.
//
//   make_vectors <output_dir>            all Phase 1 vectors and the item vectors
//   make_vectors <output_dir> --items    only the item vectors; other manifest
//                                        lines are kept
//   make_vectors <output_dir> --envelope3   only the provider 3 envelope vectors
//                                        (envelope3_*.key and envelope3.txt);
//                                        manifest.txt and the objects are kept
//   make_vectors <output_dir> --video-index  only the video index vectors (Phase 3C:
//                                        video_index_*.bin, item_video_index.pvo,
//                                        video_index.txt); everything else is kept
//
// Manifest columns: name length chunk_size blake2b object_id media_type
// rotation width height created_unix_ms payload_len. For an item vector the
// plaintext is the 48-byte item header followed by payload_len deterministic
// bytes, length is the whole plaintext, and blake2b is over the payload only.
// For a plain vector the last six columns are "-".
#include "envelope3_builder.h"
#include "pv/pv.h"

#include <sodium.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace {

const char* kVmk = "PRIVATEVAULT TEST ONLY VMK  0001";
const char* kSeed = "PRIVATEVAULT TEST ONLY SEED 0001";

struct Spec {
    const char* name;
    uint64_t length;
    uint32_t chunk_size;
};

struct ItemSpec {
    const char* name;
    uint64_t payload_len;
    uint32_t chunk_size;
    uint16_t media_type;
    uint16_t rotation;
    uint32_t width;
    uint32_t height;
};

// 2026-10-13T00:00:00Z, fixed so the vectors are reproducible.
constexpr uint64_t kCreated = 1791849600000ull;

// Object IDs are fixed per vector so the manifest can name them.
void fixed_id(const char* name, uint8_t* id) {
    uint8_t h[32];
    crypto_generichash(h, sizeof(h), reinterpret_cast<const unsigned char*>(name),
                       std::strlen(name), nullptr, 0);
    std::memcpy(id, h, PV_OBJECT_ID_BYTES);
}

std::string hex(const uint8_t* b, size_t n) {
    std::string s(2 * n + 1, '\0');
    sodium_bin2hex(s.data(), s.size(), b, n);
    s.resize(2 * n);
    return s;
}

const uint8_t* vmk() { return reinterpret_cast<const uint8_t*>(kVmk); }
const uint8_t* seed() { return reinterpret_cast<const uint8_t*>(kSeed); }

int write_object(const std::string& path, pv_source* src, uint32_t chunk_size, const uint8_t* id) {
    pv_file_delete(path.c_str());
    pv_sink sink{};
    if (pv_file_sink_create(path.c_str(), &sink) != PV_OK) {
        std::fprintf(stderr, "cannot create %s\n", path.c_str());
        return 2;
    }
    int rc = pv_object_encrypt(vmk(), PV_VMK_BYTES, src, &sink, chunk_size, id);
    if (rc != PV_OK) {
        pv_file_sink_abort(&sink);
        std::fprintf(stderr, "%s: %s\n", path.c_str(), pv_error_name(rc));
        return 2;
    }
    return pv_file_sink_commit(&sink) == PV_OK ? 0 : 2;
}

int plain_line(const std::string& dir, const Spec& s, std::string& line) {
    uint8_t id[PV_OBJECT_ID_BYTES];
    fixed_id(s.name, id);
    pv_source src{};
    if (pv_source_deterministic_new(seed(), PV_SEED_BYTES, s.length, &src) != PV_OK) {
        return 2;
    }
    int rc = write_object(dir + "/" + s.name + ".pvo", &src, s.chunk_size, id);
    pv_source_deterministic_free(&src);
    if (rc != 0) {
        return rc;
    }
    uint8_t h[PV_HASH_BYTES];
    if (pv_hash_deterministic(seed(), PV_SEED_BYTES, s.length, h, sizeof(h)) != PV_OK) {
        return 2;
    }
    char buf[256];
    std::snprintf(buf, sizeof(buf), "%s %llu %u %s %s - - - - - -\n", s.name,
                  static_cast<unsigned long long>(s.length), s.chunk_size,
                  hex(h, sizeof(h)).c_str(), hex(id, sizeof(id)).c_str());
    line = buf;
    return 0;
}

int item_line(const std::string& dir, const ItemSpec& s, std::string& line) {
    uint8_t id[PV_OBJECT_ID_BYTES];
    fixed_id(s.name, id);
    pv_item_header h{};
    h.item_version = PV_ITEM_VERSION;
    h.header_len = PV_ITEM_HEADER_BYTES;
    h.media_type = s.media_type;
    h.rotation_degrees = s.rotation;
    h.width = s.width;
    h.height = s.height;
    h.created_unix_ms = kCreated;
    h.payload_len = s.payload_len;
    uint8_t raw[PV_ITEM_HEADER_BYTES];
    size_t raw_len = 0;
    if (pv_item_header_encode(&h, raw, sizeof(raw), &raw_len) != PV_OK) {
        return 2;
    }
    // The payload is generated into memory so the memory source can present
    // header and payload as two parts, as the Android capture path does.
    std::vector<uint8_t> payload(static_cast<size_t>(s.payload_len));
    pv_source det{};
    if (pv_source_deterministic_new(seed(), PV_SEED_BYTES, s.payload_len, &det) != PV_OK) {
        return 2;
    }
    size_t pos = 0;
    while (pos < payload.size()) {
        size_t n = 0;
        if (det.read(det.ctx, payload.data() + pos, payload.size() - pos, &n) != 0 || n == 0) {
            return 2;
        }
        pos += n;
    }
    pv_source_deterministic_free(&det);
    const uint8_t* parts[] = {raw, payload.data()};
    size_t lens[] = {raw_len, payload.size()};
    pv_source src{};
    if (pv_source_memory_new(parts, lens, 2, &src) != PV_OK) {
        return 2;
    }
    int rc = write_object(dir + "/" + s.name + ".pvo", &src, s.chunk_size, id);
    pv_source_memory_free(&src);
    if (rc != 0) {
        return rc;
    }
    uint8_t hash[PV_HASH_BYTES];
    if (pv_hash_deterministic(seed(), PV_SEED_BYTES, s.payload_len, hash, sizeof(hash)) != PV_OK) {
        return 2;
    }
    char buf[320];
    std::snprintf(buf, sizeof(buf), "%s %llu %u %s %s %u %u %u %u %llu %llu\n", s.name,
                  static_cast<unsigned long long>(raw_len + s.payload_len), s.chunk_size,
                  hex(hash, sizeof(hash)).c_str(), hex(id, sizeof(id)).c_str(), s.media_type,
                  s.rotation, s.width, s.height, static_cast<unsigned long long>(kCreated),
                  static_cast<unsigned long long>(s.payload_len));
    line = buf;
    return 0;
}

// Provider 3 envelope vectors (Phase 3A). The Keystore step cannot run on
// Windows, so the vectors fix the KEK instead of deriving it. The test PIN,
// salt, and parameters are recorded with the Argon2id output so both
// platforms can check their stretch against the same answer.
const char* kKek = "PRIVATEVAULT TEST ONLY KEK  0001";
const char* kPin = "000000";
const char* kSalt = "PVTESTONLYSALT01";
const char* kAlias = "privatevault.pin.testonly";

int write_envelope_vectors(const std::string& dir) {
    const uint8_t* kek = reinterpret_cast<const uint8_t*>(kKek);
    const uint8_t* salt = reinterpret_cast<const uint8_t*>(kSalt);
    const uint32_t ops = PV_PIN_OPSLIMIT_MIN;
    const uint64_t mem = PV_PIN_MEMLIMIT_MIN;
    uint8_t k_pin[PV_KEK_BYTES];
    if (pv_pin_stretch(reinterpret_cast<const uint8_t*>(kPin), std::strlen(kPin), salt,
                       PV_PIN_SALT_BYTES, ops, mem, k_pin, sizeof(k_pin)) != PV_OK) {
        return 2;
    }
    e3::Bytes body = e3::pin_body(PV_MECHANISM_ANDROID_KEYSTORE_HMAC, kAlias, ops, mem, salt, nullptr);
    e3::Bytes single = e3::build(1, 3, 1, 0, {{PV_SLOT_PIN, body}}, vmk(), {kek});
    e3::Bytes unknown_body(24, 0x5a);
    e3::Bytes with_unknown = e3::build(1, 3, 2, 0, {{7, unknown_body}, {PV_SLOT_PIN, body}}, vmk(), {kek});
    // The library must open both.
    uint8_t back[PV_VMK_BYTES];
    for (const e3::Bytes* e : {&single, &with_unknown}) {
        if (pv_envelope3_open(e->data(), e->size(), kek, PV_KEK_BYTES, back, sizeof(back)) != PV_OK ||
            std::memcmp(back, vmk(), PV_VMK_BYTES) != 0) {
            std::fprintf(stderr, "envelope vector does not open\n");
            return 2;
        }
    }
    const std::string f1 = dir + "/envelope3_single.key";
    const std::string f2 = dir + "/envelope3_unknown_slot.key";
    pv_file_delete(f1.c_str());
    pv_file_delete(f2.c_str());
    if (pv_file_write_atomic(f1.c_str(), single.data(), single.size()) != PV_OK ||
        pv_file_write_atomic(f2.c_str(), with_unknown.data(), with_unknown.size()) != PV_OK) {
        return 2;
    }
    std::string text =
        "# TEST ONLY provider 3 envelope vectors. Nothing here is a secret.\n"
        "# Both .key files wrap the test VMK under the fixed KEK stand-in below (the\n"
        "# Keystore step is replaced by a constant for the vectors). k_pin_hex is the\n"
        "# Argon2id output for the test PIN, salt, and parameters; both platforms\n"
        "# check their stretch against it. Format: key value per line.\n";
    text += "kek_hex " + hex(kek, PV_KEK_BYTES) + "\n";
    text += std::string("pin ") + kPin + "\n";
    text += "salt_hex " + hex(salt, PV_PIN_SALT_BYTES) + "\n";
    text += "opslimit " + std::to_string(ops) + "\n";
    text += "memlimit " + std::to_string(mem) + "\n";
    text += "k_pin_hex " + hex(k_pin, sizeof(k_pin)) + "\n";
    text += std::string("alias ") + kAlias + "\n";
    text += "mechanism " + std::to_string(PV_MECHANISM_ANDROID_KEYSTORE_HMAC) + "\n";
    text += "vector envelope3_single.key 1\n";
    text += "vector envelope3_unknown_slot.key 2\n";
    const std::string tpath = dir + "/envelope3.txt";
    pv_file_delete(tpath.c_str());
    if (pv_file_write_atomic(tpath.c_str(), reinterpret_cast<const uint8_t*>(text.data()),
                             text.size()) != PV_OK) {
        return 2;
    }
    std::printf("wrote envelope3_single.key (%zu bytes), envelope3_unknown_slot.key (%zu bytes), envelope3.txt\n",
                single.size(), with_unknown.size());
    return 0;
}

// Phase 3C: video index v1 vectors (docs/PHASE3C_DESIGN.md Section 4.5).
// Three raw indexes and one item object whose payload is the three-segment
// index, under the test VMK. video_index.txt records the fields and the
// payload hash so both platforms check what they decode.
int write_video_index_vectors(const std::string& dir) {
    struct IndexSpec {
        const char* name;
        uint32_t segments;
        std::vector<uint32_t> keys_per_segment;
        uint16_t audio_codec;
        uint16_t audio_channels;
        uint32_t audio_rate;
    };
    const IndexSpec specs[] = {
        {"video_index_min", 1, {1}, PV_AUDIO_CODEC_NONE, 0, 0},
        {"video_index_three", 3, {3, 2, 4}, PV_AUDIO_CODEC_NONE, 0, 0},
        {"video_index_audio", 3, {3, 2, 4}, PV_AUDIO_CODEC_AAC, 2, 48000},
    };
    std::string text = "# Video index v1 vectors (Phase 3C). Synthetic values, TEST ONLY.\n"
                       "# vector <name> <segments> <keys_total> <total_duration_us> <audio_codec> <blake2b of the bytes>\n"
                       "# item <name> <object_id> <blake2b of the payload>: an item object (media_type 3) whose payload is video_index_three.bin\n";
    std::vector<uint8_t> three;
    for (const IndexSpec& sp : specs) {
        pv_video_index_header h{};
        h.version = PV_VIDEO_INDEX_VERSION;
        h.header_len = PV_VIDEO_INDEX_HEADER_BYTES;
        h.video_codec = PV_VIDEO_CODEC_H264;
        h.rotation_degrees = 90;
        h.width = 1600;
        h.height = 1200;
        h.frame_rate_millihz = 14200;
        h.segment_count = sp.segments;
        h.created_unix_ms = kCreated;
        h.audio_codec = sp.audio_codec;
        h.audio_channels = sp.audio_channels;
        h.audio_sample_rate = sp.audio_rate;
        std::vector<pv_video_segment> segs;
        std::vector<uint32_t> keys;
        uint64_t start = 0;
        for (uint32_t i = 0; i < sp.segments; ++i) {
            pv_video_segment g{};
            std::string seg_name = std::string(sp.name) + "_segment_" + std::to_string(i);
            fixed_id(seg_name.c_str(), g.object_id);
            g.start_us = start;
            g.duration_us = 3053000 + 1000 * i;   // the phone's segment length, with a small difference per segment
            g.byte_len = 1746306 + 262271 * i;
            g.key_count = sp.keys_per_segment[i];
            segs.push_back(g);
            for (uint32_t k = 0; k < g.key_count; ++k) {
                keys.push_back(k * (static_cast<uint32_t>(g.duration_us) / g.key_count));
            }
            start += g.duration_us;
        }
        h.total_duration_us = start;
        size_t size = 0;
        if (pv_video_index_size(&h, segs.data(), &size) != PV_OK) {
            std::fprintf(stderr, "%s: size failed\n", sp.name);
            return 2;
        }
        std::vector<uint8_t> out(size);
        size_t len = 0;
        int rc = pv_video_index_encode(&h, segs.data(), keys.data(), keys.size(), out.data(), out.size(), &len);
        if (rc != PV_OK) {
            std::fprintf(stderr, "%s: %s\n", sp.name, pv_error_name(rc));
            return 2;
        }
        const std::string path = dir + "/" + sp.name + ".bin";
        pv_file_delete(path.c_str());
        if (pv_file_write_atomic(path.c_str(), out.data(), len) != PV_OK) {
            std::fprintf(stderr, "cannot write %s\n", path.c_str());
            return 2;
        }
        uint8_t digest[PV_HASH_BYTES];
        crypto_generichash(digest, sizeof(digest), out.data(), len, nullptr, 0);
        char buf[256];
        std::snprintf(buf, sizeof(buf), "vector %s %u %zu %llu %u %s\n", sp.name, sp.segments, keys.size(),
                      static_cast<unsigned long long>(h.total_duration_us), sp.audio_codec, hex(digest, sizeof(digest)).c_str());
        text += buf;
        if (std::string(sp.name) == "video_index_three") {
            three = out;
        }
        std::printf("wrote %s (%zu bytes)\n", sp.name, len);
    }
    // The item object: header fields of a clip, payload = the three-segment index.
    uint8_t id[PV_OBJECT_ID_BYTES];
    fixed_id("item_video_index", id);
    pv_item_header ih{};
    ih.item_version = PV_ITEM_VERSION;
    ih.header_len = PV_ITEM_HEADER_BYTES;
    ih.media_type = PV_MEDIA_VIDEO_INDEX;
    ih.rotation_degrees = 90;
    ih.width = 1600;
    ih.height = 1200;
    ih.created_unix_ms = kCreated;
    ih.payload_len = three.size();
    uint8_t raw[PV_ITEM_HEADER_BYTES];
    size_t raw_len = 0;
    if (pv_item_header_encode(&ih, raw, sizeof(raw), &raw_len) != PV_OK) {
        return 2;
    }
    const uint8_t* parts[] = {raw, three.data()};
    const size_t lens[] = {raw_len, three.size()};
    pv_source src{};
    if (pv_source_memory_new(parts, lens, 2, &src) != PV_OK) {
        return 2;
    }
    int rc = write_object(dir + "/item_video_index.pvo", &src, PV_DEFAULT_CHUNK_SIZE, id);
    pv_source_memory_free(&src);
    if (rc != 0) {
        return rc;
    }
    uint8_t digest[PV_HASH_BYTES];
    crypto_generichash(digest, sizeof(digest), three.data(), three.size(), nullptr, 0);
    text += "item item_video_index " + hex(id, sizeof(id)) + " " + hex(digest, sizeof(digest)) + "\n";
    const std::string tpath = dir + "/video_index.txt";
    pv_file_delete(tpath.c_str());
    if (pv_file_write_atomic(tpath.c_str(), reinterpret_cast<const uint8_t*>(text.data()), text.size()) != PV_OK) {
        return 2;
    }
    std::printf("wrote item_video_index.pvo, video_index.txt\n");
    return 0;
}

std::string first_word(const std::string& line) {
    std::istringstream in(line);
    std::string w;
    in >> w;
    return w;
}

int field_count(const std::string& line) {
    std::istringstream in(line);
    std::string w;
    int n = 0;
    while (in >> w) ++n;
    return n;
}

}  // namespace

int main(int argc, char** argv) {
    const bool envelope_only = argc == 3 && std::strcmp(argv[2], "--envelope3") == 0;
    const bool index_only = argc == 3 && std::strcmp(argv[2], "--video-index") == 0;
    if (argc < 2 || argc > 3 ||
        (argc == 3 && std::strcmp(argv[2], "--items") != 0 && !envelope_only && !index_only)) {
        std::fprintf(stderr, "usage: make_vectors <output_dir> [--items|--envelope3|--video-index]\n");
        return 1;
    }
    if (pv_init() != PV_OK) {
        return 2;
    }
    const std::string dir = argv[1];
    if (envelope_only) {
        return write_envelope_vectors(dir);
    }
    if (index_only) {
        return write_video_index_vectors(dir);
    }
    const bool items_only = argc == 3;
    const uint32_t c = PV_MIN_CHUNK_SIZE;
    const Spec specs[] = {
        {"empty", 0, c},
        {"one_byte", 1, c},
        {"chunk_minus_1", c - 1, c},
        {"chunk_exact", c, c},
        {"chunk_plus_1", c + 1, c},
        {"three_chunks", 3 * c + 100, c},
        {"two_chunks_exact", 2 * c, c},
        {"default_chunk_100b", 100, PV_DEFAULT_CHUNK_SIZE},
    };
    const ItemSpec item_specs[] = {
        {"item_jpeg_small", 100, c, PV_MEDIA_JPEG, 90, 640, 480},
        {"item_jpeg_chunk_plus_1", c + 1 - PV_ITEM_HEADER_BYTES, c, PV_MEDIA_JPEG, 0, 1280, 960},
        {"item_jpeg_empty_payload", 0, c, PV_MEDIA_JPEG, 180, 1, 1},
        {"item_video_segment", 200000, PV_DEFAULT_CHUNK_SIZE, PV_MEDIA_VIDEO_SEGMENT, 270, 1280, 720},
    };

    std::vector<std::string> lines;
    const std::string mpath = dir + "/manifest.txt";
    if (items_only) {
        // Keep the existing non-item lines, padding Phase 1 lines to the new
        // column count.
        std::ifstream in(mpath);
        std::string line;
        while (std::getline(in, line)) {
            if (line.empty() || line[0] == '#') continue;
            std::string name = first_word(line);
            bool is_item = false;
            for (const ItemSpec& s : item_specs) {
                if (name == s.name) is_item = true;
            }
            if (is_item) continue;
            if (field_count(line) == 5) line += " - - - - - -";
            lines.push_back(line + "\n");
        }
    } else {
        for (const Spec& s : specs) {
            std::string line;
            int rc = plain_line(dir, s, line);
            if (rc != 0) return rc;
            lines.push_back(line);
        }
    }
    for (const ItemSpec& s : item_specs) {
        std::string line;
        int rc = item_line(dir, s, line);
        if (rc != 0) return rc;
        lines.push_back(line);
    }
    std::string manifest =
        "# name length chunk_size blake2b object_id media_type rotation width height created_unix_ms payload_len\n"
        "# plaintext = deterministic stream for seed 'PRIVATEVAULT TEST ONLY SEED 0001'\n"
        "# key = 'PRIVATEVAULT TEST ONLY VMK  0001' (TEST ONLY, not a secret)\n"
        "# item vectors: plaintext = 48-byte item header + payload_len deterministic bytes;\n"
        "#   length is the whole plaintext, blake2b is over the payload only. Plain vectors: '-'.\n";
    for (const std::string& l : lines) manifest += l;
    pv_file_delete(mpath.c_str());
    if (pv_file_write_atomic(mpath.c_str(), reinterpret_cast<const uint8_t*>(manifest.data()),
                             manifest.size()) != PV_OK) {
        return 2;
    }
    std::printf("wrote %zu manifest lines to %s\n", lines.size(), dir.c_str());
    return write_envelope_vectors(dir);
}
