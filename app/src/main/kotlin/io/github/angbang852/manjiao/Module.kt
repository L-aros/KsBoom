package io.github.angbang852.manjiao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.data.SyncService
import io.github.angbang852.manjiao.hook.AntiAntiHook
import io.github.angbang852.manjiao.hook.CfhState
import io.github.angbang852.manjiao.hook.ContentFilterHook
import io.github.angbang852.manjiao.hook.GestureHook
import io.github.angbang852.manjiao.hook.GoldFloatHook
import io.github.angbang852.manjiao.hook.ImmersiveHook
import io.github.angbang852.manjiao.hook.PerfHook
import io.github.angbang852.manjiao.hook.PlaybackHook
import io.github.angbang852.manjiao.hook.PurifyHook

import io.github.angbang852.manjiao.hook.SharePanelHook
import io.github.angbang852.manjiao.hook.VideoDownloaderHook
import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class Module : XposedModule {
    private val init = AtomicBoolean(false)
    // ★ 性能优化-进程级一次初始化：快手插件化框架（nebula 等）会在主进程多个线程
    // 反复 new Application 并调 onCreate——不幂等的话 receiver 会重复注册十几遍、
    // 全部 hook 重复装载（ANR 根因之一，实测单次启动重复 13 次）
    private val onCreateDone = AtomicBoolean(false)

    constructor() { }

    // ★ libxposed 的 ModuleLoadedParam.processName 是权威进程名（实测三进程各自正确）；
    // 而 ctx.applicationInfo.processName 在快手子进程里错误地返回主包名（不带 :messagesdk），
    // 导致"仅主进程注入"判定失效——子进程照样全量注入（本次实测抓到）
    private var realProcName: String? = null

    /**
     * 诊断落盘（2026-09 排障用）：**不经过任何日志门控与去重**。
     *
     * 为什么需要它：排查「打开模块菜单没反应」时，`Logger.once` 出现了自相矛盾的现象 ——
     * 位置更靠后的 `DLHOOK installed`（once）打出来了，而更靠前的注册埋点（同为 once）
     * 没有输出。怀疑 LSPosed 多 ClassLoader 场景下 `Logger.onceTags` 静态去重表
     * 被跨加载器污染。写文件是唯一不受影响的观测手段。
     *
     * 路径与 `AuditMirror` 同域（模块媒体目录），adb 可直接读取核对。
     */
    private fun diagWrite(msg: String) {
        // 路径选择：**不能用** `/sdcard/Android/media/<模块包名>/` ——
        // 该目录属主是模块（u0_aXXX，组 media_rw，other 无权限），而本代码跑在
        // **快手进程**（另一个 uid）里，写入必然 EACCES（与心跳法踩的是同一个坑）。
        // 用 `/sdcard/Download/ManJiao/.sys/` —— AuditMirror 已实测该目录两进程都可写。
        try {
            val f = java.io.File("/sdcard/Download/ManJiao/.sys", "boot_diag.txt")
            f.parentFile?.mkdirs()
            // ★★ 上限 + 轮转（2026-10 收尾）：原先这里是**纯 append、无上限、无轮转**，
            //   而它每次冷启写 1~2 行 ⇒ 随冷启次数**单调增长**，且落在 world-writable
            //   公共目录里（任意 App 可读）。改为走 [Logger.appendCapped] ——
            //   **复用 Logger 既有的「保留一代 .1」滚动约定**，不另造一套轮转实现。
            //
            //   上限取值依据：实测 734 行 / 18.3KB（≈25 字节/行，含「recv=OK」与
            //   「RECV OPEN_MENU」两类）。取 **256KB** ⇒ 256KB ÷ 25B ≈ 10400 行
            //   ≈ **5000+ 次冷启**的存量（本文件就是为排障保留的，这个量级足够）。
            //   之所以不取 8MiB（与 evidence 同级）：它只记「广播到没到 / 接收器注册
            //   成没成」，诊断价值集中在最近几次冷启，不需要 8MiB 的历史。
            //   轮转保留一代 ⇒ 稳态峰值 2 × 256KB = 512KB。
            //
            //   注：本方法**不经过任何日志门控**（这是它的设计目的，见上方 KDoc），
            //   本次只补上限，**不动门控语义**。
            Logger.appendCapped(f, msg + "\n", BOOT_DIAG_MAX_BYTES)
        } catch (_: Throwable) {}
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Logger.init(this)
        // ★ XposedService 收编（2026-09）：两进程统一经 Service 拿共享配置（广播兜底）
        try { SyncService.init() } catch (_: Throwable) {}
        realProcName = param.processName
        Logger.d("onModuleLoaded process=" + param.processName)
    }

    companion object {
        /**
         * `boot_diag.txt` 体积上限（256KB）。
         *
         * 依据：实测 734 行 / 18.3KB ≈ 25 字节/行 ⇒ 256KB ≈ 10400 行 ≈ 5000+ 次冷启存量；
         * 轮转保留一代（`.1`）⇒ 稳态峰值 512KB。详见 [diagWrite] 的注释。
         */
        private const val BOOT_DIAG_MAX_BYTES = 256L * 1024

        /** app 侧「清空拦截记录」→ 快手进程执行（签名级权限保护，第三方不可伪造） */
        const val ACTION_AUDIT_CLEAR = "io.github.angbang852.manjiao.AUDIT_CLEAR"

        /** app 侧「重置统计」→ 快手进程执行 */
        const val ACTION_AUDIT_RESET = "io.github.angbang852.manjiao.AUDIT_RESET"

        // ★ ACTION_AUDIT_ALLOW（标为误拦）已随该功能移除（2026-09）。

        /**
         * app 侧「打开模块菜单」→ 快手进程弹出菜单（2026-09）。
         *
         * ★ 为什么必须走广播，而不是 app 直接弹：
         * 模块菜单是一个 `Dialog`，必须**依附在快手的 Activity 上**才能显示在快手界面里。
         * app 进程与快手进程是两个独立进程，app 无法往快手的窗口里塞 Dialog ——
         * 这不是没实现，是 Android 的进程/窗口模型决定的。
         *
         * 所以正确做法是：app 发这条广播 → 快手进程内本接收器收到 →
         * 在快手进程里用上下文调 `MainMenuDialog.show(act)`。
         * 走的是与「清空记录/重置统计」完全相同的**签名级权限**通道
         * （`Prefs.PERM_SYNC`，protectionLevel=signature），第三方 app 无法伪造。
         */
        const val ACTION_OPEN_MENU = "io.github.angbang852.manjiao.OPEN_MENU"

        /**
         * 读审计记录条数上限（2026-09 随「7 天保留」策略放宽）。
         *
         * ★ 存量迁移：UI 从未提供此设置项的控件（只能 adb 写），
         * 旧默认 200 几乎必然是「从未配置」而非「用户故意要 200」。
         * 用户实测「200 条不出 1 小时就能累计到」—— 因此读到旧默认值时
         * 直接采用新默认（10000），只有 adb 显式写过其它值才尊重该值。
         */
        fun auditLimitFromPrefs(): Int {
            val v = Prefs.int(Prefs.K_AUDIT_LIMIT, -1)
            return if (v <= 0 || v == 200) 10000 else v.coerceIn(20, 10000)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        if (pkg != KsClass.PKG && pkg != KsClass.PKG_NEBULA) return
        if (!init.compareAndSet(false, true)) return

        Logger.d("onPackageLoaded $pkg")
        val cl = try { param.defaultClassLoader } catch (_: Throwable) { null }
        if (cl == null) { Logger.d("no classloader"); return }

        // ★ 版本自适应探测（2026-09）：**必须最早执行**——后续所有 hook 的路径选择
        // 都依赖 KsVersion.tier；太晚则首个 hook 已在无档位信息下装完。
        // 探测本身全链路容错：失败时降级为「结构指纹推断」，再失败则沿用默认档位
        // （= 既有实测路径 14.7.40 的行为），**不会因探测失败而少装任何 hook**。
        try { io.github.angbang852.manjiao.adapt.KsVersion.probe(cl) } catch (_: Throwable) {}
        // ★ DexKit 预热（2026-09 真机实测修正）：native 库首次加载约 1s，
        //   而主线程上的结构发现在装钩期就会用到它。提前丢到后台加载，
        //   避免「解析时 .so 还没就绪 → 结构发现失败 → 目标 MISS」
        //   （实测 14.8.20.50218 上 knhb 就是这样漏掉的）。
        try { io.github.angbang852.manjiao.adapt.KsResolve.warmUpDexKit() } catch (_: Throwable) {}

        val appCls = Class.forName("android.app.Application", false, cl)
        hook(appCls.getDeclaredMethod("onCreate"))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("app.onCreate")
            .intercept { chain ->
                chain.proceed()
                try {
                    val ctx = chain.thisObject as Context
                    // ★ 版本号补齐（2026-09 真机实测）：onPackageLoaded 早期探测时
                    //   Context 可能还没就绪 → 版本号读到 null、档位退化为指纹推断。
                    //   此处 Context 一定可用，补一次让日志与设置页拿到真实版本号。
                    //   注意只补版本号、**不重算档位**（档位已在首次探测时定案，
                    //   中途改档会让已装好的 hook 与档位不一致）。
                    try { io.github.angbang852.manjiao.adapt.KsVersion.refine(ctx) } catch (_: Throwable) {}
                    Prefs.init(ctx)
                    // ★★★ v13.80 池存档恢复**提前到 Application.onCreate**（真机症状专治）。
                    //
                    //   症状：冷启动进精选页先显示「没有网络」，**点一下刷新**过一会才有视频。
                    //   实测时序（上一轮）：
                    //     [POOLGSON]  ★已捕获快手自己的 Gson        ...670836
                    //     [RESPFILL]  ★空响应但池内无可用条目 池=0   ...672016  ← 首屏请求
                    //     [POOLSTORE] ★重启恢复 条目=1 池=1          ...672026  ← 只晚 10ms
                    //   根因：恢复原来是挂在 `ensurePoolSupply()` 上的，而那个函数**正是被
                    //   首次 getItems 触发**的 —— 让「恢复」被它要伺候的那次请求叫醒，
                    //   必然晚一拍，首屏就判「无网络」。
                    //   放到 onCreate 就不会再有这个竞态：恢复在**任何网络请求之前**启动。
                    try { io.github.angbang852.manjiao.hook.CfhPoolStore.loadOnce() } catch (_: Throwable) {}
                    // ★★ 白名单模式总开关（2026-09-26 用户定稿「判正常才放行」）。
                    //
                    //   语义：**默认关闭**（`flt_whitelist` 未设或 false ⇒ 保持既有黑名单行为）。
                    //   开启后 `CfhClean.filterWhitelist` 在 L1 生效 ——
                    //   只放行明确判为干净的条目，判脏直接挡下，字段未齐的暂扣等齐
                    //   （3 秒超时兜底，避免「无更多作品」）。
                    //
                    //   沿用 `adapt_on` 的既有范式（同一位置、同一风格），便于统一排查。
                    try {
                        io.github.angbang852.manjiao.hook.CfhState.whiteListEnabled =
                            Prefs.bool(Prefs.K_FLT_WHITELIST, false)
                        if (io.github.angbang852.manjiao.hook.CfhState.whiteListEnabled) {
                            Logger.always("WHITE enabled: L1 白名单生效（判正常才放行）")
                        }
                    } catch (_: Throwable) {}
                    // ★ 自适应层总开关（2026-09-23，默认开）：故障隔离用。
                    //   写 adapt_on=false → 解析全部退回候选名直查（等价改动前行为）。
                    try {
                        // ★ 默认 true→false（2026-09-30 用户定稿：「全关肯定是都关啊，用的人按需开启啊。」）
                        //   false ⇒ KsHookKit.enabled=false，自适应层关闭，解析退回候选名直查（等价改动前行为）。
                        //   只改默认值，判定/解析实现未动。
                        io.github.angbang852.manjiao.adapt.KsHookKit.enabled = Prefs.bool("adapt_on", false)
                    } catch (_: Throwable) {}
                    // ★ XposedService 收编：共享配置变更监听 + 首次灌入（替代广播推送主路径）
                    try { SyncService.armTargetSync() } catch (_: Throwable) {}
                    // 性能优化-日志静默：刷屏级诊断日志的字符串拼接+logcat 写入都在调用线程
                    Logger.quiet = Prefs.bool(Prefs.K_PERF_QUIET, false)
                    // ★ 诊断日志独立开关（S2）：重反射诊断块只随 diag 开
                    Logger.diag = Prefs.bool(Prefs.K_DIAG, false)
                    // ★ 拦截审计（功能 4，2026-09）：从配置恢复（默认关）
                    CfhState.auditEnabled = Prefs.bool(Prefs.K_AUDIT_ON, false)
                    CfhState.auditLimit = auditLimitFromPrefs()
                    // ★ 跨进程镜像（2026-09 修复「app 里拦截统计/记录没有信息」）：
                    // 审计状态活在快手进程，设置页在模块进程读不到，需镜像到磁盘。
                    // 存到 Android/media/<模块包名>/ —— 与 Prefs 的 slowkick.properties
                    // 同一处：两进程都能读写，且不受 scoped storage 限制。
                    CfhState.mirrorDir = Prefs.MEDIA_DIR_PUBLIC
                    Logger.always("audit mirror dir: ${CfhState.mirrorDir}")
                    // ★ 先从磁盘镜像恢复历史（2026-09 修复「拦截统计只剩最近的」）：
                    //   必须在 startPeriodicPublish 之前 —— 否则 20s 后的首次发布
                    //   会用空的内存状态覆盖镜像，历史记录就此丢失。
                    //   实现见 CfhState.restoreFromMirrorIfEmpty（仅内存为空时恢复，
                    //   镜像超 7 天不恢复）。
                    //
                    // ★★ 2026-09-23 修复「更新模块后统计丢失」：**去掉 auditEnabled 前置条件**。
                    //   原实现只在「记录拦截证据」打开时才恢复，于是关掉该开关的用户
                    //   重启后统计（总数/规则分布/分时桶）全部归零 —— 而项目自身的设计
                    //   是「时间维度统计即使不逐条留证据也应可用」（见 CfhDecide.hit 注释）。
                    //   恢复本身只读一次文件（≤4MB 上限），无开关也应执行。
                    // ★★★ 修复（2026-09-29 代码审核，③、⑤ **两份独立报告印证**）：
                    //   子进程护栏原本在下面 :226，**晚于**这几行「主进程专属」动作 ⇒
                    //   4 个进程全都执行了「恢复镜像 / 起周期发布器 / 建目录 / 自检」：
                    //     · 每进程各 spawn 一个 mirrorExecutor，**每 20 秒各写一次同一个
                    //       audit_mirror.json** ⇒ 4 倍写盘 + 多写者互相覆盖
                    //       （「统计有时候对不上/归零」这类现象的温床）
                    //     · 每进程各读一次 ≤4MB 镜像、各建一遍目录
                    //   ⇒ 这里**提前**判一次「是不是子进程」，是则整块跳过。
                    //   注意：子进程后面仍必须装 PurifyHook（见 :230 附近），
                    //   所以这里**不能提前 return**，只能把这块包起来。
                    val subProcEarly = Prefs.bool(Prefs.K_PERF_MAINPROC, false) &&
                        (realProcName
                            ?: try { ctx.applicationInfo.processName } catch (_: Throwable) { null })
                            ?.contains(':') == true
                    if (!subProcEarly) {
                        try { CfhState.restoreFromMirrorIfEmpty() } catch (_: Throwable) {}
                        // ★ 周期发布审计快照（2026-09）：**无命中时也要发**。
                        // 否则 app 侧永远收不到任何快照，用户看到「app 里没数据」时
                        // 分不清是「确实没命中」还是「通道坏了」（实测踩过这个坑：
                        // hitTotal=0 导致 publish 从未触发，app 侧显示「键不存在」）。
                        try { CfhState.startPeriodicPublish() } catch (_: Throwable) {}
                        // 模块专属目录建齐（2026-09）：让用户在文件管理器里
                        // 一眼看到 Download/ManJiao/{视频,音频,图集,封面,其他}
                        try {
                            io.github.angbang852.manjiao.data.StorageDirs.ensureAll(
                                Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH)
                            )
                        } catch (_: Throwable) {}
                        // 自检一次：写入侧看到什么权限，出问题时靠这行定位
                        io.github.angbang852.manjiao.data.AuditMirror.diagnose("ks")
                    }
                    // ★ 性能优化-仅主进程注入：快手的 push_v3/messagesdk/kwv_sandboxed 等子进程
                    // 不装任何 hook、不注册 receiver、不发 query——子进程注入只会带来
                    // 启动变慢 + 内存浪费 + 广播风暴 + 主线程阻塞（ANR 根因之一）
                    // 进程名优先取 onModuleLoaded 的权威值；ctx.applicationInfo.processName
                    // 在快手子进程实测错误返回主包名（不带 :messagesdk），不可作判定依据
                    val procName = realProcName
                        ?: try { ctx.applicationInfo.processName } catch (_: Throwable) { null }
                    // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）。
                    //   false ⇒ 子进程（push_v3/messagesdk/kwv_sandboxed）也照常装全部 hook，
                    //   等于恢复「仅主进程注入」优化之前的行为（启动更慢/内存更高，见上方 :234 注释）。
                    Logger.once("boot.proc", "proc=$procName mainproc=" + Prefs.bool(Prefs.K_PERF_MAINPROC, false))
                    if (Prefs.bool(Prefs.K_PERF_MAINPROC, false) && procName != null && procName.contains(':')) {
                        Logger.once("boot.subproc", "skip sub process: $procName")
                        // 子进程仍装载 PurifyHook：主拦截层已移到**调用侧**
                        // （主进程 ContextImpl.bindService/startService，见 PurifyHook.hookBindCaller），
                        // 这里保留为第二道防线（Service.onCreate + stopSelf 兜底）。
                        Logger.safe("purify") { PurifyHook.hook(this, cl, ctx) }
                        return@intercept null
                    }
                    if (!onCreateDone.compareAndSet(false, true)) {
                        // ★ 用 once 而非 always：Logger.always 受 quiet 门控（见 Logger.kt:67
                        //   `fun always(msg) { if (quiet) return; ... }`），而 quiet 默认 true，
                        //   所以排障期用 always 打的日志全部被静默 —— 这是我这次排查绕远路的根因。
                        //   once 不受 quiet 影响，且每进程只打一次。
                        Logger.once("boot.reinit2", "onCreate repeat, skip re-init (接收器不会注册)")
                        return@intercept null
                    }
                    Logger.once("boot.firstinit2", "first init: 继续注册接收器与 hooks")
                    Logger.d("prefs ready")
                    val prefsReceiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, i: Intent) {
                            try {
                                // ★ 诊断（2026-09）：接收器入口落盘，确认广播到底有没有到。
                                if (i.action == ACTION_OPEN_MENU) {
                                    diagWrite("RECV OPEN_MENU pid=" + android.os.Process.myPid() +
                                        " tracked=" + io.github.angbang852.manjiao.hook.CfhState.tracked)
                                }
                                if (i.action == Prefs.ACTION_PULL) {
                                    try { Prefs.replyPull(c) } catch (_: Throwable) {}
                                    return
                                }
                                // ★ 审计清空/重置（2026-09）：app 侧点了「清空记录 / 重置统计」，
                                // 本进程才是真正持有数据的一方，必须在这里执行并把镜像刷掉 ——
                                // 否则下一轮 mirrorSoon() 会把旧数据原样写回去。
                                if (i.action == ACTION_AUDIT_CLEAR) {
                                    try { ContentFilterHook.clearAuditRecords() } catch (_: Throwable) {}
                                    try { CfhState.mirrorNow() } catch (_: Throwable) {}
                                    return
                                }
                                if (i.action == ACTION_AUDIT_RESET) {
                                    try { ContentFilterHook.resetStatsBaseline() } catch (_: Throwable) {}
                                    try { ContentFilterHook.clearHourly() } catch (_: Throwable) {}
                                    try { CfhState.mirrorNow() } catch (_: Throwable) {}
                                    return
                                }
                                // ★★★ 钥匙索取（2026-09-30 用户规格 ②）
                                //
                                //   模块 app 侧需要解密数据文件时（审计/统计查看），向**本进程**索取钥匙
                                //   —— 本进程既是钥匙的生成者也是持有者，且永远是这些文件的读写方。
                                //
                                //   为什么复用这条通道而不发明新通道（用户明确要求）：
                                //   接收器是以 Prefs.PERM_SYNC（protectionLevel=signature）注册的，
                                //   发送方必须持有该权限；只有同证书的本模块 app 在 manifest 里
                                //   声明了 uses-permission ⇒ **第三方 app 发不进来**。
                                //   回包侧再由 KeyVault 用 setPackage(OWN_PKG) 定向 ⇒ 也收不到。
                                //   与 AuditBridge.ACTION_REQ 是同一套形状，零新增机制。
                                //
                                //   为什么钥匙不需要用户打开模块 app：它在**本进程首次运行时**
                                //   就生成并存进快手私有目录（KeyVault），与 app 是否运行无关。
                                if (i.action == io.github.angbang852.manjiao.util.KeyVault.ACTION_KEY_REQ) {
                                    try {
                                        io.github.angbang852.manjiao.util.KeyVault.replyKeyRequest(c)
                                    } catch (_: Throwable) {}
                                    return
                                }
                                // ★ 审计快照请求（A 通道，2026-09）：app 侧要数据时发广播，
                                // 本进程组装 JSON 回包。B 通道（共享配置）为主，这是兜底。
                                if (i.action == io.github.angbang852.manjiao.data.AuditBridge.ACTION_REQ) {
                                    try {
                                        val json = CfhState.snapshotJson()
                                        if (json.isNotEmpty()) {
                                            io.github.angbang852.manjiao.data.AuditBridge.reply(c, json, null)
                                            // 顺手把 B 通道也刷新一次，下次读取零等待
                                            io.github.angbang852.manjiao.data.AuditBridge.publish(json)
                                        }
                                    } catch (_: Throwable) {}
                                    return
                                }
                                // ★「标为误拦」广播分支已随该功能移除（2026-09）。
                                // ★ app 侧「打开模块菜单」（2026-09）：本进程才能弹菜单。
                                // 菜单是 Dialog，必须依附快手的 Activity —— app 进程跨不过来，
                                // 所以由 app 发广播，这里用当前前台 Activity 来 show。
                                if (i.action == ACTION_OPEN_MENU) {
                                    // ★ 快手冷启动到首页 onResume 需要数秒，期间 CfhState.tracked
                                    //   仍是 null。这里轮询等待（最多 ~12s），拿到 Activity 就弹。
                                    //   （2026-09 实测：只等 1.2s 不够，快手冷启动慢于该窗口）
                                    val attempts = intArrayOf(0)
                                    val delays = longArrayOf(400, 800, 1500, 2500, 3500, 5000)
                                    val tryShow = object : Runnable {
                                        override fun run() {
                                            val act = io.github.angbang852.manjiao.hook.CfhState.tracked
                                            if (act != null && !act.isFinishing && !act.isDestroyed) {
                                                try {
                                                    io.github.angbang852.manjiao.ui.MainMenuDialog.show(act)
                                                    Logger.once("openmenu.ok", "open menu: shown (attempt ${attempts[0]})")
                                                } catch (t: Throwable) {
                                                    Logger.once("openmenu.err", "open menu failed: ${t.message}")
                                                }
                                                return
                                            }
                                            if (attempts[0] >= delays.size) {
                                                Logger.once("openmenu.timeout", "open menu: no activity after retries")
                                                return
                                            }
                                            val d = delays[attempts[0]]
                                            attempts[0]++
                                            CfhState.handler.postDelayed(this, d)
                                        }
                                    }
                                    CfhState.handler.post(tryShow)
                                    return
                                }
                                val type = i.getStringExtra("type") ?: return
                                val key = i.getStringExtra("key") ?: return
                                when (type) {
                                    "bool" -> Prefs.applyRemote(key, i.getBooleanExtra("value", false))
                                    "strset" -> {
                                        val arr = try { i.getStringArrayExtra("value") } catch (_: Throwable) { null }
                                        Prefs.applyRemote(key, (arr ?: emptyArray()).toSet())
                                    }
                                    "int" -> Prefs.applyRemote(key, i.getIntExtra("value", 0))
                                    "str" -> Prefs.applyRemote(key, i.getStringExtra("value") ?: "")
                                }
                                // 配置变化 → 过滤判定缓存失效（下次判定走全量反射并重新缓存）
                                if (key.startsWith("flt_") || key.startsWith("perf_")) ContentFilterHook.invalidateFilterCache()
                                Logger.quiet = Prefs.bool(Prefs.K_PERF_QUIET, false)
                                Logger.diag = Prefs.bool(Prefs.K_DIAG, false)
                                // ★ 拦截审计开关（功能 4，2026-09）：运行时生效，
                                // 用户拨开关即刻开始/停止记录，无需重启快手。
                                // 白名单变化时同样要清判定缓存，让被标记误拦的项立刻恢复
                                if (key == Prefs.K_AUDIT_ON) {
                                    CfhState.auditEnabled = Prefs.bool(Prefs.K_AUDIT_ON, false)
                                    // ★ 开关打开时补启发布线程（2026-09）：
                                    // startPeriodicPublish 现在「审计关则不启线程」以省资源，
                                    // 因此这里热开启后必须补一次，否则 app 侧收不到快照。
                                    // 该方法是幂等的（publishThread != null 直接返回）。
                                    if (CfhState.auditEnabled) {
                                        try { CfhState.startPeriodicPublish() } catch (_: Throwable) {}
                                    }
                                }
                                if (key == Prefs.K_AUDIT_LIMIT) CfhState.auditLimit = auditLimitFromPrefs()
                                Logger.d("prefs sync $type $key")
                            } catch (t: Throwable) { Logger.d("prefs recv fail: ${t.message}") }
                        }
                    }
                    // ★ API 24/25 无带 flags 的 registerReceiver 重载（API 26+ 才有）：
                    // 原直接调用会 NoSuchMethodError 被外层 catch，导致全部 hook 静默不装
                    val prefsFilter = IntentFilter(Prefs.ACTION_UPDATE).apply {
                        addAction(Prefs.ACTION_PULL)
                        // ★ 审计清空/重置（2026-09）：这两个 action 同样只接受本模块发来的广播
                        // （受 PERM_SYNC signature 权限保护），第三方无法伪造清空用户数据
                        addAction(ACTION_AUDIT_CLEAR)
                        addAction(ACTION_AUDIT_RESET)
                        // ★ app 侧「打开模块菜单」（2026-09）：同样受 PERM_SYNC 签名权限保护，
                        // 第三方 app 无法伪造「让快手弹模块菜单」
                        addAction(ACTION_OPEN_MENU)
                        addAction(io.github.angbang852.manjiao.data.AuditBridge.ACTION_REQ)
                        // ★ 钥匙索取（2026-09-30 规格 ②）：同样受 PERM_SYNC 签名权限保护，
                        // 第三方 app 发不出这个广播，也就拿不到解密钥匙
                        addAction(io.github.angbang852.manjiao.util.KeyVault.ACTION_KEY_REQ)
                    }
                    // ★ 诊断（2026-09）：注册结果同时写文件。
                    //   为什么不用 Logger.once：实测出现「DLHOOK installed（once，在更后面）
                    //   打出来了、而这行 once 没打」的矛盾，怀疑 onceTags 去重表在
                    //   LSPosed 多 ClassLoader 场景下被跨进程/跨加载器污染。
                    //   写文件不经过任何日志门控与去重，是唯一无歧义的手段。
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            ctx.registerReceiver(prefsReceiver, prefsFilter, Prefs.PERM_SYNC, null, Context.RECEIVER_EXPORTED)
                        } else {
                            // ★ API 24/25 没有 flags 重载，但 (receiver, filter, permission, scheduler)
                            // 四参重载自 API 1 就存在——原 else 分支漏传 PERM_SYNC，低版本上任何本机
                            // app 都能伪造 ACTION_UPDATE 向快手进程注入配置（P1 安全）
                            ctx.registerReceiver(prefsReceiver, prefsFilter, Prefs.PERM_SYNC, null)
                        }
                        diagWrite("recv=OK sdk=" + android.os.Build.VERSION.SDK_INT +
                            " pid=" + android.os.Process.myPid())
                    } catch (t: Throwable) {
                        diagWrite("recv=FAIL $t")
                    }
                    Logger.safe("anti") { AntiAntiHook.hook(this, cl) }
                    Logger.safe("dl") { VideoDownloaderHook.hook(this, cl) }
                    Logger.safe("share") { SharePanelHook.hook(this, cl) }
                    Logger.safe("imm") { ImmersiveHook.hook(this, cl) }
                    Logger.safe("gold") { GoldFloatHook.hook(this, cl) }
                    Logger.safe("flt") { ContentFilterHook.hook(this, cl) }
                    Logger.safe("gs") { GestureHook.hook(this, cl) }
                    Logger.safe("pb") { PlaybackHook.hook(this, cl) }

                    Logger.safe("perf") { PerfHook.hook(this, cl) }
                    Logger.safe("purify") { PurifyHook.hook(this, cl, ctx) }
                    // ★ E1 自检摘要：各 hook 的安装成败由各自 always 级标记输出
                    //（DLHOOK/PlaybackHook: hooked 等），此行确认主进程注入完成
                    Logger.once("boot.installed", "INSTALLED: anti/dl/share/imm/gold/flt/gs/pb/perf/purify")
                    // ★ 版本自适应报告（2026-09）：一行摘要 + 每条失败项的**具体原因**
                    //（类未找到 / 方法漂移 / 装钩异常）。用户报「某版本某功能失效」时，
                    // 这一段直接给出定位，不必再靠猜是哪一层断的。
                    try { io.github.angbang852.manjiao.adapt.KsHookKit.report() } catch (_: Throwable) {}
                } catch (t: Throwable) {
                    // ★ 2026-09：改 once 级别（不受 quiet 门控）。
                    // 原用 Logger.d，quiet 模式下被静默 ⇒ 「主进程只走到 onPackageLoaded
                    // 就没了下文」这种故障完全无痕，排查时看不见。
                    Logger.once("boot.hookfail", "onCreate hook FAILED: $t")
                }
                null
            }
    }
}
