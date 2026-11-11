package io.github.sowan3k.chargewatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertQueueTest {
    private val now = 1_700_000_000L
    private fun dev(level: Int, charging: Boolean = true, id: String = "phone001") =
        Device(id, "Pixel", level, charging, now)

    @Test
    fun aLiveAlertPassesStraightThrough() {
        val q = AlertQueue()
        q.offer(dev(80), Update(true, Alert.THRESHOLD))
        assertEquals(listOf(dev(80) to Alert.THRESHOLD), q.drain())
        assertTrue(q.isEmpty())
    }

    @Test
    fun fullReplacesTheEarlierAlertForTheSamePhone() {
        val q = AlertQueue()
        q.offer(dev(80), Update(true, Alert.THRESHOLD))
        q.offer(dev(90), Update(true))
        q.offer(dev(100), Update(true, Alert.FULL))
        assertEquals(listOf(dev(100) to Alert.FULL), q.drain())
    }

    @Test
    fun aPhoneUnpluggedLaterInTheBurstGetsNoAlert() {
        val q = AlertQueue()
        q.offer(dev(80), Update(true, Alert.THRESHOLD))
        q.offer(dev(82, charging = false), Update(true))
        assertTrue(q.drain().isEmpty())
    }

    @Test
    fun ignoredMessagesDoNotCancelAnAlert() {
        // A duplicate or out-of-order message changes nothing, whatever it says.
        val q = AlertQueue()
        q.offer(dev(80), Update(true, Alert.THRESHOLD))
        q.offer(dev(50, charging = false), Update(false))
        assertEquals(1, q.drain().size)
    }

    @Test
    fun aPhoneThatLeftGetsNoAlert() {
        val q = AlertQueue()
        q.offer(dev(80), Update(true, Alert.THRESHOLD))
        q.offer(dev(100, id = "phone002"), Update(true, Alert.FULL))
        q.drop("phone001")
        assertEquals(listOf(dev(100, id = "phone002") to Alert.FULL), q.drain())
    }
}

class AlertTextTest {
    private val now = 1_700_000_000L
    private fun dev(level: Int, charging: Boolean = true, age: Long = 0) =
        Device("phone001", "Samsung S24", level, charging, now - age)

    @Test
    fun titlesNameTheLevelAndTheChargingState() {
        assertEquals("Samsung S24 reached 80% · Charging", AlertText.title(dev(80), Alert.THRESHOLD))
        assertEquals("Samsung S24 reached 83% · Charging", AlertText.title(dev(83), Alert.THRESHOLD))
        assertEquals("Samsung S24 is full · Still on the charger", AlertText.title(dev(100), Alert.FULL))
        assertEquals("Samsung S24 is full", AlertText.title(dev(100, charging = false), Alert.FULL))
    }

    @Test
    fun aTimelyAlertSaysWhatToDo() {
        assertEquals("Unplug it now if that is enough.", AlertText.text(dev(80, age = 30), Alert.THRESHOLD, now))
        assertEquals("You can unplug it and use it now.", AlertText.text(dev(100, age = 30), Alert.FULL, now))
        val edge = AlertText.LATE_NOTE_SECONDS
        assertFalse(AlertText.text(dev(80, age = edge), Alert.THRESHOLD, now).startsWith("Reported"))
    }

    @Test
    fun aLateAlertSaysWhenThePhoneReported() {
        assertEquals(
            "Reported 35 min ago. The update arrived late, so it may be higher now.",
            AlertText.text(dev(80, age = 35 * 60 + 20), Alert.THRESHOLD, now),
        )
        assertEquals(
            "Reported 1 h 50 min ago. The update arrived late.",
            AlertText.text(dev(100, age = 110 * 60), Alert.FULL, now),
        )
        assertTrue(AlertText.text(dev(80, age = AlertText.LATE_NOTE_SECONDS + 60), Alert.THRESHOLD, now).startsWith("Reported 3 min ago."))
    }

    @Test
    fun preciseAgoKeepsTheMinutes() {
        assertEquals("35 min ago", agoPrecise(35 * 60))
        assertEquals("1 h ago", agoPrecise(3600 + 20))
        assertEquals("1 h 50 min ago", agoPrecise(110 * 60))
        assertEquals("2 d ago", agoPrecise(2 * 86400))
    }
}

class DeviceJsonTest {
    @Test
    fun roundTripKeepsEveryField() {
        val list = listOf(
            Device("a1a1a1a1", "Poco X8", 45, false, 1_700_000_000L, "m1", alertedThreshold = false, alertedFull = false, heartbeat = 3600),
            Device("b2b2b2b2", "Pixel 7", 100, true, 1_700_000_100L, "m2", alertedThreshold = true, alertedFull = true, heartbeat = 1800),
        )
        assertEquals(list, DeviceJson.decode(DeviceJson.encode(list)))
    }

    @Test
    fun aPhoneOnBatteryStaysFreshAfterARestart() {
        // One report an hour: not stale 70 minutes after its last one, even after a reload.
        val reported = 1_700_000_000L
        val saved = DeviceJson.encode(listOf(Device("a1a1a1a1", "Poco X8", 45, false, reported, heartbeat = 3600)))
        val restored = DeviceJson.decode(saved).single()
        assertFalse(Tracker.isStale(restored, reported + 70 * 60))
        assertTrue(Tracker.isStale(restored, reported + 130 * 60))
    }

    @Test
    fun dataFromOlderVersionsStillLoads() {
        val old = """[{"id":"a1a1a1a1","name":"Poco X8","level":45,"charging":false,"time":1700000000,"mid":"m1","at":false,"af":false}]"""
        val d = DeviceJson.decode(old).single()
        assertEquals(Tracker.DEFAULT_HEARTBEAT, d.heartbeat)
        assertEquals(45, d.level)
    }

    @Test
    fun junkLoadsAsNothing() {
        assertTrue(DeviceJson.decode(null).isEmpty())
        assertTrue(DeviceJson.decode("not json").isEmpty())
        assertEquals(DeviceJson.MAX_HEARTBEAT, DeviceJson.decode("""[{"id":"a","hb":999999999}]""").single().heartbeat)
    }
}
