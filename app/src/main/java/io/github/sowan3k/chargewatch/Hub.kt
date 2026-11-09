package io.github.sowan3k.chargewatch

import android.os.Handler
import android.os.Looper

/**
 * In-memory state shared by the service and the screen. Both run in one process.
 * All reads and writes happen on the main thread.
 */
object Hub {
    /** Other phones in the group, as last reported. */
    var others: List<Device> = emptyList()
        private set

    /** This phone, from the local battery broadcast. */
    var self: Device? = null
        private set

    var status: String = ""
        private set

    var connected: Boolean = false
        private set

    /** The newest release we know about, from version.json (cached across restarts). */
    var updateInfo: UpdateInfo? = null
        private set
    var updatePhase: UpdatePhase = UpdatePhase.IDLE
        private set
    var updateProgress: Int = 0
        private set
    var updateError: String? = null
        private set

    /** True while the main screen is on screen. The service stays live then. */
    var uiVisible: Boolean = false
        private set

    /** What the service exposes to the screen and to the poll alarm while it runs. */
    interface ServiceHooks {
        fun reevaluate()
        fun checkNow()
    }

    var service: ServiceHooks? = null

    fun setUiVisible(visible: Boolean) {
        if (visible == uiVisible) return
        uiVisible = visible
        service?.reevaluate()
    }

    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var posted = false

    fun setOthers(list: List<Device>) {
        others = list
        changed()
    }

    fun setSelf(d: Device?) {
        self = d
        changed()
    }

    fun setStatus(text: String, isConnected: Boolean) {
        if (text == status && isConnected == connected) return
        status = text
        connected = isConnected
        changed()
    }

    fun setUpdate(
        phase: UpdatePhase = updatePhase,
        progress: Int = updateProgress,
        error: String? = null,
        info: UpdateInfo? = updateInfo,
    ) {
        updatePhase = phase
        updateProgress = progress
        updateError = error
        updateInfo = info
        changed()
    }

    fun reset() {
        others = emptyList()
        self = null
        status = ""
        connected = false
        changed()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Coalesces bursts (for example a backlog replay) into one redraw. */
    private fun changed() {
        if (posted) return
        posted = true
        main.post {
            posted = false
            listeners.toList().forEach { it() }
        }
    }
}
