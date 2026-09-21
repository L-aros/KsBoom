package io.github.angbang852.manjiao.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

object GoldFloatHook {
    private val handler = Handler(Looper.getMainLooper())

    // ★ 金币红包浮窗真实类名（手机 dumpsys 实测抓到）：
    // com.kuaishou.growth.pendant.coin.core.kds.unionwidget.UnionView            —— 299x299 可拖动浮球主体
    // com.kuaishou.growth.pendant.coin.core.kds.unionwidget.absorb.UnionAbsorbView —— 140x175 吸附侧边收纳条
    // com.kuaishou.growth.pendant.ui.widget.PendantDrawerView                    —— 抽屉挂件
    // 之前只 hook WindowManager.addView 拦不到：它们是 addView 到 Activity DecorView 的，
    // 不经过 WindowManagerImpl —— 所以"隐藏不了"。改成直接 hook 这三个类的 onAttachedToWindow
    private val FLOAT_CLASSES = arrayOf(
        "com.kuaishou.growth.pendant.coin.core.kds.unionwidget.UnionView",
        "com.kuaishou.growth.pendant.coin.core.kds.unionwidget.absorb.UnionAbsorbView",
        "com.kuaishou.growth.pendant.ui.widget.PendantDrawerView"
    )

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        hookAddView(xp, cl)
        hookFloatClasses(xp, cl)
        Logger.d("GoldFloatHook installed")
    }

    // 挂点一：浮窗类自身的 onAttachedToWindow —— 每次浮窗出现（含拖动后重新 attach）必经
    private fun hookFloatClasses(xp: XposedInterface, cl: ClassLoader) {
        for (cn in FLOAT_CLASSES) {
            Logger.safe("gold.cls.$cn") {
                val c = Reflect.findClass(cn, cl) ?: run { Logger.d("gold cls not found: $cn"); return@safe }
                val m = try { c.getDeclaredMethod("onAttachedToWindow") } catch (_: Throwable) { null }
                if (m == null) {
                    // ★ 兜底接线（审阅 2026-09 P3）：类未覆写 onAttachedToWindow 时
                    // 原实现直接 return@safe，连下方的防御 setVisibility hook 也被跳过，
                    // 该类完全无防护。改挂 onWindowVisibilityChanged 兜底
                    Logger.d("gold cls no onAttachedToWindow: $cn (fallback onWindowVisibilityChanged)")
                    val vm0 = try { c.getDeclaredMethod("onWindowVisibilityChanged", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
                    if (vm0 != null) {
                        try {
                            xp.hook(vm0).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                .setId("gold.attachfb.$cn").intercept { chain ->
                                    chain.proceed()
                                    try { (chain.thisObject as? View)?.let { hideNow(it); scheduleHide(it) } } catch (_: Throwable) {}
                                    null
                                }
                        } catch (_: Throwable) {}
                    }
                    return@safe
                }
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("gold.attach.$cn")
                    .intercept { chain ->
                        chain.proceed()
                        try {
                            val v = chain.thisObject as? View ?: return@intercept null
                            val p = v.parent
                            Logger.d("gold float attach: $cn parent=${p?.javaClass?.name}")
                            // ★ 源头阻断第一步（2026-09-21）：attach 后同步置 GONE。
                            // GONE 不依赖测量结果，故无需等宽高——原实现"宽高为 0 就再等
                            // 500ms"正是闪现的主因（那半秒浮窗是实打实可见的）
                            hideNow(v)
                            scheduleHide(v)
                        } catch (_: Throwable) {}
                        null
                    }
                // ★ 防御二（2026-09-21 重写）：快手拖动/重显时会调 setVisibility(VISIBLE)
                // 把浮窗 show 回来。原实现"先 proceed 让它显示、再延迟隐藏"⇒ 每次必露
                // 一帧，且与快手的重显循环形成 HIDDEN↔re-show 拉锯（实测 65 次/分钟，
                // 即用户看到的"反复闪现"）。改为在 proceed 之前把 VISIBLE 改写为 GONE，
                // 显示调用根本不生效 ⇒ 零帧可见、零闪现。
                Logger.safe("gold.vis.$cn") {
                    val vm = try { c.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
                    if (vm != null) {
                        xp.hook(vm).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("gold.vis.$cn")
                            .intercept { chain ->
                                var wantShow = false
                                try { wantShow = (chain.args.firstOrNull() as? Int) == View.VISIBLE } catch (_: Throwable) {}
                                val on = try { Prefs.bool(Prefs.K_IMM_GOLD, false) } catch (_: Throwable) { false }
                                var r: Any? = null
                                if (wantShow && on) {
                                    // ★ 正确改参方式（2026-09-21 实测修正）：libxposed 的
                                    // Chain.getArgs() 返回**只读 List**，`chain.args[0] = x`
                                    // 走 List.set() 会抛 UnsupportedOperationException（被
                                    // catch 吞掉 → 既不拦也不报，浮窗彻底失管）。必须用
                                    // proceed(Object[]) 重载携带新参数。
                                    r = try {
                                        val na = chain.args.toMutableList()
                                        na[0] = View.GONE
                                        chain.proceed(na.toTypedArray())
                                    } catch (_: Throwable) {
                                        try { chain.proceed() } catch (_: Throwable) { null }
                                    }
                                } else {
                                    r = try { chain.proceed() } catch (_: Throwable) { null }
                                }
                                if (wantShow && on) {
                                    // 同步兜底：即便框架忽略新参，同一次主线程回调内立即置
                                    // GONE（不等下一帧 → 不会被绘制）
                                    try { (chain.thisObject as? View)?.let { hideNow(it) } } catch (_: Throwable) {}
                                    if (blockShowDiag < 20) {
                                        blockShowDiag++
                                        Logger.d("gold float BLOCK show: ${c.simpleName}")
                                    }
                                }
                                r
                            }
                    }
                }
            }
        }
    }

    // ★ 源头阻断计数（限次日志，避免刷屏）
    private var blockShowDiag = 0

    // 同步立即隐藏：GONE 不依赖宽高测量，故不做尺寸前置判断
    private fun hideNow(v: View) {
        try {
            if (!Prefs.bool(Prefs.K_IMM_GOLD, false)) return
            if (v.visibility == View.GONE) return
            v.visibility = View.GONE
            Logger.d("gold float HIDDEN(now): ${v.javaClass.simpleName}")
        } catch (_: Throwable) {}
    }

    private fun scheduleHide(v: View) {
        // attach 后快手仍可能经非 setVisibility 路径（内部字段/动画/父容器）让它复现，
        // 保留有界看门狗：10 次 × 500ms（5s 窗口）即止，不再做"宽高为 0 就盲等"的延迟
        val task = object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                if (v.parent == null) return
                if (tries > 10) return
                if (!Prefs.bool(Prefs.K_IMM_GOLD, false)) return
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    if (tries <= 3) Logger.d("gold float HIDDEN(watch$tries): ${v.javaClass.simpleName}")
                }
                v.postDelayed(this, 500)
            }
        }
        v.post(task)
    }

    private fun hookAddView(xp: XposedInterface, cl: ClassLoader) {
        for (cn in arrayOf("android.view.WindowManagerImpl", "android.view.WindowManagerGlobal")) {
            val c = Reflect.findClass(cn, cl) ?: continue
            for (m in c.declaredMethods) {
                if (m.name != "addView") continue
                if (m.parameterTypes.size < 2) continue
                if (m.parameterTypes[0] != View::class.java) continue
                Logger.safe("gold.hook.$cn") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("gold.$cn.addView").intercept { chain ->
                        chain.proceed()
                        try { onViewAdded(chain.args[0] as? View) } catch (_: Throwable) {}
                        null
                    }
                }
            }
        }
    }

    private fun onViewAdded(v: View?) {
        if (v == null) return
        handler.postDelayed({
            Logger.safe("gold.check") { checkFloat(v) }
        }, 800)
    }

    private fun checkFloat(v: View) {
        if (v.width == 0 || v.height == 0) return
        val w = v.width; val h = v.height
        if (w > 480 || h > 480) return
        val txt = collectText(v)
        val cd = v.contentDescription?.toString() ?: ""
        val idName = try { val rid = v.id; if (rid != View.NO_ID) v.resources.getResourceEntryName(rid) else "" } catch (_: Throwable) { "" }
        Logger.d("gold win cls=${v.javaClass.simpleName} id=$idName w=$w h=$h t=${txt.take(15)} cd=${cd.take(15)} kids=${if (v is ViewGroup) v.childCount else 0}")
        if (!Prefs.bool(Prefs.K_IMM_GOLD, false)) return
        // 浮窗类名直接命中（UnionView 等）也走 WindowManager 路径时兜底
        val clsHit = FLOAT_CLASSES.any { v.javaClass.name == it }
        val strong = clsHit || txt.contains("已完成") || txt.contains("金币") || txt.contains("红包") || txt.contains("领取") ||
            cd.contains("已完成") || cd.contains("金币") || cd.contains("红包") || cd.contains("领取") ||
            idName.contains("gold", true) || idName.contains("coin", true) || idName.contains("red_packet", true)
        if (strong && w in 40..320 && h in 40..320) {
            v.visibility = View.GONE
            Logger.d("gold HIDDEN strong cls=${v.javaClass.simpleName} w=$w h=$h t=${txt.take(10)}")
            return
        }
        // ★ 启发式 plain 分支已移除（审阅 2026-09 P2）：「无 id 无文字 40-240px 小窗
        // 直接 GONE 且无恢复路径」误命中任意无名小 view 后永久消失、关开关也不恢复。
        // 保留强信号分支（精确类名 / 金币文本 / id 命名），漏网的由
        // FloatClasses 精确 hook 兜底
    }

    private fun collectText(v: View): String {
        val sb = StringBuilder()
        fun rec(x: View) {
            if (x is TextView) x.text?.let { sb.append(it) }
            if (sb.length > 30) return
            if (x is ViewGroup) for (i in 0 until x.childCount) rec(x.getChildAt(i) ?: return)
        }
        rec(v)
        return sb.toString().trim()
    }
}
