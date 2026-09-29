package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger

/**
 * 三条对账 —— **分叉现场取证**（2026-09-24）。
 *
 * ## 为什么需要它（十轮排查后的方法纠错）
 *
 * 排查「文案对不上视频」已连续 10 轮未果，每轮都是「从代码里找一处像原因的地方
 * 改一下 → 让用户试 → 没效果 → 换一处」。**那是碰运气，不是排查。**
 *
 * 本类的做法是把**三个独立来源在同一时刻对齐**：
 *
 * | # | 来源 | 含义 | 取自 |
 * |---|---|---|---|
 * | ① | 播放器实际 URL | 画面真正在播什么 | `PlayModule.getPlayer().getCurrentPlayUrl()` |
 * | ② | 数据层当前条 | 数据认为当前是哪条 | `SlidePlayViewModel.h0()` + `按下标取条目` |
 * | ③ | 屏上文案视图 | 用户眼睛看到的文字 | `CaptionTextView` / `DL vis` 同源 |
 *
 * **分叉那一刻的现场**（三条不一致时打印完整上下文）就是答案 ——
 * 不需要推断「谁导致了谁」，因为**分叉点本身就定义了因果关系**。
 *
 * ## 与既有探针的区别（之前的为什么没查到）
 *
 * - `CaptionProbe` 只比 ② vs ③（漏了播放器）
 * - `CurrentPhotoHook` 只取 ②（没有对照物）
 * - `FEATTRACK` 打切条，但**与播放器/文案不在同一时刻**
 *
 * 三者从未**在同一时刻对齐**过 —— 而分叉是瞬时事件，不对齐就永远抓不到。
 *
 * ## 只读保证
 *
 * 全部为读取操作，不修改任何状态；只在「发现不一致」时打印（正常零噪声）。
 */
object TripleCheck {

    /**
     * 总开关（2026-09-24）。
     *
     * ★★ 为什么默认关：
     *   本探针每 500ms 做多次反射调用（读播放器 URL、读 VM 下标、取条目）。
     *   而「文案对不上视频」排查了 12 轮仍未定位 —— 此时**必须排除
     *   「探针自身在干扰」这个可能**，否则再多观测也不可信。
     *
     *   已知本模块共 85 处 hook、另有多个 200~800ms 的周期轮询，
     *   每一次反射介入都可能影响快手的播放/渲染时序。
     *
     *   故：默认关闭，只在明确需要取证时打开。
     */
    @Volatile var enabled = false

    /** 每多少毫秒对账一次（太密浪费、太疏漏掉瞬间） */
    private const val INTERVAL_MS = 500L

    /** 已对账次数 */
    private val checks = java.util.concurrent.atomic.AtomicInteger(0)

    /** 发现不一致的次数 */
    private val mismatches = java.util.concurrent.atomic.AtomicInteger(0)

    /** 上次对账时间 */
    @Volatile private var lastAt = 0L

    /** 上次记录的不一致组合（避免同一种分叉重复打爆日志） */
    @Volatile private var lastMismatchKey: String? = null

    @Volatile private var timer: java.util.Timer? = null

    /** 连续一致次数（用于「分叉前是否一直正常」的判断） */
    private val okStreak = java.util.concurrent.atomic.AtomicInteger(0)

    fun stats(): String =
        "对账=${checks.get()}次 不一致=${mismatches.get()}次"

    /**
     * 执行一次三方对账。由定时器调用，也可由翻页事件触发。
     *
     * @return true = 本次发现不一致（已落盘）
     */
    fun check(): Boolean {
        if (!enabled) return false
        val now = System.currentTimeMillis()
        if (now - lastAt < INTERVAL_MS) return false
        lastAt = now
        checks.incrementAndGet()
        return try {
            // ① 播放器实际在播的内容
            val playUrl = try { CurrentPhotoHook.currentPlayUrlPublic() } catch (_: Throwable) { null }
            val playId = playUrl?.let { NoLoopGuard.extractPhotoId(it) }
            // ② 数据层当前条
            val dataId = try { CurrentPhotoHook.currentPhotoIdPublic() } catch (_: Throwable) { null }
            // ③ 屏上文案（用数据层文案作为代理 —— 屏上视图读取不可靠时仍可比对）
            val dataCap = try { CurrentPhotoHook.currentCaptionPublic() } catch (_: Throwable) { null }

            // 三条都拿不到 → 不在可对账场景（如非精选页），静默
            if (playId == null && dataId == null) return false

            val key = "play=$playId|data=$dataId"
            val same = playId != null && dataId != null && playId == dataId
            if (same) {
                okStreak.incrementAndGet()
                // 从不一致恢复 → 记一句（说明分叉是瞬时的）
                if (lastMismatchKey != null) {
                    lastMismatchKey = null
                    Logger.evidence("TRIPLE", "已恢复一致（连续一致${okStreak.get()}次）")
                }
                return false
            }
            // 不一致：只在「组合变化」时才记录（避免同一分叉刷屏）
            if (key == lastMismatchKey) return false
            lastMismatchKey = key
            mismatches.incrementAndGet()
            val streak = okStreak.getAndSet(0)
            Logger.evidence(
                "TRIPLE",
                "★分叉 播放器id=$playId 数据层id=$dataId " +
                    "一致=${if (playId == null || dataId == null) "无法比" else "否"} " +
                    "分叉前连续一致=${streak}次 数据层文案=\"${dataCap?.take(20) ?: "-"}\" " +
                    "播放器url尾=${playUrl?.substringAfterLast('/')?.take(28) ?: "-"}"
            )
            true
        } catch (_: Throwable) { false }
    }

    /** 启动周期对账（仅在 [enabled] 为真时实际工作） */
    fun start() {
        if (timer != null) return
        if (!enabled) {
            Logger.once("triple.off", "TRIPLE 三条对账未启用（默认关，避免探针自身干扰）")
            return
        }
        try {
            val t = java.util.Timer("triple-check", true)
            t.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try { check() } catch (_: Throwable) {}
                }
            }, 1500L, INTERVAL_MS)
            timer = t
            Logger.once("triple.start", "TRIPLE 三条对账已启动（500ms 一次，只在分叉时落盘）")
        } catch (_: Throwable) {}
    }
}
