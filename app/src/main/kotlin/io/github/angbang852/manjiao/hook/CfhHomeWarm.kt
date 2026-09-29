package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.angbang852.manjiao.util.Logger

/**
 * ★★★ v13.83 方案 C：**首页静默预热**（用户批准：自动拉，不用手动滑首页）
 *
 * ## 为什么需要它（真机实测的因果链，2026-09-29）
 *
 * ```
 * 池里唯一的干净货源 = 首页被真实渲染（PV2-POOL 在 bind 出口入池）
 *          ↑
 * 而首页那个 Fragment **只有切到首页 tab 才会被创建**
 *          ↑
 * 所以冷启动直接停在精选页时：池=0 → 拉不到干净货源 → 断流
 * ```
 * 实证：冷启动只停精选页 ⇒ `[HOMEPULL] ★主页引用未就绪（homeDsRef/homeVmRef 均 null）`
 * 连刷、`PV2-POOL 0`；而手点一次首页后立刻 `[HOMEPULL] 主页 VM 分页 池=1 → 池=13`。
 *
 * ## 做法
 * 模块**自己**把主 pager 切到第 0 页（首页）→ 快手自然创建首页 Fragment 并加载
 * → `PV2-POOL` 把干净条目灌进池 → 再切回用户原来那一页。用户最多看到界面闪一下首页。
 *
 * ## ★★★ 线程纪律（v13.83 **血的教训**，务必遵守）
 *
 * v13.82 我把 `setCurrentItem()` 放在**后台线程**调，真机直接崩溃：
 * ```
 * 09-29 02:57:24.906 java.lang.NullPointerException:
 *   'int android.view.View.getVisibility()' on a null object reference
 *     at android.widget.FrameLayout.layoutChildren(FrameLayout.java:297)
 *     at androidx.viewpager.widget.ViewPager.onLayout(SourceFile:35)
 *     at android.view.ViewRootImpl.performTraversals(ViewRootImpl.java:5291)
 * ```
 * 崩溃时间与「预热结束」只差 48ms ⇒ **确证是它**。
 * 机理：`ViewPager.setCurrentItem` 会 add/remove 子 View（populate），
 * 而主线程此刻正在 `performTraversals → layout` 遍历子 View ⇒ 并发改/读子 View 列表
 * ⇒ `layoutChildren` 拿到 null 子项 ⇒ NPE。
 *
 * 因此本文件**所有 View 访问与 setCurrentItem 一律在主线程**，
 * 状态推进用 `postDelayed`（不阻塞主线程），后台线程不再碰任何 View。
 *
 * ## 不适用条件
 * · 用户已停在首页（3 秒内有过首页频道）时不预热；
 * · 池 ≥ [LOW_WATER] 或距上次预热不足 [COOLDOWN_MS] 时不预热；
 * · 找不到主 pager **且** 找不到底栏 tab 时直接放弃（只落日志）。
 */
object CfhHomeWarm {

    /** 总开关（出问题可一键关闭，不影响别的补货逻辑） */
    const val ENABLED = true

    /** 池低于这个数才值得预热 */
    private const val LOW_WATER = 15

    /** 两次预热的最小间隔：池见底是常态，不能无限刷屏 */
    private const val COOLDOWN_MS = 120_000L

    /**
     * 停在首页的时长。
     *
     * ★ v13.89 由 8000ms 压到 2000ms（用户要「近乎无感」）。
     *   真机实测：切页后 **1.25 秒** `PV2-POOL` 就已经在入池了
     *   （切页 1790623761994 → 首条入池 1790623763245）⇒ 8 秒纯属浪费，
     *   而停留越久用户越会注意到「闪到首页」。
     *   2 秒若不够（池没涨），`stepTail()` 会自己再等一拍，最多补一次。
     */
    private const val DWELL_MS = 2000L

    /** 2 秒不够时最多再补等一拍（防止把「几乎无感」做成「什么都没拉到」） */
    private const val EXTRA_WAIT_MS = 2500L

    /**
     * ★ v13.90 收尾前的等待，2000ms → 1500ms。
     *   这一段是**用户可见**的（还停在首页），能短则短；
     *   真正的补量靠上面的 `triggerHomeLoad(0L)`，不靠干等。
     */
    private const val TAIL_MS = 1500L

    @Volatile private var running = false
    @Volatile private var lastMs = 0L

    /** 观测：预热尝试次数 / 成功拿到货的次数 */
    @Volatile var tryCount = 0
    @Volatile var winCount = 0

    private val main = Handler(Looper.getMainLooper())

    // 跨步骤传递（全部只在主线程读写）
    private var pagerRef: View? = null
    private var barRef: ViewGroup? = null
    private var curRef = -1

    /** 本次预热**是否真的把页面切走了**（决定收尾要不要切回；离屏预热全程不切页） */
    private var switched = false

    /** 本次是否试过离屏（失败后要把「离屏没用」记下来） */
    private var offscreenTried = false

    /**
     * ★★★ v13.90 **离屏预热在快手上实测无效** —— 记下来，本进程内不再尝试。
     *
     * 两次真机实测都是 0 出货：
     * ```
     * ★离屏预热无货（池=0）⇒ 退回真切页      （第一轮）
     * ★离屏预热无货（池=0）⇒ 退回真切页      （第二轮）
     * ```
     * 即 `setOffscreenPageLimit(1)` 虽让相邻页被实例化，**但快手不为不可见页加载数据**
     * （它靠可见性/`setUserVisibleHint` 判后才拉）。所以这条路走不通，
     * 每次白等 2 秒。记住结论 → 后续直接走真切页。
     *
     * ⚠️ 初值直接给 **true**（已知无效，不再试）——
     *   靠「运行时学到的标记」没用：进程一重启标记就没了，而**冷启动正是主场景**，
     *   等于每次都还要白等那 2 秒。想重新验证离屏是否可行，把它改成 false 即可。
     */
    @Volatile private var offscreenUseless = true
    private var pool0Ref = 0
    private var t0Ref = 0L

    /**
     * 可能的话，预热一次。**本身不碰任何 View**，只做闸门判断后把活交给主线程。
     *
     * @param poolSize 当前池大小
     * @param reason   触发来源（只进日志，便于归因）
     */
    fun maybeWarm(poolSize: Int, reason: String) {
        if (!ENABLED) return
        // ★★★ v13.92 受「内容过滤 → 首页内容池接精选页」开关管。
        //   用户 2026-09-29 指正：新加的注入路与自动预热只受编译期常量
        //   （RESPFILL/RENDERFILL/ENABLED）控制，**不读 `flt_homerefill`**
        //   ⇒ 开关拨到「关」时旧链路停了、新链路照跑，开关名副其实不了。
        //   现在三处（RESPFILL / RENDERFILL / 这里）都接上，语义才完整：
        //   关 = 首页池彻底不接精选页，精选页只走自己的白名单。
        // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：
        //   false ⇒ maybeWarm 直接返回，冷启动自动预热不再投递（与 RESPFILL/RENDERFILL 三处口径统一）。
        if (!io.github.angbang852.manjiao.data.Prefs.bool(
                io.github.angbang852.manjiao.data.Prefs.K_FLT_HOMEREFILL, false
            )
        ) return
        if (running) return
        if (poolSize >= LOW_WATER) return
        val now = System.currentTimeMillis()
        if (now - lastMs < COOLDOWN_MS) return
        // 用户此刻就在首页 ⇒ 快手自己在加载，不需要我们插手
        if (CfhState.lastChannelWasHome || now - CfhState.lastHomeChannelMs < 3000L) return
        val act = CfhState.tracked ?: return
        running = true
        lastMs = now
        tryCount++
        try {
            main.post { stepSwitch(act, poolSize, reason) }
        } catch (t: Throwable) {
            running = false
            Logger.evidence("HOMEWARM", "★★预热投递失败 ${t.javaClass.simpleName} ${t.message}")
        }
    }

    /**
     * 预热真实状态（"模块状态" 页用，2026-09-29 用户要求显示真值）。
     * 读的是**运行时计数器**，不是配置项 —— 打开弹窗看到的就是此刻真相。
     */
    fun statsText(): String {
        val n = try { poolSizeNow() } catch (_: Throwable) { -1 }
        return "启用=" + ENABLED +
            " · 累计尝试=$tryCount 有货=$winCount" +
            " · 当前池=" + if (n >= 0) "$n 条" else "?"
    }

    private fun poolSizeNow(): Int = synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }

    private fun fail(t: Throwable) {
        running = false
        pagerRef = null
        barRef = null
        Logger.evidence("HOMEWARM", "★★预热异常 ${t.javaClass.simpleName} ${t.message}")
    }

    // ---------- 步骤①：切到首页（主线程） ----------
    private fun stepSwitch(act: Activity, pool0: Int, reason: String) {
        try {
            val root: View = act.window?.decorView ?: run {
                running = false
                Logger.evidence("HOMEWARM", "★预热放弃：decorView 为空")
                return
            }
            pagerRef = findMainPager(root)
            curRef = pagerRef?.let { getCurrent(it) } ?: -1
            pool0Ref = pool0
            t0Ref = SystemClock.elapsedRealtime()
            Logger.evidence(
                "HOMEWARM",
                "★预热开始 reason=$reason 池=$pool0 " +
                    "pager=${pagerRef?.javaClass?.simpleName ?: "无"} 当前页=$curRef"
            )

            // ★★★ v13.88 ①-a0 **零感知路径（首选）**：`setOffscreenPageLimit(1)`
            //
            //   原理：快手主 pager 的相邻页会被**实例化并 layout**（只是画在屏幕外）。
            //   你在精选页（第 1 页）时，第 0 页（首页）就会被创建：
            //   Fragment 建出来 → 快手自己加载首页数据 → `PV2-POOL` 灌池，
            //   **而你的屏幕全程停在精选页，看不出任何变化** —— 这正是用户要的
            //   「完全没有任何感知地拉首页数据」。
            //
            //   ⚠️ 必须主线程调用（它会触发 populate，改子 View）。
            //   ⚠️ 不保证一定有效：快手若靠 setUserVisibleHint 判可见才加载，
            //      离屏页可能不触发 ⇒ `stepTail()` 里检测到没出货会自动退回真切页。
            if (!offscreenUseless && pagerRef != null && curRef > 0 && setOffscreenLimit(pagerRef!!, 1)) {
                offscreenTried = true
                main.postDelayed({ stepTail() }, DWELL_MS)
                return
            }

            // ①-a 主路径（回退档）：pager 真切页
            if (pagerRef != null && curRef > 0 && setItem(pagerRef!!, 0)) {
                switched = true
                main.postDelayed({ stepTail() }, DWELL_MS)
                return
            }
            // ①-b 兜底：底部 tab 栏合成触摸（找不到 pager 时）
            barRef = findTabBar(root)
            val bar = barRef
            if (bar != null && bar.childCount >= 2) {
                Logger.evidence("HOMEWARM", "★走兜底触摸路 底栏=${bar.javaClass.simpleName} 子项=${bar.childCount}")
                tapView(root, bar.getChildAt(0))
                switched = true
                main.postDelayed({ stepTail() }, DWELL_MS)
                return
            }
            running = false
            Logger.evidence("HOMEWARM", "★预热放弃：既没有可用 pager 也没找到底栏（池=$pool0 当前页=$curRef）")
        } catch (t: Throwable) {
            fail(t)
        }
    }

    // ---------- 步骤②：催快手多拉一批（主线程） ----------
    private fun stepTail() {
        try {
            val p1 = poolSizeNow()
            // ★★ v13.88 离屏预热**没出货** ⇒ 自动退回「真切页」（已验证可用的老路）。
            //   判定标准就是最直接的：池涨了没有。涨了说明离屏页真的被加载了。
            if (!switched && p1 <= pool0Ref && pagerRef != null && curRef > 0) {
                if (offscreenTried) offscreenUseless = true
                Logger.evidence("HOMEWARM", "★离屏预热无货（池=$p1）⇒ 退回真切页（本进程不再试离屏）")
                switched = setItem(pagerRef!!, 0)
                if (switched) {
                    main.postDelayed({ stepTail() }, DWELL_MS)
                    return
                }
            }
            // ★ v13.90 取消「补等一拍」（原 EXTRA_WAIT_MS）：紧接着就调
            //   `triggerHomeLoad(0L)` 催快手自己再拉一批 —— 那本身就是等待，
            //   再叠 2.5 秒只是**白白延长用户看见首页的时间**，与「无感」相悖。
            try { CfhSupply.triggerHomeLoad(0L) } catch (_: Throwable) {}
            main.postDelayed({ stepBack() }, TAIL_MS)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    // ---------- 步骤③：切回原页并收尾（主线程） ----------
    private fun stepBack() {
        try {
            val p1 = poolSizeNow()
            var back = false
            val pager = pagerRef
            // ★ v13.88 只有**真的切走过**才需要切回；离屏预热全程没切页 ⇒ 无需回切，
            //   用户从头到尾看到的都是同一页（零感知）。
            if (switched && pager != null && curRef > 0) back = setItem(pager, curRef)
            if (switched && !back) {
                val bar = barRef
                val act = CfhState.tracked
                if (bar != null && act != null && curRef in 1 until bar.childCount) {
                    try {
                        val root = act.window?.decorView
                        if (root != null) { tapView(root, bar.getChildAt(curRef)); back = true }
                    } catch (_: Throwable) {}
                }
            }
            val p2 = poolSizeNow()
            val gained = p2 - pool0Ref
            if (gained > 0) winCount++
            Logger.evidence(
                "HOMEWARM",
                "★预热结束 ${SystemClock.elapsedRealtime() - t0Ref}ms 池 $pool0Ref→$p1→$p2 (+$gained) " +
                    "模式=${if (switched) "切页" else "离屏"} 回页=$back 累计 尝试=$tryCount 有货=$winCount"
            )
        } catch (t: Throwable) {
            fail(t)
        } finally {
            running = false
            pagerRef = null
            barRef = null
            switched = false
            offscreenTried = false
        }
    }

    /**
     * 设离屏页数（反射，**必须主线程**）。让「当前页的相邻页」被实例化并 layout，
     * 从而在**屏幕外**把首页 Fragment 建出来。
     */
    private fun setOffscreenLimit(pager: View, limit: Int): Boolean = try {
        pager.javaClass.getMethod("setOffscreenPageLimit", Int::class.javaPrimitiveType).invoke(pager, limit)
        true
    } catch (_: Throwable) { false }

    private fun getCurrent(pager: View): Int = try {
        (pager.javaClass.getMethod("getCurrentItem").invoke(pager) as? Int) ?: -1
    } catch (_: Throwable) { -1 }

    /** 反射 setCurrentItem(int, boolean)；ViewPager 与 ViewPager2 都有这个方法。**必须主线程调** */
    private fun setItem(pager: View, index: Int): Boolean = try {
        pager.javaClass.getMethod(
            "setCurrentItem", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        ).invoke(pager, index, false)
        true
    } catch (_: Throwable) {
        try {
            pager.javaClass.getMethod("setCurrentItem", Int::class.javaPrimitiveType).invoke(pager, index)
            true
        } catch (_: Throwable) { false }
    }

    /**
     * 找主 pager：BFS 取**最外层**那个「类名含 ViewPager 且子项 ≥3」的 ViewGroup。
     * 只认类名、不写类型引用，避免不同档位/混淆差异导致编译期失配。
     */
    private fun findMainPager(root: View): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var steps = 0
        while (queue.isNotEmpty() && steps < 400) {
            val v = queue.removeFirst()
            steps++
            if (v is ViewGroup && v.childCount >= 3 && v.javaClass.name.contains("ViewPager")) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    try { v.getChildAt(i)?.let { queue.add(it) } } catch (_: Throwable) {}
                }
            }
        }
        return null
    }

    /**
     * 找底部 tab 栏：屏幕下方 25% 内、子项 3~8 个、且子项宽度相近的那个 ViewGroup。
     *
     * 真机实测（SM_S9180 1440×3200）：底栏 5 项均分，首页在 x≈146（第 0 项）、
     * 精选在 x≈432（第 1 项）—— 与 adb tap 坐标吻合，故按「第 0 项 = 首页」取。
     */
    private fun findTabBar(root: View): ViewGroup? {
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val h = root.height
        val bottomLine = rootLoc[1] + h - (h * 25 / 100)
        val queue = ArrayDeque<View>()
        queue.add(root)
        var steps = 0
        while (queue.isNotEmpty() && steps < 600) {
            val v = queue.removeFirst()
            steps++
            if (v is ViewGroup && v.childCount in 3..8) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (loc[1] >= bottomLine && v.height > 0) {
                    val w0 = v.getChildAt(0)?.width ?: 0
                    if (w0 > 0) {
                        var same = true
                        for (k in 0 until v.childCount) {
                            val cw = try { v.getChildAt(k)?.width ?: 0 } catch (_: Throwable) { 0 }
                            if (cw <= 0 || Math.abs(cw - w0) > 8) { same = false; break }
                        }
                        if (same) return v
                    }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    try { v.getChildAt(i)?.let { queue.add(it) } } catch (_: Throwable) {}
                }
            }
        }
        return null
    }

    /** 在 [target] 中心合成一次 DOWN/UP —— 与用户手指点击走同一条事件链路。**必须主线程调** */
    private fun tapView(root: View, target: View?) {
        if (target == null || target.width <= 0 || target.height <= 0) return
        val rootLoc = IntArray(2)
        val tLoc = IntArray(2)
        try {
            root.getLocationOnScreen(rootLoc)
            target.getLocationOnScreen(tLoc)
        } catch (_: Throwable) { return }
        val x = (tLoc[0] - rootLoc[0] + target.width / 2).toFloat()
        val y = (tLoc[1] - rootLoc[1] + target.height / 2).toFloat()
        val down = SystemClock.uptimeMillis()
        try {
            root.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
            root.dispatchTouchEvent(
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            )
        } catch (_: Throwable) {}
    }
}
