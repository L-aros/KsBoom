package io.github.angbang852.manjiao.util

import java.util.concurrent.ConcurrentHashMap

/**
 * ★★★ 滑动窗口限流器（2026-09-26 用户要求）。
 *
 * ## 解决的问题
 *
 * 此前各拦截点用**整个进程一次性限次**：
 * ```kotlin
 * if (CfhState.ttpBlockLog < 80) {
 *     CfhState.ttpBlockLog++
 *     Logger.evidence("TTPPARSE-BLOCK", ...)
 * }
 * ```
 *
 * **实测后果**（用户报「拦截记录不全」）：
 * ```
 * TTPPARSE-BLOCK = 80   ← 正好卡在限次 80
 * PV2-BLOCK      = 60   ← 正好卡在限次 60
 * ```
 * **⇒ 前 80 次有记录，之后的拦截全部静默** ——
 * 用户看到的「拦截记录」不完整，无法判断后续拦截是否正常。
 *
 * ## 本类的语义：**按时间窗口限流**
 *
 * ```
 * 每个 key 独立计数，超过窗口后自动重置：
 *   · 每分钟最多 N 条
 *   · 之后继续记录（下一分钟重新计数）
 * ```
 *
 * **好处**：
 *   · **持续有记录**（不是前 N 次之后就没有）
 *   · **仍有限流**（不会淹没日志）
 *   · **能反映真实拦截量**
 *
 * ## 用法
 *
 * ```kotlin
 * if (RateLimiter.allow("TTPPARSE-BLOCK", perMinute = 120)) {
 *     Logger.evidence("TTPPARSE-BLOCK", ...)
 * }
 * ```
 *
 * ## 实现
 *
 * 每 key 存 `(窗口开始时刻, 窗口内计数)`；窗口过期则重置。
 * 无锁读取（`ConcurrentHashMap` 的 `compute` 保证原子性）。
 */
object RateLimiter {

    /** key → (窗口起始 ms, 窗口内已用次数) */
    private val buckets = ConcurrentHashMap<String, LongArray>()

    /** 默认窗口：1 分钟 */
    private const val WINDOW_MS = 60_000L

    /**
     * 是否允许记录。
     *
     * @param key       日志类别（建议与日志标签一致，便于排查）
     * @param perMinute 每分钟上限（默认 120 —— 足够反映真实量，又不会淹没日志）
     * @return true = 可以记录；false = 本窗口已满，跳过
     */
    fun allow(key: String, perMinute: Int = 120): Boolean {
        return try {
            val now = System.currentTimeMillis()
            // 用「本窗口内的序号」判定：序号 ≤ perMinute ⇒ 允许
            // （compute 返回的 cur[1] 即本次的序号 —— 新窗口时为 1）
            val seq = buckets.compute(key) { _, cur ->
                if (cur == null || now - cur[0] >= WINDOW_MS) {
                    longArrayOf(now, 1L)          // 新窗口，本次序号 = 1
                } else {
                    cur[1] = cur[1] + 1L          // 累加，本次序号 = 新值
                    cur
                }
            } ?: return true
            seq[1] <= perMinute
        } catch (_: Throwable) { true }
    }

    /**
     * 读取某 key 本窗口的已用次数（诊断用）。
     */
    fun used(key: String): Long = try {
        val b = buckets[key]
        if (b == null) 0L
        else {
            val now = System.currentTimeMillis()
            if (now - b[0] >= WINDOW_MS) 0L else b[1]
        }
    } catch (_: Throwable) { 0L }

    /** 清空所有窗口（测试用） */
    fun reset() = try { buckets.clear() } catch (_: Throwable) {}
}
