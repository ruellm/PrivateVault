# Threat model: encrypted storage and capture prototype

Written 2026-10-12 for the Phase 1 Milestone B checkpoint; extended 2026-10-13 for Phase 2
(Section 6). It covers what Phases 1 and 2 build: an encrypted object store on Windows
(password protected) and Android (Keystore protected), and in-app photo capture into that
store on Android. Sharing, viewing, and networking are later phases and will need their own
threat models.

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
- **Android without a secure lock screen.** Keystore refuses `setUnlockedDeviceRequired(true)`
  when no PIN, pattern, or password is set, so the app then creates the key without that flag
  ("not lock-bound" mode, `docs/PHASE1_DESIGN.md` Section 5, D-010). Such a key is usable
  whenever the device is on: the vault still protects against copying the files and against
  other apps, but not against someone holding the phone. An in-app lock is a later-phase item.
  A key keeps the mode it was created with if the user later adds or removes a screen lock;
  existing keys are not migrated in Phase 1, and whether removing a lock invalidates a
  lock-bound key on real devices is unverified.
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

## 6. Phase 2 additions: capture

What Phase 2 claims, and only this: **app code writes no plaintext media to storage, and
nothing in app-visible locations contains it** (checked by the `leak_scan` action and test:
app data directories, external app directories, MediaStore rows owned by the app, logcat).
Everything below the app is outside that claim.

### 6.1 New assets

| Asset | Where it lives | Lifetime |
|---|---|---|
| Captured JPEG bytes | The `ImageReader` buffer behind the CameraX `ImageProxy` (native memory the camera stack owns); read in place by the core through the memory source | Until `ImageProxy.close()`; not zeroed by the app |
| The same frame before encoding | Camera HAL buffers, image signal processor, gralloc buffers, the preview `Surface`, and any internal CameraX copy (its software JPEG and effects paths use Java byte arrays; on the emulator the in-memory path delivered a direct buffer straight from the HAL stream) | Camera stack lifetime; not under app control |
| Item header (type, rotation, size, capture time) | Inside the encrypted plaintext of the object | As long as the object |
| Video segments and index (prototype only) | `FragmentedMp4Muxer` Java buffers, a Java `ByteArrayOutputStream` per segment, one native guarded copy for encryption; debug verification reads segments back into Java arrays | Until the segment is encrypted; the Java copies are subject to garbage collection timing |

### 6.2 New threats and the Phase 2 position

| # | Threat | Position |
|---|---|---|
| T10 | Screenshot, screen recording, or the recents thumbnail shows the viewfinder | `FLAG_SECURE` on the capture activity. Verified on the emulator: `screencap` of the capture screen and of the recents card are black. Not verified on the phone (not connected). |
| T11 | A capture leaves plaintext in app storage (temporary files, caches, crash files) | No file output path exists in the capture code (`OutputFileOptions`, `OnImageSavedCallback`, `MediaStore` output, `FileOutputStream` are absent from `src/main` except the read-only MediaStore query of the scan). `leak_scan` after captures and after both video probes found 0 markers and no new files besides `objects/*.pvo` on the emulator. |
| T12 | A capture leaves plaintext in the shared media collections | No MediaStore output is used; `leak_scan` queries images, video, and files for rows owned by the app: 0. |
| T13 | Image bytes copied into the Kotlin heap by app code | On the emulator every capture arrived in a direct buffer and was read in place (`buffer=DIRECT` in every log line). The heap-array fallback (one native copy into guarded memory from a CameraX-made array) and the last-resort Kotlin copy exist in `Vault.encryptItem` and are reported in the log line when taken; the Kotlin copy path never ran on the emulator. |
| T14 | Interrupted capture leaves a half object | The core's `.partial` plus rename rule from Phase 1. Verified: a kill during a slowed encrypt left one `.partial` which the next open removed; verify of all other objects passed. |
| T15 | Metadata: sizes and times | Object sizes still equal plaintext size plus 48 bytes of item header plus chunk overhead, so photos (hundreds of KB to several MB), video segments (about 1 MB each, several per clip), and the small index object are distinguishable by size; object mtimes equal capture times to within the encrypt time; a clip's length is the count of segments. Unchanged position: documented, not hidden. The item header itself (orientation, dimensions, capture time) is encrypted. |
| T16 | Camera permission misuse | Only `CAMERA` is requested, at runtime, from the capture screen. `ACCESS_NETWORK_STATE` declared by a media3 library is removed from the merged manifest (D-013). No location permission, so no GPS reaches any EXIF data. |

### 6.3 Outside app control, not verifiable without root

Camera HAL and vendor buffers (`/data/vendor` dumps, ISP memory), MIUI system services on
the phone, kernel and GPU buffers, swap and zram contents, the `ImageReader` buffer after
`close()`, and any copy CameraX makes internally. The prototype does not claim erasure of
any of these. The Kotlin `ByteArray` copy of the VMK from Phase 1 is unchanged.

## 7. Phase 3A additions: PIN lock (proposed 2026-10-14, to be confirmed at Milestone E)

What Stage 3A claims, and only this: **a provider 3 vault opens only with the correct PIN
on the device that holds its Keystore key, and the app shows no vault content while
locked.** The construction (Argon2id, then HMAC-SHA256 under a non-exportable Keystore
key, then XChaCha20-Poly1305 unwrap of the VMK) composes standard primitives and has not
been reviewed. Design: `docs/PHASE3A_DESIGN.md`.

### 7.1 New assets

| Asset | Where it lives | Lifetime |
|---|---|---|
| PIN digits | A `ByteArray` filled by the in-app keypad, zeroed after the stretch call | Seconds |
| `k_pin` (stretched PIN) | Core output copied into a Kotlin `ByteArray` for the Keystore call, zeroed after | Milliseconds |
| KEK | Keystore `Mac` output in a Kotlin `ByteArray`, copied into the core for the unwrap, zeroed after | Milliseconds |
| Keystore HMAC key | Android Keystore, alias named in the envelope | Until rewrap, erase, Clear Data, uninstall, or factory reset |
| Attempt counter and clocks | App-private preferences | Until a successful unlock or erase |

### 7.2 Threats and the Stage 3A position

| # | Threat | Position |
|---|---|---|
| T17 | Someone holding the phone opens the vault | The PIN. On a device without a secure lock screen (the Xiaomi), the Keystore key is usable whenever the device is on, so the PIN and its guessing cost are the whole defence. |
| T18 | Copies `vault.key` and guesses offline | Each guess needs the Keystore key, which does not leave the device. Off the device the copy is useless. |
| T19 | Guesses on the device | Each guess costs one Argon2id run (target 0.5 to 1.0 s in the optimised build) plus one Keystore call. Six digits are 6 to 12 days of single-threaded work at that cost; each extra digit multiplies it by ten. The app adds delays after five wrong attempts (30 s doubling, cap 1 h), persistent across restarts and clock changes. No hardware attempt counter exists for app Keystore keys; an attacker with root can reset the delays and is limited by the KDF cost alone. |
| T20 | Old envelope copy after a PIN change | The Keystore key is replaced at every rewrap and the old alias deleted after the commit (D-017), so an old copy has no key to run with. Key deletion is as strong as the Keystore implementation. |
| T21 | App left open, screen off, backgrounded, or killed | Lock on background, screen off, 5 minutes idle, manual lock; the process never starts unlocked. While locking, queued captures are dropped and an in-flight encrypt is cancelled, which makes the core delete its `.partial`. |
| T22 | Screenshot or recents thumbnail of the lock screen or a dialog | `FLAG_SECURE` on every activity; confirmations are in-activity views, not dialogs. |
| T23 | Interrupted migration, PIN change, or erase | Rewrap commits by a rename over `vault.key`; the old key is deleted only after it. Erase sets a pending flag first and the next start finishes it. |
| T24 | Secrets in logs | No PIN, `k_pin`, `kek`, or PIN length is logged; T2 scans logcat for them. Test PINs are throwaway. |

### 7.3 Residual risks

- Kotlin copies: the PIN digits, `k_pin`, and `kek` exist as managed `ByteArray` objects
  for a short time; the garbage collector may copy them before they are zeroed. The VMK
  itself no longer crosses JNI for a provider 3 vault (setup and rewrap happen inside the
  core); the provider 2 migration is the one remaining crossing.
- Delays are app-enforced state in a preferences file; root or Clear Data removes them
  (Clear Data also removes the Keystore keys and so the vault).
- Argon2id memory: the unlock allocates `memlimit` bytes once; on a low-memory device the
  allocation may fail and the unlock reports it instead of weakening the parameters.
- Erased files are directory-entry removals; flash blocks may persist until reuse.
- Nothing here has been independently reviewed.

## 8. Phase 3B additions: photo viewer and delete (approved 2026-10-14 at the Milestone B checkpoint, to be confirmed at Milestone E)

What Stage 3B claims, and only this: **a photo is decrypted for display only into memory
the app controls, that memory is wiped when the decode is done, the decoded bitmap is erased
when the viewer stops or the vault locks, and nothing about a photo is written to storage.**
What the platform does with the pixels after the app hands them to a view is outside that
claim. Design: `docs/PHASE3B_DESIGN.md`.

### 8.1 New assets

| Asset | Where it lives | Lifetime |
|---|---|---|
| Decrypted object plaintext (item header plus JPEG) | One `sodium_malloc` block per load (guard pages, canary, wiped on free; not locked, see 8.3) | From decrypt to the end of the decode, tens of milliseconds |
| Decoded pixels | A mutable software `Bitmap` in the viewer, and up to 32 thumbnails of 256 px in a cache | Until the activity stops, the vault locks, or the cache evicts; erased with `eraseColor(0)` before `recycle()` |
| Decoder, render, and compositor copies | `ImageDecoder` scratch rows, the view render node and its GPU texture, SurfaceFlinger buffers | Platform lifetime; not under app control |
| Object list metadata | Object IDs, file lengths, modification times read from the directory for the gallery | Shown while the gallery is open; already visible on disk (Phase 1 position) |

### 8.2 Threats and the Stage 3B position

| # | Threat | Position |
|---|---|---|
| T25 | Screenshot, screen recording, or recents card of the gallery or viewer | `FLAG_SECURE` on both activities; the recents card is checked on the Xiaomi in T3. No `Dialog`, popup, or toast carries content (P3-14). |
| T26 | Plaintext left in memory after viewing | The guarded block is wiped right after the decode on every path (success, failure, cancel); the bitmap is erased and recycled on stop, lock, eviction, and replacement; a load whose result arrives after a stop is erased at once. Decoder scratch, render node, GPU texture, and compositor buffers are not erased by the app. |
| T27 | Plaintext in zram | Decrypted buffers are guarded and wiped; whether they are locked depends on the device. Measured in the app process (`alloc_probe`): on the Xiaomi 2201116SG the limit is 64 MiB and `mlock` succeeds up to 64 MiB, so the viewer buffer (at most 32 MiB) is locked and cannot reach zram; on the emulator the limit is 64 KiB, the lock is refused, `sodium_malloc` ignores the refusal, and the pages can reach zram (compressed swap in RAM) while the buffer exists. The limit is a per-device property the app cannot raise. Bitmaps are ordinary heap memory and can reach zram on any device. |
| T28 | A damaged, truncated, or wrong-key object shows a partial image | The buffer is filled by the Phase 1 decrypt and used only after the FINAL chunk verified; on any failure the core wipes the whole buffer before returning. A damaged object is a labelled tile. |
| T29 | An oversized object exhausts memory | Objects above 32 MiB of plaintext are never decrypted for display; the size comes from the header and file length without decrypting. |
| T30 | Lock while a photo is being decrypted or decoded | The decrypt stops at the next chunk with `PV_ERR_CANCELLED` and the buffer is wiped; a decode in flight ends within its bound and its bitmap is erased; the activities finished on `LOCKED_UI`. |
| T31 | Delete does not remove the content | Delete removes the directory entry of the object (and a stale partial). The ciphertext blocks may persist on flash until reuse; the DEK that would decrypt them is inside that same ciphertext, wrapped by the VMK. No erasure beyond the directory entry is claimed; cryptographic erasure is a Phase 6 item. |
| T32 | Thumbnails or decoded images reach storage | No thumbnail store, no cache files, no `MediaStore` output; `leak_scan` after a viewer session that opens every photo and scrolls the gallery is a T2 and T3 step. |

### 8.3 Residual risks

- Decoder and GPU copies (8.1, T26) and, on devices with a small memory lock limit, the
  unlocked guarded block (T27) are outside the app; the app does not claim erasure of them.
- EXIF inside the JPEG payload: the Xiaomi writes make, model, and an embedded thumbnail
  into the capture; the emulator writes make, model, and the orientation tag. All of it is
  inside the encrypted object and is shown nowhere by the viewer, but it travels with the
  payload if a photo is ever shared (Phase 5 item in `docs/ROADMAP.md`). Which tags are
  present is recorded in `docs/PHASE3B_TEST_REPORT.md`.
- The heap finding of the capture burst (brief 4.7): on the emulator (Stage 3B, Milestone
  D) the Java heap peak rises by about 1 MB per 571 KB capture until a collection and
  drops back after it, the same shape as on the Xiaomi in Phase 2; the allocating class and
  whether the array holds image data are not yet measured (the Studio profiler step of the
  phone run, design Section 10). Until then the position is: CameraX may hold one
  JPEG-sized copy of each capture in the Java heap until the next collection.
- An authorized viewer can photograph the screen (Phase 1 position); nothing in 3B changes
  that. The Phase 4 experiment is the first attempt to make such copies less useful and
  promises nothing.
- Nothing here has been independently reviewed.

## 9. Phase 3C additions: video groundwork (approved 2026-10-19 at the Milestone B checkpoint, to be confirmed at Milestone E)

What Stage 3C claims, and only this: **a video clip stays encrypted on disk as segment
objects plus one index object, is decrypted for playback one or two segments at a time
into memory the app controls and wipes, and nothing of it is written to storage.** The
playback probe exists in the debug variant only. Design: `docs/PHASE3C_DESIGN.md`.

### 9.1 New assets

| Asset | Where it lives | Lifetime |
|---|---|---|
| Decrypted segment (fragmented MP4, 0.5 to 2 MB) | One guarded block per segment, at most two at once | Until the player moves past the segment, stops, or locks; wiped on free |
| Sample copies inside the player | media3 allocator arrays and extractor scratch in the Java heap; `MediaCodec` input buffers | Until the player is released and the collector runs; outside app control |
| Decoded frames | Codec output and surface buffers, GPU, compositor | Per frame; outside app control; `FLAG_SECURE` and a secure surface keep them out of captures and recents |
| Index content | Segment list, durations, key-frame grid, codec and size, creation time | Inside one encrypted object; decrypted into a Kotlin structure while a clip is open |

### 9.2 Threats and the Stage 3C position

| # | Threat | Position |
|---|---|---|
| T33 | Screenshot, recording, or recents card of playback | `FLAG_SECURE` on the probe window and `SurfaceView.setSecure(true)`; checked on both devices. |
| T34 | Plaintext left in memory after playback | Guarded buffers wiped on close, transition, stop, and lock (counter checked in T2). media3 and codec copies are not wiped by the app; releasing the player drops the references. |
| T35 | Lock while a segment is decrypting or playing | Decrypt cancelled through the 3B flag and the buffer wiped; player released; activity finished. |
| T36 | Clip structure visible on disk | Segment objects of one clip share sizes and adjacent modification times, and the index is a small object beside them (Phase 2 position: documented, not hidden). The structure itself (durations, key grid) is inside the encrypted index. |
| T37 | A damaged or foreign index | Every field is validated in the core before any segment is touched; a segment that fails to decrypt stops playback with the code name. |

### 9.3 Residual risks

- Java-heap copies of segment data inside media3 and native copies inside the codec are
  outside the app on every device (9.1).
- The probe writes recordings as test objects into the vault; they are deleted only with
  the developer's agreement.
- Nothing here has been independently reviewed; media3 is an external library processing
  plaintext in the debug build.
