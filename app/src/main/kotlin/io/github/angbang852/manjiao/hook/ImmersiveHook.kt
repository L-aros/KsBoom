package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

object ImmersiveHook {

    private var dumped = false
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var tracked: Activity? = null
    @Volatile private var active = false
    private val HIDDEN_TAG = Object()
    private val STRIP_TAG = Object()
    private val GOLD_FAST_IDS = arrayOf("photo_detail_placeholder_lottie", "photo_detail_gold_coin", "gold_coin_pendant", "coin_float_container")
    private var passCount = 0
    private var skipGc = 0
    private val RESET_INTERVAL = 3

    private val HIDE_IDS = arrayOf(
        "bottom_bar_and_grey_cover_container", "bottom_bar", "main_tab_container",
        "comment_container", "comment_editor_container",
        "ai_text_container", "milano_player_seekbar", "nasa_milano_progress_container",
        "speed_anim_container", "pad_slide_auto_play_icon",
        "title_root", "title_mask", "home_fragment_vip", "home_tab_bg", "block_tab_bg",
        "block_float_tabs_mask", "left_btn_parent", "live_btn", "home_share_opened_tip_view",
        "right_action_group", "side_progress_group", "slide_play_like_image",
        "like_button", "comment_button", "collect_button", "forward_button",
        "music_wheel", "slide_play_right_follow", "follow_button",
        "group_right_action_bar_root_layout"
    )

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        val targets = mutableSetOf(
            KsClass.PHOTO_DETAIL_ACTIVITY,
            KsClass.PHOTO_DETAIL_ACTIVITY_TABLET,
            "com.yxcorp.gifshow.HomeActivity",
            "com.yxcorp.gifshow.HomeActivityTablet"
        )
        for (a in targets) {
            val c = Reflect.findClass(a, cl) ?: continue
            val m1 = Reflect.findMethod(c, "onResume", 0)
            if (m1 != null) xp.hook(m1).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("imm.$a").intercept { chain ->
                chain.proceed(); try { start(chain.thisObject as Activity) } catch (_: Throwable) {}; null
            }
            val mOff = Reflect.findMethod(c, "onPause", 0)
            if (mOff != null) xp.hook(mOff).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("imm.off.$a").intercept { chain ->
                chain.proceed(); try { stop(chain.thisObject as Activity) } catch (_: Throwable) {}; null
            }
            Logger.d("ImmersiveHook on $a m1=${m1 != null} off=${mOff != null}")
        }
    }


    // ★ 监听所挂 decor 弱引用（审阅 2026-09 P3）：stop 只在 tracked===act 时移除，
    // B 顶 A 时 A 的监听残留泄漏——记录挂载点，start 前先对旧 decor 反注册
    @Volatile private var vtoDecor: android.view.View? = null

    // ★ GOLD id 解析缓存（流畅度）：onlyGoldOn 每轮 pass 对 14 个 id × 2 命名空间
    // 调 getIdentifier（资源表字符串查找，主线程）——进程内资源 id 恒定，解析一次
    // 查表即可；未命中(0)也缓存，避免每轮空查
    private val goldIdCache = java.util.HashMap<String, Int>()

    private fun goldId(act: Activity, gn: String): Int {
        synchronized(goldIdCache) {
            goldIdCache[gn]?.let { return it }
            val gid = act.resources.getIdentifier(gn, "id", act.packageName)
                .takeIf { it != 0 } ?: act.resources.getIdentifier(gn, "id", KsClass.PKG)
            goldIdCache[gn] = gid
            return gid
        }
    }

    private fun start(act: Activity) {
        active = true
        tracked = act
        val immOn = Prefs.bool(Prefs.K_IMM_ON, false)
        val immCustom = Prefs.bool(Prefs.K_IMM_CUSTOM, false)
        Logger.safe("immersive") {
            // 只在一键沉浸/自定义隐藏开启时才隐藏系统栏，避免开关全关时顶栏被误藏
            if (immOn || immCustom || anyCustomSubOn()) hideSystemUi(act)
            val decor = act.window.decorView
            if (!dumped) {
                dumped = true
                decor.postDelayed({ dumpUi(act) }, 1200)
            }
            Logger.safe("vto") {
                // 幂等：先从旧挂载点（若有）与当前 decor 反注册同实例再注册，
                // 防 B 顶 A 场景 A 的监听泄漏 + 同实例重复注册
                vtoDecor?.viewTreeObserver?.let { old ->
                    try { old.removeOnScrollChangedListener(scrollListener) } catch (_: Throwable) {}
                    try { old.removeOnGlobalLayoutListener(layoutListener) } catch (_: Throwable) {}
                }
                val vto = decor.viewTreeObserver
                vto.removeOnScrollChangedListener(scrollListener)
                vto.removeOnGlobalLayoutListener(layoutListener)
                vto.addOnScrollChangedListener(scrollListener)
                vto.addOnGlobalLayoutListener(layoutListener)
                vtoDecor = decor
            }
        }
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 100)
    }

    private fun stop(act: Activity) {
        // 反注册不依赖 tracked 身份：任何 stop 都清掉自己 decor 上的监听
        vtoDecor?.viewTreeObserver?.let { vto ->
            Logger.safe("vtoOff") {
                try { vto.removeOnScrollChangedListener(scrollListener) } catch (_: Throwable) {}
                try { vto.removeOnGlobalLayoutListener(layoutListener) } catch (_: Throwable) {}
            }
        }
        vtoDecor = null
        if (tracked === act) {
            active = false
            tracked = null
            handler.removeCallbacks(hideTask)
            handler.removeCallbacks(quickHide)
        }
    }

    private val quickHide = Runnable {
        val act = tracked
        if (act != null && active) Logger.safe("immQuick") { hideByConfig(act) }
        vtreeDirty = false
    }

    private fun scheduleQuickHide() {
        if (onlyGoldOn()) return
        handler.removeCallbacks(quickHide)
        handler.postDelayed(quickHide, 250)
    }

    // ★ 无变化跳过全树遍历：滚动/布局事件置脏；hideTask 周期 pass 在「模式未变且
    // 视图树无变化」时直接跳过 walkAll（配置切换走 mode 比对，仍会执行）。
    // 全树 walkAll 一次几千个 View，是沉浸模式下的常驻主线程开销
    @Volatile private var vtreeDirty = true

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { vtreeDirty = true; scheduleQuickHide() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { vtreeDirty = true; scheduleQuickHide() }

    /**
     * 沉浸相关开关的一次性快照（性能修复 2026-09 · S3/M2）。
     *
     * 原实现里 `onlyGoldOn()`（7 次 Prefs 读）、`anyCustomSubOn()`（6 次）、
     * `hideByConfig` 内的 mode 计算（2 次）各自独立读取，且 `hideTask` 的
     * 判定分支与 finally 分支各调一次 —— 单轮最多 20+ 次 Prefs 读取。
     * 这些开关在一轮内不会变化，统一快照后各逻辑改读快照字段。
     *
     * 注意：快照只在**单轮 pass 内**有效，不跨轮复用（否则用户拨开关后要等下一轮）。
     */
    private class ImmMode(
        val immOn: Boolean, val immCustom: Boolean,
        val topbar: Boolean, val right: Boolean, val bottom: Boolean,
        val nickname: Boolean, val collection: Boolean, val gold: Boolean
    ) {
        /** 任一自定义子项开启（原 anyCustomSubOn） */
        val anyCustomSub: Boolean get() = topbar || right || bottom || nickname || collection || gold
        /** 仅金币开启、其余全关（原 onlyGoldOn，决定轮询可降到 5s） */
        val onlyGold: Boolean get() = gold && !topbar && !right && !bottom && !nickname && !collection && !immOn && !immCustom
        /** 模式码（原 hideByConfig 内 mode 计算）：1=一键沉浸，2=自定义，0=全关 */
        val mode: Int get() = if (immOn) 1 else if (immCustom || anyCustomSub) 2 else 0
    }

    private fun readImmMode(): ImmMode = ImmMode(
        immOn = Prefs.bool(Prefs.K_IMM_ON, false),
        immCustom = Prefs.bool(Prefs.K_IMM_CUSTOM, false),
        topbar = Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false),
        right = Prefs.bool(Prefs.K_IMM_RIGHT_ON, false),
        bottom = Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false),
        nickname = Prefs.bool(Prefs.K_IMM_NICKNAME, false),
        collection = Prefs.bool(Prefs.K_IMM_COLLECTION, false),
        gold = Prefs.bool(Prefs.K_IMM_GOLD, false)
    )

    private val hideTask = object : Runnable {
        override fun run() {
            // ★ 全项目唯一无兜底主线程入口（审阅 2026-09 P1）：hideByConfig 内部任何
            // RuntimeException 会直接崩快手进程——整段 try/catch，重排放 finally
            // 保证轮询永不中断
            var imm: ImmMode? = null
            try {
                val act = tracked
                if (act != null) {
                    // ★ 性能修复（S3/M2）：一次快照读完 8 个开关（原实现本函数内
                    // 2 次 + anyCustomSubOn 6 次 + finally 里 onlyGoldOn 7 次）
                    val m = readImmMode()
                    imm = m
                    if (m.mode != lastMode || vtreeDirty || firstRestore) {
                        vtreeDirty = false
                        hideByConfig(act, m)
                    }
                }
            } catch (t: Throwable) {
                Logger.d("imm hideTask: " + t.javaClass.simpleName + ": " + t.message)
            } finally {
                // 全关时降频轮询；有开关开启才高频跑。
                // 复用同轮快照（为空说明没走到读开关那步，此时补读一次）
                val m = imm ?: readImmMode()
                if (active) handler.postDelayed(this, if (lastMode == 0) 3000 else if (m.onlyGold) 5000 else 2500)
            }
        }
    }

    private fun hideSystemUi(act: Activity) {
        val w = act.window
        if (Build.VERSION.SDK_INT >= 30) {
            w.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
    }

    private var dumpRemain = 0
    @Volatile private var lastMode = 0
    @Volatile private var lastSig = 0
    @Volatile private var firstRestore = true

    private fun anyCustomSubOn() = Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false) || Prefs.bool(Prefs.K_IMM_RIGHT_ON, false)
        || Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false) || Prefs.bool(Prefs.K_IMM_NICKNAME, false)
        || Prefs.bool(Prefs.K_IMM_COLLECTION, false) || Prefs.bool(Prefs.K_IMM_GOLD, false)

    private fun onlyGoldOn() = Prefs.bool(Prefs.K_IMM_GOLD, false)
        && !Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false)
        && !Prefs.bool(Prefs.K_IMM_RIGHT_ON, false)
        && !Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false)
        && !Prefs.bool(Prefs.K_IMM_NICKNAME, false)
        && !Prefs.bool(Prefs.K_IMM_COLLECTION, false)
        && !Prefs.bool(Prefs.K_IMM_ON, false)
        && !Prefs.bool(Prefs.K_IMM_CUSTOM, false)

    private fun hideByConfig(act: Activity, imm: ImmMode = readImmMode()) {
        // GC 压力保护：堆 >90% 时跳过本轮遍历，避免在 GC 期间加重主线程负担
        // （快手稳态堆 234/258MB，GC 每次回收 17-64MB 耗时 128-999ms）
        val rt = Runtime.getRuntime()
        if ((rt.totalMemory() - rt.freeMemory()).toFloat() / rt.maxMemory() > 0.9f) {
            skipGc++
            if (skipGc % 10 == 0) Logger.d("imm skip gc heap=${(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024}MB/${rt.maxMemory() / 1024 / 1024}MB n=$skipGc")
            return
        }
        val decor = act.window.decorView as? ViewGroup ?: return
        // ★ 性能修复（S3/M2）：改用调用方传入的开关快照，本函数不再重复读 Prefs
        val immOn = imm.immOn
        val mode = imm.mode
        if (mode == 0) {
            // 只在从开→关的转换时做一次全树恢复；之后零遍历（卡顿修复：
            // 之前每 1.5s 全树 walkAll 一遍刷日志）
            // firstRestore：新进程首次无条件恢复一次，清理之前进程残留的隐藏标记
            // （如 kcube_tab_strip willNotDraw=true 导致顶栏文字不显示）
            // 定期 restoreStrip：kcube_tab_strip 可能在首次 restore 后才创建，
            // 需持续恢复其 willNotDraw=false，否则顶栏标签文字不显示
            passCount++
            if (firstRestore || lastMode != 0) {
                restoreAll(decor)
                restoreStrip(decor)
                lastMode = 0
                firstRestore = false
                Logger.d("imm off detected, restored")
            } else if (passCount % 5 == 0) {
                restoreStrip(decor)
                Logger.d("imm off periodic restoreStrip pass=$passCount")
            }
            return
        }
        if (mode != lastMode) {
            restoreAll(decor)
            restoreStrip(decor)
            lastMode = mode
            lastSig = 0
            Logger.d("imm mode changed to $mode, restored")
        }
        val hide = Prefs.strSet(Prefs.K_IMM_HIDE)
        val w = decor.width; val h = decor.height
        if (w == 0 || h == 0) return
        if (!hasVideoPlaying(decor)) {
            // 无视频（图片页/黑屏页）也要恢复：开关关闭后残留的隐藏必须在此还原，
            // 否则一旦离开视频页，hideByConfig 永远提前 return，顶栏等隐藏项无法恢复
            if (mode == 2) {
                val s = buildCustomSets()
                if (s.top.isEmpty() && s.right.isEmpty() && s.other.isEmpty()) {
                    restoreAll(decor)
                } else {
                    restoreUnmatched(decor, s.top, s.right, s.other, w, h)
                    if (s.top.isEmpty()) restoreStrip(decor)
                }
            }
            return
        }

        passCount++
        val force = passCount % RESET_INTERVAL == 0


        var c1 = 0
        var c2 = 0
        if (immOn) {
            // ★ 性能修复（审阅 2026-09 · S3）：原先 hideByIds + hideByPosition +
            // hideSelected 各自全树遍历一遍（一轮 pass 最多 3 次 walk）——合并为
            // applyHideRulesSinglePass 的**单次遍历**，语义见该函数注释。
            // 一键沉浸同时应用自定义隐藏子项（topbar/right/other），
            // 单遍实现内部对 immOn=true 不做 restoreUnmatched，避免把一键沉浸
            // hideByIds/hideByPosition 隐藏的 view 恢复成 VISIBLE 导致右侧按钮闪烁。
            c2 = applyHideRulesSinglePass(decor, w, h, force, immOn = true, sets = buildCustomSets())
            Logger.d("imm pass=$passCount force=$force merged=$c2")
        } else {
            if (imm.onlyGold) {
                val actRef = act
                // ★ 性能修复（审阅 2026-09 · S1/M2）：原先每轮 14 个 id 各自调
                // Prefs.bool 之外，还要靠 goldId() 的 synchronized 查表。此处
                // GOLD_FAST_IDS 是常量数组，findViewById 本身是 O(树) 但只在
                // 金币单开时走；保留原逻辑，仅在计数上合并。
                for (gn in GOLD_FAST_IDS) {
                    // ★ 双命名空间兜底：硬编码主包命名空间在极速版（com.kuaishou.nebula）
                    // 上若资源表已随包名改名则恒返回 0，金币快速隐藏整体静默失效。
                    // 先查当前进程包名，未命中再回退主包（主包行为不变，极速版只增不减）
                    val gid = goldId(actRef, gn)
                    if (gid != 0) {
                        val gv = actRef.findViewById(gid) as? View
                        if (gv != null && gv.visibility != View.GONE) { gv.visibility = View.GONE; c2++ }
                    }
                }
            } else {
                val sets = buildCustomSets()
                // ★ 性能修复（审阅 2026-09 · S3）：原先 hideSelected（一次 walk）
                // + hideTopBarIndicator（再一次 walkAll）两次全树遍历——合并为单次。
                c2 = applyHideRulesSinglePass(decor, w, h, force, immOn = false, sets = sets)
                // topbar 集合为空时仍需周期性恢复 kcube_tab_strip 的 willNotDraw
                // （原实现在此分支外单独调 restoreStrip，语义保留）
                if (sets.top.isEmpty()) restoreStrip(decor)
                if (passCount % 20 == 0) Logger.d("imm dbg topbar=${sets.top.size} right=${sets.right.size} other=${sets.other.size} items=${sets.top.joinToString(",")}")
                if (passCount % 20 == 0) Logger.d("imm pass=$passCount force=$force merged=$c2")
            }
            // dumpTopBar 每30轮刷屏拖垮 logcat/CPU，已停用
        }
        // dumpRemaining 诊断已完成使命（每轮50条刷爆 logcat 256KB 缓冲，冲掉其他模块
        // 日志），永久停用；需要时临时恢复此调用
        // if (dumpRemain < 15) { dumpRemain++; dumpRemaining(decor, w, h) }
    }

    private fun clearTags(root: ViewGroup) {
        walkAll(root) { v ->
            if (v.tag === HIDDEN_TAG) {
                v.tag = null
                if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
            } else if (v.tag === STRIP_TAG) {
                v.tag = null
                v.setWillNotDraw(false)
                v.invalidate()
            }
        }
    }

    private fun restoreAll(root: ViewGroup) {
        var n = 0
        walkAll(root) { v ->
            if (v.tag === HIDDEN_TAG) {
                v.tag = null
                if (v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; n++ }
            } else if (v.tag === STRIP_TAG) {
                v.tag = null
                v.setWillNotDraw(false)
                v.invalidate()
                n++
            }
        }
        if (n > 0) Logger.d("imm restored $n views")
    }

    private fun dumpRemaining(root: ViewGroup, w: Int, h: Int) {
        var n = 0
        walk(root) { v ->
            if (n >= 50) return@walk
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            if (v.width == 0 || v.height == 0) return@walk
            val left = v.left; val top = v.top; val right = v.right; val bottom = v.bottom
            val isRight = left > w * 45 / 100
            val isBottom = bottom > h * 65 / 100
            val isLeftMid = left < w * 40 / 100 && top in (h * 10 / 100)..(h * 75 / 100)
            if (!isRight && !isBottom && !isLeftMid) return@walk
            val cn = v.javaClass.simpleName
            val idName = idNameOf(v)
            val b = "[$left,$top][$right,$bottom]"
            val t = if (v is TextView) v.text?.toString()?.take(15) ?: "" else ""
            Logger.d("imm remain $cn $b${if (idName.isNotEmpty()) " id=$idName" else ""}${if (t.isNotEmpty()) " t=$t" else ""}")
            n++
        }
    }

    private fun restoreStrip(root: ViewGroup) {
        // 按 id 无条件恢复：kcube_tab_strip 的 tag 是公共属性，会被快手 CubeUI
        // 代码覆盖，依赖 STRIP_TAG 找回会造成文字永久消失（只剩图标）
        walkAll(root) { v ->
            if (v.id != View.NO_ID) {
                val idName = idNameOf(v)
                if (idName == "kcube_tab_strip") {

                    v.tag = null
                    v.setWillNotDraw(false)
                    v.invalidate()
                    // 恢复子 view visibility：仅恢复带 HIDDEN_TAG（本模块隐藏标记）的
                    // 子项——原实现无条件强制全部子 view VISIBLE，会顶掉快手自己
                    // GONE 掉的角标/占位（审阅 2026-09 P2）
                    if (v is ViewGroup) {
                        var restored = 0
                        for (i in 0 until v.childCount) {
                            val child = v.getChildAt(i) ?: continue
                            if (child.tag === HIDDEN_TAG) {
                                child.tag = null
                                if (child.visibility != View.VISIBLE) { child.visibility = View.VISIBLE; restored++ }
                            }
                            if (child is ViewGroup) {
                                for (j in 0 until child.childCount) {
                                    val gc = child.getChildAt(j) ?: continue
                                    // 同上：孙级也只恢复本模块标记过的
                                    if (gc.tag === HIDDEN_TAG) {
                                        gc.tag = null
                                        if (gc.visibility != View.VISIBLE) { gc.visibility = View.VISIBLE; restored++ }
                                    }
                                }
                            }
                        }
                        if (restored > 0) Logger.d("imm STRIP restored $restored children vis")
                    }
                    Logger.d("imm STRIP restored willNotDraw=false")
                    return@walkAll
                }
            }
            if (v.tag === STRIP_TAG) {
                v.tag = null
                v.setWillNotDraw(false)
                v.invalidate()
            }
        }
    }

    private fun hideTopBarIndicator(root: ViewGroup, w: Int, h: Int): Int {
        var count = 0
        val loc = IntArray(2)
        val debug = passCount % 30 == 0
        if (debug) Logger.d("imm line dbg start")
        walkAll(root) { v ->
            if (v === root) return@walkAll
            if (v.visibility != View.VISIBLE) return@walkAll
            if (v.width == 0 || v.height == 0) return@walkAll
            v.getLocationOnScreen(loc)
            val absY = loc[1]; val absX = loc[0]
            if (absY >= h / 4) return@walkAll
            val idName = idNameOf(v)
            if (idName == "kcube_tab_strip" && v.tag !== STRIP_TAG) {
                v.setWillNotDraw(true)
                v.tag = STRIP_TAG
                v.invalidate()
                count++
                Logger.d("imm STRIP willNotDraw y=$absY x=$absX w=${v.width}")
            }
            val cn = v.javaClass.simpleName
            val isIndicator = idName.contains("indicator", true) || idName.contains("underline", true) || idName.contains("selector_line", true) || idName.contains("tab_line", true) || idName.contains("tab_indicator", true) || cn.contains("Indicator", true)
            // 指示器实际高度常含 padding 超 10px，放宽到 16 并限定顶栏 1/8 区域内的细长条
            val isLine = (v.height in 1..10 && v.width > 20) ||
                (v.height in 1..16 && v.width in 20..(w / 3) && absY < h / 8 && v !is TextView && idName != "kcube_tab_strip")
            if (isIndicator || isLine) {
                v.visibility = View.GONE
                v.tag = HIDDEN_TAG
                count++
                Logger.d("imm LINE hidden y=$absY h=${v.height} w=${v.width} id=$idName cls=$cn")
            }
        }
        if (debug) Logger.d("imm line dbg end")
        return count
    }

    private fun dumpTopBar(root: ViewGroup, w: Int, h: Int) {
        var n = 0
        val loc = IntArray(2)
        Logger.d("imm topbar dump start w=$w h=$h")
        walkAll(root) { v ->
            if (n >= 400) return@walkAll
            if (v === root) return@walkAll
            if (v.width == 0 || v.height == 0) return@walkAll
            v.getLocationOnScreen(loc)
            val absY = loc[1]; val absX = loc[0]
            val t = if (v is TextView) v.text?.toString() ?: "" else ""
            val cd = v.contentDescription?.toString() ?: ""
            val idName = idNameOf(v)
            if (t.isBlank() && cd.isBlank() && idName.isBlank()) return@walkAll
            val ft = fixStr(t); val fcd = fixStr(cd)
            Logger.d("imm tb y=$absY x=$absX vis=${v.visibility} cls=${v.javaClass.simpleName} w=${v.width} h=${v.height}${if (idName.isNotEmpty()) " id=$idName" else ""}${if (ft.isNotEmpty()) " t=$ft" else ""}${if (fcd.isNotEmpty()) " cd=$fcd" else ""}${if (v is ViewGroup) " kids=${v.childCount}" else ""}")
            n++
        }
        Logger.d("imm topbar dump end n=$n")
    }

    private fun hasVideoPlaying(root: View): Boolean {
        if (root.visibility != View.VISIBLE) return false
        if (root is SurfaceView || root is TextureView) return root.width > 100 && root.height > 100
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (hasVideoPlaying(root.getChildAt(i) ?: continue)) return true
            }
        }
        return false
    }

    private val idNameCache = android.util.SparseArray<String>()

    /** id->资源名缓存：每轮全树遍历数百 view 的 getResourceEntryName 是主线程最大开销（缓存后 int 查表） */
    private fun idNameOf(v: View): String {
        val rid = v.id
        if (rid == View.NO_ID) return ""
        val c = idNameCache.get(rid)
        if (c != null) return c
        val n = try { v.resources.getResourceEntryName(rid) } catch (_: Throwable) { "" }
        idNameCache.put(rid, n)
        return n
    }
    private fun hideByIds(root: ViewGroup, force: Boolean): Int {
        var count = 0
        walk(root) { v ->
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            val idName = idNameOf(v)
            for (key in HIDE_IDS) {
                if (idName == key || idName.contains(key, true)) {
                    if (hide(v, force)) count++; break
                }
            }
        }
        return count
    }

    private fun hideByPosition(root: ViewGroup, w: Int, h: Int, force: Boolean): Int {
        var count = 0
        walk(root) { v ->
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            val vw = v.width; val vh = v.height
            if (vw == 0 || vh == 0) return@walk
            val left = v.left; val top = v.top; val right = v.right; val bottom = v.bottom
            val isFloating = vw in 40..250 && vh in 40..250 && (right > w * 60 / 100 && bottom > h * 60 / 100 || left < w * 40 / 100 && bottom > h * 60 / 100)
            val isLeftAuthor = left < w * 35 / 100 && top in (h * 10 / 100)..(h * 70 / 100) && vw in 30..300 && vh in 30..300
            if (isFloating || isLeftAuthor) {
                if (hide(v, force)) count++; return@walk
            }
            if (v !is ViewGroup) return@walk
            if (v.childCount == 0) return@walk
            val isRightCol = left > w * 82 / 100 && vw in 1..(w / 5) && vh in 1..(h * 2 / 3) && bottom > h / 3
            val isTopBar = top in 0..(h / 4) && vh in 1..(h / 3) && vw > w / 2
            val isBottomBar = bottom > h * 3 / 4 && vh in 1..(h / 3) && vw > w / 2
            if (isRightCol || isTopBar || isBottomBar) {
                if (hide(v, force)) count++
            }
        }
        return count
    }

    private fun hideAllUi(root: ViewGroup, w: Int, h: Int, force: Boolean): Int {
        var count = 0
        walk(root) { v ->
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            val vw = v.width; val vh = v.height
            if (vw == 0 || vh == 0) return@walk
            if (vw > w / 2 && vh > h / 2) return@walk
            val text = if (v is TextView) v.text?.toString() else null
            val cd = v.contentDescription?.toString()
            val hasUi = (text != null && text.isNotBlank()) || (cd != null && cd.isNotBlank())
            if (hasUi) {
                if (hide(v, force)) count++
            }
        }
        return count
    }

    private val RIGHT_ID_MAP = linkedMapOf(
        "like_button" to "喜欢",
        "comment_button" to "评论",
        "collect_button" to "收藏",
        "forward_button" to "转发",
        "music_wheel" to "音乐封面",
        "slide_play_right_follow" to "关注",
        "follow_button" to "关注"
    )

    private class MatchResult(val item: String, val isRight: Boolean, val byIdRight: Boolean, val absX: Int, val absY: Int, val isTopArea: Boolean, val isRightArea: Boolean)

    private val NICK_CAPTION_IDS = setOf("username_group", "user_name_text_view", "caption_scroll_container", "element_caption_label", "global_caption_label", "slide_play_caption_root")

    private fun matchHideItem(v: View, topbar: Set<String>, right: Set<String>, other: Set<String>, w: Int, h: Int, loc: IntArray): MatchResult? {
        if (v.width == 0 || v.height == 0) return null
        val text = if (v is TextView) v.text?.toString() else null
        val cd = v.contentDescription?.toString()
        val t = text ?: fixStr(cd ?: "")
        val idName = idNameOf(v)
        v.getLocationOnScreen(loc)
        val absX = loc[0]; val absY = loc[1]
        if (absY >= h || absY + v.height <= 0) return null
        if (absX + v.width <= 0 || absX >= w) return null
        val isTopArea = absY < h / 5
        val isRightArea = absX > w * 2 / 3 && absY in (h / 5)..(h * 95 / 100)
        val isNickOrCaption = idName in NICK_CAPTION_IDS
        if (idName.isNotEmpty()) {
            for ((key, item) in RIGHT_ID_MAP) {
                if (idName == key || idName.contains(key)) {
                    if (item in right) return MatchResult(item, true, true, absX, absY, isTopArea, isRightArea)
                    if (absX > w * 4 / 5) return null
                }
            }
        }
        if (isTopArea) {
            for (item in topbar) {
                if (matchItem(item, v, t, w, h)) return MatchResult(item, false, false, absX, absY, isTopArea, isRightArea)
            }
        }
        if (isRightArea) {
            for (item in right) {
                if (matchItem(item, v, t, w, h)) return MatchResult(item, true, false, absX, absY, isTopArea, isRightArea)
            }
        }
        for (item in other) {
            val isLeftMid = absX < w / 2 && absY in (h / 5)..(h * 95 / 100)
            val isBottom = absY > h * 4 / 5
            if (item == "合集" && (isNickOrCaption || t.length > 20)) continue
            val posOk = when (item) {
                "作者昵称", "视频文案" -> isLeftMid
                "合集" -> absY in (h * 7 / 10)..(h * 95 / 100)
                "底部Tab栏" -> isBottom && absX < w / 4
                else -> true
            }
            if (posOk && matchItem(item, v, t, w, h)) return MatchResult(item, false, false, absX, absY, isTopArea, isRightArea)
        }
        if ("合集" in other && !isNickOrCaption && idName.isEmpty() && v !is TextView && v.width > w / 2 && v.height in 1..10 && absY in (h * 7 / 10)..(h * 98 / 100) && absX < w * 4 / 5) {
            return MatchResult("合集", false, false, absX, absY, isTopArea, isRightArea)
        }
        if ("金币红包" in other && idName.isEmpty() && v !is TextView && v !is SurfaceView && v !is TextureView) {
            val inFloatArea = absY > h / 5 && absY + v.height < h * 9 / 10 && absX > w / 10 && absX + v.width < w * 9 / 10
            val childOk = v !is ViewGroup || v.childCount in 1..4
            val plainSquare = t.isBlank() && v.width in 40..200 && v.height in 40..200 && Math.abs(v.width - v.height) * 3 < v.width
            val pendantShape = v.width in 40..200 && v.height in 40..320 && v.height > v.width
            if (inFloatArea && childOk && (plainSquare || pendantShape)) {
                return MatchResult("金币红包", false, false, absX, absY, isTopArea, isRightArea)
            }
        }
        return null
    }

    private class CustomSets(val top: Set<String>, val right: Set<String>, val other: Set<String>) {
        val any: Boolean get() = top.isNotEmpty() || right.isNotEmpty() || other.isNotEmpty()
    }

    @Volatile private var setsCache: CustomSets? = null
    @Volatile private var setsCacheSig = -1

    /** 结果缓存：hideByConfig 每轮（250ms~1.5s）重建 sets+打日志是重复开销；sig 含开关值与集合 hash，变化才重建 */
    private fun buildCustomSets(): CustomSets {
        val topOn = Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false)
        val rightOn = Prefs.bool(Prefs.K_IMM_RIGHT_ON, false)
        val nickOn = Prefs.bool(Prefs.K_IMM_NICKNAME, false)
        val collOn = Prefs.bool(Prefs.K_IMM_COLLECTION, false)
        val botOn = Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false)
        val goldOn = Prefs.bool(Prefs.K_IMM_GOLD, false)
        val top = if (topOn) Prefs.strSet(Prefs.K_IMM_TOPBAR).toSet() else emptySet()
        val right = if (rightOn) Prefs.strSet(Prefs.K_IMM_RIGHT_ITEMS).toSet() else emptySet()
        val sig = (if (topOn) 1 else 0) + (if (rightOn) 2 else 0) + (if (nickOn) 4 else 0) +
            (if (collOn) 8 else 0) + (if (botOn) 16 else 0) + (if (goldOn) 32 else 0) +
            top.hashCode() * 31 + right.hashCode()
        val c = setsCache
        if (c != null && setsCacheSig == sig) return c
        val other = mutableSetOf<String>()
        if (nickOn) { other.add("作者昵称"); other.add("视频文案") }
        if (collOn) other.add("合集")
        if (botOn) other.add("底部Tab栏")
        if (goldOn) other.add("金币红包")
        val s = CustomSets(top, right, other)
        setsCache = s; setsCacheSig = sig
        Logger.d("imm sets top=$top right=$right other=$other gold=$goldOn")
        return s
    }

    /**
     * 恢复段：把带 HIDDEN_TAG 且已不匹配任何隐藏集合的视图还原（开关关闭后调用）
     */
    private fun restoreUnmatched(root: ViewGroup, topbar: Set<String>, right: Set<String>, other: Set<String>, w: Int, h: Int): Int {
        var restored = 0
        val loc = IntArray(2)
        walkAll(root) { v ->
            if (v === root) return@walkAll
            if (v.tag !== HIDDEN_TAG) return@walkAll
            if (v.visibility == View.VISIBLE) {
                if (v.width > 0 && v.height > 0) {
                    val m = try { matchHideItem(v, topbar, right, other, w, h, loc) } catch (_: Throwable) { null }
                    if (m != null) { v.visibility = View.GONE; return@walkAll }
                }
                v.tag = null; return@walkAll
            }
            // GONE 的 view 宽高为 0，matchHideItem 必返回 null，
            // 若据此恢复会造成"隐藏→恢复→再隐藏"循环闪烁，故尺寸为 0 时保守不恢复
            if (v.width == 0 || v.height == 0) return@walkAll
            try {
                v.getLocationOnScreen(loc)
                if (loc[0] + v.width <= 0 || loc[0] >= w) return@walkAll
            } catch (_: Throwable) { return@walkAll }
            val m = try { matchHideItem(v, topbar, right, other, w, h, loc) } catch (_: Throwable) { null }
            if (m == null) {
                v.visibility = View.VISIBLE
                v.tag = null
                restored++
                Logger.d("imm restore cls=${v.javaClass.simpleName} w=${v.width} h=${v.height}")
            }
        }
        if (restored > 0) Logger.d("imm restored $restored views")
        return restored
    }

    private fun hideSelected(root: ViewGroup, topbar: Set<String>, right: Set<String>, other: Set<String>, w: Int, h: Int, doRestore: Boolean = true): Int {
        var count = 0
        val loc = IntArray(2)
        val goldDebug = "金币红包" in other && passCount % 10 == 0
        if (goldDebug) Logger.d("gold dbg begin")
        if (doRestore) restoreUnmatched(root, topbar, right, other, w, h)
        walk(root) { v ->
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            val m = try { matchHideItem(v, topbar, right, other, w, h, loc) } catch (_: Throwable) { null }
            if (m != null) {
                // 惰性求值：quiet 时零成本（原行 $<IDNAMEOF> 是批量替换事故残留，
                // 会把占位符字面打进日志，改回 idNameOf 并入惰性 lambda）
                Logger.d({ "imm M item=${m.item} cls=${v.javaClass.simpleName} id=${idNameOf(v)} x=${m.absX} y=${m.absY} w=${v.width} h=${v.height}" })
                if (m.byIdRight) {
                    v.visibility = View.GONE; v.tag = HIDDEN_TAG; count++
                } else if (hideTopItem(v, root, w, h, m.isRight || m.isRightArea, m.absY)) {
                    count++
                    if (m.isTopArea) hideIndicatorNear(v, root)
                }
            } else if (goldDebug && v.width in 40..200 && v.height in 40..200) {
                val txt = (v as? TextView)?.text?.toString() ?: ""
                val cdsc = v.contentDescription?.toString() ?: ""
                val idN = idNameOf(v)
                if (txt.isBlank() && cdsc.isBlank()) {
                    v.getLocationOnScreen(loc)
                    Logger.d("gold cand cls=${v.javaClass.simpleName} id=$idN x=${loc[0]} y=${loc[1]} w=${v.width} h=${v.height} kids=${if (v is ViewGroup) v.childCount else 0}")
                }
            }
        }
        return count
    }

    private fun hideTopItem(v: View, root: ViewGroup, w: Int, h: Int, isRight: Boolean = false, absY: Int = 0): Boolean {
        val p = v.parent
        if (absY < h / 5 && p is ViewGroup && p !== root && p.left <= v.left && p.right >= v.right && p.top <= v.top && p.bottom >= v.bottom && p.width < w / 2 && p.height < h / 4) {
            if (p.visibility == View.VISIBLE) {
                p.visibility = View.GONE
                p.tag = HIDDEN_TAG
                if (v.visibility == View.VISIBLE) { v.visibility = View.GONE; v.tag = HIDDEN_TAG }
                return true
            }
            return false
        }
        if (v.visibility == View.VISIBLE && v.width <= w && v.height < h / 2) { v.visibility = View.GONE; v.tag = HIDDEN_TAG; return true }
        return false
    }

    private fun hideIndicatorNear(tab: View, root: ViewGroup) {
        val tabLeft = tab.left; val tabRight = tab.right; val tabBottom = tab.bottom
        val h = root.height
        walkAll(root) { v ->
            if (v === tab) return@walkAll
            if (v.visibility != View.VISIBLE) return@walkAll
            if (v.width == 0 || v.height == 0) return@walkAll
            // 仅细条(≤10px)才算 tab 指示器：isSelected 的大控件是个人页"作品/喜欢"等
            // 选中项（h=51/19），h<80 会误藏个人页顶部 tab
            if (v.isSelected && v.height <= 10 && v.width < root.width / 2) {
                var absY = 0; var p: View? = v
                while (p != null && p !== root) { absY += p.top; p = (p as? View)?.parent as? View }
                if (absY < h / 5) {
                    Logger.d("imm IND sel hidden cls=${v.javaClass.simpleName} absY=$absY left=${v.left} w=${v.width} h=${v.height}")
                    v.visibility = View.GONE
                    v.tag = HIDDEN_TAG
                }
            }
            val near = v.top in (tabBottom - 5)..(tabBottom + 80)
            if (near && v.height <= 30) {
                val overlap = v.left < tabRight && v.right > tabLeft
                if (overlap && v.height <= 10) {
                    Logger.d("imm IND hidden h=${v.height} w=${v.width} top=${v.top} cls=${v.javaClass.simpleName}")
                    hide(v, true)
                }
            }
        }
    }

    private fun isVideoView(v: View): Boolean {
        val cn = v.javaClass.name
        return v is SurfaceView || v is TextureView || cn.contains("VideoView") || (cn.contains("Player") && !cn.contains("Kit"))
    }

    private fun fixStr(s: String): String {
        if (s.isEmpty()) return s
        var hasHi = false
        for (c in s) { if (c.code in 0x80..0xFF) { hasHi = true; break } }
        if (!hasHi) return s
        return try { String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) } catch (_: Throwable) { s }
    }

    private val LEAF_ITEMS = setOf(
        "精选","看游戏","玩游戏","短剧","同城","关注","发现","直播",
        "养萌宠","王者送福利","侧边栏","搜索",
        "点赞","评论","分享","收藏","喜欢","音乐唱片"
    )

    private fun matchItem(item: String, v: View, t: String, w: Int, h: Int): Boolean {
        val cn = v.javaClass.name
        val idName = idNameOf(v)
        if (item in LEAF_ITEMS && v is ViewGroup && v.width > w * 3 / 5) return false
        return when (item) {
            "点赞" -> t.contains("点赞") || t.contains("like", true)
            "评论" -> t.contains("评论") || t.contains("comment", true)
            "分享" -> t.contains("分享") || t.contains("share", true)
            "收藏" -> t.contains("收藏") || t.contains("favorite", true) || t.contains("fav", true)
            "关注" -> t.contains("关注") || t.contains("follow", true) || idName.contains("follow", true)
            "喜欢" -> t.contains("喜欢") || t.contains("like", true) || idName.contains("like", true)
            "作者头像" -> cn.contains("Avatar", true) || t.contains("头像")
            "作者昵称" -> idName == "user_name_text_view" || idName.contains("username_group", true) || idName.contains("verify", true) || idName.contains("disclaimer") || idName.contains("reco_reason") || idName.contains("tube_panel") || idName.contains("tube_first") || idName.contains("tube_second") || idName.contains("tube_third") || t == "短剧"
            "视频文案" -> idName == "element_caption_label" || idName == "global_caption_label" || (t.length > 20 && cn.contains("TextView"))
            "顶部栏" -> v is ViewGroup && v.top in 0..(h / 6) && v.height in 1..(h / 10) && v.width > w / 2 && v.childCount > 1
            "底部Tab栏" -> idName.contains("bottom_bar") || idName.contains("bottom_navigation") || idName.contains("main_tab") || (v !is TextView && v.width > w * 9 / 10 && v.height in 1..(h / 8) && v.bottom > h * 5 / 6)
            "音乐旋转" -> cn.contains("Music", true) || t.contains("音乐")
            "音乐唱片" -> cn.contains("Music", true) || t.contains("音乐") || idName.contains("music", true)
            "音乐封面" -> cn.contains("Music", true) || t.contains("音乐") || idName.contains("music", true) || idName.contains("album", true)
            "转发" -> t.contains("转发") || t.contains("forward", true) || idName.contains("forward", true) || idName.contains("share", true)
            "倒计时" -> t.contains("倒计时") || cn.contains("Timer", true)
            "进度条" -> cn.contains("SeekBar", true) || cn.contains("ProgressBar", true)
            "倍速" -> t.contains("倍速") || t.contains("speed", true)
            "清晰度" -> t.contains("清晰度") || t.contains("quality", true) || t.contains("画质")
            "搜索" -> t.contains("搜索") || t.contains("search", true) || idName.contains("search", true)
            "侧边栏" -> t.contains("侧边栏") || idName.contains("sidebar", true) || idName.contains("drawer", true)
            // avatar/head 类 id 需限定小尺寸(宽<屏宽1/6)：首页顶栏头像是小图标(~51px)，
            // 个人页账号头像是大图(~112-120px)，不限尺寸会误藏个人页账号信息
            "左上角按钮" -> idName.contains("sidebar", true) || idName.contains("drawer", true) || (v.width < w / 4 && idName.contains("menu", true)) || (v.width < w / 6 && (idName.contains("avatar", true) || idName.contains("head", true))) || (t.contains("侧边栏"))
            "游戏" -> t.contains("看游戏") || t.contains("玩游戏") || t == "游戏" || t == "游戏TV"
            "看游戏" -> t.contains("看游戏") || t == "游戏" || t == "游戏TV"
            "玩游戏" -> t.contains("玩游戏") || t == "游戏" || t == "游戏TV"
            "短剧" -> t.contains("短剧") || idName.contains("drama", true)
            "同城" -> t.contains("同城") || idName.contains("local", true) || idName.contains("city", true)
            "直播" -> t.contains("直播") || t.contains("live", true) || idName.contains("live", true)
            "发现" -> t.contains("发现") || t.contains("discover", true) || idName.contains("discover", true)
            "精选" -> t.contains("精选") || t.contains("feature", true) || idName.contains("feature", true) || idName.contains("select", true)
            "精选指示器" -> idName.contains("indicator", true) || idName.contains("underline", true) || idName.contains("selector_line", true)
            "养萌宠" -> t.contains("养萌宠") || t.contains("萌宠") || t.contains("pet", true)
            "王者送福利" -> t.contains("王者送福利") || t.contains("王者") || t.contains("福利")
            "合集" -> t.contains("合集") || t.contains("上一集") || t.contains("下一集") || t.contains("看全集") || t.contains("热榜") || t.contains("高光") || t.contains("完整版") || t.contains("文娱榜") || t.contains("万人在看") || t.contains("播放量") || (t.contains("全") && t.contains("集")) || idName.contains("collection", true) || idName.contains("feed_set", true) || idName.contains("chapter", true) || idName.contains("serial", true) || idName.contains("tube_panel", true) || idName.contains("tube_first", true) || idName.contains("tube_second", true) || idName.contains("tube_third", true) || idName.contains("tube_tk_action", true) || idName.contains("general_entry", true) || idName.contains("group_bottom_root", true) || idName == "bottom_shadow"
            "金币红包" -> t.contains("金币") || t.contains("红包") || idName.contains("gold", true) || idName.contains("coin", true) || idName.contains("red_packet", true) || idName.contains("pendant", true) || idName.contains("suspend", true) || idName.contains("float_task", true) || idName.contains("hang_widget", true) || idName.contains("treasure", true) || idName.contains("placeholder_lottie", true) || idName.contains("lottie_anim", true)
            else -> t.contains(item)
        }
    }

    private fun dumpUi(act: Activity) {
        try {
            val decor = act.window.decorView as? ViewGroup ?: return
            val w = decor.width; val h = decor.height
            Logger.d("imm dump start w=$w h=$h pkg=${act.javaClass.simpleName}")
            walk(decor, 0) { v, d ->
                if (v.visibility != View.VISIBLE) return@walk
                val cn = v.javaClass.simpleName
                val idName = idNameOf(v)
                val t = if (v is TextView) v.text?.toString() ?: "" else ""
                val cd = v.contentDescription?.toString() ?: ""
                val b = "[${v.left},${v.top}][${v.right},${v.bottom}]"
                val info = buildString {
                    append("d=$d ").append(cn).append(" ").append(b)
                    if (idName.isNotEmpty()) append(" id=").append(idName)
                    if (t.isNotEmpty()) append(" t=").append(t.take(20))
                    if (cd.isNotEmpty()) append(" cd=").append(cd.take(20))
                    if (v is ViewGroup) append(" children=").append(v.childCount)
                }
                Logger.d("imm $info")
            }
            Logger.d("imm dump end")
            // ★ 精选页结构探测（2026-09-23）：用户报「精选页一条条刷」时出现
            //   「文案不变、画面在变」。精选页的 Fragment
            //   （HomeFeaturedMilanoContainerFragment）全项目零处理，
            //   模块不知道「当前在屏是哪条」。
            //
            //   这里借 dumpUi 已有的**一次全树遍历**顺带探测精选页容器 ——
            //   复用遍历、零额外成本（dumpUi 本身只在页面切换时跑）。
            //   只读，不做任何修改。
            try { FeaturedProbe.dumpFeaturedViewTree() } catch (_: Throwable) {}
        } catch (e: Throwable) {
            Logger.d("imm dump err: ${e.message}")
        }
    }

    private fun walk(v: View, cb: (View) -> Unit) {
        cb(v)
        if (isVideoView(v)) return
        if (v.tag === HIDDEN_TAG && v.visibility == View.VISIBLE) v.visibility = View.GONE
        if (io.github.angbang852.manjiao.ui.MainMenuDialog.isOverlay(v)) return
        if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, cb) }
    }

    private fun walkAll(v: View, cb: (View) -> Unit) {
        cb(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walkAll(it, cb) }
    }

    private fun walk(v: View, depth: Int, cb: (View, Int) -> Unit) {
        cb(v, depth)
        if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, depth + 1, cb) }
    }

    private fun hide(v: View, force: Boolean): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (!force && v.tag !== HIDDEN_TAG) return false
        if (hasVideoChild(v, 0)) return false
        v.visibility = View.INVISIBLE
        v.tag = HIDDEN_TAG
        return true
    }

    private fun hasVideoChild(v: View, depth: Int): Boolean {
        if (depth >= 4) return false
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i) ?: continue
                if (isVideoView(c)) return true
                if (c is ViewGroup && hasVideoChild(c, depth + 1)) return true
            }
        }
        return false
    }

    // ==================== 单遍遍历（性能修复 2026-09 · S3） ====================

    /**
     * **一次全树遍历**完成 ids / position / selected 三套隐藏规则 + 顶栏指示器。
     *
     * 原实现：`hideByIds` + `hideByPosition` + `hideSelected`（其一键沉浸分支）
     * 各自独立 `walk(root)` 全树遍历一遍，一轮 pass 最多 3 次全树 + 若干次
     * `hideIndicatorNear` 的 `walkAll`。快手 decor 树数千 View，
     * 每 2.5 秒 × 3~4 次遍历是沉浸模式下的常驻主线程开销。
     *
     * 本函数把三套规则合并到**同一次遍历**里逐 View 依次尝试，语义与原实现一致：
     * - 三套规则都只对「VISIBLE + 非视频 + 有尺寸」的 View 生效（同一个前置过滤）
     * - 任一套命中即停（原实现里各规则各自 `return@walk`，但它们是独立的 walk；
     *   合并后用 `matched` 标志保证同一 View 不被多套规则重复处理，
     *   与原实现「先 ids 后 position（各自可能命中同一个 View）」相比**更保守**
     *   —— 不会出现两条规则对同一 View 重复置 GONE 的情况）
     * - `getLocationOnScreen` 每 View 至多算一次并缓存复用（原实现各规则各算一次）
     *
     * @param immOn 一键沉浸开启：跑 ids + position + selected
     * @param sets  自定义隐藏集合（immOn 为假时只用它跑 selected）
     * @return 隐藏计数
     */
    private fun applyHideRulesSinglePass(
        decor: ViewGroup, w: Int, h: Int, force: Boolean,
        immOn: Boolean, sets: CustomSets
    ): Int {
        var count = 0
        val loc = IntArray(2)
        val topbar = sets.top
        val right = sets.right
        val other = sets.other
        val useSelected = !immOn || topbar.isNotEmpty() || right.isNotEmpty() || other.isNotEmpty()
        // 一键沉浸下的 selected 不做 restore（避免把 ids/position 藏的项恢复成 VISIBLE）
        if (!immOn) restoreUnmatched(decor, topbar, right, other, w, h)
        val needIndicator = topbar.isNotEmpty()

        walk(decor, 0) { v, _ ->
            if (v === decor) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            val vw = v.width
            val vh = v.height

            var matched = false

            // ---- 规则 1：id 名单（原 hideByIds）----
            if (immOn) {
                val idName = idNameOf(v)
                for (key in HIDE_IDS) {
                    if (idName == key || idName.contains(key, true)) {
                        if (hide(v, force)) count++
                        matched = true
                        break
                    }
                }
            }

            // ---- 规则 2：几何位置（原 hideByPosition）----
            if (!matched && immOn && vw != 0 && vh != 0) {
                val left = v.left; val top = v.top; val right0 = v.right; val bottom = v.bottom
                val isFloating = vw in 40..250 && vh in 40..250 &&
                    (right0 > w * 60 / 100 && bottom > h * 60 / 100 || left < w * 40 / 100 && bottom > h * 60 / 100)
                val isLeftAuthor = left < w * 35 / 100 && top in (h * 10 / 100)..(h * 70 / 100) && vw in 30..300 && vh in 30..300
                if (isFloating || isLeftAuthor) {
                    if (hide(v, force)) count++
                    matched = true
                } else if (v is ViewGroup && v.childCount != 0) {
                    val isRightCol = left > w * 82 / 100 && vw in 1..(w / 5) && vh in 1..(h * 2 / 3) && bottom > h / 3
                    val isTopBar = top in 0..(h / 4) && vh in 1..(h / 3) && vw > w / 2
                    val isBottomBar = bottom > h * 3 / 4 && vh in 1..(h / 3) && vw > w / 2
                    if (isRightCol || isTopBar || isBottomBar) {
                        if (hide(v, force)) count++
                        matched = true
                    }
                }
            }

            // ---- 规则 3：自定义隐藏项（原 hideSelected 主体）----
            if (!matched && useSelected) {
                val m = try {
                    // loc 每 View 只算一次：matchHideItem 内部会调 getLocationOnScreen，
                    // 这里传入复用缓冲区（该函数签名已支持 loc 参数）
                    matchHideItem(v, topbar, right, other, w, h, loc)
                } catch (_: Throwable) { null }
                if (m != null) {
                    // 惰性求值：quiet 时零成本
                    Logger.d({ "imm M item=${m.item} cls=${v.javaClass.simpleName} id=${idNameOf(v)} x=${m.absX} y=${m.absY} w=${v.width} h=${v.height}" })
                    if (m.byIdRight) {
                        v.visibility = View.GONE; v.tag = HIDDEN_TAG; count++
                    } else if (hideTopItem(v, decor, w, h, m.isRight || m.isRightArea, m.absY)) {
                        count++
                        if (m.isTopArea) hideIndicatorNear(v, decor)
                    }
                    matched = true
                }
            }

            // ---- 顶栏指示器细条（原 hideTopBarIndicator 主体，可与上面共存）----
            if (needIndicator && !matched && vw != 0 && vh != 0) {
                v.getLocationOnScreen(loc)
                val absY = loc[1]
                if (absY < h / 4) {
                    val idName = idNameOf(v)
                    if (idName == "kcube_tab_strip" && v.tag !== STRIP_TAG) {
                        v.setWillNotDraw(true)
                        v.tag = STRIP_TAG
                        v.invalidate()
                        count++
                    }
                    val cn = v.javaClass.simpleName
                    val isIndicator = idName.contains("indicator", true) || idName.contains("underline", true) ||
                        idName.contains("selector_line", true) || idName.contains("tab_line", true) ||
                        idName.contains("tab_indicator", true) || cn.contains("Indicator", true)
                    val isLine = (vh in 1..10 && vw > 20) ||
                        (vh in 1..16 && vw in 20..(w / 3) && absY < h / 8 && v !is TextView && idName != "kcube_tab_strip")
                    if (isIndicator || isLine) {
                        v.visibility = View.GONE
                        v.tag = HIDDEN_TAG
                        count++
                        Logger.d("imm LINE hidden y=$absY h=${v.height} w=${v.width} id=$idName cls=$cn")
                    }
                }
            }
        }
        return count
    }
}
