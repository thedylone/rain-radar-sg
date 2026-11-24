package dev.rainradar.sg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Fetches NEA's 50km "rain areas" radar frames and stacks them the same way
 * www.weather.gov.sg/weather-rain-area-50km/ does:
 *   base-853.png (853x479)  ->  radar frame scaled to 853x479 at 50% opacity
 *   -> SG-Township.png stretched to 853x485 and shifted 3px up.
 */
object Radar {
    val SGT: ZoneId = ZoneId.of("Asia/Singapore")
    const val STEP_MIN = 5L

    const val MAP_W = 853
    const val MAP_H = 479
    private const val LABELS_H = 485
    private const val LABELS_DY = -3
    private const val RADAR_ALPHA = 128 // NEA uses opacity: 0.5

    private const val HOST = "https://www.weather.gov.sg"
    private const val BASE_URL = "$HOST/wp-content/themes/wiptheme/assets/img/base-853.png"
    private const val LABELS_URL = "$HOST/wp-content/themes/wiptheme/images/SG-Township.png"
    private const val FRAME_URL = "$HOST/files/rainarea/50km/v2/dpsri_70km_%s0000dBR.dpsri.png"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    class Frame(val time: ZonedDateTime, val bytes: ByteArray) {
        val bitmap: Bitmap by lazy { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }

    fun clock(t: ZonedDateTime): String = t.format(CLOCK).lowercase(Locale.ENGLISH)

    /** Latest 5-minute slot at or before [t], in Singapore time. */
    fun slotFor(t: ZonedDateTime): ZonedDateTime {
        val z = t.withZoneSameInstant(SGT).truncatedTo(ChronoUnit.MINUTES)
        return z.minusMinutes(z.minute % STEP_MIN)
    }

    /** Returns the body on HTTP 200, null on any other status; throws on network failure. */
    @Throws(IOException::class)
    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "RainRadarSG-Android/1.0")
        try {
            return if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } finally {
            c.disconnect()
        }
    }

    @Throws(IOException::class)
    fun fetch(t: ZonedDateTime): Frame? = get(FRAME_URL.format(t.format(STAMP)))?.let { Frame(t, it) }

    /** Walks back from now until a published frame is found (frames appear a few minutes late). */
    @Throws(IOException::class)
    fun fetchLatest(): Frame? {
        var t = slotFor(ZonedDateTime.now(SGT))
        repeat(12) {
            fetch(t)?.let { return it }
            t = t.minusMinutes(STEP_MIN)
        }
        return null
    }

    // --- static layers, cached on disk ---------------------------------------------------

    private fun cached(ctx: Context, name: String, url: String): Bitmap? {
        val f = File(ctx.filesDir, name)
        val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 24 * 3600 * 1000
        if (stale) {
            try {
                get(url)?.let { bytes -> writeAtomic(f, bytes) }
            } catch (_: IOException) {
                // keep whatever we had
            }
        }
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    fun baseMap(ctx: Context): Bitmap? = cached(ctx, "base-853.png", BASE_URL)
    fun labels(ctx: Context): Bitmap? = cached(ctx, "township.png", LABELS_URL)

    fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) throw IOException("rename failed")
    }

    // --- drawing ---------------------------------------------------------------------------

    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val radarPaint = Paint().apply { alpha = RADAR_ALPHA } // nearest-neighbour like the site

    /** Draws the NEA stack into [dst], which should have the 853:479 aspect ratio. */
    fun draw(c: Canvas, dst: RectF, base: Bitmap?, radar: Bitmap?, labels: Bitmap?) {
        val s = dst.width() / MAP_W
        if (base != null) c.drawBitmap(base, null, dst, smooth) else c.drawColor(0xFFEDEDED.toInt())
        if (radar != null) c.drawBitmap(radar, null, dst, radarPaint)
        if (labels != null) {
            val top = dst.top + LABELS_DY * s
            c.drawBitmap(labels, null, RectF(dst.left, top, dst.right, top + LABELS_H * s), smooth)
        }
    }

    fun render(base: Bitmap?, radar: Bitmap?, labels: Bitmap?, scale: Float = 1.5f): Bitmap {
        val w = (MAP_W * scale).toInt()
        val h = (MAP_H * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(out), RectF(0f, 0f, w.toFloat(), h.toFloat()), base, radar, labels)
        return out
    }
}
