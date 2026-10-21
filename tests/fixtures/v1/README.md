# Golden vectors, object format version 1

TEST ONLY. Everything in this directory is synthetic and public. Nothing here is a secret.

- `*.pvo`: objects encrypted under the test vault master key with plaintext from the
  deterministic source (`docs/PHASE1_DESIGN.md` Section 13).
- `manifest.txt`: one line per vector: name, plaintext length, chunk size, BLAKE2b-256,
  object ID, then six item columns: media type, rotation, width, height, created time
  (Unix ms), payload length. Plain vectors (Phase 1) carry `-` in the item columns and their
  hash covers the whole plaintext. Item vectors (Phase 2, names starting with `item_`) hold
  a 48-byte media item header (`docs/PHASE2_DESIGN.md` Section 3) followed by `payload_len`
  deterministic bytes; their length column is the whole plaintext and their hash covers the
  payload only.
- `test_vmk.txt`: the 32-byte test key as ASCII (`PRIVATEVAULT TEST ONLY VMK  0001`,
  note the two spaces before `0001`).
- `test_seed.txt`: the 32-byte seed as ASCII (`PRIVATEVAULT TEST ONLY SEED 0001`).
- `envelope3_single.key`, `envelope3_unknown_slot.key` (Phase 3A): provider 3 key envelopes
  (`docs/PHASE3A_DESIGN.md` Section 2) wrapping the test VMK under the fixed KEK stand-in
  `PRIVATEVAULT TEST ONLY KEK  0001` (32 ASCII bytes, two spaces before `0001`). The
  Keystore step of the real unlock is replaced by that constant, because it cannot run on
  Windows. The second file carries an unknown slot (type 7) before the PIN slot, which a
  reader must skip. `envelope3.txt` records the KEK, the test PIN `000000`, the salt
  `PVTESTONLYSALT01`, the Argon2id parameters, the Argon2id output `k_pin_hex` for them,
  the alias, and the vector names; both platforms check their PIN stretch against
  `k_pin_hex` and open both envelopes to the test VMK.
- `video_index_min.bin`, `video_index_three.bin`, `video_index_audio.bin` (Phase 3C): video
  index v1 payloads (`docs/PHASE3C_DESIGN.md` Section 4) with one segment and one key, three
  segments with 3, 2, and 4 keys, and the same three segments with the audio fields set (AAC,
  2 channels, 48 000 Hz) so a 3C reader accepts a 3E header. `item_video_index.pvo` is an
  item object of media type 3 whose payload is `video_index_three.bin`, under the test VMK.
  `video_index.txt` records segment and key counts, total duration, audio codec, the BLAKE2b
  of each index, and the object ID and payload hash of the item. Both platforms decode them
  and re-encode to the same bytes.

- `segment_one_fragment.bin`, `segment_samples.bin`, `segment.txt` (Phase 3E): a video segment from the core segment
  writer (`docs/PHASE3E_DESIGN.md` Section 4.4): one fragment of three synthetic samples at
  1920x1080 with rotation 90 (`ftyp`, `moov` with the `tkhd` matrix and `avcC`, `sidx`,
  `moof` with `tfdt` and a `trun` of durations, sizes, and flags, `mdat`). `segment.txt`
  records the parameter sets, the sample sizes and times, the box offsets, and the BLAKE2b
  of the file; `segment_samples.bin` holds the three input samples back to back (Annex B).
  Both platforms feed the samples through the writer and compare byte for byte.

Generated with `make_vectors` (`tests/tools/make_vectors.cpp`). The Phase 1 vectors were
written once in Phase 1; the item vectors were added in Phase 2 with the `--items` mode,
which leaves the other files and manifest lines as they are:

```
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1            # everything (do not run casually)
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --items    # item vectors only
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --envelope3   # envelope vectors only (Phase 3A)
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --video-index   # video index vectors only (Phase 3C)
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --segment   # segment writer vector only (Phase 3E)
```

Regenerating produces different bytes (fresh data keys, nonces, and stream headers) for the
same plaintext. The committed files are the reference; both the Windows tests
(`test_golden`) and the Android instrumented tests decrypt them.
