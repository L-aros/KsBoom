package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第五步：数据源/响应钩（2026-09 S3）。
// 网络响应/缓存/Milano 容器/PageList/knhb.T0/LiveStreamFeed 构造/DataSource/ViewModel
// 的取数入口拦截 + 冷启动 BOOTFLUSH。过滤动作全部委托 CfhClean/CfhWash/CfhDecide 等。
object CfhFeedHook {

    // ==================== LAWATCH：l.a 写入监控 + 前置删除 ====================
    // 痛点：真源清洗（sanitize deep:l.a）已生效但时机晚——直播先进 l.a → pager
    // 渲染上屏（用户先看到）→ V0 getter 触发 → 快照命中才补删真源（画面卡住）。
    // B(List) hook 实证未命中合并路径 → 快手走私有内部方法写 l.a。
    // l.a 是 CopyOnWriteArrayList，外部写入必经 add/addAll 系方法 → 全局 hook 这些
    // 方法 + 身份比对（this === laRef），即可 100% 覆盖所有写入路径：
    //  1) addAll/addAllAbsent：before 阶段直接过滤参数 Collection（前置删除，
    //     脏项根本进不了 l.a，早于渲染）
    //  2) add/addIfAbsent：after 阶段立即移除（同一调用栈内，仍早于渲染）
    //  3) 每次命中打印完整调用栈（LAWATCH stack）→ 定位私有合并方法
    // 性能：CopyOnWriteArrayList 是热点类，callback 首行 identity 比对开销可忽略；
    // 定位到私有路径后应移除本插桩，改为精准 hook 合并方法



    // 精确判定：仅 feed 数据对象（QPhoto 本体 / mEntity 含 Feed 的包装）参与判脏。
    // 教训 2026-09-08：宽泛判定把 FragmentManager.mAdded、l.c Presenter 回调表、
    // mBackPressInterceptors、slideprocess 追踪器全误删（匿名类 q$a/d$c 不含
    // "Presenter" 字样守卫失效）——非 feed 对象一律不碰

    // ==================== LAWATCH end ====================

    // ★ keep-latest 去重（审阅 2026-09）：已排队/执行中时新触发直接丢弃，防滚动期
    // rerank.d.j 高频绕流把全图 BFS 任务在单线程 executor 里积压
    // ★ 身份引用数组：hook 回调热路径禁用任何集合类（SetFromMap.contains 内部
    // 会触发其他被 hook 的集合方法 → 递归风暴实证），只用纯 === 数组遍历


    // ★ TRUEWATCH：真源 h.m.p.a 写方法 watch（身份比对）——前置删除脏项 + 打调用
    // 栈定位快手合并私有方法。对实际运行时类挂 add/addAll/set 系（ArrayList 走
    // add(E)/add(int,E)/addAll(Collection)/addAll(int,Collection)/set(int,E)）

    // ★ LAFIND：脏 QPhoto 身份反查真源字段——BFS 遍历 VM+adapter 对象图（深度 6），
    // 找出「哪些字段的 List/数组以身份相同包含该元素」，命中即 allowEmpty 清理。
    // 根集：vmRef（VM 数据源）+ adpRef/adpRefs（pager adapter——rerank 把直播插进
    // adapter 数据集，不在 VM 真源里，漏拦实证 2026-09-08）。
    // 后台线程跑（ANR 红线），2.5 秒节流



    internal fun hookFeedResponse(xp: XposedInterface, cl: ClassLoader) {
        val respNames = arrayOf(
            "com.yxcorp.gifshow.detail.slideplay.hotphoto.network.SlideHotPhotoResponse",
            "com.yxcorp.gifshow.feed.response.PhotoResponse",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.feed.CommonFeedSlideResponse"
        )
        for (rn in respNames) {
            val rc = Reflect.findClass(rn, cl) ?: continue
            Logger.d("hookFeedResp: $rn")
            var c: Class<*>? = rc
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    Logger.d("  resp field: ${f.name} type=${f.type.name}")
                }
                for (m in c.declaredMethods) {
                    val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                    if (isRetList || hasListParam) {
                        Logger.d("  resp method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName} static=${java.lang.reflect.Modifier.isStatic(m.modifiers)}")
                        Logger.safe("hookResp.${rn}.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("resp.${rn}.${m.name}").intercept { chain ->
                                try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                                val result = chain.proceed()
                                try { CfhClean.filterResult(result) } catch (_: Throwable) {}
                                try { CfhClean.filterResponseFields(chain.thisObject) } catch (_: Throwable) {}
                                result
                            }
                        }
                    }
                }
                c = c.superclass; lvl++
            }
        }
    }


    internal fun hookCacheClasses(xp: XposedInterface, cl: ClassLoader) {
        val cacheNames = arrayOf(
            "com.yxcorp.gifshow.feed.cache.home.HomeResponseEvictingQueueCorrector",
            "com.yxcorp.gifshow.feed.cache.home.HomeResponseLiveFilter",
            "com.yxcorp.gifshow.feed.cache.home.HomeResponseCache"
        )
        for (cn in cacheNames) {
            val cc = Reflect.findClass(cn, cl) ?: continue
            Logger.d("hookCache: $cn")
            var c: Class<*>? = cc
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (m in c!!.declaredMethods) {
                    val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                    if (isRetList || hasListParam) {
                        Logger.d("  cache method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                        Logger.safe("hookCache.${cn}.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("cache.${cn}.${m.name}").intercept { chain ->
                                try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                                val result = chain.proceed()
                                try { CfhClean.filterResult(result) } catch (_: Throwable) {}
                                result
                            }
                        }
                    }
                }
                c = c.superclass; lvl++
            }
        }
    }

    // Milano �?feed 架构：直�?hotphoto)/AI(airecommendslide)/视频(commonfeedslide) 各是独立容器�?
    // 容器类被混淆�?a，这里探测其取数方法�?hook 过滤�?

    internal fun hookMilanoContainers(xp: XposedInterface, cl: ClassLoader) {
        val names = arrayOf(
            "com.yxcorp.gifshow.detail.slideplay.hotphoto.container.a",
            "com.yxcorp.gifshow.detail.slideplay.airecommendslide.a",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.a"
        )
        for (cn in names) {
            val cc = Reflect.findClass(cn, cl) ?: continue
            hookMilanoContainer(xp, cc, cn)
        }
    }
    private fun hookMilanoContainer(xp: XposedInterface, cc: Class<*>, cn: String) {
        synchronized(CfhState.hookedMilanoContainers) { if (!CfhState.hookedMilanoContainers.add(CfhUtil.hookKey(cc))) return }
        Logger.d("hookMilano: $cn")
        var c: Class<*>? = cc
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 4) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val ft = f.type.name
                if (ft.contains("List") || ft.contains("Collection") || ft.contains("QPhoto") || ft.contains("Feed")) {
                    Logger.d("  milano field: ${f.name} type=${ft}")
                }
            }
            for (m in c.declaredMethods) {
                val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                if (isRetList || hasListParam) {
                    Logger.d("  milano method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                    Logger.safe("hookMilano.${cn}.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("milano.${cn}.${m.name}").intercept { chain ->
                            try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                            val result = chain.proceed()
                            try { CfhClean.filterResult(result) } catch (_: Throwable) {}
                            try { CfhClean.filterResponseFields(chain.thisObject) } catch (_: Throwable) {}
                            result
                        }
                    }
                }
            }
            c = c.superclass; lvl++
        }
    }

    // Milano 数据�?= �?PageList（双向分页列表）。hook 其取数方法，
    // 在源头把直播/AI/广告/剧集 项替换成干净项或过滤掉�?

    internal fun hookPageLists(xp: XposedInterface, cl: ClassLoader) {
        val names = arrayOf(
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.CommonFeedSlideBidirectionalPageList",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.PostCommonFeedSlidePageList",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.PostLocalFeedSlidePageList",
            "com.yxcorp.gifshow.detail.slideplay.airecommendslide.slide.network.AiRecommendSlidePageList"
        )
        for (cn in names) {
            val cc = Reflect.findClass(cn, cl) ?: continue
            val already = synchronized(CfhState.hookedPageLists) { !CfhState.hookedPageLists.add(CfhUtil.hookKey(cc)) }
            if (already) continue
            Logger.d("hookPageList: $cn")
            var c: Class<*>? = cc
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (m in c!!.declaredMethods) {
                    val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    val isIntP = m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
                    val nonPrimRet = !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != java.lang.String::class.java
                    if ((isIntP && nonPrimRet) || isRetList) {
                        Logger.d("  plist method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                        Logger.safe("hookPageList.${cn}.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("plist.${cn}.${m.name}").intercept { chain ->
                                try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                                val r = chain.proceed()
                                try { CfhClean.filterResult(r) } catch (_: Throwable) {}
                                // 源头拦截：单项返回是直播/广告直接返回 null，让 adapter 跳过该位置（不进信息流）
                                try {
                                    if (r != null) {
                                        val q = CfhProbe.findQpInObject(r)
                                        if (q != null && CfhDecide.shouldFilterFeed(q)) {
                                            if (CfhState.plistSkipDiag < 30) { CfhState.plistSkipDiag++; Logger.d("plist skip ${m.name} (${CfhUtil.readCaption(q)?.take(15)})") }
                                            return@intercept null
                                        }
                                    }
                                } catch (_: Throwable) {}
                                r
                            }
                        }
                    }
                }
                c = c.superclass; lvl++
            }
        }
    }

    // ★ 防重复视频：refresh（BOOTFLUSH/prefetch）重拉第一页可能带回已供给过的视频。
    // 已供给 photoId 历史（LRU 上限 500）+ QPhoto→photoId 身份缓存（每对象只反射一次）。
    // 去重只作用于实测的数据载荷路径：T0 的 args[2]（update 批次）+ E1 的 args[0]，
    // 删批次里 photoId 已在历史中的项；护栏同 filterListArgs（删后至少留 1 或原本 ≤1）。
    // ★ getPhotoId Method 缓存（含负缓存）：包装类无此方法时原先每元素每次抛
    // NoSuchMethodException（栈填充极贵），E1 大批次下纯烧 CPU
    // ★ 饥饿计数原子化：hook 回调跑在任意线程，非原子 ++/清零丢计数会让
    // 「连续 4 批饥饿→清历史自愈」延迟触发（功能性计数，非诊断）

    internal fun hookKnhbT0(xp: XposedInterface, cl: ClassLoader) {
        if (CfhState.knhbT0Hooked) return
        val tryNames = listOf("knh.b", "knh\$b")
        for (cn in tryNames) {
            val cc = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
            CfhState.knhbT0Hooked = true
            Logger.d("knhb found cls=$cn methodCount=${cc.declaredMethods.size}")
            for (m in cc.declaredMethods) {
                val nm = m.name
                val ptypes = m.parameterTypes
                val hasListParam = ptypes.any { it == java.util.List::class.java }
                if (!hasListParam) continue
                Logger.d("knhb method: $nm(${ptypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                Logger.safe("knhb.$cn.$nm") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("knhb.$cn.$nm").intercept { chain ->
                        try {
                            if (CfhState.knhbCallDiag < 40) {
                                CfhState.knhbCallDiag++
                                val sizes = chain.args.map { if (it is List<*>) "L${it.size}" else it?.javaClass?.simpleName ?: "-" }.joinToString(",")
                                Logger.d("knhb call $nm($sizes) hist=${synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.size }}")
                            }
                            val removed = CfhClean.filterListArgs(chain.args)
                            if (removed > 0 && CfhState.knhbT0Diag < 30) {
                                CfhState.knhbT0Diag++
                                Logger.d("knhb.$nm filtered del=$removed")
                            }
                            // ★ 启动兜底「无条件武装」（2026-09-21 实测修复）：原先
                            // scheduleBootFlush 只在 removed>0 时调度 —— 条件恰好反了：
                            // 它本是"首批没删掉脏项、信息流没被过滤"的补药，却只在"已经
                            // 删掉了"时发放。实测首批 del=0 ⇒ BOOTFLUSH 从未调度 ⇒
                            // knhbInst 恒 null（日志实证 loadMore SKIP: knhbInst=null）。
                            // 改为首次 T0/E1 进入即武装（scheduleBootFlush 自带
                            // bootFlushDone/Pending 一次性守卫，不会重复调度）。
                            if (nm == "T0" || nm == "E1") {
                                scheduleBootFlush(chain.thisObject)
                                armEarlyTrueSourceWash()
                            }
                            // ★ 防重复（单点去重）：E1 全时去重（服务端原始批次主战场）；
                            // T0 仅 refresh 重拉路径（reason 含 firstRequest）去重——loadMore 续拉时
                            // T0 的 update 批次是 E1 刚供给的幸存项（已在 hist），再判重=双重去重误删
                            if (nm == "E1" && ptypes.size == 1) {
                                CfhProbe.dedupeInsertBatch(chain.args.getOrNull(0) as? MutableList<Any?>, "E1")
                            } else if (nm == "T0" && ptypes.size == 6) {
                                val reason = chain.args.getOrNull(5) as? String ?: ""
                                if (reason.contains("firstRequest")) {
                                    CfhProbe.dedupeInsertBatch(chain.args.getOrNull(2) as? MutableList<Any?>, "T0fr")
                                }
                            }
                        } catch (_: Throwable) {}
                        val r = chain.proceed()
                        try { if (r is List<*>) CfhClean.filterResult(r) } catch (_: Throwable) {}
                        r
                    }
                }
            }
            if (CfhState.knhbT0Hooked) break
        }
        if (!CfhState.knhbT0Hooked) Logger.d("knhb NOT FOUND (obfuscated?)")
    }
    private fun scheduleBootFlush(src: Any?) {
        // ★★ khhbInst 捕获必须与开关解耦（2026-09 自测发现的自身 bug）：
        // 首版把门控放在函数开头，导致关掉开关后 knhbInst 恒为 null，
        // 连带打死补位加载 —— 实测 probe11 三连
        // 「loadMore SKIP: knhbInst=null -> fallback refresh」。
        // 开关只应控制「是否发起那次刷新」，不应影响实例捕获（供 loadMore 用）。
        if (src != null && CfhState.knhbInst?.get() == null) {
            CfhState.knhbInst = java.lang.ref.WeakReference(src)
        }
        // ★ 开关化（2026-09 用户要求）：首次进主页自动刷新一次原为无条件行为。
        // 关掉后仅跳过这次 refresh，其余链路不变，用户可 A/B 对比
        // 「首屏这次刷新是否反而把脏内容带进来」。
        if (!Prefs.bool(Prefs.K_FLT_BOOTFLUSH, true)) {
            if (!CfhState.bootFlushDone) { CfhState.bootFlushDone = true; Logger.always("BOOTFLUSH disabled by switch (inst captured)") }
            return
        }
        if (CfhState.bootFlushDone || CfhState.bootFlushPending) return
        CfhState.bootFlushPending = true
        Logger.d("BOOTFLUSH scheduled 2s")
        CfhState.handler.postDelayed({
            CfhState.bootFlushPending = false
            try { doBootFlush() } catch (e: Throwable) { Logger.d("BOOTFLUSH err: ${e.message}") }
        }, 2000)
    }

    // ★ 真源清洗前移（2026-09-21 实测修复）：filterVmLists（真源二层清洗）的武装原先
    // 全部依赖 pager/fragment 生命周期（hookFragCallSeq / findPager / filterResult 补链），
    // 实测约启动后 9 秒才 armed，而首批数据约 6 秒就进了真源 —— 中间 1.8~2 秒空白期内
    // 真源是脏的（VMPROBE filterVmLists armed 日志晚于首批 1.8s）。
    // 改为首次 T0/E1 进入后起一个有界重试：vmRef 一出现立即清洗（500ms × 12 = 6 秒窗口，
    // 覆盖整个启动期）；filterVmLists 自带 500ms 节流与 keep-latest 队列，无需去重。
    private fun armEarlyTrueSourceWash() {
        if (CfhState.earlyWashArmed) return
        CfhState.earlyWashArmed = true
        Logger.d("earlyWash armed")
        val r = object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                if (tries > 12) return
                val vm = CfhState.vmRef
                if (vm == null) { CfhState.handler.postDelayed(this, 500); return }
                try { CfhWash.filterVmLists(vm) } catch (_: Throwable) {}
                if (tries == 1) Logger.always("earlyWash: bootstrap true-source clean fired")
                CfhState.handler.postDelayed(this, 500)
            }
        }
        CfhState.handler.postDelayed(r, 300)
    }

    private fun doBootFlush() {
        if (CfhState.bootFlushDone) return
        CfhState.bootFlushDone = true
        val inst = CfhState.knhbInst?.get()
        Logger.d("BOOTFLUSH run inst=${inst?.javaClass?.name ?: "null"}")
        // 路径1：数据源实例（含父类）上名字含 refresh/reload/requery 的无参 void 方法
        if (inst != null) {
            var c: Class<*>? = inst.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 6) {
                for (m in c!!.declaredMethods) {
                    if (m.parameterTypes.isNotEmpty() || m.returnType != Void.TYPE) continue
                    val mn = m.name.lowercase()
                    if (!(mn.contains("refresh") || mn.contains("reload") || mn.contains("requery"))) continue
                    try {
                        m.isAccessible = true
                        m.invoke(inst)
                        Logger.d("BOOTFLUSH called ${m.name} on ${c!!.name}")
                        return
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            // 路径2：遍历字段值按运行时类型找 kik.o0 请求器（字段声明类型是 kik.i 接口，
            // 非dnh.包名，须按字段值的实际继承链找 refresh() 无参方法）
            var fc: Class<*>? = inst.javaClass
            var flvl = 0
            while (fc != null && fc != Any::class.java && flvl < 6) {
                for (f in fc!!.declaredFields) {
                    try {
                        f.isAccessible = true
                        val req = f.get(inst) ?: continue
                        if (req is Collection<*> || req is android.view.View) continue
                        var oc: Class<*>? = req.javaClass
                        var ol = 0
                        while (oc != null && oc != Any::class.java && ol < 8) {
                            for (rm in oc!!.declaredMethods) {
                                if (rm.name != "refresh" || rm.parameterTypes.isNotEmpty() || rm.returnType != Void.TYPE) continue
                                try {
                                    rm.isAccessible = true
                                    rm.invoke(req)
                                    Logger.d("BOOTFLUSH called refresh on ${req.javaClass.name} (field ${f.name})")
                                    return
                                } catch (_: Throwable) {}
                            }
                            oc = oc.superclass; ol++
                        }
                    } catch (_: Throwable) {}
                }
                fc = fc.superclass; flvl++
            }
        }
        // 路径3：兜底现有 vmRef 刷新链
        val ok = try { CfhSupply.triggerRefresh() } catch (_: Throwable) { false }
        Logger.d("BOOTFLUSH fallback triggerRefresh=$ok")
    }
    private fun hookDataSource(c: Class<*>) {
        val xp = CfhState.xpRef ?: return
        synchronized(CfhState.hookedDsClasses) {
            if (!CfhState.hookedDsClasses.add(CfhUtil.hookKey(c))) return
        }
        Logger.d("hookDataSource: ${c.name}")
        val qpClass = try { Class.forName("com.yxcorp.gifshow.entity.QPhoto", false, c.classLoader) } catch (_: Throwable) { null }
        var batchHooked = 0; var singleHooked = 0
        for (m in c.declaredMethods) {
            val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
            val isRetQp = qpClass != null && m.returnType == qpClass
            if ((m.name.startsWith("get") || m.name.startsWith("is")) && !isRetList && !isRetQp) continue
            Logger.d("ds method: ${m.name} ret=${m.returnType.simpleName} params=${m.parameterTypes.map { it.simpleName }}")
            val listCount = m.parameterTypes.count {
                it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection")
            }
            if (listCount >= 2) {
                Logger.safe("hookDSBatch.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.bat.${c.name}.${m.name}").intercept { chain ->
                        try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                        val result = chain.proceed()
                        try { CfhClean.filterResult(result) } catch (_: Throwable) {}
                        result
                    }
                }
                batchHooked++; continue
            }
            if (m.returnType == java.util.List::class.java || m.returnType.name.contains("List")) {
                Logger.safe("hookDSRet.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.ret.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try { CfhClean.filterResult(result) } catch (_: Throwable) {}
                        result
                    }
                }
                batchHooked++; continue
            }
            if (qpClass != null && m.parameterTypes.any { it == qpClass }) {
                Logger.safe("hookDSOne.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.one.${c.name}.${m.name}").intercept { chain ->
                        try {
                            for (a in chain.args) {
                                if (a != null && a.javaClass == qpClass && CfhDecide.shouldFilterFeed(a)) {
                                    Logger.d("feed filtered single: ${CfhUtil.readCaption(a)?.take(30)}")
                                    return@intercept null
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                        null
                    }
                }
                singleHooked++
            }
            if (isRetQp) {
                Logger.safe("hookDSRetQP.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.rqp.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (result != null && CfhDecide.shouldFilterFeed(result)) {
                                Logger.d("feed filtered retQP: ${CfhUtil.readCaption(result)?.take(30)}")
                                return@intercept null
                            }
                        } catch (_: Throwable) {}
                        result
                    }
                }
                singleHooked++
            }
        }
        Logger.d("hookDataSource done: ${c.name} batch=$batchHooked single=$singleHooked")
    }
    internal fun hookViewModel(vm: Any) {
        val xp = CfhState.xpRef ?: return
        val c = vm.javaClass
        synchronized(CfhState.hookedVmClasses) { if (!CfhState.hookedVmClasses.add(CfhUtil.hookKey(c))) return }
        val qpClass = try { Class.forName("com.yxcorp.gifshow.entity.QPhoto", false, c.classLoader) } catch (_: Throwable) { null } ?: return
        Logger.d("hookViewModel: ${c.name}")
        CfhState.qpClassRef = qpClass
        CfhState.vmRef = vm
        var cls: Class<*>? = c
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 6) {
            for (m in cls!!.declaredMethods) {
                if (CfhState.vmMethodDump < 200) {
                    CfhState.vmMethodDump++
                    Logger.d("vmM ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                }
                if (m.returnType == Void.TYPE && m.parameterTypes.size <= 2) {
                    Logger.d("vm void: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")})")
                }
                if (m.parameterTypes.any { qpClass.isAssignableFrom(it) } && m.returnType == Void.TYPE) installVmShow(xp, c, m, qpClass)
                if (m.parameterTypes.any { java.util.List::class.java.isAssignableFrom(it) || it.name.contains("List") } && m.returnType == Void.TYPE) installVmList(xp, c, m)
                // y0()/B0()/E()/F0()/H()/H0()/V0() 等返�?List 的方�?= 直播/卡片�?adapter 的数据源�?
                // 直接过滤返回值，让直播卡根本进不�?adapter�?
                if ((m.returnType == java.util.List::class.java || m.returnType.name.contains("List")) && m.parameterTypes.isEmpty()) installVmListRet(xp, c, m)
                // ★ rerank 插卡唯一入口 T1(int,QPhoto,boolean,String)（LiveRerankPresenter d.G
                // → VM.T1 → data_source_service.q() 单条插入）：非 List 批次 filterListArgs
                // 结构性拦不到，data source 内部列表也不在 laFind 根集——T1 入口是唯一拦点。
                // 判定用 shouldFilterFeed + decideFeedRaw 同步全量兜底（T1 一次性入口不能走
                // 异步缓存：miss 先放行=卡必上屏）；T1CALL 无条件打日志取证 T1 是否真被调用
                if (m.name == "T1" && m.parameterTypes.size == 4 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    qpClass.isAssignableFrom(m.parameterTypes[1]) &&
                    m.parameterTypes[2] == java.lang.Boolean.TYPE &&
                    m.parameterTypes[3] == String::class.java) installVmT1(xp, c, m)
                if (m.returnType == qpClass && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) installVmGet(xp, c, m, qpClass)
                if (m.name == "y" || m.name == "y0") installVmY(xp, c, m)
            }
            cls = cls.superclass; lvl++
        }
    }
    private fun installVmShow(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method, qpClass: Class<*>) {
        Logger.safe("hookVMShow.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.show.${c.name}.${m.name}").intercept { chain ->
                try {
                    for (a in chain.args) {
                        if (a != null && qpClass.isAssignableFrom(a.javaClass) && CfhDecide.shouldFilterFeed(a)) {
                            Logger.d("vm filtered show: ${CfhUtil.readCaption(a)?.take(30)}")
                            return@intercept null
                        }
                    }
                } catch (_: Throwable) {}
                chain.proceed()
                null
            }
        }
    }

    private fun installVmList(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookVMList.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.list.${c.name}.${m.name}").intercept { chain ->
                try {
                    val removed = CfhClean.filterListArgs(chain.args)
                    if (removed > 0) Logger.d("vm filtered list: $removed via ${m.name}")
                } catch (_: Throwable) {}
                chain.proceed()
                null
            }
        }
    }

    private fun installVmListRet(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.d("vmListRet sig: ${m.name}() -> ${m.returnType.simpleName}")
        Logger.safe("hookVMListRet.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.lret.${c.name}.${m.name}").intercept { chain ->
                val r = chain.proceed()
                try {
                    if (r is List<*> && r.isNotEmpty()) {
                        val before = r.size
                        CfhClean.filterResult(r)
                        if (r.size != before) {
                            Logger.d("vm lret ${m.name} filtered: $before -> ${r.size}")
                            // ★ 快照诊断：若同一方法反复出现相同 before（如反复 7->2），
                            // 说明 V0() 每次返回新建快照，删快照无效，真源在别处。
                            CfhState.lretDiagCount++
                            if (CfhState.lretDiagCount <= 6) {
                                val implCls = r.javaClass.name
                                val firstEl = r.firstOrNull()
                                Logger.always("lretDIAG ${m.name}: impl=$implCls idHc=${System.identityHashCode(r)} size=${r.size} firstEl=${firstEl?.javaClass?.name ?: "null"}")
                            }
                        }
                    }
                } catch (_: Throwable) {}
                r
            }
        }
    }

    private fun installVmT1(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookVMT1") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.t1.${c.name}").intercept { chain ->
                try {
                    val t1idx = chain.args.getOrNull(0) as? Int ?: -1
                    val t1tag = chain.args.getOrNull(3) as? String ?: ""
                    val t1qp = chain.args.getOrNull(1)
                    val t1dirty = t1qp != null && (CfhDecide.shouldFilterFeed(t1qp) || try { CfhDecide.decideFeedRaw(t1qp) } catch (_: Throwable) { false })
                    Logger.always("T1CALL idx=$t1idx tag=$t1tag dirty=$t1dirty CfhState.liveTop=${CfhState.liveTop} qp=${t1qp?.javaClass?.simpleName ?: "null"}")
                    if (t1dirty) {
                        Logger.always("T1 SWALLOWED idx=$t1idx tag=$t1tag")
                        return@intercept null
                    }
                } catch (_: Throwable) {}
                chain.proceed()
                null
            }
        }
    }

    private fun installVmGet(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method, qpClass: Class<*>) {
        Logger.d("vmGet sig: ${m.name}(int) -> ${m.returnType.simpleName}")
        Logger.safe("hookVMGet.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.get.${c.name}.${m.name}").intercept { chain ->
                val r = try { chain.proceed() } catch (_: Throwable) { null }
                try {
                    if (r != null && qpClass.isAssignableFrom(r.javaClass)) {
                        val idx = (chain.args.getOrNull(0) as? Int) ?: -1
                        var clsQ: Any? = r
                        // 空壳实例兜底：用富数据实例分�?
                        if (CfhUtil.readUserName(r, Reflect.readAny(r, "mEntity") ?: r).isEmpty()) {
                            clsQ = CfhCapture.findWindowQp(idx) ?: r
                        }
                        if (clsQ != null && CfhDecide.shouldFilterFeed(clsQ)) {
                            val clean = CfhSwap.pickFromQueue()
                            if (clean != null) {
                                if (CfhState.vmGetSubCount < 20) { CfhState.vmGetSubCount++; Logger.d("vm getter ${m.name} -> clean: ${CfhUtil.readCaption(clsQ)?.take(18)}") }
                                return@intercept clean
                            }
                        }
                    }
                } catch (_: Throwable) {}
                r
            }
        }
    }

    private fun installVmY(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.d("vmY hook: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
        Logger.safe("hookVMY.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.y.${c.name}.${m.name}").intercept { chain ->
                try {
                    val removed = CfhClean.filterListArgs(chain.args)
                    if (removed > 0) Logger.d("vmY filtered list: $removed")
                } catch (_: Throwable) {}
                val r = chain.proceed()
                try {
                    if (CfhState.vmYDiag < 20) {
                        CfhState.vmYDiag++
                        Logger.d("vmY ret: ${m.name} -> ${r?.javaClass?.name ?: "null"}")
                    }
                } catch (_: Throwable) {}
                try { CfhClean.filterResult(r) } catch (_: Throwable) {}


                r
            }
        }
    }

    internal fun hookLiveFeedConstruct(xp: XposedInterface, cl: ClassLoader) {
        val cn = "com.kuaishou.android.model.feed.LiveStreamFeed"
        val c = Reflect.findClass(cn, cl) ?: return
        Logger.d("hookLiveFeedConstruct: $cn ctors=${c.declaredConstructors.size}")
        // dump 字段名（仅一次）
        if (CfhState.liveCtorDiag == 0) {
            var fc: Class<*>? = c
            while (fc != null && fc != Any::class.java) {
                for (f in fc!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    Logger.d("  LiveStreamFeed fld: ${fc.simpleName}.${f.name}:${f.type.simpleName}")
                }
                fc = fc.superclass
            }
        }
        for (ctor in c.declaredConstructors) {
            Logger.safe("hookLiveCtor.${ctor.parameterTypes.size}") {
                xp.hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("liveCtor.${cn}.${ctor.parameterTypes.size}").intercept { chain ->
                    val r = chain.proceed()
                    try {
                        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhState.liveTop) {
                            var zapped = 0
                            var fc: Class<*>? = r.javaClass
                            while (fc != null && fc != Any::class.java) {
                                val fcNow = fc!!
                                for (f in fcNow.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                    val fnl = f.name.lowercase()
                                    if (fnl.contains("live") || fnl.contains("stream") || fnl.contains("living") || fnl.contains("play")) {
                                        try {
                                            f.isAccessible = true
                                            when {
                                                f.type == Boolean::class.javaPrimitiveType -> { if (f.getBoolean(r)) { f.setBoolean(r, false); zapped++ } }
                                                f.type == Int::class.javaPrimitiveType -> { if (f.getInt(r) != 0) { f.setInt(r, 0); zapped++ } }
                                                f.type == Long::class.javaPrimitiveType -> { if (f.getLong(r) != 0L) { f.setLong(r, 0L); zapped++ } }
                                                else -> { val v = f.get(r); if (v != null) { f.set(r, null); zapped++ } }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                fc = fcNow.superclass
                            }
                            if (zapped > 0 && CfhState.liveCtorDiag < 30) { CfhState.liveCtorDiag++; Logger.d("liveCtor zap zapped=$zapped") }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    // 源头之王：hook LiveStreamFeed 构造函数，构造时把所有直播标识字段置空/置 false，
    // 让快手判别为非直播，根本不启动直播渲染管线。这样直播卡变成空壳，信息流自动跳过。

    internal fun ensureLiveFeedConstructHooked(ent: Any) {
        val xp = CfhState.xpRef ?: return
        val c = ent.javaClass
        if (!CfhState.liveCtorHookedCls.add(CfhUtil.hookKey(c))) return
        Logger.d("hookLiveCtor late: ${c.name} ctors=${c.declaredConstructors.size}")
        for (ctor in c.declaredConstructors) {
            Logger.safe("hookLiveCtorLate.${ctor.parameterTypes.size}") {
                xp.hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("liveCtorLate.${c.name}.${ctor.parameterTypes.size}").intercept { chain ->
                    val r = chain.proceed()
                    try {
                        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhState.liveTop) {
                            if (CfhState.liveCtorDiag < 1) {
                                CfhState.liveCtorDiag++
                                var dfc: Class<*>? = r.javaClass
                                while (dfc != null && dfc != Any::class.java) {
                                    val dfcNow = dfc!!
                                    for (f in dfcNow.declaredFields) {
                                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                        try { f.isAccessible = true; Logger.d("  LSF fld: ${dfcNow.simpleName}.${f.name}:${f.type.simpleName}=${f.get(r)?.javaClass?.simpleName ?: "null"}") } catch (_: Throwable) {}
                                    }
                                    dfc = dfcNow.superclass
                                }
                            }
                            var zapped = 0
                            var fc: Class<*>? = r.javaClass
                            while (fc != null && fc != Any::class.java) {
                                val fcNow = fc!!
                                for (f in fcNow.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                    val fnl = f.name.lowercase()
                                    if (fnl.contains("live") || fnl.contains("stream") || fnl.contains("living") || fnl.contains("play")) {
                                        try {
                                            f.isAccessible = true
                                            when {
                                                f.type == Boolean::class.javaPrimitiveType -> { if (f.getBoolean(r)) { f.setBoolean(r, false); zapped++ } }
                                                f.type == Int::class.javaPrimitiveType -> { if (f.getInt(r) != 0) { f.setInt(r, 0); zapped++ } }
                                                f.type == Long::class.javaPrimitiveType -> { if (f.getLong(r) != 0L) { f.setLong(r, 0L); zapped++ } }
                                                else -> { val v = f.get(r); if (v != null) { f.set(r, null); zapped++ } }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                fc = fcNow.superclass
                            }
                            if (zapped > 0 && CfhState.liveCtorDiag < 40) { CfhState.liveCtorDiag++; Logger.d("liveCtor zap zapped=$zapped") }
                        }
                    } catch (_: Throwable) {}
                    r
                }
            }
        }
    }
















    // 影视/广告壳子字段对普通视频也是非 null 空壳 �?必须查内层真实内容才算命�?





    // dump holder 图 / dumpMilanoHolder / forceRebindCurrent / applyWindowClean /
    // findCleanPos / adapterMainList 已删除：grep 证实零调用死代码（R8 release 亦剥离）

    // �?vm 窗口取同位置富数�?qp（显示源实例，带完整 user/caption�?

    // 干净视频写进 vm 窗口同槽的 applyWindowClean 已删除：grep 证实零调用死代码

    // holder 图里�?Fragment 实例


    internal fun findDataSource(frag: Any) {
        Logger.safe("findDataSource") {
            val qc = CfhState.qpClassRef
            var vm: Any? = null
            var c: Class<*>? = frag.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(frag) ?: continue
                        if (v.javaClass.name.endsWith("SlidePlayViewModel")) {
                            Logger.d("NASA vm=${v.javaClass.name}")
                            vm = v
                            val ds = Reflect.callMethod(v, "getDataSource")
                            if (ds != null) {
                                Logger.d("NASA dataSource=${ds.javaClass.name}")
                                hookDataSource(ds.javaClass)
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            // 混淆兜底：按方法签名�?(int)->QPhoto �?ViewModel
            if (vm == null && qc != null) {
                c = frag.javaClass; lvl = 0
                outer@ while (c != null && c != Any::class.java && lvl < 4) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(frag) ?: continue
                            val vn = v.javaClass.name
                            if (vn.startsWith("android.") || vn.startsWith("java.") || vn.contains("Fragment")) continue
                            var n = 0
                            var mc: Class<*>? = v.javaClass
                            var ml = 0
                            while (mc != null && ml < 4) {
                                for (m in mc!!.declaredMethods) {
                                    if (m.returnType == qc && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) n++
                                }
                                mc = mc.superclass; ml++
                            }
                            if (n >= 2) {
                                vm = v
                                Logger.d("NASA vm fallback=${vn} sig=$n")
                                break@outer
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
            }
            if (vm != null) {
                hookViewModel(vm!!)
                val ds = Reflect.callMethod(vm!!, "getDataSource")
                if (ds != null) {
                    Logger.d("NASA dataSource=${ds.javaClass.name}")
                    hookDataSource(ds.javaClass)
                }
            }
        }
    }
}

