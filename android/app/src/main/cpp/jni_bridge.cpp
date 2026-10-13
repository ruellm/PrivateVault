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

// Returns a handle greater than zero, or the negated status code.
JNIEXPORT jlong JNICALL
Java_com_privatevault_app_NativeBridge_nativeVaultOpen(JNIEnv* env, jclass, jstring dir,
                                                       jbyteArray vmk) {
    std::string d = to_utf8(env, dir);
    KeyCopy k;
    if (!copy_bytes(env, vmk, PV_VMK_BYTES, k.bytes)) {
        return -PV_ERR_INVALID_ARGUMENT;
    }
    pv_vault* v = nullptr;
    int rc = pv_vault_open(d.c_str(), k.bytes.data(), k.bytes.size(), &v);
    if (rc != PV_OK) {
        return -rc;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(v));
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
    uint8_t id[PV_OBJECT_ID_BYTES];
    rc = pv_vault_encrypt(handle_to_vault(handle), &src, static_cast<uint32_t>(chunk_size), id);
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

}
