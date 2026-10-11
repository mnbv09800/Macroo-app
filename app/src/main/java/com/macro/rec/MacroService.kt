package com.macro.rec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

// مسار إصبع واحد أثناء التسجيل
class Rec(val off: Long) {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    var end = off
}

class MacroService : AccessibilityService() {
    companion object {
        var inst: MacroService? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private val steps: ArrayList<Step> get() = Store.macros[Store.cur].steps
    private lateinit var wm: WindowManager
    private lateinit var shield: View
    private lateinit var shieldLp: LP
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var slotBtn: TextView
    private lateinit var icRec: Ico
    private lateinit var icPlay: Ico
    var panelOn = false
    private var recording = false
    private var playing = false
    private var lastEnd = 0L
    private var loopNo = 0
    private val rec = HashMap<Int, Rec>()
    private var sess = 0L
    private val queue = ArrayList<Step>()
    private var flushing = false
    private val flushRun = Runnable { flush() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        inst = this
        Store.ensure(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hidePanel()
        inst = null
        return super.onUnbind(intent)
    }

    // ---- اللوحة العائمة (تظهر لما تضغط ابدأ من التطبيق) ----
    fun showPanel() {
        if (panelOn) return
        panelOn = true

        // طبقة شفافة تلتقط اللمسات وقت التسجيل
        shield = View(this)
        shieldLp = LP(
            LP.MATCH_PARENT, LP.MATCH_PARENT, LP.TYPE_ACCESSIBILITY_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or
                LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        shield.setOnTouchListener { _, e ->
            onShield(e)
            true
        }
        wm.addView(shield, shieldLp)

        panel = LinearLayout(this)
        panel.gravity = Gravity.CENTER_VERTICAL
        panel.setPadding(dp(8), dp(6), dp(8), dp(6))
        panel.background = GradientDrawable().apply {
            setColor(Color.parseColor("#E60B1220"))
            cornerRadius = dp(30).toFloat()
            setStroke(dp(1), Color.parseColor("#4422D3EE"))
        }
        fun ico(kind: Int, col: String, click: () -> Unit): Ico {
            val v = Ico(this, kind, Color.parseColor(col))
            v.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), 0, dp(3), 0) }
            v.setOnClickListener { click() }
            panel.addView(v)
            return v
        }
        val handle = ico(3, "#FFFFFF") {}
        icRec = ico(0, "#FB7185") { if (recording) stopAll() else startRec() }
        icPlay = ico(1, "#4ADE80") { if (playing) stopAll() else startPlay() }
        ico(2, "#22D3EE") { stopAll() }
        slotBtn = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#22D3EE"))
            setPadding(dp(10), 0, dp(6), 0)
            setOnClickListener { nextSlot() }
        }
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(10), 0)
        }
        panel.addView(slotBtn)
        panel.addView(status)

        val lp = LP(
            LP.WRAP_CONTENT, LP.WRAP_CONTENT, LP.TYPE_ACCESSIBILITY_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 20
        lp.y = 120
        var ox = 0f
        var oy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ox = e.rawX - lp.x; oy = e.rawY - lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX - ox).toInt()
                    lp.y = (e.rawY - oy).toInt()
                    wm.updateViewLayout(panel, lp)
                }
            }
            true
        }
        wm.addView(panel, lp)
        refresh()
    }

    fun hidePanel() {
        stopAll()
        if (!panelOn) return
        panelOn = false
        try {
            wm.removeView(panel)
            wm.removeView(shield)
        } catch (e: Exception) {
        }
    }

    private fun refresh() {
        if (!panelOn) return
        status.text = "${steps.size}"
        slotBtn.text = Store.macros[Store.cur].name
    }

    // تبديل الماكرو من اللوحة
    private fun nextSlot() {
        stopAll()
        Store.cur = (Store.cur + 1) % Store.macros.size
        Store.save(this)
        refresh()
    }

    private fun setTouchable(on: Boolean) {
        shieldLp.flags = if (on) shieldLp.flags and LP.FLAG_NOT_TOUCHABLE.inv()
        else shieldLp.flags or LP.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(shield, shieldLp)
    }
        // ---- التسجيل: نلتقط المسار الكامل لكل إصبع ----
    private fun pt(r: Rec, x: Float, y: Float) {
        r.xs.add(x)
        r.ys.add(y)
    }

    private fun onShield(e: MotionEvent) {
        val loc = IntArray(2)
        shield.getLocationOnScreen(loc)
        val ox = loc[0].toFloat()
        val oy = loc[1].toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rec.clear()
                sess = e.eventTime
                h.removeCallbacks(flushRun)
                val r = Rec(0L)
                pt(r, e.getX(0) + ox, e.getY(0) + oy)
                rec[e.getPointerId(0)] = r
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val r = Rec(e.eventTime - sess)
                pt(r, e.getX(i) + ox, e.getY(i) + oy)
                rec[e.getPointerId(i)] = r
            }
            MotionEvent.ACTION_MOVE -> {
                for (n in 0 until e.pointerCount) {
                    val r = rec[e.getPointerId(n)] ?: continue
                    for (k in 0 until e.historySize) {
                        pt(r, e.getHistoricalX(n, k) + ox, e.getHistoricalY(n, k) + oy)
                    }
                    pt(r, e.getX(n) + ox, e.getY(n) + oy)
                    r.end = e.eventTime - sess
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val i = e.actionIndex
                val r = rec[e.getPointerId(i)]
                if (r != null) {
                    pt(r, e.getX(i) + ox, e.getY(i) + oy)
                    r.end = e.eventTime - sess
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) finish(e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> finish(e.eventTime)
        }
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

    // انتهت اللمسة (كل الأصابع): نحفظها كخطوة
    private fun finish(now: Long) {
        if (rec.isEmpty()) return
        val list = ArrayList<Stroke>()
        for (r in rec.values.sortedBy { it.off }.take(GestureDescription.getMaxStrokeCount())) {
            list.add(
                Stroke(minOf(r.off, 25000L), minOf(maxOf(r.end - r.off, 40L), 30000L), thin(r))
            )
        }
        val begin = sess
        rec.clear()
        val st = Step(list, if (lastEnd == 0L) 0L else begin - lastEnd, false)
        lastEnd = now
        steps.add(st)
        refresh()
        enqueue(st)
    }

    // نمرّر اللمسات للعبة بعد ما توقف لحظة (0.3 ثانية) حتى ما تضيع ضغطاتك السريعة
    private fun enqueue(st: Step) {
        queue.add(st)
        if (!flushing) {
            h.removeCallbacks(flushRun)
            h.postDelayed(flushRun, 300)
        }
    }

    private fun flush() {
        if (queue.isEmpty() || flushing) return
        flushing = true
        shot { a ->
            if (rec.isNotEmpty()) {
                // إصبعك نازل هسه، نأجّل
                flushing = false
                h.postDelayed(flushRun, 200)
                return@shot
            }
            val only = if (queue.size == 1) queue[0] else null
            setTouchable(false)
            if (only != null) learn(only, a)
            drain(true)
        }
    }

    private fun drain(first: Boolean) {
        if (queue.isEmpty()) {
            flushing = false
            if (recording) setTouchable(true)
            return
        }
        val st = queue.removeAt(0)
        h.postDelayed({ tap(st) { drain(false) } }, if (first) 0L else st.before.coerceIn(0L, 400L))
    }

    // يتعلم لحاله: إذا الضغطة غيّرت الشاشة بشكل واضح (مثل زر الهجوم) يعلّمها للتحقق
    private fun learn(st: Step, a: IntArray?) {
        if (a == null || st.before in 1L..1499L) return
        val n = steps.size
        val cur = Store.cur
        waitChange(a, System.currentTimeMillis() + 3500) { ok ->
            if (ok && steps.size == n && cur == Store.cur) {
                st.verify = true
                Store.save(this)
            }
        }
    }

    private fun startRec() {
        stopAll()
        steps.clear()
        lastEnd = 0L
        recording = true
        icRec.on = true
        shield.setBackgroundColor(0x22FF0000)
        setTouchable(true)
        refresh()
    }

    fun stopAll() {
        if (recording && steps.isNotEmpty()) Store.save(this)
        recording = false
        playing = false
        flushing = false
        queue.clear()
        rec.clear()
        h.removeCallbacksAndMessages(null)
        if (panelOn) {
            shield.setBackgroundColor(0)
            setTouchable(false)
            icRec.on = false
            icPlay.on = false
        }
    }
        // ---- التشغيل ----
    private fun startPlay() {
        stopAll()
        if (steps.isEmpty()) return
        playing = true
        loopNo = 0
        icPlay.on = true
        refresh()
        h.postDelayed({ if (playing) play(0) }, 1500)
    }

    // يشغّل الخطوة i ثم اللي بعدها، وبعد آخر خطوة يبدأ دورة جديدة
    private fun play(i: Int) {
        val list = steps
        if (list.isEmpty()) {
            stopAll()
            return
        }
        if (i >= list.size) {
            loopNo++
            if (Store.cfg.loops in 1..loopNo) {
                stopAll()
                return
            }
            h.postDelayed({ if (playing) play(0) }, Store.cfg.loopGap)
            return
        }
        val s = list[i]
        h.postDelayed({
            if (playing) go(s, 0) { play(i + 1) }
        }, s.before)
    }

    // ينفذ الخطوة، وإذا كانت معلّمة للتحقق ينتظر تغيّر الشاشة، وإذا ما صار يعيدها
    private fun go(s: Step, tries: Int, next: () -> Unit) {
        if (!playing) return
        if (!s.verify) {
            burst(s, s.repeat, next)
            return
        }
        shot { a ->
            burst(s, s.repeat) {
                if (a == null) next()
                else waitChange(a, System.currentTimeMillis() + Store.cfg.timeout) { ok ->
                    when {
                        !playing -> {}
                        ok -> next()
                        tries >= Store.cfg.tries -> { stopAll(); status.text = "❌" }
                        else -> go(s, tries + 1, next)
                    }
                }
            }
        }
    }

    // تكرار الخطوة n مرة بالفاصل اللي حددته
    private fun burst(s: Step, n: Int, done: () -> Unit) {
        tap(s) {
            if (n <= 1) done()
            else h.postDelayed({ if (playing) burst(s, n - 1, done) }, s.repGap)
        }
    }

    private fun waitChange(a: IntArray, end: Long, cb: (Boolean) -> Unit) {
        h.postDelayed({
            shot { b ->
                when {
                    b != null && diff(a, b) > Store.cfg.change -> cb(true)
                    System.currentTimeMillis() > end -> cb(false)
                    else -> waitChange(a, end, cb)
                }
            }
        }, 600)
    }

    // ننفذ الخطوة: كل إصبع مسار (منحنى أو خط أو نقطة) بنفس توقيته
    private fun tap(s: Step, done: () -> Unit) {
        val b = GestureDescription.Builder()
        for (k in s.strokes) {
            val p = Path()
            p.moveTo(k.pts[0], k.pts[1])
            var i = 2
            while (i + 1 < k.pts.size) {
                p.lineTo(k.pts[i], k.pts[i + 1])
                i += 2
            }
            b.addStroke(GestureDescription.StrokeDescription(p, k.off, maxOf(1L, k.dur)))
        }
        val ok = try {
            dispatchGesture(b.build(), object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { done() }
                override fun onCancelled(d: GestureDescription?) { done() }
            }, null)
        } catch (e: Exception) {
            false
        }
        if (!ok) done()
    }

    // لقطة شاشة مصغّرة (24×48) نقارن بيها قبل وبعد الضغطة
    private fun shot(cb: (IntArray?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                r.hardwareBuffer.close()
                if (sw == null) { cb(null); return }
                val small = Bitmap.createScaledBitmap(sw, 24, 48, true)
                val px = IntArray(24 * 48)
                small.getPixels(px, 0, 24, 0, 0, 24, 48)
                cb(px)
            }
            override fun onFailure(errorCode: Int) { cb(null) }
        })
    }

    private fun lum(c: Int) = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10

    private fun diff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(lum(a[i]) - lum(b[i]))
        return s.toDouble() / a.size
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
