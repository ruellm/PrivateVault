# PrivateVault

R&D for a decentralized, privately stored media vault. Phase 1 builds the encrypted storage
core (C++20, libsodium), a Windows command line tool, and an Android proof of concept that
share one object format.

This is a research prototype. It has not been audited and must not hold real private media.

Documents:

- `PROJECT_HANDOFF.md`: the brief for Phase 1.
- `docs/PHASE1_DESIGN.md`: object and envelope formats, key hierarchy, C ABI.
- `docs/THREAT_MODEL.md`: what the prototype defends against and what it does not.
- `docs/DEPENDENCIES.md`: pinned versions, sources, licenses, verification.
- `docs/DECISIONS.md`: engineering choices with alternatives.
- `docs/CONTAINER_FEASIBILITY.md`: why TrueCrypt-style containers are not the foundation.
- `docs/PHASE1_PROGRESS.md`: state of the work and commands run.
- `docs/PHASE1_TEST_REPORT.md`: results per test tier.

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

- For Android: Android SDK with platform 35, NDK r29 (`29.0.14206865`), SDK CMake 4.1.2, and
  a JDK 17 or later. Set `ANDROID_HOME`, `ANDROID_NDK_HOME`, and `JAVA_HOME`.

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
pv verify <vault_dir> <object_id>                decrypts into a hash, prints the length
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
8.10.2 (checksum pinned) into `GRADLE_USER_HOME`; AGP 8.8.2 installs `build-tools/35.0.0`
into the SDK if it is missing.

```
cd android
gradlew.bat assembleDebug                 # app\build\outputs\apk\debug\app-debug.apk
gradlew.bat connectedDebugAndroidTest     # T2: needs a booted emulator or a connected device
```

To keep the test APK off a phone that is also connected, pin the target:
`$env:ANDROID_SERIAL = "emulator-5554"`.

Headless emulator used for T2 (AVD `Pixel_8_API_35`, x86_64, API 35):

```
emulator -avd Pixel_8_API_35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
adb -e shell getprop sys.boot_completed   # wait for 1
```

T3 steps on a device, scripted through the activity's `actions` extra (debug build only).
Results appear in logcat under the tag `PrivateVault` and on screen. The extra is read in
`onCreate`, so force-stop the app before each launch:

```
adb install app-debug.apk
adb shell am start -n com.privatevault.app/.MainActivity --es actions open,security_level,encrypt_1mb,encrypt_100mb,verify
adb shell am force-stop com.privatevault.app
adb shell am start -n com.privatevault.app/.MainActivity --es actions open,verify
adb reboot          # unlock the device afterwards; the Keystore key needs an unlocked device
adb shell am start -n com.privatevault.app/.MainActivity --es actions open,verify
adb shell am force-stop com.privatevault.app
adb shell am start -n com.privatevault.app/.MainActivity --es actions open,tamper,truncation,wrong_key,security_level
adb logcat -d -s PrivateVault:I
```

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

The app stores the vault under `filesDir/vault/` (internal app-private storage). Uninstall,
Clear Data, and factory reset destroy the Keystore key and with it the vault.
