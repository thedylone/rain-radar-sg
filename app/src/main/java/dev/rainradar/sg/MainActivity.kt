package dev.rainradar.sg

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val io = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var map: RadarView
    private lateinit var time: android.widget.TextView
    private lateinit var start: android.widget.TextView
    private lateinit var widgetStatus: android.widget.TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var seek: Slider
    private lateinit var play: MaterialButton

    private var frames: List<Radar.Frame> = emptyList()
    private var labelsBmp: Bitmap? = null
    private var playing = false
    private var loading = false

    private val tick = object : Runnable {
        override fun run() {
            if (!playing || frames.size < 2) return
            val next = (seek.value.toInt() + 1) % frames.size
            seek.value = next.toFloat()
            main.postDelayed(this, if (next == frames.size - 1) 1500 else 350)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        map = findViewById(R.id.map)
        time = findViewById(R.id.time)
        start = findViewById(R.id.start)
        widgetStatus = findViewById(R.id.widget_status)
        progress = findViewById(R.id.progress)
        seek = findViewById(R.id.seek)
        play = findViewById(R.id.play)

        // Edge-to-edge: keep the app bar below the status bar and content above the nav bar.
        val appbar = findViewById<AppBarLayout>(R.id.appbar)
        val content = findViewById<View>(R.id.content)
        val contentBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            appbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            content.updatePadding(bottom = contentBottom + bars.bottom)
            findViewById<View>(R.id.scroll).updatePadding(left = bars.left, right = bars.right)
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener {
            if (it.itemId == R.id.action_refresh) { load(); true } else false
        }

        seek.setLabelFormatter { v -> frames.getOrNull(v.toInt())?.let { Radar.clock(it.time) } ?: "" }
        seek.addOnChangeListener { _, v, fromUser ->
            if (fromUser) setPlaying(false)
            show(v.toInt())
        }
        play.setOnClickListener { setPlaying(!playing) }

        // Colour scale sampled from NEA's radar frames (light -> heavy), shown the way it looks
        // on the map: 50% opacity over the white land, so it reads the same in dark mode.
        val r = 6 * resources.displayMetrics.density
        findViewById<View>(R.id.legend).background = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = r },
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                longArrayOf(0xFF00FFFF, 0xFF00B729, 0xFF00FF00, 0xFFFFFF00, 0xFFFF9900, 0xFFC10000, 0xFFFF00FF)
                    .map { it.toInt() }.toIntArray()
            ).apply { cornerRadius = r; alpha = 128 },
        ))

        val labelsSwitch = findViewById<MaterialSwitch>(R.id.labels)
        labelsSwitch.isChecked = Prefs.showLabels(this)
        labelsSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setShowLabels(this, on)
            map.labels = if (on) labelsBmp else null
            io.execute { Updater.rerender(applicationContext) }
        }

        val group = findViewById<MaterialButtonToggleGroup>(R.id.interval)
        val byId = mapOf(R.id.i5 to 5, R.id.i10 to 10, R.id.i15 to 15, R.id.i30 to 30)
        byId.entries.firstOrNull { it.value == Prefs.interval(this) }?.let { group.check(it.key) }
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            Prefs.setInterval(this, byId[id] ?: 10)
            if (Widgets.ids(this).isNotEmpty()) Scheduler.ensure(this, reset = true)
        }

        val mgr = AppWidgetManager.getInstance(this)
        val pin = findViewById<MaterialButton>(R.id.pin)
        pin.visibility = if (mgr.isRequestPinAppWidgetSupported) View.VISIBLE else View.GONE
        pin.setOnClickListener {
            mgr.requestPinAppWidget(ComponentName(this, RadarWidgetProvider::class.java), null, null)
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        updateWidgetStatus()
    }

    override fun onPause() {
        super.onPause()
        setPlaying(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        play.setIconResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
        main.removeCallbacks(tick)
        if (p) {
            // Restart from the oldest frame when playing from the end.
            if (seek.value.toInt() == frames.size - 1) seek.value = 0f
            main.postDelayed(tick, 350)
        }
    }

    private fun show(i: Int) {
        val f = frames.getOrNull(i) ?: return
        map.radar = f.bitmap
        time.text = getString(R.string.time_ago, Radar.clock(f.time), ago(f.time))
    }

    private fun ago(t: ZonedDateTime): String {
        val min = Duration.between(t.toInstant(), Instant.now()).toMinutes()
        return if (min < 1) getString(R.string.just_now) else getString(R.string.min_ago, min)
    }

    private fun updateWidgetStatus() {
        val t = Prefs.frameTime(this)
        widgetStatus.text = when {
            Widgets.ids(this).isEmpty() -> getString(R.string.widget_none)
            t == 0L -> getString(R.string.loading)
            else -> getString(R.string.widget_status, Radar.clock(Instant.ofEpochMilli(t).atZone(Radar.SGT)))
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        setPlaying(false)
        progress.show()
        if (frames.isEmpty()) time.setText(R.string.loading)
        val ctx = applicationContext
        io.execute {
            val base = Radar.baseMap(ctx)
            val labels = Radar.labels(ctx)
            main.post {
                map.base = base
                labelsBmp = labels
                map.labels = if (Prefs.showLabels(ctx)) labels else null
            }
            val latest = try { Radar.fetchLatest() } catch (e: IOException) { null }
            if (latest == null) {
                main.post { done(); failed() }
                return@execute
            }
            Updater.store(ctx, latest) // keep the widget in sync with what we show here
            // Past hour, fetched in parallel.
            val past = (11 downTo 1).map { k ->
                io.submit<Radar.Frame?> {
                    try { Radar.fetch(latest.time.minusMinutes(k * Radar.STEP_MIN)) } catch (e: IOException) { null }
                }
            }.mapNotNull { runCatching { it.get() }.getOrNull() }
            val all = past + latest
            all.forEach { it.bitmap } // decode off the main thread
            main.post {
                done()
                frames = all
                val last = all.size - 1
                seek.isEnabled = last > 0
                play.isEnabled = last > 0
                seek.valueTo = maxOf(last, 1).toFloat()
                seek.value = last.toFloat()
                start.text = Radar.clock(all.first().time)
                show(last)
                updateWidgetStatus()
            }
        }
    }

    private fun done() {
        loading = false
        progress.hide()
    }

    private fun failed() {
        if (frames.isEmpty()) time.setText(R.string.offline)
        Snackbar.make(root, R.string.offline_long, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.retry) { load() }
            .show()
    }
}
