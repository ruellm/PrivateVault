# Phase 3D design: video playback in the gallery and viewer, release build

Status: implemented (Milestones C to E done 2026-10-20; results in
`docs/PHASE3D_TEST_REPORT.md`). Approved by the developer 2026-10-20 at the Milestone B
checkpoint with three amendments, applied below: the sweep works over the object list taken at its start and
never runs beside a recording, with a T2 test for a recording started right after an unlock
(4.6, 7); the video surface keeps the screen on while playing without a wake lock (4.3);
the two T3 clips stay on the phone as the regression clips for the 3E muxing change
(`docs/ROADMAP.md`). Items marked **CP-n** are the choices this document makes beyond the
brief, or where the brief leaves the default to the design (Section 8). "Measured" names a run in `docs/PHASE3D_PROGRESS.md` (Milestone A) or
in the Stage 3C records; everything else is design intent to be confirmed by Milestones C
and D.

Facts from earlier stages are not repeated: `docs/PHASE3C_DESIGN.md` (index v1, the seek
rule, the probe pieces), `docs/VIDEO_PLAYBACK_PROBE.md` (playback measurements, the two
media3 behaviours), `docs/PHASE3B_DESIGN.md` (viewer pipeline, guarded buffers,
cancellation, gallery, thumbnail cache, delete). Nothing here changes the version 1 object
format, the item header, envelope providers 1 to 3, or index v1.

## 1. Summary

The playback probe of 3C becomes the viewer: its data source, seek map, and extractor
wrapper move from the debug source set into the main one, `media3-exoplayer` is declared for
every variant with own controls and no `media3-ui`, and a `VideoActivity` plays one clip
over a secure `SurfaceView` with tap to play or pause, a seek bar, and elapsed and total
time. The gallery classifies objects through a new core call that decrypts only the first
chunk of an object and reads its item header; an index object becomes one video tile with a
first-frame thumbnail, a play mark, and the duration, and segment objects are not listed.
The thumbnail is the first key frame of the first segment, decoded through the platform
codec from the guarded segment buffer into a 256 px software bitmap under the same cache and
erase rules as photo thumbnails. Delete removes the index first and then the segments; a
sweep after every unlock removes segment objects that no readable index names. The index
rotation, which the muxer does not write into the container, is applied by the player
through the sample format. Measured in Milestone A: `media3-exoplayer` adds 5.0 MB and 2577
classes to the release and benchmark APKs and one permission to their manifests, which a
merge rule removes; the thumbnail decode takes about 50 ms on the emulator with the
segment buffer live for under 50 ms.

## 2. Milestone A results (measured)

### 2.1 Gates

`git status` clean apart from the untracked brief. T1 18/18 in Debug and Release, 0 build
warnings. T2 67 tests, 0 failures, 1 skipped (`LockBoundKeyTest`, as in every stage since
3A). Lint 0 errors.

### 2.2 `media3-exoplayer` in every variant (trial, reverted)

| Item | Before (3C) | With `implementation("androidx.media3:media3-exoplayer:1.11.1")` |
|---|---|---|
| Release APK (unsigned) | 10 619 505 bytes | 15 628 059 bytes (+5 008 554) |
| Benchmark APK | 10 627 697 bytes | 15 636 251 bytes (+5 008 554) |
| Classes defined, release and benchmark | 7345, 0 in `androidx.media3` | 12 056, **2577** in `androidx.media3` |
| Resolved release tree | no media3 | `media3-common`, `-container`, `-database`, `-datasource`, `-decoder`, `-exoplayer`, `-extractor`, all 1.11.1 |
| Merged release and benchmark manifests | `CAMERA`; 5 activities, `LockActivity` exported; 1 disabled service; no receiver or provider | the same plus `android.permission.WAKE_LOCK` from `media3-exoplayer` |
| Native libraries | `libprivatevault_jni.so`, `libimage_processing_util_jni.so`, `libsurface_util_jni.so` per ABI | unchanged (media3 has no native code) |
| Debug APK | 20 601 168 bytes, 2637 media3 classes | unchanged (the muxer accounts for the 60 extra classes) |

### 2.3 First-frame thumbnail through `MediaCodec` (emulator; phone pending)

Debug-only trial code `VideoFirstFrame.kt` and `VideoThumbProbe.kt`, five runs over the
first segment (1.57 MB) of a 12.7 s 1280x960 clip with rotation 90:

| Step | First run | Runs 1 to 4 |
|---|---|---|
| Segment decrypt into guarded memory | 13 ms | 11 to 12 ms |
| Parse (`avcC`, first `moof`) | 0 ms | 0 ms |
| Codec create, configure, start, sample queued | 92 ms | 27 to 37 ms |
| Segment buffer live (decrypt to free) | 108 ms | 39 to 50 ms |
| Wait for the frame | 0 ms | 0 to 1 ms |
| YUV to a 256x192 bitmap | 59 ms | 3 to 4 ms |
| Total | 170 ms | 46 to 57 ms |

Codec `c2.goldfish.h264.decoder`, first sample 50 542 bytes, bitmap 256x192 turned to
192x256 by the rotation, mean luma 98 with 4945 colours in every run (the 3C surface sample
of this scene read 99).

Xiaomi (measured after the checkpoint, `build/p3d/phone/A2-thumb.txt`), hardware decoder
`c2.qti.avc.decoder` in buffer mode, first segment 1 951 510 bytes of an 11.65 s 1600x1200
clip with rotation 90, first sample 29 466 bytes, five runs:

| Step | First run | Runs 1 to 4 |
|---|---|---|
| Segment decrypt (debug build) | 79 ms | 75 to 79 ms |
| Parse | 0 ms | 0 ms |
| Codec create, configure, start, sample queued | 254 ms | 204 to 218 ms |
| Segment buffer live | 336 ms | 284 to 295 ms |
| Wait for the frame | 43 ms | 46 to 51 ms |
| YUV to a 256x192 bitmap | 97 ms | 16 to 90 ms |
| Total | 535 ms | 405 to 488 ms |

Mean luma 58 with 10 471 colours in every run; the prototype's own decode of the same
frame read luma 67 in the YUV plane. PSS rose from about 65 to 105 MiB during a run (the
codec's buffers) and fell back between runs. Buffer mode works on this decoder, so the
`ImageReader` route the brief names as the alternative is not needed.

### 2.4 Segment lifetime inside ExoPlayer (3C logs)

Each segment's data source is opened, extracted whole, and closed before the next one
opens; on the phone all four segments of the 11.5 s clip were opened and closed within
the first 2 s of playback. The guarded buffer of a segment therefore lives only for its
decrypt and extraction (100 to 220 ms on the phone), one at a time; the samples then live in
the player's sample queue until played or the player is released (Section 4.3).

## 3. Dependencies and manifests (P3D-1; CP-1)

- `androidx.media3:media3-exoplayer:1.11.1` becomes `implementation` (every variant).
  `media3-muxer` and `media3-extractor` stay `debugImplementation` as declared; the
  extractor arrives in release transitively (Section 2.2), the muxer does not. No
  `media3-ui`, no `media3-session`, no other media3 artifact. The version is pinned to the
  one the probe measured with.
- The `WAKE_LOCK` removal (`tools:node="remove"`) moves from the debug manifest to the main
  manifest; the app holds no wake lock (the screen-off lock is the rule, Section 4.3). The
  `ACCESS_NETWORK_STATE` rule of D-013 stays. The expected merged release and benchmark
  manifests: `CAMERA` only, `LockActivity` the only exported activity, six activities
  (the five of today plus `VideoActivity`), the disabled CameraX service, no receiver, no
  provider. Debug adds `ScriptActivity` (exported) and `VideoProbeActivity`.
- Expected sizes: release and benchmark about 15.6 MB (Section 2.2) plus the new code; R8
  stays off (Phase 6 hardening item, `docs/ROADMAP.md`), and the size is recorded in the
  test report, not optimised here.
- `docs/DEPENDENCIES.md` gains the release record of `media3-exoplayer` and its transitive
  artifacts; `docs/THREAT_MODEL.md` records that a media library now processes plaintext in
  the release variant (Section 10).
- Version 0.6.0 (`versionCode` 6).

## 4. Architecture

### 4.1 Source layout

| File | Today | 3D |
|---|---|---|
| `GuardedSegmentDataSource.kt` (`LiveBuffers`, `GuardedSegmentDataSource`, `SegmentSeekMap`, `SeekableSegmentExtractor`) | debug | main, unchanged apart from the rotation in the format (4.2) and a test hook (Section 6) |
| `VideoFirstFrame.kt` | debug (trial) | main (4.5) |
| `VideoActivity.kt` | none | main (4.2), declared in the main manifest, not exported |
| `VideoItems.kt` (index read for the gallery, segment checks, the sweep) | none | main (4.4, 4.6) |
| `GalleryActivity.kt`, `PhotoLoader.kt` | main | classification through the header peek, the video tile, hidden segments (4.4) |
| `Vault.kt`, `NativeBridge.kt`, `jni_bridge.cpp` | main | `itemHeader` over the new core call (4.7) |
| `LockController.kt` | main | runs the sweep after an unlock (4.6) |
| `MainActivity.kt` | main | scripted actions `video_open`, `delete_video`, `sweep` (4.8) |
| `VideoProbeActivity.kt`, `VideoThumbProbe.kt`, `DebugVideoProbes.kt` | debug | debug; the probe keeps working over the moved classes and stays the measurement tool |
| `src/vault_core/src/viewer.cpp`, `pv.h`, `tests/core/test_viewer_buffer.cpp` | core | `pv_object_item_header` and its tests (4.7) |

### 4.2 Player pipeline: `VideoActivity`

- Not exported; `FLAG_SECURE`; routes to the lock screen unless `UNLOCKED`;
  `LockController.Listener`; the only extra is the index object ID. Layout: a black frame
  holding a `SurfaceView` with `setSecure(true)`, below it a row with the elapsed time, a
  platform `SeekBar`, and the total time, then Back and Delete, then the in-activity
  confirmation of 3B and a status line.
- Load: `VideoIndex.read` on the viewer thread (`pv-view`). A status other than `OK`
  shows the code name and leaves Delete available (4.6); nothing else is decrypted.
- Player: built as the probe builds it. `ProgressiveMediaSource.Factory` over
  `GuardedSegmentDataSource.Factory` and `SeekableSegmentExtractor.Factory`; one
  `MediaItem` per segment (`pv://segment/<n>`); `DefaultLoadControl` with 2000, 4000, 500,
  1000 ms and the default back buffer of 0 ms, so played samples are released at once;
  `setVideoSurfaceView`; `playWhenReady = true` once prepared.
- **Rotation (CP-2).** The muxer is given no rotation and writes none into the container
  (measured: both devices record rotation 90 in the index, and the probe rendered the
  frames unrotated). `SeekableSegmentExtractor` is given the index rotation and sets
  `rotationDegrees` on the track format it passes through; ExoPlayer hands it to the codec
  as `KEY_ROTATION`, so the decoder renders the frame upright on the surface and reports
  the swapped size. The activity sizes the `SurfaceView` to the reported video size inside
  its frame (`onVideoSizeChanged`), letterboxed on black. The thumbnail is turned by the
  same value (4.5), so tile and picture agree. The emulator orientation sensor reports
  portrait while the capture rotation is 90 (Stage 3B finding), so the by-eye check of the
  orientation is a phone step (T3).
- **Controls (P3D-4).** A tap on the picture toggles play and pause; after the clip ended a
  tap seeks to the start and plays. The seek bar spans `total_duration_us`; a drag that
  ends (`onStopTrackingTouch`) at clip time T goes through `index.locate(T)` to
  `player.seekTo(segment, keyTimeUs - startUs)`, as the probe does. Elapsed time is
  `segments[currentItem].startUs + player.currentPosition`, refreshed every 200 ms while
  playing and on every seek; the bar follows it. No speed control, no audio, no scrubbing
  preview (3E).
- Position after a seek shows the key frame the rule chose, at most one GOP before the
  target (3C seek rule). The seek latency measured in 3C (142 to 373 ms on the phone) is
  the time until the frame appears; the bar jumps at once.
- Errors: `onPlayerError` shows the code name and, for a segment that failed to open, the
  segment number and the core status (`SegmentOpenException`), releases the player, and
  waits for the open data source to close (the probe's 2 s bound; 0 to 5 ms measured).
  The activity stays so the user can go Back or Delete. No frame from a segment that did
  not authenticate can appear: `viewerLoad` returns a buffer only after the object's final
  chunk verified, and the data source serves bytes from nothing else (brief 4.2).

### 4.3 Buffer lifetime, lock rules, idle rule

- A segment's guarded buffer lives from `DataSource.open` to `close`: the decrypt plus the
  extraction of that segment, one at a time as measured (Section 2.4), two at most while
  the loader closes one and opens the next. It is wiped and freed on `close`, which happens
  when the extraction ends, when the player releases (lock, stop, error, Back, Delete),
  and when a seek cancels a load in flight (ExoPlayer cancels the loader, which closes the
  source). That is the brief's "current and next segment" bound with the measured
  behaviour behind it.
- What outlives the buffer: the extracted samples in the player's sample queue (media3
  `DefaultAllocator` arrays in the Java heap), for the clip read ahead. Measured in
  Milestone D on the emulator (`build/p3d/E3-read30.txt`): on a 30 s clip of nine
  segments the viewer extracted segments 0 to 5 (about 20 s, 5.6 MB of samples) within the
  first 1.7 s, then opened segment 6 and held its guarded buffer (0.96 MB) open for 18 s
  while the load control paused the loader, resumed it as playback reached segment 5, and
  took segments 7 and 8 the same way. On a 12 s clip in the same session the opens came
  one segment ahead of playback. So the sample queue holds up to about 20 s of a long
  clip on this emulator, more than the 4 s the load control names, and a guarded buffer
  can stay live for the length of the pause, one at a time. Those arrays are released
  with the player and collected, not wiped (Section 5). Bounding the read-ahead more
  tightly (a byte threshold on the load control, or one media source over all segments)
  is left to 3E with the muxing change (Section 9).
- Lock (background, screen off, idle, manual) and `onStop`: stop, release the player,
  wait for the open source to close, finish to the lock screen (as the probe, 3C Section
  5.2). A decrypt in flight is cancelled by `Vault.close()` through the 3B flag; the open
  throws and the error is ignored while the activity finishes.
- **Idle rule (CP-3).** The position ticker calls `LockController.touch()` on every tick
  while `player.isPlaying`, so a playing clip counts as activity and a clip longer than the
  idle timeout does not lock mid-play (brief 4.1). Paused or ended, nothing is touched and
  the idle lock fires after the timeout as elsewhere. No separate pause timeout: the
  guarded buffers are already closed during a pause (Section 2.4); what a pause holds is
  the sample queue and the codec buffers, and the idle lock, the screen-off lock, and the
  background lock release them.
- **Screen (checkpoint amendment).** The video surface keeps the screen on while the
  player is playing (`SurfaceView.keepScreenOn = true` while `isPlaying`, false when paused
  or ended); no wake lock, no `WAKE_LOCK` permission (Section 3). The screen-off lock is
  unchanged: the power button, or the system timeout once the clip is paused or ended,
  turns the screen off and locks the vault as before.

### 4.4 Gallery (P3D-2; CP-4)

- Classification. `refresh()` lists the objects as today, then queues one classification
  per object on the viewer thread, newest first, through `Vault.itemHeader` (the header
  peek of 4.7, one chunk per object). Until classified a tile shows "loading". Kinds:

| Tile | Decided by | Decrypted for it |
|---|---|---|
| photo | `media_type` 1; the thumbnail loads lazily as in 3B | one chunk, then the whole object for the thumbnail |
| video | `media_type` 3 and the payload decodes as index v1; every segment it names exists and its plaintext length equals the item header length plus `byte_len` (sizes from the headers, no decrypt) | one chunk, then the index object (about 300 bytes); the first segment for the thumbnail (4.5) |
| damaged (reason) | `media_type` 3 whose index fails to decode (the code name); an index naming a segment that is missing or whose length does not match ("segment n NOT_FOUND", "segment n length"); any decrypt failure of the peek (`AUTH_FAILED`, `WRONG_VMK`, `TRUNCATED`, ...) | one chunk, and the index when present |
| unknown item | `NOT_ITEM`, `ITEM_MALFORMED`, `ITEM_UNSUPPORTED`, `media_type` 3 with `NOT_INDEX` (the Phase 2 prototype indexes), any other media type | one chunk |
| large item, out of memory, cancelled | as in 3B | as in 3B |
| hidden | `media_type` 2 (a segment): removed from the list, whether or not an index names it | one chunk |

- A video tile shows the thumbnail (or a grey tile with the word "video" when the
  thumbnail failed), a play mark, and the duration as `m:ss` from `total_duration_us`.
  A damaged video tile shows "damaged: <reason>" with no image. Tapping a video or a
  damaged video tile opens `VideoActivity`, which shows the picture or the error and
  offers Delete in both cases, so a damaged clip can be removed. An object whose first
  chunk fails to decrypt has no readable header, so its damaged tile cannot say whether
  it was a clip; it opens the photo viewer, whose error state offers Delete too, and the
  next sweep removes the segments of a deleted index.
- The grid reflows as segments drop out of the list; with the index newer than its
  segments and classification newest first, a clip's segments are usually gone before
  their cells bind. Hiding is by the object's own header, not by an index naming it, so a
  segment of a damaged clip or of an interrupted recording is hidden too; the sweep
  (4.6) or the delete of the damaged tile removes it.
- Cost: one 64 KiB chunk per object per gallery open, instead of the whole object the 3B
  gallery decrypts to decide a tile. The 53 objects of the phone vault classify in well
  under a second (to be measured in T3).

### 4.5 Thumbnails (P3D-3)

- `VideoFirstFrame` (the trial code of Milestone A, moved to main; platform classes only):
  decrypt the first segment named by the index through `viewerLoad` (the whole object,
  32 MiB cap, authenticated to the end), parse the `avcC` of the first sample entry and the
  first sample of the first fragment from the buffer, make an H.264 decoder in buffer mode
  with the parameter sets as `csd-0` and `csd-1`, copy the sample into the codec input
  buffer with start codes in place of the length prefixes, queue it and the end of stream,
  then **free and wipe the segment buffer** (measured: the buffer is live for 39 to
  108 ms on the emulator). The decoder's flexible YUV frame is sampled into a mutable
  software `ARGB_8888` bitmap whose long side is 256 px, turned by the index rotation
  (`PhotoLoader.turned`), and put into the gallery's `ThumbCache` under the index ID hex.
  Codec stopped and released on every path.
- Same rules as photo thumbnails: the cache holds 32 entries, erases on eviction, and is
  cleared (erase, recycle) on `onStop` and on lock. The work runs on the viewer thread; a
  lock cancels the segment decrypt through the 3B flag, and a decode in flight ends within
  its bound (5 s timeout in the code, 1 ms measured) with the bitmap erased because the
  gallery is no longer started.
- A parse error, a codec error, no frame within the bound, or a `video_codec` other than
  H.264 gives the generic video tile. The frame's `cropRect` is honoured; nearest-neighbour
  sampling with BT.601 limited-range conversion.

### 4.6 Delete and the sweep (P3D-5; CP-5)

- From `VideoActivity`, behind the in-activity confirmation. Order: release the player
  (every buffer closed), delete the index object, then each segment in index order through
  `Vault.deleteObject`; every status is logged by segment number; the status line reports
  "deleted" or the first failure. `NOT_FOUND` on a segment is not a failure (an earlier
  interrupted delete may have removed it). The thumbnail cache entry of the index goes with
  the gallery refresh.
- An interrupted delete leaves segments without an index. They are hidden in the gallery
  (4.4) and removed by the sweep.
- **Sweep.** `VideoItems.sweep(vault)` runs on the lock thread right after every
  successful unlock (`LockController` posts it after `UNLOCKED`; the unlock itself is not
  delayed) and on the scripted `sweep` action. It takes the object list once at its start
  and considers only those objects: it peeks every listed object's header; for every index
  object it decodes the index and collects the segment IDs it names; if any object's first
  chunk fails to decrypt (an index with a damaged first chunk cannot be told from any other
  damaged object; found by T2 in Milestone D, where the first version skipped such objects
  and swept the clip's segments) or any index fails to decode, the sweep stops and logs
  that it skipped (a damaged index keeps its segments until the damaged tile is deleted,
  after which the next sweep removes them); otherwise it deletes every listed segment
  object no index names and logs the counts. Objects without an item header (`NOT_ITEM`,
  `ITEM_MALFORMED`, `ITEM_UNSUPPORTED`: the Phase 1 samples, for one) do not block it. An
  object written after the list was taken is never touched. The sweep touches no photo, no
  unknown item, and no index. Segments of a recording interrupted before its index was
  written are orphans by this rule; 3E's recovery design may change that (Section 9).
- **Sweep and recordings (checkpoint amendment).** The sweep never runs while a recording
  is active, and a recording does not start while the sweep runs: both go through one
  `VideoItems.recordingGate`, a lock the recorder holds from its first segment to its index
  write and the sweep holds for its run. A sweep that finds the gate held (a recording
  started between the unlock and the sweep) returns at once and logs "sweep: skipped,
  recording active"; it runs again at the next unlock or on the `sweep` action. A
  recording that finds the gate held waits for the sweep, which is bounded by the object
  count (one header peek each). The debug recorder (`V3Probe`) takes the gate like the 3E
  feature will. T2 starts a recording right after an unlock and checks that no segment of
  it is deleted (Section 7).

### 4.7 Core addition (brief 4.5; CP-6)

One additive call, no new error code:

```c
// Reads the item header of an object by decrypting only its first chunk. The
// chunk is authenticated by its own tag and bound to the object header through
// the additional data, so the header bytes are verified; nothing says whether
// the rest of the object is intact (completeness is known only at the final
// chunk; every display path still decrypts the whole object). The plaintext
// length is computed first as pv_object_plaintext_size does and the header is
// checked against it. Codes: PV_OK with *out filled; PV_ERR_NOT_ITEM,
// PV_ERR_ITEM_MALFORMED, PV_ERR_ITEM_UNSUPPORTED from the header; otherwise the
// decrypt code (PV_ERR_AUTH_FAILED, PV_ERR_WRONG_VMK, PV_ERR_TRUNCATED,
// PV_ERR_MALFORMED_HEADER, PV_ERR_MALFORMED_CHUNK, PV_ERR_IO, PV_ERR_NOT_FOUND).
// The chunk buffer is wiped before return on every path. plaintext_len_out
// may be NULL.
int pv_object_item_header(pv_vault* v, const uint8_t* object_id, pv_item_header* out,
                          uint64_t* plaintext_len_out);
```

Implementation in `viewer.cpp`: a sink over a wiped buffer of `PV_ITEM_HEADER_MAX_BYTES`
that keeps the first bytes it receives and then stops the decrypt (the decrypt loop reports
a sink refusal as `PV_ERR_IO`, which the call maps back to its own outcome, as
`decrypt_to_buffer` does for cancel and overflow). An object whose first chunk is also its
final chunk is read to the end as today. The chunk buffer is a `WipedBuffer` (wiped when
the call returns on every path). JNI (D-011 shape): `nativeObjectItemHeader(handle, id,
infoOut: LongArray(8)): Int` returns the core status and on `PV_OK` fills `infoOut` with
`[media_type, rotation, width, height, created_unix_ms, payload_len, header_len,
plaintext_len]`; Kotlin `Vault.itemHeader(id): ItemPeek(status, item, plaintextLen)`. The
desktop CLI is unchanged.

Not added: a call listing the segment IDs of an index (`VideoIndex.read` already returns
them) and a time-to-segment helper (`pv_video_index_locate` covers it).

### 4.8 Scripted actions

On the vault screen (main dispatcher, reached through the debug `ScriptActivity`):
`video_open=<index hex | latest>` opens `VideoActivity`; `delete_video=<index hex>` runs
the delete order of 4.6 and prints each status; `sweep` runs the sweep and prints the
counts; `gallery` exists. `thumb_probe` stays in `ScriptActivity` (debug), `video_play`
stays the probe. `VideoActivity` logs, as the probe does, the first frame time, every
boundary gap, every seek with its landing, and the live-buffer accounting, so the T3 runs
read the same lines.

## 5. Where plaintext lives (brief 4.6)

| Place | Owner | Lifetime | Wiped by the app |
|---|---|---|---|
| Guarded segment buffer, one at a time (two at most at a handover) | app (`sodium_malloc`) | decrypt plus extraction of that segment; 100 to 220 ms measured | yes, `pv_secure_free` |
| Guarded first-segment buffer for the thumbnail | app | decrypt until the sample is queued; 39 to 108 ms measured | yes |
| Guarded index buffer | app | the decode, under a millisecond | yes |
| Header-peek chunk buffer (64 KiB) in the core | app (core) | one call | yes, `sodium_memzero` |
| ExoPlayer sample queue and `DataSource.read` arrays | media3, Java heap | the read-ahead (whole short clips; load control plus whole segments for long ones); released with the player, collected later | no |
| Extractor scratch | media3, Java heap | per fragment | no |
| Codec input buffers (player and thumbnail) | platform, native | per sample | no |
| Decoded frames: codec output, surface buffers, compositor | platform and vendor | per frame; `FLAG_SECURE` and the secure surface keep them out of captures and recents | no |
| Thumbnail YUV output image | platform, native | until the output buffer is released, inside the convert | no |
| Video thumbnail bitmap, 256 px | app, mutable software bitmap | until the gallery stops, the vault locks, or the cache evicts | yes, `eraseColor` then `recycle` |

The app's claim is limited to the rows marked yes and to releasing the player and the
codec on lock; the threat model records the rest (Section 10).

## 6. Failure handling (brief Section 5)

| Situation | Behaviour | Test |
|---|---|---|
| Lock during playback | player released, buffers closed and wiped, activity finished to the lock screen | T2, T3 |
| Lock during the thumbnail's segment decrypt | the decrypt ends with `PV_ERR_CANCELLED` and the buffer is wiped; no bitmap | T2 (slow-decrypt hook) |
| Lock during the thumbnail's codec decode | the decode ends within its bound; the bitmap is erased because the gallery is stopped | T2 (observed through the cache being empty after the lock) |
| Missing segment | damaged tile with the segment number; `VideoActivity` shows the error and offers Delete | T2 |
| Damaged segment (length intact, bytes changed) | video tile as usual; playback stops at that segment with `AUTH_FAILED` and the segment number, player released, no frame from it | T2 |
| Damaged index | damaged tile with the code; `VideoActivity` shows it and offers Delete | T2 |
| Interrupted delete | no gallery entry (segments hidden); the sweep after the next unlock removes them | T2 |
| Damaged index and the sweep | the sweep skips and logs; the clip's segments stay until the damaged tile is deleted | T2 |
| Data source read failure (hook) | `onPlayerError`, error shown, player released, buffers wiped; the codec failure path is the same handler but is not forced | T2 |
| Out of memory on a segment open (3B alloc hook) | the open fails with `OUT_OF_MEMORY`, error shown, player released; with the hook cleared the gallery and viewer work again | T2 |
| Thumbnail parse or codec failure (hook) | generic video tile | T2 |
| Seek past the end, before the start | `locate` clamps (3C) | T1 (existing) |
| Index with `video_codec` 2 | generic tile; playback is attempted (ExoPlayer decodes H.265 where the device can) | not exercised (no such clip) |

## 7. Test plan

**T1 (Windows).** The 18 existing tests. `test_viewer_buffer` gains `pv_object_item_header`:
item objects of one chunk, of three chunks, and with no payload give `PV_OK` with the
fields of the header and the plaintext length; a non-item object gives `NOT_ITEM`; a 0-byte
object `NOT_ITEM`; hand-written headers with rotation 45 and a payload length off by one
`ITEM_MALFORMED`, with media type 9 and item version 2 `ITEM_UNSUPPORTED`; a tampered byte
in the first chunk `AUTH_FAILED`; a tampered byte in a later chunk `PV_OK` while
`pv_vault_verify` of the same object fails (documented: classification only); a file cut
below one chunk of overhead `TRUNCATED`, cut inside the first chunk `AUTH_FAILED` (the
length check reads it as a short final chunk that then fails authentication, the 3B
position on damaged files), cut right after a full chunk `MALFORMED_CHUNK` (no room for a
final chunk, refused by the length check before any decrypt); a wrong key `WRONG_VMK`; a missing
object `NOT_FOUND`; null arguments `INVALID_ARGUMENT`. The chunk buffer is a `WipedBuffer`,
wiped on scope exit, which the tests do not observe.

**T2 (emulator).** The 67 existing tests, `VideoProbeTest` included (the probe keeps
running over the moved classes). New `VideoViewerTest`: leak scan; one 10 s recording
through `V3Probe` as in `VideoProbeTest`; the gallery classifies the index as a video tile
with the duration, lists no segment, and holds a thumbnail of at most 256 px under the
index hex, which is gone after a lock; `VideoActivity` with `FLAG_SECURE` and the secure
surface plays to the end (transitions and boundary gaps equal to the segment count minus
one, 0 errors), seeks to a segment start and inside a segment through the same method the
seek bar uses and lands on the located key time, pauses and resumes (position frozen then
advancing), and is released by a lock during playback (no live buffer, opens equal closes,
`buffersFreed` advanced); a lock during a slowed thumbnail decrypt ends it with
`CANCELLED` and leaves the cache empty; a byte flipped in segment 1 stops playback at
segment 1 with `AUTH_FAILED` and no frame from it (the last rendered frame time is below
the segment 1 start); a byte flipped in the index gives a damaged tile and the viewer's
error state with Delete; delete removes the index and every segment; an interrupted delete
(index only) hides the clip and the next unlock's sweep removes the segments (object count);
the sweep skips while a damaged index exists; a recording started right after an unlock
(the sweep posted behind it) loses no segment: every segment the recorder wrote is still
there with its index once both finished, and the sweep logged either a clean run over the
earlier list or the skip; the read-failure hook, the alloc-fail hook, and the
thumbnail-fail hook behave as in Section 6; leak scan after, no new non-object file. Host-driven: merged manifests of the three variants (`CAMERA` only, `LockActivity`
the only export, `VideoActivity` present), class scan (2577 media3 classes in release and
benchmark, muxer classes only in debug), APK sizes, screen capture and recents card black
during playback, the 3B photo flows unchanged (existing tests), a 30 s scripted clip for
the read-ahead measurement (Section 4.3).

**T3 (Xiaomi; the developer taps, Claude Code runs the scripts).** Brief Section 7: two
clips with the debug build aimed at a neutral lit scene; the benchmark build installed over
it with the vault kept; the developer confirms by eye that the picture shows the scene
upright and plays smoothly (the rotation of CP-2); gallery tile and thumbnail (time to
classify the vault, thumbnail time on the hardware decoder); play, pause, seek with first
frame and seek latency; Home, screen off, and the MIUI camera during playback; recents
card; delete one clip; peak memory; `leak_scan` zero. The thumbnail trial of Milestone A
runs first on the debug build (Section 11).

## 8. Checkpoint items (CP-1 to CP-7)

| # | Item | Proposal |
|---|---|---|
| CP-1 | Dependency and manifest plan | `media3-exoplayer` 1.11.1 `implementation`, no `media3-ui`, muxer debug-only; `WAKE_LOCK` removed by a main manifest rule; +5.0 MB and 2577 classes accepted, R8 stays a Phase 6 item (Section 3). Approved |
| CP-2 | Rotation | the index rotation set on the track format by the extractor wrapper and applied by the codec; the thumbnail turned by the same value; by-eye check on the phone (4.2). Approved |
| CP-3 | Idle rule and pause | playing counts as activity through the position ticker; paused or ended does not; no separate pause timeout; the surface keeps the screen on while playing, no wake lock (4.3). Approved with the screen-on amendment |
| CP-4 | Gallery classification | an item-header peek per object (one chunk), video tiles keyed by the index with cheap segment checks, segments hidden by their own header (4.4). Approved |
| CP-5 | Delete and sweep | index first then segments; the sweep after every unlock on the lock thread over the object list taken at its start, skipped while any index is unreadable, never beside a recording (4.6). Approved with the list and recording-gate amendments |
| CP-6 | Core addition | `pv_object_item_header` only, no new error code; the two other candidates not needed (4.7). Approved |
| CP-7 | Source moves and version | the probe pieces and the thumbnail decoder move to main; the probe stays in debug over them; version 0.6.0 (4.1). Approved |

## 9. Seams for 3E, Phase 4, Phase 5

- 3E: the muxing step writes the rotation into the container (`tkhd` matrix) beside the
  `tfdt` or `sidx` already planned, after which CP-2's format override and the extractor
  wrapper can go; the sweep's rule for segments without an index is where a recovery
  design plugs in (a clip ID in the reserved item header bytes would let the sweep keep
  them); audio lands in the same segments and `VideoActivity` gains a volume control;
  `VideoFirstFrame` gains `hvcC` if H.265 is chosen.
- Phase 4: the secure `SurfaceView` of `VideoActivity` and the `PhotoView` of 3B are the
  two surfaces the temporal-interference drawing composites over.
- Phase 5: a clip is streamed segment by segment through the same data source shape; the
  index is what a viewer session negotiates first.

## 10. Threat model deltas (for `docs/THREAT_MODEL.md` Section 10)

- A player library (media3 1.11.1) processes plaintext in every variant, release
  included; its sample queue holds the read-ahead of a clip in the Java heap, released
  with the player and not wiped by the app.
- The thumbnail path adds the codec input buffer and the YUV output image (platform,
  native) and a 256 px bitmap the app erases.
- Playback keeps the idle lock from firing; the screen-off and background locks are
  unchanged. A phone left playing on a desk stays unlocked until the clip ends plus the
  idle timeout.
- The header peek decrypts one authenticated chunk per object on every gallery open and
  every unlock sweep; nothing of it is retained.
- An interrupted delete leaves orphan segments on disk until the next unlock.

## 11. Settled at the checkpoint

- The Xiaomi half of the thumbnail trial ran right after the approval (Section 2.3):
  buffer mode works on the hardware decoder, 4.5 stands as written.
- The two T3 clips stay on the phone after Stage 3D as the regression clips for the 3E
  muxing change (developer decision at the checkpoint; `docs/ROADMAP.md`).
