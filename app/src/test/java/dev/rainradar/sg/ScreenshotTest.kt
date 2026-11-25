package dev.rainradar.sg

import android.view.View
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/**
 * Renders the main screen on the JVM, without an emulator.
 *   ./gradlew recordPaparazziDebug   writes PNGs to app/src/test/snapshots/images
 *   ./gradlew verifyPaparazziDebug   fails if the UI no longer matches them
 * The NEA map layers are downloaded at runtime, so the map shows its grey placeholder here.
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "AppTheme")

    @Test
    fun mainLight() = snapMain(NightMode.NOTNIGHT)

    @Test
    fun mainDark() = snapMain(NightMode.NIGHT)

    private fun snapMain(night: NightMode) {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = night))
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        root.findViewById<TextView>(R.id.time).text = "10:20 am · 3 min ago"
        root.findViewById<View>(R.id.progress).visibility = View.GONE
        root.findViewById<RadarView>(R.id.map).apply {
            pin = Radar.toMap(1.2801, 103.8167)
            me = Radar.toMap(1.3521, 103.8198)
            meAccuracy = 4f
        }
        paparazzi.snapshot(root)
    }
}
