package io.github.sowan3k.chargewatch

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The only screen. Built in code with the small design system in Ui.kt (no AndroidX, no XML
 * layouts), over the living monogram background from Motion.kt.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var root: FrameLayout
    private lateinit var backdrop: MonogramView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val hubListener: () -> Unit = { renderSoon() }
    private val ticker = object : Runnable {
        override fun run() {
            renderSoon() // refreshes the "updated 3 min ago" labels
            main.postDelayed(this, 30_000)
        }
    }
    private val deferredRender = Runnable { render() }

    private var motionOn = true
    private var lastScreen: String? = null
    private var entering = false
    private var entryUntil = 0L
    private var themeSwitching = false
    private var introPlaying = false

    /** Level animations by device id. They outlive the cards, which are rebuilt on every change. */
    private val levelAnims = HashMap<String, LevelAnim>()
    private val shownDevices = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        motionOn = Motion.enabled(this)
        ui = Ui(this, wantDark(), motionOn)
        content = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(32))
            clipChildren = false // the first screen's battery sends its rings past its own edges
        }
        backdrop = MonogramView(this).apply {
            setPalette(ui)
            animate = motionOn
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            // The pattern behind slides a little as the page scrolls.
            setOnScrollChangeListener { _, _, y, _, _ -> backdrop.scrollOffset = y }
        }
        root = FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setContentView(root)
        // A fresh start opens with the logo. Not after a rotation, and not with animations off.
        if (savedInstanceState == null && motionOn) playIntro()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) edgeToEdge()
        applyBars()
        Notifier.createChannels(this)
        if (Hub.updateInfo == null) prefs.cachedUpdate?.let(Updater::parse)?.let { Hub.setUpdate(info = it) }
        if (prefs.group != null) {
            WatchService.start(this)
            askNotificationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // Battery Saver or the system's animation setting may have changed while we were away.
        val motion = Motion.enabled(this)
        if (motion != motionOn) {
            motionOn = motion
            ui = Ui(this, ui.dark, motion)
            backdrop.animate = motion
        }
        Hub.addListener(hubListener)
        Hub.setUiVisible(true) // the service goes live while the screen is open
        if (prefs.group != null) WatchService.start(this)
        main.post(ticker)
        if (System.currentTimeMillis() - prefs.lastUpdateCheck > UPDATE_CHECK_MS) checkForUpdate(manual = false)
    }

    override fun onPause() {
        Hub.removeListener(hubListener)
        Hub.setUiVisible(false)
        main.removeCallbacks(ticker)
        main.removeCallbacks(deferredRender)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    /** Every touch also reaches the background: its light follows the finger. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        backdrop.onFinger(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** The opening animation, on top of everything. The screen makes its entrance as it opens. */
    private fun playIntro() {
        introPlaying = true
        var intro: IntroView? = null
        intro = IntroView(
            this, ui,
            onReveal = {
                introPlaying = false
                render()
            },
            onDone = { intro?.let(root::removeView) },
        )
        root.addView(intro, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---- Window, theme ----

    /** The background runs under the status and navigation bars. The content keeps clear of them. */
    @TargetApi(30)
    private fun edgeToEdge() {
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
            content.setPadding(
                ui.dp(20) + bars.left,
                ui.dp(18) + bars.top,
                ui.dp(20) + bars.right,
                ui.dp(32) + if (keyboard > 0) 0 else bars.bottom,
            )
            // The keyboard shortens the scrolling area, which keeps the focused field in view.
            val lp = scroll.layoutParams as FrameLayout.LayoutParams
            if (lp.bottomMargin != keyboard) {
                lp.bottomMargin = keyboard
                scroll.layoutParams = lp
            }
            insets
        }
    }

    /** Window colour and the status bar's icon colour follow the app's theme, not the system's. */
    private fun applyBars() {
        window.setBackgroundDrawable(ColorDrawable(ui.bg))
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (ui.dark) 0 else light, light)
        } else {
            window.statusBarColor = ui.bg
            window.navigationBarColor = ui.bg
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (ui.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun wantDark(): Boolean = when (prefs.themeMode) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Changes the theme in place. A picture of the old look stays on top for a moment and
     * shrinks away into the button that was tapped.
     */
    private fun switchTheme(mode: Int, from: View?) {
        if (themeSwitching) return
        prefs.themeMode = mode
        val dark = wantDark()
        if (dark == ui.dark) {
            render()
            return
        }
        var old: ImageView? = null
        if (ui.motion && root.width > 0 && root.height > 0) {
            try {
                val shot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(shot))
                old = ImageView(this).apply {
                    setImageBitmap(shot)
                    scaleType = ImageView.ScaleType.FIT_XY
                }
            } catch (e: OutOfMemoryError) {
                old = null
            }
        }
        var cx = root.width / 2
        var cy = 0
        if (from != null) {
            val a = IntArray(2)
            val b = IntArray(2)
            from.getLocationInWindow(a)
            root.getLocationInWindow(b)
            cx = a[0] - b[0] + from.width / 2
            cy = a[1] - b[1] + from.height / 2
        }

        ui = Ui(this, dark, motionOn)
        backdrop.setPalette(ui)
        applyBars()
        render()

        val cover = old ?: return
        themeSwitching = true
        root.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        cover.post {
            val radius = hypot(maxOf(cx, root.width - cx).toDouble(), maxOf(cy, root.height - cy).toDouble()).toFloat()
            val shrink = ViewAnimationUtils.createCircularReveal(cover, cx, cy, radius, 0f)
            shrink.duration = 520
            shrink.interpolator = DecelerateInterpolator()
            shrink.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.removeView(cover)
                    themeSwitching = false
                }
            })
            shrink.start()
        }
    }

    // ---- Screens ----

    /** A redraw asked for by new data. It waits until a screen's entrance has finished. */
    private fun renderSoon() {
        val wait = entryUntil - SystemClock.uptimeMillis()
        if (wait > 0) {
            main.removeCallbacks(deferredRender)
            main.postDelayed(deferredRender, wait)
        } else {
            render()
        }
    }

    private fun render() {
        main.removeCallbacks(deferredRender)
        content.removeAllViews()
        val group = prefs.group
        val screen = when {
            group != null -> "group"
            !prefs.nameChosen -> "name"
            else -> "pair"
        }
        // While the opening plays the screen is built but hidden. Its entrance waits for the reveal.
        entering = screen != lastScreen && !introPlaying
        lastScreen = if (introPlaying) null else screen
        if (group == null) renderWelcome() else renderGroup(group)
        if (entering) {
            // A new screen starts at the top, and its parts rise in one after the other.
            scroll.scrollTo(0, 0)
            if (ui.motion) {
                val parts = (0 until content.childCount).map { content.getChildAt(it) }
                entryUntil = SystemClock.uptimeMillis() + Motion.riseIn(parts, ui.dpf(18f))
            }
        }
        entering = false
    }

    private fun add(view: View, topDp: Int = 12) {
        val lp = (view.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = ui.dp(topDp)
        content.addView(view, lp)
    }

    /** The level animation for a device: it carries on across redraws and restarts on a new level. */
    private fun glide(id: String, level: Int): LevelAnim {
        val now = SystemClock.uptimeMillis()
        val old = levelAnims[id]
        if (old != null && old.to == level) return old
        val next = if (ui.motion) LevelAnim(old?.value(now)?.roundToInt() ?: 0, level, now) else LevelAnim.still(level)
        levelAnims[id] = next
        return next
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
            val pill = ui.pill(status.ifEmpty { "Starting…" }, tone, live = Hub.connected)
            (pill.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(10)
            left.addView(pill)
        }
        row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(
            ui.iconButton(
                if (ui.dark) R.drawable.ic_sun else R.drawable.ic_moon,
                if (ui.dark) "Switch to light mode" else "Switch to dark mode",
            ) { switchTheme(if (ui.dark) Prefs.THEME_LIGHT else Prefs.THEME_DARK, it) }
        )
        row.addView(
            ui.iconButton(R.drawable.ic_settings, "Settings") {
                it.animate().rotationBy(90f).setStartDelay(0).setDuration(450)
                    .setInterpolator(DecelerateInterpolator()).start()
                settings()
            },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginStart = ui.dp(8) },
        )
        return row
    }

    private fun renderWelcome() {
        add(header(null), 0)
        updateCard()?.let { add(it, 18) }
        if (!prefs.nameChosen) renderNameStep() else renderPairStep()
    }

    /** Onboarding step 1: name this phone, or take the name from the system. */
    private fun renderNameStep() {
        add(HeroBattery(this, ui).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(124), ui.dp(84)) }, 14)
        add(ui.eyebrow("Battery alerts between your phones", ui.brass), 8)
        add(ui.headline("Know when your other phone is charged."), 10)
        add(
            ui.body(
                "Pair your phones with a QR code. While one charges, the other tells you when it " +
                    "reaches ${thresholdLabel()} and when it is full.",
                sp = 16f,
            ),
            14,
        )

        val card = ui.card()
        card.addView(ui.eyebrow("Step 1 of 2", ui.brass))
        card.addView(ui.cardTitle("Name this phone").apply { setPadding(0, ui.dp(4), 0, 0) })
        card.addView(ui.body("Your other phones show this name in their list and alerts.").apply { setPadding(0, ui.dp(4), 0, 0) })
        val input = ui.input("", "For example, Work phone").apply {
            filters = arrayOf(InputFilter.LengthFilter(Prefs.MAX_NAME))
        }
        card.addView(input, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(14) })
        val system = prefs.defaultName()
        val suggest = ui.row()
        suggest.addView(ui.button("Use system name") {
            input.setText(system)
            input.setSelection(input.text.length)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        suggest.addView(
            ui.caption(system).apply {
                setPadding(ui.dp(12), 0, 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        card.addView(suggest, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(10) })
        card.addView(
            ui.button("Continue", Ui.Style.PRIMARY) { saveName(input.text.toString(), system) },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) },
        )
        add(card, 24)
        add(trustLine(), 18)
    }

    private fun saveName(typed: String, system: String) {
        val name = typed.trim()
        // An empty name, or the system name, keeps following the system name.
        prefs.name = if (name.isEmpty() || name == system) "" else name
        prefs.nameChosen = true
        if (prefs.group != null) WatchService.start(this, reload = true)
        render()
    }

    /** Onboarding step 2: create or join a group. */
    private fun renderPairStep() {
        add(ui.eyebrow("Step 2 of 2", ui.brass), 32)
        add(ui.headline("Pair your phones"), 10)
        val who = ui.row()
        who.addView(ui.body("This phone is called ${prefs.name}.", sp = 16f), LinearLayout.LayoutParams(0, WRAP, 1f))
        who.addView(ui.button("Change", Ui.Style.GHOST) {
            prefs.nameChosen = false
            render()
        })
        add(who, 6)

        val steps = ui.card()
        steps.addView(ui.cardTitle("How it works"))
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
        add(steps, 18)

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
        add(trustLine(), 10)
    }

    /**
     * Public numbers people can check on GitHub: downloads (counted by GitHub across all
     * releases) and stars. There is no server, so the app cannot count users, and it does
     * not pretend to. Small numbers are left out until they mean something.
     */
    private fun trustLine(): View {
        val info = Hub.updateInfo
        val parts = mutableListOf("Free and open source")
        if (info != null && info.downloads >= SOCIAL_PROOF_MIN) parts += "%,d downloads".format(info.downloads)
        if (info != null && info.stars >= SOCIAL_PROOF_MIN) parts += "★ ${info.stars} on GitHub"
        parts += "Version ${BuildConfig.VERSION_NAME}"
        return ui.caption(parts.joinToString(" · ")).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setPadding(0, ui.dp(6), 0, ui.dp(6))
            isClickable = true
            setOnClickListener { openUrl(Updater.REPO_URL) }
        }
    }

    /** Shown at the top when GitHub has a newer build than this one. */
    private fun updateCard(): View? {
        val info = Hub.updateInfo ?: return null
        if (!info.isNewer) return null
        val card = ui.card()
        card.addView(ui.eyebrow("Update available", ui.brass))
        card.addView(ui.cardTitle("ChargeWatch ${info.versionName}").apply { setPadding(0, ui.dp(4), 0, 0) })
        if (info.notes.isNotBlank()) {
            card.addView(ui.body(info.notes).apply {
                setPadding(0, ui.dp(4), 0, 0)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        when (Hub.updatePhase) {
            UpdatePhase.DOWNLOADING -> {
                card.addView(
                    BatteryBar(this, ui.track, ui.brass).apply {
                        level = Hub.updateProgress.coerceAtLeast(1)
                        fillColor = ui.brand
                        shimmer = ui.motion
                    },
                    LinearLayout.LayoutParams(MATCH, ui.dp(14)).apply { topMargin = ui.dp(14) },
                )
                card.addView(ui.caption("Downloading… ${Hub.updateProgress}%").apply { setPadding(0, ui.dp(6), 0, 0) })
            }
            UpdatePhase.INSTALLING -> {
                card.addView(ui.caption("Android's installer opens next. Tap Update there.").apply { setPadding(0, ui.dp(10), 0, 0) })
                card.addView(ui.button("Try again", Ui.Style.GHOST) { startUpdate(info) },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(6) })
            }
            else -> {
                Hub.updateError?.let { card.addView(ui.caption(it, ui.amberText).apply { setPadding(0, ui.dp(10), 0, 0) }) }
                card.addView(
                    ui.button(if (Hub.updatePhase == UpdatePhase.FAILED) "Try again" else "Update now", Ui.Style.PRIMARY) {
                        startUpdate(info)
                    },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(14) },
                )
                val size = if (info.size > 0) " %.1f MB.".format(info.size / 1_048_576.0) else ""
                card.addView(ui.caption("Installs over this version. Your group and settings stay.$size").apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(8), 0, 0)
                })
            }
        }
        return card
    }

    private fun renderGroup(group: String) {
        add(header(group), 0)
        updateCard()?.let { add(it, 18) }
        renderNotices()
        if (!prefs.nameChosen) {
            val card = ui.card()
            card.addView(ui.eyebrow("Name this phone", ui.brass))
            card.addView(
                ui.body("Your other phones see it as \u201c${prefs.name}\u201d. Pick a name you will recognize.", ui.fg).apply {
                    setPadding(0, ui.dp(4), 0, 0)
                }
            )
            card.addView(ui.button("Set name", Ui.Style.PRIMARY) { editName() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(12) })
            add(card, 18)
        }

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
        add(trustLine(), 4)
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
        val card = ui.card(tappable = true)
        val age = (now - d.time).coerceAtLeast(0)
        val stale = !isSelf && Tracker.isStale(d, now)
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
                d.charging -> ui.pill("Charging", Ui.Tone.OK, live = true)
                low -> ui.pill("Low", Ui.Tone.RED)
                else -> ui.pill("On battery", Ui.Tone.IDLE)
            }
        )
        card.addView(top)

        // The number counts and the bar glides to a new level.
        val moving = glide(d.id, d.level)
        val figure = ui.row(baseline = true)
        val number = ui.figure("${d.level}", 56f, if (stale) ui.muted else ui.fg)
        Motion.countUp(number, moving)
        figure.addView(number)
        figure.addView(ui.figure("%", 26f, ui.muted).apply { setPadding(ui.dp(2), 0, 0, 0) })
        card.addView(figure, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ui.dp(6) })

        card.addView(
            BatteryBar(this, ui.track, ui.mark).apply {
                level = d.level
                anim = moving
                shimmer = ui.motion && d.charging && d.level < 100 && !stale
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
            bottom.addView(ui.caption("Alert at ${prefs.threshold}%", ui.mark))
        }
        card.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) })

        if (isSelf) {
            card.setOnClickListener {
                Motion.tick(it)
                editName()
            }
        } else {
            card.setOnClickListener {
                Motion.tick(it)
                deviceSheet(d)
            }
            card.setOnLongClickListener {
                confirmForget(d)
                true
            }
        }
        // A phone that just joined the list settles into place.
        val fresh = shownDevices.add(d.id)
        if (fresh && !entering && ui.motion) Motion.popIn(card)
        return card
    }

    private fun deviceSheet(d: Device) {
        val now = System.currentTimeMillis() / 1000
        val state = when {
            Tracker.isStale(d, now) -> "Offline"
            d.charging && d.level >= 100 -> "Full, on the charger"
            d.charging -> "Charging"
            else -> "On battery"
        }
        Sheet(this, ui)
            .title(d.name)
            .text("${d.level}% · $state\nLast update ${ago((now - d.time).coerceAtLeast(0))}")
            .action("Remove from list", Ui.Style.DANGER) { WatchService.forget(this, d.id) }
            .action("Close", Ui.Style.GHOST)
            .show()
    }

    // ---- Updates ----

    private fun checkForUpdate(manual: Boolean) {
        val phase = Hub.updatePhase
        if (phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING) return
        Hub.setUpdate(UpdatePhase.CHECKING)
        prefs.lastUpdateCheck = System.currentTimeMillis()
        Thread {
            val info = runCatching { Updater.fetch() }.getOrNull()
            main.post {
                if (info != null) {
                    prefs.cachedUpdate = Updater.toJson(info)
                    Hub.setUpdate(UpdatePhase.IDLE, info = info)
                    if (manual) toast(if (info.isNewer) "ChargeWatch ${info.versionName} is available." else "You have the latest version.")
                } else {
                    Hub.setUpdate(UpdatePhase.IDLE)
                    if (manual) toast("Could not check for updates. Check your internet connection.")
                }
            }
        }.start()
    }

    private fun startUpdate(info: UpdateInfo) {
        if (Hub.updatePhase == UpdatePhase.DOWNLOADING) return
        if (!packageManager.canRequestPackageInstalls()) {
            Sheet(this, ui)
                .title("Allow updates")
                .text("Android asks once before an app can install its own updates. Turn on Allow from this source for ChargeWatch, then come back and tap Update now.")
                .action("Open settings", Ui.Style.PRIMARY) {
                    tryStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .action("Not now", Ui.Style.GHOST)
                .show()
            return
        }
        Hub.setUpdate(UpdatePhase.DOWNLOADING, progress = 0)
        val app = applicationContext
        Thread {
            try {
                val file = Updater.download(app, info) { pct ->
                    main.post { if (Hub.updatePhase == UpdatePhase.DOWNLOADING) Hub.setUpdate(UpdatePhase.DOWNLOADING, progress = pct) }
                }
                main.post { Hub.setUpdate(UpdatePhase.INSTALLING) }
                Updater.install(app, file)
            } catch (e: Exception) {
                main.post { Hub.setUpdate(UpdatePhase.FAILED, error = e.message ?: "The update failed. Try again.") }
            }
        }.start()
    }

    private fun updateLabel(): String {
        val info = Hub.updateInfo
        return when {
            Hub.updatePhase == UpdatePhase.CHECKING -> "Checking…"
            info != null && info.isNewer -> "Version ${info.versionName} is available"
            info != null -> "Version ${BuildConfig.VERSION_NAME} · Up to date"
            else -> "Version ${BuildConfig.VERSION_NAME} · Tap to check"
        }
    }

    private fun onUpdateRow() {
        val info = Hub.updateInfo
        if (info != null && info.isNewer) startUpdate(info) else checkForUpdate(manual = true)
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
        Background.cancel(this)
        prefs.leave()
        Hub.reset()
        levelAnims.clear()
        shownDevices.clear()
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
            Triple("Appearance", appearanceLabel(), ::pickAppearance),
            Triple("This phone's name", prefs.name, ::editName),
            Triple("Relay server", host(prefs.server), ::editServer),
            Triple("Background and battery", if (isUnrestricted()) "Unrestricted" else "Battery optimization is on", ::batteryHelp),
            Triple("Status notification", if (prefs.alwaysOn) "Always" else "Only while a phone is charging", ::pickStatusMode),
            Triple("App updates", updateLabel(), ::onUpdateRow),
            Triple("About", "Open source on GitHub", ::about),
        )
        rows.forEachIndexed { i, (label, value, open) ->
            if (i > 0) list.addView(ui.divider())
            list.addView(settingsRow(label, value) {
                sheet.dismiss()
                open()
            })
        }
        // The rows reach a little past the text, so their ripples have room.
        list.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = -ui.dp(8)
            marginEnd = -ui.dp(8)
        }
        sheet.add(list, 12).action("Close", Ui.Style.GHOST).show()
    }

    private fun settingsRow(label: String, value: String, onClick: () -> Unit): View {
        val row = ui.row().apply {
            setPadding(ui.dp(8), ui.dp(14), ui.dp(8), ui.dp(14))
            setOnClickListener {
                Motion.tick(it)
                onClick()
            }
        }
        ui.tappable(row)
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

    private fun appearanceLabel() = when (prefs.themeMode) {
        Prefs.THEME_LIGHT -> "Light"
        Prefs.THEME_DARK -> "Dark"
        else -> "System (${if (ui.dark) "dark" else "light"} now)"
    }

    private fun pickAppearance() {
        val sheet = Sheet(this, ui)
            .title("Appearance")
            .text("Light or dark. System follows your phone's setting. The sun or moon at the top of the screen switches it too.")
        listOf(Prefs.THEME_SYSTEM to "System", Prefs.THEME_LIGHT to "Light", Prefs.THEME_DARK to "Dark").forEach { (mode, label) ->
            sheet.add(
                ui.button(label, if (prefs.themeMode == mode) Ui.Style.PRIMARY else Ui.Style.SECONDARY) {
                    sheet.dismiss()
                    switchTheme(mode, null)
                },
                10,
            )
        }
        sheet.show()
    }

    private fun pickStatusMode() {
        val sheet = Sheet(this, ui)
            .title("Status notification")
            .text(
                "Android shows a notification whenever ChargeWatch keeps a live connection. By default that only " +
                    "happens while one of your phones is charging, so the notification appears then and goes away after.\n\n" +
                    "Always keeps ChargeWatch running all the time, with the notification. It uses a little more battery."
            )
        val options = listOf(false to "Only while a phone is charging", true to "Always")
        options.forEach { (always, label) ->
            sheet.add(
                ui.button(label, if (prefs.alwaysOn == always) Ui.Style.PRIMARY else Ui.Style.SECONDARY) {
                    sheet.dismiss()
                    if (prefs.alwaysOn != always) {
                        prefs.alwaysOn = always
                        if (prefs.group != null) WatchService.start(this, reload = true)
                        render()
                    }
                },
                10,
            )
        }
        sheet.show()
    }

    private fun editName() {
        val input = ui.input(prefs.name, "Phone name").apply {
            filters = arrayOf(InputFilter.LengthFilter(Prefs.MAX_NAME))
        }
        Sheet(this, ui)
            .title("This phone's name")
            .text("Other phones show this name in their list and alerts. The system name is \u201c${prefs.defaultName()}\u201d.")
            .add(input)
            .action("Save", Ui.Style.PRIMARY) {
                prefs.name = input.text.toString()
                nameChanged()
            }
            .action("Use system name", Ui.Style.GHOST) {
                prefs.name = ""
                nameChanged()
            }
            .showKeyboard()
            .show()
    }

    private fun nameChanged() {
        prefs.nameChosen = true
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
                "$state\n\nChargeWatch is built to sip battery. It runs live, with a notification, only while one of your " +
                    "phones is charging. The rest of the time nothing runs: Android checks for news about every 15 minutes " +
                    "and wakes ChargeWatch when this phone is plugged in.\n\nGoing live from the background needs battery " +
                    "use set to Unrestricted. If updates stop arriving, check that first. " +
                    "Xiaomi, Oppo, Vivo, OnePlus and Huawei phones also need Autostart. Samsung phones must keep ChargeWatch " +
                    "out of Sleeping apps.\n\nThe moving background only runs while this screen is open, and it stops in Battery Saver."
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
                    "Free and open source. Messages go through ntfy.sh, a free push relay. There is no account and no ChargeWatch server, " +
                    "so the app cannot count its users. GitHub counts downloads${statsText()}.\n\n" +
                    "Fonts: IBM Plex and Barlow, SIL Open Font License."
            )
            .action("Source code", Ui.Style.PRIMARY) { openUrl("https://github.com/Sowan3k/chargewatch") }
            .action("Close", Ui.Style.GHOST)
            .show()
    }

    private fun statsText(): String {
        val info = Hub.updateInfo ?: return ""
        val parts = mutableListOf<String>()
        if (info.downloads > 0) parts += "%,d so far".format(info.downloads)
        if (info.stars > 0) parts += "${info.stars} people starred the project"
        return if (parts.isEmpty()) "" else ": " + parts.joinToString(", and ")
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
        private const val UPDATE_CHECK_MS = 6 * 60 * 60 * 1000L
        /** Download and star counts below this stay hidden: a tiny number builds no trust. */
        private const val SOCIAL_PROOF_MIN = 10
        private val THRESHOLDS = intArrayOf(0, 50, 60, 70, 75, 80, 85, 90, 95)
    }
}
