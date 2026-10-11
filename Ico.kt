package com.macro.rec

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

// أيقونات مرسومة للوحة العائمة (0 تسجيل، 1 تشغيل، 2 إيقاف، 3 سحب)
class Ico(c: Context, private val kind: Int, private val col: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var on = false
        set(v) {
            field = v
            invalidate()
        }

    override fun onMeasure(w: Int, h: Int) {
        val s = (46 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.style = Paint.Style.FILL
        p.color = if (on) (col and 0x00FFFFFF) or 0x66000000 else 0x22FFFFFF
        c.drawCircle(r, r, r - 2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = col
        c.drawCircle(r, r, r - 3f, p)
        p.style = Paint.Style.FILL
        when (kind) {
            0 -> c.drawCircle(r, r, r * 0.36f, p)
            1 -> {
                val t = Path()
                t.moveTo(r * 0.78f, r * 0.6f)
                t.lineTo(r * 1.46f, r)
                t.lineTo(r * 0.78f, r * 1.4f)
                t.close()
                c.drawPath(t, p)
            }
            2 -> c.drawRoundRect(r * 0.66f, r * 0.66f, r * 1.34f, r * 1.34f, 5f, 5f, p)
            else -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 4f
                c.drawLine(r * 0.55f, r, r * 1.45f, r, p)
                c.drawLine(r, r * 0.55f, r, r * 1.45f, p)
            }
        }
    }
}
