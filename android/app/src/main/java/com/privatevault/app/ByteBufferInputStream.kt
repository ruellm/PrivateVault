package com.privatevault.app

import java.io.InputStream
import java.nio.ByteBuffer

// Reads a ByteBuffer as a stream; the buffer position moves with the reads.
// Callers pass a duplicate when the original position must stay.
class ByteBufferInputStream(private val buf: ByteBuffer) : InputStream() {
    override fun read(): Int = if (buf.hasRemaining()) buf.get().toInt() and 0xff else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!buf.hasRemaining()) return -1
        val n = minOf(len, buf.remaining())
        buf.get(b, off, n)
        return n
    }

    override fun available(): Int = buf.remaining()
}
