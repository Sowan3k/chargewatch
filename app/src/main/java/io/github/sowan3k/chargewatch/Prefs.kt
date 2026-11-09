package io.github.sowan3k.chargewatch

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Small wrapper around SharedPreferences. */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("chargewatch", Context.MODE_PRIVATE)

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

    /** True once the user picked a name (or chose the system name) during onboarding or in settings. */
    var nameChosen: Boolean
        get() = sp.getBoolean(KEY_NAME_CHOSEN, !sp.getString(KEY_NAME, null).isNullOrBlank())
        set(v) = sp.edit().putBoolean(KEY_NAME_CHOSEN, v).apply()

    /** The last version.json we fetched, so the screen can show it offline. */
    var cachedUpdate: String?
        get() = sp.getString(KEY_UPDATE_JSON, null)
        set(v) = sp.edit().putString(KEY_UPDATE_JSON, v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_UPDATE_CHECKED, 0L)
        set(v) = sp.edit().putLong(KEY_UPDATE_CHECKED, v).apply()

    /** The newest version we already sent an "update available" notification for. */
    var notifiedVersion: Int
        get() = sp.getInt(KEY_UPDATE_NOTIFIED, 0)
        set(v) = sp.edit().putInt(KEY_UPDATE_NOTIFIED, v).apply()

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

    /** Keep the service and its notification on all the time, even while nothing charges. */
    var alwaysOn: Boolean
        get() = sp.getBoolean(KEY_ALWAYS_ON, false)
        set(v) = sp.edit().putBoolean(KEY_ALWAYS_ON, v).apply()

    /** What this phone last sent, shared by the service and the background check. */
    val sentLevel: Int get() = sp.getInt(KEY_SENT_LEVEL, -1)
    val sentCharging: Boolean? get() = if (sp.contains(KEY_SENT_CHARGING)) sp.getBoolean(KEY_SENT_CHARGING, false) else null
    val sentAt: Long get() = sp.getLong(KEY_SENT_AT, 0L)

    fun markSent(level: Int, charging: Boolean, atMs: Long) {
        sp.edit().putInt(KEY_SENT_LEVEL, level).putBoolean(KEY_SENT_CHARGING, charging).putLong(KEY_SENT_AT, atMs).apply()
    }

    /** The last relay message id seen in [group], so checks only fetch what is new. */
    fun lastMsgId(group: String): String? =
        if (sp.getString(KEY_LAST_MSG_GROUP, null) == group) sp.getString(KEY_LAST_MSG, null) else null

    fun setLastMsgId(group: String, id: String?) {
        sp.edit().putString(KEY_LAST_MSG_GROUP, group).putString(KEY_LAST_MSG, id).apply()
    }

    /** Forget the group and everything learned from it. Keeps name, threshold and server. */
    fun leave() {
        sp.edit().remove(KEY_GROUP).remove(KEY_DEVICES).remove(KEY_LAST_MSG).remove(KEY_LAST_MSG_GROUP)
            .remove(KEY_SENT_LEVEL).remove(KEY_SENT_CHARGING).remove(KEY_SENT_AT).apply()
    }

    /**
     * The name people gave the phone in Settings > About phone (for example "Redmi Note 13 Pro"),
     * or the model code (Build.MODEL) when there is none.
     */
    fun defaultName(): String {
        val cr = app.contentResolver
        // Vendors keep the user's device name in different places. Each read may be refused.
        val candidates = listOf(
            { Settings.Global.getString(cr, Settings.Global.DEVICE_NAME) },
            { Settings.System.getString(cr, "device_name") },
            { Settings.Secure.getString(cr, "bluetooth_name") },
        )
        for (read in candidates) {
            val name = runCatching { read() }.getOrNull()?.trim()
            if (!name.isNullOrEmpty() && name.length <= MAX_NAME) return name
        }
        val model = Build.MODEL?.trim().orEmpty()
        val maker = Build.MANUFACTURER?.trim().orEmpty().replaceFirstChar { it.uppercase() }
        val full = if (maker.isNotEmpty() && !model.startsWith(maker, ignoreCase = true)) "$maker $model" else model
        return full.trim().takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: "Android phone"
    }

    companion object {
        const val MAX_NAME = 40
        private const val KEY_GROUP = "group"
        private const val KEY_SERVER = "server"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_NAME = "name"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DEVICES = "devices"
        private const val KEY_NAME_CHOSEN = "name_chosen"
        private const val KEY_UPDATE_JSON = "update_json"
        private const val KEY_UPDATE_CHECKED = "update_checked"
        private const val KEY_UPDATE_NOTIFIED = "update_notified"
        private const val KEY_LAST_MSG = "last_msg_id"
        private const val KEY_ALWAYS_ON = "always_on"
        private const val KEY_SENT_LEVEL = "sent_level"
        private const val KEY_SENT_CHARGING = "sent_charging"
        private const val KEY_SENT_AT = "sent_at"
        private const val KEY_LAST_MSG_GROUP = "last_msg_group"

    }
}
