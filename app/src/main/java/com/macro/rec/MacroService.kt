package com.macro.rec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
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

class Step(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val dur: Long, val before: Long, var verify: Boolean
)

class MacroService : AccessibilityService() {
    private val TIMEOUT = 4000L   // كم ننتظر تغيّر الشاشة قبل إعادة الضغط
    private val MAX_TRIES = 20    // عدد محاولات الإعادة قبل ما يوقف
    private val CHANGE = 8        // حساسية تغيّر الشاشة (قلّلها = أحسس)

    private val h = Handler(Looper.getMainLooper())
    private val steps = ArrayList<Step>()
    private lateinit var wm: WindowManager
    private lateinit var shield: View
    private lateinit var shieldLp: LP
    private lateinit var status: TextView
    private var recording = false
    private var playing = false
    private var lastEnd = 0L
    private var latest: IntArray? = null
    private var inj = 0

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

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

        // اللوحة العائمة
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.setBackgroundColor(0xAA000000.toInt())
        fun btn(t: String, click: () -> Unit): TextView = TextView(this).apply {
            text = t
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(22, 12, 22, 12)
            setOnClickListener { click() }
            panel.addView(this)
        }
        val handle = btn("✥") {}
        btn("⏺") { startRec() }
        btn("▶") { startPlay() }
        btn("⏹") { stopAll() }
        status = btn("0") {}

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

        load()
        refresh()
    }

    private fun refresh() {
        status.text = "${steps.size}"
    }

    // ---- الحفظ: الماكرو ينحفظ ويرجع بعد إغلاق التطبيق أو إعادة تشغيل الهاتف ----
    private fun prefs() = getSharedPreferences("m", MODE_PRIVATE)

    private fun save() {
        val s = steps.joinToString(";") {
            "${it.x1},${it.y1},${it.x2},${it.y2},${it.dur},${it.before},${it.verify}"
        }
        prefs().edit().putString("s", s).apply()
    }

    private fun load() {
        steps.clear()
        val s = prefs().getString("s", "") ?: ""
        for (part in s.split(";")) {
            if (part.isBlank()) continue
            val p = part.split(",")
            steps.add(
                Step(
                    p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(),
                    p[4].toLong(), p[5].toLong(), p[6].toBoolean()
                )
            )
        }
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
    // الضغطات السريعة المتتالية (مثل نشر القوات) ما تتعلّم، حتى ما يعيدها بدون داعي
    private fun learn(st: Step, a: IntArray?) {
        if (a == null || st.before in 1L..1499L) return
        val n = steps.size
        waitChange(a, System.currentTimeMillis() + 3000) { ok ->
            if (ok && steps.size == n) {
                st.verify = true
                save()
            }
        }
    }

    private fun startRec() {
        stopAll()
        steps.clear()
        lastEnd = 0L
        latest = null
        recording = true
        shield.setBackgroundColor(0x22FF0000)
        setTouchable(true)
        refresh()
        poll()
    }

    private fun stopAll() {
        if (recording && steps.isNotEmpty()) save()
        recording = false
        playing = false
        h.removeCallbacksAndMessages(null)
        shield.setBackgroundColor(0)
        setTouchable(false)
    }

    // ---- التشغيل ----
    private fun startPlay() {
        stopAll()
        if (steps.isEmpty()) return
        playing = true
        refresh()
        play(0)
    }

    // يشغّل الخطوة i ثم اللي بعدها، وبعد آخر خطوة يرجع للأولى (حلقة بلا نهاية)
    private fun play(i: Int) {
        val s = steps[i]
        val gap = if (i == 0) 2000L else 0L
        h.postDelayed({
            if (playing) go(s, 0) { play((i + 1) % steps.size) }
        }, s.before + gap)
    }

    // ينفذ الضغطة، وإذا كانت خطوة تغيّر الشاشة ينتظر التغيّر، وإذا ما صار يعيدها
    private fun go(s: Step, tries: Int, next: () -> Unit) {
        if (!playing) return
        if (!s.verify) {
            tap(s, next)
            return
        }
        shot { a ->
            tap(s) {
                if (a == null) next()
                else waitChange(a, System.currentTimeMillis() + TIMEOUT) { ok ->
                    when {
                        !playing -> {}
                        ok -> next()
                        tries >= MAX_TRIES -> { stopAll(); status.text = "❌" }
                        else -> go(s, tries + 1, next)
                    }
                }
            }
        }
    }

    private fun waitChange(a: IntArray, end: Long, cb: (Boolean) -> Unit) {
        h.postDelayed({
            shot { b ->
                when {
                    b != null && diff(a, b) > CHANGE -> cb(true)
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
