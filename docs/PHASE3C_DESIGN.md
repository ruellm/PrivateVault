# Phase 3C design: video groundwork (key frames, index v1, seeking, playback probe)

Status: implemented (Milestones C to E done 2026-10-20; results in `docs/PHASE3C_TEST_REPORT.md`
and `docs/VIDEO_PLAYBACK_PROBE.md`). Proposed 2026-10-19 for the Milestone B checkpoint;
approved by the developer the same day with two confirmations, applied below: the 4096 key bound is per segment, with
boundary tests for both bounds (4.2, 8); segments are cut on the encoder's actual key-frame
output and the index records actual key-frame times, with a T2 check (3, 8). The developer
also asked for a capture resolution and frame rate choice under 3E in `docs/ROADMAP.md`, and
for the phone cleanup of Milestone E to go through the app's delete action with notice
before it starts (8). Items marked **CP-n** are the choices this document made beyond the
brief (Section 9).
Nothing here ships in release: the index codec is core code without media3, the recording
and playback probes stay in the debug source set, and release and benchmark builds carry no
media3. Nothing changes the version 1 object format, the item header, or the envelopes.

Facts from earlier phases are not repeated; see `docs/VIDEO_CAPTURE_FEASIBILITY.md` (the
V3b prototype), `docs/PHASE2_DESIGN.md` (item header), `docs/PHASE3B_DESIGN.md` (guarded
buffers, cancellation, lock rules). "Measured" names the run in `docs/PHASE3C_PROGRESS.md`;
everything else is design intent to be confirmed by Milestones C and D.

## 1. Summary

The key-frame mismatch of Phase 2 is a reading difference, not a lost flag: the muxer
writes every encoder key frame into the container as a per-sample sync flag, media3 reads
those flags back, and the framework `MediaExtractor` reports sync only for the first sample
of each fragment (H1). Seeking therefore keys off an index of encoder key-frame times, not
the container, and playback uses media3, which honours the per-sample flags. Recommended
settings: 3 s segments, 1 s GOPs made by time-based sync requests, fragment duration equal
to the GOP, one fragment per GOP. Index v1 is one encrypted object (`media_type` 3) with a
64-byte header, a 48-byte record per segment, and the key-frame offsets per segment, encoded
and decoded in the core. The playback probe (debug only) plays the segments from guarded
memory through ExoPlayer with a custom `DataSource`, measures first frame, boundary gaps,
seek latency, and memory, and lists where plaintext lives.

## 2. Key-frame investigation (brief Section 5), measured

### 2.1 Runs

Twelve clips, 12 s each, 3 s segments, four segments per clip, neutral scene: fragment
duration 500, 1000, 3000 ms times I-frame interval 1 and 2 s, on the emulator
(`c2.android.avc.encoder`, 1280x960, software) and on the Xiaomi 2201116SG
(`c2.qti.avc.encoder`, 1600x1200, hardware). Per clip the probe logged every encoder output
buffer (PTS, size, encoder flags, muxer flags), the sync request at each boundary, and per
decrypted segment the box walk, the framework count, and the media3 count.

### 2.2 Evidence

| Device, fragment, I-frame | Segment | Samples | Encoder key frames (PTS ms) | Container (`trun` per-sample sync) | Framework `MediaExtractor` | media3 default | media3 with the every-frame-is-sync workaround | `moof` |
|---|---|---|---|---|---|---|---|---|
| Emulator 1000 ms, 1 s | 0 to 3 | 54, 44, 37, 26 | 2 (0, 1069), 3 (3204, 3464, 5793), 2, 2 | 2, 3, 2, 2 | 2, 2, 2, 2 | 2, 3, 2, 2 | 2, 2, 2, 2 | 2, 2, 2, 2 |
| Emulator 500 ms, 1 s | 0 to 3 | 59, 45, 41, 36 | 2, 3 (3225, 3258, 5491), 2, 3 | 2, 3, 2, 3 | 2, 2, 2, 2 | 2, 3, 2, 3 | 2, 2, 2, 2 | 2, 2, 2, 2 |
| Emulator 3000 ms, 1 s | 0 to 3 | 60, 44, 41, 38 | 2, 2, 2, 3 | 2, 2, 2, 3 | 1, 1, 1, 1 | 2, 2, 2, 3 | 1, 1, 1, 1 | 1, 1, 1, 1 |
| Emulator 1000 ms, 2 s | 0 to 3 | 56, 50, 49, 37 | 1, 2 (3161, 3400), 2 (6423, 7320), 2 | 1, 2, 2, 2 | 1, 1, 1, 2 | 1, 2, 2, 2 | 1, 1, 1, 2 | 1, 1, 1, 2 |
| Emulator 500 ms, 2 s | 0 to 3 | 65, 45, 42, 29 | 2 (0, 3000), 1, 2, 2 | 2, 1, 2, 2 | 2, 1, 2, 2 | 2, 1, 2, 2 | 2, 1, 2, 2 | 2, 1, 2, 2 |
| Emulator 3000 ms, 2 s | 0 to 3 | 56, 46, 39, 32 | 1, 2, 2, 1 | 1, 2, 2, 1 | 1, 1, 1, 1 | 1, 2, 2, 1 | 1, 1, 1, 1 | 1, 1, 1, 1 |
| Xiaomi 1000 ms, 1 s | 0 to 3 | 43, 44, 44, 33 | 2 each (0 and 2178; 3091 and 5198; 6181 and 8289; 9272 and 11379) | 2, 2, 2, 2 | 2, 2, 2, 2 | 2, 2, 2, 2 | 2, 2, 2, 2 | 2, 2, 2, 2 |
| Xiaomi 500 ms, 1 s | 0 to 3 | same | same | 2, 2, 2, 2 | 2, 2, 2, 2 | 2, 2, 2, 2 | 2, 2, 2, 2 | 2, 2, 2, 2 |
| Xiaomi 3000 ms, 1 s | 0 to 3 | same | same | 2, 2, 2, 2 | 1, 1, 1, 1 | 2, 2, 2, 2 | 1, 1, 1, 1 | 1, 1, 1, 1 |
| Xiaomi 1000, 500, 3000 ms, 2 s | 0 to 3 | 43, 44, 44, 31 to 33 | 1 each (the boundary frame only) | 1, 1, 1, 1 | 1, 1, 1, 1 | 1, 1, 1, 1 | 1, 1, 1, 1 | 1, 1, 1, 1 |

Box details, every segment on both devices: `trex` default sample flags 0x0; `tfhd` carries
no default sample flags; every `trun` has flags 0x701 (data offset, per-sample duration,
size, and flags), no `first_sample_flags`, and a flags word per sample whose
`sample_is_non_sync_sample` bit is clear exactly for the encoder key frames. The flags
given to `writeSampleData` equalled the encoder flags for every one of the 2 000 buffers
logged (the summarizer prints a MISMATCH line otherwise; none appeared).

### 2.3 What the evidence says about the hypotheses

- **H1 supported.** The framework `MediaExtractor` count equals the `moof` count in all 48
  segments, whatever the per-sample flags say: 1 with one fragment per segment, 2 with two.
  The same number comes out of media3 when its `FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME`
  is set, which by its documentation keeps only the fragment-first sample as sync.
- **H2 rejected.** The muxer writes per-sample flags (0x701) and marks every encoder key
  frame as sync; the container count equals the encoder count in all 48 segments.
- **H3 rejected for the flag conversion;** the encoder-side half is confirmed: the extra key
  frames are the sync frame requested at each segment boundary plus the encoder's own
  periodic ones. On the emulator the two can fall 33 to 260 ms apart (segment 1 of the
  1000 ms and 500 ms runs: a periodic key frame right after the requested one).
- **H4 rejected.** Muxer and extractor are the same media3 1.11.1; media3 reads back
  exactly what the muxer wrote. The Phase 2 observation (2/4/4/3 against 2/2/2/2) was the
  framework reader against two 1 s fragments per 3 s segment, which is H1.

Consequence: any reader that relies on the framework extractor's sync flags sees one seek
point per fragment. media3 sees every key frame. The index records the encoder's key frames
and makes playback independent of either reader (Section 4).

### 2.4 The I-frame interval is counted in frames

Both encoders place their periodic key frames by frame count at the configured frame rate,
not by wall time: with `KEY_FRAME_RATE` 30 and `KEY_I_FRAME_INTERVAL` 1 the Xiaomi delivered
14.2 fps (70.2 ms between frames, steady) and put a key frame every 31 frames, 2 178 ms
apart; with interval 2 the periodic key frame never fell inside a 3 s segment (62 frames is
4.4 s). The emulator, at 14 to 16 fps under the load of the parallel test run, showed the
same effect less regularly. Any GOP length chosen in seconds must therefore be enforced by
time, not by `KEY_I_FRAME_INTERVAL` alone (Section 3).

### 2.5 Fragments start only on key frames

With fragment duration 500 ms and 1 s GOPs the muxer still produced one fragment per GOP:
a fragment closes only when a key frame arrives after at least the fragment duration has
accumulated (brief, research finding 1). With fragment duration 3000 ms and 3 s segments
every segment was one fragment. Fragment duration equal to the GOP length gives one fragment
per GOP; a shorter value gains nothing.

## 3. Recommended settings (CP-1)

| Setting | Value | Reason (measured) |
|---|---|---|
| Segment length | 3 s of PTS, cut on a key frame | 1.4 to 2.0 MB per segment on the phone at 4.8 Mbit/s, 0.5 to 0.9 MB on the emulator; well under the 8 MiB cap; one segment decrypt is 120 to 150 ms on the phone (debug build) |
| GOP length | 1 s, made by a sync request every 1 s of PTS from the app (`PARAMETER_KEY_REQUEST_SYNC_FRAME`), with `KEY_I_FRAME_INTERVAL` set to 10 s as a backstop | the encoder's own interval is frame-count based and lands at 2.2 s on the phone (2.4); time-based requests give a predictable seek grid and avoid the paired key frames at boundaries (2.3) |
| Segment boundary | the encoder's actual key-frame output: the segment closes when the third key frame after its first one arrives, whatever its PTS; the request only asks for it | no separate boundary request, so no near-duplicate key frames; segments are three GOPs as the encoder delivered them (developer confirmation: cut on output, not on request time) |
| Fragment duration | 1000 ms, equal to the GOP | one `moof` per GOP (2.5); the framework reader then sees every key frame too, which costs nothing and helps any tool that reads the container the framework way |
| Frame rate given to the encoder | the rate the camera delivers, measured before the encoder exists by binding the Preview to an `ImageReader` (`USAGE_GPU_SAMPLED_IMAGE`; the Xiaomi HAL delivers nothing to a private reader with the default usage) for 1.5 s and counting frames, rounded (emulator 29 to 30, Xiaomi 24 towards a window and 14 towards the desk); 30 if the meter gets no frames | bitrate control and the backstop interval are computed from it; the index records the meter's rate, or the delivered rate of the encoder output when the meter got none |
| Codec | H.264 (`video/avc`), profile as the encoder picks (phone: profile 8, level as reported) | the only codec exercised; H.265 reserved in the index |
| Bitrate | 4 Mbit/s requested (the phone encoder settles at 4.8) | unchanged from Phase 2 |

Implemented in the debug prototype in Milestone D and confirmed by the key-frame counts
from encoder, container, and index agreeing on both devices (T3).

## 4. Index format v1 and seek design (brief Section 6)

### 4.1 Rules

- One index object per clip, item `media_type` 3, written after the last segment. Its
  payload is the layout below; everything is inside the encrypted object.
- Key-frame times come from the encoder output (the PTS of every buffer flagged
  `KEY_FRAME`), recorded as offsets from the segment start; the request times are not
  recorded anywhere. The container's sync table is not consulted.
- Every segment starts on a key frame, so the first key offset of every segment is 0.
- Sample times inside a segment's fragmented MP4 start at zero (the writer subtracts the
  segment's first PTS before muxing); the index carries the clip offset in `start_us`. A
  player given one segment therefore starts at its first frame at once, and a seek inside
  a segment is `key_offset_us` into that segment (Milestone D).
- The Phase 2 prototype indexes (magic `PVVIDX01`) are not migrated; the new magic differs,
  so they stay unknown items (`PV_ERR_NOT_INDEX` from the decoder, as today).
- All integers little-endian, unsigned.

### 4.2 Layout (CP-2)

Header, 64 bytes:

| Offset | Size | Field | Rule |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `PVVINDEX`. Otherwise `PV_ERR_NOT_INDEX`. |
| 8 | 2 | `version` | 1. Other: `PV_ERR_INDEX_UNSUPPORTED`. |
| 10 | 2 | `header_len` | 64 written; 64 to 4096 accepted, extra bytes skipped. Otherwise `PV_ERR_INDEX_MALFORMED`. |
| 12 | 2 | `video_codec` | 1 = H.264. 2 = H.265 reserved, accepted by the decoder. Other: `PV_ERR_INDEX_UNSUPPORTED`. |
| 14 | 2 | `rotation_degrees` | 0, 90, 180, 270. |
| 16 | 4 | `width` | 1 to 16384. |
| 20 | 4 | `height` | 1 to 16384. |
| 24 | 4 | `frame_rate_millihz` | nominal frame rate times 1000; 0 allowed (unknown). |
| 28 | 4 | `segment_count` | 1 to 16384. 0 or more: `PV_ERR_INDEX_MALFORMED`. |
| 32 | 8 | `total_duration_us` | equals `start_us + duration_us` of the last segment. |
| 40 | 8 | `created_unix_ms` | clip start, UTC. |
| 48 | 2 | `audio_codec` | 0 = none (Stage 3C). 1 = AAC reserved for 3E; accepted by the decoder. |
| 50 | 2 | `audio_channels` | 0 when no audio. |
| 52 | 4 | `audio_sample_rate` | 0 when no audio. |
| 56 | 8 | `reserved` | 0 when writing, ignored when reading. |

Then `segment_count` segment records, each 48 bytes followed by its key offsets:

| Offset | Size | Field | Rule |
|---|---|---|---|
| 0 | 16 | `object_id` | the segment object. |
| 16 | 8 | `start_us` | PTS of the first sample, relative to the clip start; strictly increasing across segments; the first is 0. |
| 24 | 8 | `duration_us` | > 0; `start_us + duration_us <= start_us` of the next segment plus one frame period (the writer rounds the last frame's duration), checked as `<=` the next start plus `frame_period_us` when the frame rate is known, otherwise `<=` the next start. |
| 32 | 8 | `byte_len` | the segment payload length in bytes (the item's `payload_len`); > 0; at most 1 GiB. |
| 40 | 4 | `key_count` | 1 to 4096, **per segment** (`PV_VIDEO_INDEX_MAX_KEYS`): a 3 s segment holds three keys at the chosen settings, and the bound leaves room for one key frame per frame at 60 fps for over a minute of segment. The number of keys in the whole index is bounded only by `segment_count` times this and by the 8 MiB size (an hour at 1 s GOPs is 3 600 keys over 1 200 segments). |
| 44 | 4 | `flags` | 0 when writing, ignored when reading. |
| 48 | 4 x `key_count` | `key_offset_us` | microseconds from `start_us`; strictly increasing; the first is 0; every one below `duration_us`. |

Bounds, all checked by the encoder and the decoder: `segment_count` 1 to 16384 per index
(13.6 hours at 3 s), `key_count` 1 to 4096 per segment, and the whole index at most 8 MiB
(`PV_VIDEO_INDEX_MAX_BYTES`), which the decoder refuses before parsing and the encoder
refuses from the computed size. Lengths past the buffer at any point:
`PV_ERR_INDEX_MALFORMED`. An hour of video at 3 s segments and 1 s GOPs is 1 200 records of
60 bytes, 72 KB; the 8 MiB bound is reached only by about 16 000 segments with 120 keys
each.

Room without a version change: `header_len` lets a later writer append header fields; the
audio fields exist already; `flags` per segment and `reserved` in the header are zero now.
A second track's segment objects (if audio ever lives in its own objects) would be a version
2 change; in 3E audio is planned as a second track inside the same fragmented MP4 segment,
which needs only the three audio header fields.

### 4.3 Seek rule (CP-3)

Seek to clip time T (microseconds):

1. Segment `s` = the last segment with `start_us <= T`; if T is past the end, the last
   segment and its last key frame.
2. Key `k` = the last key offset of `s` with `start_us + key_offset_us <= T`.
3. Decrypt segment `s` into guarded memory (3B pipeline), hand it to the player, seek the
   player to `start_us + key_offset_us[k]` of `s`, decode forward to T.

Granularity one GOP (1 s); worst case one segment decrypt (about 150 ms for 2 MB on the
phone in the debug build, less in release) plus one GOP of decode. The core provides the
arithmetic of steps 1 and 2 (`pv_video_index_locate`) so both platforms and the T1 tests
share it.

### 4.4 Core ABI (CP-4)

Additive, in `pv.h` after the Stage 3B functions, portable, no media3. Error codes 27 to 29.

```c
#define PV_ERR_INDEX_MALFORMED 27
#define PV_ERR_INDEX_UNSUPPORTED 28
#define PV_ERR_NOT_INDEX 29              // no PVVINDEX magic; a classification, as PV_ERR_NOT_ITEM

#define PV_VIDEO_INDEX_MAGIC "PVVINDEX"
#define PV_VIDEO_INDEX_VERSION 1
#define PV_VIDEO_INDEX_HEADER_BYTES 64
#define PV_VIDEO_INDEX_SEGMENT_BYTES 48
#define PV_VIDEO_INDEX_MAX_SEGMENTS 16384
#define PV_VIDEO_INDEX_MAX_KEYS 4096
#define PV_VIDEO_INDEX_MAX_BYTES (8u * 1024u * 1024u)
#define PV_VIDEO_CODEC_H264 1
#define PV_VIDEO_CODEC_H265 2
#define PV_AUDIO_CODEC_NONE 0
#define PV_AUDIO_CODEC_AAC 1

typedef struct pv_video_index_header {
    uint16_t version; uint16_t header_len; uint16_t video_codec; uint16_t rotation_degrees;
    uint32_t width; uint32_t height; uint32_t frame_rate_millihz; uint32_t segment_count;
    uint64_t total_duration_us; uint64_t created_unix_ms;
    uint16_t audio_codec; uint16_t audio_channels; uint32_t audio_sample_rate;
} pv_video_index_header;

typedef struct pv_video_segment {
    uint8_t object_id[PV_OBJECT_ID_BYTES];
    uint64_t start_us; uint64_t duration_us; uint64_t byte_len;
    uint32_t key_count; uint32_t flags;
} pv_video_segment;

// Bytes an index with these segments needs (header plus records plus key offsets).
int pv_video_index_size(const pv_video_index_header* h, const pv_video_segment* segs, size_t* out);
// Writes the index. keys holds the key offsets of all segments back to back, in segment
// order (sum of key_count entries). Every rule of 4.2 is checked; a writer cannot
// produce an index its reader rejects.
int pv_video_index_encode(const pv_video_index_header* h, const pv_video_segment* segs,
                          const uint32_t* keys, size_t keys_len, uint8_t* out, size_t cap, size_t* out_len);
// Reads the header and the counts without the records (segment_count tells the caller
// how much to allocate; keys_total the number of key offsets).
int pv_video_index_decode_header(const uint8_t* buf, size_t len, pv_video_index_header* out, size_t* keys_total);
// Reads everything, validating every rule. segs_cap and keys_cap are entry counts.
int pv_video_index_decode(const uint8_t* buf, size_t len, pv_video_index_header* h_out,
                          pv_video_segment* segs_out, size_t segs_cap,
                          uint32_t* keys_out, size_t keys_cap, size_t* keys_len_out);
// The seek rule of 4.3 on a decoded index: segment and key index for time_us, plus the
// key frame's clip time.
int pv_video_index_locate(const pv_video_index_header* h, const pv_video_segment* segs,
                          const uint32_t* keys, uint64_t time_us,
                          uint32_t* segment_out, uint32_t* key_out, uint64_t* key_time_us_out);
```

`pv_error_name` learns the three codes; `NativeBridge` mirrors them. JNI entry points
(Milestone D), shapes per D-011 (status returned, data through arrays): the header crosses
as a `LongArray` of 13 in field order; segment records as a flat `ByteArray` of IDs and a
`LongArray` of 5 per segment (`start_us`, `duration_us`, `byte_len`, `key_count`, `flags`);
key offsets as one `IntArray` in segment order. `nativeVideoIndexEncode(header, ids,
segFields, keys, statusOut): ByteArray?`, `nativeVideoIndexDecodeHeader(buf, offset, length,
headerOut, keysTotalOut): Int`, `nativeVideoIndexDecode(buf, offset, length, headerOut,
idsOut, segFieldsOut, keysOut, countsOut): Int`, `nativeVideoIndexLocate(header, segFields,
keys, timeUs, out): Int`. The Kotlin `VideoIndex` class (main source set, no media3) holds
the fields, encodes, decodes, locates, and reads an index object through the viewer load.

### 4.5 Golden vectors (T1 and T2)

`tests/fixtures/v1/video_index_min.bin` (one segment, one key), `video_index_three.bin`
(three segments, 3, 2, and 4 keys, audio fields zero), `video_index_audio.bin` (audio
fields set to AAC, 2 channels, 48 000, so a 3C reader accepts a 3E header), each with its
fields in `video_index.txt`; and `item_video_index.pvo`, an item object of `media_type` 3
whose payload is `video_index_three.bin`, under the test VMK, so both platforms decrypt it
and decode the index. Generated by `make_vectors --video-index`. Synthetic values only.

## 5. Playback probe (brief Section 7; debug only)

### 5.1 Pieces

- `media3-exoplayer` 1.11.1 `debugImplementation` (brings `media3-datasource` and
  `media3-database`; `media3-extractor` and `media3-decoder` are already in debug). The
  merged debug manifest is checked in Milestone D and every added entry recorded (found:
  `WAKE_LOCK` from `media3-exoplayer`, removed again in the debug manifest since the probe
  holds no wake lock; no service, receiver, or provider added); release
  and benchmark unchanged (0 media3 classes).
- `VideoProbeActivity` (debug source set, exported only through `ScriptActivity` handoff,
  not exported itself; `FLAG_SECURE`; `SurfaceView` with `setSecure(true)`;
  `LockController.Listener`). Buttons: Play, Pause, Seek, Back. Scripted action
  `video_play=<index object hex | latest>[:seeks=<n>][:hold=<ms>][:secure=0]` from the
  vault screen; `hold` keeps the last frame up for the capture checks, `secure=0` leaves the
  surface unsecured for comparison (Milestone E: the picture is the same on the Xiaomi).
  The probe samples the `SurfaceView` through `PixelCopy` (64x64, mean luma and colour
  count, no image kept) 2 s after the first frame and at the hold, so a black screen can be
  told apart from a dark recording.
- `GuardedSegmentDataSource`: a media3 `DataSource` whose `open(DataSpec)` resolves
  `pv://segment/<n>` to segment `n` of the index, decrypts it through `Vault.viewerLoad`
  (the 3B pipeline, 32 MiB cap; Milestone D widened the load to return the buffer for
  video segment and index items, not only JPEG) into guarded memory, and serves `read`
  from that direct buffer; `close` frees (wipes) it. ExoPlayer opens a segment's source
  when it extracts that segment into its sample queue and closes it when the extraction
  ends, so with the small load control below at most the segment being extracted and,
  briefly, the one before it are decrypted at once. `LiveBuffers` counts the open buffers
  and their bytes and keeps the peak (logged at every open and close).
- Seek map: `FragmentedMp4Extractor` reports an unseekable stream when there is no `sidx`
  box, and ExoPlayer then treats every seek into an unloaded segment as a seek to its
  start. The data source therefore builds a media3 `ChunkIndex` from the segment's own
  fragment table at open time (`moof` byte offsets and sizes, the `mdhd` timescale, and
  each fragment's start time as the sum of the `trun` sample durations before it: the
  media3 muxer writes no `tfdt`, measured in Milestone D), and `SeekableSegmentExtractor`
  wraps `FragmentedMp4Extractor` (default flags, so per-sample sync flags are honoured),
  hands that map to the player in place of the unseekable one, and adds the fragment's
  start time back to the sample times after a seek to a fragment offset (without a
  `tfdt` the extractor numbers a fragment it was seeked to from zero; the first probe
  build showed wrong frames with a correct-looking position until this was added). One
  fragment per GOP (Section 2.5) makes every key frame a seek point.
- Container times and the index: the muxer gives the last sample of every fragment the
  duration of the sample before it (`LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS`
  in media3 1.11.1, confirmed in its source), so when the frame right before a key frame
  arrived after an uneven gap, every container time after that fragment boundary is
  shifted by the difference between the two durations (141 ms once on the emulator under
  load; 0 with even delivery). The index keeps the encoder's key-frame times (D-030); a
  seek still lands within one GOP, one frame gap at most away from the key frame. 3E can
  remove the shift by writing `tfdt` (or the true last durations) in its own muxing step
  (Section 10).
- Playlist: one `MediaItem` per segment with `pv://segment/<n>`, a `ProgressiveMediaSource`
  over the factories above, `DefaultLoadControl` with 2 to 4 s of buffer (the data is
  local). The player renders to the secure `SurfaceView`.
- Seek: the probe maps a clip time to `(segment, key time)` through
  `nativeVideoIndexLocate`, then `player.seekTo(segmentIndex, keyTimeInSegmentMs)`; ExoPlayer
  decodes forward from that key frame. Latency is measured from the call to the first
  frame the renderer releases after it (`VideoFrameMetadataListener`), together with the
  frame's time and the position the player reports.
- Measurements, both devices, logged as numbers only: time to first frame
  (`onRenderedFirstFrame` after `prepare`); at each segment boundary the gap between the
  release times of the last frame of segment `n` and the first of `n + 1` (a frame whose
  time is below the previous one starts the next segment, since segment times start at
  zero; `onMediaItemTransition` and `onPositionDiscontinuity` are logged beside it); seek
  latency for ten positions (five segment starts, five at 1.5 s into a segment, spread over
  the clip); peak Java and native heap and PSS sampled every 200 ms (`Debug`); decrypted
  bytes held at once (peak of the live guarded buffers).

### 5.2 Lock rules

`LOCKED_UI` posted by `LockController` (background, screen off, idle, manual): the probe
activity stops playback, releases the player (which closes every open `DataSource`, freeing
and wiping the guarded buffers), finishes to the lock screen. A decrypt in flight inside a
`DataSource.open` is cancelled by `Vault.close()` through the 3B flag; `open` throws, the
player reports an error that the finishing activity ignores. `onStop` does the same without
the lock.

### 5.3 Where plaintext lives during playback (CP-5)

| Place | Owner | Lifetime | Can the app wipe it |
|---|---|---|---|
| Guarded segment buffer (current and next segment) | app (`sodium_malloc`) | until the player moves past the segment or stops | yes, `pv_secure_free` wipes |
| `DataSource.read` target arrays and ExoPlayer's sample queue (`DefaultAllocator` byte arrays) | media3, Java heap | until the allocator reuses or the player is released; survive until collected | no (media3 objects; the app can only release the player) |
| Extractor scratch (`ParsableByteArray` in `FragmentedMp4Extractor`) | media3, Java heap | per fragment | no |
| Decoder input buffers (`MediaCodec` input `ByteBuffer`) | platform, native | per sample until released | no |
| Decoded frames (codec output, surface buffers) | platform and vendor, native and GPU | until displayed and recycled | no |
| Compositor and display | platform | per frame | no; `FLAG_SECURE` and `setSecure` keep them out of captures and recents |

The threat model Section 9 records this; the app's claim is limited to the guarded buffers
and to releasing the player on lock.

### 5.4 Not in 3C

No playback in the release viewer (3D), no capture feature, no audio, no viewfinder
mirror (3E). The probe's `DataSource`, index reader, and lock handling are the pieces 3D
moves into the viewer.

## 6. Build and dependencies

- Core: `src/vault_core/src/video_index.cpp` (new), `pv.h`, `pv_init.cpp`; T1
  `test_video_index.cpp`; `make_vectors --video-index`; fixtures of 4.5.
- Debug: `media3-extractor` (Milestone A) and `media3-exoplayer` (Milestone D), both
  1.11.1. No change to release or benchmark. `versionCode` 5, `versionName` 0.5.0.

## 7. Failure handling

| Situation | Behaviour | Test |
|---|---|---|
| Index object missing or not an index (`PV_ERR_NOT_INDEX`) | the clip is not listed as playable; segments stay unknown items (as today) | T2 |
| Index malformed or unsupported | refused with the code name; nothing decrypted beyond the index | T1 every field, T2 one case |
| Segment object of the index missing or damaged | the `DataSource.open` fails for that segment, the player reports an error, the probe logs the segment number and the code | T2 |
| Segment above the 32 MiB cap | `open` fails with the size code; no allocation | T1 (core cap) |
| Lock during playback | 5.2 | T2 |
| Lock during a segment decrypt | cancelled through the flag, buffer wiped (3B), player error ignored | T2 |
| Seek past the end | last segment, last key frame | T1 `locate` |

## 8. Test plan

**T1 (Windows).** All 17 existing tests. `test_video_index`: size, encode, decode round trip
for 1, 3, and 1 000 segments with 1 to 4096 keys; `decode_header` counts; every header
rule (magic absent gives `NOT_INDEX`; version 2, codec 3, rotation 45, width 0 and 16385,
segment count 0 and 16385, `header_len` 63, 4097, past the buffer, total duration off by one;
audio codec 2); every segment rule (start not increasing, duration 0, overlap beyond a frame
period, `byte_len` 0 and above 1 GiB, `key_count` 0 and 4097, key offsets not increasing,
first key offset 5, a key offset at or past the duration); truncation at every length of
the three-segment vector and one trailing byte; `header_len` 72 with extra bytes accepted;
boundary tests for both bounds: one segment with 4096 keys accepted and 4097 refused, 16384
segments with one key each accepted and 16385 refused, an index whose computed size is one
record above 8 MiB refused by `size`, `encode`, and `decode` (a buffer of that length);
`locate` for times before the start, at each segment start, inside GOPs, at the end, past
the end; argument checks; error names. `test_golden`: the four new vectors.

**T2 (emulator).** All 63 existing tests. `VideoIndexTest`: JNI round trip, the golden
vectors through the item path, `locate` through JNI. `VideoProbeTest`: the V3b recording at
the approved settings writes index v1 whose key counts equal the encoder and container
counts, every segment's first sample is a key frame (the first `trun` per-sample flag of
every segment is sync, and the encoder log marks the first buffer of every segment as a key
frame), and every key time in the index equals the PTS of a key frame in the encoder log
(developer confirmation); `video_play` on that clip renders a first frame, crosses every boundary, seeks to
ten positions (the player position after each seek lies within one GOP before the target,
and at the exact key time for segment starts), lock during playback releases the player and
frees the buffers (free counter), lock during a slowed decrypt cancels it; `FLAG_SECURE` on
the probe window and `isSecure` on the surface; `leak_scan` before and after. Host-driven:
merged manifests of the three variants, class scan (0 media3 outside debug), screen
capture and recents card during playback (black), the Phase 2 photo and 3B viewer flows
unchanged.

**T3 (Xiaomi).** Brief Section 9: clips at the approved settings with encoder, container,
and index key-frame counts agreeing; playback, boundary gaps, seek latency, memory on the
hardware decoder; Home, screen off, MIUI camera case during playback; recents card black;
`leak_scan` zero. Phone cleanup through the app's `delete_object` action, after telling the
developer: every 3C test recording, every prototype index object, and the Phase 2 video
objects (segments and index); photos stay (developer instruction).

## 9. Checkpoint items (CP-1 to CP-7)

| # | Item | Proposal |
|---|---|---|
| CP-1 | Settings | 3 s segments cut on the encoder's actual key frames (the third after the segment start), 1 s GOP by time-based request, `KEY_I_FRAME_INTERVAL` 10 s as backstop, fragment 1000 ms, encoder frame rate from the measured delivery; the index records actual key-frame times (Section 3; approved with that confirmation) |
| CP-2 | Index layout | 64-byte header with audio fields and `header_len`, 48-byte segment records, u32 key offsets; magic `PVVINDEX`; bounds 16384 segments per index, 4096 keys per segment, 8 MiB per index, each with boundary tests (4.2; approved with that confirmation) |
| CP-3 | Seek rule | last segment at or before T, last key at or before T, decode forward; `locate` in the core (4.3) |
| CP-4 | ABI | six functions, codes 27 to 29, `PV_ERR_NOT_INDEX` as a classification like `NOT_ITEM` (4.4) |
| CP-5 | Plaintext claim | app-owned guarded buffers only; media3 and codec copies listed as outside the app (5.3) |
| CP-6 | Playlist shape | one `MediaItem` per segment over a custom `DataSource` with `FragmentedMp4Extractor` default flags; current and next segment decrypted at once (5.1) |
| CP-7 | Debug-only footprint | `media3-exoplayer` in debug only; `VideoProbeActivity` in the debug source set, reached through `ScriptActivity`; version 0.5.0 (6) |

## 10. Seams for 3D and 3E

- 3D: `GuardedSegmentDataSource`, the index reader, and the lock handling move from the
  probe into the viewer; `PhotoView` gains a sibling for video; the gallery's `unknown item`
  tiles for `media_type` 2 and 3 become a clip tile keyed by the index object.
- 3E: the audio header fields; `flags` per segment and `reserved` in the header; the
  prototype's Java `ByteArrayOutputStream` segment buffer becomes a native guarded buffer;
  the segment container gets a `tfdt` per fragment (or a `sidx`) and true last-sample
  durations, so any reader seeks inside a segment without the extractor wrapper of the
  probe (Section 5.1) and container times equal the index times;
  the viewfinder mirror; the recovery rule for segments without an index (the reserved item
  header bytes could carry a clip ID and sequence number).
- Phase 4: the probe's secure `SurfaceView` is where the temporal-interference drawing
  would composite.

## 11. Threat model deltas (for `docs/THREAT_MODEL.md` Section 9)

- Plaintext during playback: the guarded buffers of at most two segments (wiped by the
  app), media3 heap copies and codec buffers (outside the app), decoded frames and
  compositor buffers (outside the app; `FLAG_SECURE` and `setSecure` for captures).
- The index reveals the clip structure (segment count, durations, key-frame grid) only
  after decryption; on disk it is one more object whose size (tens of KB) is visible.
- Segments of one clip are distinguishable on disk by size and adjacent modification times
  (Phase 2 position, unchanged).
- A player library (media3) now processes plaintext in the debug variant; nothing of it
  ships in release until 3D decides.
