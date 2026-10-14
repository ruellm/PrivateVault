// Vault directory handling: object naming, partial files, atomic finalize.
#include "format.h"
#include "fs.h"
#include "vault_internal.h"

namespace pv {

// Declared in object.cpp.
int object_encrypt(const uint8_t* vmk, pv_source* src, pv_sink* dst, uint32_t chunk_size,
                   const uint8_t* object_id);
int object_decrypt(const uint8_t* vmk, pv_source* src, pv_sink* dst,
                   const uint8_t* expected_object_id, uint64_t* plaintext_len_out);
int object_inspect(pv_source* src, pv_object_info* out);

namespace {

constexpr const char* kObjectSuffix = ".pvo";
constexpr const char* kPartialSuffix = ".pvo.partial";
constexpr const char* kTempSuffix = ".pvtmp";

bool ends_with(const std::string& s, const char* suffix) {
    size_t n = std::strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

std::string object_path(const pv_vault* v, const uint8_t* object_id) {
    char hex[2 * PV_OBJECT_ID_BYTES + 1];
    sodium_bin2hex(hex, sizeof(hex), object_id, PV_OBJECT_ID_BYTES);
    return fs::join(v->objects_dir, std::string(hex) + kObjectSuffix);
}

int null_write(void*, const uint8_t*, size_t) {
    return 0;
}

bool valid_vault(const pv_vault* v) {
    return v != nullptr && v->vmk != nullptr;
}

int remove_stale_partials(const std::string& objects_dir) {
    std::vector<std::string> names;
    int rc = fs::list_dir(objects_dir, names);
    if (rc != PV_OK) {
        return rc;
    }
    for (const std::string& n : names) {
        if (ends_with(n, kPartialSuffix)) {
            rc = fs::remove_file(fs::join(objects_dir, n));
            if (rc != PV_OK && rc != PV_ERR_NOT_FOUND) {
                return rc;
            }
        }
    }
    return PV_OK;
}

int vault_encrypt(pv_vault* v, pv_source* src, uint32_t chunk_size, uint8_t* object_id_out) {
    uint8_t id[PV_OBJECT_ID_BYTES];
    randombytes_buf(id, sizeof(id));
    std::string final_path = object_path(v, id);
    std::string partial_path = final_path + ".partial";

    bool present = false;
    int rc = fs::exists(final_path, &present);
    if (rc != PV_OK) {
        return rc;
    }
    if (present) {
        return PV_ERR_DEST_EXISTS;
    }

    pv_sink sink{};
    rc = pv_file_sink_create(partial_path.c_str(), &sink);
    if (rc != PV_OK) {
        return rc;
    }
    rc = object_encrypt(v->vmk, src, &sink, chunk_size, id);
    if (rc != PV_OK) {
        pv_file_sink_abort(&sink);
        return rc;
    }
    rc = pv_file_sink_commit(&sink);
    if (rc != PV_OK) {
        fs::remove_file(partial_path);
        return rc;
    }
    rc = fs::rename_no_replace(partial_path, final_path);
    if (rc != PV_OK) {
        fs::remove_file(partial_path);
        return rc;
    }
    std::memcpy(object_id_out, id, sizeof(id));
    return PV_OK;
}

int vault_decrypt(pv_vault* v, const uint8_t* object_id, pv_sink* dst,
                  uint64_t* plaintext_len_out) {
    pv_source src{};
    int rc = pv_file_source_open(object_path(v, object_id).c_str(), &src);
    if (rc != PV_OK) {
        return rc;
    }
    rc = object_decrypt(v->vmk, &src, dst, object_id, plaintext_len_out);
    pv_file_source_close(&src);
    return rc;
}

int vault_decrypt_to_file(pv_vault* v, const uint8_t* object_id, const std::string& dest) {
    bool present = false;
    int rc = fs::exists(dest, &present);
    if (rc != PV_OK) {
        return rc;
    }
    if (present) {
        return PV_ERR_DEST_EXISTS;
    }
    std::string tmp = dest + kTempSuffix;
    pv_sink sink{};
    rc = pv_file_sink_create(tmp.c_str(), &sink);
    if (rc != PV_OK) {
        return rc;
    }
    rc = vault_decrypt(v, object_id, &sink, nullptr);
    if (rc != PV_OK) {
        pv_file_sink_abort(&sink);
        return rc;
    }
    rc = pv_file_sink_commit(&sink);
    if (rc != PV_OK) {
        fs::remove_file(tmp);
        return rc;
    }
    rc = fs::rename_no_replace(tmp, dest);
    if (rc != PV_OK) {
        fs::remove_file(tmp);
        return rc;
    }
    return PV_OK;
}

}  // namespace

int vault_open_raw(const std::string& dir, const uint8_t* vmk, pv_vault** out) {
    std::string objects_dir = fs::join(dir, kObjectsDirName);
    bool is_dir = false;
    int rc = fs::dir_exists(objects_dir, &is_dir);
    if (rc != PV_OK) {
        return rc;
    }
    if (!is_dir) {
        return PV_ERR_NOT_FOUND;
    }
    rc = remove_stale_partials(objects_dir);
    if (rc != PV_OK) {
        return rc;
    }
    uint8_t* key = static_cast<uint8_t*>(sodium_malloc(PV_VMK_BYTES));
    if (!key) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    std::memcpy(key, vmk, PV_VMK_BYTES);
    pv_vault* v = new (std::nothrow) pv_vault{dir, objects_dir, key};
    if (!v) {
        sodium_free(key);
        return PV_ERR_OUT_OF_MEMORY;
    }
    *out = v;
    return PV_OK;
}

}  // namespace pv

extern "C" {

int pv_vault_create(const char* dir) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!dir || !*dir) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        rc = pv::fs::mkdir_if_missing(dir);
        if (rc != PV_OK) {
            return rc;
        }
        return pv::fs::mkdir_if_missing(pv::fs::join(dir, pv::kObjectsDirName));
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_open(const char* dir, const uint8_t* vmk, size_t vmk_len, pv_vault** out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!dir || !*dir || !vmk || vmk_len != PV_VMK_BYTES || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    *out = nullptr;
    try {
        return pv::vault_open_raw(dir, vmk, out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

void pv_vault_close(pv_vault* v) {
    if (!v) {
        return;
    }
    if (v->vmk) {
        sodium_free(v->vmk);
        v->vmk = nullptr;
    }
    delete v;
}

int pv_vault_encrypt(pv_vault* v, pv_source* src, uint32_t chunk_size,
                     uint8_t* object_id_out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!pv::valid_vault(v) || !src || !src->read || !object_id_out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (chunk_size == 0) {
        chunk_size = PV_DEFAULT_CHUNK_SIZE;
    }
    if (!pv::chunk_size_in_range(chunk_size)) {
        return PV_ERR_CHUNK_SIZE_OUT_OF_RANGE;
    }
    try {
        return pv::vault_encrypt(v, src, chunk_size, object_id_out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_encrypt_file(pv_vault* v, const char* src_path, uint32_t chunk_size,
                          uint8_t* object_id_out) {
    if (!src_path || !*src_path) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_source src{};
    int rc = pv_file_source_open(src_path, &src);
    if (rc != PV_OK) {
        return rc;
    }
    rc = pv_vault_encrypt(v, &src, chunk_size, object_id_out);
    pv_file_source_close(&src);
    return rc;
}

int pv_vault_decrypt(pv_vault* v, const uint8_t* object_id, pv_sink* dst,
                     uint64_t* plaintext_len_out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!pv::valid_vault(v) || !object_id || !dst || !dst->write) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::vault_decrypt(v, object_id, dst, plaintext_len_out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_decrypt_to_file(pv_vault* v, const uint8_t* object_id, const char* dest_path) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!pv::valid_vault(v) || !object_id || !dest_path || !*dest_path) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::vault_decrypt_to_file(v, object_id, dest_path);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_verify(pv_vault* v, const uint8_t* object_id, uint64_t* plaintext_len_out) {
    pv_sink null_sink{nullptr, pv::null_write};
    return pv_vault_decrypt(v, object_id, &null_sink, plaintext_len_out);
}

int pv_vault_inspect(pv_vault* v, const uint8_t* object_id, pv_object_info* out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!pv::valid_vault(v) || !object_id || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        pv_source src{};
        rc = pv_file_source_open(pv::object_path(v, object_id).c_str(), &src);
        if (rc != PV_OK) {
            return rc;
        }
        rc = pv::object_inspect(&src, out);
        pv_file_source_close(&src);
        return rc;
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_vault_object_path(pv_vault* v, const uint8_t* object_id, char* buf, size_t cap) {
    if (!pv::valid_vault(v) || !object_id || !buf) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        std::string p = pv::object_path(v, object_id);
        if (p.size() + 1 > cap) {
            return PV_ERR_INVALID_ARGUMENT;
        }
        std::memcpy(buf, p.c_str(), p.size() + 1);
        return PV_OK;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

}
