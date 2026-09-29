package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * 「禁止循环播放」—— **PlayModule 版实现**（2026-09-23 重做）。
 *
 * ## 为什么推倒重做
 *
 * 旧实现（`PlaybackHook.startCheckThread` + `notifyOnCompletion`）在快手
 * 14.8.20.50218 上**整条链路断裂**：装钩成功但 Java 播放器方法从不被调用
 * （实测 `pb:` 运行日志零输出）。原因：快手把播放下沉到
 * `libAemonPlayer.so` + `KwaiPlayerKitView`，Java 侧的
 * `AemonMediaPlayer.start()/notifyOnCompletion()` 不再是活跃路径。
 *
 * ## 新实现的事实基础（全部来自真机探测，非推测）
 *
 * | 事实 | 证据 |
 * |---|---|
 * | `onVideoProgressChanged(a0, a1)`，a0=当前位置ms、a1=总时长ms | 实测序列递增到 24433 后**回绕**到 71 |
 * | 回绕点 = 自动循环重播的瞬间 | 同上，这是「播完」最可靠的信号 |
 * | `PlayerState` 无「播完」取值（只有 Preparing/Prepared/Playing/Paused/Released） | 枚举实测，故**不能**用状态判定 |
 * | `PlayModule.pause()` **真的能停住播放** | 实调实测：调用后进度采样 `210ms → 210ms`（差 0ms） |
 *
 * ## 与旧实现的关键差别：不拦 start
 *
 * 旧实现有**两条**机制：① `start` 拦截（判据是全局 `lastCompletionMs`）
 * ② 看门狗主动 pause。两条互相打架，且判据来自别的视频 ——
 * 用户实测表现为**「要点两下才能播放」**（第 1 下被拦、第 2 下才走）。
 *
 * 新实现**只保留「播完主动停」一条**，且判据完全来自进度回绕。
 * 播放态由快手自己维护 —— 我们只负责在循环发生的瞬间把它停住，
 * 不去干预任何 start。
 *
 * ## 已知行为（需在真机上确认，勿视为已完成）
 *
 * 实测「主动 pause 后点一下」快手 UI 需要第一下确认暂停态、第二下才播。
 * 这是快手自身的 UI 状态机行为（它不知道暂停是外部发起的）。
 * 若体验不可接受，替代方向见 `版本自适应适配方案.md` 的记录。
 */
object NoLoopGuard {

    /** 距结尾多近算「即将播完」。实测回绕发生在距结尾约 74ms，留余量取 250ms */
    private const val TAIL_MS = 250L

    /** 同一个实例的停播动作只做一次（避免反复 pause 造成状态抖动） */
    private val pausedInstances =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Long>())

    /** 是否已经装过（幂等） */
    @Volatile private var installed = false

    /** 进度回调计数（2026-09-23 探针：验证精选页是否也走 PlayModule） */
    private val progressSeen = java.util.concurrent.atomic.AtomicInteger(0)

    /** 命中「播完即停」的次数 */
    private val hitSeen = java.util.concurrent.atomic.AtomicInteger(0)

    private const val PLAY_MODULE = "com.kwai.library.kwaiplayerkit.domain.play.PlayModule"

    // ==================== 两个探针文件的体积上限（2026-10 收尾）====================
    //
    // ★ 背景（审计实测 2026-09-29）：`probe_noloop.txt` / `probe_playurl.txt` 原先
    //   是**纯 append、无上限、无轮转**，落在 `/sdcard/Download/ManJiao/.sys/`
    //   （world-writable 公共目录）⇒ 随冷启/播放次数**单调增长**。
    //   现在统一走 [Logger.appendCapped]（复用 `Logger` 既有的「保留一代 .1」滚动约定，
    //   不另造一套），轮转后**当前文件仍是最新记录**，读最近证据不受影响。
    //
    // 取值依据（实测数据 + 留存量推算，不拍脑袋）：
    //   · probe_playurl.txt：写入门槛是**进程级 3 次**（见 `urlProbeSeen.getAndIncrement() < 3`），
    //     实测 566 行 / 49.9KB（≈88 字节/行）。**单进程最多 3 行**；
    //     但本项目有 5 个进程（主 / :messagesdk / :push_v3 / :kwv_sandboxed_p0 / :mini0，
    //     见 `Logger.secretBaseName` 注释）各自持独立计数器 ⇒ 上限约 15 行/冷启。
    //     256KB ÷ 88B ≈ 2900 行 ≈ **190 次冷启**的存量。
    //   · probe_noloop.txt：每 200 次进度回调写一行（见下方 `n % 200`），
    //     实测 733 行 / 59.2KB（≈81 字节/行）。**热路径**，正常播放每天数十行；
    //     256KB ÷ 81B ≈ 3230 行 —— 按常态速率是**数月**的存量，
    //     连「开屏就自动播放」那种极端挂机场景也能兜住（≈3230×200 = 64 万次回调）。
    //   · 为什么两个都取 256KB（而不是 8MiB）：这两个探针的用途是「验证这条路在跑」，
    //     诊断价值集中在**最近几次冷启**，不需要 8MiB 那种量级的历史；
    //     256KB × 2 代 × 2 文件 = 1MiB 稳态上限，与既有滚动桶（evidence 侧）相比可忽略。
    //   · 轮转保留一代（`.1`）⇒ 单文件稳态峰值 = 2 × 256KB = 512KB。

    /** `probe_playurl.txt` 上限 256KB（依据见上：3 行/进程 × 5 进程/冷启，≈190 次冷启存量） */
    private const val PROBE_PLAYURL_MAX_BYTES = 256L * 1024

    /** `probe_noloop.txt` 上限 256KB（依据见上：1 行/200 次回调，≈3230 行 ≈ 数月存量） */
    private const val PROBE_NOLOOP_MAX_BYTES = 256L * 1024

    /**
     * 当前统计（供排障读取）。
     *
     * ★ 用途（2026-09-23）：验证**精选页**是否也走 `PlayModule`。
     *   已证详情页走它（实测 `onVideoProgressChanged` 正常回调）；
     *   而精选页与详情页**共用播放器组件**（`KwaiPlayerKitView` 的 id
     *   `slide_playerkit_view` 两页实测相同）→ 预期精选页也走。
     *   若成立，精选页的「当前条」跟踪可复用这条通路，
     *   不必去啃精选页那套 presenter 结构。
     */
    fun stats(): String = try {
        "PlayModule 进度回调=${progressSeen.get()}次 播完即停=${hitSeen.get()}次"
    } catch (_: Throwable) { "?" }

    // ==================== 「当前条」反查探针（2026-09-23）====================
    //
    // 目标：把 PlayModule 的进度回调（只有 pos/dur，**不含条目身份**）
    //       与「当前播放的是哪条 QPhoto」关联起来。
    //
    // 思路：`PlayModule.getPlayer()` 返回 `IWaynePlayer`（实测 80 个方法），
    //       其中 `getCurrentPlayUrl()` 能拿到当前播放的 URL。
    //       而数据层每条 QPhoto 也带视频 URL —— **URL 即身份**，可据此反查。
    //
    // 本探针负责验证可行性：
    //   1. 从 PlayModule 实例能否稳定取到 getPlayer() / getCurrentPlayUrl()
    //   2. 拿到的 URL 是否与数据层能对上（打印出来人工/程序比对）
    //
    // 只读，不修改任何状态。

    /** 已 dump 次数（限流） */
    private val urlProbeSeen = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 尝试从 PlayModule 实例取当前播放 URL。
     *
     * @param playModule `onVideoProgressChanged` 回调的 thisObject
     * @return 当前播放 URL，取不到返回 null
     */
    private fun currentPlayUrl(playModule: Any): String? {
        return try {
            val getPlayer = playModule.javaClass.declaredMethods.firstOrNull {
                it.name == "getPlayer" && it.parameterTypes.isEmpty()
            } ?: return null
            getPlayer.isAccessible = true
            val player = getPlayer.invoke(playModule) ?: return null
            val getUrl = player.javaClass.methods.firstOrNull {
                it.name == "getCurrentPlayUrl" && it.parameterTypes.isEmpty()
            } ?: return null
            getUrl.isAccessible = true
            (getUrl.invoke(player) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
    }

    /** 把「当前播放 URL」落盘，供与数据层比对 */
    private fun recordPlayUrl(url: String, pos: Long, dur: Long) {
        try {
            val f = java.io.File("/sdcard/Download/ManJiao/.sys/probe_playurl.txt")
            f.parentFile?.mkdirs()
            // 只记 URL 的尾部片段（够比对、又短）：完整 URL 很长且带签名
            val tail = url.substringAfterLast('/').take(48)
            // ★ 上限 + 轮转（2026-10 收尾）：原先这里是**纯 append、无上限**。
            //   走 Logger 既有设施（保留一代 `.1`），上限取值依据见
            //   [PROBE_PLAYURL_MAX_BYTES] 的注释。
            Logger.appendCapped(
                f,
                "${System.currentTimeMillis()} pos=$pos dur=$dur tail=$tail\n",
                PROBE_PLAYURL_MAX_BYTES
            )
        } catch (_: Throwable) {}
    }

    // ==================== 「当前条」身份提取（2026-09-23 已验证）====================
    //
    // ★ 已证（真机对账）：
    //   `getCurrentPlayUrl()` 的 URL 尾部形如
    //     `5229805274381284935_43c74e22940fd6c6_9429_hlsfk5`
    //   下划线前的长数字 **就是 photoId**；同一时刻 `onVideoProgressChanged`
    //   报告的 `dur=500732` 与数据层该条 `mDuration=500683` **吻合**（差 49ms）→
    //   `dur` 是**当前条时长**而非列表聚合时长，URL↔条目 一一对应。
    //
    // ★ 意义：PlayModule 的进度回调只带 pos/dur、不带条目身份；
    //   有了这一步，「当前播的是哪条」就能从播放链路直接反查出来，
    //   不必去啃精选页那套 presenter 结构（s0$b / i0$c）。
    //   精选页与详情页共用 `KwaiPlayerKitView`（id 均为 slide_playerkit_view），
    //   故两页都可走这条通路。

    /** photoId 提取：URL 尾部首个「长数字段」 */
    private val PHOTO_ID_REGEX = Regex("""(\d{15,25})""")

    /**
     * 从当前播放 URL 提取 photoId。
     *
     * @return photoId 字符串，提取不到返回 null
     */
    fun currentPhotoId(playModule: Any): String? {
        val url = currentPlayUrl(playModule) ?: return null
        return extractPhotoId(url)
    }

    /**
     * 取当前播放 URL 的**公开入口**（供 [TripleCheck] 三条对账使用）。
     *
     * 与内部 `currentPlayUrl` 同实现 —— 单独暴露是因为对账模块需要在
     * **任意时刻**（不只进度回调里）读取播放器状态，而分叉是瞬时事件。
     */
    fun currentPlayUrlOf(playModule: Any): String? = currentPlayUrl(playModule)

    /**
     * 从 URL 里剥离 photoId（纯函数，供离线自检）。
     *
     * 实测样本：`.../5229805274381284935_43c74e22940fd6c6_9429_hlsfk5`
     *          → photoId = `5229805274381284935`
     *
     * 取「最后一段路径里的第一个 15~25 位数字」，而不是全局首个 ——
     * URL 中 `http://v23-vod-3.kwa...` 之类前缀也可能带数字，全局取会取错。
     */
    fun extractPhotoId(url: String): String? {
        val last = url.substringAfterLast('/')
        return PHOTO_ID_REGEX.find(last)?.groupValues?.get(1)
    }

    /**
     * 安装。由 `PlaybackHook.hook()` 调用。
     *
     * 全程容错：类找不到 / 方法不存在 / 装钩异常，都只记日志并静默降级
     * （该功能不可用，但不影响其它 hook）。
     */
    fun install(xp: XposedInterface, cl: ClassLoader) {
        if (installed) return
        try {
            val c = Reflect.findClass(PLAY_MODULE, cl) ?: run {
                Logger.once("noloop.miss", "NOLOOP: $PLAY_MODULE 未找到 → 本版本该功能不可用（不影响其它功能）")
                installed = true
                return
            }
            val progress = c.declaredMethods.firstOrNull {
                it.name == "onVideoProgressChanged" && it.parameterTypes.size == 2
            }
            val pause = c.declaredMethods.firstOrNull {
                it.name == "pause" && it.parameterTypes.isEmpty()
            }
            if (progress == null || pause == null) {
                Logger.once(
                    "noloop.nomethod",
                    "NOLOOP: 方法缺失 progress=$progress pause=$pause → 该功能不可用"
                )
                installed = true
                return
            }

            // ★ 每次进度回调都检查；命中尾部且开关开启时主动停。
            //
            //   为什么在**回调里同步**调 pause（而不是丢线程）：
            //   实测同步调用安全且立即生效（进度采样差 0ms）。
            //   丢线程会与快手的下一帧推进产生竞态，反而可能出现
            //   「停了又走一帧」的抖动。
            xp.hook(progress).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("noloop.progress").intercept { chain ->
                    chain.proceed()
                    try {
                        // ★ 计数（2026-09-23）：无条件累加，用于验证「精选页是否也走
                        //   PlayModule」。每 200 次输出一行汇总（不刷屏），
                        //   并带上当时的前台 Activity 类名 —— 据此区分「详情页回调」
                        //   与「精选页回调」（两者 Activity 相同，但可通过其它特征分辨）。
                        val n = progressSeen.incrementAndGet()
                        // ★ 登记 PlayModule 实例（供三条对账 ①「播放器实际在播」使用）
                        try { CurrentPhotoHook.notePlayModule(chain.thisObject) } catch (_: Throwable) {}
                        // ★ 「当前条」反查探针（2026-09-23）：验证能否用
                        //   PlayModule→getPlayer()→getCurrentPlayUrl() 拿到
                        //   当前播放的 URL（用于反查条目身份）。
                        //
                        //   ★ 已从「探针」升级为**生产路径**：真机对账已证实
                        //     URL 尾部的 photoId 与数据层一一对应（dur=500732
                        //     ↔ mDuration=500683），故现在的提取结果会直接驱动
                        //     精选页「当前条」跟踪（FeaturedTrack）。
                        //
                        //   代价控制：每 30 次回调查一次（约 1 秒一次），
                        //   而非每次 —— URL 提取涉及反射，不宜进热路径。
                        //
                        //   ★★ 真机实测修正（2026-09-23）：精选页上 `FEATTRACK`
                        //      / `FEATMISS` 一行都不打，但 playurl 探针里
                        //      photoId 明明取到了、且与登记表里的 id 一致。
                        //      原因是**时序**：进度回调从开机就在跑，而条目是
                        //      「滚动到才登记」；探针每 30 次才看一次，
                        //      很容易全部落在「已登记但还没轮到下一次采样」的窗口外。
                        //
                        //      修法：不再只看固定间隔 —— 每次回调都做一次
                        //      **极廉价的前置判定**（读一个 AtomicInteger），
                        //      只有「表非空」时才去提取 photoId。
                        //      表空时零成本跳过，表非空时高频采样 ⇒ 不再错过切换。
                        // ★★ 已移除「播放回调 → 发布当前条」（2026-09-24）。
                        //
                        //   曾在此处用 `getCurrentPlayUrl()` 提取 photoId，
                        //   再经 FeaturedTrack 发布「当前条」。但那只是**早期兜底**：
                        //   当时下标路径还没打通，只能靠播放链路反查。
                        //
                        //   下标路径打通后，实测出现**双源打架**：
                        //     03:57:21 FEATTRACK ... 5243597550254417531   ← 播放回调（缓冲/预加载）
                        //     03:57:21 FEATTRACK ... 5222486925599688565   ← 下标路径（真实当前条）
                        //     03:57:22 FEATTRACK ... 5243597550254417531   ← 翻回去
                        //   —— 两个 id 每 100~200ms 交替一次，跟踪彻底失效。
                        //
                        //   为什么移除的是播放回调而不是下标路径：
                        //   ① 下标路径来自 `SlidePlayViewModel` 的**权威位置接口**，
                        //      语义上就是「当前第几条」；
                        //   ② 播放回调给的是**播放器正在解码的 URL**，它可能领先
                        //      或滞后于用户看到的画面（预加载/缓冲），
                        //      实测这个残留偏差正是「文案对不上」的来源之一。
                        //
                        //   进度回调仍保留（下面 noloop 的播完即停逻辑照常工作），
                        //   只是不再由它决定「当前条」。
                        // 保留一次落盘证据（验证「这条路是否在跑」）
                        if (n % 200 == 0 && urlProbeSeen.getAndIncrement() < 3) {
                            val url = currentPlayUrl(chain.thisObject)
                            if (url != null) {
                                val p0 = (chain.args.getOrNull(0) as? Number)?.toLong() ?: -1L
                                val d0 = (chain.args.getOrNull(1) as? Number)?.toLong() ?: -1L
                                recordPlayUrl(url, p0, d0)
                                Logger.always("URLPROBE pos=$p0 dur=$d0 photoId=${extractPhotoId(url)}")
                            }
                        }
                        if (n % 200 == 0) {
                            Logger.always("NOLOOPSTAT ${stats()}")
                            // ★ 同时落盘（2026-09-23）：实测主进程日志常被快手自身的
                            //   刷屏日志挤出 logcat 缓冲区，导致「探针跑了但看不到」。
                            //   写文件绕开缓冲区竞争，adb 可直接读取核对。
                            //   路径与 AuditMirror/Module.diagWrite 同域（已验证可写）。
                            try {
                                val f = java.io.File("/sdcard/Download/ManJiao/.sys/probe_noloop.txt")
                                f.parentFile?.mkdirs()
                                // ★ 上限 + 轮转（2026-10 收尾）：原先这里是**纯 append、无上限**。
                                //   走 Logger 既有设施（保留一代 `.1`），上限取值依据见
                                //   [PROBE_NOLOOP_MAX_BYTES] 的注释。
                                Logger.appendCapped(
                                    f,
                                    "${System.currentTimeMillis()} " + stats() +
                                        " act=${try { CfhState.tracked?.javaClass?.simpleName } catch (_: Throwable) { "?" }}\n",
                                    PROBE_NOLOOP_MAX_BYTES
                                )
                            } catch (_: Throwable) {}
                        }
                        if (Prefs.bool(Prefs.K_PB_NO_LOOP, false)) {
                            val pos = (chain.args.getOrNull(0) as? Number)?.toLong() ?: return@intercept null
                            val dur = (chain.args.getOrNull(1) as? Number)?.toLong() ?: return@intercept null
                            if (dur > 1000 && pos >= dur - TAIL_MS) {
                                val self = chain.thisObject
                                val last = try { pausedInstances[self] } catch (_: Throwable) { null }
                                val now = System.currentTimeMillis()
                                // 同一实例 2 秒内只停一次：避免「停了 → 用户点了 → 又立刻停」
                                // 造成连续点击都无效的观感
                                if (last == null || now - last > 2000) {
                                    pausedInstances[self] = now
                                    try {
                                        pause.isAccessible = true
                                        pause.invoke(self)
                                        hitSeen.incrementAndGet()
                                        Logger.always("NOLOOP: 播完即停 pos=$pos dur=$dur (playModule)")
                                    } catch (t: Throwable) {
                                        Logger.once("noloop.perr", "NOLOOP pause 调用失败: ${t.message}")
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                    null
                }
            installed = true
            Logger.once("noloop.installed", "NOLOOP installed: PlayModule.onVideoProgressChanged → pause()（回绕判据 ${TAIL_MS}ms）")
        } catch (t: Throwable) {
            installed = true
            Logger.once("noloop.fail", "NOLOOP 安装失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
