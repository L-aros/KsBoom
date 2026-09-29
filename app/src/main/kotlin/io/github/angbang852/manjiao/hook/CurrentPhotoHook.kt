package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * 精选页「当前条」跟踪 —— **位置服务 + 按下标取条目**（2026-09-24 **真机验证通过**）。
 *
 * ## 最终结论（真机双向对账，可复现）
 *
 * 用户念屏幕文案「喜爱度激励计划」，模块同一时刻跟踪到
 * `photoId=5223612824324334466`、其文案 `#喜爱度激励计划` —— **一致**。
 * 前后相邻条也各对各（阿姨没核桃 → 喜爱度激励计划 → 老师我真的做到了）。
 * 「文案不变、画面在变」由此修复。
 *
 * ## 正确路径（三步，缺一不可）
 *
 * ```
 * ① SlidePlayViewModel.h0()        → 列表内当前下标      ← 用 h0，不是 I！
 * ② SlidePlayViewModel.<按下标方法>(idx) → QPhoto          ← 按特征找，不锁方法名
 * ③ FeaturedTrack.publishObject(qp, photoId)             → 发布为「当前条」
 * ```
 *
 * ### ① 为什么是 `h0()` 而不是 `I()`
 *
 * 两者都调 `position_service`，但取的是**不同语义**的值
 * （`I→eVar.a()`、`h0→eVar.b()`）。实测同一时刻对照：
 * ```
 * 列表=10    I=12    h0=3
 * ```
 * `I()` 是**滑动累计位置**，会超出列表长度（12 > 10）→ 按下标取必然全返回 null；
 * `h0()` 才是**列表内下标**（3 < 10）。用错 `I()` 是「文案对不上」的直接原因：
 * 取不到条目就只能退回不随滑动更新的 `getCurrentPhoto()`。
 *
 * ### ② 为什么「按特征找」而不是按方法名
 *
 * **类名相同、方法名不同**：逆向件（14.8.30）叫 `K(int)`，
 * 实机（14.8.20）叫 `T(int)`。锁死 `"K"` 的结果是全程静默失效。
 * 故按「返回 QPhoto + 恰好 1 个 int 参数」这一**语义特征**匹配 ——
 * 方法名会被混淆改掉，但这个签名语义改不掉。
 * 实机自报候选：`T | U | U3 | c0 | x4`（选中后**锁定**首个成功者，
 * 避免多候选指向不同列表导致 photoId 横跳）。
 *
 * ### ③ 为什么必须「主动拉」而不是挂方法钩
 *
 * `getCurrentPhoto()` 是**查询接口，不是事件源** —— 快手只在页面初始化时
 * 调它一次，滑动时不调。所以挂钩子收不到通知（实测切 1 次后就不动了）。
 * 必须由模块主动轮询（800ms + 翻页时即时触发）。
 *
 * ## 踩过的五个坑（全部有真机证据，勿重犯）
 *
 * | # | 错误做法 | 实测症状 |
 * |---|---|---|
 * | 1 | 读精选页列表字段 | 元素是 `WeakReference`、实体类混淆名、Fragment 不触发 `onResume` → 滞后 7.5 分钟 |
 * | 2 | 自行「登记→反查」 | 登记点选在 `shouldFilterFeed` 末尾，多个早退绕过 → 登记不执行 |
 * | 3 | 固定间隔采样（每 30 次） | 时序脆弱，采样全落在「已登记但没轮到」窗口外 → id 取到了却不切条 |
 * | 4 | 弱引用存映射 | 登记 101 条、表里只剩 4 条（被 GC 回收）→ 刷几条只切中 1 次 |
 * | 5 | 多源同时发布 | 播放回调 + `getCurrentPhoto` 钩子 + 轮询三方打架 → photoId 每 100~200ms 横跳 |
 *
 * **共性**：前 4 条都是「在外围重建快手已经维护好的事实」。
 * 第 5 条是「权威源不唯一」—— 现在**发布源只有一个**（本类的轮询），
 * 其余一律退化为**只观察不发布**。
 *
 * ## 成本与只读保证
 *
 * 每次拉取 = 2 次反射调用（`h0()` + 一个下标方法），由 200ms 闸门限流。
 * 全程**不修改任何业务状态**：钩子对返回值原样返回，发布只写
 * `CfhState.visiblePhotoRef`（该字段本就是「当前可见条」的既有缓存）。
 *
 * ## 不适用条件
 *
 * - 直播/图片浏览无 `QPhoto` → 下标方法返回 null，自然跳过（不清空已有值）。
 * - 未来版本若删掉「返回 QPhoto + 收 int」的全部方法 → 退化为
 *   `getCurrentPhoto()` 兜底（不随滑动更新，等于该功能失效，但不影响其它功能）。
 */
object CurrentPhotoHook {

    private const val VM = "com.kwai.library.groot.api.viewmodel.SlidePlayViewModel"
    private const val METHOD = "getCurrentPhoto"

    @Volatile private var installed = false

    /** 成功取到当前条的次数 */
    private val hits = java.util.concurrent.atomic.AtomicInteger(0)

    /** 返回 null（无当前条 / 服务未就绪）的次数 */
    private val nulls = java.util.concurrent.atomic.AtomicInteger(0)

    /** 最近一次取到的 photoId（供诊断/离线核对） */
    @Volatile var lastPhotoId: String? = null
        private set

    fun stats(): String = "当前条直取=${hits.get()}次 空=${nulls.get()}次 末次id=${lastPhotoId ?: "-"}"

    // ==================== 供三条对账（TripleCheck）使用的只读访问器 ====================
    //
    // ★ 用途（2026-09-24）：把「播放器实际在播」「数据层当前条」「屏上文案」
    //   三个来源在同一时刻对齐，抓分叉现场。此前三个探针各自为政、
    //   从未同时取值，而分叉是**瞬时事件** —— 不对齐就抓不到。

    /** ① 播放器实际在播的 URL（取自 PlayModule → IWaynePlayer.getCurrentPlayUrl） */
    fun currentPlayUrlPublic(): String? = try {
        val pm = playModuleRef?.get()
        if (pm == null) null else NoLoopGuard.currentPlayUrlOf(pm)
    } catch (_: Throwable) { null }

    /** ② 数据层当前条的 photoId（取自 SlidePlayViewModel 下标 + 按下标取条目） */
    fun currentPhotoIdPublic(): String? {
        return try {
            val vm = vmRef?.get() ?: return null
            val m = methodI ?: return null
            val idx = (m.invoke(vm) as? Number)?.toInt() ?: return null
            if (idx < 0) return null
            var qp: Any? = null
            for (m2 in methodIdx) {
                val r = try { m2.invoke(vm, idx) } catch (_: Throwable) { null }
                if (r != null) { qp = r; break }
            }
            qp?.let { CfhProbe.readPhotoId(it) }
        } catch (_: Throwable) { null }
    }

    /** ③ 数据层当前条的文案 */
    fun currentCaptionPublic(): String? {
        return try {
            val vm = vmRef?.get() ?: return null
            val m = methodI ?: return null
            val idx = (m.invoke(vm) as? Number)?.toInt() ?: return null
            if (idx < 0) return null
            var qp: Any? = null
            for (m2 in methodIdx) {
                val r = try { m2.invoke(vm, idx) } catch (_: Throwable) { null }
                if (r != null) { qp = r; break }
            }
            qp?.let { CfhUtil.readCaption(it) }
        } catch (_: Throwable) { null }
    }

    // ★★★ v13.66 按下标循环取数入池（2026-09-30 用户定稿方案）
    //
    // ## 为什么这是对的取数方式（而非我一直用的"扫字段"）
    // 决定性实证：`KWSEARCH` 从 VM 出发遍历 4000 个对象、深度 6，再换整页
    // Activity 作根，搜「卿本佳人 / 泡泡追剧 / 鼠鼠巴啦啦」**全部零命中**
    // ⇒ 屏幕内容**不在任何可达的 Java 字段里**，只通过方法暴露。
    // 而 `h0()` + `x4(idx)` 这套是本类**已验证可用**的取数路径
    // （CurrentPhotoHook 双向对账：模块跟踪到的条与用户念的屏幕文案逐条一致）。
    //
    // ## 做法
    //   ① `h0()` 取列表内当前下标 pos（**不是 I()** —— I 是滑动累计位置，
    //      实测 12 > 列表长度 10，按下标取必 null）
    //   ② 在 pos±span 范围内逐格调用「返回 QPhoto + 1 个 int 参数」的方法族
    //      （实测候选：T | U | U3 | c0 | x4，按签名特征匹配、不锁方法名）
    //   ③ 取到的条目**判白**（只有 WHITE 才够格入池）→ noteClean
    //
    // ## 为什么这样就能"自动"，不用用户滑首页
    // 屏幕上是哪几条，这个窗口就能取到哪几条 —— 用户滑的是**精选页**，
    // 取的也是**精选页当前屏**的真实内容，与首页无关。
    //
    // ## 边界
    // · 下标越界 ⇒ 方法返回 null ⇒ 自然跳过（不清空任何已有值）
    // · 直播/图片页无 QPhoto ⇒ 全程 0，无副作用
    // · 只读：不修改 VM 任何状态，只把返回值拿去判定
    // ==================== v13.67 多 VM 取数（修「入池恒 0」死角）====================
    //
    // ## 上一版（v13.66）的死角
    // harvestByIndex 只认 `vmRef`（最后注册的赢）⇒ 取的是**当前屏**。
    // 用户在精选页时当前屏就是精选页 —— 而精选页实测 ~100% 脏
    // （TTPPARSE 收=8 放行=0 / 收=9 放行=0）⇒ 入池恒 0 ⇒ 补位断粮 ⇒ 刷不出。
    // 首页发现页干净率 71~81%（放行22/26、放行16/21）—— **它才是池的供给源**。
    //
    // ## 为什么同一批 Method 能用在不同实例上
    // 「按下标取条目」的方法族是按**类**（沿继承链 declaredMethods）找到的，
    // 与实例无关；而精选页 VM 与首页 VM 又是**同一个类**（SlidePlayViewModel）
    // ⇒ 同一个 Method 对象可直接用在另一个实例上。故按**类名**缓存方法集。
    private class VmMethods(
        val byIndex: List<java.lang.reflect.Method>,
        val pos: java.lang.reflect.Method?,
        val size: java.lang.reflect.Method?
    )

    private val methodsByClass =
        java.util.concurrent.ConcurrentHashMap<String, VmMethods>()

    /** 留痕限次（按 tag 各自计数 —— 避免「全脏」与「卡某步」互相顶掉证据） */
    private val diagCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun diag(tag: String, msg: String) {
        // 计数竞争无所谓（纯诊断限次）；用显式 get/put 避免平台类型带来的可空推断
        val n: Int = try {
            val nv = (diagCount[tag] ?: 0) + 1
            diagCount[tag] = nv
            nv
        } catch (_: Throwable) { 99 }
        if (n > 40) return
        try { Logger.evidence(tag, "$msg (#$n)") } catch (_: Throwable) {}
    }

    /** 沿继承链找「返回 QPhoto + 恰好 1 个 int 参数」的方法族（特征匹配，不锁方法名）。 */
    private fun findIndexMethods(vm: Any): List<java.lang.reflect.Method> {
        val qc = CfhState.qpClassRef
        val out = ArrayList<java.lang.reflect.Method>(4)
        var c: Class<*>? = vm.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 8) {
            val cc: Class<*>? = c
            for (mm in (cc ?: break).declaredMethods) {
                if (mm.parameterTypes.size != 1) continue
                val p = mm.parameterTypes[0]
                if (p != Int::class.javaPrimitiveType && p != Int::class.java) continue
                val ok = if (qc != null) mm.returnType == qc
                         else mm.returnType.name.endsWith("QPhoto")
                if (!ok) continue
                try { mm.isAccessible = true } catch (_: Throwable) {}
                out.add(mm)
            }
            c = cc?.superclass; lvl++
        }
        return out
    }

    /** 沿继承链找无参方法。 */
    private fun findNoArgOn(vm: Any, name: String): java.lang.reflect.Method? {
        var c: Class<*>? = vm.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 8) {
            val cc: Class<*>? = c
            for (mm in (cc ?: break).declaredMethods) {
                if (mm.name == name && mm.parameterTypes.isEmpty()) {
                    try { mm.isAccessible = true } catch (_: Throwable) {}
                    return mm
                }
            }
            c = cc?.superclass; lvl++
        }
        return null
    }

    /** 沿继承链找「无参 + 返回 List」的方法（列表长度，仅诊断用）。 */
    private fun findListMethod(vm: Any): java.lang.reflect.Method? {
        var c: Class<*>? = vm.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 8) {
            val cc: Class<*>? = c
            for (mm in (cc ?: break).declaredMethods) {
                if (mm.parameterTypes.isNotEmpty()) continue
                if (!List::class.java.isAssignableFrom(mm.returnType)) continue
                try { mm.isAccessible = true } catch (_: Throwable) {}
                return mm
            }
            c = cc?.superclass; lvl++
        }
        return null
    }

    /** 取（并按类名缓存）某类 VM 的方法集；任何同类实例都可复用。 */
    private fun methodsOf(vm: Any): VmMethods {
        val cn = vm.javaClass.name
        methodsByClass[cn]?.let { return it }
        val m = VmMethods(
            findIndexMethods(vm),
            findNoArgOn(vm, "h0") ?: findNoArgOn(vm, "I"),
            findListMethod(vm)
        )
        methodsByClass[cn] = m
        return m
    }

    /** 当前注册的 VM（当前页 SlidePlayViewModel）；未注册时 null。 */
    /**
     * 当前 VM 实例。
     *
     * ★ v13.69：弱引用失效时回退到 `CfhState.vmRef`（强引用）。
     *   ## 为什么要回退
     *   `vmRef` 是 WeakReference（见下方声明）—— 一旦被 GC 回收，本方法就原样
     *   返回 null，而 `CfhSupply.ensurePoolSupply` 的两条收割路径都是
     *   `?: return` 的**静默跳过**（一条日志都不打）。真机上表现为
     *   「一切看着正常，就是 IDXHARVEST 一条都没有」，极难定位。
     *   `CfhState.vmRef` 是强引用，且由 `hookViewModel` 与 `registerVm` 在
     *   同一处赋值、指向同一个实例，回退不会引入「另一个 VM」。
     *   ## 与交接文档 §3.5 同一个坑
     *   弱引用存实例 → 登记 101 条、表里只剩 4 条（被 GC 回收）。
     */
    fun currentVmRef(): Any? = try {
        vmRef?.get() ?: CfhState.vmRef
    } catch (_: Throwable) {
        try { CfhState.vmRef } catch (_: Throwable) { null }
    }

    /** 扫掠上限（无位置服务时从 0 起扫多少格）。首屏列表实测 9~16 条，24 有富余。 */
    private const val SWEEP_MAX = 24

    /**
     * 从**任意指定的 VM 实例**按下标取数 → 判白 → 入池（v13.67）。
     *
     * 供「不管用户在哪个页面，都能拿到干净内容进池」用：
     *   · 首页 VM（[CfhState.homeVmRef]，干净率 71~81%）—— 池的**主供给源**
     *   · 当前页 VM（在首页时即首页 VM；在精选页时基本全脏，仅兜底 + 留痕）
     *
     * @param vm    目标 VM 实例（同类实例共用按类缓存的方法集）
     * @param span  `sweep=false` 时是以当前下标为中心的**窗口半径**；
     *              `sweep=true` 时是**从 0 起的最大下标**（不含）
     * @param tag   留痕标签后缀（`HOME` / 空 = 当前页），失败原因分开计数
     * @param sweep true = 不看当前位置、直接从 0 扫 —— 用于「把整屏列表一次收完」，
     *              以及后台/不可见的 VM（那些实例上位置服务可能给 -1）
     * @return 实际入池条数
     */
    fun harvestFrom(vm: Any, span: Int = 4, tag: String = "", sweep: Boolean = false): Int {
        val t = if (tag.isEmpty()) "IDXHARVEST" else "IDXHARVEST-$tag"
        val m = try { methodsOf(vm) } catch (_: Throwable) { return 0 }
        // ① 方法族没找到 —— 静默失败最常见的一步，必须先报出来
        if (m.byIndex.isEmpty()) {
            diag(t, "方法族未找到（无「返回QPhoto+1个int」的方法）VM=${vm.javaClass.simpleName}")
            return 0
        }
        val size = try {
            m.size?.let { (it.invoke(vm) as? List<*>)?.size ?: -1 } ?: -1
        } catch (_: Throwable) { -1 }
        // ② 位置下标；拿不到就**退化为从 0 扫** —— 按类取数并不依赖位置服务
        val pos = if (m.pos != null) {
            try { (m.pos.invoke(vm) as? Number)?.toInt() ?: -1 } catch (_: Throwable) { -1 }
        } else -1
        val indices: IntArray = when {
            sweep -> {
                // ★ 用「列表长度」当**上界**，绝不用它当**偏移**：
                //   长度取自 [findListMethod] 找到的「第一个返回 List 的无参方法」，
                //   未必与「按下标取条目」读的是同一个列表 —— 当偏移会越界取空，
                //   当上界最坏只是少收几条（安全方向）。
                val lim = maxOf(1, span)
                val n2 = if (size in 1 until lim) size else lim
                IntArray(n2) { it }
            }
            pos >= 0 -> {
                val lo = maxOf(0, pos - span)
                IntArray(pos + span - lo + 1) { lo + it }
            }
            else -> IntArray(SWEEP_MAX) { it }
        }
        var reg = 0
        var seen = 0
        for (idx in indices) {
            var qp: Any? = null
            for (m2 in m.byIndex) {
                val r = try { m2.invoke(vm, idx) } catch (_: Throwable) { null }
                if (r != null) { qp = r; break }
            }
            if (qp == null) continue
            seen++
            val v = try { CfhDecide.judgeWhitelist(qp) }
                    catch (_: Throwable) { CfhDecide.WhitelistVerdict.PENDING }
            if (v == CfhDecide.WhitelistVerdict.WHITE) {
                try { if (CfhState.noteClean(qp)) reg++ } catch (_: Throwable) {}
            }
        }
        // ③ **逐种失败分开留痕**（v13.67）：
        //    上一版只在 `reg > 0` 时打日志 ⇒「方法没找到 / pos 取不到 / 窗口内全脏」
        //    三种情况在 evidence 里**长得一模一样（都是零输出）**，
        //    这是上一次白跑一轮的直接原因。现在每种各打一行、各自限次。
        when {
            seen == 0 -> diag(
                t, "窗口内全取空 pos=$pos 列表=$size 试了下标=${indices.size} " +
                    "sweep=$sweep VM=${vm.javaClass.simpleName}"
            )
            reg == 0 -> diag(
                t, "取到=$seen 全判非WHITE pos=$pos 列表=$size " +
                    "sweep=$sweep VM=${vm.javaClass.simpleName}"
            )
            else -> diag(
                t, "★入池=$reg 取到=$seen pos=$pos 列表=$size sweep=$sweep " +
                    "池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }} " +
                    "方法=${m.byIndex.joinToString("/") { it.name }}"
            )
        }
        return reg
    }

    /** 当前页 VM 按下标取数（v13.66 原路径；v13.67 起委托 [harvestFrom]）。 */
    fun harvestByIndex(span: Int = 4): Int {
        val vm = currentVmRef() ?: return 0
        return harvestFrom(vm, span, "")
    }

    /** 按下标取数是否已就绪（供 CfhSupply 判断能否走这条路） */
    fun indexHarvestReady(): Boolean = try {
        val vm = vmRef?.get()
        vm != null && methodsOf(vm).byIndex.isNotEmpty()
    } catch (_: Throwable) { false }

    /** 记录 PlayModule 实例（供 ① 使用；由 NoLoopGuard 在进度回调时登记） */
    @Volatile private var playModuleRef: java.lang.ref.WeakReference<Any>? = null

    /** 登记 PlayModule 实例（NoLoopGuard 调用） */
    fun notePlayModule(pm: Any) {
        try {
            if (playModuleRef?.get() !== pm) {
                playModuleRef = java.lang.ref.WeakReference(pm)
            }
        } catch (_: Throwable) {}
    }

    /**
     * 当前下标读取值（诊断用，不参与判定）。
     *
     * ★ 用途：区分「用户没滑动」与「下标不更新」——心跳里带上它，
     *   一眼就能看出是哪一种。
     */
    private fun currentIndex(): Int = try {
        val vm = vmRef?.get()
        val m = methodI
        if (vm == null || m == null) -999
        else (m.invoke(vm) as? Number)?.toInt() ?: -998
    } catch (_: Throwable) { -997 }

    // ==================== 主动拉取（2026-09-24）====================
    //
    // ★★ 真机实测发现的关键事实：`getCurrentPhoto()` 是**查询接口，不是事件源**。
    //    装钩后只在页面**初始化**时被快手调用一次（02:41:08 唯一一次），
    //    用户滑动时快手**不会重新调用它** → 我的 hook 收不到通知，
    //    表现为「切了 1 次就再也不动」。
    //
    //    所以必须由模块**主动去调**它。触发时机选两处：
    //    ① 翻页事件（下方 [pokeNow]，由 pager 相关 hook 调用）
    //    ② 兜底定时器（防止某些页面结构不触发①）
    //
    //    这也解释了为什么之前两版都失败：我一直在找「事件源」，
    //    而正确答案是「查询 + 主动拉」。

    /** 缓存的 VM 实例（WeakReference：不阻止回收，VM 随页面销毁即可丢弃） */
    @Volatile private var vmRef: java.lang.ref.WeakReference<Any>? = null

    /** 缓存的 `getCurrentPhoto` 方法（按 VM 类缓存，避免每次反射查找） */
    @Volatile private var methodRef: java.lang.reflect.Method? = null

    /** 缓存的运行时 VM 类名（与编译期常量不同：安卓可能二次混淆） */
    @Volatile private var vmClassName: String? = null

    /** 主动拉取次数 */
    private val polls = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 注册 VM 实例（由既有 vmRef 建立点调用）。
     *
     * ★★ 真机实测暴露的关键错误（2026-09-24）：
     *    上一版只取 `getCurrentPhoto()` —— 实测「末次id 从头到尾同一个」，
     *    **滑动时它不更新**。原因来自逆向证据：
     *
     *    ```java
     *    public QPhoto getCurrentPhoto() {        // ← 走 kwai_data_source_service.b()
     *        v3c.a aVar = (v3c.a) P0("kwai_data_source_service");
     *        return aVar != null ? aVar.b() : null;
     *    }
     *    ```
     *    而**真正随滑动变的是位置服务**：
     *    ```java
     *    public int I() {                          // ← 当前下标
     *        e3c.e eVar = (e3c.e) P0("position_service");
     *        return eVar != null ? eVar.a() : -1;
     *    }
     *    public QPhoto K(int i) {                  // ← 按下标取条目
     *        v3c.a aVar = (v3c.a) P0("kwai_data_source_service");
     *        return aVar != null ? aVar.h(i) : null;
     *    }
     *    ```
     *    所以正确路径是 **`K(I())`**：先取当前位置，再按下标取条目。
     *
     *    本版同时保留两条路径 —— 每轮先试 `K(I())`，取到不同条目就用它；
     *    并在内部按 photoId 判重，避免同一条重复发布。
     */
    fun registerVm(vm: Any) {
        try {
            // ★ 用 declaredMethods 沿继承链找（getMethods 对某些合成/继承方法不可靠），
            //   参数匹配放宽为「1 个参数且是 int 或其包装」——
            //   避免因 int/Integer 差异而漏掉（实测首版用 getMethods 时 K=null）。
            fun findIntArg(name: String): java.lang.reflect.Method? {
                var c: Class<*>? = vm.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 8) {
                    val cc: Class<*>? = c
                    for (m in (cc ?: break).declaredMethods) {
                        if (m.name != name || m.parameterTypes.size != 1) continue
                        val p = m.parameterTypes[0]
                        if (p == Int::class.javaPrimitiveType || p == Int::class.java) {
                            try { m.isAccessible = true } catch (_: Throwable) {}
                            return m
                        }
                    }
                    c = cc?.superclass; lvl++
                }
                return null
            }

            /**
             * ★ 按**特征**找「按下标取条目」的方法（2026-09-24 关键修正）。
             *
             * 为什么不能锁死方法名：实机 14.8.20 上叫 `T(int)`，
             * 而逆向件 14.8.30 上叫 `K(int)` —— **类名相同、方法名不同**。
             * 实测证据（设备自报）：
             *   返回 QPhoto 的方法 = T(int) | U(int) | U3(int) | c0(int) |
             *                          x4(int) | Y0(List,int) | getCurrentPhoto() ...
             * 锁死 "K" 的结果就是 `K=false`，功能全程静默失效。
             *
             * 故改为按「**返回 QPhoto + 恰好 1 个 int 参数**」这个语义特征匹配。
             * 这是混淆**改不动**的部分：方法名会变，但「按下标取一条」的
             * 签名语义必须保留。
             *
             * 多个候选时**全部保留**，运行时逐个试 —— 谁先返回有效条目就用谁
             * （不同版本可能只有其中一个是真的数据源入口）。
             */
            fun findQPhotoByIndex(): List<java.lang.reflect.Method> {
                val qc = CfhState.qpClassRef
                val out = ArrayList<java.lang.reflect.Method>(4)
                var c: Class<*>? = vm.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 8) {
                    val cc: Class<*>? = c
                    for (m in (cc ?: break).declaredMethods) {
                        if (m.parameterTypes.size != 1) continue
                        val p = m.parameterTypes[0]
                        if (p != Int::class.javaPrimitiveType && p != Int::class.java) continue
                        val ok = if (qc != null) m.returnType == qc
                                 else m.returnType.name.endsWith("QPhoto")
                        if (!ok) continue
                        try { m.isAccessible = true } catch (_: Throwable) {}
                        out.add(m)
                    }
                    c = cc?.superclass; lvl++
                }
                return out
            }

            fun findNoArg(name: String): java.lang.reflect.Method? {
                var c: Class<*>? = vm.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 8) {
                    val cc: Class<*>? = c
                    for (m in (cc ?: break).declaredMethods) {
                        if (m.name == name && m.parameterTypes.isEmpty()) {
                            try { m.isAccessible = true } catch (_: Throwable) {}
                            return m
                        }
                    }
                    c = cc?.superclass; lvl++
                }
                return null
            }

            // 路径①（主）：按特征找「按下标取条目」——不锁死方法名
            val byIndex = findQPhotoByIndex()
            // 路径②：**h0()** —— 列表内当前下标。
            //
            // ★ 为什么是 h0 而不是 I（2026-09-24 实测对照）：
            //   同一时刻实测 列表=10 / I=12 / h0=3。
            //   I() 超出列表长度（12 > 10）→ 按下标取全部返回 null；
            //   h0() 落在范围内（3 < 10）→ 才是真正的列表内下标。
            //   取不到条目就只能退回 getCurrentPhoto()（不随滑动更新），
            //   表现为「文案对不上」。
            //
            //   两个方法调用的是位置服务里不同的取值（I→eVar.a、h0→eVar.b），
            //   语义不同，不能混用。
            val mI = findNoArg("h0") ?: findNoArg("I")
            // 路径③（兜底）：getCurrentPhoto() —— 实测不随滑动更新，仅保底
            val mCur = findNoArg(METHOD)

            if (mCur == null && (byIndex.isEmpty() || mI == null)) return
            vmRef = java.lang.ref.WeakReference(vm)
            methodIdx = byIndex
            methodI = mI
            methodRef = mCur
            vmClassName = vm.javaClass.name
            Logger.once(
                "curphoto.vm",
                "CURPHOTO vm 已注册: ${vm.javaClass.name} 按下标=${byIndex.map { it.name }} I=${mI != null} getCurrentPhoto=${mCur != null}"
            )
            // ★ 结构自证（2026-09-24）：逆向件是 14.8.30，设备跑的是 14.8.20
            //   —— 签名可能不同。与其猜，不如把**设备上真实存在**的
            //   「返回 QPhoto 的方法」全列出来（每进程一次，成本可忽略）。
            try {
                val qc = CfhState.qpClassRef
                if (qc != null) {
                    val found = ArrayList<String>()
                    var c2: Class<*>? = vm.javaClass
                    var l2 = 0
                    while (c2 != null && c2 != Any::class.java && l2 < 8) {
                        val cc2: Class<*>? = c2
                        for (m in (cc2 ?: break).declaredMethods) {
                            if (m.returnType == qc || m.returnType.name.endsWith("QPhoto")) {
                                found.add("${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
                            }
                        }
                        c2 = cc2?.superclass; l2++
                    }
                    Logger.always("CURPHOTO 实际返回QPhoto的方法(${found.size}): ${found.joinToString(" | ")}")
                    // ★ 同时列出「收 int 参数」的方法 —— 这是「按下标取条目」的候选
                    val intArg = ArrayList<String>()
                    var c3: Class<*>? = vm.javaClass
                    var l3 = 0
                    while (c3 != null && c3 != Any::class.java && l3 < 8) {
                        val cc3: Class<*>? = c3
                        for (m in (cc3 ?: break).declaredMethods) {
                            if (m.parameterTypes.size == 1 &&
                                (m.parameterTypes[0] == Int::class.javaPrimitiveType ||
                                 m.parameterTypes[0] == Int::class.java)
                            ) {
                                intArg.add("${m.name}->${m.returnType.simpleName}")
                            }
                        }
                        c3 = cc3?.superclass; l3++
                    }
                    Logger.always("CURPHOTO 收int参数的方法(${intArg.size}): ${intArg.joinToString(" | ")}")
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /** 上次范围自证时间（限流用） */
    @Volatile private var lastRangeDiagAt = 0L

    /** 无参方法缓存（范围自证用） */
    private val noArgMethods =
        java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>>()

    /**
     * 位置方法自选（2026-09-24）—— 修「数据层卡死」。
     *
     * ## 为什么要自选
     *
     * 实测对照（同一时刻）：
     * - 精选页：`列表=10  I=12  h0=3`  → 用 `h0`（`I` 超界）
     * - 详情页：`列表=78  I=91  h0=3`  → `h0` **恒定 3 不动**
     *   （对账实测 `数据层id 从头发到尾没变过`）
     *
     * 即：**没有哪一个位置方法在所有页面上都对**。
     * 与其继续赌某一个，不如**运行时自选**：
     * 记录每个候选的返回值序列，谁**真的在变**、且**落在列表范围内**，
     * 就用谁。
     *
     * ## 选法
     *
     * 维护「候选 → 上次值」，当前值与前次不同即记一次「变化」；
     * 变化次数最多、且当前值 < 列表长度 的候选胜出。
     * 每 [RESELECT_EVERY] 次读取重新评估一次（页面切换后可能换赢家）。
     */
    @Volatile private var idxChosenName: String? = null

    private val idxLastVal = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val idxChanges = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val idxReadCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 重新评估胜者的间隔（读次数） */
    private const val RESELECT_EVERY = 20

    /**
     * 读取「当前位置下标」——自动选用在当前页面上真正有效的那个位置方法。
     *
     * ★ 可见性放宽为 internal（2026-09-24）：`CfhWash` 的周期可见内容巡检
     *   （PSCAN）需要它来定位「当前播放的是哪一条」。
     *   该巡检脱离 UI 事件、挂在周期任务上，用于解决
     *   「VISDUMP 挂在 Fragment 扫描上导致 52 秒零输出」的观测盲区。
     *
     * @return 下标；全部候选都不可用时返回 -1
     */
    internal fun readCurrentIndex(vm: Any): Int {
        val candidates = arrayOf("h0", "I", "m0", "g0", "n", "x", "Z")
        // 列表长度（用于排除超界值）
        var listSize = -1
        try {
            val lm = findNoArgCached("H") ?: findNoArgCached("E")
            if (lm != null) listSize = (lm.invoke(vm) as? List<*>)?.size ?: -1
        } catch (_: Throwable) {}

        val n = idxReadCount.incrementAndGet()
        // ★★ 选定即锁定，不再周期重选（2026-09-24 修正）。
        //
        //   原实现每 20 次读取重新评估胜者，实测导致**抖动**：
        //   对账记录显示数据层在相邻两条之间每 0.5 秒来回切
        //   （`5246130824713918293 ←→ 5224738727932912920`），
        //   而播放器始终稳定在同一条 —— 这个「数据层横跳」正是
        //   「文案对不上视频」的直接来源（我引入的，不是快手的行为）。
        //
        //   改为：首次选定后**只在该候选返回无效值时**才重选。
        //   稳定的页面用一个稳定的位置来源，不来回换。
        val needReselect = idxChosenName == null

        // 每次读取都更新「变化统计」（供下一次重新评估用）
        val vals = HashMap<String, Int>(8)
        for (nm in candidates) {
            val m = findNoArgCached(nm) ?: continue
            val v = try { (m.invoke(vm) as? Number)?.toInt() ?: continue } catch (_: Throwable) { continue }
            vals[nm] = v
            val last = idxLastVal[nm]
            if (last != null && last != v) idxChanges.merge(nm, 1, Int::plus)
            idxLastVal[nm] = v
        }
        if (vals.isEmpty()) return -1

        if (needReselect) {
            // 胜者 = 「变化最多」且「当前值在列表范围内」
            val best = vals.entries
                .filter { (_, v) -> v >= 0 && (listSize <= 0 || v < listSize) }
                .maxByOrNull { (nm, _) -> idxChanges[nm] ?: 0 }
            idxChosenName = best?.key
        }
        val chosen = idxChosenName ?: return -1
        val v = vals[chosen]
        // 锁定的候选失效（不在本次读到的值里 / 越界）→ 释放，让下次重选
        if (v == null || v < 0 || (listSize > 0 && v >= listSize)) {
            idxChosenName = null
            return -1
        }
        return v
    }

    /** 位置方法全量对照（诊断）。 */
    private fun positionSurvey(): String {
        val vm = vmRef?.get() ?: return "无VM"
        val names = arrayOf("I", "h0", "m0", "g0", "Z", "n", "x")
        val sb = StringBuilder()
        for (n in names) {
            val m = findNoArgCached(n) ?: continue
            val v = try { (m.invoke(vm) as? Number)?.toString() ?: "?" } catch (_: Throwable) { "E" }
            sb.append(n).append('=').append(v).append(' ')
        }
        val lm = findNoArgCached("H") ?: findNoArgCached("E")
        if (lm != null) {
            val sz = try { (lm.invoke(vm) as? List<*>)?.size ?: -1 } catch (_: Throwable) { -1 }
            sb.append("列表=").append(sz)
        }
        return sb.toString()
    }

    /** 按名取无参方法（带缓存；找不到缓存为 empty 避免反复反射） */
    private fun findNoArgCached(name: String): java.lang.reflect.Method? {
        val vm = vmRef?.get() ?: return null
        val hit = noArgMethods[name]
        if (hit != null) return hit.orElse(null)
        var c: Class<*>? = vm.javaClass
        var lvl = 0
        var found: java.lang.reflect.Method? = null
        while (c != null && c != Any::class.java && lvl < 8) {
            val cc: Class<*>? = c
            for (m in (cc ?: break).declaredMethods) {
                if (m.name == name && m.parameterTypes.isEmpty()) {
                    try { m.isAccessible = true } catch (_: Throwable) {}
                    found = m; break
                }
            }
            if (found != null) break
            c = cc?.superclass; lvl++
        }
        noArgMethods[name] = java.util.Optional.ofNullable(found)
        return found
    }

    /** 按特征找到的「按下标取条目」方法（可能多个，运行时逐个试） */
    @Volatile private var methodIdx: List<java.lang.reflect.Method> = emptyList()

    /** `I()` —— 当前位置下标 */
    @Volatile private var methodI: java.lang.reflect.Method? = null

    /**
     * 已锁定的「按下标取条目」方法。
     *
     * ★ 为什么要锁定（2026-09-24 实测）：
     *   候选方法有 5 个（T/U/U3/c0/x4），都收 int 且返回 QPhoto，
     *   但**未必指向同一个列表**。若每次都在候选间重新试，
     *   同一下标会取到不同条目 → photoId 反复横跳
     *   （实测 46 次切条仅 25 个不同 id）。
     *   锁定首个成功者后，下标↔列表 对应关系恒定，切条才会单调跟随滑动。
     */
    @Volatile private var lockedIdxMethod: java.lang.reflect.Method? = null

    /**
     * 主动拉一次当前条。翻页 / 定时器都可调，幂等且廉价。
     *
     * ★★ 上一版这里写出过严重 bug（2026-09-24 真机刷屏事故）：
     *    计数 `polls.incrementAndGet()` 在多个分支各调一次，
     *    而日志条件又依赖这个被重复自增的数 —— 结果条件几乎恒成立，
     *    几十秒内打出 6 万行日志把 logcat 淹没。
     *
     *    本次重写的铁律：
     *    ① **单一自增点**：函数入口加一次，exit 路径不再各自加；
     *    ② **日志用时间限流**，不用计数取模（计数会被多次自增污染，
     *       时间不会）；
     *    ③ 每个 exit 路径都走 [finish] 统一出口，杜绝「分支各写一份日志逻辑」。
     *
     * @return true = 取到并发布了新条目
     */
    fun pokeNow(): Boolean {
        // ★★ 重入/高频闸门（2026-09-24 刷屏事故的根本修复）：
        //    pokeNow 被挂在 pager 的拦截点上，而 pager 方法每秒被调用**上千次**，
        //    实测「主动拉取=31461次/秒」—— 统计和日志都被这个量级冲垮。
        //    这里加一道时间闸：同一时刻只允许一次，且两次之间至少 200ms。
        //    （200ms 足够跟上用户滑动的手速，又不会让 pager 的高频调用穿透。）
        val now0 = System.currentTimeMillis()
        if (now0 - lastPokeAt < 200L) return false
        lastPokeAt = now0

        val seq = polls.incrementAndGet()
        var result = false
        var note: String? = null
        try {
            val vm = vmRef?.get()
            if (vm == null) {
                note = "无VM"
            } else {
                // ★ 主路径：**按下标取条目**（特征匹配，不锁方法名）。
                //   逆向 + 实机双重证据：随滑动变的是 position_service（I()），
                //   而不是 getCurrentPhoto()（实测末次id 恒定不变）。
                var qp: Any? = null
                var via = "-"
                if (methodIdx.isNotEmpty()) {
                    // ★★ 下标改为**运行时自选**（2026-09-24，修「数据层卡死」）。
                    //
                    //   实测对照证明**没有哪个位置方法在所有页面都对**：
                    //     精选页：列表=10  I=12  h0=3   → h0 对（I 超界）
                    //     详情页：列表=78  I=91  h0=3   → h0 **恒定 3 不动**
                    //              （对账实测「数据层id 从头发到尾没变过」）
                    //
                    //   故改为 [readCurrentIndex]：记录各候选的**变化次数**，
                    //   选「真的在变 + 落在列表范围内」的那个。
                    //   页面切换后每 20 次读取重新评估。
                    val idx = readCurrentIndex(vm)
                    if (idx >= 0) {
                        // ★★ 固定用「第一次成功的那个方法」，不再每次都从候选表头重试。
                        //
                        //   实测教训（2026-09-24）：候选 T/U/U3/c0/x4 都收 int 且返回
                        //   QPhoto，但它们**未必指向同一个列表**（可能分别是当前列表、
                        //   预加载列表、历史列表…）。原实现「谁先非 null 就用谁」会让
                        //   同一个下标在不同方法间取到不同条目 ——
                        //   日志实测表现为 photoId **反复横跳**
                        //   （46 次切条只有 25 个不同 id，同样几个来回切）。
                        //
                        //   修法：一旦某个方法成功返回过条目，就**锁定它**，
                        //   后续只用它。锁定后下标与列表的对应关系恒定，
                        //   photoId 自然单调跟随滑动。
                        val locked = lockedIdxMethod
                        val order = if (locked != null) listOf(locked) else methodIdx
                        for (m in order) {
                            val r = try { m.invoke(vm, idx) } catch (_: Throwable) { null }
                            if (r != null) {
                                if (locked == null) {
                                    lockedIdxMethod = m
                                    // ★ 锁定即报「下标 + 方法名 + 该下标的 photoId」三要素。
                                    //   没有这行就无法区分「用户没滑」和「下标不动」——
                                    //   而这两种情况的修法完全不同（前者等待，后者查 I()）。
                                    val pid0 = try { CfhProbe.readPhotoId(r) } catch (_: Throwable) { null }
                                    try {
                                        FeaturedTrack.log("CURPHOTO 锁定下标方法=${m.name} 当前下标=$idx 该下标id=$pid0")
                                    } catch (_: Throwable) {}
                                }
                                qp = r; via = "${m.name}($idx)"; break
                            }
                        }
                    }
                    if (qp == null) via = "按下标取空(idx=$idx)"
                    // ★ 超界自证（2026-09-24）：实测「按下标取空(idx=30)」——
                    //   下标 30 却全候选返回 null，最可能是**超界**：
                    //   position_service 给的可能是「全局累计位置」，
                    //   而按下标取条目要的是「当前列表内下标」。
                    //   这里把「列表长度」与「几个下标试取结果」一并报出，
                    //   据此判定到底是不是超界，而不是继续猜。
                    if (qp == null && idx >= 0) {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastRangeDiagAt > 10000L) {
                            lastRangeDiagAt = nowMs
                            val sizes = StringBuilder()
                            val mDs = findNoArgCached("H") ?: findNoArgCached("E")
                            if (mDs != null) {
                                try {
                                    val lst = mDs.invoke(vm) as? List<*>
                                    sizes.append("列表=${lst?.size ?: "?"}")
                                } catch (_: Throwable) { sizes.append("列表=异常") }
                            } else sizes.append("列表=无方法")
                            // 试几个下标，看哪个能取到
                            val probe = StringBuilder()
                            for (t in intArrayOf(0, 1, 5, idx)) {
                                var ok = "-"
                                for (m in methodIdx) {
                                    val r = try { m.invoke(vm, t) } catch (_: Throwable) { null }
                                    if (r != null) { ok = m.name; break }
                                }
                                probe.append("[$t→$ok]")
                            }
                            try {
                                FeaturedTrack.log("CURPHOTO 范围自证 $sizes 试取$probe")
                            } catch (_: Throwable) {}
                        }
                    }
                }
                // 兜底：按下标取不到时才退回 getCurrentPhoto()
                if (qp == null) {
                    methodRef?.let { m ->
                        val r = try { m.invoke(vm) } catch (_: Throwable) { null }
                        if (r != null) { qp = r; via = "兜底getCurrentPhoto(下标路:$via)" }
                    }
                }
                val qc = CfhState.qpClassRef
                val got: Any? = qp
                if (got != null && qc != null && qc.isInstance(got)) {
                    val id = CfhProbe.readPhotoId(got)
                    if (!id.isNullOrBlank() && id != lastPhotoId) {
                        lastPhotoId = id
                        hits.incrementAndGet()
                        // ★★ 切条时**同时打出该条的文案**（2026-09-24 终验）。
                        //
                        //   这是「文案不变、画面在变」bug 的**唯一终局判据**：
                        //   用户念屏幕上的文案，我在日志里看同一 photoId 的文案，
                        //   两边一致 = 真修好；不一致 = 认错条目。
                        //
                        //   之前我一直在用间接证据（id 是否变化、是否横跳）推断，
                        //   但那些只能说明「跟踪在动」，**证明不了「动对了」**。
                        //   把文案和 id 绑在同一行打出，才能一次判定，不用再来回试。
                        val cap = try { CfhUtil.readCaption(got) } catch (_: Throwable) { null }
                        try {
                            FeaturedTrack.log(
                                "FEATTRACK 当前条切换 photoId=$id 文案=${cap?.take(40) ?: "（空）"}"
                            )
                        } catch (_: Throwable) {}
                        FeaturedTrack.publishObject(got, id)
                        result = true
                    } else {
                        note = "同条[$via] id=$id"
                    }
                } else {
                    note = "取空[$via]"
                }
            }
        } catch (_: Throwable) {
            note = "异常"
        }
        // ★ 时间限流：同一类提示最快 3 秒一次（计数会被多次自增污染，时间不会）
        if (note != null && result.not()) maybeLog(note, seq)
        return result
    }

    /** 上次打同类提示的时间（时间限流用） */
    @Volatile private var lastNoteAt = 0L

    /** 限流日志：3 秒内不重复打同一类提示 */
    private fun maybeLog(note: String, seq: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNoteAt < 3000L) return
        lastNoteAt = now
        try { FeaturedTrack.log("CURPHOTO 拉取提示=$note (第${seq}次)") } catch (_: Throwable) {}
    }

    /** 兜底定时器句柄（供幂等控制） */
    @Volatile private var timer: java.util.Timer? = null

    /**
     * 是否启用主动轮询（2026-09-24 起默认**关**）。
     *
     * ★★ 为什么默认关：
     *   本轮询每 800ms 做 2 次反射调用（读下标 + 按下标取条目），
     *   在「文案对不上视频」排查 12 轮未果后，**必须先排除
     *   『探针自身干扰』**这个可能 —— 否则观测不可信。
     *
     *   轮询原本的用途是「精选页当前条跟踪」；而详情页的
     *   当前条已由 `FEATTRACK`（pager 翻页事件驱动）覆盖，
     *   不再依赖周期轮询。
     *
     *   需要取证时把本开关置 true 即可。
     */
    @Volatile var pollingEnabled = false

    /**
     * 启动兜底轮询。
     *
     * ★ 为什么需要定时器（而不是只靠翻页事件）：
     *   翻页事件的 hook 位置依赖具体类结构，版本一换可能失配；
     *   定时器不依赖任何结构，是「无论如何都能兜住」的那一层。
     *   成本：每 800ms 一次反射调用，可忽略。
     *   仅在已注册 VM 后才实际工作（vmRef 为 null 时直接返回）。
     */
    fun startPolling() {
        if (timer != null) return
        if (!pollingEnabled) {
            Logger.once("curphoto.polloff", "CURPHOTO 主动轮询未启用（默认关，避免反射轮询干扰播放）")
            return
        }
        try {
            val t = java.util.Timer("curphoto-poll", true)
            t.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        pokeNow()
                        // ★ 心跳：时间限流（60 秒一次），不再用计数取模 ——
                        //   上一版正是栽在「计数被多次自增 → 取模条件几乎恒真 → 刷屏」。
                        val now = System.currentTimeMillis()
                        if (now - lastBeatAt >= 15_000L) {
                            lastBeatAt = now
                            try {
                                FeaturedTrack.log("CURPHOTO 心跳 ${stats()} ${pollStats()} 下标=${currentIndex()}")
                            } catch (_: Throwable) {}
                            // ★ 位置方法全量对照（2026-09-24）：逆向显示位置服务有 7 个
                            //   取值方法且含义不同（getFirstValidItemPosition /
                            //   getLastValidItemPosition / getRealCountInAdapter / ...）。
                            //   我不知道哪个是「列表内当前下标」，也不该继续猜 ——
                            //   把每个方法的返回值全打出来，与列表长度一对照就清楚了。
                            try { FeaturedTrack.log("CURPHOTO 位置对照 ${positionSurvey()}") } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }
            }, 500L, 800L)
            timer = t
            Logger.once("curphoto.timer", "CURPHOTO 兜底轮询已启动（800ms 一次，主动拉当前条）")
        } catch (_: Throwable) {}
    }

    /** 上次心跳时间（时间限流，不用计数） */
    @Volatile private var lastBeatAt = 0L

    /** 钩子观察到的 getCurrentPhoto 值（仅供排障对照，不参与发布） */
    @Volatile private var lastSeenByHook: String? = null

    /** 上次实际拉取时间（高频重入闸门用） */
    @Volatile private var lastPokeAt = 0L

    fun pollStats(): String = "主动拉取=${polls.get()}次"

    /** 离线自检用：重置 */
    fun resetForTest() {
        vmRef = null; methodRef = null; methodIdx = emptyList(); methodI = null
        lockedIdxMethod = null; vmClassName = null
        polls.set(0); hits.set(0); nulls.set(0); lastPhotoId = null
    }

    /**
     * 安装。由 `ContentFilterHook` 调用。
     *
     * 全程容错：类找不到 / 方法不存在 / 装钩异常 → 记日志并静默降级。
     */
    fun install(xp: XposedInterface, cl: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val c = Reflect.findClass(VM, cl) ?: run {
                Logger.once(
                    "curphoto.miss",
                    "CURPHOTO: $VM 未找到 → 精选页「当前条」直取不可用（不影响其它功能）"
                )
                return
            }
            // ★ 按**方法名**找，不锁定签名：不同版本返回类型可能变化，
            //   只要名字和无参这两点成立就装。
            val m = c.declaredMethods.firstOrNull {
                it.name == METHOD && it.parameterTypes.isEmpty()
            } ?: run {
                Logger.once("curphoto.nomethod", "CURPHOTO: $VM.$METHOD() 不存在 → 不可用")
                return
            }
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("curphoto").intercept { chain ->
                    val ret = chain.proceed()
                    // ★ 只观察、不改写：原样返回 ret。
                    //   绝不在这里替换返回值 —— 那会改变快手自身的行为。
                    //
                    // ★★ 这里**不再发布「当前条」**（2026-09-24 双源冲突修复）。
                    //
                    //   曾在此发布 FeatureTrack，但那是个错误：快手自己在后台
                    //   **周期性调用** getCurrentPhoto()，于是本钩子变成第二个发布源。
                    //   实测两个源精准打架（约 0.4s / 9.6s 两个节奏交替）：
                    //     04:05:28  5249227049826938458   ← 本钩子（getCurrentPhoto，不随滑动更新）
                    //     04:05:29  5257389823659536427   ← 轮询（下标路径，真实的当前条）
                    //     04:05:33  5249227049826938458   ← 又翻回去
                    //   —— 用户看到的仍是错条目。
                    //
                    //   注意：`getCurrentPhoto()` 的**返回值**本身没变（实测滑动时
                    //   它给的一直是旧条目），变的是「谁在发布」。所以修复方式是
                    //   让权威源唯一：**只保留下标路径的轮询**，本钩子退化为纯观察。
                    try {
                        if (ret != null && CfhState.qpClassRef?.isInstance(ret) == true) {
                            val id = CfhProbe.readPhotoId(ret)
                            // 仅记录一次「它给的是什么」，供排障对照，不发布
                            if (!id.isNullOrBlank() && id != lastSeenByHook) {
                                lastSeenByHook = id
                                try {
                                    FeaturedTrack.log("CURPHOTO 钩子观察到 getCurrentPhoto=$id（不发布）")
                                } catch (_: Throwable) {}
                            }
                        } else {
                            nulls.incrementAndGet()
                        }
                    } catch (_: Throwable) {}
                    ret
                }
            Logger.once(
                "curphoto.installed",
                "CURPHOTO installed: $VM.$METHOD() → 直取当前条（精选页跟踪走这条）"
            )
        } catch (t: Throwable) {
            Logger.once("curphoto.fail", "CURPHOTO 安装失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
