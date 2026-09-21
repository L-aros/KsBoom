package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.angbang852.manjiao.data.CurrentVideo
import io.github.angbang852.manjiao.data.Prefs
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
                        // ★ 记录页面切换时刻（供 isLikelyUserTapByTime 兜底判据用）
                        CfhState.lastPageSwitchAt = System.currentTimeMillis()
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

    // ★★★ 禁止自动进入直播间（2026-09 用户需求）：在直播预览页停留时间长了，快手会自动
    // 拉起直播间 Activity（设备事件日志实证：
    //   21:27:06 ACTIVITY_RESUMED class=com.kuaishou.live.core.basic.activity.LiveSlideActivity
    //   21:29:59 ACTIVITY_PAUSED  （停留 2m53s））。
    // 拦截点选 Activity.startActivity*（跳转发起处），属「源头侧拒绝」而非渲染层补丁：
    // 命中直播间 Activity 时不执行跳转，直接返回，直播间根本不会被创建。
    // 受 K_PB_NO_AUTO_LIVE 开关控制（默认关=保持原行为）；用户手动点进直播间不受影响
    //（手动入口走同一 startActivity，但带 FLAG_ACTIVITY_NEW_TASK 与自动跳转难以区分，
    //  故默认关闭，由用户自行开启并按体感确认）。
    private val AUTO_LIVE_ACT = arrayOf(
        "com.kuaishou.live.core.basic.activity.LiveSlideActivity",
        "com.kuaishou.live.core.basic.activity.LivePlayActivity"
    )

    /**
     * 判断本次 startActivity 是否由用户点击触发（而非 App 自动跳转）。
     *
     * ★ 只看栈顶若干帧：不能全栈扫描 —— 主线程任何调用（含 Handler 定时跳转）
     * 底层都挂在 Looper/ViewRootImpl 之下，全栈找 "ViewRootImpl" 会把自动跳转
     * 也判成手动，过滤器形同虚设。真正有判别力的是「起跳前几帧」：
     *   手动：View.performClick → AdapterView$PerformClick → dispatchTouchEvent …
     *   自动：Handler.dispatchMessage → xxx$Runnable.run / Timer* / CountDownTimer …
     */
    /**
     * 停留时长兜底判据（与调用栈判别取「或」）：
     * 实证设备事件日志 —— 自动进入直播间发生在「停留 40s ~ 2m53s」之后；手动点击则是
     * 用户看到直播预览后立即发生的。故若距上次页面切换 < 25s，更可能是手动点击，放行。
     * 该判据不依赖栈形状，作为栈判别失效时的安全网（避免误拦手动进入）。
     */
    private fun isLikelyUserTapByTime(): Boolean {
        val last = CfhState.lastPageSwitchAt
        if (last <= 0L) return false
        return System.currentTimeMillis() - last < 25_000L
    }

    private fun isUserInitiatedStack(): Boolean {
        return try {
            val st = Thread.currentThread().stackTrace
            // ★ 放宽到 24 帧：实测手动点击走 Fragment/Context 路径，输入事件帧可能
            // 不在最顶端（8/8 手动点击被判 byUser=false，说明 12 帧窗口太浅）。
            val top = st.drop(2).take(24)
            for (f in top) {
                val c = f.className
                val m = f.methodName
                if (m == "performClick" || m == "onClick" ||
                    m == "dispatchTouchEvent" || m == "onTouchEvent" || m == "onTouch" ||
                    m == "onSingleTapUp" || m == "onSingleTapConfirmed" ||
                    m == "onItemClick" || m == "onItemSelected" ||
                    c.contains("InputEventReceiver") || c.contains("MotionEvent") ||
                    c.contains("GestureDetector") || c.contains("TouchListener") ||
                    c.contains("ItemClickListener")
                ) return true
            }
            // 栈顶出现定时器/消息派发特征 → 明确判为自动
            for (f in top) {
                val m = f.methodName
                if (m == "dispatchMessage" || m.contains("handleMessage") ||
                    f.className.contains("Timer") || f.className.contains("CountDownTimer") ||
                    f.className.contains("ScheduledExecutor") ||
                    (m == "run" && f.className.contains("$"))
                ) return false
            }
            false
        } catch (_: Throwable) { false }
    }

    internal fun hookBlockAutoLive(xp: XposedInterface) {
        try {
            val actCls = Class.forName("android.app.Activity", false, null)
            val hooked = java.util.concurrent.atomic.AtomicInteger(0)
            for (m in actCls.declaredMethods) {
                val mn = m.name
                if (mn != "startActivity" && mn != "startActivityForResult" && mn != "startActivityIfNeeded" && mn != "startNextMatchingActivity") continue
                Logger.safe("blkAutoLive.$mn") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("blkAutoLive.$mn").intercept { chain ->
                        try {
                            if (Prefs.bool(Prefs.K_PB_NO_AUTO_LIVE, false)) {
                                // Intent.getComponent().getClassName()：用直接反射取，不依赖 Reflect.callMethod 的链式返回
                                val it0 = chain.args.getOrNull(0)
                                var cn: String? = null
                                if (it0 != null && it0.javaClass.name.contains("Intent")) {
                                    val comp = try { it0.javaClass.getMethod("getComponent").invoke(it0) } catch (_: Throwable) { null }
                                    if (comp != null) {
                                        cn = try { comp.javaClass.getMethod("getClassName").invoke(comp) as? String } catch (_: Throwable) { null }
                                    }
                                }
                                if (cn != null && AUTO_LIVE_ACT.any { it == cn }) {
                                    CfhState.autoLiveBlocked++
                                    // ★★ 调用栈判别（2026-09 用户反馈「手动点击也被拦」）：
                                    // 手动点击的栈顶含输入事件链，自动跳转来自定时器/Handler。
                                    // 前 8 次无论拦不拦都打栈，便于核对判据是否符合实际。
                                    val byUser = isUserInitiatedStack() || isLikelyUserTapByTime()
                                    if (CfhState.autoLiveBlocked <= 8) {
                                        // 打全栈（不截断）——上一版只打 12 帧且用 \n 拼接，
                                        // 日志里没能留下可读栈，无法判定手动路径到底长什么样。
                                        // 改为「一帧一行」的 always 输出，确保 logcat 完整保留。
                                        val st = Thread.currentThread().stackTrace
                                        Logger.always("AUTOLIVE probe #${CfhState.autoLiveBlocked} byUser=$byUser frames=${st.size} -> $cn")
                                        for ((fi, fr) in st.withIndex()) {
                                            if (fi > 26) break
                                            Logger.always("AUTOLIVE   [$fi] ${fr.className}.${fr.methodName}:${fr.lineNumber}")
                                        }
                                    }
                                    if (byUser) {
                                        if (CfhState.autoLiveBlocked <= 20) Logger.always("AUTOLIVE pass (user tap) -> $cn")
                                        return@intercept chain.proceed()
                                    }
                                    if (CfhState.autoLiveBlocked <= 20) Logger.always("AUTOLIVE blocked (auto) -> $cn")
                                    return@intercept null
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
                hooked.incrementAndGet()
            }
            Logger.always("hookBlockAutoLive installed=$hooked (switch=${Prefs.bool(Prefs.K_PB_NO_AUTO_LIVE, false)})")
        } catch (t: Throwable) { Logger.always("hookBlockAutoLive fail: ${t.message}") }
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
