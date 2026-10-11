package com.macro.rec

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class MainActivity : Activity() {
    private val BG = Color.parseColor("#0B1220")
    private val CARD = Color.parseColor("#141C2E")
    private val LINE = Color.parseColor("#22304A")
    private val CY = Color.parseColor("#22D3EE")
    private val GR = Color.parseColor("#4ADE80")
    private val RD = Color.parseColor("#FB7185")
    private val TX = Color.parseColor("#E5E7EB")
    private val DIM = Color.parseColor("#94A3B8")
    private lateinit var root: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.ensure(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(40), dp(18), dp(30))
        sv.addView(root)
        setContentView(sv)
    }

            override fun onResume() {
        super.onResume()
        MacroService.inst?.stopAll()
        if (Shared.pending) {
            Shared.pending = false
            startActivity(Intent(this, EditorActivity::class.java))
        }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(color: Int, r: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float = 15f, col: Int = TX, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(col)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = box(CARD, 20, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(14)) }
    }

    private fun btn(t: String, col: Int, click: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(col)
        gravity = Gravity.CENTER
        background = box(Color.TRANSPARENT, 14, col)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(6), dp(8), 0) }
        setOnClickListener { click() }
    }

    private fun chip(t: String, sel: Boolean, click: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(if (sel) BG else TX)
        background = box(if (sel) CY else Color.TRANSPARENT, 16, if (sel) CY else LINE)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(8), 0) }
        setOnClickListener { click() }
    }

    private fun ring(col: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(4), col)
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun render() {
        root.removeAllViews()
        val svc = MacroService.inst
        val on = svc?.panelOn == true
        val m = Store.macros[Store.cur]

        root.addView(tv("Macro", 30f, CY, true))
        root.addView(
            tv(if (svc == null) "● الخدمة غير مفعلة" else "● الخدمة شغالة", 13f, if (svc == null) RD else GR)
        )
        root.addView(space(18))

        // بطاقة البدء
        val c1 = card()
        c1.gravity = Gravity.CENTER_HORIZONTAL
        val go = tv(if (on) "إيقاف" else "ابدأ", 22f, if (on) RD else GR, true)
        go.gravity = Gravity.CENTER
        go.background = ring(if (on) RD else GR)
        go.layoutParams = LinearLayout.LayoutParams(dp(130), dp(130))
        go.setOnClickListener { toggle() }
        c1.addView(go)
        c1.addView(
            tv(if (on) "اللوحة العائمة شغالة، افتح اللعبة" else "اضغط ابدأ لإظهار اللوحة العائمة", 13f, DIM).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }
        )
        root.addView(c1)

        // الماكروهات
        val c2 = card()
        c2.addView(tv("الماكروهات", 17f, TX, true))
        val row = LinearLayout(this)
        row.setPadding(0, dp(10), 0, dp(4))
        Store.macros.forEachIndexed { i, mm ->
            row.addView(chip(mm.name, i == Store.cur) {
                Store.cur = i
                Store.save(this)
                render()
            })
        }
        val hs = HorizontalScrollView(this)
        hs.addView(row)
        c2.addView(hs)
        val acts = LinearLayout(this)
        acts.addView(btn("+ جديد", GR) {
            Store.macros.add(Macro("M${Store.macros.size + 1}"))
            Store.cur = Store.macros.size - 1
            Store.save(this)
            render()
        })
        acts.addView(btn("تسمية", CY) {
            ask("اسم الماكرو", m.name) { n ->
                m.name = n
                Store.save(this)
                render()
            }
        })
        acts.addView(btn("حذف", RD) {
            if (Store.macros.size > 1) {
                Store.macros.removeAt(Store.cur)
                Store.cur = 0
                Store.save(this)
                render()
            }
        })
        c2.addView(acts)
        root.addView(c2)

        // الخطوات
        val c3 = card()
        c3.addView(tv("خطوات ${m.name} (${m.steps.size})", 17f, TX, true))
        if (m.steps.isEmpty()) {
            c3.addView(tv("ما أكو خطوات. من اللوحة العائمة اضغط 📷 ثم «رسم الهجوم».", 13f, DIM).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        m.steps.forEachIndexed { i, s ->
            val swipe = abs(s.x2 - s.x1) > 20 || abs(s.y2 - s.y1) > 20
            val t = "${i + 1}  " + (if (swipe) "سحب" else "ضغطة") + "  ${s.dur}ms  ×${s.repeat}" +
                (if (s.verify) "  ✔ تحقق" else "")
            c3.addView(tv(t, 14f).apply {
                background = box(Color.parseColor("#0F172A"), 12, LINE)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) }
                setOnClickListener { editStep(m, s) }
            })
        }
        root.addView(c3)

        // الأزرار
        val c5 = card()
        c5.addView(tv("الأزرار (البوت يفحصها من فوق لتحت)", 17f, TX, true))
        Store.btns.forEachIndexed { i, d ->
            val r5 = LinearLayout(this)
            r5.gravity = Gravity.CENTER_VERTICAL
            r5.background = box(Color.parseColor("#0F172A"), 12, LINE)
            r5.setPadding(dp(12), dp(8), dp(4), dp(8))
            r5.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) }
            val got = Store.anchors.containsKey(d.id)
            val label = tv(
                "${i + 1}  " + (if (got) "✔ " else "✖ ") + d.name + "\n" + Store.kindName(d.kind),
                14f, if (got || d.kind == 3) TX else RD
            )
            label.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            label.setOnClickListener { editBtn(d) }
            r5.addView(label)
            r5.addView(arrow("▲") { moveBtn(i, -1) })
            r5.addView(arrow("▼") { moveBtn(i, 1) })
            c5.addView(r5)
        }
        c5.addView(btn("+ زر جديد", GR) { editBtn(null) })
        root.addView(c5)

        // الإعدادات العامة
        val cf = Store.cfg
        val lp = if (cf.loops == 0) "∞" else "${cf.loops}"
        val c4 = card()
        c4.addView(tv("الإعدادات العامة", 17f, TX, true))
        c4.addView(
            tv("انتظار التأكد ${cf.timeout / 1000}ث • محاولات ${cf.tries} • دورات $lp", 13f, DIM).apply {
                setPadding(0, dp(8), 0, 0)
            }
        )
        c4.addView(btn("تعديل", CY) { editCfg() })
        root.addView(c4)
    }

    private fun arrow(t: String, click: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 18f
        setTextColor(CY)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { click() }
    }

    private fun moveBtn(i: Int, d: Int) {
        val j = i + d
        if (j !in Store.btns.indices) return
        val t = Store.btns[i]
        Store.btns[i] = Store.btns[j]
        Store.btns[j] = t
        Store.save(this)
        render()
    }

    private fun editBtn(d: Btn?) {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(20), dp(10), dp(20), 0)
        val e = EditText(this)
        e.setText(d?.name ?: "")
        e.hint = "اسم الزر"
        lay.addView(e)
        lay.addView(tv("شنو يسوي البوت لما يشوفه؟", 13f, DIM).apply { setPadding(0, dp(12), 0, 0) })
        val g = RadioGroup(this)
        Store.kinds.forEachIndexed { i, k ->
            val rb = RadioButton(this)
            rb.id = 100 + i
            rb.text = k
            g.addView(rb)
        }
        g.check(100 + (d?.kind ?: 0))
        lay.addView(g)
        val scroll = ScrollView(this)
        scroll.addView(lay)
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (d == null) "زر جديد" else "تعديل الزر")
            .setView(scroll)
            .setPositiveButton("حفظ") { _, _ ->
                val n = e.text.toString().trim()
                val k = (g.checkedRadioButtonId - 100).coerceIn(0, Store.kinds.size - 1)
                if (n.isNotEmpty()) {
                    if (d == null) {
                        Store.btns.add(Btn("b" + System.currentTimeMillis(), n, k))
                    } else {
                        d.name = n
                        d.kind = k
                    }
                    Store.save(this)
                    render()
                }
            }
            .setNegativeButton("إلغاء", null)
        if (d != null) {
            dlg.setNeutralButton("حذف") { _, _ ->
                Store.btns.remove(d)
                Store.anchors.remove(d.id)
                Store.save(this)
                render()
            }
        }
        dlg.show()
    }

    private fun toggle() {
        val s = MacroService.inst
        if (s == null) {
            Toast.makeText(this, "فعّل Macro من إمكانية الوصول", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else if (s.panelOn) {
            s.hidePanel()
            render()
        } else {
            s.showPanel()
            moveTaskToBack(true)
        }
    }

    private fun num(p: LinearLayout, label: String, v: Long): EditText {
        p.addView(tv(label, 13f, DIM).apply { setPadding(0, dp(10), 0, 0) })
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(v.toString())
        p.addView(e)
        return e
    }

    private fun ask(title: String, init: String, ok: (String) -> Unit) {
        val e = EditText(this)
        e.setText(init)
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("حفظ") { _, _ ->
                val n = e.text.toString().trim()
                if (n.isNotEmpty()) ok(n)
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun editStep(m: Macro, s: Step) {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(20), dp(10), dp(20), 0)
        val dur = num(lay, "مدة الضغطة (ms)", s.dur)
        val bef = num(lay, "التأخير قبل الخطوة (ms)", s.before)
        val rep = num(lay, "عدد التكرار", s.repeat.toLong())
        val gap = num(lay, "الفاصل بين التكرار (ms)", s.repGap)
        val sw = Switch(this)
        sw.text = "تحقق من نجاح الضغطة"
        sw.isChecked = s.verify
        sw.setPadding(0, dp(14), 0, 0)
        lay.addView(sw)
        val scroll = ScrollView(this)
        scroll.addView(lay)
        AlertDialog.Builder(this).setTitle("إعدادات الخطوة").setView(scroll)
            .setPositiveButton("حفظ") { _, _ ->
                s.dur = (dur.text.toString().toLongOrNull() ?: s.dur).coerceIn(30L, 10000L)
                s.before = (bef.text.toString().toLongOrNull() ?: s.before).coerceIn(0L, 600000L)
                s.repeat = (rep.text.toString().toIntOrNull() ?: s.repeat).coerceIn(1, 100)
                s.repGap = (gap.text.toString().toLongOrNull() ?: s.repGap).coerceIn(0L, 60000L)
                s.verify = sw.isChecked
                Store.save(this)
                render()
            }
            .setNeutralButton("حذف") { _, _ ->
                m.steps.remove(s)
                Store.save(this)
                render()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun editCfg() {
        val cf = Store.cfg
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(20), dp(10), dp(20), 0)
        val t = num(lay, "انتظار تغيّر الشاشة بعد الضغطة (ثانية)", cf.timeout / 1000)
        val k = num(lay, "أقصى عدد محاولات إعادة الضغط", cf.tries.toLong())
        val c = num(lay, "حساسية تغيّر الشاشة (قلّلها = أحسس)", cf.change.toLong())
        val g = num(lay, "الفاصل بين الدورات (ثانية)", cf.loopGap / 1000)
        val l = num(lay, "عدد الدورات (0 = بلا نهاية)", cf.loops.toLong())
        val scroll = ScrollView(this)
        scroll.addView(lay)
        AlertDialog.Builder(this).setTitle("الإعدادات العامة").setView(scroll)
            .setPositiveButton("حفظ") { _, _ ->
                cf.timeout = (t.text.toString().toLongOrNull() ?: 4L).coerceIn(1L, 60L) * 1000
                cf.tries = (k.text.toString().toIntOrNull() ?: 20).coerceIn(1, 200)
                cf.change = (c.text.toString().toIntOrNull() ?: 8).coerceIn(1, 100)
                cf.loopGap = (g.text.toString().toLongOrNull() ?: 2L).coerceIn(0L, 600L) * 1000
                cf.loops = (l.text.toString().toIntOrNull() ?: 0).coerceIn(0, 100000)
                Store.save(this)
                render()
            }
            .setNegativeButton("إلغاء", null).show()
    }
}
