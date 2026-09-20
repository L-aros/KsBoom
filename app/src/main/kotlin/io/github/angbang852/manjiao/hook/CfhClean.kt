package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第二步：清洗职责（2026-09 S3）。
// 列表/快照/真源清洗（filter*/sanitize*）、LAWATCH/TRUEWATCH 前置删除、laFind 反查、
// 干净队列/URL 替换、去重、refresh/loadMore 恢复。不持有状态——一律读 CfhState。
object CfhClean {
    fun filterListArgs(args: List<Any?>): Int {
        if (CfhState.liveTop) return 0
        var removed = 0
        for (a in args) {
            if (a is MutableList<*>) {
                // ★ 零分配干净路径：绝大多数列表无脏项，filter 的 ArrayList 分配
                // （每次列表变异一次）改为命中才建列表
                var hitList: ArrayList<Any?>? = null
                for (el in a) {
                    if (el != null && CfhDecide.shouldFilterFeed(el)) {
                        if (hitList == null) hitList = ArrayList()
                        hitList.add(el)
                    }
                }
                val hits = hitList
                @Suppress("UNCHECKED_CAST")
                val la = a as MutableList<Any?>
                if (hits != null && (a.size - hits.size >= 1 || a.size == 1)) {
                    val cap0 = CfhUtil.readCaption(hits.first())
                    // ★ 直接删除脏项不补位：补位池耗尽后轮转退化会反复取同一条旧视频=重复刷到。
                    // 列表短暂缩水由 prefetch(阈值4)+快手自身翻页填补，无重复
                    var deleted = 0
                    // ★ 后台闸门：非线程安全列表的删除投回主线程按身份执行
                    if (!CfhUtil.isBgMutationSafe(la) && Looper.myLooper() != Looper.getMainLooper()) {
                        // ★ 该路径是「补剔」不是「拦截」（审阅 2026-09）：投递异步，proceed
                        // 时脏项仍在列表里。不虚报 deleted（曾致 knhb 误判已拦而触发
                        // BOOTFLUSH），靠异步删除 + 后续翻页/BOOTFLUSH 兜底
                        val dirtyId = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
                        hits.forEach { dirtyId.add(it) }
                        removeByIdentityOnMain(la, dirtyId, "fla", false)
                        Logger.d("feed filtered (deferred-main) detected=${hits.size} left=${a.size}")
                    } else {
                        for (h in hits) {
                            // ★ 按身份删：equals 语义会误删「同值不同实例」的干净兄弟项
                            val i = la.indexOfFirst { it === h }
                            if (i >= 0) { la.removeAt(i); deleted++ }
                        }
                        removed += deleted
                        Logger.d("feed filtered del=$deleted left=${a.size} first: ${cap0?.take(30)}")
                        // 调用链取证（限流）：直播卡片「先渲染后删除」漏拦路径定位用
                        if (!Logger.quiet && CfhState.fltCallerDiag < 20) {
                            CfhState.fltCallerDiag++
                            Logger.d("fltCaller: " + Thread.currentThread().stackTrace.drop(2).take(8)
                                .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName })
                        }
                    }
                    // 列表偏短就提前预取下一页（阈值 4：只在真快耗尽才刷新，避免每批都触发刷新带回旧推荐=重复视频）
                    if (!Prefs.bool(Prefs.K_FLT_NOMORE, true)) {
                        Logger.d("prefetch BLOCKED by K_FLT_NOMORE=false (switch off!)")
                    } else if (a.size < 4) { Logger.d("prefetch short list=${a.size}"); triggerLoadMore() }
                } else if (hits != null) {
                    // ★ 全脏多元素批次不再整体放行（审阅 2026-09）：旧注释声称交 sanitizeList
                    // 兜底但该路径并未调用，2 条直播同批插入会原样进宿主。现显式接
                    // sanitizeList（内部含 all-dirty refresh 兜底与后台闸门，默认保留 ≥1 项）
                    try { sanitizeList(la, "fla-all") } catch (_: Throwable) {}
                }
            }
        }
        // ★ 不再普通过滤命中即 triggerRefresh：refresh 会重载 adapter 数据，打断
        // ViewPager 滑动动画（视频瞬切无过渡，AI 判定扩容后命中量大增放大此问题）。
        // 补位交给两处：列表短于阈值时的 prefetch(L3580) + 快手自身翻页加载。
        return removed
    }

    fun filterResult(result: Any?): Int {
        if (result !is MutableList<*>) return 0
        val now = System.currentTimeMillis()
        val key = System.identityHashCode(result)
        val last = CfhState.retThrottle[key]
        if (last != null && now - last < 200) return 0
        CfhState.retThrottle[key] = now
        if (CfhState.retThrottle.size > 64) CfhState.retThrottle.clear()
        if (result.isNotEmpty()) {
            CfhState.filterResultDiag++
            if (CfhState.filterResultDiag <= 10 || CfhState.filterResultDiag % 100 == 0) {
                val elem = result[0]
                val elemCls = elem?.javaClass?.name ?: "null"
                val ent = elem?.let { Reflect.readAny(it, "mEntity") }
                val entCls = ent?.javaClass?.name ?: "null"
                Logger.d("filterResult diag #${CfhState.filterResultDiag}: size=${result.size} elemCls=$elemCls entCls=$entCls")
            }
        }
        val hits = result.filter { it != null && (try { CfhDecide.shouldFilterFeed(it) } catch (_: Throwable) { false }) }
        if (hits.isEmpty()) return 0
        val cap0 = CfhUtil.readCaption(hits.first())
        // 记录快照删掉的脏元素（身份反查真源字段用）
        try {
            synchronized(CfhState.retDelQp) {
                CfhState.retDelQp.clear()
                for (h in hits) CfhState.retDelQp.add(h)
                if (CfhState.retDelQp.size > 24) CfhState.retDelQp.subList(0, CfhState.retDelQp.size - 24).clear()
            }
        } catch (_: Throwable) {}
        @Suppress("UNCHECKED_CAST")
        sanitizeList(result as MutableList<Any?>, "ret")
        Logger.d("feed filtered ret ${hits.size} first: ${cap0?.take(30)}")
        // ★ 真源清洗补链：ret 是 V0() 重建的快照副本，删了真源不动（实证「大青蜜桃」直播
        // 卡 ret 删 190 轮仍在屏）。快照删到脏项=真源必有对应脏对象，此处补调 filterVmLists
        // （500ms 节流+后台线程+Presenter/Callback 守卫齐全），vmRef 已建立时同步清真源
        if (CfhState.vmRef != null) { try { filterVmLists(CfhState.vmRef!!) } catch (_: Throwable) {} }
        // 幸存者入�?
        try {
            for (el in result) el?.let { e -> findQpInObject(e)?.let { q -> if (!CfhDecide.shouldFilterFeed(q)) offerClean(q) } }
        } catch (_: Throwable) {}
        // ★ 不 triggerRefresh（防滑动动画被打断，同 filterListArgs）

        return hits.size
    }

    fun filterResponseFields(obj: Any) {
        if (obj == null) {
            if (CfhState.respFieldCallDiag < 8) { CfhState.respFieldCallDiag++; Logger.d("respFieldCall obj=NULL") }
            return
        }
        if (CfhState.respFieldCallDiag < 8) {
            CfhState.respFieldCallDiag++
            var listCount = 0
            var c0: Class<*>? = obj.javaClass
            while (c0 != null && c0 != Any::class.java) {
                for (ff in c0.declaredFields) if (ff.type == java.util.List::class.java || ff.type.name.contains("List")) listCount++
                c0 = c0.superclass
            }
            Logger.d("respFieldCall obj=${obj.javaClass.simpleName} listFields=$listCount")
        }
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.type != java.util.List::class.java && !f.type.name.contains("List")) continue
                try {
                    f.isAccessible = true
                    val list = f.get(obj) as? List<*> ?: continue
                    if (list.isEmpty()) continue
                    val e0 = list[0]
                    // 只要能抽�?QPhoto 就视�?feed 列表（含 wrapper 包装�?
                    val isFeed = e0 != null && (findQpInObject(e0) != null || e0.javaClass.name.contains("Feed") || e0.javaClass.name.contains("Photo"))
                    if (!isFeed) continue
                    if (CfhState.respFieldDiag < 20) {
                        CfhState.respFieldDiag++
                        Logger.d("respField see ${f.name} size=${list.size} elem0=${e0?.javaClass?.name ?: "null"}")
                    }
                    val copy = arrayListOf<Any?>()
                    copy.addAll(list)
                    sanitizeList(copy, "field:${f.name}")
                    try { f.set(obj, copy) } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
    }

    fun sanitizeList(list: MutableList<Any?>, tag: String, allowEmpty: Boolean = false) {
        // ★ 上下双视频修复（2026-09）：正在显示的那条不得删除（原地删→分页器位置
        // 错位→当前页叠出两个视频），等它滑出视野再清
        val visibleNow = try { CfhCapture.currentFeedPhoto() } catch (_: Throwable) { null }
        val dirtyIdx = arrayListOf<Int>()
        val originalSize = list.size
        val now = System.currentTimeMillis()
        for (i in list.indices) {
            val it = list[i] ?: continue
            if (it === visibleNow) continue
            val dirty = try {
                val q = findQpInObject(it) ?: it
                // 兼容裸实体（LiveStreamFeed/广告实体无 mEntity 包装）：按类名兜底（受对应开关控制）；
                // ★ 宽匹配"Live"前先过结构类名黑名单（LiveConfig/LiveXxxPresenter 误删教训）
                val rawCls = it.javaClass.name
                CfhDecide.shouldFilterFeed(q) ||
                    (Prefs.bool(Prefs.K_FLT_LIVE, false) && rawCls.contains("LiveStreamFeed")) ||
                    (Prefs.bool(Prefs.K_FLT_ADS, false) && rawCls.contains("AdFeed")) ||
                    (Prefs.bool(Prefs.K_FLT_LIVE, false) && !isStructClsName(rawCls) && rawCls.contains("Live", true))
            } catch (_: Throwable) { false }
            if (dirty) dirtyIdx.add(i)
        }
        if (dirtyIdx.isEmpty()) return
        // ★ 后台闸门：非线程安全列表的删除投回主线程按身份执行
        if (!CfhUtil.isBgMutationSafe(list) && Looper.myLooper() != Looper.getMainLooper()) {
            val dirtyId = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            for (i in dirtyIdx) list.getOrNull(i)?.let { dirtyId.add(it) }
            removeByIdentityOnMain(list, dirtyId, tag, allowEmpty)
            return
        }
        var removed = 0
        for (i in dirtyIdx.sortedDescending()) {
            // 直播位置替换成 VideoFeed 会触发 onMeasure ClassCastException，优先直接删除。
            // allowEmpty（真源存储列表，非 pager 直接数据结构）删到 0 也不崩
            val isLive = try { val rawCls = list[i]?.javaClass?.name ?: ""; rawCls.contains("LiveStreamFeed") || rawCls.contains("Live", true) } catch (_: Throwable) { false }
            if (isLive && (allowEmpty || list.size > 1)) {
                list.removeAt(i); removed++
            } else if (allowEmpty || list.size > 1) {
                list.removeAt(i); removed++
            }
        }
        if (removed > 0) {
            Logger.d("sanitize $tag removed/replaced $removed (left ${list.size})")
            // ★ 不 triggerRefresh（防滑动动画被打断，同 filterListArgs）
            // 全脏批次兜底：过滤后仍剩脏项（无干净替换可用、最后1项无法移除）→ 功能性刷新
            // 拉新批次，直到有干净视频进来（"开屏前几个全广告"场景的唯一出路）
            // 受「优化无更多视频」开关控制（与 prefetch 同一功能语义）
            if (Prefs.bool(Prefs.K_FLT_NOMORE, true) && list.isNotEmpty()) {
                val leftoverDirty = list.any { el ->
                    el != null && try {
                        val q = findQpInObject(el) ?: el
                        CfhDecide.shouldFilterFeed(q)
                    } catch (_: Throwable) { false }
                }
                if (leftoverDirty && now - CfhState.lastAllDirtyRefreshAt > 3000) {
                    CfhState.lastAllDirtyRefreshAt = now
                    Logger.always("sanitize $tag all-dirty batch -> triggerRefresh (left ${list.size})")
                    triggerRefresh()
                }
            }
        }
    }

    fun removeByIdentityOnMain(list: MutableList<Any?>, dirtyId: MutableSet<Any>, tag: String, allowEmpty: Boolean) {
        CfhState.handler.post {
            try {
                var removed = 0
                for (i in list.indices.reversed()) {
                    val el = list[i]
                    if (el != null && dirtyId.contains(el) && (allowEmpty || list.size > 1)) {
                        list.removeAt(i); removed++
                    }
                }
                if (removed > 0) Logger.d("sanitize-main $tag removed $removed (left ${list.size})")
            } catch (_: Throwable) {}
        }
    }

    fun isStructClsName(cn: String): Boolean =
        cn.contains("Presenter") || cn.contains("Callback") || cn.contains("Fragment") ||
            cn.contains("Interceptor") || cn.contains("Executer") || cn.contains("Executor")

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
                                            val qpWrapped = firstEl != null && !qpDirect && findQpInObject(firstEl) != null
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
                                                sanitizeList(v2 as MutableList<Any?>, "deep:${f1.name}.${f2.name}")
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
                                Logger.d("vmListDiag ${f.name}: size=${v.size} elem=${e0?.javaClass?.name} qpIn=${e0?.let { findQpInObject(it) != null }}")
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
                                var qp = findQpInObject(el)
                                // ★ 槽位包装链 m.b 提 QP：el(x5i.q$a).b→x5i.q.b→Fragment.m.b→QPhoto。
                                // findQpInObject 在 depth<1 才下钻提不到，这里两跳直达。
                                // 已知风险：x5i.q$b 的 b 字段也可能直接指向 Fragment（slotScan 实证两种包装都存在），
                                // 故 b 链上每跳都做 Fragment/QP 类型校验，提不到就交给 BFS 兜底
                                if (qp == null) qp = findSlotQpViaMb(el)
                                // qp==null 兜底：as8.f$b 等 pager 槽位包装类（QP 藏 2+ 层深），
                                // 用 findDirtyEntityInHolder 深度 BFS 找 Live/Ad 实体判定
                                val isDirty = if (qp != null) CfhDecide.shouldFilterContent(qp)
                                    else findDirtyEntityInHolder(el) != null
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
            val q = if (qp.isAssignableFrom(el.javaClass)) el else findQpInObject(el) ?: return false
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
                                            sanitizeList(mut, "lafind:$path${f.name}", allowEmpty = true)
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
                    val q = el?.let { findQpInObject(it) }
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

                            val hits = v.filter { it != null && findQpInObject(it)?.let { q -> CfhDecide.shouldFilterFeed(q) } == true }
                            if (hits.isNotEmpty() && v.size - hits.size >= 1) {
                                val cap0 = hits.firstOrNull()?.let { findQpInObject(it)?.let { q -> CfhUtil.readCaption(q) } }
                                Logger.d("adpSelfFix ${f.name} fixed=${hits.size} cap0=${cap0?.take(14)}")
                                var fixed = 0
                                for (i in 0 until v.size) {
                                    val el = v[i] ?: continue
                                    val eq = findQpInObject(el)
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
                                el?.let { e -> findQpInObject(e)?.let { q -> if (!CfhDecide.shouldFilterFeed(q)) { try { offerClean(q) } catch (_: Throwable) {} } } }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        } catch (_: Throwable) {}
    }

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
        // ★ 反射成本核心优化：字段表按类缓存（Reflect.nonStaticFields），不再每次
        // declaredFields 复制数组；isAssignableFrom→isInstance 少一层类查找
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (f in Reflect.nonStaticFields(c!!)) {
                try {
                    val v = f.get(obj) ?: continue
                    if (qpClass.isInstance(v)) return v
                    if (depth < 1 && v.javaClass.name.contains(".") && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("android.")) {
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

    fun dedupeInsertBatch(batch: MutableList<Any?>?, tag: String): Int {
        if (batch == null || batch.isEmpty()) return 0
        if (CfhState.dedupeProbe < 3) {
            CfhState.dedupeProbe++
            val f = batch.firstOrNull()
            Logger.d("knhb dedupe probe $tag size=${batch.size} cls=${f?.javaClass?.name} id=${readPhotoId(f)} hist=${synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.size }}")
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
            Logger.d("knhb dedupe $tag dup=$dup left=${batch.size} starve=${CfhState.dedupeStarve.get()} hist=${synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.size }}")
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

            if (now - CfhState.lastLoadMoreTime > 5000) {

                Logger.always("loadMore in flight >5s -> hist reset + refresh recover")

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
            val m = cachedMethod(vm.javaClass, name) ?: continue
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

    fun cachedMethod(cls: Class<*>, name: String, vararg pt: Class<*>): java.lang.reflect.Method? {
        val key = cls.name + "#" + name + "#" + pt.size + "#" + pt.joinToString(",") { it.name }
        CfhState.methodCache[key]?.let { return it }
        val m = try { cls.getDeclaredMethod(name, *pt) } catch (_: Throwable) { null } ?: return null
        m.isAccessible = true
        CfhState.methodCache[key] = m
        return m
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

}
