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
    @Volatile private var lastCompletionMs = 0L
    @Volatile private var currentPlayer: Any? = null
    @Volatile private var checkRunning = false
    @Volatile private var mPauseRef: java.lang.reflect.Method? = null
    // ★ DexKit 播放器发现：xp 引用供异步发现线程 hook 用
    @Volatile private var xpRefD: XposedInterface? = null

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        Logger.d("PlaybackHook: hook() called")
        xpRefD = xp
        // ★ hookLoop(xp, cl) 已移除（2026-09-21）：见下方墓碑注释
        hookBgPause(xp, cl)
        // ★ DexKit 结构发现：按方法特征找播放器类（pause+start），抗混淆/插件化
        discoverPlayers(cl)
        // ★ 上限 + daemon：目标类不存在（宿主改名/插件化）时原线程每 2s 空转
        // 永不退出（电量/CPU 常驻税）；120 次 ≈ 4 分钟后放弃
        Thread {
            var tries = 0
            while (!delayedHooked && tries < 120) {
                tries++
                try { Thread.sleep(2000) } catch (_: Throwable) {}
                try { delayedHookAemon(xp, cl) } catch (t: Throwable) { Logger.d("pb delayed fail: ${t.message}") }
            }
        }.also { it.isDaemon = true }.start()
    }

    // ★ DexKit 结构发现（官方 DSL，2026-09）：create(apkPath) 单参 + use 自动 close；
    // 按「pause+start 无参」找播放器类，hook start()（noLoop 拦截 + currentPlayer 跟踪），
    // pause 写回 mPauseRef 供 BFS 后台暂停优先使用
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
                    Logger.always("DexKit candidates=${found.size}")
                    val xp = xpRefD ?: return@use
                    var hooked = 0
                    for (cd in found.take(8)) {
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
                                        if (Prefs.bool(Prefs.K_PB_NO_LOOP, false) && System.currentTimeMillis() - lastCompletionMs < 1000) {
                                            return@intercept null
                                        }
                                        chain.proceed().also {
                                            currentPlayer = chain.thisObject
                                            if (Prefs.bool(Prefs.K_PB_NO_LOOP, false) && pos != null && dur != null && pause != null && !checkRunning) {
                                                startCheckThread(pos, dur, pause)
                                            }
                                        }
                                    }
                                mPauseRef = pause
                                hooked++
                                Logger.always("DexKit hooked player: $cn")
                            } catch (_: Throwable) {}
                        } catch (_: Throwable) {}
                    }
                    Logger.always("DexKit hooked=$hooked")
                }
            } catch (t: Throwable) { Logger.always("DexKit fail: ${t.message}") }
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
                    if (Prefs.bool(Prefs.K_PB_NO_LOOP, false) && System.currentTimeMillis() - lastCompletionMs < 1000) {
                        Logger.d("pb: BLOCK start after completion (noLoop)")
                        return@intercept null
                    }
                    chain.proceed().also {
                        // ★ currentPlayer 与开关解耦（审阅 2026-09 P1）：原先只在
                        // K_PB_NO_LOOP 开启时赋值，而 pausePlayer 依赖它——只开
                        // 「后台暂停」时 currentPlayer 恒 null，功能整体失效
                        currentPlayer = chain.thisObject
                        if (Prefs.bool(Prefs.K_PB_NO_LOOP, false) && mGetPos != null && mGetDur != null && mPause != null && !checkRunning) {
                            startCheckThread(mGetPos, mGetDur, mPause)
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
                    // ★ 只拦「播放结束后的自动回跳」（与 start 拦截共用 1s 窗口）：
                    // 无差别拦 seekTo(0) 会把用户手动把进度条拖回片头也吞掉
                    if (Prefs.bool(Prefs.K_PB_NO_LOOP, false) && pos == 0 &&
                        System.currentTimeMillis() - lastCompletionMs < 1000
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
                    if (Prefs.bool(Prefs.K_PB_NO_LOOP, false)) {
                        lastCompletionMs = System.currentTimeMillis()
                        Logger.d("pb: BLOCK completion (noLoop), pause")
                        try { mPause?.invoke(chain.thisObject) } catch (_: Throwable) {}
                        return@intercept null
                    }
                    chain.proceed()
                }
            Logger.d("PlaybackHook: hooked $cn.notifyOnCompletion")
        }

    }

    private fun startCheckThread(mGetPos: java.lang.reflect.Method, mGetDur: java.lang.reflect.Method, mPause: java.lang.reflect.Method) {
        checkRunning = true
        Thread {
            var closedTicks = 0
            while (checkRunning) {
                try {
                    Thread.sleep(500)
                    val p = currentPlayer ?: break
                    // ★ 开关关闭连续 10s（20 tick）即退出守护线程（审阅 2026-09 P2）：
                    // 原先仅 continue，线程用过一次后永不退出；重新 start() 时会再拉起
                    if (!Prefs.bool(Prefs.K_PB_NO_LOOP, false)) {
                        if (++closedTicks >= 20) break
                        continue
                    }
                    closedTicks = 0
                    val pos = (mGetPos.invoke(p) as? Number)?.toLong() ?: continue
                    val dur = (mGetDur.invoke(p) as? Number)?.toLong() ?: continue

                    if (dur > 1000 && pos >= dur - 300) {
                        mPause.invoke(p)
                        Logger.d("pb: noLoop pause at pos=$pos dur=$dur")
                        break
                    }
                } catch (_: Throwable) {}
            }
            checkRunning = false
        }.also { it.isDaemon = true }.start()
    }

    // ★ hookLoop 整体移除（2026-09-21）：原 hook 播放器 setLooping/setRepeatMode 等
    // 循环参数方法，在「停止循环播放」开启时把参数改写为 false/0。
    // 删除理由：这是**在播放器对象上事后改参数**——不是本模块的「数据源拦截」路线，
    // 也拦不住（循环状态由播放器内部与上层各自维护，一次性改参不落地）；
    // 且实测从未生效：核心动作 `chain.args[0] = ...` 属 libxposed 只读 List 误用
    // （Chain.getArgs() 返回只读 List，List.set() 必抛，被 catch 吞掉）。
    // 注意：防循环功能其余两条机制仍保留且有效——① start 拦截（播完 1s 内吞掉 start，
    // 见 delayedHookAemon）② startCheckThread 播完时主动调 pause。后者是 2026-09-21
    // 实测「暂停标志卡住 / 点暂停无反应」的成因（注入暂停绕过上层状态机）；
    // 如需彻底移除防循环功能，应连同这两条与开关 K_PB_NO_LOOP 一起评估。


    // ★ 抖鸡对齐（PlaybackControlFeature）：真后台判定四路（onPause/onUserLeaveHint/
    // onStop/TRIM_MEMORY_UI_HIDDEN）+ 前台计数 + 220ms 去抖 + 多源暂停执行（BFS 找
    // 播放器，抗混淆/插件化）
    @Volatile private var resumedCount = 0
    @Volatile private var uiHidden = false
    @Volatile private var lastPauseTarget: Any? = null
    private val pauseMethodCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Class<*>, java.lang.reflect.Method>())
    private val pauseNames = arrayOf("handlePause", "pausePlay", "pause", "stopPlay", "stop")
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pauseRunnable = Runnable { pauseCurrentPlayback("bg") }

    private fun hookBgPause(xp: XposedInterface, cl: ClassLoader) {
        val actCls = Reflect.findClass("android.app.Activity", cl) ?: return
        // 0=onResume 1=onPause 2=onUserLeaveHint 3=onStop（全 Activity 计数，应用内
        // 跳转时 onPause/onResume 成对出现，resumedCount 维持 ≥1 → 不误暂停）
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
        // TRIM_MEMORY_UI_HIDDEN：系统级「界面已隐藏」信号（HOME/最近任务）
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
        // 只有真退到后台（无前台 Activity 或系统判定 UI 已隐藏）才暂停
        if (resumedCount > 0 && !uiHidden) return
        handler.removeCallbacks(pauseRunnable)
        handler.postDelayed(pauseRunnable, 220)
    }

    private fun pauseCurrentPlayback(reason: String): Boolean {
        try {
            // 1) 已知目标（currentPlayer + 上次成功暂停对象）
            val cands = ArrayList<Any>()
            currentPlayer?.let { cands.add(it) }
            lastPauseTarget?.let { cands.add(it) }
            for (c in cands) {
                if (c != null && invokePause(c, reason)) return true
            }
            // 2) BFS 可见 Fragment 对象图找带 pause 方法的对象（抖鸡 findPauseTarget 同款）
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

    // BFS 深度 2 / 80 对象（抖鸡 PAUSE_SCAN_MAX_DEPTH=2 / MAX_OBJECTS=80 同款）
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
