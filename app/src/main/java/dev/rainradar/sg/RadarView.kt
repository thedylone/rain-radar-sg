package dev.rainradar.sg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Draws NEA's map stack, keeping the 853:479 aspect ratio. */
class RadarView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    var base: Bitmap? = null
        set(v) { field = v; invalidate() }
    var radar: Bitmap? = null
        set(v) { field = v; invalidate() }
    var labels: Bitmap? = null
        set(v) { field = v; invalidate() }

    private val dst = RectF()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, w * Radar.MAP_H / Radar.MAP_W)
    }

    override fun onDraw(c: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        c.save()
        c.clipRect(dst)
        Radar.draw(c, dst, base, radar, labels)
        c.restore()
    }
}
