package com.privatevault.app

// TEST ONLY values, identical to tests/fixtures/v1. They exist only in the
// androidTest source set; the app never uses a fixed key.
object TestKeys {
    val VMK: ByteArray = "PRIVATEVAULT TEST ONLY VMK  0001".toByteArray(Charsets.US_ASCII)
    val SEED: ByteArray = "PRIVATEVAULT TEST ONLY SEED 0001".toByteArray(Charsets.US_ASCII)
    val OTHER_VMK: ByteArray = ByteArray(32) { 0x5a }
}

// Test access to the native handle for inspect calls.
fun Vault.handleForTest(): Long {
    val field = Vault::class.java.getDeclaredField("handle")
    field.isAccessible = true
    return field.getLong(this)
}
