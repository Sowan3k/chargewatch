package io.github.sowan3k.chargewatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private val sha = "0123456789abcdef".repeat(4)
    private val url = "https://github.com/Sowan3k/chargewatch/releases/download/latest/ChargeWatch.apk"

    private fun json(code: Int = 12, link: String = url, hash: String = sha) = """
        {"versionCode": $code, "versionName": "1.0.$code", "url": "$link", "sha256": "$hash",
         "size": 450000, "notes": "Add in-app updates", "downloads": 1234, "stars": 56}
    """.trimIndent()

    @Test
    fun readsTheVersionFile() {
        val info = Updater.parse(json())
        assertNotNull(info)
        info!!
        assertEquals(12, info.versionCode)
        assertEquals("1.0.12", info.versionName)
        assertEquals(1234L, info.downloads)
        assertEquals(56, info.stars)
        assertEquals("Add in-app updates", info.notes)
    }

    @Test
    fun cacheRoundTrip() {
        val info = Updater.parse(json())!!
        assertEquals(info, Updater.parse(Updater.toJson(info)))
    }

    @Test
    fun onlyTrustsThisRepositoryAndRealHashes() {
        assertNull(Updater.parse(json(link = "https://example.com/ChargeWatch.apk")))
        assertNull(Updater.parse(json(link = "http://github.com/Sowan3k/chargewatch/releases/download/latest/ChargeWatch.apk")))
        assertNull(Updater.parse(json(hash = "abc")))
        assertNull(Updater.parse("not json"))
        assertNull(Updater.parse("{}"))
    }

    @Test
    fun comparesWithThisBuild() {
        assertTrue(Updater.parse(json(code = BuildConfig.VERSION_CODE + 1))!!.isNewer)
        assertFalse(Updater.parse(json(code = BuildConfig.VERSION_CODE))!!.isNewer)
        assertFalse(Updater.parse(json(code = BuildConfig.VERSION_CODE - 1))!!.isNewer)
    }
}
