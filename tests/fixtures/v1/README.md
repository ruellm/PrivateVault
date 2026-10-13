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

Generated with `make_vectors` (`tests/tools/make_vectors.cpp`). The Phase 1 vectors were
written once in Phase 1; the item vectors were added in Phase 2 with the `--items` mode,
which leaves the other files and manifest lines as they are:

```
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1            # everything (do not run casually)
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1 --items    # item vectors only
```

Regenerating produces different bytes (fresh data keys, nonces, and stream headers) for the
same plaintext. The committed files are the reference; both the Windows tests
(`test_golden`) and the Android instrumented tests decrypt them.
