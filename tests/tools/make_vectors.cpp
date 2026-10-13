// Writes the golden vectors for format version 1 into a directory:
// objects encrypted under the test VMK with plaintext from the deterministic
// source, plus manifest.txt. Run once; the outputs are committed under
// tests/fixtures/v1. Regenerating produces different bytes (fresh keys and
// nonces) with the same plaintext.
//
//   make_vectors <output_dir>
#include "pv/pv.h"

#include <sodium.h>

#include <cstdio>
#include <cstring>
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

}  // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::fprintf(stderr, "usage: make_vectors <output_dir>\n");
        return 1;
    }
    if (pv_init() != PV_OK) {
        return 2;
    }
    const std::string dir = argv[1];
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
    std::string manifest =
        "# name length chunk_size blake2b(plaintext) object_id\n"
        "# plaintext = deterministic stream for seed 'PRIVATEVAULT TEST ONLY SEED 0001'\n"
        "# key = 'PRIVATEVAULT TEST ONLY VMK  0001' (TEST ONLY, not a secret)\n";
    for (const Spec& s : specs) {
        uint8_t id[PV_OBJECT_ID_BYTES];
        fixed_id(s.name, id);
        pv_source src{};
        if (pv_source_deterministic_new(reinterpret_cast<const uint8_t*>(kSeed), PV_SEED_BYTES,
                                        s.length, &src) != PV_OK) {
            return 2;
        }
        std::string path = dir + "/" + s.name + ".pvo";
        pv_file_delete(path.c_str());
        pv_sink sink{};
        if (pv_file_sink_create(path.c_str(), &sink) != PV_OK) {
            std::fprintf(stderr, "cannot create %s\n", path.c_str());
            return 2;
        }
        int rc = pv_object_encrypt(reinterpret_cast<const uint8_t*>(kVmk), PV_VMK_BYTES, &src,
                                   &sink, s.chunk_size, id);
        pv_source_deterministic_free(&src);
        if (rc != PV_OK) {
            pv_file_sink_abort(&sink);
            std::fprintf(stderr, "%s: %s\n", s.name, pv_error_name(rc));
            return 2;
        }
        if (pv_file_sink_commit(&sink) != PV_OK) {
            return 2;
        }
        uint8_t h[PV_HASH_BYTES];
        if (pv_hash_deterministic(reinterpret_cast<const uint8_t*>(kSeed), PV_SEED_BYTES,
                                  s.length, h, sizeof(h)) != PV_OK) {
            return 2;
        }
        char line[256];
        std::snprintf(line, sizeof(line), "%s %llu %u %s %s\n", s.name,
                      static_cast<unsigned long long>(s.length), s.chunk_size,
                      hex(h, sizeof(h)).c_str(), hex(id, sizeof(id)).c_str());
        manifest += line;
    }
    std::string mpath = dir + "/manifest.txt";
    pv_file_delete(mpath.c_str());
    if (pv_file_write_atomic(mpath.c_str(), reinterpret_cast<const uint8_t*>(manifest.data()),
                             manifest.size()) != PV_OK) {
        return 2;
    }
    std::printf("wrote %zu vectors to %s\n", sizeof(specs) / sizeof(specs[0]), dir.c_str());
    return 0;
}
