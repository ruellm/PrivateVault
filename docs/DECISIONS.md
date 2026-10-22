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

## D-016 Envelope 3 additional data is the whole image with the wrapped fields zeroed (2026-10-14, approved at the Phase 3A checkpoint)

Choice: for every PIN slot of a provider 3 envelope, the AEAD additional data is the
complete envelope with each slot's `wrapped_vmk` field replaced by zeros. The writer builds
that image first and fills the wrapped fields afterwards; the reader zeroes the same fields
before unwrapping. A change anywhere outside a wrapped field, including adding or removing
a slot, fails every slot; a change inside a wrapped field fails that slot's tag.

Alternatives: the additional data as the bytes before the slot's own `wrapped_vmk` (the
phrasing in the brief). For a single slot the two differ only by 48 trailing zeros; with
several slots the prefix form leaves later slots unbound, which the brief also asks to
avoid. The masked-image form costs one copy of a file under 4 KiB.

## D-017 A rewrap creates a fresh Keystore HMAC key and deletes the old one after the rename (2026-10-14, approved at the Phase 3A checkpoint)

Choice: every PIN change and the migration generate a new Keystore HMAC-SHA256 key under
a fresh alias (`privatevault.pin.<16 hex>`), write the new envelope naming it, and delete
the previous alias (or the provider 2 alias) only after the rename returned. An alias
sweep after every successful unlock removes aliases the current envelope does not name.

Alternative: keep one HMAC key for the life of the vault and only change salt and PIN.
Rejected because a copy of `vault.key` taken before the PIN change would still open with
the old PIN on the device. With the old key gone, the old copy has nothing to run its HMAC
with. Cost: one key generation per rewrap (15 ms on the emulator) and the sweep.

## D-018 A reboot restarts the current wrong-PIN delay (2026-10-14, approved at the Phase 3A checkpoint)

Choice: `pv_pin_remaining_ms` returns the larger of the elapsed-clock and wall-clock
estimates while the boot count is unchanged, and the whole delay again after a reboot.

Alternative: trust the wall clock after a reboot. Rejected because the device owner can set
the wall clock, so a reboot plus a clock change would skip the delay. Restarting the delay
costs a legitimate user one extra wait, at most an hour, only after a reboot during a
delay.

## D-019 LockActivity is the exported launcher; scripted actions enter through it (2026-10-14, approved at the Phase 3A checkpoint)

Choice: the lock screen is the only exported activity. `MainActivity` (vault screen) and
`CaptureActivity` are not exported and route to the lock screen whenever the process state
is not `UNLOCKED`. The `actions` extra of D-009 is read by `ScriptActivity`, which lives in
the debug source set and is exported by the debug manifest only (the developer's amendment
at the checkpoint: the shell cannot start a non-exported activity, so a debug-only exported
entry point is needed); the list must begin with `unlock_pin=<pin>` or `setup_pin=<pin>`,
after which the rest is handed to the vault screen as before. The benchmark and release
manifests export nothing but `LockActivity`.

Alternative: keep `MainActivity` exported and let it show a lock view when locked. Rejected
because every entry point would then need the same check, and a scripted `am start` could
reach the vault screen class directly.

## D-020 Background detection by counting started activities, not ProcessLifecycleOwner (2026-10-14)

Found in the host-driven T2 run: pressing Home while unlocked never produced the
background lock, although the screen-off and idle triggers did. `ProcessLifecycleOwner`
(`androidx.lifecycle:lifecycle-process`) is attached by `ProcessLifecycleInitializer`
through the `androidx.startup.InitializationProvider`, and D-013 removes that provider from
the merged manifest with `tools:node="remove"` because `profileinstaller` registers through
the same provider. With the provider gone, the owner never observes any activity and
`ON_STOP` never fires.

Choice: `PvApp` registers `ActivityLifecycleCallbacks`, counts started activities, and
asks for the lock 700 ms after the count reaches zero, which is the rule
`ProcessLifecycleOwner` applies internally (the delay lets a rotation or a permission dialog
pass). The `lifecycle-process` dependency is removed again; CP-6 is withdrawn.

Alternative: keep the dependency and merge the provider back with only the lifecycle
initializer (`tools:node="merge"` on the provider, `tools:node="remove"` on the
profileinstaller meta-data). Rejected because the counting is twenty lines, needs no
provider, and keeps the merged manifest exactly as Phase 2 left it.

## D-021 Default Argon2id parameters ops 2 / 256 MiB, fallback ops 3 / 128 MiB (2026-10-14, CP-2 approved)

Measured on the Xiaomi 2201116SG in the benchmark build (`docs/PHASE3A_DESIGN.md` Section
3.3): ops 2 / 256 MiB takes 0.83 to 0.88 s per guess, ops 3 / 128 MiB 0.59 s, ops 3 / 256 MiB
1.18 s. The Section 3.4 rule (largest memory inside 0.5 to 1.0 s, then the larger pass
count) selects ops 2 / 256 MiB, which the developer approved as `PV_PIN_OPSLIMIT_DEFAULT`
and `PV_PIN_MEMLIMIT_DEFAULT` (mirrored in `NativeBridge`). Six digits are then about ten
days of single-threaded on-device work.

Conditions attached to the approval: an Argon2id allocation failure during an unlock is
reported as "not enough memory, try again" and the attempt recorded before the stretch is
rolled back, because no guess was made (a debug-only hook forces that code so a T2 test
covers it); the T2 lock tests make their vaults with ops 2 / 64 MiB through a test
override so the suite stays short, and one test makes a vault with the defaults and
checks the recorded parameters.

Fallback: ops 3 / 128 MiB at 0.59 s per guess, half the memory, if 256 MiB turns out too
large for a device in scope. The reader accepts the whole INTERACTIVE to MODERATE box, so
changing the default later needs no format change; existing envelopes keep the parameters
they were written with until the next PIN change rewraps them.

## D-022 camera-video excluded on camera-view; V2 and V3a probe code deleted (2026-10-14, approved at the Phase 3B checkpoint)

Choice (P3-10): `androidx.camera:camera-view:1.6.2` is kept and declared with an exclusion
of `androidx.camera:camera-video`; the explicit debug dependency on `camera-video` is gone;
`androidx.media3:media3-muxer:1.11.1` stays in the debug variant for the video prototype.
The Phase 2 V2 probe (`Recorder` over a proxy file descriptor) and the V3a attempt (custom
`VideoOutput`) were deleted because both need `camera-video`; their measurements remain in
`docs/VIDEO_CAPTURE_FEASIBILITY.md`.

Measured on the emulator (Milestone A): `PreviewView` binds and captures with the exclusion
in place (8 verified captures through `CaptureActivity`), the V3b prototype still records
and verifies, and the full T2 suite passes. Release and benchmark APKs define no media3 and
no `androidx.camera.video` class (443 to 557 classes each before); benchmark shrinks from
13 822 758 to 10 529 341 bytes. `com.google.guava:listenablefuture` now resolves to the
real 1.0 artifact instead of the empty placeholder that guava had forced, and
`ListenableFuture` is present in every APK.

Alternatives: drop `camera-view` and draw the viewfinder through `Preview.setSurfaceProvider`
into an own `SurfaceView` (brief 4.6.4). Not needed while `PreviewView` works without
`camera-video`; it stays the fallback if the phone shows otherwise. Keeping `camera-video`
only in debug was rejected because P3-10 removes it from every variant.

## D-023 Orientation rule: the EXIF tag decides, the header rotation is the fallback (2026-10-14, approved at the Phase 3B checkpoint; the debug build logs a disagreement between the tag and the header)

Measured on the emulator: CameraX writes an EXIF orientation tag into the in-memory JPEG that
matches `ImageInfo.rotationDegrees`, which the item header stores (6 for 90, 8 for 270, 1 for
0); `ImageDecoder` applies that tag by itself and `BitmapFactory` does not. On the Xiaomi
(Phase 2) the HAL rotates the pixels and reports rotation 0; its tag is assumed to be 1 or
absent until the phone probe runs.

Choice: decode with `ImageDecoder`; if the JPEG carries an EXIF orientation other than 1,
apply nothing more; if the tag is absent or 1, turn by the header `rotation_degrees`. One
rule, never two turns, upright on both devices observed, and it covers a device that writes
no tag. On a conflict the tag wins (the decoder has already applied it). The tag is read by a
small own parser over the marker segments (D-026).

Alternatives: always turn by the header rotation (turns the emulator photos twice: the
decoder once, the app once); ignore the header and trust the decoder alone (leaves a device
without EXIF sideways); decode with `BitmapFactory` and turn by the header only (gives up
the target-size decode of `ImageDecoder` and would show a device that writes a tag but
reports rotation 0 sideways).

## D-024 Viewer buffers in guarded memory without a memory lock, no malloc fallback (2026-10-14, approved at the Phase 3B checkpoint)

Measured on the emulator: `sodium_malloc` succeeds at 5 to 64 MiB; `mlock` on the block
fails with `ENOMEM` because `RLIMIT_MEMLOCK` is 65 536 bytes (the phone reports the same
limit). `sodium_malloc` ignores the refusal, so the block has guard pages, a canary, and a
wipe on free, but is not locked.

Choice: use `sodium_malloc` through `pv_secure_alloc` anyway, record in the threat model
that the block is not locked, and report an allocation failure as `PV_ERR_OUT_OF_MEMORY`
with no plain `malloc` fallback: both allocators fail on the same `mmap` failure, and a
second free path would be a way to abort the process (`sodium_free` on a `malloc` block).

Alternatives: raising `RLIMIT_MEMLOCK` is not possible for an app; a plain `malloc` plus
`sodium_memzero` on free would lose the guard pages and the canary for nothing in return.

## D-025 Viewer decrypts are cancelled by a flag the core polls, set inside Vault.close (2026-10-14, approved at the Phase 3B checkpoint)

Choice: `pv_vault_decrypt_to_buffer` takes a pointer to a caller-owned flag and stops with
`PV_ERR_CANCELLED` (wiping the buffer) when it is set between two chunks. On Android the
flag is a JNI global that `Vault.close()` sets before taking the write side of its guard and
clears after the handle is closed. A lock therefore ends a viewer decrypt within one chunk,
and the flag is never set outside that window.

Alternatives: let the lock wait for the decrypt (bounded by one object of at most 32 MiB,
but a slowed test decrypt would hold the lock for the whole stream, and the brief asks for
the result to be discarded); reuse the Phase 3A encrypt cancel flag (it is cleared when the
capture queue empties, which can be before the viewer decrypt ends, and Phase 3A showed a
process-wide flag that outlives the lock breaks later encrypts); a per-handle cancel in the
core (changes the behaviour of existing functions for every caller).

## D-026 No new Android dependency for the viewer (2026-10-14, approved at the Phase 3B checkpoint as amended)

Choice: the gallery uses the platform `GridView`, the viewer a plain `View` with a `Matrix`,
decoding uses `ImageDecoder`, and the EXIF orientation tag is read with the platform
`android.media.ExifInterface(InputStream)` over an in-memory stream view of the payload
buffer, the path the Milestone A probe used. The developer chose this over the proposed own
sixty-line parser of the APP1 segment: one platform call instead of own parsing code, no
dependency either way. The lint warning on that class is suppressed at the one call site.

Alternatives: `androidx.recyclerview` (a new dependency for a grid the platform widget
draws); the own parser (proposed, rejected by the developer); `androidx.exifinterface`
(already in the APK through `camera-core`, but using a transitive artifact directly needs an
explicit pin and a dependency record for one tag).

## D-027 Heap investigation with the Android Studio profiler and heap dumps, Perfetto for native (2026-10-14, approved at the Phase 3B checkpoint as amended)

Found in Milestone A: Android Studio on the machine was AI-242 (Ladybug Feature Drop), which
cannot open the AGP 8.9 project; the phone runs Android 13, whose Perfetto (v25) offers Java
heap graphs and native `heapprofd` but no Java allocation sampling (that starts with
Android 14).

Choice (developer): the developer updates Android Studio; its profiler records Java
allocations with call stacks during the ten-capture burst on the debug build (route a), and
`am dumpheap` before and after a forced collection shows retention. Fallback (route b):
`am dumpheap`, `hprof-conv`, and the dump opened in Android Studio or Eclipse MAT.
`heapprofd` gives the native callstacks for the native heap growth seen in Phase 2. Dumps
and traces stay under `D:\pv-heap\`, outside the repository, and are deleted after analysis.

Alternative proposed and rejected: an own Python HPROF reader for the retaining paths,
which would have given retention but no allocation stacks.

## D-028 Sync frames: the framework extractor reports fragment starts, the container carries every key frame (2026-10-19, approved at the Phase 3C checkpoint)

Measured on the emulator and the Xiaomi (12 clips, 48 segments): the `FragmentedMp4Muxer`
writes a per-sample flags word in every `trun` (flags 0x701) and clears the non-sync bit
for exactly the frames the encoder flagged as key frames; media3's `FragmentedMp4Extractor`
reports the same count; the framework `MediaExtractor` reports one sync sample per `moof`,
whatever the per-sample flags say. The Phase 2 mismatch (2/4/4/3 against 2/2/2/2) was the
framework reader over two fragments per segment.

Choice: seeking keys off an index of encoder key-frame times (D-029); playback uses media3,
which honours the per-sample flags; the framework extractor is used nowhere in the video
path. Fragment duration is set equal to the GOP so that even a framework-style reader sees
every key frame.

Alternatives: trusting the framework extractor (loses every key frame but the first per
fragment); fragments of one GOP only (chosen anyway, for the reader independence it
brings).

## D-029 Video settings: time-based sync requests, 3 s segments of three GOPs, fragment equal to the GOP (2026-10-19, approved at the Phase 3C checkpoint; segments are cut on the actual key-frame output and the index records actual key-frame times)

Measured: both encoders count `KEY_I_FRAME_INTERVAL` in frames at the configured frame
rate, so with 30 configured and 14 delivered the Xiaomi placed key frames 2.2 s apart; the
boundary sync request plus the periodic key frame gave paired key frames 33 to 260 ms apart
on the emulator.

Choice: the app requests a sync frame every 1 s of PTS, cuts a segment at every third
requested key frame, sets `KEY_I_FRAME_INTERVAL` to 10 s as a backstop, gives the encoder
the delivered frame rate, and uses a 1000 ms fragment duration. Segment and GOP lengths
then hold in time on every encoder, and the seek grid is predictable.

Alternatives: `KEY_I_FRAME_INTERVAL` 1 s with the nominal rate (2.2 s GOPs on the phone);
cutting on the encoder's periodic key frames (segments of unpredictable length); fragment
500 ms (no effect, fragments start on key frames only).

## D-030 Index v1: a fixed header with audio fields and header_len, 48-byte segment records, u32 key offsets, magic PVVINDEX (2026-10-19, approved at the Phase 3C checkpoint; the 4096 key bound is per segment)

Choice: the layout of `docs/PHASE3C_DESIGN.md` Section 4.2, encoded and decoded in the core
with every field validated, bounded at 16384 segments, 4096 keys per segment, and 8 MiB. A
new magic keeps the Phase 2 prototype indexes unknown items without migration. Audio fields
are present and zero so a 3E writer needs no version change; `header_len` lets a later
header grow.

Alternatives: a text or TLV format (more code to validate for no gain at this size);
recording key frames as absolute clip times in u64 (twice the size; offsets from the
segment start fit u32 for any segment under 71 minutes); migrating the prototype indexes
(nothing of value in them; they are test recordings).

## D-031 Playback probe: ExoPlayer over a guarded-memory DataSource, one MediaItem per segment, media3 only in debug (2026-10-19, approved at the Phase 3C checkpoint)

Choice: `media3-exoplayer` 1.11.1 in the debug variant only; a custom `DataSource` that
decrypts a segment into guarded memory on `open` and wipes it on `close`; a playlist of
segment items over `FragmentedMp4Extractor` with default flags; seeks resolved by the
core's `locate`; at most the current and the next segment decrypted at once; the player
released and the buffers wiped on every lock trigger. Plaintext copies inside media3 and
the codec are listed, not claimed.

Alternatives: the framework `MediaPlayer` or a bare `MediaCodec` loop (no per-sample sync
reading in the former; the latter is the 3D amount of work the probe should measure, not
replace); one concatenated stream across segments (several `moov` boxes in one stream are
not a valid fragmented MP4); decrypting the whole clip (defeats the segment design and the
memory bound).

## D-032 media3-exoplayer in every variant, own controls, no media3-ui (2026-10-20, approved at the Phase 3D checkpoint)

Measured in Milestone A: `media3-exoplayer` 1.11.1 declared as `implementation` adds
5 008 554 bytes and 2577 classes to the release and benchmark APKs, seven media3 artifacts to
their trees, no native library, and one manifest permission (`WAKE_LOCK`).

Choice: the pinned 1.11.1 the probe measured with, declared for every variant; the
`WAKE_LOCK` removal moves to the main manifest; the controls (tap, seek bar, times) are
platform widgets in `VideoActivity`; `media3-ui` is not added. R8 stays a Phase 6 item.

Alternatives: a bare `MediaCodec` loop in place of ExoPlayer (the 3C probe needed its seek
map, re-basing, and playlist handling, all of which ExoPlayer provides over the data
source; the own loop would be the larger and less tested piece); `media3-ui` for the
controls (brings its own views and resources for three widgets the platform has).

## D-033 Gallery classification by an item-header peek, segments hidden by their own header (2026-10-20, approved at the Phase 3D checkpoint)

Choice: a new core call `pv_object_item_header` decrypts only the first chunk of an object
(authenticated by its tag, bound to the object header) and reads the item header; the
gallery classifies every object through it, shows one tile per index object, and lists no
segment object, whether or not an index names it. Every display path still decrypts the
whole object.

Alternatives: classify through the full viewer load as 3B does (decrypts every segment,
1 to 2 MB each, to learn it is a segment: several seconds of background work for a vault
of a few clips); hide segments only once an index names them (segments of a damaged clip
or an interrupted recording would show as unknown tiles); a persistent object list with
types (metadata on disk outside the objects, which the design avoids).

## D-034 Video thumbnail from the guarded buffer through MediaCodec, buffer freed once the sample is queued (2026-10-20, approved at the Phase 3D checkpoint)

Measured on the emulator: parse 0 ms, codec start 27 to 92 ms, segment buffer live 39 to
108 ms, frame wait 0 to 1 ms, conversion 3 to 59 ms, 46 to 170 ms in all; the bitmap is
256x192, turned to 192x256 by the index rotation, mean luma 98.

Choice: an own parse of the `avcC` and the first sample of the first fragment, the sample
copied straight into the codec input buffer with start codes, the segment buffer wiped and
freed before the decode waits, a flexible YUV output sampled into a mutable software
bitmap. Platform classes only; the same cache and erase rules as photo thumbnails.

Alternatives: `MediaMetadataRetriever` over a `MediaDataSource` (the extraction runs in a
system process over callbacks that copy the segment into Java arrays); the framework
`MediaExtractor` over a `MediaDataSource` (same copies, and it reads sync flags the
framework way, D-028); media3's extractor for the sample (ties the thumbnail to media3
for no gain over sixty lines of box reading); a surface and `ImageReader` output (the
brief's alternative, kept as the fallback if the hardware decoder in buffer mode fails on
the Xiaomi).

## D-035 Delete order index first, orphan sweep after every unlock, skipped while any index is unreadable (2026-10-20, approved at the Phase 3D checkpoint as amended: the sweep works over the object list taken at its start and never runs beside a recording)

Choice: delete the index object, then the segments in index order; after every successful
unlock the lock thread takes the object list once, peeks every listed object header,
decodes every index, and deletes the listed segment objects no index names; if any object
is unreadable (its first chunk fails to decrypt: an index with a damaged first chunk looks
like any other damaged object, as the Milestone D T2 run showed when the first version
swept such a clip's segments) or any index fails to decode, the sweep skips and logs. A
recording gate keeps the sweep and a recording
apart: the sweep skips when a recording is active, and a recording waits for a running
sweep. A T2 test starts a recording right after an unlock and checks no segment of it is
deleted.

Alternatives: segments first then the index (an interruption leaves a gallery entry for a
clip that no longer plays); a sweep at every gallery open (repeats the work on every open
and runs on the viewer thread); deleting unnamed segments even when an index is damaged
(would remove a damaged clip's segments while its tile is still shown).

## D-036 Playback counts as activity for the idle lock; the surface keeps the screen on while playing; no pause timeout (2026-10-20, approved at the Phase 3D checkpoint as amended)

Choice: the viewer's position ticker touches the idle timer while the player is playing;
paused or ended, the idle lock fires after the timeout as everywhere else. The video
surface keeps the screen on while playing (`keepScreenOn`, no wake lock and no `WAKE_LOCK`
permission); the screen-off lock is unchanged. No separate timeout on pause: the guarded segment buffers are closed within the first seconds of
playback (measured), so a pause holds only the player's sample queue and the codec
buffers, which the idle, screen-off, and background locks release.

Alternatives: let the idle lock fire mid-play (the brief rules it out); a pause timeout
shorter than the idle timeout (a second timer for memory the app cannot wipe anyway).

## D-037 Viewfinder while recording through a CameraEffect splitting one camera stream in OpenGL (2026-10-21, approved at the Phase 3E checkpoint; amended in Milestone E: the encoder draws with the viewfinder matrix and the clip carries rotation 0)

Measured in Milestone A on the Xiaomi 2201116SG: with a public `CameraEffect` (target
`PREVIEW`) whose `SurfaceProcessor` draws every camera frame to the `PreviewView` surface
and to the encoder input surface, the camera delivers 30 fps at 1920x1080, every frame
reaches both outputs, and the encoder keeps 30 fps, the same as the prototype without a
viewfinder; the cost is about 20 percent of a core and 90 to 120 MiB of PSS in the debug
build. The emulator (software GL) halves its rate through the split at any size. An own
`SurfaceTexture` as the Preview output with an own `SurfaceView` drawn in GL gives the
same numbers.

Choice: the effect route. CameraX supplies the matrix that turns and crops the preview
output and the `PreviewView` keeps its role. Amended after T3 (Milestone E): the camera
transform of the device sits inside the texture matrix (a quarter turn on the Xiaomi),
so an encoder drawn with that matrix and a container turned by the stream rotation played
sideways; the encoder is now sized to the turned stream and drawn with the same matrix
CameraX supplies for the viewfinder output, and the index and the container carry
rotation 0. The own route stays in the trial record as the fallback.

Alternatives: the prototype without a viewfinder (rejected by the brief); the own route
(works, but the app owns the preview transform and a second view).

## D-038 Segments assembled by an own fragmented MP4 writer in the core inside guarded memory (2026-10-21, approved at the Phase 3E checkpoint with conditions: H.264 now and AAC in 3F, no other codec, only the boxes the design lists; a compatibility gate in T2 and T3 (framework MediaExtractor and media3 parse every new clip, key counts equal to the index); T1 golden vectors for the init segment and one fragment)

Measured: the media3 muxer plus a post-pass (tfdt per fragment, trun and tfhd offsets
rewritten, sidx after the moov) and an own writer sketch both give segments that play and
seek through the bare `FragmentedMp4Extractor` with every seek on the chosen key frame
on both devices; the muxer copies every sample into Java byte buffers before it writes a
fragment, and the muxer would become a release dependency.

Choice: the writer lives in the core (`segment.cpp`), appends each encoder output buffer
from its direct `ByteBuffer` into one guarded segment buffer, writes the tkhd rotation,
tfdt, sidx, true sample durations, and a microsecond timescale, and the segment is
encrypted from that buffer. No Java array holds a sample or a segment; three segment
buffers at most. T1 tests the boxes.

Alternatives: the muxer plus post-pass (a release dependency on `media3-muxer`, a Java
copy of every fragment, a second pass per segment); a push-style object writer (D-014
stands).

## D-039 Playback chooses the extractor per segment by the presence of tfdt (2026-10-21, approved at the Phase 3E checkpoint; the wrapper behaviour on tfdt clips is traced in Milestone D or pinned by a T2 test as a known limit)

Measured: without a sidx the extractor reports the stream unseekable and every in-segment
seek lands at the segment start, so the standard path needs the sidx; with tfdt and sidx
the bare extractor seeks inside a segment and applies the container rotation.

Choice: the data source records whether the first fragment of a segment carries tfdt;
the extractor factory returns the bare `FragmentedMp4Extractor` for such a segment and
the 3D wrapper with the index rotation otherwise. The two regression clips keep the
wrapper path. The wrapper also landed every seek right on a tfdt clip in the trial; the
mechanism was not traced and is not relied on.

Alternatives: one path for all clips through the wrapper (keeps the re-basing code
forever); converting the old clips (a rewrite of stored objects, out of scope).

## D-040 Capture settings 1920x1080 asked with fallback, 30 fps target, 8 Mbit/s (2026-10-21, approved at the Phase 3E checkpoint: the Xiaomi decides, the emulator records at its 720p fallback and T2 asserts no frame rate)

Measured on the Xiaomi with the viewfinder live: 30 fps delivered and encoded at
1920x1080 at 8.0 Mbit/s (segments 3.3 to 3.6 MB) and at 1280x720 at 5.1 Mbit/s (2.0 MB).
The emulator offers 1280x720 at most and 7 to 11 fps through the split.

Choice: a `ResolutionSelector` asking 1920x1080 (16:9, closest lower then higher), a
30 to 30 frame-rate range, the encoder configured with the rate the splitter counts
over its first second, 8 Mbit/s at 1080p and 5 Mbit/s at 720p or below, the 8 MiB
segment cap, the 3C GOP and segment rules; the index records the delivered rate. The
phone is the deciding device; the emulator takes the fallback and T2 asserts the
container and index rules, not a rate.

Alternatives: 1280x720 everywhere (the brief rule read with the emulator as a device
that must reach 25 fps; it cannot reach it at any size through the split); a size fixed
per device (no public way to know the encoder budget in advance).

## D-041 One stop path for every end of a recording, the lock bounded by 4 s (2026-10-21, approved at the Phase 3E checkpoint)

Choice: the stop button, every lock trigger, low space at a segment start, an encoder,
camera, or effect error, an encrypt failure, and a third finished segment all call
`VideoRecorder.stop(reason)`: no new segment, end of stream signalled, the open segment
finished where the stream ends and encrypted, the index written over every encrypted
segment, the gate released. `LockController.lock()` waits up to 4 s for it before the
capture drain; past the bound the open segment buffer is wiped and the index covers the
rest. A process kill leaves orphan segments for the sweep (3D rule); no recovery in 3E.

Alternatives: cutting the current segment on a lock without encrypting it (loses up to
3 s the user saw recorded); an unbounded wait (a slow encrypt would hold the lock).

## D-042 Read-ahead bounded by a byte target of two segments (2026-10-21, approved at the Phase 3E checkpoint)

Measured in 3D: the load control kept about 20 s of a 30 s clip in the sample queue on
the emulator, more than its 4 s of durations, and held a guarded buffer open while it
paused.

Choice: `DefaultLoadControl` with a target buffer of twice the largest segment of the
index (at least 4 MiB) and size before time, so the loader pauses at about two segments
of samples; measured on a 60 s clip on both devices in Milestone E.

Alternatives: one media source over all segments (changes the playlist shape of 3D);
a smaller time threshold (the queue is bounded by bytes, not time, when segments are
whole).

## D-043 Heap investigation with the Rabbit profiler on the Xiaomi debug build (2026-10-21, approved at the Phase 3E checkpoint)

Choice: the 3B procedure (D-027 route a) with the current Android Studio: ten photo
captures with Java allocation recording, dumps before and after a forced collection under
`D:\pv-heap`, deleted after reading; a capture change only through a public CameraX
setting with T2 and the phone captures green; otherwise the threat model records the
copy.

Alternatives: as D-027.

## D-044 Audio format AAC-LC 48 kHz stereo 128 kbit/s from the CAMCORDER source through the platform encoder (2026-10-21, approved at the Phase 3F checkpoint)

Measured in Milestone A: both devices offer only the software AAC encoders
(`c2.android.aac.encoder`, `OMX.google.aac.encoder`), 8 to 48 kHz, up to 6 channels, 8 to
510 kbit/s; every `AudioRecord` configuration of `CAMCORDER` and `MIC` at 48 and 44.1 kHz
mono and stereo initializes and delivers frames; the encoder keeps input times on its
output.

Choice: AAC-LC at 48 kHz, stereo, 128 kbit/s, source `CAMCORDER`, `MediaCodec` by type;
mono when the device offers no stereo input. The `AudioSpecificConfig` the encoder
reports goes into the container; the index records codec 1, channels, and rate.

Alternatives: 44.1 kHz (no reason on these devices); the `MIC` source (no processing
chosen by the platform for camcorder use); a hardware encoder (none exists here).

## D-045 The audio track in the core writer: mp4a with esds, a second traf per moof, the audio counted in samples, per-fragment interleaving (2026-10-21, approved at the Phase 3F checkpoint as amended: the audio timescale is the sample rate, every AAC frame exactly 1024 ticks, the video stays in microseconds)

Measured: a Kotlin sketch of the two-track container in the 3E shape (`tfdt`, `sidx`,
`trun` 0x701 for the video, 0x301 for the audio, the audio samples after the video
samples in each `mdat`) is parsed by the framework `MediaExtractor` and media3
`FragmentedMp4Extractor` with equal sample counts per track and played by ExoPlayer with
no underrun on both devices, with the audio track in microseconds and in samples alike.
The framework extractor ignores `tfdt` and times every track from 0.

Choice: the core writer takes an optional audio track at open (`pv_segment_audio`),
writes the `mp4a` sample entry with `esds`, a `trex` per track, and per fragment an audio
`traf` with `tfdt` and a `trun` of durations and sizes; the audio track counts in
samples (timescale 48 000): the first frame of a segment gets its microsecond time
rounded to samples, every later frame sits exactly 1024 samples after the one before,
every duration is 1024, so a fragment's audio `tfdt` is the first tick plus 1024 times
the frames before it, with no drift (T1 checks one hour); the video stays in
microseconds; the audio of a fragment is the audio whose time falls in its video span,
staged in guarded memory until the fragment closes. The single-track shape is unchanged.

Alternatives: both tracks in microseconds (measured, works; per-frame rounding of 21 333
or 21 334 us, which the developer ruled out at the checkpoint); interleaving inside the
`mdat` by time (needs several runs per track, no reader gain at one-second fragments).

## D-046 One clock for both tracks: the camera clock found at the first frame, audio timed from AudioRecord.getTimestamp (2026-10-21, approved at the Phase 3F checkpoint)

Measured: the Xiaomi declares `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` and stamps camera
frames on the boot clock, 353 662 s off the monotonic clock on the trial day; the
emulator declares `UNKNOWN` on its back camera and stamps on the monotonic clock. The
camera timestamp of the first frame lies 33 to 103 ms before its callback on the right
clock. `getTimestamp` succeeds on the first read on both devices; later timestamps stay
within 0.2 ms of a frame count from the first one over 20 s on the Xiaomi. The clap
clip plays with the audio 30 to 90 ms behind the video by the probe, on time by the
developer's ear and eye.

Choice: the recorder compares the first camera timestamp with `System.nanoTime` and
`SystemClock.elapsedRealtimeNanos` and takes the nearer clock as the camera clock; audio
chunks are timed on the monotonic clock from the first `getTimestamp` anchor by frame
count and moved by the boot-minus-monotonic difference when the camera uses the boot
clock. No empirical correction constant; T3 measures the offset again.

Alternatives: trusting the declared source (right on the phone, says nothing on the
emulator); timing audio by the read return time (10 to 110 ms of pipeline latency
ignored); a fixed correction (not supported by the measurement).

## D-047 Encoded audio waits in a guarded queue in the core until the video has reached its time (2026-10-21, approved at the Phase 3F checkpoint as amended: audio arriving after the wait goes into the next segment and is never dropped; only the final segment drains to the last sample)

Measured: the video encoder output lags the camera by about 0.5 s on the Xiaomi and over
a second on the emulator, while encoded audio arrives about 20 ms after capture; audio
appended on arrival landed in the wrong segment at every cut on the emulator (53 frames
of segment 1 written into segment 0 and dropped). With the audio queued until a video
sample at or past its time was written, every segment of every trial clip held its audio
contiguously from boundary to boundary; the queue peaked at 25 to 34 frames on the
phone.

Choice: `pv_audio_queue` in guarded memory takes every AAC output buffer from the
encoder in place; the video drain moves the queued frames below the last video time
into the open writer after every video sample; at a cut the old segment takes the
frames below the key frame time and waits for audio past it (or 500 ms) before it is
finished; a frame that arrives after that goes into the next segment, written at its
start, never dropped; at stop the whole queue is moved into the last segment to its
last frame. Audio from before the first video frame is discarded (the clip begins with
that frame). Cap 2 MiB; past it the recording stops through the 3E stop path and keeps
the clip.

Alternatives: appending on arrival (wrong at every cut); holding the audio in Java (a
copy of every frame in the heap, against the brief); delaying the video (the video
samples still have to precede the audio in the fragment).

## D-048 Microphone permission asked once on entering video mode; a mute toggle held by the recorder (2026-10-21, approved at the Phase 3F checkpoint)

Choice: `RECORD_AUDIO` is requested the first time the capture screen enters video mode
in a process; a denial gives recordings without an audio track, "no audio" on the record
button and the elapsed label, and a Microphone button that asks again or opens the app
settings page when the system shows no dialog; the app repeats no request by itself. The
Mute toggle lives in the process-scoped recorder: muted recordings open no `AudioRecord`
and write no audio track.

Alternatives: asking at app start (audio is not needed for photos); asking at every
recording (a loop).

## D-049 Interruptions end the recording through the 3E stop path after the audio encoder is drained (2026-10-21, approved at the Phase 3F checkpoint)

Choice: the microphone taken (the app's recording reported silenced by the recording
callback), a read error, a codec exception on the audio thread, and the queue cap each
call `VideoRecorder.stop` with their name; the stop path drains the video, stops the
`AudioRecord`, drains the AAC encoder (bounded by 1 s), moves the queue into the last
segment, and finishes it; the lock's 4 s bound covers the drain and wipes the queue past
it.

Alternatives: pausing on an interruption (two clips, out of scope); recording on with
silence (the user gets a silent clip without knowing).

## D-050 Playback with audio focus, a mute button, the media volume keys, and a pause when headphones are unplugged (2026-10-21, approved at the Phase 3F checkpoint)

Choice: ExoPlayer with media audio attributes and focus handling (pause on loss, duck
on a transient loss), `setHandleAudioBecomingNoisy`, the volume keys on the music
stream, a Mute button that sets the player volume to 0 for the viewer instance; clips
without an audio track play silent and the status line says so from the index.

Alternatives: an own focus listener (ExoPlayer has one); a persistent mute setting (a
viewer-side convenience, not a vault setting).
