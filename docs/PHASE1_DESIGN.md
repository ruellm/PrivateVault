# Phase 1 design: encrypted object storage

Status: proposed 2026-10-12, approved by the developer on 2026-10-13 with changes (short
reads, deterministic source construction, canonical final chunk, UTF-8 paths, temp-file
collision, Android rename fallback, no aborts). The format version is 1 and is not declared
stable until it has been tested and reviewed.

All sizes come from libsodium constants. The numbers in parentheses are the values those
constants have in libsodium 1.0.22 and are given only so the layouts can be read without the
headers. Code uses the constant names.

| Constant | Value |
|---|---|
| `crypto_secretstream_xchacha20poly1305_KEYBYTES` | 32 |
| `crypto_secretstream_xchacha20poly1305_HEADERBYTES` | 24 |
| `crypto_secretstream_xchacha20poly1305_ABYTES` | 17 |
| `crypto_secretstream_xchacha20poly1305_TAG_MESSAGE` | 0x00 |
| `crypto_secretstream_xchacha20poly1305_TAG_FINAL` | 0x03 |
| `crypto_aead_xchacha20poly1305_ietf_KEYBYTES` | 32 |
| `crypto_aead_xchacha20poly1305_ietf_NPUBBYTES` | 24 |
| `crypto_aead_xchacha20poly1305_ietf_ABYTES` | 16 |
| `crypto_pwhash_SALTBYTES` | 16 |
| `crypto_pwhash_OPSLIMIT_INTERACTIVE` / `MEMLIMIT_INTERACTIVE` | 2 / 64 MiB |
| `crypto_pwhash_OPSLIMIT_MODERATE` / `MEMLIMIT_MODERATE` | 3 / 256 MiB |
| `crypto_pwhash_OPSLIMIT_SENSITIVE` / `MEMLIMIT_SENSITIVE` | 4 / 1 GiB |
| `crypto_generichash_BYTES` | 32 |
| `randombytes_SEEDBYTES` | 32 |

All multi-byte integers in the formats below are little-endian and unsigned.

## 1. Key hierarchy

```
KEK  (platform)  ──wraps──>  VMK  (per vault)  ──wraps──>  DEK  (per object)
Desktop: Argon2id(password)   32 random bytes               32 random bytes
Android: Keystore AES-256-GCM
```

- **KEK, key encryption key.** Never stored by the project. Desktop: derived from the password
  with Argon2id each time the vault is opened. Android: a non-exportable AES-256-GCM key in
  Android Keystore; the project only ever asks Keystore to encrypt or decrypt with it.
- **VMK, vault master key.** 32 bytes from `randombytes_buf` when the vault is created. Stored
  only in `vault.key`, wrapped by the KEK. In memory it lives in `sodium_malloc` storage inside
  the core's vault handle and is wiped with `sodium_memzero` on close.
- **DEK, data encryption key.** 32 bytes from `crypto_secretstream_xchacha20poly1305_keygen`
  per object. Stored in the object header wrapped by the VMK with
  `crypto_aead_xchacha20poly1305_ietf`. Held in a stack buffer during one encrypt or decrypt
  and wiped afterwards.

Why three levels: no key is ever used for two objects; changing a password or a platform key
rewraps only the VMK; per-object sharing (Phase 5) and per-object cryptographic erasure
(Phase 6) only need the DEK wrap to change, not the object format.

The core receives the VMK as bytes and nothing else. Desktop and Android envelopes differ;
object files are identical on both platforms.

## 2. Vault directory

```
<vault>/
  vault.key                     platform key envelope (Section 4 or 5)
  objects/
    <object_id as 32 hex chars>.pvo
    <object_id as 32 hex chars>.pvo.partial   in-progress write, removed on open
```

- Object IDs are 16 random bytes (`randombytes_buf`). File names carry no other meaning.
  Original file names and media types are not stored in Phase 1.
- Opening a vault deletes every `*.pvo.partial` in `objects/`. A `.partial` file is by
  definition incomplete.
- Nothing else is written to the directory. There is no index file in Phase 1; listing
  `objects/` is the index.

## 3. Object file, format version 1

### 3.1 Header (128 bytes, fixed)

| Offset | Size | Field | Value and rule |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `PVOBJECT`. Reader rejects anything else: `MALFORMED_HEADER`. |
| 8 | 2 | `format_version` | 1. Any other value: `UNSUPPORTED_VERSION`. Checked before every other field except magic. |
| 10 | 2 | `suite_id` | 1 = secretstream XChaCha20-Poly1305 payload, XChaCha20-Poly1305-IETF DEK wrap. Any other value: `UNSUPPORTED_SUITE`. |
| 12 | 4 | `chunk_size` | Plaintext bytes per non-final chunk. Allowed range 4096 to 4 194 304 inclusive. Default when writing: 65 536. Out of range: `CHUNK_SIZE_OUT_OF_RANGE`, checked before any allocation. |
| 16 | 16 | `object_id` | Random. Must equal the ID in the file name when opened through a vault; otherwise `MALFORMED_HEADER`. Stream-level APIs do not check it. |
| 32 | 24 | `dek_wrap_nonce` | `crypto_aead_xchacha20poly1305_ietf_NPUBBYTES` random bytes. |
| 56 | 48 | `wrapped_dek` | DEK (32) plus tag (16), produced by `crypto_aead_xchacha20poly1305_ietf_encrypt` with key = VMK, nonce = `dek_wrap_nonce`, additional data = header bytes 0 to 55. Unwrap failure: `WRONG_VMK`. |
| 104 | 24 | `stream_header` | `crypto_secretstream_xchacha20poly1305_HEADERBYTES` from `init_push`. `init_pull` failure: `MALFORMED_HEADER`. |

Offsets 0 to 55 are the "wrap-bound" prefix. Because `object_id`, `chunk_size`, version, and
suite are inside it, a change to any of them makes the DEK unwrap fail. Because the entire
128 bytes are the additional data of the first chunk, a change to `stream_header` or
`wrapped_dek` (even a change that somehow still unwrapped) makes the first chunk fail.

### 3.2 Chunks

Immediately after the header, a sequence of secretstream messages. Each ciphertext message is
its plaintext length plus `crypto_secretstream_xchacha20poly1305_ABYTES` (17).

Writer rules (canonical form):

- Every non-final chunk carries exactly `chunk_size` plaintext bytes with tag `TAG_MESSAGE`.
- The final chunk carries exactly `n mod chunk_size` plaintext bytes with tag `TAG_FINAL`,
  where `n` is the plaintext length. When `n` is a multiple of `chunk_size` (including
  `n = 0`) the final chunk is empty: 17 bytes of ciphertext holding no plaintext.
- The additional data of the first chunk is the full 128-byte header. Later chunks have no
  additional data; the secretstream state chains them to the first.
- Tags `TAG_PUSH` and `TAG_REKEY` are never written.
- The golden vectors follow this form.

Reader rules. The reader accepts a final chunk of 0 to `chunk_size` plaintext bytes, so a
non-canonical but authenticated file still decrypts. A `pv_source` read may return fewer bytes
than requested without being at the end of the data; the reader fills its chunk buffer by
calling `read` repeatedly until the buffer holds `chunk_size + ABYTES` bytes or a call returns
zero bytes. Only a zero-byte read means end of data. The rules, applied in this order for
every chunk:

1. Fill the chunk buffer as above.
2. Zero bytes collected before any chunk was seen: `TRUNCATED` (even an empty object has a
   FINAL chunk). Zero bytes collected after a `TAG_MESSAGE` chunk: `TRUNCATED` (end of file
   without FINAL).
3. Fewer than `ABYTES` bytes collected: `TRUNCATED`.
4. Decrypt with `crypto_secretstream_xchacha20poly1305_pull`. Failure: `AUTH_FAILED`.
5. Tag `TAG_MESSAGE`: the buffer must hold exactly `chunk_size + ABYTES` bytes,
   otherwise `MALFORMED_CHUNK`. Write the plaintext and continue with step 1.
6. Tag `TAG_FINAL`: attempt to read one more byte, again looping until a byte arrives or a
   read returns zero. If a byte arrives: `TRAILING_DATA`. Otherwise write the plaintext and
   finish with `OK`.
7. Any other tag: `MALFORMED_CHUNK`.

Only when step 6 returns `OK` is the output complete. Each chunk is authenticated as it is
decrypted, but completeness is known only at FINAL, so the file decrypt path writes to a
temporary file and renames it only at the end (Section 7). A future streaming consumer must treat
output before FINAL as provisional.

A truncation that cuts inside a chunk removes part of that chunk's tag or ciphertext, so it
surfaces as `AUTH_FAILED`, not `TRUNCATED`. A truncation at a chunk boundary surfaces as
`TRUNCATED`. Both fail closed; the report distinguishes them only for diagnostics.

Trailing data has the same limit. Chunks are not length-prefixed, so when the final chunk is
shorter than `chunk_size` the reader cannot tell where it ends: extra bytes after it are read
into the same buffer and the chunk fails authentication (`AUTH_FAILED`). `TRAILING_DATA` is
reported when the final chunk is full-size and any byte follows it. In every case the file is
rejected and nothing is reported complete. Recorded as D-007 in `docs/DECISIONS.md` with the
alternative (a length trailer) for a later format revision.

### 3.3 Size arithmetic

- Plaintext length `n`, chunk size `c`: number of chunks = `n / c + 1` (integer division; the
  `+1` is the final chunk, which may be empty when `n` is a multiple of `c`).
- Ciphertext length = 128 + `n` + 17 × number of chunks.
- The reader never allocates more than one chunk buffer (`c + 17` bytes) plus one plaintext
  buffer (`c` bytes). The writer is the same. Memory is bounded by `chunk_size`, which the
  range check caps at 4 MiB.
- All of this uses 64-bit arithmetic and is checked before any allocation.

### 3.4 Metadata exposure

- Ciphertext size reveals plaintext size to within one chunk (exactly, once `chunk_size` is
  read from the header).
- Object count and file timestamps are visible to anyone who can list the directory.
- File names are random.
- Nothing in the header identifies the vault, the platform, or the user.

## 4. Desktop key envelope (`vault.key`, provider 1)

Written and read by the desktop adapter (`src/desktop_cli`), not by the core.

| Offset | Size | Field | Value and rule |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `PVKEYENV`. Otherwise `ENVELOPE_MALFORMED`. |
| 8 | 2 | `version` | 1. Otherwise `ENVELOPE_UNSUPPORTED`. |
| 10 | 2 | `provider_id` | 1 = password, Argon2id. Otherwise `ENVELOPE_UNSUPPORTED` (a desktop reader given an Android envelope stops here). |
| 12 | 4 | `opslimit` | Argon2id passes. Accepted range: `crypto_pwhash_OPSLIMIT_INTERACTIVE` (2) to 16 inclusive. |
| 16 | 8 | `memlimit` | Argon2id memory in bytes. Accepted range: `crypto_pwhash_MEMLIMIT_INTERACTIVE` (64 MiB) to `crypto_pwhash_MEMLIMIT_SENSITIVE` (1 GiB) inclusive. |
| 24 | 16 | `salt` | `crypto_pwhash_SALTBYTES` random bytes. |
| 40 | 24 | `nonce` | `crypto_aead_xchacha20poly1305_ietf_NPUBBYTES` random bytes. |
| 64 | 48 | `wrapped_vmk` | VMK (32) plus tag (16). Key = Argon2id output (32 bytes), additional data = bytes 0 to 63. |

Total 112 bytes.

- Out-of-range `opslimit` or `memlimit`: `ENVELOPE_KDF_PARAMS`, returned before the KDF runs
  and before any memory is requested. The upper bound stops a modified envelope from forcing a
  multi-gigabyte allocation; the lower bound stops it from weakening the derivation below the
  libsodium interactive preset.
- Default write parameters: `crypto_pwhash_OPSLIMIT_MODERATE` and
  `crypto_pwhash_MEMLIMIT_MODERATE` (3 passes, 256 MiB), algorithm
  `crypto_pwhash_ALG_ARGON2ID13`, passed explicitly rather than through `ALG_DEFAULT`.
- Tests use `INTERACTIVE` parameters through the test-only path (`PV_TEST_HOOKS`), which are
  inside the accepted range.
- Unwrap failure (wrong password, modified salt, nonce, or ciphertext): `ENVELOPE_UNWRAP_FAILED`.
  The message does not say which.
- The password is read with a no-echo console prompt (`SetConsoleMode` without
  `ENABLE_ECHO_INPUT`) or from stdin with `--password-stdin`. It is never a command line
  argument. The password buffer and the derived key are wiped with `sodium_memzero`.
- Changing the password is not implemented in Phase 1. When it is, it means: unwrap the VMK with
  the old password, write a new envelope with a new salt and nonce to `vault.key.partial`, and
  rename. The object files do not change.

## 5. Android key envelope (`vault.key`, provider 2)

Written and read by the Kotlin key provider, not by the core.

| Offset | Size | Field | Value and rule |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `PVKEYENV`. |
| 8 | 2 | `version` | 1. |
| 10 | 2 | `provider_id` | 2 = Android Keystore AES-256-GCM. |
| 12 | 2 | `alias_len` | Length of the following alias, 1 to 64. |
| 14 | `alias_len` | `alias` | Keystore alias, UTF-8. Phase 1 uses `privatevault.vmk.v1`. |
| | 2 | `iv_len` | Length of the IV as returned by the Keystore cipher (12 for GCM). |
| | `iv_len` | `iv` | The IV Keystore generated. The app never supplies an IV. |
| | 2 | `wrapped_len` | Length of the following field (48 for a 32-byte VMK with a 16-byte tag). |
| | `wrapped_len` | `wrapped_vmk` | Output of `Cipher.doFinal` in GCM mode: ciphertext plus tag. AAD = every preceding byte of the file (from offset 0 through `wrapped_len`). |

- Keystore key: `KeyGenParameterSpec` with `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, key size 256,
  `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, `setRandomizedEncryptionRequired(true)`,
  `setUnlockedDeviceRequired(true)` (minSdk 28 allows it), no user authentication requirement.
- `setIsStrongBoxBacked(true)` is tried first when `PackageManager.FEATURE_STRONGBOX_KEYSTORE`
  is present; on `StrongBoxUnavailableException` the key is generated again without it. The app
  reports the actual level from `KeyInfo.getSecurityLevel()` (API 31+) or
  `KeyInfo.isInsideSecureHardware()` (API 28 to 30): software, trusted environment, or StrongBox.
- GCM authentication failure (modified envelope, wrong key): `ENVELOPE_UNWRAP_FAILED`.
- The envelope is written to `vault.key.partial` in `filesDir/vault/` and renamed.
- Uninstall, Clear Data, and factory reset delete the Keystore key. The envelope then cannot be
  opened and every object in the vault is unreadable. This is the expected Phase 1 behaviour and
  is shown in the app's text.
- The VMK is a Kotlin `ByteArray` for the moment between unwrap and the JNI call that copies it
  into `sodium_malloc` memory. The Kotlin side overwrites its array afterwards, but the garbage
  collector may already have copied it. This limit is recorded in the threat model.

## 6. Nonce and key lifecycle

| Secret | Created by | Stored | Wiped |
|---|---|---|---|
| DEK | `crypto_secretstream_xchacha20poly1305_keygen` per encrypt | Wrapped in the object header | `sodium_memzero` after `init_push` or `init_pull` |
| DEK wrap nonce | `randombytes_buf` per object | Object header, plaintext | Not secret |
| secretstream header | `init_push` (contains a random nonce) | Object header, plaintext | Not secret |
| VMK | `randombytes_buf` at vault creation | Wrapped in `vault.key` | `sodium_free` on vault close (which zeroes) |
| VMK wrap nonce (desktop) | `randombytes_buf` per envelope write | `vault.key`, plaintext | Not secret |
| Argon2id salt | `randombytes_buf` per envelope write | `vault.key`, plaintext | Not secret |
| Derived KEK (desktop) | `crypto_pwhash` per open | Never | `sodium_memzero` after unwrap |
| GCM IV (Android) | Keystore per envelope write | `vault.key`, plaintext | Not secret |

Nonce reuse analysis: every DEK is used for exactly one secretstream, and secretstream
derives its own per-message nonces from the header and an internal counter with automatic
rekeying. Every DEK wrap uses a fresh random 192-bit nonce under the VMK; with random 192-bit
nonces the collision probability is negligible for any realistic number of objects. The VMK
wrap uses one random nonce per envelope write.

## 7. Atomic writes and temporary files

Writer (encrypt into a vault):

1. Create `objects/<id>.pvo.partial`. If it exists, fail with `DEST_EXISTS` (another writer or a
   crash in progress); the open-time cleanup handles stale files.
2. Write header and chunks.
3. Flush: `FlushFileBuffers` on Windows, `fsync` elsewhere.
4. Close, then rename to `objects/<id>.pvo`:
   - Windows: `MoveFileExW(partial, final, MOVEFILE_WRITE_THROUGH)` without
     `MOVEFILE_REPLACE_EXISTING`, so an existing destination fails with `DEST_EXISTS`.
   - POSIX (Android), preferred: `link(partial, final)` (fails with `EEXIST` if the
     destination exists), then `unlink(partial)`, then `fsync` on the directory. `rename` alone
     is not used because it replaces an existing destination silently.
   - POSIX fallback, which is the path Android actually takes: SELinux denies `link` inside
     app data for untrusted apps (errno 13 on the Android 15 emulator, see D-008 in
     `docs/DECISIONS.md`). When `link` fails for any reason other than `EEXIST`, the core
     checks that the destination does not exist (`stat`) and then calls `rename`, then
     `fsync` on the directory. Between the check and the rename another writer could create
     the same name; with 128-bit random object IDs chosen by the same process this is a
     negligible race, and the open-time cleanup only ever removes `.partial` files. An
     existing destination is never replaced silently in either path.
5. On any failure before step 4 completes, delete the `.partial` file and return the error.

Reader (decrypt to a file):

1. If the destination exists, fail with `DEST_EXISTS` before writing anything.
2. Create `<destination>.pvtmp` in the destination directory. If that file already exists,
   fail with `DEST_EXISTS` without touching it (it may belong to another decrypt in progress).
3. On `OK` from the chunk reader: flush, close, rename as above.
4. On any error: close and delete the temporary file. The error is returned and no file exists
   under the destination name.

Paths passed across the C ABI are UTF-8. On Windows the core converts them to UTF-16 with
`MultiByteToWideChar` and uses only wide file APIs (`CreateFileW`, `MoveFileExW`,
`GetFileAttributesW`, `CreateDirectoryW`, `FindFirstFileW`, `DeleteFileW`). On POSIX the
bytes are passed through unchanged. A host test uses a vault directory with a non-ASCII name.

Deleting a temporary file removes a directory entry. On flash storage the old data may remain
until the controller reuses the blocks. The prototype does not promise erasure of temporary
plaintext written to disk. The Android demo never writes plaintext to disk at all: it decrypts
into a BLAKE2b hash sink.

## 8. Error model

C ABI integer codes. `pv_error_name(code)` returns a static string for each.

| Code | Name | Meaning |
|---|---|---|
| 0 | `PV_OK` | |
| 1 | `PV_ERR_INIT` | `sodium_init` failed |
| 2 | `PV_ERR_INVALID_ARGUMENT` | Null pointer, bad length, or bad handle from the caller |
| 3 | `PV_ERR_IO` | A source, sink, or file operation failed |
| 4 | `PV_ERR_DEST_EXISTS` | Destination file or `.partial` already exists |
| 5 | `PV_ERR_MALFORMED_HEADER` | Bad magic, short header, object ID mismatch, or secretstream header rejected |
| 6 | `PV_ERR_UNSUPPORTED_VERSION` | `format_version` is not 1 |
| 7 | `PV_ERR_UNSUPPORTED_SUITE` | `suite_id` is not 1 |
| 8 | `PV_ERR_CHUNK_SIZE_OUT_OF_RANGE` | `chunk_size` outside 4096 to 4 194 304 |
| 9 | `PV_ERR_WRONG_VMK` | DEK unwrap failed (wrong vault key or modified wrap-bound prefix) |
| 10 | `PV_ERR_AUTH_FAILED` | A chunk failed authentication |
| 11 | `PV_ERR_TRUNCATED` | End of data without a FINAL chunk, or a chunk shorter than the tag |
| 12 | `PV_ERR_TRAILING_DATA` | Bytes after the FINAL chunk |
| 13 | `PV_ERR_MALFORMED_CHUNK` | Unexpected tag or a non-final chunk of the wrong size |
| 14 | `PV_ERR_ENVELOPE_MALFORMED` | Envelope magic or length wrong |
| 15 | `PV_ERR_ENVELOPE_UNSUPPORTED` | Envelope version or provider not handled by this reader |
| 16 | `PV_ERR_ENVELOPE_KDF_PARAMS` | Stored KDF parameters outside the accepted range |
| 17 | `PV_ERR_ENVELOPE_UNWRAP_FAILED` | Wrong password or key, or modified envelope |
| 18 | `PV_ERR_OUT_OF_MEMORY` | Allocation failed |
| 19 | `PV_ERR_INTERNAL` | A libsodium call that should not fail did |
| 20 | `PV_ERR_NOT_FOUND` | No object with that ID in the vault, or the vault directory is missing |

Envelope codes 14 to 17 are reported by the platform adapters but share the numbering so the
CLI and the app print one table. Messages never include key bytes, passwords, or plaintext.

## 9. C ABI

The authoritative declaration is `src/vault_core/include/pv/pv.h`. The sketch below was the
checkpoint proposal; the implementation added `PV_ERR_NOT_FOUND`, an `expected_object_id`
argument on `pv_object_decrypt`, `pv_vault_object_path`, the `pv_file_*` helpers (file
streams, `pv_file_exists`, `pv_file_read_all`, `pv_file_write_atomic`, `pv_file_delete`) used
by both key providers, `pv_sink_blake2b_abort`, and the object ID hex helpers.

```c
// Opaque vault handle. Holds the VMK in sodium_malloc memory and the vault path.
typedef struct pv_vault pv_vault;

// Caller-supplied streams. read may return fewer bytes than capacity at any time;
// *out_n == 0 means end of data. Nonzero return = I/O error. Paths are UTF-8.
typedef struct pv_source {
    void* ctx;
    int (*read)(void* ctx, uint8_t* buf, size_t capacity, size_t* out_n);
} pv_source;
typedef struct pv_sink {
    void* ctx;
    int (*write)(void* ctx, const uint8_t* buf, size_t n);
} pv_sink;

// Header fields without any key material.
typedef struct pv_object_info {
    uint16_t format_version;
    uint16_t suite_id;
    uint32_t chunk_size;
    uint8_t  object_id[16];
} pv_object_info;

int  pv_init(void);
const char* pv_error_name(int code);

// Key material helpers used by the platform adapters.
int  pv_vmk_generate(uint8_t* out, size_t out_len);     // out_len must be PV_VMK_BYTES (32)

// Vault lifecycle. open copies the VMK, removes stale .partial files, and keeps the path.
int  pv_vault_create(const char* dir);                   // creates dir and dir/objects
int  pv_vault_open(const char* dir, const uint8_t* vmk, size_t vmk_len, pv_vault** out);
void pv_vault_close(pv_vault* v);                       // wipes the VMK

// Objects inside a vault. chunk_size 0 means the default.
int  pv_vault_encrypt(pv_vault* v, pv_source* src, uint32_t chunk_size, uint8_t* object_id_out);
int  pv_vault_decrypt(pv_vault* v, const uint8_t* object_id, pv_sink* dst);
int  pv_vault_verify(pv_vault* v, const uint8_t* object_id, uint64_t* plaintext_len_out);
int  pv_vault_decrypt_to_file(pv_vault* v, const uint8_t* object_id, const char* dest_path);
int  pv_vault_encrypt_file(pv_vault* v, const char* src_path, uint32_t chunk_size, uint8_t* object_id_out);
int  pv_vault_inspect(pv_vault* v, const uint8_t* object_id, pv_object_info* out);

// Stream-level operations with no vault directory. Used by tests, golden vectors, and inspect.
int  pv_object_encrypt(const uint8_t* vmk, size_t vmk_len, pv_source* src, pv_sink* dst,
                       uint32_t chunk_size, const uint8_t* object_id_or_null);
int  pv_object_decrypt(const uint8_t* vmk, size_t vmk_len, pv_source* src, pv_sink* dst,
                       uint64_t* plaintext_len_out);
int  pv_object_inspect(pv_source* src, pv_object_info* out);   // header only, no key

// Test data helpers (no secrets): a deterministic byte source from a 32-byte seed, and a
// BLAKE2b hashing sink. Used by host tests and the Android demo so no plaintext touches disk.
int  pv_source_deterministic_new(const uint8_t* seed, size_t seed_len, uint64_t length, pv_source* out);
void pv_source_deterministic_free(pv_source* s);
int  pv_sink_blake2b_new(pv_sink* out);
int  pv_sink_blake2b_finish(pv_sink* s, uint8_t* hash_out, size_t hash_len);  // also frees
int  pv_hash_deterministic(const uint8_t* seed, size_t seed_len, uint64_t length,
                           uint8_t* hash_out, size_t hash_len);
```

- No C++ exceptions cross this boundary; every C++ entry point catches and maps to
  `PV_ERR_OUT_OF_MEMORY` or `PV_ERR_INTERNAL`.
- Every pointer and length is checked before use.
- JNI calls exactly these functions. A future Swift client can call them through a C module map.

## 10. Portability seam

- `src/vault_core` has no Windows or Android specific code except the atomic rename helper,
  which has two implementations selected with `#ifdef _WIN32`. Everything else is standard
  C++20 and libsodium.
- Key providers are "wrap and unwrap these 32 bytes": the desktop provider (Argon2id, C++) and
  the Android provider (Keystore, Kotlin) both end in a call to `pv_vault_open` with raw VMK
  bytes. An iOS provider would use Keychain or Secure Enclave the same way.
- File I/O for objects goes through `pv_source` and `pv_sink`, so tests inject failures and
  platforms can supply their own streams. The file based helpers are thin wrappers over the
  same functions.

## 11. Video seeking (document only, decision for Phase 3)

`crypto_secretstream` is sequential: to decrypt the chunk at offset `o` the reader must pull
every earlier chunk, because each message's nonce and key depend on the ones before it. Seeking
to the middle of a long video would cost reading from the start.

Option 1: **segment objects.** Store a video as many short objects (for example 2 to 10 seconds
each), each a normal version 1 object with its own DEK, plus an encrypted index object that lists
the segments in order. Seeking means picking the right segment and decrypting it from its start.
Granularity is one segment. No new cryptographic construction; version 1 objects are reused as
is; the index is itself a version 1 object. Cost: more files and a small per-segment overhead.

Option 2: **random-access chunk format.** A new `suite_id` where every chunk is an independent
`crypto_aead_xchacha20poly1305_ietf` message with a nonce derived from the chunk index and
additional data binding the header, the chunk index, and a last-chunk flag (the STREAM
construction). Any chunk can be decrypted alone. Cost: the project owns a construction that must
be reviewed, including its handling of truncation, chunk reordering, and the last-chunk flag.

Recommendation: option 1 is the default direction. Option 2 only if measured playback needs in
Phase 3 show that segment granularity is not enough. The `suite_id` field keeps option 2 open
without changing version 1 objects.

## 12. Migration, rotation, and recovery limits

- No password change, key rotation, vault migration, or recovery in Phase 1.
- A desktop vault and an Android vault with the same VMK would share objects, but no VMK
  transfer path exists. Designing one (export under a recovery key, QR transfer, paired-device
  transfer) is a Phase 6 task.
- A future format version 2 can change the header; the reader must reject version 2 files today
  (`UNSUPPORTED_VERSION`), and tests cover that.
- The envelope format can evolve independently of the object format because the core never
  reads an envelope.

## 13. Test-only paths

- Desktop: the CLI gains `--test-vmk-file <path>` and `--kdf interactive` only when built with
  `-DPV_TEST_HOOKS=ON`. The production build has no code path that accepts a key from a file.
- Android: a test VMK and a direct `pv_vault_open` path exist only in the `androidTest` source
  set. The main source set always goes through the Keystore provider.
- Golden vectors in `tests/fixtures/v1/` are encrypted under the test VMK
  `PRIVATEVAULT TEST ONLY VMK  0001` (32 ASCII bytes) with plaintext produced by the
  deterministic source below from the seed `PRIVATEVAULT TEST ONLY SEED 0001`
  (32 ASCII bytes). Both are labelled TEST ONLY in the fixture directory and are not secrets.

### Deterministic byte source

The test data generator must produce the same bytes for a given seed regardless of how the
reads are sized, because the golden vectors, the host tests, and the Android demo all
regenerate the same plaintext in pieces. `randombytes_buf_deterministic` restarts its stream
on every call, so it is not used. The construction is:

- Byte `i` of the stream (0-based, `i < length`) is byte `i` of the ChaCha20-IETF keystream
  with key = the 32-byte seed, nonce = 12 zero bytes, initial block counter 0.
- Equivalently, the whole stream is `crypto_stream_chacha20_ietf(out, length, zero_nonce, seed)`.
- The streaming source keeps a byte offset `o`. For each read of `n` bytes it calls
  `crypto_stream_chacha20_ietf_xor_ic` over zero bytes with block counter `o / 64`,
  generates from the start of that block, and discards the first `o mod 64` bytes.
- A host test checks that one seed and length give the same BLAKE2b hash for read sizes
  1, 4096, and 1 MiB.

This is test data only. The seed is public and the stream carries no secret.
