package io.github.sowan3k.chargewatch

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * What ChargeWatch does while nothing is charging: no service, no notification, no connection.
 *
 * Two jobs, scheduled with Android's JobScheduler so the system can batch them with other
 * apps' work, skip them without network, and hold them in Doze:
 * - a check about every 15 minutes: fetch what is new, alert if needed, send this phone's
 *   level when it is due, and go live if another phone is charging toward an alert;
 * - a job that runs when this phone starts charging, which goes live at once.
 *
 * "Live" is the foreground service. Starting it from the background needs the battery
 * exemption (battery use set to Unrestricted) or a boot. Without it, the checks still deliver
 * alerts and send the level, just less often.
 */
object Background {
    const val JOB_CHECK = 1
    const val JOB_CHARGING = 2
    private const val CHECK_PERIOD_MS = 15 * 60 * 1000L
    private const val CHECK_FLEX_MS = 5 * 60 * 1000L
    private const val UPDATE_CHECK_MS = 24 * 60 * 60 * 1000L

    /** Makes sure the jobs exist. The charging job is only armed while unplugged. */
    fun schedule(context: Context, pluggedIn: Boolean) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(context, CheckJob::class.java)
        if (js.getPendingJob(JOB_CHECK) == null) {
            js.schedule(
                JobInfo.Builder(JOB_CHECK, component)
                    .setPeriodic(CHECK_PERIOD_MS, CHECK_FLEX_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }
        if (!pluggedIn && js.getPendingJob(JOB_CHARGING) == null) {
            js.schedule(
                JobInfo.Builder(JOB_CHARGING, component)
                    .setRequiresCharging(true)
                    .setPersisted(true)
                    .build()
            )
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.apply {
            cancel(JOB_CHECK)
            cancel(JOB_CHARGING)
        }
    }

    /** Starts the foreground service in live mode. Returns false if Android does not allow it now. */
    fun startLive(context: Context): Boolean = try {
        context.startForegroundService(Intent(context, WatchService::class.java).setAction(WatchService.ACTION_LIVE))
        true
    } catch (e: Exception) {
        false
    }

    /** True when another phone is charging toward an alert and still reporting. */
    fun anotherPhoneCharging(devices: Collection<Device>, selfId: String, nowSec: Long): Boolean =
        devices.any { d -> d.id != selfId && d.charging && d.level < 100 && !Tracker.isStale(d, nowSec) }

    /** One check without the service. Blocking: call it off the main thread. */
    fun check(context: Context) {
        val prefs = Prefs(context)
        val group = prefs.group ?: return
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (raw >= 0 && scale > 0) (raw * 100 / scale).coerceIn(0, 100) else -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        // On power: the live service publishes every step. Nothing else to do here.
        if (plugged && startLive(context)) return

        val tracker = Tracker(prefs.deviceId).apply { restore(prefs.loadDevices()) }
        var lastId = prefs.lastMsgId(group)
        val lines = try {
            Relay.poll(prefs.server, Pairing.topic(group), lastId ?: "12h")
        } catch (e: Exception) {
            null
        }
        val now = System.currentTimeMillis() / 1000
        lines?.forEach { line ->
            val (id, report) = Relay.parse(line) ?: return@forEach
            if (id.isNotEmpty()) lastId = id
            if (report == null) return@forEach
            if (report.left) {
                tracker.onLeft(report.device.id, report.device.time)
            } else {
                tracker.onMessage(report.device, now, prefs.threshold).alert?.let { Notifier.alert(context, report.device, it) }
            }
        }
        if (lines != null) {
            prefs.saveDevices(tracker.devices)
            prefs.setLastMsgId(group, lastId)
        }

        if (level >= 0) publishIfDue(prefs, group, level, plugged)

        if (anotherPhoneCharging(tracker.devices, prefs.deviceId, now) || prefs.alwaysOn) startLive(context)
        schedule(context, plugged)
        checkUpdateIfDue(context)
    }

    /**
     * Sends this phone's level from a check: on a 5 % change (1 % on power), a change of
     * charging state, or when the heartbeat would fall due before the next check.
     */
    private fun publishIfDue(prefs: Prefs, group: String, level: Int, plugged: Boolean) {
        val nowMs = System.currentTimeMillis()
        val heartbeatMs = if (plugged) WatchService.HEARTBEAT_CHARGING_MS else WatchService.HEARTBEAT_BATTERY_MS
        val step = if (plugged) 1 else 5
        val due = prefs.sentCharging != plugged ||
            prefs.sentLevel < 0 ||
            abs(level - prefs.sentLevel) >= step ||
            nowMs - prefs.sentAt >= heartbeatMs - CHECK_PERIOD_MS
        if (!due) return
        val body = Relay.report(prefs.deviceId, prefs.name, level, plugged, (heartbeatMs / 1000).toInt())
        try {
            Relay.publish(prefs.server, Pairing.topic(group), body)
            prefs.markSent(level, plugged, nowMs)
        } catch (e: Exception) {
            // The next check tries again.
        }
    }

    /** Once a day, look for a new release. Notifies once per version. Blocking. */
    fun checkUpdateIfDue(context: Context): UpdateInfo? {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < UPDATE_CHECK_MS) return null
        prefs.lastUpdateCheck = now
        val info = runCatching { Updater.fetch() }.getOrNull() ?: return null
        prefs.cachedUpdate = Updater.toJson(info)
        if (info.isNewer && prefs.notifiedVersion < info.versionCode) {
            prefs.notifiedVersion = info.versionCode
            Notifier.update(context, info)
        }
        return info
    }
}

/** Runs [Background.check], or hands it to the service when the service is already running. */
class CheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val hooks = Hub.service
        if (hooks != null) {
            hooks.checkNow()
            return false
        }
        Thread {
            try {
                Background.check(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
