package com.macro.rec

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import kotlin.math.abs

// سطر نص قرأه الـOCR من الشاشة مع مكانه
private class TLine(val text: String, val box: Rect) {
    val low: String = text.lowercase().trim()
    val cx: Int get() = box.centerX()
    val cy: Int get() = box.centerY()
}

// الشاشة اللي نكون فيها أثناء تطوير الجدران
private enum class Scene { NEED_MORE, CONFIRM, WALL_BAR, HOME, LIST, UNKNOWN }

/**
 * يطوّر الجدران: يفتح Build، يلكى صف Wall بالقائمة (يسحب إذا لازم)، يضغطه،
 * يختار الذهب أو الإكسير، يضغط Confirm، ويكرر لين تخلص الموارد.
 *
 * الفكرة: كل دورة = لقطة شاشة + OCR، نعرف أي شاشة احنا بيها، ونسوي خطوة وحدة فقط.
 * هيج إذا صار أي شي غير متوقع (نافذة زيادة، تأخير...) يرجع يفهم الشاشة من جديد ويكمل.
 * ما نضغط أبداً زر شراء الناقص بالجواهر: إذا طلعت نافذة "You need more" نسكرها ونعتبر المورد خلص.
 */
class WallBot(
    private val svc: MacroService,
    private val onDone: (Int, String) -> Unit
) {
    companion object {
        const val BUILD = "w_build"
        private const val LIST_X = 0.68f     // القائمة تبدأ من 68% من عرض الشاشة
        private const val LIST_Y0 = 0.11f    // أول صف
        private const val LIST_Y1 = 0.86f    // آخر صف
        private const val BAR_Y = 0.70f      // شريط أزرار Upgrade من تحت
        private const val MAX_CYCLES = 1500
        private const val MAX_SCROLLS = 24
        private val NAMES = arrayOf("الذهب", "الإكسير")
    }

    private var alive = true
    private var upgrades = 0
    private val exhausted = BooleanArray(2)   // 0 ذهب، 1 إكسير: خلص (طلعت نافذة You need more)
    private var lastTry = -1                  // آخر مورد ضغطنا زره
    private var pending = false               // ضغطنا Confirm وننتظر نتيجته
    private var finishing = false
    private var finishReason = ""
    private var cycles = 0

    private var lastScene = Scene.UNKNOWN
    private var sameScene = 0
    private var closeTries = 0
    private var openTries = 0

    private var dir = 1                       // 1 نسحب القائمة لتحت، -1 لفوق
    private var scrolls = 0
    private var turns = 0
    private var lastRows = ""

    fun start() {
        svc.say("🧱…")
        step(300)
    }

    fun cancel() {
        alive = false
    }

    private fun step(ms: Long) {
        svc.h.postDelayed({ if (alive) observe() }, ms)
    }

    private fun end(reason: String) {
        if (!alive) return
        alive = false
        onDone(upgrades, reason)
    }

    private fun finish(reason: String) {
        if (!finishing) {
            finishing = true
            finishReason = reason
        }
    }

    // ---- دورة المراقبة: لقطة + OCR ----
    private fun observe() {
        cycles++
        if (cycles > MAX_CYCLES) {
            end("وصلت الحد الأقصى للعمليات")
            return
        }
        svc.grab { b ->
            if (!alive) {
                b?.recycle()
                return@grab
            }
            if (b == null) {
                step(800)
                return@grab
            }
            svc.ocr.process(InputImage.fromBitmap(b, 0))
                .addOnSuccessListener { t ->
                    try {
                        if (alive) {
                            val lines = ArrayList<TLine>()
                            for (blk in t.textBlocks) {
                                for (ln in blk.lines) {
                                    val r = ln.boundingBox ?: continue
                                    lines.add(TLine(ln.text, r))
                                }
                            }
                            val home = svc.seen(b, BUILD)
                            handle(lines, home, b)
                        }
                    } catch (e: Throwable) {
                        svc.toast("خطأ جدران: ${e.javaClass.simpleName} ${e.message ?: ""}")
                        end("خطأ")
                    } finally {
                        b.recycle()
                    }
                }
                .addOnFailureListener {
                    b.recycle()
                    if (alive) step(900)
                }
        }
    }

    // ---- نعرف الشاشة ونسوي خطوة وحدة ----
    private fun handle(lines: List<TLine>, home: Boolean, b: Bitmap) {
        val w = b.width
        val h = b.height
        val all = lines.joinToString(" ") { it.low }
        val rows = wallRows(lines, w, h)
        val bar = barButtons(lines, h)
        val scene = when {
            all.contains("need more") || all.contains("the missing") -> Scene.NEED_MORE
            lines.any { it.low.contains("confirm") } -> Scene.CONFIRM
            bar.size >= 2 -> Scene.WALL_BAR
            home -> Scene.HOME
            rows.isNotEmpty() || all.contains("buildings") || all.contains("heroes") ||
                all.contains("upgrades") -> Scene.LIST
            else -> Scene.UNKNOWN
        }

        if (scene == lastScene) {
            sameScene++
        } else {
            sameScene = 0
            closeTries = 0
        }
        lastScene = scene
        if (scene != Scene.HOME) openTries = 0
        if (sameScene >= (if (scene == Scene.LIST) 20 else 7)) {
            end("علقت بنفس الشاشة")
            return
        }

        // Confirm انضغط قبل شوي: إذا ما طلعت نافذة نقص موارد يعني التطوير نجح
        if (pending) {
            pending = false
            if (scene != Scene.NEED_MORE && scene != Scene.CONFIRM) {
                upgrades++
                svc.say("🧱 $upgrades")
                val mx = Store.cfg.wallMax
                if (mx > 0 && upgrades >= mx) finish("وصلت الحد الأقصى ($mx)")
            }
        }

        when (scene) {
            Scene.NEED_MORE -> {
                val r = when {
                    all.contains("dark") -> -1
                    all.contains("elixir") -> 1
                    all.contains("gold") -> 0
                    else -> lastTry
                }
                if (r in 0..1) {
                    exhausted[r] = true
                } else {
                    exhausted[0] = true
                    exhausted[1] = true
                }
                val nm = if (r in 0..1) NAMES[r] else "الموارد"
                svc.say("⛔ ناقص $nm")
                closeTop(scene, w, h)
            }

            Scene.CONFIRM -> {
                val bad = !all.contains("wall") || (lastTry in 0..1 && exhausted[lastTry])
                if (finishing || bad) {
                    closeTop(scene, w, h)
                } else {
                    val c = lines.first { it.low.contains("confirm") }
                    pending = true
                    svc.tapAt(c.cx, c.cy) { step(1100) }
                }
            }

            Scene.WALL_BAR -> {
                if (finishing) {
                    closeTop(scene, w, h)
                    return
                }
                // الأزرار مرتبة من اليسار: [Upgrade More]؟ ثم ذهب ثم إكسير، نأخذ آخر اثنين
                val two = if (bar.size >= 3) bar.takeLast(2) else bar
                chooseUpgrade(two, lines, b, w, h)
            }

            Scene.LIST -> {
                if (finishing) {
                    closeTop(scene, w, h)
                    return
                }
                if (rows.isNotEmpty()) {
                    // أعلى صف Wall = أغلى مستوى
                    val r = rows.minByOrNull { it.cy }!!
                    scrolls = 0
                    turns = 0
                    lastRows = ""
                    svc.say("🧱 …")
                    svc.tapAt((w * 0.80f).toInt(), r.cy + (h * 0.02f).toInt()) { step(1000) }
                } else {
                    scrollList(lines, w, h)
                }
            }

            Scene.HOME -> {
                if (finishing) {
                    end(finishReason)
                    return
                }
                openTries++
                if (openTries > 4) {
                    end("ما انفتحت قائمة Build")
                    return
                }
                svc.say("🔨 Build")
                svc.press(BUILD)
                step(1300)
            }

            Scene.UNKNOWN -> {
                svc.say("؟${sameScene + 1}")
                if (sameScene >= 2 && sameScene % 2 == 0) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
                step(900)
            }
        }
    }

    // ---- اختيار المورد (ذهب/إكسير) وضغط زر Upgrade ----
    private fun chooseUpgrade(btns: List<TLine>, lines: List<TLine>, b: Bitmap, w: Int, h: Int) {
        val cf = Store.cfg
        val keep = longArrayOf(cf.wallKeepGold, cf.wallKeepElixir)
        val price = LongArray(2) { priceAbove(lines, btns[it], w, h) ?: -1L }
        // رصيدك الحالي من شريط مخزنك (إذا محدد منطقته)، نستخدمه للترتيب وللاحتفاظ فقط
        val own = Store.own
        val idx = (0..1).filter { own[it].w > 0 }
        val regs = idx.map { own[it] }
        svc.readAll(b, regs) { vals ->
            if (!alive) return@readAll
            val have = LongArray(2) { -1L }
            for (j in idx.indices) have[idx[j]] = vals[j] ?: -1L
            val order = (0..1)
                .filter { !exhausted[it] }
                .filter { r -> keep[r] <= 0L || have[r] < 0L || have[r] - maxOf(price[r], 0L) >= keep[r] }
                .sortedByDescending { have[it] }
            if (order.isEmpty()) {
                finish(if (exhausted[0] && exhausted[1]) "خلصت الموارد" else "وصلنا لحد الاحتفاظ")
                step(300)
                return@readAll
            }
            val r = order.first()
            lastTry = r
            svc.say("🧱 ${NAMES[r]}")
            svc.tapAt(btns[r].cx, btns[r].cy) { step(1000) }
        }
    }

    // ---- سحب القائمة للبحث عن Wall ----
    private fun scrollList(lines: List<TLine>, w: Int, h: Int) {
        val sig = lines
            .filter { it.cx > w * LIST_X && it.cy > h * LIST_Y0 && it.cy < h * LIST_Y1 }
            .joinToString("|") { it.low }
        if (scrolls > 0 && sig == lastRows) {
            // ما تغيرت القائمة = وصلنا لنهايتها بهذا الاتجاه
            dir = -dir
            turns++
            if (turns >= 3) {
                finish("ما لقيت Wall بالقائمة")
                step(300)
                return
            }
        }
        lastRows = sig
        scrolls++
        if (scrolls > MAX_SCROLLS) {
            finish("ما لقيت Wall بالقائمة")
            step(300)
            return
        }
        svc.say("↕ ${scrolls}")
        val x = (w * 0.83f).toInt()
        val a = (h * 0.72f).toInt()
        val z = (h * 0.28f).toInt()
        val y1 = if (dir > 0) a else z
        val y2 = if (dir > 0) z else a
        svc.swipe(x, y1, x, y2, 600L) { step(800) }
    }

    // ---- إغلاق النافذة العليا: Back أول، وإذا ما نفع نضغط X حسب مكانه ----
    private fun closeTop(sc: Scene, w: Int, h: Int) {
        val n = closeTries
        closeTries++
        if (n % 2 == 0) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            step(900)
        } else {
            val fx: Float
            val fy: Float
            when (sc) {
                Scene.NEED_MORE -> { fx = 0.663f; fy = 0.302f }
                Scene.CONFIRM -> { fx = 0.807f; fy = 0.076f }
                else -> { fx = 0.946f; fy = 0.039f }
            }
            svc.tapAt((w * fx).toInt(), (h * fy).toInt()) { step(900) }
        }
    }

    // ---- أدوات قراءة النص ----
    private fun wallRows(lines: List<TLine>, w: Int, h: Int): List<TLine> =
        lines.filter {
            it.cx > w * LIST_X && it.cy > h * LIST_Y0 && it.cy < h * LIST_Y1 &&
                it.low.startsWith("wal") && !it.low.contains("breaker")
        }

    // أزرار Upgrade بالشريط السفلي (نطابق الكلمة بالضبط حتى ما ندخل "Upgrade time" و"Upgrade More")
    private fun barButtons(lines: List<TLine>, h: Int): List<TLine> =
        lines.filter { it.low == "upgrade" && it.cy > h * BAR_Y }.sortedBy { it.cx }

    // السعر المكتوب فوق الزر
    private fun priceAbove(lines: List<TLine>, btn: TLine, w: Int, h: Int): Long? {
        var best: TLine? = null
        for (l in lines) {
            if (abs(l.cx - btn.cx) > w * 0.06f) continue
            if (l.cy >= btn.cy || l.cy < btn.cy - h * 0.2f) continue
            if (numOf(l.text) == null) continue
            val cur = best
            if (cur == null || l.cy > cur.cy) best = l
        }
        val pick = best ?: return null
        return numOf(pick.text)
    }

    private fun numOf(t: String): Long? {
        val s = t.replace('O', '0').replace('o', '0').replace(Regex("[\\s.,]"), "")
        if (s.isEmpty() || s.length > 12 || !s.all { it.isDigit() }) return null
        return s.toLongOrNull()
    }
}
