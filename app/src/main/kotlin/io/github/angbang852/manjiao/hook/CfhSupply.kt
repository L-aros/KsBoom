package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.util.Logger

// ★ ContentFilterHook 深拆第三步：供给恢复（2026-09 S3）。
// loadMore/refresh 反射调用链与 hasMore 健康检查——列表被删短后的续拉/重拉兜底。
object CfhSupply {
    fun findLoadTarget(inst: Any): Any? {
        val selfHas = try { inst.javaClass.getMethod("load"); true } catch (_: Throwable) { false }
        if (selfHas) return inst
        var fc: Class<*>? = inst.javaClass
        var flvl = 0
        while (fc != null && fc != Any::class.java && flvl < 6) {
            for (f in fc!!.declaredFields) {
                try {
                    f.isAccessible = true
                    val req = f.get(inst) ?: continue
                    if (req is Collection<*> || req is android.view.View) continue
                    val has = try { req.javaClass.getMethod("load"); true } catch (_: Throwable) { false }
                    if (has) return req
                } catch (_: Throwable) {}
            }
            fc = fc.superclass; flvl++
        }
        return null
    }
    fun triggerLoadMore(): Boolean {
        // ★ 线程闸门：同 triggerRefresh，后台线程调用一律投回主线程执行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            CfhState.handler.post { try { triggerLoadMore() } catch (_: Throwable) {} }
            return true
        }
        val now = System.currentTimeMillis()
        if (now - CfhState.lastLoadMoreTime < 800) return false
        val inst = CfhState.knhbInst?.get()
        if (inst == null) { Logger.d("loadMore SKIP: knhbInst=null -> fallback refresh"); return triggerRefresh() }
        val target = findLoadTarget(inst)
        if (target == null) { Logger.d("loadMore no target -> fallback refresh"); return triggerRefresh() }
        val hasMore = try {
            val hm = target.javaClass.getMethod("hasMore"); hm.isAccessible = true
            hm.invoke(target) as? Boolean ?: true
        } catch (_: Throwable) { true }
        if (!hasMore) {
            Logger.d("loadMore hasMore=false -> refresh recover")
            val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }
            if (rm != null) {
                try { rm.isAccessible = true; rm.invoke(target); CfhState.lastLoadMoreTime = now; return true } catch (_: Throwable) {}
            }
            return triggerRefresh()
        }
        val isLoading = try {
            val il = target.javaClass.getMethod("isLoading"); il.isAccessible = true
            il.invoke(target) as? Boolean ?: false
        } catch (_: Throwable) { false }
        if (isLoading) {

            // ★ 启动窗（冷启后 15s 内）收紧等待阈值（实证 2026-09 probe4 首屏）：
            // 「prefetch short list=2 → loadMore in flight >5s → skip: request in flight
            //   → prefetch short list=0」——首屏 7 条里 5 条脏只剩 2 条，而补位请求卡在
            // 宿主自己的 in-flight 状态里死等 5s，这 2 秒空窗分页器只有 2 条可翻，
            // 用户看到的第一/第二条就是这残存的 2 条（含未回填的空壳项）。
            // 启动窗内改用 1.2s 阈值，尽快走 refresh 兜底把数据补进来。
            val bootWindow = now - CfhState.processStartAt < 15_000L
            val waitMs = if (bootWindow) 1_200L else 5_000L
            if (now - CfhState.lastLoadMoreTime > waitMs) {

                Logger.always("loadMore in flight >${waitMs}ms (boot=$bootWindow) -> hist reset + refresh recover")

                synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.clear() }

                val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }

                if (rm != null) { try { rm.isAccessible = true; rm.invoke(target); CfhState.lastLoadMoreTime = now; return true } catch (_: Throwable) {} }

                return triggerRefresh()

            }

            Logger.d("loadMore skip: request in flight")

            return true

        }
        return try {
            val m = target.javaClass.getMethod("load")
            m.isAccessible = true
            m.invoke(target)
            CfhState.lastLoadMoreTime = now
            Logger.d("loadMore called on ${target.javaClass.name} (hasMore=true)")
            true
        } catch (_: Throwable) { triggerRefresh() }
    }
    fun triggerRefresh(): Boolean {
        // ★ 线程闸门（审阅 2026-09）：本方法会从 cleanExecutor 后台线程（sanitizeList
        // all-dirty 兜底、loadMore 恢复）调用，反射 invoke 宿主 VM 刷新方法必须在主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            CfhState.handler.post { try { triggerRefresh() } catch (_: Throwable) {} }
            return true
        }
        val now = System.currentTimeMillis()
        if (now - CfhState.lastRefreshTime < 800) return false
        CfhState.lastRefreshTime = now
        val vm = CfhState.vmRef
        if (vm == null) { Logger.always("refresh SKIP: vmRef=null (VM not found yet)"); return false }
        for (name in arrayOf("v0", "B1", "C1", "E1", "K1", "W0", "X0", "Y0", "z0", "y0", "refresh", "loadMore")) {
            val m = CfhProbe.cachedMethod(vm.javaClass, name) ?: continue
            try {
                m.invoke(vm)
                Logger.d("refresh called: $name")
                return true
            } catch (_: Throwable) {}
        }
        val sigKeys = arrayOf("refresh", "load", "more", "feed", "page", "fetch", "reload", "request")
        var c: Class<*>? = vm.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (m in c!!.declaredMethods) {
                if (m.parameterTypes.isNotEmpty() || m.returnType != Void.TYPE) continue
                val mn = m.name.lowercase()
                if (!sigKeys.any { mn.contains(it) }) continue
                try {
                    m.isAccessible = true
                    m.invoke(vm)
                    Logger.d("refresh called(sig): ${m.name}")
                    return true
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        Logger.always("refresh no method vm=${vm.javaClass.name}")
        return false
    }
    fun refreshContent(): Boolean {
        synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.clear() }
        Logger.always("refreshContent: hist cleared")
        val inst = CfhState.knhbInst?.get()
        if (inst == null) { Logger.always("refreshContent: knhbInst=null, fallback loadMore"); return triggerLoadMore() }
        val target = findLoadTarget(inst)
        if (target == null) { Logger.always("refreshContent: no target, fallback loadMore"); return triggerLoadMore() }
        val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }
        if (rm != null) {
            try {
                rm.isAccessible = true; rm.invoke(target)
                CfhState.lastLoadMoreTime = System.currentTimeMillis()
                Logger.always("refreshContent: refresh() called on " + target.javaClass.name)
                return true
            } catch (e: Throwable) { Logger.always("refreshContent: refresh() threw " + e.javaClass.name) }
        }
        Logger.always("refreshContent: no refresh method, fallback loadMore")
        return triggerLoadMore()
    }

    /**
     * ★★★ 安全续拉（2026-09-28「精选页池空自动续上」用户方案）。
     *
     * ## 与 triggerLoadMore / triggerRefresh 的本质区别
     *
     * 用户明确否决了 refresh（「刷新是把页面原有数据都清空了」）。
     * triggerLoadMore 在 inst/target/hasMore/isLoading 失败时会 **fallback refresh**，
     * 那正是清空重拉 —— 不可用于「池空自动续上」。
     *
     * 本函数只做一件事：**找到目标对象的 load() 并调用它（追加下一页）**。
     * 任何一步不满足（inst 缺失 / 无 load 方法 / hasMore=false / 正在加载）
     * 都 **直接返回 false**，绝不走 refresh —— 宁可等快手自己的节奏，
     * 也绝不清空页面已有数据。
     *
     * ## 为什么能解决「精选页转圈」
     *
     * 实测：网络层清洗后每批 6-9 条几乎全拦（放行率 9.6%），
     * 列表空 → 快手客户端要等自己节奏（RECV 平均 3.1s、峰值 16-40s）
     * 才拉下一批 ⇒ 用户「等半天」。本函数在清洗发现「本批几乎全空」时
     * 主动调用宿主 load() ⇒ 快手立即追加下一页 ⇒ 转圈时间大幅缩短。
     *
     * ## 安全性
     *
     * · 主线程闸门（load 会改数据源，必须在主线程）
     * · 800ms 节流（lastLoadMoreTime，防止风暴）
     * · hasMore/isLoading 健康检查（快手自己的分页状态）
     * · 绝不 fallback refresh（用户硬性要求）
     */
    fun triggerSafeLoadMore(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            CfhState.handler.post { try { triggerSafeLoadMore() } catch (_: Throwable) {} }
            return true
        }
        val now = System.currentTimeMillis()
        if (now - CfhState.lastLoadMoreTime < 800) return false
        val inst = CfhState.knhbInst?.get()
        if (inst == null) {
            if (CfhState.safeLoadMoreDiag < 20) { CfhState.safeLoadMoreDiag++; Logger.evidence("REFILL-DIAG", "knhbInst=null，等待数据源出现") }
            return false
        }
        val target = findLoadTarget(inst)
        if (target == null) {
            if (CfhState.safeLoadMoreDiag < 20) { CfhState.safeLoadMoreDiag++; Logger.evidence("REFILL-DIAG", "无 load 目标（findLoadTarget=null）") }
            return false
        }
        val hasMore = try {
            val hm = target.javaClass.getMethod("hasMore"); hm.isAccessible = true
            hm.invoke(target) as? Boolean ?: true
        } catch (_: Throwable) { true }
        if (!hasMore) {
            if (CfhState.safeLoadMoreDiag < 20) { CfhState.safeLoadMoreDiag++; Logger.evidence("REFILL-DIAG", "hasMore=false（无可追加），等快手自行恢复") }
            return false
        }
        val isLoading = try {
            val il = target.javaClass.getMethod("isLoading"); il.isAccessible = true
            il.invoke(target) as? Boolean ?: false
        } catch (_: Throwable) { false }
        if (isLoading) {
            if (CfhState.safeLoadMoreDiag < 20) { CfhState.safeLoadMoreDiag++; Logger.evidence("REFILL-DIAG", "isLoading=true（请求在途），跳过本次") }
            return false
        }
        // ★★★ v13.18 停用主动 load()（2026-09-28 逆向实证）：
        //   findLoadTarget 找到的 dnh.q1（精选页请求器）不 extends hx0.b，
        //   load() 一调就 e=true 永久锁死请求器。全部主动 load() 触发
        //   停用 —— 干净池靠自产自销（REPOOL-SEED）+ 首页自然到达。
        // ★★★ v13.57 探测精选页数据源的加载方法名（2026-09-30 用户定调）：
        //   用户原话：「拦截率不是借口。拦截率再高发现页始终能刷出新视频，
        //             精选页刷不出视频就不对」
        //   —— 这个推理成立：拦截率高只解释「留下多少」，不解释「为什么两个
        //   页面表现相反」。真正的机制差异是**加载循环**：
        //     发现页每批 19 条 → 删剩几条 → 列表非空 → 能滚动 → 快手自动加载下一页
        //     精选页每批 6-9 条 → 删到剩 0-1 条 → 列表空 → 没有可滚动项 →
        //     快手**不再触发下一页** → 死锁
        //   修法 = 列表空时**主动触发精选页数据源的下一页加载**，不依赖滚动。
        //   但 v13.18 实测过「dnh.q1.load() 一调就锁死请求器」⇒ 不能盲调。
        //   先探测：把 target 上所有「无参 void」方法名打出来，看清再决定调哪个
        //   （和 v13.52 修 VM 方法名同一个思路，旧表全是过期混淆名）。
        probeLoadTargetMethods(target)
        if (CfhState.safeLoadMoreDiag < 20) { CfhState.safeLoadMoreDiag++; Logger.evidence("REFILL-DIAG", "v13.18 起不盲调 load()（dnh.q1 非 hx0.b 子类，load 会锁死请求器）—— v13.57 改为先探测方法名") }
        return false
    }

    /** v13.57 精选页数据源方法名探测限次 */
    @Volatile private var pageProbeLog = 0

    /**
     * ★★★ v13.64 自动拉取：替快手做一次「首页滑到底」（2026-09-30）
     *
     * ## 原理
     * 首页信息流是 RecyclerView（实证类名
     * `com.yxcorp.gifshow.mortise.widget.GeminiMortiseRecyclerView`），
     * 快手的「到底自动加载下一页」由**滚动位置**驱动。
     * ⇒ 直接对这个列表调 `smoothScrollToPosition(itemCount-1)`，
     * 快手自己就会发起下一页请求（用它的真实加载方法，不靠我们猜混淆名）。
     *
     * ## 与旧方案的区别
     * 旧方案（`arh.q1.load()` / VM 的 D0）都是**猜快手的内部方法名**：
     * 名字是混淆的，猜不中；调错还会锁死请求器。本方案只做「用户动作」，
     * 走快手自己的既定路径，不碰它的状态机。
     *
     * ## 安全边界
     * · 用户在首页时直接放弃（他自己在滑，我们插手会把画面弄乱）
     * · 1.5s 节流
     * · 全反射，任何环节拿不到就安静返回 false
     */
    private fun nudgeHomeFeedToBottom(): Boolean {
        val now = System.currentTimeMillis()
        if (now - CfhState.lastNudgeMs < 1500L) return false
        if (CfhState.lastChannelWasHome || now - CfhState.lastHomeChannelMs < 3000L) return false
        val rv = findHomeFeedList()
        if (rv == null) {
            if (CfhState.nudgeDiag < 20) {
                CfhState.nudgeDiag++
                Logger.evidence(
                    "AUTOPULL",
                    "★首页列表未找到 tracked=${CfhState.tracked?.javaClass?.simpleName ?: "null"}"
                )
            }
            return false
        }
        CfhState.lastNudgeMs = now
        return try {
            val adapter = rv.javaClass.getMethod("getAdapter").invoke(rv) ?: return false
            val n = adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int ?: return false
            if (n <= 0) return false
            rv.javaClass
                .getMethod("smoothScrollToPosition", Int::class.javaPrimitiveType)
                .invoke(rv, n - 1)
            Logger.evidence(
                "AUTOPULL",
                "★自动拉取：首页列表滚到底 itemCount=$n " +
                    "池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
            )
            true
        } catch (_: Throwable) { false }
    }

    /** v13.64 首页信息流列表定位（按实证类名找，找不到就放弃） */
    private fun findHomeFeedList(): Any? {
        val act = CfhState.tracked ?: return null
        val decor = try { act.window?.decorView } catch (_: Throwable) { null } ?: return null
        return walkForHomeFeed(decor, 0)
    }

    private fun walkForHomeFeed(v: android.view.View?, depth: Int): Any? {
        if (v == null || depth > 25) return null
        if (v.javaClass.name.contains("GeminiMortiseRecyclerView")) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val hit = walkForHomeFeed(v.getChildAt(i), depth + 1)
                if (hit != null) return hit
            }
        }
        return null
    }

    /**
     * ★★★ v13.57 探测精选页数据源的「无参 void」方法名（2026-09-30）
     *
     * ## 为什么只探测不调用
     * v13.18 实测：`dnh.q1.load()` 一调就让请求器 `e=true` **永久锁死**
     * （全部主动 load 触发因此被停用）。既然不知道当前版本哪个方法才是
     * 「加载下一页」，就不能挨个盲调 —— 先看清有哪些候选，再决定。
     *
     * ## 输出
     * `[PAGEPROBE] ★精选页数据源 <类名> 无参void方法(N)=a,b,c`
     * 拿到名字后就能对着它写精确的触发（而不是拿过期混淆名瞎试）。
     */
    private fun probeLoadTargetMethods(target: Any) {
        if (pageProbeLog >= 10) return
        try {
            val ms = target.javaClass.declaredMethods
                .filter { it.parameterTypes.isEmpty() && it.returnType == Void.TYPE }
                .map { it.name }.distinct()
            // 顺带把「有返回值」的候选也亮出来（分页方法常返回对象/布尔）
            val nonVoid = target.javaClass.declaredMethods
                .filter { it.parameterTypes.isEmpty() && it.returnType != Void.TYPE }
                .map { it.name + ":" + it.returnType.simpleName }.distinct()
            pageProbeLog++
            Logger.evidence(
                "PAGEPROBE",
                "★精选页数据源 ${target.javaClass.name} 无参void(${ms.size})=${ms.joinToString(",")} " +
                    "| 无参有返回(${nonVoid.size})=${nonVoid.take(20).joinToString(",")}"
            )
        } catch (_: Throwable) {}
    }

    /**
     * ★★★ v13.50 从发现页 VM 直接收割整屏内容 —— 采集漏的根治（2026-09-30）
     *
     * ## 问题（用户实证：「池子进了25条，可是精选页只有1条视频」的采集侧根因）
     * 所有既有采集点（网络层 / HomeFeedResponse / 数据源列表）拿到的都只有
     * **1 条**（POOLALL 实证 `遍历=1 入池=0`）—— 快手是**逐条**把内容喂进
     * 各个 hook 点的，没有任何一个点能看到「一屏 9-16 条」。
     * 而 SRCELEM 早就探测到完整列表就在发现页 VM 的字段里：
     *   `真源 vm.i size=9 qpWrapped=true`、`vm.h.f.N size=16`
     * ⇒ 用户滑十屏 ≈ 上百条内容，我们能收的却只有零星几条。
     *
     * ## 修复：直接读发现页 VM 的列表字段
     * 这就是用户说的「看主页的数据从哪来的，就从那里拉数据到池子」——
     * 主页数据的**真实存放处**就是这些 VM 字段，不是再去调一次 load()。
     * 遍历 VM 及其父类的字段，凡是 List 的就把元素交给 noteCleanAll
     * （内部：findQpInObject 提取 QPhoto → judgeWhitelist 判白 → noteClean 入池）。
     *
     * ## 安全
     * · 非内容列表（监听器/回调）里的元素提不出 QPhoto ⇒ noteCleanAll 自然跳过
     * · 只收 WHITE（noteCleanAll 内判定）⇒ 脏内容不会混入
     * · 全程 try-catch，绝不抛出影响宿主
     *
     * @return 本次入池条数
     */
    fun harvestVmLists(vm: Any?): Int {
        if (vm == null) return 0
        // ★★★ v13.54 递归收割（2026-09-30 SRCELEM 实证「采集漏」根治）：
        //   ## 上一版为什么只捞到 9 条
        //   v13.50 只扫 VM 的**第一层**字段 —— 而真正的内容列表在**嵌套字段**里：
        //     SRCELEM 实证 `真源 vm.j.S.n size=26 qpWrapped=true`
        //   路径是 vm → 字段 j → 字段 S → 字段 n(列表)，第 **3** 层。
        //   只扫一层 ⇒ 只捞到当前屏 9 条，预加载进来的另外 17 条**从未被碰过**
        //   ⇒ 池永远只有一屏的量、精选页补几次就空。
        //   ## 本版做法
        //   广度优先下钻（深度 ≤4），凡遇到非空 List 就交给 noteCleanAll
        //   （内部提取 QPhoto → 判白 → 入池）；跳系统/框架类避免遍历爆炸。
        //   ## 成本控制
        //   · 单次最多访问 [HARVEST_MAX_VISITS] 个对象
        //   · 500ms 节流（本函数会被网络层每批触发，必须自限）
        //   · 已访问对象用 IdentityHashMap 去重，环路安全
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastHarvestMs < 500L) return 0
        lastHarvestMs = nowMs
        var total = 0
        var lists = 0
        var elems = 0
        var visits = 0
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val depthMap = java.util.IdentityHashMap<Any, Int>()
        try {
            val queue = ArrayDeque<Any>()
            queue.add(vm); depthMap[vm] = 0
            while (queue.isNotEmpty() && visits < HARVEST_MAX_VISITS) {
                val obj = queue.removeFirst()
                if (!seen.add(obj)) continue
                visits++
                val depth = depthMap[obj] ?: 0
                var c: Class<*>? = obj.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 6) {
                    for (f in c.declaredFields) {
                        try {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            f.isAccessible = true
                            val v = f.get(obj) ?: continue
                            if (v is List<*>) {
                                if (v.isEmpty()) continue
                                lists++
                                elems += v.size
                                total += try { CfhState.noteCleanAll(v) } catch (_: Throwable) { 0 }
                            } else if (depth < HARVEST_MAX_DEPTH && shouldDescend(v)) {
                                if (!seen.contains(v)) {
                                    depthMap[v] = depth + 1
                                    queue.add(v)
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
            }
        } catch (_: Throwable) {}
        if (total > 0 || (harvestLog < 40 && lists > 0)) {
            harvestLog++
            Logger.evidence(
                "HARVEST",
                "★VM 递归收割 列表=$lists 元素=$elems 入池=$total 访问=$visits " +
                    "池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
            )
        }
        return total
    }

    /**
     * v13.54 是否下钻到该对象。
     *
     * ★ v13.55 放宽（2026-09-30）：上一版按类名挡掉 android./java./kotlin. 等，
     *   结果**把通往内容列表的路径挡掉了** —— 实测访问 300 个对象、收割 295 个
     *   元素，全是系统监听器列表（`mConfigChangeListeners` 之类），
     *   真正的内容列表（SRCELEM 实测 `vm.j.S.n size=26`）根本没被走到。
     *   对照 CfhWash.probeSourceDirty（能走到内容列表的参考实现）：它**无条件
     *   下钻**，只靠访问总数限流。本函数改为同样的策略 —— 只挡掉三类必然
     *   无内容且会引爆对象图的东西：View（UI 树）、容器本身、字符串。
     */
    private fun shouldDescend(v: Any): Boolean {
        if (v is Collection<*> || v is Map<*, *> || v is CharSequence) return false
        if (v is android.view.View || v is android.content.Context) return false
        val cl = v.javaClass
        if (cl.isArray || cl.isPrimitive) return false
        return true
    }

    /** v13.50 收割日志限次 */
    @Volatile private var harvestLog = 0
    /** v13.54 收割节流时间戳 */
    @Volatile private var lastHarvestMs = 0L
    /**
     * v13.55 单次收割最多访问对象数。
     * 300 实测不够（只走到系统监听器就停了），对齐 CfhWash.probeSourceDirty 的
     * 2000 量级；配合 500ms 节流，单次成本可控（实测 300 次访问耗时在毫秒级）。
     */
    private const val HARVEST_MAX_VISITS = 1500
    /** v13.54 递归深度上限（内容列表在 vm.j.S.n 第 3 层，留一层余量） */
    private const val HARVEST_MAX_DEPTH = 4
    /** v13.52 VM 方法名探测（只打一次） */
    @Volatile private var vmProbeDone = false

    /**
     * ★★★ v13.43 池水位保障 —— 用户方案（2026-09-30）：
     * ```
     * 「池子少于50就一直拉取」
     * ```
     * 池 < [POOL_TARGET] 就触发一次主动拉主页数据（节流由 triggerHomeLoad 内部
     * 的 lastHomeLoadTime 负责，所以「一直拉取」= 每次有机会就拉一次，直到池够）。
     *
     * 调用点（两处高频天然触发点，不额外起定时器）：
     *   · refillFromCleanPool 开头（精选页每次补位）
     *   · 网络层每批解析统计处（用户滑动时每批都过）
     * 二者覆盖了「精选页正在消耗池」的全部时机 —— 池被抽的瞬间就会补拉，
     * 不需要空转的定时器。
     */
    const val POOL_TARGET = 50

    /**
     * v13.67 首页 VM 按下标取数的节流（2026-09-30）。
     *
     * sweep 一轮 = 24 格 × ≤5 个候选方法 ≈ 120 次反射调用，而 ensurePoolSupply
     * 被「每个网络批次解析」触发（用户滑动时每秒好几批）—— 不节流会把主线程
     * 的反射开销堆起来。2 秒一次既能跟上滑动节奏，又不会打爆。
     */
    @Volatile private var lastHomeHarvestAt = 0L
    private const val HOME_HARVEST_INTERVAL_MS = 2000L

    /** v13.67 当前页 VM 按下标取数的节流（±4 窗口 = 9 格，成本小于 sweep，仍要限流） */
    @Volatile private var lastCurHarvestAt = 0L
    private const val CUR_HARVEST_INTERVAL_MS = 800L

    /**
     * v13.67 `harvestVmLists`（扫字段兜底）的节流。
     *
     * 必要性来自 v13.66 的实测行为：那条路**恒返回 0** ⇒ 每个网络批次都会执行到它，
     * 而它单次最多遍历 [HARVEST_MAX_VISITS]=1500 个对象。它已被 KWSEARCH 证明
     * 结构性无效（内容不在任何可达字段里），留着只作兜底，不必每批都跑。
     */
    @Volatile private var lastVmListHarvestAt = 0L
    private const val VMLIST_HARVEST_INTERVAL_MS = 2000L

    /** v13.67 非主线程 → 主线程的补投标志（防止每个批次都 post，刷爆主线程队列） */
    @Volatile private var harvestPosted = false

    fun ensurePoolSupply(): Boolean {
        // ★★★ v13.74 池持久化恢复（异步、幂等、不占主线程）：
        //   挂在这里是因为本函数「一启动就会反复触发」，是最早的可用时机。
        //   冷启动池恒为 0 是整条死锁链的起点（见 CfhPoolStore 头注释）。
        try { CfhPoolStore.loadOnce() } catch (_: Throwable) {}
        val n = synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }
        if (n >= POOL_TARGET) return false
        // ★ v13.65 入口留痕（2026-09-30）：上一轮实测 AUTOPULL 零输出 ⇒ 必须先
        //   分清「本函数根本没被调用」还是「调了但定位不到首页列表」。
        if (CfhState.supplyDiag < 30) {
            CfhState.supplyDiag++
            Logger.evidence("SUPPLY", "★补池触发 池=$n")
        }
        // ★★★ v13.50 先直接收割发现页 VM 的整屏列表（最全、最直接的来源）：
        //   各 hook 点都只能看到 1 条（POOLALL 实证 遍历=1），而发现页 VM
        //   字段里就有整屏 9-16 条（SRCELEM 实证 vm.i size=9）。收割成功就
        //   不必再触发 load()（少一次网络往返、少一份副作用）。
        // ★★★ v13.67 按下标取数 —— **首页 VM 优先**（2026-09-30 修 v13.66 死角）
        //   ## v13.66 为什么零产出（实证复盘，勿重犯）
        //   v13.66 的 harvestByIndex 只认 `vmRef`（最后注册的赢）= **当前页 VM**。
        //   用户在精选页 ⇒ 取到的就是精选页当前屏，而精选页实测 ~100% 脏
        //   （TTPPARSE 收=8 放行=0 / 收=9 放行=0）⇒ 入池恒 0 ⇒ 补位断粮。
        //   首页发现页干净率 71~81%（放行22/26、放行16/21）⇒ **它才是池的供给源**。
        //   ## 做法（三条，缺一不可）
        //   ① 先对 `homeVmRef`（首页 VM）按下标取数，且用 **sweep 模式**
        //      （不看当前位置、直接从 0 扫）—— 首页列表是「已加载整屏」，
        //      一次收完才是池该有的量；且后台 VM 的位置服务可能给 -1。
        //   ② 再对当前页 VM 走窗口模式兜底（用户在首页时两者是同一个 VM）。
        //   ③ 两种失败都在 IDXHARVEST / IDXHARVEST-HOME 上**分开**留痕
        //      （v13.66 只在入池>0 时打 ⇒ 三种失败全是零输出、无法区分）。
        //   ## 节流
        //   sweep 一轮 = 24 格 × ≤5 个候选 ≈ 120 次反射，不能每个网络批次都跑。
        // ★★★ v13.67 反射调宿主 VM **必须主线程**（与 triggerHomeLoad 同一条约束，
        //   见其首行注释「反射调宿主 VM 必须主线程」）。
        //   ensurePoolSupply 是从 CfhTtpParse 的**网络解析路径**调进来的，而 Gson
        //   解析未必在主线程 —— v13.66 的 harvestByIndex 正是走这条**没有闸门**的路。
        //   这里补上闸门：非主线程就 post 一次（用 posted 标志防刷屏），本轮直接返回。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!harvestPosted) {
                harvestPosted = true
                CfhState.handler.post {
                    harvestPosted = false
                    try { ensurePoolSupply() } catch (_: Throwable) {}
                }
            }
            return false
        }
        val nowMs = System.currentTimeMillis()
        // ① 首页 VM（干净率 71~81%）= 池的主供给源：sweep 模式、从 0 扫
        try {
            val homeVm = CfhState.homeVmRef
            if (homeVm != null && nowMs - lastHomeHarvestAt > HOME_HARVEST_INTERVAL_MS) {
                lastHomeHarvestAt = nowMs
                if (CurrentPhotoHook.harvestFrom(homeVm, 32, "HOME", sweep = true) > 0) return true
            }
        } catch (_: Throwable) {}
        // ② 当前页 VM 兜底（用户在首页时与 ① 是同一个 VM；在精选页时基本全脏）
        try {
            val curVm = CurrentPhotoHook.currentVmRef()
            if (curVm != null && nowMs - lastCurHarvestAt > CUR_HARVEST_INTERVAL_MS) {
                lastCurHarvestAt = nowMs
                if (CurrentPhotoHook.harvestFrom(curVm, 4, "") > 0) return true
            }
        } catch (_: Throwable) {}
        // ③ 扫字段兜底（KWSEARCH 已用决定性实证否掉这条路，仅保留 + 独立节流）：
        //   v13.66 里 ①（旧 harvestByIndex）**恒返回 0** ⇒ 每个网络批次都会落到这里，
        //   而 harvestVmLists 单次最多遍历 1500 个对象 —— 那是实打实的主线程开销。
        if (nowMs - lastVmListHarvestAt > VMLIST_HARVEST_INTERVAL_MS) {
            lastVmListHarvestAt = nowMs
            val vm = CfhState.homeVmRef ?: CfhState.vmRef
            val got = try { harvestVmLists(vm) } catch (_: Throwable) { 0 }
            if (got > 0) return true
        }
        // ★★★ v13.64 自动拉取（2026-09-30 用户质问「为什么还要手动在首页滑动
        //   养池？为什么不能自动入池？」）：
        //   ## 为什么之前所有"主动拉"都失败
        //   我们一直在找快手的「加载方法」——`arh.q1.load()`（12 次全在途、0 条
        //   回来）、VM 的无参 void 方法（D0 调了 30 次同样无效）。方法名是混淆的，
        //   猜不中；而且写错会锁死请求器（v13.18 实测）。
        //   ## 换个思路：不去调"加载"，而是替快手做一次它认识的**用户动作**
        //   首页信息流是 RecyclerView（实证类名 `GeminiMortiseRecyclerView`），
        //   快手的"到底自动加载下一页"由**滚动**驱动。⇒ 我们直接对那个列表
        //   调一次 `smoothScrollToPosition(itemCount-1)`，快手自己就会去拉下一页。
        //   这是真自动：用户不需要碰首页。
        //   ## 安全边界
        //   · 用户在首页时**绝不动它**（他自己在滑，我们插手反而乱）
        //   · 1.5s 节流，避免连滚
        //   · 全程反射调用，拿不到就安静放弃
        try { if (nudgeHomeFeedToBottom()) return true } catch (_: Throwable) {}
        // v13.57 兜底：仍然保留方法名探测（拿到真实名字后再写精确触发）
        try { triggerSafeLoadMore() } catch (_: Throwable) {}
        return try { triggerHomeLoad() } catch (_: Throwable) { false }
    }

    /**
     * ★★★ v13.43 主动拉主页（发现页）数据 —— 用户方案（2026-09-30）：
     * ```
     * 「看主页的数据从哪来的，就从那里拉数据到池子，池子少于50就一直拉取」
     * ```
     *
     * ## 主页数据的入口（已实证）
     * CfhFeedHook 首页 Fragment 分支（NASA 探测）拿到：
     *   · `homeVmRef` = 主页 SlidePlayViewModel
     *   · `homeDsRef` = 它的 `getDataSource()` 返回值
     * 本函数从 homeDsRef（缺失时降级 homeVmRef）找带 `load()` 的对象并调用 ——
     * 那就是「主页往下滑时继续向服务器要数据」的同一个动作。
     *
     * ## 与 triggerSafeLoadMore 的区别（重要）
     * triggerSafeLoadMore 用的是 `knhbInst`（当前页面数据源）→ 在精选页时
     * 拉回来的是精选页 AI 短剧脏池，且 v13.18 实证 dnh.q1 的 load() 会锁死
     * 请求器。本函数**只碰主页引用**，与当前页面无关。
     *
     * ## 安全约束
     * · 主线程闸门（反射调宿主 VM 必须主线程）
     * · 独立节流 lastHomeLoadTime（默认 1200ms，防把请求器打爆）
     * · **绝不 fallback refresh**（用户硬性要求：刷新会清空页面数据）
     * · isLoading / hasMore 检查，在途时不重复拉
     * · 任何失败仅打证据，无副作用
     *
     * @param minIntervalMs 两次拉取的最小间隔
     */
    fun triggerHomeLoad(minIntervalMs: Long = 1200L): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            CfhState.handler.post { try { triggerHomeLoad(minIntervalMs) } catch (_: Throwable) {} }
            return true
        }
        val now = System.currentTimeMillis()
        if (now - CfhState.lastHomeLoadTime < minIntervalMs) return false
        // ★★★ v13.58 优先用**首页数据源强引用**（2026-09-30）：
        //   homeDsRef/homeVmRef 只在 HomeFeedResponse 通道赋值 —— 而首页发现页
        //   的数据主要走 knhb T0/E1（CfhFeedHook:473 注释实证：WHITEPASS 895 条
        //   vs HomeFeedResponse 几乎不动）⇒ 那两个引用长期是 null，拉取链断在
        //   开头。`homeSrcStrong` 是 T0/E1 时抓到的数据源本体（强引用，不会被
        //   GC），它才是可靠的首页入口。
        val src = CfhState.homeSrcStrong
        val ds = src ?: CfhState.homeDsRef
        val vm = CfhState.homeVmRef
        if (ds == null && vm == null) {
            if (CfhState.homeReqDiag < 20) {
                CfhState.homeReqDiag++
                Logger.evidence("HOMEPULL", "★主页引用未就绪（homeDsRef/homeVmRef 均 null）—— 等主页 Fragment 出现")
            }
            return false
        }
        // 先从 ds 找 load 目标，再从 vm 找
        val target = (ds?.let { runCatching { findLoadTarget(it) }.getOrNull() })
            ?: (vm?.let { runCatching { findLoadTarget(it) }.getOrNull() })
        if (target == null) {
            if (CfhState.homeReqDiag < 20) {
                CfhState.homeReqDiag++
                Logger.evidence("HOMEPULL", "★主页 load 目标未找到 ds=${ds?.javaClass?.name ?: "-"} vm=${vm?.javaClass?.name ?: "-"}")
            }
            return false
        }
        val hasMore = try {
            val hm = target.javaClass.getMethod("hasMore"); hm.isAccessible = true
            hm.invoke(target) as? Boolean ?: true
        } catch (_: Throwable) { true }
        if (!hasMore) {
            if (CfhState.homeReqDiag < 20) { CfhState.homeReqDiag++; Logger.evidence("HOMEPULL", "★主页 hasMore=false（主页也拉不到新页了）") }
            return false
        }
        // ★★★ v13.51 优先试**首页 VM 分页方法**（2026-09-30 用户质问「从首页
        //   拉取了多少？」）：
        //   dataSource 的 load() 实测无效 —— 调了 12 次，要么卡在 isLoading=true
        //   永不返回，要么拉起后 0 条数据回池。所以不再等它，直接试 VM 上的
        //   分页/刷新方法（快手内部真正驱动分页的那批混淆名）。
        //   放在 isLoading 检查**之前**：正因为它一直卡在在途，才更不能被它挡住。
        if (tryHomeVmPaging(vm, now)) return true
        val isLoading = try {
            val il = target.javaClass.getMethod("isLoading"); il.isAccessible = true
            il.invoke(target) as? Boolean ?: false
        } catch (_: Throwable) { false }
        if (isLoading) {
            if (CfhState.homeReqDiag < 20) { CfhState.homeReqDiag++; Logger.evidence("HOMEPULL", "★主页 load 在途（isLoading=true），下次再拉") }
            return false
        }
        return try {
            val m = target.javaClass.getMethod("load")
            m.isAccessible = true
            m.invoke(target)
            CfhState.lastHomeLoadTime = now
            // ★★★ v13.45 开拉取时间窗：窗口内到达网络层的 WHITE 内容即本次
            //   主页拉取的响应 ⇒ 登记进池（见 CfhState.homePullWindowUntil）。
            CfhState.homePullWindowUntil = now + 3000L
            CfhState.homeReqLog++
            if (CfhState.homeReqLog <= 30) {
                Logger.evidence(
                    "HOMEPULL",
                    "★主动拉主页数据 #${CfhState.homeReqLog} target=${target.javaClass.name} 池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                )
            }
            true
        } catch (t: Throwable) {
            // ★★★ v13.51 数据源 load() 失败/无效时，改试**首页 VM 自己的分页方法**
            //   （2026-09-30 用户质问「从首页拉取了多少？」—— 实测 arh.q1.load()
            //   调了 12 次全部卡在 isLoading=true、0 条数据回来，这条路是死的）。
            //   triggerRefresh 里已经在用这批混淆名（v0/B1/C1/E1/K1/W0/X0/Y0/z0/y0
            //   /refresh/loadMore）驱动快手自己的刷新 —— 那是快手内部真正驱动
            //   分页的方法，比数据源的 load() 靠谱得多。这里同样挨个试一遍，
            //   只调**无参且返回 void** 的（避免把带参方法调坏）。
            if (tryHomeVmPaging(vm, now)) return true
            if (CfhState.homeReqDiag < 20) {
                CfhState.homeReqDiag++
                Logger.evidence("HOMEPULL", "★主页 load() 调用失败：${t.javaClass.simpleName}")
            }
            false
        }
    }

    /**
     * ★★★ v13.51 用首页 VM 自己的分页方法拉数据（2026-09-30）
     *
     * 数据源 `arh.q1.load()` 实测无效（12 次调用全卡在途、0 条回池），
     * 改用 VM 上的驱动方法。只调**无参 + 返回 void** 的方法，
     * 且名字命中快手分页/刷新的常见混淆名或语义名 —— 调错也不会有副作用
     * （无参无返回的方法不会破坏状态机）。
     */
    private fun tryHomeVmPaging(vm: Any?, now: Long): Boolean {
        if (vm == null) return false
        // ★★★ v13.52 方法名探测（2026-09-30）：VM 分页方法一个都没调成 ⇒ 需要
        //   看清 homeVmRef 到底是哪个类、有哪些「无参 + 返回 void」的方法可调。
        //   只打一次，输出全部候选名，下次直接照名字试。
        if (!vmProbeDone) {
            vmProbeDone = true
            try {
                val ms = vm.javaClass.declaredMethods
                    .filter { it.parameterTypes.isEmpty() && it.returnType == Void.TYPE }
                    .map { it.name }
                    .distinct()
                Logger.evidence(
                    "VMPROBE",
                    "★主页 VM=${vm.javaClass.name} 无参void方法(${ms.size})=${ms.joinToString(",")}"
                )
            } catch (_: Throwable) {}
        }
        // ★★★ v13.53 用**探测到的真实方法名**（2026-09-30 VMPROBE 实证）：
        //   旧表（v0/B1/C1/E1/K1/W0/X0/Y0/z0/y0…）是**过期版本**的混淆名，
        //   在 50388 当前版本上**一个都不存在** —— 这也是 triggerRefresh
        //   一直无效的原因（它用的是同一批旧名）。
        //   本机 SlidePlayViewModel 的无参 void 方法实测只有 5 个：
        //     D0, E0, G0, O0, p
        //   先试前 4 个（`p` 疑似生命周期方法，避免误调暂停播放），
        //   再兜底试语义名。
        val names = arrayOf(
            "D0", "E0", "G0", "O0",
            "refresh", "loadMore", "reload", "loadNext", "requestNext"
        )
        for (name in names) {
            val m = CfhProbe.cachedMethod(vm.javaClass, name) ?: continue
            if (m.parameterTypes.isNotEmpty()) continue
            if (m.returnType != Void.TYPE) continue
            try {
                m.invoke(vm)
                CfhState.lastHomeLoadTime = now
                CfhState.homePullWindowUntil = now + 3000L
                CfhState.homeReqLog++
                Logger.evidence(
                    "HOMEPULL",
                    "★主页 VM 分页 #${CfhState.homeReqLog} 方法=$name 池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                )
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * ★★★ 触发首页请求器 load()（2026-09-28「请求服务器数据的动作」用户方案）。
     *
     * ## 与 triggerSafeLoadMore 的本质区别
     *
     * triggerSafeLoadMore 调的是 `findLoadTarget(knhbInst)` 找到的 **dnh.q1**
     * （精选页数据源）⇒ load() 拉回来的是**精选页的 AI 短剧脏池**（REFILL-DIAG
     * 全是 isLoading=true + 续拉后 STAT 仍 全拦 实证）。这条路是死的。
     *
     * 用户原话：「快手应该是有个请求服务器数据的一个动作啊」
     *          「首页发现页往下滑，视频数据源源不断的出来」
     * —— 首页发现页的信息流**有自己持续向服务器要数据的分页请求器**。
     * 本函数在精选页池空时，触发**首页请求器**的 load()：
     *   首页请求器.load() → 快手向服务器要发现页下一页
     *     → HomeFeedResponse.getItems → hookFeedResponse 登记 WHITE 进干净池
     *     → 精选页下一批清洗时 refillFromCleanPool 从池补位
     *
     * ## 保存的是「首页请求器」，不是任意请求器
     *
     * homeReqRef 由「load 触发 → HomeFeedResponse 响应到达」时序配对晋升
     * （hookHomeRequester 暂存 pendingHomeReq，hookFeedResponse 晋升），
     * 必然只有首页发现页的请求器 —— 精选/详情页的 load() 永不污染它。
     *
     * ## 安全性与 triggerSafeLoadMore 相同
     * · 主线程闸门 / 800ms 节流（独立 lastHomeLoadTime）
     * · 绝不 fallback refresh（用户硬性要求：刷新会清空页面数据）
     * · 失败仅打证据，无副作用
     */
    // ★★★ v13.38 删除 triggerHomeLoadMore（2026-09-30 用户定案）：
    //   池是**共享的**（cleanPool 唯一）—— 首页滑到的干净内容本就自动进池，
    //   不存在「首页池没接到精选页」的问题。该函数（104 行：homeDsRef 直调
    //   load() → findLoadTarget → HomeReqRef → triggerSafeLoadMore 降级链）
    //   是早期「两池不同源」错误假设的产物，纯多余且带副作用
    //   （触发 load 可能弄坏请求器、拉回精选页 AI 短剧）。
    //   调用点已全部移除（CfhTtpParse 池空分支 v13.38、installVmList 池水位
    //   v13.36）。保留此注释作为删除留痕，函数本体不再存在。
}
