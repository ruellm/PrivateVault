# PrivateVault R&D: Project Handoff and Phase 1 Specification

**Document role:** Canonical handoff for Claude Code. It records product intent, agreed architecture, phase roadmap, constraints, test strategy, and the end-to-end requirements for Phase 1. `CLAUDE_CODE_START_PROMPT.md` is the short launcher; where the two differ, this document wins.

**Project status:** R&D kickoff. Assume nothing is implemented until Claude Code inspects the repository and reports what it finds.

**Workflow:**
- ChatGPT: technical research and architectural guidance.
- Claude Chat: phase planning, implementation briefs, and review of phase results.
- Claude Code: implementation and testing of the active phase.
- The developer runs everything on his Windows machine, supplies hardware and environment access, approves checkpoint decisions, and decides whether a phase is accepted.

Claude Code executes Phase 1 end-to-end with one planned design checkpoint (Section 8). Ordinary implementation is not split into a series of small prompts.

---

## 1. Product idea and long-term intention

PrivateVault is an R&D project for a privacy-first mobile app aimed initially at couples and other trusted relationships. Users capture private photos or videos inside the app. The original media is stored encrypted on the originating user's phone instead of being uploaded to a central cloud media library. Another authorized person can request to view the media, ideally by streaming it from the origin device. The owner can revoke future access and keep local access records.

The intended product combines:

- local, application-managed encrypted media storage;
- in-app capture, so media does not intentionally pass through the ordinary photo gallery;
- a protected custom media viewer;
- permission-based sharing and future revocation;
- origin-device storage, with no central server storing the media by default;
- logging of completed viewing sessions and, where technically possible, received access attempts;
- later experimentation with temporal visual interference (scan-line patterns) intended to make external-camera copies less useful;
- later features such as watermarking, secure locking, and carefully designed recovery and device migration.

The goal is to reduce accidental exposure, casual copying, and the risk of a central service leak. **It is not possible to guarantee that a recipient cannot copy media.** A second device can photograph a screen, a compromised or rooted device can defeat some controls, and an authorized recipient may extract content while viewing it. Product language must never promise "leak-proof" or absolute prevention.

### Sharing model envisioned for later phases

1. Person A captures media.
2. The original media remains encrypted on A's device.
3. Person A grants Person B permission to view it.
4. Person B requests a viewing session. The source device authorizes and supplies the content, preferably as a stream rather than an exported file.
5. The source device records the session locally when it receives and processes the request.
6. Person A can revoke future access. Revocation cannot recall anything already copied or photographed.

"Decentralized" means media storage and the authority to serve it are controlled by the source device. It does not mean zero servers. A small signaling, identity, or notification service, or a TURN relay, may later be needed for connectivity; the goal is that these never persist photos or videos. An offline source phone cannot deliver media or record a request it never receives. Future design must state these trade-offs.

## 2. Agreed product and engineering decisions

### 2.1 Android first, iOS later

- Develop on Windows with Visual Studio 2022 (17.x) or later and MSVC. Record the exact versions found.
- Build the portable vault core in C++20.
- Use CMake (with `CMakePresets.json`) as the shared build description so the same source builds under Visual Studio on Windows and under the Android NDK through Gradle.
- Android: native app with a thin Kotlin platform layer, calling the shared core through the NDK/JNI.
- iOS later: Swift and Apple APIs around the same core. No Mac is available, so iOS is not a Phase 1 dependency, but the core must not block it (Section 2.7).
- OS-specific storage and key handling stay behind explicit interfaces. The object format and core encryption logic must not depend on Android-only or Windows-only paths or APIs.

### 2.2 No root requirement

The product must work on a normal, unrooted Android device. Root, bootloader unlocking, and custom ROMs are never prerequisites.

### 2.3 TrueCrypt is a research reference, not the foundation

TrueCrypt is discontinued and must not be assumed safe for new development. The primary implementation is an application-managed encrypted vault built on maintained cryptographic software. Phase 1 includes a short desk-research note on container-based alternatives (Section 4.7). No TrueCrypt or VeraCrypt code, no custom filesystem, and no mounted volume in Phase 1. The obsolete format is not a marketing point; the differentiator is the end-to-end privacy architecture.

### 2.4 One logical vault, individually encrypted objects

The app can present an in-app "vault" without a real OS-mounted drive. Storage is a set of individually encrypted objects plus encrypted or minimal metadata inside the app's private storage. This fits per-item permissions, streaming, metadata handling, and per-item deletion better than a large virtual disk. The on-disk format is versioned and documented, and is not declared stable until tested and reviewed.

### 2.5 Cryptography comes from an established library

- Do not implement cryptographic primitives, ciphers, password hashing, random-number generators, or cryptographic protocols from scratch.
- Use **libsodium**. Verify the current stable release, license (ISC), and APIs from official sources before pinning. As of this handoff the newest point release is believed to be 1.0.22; do not use anything older than 1.0.21. Confirm this during Milestone A.
- Use `crypto_secretstream_xchacha20poly1305` for object payloads (authenticated chunked stream with an explicit final tag). Its seeking limitation is documented in Section 4.4.
- Use `crypto_aead_xchacha20poly1305_ietf` for wrapping keys inside the core.
- Use Argon2id via `crypto_pwhash` for the desktop password-derived key. Never store plaintext passwords.
- Record every cryptographic choice and dependency version in the design notes. No unpinned "latest" or "master" dependencies and no undocumented downloads.

### 2.6 Key hierarchy (Phase 1 baseline)

Three levels:

1. **Key-encryption key (KEK), platform-specific.** Desktop: derived from a password with Argon2id. Android: a non-exportable AES-256-GCM key in Android Keystore.
2. **Vault master key (VMK).** 32 random bytes per vault, generated by libsodium. Stored only wrapped by the KEK in a platform key envelope file.
3. **Per-object data-encryption key (DEK).** 32 random bytes per object (`crypto_secretstream_xchacha20poly1305_keygen`). Stored in the object header, wrapped by the VMK.

Reasons: no key is ever reused across objects; a password change or platform key change only rewraps the VMK; future per-object sharing (Phase 5) and per-object cryptographic erasure (Phase 6) are possible without changing the object format.

The portable core only ever receives a VMK as bytes. It knows nothing about passwords, Keystore, or envelopes. Desktop and Android envelopes are deliberately different; objects are identical across platforms. Cross-device recovery or migration is a future design task, not an accidental feature.

### 2.7 Portability seam for iOS and JNI

- The core exposes a small C ABI (`pv_*` functions, opaque handles, integer error codes) over the C++20 implementation. JNI calls the C ABI; a future Swift client can call the same ABI without relying on Swift/C++ interop.
- No C++ exceptions cross the C ABI.
- The key-provider interface is "wrap/unwrap these bytes" so it can be backed by Android Keystore, Windows password derivation, or later an iOS Keychain or Secure Enclave scheme, without assuming a specific algorithm.
- The core does file I/O through a minimal stream interface (source/sink) so tests can inject faults and platforms can supply their own I/O.

---

## 3. Full roadmap

Phase 1 is the only active implementation phase. Later phases are listed for context; do not start them. Where a later phase affects a Phase 1 choice, document it and leave a seam.

### Phase 1: Encrypted storage R&D (ACTIVE)

Build a small, testable encrypted-storage core on Windows and integrate it into a minimal Android proof of concept on a normal unrooted device. Establish file format, chunked read/write, integrity behavior, key protection, persistence, cross-platform build, tests, and security limitations. No production UI.

**Exit:** every Phase 1 acceptance criterion (Section 7) is met or explicitly marked blocked by a real environment limitation, with evidence and known gaps documented.

### Phase 2: In-app capture and private media storage

- Android camera capture using supported APIs.
- Write captured images directly into the encrypted object pipeline, without intentionally creating a plaintext gallery copy.
- Add video recording after the photo path is validated; video capture may need a different output strategy.
- Inspect and mitigate thumbnails, caches, crash recovery files, backups, logs, temporary files, and media indexing.
- Test low-storage and interrupted-capture behavior.
- Document residual plaintext exposure and any capture APIs that force temporary file output.

### Phase 3: Protected custom viewer

- Render images and videos inside the app only, never through an external viewer.
- `FLAG_SECURE` on every sensitive view; test lifecycle and background behavior.
- Manage decrypted buffers, caches, temporary data, and lock-on-background.
- Streaming playback must treat decrypted output as provisional until the stream's final tag is verified (Section 4.4).
- Decide the video seeking approach (Section 4.4) before or at the start of this phase.
- Session expiry and locking; optional biometric authentication.
- Design the renderer so the temporal-interference experiment can attach without changing vault encryption.

### Phase 4: Temporal interference / anti-external-camera R&D

Experimental; not guaranteed to prevent copying.

The technique draws the protected image in a custom viewer and animates scan lines, brightness or pattern changes, or other temporal modulation in software. It does **not** change the display's physical refresh rate. External cameras may show rolling-shutter bands, moiré, uneven exposure, or flicker depending on sensor, exposure, and display interaction, and camera settings can sometimes reduce these.

- Start with a controllable Canvas prototype; evaluate a GPU shader if needed.
- Measure original-versus-captured image quality, not only whether lines are visible.
- Test multiple external phones, camera settings, lighting, brightness, stills, and video.
- Compare quality for an authorized viewer against camera-captured results.
- Consider a visible session-specific watermark as a complementary deterrent.
- Report failures and conditions where it does not work. No claim of reliable prevention without evidence.

### Phase 5: Permission-based decentralized sharing

- Account/device identity and pairing.
- Versioned, authenticated authorization protocol and key/session establishment, built on an established protocol or library, not a home-grown one.
- Local network prototype first, then WebRTC or an equivalent maintained approach for internet connectivity.
- Signaling and TURN relay needs, with no media persistence on servers.
- Stream media from the source device without normal recipient export; authenticate sessions; enforce revocation for future requests.
- Record view sessions and received requests on the source device when it can observe them.
- Test source offline, network interruption, token expiry, revoked access, relay use, replay, and unauthorized requests.
- Document that screenshots, recordings, and existing copies cannot be recalled.

### Phase 6: Security hardening and product readiness

- Independent cryptographic and security review; threat-model update.
- Penetration and abuse-case testing.
- Backup, recovery, and device migration design; explain the consequence of losing device-bound keys.
- Reviewed delete and cryptographic-erasure approach. Do not promise forensic secure deletion on flash storage.
- App lock, notification privacy, analytics policy, minimal permissions, accessibility, platform policy review.
- Consented beta testing with clear user expectations.
- iOS implementation once macOS/Xcode is available, reusing the portable core and protocol definitions.

---

## 4. Phase 1 objectives and scope

### 4.1 Goals

By the end of Phase 1, demonstrate:

1. A C++20 vault-core library builds on Windows with MSVC/CMake in Visual Studio.
2. A desktop CLI can create a vault and encrypt, decrypt, and inspect synthetic test objects.
3. A native Android debug app builds with Gradle and uses the same C++ core via the NDK/JNI.
4. The Android proof of concept works without root on an emulator and, if available, a physical device.
5. Data is encrypted at the application-object level, not only by OS storage encryption or sandboxing.
6. Encryption and decryption run in fixed-size chunks with bounded memory.
7. Wrong keys, wrong passwords, tampering, truncation, trailing data, malformed headers, and unsupported versions fail closed.
8. Platform key handling is isolated from the portable core.
9. The object format is verified across Windows and Android with committed synthetic golden vectors and a test-only key.
10. Automated tests cover the above, with setup, build, and test instructions.
11. A Phase 1 report states what works, what is not proven, how to reproduce tests, and which decisions remain.

### 4.2 Out of scope for Phase 1

- Real photos, intimate images, personal files, or any real user data. Synthetic generated data only.
- Camera capture, gallery UI, media playback, temporal-interference rendering.
- Accounts, networking of any kind, WebRTC, signaling, relays, sharing or revocation UI.
- Release builds, store publishing, backend, analytics, monetization.
- TrueCrypt/VeraCrypt implementation, mounted filesystems, root, kernel changes, filesystem drivers.
- Any claim that the prototype is audited or safe for real intimate content.
- Custom cryptographic primitives or constructions.
- Video seeking implementation (design note only).
- Password change, key rotation, vault migration, or recovery (design note only if relevant).

### 4.3 Recommended layout (adjust if evidence warrants)

```text
PrivateVault/
  CMakeLists.txt
  CMakePresets.json
  CLAUDE.md
  README.md
  .gitignore
  docs/
    ROADMAP.md
    THREAT_MODEL.md
    PHASE1_DESIGN.md
    PHASE1_PROGRESS.md
    PHASE1_TEST_REPORT.md
    DECISIONS.md
    CONTAINER_FEASIBILITY.md
    DEPENDENCIES.md
  src/
    vault_core/        # portable C++20 library + C ABI header
    desktop_cli/       # Windows CLI and desktop key provider
  tests/
    core/              # host tests for the core
    fixtures/v1/       # committed synthetic golden vectors + test-key file
  android/
    app/src/main/      # Kotlin UI, Keystore key provider
    app/src/main/cpp/  # JNI bridge to the C ABI
    app/src/androidTest/  # instrumented tests
  third_party/ or vcpkg.json   # per the dependency strategy chosen in Milestone A
```

Create only what is needed. Preserve anything already in the repository.

**Boundaries:**
- **Portable core:** object format, streaming encrypt/decrypt, DEK wrap/unwrap under a supplied VMK, header validation, error model, atomic-finalize helpers, stream interfaces.
- **Desktop adapter:** Argon2id password envelope, no-echo password prompt, CLI, filesystem.
- **Android adapter:** Keystore KEK, envelope in app-private storage, JNI bridge, minimal UI.
- No plaintext keys or passwords in files or logs. Zero key buffers with `sodium_memzero` where practical; hold long-lived keys in `sodium_malloc` memory if it does not complicate the API. Document what this does not guarantee (Kotlin `ByteArray` copies, GC, swap, a compromised process).
- No sensitive data in external or public shared storage.

### 4.4 Object format and key envelopes (baseline for Milestone B)

This is a starting point. Claude Code finalizes exact byte layouts in `docs/PHASE1_DESIGN.md` and presents them at the Milestone B checkpoint. Use libsodium constants by name; do not hard-code sizes that the library defines.

**Vault directory (same on both platforms):**
- `vault.key`: platform key envelope (wrapped VMK).
- `objects/<object_id_hex>.pvo`: one file per object. Names are random IDs; original names and types are not stored in Phase 1.
- In-progress writes use `objects/<object_id_hex>.pvo.partial` in the same directory and are renamed on completion. Stale `.partial` files are deleted when the vault is opened.

**Object file, format version 1:**

| Field | Notes |
|---|---|
| magic | fixed bytes identifying a PrivateVault object |
| format_version | u16 LE; reader rejects unknown versions |
| suite_id | u16 LE; 1 = secretstream XChaCha20-Poly1305 payload + XChaCha20-Poly1305-IETF DEK wrap |
| chunk_size | u32 LE plaintext bytes per non-final chunk; default 64 KiB; reader rejects values outside a documented range (for example 4 KiB to 4 MiB) before allocating |
| object_id | 16 random bytes |
| dek_wrap_nonce | random, `crypto_aead_xchacha20poly1305_ietf_NPUBBYTES` |
| wrapped_dek | DEK encrypted under VMK; AD = all preceding header bytes |
| stream_header | `crypto_secretstream_xchacha20poly1305_HEADERBYTES` |
| chunks | sequence of secretstream messages |

**Required stream rules:**
- The complete header bytes are passed as additional data to the first secretstream message, so any header change fails authentication.
- Every non-final chunk carries exactly `chunk_size` plaintext and tag `MESSAGE`. The last chunk carries 0 to `chunk_size` bytes and tag `FINAL`. An empty input produces one empty `FINAL` chunk.
- The reader fails if: end of file arrives without a `FINAL` chunk (truncation); any data follows the `FINAL` chunk (trailing data); a non-final chunk has the wrong size or tag; a tag other than `MESSAGE`/`FINAL` appears; any length is inconsistent with `chunk_size`.
- Each chunk is authenticated as it is decrypted, but **completeness is only known at the `FINAL` tag**. File decryption therefore writes to a temporary file and finalizes only after `FINAL` is verified. Future streaming consumers (Phase 3) must treat earlier output as provisional.
- Writers produce `.partial` files, flush (`FlushFileBuffers` / `fsync`), then rename atomically (`MoveFileExW` with `MOVEFILE_WRITE_THROUGH`; `rename(2)` plus directory `fsync` on Android). The default for an existing destination is to fail, not overwrite.

**Error model:** distinct codes at least for: wrong VMK (DEK unwrap failed), wrong password or KEK (envelope unwrap failed), authentication failure in payload, truncated, trailing data, malformed header, unsupported version or suite, I/O error, destination exists. No secret material in error messages.

**Desktop envelope (`vault.key`, provider 1):** magic, version, provider_id, Argon2id `opslimit` and `memlimit`, salt (`crypto_pwhash_SALTBYTES`), nonce, wrapped VMK; AD = all preceding bytes. Default parameters: libsodium `MODERATE` (verify current values in the docs). The reader enforces minimum and maximum bounds on stored parameters before running the KDF, so a modified envelope cannot cause a huge memory allocation or a weak derivation. Tests may use `INTERACTIVE` parameters through the test-only path.

**Android envelope (`vault.key`, provider 2):** magic, version, provider_id, Keystore alias, GCM IV as returned by the Keystore cipher, wrapped VMK with tag; header bytes supplied as GCM AAD. Keystore key: AES-256, GCM, no padding, randomized encryption required (Keystore generates the IV; never supply one). StrongBox is attempted only if available, with fallback; report the actual security level. User authentication is not required in Phase 1. `setUnlockedDeviceRequired(true)` is applied if minSdk allows (Section 4.5).

**Metadata exposure (document, do not solve in Phase 1):** ciphertext size reveals plaintext size to within one chunk; object count and file timestamps are visible to anyone who can read the directory; file names are random.

**Video seeking (document only):** secretstream is sequential. Seeking to an offset requires decrypting from the start of the object. Options for Phase 3, to be compared in `PHASE1_DESIGN.md`:
1. Store video as many short segment objects (each its own secretstream object with its own DEK), with an encrypted segment index. Seek granularity is one segment; no new cryptographic construction.
2. A random-access chunk format using `crypto_aead_xchacha20poly1305_ietf` per chunk with nonce and AD binding chunk index and last-chunk flag (the STREAM construction). Allows chunk-level seeking but is a construction the project would own and must get reviewed.
Recommendation to record: option 1 as the default direction, option 2 only if measured playback needs require it. The `suite_id` field keeps either path open without breaking version 1 objects.

### 4.5 Android storage and app behavior

- Encrypted objects and the envelope live in `Context.getFilesDir()` (internal app-private storage). No external or shared storage.
- `android:allowBackup="false"` and data-extraction / full-backup rules that exclude all app data, so vault files are not copied by cloud backup or device-to-device transfer.
- No `INTERNET` or storage/media permissions. Verify the **merged** manifest of the debug APK, since dependencies can add permissions.
- No network code, analytics, ads, capture code, or third-party dependencies beyond the AndroidX basics the template needs.
- minSdk: 28 recommended (allows `setUnlockedDeviceRequired`, StrongBox APIs). Pending developer approval; see Section 9.
- ABIs: `arm64-v8a` (devices) and `x86_64` (emulator).
- Native library: one shared library that statically links the core and libsodium, using the static C++ runtime. Use an NDK version that aligns native libraries for 16 KB page sizes by default, or add the documented linker flag; verify current NDK guidance.
- The demo must not write decrypted plaintext to disk. Sample content is generated in memory from a fixed seed (`randombytes_buf_deterministic`) and streamed into the encryptor; verification streams decrypted output into a BLAKE2b hash (`crypto_generichash`) and compares it with the source hash.
- Minimal UI: create or open vault; encrypt a generated sample of selectable size (for example 1 MB and 100 MB); decrypt and verify; run tamper, truncation, and wrong-key tests; show pass/fail per action and the Keystore security level. No secret values or plaintext shown.
- Uninstall, Clear Data, and factory reset destroy the Keystore key and therefore the vault. This is expected in Phase 1 and must be documented.
- Logcat output must never contain keys, passwords, plaintext, or sample content.

### 4.6 Build and dependency strategy

- Windows: CMake presets for MSVC x64 Debug and Release. The Visual Studio generator locates MSVC without a Developer prompt; a Ninja preset requires a Developer PowerShell. Do not treat a missing `cl` in a plain shell as proof that MSVC is absent; use `vswhere`.
- Android: Gradle wrapper with the project's CMake via `externalNativeBuild`. Gradle needs a JDK (Android Studio's bundled JBR is acceptable); record the version.
- **libsodium is the main feasibility risk.** libsodium's official Android build path is a shell script (`dist-build/android-*.sh`) that needs a Unix-like environment, and the vcpkg Android build of libsodium has had reported failures. Milestone A must prove integration before anything else is built on it. Evaluate in this order and record the result:
  1. vcpkg manifest mode with a pinned baseline, triplets `x64-windows-static`, `arm64-android`, `x64-android`, all built on this Windows machine.
  2. Windows: official signed prebuilt MSVC package. Android: official `dist-build` scripts run under WSL2 (or another Linux environment), producing pinned static libraries stored under `third_party/` with checksums and the exact rebuild commands.
  3. Anything else only with a written justification in `DECISIONS.md`.
  Installing WSL, a hypervisor feature, or any system-wide tool requires developer approval.
- Verify downloads: libsodium release tarballs are signed with minisign (public key on the official docs); vcpkg pins source hashes. Record version, source URL, license, and verification method in `docs/DEPENDENCIES.md`.
- Do not commit build output. Prebuilt third-party libraries may be committed only under option 2, with checksums and rebuild instructions.

### 4.7 Container feasibility note (desk research only)

Write `docs/CONTAINER_FEASIBILITY.md`, one to two pages, no code. Cover at least:
- Mounting a TrueCrypt/VeraCrypt volume as a filesystem on unrooted Android is not available to ordinary apps; confirm against current platform documentation.
- Reading a container in userspace would mean porting volume-header parsing, the XTS cipher layer, and a FAT/exFAT layer: large, security-sensitive, and outside "established library" use.
- XTS provides confidentiality without integrity, which conflicts with the tamper-detection requirement.
- One large container complicates per-item sharing, revocation, and erasure.
- Licensing of TrueCrypt-derived code (verify current VeraCrypt licensing).
- Conclusion and whether any container compatibility is worth revisiting later (for example export or backup).

---

## 5. Security objectives and threat model for Phase 1

### Assets
- Encrypted test objects and future private media.
- KEKs, VMKs, DEKs.
- Unlock credentials.
- Metadata that could reveal private activity.
- Plaintext buffers and incomplete or decrypted temporary files.

### In-scope threats
- Someone copies vault files from disk without the key.
- Another ordinary app tries to read app-private storage.
- An attacker or storage fault modifies ciphertext, headers, or envelopes.
- Truncated or corrupted files, interrupted writes, wrong passwords or keys.
- A modified envelope that tries to force a huge or weak KDF.
- Secret disclosure through logs, exceptions, command history, crash files, backups, or repository commits.
- Unbounded memory use for large files.
- App restart or device restart causing data loss or an unusable vault.

### Out of scope / residual risks
- Rooted or fully compromised devices, malicious OS or firmware, debuggers or instrumentation on a running process.
- Keyloggers or malware capturing credentials before they reach the app.
- Screenshots, screen recording, external cameras, deliberate copying by an authorized recipient.
- Coercion, shared device passwords, unlocked-device physical access.
- Metadata leakage beyond what Section 4.4 addresses.
- Recovery after loss of a device-bound key.
- Independent audit or proof of production security.

### Required engineering practices
- Use libsodium and platform APIs as documented. No home-grown cipher, KDF, PRNG, or construction.
- Call `sodium_init()` once before any use and fail if it fails.
- Fail closed on any authentication or format error. Never treat unauthenticated output as valid.
- CLI decryption writes to a temporary file and renames only after `FINAL` is verified; on failure it deletes the temporary file. Document that deletion on flash storage does not guarantee erasure.
- All writes use temporary file plus atomic rename.
- Validate lengths and integer arithmetic before allocation or I/O. Handle empty, small, large, and malformed inputs.
- Never log secrets, passwords, key bytes, plaintext, or test payloads.
- Never accept passwords as command-line arguments. Interactive use: no-echo console prompt. Scripted use: optional `--password-stdin`. Automated tests call the library directly.
- Test-only key injection: the CLI exposes test options only when built with a CMake option (for example `PV_TEST_HOOKS`, default OFF); Android test keys exist only in the `androidTest` source set. The production code paths always use the real key providers.
- The fixed test VMK in `tests/fixtures/` is synthetic and labelled TEST ONLY. No real secrets anywhere in the repository.
- Do not describe the prototype as secure because tests pass. It is an R&D prototype until reviewed.

---

## 6. Test strategy

Every test belongs to one tier. The report lists results per tier.

| Tier | Runs on | How | Covers |
|---|---|---|---|
| T1 Host automated | Windows | `ctest` | All core format and negative tests, CLI tests, golden vectors, large-file and memory tests |
| T2 Android automated | Emulator (or device) | `gradlew connectedDebugAndroidTest` | JNI load, round trip in `filesDir`, Keystore wrap/unwrap, golden-vector decrypt, tamper/truncation/wrong-key through JNI |
| T3 Device manual or scripted | Physical device | `adb` steps documented in the report | Keystore security level, persistence across force-stop and reboot, 100 MB+ timing, install without root |

Notes:
- JVM unit tests cannot load the Android native library; anything touching JNI is T2.
- An emulator needs a system image and a Windows hypervisor feature. If neither emulator nor device is available, T2 and T3 are "Not verified" with the exact blocker.
- Large-file tests (100 to 256 MB) generate data at test time into the build directory, carry a separate CTest label, and are deleted afterwards. Nothing large is committed.
- Bounded memory: run the CLI on 1 MB and 256 MB inputs in separate processes and compare peak working set (`GetProcessMemoryInfo`). The difference must stay under a documented threshold (for example 16 MB).
- I/O failure tests use a fault-injecting sink/source through the core's stream interface.
- Golden vectors: small committed `.pvo` files (empty, 1 byte, chunk_size minus 1, chunk_size, chunk_size plus 1, about three chunks) encrypted under the test VMK, with plaintext regenerated from a fixed seed. T1 and T2 must both decrypt them. They also detect accidental format changes.
- Android to Windows: a T2 test writes a vector into app-private storage; the documented step `adb exec-out run-as <package> cat files/...` pulls it (debug build only); a T1 test decrypts it. If this step cannot run, mark it "Not verified".

---

## 7. Milestones and acceptance criteria

### Milestone A: Inspect, scaffold, dependency spike
- Inspect the repository, Git state (read-only), and toolchains: Visual Studio/MSVC (`vswhere`), CMake, Ninja, Android SDK, NDK versions, JDK, emulator images, connected devices (`adb devices`), WSL availability. Record findings in `docs/PHASE1_PROGRESS.md`.
- Create `CLAUDE.md`, `README.md`, `.gitignore`, `docs/ROADMAP.md`, `docs/DECISIONS.md` as needed.
- Minimal CMake C++20 library and CLI with presets; one passing CTest test.
- **Dependency spike:** link libsodium and call `sodium_init()` from (a) the Windows build and (b) an Android NDK build for `arm64-v8a` and `x86_64`, per Section 4.6.
- Choose the test framework: plain CTest executables or one pinned single-header framework. Record the reason.

**Acceptance:** clean configure, build, and test on Windows; libsodium links for Windows and both Android ABIs, or the exact blocker and the approval needed are reported.

### Milestone B: Design (CHECKPOINT)
- Verify libsodium version, license, and APIs against official docs; record access dates.
- Write `docs/PHASE1_DESIGN.md`: exact object and envelope byte layouts, key hierarchy, nonce and key lifecycle, stream rules, error model, metadata exposure, atomic-write behavior, seeking options, desktop and Android key-wrap designs, migration limits, C ABI sketch.
- Write `docs/THREAT_MODEL.md`, `docs/DEPENDENCIES.md`, `docs/CONTAINER_FEASIBILITY.md`, and decision entries with alternatives considered.

**Acceptance:** another engineer can understand the format and threat boundaries from the docs alone. **Stop here and report to the developer** (Section 8). Do not implement the format until the developer approves or adjusts it.

### Milestone C: Desktop encrypted storage
Core capabilities: create vault (generate VMK), open vault via a key provider, encrypt a source stream to a new object, decrypt and verify an object to a sink, inspect and validate a header without decrypting, structured errors. CLI: `init`, `encrypt`, `decrypt`, `verify`, `inspect`, plus test-only options behind `PV_TEST_HOOKS`.

T1 tests:
- empty file; 1 byte; chunk_size minus 1, chunk_size, chunk_size plus 1; multiple chunks;
- 100 to 256 MB synthetic file (large label);
- byte-for-byte round trip (hash compare);
- wrong VMK; wrong password;
- tampered header field (each field class), tampered ciphertext in first, middle, and final chunk, tampered envelope;
- truncation at several positions including inside the final chunk and exactly at a chunk boundary before `FINAL`;
- trailing bytes after `FINAL`;
- chunk_size out of range; unsupported version and suite; envelope KDF parameters out of bounds;
- destination exists; injected read and write failures;
- bounded memory per Section 6;
- after every failed decrypt, no final output file exists and the temporary file is removed;
- golden vectors decrypt.

**Acceptance:** all T1 tests pass. Any skipped test states why and gives a reproducible alternative.

### Milestone D: Android integration
- Kotlin app per Section 4.5; JNI bridge over the C ABI; Keystore key provider and envelope.
- T2 instrumented tests: create vault, round trip, Keystore wrap/unwrap, golden vectors, tamper, truncation, wrong key.
- Report the Keystore security level (software, TEE, StrongBox) seen on each target.

**Acceptance:** `gradlew assembleDebug` builds reproducibly; merged manifest has no `INTERNET` or storage permissions and backup is disabled; T2 tests pass on an emulator or device. If a physical device is available: install, create, encrypt, force-stop, relaunch, decrypt and verify, reboot, decrypt and verify again, plus tamper and wrong-key tests (T3). Without a device, state that T3 is not verified and list the remaining manual steps.

### Milestone E: Cross-platform verification and documentation
- Golden vectors verified on both platforms; Android-to-Windows check per Section 6.
- Confirm no secrets or build outputs are in the working tree (`git status`, review of untracked files).
- Review failure paths, logs, temporary files, and key lifetime.
- Update README (clone, build, and test steps for each tier), design, threat model, dependencies, decisions.
- Write `docs/PHASE1_TEST_REPORT.md` (Section 10 format).

**Phase 1 is complete only when:**
- the Windows core builds and all T1 tests pass;
- the Android debug app builds;
- T2 tests pass, or the emulator/device blocker is explicit;
- T3 tests pass if a device is available, otherwise the blocker and remaining steps are explicit;
- encryption and decryption are chunked and authenticated;
- wrong key, tampering, truncation, and trailing data fail closed;
- desktop and Android key handling are outside the shared core;
- the prototype stores no unnecessary plaintext persistently (Android stores none);
- cross-platform format compatibility is tested or the exact blocker is documented;
- a new developer can follow the README to reproduce supported builds and tests;
- the report separates demonstrated facts from assumptions and future work.

---

## 8. Instructions to Claude Code

1. Read this document fully before changing anything.
2. Repository location: expected at `D:\repository\PrivateVault`. Confirm the actual working directory and report it.
3. Inspect the repository, Git state, and toolchains. Preserve existing work; never overwrite a file without reading it.
4. Make a plan, then execute it. Do not stop after presenting a plan, except at the Milestone B checkpoint.
5. **Milestone B checkpoint:** after the design docs are written, stop and report: the proposed object and envelope layouts, key hierarchy, dependency strategy result, seeking recommendation, and any open decisions. Wait for the developer's approval before Milestone C.
6. Keep `docs/PHASE1_PROGRESS.md` current after each milestone (state, commands run, results, open items) so work can resume in a new session.
7. Build and test after each meaningful change. When a command fails, diagnose and fix it; do not report failure as completion.
8. Make ordinary low-risk choices yourself and record non-obvious ones in `docs/DECISIONS.md`.
9. Ask the developer only for: missing access or devices, system-wide or privileged installs (WSL, hypervisor features, SDK components outside the project), purchases, credentials, destructive operations, or security trade-offs not settled by this document or authoritative docs. Never ask for passwords or keys in chat.
10. **Git rules:**
    - Allowed (read-only): `git status`, `git diff`, `git log`, `git show`.
    - Not allowed: `git init`, `add`, `commit`, `stash`, `reset`, `checkout`, `switch`, `restore`, `branch`, `merge`, `rebase`, `cherry-pick`, `tag`, `clean`, `pull`, `push`, or any history rewrite.
    - If the folder is not a Git repository, report it and continue without Git.
    - At the end, recommend a commit message; do not commit.
11. **Code rules:**
    - Do not add redundant `#include` lines for standard headers a file already gets through the project's own headers.
    - Comments must make sense to someone reading the file cold, in plain words. No coined terms, no contrasts against rejected alternatives, no reassurance words such as "safe", "correct", "secure". Longer rationale goes in `docs/`, not in code.
12. No production claims ("unbreakable", "leak-proof", "military-grade"). No claim of audit or validation that did not happen.
13. Synthetic data only.
14. Do not start Phase 2 or later. Where a later need affects Phase 1, document it and leave a seam.
15. If the Android SDK/NDK, emulator, a dependency download, or a device is unavailable, continue with everything else and report the exact blocker and next steps. Never invent a build or test result.
16. Before finishing, run `git status` and `git diff`, and confirm no secrets, generated build output, or large test files are present.

---

## 9. Decisions recorded with this handoff

Approved or pending developer approval as marked. Claude Code treats "pending" items as the default unless told otherwise at the checkpoint.

| Decision | Choice | Status |
|---|---|---|
| Crypto library | libsodium, pinned stable release, at least 1.0.21 | Agreed |
| Payload construction | `crypto_secretstream_xchacha20poly1305`, header bound as AD | Agreed |
| Key hierarchy | KEK -> VMK -> per-object DEK | Pending approval |
| Core interface | C ABI over C++20 core | Pending approval |
| Video seeking direction | Segment objects (document only in Phase 1) | Pending approval |
| Android minSdk | 28 | Pending approval |
| Execution model | End-to-end with one stop at Milestone B | Pending approval |
| Dependency route | Decided by Milestone A spike; WSL or system installs need approval | Open |

---

## 10. Final report format (`docs/PHASE1_TEST_REPORT.md` and final message)

- **Status:** Complete / Partially complete / Blocked.
- **Environment:** exact versions of OS, Visual Studio, MSVC, CMake, Ninja, JDK, Gradle, AGP, Android SDK, NDK, emulator image, device model and Android version, libsodium.
- **Architecture:** what was implemented and why.
- **Build and tests:** exact commands; results per tier (T1, T2, T3) as pass/fail/skip with counts.
- **Android device:** actual install and run result, or the exact blocker.
- **Security review notes:** key handling and lifetime, authentication and failure behavior, temporary-file and plaintext handling, logging, backup settings, threat limitations.
- **Dependencies:** versions, sources, licenses, verification method.
- **Files changed:** main paths.
- **Unresolved risks and next actions:** factual items only.
- **Phase 1 acceptance checklist:** each criterion in Section 7 marked Pass, Fail, or Not verified.
- **Suggested commit message.**

No success claims without command or test evidence.

---

## 11. References

Check current content; record access dates and versions in the docs.

- libsodium code and releases: https://github.com/jedisct1/libsodium
- libsodium documentation: https://doc.libsodium.org/
- libsodium installation (prebuilt, Android, vcpkg): https://doc.libsodium.org/installation
- libsodium secretstream: https://doc.libsodium.org/secret-key_cryptography/secretstream
- libsodium AEAD XChaCha20-Poly1305: https://doc.libsodium.org/secret-key_cryptography/aead/chacha20-poly1305/xchacha20-poly1305_construction
- libsodium password hashing: https://doc.libsodium.org/password_hashing/default_phf
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- Android app-specific storage: https://developer.android.com/training/data-storage/app-specific
- Android Auto Backup: https://developer.android.com/identity/data/autobackup
- Android NDK and CMake: https://developer.android.com/ndk/guides/cmake
- Android 16 KB page sizes: https://developer.android.com/guide/practices/page-sizes
- vcpkg on Android: https://learn.microsoft.com/vcpkg/users/platforms/android
- Visual Studio CMake projects: https://learn.microsoft.com/cpp/build/cmake-projects-in-visual-studio
- Archived TrueCrypt site: https://truecrypt.sourceforge.net/

---

## 12. Definition of success

A reproducible Phase 1 encrypted-storage prototype spanning Windows and Android, with tiered test evidence and a clear report. It is the foundation for Phase 2, not a production vault and not a sharing app.
