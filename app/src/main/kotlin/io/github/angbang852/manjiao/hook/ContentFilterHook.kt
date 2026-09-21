package io.github.angbang852.manjiao.hook

import android.app.Activity
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第五步：收口为入口协调器 + 对外 API 转发（2026-09 S3）。
// 装钩按三层外移：CfhLcHook（生命周期）/CfhFeedHook（数据源）/CfhViewHook（渲染层）。
object ContentFilterHook {
    // shouldFilterMeta 用（每 10s 一次）：Regex 预编译，不随调用重建


    // ★ 直播页上下文放行：精选 tab 的过滤/删除链路（filterListArgs/laFind/TRUEDEL/zap）
    // 对直播页（LiveSlideActivity 等 com.kuaishou.live.* 页面）是灾难——直播页与精选容器
    // 共享数据引用（Ip() 从 slideplay 容器取 items），删共享列表/zap 直播实体字段会把
    // 直播页 pager 掏空或打成空壳 → 直播间黑屏、滑不动、底栏切换失效（实证 07:51 del=1 left=0 后卡死）。
    // 直播页在前台时：所有内容判定放行（shouldFilterFeed=false）、构造 zap 跳过。
    // 非快手主包 Activity（系统弹窗等）不改变状态，防弹窗期间误恢复过滤。

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        CfhState.xpRef = xp
        CfhState.clRef = cl
        // ★ 进程起点（供启动窗判定用）：CfhSupply.triggerLoadMore 的 in-flight 等待阈值
        // 在启动窗内收紧（5s→1.2s），依据就是「now - processStartAt」。
        CfhState.processStartAt = System.currentTimeMillis()
        // ★ 启动期身份前置（2026-09-21 实测修复）：qpClassRef 原先**只**由 hookViewModel
        // 赋值（约启动后 8 秒、全屏详情页起来时），而首批数据约 6 秒就进数据源 —— 那 1.1~2 秒
        // 窗口内 qpClassRef 为 null，判脏链路的 QPhoto 解包（findQpInObject）与实体类型判定
        // 整体失灵 → 开头几条被判"干净"放行（实测时间轴：32.8s 首批 6 条 del=0，
        // 33.5s hookViewModel 才赋值，34.8s 才首次命中）。
        // 此处用 hook() 已有的 app 类加载器就地解析，把身份建立提前到装钩时刻。
        try {
            CfhState.qpClassRef = Class.forName("com.yxcorp.gifshow.entity.QPhoto", false, cl)
            Logger.always("QPHOTO identity preloaded (boot window covered)")
        } catch (t: Throwable) { Logger.d("QPHOTO preload fail: ${t.message}") }
        CfhLcHook.hookActivityLifecycle(xp)
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
                try { CfhLcHook.startTrack(chain.thisObject as Activity) } catch (_: Throwable) {}
                null
            }
            val mOff = Reflect.findMethod(c, "onPause", 0)
            if (mOff != null) xp.hook(mOff).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("filter.off.$a").intercept { chain ->
                chain.proceed()
                try { CfhLcHook.stopTrack(chain.thisObject as Activity) } catch (_: Throwable) {}
                null
            }
            Logger.d("FilterHook on $a")
        }
        CfhLcHook.hookNasaFragment(xp, cl)
        CfhFeedHook.hookFeedResponse(xp, cl)
        CfhFeedHook.hookCacheClasses(xp, cl)
        CfhFeedHook.hookMilanoContainers(xp, cl)
        CfhFeedHook.hookPageLists(xp, cl)
        CfhViewHook.hookLiveRerank(xp, cl)
        CfhFeedHook.hookKnhbT0(xp, cl)
        CfhFeedHook.hookLiveFeedConstruct(xp, cl)
        CfhViewHook.hookKrnProbe(xp, cl)
        CfhViewHook.hookKrnReactContainerView(xp, cl)
        CfhLcHook.hookFragmentCrashGuard(xp, cl)
        // ★ 启动期配置转储（2026-09-21）：排除"开关状态靠猜"——每次冷启动把生效的
        // 过滤/播放/装饰开关一次性打进日志（Logger.always 不受静默影响）。
        // 排障时先看这一行，就知道当日判定该命中哪些规则。
        try {
            fun b(k: String, d: Boolean = false) = if (Prefs.bool(k, d)) "1" else "0"
            Logger.always(
                "FLTCFG ads=${b(Prefs.K_FLT_ADS)} advideo=${b(Prefs.K_FLT_ADVIDEO)} image=${b(Prefs.K_FLT_IMAGE)} " +
                    "live=${b(Prefs.K_FLT_LIVE)} ai=${b(Prefs.K_FLT_AI)} ec=${b(Prefs.K_FLT_EC)} " +
                    "drama=${b(Prefs.K_FLT_DRAMA, true)} like_on=${b(Prefs.K_FLT_LIKE_ON)} " +
                    "kw_on=${b(Prefs.K_FLT_KW_ON)} kw=[${Prefs.str(Prefs.K_FLT_KEYWORDS, "").take(40)}] " +
                    "nomore=${b(Prefs.K_FLT_NOMORE, true)} bootflush=${b(Prefs.K_FLT_BOOTFLUSH, true)} | pb_noLoop=${b(Prefs.K_PB_NO_LOOP)} " +
                    "pb_bgPause=${b(Prefs.K_PB_BG_PAUSE)} gold=${b(Prefs.K_IMM_GOLD)} diag=${b(Prefs.K_DIAG)} " +
                    "quiet=${b(Prefs.K_PERF_QUIET, true)}"
            )
        } catch (_: Throwable) {}
    }


    // ==================== 对外 API 转发（职责拆分后调用点不变） ====================
    fun invalidateFilterCache() = CfhDecide.invalidateCaches()
    fun refreshContent(): Boolean = CfhSupply.refreshContent()
    fun currentFeedPhoto(): Any? = CfhCapture.currentFeedPhoto()
    fun currentFeedFragment(): Any? = CfhCapture.currentFeedFragment()
    fun isCaptureTrusted(): Boolean = CfhCapture.isCaptureTrusted()
    fun findPhotoById(pid: String): Any? = CfhCapture.findPhotoById(pid)
    fun visibleEntries(): List<CfhCapture.VisEntry> = CfhCapture.visibleEntries()
    fun readVisibleUserName(qp: Any): String = CfhCapture.readVisibleUserName(qp)
}
