package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第三步：真源深清洗与写监控（2026-09 S3）。
// VM 真源二层清洗（filterVmLists*）+ LAWATCH/TRUEWATCH 前置删除 + laFind 身份反查。
object CfhWash {
    fun filterVmLists(obj: Any) {
        if (CfhState.liveTop) return
        val now = System.currentTimeMillis()
        if (now - CfhState.lastFilterVmListsAt < 500) return
        CfhState.lastFilterVmListsAt = now
        // 一次性探针：确认 vmRef 状态与真源清洗是否激活（查「大青蜜桃」在屏滞留）
        if (!CfhState.vmRefProbeDone) { CfhState.vmRefProbeDone = true; Logger.always("VMPROBE filterVmLists armed: vm=${obj.javaClass.name}") }
        CfhState.pendingCleanObj.set(obj)
        if (CfhState.cleanDrainArmed.compareAndSet(false, true)) {
            CfhState.cleanExecutor.execute {
                CfhState.cleanDrainArmed.set(false)
                val target = CfhState.pendingCleanObj.getAndSet(null) ?: return@execute
                filterVmListsInner(target)
            }
        }
    }
    fun filterVmListsInner(obj: Any) {
        Logger.safe("filterVmLists") {
            // 一次性全字段 dump：找 QPhoto 类型字段的真实藏身处
            if (!CfhState.vmAllFieldsDumped) {
                CfhState.vmAllFieldsDumped = true
                val qpClass = CfhState.qpClassRef
                val sb = StringBuilder("vmFields ${obj.javaClass.simpleName}:")
                var c0: Class<*>? = obj.javaClass
                var l0 = 0
                while (c0 != null && c0 != Any::class.java && l0 < 5) {
                    for (f0 in c0!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f0.modifiers)) continue
                        try {
                            f0.isAccessible = true
                            val v0 = f0.get(obj)
                            val isQpList = v0 is List<*> && v0.isNotEmpty() && v0[0] != null && qpClass != null && qpClass.isAssignableFrom(v0[0]!!.javaClass)
                            val mark = if (isQpList) " <<<QPLIST" else ""
                            sb.append(" ${f0.name}:${v0?.javaClass?.simpleName ?: "null"}$mark")
                        } catch (_: Throwable) {}
                    }
                    c0 = c0.superclass; l0++
                }
                Logger.d(sb.toString())
            }
            // ★ 二层清洗：VM.l.a（u.a）类嵌套 QP/槽位列表——V0() 快照的真源，每秒随 autoSkip 清洗。
            // s0$b 是槽位包装（QP 提取需 findQpInObject）；脏项换干净缓存项，保留最后 1 项防崩。
            Logger.safe("vmDeepClean") {
                val qpClass = CfhState.qpClassRef
                var c1: Class<*>? = obj.javaClass
                var l1 = 0
                while (c1 != null && c1 != Any::class.java && l1 < 5) {
                    for (f1 in Reflect.nonStaticFields(c1!!)) {
                        try {
                            f1.isAccessible = true
                            val v1 = f1.get(obj) ?: continue
                            val vn = v1.javaClass.name
                            if (vn.startsWith("java.") || vn.startsWith("android.") || v1 is List<*> || v1 is android.view.View) continue
                            var c2: Class<*>? = v1.javaClass
                            var l2 = 0
                            while (c2 != null && c2 != Any::class.java && l2 < 3) {
                                for (f2 in Reflect.nonStaticFields(c2!!)) {
                                    try {
                                        f2.isAccessible = true
                                        val v2 = f2.get(v1)
                                        // ★ LAWATCH：l.a 是 COW 真源（sanitize deep:l.a 实证），
                                        // 捕获引用 + 挂写方法 watch hook，定位快手合并新批次的
                                        // 私有路径 → 做前置删除（消灭「先上屏后删」窗口）
                                        if (f1.name == "l" && f2.name == "a" && v2 is java.util.concurrent.CopyOnWriteArrayList<*>) {
                                            armLaWatch(v2)
                                        }
                                        if (v2 is MutableList<*> && v2.isNotEmpty()) {
                                            val firstEl = v2[0]
                                            val qpDirect = firstEl != null && qpClass != null && qpClass.isAssignableFrom(firstEl.javaClass)
                                            val qpWrapped = firstEl != null && !qpDirect && CfhProbe.findQpInObject(firstEl) != null
                                            val hasQp = qpDirect || qpWrapped
                                            // 一次性结构 dump（首元素字段图）
                                            if (hasQp && CfhState.vmListElDump < 3) {
                                                CfhState.vmListElDump++
                                                val fe = firstEl!!
                                                val sb2 = StringBuilder("vmDeepList ${f1.name}.${f2.name} size=${v2.size} qpDirect=$qpDirect cls=${fe.javaClass.name}:")
                                                var ec4: Class<*>? = fe.javaClass
                                                var l4 = 0
                                                while (ec4 != null && ec4 != Any::class.java && l4 < 2) {
                                                    for (ef4 in ec4!!.declaredFields.take(8)) {
                                                        if (java.lang.reflect.Modifier.isStatic(ef4.modifiers)) continue
                                                        try {
                                                            ef4.isAccessible = true
                                                            val ev4 = ef4.get(fe)
                                                            sb2.append(" ${ef4.name}=${ev4?.javaClass?.simpleName ?: "null"}")
                                                        } catch (_: Throwable) {}
                                                    }
                                                    ec4 = ec4.superclass; l4++
                                                }
                                                Logger.always(sb2.toString())
                                            }
                                            if (!hasQp) {
                                                // ★ s0$b 包装提不出 QP（mEntity 是 VideoFeed 非 QPhoto）
                                                // → hasQp 恒 false → l.a 真源从未被清洗（实证：V0 快照
                                                // 每次过滤 11 项而真源不动，广告上屏源）。元素本身可判脏
                                                // （decideFeedRaw 读 mEntity）——sanitizeList 同款兜底；
                                                // Presenter/Callback 守卫前置（l.c 回调表红线教训）
                                                val rawCls0 = firstEl?.javaClass?.name ?: continue
                                                if (rawCls0.contains("Presenter") || rawCls0.contains("Callback")) continue
                                                @Suppress("UNCHECKED_CAST")
                                                CfhPurge.sanitizeList(v2 as MutableList<Any?>, "deep:${f1.name}.${f2.name}")
                                                continue
                                            }
                                            // ★ 回调表保护：l.c 元素是 MilanoAttachCallbackPresenter$a
                                            // （回调注册表），深层引用 QPhoto 被误判为 QP 包装列表——
                                            // 实证清洗它会破坏快手功能，类名含 Presenter 直接跳过
                                            val elCls = firstEl?.javaClass?.name ?: continue
                                            if (elCls.contains("Presenter") || elCls.contains("Callback")) continue
                                            // 直接删除式清洗：仅 qpDirect（元素本身是 QP）允许删项；
                                            // qpWrapped（QP 深藏包装）只替换不删——包装列表可能是
                                            // pager 骨架结构（i 槽位链教训），删项=破坏结构
                                            @Suppress("UNCHECKED_CAST")
                                            val m2 = v2 as MutableList<Any?>
                                            // ★ 后台闸门（审阅 2026-09 P0）：本段跑在 cleanExecutor
                                            // 后台线程，原先对 v2 直接 removeAt 没走 isBgMutationSafe
                                            // 闸门——v2 若是 ArrayList 等非线程安全列表，与主线程宿主
                                            // 迭代并发 → 宿主 CME/IndexOOB（PROTECTIVE 救不了宿主，
                                            // 正是 sanitizeList 已写下的教训）。删除动作就地执行或
                                            // 投回主线程倒序执行
                                            val deepTag = "${f1.name}.${f2.name}"
                                            val visibleNow = try { CfhCapture.currentFeedPhoto() } catch (_: Throwable) { null }
                                            val cleanDeep: () -> Int = {
                                                var removedDeep = 0
                                                var i = m2.size - 1
                                                while (i >= 0) {
                                                    val el2 = m2[i]
                                                    // ★ 上下双视频修复（2026-09）：正在显示的那条不得删除——
                                                    // 原地删会让分页器位置错位、当前页被换数据时叠出两个视频。
                                                    // 等它滑出视野（不再是 currentFeedPhoto）再清
                                                    if (el2 != null && el2 === visibleNow) { i--; continue }
                                                    if (el2 == null || (qpDirect && CfhDecide.shouldFilterContent(el2))) { m2.removeAt(i); removedDeep++ }
                                                    i--
                                                }
                                                removedDeep
                                            }
                                            if (CfhUtil.isBgMutationSafe(m2) || Looper.myLooper() == Looper.getMainLooper()) {
                                                val sw2 = cleanDeep()
                                                if (sw2 > 0) Logger.always("vmDeepClean $deepTag: removed $sw2 (left ${m2.size})")
                                            } else {
                                                CfhState.handler.post {
                                                    try {
                                                        val sw2 = cleanDeep()
                                                        if (sw2 > 0) Logger.always("vmDeepClean-main $deepTag: removed $sw2 (left ${m2.size})")
                                                    } catch (_: Throwable) {}
                                                }
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }
                                c2 = c2.superclass; l2++
                            }
                        } catch (_: Throwable) {}
                    }
                    c1 = c1.superclass; l1++
                }
            }
            var c: Class<*>? = obj.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue

                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v is MutableList<*> && v.isNotEmpty()) {
                            if (!CfhState.vmListDiagDone) {
                                CfhState.vmListDiagDone = true
                                val e0 = v[0]
                                Logger.d("vmListDiag ${f.name}: size=${v.size} elem=${e0?.javaClass?.name} qpIn=${e0?.let { CfhProbe.findQpInObject(it) != null }}")
                                if (e0 != null) {
                                    val esb = StringBuilder()
                                    var ec: Class<*>? = e0.javaClass
                                    var el = 0
                                    while (ec != null && ec != Any::class.java && el < 2) {
                                        for (ef in ec!!.declaredFields.take(8)) {
                                            if (java.lang.reflect.Modifier.isStatic(ef.modifiers)) continue
                                            try {
                                                ef.isAccessible = true
                                                val ev = ef.get(e0)
                                                val ed = if (ev is List<*>) "List(${ev.size})" else ev?.javaClass?.simpleName ?: "null"
                                                esb.append("${ef.name}:${ed} ")
                                            } catch (_: Throwable) {}
                                        }
                                        ec = ec.superclass; el++
                                    }
                                    Logger.d("vmListDiag elem fields: $esb")
                                }
                            }
                            // ★ 替换式清洗：脏项优先换干净缓存项；缓存空时直接删除脏项——
                            // 不留脏项保底（删空列表也比看广告强，空页由快手 GrootEmptyFragment 兜底，
                            // triggerRefresh 拉新数据后自动恢复）。索引用 while 手动推进：
                            // 删除后元素前移不递增 idx，替换/保留才递增，防越界跳项
                            @Suppress("UNCHECKED_CAST")
                            val mutable = v as MutableList<Any?>
                            var swapped = 0
                            val cleanRun = Runnable {
                            var idx = 0
                            while (idx < mutable.size) {
                                val el = mutable[idx]
                                if (el == null) { mutable.removeAt(idx); swapped++; continue }
                                var qp = CfhProbe.findQpInObject(el)
                                // ★ 槽位包装链 m.b 提 QP：el(x5i.q$a).b→x5i.q.b→Fragment.m.b→QPhoto。
                                // findQpInObject 在 depth<1 才下钻提不到，这里两跳直达。
                                // 已知风险：x5i.q$b 的 b 字段也可能直接指向 Fragment（slotScan 实证两种包装都存在），
                                // 故 b 链上每跳都做 Fragment/QP 类型校验，提不到就交给 BFS 兜底
                                if (qp == null) qp = findSlotQpViaMb(el)
                                // qp==null 兜底：as8.f$b 等 pager 槽位包装类（QP 藏 2+ 层深），
                                // 用 findDirtyEntityInHolder 深度 BFS 找 Live/Ad 实体判定
                                val isDirty = if (qp != null) CfhDecide.shouldFilterContent(qp)
                                    else CfhProbe.findDirtyEntityInHolder(el) != null
                                if (qp == null && !isDirty && CfhState.vmListElDump < 3) {
                                    CfhState.vmListElDump++
                                    val sb = StringBuilder("vmElDump ${f.name}[$idx] cls=${el.javaClass.name}:")
                                    var ec2: Class<*>? = el.javaClass
                                    var l2 = 0
                                    while (ec2 != null && ec2 != Any::class.java && l2 < 3) {
                                        for (ef2 in ec2!!.declaredFields) {
                                            if (java.lang.reflect.Modifier.isStatic(ef2.modifiers)) continue
                                            try {
                                                ef2.isAccessible = true
                                                val ev2 = ef2.get(el)
                                                sb.append(" ${ef2.name}=${ev2?.javaClass?.simpleName ?: "null"}")
                                                if (ev2 is List<*> && ev2.isNotEmpty()) {
                                                    sb.append("[0]=${ev2[0]?.javaClass?.name ?: "null"}")
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                        ec2 = ec2.superclass; l2++
                                    }
                                    Logger.d(sb.toString())
                                }
                                if (!isDirty) { idx++; continue }
                                // 直接删除：有 QP 判脏依据（qp!=null）才删——
                                // qp==null 靠 BFS 命中的项可能是 pager 骨架/回调结构（l.c 教训），
                                // 删结构会破坏快手功能，只跳过不删
                                if (qp != null) { mutable.removeAt(idx); swapped++ }
                                else idx++
                            }
                            if (swapped > 0) Logger.always("vmListClean ${f.name}: swapped/removed $swapped (left ${mutable.size})")
                            }
                            // ★ 后台闸门：非线程安全列表（ArrayList 等）投回主线程改
                            if (CfhUtil.isBgMutationSafe(mutable) || Looper.myLooper() == Looper.getMainLooper()) cleanRun.run()
                            else CfhState.handler.post { try { cleanRun.run() } catch (_: Throwable) {} }

                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
    }
    fun findSlotQpViaMb(el: Any): Any? {
        val qpClass = CfhState.qpClassRef ?: return null
        try {
            var cur: Any = el
            for (hop in 0 until 5) {
                if (qpClass.isAssignableFrom(cur.javaClass)) return cur
                if (cur.javaClass.name.endsWith("Fragment")) {
                    val m = Reflect.readAny(cur, "m") ?: return null
                    val qp = Reflect.readAny(m, "b") ?: return null
                    return if (qpClass.isAssignableFrom(qp.javaClass)) qp else null
                }
                val nxt = Reflect.readAny(cur, "b") ?: return null
                if (nxt is Collection<*> || nxt is android.view.View) return null
                cur = nxt
            }
        } catch (_: Throwable) {}
        return null
    }
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
        if (!force && now - CfhState.laFindAt < 2500) return
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
