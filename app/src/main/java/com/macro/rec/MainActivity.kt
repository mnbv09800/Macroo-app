package com.macro.rec

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
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
        acts.addView(btn("نسخ", CY) {
            val e = EditText(this)
            e.setText("1")
            e.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            AlertDialog.Builder(this).setTitle("كم نسخة من «${m.name}»؟").setView(e)
                .setPositiveButton("انسخ") { _, _ ->
                    val n = (e.text.toString().trim().toIntOrNull() ?: 1).coerceIn(1, 20)
                    Store.duplicate(m, n)
                    Store.cur = Store.macros.size - n
                    Store.save(this)
                    render()
                }
                .setNegativeButton("إلغاء", null).show()
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
            val cnt = Store.anchors[d.id]?.size ?: 0
            val got = cnt > 0
            val label = tv(
                "${i + 1}  " + (if (got) "✔ " else "✖ ") + d.name + (if (cnt > 1) " ($cnt صور)" else "") + "\n" + Store.kindName(d.kind),
                14f, if (got || d.kind >= 3) TX else RD
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

        // الموارد
        val c6 = card()
        c6.addView(tv("الموارد (حد أدنى للهجوم)", 17f, TX, true))
        c6.addView(tv("الصفر = ما يفحص هذا المورد. حدد مكان الرقم من 📷 ثم اختر المورد من فوق.", 12f, DIM).apply {
            setPadding(0, dp(6), 0, 0)
        })
        for (r in Store.res) {
            val got = r.w > 0
            val t = "${r.icon} ${r.name}   " + (if (got) "✔" else "✖ المنطقة ما انحددت") + "   " +
                (if (r.min > 0) "الحد: ${r.min}" else "معطّل")
            c6.addView(tv(t, 14f, if (got) TX else RD).apply {
                background = box(Color.parseColor("#0F172A"), 12, LINE)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) }
                setOnClickListener { editRes(r) }
            })
        }
        root.addView(c6)

        // مخزنك (إيقاف عند الامتلاء)
        val c8 = card()
        c8.addView(tv("مخزنك (إيقاف عند الامتلاء)", 17f, TX, true))
        c8.addView(tv("من 📷 اختر «مخزنك: ...» وحدد مستطيل حول الشريط كله (من بدايته لنهايته وبكامل ارتفاعه، بدون أيقونة العملة). البوت يقيس نسبة امتلاء الشريط ويوقف لما توصل النسبة اللي تحددها. اكتب 95 أو 100 للامتلاء.", 12f, DIM).apply {
            setPadding(0, dp(6), 0, 0)
        })
        for (r in Store.own) {
            val got = r.w > 0
            val t = "${r.icon} ${r.name}   " + (if (got) "✔" else "✖ المنطقة ما انحددت") + "   " +
                (if (r.min > 0) "أوقف عند: ${r.min}%" else "معطّل")
            c8.addView(tv(t, 14f, if (got) TX else RD).apply {
                background = box(Color.parseColor("#0F172A"), 12, LINE)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) }
                setOnClickListener { editRes(r) }
            })
        }
        root.addView(c8)

        // تطوير الجدران لما تمتلئ المخازن
        val cw = card()
        val cfw = Store.cfg
        cw.addView(tv("تطوير الجدران عند الامتلاء", 17f, TX, true))
        cw.addView(tv("لما كل مخازنك المحددة توصل حدها، البوت يفتح Build ويطوّر الجدران بالذهب أو الإكسير (الأكثر أولاً) لين تخلص الموارد، وبعدها يرجع يهاجم. يحتاج: صورة زر «Build» من 📷 وتفعيل «مخزنك».", 12f, DIM).apply {
            setPadding(0, dp(6), 0, 0)
        })
        val wmx = if (cfw.wallMax == 0) "∞" else "${cfw.wallMax}"
        cw.addView(
            tv(
                (if (cfw.wallOn) "✔ مفعّل" else "✖ معطّل") + " • يبقي ذهب ${cfw.wallKeepGold} • يبقي إكسير ${cfw.wallKeepElixir} • أقصى عدد $wmx",
                13f, if (cfw.wallOn) GR else DIM
            ).apply { setPadding(0, dp(8), 0, 0) }
        )
        cw.addView(btn("تعديل", CY) { editWall() })
        root.addView(cw)

        // الإعدادات العامة
        val cf = Store.cfg
        val lp = if (cf.loops == 0) "∞" else "${cf.loops}"
        val c4 = card()
        c4.addView(tv("الإعدادات العامة", 17f, TX, true))
        c4.addView(
            tv("سرعة الفحص ${cf.poll}ms • حساسية الأزرار ${cf.match} • منع التكرار ${cf.repress}ms • دورات $lp", 13f, DIM).apply {
                setPadding(0, dp(8), 0, 0)
            }
        )
        c4.addView(btn("تعديل", CY) { editCfg() })
        root.addView(c4)

        // تصدير / استيراد
        val c7 = card()
        c7.addView(tv("مشاركة الإعدادات", 17f, TX, true))
        c7.addView(tv("صدّر كل شي (الماكروهات والأزرار والموارد) بملف، وصديقك يستورده بدون ما يرسم من جديد.", 12f, DIM).apply {
            setPadding(0, dp(6), 0, 0)
        })
        val r7 = LinearLayout(this)
        r7.addView(btn("📤 تصدير", GR) { doExport() })
        r7.addView(btn("📥 استيراد", CY) { doImport() })
        c7.addView(r7)
        root.addView(c7)
    }

    private fun doExport() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "macro-backup.json")
        try {
            startActivityForResult(i, 11)
        } catch (e: Exception) {
            Toast.makeText(this, "ما كدرت أفتح اختيار الملفات", Toast.LENGTH_LONG).show()
        }
    }

    private fun doImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            startActivityForResult(i, 12)
        } catch (e: Exception) {
            Toast.makeText(this, "ما كدرت أفتح اختيار الملفات", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri: Uri = data?.data ?: return
        if (res != RESULT_OK) return
        if (req == 11) {
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(Store.build().toString().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "انصدّر الملف ✔ ابعثه لصديقك", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "فشل التصدير: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else if (req == 12) {
            val raw = try {
                contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (e: Exception) {
                null
            }
            if (raw == null) {
                Toast.makeText(this, "ما كدرت أقرأ الملف", Toast.LENGTH_LONG).show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("استيراد")
                .setMessage("راح يستبدل كل ماكروهاتك وأزرارك وإعداداتك الحالية بالملف. تكمل؟")
                .setPositiveButton("استيراد") { _, _ ->
                    val b = windowManager.maximumWindowMetrics.bounds
                    val err = Store.importJson(this, raw, b.width(), b.height())
                    Toast.makeText(this, err ?: "تم الاستيراد ✔", Toast.LENGTH_LONG).show()
                    render()
                }
                .setNegativeButton("إلغاء", null).show()
        }
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

    private fun editRes(r: Res) {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(20), dp(10), dp(20), 0)
        val lbl = if (r.id.startsWith("o_")) "أوقف البوت لما ${r.name} يمتلئ بنسبة % (0 = معطّل، مثلاً 100 أو 95)"
        else "الحد الأدنى لـ ${r.name} (0 = معطّل)"
        val m = num(lay, lbl, r.min)
        AlertDialog.Builder(this).setTitle(r.name).setView(lay)
            .setPositiveButton("حفظ") { _, _ ->
                r.min = (m.text.toString().toLongOrNull() ?: 0L).coerceIn(0L, if (r.id.startsWith("o_")) 100L else 100000000L)
                Store.save(this)
                render()
            }
            .setNegativeButton("إلغاء", null).show()
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

    private fun editWall() {
        val cf = Store.cfg
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(20), dp(10), dp(20), 0)
        val sw = Switch(this)
        sw.text = "تفعيل تطوير الجدران"
        sw.isChecked = cf.wallOn
        lay.addView(sw)
        val kg = num(lay, "اترك هذا المقدار من الذهب ولا تصرفه (0 = اصرف كل شي)", cf.wallKeepGold)
        val ke = num(lay, "اترك هذا المقدار من الإكسير (مفيد لتدريب الجيش) (0 = اصرف كل شي)", cf.wallKeepElixir)
        val mx = num(lay, "أقصى عدد جدران بكل مرة (0 = بلا حد)", cf.wallMax.toLong())
        val scroll = ScrollView(this)
        scroll.addView(lay)
        AlertDialog.Builder(this).setTitle("تطوير الجدران").setView(scroll)
            .setPositiveButton("حفظ") { _, _ ->
                cf.wallOn = sw.isChecked
                cf.wallKeepGold = (kg.text.toString().toLongOrNull() ?: 0L).coerceIn(0L, 100000000L)
                cf.wallKeepElixir = (ke.text.toString().toLongOrNull() ?: 0L).coerceIn(0L, 100000000L)
                cf.wallMax = (mx.text.toString().toIntOrNull() ?: 0).coerceIn(0, 100000)
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
        val pl = num(lay, "سرعة الفحص (ms) كل ما قلّت صار أسرع (الأدنى 350)", cf.poll)
        val rp = num(lay, "منع تكرار الضغط على نفس الزر (ms)", cf.repress)
        val mt = num(lay, "حساسية التعرف على الأزرار (أعلى = أسهل يتعرف، الافتراضي 10)", cf.match.toLong())
        val t = num(lay, "انتظار تغيّر الشاشة بعد الضغطة (ثانية)", cf.timeout / 1000)
        val k = num(lay, "أقصى عدد محاولات إعادة الضغط", cf.tries.toLong())
        val c = num(lay, "حساسية تغيّر الشاشة (قلّلها = أحسس)", cf.change.toLong())
        val g = num(lay, "الفاصل بين الدورات (ثانية)", cf.loopGap / 1000)
        val l = num(lay, "عدد الدورات (0 = بلا نهاية)", cf.loops.toLong())
        val scroll = ScrollView(this)
        scroll.addView(lay)
        AlertDialog.Builder(this).setTitle("الإعدادات العامة").setView(scroll)
            .setPositiveButton("حفظ") { _, _ ->
                cf.poll = (pl.text.toString().toLongOrNull() ?: 400L).coerceIn(350L, 5000L)
                cf.repress = (rp.text.toString().toLongOrNull() ?: 1500L).coerceIn(300L, 10000L)
                cf.match = (mt.text.toString().toIntOrNull() ?: 10).coerceIn(3, 60)
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
