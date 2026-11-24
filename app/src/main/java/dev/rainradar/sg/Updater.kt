package dev.rainradar.sg

import android.content.Context
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import android.graphics.Bitmap

/** Downloads the latest frame, renders the widget image and pushes it to all widgets. */
object Updater {
    private const val RAW = "radar_latest.png"
    const val WIDGET_IMG = "widget.png"

    @Synchronized
    fun refresh(ctx: Context): Boolean {
        val frame = try {
            Radar.fetchLatest()
        } catch (e: IOException) {
            null
        }
        if (frame == null) {
            Prefs.recordAttempt(ctx, null, failed = true)
            Widgets.pushAll(ctx)
            return false
        }
        store(ctx, frame)
        return true
    }

    /** Saves a frame (e.g. one the app already downloaded) and updates the widgets. */
    @Synchronized
    fun store(ctx: Context, frame: Radar.Frame) {
        if (frame.time.toInstant().toEpochMilli() >= Prefs.frameTime(ctx)) {
            Radar.writeAtomic(File(ctx.filesDir, RAW), frame.bytes)
            Prefs.recordAttempt(ctx, frame.time.toInstant().toEpochMilli(), failed = false)
        }
        rerender(ctx)
    }

    /** Re-renders the widget image from the stored frame (e.g. after toggling labels). */
    @Synchronized
    fun rerender(ctx: Context) {
        val raw = File(ctx.filesDir, RAW)
        val radar = if (raw.exists()) BitmapFactory.decodeFile(raw.path) else null
        val labels = if (Prefs.showLabels(ctx)) Radar.labels(ctx) else null
        val img = Radar.render(Radar.baseMap(ctx), radar, labels)
        val out = ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.PNG, 100, out)
        Radar.writeAtomic(File(ctx.filesDir, WIDGET_IMG), out.toByteArray())
        Widgets.pushAll(ctx)
    }
}
