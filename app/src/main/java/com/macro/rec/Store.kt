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
    var loopGap: Long = 2000L, var loops: Int = 0, var match: Int = 10
)

// زر من اللعبة نتعرف عليه: مكانه وبصمة صورته
class Anchor(
    val role: String, val x: Int, val y: Int, val w: Int, val h: Int, val sig: IntArray
) {
    val cx: Int get() = x + w / 2
    val cy: Int get() = y + h / 2
}

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
    val anchors = HashMap<String, Anchor>()
    val roles = listOf(
        "attack" to "زر الهجوم (القرية)",
        "find" to "زر البحث عن خصم",
        "next" to "زر Next",
        "home" to "زر Return Home",
        "ok" to "زر موافق (اختياري)"
    )
    private var ready = false

    fun roleName(k: String): String = roles.firstOrNull { it.first == k }?.second ?: k

    fun ensure(c: Context) {
        if (!ready) { load(c); ready = true }
    }

    fun save(c: Context) {
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
        for (a in anchors.values) an.put(
            JSONObject().put("r", a.role).put("x", a.x).put("y", a.y).put("w", a.w).put("h", a.h)
                .put("g", a.sig.joinToString(","))
        )
        val o = JSONObject().put("cur", cur).put("m", ms).put("an", an)
            .put("t", cfg.timeout).put("k", cfg.tries).put("c", cfg.change)
            .put("lg", cfg.loopGap).put("lp", cfg.loops).put("mt", cfg.match)
        c.getSharedPreferences("m4", Context.MODE_PRIVATE).edit()
            .putString("db", o.toString()).apply()
    }

    private fun load(c: Context) {
        macros.clear()
        anchors.clear()
        val raw = c.getSharedPreferences("m4", Context.MODE_PRIVATE).getString("db", null)
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                cur = o.optInt("cur", 0)
                cfg.timeout = o.optLong("t", 4000L)
                cfg.tries = o.optInt("k", 20)
                cfg.change = o.optInt("c", 8)
                cfg.loopGap = o.optLong("lg", 2000L)
                cfg.loops = o.optInt("lp", 0)
                cfg.match = o.optInt("mt", 10)
                val ms = o.getJSONArray("m")
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
                        anchors[q.getString("r")] = Anchor(
                            q.getString("r"), q.getInt("x"), q.getInt("y"), q.getInt("w"), q.getInt("h"), sig
                        )
                    }
                }
            } catch (e: Exception) {
            }
        }
        if (macros.isEmpty()) macros.add(Macro("M1"))
        if (cur !in macros.indices) cur = 0
    }
}
