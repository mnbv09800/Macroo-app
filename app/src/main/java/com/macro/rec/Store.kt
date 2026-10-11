package com.macro.rec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

// مسار إصبع واحد: off = متى نزل (ms)، dur = كم بقي، pts = نقاط المسار x,y,x,y,...
class Stroke(var off: Long, var dur: Long, var pts: FloatArray)

// خطوة = لمسة كاملة (إصبع أو أكثر، ضغطة أو سحب أو منحنى)
class Step(
    val strokes: ArrayList<Stroke>, var before: Long, var verify: Boolean,
    var repeat: Int = 1, var repGap: Long = 150L
) {
    val x1: Float get() = strokes[0].pts[0]
    val y1: Float get() = strokes[0].pts[1]
    val x2: Float get() = strokes[0].pts[strokes[0].pts.size - 2]
    val y2: Float get() = strokes[0].pts[strokes[0].pts.size - 1]
    var dur: Long
        get() = strokes.maxOf { it.off + it.dur }
        set(v) {
            val k = v.toDouble() / dur
            for (s in strokes) {
                s.off = (s.off * k).toLong()
                s.dur = maxOf(1L, (s.dur * k).toLong())
            }
        }
}

class Macro(var name: String, val steps: ArrayList<Step> = ArrayList())

class Cfg(
    var timeout: Long = 4000L, var tries: Int = 20, var change: Int = 8,
    var loopGap: Long = 2000L, var loops: Int = 0, var match: Int = 10,
    var poll: Long = 400L, var repress: Long = 1500L
)

// زر من اللعبة نتعرف عليه: مكانه وبصمة صورته
class Anchor(
    val role: String, val x: Int, val y: Int, val w: Int, val h: Int, val sig: IntArray
) {
    val cx: Int get() = x + w / 2
    val cy: Int get() = y + h / 2
}

// زر معرّف من المستخدم: id ثابت، اسم، ونوع (0 عادي، 1 بداية الهجوم، 2 نهاية الجولة، 3 موافق)
class Btn(val id: String, var name: String, var kind: Int)

// منطقة رقم مورد (ذهب/إكسير...) على الشاشة + الحد الأدنى (0 = معطّل)
class Res(
    val id: String, val name: String, val icon: String,
    var x: Int = 0, var y: Int = 0, var w: Int = 0, var h: Int = 0, var min: Long = 0L
)

// لقطة الشاشة الأخيرة (تنتقل بين الخدمة والمحرر)
object Shared {
    var shot: Bitmap? = null
    var pending = false
}

// بصمة منطقة من الصورة (24×16 بالرمادي) ومقارنتها
object Vision {
    const val GW = 24
    const val GH = 16

    fun sig(src: Bitmap, x: Int, y: Int, w: Int, h: Int): IntArray {
        val x0 = x.coerceIn(0, src.width - 2)
        val y0 = y.coerceIn(0, src.height - 2)
        val w0 = w.coerceIn(2, src.width - x0)
        val h0 = h.coerceIn(2, src.height - y0)
        val crop = Bitmap.createBitmap(src, x0, y0, w0, h0)
        val small = Bitmap.createScaledBitmap(crop, GW, GH, true)
        val px = IntArray(GW * GH)
        small.getPixels(px, 0, GW, 0, 0, GW, GH)
        return IntArray(px.size) { i ->
            val c = px[i]
            (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
        }
    }

    fun dist(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size
    }
}

object Store {
    val macros = ArrayList<Macro>()
    var cur = 0
    val cfg = Cfg()
    // كل زر ممكن يكون له أكثر من صورة (حتى يتعرف عليه بكل الظروف)
    val anchors = HashMap<String, ArrayList<Anchor>>()
    const val MAX_IMGS = 8

    fun addAnchor(a: Anchor) {
        anchors.getOrPut(a.role) { ArrayList() }.add(a)
    }
    // الأزرار بالترتيب (البوت يفحصها من فوق لتحت)
    val btns = ArrayList<Btn>()
    val kinds = listOf(
        "ضغطة عادية",
        "بداية الهجوم (ينفذ الخطة المرسومة)",
        "نهاية الجولة (يرجع للبداية)",
        "موافق / إغلاق (يضغطه بأي وقت)"
    )
    val res = arrayListOf(
        Res("r_gold", "الذهب", "💰"),
        Res("r_elixir", "الإكسير", "💧"),
        Res("r_dark", "الإكسير الأسود", "🖤")
    )
    // حجم الشاشة اللي انحفظت عليها الإعدادات (نستخدمه لتكبير/تصغير الاستيراد)
    var refW = 0
    var refH = 0
    private var ready = false

    fun kindName(k: Int): String = kinds.getOrElse(k) { kinds[0] }

    fun roleName(k: String): String = btns.firstOrNull { it.id == k }?.name ?: k

    fun defaultBtns() {
        btns.clear()
        btns.add(Btn("attack", "زر الهجوم (القرية)", 0))
        btns.add(Btn("go", "زر Attack الأخضر (الجيش)", 0))
        btns.add(Btn("find", "زر البحث عن خصم", 0))
        btns.add(Btn("next", "زر Next", 1))
        btns.add(Btn("home", "زر Return Home", 2))
        btns.add(Btn("ok", "زر موافق (اختياري)", 3))
    }

    fun ensure(c: Context) {
        if (!ready) { load(c); ready = true }
    }

    // كل الإعدادات كـ JSON (للحفظ والتصدير)
    fun build(): JSONObject {
        val ms = JSONArray()
        for (m in macros) {
            val ss = JSONArray()
            for (s in m.steps) {
                val sk = JSONArray()
                for (k in s.strokes) sk.put(
                    JSONObject().put("o", k.off).put("d", k.dur)
                        .put("p", k.pts.joinToString(",") { it.toInt().toString() })
                )
                ss.put(
                    JSONObject().put("k", sk).put("bef", s.before).put("v", s.verify)
                        .put("r", s.repeat).put("g", s.repGap)
                )
            }
            ms.put(JSONObject().put("n", m.name).put("s", ss))
        }
        val an = JSONArray()
        for (a in anchors.values.flatten()) an.put(
            JSONObject().put("r", a.role).put("x", a.x).put("y", a.y).put("w", a.w).put("h", a.h)
                .put("g", a.sig.joinToString(","))
        )
        val bt = JSONArray()
        for (d in btns) bt.put(JSONObject().put("i", d.id).put("n", d.name).put("k", d.kind))
        val rjs = JSONArray()
        for (r in res) rjs.put(
            JSONObject().put("i", r.id).put("x", r.x).put("y", r.y).put("w", r.w).put("h", r.h).put("m", r.min)
        )
        return JSONObject().put("cur", cur).put("m", ms).put("an", an).put("bt", bt).put("rs", rjs)
            .put("t", cfg.timeout).put("k", cfg.tries).put("c", cfg.change)
            .put("lg", cfg.loopGap).put("lp", cfg.loops).put("mt", cfg.match)
            .put("pl", cfg.poll).put("rp", cfg.repress)
            .put("rw", refW).put("rh", refH)
    }

    fun save(c: Context) {
        c.getSharedPreferences("m4", Context.MODE_PRIVATE).edit()
            .putString("db", build().toString()).apply()
    }

    // يقرأ JSON ويملأ كل شي (يرمي استثناء إذا الملف غلط)
    private fun parse(raw: String) {
        val o = JSONObject(raw)
        val ms = o.getJSONArray("m")
        macros.clear()
        anchors.clear()
        btns.clear()
        for (r in res) { r.x = 0; r.y = 0; r.w = 0; r.h = 0; r.min = 0L }
        cur = o.optInt("cur", 0)
        cfg.timeout = o.optLong("t", 4000L)
        cfg.tries = o.optInt("k", 20)
        cfg.change = o.optInt("c", 8)
        cfg.loopGap = o.optLong("lg", 2000L)
        cfg.loops = o.optInt("lp", 0)
        cfg.match = o.optInt("mt", 10)
        cfg.poll = o.optLong("pl", 400L)
        cfg.repress = o.optLong("rp", 1500L)
        refW = o.optInt("rw", 0)
        refH = o.optInt("rh", 0)
        val bt = o.optJSONArray("bt")
        if (bt != null) {
            for (i in 0 until bt.length()) {
                val q = bt.getJSONObject(i)
                btns.add(Btn(q.getString("i"), q.getString("n"), q.getInt("k")))
            }
        }
        val rj = o.optJSONArray("rs")
        if (rj != null) {
            for (i in 0 until rj.length()) {
                val q = rj.getJSONObject(i)
                val r = res.firstOrNull { it.id == q.getString("i") } ?: continue
                r.x = q.optInt("x")
                r.y = q.optInt("y")
                r.w = q.optInt("w")
                r.h = q.optInt("h")
                r.min = q.optLong("m", 0L)
            }
        }
        for (i in 0 until ms.length()) {
            val mo = ms.getJSONObject(i)
            val m = Macro(mo.getString("n"))
            val ss = mo.getJSONArray("s")
            for (j in 0 until ss.length()) {
                val s = ss.getJSONObject(j)
                val sk = s.getJSONArray("k")
                val list = ArrayList<Stroke>()
                for (q in 0 until sk.length()) {
                    val ko = sk.getJSONObject(q)
                    val pts = ko.getString("p").split(",").map { it.toFloat() }.toFloatArray()
                    list.add(Stroke(ko.getLong("o"), ko.getLong("d"), pts))
                }
                if (list.isNotEmpty()) {
                    m.steps.add(
                        Step(list, s.getLong("bef"), s.getBoolean("v"), s.getInt("r"), s.getLong("g"))
                    )
                }
            }
            macros.add(m)
        }
        val an = o.optJSONArray("an")
        if (an != null) {
            for (i in 0 until an.length()) {
                val q = an.getJSONObject(i)
                val sig = q.getString("g").split(",").map { it.toInt() }.toIntArray()
                addAnchor(
                    Anchor(
                        q.getString("r"), q.getInt("x"), q.getInt("y"), q.getInt("w"), q.getInt("h"), sig
                    )
                )
            }
        }
    }

    private fun fix() {
        if (btns.isEmpty()) defaultBtns()
        if (macros.isEmpty()) macros.add(Macro("M1"))
        if (cur !in macros.indices) cur = 0
    }

    private fun load(c: Context) {
        macros.clear()
        anchors.clear()
        btns.clear()
        val raw = c.getSharedPreferences("m4", Context.MODE_PRIVATE).getString("db", null)
        if (raw != null) {
            try {
                parse(raw)
            } catch (e: Exception) {
            }
        }
        fix()
    }

    // تكبير/تصغير الإحداثيات حسب حجم شاشة الجهاز الجديد
    private fun scale(kx: Float, ky: Float) {
        for (m in macros) for (s in m.steps) for (k in s.strokes) {
            for (i in k.pts.indices) k.pts[i] = k.pts[i] * (if (i % 2 == 0) kx else ky)
        }
        val old = anchors.values.flatten()
        anchors.clear()
        for (a in old) addAnchor(
            Anchor(
                a.role, (a.x * kx).toInt(), (a.y * ky).toInt(),
                maxOf(2, (a.w * kx).toInt()), maxOf(2, (a.h * ky).toInt()), a.sig
            )
        )
        for (r in res) {
            r.x = (r.x * kx).toInt()
            r.y = (r.y * ky).toInt()
            r.w = (r.w * kx).toInt()
            r.h = (r.h * ky).toInt()
        }
    }

    // استيراد ملف: يستبدل كل شي. يرجع null إذا نجح، أو رسالة الخطأ
    fun importJson(c: Context, raw: String, screenW: Int, screenH: Int): String? {
        val old = build().toString()
        try {
            parse(raw)
            if (refW > 0 && refH > 0 && screenW > 0 && screenH > 0) {
                val long = maxOf(screenW, screenH)
                val short = minOf(screenW, screenH)
                val land = refW > refH
                val nw = if (land) long else short
                val nh = if (land) short else long
                val kx = nw.toFloat() / refW
                val ky = nh.toFloat() / refH
                if (kx != 1f || ky != 1f) scale(kx, ky)
                refW = nw
                refH = nh
            }
            fix()
            save(c)
            return null
        } catch (e: Exception) {
            try {
                parse(old)
            } catch (e2: Exception) {
            }
            fix()
            return "الملف غير صالح أو تالف"
        }
    }
}
