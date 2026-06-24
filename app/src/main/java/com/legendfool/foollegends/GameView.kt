package com.legendfool.foollegends

import android.content.Context
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
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pure game screen. Starts immediately (no embedded menu).
 * State: PLAY → CORRECT → PLAY … → GAMEOVER
 * On GAMEOVER: "MENU" button returns to MainActivity, "AGAIN" restarts.
 */
class GameView(context: Context) : View(context) {

    private enum class State { PLAY, CORRECT, GAMEOVER }

    private val prefs = context.getSharedPreferences("fool_legends", Context.MODE_PRIVATE)
    private val rnd = Random.Default

    private val jokerNormal = decode(R.drawable.joker_normal)
    private val jokerAngry  = decode(R.drawable.joker_angry)
    private val jokerFun    = decode(R.drawable.joker_fun)
    private val vibrator    = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val fill   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp     = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val black  = Typeface.create("sans-serif-black",  Typeface.NORMAL)
    private val bold   = Typeface.create("sans-serif",        Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val gold    = 0xFFF4C95B.toInt()
    private val cream   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFFB9A98A.toInt()
    private val redBad  = 0xFFFF453A.toInt()
    private val greenOk = 0xFF32D74B.toInt()
    private val darkRed = 0xFF3A0A0C.toInt()

    // Geometry.
    private var vw = 0f; private var vh = 0f; private var pad = 0f
    private val chipLeft  = RectF(); private val chipRight = RectF()
    private val timerBar  = RectF()
    private val jokerRect = RectF()
    private val wordCard  = RectF()
    private val buttons   = Array(4) { RectF() }
    private val menuBtn   = RectF(); private val againBtn = RectF()
    private var bg: Bitmap? = null

    // State.
    private var state      = State.PLAY
    private var stateStart = 0L
    private var now        = 0L
    var level              = 1
    private var best       = prefs.getInt("best_level", 0)
    private var round: Round = LevelRules.generate(1)
    private var deadline   = 0L

    private var pressed   = -1
    private var flashBtn  = -1
    private var flashStart = -10_000L
    private var flashOk   = false

    var onMenuRequested: (() -> Unit)? = null

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun decode(id: Int): Bitmap =
        (resources.getDrawable(id, null) as? BitmapDrawable)?.bitmap
            ?: BitmapFactory.decodeResource(resources, id)

    // ---------------------------------------------------------------- lifecycle

    private var running = false
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            now = SystemClock.uptimeMillis()
            update(); invalidate()
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true; now = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vw = w.toFloat(); vh = h.toFloat(); pad = w * 0.05f

        val chipH = h * 0.058f; val chipW = w * 0.34f; val chipY = h * 0.035f
        chipLeft.set(pad, chipY, pad + chipW, chipY + chipH)
        chipRight.set(w - pad - chipW, chipY, w - pad, chipY + chipH)

        timerBar.set(pad, h * 0.115f, w - pad, h * 0.115f + h * 0.012f)

        val jh = h * 0.16f; val jw = w * 0.5f
        jokerRect.set((w - jw) / 2f, h * 0.145f, (w + jw) / 2f, h * 0.145f + jh)

        wordCard.set(w * 0.09f, h * 0.335f, w * 0.91f, h * 0.475f)

        val top = h * 0.520f; val bottom = h * 0.955f
        val gap = w * 0.04f
        val bw = (w - 2 * pad - gap) / 2f; val bh = (bottom - top - gap) / 2f
        fun place(i: Int, c: Int, r: Int) {
            val x = pad + c * (bw + gap); val y = top + r * (bh + gap)
            buttons[i].set(x, y, x + bw, y + bh)
        }
        place(0, 0, 0); place(1, 1, 0); place(2, 0, 1); place(3, 1, 1)

        // Game-over buttons.
        val gbW = (w - 2 * pad - gap) / 2f; val gbH = h * 0.08f; val gbY = h * 0.80f
        menuBtn.set(pad,          gbY, pad + gbW,  gbY + gbH)
        againBtn.set(w - pad - gbW, gbY, w - pad, gbY + gbH)

        buildBg(w, h)
    }

    private fun buildBg(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        bg?.recycle()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            0xFF2A0A10.toInt(), 0xFF12050A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w / 2f, h * 0.34f, h * 0.5f,
            0x33B0303C, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.color = 0x55F4C95B; p.strokeWidth = w * 0.004f
        val ins = w * 0.025f
        c.drawRoundRect(ins, ins, w - ins, h - ins, w * 0.06f, w * 0.06f, p)
        bg = b
    }

    // ---------------------------------------------------------------- game logic

    fun startRound() {
        // Always read fresh time — 'now' may be 0 before first Choreographer frame.
        val t = SystemClock.uptimeMillis()
        now = t
        round = LevelRules.generate(level, rnd)
        state = State.PLAY; stateStart = t
        deadline = t + round.timeMs
        flashBtn = -1; pressed = -1
    }

    private fun update() {
        when (state) {
            State.PLAY -> if (now >= deadline) fail(-1)
            State.CORRECT -> if (now - stateStart >= 160L) { level++; startRound() }
            else -> Unit
        }
    }

    private fun colorAt(pos: Int): GameColor = round.layout.getOrElse(pos) { GameColor.entries[pos] }

    private fun onTap(pos: Int) {
        if (state != State.PLAY) return
        if (colorAt(pos) == round.answer) {
            flashBtn = pos; flashStart = now; flashOk = true; buzz(14)
            state = State.CORRECT; stateStart = now
        } else {
            fail(pos)
        }
    }

    private fun fail(wrongPos: Int) {
        flashBtn = wrongPos; flashStart = now; flashOk = false
        if (level > best) { best = level; prefs.edit().putInt("best_level", best).apply() }
        state = State.GAMEOVER; stateStart = now; buzz(220)
    }

    private fun buzz(ms: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when (state) {
                    State.PLAY -> {
                        val h = btnAt(e.x, e.y)
                        if (h >= 0) pressed = h
                    }
                    State.GAMEOVER -> {
                        if (menuBtn.contains(e.x, e.y))  pressed = 10
                        if (againBtn.contains(e.x, e.y)) pressed = 11
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                when {
                    state == State.PLAY && pressed in 0..3 && btnAt(e.x, e.y) == pressed -> onTap(pressed)
                    state == State.GAMEOVER && pressed == 10 && menuBtn.contains(e.x, e.y)  -> onMenuRequested?.invoke()
                    state == State.GAMEOVER && pressed == 11 && againBtn.contains(e.x, e.y) -> { level = 1; startRound() }
                }
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> pressed = -1
        }
        return true
    }

    private fun btnAt(x: Float, y: Float): Int {
        for (i in buttons.indices) if (buttons[i].contains(x, y)) return i
        return -1
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        try {
            bg?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                ?: canvas.drawColor(0xFF12050A.toInt())

            val shakeX = if (state == State.GAMEOVER) shake() else 0f
            canvas.save(); canvas.translate(shakeX, 0f)

            drawChips(canvas)
            drawTimer(canvas)
            drawJoker(canvas)
            drawWordCard(canvas)
            for (i in 0..3) drawButton(canvas, i)

            canvas.restore()

            if (state == State.GAMEOVER) drawGameOver(canvas)

        } catch (_: Exception) {}
    }

    private fun shake(): Float {
        val t = (now - stateStart).toFloat()
        if (t > 550f) return 0f
        return sin(t / 19f) * 36f * (1f - t / 550f)
    }

    private fun chip(c: Canvas, r: RectF, label: String, value: String, accent: Int) {
        val rad = r.height() * 0.34f
        fill.color = 0x1FFFFFFF
        c.drawRoundRect(r, rad, rad, fill)
        stroke.color = 0x33FFFFFF; stroke.strokeWidth = r.height() * 0.04f
        c.drawRoundRect(r, rad, rad, stroke)
        tp.textAlign = Paint.Align.LEFT; tp.typeface = medium
        tp.textSize = r.height() * 0.30f; tp.color = muted
        c.drawText(label, r.left + r.height() * 0.4f, r.centerY() - r.height() * 0.07f, tp)
        tp.typeface = black; tp.textSize = r.height() * 0.46f; tp.color = accent
        c.drawText(value, r.left + r.height() * 0.4f, r.centerY() + r.height() * 0.36f, tp)
        tp.textAlign = Paint.Align.CENTER
    }

    private fun drawChips(c: Canvas) {
        chip(c, chipLeft,  "LEVEL", level.toString(), cream)
        chip(c, chipRight, "BEST",  best.toString(),  gold)
    }

    private fun drawTimer(c: Canvas) {
        val rad = timerBar.height() / 2f
        fill.color = 0x22FFFFFF
        c.drawRoundRect(timerBar, rad, rad, fill)
        if (state != State.PLAY) return
        val frac = ((deadline - now).toFloat() / round.timeMs).coerceIn(0f, 1f)
        fill.color = when {
            frac > 0.50f -> greenOk
            frac > 0.22f -> gold
            else         -> redBad
        }
        c.drawRoundRect(timerBar.left, timerBar.top,
            timerBar.left + timerBar.width() * frac, timerBar.bottom, rad, rad, fill)
    }

    private fun drawJoker(c: Canvas) {
        val t = (now - stateStart).toFloat()
        val lowTime = state == State.PLAY && (deadline - now).toFloat() / round.timeMs < 0.28f
        val bmp = when { state == State.GAMEOVER -> jokerFun; lowTime -> jokerAngry; else -> jokerNormal }
        fill.shader = RadialGradient(jokerRect.centerX(), jokerRect.centerY(), jokerRect.width() * 0.62f,
            (if (lowTime) 0x44FF453A else 0x40FFD777), 0x00000000, Shader.TileMode.CLAMP)
        c.drawCircle(jokerRect.centerX(), jokerRect.centerY(), jokerRect.width() * 0.62f, fill)
        fill.shader = null
        val s = min(jokerRect.width() / bmp.width, jokerRect.height() / bmp.height)
        val dw = bmp.width * s; val dh = bmp.height * s
        c.drawBitmap(bmp, null,
            RectF(jokerRect.centerX() - dw / 2f, jokerRect.centerY() - dh / 2f,
                  jokerRect.centerX() + dw / 2f, jokerRect.centerY() + dh / 2f), fill)
    }

    private fun drawWordCard(c: Canvas) {
        if (state == State.GAMEOVER) return
        val rad = wordCard.height() * 0.22f
        fill.color = 0x40000000
        c.drawRoundRect(wordCard, rad, rad, fill)
        stroke.strokeWidth = vh * 0.0035f
        stroke.color = if (round.mismatch) 0x66FF453A else 0x55F4C95B
        c.drawRoundRect(wordCard, rad, rad, stroke)

        val hh = wordCard.height(); val top = wordCard.top

        // Header.
        val header = if (round.mismatch) "JOKER LIES" else "JOKER SAYS"
        val headerColor = if (round.mismatch) redBad else gold
        txt(c, header, vw / 2f, top + hh * 0.28f, vh * 0.024f, wordCard.width() * 0.82f, headerColor, black)

        // Big color word painted in the lying ink.
        txt(c, round.word.display, vw / 2f, top + hh * 0.84f, vh * 0.072f,
            wordCard.width() * 0.88f, round.ink.rgb, black, outline = true)

        // Short hint for early levels.
        val hint = when {
            level <= 3 -> "tap the button that matches the WORD"
            level in 4..5 -> "ignore the color — read the word"
            else -> null
        }
        if (hint != null)
            txt(c, hint, vw / 2f, wordCard.bottom + vh * 0.03f, vh * 0.021f, vw * 0.88f, muted, medium)
    }

    private fun drawButton(c: Canvas, pos: Int) {
        val r = buttons[pos]; val color = colorAt(pos)
        val isPressed = pressed == pos
        val flashing  = flashBtn == pos && now - flashStart < 270L
        val sh = if (isPressed) r.width() * 0.025f else 0f
        val rr = RectF(r.left + sh, r.top + sh, r.right - sh, r.bottom - sh)
        val rad = rr.width() * 0.22f; val base = color.rgb

        fill.color = 0x44000000
        c.drawRoundRect(rr.left, rr.top + rr.height() * 0.05f,
            rr.right, rr.bottom + rr.height() * 0.06f, rad, rad, fill)

        fill.shader = LinearGradient(rr.left, rr.top, rr.left, rr.bottom,
            blend(base, Color.WHITE, 0.14f), blend(base, Color.BLACK, 0.26f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rr, rad, rad, fill)
        fill.shader = null

        fill.color = 0x33FFFFFF
        c.drawRoundRect(rr.left + rr.width() * 0.12f, rr.top + rr.height() * 0.09f,
            rr.right - rr.width() * 0.12f, rr.top + rr.height() * 0.38f, rad, rad, fill)

        if (flashing) {
            fill.color = if (flashOk) 0x88FFFFFF.toInt() else 0x55000000
            c.drawRoundRect(rr, rad, rad, fill)
        }
        stroke.strokeWidth = rr.width() * if (flashing) 0.06f else 0.03f
        stroke.color = when {
            flashing && flashOk  -> Color.WHITE
            flashing && !flashOk -> redBad
            else -> blend(base, Color.BLACK, 0.35f)
        }
        c.drawRoundRect(rr, rad, rad, stroke)
    }

    private fun drawGameOver(c: Canvas) {
        fill.color = 0xD0000000.toInt()
        c.drawRect(0f, 0f, vw, vh, fill)

        val bmp = jokerFun
        val sc = min(vw * 0.70f / bmp.width, vh * 0.28f / bmp.height)
        val dw = bmp.width * sc; val dh = bmp.height * sc
        c.drawBitmap(bmp, null,
            RectF(vw / 2f - dw / 2f, vh * 0.15f, vw / 2f + dw / 2f, vh * 0.15f + dh), fill)

        txt(c, "FOOLED!",      vw / 2f, vh * 0.54f, vh * 0.072f, vw * 0.86f, redBad, black, true)
        txt(c, "Level reached",vw / 2f, vh * 0.60f, vh * 0.025f, vw * 0.8f,  muted,  medium)
        txt(c, level.toString(),vw / 2f, vh * 0.655f,vh * 0.062f, vw * 0.7f,  cream,  black)
        txt(c, "Best  $best",  vw / 2f, vh * 0.71f, vh * 0.030f, vw * 0.7f,  gold,   bold)

        // Buttons: MENU  |  PLAY AGAIN
        overBtn(c, menuBtn,  "MENU",       0x33FFFFFF, cream)
        overBtn(c, againBtn, "PLAY AGAIN", gold,       darkRed)
    }

    private fun overBtn(c: Canvas, r: RectF, label: String, bg: Int, textColor: Int) {
        val rad = r.height() * 0.36f
        fill.color = if (bg == gold) bg else 0x00000000
        if (bg == gold) {
            fill.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
                blend(gold, Color.WHITE, 0.2f), blend(gold, Color.BLACK, 0.15f), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = null
        if (bg != gold) {
            fill.color = 0x22FFFFFF; c.drawRoundRect(r, rad, rad, fill)
            stroke.color = 0x44FFFFFF; stroke.strokeWidth = r.height() * 0.06f
            c.drawRoundRect(r, rad, rad, stroke)
        }
        txt(c, label, r.centerX(), r.centerY() + r.height() * 0.14f,
            r.height() * 0.38f, r.width() * 0.88f, textColor, black)
    }

    // ---------------------------------------------------------------- text util

    private fun txt(c: Canvas, s: String, cx: Float, cy: Float, size: Float, maxW: Float,
                    color: Int, tf: Typeface, outline: Boolean = false) {
        tp.typeface = tf; tp.textSize = size; tp.style = Paint.Style.FILL
        val w = tp.measureText(s)
        if (w > maxW) tp.textSize = size * (maxW / w)
        if (outline) {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = tp.textSize * 0.11f
            tp.color = 0xCC000000.toInt()
            c.drawText(s, cx, cy, tp)
            tp.style = Paint.Style.FILL
        }
        tp.color = color; c.drawText(s, cx, cy, tp)
    }

    private fun blend(c: Int, w: Int, f: Float): Int {
        val r = (Color.red(c) * (1 - f) + Color.red(w) * f).toInt()
        val g = (Color.green(c) * (1 - f) + Color.green(w) * f).toInt()
        val b = (Color.blue(c) * (1 - f) + Color.blue(w) * f).toInt()
        return Color.rgb(r, g, b)
    }
}
