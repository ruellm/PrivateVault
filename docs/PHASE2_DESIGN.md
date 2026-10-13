# Phase 2 design: capture to vault

Status: proposed 2026-10-13 for the Milestone B checkpoint; approved by the developer the
same day with four amendments, all applied below (item sink rules in 3.4, `PV_ERR_NOT_ITEM`
in 4, the `VideoOutput` restriction check in 8.2, video low-space handling in 8.3). Items
marked **P2-x** are the decisions listed in the Phase 2 brief, Section 11. Nothing in this
document changes the version 1 object format, the key envelopes, or any existing C ABI
function.

Facts taken from Phase 1 are not repeated; see `docs/PHASE1_DESIGN.md`. Where this document
says "verified" it names where. Everything else is design intent to be confirmed by the
Milestone C and D tests.

## 1. Toolchain (P2-1, applied in Milestone A)

| Item | Phase 1 | Phase 2 |
|---|---|---|
| compileSdk | 35 | 36 |
| targetSdk | 35 | 35 (no dependency needs 36) |
| minSdk | 28 | 28 (P2-7) |
| AGP | 8.8.2 | 8.9.3 |
| Gradle | 8.10.2 | 8.11.1 |
| Kotlin plugin | 2.1.10 | 2.1.10 |
| NDK, CMake, vcpkg, libsodium | unchanged | unchanged |

Reason and alternatives: D-012. The Phase 1 suites were rerun after the change (results in
`docs/PHASE2_PROGRESS.md`, Milestone A).

Dependencies to add in Milestone D, all pinned, all Apache 2.0, metadata recorded in
`docs/DEPENDENCIES.md`:

- `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` 1.6.2
  (main source set).
- `androidx.camera:camera-video` 1.6.2 and `androidx.media3:media3-muxer` 1.11.1 only for the
  video prototype (Milestone E). `camera-view` already pulls `camera-video` as a runtime
  dependency, so the artifact is in the APK either way; the explicit dependency only makes
  its classes visible to app code.
- `androidx.test:rules` 1.6.1 (`androidTest` only) for `GrantPermissionRule`.

The CameraX AAR manifests add no permission and no feature. They add one disabled,
non-exported service (`MetadataHolderService`) which the merged-manifest check in Milestone D
records. `camera-core` brings two small native libraries per ABI; the APK keeps `arm64-v8a`
and `x86_64` only.

## 2. Photo pipeline

```
CaptureActivity (FLAG_SECURE)
  PreviewView  <- Preview use case
  shutter      -> ImageCapture.takePicture(captureExecutor, OnImageCapturedCallback)
                    onCaptureSuccess(ImageProxy)            [CameraX thread]
                      -> CaptureSession.submit(proxy)       bounded queue, app scoped
                           encryptExecutor (single thread, app scoped):
                             plane 0 buffer, isDirect?
                             NativeBridge.nativeEncryptItem(handle, buffer, offset, len, fields, idOut, lenOut)
                               JNI: GetDirectBufferAddress (or one copy into sodium_malloc)
                               core: pv_item_header_encode -> 48 bytes
                                     pv_source_memory_new([header, jpeg])
                                     pv_vault_encrypt -> objects/<id>.pvo.partial -> rename
                             proxy.close()                  on every path
                             verify: pv_vault_decrypt into item sink + BLAKE2b
                             status line + logcat: id, lengths, status, elapsed ms
```

### 2.1 Components

- **`PvApp`** (`Application`): owns `VaultSession` and `CaptureSession`. Both are process
  scoped, so a configuration change or activity recreation does not cancel an encrypt in
  progress.
- **`VaultSession`**: the one open `Vault` for the process (create or open through
  `KeystoreKeyProvider`, close on explicit request). `MainActivity` and `CaptureActivity`
  share it. Phase 1 behaviour of the buttons and scripted actions is unchanged.
- **`CaptureSession`**: a single-thread `Executor` for encryption, a queue of at most
  `MAX_PENDING = 2` captured images waiting for encryption, and a listener for status lines.
  A third capture while two wait is refused before `takePicture` is called (the shutter is
  disabled while the queue is full), so no `ImageProxy` is ever held without a bounded
  reason.
- **`CaptureActivity`**: `PreviewView`, shutter button, optional front/back toggle, a status
  `TextView`. `window.setFlags(FLAG_SECURE)` in `onCreate` before `setContentView`. Requests
  `CAMERA` at runtime; binds `Preview` and `ImageCapture` to the activity lifecycle with
  `ProcessCameraProvider` once the permission is granted and the vault is open. Reads the
  `actions` extra (Section 6) and runs it after the first successful bind.
- **`NativeBridge.nativeEncryptItem`** (new JNI entry, Section 4).

### 2.2 ImageCapture configuration

- `ImageCapture.Builder().setCaptureMode(CAPTURE_MODE_MAXIMIZE_QUALITY)`, output format
  JPEG (the default), no `setJpegQuality` override, `setTargetRotation` updated from the
  display rotation through an `OrientationEventListener` so that
  `ImageInfo.getRotationDegrees()` reports what the viewer must apply.
- Resolution: the highest the device offers for JPEG through the default
  `ResolutionSelector` with `ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY`. The measured
  sizes go in the report (the brief asks for peak memory at the highest resolution).
- No `OutputFileOptions`, no `OnImageSavedCallback`, no `MediaStore`, no `File`. The final
  check greps the source for `OutputFileOptions`, `OnImageSavedCallback`, `MediaStore`,
  `FileOutputStream`, and `openFileOutput` and expects no hit outside the leak-scan code,
  which reads only.

### 2.3 What the callback does

`onCaptureSuccess(ImageProxy proxy)` runs on the capture executor. It records
`System.currentTimeMillis()` as the capture time, reads `proxy.format` (must be
`ImageFormat.JPEG`, otherwise close and report `BAD_FORMAT`), `proxy.width`, `proxy.height`,
`proxy.imageInfo.rotationDegrees`, and hands the proxy to `CaptureSession.submit`. Nothing
reads the pixel bytes on the Kotlin side.

On the encrypt thread:

1. `val buffer = proxy.planes[0].buffer`; `buffer.rewind()`; `len = buffer.remaining()`.
   Record `buffer.isDirect` for the report (debug log line, once per capture).
2. If direct: call `nativeEncryptItem(handle, buffer, buffer.position(), len, fields, idOut,
   lenOut)`. The JNI side uses `GetDirectBufferAddress`; no byte of the image is copied into
   the Kotlin heap by app code.
3. If not direct and `buffer.hasArray()`: call the `ByteArray` overload with
   `buffer.array()`, `buffer.arrayOffset() + buffer.position()`, `len`. The array already
   exists (CameraX made it); the JNI side copies it once into `sodium_malloc` memory, encrypts
   from there, and `sodium_free`s it. The report records that this path ran and on which
   device.
4. If neither (read-only heap buffer): same as 3 after `buffer.get(ByteArray)`. This is the
   only case where app code would create a Kotlin copy of capture bytes; the report says
   whether it ever happened.
5. `proxy.close()` in a `finally`.
6. On `PV_OK`: verify the new object (decrypt into the item sink and a hash sink, Section
   3.4), compare `payload_len` and the plaintext length, log
   `captured <id> jpeg=<len> object=<file size> rotation=<deg> <w>x<h> verify=<status>
   total=<ms> ms`. No variant logs a hash of a capture. In the debug variant the
   decrypt-side payload hash is kept in the in-memory capture result for the instrumented
   test comparison only; the log line carries the same fields in every variant.
7. On any other status: the error name is shown and logged; nothing is left on disk (the
   core removed the `.partial`).

### 2.4 Chunk size

Default 64 KiB. A photo of 3 to 12 MB is 50 to 200 chunks; per-chunk overhead is 17 bytes
plus one secretstream call. Milestone D measures capture-to-object time at 64 KiB and
256 KiB on the emulator and records both; the default stays unless the larger size is at
least 20 percent faster on the phone, in which case the change is recorded as a decision.

### 2.5 Threads and lifecycle

| Event | Behaviour |
|---|---|
| Rotation or recreation of `CaptureActivity` | Camera use cases are unbound by the lifecycle and rebound by the new activity. Pending encrypts continue on the app-scoped executor. Status lines are replayed from `CaptureSession` to the new activity. |
| Activity stopped (home, screen off) during encrypt | Encrypt finishes. The object is complete and verified. The next `onStart` shows the result. |
| Process killed during encrypt | The `.partial` file is removed by the next `pv_vault_open`. Nothing else exists. |
| Shutter pressed while a capture is in flight | Allowed up to `MAX_PENDING` queued images; then refused with `QUEUE_FULL`. |
| Vault not open | Shutter disabled, status says so. |
| Free space under threshold | Shutter disabled, status says `LOW_STORAGE` (Section 5). |

## 3. Media item header (P2-2)

Approved layout, version 1, 48 bytes. All integers little-endian. Lives at the start of the
plaintext of an object; the v1 object format does not change. The header is encrypted and
authenticated with the media bytes because it is inside the secretstream payload.

| Offset | Size | Field | Rule |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `PVITEM01`. Absent: the plaintext is an unknown item (`PV_ERR_NOT_ITEM`), which is not an object error. |
| 8 | 2 | `item_version` | 1. Other: `PV_ERR_ITEM_UNSUPPORTED`. |
| 10 | 2 | `header_len` | Total header bytes. Writers put 48. Readers accept 48 to 4096 and skip bytes 48 to `header_len` without reading them. Below 48 or above 4096: `PV_ERR_ITEM_MALFORMED`. |
| 12 | 2 | `media_type` | 1 = JPEG photo, 2 = video segment (fragmented MP4), 3 = video index. Other: `PV_ERR_ITEM_UNSUPPORTED`. 2 and 3 are prototype-only in Phase 2 (Section 8). |
| 14 | 2 | `rotation_degrees` | 0, 90, 180, or 270. Other: `PV_ERR_ITEM_MALFORMED`. |
| 16 | 4 | `width` | Pixels as delivered by the camera, before rotation. 0 allowed for media types without a frame size (none in Phase 2). |
| 20 | 4 | `height` | Same. |
| 24 | 8 | `created_unix_ms` | Capture time, milliseconds since the Unix epoch, UTC. |
| 32 | 8 | `payload_len` | Bytes after the header. The reader checks `header_len + payload_len == plaintext length` once FINAL has been seen; mismatch: `PV_ERR_ITEM_MALFORMED`. |
| 40 | 8 | `reserved` | Zero when writing, ignored when reading. |

Rules:

- `pv_item_header_encode` writes exactly 48 bytes and rejects a struct whose fields break the
  rules above (same codes), so a writer cannot produce a header its own reader would reject.
- `pv_item_header_decode(buf, len, out)` needs `len >= 48`; fewer bytes: `PV_ERR_ITEM_MALFORMED`.
  Check order: magic, version, `header_len`, `media_type`, `rotation_degrees`. It does not
  know the plaintext length; `pv_item_header_check_len(h, plaintext_len)` does that check.
- The header is parsed only after `pv_object_decrypt` or `pv_vault_decrypt` returned `PV_OK`.
  The item sink (Section 3.4) buffers header bytes while the stream flows but interprets
  them only in its finish call, which the caller makes after `PV_OK`. The one value it reads
  early is `header_len`, used only to decide how many bytes to keep (capped at 4096).
- Phase 1 objects (deterministic test data) start with keystream bytes, not the magic, and
  are reported as unknown items (`PV_ERR_NOT_ITEM`, a classification, not a corruption).
  `verify_all` lists them as `unknown item` and then checks their hash with a second,
  plain decrypt into a hash sink, as Phase 1 did.
- A 48-byte header on a 3 MB JPEG changes the ciphertext size by 48 bytes; the metadata
  exposure of Phase 1 (size, count, mtime) is unchanged. Object sizes still distinguish
  photos (a few MB) from video segments (hundreds of KB to a few MB each, many per clip) and
  from the small index object; `docs/THREAT_MODEL.md` says so.

Alternatives considered: a separate metadata object per photo (doubles the object count and
needs a link the vault has no index for); EXIF orientation inside the JPEG (CameraX does not
promise an EXIF orientation tag on the in-memory path, and video needs the same fields);
no metadata until Phase 3 (orientation is only known at capture time). Recommendation: the
item header as above.

### 3.4 Item sink (core)

To verify an item-wrapped object without holding the plaintext, the core gains a sink that
splits the stream: it keeps the header bytes and forwards the payload to an inner sink.

```
pv_sink_item_new(pv_sink* payload_sink, void (*payload_abort)(pv_sink*), pv_sink* out)
pv_sink_item_finish(pv_sink* s, uint64_t plaintext_len, pv_item_header* header_out)
pv_sink_item_abort(pv_sink* s)
```

What the sink reads before completion is known: the decrypt loop hands the sink plaintext
chunk by chunk. Each chunk has passed its Poly1305 tag, but the stream is not known to be
complete until FINAL. From that plaintext the sink reads only two things, the 8-byte magic
and the 2-byte `header_len`, and uses them for one purpose: deciding where the header ends
and the payload starts. Every field is validated in `finish`, which the caller calls only
after the decrypt returned `PV_OK`.

- Bytes 0 to 11 are collected first. Nothing reaches the payload sink before they have
  arrived.
- **No magic** (bytes 0 to 7 differ from `PVITEM01`, or the plaintext ends before 12
  bytes): the sink forwards nothing to the payload sink, calls `payload_abort` on it once,
  discards the rest of the stream, and `finish` returns `PV_ERR_NOT_ITEM` with `header_out`
  zeroed.
- **`header_len` out of range** (below 48 or above 4096) or **beyond the plaintext** (the
  stream ends before `header_len` bytes arrived): the sink aborts the payload sink as above,
  forwards nothing, and `finish` returns `PV_ERR_ITEM_MALFORMED`.
- Otherwise the first `header_len` bytes are kept and every later byte is forwarded to the
  payload sink.
- `finish` runs `pv_item_header_decode` on the kept bytes, then `pv_item_header_check_len`
  with the `plaintext_len` the caller got from the decrypt call, writes `header_out`, and
  frees the item sink. On a decode or length failure it also aborts the payload sink. On
  success it does not touch the payload sink, which the caller then finishes (on Android:
  `pv_sink_blake2b_finish`).
- **Decrypt failure**: the caller calls `pv_sink_item_abort`, which aborts the payload sink
  (if not already aborted) and frees the item sink. `payload_abort` may be null for a sink
  with nothing to release; it is called at most once.
- T1 (`test_item_objects`) covers the four paths: valid item, no magic, bad or truncated
  `header_len`, and decrypt failure followed by abort, each checking that the inner sink
  was aborted exactly when the rules say.

On Android the payload sink is the BLAKE2b sink from Phase 1, so verification still writes
nothing to disk.

## 4. C ABI additions (P2-3)

Additive only. Declared in `pv.h` after the Phase 1 functions.

```c
#define PV_ERR_ITEM_MALFORMED 21     // header fails a rule, or header_len + payload_len != plaintext length
#define PV_ERR_ITEM_UNSUPPORTED 22   // item_version or media_type not handled by this reader
#define PV_ERR_NOT_ITEM 23           // no PVITEM01 magic. A classification result, not a corruption:
                                     // callers that accept non-item objects treat it as "unknown item"

#define PV_ITEM_MAGIC "PVITEM01"
#define PV_ITEM_HEADER_BYTES 48
#define PV_ITEM_HEADER_MAX_BYTES 4096
#define PV_ITEM_VERSION 1
#define PV_MEDIA_JPEG 1
#define PV_MEDIA_VIDEO_SEGMENT 2
#define PV_MEDIA_VIDEO_INDEX 3

typedef struct pv_item_header {
    uint16_t item_version;
    uint16_t header_len;
    uint16_t media_type;
    uint16_t rotation_degrees;
    uint32_t width;
    uint32_t height;
    uint64_t created_unix_ms;
    uint64_t payload_len;
} pv_item_header;

int  pv_item_header_encode(const pv_item_header* h, uint8_t* out, size_t out_cap, size_t* out_len);
int  pv_item_header_decode(const uint8_t* buf, size_t len, pv_item_header* out);
int  pv_item_header_check_len(const pv_item_header* h, uint64_t plaintext_len);

// Source over caller-owned memory parts, read in order as one stream. No copy;
// the caller keeps every part alive until the source is freed.
int  pv_source_memory_new(const uint8_t* const* parts, const size_t* part_lens,
                          size_t part_count, pv_source* out);
void pv_source_memory_free(pv_source* s);

int  pv_sink_item_new(pv_sink* payload_sink, void (*payload_abort)(pv_sink*), pv_sink* out);
int  pv_sink_item_finish(pv_sink* s, uint64_t plaintext_len, pv_item_header* header_out);
void pv_sink_item_abort(pv_sink* s);
```

- `pv_error_name` learns the three codes; `NativeBridge` mirrors them.
- Memory source rules: `part_count` may be 0 (empty stream); a part may have length 0; a
  part pointer may be null only when its length is 0; reads never cross the total length;
  each read returns at most what remains of the current part, so a consumer sees short reads
  at part boundaries (the Phase 1 reader loops, which `test_memory_source` confirms).
- No push-style writer (`begin`/`write`/`finish`/`abort`) for photos. The video prototype
  decides whether one is needed (Section 8.6).
- No platform code in the core.

Desktop: `pv inspect` is unchanged (header only, no key). `pv verify` prints `item: jpeg
WxH rotation R created T payload N` when the plaintext is an item, or `item: unknown` as
before. This is the Windows side of acceptance criterion 9.

## 5. JNI and memory

### 5.1 Entry points

```kotlin
// Direct buffer path. fields = [media_type, rotation, width, height, created_unix_ms].
external fun nativeEncryptItem(handle: Long, buffer: ByteBuffer, offset: Int, length: Int,
                               fields: LongArray, idOut: ByteArray, lenOut: LongArray): Int
// Heap array path (CameraX-made array; one native copy into sodium_malloc memory).
external fun nativeEncryptItemArray(handle: Long, data: ByteArray, offset: Int, length: Int,
                                    fields: LongArray, idOut: ByteArray, lenOut: LongArray): Int
// Decrypts into the item sink over a hash sink. infoOut = [media_type, rotation, width,
// height, created_unix_ms, payload_len, header_len, plaintext_len]; itemStatus[0] is the
// item sink verdict (PV_OK, PV_ERR_NOT_ITEM, ...). Returns the decrypt status.
external fun nativeDecryptVerifyItem(handle: Long, id: ByteArray, hashOut: ByteArray,
                                     infoOut: LongArray, itemStatus: IntArray): Int
```

Following D-011: status is the return value; IDs, lengths, and handles go through output
arrays; no pointer is ever a status.

### 5.2 Native side

1. `GetDirectBufferAddress` and `GetDirectBufferCapacity`; `offset + length <= capacity`
   or `PV_ERR_INVALID_ARGUMENT`.
2. Fill `pv_item_header` from `fields`, `payload_len = length`, encode into a 48-byte stack
   buffer.
3. `pv_source_memory_new({header, jpeg}, {48, length}, 2)`, `pv_vault_encrypt(v, src, 0, id)`,
   free the source. The core reads the JPEG through the source in 64 KiB pieces straight from
   the image memory; the only plaintext buffer the core allocates is one chunk.
4. The array overload does `GetByteArrayRegion` into `sodium_malloc(length)` memory,
   encrypts from there, and `sodium_free`s it, which also zeroes it.

### 5.3 What cannot be wiped

The JPEG bytes live in memory the camera stack owns: the `ImageReader` buffer behind the
`ImageProxy` (released on `close()`, not zeroed by the app), the camera HAL and ISP buffers,
gralloc buffers, the preview `Surface`, and any copies CameraX makes internally (its
software JPEG path and effects pipeline use Java byte arrays; the in-memory JPEG path from a
HAL JPEG stream is expected to wrap the HAL buffer directly). None of this is under app
control. `docs/THREAT_MODEL.md` lists these as outside the app, with no claim of erasure.

### 5.4 Measurements (Milestone D and F)

- `buffer.isDirect` per capture on the emulator and the Xiaomi.
- Java heap (`Runtime`), native heap (`Debug.getNativeHeapAllocatedSize`), and
  `dumpsys meminfo` PSS before, during (sampled on a timer at 50 ms while an encrypt runs),
  and after a capture at the highest JPEG resolution.
- Capture-to-object latency: from the shutter call to the rename, and separately the
  encrypt call alone.

## 6. Failure handling

| Situation | Detection | Behaviour | Status name |
|---|---|---|---|
| Permission denied or revoked | `checkSelfPermission`, `onRequestPermissionsResult`, `SecurityException` on bind | No bind. Status line and a "Grant camera" button that re-requests. | `PERMISSION_DENIED` |
| No camera, camera busy, disconnected | `ProcessCameraProvider` has no matching camera; `CameraState` observed through `cameraInfo.cameraState` (`ERROR_CAMERA_IN_USE`, `ERROR_CAMERA_DISABLED`, `ERROR_CAMERA_FATAL_ERROR`, `ERROR_OTHER_RECOVERABLE_ERROR`) | Shutter disabled; CameraX rebinds on its own when the camera becomes available; on resume the activity rebinds. | `CameraState` error name |
| `ImageCapture` error | `onError(ImageCaptureException)` | Nothing written; `getImageCaptureError()` mapped to its name. | `ERROR_CAMERA_CLOSED`, `ERROR_CAPTURE_FAILED`, `ERROR_INVALID_CAMERA`, `ERROR_UNKNOWN` |
| Wrong image format | `proxy.format != JPEG` | Close, nothing written. | `BAD_FORMAT` |
| Backgrounding during encrypt | None needed | Encrypt completes on the app-scoped executor; verified; result shown on next resume. | `OK` |
| Process death during encrypt | None possible | `pv_vault_open` removes the `.partial`; `list` shows no orphan. | n/a |
| Storage full or write failure | `pv_vault_encrypt` returns `PV_ERR_IO`; `pv_last_os_error` gives `ENOSPC` (28) | `.partial` deleted by the core; status shows the name and errno. | `PV_ERR_IO os_error=28` |
| Low free space before capture | `StatFs(filesDir).availableBytes < LOW_SPACE_BYTES` (64 MiB) checked before each `takePicture` | Capture refused; shutter stays enabled so the check repeats. | `LOW_STORAGE` |
| Any other `PV_ERR_*` | return value | Proxy closed, nothing left, name shown. | the `PV_ERR_*` name |
| Rapid captures | queue length | Refused beyond `MAX_PENDING`. | `QUEUE_FULL` |
| Vault not open | `VaultSession.vault == null` | Shutter disabled. | `VAULT_CLOSED` |

Test hooks (debug only, `androidTest` or a `BuildConfig.DEBUG` guard): a slow memory source
(the JNI layer sleeps between reads when a debug flag is set, so process death during
encrypt can be forced with `am kill`), and a fault flag that makes the encrypt call return
`PV_ERR_IO` after the header, to exercise the cleanup path without filling storage. Ballast
(filling the app storage) runs on the emulator only.

## 7. Leak scan

`LeakScan.run(context)` returns a list of findings and a snapshot. It reads only.

1. **Directory walk.** `dataDir` (which contains `files`, `cache`, `code_cache`,
   `no_backup`, `shared_prefs`, `databases`), `getExternalFilesDirs(null)`,
   `getExternalCacheDirs()`, `getExternalMediaDirs()`. Every file with size and mtime goes in
   the snapshot. Comparing the snapshot before and after captures must show new entries only
   under `files/vault/objects/*.pvo`.
2. **Content scan.** Each file is read in 64 KiB pieces. Findings: `FF D8 FF` at offset 0,
   `FF D8 FF` anywhere in the first 64 KiB, and the ASCII `ftyp` at offset 4 of the file or
   anywhere in the first 64 KiB (after video probes). Encrypted objects are ciphertext and
   are scanned like every other file; a hit inside one would be a chance three-byte match
   and is reported with its offset so it can be judged.
3. **MediaStore.** Queries `MediaStore.Images`, `Video`, and `Files` external content URIs
   with `OWNER_PACKAGE_NAME = <this package>` (API 29 and later; on API 28 the column does
   not exist and the check reports "not available"). Expected: zero rows.
4. **Logcat.** Done from the host: `adb logcat -d` for the run is scanned by a script for
   base64 runs longer than 44 characters and hex runs longer than 32 characters (the object
   ID length); the only expected hex of exactly 32 characters are object IDs and, in debug
   runs, BLAKE2b hashes of 64 characters which the script lists separately.
5. **Recents thumbnail.** Manual: open the capture screen, press recents, screenshot the
   recents screen with `adb exec-out screencap -p`; the app card must be blank.
6. **Merged manifest.** `aapt2 dump xmltree` of the APK: `allowBackup=false`, both rule
   references, permissions exactly `CAMERA` (plus `RECORD_AUDIO` only in the debug variant if
   Section 8.5 happens), no `provider`, exported components only the launcher activity.
7. **Descriptors.** `adb shell run-as <pkg> ls -l /proc/<pid>/fd` during a capture, and
   `dumpsys media.camera` while bound: recorded verbatim in the report.

Findings are shown on screen and logged under `PrivateVault` as `leak_scan: <n> findings`
with one line each. The scripted action `leak_scan` and the instrumented test both call the
same class.

## 8. Video

### 8.1 Constraints (verified by reading the 1.6.2 and 1.11.1 class files on 2026-10-13)

- `Recorder.prepareRecording` has three overloads: `FileOutputOptions`,
  `FileDescriptorOutputOptions`, `MediaStoreOutputOptions`. All end in a file or a descriptor.
- `androidx.camera.video.VideoOutput` is a public interface with one abstract method,
  `onSurfaceRequested(SurfaceRequest)`. Its other methods are `default` methods annotated
  `@RestrictTo(LIBRARY)` (`getMediaSpec`, `getStreamInfo`, `onSourceStateChanged`,
  `isSourceStreamRequired`, `getMediaCapabilities`, `getEncoderProfilesResolver`,
  `onSurfaceRequested(SurfaceRequest, Timebase, boolean)`). `VideoCapture.withOutput(T)` is
  public. So a custom `VideoOutput` that implements only `onSurfaceRequested` uses public
  API only and is the V3 variant to evaluate first (Section 8.3).
- `androidx.media3.muxer.FragmentedMp4Muxer` is `@UnstableApi`; its `Builder` takes an
  `OutputStream` or a `WritableByteChannel`; `setFragmentDurationMs` exists; `addTrack(Format)`,
  `writeSampleData(int, ByteBuffer, BufferInfo)`, `close()`. `Mp4Muxer` needs a
  `FileOutputStream` or `SeekableMuxerOutput`, which is why only the fragmented muxer fits.
- `SurfaceRequest.provideSurface(Surface, Executor, Consumer<Result>)` is public, as is
  `Preview.SurfaceProvider`. `CameraEffect` with targets `PREVIEW | VIDEO_CAPTURE` and a
  `SurfaceProcessor` is public.

### 8.2 Options

| Option | Pipeline | Plaintext on disk | Assessment |
|---|---|---|---|
| V1 | `Recorder` to a temp file, encrypt, delete | Yes | Paper only. Breaks the phase goal and deletion on flash does not erase. Not built. |
| V2 | `Recorder` with `FileDescriptorOutputOptions` over `StorageManager.openProxyFileDescriptor` | No, while the callback keeps data in memory | Probe only: log every `onWrite(offset, size)` and `onFsync` for a 10 s clip to learn the write pattern of the platform `MediaMuxer` (expected: `ftyp` and `mdat` first, `moov` at the end, plus back-patching of the `mdat` size at offset 0 region). Whole-clip buffering would be needed for a random-write store; not built. |
| V3a | `VideoCapture.withOutput(customVideoOutput)` where the output provides a `MediaCodec` input surface; encoder output into `FragmentedMp4Muxer` writing to an in-memory bounded channel; channel read by a `pv_source` on the encrypt thread | No | First choice. On-screen `PreviewView` stays bound as the `Preview` use case, so no second preview is needed. Risk: the restricted default methods return defaults the `VideoCapture` pipeline may not accept (for example `getMediaSpec` returning an empty observable); the probe finds out. **Gate (developer amendment):** before building on it, confirm from the 1.6.2 class files that the abstract method and every `VideoOutput` method `VideoCapture` calls on a custom output are not `@RestrictTo`, and run Android lint with `RestrictedApi` as an error on the app module. If any required method is restricted, or `VideoCapture` misbehaves with the default implementations, fall back to V3b; the feasibility doc records which variant was used and why. |
| V3b | `Preview` use case whose `SurfaceProvider` gives the encoder surface, with a `CameraEffect` (`PREVIEW` target, `SurfaceProcessor`) that renders the camera frame to both the on-screen surface and the encoder surface | No | Fallback if V3a does not run. Needs an OpenGL ES copy per frame in app code. If that is too much for the prototype, record without on-screen preview and say so. |
| V4 | Camera2 directly with `MediaCodec` | No | Not built; same encoder work as V3 and loses CameraX device handling. |

### 8.3 Prototype (V3), `video_probe` action, debug build only

1. Request a 1280x720 H.264 (`video/avc`) encoder surface from `MediaCodec`, 30 fps,
   bitrate 4 Mbit/s, `KEY_I_FRAME_INTERVAL` 2 s, Surface color format. Record 10 s.
2. Bind the on-screen `Preview` plus `VideoCapture.withOutput(ProbeOutput)`. `ProbeOutput`
   answers the `SurfaceRequest` with the encoder surface. If binding fails or no frames
   arrive within 3 s, fall back to V3b; if V3b is not achieved in the prototype budget,
   record without on-screen preview and report it.
3. Segmenting: each segment is its own `FragmentedMp4Muxer` with its own track; a segment
   starts at a sync frame (the encoder is asked for one with
   `PARAMETER_KEY_REQUEST_SYNC_FRAME` at each 3 s boundary) and ends just before the next one.
   Each segment is one v1 object with item header `media_type = 2`, `width`, `height`,
   `rotation_degrees` from the camera sensor orientation and display rotation,
   `created_unix_ms` the capture start of the segment.
4. The muxer writes into `SegmentChannel`, a `WritableByteChannel` over a bounded queue of
   byte blocks (capacity 8 MiB). The encrypt thread owns a `pv_source` whose `read`
   dequeues blocks; an empty queue blocks the reader until the muxer writes or closes;
   a full queue makes the muxer `write` fail with an exception that stops the recording
   with `QUEUE_FULL`. No spill to disk, no unbounded growth. High-water mark recorded.
   Because the core reads a `pv_source` to completion inside one `pv_vault_encrypt` call,
   this channel is enough: encryption pulls while the muxer pushes. The question of a
   push-style core writer is answered in 8.6.
5. Index object: `media_type = 3`, payload = `PVVIDX01` (8), `version` u16 = 1,
   `segment_count` u16, `codec` 16 ASCII bytes (`video/avc`, zero padded), `width` u32,
   `height` u32, `frame_rate` u32, then `segment_count` entries of object ID (16),
   `start_us` u64, `duration_us` u64, `sync_frame_count` u32, `reserved` u32. Prototype
   layout; not part of the approved format.
6. Verification without plaintext on disk: each segment decrypted into memory (a
   `ByteArray` sink in the `androidTest` and debug path only, documented as a Kotlin copy),
   fed to `MediaExtractor` through a `MediaDataSource` over that array; track MIME, sample
   count, and duration compared with what the prototype recorded; first frame decoded with
   `MediaCodec` to a `ByteBuffer` output (no surface) and its size checked.
7. Low space during a recording (developer amendment): the 64 MiB precheck of Section 6
   covers photos and the start of a recording. At every segment boundary the prototype
   reads the free space again and stops the recording cleanly when it is below a threshold
   sized from the measured bitrate: the threshold is 64 MiB plus the bytes of the largest
   segment seen so far times four. The current segment is finished if its buffer is
   complete, otherwise discarded; the index is written for the completed segments; the
   status says `LOW_STORAGE` with the segment count. The measured threshold and the
   observed behaviour go in the feasibility doc.
8. Stretch (only after 1 to 7 pass): AAC audio from `AudioRecord` with `RECORD_AUDIO`
   declared in the debug source set manifest only (`src/debug/AndroidManifest.xml`), muxed as
   a second track, interleaved by timestamp. Not attempted before the photo path and the
   video-only probe are done.

### 8.4 V2 probe

`ProxyFileDescriptorCallback` over `openProxyFileDescriptor(MODE_READ_WRITE)` (the probe
used read-write because the platform muxer seeks and rewrites earlier offsets) with a
`HandlerThread`; `Recorder` with `FileDescriptorOutputOptions`; record 10 s with the on-screen
preview; log `onGetSize`, each `onWrite(offset, size)`, `onFsync`, `onRelease`; keep at most
16 MiB in memory and stop the recording with an error beyond that. Report whether the
recorder accepted the descriptor and the full write pattern. Then `leak_scan`.

### 8.5 Measurements for the feasibility report

Encoder output bitrate, encrypt throughput (bytes per second of `pv_vault_encrypt` on the
segment stream), queue high-water mark, frames submitted and frames encoded, CPU
(`top -n 1 -p <pid>` sampled every second) and memory (Section 5.4 method) during recording,
segment sizes and counts. Both on the emulator and the Xiaomi.

### 8.6 Push-style writer

The prototype answers whether the pull-style `pv_source` over a bounded queue is enough.
Expected answer: yes for a single recording thread, because the encrypt call blocks on the
queue and the muxer thread fills it. A push-style writer would only be needed if the real
implementation must interleave several encrypts on one thread or must flush partial
segments on demand. The feasibility report states the measured answer.

## 9. Debug app changes

- `MainActivity`: a "Capture" button that starts `CaptureActivity`; new scripted actions
  `verify_all` (replaces the Phase 1 `verify` output format with item fields; `verify` is
  kept as an alias), `list` (object IDs, sizes, mtimes, item type when it can be read), and
  `leak_scan`.
- `CaptureActivity` scripted actions: `capture_photo`, `capture_photo_n=<count>`,
  `verify_all`, `list`, `leak_scan`, `video_probe`, `v2_probe`, plus `open`. Launched as
  `am start -n com.privatevault.app/.CaptureActivity --es actions open,capture_photo_n=3,verify_all,leak_scan`.
  Scripted captures wait for the camera to be bound and for the previous capture to finish.
- Status lines also go to logcat under `PrivateVault`, as in Phase 1.

## 10. Build variants

- `debug`: as Phase 1, plus the test hooks under `BuildConfig.DEBUG`.
- `benchmark` (new): `initWith(release)`, `isDebuggable = false`, signed with the debug
  signing config, native code built with the Release CMake configuration (which the Phase 1
  Gradle setup already uses for non-debug variants). Used for the Section 9.2 measurements.
  Results come from logcat because `run-as` does not work on a non-debuggable build.
- `release`: unchanged, unsigned.

## 11. Threat model deltas (for `docs/THREAT_MODEL.md`)

- New asset: captured media in camera stack memory (HAL, ISP, gralloc, `ImageReader`,
  preview surface) and in CameraX internal buffers. Outside app control; not wiped.
- New threat: a screenshot, screen recording, or recents thumbnail of the preview.
  `FLAG_SECURE` on the capture activity is the defence; verified on the device for the
  recents thumbnail only.
- Metadata: object sizes distinguish photos from video segments and from index objects;
  mtimes track capture times; segment counts reveal clip lengths. Unchanged from the Phase 1
  position: documented, not hidden.
- Unverifiable without root: vendor camera dumps under `/data/vendor`, MIUI system services,
  kernel and GPU buffers, swap and zram.
- Kotlin VMK copy: unchanged.
- Permissions: `CAMERA` only. No location; EXIF written by CameraX on the in-memory path
  carries no GPS because no location is ever given to it.

## 12. Test plan

T1 (Windows, CTest), new: `test_memory_source`, `test_item_header`, `test_item_objects`
(item-wrapped objects end to end through the memory source and the item sink, tamper and
truncation still fail before any header parse), golden vectors `item_jpeg_small`,
`item_jpeg_chunk_plus_1`, `item_unknown` under `tests/fixtures/v1/` with the manifest
extended by `media_type`, `rotation`, `width`, `height`, `created_unix_ms`, `payload_len`
columns (`-` for unknown items).

T2 (emulator, instrumented), new classes: `ItemHeaderTest` (JNI round trip of the header
fields), `CaptureTest` (permission granted by rule; three captures; three objects; item
fields; `BitmapFactory.decodeByteArray` of the decrypted payload gives the recorded size;
hash equality between the direct-buffer hash before encrypt and the decrypt hash;
`isDirect` recorded), `CaptureFailureTest` (permission revoked through `UiAutomation`;
unbind during capture; fault flag; low-space threshold; queue limit), `LeakScanTest`
(before and after captures), `LockBoundKeyTest` (runs only when the device reports a secure
lock screen; the PIN is set and cleared by the host script, not the test), and the Android
to Windows export of item vectors.

T3 (Xiaomi): the brief, Section 12.

## 13. Checkpoint items (resolved 2026-10-13)

All five were approved as proposed: the header layout with the 48 to 4096 `header_len`
range (P2-2); the ABI set of Section 4 including the item sink and
`pv_item_header_check_len` (P2-3); the 64 MiB low-space threshold and `MAX_PENDING = 2`;
3 s segments and the prototype index layout; `verify` kept as an alias of `verify_all`.

## 14. What the implementation changed against this design

- Section 8.2, V3a: `VideoCapture.withOutput` with a `VideoOutput` that implements only its
  abstract method fails at bind time ("MediaSpec can't be null"), because `VideoCapture`
  needs the restricted `getMediaSpec()`. The prototype records with V3b, without an
  on-screen viewfinder. Evidence and consequences: `docs/VIDEO_CAPTURE_FEASIBILITY.md`.
- Section 8.3, item 1: the prototype does not choose the resolution; CameraX picks it for
  the `Preview` request (1280x960 on the emulator back camera, the largest size it offers,
  rather than the 720p of the plan), and the encoder is created at that size. The I-frame
  interval is 1 s instead of 2 s, with a sync frame requested at every 3 s boundary, so a
  segment boundary waits at most one second for a key frame.
- Section 8.3, item 4: the muxer output is buffered per complete segment and then read by
  the memory source, not streamed through a queue while muxing (D-014). The bound is
  8 MiB per segment and two completed segments in flight.
- Section 2.4: chunk size stays at 64 KiB. Emulator encrypt times for a 570 KB JPEG were 8
  to 80 ms in both debug and benchmark builds; a Windows CLI comparison on a 5 MB file gave
  231 to 278 ms at 64 KiB against 182 to 258 ms at 256 KiB, dominated by the Argon2id open,
  with no consistent difference. On the Xiaomi the benchmark build encrypts a capture of
  about 2.6 MB in 45 to 59 ms (debug build 139 to 301 ms) at 64 KiB, which leaves no
  reason to change the size.
- Section 7, item 1 (D-015): the scan builds the external paths (`Android/data`,
  `Android/media`, `Android/obb` under the primary external storage and under every
  `StorageManager` volume on API 30 and later) and walks them only if they already exist,
  instead of calling the `Context` getters, which create the directories they return. Every
  MediaStore row owned by the app is named in the scan output.
- Section 5.1: the JNI entry points are as listed, plus `nativeEncryptItemSampleFile` for
  the Android-to-Windows item vectors and three debug-only hooks (`nativeDecryptToArray`,
  `nativeHashDirectBuffer`, `nativeSetTestHooks`) compiled only when the debug variant
  passes `PV_ANDROID_DEBUG_HOOKS`.
- Section 7: the merged manifest needed `tools:node="remove"` rules for a permission and two
  components added by dependencies (D-013).
- Scripted actions gained `flip`, `memory`, `test_hook_slow=<ms>`, and `test_hook_clear`
  (debug only); all capture-screen actions are reached through `MainActivity`, which hands
  the rest of its action list to `CaptureActivity`, because that activity is not exported
  and `am start` cannot reach it directly.
