// PrivateVault core C ABI.
// Every function has C linkage, returns an integer status or a plain value, and
// never throws. Platform layers (desktop CLI, Android JNI) call only what is
// declared here. Byte layouts are specified in docs/PHASE1_DESIGN.md.
#ifndef PV_PV_H
#define PV_PV_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Status codes. pv_error_name() gives the symbolic name of each.
#define PV_OK 0
#define PV_ERR_INIT 1
#define PV_ERR_INVALID_ARGUMENT 2
#define PV_ERR_IO 3
#define PV_ERR_DEST_EXISTS 4
#define PV_ERR_MALFORMED_HEADER 5
#define PV_ERR_UNSUPPORTED_VERSION 6
#define PV_ERR_UNSUPPORTED_SUITE 7
#define PV_ERR_CHUNK_SIZE_OUT_OF_RANGE 8
#define PV_ERR_WRONG_VMK 9
#define PV_ERR_AUTH_FAILED 10
#define PV_ERR_TRUNCATED 11
#define PV_ERR_TRAILING_DATA 12
#define PV_ERR_MALFORMED_CHUNK 13
#define PV_ERR_ENVELOPE_MALFORMED 14
#define PV_ERR_ENVELOPE_UNSUPPORTED 15
#define PV_ERR_ENVELOPE_KDF_PARAMS 16
#define PV_ERR_ENVELOPE_UNWRAP_FAILED 17
#define PV_ERR_OUT_OF_MEMORY 18
#define PV_ERR_INTERNAL 19
#define PV_ERR_NOT_FOUND 20

// Sizes fixed by the format. Key sizes equal the libsodium constants; the
// core checks that at compile time.
#define PV_VMK_BYTES 32
#define PV_OBJECT_ID_BYTES 16
#define PV_HASH_BYTES 32
#define PV_SEED_BYTES 32
#define PV_HEADER_BYTES 128
#define PV_FORMAT_VERSION 1
#define PV_SUITE_SECRETSTREAM_XCHACHA20 1
#define PV_MIN_CHUNK_SIZE 4096u
#define PV_MAX_CHUNK_SIZE 4194304u
#define PV_DEFAULT_CHUNK_SIZE 65536u

// Caller-supplied streams. read fills up to capacity bytes and sets *out_n; a
// read may return fewer bytes than capacity at any time, and *out_n == 0 means
// end of data. write must consume all n bytes. A nonzero return from either
// means an I/O failure and the operation stops with PV_ERR_IO.
typedef struct pv_source {
    void* ctx;
    int (*read)(void* ctx, uint8_t* buf, size_t capacity, size_t* out_n);
} pv_source;

typedef struct pv_sink {
    void* ctx;
    int (*write)(void* ctx, const uint8_t* buf, size_t n);
} pv_sink;

// Header fields of an object, without any key material.
typedef struct pv_object_info {
    uint16_t format_version;
    uint16_t suite_id;
    uint32_t chunk_size;
    uint8_t object_id[PV_OBJECT_ID_BYTES];
} pv_object_info;

// Opaque vault handle.
typedef struct pv_vault pv_vault;

// Initializes libsodium. Call once per process before any other pv_ function.
// Calling it again is harmless. Returns PV_ERR_INIT on failure.
int pv_init(void);

// Static strings. pv_error_name returns "PV_ERR_UNKNOWN" for codes it does not know.
const char* pv_version_string(void);
const char* pv_sodium_version_string(void);
const char* pv_error_name(int code);

// Platform error value (errno, or GetLastError on Windows) recorded by the
// most recent failed file operation on the calling thread. 0 if none. Read it
// right after a call that returned PV_ERR_IO, PV_ERR_NOT_FOUND, or
// PV_ERR_DEST_EXISTS to see why the platform refused.
int pv_last_os_error(void);

// Fills out with PV_VMK_BYTES random bytes. out_len must equal PV_VMK_BYTES.
int pv_vmk_generate(uint8_t* out, size_t out_len);

// Vault lifecycle. All paths are UTF-8.
// pv_vault_create makes dir and dir/objects if they do not exist.
// pv_vault_open checks that dir/objects exists, deletes stale *.pvo.partial
// files, and copies the key into guarded memory. pv_vault_close wipes it.
int pv_vault_create(const char* dir);
int pv_vault_open(const char* dir, const uint8_t* vmk, size_t vmk_len, pv_vault** out);
void pv_vault_close(pv_vault* v);

// Objects inside a vault. chunk_size 0 selects PV_DEFAULT_CHUNK_SIZE.
// Encrypt writes objects/<id>.pvo.partial and renames it on success.
// Decrypt and verify report completion only after the FINAL chunk verifies.
int pv_vault_encrypt(pv_vault* v, pv_source* src, uint32_t chunk_size,
                     uint8_t* object_id_out);
int pv_vault_encrypt_file(pv_vault* v, const char* src_path, uint32_t chunk_size,
                          uint8_t* object_id_out);
int pv_vault_decrypt(pv_vault* v, const uint8_t* object_id, pv_sink* dst,
                     uint64_t* plaintext_len_out);
int pv_vault_decrypt_to_file(pv_vault* v, const uint8_t* object_id, const char* dest_path);
int pv_vault_verify(pv_vault* v, const uint8_t* object_id, uint64_t* plaintext_len_out);
int pv_vault_inspect(pv_vault* v, const uint8_t* object_id, pv_object_info* out);
int pv_vault_object_path(pv_vault* v, const uint8_t* object_id, char* buf, size_t cap);

// Stream-level operations with no vault directory. object_id may be NULL for a
// random one. Decrypt checks the header against expected_object_id when that is
// not NULL.
int pv_object_encrypt(const uint8_t* vmk, size_t vmk_len, pv_source* src, pv_sink* dst,
                      uint32_t chunk_size, const uint8_t* object_id);
int pv_object_decrypt(const uint8_t* vmk, size_t vmk_len, pv_source* src, pv_sink* dst,
                      const uint8_t* expected_object_id, uint64_t* plaintext_len_out);
int pv_object_inspect(pv_source* src, pv_object_info* out);

// File streams over the platform file API. A source reads an existing file.
// A sink creates a new file and fails with PV_ERR_DEST_EXISTS if it exists;
// commit flushes, closes and keeps it; abort closes and deletes it.
int pv_file_source_open(const char* path, pv_source* out);
void pv_file_source_close(pv_source* s);
int pv_file_sink_create(const char* path, pv_sink* out);
int pv_file_sink_commit(pv_sink* s);
void pv_file_sink_abort(pv_sink* s);

// Small-file helpers used by the key providers. write_atomic writes
// path.partial, flushes, and renames; it fails with PV_ERR_DEST_EXISTS if path
// exists. read_all fails with PV_ERR_IO if the file is larger than cap.
int pv_file_exists(const char* path, int* out_exists);
int pv_file_read_all(const char* path, uint8_t* buf, size_t cap, size_t* out_len);
int pv_file_write_atomic(const char* path, const uint8_t* data, size_t len);
int pv_file_delete(const char* path);

// Test data helpers (no secrets). The deterministic source yields the ChaCha20
// keystream for the seed (see docs/PHASE1_DESIGN.md Section 13). The BLAKE2b
// sink hashes everything written to it.
int pv_source_deterministic_new(const uint8_t* seed, size_t seed_len, uint64_t length,
                                pv_source* out);
void pv_source_deterministic_free(pv_source* s);
int pv_sink_blake2b_new(pv_sink* out);
int pv_sink_blake2b_finish(pv_sink* s, uint8_t* hash_out, size_t hash_len);
void pv_sink_blake2b_abort(pv_sink* s);
int pv_hash_deterministic(const uint8_t* seed, size_t seed_len, uint64_t length,
                          uint8_t* hash_out, size_t hash_len);

// Hex helpers for object IDs. hex_out needs 2 * PV_OBJECT_ID_BYTES + 1 bytes.
int pv_object_id_to_hex(const uint8_t* object_id, char* hex_out, size_t cap);
int pv_object_id_from_hex(const char* hex, uint8_t* object_id_out);

#ifdef __cplusplus
}
#endif

#endif
