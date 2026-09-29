package io.github.angbang852.manjiao.adapt

import io.github.angbang852.manjiao.hook.CfhUtil
import io.github.angbang852.manjiao.hook.FeaturedTrack
import io.github.angbang852.manjiao.hook.NoLoopGuard

/**
 * 版本自适应逻辑的**离线验证台**（`main` 函数，不参与 APK 运行时）。
 *
 * 为什么放在 adapt 包内而不是独立 test 源集：项目当前没有测试基础设施，
 * 而任务约束要求「不引入不必要的新依赖、改动尽量局部化」。放在同包内
 * 有两个好处：
 * 1. 可以直接 import `KsVersion.Tier` / `KsResolve.Feature` 等**真实类型**，
 *    不会出现「测试副本与实现漂移」；
 * 2. 用项目既有的 Gradle Kotlin 工具链编译（`compileDebugKotlin` 已经过验证），
 *    零新增依赖。
 *
 * 运行方式（开发期）：
 * ```
 * gradlew -q :app:adaptVerify
 * ```
 * （任务定义在 app/build.gradle.kts 的 `adaptVerify`，用 JavaExec 跑本类的 main。
 *  该任务只在开发期使用，不进入任何产物。）
 *
 * ★ 纪律：改动 `KsVersion.tierOf` / `KsResolve.Feature.matches` / `satisfies` 后
 *   必须重跑本验证台，确保「新增版本只改表、不改逻辑」的承诺仍然成立。
 */
object AdaptVerify {

    private var pass = 0
    private var fail = 0

    private fun check(name: String, actual: Any?, expected: Any?) {
        if (actual == expected) {
            pass++; println("  PASS  $name = $actual")
        } else {
            fail++; println("  FAIL  $name: got=$actual expected=$expected")
        }
    }

    // ==================== 测试夹具：模拟快手的真实类形状 ====================

    class FakeKnhB {
        @Suppress("UNUSED_PARAMETER")
        fun E1(items: MutableList<Any?>): MutableList<Any?> = items
        @Suppress("UNUSED_PARAMETER")
        fun T0(a: Any?, b: Any?, items: MutableList<Any?>, c: Any?, d: Any?, reason: String): Boolean = true
    }

    class FakeWrongClass {
        @Suppress("UNUSED_PARAMETER")
        fun E1(x: String): String = x          // 1 参但不含 List → 不该命中 knhb 的 E1 特征
        fun foo(): Int = 0
    }

    class FakeLiveStreamFeed

    class FakeRerankD {
        @Suppress("UNUSED_PARAMETER")
        fun m(feed: FakeLiveStreamFeed) {}
        @Suppress("UNUSED_PARAMETER")
        fun r(a: Map<Any?, Any?>, b: MutableList<Any?>, c: Boolean) {}
        @Suppress("UNUSED_PARAMETER")
        fun g(a: MutableList<Any?>, b: MutableList<Any?>) {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== 1. 版本号 → 档位映射（KsVersion.tierOf）===")
        check("14.7.40 → MID（当前实测版本）", KsVersion.tierOf(14, 7), KsVersion.Tier.MID)
        check("14.8.20 → MODERN（适配文档目标版本）", KsVersion.tierOf(14, 8), KsVersion.Tier.MODERN)
        check("14.8.30 → MODERN（适配文档目标版本）", KsVersion.tierOf(14, 8), KsVersion.Tier.MODERN)
        check("14.5.10 → LEGACY", KsVersion.tierOf(14, 5), KsVersion.Tier.LEGACY)
        check("14.9.0 → LATEST", KsVersion.tierOf(14, 9), KsVersion.Tier.LATEST)
        check("15.0.0 → LATEST", KsVersion.tierOf(15, 0), KsVersion.Tier.LATEST)
        check("13.2.0 → LEGACY", KsVersion.tierOf(13, 2), KsVersion.Tier.LEGACY)
        check("未知(-1,-1) → MID（保守=保持既有行为）", KsVersion.tierOf(-1, -1), KsVersion.Tier.MID)

        println("=== 2. atLeast 语义（UNKNOWN 恒 false：宁可不装也不猜）===")
        check("MID.atLeast(MID)", KsVersion.atLeastOf(KsVersion.Tier.MID, KsVersion.Tier.MID), true)
        check("MODERN.atLeast(MID)", KsVersion.atLeastOf(KsVersion.Tier.MODERN, KsVersion.Tier.MID), true)
        check("LEGACY.atLeast(MID)", KsVersion.atLeastOf(KsVersion.Tier.LEGACY, KsVersion.Tier.MID), false)
        check("UNKNOWN.atLeast(UNKNOWN)=false", KsVersion.atLeastOf(KsVersion.Tier.UNKNOWN, KsVersion.Tier.UNKNOWN), false)

        println("=== 3. knhb 结构匹配（真反射输入）===")
        val knhbMethods = FakeKnhB::class.java.declaredMethods.toList()
        val e1 = knhbMethods.first { it.name == "E1" }
        val t0 = knhbMethods.first { it.name == "T0" }
        val knhbE1 = KsResolve.Feature(name = "E1", paramCount = 1, paramsHasList = true)
        val knhbT0 = KsResolve.Feature(name = "T0", paramCount = 6, paramsHasList = true)
        check("E1(List) 命中 knhb 的 E1 特征", knhbE1.matches(e1), true)
        check("T0(6参含List) 命中 knhb 的 T0 特征", knhbT0.matches(t0), true)

        val wrongE1 = FakeWrongClass::class.java.declaredMethods.first { it.name == "E1" }
        check("E1(String) 不命中（1参但无 List）", knhbE1.matches(wrongE1), false)

        println("=== 4. 目标判据 satisfies（require AND anyOf）===")
        check(
            "FakeKnhB 满足 knhb 目标",
            KsResolve.satisfiesForTest(listOf(knhbE1), listOf(knhbT0), FakeKnhB::class.java), true
        )
        check(
            "FakeWrongClass 不满足（require E1 形状不符）",
            KsResolve.satisfiesForTest(listOf(knhbE1), listOf(knhbT0), FakeWrongClass::class.java), false
        )
        check(
            "无约束目标不接受任意类（防空匹配）",
            KsResolve.satisfiesForTest(emptyList(), emptyList(), FakeKnhB::class.java), false
        )

        println("=== 5. rerank 结构匹配 + 形状兜底（名字变了也能中）===")
        val dMethods = FakeRerankD::class.java.declaredMethods.toList()
        check("d.m(1参) 命中", dMethods.any { KsResolve.Feature(name = "m", paramCount = 1).matches(it) }, true)
        check("d.r(3参) 命中", dMethods.any { KsResolve.Feature(name = "r", paramCount = 3).matches(it) }, true)
        check("d.g(2参) 命中", dMethods.any { KsResolve.Feature(name = "g", paramCount = 2).matches(it) }, true)
        val shapeR = KsResolve.Feature(paramCount = 3, paramsHasList = true)
        check("r 的形状兜底（无名字、3参含List）命中", dMethods.any { shapeR.matches(it) }, true)

        println("=== 6. 播放器候选名顺序（保证旧版本行为不变）===")
        val known = KsResolve.knownPlayerClassesForTest()
        check("候选名数量", known.size, 3)
        check("首选名与项目原顺序一致", known[0], "com.kwai.player.KwaiPlayer")
        check("候选名全部去重", known.toSet().size, known.size)

        println("=== 6b. 装配报告登记（KsHookKit.noteResolve）===")
        KsHookKit.noteResolve("verify.t1", ok = true, detail = "a via=known-name")
        KsHookKit.noteResolve("verify.t2", ok = false, detail = "unresolved in pkg")
        check("成功项已登记", KsHookKit.outcomeOf("verify.t1")?.ok, true)
        check("失败项已登记", KsHookKit.outcomeOf("verify.t2")?.ok, false)
        // ★ 只登记首次：重入解析（缓存命中）不得覆盖最先那次的 via
        KsHookKit.noteResolve("verify.t1", ok = false, detail = "OVERWRITTEN")
        check("重复登记不覆盖首次结果", KsHookKit.outcomeOf("verify.t1")?.detail, "a via=known-name")

        println("=== 7. 完整版本名解析（KsVersion.parseName）===")
        check("14.7.40.49980", KsVersion.parseNameForTest("14.7.40.49980"), Triple(14, 7, 40))
        check("14.8.30.50465", KsVersion.parseNameForTest("14.8.30.50465"), Triple(14, 8, 30))
        check("14.8.20.50218", KsVersion.parseNameForTest("14.8.20.50218"), Triple(14, 8, 20))
        check("带后缀 14.7.40.49980-beta", KsVersion.parseNameForTest("14.7.40.49980-beta"), Triple(14, 7, 40))
        check("两段 14.7", KsVersion.parseNameForTest("14.7"), Triple(14, 7, -1))
        check("空串 → 全 -1（不抛异常）", KsVersion.parseNameForTest(""), Triple(-1, -1, -1))
        check("垃圾输入 → 全 -1（不抛异常）", KsVersion.parseNameForTest("abc"), Triple(-1, -1, -1))

        println("=== 8. 持久缓存签名语义（KsCache 失效策略）===")
        // 签名的三要素：快手版本 | 模块版本 | 格式版本。
        // 「快手更新 / 模块更新才失效」正是用户提出的需求，必须有断言守着。
        check("版本已知 → 可写盘", KsCache.writableForTest(ksVersion = "14.8.20.50218"), true)
        check("版本未知 → 不写盘（避免半成品签名污染）", KsCache.writableForTest(ksVersion = ""), false)
        check("同版本同模块 → 签名一致（缓存可复用）",
            KsCache.sigForTest("14.8.20.50218", 50218), KsCache.sigForTest("14.8.20.50218", 50218))
        check("快手更新 → 签名变化（缓存失效）",
            KsCache.sigForTest("14.8.20.50218", 50218) != KsCache.sigForTest("14.8.30.50465", 50218), true)
        check("模块更新 → 签名变化（缓存失效）",
            KsCache.sigForTest("14.8.20.50218", 50218) != KsCache.sigForTest("14.8.20.50218", 50219), true)
        check("签名含三要素（以 | 分隔）", KsCache.sigForTest("14.8.20.50218", 50218).count { it == '|' }, 2)

        println("=== 9. 播放器状态按实例隔离（PlaybackState 真机缺陷回归）===")
        // ★ 这三条守着一个真机缺陷：原实现用全局 lastCompletionMs / currentPlayer，
        //   详情页滑动时新旧播放器交替 start，导致
        //   ① 暂停打在旧实例上（当前视频卡在暂停按钮）
        //   ② 点击播放被**别的视频**的完成时刻拦掉（点了没反应）
        //   ③ 画面被相邻条目填充而变形
        //   修法：状态按实例存（PlaybackState）。
        PlaybackState.clearForTest()
        val playerA = Any()
        val playerB = Any()
        check("未播完的实例 → 不拦（可正常播放）",
            PlaybackState.justCompleted(playerA), false)
        PlaybackState.markCompletion(playerA)
        check("刚播完的实例 → 拦（禁止循环生效）",
            PlaybackState.justCompleted(playerA), true)
        // ★ 核心不变量：A 的完成时刻**不得**影响 B —— 换视频后点击必须能播放
        check("换视频后（另一实例）→ 不被 A 的完成时刻拦",
            PlaybackState.justCompleted(playerB), false)
        check("已记录 1 个实例", PlaybackState.trackedCount(), 1)
        PlaybackState.clearForTest()
        check("清空后不再拦", PlaybackState.justCompleted(playerA), false)

        println("=== 10. 播放器候选排序（真机「禁止循环没生效」回归）===")
        // ★ 这组断言守着一次真机失败：DexKit 返回 58 个候选、原实现取前 8，
        //   结果装到了广告的 MKVideoView 上，用户看的视频不经过它 ⇒ 功能看起来没生效。
        //   候选名单取自 14.8.20.50218 的实测输出（真名，含重构后新增的 kwai_player 层）。
        val realCandidates = listOf(
            "ack.s",
            "com.hpplay.sdk.source.api.ILelinkPlayer",
            "com.kuaishou.commercial.tach.component.frog.TKFrogCanvas",
            "com.kuaishou.commercial.tachikoma.view.MKVideoView",
            "com.kuaishou.krn.bridges.download.KrnDownloadBridge",
            "com.kuaishou.webkit.extension.media.IKsMediaPlayer",
            "com.kwai.framework.player.core.b",
            "com.kwai.video.aemonplayer.AemonMediaPlayer",
            "com.kwai.video.downloader.downloader.DownloadTask",
            "com.kwai.video.player.AndroidMediaPlayer",
            "com.kwai.video.player.IMediaPlayer",
            "com.kwai.video.player.KsMediaPlayerImpl",
            "com.kwai.video.player.kwai_player.KwaiMediaPlayer",
            "com.kwai.video.wayne.player.main.WaynePlayer",
            "com.kwai.video.krtc.AryaAudioEngineProxy",
            "com.yxcorp.gifshow.camera.record.followshoot.f",
        )
        val ranked = PlayerRank.rank(realCandidates)
        val top8 = ranked.take(8)
        // ★ 先诊断：把评分打出来（断言失败时直接看到排序依据，不必再猜）
        println("  排序结果（分数降序）:")
        for (c in ranked) println("    ${PlayerRank.score(c)}  $c")
        check("真实播放器 KwaiMediaPlayer 进入前 8", top8.any { it.contains("kwai_player.KwaiMediaPlayer") }, true)
        check("直播播放器 WaynePlayer 进入前 8", top8.any { it.endsWith("WaynePlayer") }, true)
        check("AemonMediaPlayer 进入前 8", top8.any { it.endsWith("AemonMediaPlayer") }, true)
        // ★ 核心：广告 VideoView 必须被压到前 8 之外（这正是原实现踩的坑）
        check("广告 MKVideoView **不在**前 8", top8.none { it.contains("MKVideoView") }, true)
        check("下载器不在前 8", top8.none { it.contains("Download") }, true)
        check("WebView 沙盒不在前 8", top8.none { it.contains("webkit") }, true)
        check("接口 IMediaPlayer 不在前 8", top8.none { it.endsWith(".IMediaPlayer") }, true)
        // 排序稳定性：同输入两次结果一致（避免冷启动选中漂移）
        check("排序稳定（两次一致）", PlayerRank.rank(realCandidates), ranked)
        check("首位是具体播放器而非噪声", PlayerRank.score(ranked[0]) > PlayerRank.score("com.kuaishou.commercial.tachikoma.view.MKVideoView"), true)

        // ==================== photoId 提取（真机样本）====================
        //
        // ★ 来源：真机 `probe_playurl.txt` 实测样本。提取规则必须有离线断言
        //   兜底 —— 该正则跑在播放热路径上（每 60 次进度回调一次），
        //   一旦取错就会把「当前条」认成别的视频，且**症状是静默的**
        //   （画面正常、只是文案/判定跟着错条目走）。
        println()
        println("---- photoId 提取（真机样本） ----")
        val realUrl = "http://v23-vod-3.kwa/video/5229805274381284935_43c74e22940fd6c6_9429_hlsfk5"
        check("真机样本提取 photoId", NoLoopGuard.extractPhotoId(realUrl), "5229805274381284935")
        // 前缀带数字时不能取错（取「最后一段路径的首个长数字」，不是全局首个）
        check(
            "URL 前缀含数字时不误取",
            NoLoopGuard.extractPhotoId("http://v23-vod-3.kwa/20240101/xyz/5229805274381284935_a_b_c"),
            "5229805274381284935"
        )
        check("短数字不被当成 photoId", NoLoopGuard.extractPhotoId("http://x/y/123_abc"), null)
        check("无数字返回 null", NoLoopGuard.extractPhotoId("http://x/y/abc"), null)
        check("空串返回 null", NoLoopGuard.extractPhotoId(""), null)

        // ==================== 精选页「当前条」跟踪 ====================
        //
        // ★ 这一组断言守的是「文案不变、画面在变」那个 bug 的核心不变量：
        //   **播放链路报出的 photoId 必须能唯一地映射回一条条目**。
        //   映射错位的后果是静默的（画面正常，只是判定/文案跟着错条目走），
        //   所以必须有离线断言兜底，不能只靠真机观察。
        println()
        println("---- 精选页「当前条」跟踪 ----")

        // 用哨兵对象代替真实 QPhoto（本类只做身份传递，不碰业务字段）
        class P(val id: String)
        val a = P("A")
        val b = P("B")

        // ★ 本组断言在**离线 JVM** 里跑，不能碰 CfhState（依赖 android.os.Handler）。
        //   故注入一个记录器替掉生产 sink —— 这样校验的仍是同一段核心逻辑，
        //   只是把「往哪写」换成了可断言的目标。
        var sinkTarget: Any? = null
        FeaturedTrack.log = { }   // 离线静音

        FeaturedTrack.resetForTest()
        FeaturedTrack.sink = { qp -> sinkTarget = qp }
        FeaturedTrack.log = { }
        check("空表查不到", FeaturedTrack.lookup("A"), null)

        FeaturedTrack.putForTest("A", a)
        check("登记后可查回同一实例", FeaturedTrack.lookup("A") === a, true)
        check("未登记的 id 查不到", FeaturedTrack.lookup("B"), null)

        // ★ 核心不变量：发布确实把「当前条」切到了对应实例
        check("首次发布返回 true", FeaturedTrack.publishIfChanged("A"), true)
        check("发布后 sink 收到该实例", sinkTarget === a, true)
        // ★ 同一集内重复回调**不得**重复发布（进度回调每秒数次，
        //   每次都写会造成可见性抖动，也可能干扰依赖该字段的其它诊断）
        check("同一 id 重复发布返回 false（不抖动）", FeaturedTrack.publishIfChanged("A"), false)

        // ★ 切到另一条必须生效 —— 这正是「画面变、跟踪不变」的修复点
        FeaturedTrack.putForTest("B", b)
        check("切到另一条返回 true", FeaturedTrack.publishIfChanged("B"), true)
        check("切换后 sink 收到新实例", sinkTarget === b, true)

        // 查不到的 id（如映射表被裁剪后）不得把「当前条」清空或误指
        sinkTarget = null
        check("未知 id 发布返回 false", FeaturedTrack.publishIfChanged("C"), false)
        check("未知 id 不写入 sink（不破坏已跟踪的当前条）", sinkTarget, null)
        check("空 id 发布返回 false（不崩）", FeaturedTrack.publishIfChanged(""), false)

        // ★ 「查不到」必须可见（这条守的是我自己的排障能力）。
        //   真机实测 FEATTRACK 零输出时，旧实现无法区分
        //   ① 播放链路没调到、② 调到了但表里没有 —— 只能靠猜，浪费了一整轮。
        //   现要求「未命中」也留下痕迹，且必须与「成功切换」分开计数，
        //   否则两种情况在统计里会混成同一个数。
        // ★ 前置判定：表空 vs 表非空必须可区分
        //   （这条守的是「时序脆弱」那个真机 bug：原设计每 30 次回调才采样一次，
        //    而条目是滚动到才登记的，采样很容易全部落在窗口外 →
        //    日志里 photoId 取到了却不切条。现在改成先读表大小、表非空才提取。）
        FeaturedTrack.resetForTest()
        check("空表时 tableSize=0（据此跳过提取，零成本）", FeaturedTrack.tableSize(), 0)
        FeaturedTrack.putForTest("X", a)
        check("登记后 tableSize>0（据此启用提取）", FeaturedTrack.tableSize() > 0, true)

        val seen = ArrayList<String>()
        FeaturedTrack.resetForTest()
        // ==================== 当前条「直取」路径 ====================
        //
        // ★ 这条守的是重做后的**主路径**：由快手官方接口
        //   SlidePlayViewModel.getCurrentPhoto() 直接给出 QPhoto，
        //   不再经「登记 → 反查」（那条路实测登记 101 条、表里只剩 4 条，
        //   因为弱引用被 GC 回收 → 刷几条只切中 1 次）。
        println()
        println("---- 当前条直取（主路径） ----")
        FeaturedTrack.resetForTest()
        var direct: Any? = null
        FeaturedTrack.sink = { qp -> direct = qp }
        FeaturedTrack.log = { }

        val c1 = P("C1")
        val c2 = P("C2")
        check("首次直取返回 true", FeaturedTrack.publishObject(c1, "C1"), true)
        check("直取把对象交给 sink", direct === c1, true)
        // 同一集内重复调用不得重复发布（进度/查询可能高频触发）
        check("同一 photoId 重复直取返回 false", FeaturedTrack.publishObject(c1, "C1"), false)
        // ★ 切换必须生效 —— 这是「画面变、跟踪不变」的修复点
        check("切到另一条返回 true", FeaturedTrack.publishObject(c2, "C2"), true)
        check("切换后 sink 拿到新对象", direct === c2, true)
        check("直取计入成功切换", FeaturedTrack.stats().contains("当前条切换=2次"), true)
        // ★ 关键差异：直取路径**不需要先登记**
        //   （旧路径必须先 register 才能 lookup，这是它最大的脆弱点）
        check("直取不依赖登记表（表为空也能工作）", FeaturedTrack.tableSize(), 0)

        // 兜底路径仍可用（进度回调 photoId → 反查）
        FeaturedTrack.resetForTest()
        FeaturedTrack.sink = { qp -> direct = qp }
        FeaturedTrack.log = { }
        FeaturedTrack.putForTest("D1", c1)
        check("兜底路径仍可用（登记后能反查）", FeaturedTrack.publishIfChanged("D1"), true)

        val missLogs = ArrayList<String>()
        // ★ 重置：本组断言依赖「成功切换=0」这个起点，
        //   上一组（兜底路径）已把计数推到 1，不重置会误判。
        FeaturedTrack.resetForTest()
        FeaturedTrack.sink = { qp -> sinkTarget = qp }
        FeaturedTrack.log = { m -> missLogs.add(m) }
        check("未命中时返回 false", FeaturedTrack.publishIfChanged("9999999999999999"), false)
        // 每 20 次记一次，故打 20 次必然产生日志
        for (i in 1..20) FeaturedTrack.publishIfChanged("999999999999999$i")
        check("未命中留下日志痕迹（不再静默）", missLogs.any { it.contains("FEATMISS") }, true)
        check("未命中不计入成功切换", FeaturedTrack.stats().contains("当前条切换=0次"), true)
        check("未命中数出现在 stats", FeaturedTrack.stats().contains("未命中=21"), true)

        // 被回收的条目必须查不到（否则会拿到死引用 → NPE 或读到脏数据）
        run {
            var tmp: P? = P("D")
            FeaturedTrack.putForTest("D", tmp!!)
            check("回收前可查回", FeaturedTrack.lookup("D") === tmp, true)
            tmp = null
            System.gc()
            // GC 不保证立即回收 → 只在确实回收了的情况下断言，
            // 否则会变成一条会随机失败的脆弱断言
            if (FeaturedTrack.lookup("D") == null) {
                println("  PASS  回收后查不到（死引用被清理）")
                pass++
            } else {
                println("  SKIP  回收后仍可达（GC 未回收，非实现问题）")
            }
        }

        FeaturedTrack.resetForTest()

        // ================= 结构类名判定（2026-09-25 真机实证回归） =================
        println()
        println("---- 结构类名判定 isStructClsName ----")
        // 判据来源：冷启动实测 LISTAUDIT
        //   vm.f.h.c sz=42 直=0 提=10 有文案=0 脏=10
        //     元素类=,g,LiveWeakNetworkRecordInfoManager,
        //            LiveWeakNetworkRemoveAndInsertManagerV2,LiveReduceShowManager,h,d,e
        // 这三个 *Manager 都是直播网络状态管理器（框架对象），
        // 此前因本函数不含 Manager 而穿透到 contains("Live") ⇒ 被误判为直播脏项。
        check("LiveWeakNetworkRecordInfoManager 判为结构类（不再误拦）",
            CfhUtil.isStructClsName("com.kuaishou.live.LiveWeakNetworkRecordInfoManager"), true)
        check("LiveWeakNetworkRemoveAndInsertManagerV2 判为结构类",
            CfhUtil.isStructClsName("com.kuaishou.live.LiveWeakNetworkRemoveAndInsertManagerV2"), true)
        check("LiveReduceShowManager 判为结构类",
            CfhUtil.isStructClsName("com.kuaishou.live.LiveReduceShowManager"), true)

        // 原有成员保持有效（不得因新增而回退）
        //   ⚠️ 字符串里的 `$d` 必须转义：Kotlin 会把未转义的 `$d` 当模板插值。
        check("Presenter 仍判为结构类（原行为保持）",
            CfhUtil.isStructClsName("com.x.PresenterV2\$d"), true)
        check("Callback 仍判为结构类",
            CfhUtil.isStructClsName("com.x.MilanoAttachCallbackPresenter\$a"), true)
        check("Fragment 仍判为结构类",
            CfhUtil.isStructClsName("com.x.SomeFragment"), true)

        // ★ 关键反例：真内容实体**不能**被判为结构类（否则漏拦）
        //   真机 ent= 实测只有这两类：
        check("VideoFeed 不是结构类（内容实体必须能拦）",
            CfhUtil.isStructClsName("com.kuaishou.android.model.feed.VideoFeed"), false)
        check("LiveStreamFeed 不是结构类（真实直播实体必须能拦）",
            CfhUtil.isStructClsName("com.kuaishou.android.model.feed.LiveStreamFeed"), false)
        check("普通直播类仍能命中 Live 判据",
            !CfhUtil.isStructClsName("com.kuaishou.live.SomeLiveRoom") &&
                "com.kuaishou.live.SomeLiveRoom".contains("Live", true), true)

        // ================= COW 快照提交补偿（2026-09-25 真机实证回归） =================
        println()
        println("---- COW 快照提交补偿 commitCowWrite ----")
        // 判据来源：真机实测 `★命中 hc=91926624 a@u size=5
        //   列表类=java.util.concurrent.CopyOnWriteArrayList`
        // 缺口：CfhPurge.sanitizeList 此前**无**补偿（而真源 vm.l.a 正是 COW）
        //   ⇒ 实测「删11条剩1 → 删20条剩23」的无效删除。
        run {
            val cow = java.util.concurrent.CopyOnWriteArrayList<Any>(listOf("a", "b", "c"))
            val before = ArrayList(cow)
            check("COW 列表：补偿返回 true", CfhUtil.commitCowWrite(cow), true)
            check("COW 列表：补偿不改变内容", ArrayList(cow) == before, true)
            check("COW 列表：补偿后仍可读", cow.size, 3)
        }
        run {
            // ★ 关键边界：空表必须跳过（否则 size-1 = -1 越界）
            val empty = java.util.concurrent.CopyOnWriteArrayList<Any>()
            check("空 COW 列表：补偿返回 false（不越界）", CfhUtil.commitCowWrite(empty), false)
        }
        run {
            // 非 COW：零成本直接返回
            check("ArrayList：补偿返回 false（无快照语义，不白复制）",
                CfhUtil.commitCowWrite(ArrayList(listOf("x"))), false)
            check("普通 List：补偿返回 false",
                CfhUtil.commitCowWrite(listOf("x")), false)
        }
        check("null：补偿返回 false（不抛异常）", CfhUtil.commitCowWrite(null), false)
        run {
            // 单元素 COW：末位下标 0，合法
            val one = java.util.concurrent.CopyOnWriteArrayList<Any>(listOf("only"))
            check("单元素 COW：补偿返回 true", CfhUtil.commitCowWrite(one), true)
            check("单元素 COW：内容保持", one.size, 1)
        }
        run {
            // 幂等：重复补偿无副作用
            val cow = java.util.concurrent.CopyOnWriteArrayList<Any>(listOf("p", "q"))
            CfhUtil.commitCowWrite(cow); CfhUtil.commitCowWrite(cow); CfhUtil.commitCowWrite(cow)
            check("重复补偿幂等（内容不变）", ArrayList(cow), arrayListOf<Any>("p", "q"))
        }

        // ★ 负向对照：确认上面这组断言不是恒真
        val beforeCowFail = fail
        runCatching {
            check("COW 负向对照（故意错误，必须失败）", CfhUtil.commitCowWrite(null), true)
        }
        if (fail == beforeCowFail) {
            fail++
            println("  FAIL  COW 负向对照没有失败 —— 该组断言无效")
        } else {
            fail--; pass++
            println("  PASS  COW 负向对照按预期失败（该组断言有效）")
        }

        // ★ 负向对照：确认上面这组断言不是恒真
        val beforeStructFail = fail
        runCatching {
            check("结构类名负向对照（故意错误，必须失败）",
                CfhUtil.isStructClsName("com.kuaishou.android.model.feed.VideoFeed"), true)
        }
        if (fail == beforeStructFail) {
            fail++
            println("  FAIL  结构类名负向对照没有失败 —— 该组断言无效")
        } else {
            fail--; pass++
            println("  PASS  结构类名负向对照按预期失败（该组断言有效）")
        }

        // ★ 负向对照：确认验证台**真的会失败**。
        //   没有这一条的话，「全 PASS」可能只是断言写错了（恒真）。
        //   故意断言一个错误值，捕获到失败才算验证台本身可信。
        val beforeFail = fail
        runCatching { check("负向对照（故意错误，必须失败）", KsVersion.tierOf(14, 7), KsVersion.Tier.LATEST) }
        if (fail == beforeFail) {
            fail++
            println("  FAIL  负向对照没有失败 —— 验证台断言无效，结果不可信")
        } else {
            // 把故意制造的失败扣回去：它证明的是「验证台有效」，不是「实现有错」
            fail--; pass++
            println("  PASS  负向对照按预期失败（验证台断言有效）")
        }

        println()
        println("================ 结果: $pass 通过, $fail 失败 ================")
        if (fail > 0) throw IllegalStateException("版本自适应验证失败: $fail 项")
    }
}
