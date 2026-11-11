package io.github.sowan3k.chargewatch

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
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

    /**
     * This phone's identity in a group. It comes from Android's per-app device id, so it stays
     * the same when the app is reinstalled or its data is cleared, and the phone does not turn
     * up twice in other phones' lists. (Earlier versions used a random id per install.)
     */
    val deviceId: String
        get() {
            cachedId?.let { return it }
            val stored = sp.getString(KEY_DEVICE_ID, null)
            val id = stableId() ?: stored ?: Pairing.newDeviceId()
            if (id != stored) {
                sp.edit().putString(KEY_DEVICE_ID, id).apply()
                if (stored != null) {
                    // An id from an earlier version: tell the group to drop it, and drop it here.
                    group?.let { addGoodbye(server, it, stored, name) }
                    saveDevices(loadDevices().filter { it.id != stored })
                }
            }
            cachedId = id
            return id
        }

    /** A short hash of Android's id for this app on this phone, or null if the phone has none. */
    @SuppressLint("HardwareIds")
    private fun stableId(): String? {
        val raw = runCatching { Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
        if (raw.isNullOrBlank() || raw == "9774d56d682e549c") return null // a known broken value
        val hash = MessageDigest.getInstance("SHA-256").digest("chargewatch-device:$raw".toByteArray())
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    // ---- Goodbyes: "this phone left" messages that still have to reach the relay ----

    fun goodbyes(): List<Goodbye> = try {
        val arr = JSONArray(sp.getString(KEY_GOODBYES, null) ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Goodbye(o.getString("server"), o.getString("group"), o.getString("id"), o.optString("name", "Phone"), o.optLong("at"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun setGoodbyes(list: List<Goodbye>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("server", it.server).put("group", it.group).put("id", it.id).put("name", it.name).put("at", it.at))
        }
        sp.edit().putString(KEY_GOODBYES, arr.toString()).apply()
    }

    fun addGoodbye(server: String, group: String, id: String, name: String) {
        setGoodbyes(goodbyes().filterNot { it.group == group && it.id == id } + Goodbye(server, group, id, name, System.currentTimeMillis()))
    }

    /** Joining a group again cancels a goodbye for it that has not been sent yet. */
    fun dropGoodbye(group: String, id: String) {
        val all = goodbyes()
        val kept = all.filterNot { it.group == group && it.id == id }
        if (kept.size != all.size) setGoodbyes(kept)
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

    fun loadDevices(): List<Device> = DeviceJson.decode(sp.getString(KEY_DEVICES, null))

    fun saveDevices(list: Collection<Device>) {
        sp.edit().putString(KEY_DEVICES, DeviceJson.encode(list)).apply()
    }

    /** Appearance: follow the system, or always light, or always dark. */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME, THEME_SYSTEM)
        set(v) = sp.edit().putInt(KEY_THEME, v).apply()

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
        private const val KEY_GOODBYES = "goodbyes"
        @Volatile private var cachedId: String? = null
        private const val KEY_NAME = "name"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DEVICES = "devices"
        private const val KEY_NAME_CHOSEN = "name_chosen"
        private const val KEY_UPDATE_JSON = "update_json"
        private const val KEY_UPDATE_CHECKED = "update_checked"
        private const val KEY_UPDATE_NOTIFIED = "update_notified"
        private const val KEY_LAST_MSG = "last_msg_id"
        private const val KEY_ALWAYS_ON = "always_on"
        private const val KEY_THEME = "theme"
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        private const val KEY_SENT_LEVEL = "sent_level"
        private const val KEY_SENT_CHARGING = "sent_charging"
        private const val KEY_SENT_AT = "sent_at"
        private const val KEY_LAST_MSG_GROUP = "last_msg_group"

    }
}

/** A "left the group" message waiting to be sent. */
data class Goodbye(val server: String, val group: String, val id: String, val name: String, val at: Long)
