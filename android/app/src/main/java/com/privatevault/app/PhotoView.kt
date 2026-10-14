package com.privatevault.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Draws one bitmap fit to the view through a matrix: an optional quarter
// turn from the orientation rule, pinch zoom from 1x to 2x of the fit scale,
// panning inside the image bounds, double tap to toggle the zoom. The bitmap
// stays at the size it was decoded at; the matrix does the rest.
class PhotoView(context: Context) : View(context) {

    companion object {
        const val MAX_ZOOM = 2f
    }

    private var bitmap: Bitmap? = null
    private var turn = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
            clampPan()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX -= distanceX
            panY -= distanceY
            clampPan()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = if (zoom > 1f) 1f else MAX_ZOOM
            clampPan()
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
    }

    val zoomFactor: Float get() = zoom

    val hasPhoto: Boolean get() = bitmap?.isRecycled == false

    // Shows a bitmap; the view does not own the previous one.
    fun setPhoto(b: Bitmap?, turnDegrees: Int) {
        bitmap = b
        turn = turnDegrees
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    // Hands the bitmap back to the caller, who erases it.
    fun takePhoto(): Bitmap? {
        val b = bitmap
        bitmap = null
        invalidate()
        return b
    }

    private fun turnedWidth(b: Bitmap): Int = if (turn == 90 || turn == 270) b.height else b.width
    private fun turnedHeight(b: Bitmap): Int = if (turn == 90 || turn == 270) b.width else b.height

    private fun fitScale(b: Bitmap): Float {
        if (width == 0 || height == 0) return 1f
        return min(width.toFloat() / turnedWidth(b), height.toFloat() / turnedHeight(b))
    }

    private fun clampPan() {
        val b = bitmap ?: return
        val s = fitScale(b) * zoom
        val maxX = max(0f, (turnedWidth(b) * s - width) / 2f)
        val maxY = max(0f, (turnedHeight(b) * s - height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        if (b.isRecycled) return
        val s = fitScale(b) * zoom
        matrix.reset()
        matrix.postTranslate(-b.width / 2f, -b.height / 2f)
        matrix.postRotate(turn.toFloat())
        matrix.postScale(s, s)
        matrix.postTranslate(width / 2f + panX, height / 2f + panY)
        canvas.drawBitmap(b, matrix, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && abs(zoom - 1f) < 0.001f) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
