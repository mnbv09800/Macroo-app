package com.macro.rec

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

// مسار إصبع واحد أثناء الرسم
class Rec(val off: Long) {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    var end = off
}

// يعرض لقطة اللعبة: إما نحدد عليها زر، أو نرسم عليها الهجوم (بدون ما اللعبة تتفاعل)
class ShotView(c: Context, private val bmp: Bitmap) : View(c) {
    var drawMode = false
    var sel: RectF? = null
    var onStep: ((Step) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f
    private var sx = 0f
    private var sy = 0f
    private var sess = 0L
    private var lastEnd = 0L
    private val rec = HashMap<Int, Rec>()

    private fun calc() {
        sc = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        ox = (width - bmp.width * sc) / 2f
        oy = (height - bmp.height * sc) / 2f
    }

    private fun bx(x: Float) = ((x - ox) / sc).coerceIn(0f, bmp.width.toFloat())
    private fun by(y: Float) = ((y - oy) / sc).coerceIn(0f, bmp.height.toFloat())

    override fun onDraw(c: Canvas) {
        calc()
        c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)
        c.drawBitmap(bmp, 0f, 0f, null)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f / sc
        val s = sel
        if (s != null) {
            p.color = Color.parseColor("#4ADE80")
            c.drawRect(s, p)
        }
        if (drawMode) {
            p.textSize = 26f / sc
            var n = 0
            for (st in Store.macros[Store.cur].steps) {
                n++
                for (k in st.strokes) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 5f / sc
                    p.color = Color.parseColor("#FB923C")
                    val path = Path()
                    path.moveTo(k.pts[0], k.pts[1])
                    var i = 2
                    while (i + 1 < k.pts.size) {
                        path.lineTo(k.pts[i], k.pts[i + 1])
                        i += 2
                    }
                    c.drawPath(path, p)
                    c.drawCircle(k.pts[0], k.pts[1], 14f / sc, p)
                    p.style = Paint.Style.FILL
                    c.drawText("$n", k.pts[0] + 18f / sc, k.pts[1] - 18f / sc, p)
                }
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f / sc
            p.color = Color.parseColor("#FACC15")
            for (r in rec.values) {
                val path = Path()
                path.moveTo(r.xs[0], r.ys[0])
                for (i in 1 until r.xs.size) path.lineTo(r.xs[i], r.ys[i])
                c.drawPath(path, p)
            }
        }
        c.restore()
    }

    private fun add(r: Rec, e: MotionEvent, i: Int) {
        r.xs.add(bx(e.getX(i)))
        r.ys.add(by(e.getY(i)))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        calc()
        if (!drawMode) {
            val x = bx(e.x)
            val y = by(e.y)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = x
                    sy = y
                    sel = RectF(x, y, x, y)
                }
                MotionEvent.ACTION_MOVE -> {
                    sel = RectF(minOf(sx, x), minOf(sy, y), maxOf(sx, x), maxOf(sy, y))
                }
            }
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rec.clear()
                sess = e.eventTime
                val r = Rec(0L)
                add(r, e, 0)
                rec[e.getPointerId(0)] = r
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val r = Rec(e.eventTime - sess)
                add(r, e, i)
                rec[e.getPointerId(i)] = r
            }
            MotionEvent.ACTION_MOVE -> {
                for (n in 0 until e.pointerCount) {
                    val r = rec[e.getPointerId(n)] ?: continue
                    for (k in 0 until e.historySize) {
                        r.xs.add(bx(e.getHistoricalX(n, k)))
                        r.ys.add(by(e.getHistoricalY(n, k)))
                    }
                    add(r, e, n)
                    r.end = e.eventTime - sess
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val i = e.actionIndex
                val r = rec[e.getPointerId(i)]
                if (r != null) {
                    add(r, e, i)
                    r.end = e.eventTime - sess
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) finish(e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> finish(e.eventTime)
        }
        invalidate()
        return true
    }

    // نخفّف النقاط (حد أقصى 40)، وإذا الإصبع ما تحرك نعتبرها ضغطة
    private fun thin(r: Rec): FloatArray {
        val n = r.xs.size
        var move = 0f
        for (i in 1 until n) move = maxOf(move, abs(r.xs[i] - r.xs[0]), abs(r.ys[i] - r.ys[0]))
        if (move < 20f) return floatArrayOf(r.xs[0], r.ys[0])
        val k = minOf(n, 40)
        val out = FloatArray(k * 2)
        for (i in 0 until k) {
            val j = i * (n - 1) / (k - 1)
            out[i * 2] = r.xs[j]
            out[i * 2 + 1] = r.ys[j]
        }
        return out
    }

    private fun finish(now: Long) {
        if (rec.isEmpty()) return
        val list = ArrayList<Stroke>()
        for (r in rec.values.sortedBy { it.off }.take(10)) {
            list.add(Stroke(minOf(r.off, 25000L), minOf(maxOf(r.end - r.off, 40L), 30000L), thin(r)))
        }
        val first = Store.macros[Store.cur].steps.isEmpty()
        val gap = if (first) 0L else if (lastEnd == 0L) 500L else sess - lastEnd
        rec.clear()
        lastEnd = now
        onStep?.invoke(Step(list, gap, false))
    }
}

class EditorActivity : Activity() {
    private val BG = Color.parseColor("#0B1220")
    private val LINE = Color.parseColor("#22304A")
    private val CY = Color.parseColor("#22D3EE")
    private val GR = Color.parseColor("#4ADE80")
    private val RD = Color.parseColor("#FB7185")
    private val TX = Color.parseColor("#E5E7EB")
    private val DIM = Color.parseColor("#94A3B8")
    private lateinit var sv: ShotView
    private lateinit var bar: LinearLayout
    private lateinit var foot: LinearLayout
    private var draw = false
    private var role = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Shared.pending = false
        val bmp = Shared.shot
        if (bmp == null) {
            Toast.makeText(this, "ما أكو لقطة. اضغط 📷 من اللوحة داخل اللعبة", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Store.ensure(this)
        if (role.isEmpty()) role = Store.btns.firstOrNull()?.id ?: ""
        requestedOrientation = if (bmp.width > bmp.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        bar = LinearLayout(this)
        bar.setPadding(dp(8), dp(8), dp(8), dp(4))
        val hs = HorizontalScrollView(this)
        hs.addView(bar)
        sv = ShotView(this, bmp)
        sv.onStep = { st ->
            Store.macros[Store.cur].steps.add(st)
            Store.save(this)
            render()
        }
        foot = LinearLayout(this)
        foot.gravity = Gravity.CENTER_VERTICAL
        foot.setPadding(dp(8), 0, dp(8), dp(8))
        root.addView(hs)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(foot)
        setContentView(root)
        render()
    }

    private fun chip(t: String, sel: Boolean, click: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(if (sel) BG else TX)
        background = GradientDrawable().apply {
            setColor(if (sel) CY else Color.TRANSPARENT)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), if (sel) CY else LINE)
        }
        setPadding(dp(14), dp(7), dp(14), dp(7))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(8), 0) }
        setOnClickListener { click() }
    }

    private fun btn(t: String, col: Int, click: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(col)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), col)
        }
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(6), dp(8), 0) }
        setOnClickListener { click() }
    }

    private fun render() {
        bar.removeAllViews()
        foot.removeAllViews()
        bar.addView(chip("تحديد زر", !draw) { draw = false; sv.drawMode = false; sv.invalidate(); render() })
        bar.addView(chip("رسم الهجوم", draw) { draw = true; sv.drawMode = true; sv.sel = null; sv.invalidate(); render() })
        if (!draw) {
            for (d in Store.btns) {
                val k = d.id
                val on = Store.anchors.containsKey(k)
                bar.addView(chip((if (on) "✔ " else "") + d.name, k == role) {
                    role = k
                    sv.sel = null
                    sv.invalidate()
                    render()
                })
            }
            for (r in Store.res) {
                bar.addView(chip((if (r.w > 0) "✔ " else "") + r.icon + " " + r.name, r.id == role) {
                    role = r.id
                    sv.sel = null
                    sv.invalidate()
                    render()
                })
            }
            foot.addView(btn("حفظ الزر", GR) { saveAnchor() })
            foot.addView(btn("مسح صورة الزر", RD) {
                Store.anchors.remove(role)
                Store.res.firstOrNull { it.id == role }?.let { it.w = 0 }
                Store.save(this)
                render()
            })
        } else {
            val steps = Store.macros[Store.cur].steps
            foot.addView(btn("تراجع", CY) {
                if (steps.isNotEmpty()) steps.removeAt(steps.size - 1)
                Store.save(this)
                sv.invalidate()
                render()
            })
            foot.addView(btn("مسح الكل", RD) {
                steps.clear()
                Store.save(this)
                sv.invalidate()
                render()
            })
            foot.addView(TextView(this).apply {
                text = "${steps.size} خطوة"
                textSize = 14f
                setTextColor(DIM)
                setPadding(dp(4), dp(6), dp(12), 0)
            })
        }
        foot.addView(btn("رجوع", DIM) { finish() })
    }

    private fun saveAnchor() {
        val rs = Store.res.firstOrNull { it.id == role }
        if (rs == null && Store.btns.none { it.id == role }) {
            Toast.makeText(this, "اختر زر من فوق أول", Toast.LENGTH_SHORT).show()
            return
        }
        val s = sv.sel
        val bmp = Shared.shot
        if (s == null || bmp == null || s.width() < 8f || s.height() < 8f) {
            Toast.makeText(this, "حدد مستطيل حوالين الزر أول (اسحب بإصبعك)", Toast.LENGTH_SHORT).show()
            return
        }
        Store.refW = bmp.width
        Store.refH = bmp.height
        val x = s.left.toInt()
        val y = s.top.toInt()
        val w = s.width().toInt()
        val h = s.height().toInt()
        if (rs != null) {
            rs.x = x
            rs.y = y
            rs.w = w
            rs.h = h
        } else {
            Store.anchors[role] = Anchor(role, x, y, w, h, Vision.sig(bmp, x, y, w, h))
        }
        Store.save(this)
        sv.sel = null
        sv.invalidate()
        Toast.makeText(this, "انحفظ ✔", Toast.LENGTH_SHORT).show()
        render()
    }
}
