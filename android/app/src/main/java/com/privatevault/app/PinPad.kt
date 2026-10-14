package com.privatevault.app

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

// PIN digits held in a byte array, never a String. Zeroed on clear and when
// the owner is done with them.
class PinBuffer(private val max: Int = LockController.PIN_MAX) {
    private val buf = ByteArray(max)
    var length = 0
        private set

    fun push(digit: Int): Boolean {
        if (digit !in 0..9 || length >= max) return false
        buf[length++] = ('0'.code + digit).toByte()
        return true
    }

    fun pop() {
        if (length > 0) {
            length--
            buf[length] = 0
        }
    }

    fun clear() {
        buf.fill(0)
        length = 0
    }

    // A copy for the controller; the caller zeroes it after use.
    fun copy(): ByteArray = buf.copyOf(length)

    fun sameAs(other: PinBuffer): Boolean {
        if (length != other.length) return false
        var diff = 0
        for (i in 0 until length) diff = diff or (buf[i].toInt() xor other.buf[i].toInt())
        return diff == 0
    }

    fun isZeroed(): Boolean = buf.all { it == 0.toByte() } && length == 0
}

// The in-app numeric keypad: digits 0 to 9, delete, OK. No text field, so
// the system keyboard and autofill never see the digits.
class PinPad(context: Context, private val onDigit: (Int) -> Unit, private val onDelete: () -> Unit,
             private val onOk: () -> Unit) : LinearLayout(context) {

    private val buttons = ArrayList<Button>()

    init {
        orientation = VERTICAL
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("del", "0", "OK"))
        for (row in rows) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (label in row) {
                val b = Button(context).apply {
                    text = label
                    textSize = 22f
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        when (label) {
                            "del" -> onDelete()
                            "OK" -> onOk()
                            else -> onDigit(label[0] - '0')
                        }
                    }
                }
                buttons.add(b)
                line.addView(b)
            }
            addView(line)
        }
    }

    fun setKeysEnabled(enabled: Boolean) {
        for (b in buttons) b.isEnabled = enabled
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility != View.VISIBLE) setKeysEnabled(false)
    }
}
