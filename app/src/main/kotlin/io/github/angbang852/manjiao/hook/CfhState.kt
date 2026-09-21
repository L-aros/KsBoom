package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第二步/状态收敛（2026-09 S3）：全量可变状态与运行时
// 基础设施（handler/执行器/缓存/计数器/引用/队列）收敛为单一单例。
// 判定 CfhDecide / 清洗 CfhClean / 捕获 CfhCapture / 诊断 CfhDiag 与主钩子文件
// 共享同一份状态——任何对象不得自行持有副本，避免多份状态漂移。
object CfhState {
    val handler = Handler(Looper.getMainLooper())
    @Volatile var tracked: Activity? = null
    var lastSkipTime = 0L
    @Volatile var xpRef: XposedInterface? = null
    @Volatile var clRef: ClassLoader? = null
    @Volatile var vmRef: Any? = null
    @Volatile var adpRef: Any? = null
    val adpRefs = java.util.Collections.synchronizedList(mutableListOf<Any>())
    var elemDumped = false
    @Volatile var qpClassRef: Class<*>? = null
    val hookedDsClasses = mutableSetOf<String>()
    @Volatile var liveTop = false
    var krnProbeHooked = false
    var krnProbeRetries = 0
    var krnProbeCount = 0
    var krnRcvHooked = false
    var krnRcvRetries = 0
    var krnLmDiag = 0
    val hookedRerankCls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    var rerankJDiag = 0
    var rerankScrollDiag = 0
    var rerankListDiag = 0
    var rerankSingleDiag = 0
    var plistSkipDiag = 0
    var lastAdpSelfFix = 0L
    val hookedPageLists = mutableSetOf<String>()
    var knhbT0Hooked = false
    var knhbT0Diag = 0
    var knhbCallDiag = 0
    @Volatile var bootFlushDone = false
    @Volatile var bootFlushPending = false
    // ★ 启动期真源清洗前移的一次性守卫（2026-09-21）：首次 T0/E1 后武装，避免重复起重试循环
    @Volatile var earlyWashArmed = false
    var knhbInst: java.lang.ref.WeakReference<Any>? = null
    val seenPhotoIds = LinkedHashSet<String>()
    val photoIdCache = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Any, String?>())
    val pidMCache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()
    val pidMNeg = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>())
    var dedupeDiag = 0
    var dedupeProbe = 0
    val dedupeStarve = java.util.concurrent.atomic.AtomicInteger(0)
    val hookedMilanoContainers = mutableSetOf<String>()
    var liveCtorDiag = 0
    val liveCtorHookedCls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    @Volatile var laRef: Any? = null
    @Volatile var laWatchArmed = false
    val laLogN = java.util.concurrent.atomic.AtomicInteger(0)
    val retDelQp = java.util.Collections.synchronizedList(ArrayList<Any?>())
    @Volatile var laFindAt = 0L
    val laFindPending = java.util.concurrent.atomic.AtomicBoolean(false)
    var truesrcCapDiag = 0
    val trueListRefs = java.util.concurrent.CopyOnWriteArrayList<Any>()
    val trueWatchIds = java.util.concurrent.CopyOnWriteArrayList<Int>()
    val hookedTrueCls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    val trueListLogN = java.util.concurrent.atomic.AtomicInteger(0)
    var lastPagerSearch = 0L
    @Volatile var lastViewQp: Any? = null
    var aiFullDumpCount = 0
    @Volatile var visiblePhotoRef: java.lang.ref.WeakReference<Any>? = null
    val posPhotoMap = java.util.LinkedHashMap<Int, java.lang.ref.WeakReference<Any>>()
    val liveSlideFragments =
    java.util.Collections.newSetFromMap(java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>()))
    @Volatile var lastVisibleFragRef: java.lang.ref.WeakReference<Any>? = null
    @Volatile var lastCaptureTrusted = false
    val visRing = java.util.ArrayDeque<CfhCapture.VisEntry>()
    @Volatile var realFragClass: Class<*>? = null
    val fragSeqHookedClasses = mutableSetOf<String>()
    val fragSeqCount = java.util.concurrent.atomic.AtomicInteger(0)
    var vpBlockDiag = 0
    var gqDumpCount = 0
    val fragSetterHooked = mutableSetOf<String>()
    @Volatile var inFindClean = false
    var lastClean: Any? = null
    var vmReplacedDiag = 0
    var offerDiag = 0
    var findCleanDiag = 0
    var vmRetNameDiag = 0
    var vmKeepDiag = 0
    val cleanQueue = ArrayDeque<Any>()
    val cleanCachePersist = ArrayDeque<Any>()
    // dirtyUrls / cleanUrlPool / hookedVmUrlClasses / urlSubCount / playerHookTried
    // 已随 CfhSwap 的 URL 替换子系统一并移除（2026-09-21）：非数据源拦截路线且从未生效
    @Volatile var dataDiag = 0
    var capturedLines = 0
    var liveDumpCount = 0
    var dramaDumpCount = 0
    val liveDumped = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
    val dramaDumped = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
    val capturedIds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
    var pagerCache: Any? = null
    var holderDumpCount = 0
    var vmGetSubCount = 0
    var vmMethodDump = 0
    var fragDiagCount = 0
    var fragMethodsDiag = false
    var fragFieldsDiag = false
    val hookedAdpClasses = mutableSetOf<String>()
    var feedPagerFound = false
    var feedPagerLogCount = 0
    var adpDumpCount = 0
    val hookedPagerCls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    var liveWindowDiag = 0
    var pagerDiag = 0
    var pagerSwapCount = 0
    val hookedProvClasses = mutableSetOf<String>()
    var provDiag = false
    var adpGetDiag = 0
    var adpGetLiveSkipDiag = 0
    var adpProvDiag = 0
    var adpQpDiag = 0
    var adpXDump = 0
    var adpXLiveZapDiag = 0
    // adpXRedirectDiag 已随「脏页位置重定向」死代码一并移除（2026-09-21）：从未生效
    val adpXDumped = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    var adpSelfDumped = false
    var adpGetSwapIn = false
    val methodCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()
    val dirtyEntSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val hookedVmClasses = mutableSetOf<String>()
    @Volatile var lretDiagCount = 0
    var vmYDiag = 0
    var vmListDiagDone = false
    var vmListElDump = 0
    var vmAllFieldsDumped = false
    @Volatile var lastViewSig = ""
    @Volatile var lastFilterVmListsAt = 0L
    @Volatile var vmRefProbeDone = false
    val cleanExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "ManJiaoClean").apply { isDaemon = true }
}
    val pendingCleanObj = java.util.concurrent.atomic.AtomicReference<Any?>()
    val cleanDrainArmed = java.util.concurrent.atomic.AtomicBoolean(false)
    var filterResultDiag = 0
    var fltCallerDiag = 0
    val retThrottle = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    var lastRefreshTime = 0L
    var lastAllDirtyRefreshAt = 0L
    var respFieldDiag = 0
    var respFieldCallDiag = 0
    var lastLoadMoreTime = 0L
    var adpListDumped = false
    var feedDiagCount = 0
    var lastFeedDiagSig = ""
    @Volatile var lastFeedDiagAt = 0L
    var nonVfDiagCount = 0
    var weakUnwrapDiag = 0
    var liveDiagCount = 0
    var movieDiagCount = 0
    var entFullDumpCount = 0
    var entScanDiag = 0
    var entFullProbeCount = 0
    var liveFieldDiag = 0
    var anyOnCache = false
    var anyOnAt = 0L
    val sigIdCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    val feedFilterCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
    val contentFilterCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
    var fcacheDiag = 0
    val filterHitStats = java.util.concurrent.ConcurrentHashMap<String, Int>()
    var hitLogDiag = 0
    var quickDebugCount = 0
    var hitTotal = 0
    val feedSigCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val contentSigCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val asyncDecidePending = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    // ★ 可见脏项豁免计数（CfhPurge）：记「当前被可见性保护跳过的那个脏对象」及其次数。
    // 同一对象累计超过上限后不再豁免，避免首屏脏项（如开头的 AI 视频）永久在屏。
    @Volatile var visibleSkipOwner: Any? = null
    @Volatile var visibleSkipCount = 0
    /** 模块装钩时刻（进程起点近似值）：启动窗判定用（如 CfhSupply 的 in-flight 阈值收紧） */
    @Volatile var processStartAt = 0L
    /** 启动窗位置探针计数（CfhViewHook.installAdpGet）：定位「第 N 条为何漏网」 */
    @Volatile var adpPosProbe = 0
    /** 上一轮真源清洗实际删掉的条数（CfhWash）：0=已收敛，>0=需立即再清（不被 500ms 节流饿死） */
    @Volatile var lastCleanRemoved = 0
    /** rerank 源头拦截命中计数（CfhViewHook：e$d.G / d.m / e.doInject 拒绝 LiveStreamFeed） */
    @Volatile var rerankInjectBlocked = 0
    /** 自动进入直播间拦截计数（CfhLcHook.hookBlockAutoLive） */
    @Volatile var autoLiveBlocked = 0
    /**
     * 脏项 photoId 黑名单（判脏即入，见 CfhDecide.hit）。
     * 用途：同一条内容可能被多条引用持有（数据源列表 / rerank 快照 / pager adapter /
     * Fragment 字段），从数据源删除删不到其余引用，它会以「另一条」的形式重回屏幕。
     * 实证 probe11：删除在 44.748，屏幕渲染在 46.665，二次判脏在 47.073（迟 0.4s）。
     * 记 id 后任何路径再遇即毫秒级判脏，消除该窗口。
     * 上限 512 防无界增长（启动期脏项密度最高，512 足够覆盖一次会话的可见窗口）。
     */
    val dirtyPhotoIds: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
}
