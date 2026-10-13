# Decisions

Non-obvious engineering choices, with the alternatives considered. Entries are dated.
D-001 to D-011 are Phase 1; the Phase 1 checkpoint is done and none of them is pending.
Phase 2 entries start at D-012.

## D-001 vcpkg clone on drive D instead of the Visual Studio bundled vcpkg (2026-10-12)

Choice: a dedicated clone of `microsoft/vcpkg` at `D:\tools\vcpkg`, checked out at the
release tag `2026.07.29` (commit `9e593bb18ea69cc5095e012465dcd675a822ed0d`), used in manifest
mode with that commit as `builtin-baseline`. Binary cache at `D:\tools\vcpkg-cache`.

Alternatives: the vcpkg bundled with Visual Studio 2026 (same tool version, 2026-07-27).
It stores registries, downloads, and binary cache under `%LOCALAPPDATA%` on drive C, which is
almost full, and it has no local `ports` tree to inspect. A separate clone keeps everything on
D and makes the pinned port files visible.

## D-002 Test framework: plain CTest executables (2026-10-12)

Choice: each test is a small executable using `tests/core/check.h` (a `CHECK` macro that prints
the failing expression and exits nonzero). CTest registers and runs them.

Alternatives: doctest or Catch2 as a pinned single header. Rejected for Phase 1 because the test
set is small, the extra dependency adds nothing the brief needs, and a separate process per test
suits the bounded-memory and failure-cleanup tests, which need process isolation anyway.

## D-003 NDK version (2026-10-12, updated 2026-10-13)

Choice: NDK `29.0.14206865` (r29, stable, clang 21.0.0). The developer installed it on
2026-10-13 after the earlier state was reported: the SDK then held only `28.0.12433566`
(r28 beta 1) and an incomplete `30.0.16248370` directory containing nothing but an
`.installer` folder. The developer removed r28 beta 1; the empty r30 directory was deleted
after confirming it still held only `.installer`. NDK r28 and later align shared libraries
to 16 KB pages by default, so no extra linker flag is needed.

`ANDROID_NDK_HOME` and the Gradle `ndkVersion` both name `29.0.14206865`. The libsodium
Android packages first built with r28 beta 1 were removed and rebuilt with r29; vcpkg's
binary cache keys include the compiler, so the old entries are never reused.

## D-004 Gradle 8.10.2, AGP 8.8.2, Kotlin 2.1.10, minSdk 28 (2026-10-13)

Choice: Android Gradle Plugin 8.8.2 with Gradle 8.10.2 (its minimum) and the Kotlin Android
plugin 2.1.10. compileSdk and targetSdk 35, minSdk 28 (pending the checkpoint).

Alternatives: newer AGP lines (8.9 to 9.x) exist. 8.8 is the newest line the installed
Android Studio (Ladybug Feature Drop, build 242) can open, so the developer can use the IDE
on the project without upgrading it. The wrapper distribution and jar are pinned by SHA-256
(`docs/DEPENDENCIES.md`). AGP installed `build-tools/35.0.0` into the SDK on the first build
because it requires that exact version; this happened without a prior request and is recorded
as a side effect.

## D-005 libsodium for Android through vcpkg (route 1), no WSL (2026-10-13)

Choice: vcpkg triplets `arm64-android` and `x64-android` build libsodium with its autotools
path, using the msys2 environment vcpkg downloads on its own. Both built on the first attempt
with NDK r28 beta 1 and again with NDK r29 (about 14 minutes for the pair, from source).

Alternatives considered in the order the brief gives: route 2 (libsodium `dist-build`
scripts under WSL2 with prebuilt libraries committed under `third_party/`) was not needed;
WSL2 with Ubuntu 24.04 exists on the machine and remains the fallback. Route 3 was not
considered. With route 1 nothing prebuilt is committed; every build reproduces libsodium
from the pinned source hash or restores it from the local binary cache.

## D-006 One root CMakeLists.txt for both Visual Studio and Gradle (2026-10-13)

Choice: Gradle's `externalNativeBuild` points at the repository root `CMakeLists.txt`. On
Android the root adds the JNI library and skips the CLI and host tests. Before `project()`
the root maps `ANDROID_ABI` to the vcpkg triplet and chainloads the NDK toolchain through the
vcpkg toolchain file, so the `android-*` presets and Gradle configure the same way.

Alternative: a separate `android/app/src/main/cpp/CMakeLists.txt` as the Gradle root that
adds `src/vault_core` by relative path. Rejected because it duplicates the vcpkg and toolchain
setup and makes the two Android build paths drift.

## D-007 Trailing data after a short final chunk reports AUTH_FAILED (2026-10-13)

Found while writing the negative tests. Chunks carry no length field, so the reader fills a
buffer of `chunk_size + 17` bytes and decrypts whatever it got. When the final chunk is
shorter than that and extra bytes follow, the extra bytes land in the same buffer and the
chunk fails authentication. The reader reports `PV_ERR_AUTH_FAILED` and the file is rejected.
`PV_ERR_TRAILING_DATA` is reported when the final chunk is full-size and any byte follows.

Alternatives: a plaintext length trailer after the FINAL chunk, or a length prefix per chunk,
would let the reader name the condition exactly. Both change the version 1 layout approved at
the checkpoint. Trying shorter prefixes of the buffer until one authenticates would cost up to
chunk_size decryptions per file and was rejected. The behaviour is documented in
`docs/PHASE1_DESIGN.md` Section 3.2 and tested in `tests/core/test_negative.cpp`; the format
still fails closed, which is the requirement. A length trailer is noted as an option for a
format version 2.

## D-008 Android atomic finalize uses rename after an existence check (2026-10-13)

Checked on the emulator (Pixel_8_API_35, Android 15) through the instrumented test
`KeystoreTest.linkProbeIsLogged`: `link()` inside `filesDir` fails with errno 13 (EACCES),
as the developer anticipated for untrusted apps under SELinux. The core therefore takes the
fallback path in `fs::rename_no_replace`: `link()` is attempted first, and on any failure
other than `EEXIST` the destination is checked with `stat()` and `rename()` is called only
when nothing is there, followed by `fsync()` of the directory.

Window for a race: between the `stat()` and the `rename()` another writer could create the
same name. Object names are 128-bit random IDs chosen by the same process, and the only
other writer of `objects/` is the open-time cleanup, which deletes `.partial` files only.
The race is recorded as negligible. An existing object is never replaced silently on either
platform. Windows uses `MoveFileExW` without `MOVEFILE_REPLACE_EXISTING` and has no such
window.

## D-009 Scripted actions in the debug activity for T3 (2026-10-13)

Choice: `MainActivity` reads an optional `actions` string extra (for example
`open,encrypt_100mb,verify`) and runs those actions in order on its worker thread, printing to
the screen and to logcat under the tag `PrivateVault`. This lets the T3 steps run over `adb`
without tapping, and makes the procedure repeatable. The extra is read in `onCreate`, so the
app is force-stopped before each scripted launch.

Alternatives: driving the buttons with `adb shell input tap` (coordinates differ per device)
or a UI automation dependency (adds a library the brief does not want). The extra prints only
object IDs, byte counts, status names, and hashes of synthetic data; no key or sample bytes.
It is a proof-of-concept convenience and would not ship in a product build.

## D-010 Keystore key mode depends on the lock screen (2026-10-13)

Found during T3 on the Xiaomi 2201116SG (Android 13, MIUI, no screen lock set): key
generation with `setUnlockedDeviceRequired(true)` throws `ProviderException`, so create and
open of the vault failed. The emulator, also without a lock screen, did not reproduce it.

Choice, per the developer: `KeystoreKeyProvider` checks `KeyguardManager.isDeviceSecure()`
before generating. With a secure lock screen the key keeps `setUnlockedDeviceRequired(true)`
("lock-bound"); without one the flag is omitted ("not lock-bound"). AES-256-GCM, randomized
encryption, and the StrongBox attempt are unchanged, and any `ProviderException` on the
StrongBox attempt leads to one retry without StrongBox. The mode is recorded in app
preferences at creation, because Keystore does not report the flag back, and is shown and
logged with the security level. Action failures log the class and message of every cause.

Consequences: without a lock screen the key is usable whenever the device is on, so the vault
protects the files against copying and other apps but not against someone holding the phone;
an in-app lock is a later-phase item. A key keeps its creation mode if the user later adds or
removes a lock; keys are not migrated in Phase 1, and whether removing a lock invalidates a
lock-bound key on real devices is unverified. The emulator test
`KeystoreTest.noLockScreenPathCreatesUsableKey` covers the not-lock-bound path.

Alternatives: refusing to create a vault without a lock screen (rejected for the prototype,
which must run on the test phone as it is), or always omitting the flag (rejected because it
gives up a platform protection where it is available).

## D-011 Vault handles cross JNI as opaque values with a separate status (2026-10-13)

Found during T3 on the Xiaomi 2201116SG (arm64, Android 13): `Vault.create` left a
complete `files/vault/` (objects directory and a 97-byte `vault.key`) and then failed at the
final open with `PV_ERR_UNKNOWN`. The JNI function returned the native `pv_vault*` as a
`jlong` and used a negative value to signal an error. On arm64 Android the heap allocator
tags the top byte of pointers (top-byte-ignore), so a valid handle can be negative; the
Kotlin side negated it and got a code outside the table. The x86_64 emulator has no pointer
tagging and never showed it.

Choice: `nativeVaultOpen` returns the status and writes the handle into a `LongArray`; the
handle is never compared as a number. `NativeBridge.errorName` always appends the raw value,
so an unexpected code is visible. The core gained `pv_last_os_error()` (per-thread errno or
`GetLastError` of the last failed file call), and `Vault.CoreException` carries the step
name, the raw status, and that platform error. The Kotlin constants mirror every code in
`pv.h`. Not MIUI-specific: any arm64 device with heap tagging enabled would show it.

Regression test: `VaultTest.openReportsStatusAndHandleSeparately` checks the status and
handle paths and the raw-code formatting. The tagging itself cannot be reproduced on the
x86_64 emulator; only an arm64 target shows it.

## D-012 Phase 2 toolchain: AGP 8.9.3, Gradle 8.11.1, compileSdk 36 (2026-10-13)

Choice (Phase 2 brief, decision P2-1, option U): the Android project moves to compileSdk 36,
Android Gradle Plugin 8.9.3, and Gradle 8.11.1. targetSdk stays 35 and minSdk stays 28.

Why: the AAR metadata of the newest stable CameraX (1.6.2) declares `minCompileSdk=36` and
`minAndroidGradlePluginVersion=8.9.1`; `androidx.media3:media3-muxer` 1.11.1 declares
`minCompileSdk=36`. AGP 8.9 is the lowest line that satisfies 8.9.1, and Gradle 8.11.1 is the
minimum for AGP 8.9. 8.9.3 is the last patch release of that line. No dependency forces
targetSdk 36, so the target stays where Phase 1 tested it. Platform android-36 was already
installed in the SDK, so no SDK component had to be added for this change; build-tools 35.0.0
(required by AGP 8.9) was already present from Phase 1.

Consequence: Android Studio Ladybug Feature Drop (build 242), the version installed in
Phase 1, cannot open an AGP 8.9 project. Command line builds do not need the IDE. The
developer updates Android Studio when IDE use is wanted; any version from Meerkat
(2024.3.1) on supports AGP 8.9.

Alternative (option P): stay on AGP 8.8.2 and compileSdk 35 with CameraX 1.5.3
(`minCompileSdk=35`, `minAndroidGradlePluginVersion=8.6.0`). That would give up the
CameraX 1.6 line and, because every media3 release checked (1.10.1 and 1.11.1) requires
compileSdk 36, the video prototype could not use `media3-muxer` at all. Rejected per P2-1.

## D-013 Manifest merge rules remove what the camera dependencies add (2026-10-13)

Found in Milestone D from the merged debug manifest: `androidx.media3:media3-common`
declares `android.permission.ACCESS_NETWORK_STATE`, and `androidx.profileinstaller`
(a transitive dependency of `androidx.activity` 1.10.1 and of CameraX) adds an
`androidx.startup.InitializationProvider` content provider and an exported
`ProfileInstallReceiver`. The app has no network code, ships no baseline profile, and the
brief allows no exported component beyond the launcher activity.

Choice: `tools:node="remove"` entries in `android/app/src/main/AndroidManifest.xml` for the
permission, the provider, and the receiver. The merged manifest after the change carries
`CAMERA` only (plus the app-private `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that AGP
declares for every app that targets below 33), two activities (one exported), and the
disabled, non-exported `MetadataHolderService` that CameraX needs for its configuration
lookup. Removing a dependency instead was not possible: `media3-common` comes with the
muxer the video prototype uses (and, in the release variant, with `camera-video` 1.6.2
through `camera-view`), and `profileinstaller` comes with every current AndroidX activity
line.

## D-014 Video prototype buffers whole segments instead of streaming them (2026-10-13)

The brief (Section 8.3, item 4) describes the muxer output reaching the core through a
bounded queue read by a `pv_source` while the muxer writes. The item header approved in
P2-2 carries `payload_len` at the start of the plaintext, so an object cannot be started
before its payload length is known. The prototype therefore lets each segment's
`FragmentedMp4Muxer` write into a bounded in-memory buffer (8 MiB cap), closes the muxer at
the segment boundary, and only then encrypts the complete segment through the memory
source on the encrypt thread. At most two completed segments may wait for encryption;
a third stops the recording with `QUEUE_FULL`, and a segment that would exceed its cap
stops it with `MUXER_WRITE_FAILED`. Memory stays bounded; nothing spills to disk.

Consequence for the push-style writer question (brief Section 8.4): with a length field
at the start of the plaintext, a streaming writer would not help video, because the length
is only known at the end of the segment. Either the segment is complete before encryption
starts (this prototype) or a future item header version puts the payload length in a
trailer. The feasibility report records the measured answer.

## D-015 The leak scan must not create the directories it scans (2026-10-13)

Found in T3 on the Xiaomi after the reboot step: `leak_scan` reported one MediaStore row
owned by the app in the files collection. The row was the empty directory
`Android/media/com.privatevault.app`, which the scan itself had created on its first run by
calling `Context.getExternalMediaDirs()` (the `getExternalFilesDirs` and `externalCacheDirs`
getters likewise created `Android/data/com.privatevault.app/{files,cache}`). The media
indexer picked the empty directory up during the boot scan. No media was involved and the
emulator never showed the row because it was not rebooted after the scan.

Choice: `LeakScan.roots()` builds the external paths (`Android/data`, `Android/media`,
`Android/obb` under the primary external storage and under every volume that
`StorageManager.getStorageVolumes()` reports on API 30 and later) and walks them only
when they already exist. No `Context` directory getter is called any more: a first version
of the fix still used `getExternalMediaDirs()` to find the volumes and recreated the
directory on the phone during the benchmark run, which a second check caught; the
instrumented test `LeakScanTest.scanCreatesNoExternalDirectories` now guards it. The
MediaStore query names every owned row (display name, relative path, MIME type, size) so
a directory row can be told from a media file. The empty directories made on the phone
were removed afterwards (`run-as rmdir` for `Android/data/<pkg>`; the shell user for
`Android/media/<pkg>`, which `run-as` could not delete), and the stale row went away after
a `scan_file` call on the removed path through `content call`.

Alternative: keep the getters and ignore directory rows. Rejected because the scan is
supposed to leave the device as it found it.
