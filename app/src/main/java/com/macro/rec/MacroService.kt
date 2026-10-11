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
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

// أيقونات اللوحة العائمة (1 تشغيل، 2 إيقاف، 3 سحب، 4 كاميرا، 5 عين)
class Ico(c: Context, kind0: Int, private val col: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var kind = kind0
        set(v) {
            field = v
            invalidate()
        }
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
            1 -> {
                val t = Path()
                t.moveTo(r * 0.78f, r * 0.6f)
                t.lineTo(r * 1.46f, r)
                t.lineTo(r * 0.78f, r * 1.4f)
                t.close()
                c.drawPath(t, p)
            }
            2 -> c.drawRoundRect(r * 0.66f, r * 0.66f, r * 1.34f, r * 1.34f, 5f, 5f, p)
            4 -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3.5f
                c.drawRoundRect(r * 0.5f, r * 0.72f, r * 1.5f, r * 1.4f, 8f, 8f, p)
                c.drawCircle(r, r * 1.06f, r * 0.2f, p)
            }
            6, 7 -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 5f
                p.strokeCap = Paint.Cap.ROUND
                val a = if (kind == 6) r * 1.15f else r * 0.85f
                val m = if (kind == 6) r * 0.8f else r * 1.2f
                c.drawLine(a, r * 0.6f, m, r, p)
                c.drawLine(m, r, a, r * 1.4f, p)
                p.strokeCap = Paint.Cap.BUTT
            }
            5 -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3.5f
                c.drawOval(r * 0.45f, r * 0.72f, r * 1.55f, r * 1.28f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(r, r, r * 0.17f, p)
            }
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
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var slotBtn: TextView
    private lateinit var icPlay: Ico
    var panelOn = false
    private var panelLp: LP? = null
    private var bot = false
    private var phase = 0
    private var unknown = 0
    private var waited = 0
    private var rounds = 0
    private var skips = 0
    private var readFails = 0
    private var lastId = ""
    private var lastAt = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val hb = Handler(Looper.getMainLooper())
    private var banner: TextView? = null

    // شريط رسائل على الشاشة (بدل Toast لأن بعض الأجهزة تخفيه)
    private fun toast(t: String) {
        try {
            banner?.let {
                try { wm.removeView(it) } catch (e: Exception) {}
            }
            val tv = TextView(this).apply {
                text = t
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F00B1220"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor("#4422D3EE"))
                }
            }
            val lp = LP(
                LP.WRAP_CONTENT, LP.WRAP_CONTENT, LP.TYPE_ACCESSIBILITY_OVERLAY,
                LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.y = dp(120)
            wm.addView(tv, lp)
            banner = tv
            hb.postDelayed({
                try { wm.removeView(tv) } catch (e: Exception) {}
                if (banner === tv) banner = null
            }, 4500)
        } catch (e: Throwable) {
            Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
        }
    }

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
            v.setOnClickListener {
                try {
                    click()
                } catch (e: Throwable) {
                    toast("خطأ: ${e.javaClass.simpleName} ${e.message ?: ""}")
                }
            }
            panel.addView(v)
            return v
        }
        val handle = ico(3, "#FFFFFF") {}
        val rest = ArrayList<View>()
        var open = true
        lateinit var tg: Ico
        tg = ico(6, "#FFFFFF") {
            open = !open
            for (v in rest) v.visibility = if (open) View.VISIBLE else View.GONE
            tg.kind = if (open) 6 else 7
        }
        rest.add(ico(4, "#FBBF24") { capture() })
        rest.add(ico(5, "#A78BFA") { probe() })
        icPlay = ico(1, "#4ADE80") { if (bot) stopAll() else botStart() }
        rest.add(icPlay)
        rest.add(ico(2, "#22D3EE") { stopAll() })
        slotBtn = TextView(this).apply {
            isSingleLine = true
            textSize = 14f
            setTextColor(Color.parseColor("#22D3EE"))
            setPadding(dp(10), 0, dp(6), 0)
            setOnClickListener { nextSlot() }
        }
        status = TextView(this).apply {
            isSingleLine = true
            setHorizontallyScrolling(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(10), 0)
        }
        panel.addView(slotBtn)
        panel.addView(status)
        rest.add(slotBtn)
        rest.add(status)

        val lp = LP(
            LP.WRAP_CONTENT, LP.WRAP_CONTENT, LP.TYPE_ACCESSIBILITY_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 20
        lp.y = 120
        panelLp = lp
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
        } catch (e: Exception) {
        }
    }

    private fun say(t: String) {
        if (!panelOn) return
        if (status.text.toString() == t) return
        status.text = t
        val lp = panelLp ?: return
        // نخلي النافذة تعيد حساب حجمها (حتى ما ينلف النص بعدة أسطر)
        panel.post {
            try {
                if (panelOn) wm.updateViewLayout(panel, lp)
            } catch (e: Exception) {
            }
        }
    }

    private fun refresh() {
        if (!panelOn) return
        slotBtn.text = Store.macros[Store.cur].name
        say("${steps.size}")
    }

    // تبديل ماكرو الهجوم من اللوحة
    private fun nextSlot() {
        stopAll()
        Store.cur = (Store.cur + 1) % Store.macros.size
        Store.save(this)
        refresh()
    }

    fun stopAll() {
        bot = false
        h.removeCallbacksAndMessages(null)
        if (panelOn) {
            icPlay.on = false
            refresh()
        }
    }

    // ---- لقطة الشاشة ----
    private var lastErr = 0

    private fun grab(cb: (Bitmap?) -> Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    var sw: Bitmap? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Throwable) {
                        toast("خطأ لقطة: ${e.message}")
                    } finally {
                        try { r.hardwareBuffer.close() } catch (e: Throwable) {}
                    }
                    cb(sw)
                }
                override fun onFailure(errorCode: Int) {
                    lastErr = errorCode
                    cb(null)
                }
            })
        } catch (e: Throwable) {
            toast("خطأ لقطة: ${e.message}")
            cb(null)
        }
    }

    // 📷 نلتقط الشاشة ونفتح المحرر لتحديد الأزرار أو رسم الهجوم
    private fun capture() {
        stopAll()
        grab { b ->
            if (b == null) {
                toast("ما قدرت ألتقط الشاشة")
                return@grab
            }
            Shared.shot = b
            Shared.pending = true
            Store.refW = b.width
            Store.refH = b.height
            Store.save(this)
            toast("انلقطت الشاشة. إذا ما انفتح المحرر افتح تطبيق Macro")
            try {
                startActivity(Intent(this, EditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
    }

    // 👁 نختبر شنو الأزرار اللي يشوفها بهالشاشة
    private fun probe() {
        say("👁…")
        grab { b ->
            if (b == null) {
                toast("ما قدرت ألتقط الشاشة (كود $lastErr). جرّب مرة ثانية بعد ثانيتين")
                refresh()
                return@grab
            }
            val hit = Store.anchors.keys.filter { seen(b, it) }
            val msg = if (hit.isEmpty()) "ما شفت أي زر" else "شفت: " + hit.joinToString(" • ") { Store.roleName(it) }
            val on = Store.res.filter { it.w > 0 }
            if (on.isEmpty()) {
                toast(msg)
            } else {
                readAll(b, on) { vals ->
                    toast(msg + "\n" + on.indices.joinToString(" • ") { "${on[it].icon} ${vals[it] ?: "؟"}" })
                }
            }
            b.recycle()
        }
    }

    private val ocr by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // نقرأ الرقم من كل منطقة بالـOCR (ننسخ المنطقة أول، فاللقطة الأصلية تنحذف براحتها)
    private fun readAll(b: Bitmap, list: List<Res>, cb: (List<Long?>) -> Unit) {
        val out = arrayOfNulls<Long>(list.size)
        var left = list.size
        if (left == 0) {
            cb(out.toList())
            return
        }
        list.forEachIndexed { i, r ->
            val x0 = r.x.coerceIn(0, b.width - 2)
            val y0 = r.y.coerceIn(0, b.height - 2)
            val w0 = r.w.coerceIn(2, b.width - x0)
            val h0 = r.h.coerceIn(2, b.height - y0)
            val crop = Bitmap.createBitmap(b, x0, y0, w0, h0)
            val k = (90f / h0).coerceIn(1f, 4f)
            val big = Bitmap.createScaledBitmap(crop, (w0 * k).toInt(), (h0 * k).toInt(), true)
            ocr.process(InputImage.fromBitmap(big, 0))
                .addOnSuccessListener { t ->
                    out[i] = t.text.filter { it.isDigit() }.toLongOrNull()
                    left--
                    if (left == 0) cb(out.toList())
                }
                .addOnFailureListener {
                    left--
                    if (left == 0) cb(out.toList())
                }
        }
    }

    // آخر صورة تطابقت لكل زر (نضغط على مكانها)
    private val matched = HashMap<String, Anchor>()

    // الزر يعتبر "مرئي" إذا أي صورة من صوره تطابق، ونختار الأقرب
    private fun seen(b: Bitmap, role: String): Boolean {
        val list = Store.anchors[role] ?: return false
        var best: Anchor? = null
        var bd = Double.MAX_VALUE
        for (a in list) {
            val d = Vision.dist(Vision.sig(b, a.x, a.y, a.w, a.h), a.sig)
            if (d < bd) {
                bd = d
                best = a
            }
        }
        if (best != null && bd < Store.cfg.match) {
            matched[role] = best
            return true
        }
        return false
    }

    private fun press(role: String) {
        val a = matched[role] ?: Store.anchors[role]?.firstOrNull() ?: return
        val k = Stroke(0L, 60L, floatArrayOf(a.cx.toFloat(), a.cy.toFloat()))
        tap(Step(arrayListOf(k), 0L, false)) {}
    }

    // نضغط ونسجل آخر زر انضغط (حتى ما نكرر الضغط على نفس الزر قبل ما الشاشة تتغير)
    private fun pressRec(id: String) {
        lastId = id
        lastAt = SystemClock.uptimeMillis()
        press(id)
    }

    // ---- العقل: يفهم الشاشة ويقرر ----
    private fun botStart() {
        stopAll()
        say("▶…")
        val need = Store.btns.filter { it.kind != 3 && Store.anchors[it.id].isNullOrEmpty() }
        if (need.isNotEmpty()) {
            toast("ناقص: " + need.joinToString(" • ") { it.name })
            return
        }
        if (Store.btns.none { it.kind == 1 }) {
            toast("ما أكو زر نوعه (بداية الهجوم)")
            return
        }
        if (Store.btns.none { it.kind == 2 }) {
            toast("ما أكو زر نوعه (نهاية الجولة)")
            return
        }
        if (steps.isEmpty()) {
            toast("ارسم الهجوم أول من المحرر (📷 ثم رسم الهجوم)")
            return
        }
        val noReg = Store.res.filter { it.min > 0 && it.w <= 0 }
        if (noReg.isNotEmpty()) {
            toast("حدد منطقة الرقم لـ: " + noReg.joinToString(" • ") { it.name })
            return
        }
        bot = true
        phase = 0
        unknown = 0
        waited = 0
        rounds = 0
        skips = 0
        readFails = 0
        lastId = ""
        lastAt = 0L
        icPlay.on = true
        tick(200)
    }

    private fun tick(d: Long) {
        h.postDelayed({ if (bot) look() }, d)
    }

    private fun look() {
        grab { b ->
            if (!bot) {
                b?.recycle()
                return@grab
            }
            if (b == null) {
                tick(maxOf(Store.cfg.poll, 500L))
                return@grab
            }
            // أزرار "موافق" أول شي، وبعدها الباقي حسب ترتيبك
            var hit: Btn? = null
            for (x in Store.btns.sortedBy { if (it.kind == 3) 0 else 1 }) {
                if (phase == 1 && x.kind == 0) continue
                if (seen(b, x.id)) {
                    hit = x
                    break
                }
            }
            val d = hit
            val poll = Store.cfg.poll
            if (d == null) {
                if (phase == 1) {
                    val bt = maxOf(poll, 800L)
                    waited++
                    if (waited > (375000L / bt).toInt()) {
                        phase = 0
                        waited = 0
                    }
                    say("⚔")
                    tick(bt)
                } else {
                    unknown++
                    say("؟$unknown")
                    if (unknown % maxOf(40, (60000L / poll).toInt()) == 0) performGlobalAction(GLOBAL_ACTION_BACK)
                    tick(poll)
                }
            } else if (d.id == lastId && SystemClock.uptimeMillis() - lastAt < Store.cfg.repress) {
                // نفس الزر انضغط قبل شوي: ننتظر الشاشة تتغير بدل ما نكرر الضغط
                tick(poll)
            } else {
                when (d.kind) {
                    3 -> {
                        pressRec(d.id)
                        tick(poll)
                    }
                    2 -> {
                        phase = 0
                        unknown = 0
                        rounds++
                        say("🏠$rounds")
                        pressRec(d.id)
                        val lim = Store.cfg.loops
                        if (lim > 0 && rounds >= lim) {
                            toast("خلصت $rounds دورة")
                            h.postDelayed({ stopAll() }, 1500)
                        } else {
                            tick(maxOf(poll, Store.cfg.loopGap))
                        }
                    }
                    1 -> {
                        unknown = 0
                        val on = Store.res.filter { it.min > 0 && it.w > 0 }
                        if (on.isEmpty()) {
                            say("🔍")
                            lastId = ""
                            scout()
                        } else {
                            readAll(b, on) { vals ->
                                if (!bot) return@readAll
                                val txt = on.indices.joinToString(" ") { "${on[it].icon}${vals[it] ?: "؟"}" }
                                if (vals.any { it == null }) {
                                    readFails++
                                    if (readFails >= 5) {
                                        stopAll()
                                        toast("ما كدرت أقرأ الأرقام. تأكد من المستطيل (جرّب 👁)")
                                        return@readAll
                                    }
                                } else {
                                    readFails = 0
                                }
                                val pass = on.indices.all { (vals[it] ?: -1L) >= on[it].min }
                                if (pass) {
                                    say("✅ $txt")
                                    lastId = ""
                                    scout()
                                } else {
                                    skips++
                                    say("⏭$skips $txt")
                                    pressRec(d.id)
                                    tick(poll)
                                }
                            }
                        }
                    }
                    else -> {
                        unknown = 0
                        say("▶")
                        pressRec(d.id)
                        tick(poll)
                    }
                }
            }
            b.recycle()
        }
    }

    // وصلنا لشاشة البحث: ننفذ خطة الهجوم
    private fun scout() {
        phase = 1
        waited = 0
        runPlan(0) { if (bot) tick(Store.cfg.poll) }
    }

    private fun runPlan(i: Int, done: () -> Unit) {
        val list = steps
        if (i >= list.size) {
            done()
            return
        }
        val s = list[i]
        h.postDelayed({
            if (bot) burst(s, s.repeat) { runPlan(i + 1, done) }
        }, s.before)
    }

    // تكرار الخطوة n مرة بالفاصل اللي حددته
    private fun burst(s: Step, n: Int, done: () -> Unit) {
        tap(s) {
            if (n <= 1) done()
            else h.postDelayed({ if (bot) burst(s, n - 1, done) }, s.repGap)
        }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
