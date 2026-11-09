package io.github.sowan3k.chargewatch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What the "latest" GitHub release says about itself (its version.json). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val notes: String,
    /** Total downloads of the app from GitHub releases, counted by GitHub. */
    val downloads: Long,
    /** GitHub stars on the repository. */
    val stars: Int,
) {
    val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
}

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, INSTALLING, FAILED }

/**
 * In-app updates from GitHub releases, with no store and no extra library.
 *
 * Every release carries a small version.json next to ChargeWatch.apk. The app reads it,
 * downloads the APK, checks its SHA-256, and hands it to Android's PackageInstaller.
 * Android then asks the user to confirm, and only installs it if it is signed with the
 * same key as the installed app.
 */
object Updater {
    const val VERSION_URL = "https://github.com/Sowan3k/chargewatch/releases/download/latest/version.json"
    const val REPO_URL = "https://github.com/Sowan3k/chargewatch"
    private const val ALLOWED_PREFIX = "https://github.com/Sowan3k/chargewatch/releases/download/"

    fun parse(json: String): UpdateInfo? = try {
        val o = JSONObject(json)
        val url = o.optString("url")
        val sha = o.optString("sha256").lowercase()
        if (!url.startsWith(ALLOWED_PREFIX) || !Regex("^[0-9a-f]{64}$").matches(sha)) {
            null
        } else {
            UpdateInfo(
                versionCode = o.getInt("versionCode"),
                versionName = o.optString("versionName", "?"),
                url = url,
                sha256 = sha,
                size = o.optLong("size"),
                notes = o.optString("notes").take(300),
                downloads = o.optLong("downloads"),
                stars = o.optInt("stars"),
            )
        }
    } catch (e: Exception) {
        null
    }

    fun toJson(i: UpdateInfo): String = JSONObject()
        .put("versionCode", i.versionCode)
        .put("versionName", i.versionName)
        .put("url", i.url)
        .put("sha256", i.sha256)
        .put("size", i.size)
        .put("notes", i.notes)
        .put("downloads", i.downloads)
        .put("stars", i.stars)
        .toString()

    /** Fetches version.json. Blocking: call it off the main thread. */
    fun fetch(): UpdateInfo {
        val c = URL(VERSION_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "ChargeWatch/${BuildConfig.VERSION_NAME}")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.use { it.readBytes().take(64 * 1024).toByteArray() }.toString(Charsets.UTF_8)
            return parse(body) ?: throw IOException("Bad version file")
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the APK into the cache and checks its SHA-256. Blocking. */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "ChargeWatch-${info.versionCode}.apk")
        val c = URL(info.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "ChargeWatch/${BuildConfig.VERSION_NAME}")
            if (c.responseCode != 200) throw IOException("Download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != info.sha256) {
                file.delete()
                throw IOException("The download did not match the release. Try again.")
            }
            return file
        } finally {
            c.disconnect()
        }
    }

    /** Hands the APK to PackageInstaller. [UpdateReceiver] gets the result. Blocking (copies the file). */
    fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("ChargeWatch.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, UpdateReceiver::class.java).setPackage(context.packageName),
                flags,
            )
            session.commit(callback.intentSender)
        }
    }
}

/** Receives PackageInstaller's answer: show the system's confirm screen, or report a failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    Hub.setUpdate(UpdatePhase.FAILED, error = "Android did not show the installer. Try again.")
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Hub.setUpdate(UpdatePhase.FAILED, error = "Open ChargeWatch and tap Update again.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Android replaces this process with the new version.
            }
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Hub.setUpdate(UpdatePhase.FAILED, error = "Update cancelled.")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Hub.setUpdate(UpdatePhase.FAILED, error = "Install failed" + (msg?.let { ": $it" } ?: "."))
            }
        }
    }
}
