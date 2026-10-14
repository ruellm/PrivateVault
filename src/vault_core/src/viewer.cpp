// Viewer support: plaintext length without decrypting, decrypt into a caller
// buffer, memory for plaintext, delete of one object. docs/PHASE3B_DESIGN.md
// Sections 2, 6, and 7.
#include "format.h"
#include "fs.h"
#include "vault_internal.h"

namespace pv {

// Declared in object.cpp.
int object_decrypt(const uint8_t* vmk, pv_source* src, pv_sink* dst,
                   const uint8_t* expected_object_id, uint64_t* plaintext_len_out);
int object_inspect(pv_source* src, pv_object_info* out);

namespace {

bool valid_vault(const pv_vault* v) {
    return v != nullptr && v->vmk != nullptr;
}

std::string object_path(const pv_vault* v, const uint8_t* object_id) {
    char hex[2 * PV_OBJECT_ID_BYTES + 1];
    sodium_bin2hex(hex, sizeof(hex), object_id, PV_OBJECT_ID_BYTES);
    return fs::join(v->objects_dir, std::string(hex) + ".pvo");
}

// Plaintext length from the header and the file length (design Section 7.1):
// every chunk carries 17 bytes of tag, every non-final chunk chunk_size bytes
// of plaintext, the final chunk the rest (possibly none).
int plaintext_size_of_file(const std::string& path, const uint8_t* expected_id, uint64_t* out) {
    pv_source src{};
    int rc = pv_file_source_open(path.c_str(), &src);
    if (rc != PV_OK) {
        return rc;
    }
    pv_object_info info{};
    rc = object_inspect(&src, &info);
    pv_file_source_close(&src);
    if (rc != PV_OK) {
        return rc;
    }
    if (expected_id && sodium_memcmp(expected_id, info.object_id, PV_OBJECT_ID_BYTES) != 0) {
        return PV_ERR_MALFORMED_HEADER;
    }
    uint64_t len = 0;
    rc = fs::file_size(path, &len);
    if (rc != PV_OK) {
        return rc;
    }
    if (len < kHeaderBytes + kChunkOverhead) {
        return PV_ERR_TRUNCATED;
    }
    uint64_t body = len - kHeaderBytes - kChunkOverhead;
    uint64_t unit = static_cast<uint64_t>(info.chunk_size) + kChunkOverhead;
    uint64_t full = body / unit;
    uint64_t rest = body % unit;
    if (rest >= info.chunk_size) {
        return PV_ERR_MALFORMED_CHUNK;
    }
    *out = full * info.chunk_size + rest;
    return PV_OK;
}

// Sink over the caller's buffer. Refuses to write past cap and stops when the
// cancel flag is set; `reason` keeps the code the caller should see, because
// the decrypt loop reports every sink failure as PV_ERR_IO.
struct BufferSink {
    uint8_t* buf;
    uint64_t cap;
    uint64_t written;
    const volatile int* cancel;
    int reason;
};

int buffer_write(void* ctx, const uint8_t* p, size_t n) {
    BufferSink* s = static_cast<BufferSink*>(ctx);
    if (s->cancel && *s->cancel) {
        s->reason = PV_ERR_CANCELLED;
        return -1;
    }
    if (n > s->cap - s->written) {
        s->reason = PV_ERR_BUFFER_TOO_SMALL;
        return -1;
    }
    std::memcpy(s->buf + s->written, p, n);
    s->written += n;
    return 0;
}

int decrypt_to_buffer(pv_vault* v, const uint8_t* object_id, uint8_t* buf, uint64_t cap,
                      uint64_t* out_len, const volatile int* cancel) {
    std::string path = object_path(v, object_id);
    uint64_t expect = 0;
    int rc = plaintext_size_of_file(path, object_id, &expect);
    if (rc != PV_OK) {
        return rc;
    }
    if (expect > cap) {
        return PV_ERR_BUFFER_TOO_SMALL;
    }
    pv_source src{};
    rc = pv_file_source_open(path.c_str(), &src);
    if (rc != PV_OK) {
        return rc;
    }
    BufferSink state{buf, cap, 0, cancel, 0};
    pv_sink sink{&state, buffer_write};
    uint64_t len = 0;
    rc = object_decrypt(v->vmk, &src, &sink, object_id, &len);
    pv_file_source_close(&src);
    if (rc == PV_OK && len != expect) {
        rc = PV_ERR_INTERNAL;
    }
    if (rc != PV_OK) {
        if (rc == PV_ERR_IO && state.reason != 0) {
            rc = state.reason;
        }
        if (cap > 0) {
            sodium_memzero(buf, static_cast<size_t>(cap));
        }
        return rc;
    }
    if (out_len) {
        *out_len = len;
    }
    return PV_OK;
}

int delete_object(pv_vault* v, const uint8_t* object_id) {
    std::string path = object_path(v, object_id);
    int rc = fs::remove_file(path + ".partial");
    if (rc != PV_OK && rc != PV_ERR_NOT_FOUND) {
        return rc;
    }
    rc = fs::remove_file(path);
    if (rc != PV_OK) {
        return rc;
    }
    fs::sync_dir(v->objects_dir);
    return PV_OK;
}

}  // namespace
}  // namespace pv

extern "C" {

int pv_object_plaintext_size(pv_vault* v, const uint8_t* object_id, uint64_t* out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!pv::valid_vault(v) || !object_id || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::plaintext_size_of_file(pv::object_path(v, object_id), object_id, out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_object_plaintext_size_file(const char* path, uint64_t* out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!path || !*path || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::plaintext_size_of_file(path, nullptr, out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_decrypt_to_buffer(pv_vault* v, const uint8_t* object_id, uint8_t* buf, uint64_t cap,
                               uint64_t* out_len, const volatile int* cancel) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!pv::valid_vault(v) || !object_id || !buf) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::decrypt_to_buffer(v, object_id, buf, cap, out_len, cancel);
    } catch (const std::bad_alloc&) {
        if (cap > 0) {
            sodium_memzero(buf, static_cast<size_t>(cap));
        }
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        if (cap > 0) {
            sodium_memzero(buf, static_cast<size_t>(cap));
        }
        return PV_ERR_INTERNAL;
    }
}

int pv_secure_alloc(size_t n, void** out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (n == 0 || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    *out = nullptr;
    void* p = sodium_malloc(n);
    if (!p) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    *out = p;
    return PV_OK;
}

void pv_secure_free(void* p, size_t n) {
    (void)n;
    if (p) {
        sodium_free(p);
    }
}

int pv_vault_delete_object(pv_vault* v, const uint8_t* object_id) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!pv::valid_vault(v) || !object_id) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::delete_object(v, object_id);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

}
