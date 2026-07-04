package com.legendfool.foollegends

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/**
 * Procedurally draws a stylized jester face entirely with Canvas primitives —
 * no bitmap assets. Three emotions drive the brows / mouth:
 *   NORMAL — friendly grin
 *   ANGRY  — furrowed brows, gritted teeth
 *   FUN    — wide open laugh
 */
object JesterArtist {

    enum class Mood { NORMAL, ANGRY, FUN }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    private const val SKIN = 0xFFE9BE96.toInt()
    private const val SKIN_SH = 0xFFD69C74.toInt()
    private const val RED = 0xFFD8352C.toInt()
    private const val RED_DK = 0xFFA5241D.toInt()
    private const val YELLOW = 0xFFF3C21C.toInt()
    private const val GOLD = 0xFFE0A324.toInt()
    private const val EYE = 0xFF3AA046.toInt()
    private const val OUTLINE = 0xFF5A2A18.toInt()

    /**
     * @param cx,cy  center of the face
     * @param size   overall diameter budget (hat + face fit inside roughly this)
     */
    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, mood: Mood) {
        val r = size * 0.30f            // face radius
        val u = r / 100f                // unit
        stroke.color = OUTLINE
        stroke.strokeWidth = 3f * u

        drawBackHat(c, cx, cy, r, u)
        drawFace(c, cx, cy, r, u)
        drawEars(c, cx, cy, r, u)
        drawHatBand(c, cx, cy, r, u)
        drawBrows(c, cx, cy, r, u, mood)
        drawEyes(c, cx, cy, r, u, mood)
        drawNose(c, cx, cy, r, u)
        drawMouth(c, cx, cy, r, u, mood)
        drawCollar(c, cx, cy, r, u)
    }

    // ── hat: two side horns behind the head + bells ─────────────────────
    private fun drawBackHat(c: Canvas, cx: Float, cy: Float, r: Float, u: Float) {
        val topY = cy - r * 1.05f
        // left horn
        hornPath(cx - r * 0.15f, topY, cx - r * 1.55f, cy + r * 0.15f, cx - r * 0.7f, cy - r * 0.2f, r, u)
        fillHorn(c, u)
        bell(c, cx - r * 1.5f, cy + r * 0.2f, r * 0.18f, u)
        // right horn
        hornPath(cx + r * 0.15f, topY, cx + r * 1.55f, cy + r * 0.15f, cx + r * 0.7f, cy - r * 0.2f, r, u)
        fillHorn(c, u)
        bell(c, cx + r * 1.5f, cy + r * 0.2f, r * 0.18f, u)
        // center horn (up)
        path.reset()
        path.moveTo(cx - r * 0.34f, cy - r * 0.85f)
        path.quadTo(cx, cy - r * 1.7f, cx + r * 0.34f, cy - r * 0.85f)
        path.close()
        p.style = Paint.Style.FILL; p.color = RED
        c.drawPath(path, p); c.drawPath(path, stroke)
        bell(c, cx, cy - r * 1.6f, r * 0.17f, u)
    }

    private fun hornPath(x0: Float, y0: Float, tipX: Float, tipY: Float, cX: Float, cY: Float, r: Float, u: Float) {
        path.reset()
        path.moveTo(x0, y0)
        path.quadTo(cX - r * 0.2f, cY - r * 0.5f, tipX, tipY)
        path.quadTo(cX + r * 0.1f, cY + r * 0.5f, x0, y0 + r * 0.55f)
        path.close()
    }

    private fun fillHorn(c: Canvas, u: Float) {
        p.style = Paint.Style.FILL; p.color = YELLOW
        c.drawPath(path, p)
        p.color = 0x33FFFFFF
        c.drawPath(path, p)
        c.drawPath(path, stroke)
    }

    private fun bell(c: Canvas, x: Float, y: Float, rad: Float, u: Float) {
        p.style = Paint.Style.FILL; p.color = GOLD
        c.drawCircle(x, y, rad, p)
        p.color = 0x55FFFFFF
        c.drawCircle(x - rad * 0.3f, y - rad * 0.3f, rad * 0.4f, p)
        p.color = OUTLINE
        c.drawCircle(x, y, rad, stroke)
        c.drawLine(x - rad * 0.6f, y + rad * 0.5f, x + rad * 0.6f, y + rad * 0.5f, stroke)
    }

    // ── face ────────────────────────────────────────────────────────────
    private fun drawFace(c: Canvas, cx: Float, cy: Float, r: Float, u: Float) {
        val rect = RectF(cx - r * 0.92f, cy - r * 1.0f, cx + r * 0.92f, cy + r * 1.15f)
        p.style = Paint.Style.FILL; p.color = SKIN
        c.drawOval(rect, p)
        // chin/jaw shading
        p.color = SKIN_SH
        c.drawArc(rect, 20f, 140f, false, p.apply { /* subtle */ })
        p.color = SKIN
        val inner = RectF(rect.left, rect.top, rect.right, rect.bottom - r * 0.12f)
        c.drawOval(inner, p)
        c.drawOval(rect, stroke)
    }

    private fun drawEars(c: Canvas, cx: Float, cy: Float, r: Float, u: Float) {
        p.style = Paint.Style.FILL; p.color = SKIN
        for (sgn in intArrayOf(-1, 1)) {
            val ex = cx + sgn * r * 0.9f
            c.drawCircle(ex, cy + r * 0.1f, r * 0.2f, p)
            c.drawCircle(ex, cy + r * 0.1f, r * 0.2f, stroke)
        }
    }

    // ── hat band on the forehead (red with gold V + trim) ───────────────
    private fun drawHatBand(c: Canvas, cx: Float, cy: Float, r: Float, u: Float) {
        path.reset()
        path.moveTo(cx - r * 0.95f, cy - r * 0.35f)
        path.quadTo(cx - r * 0.95f, cy - r * 0.95f, cx, cy - r * 0.95f)
        path.quadTo(cx + r * 0.95f, cy - r * 0.95f, cx + r * 0.95f, cy - r * 0.35f)
        // gold V dipping to the brow
        path.lineTo(cx + r * 0.5f, cy - r * 0.35f)
        path.lineTo(cx, cy - r * 0.02f)
        path.lineTo(cx - r * 0.5f, cy - r * 0.35f)
        path.close()
        p.style = Paint.Style.FILL; p.color = RED
        c.drawPath(path, p)
        c.drawPath(path, stroke)
        // gold trim line
        stroke.color = GOLD; stroke.strokeWidth = 5f * u
        path.reset()
        path.moveTo(cx - r * 0.5f, cy - r * 0.35f)
        path.lineTo(cx, cy - r * 0.02f)
        path.lineTo(cx + r * 0.5f, cy - r * 0.35f)
        c.drawPath(path, stroke)
        stroke.color = OUTLINE; stroke.strokeWidth = 3f * u
    }

    // ── brows ───────────────────────────────────────────────────────────
    private fun drawBrows(c: Canvas, cx: Float, cy: Float, r: Float, u: Float, mood: Mood) {
        p.style = Paint.Style.FILL; p.color = 0xFF7A3B1E.toInt()
        val by = cy - r * 0.02f
        val dx = r * 0.34f
        for (sgn in intArrayOf(-1, 1)) {
            path.reset()
            val ex = cx + sgn * dx
            when (mood) {
                Mood.ANGRY -> {
                    // inner-down angled brows
                    path.moveTo(ex - sgn * r * 0.28f, by - r * 0.18f)
                    path.lineTo(ex + sgn * r * 0.28f, by + r * 0.12f)
                    path.lineTo(ex + sgn * r * 0.28f, by + r * 0.24f)
                    path.lineTo(ex - sgn * r * 0.28f, by - r * 0.02f)
                }
                else -> {
                    path.moveTo(ex - r * 0.26f, by)
                    path.quadTo(ex, by - r * 0.20f, ex + r * 0.26f, by - r * 0.02f)
                    path.lineTo(ex + r * 0.26f, by + r * 0.08f)
                    path.quadTo(ex, by - r * 0.08f, ex - r * 0.26f, by + r * 0.10f)
                }
            }
            path.close()
            c.drawPath(path, p)
        }
    }

    // ── eyes ────────────────────────────────────────────────────────────
    private fun drawEyes(c: Canvas, cx: Float, cy: Float, r: Float, u: Float, mood: Mood) {
        val ey = cy + r * 0.22f
        val dx = r * 0.34f
        val ew = r * 0.24f
        val eh = if (mood == Mood.FUN) r * 0.30f else r * 0.26f
        for (sgn in intArrayOf(-1, 1)) {
            val ex = cx + sgn * dx
            // white
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            val wr = RectF(ex - ew, ey - eh, ex + ew, ey + eh)
            c.drawOval(wr, p)
            c.drawOval(wr, stroke)
            // iris
            p.color = EYE
            c.drawCircle(ex, ey, ew * 0.62f, p)
            // pupil
            p.color = Color.BLACK
            c.drawCircle(ex, ey, ew * 0.30f, p)
            // glint
            p.color = 0xCCFFFFFF.toInt()
            c.drawCircle(ex - ew * 0.2f, ey - ew * 0.2f, ew * 0.12f, p)
        }
    }

    private fun drawNose(c: Canvas, cx: Float, cy: Float, r: Float, u: Float) {
        p.style = Paint.Style.FILL; p.color = SKIN_SH
        path.reset()
        path.moveTo(cx, cy + r * 0.30f)
        path.quadTo(cx - r * 0.12f, cy + r * 0.55f, cx, cy + r * 0.60f)
        path.quadTo(cx + r * 0.12f, cy + r * 0.55f, cx, cy + r * 0.30f)
        c.drawPath(path, p)
    }

    // ── mouth ───────────────────────────────────────────────────────────
    private fun drawMouth(c: Canvas, cx: Float, cy: Float, r: Float, u: Float, mood: Mood) {
        val my = cy + r * 0.72f
        when (mood) {
            Mood.FUN -> {
                // wide open laugh
                p.style = Paint.Style.FILL; p.color = RED_DK
                val mr = RectF(cx - r * 0.42f, my - r * 0.12f, cx + r * 0.42f, my + r * 0.5f)
                c.drawOval(mr, p); c.drawOval(mr, stroke)
                // teeth top
                p.color = Color.WHITE
                c.drawRect(cx - r * 0.36f, my - r * 0.10f, cx + r * 0.36f, my + r * 0.02f, p)
                // tongue
                p.color = 0xFFE86A6A.toInt()
                c.drawCircle(cx, my + r * 0.34f, r * 0.16f, p)
            }
            Mood.ANGRY -> {
                // gritted teeth
                p.style = Paint.Style.FILL; p.color = RED_DK
                val mr = RectF(cx - r * 0.4f, my - r * 0.02f, cx + r * 0.4f, my + r * 0.26f)
                c.drawRoundRect(mr, r * 0.06f, r * 0.06f, p); c.drawRoundRect(mr, r * 0.06f, r * 0.06f, stroke)
                p.color = Color.WHITE
                c.drawRect(cx - r * 0.36f, my + r * 0.02f, cx + r * 0.36f, my + r * 0.22f, p)
                // teeth separators
                stroke.strokeWidth = 2f * u
                var x = cx - r * 0.24f
                while (x < cx + r * 0.3f) { c.drawLine(x, my + r * 0.02f, x, my + r * 0.22f, stroke); x += r * 0.12f }
                stroke.strokeWidth = 3f * u
            }
            Mood.NORMAL -> {
                // friendly grin
                p.style = Paint.Style.FILL; p.color = RED_DK
                path.reset()
                path.moveTo(cx - r * 0.42f, my - r * 0.02f)
                path.quadTo(cx, my + r * 0.42f, cx + r * 0.42f, my - r * 0.02f)
                path.quadTo(cx, my + r * 0.16f, cx - r * 0.42f, my - r * 0.02f)
                path.close()
                c.drawPath(path, p); c.drawPath(path, stroke)
                // teeth
                p.color = Color.WHITE
                path.reset()
                path.moveTo(cx - r * 0.36f, my)
                path.quadTo(cx, my + r * 0.12f, cx + r * 0.36f, my)
                path.quadTo(cx, my + r * 0.04f, cx - r * 0.36f, my)
                path.close()
                c.drawPath(path, p)
            }
        }
    }

    // ── jester collar ───────────────────────────────────────────────────
    private fun drawCollar(c: Canvas, cx: Float, cy: Float, r: Float, u: Float) {
        val ty = cy + r * 1.02f
        p.style = Paint.Style.FILL
        val pts = 7
        val span = r * 1.5f
        for (i in 0 until pts) {
            val fx = cx - span + (2 * span) * i / (pts - 1)
            p.color = if (i % 2 == 0) RED else YELLOW
            path.reset()
            path.moveTo(fx - r * 0.2f, ty)
            path.lineTo(fx + r * 0.2f, ty)
            path.lineTo(fx, ty + r * 0.4f)
            path.close()
            c.drawPath(path, p); c.drawPath(path, stroke)
            bell(c, fx, ty + r * 0.42f, r * 0.07f, u)
        }
    }
}
