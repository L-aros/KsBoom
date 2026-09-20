package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.angbang852.manjiao.data.CurrentVideo
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第五步：生命周期/入口钩（2026-09 S3）。
// Activity/Fragment 生命周期跟踪（liveTop/前台追踪）、crashGuard 防护网、
// 轮询检查（check 每 1.5s 兜底）与 fragment 钩子安装。
object CfhLcHook {
    // shouldFilterMeta 用（每 10s 一次）：Regex 预编译，不随调用重建


    // ★ 直播页上下文放行：精选 tab 的过滤/删除链路（filterListArgs/laFind/TRUEDEL/zap）
    // 对直播页（LiveSlideActivity 等 com.kuaishou.live.* 页面）是灾难——直播页与精选容器
    // 共享数据引用（Ip() 从 slideplay 容器取 items），删共享列表/zap 直播实体字段会把
    // 直播页 pager 掏空或打成空壳 → 直播间黑屏、滑不动、底栏切换失效（实证 07:51 del=1 left=0 后卡死）。
    // 直播页在前台时：所有内容判定放行（shouldFilterFeed=false）、构造 zap 跳过。
    // 非快手主包 Activity（系统弹窗等）不改变状态，防弹窗期间误恢复过滤。

    internal fun hookActivityLifecycle(xp: XposedInterface) {
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


    internal fun hookFragmentCrashGuard(xp: XposedInterface, cl: ClassLoader) {
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


    internal fun hookNasaFragment(xp: XposedInterface, cl: ClassLoader) {
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
                try { CfhFeedHook.findDataSource(chain.thisObject) } catch (_: Throwable) {}
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
                                            CfhWash.filterVmLists(a0)
                                        } catch (_: Throwable) {}
                                    }
                                    val qpFound = CfhProbe.findQpInObject(a0)
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
                                    val clean = CfhSwap.findCleanQp()
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


    internal fun startTrack(act: Activity) {
        CfhState.tracked = act
        CfhState.handler.removeCallbacks(checkTask)
        CfhState.handler.postDelayed(checkTask, 300)
    }


    internal fun stopTrack(act: Activity) {
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
                        if (v != null) CfhViewHook.findPager(v)
                    }
                } catch (_: Throwable) {}
                val pc2 = CfhState.pagerCache
                if (pc2 == null || (pc2 as? android.view.View)?.isAttachedToWindow != true) {
                    val decor = act.window.decorView as? ViewGroup
                    if (decor != null) CfhViewHook.findPager(decor)
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
}
