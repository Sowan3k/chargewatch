package io.github.sowan3k.chargewatch

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** One phone in the group, as last reported over the relay. */
data class Device(
    val id: String,
    val name: String,
    val level: Int,
    val charging: Boolean,
    /** Relay server timestamp of the message, in unix seconds. */
    val time: Long,
    /** Relay message id. Used to drop duplicates when the backlog is replayed. */
    val msgId: String = "",
    /** Set once the threshold alert has fired for the current charge. */
    val alertedThreshold: Boolean = false,
    /** Set once the full alert has fired for the current charge. */
    val alertedFull: Boolean = false,
    /** How often this phone promises a heartbeat, in seconds. Used to tell "quiet" from "gone". */
    val heartbeat: Int = Tracker.DEFAULT_HEARTBEAT,
)

enum class Alert { THRESHOLD, FULL }

data class Update(val changed: Boolean, val alert: Alert? = null)

/**
 * Keeps the latest state of every device in the group and decides when to alert.
 *
 * Rules:
 * - Alerts fire only on a transition: the previous level was below the mark and the new
 *   level is at or above it.
 * - Alerts fire only for recent messages and never for this phone. A message is recent when it
 *   is under [FRESH_SECONDS] old, which covers a low-power check running ~15 minutes late. When
 *   this phone resumes from the last message it saw ("resumed"), everything it fetches is news
 *   to it, so transitions up to [LATE_SECONDS] old still alert, and the alert says how late it
 *   is. The first read after joining replays a 12-hour backlog and is never "resumed", so old
 *   transitions in it stay quiet.
 * - Each alert fires at most once per charge. It re-arms when the phone is unplugged or the
 *   level drops clearly below the mark. This stops repeat alerts when a phone hovers at
 *   99/100 % or sits at an 80 % charge limit.
 * - Old or duplicate messages are ignored, so replaying the backlog cannot fire alerts twice.
 *
 * Not thread safe. The service calls it from the main thread only.
 */
class Tracker(private val selfId: String) {
    private val map = LinkedHashMap<String, Device>()

    val devices: List<Device> get() = map.values.toList()

    operator fun get(id: String): Device? = map[id]

    fun restore(list: Collection<Device>) {
        list.forEach { map[it.id] = it }
    }

    fun remove(id: String): Device? = map.remove(id)

    fun clear() = map.clear()

    /** A device announced that it left the group. */
    fun onLeft(id: String, time: Long): Boolean {
        val prev = map[id] ?: return false
        if (time < prev.time) return false
        map.remove(id)
        return true
    }

    /**
     * Drops entries that are clearly leftovers, and reports whether anything went:
     * - a phone that has been silent for a week;
     * - an entry that has been silent for hours while another phone (or this one) carries the
     *   same name. That is the same phone under an old identity, from before a reinstall.
     * A phone dropped by mistake comes back with its next report.
     */
    fun tidy(nowSec: Long, selfName: String?): Boolean {
        val names = map.values.filter { it.id != selfId && nowSec - it.time < DUPLICATE_SECONDS }.groupBy { it.name }
        val gone = map.values.filter { d ->
            if (d.id == selfId) return@filter false
            val silent = nowSec - d.time
            silent > FORGET_SECONDS ||
                (silent >= DUPLICATE_SECONDS && (d.name == selfName || names.containsKey(d.name)))
        }
        gone.forEach { map.remove(it.id) }
        return gone.isNotEmpty()
    }

    /**
     * [resumed] is true when the message came from a read that continued from the last message
     * id this phone had seen, so the message is new to this phone even if it is old.
     */
    fun onMessage(msg: Device, nowSec: Long, threshold: Int, resumed: Boolean = false): Update {
        val prev = map[msg.id]
        if (prev != null) {
            if (msg.time < prev.time) return Update(false)
            if (msg.msgId.isNotEmpty() && msg.msgId == prev.msgId) return Update(false)
        }

        var alertedThreshold = prev?.alertedThreshold ?: false
        var alertedFull = prev?.alertedFull ?: false
        if (!msg.charging) {
            alertedThreshold = false
            alertedFull = false
        }
        if (msg.level < threshold - REARM_MARGIN) alertedThreshold = false
        if (msg.level < 100 - REARM_MARGIN) alertedFull = false

        var alert: Alert? = null
        if (prev != null) {
            val crossedFull = prev.level < 100 && msg.level >= 100
            val crossedThreshold = threshold in 1..99 && msg.charging &&
                prev.level < threshold && msg.level >= threshold
            if (crossedFull && !alertedFull) {
                alertedFull = true
                alertedThreshold = true
                alert = Alert.FULL
            } else if (crossedThreshold && !alertedThreshold) {
                alertedThreshold = true
                alert = Alert.THRESHOLD
            }
        }

        // The flags still update for stale messages and for this phone, so a replayed
        // transition counts as "already alerted" and cannot fire later.
        val age = nowSec - msg.time
        val recent = age <= FRESH_SECONDS || (resumed && age <= LATE_SECONDS)
        if (!recent || msg.id == selfId) alert = null

        map[msg.id] = msg.copy(alertedThreshold = alertedThreshold, alertedFull = alertedFull)
        return Update(true, alert)
    }

    companion object {
        const val FRESH_SECONDS = 20 * 60L
        /** How late a transition may be found and still alert, when the read resumed. */
        const val LATE_SECONDS = 2 * 3600L
        const val REARM_MARGIN = 5
        const val DEFAULT_HEARTBEAT = 30 * 60
        /** A phone silent this long is dropped from the list. */
        const val FORGET_SECONDS = 7 * 24 * 3600L
        /** A silent entry with the same name as a live phone is dropped after this long. */
        const val DUPLICATE_SECONDS = 6 * 3600L

        /** A phone is "gone" after missing two heartbeats plus a margin. */
        fun staleAfter(d: Device): Long = 2L * d.heartbeat + 5 * 60

        fun isStale(d: Device, nowSec: Long): Boolean = nowSec - d.time > staleAfter(d)
    }
}

/**
 * Alerts from one burst of messages (a check, or a reconnect that replays what was missed),
 * reduced to what is still worth showing:
 * - one alert per phone, the newest (a later "full" replaces "reached 80%");
 * - none for a phone that a later message shows unplugged or gone, since nobody needs to act.
 * A live message is a burst of one, so it passes straight through.
 */
class AlertQueue {
    private val pending = LinkedHashMap<String, Pair<Device, Alert>>()

    fun offer(d: Device, update: Update) {
        val alert = update.alert
        if (alert != null) {
            pending[d.id] = d to alert
        } else if (update.changed && !d.charging) {
            pending.remove(d.id)
        }
    }

    fun drop(id: String) {
        pending.remove(id)
    }

    fun isEmpty() = pending.isEmpty()

    fun drain(): List<Pair<Device, Alert>> = pending.values.toList().also { pending.clear() }
}

/**
 * The words of a charge alert. They say what was reported and when, and never suggest the
 * news is live when it reached this phone late (Android may hold background checks back).
 */
object AlertText {
    /** Reports older than this read as late and say when they were sent. */
    const val LATE_NOTE_SECONDS = 2 * 60L

    fun title(d: Device, alert: Alert): String = when (alert) {
        Alert.THRESHOLD -> "${d.name} reached ${d.level}% · Charging"
        Alert.FULL -> if (d.charging) "${d.name} is full · Still on the charger" else "${d.name} is full"
    }

    fun text(d: Device, alert: Alert, nowSec: Long): String {
        val age = nowSec - d.time
        if (age > LATE_NOTE_SECONDS) {
            val reported = "Reported ${agoPrecise(age)}."
            return when (alert) {
                Alert.THRESHOLD -> "$reported The update arrived late, so it may be higher now."
                Alert.FULL -> "$reported The update arrived late."
            }
        }
        return when (alert) {
            Alert.THRESHOLD -> "Unplug it now if that is enough."
            Alert.FULL -> if (d.charging) "You can unplug it and use it now." else "You can use it now."
        }
    }
}

/**
 * Device state as stored on this phone between runs. The heartbeat is kept too: without it, a
 * phone on battery (one report an hour) looked like a 30-minute phone after a restart and was
 * shown as offline an hour early.
 */
object DeviceJson {
    fun encode(list: Collection<Device>): String {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("level", d.level)
                    .put("charging", d.charging)
                    .put("time", d.time)
                    .put("mid", d.msgId)
                    .put("at", d.alertedThreshold)
                    .put("af", d.alertedFull)
                    .put("hb", d.heartbeat)
            )
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<Device> {
        if (raw == null) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Device(
                    id = o.getString("id"),
                    name = o.optString("name", "Phone"),
                    level = o.optInt("level"),
                    charging = o.optBoolean("charging"),
                    time = o.optLong("time"),
                    msgId = o.optString("mid"),
                    alertedThreshold = o.optBoolean("at"),
                    alertedFull = o.optBoolean("af"),
                    // Saved by older versions without it: the old default, until the next report.
                    heartbeat = o.optInt("hb", Tracker.DEFAULT_HEARTBEAT).coerceIn(MIN_HEARTBEAT, MAX_HEARTBEAT),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    const val MIN_HEARTBEAT = 5 * 60
    const val MAX_HEARTBEAT = 24 * 60 * 60
}

/** An invite read from a QR code or typed in by hand. */
data class Invite(val groupId: String, val server: String)

/**
 * Group codes. The QR holds "chargewatch:<group id>" for the public relay, or
 * "chargewatch:<group id>@<server url>" for a self-hosted ntfy server.
 * The group id is 80 random bits as 20 hex characters.
 */
object Pairing {
    const val PREFIX = "chargewatch:"
    const val DEFAULT_SERVER = "https://ntfy.sh"
    private val GROUP_ID = Regex("^[0-9a-f]{20}$")
    private val random = SecureRandom()

    fun newGroupId(): String = hex(10)

    fun newDeviceId(): String = hex(8)

    fun topic(groupId: String) = "chargewatch-$groupId"

    fun encode(groupId: String, server: String): String {
        val s = normalizeServer(server) ?: DEFAULT_SERVER
        return if (s == DEFAULT_SERVER) "$PREFIX$groupId" else "$PREFIX$groupId@$s"
    }

    /** "3f9a1c0e..." -> "3f9a-1c0e-...", easier to read out or type. */
    fun pretty(groupId: String) = groupId.chunked(4).joinToString("-")

    fun parse(raw: String): Invite? {
        var s = raw.trim()
        if (s.startsWith(PREFIX, ignoreCase = true)) s = s.substring(PREFIX.length)
        val at = s.indexOf('@')
        val codePart = if (at >= 0) s.substring(0, at) else s
        val serverPart = if (at >= 0) s.substring(at + 1) else ""
        val code = codePart.filterNot { it == '-' || it == ' ' }.lowercase()
        if (!GROUP_ID.matches(code)) return null
        val server = if (serverPart.isBlank()) DEFAULT_SERVER else normalizeServer(serverPart) ?: return null
        return Invite(code, server)
    }

    /** Returns a clean "https://host[:port][/path]" URL, or null if it is not http(s). */
    fun normalizeServer(raw: String): String? {
        val s = raw.trim().trimEnd('/')
        val lower = s.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        val rest = s.substringAfter("://")
        if (rest.isEmpty() || rest.any { it.isWhitespace() || it == '?' || it == '#' }) return null
        return s
    }

    private fun hex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 d ago". */
fun ago(seconds: Long): String = when {
    seconds < 60 -> "just now"
    seconds < 3600 -> "${seconds / 60} min ago"
    seconds < 86400 -> "${seconds / 3600} h ago"
    else -> "${seconds / 86400} d ago"
}

/** Like [ago], but keeps the minutes past the hour: "1 h 50 min ago". For alert texts. */
fun agoPrecise(seconds: Long): String {
    if (seconds < 3600 || seconds >= 86400) return ago(seconds)
    val minutes = (seconds % 3600) / 60
    return if (minutes == 0L) ago(seconds) else "${seconds / 3600} h $minutes min ago"
}
