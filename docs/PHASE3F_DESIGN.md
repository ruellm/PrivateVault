# Phase 3F design: audio in video recording and playback

Status: implemented (Milestones C to E done 2026-10-22; results in
`docs/PHASE3F_TEST_REPORT.md`). Approved by the developer 2026-10-21 at the Milestone B checkpoint with two
changes, applied below: CP-2 with the audio track counted in samples (timescale 48 000,
every AAC frame exactly 1024 ticks, the video in microseconds), and CP-4 with late audio
never dropped (audio arriving after the 500 ms wait of a cut segment goes into the next
segment; only the final segment drains to the last sample). Three rules recorded at the
developer's request: a full audio queue or stage stops the recording through the 3E
stop path and keeps the clip (T2 hook test); the mute toggle resets to audio on whenever
video mode opens and is not saved; a muted recording opens no `AudioRecord` (T3 watches
the system microphone indicator). CP-1, CP-3, CP-5 to CP-10, the P3F-2 to P3F-8
confirmations, D-044 to D-050 (D-045 and D-047 amended), and threat model Section 12
approved. Milestone A trials complete on the emulator and the Xiaomi (Section 2). Items marked **CP-n** are
the choices this document makes beyond the brief, or where the brief leaves the default
to the design (Section 9). "Measured" names a run in `docs/PHASE3F_PROGRESS.md`
(Milestone A); everything else is design intent to be confirmed by Milestones C to E.

Facts from earlier stages are not repeated: `docs/PHASE3E_DESIGN.md` (the camera route,
the core segment writer, the stop path, the read-ahead), `docs/PHASE3C_DESIGN.md` (index
v1, the seek rule), `docs/PHASE3D_DESIGN.md` (viewer, gallery, sweep, recording gate).
Nothing here changes the version 1 object format, the item header, envelope providers 1
to 3, or the index v1 layout; the audio fields of the index header are filled as
defined.

## 1. Summary

A recording gets an AAC track. An `AudioRecord` on its own thread feeds the platform AAC
encoder in 1024-frame chunks; every chunk is timed from the first `AudioRecord.getTimestamp`
and the frame count, on the clock the camera stamps its frames with, which the recorder
finds at the first camera frame (the Xiaomi stamps on the boot clock, four days off the
monotonic clock; the emulator on the monotonic clock). Encoded audio goes from the
encoder's output buffer into a guarded queue in the core and is moved into the segment
writer once the video has reached its time, so every AAC frame lands in the segment and
fragment its time falls in; a frame that comes late goes into the next segment, never
away. The core writer gains the audio track (`mp4a` with `esds`, a second `traf` per
`moof`, the audio counted in samples at exactly 1024 ticks per frame); the index header carries
the codec, channels, and rate. Audio is on by default with a mute toggle; a denied
microphone gives a clip without audio and a "no audio" label. Every interruption ends the
recording through the 3E stop path after the audio encoder is drained. ExoPlayer plays
the track with audio focus and a mute button; old clips play silent. Measured on the
Xiaomi: both readers parse every segment of every trial clip with two tracks, and the
clap clip plays with the audio 30 to 90 ms behind the video by the probe, on time by
the developer's ear and eye.

## 2. Milestone A results (measured)

### 2.1 Gates

`git status` clean apart from the untracked brief. T1 19/19 in Debug and Release, 0
warnings. T2 72 tests, 0 failures, 1 skipped (`LockBoundKeyTest`, as in every stage
since 3A), 583 s. Lint 0 errors. The trial code (debug source set) built with lint at 0
errors; release and benchmark manifests unchanged (`CAMERA` only, 2577 media3 classes).

### 2.2 What the devices offer (P3F-3)

| | Emulator `Pixel_8_API_35` | Xiaomi 2201116SG |
|---|---|---|
| AAC encoders | `c2.android.aac.encoder`, `OMX.google.aac.encoder` (software) | the same two; no hardware AAC encoder |
| Encoder ranges | 8 to 48 kHz, up to 6 channels, 8 to 510 kbit/s, profiles LC, HE, HE v2, LD, ELD | the same |
| 48 kHz stereo 128 kbit/s LC | accepted; `csd-0` `1190`; output times equal to input times (21 333 us steps) | accepted; `csd-0` `1190`; the first output times equal to the input times; two extra frames flushed at the end of the stream |
| `AudioRecord` `CAMCORDER` and `MIC`, 48 and 44.1 kHz, mono and stereo | every configuration initialized and delivered frames; the host microphone silent (peak 0 to 11) | every configuration initialized, first data after 132 to 156 ms, room noise 67 to 693, no short reads |
| `getTimestamp` | 0 on `MONOTONIC` and `BOOTTIME`; the read position 25 to 110 ms behind the clock (550 ms on the first open) | 0 on both; the read position 10 to 23 ms behind the clock |
| Camera timestamp source (`SENSOR_INFO_TIMESTAMP_SOURCE`) | back `UNKNOWN`, front `REALTIME` | back and front `REALTIME` |
| Camera clock, measured at the first frame | `MONOTONIC` (the timestamp 33 to 43 ms before the callback; boot minus monotonic 0) | **`BOOTTIME`** (the timestamp 81 to 103 ms before the callback on the boot clock, 353 662 s off the monotonic clock) |

### 2.3 Trial clips

Trial code: `AudioTrial.kt` (the 3E camera route plus an `AudioRecord` thread and the
AAC encoder), `Fmp4TrialWriter.kt` (a Kotlin sketch of the two-track container),
`Readback.kt` (the two readers per track, and the A/V probe), `AudioProbes.kt`
(2.2). Scripted actions `audio_caps`, `audio_trial=...`, `readback=`, `av_sync=`.

| Clip | Recording | Readers (`readback`) | Playback (`VideoActivity`) |
|---|---|---|---|
| Emulator, 10 s, first build (`A2`) | 720x1280 at 14 fps (software GL), 3 segments, index `audio_codec=1 channels=2 rate=48000`; **defect:** segment 0 held audio to 4659 ms against a video end of 3531 ms, since the emulator encoder lags real time by about a second and audio arrived before the cutting key frame | two tracks per segment in both readers; video sync counts and media3 sync times equal to the index; audio counts equal between the readers (166, 112, 98) | audio played (`c2.android.aac.decoder`, stereo 48 kHz), 0 underruns, every segment on the standard path, 4 seeks on the key frame |
| Emulator, 10 s, audio queued until the video reaches its time (`A6`) | 3 segments; audio 15 to 3855 ms of 3862, 14 to 3449 of 3469, 0 to 2497 of 2515: contiguous across the boundaries; 83 frames past the last video frame dropped; queue peak 84 frames (1.8 s) | **video ok, audio ok on all 3 segments** | |
| Emulator, 8 s, audio track in samples (`A8`) | 3 segments | ok on all 3; media3 audio times within one frame of the microsecond run | audio played, 0 underruns, 0 dropped |
| Xiaomi, 12 s (`P3`) | 1080x1920 at 30 fps, 4 segments of 3.4 s, audio 17 to 3387 ms of 3404, 4 to 3353 of 3371, 4 to 3396 of 3404, 12 to 1356 of 1368; queue peak 25 frames (0.5 s, the hardware encoder lag); drift of the anchor within 0.2 ms over 12 s; encrypt 116 to 264 ms per segment; leak scan 0 | ok on all 4 | first frame 669 ms, gaps 16, 33, 33 ms, 0 dropped of 344, 0 underruns, six seeks on the key frame, one live buffer, PSS peak 176 MiB |
| Xiaomi, 10 s (`P8`) | 3 segments | ok on all 3 | |
| Xiaomi, 20 s with five claps (`P11`) | 6 segments, 575 frames at 30 fps, queue peak 34, 0 overruns, leak scan 0 | ok on all 6 | first frame 645 ms, gaps 33, 16, 33, 33, 33 ms, 0 dropped of 575, 0 underruns, six seeks on the key frame, PSS peak 164 MiB; **the developer: the claps land when the hands meet, the picture upright, no stutter or silence** |

Both readers also parse the kept 3E clip `9a9eba86...` on the phone with the sync counts
and media3 sync times equal to the index (the 3E gap, closed in `P1-readback-3e.txt`).

### 2.4 The first A/V offset (P3F-6)

`av_sync` on the clap clip: five audio onsets at 4630, 8330, 11120, 12060, and 13140 ms,
each rising from a floor of about 800 (the desk fan) to above 20 000 within 10 ms. The
hands were small in the picture (motion 2 to 4 against a floor of 0.5), but every clap
shows the same shape: an approach blip 60 to 100 ms before the onset, motion stopped 20
to 90 ms before the onset, the hands separating about 350 ms after. **Audio 30 to 90 ms
behind the video**, inside the target of 45 ms ahead to 125 ms behind. Part of that lag
is the frame itself: a camera timestamp marks the start of exposure and a 30 fps frame
spans 33 ms, so a visual event sits up to one frame after its timestamp. The probe's
pairing was tightened to the approach movement for T3.

### 2.5 Two findings that shape the design

- **The audio must wait for the video.** The video encoder output lags the camera by
  about 0.5 s on the Xiaomi (hardware) and over a second on the emulator (software),
  while encoded audio arrives about 20 ms after capture. An AAC frame appended to the
  open segment when it arrives lands in the wrong segment whenever a cut is still on its
  way. Queuing encoded audio until a video sample at or past its time has been written
  gives every frame its segment and fragment by time (CP-4), with the queue bounded by
  the encoder lag (25 to 34 frames on the phone).
- **The framework `MediaExtractor` ignores `tfdt`** and times every track from 0 by the
  sum of its `trun` durations (audio first 0, last 3 520 000 against media3 8534 to
  3 528 534 in the same segment). It still counts every sample. The readback gate
  compares the readers on counts and spans and takes times from media3 (CP-10). The
  player is media3, which reads `tfdt`. Found in Milestone D and pinned by
  `GoldenVectorTest.readersOnAudioStartingInSecondFragment`: the framework also ends a
  track after two consecutive fragments without its samples (an audio track starting in
  a segment's second fragment is read, one starting in the third reports no samples),
  while media3 reads both. Only late audio after a stall across a cut leaves that shape
  (4.2); the gate records it rather than failing it.

## 3. Decisions proposed (brief Section 3)

| ID | Decision | Proposal |
|---|---|---|
| P3F-1 | Audio on by default, mute toggle | as settled (4.1) |
| P3F-2 | Permission | as the brief: asked on the first switch to video mode, once per process; denied gives a clip without audio and "no audio" on the record button; a "Microphone" button asks again, and opens the app's settings page when the system no longer shows the dialog (4.1) (CP-6) |
| P3F-3 | Audio format | **confirmed:** AAC-LC, 48 kHz, stereo, 128 kbit/s, `CAMCORDER`, through `c2.android.aac.encoder` on both devices (the only AAC encoders are software); mono is the fallback when the device offers no stereo input (neither device needs it) (CP-1) |
| P3F-4 | Container | **confirmed, the timescale changed at the checkpoint:** `mp4a` with `esds`, a second `traf` per `moof`; the audio track counts in samples (timescale 48 000, every AAC frame exactly 1024 ticks, no per-frame rounding), the video in microseconds (both shapes measured through both readers and ExoPlayer); the audio samples of a fragment are those whose time falls in the video span of the fragment, written after its video samples in the `mdat` (4.3) (CP-2) |
| P3F-5 | Index | **confirmed:** `audio_codec` 1, `audio_channels`, `audio_sample_rate` from the encoder output format; no field is missing (4.4) |
| P3F-6 | A/V sync | **confirmed with one change:** the common clock is the camera's, found at the first frame by comparing the camera timestamp with both clocks rather than taken from `SENSOR_INFO_TIMESTAMP_SOURCE` (the Xiaomi declares `REALTIME` and stamps on the boot clock; the emulator declares `UNKNOWN` and stamps on the monotonic clock); audio times from `getTimestamp` on the monotonic clock plus the boot-minus-monotonic offset when the camera uses the boot clock; no empirical correction constant (4.2) (CP-3, CP-8) |
| P3F-7 | Interruptions | as the brief, through `VideoRecorder.stop`: the microphone taken (the app's recording reported silenced by `AudioManager.AudioRecordingCallback`), a read error, an encoder error (4.5) (CP-7) |
| P3F-8 | Playback | as the brief: ExoPlayer with audio attributes and focus handling, `setHandleAudioBecomingNoisy`, the volume keys on the music stream, a Mute button (4.6) |

## 4. Architecture

### 4.1 Capture screen (P3F-1, P3F-2; CP-6)

`CaptureActivity` video mode gains:

- A **Mute** toggle beside the mode button (text "Mute" while audio is on, "Unmute"
  while muted), enabled in video mode, disabled while recording. The state resets to
  audio on whenever video mode opens (the mode switch, a recreated screen); it is not
  saved anywhere.
- The elapsed label over the preview reads "● 0:05 mic" while audio records, "● 0:05
  muted" for a muted recording, "● 0:05 no audio" when the permission is missing.
- `RECORD_AUDIO` is requested the first time the screen enters video mode in this
  process. Denied: the record button reads "Record (no audio)", recordings have no
  audio track, and a **Microphone** button appears in the button row that asks again
  on a tap; when `shouldShowRequestPermissionRationale` is false after a denial the
  system shows no dialog, so the button opens the app's settings page instead
  (`ACTION_APPLICATION_DETAILS_SETTINGS`). No request is repeated by the app itself.
- A revoked permission while the app runs: the next recording checks the permission
  at start and records without audio (`pm revoke` in T2).
- Scripted actions: `record=<ms>` as 3E (audio on unless muted); `mute=<0|1>`; the
  hooks `record_hook=AUDIO_ERROR|AUDIO_TAKEN` (4.5). `audio_caps`, `readback=`, and
  `av_sync=` stay as debug probes; `audio_trial` and the Kotlin writer sketch are removed
  in Milestone D (CP-9).

### 4.2 Recording pipeline (P3F-3, P3F-6; CP-3, CP-4)

```
camera -> CameraEffect split -> viewfinder, H.264 encoder (3E)        camera timestamps as video pts
AudioRecord (CAMCORDER, 48 kHz, stereo, own thread) -> 1024-frame chunks -> AAC encoder (MediaCodec)
   chunk pts = anchor + frames since the anchor; anchor = first AudioRecord.getTimestamp (MONOTONIC)
AAC output buffer (direct) -> pv_audio_queue (guarded, core)           pts moved onto the camera clock
video drain: after every video sample, pv_audio_queue_move(queue, writer, segment start, video pts + 1)
   at a cut on key frame T: move(old writer, until T); old waits for audio past T; move(new writer, from T)
stop: video EOS drained, AudioRecord stopped, AAC encoder drained, the queue moved into the last segment, finish
```

- **The clock.** The encoder gets the camera timestamp as the presentation time (3E),
  so video times are on the camera's clock. At the first camera frame the splitter
  records the timestamp, `System.nanoTime()`, and `SystemClock.elapsedRealtimeNanos()`;
  the clock whose reading is nearest the timestamp is the camera's (measured: 81 to
  103 ms before the callback on the right clock, 353 662 s off on the wrong one on the
  Xiaomi; equal on the emulator, where either works). Audio times are produced on the
  monotonic clock and moved by `elapsedRealtimeNanos - nanoTime` when the camera uses
  the boot clock. The declared `SENSOR_INFO_TIMESTAMP_SOURCE` is logged, not used (it
  says `REALTIME` on the Xiaomi, which is right, and `UNKNOWN` on the emulator back
  camera, which says nothing).
- **The audio anchor.** The first successful `getTimestamp(MONOTONIC)` gives
  `(framePosition, nanoTime)`; the time of PCM frame `k` is
  `nanoTime + (k - framePosition) / rate`. The thread reads 1024-frame chunks into one
  direct buffer from an `AudioRecord` with one second of buffer (a stall of the thread
  up to that long drops no PCM, so the frame count stays a clock), counts frames, and
  queues each chunk to the encoder with the time of its first frame; the encoder keeps input times on its output (measured). Chunks read
  before the anchor exists are held (a few) and timed from it when it arrives; without
  a timestamp after 8 reads the read return time is the anchor (logged as a fallback).
  Measured: the anchor on the first read on both devices, later timestamps within
  0.2 ms of the anchor over 20 s on the Xiaomi (two 20 to 37 ms outliers on the emulator
  while its reader lagged).
- **The queue (CP-4).** Every AAC output buffer is pushed straight from its direct
  buffer into `pv_audio_queue`, guarded memory in the core, with its camera-clock time
  (no Java copy). The video drain, after writing a video sample at time `t`, moves every
  queued frame with time at or below `t` into the open writer, timed from the segment
  start. At a segment cut (key frame at `T`): the frames below `T` go to the old
  segment, which then waits for the queue to hold a frame at or past `T` (the audio has
  passed its end) or 500 ms, whichever first, before it is finished and encrypted; the
  new segment takes the rest from `T`. A frame below `T` that arrives after the wait
  goes into the next segment, written at that segment's start (the audio of that
  segment then runs late by the delay, audible, not lost); no frame inside the clip is
  dropped. Frames before the first video frame are discarded (18 to 27 on the phone, the
  camera start: the clip begins with its first frame). At stop the queue is moved into
  the last segment to its last frame, so the final segment's audio runs past its last
  video frame by the encoder lag (about 0.5 s on the phone); the index duration stays
  the video end. Queue cap 2 MiB (about 2 minutes of audio at 128 kbit/s); past it the
  recording stops with `AUDIO_QUEUE_FULL` through the 3E stop path and keeps the clip
  (a video encoder that stalls that long has failed). Measured peak 25 to 34 frames on
  the phone.
- **Threads and the gate.** The audio thread starts with the recording (after the
  permission check and unless muted) and ends in the stop path; the recording gate,
  the queue bound, and the lock bound cover it. The PCM read buffer (one direct 4 KiB
  buffer) is zeroed at the end of the thread.
- **Muted or denied.** No `AudioRecord` is opened and no AAC encoder created; the
  writer is opened without an audio track; the index has `audio_codec` 0. T3 checks by
  eye that the system microphone indicator does not light during a muted recording.

### 4.3 Container: the core writer additions (P3F-4; CP-2, CP-5)

`src/vault_core/src/segment.cpp` gains the audio track; the single-track shape of 3E is
unchanged when no audio is configured (the 3E golden vector still passes).

- `moov`: a second `trak` (track 2): `tkhd` with volume 0x0100 and no size, `mdhd` at
  the sample rate (48 000), `hdlr` `soun`, `smhd`, `stsd` with `mp4a` (channels, 16-bit,
  the sample rate) holding `esds` (ES descriptor, decoder config with object type 0x40
  and stream type 0x05, the `AudioSpecificConfig` from the encoder's `csd-0`, SL config
  2; one-byte descriptor lengths), empty sample tables; `mvex` with a `trex` per track.
  `mvhd` `next_track_ID` 3. The `sidx` keeps reference_ID 1 (the video track) and its
  references cover each `moof` and `mdat` as before.
- Per fragment: `moof` with `mfhd`, the video `traf` (as 3E: `tfhd` default-base-is-moof,
  `tfdt` version 1, `trun` flags 0x701), and when the fragment holds audio an audio
  `traf` (`tfhd` track 2, `tfdt` with the first audio time, `trun` flags 0x301 with
  duration and size per sample, no flags: every AAC frame is a sync sample by the
  `trex` default). The `mdat` holds the video samples then the audio samples; the audio
  `trun` data offset points past the video bytes. **Audio time base (CP-2 as approved):**
  the first audio sample of the segment gets `round(t_us × rate / 1 000 000)` ticks from
  its microsecond time; every later sample sits exactly 1024 ticks after the one
  before, whatever its microsecond time says (the time is checked not to decrease,
  nothing else); every duration is 1024. So the audio `tfdt` of a fragment is the first
  sample's tick plus 1024 times the audio samples before it in the segment, with no
  drift (T1 checks it over one hour of frames).
- Layout in the guarded buffer: the `moof` slot grows by the audio `traf` bound
  (`kBox + kTfhd + kTfdt + kTrunHeader + 8 × PV_SEGMENT_MAX_AUDIO_SAMPLES_PER_FRAGMENT`,
  512 samples, 10.9 s at 48 kHz, past the 10 s backstop GOP). The audio samples of the
  open fragment are staged in a second guarded buffer (`PV_SEGMENT_AUDIO_STAGE_BYTES`,
  512 KiB, 32 s at 128 kbit/s) because the video samples are still arriving into the
  `mdat`; at fragment close the stage is copied behind the video samples, the real
  `moof` written into its slot, and the `mdat` moved back as today. The stage is wiped
  at close and at every fragment close. A full stage or a full audio `traf` slot
  reports `SEGMENT_FULL`; the recorder stops through the 3E stop path with `AUDIO_FULL`
  and keeps the clip (T2 hook test, as for the queue cap).
- Interleaving is per fragment (one GOP): a reader gets 1 s of video then 1 s of audio.
  ExoPlayer reads whole fragments; measured without underruns on both devices.

### 4.4 Index (P3F-5)

The header's `audio_codec` (1), `audio_channels`, and `audio_sample_rate` are filled
from the AAC encoder's output format; a muted or denied recording writes 0, 0, 0 (the
rule the index already checks). Segment records are unchanged; the key offsets stay the
video key frames. `VideoIndex.Header` carries the fields already; `VideoRecorder.writeIndex`
sets them. The gallery and the viewer read them for the "silent" case (4.6).

### 4.5 Stop, interruptions, and the lock (P3F-7; CP-7)

The 3E stop path gains the audio drain: `stop(reason)` ends the video as today; the
drain loop, after the video end of stream, stops the `AudioRecord`, signals end of
stream to the AAC encoder, drains its output into the queue (bounded by 1 s), moves the
whole queue into the last segment to its last frame, and then finishes it. The lock's 4 s bound covers the
drain (measured on the phone: a segment finishes in under 300 ms; the encoder flush
is a few frames). Past the bound the open segment is dropped as in 3E, and the queue
is closed (wiped).

| Situation | Detection | Behaviour |
|---|---|---|
| Microphone taken (a call, another recorder with priority) | `AudioManager.registerAudioRecordingCallback`: the app's own recording reported `isClientSilenced` (API 29 and later; on API 28 the read keeps returning silence and the recording continues) | `stop("AUDIO_TAKEN")`, the clip kept with the audio so far |
| `AudioRecord` read error or recording state lost | the read returns a negative code or `recordingState` is not recording | `stop("AUDIO_ERROR")` |
| AAC encoder error | `MediaCodec.CodecException` on the audio thread | `stop("AUDIO_ERROR")` |
| Audio queue full (2 MiB) or stage full (512 KiB) | `pv_audio_queue_push` returns `AUDIO_QUEUE_FULL`; `pv_segment_writer_add_audio` returns `SEGMENT_FULL` | `stop("AUDIO_QUEUE_FULL")` or `stop("AUDIO_FULL")` through the 3E path, the clip kept with the segments so far (T2 hook: the caps set small by `testHooks`) |
| Lock during a recording | as 3E | the audio drained inside the bound, the clip indexed with its audio fields |
| Permission revoked while the app runs | checked at every recording start | the next recording has no audio track |

Test hooks: `stopAt` `AUDIO_ERROR` throws on the audio thread at the next chunk;
`AUDIO_TAKEN` takes the silenced path; `audioQueueCap` and `audioStageCap` open the
queue and the writer with small caps; `audioDelayMs` holds the audio thread for that
long once, after the first segment, so a cut segment times out and the late frames go
into the next segment (the CP-4 case).

### 4.6 Playback (P3F-8)

- `ExoPlayer.Builder.setAudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MOVIE, handleAudioFocus
  = true)`: the player requests focus on play, pauses on loss, lowers its volume on a
  transient loss with ducking allowed, resumes after a transient loss.
  `setHandleAudioBecomingNoisy(true)`: pause when headphones are unplugged.
  `volumeControlStream = AudioManager.STREAM_MUSIC` so the volume keys set the media
  volume while the viewer is open.
- A **Mute** button beside Delete toggles the player volume between 0 and 1 for this
  viewer instance (text "Mute" and "Unmute"); the status line notes it.
- Clips without an audio track (the two regression clips, the 3E clip, muted clips):
  the extractor reports one track, the audio renderer idles, no error; the status line
  says "silent" from the index's `audio_codec`.
- The lock and `onStop` release the player as in 3D, which abandons focus. The
  read-ahead byte target is unchanged (the audio adds about 2 percent to a segment).
- The viewer report carries the audio format, the decoder, and the underrun count
  (added in Milestone A); T2 reads them.

### 4.7 Core additions (brief 4.2)

Additive, portable, T1-tested. Error code after 31:

```c
#define PV_ERR_AUDIO_QUEUE_FULL 32                      // the audio queue cap is reached
#define PV_SEGMENT_MAX_AUDIO_SAMPLES_PER_FRAGMENT 512u
#define PV_SEGMENT_AUDIO_STAGE_BYTES (512u * 1024u)
#define PV_AUDIO_QUEUE_CAP (2u * 1024u * 1024u)

typedef struct pv_segment_audio {
    uint16_t codec;            // PV_AUDIO_CODEC_AAC
    uint16_t channels;         // 1 or 2
    uint32_t sample_rate;      // 8000 to 96000
    const uint8_t* config;     // AudioSpecificConfig (the encoder csd-0), 2 to 64 bytes
    size_t config_len;
} pv_segment_audio;

// As pv_segment_writer_open, plus an audio track; audio may be null.
int pv_segment_writer_open_av(uint32_t width, uint32_t height, uint16_t rotation_degrees,
                              const uint8_t* sps, size_t sps_len, const uint8_t* pps, size_t pps_len,
                              const pv_segment_audio* audio, size_t cap, pv_segment_writer** out);
// Appends one AAC frame (raw, no ADTS) with its time from the segment start to
// the open fragment. The first frame sets the track time in samples; every
// later frame follows 1024 samples after the one before. Times must not
// decrease; PV_ERR_SEGMENT_STATE before the first video sample or without an
// audio track; PV_ERR_SEGMENT_FULL when the fragment holds the sample bound
// or the stage is full.
int pv_segment_writer_add_audio(pv_segment_writer* w, const uint8_t* sample, size_t len, uint64_t pts_us);
// Audio samples written so far and the tick of the first one.
int pv_segment_writer_audio_stats(const pv_segment_writer* w, uint32_t* samples_out, uint64_t* first_tick_out);

// Encoded audio waiting for the video to reach its time, in guarded memory.
typedef struct pv_audio_queue pv_audio_queue;
int pv_audio_queue_open(size_t cap, pv_audio_queue** out);
// Appends one frame with its time on the recorder clock (increasing).
int pv_audio_queue_push(pv_audio_queue* q, const uint8_t* sample, size_t len, uint64_t pts_us);
// Discards every queued frame with pts below the given time (audio from
// before the first video frame).
int pv_audio_queue_discard_before(pv_audio_queue* q, uint64_t pts_us, uint32_t* dropped_out);
// Moves every queued frame with pts below until_us into the writer, timed
// from base_us; a frame below base_us (late for a cut) is written at time 0
// and counted as clamped. The queue keeps the rest.
int pv_audio_queue_move(pv_audio_queue* q, pv_segment_writer* w, uint64_t base_us, uint64_t until_us,
                        uint32_t* moved_out, uint32_t* clamped_out);
// Bytes and frames held; head_pts_out gets the time of the oldest frame (0 when empty).
int pv_audio_queue_stats(const pv_audio_queue* q, size_t* bytes_out, uint32_t* frames_out, uint64_t* head_pts_out);
// Wipes and frees.
void pv_audio_queue_close(pv_audio_queue* q);
```

`pv_segment_writer_open` stays as it is (it calls `open_av` with no audio), so the 3E
callers, tests, and golden vector are untouched. JNI (D-011 shape): `nativeSegmentOpenAv`
(the 3E arguments plus codec, channels, rate, config), `nativeSegmentAddAudio(handle,
ByteBuffer direct, offset, length, ptsUs)` for T2 and the probes, `nativeSegmentAudioStats`,
`nativeAudioQueueOpen`, `nativeAudioQueuePush(handle, ByteBuffer direct, offset, length,
ptsUs)`, `nativeAudioQueueMove`, `nativeAudioQueueStats`, `nativeAudioQueueClose`. The
desktop CLI is unchanged.

Golden vectors: `tests/fixtures/v1/segment_av_one_fragment.bin`, a segment of one
fragment with the three synthetic video samples of the 3E vector and four synthetic
AAC samples (deterministic bytes, times 500, 21 833, 43 166, 64 500 us, so the first
tick is 24) at 48 kHz stereo with the config `11 90`, written by `make_vectors --segment-av`, and `segment_av.txt`
with the inputs, the box offsets (end of `moov`, `sidx`, `moof`, the audio `traf`,
`mdat`, the audio payload), and the BLAKE2b of the file. T1 writes the same inputs and
compares byte for byte; T2 does the same through the JNI. The 3E vector
`segment_one_fragment.bin` must still match (the single-track shape is unchanged).

### 4.8 Where plaintext lives during a recording and playback (brief 4.4)

| Place | Owner | Lifetime | Wiped by the app |
|---|---|---|---|
| `AudioRecord` buffer (one second) | platform (audio HAL, audioserver) | per chunk | no |
| The PCM read buffer (one direct 4 KiB buffer) | app | the recording | yes, at the end of the audio thread |
| AAC encoder input and output buffers | platform codec | per frame | no |
| Encoded audio waiting for the video (`pv_audio_queue`) | app (core, `sodium_malloc`) | up to the video encoder lag (0.5 to 2 s measured), bounded at 2 MiB | yes |
| The audio stage of the open fragment | app (core) | one fragment | yes |
| Segment buffers (open plus two finished) | app (core) | as 3E | yes |
| Index bytes | app (Java array) | as 3E | yes |
| ExoPlayer audio sample queue, decoder buffers, `AudioTrack` | platform and media3 | playback | no |

No Java array holds a PCM chunk or an AAC frame in the feature path (the trial code did;
it is removed in Milestone D).

## 5. Failure handling

Section 4.5 for the recording. Also: `pv_secure_alloc` failure for the queue or the
stage at the start stops the recording with `OUT_OF_MEMORY`; an audio `traf` slot or
stage overflow reports `SEGMENT_FULL` through the recorder as `AUDIO_FULL` (the clip
keeps the segments so far); a focus loss during playback pauses and the user resumes;
a decoder failure of the audio track is a player error as any other.

## 6. Test plan

**T1 (Windows).** The 19 existing tests. `test_segment_writer` gains: a segment of
three fragments with both tracks parsed box by box (`mp4a` fields, `esds` descriptor
tree and the config bytes, the second `trex`, `smhd`, `hdlr` `soun`, per `moof` the
audio `traf` with `tfdt` equal to the first audio time, `trun` flags 0x301 with the
durations and sizes, the data offset landing after the video bytes, the payload bytes),
a fragment without audio (no audio `traf`), audio before the first video sample
(`SEGMENT_STATE`), decreasing audio times (`INVALID_ARGUMENT`), the 512-sample bound and
the stage bound (`SEGMENT_FULL`), audio past the video end kept at finish, the
single-track shape unchanged (the 3E vector), the new golden vector byte for byte with
the offsets and hash of `segment_av.txt`; the round trip through a vault object; **one
hour of audio frames** (168 750 frames timed as the recorder times them, through
successive writers cut every three seconds): every fragment's audio `tfdt` equals the
segment's first tick plus 1024 times the frames before it, every duration 1024, and the
tick clock stays within one tick of the microsecond clock at the end. `test_audio_queue`
(new): push and move by time with base and until, the clamped count for a late frame
(written at time 0, never dropped), the discard before a time, the head time, the cap
(`AUDIO_QUEUE_FULL`), close wipes, arguments, error name 32.

**T2 (emulator).** The 72 existing tests. `AudioCaptureTest` (new): a recording with
audio from the feature path (the emulator microphone delivers silence; the pipeline
runs): the index audio fields, both readers two tracks per segment with equal counts
(`Readback`), the first audio time of every segment below one AAC frame plus the
anchor lag, the clip plays with `report.audioFormat` set and no underrun error; mute in
the viewer (volume 0, playing continues); a focus request by the test pauses the player;
the old-shape clip (`V3Probe`) and a muted clip play silent with `audioFormat` null and
no error; a muted recording writes no audio track and `audio_codec` 0; the permission
revoked (`UiAutomation.revokeRuntimePermission`) gives a clip without audio and the
"no audio" label, and granted again gives audio; a lock during a recording drains the
audio and indexes (reason `lock`, readback ok); the `AUDIO_ERROR` and `AUDIO_TAKEN`
hooks stop with their reason and an index; the queue cap and the stage cap set small
stop with `AUDIO_QUEUE_FULL` and `AUDIO_FULL` and keep the clip; the audio thread held
for 800 ms after the first segment gives a cut segment that times out and the next
segment holding the late frames, with every frame after the first video frame
accounted for (readback counts); the mute toggle back on after a mode switch;
`FLAG_SECURE` while recording; `leak_scan` clean; `GoldenVectorTest.segmentAvVector`. Host-driven: merged manifests (`CAMERA` and
`RECORD_AUDIO`, `LockActivity` the only export), class scan, APK sizes. No frame-rate
or sync assertion.

**T3 (Xiaomi).** Brief Section 8 as listed: the debug build, the readback on the kept
3E clip (done in Milestone A, repeated), a recording with audio and its readback, the
clap with the tightened probe and the developer's ear, a muted recording with the system
indicator watched, the front camera, the benchmark build with audio, mute, the volume
keys, old clips silent, Home, screen off, and the MIUI camera during a recording with
audio, the recents card, `leak_scan`, cleanup.

## 7. Dependencies, manifests, version

No new dependency. `RECORD_AUDIO` moves from the debug manifest to the main one, so
release and benchmark declare `CAMERA` and `RECORD_AUDIO`; `LockActivity` stays the only
export. media3 1.11.1 and CameraX 1.6.2 unchanged. Version 0.8.0 (`versionCode` 8).

## 8. Threat model deltas (for `docs/THREAT_MODEL.md` Section 12)

- The microphone is a new input: audio is captured only while a recording runs with
  audio on and the permission granted; muted recordings open no `AudioRecord`.
- PCM lives in the HAL and one app buffer; encoded audio lives in platform codec
  buffers, the guarded queue, the guarded stage, and the segment buffers; the app wipes
  its own. The Java heap holds no audio.
- The audio queue holds up to the video encoder lag of audio (about 0.5 s on the
  phone), bounded at 2 MiB; a lock wipes it with the open segment past the 4 s bound; a
  full queue or stage stops the recording and keeps the clip.
- Playback hands decoded audio to the `AudioTrack` of the platform; focus loss and
  headphone removal pause the player.
- The container carries the audio config and the per-fragment audio times in plaintext
  inside the encrypted object, like the video; nothing new on disk.

## 9. Checkpoint items (CP-1 to CP-10)

| # | Item | Proposal |
|---|---|---|
| CP-1 | Audio format | AAC-LC 48 kHz stereo 128 kbit/s, `CAMCORDER`, the software encoder both devices have (2.2) |
| CP-2 | Container details | **changed at the checkpoint:** the audio track in samples (timescale 48 000, every frame 1024 ticks, no drift), the video in microseconds; per-fragment interleaving, video then audio in the `mdat`; audio `trun` 0x301; `esds` with one-byte lengths and the bitrate setting (4.3) |
| CP-3 | Camera clock | found at the first frame by comparing the timestamp with both clocks, the declared source logged only (4.2) |
| CP-4 | Audio routing | **changed at the checkpoint:** encoded audio queued in the core until the video reaches its time; a cut segment waits for audio past its end or 500 ms, and audio that comes later goes into the next segment, never dropped; only the final segment drains to the last sample; queue cap 2 MiB, past it the recording stops and keeps the clip (4.2) |
| CP-5 | Writer bounds | 512 audio samples per fragment, a 512 KiB guarded stage per writer (4.3) |
| CP-6 | Permission and mute UI | one request per process on entering video mode, a Microphone button for the retry and the settings path, a Mute toggle that resets to audio on whenever video mode opens and is not saved (4.1) |
| CP-7 | Interruption detection | the recording callback's silenced flag, read errors, codec exceptions, the queue cap; hooks `AUDIO_ERROR` and `AUDIO_TAKEN` (4.5) |
| CP-8 | Sync correction | none: the measured lag (30 to 90 ms behind, on time by ear) is inside the target; T3 measures again with the tightened probe and records the figure |
| CP-9 | Trial code | `AudioTrial.kt` and `Fmp4TrialWriter.kt` removed in Milestone D; `AudioProbes`, `Readback`, and `AvSync` stay as debug probes and T3 tools |
| CP-10 | The readback gate | both readers parse every segment; video sync counts and media3 sync times equal to the index; audio counts and spans equal between the readers (the framework ignores `tfdt`) |

## 10. Seams for later phases

- Phase 5: a shared clip is the same segments; the audio track travels with them.
- Phase 6: the reserved item header bytes (a clip ID) and recovery of orphans, as 3E.
- Mono and other rates are a format field away (`pv_segment_audio`); no other codec.
