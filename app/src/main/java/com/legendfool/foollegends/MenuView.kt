package com.legendfool.foollegends

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sin

class MenuView(context: Context) : View(context) {

    private val prefs = context.getSharedPreferences("fool_legends", Context.MODE_PRIVATE)
    private val joker  = decode(R.drawable.joker_normal)

    // Paints.
    private val fill   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val tp     = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val black  = Typeface.create("sans-serif-black",  Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // Colors.
    private val gold    = 0xFFF4C95B.toInt()
    private val goldDim = 0xFFB8881E.toInt()
    private val white   = Color.WHITE
    private val muted   = 0xFFBBAA88.toInt()
    private val darkRed = 0xFF2A0608.toInt()

    // Touch state.
    private var pressedBtn = -1   // 0=play, 1=privacy, 2=support

    // Layout rects (computed in onSizeChanged).
    private var vw = 0f; private var vh = 0f; private var pad = 0f
    private val jokerDst    = RectF()
    private val playBtn     = RectF()
    private val privacyBtn  = RectF()
    private val supportBtn  = RectF()
    private var bg: Bitmap? = null

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun decode(id: Int): Bitmap =
        (resources.getDrawable(id, null) as? BitmapDrawable)?.bitmap
            ?: BitmapFactory.decodeResource(resources, id)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vw = w.toFloat(); vh = h.toFloat(); pad = w * 0.06f

        // Joker — large upper area.
        val jMaxW = w * 0.72f; val jMaxH = h * 0.32f
        val jSc = min(jMaxW / joker.width, jMaxH / joker.height)
        val jW = joker.width * jSc; val jH = joker.height * jSc
        val jTop = h * 0.040f
        jokerDst.set((w - jW) / 2f, jTop, (w + jW) / 2f, jTop + jH)

        // PLAY button — wide, tall, golden.
        val pbW = w * 0.72f; val pbH = h * 0.088f
        val pbY = h * 0.640f
        playBtn.set((w - pbW) / 2f, pbY, (w + pbW) / 2f, pbY + pbH)

        // Footer link buttons.
        val lnkH = h * 0.065f; val gap = w * 0.04f
        val lnkW = (w - 2 * pad - gap) / 2f
        val lnkY = h * 0.875f
        privacyBtn.set(pad,           lnkY, pad + lnkW,  lnkY + lnkH)
        supportBtn.set(w - pad - lnkW, lnkY, w - pad, lnkY + lnkH)

        buildBg(w, h)
    }

    private fun buildBg(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        bg?.recycle()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Deep dark red gradient.
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            0xFF360D10.toInt(), 0xFF0E0407.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Center glow.
        p.shader = RadialGradient(w / 2f, h * 0.35f, h * 0.45f,
            0x44AA2020, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Gold frame.
        p.shader = null; p.style = Paint.Style.STROKE
        p.color = 0x66F4C95B; p.strokeWidth = w * 0.005f
        val ins = w * 0.025f
        c.drawRoundRect(ins, ins, w - ins, h - ins, w * 0.05f, w * 0.05f, p)
        bg = b
    }

    private var running = false
    override fun onAttachedToWindow()  { super.onAttachedToWindow();  running = true;  postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        bg?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
            ?: canvas.drawColor(0xFF0E0407.toInt())

        drawJoker(canvas)
        drawTitle(canvas)
        drawBestAndRules(canvas)
        drawPlayButton(canvas)
        drawFooterLinks(canvas)

        if (running) postInvalidateOnAnimation()
    }

    private fun drawJoker(c: Canvas) {
        val cx = jokerDst.centerX(); val cy = jokerDst.centerY(); val r = jokerDst.width() * 0.5f
        // Warm glow behind the joker.
        fill.shader = RadialGradient(cx, cy, r, 0x60FFB830, 0x00000000, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        // Joker bitmap — bitmapPaint is always opaque white (filter only).
        c.drawBitmap(joker, null, jokerDst, bitmapPaint)
    }

    private fun drawTitle(c: Canvas) {
        val t = SystemClock.uptimeMillis()
        val pulse = (0.55f + 0.45f * sin(t / 700.0)).toFloat()

        tp.typeface = black; tp.style = Paint.Style.FILL
        var sz = vh * 0.072f
        tp.textSize = sz
        val s = "FOOL LEGENDS"
        val w = tp.measureText(s)
        if (w > vw * 0.90f) sz *= vw * 0.90f / w
        tp.textSize = sz

        // Shadow.
        tp.style = Paint.Style.STROKE; tp.strokeWidth = sz * 0.10f
        tp.color = 0xCC000000.toInt()
        c.drawText(s, vw / 2f, vh * 0.435f, tp)
        // Gold fill.
        tp.style = Paint.Style.FILL
        tp.color = blend(gold, Color.WHITE, 0.10f + 0.08f * pulse)
        c.drawText(s, vw / 2f, vh * 0.435f, tp)
    }

    private fun drawBestAndRules(c: Canvas) {
        val best = prefs.getInt("best_level", 0)
        if (best > 0) {
            tp.typeface = medium; tp.style = Paint.Style.FILL
            tp.textSize = vh * 0.028f; tp.color = gold
            c.drawText("BEST  $best", vw / 2f, vh * 0.480f, tp)
        }

        tp.textSize = vh * 0.026f; tp.color = white
        c.drawText("Tap the color the WORD says.", vw / 2f, vh * 0.540f, tp)
        tp.textSize = vh * 0.022f; tp.color = muted
        c.drawText("The Joker paints it wrong — read, don't react.", vw / 2f, vh * 0.574f, tp)
        c.drawText("Be fast. One miss ends the run.", vw / 2f, vh * 0.604f, tp)
    }

    private fun drawPlayButton(c: Canvas) {
        val t = SystemClock.uptimeMillis()
        val pulse = (0.5f + 0.5f * sin(t / 480.0)).toFloat()
        val r = playBtn
        val pressed = pressedBtn == 0
        val shrink = if (pressed) r.width() * 0.012f else 0f
        val rr = RectF(r.left + shrink, r.top + shrink, r.right - shrink, r.bottom - shrink)
        val rad = rr.height() * 0.40f

        // Outer pulse glow.
        fill.color = applyAlpha(gold, (0.20f + 0.12f * pulse).coerceIn(0f, 1f))
        val ex = rr.height() * 0.7f
        c.drawRoundRect(rr.left - ex, rr.top - ex, rr.right + ex, rr.bottom + ex, rad + ex, rad + ex, fill)

        // Button body — top-lit gold gradient.
        fill.shader = LinearGradient(rr.left, rr.top, rr.left, rr.bottom,
            blend(gold, Color.WHITE, if (pressed) 0.05f else 0.28f),
            blend(goldDim, Color.BLACK, if (pressed) 0.20f else 0.08f),
            Shader.TileMode.CLAMP)
        c.drawRoundRect(rr, rad, rad, fill)
        fill.shader = null

        // Top gloss.
        fill.color = 0x38FFFFFF
        c.drawRoundRect(rr.left + rr.width() * 0.08f, rr.top + rr.height() * 0.08f,
            rr.right - rr.width() * 0.08f, rr.top + rr.height() * 0.46f, rad, rad, fill)

        // Label.
        tp.typeface = black; tp.style = Paint.Style.FILL
        val sz = rr.height() * 0.46f
        tp.textSize = sz; tp.color = darkRed
        c.drawText("PLAY", vw / 2f, rr.centerY() + sz * 0.36f, tp)
    }

    private fun drawFooterLinks(c: Canvas) {
        drawLinkBtn(c, privacyBtn, "Privacy Policy")
        drawLinkBtn(c, supportBtn, "Support")
    }

    private fun drawLinkBtn(c: Canvas, r: RectF, label: String) {
        val rad = r.height() * 0.34f
        fill.color = 0x22FFFFFF; c.drawRoundRect(r, rad, rad, fill)
        fill.style = Paint.Style.STROKE
        // reuse fill as stroke-paint just for the border
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = r.height() * 0.04f
            color = 0x33FFFFFF
        }
        c.drawRoundRect(r, rad, rad, border)
        fill.style = Paint.Style.FILL

        tp.typeface = medium; tp.style = Paint.Style.FILL
        tp.textSize = r.height() * 0.36f; tp.color = muted
        val w = tp.measureText(label)
        if (w > r.width() * 0.88f) tp.textSize *= r.width() * 0.88f / w
        c.drawText(label, r.centerX(), r.centerY() + tp.textSize * 0.36f, tp)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedBtn = when {
                    playBtn.contains(e.x, e.y)    -> 0
                    privacyBtn.contains(e.x, e.y) -> 1
                    supportBtn.contains(e.x, e.y) -> 2
                    else -> -1
                }
                invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                val p = pressedBtn; pressedBtn = -1; invalidate()
                when {
                    p == 0 && playBtn.contains(e.x, e.y) ->
                        context.startActivity(Intent(context, GameActivity::class.java))
                    p == 1 && privacyBtn.contains(e.x, e.y) ->
                        openWeb(WebActivity.PRIVACY_URL, "Privacy Policy")
                    p == 2 && supportBtn.contains(e.x, e.y) ->
                        openWeb(WebActivity.SUPPORT_URL, "Support")
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressedBtn = -1; invalidate() }
        }
        return true
    }

    private fun openWeb(url: String, title: String) {
        context.startActivity(Intent(context, WebActivity::class.java)
            .putExtra(WebActivity.EXTRA_URL, url)
            .putExtra(WebActivity.EXTRA_TITLE, title))
    }

    // ---------------------------------------------------------------- utils

    private fun applyAlpha(c: Int, a: Float): Int =
        ((255 * a.coerceIn(0f, 1f)).toInt() shl 24) or (c and 0x00FFFFFF)

    private fun blend(c: Int, w: Int, f: Float): Int {
        val r = (Color.red(c)   * (1 - f) + Color.red(w)   * f).toInt()
        val g = (Color.green(c) * (1 - f) + Color.green(w) * f).toInt()
        val b = (Color.blue(c)  * (1 - f) + Color.blue(w)  * f).toInt()
        return Color.rgb(r, g, b)
    }
}
