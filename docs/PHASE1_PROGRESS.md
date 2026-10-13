# Phase 1 progress

Working log for Claude Code sessions. Updated after each milestone. Newest state first.

## Current state (2026-10-13)

Phase 1 partially complete. Milestones A, B, C, and E done; Milestone D done except T3 on
the physical device, which is Not verified (MIUI blocks `adb install`; the same scripted
procedure passed on the emulator). Full results in `docs/PHASE1_TEST_REPORT.md`. The
working tree is uncommitted; a commit message is suggested at the end of the report.

To resume: enable "Install via USB" on the phone, run the T3 commands from `README.md`, and
add the device results (Keystore security level, timings) to the test report.

## Resumed (2026-10-13)

The session that implemented Milestones C and D was interrupted during the T3 device steps.
State found on resume: working tree uncommitted (`git log` still shows only the two original
commits; `git status` lists `README.md` modified and the scaffold, `src/`, `tests/`,
`android/`, and `docs/` untracked), handoff and start prompt unchanged (they still name
`D:\repository\PrivateVault`; the actual directory `D:\repo\PrivateVault` is used).
Nothing was discarded or redone.

Status of the eight approval changes, checked against the files on disk:

1. Short reads: applied. `pv::fill()` in `src/vault_core/src/object.cpp` loops until the
   buffer is full or a read returns zero; `probe_more()` does the same for the post-FINAL
   byte. `tests/core/test_support.h` has the 1 to 7 byte source (`cycle_short`), used by
   `test_roundtrip` (encrypt and decrypt) and by every case in `test_negative`.
2. Deterministic source: applied. ChaCha20-IETF keystream with
   `crypto_stream_chacha20_ietf_xor_ic`, zero nonce, running block counter
   (`src/vault_core/src/streams.cpp`), specified in `docs/PHASE1_DESIGN.md` Section 13,
   `docs/DEPENDENCIES.md` updated; `test_deterministic` checks read sizes 1, 7, 4096, 1 MiB
   and the whole-stream libsodium call. Golden vectors were generated with it.
3. Android rename: applied and verified. `link()` is denied in `filesDir` on the emulator
   (errno 13); the core falls back to an existence check plus `rename()`; D-008 and the
   design Section 7 document the race.
4. UTF-8 paths with wide APIs on Windows: applied in `src/vault_core/src/fs.cpp`;
   `test_vault` runs the whole vault scenario in a directory named with Latin, Japanese,
   and emoji characters.
5. Canonical final chunk: applied (writer emits `n mod chunk_size`, reader accepts 0 to
   `chunk_size`); Section 3.2 states it; `test_negative` checks a 2-chunk object ends with an
   empty FINAL and that a full-size FINAL is accepted.
6. `.pvtmp` collision: applied (`vault_decrypt_to_file` uses an exclusive create, so an
   existing temp file gives `PV_ERR_DEST_EXISTS` without touching it); covered in `test_vault`.
7. No aborts: applied. `pv::ensure_init()` returns `PV_ERR_INIT`; `docs/THREAT_MODEL.md`
   Section 5 says so.
8. Progress log remark about `D:\repository`: removed.

Also found during Milestone C and recorded: D-007 (trailing data after a short final chunk is
reported as `PV_ERR_AUTH_FAILED`, still fail-closed).

Incomplete at the interruption: T3 on the physical device. The debug APK install returned
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` (MIUI asks on the phone to allow
USB installs). Retried on resume; see the T3 section below.

Milestone A results:

- Windows: `windows-x64-tests` preset configures with MSVC 19.51, builds Debug and Release,
  `ctest` passes 1/1, `pv version` prints `pv 0.1.0 (libsodium 1.0.22)`.
- Android core: `android-arm64` and `android-x64` presets build `libprivatevault_jni.so`
  with libsodium linked statically (`sodium_init` resolved, no undefined `sodium_*` symbols),
  LOAD segments aligned to 0x4000 (16 KB).
- Android app: `gradlew assembleDebug` produces `app-debug.apk` (3.4 MB) with
  `arm64-v8a` and `x86_64`; merged manifest has no `uses-permission`, `allowBackup=false`,
  `fullBackupContent` and `dataExtractionRules` set; `zipalign -c -P 16` verification
  successful; the packaged `.so` exports only the three `Java_*` symbols.
- Emulator: `Pixel_8_API_35` boots headless (Android 15, API 35, x86_64, `release-keys`,
  adb shell runs as uid 2000, not root). `gradlew connectedDebugAndroidTest` runs
  `NativeInitTest` and passes 1/1. `adb install` plus `am start` brings `MainActivity` to
  `topResumedActivity` with no `AndroidRuntime` error; `run-as com.privatevault.app` works
  (needed for the Android-to-Windows vector check in Milestone E).
- Dependency route: option 1 (vcpkg manifest mode) works for all three triplets. No WSL used.
- Side effect to note: the first `gradlew assembleDebug` made AGP 8.8.2 install
  `build-tools/35.0.0` into `D:\Android\Sdk` automatically (its required version; the SDK
  only had 37.0.0). This was an SDK change not asked for in advance; it is recorded here and
  in D-004.

## Repository

- Working directory: `D:\repo\PrivateVault`.
- Git: branch `main`, clean tree at start, two commits (`6694b0e Initial commit`,
  `4ecdc3c Add Phase 1 handoff and start prompt`). Contents at start: `README.md`,
  `PROJECT_HANDOFF.md`, `CLAUDE_CODE_START_PROMPT.md`.
- Drive C is close to full. All caches, downloads, and build output stay on drive D.

## Toolchain inventory (2026-10-12)

| Tool | Found | Notes |
|---|---|---|
| OS | Windows 11 Home 10.0.26200 | |
| CPU | Intel Core i7-10750H | |
| Visual Studio | Community 2026, 18.10.3 (18.10.12224.181) at `C:\Program Files\Microsoft Visual Studio\18\Community` | found with `vswhere` |
| MSVC toolsets | 14.51.36231 (VS 2026), 14.29.30133 (VS 2019 toolset) | |
| CMake | 4.4.4 on PATH (`C:\Program Files\CMake`); VS bundles its own; SDK has 4.1.2 | |
| Ninja | not on PATH; VS bundles one; SDK CMake 4.1.2 bundles `ninja.exe` | |
| vcpkg | tool 2026-07-27 bundled with VS; separate clone at `D:\tools\vcpkg` at tag 2026.07.29 | see D-001 |
| Android SDK | `D:\Android\Sdk` (`ANDROID_HOME`) | |
| Platforms | android-35, android-36 | |
| Build-tools | 37.0.0 | |
| Platform-tools | 37.0.1 (adb 1.0.41) | |
| NDK | 29.0.14206865 (r29, clang 21.0.0) installed by the developer on 2026-10-13. Earlier: 28.0.12433566 (r28 beta 1, since removed) and an incomplete 30.0.16248370 directory (only `.installer`, deleted 2026-10-13) | see D-003 |
| SDK CMake | 4.1.2 | |
| Emulator | 37.2.12; WHPX reported usable (`emulator -accel-check`) | |
| AVD | `Pixel_8_API_35` (x86_64, API 35 Google Play image) at `D:\Android\avd` | |
| Devices | none (`adb devices` empty) | T3 not possible for now |
| JDK | OpenJDK 21.0.4 (Android Studio JBR at `D:\Android\Android Studio1\jbr`, `JAVA_HOME`) | |
| Android Studio | build AI-242.23726.103 (Ladybug Feature Drop 2024.2.2) | supports AGP up to 8.8 |
| Gradle | `GRADLE_USER_HOME=D:\Android\gradle`; only a partial 8.10.2 download present, no cached AGP | first Gradle run downloads |
| Git | 2.55.0.windows.5 | |
| Python | 3.13.1 | |
| WSL | WSL2 with Ubuntu-24.04 installed (stopped) | available as fallback for libsodium dist-build |
| libsodium | 1.0.22 released 2026-04-09, port 1.0.22#1 in vcpkg baseline | |

## Milestone A log

Commands run so far (all from `D:\repo\PrivateVault` unless noted):

```
git status; git log --oneline -5
vswhere -latest -products * -format text
cmake --version
sdkmanager --list_installed --sdk_root=D:\Android\Sdk
adb devices -l
emulator -list-avds
emulator -accel-check
wsl --status
git clone https://github.com/microsoft/vcpkg.git D:\tools\vcpkg
git -C D:\tools\vcpkg checkout 2026.07.29
D:\tools\vcpkg\bootstrap-vcpkg.bat -disableMetrics
```

Dependency spike, route 1 (vcpkg manifest mode), run 2026-10-12:

```
vcpkg install libsodium:x64-windows-static libsodium:arm64-android libsodium:x64-android --clean-after-build
```

with `VCPKG_DEFAULT_BINARY_CACHE=D:\tools\vcpkg-cache` and (at that time)
`ANDROID_NDK_HOME=D:\Android\Sdk\ndk\28.0.12433566`. Result: all three packages built and
were stored in the binary cache in 15 minutes. The Windows package used the MSBuild project
in the libsodium tree; the Android packages used the autotools path through vcpkg's bundled
msys2, with no WSL involved. Output checked: `arm64-android/lib/libsodium.a` is ELF64
AArch64, `version.h` reports 1.0.22.

NDK change, 2026-10-13: the developer replaced NDK r28 beta 1 with r29 (`29.0.14206865`).
The two Android packages were removed and rebuilt:

```
vcpkg remove libsodium:arm64-android libsodium:x64-android
vcpkg install libsodium:arm64-android libsodium:x64-android --clean-after-build
```

with `ANDROID_NDK_HOME=D:\Android\Sdk\ndk\29.0.14206865`. Result: see below.

Windows scaffold, 2026-10-13 (`VCPKG_ROOT=D:\tools\vcpkg`):

```
cmake --preset windows-x64-tests            # configure: vcpkg install from cache in 1 s, MSVC 19.51.36260 detected
cmake --build --preset windows-x64-tests-debug
ctest --preset windows-x64-tests-debug       # 1/1 passed (test_init)
build\windows-x64-tests\src\desktop_cli\Debug\pv.exe version   # prints: pv 0.1.0 (libsodium 1.0.22)
```

Android core presets and app, 2026-10-13 (`VCPKG_ROOT`, `VCPKG_DEFAULT_BINARY_CACHE`,
`ANDROID_HOME=D:\Android\Sdk`, `ANDROID_NDK_HOME=D:\Android\Sdk\ndk\29.0.14206865` set):

```
cmake --preset android-arm64 && cmake --build --preset android-arm64     # Clang 21.0.0, libsodium restored from cache
cmake --preset android-x64   && cmake --build --preset android-x64
llvm-readelf -l build\android-arm64\android\app\src\main\cpp\libprivatevault_jni.so   # LOAD align 0x4000
llvm-nm -D --defined-only ...libprivatevault_jni.so                                   # only Java_* symbols
cd android
gradlew.bat --version                      # Gradle 8.10.2 on JBR 21.0.4
gradlew.bat help                           # resolves AGP 8.8.2, Kotlin 2.1.10
gradlew.bat assembleDebug                  # BUILD SUCCESSFUL in 1m 21s
aapt2 dump permissions app\build\outputs\apk\debug\app-debug.apk    # package only, no permissions
aapt2 dump xmltree --file AndroidManifest.xml ...app-debug.apk      # allowBackup=false, backup rules present
zipalign -c -P 16 -v 4 ...app-debug.apk                             # Verification successful
emulator -avd Pixel_8_API_35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
adb -e shell getprop sys.boot_completed    # 1
gradlew.bat connectedDebugAndroidTest      # 1 test, 0 failures (NativeInitTest)
adb -e install -r ...app-debug.apk; adb -e shell am start -W -n com.privatevault.app/.MainActivity   # Status: ok
adb -e shell run-as com.privatevault.app id                                                         # uid u0_a211
adb -e uninstall com.privatevault.app
```

## Milestone C log (2026-10-13)

Implemented: core (`src/vault_core`: format, object codec, vault directory logic, platform
file layer, streams, deterministic source, hashing sink), desktop envelope library and `pv`
tool (`src/desktop_cli`), eleven host tests, golden vector generator, eight committed vectors.

```
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1     # once
ctest --preset windows-x64-tests-debug          # 11/11 passed, 28 s (large label 19 s)
cmake --build --preset windows-x64-tests-release
ctest --preset windows-x64-tests-release        # 11/11 passed
cmake --preset windows-x64 && cmake --build --preset windows-x64-release   # production build, no test hooks (10 tests registered, --test-vmk-file rejected as usage)
```

Measured in Release: 256 MB encrypt 4.9 s, decrypt plus hash 1.6 s; peak working set 4.9 MB
(1 MB input) versus 4.5 MB (256 MB input) for encrypt, 4.5 MB versus 4.5 MB for decrypt.
No compiler warnings at `/W4`.

## Milestone D log (2026-10-13)

Implemented: JNI bridge (`android/app/src/main/cpp/jni_bridge.cpp`), `NativeBridge`,
`KeystoreKeyProvider` (AES-256-GCM, randomized IV, `setUnlockedDeviceRequired(true)`,
StrongBox attempted with fallback), `Vault`, `MainActivity` with buttons and a scripted
`actions` intent extra, four instrumented test classes (15 tests) with the golden vectors as
test assets.

```
cd android
gradlew.bat assembleDebug assembleDebugAndroidTest      # BUILD SUCCESSFUL
$env:ANDROID_SERIAL = "emulator-5554"
gradlew.bat connectedDebugAndroidTest                   # 15 tests, 0 failures, 0 errors on Pixel_8_API_35 (Android 15)
```

Emulator findings: Keystore security level `SOFTWARE`; `link()` in `filesDir` denied with
errno 13 (D-008). Physical device: Xiaomi 2201116SG (veux), Android 13, API 33, security
patch 2024-10-01, `arm64-v8a`, `release-keys`, verified boot `green`, no `su`, no StrongBox
feature. `adb install` of the debug APK fails with `INSTALL_FAILED_USER_RESTRICTED: Install
canceled by user` (MIUI 14, V816.0.9.0.TKCMIXM) on two attempts; see the T3 section of
`docs/PHASE1_TEST_REPORT.md` for the blocker and the remaining steps.

## Milestone E log (2026-10-13)

- Android-to-Windows check: three vectors made on the emulator by
  `GoldenVectorTest.exportVectorForWindows`, pulled with `adb exec-out run-as ... cat` from
  Git Bash (PowerShell redirection corrupts binary output), decrypted by
  `test_golden.exe build\android-export`: all three ok, hashes equal to the Windows vectors
  of the same length.
- Scripted T3 procedure on the emulator (install, create, encrypt 1 MB and 100 MB, verify,
  force-stop, relaunch, verify, `adb reboot`, verify, tamper, truncation, wrong key, security
  level): all steps passed; see the test report. Not a device result.
- Final T1 run after the last code change: Debug 11/11 (30 s).
- `git status` reviewed: no build output, no secrets, no large files; the only binary is the
  Gradle wrapper jar. Fixture objects total 42 KB.
- README, design (Section 9 now points at `pv.h`), decisions (D-007 to D-009), and this log
  updated; `docs/PHASE1_TEST_REPORT.md` written.

## T3 on the physical device (2026-10-13)

Device: Xiaomi 2201116SG (veux), Android 13, MIUI V816.0.9.0.TKCMIXM, connected and
authorized (`adb devices` shows `device`). `adb install -r app-debug.apk` returned
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` twice. MIUI requires "Install via
USB" in Developer options (which asks for a Mi account) and an on-screen confirmation;
neither was done because device settings were out of bounds. No other APK was installed and
nothing else was changed on the phone. Steps to finish are in `README.md` and the test report.

## Milestone B log

Documents written 2026-10-12 and 2026-10-13: `docs/PHASE1_DESIGN.md`, `docs/THREAT_MODEL.md`,
`docs/DEPENDENCIES.md`, `docs/CONTAINER_FEASIBILITY.md`, `docs/DECISIONS.md`, `docs/ROADMAP.md`.
Reference pages were read on 2026-10-12 (libsodium docs, Android Keystore, Auto Backup,
16 KB page sizes, AGP release notes, VeraCrypt license). Checkpoint report sent to the
developer; Milestone C waits for approval.

## Open items

- Developer decision at the Milestone B checkpoint (object and envelope layouts, key
  hierarchy, C ABI, minSdk 28, seeking recommendation).
- Physical Android device for T3 (none connected).
