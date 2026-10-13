// Sources and sinks over files, the deterministic test source, the hashing
// sink, small-file helpers, and hex helpers.
#include "fs.h"

namespace pv {
namespace {

// File source.
int file_read(void* ctx, uint8_t* buf, size_t cap, size_t* out_n) {
    return fs::read(static_cast<fs::File*>(ctx), buf, cap, out_n) == PV_OK ? 0 : -1;
}

// File sink. Keeps the path so abort can delete the file.
struct FileSink {
    fs::File* f;
    std::string path;
};

int file_write(void* ctx, const uint8_t* buf, size_t n) {
    FileSink* s = static_cast<FileSink*>(ctx);
    return fs::write(s->f, buf, n) == PV_OK ? 0 : -1;
}

// Deterministic source: ChaCha20-IETF keystream under the seed, zero nonce,
// block counter starting at 0. See docs/PHASE1_DESIGN.md Section 13.
struct DetSource {
    uint8_t key[crypto_stream_chacha20_ietf_KEYBYTES];
    uint64_t length;
    uint64_t offset;
};

constexpr size_t kBlock = 64;
constexpr uint64_t kMaxDetBytes = static_cast<uint64_t>(UINT32_MAX) * kBlock;

int det_read(void* ctx, uint8_t* buf, size_t cap, size_t* out_n) {
    DetSource* s = static_cast<DetSource*>(ctx);
    uint64_t remaining = s->length - s->offset;
    size_t n = remaining < cap ? static_cast<size_t>(remaining) : cap;
    *out_n = n;
    if (n == 0) {
        return 0;
    }
    static const uint8_t nonce[crypto_stream_chacha20_ietf_NONCEBYTES] = {0};
    uint64_t block = s->offset / kBlock;
    size_t skip = static_cast<size_t>(s->offset % kBlock);
    uint8_t* out = buf;
    size_t left = n;
    if (skip != 0) {
        uint8_t tmp[kBlock] = {0};
        if (crypto_stream_chacha20_ietf_xor_ic(tmp, tmp, kBlock, nonce,
                                               static_cast<uint32_t>(block), s->key) != 0) {
            return -1;
        }
        size_t take = kBlock - skip;
        if (take > left) {
            take = left;
        }
        std::memcpy(out, tmp + skip, take);
        out += take;
        left -= take;
        block += 1;
    }
    if (left > 0) {
        std::memset(out, 0, left);
        if (crypto_stream_chacha20_ietf_xor_ic(out, out, left, nonce,
                                               static_cast<uint32_t>(block), s->key) != 0) {
            return -1;
        }
    }
    s->offset += n;
    return 0;
}

// Hashing sink.
struct HashSink {
    crypto_generichash_state st;
};

int hash_write(void* ctx, const uint8_t* buf, size_t n) {
    HashSink* s = static_cast<HashSink*>(ctx);
    return crypto_generichash_update(&s->st, buf, n) == 0 ? 0 : -1;
}

}  // namespace
}  // namespace pv

extern "C" {

int pv_file_source_open(const char* path, pv_source* out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!path || !*path || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        pv::fs::File* f = nullptr;
        rc = pv::fs::open_read(path, &f);
        if (rc != PV_OK) {
            return rc;
        }
        out->ctx = f;
        out->read = pv::file_read;
        return PV_OK;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

void pv_file_source_close(pv_source* s) {
    if (s && s->ctx) {
        pv::fs::close(static_cast<pv::fs::File*>(s->ctx));
        s->ctx = nullptr;
        s->read = nullptr;
    }
}

int pv_file_sink_create(const char* path, pv_sink* out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!path || !*path || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        pv::fs::File* f = nullptr;
        rc = pv::fs::create_new(path, &f);
        if (rc != PV_OK) {
            return rc;
        }
        pv::FileSink* s = new (std::nothrow) pv::FileSink{f, path};
        if (!s) {
            pv::fs::close(f);
            pv::fs::remove_file(path);
            return PV_ERR_OUT_OF_MEMORY;
        }
        out->ctx = s;
        out->write = pv::file_write;
        return PV_OK;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_file_sink_commit(pv_sink* s) {
    if (!s || !s->ctx) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv::FileSink* fs_ = static_cast<pv::FileSink*>(s->ctx);
    int rc = pv::fs::flush(fs_->f);
    pv::fs::close(fs_->f);
    if (rc != PV_OK) {
        pv::fs::remove_file(fs_->path);
    }
    delete fs_;
    s->ctx = nullptr;
    s->write = nullptr;
    return rc;
}

void pv_file_sink_abort(pv_sink* s) {
    if (!s || !s->ctx) {
        return;
    }
    pv::FileSink* fs_ = static_cast<pv::FileSink*>(s->ctx);
    pv::fs::close(fs_->f);
    pv::fs::remove_file(fs_->path);
    delete fs_;
    s->ctx = nullptr;
    s->write = nullptr;
}

int pv_file_exists(const char* path, int* out_exists) {
    if (!path || !*path || !out_exists) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        bool present = false;
        int rc = pv::fs::exists(path, &present);
        *out_exists = present ? 1 : 0;
        return rc;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_file_read_all(const char* path, uint8_t* buf, size_t cap, size_t* out_len) {
    if (!path || !*path || !buf || !out_len) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        pv::fs::File* f = nullptr;
        int rc = pv::fs::open_read(path, &f);
        if (rc != PV_OK) {
            return rc;
        }
        size_t have = 0;
        for (;;) {
            if (have == cap) {
                uint8_t probe = 0;
                size_t n = 0;
                rc = pv::fs::read(f, &probe, 1, &n);
                if (rc == PV_OK && n > 0) {
                    rc = PV_ERR_IO;
                }
                break;
            }
            size_t n = 0;
            rc = pv::fs::read(f, buf + have, cap - have, &n);
            if (rc != PV_OK || n == 0) {
                break;
            }
            have += n;
        }
        pv::fs::close(f);
        *out_len = have;
        return rc;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_file_write_atomic(const char* path, const uint8_t* data, size_t len) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!path || !*path || (!data && len > 0)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        std::string final_path(path);
        std::string partial = final_path + ".partial";
        bool present = false;
        rc = pv::fs::exists(final_path, &present);
        if (rc != PV_OK) {
            return rc;
        }
        if (present) {
            return PV_ERR_DEST_EXISTS;
        }
        pv_sink sink{};
        rc = pv_file_sink_create(partial.c_str(), &sink);
        if (rc != PV_OK) {
            return rc;
        }
        rc = pv::write_all(&sink, data, len);
        if (rc != PV_OK) {
            pv_file_sink_abort(&sink);
            return rc;
        }
        rc = pv_file_sink_commit(&sink);
        if (rc != PV_OK) {
            pv::fs::remove_file(partial);
            return rc;
        }
        rc = pv::fs::rename_no_replace(partial, final_path);
        if (rc != PV_OK) {
            pv::fs::remove_file(partial);
        }
        return rc;
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_file_delete(const char* path) {
    if (!path || !*path) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::fs::remove_file(path);
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_source_deterministic_new(const uint8_t* seed, size_t seed_len, uint64_t length,
                                pv_source* out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!seed || seed_len != PV_SEED_BYTES || !out || length > pv::kMaxDetBytes) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv::DetSource* s = new (std::nothrow) pv::DetSource{};
    if (!s) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    std::memcpy(s->key, seed, PV_SEED_BYTES);
    s->length = length;
    s->offset = 0;
    out->ctx = s;
    out->read = pv::det_read;
    return PV_OK;
}

void pv_source_deterministic_free(pv_source* s) {
    if (s && s->ctx) {
        delete static_cast<pv::DetSource*>(s->ctx);
        s->ctx = nullptr;
        s->read = nullptr;
    }
}

int pv_sink_blake2b_new(pv_sink* out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv::HashSink* s = new (std::nothrow) pv::HashSink{};
    if (!s) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    if (crypto_generichash_init(&s->st, nullptr, 0, PV_HASH_BYTES) != 0) {
        delete s;
        return PV_ERR_INTERNAL;
    }
    out->ctx = s;
    out->write = pv::hash_write;
    return PV_OK;
}

int pv_sink_blake2b_finish(pv_sink* s, uint8_t* hash_out, size_t hash_len) {
    if (!s || !s->ctx || !hash_out || hash_len != PV_HASH_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv::HashSink* hs = static_cast<pv::HashSink*>(s->ctx);
    int rc = crypto_generichash_final(&hs->st, hash_out, hash_len) == 0 ? PV_OK
                                                                        : PV_ERR_INTERNAL;
    delete hs;
    s->ctx = nullptr;
    s->write = nullptr;
    return rc;
}

void pv_sink_blake2b_abort(pv_sink* s) {
    if (s && s->ctx) {
        delete static_cast<pv::HashSink*>(s->ctx);
        s->ctx = nullptr;
        s->write = nullptr;
    }
}

int pv_hash_deterministic(const uint8_t* seed, size_t seed_len, uint64_t length,
                          uint8_t* hash_out, size_t hash_len) {
    if (!hash_out || hash_len != PV_HASH_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_source src{};
    int rc = pv_source_deterministic_new(seed, seed_len, length, &src);
    if (rc != PV_OK) {
        return rc;
    }
    pv_sink sink{};
    rc = pv_sink_blake2b_new(&sink);
    if (rc != PV_OK) {
        pv_source_deterministic_free(&src);
        return rc;
    }
    try {
        std::vector<uint8_t> buf(65536);
        for (;;) {
            size_t n = 0;
            if (src.read(src.ctx, buf.data(), buf.size(), &n) != 0) {
                rc = PV_ERR_IO;
                break;
            }
            if (n == 0) {
                break;
            }
            if (sink.write(sink.ctx, buf.data(), n) != 0) {
                rc = PV_ERR_IO;
                break;
            }
        }
    } catch (const std::bad_alloc&) {
        rc = PV_ERR_OUT_OF_MEMORY;
    }
    pv_source_deterministic_free(&src);
    if (rc != PV_OK) {
        pv_sink_blake2b_abort(&sink);
        return rc;
    }
    return pv_sink_blake2b_finish(&sink, hash_out, hash_len);
}

int pv_object_id_to_hex(const uint8_t* object_id, char* hex_out, size_t cap) {
    if (!object_id || !hex_out || cap < 2 * PV_OBJECT_ID_BYTES + 1) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    sodium_bin2hex(hex_out, cap, object_id, PV_OBJECT_ID_BYTES);
    return PV_OK;
}

int pv_object_id_from_hex(const char* hex, uint8_t* object_id_out) {
    if (!hex || !object_id_out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    size_t bin_len = 0;
    const char* end = nullptr;
    if (sodium_hex2bin(object_id_out, PV_OBJECT_ID_BYTES, hex, std::strlen(hex), nullptr,
                       &bin_len, &end) != 0 ||
        bin_len != PV_OBJECT_ID_BYTES || *end != '\0') {
        return PV_ERR_INVALID_ARGUMENT;
    }
    return PV_OK;
}

}
