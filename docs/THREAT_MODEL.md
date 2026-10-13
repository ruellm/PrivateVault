# Threat model: Phase 1 encrypted storage prototype

Written 2026-10-12 for the Milestone B checkpoint. It covers only what Phase 1 builds: an
encrypted object store on Windows (password protected) and Android (Keystore protected), with
synthetic test data. Sharing, capture, viewing, and networking are later phases and will need
their own threat models.

This document describes the prototype's intended properties. It does not claim that the
prototype has been audited or that it is suitable for real private media.

## 1. Assets

| Asset | Where it lives | Lifetime |
|---|---|---|
| Object plaintext (future photos and videos; synthetic data in Phase 1) | Only in process memory during encrypt or decrypt; CLI decrypt writes it to a user chosen output file | Transient, except the user's own decrypt output |
| Per-object data encryption key (DEK) | Object header, wrapped by the vault master key; in memory while that object is processed | One per object, lives as long as the object |
| Vault master key (VMK) | `vault.key` envelope, wrapped by the platform key; in memory while the vault is open | One per vault |
| Key encryption key (KEK) | Desktop: derived from the password in memory, never stored. Android: inside Android Keystore, never exported | Desktop: per session. Android: until uninstall, clear data, or factory reset |
| Password (desktop only) | Typed into a no-echo prompt or piped through stdin in scripts; held in memory until the KDF finishes | Seconds |
| Metadata | Object count, ciphertext sizes, file timestamps, random object IDs, envelope KDF parameters | Visible on disk |
| Temporary files | `.pvo.partial` while writing an object; a temporary output file while decrypting | Until rename or failure cleanup |

## 2. Attackers and threats in scope

| # | Threat | Attacker capability | Intended defence in Phase 1 |
|---|---|---|---|
| T1 | Copies vault files from disk without the credential | Reads the vault directory (lost laptop, backup copy, another user on the machine) | Every object is encrypted with its own DEK; the DEK is wrapped by the VMK; the VMK is wrapped by a KEK the attacker does not have. No plaintext is stored. |
| T2 | Another Android app reads the vault | Ordinary app without root | Vault lives in internal app-private storage (`filesDir`), which the platform sandbox restricts to the owning app. The Keystore key is bound to the app's UID. Backup and device transfer are disabled so copies do not leave through those paths. |
| T3 | Modifies ciphertext, headers, or envelopes | Write access to the files, or a storage fault | Every chunk carries a Poly1305 tag; the header is bound as additional data to the DEK wrap and to the first chunk; the envelope header is bound as additional data to the VMK wrap. Any change makes decryption fail and the core reports a distinct error. |
| T4 | Truncates a file, appends data, interrupts a write | Same as T3, or a crash mid-write | A missing `FINAL` chunk is a truncation error; bytes after `FINAL` are a trailing-data error; writers use `.partial` plus atomic rename so an interrupted write never leaves a half object under the final name; stale `.partial` files are removed on open. |
| T5 | Guesses the desktop password | Has `vault.key`, can run an offline guess loop | Argon2id with libsodium `MODERATE` parameters raises the per-guess cost. Strong passwords remain the user's responsibility. |
| T6 | Edits the envelope to force a huge or weak KDF | Can write `vault.key` | Stored `opslimit` and `memlimit` are checked against fixed bounds before the KDF runs; out of range values are rejected without allocating. |
| T7 | Learns secrets from logs, errors, crash files, shell history, or the repository | Reads logs or the repo | Error messages carry codes and file names only. Passwords are never command line arguments. Logcat output never contains keys or sample data. Test keys are synthetic and labelled. |
| T8 | Makes the app use unbounded memory with a large or malformed file | Supplies a large object or a header with a huge `chunk_size` | `chunk_size` is validated against a fixed range before any allocation. Encrypt and decrypt hold one chunk at a time. A test compares peak working set between 1 MB and 256 MB inputs. |
| T9 | Causes data loss through restart or reboot | Normal device behaviour | Objects and envelope persist in app storage; a Keystore key survives process death and reboot (Phase 1 does not bind the key to user authentication). T3 tier tests cover force-stop and reboot when a device is available. |

## 3. Threats out of scope and residual risks

- **Rooted or compromised devices.** A debugger, a malicious OS, or a rooted device can read
  process memory, including keys and plaintext, while the vault is open.
- **Malware on the desktop.** A keylogger captures the password before it reaches the prompt.
- **Screen capture and cameras.** Phase 1 has no viewer. Later phases cannot stop an
  authorized viewer from photographing the screen.
- **Memory hygiene limits.** The core zeroes key buffers with `sodium_memzero` and keeps
  long-lived keys in `sodium_malloc` memory. This does not cover copies made by the Kotlin side
  (`ByteArray` objects are managed by the garbage collector and may be copied or kept alive),
  swap and hibernation files on Windows, core dumps, or a compromised process.
- **Metadata.** The ciphertext size reveals the plaintext size to within one chunk. The object
  count and timestamps are visible to anyone who can read the directory. File names are random
  and carry no meaning. Hiding sizes and counts (padding, decoy objects) is not a Phase 1 goal.
- **Deletion.** Removing a `.partial` or temporary file deletes a directory entry. On flash
  storage the old blocks may remain until the controller reuses them. The prototype does not
  promise forensic erasure. Per-object DEKs make cryptographic erasure possible later: destroy
  the wrapped DEK and the ciphertext becomes unreadable.
- **Key loss.** Uninstalling the Android app, clearing its data, or a factory reset destroys the
  Keystore key and with it every object in that vault. Forgetting the desktop password has the
  same effect. There is no recovery path in Phase 1. Migration between devices is a future
  design task.
- **Coercion, shared passwords, physical access to an unlocked device.** Not addressed.
- **Side channels.** Timing or power analysis of libsodium primitives is outside the prototype's
  scope; libsodium's own design choices apply.
- **Audit.** Nothing here has been independently reviewed.

## 4. Trust boundaries

```
+---------------------------------------------------------------+
| Platform layer (trusted to supply the VMK)                    |
|   Desktop: password prompt -> Argon2id -> KEK -> unwrap VMK   |
|   Android: Keystore AES-GCM key -> unwrap VMK                 |
+------------------------------+--------------------------------+
                               | VMK bytes only (C ABI)
+------------------------------v--------------------------------+
| Portable core (vault_core)                                    |
|   object format, DEK wrap/unwrap under VMK, secretstream,     |
|   header validation, chunk rules, atomic finalize, errors     |
+------------------------------+--------------------------------+
                               | stream source/sink interface
+------------------------------v--------------------------------+
| Storage (untrusted bytes): vault.key, objects/*.pvo           |
+---------------------------------------------------------------+
```

- The core treats every byte read from storage as untrusted until a tag verifies it.
- The core never sees a password or a Keystore handle. If a platform layer leaks its KEK,
  the object format is unaffected, but every object in that vault is exposed, because the KEK
  unwraps the VMK and the VMK unwraps every DEK.
- The desktop and Android envelopes are different formats. An object file is identical on both
  platforms; only the envelope differs.

## 5. Engineering practices that follow from this model

- `sodium_init()` once per process through `pv_init()`. The library never aborts: a failed
  `sodium_init()` returns `PV_ERR_INIT`, and every other function returns `PV_ERR_INIT` until
  initialization succeeds.
- Fail closed on any authentication or format error. Output produced before a `FINAL` tag is
  provisional; file decryption writes to a temporary file and renames only after `FINAL`.
- Validate lengths and integer arithmetic before allocating or reading.
- Zero key buffers after use. Keep the VMK in `sodium_malloc` memory while a vault is open.
- No secrets in error messages, logs, test output, or the repository.
- Passwords only through the no-echo prompt or `--password-stdin`.
- Test-only key injection is compiled only with `PV_TEST_HOOKS=ON` on the desktop and only in
  the `androidTest` source set on Android.
