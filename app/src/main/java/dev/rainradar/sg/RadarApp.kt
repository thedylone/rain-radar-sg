package dev.rainradar.sg

import android.app.Application
import com.google.android.material.color.DynamicColors

class RadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: take colours from the wallpaper on Android 12+.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
