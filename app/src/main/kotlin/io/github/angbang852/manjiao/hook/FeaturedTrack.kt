package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger

/**
 * 精选页「当前条」跟踪（2026-09-23）。
 *
 * ## 要解决的问题
 *
 * 用户实测（精选页一条条刷）：
 * - 「文案不变、画面在变」
 * - 「文案是『跟我野子』，但视频不对」
 * - 「卡片上显示的图片是上一个视频的图片，文案是当前的」
 *
 * 根因（已查明）：模块的「当前条」跟踪**停在旧条目上不动**。实测证据：
 * 最后一次 `DL vis` 在 01:02:51，而屏幕内容已是 01:10:20 —— **滞后 7.5 分钟**。
 * 因为 `diagFragment` 只挂在 `NasaPhotoDetailFragment.onResume` 上，
 * 而精选页的 `HomeFeaturedMilanoContainerFragment` **全项目零处理**
 * —— 它不会触发 `onResume`，也就永远不刷新「当前条」。
 *
 * ## 为什么不用「读取精选页容器字段」这条路
 *
 * 精选页数据结构与详情页不同（列表元素是 `WeakReference` 包装、
 * 实体类是 `s0$b`/`i0$c` 这类混淆名），照搬详情页那套会再次踩坑。
 *
 * ## 采用的方案：从**播放链路**反查条目身份
 *
 * 关键洞察：**「当前正在播的视频」就是「当前条」**，不必去问列表。
 * 而播放链路已经打通（`PlayModule.onVideoProgressChanged`），
 * 且 `getCurrentPlayUrl()` 能拿到当前播放 URL —— URL 尾部含 photoId。
 *
 * 真机对账已验证（非推测）：
 * ```
 * PlayModule: dur=500732  tail=5229805274381284935_43c74e22940fd6c6_9429_hlsfk5
 * 数据层:     mDuration=500683  mIsLongVideo=true
 * ```
 * 时长吻合（差 49ms）、photoId 可提取 → **一一对应成立**。
 *
 * ## 工作方式（register → lookup 两段式）
 *
 * ```
 * ① register：判定链路每见到一条 QPhoto，就登记 photoId → WeakReference<QPhoto>
 * ② lookup  ：PlayModule 进度回调拿到 photoId → 查回 QPhoto → 发布为「当前条」
 * ```
 *
 * 这样精选页**不需要任何专属结构知识**：只要它的视频走 PlayModule
 * （实测走 —— `probe_noloop.txt` 记录 200 次进度回调，前台是 HomeActivity），
 * 就能被正确跟踪。
 *
 * ## 只读保证
 *
 * 本类**不修改任何业务状态**，只维护自己的映射表并把结果写入
 * `CfhState.visiblePhotoRef`（该字段本就是「当前可见条」的缓存，
 * 由既有消费方读取）。不 hook 任何新方法、不改列表、不触发刷新。
 *
 * ## 不适用条件
 *
 * - 直播/图片浏览（无视频播放）→ 无进度回调，不会更新；
 *   此时保持旧值比乱猜更好（旧值至少是「上一次确知」）。
 * - 若某个版本 `getCurrentPlayUrl()` 返回不带 photoId 的 URL
 *   （如纯 CDN 路径），`extractPhotoId` 返回 null → 静默不更新，
 *   不影响其它功能。
 */
object FeaturedTrack {

    /** photoId → QPhoto（弱引用：不阻止回收，避免长时间刷页后内存堆积） */
    private val byPhotoId =
        java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<Any>>()

    /**
     * 映射表容量上限。
     *
     * 精选页连刷会不断产生新 photoId。超出后清掉「已被回收」的条目；
     * 若仍超限（极端情况）则整体清空重建 —— 重建的代价只是
     * 「接下来几条暂时认不出身份」，而**不清理的代价是内存持续增长**。
     */
    private const val MAX_ENTRIES = 512

    /** 已通过播放链路成功切换「当前条」的次数 */
    private val switched = java.util.concurrent.atomic.AtomicInteger(0)

    /** 登记失败的次数（photoId 读不到等），用于判断「这条路是否真的在跑」 */
    private val regFailed = java.util.concurrent.atomic.AtomicInteger(0)

    /** 登记成功的次数 */
    private val regOk = java.util.concurrent.atomic.AtomicInteger(0)

    /** 「播放中的 photoId 不在表内」的次数 —— 打通链路前，这是主要失败形态 */
    private val missSeen = java.util.concurrent.atomic.AtomicInteger(0)

    /** 上次发布的 photoId（避免同一集重复发布/重复打日志） */
    @Volatile private var lastPublishedId: String? = null

    /**
     * 「当前条」的落点。
     *
     * ★ 为什么做成可注入的回调，而不是直接写 `CfhState.visiblePhotoRef`：
     *   本类的核心不变量（photoId ⇄ 条目 的唯一映射）必须能在**离线验证台**
     *   里被断言。而 `CfhState` 依赖 `android.os.Handler`，离线 JVM 里
     *   加载 `CfhState` 会 `NoClassDefFoundError` —— 那样这条不变量就
     *   只能靠真机肉眼观察，而它的失效症状恰恰是静默的。
     *   所以把「往哪写」抽成回调：生产环境注入 `CfhState`，
     *   验证台注入一个记录器。
     */
    @Volatile
    var sink: (Any) -> Unit = { qp -> CfhState.visiblePhotoRef = java.lang.ref.WeakReference(qp) }

    /**
     * 日志出口（同样可注入，理由同上：离线验证台不需要也不应产生真实日志）。
     * 默认走 [Logger.always] —— 后者内部全程 try/catch，离线调用也安全。
     */
    @Volatile
    var log: (String) -> Unit = { msg -> Logger.always(msg) }

    /**
     * ① 登记：把一条 QPhoto 与它的 photoId 关联起来。
     *
     * 由判定链路在每个见过条目的位置调用（**廉价操作**：
     * `CfhProbe.readPhotoId` 本身有 IdentityHashMap 缓存 + 方法缓存，
     * 不构成热路径负担）。
     *
     * @param qp 任意可能是 QPhoto 的对象；不是 QPhoto 时静默跳过
     */
    fun register(qp: Any?) {
        if (qp == null) return
        try {
            val id = CfhProbe.readPhotoId(qp)
            if (id.isNullOrBlank()) {
                regFailed.incrementAndGet()
                return
            }
            if (byPhotoId.size >= MAX_ENTRIES) trim()
            byPhotoId[id] = java.lang.ref.WeakReference(qp)
            // ★ 可见性：登记链路以前是「静默失败」的 —— 真机实测 FEATTRACK
            //   零输出时，无法区分「没收到条目」和「收到了但读不到 photoId」，
            //   只能靠猜。每 100 条记一次，把「这条路是否在跑」变成可观测事实。
            val n = regOk.incrementAndGet()
            if (n % 100 == 1) {
                try { log("FEATREG 已登记=${n}条 失败=${regFailed.get()} 表=${byPhotoId.size} 样例id=$id") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            regFailed.incrementAndGet()
        }
    }

    /**
     * 映射表当前条目数 —— 供热路径做**零成本前置判定**。
     *
     * ★ 为什么需要它（2026-09-23 真机教训）：
     *   进度回调每秒数次，而 photoId 提取涉及反射。原设计「每 30 次采样一次」
     *   看似省成本，实则**时序脆弱**：条目是滚动到才登记的，
     *   固定间隔采样很容易全部落在「已登记但还没轮到」的窗口之外，
     *   表现为「日志里 photoId 明明取到了、却不切条」。
     *   现在改为：先读这个数（一次 volatile/原子读，成本可忽略），
     *   表空就直接跳过、表非空就每次回调都判 —— 既省成本又不漏。
     */
    fun tableSize(): Int = byPhotoId.size

    /**
     * ② 查回：按 photoId 找 QPhoto。
     *
     * @return 找到且**尚未被回收**的 QPhoto；否则 null
     */
    fun lookup(photoId: String?): Any? {
        if (photoId.isNullOrBlank()) return null
        val ref = byPhotoId[photoId] ?: return null
        val qp = ref.get()
        if (qp == null) {
            // 已被回收 → 顺手清掉，避免表里堆积死条目
            byPhotoId.remove(photoId, ref)
            return null
        }
        return qp
    }

    /**
     * ③ 发布（**直接给对象版**）—— 供 [CurrentPhotoHook] 使用。
     *
     * ★ 与 [publishIfChanged] 的区别（这是重做后的主路径）：
     *   旧版先 `register(photoId→QPhoto)` 再 `lookup(photoId)` 反查，
     *   中间隔着「弱引用可能已被 GC 回收」这一致命环节 ——
     *   真机实测登记 101 条、表里只剩 4 条，刷几条才切中 1 次。
     *   新版由 `SlidePlayViewModel.getCurrentPhoto()` **直接给出对象**，
     *   不需要登记、不需要反查、不持有任何引用。
     *
     * @param qp   当前条对象（由快手官方接口给出）
     * @param photoId 该对象的 photoId（用于判重）
     * @return true = 确实发生了切换
     */
    fun publishObject(qp: Any, photoId: String): Boolean {
        if (photoId == lastPublishedId) return false
        lastPublishedId = photoId
        try { sink(qp) } catch (_: Throwable) {}
        switched.incrementAndGet()
        try { log("FEATTRACK 当前条切换 photoId=$photoId (第${switched.get()}次)") } catch (_: Throwable) {}
        return true
    }

    /**
     * ③ 发布：PlayModule 进度回调拿到 photoId 后调用，把「当前条」切过去。
     *
     * ★ 保留作为**兜底路径**：主路径已是 [publishObject]（快手官方接口直取）。
     *   这条依赖登记表，在进度回调触发时若表里有该 photoId 也能生效。
     *
     * @param photoId 当前播放的 photoId
     * @return true = 本次确实发生了切换（供调用方决定是否打日志）
     */
    fun publishIfChanged(photoId: String?): Boolean {
        if (photoId.isNullOrBlank()) return false
        if (photoId == lastPublishedId) return false
        val qp = lookup(photoId)
        if (qp == null) {
            // ★ 缺口可见化（2026-09-23）：以前这里直接 return false，
            //   真机表现为「FEATTRACK 零输出」—— 无法区分是
            //   ① 播放链路没调到这儿，还是 ② 调到了但表里查不到该 photoId。
            //   实测就是 ②（登记点选得太窄，精选页条目没进表）。
            //   现在把「查不到」也记出来：日志一出现就能立刻定位是哪一环。
            val miss = missSeen.incrementAndGet()
            if (miss % 20 == 1) {
                try { log("FEATMISS 播放中的 photoId=$photoId 不在表内 (第${miss}次/表${byPhotoId.size}条)") } catch (_: Throwable) {}
            }
            return false
        }
        lastPublishedId = photoId
        try { sink(qp) } catch (_: Throwable) {}
        switched.incrementAndGet()
        try { log("FEATTRACK 当前条切换 photoId=$photoId (第${switched.get()}次)") } catch (_: Throwable) {}
        return true
    }

    /** 清理已被回收的条目；若清理后仍超限则整体重建 */
    private fun trim() {
        try {
            val it = byPhotoId.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.get() == null) it.remove()
            }
            if (byPhotoId.size >= MAX_ENTRIES) byPhotoId.clear()
        } catch (_: Throwable) {
            // 并发修改等极端情况：整体重建，保证不持续增长
            try { byPhotoId.clear() } catch (_: Throwable) {}
        }
    }

    /** 统计（供 ADAPT/诊断行读取）。格式与既有诊断行一致：`键=值 键=值` */
    fun stats(): String =
        "当前条切换=${switched.get()}次 登记=${regOk.get()}条 未命中=${missSeen.get()} 表=${byPhotoId.size}"

    /** 离线自检用：重置状态（并把 sink/log 复位为生产实现） */
    fun resetForTest() {
        byPhotoId.clear()
        lastPublishedId = null
        switched.set(0)
        regFailed.set(0)
        regOk.set(0)
        missSeen.set(0)
        sink = { qp -> CfhState.visiblePhotoRef = java.lang.ref.WeakReference(qp) }
        log = { msg -> Logger.always(msg) }
    }

    /** 离线自检用：直接注入映射 */
    fun putForTest(photoId: String, qp: Any) {
        byPhotoId[photoId] = java.lang.ref.WeakReference(qp)
    }
}
