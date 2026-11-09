package io.github.sowan3k.chargewatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or an app update: re-arm the background jobs and run one check. The check
 * starts the live service if a phone is charging (allowed right after boot).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs(context).group == null) return
        val result = goAsync()
        Thread {
            try {
                Background.check(context.applicationContext)
            } catch (e: Exception) {
                // The periodic job runs again in a few minutes.
            } finally {
                result.finish()
            }
        }.start()
    }
}
