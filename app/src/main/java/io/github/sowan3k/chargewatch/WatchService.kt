package io.github.sowan3k.chargewatch

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * Foreground service that does all the work, spending as little battery as it can.
 *
 * Live mode: a streaming connection to the relay, so alerts arrive within a second. The relay
 * sends a keepalive about every 45 s, and each one wakes the phone and its radio, so the app is
 * only live while it matters: the screen is open, this phone is on power, or another phone in
 * the group is charging (and not full yet).
 *
 * Low-power mode, the rest of the time: no connection. The app checks for news every 15 minutes
 * with an inexact, Doze-friendly alarm that Android batches with other apps (30 minutes in
 * Battery Saver), and right away when the screen turns on. A check that finds a charging phone
 * switches to live mode. Alerts still fire for transitions a check finds late (see Tracker).
 *
 * Publishing: this phone's level when it changes (fine steps only while charging), plus a
 * heartbeat every 30 minutes on power or 60 minutes on battery. Network work runs on one thread,
 * under a short wakelock, and a due heartbeat rides along with a check so the radio wakes once.
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
    private var started = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    @Volatile private var lastMsgId: String? = null
    private var polling = false
    private var pollArmed = false
    private var pollFailed = false
    private var lastPollAt = 0L

    // This phone's battery, from the last ACTION_BATTERY_CHANGED.
    private var level = -1
    private var charging = false

    // What was last published.
    private var sentLevel = -1
    private var sentCharging: Boolean? = null
    private var sentAt = 0L
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

    /** In low-power mode, turning the screen on triggers a check, so the list is fresh when you look. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mode == Mode.LOW_POWER && SystemClock.elapsedRealtime() - lastPollAt > SCREEN_POLL_GAP_MS) pollNow()
        }
    }

    private val hooks = object : Hub.ServiceHooks {
        override fun reevaluate() = applyMode()
        override fun pollAlarm() {
            pollArmed = false
            pollNow()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tracker = Tracker(prefs.deviceId)
        createChannels(this)
        goForeground()
        Hub.service = hooks
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground().
        goForeground()
        val g = prefs.group
        if (g == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (g != group || prefs.server != server) {
            switchGroup(g, prefs.server)
        } else if (intent?.action == ACTION_RELOAD) {
            // The name may have changed. Send it right away.
            publishNow()
        }
        when (intent?.action) {
            ACTION_FORGET -> intent?.getStringExtra(EXTRA_ID)?.let { id ->
                if (tracker.remove(id) != null) {
                    scheduleSave()
                    pushToHub()
                }
            }
            ACTION_POLL -> pollNow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (Hub.service === hooks) Hub.service = null
        stopStream()
        cancelPollAlarm()
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
        // Late callbacks from other threads check this and do nothing.
        group = null
        net.shutdown()
        super.onDestroy()
    }

    // ---- Group, modes and checks ----

    private fun switchGroup(g: String, s: String) {
        stopStream()
        group = g
        server = s
        mode = Mode.OFF
        lastMsgId = prefs.lastMsgId(g)
        tracker.clear()
        tracker.restore(prefs.loadDevices())
        pushToHub()
        sentCharging = null
        startOnce()
        if (level >= 0) publishNow()
        applyMode()
        // Catch up at once; live mode does this by itself when its stream connects.
        if (mode == Mode.LOW_POWER) pollNow()
        maybeCheckUpdate()
    }

    /** Live while the screen is open, while on power, or while another phone is charging toward an alert. */
    private fun wantLive(): Boolean {
        if (Hub.uiVisible || charging) return true
        val now = System.currentTimeMillis() / 1000
        val selfId = prefs.deviceId
        return tracker.devices.any { d ->
            d.id != selfId && d.charging && d.level < 100 && !Tracker.isStale(d, now)
        }
    }

    private fun applyMode() {
        val g = group ?: return
        if (wantLive()) {
            cancelPollAlarm()
            if (subscriber == null) startStream(g)
            mode = Mode.LIVE
        } else {
            if (subscriber != null) stopStream()
            if (mode != Mode.LOW_POWER) {
                mode = Mode.LOW_POWER
                Hub.setStatus("Low-power mode", true)
            }
            armPollAlarm()
        }
        scheduleStatus()
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

    /** One check: fetch what is new since the last message, then decide the mode again. */
    private fun pollNow() {
        val g = group ?: return
        if (polling) return
        polling = true
        lastPollAt = SystemClock.elapsedRealtime()
        // Send a heartbeat that would fall due before the next check now, in the same radio wake-up.
        if (level >= 0 && lastPollAt - sentAt >= heartbeatMs() - pollIntervalMs()) publishNow()
        val srv = server
        val since = lastMsgId ?: "12h"
        runOnNetwork {
            val lines = try {
                Relay.poll(srv, Pairing.topic(g), since)
            } catch (e: Exception) {
                null
            }
            main.post {
                polling = false
                if (g != group) return@post
                pollFailed = lines == null
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

    private fun pollIntervalMs(): Long {
        val saver = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        return if (saver) 2 * POLL_INTERVAL_MS else POLL_INTERVAL_MS
    }

    private fun pollIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        0,
        Intent(this, PollReceiver::class.java).setPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * An inexact alarm that may run in Doze, at most every 9 minutes there. It needs no
     * exact-alarm permission and Android may batch it with other apps' wake-ups.
     */
    private fun armPollAlarm() {
        if (pollArmed) return
        val am = getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + pollIntervalMs(), pollIntent())
        pollArmed = true
    }

    private fun cancelPollAlarm() {
        if (!pollArmed) return
        getSystemService(AlarmManager::class.java)?.cancel(pollIntent())
        pollArmed = false
    }

    /** Once a day, look for a new release and post a notification about it (once per version). */
    private fun maybeCheckUpdate() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < UPDATE_CHECK_MS) return
        prefs.lastUpdateCheck = now
        runOnNetwork {
            val info = runCatching { Updater.fetch() }.getOrNull() ?: return@runOnNetwork
            main.post {
                prefs.cachedUpdate = Updater.toJson(info)
                Hub.setUpdate(info = info)
                if (info.isNewer && prefs.notifiedVersion < info.versionCode) {
                    prefs.notifiedVersion = info.versionCode
                    val n = Notification.Builder(this, CHANNEL_UPDATES)
                        .setSmallIcon(R.drawable.ic_notify)
                        .setColor(BRAND)
                        .setContentTitle("ChargeWatch ${info.versionName} is available")
                        .setContentText("Tap to update. Your group and settings stay.")
                        .setAutoCancel(true)
                        .setContentIntent(openApp(this))
                        .build()
                    getSystemService(NotificationManager::class.java)?.notify(NOTIF_UPDATE, n)
                }
            }
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
                    if (mode == Mode.LOW_POWER && pollFailed) pollNow()
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
        val wasLive = wantLive()
        val update = tracker.onMessage(d, System.currentTimeMillis() / 1000, prefs.threshold)
        if (!update.changed) return
        val alert = update.alert
        if (alert != null) {
            showAlert(d, alert)
            // Save at once so a crash or restart cannot fire the same alert again.
            main.removeCallbacks(saveRunnable)
            saveRunnable.run()
        } else {
            scheduleSave()
        }
        pushToHub()
        if (wantLive() != wasLive) applyMode()
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
        maybePublish()
        if (charging != wasCharging) applyMode()
    }

    /**
     * Keeps the message count low (the public relay has a daily limit per IP):
     * every 1 % while charging above 50 %, where alerts happen, and every 5 % otherwise.
     * A heartbeat covers phones whose level does not move, such as one sitting at 100 %.
     */
    private fun maybePublish() {
        if (group == null || level < 0) return
        val now = SystemClock.elapsedRealtime()
        val step = if (charging && level >= FINE_FROM) 1 else COARSE_STEP
        val due = sentCharging == null ||
            charging != sentCharging ||
            abs(level - sentLevel) >= step ||
            now - sentAt >= heartbeatMs() ||
            (sendFailed && now - sentAt >= RETRY_MS)
        if (due) publishNow()
    }

    private fun publishNow() {
        val g = group ?: return
        if (level < 0) return
        sentLevel = level
        sentCharging = charging
        sentAt = SystemClock.elapsedRealtime()
        sendFailed = false
        val hbSec = (heartbeatMs() / 1000).toInt()
        send(server, Pairing.topic(g), Relay.report(prefs.deviceId, prefs.name, level, charging, hbSec))
        armHeartbeat()
    }

    /**
     * Runs network work on the single network thread. The wakelock is taken here, before the
     * hand-off, so the phone cannot fall asleep between an alarm and the request.
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

    // ---- Notifications ----

    private fun goForeground() {
        val n = statusNotification(statusTexts())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_STATUS, n)
        }
    }

    /** Updates the status notification only when its text changes. */
    private fun postStatus() {
        val texts = statusTexts()
        if (texts == lastStatus) return
        lastStatus = texts
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_STATUS, statusNotification(texts))
    }

    private fun statusTexts(): Pair<String, String> {
        val others = Hub.others
        val title = when {
            group == null -> "ChargeWatch"
            others.isEmpty() -> "Waiting for your other phones"
            else -> others.joinToString(" · ") { d ->
                "${d.name} ${d.level}%" + if (d.charging) " charging" else ""
            }
        }
        val minutes = pollIntervalMs() / 60_000
        val text = when {
            group == null -> "Starting…"
            mode == Mode.LOW_POWER -> "Low-power mode · checks every $minutes min"
            Hub.connected -> "Live · watching ${others.size} phone" + (if (others.size == 1) "" else "s")
            Hub.status.isNotEmpty() -> Hub.status
            else -> "Starting…"
        }
        return title to text
    }

    private fun statusNotification(texts: Pair<String, String>): Notification =
        Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(BRAND)
            .setContentTitle(texts.first)
            .setContentText(texts.second)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp(this))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()

    private fun showAlert(d: Device, alert: Alert) {
        val title: String
        val text: String
        when (alert) {
            Alert.THRESHOLD -> {
                title = "${d.name} is ${d.level}% charged"
                text = "Still charging. Unplug it now if that is enough."
            }
            Alert.FULL -> {
                title = "${d.name} is fully charged"
                text = "You can use it now."
            }
        }
        val n = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(BRAND)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp(this))
            .build()
        // One notification slot per phone: "full" replaces the earlier "80 %" alert.
        getSystemService(NotificationManager::class.java)?.notify(ALERT_BASE + (d.id.hashCode() and 0xffff), n)
    }

    companion object {
        const val ACTION_START = "io.github.sowan3k.chargewatch.START"
        const val ACTION_RELOAD = "io.github.sowan3k.chargewatch.RELOAD"
        const val ACTION_FORGET = "io.github.sowan3k.chargewatch.FORGET"
        const val ACTION_POLL = "io.github.sowan3k.chargewatch.POLL"
        private const val EXTRA_ID = "id"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_UPDATES = "updates"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_UPDATE = 2
        private const val UPDATE_CHECK_MS = 24 * 60 * 60 * 1000L
        private const val ALERT_BASE = 1000
        private const val BRAND = 0xFF0D7A56.toInt()

        private const val HEARTBEAT_CHARGING_MS = 30 * 60 * 1000L
        private const val HEARTBEAT_BATTERY_MS = 60 * 60 * 1000L
        private const val POLL_INTERVAL_MS = 15 * 60 * 1000L
        private const val SCREEN_POLL_GAP_MS = 2 * 60 * 1000L
        private const val RETRY_MS = 30 * 1000L
        private const val FINE_FROM = 50
        private const val COARSE_STEP = 5

        fun start(context: Context, reload: Boolean = false) {
            val i = Intent(context, WatchService::class.java)
                .setAction(if (reload) ACTION_RELOAD else ACTION_START)
            context.startForegroundService(i)
        }

        /** Removes a phone from the list until it reports again. */
        fun forget(context: Context, id: String) {
            val i = Intent(context, WatchService::class.java).setAction(ACTION_FORGET).putExtra(EXTRA_ID, id)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Charge alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When another phone reaches your alert level or is fully charged"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a new version of ChargeWatch is ready to install"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Background sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps ChargeWatch running. You can hide this channel."
                    setShowBadge(false)
                }
            )
        }

        fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** The low-power check alarm. Hands the check to the running service, or starts it. */
class PollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hooks = Hub.service
        if (hooks != null) {
            hooks.pollAlarm()
            return
        }
        if (Prefs(context).group == null) return
        try {
            context.startForegroundService(Intent(context, WatchService::class.java).setAction(WatchService.ACTION_POLL))
        } catch (e: Exception) {
            // Background start not allowed (battery optimization on): the next app open restarts it.
        }
    }
}
