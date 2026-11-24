package dev.rainradar.sg

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class RadarWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Updater.refresh(applicationContext)
        // The "chain" job re-schedules itself so refreshes can run more often than
        // WorkManager's 15-minute periodic minimum.
        if (inputData.getBoolean(KEY_CHAIN, false)) {
            Scheduler.scheduleNext(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success() // never fail: a failed job would break the chain
    }

    companion object {
        const val KEY_CHAIN = "chain"
    }
}

object Scheduler {
    private const val PERIODIC = "radar_periodic"
    private const val CHAIN = "radar_chain"
    private const val NOW = "radar_now"

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Makes sure background refreshes are scheduled. [reset] applies a new interval. */
    fun ensure(ctx: Context, reset: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        // Safety net in case the chain is ever lost (e.g. after a force-stop).
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RadarWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
        )
        scheduleNext(ctx, if (reset) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    fun scheduleNext(ctx: Context, policy: ExistingWorkPolicy) {
        val req = OneTimeWorkRequestBuilder<RadarWorker>()
            .setInitialDelay(Prefs.interval(ctx).toLong(), TimeUnit.MINUTES)
            .setConstraints(net)
            .setInputData(workDataOf(RadarWorker.KEY_CHAIN to true))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(CHAIN, policy, req)
    }

    fun refreshNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RadarWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(net)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(CHAIN)
        wm.cancelUniqueWork(NOW)
    }
}
