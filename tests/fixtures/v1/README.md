# Golden vectors, object format version 1

TEST ONLY. Everything in this directory is synthetic and public. Nothing here is a secret.

- `*.pvo`: objects encrypted under the test vault master key with plaintext from the
  deterministic source (`docs/PHASE1_DESIGN.md` Section 13).
- `manifest.txt`: one line per vector: name, plaintext length, chunk size, BLAKE2b-256 of
  the plaintext, object ID.
- `test_vmk.txt`: the 32-byte test key as ASCII (`PRIVATEVAULT TEST ONLY VMK  0001`,
  note the two spaces before `0001`).
- `test_seed.txt`: the 32-byte seed as ASCII (`PRIVATEVAULT TEST ONLY SEED 0001`).

Generated once with `make_vectors` (`tests/tools/make_vectors.cpp`):

```
build\windows-x64-tests\tests\core\Debug\make_vectors.exe tests\fixtures\v1
```

Regenerating produces different bytes (fresh data keys, nonces, and stream headers) for the
same plaintext. The committed files are the reference; both the Windows tests
(`test_golden`) and the Android instrumented tests decrypt them.
