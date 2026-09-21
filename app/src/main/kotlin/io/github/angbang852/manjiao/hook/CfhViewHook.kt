package io.github.angbang852.manjiao.hook

import android.view.View
import android.view.ViewGroup
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第五步：渲染层钩（2026-09 S3）。
// pager/adapter/provider 渲染链路、rerank 直播重排、KRN 电商卡拦截。
object CfhViewHook {
    // 直播重排模块：com.kuaishou.live.rerank 在 VerticalViewPager 滚动时把
    // LiveStreamFeed 直接塞进首页信息流。它的类被混淆（e$b.onPageScrolled 回调 +
    // d.t / e$d.E 内部方法），但数据一定以 List / 单项实体的形式跨方法。
    // 策略：按「回调签名」hook onPageScrolled，并扫描 rerank 包的 List 返回方法过滤。

    // ★ 去重键含 classloader 身份：快手插件化会把同名类装进第二个 loader，
    // 按类名去重会让新 Class 被误判「已 hook」而静默漏装（fragSeqHookedClasses
    // :1592 早已用此写法，此处统一）


    internal fun hookLiveRerank(xp: XposedInterface, cl: ClassLoader) {
        val pkg = "com.kuaishou.live.rerank"
        val tryNames = listOf("e", "e\$b", "d", "e\$d", "c", "b")
        var hookedAny = false
        for (tn in tryNames) {
            val cn = "$pkg.$tn"
            val cc = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
            if (!CfhState.hookedRerankCls.add(CfhUtil.hookKey(cc))) continue
            hookedAny = true
            Logger.d("rerank cls: $cn methodCount=${cc.declaredMethods.size}")
            for (m in cc.declaredMethods) {
                val nm = m.name
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection") }
                if (!hasListParam && (m.returnType.isPrimitive || m.returnType == Void.TYPE || m.returnType == java.lang.String::class.java)) continue
                if (nm.startsWith("getCurrent") || nm == "getPhoto" || nm == "getItem") continue
                Logger.safe("rerank.${cn}.${nm}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.${cn}.${nm}").intercept { chain ->
                        if (Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                            try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                        }
                        val r = chain.proceed()
                        try {
                            if (r is List<*>) {
                                val before = r.size
                                CfhClean.filterResult(r)
                                if (r.size != before && CfhState.rerankListDiag < 20) {
                                    CfhState.rerankListDiag++
                                    Logger.d("rerank list ${nm} filtered: $before -> ${r.size}")
                                }
                            } else if (r != null) {
                                val q = CfhProbe.findQpInObject(r)
                                if (q != null && CfhDecide.shouldFilterFeed(q)) {
                                    if (CfhState.rerankSingleDiag < 20) {
                                        CfhState.rerankSingleDiag++
                                        Logger.d("rerank single ${nm}: ${CfhUtil.readCaption(q)?.take(20)}")
                                    }
                                    return@intercept null
                                }
                            }
                        } catch (_: Throwable) {}
                        r
                    }
                }
            }
        }
        for (tn in listOf("e\$b")) {
            val cn = "$pkg.$tn"
            val cc = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
            for (m in cc.declaredMethods) {
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    Logger.safe("rerank.sel.${cn}.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.sel.${cn}.${m.name}").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                val pos = chain.args.getOrNull(0) as? Int ?: -1
                                if (CfhState.rerankScrollDiag < 10) { CfhState.rerankScrollDiag++; Logger.d("rerank selected #$pos") }
                                if (!Logger.quiet) try { CfhWatch.laFind() } catch (_: Throwable) {}
                            } catch (_: Throwable) {}
                            r
                        }
                    }
                } else if (m.parameterTypes.size == 3 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    Logger.safe("rerank.scroll.${cn}.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.scroll.${cn}.${m.name}").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                if (CfhState.rerankScrollDiag < 10) { CfhState.rerankScrollDiag++; val pos = chain.args.getOrNull(0) as? Int ?: -1; Logger.d("rerank scroll #$pos") }
                                if (!Logger.quiet) try { CfhWatch.laFind() } catch (_: Throwable) {}
                            } catch (_: Throwable) {}
                            r
                        }
                    }
                }
            }
        }
        try {
            val jd = Class.forName("com.kuaishou.live.rerank.d", false, cl)
            for (m in jd.declaredMethods) {
                if (m.name == "j" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType && m.returnType == java.lang.Boolean.TYPE) {
                    Logger.safe("rerank.d.j") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.d.j").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                if (r == true && !CfhState.liveTop) {
                                    val iN = chain.args.getOrNull(0) as? Int ?: -1
                                    if (CfhState.rerankJDiag < 20) { CfhState.rerankJDiag++; Logger.always("RERANKJ hit iN=" + iN + " (live 2 ahead) -> force laFind") }
                                    if (!Logger.quiet && CfhState.rerankJDiag < 20) {
                                        try {
                                            val dumpAdp = CfhState.adpRef ?: CfhState.adpRefs.firstOrNull()
                                            if (dumpAdp != null) {
                                                var dc: Class<*>? = dumpAdp.javaClass
                                                var dlvl = 0
                                                while (dc != null && dc != Any::class.java && dlvl < 3) {
                                                    for (df in dc!!.declaredFields) {
                                                        if (java.lang.reflect.Modifier.isStatic(df.modifiers)) continue
                                                        try {
                                                            df.isAccessible = true
                                                            val dv = df.get(dumpAdp) ?: continue
                                                            if (dv is List<*>) {
                                                                for ((didx, del) in dv.withIndex()) {
                                                                    if (didx > 6) break
                                                                    val dqp = del ?: continue
                                                                    val dent = Reflect.readAny(dqp, "mEntity") ?: dqp
                                                                    val dpm = Reflect.readAny(dent, "mPhotoMeta")
                                                                    val dlm = Reflect.readAny(dent, "mLivePlaybackMeta")
                                                                    val dcm = Reflect.readAny(dent, "mCommonMeta")
                                                                    val dcap = (dcm?.let { Reflect.readString(it, "mCaption") } ?: "").take(30)
                                                                    val dun = CfhUtil.readUserName(dqp, dent).take(20)
                                                                    Logger.always("RJDUMP adp[" + didx + "] ent=" + dent.javaClass.simpleName + " lm=" + (dlm != null) + " liveSid=" + (dlm?.let { Reflect.readAny(it, "mLiveStreamId") != null }) + " state=" + (dpm?.let { Reflect.readBool(it, "mCurrentLivingState") }) + " useLive=" + (dpm?.let { Reflect.readBool(it, "mUseLive") }) + " merch=" + (dpm?.let { Reflect.readAny(it, "mMerchantLiveInfo") != null }) + " btn=" + (dpm?.let { Reflect.readAny(it, "mInteractionLiveCardButton") != null }) + " clip=" + (dpm?.let { Reflect.readAny(it, "mLiveStreamClipInfo") != null }) + " cap=" + dcap + " user=" + dun)
                                                                }
                                                                break
                                                            }
                                                        } catch (_: Throwable) {}
                                                    }
                                                    dc = dc.superclass; dlvl++
                                                }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                    try { CfhWatch.laFind(true) } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                            r
                        }
                    }
                    Logger.d("hooked rerank.d.j(int)")
                }
            }
        } catch (_: Throwable) {}
        if (hookedAny) Logger.d("hookLiveRerank done pkg=$pkg")
    }

    // ★ 直播带货预览卡（RN CombinedCard）取证：hook KRN 容器 KrnFragment 生命周期，
    // 打印参数(bundleId等) + 创建调用栈，反查 feed 里谁在创建它，找到 Java 层数据源头。

    internal fun hookKrnProbe(xp: XposedInterface, cl: ClassLoader) {
        if (CfhState.krnProbeHooked) return
        CfhState.krnProbeHooked = true
        // ★ 纯探针（只打日志不改变行为）：静默模式（默认）不安装，省掉 KrnFragment
        // 5 个生命周期 hook 的常驻开销；排查 KRN 问题时把 日志静默 关掉重启即恢复
        if (Logger.quiet) return
        val probeCl = cl
        val retry = object : Runnable {
            override fun run() {
                var done = false
                for (cn in listOf("com.kuaishou.krn.page.KrnFragment", "KrnFragment")) {
                    val c = Reflect.findClass(cn, probeCl) ?: continue
                    var hooked = 0
                    for (mn in listOf("onViewCreated", "onCreateView", "onAttach", "setArguments", "onResume")) {
                        val m = c.declaredMethods.firstOrNull { it.name == mn } ?: continue
                        try {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnProbe.$cn.$mn").intercept { chain ->
                                try {
                                    if (CfhState.krnProbeCount < 6) {
                                        CfhState.krnProbeCount++
                                        val args = chain.args.joinToString(",") { a ->
                                            when (a) {
                                                null -> "null"
                                                is android.os.Bundle -> {
                                                    val sb = StringBuilder()
                                                    try {
                                                        for (key in a.keySet()) {
                                                            val v = a.get(key)
                                                            sb.append("$key=${if (v is String) v.take(80) else v?.javaClass?.simpleName ?: "null"}; ")
                                                        }
                                                    } catch (_: Throwable) {}
                                                    "Bundle[$sb]"
                                                }
                                                else -> "${a.javaClass.simpleName}"
                                            }
                                        }
                                        Logger.always("KRNPROBE $mn #${CfhState.krnProbeCount} args=[$args]\n" +
                                            Thread.currentThread().stackTrace.drop(1).take(22).joinToString("\n"))
                                    }
                                } catch (_: Throwable) {}
                                chain.proceed()
                            }
                            hooked++
                        } catch (t: Throwable) {
                            Logger.always("krnProbe hook $mn exc: ${t.message}")
                        }
                    }
                    Logger.always("krnProbe hooked $cn methods=$hooked")
                    if (hooked > 0) { done = true; break }
                }
                if (!done && CfhState.krnProbeRetries < 40) {
                    CfhState.krnProbeRetries++
                    if (CfhState.krnProbeRetries == 1 || CfhState.krnProbeRetries % 10 == 0) Logger.always("krnProbe retry #${CfhState.krnProbeRetries}: KrnFragment not loaded yet")
                    CfhState.handler.postDelayed(this, 2000)
                } else if (!done) {
                    Logger.always("krnProbe GIVE UP after ${CfhState.krnProbeRetries} retries: KrnFragment never loaded")
                }
            }
        }
        CfhState.handler.postDelayed(retry, 2000)
    }

    // ★ KRN 电商/直播带货卡渲染源头拦截（模拟器已验证判定锚点）：
    // KrnReactContainerView.getLaunchModel 返回的 LaunchModel.f Bundle 含
    // bundleId=Kwaishop*（实证 KwaishopRNCPrecisionMarketing/KwaishopCLivePreviewCommodityCard
    // 同族）。命中直播带货 bundle 时清空该 Bundle 键——卡片拿不到数据即不渲染
    // （删除式拦截，非替换）。KRNLM 日志保留限次审计。


    internal fun hookKrnReactContainerView(xp: XposedInterface, cl: ClassLoader) {
        if (CfhState.krnRcvHooked) return
        CfhState.krnRcvHooked = true
        val probeCl = cl
        val retry = object : Runnable {
            override fun run() {
                val c = Reflect.findClass("com.kuaishou.krn.page.KrnReactContainerView", probeCl)
                if (c == null) {
                    if (CfhState.krnRcvRetries < 40) {
                        CfhState.krnRcvRetries++
                        if (CfhState.krnRcvRetries == 1 || CfhState.krnRcvRetries % 10 == 0) Logger.always("krnRcv retry #${CfhState.krnRcvRetries}")
                        CfhState.handler.postDelayed(this, 2000)
                    } else Logger.always("krnRcv GIVE UP")
                    return
                }
                var hooked = 0
                val gm = c.declaredMethods.firstOrNull { it.name == "getLaunchModel" }
                if (gm != null) {
                    try {
                        xp.hook(gm).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnRcv.getLaunchModel").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                if (r != null) {
                                    var fBundle: android.os.Bundle? = null
                                    var fc: Class<*>? = r.javaClass
                                    var lvl = 0
                                    while (fc != null && fc != Any::class.java && lvl < 3 && fBundle == null) {
                                        for (f in fc!!.declaredFields) {
                                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                            if (f.type == android.os.Bundle::class.java) {
                                                try { f.isAccessible = true; fBundle = f.get(r) as? android.os.Bundle } catch (_: Throwable) {}
                                                if (fBundle != null) break
                                            }
                                        }
                                        fc = fc.superclass; lvl++
                                    }
                                    val bid = fBundle?.get("bundleId") as? String
                                    val isShop = bid != null && bid.startsWith("Kwaishop")
                                    if (isShop && Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                                        val keys = fBundle!!.keySet().toList()
                                        for (k in keys) fBundle!!.remove(k)
                                        Logger.always("KRNZAP #${CfhState.krnLmDiag} bundleId=$bid keysCleared=${keys.size}")
                                        CfhState.krnLmDiag++
                                    } else if (CfhState.krnLmDiag < 3 && bid != null) {
                                        Logger.always("KRNLM pass bundleId=$bid")
                                        CfhState.krnLmDiag++
                                    }
                                }
                            } catch (_: Throwable) {}
                            r
                        }
                        hooked++
                    } catch (t: Throwable) { Logger.always("krnRcv gm hook exc: ${t.message}") }
                }
                // ★ 第二条渲染路径兜底：KrnReactRootView.setBundleId 是所有 KRN root
                // view 注入 bundle 的统一入口（CombinedCard 平铺路径不走 getLaunchModel）。
                // 命中 Kwaishop* 且直播开关开 → 清 ReactStyleProps 阻断渲染（删除式）。
                val rv = Reflect.findClass("com.kuaishou.krn.widget.react.KrnReactRootView", probeCl)
                if (rv != null) {
                    val sb = rv.declaredMethods.firstOrNull { it.name == "setBundleId" }
                    val gp = rv.declaredMethods.firstOrNull { it.name == "getReactStyleProps" }
                    if (sb != null) {
                        try {
                            xp.hook(sb).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnRv.setBundleId").intercept { chain ->
                                val bid = chain.args.firstOrNull() as? String
                                if (bid != null && bid.startsWith("Kwaishop") && Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                                    Logger.always("KRNZAP-ROOT bundleId=$bid")
                                    try {
                                        // attach 时 getBundleId 仍为 null（实证 bid=null），
                                        // setBundleId 是 bundleId 首次可读时机，此处置 GONE
                                        // 保留到上屏（显示层删除式拦截）
                                        (chain.thisObject as? android.view.View)?.visibility = android.view.View.GONE
                                        Logger.always("KRNZAP-GONE bundleId=$bid")
                                    } catch (_: Throwable) {}
                                    try {
                                        val root = chain.thisObject
                                        if (gp != null) {
                                            val style = gp.invoke(root)
                                            if (style != null) {
                                                var sf: Class<*>? = style.javaClass
                                                while (sf != null && sf != Any::class.java) {
                                                    for (f in sf!!.declaredFields) {
                                                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                                        try {
                                                            f.isAccessible = true
                                                            if (!f.type.isPrimitive) f.set(style, null)
                                                        } catch (_: Throwable) {}
                                                    }
                                                    sf = sf.superclass
                                                }
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }
                                chain.proceed()
                            }
                        } catch (t: Throwable) { Logger.always("krnRv sb hook exc: ${t.message}") }
                    }
                }
                // ★ 显示层拦截（终防线）：attach 时 bundleId 已注入（setBundleId
                // 先于 attach 实证），双保险。命中 Kwaishop* → GONE。
                val rv2 = Reflect.findClass("com.kuaishou.krn.widget.react.KrnReactRootView", probeCl)
                if (rv2 != null) {
                    val att = rv2.declaredMethods.firstOrNull { it.name == "onAttachedToWindow" }
                    val gb = rv2.declaredMethods.firstOrNull { it.name == "getBundleId" }
                    if (att != null && gb != null) {
                        try {
                            xp.hook(att).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnRv.attach").intercept { chain ->
                                val proceedResult = chain.proceed()
                                try {
                                    val root = chain.thisObject
                                    val bid = gb.invoke(root) as? String
                                    if (bid != null && bid.startsWith("Kwaishop") && Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                                        (root as? android.view.View)?.visibility = android.view.View.GONE
                                        Logger.always("KRNZAP-GONE-ATT bundleId=$bid")
                                    }
                                } catch (_: Throwable) {}
                                proceedResult
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        // ★ 补接线：retry Runnable 构造后从未被调度（postDelayed 只存在于
        // hookKrnProbe），整条 KRN 拦截链路实际从未安装
        CfhState.handler.postDelayed(retry, 2000)
    }





    internal fun findPager(v: View) {
        val n = v.javaClass.name
        val isPager = n.contains("CustomAnimationViewPager") || n.contains("ScrollStrategyViewPager") ||
            n.contains("VerticalViewPager") || n.contains("LiveSlideViewPager") || n.contains("LiveSafeViewPager") ||
            (v.id != View.NO_ID && try { v.resources.getResourceEntryName(v.id) == "nasa_groot_view_pager" } catch (_: Throwable) { false }) ||
            (v.id != View.NO_ID && try { v.resources.getResourceEntryName(v.id) == "milano_container_layout" } catch (_: Throwable) { false })
        if (isPager) {
            // ★ 陈旧引用失效（审阅 2026-09）：Activity 重建后旧 pager 已 detach，但
            // pagerCache 此前只在 null 时更新——旧 Activity 视图链被静态强引用至进程
            // 结束，且 check/laFind 持续对死对象做功。缓存 detached 时允许覆盖刷新
            val stale = (CfhState.pagerCache as? View)?.isAttachedToWindow == false
            if (CfhState.pagerCache == null || stale) CfhState.pagerCache = v
            try { hookPagerClass(v.javaClass) } catch (_: Throwable) {}
            val adp = try { Reflect.callMethod(v, "getAdapter") } catch (_: Throwable) { null }
            if (adp == null) {
                if (CfhState.feedPagerFound && !stale) return
                Logger.safe("feedPagerNoAdp") { Logger.d("feedPager found but no adapter yet: ${v.javaClass.name}") }
                return
            }
            val isFirst = !CfhState.feedPagerFound || stale
            CfhState.feedPagerFound = true
            if (isFirst) {
                CfhState.adpRef = adp
                CfhState.pagerCache = v
            }
            if (CfhState.feedPagerLogCount < 20) {
                CfhState.feedPagerLogCount++
                Logger.safe("feedPagerLog") { Logger.d("feedPager: ${v.javaClass.name} adp=${adp.javaClass.name} first=$isFirst") }
            }
            try {
                if (CfhState.adpRefs.none { it === adp }) {
                    // ★ 上限防泄漏（审阅 2026-09）：adpRefs 原先只增不减，每次 Activity
                    // 重建新增一个 adapter 强引用。超 6 个先清空再留当前代际（laFind 只
                    // 把这里当候选根集，旧代际无价值）
                    if (CfhState.adpRefs.size >= 6) CfhState.adpRefs.clear()
                    CfhState.adpRefs.add(adp)
                }
            } catch (_: Throwable) {}
            if (adp.javaClass.name.startsWith("l3c")) Logger.d("feedPager DETAIL-adp: ${adp.javaClass.name} pager=${v.javaClass.simpleName}")
            // ★ 从 adapter 反向找 VM（fragSeq aq 未调用时的替代路径）：
            // 扫 adapter 字段找 SlidePlayViewModel，设 vmRef + hookViewModel + filterVmLists
            if (CfhState.vmRef == null) {
                if (CfhState.adpDumpCount < 3) { CfhState.adpDumpCount++; val sb = StringBuilder("ADPDUMP ${adp.javaClass.name}:"); var dc: Class<*>? = adp.javaClass; var dl = 0; while (dc != null && dc != Any::class.java && dl < 4) { for (df in dc!!.declaredFields) { if (java.lang.reflect.Modifier.isStatic(df.modifiers)) continue; try { df.isAccessible = true; val dv = df.get(adp); sb.append(" ${df.name}=${dv?.javaClass?.simpleName ?: "null"}") } catch (_: Throwable) {} }; dc = dc.superclass; dl++ }; Logger.d(sb.toString()) }
                var c2: Class<*>? = adp.javaClass
                var lvl2 = 0
                while (c2 != null && c2 != Any::class.java && lvl2 < 4) {
                    for (f2 in c2!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f2.modifiers)) continue
                        try {
                            f2.isAccessible = true
                            val fv = f2.get(adp) ?: continue
                            if (fv.javaClass.name.contains("SlidePlay") || fv.javaClass.name.contains("ViewModel")) {
                                CfhState.vmRef = fv
                                Logger.always("vmFromAdp: ${fv.javaClass.name} via ${f2.name}")
                                try { CfhFeedHook.hookViewModel(fv) } catch (_: Throwable) {}
                                try { CfhWash.filterVmLists(fv) } catch (_: Throwable) {}
                                break
                            }
                        } catch (_: Throwable) {}
                    }
                    if (CfhState.vmRef != null) break
                    c2 = c2.superclass; lvl2++
                }
            }

            // ★ 持续清洗：vmFromAdp 首次设 vmRef 后 filterVmLists 只调了一次（此时 i 可能空）。
            // 后续 feed 数据加载后 i 被填充，但 fragSeq aq 不调用 → filterVmLists 不再触发。
            // findPager 每 ~3s 由 check() 触发，此处补调 filterVmLists（500ms 节流自防过度）
        if (CfhState.vmRef != null) { try { CfhWash.filterVmLists(CfhState.vmRef!!) } catch (_: Throwable) {} }
        // LAFIND：脏元素身份反查真源字段（诊断用）
        if (!Logger.quiet) try { CfhWatch.laFind() } catch (_: Throwable) {}

            try { hookPagerAdapter(adp.javaClass) } catch (t: Throwable) { Logger.always("hookPagerAdapter exc: ${t.message}") }
            if (isFirst) {
                for (provName in listOf("G", "H", "getProvider")) {
                    val provider = try { Reflect.callMethod(adp, provName) } catch (_: Throwable) { null }
                    if (provider != null) {
                        Logger.safe("feedPagerProv") { Logger.d("feedPager provider via $provName: ${provider.javaClass.name}") }
                        hookDataProvider(provider.javaClass)
                        break
                    }
                }
            }
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { findPager(it) }
            }
        }
    }
    private fun hookPagerClass(startCls: Class<*>) {
        try {
            val xp = CfhState.xpRef ?: return
            var cc: Class<*>? = startCls
            var lvl = 0
            while (cc != null && cc != Any::class.java && lvl < 6) {
                val clsNow = cc
                if (!CfhState.hookedPagerCls.add(CfhUtil.hookKey(clsNow))) { cc = cc.superclass; lvl++; continue }
                for (m in clsNow.declaredMethods) {
                    val nm = m.name
                    if (!m.returnType.isPrimitive && m.returnType != Void.TYPE &&
                        (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType ||
                         m.parameterTypes.size == 2 && m.parameterTypes[1] == Int::class.javaPrimitiveType)) {
                        Logger.safe("hookPager.${clsNow.simpleName}.${nm}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("pager.${clsNow.name}.${nm}").intercept { chain ->
                                val result = chain.proceed()
                                try {
                                    val pos = chain.args.lastOrNull() as? Int ?: -1
                                    if (result != null) {
                                        val qp = CfhProbe.findQpInObject(result)
                                        val hit = if (qp != null) CfhDecide.shouldFilterFeed(qp) else false
                                        if (CfhState.pagerDiag < 25) {
                                            CfhState.pagerDiag++
                                            Logger.d("pager i ${nm}(#$pos) -> ${result.javaClass.simpleName} hit=$hit qp=${qp != null}")
                                        }
                                        if (qp != null && hit) {
                                            val clean = CfhSwap.pickFromQueue()
                                            if (clean != null) {
                                                val sw = CfhSwap.writeQpInto(result, clean)
                                                CfhState.pagerSwapCount++
                                                Logger.d("pager swap ${nm}(#$pos) sw=$sw ${CfhUtil.readCaption(qp)?.take(15)}")
                                            }
                                        } else if (qp != null && !hit && CfhState.liveWindowDiag < 8) {
                                            // ★ 视频卡是否带"直播浮窗/进入直播间引导"：找 QP 树里的 live 状态字段
                                            val liveInfo = CfhProbe.findLiveWindowField(qp)
                                            if (liveInfo != null) {
                                                CfhState.liveWindowDiag++
                                                Logger.d("LIVEWIN ${nm}(#$pos) $liveInfo cap=${CfhUtil.readCaption(qp)?.take(16)}")
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {}
                                result
                            }
                        }
                    }
                }
                cc = cc.superclass; lvl++
            }
        } catch (_: Throwable) {}
    }
    private fun hookDataProvider(c: Class<*>) {
        val xp = CfhState.xpRef ?: return
        synchronized(CfhState.hookedProvClasses) { if (!CfhState.hookedProvClasses.add(c.name)) return }
        Logger.d("hookDataProvider: ${c.name}")
        for (m in c.declaredMethods) {
            Logger.d("  prov m: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
            val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
            if (isRetList) {
                Logger.safe("hookProvList.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("provL.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (!CfhState.provDiag && result is MutableList<*> && result.isNotEmpty()) {
                                CfhState.provDiag = true
                                val elem = result[0]
                                Logger.d("provList diag: size=${result.size} elemCls=${elem?.javaClass?.name}")
                                val qp = elem?.let { CfhProbe.findQpInObject(it) }
                                Logger.d("provList qp: ${qp != null}")
                            }
                            if (result is MutableList<*>) {
                                val hits = result.filter { it != null && (try { CfhDecide.shouldFilterFeed(it) } catch (_: Throwable) { false }) }
                                if (hits.isEmpty()) {
                                    val wrapHits = result.filter { it != null && CfhProbe.findQpInObject(it)?.let { qp -> CfhDecide.shouldFilterFeed(qp) } == true }
                                    if (wrapHits.isNotEmpty()) {
                                        @Suppress("UNCHECKED_CAST")
                                        (result as MutableList<Any?>).removeAll(wrapHits)
                                        Logger.d("provWrap filtered ${wrapHits.size} via ${m.name}")
                                    }
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    (result as MutableList<Any?>).removeAll(hits)
                                    Logger.d("prov filtered ${hits.size} via ${m.name}")
                                }
                            }
                        } catch (_: Throwable) {}
                        result
                    }
                }
            }
            if (m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == Int::class.javaPrimitiveType && !m.returnType.isPrimitive && m.returnType != Void.TYPE) {
                Logger.safe("hookProvGet.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("provG.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (result != null) {
                                val qp = CfhProbe.findQpInObject(result)
                                if (qp != null && CfhDecide.shouldFilterFeed(qp)) {
                                    Logger.d("provGet blocked pos=${chain.args[0]} ${CfhUtil.readCaption(qp)?.take(25)}")
                                }
                            }
                        } catch (_: Throwable) {}
                        result
                    }
                }
            }
        }
    }
    private fun hookPagerAdapter(c: Class<*>) {
        val xp = CfhState.xpRef ?: run { Logger.always("hookPagerAdapter skip: xpRef null"); return }
        val added = synchronized(CfhState.hookedAdpClasses) { CfhState.hookedAdpClasses.add(CfhUtil.hookKey(c)) }
        if (!added) { Logger.d("hookPagerAdapter dup: ${c.name}"); return }
        Logger.d("hookPagerAdapter: ${c.name}")
        // 诊断：dump 类层次全部方法，找数据供给方法
        var dcls: Class<*>? = c
        var dlvl = 0
        while (dcls != null && dcls != Any::class.java && dlvl < 5) {
            val dcn = dcls!!
            for (m in dcn.declaredMethods) {
                val pDesc = m.parameterTypes.joinToString(",") { it.simpleName }
                if (m.parameterTypes.size <= 3) {
                    Logger.d("  adpM[${dcn.simpleName}]: ${m.name}($pDesc) -> ${m.returnType.simpleName}")
                }
            }
            dcls = dcn.superclass; dlvl++
        }
        // 决定性：hook D(int)/p(ViewGroup,int) dump 返回结构 + 所有 List 写入方法源头过滤
        var qcls: Class<*>? = c
        var qlvl = 0
        while (qcls != null && qcls != Any::class.java && qlvl < 5) {
            val qcn = qcls!!
            for (m in qcn.declaredMethods) {
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                val isDMethod = m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.name.length <= 2
                val isInst = m.parameterTypes.size == 2 && m.parameterTypes[0].name.contains("ViewGroup") &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType && !m.returnType.isPrimitive && m.returnType != Void.TYPE
                if (hasListParam || isDMethod || isInst) installAdpX(xp, qcn, m)
            }
            qcls = qcn.superclass; qlvl++
        }
        var cls: Class<*>? = c
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 4) {
            for (m in cls!!.declaredMethods) {
                // ★ installAdpF 派发已移除（2026-09-21）：消费端参数替换，非数据源拦截且从未生效
                // adapter 的 set/add/addAll(List) 方法：直播从这塞进信息流，在参数阶段就剔掉
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection") }
                if (hasListParam && m.declaringClass == cls) installAdpList(xp, c, m)
                if (m.parameterTypes.size <= 3 && m.declaringClass == cls) {
                    val p1 = m.parameterTypes.firstOrNull()
                    val isIntP = p1 == Int::class.javaPrimitiveType
                    val nonPrimRet = !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != java.lang.String::class.java
                    val retFrag = m.returnType.name.contains("Fragment")
                    val retList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    // 供给方法：单 int 参返回对象（getItem/D）；(ViewGroup,int) 返回 View/Fragment（instantiateItem）；
                    // 或返回 Fragment/List 的任意短参方法
                    val isSupply = (m.parameterTypes.size == 1 && isIntP && nonPrimRet) ||
                        (m.parameterTypes.size == 2 && m.parameterTypes[0].name.contains("ViewGroup") && m.parameterTypes[1] == Int::class.javaPrimitiveType && (retFrag || nonPrimRet)) ||
                        (retFrag && m.parameterTypes.size <= 2) ||
                        (retList && m.parameterTypes.size <= 2 && isIntP)
                    if (isSupply) installAdpGet(xp, c, m)
                    else if (m.parameterTypes.isEmpty() && nonPrimRet) installAdpProv(xp, c, m)
                    else if (m.parameterTypes.size >= 1 && m.parameterTypes.any { it.name.contains("QPhoto") }) installAdpQp(xp, c, m)
                }
            }
            cls = cls.superclass; lvl++
        }
    }
    private fun installAdpX(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.d("  hookAdpX[${c.simpleName}]: ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName}")
        Logger.safe("hookAdpX.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpX.${c.name}.${m.name}").intercept { chain ->
                try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                // ★ 死代码移除（2026-09-21）：此处原有「脏页 p() 位置重定向」——
                // 探测 p(ViewGroup,int) 的脏页，把参数改成邻近干净页的位置
                // （chain.args[1] = np + triggerRefresh）。经 libxposed API 核对：
                // Chain.getArgs() 返回**只读 List**，List.set() 必抛
                // UnsupportedOperationException 并被本块 catch 吞掉 ——
                // 该功能自写下起从未生效（实测 adpX p REDIRECT 日志恒为 0，
                // 见 2026-09-21 抓包）。既从未生效、又需新增状态与刷新副作用，
                // 故整体删除而非改造；若日后要恢复"脏页换邻页"，必须用
                // chain.proceed(newArgs) 携带新参，并同步评估位置↔内容错配
                // 对视口比例的影响（会引发上下压缩畸变）。
                val r = chain.proceed()

                try {
                    val pos = chain.args.lastOrNull() as? Int ?: -1
                    val isPD = m.name == "p" || m.name == "D"
                    val shouldDump = if (isPD) CfhState.adpXDump < 40 && CfhState.adpXDumped.add("pd_" + pos) else CfhState.adpXDumped.add(c.name + "." + m.name) && CfhState.adpXDump < 25
                    if (shouldDump) {
                        CfhState.adpXDump++
                        Logger.d("adpX ${m.name} #$pos ret=${r?.javaClass?.name ?: "null"}")
                        if (r != null && isPD) {
                            var rc: Class<*>? = r.javaClass
                            var rl = 0
                            while (rc != null && rc != Any::class.java && rl < 3) {
                                for (rf in rc!!.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(rf.modifiers)) continue
                                    try { rf.isAccessible = true; val rv = rf.get(r); Logger.d("  adpXfld ${rf.name}:${rf.type.simpleName}=${rv?.javaClass?.name ?: "null"}") } catch (_: Throwable) {}
                                }
                                rc = rc.superclass; rl++
                            }
                        }
                    }
                } catch (_: Throwable) {}

                r
            }
        }
    }

    // ★ installAdpF 整体移除（2026-09-21）：该函数 hook「返回 Fragment 的 (Any) 方法」，
    // 把 pager 请求的脏 QPhoto 参数替换成 vm.T0()/U0() 里的干净项。
    // 删除理由（架构 + 事实双重）：
    //   1) 架构：本模块路线是**数据源拦截**（在 VM/列表/真源层删掉脏项 → 脏内容"刷不到"）。
    //      在 pager 的参数上事后换对象属**消费端补丁**——不是那条路线，也拦不住：
    //      Fragment 参数只在这一次调用里生效，对象与视图随后被重建/重读。
    //   2) 事实：其核心动作 `chain.args[0] = qp` 是 libxposed 只读 List 误用
    //      （Chain.getArgs() 返回只读 List，List.set() 必抛，被 catch 吞掉）⇒ 替换
    //      从未发生，函数只剩下一条 "adp F blocked" 日志与 vm.T0/U0 空转探测。
    // 若日后要在视图层换数据，必须用 chain.proceed(newArgs)，并先证明它能"粘住"。

    private fun installAdpList(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
// adapter 的 set/add/addAll(List) 方法：直播从这塞进信息流，在参数阶段就剔掉
        Logger.safe("hookAdpList.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpList.${c.name}.${m.name}").intercept { chain ->
                try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                chain.proceed()
            }
        }
    }

    private fun installAdpGet(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookAdpGet.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpGet.${c.name}.${m.name}").intercept { chain ->
                if (CfhState.adpRef == null) CfhState.adpRef = chain.thisObject
                val result = chain.proceed()
                try {
                    if (result != null && !CfhState.adpGetSwapIn) {
                        val qp = CfhProbe.findQpInObject(result)
                        if (qp != null) CfhCapture.captureFeedItem(qp)
                        // ★ 位置↔条目权威映射（下载捕获 2026-09）：D(pos) 返回什么，
                        // 适配器自己最清楚——记录 pos→QPhoto，下载时用 ViewPager 的
                        // mCurrentItem 查表（迭代 6 版的可见性推断全部淘汰）
                        try {
                            val dpos = (chain.args.getOrNull(0) as? Int) ?: -1
                            if (dpos >= 0) {
                                val store = qp ?: CfhCapture.scanFragmentPhoto(result)
                                if (store != null) synchronized(CfhState.posPhotoMap) {
                                    CfhState.posPhotoMap.remove(dpos)
                                    CfhState.posPhotoMap[dpos] = java.lang.ref.WeakReference(store)
                                    while (CfhState.posPhotoMap.size > 16) {
                                        val first = CfhState.posPhotoMap.keys.firstOrNull() ?: break
                                        CfhState.posPhotoMap.remove(first)
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        val pos = (chain.args.getOrNull(0) as? Int) ?: -1
                        var clsQp = qp
                        // 空壳实例兜底：用同位�?vm 窗口的富 qp 分类（显示源=qm 有完整数据）
                        if (clsQp == null || CfhUtil.readUserName(clsQp, Reflect.readAny(clsQp, "mEntity") ?: clsQp).isEmpty()) {
                            clsQp = CfhCapture.findWindowQp(pos) ?: clsQp
                        }
                        // ★ QPhoto 提不到时用 holder 的 Fragment 类型判定（g3c.a 的 b 字段即页面 Fragment）：
                        // 直播 holder 的 Fragment 类名含 Live
                        val holderLive = CfhProbe.findFragInHolder(result)?.javaClass?.name?.let { fn -> fn.contains("Live") || fn.contains("Ad") } == true
                        // ★★ 再 BFS 全图找任何 Live/Ad 实体（直播卡可能渲染在 NasaPhotoDetailFragment 里，
                        // Fragment 类名不含 Live，QPhoto 也提不到，只能全图找实体类名）
                        val holderDirtyEnt = if (!holderLive) CfhProbe.findDirtyEntityInHolder(result) else null
                        // ★★★ 换页机制整体拆除（真机三次实证 01:18/22:34 闪退）：KMP groot
                        // 框架按 fragment 创建时的位置登记 KmpSlideContext/依赖字段（如
                        // PhotoDetailLogger），返回相邻位 fragment 顶包 = 框架状态错配，
                        // 无论强弱信号都会在 onCreatedView/onActivityCreated 空指针闪退。
                        // 脏页改为「先渲染、后台毫秒级清洗摘除」：幸存者入池 +
                        // fixAdapterSelfAlways + filterVmLists/laFind + fragSeq Vp 拦绑定
                        // 兜底——稳定性优先，代价是脏卡上屏后零点几秒内消失
                        if ((clsQp != null && CfhDecide.shouldFilterFeed(clsQp)) || holderLive || holderDirtyEnt != null) {
                            if (CfhState.adpGetLiveSkipDiag < 40) {
                                CfhState.adpGetLiveSkipDiag++
                                Logger.always("adpGet dirty #$pos defer-clean qp=${clsQp != null && CfhDecide.shouldFilterFeed(clsQp)} live=$holderLive ent=${holderDirtyEnt != null}")
                            }
                        }
                        // 干净项入池：D 每取一个位置，普通视频就是池子的食粮
                        if (qp != null && !CfhDecide.shouldFilterFeed(qp)) {
                            try { CfhSwap.offerClean(qp) } catch (_: Throwable) {}
                        }
                        // ★★ 启动窗位置探针（2026-09 用户报「第二条 90后零食」类漏网）：
                        // D(pos) 是 pager 的权威供给口，此处按位置打印「这一页到底是什么 +
                        // 判没判脏 + 关键判据字段」，用于定位「第 N 条为何漏网」。
                        // 冷启 20s 内、前 12 次供给全打（always，不受 diag 开关影响）。
                        try {
                            if (CfhState.processStartAt > 0L && System.currentTimeMillis() - CfhState.processStartAt < 20_000L && CfhState.adpPosProbe < 12) {
                                CfhState.adpPosProbe++
                                val p0 = (chain.args.getOrNull(0) as? Int) ?: -1
                                val q1 = qp ?: clsQp
                                val e1 = q1?.let { Reflect.readAny(it, "mEntity") }
                                val pm1 = e1?.let { Reflect.readAny(it, "mPhotoMeta") } ?: q1?.let { Reflect.readAny(it, "mPhotoMeta") }
                                val dis1 = pm1?.let { try { Reflect.readAny(it, "mDisclaimergeMessageV2") } catch (_: Throwable) { null } }
                                val disC1 = dis1?.let { try { Reflect.readAny(it, "content") } catch (_: Throwable) { null } }
                                val lm1 = e1?.let { Reflect.readAny(it, "mLivePlaybackMeta") }
                                Logger.always("POSPROBE #$p0 cap=\"${CfhUtil.readCaption(q1 ?: result)?.take(26)}\" " +
                                    "qp=${q1 != null} dirty=${if (q1 != null) CfhDecide.shouldFilterFeed(q1) else false} " +
                                    "ent=${e1?.javaClass?.simpleName ?: "-"} dis=${dis1 != null} disC=${disC1?.toString()?.take(20) ?: "-"} " +
                                    "liveMeta=${lm1 != null} liveStart=${lm1?.let { CfhUtil.safeNextLong(it, "mLiveStartTime") } ?: 0} " +
                                    "entCls=${e1?.javaClass?.name?.substringAfterLast('.') ?: "-"}")
                            }
                        } catch (_: Throwable) {}
                        // ===== 原诊断（节流�?=====
                        if (CfhState.adpGetDiag < 10) {
                            CfhState.adpGetDiag++
                            qp?.let { q0 ->
                                Logger.d("adpGet ${m.name}(#${chain.args[0]}) ret=${result.javaClass.name} qp hit=${CfhDecide.shouldFilterFeed(q0)} cap=${CfhUtil.readCaption(q0)?.take(20)}")
                            }
                            if (!CfhState.adpSelfDumped) {
                                CfhState.adpSelfDumped = true
                                CfhDiag.dumpAdapterSelf(chain.thisObject)
                            }
                        }
                        // ★★★ adapter 自持列表每次 D() 都修（去掉一次门控）：o 列表是实际显示源，
                        // rerank 每次换页都会往 o 里塞新的直播项，必须持续清理。
                        try { CfhSwap.fixAdapterSelfAlways(chain.thisObject) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
                result
            }
        }
    }

    private fun installAdpProv(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookAdpProv.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpProv.${c.name}.${m.name}").intercept { chain ->
                val result = chain.proceed()
                try {
                    if (result != null && CfhState.adpProvDiag < 10) {
                        CfhState.adpProvDiag++
                        Logger.d("adpProv ${m.name}() ret=${result.javaClass.name}")
                        if (result.javaClass.name != "com.yxcorp.gifshow.entity.QPhoto") {
                            CfhDiag.dumpProvider(result)
                        }
                    }
                } catch (_: Throwable) {}
                result
            }
        }
    }

    private fun installAdpQp(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookAdpQp.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpQp.${c.name}.${m.name}").intercept { chain ->
                try {
                    val qp = chain.args.firstOrNull { it != null && CfhState.qpClassRef?.isAssignableFrom(it.javaClass) == true }
                    if (qp != null && CfhState.adpQpDiag < 15) {
                        CfhState.adpQpDiag++
                        Logger.d("adpQp ${m.name}(${qp.javaClass.simpleName}) ret=${m.returnType.simpleName} hit=${CfhDecide.shouldFilterFeed(qp)} cap=${CfhUtil.readCaption(qp)?.take(20)}")
                    }
                } catch (_: Throwable) {}
                chain.proceed()
            }
        }
    }

    private fun isDescendantOf(v: View, root: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === root) return true; x = x.parent as? View }
        return false
    }
}

