package com.macro.rec

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    var loopGap: Long = 2000L, var loops: Int = 0
)

object Store {
    val macros = ArrayList<Macro>()
    var cur = 0
    val cfg = Cfg()
    private var ready = false

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
        val o = JSONObject().put("cur", cur).put("m", ms)
            .put("t", cfg.timeout).put("k", cfg.tries).put("c", cfg.change)
            .put("lg", cfg.loopGap).put("lp", cfg.loops)
        c.getSharedPreferences("m4", Context.MODE_PRIVATE).edit()
            .putString("db", o.toString()).apply()
    }

    private fun load(c: Context) {
        macros.clear()
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
            } catch (e: Exception) {
            }
        }
        if (macros.isEmpty()) macros.add(Macro("M1"))
        if (cur !in macros.indices) cur = 0
    }
}
