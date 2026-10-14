# Phase 3A test report

Date: 2026-10-14. Format per `PHASE3A_BRIEF.md` Section 10. Every result below comes from a
command that was run; nothing is inferred. "Not verified" means exactly that.

## Status

**Partially complete.** The core additions (T1) and the Android PIN lock (T2, instrumented
and host-driven) pass on Windows and the emulator. The physical device tier (T3) did not
run: the Xiaomi was attached for part of the session but had gone again when the final
build was ready for its install, and every install on it needs the developer's tap. The
Milestone A phone measurements (Argon2id timing, Keystore HMAC level) are therefore still
open, and the default Argon2id parameters (CP-2) stay provisional. Section "T3" lists the
exact steps left. Four defects were found by the test runs and fixed (Section "Defects").

## Environment

| Component | Version |
|---|---|
| OS, Visual Studio, MSVC, CMake, vcpkg, JDK, NDK, libsodium, Gradle, AGP, Kotlin, compileSdk 36 / targetSdk 35 / minSdk 28, CameraX 1.6.2 | As in `docs/PHASE2_TEST_REPORT.md` (unchanged) |
| New Android dependency | `androidx.test:core` 1.6.1 (`androidTest` only). `lifecycle-process` 2.6.2 was added and removed again (D-020). |
| App version | 0.3.0 (`versionCode` 3) |
| Emulator | 37.2.12, `Pixel_8_API_35` (Android 15, x86_64), Keystore `SOFTWARE`, no screen lock except during the PIN procedure |
| Device | Xiaomi 2201116SG: not available for this tier (see T3) |

## Architecture changes

- **Core (additive, Android-free):** error code 24 `PV_ERR_ENVELOPE_NO_SLOT`; PIN
  stretching (`pv_pin_stretch`, bounds INTERACTIVE to MODERATE, defaults ops 3 / 64 MiB
  provisional); `pv_pin_salt_generate`; delay arithmetic (`pv_pin_delay_ms`,
  `pv_pin_remaining_ms`); envelope provider 3 (`pv_envelope3_parse`, `seal`, `open`) with
  slots, a PIN slot carrying mechanism, alias, Argon2id parameters, salt, nonce, and the
  wrapped VMK, and additional data equal to the whole envelope with every wrapped field
  zeroed; vault forms `pv_vault_create_envelope3`, `pv_vault_open_envelope3`,
  `pv_vault_write_envelope3` (the VMK never leaves the core), `pv_vault_destroy_files`.
  `fs` gained `rename_replace` (the one place an existing file is replaced) and
  `remove_dir`. No existing function, code, or vector changed.
- **Android:** `LockController` (states `NO_VAULT`, `LOCKED`, `UNLOCKED`, `LOCKING`;
  unlock, setup, migration of the provider 2 vault, PIN change, rewrap with a fresh
  Keystore HMAC key per rewrap, alias sweep, lock with queue drain and encrypt cancel,
  erase with a pending flag), `AttemptPolicy` (attempt recorded before the stretch, reset
  only on a successful unwrap, elapsed and wall clocks plus boot count), `KeystoreHmacProvider`
  (the `DeviceBoundKey` for mechanism 1), `PinEnvelope`, `LockActivity` (launcher, in-app
  keypad, `PinBuffer`, setup, change, erase after typed `ERASE`, countdown, diagnostics),
  `MainActivity` and `CaptureActivity` not exported and routed to the lock screen,
  `FLAG_SECURE` on all three, `ScriptActivity` in the debug source set only. Lock triggers:
  background (activity count, 700 ms), screen off, 5 min idle, manual; a process always
  starts locked.
- **Design and decisions:** `docs/PHASE3A_DESIGN.md` (approved with four changes, Section 18
  lists the implementation deltas), D-016 to D-020, `docs/THREAT_MODEL.md` Section 7.

## Build and tests

Windows (repository root, `VCPKG_ROOT` and `VCPKG_DEFAULT_BINARY_CACHE` set):

```
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --envelope3   # run once, envelope vectors
ctest --preset windows-x64-tests-debug
cmake --build --preset windows-x64-tests-release
ctest --preset windows-x64-tests-release
```

Android (`android/`, `ANDROID_HOME`, `ANDROID_NDK_HOME`, `JAVA_HOME`, `VCPKG_ROOT` set, emulator booted headless):

```
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark
$env:ANDROID_SERIAL = "emulator-5554"
gradlew.bat connectedDebugAndroidTest
```

Host-driven steps: `build/p3a/host-t2.sh`, `host-bg.sh`, `emu-pin-p3a.sh` (not committed;
the commands are also in `docs/PHASE3A_PROGRESS.md` and `README.md`).

### T1, host automated (Windows, CTest)

Before any change: 14/14 Debug and Release. After Milestone C: Debug **16 passed, 0
failed** (35 s), Release **16 passed** (15 s). No warnings at `/W4`.

| Test | Covers | Result |
|---|---|---|
| test_pin_stretch (new) | known answer from the fixture, determinism, salt and PIN and parameter sensitivity, 12-digit and long inputs, bounds (ops 1, 4, 0, huge; memory below, above, huge, 8 KiB) refused at once, argument checks, defaults inside the range, salt generation, the delay table to 14 failures and beyond, remaining time for same boot, clock forward, clock back, elapsed behind, reboot, negative wall values | Pass |
| test_envelope3 (new) | seal and open, parse fields, wrong KEK, fresh nonce, builder reproduces the layout; every header and slot field flipped (magic, version, provider 1 and 2, slot count 0, 9, 2-with-one-slot, reserved, slot type unknown and biometric alone, slot length long, short, past the end, alias length mismatch, NUL in alias, mechanism, alias byte, salt, nonce, body, tag, KDF parameters in and out of range); every truncation, a trailing byte, the 4096 limit; unknown slot alone (`NO_SLOT`), unknown before and after a PIN slot (accepted, counted, bound), trailing slot removed, empty unknown slot, two PIN slots (first used), eight slots, nine slots, PIN bodies of the wrong shape, alias 0, 64, 65 bytes, structure before KDF range; seal and open argument checks; vault forms (create, second create refused, open with wrong and right KEK, verify, rewrap keeps the VMK and the objects, old envelope bytes still open with the old KEK, stale partial removed by open and write, a write that cannot create its partial keeps the old envelope, key file too long, provider 2 file, missing file, create refused over a bad key file); destroy files (objects, partials, stale key partial, twice, interrupted setup directory, arguments) | Pass |
| test_golden | 8 Phase 1 and 4 Phase 2 vectors unchanged; `envelope3.txt`: the test PIN stretches to the recorded `k_pin`, both envelope vectors parse with the recorded fields and open to the test VMK with the KEK stand-in, the wrong KEK fails, the VMK that came out decrypts an object vector | Pass |
| other 13 tests | unchanged | Pass |

### T2, Android automated (emulator)

Final run: **51 tests, 0 failures, 0 errors, 1 skipped** (`LockBoundKeyTest`, run
separately with a PIN, below). The 31 Phase 1 and 2 tests are unchanged and pass.

| Class | Tests | Result |
|---|---|---|
| PinTest (new) | stretch equals the fixture `k_pin`; bounds refused in under 100 ms; argument checks; salts fresh; delay table; remaining time cases | Pass (3) |
| Envelope3Test (new) | create, parse, object, second create refused, wrong KEK, rewrap under another KEK and alias, stale partial, flipped byte, flipped provider, destroy twice; parse errors without running (truncated, trailing, empty, no slot, bad KDF); the two committed envelope vectors opened through the vault form beside the `one_byte` object vector, which verifies | Pass (3) |
| KeystoreHmacTest (new) | fresh aliases, create, level, mode, deterministic MAC, other key gives other MAC, alias listing, delete, `NO_KEY` | Pass (1) |
| LockTest (new) | setup, four free wrong PINs then a delay, refused attempts do not count, delay survives a new policy object, delays with injected clocks (forward, back, reboot), attempt recorded before the stretch and left counted when the stretch aborts, PIN change rotates the key and the old PIN stops working, migration from a provider 2 vault, migration interrupted at each of four points (old or new envelope, never neither; leftovers removed), PIN change interrupted at each point, Keystore key deleted gives `KEY_MISSING` without counting and erase recovers, lock during a slowed capture cancels it in 242 ms and leaves no partial, capture refused while locked, erase from unlocked, interrupted erase finished at the next start, `PinBuffer` | Pass (10) |
| LockScreenTest (new) | `FLAG_SECURE` on the lock activity window; the vault screen destroys itself when launched locked | Pass (2) |
| Phase 1 and 2 classes | 31 | Pass (30, 1 skipped) |

Logged by the run: unlock 1.3 to 1.8 s (debug build); Keystore HMAC `SOFTWARE`, not
lock-bound. Logcat of the run (286 lines): 0 hex runs over 32 characters, 0 occurrences of
the test PINs.

### T2, host-driven (emulator, debug build)

| Step | Result |
|---|---|
| Setup through `ScriptActivity`, `am kill`, relaunch | lock screen in front, vault screen absent |
| Scripted actions without `unlock_pin` first | refused |
| `am kill` 2 s into a 4 s delayed wrong unlock, then four scripted wrong attempts | the fifth reports `attempts 5` and a 28 s wait: the killed attempt counted |
| Clock moved two hours forward during the delay | still `wait 25 s`; after the delay the right PIN unlocks |
| Screen off | `locking (screen off)`, `locked` |
| Idle (5 s by script) | `locking (idle)`, `locked`, lock screen in front |
| Home | `locking (background)`, `locked` |
| Rotation while unlocked | no lock |
| Home during a capture slowed to 1.5 s per read | `locking (background)`, `capture failed: CANCELLED`, `locked`; `objects/` empty; next unlock lists no partial |
| Lock screen `screencap`, recents card | both black apart from the status bar |
| Merged manifests | debug: `ScriptActivity` and `LockActivity` exported, the others not; benchmark: only `LockActivity`; `CAMERA` the only permission in both |
| `leak_scan` after the run | 0 findings |
| Logcat (113 lines) | 0 test PIN strings, 0 hex runs over 32 characters, no `k_pin` or `kek` value (the ten hits are constant names in bounds messages) |

### T2, lock-bound mode with an emulator PIN

`locksettings set-pin`; `LockBoundKeyTest` passes (provider 2 path, as Phase 2); provider 3
setup creates a lock-bound HMAC key; with the screen locked the unlock reports `keystore
InvalidKeyException` (attempt counted); after unlocking the device the PIN opens the vault
and verifies; PIN and app data cleared afterwards. The emulator Keystore is
software-backed, so this exercises the code path only. **Pass (code path).**

### Argon2id and Keystore HMAC measurements (Milestone A)

Emulator figures in `docs/PHASE3A_DESIGN.md` Section 3.3 (benchmark build: ops 2 / 64 MiB
156 to 349 ms, ops 3 / 256 MiB 452 to 908 ms, noisy; HMAC key `SOFTWARE`, MAC 1 ms).
**Xiaomi: not measured** (phone not attached when the build was ready). The default
parameters ops 3 / 64 MiB are therefore provisional (CP-2).

### T3, device (Xiaomi 2201116SG)

**Not run.** The phone was attached for part of the session and gone when the final debug
APK was ready; the install attempt returned `device not found`. Remaining steps, each
install needing the developer's tap, with `build/p3a/phone-p3a.sh` as the driver
(`install-debug`, `bench`, `migrate <pin>`, `unlock <pin>`, `wrong <pin>`, `capture <pin>`,
`install-benchmark`, `logcat`):

1. Install the debug APK; run `kdf_bench,hmac_probe` through `ScriptActivity` (no vault
   touched); fill the phone rows of design Section 3.3 and settle CP-2.
2. `setup_pin=<throwaway>` with `verify_all`: migrates the 52-object provider 2 vault in
   place and verifies every object; expect `vault migrated to provider 3`, `provider 2 key
   present: false`, `verify_all: 52 of 52 ok`.
3. Unlock time: the `lock: unlocked in N ms` line (debug); then the benchmark APK
   (second tap), PIN typed on the lock screen, same line.
4. Background, screen off, and the MIUI camera kill case: expect the lock screen on return.
5. Five wrong PINs on the keypad: the countdown appears; a sixth attempt is refused.
6. Recents card with the lock screen in front.
7. `unlock_pin=<pin>,capture_photo_n=3,verify_all,list,leak_scan` (debug build).
8. Erase only if the developer agrees.

## Defects found and fixed

- The JNI cancel flag stayed set after a lock and made every later encrypt fail; it is
  now cleared once the encrypt queue is idle.
- An empty byte array reached the envelope parser as a null pointer (`INVALID_ARGUMENT`
  instead of `MALFORMED`); the JNI maps it to an empty envelope.
- `ScriptActivity` used `Theme.NoDisplay`, which requires `finish()` before resume, so every
  scripted run crashed; now a translucent theme.
- The background trigger never fired: `ProcessLifecycleOwner` is attached through the
  `androidx.startup` provider that D-013 removes from the manifest. Replaced by activity
  counting in `PvApp` (D-020); the `lifecycle-process` dependency was removed again.
- Two test expectations were wrong (remaining delay is read after the stretch; the clock-back
  case keeps the larger estimate).

## Known limits and unresolved risks

- T3 and the phone measurements are outstanding; CP-2 is provisional.
- A Keystore refusal during an unlock (lock-bound key with the device locked) counts as an
  attempt, because the record precedes the stretch (design Section 13).
- Kotlin copies of the PIN digits, `k_pin`, and `kek` exist briefly in the managed heap; the
  provider 2 migration still passes the VMK through Kotlin once.
- The delays are app-enforced; root resets them. No hardware attempt counter.
- Argon2id allocates `memlimit` once per unlock; the default of 64 MiB was chosen with the
  phone capture peak of about 50 MB in mind and has not been measured on the phone.
- The construction has not been independently reviewed.

## Phase 3A acceptance checklist (brief Section 8)

| # | Criterion | Result |
|---|---|---|
| 1 | Phase 1 and 2 T1 and T2 suites pass | Pass (T1 14/14 before and 16/16 after; T2 31 of the 51, 1 skipped as in Phase 2) |
| 2 | Provider 3 vault opens only with the correct PIN; VMK never crosses JNI; tampered envelope and missing Keystore key fail closed | Pass on the emulator (T1 tamper per field and slot; T2 wrong PIN, flipped byte, deleted alias); the VMK crosses JNI only in the provider 2 migration |
| 3 | Migration and PIN change survive interruption at every step | Pass (T2 hooks at four points each; T1 stale partial and failed write) |
| 4 | Lock triggers per P3-4 on emulator and Xiaomi; capture blocked while locked | Pass on the emulator (background, screen off, idle, manual, process restart; capture refused and cancelled); **not verified on the Xiaomi** |
| 5 | Attempt delays per P3-5, persistent across restarts and clock changes | Pass (T2 instrumented and host: kill during unlock, clock forward, new policy object; reboot by injected boot count) |
| 6 | Erase from the lock screen; interrupted erase leaves nothing readable | Pass (T2: erase from locked and unlocked, pending flag finishes an interrupted erase at the next start); the lock screen flow itself is manual UI, exercised through the controller |
| 7 | Core changes additive and Android-free; golden vectors decrypt on both platforms | Pass (two envelope vectors and the `k_pin` answer check on Windows and the emulator) |
| 8 | Threat model updated | Pass (`docs/THREAT_MODEL.md` Section 7) |
| 9 | Report separates demonstrated facts from assumptions | Pass (this document) |

## Files changed

- Root: `README.md`, `CLAUDE.md`.
- `src/vault_core/`: `include/pv/pv.h`, `src/envelope3.cpp` (new), `src/pin.cpp` (new),
  `src/vault_internal.h` (new), `src/vault.cpp`, `src/fs.h`, `src/fs.cpp`, `src/pv_init.cpp`,
  `CMakeLists.txt`.
- `tests/core/`: `test_pin_stretch.cpp`, `test_envelope3.cpp`, `envelope3_builder.h` (new),
  `test_golden.cpp`, `CMakeLists.txt`; `tests/tools/make_vectors.cpp`;
  `tests/fixtures/v1/`: `envelope3_single.key`, `envelope3_unknown_slot.key`, `envelope3.txt`
  (new), `README.md`.
- `android/`: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`,
  `app/src/debug/AndroidManifest.xml` (new), `app/src/main/cpp/jni_bridge.cpp`,
  `app/src/main/java/com/privatevault/app/{LockController,AttemptPolicy,KeystoreHmacProvider,PinEnvelope,PinPad,LockActivity,Diagnostics}.kt`
  (new), `{MainActivity,CaptureActivity,CaptureSession,PvApp,Vault,NativeBridge}.kt`,
  `app/src/debug/java/com/privatevault/app/ScriptActivity.kt` (new),
  `app/src/androidTest/.../{PinTest,Envelope3Test,KeystoreHmacTest,LockTest,LockScreenTest}.kt` (new).
- `docs/`: `PHASE3A_DESIGN.md`, `PHASE3A_PROGRESS.md`, this report (new); `DECISIONS.md`
  (D-016 to D-020), `DEPENDENCIES.md`, `THREAT_MODEL.md`, `ROADMAP.md`.

## Suggested commit message

```
Phase 3A: PIN lock and PIN-derived vault key

Core: envelope provider 3 with PIN slots (Argon2id stretch, device-bound
KEK, XChaCha20-Poly1305 wrap, additional data over the whole masked
envelope), vault create/open/write forms that keep the VMK inside the
core, destroy_files, wrong-PIN delay arithmetic, error code 24. Two
envelope golden vectors with the recorded stretch answer. T1 16/16.

Android: lock screen with an in-app keypad as the only exported activity,
LockController (unlock, setup, in-place migration of the provider 2 vault,
PIN change with a fresh Keystore HMAC key per rewrap, lock on background,
screen off, idle and manual with encrypt cancel, erase with a pending
flag), attempt counter recorded before the stretch, debug-only script
entry. T2 51 tests on the emulator, host-driven lock, kill, clock and
manifest checks, lock-bound mode with an emulator PIN.

Docs: design (approved with four changes), D-016 to D-020, threat model
Section 7, dependencies, progress, test report. T3 on the Xiaomi and the
phone KDF timing are still open; the default Argon2id parameters stay
provisional.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```
