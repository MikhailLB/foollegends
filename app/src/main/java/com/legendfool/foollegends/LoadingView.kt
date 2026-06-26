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
import kotlin.math.max
import kotlin.math.min

/**
 * Splash / loading screen. Shows the branded artwork (portrait + landscape),
 * a "Loading..." caption and an animated loading bar drawn in code.
 *
 * Two modes:
 *  - timed (default): bar fills over [durationMs] then calls [onComplete].
 *    Used by the native game's LoadingActivity.
 *  - indeterminate: bar eases toward ~92% and keeps a subtle moving shimmer,
 *    never "completes" on a timer. The caption dots keep animating. Used by
 *    WelcomePortal while gray/white routing runs (which can take a variable time).
 */
class LoadingView(
    context: Context,
    private val durationMs: Long = 2400L,
    private val indeterminate: Boolean = false,
    private val onComplete: () -> Unit
) : View(context) {

    private val portrait = BitmapFactory.decodeResource(resources, R.drawable.loading_portrait)
    private val landscape = BitmapFactory.decodeResource(resources, R.drawable.loading_landscape)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val gold = 0xFFF2C14E.toInt()

    private val srcRect = Rect()
    private val dstRect = RectF()

    private var startTime = 0L
    private var finished = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(0xFF2C0709.toInt())
        if (width >= height) {
            val bmp = landscape
            val scale = min(w / bmp.width, h / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val l = (w - dw) / 2f
            val t = (h - dh) / 2f
            dstRect.set(l, t, l + dw, t + dh)
            canvas.drawBitmap(bmp, null, dstRect, paint)
        } else {
            val bmp = portrait
            val scale = max(w / bmp.width, h / bmp.height)
            val sw = w / scale
            val sh = h / scale
            val sx = (bmp.width - sw) / 2f
            val sy = (bmp.height - sh) / 2f
            srcRect.set(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
            dstRect.set(0f, 0f, w, h)
            canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        }

        val elapsed = SystemClock.uptimeMillis() - startTime

        // Caption dots — always animating (never frozen).
        val dots = ".".repeat(((elapsed / 400L) % 4L).toInt())
        textPaint.textSize = h * 0.030f
        textPaint.color = gold
        textPaint.setShadowLayer(h * 0.006f, 0f, h * 0.003f, Color.BLACK)
        canvas.drawText("Loading$dots", w / 2f, h * 0.885f, textPaint)
        textPaint.clearShadowLayer()

        if (indeterminate) {
            // Ease toward ~0.92 and never finish on a timer.
            val t = elapsed / 1000f
            val progress = (0.92f * (1f - Math.exp((-t / 1.1f).toDouble()).toFloat()))
                .coerceIn(0f, 0.95f)
            drawLoadingBar(canvas, w, h, progress, shimmer = true, shimmerPhase = elapsed)
        } else {
            val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
            drawLoadingBar(canvas, w, h, progress, shimmer = false, shimmerPhase = 0L)
            if (progress >= 1f && !finished) {
                finished = true
                post { onComplete() }
                return
            }
        }
        postInvalidateOnAnimation()
    }

    private fun drawLoadingBar(
        canvas: Canvas, w: Float, h: Float, progress: Float,
        shimmer: Boolean, shimmerPhase: Long
    ) {
        val barW = w * 0.62f
        val barH = h * 0.022f
        val x0 = (w - barW) / 2f
        val y0 = h * 0.915f
        val r = barH / 2f

        paint.style = Paint.Style.FILL
        paint.color = 0x88000000.toInt()
        canvas.drawRoundRect(x0, y0, x0 + barW, y0 + barH, r, r, paint)

        if (progress > 0f) {
            val fillW = barW * progress
            paint.shader = LinearGradient(
                x0, y0, x0 + barW, y0,
                0xFFE53935.toInt(), gold, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(x0, y0, x0 + fillW, y0 + barH, r, r, paint)
            paint.shader = null

            // Moving shimmer highlight on the filled portion (indeterminate feel).
            if (shimmer && fillW > barH) {
                val sw = barW * 0.18f
                val cycle = 1400f
                val phase = (shimmerPhase % cycle.toLong()) / cycle  // 0..1
                val cx = x0 + (fillW + sw) * phase - sw
                val left = cx.coerceIn(x0, x0 + fillW)
                val right = (cx + sw).coerceIn(x0, x0 + fillW)
                if (right > left) {
                    paint.shader = LinearGradient(
                        left, y0, right, y0,
                        0x00FFFFFF, 0x66FFFFFF, Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(left, y0, right, y0 + barH, r, r, paint)
                    paint.shader = null
                }
            }
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.0035f
        paint.color = gold
        canvas.drawRoundRect(x0, y0, x0 + barW, y0 + barH, r, r, paint)
        paint.style = Paint.Style.FILL
    }
}
