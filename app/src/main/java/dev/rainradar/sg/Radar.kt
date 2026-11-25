package dev.rainradar.sg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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

    // Geographic bounds of base-853.png (linear lat/lon <-> pixels). weather.gov.sg's own
    // calculatePosition() uses Singapore's bounding box (1.4572..1.1450, 103.565..104.130), which
    // puts points ~1 km off; these were fitted to the drawn coastlines of Jurong Island and
    // Pulau Ubin and checked against Pulau Bukom, Sentosa, Woodlands and Tuas checkpoints.
    private const val LAT_TOP = 1.47736
    private const val LAT_BOTTOM = 1.15393
    private const val LON_LEFT = 103.55298
    private const val LON_RIGHT = 104.13499
    /** Ground distance covered by one map pixel. */
    const val METERS_PER_PX = (LAT_TOP - LAT_BOTTOM) * 111_320 / MAP_H

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

    // --- geography -------------------------------------------------------------------------

    fun onMap(lat: Double, lon: Double) = lat in LAT_BOTTOM..LAT_TOP && lon in LON_LEFT..LON_RIGHT

    /** Lat/lon to map pixels (0..853, 0..479). */
    fun toMap(lat: Double, lon: Double) = PointF(
        ((lon - LON_LEFT) / (LON_RIGHT - LON_LEFT) * MAP_W).toFloat(),
        ((LAT_TOP - lat) / (LAT_TOP - LAT_BOTTOM) * MAP_H).toFloat(),
    )

    /** Map pixels to lat/lon. */
    fun toGeo(p: PointF): Pair<Double, Double> =
        LAT_TOP - p.y / MAP_H * (LAT_TOP - LAT_BOTTOM) to LON_LEFT + p.x / MAP_W * (LON_RIGHT - LON_LEFT)

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

    /** Renders the map stack, with an optional saved [pin] (in map pixels). */
    fun render(base: Bitmap?, radar: Bitmap?, labels: Bitmap?, pin: PointF? = null, scale: Float = 1.5f): Bitmap {
        val w = (MAP_W * scale).toInt()
        val h = (MAP_H * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        draw(c, RectF(0f, 0f, w.toFloat(), h.toFloat()), base, radar, labels)
        if (pin != null) Markers().pin(c, pin.x * scale, pin.y * scale, h * 0.1f)
        return out
    }
}

/** Location markers. Not thread-safe: give each drawing thread its own instance. */
class Markers {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val tip = Path()

    /** Teardrop pin of height [h] whose point sits at ([x], [y]). */
    fun pin(c: Canvas, x: Float, y: Float, h: Float) {
        val r = h * 0.34f
        val cy = y - h + r
        path.reset(); tip.reset()
        path.addCircle(x, cy, r, Path.Direction.CW)
        // Tangent points from the tip to the head circle.
        val d = h - r
        val a = Math.asin((r / d).toDouble())
        val tx = (r * Math.cos(a)).toFloat()
        val ty = (r * Math.sin(a)).toFloat()
        tip.moveTo(x, y)
        tip.lineTo(x - tx, cy + ty)
        tip.lineTo(x + tx, cy + ty)
        tip.close()
        path.op(tip, Path.Op.UNION)

        fill.color = 0x40000000
        c.save(); c.translate(0f, h * 0.03f); c.drawPath(path, fill); c.restore() // soft shadow
        fill.color = PIN
        c.drawPath(path, fill)
        stroke.strokeWidth = h * 0.06f
        c.drawPath(path, stroke)
        fill.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, cy, r * 0.38f, fill)
    }

    /** Live-location dot of radius [r], with an accuracy halo of radius [accuracy]. */
    fun dot(c: Canvas, x: Float, y: Float, r: Float, accuracy: Float) {
        if (accuracy > r * 1.5f) {
            fill.color = 0x302A7DE1
            c.drawCircle(x, y, accuracy, fill)
        }
        fill.color = 0x50000000
        c.drawCircle(x, y + r * 0.15f, r * 1.45f, fill)
        fill.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, r * 1.4f, fill)
        fill.color = ME
        c.drawCircle(x, y, r, fill)
    }

    private companion object {
        const val PIN = 0xFFE53935.toInt()
        const val ME = 0xFF2A7DE1.toInt()
    }
}
