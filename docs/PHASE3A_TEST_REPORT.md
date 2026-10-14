# Phase 3A test report

Date: 2026-10-14. Format per `PHASE3A_BRIEF.md` Section 10. Every result below comes from a
command that was run; nothing is inferred. "Not verified" means exactly that.

## Status

**Complete.** The core additions (T1) and the Android PIN lock (T2, instrumented and
host-driven) pass on Windows and the emulator, and the device tier (T3) ran on the Xiaomi
2201116SG on 2026-10-14 with the developer providing the install taps and the manual
actions: the provider 2 vault migrated in place, every object verifies, captures, wrong-PIN
delays, the lock triggers, and the recents card behave as designed. The Argon2id default
was settled from the benchmark-build measurement on the phone (CP-2: ops 2 / 256 MiB,
D-021) and the phone vault was rewrapped with it and verified again. Five defects were found
by the test runs and fixed (Section "Defects"), one of them on the phone. The vault on the
phone was not erased (55 objects remain).

## Environment

| Component | Version |
|---|---|
| OS, Visual Studio, MSVC, CMake, vcpkg, JDK, NDK, libsodium, Gradle, AGP, Kotlin, compileSdk 36 / targetSdk 35 / minSdk 28, CameraX 1.6.2 | As in `docs/PHASE2_TEST_REPORT.md` (unchanged) |
| New Android dependency | `androidx.test:core` 1.6.1 (`androidTest` only). `lifecycle-process` 2.6.2 was added and removed again (D-020). |
| App version | 0.3.0 (`versionCode` 3) |
| Emulator | 37.2.12, `Pixel_8_API_35` (Android 15, x86_64), Keystore `SOFTWARE`, no screen lock except during the PIN procedure |
| Device | Xiaomi 2201116SG (veux), Android 13, MIUI 14, arm64-v8a, Keystore `TRUSTED_ENVIRONMENT`, no StrongBox, no screen lock; shell input injection refused (`INJECT_EVENTS`) |

## Architecture changes

- **Core (additive, Android-free):** error code 24 `PV_ERR_ENVELOPE_NO_SLOT`; PIN
  stretching (`pv_pin_stretch`, bounds INTERACTIVE to MODERATE, defaults ops 2 / 256 MiB,
  D-021); `pv_pin_salt_generate`; delay arithmetic (`pv_pin_delay_ms`,
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
  lists the implementation deltas), D-016 to D-021, `docs/THREAT_MODEL.md` Section 7.

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
failed** (35 s), Release **16 passed** (15 s). Final run with the approved defaults (CP-2):
Debug **16 passed** (55 s), Release **16 passed** (17 s). No warnings at `/W4`.

| Test | Covers | Result |
|---|---|---|
| test_pin_stretch (new) | known answer from the fixture, determinism, salt and PIN and parameter sensitivity, 12-digit and long inputs, bounds (ops 1, 4, 0, huge; memory below, above, huge, 8 KiB) refused at once, argument checks, defaults inside the range, salt generation, the delay table to 14 failures and beyond, remaining time for same boot, clock forward, clock back, elapsed behind, reboot, negative wall values | Pass |
| test_envelope3 (new) | seal and open, parse fields, wrong KEK, fresh nonce, builder reproduces the layout; every header and slot field flipped (magic, version, provider 1 and 2, slot count 0, 9, 2-with-one-slot, reserved, slot type unknown and biometric alone, slot length long, short, past the end, alias length mismatch, NUL in alias, mechanism, alias byte, salt, nonce, body, tag, KDF parameters in and out of range); every truncation, a trailing byte, the 4096 limit; unknown slot alone (`NO_SLOT`), unknown before and after a PIN slot (accepted, counted, bound), trailing slot removed, empty unknown slot, two PIN slots (first used), eight slots, nine slots, PIN bodies of the wrong shape, alias 0, 64, 65 bytes, structure before KDF range; seal and open argument checks; vault forms (create, second create refused, open with wrong and right KEK, verify, rewrap keeps the VMK and the objects, old envelope bytes still open with the old KEK, stale partial removed by open and write, a write that cannot create its partial keeps the old envelope, key file too long, provider 2 file, missing file, create refused over a bad key file); destroy files (objects, partials, stale key partial, twice, interrupted setup directory, arguments) | Pass |
| test_golden | 8 Phase 1 and 4 Phase 2 vectors unchanged; `envelope3.txt`: the test PIN stretches to the recorded `k_pin`, both envelope vectors parse with the recorded fields and open to the test VMK with the KEK stand-in, the wrong KEK fails, the VMK that came out decrypts an object vector | Pass |
| other 13 tests | unchanged | Pass |

### T2, Android automated (emulator)

Final run with the approved defaults: **53 tests, 0 failures, 0 errors, 1 skipped**
(`LockBoundKeyTest`, run separately with a PIN, below), 132 s. The 31 Phase 1 and 2 tests
are unchanged and pass. Earlier full runs: 51 tests after Milestone D (two of them passed,
one later run failed only `LeakScanTest` on test-runner rows, Section "Defects").

| Class | Tests | Result |
|---|---|---|
| PinTest (new) | stretch equals the fixture `k_pin`; bounds refused in under 100 ms; argument checks; salts fresh; delay table; remaining time cases | Pass (3) |
| Envelope3Test (new) | create, parse, object, second create refused, wrong KEK, rewrap under another KEK and alias, stale partial, flipped byte, flipped provider, destroy twice; parse errors without running (truncated, trailing, empty, no slot, bad KDF); the two committed envelope vectors opened through the vault form beside the `one_byte` object vector, which verifies | Pass (3) |
| KeystoreHmacTest (new) | fresh aliases, create, level, mode, deterministic MAC, other key gives other MAC, alias listing, delete, `NO_KEY` | Pass (1) |
| LockTest (new) | setup, four free wrong PINs then a delay, refused attempts do not count, delay survives a new policy object, delays with injected clocks (forward, back, reboot), attempt recorded before the stretch and left counted when the stretch aborts, PIN change rotates the key and the old PIN stops working, migration from a provider 2 vault, migration interrupted at each of four points (old or new envelope, never neither; leftovers removed), PIN change interrupted at each point, Keystore key deleted gives `KEY_MISSING` without counting and erase recovers, lock during a slowed capture cancels it in 242 ms and leaves no partial, capture refused while locked, erase from unlocked, interrupted erase finished at the next start, `PinBuffer`; a vault made with the defaults records ops 2 / 256 MiB; a forced allocation failure in the stretch is reported as `NoMemory`, rolls the pre-recorded attempt and delay back, and leaves a PIN change unlocked (CP-2 conditions). The other lock tests make their vaults at ops 2 / 64 MiB through the test override | Pass (12) |
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
Xiaomi figures, debug and benchmark builds: the two rows of design Section 3.3 and the
"Argon2id, debug build" and "Benchmark build" rows of the T3 table below. The default
parameters follow from them (CP-2, D-021: ops 2 / 256 MiB).

### T3, device (Xiaomi 2201116SG, 2026-10-14, second session)

Debug APK installed by the developer (MIUI tap). Runs driven over adb through the debug-only
`ScriptActivity` with logcat streamed to a file (`build/p3a/phone-p3a.sh`, outputs under
`build/p3a/phone/`, not committed). MIUI refuses shell input injection (`INJECT_EVENTS`),
so Home, power, the system camera, and recents were operated by the developer by hand while
the log streamed; activities launched behind a dozing screen are stopped at once and lock
the vault, so every scripted step needed the screen on. The throwaway PIN is not recorded.

| Step | Result on the phone |
|---|---|
| Migration of the provider 2 vault (`setup_pin`) | `vault migrated to provider 3, not lock-bound, strongbox=false`; `provider 2 key present: false`; `objects dir: 52 entries` |
| Unlock and verify (debug build) | `lock: unlocked in 4767 ms`; `verify_all: 52 of 52 ok` (39 s, two 100 MB objects included); a later unlock 4714 and 4759 ms |
| Wrong-PIN delays | five scripted wrong PINs: attempts 1 to 4 free, the fifth reports `wait 26 s`; the right PIN during the delay is refused with `wait 23 s` |
| Phase 2 captures while unlocked | three captures at 2448x3264, 2.85 to 2.87 MB JPEG, `buffer=DIRECT`, encrypt 133 to 148 ms, `verify=ok` each; `verify_all: 55 of 55 ok`; `leak_scan: 0 findings, 60 files scanned` |
| Background (Home) | `locking (background)`, `locked`; reopening showed the lock screen |
| Screen off (power button) | locked through the activity-stop path (`locking (background)` 700 ms after the screen went off, before any screen-off broadcast reached the app); MIUI then killed the process (`SwipeUpClean`) and the relaunch started `LockActivity` |
| MIUI camera case (Home, system camera, Home, reopen) | locked on the Home press; the process was not killed this time (the system camera was already warm and the app had no camera bound), and the return showed the lock screen. The Phase 2 kill was not reproduced; locked either way |
| Recents card with the lock screen in front | blank white card (`build/p3a/phone/P-recents.png`) |
| Rewrap after CP-2 (`unlock_pin`, `change_pin` to the same throwaway PIN, verify) | `change_pin ok`; the envelope pulled afterwards parses as provider 3, one PIN slot, mechanism 1, ops 2 / 256 MiB, 157 bytes; `verify_all: 55 of 55 ok` (40 s); the app was then force-stopped, so the vault is locked |
| Keystore HMAC key | `TRUSTED_ENVIRONMENT`, not lock-bound (no screen lock), no StrongBox |
| Argon2id, debug build | design Section 3.3: ops 2 / 64 MiB 3.1 s, ops 3 / 64 MiB 4.7 s, up to 18.8 s at ops 3 / 256 MiB |
| Benchmark build (installed with a second tap) | Diagnostics from the lock screen: ops 2 / 64 MiB 203 to 232 ms, ops 3 / 64 MiB 288 ms, ops 3 / 128 MiB 586 ms, ops 2 / 256 MiB 833 to 876 ms, ops 3 / 256 MiB 1181 to 1183 ms; HMAC key `TRUSTED_ENVIRONMENT`, MAC 18 to 23 ms; unlock with the keypad under the provisional ops 3 / 64 MiB: `lock: unlocked in 348 ms` |
| Erase | not run (developer decision) |

Defect found on the phone and fixed during this tier: the lock closed the vault handle under
a running `verify_all` (Section "Defects"). The migration run that exposed it ended with
`verify_all: 1 of 52` and 51 `INVALID_ARGUMENT` lines; after the fix the same vault verified
52 of 52.

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
- Found on the Xiaomi (T3 migration step): the background lock fired while `verify_all`
  was decrypting a 100 MB object on the vault screen's worker thread (the phone screen
  was off, so the launched activities stopped at once). The lock closed and freed the core
  handle under the running decrypt; the decrypt completed on the freed handle and the
  remaining 51 objects failed closed with `INVALID_ARGUMENT`. The drain only covered the
  capture queue. Fixed: `Vault` holds a read-write guard, every core call takes the read
  side, `close()` takes the write side and waits, later calls report "vault closed";
  `verify_all` stops when the vault is locked. T2 was rerun on the emulator after the fix:
  50 of 51 pass, with `LeakScanTest.scanFindsNothingBeforeAndAfterCaptures` failing on two
  MediaStore rows, `Android/media/com.privatevault.app` and its child
  `additional_test_output`. That directory is created on the device by the Android Gradle
  plugin test runner for test output, not by the app. Shown in isolation
  (`build/p3a/leak-only.sh`): on a freshly booted emulator `Android/media` is empty, the
  class passes 3/3 on its own, and creating that directory by hand and rescanning
  reproduces exactly the two rows and the failure. The earlier full runs passed because the
  media scanner had not indexed the directory before the test ran. The scan behaves as
  designed; the row is test-runner state. Not an app defect; no code change.

## Known limits and unresolved risks

- On the phone the idle (5 min) and manual lock triggers were not exercised by hand; they
  are covered on the emulator. Erase was not run on the phone (developer decision).
- The MIUI camera kill of Phase 2 was not reproduced this time (the system camera was
  already running and the app had no camera bound); the lock screen appeared either way.
- The debug build takes about 12.5 s per stretch at the approved defaults on the phone and
  3.5 to 5 s on the emulator; the benchmark build 0.85 s. Unlock times quoted for the
  debug build include that.
- A Keystore refusal during an unlock (lock-bound key with the device locked) counts as an
  attempt, because the record precedes the stretch (design Section 13).
- Kotlin copies of the PIN digits, `k_pin`, and `kek` exist briefly in the managed heap; the
  provider 2 migration still passes the VMK through Kotlin once.
- The delays are app-enforced; root resets them. No hardware attempt counter.
- Argon2id allocates `memlimit` once per setup, unlock, or PIN change; the default is
  256 MiB (D-021), measured on the phone in all three operations (setup through the
  migration, unlock, and the scripted PIN change) without a failure. A device that cannot
  give it reports "not enough memory" and the attempt is not counted.
- The construction has not been independently reviewed.

## Phase 3A acceptance checklist (brief Section 8)

| # | Criterion | Result |
|---|---|---|
| 1 | Phase 1 and 2 T1 and T2 suites pass | Pass (T1 14/14 before and 16/16 after; T2 31 of the 53, 1 skipped as in Phase 2) |
| 2 | Provider 3 vault opens only with the correct PIN; VMK never crosses JNI; tampered envelope and missing Keystore key fail closed | Pass on the emulator (T1 tamper per field and slot; T2 wrong PIN, flipped byte, deleted alias); the VMK crosses JNI only in the provider 2 migration |
| 3 | Migration and PIN change survive interruption at every step | Pass (T2 hooks at four points each; T1 stale partial and failed write) |
| 4 | Lock triggers per P3-4 on emulator and Xiaomi; capture blocked while locked | Pass on the emulator (background, screen off, idle, manual, process restart; capture refused and cancelled) and on the Xiaomi (Home, screen off, the camera case, process restart all end on the lock screen; the idle and manual triggers were not exercised by hand on the phone) |
| 5 | Attempt delays per P3-5, persistent across restarts and clock changes | Pass (T2 instrumented and host: kill during unlock, clock forward, new policy object; reboot by injected boot count) |
| 6 | Erase from the lock screen; interrupted erase leaves nothing readable | Pass (T2: erase from locked and unlocked, pending flag finishes an interrupted erase at the next start); the lock screen flow itself is manual UI, exercised through the controller |
| 7 | Core changes additive and Android-free; golden vectors decrypt on both platforms | Pass (two envelope vectors and the `k_pin` answer check on Windows and the emulator; on the phone the migrated vault and the rewrapped envelope open and all objects verify) |
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
  (D-016 to D-021), `DEPENDENCIES.md`, `THREAT_MODEL.md`, `ROADMAP.md`.

## Suggested commit message

For the T3 session, on top of the Phase 3A commit:

```
Phase 3A T3 on the Xiaomi: handle guard, CP-2 defaults, phone results

The lock closed the core handle under a running verify_all on the vault
screen; Vault now holds a read-write guard, close waits for the call in
flight and later calls report "vault closed", verify_all stops when locked.

Argon2id defaults settled from the benchmark build on the phone (D-021):
opslimit 2, memlimit 256 MiB, 0.83 to 0.88 s per guess. An allocation
failure in the stretch is reported as "not enough memory" and rolls the
pre-recorded attempt back (debug hook and T2 test); lock tests make their
vaults at ops 2 / 64 MiB through a test override, one test checks the
defaults. Script entry gains change_pin.

Phone: provider 2 vault migrated in place, 52 then 55 objects verified,
wrong-PIN delays, captures, Home, screen off, camera case, recents card;
rewrapped with the final defaults and verified again. T1 16/16, T2 53
tests. Test report, design Section 18, progress, decisions updated.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```
