package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第七步后：CfhWash 专注真源二层清洗（2026-09 S3）。
// VM 真源（V0() 快照的真源）二层清洗 + 槽位包装链提 QP；
// 写入路径监控与反查见 CfhWatch。不持有状态——一律读 CfhState。
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
                                            CfhWatch.armLaWatch(v2)
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
}
