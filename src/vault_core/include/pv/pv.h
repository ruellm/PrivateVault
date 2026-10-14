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
// Media item header codes (Phase 2). See docs/PHASE2_DESIGN.md Section 3.
#define PV_ERR_ITEM_MALFORMED 21
#define PV_ERR_ITEM_UNSUPPORTED 22
// The plaintext does not start with the item magic. A classification result,
// not a corruption: callers that accept non-item objects treat it as an
// "unknown item". The object itself decrypted and authenticated normally.
#define PV_ERR_NOT_ITEM 23
// A provider 3 envelope that parsed but holds no slot this reader can use
// (Phase 3A). See docs/PHASE3A_DESIGN.md Section 2.
#define PV_ERR_ENVELOPE_NO_SLOT 24
// Viewer codes (Phase 3B). See docs/PHASE3B_DESIGN.md Section 7.
#define PV_ERR_BUFFER_TOO_SMALL 25
#define PV_ERR_CANCELLED 26

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

// Media item header. A convention inside the plaintext of an object: the
// plaintext starts with this header, the media bytes follow. The object
// format itself is unchanged. Layout (little-endian, 48 bytes written):
//   0  8  magic "PVITEM01"
//   8  2  item_version (1)
//  10  2  header_len (48 written; 48 to 4096 accepted, extra bytes skipped)
//  12  2  media_type
//  14  2  rotation_degrees (0, 90, 180, 270)
//  16  4  width
//  20  4  height
//  24  8  created_unix_ms
//  32  8  payload_len (bytes after the header)
//  40  8  reserved (zero)
#define PV_ITEM_MAGIC "PVITEM01"
#define PV_ITEM_HEADER_BYTES 48
#define PV_ITEM_HEADER_MAX_BYTES 4096
#define PV_ITEM_VERSION 1
#define PV_MEDIA_JPEG 1
#define PV_MEDIA_VIDEO_SEGMENT 2
#define PV_MEDIA_VIDEO_INDEX 3

typedef struct pv_item_header {
    uint16_t item_version;
    uint16_t header_len;
    uint16_t media_type;
    uint16_t rotation_degrees;
    uint32_t width;
    uint32_t height;
    uint64_t created_unix_ms;
    uint64_t payload_len;
} pv_item_header;

// encode writes PV_ITEM_HEADER_BYTES bytes and rejects fields a reader would
// reject. decode needs at least PV_ITEM_HEADER_BYTES bytes and checks magic,
// version, header_len, media_type, and rotation in that order. check_len
// checks header_len + payload_len == plaintext_len. Codes: PV_ERR_NOT_ITEM,
// PV_ERR_ITEM_UNSUPPORTED, PV_ERR_ITEM_MALFORMED.
int pv_item_header_encode(const pv_item_header* h, uint8_t* out, size_t out_cap,
                          size_t* out_len);
int pv_item_header_decode(const uint8_t* buf, size_t len, pv_item_header* out);
int pv_item_header_check_len(const pv_item_header* h, uint64_t plaintext_len);

// Source over caller-owned memory parts, read in order as one stream. Nothing
// is copied; the caller keeps every part alive until the source is freed. A
// part may be empty; its pointer may be NULL only then. Reads stop at a part
// boundary, so a consumer sees short reads.
int pv_source_memory_new(const uint8_t* const* parts, const size_t* part_lens,
                         size_t part_count, pv_source* out);
void pv_source_memory_free(pv_source* s);

// Sink that splits an item plaintext: keeps the header bytes, forwards the
// payload to payload_sink. Use it as the destination of a decrypt, then call
// finish only after the decrypt returned PV_OK; call abort otherwise. Before
// completion the sink reads only the magic and header_len, to find where the
// payload starts. If the magic is absent, or header_len is out of range or
// beyond the plaintext, nothing is forwarded, payload_abort is called once on
// payload_sink (when not NULL), and finish reports PV_ERR_NOT_ITEM or
// PV_ERR_ITEM_MALFORMED. finish validates every field and the length; on
// success it writes header_out and leaves payload_sink to the caller. abort
// also aborts payload_sink unless that already happened. Both free the item
// sink.
int pv_sink_item_new(pv_sink* payload_sink, void (*payload_abort)(pv_sink*), pv_sink* out);
int pv_sink_item_finish(pv_sink* s, uint64_t plaintext_len, pv_item_header* header_out);
void pv_sink_item_abort(pv_sink* s);

// PIN stretching (Phase 3A, docs/PHASE3A_DESIGN.md Section 3). Argon2id over
// the PIN bytes. opslimit and memlimit are checked against the range below
// before any memory is requested; out of range gives PV_ERR_ENVELOPE_KDF_PARAMS.
// The range equals the libsodium INTERACTIVE to MODERATE presets. salt_len
// must be PV_PIN_SALT_BYTES and out_len PV_KEK_BYTES. The caller wipes pin
// and out when done.
#define PV_PIN_SALT_BYTES 16
#define PV_KEK_BYTES 32
#define PV_PIN_OPSLIMIT_MIN 2u
#define PV_PIN_OPSLIMIT_MAX 3u
#define PV_PIN_MEMLIMIT_MIN (64u * 1024u * 1024u)
#define PV_PIN_MEMLIMIT_MAX (256u * 1024u * 1024u)
// Parameters a new envelope is written with: 0.83 to 0.88 s per guess on the
// Xiaomi 2201116SG in the optimised build (design Section 3.4, CP-2).
#define PV_PIN_OPSLIMIT_DEFAULT 2u
#define PV_PIN_MEMLIMIT_DEFAULT (256u * 1024u * 1024u)

int pv_pin_stretch(const uint8_t* pin, size_t pin_len, const uint8_t* salt, size_t salt_len,
                   uint64_t opslimit, uint64_t memlimit, uint8_t* out, size_t out_len);
// Fills out with PV_PIN_SALT_BYTES random bytes. out_len must equal that.
int pv_pin_salt_generate(uint8_t* out, size_t out_len);

// Wrong-PIN delay arithmetic (design Section 8). delay_ms: the wait after
// `failures` consecutive wrong PINs: 0 below 5, then 30 s doubling per
// failure, capped at 3600 s. remaining_ms: how much of delay_ms is left.
// Two estimates are made, one from the elapsed clock and one from the wall
// clock (a clock that went backwards counts as the whole delay); on the same
// boot the larger one is returned, after a reboot the whole delay.
uint64_t pv_pin_delay_ms(uint32_t failures);
uint64_t pv_pin_remaining_ms(uint64_t delay_ms, uint64_t fail_elapsed_ms, uint64_t now_elapsed_ms,
                             int64_t fail_wall_ms, int64_t now_wall_ms, int boot_changed);

// Key envelope provider 3: the VMK wrapped under a key derived from a PIN and
// a device-bound key. Layout in docs/PHASE3A_DESIGN.md Section 2. The
// envelope holds slots; a PIN slot carries the mechanism of the device-bound
// step, the platform alias of that key, the Argon2id parameters, salt, nonce,
// and the wrapped VMK. Unknown slot types are skipped. The additional data
// of every slot is the whole envelope with each wrapped field zeroed.
#define PV_ENVELOPE_PROVIDER_PIN 3
#define PV_SLOT_PIN 1
#define PV_SLOT_BIOMETRIC 2
#define PV_MECHANISM_ANDROID_KEYSTORE_HMAC 1
#define PV_ENVELOPE_ALIAS_MAX 64
#define PV_ENVELOPE3_MAX_BYTES 4096
#define PV_ENVELOPE3_MAX_SLOTS 8

typedef struct pv_envelope3_params {
    uint16_t mechanism;
    char alias[PV_ENVELOPE_ALIAS_MAX + 1];
    uint32_t opslimit;
    uint64_t memlimit;
    uint8_t salt[PV_PIN_SALT_BYTES];
} pv_envelope3_params;

typedef struct pv_envelope3_info {
    uint16_t version;
    uint16_t provider_id;
    uint16_t slot_count;
    uint16_t pin_slot_count;
    uint16_t unknown_slot_count;
    uint16_t pin_slot_index;
    pv_envelope3_params pin;
} pv_envelope3_info;

// parse checks the structure and the KDF range of the first PIN slot and
// reports its fields; it runs no KDF and requests no memory. Codes:
// PV_ERR_ENVELOPE_MALFORMED, PV_ERR_ENVELOPE_UNSUPPORTED (version, or a
// provider other than 3), PV_ERR_ENVELOPE_NO_SLOT, PV_ERR_ENVELOPE_KDF_PARAMS.
int pv_envelope3_parse(const uint8_t* buf, size_t len, pv_envelope3_info* out);
// seal writes a one-slot envelope for vmk under kek. open recovers the VMK
// from the first PIN slot; wrong kek or a modified byte gives
// PV_ERR_ENVELOPE_UNWRAP_FAILED. open exists for tests and the vector
// generator; platforms use the vault forms below so the VMK stays inside.
int pv_envelope3_seal(const uint8_t* vmk, size_t vmk_len, const pv_envelope3_params* p,
                      const uint8_t* kek, size_t kek_len, uint8_t* out, size_t out_cap,
                      size_t* out_len);
int pv_envelope3_open(const uint8_t* buf, size_t len, const uint8_t* kek, size_t kek_len,
                      uint8_t* vmk_out, size_t vmk_out_len);

// Vault forms. create makes dir and dir/objects, refuses an existing
// vault.key (PV_ERR_DEST_EXISTS), generates the VMK, writes the envelope, and
// returns the open handle. open removes a stale vault.key.partial, reads
// vault.key, unwraps, and opens. write rewraps the VMK of an open vault into
// a new envelope through vault.key.partial and a rename over vault.key.
// destroy_files deletes vault.key, its partial, everything in objects/, and
// both directories; a missing directory is not an error.
int pv_vault_create_envelope3(const char* vault_dir, const pv_envelope3_params* p,
                              const uint8_t* kek, size_t kek_len, pv_vault** out);
int pv_vault_open_envelope3(const char* vault_dir, const uint8_t* kek, size_t kek_len,
                            pv_vault** out);
int pv_vault_write_envelope3(pv_vault* v, const pv_envelope3_params* p, const uint8_t* kek,
                             size_t kek_len);
int pv_vault_destroy_files(const char* vault_dir);

// Viewer support (Phase 3B, docs/PHASE3B_DESIGN.md Sections 2, 6, 7).

// Exact plaintext length of an object from its header and its file length,
// without decrypting. The header is validated first and its object ID must
// equal the name. A file too short for a final chunk gives PV_ERR_TRUNCATED;
// bytes that form no whole chunk give PV_ERR_MALFORMED_CHUNK. For a damaged
// file the value is only a plausible number that a decrypt then refutes.
// The _file form takes a path and skips the ID check (used by pv inspect).
int pv_object_plaintext_size(pv_vault* v, const uint8_t* object_id, uint64_t* out);
int pv_object_plaintext_size_file(const char* path, uint64_t* out);

// Decrypts an object into buf. The plaintext length is computed first; if it
// is above cap the call returns PV_ERR_BUFFER_TOO_SMALL before any byte is
// written, and the same holds for every failure of that first step. Once the
// stream starts, any failure (authentication, truncation, trailing data, I/O,
// cancel, a plaintext longer than cap) wipes all cap bytes before the call
// returns. cancel may be NULL; when it points at a nonzero int the call stops
// at the next chunk with PV_ERR_CANCELLED. On PV_OK *out_len (may be NULL) is
// the plaintext length.
int pv_vault_decrypt_to_buffer(pv_vault* v, const uint8_t* object_id, uint8_t* buf, uint64_t cap,
                               uint64_t* out_len, const volatile int* cancel);

// Memory for plaintext: sodium_malloc (guard pages, canary, wiped on free; the
// memory lock is attempted and not guaranteed). n must be nonzero. free takes
// the pointer and the size it was allocated with; NULL is ignored.
int pv_secure_alloc(size_t n, void** out);
void pv_secure_free(void* p, size_t n);

// Removes objects/<id>.pvo and a matching .partial, then syncs the directory.
// A missing object file gives PV_ERR_NOT_FOUND (a lone partial is still
// removed). Removal of the directory entry only; see the threat model.
int pv_vault_delete_object(pv_vault* v, const uint8_t* object_id);

#ifdef __cplusplus
}
#endif

#endif
