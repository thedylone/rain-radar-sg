package dev.rainradar.sg

import android.content.Context

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("radar", Context.MODE_PRIVATE)

    fun showLabels(ctx: Context) = p(ctx).getBoolean("labels", true)
    fun setShowLabels(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("labels", v).apply()

    /** Show the live location dot on the app map. */
    fun showLocation(ctx: Context) = p(ctx).getBoolean("location", false)
    fun setShowLocation(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("location", v).apply()

    /** Saved pin as (lat, lon), or null. */
    fun pin(ctx: Context): Pair<Double, Double>? {
        val p = p(ctx)
        if (!p.contains("pinLat")) return null
        return Double.fromBits(p.getLong("pinLat", 0)) to Double.fromBits(p.getLong("pinLon", 0))
    }

    fun setPin(ctx: Context, v: Pair<Double, Double>?) {
        val e = p(ctx).edit()
        if (v == null) e.remove("pinLat").remove("pinLon")
        else e.putLong("pinLat", v.first.toRawBits()).putLong("pinLon", v.second.toRawBits())
        e.apply()
    }

    fun pinOnWidget(ctx: Context) = p(ctx).getBoolean("pinWidget", true)
    fun setPinOnWidget(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("pinWidget", v).apply()

    /** Widget refresh interval in minutes. */
    fun interval(ctx: Context) = p(ctx).getInt("interval", 10)
    fun setInterval(ctx: Context, v: Int) = p(ctx).edit().putInt("interval", v).apply()

    /** Epoch millis of the radar frame currently shown on the widget (0 = none). */
    fun frameTime(ctx: Context) = p(ctx).getLong("frameTime", 0L)
    fun lastFailed(ctx: Context) = p(ctx).getBoolean("failed", false)
    fun lastAttempt(ctx: Context) = p(ctx).getLong("attempt", 0L)

    fun recordAttempt(ctx: Context, frameTime: Long?, failed: Boolean) {
        val e = p(ctx).edit().putBoolean("failed", failed).putLong("attempt", System.currentTimeMillis())
        if (frameTime != null) e.putLong("frameTime", frameTime)
        e.apply()
    }
}
