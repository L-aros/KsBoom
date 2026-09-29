package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.angbang852.manjiao.util.Logger
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

    // ★★★ v13.43 主页（发现页）数据源引用（2026-09-30 用户方案「池子少于50就一直拉取」）：
    //   ## 为什么单独存主页引用
    //   vmRef 是**当前页面**的 SlidePlayViewModel —— 用户在精选页时它就是精选页的 VM，
    //   拉回来的是精选页的 AI 短剧脏池（v13.18 实证：dnh.q1 的 load() 还会锁死请求器）。
    //   用户方案要的是「看主页的数据从哪来的，就从那里拉」⇒ 必须持有**主页自己的**
    //   VM / DataSource 引用，与当前在哪个页面无关。
    //   ## 赋值点
    //   CfhFeedHook 首页 Fragment 分支（NASA）：找到 SlidePlayViewModel + getDataSource()
    //   ⇒ homeVmRef / homeDsRef。只在主页 Fragment 出现时覆盖。
    @Volatile var homeVmRef: Any? = null
    @Volatile var homeDsRef: Any? = null

    // ★★★ v13.58 首页数据源**强引用**（2026-09-30 用户定调「发现页始终能刷出
    //   新视频，精选页刷不出就不对」——追这个差异追到的断点）：
    //   ## 断在哪
    //   两条通道是分开的（CfhFeedHook:473-483 注释里早就写明）：
    //     · 首页发现页 → knhb 的 **T0 / E1** 通道（knhbInst 在这里被赋值）
    //     · 精选页     → **Gson CollectionTypeAdapter（GSCOLL）** 通道
    //   而 refillFromCleanPool / triggerSafeLoadMore 拿的是 `knhbInst`
    //   ⇒ **在精选页时它必然是 null**（精选页根本不走 knhb）⇒ 整条拉取链断。
    //   v13.57 实测铁证：`REFILL-DIAG knhbInst=null，等待数据源出现`。
    //   ## 为什么还要单独存强引用
    //   `knhbInst` 是 **WeakReference** —— 只在首页活着时有效，用户一离开首页
    //   就可能被 GC 清掉，精选页再拉就是 null。
    //   ## 赋值点
    //   CfhFeedHook 的 T0/E1 分支：`chain.thisObject` **就是**首页数据源本体。
    //   只存一次（首次非空），之后不覆盖，避免被别的页面实例顶掉。
    @Volatile var homeSrcStrong: Any? = null
    // ★★★ v13.45 主页拉取时间窗（2026-09-30 用户质问「按首页的方法拉，数量不该一样么」）：
    //   ## 问题
    //   v13.44 撤销网络层泛登记后只指望 HOMEPOOL（HomeFeedResponse），但实测
    //   `HOMEPOOL 批size=0` —— **拉回来的数据不走 HomeFeedResponse 分支**，
    //   而网络层明明收到了干净批次（TTPPARSE-STAT 收=6 放行=6 挡下=0）⇒
    //   干净内容两头落空，池永远是 0。
    //   ## 修复：时序关联
    //   triggerHomeLoad 成功发起后，开一个短窗（默认 3s）。**窗口内**到达
    //   网络层且判 WHITE 的内容 = 本次主页拉取的响应 ⇒ 登记进池；窗口外
    //   不登记（关注页/同城页/详情页的自然流量因此不会污染池）。
    //   窗口收紧到 3s，避免用户在窗口内滑别的页面造成误收。
    @Volatile var homePullWindowUntil = 0L
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
    // ★★★ v13.38 删除「主动拉首页数据」全部状态（2026-09-30 用户定案）：
    //   池是共享的，首页内容本就自动进池 ⇒ 无需主动拉。已删：
    //   homeReqRef / pendingHomeReq（首页请求器配对晋升）/ homeDsRef（数据源实例）
    //   + triggerHomeLoadMore 函数本体 + 两处调用点（池空分支、池水位）。
    //   仅保留以下诊断计数器（零成本，日志历史可读）。
    var homeReqLog = 0
    var homeReqDiag = 0
    var lastHomeLoadTime = 0L
    val seenPhotoIds = LinkedHashSet<String>()
    val photoIdCache = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Any, String?>())
    val pidMCache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()
    val pidMNeg = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>())
    var dedupeDiag = 0
    var dedupeProbe = 0
    val dedupeStarve = java.util.concurrent.atomic.AtomicInteger(0)
    val hookedMilanoContainers = mutableSetOf<String>()
    /** AI 标签字段自证计数（限次，见 CfhUtil.dumpAiTagField） */
    var aiTagProbeCount = 0
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
    /** FRAGSET-BLOCK 落盘限次（详情页 Fragment setter 脏 QPhoto 参数置 null） */
    @Volatile var fragSetBlockLog = 0
    /** PMETA-BLOCK 落盘限次（PhotoMeta AI 声明→写黑名单） */
    @Volatile var pmBlockLog = 0
    /** FRAGSEQ-BLOCK 落盘限次（fragment 通用参数判脏置 null） */
    @Volatile var fragSeqBlockLog = 0
    /** NQPDEL 落盘限次（嵌套 QPhoto 列表清洗，vm.h.f.o 数据源） */
    @Volatile var nestedQpDelCount = 0
    /** EPISODE-PEND 落盘限次（第N集形态 AI 未确认 → PENDING） */
    @Volatile var episodePendLog = 0
    /** GSCOLL 落盘限次（Gson 原生列表容器 CollectionTypeAdapter.read） */
    @Volatile var gsCollLog = 0
    /** 安全续拉 load() 成功落盘限次（CfhSupply.triggerSafeLoadMore） */
    @Volatile var safeLoadMoreLog = 0
    /** 安全续拉诊断落盘限次（inst/target/hasMore/isLoading 各类跳过原因） */
    @Volatile var safeLoadMoreDiag = 0

    // ==================== ★★★ 干净内容共享池（2026-09-28「首页池接精选页」用户方案）====================
    //
    // ## 为什么建池
    //
    // 用户实测：**首页发现页干净内容多、拉取快；精选页半天才刷出一个**。
    // 两池不同源：首页走 stag ListTypeAdapter（内容偏正常），精选页走 Gson
    // CollectionTypeAdapter（内容池基本是 AI 短剧）⇒ 精选页每批 6-9 条几乎全拦
    // ⇒ 列表空 ⇒ 快手拉下一批还是脏 ⇒ 转圈。
    //
    // ## 方案（用户原话「把首页发现的内容池接到精选页上」→ 安全等价实现）
    //
    // 网络层共享同一个 scrubIfQpList，无法区分首页/精选页 —— 也不必区分：
    // **任何一批判 WHITE 的干净 QPhoto 都登记进池**；任何一批清洗后
    // 「剩余 ≤2 且拦掉 ≥4」（池空/近空，主要命中精选页）就从池里
    // **借最近 WHITE 的干净内容补位**（add 回网络层列表）。
    //
    // ## 安全性
    //
    // · 只操作**网络层 Gson 列表**（shuffle 前），不碰 VM/ViewPager 数据源
    //   ⇒ 无「Expected=1000000」崩溃风险（那是操作 VM 数据源的历史教训）。
    // · 池存的是刚判 WHITE 的 QPhoto 引用，补位内容本身就是干净的。
    // · photoId 去重 + 补位排除本批已有 ⇒ 不重复灌。
    // · 上限 100 防内存膨胀，满删最旧。
    //
    // ## 语义修正
    //
    // 初版思路「选页空 → triggerSafeLoadMore 续拉」实测仅 5 次成功且拉回来
    // 的还是脏池（REFILL-DIAG 全是 isLoading=true 挡下）⇒ 续拉无济于事。
    // 池补位是真正解决「干净内容稀缺」的手段。
    val cleanPool: MutableList<Any> = java.util.Collections.synchronizedList(ArrayList())
    /**
     * ★★★ v13.49 池去重表改为「pid → 入池时间戳」（2026-09-30 用户质问
     * 「什么池小？你不是说100的池子么」）：
     *
     * ## 为什么改
     * 原实现是 `ConcurrentHashMap<String, Boolean>` = **永久标记**：
     * 内容入池一次后，即使被精选页补位取走，标记**永不失效** ⇒
     * 用户滑发现页时，快手推的看过的内容全部被判 DUP 拒绝
     * （POOLREJ[DUP] 满屏实证）⇒ **池水位永远回不了血**（实测 0-10 条，
     * 而容量是 100）。用户看到的「100 的池子却这么大点」就是这个原因。
     *
     * ## 新语义：时间窗去重
     * · 窗口内（[REENTRY_WINDOW_MS]）同一 pid 不再入池 ⇒ 防「同屏/连续循环」
     * · 窗口外允许重新入池 ⇒ 池能回血，水位能涨回容量附近
     * 防循环的正确防线本来就是**补位层的 existing 去重**（同屏已有的不补），
     * 而不是「这条内容这辈子只能出现一次」。
     *
     * ★★★ v13.95 追加**双闸**（2026-10-01 用户规格：时间 30 天 + 条数 20000）：
     * 上面那句「窗口外允许重新入池」在当时**并没有真正生效** ——
     * `noteClean` 里还留着一处与时间无关的判定（`containsKey`）把它盖掉了，
     * 实测仍是永久拉黑（1312 条只增不减）。双闸见下方 [POOL_ID_KEEP_MS] 段落。
     */
    val cleanPoolIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ==================== ★★★ v13.95 去重表双闸（2026-10-01 用户规格）====================
    //
    // ## 为什么加闸
    // 去重表 `cleanPoolIds` 形同**永久拉黑**：表只增不减（真机实测 1312 条并持续增长）
    // ⇒ 快手推的看过的老内容全被判 DUP 拒绝 ⇒ 池回不了血。
    // 用户**已知情并接受**：过了保留期同一条内容**可能再次出现**。
    // 但底线不变 —— **去重表（绝不重复）永远优先于省空间**，
    // 所以保留期取 30 天（覆盖实际会重看的时间尺度），而不是几小时。
    //
    // ## 两道闸，先到先算（超过任一门即淘汰）
    // · 时间闸 [POOL_ID_KEEP_MS] = 30 天：`now - ts >= KEEP` 即过期淘汰
    // · 条数闸 [POOL_ID_MAX] = 20000：超限按**时间戳升序删最旧的**（FIFO）
    //
    // ## 触发（不设定时器、零新增线程）
    // ① 入池时（`noteClean` 内、markDirty 之前）—— 走**廉价闸**：
    //    `size > POOL_ID_MAX` 才做一次 O(n) FIFO 淘汰；时间闸每 [POOL_ID_SCAN_TICK]
    //    次入池扫一次。**不许每次入池都全表扫**（2 万条/次会拖慢热路径）。
    // ② `CfhPoolStore.loadNow()` 恢复 ids+合并之后、装条目之前 —— force=true 扫一次。
    //    次序很关键：`mergeIdsAlone` 只增不改，会把独立副本里已淘汰的 id 灌回来
    //    ⇒ **淘汰必须排在合并之后**。

    /** 时间闸：去重表条目保留期（30 天）。过期即淘汰 ⇒ 该内容允许再次出现 */
    // 人造验证遗留（曾被临时改成 60_000L），已还原为定稿值 30 天
    const val POOL_ID_KEEP_MS = 30L * 24 * 3600_000

    /** 条数闸：去重表上限（超限按时间戳升序删最旧的，FIFO） */
    // 人造验证遗留（曾被临时改成 100），已还原为定稿值 20000
    const val POOL_ID_MAX = 20000

    /** 时间闸扫描周期（入池次数）：每 256 次入池才做一次 O(n) 全表扫描 */
    private const val POOL_ID_SCAN_TICK = 256

    /** 入池计数（廉价闸用）：只在热路径做一次原子自增，无锁、无遍历 */
    private val poolIdTick = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * ★★★ v13.95 去重表淘汰（双闸：时间 30 天 / 条数 20000）。
     *
     * ## 为什么必须有（头号坑）
     * 只在 `noteClean` 里加双闸是**无效**的 —— 那里另有一处
     * `if (cleanPoolIds.containsKey(pid)) return false`（与时间无关）
     * 会**盖掉** 5 分钟窗口 [REENTRY_WINDOW_MS]，使实际行为退化成「永久拉黑」。
     * 本次已把它改成按时间戳判断（见 [noteClean] 内）。
     *
     * ## 两层去重语义（别混）
     * · 5 分钟窗口 = 防「同屏/连续循环」（保持不动）
     * · 30 天保留   = 跨会话不重放（本次新增）
     *
     * ## 零戳条目
     * `ts <= 0` 的条目年龄无法判断（旧格式），一律按**过期**淘汰并单独计数。
     * 正常路径不会出现：三条恢复路径都会把零戳补成**加载时刻**
     * （见 `CfhPoolStore` 的 `idsFix`；**不能用文件 mtime**，那会高估旧条目新鲜度）。
     *
     * @param force true = 无视廉价闸直接扫（恢复路径专用）
     * @return 淘汰条数
     */
    fun prunePoolIds(force: Boolean = false): Int {
        val now = System.currentTimeMillis()
        // ★ 廉价闸：绝大多数调用在这里就返回 —— 不遍历、不分配（热路径零成本）
        val overCount = cleanPoolIds.size > POOL_ID_MAX
        val tickDue = poolIdTick.incrementAndGet() >= POOL_ID_SCAN_TICK
        if (!force && !overCount && !tickDue) return 0
        if (tickDue) poolIdTick.set(0)
        var aged = 0
        var zero = 0
        try {
            // 时间闸：ConcurrentHashMap 的迭代器**支持安全删除**（弱一致，不需要额外加锁）
            val it = cleanPoolIds.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val t = e.value
                if (t <= 0L) { it.remove(); zero++; continue }
                if (now - t >= POOL_ID_KEEP_MS) { it.remove(); aged++ }
            }
        } catch (_: Throwable) {}
        // 条数闸：**时间闸之后**再算（可能已经降到上限之下，白删一批是浪费）
        var over = 0
        try {
            val n = cleanPoolIds.size
            if (n > POOL_ID_MAX) {
                val need = n - POOL_ID_MAX
                val all = ArrayList<Map.Entry<String, Long>>(cleanPoolIds.entries)
                all.sortBy { it.value }   // 时间戳升序 ⇒ 最旧在前（FIFO）
                val cnt = minOf(need, all.size)
                for (i in 0 until cnt) {
                    val e = all[i]
                    // remove(k, v)：值被并发刷新过（期间又入池）就不删 —— 绝不误删新记号
                    if (cleanPoolIds.remove(e.key, e.value)) over++
                }
            }
        } catch (_: Throwable) {}
        val removed = aged + zero + over
        if (removed > 0 && (force || io.github.angbang852.manjiao.util.RateLimiter.allow("POOLIDPRUNE", 20))) {
            Logger.evidence(
                "POOLIDPRUNE",
                "★去重表淘汰 过期=$aged 零戳=$zero 超限=$over 剩余=${cleanPoolIds.size} " +
                    "(保留=${POOL_ID_KEEP_MS / 86400_000}天 上限=$POOL_ID_MAX force=$force)"
            )
        }
        return removed
    }

    /**
     * ★★★ v13.91 崩溃现场观测（用于定位 `PresenterV2 不能从 CREATE 跳到 UNBIND`）
     *
     * 崩溃发生在快手 `onBindViewHolder` 里（Presenter 状态机被推进到非法态）。
     * 目前**没有确认病因**，所以不再猜，改成让数据说话：给每次注入打上判别字段。
     *
     * · [servedPids]：**已经投放给 feed 过的** photoId。
     *   意义：若某条目被**第二次**注入（pid 已在集合里），就是「同一条重复上屏」，
     *   而重复 bind 正是 Presenter 状态机最容易崩的场景 ⇒ 这是头号嫌疑。
     * · [restoredPids]：来自**跨会话存档恢复**的 photoId。
     *   意义：存档条目是 Gson 反序列化回来的，**transient 字段不会被还原**，
     *   若 bind 依赖这些状态，恢复条目会绑出非法 Presenter 树 ⇒ 二号嫌疑。
     *
     * 崩溃会杀掉进程，所以这些数必须在**崩溃前就落盘** ——
     * 因此不在这里统计，而是直接打进 RENDERFILL/RESPFILL 的注入日志行。
     */
    val servedPids: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val restoredPids: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * v13.49 重新入池窗口：同一条内容在此窗口内不重复入池（防循环），
     * 窗口外允许回池（防水位饿死）。5 分钟 —— 用户滑一屏几秒，
     * 远小于窗口 ⇒ 不会看到重复；同时池能在几分钟内回满。
     */
    const val REENTRY_WINDOW_MS = 300_000L

    /**
     * ★★★ v13.74 出队兜底下限 = **0**（2026-09-30 用户定案「严格档」后改写）。
     *
     * ## 为什么从 12 改成 0
     * v13.71 原本保留 12 条不动，理由是「补货失败时不至于把页面打死」。
     * 但那是拿**重复**换**不断流** —— 池停在 12 时，`RESPFILL` 每次取的都是
     * **同样那 12 条**，正是用户反复抱怨的「循环刷一样的视频」。
     *
     * 用户 2026-09-30 明确选择：**绝不重复 > 不断流**。
     * 所以下限归零：池该被消费就消费干净，**见底时如实停下并记录**，
     * 而不是偷偷重放看过的内容。
     *
     * 见底不会白停 —— 池见底时 `SUPPLY` 会持续触发补货，而 v13.74 的池持久化
     * 已经先把「冷启动必然池空」这条死锁链从源头切断了。
     */
    const val POOL_FLOOR = 0

    /** v13.71 累计出队条数（池水位流失总量，与入池统计对照） */
    @Volatile var poolDrainTotal = 0

    /**
     * ★★★ v13.74 渲染出口出队限流时间戳（2026-09-30 严格档配套）。
     *
     * 渲染出口（`installVmListRet`）在构建一张卡时可能**被连续调用很多次**
     * —— v13.70 实测 35 秒内触发 120 次。如果每次都从池里出队，
     * 池会在几秒内被烧干净；而严格档下「烧干」等于**直接停**。
     * 所以把它压到接近人手滑动的节奏（≥1.2s 才允许出队一次）。
     * 日志里 `出队=0` 且池没动，多半就是被这个限流挡住了。
     */
    @Volatile var lastRenderDrainMs = 0L

    /**
     * ★★★ v13.81 渲染出口**被限流跳过注入**的次数（严格档回归修复的观测点）。
     *
     * 真机症状：同一段视频「隔了几屏又出现」。
     * 病根是「注入」与「出队」被拆开 —— 限流窗口内的那些调用照样注入、却不出队，
     * 条目留在池中 ⇒ 下一拍再注入一次 ⇒ 重复上屏。
     * 修复后这里每次 +1，就代表「少注入了一拍」（本来是会造成重复的那一拍）。
     */
    @Volatile var renderFillSkip = 0

    /**
     * ★★★ v13.79 快手**自己**的 Gson 实例（由 deserializer 钩子捕获）。
     *
     * 为什么必须有它、不能自己 `new Gson()`：
     * 真机实证 —— 用默认 Gson 反序列化池存档里的 QPhoto 直接炸：
     * ```
     * ★★恢复条目失败 cls=com.yxcorp.gifshow.entity.QPhoto
     *   err=java.lang.RuntimeException
     *   msg=Failed to invoke public com.kwai.framework.model.feed.BaseFeed() with no args
     * ```
     * 即快手的模型类**无参构造一调用就抛异常** ⇒ 快手自己必然注册了
     * InstanceCreator / TypeAdapterFactory 才解得出自己的模型，光板 Gson 没有这些。
     *
     * 拿法：`JsonDeserializer.deserialize(JsonElement, Type, Gson)` 的**第 3 个参数
     * 就是 Gson 本体**（模块 `CfhTtpParse` 第 384 行的注释已确认），
     * 而 QPhoto 诞生钩子 `QPhotoDeserializer.deserialize` 每次都会经过。
     */
    @Volatile var appGson: Any? = null

    /** v13.79 探测 args[2] 到底是不是 Gson 的日志计数 */
    @Volatile var gsonProbeLog = 0

    fun noteAppGson(o: Any?) {
        if (o == null || appGson != null) return
        val g = try { digGson(o, 0, java.util.IdentityHashMap()) } catch (_: Throwable) { null }
        if (g == null) return
        appGson = g
        Logger.evidence(
            "POOLGSON",
            "★已捕获快手自己的 Gson：${g.javaClass.name}（入口=${o.javaClass.name}）"
        )
    }

    /**
     * ★★★ v13.79 顺着字段挖出真正的 Gson 实例。
     *
     * 真机实证：`deserialize(JsonElement, Type, Gson)` 传进来的第 3 个参数
     * **不是 Gson 本体**，而是 ——
     * ```
     * ★args[2] 不是 Gson：com.google.gson.internal.bind.TreeTypeAdapter$b
     * ```
     * `TreeTypeAdapter$b` 就是 Gson 源码里的 `GsonContextImpl`（R8 把内部类名改成了 `b`）。
     * 它是**非静态内部类** ⇒ 持有 `this$0` 指向 `TreeTypeAdapter`，
     * 而 `TreeTypeAdapter` 上就有 `gson` 字段。所以往下挖两层即可。
     *
     * **只深入 `com.google.gson.*` 的类**：避免误入业务对象图（QPhoto 巨大且可能成环）。
     */
    private fun digGson(
        o: Any?,
        depth: Int,
        seen: MutableMap<Any, Boolean>
    ): Any? {
        if (o == null || depth > 3) return null
        val c = o.javaClass
        if (c.name.startsWith("com.google.gson.Gson")) return o
        // 只沿 Gson 自己的类往下走
        if (!c.name.startsWith("com.google.gson.")) return null
        if (seen.put(o, true) != null) return null
        var k: Class<*>? = c
        var lvl = 0
        while (k != null && k != Any::class.java && lvl < 4) {
            for (f in k.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(o) ?: continue
                    val r = digGson(v, depth + 1, seen)
                    if (r != null) return r
                } catch (_: Throwable) {}
            }
            k = k.superclass
            lvl++
        }
        return null
    }
    /**
     * ★★★ v13.29 反循环时间窗（2026-09-30 用户「不管池子几条都不该循环刷一样的视频」）：
     * 循环根因 = refill 补位取走内容时 cleanPoolIds.remove(cpid) 抹掉了去重标记，
     * 首页同一列表对象每次被访问 noteCleanAll 看到同样内容又能重新入池 ⇒
     * 池永远被同样几条填满 ⇒ 补位永远补同样几条 ⇒ 用户滑到哪都是它们。
     * 修复双保险：
     *   ① noteClean 入池前查 recentPoolTs：同一 photoId 在窗口内（默认 5 分钟）
     *     不允许再次入池（内容被补位取走≠可重新入池）
     *   ② refill 补位成功不再 remove cleanPoolIds（保留永久去重标记）
     * 效果：池只装「真正的新内容」，循环从根上断掉。
     */
    val recentPoolTs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile var recentPoolWinSec = 20L
    @Volatile var cleanPoolLog = 0
    @Volatile var cleanPoolRefillLog = 0
    /** 首页登记池水位诊断计数（v13.19b） */
    @Volatile var cleanPoolDiagLog = 0
    /** 渲染层补位打点限次（v13.25 RENDERFILL） */
    @Volatile var renderRefillLog = 0
    /** 渲染层 WHITE 直收进池计数（v13.30，用户「首页干净内容直接入池」） */
    @Volatile var renderFeedRegCount = 0
    /** 渲染层 WHITE 直收证据打点限次（v13.30b） */
    @Volatile var renderFeedRegLog = 0
    /** 池水位<3 自动拉打点限次（v13.32，用户「池子少于3就该赶紧拉」） */
    @Volatile var poolLowLog = 0
    /** 池内容 dump 打点限次（v13.34，用户「一个作者几条视频？」—— dump 池作者分布） */
    @Volatile var poolDumpLog = 0
    /** GSCOLL 放行处登记进池的调用计数（v13.19，50388 首页通道） */
    @Volatile var ttpPassRegLog = 0
    /** V2 声明字段探针计数（v13.23 误拦诊断） */
    @Volatile var v2ProbeLog = 0

    /**
     * WHITE 内容登记进干净池（判干净即入，photoId 去重，上限 100）。
     * ★ 只登记**直接 QPhoto 实例**（qpClassRef 可判时）—— 保证池里元素
     *   类型单一，空列表补位时能安全 add 进 List<QPhoto>，绝不 ClassCastException。
     * @return true 表示实际入池（新内容）
     */
    /**
     * ★★★ v13.39 入池拒绝埋点（2026-09-30 用户「滑不出来」诊断）。
     * 原因：TYPE/TYPE2 类型不符 · NOPID 读不到 photoId · DUP 已入过池 ·
     * AUTHORn 同作者已 n 条。各原因独立限次，避免日志洪泛。
     */
    @Volatile private var rejLogTotal = 0
    /** v13.42 成功入池累计（与 rejLogTotal 对照算采集效率） */
    @Volatile private var poolAddOk = 0
    /** v13.56 渲染列表构成诊断限次（独立于 vmShowDiagLog —— 后者会被 VMSHOW-DIAG 抢光） */
    @Volatile var renderDiagLog = 0

    /** v13.59 首页批次入池日志限次（CfhFeedHook knhb T0/E1 登记） */
    @Volatile var homeRegLog = 0

    /** v13.60 数据源列表整表诊断限次（CfhFeedHook installVmList） */
    @Volatile var vmListDiag = 0

    /** v13.61 渲染出口补位累计条数（CfhFeedHook installVmListRet） */
    @Volatile var renderFillCount = 0
    /** v13.61 渲染出口补位日志限次 */
    @Volatile var renderFillLog = 0

    /** v13.63 首页渲染入池日志限次（PresenterBindHook） */
    @Volatile var pv2PoolLog = 0
    /** v13.64 自动拉取（模拟首页滑到底）节流时间戳 */
    @Volatile var lastNudgeMs = 0L
    /** v13.65 补池触发留痕限次（CfhSupply.ensurePoolSupply 入口） */
    @Volatile var supplyDiag = 0
    /** v13.65 首页列表定位失败留痕限次 */
    @Volatile var nudgeDiag = 0
    /** v13.66 按下标取数入池留痕限次（CurrentPhotoHook.harvestByIndex） */
    @Volatile var idxHarvestLog = 0

    // ★★★ v13.61 首页通道活跃时间戳（2026-09-30 渲染出口补位的页面判据）：
    //   ## 为什么需要
    //   补位要补在**渲染出口**（installVmListRet 返回的、直接给 adapter 的列表），
    //   但用户硬要求「**首页发现页零补位**」—— 补错页面会污染首页。
    //   ## 判据
    //   首页发现页的数据走 knhb T0/E1 通道，精选页走 GSCOLL。
    //   ⇒ 只要记录「最近一次 T0/E1 的时间」，就能区分当前在哪：
    //     · 3 秒内出现过 T0/E1 → 在首页 → **不补位**
    //     · 否则 → 不在首页 → 允许补位
    @Volatile var lastHomeChannelMs = 0L
    /**
     * ★★★ v13.94 **「最近一次页面数据来自哪条通道」**（2026-09-29 用户定案改造）
     *
     * 问题：原先判「在不在首页」只靠 [lastHomeChannelMs] 的 **3 秒时间窗**。
     * 而该时间戳**唯一写入点**是 `CfhFeedHook` 里 T0/E1 通道被调用时 ——
     * 它记的是「最近一次首页**数据请求**」，**不是播放/滑动/触摸心跳**。
     * ⇒ 停在首页看视频 3 秒不发数据请求，判据就失效，
     *   「首页发现页零补位」这条**硬规则**会漏，也会在用户正看首页时推动它的列表。
     *
     * 修法：改用**通道身份**（无时间衰减）—— 只要最近一次页面数据不是 T0/E1，就不算在首页；
     * 时间窗**保留并列作兜底**（`||`，不是取代），所以保护只会更强不会更弱。
     * 通道事实来自既有结论（`CfhState` 干净池注释）：
     *   首页发现页 → knhb **T0/E1**；精选页 → **GSCOLL**。
     *
     * 初值 false = 与改造前冷启动行为一致（那时 lastHomeChannelMs=0 同样算「不在首页」）。
     */
    @Volatile var lastChannelWasHome = false

    /** v13.47 noteCleanAll 日志限次 */
    @Volatile private var poolAllLog = 0
    private val rejLogByReason = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun noteCleanRej(reason: String, qp: Any) {
        if (rejLogTotal >= 120) return
        val key = reason.takeWhile { it.isLetter() }
        val n = rejLogByReason.getOrDefault(key, 0)
        if (n >= 30) return
        rejLogByReason[key] = n + 1
        rejLogTotal++
        val cap = try { CfhUtil.readCaption(qp) } catch (_: Throwable) { null }
        val pid = try { CfhProbe.readPhotoId(qp) } catch (_: Throwable) { null }
        Logger.evidence(
            "POOLREJ",
            "★入池被拒[$reason] pid=${pid ?: "-"} cap=\"${cap?.take(20) ?: "-"}\" 池=${synchronized(cleanPool) { cleanPool.size }}"
        )
    }

    /**
     * ★ 性能修复（第二批 · 2026-09）：`noteClean`「同作者入池上限」计数的**作者 key 缓存**。
     *
     * ## 问题
     * `noteClean` 每入池一条，都要在 `synchronized(cleanPool)` 内遍历**整池**（≤100 条），
     * 对每条做 4~7 次反射（`Reflect.readAny(mEntity)` + `CfhUtil.readUserKey` 内部的
     * `mUser/mUserKey/mUserIdStr/mUserId` + `mPhotoMeta.mUserId` + 昵称兜底）。
     * 而它被 `CfhFeedHook:326`、`:667`、`:1136`、`:1188` 以及本文件 `noteCleanAll`
     * 的循环（`CfhState:721`）**逐条目**调用 ⇒ 单批可达上万次反射，且**全部在锁内**
     * —— 既慢，又阻塞清洗/补位线程取池。
     *
     * ## 做法
     * 按**对象身份**缓存作者 key（`IdentityHashMap`），把「每条目 × 整池」的反射
     * 降到「每对象一次」。缓存只在 `synchronized(cleanPool)` 内读写，不新增锁。
     *
     * ## 为什么不改变判定结果
     * · 缓存值就是原来那次反射的结果，比较式仍是 `key == uKey`（判定逻辑一字未动）；
     * · **只缓存非空 key**：读空时一律不缓存 ⇒ 下次仍然重新读 —— 与改前「每次都重读」
     *   在「字段还没填好」这一情形下行为完全一致，不会把「先空后有」的 key 冻成空；
     * · 反射抛异常时与改前同样按「不是同作者」处理：原实现 `catch → false`，
     *   这里返回 `""`，而进入计数分支的前提是 `uKey.isNotBlank()` ⇒ `"" != uKey`
     *   必成立 ⇒ 计数结果相同。
     *
     * ## 有界性（池的移除点散落在别的文件，无法全部就地清缓存）
     * 出队点包括 `CfhTtpParse:1195/1232/1316/1337`、`MainMenuDialog:325`、
     * `CfhState` 补位路径与淘汰分支，入队点还有 `CfhPoolStore:654`。为不改这些文件，
     * 缓存靠**硬上限 + 本函数内主动清理**保证有界：
     * · `noteClean` 淘汰最旧一条时同步 `remove(old)`；
     * · 写入前若已达 [AUTHOR_KEY_CACHE_MAX]（256 ＝ 池上限 100 的 2.5 倍）则整表清空 ——
     *   缓存是可重建的，清空只意味着偶发多读几次反射，**绝不会无界增长**。
     *
     * 收益：单批「上万次锁内反射」降为「每对象一次」。
     */
    private const val AUTHOR_KEY_CACHE_MAX = 256

    /** 见 [AUTHOR_KEY_CACHE_MAX] 说明；键为对象**身份**，只在 `synchronized(cleanPool)` 内访问 */
    private val authorKeyCache = java.util.IdentityHashMap<Any, String>()

    /**
     * 读作者 key 并缓存。**必须在 `synchronized(cleanPool)` 内调用**（与 cleanPool 同锁）。
     * 空 key 不缓存（见 [AUTHOR_KEY_CACHE_MAX] 的说明）。
     */
    private fun authorKeyCached(o: Any): String {
        val hit = authorKeyCache[o]
        if (hit != null) return hit
        val k = try {
            val e = io.github.angbang852.manjiao.util.Reflect.readAny(o, "mEntity") ?: o
            io.github.angbang852.manjiao.hook.CfhUtil.readUserKey(o, e)
        } catch (_: Throwable) { "" }
        if (k.isNotBlank()) {
            if (authorKeyCache.size >= AUTHOR_KEY_CACHE_MAX) authorKeyCache.clear()
            authorKeyCache[o] = k
        }
        return k
    }

    fun noteClean(qp: Any): Boolean {
        // ★★★ v13.39 拒绝原因埋点（2026-09-30 用户「滑不出来」诊断）：
        //   HOMEPOOL 实证「首页登记 批size=5 成功=0 池=2」—— 首页内容 100%
        //   入池失败，但看不到原因（类型/无pid/重复/作者超限）。加埋点定位。
        val qpCls = qpClassRef
        if (qpCls != null) {
            if (!qpCls.isInstance(qp)) { noteCleanRej("TYPE", qp); return false }
        } else if (qp.javaClass.name != "com.yxcorp.gifshow.entity.QPhoto") {
            noteCleanRej("TYPE2", qp); return false
        }
        val pid = try { CfhProbe.readPhotoId(qp) } catch (_: Throwable) { null }
        if (pid.isNullOrBlank()) { noteCleanRej("NOPID", qp); return false }
        // ★★★ v13.37 护栏精简（2026-09-30 用户质问「共享池为什么精选页老是空」）：
        //   ## 矛盾根源
        //   池是**共享的**，首页内容应该全进池、池该很满 —— 但实测池长期
        //   只有 1-3 条（POOLDUMP size=1）。原因是我 v13.29-13.34b 叠加的三条
        //   护栏互相打架，把池锁成「流水线」：
        //     ① 时间窗（入池一次后 20s 拒绝）    ← 池 ≥3 时生效
        //     ② cleanPoolIds 永久去重（补位取走不清）← 池 ≥3 时生效
        //     ③ 池<3 时才放行重复入池          ← 造成「涨到 3 就锁死」
        //   ⇒ 池永远在 1-3 条徘徊，精选页补位无货。
        //
        //   ## 现在的正确设计
        //   · **入池：只做「池内当前去重」**（池里已有该 id 就不重复加）
        //     —— 不设时间窗、不设永久标记 ⇒ 首页滑出多少干净内容，池就
        //     积累多少（上限 100），精选页才有货可取。
        //   · **防重复：交给补位层**（refillFromCleanPool / refillRenderList
        //     的 existing 去重 + 每批每作者最多 1 条）—— 那才是「同屏不
        //     重复 / 不出现同作者紧挨」的正确防线，与快手源行为一致。
        //   · **防单一作者霸池**：noteClean 入池时同作者上限 8 条（宽松，
        //     只挡极端霸屏；分散仍由补位层负责）。
        val poolNow = synchronized(cleanPool) { cleanPool.size }
        val lastInTs = cleanPoolIds[pid]
        if (lastInTs != null && System.currentTimeMillis() - lastInTs < REENTRY_WINDOW_MS) {
            noteCleanRej("DUP", qp); return false
        }
        // 同作者入池上限 8 条（宽松护栏，防单作者霸池；补位层再做同批分散）
        val uEnt = try { io.github.angbang852.manjiao.util.Reflect.readAny(qp, "mEntity") ?: qp } catch (_: Throwable) { qp }
        val uKey = try { io.github.angbang852.manjiao.hook.CfhUtil.readUserKey(qp, uEnt) } catch (_: Throwable) { "" }
        if (uKey.isNotBlank()) {
            val same = synchronized(cleanPool) {
                // ★ 性能修复（第二批 · 2026-09）：原为 cleanPool.count { 每条都反射读作者 key }，
                //   即「每条目 × 整池（≤100）」在锁内做上万次反射。现改走身份缓存
                //   authorKeyCached（见其 KDoc）：同一对象只反射一次，其余命中缓存。
                //   计数口径与语义完全不变（仍是「与 uKey 相等的条数」，异常仍算不相等）。
                var n = 0
                for (e in cleanPool) if (authorKeyCached(e) == uKey) n++
                n
            }
            if (same >= 8) { noteCleanRej("AUTHOR$same", qp); return false }
        }
        synchronized(cleanPool) {
            // ★★★ v13.95【头号坑】原实现：`if (cleanPoolIds.containsKey(pid)) return false`
            //   它**与时间无关**，会盖掉上面 :549 的 5 分钟窗口 [REENTRY_WINDOW_MS]
            //   ⇒ 实际行为是「永久拉黑」⇒ 30 天保留期形同虚设（只改上面一处 = 改了个寂寞）。
            //   改为按时间戳判断：只有**仍在保留期（[POOL_ID_KEEP_MS]）内**的才拒绝。
            //   两层语义因此真正分层：5 分钟防同屏循环，30 天防跨会话重放。
            val keepTs = cleanPoolIds[pid]
            if (keepTs != null && System.currentTimeMillis() - keepTs < POOL_ID_KEEP_MS) return false
            if (cleanPool.size >= 100) {
                val old = cleanPool.removeAt(0)
                // ★ 性能修复（第二批）：淘汰/出队路径同步清理作者 key 缓存，
                //   避免缓存留住已出池对象（配合 AUTHOR_KEY_CACHE_MAX 硬上限双重保证有界）。
                authorKeyCache.remove(old)
                val oldPid = try { CfhProbe.readPhotoId(old) } catch (_: Throwable) { null }
                if (!oldPid.isNullOrBlank()) cleanPoolIds.remove(oldPid)
            }
            cleanPool.add(qp)
            // ★ 性能修复（第二批）：顺手把**刚在 :555 已经算出来**的 uKey 挂到该对象身份上
            //   （零额外反射）。它成为池成员后，后续其它条目的同作者计数直接命中缓存，
            //   不必再对这条反射一遍。只在非空时缓存，与 authorKeyCached 口径一致。
            if (uKey.isNotBlank()) {
                if (authorKeyCache.size >= AUTHOR_KEY_CACHE_MAX) authorKeyCache.clear()
                authorKeyCache[qp] = uKey
            }
            cleanPoolIds[pid] = System.currentTimeMillis()
            // ★★★ v13.95 双闸触发点①：入池即做一次**廉价闸**淘汰（必须在 markDirty 之前 ——
            //   淘汰结果要跟着这一次落盘一起下去，否则淘汰过的旧记号还会被写回存档）。
            //   绝大多数调用在此常数级返回（见 [prunePoolIds] 的廉价闸），零热路径负担。
            try { prunePoolIds() } catch (_: Throwable) {}
            // ★★★ v13.74 池持久化：池变了就（防抖）落盘一次，
            //   让「队列尾部 + 永久拉黑表」跨重启存活。
            try { CfhPoolStore.markDirty() } catch (_: Throwable) {}
            // ★★★ v13.42 入池成功统计（2026-09-30 用户「主页一大把，池却空」）：
            //   量化采集效率 —— 每 25 次成功入池打一行，与 POOLREJ（拒绝）
            //   对照即可算出「经过 N 条 → 入池 M 条」的真实比例。
            poolAddOk++
            if (poolAddOk % 25 == 0) {
                Logger.evidence(
                    "POOLSTAT",
                    "★入池统计 成功=$poolAddOk 拒绝尝试=$rejLogTotal 当前池=${synchronized(cleanPool) { cleanPool.size }}"
                )
            }
            // v13.37 护栏精简后不再使用时间窗，但仍登记时间戳（诊断用，零成本）
            recentPoolTs[pid] = System.currentTimeMillis()
            // ★★★ v13.34 池内容 dump（2026-09-30 用户「一拉一个作者3条？」）：
            //   直接看池里作者分布 —— 若池被少数作者占满，补位从池尾取自然
            //   全是一个作者。每 20 次入池打一次（前 5 次），列作者→条数。
            if (poolDumpLog < 5 && cleanPool.size % 20 == 1) {
                poolDumpLog++
                val byUser = HashMap<String, Int>()
                for (e in cleanPool) {
                    try {
                        val ent = io.github.angbang852.manjiao.util.Reflect.readAny(e, "mEntity") ?: e
                        val k = io.github.angbang852.manjiao.hook.CfhUtil.readUserKey(e, ent)
                        val un = io.github.angbang852.manjiao.hook.CfhUtil.readUserName(e, ent)
                        val key = if (k.isNotBlank()) k else "?"
                        byUser[key] = (byUser[key] ?: 0) + 1
                        if (byUser.size > 12) break
                    } catch (_: Throwable) {}
                }
                val desc = byUser.entries.joinToString(" ") { (k, v) -> "$k:$v" }
                Logger.evidence("POOLDUMP", "★池作者分布 size=${cleanPool.size} $desc")
            }
        }
        return true
    }

    /**
     * ★★★ v13.71 投放即出队（2026-09-30 用户定案）
     *
     * > 用户原话：「进队列了、上屏了，池子里对应的就应该消失啊……
     * >          快手本身的机制就应该是这样的啊？」
     *
     * ## 为什么必须有这个函数（在它之前，池只被读、从不被消费）
     * `RENDERFILL` / `RESPFILL` 的取值写法都是「拷一份 `cleanPool`，从头取前 N 条」：
     *   · 池因此永远停在 50（`POOL_TARGET`）⇒ `CfhSupply` 里
     *     `if (n >= POOL_TARGET) return false` **一次都没真正触发过**，
     *     后台补货链路整条是死的；
     *   · 每次取出来的都是**同样那前 6 条** ⇒ 快手反复收到同一批 photoId。
     * 而快手信息流本身是 **consume-once**（滚过的条目出队、按 photoId 去重）：
     * 反复灌它已经放过的内容，会被当重复处理掉 ⇒ 列表又空 ⇒ 再灌同样 6 条。
     * 真机现场完全吻合：`RESPFILL +6 池=50` 一路健康，而屏上 10 分钟
     * 只出现过 6 个 id，其中两个重复了 40 次 / 26 次。
     *
     * ## 语义
     * `served` 中的实例从池中**移除**，按**引用**（`===`）判定，不信任 `equals`。
     * `cleanPoolIds` 的去重标记**故意保留** —— 与「看过的就不再给」一致：
     * 出队的内容永不再入池（除非被 100 上限淘汰，见 `noteClean`）。
     *
     * ## 与 v13.62 翻车的区别
     * 那次「补位即取走」把池抽干到 0，是因为**取走之后没有任何补货**。
     * 这次取走本身就是为了让 `ensurePoolSupply` 的水位门（池 < 50）开始工作，
     * 并且带 `POOL_FLOOR` 硬兜底（见该常量注释）。
     *
     * @return 实际出队条数（0 = 被 floor 挡住 / served 为空 / 池里找不到）
     */
    fun consumeClean(served: List<Any?>): Int {
        if (served.isEmpty()) return 0
        var removed = 0
        var poolAfter = 0
        try {
            synchronized(cleanPool) {
                val canRemove = cleanPool.size - POOL_FLOOR
                if (canRemove <= 0) return@synchronized
                val iter = cleanPool.iterator()
                while (iter.hasNext() && removed < canRemove) {
                    val e = iter.next()
                    var hit = false
                    for (s in served) {
                        if (s != null && s === e) { hit = true; break }
                    }
                    if (hit) { iter.remove(); removed++ }
                }
                poolAfter = cleanPool.size
            }
        } catch (_: Throwable) {}
        if (removed > 0) {
            poolDrainTotal += removed
            // ★★★ v13.74 出队也要落盘：这是「已被看过」的负向证据，
            //   不存下来的话重启后这些条目会被当成「没看过」再放一次。
            try { CfhPoolStore.markDirty() } catch (_: Throwable) {}
            if (io.github.angbang852.manjiao.util.RateLimiter.allow("POOLDRAIN", 120)) {
                Logger.evidence(
                    "POOLDRAIN",
                    "★投放出队 $removed 条 池=$poolAfter 累计出队=$poolDrainTotal " +
                        "(floor=$POOL_FLOOR)"
                )
            }
        }
        return removed
    }

    /**
     * ★★★ v13.21 公共批量登记（50388 首页数据源适配）：
     * 遍历任意容器，把其中 WHITE 元素登记进干净池。
     * 用于首页数据源 MimpBripGlilt 的 VmList/DataSource hook 清洗后，
     * 把剩余 WHITE 补进池 —— 与 knhb T0/E1 的登记逻辑完全一致。
     * noteClean 自带 QPhoto 类型校验 + pid 去重 + 池上限，非 QPhoto 自动拒绝。
     * @return 实际入池条数
     */
    fun noteCleanAll(items: Iterable<*>?): Int {
        if (items == null) return 0
        // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：
        //   false ⇒ 首页数据源清洗结果不再补进池（干净安装下「首页内容池接精选页」整体停用）。
        if (!io.github.angbang852.manjiao.data.Prefs.bool(io.github.angbang852.manjiao.data.Prefs.K_FLT_HOMEREFILL, false)) return 0
        if (!whiteListEnabled) return 0
        var n = 0
        var seen = 0
        for (e in items) {
            if (e == null) continue
            seen++
            try {
                // ★★★ v13.28 首页包装对象适配（2026-09-30 根治「几个视频循环」）：
                //   50388 首页数据源（CoorlrFeeck/QueerkrFloudRaidck 等）的 VmList/
                //   DataSource 列表元素是**包装对象**（qpDirect=false qpWrapped=true
                //   SRCELEM 实证），不是直接 QPhoto ⇒ noteClean 的类型校验（
                //   qpClassRef.isInstance）直接拒绝 ⇒ 首页 9 条全进不了池 ⇒
                //   池只有零星几条 ⇒ 补位循环。必须先 findQpInObject **提取 QPhoto**
                //   再判定（WHITE 才入池，与 v13.26 一致性口径一致）再 noteClean。
                val q = try { io.github.angbang852.manjiao.hook.CfhProbe.findQpInObject(e) ?: e }
                    catch (_: Throwable) { e }
                // v13.26 一致性修复：进池前重新判定（字段回填后 AI 声明才暴露）
                val v = io.github.angbang852.manjiao.hook.CfhDecide.judgeWhitelist(q)
                if (v != io.github.angbang852.manjiao.hook.CfhDecide.WhitelistVerdict.WHITE) continue
                if (noteClean(q)) n++
            } catch (_: Throwable) {}
        }
        // ★★★ v13.47 列表采集量化（2026-09-30 用户「刷了半天还是滑不出来」）：
        //   本函数此前**完全静默** —— 采集漏在哪一环（遍历到几条 / 判白几条 /
        //   入池几条）根本看不见。加限次日志，与 POOLSTAT / POOLREJ 对照即可
        //   算清「发现页一屏 N 条 → 实际入池 M 条」的真实比例。
        if (poolAllLog < 40) {
            poolAllLog++
            Logger.evidence(
                "POOLALL",
                "★发现页列表登记 遍历=$seen 入池=$n 池=${synchronized(cleanPool) { cleanPool.size }}"
            )
        }
        return n
    }
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

    /** 替换失败（clean==null）诊断计数（2026-09-23 排查「视频与文案对不上」） */
    var fragNoCleanDiag = 0
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
    /** 真源删除明细自证计数（限次，见 CfhWash.washDiagDetail） */
    var washDiagCount = 0
    /** 真源脏项残留自证计数（限次，见 CfhWash.probeSourceDirty） */
    var srcDirtyProbeCount = 0
    /** h.j 脏项内容自证计数（限次，见 CfhWash.probeSourceDirty） */
    var hjDetailCount = 0
    /** h.j 结构自证计数（限次，见 CfhWash.probeSourceDirty） */
    var hjStructCount = 0
    /**
     * 最近一次触发 `filterResult` 的 VM 方法（如 `SlidePlayViewModel.V`）。
     *
     * ★ 用途（2026-09-24）：实测 432 次删除中 318 次删同一条 AI 视频，
     *   需要区分「哪个方法在反复返回它」—— 这是定位灌入口的关键线索。
     */
    @Volatile var lastLretMethod: String? = null
    /** G0（真源取数）身份探针计数（限次，见 CfhFeedHook 的 hookG0） */
    var g0ProbeCount = 0
    /** 真源持有者结构是否已 dump（每进程一次，见 CfhFeedHook.srcHolderDump） */
    @Volatile var srcHolderDumped = false
    /** 真源元素构成自证计数（限次，见 CfhWash 的 SRCELEM） */
    var srcElemDumpCount = 0
    /** 网络层/入参层覆盖自证计数（限次，见 CfhClean.filterListArgs 的 NET） */
    var netProbeCount = 0
    /** 进入判定留痕计数（限次，见 CfhDecide.shouldFilterFeed 的 ENTER） */
    var enterProbeCount = 0
    /** 定点追踪计数（限次，见 CfhDecide.shouldFilterFeed 的 MANGO） */
    var mangoProbeCount = 0
    /** 短剧卡片追踪计数（限次，见 CfhDecide.shouldFilterFeed 的 DRAMA） */
    var dramaProbeCount = 0
    /** 中间层清洗调用留痕计数（限次，见 CfhWash.filterVmLists 的 WASH-IN） */
    var washEnterCount = 0
    /** 中间层清洗被节流跳过的计数（限次，见 WASH-SKIP） */
    var washSkipCount = 0
    /** 中间层清洗任务提交计数（限次，见 WASH-SUBMIT） */
    var washSubmitCount = 0
    /** 中间层清洗任务取到 null 的计数（限次，见 WASH-NULL） */
    var washNullCount = 0
    /** 中间层清洗调用被丢弃的计数（限次，见 WASH-DROP） */
    var washDropCount = 0
    /** 中间层清洗「执行了但没删到」的计数（限次，见 WASH-ZERO） */
    var washZeroCount = 0
    /** 中间层清洗走到「提不到 QP」分支的计数（限次，见 WASH-NOQP） */
    var washNoQpCount = 0
    /** 主 feed 列表搜索是否已做（每进程一次，见 CfhWash 的 MAINLIST） */
    @Volatile var mainListSearched = false
    /** 含 QPhoto 的候选列表是否已收集（每进程一次，见 CfhWash 的 QPLISTCOL） */
    @Volatile var allQpListsCollected = false
    /** 全列表清洗的删除计数（限次，见 CfhWash 的 ALLDEL） */
    var allListDelCount = 0
    /** 「列表被删到剩很少」的计数（限次，见 CfhWash 的 SMALLLIST） */
    var smallListCount = 0
    /** 最小范围清洗的删除计数（限次，见 CfhWash 的 MINDEL） */
    var minimalDelCount = 0
    /** 门禁诊断输出计数（限次，见 CfhWash 的 MINGATE）—— 查「周期清洗在跑但零删除」 */
    var minimalGateCount = 0
    /** 元素判据诊断计数（限次，见 CfhWash 的 CJUDGE）—— 查「通过=4 但删=0」 */
    var contentJudgeDiagCount = 0
    /** 全字段普查计数（限次，见 CfhWash 的 FIELDD）—— 查漏拦样本的真实字段 */
    var fieldDumpCount = 0
    /** AI 指纹普查计数（限次，见 CfhWash 的 AIFP）—— 定向抓漏拦条目的字段快照 */
    var aiFingerprintCount = 0
    /** AI 剪辑样式 ID 探针计数（限次，见 CfhWash 的 STYLEID）—— 查非零 styleId 是否等价于 AI 内容 */
    var styleIdProbeCount = 0
    /** AI 剪辑样式 ID 的命中次数（非零即 +1，用于评估该指纹的覆盖率） */
    var styleIdHitCount = 0
    /** 可见页全量指纹 dump 计数（限次，见 CfhDiag 的 VISDUMP）—— 抓「屏幕上有但无判定记录」的条目 */
    var visDumpCount = 0
    /** 点名账号全字段 dump 计数（限次，见 CfhDiag 的 NAMED）—— 用户报障条目的逐字段证据 */
    var namedDumpCount = 0
    /**
     * 上次已 dump 的可见页 photoId（见 CfhDiag 的 VISDUMP）。
     *
     * ★ 用途（2026-09-24）：把可见页记录从「每 Fragment 一次」改为
     *   「每次换页一次」。原实现只在 Fragment 首次可见时记录，
     *   实测观测窗口只有 37 秒，之后用户滑多久都不再有记录 ——
     *   主人现场报的漏拦条目全部落在窗口之外，永远抓不到。
     */
    @Volatile var lastVisDumpedPid: String? = null
    /**
     * 上一次 DEL 记录里的真源 hash（见 CfhClean 的 DEL）。
     *
     * ★ 用途（2026-09-24）：`真源Hc` 连续多次相同时，说明模块一直在删副本
     *   而真源未动 —— 这就是「删了又回来」的机制。连续 3 次触发
     *   `CfhDiag.locateStableSource` 把该列表的身份打出来。
     */
    @Volatile var lastSrcHc = 0
    /** 真源 hash 连续相同的次数（见 CfhClean 的 DEL） */
    var sameSrcHcStreak = 0
    /** 恒定真源定位探针计数（限次，见 CfhClean 的 STABLEHC） */
    var stableHcProbeCount = 0
    /**
     * 周期可见内容巡检计数 + 去重（见 CfhWash 的 PSCAN）。
     *
     * ★ 用途（2026-09-24）：`VISDUMP` 挂在 `diagFragment` 上，
     *   实测会出现 **52 秒零输出**（用户正常滑动时该函数不触发）——
     *   报障条目因此永远抓不到。本巡检改挂周期任务，脱离 UI 事件。
     */
    var periodicVisCount = 0
    /**
     * 周期巡检的已见 photoId 集合（去重）。
     *
     * ★ 二次修正（2026-09-24）：第一版按下标取"当前条"，实测失败 ——
     *   下标在 2/3 抖动、记录恒为同样两条，主人眼前的内容完全没进记录。
     *   改为扫描全部条目、记录判脏的那些，本集合用于按 id 去重。
     */
    val periodicVisSeen = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    /**
     * 广告指纹探针计数（限次，见 CfhWash 的 ADTAG）。
     *
     * ★ 用途（2026-09-24）：主人报「schiff旭福官方海外」这类品牌广告漏拦，
     *   而周期巡检的判脏条目全是内容型 ⇒ 广告走另一套结构。
     *   本探针用于找出该结构带的是哪组字段。
     */
    var adFpCount = 0
    /**
     * 周期移除计数（见 CfhWash 的 PDEL）。
     *
     * ★ 用途（2026-09-24）：实测「判脏 8 条 / 被移除仅 7 条」——
     *   判脏走周期任务（稳定），移除走 Fragment 事件（有盲区），
     *   两者节奏不一致导致「判了不删」。现把移除并入周期任务。
     */
    var periodicDelCount = 0
    /**
     * 「判脏但未找到移除点」计数（见 CfhWash 的 PDEL-MISS）。
     *
     * ★ 用途（2026-09-24）：这是重要负向信号 ——
     *   判脏说明识别正确，但找不到可移除位置说明该内容
     *   不在可移除的列表里（可能在快照上，或被排除规则跳过）。
     *   持续出现即证明「判了不删」的原因在移除侧而非判据侧。
     */
    var periodicDelMissCount = 0
    /**
     * 「记录路径」的移除计数（见 CfhDiag 的 VISDEL，由 VISDUMP 触发）。
     *
     * ★ 用途（2026-09-24）：实测 PDEL（VM 侧，周期任务）删干净后，
     *   71 秒后 VISDUMP（Fragment 侧）仍能看到同一条 ——
     *   两者走**不同的容器**，而 Fragment 侧才是驱动屏幕显示的那份。
     *   本计数确认「记录路径也执行移除」是否生效。
     */
    var visDumpDelCount = 0
    /**
     * 黑名单补删计数（见 CfhWash 的 BDEL）。
     *
     * ★ 用途（2026-09-24）：「删了又回来」的收口手段 ——
     *   实测「老九讲故事」第68集删掉 4 个持有者后 1.5 秒又出现，
     *   真源哈希恒定（快手从数据源重建）。
     *   对策：判过一次即入黑名单，之后每轮周期巡检都补删，直到不再出现。
     */
    var blacklistDelCount = 0
    /**
     * 可见页强制移除计数（见 CfhDiag 的 VISFDEL）。
     *
     * ★ 用途（2026-09-24）：实测 VISDEL-SUM 删了 VM 侧 4 处，
     *   而 VISDUMP 距其仅 31 毫秒仍看到该条 —— 证明两者是不同对象图。
     *   本机制不受换页/限次门控，只要可见项在黑名单就就地删。
     */
    var visForceDelCount = 0
    /**
     * 短剧数据源普查计数（限次，见 CfhDiag → CfhWash.surveyDramaSource）。
     *
     * ★ 用途（2026-09-24）：主人报的 11 条短剧全部零记录，
     *   确认其形态是卡片/剧集页（非普通全屏视频）。
     *   本普查定位该形态的数据挂在哪条引用链上。
     */
    var dramaSrcSurveyCount = 0
    /**
     * 判定当刻同步删除（见 CfhDecide.hit）。
     *
     * ★ 用途（2026-09-24）：实测重启后仍漏拦 —— 判脏正确但用户已看到。
     *   原因是删除靠周期任务（1-2 秒一轮），而用户滑过一条只要 1 秒，
     *   「判脏→删除」的延迟大于内容停留时间。
     *   现改为在 hit()（判定命中当刻）直接尝试移除。
     */
    @Volatile var lastHitDelAt = 0L
    var hitDelCount = 0
    /**
     * Fragment 字段定位探针计数（见 CfhDiag 的 FRAGFLD）。
     *
     * ★ 用途（2026-09-24）：实测「历史切片」「锋哥看百态」被删 3 轮
     *   而 VISDUMP 始终可见 —— 怀疑内容在 Fragment 的**单值字段**上，
     *   而列表级删除碰不到单值字段（既定护栏）。
     *   本探针给出确切字段名，据此决定修法（触发翻页 vs 清缓存字段）。
     */
    var fragFieldProbeCount = 0
    /**
     * 可见项换条计数（见 CfhDiag.replaceVisibleIfDirty）。
     *
     * ★ 用途（2026-09-24）：实测内容挂在 Fragment 单值字段
     *   （`NasaPhotoDetailFragment.M`，类型 QPhoto）上，
     *   列表级删除碰不到它 —— 删 3 轮后屏幕仍显示。
     *   用户决策走「方案一：把 M 换成下一条干净内容」。
     */
    var visReplaceCount = 0
    /** 「想换条但拿不到干净项」计数 —— 用于确认「宁可不拦」的护栏是否在生效 */
    var visReplaceNoClean = 0
    /**
     * 主人现场报的漏拦关键词（见 CfhWash 的 KWSEARCH）。
     *
     * ★ 用途（2026-09-24）：这些内容从未出现在任何探针记录里，
     *   怀疑它们不在被扫描的列表上。周期任务轮转搜这些词，
     *   命中即给出确切挂载点；全部不命中 = 走 VM 之外的通道。
     *
     * ★ 结案后请清空本列表 —— 否则这些词会被长期搜索，无意义且浪费。
     */
    val reportedKeywords = listOf(
        "泼辣媳妇", "旺家门",
        "schiff", "旭福", "刘老根", "卿本佳人", "泡泡追剧",
        "鼠鼠巴啦啦", "满堂嘲讽", "小鱼带你看世界"
    )
    /** 关键词搜索的轮转下标 */
    var kwSearchIdx = 0
    /** 候选列表用途鉴别是否已做（每进程一次，见 CfhWash 的 LISTAUDIT） */
    @Volatile var listAudited = false
    /** 「元素直接是QPhoto」的列表是否已收集（见 CfhWash 的 QPDIRECT） */
    @Volatile var qpDirectListsCollected = false
    /** 内容列表清洗的删除计数（限次，见 CfhWash 的 QPDEL） */
    var qpDirectDelCount = 0
    /** 收集到的内容列表（元素直接是 QPhoto） */
    val qpDirectLists: MutableList<MutableList<Any?>> =
        java.util.Collections.synchronizedList(ArrayList())
    /** 收集到的含 QPhoto 的列表（清洗目标） */
    val qpLists: MutableList<MutableList<Any?>> =
        java.util.Collections.synchronizedList(ArrayList())
    @Volatile var lastViewSig = ""
    @Volatile var lastFilterVmListsAt = 0L
    @Volatile var vmRefProbeDone = false
    val cleanExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "ManJiaoClean").apply { isDaemon = true }
}
    // ★ 性能修复（审阅 2026-09 · M4）：对象图 BFS 反查（laFind）与清洗管线分离到
    // 独立单线程。原先两者共用 cleanExecutor —— laFind 是深度 6 的全对象图 BFS，
    // 一旦排队，filterVmLists 的清洗请求（keep-latest 模式，会被新请求顶掉）就会
    // 持续被推迟，脏内容在屏时间变长，进而触发更多 laFind，形成正反馈。
    // 分离后：清洗（延迟敏感）不被搜索（吞吐敏感、可延迟）饿死。
    val searchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ManJiaoSearch").apply { isDaemon = true }
    }
    // ★ 跨进程镜像专用单线程（2026-09）：镜像要序列化 JSON + 写磁盘，属 I/O 密集。
    // 不能复用 cleanExecutor/searchExecutor —— 前者是延迟敏感的清洗管线，
    // 后者可能正跑深度 6 的 BFS；放这里即便卡住也不影响过滤与界面。
    val mirrorExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ManJiaoMirror").apply { isDaemon = true }
    }
    val pendingCleanObj = java.util.concurrent.atomic.AtomicReference<Any?>()
    val cleanDrainArmed = java.util.concurrent.atomic.AtomicBoolean(false)
    var filterResultDiag = 0
    var fltCallerDiag = 0
    val retThrottle = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    var lastRefreshTime = 0L
    var lastAllDirtyRefreshAt = 0L

    /**
     * 上次通知分页器「数据已变」的时刻（2026-09-23）。
     *
     * 用于节流：sanitize 在启动窗内可能高频触发，每次都 notifyDataSetChanged
     * 会让分页器反复重绘（反而卡）。300ms 与 adpSelfFix 的节流同量级。
     */
    @Volatile var lastPagerNotifyAt = 0L
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
    /** 直播值级 dump 计数（2026-09-23 排查「直播漏判」，只在 diag 开启时递增） */
    var liveValDiag = 0
    var anyOnCache = false
    var anyOnAt = 0L
    // ★ sigIdCache 已删除（审阅 2026-09 · M5）：它以 System.identityHashCode 为键、
    // 命中即返回且不校验对象身份 —— GC 后 hash 复用会让新对象拿到旧对象的判定结果
    // （误拦/漏拦，静默随机）。它只是省一次 sig 计算，收益不抵可靠性风险。
    // 内容签名缓存 feedSigCache/contentSigCache 语义正确，已足够。
    //
    // ★★★ 性能优化（2026-09-26 用户要求「加快识别过滤速度」）。
    //
    // ## 原实现的问题（实测定位）
    //
    // ```kotlin
    // val feedFilterCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
    // ```
    //
    // 实测单条判定 **25~182ms（平均 55ms）**，而缓存命中时 < 3ms —— 差 20 倍。
    // 定位到两个原因：
    //
    // 1. **`WeakHashMap` 被 GC 清空** —— 实测快手进程 GC 极频繁
    //    （`Background concurrent mark compact GC freed 43MB, total 231ms`），
    //    弱引用键随即消失 ⇒ 缓存几乎不命中 ⇒ **每次都走完整判定链路**
    //    （`shouldFilterFeed` → `decideBySig` → `cachedDecide` → `decideFeedRaw`，
    //     后者含 71 处反射读取）。
    //
    // 2. **`synchronizedMap` 是全局锁** —— 判定来自多个线程
    //    （实测 `main` / `Thread-37` / `k-design-daynight-asyncinflater`），
    //    即使判定逻辑本身快，锁竞争也会拖慢。
    //
    // ## 优化
    //
    // 换成 **`ConcurrentHashMap`（强引用 + 分段锁）**：
    //   · 强引用 ⇒ 不受 GC 影响，缓存命中率大幅提升
    //   · 分段锁 ⇒ 无全局竞争
    //   · **容量上限 4096**，超限整体清空（防内存无限增长）
    //
    // ## 正确性说明
    //
    // 与已删除的 `sigIdCache` 不同，这两个缓存**以对象引用为键**
    // （`WeakHashMap<Any, Boolean>` → `ConcurrentHashMap<Any, Boolean>`），
    // **不存在 hash 复用误判问题** —— 键是对象本身，不是 identityHashCode。
    //
    // 唯一的语义变化：对象被 GC 后条目仍留着（强引用会阻止 GC）——
    // 故必须**限容**，否则长期运行会积累。上限 4096 条（约几百 KB）可接受。
    val feedFilterCache = java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
    val contentFilterCache = java.util.concurrent.ConcurrentHashMap<Any, Boolean>()

    /** 缓存容量上限（超限整体清空，防内存无限增长） */
    private const val FILTER_CACHE_MAX = 4096

    /** 写缓存（带上限保护）。供 `cachedDecide` 调用。 */
    fun putFilterCache(cache: java.util.concurrent.ConcurrentHashMap<Any, Boolean>, k: Any, v: Boolean) {
        try {
            if (cache.size >= FILTER_CACHE_MAX) cache.clear()
            cache[k] = v
        } catch (_: Throwable) {}
    }
    var fcacheDiag = 0
    val filterHitStats = java.util.concurrent.ConcurrentHashMap<String, Int>()
    var hitLogDiag = 0

    // ==================== 拦截审计（功能 4/5/6，2026-09） ====================

    /** 单条拦截记录：够用户判断「这条拦得对不对」的全部证据 */
    class HitRecord(
        val at: Long,
        val reason: String,       // 命中规则（如 "ai:disclaimer"）
        val caption: String,      // 文案（截断）
        val user: String,         // 作者
        val photoId: String,      // 视频 id（可用于「不再拦这条」）
        val entCls: String        // 实体类名（判断是否结构类名误伤）
    )

    /**
     * 拦截审计环形缓冲（上限由 [auditLimit] 控制）。
     *
     * 与 [filterHitStats]（只记计数）的区别：这里保留**逐条的原始证据**，
     * 供「拦截记录」页展示 + 用户判断是否误拦。
     */
    val hitRecords: java.util.ArrayDeque<HitRecord> = java.util.ArrayDeque()

    // ★ allowPhotoIds（标为误拦白名单）已随该功能移除（2026-09）。
    //   镜像格式里的 "allow" 字段保留（恒为 0），避免破坏旧镜像的解析兼容。

    /** 审计开关（由 Prefs 驱动；关闭时 hit() 零额外开销） */
    @Volatile var auditEnabled: Boolean = false

    /**
     * 审计记录**条数**上限 —— 仅作刷屏兜底，**不是**保留策略。
     *
     * ★ 2026-09 用户定稿：主保留策略是 7 天时间窗（[RECORD_KEEP_MS]）。
     * 用户实测「200 条不出 1 小时就能累计到」—— 条数制在重度使用下半天就打满，
     * 旧记录被挤出后 7 天保留就成了空话。
     *
     * 新值估算：单条 HitRecord（5 个短 String + 1 Long + 对象头）≈ 400-500B，
     * 10000 条 ≈ 5MB —— 宿主进程可接受；正常使用一天几百到一两千条，
     * 10000 条足够装满 7 天，条数限制不会先于时间限制触发。
     */
    @Volatile var auditLimit: Int = 10000

    /**
     * 记录保留时长：**7 天**（2026-09 用户定稿）。
     *
     * 「记录要能保留 7 天，图标最高也是只能看 7 天的」—— 记录与统计窗口对齐：
     * 更早的记录在统计图里本来就不可见，留着只会占内存、误导用户「数据还在别处」。
     * 兜底的条数上限（[auditLimit]）仍生效，防异常刷屏撑爆内存。
     */
    const val RECORD_KEEP_MS = 7L * 24 * 3600_000L

    /** 统计观测窗口起点（功能 5）：用户点「重置统计」时刷新，用于显示统计时长 */
    @Volatile var statsBaselineAt: Long = System.currentTimeMillis()

    // ==================== 跨进程镜像（2026-09 修复「app 里没有信息」） ====================

    /**
     * 镜像可用标志 —— 由 Module 在初始化时注入（见 [mirrorDir]）。
     *
     * 保留 [mirrorDir] 只是为了诊断日志能打印实际路径；读写统一走
     * [io.github.angbang852.manjiao.data.AuditMirror]，它自己解析同一目录。
     */
    @Volatile var mirrorDir: String? = null

    /** 上次镜像落盘时间（节流用，避免每次命中都写文件） */
    @Volatile private var lastMirrorAt = 0L

    /**
     * 把当前审计状态镜像到磁盘（**异步**，节流 [MIRROR_INTERVAL_MS]）。
     *
     * 调用点在命中热路径上，因此这里必须**立即返回**：快照采集放后台线程，
     * 且失败静默（镜像坏掉绝不能影响过滤本身）。设置页是「用户主动打开才看」，
     * 30 秒的同步延迟完全够用。
     */
    fun mirrorSoon() {
        val now = System.currentTimeMillis()
        if (now - lastMirrorAt < MIRROR_INTERVAL_MS) return
        lastMirrorAt = now
        try {
            mirrorExecutor.submit { doPublish() }
        } catch (_: Throwable) {}
    }

    /**
     * 启动时从磁盘镜像恢复审计状态（2026-09 修复「拦截统计只剩最近的」）。
     *
     * ★ 根因：记录与统计全部活在快手进程内存（[hitRecords] / [filterHitStats] /
     *   [hourlyByRule] / [hitTotal]），每 20s 发布的快照内容 = **当前内存**。
     *   快手重启（模块更新 force-stop / 系统杀进程）后内存为空，启动后的第一个快照
     *   带着 0 条记录**覆盖**磁盘镜像 —— 历史记录就此消失，app 只能看到重启后攒的新记录。
     *
     * ★ 修复：进程启动、审计状态就绪后，先把镜像读回内存（仅当内存为空 ——
     *   防止重复初始化时把新数据冲掉）。此后发布的快照自然包含恢复来的历史。
     *
     * 注意必须**在第一个周期发布之前**调用（调用点：Module 初始化 →
     * startPeriodicPublish 之前），否则 20s 后的首次发布就会先把镜像冲掉。
     *
     * 恢复的字段与 [doPublish] 发布的一一对应：
     *   total / baselineAt / records / stats / rawRuleBuckets / allowPhotoIds。
     * hourly 桶不单独存：rawRuleBuckets 的键就是 "bucket|rule"，恢复时拆开重建
     * [hourlyHits] 与 [hourlyByRule]（写入端 mirrorNow 落的就是 rawRuleBuckets）。
     */
    fun restoreFromMirrorIfEmpty() {
        try {
            // ★★★ 恢复策略修正（2026-09-26 用户报「48h/7d 显示不出数据」）。
            //
            // ## 原实现的致命缺陷
            //
            // ```kotlin
            // if (synchronized(hitRecords) { hitRecords.isNotEmpty() }) return
            // if (hitTotal > 0) return          // ★★★ 就是这一行
            // ```
            // **模块一启动就会拦截内容** ⇒ `hitTotal` 立刻 > 0
            // ⇒ **恢复被永久跳过** ⇒ `hourlyHits` 只剩本次进程的数据
            // ⇒ **48h / 7d 里绝大多数桶是 0**（用户看到「48h/7d 没数据」）。
            //
            // ## 修法：**按桶合并**（不再「整体恢复或完全不恢复」）
            //
            // 无论内存有没有数据，都把镜像里的桶**合并进来**：
            //   · 同一桶（同 `bucket|rule`）⇒ **累加**（本次进程的数 + 历史的数）
            //   · 仅镜像有、内存没有 ⇒ 直接补上
            //   · 仅内存有、镜像没有 ⇒ 保留（不动）
            //
            // **为什么累加是对的**：镜像里的是**上次 `mirrorNow()` 时刻的快照**，
            // 而本次进程的数据是**那之后新增的** ⇒ 两者不重叠，累加即总量。
            //
            // ## 去重保护
            //
            // 若同一进程内重复调用本函数 ⇒ 会重复累加。
            // 故用 `mirrorRestoredAt` 标记：**每进程只合并一次**。
            if (mirrorRestoredAt > 0) return          // 本进程已合并过
            mirrorRestoredAt = System.currentTimeMillis()
            val snap = io.github.angbang852.manjiao.data.AuditMirror.read()
            // 从未同步过（镜像不存在/为空）→ 无历史可合并
            if (!snap.fresh) return
            // 镜像过旧（>7 天）就不合并了：统计窗口最长 7d，恢复回来也是过期数据
            val age = System.currentTimeMillis() - snap.writtenAt
            if (age > 7L * 24 * 3600_000L) return

            // ① 命中总数：只在内存无数据时才采用镜像值（避免重复计数）
            if (hitTotal <= 0) hitTotal = snap.total
            if (statsBaselineAt <= 0 && snap.baselineAt > 0) statsBaselineAt = snap.baselineAt

            // ② 记录（用于「拦截记录」列表）：内存为空时才补
            if (synchronized(hitRecords) { hitRecords.isEmpty() }) {
                val recCutoff = System.currentTimeMillis() - RECORD_KEEP_MS
                synchronized(hitRecords) {
                    hitRecords.clear()
                    for (r in snap.records) {
                        if (r.t < recCutoff) continue
                        hitRecords.addLast(
                            HitRecord(
                                at = r.t, reason = r.rule, caption = r.caption,
                                user = r.user, photoId = r.photoId, entCls = ""
                            )
                        )
                    }
                }
            }

            // ③ ★★★ 关键：**桶按合并**（不是清空后重建）
            //
            // 原实现是 `hourlyByRule.clear()` + `hourlyHits.clear()` 后重建 ——
            // 那会**丢掉本次进程已积累的数据**（如果走到这一步的话）。
            // 现改为**合并**：镜像的桶累加到内存的桶上。
            //
            // 去重依据：镜像快照是「上次 mirrorNow 时刻」的状态，
            // 本次进程的数据是那之后新增的 ⇒ 不重叠 ⇒ 累加正确。
            val bCutoff = System.currentTimeMillis() - (7L * 24 * 3600_000L)
            var mergedBuckets = 0
            for ((k, v) in snap.rawRuleBuckets) {
                val b = k.substringBefore('|').toLongOrNull() ?: continue
                if (b < bCutoff) continue                      // 超 7 天不合并
                val cur = hourlyByRule[k] ?: 0
                if (cur == 0) {
                    hourlyByRule[k] = v
                    hourlyHits.merge(b, v, Int::plus)
                    mergedBuckets++
                }
                // cur != 0 ⇒ 本次进程已有该桶数据 ⇒ 跳过（不重复累加）
            }
            // ④ 命中规则统计：只在内存为空时补（避免重复）
            if (filterHitStats.isEmpty()) {
                for ((k, v) in snap.stats) filterHitStats[k] = v
            }
            io.github.angbang852.manjiao.util.Logger.once("audit.restore") {
                "merged from mirror: records=${snap.records.size} total=${snap.total} " +
                    "buckets=$mergedBuckets stats=${snap.stats.size} ageMs=$age"
            }
            io.github.angbang852.manjiao.util.Logger.d("restoreFromMirrorIfEmpty done")
        } catch (_: Throwable) {}
    }

    /** 本进程是否已从镜像合并过（防重复累加） */
    @Volatile var mirrorRestoredAt = 0L

    /** 兼容旧调用名 */
    fun restoreFromMirrorLegacyIfEmpty() {}

    // ==================== 模块状态上报（2026-09-26 用户要求）====================
    //
    // 用户需求：
    // > 「模块菜单增加一项"模块状态"，子项："功能状态"
    // >   点开显示各个功能是不是能正常使用，有没有钩子没钩上的和解析失败的」
    //
    // ## 设计
    //
    // 各 hook / 解析点安装时调用 `noteHookStatus` / `noteParseStatus` 上报结果；
    // 「模块状态」页读 `hookStatusList` / `parseStatusList` 展示。
    //
    // **为什么用「主动上报」而不是「反射检查」**：
    //   · 反射只能查「类/方法存不存在」，查不到「hook 是否装成功」
    //   · 主动上报能带上**失败原因**（如「类不存在」「方法签名不符」）
    //   · 上报是安装时一次性写，**零运行时开销**
    //
    // 键 = 名称，值 = (成功?, 详情)。用 LinkedHashMap 保持**上报顺序**（可读性）。
    private val hookStatusMap = java.util.Collections.synchronizedMap(
        java.util.LinkedHashMap<String, Pair<Boolean, String>>()
    )
    private val parseStatusMap = java.util.Collections.synchronizedMap(
        java.util.LinkedHashMap<String, Pair<Boolean, String>>()
    )

    /** 上报一个钩子的安装结果 */
    fun noteHookStatus(name: String, ok: Boolean, detail: String = "") {
        try { hookStatusMap[name] = ok to detail } catch (_: Throwable) {}
    }

    /** 上报一个解析结果 */
    fun noteParseStatus(name: String, ok: Boolean, detail: String = "") {
        try { parseStatusMap[name] = ok to detail } catch (_: Throwable) {}
    }

    /** 读取钩子状态（按上报顺序） */
    fun hookStatusList(): List<Triple<String, Boolean, String>> =
        try {
            synchronized(hookStatusMap) {
                hookStatusMap.entries.map { Triple(it.key, it.value.first, it.value.second) }
            }
        } catch (_: Throwable) { emptyList() }

    /** 读取解析状态（按上报顺序） */
    fun parseStatusList(): List<Triple<String, Boolean, String>> =
        try {
            synchronized(parseStatusMap) {
                parseStatusMap.entries.map { Triple(it.key, it.value.first, it.value.second) }
            }
        } catch (_: Throwable) { emptyList() }

    /**
     * 启动**周期发布**（2026-09）。
     *
     * 为什么需要它：`mirrorSoon` 只在**命中时**调用。若一段时间没有命中
     * （过滤开关全关、或推荐流恰好干净），app 侧就永远收不到任何快照 ——
     * 用户看到的是「app 里没数据」，但**分不清是「确实没命中」还是「通道坏了」**。
     *
     * 现在起一个低频守护线程，无论有无命中都定期发布一次当前状态；
     * 这样 app 侧总能拿到一份「时间戳新鲜但计数为 0」的快照，语义明确。
     *
     * ★ 2026-09 调整：**审计关闭时不启线程**。
     * 原先无条件起线程，只为顺带写心跳；而心跳已不是激活判据（见 `SyncService.isActive`），
     * 在审计默认关闭的情况下，这个每 20 秒做一次完整快照的常驻线程纯属浪费
     * —— 它会跟着每个快手进程生命周期一直跑。现在只在真正有数据要发布时才起。
     * 保留 `writeHb()` 调用：审计开着时顺手写一次，成本可忽略，且万一通道将来打通就有用。
     *
     * ★★ 2026-09-23 修正（「更新模块后统计丢失」）：**恢复无条件启动**。
     * 上一版「auditEnabled=false 就 return」有个致命副作用：`doPublish()` 是
     * **统计数据的唯一落盘点**（`mirrorSoon` 只在命中时触发且 30s 节流），
     * 因此「审计关闭」时统计从不落盘 ⇒ 更新模块/重启快手后统计归零。
     * 而统计（总数/规则分布/分时桶）按设计是**独立于「记录证据」开关**always 可用的。
     * 代价：20 秒一次的快照（仅在内存里拼 JSON + 一次原子落盘，实测 ~毫秒级），
     * 相对「统计丢失」这个用户可见故障完全值得。
     */
    fun startPeriodicPublish() {
        if (publishThread != null) return
        // 立即写一次心跳（成本可忽略；不再是激活判据，仅备用）
        writeHb()
        // ★ 启动即落一次盘（2026-09-23）：把「刚恢复的历史」立刻固化为新格式/新时间戳，
        // 避免「冷启动 → 20 秒内被杀」这段窗口内镜像仍是旧内容（用户表现为「刚更新完
        // 又丢了一次」）。异步执行，不阻塞启动。
        try { mirrorExecutor.submit { doPublish() } } catch (_: Throwable) {}
        val t = Thread {
            while (true) {
                try {
                    Thread.sleep(PUBLISH_INTERVAL_MS)
                    // ★ 绕过节流：周期任务本身就是低频的，被节流挡住会退化成 60 秒一次
                    lastMirrorAt = 0L
                    writeHb()
                    doPublish()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {}
            }
        }
        t.name = "MJ-AuditPub"
        t.isDaemon = true
        t.start()
        publishThread = t
    }

    /**
     * 写一次模块心跳（2026-09）。
     *
     * ★ 2026-09 激活判定已改用框架下发的 `service.scope`（见 `SyncService.isActive`），
     * 心跳**不再是主判据**，保留它只是为将来可能打通的通道留一个入口。
     *
     * 当前两个通道都不通（已实测）：
     * - `rp` 共享配置：`SyncService.rp` 只在 **app 进程**的 `onServiceBind` 里赋值，
     *   快手进程里恒为 null ⇒ `rp?.edit()` 静默空转。
     * - `/sdcard/Android/media/<模块包名>/`：目录属主是模块（`u0_a224`，组 `media_rw`），
     *   快手以 `u0_a1305` 运行不在该组 ⇒ 写入 EACCES。
     *
     * 所以这里**不报错也不阻塞**，写不进去就算了 —— 界面的正确性不依赖它。
     */
    private fun writeHb() {
        try {
            io.github.angbang852.manjiao.data.SyncService.writeHeartbeat(
                android.os.Process.myPid()
            )
        } catch (_: Throwable) {}
    }

    private var publishThread: Thread? = null

    /** 周期发布间隔：20 秒（app 侧 30 秒节流内能拿到最新值） */
    private const val PUBLISH_INTERVAL_MS = 20_000L

    /** 采集当前状态并发布（在后台线程执行） */
    private fun doPublish() {
        try {
            val records = synchronized(hitRecords) { hitRecords.toList() }
            val stats = filterHitStats.entries.sortedByDescending { it.value }.map { it.key to it.value }
            val buckets = HashMap(hourlyByRule)
            // ★★ 修复「更新模块后拦截统计丢失」（2026-09-23 用户报告）：
            //   `AuditMirror.write` 在早前重构中**漏接**（全项目零调用），
            //   导致文件镜像自 2026-09-22 18:38 起再未更新 —— 于是：
            //     更新模块 → 重启快手 → 内存清零 → restoreFromMirrorIfEmpty 只能读回
            //     上一次落盘的旧数据，其后所有统计全部丢失。
            //   落盘是**唯一**能跨进程重启存活的通道（AuditBridge 广播通道的 app 侧
            //   消费者不存在），必须在这里补回。与广播发布复用同一份快照，无额外遍历。
            io.github.angbang852.manjiao.data.AuditMirror.write(
                total = hitTotal,
                baselineAt = statsBaselineAt,
                records = records,
                stats = stats,
                hourly = emptyList(),      // 分时桶走 hourlyByRule（rawRuleBuckets），读取端据此重建
                hourlyByRule = buckets,
                allowCount = 0
            )
            val snap = io.github.angbang852.manjiao.data.AuditSnapshot().apply {
                writtenAt = System.currentTimeMillis()
                total = hitTotal
                baselineAt = statsBaselineAt
                allowCount = 0   // 「标为误拦」已移除；字段保留保持镜像格式兼容
                this.records = records.map { r ->
                    io.github.angbang852.manjiao.data.AuditSnapshot.Rec().apply {
                        t = r.at; rule = r.reason; photoId = r.photoId
                        caption = r.caption; user = r.user
                    }
                }
                this.stats = stats
                rawRuleBuckets = buckets
            }
            io.github.angbang852.manjiao.data.AuditBridge.publish(snap.toJson())
        } catch (_: Throwable) {}
    }

    /**
     * 立即发布一次快照并返回 JSON（**供广播应答同步调用**）。
     *
     * 与 [mirrorSoon] 不同：这里是同步的，因为广播接收器需要在 onReceive 内
     * 拿到内容才能回包；调用点是「用户打开设置页才触发」的低频路径，可接受。
     */
    fun snapshotJson(): String {
        return try {
            val records = synchronized(hitRecords) { hitRecords.toList() }
            val snap = io.github.angbang852.manjiao.data.AuditSnapshot().apply {
                writtenAt = System.currentTimeMillis()
                total = hitTotal
                baselineAt = statsBaselineAt
                allowCount = 0   // 「标为误拦」已移除；字段保留保持镜像格式兼容
                this.records = records.map { r ->
                    io.github.angbang852.manjiao.data.AuditSnapshot.Rec().apply {
                        t = r.at; rule = r.reason; photoId = r.photoId
                        caption = r.caption; user = r.user
                    }
                }
                stats = filterHitStats.entries.sortedByDescending { it.value }.map { it.key to it.value }
                rawRuleBuckets = HashMap(hourlyByRule)
            }
            snap.toJson()
        } catch (_: Throwable) { "" }
    }

    /**
     * 用户点了「清空记录 / 重置统计」后立即重新镜像一次（绕过节流）。
     *
     * 否则用户点完清空、马上切到 app 页，看到的还是 30 秒前的旧数据 —— 会以为没生效。
     */
    fun mirrorNow() {
        lastMirrorAt = 0L
        mirrorSoon()
    }

    /** 镜像节流间隔：30 秒与 app 侧 5 秒轮询配合，最坏 35 秒可见新命中 */
    private const val MIRROR_INTERVAL_MS = 30_000L

    // ==================== 分时统计（24h / 48h / 7d，2026-09 用户要求） ====================

    /** 分时段命中计数：key = 桶起点（epoch ms，5 分钟对齐），value = 该桶命中数 */
    val hourlyHits: java.util.concurrent.ConcurrentHashMap<Long, Int> = java.util.concurrent.ConcurrentHashMap()

    /** 分时段按规则细化：key = "桶起点|规则前缀"，用于按时间窗出图 */
    val hourlyByRule: java.util.concurrent.ConcurrentHashMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    /**
     * 分时桶粒度：**5 分钟**（2026-09 用户定稿「细化到 5 分钟」）。
     *
     * 原为 1 小时桶 —— 24h 窗口只有 24 个点，孤立拦截只能画成 1/24 宽的窄针；
     * 参考图的细腻曲线来自高密度采样。5 分钟桶让 24h = 288 点、7d = 2016 点，
     * 曲线形态与真实分布一致（用户原话确认「就是时间取的大概时间，画不到那么细」）。
     */
    const val BUCKET_MS = 5L * 60_000L

    /** 分时数据保留：7 天 = 7*24*12 = 2016 个 5 分钟桶 */
    const val BUCKET_KEEP = 7 * 24 * 12

    /**
     * 记录一次命中到分时桶。
     *
     * 桶按 **5 分钟对齐**（`at / BUCKET_MS * BUCKET_MS`）。与 [hitRecords]
     * （逐条环形缓冲）不同，这里是**长期聚合**：只为出图服务，单条成本 = 一次自增。
     */
    fun recordHourly(at: Long, reason: String) {
        try {
            val bucket = at / BUCKET_MS * BUCKET_MS
            hourlyHits.merge(bucket, 1, Int::plus)
            val head = if (reason.contains(':')) reason.substringBefore(':') else reason
            hourlyByRule.merge("$bucket|$head", 1, Int::plus)
            // 定期清理过期桶（每 256 次触发一次，避免每次都扫）
            if (hourlyHits.size > BUCKET_KEEP && (hitTotal and 0xFF) == 0) {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 3_600_000L
                hourlyHits.keys.removeIf { it < cutoff }
                hourlyByRule.keys.removeIf { k -> (k.substringBefore('|').toLongOrNull() ?: 0L) < cutoff }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 取最近 [hours] 小时的命中序列（按 5 分钟桶升序，缺失桶补 0）。
     *
     * @return 每项 = (桶起点时间戳, 该桶命中数)
     */
    fun hourlySeries(hours: Int): List<Pair<Long, Int>> {
        val now = System.currentTimeMillis()
        val endBucket = now / BUCKET_MS * BUCKET_MS
        val n = hours * 12
        val out = ArrayList<Pair<Long, Int>>(n)
        for (i in n - 1 downTo 0) {
            val b = endBucket - i * BUCKET_MS
            out.add(b to (hourlyHits[b] ?: 0))
        }
        return out
    }

    /** 取最近 [hours] 小时内、按规则前缀聚合的命中数（降序） */
    fun ruleTotalsInWindow(hours: Int): List<Pair<String, Int>> {
        val cutoff = (System.currentTimeMillis() / 3_600_000L * 3_600_000L) - (hours - 1) * 3_600_000L
        val agg = HashMap<String, Int>()
        for ((k, v) in hourlyByRule) {
            val sep = k.indexOf('|')
            if (sep <= 0) continue
            val bucket = k.substring(0, sep).toLongOrNull() ?: continue
            if (bucket < cutoff) continue
            agg.merge(k.substring(sep + 1), v, Int::plus)
        }
        return agg.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    var quickDebugCount = 0
    /** DLCAP 捕获日志限次计数（CfhCapture.currentFeedPhoto，清洗热路径，M1 修复 2026-09） */
    var dlCapDiag = 0
    /** DL ownpid 匹配日志限次计数（CfhCapture.fragmentOwnPhoto，M1 修复 2026-09） */
    var dlOwnPidDiag = 0
    /** T1CALL 限次计数（CfhFeedHook.installVmT1，rerank 单条插入入口，M1 修复 2026-09） */
    var t1CallDiag = 0
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
    /** 最近一次 Activity onResume 时刻：供「手动点击 vs 自动跳转」的时长兜底判据 */
    @Volatile var lastPageSwitchAt = 0L
    /**
     * 脏项 photoId 黑名单（判脏即入，见 CfhDecide.hit）。
     * 用途：同一条内容可能被多条引用持有（数据源列表 / rerank 快照 / pager adapter /
     * Fragment 字段），从数据源删除删不到其余引用，它会以「另一条」的形式重回屏幕。
     * 实证 probe11：删除在 44.748，屏幕渲染在 46.665，二次判脏在 47.073（迟 0.4s）。
     * 记 id 后任何路径再遇即毫秒级判脏，消除该窗口。
     * 上限 512 防无界增长（启动期脏项密度最高，512 足够覆盖一次会话的可见窗口）。
     */
    val dirtyPhotoMap: java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * ★★★ 黑名单写入（2026-09-28 修复：满了**删最旧**，不再全清空）。
     *
     * ## 为什么改（用户实测「首页详情页刷视频还有脏项」）
     *
     * 原实现 6 处 `size >= 512 → clear()`：黑名单一满 512 就**全部清空**，
     * 30 分钟内所有判脏记录蒸发 ⇒ 详情页 `DLFEED-BLOCK` 的黑名单补拦
     * （实证 337 条全有效）瞬间失效 ⇒ 已判脏内容重新上屏。
     *
     * 改为满了「先删过期（30 分钟外）、再删最旧的一半」，保留最近判脏记录：
     * · 30 分钟过期条目本来就无效（isBlacklisted 不查它们）
     * · 删最旧一半 = 保留最新 256 条，覆盖当前会话可见窗口
     * · ConcurrentHashMap 弱一致迭代 + 按 value.second 排序，无并发崩溃
     */
    fun noteDirty(pid: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        // ★★★ 2026-09-28 紧急修复（evidence_0928o 铁证）：
        //   `isBlacklisted` 命中时把 lastHitReason 设为 "blacklist:<原因>"，
        //   调用方又拿 lastHitReason 调 noteDirty ⇒ 黑名单存的 reason 带 blacklist: 前缀；
        //   下次短路再套一层 ⇒ 无限嵌套（实证 50+ 层 `blacklist:blacklist:...`）。
        //   reasonStillEnabled 对 blacklist: 前缀走 else→true（永不过期）⇒
        //   被短路过的条目永远拦截 ⇒ 精选页干净内容被拦死、刷不出。
        //   修复：存储时递归剥离 blacklist: 前缀，只留原始判据。
        var cleanReason = reason
        while (cleanReason.startsWith("blacklist:")) {
            cleanReason = cleanReason.removePrefix("blacklist:")
        }
        if (dirtyPhotoMap.size >= 512) {
            try {
                val expired = ArrayList<String>()
                for ((k, v) in dirtyPhotoMap) {
                    if (nowMs - v.second > 30 * 60_000L) expired.add(k)
                }
                for (k in expired) dirtyPhotoMap.remove(k)
            } catch (_: Throwable) {}
            if (dirtyPhotoMap.size >= 512) {
                try {
                    val sorted = dirtyPhotoMap.entries.sortedBy { it.value.second }
                        .take(dirtyPhotoMap.size / 2)
                    for (e in sorted) dirtyPhotoMap.remove(e.key)
                } catch (_: Throwable) {}
            }
        }
        dirtyPhotoMap[pid] = cleanReason to nowMs
    }
    /** rerank 重灌链探针计数（方案 A：定位 d.u/d.v 实际改写的容器） */
    @Volatile var rerankPeek = 0
    // ★ willGateCount/willGateNoCleanCount/dwellGateCount 已随替换式换条废弃删除
    //   （2026-09-25 用户定稿「就删脏项就行了」——换条制造文案/标签与视频错配）
    //
    // ★★ scrubShownCount / fragHolderScrubCount / fragHolderDiag 三个计数器
    //    已随**展示字段清洗功能整体删除**而失效（2026-09-26 用户定稿
    //    「删掉这个功能，完全没用还干扰正常使用」）。
    //    保留字段仅为兼容历史证据中的标签，**不再被任何代码写入**。
    //    完整删除理由见 `CfhWash.scrubShownDirty` 的 KDoc。
    /** @deprecated 展示字段清洗已删除（2026-09-26），不再写入 */
    @Volatile var scrubShownCount = 0
    /** @deprecated 单值字段兜底清洗已删除（2026-09-26），不再写入 */
    @Volatile var fragHolderScrubCount = 0
    /** @deprecated 单值字段诊断已随该功能删除（2026-09-26），不再写入 */
    @Volatile var fragHolderDiag = 0
    /**
     * 最近一次发现脏项的时刻（追打模式：之后 10s 内轮询加密到 200ms）。
     *
     * ★ 读写点与「自持闸」（2026-10-01）—— 改本字段前**必读**：
     *   · **读点**：全工程仅 1 处 —— [CfhWash] 周期循环 `hunt = now - 本字段 < HUNT_WINDOW_MS`
     *     （`CfhWash.kt:891`），决定 tick 取 200ms 还是 1000ms。
     *   · **写点**：全工程仅 1 处 —— `CfhWash.periodicScanVisible` 判到脏项时
     *     （`CfhWash.kt:1224`），并且**就在巡检自己体内**。
     *   · **重置点**：**无**。
     *   ⇒ 「巡检判脏」→ 写 → 下一轮读到「还在 10 秒内」→ 继续 200ms →
     *     下一轮又判到同一条脏内容 → 又写 ⇒ 结构上是一个**正反馈闭环**。
     *   现由 [huntArmedPids] 把它改为「只由**新**脏源武装」，见该字段 KDoc。
     */
    @Volatile var lastDirtySeenAt = 0L
    /**
     * ★★★ 追打窗口的「武装来源」记录 —— 阻断 [lastDirtySeenAt] 的自我维持闭环（2026-10-01）。
     *
     * ## 闭环结构（行号见 CfhWash）
     * 读点 `CfhWash.kt:891`、写点 `CfhWash.kt:1224`、**无重置点**，
     * 且写点就在巡检体内 ⇒ 「本轮判到脏」这个结论会被写回去喂给下一轮。
     *
     * ## 自持的入口条件（三条同时成立才会自持；缺一即不会发生）
     * ① 数据层真能见到脏项（`CfhDecide.judgeFeed` 判 true）。
     *    当前配置下**不成立**：网络层白名单已把脏项拦在网络层，
     *    长窗口实测（40~230 秒、含持续滑动）判脏=true **恒为 0 次**、
     *    `SRCDIRTY` 反复「真源干净」、tick 始终 ≈1.04s。
     * ② 该脏项在**超过 10 秒**的尺度上反复出现在巡检候选集里
     *    （删不掉 / 被渲染层重灌 / 网络层漏拦）。历史上真出现过
     *    （见 `CfhWash.kt:1199-1204` 记的「判脏=true 后 4 秒还在、又 17 秒后还在」）。
     * ③ 巡检每轮都**重新**判它 —— **恒真**：`periodicVisSeen`（本文件 :1016）
     *    只被 add（`CfhWash.kt:1192`）、**全工程无任何读取点**，
     *    KDoc 里所谓「按 id 去重」实际未生效。
     *
     * ## 自持的代价（为什么值得闸）
     * 追打态下 `Thread.sleep(200ms)`（`CfhWash.kt:892`），而 `filterVmListsInner`
     * （`CfhWash.kt:902`，3~5 趟对象图 BFS、单趟上限 3000 节点）**没有毫秒门控**，
     * ⇒ 常驻 5 次/秒（`docs/性能审核-热路径.md` 第 4 条，评级「收益：大」）。
     * 2026-09-30 的毫秒门控只把 PSCAN / KWSEARCH / VMSURVEY 从 tick 解耦，
     * 这条最重的路径仍是每 tick 一次。
     *
     * ## 本集合的语义（★ 2026-09-29 收尾核对：闸门**尚未接线**，见下）
     *
     * **设计语义**（原本要实现的）：只由巡检体内那**一个**写点维护 —— 某 pid
     * **首次**被判脏 ⇒ 武装窗口（保留原设计：之后 10 秒 200ms 追打）；同一 pid 的
     * **重复**判脏 ⇒ **不续期** ⇒ 窗口 10 秒后自然退回 1000ms ⇒ 自持链被切断。
     * 新脏 pid 到达仍照常武装 —— 即「写点只由真实新脏源驱动」。有界：写点侧满
     * 4096 先清空再记（单轮判脏候选上限 60，正常远达不到）。
     *
     * **当前实际状态**：本集合**只被读、从未被写**。
     *   · 唯一读点：`CfhWash.kt:949` —— 仅取 `.size` 打进 `追打自持` 诊断日志。
     *   · 写点：**不存在**。`CfhWash.kt:1290` 仍是无条件
     *     `CfhState.lastDirtySeenAt = System.currentTimeMillis()`。
     *   ⇒ 上面的「自持链被切断」**当前并未生效**，闭环照旧是活的；
     *     `huntArmedPids` 目前只是一块**诊断预留**（观测追打是否被喂活）。
     *
     * ★ 为什么保留而不删：它不参与任何判定/放行，删掉只会丢失观测面。
     * ★ 威胁（为什么必须写清楚，不能靠注释假装已实现）：若误以为闸门已生效，
     *   就会把「自持复发」误判为「新脏源持续到达」，从而去错误的位置排查 ——
     *   这正是注释与代码不符最危险的形态（比没有注释更坏）。
     * ★ 接线前不得改本 KDoc 的「当前实际状态」段；接线时应把无条件写点改为
     *   「首次才写 + 满 4096 先清空」，并同步更新此处。
     */
    val huntArmedPids = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    // ================= 拦截分层开关（2026-09-26 用户要求「隔离数据层观察」）=================
    //
    // ## 用途
    //
    // README 对分层的定义：
    //   · **数据层清洗** = `filterVmLists` / `laFind` / `sanitize`
    //   · **渲染层只做零干扰的可见性观察**
    //
    // 用户要求「把所有除了数据层的拦截其他都关掉，然后来探针观察」——
    // 目的是**让数据层单独暴露**，看清它到底能不能兜住全部脏项，
    // 从而判断「修法是否该上移到 L1（响应层直接不接收）」。
    //
    // ★★★ 已删除：分层诊断开关（2026-09-26 用户定稿）。
    //
    // ## 用户原话
    //
    // > 「说的什么东西，就是开 ai 拦截就拦截 ai，关闭 ai 拦截就放行，其他同理」
    //
    // ## 为什么删
    //
    // 用户说得对 —— 我造的这套「分层开关」把**两件毫不相干的事**混在了一起：
    //
    // | 类别 | 归属 | 谁控制 | 存哪 |
    // |---|---|---|---|
    // | **产品功能开关** | 用户功能 | **用户**（设置页） | `Prefs` / `slowkick.properties` |
    // | ~~分层诊断开关~~ | ~~我的排障手段~~ | ~~我~~ | ~~`layers.txt`~~ |
    //
    // **产品语义很简单：开了拦 AI 就拦 AI，关了放行；其它同理。**
    // 我那套东西不表达任何产品语义，只会：
    //   · 让「为什么没拦住」的排查多一层干扰（实测两次误导：
    //     第 1 次误关数据层、第 2 次误关可见页拦截，都被当成"漏拦"）
    //   · 在用户可写目录留下一个能**静默削弱拦截能力**的文件
    //
    // ⇒ **全部删除**。排障需要临时开关时，用日志/探针，不动拦截逻辑。
    //
    // ## 产品功能开关在哪（这才是唯一该关心的）
    //
    // `Prefs`（设置页）：`flt_ai` / `flt_live` / `flt_ads` / `flt_advideo`
    // / `flt_image` / `flt_drama` / `flt_ec` / `flt_like_on` / `flt_kw_on` …
    // 语义：**开 = 拦这一类，关 = 放行这一类。**

    // ================= 白名单模式（2026-09-26 用户定稿）=================
    //
    // ## 用户原话
    //
    // > 「还是要改成白名单，要判正常的才能放行，不然前几个就算判脏了也删不到。
    // >   必须文案、标识、昵称全部干净才能放行，文案、标识、昵称还没到位
    // >   识别不到的也要先等着齐了判正常了才能进。」
    //
    // ## 为什么必须反转模型
    //
    // 实测旧模型（黑名单）下「前几条必漏」，根因是**删除追不上渲染**：
    //   · 返回值快照 `H/V/F` 每次重建 ⇒ 删 391 次、99.6% 无效
    //   · 真源 `l.a/h.j` 是包装类 ⇒ 提不到 QPhoto
    //   · 卡片容器 `Tangram/Kmp` ⇒ 无清洗路径
    // ⇒ 判脏正确也删不到。**只有从源头不放行才能失效这三条通路。**
    //
    // ## ⚠️ 风险与默认值
    //
    // 白名单是**拦截模型反转**，最大风险是**造成「无更多作品」**
    // （项目已两次栽在此：`CfhWash.kt:2302-2320` 的二分排除记录）。
    // 故：
    //   · **默认 `false`** —— 不改变既有行为
    //   · 有 3 秒超时兜底（`CfhDecide.WHITE_MAX_WAIT_MS`）
    //   · 必须显式开启并真机观察后再考虑默认开
    @Volatile var whiteListEnabled: Boolean = false
    /**
     * 字段未齐的等待起点（photoId → 起始时刻）。
     *
     * **方案 A 后仅用于"算等了多久"以便告警** —— 不再用于超时放行。
     * 见 `CfhDecide.WHITE_PEND_ALERT_MS` 的说明。
     */
    val whitePendingSince = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** WHITE 证据落盘限次 */
    @Volatile var whiteLogCount = 0
    /** WHITETIMEOUT 证据落盘限次（已废弃 —— 方案 A 取消超时放行） */
    @Volatile var whiteTimeoutLog = 0
    /** WHITEPEND 落盘限次（首次「字段未齐→扣下等待」） */
    @Volatile var whitePendLog = 0
    /** WHITESTUCK 落盘限次（长期未齐告警，**仍然不放行**） */
    @Volatile var whiteStuckLog = 0
    /** WHITEDIAG 三态统计落盘限次（前 30 次）—— 区分「没调用」/「全 WHITE」/「全 PENDING」 */
    @Volatile var whiteDiagCount = 0
    /** WHITEENTRY 入口留痕限次 —— 先证明「函数被调用了」 */
    @Volatile var whiteEntryCount = 0

    // ============ VMSHOW 渲染拦截（2026-09-26 追「没判明就不放行」）============
    //
    // `installVmShow` hook 的是「参数含 QPhoto 且返回 void」的方法 ——
    // 即**快手的渲染/绑定调用本身**。用 `return@intercept null` 让它不发生。
    //
    // 这是「不放行」的真正落点：不是删数据（实测模块拿到的永远是副本，
    // `同一对象=true` 0 次），而是**不让这条 QPhoto 被用**。
    //
    // 详见 `CfhFeedHook.installVmShow` 的 KDoc。
    /** 安装留痕（区分「没安装」与「装了没命中」—— 此前用 Logger.safe 无法区分） */
    @Volatile var vmShowInstallLog = 0
    /** 挡下渲染的留痕限次 */
    @Volatile var vmShowBlockLog = 0
    /** 渲染方法被调用的留痕限次（区分「没调用」与「调了都判干净」） */
    @Volatile var vmShowCallLog = 0
    /** PageList 类存在性留痕限次（区分「类名过时」与「hook 失败」） */
    @Volatile var pageListMissLog = 0

    // ========== QPCHK：接入快手「无效内容」过滤器（2026-09-26）==========
    //
    // 逆向发现：快手有 `QPhoto.sInvalidFeedCheckerList` + `recognizeAsInvalidData()`，
    // 在数据入口（`corona.common.utils.f.A/B/C/z` → `xp9.n.f/g`）逐个判定，
    // 判为无效则**从列表移除 + 补位**（`w7.b().c(photoId)`）。
    //
    // 本组状态用于验证该机制是否真的被调用、效果如何。
    // 详见 `CfhQPhotoChecker` 的完整说明。
    /** 校验器被调用的留痕限次 */
    @Volatile var qpChkLog = 0
    /** 判为无效（触发快手移除）的留痕限次 */
    @Volatile var qpChkBlockLog = 0
    /** QPCHK-RIM 自证：recognizeAsInvalidData 被调用的留痕限次 */
    @Volatile var qpRimLog = 0

    // ========== CHAIN：快手过滤器链条存在性自证（2026-09-26）==========
    //
    // 实测 `recognizeAsInvalidData` 零调用。为区分「链条整个没跑」与
    // 「跑了但内部短路」，逐一 hook 链条各环节。
    // 详见 `CfhQPhotoChecker.verifyFilterChain`。
    /** 链条探针落盘限次 */
    @Volatile var chainLog = 0
    /** 各环节命中计数（tag → 次数），用于验证后汇总 */
    val chainHit = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // ========== TTPPARSE：响应解析层拦截（2026-09-26）==========
    //
    // hook `KnownTypeAdapters$ListTypeAdapter.read()` —— QPhoto 被反序列化创建的那一刻。
    // 逆向确认这是**所有下游的源头**（此前三条路线都在下游副本上操作而失败）。
    // 详见 `CfhTtpParse` 的完整说明。
    /** TTPPARSE 证据落盘限次 */
    @Volatile var ttpLog = 0
    /** TTPPARSE-BLOCK 落盘限次 */
    @Volatile var ttpBlockLog = 0

    // ========== 白名单溯源探针（2026-09-26 追「getItems 路径为何漏拦」）==========
    /** WHITECALLER 栈回溯限次 */
    @Volatile var whiteCallerLog = 0
    /** RESPRET 响应返回值类型留痕限次 */
    @Volatile var respRetLog = 0

    // ========== 已清短路（2026-09-26 消除数据层冗余）==========
    //
    // 解析期（TTPPARSE）已判脏并清除的 photoId 集合。
    // 数据层（filterResult）再遇到时跳过重复判定 —— 它们永远上不了屏。
    //
    // 起因：用户问「网络解析层有拦截了，怎么数据层还有这么多拦截记录」。
    // 实测 TTPPARSE-BLOCK=80 而 DEL=22，且 DEL 首项长期是同一内容（真源 hash 恒定）。
    // 详见 `CfhClean.filterResult` 的 SHORTCIRCUIT 说明。
    val ttpClearedIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    /** SHORTCIRCUIT 证据落盘限次 */
    @Volatile var shortCircuitLog = 0

    // ========== 白名单放行/挡下留痕（2026-09-26 追「具体哪条被放行」）==========
    //
    // 此前只记「挡下 N 条」，无法回答「为什么这一条没被挡」。
    // 用户报「Vlog.嘟嘟第一次坐飞机」反复漏拦（120 次死循环），正是缺这个对照。
    /** WHITEPASS 留痕限次（判干净 ⇒ 放行的具体 id） */
    @Volatile var whitePassLog = 0
    /** WHITEBLOCK 留痕限次（判脏 ⇒ 挡下的具体 id） */
    @Volatile var whiteBlockLog = 0

    // ========== 屏幕取数拦截（2026-09-26 补最后一道关口）==========
    //
    // `SlidePlayViewModel.x4(int)` 这类「按位置取条」方法是屏幕对象的来源，
    // 实测它**不在任何已有拦截路径上** —— 这解释了多条零记录漏拦。
    // 详见 `ScreenTrace.installByFeature`。
    /** SCREEN-BLOCK 落盘限次 */
    @Volatile var screenBlockLog = 0

    // ========== 数据源探针（2026-09-26）==========
    //
    // 找「屏幕内容的最终数据源」：hook `SlidePlayViewModel.P0(String)`，
    // 抓 `kwai_data_source_service` 返回的对象 —— 那是 `K(int)`/`x4(int)` 的委托目标。
    // 详见 `ScreenTrace.probeDataSource`。
    /** DSSRC 落盘限次 */
    @Volatile var dsSrcLog = 0
    /** DS-BLOCK 落盘限次（数据源列表清除） */
    @Volatile var dsBlockLog = 0
    /** CALLSTACK 落盘限次（回答「内容怎么进来的」） */
    @Volatile var callStackLog = 0
    /** DSWRITE-BLOCK 落盘限次（数据源写入拦截 = 内容进列表那一刻） */
    @Volatile var dsWriteLog = 0
    /** WASHSEL 落盘限次（精选页定向清洗） */
    @Volatile var washSelLog = 0
    /** WASHSEL 入口诊断计数 */
    @Volatile var washSelDiag = 0

    // ========== 完整取证埋点（2026-09-26 用户要求）==========
    //
    // 四项：① 接收间隔 ② 是否积压 ③ 判脏/放行比例 ④ 处理耗时
    /** TTPPARSE-RECV 落盘限次（① 接收间隔） */
    @Volatile var ttpRecvLog = 0
    /** 上次收到数据的时刻（算间隔用） */
    @Volatile var ttpLastRecvAt = 0L
    /** 单批最大条数（② 积压判定） */
    @Volatile var ttpMaxBatch = 0
    /** TTPPARSE-STAT 落盘限次（③④） */
    @Volatile var ttpStatLog = 0
    /** 累计：批次数 */
    @Volatile var ttpBatchTotal = 0
    /** 累计：放行条数 */
    @Volatile var ttpPassTotal = 0
    /** 累计：判脏条数 */
    @Volatile var ttpDirtyTotal = 0
    /** 累计：待判（PENDING）条数 */
    @Volatile var ttpPendingTotal = 0
    /** 累计：处理总耗时（ms） */
    @Volatile var ttpCostTotalMs = 0L
    /** TTPPARSE-SLOW 落盘限次（单条判定耗时超 20ms 的记录） */
    @Volatile var ttpSlowLog = 0
    /** NETCOST 落盘限次（网络层耗时分层） */
    @Volatile var netCostLog = 0
    /** HOMEPROBE 落盘限次 */
    @Volatile var homeProbeLog = 0
    /** HOMEPROBE-HIT 落盘限次 */
    @Volatile var homeHitLog = 0
    /** HOMECARD 落盘限次（首页卡片探针） */
    @Volatile var homeCardLog = 0
    /** VMSHOW 诊断落盘限次 */
    @Volatile var vmShowDiagLog = 0
    /** PRESENTERV2 落盘限次 */
    @Volatile var pv2Log = 0
    /** CARDPRES 落盘限次（卡片 Presenter 探针） */
    @Volatile var cardPresLog = 0
    /** CARDFLD 落盘限次 */
    @Volatile var cardFldLog = 0
    /** DUMPD 落盘限次 */
    @Volatile var dumpDLog = 0
    /** HOMEROOT 落盘限次 */
    @Volatile var homeRootLog = 0
    /** LISTADD 落盘限次 */
    @Volatile var listAddLog = 0
    /** LISTADD-BLOCK 落盘限次 */
    @Volatile var listAddBlockLog = 0
    /** FEATCHK 落盘限次 */
    @Volatile var feedChkLog = 0
    /** KR-BLOCK 落盘限次（首页信息流拦截） */
    @Volatile var krBlockLog = 0
    /** STAGGER-BLOCK 落盘限次（首页信息流拦截） */
    @Volatile var staggerBlockLog = 0
    // ★ 重建时补的状态字段（原误删，2026-09-26）
    /** MINDEL / QPDEL 落盘限次 */
    @Volatile var minDelCount = 0
    /** SRCDIRTY 落盘限次 */
    @Volatile var srcDirtyCount = 0
    // ★★★ photoId → 声明内容 映射（2026-09-27 用户定稿「映射」）。
    //
    // ## 背景（完整证据链）
    //
    // 详情页漏拦内容（小安科普/咔咔剪/颖姐姐等）的公共模式：
    //   · 解析期（TTPPARSE）：原始 QPhoto 的声明字段完整（服务器 JSON 直出）
    //   · 取数期（x4/bind）：快手把数据**浅拷贝**进 VM/详情页 ⇒ 副本声明字段丢失
    //   · 判定读副本 ⇒ 声明空 ⇒ 判白 ⇒ 放行 ⇒ 上屏（角标由 UI 层另一路渲染）
    //
    // ⇒ 解析期把 photoId → 声明内容 记下（原始实例数据最全），
    //   下游拦截点判定时加查此映射 —— 副本字段缺失也能命中。
    //
    // ## 容量：4096，满则清空（与 feedSigCache 同策略）
    val declByPhotoId: java.util.concurrent.ConcurrentHashMap<String, String> =
        java.util.concurrent.ConcurrentHashMap()

    /** 判定时查询：本实例声明为空，但解析期原始实例带 AI 声明 ⇒ 返回声明内容 */
    fun lookupDecl(pid: String?): String? {
        if (pid.isNullOrBlank()) return null
        return try {
            declByPhotoId[pid]?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
    }

    // ==================== ADPROBE：ad-detail NPE 归因探针（2026-10-01）====================
    //
    // ## 这条探针要回答的问题（唯一目的）
    //
    //   历史实测崩溃（快手自身进程，模块侧只观测不干预）：
    //     java.lang.NullPointerException: mLogListener must not be null
    //   —— 出现在 `AdDetailVMFragment` 相关路径，当天 2 次
    //   （一次 06:16:16 pid 3872，另一次约 15:5x）。
    //   已排除项：崩溃栈里**零模块帧**；全仓 grep `AdDetail|mLogListener|rightactionbar`
    //   **零命中**；之后两次冷启未复现。
    //   ⇒ 结论悬空：**既不能确认是快手自身 bug，也不能排除是模块**
    //     （注入/净化动作的副作用）。
    //
    //   本探针把「崩溃发生的那个时间窗内，模块是否正在对广告详情相关的
    //   视图/数据做注入或净化动作」变成**可对齐的事实**：
    //   事后拿崩溃时刻（毫秒）与 ADPROBE 行做时间窗比对，即可切开两种可能
    //   —— 窗口内无 ADPROBE ⇒ 与模块动作无关；有 ⇒ 指向模块副作用。
    //
    // ## 为什么门控用「滑动窗口节流」而不是「进程级限次」
    //
    //   探针挂在 `PresenterV2.bind` / `SlidePlayViewModel.x4` 两个**热路径**上。
    //   项目里既有的 `xxxLog < N` 限次有个已知形态问题（见 RateLimiter 的类注释：
    //   `TTPPARSE-BLOCK=80`/`PV2-BLOCK=60` 正好卡在限次 ⇒ 之后全部静默）——
    //   而本探针最不能接受的就是「崩溃前那段静默」。所以走 RateLimiter 滑动窗口：
    //     · 两个调用点各自独立窗口、各 30 次/分钟 ⇒ **合计上限 60 行/分钟**；
    //     · 持续有记录（不是前 N 次之后就没有），又能反映真实动作量。
    //   **预期频率：稳态 ≤60 行/分钟（约 ≤1 行/秒），空闲时 0 行。**
    //   对照刷屏级诊断（实测 ~80 行/秒）低两个数量级。
    //
    // ## 记录纪律（严禁用户数据）
    //
    //   只记：毫秒时间戳（Logger.writePlain 自带前缀）+ 动作类型 + **类名/方法名** + 计数。
    //   **不记**昵称、文案、photoId、URL —— 与本模块既有证据纪律一致。
    //
    // ## 行为不变
    //
    //   纯观测：不改任何判定/放行/注入语义，不吞任何异常
    //   （这里**不是**为了「修」这个 NPE，是为了观测它）。
    /** ADPROBE 探针计数（进程级；供诊断读出与自检，不影响任何判定） */
    @Volatile var adProbeCount = 0

    /** IGUARD 落盘限次（inject 崩溃护栏） */
    @Volatile var injectGuardLog = 0
    /** QPHOTO-BLOCK 落盘限次（QPhoto 诞生拦截） */
    @Volatile var qphotoBirthLog = 0
    /** READ-PROBE 落盘限次（read 返回类型分布） */
    @Volatile var readProbeLog = 0
    /** DLFEED-BLOCK 落盘限次（详情页取数拦截） */
    @Volatile var dlFeedLog = 0
    /** TTPPARSE2-CALL 落盘限次（deserialize 调用探针） */
    @Volatile var deserProbeLog = 0
    /** DECLSCRUB 落盘限次（PDEL-MISS 清声明字段） */
    @Volatile var declScrubCount = 0
    /** SRCELEM 落盘限次 */
    @Volatile var srcElemCount = 0

    /** WHYWHITE 落盘限次（判白诊断） */
    @Volatile var whiteWhyLog = 0
    /** PV2-BLOCK 落盘限次（Presenter 数据拦截） */
    @Volatile var pv2BindLog = 0
    /** HOMEFEED 落盘限次 */
    @Volatile var homeFeedLog = 0
    @Volatile var homeFeedBlockLog = 0

    // ================= 入口追踪（2026-09-26 用户要求「定位入口」）=================
    //
    // ## 要回答的问题
    //
    // 实测矛盾：`WHITE`（白名单挡住）30 次 vs `VISDEL`（删除动作）438 次，
    // 且被追的脏内容**全程无 WHITE 记录** ⇒ **白名单挂的 10 个点不是主入口**。
    //
    // 本组状态用于「记录每条内容**首次**进入判定时的调用者」，
    // 拿已知漏拦 id 比对即可确定真正的入口。
    // 详见 `CfhDecide.shouldFilterFeed` 开头的 ENTRYTRACE 探针说明。
    /** 已追踪过的 photoId（只记首次） */
    val entryTraceSeen = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    /** ENTRYTRACE 落盘限次 */
    @Volatile var entryTraceCount = 0
}
