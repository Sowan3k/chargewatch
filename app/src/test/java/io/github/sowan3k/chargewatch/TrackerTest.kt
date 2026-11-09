package io.github.sowan3k.chargewatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerTest {
    private val self = "self0000"
    private val now = 1_700_000_000L
    private var seq = 0

    private fun msg(level: Int, charging: Boolean = true, time: Long = now, id: String = "phone001") =
        Device(id, "Pixel", level, charging, time, msgId = "m${seq++}")

    @Test
    fun firstMessageNeverAlerts() {
        val t = Tracker(self)
        assertNull(t.onMessage(msg(80), now, 80).alert)
        assertNull(t.onMessage(msg(100, time = now + 1, id = "phone002"), now, 80).alert)
    }

    @Test
    fun thresholdAndFullFireOnTransitions() {
        val t = Tracker(self)
        t.onMessage(msg(79), now, 80)
        assertEquals(Alert.THRESHOLD, t.onMessage(msg(80, time = now + 60), now + 60, 80).alert)
        assertNull(t.onMessage(msg(90, time = now + 120), now + 120, 80).alert)
        t.onMessage(msg(99, time = now + 180), now + 180, 80)
        assertEquals(Alert.FULL, t.onMessage(msg(100, time = now + 240), now + 240, 80).alert)
    }

    @Test
    fun thresholdNeedsCharging() {
        val t = Tracker(self)
        t.onMessage(msg(79, charging = false), now, 80)
        assertNull(t.onMessage(msg(80, charging = false, time = now + 1), now + 1, 80).alert)
    }

    @Test
    fun jumpStraightToFullOnlyFiresFull() {
        val t = Tracker(self)
        t.onMessage(msg(70), now, 80)
        assertEquals(Alert.FULL, t.onMessage(msg(100, time = now + 5), now + 5, 80).alert)
    }

    @Test
    fun ownMessagesNeverAlert() {
        val t = Tracker(self)
        t.onMessage(msg(79, id = self), now, 80)
        assertNull(t.onMessage(msg(80, id = self, time = now + 1), now + 1, 80).alert)
        assertTrue(t.onMessage(msg(100, id = self, time = now + 2), now + 2, 80).changed)
    }

    @Test
    fun staleMessagesDoNotAlert() {
        val t = Tracker(self)
        t.onMessage(msg(79, time = now - 3000), now, 80)
        assertNull(t.onMessage(msg(80, time = now - 2900), now, 80).alert)
    }

    @Test
    fun lateButRecentMessagesStillAlert() {
        // A low-power check can fetch a transition a few minutes after it happened.
        val t = Tracker(self)
        t.onMessage(msg(79, time = now - 700), now, 80)
        assertEquals(Alert.THRESHOLD, t.onMessage(msg(80, time = now - 600), now, 80).alert)
    }

    @Test
    fun stalenessFollowsEachPhonesHeartbeat() {
        val charging = Device("a", "A", 80, true, now - 3600, heartbeat = 1800)
        val onBattery = Device("b", "B", 50, false, now - 3600, heartbeat = 3600)
        assertFalse(Tracker.isStale(charging, now - 3600 + 60))
        assertTrue(Tracker.isStale(charging, now + 600))
        assertFalse(Tracker.isStale(onBattery, now + 600))
        assertTrue(Tracker.isStale(onBattery, now + 4200))
    }

    @Test
    fun replayingBacklogDoesNotRefire() {
        val t = Tracker(self)
        val backlog = listOf(msg(78, time = now - 30), msg(79, time = now - 20), msg(80, time = now - 10))
        val alerts = backlog.map { t.onMessage(it, now, 80).alert }
        assertEquals(Alert.THRESHOLD, alerts.last())
        // Reconnect: the server sends the same messages again.
        val replay = backlog.map { t.onMessage(it, now + 5, 80) }
        assertTrue(replay.none { it.changed })
        assertTrue(replay.none { it.alert != null })
    }

    @Test
    fun restartWithEmptyStateDoesNotRefire() {
        // Fresh process: state restored from disk, then the backlog arrives again.
        val first = Tracker(self)
        val backlog = listOf(msg(79, time = now - 20), msg(80, time = now - 10))
        backlog.forEach { first.onMessage(it, now, 80) }
        val second = Tracker(self)
        second.restore(first.devices)
        assertTrue(backlog.none { second.onMessage(it, now + 5, 80).alert != null })
    }

    @Test
    fun hoveringAroundTheMarkFiresOnce() {
        val t = Tracker(self)
        var time = now
        t.onMessage(msg(79, time = time), time, 80)
        var count = 0
        repeat(5) {
            time += 30
            if (t.onMessage(msg(80, time = time), time, 80).alert != null) count++
            time += 30
            if (t.onMessage(msg(79, time = time), time, 80).alert != null) count++
        }
        assertEquals(1, count)

        // Same for a phone that trickles between 99 and 100 %.
        count = 0
        repeat(5) {
            time += 30
            if (t.onMessage(msg(100, time = time), time, 80).alert == Alert.FULL) count++
            time += 30
            t.onMessage(msg(99, time = time), time, 80)
        }
        assertEquals(1, count)
    }

    @Test
    fun unplugRearmsAlerts() {
        val t = Tracker(self)
        t.onMessage(msg(99, time = now), now, 80)
        assertEquals(Alert.FULL, t.onMessage(msg(100, time = now + 1), now + 1, 80).alert)
        t.onMessage(msg(99, charging = false, time = now + 2), now + 2, 80)
        t.onMessage(msg(99, charging = true, time = now + 3), now + 3, 80)
        assertEquals(Alert.FULL, t.onMessage(msg(100, time = now + 4), now + 4, 80).alert)
    }

    @Test
    fun customThresholdAndOff() {
        val t = Tracker(self)
        t.onMessage(msg(89), now, 90)
        assertEquals(Alert.THRESHOLD, t.onMessage(msg(90, time = now + 1), now + 1, 90).alert)

        val off = Tracker(self)
        off.onMessage(msg(79), now, 0)
        assertNull(off.onMessage(msg(80, time = now + 1), now + 1, 0).alert)
        off.onMessage(msg(99, time = now + 2), now + 2, 0)
        assertEquals(Alert.FULL, off.onMessage(msg(100, time = now + 3), now + 3, 0).alert)
    }

    @Test
    fun outOfOrderMessagesAreIgnored() {
        val t = Tracker(self)
        t.onMessage(msg(85, time = now), now, 80)
        assertFalse(t.onMessage(msg(50, time = now - 100), now, 80).changed)
        assertEquals(85, t["phone001"]!!.level)
    }

    @Test
    fun leftRemovesDeviceOnlyIfNewer() {
        val t = Tracker(self)
        t.onMessage(msg(50, time = now), now, 80)
        assertFalse(t.onLeft("phone001", now - 10))
        assertNotNull(t["phone001"])
        assertTrue(t.onLeft("phone001", now + 10))
        assertNull(t["phone001"])
    }
}

class PairingTest {
    @Test
    fun roundTripDefaultServer() {
        val id = Pairing.newGroupId()
        assertEquals(20, id.length)
        val code = Pairing.encode(id, Pairing.DEFAULT_SERVER)
        assertEquals("chargewatch:$id", code)
        assertEquals(Invite(id, Pairing.DEFAULT_SERVER), Pairing.parse(code))
    }

    @Test
    fun roundTripCustomServer() {
        val id = Pairing.newGroupId()
        val code = Pairing.encode(id, "https://ntfy.example.com/")
        assertEquals("chargewatch:$id@https://ntfy.example.com", code)
        assertEquals(Invite(id, "https://ntfy.example.com"), Pairing.parse(code))
    }

    @Test
    fun parsesTypedCodes() {
        val id = "0123456789abcdef0123"
        assertEquals(id, Pairing.parse("0123-4567-89AB-CDEF-0123")?.groupId)
        assertEquals(id, Pairing.parse("  chargewatch:0123 4567 89ab cdef 0123 ")?.groupId)
    }

    @Test
    fun rejectsJunk() {
        assertNull(Pairing.parse("https://example.com"))
        assertNull(Pairing.parse("chargewatch:1234"))
        assertNull(Pairing.parse("chargewatch:0123456789abcdef0123@ftp://x"))
        assertNull(Pairing.normalizeServer("ntfy.sh"))
    }

    @Test
    fun topicAndPretty() {
        assertEquals("chargewatch-0123456789abcdef0123", Pairing.topic("0123456789abcdef0123"))
        assertEquals("0123-4567-89ab-cdef-0123", Pairing.pretty("0123456789abcdef0123"))
    }

    @Test
    fun agoFormatting() {
        assertEquals("just now", ago(5))
        assertEquals("5 min ago", ago(300))
        assertEquals("3 h ago", ago(3 * 3600 + 100))
        assertEquals("2 d ago", ago(2 * 86400))
    }
}
