package dev.rainradar.sg

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var map: RadarView
    private lateinit var time: TextView
    private lateinit var seek: SeekBar
    private lateinit var play: ImageButton

    private var frames: List<Radar.Frame> = emptyList()
    private var labelsBmp: android.graphics.Bitmap? = null
    private var playing = false

    private val tick = object : Runnable {
        override fun run() {
            if (!playing || frames.isEmpty()) return
            val next = (seek.progress + 1) % frames.size
            seek.progress = next
            main.postDelayed(this, if (next == frames.size - 1) 1500 else 350)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        time = findViewById(R.id.time)
        seek = findViewById(R.id.seek)
        play = findViewById(R.id.play)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = show(p)
            override fun onStartTrackingTouch(s: SeekBar) = setPlaying(false)
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        play.setOnClickListener { setPlaying(!playing) }
        // Colour scale sampled from NEA's radar frames (light -> heavy).
        findViewById<View>(R.id.legend).background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF00FFFF.toInt(), 0xFF00B729.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
                0xFFFF9900.toInt(), 0xFFC10000.toInt(), 0xFFFF00FF.toInt())
        ).apply { cornerRadius = 8f }
        findViewById<ImageButton>(R.id.reload).setOnClickListener { load() }

        val labelsSwitch = findViewById<Switch>(R.id.labels)
        labelsSwitch.isChecked = Prefs.showLabels(this)
        labelsSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setShowLabels(this, on)
            map.labels = if (on) labelsBmp else null
            io.execute { Updater.rerender(applicationContext) }
        }

        val group = findViewById<RadioGroup>(R.id.interval)
        val byId = mapOf(R.id.i5 to 5, R.id.i10 to 10, R.id.i15 to 15, R.id.i30 to 30)
        byId.entries.firstOrNull { it.value == Prefs.interval(this) }?.let { group.check(it.key) }
        group.setOnCheckedChangeListener { _, id ->
            Prefs.setInterval(this, byId[id] ?: 10)
            if (Widgets.ids(this).isNotEmpty()) Scheduler.ensure(this, reset = true)
        }

        val mgr = AppWidgetManager.getInstance(this)
        val pin = findViewById<Button>(R.id.pin)
        pin.visibility = if (mgr.isRequestPinAppWidgetSupported) View.VISIBLE else View.GONE
        pin.setOnClickListener {
            mgr.requestPinAppWidget(ComponentName(this, RadarWidgetProvider::class.java), null, null)
        }

        load()
    }

    override fun onPause() {
        super.onPause()
        setPlaying(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        play.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
        main.removeCallbacks(tick)
        if (p) main.post(tick)
    }

    private fun show(i: Int) {
        val f = frames.getOrNull(i) ?: return
        map.radar = f.bitmap
        val latest = i == frames.size - 1
        time.text = getString(if (latest) R.string.time_latest else R.string.time_past, Radar.clock(f.time))
    }

    private fun load() {
        time.setText(R.string.loading)
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
                main.post { time.setText(R.string.offline_long) }
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
                frames = all
                seek.max = all.size - 1
                seek.progress = all.size - 1
                show(all.size - 1)
            }
        }
    }
}
