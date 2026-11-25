package dev.rainradar.sg

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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

    // --- location ---
    private lateinit var lm: LocationManager
    private lateinit var locate: MaterialButton
    private lateinit var liveSwitch: MaterialSwitch
    private lateinit var pinStatus: android.widget.TextView
    private lateinit var pinRemove: MaterialButton
    private var fix: Location? = null
    private var listening = false
    private var centred = false // zoomed to the first fix since live location was turned on
    private var warnedOutside = false
    private val listener = LocationListener { onFix(it) }
    private var afterPermission: ((Boolean) -> Unit)? = null
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val cb = afterPermission
        afterPermission = null
        val ok = res.values.any { it }
        if (!ok) snack(R.string.location_denied)
        cb?.invoke(ok)
    }

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

        // Zoom
        val zoomIn = findViewById<MaterialButton>(R.id.zoom_in)
        val zoomOut = findViewById<MaterialButton>(R.id.zoom_out)
        zoomIn.setOnClickListener { map.zoomIn() }
        zoomOut.setOnClickListener { map.zoomOut() }
        map.onZoomChanged = { z ->
            zoomOut.isEnabled = z > 1.01f
            zoomIn.isEnabled = z < 7.99f
        }

        // Live location + saved pin
        lm = getSystemService(LocationManager::class.java)
        locate = findViewById(R.id.locate)
        liveSwitch = findViewById(R.id.live_location)
        pinStatus = findViewById(R.id.pin_status)
        pinRemove = findViewById(R.id.pin_remove)
        locate.setOnClickListener { setLive(!Prefs.showLocation(this)) }
        liveSwitch.setOnClickListener { setLive(liveSwitch.isChecked) }
        updateLiveUi()

        map.onLongPress = { p -> savePin(Radar.toGeo(p)) }
        findViewById<MaterialButton>(R.id.pin_here).setOnClickListener { pinCurrentLocation() }
        pinRemove.setOnClickListener {
            val old = Prefs.pin(this) ?: return@setOnClickListener
            setPin(null)
            Snackbar.make(root, R.string.pin_removed, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { setPin(old) }
                .show()
        }
        val pinWidget = findViewById<MaterialSwitch>(R.id.pin_widget)
        pinWidget.isChecked = Prefs.pinOnWidget(this)
        pinWidget.setOnCheckedChangeListener { _, on ->
            Prefs.setPinOnWidget(this, on)
            io.execute { Updater.rerender(applicationContext) }
        }
        showPin()

        load()
    }

    override fun onResume() {
        super.onResume()
        updateWidgetStatus()
        if (Prefs.showLocation(this)) {
            if (hasLocationPermission()) startLocation()
            else { Prefs.setShowLocation(this, false); map.me = null; updateLiveUi() } // revoked in Settings
        }
    }

    override fun onPause() {
        super.onPause()
        setPlaying(false)
        stopLocation()
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

    // --- location --------------------------------------------------------------------------

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Runs [action] once location permission is granted (asking if needed). */
    private fun withLocationPermission(action: (Boolean) -> Unit) {
        if (hasLocationPermission()) return action(true)
        afterPermission = action
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun setLive(on: Boolean) {
        if (!on) {
            Prefs.setShowLocation(this, false)
            stopLocation()
            map.me = null
            updateLiveUi()
            return
        }
        withLocationPermission { ok ->
            Prefs.setShowLocation(this, ok)
            updateLiveUi()
            if (ok) {
                centred = false
                warnedOutside = false
                startLocation()
            }
        }
    }

    private fun updateLiveUi() {
        val on = Prefs.showLocation(this)
        liveSwitch.isChecked = on
        locate.setIconResource(if (on) R.drawable.ic_my_location else R.drawable.ic_location_searching)
        locate.contentDescription = getString(if (on) R.string.hide_location else R.string.show_location)
    }

    /** Fused provider when the phone has one (battery-friendly), else GPS + network. */
    private fun providers(): List<String> {
        val all = lm.allProviders
        if (LocationManager.FUSED_PROVIDER in all) return listOf(LocationManager.FUSED_PROVIDER)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (listening || !hasLocationPermission()) return
        if (!lm.isLocationEnabled) { snack(R.string.location_off); return }
        listening = true
        for (p in providers()) {
            lm.getLastKnownLocation(p)?.let { if (ageMs(it) < 10 * 60_000) onFix(it) }
            lm.requestLocationUpdates(p, 5_000L, 5f, listener, Looper.getMainLooper())
        }
    }

    private fun stopLocation() {
        if (!listening) return
        listening = false
        lm.removeUpdates(listener)
    }

    private fun ageMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    private fun onFix(l: Location) {
        val prev = fix
        if (prev != null && l.elapsedRealtimeNanos < prev.elapsedRealtimeNanos) return
        fix = l
        if (!Prefs.showLocation(this)) return
        if (!Radar.onMap(l.latitude, l.longitude)) {
            map.me = null
            if (!warnedOutside) { warnedOutside = true; snack(R.string.outside_map) }
            return
        }
        val p = Radar.toMap(l.latitude, l.longitude)
        map.meAccuracy = if (l.hasAccuracy()) (l.accuracy / Radar.METERS_PER_PX).toFloat() else 0f
        map.me = p
        if (!centred) { centred = true; map.focus(p) }
    }

    @SuppressLint("MissingPermission")
    private fun pinCurrentLocation() = withLocationPermission { ok ->
        if (!ok) return@withLocationPermission
        fix?.takeIf { ageMs(it) < 2 * 60_000 }?.let { return@withLocationPermission savePin(it.latitude to it.longitude) }
        if (!lm.isLocationEnabled) return@withLocationPermission snack(R.string.location_off)
        val provider = providers().firstOrNull() ?: return@withLocationPermission snack(R.string.no_fix)
        val waiting = Snackbar.make(root, R.string.locating, Snackbar.LENGTH_INDEFINITE).apply { show() }
        lm.getCurrentLocation(provider, null, mainExecutor) { l ->
            waiting.dismiss()
            if (l == null) snack(R.string.no_fix) else { onFix(l); savePin(l.latitude to l.longitude) }
        }
    }

    private fun savePin(p: Pair<Double, Double>) {
        if (!Radar.onMap(p.first, p.second)) return snack(R.string.outside_map)
        val old = Prefs.pin(this)
        setPin(p)
        Snackbar.make(root, R.string.pin_saved, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { setPin(old) }
            .show()
    }

    private fun setPin(p: Pair<Double, Double>?) {
        Prefs.setPin(this, p)
        showPin()
        io.execute { Updater.rerender(applicationContext) }
    }

    private fun showPin() {
        val p = Prefs.pin(this)
        map.pin = p?.let { Radar.toMap(it.first, it.second) }
        pinStatus.text = if (p == null) getString(R.string.pin_none) else getString(R.string.pin_set, p.first, p.second)
        pinRemove.visibility = if (p == null) View.GONE else View.VISIBLE
    }

    private fun snack(res: Int) = Snackbar.make(root, res, Snackbar.LENGTH_LONG).show()

    private fun failed() {
        if (frames.isEmpty()) time.setText(R.string.offline)
        Snackbar.make(root, R.string.offline_long, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.retry) { load() }
            .show()
    }
}
