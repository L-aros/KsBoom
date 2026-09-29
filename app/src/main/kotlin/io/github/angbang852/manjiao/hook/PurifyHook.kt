package io.github.angbang852.manjiao.hook

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.api.XposedInterface

object PurifyHook {
    private const val PUSH_SERVICE = "com.yxcorp.gifshow.pushv3bridge.MatrixPushV3Service"
    private const val WEBVIEW_SERVICE = "com.kuaishou.webkit.process.SandboxedProcessService0"
    private const val LOG_PROVIDER = "com.yxcorp.gifshow.log.service.ConanLogContentProvider"
    // ★ 第三个目标服务：`:messagesdk` 进程的持有者。原代码只认 push/webview 两个，
    //   漏了它 ⇒ 即便 push/webview 拦成功，`:messagesdk` 仍会被主进程 bind 起来。
    private const val MSG_SERVICE = "com.kwai.chat.kwailink.service.NewKwaiLinkService"

    // ★★★ 第四个目标：`:push_v3` 进程的**真实**持有者（2026-09-30 实测根因，不是推测）。
    //
    //   前三个目标（MatrixPushV3Service / SandboxedProcessService0 / NewKwaiLinkService）
    //   被拦下后 `:push_v3` **仍然存活**，因为它根本不是靠 MatrixPushV3Service 拉起的：
    //   ```
    //   04:37:14.580 ActivityManager: Start proc 22214:com.smile.gifmaker:push_v3/u0a1305
    //     for service {com.smile.gifmaker/com.yxcorp.gifshow.v2.manager.cache.MediaCacheRequestService}
    //   ```
    //   `dumpsys activity services` 亦印证——`:push_v3` 的 ServiceRecord 只有这一条，
    //   且主进程持有它的 ConnectionRecord（flags=0x1）：
    //   ```
    //   * ServiceRecord{82738c3 .../com.yxcorp.gifshow.v2.manager.cache.MediaCacheRequestService c:com.smile.gifmaker}
    //     processName=com.smile.gifmaker:push_v3
    //     * Client AppBindRecord{b7ff500 ProcessRecord{c5c8552 21571:com.smile.gifmaker/u0a1305}}
    //   ```
    //   ⇒ 名字里的 "MediaCache" 与 push 毫无关系，极易被漏掉：**进程名 ≠ 服务名**。
    private const val PUSH_PROC_SERVICE = "com.yxcorp.gifshow.v2.manager.cache.MediaCacheRequestService"

    /** 目标进程名（用于 PackageManager 反查该进程的所有服务持有者）。 */
    private const val PUSH_PROC_NAME = ":push_v3"

    private var blockDiag = 0

    /**
     * ★★★ `:push_v3` 这类进程的**完整持有者清单**（一次性从 PackageManager 解析并缓存）。
     *
     * ## 为什么不能再写死类名（本次踩坑的根本教训）
     *
     * 写死服务名的策略连续两轮漏拦，因为**同一个进程可由多个互不相干的服务持有**，
     * 且服务名与进程名毫无字面关系：
     *   · `:push_v3`          ← MediaCacheRequestService（"缓存"，名字里没有 push）
     *   · `:kwv_sandboxed_p0` ← SandboxedProcessService0
     *   · `:messagesdk`       ← NewKwaiLinkService
     * 手工枚举永远追不上快手改版。这里改为**声明侧反查**：扫描本包所有
     * `<service android:process=":push_v3">`（含 `android:isolatedProcess`），
     * 得到"凡是启动/绑定这些服务，就会拉起该进程"的完整集合。
     *
     * 只在**首次调用时**解析一次（O(服务数)，毫秒级，有缓存），
     * 之后每次 bind/start 只是一次 HashSet 命中判断，无额外开销。
     */
    @Volatile
    private var pushProcServices: Set<String>? = null

    private fun pushProcServicesOf(ctx: Context?): Set<String> {
        pushProcServices?.let { return it }
        val out = HashSet<String>()
        out.add(PUSH_PROC_SERVICE) // ★ 实测确认的持有者：即使 PackageManager 查询失败也必须拦住
        try {
            if (ctx != null) {
                val pm = ctx.packageManager
                val flags = PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
                val infos = pm.getPackageInfo(ctx.packageName, flags)?.services
                if (infos != null) {
                    for (si in infos) {
                        val proc = si.processName ?: continue
                        // 只认**显式子进程**（":push_v3"）；裸主包名不受影响
                        if (proc == PUSH_PROC_NAME) out.add(si.name)
                    }
                }
            }
        } catch (_: Throwable) {}
        pushProcServices = out
        Logger.once("purify.pushproc.svc", "push_v3 holder services=${out.size}")
        return out
    }

    fun hook(xp: XposedInterface, cl: ClassLoader, ctx: Context? = null) {
        // ★ 先做一次声明侧反查：把"会拉起 :push_v3 的服务"全部解析出来并留痕。
        //   放在最前面，确保即使后续 hook 失败，日志里也能看到解析结果。
        try { pushProcServicesOf(ctx) } catch (_: Throwable) {}
        hookServiceCreate(xp, ctx)
        hookBindCaller(xp, ctx)
        hookLogProvider(xp, cl)
        Logger.d("PurifyHook installed")
    }

    /**
     * 开关判定（**调用侧与宿主侧共用同一张表**，避免两处规则漂移）。
     *
     * 返回 null = 不拦；否则返回可读的拦截标签，直接进日志。
     * 用完整类名匹配：快手的这些 Service 均以全限定名声明，无需前缀模糊。
     */
    private fun blockTagFor(className: String?, ctx: Context? = null): String? = when {
        className == null -> null
        Prefs.bool(Prefs.K_PURIFY_PUSH, false) && className == PUSH_SERVICE -> "push"
        Prefs.bool(Prefs.K_PURIFY_WEBVIEW, false) && className == WEBVIEW_SERVICE -> "webview"
        Prefs.bool(Prefs.K_PURIFY_PUSH, false) && className == MSG_SERVICE -> "msg"
        // ★ `:push_v3` 的真实持有者。跟随 purify_push（推送开关）——该进程是推送链路，
        //   且实测就是它把 371MB 的 push_v3 进程拉起来的。
        //   声明侧反查集合兜住"快手改版换服务名"的情况；写死的那条保证查询失败时仍生效。
        Prefs.bool(Prefs.K_PURIFY_PUSH, false) &&
            (className == PUSH_PROC_SERVICE || pushProcServicesOf(ctx).contains(className)) -> "push_v3"
        else -> null
    }

    /** 从 bind/start 的 Intent 里取组件类名；Intent 只带 action 时返回 null（交给宿主侧兜底）。 */
    private fun compOf(intent: Intent?): String? = try {
        intent?.component?.className
    } catch (_: Throwable) {
        null
    }

    /**
     * ★★★ 真正的拦截层：**调用侧（主进程）** 的 `bindService` / `startService`。
     *
     * ## 为什么必须在这一层拦（实测根因，不是推测）
     *
     * 改前 `hookServiceCreate` 只在**被绑进程内**拦 `Service.onCreate` + `stopSelf()`。
     * 实测 `dumpsys activity services` 三个目标服务全部存活，且都带活跃绑定：
     * ```
     * MatrixPushV3Service      processName=:push_v3           hasBound=true
     *   Client AppBindRecord{... ProcessRecord{... 23630:com.smile.gifmaker}}   ← 主进程持有
     * SandboxedProcessService0 processName=:kwv_sandboxed_p0  hasBound=true
     *   Client AppBindRecord{... ProcessRecord{... 23630:com.smile.gifmaker}}   ← 主进程持有
     * NewKwaiLinkService       processName=:messagesdk        hasBound=true
     *   Client AppBindRecord{... 23630} + {24214}
     * ```
     * ⇒ **`stopSelf()` 对被 bind 的 Service 无效**：ServiceRecord 的 `startRequested`
     *   只是"是否被 startService 拉起"，而进程存活由 `Bindings`/`ConnectionRecord` 决定。
     *   只要主进程仍持有 ServiceConnection，AMS 就保留该进程并在被 kill 后**重新拉起**
     *   （`createdFromFg=true`、`restartTime` 有值即系统重建的痕迹）。
     *   这与"有开关、有代码、日志无报错、进程却一个没少"的现象完全吻合。
     *
     * ## 这一层为什么能拦住
     *
     * `bindService` 是**跨进程绑定的唯一入口**：主进程不发起 bind ⇒ AMS 侧不会建立
     * Client AppBindRecord ⇒ 目标进程没有任何绑定引用 ⇒ 子进程根本不会被创建。
     * 拦在**绑定发生之前**，而不是"进程起来之后再杀"，因此不存在"杀了又被拉起"的循环。
     *
     * ## 与旧写法（stopSelf）的差别
     *
     * | | 旧：被绑进程内 onCreate + stopSelf | 新：调用侧 bindService/startService |
     * |---|---|---|
     * | 时机 | 进程已创建、Service 已 onBind 之后 | 进程创建之前 |
     * | 对 bind 有效 | ❌ 无效（本次实测根因） | ✅ 直接不建立绑定 |
     * | 系统是否重拉 | 会（ConnectionRecord 仍在） | 不会（无绑定记录） |
     *
     * ## 风险与规避（两条红线）
     *
     * 1. **绝不能让快手崩**：只对**精确类名**返回 false，其余一律放行；
     *    且 `false` 是 `bindService` 的合法返回值（等价"绑定失败"），调用方按既有
     *    失败分支处理，不会抛异常。快手内部对推送/WebView 沙盒均有降级路径
     *    （沙盒绑不上时 WebView 退回主进程渲染，功能不消失）。
     * 2. **可回滚**：沿用既有 `purify_push` / `purify_webview` 开关。开关为 false
     *    ⇒ `blockTagFor` 恒返回 null ⇒ 一律 `chain.proceed()` ⇒ 与改动前完全一致。
     *    旧 `hookServiceCreate` 保留为**第二道防线**（万一有绕过 bind 的 startService 路径）。
     *
     * 注意：`startService` 的拦截是必要的补充——`NewKwaiLinkService` 实测
     * `startRequested=true callStart=true`，说明它**同时**被 startService 拉起。
     */
    private fun hookBindCaller(xp: XposedInterface, ctx: Context?) {
        // ★ 只 hook 上下文（ContextImpl 是 ContextWrapper 体系里真正落地实现的那层）。
        //   bindService/startService 在 ContextImpl 上被实现，主进程调用必经过它。
        val impl = try {
            Class.forName("android.app.ContextImpl", false, null)
        } catch (_: Throwable) {
            null
        }
        if (impl == null) {
            // ★ 用 once 而非 always：Logger.always 受 quiet 门控，而 perf_quiet 默认开
            //   ⇒ 排障期用 always 打的日志会被全部静默（项目已踩过这个坑）。
            Logger.once("purify.caller.nocls", "purify caller hook SKIP: ContextImpl 未找到")
            return
        }
        // ★★ 不写死某一个签名，而是**枚举所有以 Intent 为首参的 bind/start 重载**。
        //
        //   实测教训：只 hook `bindService(Intent, ServiceConnection, int)` 时，
        //   MatrixPushV3Service / NewKwaiLinkService 被成功拦下（日志有 "purify block bind"），
        //   但 **SandboxedProcessService0 的绑定完全没有日志** —— 说明 WebView 沙盒走的是
        //   另一个重载（如带 Executor 的 `bindService(Intent,int,Executor,ServiceConnection)`
        //   或 `bindServiceAsUser(...)`）。写死签名会随 Android 版本/调用方不同而漏，
        //   枚举则把"同族入口"一次全覆盖，且不依赖具体版本。
        var hooked = 0
        var blockedCount = 0
        for (m in impl.declaredMethods) {
            val mn = m.name
            val isBind = mn == "bindService" || mn == "bindServiceAsUser"
            val isStart = mn == "startService" || mn == "startServiceAsUser"
            if (!isBind && !isStart) continue
            val ps = m.parameterTypes
            if (ps.isEmpty() || ps[0] != Intent::class.java) continue
            // ★ 返回值决定"拦截时该返回什么"：必须与原方法类型严格一致，
            //   否则 Xposed 回填返回值时会抛 ClassCastException 把宿主搞崩。
            val ret = m.returnType
            val safeRet: Any? = when (ret) {
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
                android.content.ComponentName::class.java -> null
                java.lang.Void.TYPE, java.lang.Void::class.java -> null
                else -> continue // 未知返回类型：宁可不 hook，也不冒崩溃风险
            }
            try {
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("purify.caller.$mn.${ps.size}").intercept { chain ->
                        val intent = chain.args.getOrNull(0) as? Intent
                        val cn = compOf(intent)
                        val tag = if (cn == null) null else blockTagFor(cn, ctx)
                        if (tag != null && cn != null) {
                            if (blockedCount < 60) {
                                blockedCount++
                                Logger.once(
                                    "purify.hit.$mn.$tag",
                                    "purify block $mn: $tag ($cn)",
                                )
                            }
                            return@intercept safeRet
                        }
                        chain.proceed()
                    }
                hooked++
            } catch (_: Throwable) {}
        }
        // ★ 安装结果必须留痕（quiet 免疫）：否则"装了没生效"与"根本没装上"无法区分。
        Logger.once("purify.caller.inst", "purify caller hook: ContextImpl overloads=$hooked")
    }

    /**
     * 第二道防线（**保留旧写法，但已不是主拦截层**）。
     *
     * ★ 实测结论：对**被 bind 的** Service，`onCreate + stopSelf()` 无法销毁进程
     *   （AMS 侧仍有 Client AppBindRecord ⇒ 系统会重新拉起，见 [hookBindCaller] 注释）。
     *   因此这里降级为兜底：万一存在绕过 ContextImpl 的绑定路径（如已缓存的
     *   系统服务直连、或 App 侧自建 Context 未走 ContextImpl），仍能在此拦一次。
     *   两者共用 [blockTagFor] 开关表，保证规则不漂移、开关一关两边同时失效。
     */
    private fun hookServiceCreate(xp: XposedInterface, ctx: Context?) {
        try {
            val m = Service::class.java.getDeclaredMethod("onCreate")
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("purify.svc").intercept { chain ->
                val r = chain.proceed()
                try {
                    val svc = chain.thisObject as? Service
                    if (svc != null) {
                        val cn = svc.javaClass.name
                        // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）。
                        //   false ⇒ 不再 stopSelf 拦截这些服务，子进程正常启动。
                        val block = blockTagFor(cn, ctx)
                        if (block != null) {
                            if (blockDiag < 30) { blockDiag++; Logger.always("purify stop service: $block ($cn)") }
                            svc.stopSelf()
                        }
                    }
                } catch (_: Throwable) {}
                r
            }
        } catch (_: Throwable) {}
    }

    private fun hookLogProvider(xp: XposedInterface, cl: ClassLoader) {
        try {
            val cls = try { Class.forName(LOG_PROVIDER, false, cl) } catch (_: Throwable) { null } ?: return
            for (m in cls.declaredMethods) {
                val mn = m.name
                if (mn != "query" && mn != "insert" && mn != "update" && mn != "delete" && mn != "call") continue
                try {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("purify.log.$mn").intercept { chain ->
                        // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：
                        //   false ⇒ ConanLogContentProvider 的 query/insert/update/delete/call 不再被拦空。
                        if (Prefs.bool(Prefs.K_PURIFY_LOG, false)) {
                            if (blockDiag < 30) { blockDiag++; Logger.always("purify block log: $mn") }
                            // ★ 按返回类型给安全空值：原统一 return null 对 int 返回方法
                            // （delete/update）会在宿主拆箱处 NPE。query→空 Cursor、
                            // delete/update→0 行、insert→占位 Uri、call→空 Bundle
                            return@intercept when (mn) {
                                "query" -> {
                                    // ★ 空 Cursor 列名对齐宿主投影（审阅 2026-09 P1）：
                                    // 原固定 _id 单列，宿主按自己的 projection getColumnIndex
                                    // 得 -1 后 getString(-1) 抛 CursorIndexOutOfBoundsException
                                    val proj = chain.args.firstOrNull { a ->
                                        a is Array<*> && (a.isEmpty() || a[0] is String)
                                    } as? Array<String>
                                    android.database.MatrixCursor(proj ?: arrayOf("_id"), 1)
                                }
                                "delete", "update" -> 0
                                "insert" -> android.net.Uri.EMPTY
                                else -> android.os.Bundle.EMPTY
                            }
                        }
                        chain.proceed()
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}