package io.github.sowan3k.chargewatch

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.PowerManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Motion for the screen: the animated background, touch reactions and small transitions.
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
 * The background: soft glows that drift, and wide arcs that flow outward from a point off the
 * lower right, each one catching a streak of light that sways. The light follows the finger,
 * and a glow blooms under it.
 *
 * Original to this app, drawn with plain Canvas gradients in the theme's colours.
 */
class AuroraView(context: Context) : View(context) {
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

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val unit = arrayOfNulls<RadialGradient>(4) // three glows and the touch glow, radius 1
    private var sheen: SweepGradient? = null

    private var base = Color.WHITE
    private var rim = 0
    private var band = 0
    private var bandSheen = 0f
    private var sheenColors = IntArray(6)

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
        val glows = if (d) intArrayOf(0x42B91C1C, 0x38B45309, 0x21DC2626) else intArrayOf(0x1A9B2C2C, 0xD9FEECAA.toInt(), 0x1AB45309)
        val finger = if (d) 0x38FBBF24 else 0xBFFEDC96.toInt()
        (glows + finger).forEachIndexed { i, c ->
            unit[i] = RadialGradient(0f, 0f, 1f, c, c and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        rim = if (d) 0x13F5F5F4 else 0x169B2C2C
        band = if (d) 0x06F5F5F4 else 0x069B2C2C
        bandSheen = if (d) 0.16f else 0.12f
        // The streak: warm on one side, bright in the middle, rose on the other.
        sheenColors = if (d) {
            intArrayOf(0x00FBBF24, 0x8CFBBF24.toInt(), 0xD9FFF7ED.toInt(), 0x80F87171.toInt(), 0x00F87171, 0x00F87171)
        } else {
            intArrayOf(0x00D97706, 0x5CD97706, 0x759B2C2C, 0x4DF47280, 0x00F47280, 0x00F47280)
        }
        buildSheen()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildSheen()

    /** A sheet or the notification shade is on top: hold still until the screen is back. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        focused = hasWindowFocus
        lastFrame = 0L
        if (hasWindowFocus) invalidate()
    }

    private fun buildSheen() {
        if (width == 0 || height == 0) return
        sheen = SweepGradient(width * CX, height * CY, sheenColors, floatArrayOf(0f, 0.022f, 0.045f, 0.068f, SPAN, 1f))
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
        if (touch > 0.01f) glow(canvas, 3, (255 * touch).toInt(), touchX, touchY, w * 0.5f)

        // Arcs: ring k sits at r0 * q^(k + phase), so every ring grows into the next one's place.
        val cx = w * CX
        val cy = h * CY
        val phase = (t / FLOW_SECONDS) % 1f
        var theta = 212f + 22f * sin(t * 0.5f)
        if (touch > 0.01f) {
            val toFinger = (Math.toDegrees(atan2((touchY - cy).toDouble(), (touchX - cx).toDouble())).toFloat() + 360f) % 360f
            theta += (toFinger - theta) * 0.85f * touch
        }
        val sweep = sheen
        for (k in 0 until RINGS) {
            val u = (k + phase) / RINGS
            val r = w * R0 * Q.pow(k + phase)
            val gap = r * (Q - 1f)
            val fade = sin(PI.toFloat() * u).pow(0.6f)

            line.shader = null
            line.color = band
            line.alpha = (Color.alpha(band) * fade).toInt()
            line.strokeWidth = gap * 0.46f
            canvas.drawCircle(cx, cy, r + gap * 0.25f, line)
            line.color = rim
            line.alpha = (Color.alpha(rim) * fade).toInt()
            line.strokeWidth = 1.25f * density
            canvas.drawCircle(cx, cy, r, line)

            if (sweep != null) {
                // The streak sits at a slightly different angle on every ring, like light on curved metal.
                canvas.save()
                canvas.rotate(theta + (k - RINGS / 2f) * 3.2f - SPAN * 180f, cx, cy)
                line.shader = sweep
                line.color = Color.BLACK
                line.alpha = (255 * fade).toInt()
                line.strokeWidth = 2.6f * density
                canvas.drawCircle(cx, cy, r, line)
                line.alpha = (255 * fade * bandSheen).toInt()
                line.strokeWidth = gap * 0.42f
                canvas.drawCircle(cx, cy, r + gap * 0.25f, line)
                canvas.restore()
            }
        }
        if (animate && focused) postInvalidateDelayed(Motion.FRAME_MS)
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
        /** The moment shown when nothing moves. */
        const val STILL = 4f
        /** Centre of the arcs, as a fraction of the view: off the lower right corner. */
        const val CX = 1.45f
        const val CY = 1.0f
        const val R0 = 0.52f
        const val Q = 1.3f
        const val RINGS = 8
        const val FLOW_SECONDS = 9f
        /** Width of the streak, as a fraction of a full turn. */
        const val SPAN = 0.09f
        /** Per glow: x, y, radius, drift x, drift y, speed x, speed y, phase (fractions of the view). */
        val GLOWS = arrayOf(
            floatArrayOf(-0.05f, 0.06f, 0.95f, 0.20f, 0.10f, 0.31f, 0.23f, 0.0f),
            floatArrayOf(1.05f, 0.30f, 0.90f, 0.18f, 0.12f, 0.26f, 0.37f, 2.1f),
            floatArrayOf(0.35f, 1.02f, 1.00f, 0.22f, 0.08f, 0.21f, 0.29f, 4.0f),
        )
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
