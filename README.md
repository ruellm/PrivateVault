# PrivateVault

R&D for a decentralized, privately stored media vault. Phase 1 built the encrypted storage
core (C++20, libsodium), a Windows command line tool, and an Android proof of concept that
share one object format. Phase 2 adds in-app photo capture straight into encrypted objects,
a plaintext exposure scan, and a video feasibility prototype.

This is a research prototype. It has not been audited and must not hold real private media.

Documents:

- `PROJECT_HANDOFF.md`: the project brief and Phase 1 specification; `PHASE2_BRIEF.md` (developer supplied) for Phase 2.
- `docs/PHASE1_DESIGN.md`: object and envelope formats, key hierarchy, C ABI.
- `docs/PHASE2_DESIGN.md`: capture pipeline, media item header, ABI additions, leak scan, video options.
- `docs/VIDEO_CAPTURE_FEASIBILITY.md`: what was tried for video, measurements, recommendation.
- `docs/THREAT_MODEL.md`: what the prototype defends against and what it does not.
- `docs/DEPENDENCIES.md`: pinned versions, sources, licenses, verification.
- `docs/DECISIONS.md`: engineering choices with alternatives.
- `docs/CONTAINER_FEASIBILITY.md`: why TrueCrypt-style containers are not the foundation.
- `docs/PHASE1_PROGRESS.md`, `docs/PHASE2_PROGRESS.md`: state of the work and commands run.
- `docs/PHASE1_TEST_REPORT.md`, `docs/PHASE2_TEST_REPORT.md`: results per test tier.

## Layout

```
CMakeLists.txt, CMakePresets.json, vcpkg.json   shared build description
src/vault_core/      portable core: include/pv/pv.h is the C ABI
src/desktop_cli/     pv tool, password prompt, Argon2id key envelope
tests/core/          host tests (CTest), tests/tools/make_vectors.cpp
tests/fixtures/v1/   committed golden vectors and the TEST ONLY key
android/             Gradle project: Kotlin app, Keystore envelope, JNI bridge
docs/                design, threat model, dependencies, decisions, reports
```

## Prerequisites (Windows)

- Visual Studio 2022 17.x or later with the C++ desktop workload (developed with
  Visual Studio Community 2026 18.10, MSVC 14.51).
- CMake 3.28 or later (developed with 4.4.4) and the Visual Studio generator.
- A vcpkg checkout. The baseline commit is pinned in `vcpkg.json`:

  ```
  git clone https://github.com/microsoft/vcpkg.git D:\tools\vcpkg
  git -C D:\tools\vcpkg checkout 2026.07.29
  D:\tools\vcpkg\bootstrap-vcpkg.bat -disableMetrics
  ```

  Set these before building so caches stay off drive C:

  ```
  $env:VCPKG_ROOT = "D:\tools\vcpkg"
  $env:VCPKG_DEFAULT_BINARY_CACHE = "D:\tools\vcpkg-cache"   # create the folder first
  ```

  The first configure builds libsodium 1.0.22 from the pinned source (about 2 minutes for
  Windows, about 7 minutes per Android ABI) and stores it in the binary cache.

- For Android: Android SDK with platforms 35 and 36, build-tools 35.0.0, NDK r29
  (`29.0.14206865`), SDK CMake 4.1.2, and a JDK 17 or later. Set `ANDROID_HOME`,
  `ANDROID_NDK_HOME`, and `JAVA_HOME`. Android Studio Meerkat (2024.3.1) or later opens the
  project (AGP 8.9).

## Build and test on Windows (T1)

```
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug
ctest --preset windows-x64-tests-debug
```

Release: `cmake --build --preset windows-x64-tests-release` and
`ctest --preset windows-x64-tests-release`. The `windows-x64-tests` preset compiles the
test-only CLI options (`--test-vmk-file`, `--kdf interactive`); use `windows-x64` for a build
without them. Large-file tests (256 MB round trip, bounded memory) carry the CTest label
`large`; `ctest --preset windows-x64-tests-release-nolarge` skips them.

Tests run in `build/<preset>/testwork/<test>/` and clean up after themselves. The golden
vectors in `tests/fixtures/v1` are committed; regenerate only on purpose with
`build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1`.

## The pv tool

```
pv init <vault_dir>                          create a vault; asks for a password twice
pv encrypt <vault_dir> <file> [--chunk-size N]   prints the new object id
pv decrypt <vault_dir> <object_id> <out_file>    writes <out_file>.pvtmp, renames on success
pv verify <vault_dir> <object_id>                decrypts into a hash, prints the length and the item fields
pv inspect <vault_dir> <object_id>               header fields, no password needed
pv inspect-file <path.pvo>
pv version
```

Passwords are read from a no-echo console prompt or, for scripts, from standard input with
`--password-stdin`. They are never command line arguments. Exit codes: 0 success, 1 usage,
2 failure (the error name is printed), 3 password mismatch or empty password.

## Android core library without Gradle

```
cmake --preset android-arm64 && cmake --build --preset android-arm64
cmake --preset android-x64   && cmake --build --preset android-x64
```

## Android app (T2, T3)

Set `VCPKG_ROOT`, `ANDROID_HOME`, and `JAVA_HOME`. The Gradle wrapper downloads Gradle
8.11.1 (checksum pinned) into `GRADLE_USER_HOME`; AGP 8.9.3 installs `build-tools/35.0.0`
into the SDK if it is missing.

```
cd android
gradlew.bat assembleDebug                 # app\build\outputs\apk\debug\app-debug.apk
gradlew.bat lintDebug                     # RestrictedApi is an error
gradlew.bat connectedDebugAndroidTest     # T2: needs a booted emulator or a connected device
gradlew.bat assembleBenchmark             # non-debuggable, optimized native code, debug signing key
```

The app asks for `CAMERA` at runtime on the capture screen and declares no other
permission. The benchmark variant has no `run-as` access and no test hooks; its results
come from logcat.

To keep the test APK off a phone that is also connected, pin the target:
`$env:ANDROID_SERIAL = "emulator-5554"`.

Headless emulator used for T2 (AVD `Pixel_8_API_35`, x86_64, API 35):

```
emulator -avd Pixel_8_API_35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
adb -e shell getprop sys.boot_completed   # wait for 1
```

Since Phase 3A the app starts on a lock screen (`LockActivity`, the only exported activity
in the benchmark and release variants). A new install asks for a 6 to 12 digit PIN; an
existing Phase 1 or 2 vault asks for a PIN once and is rewrapped in place. The vault locks
on background, screen off, 5 minutes idle, and the Lock button; a process restart always
starts locked. Five wrong PINs start a 30 s delay that doubles per attempt (cap 1 h).
"Forgot PIN: erase vault" on the lock screen deletes the keys and every object after the
word `ERASE` is typed. The lock screen also has a Diagnostics button (Argon2id timing and
the Keystore HMAC probe) that needs no vault, so the benchmark build can be measured on a
phone by hand. Design: `docs/PHASE3A_DESIGN.md`.

T3 steps on a device are scripted through the `actions` extra of the debug-only
`ScriptActivity`, which must unlock first: the list starts with `unlock_pin=<pin>` or
`setup_pin=<pin>` (a throwaway test PIN; never a real one). Before that only
`set_idle_ms=<ms>`, `unlock_delay_ms=<ms>`, `kdf_bench`, and `hmac_probe` are accepted.
Results appear in logcat under the tag `PrivateVault` and on the vault screen. Force-stop
the app before each launch. Capture actions (`capture_photo`,
`capture_photo_n=<n>`, `flip`, `memory`, `video_probe`, `v2_probe`, `test_hook_slow=<ms>`,
`test_hook_clear`) hand the rest of the list to the capture screen, which needs the camera
permission granted (`adb shell pm grant com.privatevault.app android.permission.CAMERA` on a
test device, or the on-screen dialog). Other actions: `open`, `encrypt_1mb`, `encrypt_100mb`,
`verify_all` (alias `verify`), `list`, `leak_scan`, `tamper`, `truncation`, `wrong_key`,
`security_level`, `close`:

```
adb install app-debug.apk
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions setup_pin=246810,open,security_level,encrypt_1mb,verify   # first PIN (new vault or migration)
adb shell am force-stop com.privatevault.app
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=246810,open,verify
adb reboot          # unlock the device afterwards if it has a screen lock
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=246810,open,verify
adb shell am force-stop com.privatevault.app
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=246810,tamper,truncation,wrong_key,security_level
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=246810,capture_photo_n=10,verify_all,list,leak_scan
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=246810,video_probe,leak_scan     # debug build only
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions kdf_bench,hmac_probe        # no vault needed
adb logcat -d -s PrivateVault:I
```

Lock-bound key check on the emulator (never on a phone with personal data): set a
temporary PIN with `adb shell locksettings set-pin <pin>`, remove the app vault
(`adb shell run-as com.privatevault.app rm -r files/vault`), run `open,encrypt_1mb,verify_all`
and read `key mode: lock-bound`, lock the screen with `adb shell input keyevent KEYCODE_SLEEP`,
launch `open,verify_all` again and expect `[open] FAIL ... Device locked`, unlock
(`KEYCODE_WAKEUP`, `KEYCODE_MENU`, `input text <pin>`, `KEYCODE_ENTER`), rerun and expect
success, then `adb shell locksettings clear --old <pin>`. The instrumented
`LockBoundKeyTest` runs only while a PIN is set.

On MIUI phones `adb install` needs "Install via USB" enabled under Developer options
(MIUI asks for a Mi account) and the on-screen confirmation accepted for every install;
otherwise it fails with `INSTALL_FAILED_USER_RESTRICTED`. On the MIUI build tested,
`adb uninstall` returns `DELETE_FAILED_INTERNAL_ERROR` and `pm clear` is refused, so
reinstall with `adb install -r` over the old app and expect the existing vault to be reused.
Without a screen lock the Keystore key is created "not lock-bound" (see
`docs/PHASE1_DESIGN.md` Section 5); the status text shows the mode.

Android-to-Windows format check (debug build, emulator or device with `run-as`):

```
adb install app-debug.apk; adb install app-debug-androidTest.apk
adb shell am instrument -w -e class com.privatevault.app.GoldenVectorTest#exportVectorForWindows com.privatevault.app.test/androidx.test.runner.AndroidJUnitRunner
# pull with a binary-safe shell (Git Bash); PowerShell redirection re-encodes the bytes
for n in $(adb shell run-as com.privatevault.app ls files/pv_export | tr -d '\r'); do adb exec-out run-as com.privatevault.app cat files/pv_export/$n > out/$n; done
build\windows-x64-tests\tests\core\Release\test_golden.exe <absolute path to out>
```

The export includes three item-wrapped vectors; `test_golden` prints `item` for them.

The app stores the vault under `filesDir/vault/` (internal app-private storage). Uninstall,
Clear Data, and factory reset destroy the Keystore key and with it the vault.
