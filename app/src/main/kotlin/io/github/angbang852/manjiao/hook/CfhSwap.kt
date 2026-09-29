package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第三步：替换料与写回（2026-09 S3）。
// 干净项队列/持久缓存、URL 脏→净替换、QPhoto 字段写回、adapter 自持列表修复。
object CfhSwap {
    fun offerClean(qp: Any) {
        val ent = Reflect.readAny(qp, "mEntity")
        if (ent == null) { if (CfhState.offerDiag < 20) { CfhState.offerDiag++; Logger.d("offer skip: noEntity") }; return }
        if (!ent.javaClass.name.contains("feed.VideoFeed")) {
            if (CfhState.offerDiag < 20) { CfhState.offerDiag++; Logger.d("offer skip: nonVF ${ent.javaClass.simpleName}") }
            return
        }
        if (Reflect.readAny(ent, "mPhotoMeta") == null) { if (CfhState.offerDiag < 20) { CfhState.offerDiag++; Logger.d("offer skip: noMeta") }; return }
        // ★★ 空壳项不得入队（实证 2026-09 probe4 首屏）：冷启首屏第 2 条是数据尚未
        // 回填的占位 QPhoto（cap 为空、声明/直播字段全空），它判不出脏 → 被当 clean
        // 塞进替换队列（offer add: "" queue=2）。首屏 7 条里 5 条脏只剩 2 条干净，
        // 队列被空壳占位后 pickFromQueue 只能返回空壳 → 替身上屏仍是什么都没有，
        // 用户看到的第一/第二条正是它。空文案 = 未就绪，不作为可用替身。
        // （真·无文案视频极罕见，且它会被下一批正常项顶掉，代价可接受）
        if (CfhUtil.readCaption(qp).isNullOrBlank()) {
            if (CfhState.offerDiag < 20) { CfhState.offerDiag++; Logger.d("offer skip: blankCap") }
            return
        }
        // ★ 加锁：offerClean（任意 hook 线程）与 pickFromQueue（cleanExecutor）
        // 无锁并发操作普通 ArrayDeque 会丢项/竞态（同文件 cleanCachePersist 有锁）
        synchronized(CfhState.cleanQueue) {
            if (CfhState.cleanQueue.any { it === qp }) return
            if (CfhState.cleanQueue.size >= 12) CfhState.cleanQueue.removeFirst()
            CfhState.cleanQueue.add(qp)
        }
        // 持久缓存：跨窗口不排空，兜底替换用。?
        synchronized(CfhState.cleanCachePersist) {
            if (CfhState.cleanCachePersist.none { it === qp }) {
                if (CfhState.cleanCachePersist.size >= 60) CfhState.cleanCachePersist.removeFirst()
                CfhState.cleanCachePersist.addLast(qp)
            }
        }
        // ★ recordCleanUrl(ent) 调用已随 URL 替换子系统一并移除（2026-09-21）：
        // 该子系统不是数据源拦截路线（在播放器对象上事后改 URL），且从未生效
        if (CfhState.offerDiag < 30) { CfhState.offerDiag++; Logger.d("offer add: ${CfhUtil.readCaption(qp)?.take(14)} queue=${CfhState.cleanQueue.size}") }
    }
    fun pickFromQueue(): Any? {
        val qpClass = CfhState.qpClassRef ?: return null
        var idx = 0
        // ★ lastClean 的「换条」判定必须在同一把锁下完成（审阅 2026-09）：原先队列段
        // 在 cleanQueue 锁、持久缓存段在 cleanCachePersist 锁，两线程可同时读到旧
        // lastClean 并返回同一条干净视频 → 两个脏位替换成同一视频。
        // offerClean 对两锁是顺序持有（无嵌套），此处 cleanQueue→cleanCachePersist
        // 嵌套无锁序倒置风险
        synchronized(CfhState.cleanQueue) {
            while (CfhState.cleanQueue.isNotEmpty() && idx < 24) {
                val head = CfhState.cleanQueue.removeFirst()
                idx++
                // 空壳（文案未回填）不作替身：出队即丢弃，不重新入队
                if (CfhUtil.readCaption(head).isNullOrBlank()) continue
                if (qpClass.isAssignableFrom(head.javaClass) && !CfhDecide.shouldFilterFeed(head)) {
                    CfhState.cleanQueue.add(head)
                    if (head !== CfhState.lastClean || CfhState.cleanQueue.size == 1) {
                        CfhState.lastClean = head
                        return head
                    }
                }
            }
            // 持久缓存兜底
            synchronized(CfhState.cleanCachePersist) {
                for (c in CfhState.cleanCachePersist) {
                    if (CfhUtil.readCaption(c).isNullOrBlank()) continue
                    if (qpClass.isAssignableFrom(c.javaClass) && !CfhDecide.shouldFilterFeed(c) && c !== CfhState.lastClean) {
                        CfhState.lastClean = c
                        return c
                    }
                }
            }
        }
        return null
    }
    fun findCleanQp(): Any? {
        if (CfhState.inFindClean) return null
        val vm = CfhState.vmRef
        if (vm == null) return null
        pickFromQueue()?.let { return it }
// 轻量补充：仅查 VM 窗口字段 i（不碰 T0/U0，避免反射副作用/异常）。
        CfhState.inFindClean = true
        try {
            val i = try { Reflect.readAny(vm, "i") } catch (_: Throwable) { null }
            if (i is List<*>) {
                for (el in i) {
                    val q = el?.let { CfhProbe.findQpInObject(it) }
                    if (q != null && !CfhDecide.shouldFilterFeed(q)) offerClean(q)
                }
            }
        } finally { CfhState.inFindClean = false }
        return pickFromQueue()
    }
    fun writeQpInto(obj: Any, cleanQp: Any): Int {
        // 统一保护：目标对象当前持 LiveStreamFeed 时拒绝写入 VideoFeed，
        // 否则快手 onMeasure 会 ClassCastException(VideoFeed→LiveStreamFeed)。
        val targetType = try { Reflect.readAny(obj, "mEntity")?.javaClass?.name } catch (_: Throwable) { null }
        if (targetType?.contains("LiveStreamFeed") == true) return 0
        val cleanType = try { Reflect.readAny(cleanQp, "mEntity")?.javaClass?.name } catch (_: Throwable) { null }
        if (cleanType?.contains("LiveStreamFeed") == true) return 0
        val qpClass = CfhState.qpClassRef ?: return 0
        var swapped = 0
        val visited = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
        val queue = ArrayDeque<Any>()
        queue.add(obj); visited.add(obj)
        var depth = 0
        while (queue.isNotEmpty() && depth < 4) {
            val sz = queue.size
            for (n in 0 until sz) {
                val o = queue.removeFirst()
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o)
                            if (qpClass.isAssignableFrom(f.type)) {
                                f.set(o, cleanQp); swapped++
                            } else if (v != null && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.contains("Fragment") && visited.add(v)) {
                                queue.add(v)
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
            }
            depth++
        }
        return swapped
    }
    fun dataSwallow(reason: String) {
        if (CfhState.dataDiag < 40) { CfhState.dataDiag++; Logger.d("DATA skip $reason") }
    }
    // ★ URL 替换子系统整体移除（2026-09-21）：recordCleanUrl / recordDirtyUrl /
    // readVideoUrl / hookVideoModelClass / hookPlayerClasses 五个函数 + 其状态
    // （dirtyUrls / cleanUrlPool / hookedVmUrlClasses / urlSubCount / playerHookTried）已删。
    // 删除理由（架构 + 事实双重）：
    //   1) 架构：本模块的路线是**数据源拦截**（在 VM/列表/真源层删掉脏项，让它"刷不到"）。
    //      URL 替换是在**播放器对象上事后改值**——另一条路线，且拦不住：播放器对象
    //      每次 setDataSource 都重新读值、对象反复重建，一次性改写不落地。
    //   2) 事实：整条链**从未生效**——recordDirtyUrl 无调用点 ⇒ hookPlayerClasses 从未
    //      安装 ⇒ `chain.args[0] = clean` 从未执行；dirtyUrls 从未被写入。即便被调用，
    //      该写法也是错的（libxposed Chain.getArgs() 返回只读 List，List.set() 必抛）。
    // 若日后确需在播放层换 URL，必须用 chain.proceed(newArgs)，并先证明它能"粘住"。
    fun fixAdapterSelfAlways(adp: Any?) {
        if (adp == null) return
        val now = System.currentTimeMillis()
        // ★ 2000ms（真机回归「卡死」降温）：本方法对 adapter 全字段做 4 层扫描 +
        // 每元素 findQpInObject，全在 D() 调用线程（主线程分页路径）。300ms 持续扫
        // 在滑动期是显著主线程负担——持续清理语义保留，频率让位流畅度
        if (now - CfhState.lastAdpSelfFix < 2000) return
        CfhState.lastAdpSelfFix = now
        // ★ 持续清理语义（审阅 2026-09）：原 adpSelfFixVisited 门控使本方法对每个
        // adapter 实例只真正执行一次，与「每次 D() 都修」的注释意图相反——rerank
        // 后续塞进自持列表的脏项永远不会再被清。300ms 节流已足够防重入
        try {
            var c2: Class<*>? = adp.javaClass
            var lvl2 = 0
            while (c2 != null && c2 != Any::class.java && lvl2 < 4) {
                // ★ 性能修复（审阅 2026-09 · M6）：改字段表为缓存版本
                // （Reflect.nonStaticFields 按类缓存 Array<Field> 并预置 accessible），
                // 不再每次 declaredFields 复制数组 + 逐字段 isAccessible
                for (f in Reflect.nonStaticFields(c2!!)) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(adp)
                        if (v is MutableList<*> && v.size > 0) {
                            // ★★ 只删不换（2026-09-25 用户定稿）：adapter 自持列表里的
                            //   脏项直接移除（保留至少 1 项），不再用 writeQpInto 替换 ——
                            //   替换制造「净视频+脏文字」错配。干净项入池职责保留
                            //   （供 scrub/记录等只读用途）。
                            var fixed = 0
                            var dirtySeen = 0
                            // 单遍：一次 findQpInObject 决定「脏 → 删」或「净 → 入池」
                            @Suppress("UNCHECKED_CAST")
                            val ml = v as MutableList<Any?>
                            var i = ml.size - 1
                            while (i >= 0) {
                                if (ml.size <= 1) break   // 护栏：至少留 1 项
                                val el = ml[i]
                                if (el == null) { ml.removeAt(i); i--; continue }
                                val q = CfhProbe.findQpInObject(el)
                                if (q == null) { i--; continue }
                                if (CfhDecide.shouldFilterFeed(q)) {
                                    dirtySeen++
                                    // [已移除 2026-09-26] scrubShownDirty：功能早已删除，调用点清理
                                    ml.removeAt(i)
                                    fixed++
                                } else {
                                    try { offerClean(q) } catch (_: Throwable) {}
                                }
                                i--
                            }
                            if (fixed > 0) {
                                Logger.d("adpSelfFixDel ${f.name} removed=$fixed sz=${ml.size}")
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        } catch (_: Throwable) {}
    }
}
