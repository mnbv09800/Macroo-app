package com.macro.rec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    private var latest: IntArray? = null
    private var inj = 0
    private var loopNo = 0

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
        var dx = 0f
        var dy = 0f
        var t0 = 0L
        shield.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; t0 = e.eventTime }
                MotionEvent.ACTION_UP -> {
                    val st = Step(
                        dx, dy, e.rawX, e.rawY,
                        (e.eventTime - t0).coerceIn(30L, 5000L),
                        if (lastEnd == 0L) 0L else t0 - lastEnd,
                        false
                    )
                    steps.add(st)
                    lastEnd = e.eventTime
                    refresh()
                    learn(st, latest)
                    inject(st)
                }
            }
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
    }    fun hidePanel() {
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

    // ---- التسجيل ----
    private fun setTouchable(on: Boolean) {
        shieldLp.flags = if (on) shieldLp.flags and LP.FLAG_NOT_TOUCHABLE.inv()
        else shieldLp.flags or LP.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(shield, shieldLp)
    }

    // نمرّر نفس الضغطة للعبة (والطبقة تصير غير لمسية لحظياً حتى ما تسجلها مرة ثانية)
    private fun inject(st: Step) {
        inj++
        setTouchable(false)
        tap(st) {
            inj--
            if (inj == 0 && recording) setTouchable(true)
        }
    }

    // لقطة تتحدث كل 0.8 ثانية وقت التسجيل (هذي "الشاشة قبل الضغطة")
    private fun poll() {
        if (!recording) return
        shot { if (it != null) latest = it }
        h.postDelayed({ poll() }, 800)
    }

    // يتعلم لحاله: إذا الضغطة غيّرت الشاشة بشكل واضح (مثل زر الهجوم) يعلّمها للتحقق
    // الضغطات السريعة المتتالية (مثل نشر القوات) ما تتعلّم، وتكدر تعدّلها من التطبيق
    private fun learn(st: Step, a: IntArray?) {
        if (a == null || st.before in 1L..1499L) return
        val n = steps.size
        val cur = Store.cur
        waitChange(a, System.currentTimeMillis() + 3000) { ok ->
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
        latest = null
        recording = true
        icRec.on = true
        shield.setBackgroundColor(0x22FF0000)
        setTouchable(true)
        refresh()
        poll()
    }

    fun stopAll() {
        if (recording && steps.isNotEmpty()) Store.save(this)
        recording = false
        playing = false
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

    // تكرار الضغطة n مرة بالفاصل اللي حددته
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

    private fun tap(s: Step, done: () -> Unit) {
        val p = Path().apply {
            moveTo(s.x1, s.y1)
            if (abs(s.x2 - s.x1) > 20 || abs(s.y2 - s.y1) > 20) lineTo(s.x2, s.y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, s.dur))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done() }
            override fun onCancelled(d: GestureDescription?) { done() }
        }, null)
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
