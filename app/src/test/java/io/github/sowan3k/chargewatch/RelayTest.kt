package io.github.sowan3k.chargewatch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTest {
    /** Wraps a report the way ntfy delivers it on the stream. */
    private fun event(message: String, id: String = "msg123", time: Long = 1_700_000_000L) =
        JSONObject().put("id", id).put("time", time).put("event", "message").put("topic", "t").put("message", message).toString()

    @Test
    fun reportRoundTrip() {
        val body = Relay.report("0123456789abcdef", "Pixel 7", 82, true, 1800)
        val (msgId, report) = Relay.parse(event(body))!!
        assertEquals("msg123", msgId)
        assertNotNull(report)
        val d = report!!.device
        assertEquals("0123456789abcdef", d.id)
        assertEquals("Pixel 7", d.name)
        assertEquals(82, d.level)
        assertTrue(d.charging)
        assertEquals(1800, d.heartbeat)
        assertEquals(1_700_000_000L, d.time)
        assertFalse(report.left)
    }

    @Test
    fun olderAppsWithoutHeartbeatGetTheDefault() {
        val body = """{"v":1,"id":"0123456789abcdef","name":"Old","level":40,"charging":false}"""
        assertEquals(Tracker.DEFAULT_HEARTBEAT, Relay.parse(event(body))!!.second!!.device.heartbeat)
    }

    @Test
    fun leaveMessages() {
        val (_, report) = Relay.parse(event(Relay.leaveReport("0123456789abcdef", "Pixel")))!!
        assertTrue(report!!.left)
    }

    @Test
    fun ignoresKeepalivesAndJunk() {
        assertNull(Relay.parse("""{"id":"k","time":1,"event":"keepalive","topic":"t"}"""))
        assertNull(Relay.parse("not json"))
        // A message that is not a ChargeWatch report: the id is kept, the report is dropped.
        assertNull(Relay.parse(event("hello"))!!.second)
        assertNull(Relay.parse(event("""{"id":"x","level":50}"""))!!.second)
        assertNull(Relay.parse(event("""{"id":"0123456789abcdef","level":150}"""))!!.second)
    }
}
