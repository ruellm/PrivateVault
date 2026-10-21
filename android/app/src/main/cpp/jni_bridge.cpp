// JNI entry points for the Android app. Each function maps a Kotlin call in
// com.privatevault.app.NativeBridge onto the core C ABI in pv/pv.h. Status
// codes are returned as the core gives them; nothing here logs key bytes or
// sample data.
#include "pv/pv.h"

#include <sodium.h>

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <time.h>
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

#ifdef PV_DEBUG_HOOKS
jlong mono_ms() {
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<jlong>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}
#endif

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

// Every encrypt source passes through this wrapper. In all variants it
// checks the cancel flag that the lock sets, so an encrypt in flight fails at
// its next read and the core removes the partial object. The debug variant
// adds a sleep per read and a read failure after a byte count, both off (0)
// unless a test sets them.
std::atomic<bool> g_cancel_encrypts{false};
#ifdef PV_DEBUG_HOOKS
std::atomic<int> g_slow_read_ms{0};
std::atomic<long long> g_fail_after_bytes{0};
// Makes the PIN stretch report the code the core gives when Argon2id cannot
// get its memory, without running the KDF.
std::atomic<bool> g_fail_pin_stretch{false};
#endif

struct HookedSource {
    pv_source inner;
    long long delivered;
};

int hooked_read(void* ctx, uint8_t* buf, size_t cap, size_t* out_n) {
    HookedSource* h = static_cast<HookedSource*>(ctx);
    if (g_cancel_encrypts.load()) {
        return -1;
    }
#ifdef PV_DEBUG_HOOKS
    int ms = g_slow_read_ms.load();
    if (ms > 0) {
        ::usleep(static_cast<useconds_t>(ms) * 1000);
    }
    long long limit = g_fail_after_bytes.load();
    if (limit > 0 && h->delivered >= limit) {
        return -1;
    }
#endif
    int rc = h->inner.read(h->inner.ctx, buf, cap, out_n);
    if (rc == 0) {
        h->delivered += static_cast<long long>(*out_n);
    }
    return rc;
}

// Fills p from the Kotlin-side pieces of an envelope 3 parameter set.
bool fill_params(JNIEnv* env, jint mechanism, jstring alias, jlong opslimit, jlong memlimit,
                 jbyteArray salt, pv_envelope3_params* p) {
    std::memset(p, 0, sizeof(*p));
    std::string a = to_utf8(env, alias);
    if (mechanism < 0 || mechanism > 0xffff || a.empty() || a.size() > PV_ENVELOPE_ALIAS_MAX ||
        opslimit < 0 || memlimit < 0) {
        return false;
    }
    std::vector<uint8_t> s;
    if (!copy_bytes(env, salt, PV_PIN_SALT_BYTES, s)) {
        return false;
    }
    p->mechanism = static_cast<uint16_t>(mechanism);
    std::memcpy(p->alias, a.data(), a.size());
    p->opslimit = static_cast<uint32_t>(opslimit);
    p->memlimit = static_cast<uint64_t>(memlimit);
    std::memcpy(p->salt, s.data(), PV_PIN_SALT_BYTES);
    return true;
}

void put_handle(JNIEnv* env, jlongArray handle_out, pv_vault* v) {
    if (handle_out && env->GetArrayLength(handle_out) >= 1) {
        jlong h = static_cast<jlong>(reinterpret_cast<intptr_t>(v));
        env->SetLongArrayRegion(handle_out, 0, 1, &h);
    }
}

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
    HookedSource hooked{src, 0};
    pv_source hooked_src{&hooked, hooked_read};
    uint8_t id[PV_OBJECT_ID_BYTES];
    rc = pv_vault_encrypt(handle_to_vault(handle), &hooked_src, 0, id);
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

// Phase 3C: video index (docs/PHASE3C_DESIGN.md Section 4.4). The header
// crosses as a LongArray of 13: [version, header_len, video_codec, rotation,
// width, height, frame_rate_millihz, segment_count, total_duration_us,
// created_unix_ms, audio_codec, audio_channels, audio_sample_rate]. Segment
// records cross as a flat ByteArray of IDs (16 bytes each) and a LongArray of
// 5 per segment: [start_us, duration_us, byte_len, key_count, flags]. Key
// offsets cross as one IntArray in segment order.
constexpr jsize kIndexHeaderFields = 13;
constexpr jsize kSegmentFields = 5;

bool header_from_array(JNIEnv* env, jlongArray arr, pv_video_index_header* h) {
    if (!arr || env->GetArrayLength(arr) < kIndexHeaderFields) {
        return false;
    }
    jlong f[kIndexHeaderFields];
    env->GetLongArrayRegion(arr, 0, kIndexHeaderFields, f);
    for (jlong v : f) {
        if (v < 0) {
            return false;
        }
    }
    *h = pv_video_index_header{};
    h->version = static_cast<uint16_t>(f[0]);
    h->header_len = static_cast<uint16_t>(f[1]);
    h->video_codec = static_cast<uint16_t>(f[2]);
    h->rotation_degrees = static_cast<uint16_t>(f[3]);
    h->width = static_cast<uint32_t>(f[4]);
    h->height = static_cast<uint32_t>(f[5]);
    h->frame_rate_millihz = static_cast<uint32_t>(f[6]);
    h->segment_count = static_cast<uint32_t>(f[7]);
    h->total_duration_us = static_cast<uint64_t>(f[8]);
    h->created_unix_ms = static_cast<uint64_t>(f[9]);
    h->audio_codec = static_cast<uint16_t>(f[10]);
    h->audio_channels = static_cast<uint16_t>(f[11]);
    h->audio_sample_rate = static_cast<uint32_t>(f[12]);
    return true;
}

void header_to_array(JNIEnv* env, jlongArray arr, const pv_video_index_header& h) {
    jlong f[kIndexHeaderFields] = {
        h.version, h.header_len, h.video_codec, h.rotation_degrees, h.width, h.height,
        h.frame_rate_millihz, h.segment_count, static_cast<jlong>(h.total_duration_us),
        static_cast<jlong>(h.created_unix_ms), h.audio_codec, h.audio_channels, h.audio_sample_rate};
    env->SetLongArrayRegion(arr, 0, kIndexHeaderFields, f);
}

// Segment records from the flat arrays. ids may be null (locate needs none).
bool segments_from_arrays(JNIEnv* env, jbyteArray ids, jlongArray fields, size_t count,
                          std::vector<pv_video_segment>& out) {
    if (!fields || count > static_cast<size_t>(env->GetArrayLength(fields)) / kSegmentFields) {
        return false;
    }
    if (ids && static_cast<size_t>(env->GetArrayLength(ids)) < count * PV_OBJECT_ID_BYTES) {
        return false;
    }
    std::vector<jlong> f(count * kSegmentFields);
    if (!f.empty()) {
        env->GetLongArrayRegion(fields, 0, static_cast<jsize>(f.size()), f.data());
    }
    out.assign(count, pv_video_segment{});
    for (size_t i = 0; i < count; ++i) {
        const jlong* s = &f[i * kSegmentFields];
        if (s[0] < 0 || s[1] < 0 || s[2] < 0 || s[3] < 0 || s[4] < 0) {
            return false;
        }
        out[i].start_us = static_cast<uint64_t>(s[0]);
        out[i].duration_us = static_cast<uint64_t>(s[1]);
        out[i].byte_len = static_cast<uint64_t>(s[2]);
        out[i].key_count = static_cast<uint32_t>(s[3]);
        out[i].flags = static_cast<uint32_t>(s[4]);
        if (ids) {
            env->GetByteArrayRegion(ids, static_cast<jsize>(i * PV_OBJECT_ID_BYTES), PV_OBJECT_ID_BYTES,
                                    reinterpret_cast<jbyte*>(out[i].object_id));
        }
    }
    return true;
}

bool keys_from_array(JNIEnv* env, jintArray keys, std::vector<uint32_t>& out) {
    if (!keys) {
        return false;
    }
    jsize n = env->GetArrayLength(keys);
    std::vector<jint> k(static_cast<size_t>(n));
    if (n > 0) {
        env->GetIntArrayRegion(keys, 0, n, k.data());
    }
    out.resize(k.size());
    for (size_t i = 0; i < k.size(); ++i) {
        out[i] = static_cast<uint32_t>(k[i]);
    }
    return true;
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
    HookedSource hooked{src, 0};
    pv_source hooked_src{&hooked, hooked_read};
    uint8_t id[PV_OBJECT_ID_BYTES];
    rc = pv_vault_encrypt(handle_to_vault(handle), &hooked_src, static_cast<uint32_t>(chunk_size), id);
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

// Phase 3A: Argon2id over the PIN bytes. The PIN copy is wiped when the
// function returns; the Kotlin side wipes its own array.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativePinStretch(JNIEnv* env, jclass, jbyteArray pin,
                                                        jbyteArray salt, jlong opslimit,
                                                        jlong memlimit, jbyteArray out) {
    KeyCopy p;
    std::vector<uint8_t> s;
    if (!copy_bytes(env, pin, 0, p.bytes) || !copy_bytes(env, salt, PV_PIN_SALT_BYTES, s) ||
        opslimit < 0 || memlimit < 0 || !out || env->GetArrayLength(out) != PV_KEK_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint8_t kek[PV_KEK_BYTES];
#ifdef PV_DEBUG_HOOKS
    if (g_fail_pin_stretch.load()) {
        return PV_ERR_OUT_OF_MEMORY;
    }
#endif
    int rc = pv_pin_stretch(p.bytes.data(), p.bytes.size(), s.data(), s.size(),
                            static_cast<uint64_t>(opslimit), static_cast<uint64_t>(memlimit),
                            kek, sizeof(kek));
    if (rc == PV_OK) {
        put_bytes(env, out, kek, sizeof(kek));
    }
    sodium_memzero(kek, sizeof(kek));
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativePinSaltGenerate(JNIEnv* env, jclass, jbyteArray out) {
    if (!out || env->GetArrayLength(out) != PV_PIN_SALT_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint8_t salt[PV_PIN_SALT_BYTES];
    int rc = pv_pin_salt_generate(salt, sizeof(salt));
    if (rc == PV_OK) {
        put_bytes(env, out, salt, sizeof(salt));
    }
    return rc;
}

JNIEXPORT jlong JNICALL
Java_com_privatevault_app_NativeBridge_nativePinDelayMs(JNIEnv*, jclass, jint failures) {
    if (failures < 0) {
        return 0;
    }
    return static_cast<jlong>(pv_pin_delay_ms(static_cast<uint32_t>(failures)));
}

JNIEXPORT jlong JNICALL
Java_com_privatevault_app_NativeBridge_nativePinRemainingMs(JNIEnv*, jclass, jlong delay_ms,
                                                            jlong fail_elapsed_ms,
                                                            jlong now_elapsed_ms,
                                                            jlong fail_wall_ms, jlong now_wall_ms,
                                                            jboolean boot_changed) {
    if (delay_ms < 0 || fail_elapsed_ms < 0 || now_elapsed_ms < 0) {
        return 0;
    }
    return static_cast<jlong>(pv_pin_remaining_ms(
        static_cast<uint64_t>(delay_ms), static_cast<uint64_t>(fail_elapsed_ms),
        static_cast<uint64_t>(now_elapsed_ms), fail_wall_ms, now_wall_ms,
        boot_changed ? 1 : 0));
}

// Parses an envelope. infoOut = [version, provider, slot_count, pin_slot_count,
// unknown_slot_count, pin_slot_index, mechanism, opslimit, memlimit];
// saltOut gets the salt; aliasOut[0] the alias. Returns the parse status.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeEnvelope3Parse(JNIEnv* env, jclass,
                                                            jbyteArray envelope,
                                                            jlongArray info_out,
                                                            jbyteArray salt_out,
                                                            jobjectArray alias_out) {
    std::vector<uint8_t> e;
    if (!copy_bytes(env, envelope, 0, e) || !info_out || env->GetArrayLength(info_out) < 9 ||
        !salt_out || env->GetArrayLength(salt_out) != PV_PIN_SALT_BYTES || !alias_out ||
        env->GetArrayLength(alias_out) < 1) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_envelope3_info info{};
    // An empty array is an empty envelope, not a missing argument.
    uint8_t none = 0;
    int rc = pv_envelope3_parse(e.empty() ? &none : e.data(), e.size(), &info);
    if (rc != PV_OK) {
        return rc;
    }
    jlong vals[9] = {info.version, info.provider_id, info.slot_count, info.pin_slot_count,
                     info.unknown_slot_count, info.pin_slot_index, info.pin.mechanism,
                     static_cast<jlong>(info.pin.opslimit), static_cast<jlong>(info.pin.memlimit)};
    env->SetLongArrayRegion(info_out, 0, 9, vals);
    put_bytes(env, salt_out, info.pin.salt, PV_PIN_SALT_BYTES);
    env->SetObjectArrayElement(alias_out, 0, env->NewStringUTF(info.pin.alias));
    return PV_OK;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVaultCreateEnvelope3(
    JNIEnv* env, jclass, jstring dir, jint mechanism, jstring alias, jlong opslimit,
    jlong memlimit, jbyteArray salt, jbyteArray kek, jlongArray handle_out) {
    put_handle(env, handle_out, nullptr);
    pv_envelope3_params p{};
    KeyCopy k;
    if (!fill_params(env, mechanism, alias, opslimit, memlimit, salt, &p) ||
        !copy_bytes(env, kek, PV_KEK_BYTES, k.bytes)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    std::string d = to_utf8(env, dir);
    pv_vault* v = nullptr;
    int rc = pv_vault_create_envelope3(d.c_str(), &p, k.bytes.data(), k.bytes.size(), &v);
    if (rc == PV_OK) {
        put_handle(env, handle_out, v);
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVaultOpenEnvelope3(JNIEnv* env, jclass, jstring dir,
                                                                jbyteArray kek,
                                                                jlongArray handle_out) {
    put_handle(env, handle_out, nullptr);
    KeyCopy k;
    if (!copy_bytes(env, kek, PV_KEK_BYTES, k.bytes)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    std::string d = to_utf8(env, dir);
    pv_vault* v = nullptr;
    int rc = pv_vault_open_envelope3(d.c_str(), k.bytes.data(), k.bytes.size(), &v);
    if (rc == PV_OK) {
        put_handle(env, handle_out, v);
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVaultWriteEnvelope3(
    JNIEnv* env, jclass, jlong handle, jint mechanism, jstring alias, jlong opslimit,
    jlong memlimit, jbyteArray salt, jbyteArray kek) {
    pv_envelope3_params p{};
    KeyCopy k;
    if (!fill_params(env, mechanism, alias, opslimit, memlimit, salt, &p) ||
        !copy_bytes(env, kek, PV_KEK_BYTES, k.bytes)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    return pv_vault_write_envelope3(handle_to_vault(handle), &p, k.bytes.data(), k.bytes.size());
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVaultDestroyFiles(JNIEnv* env, jclass, jstring dir) {
    std::string d = to_utf8(env, dir);
    return pv_vault_destroy_files(d.c_str());
}

#ifdef PV_DEBUG_HOOKS
JNIEXPORT void JNICALL
Java_com_privatevault_app_NativeBridge_nativeSetPinStretchFailure(JNIEnv*, jclass, jboolean fail) {
    g_fail_pin_stretch.store(fail == JNI_TRUE);
}
#endif

// Sets or clears the flag that makes every encrypt in flight fail at its next
// read. The lock sets it while closing the vault and clears it on unlock.
JNIEXPORT void JNICALL
Java_com_privatevault_app_NativeBridge_nativeSetEncryptCancel(JNIEnv*, jclass, jboolean cancel) {
    g_cancel_encrypts.store(cancel == JNI_TRUE);
}

// Stage 3B viewer entry points (docs/PHASE3B_DESIGN.md Section 8).

// Polled by pv_vault_decrypt_to_buffer between chunks. Vault.close sets it
// before it waits for the call in flight and clears it afterwards.
volatile int g_viewer_cancel = 0;
#ifdef PV_DEBUG_HOOKS
std::atomic<bool> g_viewer_alloc_fail{false};
std::atomic<int> g_viewer_slow_ms{0};
#endif

JNIEXPORT void JNICALL
Java_com_privatevault_app_NativeBridge_nativeSetViewerCancel(JNIEnv*, jclass, jboolean cancel) {
    g_viewer_cancel = cancel == JNI_TRUE ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeObjectPlaintextSize(JNIEnv* env, jclass, jlong handle,
                                                                 jbyteArray id, jlongArray out) {
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid) || !out || env->GetArrayLength(out) < 1) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint64_t size = 0;
    int rc = pv_object_plaintext_size(handle_to_vault(handle), oid.data(), &size);
    if (rc == PV_OK) {
        jlong v = static_cast<jlong>(size);
        env->SetLongArrayRegion(out, 0, 1, &v);
    }
    return rc;
}

// Allocates guarded memory for the whole plaintext, decrypts into it, and
// decodes the item header. Returns the decrypt status. On PV_OK info_out =
// [item_status, media_type, rotation, width, height, created_unix_ms,
// payload_len, header_len, plaintext_len] and, for an item of a known media
// type (JPEG, video segment, video index), buffer_out[0] is a direct
// ByteBuffer over the plaintext that the caller gives back to
// nativeViewerFree. Every other outcome leaves nothing allocated: the buffer
// of an unknown item is wiped here at once.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeViewerLoad(JNIEnv* env, jclass, jlong handle,
                                                        jbyteArray id, jlong cap,
                                                        jlongArray info_out,
                                                        jobjectArray buffer_out) {
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid) || cap < 0 || !info_out ||
        env->GetArrayLength(info_out) < 9 || !buffer_out || env->GetArrayLength(buffer_out) < 1) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    env->SetObjectArrayElement(buffer_out, 0, nullptr);
    pv_vault* v = handle_to_vault(handle);
    uint64_t size = 0;
    int rc = pv_object_plaintext_size(v, oid.data(), &size);
    if (rc != PV_OK) {
        return rc;
    }
    if (size > static_cast<uint64_t>(cap)) {
        return PV_ERR_BUFFER_TOO_SMALL;
    }
    jlong info[9] = {PV_ERR_NOT_ITEM, 0, 0, 0, 0, 0, 0, 0, static_cast<jlong>(size)};
    if (size == 0) {
        // Nothing to decrypt and no room for a header.
        env->SetLongArrayRegion(info_out, 0, 9, info);
        return PV_OK;
    }
#ifdef PV_DEBUG_HOOKS
    if (g_viewer_alloc_fail.load()) {
        return PV_ERR_OUT_OF_MEMORY;
    }
#endif
    void* buf = nullptr;
    rc = pv_secure_alloc(static_cast<size_t>(size), &buf);
    if (rc != PV_OK) {
        return rc;
    }
#ifdef PV_DEBUG_HOOKS
    int slow = g_viewer_slow_ms.load();
    if (slow > 0) {
        // Delays the start of the decrypt so a lock can arrive while this
        // call holds the vault guard.
        ::usleep(static_cast<useconds_t>(slow) * 1000);
    }
#endif
    uint64_t len = 0;
    rc = pv_vault_decrypt_to_buffer(v, oid.data(), static_cast<uint8_t*>(buf), size, &len,
                                    &g_viewer_cancel);
    if (rc != PV_OK) {
        pv_secure_free(buf, static_cast<size_t>(size));
        return rc;
    }
    pv_item_header h{};
    int item_rc = pv_item_header_decode(static_cast<const uint8_t*>(buf), static_cast<size_t>(len), &h);
    if (item_rc == PV_OK) {
        item_rc = pv_item_header_check_len(&h, len);
    }
    info[0] = item_rc;
    if (item_rc == PV_OK) {
        info[1] = h.media_type;
        info[2] = h.rotation_degrees;
        info[3] = static_cast<jlong>(h.width);
        info[4] = static_cast<jlong>(h.height);
        info[5] = static_cast<jlong>(h.created_unix_ms);
        info[6] = static_cast<jlong>(h.payload_len);
        info[7] = h.header_len;
    }
    info[8] = static_cast<jlong>(len);
    bool known = h.media_type == PV_MEDIA_JPEG || h.media_type == PV_MEDIA_VIDEO_SEGMENT ||
                 h.media_type == PV_MEDIA_VIDEO_INDEX;
    if (item_rc == PV_OK && known) {
        jobject bb = env->NewDirectByteBuffer(buf, static_cast<jlong>(len));
        if (!bb) {
            pv_secure_free(buf, static_cast<size_t>(size));
            return PV_ERR_INTERNAL;
        }
        env->SetObjectArrayElement(buffer_out, 0, bb);
    } else {
        pv_secure_free(buf, static_cast<size_t>(size));
    }
    env->SetLongArrayRegion(info_out, 0, 9, info);
    return PV_OK;
}

// Item header of an object from its first chunk (pv_object_item_header).
// Returns the core status; on PV_OK info_out = [media_type, rotation, width,
// height, created_unix_ms, payload_len, header_len, plaintext_len].
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeObjectItemHeader(JNIEnv* env, jclass, jlong handle,
                                                              jbyteArray id, jlongArray info_out) {
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid) || !info_out ||
        env->GetArrayLength(info_out) < 8) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_item_header h{};
    uint64_t len = 0;
    int rc = pv_object_item_header(handle_to_vault(handle), oid.data(), &h, &len);
    if (rc == PV_OK) {
        jlong info[8] = {h.media_type,
                         h.rotation_degrees,
                         static_cast<jlong>(h.width),
                         static_cast<jlong>(h.height),
                         static_cast<jlong>(h.created_unix_ms),
                         static_cast<jlong>(h.payload_len),
                         h.header_len,
                         static_cast<jlong>(len)};
        env->SetLongArrayRegion(info_out, 0, 8, info);
    }
    return rc;
}

// Wipes and frees a buffer from nativeViewerLoad.
JNIEXPORT void JNICALL
Java_com_privatevault_app_NativeBridge_nativeViewerFree(JNIEnv* env, jclass, jobject buffer) {
    if (!buffer) {
        return;
    }
    void* p = env->GetDirectBufferAddress(buffer);
    jlong cap = env->GetDirectBufferCapacity(buffer);
    if (p && cap > 0) {
        pv_secure_free(p, static_cast<size_t>(cap));
    }
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeDeleteObject(JNIEnv* env, jclass, jlong handle,
                                                          jbyteArray id) {
    std::vector<uint8_t> oid;
    if (!copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    return pv_vault_delete_object(handle_to_vault(handle), oid.data());
}

// Phase 3C video index entry points (array shapes above segments_from_arrays).

// Encodes an index. Returns the bytes, or null with the status in status_out.
JNIEXPORT jbyteArray JNICALL
Java_com_privatevault_app_NativeBridge_nativeVideoIndexEncode(JNIEnv* env, jclass, jlongArray header,
                                                              jbyteArray ids, jlongArray seg_fields,
                                                              jintArray keys, jintArray status_out) {
    jint rc = PV_ERR_INVALID_ARGUMENT;
    jbyteArray result = nullptr;
    pv_video_index_header h{};
    std::vector<pv_video_segment> segs;
    std::vector<uint32_t> k;
    if (header_from_array(env, header, &h) && ids &&
        segments_from_arrays(env, ids, seg_fields, h.segment_count, segs) && keys_from_array(env, keys, k)) {
        size_t size = 0;
        rc = pv_video_index_size(&h, segs.data(), &size);
        if (rc == PV_OK) {
            std::vector<uint8_t> out(size);
            size_t len = 0;
            rc = pv_video_index_encode(&h, segs.data(), k.data(), k.size(), out.data(), out.size(), &len);
            if (rc == PV_OK) {
                result = env->NewByteArray(static_cast<jsize>(len));
                if (result) {
                    put_bytes(env, result, out.data(), len);
                } else {
                    rc = PV_ERR_INTERNAL;
                }
            }
        }
    }
    if (status_out && env->GetArrayLength(status_out) >= 1) {
        env->SetIntArrayRegion(status_out, 0, 1, &rc);
    }
    return result;
}

// Header and counts of an index held in buf[offset, offset + length).
// keys_total_out[0] gets the number of key offsets across all segments.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVideoIndexDecodeHeader(JNIEnv* env, jclass, jbyteArray buf,
                                                                    jint offset, jint length,
                                                                    jlongArray header_out,
                                                                    jlongArray keys_total_out) {
    if (!buf || offset < 0 || length < 0 || static_cast<jlong>(offset) + length > env->GetArrayLength(buf) ||
        !header_out || env->GetArrayLength(header_out) < kIndexHeaderFields || !keys_total_out ||
        env->GetArrayLength(keys_total_out) < 1) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    std::vector<uint8_t> b(static_cast<size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(buf, offset, length, reinterpret_cast<jbyte*>(b.data()));
    }
    pv_video_index_header h{};
    size_t keys_total = 0;
    int rc = pv_video_index_decode_header(b.empty() ? reinterpret_cast<const uint8_t*>("") : b.data(),
                                          b.size(), &h, &keys_total);
    if (rc == PV_OK) {
        header_to_array(env, header_out, h);
        jlong kt = static_cast<jlong>(keys_total);
        env->SetLongArrayRegion(keys_total_out, 0, 1, &kt);
    }
    return rc;
}

// Whole index: header, IDs, segment fields, and key offsets into arrays
// sized from a previous decode_header. counts_out = [segments, keys].
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVideoIndexDecode(JNIEnv* env, jclass, jbyteArray buf,
                                                              jint offset, jint length,
                                                              jlongArray header_out, jbyteArray ids_out,
                                                              jlongArray seg_fields_out, jintArray keys_out,
                                                              jlongArray counts_out) {
    if (!buf || offset < 0 || length < 0 || static_cast<jlong>(offset) + length > env->GetArrayLength(buf) ||
        !header_out || env->GetArrayLength(header_out) < kIndexHeaderFields || !ids_out || !seg_fields_out ||
        !keys_out || !counts_out || env->GetArrayLength(counts_out) < 2) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    size_t segs_cap = static_cast<size_t>(env->GetArrayLength(ids_out)) / PV_OBJECT_ID_BYTES;
    if (static_cast<size_t>(env->GetArrayLength(seg_fields_out)) / kSegmentFields < segs_cap) {
        segs_cap = static_cast<size_t>(env->GetArrayLength(seg_fields_out)) / kSegmentFields;
    }
    size_t keys_cap = static_cast<size_t>(env->GetArrayLength(keys_out));
    std::vector<uint8_t> b(static_cast<size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(buf, offset, length, reinterpret_cast<jbyte*>(b.data()));
    }
    pv_video_index_header h{};
    std::vector<pv_video_segment> segs(segs_cap);
    std::vector<uint32_t> keys(keys_cap);
    size_t keys_len = 0;
    int rc = pv_video_index_decode(b.empty() ? reinterpret_cast<const uint8_t*>("") : b.data(), b.size(), &h,
                                   segs.data(), segs.size(), keys.data(), keys.size(), &keys_len);
    if (rc != PV_OK) {
        return rc;
    }
    header_to_array(env, header_out, h);
    std::vector<jlong> f(static_cast<size_t>(h.segment_count) * kSegmentFields);
    for (size_t i = 0; i < h.segment_count; ++i) {
        env->SetByteArrayRegion(ids_out, static_cast<jsize>(i * PV_OBJECT_ID_BYTES), PV_OBJECT_ID_BYTES,
                                reinterpret_cast<const jbyte*>(segs[i].object_id));
        jlong* s = &f[i * kSegmentFields];
        s[0] = static_cast<jlong>(segs[i].start_us);
        s[1] = static_cast<jlong>(segs[i].duration_us);
        s[2] = static_cast<jlong>(segs[i].byte_len);
        s[3] = segs[i].key_count;
        s[4] = segs[i].flags;
    }
    if (!f.empty()) {
        env->SetLongArrayRegion(seg_fields_out, 0, static_cast<jsize>(f.size()), f.data());
    }
    std::vector<jint> k(keys_len);
    for (size_t i = 0; i < keys_len; ++i) {
        k[i] = static_cast<jint>(keys[i]);
    }
    if (!k.empty()) {
        env->SetIntArrayRegion(keys_out, 0, static_cast<jsize>(k.size()), k.data());
    }
    jlong counts[2] = {static_cast<jlong>(h.segment_count), static_cast<jlong>(keys_len)};
    env->SetLongArrayRegion(counts_out, 0, 2, counts);
    return PV_OK;
}

// The seek rule over a decoded index: out = [segment, key, key_time_us].
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeVideoIndexLocate(JNIEnv* env, jclass, jlongArray header,
                                                              jlongArray seg_fields, jintArray keys,
                                                              jlong time_us, jlongArray out) {
    pv_video_index_header h{};
    std::vector<pv_video_segment> segs;
    std::vector<uint32_t> k;
    if (!header_from_array(env, header, &h) || !segments_from_arrays(env, nullptr, seg_fields, h.segment_count, segs) ||
        !keys_from_array(env, keys, k) || time_us < 0 || !out || env->GetArrayLength(out) < 3) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    size_t need = 0;
    for (const pv_video_segment& s : segs) {
        need += s.key_count;
    }
    if (need != k.size()) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint32_t seg = 0;
    uint32_t key = 0;
    uint64_t key_time = 0;
    int rc = pv_video_index_locate(&h, segs.data(), k.data(), static_cast<uint64_t>(time_us), &seg, &key, &key_time);
    if (rc == PV_OK) {
        jlong v[3] = {seg, key, static_cast<jlong>(key_time)};
        env->SetLongArrayRegion(out, 0, 3, v);
    }
    return rc;
}

#ifdef PV_DEBUG_HOOKS
// Debug variant only: makes the next viewer loads report the allocation
// failure code, and delays the start of each viewer decrypt by slow_ms.
JNIEXPORT void JNICALL
Java_com_privatevault_app_NativeBridge_nativeSetViewerHooks(JNIEnv*, jclass, jboolean alloc_fail,
                                                            jint slow_ms) {
    g_viewer_alloc_fail.store(alloc_fail == JNI_TRUE);
    g_viewer_slow_ms.store(slow_ms);
}
#endif

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

// Phase 3E segment writer (docs/PHASE3E_DESIGN.md Section 4.10). The handle
// is the writer pointer as a 64-bit value, opaque as the vault handle is.

pv_segment_writer* handle_to_writer(jlong h) {
    return reinterpret_cast<pv_segment_writer*>(static_cast<intptr_t>(h));
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeSegmentOpen(JNIEnv* env, jclass, jint width, jint height,
                                                         jint rotation, jbyteArray sps, jbyteArray pps,
                                                         jlong cap, jlongArray handle_out) {
    std::vector<uint8_t> s;
    std::vector<uint8_t> p;
    if (width <= 0 || height <= 0 || rotation < 0 || rotation > 0xffff || cap <= 0 || !handle_out ||
        env->GetArrayLength(handle_out) < 1 || !copy_bytes(env, sps, 0, s) || !copy_bytes(env, pps, 0, p)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_segment_writer* w = nullptr;
    int rc = pv_segment_writer_open(static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                    static_cast<uint16_t>(rotation), s.data(), s.size(), p.data(), p.size(),
                                    static_cast<size_t>(cap), &w);
    jlong h = rc == PV_OK ? static_cast<jlong>(reinterpret_cast<intptr_t>(w)) : 0;
    env->SetLongArrayRegion(handle_out, 0, 1, &h);
    return rc;
}

// Appends length bytes at buffer[offset] of a direct ByteBuffer (the encoder
// output buffer); the core copies them into the segment buffer in place.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeSegmentAdd(JNIEnv* env, jclass, jlong handle, jobject buffer,
                                                        jint offset, jint length, jlong pts_us, jboolean key) {
    if (!handle || !buffer || offset < 0 || length <= 0 || pts_us < 0) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    void* base = env->GetDirectBufferAddress(buffer);
    jlong cap = env->GetDirectBufferCapacity(buffer);
    if (!base || cap < 0 || static_cast<jlong>(offset) + length > cap) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    return pv_segment_writer_add(handle_to_writer(handle), static_cast<const uint8_t*>(base) + offset,
                                 static_cast<size_t>(length), static_cast<uint64_t>(pts_us), key ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeSegmentFinish(JNIEnv*, jclass, jlong handle, jlong end_us) {
    if (!handle || end_us < 0) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    return pv_segment_writer_finish(handle_to_writer(handle), static_cast<uint64_t>(end_us));
}

// out = [bytes, samples, fragments].
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeSegmentStats(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    if (!handle || !out || env->GetArrayLength(out) < 3) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    size_t bytes = 0;
    uint32_t samples = 0;
    uint32_t fragments = 0;
    int rc = pv_segment_writer_stats(handle_to_writer(handle), &bytes, &samples, &fragments);
    if (rc == PV_OK) {
        jlong v[3] = {static_cast<jlong>(bytes), samples, fragments};
        env->SetLongArrayRegion(out, 0, 3, v);
    }
    return rc;
}

// Encrypts a finished segment as an item (fields as nativeEncryptItem takes
// them) straight from the writer buffer, through the same cancel hook.
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeSegmentEncrypt(JNIEnv* env, jclass, jlong vault, jlong handle,
                                                            jlongArray fields, jbyteArray id_out,
                                                            jlongArray len_out) {
    if (!vault || !handle) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    const uint8_t* data = nullptr;
    size_t len = 0;
    int rc = pv_segment_writer_data(handle_to_writer(handle), &data, &len);
    if (rc != PV_OK) {
        return rc;
    }
    return encrypt_item(env, vault, data, len, fields, id_out, len_out);
}

JNIEXPORT void JNICALL
Java_com_privatevault_app_NativeBridge_nativeSegmentClose(JNIEnv*, jclass, jlong handle) {
    if (handle) {
        pv_segment_writer_close(handle_to_writer(handle));
    }
}

#ifdef PV_DEBUG_HOOKS
// Debug variant only: a copy of the finished segment for the box checks of
// the tests. Returns null before finish; the caller wipes the array.
JNIEXPORT jbyteArray JNICALL
Java_com_privatevault_app_NativeBridge_nativeSegmentCopy(JNIEnv* env, jclass, jlong handle) {
    if (!handle) {
        return nullptr;
    }
    const uint8_t* data = nullptr;
    size_t len = 0;
    if (pv_segment_writer_data(handle_to_writer(handle), &data, &len) != PV_OK) {
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(len));
    if (out && len > 0) {
        put_bytes(env, out, data, len);
    }
    return out;
}
#endif

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

// Stage 3B viewer probes (docs/PHASE3B_PROGRESS.md, Milestone A). Debug
// variant only.

// Allocates size bytes with sodium_malloc, asks for a memory lock on the block
// a second time to see the answer sodium_malloc ignores, and frees it.
// out = [alloc_ok, mlock_errno, rlimit_memlock_cur, rlimit_memlock_max,
// alloc_ms, free_ms].
JNIEXPORT jint JNICALL
Java_com_privatevault_app_NativeBridge_nativeProbeSecureAlloc(JNIEnv* env, jclass, jlong size,
                                                              jlongArray out) {
    if (size <= 0 || !out || env->GetArrayLength(out) < 6) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    struct rlimit rl {};
    getrlimit(RLIMIT_MEMLOCK, &rl);
    jlong vals[6] = {0, 0, static_cast<jlong>(rl.rlim_cur), static_cast<jlong>(rl.rlim_max), 0, 0};
    jlong t0 = mono_ms();
    void* p = sodium_malloc(static_cast<size_t>(size));
    vals[4] = mono_ms() - t0;
    if (p) {
        vals[0] = 1;
        if (mlock(p, static_cast<size_t>(size)) != 0) {
            vals[1] = errno;
        } else {
            munlock(p, static_cast<size_t>(size));
        }
        jlong t1 = mono_ms();
        sodium_free(p);
        vals[5] = mono_ms() - t1;
    } else {
        vals[1] = errno;
    }
    env->SetLongArrayRegion(out, 0, 6, vals);
    return PV_OK;
}

// Decrypts the whole plaintext of an object into sodium_malloc memory and
// returns a direct ByteBuffer over it, for the viewer probe. length is the
// plaintext length from a previous verify; a plaintext of another length
// fails. The caller frees the buffer with nativeProbeFreeDirect; on failure
// nothing stays allocated. status_out[0] gets the status.
JNIEXPORT jobject JNICALL
Java_com_privatevault_app_NativeBridge_nativeProbeDecryptDirect(JNIEnv* env, jclass, jlong handle,
                                                                jbyteArray id, jlong length,
                                                                jintArray status_out) {
    std::vector<uint8_t> oid;
    jint rc = PV_ERR_INVALID_ARGUMENT;
    jobject result = nullptr;
    if (copy_bytes(env, id, PV_OBJECT_ID_BYTES, oid) && length >= 0) {
        struct Fill {
            uint8_t* p;
            size_t cap;
            size_t n;
        } f{nullptr, static_cast<size_t>(length), 0};
        f.p = static_cast<uint8_t*>(sodium_malloc(f.cap == 0 ? 1 : f.cap));
        if (!f.p) {
            rc = PV_ERR_OUT_OF_MEMORY;
        } else {
            pv_sink sink{&f, [](void* ctx, const uint8_t* buf, size_t n) {
                             Fill* ff = static_cast<Fill*>(ctx);
                             if (ff->n + n > ff->cap) {
                                 return -1;
                             }
                             std::memcpy(ff->p + ff->n, buf, n);
                             ff->n += n;
                             return 0;
                         }};
            uint64_t len = 0;
            rc = pv_vault_decrypt(handle_to_vault(handle), oid.data(), &sink, &len);
            if (rc == PV_OK && len != f.cap) {
                rc = PV_ERR_INVALID_ARGUMENT;
            }
            if (rc == PV_OK) {
                result = env->NewDirectByteBuffer(f.p, static_cast<jlong>(f.cap));
                if (!result) {
                    rc = PV_ERR_INTERNAL;
                }
            }
            if (rc != PV_OK) {
                sodium_free(f.p);
            }
        }
    }
    if (status_out && env->GetArrayLength(status_out) >= 1) {
        env->SetIntArrayRegion(status_out, 0, 1, &rc);
    }
    return result;
}

// Wipes and frees a buffer from nativeProbeDecryptDirect.
JNIEXPORT void JNICALL
Java_com_privatevault_app_NativeBridge_nativeProbeFreeDirect(JNIEnv* env, jclass, jobject buffer) {
    if (!buffer) {
        return;
    }
    void* p = env->GetDirectBufferAddress(buffer);
    if (p) {
        sodium_free(p);
    }
}
#endif

}
