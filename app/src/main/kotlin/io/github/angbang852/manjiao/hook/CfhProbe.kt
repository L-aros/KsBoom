package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第三步：对象图定位与去重（2026-09 S3）。
// 全图找 QP/脏实体/Live 字段/Fragment、photoId 身份读取、批次去重、方法缓存。
// 纯查询叶节点——只依赖 CfhState/CfhUtil，不反向依赖任何清洗对象。
object CfhProbe {
    /**
     * 从任意对象里**深挖** QPhoto（2026-09-26 重写）。
     *
     * ## 原实现的致命缺陷（自检发现，解释了连续 7 轮挖不到）
     *
     * ```kotlin
     * if (depth >= 2) return null                      // 深度上限 2
     * ...
     * if (depth < 1 && ...) {                          // ★ 只在 depth=0 递归
     *     val r = findQpInObject(v, depth + 1)
     * }
     * ```
     * ⇒ **实际只能挖到 2 层**：
     * ```
     * depth=0: 字段直接是 QPhoto？ → 不是则递归
     * depth=1: 字段直接是 QPhoto？ → depth<1 为假，不再递归
     * depth=2: return null
     * ```
     *
     * **而快手的对象嵌套远超 2 层** —— 例如首页卡片
     * `lki.c.f : h` → `h.xxx : ...` → QPhoto（第 3 层就挖不到）。
     *
     * ## 新实现
     *
     * | 改动 | 说明 |
     * |---|---|
     * | **深度 2 → 6** | 覆盖快手的深层嵌套 |
     * | **字段名优先** | 名字像内容的（`photo`/`feed`/`mEntity`/`data`/`item`…）**先挖**，命中即返回 ⇒ 大多数情况不用挖满 |
     * | **字段名排除** | 明显不是内容的（`mContext`/`mHandler`/`mView`/`mListener`…）**跳过** ⇒ 省成本 + 防误挖到 UI 对象 |
     * | **祖先防环** | 同一对象只访问一次（快手的对象图有环） |
     * | **成本护栏** | 访问对象数上限（默认 300），超出即停 |
     *
     * ## 正确性
     *
     * · **只读**：不改任何对象
     * · 返回**第一个**找到的 QPhoto（与旧实现语义一致）
     * · 异常全吞
     *
     * @param obj   待挖对象
     * @param depth 当前深度（调用方一般不用传）
     * @param seen  已访问对象（防环，内部用）
     * @param budget 剩余可访问对象数（成本护栏，内部用）
     */
    /**
     * 从任意对象里提取 QPhoto（**性能优先版**，2026-09-26 回退）。
     *
     * ## ★ 为什么回退（重要教训）
     *
     * 当日曾把本函数从「深度 2」升级到「深度 6 + 字段名分类 + seen 集合 + budget」，
     * 目的是修「首页卡片挖不到 QPhoto」。
     *
     * **结果：模块整体严重卡顿** —— 因为：
     * ```
     * 本函数有 【66 处调用点】
     *   · CfhWash.kt     27 处（数据层轮询，高频）
     *   · CfhViewHook.kt  9 处
     *   · CfhDiag.kt      8 处
     *   · CfhSwap/Purge   8 处
     *   ...
     * ```
     * 深度 2→6 意味着**每次调用遍历的对象数增加数十倍**，
     * 加上 `lowercase()` 字符串判定 + `identityHashCode` 入 Set 的分配开销，
     * **整体开销放大 10~50 倍** ⇒ 用户报「很卡」。
     *
     * ## 教训
     *
     * **不能为了修一个场景（首页卡片），把 66 个调用点一起拖慢。**
     * 若日后需要深挖，应做**独立的新函数**（如 `findQpDeep`），
     * 只在需要的那一处调用，而不是改公共函数。
     *
     * ## 当前实现（与原版一致）
     *
     * · 深度上限 2
     * · 只在 depth=0 递归
     * · 无字符串判定、无 Set 分配
     */
    fun findQpInObject(obj: Any, depth: Int = 0): Any? {
        val qpClass = CfhState.qpClassRef ?: return null
        if (qpClass.isInstance(obj)) return obj
        if (depth >= 2) return null

        if (obj is Collection<*>) {
            for (item in obj) {
                if (item != null) {
                    val r = findQpInObject(item, depth + 1)
                    if (r != null) return r
                }
            }
            return null
        }
        // 反射成本核心优化：字段表按类缓存（Reflect.nonStaticFields），不再每次
        // declaredFields 复制数组；isAssignableFrom→isInstance 少一层类查找
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (f in Reflect.nonStaticFields(c!!)) {
                try {
                    val v = f.get(obj) ?: continue
                    if (qpClass.isInstance(v)) return v
                    if (depth < 1 && v.javaClass.name.contains(".") &&
                        !v.javaClass.name.startsWith("java.") &&
                        !v.javaClass.name.startsWith("android.")
                    ) {
                        val r = findQpInObject(v, depth + 1)
                        if (r != null) return r
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        return null
    }

    fun findDirtyEntityInHolder(holder: Any, depth: Int = 0): Any? {
        if (depth >= 5) return null
        val name = holder.javaClass.name
        // 直接命中：类名含 live（避开 LiveStreamViewModel 等无害/含 Live 的工具类）
        if ((name.contains("Live") || name.contains("Ad")) && !name.contains("ViewModel") && !name.contains("LiveData")) {
            // ★ 命中即 return（审阅 2026-09）：原 dirtyEntSeen.add 返回值作放行条件——
            // 同名类第一张脏卡 return 后，后续同类脏卡 add 失败落入字段扫描大概率
            // 返回 null → 第二张起全部漏拦上屏。add 结果只用于节流打日志
            if (holder is Collection<*>) { /* 集合本身不判脏，看元素 */ } else {
                if (CfhState.dirtyEntSeen.add(name)) Logger.d("dirtyEnt hit: $name")
                return holder
            }
        }
        if (holder is Collection<*>) {
            for (item in holder) {
                if (item != null) {
                    val r = findDirtyEntityInHolder(item, depth + 1)
                    if (r != null) return r
                }
            }
            return null
        }
        if (depth >= 4) return null
        var c: Class<*>? = holder.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (f in Reflect.nonStaticFields(c!!)) {
                try {
                    val v = f.get(holder) ?: continue
                    if (v === holder) continue
                    val vn = v.javaClass.name
                    // 跳过 JDK/安卓容器实现 与 巨型 View 树，防爆栈
                    if (vn.startsWith("java.") || vn.startsWith("android.") || vn.startsWith("kotlin.")) {
                        if (v is Collection<*>) { val r = findDirtyEntityInHolder(v, depth + 1); if (r != null) return r }
                        continue
                    }
                    if ((vn.contains("Live") || vn.contains("Ad")) && !vn.contains("ViewModel") && !vn.contains("LiveData")) {
                        if (CfhState.dirtyEntSeen.add(vn)) Logger.d("dirtyEnt hit: $vn")
                        return v
                    }
                    val r = findDirtyEntityInHolder(v, depth + 1)
                    if (r != null) return r
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        return null
    }
    /**
     * 直播判据排查探针（2026-09-23）：dump 直播相关字段的**实际值**。
     *
     * ## 为什么需要它
     *
     * 用户报「键尘团播」这条直播漏了。现有诊断 `LIVEFIELD`（见 CfhDecide）只打
     * **字段名与类型**（`mCurrentLivingState=Boolean`），看不出值到底是 true 还是
     * false —— 而判定恰恰取决于值。
     *
     * 且现有判据（live:meta / live:startTime / live:state …）在这条上没有命中，
     * 说明判据与现实有偏差。必须看到**真实值**才能定位：
     * 是 `mCurrentLivingState=false`（字段不可靠），还是该条根本不带这些字段
     * （直播走的是另一条数据结构）。
     *
     * ## 输出内容
     *
     * 对含直播迹象的条目，打印：
     * - 实体类名（`VideoFeed` / `LiveStreamFeed` / 其它）
     * - 所有 live 相关字段的**值**（不只类型）
     * - 这些字段的声明类（判断是 ent 层还是 pm/cm 层）
     *
     * @param qp  照片对象
     * @param ent 实体对象
     */
    fun dumpLiveValues(qp: Any?, ent: Any?) {
        if (qp == null || ent == null) return
        try {
            val sb = StringBuilder("LIVEVAL ent=${ent.javaClass.simpleName}")
            var any = false
            fun scan(obj: Any?, tag: String) {
                if (obj == null) return
                var c: Class<*>? = obj.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        val fn = f.name.lowercase()
                        if (!fn.contains("live") && !fn.contains("living") && !fn.contains("streaming")) continue
                        val v = try { f.isAccessible = true; f.get(obj) } catch (_: Throwable) { null }
                        // 只打有判别力的：布尔、数字、非空对象
                        val show = when (v) {
                            null -> "null"
                            is Boolean -> v.toString()
                            is Number -> v.toString()
                            else -> v.javaClass.simpleName
                        }
                        sb.append(" | $tag.${f.name}=$show")
                        any = true
                    }
                    c = c.superclass; lvl++
                }
            }
            val ent2 = try { Reflect.readAny(ent, "mEntity") ?: ent } catch (_: Throwable) { ent }
            scan(ent2, "ent")
            scan(try { Reflect.readAny(ent2, "mPhotoMeta") } catch (_: Throwable) { null }, "pm")
            scan(try { Reflect.readAny(ent2, "mCommonMeta") } catch (_: Throwable) { null }, "cm")
            scan(try { Reflect.readAny(ent2, "mLivePlaybackMeta") } catch (_: Throwable) { null }, "lm")
            // ★ 用 once（不受 quiet 门控）：偶发漏判必须保证留下证据，
            //   而 always 在默认 quiet=true 下会被静默掉（见 Logger.always:81）。
            //   每个 qp 身份作去重键，同一目录只打一次。
            if (any) {
                val key = "liveval:" + System.identityHashCode(qp)
                Logger.once(key) { sb.toString() }
            }
        } catch (_: Throwable) {}
    }

    // ==================== pager swap 聚合探针（2026-09-23）====================
    //
    // 目的：量清楚 `pager swap` 的真实规模，**不改任何行为**。
    //
    // 起因：实测日志里出现 `pager swap getChildAt(#6) sw=13 第202集｜...` 无限重复。
    // 但逐条打印会把日志刷屏（模块其它诊断全被淹没），无法判断：
    //   · 是一直在循环，还是只在特定条件下偶发？
    //   · 是否集中在某个位置（如 #6）？
    //   · 频率多少（每秒几次）？
    // 因此改为**只计数**，每 50 次才输出一行汇总（含热点位置 Top3、写入字段数分布）。

    private val swapTotal = java.util.concurrent.atomic.AtomicInteger(0)
    private val swapByPos = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val swapByWritten = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val swapFirstAt = java.util.concurrent.atomic.AtomicLong(0)
    private val swapLastAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 每多少次 swap 输出一次汇总（避免刷屏） */
    private const val SWAP_SUMMARY_EVERY = 50

    // ==================== 替换节流（2026-09-24）====================
    //
    // ★★ 要解决的问题：**「文案对不上视频」**
    //
    //   真机实测（用户报「她就这样消失在了上万吨垃圾中」）：
    //     `SWAPSTAT 热点位置=[#6×1776] 写入分布=[sw=13×1788]`
    //   —— **同一个位置 `#6` 被替换了 1776 次**，每次都是往该位置的 QPhoto
    //   字段写入「干净项」（`CfhSwap.writeQpInto`）。
    //
    //   而用户看到的现象是：**文案跟着位置变，但这个位置的文案和播放的视频对不上**，
    //   视频会变成「别的视频」。
    //
    //   机制：替换操作把**数据**换了，但**播放器实例**仍在播旧内容 ——
    //   换来换去 1776 次，文案视图（读新数据）与播放器（播旧内容）必然分叉。
    //
    //   修法：对**同一位置**做时间节流 —— 短时间内只允许替换一次。
    //   替换本来就「粘不住」（下次取数还是脏的，才需要 1776 次），
    //   所以限制频率不会让脏项更容易上屏，但能**大幅减少分叉机会**。
    //
    //   节流窗口取 800ms：远快于用户滑动（人手往返约 300ms+），
    //   又能把 1776 次压到个位数。

    /** 位置 → 上次替换时间 */
    private val swapLastByPos = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** 同一位置的替换最小间隔（ms） */
    private const val SWAP_POS_MIN_INTERVAL_MS = 800L

    /** 被节流拒绝的替换次数（观测用） */
    private val swapThrottled = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 替换节流闸门：判断「该位置现在是否允许替换」。
     *
     * 注意：**调用方只在允许时才真正写入**，本函数同时登记时间戳。
     * 合成一个函数（而不是先查后写两步）是为了避免 TOCTOU 竞态 ——
     * 本闸门会被多个线程并发调用。
     *
     * @return true = 允许本次替换
     */
    fun allowSwap(pos: Int): Boolean {
        if (pos < 0) return true   // 位置未知时不限制（无法归组）
        val now = System.currentTimeMillis()
        val last = swapLastByPos[pos]
        if (last != null && now - last < SWAP_POS_MIN_INTERVAL_MS) {
            swapThrottled.incrementAndGet()
            return false
        }
        swapLastByPos[pos] = now
        if (swapLastByPos.size > 64) swapLastByPos.clear()
        return true
    }

    /** 被节流拒绝的次数（供汇总行显示） */
    fun swapThrottledCount(): Int = try { swapThrottled.get() } catch (_: Throwable) { 0 }

    /**
     * 记录一次 pager swap（**只计数，无副作用**）。
     *
     * @param pos 被替换的位置（`getChildAt` 的下标 / 方法参数）
     * @param sw  [CfhSwap.writeQpInto] 的返回值（写了几个字段；0 = 拒绝写入）
     */
    fun noteSwap(pos: Int, sw: Int) {
        try {
            val n = swapTotal.incrementAndGet()
            swapByPos.merge(pos, 1, Int::plus)
            swapByWritten.merge(sw, 1, Int::plus)
            val now = System.currentTimeMillis()
            swapFirstAt.compareAndSet(0, now)
            swapLastAt.set(now)
            if (n % SWAP_SUMMARY_EVERY == 0) logSwapSummary(n)
        } catch (_: Throwable) {}
    }

    /** 输出 swap 规模汇总 */
    private fun logSwapSummary(n: Int) {
        try {
            val span = (swapLastAt.get() - swapFirstAt.get()).coerceAtLeast(1)
            val rate = n * 1000.0 / span
            val topPos = swapByPos.entries.sortedByDescending { it.value }.take(3)
                .joinToString(", ") { "#${it.key}×${it.value}" }
            val written = swapByWritten.entries.sortedByDescending { it.value }.take(4)
                .joinToString(", ") { "sw=${it.key}×${it.value}" }
            val zero = swapByWritten[0] ?: 0
            Logger.always(
                "SWAPSTAT n=$n ${String.format("%.1f", rate)}/s 热点位置=[$topPos] " +
                    "写入分布=[$written] 拒绝写入(sw=0)=$zero 节流拦截=${swapThrottled.get()}"
            )
        } catch (_: Throwable) {}
    }

    /** 供设置页/排障读取当前规模 */
    fun swapStats(): String = try {
        val n = swapTotal.get()
        if (n == 0) "无 swap 记录"
        else {
            val top = swapByPos.entries.sortedByDescending { it.value }.take(3)
                .joinToString(", ") { "#${it.key}×${it.value}" }
            "swap 共 $n 次，热点 $top"
        }
    } catch (_: Throwable) { "?" }

    fun findLiveWindowField(root: Any?): String? {
        if (root == null) return null
        try {
            val visited = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
            val queue = ArrayDeque<Pair<Any, Int>>()
            queue.addLast(root to 0)
            visited.add(root)
            while (queue.isNotEmpty()) {
                val (obj, depth) = queue.removeFirst()
                if (depth > 4) continue
                var c: Class<*>? = obj.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(obj)
                            if (v == null) continue
                            val fn = f.name.lowercase()
                            val cn = v.javaClass.name
                            val liveHit = cn.contains("Live") && (cn.contains("Info") || cn.contains("Status") || cn.contains("Play") || cn.contains("Feed") || cn.contains("Window") || cn.contains("Guide") || cn.contains("Preview"))
                            if (liveHit || fn.contains("livestatus") || fn.contains("isliving") || fn.contains("living") && (fn.contains("user") || fn.contains("author"))) {
                                return "[${c.simpleName}]${f.name}:${v.javaClass.simpleName}"
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
                if (depth < 3 && !obj.javaClass.name.startsWith("java.")) {
                    var c2: Class<*>? = obj.javaClass
                    var l2 = 0
                    while (c2 != null && c2 != Any::class.java && l2 < 2) {
                        for (f in c2!!.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            try {
                                f.isAccessible = true
                                val v = f.get(obj) ?: continue
                                if (!v.javaClass.isPrimitive && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("[") && visited.add(v)) {
                                    queue.addLast(v to depth + 1)
                                }
                            } catch (_: Throwable) {}
                        }
                        c2 = c2.superclass; l2++
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }
    fun findFragInHolder(holder: Any?): Any? {
        if (holder == null) return null
        try {
            var c: Class<*>? = holder.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    val ft = f.type.name
                    if (ft.contains("Fragment") && !ft.contains("FragmentManager") && !ft.contains("FragmentTransaction")) {
                        try { f.isAccessible = true; f.get(holder)?.let { return it } } catch (_: Throwable) {}
                    }
                }
                c = c.superclass; lvl++
            }
        } catch (_: Throwable) {}
        return null
    }
    fun cachedMethod(cls: Class<*>, name: String, vararg pt: Class<*>): java.lang.reflect.Method? {
        val key = cls.name + "#" + name + "#" + pt.size + "#" + pt.joinToString(",") { it.name }
        CfhState.methodCache[key]?.let { return it }
        val m = try { cls.getDeclaredMethod(name, *pt) } catch (_: Throwable) { null } ?: return null
        m.isAccessible = true
        CfhState.methodCache[key] = m
        return m
    }
    fun dedupeInsertBatch(batch: MutableList<Any?>?, tag: String): Int {
        if (batch == null || batch.isEmpty()) return 0
        if (CfhState.dedupeProbe < 3) {
            CfhState.dedupeProbe++
            val f = batch.firstOrNull()
            Logger.probe { "knhb dedupe probe $tag size=${batch.size} cls=${f?.javaClass?.name} id=${readPhotoId(f)} hist=${synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.size }}" }
        }
        var dup = 0
        val inBatch = HashSet<String>()
        val victims = ArrayList<Any?>()
        for (el in batch) {
            val id = readPhotoId(el) ?: continue
            if (id.isBlank()) continue
            val seenBefore = synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.contains(id) }
            // 历史已供给 或 本批次内重复 → 删
            if (seenBefore || !inBatch.add(id)) victims.add(el)
        }
        // 护栏：删后至少留 1（原本 >1 时），防 replaceAll 收到空列表
        if (victims.isNotEmpty() && batch.size - victims.size < 1) victims.removeAt(victims.size - 1)
        if (victims.isEmpty()) {
            CfhState.dedupeStarve.set(0)
            // 幸存项记入历史
            synchronized(CfhState.seenPhotoIds) {
                for (el in batch) {
                    val id = readPhotoId(el) ?: continue
                    if (id.isNotBlank()) {
                        CfhState.seenPhotoIds.remove(id); CfhState.seenPhotoIds.add(id)
                    }
                }
                if (CfhState.seenPhotoIds.size > 500) {
                    val it = CfhState.seenPhotoIds.iterator()
                    var drop = CfhState.seenPhotoIds.size - 500
                    while (drop-- > 0 && it.hasNext()) { it.next(); it.remove() }
                }
            }
            return 0
        }
        for (v in victims) { try { batch.remove(v); dup++ } catch (_: Throwable) {} }
        // ★ 饥饿自愈（2026-09-08 用户报「长时间无更多滑不出」）：长时间刷后 hist 攒满、服务端推荐池
        // 轮回返回看过的视频 → dedupe 全删（left<=1）→ 列表只剩 1 项轮转 → prefetch/loadMore 无限循环
        // 但供给不涨。对策：连续 4 批 dedupe 删后 left<=1（供给无效）→ 清空 hist 重开一轮
        // （接受一轮重复换供给恢复，不卡死）；left>=2 正常批次计数清零。
        if (batch.size <= 1) {
            val st = CfhState.dedupeStarve.incrementAndGet()
            if (st >= 4) {
                synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.clear() }
                Logger.always("dedupe starved 4 batches (hist reset) -> supply recover")
                CfhState.dedupeStarve.set(0)
            }
        } else CfhState.dedupeStarve.set(0)
        if (CfhState.dedupeDiag < 30) {
            CfhState.dedupeDiag++
            Logger.probe { "knhb dedupe $tag dup=$dup left=${batch.size} starve=${CfhState.dedupeStarve.get()} hist=${synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.size }}" }
        }
        // 幸存项记入历史
        synchronized(CfhState.seenPhotoIds) {
            for (el in batch) {
                val id = readPhotoId(el) ?: continue
                if (id.isNotBlank()) {
                    CfhState.seenPhotoIds.remove(id); CfhState.seenPhotoIds.add(id)
                }
            }
            if (CfhState.seenPhotoIds.size > 500) {
                val it = CfhState.seenPhotoIds.iterator()
                var drop = CfhState.seenPhotoIds.size - 500
                while (drop-- > 0 && it.hasNext()) { it.next(); it.remove() }
            }
        }
        return dup
    }
    fun readPhotoId(qp: Any?): String? {
        if (qp == null) return null
        if (CfhState.photoIdCache.containsKey(qp)) return CfhState.photoIdCache[qp]
        val cls = qp.javaClass
        val m: java.lang.reflect.Method? = when {
            CfhState.pidMNeg.contains(cls) -> null
            CfhState.pidMCache.containsKey(cls) -> CfhState.pidMCache[cls]
            else -> try {
                cls.getMethod("getPhotoId").apply { isAccessible = true }.also { CfhState.pidMCache[cls] = it }
            } catch (_: Throwable) { CfhState.pidMNeg.add(cls); null }
        }
        val id = try { m?.invoke(qp) as? String } catch (_: Throwable) { null }
        // ★ 上限 4000→800：IdentityHashMap 强引用 QPhoto（重对象），800 已覆盖去重
        // 窗口且内存尖峰小 5 倍（审阅 2026-09）
        if (CfhState.photoIdCache.size > 800) CfhState.photoIdCache.clear()
        CfhState.photoIdCache[qp] = id
        return id
    }
}
