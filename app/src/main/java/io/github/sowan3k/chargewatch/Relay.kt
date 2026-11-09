package io.github.sowan3k.chargewatch

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A battery report as it travels over the relay. */
data class Report(val device: Device, val left: Boolean)

/**
 * Talks to an ntfy server with plain HttpURLConnection (no extra libraries).
 * Publish: POST <server>/<topic> with the JSON report as the body.
 * Subscribe: GET <server>/<topic>/json?since=..., one JSON event per line.
 */
object Relay {
    private val USER_AGENT = "ChargeWatch/${BuildConfig.VERSION_NAME}"
    private val ID = Regex("^[0-9a-f]{8,32}$")

    class HttpError(val code: Int, val retryAfterSec: Long?) : IOException("HTTP $code")

    fun report(id: String, name: String, level: Int, charging: Boolean): String =
        JSONObject()
            .put("v", 1)
            .put("id", id)
            .put("name", name)
            .put("level", level)
            .put("charging", charging)
            .toString()

    fun leaveReport(id: String, name: String): String =
        JSONObject().put("v", 1).put("id", id).put("name", name).put("left", true).toString()

    /** Sends one message. Throws on network errors and non-2xx answers. */
    fun publish(server: String, topic: String, body: String) {
        val c = URL("$server/$topic").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            // The messages are for ChargeWatch only. Skip ntfy's Firebase push forwarding.
            c.setRequestProperty("X-Firebase", "no")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                throw HttpError(code, c.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
            }
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Parses one line of the subscribe stream. Returns null for keepalives, other event
     * types, and anything that is not a valid ChargeWatch report. Anyone who knows the
     * topic can post to it, so every field is checked.
     */
    fun parse(line: String): Pair<String, Report?>? {
        val event = try {
            JSONObject(line)
        } catch (e: Exception) {
            return null
        }
        if (event.optString("event") != "message") return null
        val msgId = event.optString("id")
        val time = event.optLong("time", 0L)
        val report = try {
            val o = JSONObject(event.optString("message"))
            val id = o.optString("id")
            if (!ID.matches(id)) {
                null
            } else {
                val name = o.optString("name").trim().take(Prefs.MAX_NAME).ifEmpty { "Phone" }
                val left = o.optBoolean("left", false)
                val level = o.optInt("level", -1)
                if (!left && level !in 0..100) {
                    null
                } else {
                    Report(Device(id, name, level.coerceIn(0, 100), o.optBoolean("charging"), time, msgId), left)
                }
            }
        } catch (e: Exception) {
            null
        }
        return msgId to report
    }
}

/**
 * Keeps one streaming subscription open and reconnects with backoff when it drops.
 * Callbacks run on the subscriber thread.
 */
class Subscriber(
    private val server: String,
    private val topic: String,
    private val onReport: (Report) -> Unit,
    private val onState: (State) -> Unit,
) {
    sealed class State {
        object Connecting : State()
        object Connected : State()
        data class Waiting(val seconds: Long, val reason: String) : State()
    }

    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var wakeUp = false
    @Volatile private var lastMsgId: String? = null
    private val lock = Object()
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "chargewatch-sub").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        kick()
    }

    /** Drop the current connection (for example after a network change) and reconnect now. */
    fun kick() {
        // disconnect() may touch the socket, so keep it off the main thread.
        connection?.let { c -> Thread { c.disconnect() }.start() }
        retryNow()
    }

    /** Skip the remaining backoff wait, if any. */
    fun retryNow() {
        synchronized(lock) {
            wakeUp = true
            lock.notifyAll()
        }
    }

    private fun loop() {
        var backoffSec = 2L
        while (running) {
            var reason = "Connection lost"
            var minWaitSec = 0L
            val startedAt = System.currentTimeMillis()
            try {
                onState(State.Connecting)
                // First connect: replay the cached backlog (ntfy keeps 12 h) to learn the
                // current state of every phone. Reconnects only fetch what was missed.
                val since = lastMsgId ?: "12h"
                val c = URL("$server/$topic/json?since=$since").openConnection() as HttpURLConnection
                connection = c
                c.connectTimeout = 15_000
                // ntfy sends a keepalive event about every 45 s, so silence means a dead link.
                c.readTimeout = 100_000
                c.setRequestProperty("User-Agent", "ChargeWatch/${BuildConfig.VERSION_NAME}")
                val code = c.responseCode
                if (code != 200) {
                    val retry = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                    throw Relay.HttpError(code, retry)
                }
                onState(State.Connected)
                c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    while (running) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        val parsed = Relay.parse(line) ?: continue
                        if (parsed.first.isNotEmpty()) lastMsgId = parsed.first
                        parsed.second?.let(onReport)
                    }
                }
            } catch (e: Relay.HttpError) {
                reason = if (e.code == 429) "Relay rate limit reached" else "Relay answered HTTP ${e.code}"
                minWaitSec = e.retryAfterSec?.coerceIn(5, 3600) ?: 0
                if (e.code == 429) minWaitSec = maxOf(minWaitSec, 60)
            } catch (e: Exception) {
                reason = "No connection to relay"
            } finally {
                connection?.disconnect()
                connection = null
            }
            if (!running) break

            // A link that stayed up for a while resets the backoff.
            if (System.currentTimeMillis() - startedAt > 60_000) backoffSec = 2
            val waitSec = maxOf(backoffSec, minWaitSec)
            onState(State.Waiting(waitSec, reason))
            synchronized(lock) {
                val deadline = System.currentTimeMillis() + waitSec * 1000
                while (running && !wakeUp) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.wait(left)
                }
                wakeUp = false
            }
            backoffSec = (backoffSec * 2).coerceAtMost(120)
        }
    }
}
