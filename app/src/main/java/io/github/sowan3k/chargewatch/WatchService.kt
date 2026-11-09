package io.github.sowan3k.chargewatch

import android.app.AlarmManager
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * The live part of ChargeWatch. It only runs while it is needed:
 *
 * - while a phone is charging (this one, or another one heading for an alert): foreground,
 *   with a "… is charging" notification and a streaming connection, so alerts arrive within
 *   a second;
 * - while the app is open: running quietly in the background of the open app, with no
 *   notification, so the screen updates live;
 * - when "Always show" is on: foreground all the time, in low-power mode while nothing charges.
 *
 * The rest of the time it stops, and [Background] jobs take over: a check about every 15
 * minutes and a job that runs when this phone is plugged in. That keeps the notification out of
 * sight and the radio asleep while nothing is charging.
 */
class WatchService : Service() {

    private enum class Mode { OFF, LIVE, LOW_POWER }

    private lateinit var prefs: Prefs
    private lateinit var tracker: Tracker
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<Triple<String, String, String>?>(null)

    private var subscriber: Subscriber? = null
    private var group: String? = null
    private var server: String = Pairing.DEFAULT_SERVER
    private var mode = Mode.OFF
    private var foreground = false
    private var stopping = false
    private var lastStartId = 0
    private var started = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    @Volatile private var lastMsgId: String? = null
    private var checking = false
    private var lastCheckAt = 0L

    // This phone's battery, from the last ACTION_BATTERY_CHANGED.
    private var level = -1
    private var charging = false

    @Volatile private var sendFailed = false
    private var lastStatus: Pair<String, String>? = null

    private val heartbeat = AlarmManager.OnAlarmListener {
        refreshBattery()
        publishNow()
        applyMode() // a phone that stopped reporting while charging no longer keeps us live
        maybeCheckUpdate()
    }

    private val saveRunnable = Runnable {
        val g = group
        if (g != null && prefs.group == g) {
            prefs.saveDevices(tracker.devices)
            prefs.setLastMsgId(g, lastMsgId)
        }
    }

    private val statusRunnable = Runnable { postStatus() }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onBattery(intent)
        }
    }

    /** With "Always show" in low-power mode, turning the screen on triggers a check. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mode == Mode.LOW_POWER && SystemClock.elapsedRealtime() - lastCheckAt > SCREEN_CHECK_GAP_MS) checkNow()
        }
    }

    private val hooks = object : Hub.ServiceHooks {
        override fun reevaluate() = applyMode()
        override fun checkNow() = this@WatchService.checkNow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tracker = Tracker(prefs.deviceId)
        Notifier.createChannels(this)
        Hub.service = hooks
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        stopping = false
        // A start from the background (a job, a boot, a restart) must go foreground at once.
        if (intent == null || intent.action == ACTION_LIVE) {
            if (!goForeground()) {
                stopEverything()
                return START_NOT_STICKY
            }
        }
        val g = prefs.group
        if (g == null) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (g != group || prefs.server != server) {
            switchGroup(g, prefs.server)
        } else {
            when (intent?.action) {
                ACTION_RELOAD -> publishNow() // the name may have changed
                ACTION_FORGET -> intent?.getStringExtra(EXTRA_ID)?.let { id ->
                    if (tracker.remove(id) != null) {
                        scheduleSave()
                        pushToHub()
                    }
                }
            }
            applyMode()
        }
        return if (foreground) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Hub.service === hooks) Hub.service = null
        stopStream()
        if (started) {
            runCatching { unregisterReceiver(batteryReceiver) }
            runCatching { unregisterReceiver(screenReceiver) }
            networkCallback?.let { cb ->
                runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            }
            getSystemService(AlarmManager::class.java)?.cancel(heartbeat)
        }
        main.removeCallbacksAndMessages(null)
        saveRunnable.run()
        if (prefs.group != null) Background.schedule(this, charging)
        // Late callbacks from other threads check this and do nothing.
        group = null
        net.shutdown()
        super.onDestroy()
    }

    // ---- Lifecycle and modes ----

    private fun switchGroup(g: String, s: String) {
        stopStream()
        group = g
        server = s
        mode = Mode.OFF
        lastMsgId = prefs.lastMsgId(g)
        tracker.clear()
        tracker.restore(prefs.loadDevices())
        pushToHub()
        startOnce()
        applyMode()
        if (stopping) return
        if (level >= 0) maybePublish(force = prefs.sentCharging == null)
        if (mode == Mode.LOW_POWER) checkNow()
        maybeCheckUpdate()
    }

    private fun anotherPhoneCharging() =
        Background.anotherPhoneCharging(tracker.devices, prefs.deviceId, System.currentTimeMillis() / 1000)

    private fun applyMode() {
        val g = group ?: return
        if (stopping) return
        val otherCharging = anotherPhoneCharging()
        val needForeground = prefs.alwaysOn || charging || otherCharging
        if (!needForeground && !Hub.uiVisible) {
            stopEverything()
            return
        }
        if (needForeground) {
            if (!foreground && !goForeground()) {
                // Android refused (no battery exemption and the app is not on screen).
                stopEverything()
                return
            }
        } else if (foreground) {
            // Only the open screen needs us: drop the notification, keep running quietly.
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            lastStatus = null
        }
        if (charging || otherCharging || Hub.uiVisible) {
            if (subscriber == null) startStream(g)
            mode = Mode.LIVE
        } else {
            stopStream()
            if (mode != Mode.LOW_POWER) Hub.setStatus("Low-power mode", true)
            mode = Mode.LOW_POWER
        }
        scheduleStatus()
    }

    /** Stops the service. The background jobs keep watching (see onDestroy). */
    private fun stopEverything() {
        if (stopping) return
        stopping = true
        stopStream()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        Hub.setStatus("", false)
        // With the id, a start request that arrived meanwhile keeps the service alive.
        stopSelf(lastStartId)
    }

    private fun startStream(g: String) {
        subscriber = Subscriber(
            server = server,
            topic = Pairing.topic(g),
            since = { lastMsgId ?: "12h" },
            online = ::isOnline,
            onMessage = { id, report ->
                main.post {
                    if (g != group) return@post
                    if (id.isNotEmpty()) lastMsgId = id
                    report?.let { onReport(it, g) }
                }
            },
            onState = { st -> main.post { onSubscriberState(st, g) } },
        ).also { it.start() }
    }

    private fun stopStream() {
        subscriber?.stop()
        subscriber = null
    }

    /** One check (used by the 15-minute job and, in low-power mode, by screen-on). */
    private fun checkNow() {
        val g = group ?: return
        if (checking) return
        if (mode == Mode.LIVE) return // the stream already delivers everything
        checking = true
        lastCheckAt = SystemClock.elapsedRealtime()
        maybePublish()
        val srv = server
        val since = lastMsgId ?: "12h"
        runOnNetwork {
            val lines = try {
                Relay.poll(srv, Pairing.topic(g), since)
            } catch (e: Exception) {
                null
            }
            main.post {
                checking = false
                if (g != group) return@post
                lines?.forEach { line ->
                    val parsed = Relay.parse(line) ?: return@forEach
                    if (parsed.first.isNotEmpty()) lastMsgId = parsed.first
                    parsed.second?.let { onReport(it, g) }
                }
                if (lines != null) scheduleSave()
                applyMode()
            }
        }
    }

    private fun maybeCheckUpdate() {
        runOnNetwork {
            val info = Background.checkUpdateIfDue(this) ?: return@runOnNetwork
            main.post { Hub.setUpdate(info = info) }
        }
    }

    private fun startOnce() {
        if (started) return
        started = true
        // Battery and screen changes are protected system broadcasts, so no export flag is needed.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { onBattery(it) }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        registerNetworkCallback()
    }

    private fun isOnline(): Boolean =
        getSystemService(ConnectivityManager::class.java)?.activeNetwork != null

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    val previous = lastNetwork
                    lastNetwork = network
                    // A new network (Wi-Fi to mobile, for example) leaves the old socket hanging.
                    if (previous != null && previous != network) subscriber?.kick() else subscriber?.retryNow()
                    if (sendFailed) publishNow()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            // Not fatal: the subscriber's safety-net timeout still retries.
        }
    }

    private fun onReport(r: Report, g: String) {
        if (g != group) return
        val d = r.device
        if (r.left) {
            if (tracker.onLeft(d.id, d.time)) {
                scheduleSave()
                pushToHub()
                applyMode()
            }
            return
        }
        val wasCharging = anotherPhoneCharging()
        val update = tracker.onMessage(d, System.currentTimeMillis() / 1000, prefs.threshold)
        if (!update.changed) return
        val alert = update.alert
        if (alert != null) {
            Notifier.alert(this, d, alert)
            // Save at once so a crash or restart cannot fire the same alert again.
            main.removeCallbacks(saveRunnable)
            saveRunnable.run()
        } else {
            scheduleSave()
        }
        pushToHub()
        if (anotherPhoneCharging() != wasCharging) applyMode()
    }

    private fun onSubscriberState(st: Subscriber.State, g: String) {
        if (g != group || mode != Mode.LIVE) return
        val host = Uri.parse(server).host ?: server
        when (st) {
            Subscriber.State.Connecting -> Hub.setStatus("Connecting to $host…", false)
            Subscriber.State.Connected -> {
                Hub.setStatus("Connected to $host", true)
                if (sendFailed) publishNow()
            }
            Subscriber.State.Offline -> Hub.setStatus("No network. Waiting for a connection", false)
            is Subscriber.State.Waiting -> Hub.setStatus("${st.reason}. Retrying in ${st.seconds} s", false)
        }
        scheduleStatus()
    }

    private fun pushToHub() {
        val selfId = prefs.deviceId
        Hub.setOthers(tracker.devices.filter { it.id != selfId }.sortedBy { it.name.lowercase() })
        scheduleStatus()
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 3_000)
    }

    private fun scheduleStatus() {
        main.removeCallbacks(statusRunnable)
        main.postDelayed(statusRunnable, 1_000)
    }

    // ---- Battery and publishing ----

    private fun heartbeatMs() = if (charging) HEARTBEAT_CHARGING_MS else HEARTBEAT_BATTERY_MS

    private fun refreshBattery() {
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { onBattery(it) }
    }

    private fun onBattery(intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return
        level = (raw * 100 / scale).coerceIn(0, 100)
        // "Charging" means connected to power. A phone that sits at 100 % on the charger
        // still counts, which keeps the full state stable.
        val wasCharging = charging
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

        val name = prefs.name
        val old = Hub.self
        if (old == null || old.level != level || old.charging != charging || old.name != name) {
            Hub.setSelf(Device(prefs.deviceId, name, level, charging, System.currentTimeMillis() / 1000))
        }
        if (group == null) return
        maybePublish()
        if (charging != wasCharging) {
            applyMode()
            scheduleStatus()
        }
    }

    /**
     * Keeps the message count low (the public relay has a daily limit per IP):
     * every 1 % while charging above 50 %, where alerts happen, and every 5 % otherwise.
     * A heartbeat covers phones whose level does not move, such as one sitting at 100 %.
     * The last sent state is stored, so the background check continues where this left off.
     */
    private fun maybePublish(force: Boolean = false) {
        if (group == null || level < 0 || stopping) return
        val now = System.currentTimeMillis()
        val step = if (charging && level >= FINE_FROM) 1 else COARSE_STEP
        val due = force ||
            prefs.sentCharging != charging ||
            prefs.sentLevel < 0 ||
            abs(level - prefs.sentLevel) >= step ||
            now - prefs.sentAt >= heartbeatMs() ||
            (sendFailed && now - prefs.sentAt >= RETRY_MS)
        if (due) publishNow()
    }

    private fun publishNow() {
        val g = group ?: return
        if (level < 0) return
        prefs.markSent(level, charging, System.currentTimeMillis())
        sendFailed = false
        val hbSec = (heartbeatMs() / 1000).toInt()
        send(server, Pairing.topic(g), Relay.report(prefs.deviceId, prefs.name, level, charging, hbSec))
        armHeartbeat()
    }

    /**
     * Runs network work on the single network thread. The wakelock is taken here, before the
     * hand-off, so the phone cannot fall asleep in between.
     */
    private fun runOnNetwork(task: () -> Unit) {
        val wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeWatch:net")
        wakeLock?.acquire(45_000)
        try {
            net.execute {
                try {
                    task()
                } finally {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
            }
        } catch (e: Exception) {
            // The executor is shut down: the service is stopping.
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }

    /** Sends only the newest report. A queued older one is dropped. */
    private fun send(server: String, topic: String, body: String) {
        pending.set(Triple(server, topic, body))
        runOnNetwork {
            val job = pending.getAndSet(null) ?: return@runOnNetwork
            var ok = false
            for (attempt in 1..3) {
                try {
                    Relay.publish(job.first, job.second, job.third)
                    ok = true
                    break
                } catch (e: Relay.HttpError) {
                    if (e.code in 400..499) break // rate limit or bad request: retrying now will not help
                } catch (e: IOException) {
                    // Network trouble.
                }
                if (pending.get() != null || !isOnline()) break // newer report waiting, or no network at all
                Thread.sleep(3_000L * attempt)
            }
            if (!ok) sendFailed = true
        }
    }

    private fun armHeartbeat() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        am.cancel(heartbeat)
        // An inexact wakeup alarm: no special permission, batched by the system, deferred in Doze.
        am.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + heartbeatMs(),
            "chargewatch:heartbeat",
            heartbeat,
            main,
        )
    }

    // ---- The status notification ----

    /** Returns false if Android refuses a foreground start right now. */
    private fun goForeground(): Boolean {
        val texts = statusTexts()
        val n = statusNotification(texts)
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.NOTIF_STATUS, n)
            }
            foreground = true
            lastStatus = texts
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Updates the status notification only when its text changes, and only while it is shown. */
    private fun postStatus() {
        if (!foreground) return
        val texts = statusTexts()
        if (texts == lastStatus) return
        lastStatus = texts
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(Notifier.NOTIF_STATUS, statusNotification(texts))
    }

    private fun statusTexts(): Pair<String, String> {
        val now = System.currentTimeMillis() / 1000
        val others = Hub.others
        val chargingOthers = others.filter { it.charging && it.level < 100 && !Tracker.isStale(it, now) }
        val threshold = prefs.threshold
        val link = if (mode == Mode.LIVE && !Hub.connected && Hub.status.isNotEmpty()) Hub.status else null
        return when {
            chargingOthers.isNotEmpty() -> {
                val d = chargingOthers.first()
                val more = if (chargingOthers.size > 1) " (+${chargingOthers.size - 1} more)" else ""
                val plan = if (threshold in 1..99 && d.level < threshold) "Alerts at $threshold% and when full" else "Alert when full"
                "${d.name} is charging · ${d.level}%$more" to (link ?: plan)
            }
            charging && level >= 0 -> "This phone is charging · $level%" to (link ?: "Sharing its battery with your group")
            others.isEmpty() -> "ChargeWatch" to "Waiting for your other phones"
            else -> {
                val summary = others.joinToString(" · ") { "${it.name} ${it.level}%" }
                val minutes = 15
                summary to (link ?: if (mode == Mode.LOW_POWER) "Low-power mode · checks about every $minutes min" else "Watching ${others.size} phone" + if (others.size == 1) "" else "s")
            }
        }
    }

    private fun statusNotification(texts: Pair<String, String>): Notification =
        Notification.Builder(this, Notifier.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(Notifier.BRAND)
            .setContentTitle(texts.first)
            .setContentText(texts.second)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(Notifier.openApp(this))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()

    companion object {
        const val ACTION_START = "io.github.sowan3k.chargewatch.START"
        const val ACTION_LIVE = "io.github.sowan3k.chargewatch.LIVE"
        const val ACTION_RELOAD = "io.github.sowan3k.chargewatch.RELOAD"
        const val ACTION_FORGET = "io.github.sowan3k.chargewatch.FORGET"
        private const val EXTRA_ID = "id"

        const val HEARTBEAT_CHARGING_MS = 30 * 60 * 1000L
        const val HEARTBEAT_BATTERY_MS = 60 * 60 * 1000L
        private const val SCREEN_CHECK_GAP_MS = 2 * 60 * 1000L
        private const val RETRY_MS = 30 * 1000L
        private const val FINE_FROM = 50
        private const val COARSE_STEP = 5

        /** From the open app. A plain start: the service decides whether it needs a notification. */
        fun start(context: Context, reload: Boolean = false) {
            val i = Intent(context, WatchService::class.java)
                .setAction(if (reload) ACTION_RELOAD else ACTION_START)
            try {
                context.startService(i)
            } catch (e: Exception) {
                Background.startLive(context)
            }
        }

        /** Removes a phone from the list until it reports again. */
        fun forget(context: Context, id: String) {
            val i = Intent(context, WatchService::class.java).setAction(ACTION_FORGET).putExtra(EXTRA_ID, id)
            runCatching { context.startService(i) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
