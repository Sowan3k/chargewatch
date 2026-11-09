package io.github.sowan3k.chargewatch

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * ChargeWatch's design system.
 *
 * Colours: a warm claret theme. Off-white paper with near-black ink, claret (#9B2C2C) for
 * the main actions and the alert mark, warm cream for secondary actions, amber for
 * "charging", golden brown for labels, and a darker red only for problems. The dark theme
 * is warm stone with a brighter red. Shadows are tinted deep red. The values come from the
 * project's theme tokens (background, card, primary, secondary, accent, muted, border,
 * destructive, chart).
 *
 * Type: Barlow Semi Condensed for titles and figures, IBM Plex Sans for text, IBM Plex Mono
 * for codes. Fine states are quiet (a neutral pill with a small dot).
 */
class Ui(val context: Context) {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    // ---- Colour tokens (light / dark) ----
    val bg = if (dark) 0xFF1C1917.toInt() else 0xFFFAF7F5.toInt()            // background
    val fg = if (dark) 0xFFF5F5F4.toInt() else 0xFF1A1A1A.toInt()            // foreground
    val muted = if (dark) 0xFFD6D3D1.toInt() else 0xFF57534E.toInt()         // muted-foreground
    val surface = if (dark) 0xFF292524.toInt() else 0xFFFAF7F5.toInt()       // card
    val border = if (dark) 0xFF44403C.toInt() else 0xFFF5E8D2.toInt()        // border
    val hairline = border
    val primary = if (dark) 0xFFB91C1C.toInt() else 0xFF9B2C2C.toInt()       // primary
    val onPrimary = if (dark) 0xFFFAF7F5.toInt() else 0xFFFFFFFF.toInt()     // primary-foreground
    val secondary = if (dark) 0xFF92400E.toInt() else 0xFFFDF2D6.toInt()     // secondary
    val onSecondary = if (dark) 0xFFFEF3C7.toInt() else 0xFF805500.toInt()   // secondary-foreground
    val accent = if (dark) 0xFFB45309.toInt() else 0xFFFEF3C7.toInt()        // accent
    val onAccent = if (dark) 0xFFFEF3C7.toInt() else 0xFF7F1D1D.toInt()      // accent-foreground
    val destructive = if (dark) 0xFFEF4444.toInt() else 0xFF991B1B.toInt()   // destructive
    /** "Charging": amber (chart-4). */
    val brand = if (dark) 0xFFFBBF24.toInt() else 0xFFB45309.toInt()
    /** Labels and small accents: golden brown in light, amber in dark. */
    val brass = if (dark) 0xFFF59E0B.toInt() else 0xFF805500.toInt()
    val sheet = surface                                                     // popover
    val quiet = if (dark) 0xFF1F1C1A.toInt() else 0xFFF0EBE8.toInt()         // muted
    val dotOk = brand
    val dotIdle = if (dark) 0x66F5F5F4 else 0x401A1A1A
    val amberBg = if (dark) 0x38B45309 else accent
    val amberBorder = if (dark) 0x80B45309.toInt() else 0xFFF5E1B0.toInt()
    val amberText = if (dark) 0xFFFEF3C7.toInt() else onAccent
    val redBg = if (dark) 0x33EF4444 else 0x1A991B1B
    val redText = destructive
    val track = quiet
    /** Shadow tint, hsl(0 63% 18%). */
    val shadow = 0xFF4B1111.toInt()

    // ---- Type ----
    val display: Typeface = Fonts.get(context, Fonts.DISPLAY)
    val displaySemi: Typeface = Fonts.get(context, Fonts.DISPLAY_SEMI)
    val sans: Typeface = Fonts.get(context, Fonts.SANS)
    val sansMedium: Typeface = Fonts.get(context, Fonts.SANS_MEDIUM)
    val sansSemi: Typeface = Fonts.get(context, Fonts.SANS_SEMI)
    val mono: Typeface = Fonts.get(context, Fonts.MONO)

    fun dp(v: Int): Int = dpf(v.toFloat()).toInt()
    fun dpf(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    private fun text(s: CharSequence, sp: Float, color: Int, face: Typeface) = TextView(context).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = face
    }

    /** Page titles, 30 sp condensed bold. */
    fun pageTitle(s: String) = text(s, 30f, fg, display).apply {
        includeFontPadding = false
        letterSpacing = -0.005f
    }

    /** The opening headline. */
    fun headline(s: String) = text(s, 40f, fg, display).apply {
        includeFontPadding = false
        letterSpacing = -0.01f
        setLineSpacing(0f, 0.98f)
    }

    /** Section and sheet titles, 22 sp condensed semibold. */
    fun section(s: String) = text(s, 22f, fg, displaySemi).apply { includeFontPadding = false }

    /** Card titles, 18 sp semibold. */
    fun cardTitle(s: String) = text(s, 18f, fg, sansSemi)

    fun body(s: CharSequence, color: Int = muted, sp: Float = 15f) = text(s, sp, color, sans).apply {
        setLineSpacing(0f, 1.3f)
    }

    fun caption(s: CharSequence, color: Int = muted) = text(s, 13f, color, sans)

    /** Small uppercase labels above sections. */
    fun eyebrow(s: String, color: Int = muted) = text(s.uppercase(), 11f, color, sansSemi).apply {
        letterSpacing = 0.08f
    }

    /** Big numbers: condensed, tabular. */
    fun figure(s: String, sp: Float, color: Int = fg) = text(s, sp, color, displaySemi).apply {
        includeFontPadding = false
        fontFeatureSettings = "tnum"
    }

    fun code(s: String, sp: Float = 15f, color: Int = fg) = text(s, sp, color, mono)

    // ---- Containers ----

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** A horizontal row. Baseline alignment is off unless asked for, so margins never clip children. */
    fun row(baseline: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = baseline
        gravity = if (baseline) Gravity.NO_GRAVITY else Gravity.CENTER_VERTICAL
    }

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1) = GradientDrawable().apply {
        cornerRadius = dpf(radiusDp.toFloat())
        setColor(color)
        if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
    }

    /** The standard card: the card colour, a border, 16 dp corners and a soft red-tinted shadow. */
    fun card() = column().apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(surface, 16, border)
        elevation = dpf(if (dark) 1f else 3f)
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = shadow
            outlineAmbientShadowColor = shadow
        }
    }

    /** Empty states: a dashed outline. */
    fun dashedCard() = column().apply {
        setPadding(dp(20), dp(22), dp(20), dp(22))
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dpf(16f)
            setStroke(dp(1), if (dark) 0x40F5F5F4 else 0x4D805500, dpf(6f), dpf(5f))
        }
    }

    /** A card in the amber "needs attention" tone. */
    fun notice(title: String, message: String, action: String, onClick: () -> Unit) = column().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(amberBg, 16, amberBorder)
        addView(text(title, 15f, amberText, sansSemi))
        addView(text(message, 14f, if (dark) 0xFFF5F5F4.toInt() else 0xFF1A1A1A.toInt(), sans).apply {
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(4), 0, dp(10))
        })
        addView(
            text(action, 14f, amberText, sansSemi).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(onPrimary)
                background = ripple(rounded(primary, 10), 10)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
    }

    fun spacer(heightDp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    }

    fun divider() = View(context).apply {
        setBackgroundColor(hairline)
        layoutParams = LinearLayout.LayoutParams(MATCH, 1)
    }

    // ---- Controls ----

    enum class Style { PRIMARY, SECONDARY, GHOST, DANGER }

    private fun ripple(content: Drawable?, radiusDp: Int): RippleDrawable {
        val mask = rounded(Color.WHITE, radiusDp)
        val color = if (dark) 0x33FFFFFF else 0x229B2C2C
        return RippleDrawable(ColorStateList.valueOf(color), content, mask)
    }

    /**
     * Buttons are plain TextViews with a shape and a ripple, so no platform button
     * style (caps, insets, elevation) gets in the way. The main action is claret (primary),
     * secondary actions are warm cream (secondary).
     */
    fun button(label: String, style: Style = Style.SECONDARY, onClick: () -> Unit) = text(label, 15f, fg, sansMedium).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        isFocusable = true
        when (style) {
            Style.PRIMARY -> {
                setTextColor(onPrimary)
                background = ripple(rounded(primary, 12), 12)
            }
            Style.SECONDARY -> {
                setTextColor(onSecondary)
                background = ripple(rounded(secondary, 12, if (dark) 0 else 0xFFF5E1B0.toInt()), 12)
            }
            Style.GHOST -> {
                setTextColor(muted)
                background = ripple(null, 12)
            }
            Style.DANGER -> {
                setTextColor(redText)
                background = ripple(rounded(redBg, 12), 12)
            }
        }
        setOnClickListener { onClick() }
    }

    enum class Tone { OK, IDLE, AMBER, RED, NEUTRAL }

    /**
     * Status pills. Problems (amber, red) get a full coloured pill. Fine states are quiet:
     * a neutral pill with a small dot.
     */
    fun pill(label: String, tone: Tone) = row().apply {
        setPadding(dp(9), dp(4), dp(10), dp(4))
        val (fill, textColor) = when (tone) {
            Tone.AMBER -> amberBg to amberText
            Tone.RED -> redBg to redText
            else -> quiet to muted
        }
        background = rounded(fill, 99)
        if (tone == Tone.OK || tone == Tone.IDLE) {
            addView(
                View(context).apply { background = rounded(if (tone == Tone.OK) dotOk else dotIdle, 99) },
                LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) },
            )
        }
        addView(text(label, 12f, textColor, sansMedium))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
    }

    fun iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(primary)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        background = ripple(rounded(surface, 99, border), 99)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    fun input(value: String, hint: String, monoFace: Boolean = false) = EditText(context).apply {
        setText(value)
        setSelection(text.length)
        this.hint = hint
        setSingleLine(true)
        textSize = 16f
        typeface = if (monoFace) mono else sans
        setTextColor(fg)
        setHintTextColor(if (dark) 0x80D6D3D1.toInt() else 0x8057534E.toInt())
        minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(if (dark) 0xFF1C1917.toInt() else 0xFFFFFFFF.toInt(), 12, border, 1)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    /** Soft light behind the app, like docintel's ambient background. Static, so it costs no battery. */
    fun ambient(): Drawable = Ambient(
        bg,
        if (dark) 0x26B91C1C else 0x109B2C2C,          // primary, top left
        if (dark) 0x1FB45309 else 0xB3FEF3C7.toInt(),  // accent, right
        if (dark) 0x14DC2626 else 0x14B45309,          // chart, bottom
    )

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** Loads the bundled fonts once per process. Falls back to the system font if a file fails. */
object Fonts {
    const val DISPLAY = "display"
    const val DISPLAY_SEMI = "display-semi"
    const val SANS = "sans"
    const val SANS_MEDIUM = "sans-medium"
    const val SANS_SEMI = "sans-semi"
    const val MONO = "mono"

    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun get(context: Context, key: String): Typeface = cache.getOrPut(key) {
        val assets = context.applicationContext.assets
        val loaded = try {
            when (key) {
                DISPLAY -> Typeface.createFromAsset(assets, "fonts/barlow-700.ttf")
                DISPLAY_SEMI -> Typeface.createFromAsset(assets, "fonts/barlow-600.ttf")
                MONO -> Typeface.createFromAsset(assets, "fonts/plex-mono-500.ttf")
                else -> {
                    val weight = when (key) {
                        SANS_MEDIUM -> 500
                        SANS_SEMI -> 600
                        else -> 400
                    }
                    Typeface.Builder(assets, "fonts/plex-sans-var.ttf")
                        .setFontVariationSettings("'wght' $weight")
                        .setWeight(weight)
                        .build()
                }
            }
        } catch (e: Exception) {
            null
        }
        loaded ?: when (key) {
            MONO -> Typeface.MONOSPACE
            SANS -> Typeface.SANS_SERIF
            else -> Typeface.DEFAULT_BOLD
        }
    }
}

/** Three soft radial glows over the page colour. */
private class Ambient(private val base: Int, private vararg val glows: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaders = ArrayList<Triple<Float, Float, Float>>()
    private val gradients = ArrayList<RadialGradient>()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        shaders.clear()
        gradients.clear()
        // Top left, right side, bottom: the same layout as the web app's three spans.
        val spots = listOf(Triple(-0.15f * w, 0.02f * h, 0.95f * w), Triple(1.15f * w, 0.28f * h, 0.9f * w), Triple(0.3f * w, 1.08f * h, 1.0f * w))
        spots.forEachIndexed { i, s ->
            if (i < glows.size && s.third > 0) {
                shaders.add(s)
                gradients.add(RadialGradient(s.first, s.second, s.third, glows[i], glows[i] and 0x00FFFFFF, Shader.TileMode.CLAMP))
            }
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(base)
        shaders.forEachIndexed { i, s ->
            paint.shader = gradients[i]
            canvas.drawCircle(s.first, s.second, s.third, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * The battery meter: a rounded track and fill, with a brass tick at the alert level.
 */
class BatteryBar(context: Context, private val trackColor: Int, private val tickColor: Int) : View(context) {
    var level = 0
    var fillColor = Color.GRAY
    /** Alert level to mark, or 0 for none. */
    var mark = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val barH = h * 0.55f
        val top = (h - barH) / 2
        val r = barH / 2
        paint.color = trackColor
        rect.set(0f, top, w, top + barH)
        canvas.drawRoundRect(rect, r, r, paint)
        if (level > 0) {
            paint.color = fillColor
            rect.set(0f, top, maxOf(barH, w * level.coerceIn(0, 100) / 100f), top + barH)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        if (mark in 1..99) {
            val x = w * mark / 100f
            val tw = h * 0.14f
            paint.color = tickColor
            rect.set(x - tw / 2, 0f, x + tw / 2, h)
            canvas.drawRoundRect(rect, tw / 2, tw / 2, paint)
        }
    }
}

/**
 * A bottom sheet built on a plain Dialog: rounded top, a grab handle, a title,
 * content and stacked actions. On Android 12 and newer the app behind it is blurred.
 */
class Sheet(activity: Activity, private val ui: Ui) {
    private val dialog = Dialog(activity)
    val body: LinearLayout = ui.column()

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val frame = ui.column().apply {
            setPadding(ui.dp(24), ui.dp(10), ui.dp(24), ui.dp(24))
            background = GradientDrawable().apply {
                val r = ui.dpf(28f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(ui.sheet)
                setStroke(1, ui.hairline)
            }
            addView(
                View(activity).apply { background = ui.rounded(ui.dotIdle, 99) },
                LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = ui.dp(18)
                },
            )
            addView(body)
        }
        dialog.setContentView(ScrollView(activity).apply {
            isFillViewport = true
            addView(frame)
        })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if (ui.dark) 0.6f else 0.35f)
            if (Build.VERSION.SDK_INT >= 31) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply { blurBehindRadius = ui.dp(16) }
            }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun title(s: String) = apply { body.addView(ui.section(s)) }

    fun text(s: CharSequence) = apply {
        body.addView(ui.body(s).apply { setPadding(0, ui.dp(8), 0, 0) })
    }

    fun add(view: View, topDp: Int = 16) = apply {
        val lp = (view.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        lp.topMargin = ui.dp(topDp)
        body.addView(view, lp)
    }

    /** A full-width action. The sheet closes first, then [onClick] runs. */
    fun action(label: String, style: Ui.Style = Ui.Style.SECONDARY, onClick: () -> Unit = {}) = apply {
        body.addView(
            ui.button(label, style) {
                dialog.dismiss()
                onClick()
            },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
                topMargin = ui.dp(if (body.childCount > 0 && body.getChildAt(body.childCount - 1) is TextView &&
                    (body.getChildAt(body.childCount - 1) as TextView).isClickable) 8 else 20)
            },
        )
    }

    fun showKeyboard() = apply {
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
    }

    fun dismiss() = dialog.dismiss()

    fun show() = dialog.show()
}
