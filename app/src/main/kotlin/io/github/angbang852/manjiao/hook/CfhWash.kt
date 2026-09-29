package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.RateLimiter
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第七步后：CfhWash 专注真源二层清洗（2026-09 S3）。
// VM 真源（V0() 快照的真源）二层清洗 + 槽位包装链提 QP；
// 写入路径监控与反查见 CfhWatch。不持有状态——一律读 CfhState。
object CfhWash {
    fun filterVmLists(obj: Any) {
        // ★★ 调用留痕（2026-09-24，查「中间层清洗为什么停跑」）。
        //
        //   实测：快照删除 `ret 删12条` 每 5 秒持续，
        //   而中间层清洗 `sanitize deep:l.a removed 1` 最后一次在 10:57:07，
        //   之后 5 分钟零记录 —— 但 `filterVmLists` 明明有 6 处调用点。
        //
        //   本探针记录**每一次进入**及其分支走向（liveTop 早退 / 节流跳过 / 实际执行），
        //   据此判断是被节流饿死、还是调用点根本没触发。
        if (CfhState.washEnterCount < 300) {
            CfhState.washEnterCount++
            val gate = when {
                CfhState.liveTop -> "liveTop早退"
                else -> "进入"
            }
            Logger.evidence("WASH-IN", "$gate vm=${obj.javaClass.simpleName}")
        }
        if (CfhState.liveTop) return
        val now = System.currentTimeMillis()
        // ★★ 节流语义修正（实证 2026-09 probe7）：原实现「距上次 <500ms 即 return」，
        // 而 ret 路径在脏项驻留时会以 <200ms 间隔高频回调（实测 262 次 / 30 秒），
        // 导致真源清洗几乎永远进不来 —— 实测 deep: 清洗只在启动瞬间跑了 2 次
        // （40.391 / 40.401），此后 33 秒全程静默，真源 6 条纹丝不动，
        // V0()/H0()/E/F0 反复重建把同一脏项送回副本（vm lret 恒为 "6 -> 5"），
        // 屏幕表现为「同一位置的脏内容换成另一条脏内容」（用户所报现象）。
        // 修正：仍做 500ms 节流防抖，但「上一轮确实清掉了脏项」时立即重新武装，
        // 保证真源收敛（清到干净为止），而不是被高频调用饿死。
        // 修正：上一轮「确实清掉了脏项」时立即重新武装（不等 500ms），保证真源收敛；
        // 上一轮「没清掉东西」（已收敛/无可清）时仍按 500ms 节流防抖空转。
        val converged = CfhState.lastCleanRemoved == 0
        if (converged && now - CfhState.lastFilterVmListsAt < 500) {
            // ★ 周期清洗兜底（2026-09-24）：即使被节流吞掉，
            //   也要让周期任务知道「当前该清谁」，否则它无从下手。
            if (lastCleanTarget?.get() == null) lastCleanTarget = java.lang.ref.WeakReference(obj)
            if (CfhState.washSkipCount < 60) {
                CfhState.washSkipCount++
                Logger.evidence("WASH-SKIP", "节流跳过（距上次 ${now - CfhState.lastFilterVmListsAt}ms）")
            }
            return
        }
        CfhState.lastFilterVmListsAt = now
        // ★ 记录清洗目标供周期任务复用（2026-09-24）
        lastCleanTarget = java.lang.ref.WeakReference(obj)
        // 一次性探针：确认 vmRef 状态与真源清洗是否激活（查「大青蜜桃」在屏滞留）
        if (!CfhState.vmRefProbeDone) { CfhState.vmRefProbeDone = true; Logger.once("vmprobe.armed", "VMPROBE filterVmLists armed: vm=${obj.javaClass.name}") }
        CfhState.pendingCleanObj.set(obj)
        if (CfhState.cleanDrainArmed.compareAndSet(false, true)) {
            if (CfhState.washSubmitCount < 60) {
                CfhState.washSubmitCount++
                Logger.evidence("WASH-SUBMIT", "提交清洗任务")
            }
            CfhState.cleanExecutor.execute {
                CfhState.cleanDrainArmed.set(false)
                val target = CfhState.pendingCleanObj.getAndSet(null)
                if (target == null) {
                    if (CfhState.washNullCount < 40) {
                        CfhState.washNullCount++
                        Logger.evidence("WASH-NULL", "★任务取到 null → 本轮清洗被丢弃")
                    }
                    return@execute
                }
                filterVmListsInner(target)
            }
        } else {
            if (CfhState.washDropCount < 40) {
                CfhState.washDropCount++
                Logger.evidence("WASH-DROP", "★已有任务在排队 → 本次调用被丢弃（pendingCleanObj 被覆盖）")
            }
        }
    }
    /**
     * 主 feed 列表搜索（2026-09-24）—— 公开入口，供 VM 建立时调用。
     *
     * ★ 要解决的问题：实测模块能碰到的列表全是边角
     *   （`lret H`=3 条 / `l.a`=5 / `h.j`=8 / `l.c`=10），
     *   而用户能刷几百条内容 —— **承载屏幕内容的主列表从未被接上**。
     *   这就是「AI 视频拦不住」的根源：改的都不是地方。
     *
     * 做法：从 VM 出发广度遍历（深度 4），列出所有尺寸 ≥ 8 的 List
     * 及其「字段路径 + 尺寸 + 元素类名」。主 feed 列表的特征就是**尺寸大**。
     *
     * 只读；每进程一次。
     */
    fun searchMainList(obj: Any) {
        if (CfhState.mainListSearched) return
        CfhState.mainListSearched = true
        Logger.safe("searchMainList") {
            val found = ArrayList<String>()
            val seen = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
            )
            val queue = ArrayDeque<Array<Any>>()
            queue.add(arrayOf(obj, "vm", 0))
            seen.add(obj)
            var visited = 0
            while (queue.isNotEmpty() && visited < 5000) {
                val node = queue.removeFirst()
                val o = node[0]; val path = node[1] as String; val d = node[2] as Int
                visited++
                if (d > 4) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v is List<*>) {
                                if (v.size >= 8) {
                                    val e0 = v.firstOrNull()
                                    found.add(
                                        "$path.${f.name}(sz=${v.size},hc=${System.identityHashCode(v)}," +
                                            "首=${e0?.javaClass?.name?.substringAfterLast('.') ?: "null"})"
                                    )
                                }
                            } else {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && v !is android.view.View &&
                                    seen.add(v)
                                ) {
                                    queue.add(arrayOf(v, "$path.${f.name}", d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                }
            }
            Logger.evidence(
                "MAINLIST",
                "遍历 $visited 对象，尺寸≥8 的列表 ${found.size} 个: " + found.joinToString(" | ")
            )
            // ★★★ 按「元素含 QPhoto」重新搜索（2026-09-24）。
            //
            //   上一步按「尺寸大」找是**错的** —— 找到的 `vm.f.h.c`(42条)
            //   元素全是 `LiveWeakNetworkRecordInfoManager` 之类的功能管理器，
            //   可提 QP=10 但**判脏=0**，不是内容列表。
            //
            //   正确特征：**元素能提取到 QPhoto**（那才是视频/图文条目）。
            //   故第二次遍历改为：列出所有「能提到 QPhoto 的列表」，
            //   按「可提数」降序 —— 能提最多的那个就是主 feed 列表。
            try {
                val qpLists = ArrayList<Triple<String, Int, Int>>()  // 路径, 尺寸, 可提数
                val seen2 = java.util.Collections.newSetFromMap(
                    java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
                )
                val q2 = ArrayDeque<Array<Any>>()
                q2.add(arrayOf(obj, "vm", 0))
                seen2.add(obj)
                var vis2 = 0
                while (q2.isNotEmpty() && vis2 < 3000) {
                    val node = q2.removeFirst()
                    val o = node[0]; val path = node[1] as String; val d = node[2] as Int
                    vis2++
                    if (d > 4) continue
                    var c: Class<*>? = o.javaClass
                    var lvl = 0
                    while (c != null && c != Any::class.java && lvl < 3) {
                        val cc: Class<*>? = c
                        for (f in (cc ?: break).declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            try {
                                f.isAccessible = true
                                val v = f.get(o) ?: continue
                                if (v is List<*>) {
                                    if (v.isNotEmpty()) {
                                        var wq = 0
                                        for (e in v) {
                                            if (e == null) continue
                                            if (CfhState.qpClassRef?.isInstance(e) == true ||
                                                CfhProbe.findQpInObject(e) != null) wq++
                                            if (wq >= 3) break
                                        }
                                        if (wq >= 3) {
                                            // ★ 统计信息随列表一起存（2026-09-24）：
                                            //   之前只存路径字符串，回取时因嵌套/继承字段
                                            //   解析失败（CANDIDATES 输出为空）。
                                            //   现在把**列表引用本身**带回，直接遍历，
                                            //   不再依赖路径重建。
                                            var dirtyN = 0
                                            for (e in v) {
                                                if (e == null) continue
                                                val q = if (CfhState.qpClassRef?.isInstance(e) == true) e
                                                        else CfhProbe.findQpInObject(e)
                                                if (q != null && (try { CfhDecide.shouldFilterContent(q) } catch (_: Throwable) { false })) dirtyN++
                                            }
                                            val e0 = v.firstOrNull()
                                            val cap0 = try { CfhUtil.readCaption(e0) } catch (_: Throwable) { null }
                                            qpLists.add(
                                                Triple(
                                                    "$path.${f.name}(sz=${v.size} 脏=$dirtyN " +
                                                        "首=${e0?.javaClass?.simpleName ?: "null"} " +
                                                        "首文案=\"${cap0?.take(12) ?: "-"}\")",
                                                    v.size,
                                                    wq
                                                )
                                            )
                                        }
                                    }
                                } else {
                                    val vn = v.javaClass.name
                                    if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                        !vn.startsWith("kotlin.") && v !is android.view.View &&
                                        seen2.add(v)
                                    ) {
                                        q2.add(arrayOf(v, "$path.${f.name}", d + 1))
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                        c = cc?.superclass; lvl++
                    }
                }
                qpLists.sortByDescending { it.second }
                Logger.evidence(
                    "QPLISTS",
                    "含 QPhoto 的列表 ${qpLists.size} 个: " +
                        qpLists.take(14).joinToString(" | ") { it.first }
                )
                // ★★ 候选列表逐一验证（2026-09-24）：
                //   把「名字最像内容列表」的几个的**逐元素内容**打出来，确认哪个是真的。
                //   判据：元素能提到 QPhoto 且**能读到文案**（内容条目才有文案）。
                try {
                    val f2 = Reflect.readAny(obj, "f") ?: return@safe
                    val g2 = Reflect.readAny(f2, "g") ?: return@safe
                    val g3 = Reflect.readAny(g2, "g") ?: return@safe
                    val mq = Reflect.readAny(g3, "mQPhotos") as? List<*> ?: return@safe
                    val sb = StringBuilder()
                    var idx = 0
                    for (e in mq) {
                        if (idx >= 6) break
                        if (e == null) { idx++; continue }
                        val q = if (CfhState.qpClassRef?.isInstance(e) == true) e
                                else CfhProbe.findQpInObject(e)
                        val cap = q?.let { CfhUtil.readCaption(it) }
                        val dirty = q?.let { try { CfhDecide.shouldFilterContent(it) } catch (_: Throwable) { false } }
                        sb.append("[$idx]").append(e.javaClass.simpleName)
                            .append("/qp=").append(if (q != null) "有" else "无")
                            .append("/脏=").append(if (dirty == true) "是" else "否")
                            .append("/文案=\"").append(cap?.take(14) ?: "-").append("\" ")
                        idx++
                    }
                    Logger.evidence(
                        "MQPHOTOS",
                        "vm.f.g.g.mQPhotos size=${mq.size} 内容: $sb"
                    )
                } catch (_: Throwable) {}
                // （逐候选验证已并入上方 QPLISTS 的统计信息，不再按路径回取）
                // ★★★ 定向清洗「精选页脏项列表」（2026-09-26 用户报「游千幻的幻想世界」漏拦）。
                //
                // ## 真实证据（`QPLISTS` 探针，非推断）
                //
                // ```
                // [QPLISTS] 含 QPhoto 的列表：
                //   vm.h.f.o(sz=7 脏=1 首=QPhoto 首文案="#AI灵境计划#快手AI")      ← ★ 脏，元素直取
                //   vm.h.m.p.a(sz=7 脏=1 首=QPhoto 首文案="#AI灵境计划#快手AI")    ← ★ 脏，元素直取
                //   vm.h.t.d.f.c(sz=4 脏=1 首=QPhoto 首文案="#AI灵境计划#快手AI")  ← ★ 脏，元素直取
                // ```
                //
                // ## 为什么是这三个
                //
                // 对比同期其他列表：
                // ```
                // vm.h.j(sz=9 直=0 提=1)          ← 包装类，提不到 QPhoto ⇒ 删不动
                // vm.h.f.o(sz=7 首=QPhoto 脏=1)   ← ★ 元素直接是 QPhoto ⇒ 可删
                // ```
                //
                // 用户连续报的漏拦（「游千幻的幻想世界」「铁头王影视」「婉柔身边的守护者」）
                // **全部住在这三个列表里** —— 而它们此前**不在任何清洗范围内**
                // （路径是 `vm.h.*`，此前只清了 `vm.f.g.g.mQPhotos`）。
                //
                // ## 路径
                //
                // `vm` = SlidePlayViewModel；三个路径分别是：
                //   · `h.f.o`
                //   · `h.m.p.a`
                //   · `h.t.d.f.c`
                //
                // ## 安全
                //
                // · 元素**直接是 QPhoto**（`首=QPhoto` 实证）⇒ 无需解包，可直接判
                // · 只删判脏的，保留干净的
                // · 保留至少 1 条（与其他清洗一致的护栏）
                // · 异常全吞
                // [已停用 2026-09-26] washSelectedLists：实测造成 ViewPager 崩溃（adapter 内容变了没 notify）
            } catch (_: Throwable) {}
            // ★★ 主列表身份自证（2026-09-24）：
            //   实测主 feed 列表是 `vm.f.h.c`(42条) / `vm.f.h.f`(37条)，
            //   元素首项 `s0$c`（与 `s0$b` 同族，内容包装）。
            //
            //   本段把这两个列表的**逐元素构成**打出来，确认：
            //   ① 元素里能否提到 QPhoto（决定能不能判脏）
            //   ② 当前有多少脏项（决定清洗是否有意义）
            try {
                val fObj = Reflect.readAny(obj, "f") ?: return@safe
                val hObj = Reflect.readAny(fObj, "h") ?: return@safe
                for (fn in arrayOf("c", "f")) {
                    val lst = Reflect.readAny(hObj, fn) as? List<*> ?: continue
                    if (lst.isEmpty()) continue
                    var withQp = 0
                    var dirty = 0
                    val classes = LinkedHashSet<String>()
                    for (e in lst) {
                        if (e == null) continue
                        classes.add(e.javaClass.simpleName)
                        val q = if (CfhState.qpClassRef?.isInstance(e) == true) e
                                else CfhProbe.findQpInObject(e)
                        if (q != null) {
                            withQp++
                            if (try { CfhDecide.shouldFilterContent(q) } catch (_: Throwable) { false }) dirty++
                        }
                    }
                    Logger.evidence(
                        "MAININFO",
                        "vm.f.h.$fn size=${lst.size} 可提QP=$withQp 判脏=$dirty " +
                            "元素类=${classes.joinToString(",").take(120)} hc=${System.identityHashCode(lst)}"
                    )
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 收集「元素直接是 QPhoto」的列表（内容列表）—— 只读，每进程一次。
     *
     * 判据见 [CfhWash.filterVmListsInner] 中「清洗元素直接是 QPhoto 的列表」段落：
     * 实测 `直>0` 的列表才有脏项，`直=0` 的全为 0（且含资源池，不能碰）。
     */
    private fun collectQpDirectLists(obj: Any) {
        if (CfhState.qpDirectListsCollected) return
        CfhState.qpDirectListsCollected = true
        Logger.safe("collectQpDirect") {
            val seen = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
            )
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(obj, 0))
            seen.add(obj)
            var vis = 0
            val found = ArrayList<String>()
            while (q.isNotEmpty() && vis < 3000) {
                val node = q.removeFirst()
                val o = node[0]; val d = node[1] as Int
                vis++
                if (d > 4) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v is MutableList<*>) {
                                if (v.isEmpty()) continue
                                // ★ 核心判据：**过半元素本身是 QPhoto**
                                var n = 0
                                var direct = 0
                                for (e in v) {
                                    if (e == null) continue
                                    n++
                                    if (CfhState.qpClassRef?.isInstance(e) == true) direct++
                                }
                                if (n > 0 && direct * 2 > n) {
                                    @Suppress("UNCHECKED_CAST")
                                    CfhState.qpDirectLists.add(v as MutableList<Any?>)
                                    found.add("${f.name}(sz=${v.size},直=$direct)")
                                }
                            } else {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && v !is android.view.View &&
                                    seen.add(v)
                                ) {
                                    q.add(arrayOf(v, d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                }
            }
            Logger.evidence(
                "QPDIRECT",
                "收集到「元素直接是QPhoto」的列表 ${CfhState.qpDirectLists.size} 个: " +
                    found.joinToString(" | ")
            )
            // ★★ 响应对象 `HomeFeedResponse` 的结构自证（2026-09-24）。
            //
            //   持有链实测：`vm.f.g.g.mQPhotos ← HomeFeedResponse`
            //   —— 这是**网络响应对象**（`com.yxcorp.gifshow.model.response.feed`），
            //   而其余 5 个候选列表的持有者是混淆名（`zqh.q1`/`d5c.e`/`k5c.h`…），
            //   用途不明 —— 那正是两次误删的来源。
            //
            //   判断依据：**响应对象**的字段删了能重新拉取（有网络兜底），
            //   而运行时结构删了没得补。故只沿这条线扩大。
            //
            //   本段把响应对象的**全部字段**列出来（含嵌套 List），
            //   找出除 mQPhotos 外还有哪些内容载体。
            try {
                val fObj = Reflect.readAny(obj, "f") ?: return@safe
                val gObj = Reflect.readAny(fObj, "g") ?: return@safe
                val g2Obj = Reflect.readAny(gObj, "g") ?: return@safe
                val respCls = Class.forName(
                    "com.yxcorp.gifshow.model.response.feed.HomeFeedResponse",
                    false, obj.javaClass.classLoader
                )
                val sb = StringBuilder()
                var c2: Class<*>? = respCls
                var l2 = 0
                while (c2 != null && c2 != Any::class.java && l2 < 4) {
                    val cc2: Class<*>? = c2
                    for (f in (cc2 ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(g2Obj)
                            if (v is List<*>) {
                                var direct = 0
                                var withQp = 0
                                var dirty = 0
                                for (e in v) {
                                    if (e == null) continue
                                    if (CfhState.qpClassRef?.isInstance(e) == true) direct++
                                    val q = if (CfhState.qpClassRef?.isInstance(e) == true) e
                                            else CfhProbe.findQpInObject(e)
                                    if (q != null) {
                                        withQp++
                                        if (try { CfhDecide.shouldFilterContent(q) } catch (_: Throwable) { false }) dirty++
                                    }
                                }
                                if (withQp > 0) {
                                    sb.append("\n    ${f.name} sz=${v.size} 直=$direct 提=$withQp 脏=$dirty")
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c2 = cc2?.superclass; l2++
                }
                Logger.evidence("RESPFIELDS", "HomeFeedResponse 的内容列表:$sb")
            } catch (_: Throwable) {}
            //
            //   两次误删的根因都是「不知道列表用途就删」。
            //   本段为每个候选列表追出：
            //   ① 它在哪个对象、什么字段名（已知）
            //   ② 该对象的**类名**（比字段名有信息量）
            //   ③ 该对象是否被某个「管理器/Provider/Adapter」持有
            //      —— 即从 VM 到该列表的**完整持有链**
            //
            //   有了持有链才能判断：这是「内容源」还是「已渲染引用表」。
            //   只读，每进程一次。
            try {
                val chain = ArrayList<String>()
                for (lst in CfhState.qpDirectLists) {
                    val info = traceHolder(obj, lst)
                    if (info != null) chain.add(info)
                }
                Logger.evidence(
                    "LISTTRACE",
                    "候选列表持有链:\n  " + chain.joinToString("\n  ")
                )
            } catch (_: Throwable) {}
        }
    }

    /**
     * 追溯某个列表被哪个对象、什么字段持有 —— 用于判断列表用途。
     *
     * ★ 为什么要这个（2026-09-24）：
     *   两次误删的根因都是「不知道列表用途就删」。
     *   字段名 `o` / `a` / `c` 毫无语义，但**持有者的类名**往往有：
     *   例如被 `XxxProvider` 持有 → 内容供给；被 `XxxCache` 持有 → 缓存；
     *   被 `XxxAdapter` 持有 → 已渲染引用。
     *
     * @return 形如 `vm.f.g.o ← 持有者=com.xxx.Provider(字段 o)`；未找到返回 null
     */
    private fun traceHolder(root: Any, target: List<*>): String? {
        return try {
            val seen = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
            )
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(root, "vm", 0))
            seen.add(root)
            var vis = 0
            while (q.isNotEmpty() && vis < 3000) {
                val node = q.removeFirst()
                val o = node[0]; val path = node[1] as String; val d = node[2] as Int
                vis++
                if (d > 4) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v === target) {
                                // ★ 持有者身份自证（2026-09-24）：
                                //   混淆类名本身没信息，但**继承链 + 接口 + 方法名**有。
                                //   例如「实现 XxxProvider 接口」或「有 getNextPhotos() 方法」
                                //   就能判断它是供给方还是消费方。
                                val holderCls = o.javaClass
                                val ifaces = try {
                                    holderCls.interfaces.joinToString(",") {
                                        it.name.substringAfterLast('.')
                                    }.take(120)
                                } catch (_: Throwable) { "-" }
                                val superN = try {
                                    holderCls.superclass?.name?.substringAfterLast('.') ?: "-"
                                } catch (_: Throwable) { "-" }
                                val methods = try {
                                    holderCls.declaredMethods
                                        .filter { it.parameterTypes.isEmpty() &&
                                            (it.returnType == java.util.List::class.java ||
                                                it.returnType.name.contains("List")) }
                                        .take(5).joinToString(",") { it.name }
                                } catch (_: Throwable) { "-" }
                                return "$path.${f.name} ← 持有者=${holderCls.name}(sz=${target.size}) " +
                                    "父类=$superN 接口=[$ifaces] 取数方法=[$methods]"
                            }
                            if (v !is List<*> && v !is android.view.View) {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && seen.add(v)
                                ) {
                                    q.add(arrayOf(v, "$path.${f.name}", d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                    if (lvl > 3) break
                }
            }
            null
        } catch (_: Throwable) { null }
    }

    /**
     * 全面清洗所有候选内容列表（2026-09-24，按用户指示）。
     *
     * ## 与之前两次「扩大」的区别
     *
     * 前两次扩大都误删正常内容（用户报「无更多作品」）。本版的两道保险：
     *
     * ① **每列表保留至少 1 条** —— 没删到空（空列表的处理路径未验证）
     * ② **只删明确判脏的** —— 判据用 `shouldFilterContent`，不做推测兜底
     *
     * 用户明确要求「只要拦的是要拦的没有误拦就行，无更多作品在其他地方想办法」，
     * 故放开列表范围，优先保证拦截覆盖。
     *
     * ## 遍历范围
     *
     * 从 VM 出发（深度 4），对每个含 QPhoto 的 MutableList 执行清洗，
     * 跳过 Presenter/Callback 过半的回调表（那会破坏快手功能，属真误删）。
     */
    private fun cleanAllCandidateLists(obj: Any) {
        Logger.safe("cleanAllCandidate") {
            val seen = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
            )
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(obj, 0))
            seen.add(obj)
            var vis = 0
            var totalRemoved = 0
            var listHit = 0
            while (q.isNotEmpty() && vis < 3000) {
                val node = q.removeFirst()
                val o = node[0]; val d = node[1] as Int
                vis++
                if (d > 4) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v is MutableList<*>) {
                                if (v.isEmpty()) continue
                                // ★★★ 核心修正（2026-09-24）：只处理**元素本身就是 QPhoto**的列表。
                                //
                                //   实测教训（SMALLLIST）：
                                //     `字段=mBackPressInterceptors 持有者=HomeActivity`
                                //     `字段=mConfigChangeListeners 持有者=HomeActivity`
                                //   这两个是 **Activity 的返回键拦截器表 / 配置变更监听器表** ——
                                //   与内容毫无关系，却被清洗掉了。
                                //
                                //   原因：原实现用 `CfhProbe.findQpInObject(e)` **深度 BFS**
                                //   从元素里挖 QPhoto —— 监听器对象深层可能挂着某个 QPhoto，
                                //   挖出来判脏为真，就把**监听器**删了。
                                //
                                //   后果：删掉 Activity 监听器 → 快手功能异常 →
                                //   用户报「无更多作品」。
                                //
                                //   修正：**只认元素本身是 QPhoto 的列表**，不做任何深挖。
                                //   代价：QPhoto 藏在包装里的列表不再清洗（覆盖收窄），
                                //   但换来「不会误删非内容对象」—— 这正是用户要的
                                //   「只要拦的是要拦的没有误拦就行」。
                                var direct = 0
                                var n = 0
                                var cb = 0
                                for (e in v) {
                                    if (e == null) continue
                                    n++
                                    val cn = e.javaClass.name
                                    if (cn.endsWith("Presenter\$a") || cn.contains("Callback")) cb++
                                    if (CfhState.qpClassRef?.isInstance(e) == true) direct++
                                }
                                // 过半元素本身是 QPhoto，且不是回调表
                                if (n == 0 || direct * 2 <= n || cb * 2 > n) continue
                                @Suppress("UNCHECKED_CAST")
                                val ml = v as MutableList<Any?>
                                var removed = 0
                                var i = ml.size - 1
                                while (i >= 0) {
                                    val e = ml[i]
                                    // ★ 元素本身必须是 QPhoto 才判脏（不深挖）
                                    if (e != null && CfhState.qpClassRef?.isInstance(e) == true) {
                                        val dirty = try {
                                            CfhDecide.shouldFilterContent(e)
                                        } catch (_: Throwable) { false }
                                        if (dirty && ml.size > 1) { ml.removeAt(i); removed++ }
                                    }
                                    i--
                                }
                                if (removed > 0) { totalRemoved += removed; listHit++ }
                                // ★★ 记录每个被清洗列表的**清洗前后尺寸**（2026-09-24）。
                                //   用户报「无更多作品」—— 需确认是否某个列表被删到很小。
                                //   只记「删完剩 ≤3 条」的（那才是可疑的）。
                                if (removed > 0 && ml.size <= 3 && CfhState.smallListCount < 30) {
                                    CfhState.smallListCount++
                                    Logger.evidence(
                                        "SMALLLIST",
                                        "★列表被删到剩${ml.size}条（原${ml.size + removed}）" +
                                            " 字段=${f.name} 持有者=${o.javaClass.name} 判据=${CfhDecide.lastHitReason ?: "?"}"
                                    )
                                }
                            } else {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && v !is android.view.View &&
                                    seen.add(v)
                                ) {
                                    q.add(arrayOf(v, d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                }
            }
            if (totalRemoved > 0 && CfhState.allListDelCount < 60) {
                CfhState.allListDelCount++
                Logger.evidence(
                    "ALLDEL2",
                    "全面清洗 删${totalRemoved}条 涉及${listHit}个列表 判据=${CfhDecide.lastHitReason ?: "?"}"
                )
            }
        }
    }

    /**
     * 最小范围清洗（2026-09-24，**已实机验证通过**）。
     *
     * ## 最终效果（用户确认）
     *
     * 「能（正常刷视频），没看到有漏的」
     *
     * ## 四轮迭代的完整过程（每次都有实测数据）
     *
     * | 版本 | 范围 | 保留 | 结果 |
     * |---|---|---|---|
     * | 全列表 | 30 个列表（无差别） | 1 条 | ❌ 误删监听器表 → 无更多作品 |
     * | 直>0 | 6 个列表 | 1 条 | ❌ 仍无更多作品 |
     * | 最小 | 元素 100% 是 QPhoto + 尺寸≥5 | 3 条 | ⚠️ 仍无更多作品（4 个列表里有一个不能删） |
     * | **本版** | **过半可提 QPhoto + 尺寸≥5** | **3 条** | ✅ **通过** |
     *
     * ## 两个关键发现（都是二分排除法得出的）
     *
     * ### ① 字段 `o` —— 绝不能删
     *
     * 从 4 个候选列表（`o@zqh.q1` / `o@d5c.e` / `mQPhotos@HomeFeedResponse` / `a@k5c.h`）中
     * 只排除 `o` → 用户报「没有了」（「无更多作品」消失）。**这就是根因。**
     *
     * 它特征最像内容列表（元素 100% 是 QPhoto），实际是
     * 「当前页已展示项的引用表」—— 删了快手认为该页读完。
     *
     * ### ② Activity/Fragment 的 `m*` 框架字段 —— 不能删
     *
     * 放宽判据后实测 `[mConfigChangeListeners@HomeActivity] 删2条` ——
     * 那是配置变更监听器表，与内容无关（监听器对象深层挂 QPhoto 被误判）。
     *
     * ## 保留的护栏
     *
     * 1. 尺寸 ≥5（太小的可能承载关键少量项）
     * 2. 每列表删后保留 ≥3 条（不删空）
     * 3. 跳过过半 Presenter/Callback 的回调表
     * 4. 排除 `o` 字段
     * 5. 排除 Activity/Fragment 的 `m*` 字段
     *
     * ## 不适用条件
     *
     * - 若用户报「无更多作品」复现 → 优先怀疑又放进了新的「引用表类」字段，
     *   用同一套二分排除法定位（每次只排除一个字段，让用户验证）。
     * - 本函数只做「删除」，不做替换 —— 用户明确要求「尽量直接删，不要替换」。
     */
    /**
     * 周期清洗调度（2026-09-24 新增，修「有时漏有时不漏」）。
     *
     * ## 为什么需要它
     *
     * 原有的清洗只在 `filterVmLists` 被事件触发时执行。实测发现：
     * **同一个 APK、同一份配置，重启后行为不同**——
     *   · 有的轮次 `MINDEL` 有删除 → 不漏
     *   · 有的轮次 `MINDEL` 零次（`WASH-INNER` 进来了但没删到东西）→ 漏
     *
     * 原因是触发时刻与「列表填充完成时刻」的相对顺序不稳定：
     * 冷启动/网络快时，清洗可能跑在内容填充**之前**，此时列表是空的，
     * 没有可删项；等列表填好后再也没有事件来触发清洗 → 漏。
     *
     * ## 做法
     *
     * 独立起一条周期任务（1 秒/次），与事件触发**解耦**。
     * 无论时序如何，列表一旦填充就会被处理。
     * 成本：一次遍历（深度 4、跳过集合/映射），1 秒一次可忽略。
     *
     * ## 与事件触发的关系
     *
     * 事件触发保留不动（响应快），周期清洗兜底（保证不漏）。
     * 两条路径都走 `cleanMinimalLists`，其内部有计数节流与
     * 两个永久排除字段（`o` / `m*` 框架字段），不会重复删或误删。
     */
    /**
     * ★★ 周期清洗（2026-09-24，修「有时漏有时不漏」）。
     *
     * ## 为什么需要它
     *
     * 实测：同一个 APK、同一份配置，重启后行为不同 ——
     *   · 有的轮次 `MINDEL` 有删除记录 → 不漏
     *   · 有的轮次 `MINDEL` 零次（`WASH-INNER` 进来了但没删到东西）→ 漏
     *
     * 即清洗入口在跑，但**执行时刻的列表还是空的/不完整**，
     * 没有可删项；等列表填充好之后，再没有事件触发清洗 → 漏。
     * 触发时刻与「列表填充完成时刻」的相对顺序受冷启动/网络快慢影响，
     * 这正是「时好时坏」的来源。
     *
     * ## 做法
     *
     * 独立起一条 1 秒周期任务，与事件触发**解耦**：只要曾经有过清洗目标
     * （`pendingCleanObj` 或 `lastCleanObj` 有值），就持续重跑清洗。
     * 无论时序如何，列表一旦填充就会被处理。
     *
     * 成本：一次 VM 遍历（深度 4、跳过集合/映射、跳过 `o`/`m*`），
     * 1 秒一次可忽略；且 `cleanMinimalLists` 内部有计数与收敛判断，
     * 已干净时不会重复删。
     *
     * ## 与事件触发的关系
     *
     * 事件触发保留（响应快），周期清洗兜底（保证不漏）。
     */
    private val periodicStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 最近一次清洗目标（周期任务复用；弱引用避免拖住 VM） */
    @Volatile
    private var lastCleanTarget: java.lang.ref.WeakReference<Any>? = null

    /** 追打态 tick 周期：发现脏项后的 10 秒内高频追打（追着重灌打） */
    private const val HUNT_TICK_MS = 200L

    /** 平时 tick 周期 */
    private const val IDLE_TICK_MS = 1000L

    /** 「追打态」判定窗口：距最近一次见到脏项多久之内算追打 */
    private const val HUNT_WINDOW_MS = 10_000L

    /**
     * 追打态「自持」预警闸门 —— 单次**连续**追打超过它就打一条 `PERIODIC` 日志（2026-10-01）。
     *
     * ## 为什么需要
     *
     * `lastDirtySeenAt` 的读点（:891）与写点（:1224）都在本巡检体内、且无重置点，
     * 结构上是一个正反馈闭环（完整论证见 `CfhState.huntArmedPids` 的 KDoc）。
     * 闭环是否被喂活**必须能被事后看见** —— 否则下次复发又只能从头猜。
     *
     * ## 取值与判读
     *
     * 设计上单次追打 = [HUNT_WINDOW_MS] 10 秒；只有**新的**脏 pid 才会续期
     * （`CfhState.huntArmedPids` 闸门）⇒ 明显超过 10 秒即说明「新脏源在持续到达」
     * 或「闸门失效/闭环被喂活」，两种情况都值得人看一眼。
     * 取 60_000ms ＝ 6 倍设计值，避免正常抖动误报。
     */
    private const val HUNT_SUSTAIN_WARN_MS = 60_000L

    /**
     * 周期可见巡检（`periodicScanVisible`）的最小间隔 —— 2026-09-30 修复。
     *
     * ## 修的是什么
     *
     * 原判据 `tick % 1L == 0L` **恒真**（任何整数对 1 取模都是 0），
     * 「1 秒一次巡检」这个原意从未生效，实际变成**每 tick 一次**：
     *   · 追打态 tick = [HUNT_TICK_MS] 200ms ⇒ 巡检 **5 次/秒**
     *   · 平时   tick = [IDLE_TICK_MS] 1000ms ⇒ 巡检 1 次/秒
     * 而巡检本身是「2 趟全对象图 BFS + 最多 60 次完整判定」，
     * 5 次/秒正好落在追打态（＝用户正在滑）⇒ 全项目最重的 CPU 消耗点之一。
     *
     * ## 为什么用「毫秒」而不是「tick 数」表达周期
     *
     * tick 周期随追打态在 200/1000 之间变（见上），任何 `% N` 的 tick 计数
     * 都会随之整体缩放 —— 这正是本缺陷的成因（同一个 `% 1L` 在两种态下
     * 分别等于 5 次/秒与 1 次/秒）。改成「距上次巡检 ≥ N 毫秒」后，
     * 两种 tick 周期下都稳定收敛到目标频率；后续再调 sleep 也不会被放大。
     *
     * ## 取值
     *
     * 1000ms ＝ 与原注释「1 秒一次，与用户怎么滑无关」一致，也是追打态下的
     * 下降目标：**5 次/秒 → 1 次/秒（降 5 倍）**。
     * 平时态本就是 1 次/秒，不受影响 ⇒ 无额外覆盖损失。
     *
     * ## 代价（用户已知情并裁定接受）
     *
     * 巡检变稀 ⇒ 「脏项刚脏、下一轮巡检还没到」的窗口变长，
     * 极端情况可能漏过一小段停留期的脏项。用户明确接受该漏拦代价。
     * 追打路径本身（`filterVmListsInner`）与 tick 频率**均未改动**，响应性不受影响。
     */
    private const val PERIODIC_SCAN_INTERVAL_MS = 1000L

    /**
     * 报障关键词全域搜索（[searchByKeyword]）的最小间隔 —— 2026-09-30 同型缺陷修复。
     *
     * ## 修的是什么
     *
     * 原判据 `tick % 25L == 0L` 是**按 tick 计数**，而 tick 周期随追打态在
     * [HUNT_TICK_MS] 200ms 与 [IDLE_TICK_MS] 1000ms 之间切换
     * （成因与论证见 [PERIODIC_SCAN_INTERVAL_MS]）⇒ 同一个 `% 25L` 在两种态下：
     *   · 追打态 tick=200ms ⇒ 25 × 200ms = **5 秒/词**（比注释意图快 5 倍）
     *   · 平时   tick=1000ms ⇒ 25 × 1000ms = 25 秒/词（＝注释意图）
     * 即「用户正在滑」（追打态）时，这趟**最重的探针跑得最勤** —— 与期望相反。
     *
     * ## 本探针有多重
     *
     * [searchByKeyword] 从 Activity / VM / pager 出发做对象图 BFS
     * （上限 4000 对象、深度 6、沿继承链 5 层），并对**每个字符串字段**做子串匹配。
     * 属周期线程里最重的几个动作之一，5 秒一次是纯浪费。
     *
     * ## 取值
     *
     * 25_000ms ＝ 与原注释「每 25 秒搜一个词（轮转）」一致：
     * 追打态 5 秒 → 25 秒（降 5 倍），平时态本就 25 秒 ⇒ 无额外覆盖损失。
     * 轮转语义（`kwSearchIdx`）、搜索根与探针内容**均未改动**。
     */
    private const val KW_SEARCH_INTERVAL_MS = 25_000L

    /**
     * 全 VM 普查（[surveyAllViewModels]）的最小间隔 —— 2026-09-30 同型缺陷修复。
     *
     * 同一缺陷的第三例：原判据 `tick % 90L == 0L` 同为**按 tick 计数**，
     * 追打态 200ms × 90 = **18 秒/次**（比注释意图快 5 倍），
     * 平时态 1000ms × 90 = 90 秒/次（＝注释意图）。
     *
     * 本探针做的是「从 Activity 出发找出所有 ViewModel，并逐个统计其内容列表」，
     * 同样是深度 BFS ＋ 反射，90 秒一次才是它应有的频率。
     *
     * 取值 90_000ms ＝ 与原注释「每 90 秒一次」一致：追打态 18 秒 → 90 秒，平时态不变。
     */
    private const val VM_SURVEY_INTERVAL_MS = 90_000L

    /**
     * 内层清洗（[filterVmListsInner]）在**周期 tick 路径**上的最小间隔 —— 2026-10-01 性能 Top①。
     *
     * ## 修的是什么
     *
     * `startPeriodicClean` 的 while 里原本**无条件**调用 `filterVmListsInner(target)`
     * （同循环里的 [periodicScanVisible] / 关键词搜索 / VM 普查三处都已有毫秒门控，
     * 只有它没有）。实测（静默态，60 秒窗口）：`WASH-INNER` **1.31 次/秒**，
     * 时间戳严格随 tick 间隔、**永不停止**；而每次调用含 3~5 趟 BFS
     * （2000~3000 节点）＋ 逐字段反射清洗，是全场景（滑 / 不滑都跑）常驻的 CPU/GC 开销。
     * 追打态 tick = [HUNT_TICK_MS] 200ms ⇒ 实际 **5 次/秒**（＝用户正在滑时最重）。
     *
     * ## 为什么用「毫秒」而不是「tick 数」
     *
     * 与 [PERIODIC_SCAN_INTERVAL_MS] 同因：tick 周期随追打态在 200/1000ms 之间切换，
     * 任何 `% N` 的 tick 计数都会随之整体缩放 —— 本项目已犯过三次（见同循环内三处注释）。
     * 改成「距上次清洗 ≥ N 毫秒」后，两种 tick 周期下频率恒定，后续再调 sleep 也不会被放大。
     *
     * ## 取值 3000ms 的依据
     *
     * · 平时态 tick = [IDLE_TICK_MS] 1000ms，原实现＝**每 tick 都跑 ⇒ 1 次/秒**；
     *   所以周期必须 **> 1000ms** 才有效果（取 ≤1000ms 在平时态是空操作）。
     * · 取 3 × [IDLE_TICK_MS] ⇒ 平时态 1 次/秒 → 0.33 次/秒；
     *   追打态 5 次/秒 → 0.33 次/秒（降 15 倍，正好落在用户正在滑的最重时段）。
     * · 不用更长周期：本路径是**兜底**（见 :780「事件触发保留（响应快），周期清洗兜底
     *   （保证不漏）」），而事件路径（[filterVmLists] → `cleanExecutor`）**完全未动**，
     *   滑屏时的响应性不变；兜底周期越长，「事件路径没覆盖到、只能等兜底」的滞留越久。
     *
     * ## 代价（权衡，必须写明）
     *
     * 降频 ⇒ **拦脏项被延后**：极端情况（事件路径恰好没触发）脏项在数据源里多停留
     * 至多 3 秒（原为至多 1 秒），即**新增最多 2 秒窗口**。清洗逻辑本身、
     * 判定 / 放行 / PENDING·DIRTY 语义**均未改动**，只是跑得稀了。
     */
    private const val INNER_WASH_INTERVAL_MS = 3000L

    /**
     * `WASH-INNER` 落盘限流（滑动窗口，行 / 分钟）—— 2026-09-30 性能 Top⑤。
     *
     * ## 修的是什么
     *
     * [filterVmListsInner] 开头那句 `Logger.evidence("WASH-INNER", ...)` 是全文件
     * **唯一**没有护栏的 evidence 调用（同文件其它调用点普遍带 `xxx < N` 计数或毫秒门控）。
     * 实测该 tag **21,741 行**（全文件第 2 大 tag），平时态随 tick 永久写。
     *
     * ## 为什么用「滑动窗口」而不是 `xxxLog < N` 进程级限次
     *
     * 本 tag 是**频率指示器**：验证协议靠 `grep -c WASH-INNER` 判断清洗链路是否在跑
     * （本轮 ① 的节流效果也用它度量）。进程级 `xxxLog < N` 的已知形态问题是
     * **N 次之后永久静默**（见 [RateLimiter] 类注释：`TTPPARSE-BLOCK=80` / `PV2-BLOCK=60`
     * 正好卡在限次、之后全部无记录），一旦在崩溃/故障前用光配额，
     * **最需要它的那段时间恰好是空白**。滑动窗口每分钟自动重置 ⇒ 持续有记录、又限得住。
     *
     * ## 取值 60 行/分钟 的依据
     *
     * 60/min ＝ 1 次/秒 ＝ 这条链路原本想要的节律。它是**突发上限**而非常态节流：
     * ① 之后平时态真实速率已降到 ≈0.33 次/秒（≈19~20 行/分钟）< 60 ⇒ 平时态**不触发**上限，
     * 因此验证协议量到的仍是真实频率；只有追打/事件密集时（实测可达 5 + 2 次/秒）
     * 才把落盘压到 60 行/分钟。**只限日志、不清洗** —— 清洗照旧执行。
     *
     * ## ⚠️ 数值更正（2026-09-30）
     *
     * 本段①原写「**≈0.6 次/秒（≈38 行/分钟）**」，**与实测不符**，且与本文件
     * :911 自己给的「3 × [IDLE_TICK_MS] ⇒ 0.33 次/秒」**自相矛盾**。
     * **2026-09-30 实测更正为 0.33 次/秒（≈19~20 行/分钟）**：
     * `evidence.txt` 逐分钟稳定 19~20 行（2026-09-30 03:30–03:54 连续 25 分钟，
     * 每行为 19 或 20），与 0.33 × 60 = 19.8 吻合。
     * 原数字保留于此仅为留痕，**以更正后的 0.33 次/秒（19~20 行/分钟）为准**。
     * 本更正**只改注释文字**，[WASH_INNER_LOG_PER_MIN] 常量与门控逻辑一字未动。
     */
    private const val WASH_INNER_LOG_PER_MIN = 60

    private fun startPeriodicClean() {
        if (!periodicStarted.compareAndSet(false, true)) return
        try {
            CfhState.cleanExecutor.execute {
                var tick = 0L
                // ★ 巡检节流时钟（2026-09-30）：上次跑 periodicScanVisible 的时刻。
                //   初值 0 ⇒ 第一轮 tick 必跑一次（与修复前行为一致，不冷启动漏扫）。
                var lastPeriodicScanAt = 0L
                // ★★★ 内层清洗节流时钟（2026-10-01，性能 Top①）：
                //   上次跑 filterVmListsInner 的时刻。初值 0 ⇒ 第一轮 tick 必跑一次
                //   （与修复前行为一致，不冷启动漏清）。
                var lastInnerWashAt = 0L
                // ★ 同型节流时钟（2026-09-30）：下面两处原判据同为 `% N` tick 计数
                //   （`% 25L` / `% 90L`），会随 tick 周期在 200/1000ms 间缩放。
                //   初值取「当前时刻」—— 保留修复前的**首次**触发时刻
                //   （原 `% 25L` / `% 90L` 首次分别落在第 25 / 90 个 tick），
                //   避免冷启动时提前多跑重探针；稳态周期则由常量恒定决定。
                var lastKwSearchAt = System.currentTimeMillis()
                var lastVmSurveyAt = System.currentTimeMillis()
                // ★★★ 追打态「自持」观测（2026-10-01）—— 唯一的长期保险。
                //   本字段的读写构成闭环（读点 :891 / 写点 :1224 / 无重置点，
                //   完整论证见 CfhState.huntArmedPids 的 KDoc），
                //   闭环是否被喂活必须能事后看见，否则下次复发只能从头猜。
                //
                //   判读方法（明文通道 `evidence.txt`，tag `PERIODIC` 在白名单内）：
                //     · `追打结束 持续=Ns 轮=N` —— 每次退出追打态打一条。
                //       正常：偶发、持续≈10 秒、两次之间相隔很远。
                //       异常（自持迹象）：持续恒 ≥10 秒且**几乎不再出现**「追打结束」
                //       （即长期停在追打态不出来）。
                //     · `追打自持 …` —— 单次连续追打超过 [HUNT_SUSTAIN_WARN_MS] 打一条。
                //       **一条都不出现 ＝ 闭环未被喂活**（当前实测即此状态）。
                //   威胁：只多 3 个局部变量 + 2 条日志，不改 tick、不改判定、不放行。
                var huntSince = 0L      // 本次连续追打的起始时刻（0 ＝ 当前不在追打态）
                var huntRounds = 0L     // 本次连续追打的轮数
                var huntWarned = false  // 本次连续追打是否已打过自持预警
                while (true) {
                    var hunt = false
                    try {
                        // ★ 自适应追打（2026-09-25 主人要求「让他不重灌」）：
                        //   发现脏项后的 10 秒内以 200ms 高频清洗（追着重灌打），
                        //   平时保持 1s。JDK 真源列表无法挂写入拦截（ANR 红线），
                        //   高频轮询是数据层唯一合规的压缩重灌窗口手段。
                        hunt = System.currentTimeMillis() - CfhState.lastDirtySeenAt < HUNT_WINDOW_MS
                        Thread.sleep(if (hunt) HUNT_TICK_MS else IDLE_TICK_MS)
                    } catch (ie: InterruptedException) {
                        return@execute
                    }
                    // ★ 追打态进出观测（见上方局部变量处的说明）。
                    //   单独 try 包住：本块在 InterruptedException 的 catch 之外，
                    //   日志若抛异常会被最外层 catch(e: Throwable) 收走并**结束整条周期线程**，
                    //   所以必须自己吞掉 —— 观测不得影响巡检本体。
                    try {
                        val obsNow = System.currentTimeMillis()
                        if (hunt) {
                            if (huntSince == 0L) {
                                huntSince = obsNow
                                huntRounds = 0L
                                huntWarned = false
                            }
                            huntRounds++
                            if (!huntWarned && obsNow - huntSince >= HUNT_SUSTAIN_WARN_MS) {
                                huntWarned = true
                                Logger.evidence(
                                    "PERIODIC",
                                    "追打自持 连续=${(obsNow - huntSince) / 1000}秒 " +
                                        "轮=$huntRounds 武装源数=${CfhState.huntArmedPids.size}"
                                )
                            }
                        } else if (huntSince != 0L) {
                            Logger.evidence(
                                "PERIODIC",
                                "追打结束 持续=${(obsNow - huntSince) / 1000}秒 轮=$huntRounds"
                            )
                            huntSince = 0L
                            huntRounds = 0L
                            huntWarned = false
                        }
                    } catch (_: Throwable) {}
                    tick++
                    // 优先用排队中的目标，其次用上一次的目标
                    val target = CfhState.pendingCleanObj.getAndSet(null)
                        ?: lastCleanTarget?.get()
                        ?: continue
                    try {
                        // ★★★ 节流（2026-10-01，性能 Top①）—— 原为**无条件调用**。
                        //
                        //   实测（静默 60 秒窗口）：`WASH-INNER` 1.31 次/秒且永不停止，
                        //   每次含 3~5 趟 BFS（2000~3000 节点）＋ 逐字段清洗；
                        //   追打态 tick=200ms 时更到 5 次/秒（＝用户正在滑时最重）。
                        //
                        //   与同循环的 periodicScanVisible / 关键词搜索 / VM 普查一致，
                        //   改为**毫秒门控**（不用 `% N` tick 计数：tick 周期在 200/1000ms
                        //   间切换，`% N` 会整体缩放 —— 本项目已犯过三次，见上方三处注释）。
                        //
                        //   周期 = [INNER_WASH_INTERVAL_MS] 3000ms（取值依据见该常量 KDoc）：
                        //     平时态 1 次/秒 → 0.33 次/秒；追打态 5 次/秒 → 0.33 次/秒。
                        //
                        //   ⚠️ 权衡：降频 ⇒ 拦脏项被**延后**，极端情况（事件路径恰好没触发）
                        //   脏项在数据源多停留至多 3 秒（原至多 1 秒，新增最多 2 秒窗口）。
                        //   清洗逻辑本身未关（清洗是拦脏项的手段，只降频不停用）；
                        //   事件路径 filterVmLists → cleanExecutor（:72）完全未动，响应性不变。
                        val innerNow = System.currentTimeMillis()
                        if (innerNow - lastInnerWashAt >= INNER_WASH_INTERVAL_MS) {
                            lastInnerWashAt = innerNow
                            filterVmListsInner(target)
                        }
                    } catch (e: Throwable) {
                        Logger.evidence("PERIODIC", "err ${e.javaClass.simpleName}: ${e.message}")
                    }
                    if (tick <= 5L) {
                        Logger.evidence("PERIODIC", "tick=$tick vm=${target.javaClass.simpleName}")
                    }
                    // ★★★ 周期可见内容巡检（2026-09-24）—— 脱离 UI 事件的观测点。
                    //
                    //   ## 为什么必须脱离 diagFragment
                    //
                    //   实测（主人报「刘老根大舞台」）：
                    //     VISDUMP 最后一条  1790254768249
                    //     文件最后一条      1790254820087  ← 仍在写
                    //     ⇒ VISDUMP **52 秒零输出**，而模块运行正常。
                    //
                    //   根因：`VISDUMP` 挂在 `diagFragment`（Fragment 字段扫描）上，
                    //   而该函数只在**特定 UI 时机**被调用。
                    //   主人正常滑动时它根本不触发 → 报障条目永远抓不到。
                    //
                    //   ## 本巡检怎么绕开
                    //
                    //   不依赖任何 UI 事件，直接：
                    //     ① 用已建立的 vmRef
                    //     ② 读 VM 的当前下标（CurrentPhotoHook.readCurrentIndex）
                    //     ③ 从内容列表里取那一条
                    //     ④ 记录它的指纹
                    //
                    //   1 秒一次，与用户怎么滑无关 —— 只要那条在数据层存在，
                    //   就会被记录到。
                    //
                    // ★★★ 节流修复（2026-09-30）—— 原判据 `tick % 1L == 0L` **恒真**
                    //   （任何整数对 1 取模都是 0），「1 秒一次」从未生效：
                    //   · 追打态 tick=200ms ⇒ 本巡检实际 **5 次/秒**（＝用户正在滑时最重）
                    //   · 平时   tick=1000ms ⇒ 1 次/秒
                    //   现改为毫秒门控 [PERIODIC_SCAN_INTERVAL_MS]=1000ms：
                    //   **两种 tick 周期下都恒为 1 次/秒** ⇒ 追打态 5 次/秒 → 1 次/秒，降 5 倍；
                    //   平时态本就 1 次/秒，维持不变（无额外覆盖损失）。
                    //   代价：巡检变稀，「脏项刚脏、下一轮还没到」的窗口变长，
                    //   极端情况可能漏过一小段停留期 —— 用户已知情裁定接受。
                    //   追打本身（上面的 filterVmListsInner 与 tick 频率）**未改**，响应性不变。
                    val scanNow = System.currentTimeMillis()
                    if (scanNow - lastPeriodicScanAt >= PERIODIC_SCAN_INTERVAL_MS) {
                        lastPeriodicScanAt = scanNow
                        try { periodicScanVisible(target) } catch (_: Throwable) {}
                    }
                    // ★ 替换式换条废弃（2026-09-25 用户定稿「就删脏项就行了」）：
                    //   periodicDwellGate（停留期三处写回）随 WILLGATE 一并移除 ——
                    //   换条会制造「视频是净项、文案/角标还是脏项」的错配。
                    //   停留期脏项由既有删除链路（PSCAN/PDEL/VISFDEL 双跳匹配）持续压制。
                    // ★★★ 报障关键词全域搜索（2026-09-24）。
                    //
                    //   主人报的漏拦内容（「小鱼带你看世界」「满堂嘲讽，执手良缘」
                    //   「鼠鼠巴啦啦」「泡泡追剧」「卿本佳人」「刘老根大舞台」
                    //   「schiff旭福官方海外」）**从来没出现在我的记录里** ——
                    //   说明它们可能根本不在我扫描的那些列表上。
                    //
                    //   与其继续猜「哪个列表承载它」，不如直接全域搜：
                    //   从 VM 出发遍历对象图做字符串匹配，命中即给出确切挂载点；
                    //   一条都不命中 = 它走 VM 之外的通道（那也是决定性的结论）。
                    //
                    //   每 25 秒搜一个词（轮转），避免占用周期任务。
                    // ★★ 节流修复（2026-09-30，与 [PERIODIC_SCAN_INTERVAL_MS] 同型缺陷）：
                    //   原判据 `tick % 25L == 0L` 按 tick 计数，会随 tick 周期**整体缩放**：
                    //     · 追打态 tick=200ms ⇒ 实际 **5 秒/词**（＝注释意图的 5 倍频，
                    //       恰好在「用户正在滑」这一最敏感的时段加重负载）
                    //     · 平时   tick=1000ms ⇒ 25 秒/词
                    //   现改为毫秒门控 [KW_SEARCH_INTERVAL_MS]=25_000ms：
                    //   **两种 tick 周期下都恒为 25 秒/词** ⇒ 追打态 5 秒 → 25 秒（降 5 倍）；
                    //   平时态本就 25 秒，维持不变 ⇒ 无额外覆盖损失。
                    //   轮转（kwSearchIdx）、搜索根与探针内容未改，只是频率恒定。
                    val kwNow = System.currentTimeMillis()
                    if (kwNow - lastKwSearchAt >= KW_SEARCH_INTERVAL_MS) {
                        lastKwSearchAt = kwNow
                        try {
                            val kw = CfhState.reportedKeywords
                            if (kw.isNotEmpty()) {
                                val i = CfhState.kwSearchIdx % kw.size
                                CfhState.kwSearchIdx++
                                // ★★ 搜索根从 VM 改为 **Activity**（2026-09-24 第三步修正）。
                                //
                                //   实测：从 `SlidePlayViewModel` 出发、遍历 4000 个对象、
                                //   深度 6，搜「schiff」「旭福」「刘老根」**全部零命中**：
                                //     `KWSEARCH 关键词 "schiff"：未在 VM 找到（访问4000 对象）`
                                //
                                //   结论（决定性）：这些内容**不在 SlidePlayViewModel 的
                                //   对象图里** —— 这解释了为什么我把探针挂在这个 VM 上、
                                //   反复改判据/深度/时机，报障内容始终抓不到。
                                //
                                //   所以把搜索根换成 **Activity**（整个页面的对象根），
                                //   覆盖范围从「一个 VM」扩大到「整页」。
                                //   若这样仍零命中，就证明它走的是 VM/Activity 之外的通道
                                //   （独立广告 SDK、WebView、或原生渲染），
                                //   那也是最终结论 —— 该改动拦截位置而非判据。
                                val rootAct = CfhState.tracked
                                if (rootAct != null) {
                                    searchByKeyword(rootAct, kw[i])
                                } else {
                                    searchByKeyword(target, kw[i])
                                }
                                // ★ 同时搜 pager 链（2026-09-24）。
                                //   实测从 VM 出发零命中，怀疑内容在 pager/adapter 上 ——
                                //   那是 Android 侧真正驱动「当前显示哪一页」的结构，
                                //   与业务 VM 是两条独立的引用链。
                                try {
                                    val pagerRef = CfhState.pagerCache
                                    if (pagerRef != null) {
                                        searchByKeyword(pagerRef, kw[i])
                                    }
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                    // ★★★ 全 VM 普查（2026-09-24，每 90 秒一次）。
                    //
                    //   动机：主人报的 11 条**全部是短剧**且零记录，
                    //   而「短剧只要被扫描到就 100% 判脏」——
                    //   ⇒ 它们不在被扫描的那个 VM 上。
                    //
                    //   本普查从 Activity 出发找出**所有** ViewModel，
                    //   报告各自持有的内容列表 —— 定位短剧的真实数据源。
                    // ★★ 节流修复（2026-09-30，同型缺陷第三例）：
                    //   原判据 `tick % 90L == 0L` 按 tick 计数 ⇒ 追打态
                    //   200ms × 90 = **18 秒/次**（注释意图的 5 倍频），
                    //   平时态 1000ms × 90 = 90 秒/次。
                    //   现改为毫秒门控 [VM_SURVEY_INTERVAL_MS]=90_000ms：
                    //   **两种 tick 周期下都恒为 90 秒/次** ⇒ 追打态 18 秒 → 90 秒（降 5 倍）；
                    //   平时态本就 90 秒，维持不变。普查内容与搜索根未改。
                    val svNow = System.currentTimeMillis()
                    if (svNow - lastVmSurveyAt >= VM_SURVEY_INTERVAL_MS) {
                        lastVmSurveyAt = svNow
                        try { surveyAllViewModels() } catch (_: Throwable) {}
                    }
                }
            }
            Logger.evidence("PERIODIC", "started")
        } catch (e: Throwable) {
            Logger.evidence("PERIODIC", "start err ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 周期扫描「当前播放内容」（2026-09-24）。
     *
     * 与 `CfhDiag` 的可见页探针互补：那个挂在 Fragment 扫描上（不稳定），
     * 这个挂在周期任务上（稳定）。两者都记的话，抓不到的情况会大幅减少。
     *
     * ## ★ 取哪一条：不靠下标，靠「元素是否被判脏」（2026-09-24 二次修正）
     *
     * 第一版按下标取（`readCurrentIndex`），实测**失败**：
     * ```
     * PSCAN 下标=2 昵称="社恐熊猫"
     * PSCAN 下标=3 昵称="秉熙白"
     * PSCAN 下标=2 昵称="社恐熊猫"   ← 来回跳，一直停在同样两条
     * ```
     * 下标在 2/3 之间抖动，记录的全是固定的那两条 ——
     * 主人眼前的内容（如「schiff旭福官方海外」这种广告）**根本没进记录**。
     *
     * 根因：`readCurrentIndex` 靠「哪个方法变化最多」来选候选，
     * 而**页面静止时所有候选都不变**，于是锁到一个与"当前播放位置"
     * 无关的方法上。用它定位"用户正在看什么"是不可靠的。
     *
     * ## 改用什么判据
     *
     * 放弃「定位当前条」，改为**扫描全部条目并记录判脏的那些**：
     *   · 目标本来就是「找出漏拦的内容」，而不是「精确知道用户在看第几条」
     *   · 判脏条目的 id 会与主人报的名字对上 —— 这就够定位问题了
     *   · 不依赖任何下标方法，因此不会受方法名混淆/版本差异影响
     *
     * 去重仍按 photoId，保证同一条只记一次。
     */
    private fun periodicScanVisible(vm: Any) {
        // 限次：避免长时间运行时把文件写爆。
        // ★ 提高上限（2026-09-24）：原 3000。既然改为全量记录以便事后回溯，
        //   上限太紧会让后半段刷的内容完全无痕 —— 那正是报障内容常出现的时段。
        if (CfhState.periodicVisCount >= 20000) return
        try {
            // ★★★ 顺序修复（2026-09-24）—— 这是我自己引入的缺陷。
            //
            //   ## 实测症状
            //
            //   `PSCAN` 只有 14 条，最后一条停在 1790262843208，
            //   而同一时期 `WASH-INNER` 有 252 次、记录文件仍在增长
            //   （末尾 1790262909717）—— 即**周期任务在跑，但巡检没输出**。
            //
            //   ## 根因
            //
            //   原写法第一行是：
            //     `val lst = lastCleanTarget?.get() ?: return`
            //   一旦这个根失效（VM 重建后 lastCleanTarget 被 GC 或清空），
            //   **整个函数直接返回** —— 而下面「组件容器收集」的代码
            //   在它**之后**，于是连卡片短剧也不扫了。
            //
            //   这正好解释了：加了组件扫描后判脏率一度升到 92%，
            //   随后 PSCAN 归零 —— 不是没内容，是巡检根本没进来。
            //
            //   ## 修法
            //
            //   两个根**各自独立**地尝试收集，任一可用即继续：
            //     · VM 侧（lastCleanTarget）—— 信息流内容
            //     · Activity 侧（tracked）    —— 组件/卡片容器
            //   只有**两个都拿不到**才返回。
            val vmRoot = lastCleanTarget?.get()
            val actRoot = CfhState.tracked
            if (vmRoot == null && actRoot == null) return
            // 收集这一轮看到的全部内容条目（不定位"当前"，见上方说明）
            val items = ArrayList<Any>(256)
            if (vmRoot != null) {
                try {
                    items.addAll(collectContentItems(vmRoot, 200))
                } catch (_: Throwable) {}
            }
            // ★★★ 并入「组件容器」的内容（2026-09-24）—— 覆盖卡片/剧集页短剧。
            //
            //   DRAMASRC 普查发现页面上有此前完全没扫过的容器：
            //     `i@TangramComponentManager`        sz=21 含Qp=5
            //     `j@TangramComponentManager`        sz=11 含Qp=3
            //     `e@KmpSlideInformationComponent`   sz=71 含Qp=68
            //     `a@g`                              sz=29 含Qp=28
            //   而主人报障的 11 条**全部是卡片形态短剧**且零记录 ——
            //   它们挂在这些组件上，与信息流 VM 是两条独立引用链。
            if (actRoot != null) {
                try {
                    items.addAll(collectComponentItems(actRoot, 200))
                } catch (_: Throwable) {}
            }
            try {
                val vref = CfhState.visiblePhotoRef?.get()
                if (vref != null && items.none { it === vref }) {
                    items.add(vref)
                }
            } catch (_: Throwable) {}
            if (items.isEmpty()) return
            // ★★★ 本轮处理量上限（2026-09-24 性能护栏）。
            //
            //   实测：PSCAN 时间戳间隔 49 秒 / 41 秒 —— 单轮处理 200 条
            //   （每条都做判定 + 可能的移除遍历）把周期线程堵死。
            //
            //   本护栏把单轮处理量压到 60 条。
            //   代价：一轮扫不完全部内容；收益：**周期不再被饿死**，
            //   多轮累积的覆盖率远高于「跑一轮卡一分钟」。
            //   （内容条目按 id 去重，已处理过的下轮会跳过，所以不会重复劳动。）
            var processedThisRound = 0
            for (qp in items) {
                if (CfhState.periodicVisCount >= 20000) return
                if (processedThisRound >= 60) break
                processedThisRound++
                val pid = try {
                    CfhProbe.readPhotoId(qp)
                } catch (_: Throwable) {
                    null
                }
                if (pid.isNullOrBlank()) continue
                // ★★★ 去重修正（2026-09-27「第五个季节」「明阳安全科普」「Ai小喵」）。
                //
                // ## 原去重的缺陷
                //
                // `periodicVisSeen.add(pid)` 在判定**之前**执行 ——
                // 同一条内容一辈子只判一次。
                //
                // **但 AI 声明字段存在回填延迟（实测 0.7~5 秒，中位 2.2 秒）**：
                // 第一轮扫到时字段未到 ⇒ 判白 ⇒ seen 已记录 ⇒
                // **3 秒后字段到了，它也永远不会再被扫** ⇒ 永久漏拦。
                //
                // ## 修法：**判白且声明未回填的，不记 seen** ⇒ 下轮重扫
                //
                // 判脏/判白但字段齐全的 ⇒ 照旧记 seen（不重复劳动）。
                // 判白且声明空的 ⇒ 可能是"字段还没到" ⇒ 留给下轮。
                //   声明读取用 `readDisclaimer`（与判定同一口径）。
                //   每轮扫完它只多花一次判定的成本，直到字段回填判脏为止。
                val ent = (try {
                    Reflect.readAny(qp, "mEntity")
                } catch (_: Throwable) {
                    null
                }) ?: qp
                val pm = try {
                    Reflect.readAny(ent, "mPhotoMeta")
                } catch (_: Throwable) {
                    null
                }
                val declNow = try { CfhUtil.readDisclaimer(qp, ent, pm) } catch (_: Throwable) { null }
                val fieldReady = !declNow.isNullOrBlank()   // 声明已回填（或本就无声明字段的形态齐了）
                val (dirty, reason) = try {
                    CfhDecide.judgeFeed(qp)
                } catch (_: Throwable) {
                    false to null
                }
                // ★ 去重：判脏/字段齐 ⇒ 记 seen（一次定案）；
                //   判白且声明空 ⇒ **不记 seen**（等字段回填后下轮重判）
                if (dirty || fieldReady) {
                    CfhState.periodicVisSeen.add(pid)
                }
                CfhState.periodicVisCount++
                // ★★★ 判脏即入黑名单（2026-09-24）—— 不依赖 hit()。
                //
                //   ## 实测缺口（主人报「被爱的前提，一定是有颜值」）
                //
                //     3735821  VISDEL-SUM  含目标3 移除3 处   ← 删了 3 处
                //     3735857  PDEL        ★周期移除 3 处
                //     3739780  VISDUMP     判脏=true          ← 4 秒后还在
                //     3756321  VISDUMP     判脏=true          ← 又 17 秒后还在
                //
                //   删了 3 处却仍可见，且 `VISFDEL`（可见页强制移除）**没触发**。
                //
                //   ## 为什么没触发
                //
                //   `VISFDEL` 的判据是 `dirtyPhotoIds.contains(pid)`，
                //   而该名单只在 `CfhDecide.hit()` 里写入 ——
                //   **判定命中签名缓存时不会调 hit()**，于是名单为空，
                //   强制移除形同虚设。
                //
                //   ## 修法
                //
                //   在**判脏的当场**直接把 pid 写入黑名单，
                //   不依赖判定内部是否走了 hit()。
                //   这样无论判据路径如何（缓存/直接/深度），
                //   只要本轮判脏，`VISFDEL` 与 `BDEL` 就能接管。
                //
                //   上限沿用 512（与 dirtyPhotoIds 一致，满则删最旧——见 noteDirty）。
                if (dirty && !pid.isNullOrBlank()) {
                    try {
                        CfhState.noteDirty(pid, CfhDecide.lastHitReason ?: "manual")
                        CfhState.lastDirtySeenAt = System.currentTimeMillis()
                    } catch (_: Throwable) {}
                    // ★ 判脏即清洗展示字段（2026-09-25「淘金哥」）：
                    //   广告投放矩阵反复投喂同一条，渲染引用重灌使画面无法由数据层
                    //   移除——但文案/AI 角标 View 订阅的是脏项 QPhoto 本体，
                    //   清空 caption+声明后 View 立即刷新为空，广告文字与
                    //   「AI 生成」标从屏上消失（视频画面解耦，可能仍在播）。
                    //   PSCAN 拿到的与可见页常为同 id 不同实例 → 每个实例都清。
                    //   （单值字段兜底已并入 scrubShownDirty 内部，此处不再重复调用。）
                    // [已移除 2026-09-26] scrubShownDirty：功能早已删除，调用点清理
                }
                // ★★★ 全量记录（2026-09-24 第四次修正）—— 这是「事后可回溯」的前提。
                //
                //   ## 我犯的错
                //
                //   前几版只记「判脏」的 + 每 40 条一条样本。
                //   结果：主人报的内容若**判脏=false**（就是漏拦的那些），
                //   **根本不留任何痕迹** → 事后无法检索 → 我才不得不
                //   「让主人报名字 → 我改代码加关键词 → 编译安装 → 再去搜」，
                //   而那时内容早刷过去了。主人原话：
                //     「等你装上你是去服务器给我找出来么？」
                //   —— 完全正确，这个流程从设计上就不成立。
                //
                //   ## 改法
                 //
                //   **每条都记**。判脏与否不是记录条件 ——
                //   正因为需要事后判断「哪些该拦而没拦」，
                //   才必须把**判否的那些也留下**。
                //
                //   成本：一轮扫描几十条，1 秒一次，
                //   单行约 200 字节 → 每分钟约 10KB，
                //   evidence 有 4000 行上限、按行淘汰，可接受。
                //
                //   这样主人只需**正常刷一段**，之后我按名字检索即可，
                //   不需要任何时机配合。
                val isSample = (CfhState.periodicVisCount % 40) == 0
                if (!dirty && !isSample) {
                    // ★★★ 2026-09-29 v13.20 停用黑名单补删（用户定稿「网络层白名单拦截，
                    //   还要什么黑名单？」）：网络层每批逐条新鲜判定，DIRTY/PENDING
                    //   当场 remove —— 误判内容下批还能重新判 WHITE。
                    //   黑名单补删会把「一次误判」变成界面层 30 分钟反复删除
                    //   （50388 实测：粤菜黄师傅/可乐测评等正常内容被永久拉黑 ⇒
                    //   精选页每批拦 8-9 条 ⇒ 列表空 ⇒ 「无网络」）。
                    //   ⇒ 巡检不再查黑名单，本轮判否就放过（continue 原逻辑保留）。
                    if (false && CfhState.dirtyPhotoMap.containsKey(pid) &&
                        CfhState.blacklistDelCount < 5000
                    ) {
                        try {
                            var rm = if (vmRoot != null) {
                                CfhDiag.removeFromFragContainers(vmRoot, pid)
                            } else 0
                            if (rm == 0) rm = CfhDiag.removeFromFragContainers(CfhState.tracked, pid)
                            if (rm > 0) {
                                CfhState.blacklistDelCount++
                                if (CfhState.blacklistDelCount <= 60) {
                                    Logger.evidence(
                                        "BDEL",
                                        "★黑名单补删 $rm 处 " +
                                            "昵称=\"${CfhUtil.readUserName(qp, ent).take(16)}\" id=$pid"
                                    )
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    continue
                }
                // ★★★ 广告专项指纹（2026-09-24）——
                //   主人报「schiff旭福官方海外」这类**品牌官方号广告**，
                //   而本轮 PSCAN 里判脏的全是内容型（AI 声明/短剧），
                //   **一条广告都没有** ⇒ 广告走的不是同一套判据。
                //
                //   这里对每条内容额外探一遍「广告特征字段」，
                //   命中就写 ADTAG。目的是回答：
                //   **广告内容带的是哪些字段？**（不是继续猜）
                val adTag = try { CfhUtil.adFingerprint(qp, ent) } catch (_: Throwable) { null }
                if (adTag != null && CfhState.adFpCount < 60) {
                    CfhState.adFpCount++
                    Logger.evidence(
                        "ADTAG",
                        "★广告指纹[$adTag] 判脏=$dirty " +
                            "昵称=\"${CfhUtil.readUserName(qp, ent).take(20)}\" " +
                            "文案=\"${CfhUtil.readCaption(qp)?.take(30) ?: "-"}\" " +
                            "id=$pid"
                    )
                }
                // ★★★ 判脏即移除（2026-09-24 第五次修正）—— 修「判了不删」。
                //
                //   ## 实测证据（决定性）
                //
                //   把「判脏的 id」与「被移除的 id」做对照：
                //     判脏 8 条，被移除仅 7 条，且**差集明显存在**：
                //       判脏但未移除：5196591227162063982 / 5224175777970972044
                //                     5235434775829951559 / 5245004923755522743
                //                     5257952773759948716 / USVRi-pvtEo / ftZhG7cnkq8
                //
                //   ## 根因
                //
                //   两条链路挂在**不同的触发源**上：
                //     · 判脏 → `PSCAN`（周期任务，1 秒一次，稳定）
                //     · 移除 → `VISDEL`（由 `VISJUDGE` 触发，
                //               而 VISJUDGE 挂在 Fragment 扫描上，
                //               有和 VISDUMP 同样的**观测盲区**）
                //   周期那条判脏了，事件那条没跑 ⇒ **判了不删**。
                //
                //   ## 修法
                //
                //   把移除动作挂到周期任务里 —— 与判脏同一处、同一节奏。
                //   判脏即刻尝试移除，不再依赖另一个触发源。
                //
                //   安全护栏沿用 `removeFromFragContainers` 内部那一套
                //   （只动列表不动单值字段 / 按 photoId 匹配 /
                //     保留至少 1 项 / 跳过 `o` 与 `m*` 字段）。
                if (dirty && CfhState.periodicDelCount < 3000) {
                    try {
                        // ★★ 移除根修正（2026-09-24）。
                        //
                        //   上一版传 `CfhState.tracked`（那是 **Activity**），
                        //   实测 `PDEL` 零次 —— 14 条判脏，一次都没移除。
                        //
                        //   原因：`removeFromFragContainers` 用深度 3 遍历找内容列表，
                        //   从 Activity 出发时，列表藏在「Activity → ViewModel →
                        //   数据层」三级之后，**超出深度**，根本走不到。
                        //   （之前 `VISDEL` 能成功，是因为它传的是 **Fragment** ——
                        //     Fragment 到列表只有一级。）
                        //
                        //   修正：**用刚才扫描时用的同一个根**（`lst`，即
                        //   `lastCleanTarget`），它已被证明能到达内容列表 ——
                        //   上面这次扫描里，所有判脏项都是从这个根里取出来的。
                        //
                        //   两个根都试：先 VM 根（已验证可达），再 tracked（兜底）。
                        var removed = if (vmRoot != null) {
                            CfhDiag.removeFromFragContainers(vmRoot, pid)
                        } else 0
                        if (removed == 0) {
                            removed = CfhDiag.removeFromFragContainers(CfhState.tracked, pid)
                        }
                        // ★★★ 第三个移除起点：ViewModel（2026-09-24）。
                        //
                        //   ## 实测缺口
                        //
                        //   「老九讲故事」第68集的 4 个持有者全部删除：
                        //     [1] c@b  剩79  SynchronizedRandomAccessList
                        //     [2] b@q1 剩79  ArrayList
                        //     [3] a@a  剩79  ArrayList
                        //     [4] c@b  剩6   ArrayList
                        //   删除后 1.5 秒，`VISDUMP` **仍然看到该条**。
                        //
                        //   而 VISDUMP 是从 **Fragment 字段**取到它的 ——
                        //   说明真正的显示来源挂在 Fragment/VM 这条链上，
                        //   而前两个起点（`lst` = lastCleanTarget，`tracked` = Activity）
                        //   都没覆盖到那一条引用。
                        //
                        //   修法：补上 **VM** 作为第三个起点。
                        //   VM 是 `frag → vm → 数据层` 的中间环节，
                        //   也是 `VISDUMP` 判定时读 `mEntity` 的来源对象。
                        //
                        //   幂等：三次调用都按 photoId 精确匹配，
                        //   删不到即返回 0，重复无副作用。
                        if (removed == 0) {
                            val vmRefNow = CfhState.vmRef
                            if (vmRefNow != null) {
                                removed = CfhDiag.removeFromFragContainers(vmRefNow, pid)
                            }
                        }
                        if (removed > 0) {
                            CfhState.periodicDelCount++
                            // ★★ 跨轮重灌计数（2026-09-25「喵了个咪/宇宙爆米花/李清照」三连循环）：
                            //   同一 pid 反复「删净→隔几秒又出现」= 广告 SDK 页面级注入，
                            //   数据层每轮删净但永远删不完。计数达 3 轮 → 触发一次整页刷新
                            //   （快手自己重取 feed，新批次黑名单秒拦，错配组件随页重建消失）。
                            //   per-pid 计数，60s 无重灌则清零。
                            val nowMs = System.currentTimeMillis()
                            val refeedCount = synchronized(refreshGateLock) {
                                val last = refeedGate[pid]
                                val c = if (last != null && nowMs - last.second < 60_000L) last.first + 1 else 1
                                refeedGate[pid] = c to nowMs
                                if (refeedGate.size > 32) refeedGate.clear()
                                c
                            }
                            Logger.evidence(
                                "PDEL",
                                "★周期移除 $removed 处 " +
                                    "昵称=\"${CfhUtil.readUserName(qp, ent).take(16)}\" " +
                                    "id=$pid " +
                                    "判据=${reason ?: "-"}" +
                                    (if (refeedCount >= 2) " 重灌第${refeedCount}轮" else "")
                            )
                            // 跨轮重灌达 3 轮 → 整页刷新（60s 节流由 refreshGate 保证）
                            if (refeedCount >= 3) {
                                val gateOk = synchronized(refreshGateLock) {
                                    val t = refreshGate[pid]
                                    if (t != null && nowMs - t < 60_000L) false else {
                                        refreshGate[pid] = nowMs
                                        if (refreshGate.size > 32) refreshGate.clear()
                                        true
                                    }
                                }
                                if (gateOk) {
                                    try { CfhSupply.triggerRefresh() } catch (_: Throwable) {}
                                    Logger.evidence(
                                        "REFRESH",
                                        "★同 pid 重灌$refeedCount 轮 → 整页刷新 id=$pid " +
                                            "昵称=\"${CfhUtil.readUserName(qp, ent).take(16)}\""
                                    )
                                }
                            }
                            // ★★★ 移除后复检（2026-09-24）—— 判定「删不动」的确切机制。
                            //
                            //   ## 要回答的问题
                            //
                            //   实测「不惑电竞」：
                            //     移除 2 处 → 0.7 秒后 VISDUMP 仍判它可见。
                            //
                            //   两种可能，修法完全不同：
                            //     A. 它被**多个容器**持有，只删了其中一部分
                            //        → 修法：扩大删除范围（本版遍历已扩到 4000/深度6）
                            //     B. 删对了地方，但**快手立刻重新填充**（服务端/缓存）
                            //        → 修法：加 photoId 黑名单，在填充路径上拦截
                            //        （模块已有 `CfhState.dirtyPhotoIds` 可复用）
                            //
                            //   ## 怎么区分
                            //
                            //   删除后**立刻**再扫同一个根：
                            //     · 目标 id 再次出现 → 情况 B（被重建）
                            //     · 不再出现         → 情况 A（漏删了别的持有者）
                            //
                            //   只在「确实删掉了东西」时触发，成本可控。
                            try {
                                val recheckRoot = vmRoot ?: CfhState.tracked
                                val after = if (recheckRoot != null) {
                                    collectContentItems(recheckRoot, 200)
                                } else emptyList()
                                var still = 0
                                for (e2 in after) {
                                    val p2 = try {
                                        CfhProbe.readPhotoId(e2)
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    if (p2 != null && p2 == pid) still++
                                }
                                Logger.evidence(
                                    "PDEL-CHK",
                                    if (still == 0) "★复检：已消失（情况A-删净）"
                                    else "★复检：仍存在 $still 项（情况B-被重新填充）" +
                                        " 昵称=\"${CfhUtil.readUserName(qp, ent).take(14)}\" id=$pid"
                                )
                                // ★★★ 复检发现「仍存在」→ 立刻扩大移除起点（2026-09-24）。
                                //
                                //   实测「被爱的前提，一定是有颜值」：
                                //   从 vmRoot 删了 3 处，4 秒后 VISDUMP（Fragment 侧）
                                //   仍看到该条 —— 说明**真正的显示来源挂在
                                //   Fragment/Activity 那条链上**，而不在 vmRoot 里。
                                //
                                //   既然复检已经证明「还在」，就不必等下一轮：
                                //   当场换用 Activity 作为起点再删一次。
                                //   （幂等：按 photoId 精确匹配，删不到返回 0。）
                                if (still > 0) {
                                    try {
                                        val actNow = CfhState.tracked
                                        if (actNow != null) {
                                            val rm2 = CfhDiag.removeFromFragContainers(actNow, pid)
                                            if (rm2 > 0 && CfhState.visForceDelCount < 200) {
                                                CfhState.visForceDelCount++
                                                Logger.evidence(
                                                    "VISFDEL",
                                                    "★复检补删 $rm2 处（Activity 起点）id=$pid " +
                                                        "昵称=\"${CfhUtil.readUserName(qp, ent).take(14)}\""
                                                )
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                    // ★★★ 反复重灌 → 触发宿主整页刷新（2026-09-25「记枞君」）。
                                    //
                                    //   广告分发矩阵条目被删 41+8 处仍在屏（组件复用重绑），
                                    //   数据层删除追不上服务端重灌。刷新不是换条 —— 不写任何
                                    //   引用，而是让快手自己重取 feed（新批次下发，黑名单秒拦
                                    //   旧条，错配组件随整页重建消失）。
                                    //   节流：同一条 id 60s 内只触发一次。
                                    val nowMs = System.currentTimeMillis()
                                    val last = synchronized(refreshGateLock) {
                                        val t = refreshGate[pid]
                                        if (t != null && nowMs - t < 60_000L) t else {
                                            refreshGate[pid] = nowMs
                                            if (refreshGate.size > 32) refreshGate.clear()
                                            null
                                        }
                                    }
                                    if (last == null) {
                                        try { CfhSupply.triggerRefresh() } catch (_: Throwable) {}
                                        Logger.evidence(
                                            "REFRESH",
                                            "★复检仍存在 → 触发宿主整页刷新 id=$pid " +
                                                "昵称=\"${CfhUtil.readUserName(qp, ent).take(14)}\""
                                        )
                                    }
                                }
                            } catch (_: Throwable) {}
                        } else if (CfhState.periodicDelMissCount < 40) {
                            // 判脏却找不到可移除的位置 —— 这是重要信号：
                            // 说明该内容不在可移除的列表里（可能在快照上，
                            // 或持有者字段被排除规则跳过了）。
                            CfhState.periodicDelMissCount++
                            Logger.evidence(
                                "PDEL-MISS",
                                "判脏但未找到移除点 " +
                                    "昵称=\"" + CfhUtil.readUserName(qp, ent).take(16) + "\" " +
                                    "id=$pid " +
                                    "在黑名单=" + CfhState.dirtyPhotoMap.containsKey(pid)
                            )
                            // ★★★ 实验性（2026-09-26 用户指示「先试一下」）：
                            //   详情页的脏项只挂在 `NasaPhotoDetailFragment.M`（单值字段，
                            //   `FRAGFLD` 实证），置 null **不动列表结构** ——
                            //   与之前 ViewPager 崩溃场景（清 adapter 的列表）机制不同。
                            //   本试验验证：① 会不会崩 ② 角标/内容会不会消失。
                            try {
                                val fragNow = CfhCapture.currentFeedFragment()
                                if (fragNow != null &&
                                    fragNow.javaClass.name.endsWith("NasaPhotoDetailFragment")
                                ) {
                                    var zappedM = 0
                                    var c2: Class<*>? = fragNow.javaClass
                                    var l2 = 0
                                    while (c2 != null && c2 != Any::class.java && l2 < 4) {
                                        val cc2: Class<*> = c2
                                        for (f2 in Reflect.nonStaticFields(cc2)) {
                                            try {
                                                f2.isAccessible = true
                                                val fv = f2.get(fragNow) ?: continue
                                                if (CfhState.qpClassRef?.isInstance(fv) != true) continue
                                                val fpid = try { CfhProbe.readPhotoId(fv) } catch (_: Throwable) { null }
                                                if (fpid == pid) {
                                                    f2.set(fragNow, null)
                                                    zappedM++
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                        c2 = cc2.superclass; l2++
                                    }
                                    if (zappedM > 0) {
                                        Logger.evidence(
                                            "M-NULL",
                                            "★实验：详情页单值字段置null zapped=$zappedM id=$pid"
                                        )
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }
                Logger.evidence(
                    "PSCAN",
                    "判脏=$dirty 判据=${reason ?: "-"} " +
                        "昵称=\"${CfhUtil.readUserName(qp, ent).take(16)}\" " +
                        "文案=\"${CfhUtil.readCaption(qp)?.take(32) ?: "-"}\" " +
                        "id=$pid " +
                        "声明=\"${CfhUtil.readDisclaimer(qp, ent, pm)?.take(24) ?: "null"}\" " +
                        "styleId=${Reflect.readLong(pm, "mAiCutPhotoStyleId")} " +
                        "aiFp=${CfhUtil.aiFingerprint(qp, 3) ?: "无"}"
                )
            }
        } catch (_: Throwable) {}
    }

    /**
     * ★★★ 按名称全域搜索（2026-09-24）—— 定位「报障内容到底在哪个对象上」。
     *
     * ## 为什么需要（这是本轮排查的关键转折）
     *
     * 主人先后报了这些漏拦内容：
     *   「小鱼带你看世界」「满堂嘲讽，执手良缘」「鼠鼠巴啦啦」
     *   「泡泡追剧」「卿本佳人」「刘老根大舞台」「schiff旭福官方海外」
     *
     * 我把探针挂在 VM 列表上，反复改判据、改深度、改时机 ——
     * **但绝大多数报障内容从来没出现在任何记录里**。
     *
     * 这说明一个我一直没正面面对的可能：
     * **这些内容根本不在我扫描的那些列表上。**
     *
     * ## 本函数做什么
     *
     * 不再猜「哪个列表承载它」。反过来做：
     * 从 VM 出发宽度遍历对象图（深度 6、人数上限 4000），
     * 对每个对象的每个**字符串字段**做子串匹配 —— 命中目标词就记录
     * 「持有者类名 + 字段名 + 值」，从而**确定它的真实挂载点**。
     *
     * 只读、限次。命中即证明「该内容在 VM 可达范围内」，
     * 并给出确切位置；一条都不命中则证明它走的是 VM 之外的通道
     * （例如独立的广告 SDK / WebView），那也是决定性的结论。
     */
    fun searchByKeyword(vm: Any?, keyword: String) {
        if (vm == null || keyword.isBlank()) return
        try {
            val seen = java.util.HashSet<Any>()
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(vm, 0))
            seen.add(vm)
            var vis = 0
            val hits = StringBuilder()
            var hitCount = 0
            while (q.isNotEmpty() && vis < 4000 && hitCount < 12) {
                val nd = q.removeFirst()
                val o = nd[0]; val d = nd[1] as Int
                vis++
                if (d > 6) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 5) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            // ① 字符串字段命中关键词
                            if (v is String) {
                                if (v.contains(keyword, true)) {
                                    hitCount++
                                    hits.append("\n  ★${o.javaClass.name}.${f.name}=\"${v.take(40)}\"")
                                }
                                continue
                            }
                            // ② 其他对象入队（跳过 java./android./kotlin. 与集合元素）
                            val vn = v.javaClass.name
                            if (vn.startsWith("java.") && v !is java.util.Collection<*> &&
                                v !is java.util.Map<*, *>
                            ) continue
                            if (vn.startsWith("android.") || vn.startsWith("kotlin.") ||
                                v is android.view.View
                            ) continue
                            if (v is java.util.Collection<*>) {
                                for (e in v) {
                                    if (e == null || hitCount >= 12) continue
                                    val en = e.javaClass.name
                                    if (en.startsWith("java.") || en.startsWith("android.") ||
                                        en.startsWith("kotlin.")
                                    ) continue
                                    if (seen.add(e)) q.add(arrayOf(e, d + 1))
                                }
                                continue
                            }
                            if (seen.add(v)) q.add(arrayOf(v, d + 1))
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                }
            }
            Logger.evidence(
                "KWSEARCH",
                if (hitCount == 0) "关键词 \"$keyword\"：未在 VM 找到（访问$vis 对象）"
                else "关键词 \"$keyword\"：命中$hitCount 处（访问$vis 对象）$hits"
            )
        } catch (_: Throwable) {}
    }

    /**
     * ★★★ 全 VM 普查（2026-09-24）—— 找出短剧走的是哪个数据源。
     *
     * ## 为什么要做（本轮的关键推论）
     *
     * 主人的报障数据统计：
     *   · 抓到并判脏正确的：5 条（都是普通信息流内容）
     *   · **零记录的：11 条，全部是「短剧」**
     *
     * 而扫描数据表明：
     *   · 含「短剧」的条目被扫描到时，**判脏率 100%**（判否的一条都没有）
     *   · 即「短剧只要进扫描范围就必被拦」
     *   ⇒ 报障的短剧**根本不在扫描范围内**
     *
     * 而删除来源统计（409 次）全部来自：
     *   `SlidePlayViewModel.H` / `.V` / `.F` / `.t` / `.R3`
     * ⇒ 短剧走高概率**另一个 VM**，不挂在 `SlidePlayViewModel` 上。
     *
     * ## 本函数做什么
     *
     * 从当前 Activity 出发，找出**所有**看起来像 ViewModel 的对象
     * （类名含 ViewModel / Model，或继承自 androidx ViewModel），
     * 逐个尝试扫描其中的内容列表并统计。
     * 命中短剧字段的 VM 会被记录下来 —— 那就是要补的扫描根。
     *
     * 只读，限次。
     */
    fun surveyAllViewModels() {
        val act = CfhState.tracked ?: return
        try {
            val seen = java.util.HashSet<Any>()
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(act, 0))
            seen.add(act)
            var vis = 0
            val found = StringBuilder()
            var vmCount = 0
            while (q.isNotEmpty() && vis < 3000) {
                val nd = q.removeFirst()
                val o = nd[0]; val d = nd[1] as Int
                vis++
                if (d > 4) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 4) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v is java.util.Collection<*> || v is java.util.Map<*, *>) continue
                            val vn = v.javaClass.name
                            if (vn.startsWith("java.") || vn.startsWith("android.") ||
                                vn.startsWith("kotlin.") || v is android.view.View
                            ) continue
                            // 判定是否像 VM：类名或父类名含 ViewModel/Model，或包名含 viewmodel
                            var isVm = false
                            var ck: Class<*>? = v.javaClass
                            var kl = 0
                            while (ck != null && ck != Any::class.java && kl < 4) {
                                val kn = ck.name
                                if (kn.contains("ViewModel", true) || kn.contains("viewmodel")) {
                                    isVm = true; break
                                }
                                ck = ck.superclass; kl++
                            }
                            val simpleVn = v.javaClass.simpleName
                            if (isVm && vmCount < 24) {
                                vmCount++
                                // 统计该 VM 里的内容列表（size>=5 且过半可提 QPhoto）
                                var listInfo = ""
                                var ck2: Class<*>? = v.javaClass
                                var kl2 = 0
                                while (ck2 != null && ck2 != Any::class.java && kl2 < 4) {
                                    val ck2c: Class<*>? = ck2
                                    for (f2 in (ck2c ?: break).declaredFields) {
                                        if (java.lang.reflect.Modifier.isStatic(f2.modifiers)) continue
                                        try {
                                            f2.isAccessible = true
                                            val v2 = f2.get(v) ?: continue
                                            if (v2 is MutableList<*>) {
                                                if (v2.size < 5) continue
                                                var qp = 0; var n = 0
                                                for (e in v2) {
                                                    if (e == null) continue
                                                    n++
                                                    if (CfhState.qpClassRef?.isInstance(e) == true ||
                                                        CfhProbe.findQpInObject(e) != null
                                                    ) qp++
                                                }
                                                if (n > 0 && qp * 2 > n) {
                                                    listInfo += "${f2.name}(sz=${v2.size}) "
                                                }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                    ck2 = ck2c?.superclass; kl2++
                                }
                                if (listInfo.isNotEmpty()) {
                                    found.append("\n  VM[${v.javaClass.name}] 内容列表: $listInfo")
                                }
                            }
                            if (seen.add(v)) q.add(arrayOf(v, d + 1))
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                }
            }
            Logger.evidence(
                "VMSURVEY",
                if (found.isEmpty()) "未找到含内容列表的 VM（访问$vis 对象，判定${vmCount}个VM）"
                else "找到含内容列表的 VM（访问$vis 对象）:$found"
            )
        } catch (_: Throwable) {}
    }

    /**
     * ★★★ 短剧卡片页数据源定位（2026-09-24）—— 针对「B 形态」。
     *
     * ## 背景（主人确认的形态）
     *
     * 主人报障 13 条，其中 11 条零记录，**全部是短剧**。
     * 主人确认这些短剧的屏幕形态是 **B**：
     *   · 卡片/剧集页 —— 有「看全集」「第N集」按钮、剧名标题、
     *     可能整屏是竖排剧集列表
     *   （A = 普通全屏视频，能上下滑走；那类我已在扫且工作正常）
     *
     * ## 为什么我全部探针都抓不到
     *
     * 所有探针的根都是 `SlidePlayViewModel`（**信息流 VM**）：
     *   · `PSCAN`      → 扫它的内容列表
     *   · `KWSEARCH`   → 从它出发做字符串搜索（4000 对象零命中）
     *   · `VISDUMP`    → 从 Fragment 字段取 QPhoto
     *
     * 而 B 形态走的是**独立的短剧页 VM / 卡片组件** ——
     * 那是另一个对象图，我的代码**一行都没覆盖**。
     * 「KWSEARCH 关键词全部未在 VM 找到」正是这个原因，
     * 而不是那些内容不存在于内存。
     *
     * ## 本函数做什么
     *
     * 从 **Fragment**（而不是 VM/Activity）出发，遍历对象图：
     *   ① 找出所有持有「短剧特征字段」的对象
     *      （`mKwAppNativeDrama` / `mNovelDrama` / `mLongToShortDrama` /
     *        `mSerialInfo` / `mDramaInfo` / `mAdNovelVideoMeta`）
     *   ② 找出所有持有 QPhoto 的列表（不论容器类名）
     *   ③ 把这些对象的**持有者类名 + 字段名**记下来
     *
     * 目的是回答：**短剧卡片的数据挂在哪条引用链上？**
     * 拿到答案后，把扫描/删除的根补上那一条即可。
     *
     * 只读，限次。
     */
    fun surveyDramaSource(frag: Any?) {
        if (frag == null) return
        try {
            val seen = java.util.HashSet<Any>()
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(frag, 0))
            seen.add(frag)
            var vis = 0
            val dramaHolders = StringBuilder()
            val listHolders = StringBuilder()
            var dramaCount = 0
            var listCount = 0
            val dramaFields = arrayOf(
                "mKwAppNativeDrama", "mNovelDrama", "mLongToShortDrama",
                "mSerialInfo", "mDramaInfo", "mAdNovelVideoMeta",
                "mKwAppMeta", "mColumnMeta", "mTubeModel"
            )
            while (q.isNotEmpty() && vis < 4000) {
                val nd = q.removeFirst()
                val o = nd[0]; val d = nd[1] as Int
                vis++
                if (d > 5) continue
                // ① 检查该对象是否带短剧特征字段
                if (dramaCount < 20) {
                    for (df in dramaFields) {
                        try {
                            val dv = Reflect.readAny(o, df) ?: continue
                            dramaCount++
                            dramaHolders.append(
                                "\n  ★${o.javaClass.name}.$df=(${dv.javaClass.simpleName})"
                            )
                            break
                        } catch (_: Throwable) {}
                    }
                }
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 4) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v is MutableList<*>) {
                                if (v.size < 1) continue
                                // ② 记录持有 QPhoto 的列表（不论容器类名）
                                if (listCount < 30) {
                                    var qp = 0; var n = 0
                                    for (e in v) {
                                        if (e == null) continue
                                        n++
                                        if (CfhState.qpClassRef?.isInstance(e) == true ||
                                            CfhProbe.findQpInObject(e) != null
                                        ) qp++
                                    }
                                    if (qp > 0) {
                                        listCount++
                                        listHolders.append(
                                            "\n  L ${f.name}@${o.javaClass.name.substringAfterLast('.')} " +
                                                "sz=${v.size} 含Qp=$qp 容器=${v.javaClass.simpleName}"
                                        )
                                    }
                                }
                                for (e in v) {
                                    if (e == null) continue
                                    val en = e.javaClass.name
                                    if (en.startsWith("java.") || en.startsWith("android.") ||
                                        en.startsWith("kotlin.")
                                    ) continue
                                    if (seen.add(e)) q.add(arrayOf(e, d + 1))
                                }
                                continue
                            }
                            val vn = v.javaClass.name
                            if (vn.startsWith("java.") || vn.startsWith("android.") ||
                                vn.startsWith("kotlin.") || v is android.view.View
                            ) continue
                            if (seen.add(v)) q.add(arrayOf(v, d + 1))
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                }
            }
            Logger.evidence(
                "DRAMASRC",
                "从 Fragment 出发（访问$vis 对象）\n" +
                    "短剧特征持有者（$dramaCount）:$dramaHolders\n" +
                    "持 QPhoto 的列表（$listCount）:$listHolders"
            )
        } catch (_: Throwable) {}
    }

    /**
     * ★★★ 补充扫描根（2026-09-24）—— 覆盖「卡片/剧集页」短剧。
     *
     * ## 性能修正（同日，实测 40 秒/次 → 必须降到毫秒级）
     *
     * 第一版实测：`PSCAN` 时间戳间隔 **49 秒 / 41 秒** ——
     * 整个周期线程被这个函数堵死（`PERIODIC` tick 被饿死，
     * `PSCAN` 一度完全停摆）。
     *
     * 根因是双重遍历：
     *   ① 从 Activity 出发宽度遍历 **4000 个对象**
     *   ② 对**每个列表的每个元素**调 `CfhProbe.findQpInObject(e)`
     *      —— 那本身是一次 BFS ⇒ 整体 N²，必然爆掉
     *
     * ## 本版做法（三条降本措施）
     *
     *   ① **限定扫描根**：不再从 Activity 全图遍历，而是只找
     *      **组件管理器类**（类名含 `ComponentManager` / `Component`），
     *      它们是 DRAMASRC 实测到的卡片容器持有者。
     *      对象上限 4000 → **800**。
     *   ② **只用便宜的判据**：元素是否 QPhoto 改看
     *      `CfhState.qpClassRef.isInstance(e)`（一次 O(1) 类型判断），
     *      **不再对每个元素做 BFS**。找不到 BFS 兜底也不影响 ——
     *      DRAMASRC 实测这些容器里 68/71、28/29、5/21 都是**直接** QPhoto。
     *   ③ 结果**缓存 3 秒**：组件容器的内容不会每毫秒变，
     *      1 秒一次的巡检没必要重复遍历同一棵树。
     *
     * 只读；返回找到的条目（上限 limit）。
     */
    private var compCacheAt = 0L
    private var compCacheRoot: Any? = null
    private var compCacheItems: List<Any> = emptyList()

    // ★ 重建时补的状态字段（原误删）
    /** MINDEL / QPDEL 落盘限次 */
    var minDelCount = 0
    /** SRCDIRTY 落盘限次 */
    var srcDirtyCount = 0
    /** SRCELEM 落盘限次 */
    var srcElemCount = 0
    /** REFRESH 触发节流：pid → 上次触发时刻（复检仍存在的条目 60s 内只刷一次） */
    private val refreshGate = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 跨轮重灌计数：pid → (轮数, 最后时刻)。60s 无重灌清零；达 3 轮触发整页刷新 */
    private val refeedGate = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    private val refreshGateLock = Any()


    /**
     * ★★ 脏项展示字段清洗（2026-09-25）—— 修「正常视频顶着脏文案+AI角标」。
     *
     * ## 错配机制（实证：倪妮科目二视频 + 「悟空宁」文案同屏）
     *
     * 文案 View / AI 角标 View 订阅的是**旧脏项 QPhoto**（DefaultSyncable 响应式流）。
     * 换条只改了 M/o.mPhoto/itemContext 的引用 → 视频层绑净项，但文案 View 仍持有
     * 脏项引用且其 caption 永不再变化 → 脏文案+角标**永久残留**在净项视频上。
     *
     * ## 修法
     *
     * 对脏项本体（黑名单成员，决定性判据产物）清展示字段：
     *   ① `setCaption("")` —— QPhoto 的 public 响应式写法（走 lwe.e 同步 →
     *      订阅它的文案 View 收到变更自动刷新为空）；
     *   ② 反射清 `pm.mDisclaimergeMessageV2.content` —— AI 角标消失。
     *
     * 只清**已判脏**的条目：正常内容永远不会走到这里，零误伤。
     */
    /**
     * 已清洗对象 → 上次清洗时刻（**节流**用，非永久去重）。
     *
     * ## 为什么是「按时间节流」而不是「永久去重」
     *
     * 最初想用 `IdentityHashMap` 永久去重（同实例只清一次），**实测否证**：
     *
     * ```
     * 1790345804230 SCRUBCAP   ← 清
     * 1790345804251 HITDEL     ← 宿主删除
     * 1790345806657 SCRUBCAP   ← 2.4 秒后重新清（宿主已把字段填回来）
     * ```
     *
     * 即：**宿主会在删除/重绑后重新填充 caption**，同一实例**确实需要再次清洗**。
     * 永久去重会把这些「必要的重清」一并挡掉 ⇒ 屏幕重新出现脏文案。
     *
     * ## 实际浪费在哪
     *
     * 冷启动实测同一 id 在 **6.6 秒内被清 117 次（≈18 次/秒）**，
     * 而 **DRAMA 判脏只有 3 次** —— 说明绝大多数是同一时刻的**并发重复调用**
     * （9 个调用点、多线程、同一实例在极短时间内被反复清）。
     * 真正需要的是**合并同一瞬间的重复**，而不是禁止后续重清。
     *
     * ## 阈值选择
     *
     * `SCRUB_THROTTLE_MS = 200ms`：
     * - 足以合并同一瞬间的并发重复（实测重复间隔多在 10–20ms）
     * - 远小于「宿主重填 → 需要重清」的间隔（实测 2.4 秒）
     * - 与项目既有节流惯例一致（`CfhClean:209` 的 200ms 内容指纹节流）
     *
     * 容量上限 512，满则整体清空（与 `dirtyPhotoMap` 同策略）。
     */
    private val scrubAt =
        java.util.IdentityHashMap<Any, Long>()

    private const val SCRUB_THROTTLE_MS = 200L

    // [已删除 2026-09-26 用户定稿「把代码清干净」]
    //
    // 删除的两个函数：
    //   · `scrubShownDirty(dirtyQp)`   —— 清 QPhoto 的展示字段（昵称/文案/标识）
    //   · `scrubFragmentHolders(pid)`  —— 清 Fragment 单值字段的展示信息
    //
    // ## 删除理由（实测证据，全部可复核）
    //
    // 1. **功能本身无效**：清的是字段，而**承载卡片仍在列表里占位**
    //    （`b@q1`/`c@b` 实测 `size=2`，被 `ml.size > 3` 门槛挡住删不掉）
    //    ⇒ 脏数据卡片永久占位，正常视频进不来。
    //
    // 2. **实测副作用明显**：
    //    | 指标 | 加此功能前 | 加此功能后 |
    //    |---|---|---|
    //    | `判脏=true` | 69 | 102 |
    //    | **`判脏=false`（正常内容）** | **97** | **35（↓64%）** |
    //
    // 3. **历史成本**：`SCRUBCAP` 累计 2390 次高频反射写字段，**收益为零**。
    //
    // ## 用户原话
    //
    // > 「昵称、文案、标识这些拦截功能……应该删掉这个功能，完全没用还干扰正常使用」
    //
    // ## 若日后要重做
    //
    // 正确方向**不是**清字段，而是**移除承载项本身**。
    // 见 `docs/交互图/诊断修正-载体移除被size门槛卡死.md`。

    // ==================== 以下为响应式清洗链路（2026-09-26 重建）====================
    //
    // ⚠️ **重建说明**：这些函数在 2026-09-26 的一次「删除 scrubShownDirty」操作中
    // 被**误删**（行号范围删除多删了约 1900 行）。此处按**反编译产物**（`CfhWash.dex`）
    // 的调用顺序与语义**重建**。
    //
    // 重建依据：
    //   · `filterVmListsInner` 的反编译调用顺序（实证）：
    //     ```
    //     Logger.evidence("WASH-INNER", "进入内层 vm=...")
    //     auditCandidateLists(obj)
    //     cleanMinimalLists(obj)
    //     startPeriodicClean()
    //     if (!CfhState.qpDirectListsCollected) collectQpDirectLists(obj)
    //     probeSourceDirty(obj)
    //     ...（后续：VM 字段 dump / 真源清洗）
    //     ```
    //   · 调用点契约：`filterVmLists`（:72）与 `periodicScanVisible`（:810）传 `target`
    //   · 各函数的诊断标签（`MINDEL` / `QPDEL` / `WASHDEL` / `WASHSEL`）

    /**
     * ★ VM 列表清洗内层（原本是清洗链路的真正执行体）。
     *
     * ## 职责（从反编译调用顺序还原）
     *
     * ```
     * ① 留痕 WASH-INNER
     * ② auditCandidateLists(obj)   候选列表审计（诊断，只读）
     * ③ cleanMinimalLists(obj)     清洗「元素直接是 QPhoto」的小列表
     * ④ startPeriodicClean()       起周期兜底任务
     * ⑤ collectQpDirectLists(obj)  收集 QPhoto 直取列表（一次性）
     * ⑥ probeSourceDirty(obj)      真源残留探测（诊断）
     * ⑦ 逐字段清洗（VM 字段链上的 List）
     * ```
     *
     * ## 安全
     *
     * · 全部包在 try 内，异常不影响调用方
     * · 各子函数内部自带节流与收敛判断
     */
    private fun filterVmListsInner(obj: Any) {
        try {
            // ★★★ 落盘限流（2026-10-01，性能 Top⑤）—— 原为全文件**唯一无护栏**的
            //   evidence 调用（同文件其它调用点普遍带 `xxx < N` 计数或毫秒门控）。
            //   实测该 tag 21,741 行（全文件第 2 大 tag），平时态随 tick 永久写。
            //
            //   形态选择：**滑动窗口**（[RateLimiter]）而不是 `xxxLog < N` 进程级限次。
            //   理由：本 tag 是频率指示器（验证协议 `grep -c WASH-INNER` 靠它判断
            //   清洗链路是否在跑），而进程级限次**N 次后永久静默**
            //   （见 RateLimiter 类注释：TTPPARSE-BLOCK=80 / PV2-BLOCK=60 卡死后全无记录），
            //   崩溃/故障前那段最需要它的时间恰恰会变成空白。
            //   上限 [WASH_INNER_LOG_PER_MIN]=60 行/分钟（取值依据见该常量 KDoc）。
            //   **只限日志、不清洗** —— 下面的清洗步骤照旧执行。
            if (RateLimiter.allow("WASH-INNER", perMinute = WASH_INNER_LOG_PER_MIN)) {
                Logger.evidence("WASH-INNER", "进入内层 vm=${obj.javaClass.simpleName}")
            }
        } catch (_: Throwable) {}
        try { auditCandidateLists(obj) } catch (_: Throwable) {}
        try { cleanMinimalLists(obj) } catch (_: Throwable) {}
        try { startPeriodicClean() } catch (_: Throwable) {}
        try {
            if (!CfhState.qpDirectListsCollected) collectQpDirectLists(obj)
        } catch (_: Throwable) {}
        try { probeSourceDirty(obj) } catch (_: Throwable) {}
        // ★★★ 2026-09-28「芯飞动漫」根因修复：详情页数据源列表 `vm.h.f.o`
        //   字段名是 "o" —— `cleanMinimalLists` 的 `fn=="o"` 排除让它被永久
        //   跳过；`filterVmListsInner` 逐字段清洗又非 BFS，到不了嵌套对象。
        //   → 脏条停在数据源列表，M（泛型擦除方法）直接取走上屏。
        //   补「嵌套 QPhoto 列表」清洗：BFS 限深 4，只清洗含**直接 QPhoto**
        //   元素的 MutableList；cleanListInPlace 只删判脏 + 保留 KEEP_MIN +
        //   白名单守卫 ⇒ 不会误删框架数据。
        try { cleanNestedQpLists(obj) } catch (_: Throwable) {}
        // ⑦ 逐字段清洗：VM 字段链上的所有 List，清洗「元素直接是 QPhoto」的
        try {
            val qc = CfhState.qpClassRef ?: return
            var c: Class<*>? = obj.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 5) {
                val cur: Class<*> = c
                for (f in Reflect.nonStaticFields(cur)) {
                    try {
                        f.isAccessible = true
                        val v = f.get(obj) ?: continue
                        if (v !is MutableList<*>) continue
                        @Suppress("UNCHECKED_CAST")
                        val ml = v as MutableList<Any?>
                        if (ml.isEmpty()) continue
                        // 只处理「首元素直接是 QPhoto」的列表（不碰包装类）
                        val first = ml.firstOrNull { it != null } ?: continue
                        if (!qc.isInstance(first)) continue
                        cleanListInPlace(ml, "vmField.${f.name}")
                    } catch (_: Throwable) {}
                }
                c = cur.superclass; lvl++
            }
        } catch (_: Throwable) {}
    }

    /**
     * 就地清洗「元素直接是 QPhoto」的列表。
     *
     * **安全护栏**（吸取三次崩溃的教训）：
     *   · 只 `removeAt`，不 `clear`（保留列表对象身份）
     *   · 删到剩 [KEEP_MIN] 条即停（防「无更多作品」）
     *   · 白名单未开启 ⇒ 完全不动
     *
     * @return 删除条数
     */
    private fun cleanListInPlace(list: MutableList<Any?>, tag: String): Int {
        if (!CfhState.whiteListEnabled) return 0
        var removed = 0
        try {
            var i = list.size - 1
            while (i >= 0 && list.size > KEEP_MIN) {
                val q = list.getOrNull(i)
                if (q != null) {
                    // ★ 两级提取（对齐原版语义，反编译实证 :5086-5089）：
                    //   ① `shouldFilterFeed`（通用判定，内部走 findQpInObject 深度 2）
                    //   ② 判定为「不脏」时，用 `findSlotQpViaMb` 走 `m.b` 路径兜底再判
                    //      —— 某些包装类不直接持有 QPhoto，而是通过 b 字段链间接持有
                    var dirty = try { CfhDecide.shouldFilterFeed(q) } catch (_: Throwable) { false }
                    if (!dirty) {
                        val viaMb = try { findSlotQpViaMb(q) } catch (_: Throwable) { null }
                        if (viaMb != null) {
                            dirty = try { CfhDecide.shouldFilterContent(viaMb) }
                                    catch (_: Throwable) { false }
                        }
                    }
                    if (dirty) {
                        try { list.removeAt(i); removed++ } catch (_: Throwable) {}
                    }
                }
                i--
            }
            if (removed > 0 && CfhState.minDelCount < 40) {
                CfhState.minDelCount++
                Logger.evidence(
                    "MINDEL",
                    "$tag 删${removed}条 剩${list.size} 判据=${CfhDecide.lastHitReason ?: "?"}"
                )
            }
            // ★ 详情日志（对齐原版 :10671）—— 清洗后打印前 3 项，便于核验删对了没有
            if (removed > 0) {
                try { washDiagDetail(list, removed) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return removed
    }

    /** 单个列表最少保留条数（防「无更多作品」） */
    private const val KEEP_MIN = 1

    /**
     * 页面清理事件（反编译实证：`filterVmListsInner` 内调用）。
     *
     * 由各子函数负责具体清理；本函数是**统一入口**，便于日后扩展。
     */
    private fun onPageClean(obj: Any) {
        try { cleanMinimalLists(obj) } catch (_: Throwable) {}
    }

    /**
     * ★ 候选列表审计（诊断，只读）。
     *
     * ## 作用（反编译 + 标签实证）
     *
     * 一次性遍历 VM 对象图，列出「含 QPhoto 的列表」及其统计信息，
     * 输出 `QPLISTS` 证据行。**只读，不做任何删除。**
     *
     * 实证输出格式（来自历史证据）：
     * ```
     * [QPLISTS] 含 QPhoto 的列表 12 个: vm.h.e.mParent.A.mPresenters(sz=112 脏=7 ...
     * [MAINLIST] 遍历 978 对象，尺寸≥8 的列表 11 个: vm.i(sz=9,hc=...) | ...
     * [MAININFO] vm.f.h.c size=42 可提QP=10 判脏=10 元素类=...
     * ```
     *
     * ## 为什么保留
     *
     * 排障时「哪个列表装内容」是最常问的问题 ——
     * 保留只读审计能让用户/AI 快速定位，成本仅一次性遍历。
     */
    private fun auditCandidateLists(obj: Any) {
        if (CfhState.listAudited) return
        CfhState.listAudited = true
        try {
            val qc = CfhState.qpClassRef ?: return
            val rows = ArrayList<String>()
            var visited = 0
            val seen = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
            )
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(obj, "vm", 0))
            seen.add(obj)
            while (q.isNotEmpty() && visited < 3000) {
                val node = q.removeFirst()
                val o = node[0]
                val path = node[1] as String
                val d = node[2] as Int
                visited++
                if (d > 4) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cur: Class<*> = c
                    for (f in Reflect.nonStaticFields(cur)) {
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            val p = "$path.${f.name}"
                            if (v is List<*>) {
                                if (v.isNotEmpty()) {
                                    val first = v.firstOrNull { it != null }
                                    val qpCnt = v.count { it != null && qc.isInstance(it) }
                                    val dirtyCnt = if (qpCnt > 0) {
                                        v.count { e ->
                                            e != null && qc.isInstance(e) &&
                                                (try { CfhDecide.shouldFilterFeed(e) } catch (_: Throwable) { false })
                                        }
                                    } else 0
                                    if (qpCnt > 0 || v.size >= 8) {
                                        rows.add(
                                            "$p(sz=${v.size} 脏=$dirtyCnt " +
                                                "首=${first?.javaClass?.simpleName ?: "-"})"
                                        )
                                    }
                                }
                                if (seen.add(v)) q.add(arrayOf(v, p, d + 1))
                            } else {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && v !is android.view.View &&
                                    seen.add(v)
                                ) {
                                    q.add(arrayOf(v, p, d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cur.superclass; lvl++
                }
            }
            if (rows.isNotEmpty()) {
                Logger.evidence(
                    "QPLISTS",
                    "含 QPhoto 的列表 ${rows.size} 个（遍历 $visited 对象）: " +
                        rows.take(14).joinToString(" | ")
                )
            }
        } catch (_: Throwable) {}
    }

    /**
     * ★ 清洗「小列表」（反编译实证：`filterVmListsInner` 内第 2 步）。
     *
     * ## 语义（从标签 `MINDEL` + 历史证据还原）
     *
     * 遍历 VM 对象图（深度限制 4、跳过集合/映射），对每个
     * **「元素直接是 QPhoto」的列表**执行就地清洗。
     *
     * ## 与 `filterVmListsInner` 末段的关系
     *
     * `filterVmListsInner` 只清 **VM 顶层字段链**上的列表；
     * 本函数做**更深的对象图遍历**（含嵌套对象内部的列表），
     * 两者互补 —— 这也是「有时漏有时不漏」的修法之一。
     *
     * ## 排除字段（反编译实证：有永久排除逻辑）
     *
     * `o`（某些框架对象）与 `m*`（框架管理器）—— 清它们会破坏功能。
     *
     * ## 安全
     *
     * · 只处理「元素直接是 QPhoto」的列表（不碰包装类）
     * · 保留 [KEEP_MIN] 条
     * · 深度限制 4 + 访问上限 3000（成本可控）
     */
    private fun cleanMinimalLists(obj: Any) {
        if (!CfhState.whiteListEnabled) return
        try {
            val qc = CfhState.qpClassRef ?: return
            var vis = 0
            var totalRemoved = 0
            val seen = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
            )
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(obj, 0))
            seen.add(obj)
            while (q.isNotEmpty() && vis < 3000) {
                val node = q.removeFirst()
                val o = node[0] ?: continue
                val d = node[1] as Int
                vis++
                if (d > 4) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cur: Class<*> = c
                    for (f in Reflect.nonStaticFields(cur)) {
                        // ★ 永久排除：框架字段（清它们会破坏功能）
                        val fn = f.name
                        if (fn == "o" || fn.startsWith("m")) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v is MutableList<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val ml = v as MutableList<Any?>
                                if (ml.isEmpty()) continue
                                val first = ml.firstOrNull { it != null } ?: continue
                                // 只处理「元素直接是 QPhoto」
                                if (!qc.isInstance(first)) continue
                                totalRemoved += cleanListInPlace(ml, "min.${fn}")
                            } else {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && v !is android.view.View &&
                                    seen.add(v)
                                ) {
                                    q.add(arrayOf(v, d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cur.superclass; lvl++
                }
            }
            if (totalRemoved > 0) {
                CfhState.lastCleanRemoved = totalRemoved
            }
        } catch (_: Throwable) {}
    }

    /**
     * ★ 嵌套 QPhoto 列表清洗（2026-09-28「芯飞动漫」根因修复）。
     *
     * ## 为什么必须有这个函数
     *
     * `cleanMinimalLists` 用 `fn == "o" || fn.startsWith("m")` 永久排除「框架
     * 字段」，但 **详情页数据源列表字段名就是 `o`**（`vm.h.f.o`，SRCDIRTY
     * 实证残留 1/4）—— 被护栏跳过，脏条停在数据源，M 直接取走上屏。
     * `filterVmListsInner` 的逐字段清洗也不是 BFS，到不了嵌套对象。
     *
     * ## 安全护栏（对齐 cleanListInPlace 语义）
     *
     * · 只清洗「含**直接 QPhoto** 元素」的 MutableList（数据源列表特征）
     * · cleanListInPlace 只删判脏 + 保留 KEEP_MIN + 白名单守卫 ⇒ 不误删
     * · BFS 限深 4 / 访问上限 2000，防解析线程拖垮（对齐 probeSourceDirty）
     */
    private fun cleanNestedQpLists(obj: Any) {
        if (!CfhState.whiteListEnabled) return
        try {
            val qc = CfhState.qpClassRef ?: return
            var vis = 0
            var totalRemoved = 0
            val seen = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
            )
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(obj, 0))
            seen.add(obj)
            while (q.isNotEmpty() && vis < 2000) {
                val node = q.removeFirst()
                val o = node[0] ?: continue
                val d = node[1] as Int
                vis++
                if (d > 4) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 4) {
                    val cur: Class<*> = c
                    for (f in Reflect.nonStaticFields(cur)) {
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v is MutableList<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val ml = v as MutableList<Any?>
                                if (ml.isEmpty()) continue
                                // 只处理含「直接 QPhoto」元素的列表（数据源特征）
                                var hasDirect = false
                                for (e in ml) {
                                    if (e != null && qc.isInstance(e)) { hasDirect = true; break }
                                }
                                if (!hasDirect) continue
                                val rm = cleanListInPlace(ml, "nestedQp.${f.name}")
                                if (rm > 0) {
                                    totalRemoved += rm
                                    if (CfhState.nestedQpDelCount < 40) {
                                        CfhState.nestedQpDelCount++
                                        Logger.evidence(
                                            "NQPDEL",
                                            "★嵌套QPhoto列表 ${f.name} 删$rm 剩${ml.size} 判据=${CfhDecide.lastHitReason ?: "?"}"
                                        )
                                    }
                                }
                            } else {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && v !is android.view.View &&
                                    seen.add(v)
                                ) {
                                    q.add(arrayOf(v, d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cur.superclass; lvl++
                }
            }
            if (totalRemoved > 0) CfhState.lastCleanRemoved = totalRemoved
        } catch (_: Throwable) {}
    }

    /**
     * ★ 从 `qpDirectLists` 清洗（反编译实证：独立函数，标签 `QPDEL`）。
     *
     * ## 数据来源
     *
     * `CfhState.qpDirectLists` —— 由 [collectQpDirectLists] 收集的
     * **「元素直接是 QPhoto」的列表集合**。
     *
     * ## 与 `cleanMinimalLists` 的区别
     *
     * `cleanMinimalLists` 每次都重新遍历对象图（贵）；
     * 本函数直接用**已收集的列表引用**（快）—— 作为高频补充路径。
     *
     * ## 实证输出
     * ```
     * [QPDEL] 清洗内容列表 删3条 覆盖2个列表 判据=ai:disclaimer
     * ```
     */
    private fun cleanCollectedLists() {
        if (!CfhState.whiteListEnabled) return
        var total = 0
        try {
            val lists = CfhState.qpDirectLists
            for (lst in lists) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val ml = lst as? MutableList<Any?> ?: continue
                    if (ml.isEmpty()) continue
                    var i = ml.size - 1
                    while (i >= 0 && ml.size > KEEP_MIN) {
                        val e = ml.getOrNull(i)
                        if (e != null) {
                            val dirty = try { CfhDecide.shouldFilterContent(e) } catch (_: Throwable) { false }
                            if (dirty) {
                                try { ml.removeAt(i); total++ } catch (_: Throwable) {}
                            }
                        }
                        i--
                    }
                } catch (_: Throwable) {}
            }
            if (total > 0 && CfhState.qpDirectDelCount < 40) {
                CfhState.qpDirectDelCount++
                Logger.evidence(
                    "QPDEL",
                    "清洗内容列表 删${total}条 覆盖${lists.size}个列表 " +
                        "判据=${CfhDecide.lastHitReason ?: "?"}"
                )
            }
        } catch (_: Throwable) {}
    }

    /**
     * [已停用] `washSelectedLists` —— 清理「首页卡片当前条」列表。
     *
     * ## 为什么是空实现（保留签名）
     *
     * 该函数曾用于清洗 `vm.h.f.o` / `h.m.p.a` / `h.t.d.f.c` 三个「当前条持有位」。
     *
     * **实测造成 ViewPager 崩溃**：
     * ```
     * java.lang.IllegalStateException:
     *   The application's PagerAdapter changed the adapter's contents
     *   without calling PagerAdapter#notifyDataSetChanged!
     *   Expected adapter item count: 1000000, found: 0
     *   Problematic adapter: class d5c.e
     * ```
     * —— `vm.h.f.o` 的持有者 `d5c.e` 是 **ViewPager 的 adapter**，
     * 清空它等于改了 adapter 内容却没 notify ⇒ 崩溃。
     *
     * **教训**：「列表元素是 QPhoto」**不等于**「可以安全删除」——
     * 还要看**谁持有这个列表**。详见
     * `docs/交互图/取证报告与崩溃复盘.md`。
     */
    fun washSelectedLists(vm: Any) {
        // 已停用：空实现（保留签名，避免调用方改动）
    }

    /**
     * ★ 公共列表清洗入口（供已收集列表的批量清洗）。
     *
     * 保留为独立函数的原因：`CfhWash.filterVmLists` 的调用方
     * （数据层）在**响应返回值**路径上需要快速清洗，不走对象图遍历。
     *
     * @return 是否有实际删除（供调用方判断是否重新武装节流）
     */
    fun cleanPublishedLists(): Boolean {
        val before = CfhState.qpDirectLists.size
        cleanCollectedLists()
        return before > 0
    }

    /**
     * ★ 真源残留探测（诊断，只读）。
     *
     * ## 作用（从标签 `SRCDIRTY` / `SRCELEM` + 历史证据还原）
     *
     * 穿越 VM 对象图，对**每个「元素直接是 QPhoto」的列表**
     * 统计「还剩几条脏项」，输出：
     * ```
     * [SRCDIRTY] 真源残留 h.j:1/9(结构疑1) l.a:1/6 l.c:4/15
     * [SRCELEM]  真源 h.j size=9 qpDirect=false qpWrapped=false 元素=[][][g][][f1]...
     * ```
     *
     * **只读** —— 用于回答「为什么删不掉」（是删了又回来，还是根本没删到）。
     *
     * ## 为什么保留
     *
     * 「真源残留」是历史上最常排查的问题（反复出现「同一位置的脏内容
     * 换成另一条脏内容」）。保留只读探测比每次重新写探针划算。
     */
    private fun probeSourceDirty(obj: Any) {
        if (CfhState.srcDirtyCount >= 40) return
        try {
            val qc = CfhState.qpClassRef ?: return
            CfhState.srcDirtyCount++
            val rows = ArrayList<String>()
            var vis = 0
            val seen = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
            )
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(obj, "vm", 0))
            seen.add(obj)
            while (q.isNotEmpty() && vis < 2000) {
                val node = q.removeFirst()
                val o = node[0]
                val path = node[1] as String
                val d = node[2] as Int
                vis++
                if (d > 3) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cur: Class<*> = c
                    for (f in Reflect.nonStaticFields(cur)) {
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            val p = "$path.${f.name}"
                            if (v is List<*>) {
                                if (v.isNotEmpty()) {
                                    val direct = v.count { e -> e != null && qc.isInstance(e) }
                                    val wrapped = v.count { e ->
                                        e != null && !qc.isInstance(e) &&
                                            (try { CfhProbe.findQpInObject(e) } catch (_: Throwable) { null } != null)
                                    }
                                    val dirtyDirect = if (direct > 0) {
                                        v.count { e ->
                                            e != null && qc.isInstance(e) &&
                                                (try { CfhDecide.shouldFilterFeed(e) } catch (_: Throwable) { false })
                                        }
                                    } else 0
                                    if (dirtyDirect > 0) {
                                        rows.add("$p:$dirtyDirect/${v.size}")
                                    }
                                    // 结构可疑：能提出 QP 但不是直取
                                    if (direct == 0 && wrapped > 0 &&
                                        CfhState.srcElemCount < 40
                                    ) {
                                        CfhState.srcElemCount++
                                        val elems = v.take(12).joinToString("") { e ->
                                            when {
                                                e == null -> "[]"
                                                qc.isInstance(e) -> "[+qp]"
                                                else -> "[${e.javaClass.simpleName.take(4)}]"
                                            }
                                        }
                                        Logger.evidence(
                                            "SRCELEM",
                                            "真源 $p size=${v.size} qpDirect=false " +
                                                "qpWrapped=true 元素=$elems"
                                        )
                                    }
                                }
                                if (p.count { it == '.' } < 4 && seen.add(v)) {
                                    q.add(arrayOf(v, p, d + 1))
                                }
                            } else {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && v !is android.view.View &&
                                    seen.add(v)
                                ) {
                                    q.add(arrayOf(v, p, d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cur.superclass; lvl++
                }
            }
            if (rows.isNotEmpty()) {
                Logger.evidence(
                    "SRCDIRTY",
                    "真源残留 ${rows.take(10).joinToString(" ")}"
                )
            } else {
                Logger.evidence("SRCDIRTY", "真源干净（无脏项残留）")
            }
        } catch (_: Throwable) {}
    }

    /**
     * ★ 清洗详情日志（反编译实证：`washDiagDetail(list, removed)`）。
     *
     * 在删除发生后打印**前 3 项**的文案/photoId，便于核验「删对了没有」。
     *
     * 实证输出：
     * ```
     * [WASHDEL] 删3 剩6 前3项=[文案1/id1][文案2/id2][文案3/id3]
     * ```
     */
    private fun washDiagDetail(list: List<Any?>, removed: Int) {
        if (removed <= 0 || CfhState.washDiagCount >= 15) return
        try {
            CfhState.washDiagCount++
            val sb = StringBuilder()
            var i = 0
            for (el0 in list) {
                if (i >= 3) break
                if (el0 == null) continue
                val qp = try { CfhProbe.findQpInObject(el0) } catch (_: Throwable) { null }
                val cap = try {
                    CfhUtil.readCaption(el0) ?: qp?.let { CfhUtil.readCaption(it) } ?: "-"
                } catch (_: Throwable) { "-" }
                val pid = try { qp?.let { CfhProbe.readPhotoId(it) } ?: "?" } catch (_: Throwable) { "?" }
                sb.append('[').append(cap.take(14)).append('/').append(pid).append(']')
                i++
            }
            Logger.evidence("WASHDEL", "删${removed} 剩${list.size} 前3项=$sb")
        } catch (_: Throwable) {}
    }

    /**
     * ★ VM 列表哈希（变化检测）。
     *
     * 取 `vm.l.a` 的 identity hash —— 用于判断「宿主是否重建了列表」。
     *
     * 反编译实证：
     * ```java
     * vm = CfhState.vmRef
     * l = Reflect.readAny(vm, "l")
     * a = Reflect.readAny(l, "a")
     * return a is List ? System.identityHashCode(a) : 0
     * ```
     *
     * 用途：配合 `WASH-DROP` / 收敛判断 —— 列表对象换了说明宿主重填，
     * 需要重新清洗。
     */
    fun sourceListHash(): Int {
        return try {
            val vm = CfhState.vmRef ?: return 0
            val l = Reflect.readAny(vm, "l") ?: return 0
            val a = Reflect.readAny(l, "a") ?: return 0
            if (a is List<*>) System.identityHashCode(a) else 0
        } catch (_: Throwable) { 0 }
    }

    /**
     * ★ 从 holder 收集「内容项」（反编译实证：`collectContentItems(holder, limit)`）。
     *
     * ## 作用
     *
     * 广度遍历 holder 对象图（深度 3），收集**所有 QPhoto 实例** ——
     * 用于「可见页重检」（`periodicScanVisible` 的 `:1317` 调用）。
     *
     * 与 [collectComponentItems] 的区别：
     *   · 本函数：从 **VM/holder** 出发，收集 QPhoto **本身**
     *   · `collectComponentItems`：从 **Fragment/Activity** 出发，收集**组件对象**
     *
     * ## 反编译实证的过滤条件
     * ```
     * · 跳过 java.* / android.* / kotlin.* 前缀的类
     * · 跳过 View
     * · 深度上限 3，访问上限 800
     * · 收集上限 limit
     * ```
     */
    fun collectContentItems(holder: Any, limit: Int): List<Any> {
        val out = ArrayList<Any>(128)
        try {
            val qc = CfhState.qpClassRef ?: return out
            val seen = HashSet<Any>()
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(holder, 0))
            seen.add(holder)
            var vis = 0
            while (q.isNotEmpty() && vis < 800 && out.size < limit) {
                val node = q.removeFirst()
                val o = node[0] ?: continue
                val d = node[1] as Int
                vis++
                if (qc.isInstance(o)) {
                    out.add(o)
                    continue
                }
                if (d > 3) continue
                if (o is List<*>) {
                    for (e in o) {
                        if (e == null) continue
                        if (qc.isInstance(e)) {
                            if (out.size < limit) out.add(e)
                        } else if (seen.add(e)) {
                            q.add(arrayOf(e, d + 1))
                        }
                    }
                    continue
                }
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cur: Class<*> = c
                    for (f in Reflect.nonStaticFields(cur)) {
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (qc.isInstance(v)) {
                                if (out.size < limit) out.add(v)
                                continue
                            }
                            val vn = v.javaClass.name
                            if (vn.startsWith("java.") || vn.startsWith("android.") ||
                                vn.startsWith("kotlin.") || v is android.view.View
                            ) continue
                            if (seen.add(v)) q.add(arrayOf(v, d + 1))
                        } catch (_: Throwable) {}
                    }
                    c = cur.superclass; lvl++
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    /**
     * ★ 从 Fragment/Activity 收集「组件项」（反编译实证：`collectComponentItems(frag, limit)`）。
     *
     * ## 作用
     *
     * 广度遍历 Fragment 对象图（深度 3），收集**「像组件的对象」** ——
     * 用于「可见页重检」（`periodicScanVisible` 的 `:1000` 调用）。
     *
     * ## 「像组件」的判据（反编译实证）
     * ```
     * · 类名含 "Component"        ⇒ 收集
     * · 类名含 "Manager" / "ViewModel" ⇒ 收集
     * · 其余 ⇒ 只作为中间节点继续遍历
     * ```
     * **注意**：命中「Component」的**直接收集**，命中 Manager/ViewModel 的
     * 也会收集（它们持有列表）。
     *
     * ## 缓存（反编译实证）
     *
     * `compCacheRoot` + `compCacheAt` + `compCacheItems`：
     * **同一个 root 对象 3 秒内复用结果** —— 避免周期任务反复遍历。
     */
    fun collectComponentItems(frag: Any?, limit: Int): List<Any> {
        if (frag == null) return emptyList()
        val now = System.currentTimeMillis()
        // ① 3 秒缓存（root 未变时复用）
        if (compCacheRoot === frag && now - compCacheAt < 3000L) {
            return if (compCacheItems.size > limit) compCacheItems.subList(0, limit)
            else compCacheItems
        }
        val out = ArrayList<Any>(128)
        try {
            val seen = HashSet<Any>()
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(frag, 0))
            seen.add(frag)
            var vis = 0
            while (q.isNotEmpty() && vis < 800 && out.size < limit) {
                val node = q.removeFirst()
                val o = node[0] ?: continue
                val d = node[1] as Int
                vis++
                if (d > 3) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cur: Class<*> = c
                    for (f in Reflect.nonStaticFields(cur)) {
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            val vn = v.javaClass.name
                            val simple = v.javaClass.simpleName
                            // 「像组件」⇒ 收集
                            if (simple.contains("Component") ||
                                simple.contains("Manager") ||
                                simple.contains("ViewModel")
                            ) {
                                if (out.size < limit) out.add(v)
                            }
                            // 继续遍历（排除框架/View）
                            if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                !vn.startsWith("kotlin.") && v !is android.view.View &&
                                seen.add(v)
                            ) {
                                q.add(arrayOf(v, d + 1))
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cur.superclass; lvl++
                }
            }
        } catch (_: Throwable) {}
        compCacheRoot = frag
        compCacheAt = now
        compCacheItems = out
        return out
    }

    /**
     * ★ 从元素找 QPhoto（走 `m.b` 路径）。
     *
     * 反编译实证逻辑：
     * ```
     * cur = el
     * 最多 5 跳：
     *   ① cur 本身是 QPhoto ⇒ 返回
     *   ② cur 类名以 "Fragment" 结尾 ⇒ 读 cur.m.b，是 QPhoto 就返回，否则 null
     *   ③ 否则 cur = cur.b（下一跳）；若 b 是集合/View/空 ⇒ 放弃
     * ```
     *
     * 用途：某些包装类不直接持有 QPhoto，而是通过 `b` 字段链间接持有。
     */
    fun findSlotQpViaMb(el: Any): Any? {
        val qc = CfhState.qpClassRef ?: return null
        var cur: Any = el
        repeat(5) {
            try {
                if (qc.isInstance(cur)) return cur
                val name = cur.javaClass.name
                if (name.endsWith("Fragment")) {
                    val m = Reflect.readAny(cur, "m") ?: return null
                    val qp = Reflect.readAny(m, "b") ?: return null
                    return if (qc.isInstance(qp)) qp else null
                }
                val nxt = Reflect.readAny(cur, "b") ?: return null
                if (nxt is Collection<*> || nxt is android.view.View) return null
                cur = nxt
            } catch (_: Throwable) { return null }
        }
        return null
    }
}
