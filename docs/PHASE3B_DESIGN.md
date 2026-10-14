# Phase 3B design: protected photo viewer, delete, dependency cleanup

Status: proposed 2026-10-14 for the Milestone B checkpoint; approved by the developer the
same day with four changes, all applied below (CP-5: the platform `ExifInterface` instead of
an own reader, 5.3; CP-1: a debug-only log line when the tag and the header disagree, 5.2;
CP-9: the Android Studio profiler for the heap investigation, 10; threat model wording on
zram, 16). CP-8 accepted, with R8 plus JNI keep rules recorded as a hardening item in
`docs/ROADMAP.md`. Items marked **P3-x** are the decisions in `PHASE3B_BRIEF.md` Section 3;
items marked **CP-n** are the choices this document made beyond the brief (Section 14). Nothing here changes the
version 1 object format, the item header, or envelope providers 1 to 3.

Facts from earlier phases are not repeated; see `docs/PHASE1_DESIGN.md` (object format),
`docs/PHASE2_DESIGN.md` (capture, item header), `docs/PHASE3A_DESIGN.md` (lock). Where this
document says "measured" it names the run (`docs/PHASE3B_PROGRESS.md`, Milestone A); where it
says "assumed" the Milestone E phone run has to confirm it. Everything else is design intent to
be confirmed by the Milestone C and D tests.

## 1. Summary

The vault screen gains a gallery: a grid of thumbnails of every object, newest first, decoded
lazily from objects that decrypt into guarded native memory and are wiped right after the
decode (P3-9). Tapping a photo opens a single-photo viewer, fit to screen, zoom to 2x, with a
Delete button behind an in-activity confirmation (P3-11). Objects above 32 MiB of plaintext,
objects without a JPEG item header, and objects that fail to decrypt get labelled tiles and
are never shown. `camera-video` leaves every build variant and media3 stays only in the
debug variant for the video prototype's muxer (P3-10); the two probe paths that needed
`camera-video` are deleted. The Java heap growth per capture seen on the Xiaomi is
investigated with heap dumps (P3-12). Every window carries `FLAG_SECURE` and no `Dialog`,
popup, or toast shows content (P3-14).

```
objects/<id>.pvo ──pv_object_plaintext_size──> n (no decrypt)
    n > 32 MiB ──────────────────────────────> "large item" tile
    pv_secure_alloc(n) ──> sodium_malloc block (guard pages, canary, wiped on free; not locked)
    pv_vault_decrypt_to_buffer(id, buf, n, cancel) ──> OK only after FINAL; any failure wipes buf
    pv_item_header_decode ──> media_type 1, or "unknown item" tile (buf wiped at once)
    JNI NewDirectByteBuffer(buf + header_len, payload_len)
    ImageDecoder.createSource(buffer), setTargetSize(view or 256 px), ALLOCATOR_SOFTWARE, mutable
    orientation rule (Section 5)
    pv_secure_free(buf, n) ──> wipe, free
    software Bitmap ──> shown; eraseColor(0) and recycle() on stop, lock, eviction, or replacement
```

## 2. Viewer pipeline

### 2.1 Steps per object (brief 4.1)

| Step | Where | Rule |
|---|---|---|
| 1 | core | `pv_object_plaintext_size(v, id, &n)`: reads and validates the 128-byte object header (magic, version, suite, chunk size range, object ID equals the file name), reads the file length, and computes the exact plaintext length (Section 7.1). No decryption, no allocation beyond the header. |
| 2 | Kotlin | `n > PhotoLoader.SIZE_CAP` (32 MiB, CP-6): the object is a **large item**; nothing further happens to it. |
| 3 | JNI | `pv_secure_alloc(n, &buf)`: `sodium_malloc`. On failure `PV_ERR_OUT_OF_MEMORY` is reported and the tile shows "out of memory"; there is no plain `malloc` fallback (CP-2, Section 2.3). |
| 4 | core | `pv_vault_decrypt_to_buffer(v, id, buf, n, &len, cancel)`: computes the length again and refuses a buffer that is too small or a file whose length check fails before writing anything; then streams the object through the Phase 1 decrypt into `buf`. Returns `PV_OK` only after the FINAL chunk verified and `len == n`. On any failure after the stream started (authentication, truncation, trailing data, I/O, cancel, a plaintext longer than `n`) the whole buffer is wiped with `sodium_memzero` before the call returns. |
| 5 | JNI | `pv_item_header_decode(buf, len)` and `pv_item_header_check_len`. Not an item, malformed, or `media_type != 1`: **unknown item**; the buffer is freed (wiped) at once. |
| 6 | JNI | `NewDirectByteBuffer(buf, n)` returned to Kotlin together with the header fields; Kotlin positions it at `header_len` and slices the payload. The buffer object is a view over native memory; no byte is copied into the Java heap by app code. |
| 7 | Kotlin | `android.media.ExifInterface` over an `InputStream` view of the payload buffer reads the EXIF orientation tag (Section 5.3). |
| 8 | Kotlin | `ImageDecoder.createSource(payload)`, `decodeBitmap` with a header listener that sets `setTargetSize` to the fit size for the view (viewer) or 256 px on the long side (thumbnail), `ALLOCATOR_SOFTWARE`, `isMutableRequired = true`. The decoder applies the EXIF orientation itself (measured, Section 5.1). |
| 9 | JNI | `pv_secure_free(buf, n)` right after `decodeBitmap` returns, success or failure (`finally`). |
| 10 | Kotlin | The orientation rule decides whether the draw matrix also rotates by the header `rotation_degrees` (Section 5.2). |

Steps 1 and 3 to 6 are one JNI call, `nativeViewerLoad` (Section 8), so a buffer never exists
without the native side owning it, and on every failure the native side has already wiped and
freed it before Kotlin sees a status. The call runs inside the `Vault` read-write guard like
every other core call; a lock closes the handle only after it returns (Section 2.4).

### 2.2 Decoded bitmaps

- Software bitmaps only (`ALLOCATOR_SOFTWARE`), mutable, `ARGB_8888`, so `eraseColor(0)`
  overwrites the pixels before `recycle()` frees them. A hardware bitmap could not be erased
  by the app. Measured on the emulator: `ImageDecoder` over the direct buffer gives a mutable
  `ARGB_8888` bitmap with these settings.
- Bitmaps live in exactly two places: the viewer's current bitmap and the gallery's
  thumbnail cache (Section 3.2). Both are erased and dropped in `onStop` of their activity and
  on `LOCKED_UI`. A bitmap being replaced (new photo, new target size) is erased first.
- Decode target: the viewer asks for the size that fits the view at scale 1; zoom to 2x
  scales that bitmap on draw (P3-9: no full-resolution and no region decoding). A 3264x2448
  photo shown on a 1080x2400 screen decodes to about 1080x810 (3.5 MB) instead of 32 MB.
  `ImageDecoder` chooses the JPEG scale denominator (1 to 8) and scales the rest, so the
  decoded size is never above the target (T2 checks this).
- Thumbnails: long side 256 px. With the orientation rule the thumbnail may need a 90, 180,
  or 270 degree turn; it is turned once into a new small bitmap and the source is erased
  and recycled. The viewer turns on draw through its matrix instead, so no second
  screen-size bitmap exists.

### 2.3 Guarded memory (brief 4.5, measured)

`sodium_malloc` places the block before a guard page, fills it with 0xdb, checks a canary
on free, and wipes it on free. It also asks the kernel to lock the pages and ignores a
refusal. Measured on the emulator (`alloc_probe`): allocations of 5, 8, 16, 32, and 64 MiB
succeed (3 to 28 ms including the fill; 1 to 6 ms to wipe and free), and a second `mlock`
on the block fails with `ENOMEM` because `RLIMIT_MEMLOCK` is 65 536 bytes. The phone reports
the same 64 KiB limit (`ulimit -l`), so the result there is assumed to be the same until the
phone probe runs. Consequence: the viewer buffer is guarded and wiped but **not locked**; a
page of it could in principle reach swap or zram. Recorded in the threat model
(Section 16).

Why no plain `malloc` fallback (CP-2): `sodium_malloc` fails only when its `mmap` fails;
a plain `malloc` of the same size uses `mmap` too and would fail for the same reason. The
fallback would add a second free path (a `sodium_free` on a `malloc` block aborts the
process) for no gain. The failure is reported as `PV_ERR_OUT_OF_MEMORY`, the tile says "out
of memory", and the app stays usable; a debug hook forces that path for T2.

### 2.4 Lock during a load (brief 4.1, Section 5)

`LockController` posts `LOCKED_UI` first; the gallery and viewer activities finish to the
lock screen at once and erase their bitmaps in `onStop`. The decrypt in flight is cancelled:
`Vault.close()` sets the viewer cancel flag before taking the write side of the guard, so
`pv_vault_decrypt_to_buffer` stops at its next chunk with `PV_ERR_CANCELLED`, wipes the
buffer, and returns; the read lock drops, `close()` closes the handle and clears the flag
again (CP-4). The flag is set only between those two points, so no other code path is
affected and nothing leaks across a lock as the Phase 3A encrypt flag did. A decode
(`ImageDecoder`) in flight cannot be interrupted; it finishes within its bound (tens of
milliseconds at viewer sizes) and the loader discards the bitmap (erase, recycle) because
the activity is no longer started. Measured decode times on the emulator at sample size 4:
4 to 11 ms; the phone figures come with Milestone E.

What the app does not control (brief 4.1, threat model Section 16): the decoder's internal
rows and scratch buffers, the view's render node and the GPU texture of the software
bitmap, the compositor buffers. `FLAG_SECURE` keeps them out of screenshots, recordings, and
the recents card; nothing erases them.

## 3. Gallery (brief 4.2)

### 3.1 Listing and tiles

- `GalleryActivity` (not exported, `FLAG_SECURE`, `LockController.Listener`, routes to the
  lock screen like the vault screen). Reached from a "Gallery" button on the vault screen.
- The list comes from the object directory without decrypting anything: for each `*.pvo`,
  the ID (file name), the file length, and the modification time; order newest first
  (already visible metadata, Phase 1 position).
- A platform `GridView` with three columns shows one tile per object (CP-5: no RecyclerView
  dependency). Each tile is a square `ImageView` with a label `TextView` over it. Labels
  carry no content: "photo" tiles show the thumbnail and nothing else; the other tiles show
  a grey square with one of the words below and no image.
- Thumbnails decode lazily: when a cell binds, its load is queued on the one viewer thread
  (`pv-view`, a single-thread executor shared by gallery and viewer); the cell shows
  "loading" until the result arrives for that ID and that cell is still bound to it.
- Tile kinds and how they are decided:

| Tile | Decided by | Buffer |
|---|---|---|
| photo | `media_type == 1`, decode succeeded | wiped after decode |
| large item | `pv_object_plaintext_size > 32 MiB` | never allocated |
| unknown item | `PV_ERR_NOT_ITEM`, `PV_ERR_ITEM_MALFORMED`, `PV_ERR_ITEM_UNSUPPORTED`, or `media_type != 1` (video segments and indexes of the prototype included) | wiped at once |
| damaged | any decrypt failure (`AUTH_FAILED`, `TRUNCATED`, `TRAILING_DATA`, `MALFORMED_HEADER`, `MALFORMED_CHUNK`, `WRONG_VMK`, `IO`, `NOT_FOUND`, a plaintext size that does not match) or a JPEG the decoder rejects (`DecodeException`); the label carries the error name | wiped by the core (decrypt failure) or after the decode |
| out of memory | `PV_ERR_OUT_OF_MEMORY` from `pv_secure_alloc`, or `OutOfMemoryError` from the decode | none, or wiped |
| cancelled | `PV_ERR_CANCELLED` (the activity is finishing anyway) | wiped by the core |

- Size cap 32 MiB (CP-6): the two Phase 1 synthetic 100 MB objects on the phone are never
  decrypted for display. 32 MiB is about ten times the largest JPEG the Xiaomi delivered
  (3.72 MB) and keeps the worst case at one 32 MiB block plus one decoded bitmap.
- Tapping a photo tile opens the viewer for that ID. Other tiles do nothing on tap.

### 3.2 Thumbnail cache

`ThumbCache`: an `LruCache<String, Bitmap>` keyed by object ID hex, 32 entries (CP-6), every
entry a 256 px software bitmap (at most 256x256x4 = 256 KiB; 8 MiB in all). `entryRemoved`
erases and recycles the evicted bitmap. The cache is process scoped so returning from the
viewer does not redecode, but it is **cleared (erase, recycle) in `GalleryActivity.onStop`
and on `LOCKED_UI`**, so no thumbnail survives leaving the gallery or a lock. Nothing is
written to disk (P3-9: no persistent thumbnails).

## 4. Viewer (P3-9)

- `ViewerActivity` (not exported, `FLAG_SECURE`, listener, routes to the lock screen).
  Extras: the object ID only (hex). Saved instance state: nothing.
- `PhotoView`, a plain `View` subclass that draws one bitmap through a `Matrix`: the fit
  scale, an optional 90, 180, or 270 degree turn from the orientation rule, the user's zoom
  (1x to 2x of the fit scale, `ScaleGestureDetector`), and panning within the image bounds
  (`GestureDetector`); double tap toggles 1x and 2x. The bitmap stays at screen size; the
  matrix does the zoom.
- The load runs on the viewer thread after the first layout, with the view size as the
  target. While loading, the view is black with a "loading" label; errors show the same
  tile words as the gallery (no content in any case).
- Buttons: Back (finish), Delete (Section 6). The Delete confirmation is a second view
  inside the activity ("Delete this photo? Delete / Cancel"), not a `Dialog` (P3-14).
- `onStop`: erase and recycle the bitmap, drop the view's reference. `onStart` after a stop
  (the activity was only hidden): reload. `LOCKED_UI`: finish; the erase happens in `onStop`.
- Rotation of the device: `android:configChanges` is left at the default, so the activity
  is recreated; `onStop` erases, the new instance reloads at the new size. Simple and leaves
  no bitmap behind.
- Phase 4 seam: the temporal-interference experiment attaches to `PhotoView.onDraw`; nothing
  about the vault or the pipeline changes for it.

## 5. Orientation rule (brief 4.3)

### 5.1 Probe results

`orient_probe` on the emulator, trial debug build, 8 JPEG objects captured through
`CaptureActivity` (`build/p3b/E3-orient.txt`):

| Camera, display | Header rotation | Header WxH | EXIF orientation | EXIF width x length | `BitmapFactory` bounds | `ImageDecoder` header size | Decoded (sample 4) BF / ID |
|---|---|---|---|---|---|---|---|
| back, portrait (4 objects) | 90 | 1280x960 | 6 | 1280x960 | 1280x960 | 960x1280 | 320x240 / 240x320 |
| front, portrait (3 objects) | 270 | 1920x1440 | 8 | 1920x1440 | 1920x1440 | 1440x1920 | 480x360 / 360x480 |
| back, display rotated (1 object) | 0 | 1280x960 | 1 | 1280x960 | 1280x960 | 1280x960 | 320x240 / 320x240 |

Measured facts: (a) CameraX writes an EXIF orientation tag into the in-memory JPEG that
equals `ImageInfo.rotationDegrees`, which is what the item header stores (6 for 90, 8 for
270, 1 for 0); (b) `BitmapFactory` ignores the tag and returns the stored pixel size;
(c) `ImageDecoder` applies the tag on its own: its header size and its decoded bitmap are
rotated. On the Xiaomi (Phase 2, measured) portrait captures arrive as 2448x3264 with
`rotation_degrees` 0, so the HAL rotates the pixels; its EXIF orientation tag is
**assumed** to be 1 or absent until the phone probe runs (Section "Phone steps" in the
progress log).

### 5.2 The rule (CP-1)

> Decode with `ImageDecoder`. Read the EXIF orientation tag from the JPEG first. If the tag
> is present and not 1, the decoder has already turned the image; apply nothing more. If the
> tag is absent or 1, turn the decoded image by the header `rotation_degrees`.

| Device | EXIF | Header | Decoder output | Extra turn | Result |
|---|---|---|---|---|---|
| Emulator back, portrait | 6 | 90 | turned 90 | none | upright, once |
| Emulator front, portrait | 8 | 270 | turned 270 | none | upright, once |
| Emulator, landscape | 1 | 0 | as stored | none (0) | upright |
| Xiaomi (assumed) | 1 or absent | 0 | as stored, already rotated by the HAL | none (0) | upright |
| A device that writes no EXIF but reports rotation | absent | 90 | as stored | 90 by header | upright, once |
| Conflict (EXIF set, header differs) | 6 | 180 | turned 90 | none | EXIF wins; the debug build logs one line per object naming the ID, the tag, and the header rotation (developer amendment), no content |

The rule never turns twice because the header turn is applied only when the decoder had
nothing to apply. It is one rule for both devices, and T2 covers all sixteen combinations of
EXIF 1, 3, 6, 8 with header rotation 0, 90, 180, 270 on committed synthetic fixtures
(Section 15). Mirrored EXIF values (2, 4, 5, 7) count as "present and not 1": the decoder
applies them, nothing more is done.

### 5.3 Reading the tag (CP-5, as amended)

`JpegExif.orientation(buffer: ByteBuffer): Int` (0 when absent) wraps the payload buffer in a
small `InputStream` and hands it to the platform `android.media.ExifInterface(InputStream)`,
then reads `TAG_ORIENTATION`. The Milestone A probe used exactly this path over the direct
buffer: no file, no copy of image data by app code beyond what the platform class reads for
its parse, no dependency. The lint `ExifInterface` warning on that class is suppressed at the
one call site with a comment naming this section; the developer chose the platform class over
an own parser (the proposal) and over `androidx.exifinterface` (in the APK through
`camera-core`, but a transitive artifact). T2 checks the tag on the fixtures.

## 6. Delete one photo (P3-11, brief 4.4)

- From the viewer, unlocked, after the in-activity confirmation. The call runs on the viewer
  thread through the `Vault` guard: `pv_vault_delete_object(v, id)`.
- Core: builds `objects/<id>.pvo` and `objects/<id>.pvo.partial`; removes the partial first
  if present (not an error when absent), then unlinks the object file, then `fsync`s the
  directory on POSIX. Missing object file: `PV_ERR_NOT_FOUND`, reported by name; the viewer
  shows "already gone" and returns to the gallery, which rebuilds its list. Any other
  failure: the error name, object left as it was.
- `unlink` is atomic: the file is gone or present, never half (brief Section 5). A process
  killed between the partial removal and the unlink leaves the object intact; killed after
  the unlink, the object is gone; the next gallery open lists what is there. The thumbnail
  cache entry for the ID is erased and removed when the viewer returns.
- This is file removal, not cryptographic erasure: the object's DEK lives in its own header,
  so once the directory entry is gone nothing remains that would decrypt the blocks, but the
  ciphertext blocks may persist on flash until the controller reuses them (Phase 1 position,
  threat model Section 16). A cryptographic erasure design (destroying the wrapped DEK
  first) is a Phase 6 item and not changed here.
- Scripted debug action `delete_object=<id hex>` on the vault screen prints the status name.

## 7. C ABI additions (brief 4.5; CP-3)

Additive only, declared in `pv.h` after the Phase 3A functions, D-011 shape (status
returned, data through output parameters).

```c
#define PV_ERR_BUFFER_TOO_SMALL 25   // the plaintext does not fit the caller's buffer
#define PV_ERR_CANCELLED 26          // the caller's cancel flag was set during the operation

// Exact plaintext length of an object from its header and file length, without decrypting.
int pv_object_plaintext_size(pv_vault* v, const uint8_t* object_id, uint64_t* out);
int pv_object_plaintext_size_file(const char* path, uint64_t* out);   // for pv inspect, no handle

// Decrypts an object into buf. cap is the buffer size; the plaintext length (from
// pv_object_plaintext_size) must fit, otherwise PV_ERR_BUFFER_TOO_SMALL before any byte is
// written. cancel may be NULL; when it points at a nonzero int between two chunks the call
// stops with PV_ERR_CANCELLED. On PV_OK *out_len is the plaintext length. On any other
// status the whole buffer (cap bytes) has been wiped.
int pv_vault_decrypt_to_buffer(pv_vault* v, const uint8_t* object_id, uint8_t* buf, uint64_t cap,
                               uint64_t* out_len, const volatile int* cancel);

// Guarded memory for plaintext: guard pages, canary, wiped on free (sodium_malloc).
// n must be nonzero. free takes the pointer and the size it was allocated with.
int pv_secure_alloc(size_t n, void** out);
void pv_secure_free(void* p, size_t n);

// Removes objects/<id>.pvo and a matching .partial. Missing object: PV_ERR_NOT_FOUND.
int pv_vault_delete_object(pv_vault* v, const uint8_t* object_id);
```

Differences from the brief's sketch: `pv_vault_decrypt_to_buffer` gains `out_len` and the
`cancel` pointer (Section 2.4); "object not found" reuses the existing `PV_ERR_NOT_FOUND`
(20) rather than a new code; two new codes, 25 and 26, with `pv_error_name` and
`NativeBridge` mirrors.

### 7.1 Plaintext length without decrypting

For a well-formed object, file length `L`, header 128 bytes, chunk size `c`, plaintext `P`:
every chunk carries 17 bytes of tag, every non-final chunk carries `c` plaintext bytes, the
final chunk carries `P mod c` bytes (an empty final chunk when `P` is a multiple of `c`,
Phase 1 writer rule). With `q = floor(P / c)` and `r = P mod c`:

```
L - 128 = q * (c + 17) + r + 17
q = (L - 128 - 17) div (c + 17)         r = (L - 128 - 17) mod (c + 17)         P = q * c + r
```

Rules: `L < 128 + 17` gives `PV_ERR_TRUNCATED` (no final chunk can exist); `r >= c` gives
`PV_ERR_MALFORMED_CHUNK` (bytes that form no whole chunk); the header is validated with the
Phase 1 parser first, and a header object ID that differs from the name gives
`PV_ERR_MALFORMED_HEADER`. The value is exact for every well-formed object. For a damaged
object it is at best a plausible number that the decrypt then refutes (a file cut inside its
final chunk reads as a slightly shorter object until that chunk fails its tag); a file cut at
a chunk boundary, or with fewer than 17 bytes after the last whole chunk, leaves a remainder
of at least `c` bytes and is refused as `MALFORMED_CHUNK` by the length check itself. The
loader trusts only the decrypt status. `fs` gains `file_size` and `sync_dir`; a `_file` form
of the length function serves `pv inspect`, which has no vault handle (Section 7.3). T1 checks exactness for
empty, 1 byte, `c - 1`, `c`, `c + 1`, many chunks, both chunk sizes, and the 100 MB object.

### 7.2 Decrypt to buffer

Implemented over the Phase 1 decrypt with a sink that writes into the buffer and refuses to
pass `cap` (`PV_ERR_BUFFER_TOO_SMALL`), after the length check of Section 7.1 (a failure
there, including a too-small buffer, returns before any byte is written and leaves the
buffer as it was). The cancel pointer is read by the sink before every chunk; a reader
failure stays `PV_ERR_IO`. On every failure after the stream started, `sodium_memzero(buf,
cap)` runs before return, so a caller never holds partial plaintext from a stream that did
not verify (Phase 1 rule: output before FINAL is provisional).

### 7.3 Desktop CLI

`pv verify` is unchanged. `pv inspect` gains the line `plaintext: <n> bytes (from the file
length)` so the Windows side of the plaintext-size function is visible; no key is needed for
it, matching `inspect`. `pv decrypt` keeps its temporary-file path.

## 8. JNI entry points (D-011 shape)

```kotlin
external fun nativeObjectPlaintextSize(handle: Long, id: ByteArray, out: LongArray): Int
// Allocates guarded memory for the whole plaintext, decrypts, decodes the item header.
// Returns the decrypt status. On OK: bufferOut[0] is a direct ByteBuffer over the whole
// plaintext (give it to nativeViewerFree), infoOut = [item_status, media_type, rotation,
// width, height, created_unix_ms, payload_len, header_len, plaintext_len]. On any other
// status nothing stays allocated. cap bounds the allocation (PV_ERR_BUFFER_TOO_SMALL).
external fun nativeViewerLoad(handle: Long, id: ByteArray, cap: Long, infoOut: LongArray,
                              bufferOut: Array<ByteBuffer?>): Int
external fun nativeViewerFree(buffer: ByteBuffer)        // pv_secure_free; wipes
external fun nativeSetViewerCancel(cancel: Boolean)      // the flag pv_vault_decrypt_to_buffer polls
external fun nativeDeleteObject(handle: Long, id: ByteArray): Int
// Debug variant only: force pv_secure_alloc to report PV_ERR_OUT_OF_MEMORY; slow the
// buffer sink per chunk so a lock can arrive during a decrypt.
external fun nativeSetViewerHooks(allocFail: Boolean, slowChunkMs: Int)
```

The Milestone A probes (`nativeProbeSecureAlloc`, `nativeProbeDecryptDirect`,
`nativeProbeFreeDirect`, `ViewerProbes`) stay in the debug variant; `orient_probe` becomes a
T3 step on the phone and the viewer path replaces `nativeProbeDecryptDirect`.

Kotlin owners: `PhotoLoader` (main source set; `load(vault, id, targetW, targetH)` returns
a sealed `Loaded`: `Photo(bitmap, turnDegrees)`, `Large`, `Unknown`, `Damaged(name)`,
`NoMemory`, `Cancelled`), `ThumbCache`, `JpegExif`, `PhotoView`, `GalleryActivity`,
`ViewerActivity`. `JpegExif` is the thin wrapper of Section 5.3. `Vault` gains `plaintextSize(id)`, `viewerLoad(id, cap)` returning a
`GuardedPlaintext` (direct buffer plus header fields, `close()` frees it exactly once), and
`deleteObject(id)`.

## 9. Dependency cleanup (P3-10, brief 4.6)

Route taken (Milestone A, measured; D-022): `camera-view` keeps its place and excludes
`camera-video`; the explicit debug `camera-video` dependency is gone; `media3-muxer` 1.11.1
stays `debugImplementation` for the prototype's `FragmentedMp4Muxer`. The Phase 2 V2 probe
(`Recorder` over a proxy file descriptor) and the V3a attempt (`VideoCapture.withOutput` with
a custom `VideoOutput`) are deleted from `DebugVideoProbes.kt`; the V3b prototype (encoder
surface through the `Preview` use case) remains and passed on the emulator after the change
(288 frames, 4 segments, every segment parsed and decoded, `leak_scan` 0). Their results stay
in `docs/VIDEO_CAPTURE_FEASIBILITY.md` as history.

| Check (brief 4.6) | Result |
|---|---|
| 1 Trees recorded for the three runtime classpaths | `build/p3b/deps-*.txt`; route: `camera-view` to `camera-video` to media3 1.9.0 and guava |
| 2 `camera-video` removed from all variants | 0 lines in all three trees after the change; 0 `androidx.camera.video` classes defined in all three APKs (was 443 to 445) |
| 3 `PreviewView` still works | emulator: `camera open` and 8 verified captures through `CaptureActivity` in the trial debug build; benchmark build on the emulator and both builds on the Xiaomi are Milestone D and E steps |
| 4 Own `SurfaceView` fallback | not needed so far; stays the fallback if the phone shows otherwise |
| 5 One media3 version in debug | 1.11.1 only (`media3-muxer`, `media3-container`, `media3-common`); 1.11.1 is the newest 1.11.x on Google Maven (2026-10-14) |
| 6 Release and benchmark free of media3 and `camera-video`; sizes; manifests | 0 media3 classes, 0 `camera.video` classes; benchmark 13 822 758 to 10 529 341 bytes, release 10 521 149 bytes, debug 19 036 751 to 19 039 140 bytes (keeps media3 and guava for the prototype); merged manifests per variant are checked again in Milestone D |

Side effects seen: guava leaves the benchmark and release variants with media3 (it came
through `media3-common`), `com.google.guava:listenablefuture` resolves to the real 1.0
artifact instead of the empty placeholder, and `ListenableFuture` is defined in every APK.
The `ACCESS_NETWORK_STATE` merge rule (D-013) stays because the debug variant still carries
`media3-common`. The brief's phrase "R8-optimized benchmark build" does not match the
project: `isMinifyEnabled` is false for release and benchmark since Phase 2. CP-8 proposes
leaving it so in Stage 3B; turning R8 on is a separate change (keep rules for CameraX and
the reflection in `PvApp`, size and startup measurements) with no decision behind it.

## 10. Heap investigation plan (P3-12, brief 4.7; CP-9 as amended)

Goal: what allocates about one JPEG-sized Java array per capture on the Xiaomi, whether it
holds image data, and whether a public CameraX setting avoids it.

Tools: the developer is updating Android Studio to a version that opens the AGP 8.9 project
(route a); its profiler records Java allocations with call stacks. Route b, if the update is
not done when Milestone E runs: `am dumpheap`, `hprof-conv`, and the dump opened in Android
Studio (File, Open) or Eclipse MAT. Perfetto on the phone (v25, Android 13) covers native
allocations only (`heapprofd`), not Java allocation stacks. Plan:

1. Debug build on the Xiaomi, neutral scene, the Studio profiler attached to the process
   with Java allocation recording on, `open,capture_photo_n=10,memory` through the capture
   screen. The recording lists arrays above 1 MB with their allocation stacks and the
   allocating class and method.
2. Right after the burst: `adb shell am dumpheap <pid> /data/local/tmp/pv-before.hprof` (no
   collection forced), then the debug-only scripted action `gc` (`Runtime.gc()`,
   `runFinalization()`, `gc()` again) and a second dump `pv-after.hprof`. The dumps show
   retention: whether the arrays are still reachable after the collection and from which
   objects. Both pulled to `D:\pv-heap\` (outside the repository), opened in Android Studio
   or MAT, and deleted; they contain image data and are never committed, attached, or
   quoted beyond class names, sizes, and counts.
3. Native heap (it rose too, Phase 2 report): `heapprofd` through Perfetto with
   `--app com.privatevault.app` during the burst gives native allocation callstacks; the
   trace stays under `D:\pv-heap\` and is deleted after reading. Hypothesis from reading
   `camera-core` 1.6.2: the in-memory JPEG path copies the whole `ImageProxy` into a
   `byte[]` to parse its EXIF (`ProcessingInput2Packet`, `ImageUtil.jpegImageToJpegByteArray`),
   one JPEG-sized array per capture, released at the end of the pipeline. Hypothesis only
   until the recording shows it.
4. Report: allocating class and method, retention (freed at the next GC or held), image
   data yes or no, and the public CameraX settings reviewed (`setCaptureMode`,
   `setOutputFormat`, `setBufferFormat`, `setJpegQuality`, resolution strategy). Capture
   code changes only if a public setting removes the copy and T2 and the phone captures stay
   green; otherwise the threat model records a CameraX-internal copy of the JPEG in the Java
   heap that lives until the next collection.

## 11. Build and dependencies

- No new runtime dependency (CP-5): `GridView`, `ImageView`, `View`, `ImageDecoder`,
  `ScaleGestureDetector`, and the own EXIF reader are platform code.
- `camera-video` removed (Section 9). `versionCode` 4, `versionName` 0.4.0.
- Variants unchanged: debug (test hooks, probes, `ScriptActivity`), benchmark, release.

## 12. Failure handling (brief Section 5)

| Situation | Detection | Behaviour | Test |
|---|---|---|---|
| Lock during decrypt | cancel flag set in `Vault.close()` | `PV_ERR_CANCELLED`, buffer wiped by the core, loader returns `Cancelled`, activities already finished | T2 (`nativeSetViewerHooks` slow chunk, then `lock`) |
| Lock during decode | activity not started when the result arrives | bitmap erased and recycled, nothing shown | T2 |
| Decrypt fails | status from `pv_vault_decrypt_to_buffer` | buffer wiped by the core; "damaged" tile with the name; no partial image | T1 (tamper, truncation, trailing, wrong key: buffer all zero), T2 (tile) |
| Above the size cap | `pv_object_plaintext_size` | "large item", never decrypted | T2 (synthetic 40 MiB object) |
| No item header, wrong media type | `pv_item_header_decode` after `PV_OK` | "unknown item", buffer wiped at once | T2 (Phase 1 object, video segment object) |
| `sodium_malloc` fails | `PV_ERR_OUT_OF_MEMORY` | "out of memory" tile, app usable | T2 (alloc hook) |
| Out of memory on decode | `OutOfMemoryError` caught around `decodeBitmap` | error tile, buffer freed in `finally`, app usable | T2 (decode hook throws it) |
| JPEG the decoder rejects | `ImageDecoder.DecodeException` | "damaged" with the exception name | T2 (authenticated object with a non-JPEG payload) |
| Delete of a missing object | `PV_ERR_NOT_FOUND` | "already gone", gallery rebuilt | T1, T2 |
| Delete interrupted | none needed | file gone or present (unlink is atomic); partial removed first | T1 (object with a stale partial), T2 (state after the call at each step) |
| Object list changed under the gallery | binding by ID | a tile whose object vanished shows "damaged NOT_FOUND" until the next rebuild | T2 |

Test hooks live only in `androidTest` or debug-only code (`nativeSetViewerHooks`, the `gc`
and `delete_object` actions, `ViewerProbes`).

## 13. Memory budget (to be measured in Milestone E)

Worst case in the viewer on the phone: one 32 MiB guarded block (cap) during a load, one
screen-size software bitmap (1080x2400x4 = 10.4 MB at most, about 3.5 MB for a fit 4:3
photo), the thumbnail cache (8 MiB at most) if the gallery is still alive underneath, plus
decoder scratch. The T3 figures (PSS while viewing a 3264x2448 photo and while scrolling)
go in the test report.

## 14. Checkpoint items (CP-1 to CP-10)

| # | Item | Proposal |
|---|---|---|
| CP-1 | Orientation rule | EXIF tag present and not 1: the decoder's turn only; absent or 1: turn by the header rotation (Section 5.2). Xiaomi part assumed until the phone probe |
| CP-2 | `pv_secure_alloc` fallback | None: a failure is a clean `PV_ERR_OUT_OF_MEMORY`; `sodium_malloc` and `malloc` fail on the same `mmap` (Section 2.3) |
| CP-3 | ABI shape | `out_len` and `cancel` on `pv_vault_decrypt_to_buffer`; codes 25 and 26; `NOT_FOUND` reused; one JNI call for alloc, decrypt, header (Sections 7, 8) |
| CP-4 | Cancellation | Flag set inside `Vault.close()` before the write lock, cleared after (Section 2.4) |
| CP-5 | No new dependency | Platform `GridView`; EXIF tag through the platform `android.media.ExifInterface` over an in-memory stream (developer amendment; the proposal was an own reader) (Sections 3.1, 5.3) |
| CP-6 | Limits | Size cap 32 MiB; thumbnails 256 px, 32 entries; viewer decode at view size, zoom by matrix to 2x (Sections 2.2, 3) |
| CP-7 | Delete | Partial first, then the object; `NOT_FOUND` by name; no cryptographic erasure claim (Section 6) |
| CP-8 | R8 | Stays off for benchmark and release in Stage 3B (Section 9); R8 with JNI keep rules is a hardening item in `docs/ROADMAP.md` |
| CP-9 | Heap tools | Android Studio profiler for Java allocation recording plus `am dumpheap` (developer amendment: Studio is being updated); `hprof-conv` and Studio or MAT as the fallback; Perfetto `heapprofd` for native (Section 10) |
| CP-10 | Fixtures and probe code | Four committed synthetic JPEGs with hand-built EXIF (Section 15) made by a committed generator; the V2 and V3a probe deletion already done in Milestone A as part of the trial (P3-10) |

## 15. Test plan

**T1 (Windows, CTest).** All 16 existing tests. New `test_viewer_buffer`:
`pv_object_plaintext_size` exact for plaintext 0, 1, `c - 1`, `c`, `c + 1`, `2c`, `2c + 1`,
three chunks plus 123 bytes, at chunk sizes 4096 and 65536, and for a 100 MB object (label
`large`), each compared with the length the decrypt reports; a file cut to 128 + 16 bytes
gives `TRUNCATED`, a file with 5 bytes past a chunk boundary gives `MALFORMED_CHUNK`, a
renamed object gives `MALFORMED_HEADER`, a missing one `NOT_FOUND`. `pv_vault_decrypt_to_buffer`:
round trip equal to the plaintext for the same sizes; `cap` one byte short gives
`BUFFER_TOO_SMALL` with the buffer untouched (filled with a pattern before, still the pattern
after); tamper in the first, middle, and final chunk, truncation inside the final chunk and at
a chunk boundary, trailing byte, wrong VMK: each fails with its Phase 1 code and leaves every
byte of the buffer zero; cancel flag set before the call: `CANCELLED` after the first chunk,
buffer zero; `out_len` NULL allowed; argument checks. `pv_secure_alloc` 0 and NULL refused,
1 byte and 32 MiB accepted, free wipes (checked through a second allocation is not
deterministic, so the test checks only that free does not fault and that alloc after free
works). `pv_vault_delete_object`: object gone, `NOT_FOUND` on a second call, an object with a
stale partial loses both, other objects intact and still verify, missing partial not an
error, argument checks. `test_golden`: every committed vector also decrypts through
`pv_vault_decrypt_to_buffer` into the exact size and hashes to the manifest value.
`test_cli`: `inspect` prints the plaintext line.

**Fixtures (committed, synthetic).** `tests/fixtures/exif/orient_{1,3,6,8}.jpg`: 64x32 pixel
JPEGs, grey with a 16x16 red square at the top left, each with a hand-built APP1 EXIF segment
(TIFF little-endian, one IFD0 entry, tag 0x0112) carrying orientation 1, 3, 6, or 8.
Generated by `tests/tools/make_exif_fixtures.py`: the image bytes come from one JPEG made
once with .NET `System.Drawing` through PowerShell (command in the fixtures README), and
the script splices the four EXIF segments into it; script and commands are committed next
to the files. The T2 test packages
them as test assets, wraps each in an item header with rotation 0, 90, 180, 270 (16 objects),
and checks after the loader runs that the red square sits where the rule says and that the
decoded size is bounded by the target.

**T2 (emulator, instrumented).** All 53 existing tests. New classes: `ViewerLoaderTest`
(the 16 orientation objects; decoded size bounded by the target for a large synthetic JPEG;
`large` tile for a 40 MiB synthetic object without any allocation (hook counts allocations);
`unknown` for a Phase 1 object, a video segment object, an item with media type 3; `damaged`
for a tampered and a truncated object with the right names; `damaged DecodeException` for
an item whose payload is not a JPEG; alloc failure hook gives `NoMemory` and the next load
works; decode OOM hook gives the error and the next load works; delete then `NOT_FOUND`;
lock during a slowed decrypt: the load returns `Cancelled`, the lock completes within 2 s,
the wipe counter advanced; `JpegExif` returns the fixture tags),
`ViewerScreenTest` (`FLAG_SECURE` on the gallery and viewer windows; after `onStop` the
viewer bitmap is recycled and the cache is empty, after `lock` likewise; a load result that
arrives after stop is recycled), `LeakScanTest.viewerSessionLeavesNothing` (scan, open every
photo through the loader and scroll the gallery by binding every cell, scan again: no new
files, zero markers, zero owned MediaStore rows, the runner's `additional_test_output` rows
accounted for as in 3A). Host-driven: scripted gallery and viewer run on the debug build with
`leak_scan` before and after; `aapt2` merged manifests of debug, benchmark, release (`CAMERA`
only; only `LockActivity` exported outside debug; `GalleryActivity` and `ViewerActivity` not
exported); `dexclasses.py` on the three APKs (0 media3, 0 `camera.video` outside debug);
`video_probe` on the debug build; benchmark build on the emulator driven through
`uiautomator dump` and `input tap` (the benchmark has no `ScriptActivity`): unlock, Gallery,
open a photo, Delete; screencap of gallery and viewer black.

**T3 (Xiaomi, developer taps installs).** Brief Section 7: gallery of the 55-object vault
(photos upright in portrait and landscape, the two 100 MB objects as `large`, the 1 MB
objects as `unknown`); open, zoom, delete one test photo; Home, screen off, MIUI camera case
with the viewer open; recents card blank on gallery and viewer; PSS while viewing a
3264x2448 photo and while scrolling; unlock time on the benchmark build at ops 2 / 256 MiB;
capture tests on the benchmark build; heap investigation (Section 10); `orient_probe` for
the Xiaomi row of Section 5.1; `leak_scan` zero.

## 16. Threat model deltas (for `docs/THREAT_MODEL.md` Section 8)

- Plaintext of one photo exists in process memory while it is shown: the guarded block for
  the length of the decode (wiped after), the software bitmap until stop or lock (erased),
  and copies the app does not control (decoder scratch, render node and GPU texture,
  compositor buffers), which `FLAG_SECURE` keeps out of screenshots and recents but nothing
  erases.
- The guarded block is wiped after use but not locked in memory (`RLIMIT_MEMLOCK` is 64 KiB
  on both devices), so its pages can reach zram while it exists.
- Objects above the size cap are never decrypted for display; what they contain is unknown
  to the viewer.
- Delete removes a directory entry; flash blocks may persist until reuse; no erasure claim.
- Heap finding from Section 10, once measured.
- An authorized viewer can still photograph the screen (Phase 1 position); nothing in 3B
  changes that.

## 17. Seams for 3C to 3E and Phase 4

- Tiles for media types 2 and 3 are "unknown item" now; the gallery's tile kind switch is
  where a video tile joins. The loader's guarded-buffer path works for a segment as it does
  for a photo; playback (3D) needs a decoder fed from guarded memory, which is the 3C
  investigation.
- `ViewerActivity` hosts one `PhotoView`; a video surface would sit in the same activity
  under the same lock rule.
- `pv_vault_decrypt_to_buffer` with its cancel flag is the shape a segment reader would use;
  seeking inside a segment stays the Phase 1 question (segment objects, option 1).
- `PhotoView.onDraw` is where the Phase 4 temporal-interference experiment attaches.

## 18. What the implementation changed against this design (Milestones C and D)

- Section 7: `pv_object_plaintext_size_file(path, out)` was added beside the handle form so
  `pv inspect`, which opens no vault, can print the plaintext length. Six functions, not five.
- Section 7.1: a file cut at a chunk boundary, or with fewer than 17 bytes after its last
  whole chunk, is refused by the length check as `MALFORMED_CHUNK`; the Phase 1 decrypt
  calls the same file `TRUNCATED`. A file cut inside its final chunk passes the length check
  and the decrypt refutes it with `AUTH_FAILED`, the Phase 1 code for a final chunk whose
  tag fails. The failure table row "truncation" means these two codes.
- Section 7.2: a failure of the length check, a too-small buffer, or a missing object returns
  before any byte is written and leaves the buffer as it was; only failures after the
  stream started wipe it.
- Section 3.2: the thumbnail cache belongs to the gallery activity instance and is emptied in
  its `onStop`, so returning from the viewer redecodes the visible thumbnails (a few
  milliseconds each) rather than keeping them alive in the process.
- Section 8: the JNI call that loads for the viewer frees the buffer itself for every object
  that is not a JPEG item, so Kotlin receives a buffer only for photos; the debug hook
  `nativeSetViewerHooks(allocFail, slowMs)` forces the allocation failure code and delays the
  start of the decrypt (the core sink itself is not slowed), which is enough for the lock
  test: the delayed decrypt sees the cancel flag at its first chunk.
- Section 5.3 (checkpoint): the tag is read with the platform `android.media.ExifInterface`
  over an in-memory stream.
- Section 6: `delete_object=<hex>` exists as a vault-screen scripted action; the viewer uses
  the same `Vault.deleteObject`. New scripted actions `view_all` (every object through the
  pipeline at thumbnail and screen size), `gallery`, and `gc`.
- Section 4: the viewer loads after the first layout at the view size; before layout it uses
  the display size.
