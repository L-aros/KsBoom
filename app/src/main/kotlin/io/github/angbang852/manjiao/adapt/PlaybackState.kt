package io.github.angbang852.manjiao.adapt

/**
 * 「播完即停」的**按实例状态机**（2026-09-23 真机缺陷修复）。
 *
 * ## 为什么把它独立出来
 *
 * 原先这段状态活在 `PlaybackHook` 里，用的是两个全局变量：
 *
 * ```kotlin
 * @Volatile private var lastCompletionMs = 0L    // 最近一次播完的时刻
 * @Volatile private var currentPlayer: Any?      // 最近一次 start 的播放器
 * ```
 *
 * 实测（快手 14.8.20.50218）有**两条独立路径**都会触发 start 拦截：
 *  - DexKit 结构发现路径（命中 `aym.b` 等混淆类）
 *  - 硬编码兜底路径（`com.kwai.video.aemonplayer.AemonMediaPlayer`）
 *
 * 两个**不同类的实例**交替写同一组全局字段，于是：
 *
 * | 现象（用户实测） | 机制 |
 * |---|---|
 * | 卡在暂停按钮、点击不响应 | `start` 被判据 `now - lastCompletionMs < 1000` 拦掉，而该时刻来自**另一个视频** |
 * | 画面被「别的视频」挤压变形 | 暂停打在旧实例上，页面重建时取到相邻条目填进来 |
 * | 滑下去正常、滑回来视频变了 | 同上，播放器状态未按实例复位 |
 *
 * ## 修法：一切按实例存
 *
 * 换视频 = 换实例 = 状态天然隔离。核心不变量是
 * **「A 的完成时刻不得影响 B」** —— 见 [justCompleted]。
 *
 * 用 `WeakHashMap` 而非强引用：播放器实例由快手持有，模块只做旁挂记录；
 * 强引用会把已销毁的播放器钉在内存（详情页反复滑动会线性增长）。
 * 同步包装因为 hook 回调跑在任意线程。
 *
 * ## 为什么从 PlaybackHook 挪到 adapt 包
 *
 * `PlaybackHook` 持有 `android.os.Handler` 等 Android 字段，**无法在 JVM 上加载**，
 * 导致 [AdaptVerify] 的断言跑不起来（`NoClassDefFoundError: android/os/Handler`）。
 * 这段状态机是**纯逻辑**，挪到这里后可以直接被验证台断言 ——
 * 「按实例隔离」这条不变量才有回归保护，而不是只靠真机复现。
 */
object PlaybackState {

    /** 实例 → 最近一次「播放完成」的时刻 */
    private val completionAt =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Long>())

    /** 记录某实例的完成时刻 */
    fun markCompletion(p: Any) {
        try { completionAt[p] = System.currentTimeMillis() } catch (_: Throwable) {}
    }

    /**
     * 该实例是否处于「刚播完的 1 秒窗口内」。
     *
     * ★ 判据只看**这个实例自己**的完成时刻，这是本修复的核心：
     *   换视频后新实例没有记录 → 立刻返回 false → 用户的播放点击不会被误拦。
     */
    fun justCompleted(p: Any): Boolean {
        val t = try { completionAt[p] } catch (_: Throwable) { null } ?: return false
        return System.currentTimeMillis() - t < 1000
    }

    /** 清空状态（测试用，避免用例间互相影响） */
    internal fun clearForTest() {
        completionAt.clear()
    }

    /** 当前记录的实例数（排障/验证用） */
    fun trackedCount(): Int = try { completionAt.size } catch (_: Throwable) { 0 }
}
