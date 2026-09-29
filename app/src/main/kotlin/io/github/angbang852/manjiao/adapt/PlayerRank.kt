package io.github.angbang852.manjiao.adapt

/**
 * 播放器候选的**语义排序**（2026-09-23 真机缺陷修复）。
 *
 * ## 为什么需要它
 *
 * `PlaybackHook.discoverPlayers` 用 DexKit 按「有 pause + start 方法」找播放器类，
 * 实测得到 **58 个候选**。原实现直接 `found.take(8)` 取前 8 个 ——
 * 而 DexKit 的返回顺序不保证语义相关性，前 8 个里一个真播放器都没有，
 * 结果钩子装到了 `com.kuaishou.commercial.tachikoma.view.MKVideoView`
 * （**广告**的 VideoView）上。用户看的视频不经过它：
 *
 * > 「停止循环播放没生效」
 *
 * ## 判据：语义包名优先，不看混淆名
 *
 * 快手会重构播放器类（README 记录过 `KwaiMediaPlayerImpl` 系列全 MISS），
 * 但**命名习惯稳定**：真播放器永远落在含 `player` 的语义包里、类名含 `Player`。
 * 因此排序只看「像不像播放器」，不写死任何具体类名 ——
 * 下次重构换了包中某一段，仍然能选对。
 *
 * 实测候选里的真实播放器（14.8.20.50218）：
 * ```
 * com.kwai.video.player.kwai_player.KwaiMediaPlayer   ← 主力（重构后新增 kwai_player 层）
 * com.kwai.video.player.KsMediaPlayerImpl
 * com.kwai.video.wayne.player.main.WaynePlayer          ← 直播
 * com.kwai.video.aemonplayer.AemonMediaPlayer
 * ```
 *
 * ## 独立成类的原因
 *
 * 与 [PlaybackState] 同理：`PlaybackHook` 持有 `android.os.Handler`，
 * 无法在 JVM 上加载，导致这段排序逻辑没法被 [AdaptVerify] 断言。
 * 它是**纯字符串逻辑**，挪到这里后可以直接测。
 */
object PlayerRank {

    /** 明确的噪声包/类特征：这些**看起来**像播放器但不是我们要的 */
    private val NOISE = listOf(
        "commercial", "tachikoma", "tach.",  // 商业广告
        "webkit",                             // WebView 沙盒
        "downloader", "DownloadTask",         // 下载器（注意用 downloader 而非 download，
                                              //   否则会误伤名字里带 Download 的正常类）
        "krn.", "kds.",                       // 前端桥接
        "krtc", "AryaAudio",                  // 连麦音频
        "camera", "followshoot",              // 拍摄（用 followshoot 而非 record，
                                              //   否则误伤含 record 的正常类）
        "CloudMusic", "clipkit",              // 音乐/剪辑
        "hpplay", "ILelink",                  // 投屏 SDK
        "Delegate", "Adapter", "Proxy",       // 包装类（跑的是被包的那个）
        "_1_Abstract", "_2_Abstract",         // 混淆的包装类
    )

    /**
     * **类名**（不含包路径）级的噪声：接口与抽象类。
     *
     * ★★ 必须与包路径噪声分开匹配（2026-09-23 验证台抓出的第二个漏洞）：
     *   首版把 `"IMediaPlayer"` 放进整串匹配的噪声表，而
     *   `com.kwai.video.player.kwai_player.**K**waiMediaPlayer` 里
     *   `KwaiMediaPlayer` 恰好**包含**子串 `IMediaPlayer`（`...wa**iMediaPlayer`）——
     *   真正的播放器被一票否决打成了 -1000，排在广告 View 的后面。
     *   这正是「验证台能抓住实现看不见的错误」的价值：
     *   如果只靠真机观察，这个 bug 的表现和「完全没修」一模一样。
     *
     *   现改为只对**简单类名**做精确后缀判断，不做全局子串扫描。
     */
    private val NOISE_SIMPLE_SUFFIX = listOf(
        "IMediaPlayer",       // 接口
        "IMediaPlayerApi",    // 接口
    )

    /**
     * 给候选类名打分（**越高越像真播放器**）。
     *
     * ★★ 关键语义：**噪声是一票否决，不是扣分**。
     *
     *   首版实现用「命中噪声 -200」的扣分制，实测被验证台断言抓出一个漏洞：
     *   `com.kwai.video.player.IMediaPlayer`（**接口**，不是实现）
     *   同时命中了噪声和加分项（类名含 `MediaPlayer` +60、包名 player +40），
     *   净分仍高于一些真播放器，于是挤进了前 8。
     *   钩子装在接口上不会被执行 ⇒ 和装在广告 View 上是同一类失败。
     *
     *   现改为：命中噪声**直接返回 [NOISE_SCORE]**（远低于任何真实候选），
     *   且不再叠加任何加分。判据只有一条 —— 是噪声就不是播放器。
     */
    fun score(className: String): Int {
        val simple = className.substringAfterLast('.')

        // 噪声一票否决：包路径子串 + 类名精确后缀，两者都直接返回，不叠加加分
        for (n in NOISE) {
            if (className.contains(n, ignoreCase = true)) return NOISE_SCORE
        }
        for (suf in NOISE_SIMPLE_SUFFIX) {
            if (simple.equals(suf, ignoreCase = true)) return NOISE_SCORE
        }

        var s = 0
        // 包路径里的 player 语义（逐级加分）
        val pkgPart = className.substringBeforeLast('.', "")
        s += pkgPart.split('.').count { it.equals("player", true) } * 40

        if (simple.contains("MediaPlayer")) s += 60
        if (simple.endsWith("Player")) s += 50
        return s
    }

    /** 噪声候选的分数：低于任何真实候选可能得到的分数 */
    const val NOISE_SCORE = -1000

    /**
     * 按「像真播放器」的程度降序排序。
     *
     * ★ 稳定排序：分值相同保持 DexKit 原顺序，避免每次冷启动选中不同类
     *   （选中不稳定会让「同一个视频有时生效有时不生效」这种问题极难排查）。
     */
    fun rank(classes: List<String>): List<String> =
        classes.sortedByDescending { score(it) }
}
