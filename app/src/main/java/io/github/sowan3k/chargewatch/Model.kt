package io.github.sowan3k.chargewatch

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
 * - Alerts fire only for recent messages (under [FRESH_SECONDS] old) and never for this phone.
 *   The window is wide enough for a low-power check, which can run up to ~15 minutes late.
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

    fun onMessage(msg: Device, nowSec: Long, threshold: Int): Update {
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
        val fresh = nowSec - msg.time <= FRESH_SECONDS
        if (!fresh || msg.id == selfId) alert = null

        map[msg.id] = msg.copy(alertedThreshold = alertedThreshold, alertedFull = alertedFull)
        return Update(true, alert)
    }

    companion object {
        const val FRESH_SECONDS = 20 * 60L
        const val REARM_MARGIN = 5
        const val DEFAULT_HEARTBEAT = 30 * 60

        /** A phone is "gone" after missing two heartbeats plus a margin. */
        fun staleAfter(d: Device): Long = 2L * d.heartbeat + 5 * 60

        fun isStale(d: Device, nowSec: Long): Boolean = nowSec - d.time > staleAfter(d)
    }
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
