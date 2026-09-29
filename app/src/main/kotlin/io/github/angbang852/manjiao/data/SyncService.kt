package io.github.angbang852.manjiao.data

import android.content.SharedPreferences
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.hook.ContentFilterHook
import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

// ★ XposedService 收编广播同步（2026-09 S3，加性设计）：
// 两个进程都经 XposedServiceHelper 拿到同一个 XposedService（模块 App 绑定到目标
// 进程的 XposedProvider；目标进程由 libxposed 投递本地 service），
// getRemotePreferences("slowkick_remote") 即跨进程共享配置。
// 广播/媒体文件链路全部保留作兜底——service 不可用时（目标未运行/未激活）回退旧链路。
object SyncService {
    private const val PREFS_NAME = "slowkick_remote"

    @Volatile private var service: XposedService? = null
    @Volatile private var rp: SharedPreferences? = null
    private val inited = java.util.concurrent.atomic.AtomicBoolean(false)
    private val targetSyncArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 绑定状态回调 —— **修复「都关闭后打开 app 显示未激活」的关键**（2026-09）。
     *
     * 问题：`XposedServiceHelper` 的绑定是**异步**的。日志实测：
     *   `22:36:42.673` Activity 已渲染
     *   `22:36:42.7xx` SyncService bound: rp=true   ← 绑定在渲染之后才完成
     * 于是 `statusCard()` 读到的是「尚未绑定」的快照 ⇒ `isActive()==false`
     * ⇒ 显示「未激活」，而模块其实是好的。
     *
     * 解决：绑定完成/断开时主动通知界面重绘，而不是让界面去猜时机。
     */
    @Volatile private var stateListener: (() -> Unit)? = null

    /**
     * 注册绑定状态变化回调（由 Activity 在 onResume 注册、onPause 注销，避免泄漏）。
     *
     * ★ 注册后**立即触发一次通知**（2026-09）：
     * 若绑定在注册之前就已完成/超时，`notifyState()` 不会再有事件可发，
     * 界面就会停在注册那一刻的快照上。主动触发一次可保证「谁注册谁立刻拿到当前状态」。
     * 注意用 post（异步）而非直接调用，避免在 onResume 期间重入界面刷新。
     */
    fun setStateListener(l: (() -> Unit)?) {
        stateListener = l
        if (l != null) notifyState()
    }

    /**
     * 开始绑定的时刻（0 = 从未开始）。
     *
     * ★ 为什么用「时间」而不是一个 `settled` 布尔（2026-09 修复「一直显示正在检查」）：
     * 最初我用 `bindAttempted` 布尔 + 一次 `postDelayed` 兜底，但 `init()` 开头有
     * `if (!inited.compareAndSet(false, true)) return` —— **一个进程只跑一次**。
     * 于是：进程先前启动过且绑定失败（`bindAttempted` 仍为 false）→ 用户退出
     * Activity 但进程存活 → 再次打开 app 时 `init()` 直接 return，**兜底定时器
     * 不会再排** → `bindAttempted` 永远是 false → 界面永久卡在「正在检查」。
     *
     * 改成纯时间判定后不存在这个状态：`isBindSettled()` 只比较「当前时间 vs 开始时刻」，
     * 无论 init 跑过几次、Activity 重建几次，结论都一致且必然收敛。
     */
    @Volatile private var bindStartedAt = 0L

    /** 判定绑定的等待上限：超过就认为「没绑上」，不再显示「正在检查」 */
    private const val BIND_WAIT_MS = 2500L

    /**
     * 绑定过程是否已有明确结论。
     *
     * 三种情况都算已定：
     * - 已绑定成功（service 非空）
     * - 检测到绑定失败（框架给出 died 状态、或重试后仍无 service）
     * - **等待超时**（超过 [BIND_WAIT_MS] 仍没有 service）
     */
    /** 绑定过程是否已有明确结论。
     *
     * 三种情况都算已定：
     * - 已绑定成功（service 非空）
     * - 检测到绑定失败（框架给出 died 状态、或重试后仍无 service）
     * - **等待超时**（超过 [BIND_WAIT_MS] 仍没有 service）
     */
    fun isBindSettled(): Boolean {
        if (service != null) return true
        val t0 = bindStartedAt
        // 从未开始过 → 立即启动一次绑定并开始计时（自愈：不依赖调用方记得先 init）
        if (t0 == 0L) {
            try { init() } catch (_: Throwable) {}
            // 确保一定留下计时起点，否则永远等不到超时
            if (bindStartedAt == 0L) bindStartedAt = System.currentTimeMillis()
            return false
        }
        return System.currentTimeMillis() - t0 >= BIND_WAIT_MS
    }

    /** 绑定内部状态摘要（日志排障用） */
    fun debugState(): String {
        val t0 = bindStartedAt
        val el = if (t0 == 0L) -1L else System.currentTimeMillis() - t0
        return "svc=${service != null} el=${el}ms init=$inited settled=${isBindSettled()}"
    }

    fun init() {
        // ★ 即使已初始化过，也要刷新计时起点，否则复用旧起点会让超时判定失真
        if (bindStartedAt == 0L) bindStartedAt = System.currentTimeMillis()
        if (!inited.compareAndSet(false, true)) return
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    try { rp = s.getRemotePreferences(PREFS_NAME) } catch (_: Throwable) { rp = null }
                    // ★ 诊断（2026-09）：scope 是判据的唯一来源，必须能看到它的真实内容
                    Logger.always(
                        "SyncService bound: rp=${rp != null} " +
                            "scope=${try { s.scope } catch (t: Throwable) { "ERR:${t.message}" }} " +
                            "targets=${try { s.runningTargets.map { it.processName } } catch (t: Throwable) { "ERR:${t.message}" }}"
                    )
                    // 目标进程绑定完成 → 立即把共享配置灌进缓存
                    if (targetSyncArmed.get()) refreshFromService()
                    // ★ 通知界面重绘：此刻才能准确判断激活状态
                    notifyState()
                }
                override fun onServiceDied(s: XposedService) {
                    service = null
                    rp = null
                    Logger.always("SyncService died")
                    notifyState()
                }
            })
        } catch (t: Throwable) { Logger.d("SyncService init fail: ${t.message}") }
        // ★ 到点通知一次：让界面从「正在检查」落到明确结论。
        // 因为 settle 判定改为纯时间比较（见 bindStartedAt 注释），即使这次
        // init() 之后 Activity 重建、或本进程早已 init 过，判定结果都一致，
        // 不存在「兜底定时器没排上 ⇒ 永远卡住」的情况。
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { notifyState() },
                BIND_WAIT_MS + 100
            )
        } catch (_: Throwable) {}
    }

    private fun notifyState() {
        try {
            val l = stateListener ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post { try { l() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }

    fun prefs(): SharedPreferences? = rp

    // ==================== 心跳通道（2026-09，已降级为备用） ====================

    /**
     * 心跳键：快手进程内的模块写自己的 pid 与时间戳。
     *
     * ★ 历史：这是第 4 版激活方案的核心，**已被证实不可行并降级**。
     *
     * 当时参考了 toki / autodaily 两个模块，得出「不要在 app 侧查询激活状态，
     * 改用与目标进程的实际通信结果」的结论 —— **这个结论方向是对的**，
     * 但我把它实现成了「让快手进程主动写盘」，而这条路径在当前环境下不通：
     *
     * | 通道 | 为什么不通 |
     * |---|---|
     * | `rp`（getRemotePreferences） | `rp` 只在 **app 进程**的 `onServiceBind` 里赋值，快手进程恒为 null |
     * | `/sdcard/Android/media/<模块包名>/` | 目录属主是模块（`u0_a224`/组 `media_rw`），快手以 `u0_a1305` 运行不在该组 ⇒ EACCES |
     *
     * 而 toki / autodaily 之所以能用「通信结果」判活，是因为它们的通信走的是
     * **框架提供的 binder 通道**（`getRemotePreferences` 在宿主进程内调用），
     * 不是让宿主进程去写一个它没权限写的文件。
     *
     * 真正的正解（第 5 版，照搬 WeKit）：**直接读框架下发的 `service.scope`**，
     * 什么都不用写。见 [isActive]。
     *
     * 心跳能力保留，是因为一旦将来打通通道，它能提供 scope 给不了的信息：
     * 「模块此刻真的在跑」而不只是「框架授权了」。当前 UI 不依赖它。
     */
    const val KEY_HEARTBEAT = "module_heartbeat"

    /** 心跳多久算「新鲜」（快手运行中应持续刷新；超过则视为未生效） */
    private const val HEARTBEAT_FRESH_MS = 90_000L

    /**
     * 写入心跳（**快手进程内调用**）。★ 2026-09 起**不再是激活判据**，仅作将来备用。
     *
     * 当前两个通道都不通（已实测定位，详见 [heartbeatAgeMs] 与 `CfhState.writeHb`）：
     * `rp` 在快手进程恒为 null；`/sdcard/Android/media/<模块包名>/` 快手无写权限。
     * 激活判定已改用框架下发的 `service.scope`（见 [isActive]）。
     *
     * @param pid 快手进程 pid，便于排障时确认是哪个进程在报活
     */
    fun writeHeartbeat(pid: Int) {
        val v = "$pid|${System.currentTimeMillis()}"
        // 通道 1：共享配置（binder，主通道）
        try { rp?.edit()?.putString(KEY_HEARTBEAT, v)?.apply() } catch (_: Throwable) {}
        // 通道 2：公共目录（备用，也便于 adb 直接看到内容做验证）
        //   Android/media/<pkg>/ 两边都能写（Prefs 的 slowkick.properties 就用这里）
        try {
            val f = java.io.File(
                "/sdcard/Android/media/io.github.angbang852.manjiao/module_heartbeat.txt"
            )
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            f.writeText(v)
        } catch (_: Throwable) {}
    }

    /**
     * 读取心跳年龄（毫秒）；无心跳返回 -1。
     *
     * ★ 2026-09 已降级为**可选参考**，不再是激活判据（主判据见 [isActive]）。
     *
     * 为什么降级 —— 两个通道在实测中都不通：
     * - 通道 2（本方法读的文件）：`/sdcard/Android/media/<模块包名>/` 属主是模块
     *   （`u0_a224`，组 `media_rw`，other 无权限），快手以 `u0_a1305` 运行不在该组，
     *   写入 EACCES。
     * - 通道 1（[rp] binder）：`rp` 只在 app 进程的 `onServiceBind` 里赋值，
     *   快手进程恒为 null。
     *
     * 保留此方法是因为一旦将来通道打通（例如模块持自身 `XposedModule` 引用、
     * 走 `getRemotePreferences` 写入），它能提供 scope 判据给不了的信息：
     * 「模块此刻**真的在跑**」而不只是「框架授权了」。
     * 当前 UI 不依赖它，读不到也不会导致任何显示异常。
     */
    fun heartbeatAgeMs(): Long {
        // 通道 1：共享配置（binder）
        try {
            val raw = rp?.getString(KEY_HEARTBEAT, null)
            if (!raw.isNullOrBlank()) {
                val ts = raw.substringAfter('|', "").toLongOrNull() ?: 0L
                if (ts > 0L) return System.currentTimeMillis() - ts
            }
        } catch (_: Throwable) {}
        // 通道 2：公共目录文件（两条通道都试，任一条通就算通 ——
        // 参考 toki 的做法：不纠结走哪条路，能读到就用）
        try {
            val f = java.io.File(
                "/sdcard/Android/media/io.github.angbang852.manjiao/module_heartbeat.txt"
            )
            if (f.exists() && f.length() in 1..100) {
                val ts = f.readText().trim().substringAfter('|', "").toLongOrNull() ?: 0L
                if (ts > 0L) {
                    // 用「文件修改时间」与「内容时间戳」取较新者：
                    // 某些 ROM 上 mtime 精度更高，两者结合更可靠
                    val byContent = System.currentTimeMillis() - ts
                    val byMtime = System.currentTimeMillis() - f.lastModified()
                    return minOf(byContent, byMtime).coerceAtLeast(0L)
                }
            }
        } catch (_: Throwable) {}
        return -1L
    }

    /** 心跳是否新鲜（= 模块确实在快手进程里活着） */
    fun isHeartbeatFresh(): Boolean {
        val age = heartbeatAgeMs()
        return age in 0..HEARTBEAT_FRESH_MS
    }

    // ==================== 模块状态查询（2026-09 app 信息面板用） ====================

    /**
     * 目标进程的模块加载状态 —— **比布尔值准确得多**。
     *
     * ★ 2026-09 修复「模块激活状态显示不准」：
     * 原先只有一个布尔判据 `isActive() = service != null`，它有两个问题：
     *
     * 1. **`service` 只代表「binder 绑定成功」，不等于「模块已注入宿主」**。
     *    实测：快手进程根本没在跑时依然能 bound ⇒ 界面显示「已激活」，
     *    而用户理解的「激活」是「模块正在起作用」，两者对不上，自然觉得不准。
     * 2. 丢掉了框架给出的**逐进程状态**。libxposed 的 `HookedTarget.getState()`
     *    明确区分四种情况，这正是排障时最需要的信息：
     *    - `UP_TO_DATE` 已注入且版本最新 —— 真正在生效
     *    - `STALE`      注入的是**旧版本**，需重启宿主进程才更新（常见坑）
     *    - `RELOADING`  正在重载
     *    - `FAILED`     注入失败 —— 模块代码有问题，需要看框架日志
     */
    enum class TargetState { NOT_RUNNING, UP_TO_DATE, STALE, RELOADING, FAILED, UNKNOWN }

    /** 快手主进程当前的模块加载状态 */
    fun targetState(): TargetState {
        val list = try { service?.runningTargets } catch (_: Throwable) { null } ?: return TargetState.NOT_RUNNING
        val t = list.firstOrNull {
            val n = it.processName
            n == KsClass.PKG || n == KsClass.PKG_NEBULA
        } ?: return TargetState.NOT_RUNNING
        return try {
            when (t.state.name) {
                "UP_TO_DATE" -> TargetState.UP_TO_DATE
                "STALE" -> TargetState.STALE
                "RELOADING" -> TargetState.RELOADING
                "FAILED" -> TargetState.FAILED
                else -> TargetState.UNKNOWN
            }
        } catch (_: Throwable) { TargetState.UNKNOWN }
    }

    /**
     * 模块是否已激活 —— 判据是「**框架把目标包纳入了本模块的作用域**」。
     *
     * ★ 这是 2026-09 参考 WeKit（github.com/Ujhhgtg/WeKit）后定下的正确答案。
     * WeKit 的 `HookStatus.kt` 只有 38 行，判据就一句：
     *
     * ```kotlin
     * xposedService?.scope?.contains(PackageNames.WECHAT) == true
     * ```
     *
     * 三个关键点，前四版我全踩错了：
     * 1. **判据是 scope，不是绑定状态**。`service != null` 只说明 binder 连上了；
     *    只有 `scope` 里出现目标包，才代表「框架确实会把本模块注入该进程」。
     *    这才是用户理解的「已激活」——即使目标进程当前没运行。
     * 2. **不需要同步查询**。`XposedServiceHelper` 只有 `registerListener`（javap 已确认），
     *    但**根本不需要查询** —— 状态放进 `MutableStateFlow`，`onServiceBind` 时赋值，
     *    UI 订阅即可。绑定是异步的，但「异步」不等于「不可知」。
     * 3. **没有中间态**。所以不会出现「正在检查…」卡死。绑定前 = 未激活，
     *    绑定后 = 按 scope 判定。两个状态都是确定的。
     *
     * 注意 `scope` 可能因框架而异：老框架可能不下发，此时退回
     * 「service 绑定 + 框架信息可读」作为近似判据（至少证明框架认识本模块）。
     */
    fun isActive(): Boolean = when {
        service == null -> false
        // 主判据：作用域里含目标包 ⇒ 框架已授权本模块注入该进程
        scope().any { it == KsClass.PKG || it == KsClass.PKG_NEBULA } -> true
        // 作用域非空但不含目标包 ⇒ 用户在 LSPosed 里没勾快手，仍未激活
        scope().isNotEmpty() -> false
        // 老框架不下发 scope，退回「service 绑定 + 框架信息可读」
        else -> frameworkInfo() != null
    }

    /** 框架信息（框架名 + 版本），未激活时返回 null */
    fun frameworkInfo(): Pair<String, String>? {
        val s = service ?: return null
        return try { s.frameworkName to s.frameworkVersion } catch (_: Throwable) { null }
    }

    /** 框架 API 版本（101 / 102），未激活时返回 0 */
    fun apiVersion(): Int = try { service?.apiVersion ?: 0 } catch (_: Throwable) { 0 }

    /**
     * 当前**正在运行**且被模块作用的目标列表（进程名，形如 `com.smile.gifmaker`）。
     *
     * 用于显示「快手是否在运行」——未运行时模块状态是「已激活但未生效」，
     * 和真正的「未激活」是两回事，界面上要区分开。
     *
     * 注意 libxposed 的 `HookedTarget` 暴露的是 `getProcessName()`（不是 packageName），
     * 子进程会带 `:` 后缀，这里过滤掉只保留主进程。
     */
    fun runningTargets(): List<String> = try {
        service?.runningTargets
            ?.mapNotNull { it.processName }
            ?.filter { !it.contains(':') }
            ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /** 模块作用域（用户在 LSPosed 里勾选的目标包） */
    fun scope(): List<String> = try {
        service?.scope ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    // ★ 目标进程专用：注册共享配置变更监听（替代 ACTION_UPDATE 广播推送到主路径）
    fun armTargetSync() {
        targetSyncArmed.set(true)
        val p = rp ?: return
        try {
            p.unregisterOnSharedPreferenceChangeListener(targetListener)
            p.registerOnSharedPreferenceChangeListener(targetListener)
            refreshFromService()
        } catch (_: Throwable) {}
    }

    private val targetListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
        try { refreshFromService() } catch (_: Throwable) {}
    }

    // 把共享配置全量灌入 Prefs 缓存（幂等，类型按值判定）
    fun refreshFromService() {
        val p = rp ?: return
        val all = try { p.all } catch (_: Throwable) { return }
        for ((k, v) in all) {
            try {
                when (v) {
                    is Boolean -> Prefs.applyRemote(k, v)
                    is Int -> Prefs.applyRemote(k, v)
                    is Long -> Prefs.applyRemote(k, v.toInt())
                    is String -> Prefs.applyRemote(k, v)
                    is Set<*> -> Prefs.applyRemote(k, v.filterIsInstance<String>().toSet())
                }
            } catch (_: Throwable) {}
        }
        ContentFilterHook.invalidateFilterCache()
        // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：与 Module.kt 的同一键口径保持一致
        Logger.quiet = Prefs.bool(Prefs.K_PERF_QUIET, false)
        Logger.diag = Prefs.bool(Prefs.K_DIAG, false)
        // ★ 审计开关随配置刷新同步（2026-09）：用户在 app 侧拨动「记录拦截证据」后，
        // 共享配置变更会打到这里；不同步的话快手进程内 CfhState 还停在旧值，
        // 直到宿主进程重启才生效。
        try {
            io.github.angbang852.manjiao.hook.CfhState.auditEnabled = Prefs.bool(Prefs.K_AUDIT_ON, false)
            // 旧默认 200 视为未配置 → 用新默认 10000（迁移逻辑见 Module.auditLimitFromPrefs）
            io.github.angbang852.manjiao.hook.CfhState.auditLimit =
                Prefs.int(Prefs.K_AUDIT_LIMIT, -1).let {
                    if (it <= 0 || it == 200) 10000 else it.coerceIn(20, 10000)
                }
        } catch (_: Throwable) {}
    }

    // ★ 写路径加性推送：任何进程的 setXxxSync 都顺带写一份共享配置（best-effort）
    fun push(type: String, key: String, value: Any?) {
        val p = rp ?: return
        try {
            val e = p.edit()
            when (type) {
                "bool" -> if (value is Boolean) e.putBoolean(key, value)
                "int" -> if (value is Int) e.putInt(key, value)
                "str" -> if (value is String) e.putString(key, value)
                "strset" -> if (value is Set<*>) e.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            e.apply()
        } catch (_: Throwable) {}
    }
}
