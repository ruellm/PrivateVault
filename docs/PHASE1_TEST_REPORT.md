# Phase 1 test report

Date: 2026-10-13. Format per `PROJECT_HANDOFF.md` Section 10. Every result below comes from a
command that was run; nothing is inferred. "Not verified" means exactly that.

## Status

**Partially complete.** Everything builds and every automated test passes on Windows (T1)
and on the Android emulator (T2). The physical-device tier (T3) is **not verified**: the
phone refuses `adb install` at the MIUI level (details below). The same T3 procedure was run
on the emulator and passed, which exercises the app code but is not a device result.

## Environment

| Component | Version |
|---|---|
| OS | Windows 11 Home 10.0.26200, Intel Core i7-10750H |
| Visual Studio | Community 2026 18.10.3 (18.10.12224.181), MSVC toolset 14.51.36231, compiler 19.51.36260, Windows SDK 10.0.26100.0 |
| CMake | 4.4.4 (host, Kitware); Android SDK CMake 4.1.2 for Gradle and the Android presets |
| Ninja | from Android SDK CMake 4.1.2 |
| vcpkg | registry tag 2026.07.29 (commit `9e593bb18ea69cc5095e012465dcd675a822ed0d`), tool 2026-07-27 |
| JDK | OpenJDK 21.0.4 (Android Studio JBR) |
| Gradle | 8.10.2 (SHA-256 pinned in the wrapper) |
| AGP | 8.8.2; Kotlin Android plugin 2.1.10 |
| Android SDK | platforms 35, build-tools 35.0.0 (installed by AGP) and 37.0.0, platform-tools 37.0.1 (adb 1.0.41), cmdline-tools 23.0.0 |
| NDK | 29.0.14206865 (r29), clang 21.0.0 |
| Emulator | 37.2.12, AVD `Pixel_8_API_35`: system image android-35 google_apis_playstore x86_64 rev 9, Android 15, API 35, WHPX |
| Device | Xiaomi 2201116SG (veux), Android 13, API 33, security patch 2024-10-01, MIUI V816.0.9.0.TKCMIXM, arm64-v8a, release-keys, verified boot green, no StrongBox feature |
| libsodium | 1.0.22 (tag `1.0.22-RELEASE`, library version 26.4), ISC license |

## Architecture

- **Portable core** (`src/vault_core`, C++20, C ABI in `include/pv/pv.h`): object format
  version 1 (128-byte header, secretstream XChaCha20-Poly1305 chunks, per-object DEK wrapped
  under the VMK with XChaCha20-Poly1305-IETF, header bound as additional data), chunk rules
  with short-read handling, vault directory logic (`objects/<id>.pvo`, `.partial` plus atomic
  rename, stale-partial cleanup, `.pvtmp` for decrypt output), platform file layer (wide APIs
  on Windows, POSIX elsewhere with `link()` then `rename()` fallback), deterministic test
  source (ChaCha20-IETF keystream) and BLAKE2b sink, error codes 0 to 20.
- **Desktop adapter** (`src/desktop_cli`): Argon2id password envelope (112 bytes, bounds
  checked before the KDF), no-echo console prompt or `--password-stdin`, `pv` tool with
  `init`, `encrypt`, `decrypt`, `verify`, `inspect`, `inspect-file`, test-only options behind
  `PV_TEST_HOOKS`.
- **Android adapter** (`android/`): Kotlin `KeystoreKeyProvider` (AES-256-GCM key in Android
  Keystore, randomized IV, `setUnlockedDeviceRequired(true)`, StrongBox attempted with
  fallback, provider 2 envelope with AAD), `Vault`, `MainActivity` (buttons plus a scripted
  `actions` extra), JNI bridge over the C ABI. One shared library per ABI with the core and
  libsodium linked statically, C++ runtime static, 16 KB aligned.
- Why: per Sections 2.4 to 2.7 of the handoff. The core never sees a password, a Keystore
  handle, or an envelope; both platforms produce identical object files.

## Build and tests

Windows (from the repository root, `VCPKG_ROOT` and `VCPKG_DEFAULT_BINARY_CACHE` set):

```
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug
ctest --preset windows-x64-tests-debug
cmake --build --preset windows-x64-tests-release
ctest --preset windows-x64-tests-release
cmake --preset windows-x64 && cmake --build --preset windows-x64-release    # production, no test hooks
cmake --preset android-arm64 && cmake --build --preset android-arm64
cmake --preset android-x64   && cmake --build --preset android-x64
```

Android (from `android/`, `ANDROID_HOME`, `JAVA_HOME`, `VCPKG_ROOT` set):

```
gradlew.bat assembleDebug assembleDebugAndroidTest
$env:ANDROID_SERIAL = "emulator-5554"
gradlew.bat connectedDebugAndroidTest
```

### T1, host automated (Windows, CTest)

Debug: 11 passed, 0 failed, 0 skipped (30 s). Release: 11 passed, 0 failed, 0 skipped (27 s).
No warnings at `/W4`.

| Test | Covers | Result |
|---|---|---|
| test_init | `pv_init` twice, versions, libsodium at least 1.0.21 | Pass |
| test_deterministic | same BLAKE2b for read sizes 1, 7, 4096, 1 MiB; equals `crypto_stream_chacha20_ietf` whole-stream output; `pv_hash_deterministic` agrees; different seed differs; empty stream | Pass |
| test_roundtrip | sizes 0, 1, 4095, 4096, 4097, 8192, 12388, 28671 at chunk 4096; 100 and 131077 at 64 KiB; 4 MiB + 1 at 4 MiB; ciphertext size arithmetic; inspect; expected-ID mismatch; short-read source on encrypt and decrypt; chunk 0 = default; two encryptions differ; argument checks | Pass |
| test_negative | wrong VMK; each header field class (magic, version 0 and 2, suite, chunk size low, high, zero, max, in-range change, object_id, wrap nonce, wrapped DEK body and tag, stream header); short header 0, 7, 127 bytes; ciphertext in first, middle, final chunk and tag bytes; truncation at header, inside first chunk (16, 17 bytes), boundary after chunk 0, inside chunk 1, boundary before FINAL, inside FINAL (16, 17, length minus 1); trailing data after short FINAL (AUTH_FAILED, see D-007) and after full-size FINAL (TRAILING_DATA); swapped chunks; crafted PUSH, REKEY, short MESSAGE alone (MALFORMED_CHUNK), short MESSAGE then FINAL, no FINAL, chunk after FINAL; all in normal and 1 to 7 byte read modes | Pass |
| test_vault | create, open before create, idempotent create, stale `.partial` removal (other files kept), encrypt from stream, verify, decrypt to stream, inspect, object path, decrypt to file (no `.pvtmp` left), destination exists, `.pvtmp` exists, unknown object, renamed file (ID mismatch), tampered and truncated objects leave no output or temp file, wrong key via vault, encrypt from file, missing input, empty input, argument checks; whole scenario repeated in a directory named `vault_üñî_日本_🔒` | Pass |
| test_io_faults | read faults at 5 points during encrypt, write faults at 4 points, read faults at 5 points during decrypt, write faults at 3 points; vault encrypt with a failing source leaves `objects/` empty; vault decrypt with a failing sink | Pass |
| test_envelope | seal and open; wrong and empty password; two seals differ; magic, version, provider (including Android provider id), salt, nonce, body, tag tampering; opslimit changed in range (unwrap fails), below min, above max, huge; memlimit below min, above max, huge, libsodium minimum; lengths 0, 7, 11, 12, 64, 111, 113; seal argument checks; file form with atomic write, refusal to overwrite, read back, wrong password, missing, oversize file | Pass |
| test_golden | 8 committed vectors: inspect fields, decrypt with expected ID, length and hash match the manifest and the deterministic stream, wrong key fails | Pass |
| test_large (label large) | 256 MB round trip through a vault (deterministic source to hash sink), file size arithmetic, cleanup; Release: encrypt 4.9 s, decrypt plus hash 1.6 s | Pass |
| test_bounded_memory (label large) | spawns `pv encrypt` and `pv decrypt` on 1 MB and 256 MB files in separate processes, compares `PeakWorkingSetSize`, verifies output hash; Release: encrypt 4.9 MB vs 4.5 MB, decrypt 4.5 MB vs 4.5 MB (limit 16 MB) | Pass |
| test_cli | `version`, usage errors, `init` with `--password-stdin --kdf interactive`, second init refused, encrypt, verify (`ok 131079`), inspect without password, inspect-file, decrypt, destination exists, wrong password on verify and decrypt (no output, no temp), bad and missing object id, tampered object (AUTH_FAILED, no files), truncated object (TRUNCATED), tampered envelope (UNWRAP_FAILED), test-key path with `--test-vmk-file` and `--chunk-size`, out-of-range chunk size | Pass |

Skipped: none. The production preset (`windows-x64`, `PV_TEST_HOOKS=OFF`) registers 10 tests
(no `test_cli`) and its `pv.exe` rejects `--test-vmk-file` as a usage error (exit 1).

### T2, Android automated (emulator)

`gradlew connectedDebugAndroidTest` on `Pixel_8_API_35` (Android 15): **15 tests, 0 failures,
0 errors, 0 skipped** (2.0 s on device, 40 s wall clock).

| Class | Tests | Result |
|---|---|---|
| NativeInitTest | library loads, `pv_init` ok, libsodium at least 1.0.21 | Pass |
| VaultTest | 1 MB round trip with size arithmetic and inspect; sizes 0, 1, 4095, 4096, 4097, 12388 at chunk 4096; tampered ciphertext (AUTH_FAILED); tampered wrapped DEK (WRONG_VMK); truncation at boundary (TRUNCATED), inside chunk (AUTH_FAILED), inside header (MALFORMED_HEADER); wrong key (WRONG_VMK) then correct key still ok; stale `.partial` removed on open; missing object (NOT_FOUND) | Pass (8) |
| KeystoreTest | seal and open round trip, two seals differ, security level reported; tampered envelope: magic, version, provider, alias, IV, tag, body, short, long, empty; `Vault.create` then `open` persists through the Keystore envelope, second create refused; `link()` probe logged | Pass (4) |
| GoldenVectorTest | all 8 committed vectors decrypt with the expected ID, length and hash; wrong key fails; export of 3 Android-made vectors to `files/pv_export` with local check | Pass (2) |

Logged on the emulator: `Keystore security level: SOFTWARE`; `link() in filesDir: denied
errno=13`.

### Android to Windows (Milestone E check)

The three vectors written by `GoldenVectorTest.exportVectorForWindows` on the emulator were
pulled with `adb exec-out run-as com.privatevault.app cat files/pv_export/<name>` (Git Bash,
binary safe) and decrypted on Windows:

```
build\windows-x64-tests\tests\core\Release\test_golden.exe D:\repo\PrivateVault\build\android-export
android_empty ok (0 bytes)
android_three_chunks ok (12388 bytes)
android_default_chunk ok (70000 bytes)
```

Hashes equal those of the Windows-made vectors of the same length. **Verified.**

### T3, device manual or scripted

**Physical device: Not verified.** `adb install` of the debug APK on the Xiaomi 2201116SG
fails immediately on two attempts:

```
adb -s 9c4424ba81c6 install -r android\app\build\outputs\apk\debug\app-debug.apk
Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```

MIUI 14 blocks installs over USB unless "Install via USB" is enabled in Developer options
(MIUI requires signing in to a Mi account for that switch) and the confirmation shown on the
phone is accepted. Changing that setting is outside what I was allowed to do. Remaining steps
once it is enabled, from `README.md`:

```
adb -s 9c4424ba81c6 install app-debug.apk
adb -s 9c4424ba81c6 shell am start -n com.privatevault.app/.MainActivity --es actions open,security_level,encrypt_1mb,encrypt_100mb,verify
adb -s 9c4424ba81c6 shell am force-stop com.privatevault.app
adb -s 9c4424ba81c6 shell am start -n com.privatevault.app/.MainActivity --es actions open,verify
adb -s 9c4424ba81c6 reboot            # then unlock the phone with its PIN
adb -s 9c4424ba81c6 shell am start -n com.privatevault.app/.MainActivity --es actions open,verify
adb -s 9c4424ba81c6 shell am force-stop com.privatevault.app
adb -s 9c4424ba81c6 shell am start -n com.privatevault.app/.MainActivity --es actions open,tamper,truncation,wrong_key,security_level
adb -s 9c4424ba81c6 logcat -d -s PrivateVault:I
```

Expected on this phone: `Keystore security level: TRUSTED_ENVIRONMENT` (no StrongBox
feature reported), `arm64-v8a` library, no root needed. Timing for the 100 MB sample will be
printed by the `encrypt_100mb` and `verify` actions.

**Same procedure on the emulator (not a device result):** all steps passed.

| Step | Result on emulator |
|---|---|
| Install debug APK, launch, create vault | `created new vault`, level SOFTWARE |
| Encrypt 1 MB, encrypt 100 MB | 108 ms and 3584 ms; object sizes 1 048 993 and 104 884 945 bytes |
| Verify both | hashes match (6.1 s) |
| `am force-stop`, relaunch, open, verify | `opened existing vault`, 2 objects, hashes match |
| `adb reboot`, relaunch, open, verify | hashes match after reboot |
| Tamper, truncation, wrong key | PASS, PASS, PASS (AUTH_FAILED, TRUNCATED, WRONG_VMK); test objects deleted afterwards |
| Storage | `files/vault/vault.key` (97 bytes) and `files/vault/objects/*.pvo`, mode 0600, owner the app uid; nothing else written |
| Crashes | none in `AndroidRuntime` |

### APK checks (`aapt2`, `zipalign`, `llvm-nm` on the debug APK)

- `aapt2 dump permissions`: package only, no `uses-permission`.
- Merged manifest: `allowBackup=false`, `fullBackupContent` and `dataExtractionRules`
  reference the exclude-everything rules, `minSdkVersion 28`, `targetSdkVersion 35`,
  `native-code: arm64-v8a x86_64`, `debuggable=true` (debug build).
- `zipalign -c -P 16 -v 4`: "Verification successful"; both `.so` files 16 KB aligned,
  stored uncompressed.
- Exported dynamic symbols of each `.so`: only the `Java_com_privatevault_app_NativeBridge_*`
  entry points.

## Android device

Install failed with `INSTALL_FAILED_USER_RESTRICTED` (see T3). No other APK was installed on
the phone and no setting was changed. The phone stayed connected throughout.

## Security review notes

- **Key handling and lifetime.** The VMK lives in `sodium_malloc` memory inside the vault
  handle and is freed (zeroed) on close. DEKs and the derived desktop KEK are stack buffers
  wiped with `sodium_memzero` after use; plaintext chunk buffers are wiped on scope exit. The
  password buffer uses `sodium_malloc`. On Android the VMK passes through a Kotlin
  `ByteArray` that is overwritten after the JNI copy; the garbage collector may keep copies
  (recorded in the threat model). Test keys exist only in `tests/fixtures`, the host tests,
  and the `androidTest` source set.
- **Authentication and failure behaviour.** Every chunk is verified before its plaintext is
  written; completeness is reported only at FINAL. Wrong key, every header change, ciphertext
  change, truncation, trailing data, and wrong tags fail with a status code and no partial
  success. Two limits are documented and tested: a cut inside a chunk and trailing data after
  a short final chunk both surface as `AUTH_FAILED` rather than the more specific code (D-007).
- **Temporary files and plaintext.** Writers use `.partial` plus a no-replace rename; stale
  `.partial` files are removed on open. Decrypt to file writes `.pvtmp` and renames only after
  FINAL; on failure the temp file is deleted and tests confirm nothing is left. The Android
  app never writes plaintext: samples come from the deterministic source and verification
  goes to a hash. Deletion on flash storage does not guarantee erasure.
- **Logging.** The CLI prints error names and object IDs. The app logs object IDs, byte
  counts, status names, hashes of synthetic data, and the Keystore level. No key bytes,
  passwords, or sample content appear in any output; `test_cli` and the T2 tests exercise
  these paths. The debug build is `debuggable`, so `run-as` can read app files; a release
  build would not allow that.
- **Backup settings.** `allowBackup=false` plus full-backup-content and data-extraction rules
  that exclude every domain for cloud backup and device transfer, confirmed in the merged
  manifest.
- **Threat limitations.** Rooted or compromised devices, debuggers, memory copies by the
  Kotlin runtime, swap and hibernation on Windows, screen capture, metadata (sizes, counts,
  timestamps), key loss on uninstall or forgotten password, and the absence of any
  independent review. See `docs/THREAT_MODEL.md`.

## Dependencies

| Dependency | Version | Source | License | Verification |
|---|---|---|---|---|
| libsodium | 1.0.22 (`1.0.22-RELEASE`) | github.com/jedisct1/libsodium via vcpkg port 1.0.22#1 | ISC | SHA512 pinned in the vcpkg portfile; upstream minisign key and signature recorded in `docs/DEPENDENCIES.md` |
| vcpkg | tag 2026.07.29 | github.com/microsoft/vcpkg | MIT | `builtin-baseline` commit in `vcpkg.json`, libsodium version override |
| Gradle | 8.10.2 | services.gradle.org | Apache 2.0 | `distributionSha256Sum` in the wrapper; wrapper jar SHA-256 checked against the published value |
| AGP, Kotlin plugin | 8.8.2, 2.1.10 | Google Maven, Maven Central | Apache 2.0 | Gradle dependency resolution |
| androidx.test runner, ext junit, JUnit | 1.6.2, 1.2.1, 4.13.2 | Google Maven, Maven Central | Apache 2.0, EPL 1.0 | `androidTest` only |

No TrueCrypt or VeraCrypt code, no test framework beyond CTest, no networking or analytics.

## Files changed

- Root: `CMakeLists.txt`, `CMakePresets.json`, `vcpkg.json`, `.gitignore`, `CLAUDE.md`, `README.md`.
- `src/vault_core/`: `include/pv/pv.h`, `src/{common.h,fs.h,fs.cpp,format.h,format.cpp,object.cpp,vault.cpp,streams.cpp,pv_init.cpp}`, `CMakeLists.txt`.
- `src/desktop_cli/`: `envelope.{h,cpp}`, `password.{h,cpp}`, `main.cpp`, `CMakeLists.txt`.
- `tests/core/`: `check.h`, `test_support.h`, eleven `test_*.cpp`, `CMakeLists.txt`; `tests/tools/make_vectors.cpp`; `tests/fixtures/v1/` (8 `.pvo`, `manifest.txt`, `README.md`, `test_vmk.txt`, `test_seed.txt`).
- `android/`: Gradle wrapper and build files, `app/src/main/cpp/{CMakeLists.txt,jni_bridge.cpp}`, `app/src/main/java/com/privatevault/app/{NativeBridge,KeystoreKeyProvider,Vault,MainActivity}.kt`, manifest and backup rules, `app/src/androidTest/.../{NativeInitTest,VaultTest,KeystoreTest,GoldenVectorTest,TestKeys}.kt`.
- `docs/`: `PHASE1_DESIGN.md`, `THREAT_MODEL.md`, `DEPENDENCIES.md`, `CONTAINER_FEASIBILITY.md`, `DECISIONS.md` (D-001 to D-009), `ROADMAP.md`, `PHASE1_PROGRESS.md`, this report.

## Unresolved risks and next actions

- T3 on the physical device needs "Install via USB" enabled on the phone; then run the
  steps above and add the results (security level, timings) to this report.
- The working tree is not committed (`git log` shows only the two original commits). A
  commit message is suggested below.
- Trailing data after a short final chunk and cuts inside a chunk report `AUTH_FAILED`
  (D-007). A length trailer is the candidate fix for a format version 2.
- The Android rename path has a check-then-rename window (D-008), judged negligible with
  random 128-bit IDs.
- The Kotlin `ByteArray` copy of the VMK cannot be reliably wiped.
- The emulator Keystore is software-backed; hardware-backed levels are unconfirmed until T3
  runs on the device.
- NDK r29 and AGP 8.8.2 are current choices; AGP 9.x exists and would need a newer Android
  Studio.
- No independent review of the format or the code has taken place.

## Phase 1 acceptance checklist (handoff Section 7)

| Criterion | Result |
|---|---|
| Windows core builds and all T1 tests pass | Pass (11/11 Debug and Release) |
| Android debug app builds reproducibly | Pass (`gradlew assembleDebug`, pinned Gradle, AGP, NDK, vcpkg baseline) |
| Merged manifest has no INTERNET or storage permissions; backup disabled | Pass |
| T2 tests pass on an emulator or device | Pass (15/15 on emulator) |
| T3 tests pass if a device is available, otherwise blocker and remaining steps explicit | Not verified (install blocked by MIUI; blocker and steps listed; procedure passed on the emulator) |
| Encryption and decryption are chunked and authenticated | Pass |
| Wrong key, tampering, truncation, trailing data fail closed | Pass (with the D-007 code mapping) |
| Desktop and Android key handling outside the shared core | Pass (`src/desktop_cli`, Kotlin `KeystoreKeyProvider`; core takes VMK bytes only) |
| No unnecessary plaintext persisted (Android stores none) | Pass (Android: hash sink only; Windows: only the user-requested decrypt output) |
| Cross-platform format compatibility tested | Pass (committed vectors on both platforms; Android-made vectors decrypted on Windows) |
| New developer can follow the README | Pass for Windows and emulator (commands in `README.md`); device install needs the MIUI switch |
| Report separates demonstrated facts from assumptions | Pass (this document) |
| Milestone A: clean configure, build, test; libsodium links for Windows and both Android ABIs | Pass |
| Milestone B: docs understandable alone; checkpoint held | Pass (approved 2026-10-13 with changes, all applied) |
| Milestone C: all T1 tests pass, skipped tests explained | Pass (none skipped) |
| Milestone D: `assembleDebug`, manifest checks, T2 on emulator, T3 or explicit blocker | Pass except T3 (Not verified, blocker explicit) |
| Milestone E: vectors on both platforms, Android-to-Windows check, no secrets or build output in the tree, docs updated, this report | Pass |

## Suggested commit message

```
Phase 1: encrypted storage core, desktop CLI, Android proof of concept

Portable C++20 vault core with a C ABI over libsodium 1.0.22 (secretstream
XChaCha20-Poly1305 objects, per-object keys wrapped under a vault master
key), Windows CLI with an Argon2id password envelope, Android app with a
Keystore AES-GCM envelope and JNI bridge, CTest and instrumented tests,
committed golden vectors, and the Phase 1 design, threat model, dependency,
decision, and test documents.

T1: 11/11 on Windows (Debug and Release). T2: 15/15 on the API 35 emulator.
T3: not verified; the MIUI device blocks adb install until Install via USB
is enabled.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```
