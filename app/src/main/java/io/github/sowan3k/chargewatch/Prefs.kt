package io.github.sowan3k.chargewatch

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Small wrapper around SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("chargewatch", Context.MODE_PRIVATE)

    /** Group id, or null when this phone is not paired. */
    var group: String?
        get() = sp.getString(KEY_GROUP, null)
        set(v) = sp.edit().putString(KEY_GROUP, v).apply()

    var server: String
        get() = sp.getString(KEY_SERVER, null) ?: Pairing.DEFAULT_SERVER
        set(v) = sp.edit().putString(KEY_SERVER, v).apply()

    val deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, null) ?: Pairing.newDeviceId().also {
            sp.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    var name: String
        get() = sp.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName()
        set(v) = sp.edit().putString(KEY_NAME, v.trim().take(MAX_NAME)).apply()

    /** Alert when another phone reaches this level while charging. 0 means off. */
    var threshold: Int
        get() = sp.getInt(KEY_THRESHOLD, 80)
        set(v) = sp.edit().putInt(KEY_THRESHOLD, v).apply()

    fun loadDevices(): List<Device> {
        val raw = sp.getString(KEY_DEVICES, null) ?: return emptyList()
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
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveDevices(list: Collection<Device>) {
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
            )
        }
        sp.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    /** Forget the group and everything learned from it. Keeps name, threshold and server. */
    fun leave() {
        sp.edit().remove(KEY_GROUP).remove(KEY_DEVICES).apply()
    }

    companion object {
        const val MAX_NAME = 40
        private const val KEY_GROUP = "group"
        private const val KEY_SERVER = "server"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_NAME = "name"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DEVICES = "devices"

        fun defaultName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android phone"
    }
}
