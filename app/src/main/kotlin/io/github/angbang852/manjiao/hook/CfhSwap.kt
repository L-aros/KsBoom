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
        // ★ 加锁：offerClean（任意 hook 线程）与 pickFromQueue（cleanExecutor）
        // 无锁并发操作普通 ArrayDeque 会丢项/竞态（同文件 cleanCachePersist 有锁）
        synchronized(CfhState.cleanQueue) {
            if (CfhState.cleanQueue.any { it === qp }) return
            if (CfhState.cleanQueue.size >= 12) CfhState.cleanQueue.removeFirst()
            CfhState.cleanQueue.add(qp)
        }
        // 持久缓存：跨窗口不排空，兜底替换�?
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
        // 轻量补充：仅�?VM 窗口字段 i（不�?T0/U0，避免反射副作用/异常�?
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
                for (f in c2!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(adp)
                        if (v is MutableList<*> && v.size > 0) {

                            val hits = v.filter { it != null && CfhProbe.findQpInObject(it)?.let { q -> CfhDecide.shouldFilterFeed(q) } == true }
                            if (hits.isNotEmpty() && v.size - hits.size >= 1) {
                                val cap0 = hits.firstOrNull()?.let { CfhProbe.findQpInObject(it)?.let { q -> CfhUtil.readCaption(q) } }
                                Logger.d("adpSelfFix ${f.name} fixed=${hits.size} cap0=${cap0?.take(14)}")
                                var fixed = 0
                                for (i in 0 until v.size) {
                                    val el = v[i] ?: continue
                                    val eq = CfhProbe.findQpInObject(el)
                                    if (eq != null && CfhDecide.shouldFilterFeed(eq)) {
                                        val cleanQp = findCleanQp()
                                        if (cleanQp != null) {
                                            val sw = try { writeQpInto(el, cleanQp) } catch (_: Throwable) { 0 }
                                            val sw2 = if (sw == 0 && CfhState.qpClassRef?.isAssignableFrom(el.javaClass) == true) {
                                                try { @Suppress("UNCHECKED_CAST") (v as MutableList<Any?>)[i] = cleanQp; 1 } catch (_: Throwable) { 0 }
                                            } else sw
                                            fixed += if (sw2 > 0) 1 else 0
                                        }
                                    }
                                }
                                if (fixed > 0) {
                                    Logger.d("adpSelfFixAl ${f.name} fixed=$fixed sz=${v.size}")
                                    try { Reflect.callMethod(adp, "notifyDataSetChanged") } catch (_: Throwable) {}
                                }
                            }
                            // 幸存干净项入池补充队列
                            for (el in v) {
                                el?.let { e -> CfhProbe.findQpInObject(e)?.let { q -> if (!CfhDecide.shouldFilterFeed(q)) { try { offerClean(q) } catch (_: Throwable) {} } } }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        } catch (_: Throwable) {}
    }
}
