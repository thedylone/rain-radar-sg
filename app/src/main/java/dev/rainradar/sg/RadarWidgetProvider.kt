package dev.rainradar.sg

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.io.File
import java.time.Instant

class RadarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, Widgets.build(ctx))
        Scheduler.ensure(ctx)
        // Throttle: onUpdate can fire often (placement, resize, launcher restarts).
        if (System.currentTimeMillis() - Prefs.lastAttempt(ctx) > 2 * 60_000) Scheduler.refreshNow(ctx)
    }

    override fun onEnabled(ctx: Context) = Scheduler.ensure(ctx)

    override fun onDisabled(ctx: Context) = Scheduler.cancelAll(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            Widgets.pushAll(ctx, updating = true)
            Scheduler.refreshNow(ctx)
        } else {
            super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "dev.rainradar.sg.REFRESH"
    }
}

object Widgets {
    fun ids(ctx: Context): IntArray = AppWidgetManager.getInstance(ctx)
        .getAppWidgetIds(ComponentName(ctx, RadarWidgetProvider::class.java))

    fun pushAll(ctx: Context, updating: Boolean = false) {
        val ids = ids(ctx)
        if (ids.isNotEmpty()) AppWidgetManager.getInstance(ctx).updateAppWidget(ids, build(ctx, updating))
    }

    fun build(ctx: Context, updating: Boolean = false): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_radar)
        val img = File(ctx.filesDir, Updater.WIDGET_IMG)
        if (img.exists()) {
            // A new query string per render forces the launcher to reload the image.
            v.setImageViewUri(R.id.map, RadarImageProvider.uri(ctx, img.lastModified()))
        }
        val t = Prefs.frameTime(ctx)
        val label = when {
            updating -> ctx.getString(R.string.updating)
            t == 0L -> if (Prefs.lastFailed(ctx)) ctx.getString(R.string.offline) else ctx.getString(R.string.loading)
            else -> Radar.clock(Instant.ofEpochMilli(t).atZone(Radar.SGT)) +
                if (Prefs.lastFailed(ctx)) " ⚠" else ""
        }
        v.setTextViewText(R.id.time, label)

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val refresh = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, RadarWidgetProvider::class.java).setAction(RadarWidgetProvider.ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        v.setOnClickPendingIntent(R.id.map, open)
        v.setOnClickPendingIntent(R.id.refresh, refresh)
        return v
    }
}
