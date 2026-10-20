# Dependencies

Every third-party component the Phase 1 build uses, with version, source, license, and how
the download is verified. Dates are when the facts were checked. Nothing here is fetched as
"latest" at build time; every item is pinned.

## libsodium 1.0.22

| | |
|---|---|
| Role | All cryptography: secretstream XChaCha20-Poly1305, XChaCha20-Poly1305-IETF AEAD, Argon2id, BLAKE2b, random bytes, guarded memory |
| Version | 1.0.22, tag `1.0.22-RELEASE`, published 2026-04-09 (GitHub releases API, checked 2026-10-12) |
| License | ISC (file `LICENSE` in the source tree, copyright 2013-2026 Frank Denis) |
| Source | https://github.com/jedisct1/libsodium, archive of tag `1.0.22-RELEASE` |
| Obtained through | vcpkg port `libsodium` 1.0.22#1 at the baseline below |
| Verification | vcpkg pins the SHA512 of the GitHub archive in `ports/libsodium/portfile.cmake`: `f8e11ad193037b7b885a40b832da331105f4e5943b74c0297fe07e02a313786016c777aebb094ad1fcf16398e54af545c2d55404c28b371eae5922d1e164ba00`. vcpkg refuses the download if the hash differs. |
| Upstream signature | Release tarballs are signed with minisign. Public key from https://doc.libsodium.org/installation (2026-10-12): `RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3`. The `.minisig` for `libsodium-1.0.22.tar.gz` (fetched 2026-10-12) carries trusted comment `timestamp:1775774745 file:libsodium-1.0.22.tar.gz hashed`. The project builds from the vcpkg-pinned GitHub archive, not the tarball, so the minisign signature is recorded here for reference and was not executed against the build input. |
| Library version | `SODIUM_LIBRARY_VERSION_MAJOR` 26, `MINOR` 4 |
| Build on Windows | vcpkg triplet `x64-windows-static`: MSBuild project `builds/msvc/vs2022/libsodium/libsodium.vcxproj`, static library, static CRT (`/MT`). `SODIUM_STATIC` is defined for consumers. |
| Build on Android | vcpkg triplets `arm64-android` and `x64-android`: autotools build driven by vcpkg's bundled msys2, NDK from `ANDROID_NDK_HOME`, static library. |

APIs used and the documentation pages checked on 2026-10-12:

- `crypto_secretstream_xchacha20poly1305_*`: https://doc.libsodium.org/secret-key_cryptography/secretstream
- `crypto_aead_xchacha20poly1305_ietf_*`: https://doc.libsodium.org/secret-key_cryptography/aead/chacha20-poly1305/xchacha20-poly1305_construction
- `crypto_pwhash` (Argon2id): https://doc.libsodium.org/password_hashing/default_phf
- `crypto_generichash` (BLAKE2b), `randombytes_buf`, `sodium_malloc`, `sodium_memzero`,
  `sodium_init`.
- `crypto_stream_chacha20_ietf_xor_ic` for the deterministic test-data source
  (`docs/PHASE1_DESIGN.md` Section 13): https://doc.libsodium.org/advanced/stream_ciphers/chacha20

## vcpkg

| | |
|---|---|
| Role | Builds and pins libsodium for Windows and Android |
| Registry | https://github.com/microsoft/vcpkg, tag `2026.07.29`, commit `9e593bb18ea69cc5095e012465dcd675a822ed0d`, cloned to `D:\tools\vcpkg` |
| Tool | vcpkg 2026-07-27 (`vcpkg.exe` downloaded by `bootstrap-vcpkg.bat` from the microsoft/vcpkg-tool release and signature-checked by the bootstrap script) |
| License | MIT |
| Pinning | `vcpkg.json` sets `builtin-baseline` to the commit above and overrides `libsodium` to `1.0.22#1` so a baseline bump cannot silently change the version |
| Helper tools vcpkg downloaded on first use | PowerShell 7.6.3, 7-Zip 26.02, msys2 packages (bash, make, autoconf, automake, libtool, m4, perl, pkgconf). All stored under `D:\tools\vcpkg\downloads` with hashes pinned in vcpkg's `scripts/vcpkg-tools.json` and `vcpkg_acquire_msys.cmake`. Only needed to build libsodium, not to use it. |
| Caches | Binary cache `D:\tools\vcpkg-cache` (set `VCPKG_DEFAULT_BINARY_CACHE`). Buildtrees and downloads under `D:\tools\vcpkg`. |

## Build tools (not redistributed, recorded for reproduction)

| Tool | Version found on the development machine (2026-10-12) |
|---|---|
| Windows | 11 Home 10.0.26200 |
| Visual Studio | Community 2026 18.10.3, MSVC toolset 14.51.36231 (compiler 19.51.36260), Windows SDK 10.0.26100.0 |
| CMake | 4.4.4 (Kitware installer on PATH); Android SDK CMake 4.1.2 for the Gradle build |
| Ninja | bundled with Android SDK CMake 4.1.2 (`cmake/4.1.2/bin/ninja.exe`), used by the Android presets |
| Android SDK | platforms android-35, build-tools 37.0.0 and 35.0.0 (the latter installed by AGP 8.8.2 on the first build, 2026-10-13), platform-tools 37.0.1, cmdline-tools 23.0.0, emulator 37.2.12, system image android-35 google_apis_playstore x86_64 rev 9 |
| Android NDK | 29.0.14206865 (r29), clang 21.0.0 based on r563880c, platforms 21 to 35. See `docs/DECISIONS.md` D-003. |
| JDK | OpenJDK 21.0.4 (Android Studio JBR) in Phases 1 to 3B; Temurin 21.0.12.1 from Stage 3C on (above) |
| Android Studio | AI-242.23726.103 (Ladybug Feature Drop 2024.2.2) through Stage 3B; AI-262 from 2026-10-19 (opens the AGP 8.9 project; its bundled JBR 25 does not run Gradle 8.11.1) |

## JDK for Gradle (added 2026-10-19, Stage 3C)

| | |
|---|---|
| Role | Runs Gradle 8.11.1 and the Android Gradle Plugin. Gradle 8.11.1 supports Java 17 to 23; the JDK bundled with the updated Android Studio (AI-262, JBR 25.0.3) is not supported and fails the build at startup. |
| Version | Eclipse Temurin JDK 21.0.12.1+1 (LTS), Windows x64, zip (no installer) |
| Source | https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip |
| Verification | SHA-256 `f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e`, equal to the checksum published by Adoptium beside the archive (`...zip.sha256.txt`) and to the value the Adoptium release API reports (checked 2026-10-19) |
| License | GPLv2 with Classpath Exception |
| Location | `D:\tools\jdk-21` (the archive's `jdk-21.0.12.1+1` directory), used through `JAVA_HOME` in the build scripts only; no PATH or registry change, no setting in the repository |

## Gradle and Android Gradle Plugin

| | |
|---|---|
| Gradle | 8.11.1 (Phase 2, D-012; Phase 1 used 8.10.2), distribution `gradle-8.11.1-bin.zip` from https://services.gradle.org/distributions/, SHA-256 `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6` (from `gradle-8.11.1-bin.zip.sha256`, 2026-10-13) pinned in `gradle-wrapper.properties` as `distributionSha256Sum` |
| Wrapper jar | `gradle-wrapper.jar` SHA-256 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`, matching https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256 (checked 2026-10-13) and the jar at tag `v8.11.1` of https://github.com/gradle/gradle. The jar, `gradlew`, and `gradlew.bat` are byte for byte the same as at tag `v8.10.2`, so the Phase 1 files were kept. |
| Gradle license | Apache 2.0 |
| Android Gradle Plugin | 8.9.3 (Phase 2, D-012; Phase 1 used 8.8.2). Requires Gradle 8.11.1 or later, JDK 17 or later, build-tools 35.0.0. Not supported by Android Studio Ladybug Feature Drop; Meerkat (2024.3.1) or later opens it. Fetched from Google Maven by Gradle, verified by Gradle's dependency resolution. License Apache 2.0. |
| Kotlin Gradle plugin | `org.jetbrains.kotlin.android` 2.1.10, pinned in `android/settings.gradle.kts`. License Apache 2.0. |
| AndroidX and JUnit | main source set: none. `androidTest` only: `androidx.test:runner:1.6.2`, `androidx.test.ext:junit:1.2.1` (Apache 2.0), `junit:junit:4.13.2` (EPL 1.0). Pinned in `android/app/build.gradle.kts`. |

Gradle downloads go to `GRADLE_USER_HOME=D:\Android\gradle`.

## Phase 2 dependency metadata check (2026-10-13)

Read from `META-INF/com/android/build/gradle/aar-metadata.properties` inside each AAR
downloaded from https://dl.google.com/android/maven2/ (the Google Maven repository Gradle
uses). The newest stable versions at the time of the check: CameraX 1.6.2 (1.7.0-alpha03 is
the newest pre-release), media3 1.11.1.

| Artifact | Version | minCompileSdk | minAndroidGradlePluginVersion |
|---|---|---|---|
| androidx.camera:camera-core, camera-camera2, camera-lifecycle, camera-view, camera-video | 1.6.2 | 36 | 8.9.1 |
| same five artifacts | 1.5.3 | 35 | 8.6.0 |
| same five artifacts | 1.4.2 | 34 | 1.0.0 |
| androidx.media3:media3-muxer, media3-common | 1.11.1 | 36 | 1.0.0 |
| androidx.media3:media3-muxer, media3-common | 1.10.1 | 36 | 1.0.0 |

Other facts from the 1.6.2 POMs and AAR contents: license Apache 2.0 for every artifact;
`camera-core` compiles against `kotlin-stdlib` 2.1.20 and pulls `kotlinx-coroutines` 1.9.0,
`exifinterface` 1.4.2, `lifecycle-livedata` 2.1.0, `tracing` 1.3.0, `concurrent-futures`
1.1.0, `listenablefuture` 1.0; `camera-camera2` pulls `camera-camera2-pipe` 1.6.2 and
`dagger` 2.59 (runtime); `camera-view` pulls `camera-video` 1.6.2, `appcompat` 1.1.0,
`fragment` 1.1.0, `viewfinder-core` 1.5.1; `camera-video` pulls `media3-muxer` 1.9.0,
`media3-container` 1.9.0, and `guava` 32.0.1-android. `camera-core` ships two small native
libraries per ABI (`libimage_processing_util_jni.so`, `libsurface_util_jni.so`). The AAR
manifests declare no permission and no feature; `camera-core` and `camera-camera2` add a
disabled, non-exported `MetadataHolderService`. `media3-muxer` has no native code, no
manifest entries beyond `minSdkVersion 23`, and depends only on `media3-common` and
`media3-container`.

## Phase 2 Android dependencies (added in Milestone D, 2026-10-13)

All fetched from Google Maven by Gradle and pinned in `android/app/build.gradle.kts`.
License Apache 2.0 for every artifact below.

| Dependency | Version | Configuration | Role |
|---|---|---|---|
| `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` | 1.6.2 | `implementation` | Photo capture (`ImageCapture` in memory), `PreviewView`, lifecycle binding |
| `androidx.activity:activity` | 1.10.1 | `implementation` | `ComponentActivity`, the `LifecycleOwner` CameraX binds to (`minCompileSdk` 35) |
| `androidx.camera:camera-video` | 1.6.2 | `debugImplementation` | Video prototype only: `VideoCapture`, `VideoOutput`, `Recorder` for the V2 probe |
| `androidx.media3:media3-muxer` | 1.11.1 | `debugImplementation` | Video prototype only: `FragmentedMp4Muxer` |
| `androidx.test:rules` | 1.6.1 | `androidTestImplementation` | `GrantPermissionRule` for the capture tests |

Transitive facts recorded from the resolved graph (`gradlew app:dependencies`):
`kotlin-stdlib` is raised from 2.1.10 to 2.1.20 by CameraX; `androidx.core:core` 1.13.0;
`lifecycle` 2.6.2; `camera-camera2-pipe` 1.6.2 with `dagger` 2.59 and `atomicfu` 0.28.0;
`profileinstaller` 1.4.0 and `startup-runtime` 1.1.1 (their manifest entries are removed,
D-013). In the release variant `camera-view` still pulls `camera-video` 1.6.2, which pulls
`media3-muxer`, `media3-container`, and `media3-common` 1.9.0 and `guava` 32.0.1-android;
the release variant therefore contains media3 classes it never calls. The debug APK is
17.6 MB against 4.1 MB in Phase 1, mostly CameraX, media3, guava, and their dependencies.

## Phase 3B dependency cleanup (2026-10-14)

`androidx.camera:camera-video` is excluded on the `camera-view` dependency and no longer
declared for the debug variant (P3-10, D-022). With it, media3 1.9.0 and guava left the
benchmark and release variants; `com.google.guava:listenablefuture` resolves to its real 1.0
artifact there. `androidx.media3:media3-muxer:1.11.1` stays `debugImplementation` for the
video prototype (`media3-common` and `media3-container` 1.11.1 with it); 1.11.1 is the newest
1.11.x on Google Maven (checked 2026-10-14). Classes defined per APK, from the dex
`class_defs`:

| APK | media3 | `androidx.camera.video` | Size before | Size after |
|---|---|---|---|---|
| debug | 626 | 445 then 0 | 19 036 751 | 17 608 236 (with the viewer code; 19 039 140 right after the removal) |
| benchmark | 557 then 0 | 443 then 0 | 13 822 758 | 10 578 545 (10 529 341 right after the removal) |
| release (unsigned) | 0 | 0 | not built in 3A | 10 570 353 (10 521 149 right after the removal) |

The viewer adds no dependency: `GridView`, `ImageDecoder`, `android.media.ExifInterface`, and
the gesture detectors are platform classes (D-026). The `ACCESS_NETWORK_STATE` merge rule of
D-013 stays because the debug variant still carries `media3-common`.

## Phase 3C Android dependencies (debug variant only, 2026-10-19)

| Dependency | Version | Configuration | Role |
|---|---|---|---|
| `androidx.media3:media3-extractor` | 1.11.1 | `debugImplementation` | `FragmentedMp4Extractor` for the key-frame investigation (reads the container the media3 way beside the framework extractor). Brings `media3-decoder` 1.11.1. Debug APK 17.6 to 28.6 MB; release and benchmark unchanged (0 media3 classes). |
| `androidx.media3:media3-exoplayer` | 1.11.1 | `debugImplementation` (Milestone D) | ExoPlayer for the playback probe (`VideoProbeActivity`, `GuardedSegmentDataSource`). Brings `media3-datasource`, `media3-database`, `media3-container` (resolved tree in `docs/PHASE3C_PROGRESS.md`). Its manifest declares `WAKE_LOCK`; the debug manifest removes it (`tools:node="remove"`), as the main manifest does for `ACCESS_NETWORK_STATE`. Release and benchmark unchanged (0 media3 classes). |

## Phase 3A Android dependencies (added in Milestone D, 2026-10-14)

Fetched from Google Maven by Gradle and pinned in `android/app/build.gradle.kts`. License
Apache 2.0.

| Dependency | Version | Configuration | Role |
|---|---|---|---|
| `androidx.test:core` | 1.6.1 | `androidTestImplementation` | `ActivityScenario` for the lock screen window test. Already in the resolved test graph through `androidx.test.ext:junit` 1.2.1; pinned explicitly. |

The PIN lock itself uses only libsodium (Argon2id, XChaCha20-Poly1305) and the platform
Keystore (HMAC-SHA256 key); no other library was added. `androidx.lifecycle:lifecycle-process`
2.6.2 was added for the background trigger and removed again in Milestone D: its
`ProcessLifecycleOwner` is attached by the `androidx.startup` provider that D-013 removes
from the manifest, so it never fired (D-020). The trigger counts started activities in
`PvApp` instead.

## Not used

- No TrueCrypt or VeraCrypt code (`docs/CONTAINER_FEASIBILITY.md`).
- No test framework beyond CTest (`docs/DECISIONS.md` D-002).
- No networking, analytics, or advertising libraries on Android.
