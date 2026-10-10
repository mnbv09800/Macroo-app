package com.macro.rec

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Step(
    var x1: Float, var y1: Float, var x2: Float, var y2: Float,
    var dur: Long, var before: Long, var verify: Boolean,
    var repeat: Int = 1, var repGap: Long = 150L
)

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
            for (s in m.steps) ss.put(
                JSONObject()
                    .put("a", s.x1.toDouble()).put("b", s.y1.toDouble())
                    .put("c", s.x2.toDouble()).put("d", s.y2.toDouble())
                    .put("dur", s.dur).put("bef", s.before).put("v", s.verify)
                    .put("r", s.repeat).put("g", s.repGap)
            )
            ms.put(JSONObject().put("n", m.name).put("s", ss))
        }
        val o = JSONObject().put("cur", cur).put("m", ms)
            .put("t", cfg.timeout).put("k", cfg.tries).put("c", cfg.change)
            .put("lg", cfg.loopGap).put("lp", cfg.loops)
        c.getSharedPreferences("m", Context.MODE_PRIVATE).edit()
            .putString("db", o.toString()).apply()
    }

    private fun load(c: Context) {
        macros.clear()
        val raw = c.getSharedPreferences("m", Context.MODE_PRIVATE).getString("db", null)
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
                        m.steps.add(
                            Step(
                                s.getDouble("a").toFloat(), s.getDouble("b").toFloat(),
                                s.getDouble("c").toFloat(), s.getDouble("d").toFloat(),
                                s.getLong("dur"), s.getLong("bef"), s.getBoolean("v"),
                                s.getInt("r"), s.getLong("g")
                            )
                        )
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
