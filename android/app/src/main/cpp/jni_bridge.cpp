// JNI entry points for the Android app. Each function maps a Kotlin call in
// com.privatevault.app.NativeBridge onto the core C ABI in pv/pv.h. Status
// codes are returned as the core gives them; nothing here logs key bytes or
// sample data.
#include "pv/pv.h"

#include <sodium.h>

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <unistd.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

namespace {

// UTF-8 copy of a Java string. Empty if the string is null.
std::string to_utf8(JNIEnv* env, jstring s) {
    if (!s) {
        return std::string();
    }
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) {
        return std::string();
    }
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Copies a Java byte array into a vector. Returns false if the array is null
// or its length differs from want (when want is nonzero).
bool copy_bytes(JNIEnv* env, jbyteArray arr, size_t want, std::vector<uint8_t>& out) {
    if (!arr) {
        return false;
    }
    jsize n = env->GetArrayLength(arr);
    if (n < 0 || (want != 0 && static_cast<size_t>(n) != want)) {
        return false;
    }
    out.resize(static_cast<size_t>(n));
    if (n > 0) {
        env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(out.data()));
    }
    return true;
}

void put_bytes(JNIEnv* env, jbyteArray arr, const uint8_t* data, size_t n) {
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(data));
}

// Key bytes copied from Kotlin, wiped when the scope ends.
struct KeyCopy {
    std::vector<uint8_t> bytes;
    ~KeyCopy() {
        if (!bytes.empty()) {
            sodium_memzero(bytes.data(), bytes.size());
        }
    }
};

pv_vault* handle_to_vault(jlong h) {
    return reinterpret_cast<pv_vault*>(static_cast<intptr_t>(h));
}

// Decrypts from an open source into a BLAKE2b sink and reports hash and length.
int decrypt_to_hash(JNIEnv* env, const uint8_t* vmk, pv_source* src, const uint8_t* expected_id,
                    jbyteArray hash_out, jlongArray len_out) {
    pv_sink sink{};
    int rc = pv_sink_blake2b_new(&sink);
    if (rc != PV_OK) {
        return rc;
    }
    uint64_t len = 0;
    rc = pv_object_decrypt(vmk, PV_VMK_BYTES, src, &sink, expected_id, &len);
    if (rc != PV_OK) {
        pv_sink_blake2b_abort(&sink);
        return rc;
    }
    uint8_t hash[PV_HASH_BYTES];
    rc = pv_sink_blake2b_finish(&sink, hash, sizeof(hash));
    if (rc != PV_OK) {
        return rc;
    }
    if (hash_out && env->GetArrayLength(hash_out) == PV_HASH_BYTES) {
        put_bytes(env, hash_out, hash, sizeof(hash));
    }
    if (len_out && env->GetArrayLength(len_out) >= 1) {
        jlong v = static_cast<jlong>(len);
        env->SetLongArrayRegion(len_out, 0, 1, &v);
    }
    return PV_OK;
}

// Phase 2: media items.

// Item header fields passed from Kotlin as a LongArray:
// [media_type, rotation_degrees, width, height, created_unix_ms].
constexpr jsize kFieldCount = 5;

bool fill_header(JNIEnv* env, jlongArray fields, uint64_t payload_len, pv_item_header* h) {
    if (!fields || env->GetArrayLength(fields) < kFieldCount) {
        return false;
    }
    jlong f[kFieldCount];
    env->GetLongArrayRegion(fields, 0, kFieldCount, f);
    if (f[0] < 0 || f[0] > 0xffff || f[1] < 0 || f[1] > 0xffff || f[2] < 0 ||
        f[2] > 0xffffffffLL || f[3] < 0 || f[3] > 0xffffffffLL || f[4] < 0) {
        return false;
    }
    std::memset(h, 0, sizeof(*h));
    h->item_version = PV_ITEM_VERSION;
    h->header_len = PV_ITEM_HEADER_BYTES;
    h->media_type = static_cast<uint16_t>(f[0]);
    h->rotation_degrees = static_cast<uint16_t>(f[1]);
    h->width = static_cast<uint32_t>(f[2]);
    h->height = static_cast<uint32_t>(f[3]);
    h->created_unix_ms = static_cast<uint64_t>(f[4]);
    h->payload_len = payload_len;
    return true;
}

#ifdef PV_DEBUG_HOOKS
// Test hooks, debug variant only: a sleep per source read and a read failure
// after a byte count. Both off (0) unless a test sets them.
std::atomic<int> g_slow_read_ms{0};
std::atomic<long long> g_fail_after_bytes{0};

struct HookedSource {
    pv_source inner;
    long long delivered;
};

int hooked_read(void* ctx, uint8_t* buf, size_t cap, size_t* out_n) {
    HookedSource* h = static_cast<HookedSource*>(ctx);
    int ms = g_slow_read_ms.load();
    if (ms > 0) {
        ::usleep(static_cast<useconds_t>(ms) * 1000);
    }
    long long limit = g_fail_after_bytes.load();
    if (limit > 0 && h->delivered >= limit) {
        return -1;
    }
    int rc = h->inner.read(h->inner.ctx, buf, cap, out_n);
    if (rc == 0) {
        h->delivered += static_cast<long long>(*out_n);
    }
    return rc;
}
#endif

// Encrypts [48-byte item header, payload bytes at data] into a new object.
int encrypt_item(JNIEnv* env, jlong handle, const uint8_t* data, size_t len, jlongArray fields,
                 jbyteArray id_out, jlongArray len_out) {
    pv_item_header h{};
    if (!fill_header(env, fields, len, &h) || !id_out ||
        env->GetArrayLength(id_out) != PV_OBJECT_ID_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint8_t raw[PV_ITEM_HEADER_BYTES];
    size_t raw_len = 0;
    int rc = pv_item_header_encode(&h, raw, sizeof(raw), &raw_len);
    if (rc != PV_OK) {
        return rc;
    }
    const uint8_t* parts[] = {raw, data};
    size_t lens[] = {raw_len, len};
    pv_source src{};
    rc = pv_source_memory_new(parts, lens, 2, &src);
    if (rc != PV_OK) {
        return rc;
    }
    pv_source* used = &src;
#ifdef PV_DEBUG_HOOKS
    HookedSource hooked{src, 0};
    pv_source hooked_src{&hooked, hooked_read};
    used = &hooked_src;
#endif
    uint8_t id[PV_OBJECT_ID_BYTES];
    rc = pv_vault_encrypt(handle_to_vault(handle), used, 0, id);
    pv_source_memory_free(&src);
    if (rc == PV_OK) {
        put_bytes(env, id_out, id, sizeof(id));
        if (len_out && env->GetArrayLength(len_out) >= 1) {
            jlong total = static_cast<jlong>(raw_len + len);
            env->SetLongArrayRegion(len_out, 0, 1, &total);
        }
    }
    return rc;
}

// Bytes wiped and freed when the scope ends. Used for the one native copy of
// a capture that did not arrive in a direct buffer.
struct GuardedCopy {
    uint8_t* p = nullptr;
    size_t n = 0;
    ~GuardedCopy() {
        if (p) {
            sodium_free(p);
        }
    }
};

// Decrypts an object through the item sink into a BLAKE2b sink. Returns the
// decrypt status. item_status[0] receives the item verdict (PV_OK,
// PV_ERR_NOT_ITEM, ...). info_out = [media_type, rotation, width, height,
// created_unix_ms, payload_len, header_len, plaintext_len].
int decrypt_item(JNIEnv* env, pv_vault* v, const uint8_t* vmk, pv_source* file_src,
                 const uint8_t* object_id, jbyteArray hash_out, jlongArray info_out,
                 jintArray item_status) {
    if (!hash_out || env->GetArrayLength(hash_out) != PV_HASH_BYTES || !info_out ||
        env->GetArrayLength(info_out) < 8 || !item_status ||
        env->GetArrayLength(item_status) < 1) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_sink hash{};
    int rc = pv_sink_blake2b_new(&hash);
    if (rc != PV_OK) {
        return rc;
    }
    pv_sink item{};
    rc = pv_sink_item_new(&hash, pv_sink_blake2b_abort, &item);
    if (rc != PV_OK) {
        pv_sink_blake2b_abort(&hash);
        return rc;
    }
    uint64_t len = 0;
    rc = v ? pv_vault_decrypt(v, object_id, &item, &len)
           : pv_object_decrypt(vmk, PV_VMK_BYTES, file_src, &item, object_id, &len);
    if (rc != PV_OK) {
        pv_sink_item_abort(&item);
        return rc;
    }
    pv_item_header h{};
    jint verdict = pv_sink_item_finish(&item, len, &h);
    env->SetIntArrayRegion(item_status, 0, 1, &verdict);
    jlong info[8] = {h.media_type, h.rotation_degrees, static_cast<jlong>(h.width),
                     static_cast<jlong>(h.height), static_cast<jlong>(h.created_unix_ms),
                     static_cast<jlong>(h.payload_len), h.header_len, static_cast<jlong>(len)};
    env->SetLongArrayRegion(info_out, 0, 8, info);
    if (verdict != PV_OK) {
        // The item sink aborted the hash sink; nothing more to finish.
        return PV_OK;
    }
    uint8_t digest[PV_HASH_BYTES];
    rc = pv_sink_blake2b_finish(&hash, digest, sizeof(digest));
    if (rc == PV_OK) {
        put_bytes(env, hash_out, digest, sizeof(digest));
    }
    return rc;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeInit(JNIEnv*, jclass) {
    return pv_init();
}

JNIEXPORT jstring JNICALL
Java_com_privatevault_app_NativeBridge_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(pv_version_string());
}

JNIEXPORT jstring JNICALL
Java_com_privatevault_app_NativeBridge_nativeSodiumVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(pv_sodium_version_string());
}

JNIEXPORT jstring JNICALL
Java_com_privatevault_app_NativeBridge_nativeErrorName(JNIEnv* env, jclass, jint code) {
    return env->NewStringUTF(pv_error_name(code));
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVmkGenerate(JNIEnv* env, jclass, jbyteArray out) {
    if (!out || env->GetArrayLength(out) != PV_VMK_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    KeyCopy k;
    k.bytes.resize(PV_VMK_BYTES);
    int rc = pv_vmk_generate(k.bytes.data(), k.bytes.size());
    if (rc == PV_OK) {
        put_bytes(env, out, k.bytes.data(), k.bytes.size());
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVaultCreate(JNIEnv* env, jclass, jstring dir) {
    std::string d = to_utf8(env, dir);
    return pv_vault_create(d.c_str());
}

// Returns the status; on PV_OK handle_out[0] holds the vault handle. The
// handle is the native pointer as a 64-bit value and has no meaningful sign:
// on arm64 Android the heap allocator tags the top byte of pointers, so a
// handle can look negative. Never interpret it as a number.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVaultOpen(JNIEnv* env, jclass, jstring dir,
                                                       jbyteArray vmk, jlongArray handle_out) {
    std::string d = to_utf8(env, dir);
    KeyCopy k;
    if (!copy_bytes(env, vmk, PV_VMK_BYTES, k.bytes) || !handle_out ||
        env->GetArrayLength(handle_out) < 1) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_vault* v = nullptr;
    int rc = pv_vault_open(d.c_str(), k.bytes.data(), k.bytes.size(), &v);
    if (rc != PV_OK) {
        return rc;
    }
    jlong h = static_cast<jlong>(reinterpret_cast<intptr_t>(v));
    env->SetLongArrayRegion(handle_out, 0, 1, &h);
    return PV_OK;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeLastOsError(JNIEnv*, jclass) {
    return pv_last_os_error();
}

JNIEXPORT void JNICALL
Java_com_privatevault_app_NativeBridge_nativeVaultClose(JNIEnv*, jclass, jlong handle) {
    pv_vault_close(handle_to_vault(handle));
}

// Encrypts length bytes of the deterministic stream for seed into a new object.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeEncryptSample(JNIEnv* env, jclass, jlong handle,
                                                           jbyteArray seed, jlong length,
                                                           jint chunk_size, jbyteArray id_out) {
    std::vector<uint8_t> s;
    if (!copy_bytes(env, seed, PV_SEED_BYTES, s) || length < 0 || chunk_size < 0 || !id_out ||
        env->GetArrayLength(id_out) != PV_OBJECT_ID_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_source src{};
    int rc = pv_source_deterministic_new(s.data(), s.size(), static_cast<uint64_t>(length), &src);
    if (rc != PV_OK) {
        return rc;
    }
    pv_source* used = &src;
#ifdef PV_DEBUG_HOOKS
    HookedSource hooked{src, 0};
    pv_source hooked_src{&hooked, hooked_read};
    used = &hooked_src;
#endif
    uint8_t id[PV_OBJECT_ID_BYTES];
    rc = pv_vault_encrypt(handle_to_vault(handle), used, static_cast<uint32_t>(chunk_size), id);
    pv_source_deterministic_free(&src);
    if (rc == PV_OK) {
        put_bytes(env, id_out, id, sizeof(id));
    }
    return rc;
}

// Decrypts an object into a hash; nothing is written to disk.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeDecryptVerify(JNIEnv* env, jclass, jlong handle,
                                                           jbyteArray id, jbyteArray hash_out,
                                                           jlongArray len_out) {
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_sink sink{};
    int rc = pv_sink_blake2b_new(&sink);
    if (rc != PV_OK) {
        return rc;
    }
    uint64_t len = 0;
    rc = pv_vault_decrypt(handle_to_vault(handle), oid.data(), &sink, &len);
    if (rc != PV_OK) {
        pv_sink_blake2b_abort(&sink);
        return rc;
    }
    uint8_t hash[PV_HASH_BYTES];
    rc = pv_sink_blake2b_finish(&sink, hash, sizeof(hash));
    if (rc != PV_OK) {
        return rc;
    }
    if (hash_out && env->GetArrayLength(hash_out) == PV_HASH_BYTES) {
        put_bytes(env, hash_out, hash, sizeof(hash));
    }
    if (len_out && env->GetArrayLength(len_out) >= 1) {
        jlong v = static_cast<jlong>(len);
        env->SetLongArrayRegion(len_out, 0, 1, &v);
    }
    return PV_OK;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeInspect(JNIEnv* env, jclass, jlong handle,
                                                     jbyteArray id, jintArray info_out) {
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid) || !info_out ||
        env->GetArrayLength(info_out) < 3) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_object_info info{};
    int rc = pv_vault_inspect(handle_to_vault(handle), oid.data(), &info);
    if (rc == PV_OK) {
        jint vals[3] = {info.format_version, info.suite_id, static_cast<jint>(info.chunk_size)};
        env->SetIntArrayRegion(info_out, 0, 3, vals);
    }
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_privatevault_app_NativeBridge_nativeObjectPath(JNIEnv* env, jclass, jlong handle,
                                                        jbyteArray id) {
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid)) {
        return nullptr;
    }
    char buf[4096];
    if (pv_vault_object_path(handle_to_vault(handle), oid.data(), buf, sizeof(buf)) != PV_OK) {
        return nullptr;
    }
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeHashDeterministic(JNIEnv* env, jclass,
                                                               jbyteArray seed, jlong length,
                                                               jbyteArray hash_out) {
    std::vector<uint8_t> s;
    if (!copy_bytes(env, seed, PV_SEED_BYTES, s) || length < 0 || !hash_out ||
        env->GetArrayLength(hash_out) != PV_HASH_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint8_t hash[PV_HASH_BYTES];
    int rc = pv_hash_deterministic(s.data(), s.size(), static_cast<uint64_t>(length), hash,
                                   sizeof(hash));
    if (rc == PV_OK) {
        put_bytes(env, hash_out, hash, sizeof(hash));
    }
    return rc;
}

// Stream-level decrypt of an object file outside any vault (golden vectors).
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeDecryptFile(JNIEnv* env, jclass, jstring path,
                                                         jbyteArray vmk, jbyteArray expected_id,
                                                         jbyteArray hash_out, jlongArray len_out) {
    std::string p = to_utf8(env, path);
    KeyCopy k;
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, vmk, PV_VMK_BYTES, k.bytes)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (expected_id && !copy_bytes(env, expected_id, PV_OBJECT_ID_BYTES, oid)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_source src{};
    int rc = pv_file_source_open(p.c_str(), &src);
    if (rc != PV_OK) {
        return rc;
    }
    rc = decrypt_to_hash(env, k.bytes.data(), &src, oid.empty() ? nullptr : oid.data(), hash_out,
                         len_out);
    pv_file_source_close(&src);
    return rc;
}

// Stream-level encrypt of the deterministic stream into a file (vector export).
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeEncryptSampleFile(JNIEnv* env, jclass, jstring path,
                                                               jbyteArray vmk, jbyteArray seed,
                                                               jlong length, jint chunk_size,
                                                               jbyteArray id_out) {
    std::string p = to_utf8(env, path);
    KeyCopy k;
    std::vector<uint8_t> s;
    if (!copy_bytes(env, vmk, PV_VMK_BYTES, k.bytes) || !copy_bytes(env, seed, PV_SEED_BYTES, s) ||
        length < 0 || chunk_size < 0 || !id_out || env->GetArrayLength(id_out) != PV_OBJECT_ID_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_source src{};
    int rc = pv_source_deterministic_new(s.data(), s.size(), static_cast<uint64_t>(length), &src);
    if (rc != PV_OK) {
        return rc;
    }
    pv_sink sink{};
    rc = pv_file_sink_create(p.c_str(), &sink);
    if (rc != PV_OK) {
        pv_source_deterministic_free(&src);
        return rc;
    }
    uint8_t id[PV_OBJECT_ID_BYTES];
    randombytes_buf(id, sizeof(id));
    rc = pv_object_encrypt(k.bytes.data(), k.bytes.size(), &src, &sink,
                           static_cast<uint32_t>(chunk_size), id);
    pv_source_deterministic_free(&src);
    if (rc != PV_OK) {
        pv_file_sink_abort(&sink);
        return rc;
    }
    rc = pv_file_sink_commit(&sink);
    if (rc == PV_OK) {
        put_bytes(env, id_out, id, sizeof(id));
    }
    return rc;
}

// Stream-level encrypt of an item (header fields plus a deterministic payload)
// into a file, for the Android-to-Windows item vector check.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeEncryptItemSampleFile(JNIEnv* env, jclass, jstring path,
                                                                   jbyteArray vmk, jbyteArray seed,
                                                                   jlong payload_len,
                                                                   jlongArray fields,
                                                                   jbyteArray id_out) {
    std::string p = to_utf8(env, path);
    KeyCopy k;
    std::vector<uint8_t> s;
    if (!copy_bytes(env, vmk, PV_VMK_BYTES, k.bytes) || !copy_bytes(env, seed, PV_SEED_BYTES, s) ||
        payload_len < 0 || !id_out || env->GetArrayLength(id_out) != PV_OBJECT_ID_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_item_header h{};
    if (!fill_header(env, fields, static_cast<uint64_t>(payload_len), &h)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint8_t raw[PV_ITEM_HEADER_BYTES];
    size_t raw_len = 0;
    int rc = pv_item_header_encode(&h, raw, sizeof(raw), &raw_len);
    if (rc != PV_OK) {
        return rc;
    }
    // Header from the memory source, then the deterministic payload: a small
    // chained source.
    struct Chain {
        pv_source head;
        pv_source tail;
        bool head_done;
    };
    pv_source head{};
    const uint8_t* parts[] = {raw};
    size_t lens[] = {raw_len};
    rc = pv_source_memory_new(parts, lens, 1, &head);
    if (rc != PV_OK) {
        return rc;
    }
    pv_source tail{};
    rc = pv_source_deterministic_new(s.data(), s.size(), static_cast<uint64_t>(payload_len), &tail);
    if (rc != PV_OK) {
        pv_source_memory_free(&head);
        return rc;
    }
    Chain chain{head, tail, false};
    pv_source src{&chain, [](void* ctx, uint8_t* buf, size_t cap, size_t* out_n) {
                      Chain* c = static_cast<Chain*>(ctx);
                      if (!c->head_done) {
                          int r = c->head.read(c->head.ctx, buf, cap, out_n);
                          if (r != 0 || *out_n > 0) {
                              return r;
                          }
                          c->head_done = true;
                      }
                      return c->tail.read(c->tail.ctx, buf, cap, out_n);
                  }};
    pv_sink sink{};
    rc = pv_file_sink_create(p.c_str(), &sink);
    if (rc == PV_OK) {
        uint8_t id[PV_OBJECT_ID_BYTES];
        randombytes_buf(id, sizeof(id));
        rc = pv_object_encrypt(k.bytes.data(), k.bytes.size(), &src, &sink, 0, id);
        if (rc != PV_OK) {
            pv_file_sink_abort(&sink);
        } else {
            rc = pv_file_sink_commit(&sink);
            if (rc == PV_OK) {
                put_bytes(env, id_out, id, sizeof(id));
            }
        }
    }
    pv_source_memory_free(&head);
    pv_source_deterministic_free(&tail);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeFileWriteAtomic(JNIEnv* env, jclass, jstring path,
                                                             jbyteArray data) {
    std::string p = to_utf8(env, path);
    std::vector<uint8_t> d;
    if (!copy_bytes(env, data, 0, d)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    return pv_file_write_atomic(p.c_str(), d.data(), d.size());
}

// Returns the file content, or null if it is missing or larger than 64 KiB.
JNIEXPORT jbyteArray JNICALL
Java_com_privatevault_app_NativeBridge_nativeFileReadAll(JNIEnv* env, jclass, jstring path) {
    std::string p = to_utf8(env, path);
    std::vector<uint8_t> buf(65536);
    size_t got = 0;
    if (pv_file_read_all(p.c_str(), buf.data(), buf.size(), &got) != PV_OK) {
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(got));
    if (out && got > 0) {
        put_bytes(env, out, buf.data(), got);
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeFileDelete(JNIEnv* env, jclass, jstring path) {
    std::string p = to_utf8(env, path);
    return pv_file_delete(p.c_str());
}

// Checks whether link() is permitted inside dir. Returns 0 when it works,
// otherwise the errno value. Used once to confirm the rename strategy.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeProbeLink(JNIEnv* env, jclass, jstring dir) {
    std::string d = to_utf8(env, dir);
    std::string a = d + "/.pv_link_probe_a";
    std::string b = d + "/.pv_link_probe_b";
    ::unlink(a.c_str());
    ::unlink(b.c_str());
    int fd = ::open(a.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) {
        return errno;
    }
    ::close(fd);
    int result = 0;
    if (::link(a.c_str(), b.c_str()) != 0) {
        result = errno;
    }
    ::unlink(a.c_str());
    ::unlink(b.c_str());
    return result;
}

// Phase 2 entry points.

// Encrypts length bytes at buffer[offset] (a direct ByteBuffer) as an item.
// No byte of the image is copied by this function; the core reads it in
// chunk-sized pieces through the memory source.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeEncryptItem(JNIEnv* env, jclass, jlong handle,
                                                         jobject buffer, jint offset, jint length,
                                                         jlongArray fields, jbyteArray id_out,
                                                         jlongArray len_out) {
    if (!buffer || offset < 0 || length < 0) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    void* base = env->GetDirectBufferAddress(buffer);
    jlong cap = env->GetDirectBufferCapacity(buffer);
    if (!base || cap < 0 || static_cast<jlong>(offset) + length > cap) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    return encrypt_item(env, handle, static_cast<const uint8_t*>(base) + offset,
                        static_cast<size_t>(length), fields, id_out, len_out);
}

// Heap-array variant: one copy into guarded memory, then the same path.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeEncryptItemArray(JNIEnv* env, jclass, jlong handle,
                                                              jbyteArray data, jint offset,
                                                              jint length, jlongArray fields,
                                                              jbyteArray id_out,
                                                              jlongArray len_out) {
    if (!data || offset < 0 || length < 0 ||
        static_cast<jlong>(offset) + length > env->GetArrayLength(data)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    GuardedCopy copy;
    copy.n = static_cast<size_t>(length);
    copy.p = static_cast<uint8_t*>(sodium_malloc(copy.n == 0 ? 1 : copy.n));
    if (!copy.p) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    if (length > 0) {
        env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte*>(copy.p));
    }
    return encrypt_item(env, handle, copy.p, copy.n, fields, id_out, len_out);
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeDecryptVerifyItem(JNIEnv* env, jclass, jlong handle,
                                                               jbyteArray id, jbyteArray hash_out,
                                                               jlongArray info_out,
                                                               jintArray item_status) {
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    return decrypt_item(env, handle_to_vault(handle), nullptr, nullptr, oid.data(), hash_out,
                        info_out, item_status);
}

// Stream-level item decrypt of an object file outside any vault (golden vectors).
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeDecryptFileItem(JNIEnv* env, jclass, jstring path,
                                                             jbyteArray vmk, jbyteArray expected_id,
                                                             jbyteArray hash_out,
                                                             jlongArray info_out,
                                                             jintArray item_status) {
    std::string p = to_utf8(env, path);
    KeyCopy k;
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, vmk, PV_VMK_BYTES, k.bytes)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (expected_id && !copy_bytes(env, expected_id, PV_OBJECT_ID_BYTES, oid)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_source src{};
    int rc = pv_file_source_open(p.c_str(), &src);
    if (rc != PV_OK) {
        return rc;
    }
    rc = decrypt_item(env, nullptr, k.bytes.data(), &src, oid.empty() ? nullptr : oid.data(),
                      hash_out, info_out, item_status);
    pv_file_source_close(&src);
    return rc;
}

#ifdef PV_DEBUG_HOOKS
// Debug variant only. Decrypts the whole plaintext of an object into a new
// byte array (item header included) so tests can decode it in memory. Returns
// null on failure or when the plaintext exceeds max_len; status_out[0] gets
// the status.
JNIEXPORT jbyteArray JNICALL
Java_com_privatevault_app_NativeBridge_nativeDecryptToArray(JNIEnv* env, jclass, jlong handle,
                                                            jbyteArray id, jint max_len,
                                                            jintArray status_out) {
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid) || max_len < 0) {
        return nullptr;
    }
    struct Collect {
        std::vector<uint8_t> data;
        size_t cap;
    } c{{}, static_cast<size_t>(max_len)};
    pv_sink sink{&c, [](void* ctx, const uint8_t* buf, size_t n) {
                     Collect* cc = static_cast<Collect*>(ctx);
                     if (cc->data.size() + n > cc->cap) {
                         return -1;
                     }
                     cc->data.insert(cc->data.end(), buf, buf + n);
                     return 0;
                 }};
    uint64_t len = 0;
    jint rc = pv_vault_decrypt(handle_to_vault(handle), oid.data(), &sink, &len);
    if (status_out && env->GetArrayLength(status_out) >= 1) {
        env->SetIntArrayRegion(status_out, 0, 1, &rc);
    }
    if (rc != PV_OK) {
        sodium_memzero(c.data.data(), c.data.size());
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(c.data.size()));
    if (out && !c.data.empty()) {
        put_bytes(env, out, c.data.data(), c.data.size());
    }
    sodium_memzero(c.data.data(), c.data.size());
    return out;
}

// Debug variant only. BLAKE2b of a direct buffer range, for the test that
// compares the bytes before encryption with the decrypt-side hash.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeHashDirectBuffer(JNIEnv* env, jclass, jobject buffer,
                                                              jint offset, jint length,
                                                              jbyteArray hash_out) {
    if (!buffer || offset < 0 || length < 0 || !hash_out ||
        env->GetArrayLength(hash_out) != PV_HASH_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    void* base = env->GetDirectBufferAddress(buffer);
    jlong cap = env->GetDirectBufferCapacity(buffer);
    if (!base || static_cast<jlong>(offset) + length > cap) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint8_t digest[PV_HASH_BYTES];
    crypto_generichash(digest, sizeof(digest), static_cast<const uint8_t*>(base) + offset,
                       static_cast<size_t>(length), nullptr, 0);
    put_bytes(env, hash_out, digest, sizeof(digest));
    return PV_OK;
}

// Debug variant only. Sets the test hooks used by the failure tests.
JNIEXPORT void JNICALL
Java_com_privatevault_app_NativeBridge_nativeSetTestHooks(JNIEnv*, jclass, jint slow_read_ms,
                                                          jlong fail_after_bytes) {
    g_slow_read_ms.store(slow_read_ms);
    g_fail_after_bytes.store(fail_after_bytes);
}
#endif

}
