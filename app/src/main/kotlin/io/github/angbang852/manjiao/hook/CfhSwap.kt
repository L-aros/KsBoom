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
        recordCleanUrl(ent)
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
    fun recordCleanUrl(ent: Any) {
        val url = readVideoUrl(ent) ?: return
        synchronized(CfhState.cleanUrlPool) {
            if (CfhState.cleanUrlPool.none { it == url }) {
                if (CfhState.cleanUrlPool.size >= 30) CfhState.cleanUrlPool.removeFirst()
                CfhState.cleanUrlPool.addLast(url)
            }
        }
    }
    fun recordDirtyUrl(ent: Any) {
        val url = readVideoUrl(ent) ?: return
        if (url.isBlank()) return
        CfhState.dirtyUrls.add(url)
        if (CfhState.dirtyUrls.size > 300) CfhState.dirtyUrls.clear()
        try {
            val vm = Reflect.readAny(ent, "mVideoModel") ?: return
            hookVideoModelClass(vm.javaClass)
        } catch (_: Throwable) {}
        hookPlayerClasses()
    }
    fun readVideoUrl(ent: Any): String? {
        return try {
            val vm = Reflect.readAny(ent, "mVideoModel") ?: return null
            Reflect.readString(vm, "mVideoUrl")
        } catch (_: Throwable) { null }
    }
    fun hookVideoModelClass(c: Class<*>) {
        val xp = CfhState.xpRef ?: return
        synchronized(CfhState.hookedVmUrlClasses) { if (!CfhState.hookedVmUrlClasses.add(c.name)) return }
        for (m in c.methods) {
            if (m.returnType != String::class.java || m.parameterTypes.isNotEmpty()) continue
            val nm = m.name.lowercase()
            if (!(nm.contains("url") || nm.contains("play") || nm.contains("video") || nm.contains("address"))) continue
            try {
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vmurl.${c.name}.${m.name}").intercept { chain ->
                    val r = chain.proceed()
                    try {
                        if (r is String && r.isNotBlank()) {
                            val dirty = CfhState.dirtyUrls.any { r.startsWith(it) || it.startsWith(r) }
                            if (dirty) {
                                val clean = synchronized(CfhState.cleanUrlPool) { CfhState.cleanUrlPool.firstOrNull { it != r } }
                                if (clean != null) {
                                    if (CfhState.urlSubCount < 20) { CfhState.urlSubCount++; Logger.d("vm url ${m.name} -> clean: ${r.take(36)}") }
                                    return@intercept clean
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                    r
                }
            } catch (_: Throwable) {}
        }
    }
    fun hookPlayerClasses() {
        if (CfhState.playerHookTried) return
        CfhState.playerHookTried = true
        val xp = CfhState.xpRef ?: return
        val appCl = CfhState.qpClassRef?.classLoader ?: CfhState.vmRef?.javaClass?.classLoader ?: return
        for (cn in listOf(
            "com.kwai.video.player.KwaiMediaPlayerWrapper",
            "com.kwai.video.player.KwaiMediaPlayerImplV3",
            "com.kwai.video.player.KwaiMediaPlayerImpl",
            "com.yxcorp.gifshow.media.player.PhotoDetailPlayer"
        )) {
            val c = try { Class.forName(cn, false, appCl) } catch (_: Throwable) { null } ?: continue
            Logger.d("player hook class: ${c.name}")
            for (m in c.declaredMethods) {
                if (m.returnType != Void.TYPE || m.parameterTypes.isEmpty() || m.parameterTypes[0] != String::class.java) continue
                val nm = m.name.lowercase()
                if (!(nm.contains("datasource") || nm.contains("videopath") || nm.contains("videouri") || nm.contains("setdata") || nm.contains("loadurl") || nm.contains("playurl"))) continue
                try {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("player.${c.name}.${m.name}").intercept { chain ->
                        try {
                            val a0 = chain.args.getOrNull(0)
                            if (a0 is String && a0.isNotBlank()) {
                                val dirty = CfhState.dirtyUrls.any { a0.startsWith(it) || it.startsWith(a0) }
                                if (dirty) {
                                    val clean = synchronized(CfhState.cleanUrlPool) { CfhState.cleanUrlPool.firstOrNull { it != a0 } }
                                    if (clean != null) {
                                        if (CfhState.urlSubCount < 20) { CfhState.urlSubCount++; Logger.d("player ${m.name} -> clean: ${a0.take(36)}") }
                                        chain.args[0] = clean
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                        null
                    }
                } catch (_: Throwable) {}
            }
        }
    }
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
