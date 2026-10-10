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
