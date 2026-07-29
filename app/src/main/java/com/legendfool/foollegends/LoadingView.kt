package com.legendfool.foollegends

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.exp
import kotlin.math.max

private const val SHIMMER_CYCLE_MS = 1500L

/** Shortest a launch is allowed to look, so the bar never blinks straight to full. */
private const val MIN_SESSION_MS = 900L

/** How long the bar takes to run from wherever it is up to 100%. */
private const val FILL_MS = 420f

/** The beat between a full bar and the screen behind it. */
private const val HOLD_MS = 500L

/**
 * Splash / loading screen. Shows the branded artwork (separate portrait and landscape
 * images), a "Loading..." caption and an animated loading bar drawn on top by code.
 * Works in both orientations; the game itself stays portrait.
 *
 * In indeterminate mode the bar belongs to a *launch session* rather than to one
 * view: the launcher hands the session over to the WebView host, which keeps drawing
 * the same bar at the same position, so a launch reads as a single screen no matter
 * how many activities it passes through. [complete] ends the session by running the
 * bar up to full and holding it for a beat.
 */
class LoadingView(
    context: Context,
    private val durationMs: Long = 2400L,
    private val indeterminate: Boolean = false,
    private val onComplete: () -> Unit
) : View(context) {

    // Decoded on first draw so only the artwork for the current orientation is
    // ever held in memory.
    private val portrait by lazy { BitmapFactory.decodeResource(resources, R.drawable.loading_portrait) }
    private val landscape by lazy { BitmapFactory.decodeResource(resources, R.drawable.loading_landscape) }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val gold = 0xFFF2C14E.toInt()

    private val srcRect = Rect()
    private val dstRect = RectF()
    private val barBounds = RectF()

    private var startTime = 0L
    private var finished = false

    private var completing = false
    private var fillStart = 0L
    private var fillFrom = 0f
    private var fullSince = 0L
    private var onFilled: (() -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = if (indeterminate) openSession() else SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /**
     * Runs the bar up to 100%, holds it briefly and then calls [onFilled]. Whatever
     * comes next — the game, a gray screen or the loaded page — must wait for that
     * callback, so the bar is never cut off half way and never sits full while the
     * app keeps working.
     */
    fun complete(onFilled: () -> Unit) {
        if (completing) return
        completing = true
        this.onFilled = onFilled
        postInvalidateOnAnimation()
    }

    /** Progress of the fill, or null while the bar is still merely waiting. */
    private fun fillProgress(now: Long, waiting: Float): Float? {
        if (!completing) return null
        if (fillStart == 0L) {
            if (now - startTime < MIN_SESSION_MS) return null
            fillStart = now
            fillFrom = waiting
        }
        val t = ((now - fillStart) / FILL_MS).coerceIn(0f, 1f)
        // Ease out: quick off the mark, gentle into the stop.
        val eased = 1f - (1f - t) * (1f - t)
        val value = fillFrom + (1f - fillFrom) * eased

        if (t >= 1f) {
            if (fullSince == 0L) fullSince = now
            val done = onFilled
            if (done != null && now - fullSince >= HOLD_MS) {
                onFilled = null
                closeSession()
                post { done() }
            }
        }
        return value
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        val landscapeMode = width >= height
        canvas.drawColor(0xFF2C0709.toInt())

        // Center-crop in both orientations: the artwork fills the screen edge to edge
        // and only the overhanging strip is trimmed.
        val bmp = if (landscapeMode) landscape else portrait
        val scale = max(w / bmp.width, h / bmp.height)
        val sw = w / scale
        val sh = h / scale
        val sx = (bmp.width - sw) / 2f
        val sy = (bmp.height - sh) / 2f
        srcRect.set(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
        dstRect.set(0f, 0f, w, h)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)

        val now = SystemClock.uptimeMillis()
        val elapsed = now - startTime
        // Indeterminate mode eases toward 92% and waits there: the bar must not claim
        // to be done while routing is still running.
        val waiting = if (indeterminate) {
            0.92f * (1f - exp(-elapsed / 1600f))
        } else {
            (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
        }
        val filling = fillProgress(now, waiting)
        val progress = filling ?: waiting

        // The bar and the caption are sized off the height, which is far smaller in
        // landscape — bump the factors there so they stay legible.
        measureBar(w, h, landscapeMode)
        val captionSize = if (landscapeMode) h * 0.055f else h * 0.030f

        // "Loading..." caption with animated dots.
        val dots = ".".repeat(((elapsed / 400L) % 4L).toInt())
        textPaint.textSize = captionSize
        textPaint.color = gold
        textPaint.setShadowLayer(h * 0.008f, 0f, h * 0.004f, Color.BLACK)
        canvas.drawText("Loading$dots", w / 2f, barBounds.top - captionSize * 0.45f, textPaint)
        textPaint.clearShadowLayer()

        drawLoadingBar(canvas, h, progress)
        if (indeterminate) {
            // The travelling highlight belongs to the waiting phase only.
            if (filling == null) drawShimmer(canvas, progress, elapsed)
            postInvalidateOnAnimation()
            return
        }

        if (progress >= 1f && !finished) {
            finished = true
            post { onComplete() }
            return
        }
        postInvalidateOnAnimation()
    }

    private fun measureBar(w: Float, h: Float, landscapeMode: Boolean) {
        val barW = w * if (landscapeMode) 0.48f else 0.62f
        val barH = h * if (landscapeMode) 0.040f else 0.022f
        val x0 = (w - barW) / 2f
        val y0 = h * 0.915f - barH / 2f
        barBounds.set(x0, y0, x0 + barW, y0 + barH)
    }

    /** Travelling highlight so the bar reads as "still working" while it sits at 92%. */
    private fun drawShimmer(canvas: Canvas, progress: Float, elapsed: Long) {
        val x0 = barBounds.left
        val y0 = barBounds.top
        val barW = barBounds.width()
        val fillW = barW * progress
        if (fillW <= 2f) return

        val band = barW * 0.22f
        val phase = (elapsed % SHIMMER_CYCLE_MS).toFloat() / SHIMMER_CYCLE_MS
        val head = x0 - band + phase * (fillW + band * 2f)

        canvas.save()
        canvas.clipRect(x0, y0, x0 + fillW, barBounds.bottom)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            head, y0, head + band, y0,
            intArrayOf(0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(x0, y0, x0 + fillW, barBounds.bottom, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun drawLoadingBar(canvas: Canvas, h: Float, progress: Float) {
        val x0 = barBounds.left
        val y0 = barBounds.top
        val barW = barBounds.width()
        val barH = barBounds.height()
        val r = barH / 2f

        // Track.
        paint.style = Paint.Style.FILL
        paint.color = 0x88000000.toInt()
        canvas.drawRoundRect(x0, y0, x0 + barW, y0 + barH, r, r, paint)

        // Fill.
        if (progress > 0f) {
            val fillW = barW * progress
            paint.shader = LinearGradient(
                x0, y0, x0 + barW, y0,
                0xFFE53935.toInt(), gold, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(x0, y0, x0 + fillW, y0 + barH, r, r, paint)
            paint.shader = null
        }

        // Gold border.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.0035f
        paint.color = gold
        canvas.drawRoundRect(x0, y0, x0 + barW, y0 + barH, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    companion object {
        @Volatile
        private var sessionStart = 0L

        /**
         * Opens a launch session. Called by the launcher so that every launch starts
         * the bar at zero, even when a previous session was abandoned half way.
         */
        fun beginSession() {
            sessionStart = SystemClock.uptimeMillis()
        }

        /** Joins the running session, or starts one for a view drawn on its own. */
        private fun openSession(): Long {
            if (sessionStart == 0L) sessionStart = SystemClock.uptimeMillis()
            return sessionStart
        }

        private fun closeSession() {
            sessionStart = 0L
        }
    }
}
