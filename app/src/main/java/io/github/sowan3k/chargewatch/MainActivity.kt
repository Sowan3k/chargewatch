package io.github.sowan3k.chargewatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** The only screen. The UI is built in code to keep the APK small (no AndroidX, no XML layouts). */
class MainActivity : Activity() {

    private class Palette(
        val bg: Int, val card: Int, val text: Int, val sub: Int,
        val accent: Int, val onAccent: Int, val warn: Int, val track: Int, val low: Int, val bar: Int,
    )

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var c: Palette
    private val main = Handler(Looper.getMainLooper())
    private val hubListener: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            render() // refreshes the "updated 3 min ago" labels
            main.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        c = if (dark) {
            Palette(
                bg = 0xFF0F1316.toInt(), card = 0xFF1B2126.toInt(), text = 0xFFE8EAED.toInt(),
                sub = 0xFF9AA4AE.toInt(), accent = 0xFF66BB6A.toInt(), onAccent = 0xFF0B1A0C.toInt(),
                warn = 0xFFFFB74D.toInt(), track = 0xFF2C343B.toInt(), low = 0xFFEF5350.toInt(),
                bar = 0xFF90A4AE.toInt(),
            )
        } else {
            Palette(
                bg = 0xFFF3F5F7.toInt(), card = Color.WHITE, text = 0xFF1B1F23.toInt(),
                sub = 0xFF5F6B76.toInt(), accent = 0xFF2E7D32.toInt(), onAccent = Color.WHITE,
                warn = 0xFFB45309.toInt(), track = 0xFFE3E7EB.toInt(), low = 0xFFD32F2F.toInt(),
                bar = 0xFF607D8B.toInt(),
            )
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(c.bg)
            isFillViewport = true
            addView(content)
        })
        WatchService.createChannels(this)
        if (prefs.group != null) {
            WatchService.start(this)
            askNotificationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        Hub.addListener(hubListener)
        main.post(ticker)
    }

    override fun onPause() {
        Hub.removeListener(hubListener)
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    // ---- Screens ----

    private fun render() {
        content.removeAllViews()
        content.addView(text("ChargeWatch", 26f, c.text, bold = true))
        val group = prefs.group
        val status = when {
            group == null -> "Not paired yet"
            Hub.status.isEmpty() -> "Starting…"
            else -> Hub.status
        }
        content.addView(
            text(status, 14f, if (group == null || Hub.connected) c.sub else c.warn).apply {
                setPadding(0, dp(2), 0, dp(14))
            }
        )
        if (group == null) {
            renderUnpaired()
        } else {
            renderWarnings()
            renderPaired(group)
        }
    }

    private fun renderUnpaired() {
        val card = card()
        card.addView(text("Pair your phones", 20f, c.text, bold = true))
        card.addView(
            text(
                "1. On one phone, tap Create group. It shows a QR code.\n" +
                    "2. On the other phone, tap Scan QR code.\n" +
                    "3. Done. Each phone now sees the other's battery and alerts you when it is charged.",
                15f, c.sub,
            ).apply {
                setPadding(0, dp(8), 0, dp(8))
                setLineSpacing(0f, 1.25f)
            }
        )
        card.addView(button("Create group", primary = true) { createGroup() })
        card.addView(button("Scan QR code") { scan() })
        card.addView(button("Enter code") { enterCode() })
        content.addView(card)
        content.addView(button("Settings") { settings() })
        content.addView(
            text(
                "No account needed. Phones talk through the free ntfy.sh relay. " +
                    "Anyone with a group's QR code can see the battery levels of the phones in it.",
                13f, c.sub,
            ).apply { setPadding(dp(4), dp(14), dp(4), 0) }
        )
    }

    private fun renderWarnings() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && !nm.areNotificationsEnabled()) {
            content.addView(
                banner(
                    "Notifications are off",
                    "Turn them on so this phone can tell you when another phone is charged.",
                    "Turn on",
                ) { openNotificationSettings() }
            )
        }
        if (!isUnrestricted()) {
            content.addView(
                banner(
                    "Battery optimization is on",
                    "Android may pause ChargeWatch in the background. Set battery usage to Unrestricted so updates and alerts arrive on time.",
                    "Allow",
                ) { requestUnrestricted() }
            )
        }
    }

    private fun renderPaired(group: String) {
        val now = System.currentTimeMillis() / 1000
        val others = Hub.others
        if (others.isEmpty()) {
            content.addView(card().apply {
                addView(text("No other phones yet", 17f, c.text, bold = true))
                addView(
                    text(
                        "Tap Show QR code, then scan it with ChargeWatch on your other phone.",
                        15f, c.sub,
                    ).apply { setPadding(0, dp(6), 0, 0) }
                )
            })
        } else {
            others.forEach { content.addView(deviceCard(it, isSelf = false, now = now)) }
        }
        Hub.self?.let { content.addView(deviceCard(it, isSelf = true, now = now)) }

        content.addView(row().apply {
            addView(button("Show QR code", primary = true) { showQr() }, half(end = true))
            addView(button("Scan QR code") { scan() }, half(end = false))
        })
        content.addView(row().apply {
            addView(button("Settings") { settings() }, half(end = true))
            addView(button("Leave group") { confirmLeave() }, half(end = false))
        })
        content.addView(
            text(
                "Group ${Pairing.pretty(group)} · alert at ${thresholdLabel()} · relay ${host(prefs.server)}",
                12f, c.sub,
            ).apply { setPadding(dp(4), dp(14), dp(4), 0) }
        )
    }

    private fun deviceCard(d: Device, isSelf: Boolean, now: Long): View {
        val card = card()
        card.addView(row().apply {
            addView(
                text(d.name, 18f, c.text, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            if (isSelf) addView(text("This phone", 13f, c.sub))
        })
        card.addView(row().apply {
            gravity = Gravity.BOTTOM
            addView(text("${d.level}%", 34f, c.text, bold = true))
            val state = when {
                d.charging && d.level >= 100 -> "Full, on charger"
                d.charging -> "Charging"
                else -> "On battery"
            }
            addView(
                text(state, 15f, if (d.charging) c.accent else c.sub).apply {
                    setPadding(dp(10), 0, 0, dp(7))
                }
            )
        })
        card.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = d.level
                val color = when {
                    d.charging -> c.accent
                    d.level <= 15 -> c.low
                    else -> c.bar
                }
                progressTintList = ColorStateList.valueOf(color)
                progressBackgroundTintList = ColorStateList.valueOf(c.track)
            },
            LinearLayout.LayoutParams(MATCH, dp(8)).apply { topMargin = dp(4) },
        )
        if (!isSelf) {
            val age = (now - d.time).coerceAtLeast(0)
            val stale = age > WatchService.STALE_SECONDS
            val label = if (stale) "Last seen ${ago(age)}. It may be off or offline." else "Updated ${ago(age)}"
            card.addView(text(label, 13f, if (stale) c.warn else c.sub).apply { setPadding(0, dp(6), 0, 0) })
            card.setOnLongClickListener {
                confirmForget(d)
                true
            }
        }
        return card
    }

    // ---- Pairing ----

    private fun createGroup() {
        prefs.leave()
        prefs.group = Pairing.newGroupId()
        startWatching()
        showQr()
    }

    private fun startWatching() {
        Hub.setOthers(emptyList())
        WatchService.start(this, reload = true)
        askNotificationPermission()
        render()
    }

    private fun showQr() {
        val group = prefs.group ?: return
        val payload = Pairing.encode(group, prefs.server)
        val size = minOf((resources.displayMetrics.widthPixels * 0.7).toInt(), dp(300))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        layout.addView(
            ImageView(this).apply { setImageBitmap(Qr.bitmap(payload, size)) },
            LinearLayout.LayoutParams(size, size),
        )
        layout.addView(
            TextView(this).apply {
                text = Pairing.pretty(group)
                textSize = 18f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextIsSelectable(true)
                setPadding(0, dp(12), 0, dp(4))
            }
        )
        val relayNote = if (prefs.server != Pairing.DEFAULT_SERVER) "\nRelay: ${host(prefs.server)}" else ""
        layout.addView(
            TextView(this).apply {
                text = "Scan this with ChargeWatch on your other phone, or type the code there. " +
                    "Anyone with this code can see this group's battery levels.$relayNote"
                textSize = 14f
                gravity = Gravity.CENTER
            }
        )
        AlertDialog.Builder(this)
            .setTitle("Pair another phone")
            .setView(layout)
            .setPositiveButton("Done", null)
            .setNeutralButton("Share code") { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "ChargeWatch group code")
                    .putExtra(Intent.EXTRA_TEXT, payload)
                startActivity(Intent.createChooser(send, "Share group code"))
            }
            .show()
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> onInvite(barcode.rawValue) }
            .addOnFailureListener {
                // Usually Google Play services is still downloading the scanner, or is missing.
                toast("The scanner is not ready yet. Try again in a minute, or type the code.")
                enterCode()
            }
    }

    private fun enterCode() {
        val input = EditText(this).apply {
            hint = "xxxx-xxxx-xxxx-xxxx-xxxx"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Enter group code")
            .setMessage("Type the code shown under the QR code on the other phone.")
            .setView(padded(input))
            .setPositiveButton("Join") { _, _ -> onInvite(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onInvite(raw: String?) {
        val invite = raw?.let { Pairing.parse(it) }
        if (invite == null) {
            toast("That is not a ChargeWatch group code.")
            return
        }
        val current = prefs.group
        if (current == invite.groupId && prefs.server == invite.server) {
            toast("This phone is already in that group.")
            return
        }
        val notes = mutableListOf<String>()
        if (current != null) notes += "This phone will leave its current group."
        if (invite.server != Pairing.DEFAULT_SERVER) notes += "This group uses its own relay server: ${host(invite.server)}."
        if (notes.isEmpty()) {
            join(invite)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Join this group?")
                .setMessage(notes.joinToString("\n\n"))
                .setPositiveButton("Join") { _, _ -> join(invite) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun join(invite: Invite) {
        if (prefs.group != null) sendLeave()
        prefs.leave()
        prefs.server = invite.server
        prefs.group = invite.groupId
        startWatching()
        toast("Joined. Phones in the group show up within a few seconds.")
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setTitle("Leave this group?")
            .setMessage(
                "This phone stops sharing its battery and stops getting alerts. " +
                    "The other phones remove it from their list. You can pair again any time."
            )
            .setPositiveButton("Leave") { _, _ -> leave() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun leave() {
        sendLeave()
        WatchService.stop(this)
        prefs.leave()
        Hub.reset()
        getSystemService(NotificationManager::class.java)?.cancelAll()
        render()
    }

    /** Tells the other phones to drop this one from their list. Best effort. */
    private fun sendLeave() {
        val group = prefs.group ?: return
        val server = prefs.server
        val body = Relay.leaveReport(prefs.deviceId, prefs.name)
        Thread { runCatching { Relay.publish(server, Pairing.topic(group), body) } }.start()
    }

    private fun confirmForget(d: Device) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${d.name}?")
            .setMessage("It disappears from this list. It comes back if it sends another update.")
            .setPositiveButton("Remove") { _, _ -> WatchService.forget(this, d.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Settings ----

    private fun settings() {
        val items = arrayOf(
            "Alert level: ${thresholdLabel()}",
            "This phone's name: ${prefs.name}",
            "Relay server: ${host(prefs.server)}",
            "Background and battery help",
            "About ChargeWatch",
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickThreshold()
                    1 -> editName()
                    2 -> editServer()
                    3 -> batteryHelp()
                    4 -> about()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickThreshold() {
        val labels = THRESHOLDS.map { if (it == 0) "Off (only alert when full)" else "$it%" }.toTypedArray()
        val selected = THRESHOLDS.indexOf(prefs.threshold).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Alert me when another phone reaches")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                prefs.threshold = THRESHOLDS[which]
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editName() {
        val input = EditText(this).apply {
            setText(prefs.name)
            setSelection(text.length)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(Prefs.MAX_NAME))
        }
        AlertDialog.Builder(this)
            .setTitle("This phone's name")
            .setMessage("Other phones show this name in their list and alerts.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                prefs.name = input.text.toString()
                nameChanged()
            }
            .setNeutralButton("Use model name") { _, _ ->
                prefs.name = ""
                nameChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun nameChanged() {
        if (prefs.group != null) WatchService.start(this, reload = true)
        render()
    }

    private fun editServer() {
        val input = EditText(this).apply {
            setText(prefs.server)
            setSelection(text.length)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Relay server")
            .setMessage(
                "Keep https://ntfy.sh to use the free public relay, or enter your own ntfy server. " +
                    "All phones in a group must use the same server. The QR code carries it, " +
                    "so pair again after you change it."
            )
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                val server = Pairing.normalizeServer(input.text.toString())
                if (server == null) toast("Enter a URL that starts with https://") else setServer(server)
            }
            .setNeutralButton("Use ntfy.sh") { _, _ -> setServer(Pairing.DEFAULT_SERVER) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setServer(server: String) {
        if (server == prefs.server) return
        prefs.server = server
        if (prefs.group != null) {
            WatchService.start(this, reload = true)
            toast("Saved. Show the QR code again and rescan it on your other phones.")
        }
        render()
    }

    private fun batteryHelp() {
        val state = if (isUnrestricted()) {
            "Battery usage is Unrestricted. Good.\n\n"
        } else {
            "Battery optimization is on, so Android may pause ChargeWatch.\n\n"
        }
        AlertDialog.Builder(this)
            .setTitle("Background and battery")
            .setMessage(
                state +
                    "ChargeWatch keeps one quiet connection open and sends a tiny update when the battery level changes.\n\n" +
                    "If updates stop arriving:\n" +
                    "• Set battery usage to Unrestricted.\n" +
                    "• Samsung: keep ChargeWatch out of Sleeping apps and Deep sleeping apps.\n" +
                    "• Xiaomi, Oppo, Vivo, OnePlus, Huawei: turn on Autostart and lock the app in Recents.\n\n" +
                    "dontkillmyapp.com has steps for each phone brand."
            )
            .setPositiveButton("Battery settings") { _, _ -> requestUnrestricted() }
            .setNeutralButton("dontkillmyapp.com") { _, _ -> openUrl("https://dontkillmyapp.com/") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("ChargeWatch ${BuildConfig.VERSION_NAME}")
            .setMessage(
                "Get a notification on one phone when your other phone is charged.\n\n" +
                    "Free and open source. Messages go through ntfy.sh, a free push relay. " +
                    "There is no account and no ChargeWatch server."
            )
            .setPositiveButton("Source code") { _, _ -> openUrl("https://github.com/Sowan3k/chargewatch") }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---- System settings ----

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        tryStart(intent, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun isUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    /** Asks Android to exempt the app from battery optimization ("Unrestricted"). */
    @SuppressLint("BatteryLife")
    private fun requestUnrestricted() {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        if (isUnrestricted()) {
            tryStart(details)
        } else {
            tryStart(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                details,
            )
        }
    }

    private fun openUrl(url: String) = tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun tryStart(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Try the next one.
            }
        }
        toast("Could not open settings on this phone.")
    }

    // ---- Small view helpers ----

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun half(end: Boolean) = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
        topMargin = dp(8)
        if (end) marginEnd = dp(6) else marginStart = dp(6)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(c.card)
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    private fun banner(title: String, body: String, action: String, onClick: () -> Unit) = card().apply {
        addView(text(title, 16f, c.warn, bold = true))
        addView(text(body, 14f, c.sub).apply { setPadding(0, dp(4), 0, 0) })
        addView(button(action, primary = true, onClick = onClick))
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        minHeight = dp(48)
        setTextColor(if (primary) c.onAccent else c.accent)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (primary) {
                setColor(c.accent)
            } else {
                setColor(c.card)
                setStroke(dp(1), c.accent)
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33808080), shape, null)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun padded(view: View) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(view)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun host(server: String) = Uri.parse(server).host ?: server

    private fun thresholdLabel() = prefs.threshold.let { if (it == 0) "100% only" else "$it%" }

    companion object {
        private const val REQ_NOTIFICATIONS = 1
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val THRESHOLDS = intArrayOf(0, 50, 60, 70, 75, 80, 85, 90, 95)
    }
}
