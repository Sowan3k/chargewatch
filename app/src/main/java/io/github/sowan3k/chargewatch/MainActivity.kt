package io.github.sowan3k.chargewatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.github.sowan3k.chargewatch.Ui.Companion.MATCH
import io.github.sowan3k.chargewatch.Ui.Companion.WRAP

/** The only screen. Built in code with the small design system in Ui.kt (no AndroidX, no XML layouts). */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
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
        ui = Ui(this)
        content = ui.column().apply { setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(32)) }
        setContentView(ScrollView(this).apply {
            background = ui.ambient()
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
        val group = prefs.group
        if (group == null) renderWelcome() else renderGroup(group)
    }

    private fun add(view: View, topDp: Int = 12) {
        val lp = (view.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = ui.dp(topDp)
        content.addView(view, lp)
    }

    private fun header(group: String?): View {
        val row = ui.row().apply { gravity = Gravity.TOP }
        val left = ui.column()
        left.addView(ui.pageTitle("ChargeWatch"))
        if (group != null) {
            val status = Hub.status
            val tone = when {
                Hub.connected -> Ui.Tone.OK
                status.isEmpty() || status.startsWith("Connecting") -> Ui.Tone.IDLE
                else -> Ui.Tone.AMBER
            }
            val pill = ui.pill(status.ifEmpty { "Starting…" }, tone)
            (pill.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(10)
            left.addView(pill)
        }
        row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(ui.iconButton(R.drawable.ic_settings, "Settings") { settings() })
        return row
    }

    private fun renderWelcome() {
        add(header(null), 0)
        add(ui.eyebrow("Battery alerts between your phones", ui.brass), 36)
        add(ui.headline("Know when your other phone is charged."), 10)
        add(
            ui.body(
                "Pair your phones with a QR code. While one charges, the other tells you when it " +
                    "reaches ${thresholdLabel()} and when it is full.",
                sp = 16f,
            ),
            14,
        )

        val steps = ui.card()
        steps.addView(ui.cardTitle("Pair your phones"))
        listOf(
            "On one phone, tap Create group. It shows a QR code.",
            "On the other phone, tap Scan QR code.",
            "Done. Each phone sees the other's battery and alerts you when it is charged.",
        ).forEachIndexed { i, step ->
            val row = ui.row().apply { gravity = Gravity.TOP }
            row.addView(
                ui.code("${i + 1}", 12f).apply {
                    gravity = Gravity.CENTER
                    background = ui.rounded(ui.quiet, 99, ui.border)
                },
                LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(12) },
            )
            row.addView(ui.body(step, ui.fg), LinearLayout.LayoutParams(0, WRAP, 1f))
            steps.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(14) })
        }
        add(steps, 24)

        add(ui.button("Create group", Ui.Style.PRIMARY) { createGroup() }, 20)
        add(ui.button("Scan QR code") { scan() }, 10)
        add(ui.button("Enter code", Ui.Style.GHOST) { enterCode() }, 4)
        add(
            ui.caption(
                "No account and no server. Phones talk through the free ntfy.sh relay. " +
                    "Anyone with a group's code can see the battery levels in it."
            ).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
            },
            16,
        )
    }

    private fun renderGroup(group: String) {
        add(header(group), 0)
        renderNotices()

        val now = System.currentTimeMillis() / 1000
        val others = Hub.others
        add(ui.eyebrow(if (others.size > 1) "Watching ${others.size} phones" else "Watching"), 28)
        if (others.isEmpty()) {
            val empty = ui.dashedCard()
            empty.addView(ui.cardTitle("No other phones yet").apply { gravity = Gravity.CENTER })
            empty.addView(
                ui.body("Tap Add a phone, then scan the code with ChargeWatch on your other phone.").apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(6), 0, 0)
                }
            )
            add(empty, 10)
        } else {
            others.forEachIndexed { i, d -> add(deviceCard(d, isSelf = false, now = now), if (i == 0) 10 else 12) }
        }

        Hub.self?.let {
            add(ui.eyebrow("This phone"), 24)
            add(deviceCard(it, isSelf = true, now = now), 10)
        }

        val actions = ui.row()
        actions.addView(
            ui.button("Add a phone", Ui.Style.PRIMARY) { showQr() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = ui.dp(6) },
        )
        actions.addView(
            ui.button("Scan QR code") { scan() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(6) },
        )
        add(actions, 24)

        val footer = ui.column()
        footer.addView(ui.eyebrow("Group"))
        footer.addView(ui.code(Pairing.pretty(group), 15f).apply {
            setTextIsSelectable(true)
            setPadding(0, ui.dp(4), 0, 0)
        })
        footer.addView(ui.caption("Alert at ${thresholdLabel()} · relay ${host(prefs.server)}").apply {
            setPadding(0, ui.dp(4), 0, 0)
        })
        add(footer, 28)
        add(ui.button("Leave group", Ui.Style.GHOST) { confirmLeave() }, 12)
    }

    private fun renderNotices() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && !nm.areNotificationsEnabled()) {
            add(
                ui.notice(
                    "Notifications are off",
                    "Turn them on so this phone can tell you when another phone is charged.",
                    "Turn on",
                ) { openNotificationSettings() },
                18,
            )
        }
        if (!isUnrestricted()) {
            val extra = if (isXiaomi()) " On Xiaomi phones, also turn on Autostart in Settings > Background and battery." else ""
            add(
                ui.notice(
                    "Battery optimization is on",
                    "Android may pause ChargeWatch in the background. Set battery use to Unrestricted so updates arrive on time.$extra",
                    "Allow",
                ) { requestUnrestricted() },
                18,
            )
        }
    }

    private fun deviceCard(d: Device, isSelf: Boolean, now: Long): View {
        val card = ui.card()
        val age = (now - d.time).coerceAtLeast(0)
        val stale = !isSelf && age > WatchService.STALE_SECONDS
        val low = !d.charging && d.level <= 15

        val top = ui.row()
        top.addView(
            ui.cardTitle(d.name).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = ui.dp(8) },
        )
        top.addView(
            when {
                stale -> ui.pill("Offline", Ui.Tone.AMBER)
                d.charging && d.level >= 100 -> ui.pill("Full", Ui.Tone.OK)
                d.charging -> ui.pill("Charging", Ui.Tone.OK)
                low -> ui.pill("Low", Ui.Tone.RED)
                else -> ui.pill("On battery", Ui.Tone.IDLE)
            }
        )
        card.addView(top)

        val figure = ui.row(baseline = true)
        figure.addView(ui.figure("${d.level}", 56f, if (stale) ui.muted else ui.fg))
        figure.addView(ui.figure("%", 26f, ui.muted).apply { setPadding(ui.dp(2), 0, 0, 0) })
        card.addView(figure, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ui.dp(6) })

        card.addView(
            BatteryBar(this, ui.track, ui.brass).apply {
                level = d.level
                fillColor = when {
                    stale -> ui.dotIdle
                    d.charging -> ui.brand
                    low -> ui.redText
                    else -> (ui.fg and 0x00FFFFFF) or 0x8C000000.toInt()
                }
                mark = if (isSelf) 0 else prefs.threshold
            },
            LinearLayout.LayoutParams(MATCH, ui.dp(14)).apply { topMargin = ui.dp(10) },
        )

        val bottom = ui.row()
        val left = when {
            isSelf -> "Tap to rename"
            stale -> "Last seen ${ago(age)}. It may be off or offline."
            else -> "Updated ${ago(age)}"
        }
        bottom.addView(ui.caption(left, if (stale) ui.amberText else ui.muted), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!isSelf && prefs.threshold > 0) {
            bottom.addView(ui.caption("Alert at ${prefs.threshold}%", ui.brass))
        }
        card.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) })

        if (isSelf) {
            card.isClickable = true
            card.setOnClickListener { editName() }
        } else {
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
        val size = minOf((resources.displayMetrics.widthPixels * 0.6).toInt(), ui.dp(260))
        val qr = FrameLayout(this).apply {
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            background = ui.rounded(Color.WHITE, 20, if (ui.dark) 0 else ui.border)
            addView(ImageView(this@MainActivity).apply { setImageBitmap(Qr.bitmap(payload, size)) }, FrameLayout.LayoutParams(size, size))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        val relay = if (prefs.server != Pairing.DEFAULT_SERVER) " This group uses the relay ${host(prefs.server)}." else ""
        Sheet(this, ui)
            .title("Add a phone")
            .text("Open ChargeWatch on your other phone, tap Scan QR code and point it here.")
            .add(qr, 20)
            .add(ui.code(Pairing.pretty(group), 20f).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.03f
                setTextIsSelectable(true)
            })
            .add(ui.caption("Or type this code there. Anyone with it can see this group's battery levels.$relay").apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
            }, 8)
            .action("Done", Ui.Style.PRIMARY)
            .action("Share code") {
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
        val input = ui.input("", "xxxx-xxxx-xxxx-xxxx-xxxx", monoFace = true)
        Sheet(this, ui)
            .title("Enter group code")
            .text("Type the code shown under the QR code on the other phone.")
            .add(input)
            .action("Join", Ui.Style.PRIMARY) { onInvite(input.text.toString()) }
            .action("Cancel", Ui.Style.GHOST)
            .showKeyboard()
            .show()
        input.requestFocus()
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
            Sheet(this, ui)
                .title("Join this group?")
                .text(notes.joinToString("\n\n"))
                .action("Join", Ui.Style.PRIMARY) { join(invite) }
                .action("Cancel", Ui.Style.GHOST)
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
        Sheet(this, ui)
            .title("Leave this group?")
            .text("This phone stops sharing its battery and stops getting alerts. The other phones remove it from their list. You can pair again any time.")
            .action("Leave", Ui.Style.DANGER) { leave() }
            .action("Cancel", Ui.Style.GHOST)
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
        Sheet(this, ui)
            .title("Remove ${d.name}?")
            .text("It disappears from this list. It comes back if it sends another update.")
            .action("Remove", Ui.Style.DANGER) { WatchService.forget(this, d.id) }
            .action("Cancel", Ui.Style.GHOST)
            .show()
    }

    // ---- Settings ----

    private fun settings() {
        val sheet = Sheet(this, ui).title("Settings")
        val list = ui.column()
        val rows = listOf(
            Triple("Alert level", thresholdLabel(), ::pickThreshold),
            Triple("This phone's name", prefs.name, ::editName),
            Triple("Relay server", host(prefs.server), ::editServer),
            Triple("Background and battery", if (isUnrestricted()) "Unrestricted" else "Battery optimization is on", ::batteryHelp),
            Triple("About", "Version ${BuildConfig.VERSION_NAME}", ::about),
        )
        rows.forEachIndexed { i, (label, value, open) ->
            if (i > 0) list.addView(ui.divider())
            list.addView(settingsRow(label, value) {
                sheet.dismiss()
                open()
            })
        }
        sheet.add(list, 12).action("Close", Ui.Style.GHOST).show()
    }

    private fun settingsRow(label: String, value: String, onClick: () -> Unit): View {
        val row = ui.row().apply {
            setPadding(0, ui.dp(14), 0, ui.dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val texts = ui.column()
        texts.addView(ui.body(label, ui.fg, 16f))
        texts.addView(ui.caption(value).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(ui.body("›", ui.muted, 24f))
        return row
    }

    private fun pickThreshold() {
        val sheet = Sheet(this, ui)
            .title("Alert level")
            .text("Get a notification when another phone reaches this level while charging. You always get one at 100%.")
        val grid = ui.column()
        THRESHOLDS.toList().chunked(3).forEachIndexed { r, chunk ->
            val row = ui.row()
            chunk.forEachIndexed { c, value ->
                val selected = value == prefs.threshold
                val chip = ui.button(if (value == 0) "Off" else "$value%", if (selected) Ui.Style.PRIMARY else Ui.Style.SECONDARY) {
                    prefs.threshold = value
                    sheet.dismiss()
                    render()
                }
                row.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (c > 0) marginStart = ui.dp(8)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (r > 0) topMargin = ui.dp(8) })
        }
        sheet.add(grid, 18).show()
    }

    private fun editName() {
        val input = ui.input(prefs.name, "Phone name").apply {
            filters = arrayOf(InputFilter.LengthFilter(Prefs.MAX_NAME))
        }
        Sheet(this, ui)
            .title("This phone's name")
            .text("Other phones show this name in their list and alerts.")
            .add(input)
            .action("Save", Ui.Style.PRIMARY) {
                prefs.name = input.text.toString()
                nameChanged()
            }
            .action("Use device name", Ui.Style.GHOST) {
                prefs.name = ""
                nameChanged()
            }
            .showKeyboard()
            .show()
    }

    private fun nameChanged() {
        if (prefs.group != null) WatchService.start(this, reload = true)
        render()
    }

    private fun editServer() {
        val input = ui.input(prefs.server, "https://ntfy.sh").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        Sheet(this, ui)
            .title("Relay server")
            .text(
                "Keep https://ntfy.sh to use the free public relay, or enter your own ntfy server. " +
                    "All phones in a group must use the same server. The QR code carries it, so pair again after you change it."
            )
            .add(input)
            .action("Save", Ui.Style.PRIMARY) {
                val server = Pairing.normalizeServer(input.text.toString())
                if (server == null) toast("Enter a URL that starts with https://") else setServer(server)
            }
            .action("Use ntfy.sh", Ui.Style.GHOST) { setServer(Pairing.DEFAULT_SERVER) }
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
            "Battery use is Unrestricted. Good."
        } else {
            "Battery optimization is on, so Android may pause ChargeWatch."
        }
        val sheet = Sheet(this, ui)
            .title("Background and battery")
            .text(
                "$state\n\nChargeWatch keeps one quiet connection open and sends a tiny update when the battery level changes. " +
                    "If updates stop arriving, set battery use to Unrestricted. Xiaomi, Oppo, Vivo, OnePlus and Huawei phones " +
                    "also need Autostart. Samsung phones must keep ChargeWatch out of Sleeping apps."
            )
            .action("Battery settings", Ui.Style.PRIMARY) { requestUnrestricted() }
        if (isXiaomi()) sheet.action("Autostart settings") { openAutostart() }
        sheet.action("Steps for my phone brand", Ui.Style.GHOST) { openUrl("https://dontkillmyapp.com/") }.show()
    }

    private fun about() {
        Sheet(this, ui)
            .title("ChargeWatch ${BuildConfig.VERSION_NAME}")
            .text(
                "Get a notification on one phone when your other phone is charged.\n\n" +
                    "Free and open source. Messages go through ntfy.sh, a free push relay. There is no account and no ChargeWatch server.\n\n" +
                    "Fonts: IBM Plex and Barlow, SIL Open Font License."
            )
            .action("Source code", Ui.Style.PRIMARY) { openUrl("https://github.com/Sowan3k/chargewatch") }
            .action("Close", Ui.Style.GHOST)
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

    private fun isXiaomi(): Boolean =
        listOf("xiaomi", "redmi", "poco").any {
            Build.MANUFACTURER.equals(it, ignoreCase = true) || Build.BRAND.equals(it, ignoreCase = true)
        }

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

    /** Xiaomi's Autostart list. Without Autostart, MIUI and HyperOS stop background apps. */
    private fun openAutostart() = tryStart(
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
    )

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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun host(server: String) = Uri.parse(server).host ?: server

    private fun thresholdLabel() = prefs.threshold.let { if (it == 0) "100% only" else "$it%" }

    companion object {
        private const val REQ_NOTIFICATIONS = 1
        private val THRESHOLDS = intArrayOf(0, 50, 60, 70, 75, 80, 85, 90, 95)
    }
}
