package com.privatevault.app

import java.io.File

// Actions over the objects of the open vault, shared by both activities and
// the scripted runners. Output lines carry object IDs, sizes, status names,
// item fields, and hashes of synthetic data only.
object ObjectActions {

    private val seed = "PRIVATEVAULT TEST ONLY SEED 0001".toByteArray(Charsets.US_ASCII)

    // Decrypts every object into a hash. Items report their header fields;
    // unknown items (Phase 1 synthetic objects) are checked against the
    // deterministic stream as Phase 1 did.
    fun verifyAll(vaults: VaultSession, say: (String) -> Unit) {
        val v = vaults.vault ?: throw IllegalStateException("vault not open")
        val ids = v.listObjectIds()
        if (ids.isEmpty()) say("no objects")
        var ok = 0
        for (id in ids) {
            if (!v.isOpen) {
                say("verify_all stopped: vault locked")
                break
            }
            val hex = NativeBridge.hex(id)
            val (rc, result) = v.decryptVerifyItem(id)
            if (rc != NativeBridge.OK || result == null) {
                say("$hex: FAIL ${NativeBridge.errorName(rc)}")
                continue
            }
            val item = result.item
            if (item != null) {
                say("$hex: ${item.typeName()} ${item.width}x${item.height} rotation ${item.rotation} created ${item.createdMs} payload ${item.payloadLen} bytes, plaintext ${result.plaintextLen}, ok")
                ok++
            } else if (result.itemStatus == NativeBridge.ERR_NOT_ITEM) {
                val (rc2, plain) = v.decryptVerify(id)
                if (rc2 != NativeBridge.OK || plain == null) {
                    say("$hex: unknown item, FAIL ${NativeBridge.errorName(rc2)}")
                    continue
                }
                val expect = ByteArray(NativeBridge.HASH_BYTES)
                NativeBridge.nativeHashDeterministic(seed, plain.length, expect)
                val match = expect.contentEquals(plain.hash)
                say("$hex: unknown item, ${plain.length} bytes, hash ${if (match) "matches" else "MISMATCH"}")
                if (match) ok++
            } else {
                say("$hex: item ${NativeBridge.errorName(result.itemStatus)}, plaintext ${result.plaintextLen}")
            }
        }
        say("verify_all: $ok of ${ids.size} ok")
    }

    // Lists objects with size and modification time, without decrypting.
    fun list(vaults: VaultSession, say: (String) -> Unit) {
        val v = vaults.vault ?: throw IllegalStateException("vault not open")
        val objects = File(v.dir, "objects").listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        say("objects dir: ${objects.size} entries")
        for (f in objects) {
            val kind = when {
                f.name.endsWith(".pvo.partial") -> "PARTIAL"
                f.name.endsWith(".pvo") -> "object"
                else -> "OTHER"
            }
            say("  $kind ${f.name} ${f.length()} bytes mtime ${f.lastModified()}")
        }
    }
}
