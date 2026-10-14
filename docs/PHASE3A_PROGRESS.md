# Phase 3A progress

Working log for Claude Code sessions. Updated after each milestone. Newest state first.
Brief: `PHASE3A_BRIEF.md` (repository root, supplied by the developer).

## Current state (2026-10-14, end of the session)

Milestones A to D done on Windows and the emulator: T1 16/16 Debug and Release, T2 51
tests (0 failures, 1 skipped without a PIN; passed in the PIN run), host-driven lock, kill,
clock, manifest, and leak checks, lock-bound mode with an emulator PIN. Milestone E: docs
and `docs/PHASE3A_TEST_REPORT.md` written; T3 on the Xiaomi and the two phone measurements
did not run (the phone was attached for part of the session and gone when the final APK
was ready; `adb install` returned `device not found`). Nothing is committed; the suggested
commit message is at the end of the test report. The emulator was stopped.

Open for the developer:

1. Attach the Xiaomi and tap the installs: `build/p3a/phone-p3a.sh install-debug`, then
   `bench` (fills design Section 3.3 and settles CP-2), `migrate <throwaway pin>` (the
   52-object vault, verify all), the remaining T3 steps in the test report, then
   `install-benchmark` for the benchmark unlock time and the lock-screen Diagnostics button.
2. CP-2 (default Argon2id parameters, provisional ops 3 / 64 MiB) after that run.

## Checkpoint (2026-10-14)

Approved by the developer with four changes, applied to `docs/PHASE3A_DESIGN.md` (status
line names them), `docs/DECISIONS.md` (D-016 to D-019 marked approved, D-019 amended), and
`docs/THREAT_MODEL.md`: the attempt is recorded before the stretch and reset only on a
successful unwrap, with a T2 test for a kill during the unlock; scripted actions enter
through a debug-only exported `ScriptActivity`; the same-boot rule is worded as the larger
of two remaining-time estimates; `pv_vault_create_envelope3` creates the vault with the VMK
inside the core. CP-2 stays provisional until the Xiaomi run.

## Milestone C log (2026-10-14)

Core (all additive, `src/vault_core`): `include/pv/pv.h` gains error code 24
(`PV_ERR_ENVELOPE_NO_SLOT`), the `PV_PIN_*` defaults and bounds, the envelope 3 constants and
structs, `pv_pin_salt_generate`, `pv_pin_delay_ms`, `pv_pin_remaining_ms`,
`pv_envelope3_parse`, `pv_envelope3_seal`, `pv_envelope3_open`,
`pv_vault_create_envelope3`, `pv_vault_open_envelope3`, `pv_vault_write_envelope3`,
`pv_vault_destroy_files`. New `src/envelope3.cpp` (layout, masked additional data, vault
forms, destroy), `src/pin.cpp` extended (salt, delay arithmetic), `src/vault_internal.h`
(the handle struct and `vault_open_raw`, shared with `vault.cpp`), `fs.h`/`fs.cpp` gain
`rename_replace` and `remove_dir`. No existing function changed.

Tests: `tests/core/test_pin_stretch.cpp`, `tests/core/test_envelope3.cpp`,
`tests/core/envelope3_builder.h` (hand-built envelopes for the slot cases and the vector
generator), `test_golden.cpp` reads `envelope3.txt`; `tests/tools/make_vectors.cpp`
`--envelope3` mode. Fixtures: `tests/fixtures/v1/envelope3_single.key` (149 bytes),
`envelope3_unknown_slot.key` (177 bytes), `envelope3.txt`; README updated.

```
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --envelope3   # once
ctest --preset windows-x64-tests-debug            # 16/16 passed, 35 s
cmake --build --preset windows-x64-tests-release
ctest --preset windows-x64-tests-release          # 16/16 passed, 15 s
```

No compiler warnings at `/W4` in either configuration. One test defect fixed on the way:
the test tried a 65-byte alias through a 65-byte `char` field, which cannot hold it; the
check became "a 64-byte alias is accepted" (the 65-byte case is covered by the hand-built
envelope).

## Milestone D log (2026-10-14)

Android: `KeystoreHmacProvider` (now the `DeviceBoundKey` implementation, fresh aliases,
alias listing), `PinEnvelope` (parse wrapper, provider sniff), `AttemptPolicy` (counter and
clocks in preferences, injectable clock), `LockController` (state machine, unlock, setup,
migration, PIN change, rewrap with test hooks, alias sweep, lock with drain, erase with the
pending flag), `PinPad` and `PinBuffer`, `LockActivity` (launcher, keypad, setup, change,
erase, countdown, diagnostics), `Diagnostics` (the Milestone A measurements, callable from
the lock screen in every build), `MainActivity` (vault screen, not exported, routes to the
lock screen, Lock, Change PIN, Erase buttons), `CaptureActivity` (routes to the lock screen,
Lock button), `CaptureSession` (`drain`, `awaitIdle`, `resume`, `CANCELLED`), `PvApp`
(controller, `ProcessLifecycleOwner` observer, screen-off receiver), `Vault.fromHandle`,
`VaultSession.openOrCreate` removed. JNI: the cancel flag in every variant, parse, create,
open, write, destroy, salt, delay entry points. Debug source set: `ScriptActivity` and
`app/src/debug/AndroidManifest.xml`. Main manifest: `LockActivity` exported launcher,
`MainActivity` not exported. Gradle: `lifecycle-process` 2.6.2, `androidx.test:core` 1.6.1,
version 0.3.0. New instrumented classes: `PinTest`, `Envelope3Test`, `KeystoreHmacTest`,
`LockTest`, `LockScreenTest`.

```
cd android
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark   # BUILD SUCCESSFUL; lint 0 errors, 51 warnings (UseKtx, GradleDependency, ApplySharedPref for the deliberate commit, ViewConstructor, OldTargetApi, MissingApplicationIcon, KtxExtensionAvailable)
$env:ANDROID_SERIAL = "emulator-5554"
gradlew.bat connectedDebugAndroidTest    # 51 tests, 0 failures, 0 errors, 1 skipped (LockBoundKeyTest, no emulator PIN), 122 s
```

First T2 run had 11 failures, all fixed before the second run: the JNI cancel flag stayed
set after a lock and made every later encrypt fail (now cleared once the queue is idle),
an empty byte array reached the parser as a null pointer (now an empty envelope), and two
test expectations were wrong (the remaining delay is read after the stretch; the
clock-back case keeps the larger estimate). Logged by the run: unlock 1.3 to 1.8 s (debug
build, emulator, ops 3 / 64 MiB); migration interrupted at each of the four points leaves
the old or the new envelope; lock during a slowed encrypt took 242 ms and reported
`CANCELLED`; Keystore HMAC key `SOFTWARE`, not lock-bound. Logcat of the run (286 lines):
0 hex runs over 32 characters, 0 occurrences of the test PINs.

Host-driven steps on the emulator (`build/p3a/host-t2.sh`, `host-bg.sh`, `emu-pin-p3a.sh`;
outputs `build/p3a/H*.txt`, `L-*.txt`, `H9-*.png`, not committed). Two defects found and
fixed on the way: `ScriptActivity` used `Theme.NoDisplay`, which requires `finish()` before
resume and crashed every scripted run (now a translucent theme); and the background
trigger never fired because `ProcessLifecycleOwner` is attached through the
`androidx.startup` provider that D-013 removes (D-020: `PvApp` counts started activities,
`lifecycle-process` removed). Results after the fixes:

| Step | Result |
|---|---|
| Setup through `ScriptActivity`, `am kill`, relaunch | `LockActivity` resumed, vault screen absent |
| Scripted actions without `unlock_pin` first | refused with a log line |
| `am kill` 2 s into a 4 s delayed wrong unlock, then four scripted wrong attempts | the fifth reports `attempts 5` and a 28 s wait: the killed attempt counted |
| Wall clock moved two hours forward during the delay | still `wait 25 s`; after the delay the right PIN unlocks |
| `KEYCODE_SLEEP` | `locking (screen off)`, `locked`, lock screen on wake |
| Idle (timeout set to 5 s by script) | `locking (idle)`, `locked`, lock screen in front |
| `KEYCODE_HOME` | `locking (background)`, `locked` |
| Rotation (`user_rotation` 1 then 0) while unlocked | no lock, vault screen stays |
| Home during a capture slowed to 1.5 s per read | `locking (background)`, `capture failed: CANCELLED`, `locked`; `objects/` empty; after the next unlock `list` shows no partial |
| Lock screen `screencap` and recents card | black apart from the status bar; recents card black |
| Merged manifests (`aapt2`) | debug: `ScriptActivity` and `LockActivity` exported, `MainActivity` and `CaptureActivity` not; benchmark: only `LockActivity` exported; `CAMERA` the only permission in both; version 0.3.0 |
| `leak_scan` after the run | 0 findings |
| Logcat of the host run (113 lines) | 0 test PIN strings, 0 hex runs over 32 characters; the 10 `k_pin`/`kek` hits are the constant names in bounds messages, no values |
| Emulator PIN (`locksettings set-pin`): `LockBoundKeyTest`, provider 3 setup, locked-screen unlock, unlocked retry | `OK (1 test)`; `vault created, lock-bound`; while locked `keystore InvalidKeyException` (attempt counted, 1); after unlocking the device the PIN opens the vault; PIN and app data cleared afterwards |

Unlock time on the emulator debug build through the script path: 2.0 to 2.4 s (stretch
at ops 3 / 64 MiB about 1.2 s unoptimised, plus Keystore, file reads, and the alias sweep).

## Milestone A log (2026-10-14)

1. `git status` on `main` at `9b7e7ad`: no tracked file modified; three untracked files
   supplied by the developer (`PHASE3A_BRIEF.md`, `docs/PHASE1_SUMMARY.md`,
   `docs/PHASE2_SUMMARY.md`). Treated as clean and continued.
2. T1 on the unchanged tree (`VCPKG_ROOT`, `VCPKG_DEFAULT_BINARY_CACHE` set; logs under
   `build/p3a/t1-*.log`, not committed):

   ```
   cmake --preset windows-x64-tests
   cmake --build --preset windows-x64-tests-debug
   ctest --preset windows-x64-tests-debug            # 14/14 passed, 55 s
   cmake --build --preset windows-x64-tests-release
   ctest --preset windows-x64-tests-release          # 14/14 passed, 16 s
   ```

3. Measurement hooks added (kept for Milestone C, names as proposed in the design):
   `pv_pin_stretch` with the `PV_PIN_*` bounds in `pv.h` and `src/vault_core/src/pin.cpp`;
   JNI `nativePinStretch`; `KeystoreHmacProvider.kt` (HMAC-SHA256 key create, MAC, level,
   delete); scripted actions `kdf_bench` and `hmac_probe` in `MainActivity`. T1 rerun with
   `pin.cpp` in the build: Debug 14/14 (40 s), Release 14/14 (16 s), no warnings at `/W4`
   (`build/p3a/t1b-*.log`).
4. Android build and T2 (emulator `Pixel_8_API_35` booted headless; `ANDROID_SERIAL`
   pinned; `ANDROID_NDK_HOME` set to `D:/Android/Sdk/ndk/29.0.14206865` for the shell):

   ```
   cd android
   gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark   # BUILD SUCCESSFUL in 2m 20s; lint 0 errors, 11 warnings (same set as Phase 2)
   gradlew.bat connectedDebugAndroidTest    # 31 tests, 0 failures, 0 errors, 1 skipped (LockBoundKeyTest, no emulator PIN), 52 s
   ```

5. Measurements on the emulator (`build/p3a/bench.sh`, outputs `build/p3a/A-debug.txt` and
   `A-benchmark.txt`): `kdf_bench` for the six pairs in the INTERACTIVE to MODERATE box and
   `hmac_probe`. Figures in `docs/PHASE3A_DESIGN.md` Section 3.3. Bounds check confirmed:
   ops 1 and 512 MiB give `PV_ERR_ENVELOPE_KDF_PARAMS`. The debug APK was reinstalled on
   the emulator afterwards.
6. Xiaomi: `adb devices` shows no phone during this session. Remaining Milestone A steps,
   each install needs the developer's tap on the phone:

   ```
   adb -s 9c4424ba81c6 install -r android\app\build\outputs\apk\debug\app-debug.apk
   adb -s 9c4424ba81c6 shell am force-stop com.privatevault.app
   adb -s 9c4424ba81c6 shell am start -n com.privatevault.app/.MainActivity --es actions kdf_bench,hmac_probe
   adb -s 9c4424ba81c6 logcat -d -s PrivateVault:I
   adb -s 9c4424ba81c6 install -r android\app\build\outputs\apk\benchmark\app-benchmark.apk
   (same start and logcat)
   adb -s 9c4424ba81c6 install -r android\app\build\outputs\apk\debug\app-debug.apk
   ```

   The actions touch neither the phone vault nor its provider 2 key: `kdf_bench` uses a
   fixed throwaway PIN and a zero salt, and `hmac_probe` creates and deletes the alias
   `privatevault.pin.probe`.

## Milestone B log (2026-10-14)

`docs/PHASE3A_DESIGN.md` written: envelope 3 byte layout with slots and the additional data
rule, PIN stretching and bounds with the emulator figures, unlock, setup, rewrap, migration,
alias sweep, lock state machine and triggers, lock screen, platform interface for the
device-bound step, attempt policy arithmetic, JNI entry points and memory handling, erase
order with the pending flag, C ABI additions, dependencies, failure table, checkpoint items
CP-1 to CP-9, seams, test plan, threat model deltas. `docs/DECISIONS.md` D-016 to D-019
(proposed). `docs/THREAT_MODEL.md` Section 7 (proposed). Checkpoint report sent to the
developer. Milestone C waits for approval.

## Toolchain inventory delta (2026-10-14)

Same machine as Phase 2 (`docs/PHASE2_PROGRESS.md`). `ANDROID_NDK_HOME` was not set in the
session shell and is exported by the helper scripts under `build/p3a/`. Emulator started
headless for T2 and the measurements and stopped at the end of Milestone B. No SDK
component installed or changed. Drive C at 86 percent; all output on D.
