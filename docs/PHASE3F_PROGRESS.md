# Phase 3F progress

Working log for Claude Code sessions. Updated after each milestone. Newest state first.
Brief: `PHASE3F_BRIEF.md` (repository root, supplied by the developer).

## Current state (2026-10-22)

Stage 3F complete: Milestones A to E done; Phase 3 complete. T1 **20/20** Debug and
Release; T2 **77 tests, 0 failures, 1 skipped** on the emulator with the final code
(`build/p3f/t2-d23-results.xml`); lint 0 errors; release and benchmark at 2577 media3
classes with `CAMERA` and `RECORD_AUDIO`; version 0.8.0 (`versionCode` 8). T3 on the
Xiaomi done: the debug steps scripted with the developer's taps, the benchmark steps by
hand (the phone dropped off adb during that session, so their device figures were not
captured; the developer confirmed each by eye and ear and chose not to repeat them).
Report in `docs/PHASE3F_TEST_REPORT.md`. All changes uncommitted; the suggested commit
message is at the end of this file. The phone holds the benchmark build 0.8.0 and 74
objects (49 photos, 4 samples, the two regression clips, the 3E clip, the 3F clap clip);
the emulator holds the 0.8.0 debug build.

## Milestone E log (2026-10-21)

1. Xiaomi, debug build 0.8.0 (developer's install tap, the vault and the microphone
   permission kept; the PIN on the command line only; logs `build/p3f/phone/E*.txt`):
   - `readback=9a9eba86...` on the kept 3E clip with the final build (`E1-readback-3e.txt`):
     both readers parse all 4 segments, sync counts 3, 3, 3, 1 and media3 sync times
     equal to the index, 101, 101, 102, 15 samples in both readers, one track each.
   - `record=10000` through the capture screen with audio on (`E2-record.txt`): 1920x1080
     stream, `c2.qti.avc.encoder` at 1080x1920, 30 fps, 280 camera frames to both
     outputs, the camera on `BOOTTIME` (the first timestamp 111 ms before the callback
     on the boot clock, 353 662 s off the monotonic clock), the anchor on the first
     read, 3 segments with 159, 160, 121 audio frames (first ticks 910, 148, 565: 19, 3,
     12 ms), 462 frames pushed equal to 440 moved plus 22 before the first video frame,
     queue peak 10, 0 closing timeouts, encrypt 181 to 238 ms, index
     `audio_codec=1 channels=2 rate=48000`, leak scan 0 findings (93 files). Readback
     (`E3-readback.txt`, run on its own: an action after `record=` stays on the capture
     screen): **video ok and audio ok on all 3 segments**; the last segment's audio runs
     to 2572 ms against a video end of 2370 ms, the tail of D-047.
   - The clap clip, `record=15000` (`E4-clap.txt`): 5 segments, 426 frames at 30 fps, 702
     audio frames (679 moved, 23 before zero), queue peak 10. Readback
     (`E5-avsync.txt`): ok on all 5 segments. `av_sync` with the tightened pairing, the
     hands large in the picture (motion peaks 37 to 42 against a floor of about 1): five
     claps, **audio 8, -7, 16, 5, and 37 ms after the hands meet**, every one within one
     frame, inside the P3F-6 target. Playback with the scripted seeks (`E6-play-clap.txt`):
     `c2.android.aac.decoder`, stereo 48 kHz, every segment on the standard path, first
     frame 571 ms, gaps 16, 33, 33, 16 ms, 0 dropped of 426, 0 underruns, six seeks on
     the key frame (136 to 698 ms). **The developer: the claps land when the hands meet,
     the picture is upright.**
   - Muted, `mute=1,record=6000` (`E7-muted.txt`, `E10-muted2.txt`, `E12-muted3.txt`):
     `audio=muted`, 0 audio frames, 2 segments each. The front camera, `flip,record=6000`
     (`E8-front.txt`): stream turn 270, `mirror=true`, 1080x1920 at 30 fps, audio on, 2
     segments, 277 frames pushed; readback ok on both segments (`E9-readback-front.txt`).
   - The system microphone indicator: the developer saw no icon during either a muted
     recording or one with audio (MIUI 14 shows the privacy indicator briefly), so the
     system's own records were sampled instead (`E12-indicator.txt`): during a 12 s
     muted recording `dumpsys audio` listed no recording session of the app and the
     `RECORD_AUDIO` app-op's last access was the earlier recording with audio; during a
     12 s recording with audio it listed the app's `CAMCORDER` session at 48 kHz stereo,
     not silenced, and the app-op showed a 12 s access. **A muted recording opens no
     microphone.**
2. Emulator host-driven checks on the final debug build (`D1` to `D4`, a fresh vault):
   a recording with audio (346 frames pushed, 333 moved, queue peak 75 at 12 fps), a
   muted one (`audio=muted`), the permission revoked by `pm revoke` while the app was
   installed and a recording after it (`audio=denied`, `audio_codec=0`, the process
   restarted by the revocation), the denied clip played silent (`audio track=none`, 0
   errors); leak scan 0 findings.
3. Full T2 on the Milestone D code (`t2-d6`): 76 tests, 5 failures: the four photo tests
   that run after `AudioCaptureTest` found the capture session still closing (the test's
   teardown locks and erases without the unlock that resumes it; the 3E test had the same
   teardown and ran last), and the 3E `VideoCaptureTest` counted one `traf` per `moof` with
   audio now on by default. Fixes in the tests: the teardowns resume the capture session;
   the 3E test records video-only (its checks cover the single-track shape). Rerun of the
   five classes (`t2-d7`): 18 tests, 1 failure, the readback gate refusing a middle
   segment whose audio runs past its video by the delay of frames clamped into it (the
   late-audio case); the gate now reports the overrun and the test bounds it for ordinary
   recordings. Further reruns on the way (`t2-d8` to `t2-d21`): the playing state waited
   for instead of asserted at an instant; the late-audio hold lengthened to 5 s so it
   spans a cut; the queue-cap case given a 96 KiB queue and an 8 s hold of the video
   drain so it fills during the hold and not during a slow camera start; the framework
   extractor found to end a track after two consecutive fragments without its samples
   (pinned by `GoldenVectorTest.readersOnAudioStartingInSecondFragment`: audio starting
   in the second fragment read, in the third not; media3 reads both), the gate accepting
   that shape in the late-audio case; a 3 s settle on each side of the capture-screen
   scenario change after the emulator camera refused to open while the previous session
   was closing (`ERROR_CAMERA_FATAL_ERROR`); the `AudioRecord` buffer raised to one
   second. 2026-10-22, after the overnight pause: the full suite (`t2-d22`) failed six
   leak scans on two MediaStore directory rows the test runner creates
   (`Android/media/<package>/additional_test_output`), indexed by the media scanner on
   that fresh boot before the tests ran; the scan now counts media file rows only and
   names directory rows. Final full suite (`t2-d23`): **77 tests, 0 failures, 0 errors,
   1 skipped**, 737 s; 14 readback segment results in the log, all ok.
4. Cleanup on the debug build before the benchmark install (`E15-gallery.txt`,
   `E16-cleanup.txt`): 14 clips listed; 10 deleted through the app (every status
   `PV_OK`, 32 segments, 0 missing, 0 failed); leak scan 0 findings, 79 files; 74 objects.
5. Final build (`d-all.sh e1 skip`, `build-e1.log`): BUILD SUCCESSFUL, lint 0 errors (95
   warnings); manifests `CAMERA` and `RECORD_AUDIO`, 6 activities, `LockActivity` the only
   export; APK sizes debug 21 253 028, benchmark 15 882 043, release 15 873 851 bytes.
6. Benchmark build 0.8.0 installed (developer's tap), by hand: the clap clip with
   sound, the volume keys, Mute and Unmute, the claps on the hands; a regression clip and
   the 3E clip silent without error; Home, screen off, and the MIUI camera during
   recordings with audio, each clip kept and playing with sound; the recents card blank
   while recording and playing; the leak scan as expected; the three lock-test clips
   deleted in the viewer. The host log capture got nothing: the phone dropped off adb as
   the session began and its log buffer had rolled over when it came back, keeping one
   benchmark playback with audio (first frame 302 ms, 157 frames, 0 dropped, 0 underruns)
   and a background lock (`B1-dump.txt`). The developer declined a repeat.
7. Docs: `docs/PHASE3F_TEST_REPORT.md` (new), `docs/PHASE3F_DESIGN.md` (status
   implemented, the reader limit, the one-second buffer), `docs/DECISIONS.md` (D-044 to
   D-050 approved, D-045 and D-047 amended), `docs/THREAT_MODEL.md` Section 12 approved,
   `docs/ROADMAP.md` (3F and Phase 3 complete), `docs/DEPENDENCIES.md`, this file. Git
   read-only; nothing committed.

## Milestone D log (2026-10-21)

1. Core: `pv_segment_audio.stage_bytes` (0 for the default), so a small stage can be
   asked for by a test; one more T1 check.
2. JNI (`jni_bridge.cpp`): `nativeSegmentOpenAv`, `nativeSegmentAddAudio`,
   `nativeSegmentAudioStats`, `nativeAudioQueueOpen`, `Push`, `DiscardBefore`, `Move`,
   `Stats`, `Close`; `NativeBridge` code 32 and the bounds; `SegmentWriter.openAv`,
   `addAudio`, `audioStats`; `AudioQueue.kt` (new).
3. Main: `VideoRecorder` (the audio thread of the Milestone A trial moved in: AudioRecord
   in 1024-frame chunks through one direct buffer, the AAC encoder, the anchor from the
   first `getTimestamp`, every output buffer pushed into the core queue in place; the
   camera clock resolved at the first frame; the queue moved behind every video sample;
   the cut segment waiting for audio past its end or 500 ms; late frames clamped into
   the next segment; the final segment taking the whole queue; the recording callback
   for a silenced microphone, read errors, codec errors, and the caps through
   `stop`; the index audio fields; the result line with the audio counts; hooks
   `AUDIO_ERROR`, `AUDIO_TAKEN`, `audioQueueCap`, `audioStageCap`, `audioDelayMs`,
   `videoDelayMs`, `denyAudio`); `CaptureActivity` (the Mute toggle reset on entering
   video mode, the one-per-process microphone request, the Microphone button with the
   settings path, the labels, `mute=<0|1>`); `VideoActivity` (media audio attributes with
   focus handling, pause when headphones are unplugged, the music volume stream, the
   Mute button, the silent flag from the index); the main manifest declares
   `RECORD_AUDIO` (the debug one no longer); version 0.8.0 (`versionCode` 8). The trial
   recorder and the Kotlin writer sketch removed (CP-9); `audio_caps`, `readback`, and
   `av_sync` stay as debug probes; the readback gate lets the last segment carry audio
   past its video end (D-047).
4. Tests: `AudioCaptureTest` (new, three methods) and `GoldenVectorTest.segmentAvVector`.
5. Build (`d-all.sh d1 skip`, `build-d1.log`): BUILD SUCCESSFUL, lint 0 errors (95
   warnings, the six new ones the `SetTextI18n` kind on the new button labels). Class
   scan: debug 2637 media3 classes, benchmark and release 2577, no muxer class outside
   debug. Merged manifests: `CAMERA` and `RECORD_AUDIO` on every variant, 6 activities in
   benchmark and release, `LockActivity` the only export. APK sizes: debug 20 534 304,
   benchmark 15 882 043, release 15 873 851 bytes (about 66 KB over 3E).
6. T2 class runs (`t2-d1` to `t2-d5`, logcat `t2-d*-logcat.txt`): the golden vector test
   passed on the first run; three defects found and fixed on the way: the recorder mixed
   the camera-clock segment start with clip-relative sample times (every recording
   failed at its first sample); revoking the microphone permission from the test kills
   the process (Android does that for any revocation), so the denied case uses a
   recorder hook and the real revocation runs from the host; the readback gate refused
   the final segment for its audio past the video end. The queue-cap case needed the
   video drain held for a second to fill 8 KiB on a fast emulator run (`videoDelayMs`).
   Final class run (`t2-d5`): **3 tests, 0 failures**, 124 s. Figures: a recording with
   audio of 7.7 s gave 3 segments, 396 frames pushed, 390 moved, 6 before the first
   video frame, queue peak 28, both readers ok on every segment; playback with
   `audio/mp4a-latm channels=2 rate=48000`, 0 underruns, the pause on a focus request;
   the muted and denied clips `audio_codec=0`, silent playback without error, the old
   3D clip the same; the lock during a recording with audio finished with 2 segments
   indexed; `AUDIO_ERROR`, `AUDIO_TAKEN`, `AUDIO_QUEUE_FULL` (at frame 24 with the 8 KiB
   queue), and `AUDIO_FULL` (the 8 KiB stage) each with an index; the late-audio case:
   2 closing timeouts, 53 frames clamped into the following segments, 526 frames pushed
   equal to 517 moved plus 9 before zero, readback ok on all 4 segments; the capture
   screen muted through `mute=1`, the toggle back on after a mode switch, `FLAG_SECURE`
   while recording with audio.
7. Host-driven emulator checks and the full T2: pending.

## Milestone C log (2026-10-21)

1. Core: `src/vault_core/src/segment.cpp` (the audio track: `pv_segment_writer_open_av`
   with `pv_segment_audio`, the `mp4a` entry with `esds`, a `trex` per track, the audio
   `traf` with `tfdt` by count and a `trun` of 1024-tick durations and sizes, the
   guarded stage copied behind the video samples at fragment close, the moof slot grown
   by the audio traf bound; `pv_segment_writer_add_audio`, `pv_segment_writer_audio_stats`;
   `pv_segment_writer_open` unchanged over `open_av`), `src/vault_core/src/audio_queue.cpp`
   (new: `pv_audio_queue_open`, `push`, `discard_before`, `move` with the clamp for a
   late frame, `stats`, `close`), `pv.h` (code 32, the bounds, the declarations),
   `pv_init.cpp` (the error name), `CMakeLists.txt`.
2. T1: `tests/core/test_segment_writer.cpp` gains `audio_fragments` (the audio boxes of
   the moov field by field, three fragments with and without audio, the payload order,
   the tfdt by count, audio past the video end kept, the single-track writer refusing
   audio and keeping the 3E shape), `audio_rules` (audio before video, times, the
   512-frame bound, the stage bound, the buffer room with the stage counted, the
   audio description arguments), `audio_one_hour` (168 749 frames timed as the recorder
   times them through 1200 writers cut every 3 s: every audio tfdt equal to the first
   tick plus 1024 times the frames before it, every duration 1024, the tick clock
   within one sample of the microsecond clock in every segment), `golden_av` (the
   two-track vector byte for byte, the offsets and hash of `segment_av.txt`, the audio
   frames file). `tests/core/test_audio_queue.cpp` (new): push and move by time, the
   clamp, the discard, the head time, the cap and the slide of the used range, a
   writer error leaving the frame queued, arguments, error name 32. Two test defects on
   the way (the second batch of frames restarting at time 0, which the writer refuses;
   a byte check across fragments), both in the test. `make_vectors --segment-av` writes
   `tests/fixtures/v1/segment_av_one_fragment.bin` (3291 bytes), `segment_av_audio.bin`,
   `segment_av.txt`; the fixtures README records them. Debug **20/20**, Release
   **20/20**, 0 warnings (`build/p3f/t1c-*.log`).

   ```
   cmake --preset windows-x64-tests
   cmake --build --preset windows-x64-tests-debug;   ctest --preset windows-x64-tests-debug     # 20/20, 32.6 s
   build\windows-x64-tests	ests\core\Debug\make_vectors.exe testsixtures1 --segment-av
   cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release   # 20/20, 12.9 s
   ```

## Milestone B log (2026-10-21)

`docs/PHASE3F_DESIGN.md` written: the Milestone A results on both devices (what the
devices offer, the trial clips, the first A/V offset, the two findings: audio must wait
for the video, the framework extractor ignores `tfdt`); the decisions P3F-2 to P3F-8
confirmed with the clock change on P3F-6; the capture screen (permission, mute, labels),
the recording pipeline (the camera clock found at the first frame, the audio anchor,
the guarded audio queue in the core), the writer additions and bounds, the index, the
stop path with the audio drain and the interruption table, playback, the core ABI
(`pv_segment_writer_open_av`, `add_audio`, `pv_audio_queue_*`, code 32), plaintext
locations, failure handling, the test plan, manifests and version 0.8.0, threat model
deltas, checkpoint items CP-1 to CP-10, seams. `docs/DECISIONS.md` D-044 to D-050
(proposed). `docs/THREAT_MODEL.md` Section 12 (proposed). `docs/ROADMAP.md` row 3F.
Approved by the developer 2026-10-21 with two changes (CP-2: the audio track in samples
at 1024 ticks per frame, a one-hour T1 drift test; CP-4: late audio goes into the next
segment and is never dropped, only the final segment drains to the last sample, a test
case) and three rules recorded (a full queue or stage stops through the 3E path and keeps
the clip, with a T2 hook test; mute resets to on whenever video mode opens; muted
recordings open no `AudioRecord`, T3 watches the indicator). Design, D-045, D-047, and
the threat model updated.

## Milestone A log (2026-10-21)

1. `git status`: clean apart from the untracked brief `PHASE3F_BRIEF.md` (at the
   repository root, not under `docs/`). Branch `main` at `3c5bd3b`. Both devices listed
   at the start; the Xiaomi dropped off adb a few minutes later.
2. Helper scripts (not committed, `build/p3f/`): `t1.sh`, `d-all.sh`, `t2-only.sh`,
   `t2-class.sh`, `emu-start.sh`, `emu-start-audio.sh` (the emulator with its audio
   backend on, `-no-audio` removed), `emu-3f.sh`, `emu-play.sh`, `phone-p3f.sh` (the 3E
   scripts with the log directory changed). Logs under `build/p3f/`.
3. T1 on the unchanged tree (`t1.sh a`, logs `t1a-*.log`): Debug **19/19**, Release
   **19/19**, 0 warnings.

   ```
   cmake --preset windows-x64-tests
   cmake --build --preset windows-x64-tests-debug;   ctest --preset windows-x64-tests-debug     # 19/19, 45.4 s
   cmake --build --preset windows-x64-tests-release; ctest --preset windows-x64-tests-release   # 19/19, 16.0 s
   ```

4. T2 on the unchanged tree (`d-all.sh a`, logs `build-a.log`, `t2-a.log`,
   `t2-a-results.xml`): build of all variants, lint 0 errors 89 warnings; **72 tests,
   0 failures, 0 errors, 1 skipped** (`LockBoundKeyTest`), 583 s. Class scan: debug 2637
   media3 classes, benchmark and release 2577, `androidx.camera.video` 0. Merged
   manifests: `CAMERA` plus the AGP-declared `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`;
   debug 8 activities, benchmark and release 6. APK sizes: debug 21 024 156, benchmark
   15 816 467, release 15 808 275 bytes.

   ```
   $env:JAVA_HOME = "D:\tools\jdk-21"; cd android
   gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug assembleBenchmark assembleRelease
   gradlew.bat connectedDebugAndroidTest
   ```

5. Trial code, debug source set (`AudioProbes.kt`, `AudioTrial.kt`, `Fmp4TrialWriter.kt`,
   `Readback.kt`), with small main-set hooks: `GlSplitter` keeps the first camera
   timestamp and the boot-clock reading at its callback; `VideoProbes.vaultAction` (a
   seam for vault-screen probes); `MainActivity` routes `readback=` and `av_sync=` to it
   and hands `audio_caps` and `audio_trial` to the capture screen; `CaptureActivity`
   asks for `RECORD_AUDIO` before an `audio_` action and waits for the answer;
   `VideoActivity` logs the audio format, the audio decoder, and underruns and carries
   them in its report; the debug manifest declares `RECORD_AUDIO` (release and benchmark
   unchanged until the checkpoint). Scripted actions:
   - `audio_caps` (capture screen): AAC encoders and their ranges, the timestamp source
     of each camera, every `AudioRecord` configuration of `CAMCORDER` and `MIC` at
     48 and 44.1 kHz mono and stereo (a 300 ms read, the frames delivered, the peak, and
     `getTimestamp` on both time bases), and a 20-frame AAC encode of a synthetic tone.
   - `audio_trial=clip=<ms>:ch=<1|2>:rate=<hz>:bitrate=<bps>:ts=<us|rate>:src=<camcorder|mic>`
     (capture screen): the 3E camera route (effect split, H.264 at the stream size with
     the viewfinder matrix), an `AudioRecord` thread feeding the AAC encoder in
     1024-frame chunks timed from the first `getTimestamp` anchor, the camera clock
     resolved from the first frame (the clock whose reading at the callback is nearest
     the camera timestamp), encoded audio queued until the video has reached its time,
     then routed to the segment it falls in (a segment whose video ended waits up to
     500 ms for its last audio); the Kotlin two-track writer sketch; segments encrypted
     as video segment items; index v1 with the audio fields.
   - `readback=<index hex|latest>` (vault screen): every segment decrypted and read by
     the framework `MediaExtractor` and media3 `FragmentedMp4Extractor`, per track the
     sample count, sync count, first and last time; video checked against the index
     keys, audio on equal counts and spans between the readers. The 3E carry-over gate.
   - `av_sync=<index hex|latest>` (vault screen): every video frame decoded and the
     motion between consecutive frames measured on a luma grid, the audio decoded to PCM
     and its loudest millisecond found; the offset between the audio onset and the
     motion peak and the frame after it where motion settles.
   Build (`d-all.sh trial skip`, `build-trial.log`): BUILD SUCCESSFUL, lint 0 errors
   (two lint rounds on the way: a nullable receiver and `MissingPermission` on the
   `AudioRecord` constructor, both fixed); release and benchmark manifests `CAMERA` only,
   2577 media3 classes; debug 21 141 320 bytes with `RECORD_AUDIO`.
6. Emulator (`Pixel_8_API_35`, restarted without `-no-audio` by `emu-start-audio.sh`,
   boot 36 s; fresh throwaway-PIN vault through `emu-3f.sh install`, `RECORD_AUDIO`
   granted by `pm grant`; logs `A1-caps.txt` to `A10-play-rate.txt`):
   - `audio_caps` (`A1-caps.txt`): AAC encoders `c2.android.aac.encoder` and
     `OMX.google.aac.encoder` (software), rates 8 to 48 kHz, up to 6 channels, 8 to
     510 kbit/s, profiles LC, HE, HE v2, LD, ELD. Cameras: back (id 10)
     `SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN`, front (id 1) `REALTIME`. Every `AudioRecord`
     configuration initialized and delivered frames (peak 0 to 11 of 32767: the host
     microphone is silent or absent); `getTimestamp` returned 0 on both time bases every
     time, the read position 25 to 110 ms behind the clock (550 ms on the first open).
     AAC encode of 48 kHz stereo and mono at 128 kbit/s: `csd-0` 2 bytes (`1190`,
     `1188`), 20 frames in, 20 out of 341 to 342 bytes, output times equal to the input
     times (21 333 us steps).
   - `audio_trial=clip=10000` first build (`A2-trial.txt`): stream 1280x720 turn 90, the
     encoder at 720x1280, camera clock measured `MONOTONIC` (first camera timestamp 33 ms
     before the callback on both clocks, boot minus monotonic 0 on the emulator),
     declared `UNKNOWN`; audio anchor from `getTimestamp` on the first read; first audio
     sample at clip 8 ms; 3 segments, 138 frames at 14 fps, index with
     `audio_codec=1 channels=2 rate=48000`; leak scan 0 findings. **Defect:** segment 0
     held audio to 4659 ms against a video end of 3531 ms and dropped 53 samples past its
     end: the emulator video encoder lags real time by about a second, so audio arrived
     before the key frame that cut the segment and was appended to the open segment.
     Fix: encoded audio is queued until the video has reached its time (the rule the
     feature takes, design 4.2).
   - Readers on that clip (`A3-readback.txt`): both see two tracks in every segment;
     video sync counts and media3 sync times equal to the index keys; audio sample
     counts equal between the readers (166, 112, 98); **the framework `MediaExtractor`
     ignores `tfdt` and times every track from 0 by the sum of its `trun` durations**
     (audio first 0, last 3 520 000 against media3 8534 to 3 528 534), measured, so the
     readback compares the readers on counts and spans and takes times from media3.
   - Playback of that clip (`A5-play.txt`): `c2.android.aac.decoder`, format
     `audio/mp4a-latm channels=2 rate=48000`, every segment on the standard path, 2
     transitions, 0 underruns, 0 errors, first frame 1182 ms, four seeks on the key
     frame (in-segment 1126 and 4785 ms), one live buffer.
   - `av_sync=latest` (`A4-avsync.txt`): 138 frames decoded by `c2.goldfish.h264.decoder`,
     376 AAC frames by `c2.android.aac.decoder`; the audio peak is 10 of 32767 (silence),
     so the offset line means nothing on the emulator; the probe runs.
   - `audio_trial=clip=10000` with the queue (`A6-trial.txt`): 3 segments; audio 15 to
     3855 ms in segment 0 (video end 3862), 14 to 3449 ms in segment 1 (end 3469), 0 to
     2497 ms in segment 2, contiguous across the boundaries; 83 samples past the last
     video frame dropped (the audio thread runs on while the emulator encoder drains its
     backlog); audio queue peak 84 frames (1.8 s, the encoder lag); 13 samples before
     the first video frame dropped; 557 reads, 0 short, 0 overruns. Readback
     (`A7-readback.txt`): **video ok and audio ok on all 3 segments**, media3 audio
     first 15 674 us in segment 0, 14 879 in segment 1, 882 in segment 2.
   - `audio_trial=clip=8000:ts=rate` (the audio track in samples, `A8-trial-rate.txt`,
     `A9-readback-rate.txt`, `A10-play-rate.txt`): readback ok on all 3 segments, media3
     audio times within one frame of the microsecond run; playback with audio, 0
     underruns, 0 dropped. Both timescales work; the microsecond one stays (P3F-4).
7. Xiaomi 2201116SG (reattached by the developer; the debug build 0.7.0 with the trial
   code installed over the kept vault with the developer's tap, twice: the second
   install carried the peak-pairing version of `av_sync`; the PIN on the command line
   only; logs `build/p3f/phone/P*-*.txt`):
   - **3E gate closed** (`P1-readback-3e.txt`, `readback=latest` before any 3F clip
     existed, so the newest index was the kept 3E clip `9a9eba86b4758f1460ab6d2c32cfdd8c`,
     4 segments, 1080x1920, `audio_codec=0`): both readers parse every segment; the
     framework `MediaExtractor` sync counts 3, 3, 3, 1 and the media3 sync counts and
     sync times equal to the index keys of each segment; samples 101, 101, 102, 15 in
     both readers; the first frame of every segment decoded. `video_ok=4`.
   - `audio_caps` (`P2-caps.txt`, the microphone granted by the developer's tap on the
     runtime prompt): the same two software AAC encoders as the emulator
     (`c2.android.aac.encoder`, `OMX.google.aac.encoder`; no hardware AAC encoder);
     cameras 0 (back) and 1 (front) both `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`,
     hardware level 3; every `AudioRecord` configuration initialized and delivered
     frames (first data after 132 to 156 ms, room-noise peaks 67 to 693), `getTimestamp`
     0 on both time bases, the read position 10 to 23 ms behind the clock, no short
     reads. AAC encode at 48 kHz 128 kbit/s: `csd-0` `1190` (stereo) and `1188` (mono),
     the first output times equal to the input times, and 22 frames out for 20 in: the
     phone build of the encoder flushes two extra frames at the end of the stream.
   - Trial 1, `audio_trial=clip=12000` (`P3-trial.txt`): stream 1920x1080 turn 90,
     `c2.qti.avc.encoder` at 1080x1920 and 8 Mbit/s; **the camera stamps frames on the
     boot clock**: the first camera timestamp was 81 ms before the callback on
     `BOOTTIME` and 353 662 385 ms (about four days of suspend) off `MONOTONIC`, declared
     source `REALTIME`, so the audio offset was set to boot minus monotonic; the audio
     anchor from `getTimestamp` on the first read (age 0 ms); first audio sample at
     clip 17 ms; 4 segments of 3.4 s at 30 fps (348 camera frames to both outputs), audio
     contiguous across every boundary (17 to 3387 ms of 3404; 4 to 3353 of 3371; 4 to 3396
     of 3404; 12 to 1356 of 1368), 12 samples past the last video frame dropped, 18
     before the first; audio queue peak 25 frames (the hardware video encoder lag,
     about 0.5 s); drift checks within 0.2 ms over 12 s; 569 reads, 0 short, 0 overruns;
     encrypt 116 to 264 ms per segment; index `audio_codec=1 channels=2 rate=48000`;
     leak scan 0 findings. Readback (`P4-readback.txt`): **video ok and audio ok on all 4
     segments**, media3 audio first 17 265, 4627, 4035, 12 731 us. Playback
     (`P6-play.txt`): `c2.android.aac.decoder`, stereo 48 kHz, every segment on the
     standard path, first frame 669 ms, gaps 16, 33, 33 ms, 0 dropped of 344, 0
     underruns, six seeks on the key frame (latency 172 to 739 ms), one live buffer, PSS
     peak 176 MiB. No clap in this clip (the developer had not clapped); the audio floor
     was high (median 5372) from handling, so `av_sync` paired noise (`P5-avsync.txt`,
     `P7-avsync2.txt`).
   - Trial 2, `audio_trial=clip=10000` (`P8-trial2.txt`, `P9-readback2.txt`,
     `P10-avsync3.txt`): 3 segments, readback ok on all; quiet floor (median 742) and no
     clap: the capture screen shows a photo-mode viewfinder before the trial rebinds, so
     the claps fell before the recording started.
   - Trial 3, `audio_trial=clip=20000` (`P11-trial3.txt`): 6 segments, 575 frames at
     30 fps, audio contiguous (14 to 3406 ms of 3407; 19 to 3368 of 3371; ...), queue peak
     34, 0 overruns, leak scan 0 findings. Readback (`P12-readback3.txt`): video ok and
     audio ok on all 6 segments. `av_sync` (`P13-avsync4.txt`): **five claps**, audio
     onsets at 4630, 8330, 11120, 12060, 13140 ms rising from a floor of about 800 to
     20 000 and more within 10 ms; the hands were small in the picture (motion 2 to 4
     against a floor of 0.5) but each clap shows the same shape: an approach blip 60 to
     100 ms before the onset (4542, 8214, 11018, 11986, 13054 ms), motion stopped 20 to
     90 ms before the onset, the hands separating about 350 ms after. **First A/V offset:
     audio 30 to 90 ms behind the video**, inside the P3F-6 target (45 ahead, 125
     behind); the probe pairing then took the separation blip for three claps and was
     tightened to the approach movement (to be rerun in T3). Playback (`P14-play3.txt`):
     every segment on the standard path, first frame 645 ms, gaps 33, 16, 33, 33, 33 ms,
     0 dropped of 575, 0 underruns, six seeks on the key frame (204 to 730 ms), PSS peak
     164 MiB. **Developer by ear and eye: the claps land when the hands meet, the picture is
     upright, no stutter or silence**; the microphone also picked up the desk fan, which
     is the audio floor the probe measured.
   - Phone state: the debug build 0.7.0 with the trial code; the kept vault plus three
     trial clips (13 segments, 3 indexes: `0d73af65...`, `6857a612...`, `fea0dc3d...`),
     to be deleted in Milestone E with the other test clips.
8. Git read-only; nothing committed.

## Suggested commit message

```
Phase 3F: audio in video recording and playback

Core: the segment writer gains an AAC track (mp4a with esds, a trex per
track, a second traf per moof, the audio counted in samples at 1024
ticks per frame, a guarded stage per fragment); pv_audio_queue holds
encoded audio in guarded memory until the video has reached its time;
code 32; test_audio_queue, the audio cases and the one-hour drift test
of test_segment_writer; make_vectors --segment-av and the two-track
fixtures. Android: VideoRecorder records 48 kHz stereo AAC from the
camcorder source on the clock the camera stamps its frames with, queues
it behind the video, waits 500 ms for a cut segment, clamps late audio
into the next segment, drains the final segment to the last frame, and
stops on a taken microphone, read or encoder errors, and full caps;
AudioQueue, SegmentWriter.openAv; the capture screen asks for the
microphone once per process, resets Mute on entering video mode, shows
the audio state; the viewer plays with audio focus, pauses when
headphones are unplugged, Mute, the music volume stream, silent clips;
RECORD_AUDIO in the manifest; version 0.8.0; the leak scan counts media
file rows only. Debug: audio_caps, readback (the compatibility gate),
av_sync probes. Tests: AudioCaptureTest, GoldenVectorTest
segmentAvVector and the framework reader probe. Docs: PHASE3F_DESIGN,
PHASE3F_TEST_REPORT, PHASE3F_PROGRESS, decisions D-044 to D-050, threat
model Section 12, roadmap, dependencies.
```
