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
 * Foreground service that does all the work:
 * - publishes this phone's battery level when it changes, plus a 30-minute heartbeat,
 * - keeps a streaming subscription to the group topic,
 * - posts alerts when another phone crosses the alert level or reaches 100 %.
 */
class WatchService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var tracker: Tracker
    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<Triple<String, String, String>?>(null)

    private var subscriber: Subscriber? = null
    private var group: String? = null
    private var server: String = Pairing.DEFAULT_SERVER
    private var started = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    // This phone's battery, from the last ACTION_BATTERY_CHANGED.
    private var level = -1
    private var charging = false

    // What was last published.
    private var sentLevel = -1
    private var sentCharging: Boolean? = null
    private var sentAt = 0L
    @Volatile private var sendFailed = false

    private val heartbeat = AlarmManager.OnAlarmListener {
        refreshBattery()
        publishNow()
        maybeCheckUpdate()
    }

    private val saveRunnable = Runnable {
        val g = group
        if (g != null && prefs.group == g) prefs.saveDevices(tracker.devices)
    }

    private val statusRunnable = Runnable {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_STATUS, statusNotification())
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onBattery(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tracker = Tracker(prefs.deviceId)
        createChannels(this)
        goForeground()
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
        if (intent?.action == ACTION_FORGET) {
            intent?.getStringExtra(EXTRA_ID)?.let { id ->
                if (tracker.remove(id) != null) {
                    scheduleSave()
                    pushToHub()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        subscriber?.stop()
        subscriber = null
        if (started) {
            runCatching { unregisterReceiver(batteryReceiver) }
            networkCallback?.let { cb ->
                runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            }
            getSystemService(AlarmManager::class.java)?.cancel(heartbeat)
        }
        main.removeCallbacksAndMessages(null)
        saveRunnable.run()
        // Late callbacks from the subscriber thread check this and do nothing.
        group = null
        sender.shutdown()
        super.onDestroy()
    }

    // ---- Group and subscription ----

    private fun switchGroup(g: String, s: String) {
        subscriber?.stop()
        group = g
        server = s
        tracker.clear()
        tracker.restore(prefs.loadDevices())
        pushToHub()
        sentCharging = null
        startOnce()
        subscriber = Subscriber(
            server = s,
            topic = Pairing.topic(g),
            onReport = { r -> main.post { onReport(r, g) } },
            onState = { st -> main.post { onSubscriberState(st, g) } },
        ).also { it.start() }
        if (level >= 0) publishNow()
        maybeCheckUpdate()
    }

    /** Once a day, look for a new release and post a notification about it (once per version). */
    private fun maybeCheckUpdate() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < UPDATE_CHECK_MS) return
        prefs.lastUpdateCheck = now
        Thread {
            val info = runCatching { Updater.fetch() }.getOrNull() ?: return@Thread
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
        }.start()
    }

    private fun startOnce() {
        if (started) return
        started = true
        // Battery changes are a protected system broadcast, so no export flag is needed.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { onBattery(it) }
        registerNetworkCallback()
    }

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
            // Not fatal: the subscriber still reconnects on its own backoff.
        }
    }

    private fun onReport(r: Report, g: String) {
        if (g != group) return
        val d = r.device
        if (r.left) {
            if (tracker.onLeft(d.id, d.time)) {
                scheduleSave()
                pushToHub()
            }
            return
        }
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
    }

    private fun onSubscriberState(st: Subscriber.State, g: String) {
        if (g != group) return
        val host = Uri.parse(server).host ?: server
        when (st) {
            Subscriber.State.Connecting -> Hub.setStatus("Connecting to $host…", false)
            Subscriber.State.Connected -> {
                Hub.setStatus("Connected to $host", true)
                if (sendFailed) publishNow()
            }
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
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

        val name = prefs.name
        val old = Hub.self
        if (old == null || old.level != level || old.charging != charging || old.name != name) {
            Hub.setSelf(Device(prefs.deviceId, name, level, charging, System.currentTimeMillis() / 1000))
        }
        maybePublish()
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
            now - sentAt >= HEARTBEAT_MS ||
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
        send(server, Pairing.topic(g), Relay.report(prefs.deviceId, prefs.name, level, charging))
        armHeartbeat()
    }

    /** Sends only the newest report. A queued older one is dropped. */
    private fun send(server: String, topic: String, body: String) {
        pending.set(Triple(server, topic, body))
        sender.execute {
            val job = pending.getAndSet(null) ?: return@execute
            val pm = getSystemService(PowerManager::class.java)
            val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeWatch:send")
            wakeLock?.acquire(30_000)
            try {
                var ok = false
                for (attempt in 1..3) {
                    try {
                        Relay.publish(job.first, job.second, job.third)
                        ok = true
                        break
                    } catch (e: Relay.HttpError) {
                        if (e.code in 400..499) break // rate limit or bad request: retrying now will not help
                    } catch (e: IOException) {
                        // Network trouble. Try again shortly.
                    }
                    if (pending.get() != null) return@execute // a newer report is waiting
                    Thread.sleep(3_000L * attempt)
                }
                if (!ok) sendFailed = true
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private fun armHeartbeat() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        am.cancel(heartbeat)
        // An inexact wakeup alarm. It needs no special permission and is batched by the system.
        am.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + HEARTBEAT_MS,
            "chargewatch:heartbeat",
            heartbeat,
            main,
        )
    }

    // ---- Notifications ----

    private fun goForeground() {
        val n = statusNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_STATUS, n)
        }
    }

    private fun statusNotification(): Notification {
        val others = Hub.others
        val title = when {
            group == null -> "ChargeWatch"
            others.isEmpty() -> "Waiting for your other phones"
            else -> others.joinToString(" · ") { d ->
                "${d.name} ${d.level}%" + if (d.charging) " charging" else ""
            }
        }
        val text = when {
            Hub.connected -> "Watching ${others.size} phone" + (if (others.size == 1) "" else "s")
            Hub.status.isNotEmpty() -> Hub.status
            else -> "Starting…"
        }
        return Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(BRAND)
            .setContentTitle(title)
            .setContentText(text)
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
    }

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
        private const val EXTRA_ID = "id"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_UPDATES = "updates"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_UPDATE = 2
        private const val UPDATE_CHECK_MS = 24 * 60 * 60 * 1000L
        private const val ALERT_BASE = 1000
        private const val BRAND = 0xFF0D7A56.toInt()

        const val HEARTBEAT_MS = 30 * 60 * 1000L
        private const val RETRY_MS = 30 * 1000L
        private const val FINE_FROM = 50
        private const val COARSE_STEP = 5

        /** A phone that has been quiet this long is shown as possibly offline. */
        const val STALE_SECONDS = 2 * HEARTBEAT_MS / 1000 + 5 * 60

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
                    description = "Keeps ChargeWatch connected. You can hide this channel."
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
