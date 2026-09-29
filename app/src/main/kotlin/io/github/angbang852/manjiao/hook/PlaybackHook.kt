package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.Message
import android.view.View
import android.view.ViewGroup
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

object PlaybackHook {

    @Volatile private var delayedHooked = false
    @Volatile private var checkRunning = false
    @Volatile private var mPauseRef: java.lang.reflect.Method? = null

    // ==================== 播放器实例状态（2026-09-23 按实例隔离）====================
    //
    // ★★ 为什么把「完成时刻」从全局标量改成**按实例记录**（真机缺陷修复）：
    //
    //   原实现有两个全局变量：
    //     `@Volatile var lastCompletionMs: Long`  —— 最近一次播完的时刻
    //     `@Volatile var currentPlayer: Any?`     —— 最近一次 start 的播放器
    //
    //   而实测（快手 14.8.20.50218）有**两条独立路径**都会触发 start 拦截：
    //     · DexKit 结构发现路径（命中 `aym.b` 等混淆类）
    //     · 硬编码兜底路径（`com.kwai.video.aemonplayer.AemonMediaPlayer`）
    //   两个**不同类的实例**交替写同一个全局字段，于是：
    //
    //   ① 暂停打在旧实例上 —— 用户看到当前视频「卡在暂停按钮」不动
    //   ② 用户的「点击播放」被判据 `now - lastCompletionMs < 1000` 拦掉
    //      —— 而那个时刻来自**另一个视频**，点击因此毫无反应
    //   ③ 页面重建时取到相邻条目 —— 表现为「滑下去正常、滑回来视频换了」
    //
    //   修法：所有「与某次播放有关」的状态都按**播放器实例身份**存储。
    //   换视频 = 换实例 = 状态天然隔离，旧实例的完成时刻不再影响新实例。
    //
    //   用 WeakHashMap 而非强引用：播放器实例由快手持有，模块只做旁挂记录；
    //   强引用会把已销毁的播放器钉在内存（详情页反复滑动会线性增长）。
    //   同步包装因为 hook 回调跑在任意线程。

    /** 最近一次 start 的播放器（后台暂停功能用；不再是 noLoop 的判据来源） */
    @Volatile private var currentPlayer: Any? = null

    // ★ 状态机已外移到 adapt/PlaybackState（2026-09-23）——它持有 WeakHashMap
    //   且是**纯逻辑**，放在这里会让 AdaptVerify 因 android.os.Handler 无法在
    //   JVM 加载而跑不起来（NoClassDefFoundError）。外移后「按实例隔离」这条
    //   不变量有了回归断言，不再只靠真机复现。

    /** 记录某实例的完成时刻 */
    private fun markCompletion(p: Any) =
        io.github.angbang852.manjiao.adapt.PlaybackState.markCompletion(p)

    /** 该实例是否在「刚播完的 1 秒窗口」内（只看该实例自己的记录） */
    private fun justCompleted(p: Any): Boolean =
        io.github.angbang852.manjiao.adapt.PlaybackState.justCompleted(p)


    /**
     * **旧 noLoop 机制是否还要生效**（2026-09-23）。
     *
     * ★ 为什么需要这个开关：新增的 [NoLoopGuard]（基于 `PlayModule` 进度回绕）
     *   才是 14.8+ 上的正确实现。旧机制（`start` 拦截 + 看门狗轮询）在
     *   14.8.20.50218 上**从不被调用**，但它**仍然活着** ——
     *   若两者同时启用，会出现实测到的糟糕现象：
     *
     *   ```
     *   PB-OBS state=Playing   ← 用户点了一下，快手进入播放
     *   PB-OBS state=Paused    ← 旧看门狗立刻又 pause 回去
     *   PB-OBS state=Playing
     *   PB-OBS state=Paused    ...（反复交替）
     *   ```
     *   用户观感就是**「要点两下才能播放」**（第 1 下被压回、第 2 下才走）。
     *
     *   因此：**新实现装成功时，旧机制整体退场**；新实现不可用（旧版快手
     *   没有 PlayModule）时才回落到旧机制 —— 这正好也是 14.7.40 的路径，
     *   老版本行为不变。
     */
    @Volatile private var newGuardInstalled = false

    private fun legacyNoLoopActive(): Boolean =
        Prefs.bool(Prefs.K_PB_NO_LOOP, false) && !newGuardInstalled
    // 鈽?DexKit 鎾斁鍣ㄥ彂鐜帮細xp 寮曠敤渚涘紓姝ュ彂鐜扮嚎绋?hook 鐢?
    @Volatile private var xpRefD: XposedInterface? = null

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        Logger.d("PlaybackHook: hook() called")
        xpRefD = xp
        // 鈽?hookLoop(xp, cl) 宸茬Щ闄わ紙2026-09-21锛夛細瑙佷笅鏂瑰纰戞敞閲?
        hookBgPause(xp, cl)
        // 鈽?DexKit 缁撴瀯鍙戠幇锛氭寜鏂规硶鐗瑰緛鎵炬挱鏀惧櫒绫伙紙pause+start锛夛紝鎶楁贩娣?鎻掍欢鍖?
        discoverPlayers(cl)
        // ★ 新 noLoop 实现（2026-09-23，PlayModule 版）：装成功则旧机制整体退场。
        //   顺序很重要 —— 必须在 hookBgPause/discoverPlayers 之外**尽早**装，
        //   因为旧机制的钩子已在上面的 discoverPlayers/延迟线程里注册。
        try {
            NoLoopGuard.install(xp, cl)
            newGuardInstalled = true
        } catch (_: Throwable) {}

        // 注：排障期的 PlaybackProbe（View 树/数据层/PlayModule 观测）已移除（2026-09-23）。
        //   它的使命是摸清 14.8 的播放链路 —— 结论已固化到 NoLoopGuard 与
        //   《版本自适应适配方案.md》。留着会在每次启动白装 5+ 只读钩子、遍历 View 树，
        //   属纯浪费。若日后要探测新版本，从 git 历史取回即可。
        // 鈽?涓婇檺 + daemon锛氱洰鏍囩被涓嶅瓨鍦紙瀹夸富鏀瑰悕/鎻掍欢鍖栵級鏃跺師绾跨▼姣?2s 绌鸿浆
        // 姘镐笉閫€鍑猴紙鐢甸噺/CPU 甯搁┗绋庯級锛?20 娆?鈮?4 鍒嗛挓鍚庢斁寮?
        Thread {
            var tries = 0
            while (!delayedHooked && tries < 120) {
                tries++
                try { Thread.sleep(2000) } catch (_: Throwable) {}
                try { delayedHookAemon(xp, cl) } catch (t: Throwable) { Logger.d("pb delayed fail: ${t.message}") }
            }
        }.also { it.isDaemon = true }.start()
    }

    // 鈽?DexKit 缁撴瀯鍙戠幇锛堝畼鏂?DSL锛?026-09锛夛細create(apkPath) 鍗曞弬 + use 鑷姩 close锛?
    // 鎸夈€宲ause+start 鏃犲弬銆嶆壘鎾斁鍣ㄧ被锛宧ook start()锛坣oLoop 鎷︽埅 + currentPlayer 璺熻釜锛夛紝
    // pause 鍐欏洖 mPauseRef 渚?BFS 鍚庡彴鏆傚仠浼樺厛浣跨敤
    private fun discoverPlayers(cl: ClassLoader) {
        Thread {
            try {
                System.loadLibrary("dexkit")
                val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.app.Application
                val apk = app.applicationInfo.sourceDir
                org.luckypray.dexkit.DexKitBridge.create(apk).use { bridge ->
                    val found = bridge.findClass {
                        matcher {
                            methods {
                                add { name = "pause" }
                                add { name = "start" }
                            }
                        }
                    }
                    Logger.once("dexkit.cands", "DexKit candidates=${found.size}")
                    // ★ 候选排查（2026-09-23 真机「禁止循环没生效」修复）：
                    //   原先取前 8 个候选逐个试，结果命中的是
                    //   `com.kuaishou.commercial.tachikoma.view.MKVideoView`（**广告**的
                    //   VideoView）—— 用户真正看的视频不经过它，于是 start 钩子白装，
                    //   「禁止循环播放」看起来完全没生效。
                    //   这里把全部候选的**类名 + 是否含 getCurrentPosition/getDuration**
                    //   打出来（once 级，每进程一次），据此选出真正的播放器。
                    Logger.once("dexkit.candlist") {
                        val sb = StringBuilder("DexKit 候选清单:")
                        for ((i, cd) in found.withIndex()) {
                            if (i >= 40) { sb.append(" ...(共${found.size})"); break }
                            val hasPos = try {
                                cd.getInstance(cl).getMethod("getCurrentPosition") != null
                            } catch (_: Throwable) { false }
                            val hasDur = try {
                                cd.getInstance(cl).getMethod("getDuration") != null
                            } catch (_: Throwable) { false }
                            sb.append("\n  ").append(cd.name)
                                .append(if (hasPos) " [+pos]" else "")
                                .append(if (hasDur) " [+dur]" else "")
                        }
                        sb.toString()
                    }
                    val xp = xpRefD ?: return@use
                    var hooked = 0
                    // ★★ 候选**排序**（2026-09-23 真机「禁止循环没生效」修复）：
                    //
                    //   原实现 `found.take(8)` 直接取 DexKit 返回的前 8 个 —— 而实测
                    //   候选有 58 个、顺序任意，前 8 个里真正的播放器一个都没有，
                    //   于是钩子装到了 `MKVideoView`（**广告**的 VideoView）上：
                    //   用户看的视频根本不经过它 ⇒「禁止循环播放」看起来完全没生效。
                    //
                    //   实测候选清单里真正的播放器是（含 getCurrentPosition/getDuration）：
                    //     com.kwai.video.player.kwai_player.KwaiMediaPlayer   ← 主力
                    //     com.kwai.video.player.KsMediaPlayerImpl
                    //     com.kwai.video.wayne.player.main.WaynePlayer          ← 直播
                    //     com.kwai.video.aemonplayer.AemonMediaPlayer
                    //   注意 `kwai_player` 这一层包名是重构后新增的（README 记录的
                    //   「KwaiMediaPlayerImpl 系列全 MISS」正是被它取代）。
                    //
                    //   排序依据（**语义包名优先，不看混淆名**）见 adapt/PlayerRank。
                    //   独立成类是为了能被 AdaptVerify 断言（本文件持有 android.os.Handler
                    //   无法在 JVM 加载）。
                    val rankMap = found.associateBy { it.name }
                    val ranked = io.github.angbang852.manjiao.adapt.PlayerRank
                        .rank(found.map { it.name })
                        .mapNotNull { rankMap[it] }
                    Logger.once("dexkit.rank") {
                        "DexKit 排序后前 8: " + ranked.take(8).joinToString(", ") { it.name }
                    }
                    for (cd in ranked.take(8)) {
                        try {
                            val cn = cd.name
                            val cc = cd.getInstance(cl)
                            val pause = try { cc.getMethod("pause") } catch (_: Throwable) { null }
                            val start = try { cc.getMethod("start") } catch (_: Throwable) { null }
                            val pos = try { cc.getMethod("getCurrentPosition") } catch (_: Throwable) { null }
                            val dur = try { cc.getMethod("getDuration") } catch (_: Throwable) { null }
                            if (pause == null || start == null || pause.parameterTypes.isNotEmpty() || start.parameterTypes.isNotEmpty()) continue
                            try {
                                xp.hook(start).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                    .setId("pb.dexkit.start").intercept { chain ->
                                        // ★ 按实例判定：只有**这个实例**刚播完才拦。
                                        //   换视频后的新实例没有完成记录 → 用户点击可正常播放。
                                        if (legacyNoLoopActive() && justCompleted(chain.thisObject)) {
                                            Logger.d("pb: BLOCK start after completion (noLoop, same instance)")
                                            return@intercept null
                                        }
                                        chain.proceed().also {
                                            currentPlayer = chain.thisObject
                                            if (legacyNoLoopActive() && pos != null && dur != null && pause != null && !checkRunning) {
                                                startCheckThread(chain.thisObject, pos, dur, pause)
                                            }
                                        }
                                    }
                                mPauseRef = pause
                                hooked++
                                Logger.once("dexkit.player", "DexKit hooked player: $cn")
                            } catch (_: Throwable) {}
                        } catch (_: Throwable) {}
                    }
                    Logger.once("dexkit.hooked", "DexKit hooked=$hooked")
                }
            } catch (t: Throwable) { Logger.once("dexkit.fail", "DexKit fail: ${t.message}") }
        }.also { it.isDaemon = true; it.name = "MJ-DexKit" }.start()
    }

    private fun delayedHookAemon(xp: XposedInterface, cl: ClassLoader) {
        val cn = "com.kwai.video.aemonplayer.AemonMediaPlayer"
        val c = Reflect.findClass(cn, cl) ?: try {
            val tcl = Thread.currentThread().contextClassLoader
            if (tcl != null) Class.forName(cn, false, tcl) else null
        } catch (_: Throwable) { null } ?: return
        delayedHooked = true
        Logger.d("PlaybackHook: $cn found methods=${c.declaredMethods.size}")
        val mPause = Reflect.findMethod(c, "pause", 0)
        mPauseRef = mPause
        val mGetPos = Reflect.findMethod(c, "getCurrentPosition", 0)
        val mGetDur = Reflect.findMethod(c, "getDuration", 0)
        val mStart = Reflect.findMethod(c, "start", 0)
        if (mStart != null) {
            xp.hook(mStart).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("pb.aemon.start").intercept { chain ->
                    // ★ 按实例判定（同 DexKit 路径）：见 justCompleted 的说明
                    if (legacyNoLoopActive() && justCompleted(chain.thisObject)) {
                        Logger.d("pb: BLOCK start after completion (noLoop, same instance)")
                        return@intercept null
                    }
                    chain.proceed().also {
                        // 鈽?currentPlayer 涓庡紑鍏宠В鑰︼紙瀹￠槄 2026-09 P1锛夛細鍘熷厛鍙湪
                        // K_PB_NO_LOOP 寮€鍚椂璧嬪€硷紝鑰?pausePlayer 渚濊禆瀹冣€斺€斿彧寮€
                        // 銆屽悗鍙版殏鍋溿€嶆椂 currentPlayer 鎭?null锛屽姛鑳芥暣浣撳け鏁?
                        currentPlayer = chain.thisObject
                        if (legacyNoLoopActive() && mGetPos != null && mGetDur != null && mPause != null && !checkRunning) {
                            startCheckThread(chain.thisObject, mGetPos, mGetDur, mPause)
                        }
                    }
                }
            Logger.d("PlaybackHook: hooked $cn.start")
        }
        val mSeek = Reflect.findMethod(c, "seekTo", 1)
        if (mSeek != null) {
            xp.hook(mSeek).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("pb.aemon.seekTo").intercept { chain ->
                    val pos = chain.args.getOrNull(0)
                    // 鈽?鍙嫤銆屾挱鏀剧粨鏉熷悗鐨勮嚜鍔ㄥ洖璺炽€嶏紙涓?start 鎷︽埅鍏辩敤 1s 绐楀彛锛夛細
                    // 鏃犲樊鍒嫤 seekTo(0) 浼氭妸鐢ㄦ埛鎵嬪姩鎶婅繘搴︽潯鎷栧洖鐗囧ご涔熷悶鎺?
                    if (legacyNoLoopActive() && pos == 0 &&
                        justCompleted(chain.thisObject)
                    ) {
                        Logger.d("pb: BLOCK seekTo(0) (noLoop auto-rewind)")
                        return@intercept null
                    }
                    chain.proceed()
                }
            Logger.d("PlaybackHook: hooked $cn.seekTo")
        }
        val mComplete = Reflect.findMethod(c, "notifyOnCompletion", 0)
        if (mComplete != null) {
            xp.hook(mComplete).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("pb.aemon.notifyOnCompletion").intercept { chain ->
                    if (legacyNoLoopActive()) {
                        markCompletion(chain.thisObject)
                        Logger.d("pb: BLOCK completion (noLoop), pause")
                        try { mPause?.invoke(chain.thisObject) } catch (_: Throwable) {}
                        return@intercept null
                    }
                    chain.proceed()
                }
            Logger.d("PlaybackHook: hooked $cn.notifyOnCompletion")
        }

    }
    /**
     * 播完即停的看门狗（**按实例**，2026-09-23 修复）。
     *
     * ★ 原实现每 tick 读全局 `currentPlayer`，而该字段会被**任意** start 覆盖。
     *   详情页滑动时新旧播放器交替 start，看门狗可能：
     *     · 中途改去检查另一个实例（拿错 pos/dur）
     *     · 对已经离屏的实例调 pause —— 用户看到当前视频「卡住不动」
     *   现改为**绑定启动它的那个实例**，全程只操作它；实例被回收即退出，
     *   不再影响后续视频（这正是「滑回来后视频变了」的成因之一）。
     *
     * @param target 本看门狗负责的播放器实例（弱引用持有，不阻止回收）
     */
    private fun startCheckThread(
        target: Any,
        mGetPos: java.lang.reflect.Method,
        mGetDur: java.lang.reflect.Method,
        mPause: java.lang.reflect.Method,
    ) {
        checkRunning = true
        val ref = java.lang.ref.WeakReference(target)
        Thread {
            var closedTicks = 0
            while (checkRunning) {
                try {
                    Thread.sleep(500)
                    val p = ref.get() ?: break
                    // 鈽?寮€鍏冲叧闂繛缁?10s锛?0 tick锛夊嵆閫€鍑哄畧鎶ょ嚎绋嬶紙瀹￠槄 2026-09 P2锛夛細
                    // 鍘熷厛浠?continue锛岀嚎绋嬬敤杩囦竴娆″悗姘镐笉閫€鍑猴紱閲嶆柊 start() 鏃朵細鍐嶆媺璧?
                    if (!legacyNoLoopActive()) {
                        if (++closedTicks >= 20) break
                        continue
                    }
                    closedTicks = 0
                    val pos = (mGetPos.invoke(p) as? Number)?.toLong() ?: continue
                    val dur = (mGetDur.invoke(p) as? Number)?.toLong() ?: continue

                    if (dur > 1000 && pos >= dur - 300) {
                        mPause.invoke(p)
                        markCompletion(p)
                        Logger.d("pb: noLoop pause at pos=$pos dur=$dur")
                        break
                    }
                } catch (_: Throwable) {}
            }
            checkRunning = false
        }.also { it.isDaemon = true }.start()
    }

    // 鈽?hookLoop 鏁翠綋绉婚櫎锛?026-09-21锛夛細鍘?hook 鎾斁鍣?setLooping/setRepeatMode 绛?
    // 寰幆鍙傛暟鏂规硶锛屽湪銆屽仠姝㈠惊鐜挱鏀俱€嶅紑鍚椂鎶婂弬鏁版敼鍐欎负 false/0銆?
    // 鍒犻櫎鐞嗙敱锛氳繖鏄?*鍦ㄦ挱鏀惧櫒瀵硅薄涓婁簨鍚庢敼鍙傛暟**鈥斺€斾笉鏄湰妯″潡鐨勩€屾暟鎹簮鎷︽埅銆嶈矾绾匡紝
    // 涔熸嫤涓嶄綇锛堝惊鐜姸鎬佺敱鎾斁鍣ㄥ唴閮ㄤ笌涓婂眰鍚勮嚜缁存姢锛屼竴娆℃€ф敼鍙備笉钀藉湴锛夛紱
    // 涓斿疄娴嬩粠鏈敓鏁堬細鏍稿績鍔ㄤ綔 `chain.args[0] = ...` 灞?libxposed 鍙 List 璇敤
    // 锛圕hain.getArgs() 杩斿洖鍙 List锛孡ist.set() 蹇呮姏锛岃 catch 鍚炴帀锛夈€?
    // 娉ㄦ剰锛氶槻寰幆鍔熻兘鍏朵綑涓ゆ潯鏈哄埗浠嶄繚鐣欎笖鏈夋晥鈥斺€斺憼 start 鎷︽埅锛堟挱瀹?1s 鍐呭悶鎺?start锛?
    // 瑙?delayedHookAemon锛夆憽 startCheckThread 鎾畬鏃朵富鍔ㄨ皟 pause銆傚悗鑰呮槸 2026-09-21
    // 瀹炴祴銆屾殏鍋滄爣蹇楀崱浣?/ 鐐规殏鍋滄棤鍙嶅簲銆嶇殑鎴愬洜锛堟敞鍏ユ殏鍋滅粫杩囦笂灞傜姸鎬佹満锛夛紱
    // 濡傞渶褰诲簳绉婚櫎闃插惊鐜姛鑳斤紝搴旇繛鍚岃繖涓ゆ潯涓庡紑鍏?K_PB_NO_LOOP 涓€璧疯瘎浼般€?


    // 鈽?鎶栭浮瀵归綈锛圥laybackControlFeature锛夛細鐪熷悗鍙板垽瀹氬洓璺紙onPause/onUserLeaveHint/
    // onStop/TRIM_MEMORY_UI_HIDDEN锛? 鍓嶅彴璁℃暟 + 220ms 鍘绘姈 + 澶氭簮鏆傚仠鎵ц锛圔FS 鎵?
    // 鎾斁鍣紝鎶楁贩娣?鎻掍欢鍖栵級
    @Volatile private var resumedCount = 0
    @Volatile private var uiHidden = false
    @Volatile private var lastPauseTarget: Any? = null
    private val pauseMethodCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Class<*>, java.lang.reflect.Method>())
    private val pauseNames = arrayOf("handlePause", "pausePlay", "pause", "stopPlay", "stop")
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pauseRunnable = Runnable { pauseCurrentPlayback("bg") }

    private fun hookBgPause(xp: XposedInterface, cl: ClassLoader) {
        val actCls = Reflect.findClass("android.app.Activity", cl) ?: return
        // 0=onResume 1=onPause 2=onUserLeaveHint 3=onStop锛堝叏 Activity 璁℃暟锛屽簲鐢ㄥ唴
        // 璺宠浆鏃?onPause/onResume 鎴愬鍑虹幇锛宺esumedCount 缁存寔 鈮? 鈫?涓嶈鏆傚仠锛?
        val lifecycle = mapOf("onResume" to 0, "onPause" to 1, "onUserLeaveHint" to 2, "onStop" to 3)
        for ((mn, kind) in lifecycle) {
            val m = Reflect.findMethod(actCls, mn, 0) ?: continue
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("pb.bg.$mn").intercept { chain ->
                    chain.proceed()
                    try {
                        when (kind) {
                            0 -> { resumedCount++; uiHidden = false; handler.removeCallbacks(pauseRunnable) }
                            1 -> { if (resumedCount > 0) resumedCount--; requestBgPause() }
                            2 -> requestBgPause()
                            3 -> requestBgPause()
                        }
                    } catch (_: Throwable) {}
                    null
                }
            Logger.d("PlaybackHook: hooked Activity.$mn")
        }
        // TRIM_MEMORY_UI_HIDDEN锛氱郴缁熺骇銆岀晫闈㈠凡闅愯棌銆嶄俊鍙凤紙HOME/鏈€杩戜换鍔★級
        try {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.app.Application
            app.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
                override fun onConfigurationChanged(c: android.content.res.Configuration) {}
                override fun onLowMemory() {}
                override fun onTrimMemory(level: Int) {
                    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                        uiHidden = true
                        requestBgPause()
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun requestBgPause() {
        if (!Prefs.bool(Prefs.K_PB_BG_PAUSE, false)) return
        // 鍙湁鐪熼€€鍒板悗鍙帮紙鏃犲墠鍙?Activity 鎴栫郴缁熷垽瀹?UI 宸查殣钘忥級鎵嶆殏鍋?
        if (resumedCount > 0 && !uiHidden) return
        handler.removeCallbacks(pauseRunnable)
        handler.postDelayed(pauseRunnable, 220)
    }

    private fun pauseCurrentPlayback(reason: String): Boolean {
        try {
            // 1) 宸茬煡鐩爣锛坈urrentPlayer + 涓婃鎴愬姛鏆傚仠瀵硅薄锛?
            val cands = ArrayList<Any>()
            currentPlayer?.let { cands.add(it) }
            lastPauseTarget?.let { cands.add(it) }
            for (c in cands) {
                if (c != null && invokePause(c, reason)) return true
            }
            // 2) BFS 鍙 Fragment 瀵硅薄鍥炬壘甯?pause 鏂规硶鐨勫璞★紙鎶栭浮 findPauseTarget 鍚屾锛?
            val frag = ContentFilterHook.currentFeedFragment() ?: return false
            val t = findPauseTarget(frag)
            if (t != null && invokePause(t, reason)) return true
        } catch (_: Throwable) {}
        return false
    }

    private fun invokePause(obj: Any, reason: String): Boolean {
        val m = findPauseMethod(obj.javaClass) ?: return false
        return try {
            val r = if (m.parameterTypes.size == 1 && m.parameterTypes[0] == java.lang.Boolean.TYPE) {
                m.invoke(obj, true)
            } else {
                m.invoke(obj)
            }
            if (r == null || r == true) {
                lastPauseTarget = obj
                Logger.always("pb: bg pause via ${m.name} reason=$reason cls=${obj.javaClass.simpleName}")
                true
            } else false
        } catch (_: Throwable) { false }
    }

    private fun findPauseMethod(cls: Class<*>): java.lang.reflect.Method? {
        pauseMethodCache[cls]?.let { return it }
        for (n in pauseNames) {
            val m = if (n == "handlePause") {
                try { cls.getMethod("handlePause", java.lang.Boolean.TYPE) } catch (_: Throwable) { null }
            } else {
                try { cls.getMethod(n) } catch (_: Throwable) { null }
            }
            if (m != null && !java.lang.reflect.Modifier.isAbstract(m.modifiers)) {
                try { m.isAccessible = true } catch (_: Throwable) {}
                pauseMethodCache[cls] = m
                return m
            }
        }
        return null
    }

    // BFS 娣卞害 2 / 80 瀵硅薄锛堟姈楦?PAUSE_SCAN_MAX_DEPTH=2 / MAX_OBJECTS=80 鍚屾锛?
    private fun findPauseTarget(root: Any): Any? {
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        val counter = intArrayOf(0)
        fun walk(o: Any?, depth: Int): Any? {
            if (o == null || depth > 2 || seen.containsKey(o) || counter[0] >= 80) return null
            seen.put(o, true); counter[0]++
            if (findPauseMethod(o.javaClass) != null) return o
            if (depth == 2) return null
            var c: Class<*>? = o.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    if (!isPauseScanCandidate(f.type)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(o) ?: continue
                        if (v === o) continue
                        walk(v, depth + 1)?.let { return it }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            return null
        }
        return walk(root, 0)
    }

    private fun isPauseScanCandidate(cls: Class<*>): Boolean {
        if (cls.isPrimitive || cls.isArray || cls.isEnum || cls == String::class.java ||
            cls == java.lang.Boolean::class.java || cls == java.lang.Character::class.java ||
            cls == java.lang.Class::class.java || Number::class.java.isAssignableFrom(cls) ||
            ClassLoader::class.java.isAssignableFrom(cls)) return false
        val n = cls.name
        return !(n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("kotlin.") || n.startsWith("android."))
    }
}
