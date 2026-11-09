package io.github.sowan3k.chargewatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the service after a reboot or an app update, if this phone is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs(context).group == null) return
        try {
            WatchService.start(context)
        } catch (e: Exception) {
            // Some vendors still block background starts. The service starts again
            // the next time the app is opened.
        }
    }
}
