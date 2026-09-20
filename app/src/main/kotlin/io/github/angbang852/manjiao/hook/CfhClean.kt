package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第三步：CfhClean 收口为「批量过滤核心」（2026-09 S3）。
// 列表/快照/响应字段清洗 + 统一删除器。真源深清洗→CfhWash，替换料→CfhSwap，
// 对象图定位→CfhProbe，供给恢复→CfhSupply。不持有状态——一律读 CfhState。
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
                        CfhPurge.removeByIdentityOnMain(la, dirtyId, "fla", false)
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
                    } else if (a.size < 4) { Logger.d("prefetch short list=${a.size}"); CfhSupply.triggerLoadMore() }
                } else if (hits != null) {
                    // ★ 全脏多元素批次不再整体放行（审阅 2026-09）：旧注释声称交 sanitizeList
                    // 兜底但该路径并未调用，2 条直播同批插入会原样进宿主。现显式接
                    // sanitizeList（内部含 all-dirty refresh 兜底与后台闸门，默认保留 ≥1 项）
                    try { CfhPurge.sanitizeList(la, "fla-all") } catch (_: Throwable) {}
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
        CfhPurge.sanitizeList(result as MutableList<Any?>, "ret")
        Logger.d("feed filtered ret ${hits.size} first: ${cap0?.take(30)}")
        // ★ 真源清洗补链：ret 是 V0() 重建的快照副本，删了真源不动（实证「大青蜜桃」直播
        // 卡 ret 删 190 轮仍在屏）。快照删到脏项=真源必有对应脏对象，此处补调 filterVmLists
        // （500ms 节流+后台线程+Presenter/Callback 守卫齐全），vmRef 已建立时同步清真源
        if (CfhState.vmRef != null) { try { CfhWash.filterVmLists(CfhState.vmRef!!) } catch (_: Throwable) {} }
        // 幸存者入�?
        try {
            for (el in result) el?.let { e -> CfhProbe.findQpInObject(e)?.let { q -> if (!CfhDecide.shouldFilterFeed(q)) CfhSwap.offerClean(q) } }
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
                    val isFeed = e0 != null && (CfhProbe.findQpInObject(e0) != null || e0.javaClass.name.contains("Feed") || e0.javaClass.name.contains("Photo"))
                    if (!isFeed) continue
                    if (CfhState.respFieldDiag < 20) {
                        CfhState.respFieldDiag++
                        Logger.d("respField see ${f.name} size=${list.size} elem0=${e0?.javaClass?.name ?: "null"}")
                    }
                    val copy = arrayListOf<Any?>()
                    copy.addAll(list)
                    CfhPurge.sanitizeList(copy, "field:${f.name}")
                    try { f.set(obj, copy) } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
    }




}
