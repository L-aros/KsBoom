package io.github.angbang852.manjiao.hook

import android.app.Activity
import io.github.angbang852.manjiao.KsClass
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
