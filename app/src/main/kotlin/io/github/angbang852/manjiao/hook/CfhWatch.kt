package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第七步：写入监控与真源反查（2026-09 S3）。
// LAWATCH（l.a 写方法监控 → 新批次落地前删除）/TRUEWATCH（真源写路径监控）/
// laElDirty 精确判脏/laFind 脏 QPhoto 身份反查真源字段。与 CfhWash（真源二层清洗）
// 分开：这里是「写入路径的拦截与反查」，那里是「落地数据的清洗」。
object CfhWatch {
    fun armLaWatch(list: Any) {
        CfhState.laRef = list
        // ★ ANR 红线（审阅 2026-09）：本插桩挂在 CopyOnWriteArrayList 类本身的
        // add/addAll 系方法上——进程内所有 COW 列表的每次写入都要过 Xposed 桥。
        // 它是为定位快手私有合并路径而生的取证插桩（见下方原始注释），生产静默
        // 模式不安装，与 hookKrnProbe 同款闸门
        if (Logger.quiet) return
        // ★ LAIDS：VM.i（vmFields 实证 CopyOnWriteArrayList）与 l.a 双列表对照 dump +
        // V0 快照脏元素身份反查（retDelQp 记录 filterResult 删过的 QPhoto，看它藏在哪个字段）
        if (!Logger.quiet) Logger.safe("laTrace") {
            val vm = CfhState.vmRef
            if (vm != null) {
                for (fname in arrayOf("i", "l")) {
                    try {
                        val holder = if (fname == "i") vm else Reflect.readAny(vm, "l") ?: continue
                        val lst = Reflect.readAny(holder, if (fname == "i") "i" else "a") as? List<*> ?: continue
                        val sb = StringBuilder("LAIDS $fname id=")
                            .append(System.identityHashCode(lst))
                            .append(" size=").append(lst.size).append(" :")
                        lst.take(14).forEachIndexed { idx, e ->
                            sb.append(" [$idx]")
                            if (e == null) { sb.append("null"); return@forEachIndexed }
                            sb.append(e.javaClass.simpleName.ifEmpty { e.javaClass.name.substringAfterLast('.') })
                            val ent = try { Reflect.readAny(e, "mEntity") } catch (_: Throwable) { null }
                            if (ent != null) sb.append("/").append(ent.javaClass.simpleName)
                            try {
                                synchronized(CfhState.retDelQp) {
                                    if (CfhState.retDelQp.any { it === e }) sb.append("*DIRTY")
                                }
                            } catch (_: Throwable) {}
                        }
                        Logger.d(sb.toString())
                    } catch (_: Throwable) {}
                }
            }
        }
        if (CfhState.laWatchArmed) return
        val xp = CfhState.xpRef ?: return
        synchronized(this) {
            if (CfhState.laWatchArmed) return
            CfhState.laWatchArmed = true
            Logger.safe("armLaWatch") {
                val cow = java.util.concurrent.CopyOnWriteArrayList::class.java
                val specs = listOf(
                    Triple("addAll", arrayOf<Class<*>>(java.util.Collection::class.java), true),
                    Triple("addAllAbsent", arrayOf<Class<*>>(java.util.Collection::class.java), true),
                    Triple("add", arrayOf<Class<*>>(Any::class.java), false),
                    Triple("addIfAbsent", arrayOf<Class<*>>(Any::class.java), false)
                )
                var ok = 0
                for ((mn, pt, isBatch) in specs) {
                    try {
                        val m = cow.getDeclaredMethod(mn, *pt)
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lawatch.$mn").intercept { chain ->
                            if (chain.thisObject !== CfhState.laRef) return@intercept chain.proceed()
                            val arg = chain.args.firstOrNull()
                            var dirtySingle: Any? = null
                            if (isBatch && arg is MutableCollection<*>) {
                                // 前置删除：脏项从参数集合剔除，addAll 执行时已无脏项
                                @Suppress("UNCHECKED_CAST")
                                val col = arg as MutableCollection<Any?>
                                val dirty = col.filter { laElDirty(it) }
                                if (dirty.isNotEmpty()) {
                                    val before = col.size
                                    // ★ 身份删除：equals 语义会误删「同值不同实例」的干净兄弟项
                                    try {
                                        val dirtyId = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
                                        dirtyId.addAll(dirty)
                                        col.removeIf { dirtyId.contains(it) }
                                    } catch (_: Throwable) {}
                                    Logger.always("LADEL $mn removed=${dirty.size}/$before left=${col.size}")
                                    laStack(mn)
                                }
                            } else if (!isBatch && arg != null && laElDirty(arg)) {
                                dirtySingle = arg
                            }
                            val r = chain.proceed()
                            if (dirtySingle != null) {
                                // 单元素：add 已执行，立即从 l.a 移除（同一调用栈内，早于渲染）
                                try { (chain.thisObject as MutableCollection<*>).remove(dirtySingle) } catch (_: Throwable) {}
                                Logger.always("LADEL $mn single removed ${(dirtySingle as Any).javaClass.name}")
                                laStack(mn)
                            }
                            r
                        }
                        ok++
                    } catch (_: Throwable) {}
                }
                Logger.always("LAWATCH armed: ok=$ok/4 (identity filter on l.a)")
            }
        }
    }
    fun armTrueWatch(list: Any) {
        if (!CfhState.trueListRefs.contains(list)) CfhState.trueListRefs.add(list)
        val cls0 = list.javaClass
        // ★ ANR 红线（审阅 2026-09）：绝不能把 add/addAll/set 挂到 JDK 集合类上——
        // ArrayList.add 是全进程最热方法之一，类级 hook 等于给全 app 每次列表写装桥。
        // 真源列表只可能是快手自有运行时类，JDK 类型直接放弃（宁可漏挂不挂错）
        val cn0 = cls0.name
        if (cn0.startsWith("java.") || cn0.startsWith("android.") || cn0.startsWith("kotlin.")) return
        val xp = CfhState.xpRef ?: return
        synchronized(this) {
            if (!CfhState.hookedTrueCls.add(CfhUtil.hookKey(cls0))) return
            Logger.safe("armTrueWatch") {
                val cls = list.javaClass
                var ok = 0
                val sigs = mutableListOf<Pair<String, Array<Class<*>>>>()
                sigs.add("add" to arrayOf<Class<*>>(Any::class.java))
                sigs.add("add" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Any::class.java))
                sigs.add("addAll" to arrayOf<Class<*>>(java.util.Collection::class.java))
                sigs.add("addAll" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, java.util.Collection::class.java))
                sigs.add("set" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Any::class.java))
                for ((mn, pt) in sigs) {
                    try {
                        val m = cls.getDeclaredMethod(mn, *pt)
                        val isBatch = mn == "addAll"
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("truewatch.${mn}.${pt.size}").intercept { chain ->
                            if (!isTrueList(chain.thisObject)) return@intercept chain.proceed()
                            val arg = chain.args.lastOrNull()
                            var dirtySingle: Any? = null
                            if (isBatch && arg is MutableCollection<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val col = arg as MutableCollection<Any?>
                                val dirty = col.filter { laElDirty(it) }
                                if (dirty.isNotEmpty()) {
                                    val before = col.size
                                    // ★ 身份删除（同 LAWATCH）
                                    try {
                                        val dirtyId = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
                                        dirtyId.addAll(dirty)
                                        col.removeIf { dirtyId.contains(it) }
                                    } catch (_: Throwable) {}
                                    Logger.always("TRUEDEL $mn removed=${dirty.size}/$before left=${col.size}")
                                    trueStack(mn)
                                }
                            } else if (!isBatch && arg != null && laElDirty(arg)) {
                                dirtySingle = arg
                            }
                            val r = chain.proceed()
                            if (dirtySingle != null) {
                                try { (chain.thisObject as MutableCollection<*>).remove(dirtySingle) } catch (_: Throwable) {}
                                Logger.always("TRUEDEL $mn single removed")
                                trueStack(mn)
                            }
                            r
                        }
                        ok++
                    } catch (_: Throwable) {}
                }
                Logger.always("TRUEWATCH armed on ${cls.name}: ok=$ok/5")
            }
        }
    }
    fun laElDirty(el: Any?): Boolean {
        if (el == null) return false
        if (CfhState.liveTop) return false
        return try {
            val rawCls = el.javaClass.name
            if (rawCls.contains("Presenter") || rawCls.contains("Callback") ||
                rawCls.contains("Fragment") || rawCls.contains("Interceptor") ||
                rawCls.contains("Executer") || rawCls.contains("Executor")) return false
            val qp = CfhState.qpClassRef ?: return false
            val ent = try { Reflect.readAny(el, "mEntity") } catch (_: Throwable) { null }
            val isFeedObj = qp.isAssignableFrom(el.javaClass) ||
                (ent != null && ent.javaClass.name.contains("Feed"))
            if (!isFeedObj) return false
            val q = if (qp.isAssignableFrom(el.javaClass)) el else CfhProbe.findQpInObject(el) ?: return false
            CfhDecide.shouldFilterFeed(q) ||
                (Prefs.bool(Prefs.K_FLT_LIVE, false) && rawCls.contains("LiveStreamFeed")) ||
                (Prefs.bool(Prefs.K_FLT_ADS, false) && rawCls.contains("AdFeed"))
        } catch (_: Throwable) { false }
    }
    fun laStack(tag: String) {
        val n = CfhState.laLogN.incrementAndGet()
        // 抓栈成本高且纯诊断：quiet（默认开）时直接跳过
        if (Logger.quiet || n > 20) return
        try {
            val st = Throwable().stackTrace
            val sb = StringBuilder("LAWATCH #$n $tag stack:")
            for (i in 0 until minOf(st.size, 25)) {
                val s = st[i]
                sb.append("\n  at ").append(s.className).append(".").append(s.methodName)
                    .append("(").append(s.fileName).append(":").append(s.lineNumber).append(")")
            }
            Logger.d(sb.toString())
        } catch (_: Throwable) {}
    }
    fun trueStack(tag: String) {
        val n = CfhState.trueListLogN.incrementAndGet()
        if (Logger.quiet || n > 20) return
        try {
            val st = Thread.currentThread().stackTrace
            val sb = StringBuilder("TRUEWATCH #$n $tag stack:")
            for (i in 0 until minOf(st.size, 25)) {
                val s = st[i]
                sb.append("\n  at ").append(s.className).append(".").append(s.methodName)
                    .append("(").append(s.fileName).append(":").append(s.lineNumber).append(")")
            }
            Logger.d(sb.toString())
        } catch (_: Throwable) {}
    }
    fun isTrueList(t: Any?): Boolean {
        if (t == null) return false
        for (r in CfhState.trueListRefs) if (r === t) return true
        return false
    }
    fun laFind(force: Boolean = false) {
        if (CfhState.liveTop) return
        val now = System.currentTimeMillis()
        // ★★ 启动窗内放宽节流（实证 2026-09 probe16 用户报「南方小果/广西黑皮水果甘蔗
        // 第二条一直停在原位不动」）：rerank 的 selected/scroll 回调是直播注入的必经点，
        // 原实现每次只调 laFind() 且被 2500ms 节流吞掉 —— 而 LAWATCH（真源监控）在
        // 00.539 才武装，直播内容 00.448 已上屏（早 91ms），中间这段真空期里
        // 数据源删了 30+ 次、屏幕纹丝不动。
        // 冷启前 20s 内把节流收紧到 250ms，让 rerank 回调触发的清洗真正落地。
        val bootWin = CfhState.processStartAt > 0L && now - CfhState.processStartAt < 20_000L
        val throttle = if (bootWin) 250L else 2500L
        if (!force && now - CfhState.laFindAt < throttle) return
        CfhState.laFindAt = now
        val targets = synchronized(CfhState.retDelQp) { CfhState.retDelQp.toList().filterNotNull() }
        // ★ force（rerank.d.j 预判前方有直播触发）时 targets 空也扫：直播卡可能未经
        // filterResult 快照（retDelQp 空），靠 laElDirty 直判 QPhoto(LiveStreamFeed) 命中
        if (targets.isEmpty() && !force) return
        val roots = mutableListOf<Pair<Any, String>>()
        CfhState.vmRef?.let { roots.add(it to "VM.") }
        CfhState.adpRef?.let { roots.add(it to "ADP.") }
        for (ar in CfhState.adpRefs.toList()) roots.add(ar to "ADP2.")
        if (roots.isEmpty()) return
        if (!CfhState.laFindPending.compareAndSet(false, true)) return
        CfhState.cleanExecutor.execute {
            CfhState.laFindPending.set(false)
            Logger.safe("laFind") {
                val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
                fun walk(holder: Any, path: String, depth: Int) {
                if (depth > 6) return
                if (!seen.add(System.identityHashCode(holder))) return
                var c: Class<*>? = holder.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 2) {
                    for (f in Reflect.nonStaticFields(c!!)) {
                        // 系统结构字段黑名单：lifecycle/Fragment 管理/拦截器列表绝不碰
                        val fn0 = f.name
                        if (fn0.contains("Lifecycle") || fn0.contains("FragmentManager") ||
                            fn0 == "mAdded" || fn0.contains("Interceptor") || fn0.contains("Callbacks")) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(holder) ?: continue
                            val npath = "$path${f.name}."
                            when (v) {
                                is List<*> -> {
                                    var hitCnt = 0
                                    for (el in v) {
                                        // 身份命中（retDelQp 已知脏）或直接判脏（rerank 插进
                                        // adapter 的脏项没经过 filterResult，不在 targets 里）
                                        if (el != null && (targets.any { it === el } || laElDirty(el))) {
                                            hitCnt++
                                            Logger.d("LAFIND HIT $path${f.name}[${v.indexOf(el)}] size=${v.size} el=${el.javaClass.name}")
                                        }
                                    }
                                    // ★ 命中即清：所有含脏项的 List 一律即时 sanitize；
                                    // 凡 VM.* 下的脏列表一律挂 TRUEWATCH 前置删除（per-cls
                                    // 去重，同运行时类的列表共享同一组 hook，身份比对过滤）
                                    if (hitCnt > 0) {
                                        if (path.startsWith("VM.") && CfhState.trueListRefs.none { it === v } &&
                                            CfhState.trueWatchIds.add(System.identityHashCode(v))) {
                                            if (CfhState.truesrcCapDiag < 20) {
                                                CfhState.truesrcCapDiag++
                                                Logger.always("TRUESRC captured: $path${f.name} id=${System.identityHashCode(v)} size=${v.size} cls=${v.javaClass.name}")
                                            }
                                            armTrueWatch(v)
                                        }
                                        @Suppress("UNCHECKED_CAST")
                                        val mut = v as? MutableList<Any?>
                                        if (mut != null) {
                                            val bs = mut.size
                                            // 真源存储列表：允许删空（pager 渲染走 V0 快照聚合，
                                            // 存储列表删空不崩；残留 1 项由 size>1 保护留脏）
                                            CfhPurge.sanitizeList(mut, "lafind:$path${f.name}", allowEmpty = true)
                                            if (mut.size != bs) Logger.d("LAFIND sanitize $path${f.name}: removed=${bs - mut.size} left=${mut.size}")
                                        }
                                    }
                                    if (depth < 6) for (el in v) if (el != null && !el.javaClass.name.startsWith("java.")) walk(el, npath + "[].", depth + 1)
                                }
                                is Array<*> -> {
                                    for (el in v) {
                                        if (el != null && (targets.any { it === el } || laElDirty(el))) {
                                            Logger.d("LAFIND HIT $path${f.name}[] size=${v.size}")
                                        }
                                    }
                                    if (depth < 6) for (el in v) if (el != null && !el.javaClass.name.startsWith("java.")) walk(el, npath + "[].", depth + 1)
                                }
                                else -> {
                                    val vn = v.javaClass.name
                                    if (!vn.startsWith("java.") && !vn.startsWith("android.") && v !is android.view.View) {
                                        walk(v, npath, depth + 1)
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
            }
                for ((r0, p0) in roots) walk(r0, p0, 0)
                Logger.d("LAFIND done targets=${targets.size} roots=${roots.size}")
            }
        }
    }
}
