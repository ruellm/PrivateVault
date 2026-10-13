// Writes the golden vectors for format version 1 into a directory:
// objects encrypted under the test VMK with plaintext from the deterministic
// source, plus manifest.txt. Run once; the outputs are committed under
// tests/fixtures/v1. Regenerating produces different bytes (fresh keys and
// nonces) with the same plaintext.
//
//   make_vectors <output_dir>            all Phase 1 vectors and the item vectors
//   make_vectors <output_dir> --items    only the item vectors; other manifest
//                                        lines are kept
//
// Manifest columns: name length chunk_size blake2b object_id media_type
// rotation width height created_unix_ms payload_len. For an item vector the
// plaintext is the 48-byte item header followed by payload_len deterministic
// bytes, length is the whole plaintext, and blake2b is over the payload only.
// For a plain vector the last six columns are "-".
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
    if (argc < 2 || argc > 3 || (argc == 3 && std::strcmp(argv[2], "--items") != 0)) {
        std::fprintf(stderr, "usage: make_vectors <output_dir> [--items]\n");
        return 1;
    }
    if (pv_init() != PV_OK) {
        return 2;
    }
    const std::string dir = argv[1];
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
    return 0;
}
