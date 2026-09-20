package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.CurrentVideo
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

object ContentFilterHook {
    // shouldFilterMeta 用（每 10s 一次）：Regex 预编译，不随调用重建


    // ★ 直播页上下文放行：精选 tab 的过滤/删除链路（filterListArgs/laFind/TRUEDEL/zap）
    // 对直播页（LiveSlideActivity 等 com.kuaishou.live.* 页面）是灾难——直播页与精选容器
    // 共享数据引用（Ip() 从 slideplay 容器取 items），删共享列表/zap 直播实体字段会把
    // 直播页 pager 掏空或打成空壳 → 直播间黑屏、滑不动、底栏切换失效（实证 07:51 del=1 left=0 后卡死）。
    // 直播页在前台时：所有内容判定放行（shouldFilterFeed=false）、构造 zap 跳过。
    // 非快手主包 Activity（系统弹窗等）不改变状态，防弹窗期间误恢复过滤。
    private fun hookActivityLifecycle(xp: XposedInterface) {
        try {
            val actCls = Class.forName("android.app.Activity", false, null)
            val mOn = actCls.getDeclaredMethod("onResume")
            xp.hook(mOn).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("liveTop.onResume").intercept { chain ->
                val r = chain.proceed()
                try {
                    val act = chain.thisObject as? Activity
                    if (act != null) {
                        val cn = act.javaClass.name
                        if (cn.startsWith("com.kuaishou.live.")) {
                            if (!CfhState.liveTop) Logger.always("LIVETOP on: $cn")
                            CfhState.liveTop = true
                        } else if (cn.startsWith("com.smile.gifmaker") || cn.startsWith("com.yxcorp.") || cn.startsWith("com.kwai.")) {
                            if (CfhState.liveTop) Logger.always("LIVETOP off: $cn")
                            CfhState.liveTop = false
                        }
                    }
                } catch (_: Throwable) {}
                r
            }
            Logger.d("hookActivityLifecycle done")
        } catch (t: Throwable) { Logger.d("hookActivityLifecycle fail: ${t.message}") }
    }

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        CfhState.xpRef = xp
        CfhState.clRef = cl
        hookActivityLifecycle(xp)
        Logger.d("VER=rerank-v2 hook() cl=$cl")
        val targets = setOf(
            KsClass.PHOTO_DETAIL_ACTIVITY, KsClass.PHOTO_DETAIL_ACTIVITY_TABLET,
            "com.yxcorp.gifshow.HomeActivity", "com.yxcorp.gifshow.HomeActivityTablet"
        )
        for (a in targets) {
            val c = Reflect.findClass(a, cl) ?: continue
            val mOn = Reflect.findMethod(c, "onResume", 0)
            if (mOn != null) xp.hook(mOn).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("filter.on.$a").intercept { chain ->
                chain.proceed()
                try { startTrack(chain.thisObject as Activity) } catch (_: Throwable) {}
                null
            }
            val mOff = Reflect.findMethod(c, "onPause", 0)
            if (mOff != null) xp.hook(mOff).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("filter.off.$a").intercept { chain ->
                chain.proceed()
                try { stopTrack(chain.thisObject as Activity) } catch (_: Throwable) {}
                null
            }
            Logger.d("FilterHook on $a")
        }
        hookNasaFragment(xp, cl)
        hookFeedResponse(xp, cl)
        hookCacheClasses(xp, cl)
        hookMilanoContainers(xp, cl)
        hookPageLists(xp, cl)
        hookLiveRerank(xp, cl)
        hookKnhbT0(xp, cl)
        hookLiveFeedConstruct(xp, cl)
        hookKrnProbe(xp, cl)
        hookKrnReactContainerView(xp, cl)
        hookFragmentCrashGuard(xp, cl)
    }

    private fun hookFragmentCrashGuard(xp: XposedInterface, cl: ClassLoader) {
        try {
            // ★ 防护网扩展（真机回归 2026-09 01:18 闪退）：NasaPhotoDetailFragment
            // （onCreate/onCreateView 里 KmpSlideContext 空指针的两个实证崩溃点）与
            // BaseSlideItemFragment 一并纳入 NPE 捕获——换页 context 错配类崩溃
            // 兜成空帧而非闪退
            val classNames = arrayOf(
                "com.kwai.component.photo.detail.slide.groot.DetailSlidePlayFragment",
                "com.yxcorp.gifshow.detail.slideplay.nasa.groot.vm.NasaPhotoDetailFragment",
                "com.kwai.kmp.component.photo.detail.slide.groot.BaseSlideItemFragment",
                "com.kwai.component.photo.detail.slide.groot.BaseSlideItemFragment"
            )
            var hooked = 0
            for (cn in classNames) {
                val cls = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
                for (m in cls.declaredMethods) {
                    if (m.name != "onCreate" && m.name != "onCreateView" && m.name != "onResume" && m.name != "onActivityCreated" && m.name != "onPause" && m.name != "onDestroy") continue
                    try {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("crashGuard.${cn.substringAfterLast('.')}#${m.name}").intercept { chain ->
                            try {
                                chain.proceed()
                            } catch (e: Throwable) {
                                // ★ S1（2026-09）：定向吞掉已知危险的宿主框架异常（NPE/
                                // 越界/类型错/状态错），其余原样抛出避免掩盖真实缺陷
                                if (e is NullPointerException || e is IndexOutOfBoundsException ||
                                    e is ClassCastException || e is IllegalStateException) {
                                    Logger.always("crashGuard ${cls.simpleName} ${m.name} ${e.javaClass.simpleName}: ${e.message}")
                                    null
                                } else throw e
                            }
                        }
                        hooked++
                    } catch (_: Throwable) {}
                }
            }
            if (hooked > 0) Logger.d("hookFragmentCrashGuard hooked " + hooked + " methods")
        } catch (t: Throwable) { Logger.d("hookFragmentCrashGuard fail: " + t.message) }
    }

    // ★ 直播带货预览卡（RN CombinedCard）取证：hook KRN 容器 KrnFragment 生命周期，
    // 打印参数(bundleId等) + 创建调用栈，反查 feed 里谁在创建它，找到 Java 层数据源头。
    private fun hookKrnProbe(xp: XposedInterface, cl: ClassLoader) {
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

    private fun hookKrnReactContainerView(xp: XposedInterface, cl: ClassLoader) {
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
    // 直播重排模块：com.kuaishou.live.rerank 在 VerticalViewPager 滚动时把
    // LiveStreamFeed 直接塞进首页信息流。它的类被混淆（e$b.onPageScrolled 回调 +
    // d.t / e$d.E 内部方法），但数据一定以 List / 单项实体的形式跨方法。
    // 策略：按「回调签名」hook onPageScrolled，并扫描 rerank 包的 List 返回方法过滤。

    // ★ 去重键含 classloader 身份：快手插件化会把同名类装进第二个 loader，
    // 按类名去重会让新 Class 被误判「已 hook」而静默漏装（fragSeqHookedClasses
    // :1592 早已用此写法，此处统一）

    private fun hookLiveRerank(xp: XposedInterface, cl: ClassLoader) {
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
                                val q = CfhClean.findQpInObject(r)
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
                                if (!Logger.quiet) try { CfhClean.laFind() } catch (_: Throwable) {}
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
                                if (!Logger.quiet) try { CfhClean.laFind() } catch (_: Throwable) {}
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
                                    try { CfhClean.laFind(true) } catch (_: Throwable) {}
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



    private fun isDescendantOf(v: View, root: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === root) return true; x = x.parent as? View }
        return false
    }

    // Milano 数据�?= �?PageList（双向分页列表）。hook 其取数方法，
    // 在源头把直播/AI/广告/剧集 项替换成干净项或过滤掉�?
    private fun hookPageLists(xp: XposedInterface, cl: ClassLoader) {
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
                                        val q = CfhClean.findQpInObject(r)
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

    // ★ 方向C：hook knh.b.T0 —— Milano adapter 数据插入入口
    // T0(int startPos, List<QPhoto> before, List<QPhoto> update, List<QPhoto> affected, UpdateType, String reason)
    // 在 List 参数里删脏项 = 直播卡数据不进 adapter = 不上屏
    // ★ 冷启动强制刷新：首批拦到脏项（脏卡可能已先上屏，拦截慢于渲染）→ 2s 后
    // 对 knh.b 数据源触发一次下拉刷新语义的 reload（重新请求第一页 replaceAll，
    // 新批次仍会走本 hook 过滤）＝把屏上的脏卡刷掉。每次进程冷启动只刷一次。
    private fun scheduleBootFlush(src: Any?) {
        if (CfhState.bootFlushDone || CfhState.bootFlushPending) return
        CfhState.bootFlushPending = true
        if (src != null) CfhState.knhbInst = java.lang.ref.WeakReference(src)
        Logger.d("BOOTFLUSH scheduled 2s")
        CfhState.handler.postDelayed({
            CfhState.bootFlushPending = false
            try { doBootFlush() } catch (e: Throwable) { Logger.d("BOOTFLUSH err: ${e.message}") }
        }, 2000)
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
        val ok = try { CfhClean.triggerRefresh() } catch (_: Throwable) { false }
        Logger.d("BOOTFLUSH fallback triggerRefresh=$ok")
    }

    // ★ 防重复视频：refresh（BOOTFLUSH/prefetch）重拉第一页可能带回已供给过的视频。
    // 已供给 photoId 历史（LRU 上限 500）+ QPhoto→photoId 身份缓存（每对象只反射一次）。
    // 去重只作用于实测的数据载荷路径：T0 的 args[2]（update 批次）+ E1 的 args[0]，
    // 删批次里 photoId 已在历史中的项；护栏同 filterListArgs（删后至少留 1 或原本 ≤1）。
    // ★ getPhotoId Method 缓存（含负缓存）：包装类无此方法时原先每元素每次抛
    // NoSuchMethodException（栈填充极贵），E1 大批次下纯烧 CPU
    // ★ 饥饿计数原子化：hook 回调跑在任意线程，非原子 ++/清零丢计数会让
    // 「连续 4 批饥饿→清历史自愈」延迟触发（功能性计数，非诊断）
    private fun hookKnhbT0(xp: XposedInterface, cl: ClassLoader) {
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
                            if (removed > 0) {
                                if (CfhState.knhbT0Diag < 30) {
                                    CfhState.knhbT0Diag++
                                    Logger.d("knhb.$nm filtered del=$removed")
                                }
                                scheduleBootFlush(chain.thisObject)
                            }
                            // ★ 防重复（单点去重）：E1 全时去重（服务端原始批次主战场）；
                            // T0 仅 refresh 重拉路径（reason 含 firstRequest）去重——loadMore 续拉时
                            // T0 的 update 批次是 E1 刚供给的幸存项（已在 hist），再判重=双重去重误删
                            if (nm == "E1" && ptypes.size == 1) {
                                CfhClean.dedupeInsertBatch(chain.args.getOrNull(0) as? MutableList<Any?>, "E1")
                            } else if (nm == "T0" && ptypes.size == 6) {
                                val reason = chain.args.getOrNull(5) as? String ?: ""
                                if (reason.contains("firstRequest")) {
                                    CfhClean.dedupeInsertBatch(chain.args.getOrNull(2) as? MutableList<Any?>, "T0fr")
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

    // Milano �?feed 架构：直�?hotphoto)/AI(airecommendslide)/视频(commonfeedslide) 各是独立容器�?
    // 容器类被混淆�?a，这里探测其取数方法�?hook 过滤�?
    private fun hookMilanoContainers(xp: XposedInterface, cl: ClassLoader) {
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

    private fun hookCacheClasses(xp: XposedInterface, cl: ClassLoader) {
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
    private fun hookLiveFeedConstruct(xp: XposedInterface, cl: ClassLoader) {
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


    private fun hookFeedResponse(xp: XposedInterface, cl: ClassLoader) {
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

    private fun startTrack(act: Activity) {
        CfhState.tracked = act
        CfhState.handler.removeCallbacks(checkTask)
        CfhState.handler.postDelayed(checkTask, 300)
    }

    private fun stopTrack(act: Activity) {
        if (CfhState.tracked === act) {
            CfhState.tracked = null
            CfhState.handler.removeCallbacks(checkTask)
        }
    }

    private val checkTask = object : Runnable {
        override fun run() {
            val act = CfhState.tracked ?: return
            check(act)
            // 1500ms：check 内部自带 5s/10s 节流，轮询本身只需兜底醒来，
            // 350ms 的空转唤醒纯属浪费（改动前每秒近 3 次主线程调度）
            if (CfhState.tracked != null) CfhState.handler.postDelayed(this, 1500)
        }
    }

    private fun check(act: Activity) {
        Logger.safe("findPagerInCheck") {
            // ★ pager 已定位且仍挂在窗口上：整个搜索块直接跳过（此前缓存有效时
            // 每 5 秒仍白跑一次 getIdentifier + findViewById）。仅在缓存缺失或
            // 脱离窗口时按 5 秒节流重新搜索
            val pc = CfhState.pagerCache
            if (pc != null && (pc as? android.view.View)?.isAttachedToWindow == true) return@safe
            val now = System.currentTimeMillis()
            if (now - CfhState.lastPagerSearch > 5000) {
                CfhState.lastPagerSearch = now
                try {
                    val id = act.resources.getIdentifier("nasa_groot_view_pager", "id", "com.smile.gifmaker")
                    if (id != 0) {
                        val v: View? = act.findViewById(id)
                        if (v != null) ContentFilterHook.findPager(v)
                    }
                } catch (_: Throwable) {}
                val pc2 = CfhState.pagerCache
                if (pc2 == null || (pc2 as? android.view.View)?.isAttachedToWindow != true) {
                    val decor = act.window.decorView as? ViewGroup
                    if (decor != null) ContentFilterHook.findPager(decor)
                }
            }
        }

        Logger.safe("filterMetaCheck") {

            val now = System.currentTimeMillis()
            if (now - CfhState.lastSkipTime < 10000) return@safe
            CfhState.lastSkipTime = now
            val v = CurrentVideo.current
            if (v.valid() && CfhDecide.shouldFilterMeta(v) && !Logger.quiet) Logger.d("filter meta diag: ${CfhUtil.readCaption(v)?.take(20)}")
        }
    }


    // 从 View 向上爬（含反射字段）的 findQpUpFromView 已删除：零调用死代码
    // （grep 证实），R8 release 亦会剥离

    // ★ 下载捕获的数据层直供（2026-09 排障）：网络钩子（URL ctor/okhttp/播放器）
    // 在 API 36 ART + 插件化播放器下全部不可靠（安装 ok 但永不命中），下载菜单
    // 拿不到视频。只认「当前可见分页 Fragment」持有的 QPhoto。
    // currentFeedPhoto 在「点下载的瞬间」现场解析：遍历活着的 slide Fragment，
    // 取此刻 localVisibleRect 非空的页读字段——500ms 被动扫描存在竞态（快速划页
    // 后立刻点下载，扫描还没跑到新页）
    // ★ pos → QPhoto/holder（D(pos) 供给映射，LRU 16）：位置↔条目的权威来源

    // ★ Fragment 自身的 photoId（自动锁定「这条视频」2026-09）：Fragment 创建时
    // 参数里绑定的是它自己那一条（与会被预绑定为下一视频的 M 字段不同）——拿这个
    // ID 去 VM 批次数据里精确匹配，得到的就是正在看的这条，且 URL 是批次原生真链


    // 供下载 URL 深扫用：与 currentFeedPhoto 配对的可见 Fragment


    // ★ 分享链接路线（用户方案 2026-09）：photoId 精确匹配 VM 窗口/活 Fragment 的
    // 照片对象——分享链接是快手自己认定的「这条视频」，零歧义

    // ★ 下载候选环（2026-09 终版）：自动判定「哪个是正在看的」在快速划页下永远有
    // 歧义——把最近划过的几条（可见页+预载页）全量列出，用户在下载菜单里自己点




    // 供下载填充用：判定路径已验证可读到作者名的读取器
    // dumpAiAllFields 已删除：grep 证实零调用死代码（AIFULL 一次性诊断的旧实现）



    // ==================== 数据层拦�?====================

    private fun hookNasaFragment(xp: XposedInterface, cl: ClassLoader) {
        val c = Reflect.findClass("com.yxcorp.gifshow.detail.slideplay.nasa.groot.vm.NasaPhotoDetailFragment", cl) ?: return
        val m = Reflect.findMethod(c, "onResume", 0) ?: return
        Logger.safe("hookNasa") {
            Logger.d("nasaCls: ${c.name} loader=${c.classLoader} methodCls=${m.declaringClass.name}")
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("nasa.onResume").intercept { chain ->
                chain.proceed()
                try {
                    Logger.d("nasaCls real: ${chain.thisObject.javaClass.name} loader=${chain.thisObject.javaClass.classLoader}")
                    CfhState.realFragClass = chain.thisObject.javaClass
                    hookFragCallSeq(xp, CfhState.realFragClass!!)
                } catch (_: Throwable) {}
                try { findDataSource(chain.thisObject) } catch (_: Throwable) {}
                try { CfhState.liveSlideFragments.add(chain.thisObject) } catch (_: Throwable) {}
                CfhState.handler.postDelayed({ try { CfhDiag.diagFragment(chain.thisObject) } catch (_: Throwable) {} }, 500)
                null
            }
        }
        hookFragQpSetters(xp, c)
    }

    private fun hookFragCallSeq(xp: XposedInterface, fragClass: Class<*>) {
        val clsKey = fragClass.name + "@" + System.identityHashCode(fragClass.classLoader)
        val isNew = synchronized(CfhState.fragSeqHookedClasses) { CfhState.fragSeqHookedClasses.add(clsKey) }
        if (!isNew) return
        var hookOk = 0
        Logger.d("fragSeqInstall start: ${fragClass.name}")
        var cls: Class<*>? = fragClass
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 4) {
            for (m in cls!!.declaredMethods) {
                if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                if (m.parameterTypes.isEmpty()) continue
                if (m.name == "onResume" || m.name == "onPause") continue
                Logger.safe("hookFragSeq.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("fragSeq.${cls!!.name}.${m.name}").intercept { chain ->
                        try {
                            val n = CfhState.fragSeqCount.incrementAndGet()
                            if (n <= 50) {
                                val argsDesc = chain.args.joinToString(",") { a -> a?.javaClass?.simpleName ?: "null" }.take(120)
                                Logger.d("fragSeq #$n ${m.name}($argsDesc) in ${cls!!.simpleName}")
                            }
                            if (m.name == "gq" || m.name == "aq" || m.name == "Vp") {
                                val a0 = chain.args.firstOrNull()
                                if (a0 != null) {
                                    if (m.name == "aq") {
                                        try {
                                            CfhState.vmRef = a0
                                            CfhClean.filterVmLists(a0)
                                        } catch (_: Throwable) {}
                                    }
                                    val qpFound = CfhClean.findQpInObject(a0)
                                    val qpHit = qpFound?.let { CfhDecide.shouldFilterFeed(it) } == true
                                    if (CfhState.gqDumpCount < 8) {
                                        CfhState.gqDumpCount++
                                        Logger.d("fragArg ${m.name}: cls=${a0.javaClass.name} qpIn=${qpFound != null} qpHit=$qpHit")
                                    }
                                    // ★ Vp 拦绑定已拆除（真机 22:40 闪退实证）：阻断绑定会造出
                                    // 「已创建未初始化」的僵尸 Fragment——框架依赖字段（如
                                    // PhotoDetailLogger）永不注入 → 下一个生命周期 onPause 空
                                    // 指针闪退。渲染层拦截在这个框架版本上不安全，脏数据全部
                                    // 交给数据层清洗（filterVmLists/laFind/sanitize 毫秒级摘除）
                                    if (m.name == "Vp" && qpHit && CfhState.vpBlockDiag < 20) {
                                        CfhState.vpBlockDiag++
                                        Logger.always("Vp dirty-pass #${CfhState.vpBlockDiag}: ${a0.javaClass.name}")
                                    }

                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
                hookOk++
            }
            cls = cls.superclass; lvl++
        }
        Logger.d("fragSeqInstall done: ok=$hookOk candidates=$hookOk")
    }


    private fun hookFragQpSetters(xp: XposedInterface, fragClass: Class<*>) {
        var cls: Class<*>? = fragClass
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 5) {
            for (m in cls!!.declaredMethods) {
                if (!m.parameterTypes.any { it.name.contains("QPhoto") }) continue
                // ★ 去重键含 classloader 身份（hookKey）：插件化二 loader 同名类不漏装
                val key = CfhUtil.hookKey(cls!!) + "." + m.name
                val shouldHook = synchronized(CfhState.fragSetterHooked) { CfhState.fragSetterHooked.add(key) }
                if (!shouldHook) continue
                Logger.d("hook frag qp setter: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) in ${cls.name}")
                Logger.safe("hookFragSet.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("fragSet.${key}").intercept { chain ->
                        try {
                            for (i in chain.args.indices) {
                                val a = chain.args[i] ?: continue
                                if (CfhState.qpClassRef?.isAssignableFrom(a.javaClass) == true && CfhDecide.shouldFilterFeed(a)) {
                                    val clean = CfhClean.findCleanQp()
                                    if (clean != null) {
                                        Logger.d("fragSet ${m.name} replaced: ${CfhUtil.readCaption(a)?.take(15)} -> ${CfhUtil.readCaption(clean)?.take(15)}")
                                        chain.args[i] = clean
                                    } else {
                                        Logger.d("fragSet ${m.name} hit but no clean: ${CfhUtil.readCaption(a)?.take(15)}")
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
            }
            cls = cls.superclass; lvl++
        }
    }
















    // 影视/广告壳子字段对普通视频也是非 null 空壳 �?必须查内层真实内容才算命�?





    // dump holder 图 / dumpMilanoHolder / forceRebindCurrent / applyWindowClean /
    // findCleanPos / adapterMainList 已删除：grep 证实零调用死代码（R8 release 亦剥离）

    // �?vm 窗口取同位置富数�?qp（显示源实例，带完整 user/caption�?

    // 干净视频写进 vm 窗口同槽的 applyWindowClean 已删除：grep 证实零调用死代码

    // holder 图里�?Fragment 实例

    private fun findDataSource(frag: Any) {
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
                                try { hookViewModel(fv) } catch (_: Throwable) {}
                                try { CfhClean.filterVmLists(fv) } catch (_: Throwable) {}
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
        if (CfhState.vmRef != null) { try { CfhClean.filterVmLists(CfhState.vmRef!!) } catch (_: Throwable) {} }
        // LAFIND：脏元素身份反查真源字段（诊断用）
        if (!Logger.quiet) try { CfhClean.laFind() } catch (_: Throwable) {}

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
                v.getChildAt(i)?.let { ContentFilterHook.findPager(it) }
            }
        }
    }

    // ScrollStrategyViewPager 等横滑 pager：hook 其基类(含 androidx ViewPager)的 instantiateItem/getItem/adapter 相关
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
                                        val qp = CfhClean.findQpInObject(result)
                                        val hit = if (qp != null) CfhDecide.shouldFilterFeed(qp) else false
                                        if (CfhState.pagerDiag < 25) {
                                            CfhState.pagerDiag++
                                            Logger.d("pager i ${nm}(#$pos) -> ${result.javaClass.simpleName} hit=$hit qp=${qp != null}")
                                        }
                                        if (qp != null && hit) {
                                            val clean = CfhClean.pickFromQueue()
                                            if (clean != null) {
                                                val sw = CfhClean.writeQpInto(result, clean)
                                                CfhState.pagerSwapCount++
                                                Logger.d("pager swap ${nm}(#$pos) sw=$sw ${CfhUtil.readCaption(qp)?.take(15)}")
                                            }
                                        } else if (qp != null && !hit && CfhState.liveWindowDiag < 8) {
                                            // ★ 视频卡是否带"直播浮窗/进入直播间引导"：找 QP 树里的 live 状态字段
                                            val liveInfo = CfhClean.findLiveWindowField(qp)
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
                                val qp = elem?.let { CfhClean.findQpInObject(it) }
                                Logger.d("provList qp: ${qp != null}")
                            }
                            if (result is MutableList<*>) {
                                val hits = result.filter { it != null && (try { CfhDecide.shouldFilterFeed(it) } catch (_: Throwable) { false }) }
                                if (hits.isEmpty()) {
                                    val wrapHits = result.filter { it != null && CfhClean.findQpInObject(it)?.let { qp -> CfhDecide.shouldFilterFeed(qp) } == true }
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
                                val qp = CfhClean.findQpInObject(result)
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
                if (hasListParam || isDMethod || isInst) {
                    Logger.d("  hookAdpX[${qcn.simpleName}]: ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName}")
                    Logger.safe("hookAdpX.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpX.${qcn.name}.${m.name}").intercept { chain ->
                            try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                            if (m.name == "p" && chain.args.size >= 2) {
                                try {
                                    val pos = chain.args[1] as Int
                                    val adp = chain.thisObject
                                    val data = try { Reflect.callMethod(adp, "g0", pos) as? List<*> } catch (_: Throwable) { null }
                                    if (data != null && data.any { it != null && CfhDecide.shouldFilterFeed(it) }) {
                                        for (delta in listOf(1, -1, 2, -2, 3, -3, 4, -4)) {
                                            val np = pos + delta
                                            val nd = try { Reflect.callMethod(adp, "g0", np) as? List<*> } catch (_: Throwable) { null }
                                            if (nd != null && nd.isNotEmpty() && !nd.any { it != null && CfhDecide.shouldFilterFeed(it) }) {
                                                chain.args[1] = np
                                                if (CfhState.adpXRedirectDiag < 30) { CfhState.adpXRedirectDiag++; Logger.d("adpX p REDIRECT #$pos -> #$np") }
                                                try { CfhClean.triggerRefresh() } catch (_: Throwable) {}
                                                break
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                            val r = chain.proceed()

                            try {
                                val pos = chain.args.lastOrNull() as? Int ?: -1
                                val isPD = m.name == "p" || m.name == "D"
                                val shouldDump = if (isPD) CfhState.adpXDump < 40 && CfhState.adpXDumped.add("pd_" + pos) else CfhState.adpXDumped.add(qcn.name + "." + m.name) && CfhState.adpXDump < 25
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
            }
            qcls = qcn.superclass; qlvl++
        }
        var cls: Class<*>? = c
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 4) {
            for (m in cls!!.declaredMethods) {
                if (m.returnType.name.contains("Fragment") && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == Any::class.java) {
                    Logger.d("  hook adp create: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) in ${cls.name}")
                Logger.safe("hookAdpF.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adp.F.${c.name}").intercept { chain ->
                        try {
                            val a0 = chain.args[0]
                            if (a0 != null && CfhDecide.shouldFilterFeed(a0)) {
                                Logger.d("adp F blocked: ${CfhUtil.readCaption(a0)?.take(25)}")
                                val vm = CfhState.vmRef
                                if (vm != null) {
                                    var replaced = false
                                    for (i in 0 until 15) {
                                        val qp = try { Reflect.callMethod(vm, "T0", i) } catch (_: Throwable) { null }
                                        if (qp != null && !CfhDecide.shouldFilterFeed(qp)) {
                                            chain.args[0] = qp
                                            replaced = true
                                            Logger.d("adp F replaced -> ${CfhUtil.readCaption(qp)?.take(25)}")
                                            break
                                        }
                                    }
                                    if (!replaced) {
                                        for (i in 0 until 15) {
                                            val qp = try { Reflect.callMethod(vm, "U0", i) } catch (_: Throwable) { null }
                                            if (qp != null && !CfhDecide.shouldFilterFeed(qp)) {
                                                chain.args[0] = qp
                                                Logger.d("adp F replaced U0 -> ${CfhUtil.readCaption(qp)?.take(25)}")
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
            }
                // adapter 的 set/add/addAll(List) 方法：直播从这塞进信息流，在参数阶段就剔掉
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection") }
                if (hasListParam && m.declaringClass == cls) {
                    Logger.safe("hookAdpList.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpList.${c.name}.${m.name}").intercept { chain ->
                            try { CfhClean.filterListArgs(chain.args) } catch (_: Throwable) {}
                            chain.proceed()
                        }
                    }
                }
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
                    if (isSupply) {
                        Logger.safe("hookAdpGet.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpGet.${c.name}.${m.name}").intercept { chain ->
                                if (CfhState.adpRef == null) CfhState.adpRef = chain.thisObject
                                val result = chain.proceed()
                                try {
                                    if (result != null && !CfhState.adpGetSwapIn) {
                                        val qp = CfhClean.findQpInObject(result)
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
                                        val holderLive = CfhClean.findFragInHolder(result)?.javaClass?.name?.let { fn -> fn.contains("Live") || fn.contains("Ad") } == true
                                        // ★★ 再 BFS 全图找任何 Live/Ad 实体（直播卡可能渲染在 NasaPhotoDetailFragment 里，
                                        // Fragment 类名不含 Live，QPhoto 也提不到，只能全图找实体类名）
                                        val holderDirtyEnt = if (!holderLive) CfhClean.findDirtyEntityInHolder(result) else null
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
                                            try { CfhClean.offerClean(qp) } catch (_: Throwable) {}
                                        }
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
                                        try { CfhClean.fixAdapterSelfAlways(chain.thisObject) } catch (_: Throwable) {}
                                    }
                                } catch (_: Throwable) {}
                                result
                            }
                        }
                    } else if (m.parameterTypes.isEmpty() && nonPrimRet) {
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
                    } else if (m.parameterTypes.size >= 1 && m.parameterTypes.any { it.name.contains("QPhoto") }) {
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
                }
            }
            cls = cls.superclass; lvl++
        }
    }

    // findCleanPos / adapterMainList（互相引用的死代码对）已删除：grep 证实零外部调用



    // ★ Method 查找缓存：rebind/jumpNext/frag 替换/refresh 命中路径的
    // getDeclaredMethod 每次全类方法表查找+复制，缓存后 O(1)；查不到不缓存
    //（方法缺失说明类结构变化，自然重查）


    // ★★ holder 全图 BFS：找类名明确含 Live（直播实体/直播Fragment/直播卡容器）的脏对象。
    // 用于 QPhoto 提不到、页面 Fragment 又不是 Live 类型的场景（直播广告卡渲染在 NasaPhotoDetailFragment 里）。

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

    private fun hookViewModel(vm: Any) {
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
                if (m.parameterTypes.any { qpClass.isAssignableFrom(it) } && m.returnType == Void.TYPE) {
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
                if (m.parameterTypes.any { java.util.List::class.java.isAssignableFrom(it) || it.name.contains("List") } && m.returnType == Void.TYPE) {
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
                // y0()/B0()/E()/F0()/H()/H0()/V0() 等返�?List 的方�?= 直播/卡片�?adapter 的数据源�?
                // 直接过滤返回值，让直播卡根本进不�?adapter�?
                if ((m.returnType == java.util.List::class.java || m.returnType.name.contains("List")) && m.parameterTypes.isEmpty()) {
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
                // ★ rerank 插卡唯一入口 T1(int,QPhoto,boolean,String)（LiveRerankPresenter d.G
                // → VM.T1 → data_source_service.q() 单条插入）：非 List 批次 filterListArgs
                // 结构性拦不到，data source 内部列表也不在 laFind 根集——T1 入口是唯一拦点。
                // 判定用 shouldFilterFeed + decideFeedRaw 同步全量兜底（T1 一次性入口不能走
                // 异步缓存：miss 先放行=卡必上屏）；T1CALL 无条件打日志取证 T1 是否真被调用
                if (m.name == "T1" && m.parameterTypes.size == 4 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    qpClass.isAssignableFrom(m.parameterTypes[1]) &&
                    m.parameterTypes[2] == java.lang.Boolean.TYPE &&
                    m.parameterTypes[3] == String::class.java) {
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
                if (m.returnType == qpClass && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
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
                                        val clean = CfhClean.pickFromQueue()
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
                if (m.name == "y" || m.name == "y0") {
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
            }
            cls = cls.superclass; lvl++
        }
    }




    // ★ 时间节流：全对象图 BFS（vmDeepClean＋一层列表）开销最大，hook 触发频率
    // 远超数据更新频率，500ms 窗口内重复清洗是无用功
    // ★ 治本 ANR：单次全对象图清洗成本数秒（启动期全 cache miss 放大），主线程跑
    // 必卡输入（01:05 ANR trace 铁证 main Runnable at decideFeedRaw←filterVmLists）。
    // 移后台单线程：主线程 hook 只剩入队；VM 列表多为 CopyOnWriteArrayList（快照
    // 迭代器并发安全），清洗循环全有 try-catch 兜底。节流 500ms 防后台积压
    // ★ keep-latest 合并：单次全图清洗可达秒级，500ms 节流后队列仍会积压过期任务
    //（都是重复清洗同一 VM）。同一时刻只保留最新待清洗对象，跑完再取最新——
    // 队列深度从无界变为至多 2，过期货全部丢弃


    // ★ 槽位包装链提 QP（通用版）：包装链深度动态（实证 x5i.q$a→q→Fragment 两跳、
    // loh.f1→e1→d0→Fragment 三跳），固定跳数必失效。沿 b 字段链下钻（≤5 层），
    // 链上任一点直接命中 QP 即返回；到 Fragment 后再试 m.b。
    // 校验 b 值非集合/View，防误下钻




    // ★ 时间节流：V0() 等 getter 每次返回重建的快照列表，副本删了真源不动，
    // 每次进来都重扫重删（实证 1.45s 15 次 sanitize ret）＝纯无用功＋分配风暴。
    // 数据更新频率低，脏项最长存活 200ms 可接受
    // ★ 节流按列表身份（审阅 2026-09）：原全进程单一时间戳使同一 200ms 窗口内到达
    // 的其它列表/快照（V0 每次 getter 重建、rerank、ds.ret、cache 多路共用本函数）
    // 完全不滤，脏项存活窗口远超预期


    // ★ 后台清洗线程闸门：非线程安全列表（ArrayList 等）的结构性修改
    // （removeAt/removeIf）与主线程迭代并发时，会让宿主自己的迭代代码抛
    // CME/IndexOOB——PROTECTIVE 只护模块回调，救不了宿主。COW/Synchronized
    // 列表可后台直改；其余一律把删除动作投回主线程（同线程修改无并发）。
    // dirtyId 用 IdentityHashMap 身份比对



    // 宽匹配（Live/Ad 子串）前的结构类名黑名单：Presenter/Callback/Fragment/
    // Interceptor/Executor 等管理结构绝不能被子串误杀（FragmentManager.mAdded 教训）



    // ★ prefetch 续拉：refresh()=invalidate+重拉第一页（带回旧推荐=重复视频）；load() 不 invalidate，
    // 已有页缓存非空时 i()=false → q1.T1() 用 j0().mCursor 续拉下一页（kik.i 接口方法，o0 实现）。
    // getMethod 解析全继承链 public 方法（knh.b 链深，declaredMethods 逐级遍历够不到 kik.o0）；
    // 失败再按 BOOTFLUSH 路径2 在字段值(如 dnh.q1)上找 load。
    // ★ 死穴防护（2026-09-08 用户报「长时间无更多滑不出」）：o0.load() 在 hasMore()==false 时是 no-op
    // （源码仅 hasMore||invalidate 才 R1 发请求），续拉链路会永久卡死——前置健康检查：
    // hasMore=false → 调 q1.refresh()（invalidate+重拉第一页）恢复供给；isLoading=true（请求在途）→ 跳过等回调；
    // 正常 → load() 续拉。



    // 过滤总开关缓存（2 秒 TTL）：所有过滤开关全关时 shouldFilterFeed 零反射直接返回，
    // 避免每次 feed 加载都白跑一遍 mEntity 反射链（全关时卡顿的主源）

    // ★ 性能优化-判定缓存：同一 QPhoto 在列表/窗口/adapter 多条链路被重复判定几十上百次，
    // 每次全量反射 20+ 字段。WeakHashMap 按对象身份缓存判定结果，配置变化时失效。

    // ★ 误伤审计：命中分支记录原因+文案样本；每 60 次汇总一次各规则拦截量

    // ★ 内容签名缓存：V0 等 getter 每次返回重建的包装对象（identity 变化致 cachedDecide
    // 的 identity 缓存全 miss，实证 96 万次全量判定/CPU 113% 风暴）。同一视频快照重建但
    // 内容不变 → 按内容签名（实体类|文案|点赞数）命中，全量判定每条视频只跑一次

    // ★ 治本 ANR 第二步：主线程零重判定。decideFeedRaw 全量判定（20+ 反射读）启动期
    // cache 全 miss 时主线程连跑 N 次=01:05 ANR 铁证。改为：sig 命中直接用；miss 时
    // 主线程只提 sig（4 次 Field 缓存反射，微秒级）＋入后台队列先放行（false）；
    // 后台算完写 sig 缓存，由高频触发的 getter/响应 hook 下一拍补剔。脏项最长存活
    // 一小拍（后台判定 ms 级+补剔频繁），换来主线程零阻塞
    // ★ 主线程微秒级官方标记快判：decideBySig miss 时先跑此函数，命中官方标记即拦，
    // 未命中才入后台全量判定。修复 ANR 修复引入的首见漏一拍回归（AI/广告/短剧）


    // ★ 规则单源（审阅 2026-09 遗留项收敛）：decideFeedRaw / decideContentRaw 的
    // 七组内容规则此前整段复制两份手工同步，极易漂移。抽出 feedRules 统一实现。
    // allowLikeRule=false 用于列表级移除（shouldFilterContent 语义）：点赞命中项
    // 保留，避免 adapter 数据列表清空导致 Pager count 失配崩溃。
    // 注：adNovel 的 drama 兜底判定（ADVIDEO 关、DRAMA 开时也拦）原先只在
    // decideContentRaw 存在，现统一两处共享（ADVIDEO 默认开时行为不变）

    // = shouldFilterFeed 去掉点赞阈值规则：列表级移除只按内容类型（广告/AI/直播/短剧/电商/关键词）
    // 点赞命中项保留，避免 adapter 数据列表清空导致 Pager count 失配崩溃。
    // 规则已单源化到 feedRules（allowLikeRule=false）





    // ★ 官方作者声明 AI 标记（2026-09-05 AIFULL 实证 + jadx 14.7.40 确认）：
    // mPhotoMeta.mDisclaimergeMessageV2（DisclaimergeMessage）是「作者声明：含AI生成内容」
    // 等官方声明角标的数据源。类字段：content(声明文本)/type/riskStyleType(>0=RUMOR 谣言,
    // 否则 DANGER 危险)/addAiMark。必须匹配 content 含 AI 关键词——不能只判非空
    //（谣言/危险类声明也挂同一对象，非空会误伤）。声明文本为中文短句，contains("AI")
    // 大写敏感安全；ai生成 忽略大小写兜底小写变体。


    // ==================== UI 层兜�?====================


    // 死代码清理(2026-09-05)：shouldFilterUi/isVideoView/parseCount/skip/findFeedPager/
    // findViewPager/findRv 曾是 UI 层跳过方案的实现，方案被数据层"刷不到"取代后
    // 全部零调用（grep 证实）。

    // ==================== 对外 API 转发（职责拆分后调用点不变） ====================
    fun invalidateFilterCache() = CfhDecide.invalidateCaches()
    fun refreshContent(): Boolean = CfhClean.refreshContent()
    fun currentFeedPhoto(): Any? = CfhCapture.currentFeedPhoto()
    fun currentFeedFragment(): Any? = CfhCapture.currentFeedFragment()
    fun isCaptureTrusted(): Boolean = CfhCapture.isCaptureTrusted()
    fun findPhotoById(pid: String): Any? = CfhCapture.findPhotoById(pid)
    fun visibleEntries(): List<CfhCapture.VisEntry> = CfhCapture.visibleEntries()
    fun readVisibleUserName(qp: Any): String = CfhCapture.readVisibleUserName(qp)

}
