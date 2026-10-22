# Phase 3F test report

Date: 2026-10-21. Format per `PHASE3F_BRIEF.md` Section 11. Every result below comes from a
command that was run; nothing is inferred. "Pending" means the step has not run. Measured
values are marked; everything else is design reasoning.

## Status

**Complete.** Video recordings carry an AAC track written by the own writer: an
`AudioRecord` thread feeds the platform AAC encoder, encoded frames wait in a guarded
queue in the core until the video has reached their time, the segment writer puts them
behind the video of the fragment they fall in with the audio counted in samples, and the
index records the codec, channels, and rate. The clock the camera stamps its frames with
is found at the first frame (the Xiaomi uses the boot clock, four days off the monotonic
clock) and the audio is timed from `AudioRecord.getTimestamp` on it; on the Xiaomi five
claps measured 8, -7, 16, 5, and 37 ms between the hands meeting and the sound, inside
the target, and the developer confirmed them on time by ear and eye. Mute, a denied
microphone, a revoked permission, a lock, a taken microphone, encoder errors, and full
caps all end with a playable clip; old clips play silent. T1 20/20 Debug and Release; T2
77 tests, 0 failures, 1 skipped; both readers parse every segment of every new clip on
both devices and the kept 3E clip on the phone (the 3E gap closed). The system
microphone stays closed during a muted recording by the system's own records. The lock
cases, the recents card, the leak scan, and the viewer controls on the benchmark build
were done by the developer and confirmed by eye and ear; their device figures were not
captured because the phone dropped off adb during that session.

## Environment

| Component | Version |
|---|---|
| OS, Visual Studio, MSVC, CMake, vcpkg, NDK, libsodium, Gradle 8.11.1, AGP 8.9.3, Kotlin 2.1.10, compileSdk 36 / targetSdk 35 / minSdk 28, CameraX 1.6.2, media3 1.11.1, JDK Temurin 21 at `D:\tools\jdk-21` | As in `docs/PHASE3E_TEST_REPORT.md` (unchanged) |
| App version | 0.8.0 (`versionCode` 8) |
| Emulator | 37.2.12, `Pixel_8_API_35` (Android 15, x86_64), booted with its audio backend on (`-no-audio` removed), software GL, encoder `c2.android.avc.encoder`, AAC `c2.android.aac.encoder`, host microphone silent |
| Device | Xiaomi 2201116SG (veux), Android 13, MIUI 14, arm64-v8a, Adreno 619, `c2.qti.avc.encoder` and decoder, AAC `c2.android.aac.encoder` (software, the only AAC encoder on the device), Keystore `TRUSTED_ENVIRONMENT` |

## Architecture changes

`docs/PHASE3F_DESIGN.md`. Core: `segment.cpp` (the AAC track: `mp4a` with `esds`, a `trex`
per track, a second `traf` per `moof` with the audio counted in samples, the guarded
stage), `audio_queue.cpp` (new: encoded audio in guarded memory until the video reaches
its time), `pv.h` (code 32, `pv_segment_audio`, `pv_segment_writer_open_av`,
`add_audio`, `audio_stats`, `pv_audio_queue_*`), `pv_init.cpp`, `make_vectors --segment-av`,
fixtures `segment_av_one_fragment.bin`, `segment_av_audio.bin`, `segment_av.txt`. Android
main: `VideoRecorder` (the audio thread, the clock resolution, the queue moves, the
closing wait, the drain order, interruptions, hooks), `AudioQueue.kt` (new),
`SegmentWriter` (`openAv`, `addAudio`, `audioStats`), `CaptureActivity` (Mute, the
microphone request, the Microphone button, labels, `mute=`), `VideoActivity` (audio
attributes and focus, becoming noisy, the music volume stream, Mute, the silent flag),
`GlSplitter` (the first camera timestamp and the boot clock at its callback),
`VideoProbes.vaultAction`, `MainActivity` (`readback=`, `av_sync=`, `audio_caps`,
`mute`), `NativeBridge`, `jni_bridge.cpp`, the main manifest (`RECORD_AUDIO`), version
0.8.0. Debug: `AudioProbes.kt` (`audio_caps`), `Readback.kt` (the readback gate and the
A/V sync probe), `DebugVideoProbes`; the Milestone A trial recorder and writer sketch were
removed in Milestone D. Tests: `test_audio_queue` and the audio cases of
`test_segment_writer` (T1), `AudioCaptureTest` and `GoldenVectorTest.segmentAvVector` (T2).

## Audio format and sync method (checkpoint decisions, with the evidence)

| Decision | Choice | Evidence (`docs/PHASE3F_PROGRESS.md` Milestone A) |
|---|---|---|
| D-044 format | AAC-LC 48 kHz stereo 128 kbit/s, `CAMCORDER`, `c2.android.aac.encoder` | both devices offer only the software AAC encoders; every `AudioRecord` configuration initialized; the encoder keeps input times on its output |
| D-045 container | `mp4a` with `esds`, a second `traf` per `moof`, the audio counted in samples (timescale 48 000, 1024 ticks per frame), the video in microseconds, video then audio in each `mdat` | both readers and ExoPlayer took the audio track in microseconds and in samples on both devices; the developer chose samples at the checkpoint; the one-hour T1 test shows no drift |
| D-046 clock | the camera clock found at the first frame; audio from `getTimestamp` by frame count, moved by the boot-minus-monotonic offset when the camera uses the boot clock | the Xiaomi stamps on `BOOTTIME` (353 662 s off `MONOTONIC`, declared `REALTIME`); the emulator on `MONOTONIC` (declared `UNKNOWN`); the anchor on the first read on both; drift within 0.2 ms over 20 s |
| D-047 routing | encoded audio in a guarded core queue until the video reaches its time; a cut segment waits 500 ms for audio past its end; late audio into the next segment, never dropped; the final segment drains to the last frame | audio appended on arrival landed in the wrong segment at every cut on the emulator (the video encoder lags about a second there, 0.5 s on the phone); with the queue every clip had contiguous audio across its boundaries |
| D-048 permission and mute | asked once per process on entering video mode; Mute resets on entering video mode | T2 and the phone recordings |
| D-049 interruptions | the silenced callback, read errors, codec errors, the caps, all through `stop` | T2 hooks |
| D-050 playback | focus handling, pause when unplugged, the music volume stream, Mute, silent clips | T2 and the phone |

### A/V offset (measured)

| Device, clip | Method | Result |
|---|---|---|
| Xiaomi, trial clip of Milestone A (20 s, five claps, the hands small in the picture) | `av_sync`: the audio onset against the motion of the hands | audio 30 to 90 ms behind the video (the approach blip 60 to 100 ms before the onset, the motion stopped 20 to 90 ms before); on time by the developer's ear and eye |
| Xiaomi, feature-path clip of Milestone E (15 s, five claps, the hands large in the picture, motion peaks 37 to 42 against a floor of about 1) | `av_sync` with the pairing on the approach movement and the frame where the hands meet | **audio 8, -7, 16, 5, and 37 ms after the hands meet**, every clap within one frame (33 ms), inside the P3F-6 target of 45 ms ahead to 125 ms behind; **the developer: the claps land when the hands meet, the picture upright** |
| Emulator | the host microphone is silent (peak 10 of 32 767) | no event to measure; the probe runs (138 frames and 376 AAC frames decoded) |

Part of the lag is the frame itself: a camera timestamp marks the start of exposure and
a 30 fps frame spans 33 ms. No correction constant is applied (CP-8).

## Commands

```
# Windows core (T1), logs build/p3f/t1c-*.log
cmake --preset windows-x64-tests
cmake --build --preset windows-x64-tests-debug;   ctest --preset windows-x64-tests-debug      # 20/20
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --segment-av
cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release    # 20/20

# Android (T2), JAVA_HOME = D:\tools\jdk-21, logs build/p3f/build-*.log, t2-d*.log
cd android
gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease
gradlew.bat connectedDebugAndroidTest
gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.privatevault.app.AudioCaptureTest
python -I build\p3b\dexclasses.py <three apks>
aapt2 dump xmltree --file AndroidManifest.xml <apk>

# Emulator (build/p3f/emu-start-audio.sh, emu-3f.sh, emu-play.sh; the throwaway PIN of README.md)
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,audio_caps
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,record=6000,leak_scan
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,readback=latest,av_sync=latest
adb shell am start -n com.privatevault.app/.ScriptActivity --es actions unlock_pin=<pin>,open,mute=1,record=4000
adb shell pm revoke com.privatevault.app android.permission.RECORD_AUDIO     # then a scripted recording: audio=denied

# Xiaomi (build/p3f/phone-p3f.sh; the developer's throwaway PIN on the command line only)
phone-p3f.sh install-debug | actions <pin> "<list>" <log> | play <pin> <index hex>:seeks=6 <log> | install-benchmark
adb shell dumpsys audio | grep <package>      # active recording sessions during a muted recording and one with audio
adb shell appops get com.privatevault.app RECORD_AUDIO
```

## T1 (Windows)

| Test | Debug | Release |
|---|---|---|
| The 19 existing tests | pass | pass |
| `test_segment_writer`, audio cases (new): the audio boxes of the `moov` field by field (`mvhd` next track 3, the second `tkhd`, `mdhd` at 48 000, `hdlr` `soun`, `smhd`, `mp4a` with channels, sample size, rate, `esds` byte by byte, the second `trex`); three fragments with and without audio, the audio `traf` (`tfhd` track 2, `tfdt` by count, `trun` 0x301 with 1024 and the sizes, the data offset after the video bytes), the payload order, audio past the video end kept, the single-track writer refusing audio and keeping the 3E shape; audio before video (`SEGMENT_STATE`), times, the 512-frame bound, the stage bound and a smaller stage, the buffer room with the stage counted, the audio description arguments; **one hour of audio frames** (168 749 frames timed as the recorder times them, 1200 writers cut every 3 s: every audio `tfdt` equal to the first tick plus 1024 times the frames before it, every duration 1024, the tick clock within one sample of the microsecond clock in every segment); the two-track golden vector byte for byte with the offsets and hash of `segment_av.txt` and the audio frames file | pass | pass |
| `test_audio_queue` (new): push and move by time with base and until, the clamp of a late frame (written at time 0, never dropped), the discard before a time, the head time, the cap (`AUDIO_QUEUE_FULL`) and the slide of the used range, a writer error leaving the frame queued, arguments, error name 32 | pass | pass |
| Total | **20/20** (32.6 s) | **20/20** (12.9 s) |

0 build warnings in both configurations.

## T2 (emulator)

| Area | Result |
|---|---|
| The 72 existing tests | pass (`LockBoundKeyTest` skipped as before) |
| `GoldenVectorTest.segmentAvVector`: the Android writer over the video samples and the audio frames of the vector in the arrival order equals `segment_av_one_fragment.bin`, 4 audio samples, first tick 24 | pass |
| `AudioCaptureTest.recordWithAudioReadsAndPlays`: a recording with audio from the feature path (8 s, 3 segments at the emulator rate): index `audio_codec=1 channels=2 rate=48000`; every frame after the first video frame in a segment (pushed equal to moved plus before zero, 0 clamped, 0 closing timeouts); the readback gate on every segment (both readers two tracks, equal audio counts, the audio of middle segments ending within 50 ms of the video); the media3 audio sample count equal to the frames moved; playback with `audio/mp4a-latm channels=2 rate=48000` reported, no error, Mute setting the volume, a focus request from the test pausing the player; leak scan clean, no new non-object file | pass (14 to 17 s) |
| `AudioCaptureTest.mutedDeniedAndOldClipsPlaySilent`: a muted recording (`audio_codec` 0, one track per segment, silent playback with `audioFormat` null and no error); the permission reported missing (`denyAudio`, since a real revocation kills the process) giving `audio=denied` and `audio_codec` 0, then audio back; the 3D-muxer clip silent without error; the capture screen muted through `mute=1`, the toggle back on after a mode switch, `FLAG_SECURE` while recording with audio | pass (67 to 71 s) |
| `AudioCaptureTest.lockHooksCapsAndLateAudio`: a lock during a recording with audio (reason `lock`, the clip indexed with its audio, readback ok); `AUDIO_ERROR` and `AUDIO_TAKEN` each with their reason and an index; the 8 KiB queue (`AUDIO_QUEUE_FULL` at frame 24 with the video drain held for a second) and the 8 KiB stage (`AUDIO_FULL`) each keeping the clip; the audio thread held 5 s across a cut: the cut segment timed out, the late frames clamped into the next segment, every frame accounted for (pushed equal to moved plus before zero), readback ok on every segment | pass (40 to 50 s) |
| **The readback gate** (D-038 condition, now with audio): per segment of every recording in `AudioCaptureTest`, the framework `MediaExtractor` and media3 `FragmentedMp4Extractor` both report two tracks (one for silent clips), the video sync counts and media3 sync times equal to the index keys, the audio sample counts equal between the readers and their spans equal, the media3 audio start between 0 and 60 ms | pass on every segment of every run (`t2-d*-logcat.txt`) |
| Host-driven on the emulator (`D1` to `D4`): a scripted recording with audio (346 frames pushed, 333 moved, queue peak 75 at 12 fps), a muted one, the permission revoked by `pm revoke` while the app was installed (the process restarted by the revocation, the next recording `audio=denied`, `audio_codec=0`), the denied clip played silent, leak scan 0 findings | pass |
| Totals (`build/p3f/t2-d23-results.xml`, final code, fresh emulator) | **77 tests, 0 failures, 0 errors, 1 skipped**, 737 s; 14 readback segment results in the run log, every one `video=ok` with `audio=ok` or `audio=none` as recorded |
| Host-driven: merged manifests, class scan, APK sizes (`d-all.sh e1 skip`) | release and benchmark `CAMERA` and `RECORD_AUDIO` (plus the AGP-declared `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), 6 activities, `LockActivity` the only export; 2577 media3 classes, no muxer class outside debug, `androidx.camera.video` 0; debug 21 253 028, benchmark 15 882 043, release 15 873 851 bytes (benchmark 65 576 bytes over 3E) |

Three defects found by T2 on the way and fixed (`docs/PHASE3F_PROGRESS.md` Milestone D
and E): the recorder mixed the camera-clock segment start with clip-relative sample
times; the test teardown left the capture session closing for the photo tests that
follow (a latent order dependency, the 3E test ran last); the readback gate judged the
audio overrun of a segment, which the approved routing rule makes legitimate. Two test
expectations were made robust (the playing state waited for, the late-audio hold spanning
a cut).

## T3 (Xiaomi)

| Step | Result |
|---|---|
| Install the debug build 0.8.0 (developer's tap), vault and microphone permission kept | done |
| Readback on the kept 3E clip `9a9eba86...` (`E1-readback-3e.txt`) | **both readers parse all 4 segments**, sync counts 3, 3, 3, 1 and media3 sync times equal to the index, 101, 101, 102, 15 samples in both, one track each (the 3E gap closed; first shown in Milestone A with the trial build, repeated with the final build) |
| Record with audio, back camera, through the capture screen (`E2-record.txt`) | 1920x1080 stream, 1080x1920 encoded at 30 fps, the camera on `BOOTTIME`, the anchor on the first read, 3 segments with 159, 160, 121 audio frames (first ticks 19, 3, 12 ms), 462 frames pushed equal to 440 moved plus 22 before the first video frame, queue peak 10, 0 closing timeouts, encrypt 181 to 238 ms, leak scan 0 findings |
| Readback on it (`E3-readback.txt`) | **video ok and audio ok on all 3 segments**; the last segment's audio runs 200 ms past its video (the tail of D-047) |
| A/V sync, the clap clip (`E4-clap.txt`, `E5-avsync.txt`, `E6-play-clap.txt`) | 5 segments, 426 frames at 30 fps, 702 audio frames; readback ok on all 5; five claps measured 8, -7, 16, 5, 37 ms after the hands meet; playback first frame 571 ms, gaps 16, 33, 33, 16 ms, 0 dropped of 426, 0 underruns, six seeks on the key frame; **confirmed by the developer by ear and eye** |
| Record muted (`E7`, `E10`, `E12`) | `audio=muted`, 0 audio frames, 2 segments each |
| The system microphone indicator | the developer saw no privacy icon during either a muted recording or one with audio (MIUI 14 shows it briefly); the system's own records sampled instead (`E12-indicator.txt`): no recording session of the app in `dumpsys audio` during a 12 s muted recording and the `RECORD_AUDIO` app-op last used by the earlier recording with audio; during a 12 s recording with audio the app's `CAMCORDER` session at 48 kHz stereo listed, not silenced, and a 12 s app-op access. **A muted recording opens no microphone** |
| Record with the front camera (`E8-front.txt`, `E9-readback-front.txt`, `E14-play-front.txt`) | stream turn 270, `mirror=true`, 1080x1920 at 30 fps, audio on, 2 segments; readback ok on both; playback first frame 855 ms, 0 dropped of 163, 0 underruns; **the developer: upright, not mirrored** (the viewfinder mirrors, the recording does not, as the platform camera app records) |
| Install the benchmark build 0.8.0 (developer's tap), vault kept | done (after the cleanup, so one install) |
| Play with audio, mute, volume keys (the clap clip, by hand) | **the developer: sound, the volume keys change it, Mute silences and Unmute restores, the claps on the hands**; the phone's log buffer kept one benchmark playback with audio: first frame 302 ms, 157 frames, 0 dropped, 0 underruns (`B1-dump.txt`) |
| Old clips silent (one regression clip, the 3E clip, by hand) | **the developer: both play, no sound, no error** |
| Lock during a recording with audio (Home, screen off, MIUI camera, by hand) | **the developer: each returned to the lock screen and the clip was listed and played with sound**; the device figures (the lock finishing within the bound) were not captured, the phone dropped off adb during the session and its log buffer had rolled over when it came back; the same path with audio is measured in T2 (reason `lock`, the clip indexed with its audio, readback ok) |
| Recents card blank while recording and playing | **the developer: blank both times** |
| `leak_scan` (vault screen button, benchmark build) | **the developer: as expected** (the figure not captured, see above); the debug build's scan after the cleanup: 0 findings, 79 files (`E16-cleanup.txt`) |
| Cleanup through the app (`E16-cleanup.txt`, scripted on the debug build before the benchmark install; the three lock-test clips deleted by hand in the viewer afterwards) | 10 test clips deleted (`delete_video`: index `PV_OK`, 32 segments deleted, 0 missing, 0 failed), leak scan 0 findings, 74 objects: the 68 kept from 3E plus the clap clip `a79e82bd...` (5 segments, 1 index) |

## Readback results per clip

| Clip | Device, build | Segments | Readers |
|---|---|---|---|
| `9a9eba86...` (3E, no audio) | Xiaomi, trial build and final debug build | 4 | video ok on all, one track each |
| `0d73af65...`, `6857a612...`, `fea0dc3d...` (Milestone A trial clips) | Xiaomi, trial build | 4, 3, 6 | video ok and audio ok on all 13 |
| `92987803...`, `776f16e5...` (Milestone A trial clips, microseconds and samples) | emulator, trial build | 3, 3 | video ok and audio ok on all |
| `3e3427aa...` (feature path, audio) | Xiaomi, final debug build | 3 | video ok and audio ok on all |
| `a79e82bd...` (the clap clip) | Xiaomi | 5 | video ok and audio ok on all |
| `e451fd45...` (front camera, audio) | Xiaomi | 2 | video ok and audio ok on both |
| every recording of `AudioCaptureTest` | emulator, T2 | 1 to 4 each | video ok, audio ok or none as recorded, on every segment |

## Playback figures (measured)

| Measurement | Emulator (T2) | Xiaomi debug (`E6-play-clap.txt`) | Xiaomi benchmark |
|---|---|---|---|
| Audio | `audio/mp4a-latm channels=2 rate=48000`, `c2.android.aac.decoder`, 0 underruns | the same, 0 underruns | the same, 0 underruns (one clip in the log buffer) |
| First frame | 1045 to 1306 ms | 571 ms | 302 ms |
| Boundary gaps | 33 ms | 16, 33, 33, 16 ms | 16 ms |
| Dropped frames | 0 to 4 | 0 of 426 | 0 of 157 |
| Seeks | on the key frame | six on the key frame, 136 to 698 ms | by hand, not measured |
| Live buffers, PSS | one, 74 MiB | one | not captured |

## Plaintext locations

`docs/PHASE3F_DESIGN.md` Section 4.8, `docs/THREAT_MODEL.md` Section 12. Measured: PCM
passes through one direct 4 KiB buffer the thread zeroes at its end (and the pending
direct buffers before the anchor, zeroed after use); encoded audio goes from the codec
output buffer into the core queue in place (`nativeAudioQueuePush` on the direct buffer)
and from the queue into the writer stage and segment buffer, all guarded and wiped; the
queue peaked at 10 frames on the phone and 75 on the emulator; the Java heap holds no
PCM and no AAC frame in the feature path (the trial code that did was removed).

## Defects and decisions

| Item | What | Where |
|---|---|---|
| Audio in the wrong segment | audio appended on arrival landed in the next segment at every cut when the video encoder lagged; the queue rule (D-047) | Milestone A, `VideoRecorder`, `audio_queue.cpp` |
| The framework extractor ignores `tfdt` | times every track from 0 by the sum of its `trun` durations; the readback compares counts and spans | `Readback.kt`, CP-10 |
| Segment start times on two bases | the camera-clock start subtracted from clip-relative sample times refused every first sample | `VideoRecorder.drain`, Milestone D |
| A revoked permission kills the process | the instrumented test cannot revoke its own; a hook reports the permission missing and the real revocation runs from the host | `AudioCaptureTest`, `VideoRecorder.TestHooks.denyAudio` |
| The capture session left closing | the lock in a test teardown without the unlock that resumes it; the photo tests after it failed to reserve; the teardowns resume the session | `AudioCaptureTest`, `VideoCaptureTest` |
| The readback gate judged the audio overrun | a segment that took late audio runs late by the delay, by the approved rule; the gate reports the overrun and the test bounds it for ordinary recordings | `Readback.kt`, `AudioCaptureTest` |
| The 3E test counted one `traf` per `moof` | audio is on by default; the 3E test records video-only | `VideoCaptureTest` |
| A one-second `AudioRecord` buffer | a stall of the audio thread up to a second drops no PCM, so the frame count stays a clock | `VideoRecorder.AudioThread` |

Decisions: D-044 to D-050 approved at the checkpoint, D-045 and D-047 amended by the
developer (samples as the audio timescale; late audio never dropped).

## Known limits (assumptions, not measured)

- The A/V offset was measured with the frame-level probe on the Xiaomi only; the emulator
  microphone is silent. The 33 ms frame period bounds the method.
- The system microphone indicator was not seen by eye on MIUI 14 in either case; the
  system's recording records stand in for it.
- A stall of the audio thread longer than the one-second `AudioRecord` buffer drops PCM
  and shifts the frame-count clock late by the loss; the thread does nothing but read,
  encode, and push, and no such stall was seen outside the test hook.
- On API 28 a silenced microphone delivers zeros and the recording continues (the
  recording callback's silenced flag is API 29 and later); the test devices are API 33
  and 35.
- Headphone removal pausing playback relies on ExoPlayer's handling of the becoming-noisy
  broadcast and was not exercised (no headphones at hand).
- The audio of the final segment runs past its last video frame by the encoder lag
  (about 200 ms on the phone, up to a second on the emulator); the player plays it
  after the last frame.

## Acceptance checklist (brief Section 9)

| # | Criterion | Evidence |
|---|---|---|
| 1 | Phase 1, 2, 3A to 3E T1 and T2 suites pass | T1 20/20 Debug and Release; T2 77 tests, 0 failures, 1 skipped |
| 2 | Audio records by default; mute and denied permission give clips without audio and no crash | T2 muted and denied cases; the phone muted recordings; the emulator `pm revoke` run |
| 3 | Segments carry an AAC track written by the own writer; both readers parse every segment of every new clip with counts equal to the index, on emulator and Xiaomi | the readback table above |
| 4 | The 3E clip passes the same readback on the phone | `E1-readback-3e.txt` |
| 5 | A/V offset on the Xiaomi within the P3F-6 target, measured and confirmed by the developer | 8, -7, 16, 5, 37 ms after the hands meet; confirmed by ear and eye |
| 6 | Playback with audio, mute, focus loss, and old silent clips work; lock releases the player | T2 (focus, mute, the 3D clip, the player released); the benchmark build by hand (sound, volume keys, Mute, the regression and 3E clips silent) |
| 7 | Interruptions and lock stop the recording through the 3E path and keep the clip | T2 hooks, caps, and lock with audio; Home, screen off, and the MIUI camera by hand on the benchmark build, each clip kept and playing with sound |
| 8 | Front camera clip recorded and checked by eye | `E8-front.txt`, `E14-play-front.txt`; upright and not mirrored by eye |
| 9 | `FLAG_SECURE` while recording and playing; recents blank; `leak_scan` clean on both devices | T2 `FLAG_SECURE` (the viewer surface secure as 3D); leak scans 0 findings on both devices (the T2 scans count media file rows, directory rows of the test runner are named only); recents blank by eye |
| 10 | Release and benchmark manifests carry `CAMERA` and `RECORD_AUDIO` only, `LockActivity` the only export; APK size recorded | `d-all.sh e1`: `CAMERA`, `RECORD_AUDIO`, 6 activities, `LockActivity` exported alone; sizes in `docs/DEPENDENCIES.md` |
| 11 | Core changes additive; index v1 layout unchanged; all golden vectors pass | the single-track writer and the 3E vector unchanged; `test_golden`, `GoldenVectorTest` |
| 12 | Threat model updated: microphone, audio buffers, focus behaviour | `docs/THREAT_MODEL.md` Section 12 |
| 13 | Report separates demonstrated facts from assumptions | this report, "Known limits" |

## Phase 3 close-out

Phase 3 delivered the protected viewer in full: the PIN lock with the PIN-derived vault
key, attempt delays, and erase (3A); the photo gallery and viewer with delete (3B); the
video index format, the key-frame rule, and the playback probe (3C); video playback in
the gallery and viewer through ExoPlayer over guarded segment buffers with the orphan
sweep (3D); video capture as a feature with a live viewfinder, an own fragmented MP4
writer in the core, and one stop path (3E); and audio with its own clock handling,
queueing, and container track (3F). Every stage kept the version 1 object format, the
item header, the envelope providers, and index v1 unchanged.

Moves to Phase 4 to 6: the temporal interference experiment (Phase 4, untouched); the
EXIF strip before sharing and the streaming of segments as the file images the writer
produces (Phase 5); and for Phase 6: R8 for the release and benchmark variants, the
reserved item header bytes for a clip ID and the recovery of orphan segments after a
process kill, the allocating method of the transient capture array (a Studio step), a
check of the framework extractor limits found here (`tfdt` ignored, a track ended after
two fragments without its samples) against any reader a share target may use, and the
becoming-noisy pause with headphones at hand.

## Phone state after Stage 3F

Benchmark build 0.8.0 over the kept vault: 49 photos, 4 Phase 1 samples, the two
regression clips (`3c55a432...`, `39b46d41...`, wrapper path), the 3E clip (`9a9eba86...`,
standard path, no audio), and the 3F clap clip (`a79e82bd...`, 15 s, 5 segments, audio):
74 objects after the scripted cleanup; the three lock-test clips the developer recorded
on the benchmark build were deleted by hand in the viewer. The microphone permission is
granted. The emulator holds the debug build 0.8.0 with a throwaway-PIN vault.

## Files changed

Core: `src/vault_core/include/pv/pv.h`, `src/vault_core/src/segment.cpp`,
`src/vault_core/src/audio_queue.cpp` (new), `src/vault_core/src/pv_init.cpp`,
`src/vault_core/CMakeLists.txt`, `tests/core/CMakeLists.txt`, `tests/core/test_segment_writer.cpp`,
`tests/core/test_audio_queue.cpp` (new), `tests/tools/make_vectors.cpp`,
`tests/fixtures/v1/segment_av_one_fragment.bin`, `segment_av_audio.bin`, `segment_av.txt` (new),
`tests/fixtures/v1/README.md`. Android main: `build.gradle.kts`, `AndroidManifest.xml`,
`jni_bridge.cpp`, `NativeBridge.kt`, `SegmentWriter.kt`, `AudioQueue.kt` (new),
`VideoRecorder.kt`, `CaptureActivity.kt`, `VideoActivity.kt`, `GlSplitter.kt`,
`VideoProbes.kt`, `MainActivity.kt`, `LeakScan.kt`. Android debug: `DebugVideoProbes.kt`,
`AudioProbes.kt` (new), `Readback.kt` (new). Tests: `AudioCaptureTest.kt` (new),
`GoldenVectorTest.kt`, `VideoCaptureTest.kt`. Docs: `PHASE3F_DESIGN.md`, `PHASE3F_PROGRESS.md`,
this report, `DECISIONS.md` (D-044 to D-050), `THREAT_MODEL.md` Section 12, `ROADMAP.md`,
`DEPENDENCIES.md`. Helper scripts and logs under `build/p3f/` are not committed. The
suggested commit message is in `docs/PHASE3F_PROGRESS.md`.
