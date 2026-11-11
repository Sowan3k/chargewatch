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
    fun lateTransitionsAlertWhenTheReadResumed() {
        // This phone slept through it: the next check continues from the last message it saw.
        val t = Tracker(self)
        t.onMessage(msg(79, time = now - 3000), now - 2990, 80)
        assertEquals(Alert.THRESHOLD, t.onMessage(msg(80, time = now - 2900), now, 80, resumed = true).alert)
        t.onMessage(msg(99, time = now - 1000), now, 80, resumed = true)
        assertEquals(Alert.FULL, t.onMessage(msg(100, time = now - 900), now, 80, resumed = true).alert)
    }

    @Test
    fun lateTransitionsStayQuietInTheBacklogReplay() {
        // Joining replays 12 hours of messages. Old transitions in it are history, not news.
        val t = Tracker(self)
        t.onMessage(msg(79, time = now - 3000), now, 80, resumed = false)
        assertNull(t.onMessage(msg(80, time = now - 2900), now, 80, resumed = false).alert)
        // And they count as alerted, so a later resumed read cannot fire them either.
        assertNull(t.onMessage(msg(81, time = now - 2800), now, 80, resumed = true).alert)
    }

    @Test
    fun transitionsOlderThanTheLateWindowNeverAlert() {
        val t = Tracker(self)
        t.onMessage(msg(79, time = now - Tracker.LATE_SECONDS - 200), now, 80)
        assertNull(t.onMessage(msg(80, time = now - Tracker.LATE_SECONDS - 100), now, 80, resumed = true).alert)
    }

    @Test
    fun ownLateMessagesNeverAlert() {
        val t = Tracker(self)
        t.onMessage(msg(79, id = self, time = now - 3000), now, 80)
        assertNull(t.onMessage(msg(80, id = self, time = now - 2900), now, 80, resumed = true).alert)
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
    fun aLeftoverWithTheSameNameIsDropped() {
        // The same phone under an old identity (from before a reinstall), silent for hours.
        val t = Tracker(self)
        t.onMessage(Device("old00001", "Poco X8", 40, false, now - 7 * 3600, msgId = "a"), now, 80)
        t.onMessage(Device("new00001", "Poco X8", 55, false, now - 60, msgId = "b"), now, 80)
        t.onMessage(Device("other001", "Pixel", 70, false, now - 7 * 3600, msgId = "c"), now, 80)
        assertTrue(t.tidy(now, "My phone"))
        assertNull(t["old00001"])
        assertNotNull(t["new00001"])
        assertNotNull("a quiet phone with its own name stays", t["other001"])
        assertFalse(t.tidy(now, "My phone"))
    }

    @Test
    fun twoLivePhonesMayShareAName() {
        val t = Tracker(self)
        t.onMessage(Device("twin0001", "Redmi", 40, false, now - 600, msgId = "a"), now, 80)
        t.onMessage(Device("twin0002", "Redmi", 55, false, now - 60, msgId = "b"), now, 80)
        assertFalse(t.tidy(now, "My phone"))
        assertEquals(2, t.devices.size)
    }

    @Test
    fun anOldCopyOfThisPhoneIsDropped() {
        val t = Tracker(self)
        t.onMessage(Device("ghost001", "My phone", 40, false, now - 7 * 3600, msgId = "a"), now, 80)
        t.onMessage(Device(self, "My phone", 90, true, now, msgId = "b"), now, 80)
        assertTrue(t.tidy(now, "My phone"))
        assertNull(t["ghost001"])
        assertNotNull(t[self])
    }

    @Test
    fun aPhoneSilentForAWeekIsForgotten() {
        val t = Tracker(self)
        t.onMessage(Device("gone0001", "Tablet", 40, false, now - 8 * 24 * 3600, msgId = "a"), now, 80)
        t.onMessage(Device("here0001", "Pixel", 40, false, now - 3 * 24 * 3600, msgId = "b"), now, 80)
        assertTrue(t.tidy(now, null))
        assertNull(t["gone0001"])
        assertNotNull(t["here0001"])
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
