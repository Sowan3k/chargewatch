package io.github.sowan3k.chargewatch

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.PowerManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Motion for the screen: the living background, the opening, touch reactions and transitions.
 *
 * Rules that keep it cheap: everything is drawn by the views themselves and only while they are
 * on screen (a view that is not drawn schedules no next frame), continuous animation runs at
 * about 30 frames a second, and all of it stops when the system has animations turned off or
 * Battery Saver is on.
 */
object Motion {
    /** About 30 frames a second for continuous animation. */
    const val FRAME_MS = 33L

    /** False when the system has animations turned off, or Battery Saver is on. */
    fun enabled(context: Context): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        return context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
    }

    /** Press feedback: the view sinks a little under the finger and springs back. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View, scale: Float = 0.97f) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(90)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(280)
                        .setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    /** A light haptic tick. Follows the phone's touch-feedback setting. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** A new screen: its parts fade in and rise, one after the other. Returns how long it takes. */
    fun riseIn(views: List<View>, distance: Float): Long {
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = distance
            v.animate().alpha(1f).translationY(0f).setStartDelay(45L * i).setDuration(RISE_MS)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
        return RISE_MS + 45L * views.size
    }

    /** A new card: it fades in and settles into place. */
    fun popIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(340)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    /** Shows the animated level as a number, counting until the animation is done. */
    fun countUp(text: TextView, anim: LevelAnim) {
        val now = SystemClock.uptimeMillis()
        text.text = anim.value(now).roundToInt().toString()
        if (anim.done(now)) return
        text.post(object : Runnable {
            override fun run() {
                val t = SystemClock.uptimeMillis()
                text.text = anim.value(t).roundToInt().toString()
                if (!anim.done(t) && text.isAttachedToWindow) text.postOnAnimation(this)
            }
        })
    }

    private const val RISE_MS = 380L
}

/**
 * A battery level on its way from one value to the next. It lives outside the views, because
 * the screen is rebuilt often and a rebuilt card must carry on where the old one was.
 */
class LevelAnim(val from: Int, val to: Int, private val startMs: Long) {
    fun value(now: Long): Float {
        val p = ((now - startMs) / DURATION_MS).coerceIn(0f, 1f)
        return from + (to - from) * (1f - (1f - p).pow(3))
    }

    fun done(now: Long) = now - startMs >= DURATION_MS

    companion object {
        private const val DURATION_MS = 800f

        /** No animation: resting at [level]. */
        fun still(level: Int) = LevelAnim(level, level, 0L)
    }
}

/**
 * The background: a monogram canvas. ChargeWatch's own marks (the "CW" monogram, a bolt in a
 * ring, a small battery) repeat on a diagonal lattice with dots between them, tone on tone over
 * soft drifting glows. A band of light crosses it every few seconds, single marks glint, the
 * marks near the finger light up and grow, and the pattern slides a little as the page scrolls.
 *
 * Original to this app, drawn with plain Canvas in the theme's colours.
 */
class MonogramView(context: Context) : View(context) {
    /** Off: one still frame is drawn and nothing moves. */
    var animate = false
        set(value) {
            field = value
            lastFrame = 0L
            if (!value) {
                clock = STILL
                touch = 0f
            }
            invalidate()
        }

    /** How far the page is scrolled, in pixels. The pattern follows at a quarter of the speed. */
    var scrollOffset = 0
        set(value) {
            if (field == value) return
            field = value
            if (animate) invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val unit = arrayOfNulls<RadialGradient>(4) // three glows and the touch glow, radius 1
    private val rect = RectF()

    // The marks, in dp around their centre.
    private val monogram = Path().apply {
        val u = 0.8f
        val cx = (-9f - 1.6f) * u
        addArc(RectF(cx - 6.8f * u, -8f * u, cx + 6.8f * u, 8f * u), -48f, -264f)
        val w = floatArrayOf(-1f, -8f, 3.75f, 8f, 9f, -2.5f, 14.25f, 8f, 19f, -8f)
        moveTo((w[0] - 1.6f) * u, w[1] * u)
        for (i in 2 until w.size step 2) lineTo((w[i] - 1.6f) * u, w[i + 1] * u)
    }
    private val bolt = Path().apply {
        moveTo(1.6f, -6f)
        lineTo(-4f, 0.8f)
        lineTo(-0.4f, 0.8f)
        lineTo(-1.6f, 6f)
        lineTo(4f, -0.8f)
        lineTo(0.4f, -0.8f)
        close()
    }

    private var base = Color.WHITE
    private var inkColor = 0
    private var lightColor = 0
    private var inkAlpha = 0f
    private var lightAlpha = 0f

    private var clock = STILL
    private var lastFrame = 0L
    private var focused = true
    private var touch = 0f
    private var touchDown = false
    private var touchX = 0f
    private var touchY = 0f
    private val location = IntArray(2)

    fun setPalette(ui: Ui) {
        val d = ui.dark
        base = ui.bg
        // Lime, a cooler second light and a soft green, the way the theme's charts are coloured.
        val glows = if (d) intArrayOf(0x2196F104, 0x263B82F6, 0x1A22C55E) else intArrayOf(0x4DAFF33E, 0xB3DCFCE7.toInt(), 0x1464748B)
        val finger = if (d) 0x33AFF33E else 0x8CAFF33E.toInt()
        (glows + finger).forEachIndexed { i, c ->
            unit[i] = RadialGradient(0f, 0f, 1f, c, c and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        // Tone on tone: slate on the light page, pale slate on navy. Lit marks turn green or lime.
        inkColor = if (d) 0x94A3B8 else 0x334155
        lightColor = if (d) 0xAFF33E else 0x16A34A
        inkAlpha = if (d) 0.10f else 0.075f
        lightAlpha = if (d) 0.70f else 0.60f
        invalidate()
    }

    /** Called with every touch on the screen. */
    fun onFinger(e: MotionEvent) {
        if (!animate) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                getLocationInWindow(location)
                touchX = e.x - location[0]
                touchY = e.y - location[1]
                touchDown = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchDown = false
        }
    }

    /** A sheet or the notification shade is on top: hold still until the screen is back. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        focused = hasWindowFocus
        lastFrame = 0L
        if (hasWindowFocus) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else (now - lastFrame).coerceAtMost(100L) / 1000f
        lastFrame = now
        if (animate) {
            clock += dt
            val target = if (touchDown) 1f else 0f
            touch += (target - touch) * (1f - exp(-dt * if (touchDown) 10f else 2.5f))
        }
        val t = clock
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(base)

        // Glows that drift slowly around their corners.
        for (i in 0..2) {
            val g = GLOWS[i]
            glow(
                canvas, i, 255,
                w * (g[0] + g[3] * sin(t * g[5] + g[7])),
                h * (g[1] + g[4] * cos(t * g[6] + g[7])),
                w * g[2] * (1f + 0.08f * sin(t * 0.33f + i)),
            )
        }
        val touching = touch > 0.01f
        if (touching) glow(canvas, 3, (255 * touch).toInt(), touchX, touchY, w * 0.5f)

        // The lattice: rows half a step apart, every other row shifted by half a step.
        val step = STEP_DP * density
        val rowH = step / 2f
        val period = 4f * rowH
        val slide = -((scrollOffset * 0.25f) % period)
        // The band of light: it crosses the screen diagonally, then rests until the next cycle.
        val cycle = (t % SHEEN_SECONDS) / SHEEN_SECONDS
        val span = w + h * 0.6f
        val bandAt = -0.25f * span + 1.5f * span * (cycle / 0.55f).coerceAtMost(1f)
        val bandWidth = 150f * density
        val reach = 130f * density

        var j = -4
        while (j * rowH < h + period) {
            val y = j * rowH + slide
            val kind = ((j % 4) + 4) % 4
            val shift = if (kind % 2 == 1) step / 2f else 0f
            var i = -1
            while (i * step < w + step) {
                val x = i * step + shift
                var light = exp(-((x + y * 0.6f - bandAt) / bandWidth).pow(2))
                // Each mark has its own moment to glint.
                val seed = sin(i * 12.9898f + j * 78.233f) * 43758.547f
                val phase = (seed - floor(seed)) * 6.2832f
                light += 0.55f * sin(t * 0.9f + phase).coerceAtLeast(0f).pow(14)
                var scale = 1f
                if (touching) {
                    val dx = x - touchX
                    val dy = y - touchY
                    val near = touch * exp(-(dx * dx + dy * dy) / (reach * reach))
                    light += 1.2f * near
                    scale += 0.35f * near
                }
                light = light.coerceIn(0f, 1f)
                ink.color = mix(inkColor, lightColor, light)
                ink.alpha = (255 * (inkAlpha + (lightAlpha - inkAlpha) * light)).toInt()

                canvas.save()
                canvas.translate(x, y)
                canvas.scale(density * scale, density * scale)
                when {
                    kind == 0 -> {
                        ink.style = Paint.Style.STROKE
                        ink.strokeWidth = 2f
                        canvas.drawPath(monogram, ink)
                    }
                    kind == 2 && i % 2 == 0 -> {
                        ink.style = Paint.Style.STROKE
                        ink.strokeWidth = 1.3f
                        canvas.drawCircle(0f, 0f, 10f, ink)
                        ink.style = Paint.Style.FILL
                        canvas.drawPath(bolt, ink)
                    }
                    kind == 2 -> {
                        ink.style = Paint.Style.STROKE
                        ink.strokeWidth = 1.5f
                        rect.set(-10.5f, -5.5f, 8.5f, 5.5f)
                        canvas.drawRoundRect(rect, 3f, 3f, ink)
                        ink.style = Paint.Style.FILL
                        rect.set(9.6f, -2.2f, 11.6f, 2.2f)
                        canvas.drawRoundRect(rect, 0.8f, 0.8f, ink)
                        rect.set(-7.6f, -2.6f, 0.9f, 2.6f)
                        canvas.drawRoundRect(rect, 1.2f, 1.2f, ink)
                    }
                    else -> {
                        ink.style = Paint.Style.FILL
                        canvas.drawCircle(0f, 0f, 1.7f, ink)
                    }
                }
                canvas.restore()
                i++
            }
            j++
        }
        if (animate && focused) postInvalidateDelayed(Motion.FRAME_MS)
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun glow(canvas: Canvas, index: Int, alpha: Int, x: Float, y: Float, radius: Float) {
        fill.shader = unit[index] ?: return
        fill.alpha = alpha
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, fill)
        canvas.restore()
    }

    private companion object {
        /** The moment shown when nothing moves: the band of light is off screen. */
        const val STILL = 5.5f
        /** Distance between marks in a row, in dp. */
        const val STEP_DP = 72f
        const val SHEEN_SECONDS = 7f
        /** Per glow: x, y, radius, drift x, drift y, speed x, speed y, phase (fractions of the view). */
        val GLOWS = arrayOf(
            floatArrayOf(-0.05f, 0.06f, 0.95f, 0.20f, 0.10f, 0.31f, 0.23f, 0.0f),
            floatArrayOf(1.05f, 0.30f, 0.90f, 0.18f, 0.12f, 0.26f, 0.37f, 2.1f),
            floatArrayOf(0.35f, 1.02f, 1.00f, 0.22f, 0.08f, 0.21f, 0.29f, 4.0f),
        )
    }
}

/**
 * The opening: the battery draws itself, "CW" is written inside it, it fills with charge and
 * sends out a ring, the name appears, and then the picture opens from its centre onto the app.
 * About a second and a half. A tap skips it.
 */
class IntroView(
    context: Context,
    private val ui: Ui,
    private val onReveal: () -> Unit,
    private val onDone: () -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ui.display
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val part = Path()
    private val hole = Path()
    private val measure = PathMeasure()
    private val body = Path()
    private val letterC = Path()
    private val letterW = Path()
    private var glow: RadialGradient? = null
    private var start = 0L
    private var revealed = false
    private var finished = false

    init {
        isClickable = true
        setOnClickListener {
            // Skip to the opening.
            val now = SystemClock.uptimeMillis()
            if (start != 0L && now - start < REVEAL_AT) start = now - REVEAL_AT.toLong()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val d = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h * CENTRE_Y
        body.rewind()
        rect.set(cx - 74f * d, cy - 37f * d, cx + 74f * d, cy + 37f * d)
        body.addRoundRect(rect, 18f * d, 18f * d, Path.Direction.CW)
        // The same letters as the icon and the background, larger.
        val u = 2.3f * d
        val ox = cx - 1.6f * u
        letterC.rewind()
        letterC.addArc(RectF(ox - 15.8f * u, cy - 8f * u, ox - 2.2f * u, cy + 8f * u), -48f, -264f)
        letterW.rewind()
        val p = floatArrayOf(-1f, -8f, 3.75f, 8f, 9f, -2.5f, 14.25f, 8f, 19f, -8f)
        letterW.moveTo(ox + p[0] * u, cy + p[1] * u)
        for (i in 2 until p.size step 2) letterW.lineTo(ox + p[i] * u, cy + p[i + 1] * u)
        val c = if (ui.dark) 0x2696F104 else 0x73AFF33E
        glow = RadialGradient(cx, cy, w * 0.8f, c, c and 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (start == 0L) start = now
        val t = (now - start).toFloat()
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * CENTRE_Y

        // The opening: a hole grows from the logo and the app shows through it.
        val reveal = span(t, REVEAL_AT, END)
        if (reveal > 0f) {
            if (!revealed) {
                revealed = true
                post(onReveal)
            }
            hole.rewind()
            hole.addCircle(cx, cy, hypot(w, h) * reveal * reveal, Path.Direction.CW)
            canvas.clipOutPath(hole)
        }
        canvas.drawColor(ui.bg)
        paint.shader = glow
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val fade = ((1f - reveal * 2.2f).coerceIn(0f, 1f) * 255).toInt()

        // Charge fills the battery from the left.
        val charge = smooth(span(t, 480f, 1050f))
        if (charge > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = ui.brand
            paint.alpha = (fade * if (ui.dark) 0.45f else 0.38f).toInt()
            val inset = 10f * d
            rect.set(cx - 74f * d + inset, cy - 37f * d + inset, cx - 74f * d + inset + (148f * d - 2 * inset) * charge, cy + 37f * d - inset)
            canvas.drawRoundRect(rect, 9f * d, 9f * d, paint)
        }

        // The ring that goes out when it is full.
        val ring = span(t, 980f, 1320f)
        if (ring > 0f && ring < 1f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * d
            paint.color = ui.mark
            paint.alpha = ((1f - ring) * fade * 0.8f).toInt()
            val grow = 1f + 0.5f * smooth(ring)
            rect.set(cx - 74f * d * grow, cy - 37f * d * grow, cx + 74f * d * grow, cy + 37f * d * grow)
            canvas.drawRoundRect(rect, 18f * d * grow, 18f * d * grow, paint)
        }

        // The battery draws itself, then its terminal appears.
        paint.style = Paint.Style.STROKE
        paint.color = ui.fg
        paint.alpha = fade
        paint.strokeWidth = 6f * d
        trace(canvas, body, smooth(span(t, 0f, 480f)))
        val nub = span(t, 400f, 520f)
        if (nub > 0f) {
            paint.style = Paint.Style.FILL
            paint.alpha = (fade * nub).toInt()
            rect.set(cx + 79f * d, cy - 12f * d, cx + 87f * d, cy + 12f * d)
            canvas.drawRoundRect(rect, 3f * d, 3f * d, paint)
        }

        // "CW" is written inside.
        paint.style = Paint.Style.STROKE
        paint.alpha = fade
        paint.strokeWidth = 6.5f * d
        trace(canvas, letterC, smooth(span(t, 220f, 500f)))
        trace(canvas, letterW, smooth(span(t, 420f, 780f)))

        // The name rises in under it.
        val name = smooth(span(t, 420f, 820f))
        if (name > 0f) {
            text.color = ui.fg
            text.alpha = (fade * name).toInt()
            text.textSize = 30f * resources.displayMetrics.scaledDensity
            canvas.drawText("ChargeWatch", cx, cy + 37f * d + 58f * d + (1f - name) * 10f * d, text)
        }

        if (t >= END) {
            if (!finished) {
                finished = true
                post(onDone)
            }
        } else {
            postInvalidateOnAnimation()
        }
    }

    /** Draws the first [fraction] of a path's length. */
    private fun trace(canvas: Canvas, path: Path, fraction: Float) {
        if (fraction <= 0f) return
        if (fraction >= 1f) {
            canvas.drawPath(path, paint)
            return
        }
        measure.setPath(path, false)
        part.rewind()
        measure.getSegment(0f, measure.length * fraction, part, true)
        canvas.drawPath(part, paint)
    }

    private fun span(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    private companion object {
        const val REVEAL_AT = 1150f
        const val END = 1600f
        /** The logo sits a little above the middle. */
        const val CENTRE_Y = 0.42f
    }
}

/** A status dot. When [live], a ring keeps pulsing out of it. */
class PulseDot(context: Context, private val color: Int, private val live: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width * 3f / 14f
        if (live) {
            val t = (SystemClock.uptimeMillis() % 1600L) / 1600f
            paint.color = color
            paint.alpha = ((1f - t) * 120).toInt()
            canvas.drawCircle(cx, cy, r + (width / 2f - r) * t, paint)
            postInvalidateDelayed(Motion.FRAME_MS)
        }
        paint.color = color
        canvas.drawCircle(cx, cy, r, paint)
    }
}

/**
 * The picture on the first screen: a battery that charges to the alert level, then to full,
 * and sends out a ring each time, the way the alerts go out.
 */
class HeroBattery(context: Context, private val ui: Ui) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bolt = Path()
    private val start = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val t = if (ui.motion) ((SystemClock.uptimeMillis() - start) % LOOP_MS) / LOOP_MS.toFloat() else 0.5f
        val level = when {
            t < 0.40f -> 0.30f + 0.50f * ease(t / 0.40f)
            t < 0.55f -> 0.80f
            t < 0.80f -> 0.80f + 0.20f * ease((t - 0.55f) / 0.25f)
            else -> 1f
        }
        val bodyW = 92f * d
        val bodyH = 46f * d
        val left = 4f * d
        val top = (height - bodyH) / 2f
        val cx = left + bodyW / 2f
        val cy = height / 2f

        // The ring that goes out at the alert level and again when full.
        if (ui.motion) {
            val since = when {
                t >= 0.80f -> (t - 0.80f) / 0.18f
                t >= 0.40f -> (t - 0.40f) / 0.15f
                else -> 2f
            }
            if (since in 0f..1f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                paint.color = ui.mark
                paint.alpha = ((1f - since) * 200).toInt()
                val grow = 1f + 0.55f * ease(since)
                rect.set(cx - bodyW / 2f * grow, cy - bodyH / 2f * grow, cx + bodyW / 2f * grow, cy + bodyH / 2f * grow)
                canvas.drawRoundRect(rect, 12f * d * grow, 12f * d * grow, paint)
            }
        }

        // Fill. It fades out at the end of the loop, before the next charge starts.
        paint.style = Paint.Style.FILL
        paint.color = ui.brand
        if (ui.motion && t > 0.94f) paint.alpha = ((1f - (t - 0.94f) / 0.06f) * 255).toInt()
        val inset = 7f * d
        rect.set(left + inset, top + inset, left + inset + (bodyW - 2 * inset) * level, top + bodyH - inset)
        canvas.drawRoundRect(rect, 5f * d, 5f * d, paint)

        // Body and terminal.
        paint.color = ui.fg
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f * d
        rect.set(left, top, left + bodyW, top + bodyH)
        canvas.drawRoundRect(rect, 12f * d, 12f * d, paint)
        paint.style = Paint.Style.FILL
        rect.set(left + bodyW + 3f * d, cy - 8f * d, left + bodyW + 9f * d, cy + 8f * d)
        canvas.drawRoundRect(rect, 2f * d, 2f * d, paint)

        // Bolt: the page colour with an ink outline, so it reads on the fill and off it.
        bolt.rewind()
        bolt.moveTo(cx + 4f * d, cy - 14f * d)
        bolt.lineTo(cx - 9f * d, cy + 1.5f * d)
        bolt.lineTo(cx - 0.5f * d, cy + 1.5f * d)
        bolt.lineTo(cx - 4f * d, cy + 14f * d)
        bolt.lineTo(cx + 9f * d, cy - 1.5f * d)
        bolt.lineTo(cx + 0.5f * d, cy - 1.5f * d)
        bolt.close()
        paint.color = ui.bg
        canvas.drawPath(bolt, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 1.6f * d
        paint.color = ui.fg
        canvas.drawPath(bolt, paint)

        if (ui.motion) postInvalidateDelayed(Motion.FRAME_MS)
    }

    private fun ease(x: Float): Float {
        val p = x.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    private companion object {
        const val LOOP_MS = 5200L
    }
}
