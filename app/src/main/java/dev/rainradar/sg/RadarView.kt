package dev.rainradar.sg

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.view.doOnLayout

/**
 * Draws NEA's map stack, keeping the 853:479 aspect ratio. Supports pinch / double-tap zoom and
 * panning, plus two markers: the live location dot and a saved pin (both in map pixels).
 */
class RadarView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    var base: Bitmap? = null
        set(v) { field = v; invalidate() }
    var radar: Bitmap? = null
        set(v) { field = v; invalidate() }
    var labels: Bitmap? = null
        set(v) { field = v; invalidate() }

    /** Saved pin, in map pixels (0..853, 0..479). */
    var pin: PointF? = null
        set(v) { field = v; invalidate() }
    /** Live location, in map pixels. */
    var me: PointF? = null
        set(v) { field = v; invalidate() }
    /** Live location accuracy radius, in map pixels. */
    var meAccuracy = 0f
        set(v) { field = v; invalidate() }

    /** Called with the map-pixel point the user long-pressed. */
    var onLongPress: ((PointF) -> Unit)? = null
    /** Called whenever the zoom level changes (1 = whole map). */
    var onZoomChanged: ((Float) -> Unit)? = null

    var zoom = 1f
        private set
    private var tx = 0f
    private var ty = 0f
    private var anim: ValueAnimator? = null

    private val dst = RectF()
    private val markers = Markers()
    private val density = resources.displayMetrics.density

    /** View pixels per map pixel at zoom 1. */
    private val fit get() = if (width > 0) width.toFloat() / Radar.MAP_W else 1f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { anim?.cancel(); return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { anim?.cancel(); return true }

        override fun onScroll(e1: MotionEvent?, e: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1f) return false
            tx -= dx; ty -= dy
            clamp(); invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent) = performClick()

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom < 1.5f) zoomTo(DOUBLE_TAP_ZOOM, e.x, e.y) else reset()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val cb = onLongPress ?: return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            cb(viewToMap(e.x, e.y))
        }
    })

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, w * Radar.MAP_H / Radar.MAP_W)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Keep the same part of the map in view when the size changes.
        if (oldw > 0) { val k = w.toFloat() / oldw; tx *= k; ty *= k }
        clamp()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        // While pinching or zoomed in, drags pan the map instead of scrolling the page.
        if (e.pointerCount > 1 || zoom > 1f) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun performClick() = super.performClick()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.save()
        c.clipRect(0f, 0f, w, h)
        c.translate(tx, ty)
        c.scale(zoom, zoom)
        dst.set(0f, 0f, w, h)
        Radar.draw(c, dst, base, radar, labels)
        c.restore()

        // Markers keep a constant on-screen size.
        me?.let {
            val s = fit * zoom
            markers.dot(c, tx + it.x * s, ty + it.y * s, 6 * density, meAccuracy * s)
        }
        pin?.let {
            val s = fit * zoom
            markers.pin(c, tx + it.x * s, ty + it.y * s, 30 * density)
        }
    }

    // --- zoom -------------------------------------------------------------------------------

    fun zoomIn() = zoomTo(zoom * 2f, width / 2f, height / 2f)
    fun zoomOut() = zoomTo(zoom / 2f, width / 2f, height / 2f)
    fun reset() = animateTo(1f, 0f, 0f)

    /** Animates so map-pixel point [p] is centred, zoomed to at least [minZoom]. */
    fun focus(p: PointF, minZoom: Float = 3f) {
        if (!isLaidOut) { doOnLayout { focus(p, minZoom) }; return }
        val z = maxOf(zoom, minZoom).coerceIn(1f, MAX_ZOOM)
        val s = fit * z
        animateTo(z, width / 2f - p.x * s, height / 2f - p.y * s)
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val z = target.coerceIn(1f, MAX_ZOOM)
        val k = z / zoom
        animateTo(z, fx - (fx - tx) * k, fy - (fy - ty) * k)
    }

    private fun zoomAround(target: Float, fx: Float, fy: Float) {
        val z = target.coerceIn(1f, MAX_ZOOM)
        val k = z / zoom
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        setZoom(z)
        clamp(); invalidate()
    }

    private fun animateTo(z: Float, x: Float, y: Float) {
        anim?.cancel()
        val (x1, y1) = clamped(z, x, y)
        val z0 = zoom; val x0 = tx; val y0 = ty
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                tx = x0 + (x1 - x0) * f
                ty = y0 + (y1 - y0) * f
                setZoom(z0 + (z - z0) * f)
                invalidate()
            }
            start()
        }
    }

    private fun setZoom(z: Float) {
        if (z == zoom) return
        zoom = z
        onZoomChanged?.invoke(z)
    }

    private fun clamp() {
        val (x, y) = clamped(zoom, tx, ty)
        tx = x; ty = y
    }

    private fun clamped(z: Float, x: Float, y: Float): Pair<Float, Float> {
        val w = width.toFloat(); val h = height.toFloat()
        return x.coerceIn(w - w * z, 0f) to y.coerceIn(h - h * z, 0f)
    }

    private fun viewToMap(x: Float, y: Float): PointF {
        val s = fit * zoom
        return PointF((x - tx) / s, (y - ty) / s)
    }

    private companion object {
        const val MAX_ZOOM = 8f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
