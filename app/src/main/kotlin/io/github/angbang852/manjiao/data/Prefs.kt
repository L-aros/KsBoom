package io.github.angbang852.manjiao.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.util.Logger
import java.io.File
import java.util.Properties

object Prefs {
    const val FILE = "slowkick"
    const val ACTION_UPDATE = "io.github.angbang852.manjiao.PREFS_UPDATE"
    const val ACTION_WRITE = "io.github.angbang852.manjiao.PREFS_WRITE"
    const val ACTION_QUERY = "io.github.angbang852.manjiao.PREFS_QUERY"
    const val ACTION_PULL = "io.github.angbang852.manjiao.PREFS_PULL"
    /** signature 级自定义权限：快手进程内的配置接收器只接受持有者（本模块）的广播 */
    const val PERM_SYNC = "io.github.angbang852.manjiao.permission.PREFS_SYNC"
    // ★ 模块自身包名公开（PrefsWriteReceiver API34+ 发送方校验用）
    const val OWN_PKG = "io.github.angbang852.manjiao"
    private const val MEDIA_DIR = "/sdcard/Android/media/io.github.angbang852.manjiao"
    private const val MEDIA_FILE = "$MEDIA_DIR/slowkick.properties"

    /**
     * 跨进程可写目录（公开常量）。
     *
     * 快手进程与模块进程都能读写这里，是两者之间**唯一**稳定可达的落盘位置：
     * 模块私有目录跨包不可达（scoped storage），宿主私有目录模块 app 也读不到。
     * [io.github.angbang852.manjiao.data.AuditMirror] 复用同一目录。
     */
    const val MEDIA_DIR_PUBLIC = MEDIA_DIR
    private const val PULL_INTERVAL_MS = 2000L
    private const val QUERY_INTERVAL_MS = 30000L
    private const val QUERY_INTERVAL_SLOW = 300000L
    // 快手进程内的持久化文件（模块代码跑在快手进程，写快手私有目录不受 FUSE 限制；
    // 冷启动恢复上次配置，不再依赖模块 app 进程可达——模块 app 在部分 ROM 上
    // 被广播拉不活，ACTION_QUERY 链路不可靠）
    private const val REMOTE_SP = "slowkick_remote"

    const val K_ANTI = "anti_detect"
    const val K_DL_PATH = "dl_path"
    const val DEFAULT_PATH = "/sdcard/Download"

    const val K_IMM_ON = "imm_one_click"
    const val K_IMM_CUSTOM = "imm_custom"
    const val K_IMM_HIDE = "imm_hide_items"
    const val K_IMM_TOPBAR_ON = "imm_topbar_on"
    const val K_IMM_TOPBAR = "imm_topbar_items"
    const val K_IMM_RIGHT_ON = "imm_right_on"
    const val K_IMM_RIGHT_ITEMS = "imm_right_items"
    const val K_IMM_NICKNAME = "imm_nickname"
    const val K_IMM_COLLECTION = "imm_collection"
    const val K_IMM_BOTTOM_BAR = "imm_bottom_bar"
    const val K_IMM_GOLD = "imm_gold"


    const val K_FLT_ADS = "flt_ads"
    const val K_FLT_ADVIDEO = "flt_advideo"
    const val K_FLT_DRAMA = "flt_drama"
    const val K_FLT_IMAGE = "flt_image"
    const val K_FLT_LIVE = "flt_live"
    const val K_FLT_AI = "flt_ai"
    /** ★★★ 疑似AI声明独立开关（v13.24，50388 适配）：
     * 快手 50388 对大量普通内容也填了「疑似含AI生成内容」声明
     * （V2PROBE 实证：城也萧何/今朝体育/马上资讯全带此标记）。
     * 「疑似」是普适合规标记，不能和「确定 AI」混为一谈。
     * 默认 true = 保持拦截（与原行为一致）；关掉则放行「疑似」内容。 */
    const val K_FLT_AI_SUSPECT = "flt_ai_suspect"
    const val K_FLT_EC = "flt_ec"
    const val K_FLT_LIKE_ON = "flt_like_on"
    const val K_FLT_LIKE_TH = "flt_like_th"
    const val K_FLT_KEYWORDS = "flt_keywords"
    const val K_FLT_KW_ON = "flt_kw_on"
    /**
     * ★★★ 白名单模式开关（2026-09-26 用户定稿「判正常才放行」）。
     *
     * **语义反转**：开启后走白名单 —— 只放行**明确判为干净**的条目；
     * 判脏直接挡下；文案/昵称/标识未齐的**暂扣等齐**（3 秒超时兜底）。
     *
     * **默认 false** —— 不改变既有黑名单行为。
     * 这是高风险开关（可能造成「无更多作品」），必须真机观察后再考虑默认开。
     *
     * 开启方式（adb）：
     * ```
     * adb shell am broadcast -a io.github.angbang852.manjiao.PREFS_WRITE \
     *   -p io.github.angbang852.manjiao --es type bool \
     *   --es key flt_whitelist --ez value true
     * ```
     */
    const val K_FLT_WHITELIST = "flt_whitelist"
    /** ★ 首次进主页自动刷新一次（BOOTFLUSH，2026-09 用户要求做成开关）：
     *  原为无条件行为。关掉可 A/B 对比「首屏刷新是否反而把脏内容带进来」。
     *  默认 true = 保持原行为不变。 */
    const val K_FLT_BOOTFLUSH = "flt_bootflush"
    /** ★★★ 首页内容池接精选页（2026-09-28 用户方案，v13.5+）：
     *  精选页内容池被 AI 短剧塞满（拦截后空池转圈），从**首页发现页**
     *  拉干净内容进共享池，精选页空池时自动补位续上。
     *  开启时：hook 首页请求器（kik.o0 → bai.a），精选页本批几乎全拦时
     *  主动触发首页 load() 拉发现页数据 → HomeFeedResponse 登记干净池 →
     *  下一批精选页清洗时补位。
     *  关闭时：不 hook 请求器、不登记池、不补位，回到快手原生行为
     *  （精选页转圈或拉到脏内容）。
     *  默认 true = 保持 v13.11 用户实测「好像可以」的行为。 */
    const val K_FLT_HOMEREFILL = "flt_homerefill"


    // 性能优化
    const val K_PERF_MAINPROC = "perf_mainproc"
    const val K_PERF_FCACHE = "perf_fcache"
    const val K_PERF_LOWFREQ = "perf_lowfreq"
    const val K_PERF_QUIET = "perf_quiet"
    const val K_PERF_SENSOR = "perf_sensor"
    const val K_PERF_LOGSPAM = "perf_logspam"
    /** ★ 深度取证开关（审阅 2026-09 · M7）：LAWATCH 真源写监控（前置删除）。
     *  默认 **false**——它会按运行时类给宿主的 COW 列表写入装 hook，属高风险取证插桩，
     *  必须在明确排障时才开。与 perf_quiet 解耦：开普通诊断日志不再触发它。
     *  关闭时功能不丢，退化为 filterVmLists 的周期性清洗（落地后摘除）。 */
    const val K_PERF_LAWATCH = "perf_lawatch"
    // ★ 诊断日志独立开关（S2 2026-09）：与 quiet 解耦，开诊断不拖垮性能
    const val K_DIAG = "diag_debug"

    // ==================== 拦截审计（功能 4/5/6，2026-09） ====================
    /** 拦截记录开关：记录每条被拦内容的证据，供「拦截记录」页回看并标记误拦。
     *  默认 **false** —— 逐条记录有微量常驻开销，需要排查时才开。 */
    const val K_AUDIT_ON = "audit_on"
    /** 拦截记录保留条数（20..1000），越多越占内存（每条约 200 字节） */
    const val K_AUDIT_LIMIT = "audit_limit"
    /** 规则命中反馈总开关：标注「近期零命中」的规则，帮用户关掉无效规则 */
    const val K_AUDIT_FEEDBACK = "audit_feedback"

    // 手势功能
    const val K_GS_NO_DBL_LIKE = "gs_no_dbl_like"
    const val K_GS_OPEN_COMMENT = "gs_open_comment"
    const val K_GS_OPEN_MENU = "gs_open_menu"
    // ★ K_GS_OPEN_COMMENT_TAPS / K_GS_OPEN_MENU_TAPS 已删除（2026-09）：三击功能废弃。
    //   两键从无任何 UI 入口（值恒为默认 2），GestureHook 中对应的 `== 3` 分支
    //   一并移除 —— 保留常量只会让后来者以为存在三击可配。

    // 播放控制
    const val K_PB_NO_LOOP = "pb_no_loop"
    const val K_PB_BG_PAUSE = "pb_bg_pause"
    /** ★ 禁止自动进入直播间（2026-09 用户需求）：在直播预览页停留久了，快手会自动拉起
     *  LiveSlideActivity（设备事件日志实证停留 2m53s 后 RESUMED）。
     *  开启本开关即在 startActivity 处拒绝该跳转 —— 源头侧拦截，直播间根本不创建。
     *  默认 false = 保持原行为。 */
    const val K_PB_NO_AUTO_LIVE = "pb_no_auto_live"

    // 快手净化
    const val K_PURIFY_PUSH = "purify_push"
    const val K_PURIFY_LOG = "purify_log"
    const val K_PURIFY_WEBVIEW = "purify_webview"

    @Volatile private var sp: SharedPreferences? = null
    @Volatile private var remote = false
    @Volatile private var inited = false
    @Volatile private var cache: MutableMap<String, Any>? = null
    @Volatile private var lastPull = 0L
    @Volatile private var lastQuery = 0L
    @Volatile private var appCtx: Context? = null
    // 快手进程内的持久化 SP（remote 模式专用）
    @Volatile private var rsp: SharedPreferences? = null
    // media 文件在此设备被 FUSE 权限封死（EACCES）后置位：不再反复读文件（每次都抛异常，
    // 每 2 秒一次的失败 IO 是卡顿源），配置同步完全走广播链路
    @Volatile private var mediaDead = false

    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            appCtx = ctx.applicationContext
            remote = ctx.packageName != OWN_PKG
            if (remote) {
                rsp = ctx.getSharedPreferences(REMOTE_SP, Context.MODE_PRIVATE)
                pullRemote(force = true)
                // ★ 性能修复（审阅 2026-09 · M2）：getter 不再驱动同步（见 bool/str 注释），
                // 改为在目标进程内起一个低频守护线程驱动 —— 与原语义一致（仍受
                // PULL_INTERVAL_MS 限频），但不再把 syscall 摊到每一次开关读取上。
                // 5 秒一轮：配置主通道是广播（applyRemote 即时生效），
                // 本线程只是「广播丢失时的兜底」，无需更密。
                Thread {
                    while (true) {
                        try { Thread.sleep(5000) } catch (_: Throwable) { break }
                        try { schedulePull() } catch (_: Throwable) {}
                    }
                }.also { it.isDaemon = true; it.name = "MJ-PrefsTick" }.start()
                // media 不可达时用快手本地 SP 恢复上次配置（用户在快手菜单里改过的开关
                // 重启后不再丢失）
                if (cache == null || cache!!.isEmpty()) {
                    val m = HashMap<String, Any>()
                    for ((k, v) in rsp?.all ?: emptyMap<String, Any>()) if (v != null) m[k] = v
                    // 2026-09-05 修复：此前此处 remove 了 K_IMM_ON/K_IMM_CUSTOM（"危险开关
                    // 防残留"），导致每次快手重启一键沉浸/自定义隐藏被强制关闭——用户在
                    // 模块菜单开的开关跨重启全部失效（imm_custom 是自定义隐藏总闸，被抹
                    // 后顶栏/右侧/底栏/昵称/金币/合集全部连带失效）。rsp 是菜单开关真实
                    // 写入的持久状态，恢复它才是正确语义；关闭开关同样会写 false 落盘。
                    if (m.isNotEmpty()) {
                        cache = m
                        Logger.d("prefs restore from local sp keys=${m.size}")
                    }
                }
            } else {
                sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                mergeMediaIntoSp()
                writeMediaSnapshot()
            }
            inited = true
            Logger.d("prefs init remote=$remote keys=${cache?.size ?: sp?.all?.size ?: 0}")
        }
    }

    fun initLocal(ctx: Context) {
        remote = false
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private val pullExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ManJiaoPrefs").apply { isDaemon = true }
    }

    // ★ getter 零阻塞 I/O：此前 bool()/str() 等在调用线程（常为主线程/滑动中）同步读
    // media 文件（FUSE 路径 5-50ms），限频一过就随机掉帧一次。改为：getter 只读内存，
    // 文件刷新节流后丢到后台单线程；配置主通道仍是广播（applyRemote 即时生效）
    private fun schedulePull() {
        // ★ mediaDead 优化（审阅 2026-09）：文件路已死时不再向 executor 投无效任务
        // （每 2 秒一次的空转提交），但仍需驱动低频 query 通道——原实现里 query 是
        // 由 pullRemote 的 mediaDead 分支发出的，直接掐断会断掉广播之外的全部同步
        if (mediaDead) {
            maybeSendQuery()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPull < PULL_INTERVAL_MS) return
        lastPull = now
        pullExecutor.execute { pullRemote(true) }
    }

    private fun pullRemote(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPull < PULL_INTERVAL_MS) return
        if (mediaDead) {
            // 文件路已死：只保留低频 query 通道（30 秒限频），不再碰文件 IO
            if (cache == null) { cache = HashMap(); maybeSendQuery() } else maybeSendQuery()
            lastPull = now
            return
        }
        try {
            val f = File(MEDIA_FILE)
            if (!f.exists()) { if (cache == null) cache = HashMap(); maybeSendQuery(); return }
            val props = Properties()
            f.inputStream().use { props.load(it) }
            val m = HashMap<String, Any>()
            for (k in props.stringPropertyNames()) {
                val v = props.getProperty(k) ?: continue
                when {
                    v == "true" || v == "false" -> m[k] = v.toBoolean()
                    v.startsWith("int:") -> v.substring(4).toIntOrNull()?.let { m[k] = it }
                    v.startsWith("set:") -> m[k] = v.substring(4).split(",").filter { it.isNotEmpty() }.toSet()
                    else -> m[k] = v
                }
            }
            cache = m
            lastPull = now
        } catch (t: Throwable) {
            if (t is java.io.FileNotFoundException || t.cause is java.io.FileNotFoundException ||
                (t.message?.contains("EACCES") == true) || (t.message?.contains("Permission denied") == true)
            ) {
                // 权限封死：这台设备上 media 路彻底不可用，永久退避
                mediaDead = true
                Logger.d("prefs media dead (EACCES), switch to broadcast-only")
            } else {
                Logger.d("prefs media read fail: $t")
            }
            if (cache == null) cache = HashMap()
            maybeSendQuery()
            lastPull = now
        }
    }

    private fun maybeSendQuery() {
        val c = appCtx ?: return
        val now = System.currentTimeMillis()
        // 性能优化-低频同步：默认 5 分钟一次。30 秒周期在多进程下是广播风暴
        // （N 进程 × 每进程一条 query × 模块 app 回发全部键 × 全部进程再各收一遍）
        // ★★ 已按用户裁定回退（2026-09-29）。
        //   经过：本轮"默认值统一为关"时，本行曾被顺手改成 `!= true`
        //   （= 未设置也走 30 秒），理由是"与 UI 口径一致"。
        //   用户否掉，理由成立：干净安装下配置广播会变成 **30 秒周期** ——
        //   而这正是上方注释里点名的「广播风暴」：N 进程 × 每进程一条 query ×
        //   模块 app 回发全部键 × 全部进程再各收一遍。那是**净增加的流量与唤醒**，
        //   与「默认关」带来的收益完全不成比例（性能域不该跟着过滤域的语义走）。
        //   ⇒ 恢复原语义：**只有显式存 false 才走 30 秒；未设置视为低频（5 分钟）**。
        //   注：本行是整个改动里**唯一**动到"默认值以外语义"的地方，现已还原；
        //      其余全部改动都严格只改 `def` 字面量，不影响任何判定结果。
        val interval = if (cache?.get(K_PERF_LOWFREQ) == false) QUERY_INTERVAL_MS else QUERY_INTERVAL_SLOW
        if (now - lastQuery < interval) return
        lastQuery = now
        try {
            // FLAG_INCLUDE_STOPPED_PACKAGES：模块 app 刚被 adb install / force-stop 后处于
            // stopped state，不加此 flag 静态 receiver 收不到广播（配置同步全断的根因）
            c.sendBroadcast(
                Intent(ACTION_QUERY).setPackage(OWN_PKG)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            )
            Logger.d("prefs query sent")
        } catch (_: Throwable) {}
    }

    fun broadcastAll(ctx: Context) {
        val s = sp ?: return
        val all = s.all
        for ((k, v) in all) {
            when (v) {
                is Boolean -> sendUpdateBroadcast(ctx, "bool", k, v)
                is Int -> sendUpdateBroadcast(ctx, "int", k, v)
                is Set<*> -> sendUpdateBroadcast(ctx, "strset", k, v)
                is String -> sendUpdateBroadcast(ctx, "str", k, v)
            }
        }
        Logger.d("prefs broadcastAll keys=${all.size}")
    }

    private fun ensureMediaDir() {
        try {
            val dir = File(MEDIA_DIR)
            if (!dir.exists()) dir.mkdirs()
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        } catch (_: Throwable) {}
    }

    private fun relaxMediaFilePerm(f: File) {
        try { f.setReadable(true, false); f.setWritable(true, false) } catch (_: Throwable) {}
    }

    private fun mergeMediaIntoSp() {
        try {
            val s = sp ?: return
            val f = File(MEDIA_FILE)
            if (!f.exists()) return
            val props = Properties()
            f.inputStream().use { props.load(it) }
            val e = s.edit()
            for (k in props.stringPropertyNames()) {
                val v = props.getProperty(k) ?: continue
                when {
                    v == "true" || v == "false" -> e.putBoolean(k, v.toBoolean())
                    v.startsWith("int:") -> v.substring(4).toIntOrNull()?.let { e.putInt(k, it) }
                    v.startsWith("set:") -> e.putStringSet(k, v.substring(4).split(",").filter { it.isNotEmpty() }.toSet())
                    else -> e.putString(k, v)
                }
            }
            e.apply()
        } catch (_: Throwable) {}
    }

    private fun writeMediaSnapshot() {
        // ★ 后台化（local 模式）：每次拨开关全量重写文件是 UI 线程 IO；sp 已 apply，
        // 落盘只为跨进程同步兜底。与 pullRemote 共用单线程 executor 天然串行
        pullExecutor.execute {
            try {
                val s = sp ?: return@execute
                ensureMediaDir()
                val props = Properties()
                for ((k, v) in s.all) {
                    when (v) {
                        is Boolean -> props[k] = v.toString()
                        is Int -> props[k] = "int:$v"
                        is Set<*> -> props[k] = "set:" + (v as Set<String>).joinToString(",")
                        else -> props[k] = v.toString()
                    }
                }
                val f = File(MEDIA_FILE)
                f.delete()
                f.outputStream().use { props.store(it, null) }
                relaxMediaFilePerm(f)
            } catch (t: Throwable) { Logger.d("prefs media write fail: $t") }
        }
    }

    fun reload() {
        if (remote) pullRemote(force = true)
        else mergeMediaIntoSp()
    }

    // ★ 异步 reload（流畅度）：菜单打开等 UI 入口原先同步调 reload——remote 模式
    // 是主线程 FUSE 文件读（5-50ms，点开菜单那一下的掉帧源）。改后台单线程执行，
    // UI 先用内存缓存显示（广播链路 applyRemote 即时同步，缓存已足够新鲜）
    fun reloadAsync() {
        pullExecutor.execute {
            try { if (remote) pullRemote(force = true) else mergeMediaIntoSp() } catch (_: Throwable) {}
        }
    }

    private fun remoteWriteMedia(type: String, key: String, value: Any?) {
        // ★ 后台化：media 文件读+全量重写此前在调用线程（常为主线程/菜单 UI），
        // FUSE 路径 5-50ms。cache 已先行更新，UI 不依赖写盘返回；lastPull 同步
        // 前移防排队写盘被后续 pull 抢跑（与 pullRemote 同一 executor 串行）
        lastPull = System.currentTimeMillis()
        pullExecutor.execute {
            try {
                ensureMediaDir()
                val f = File(MEDIA_FILE)
                val props = Properties()
                if (f.exists()) f.inputStream().use { props.load(it) }
                when (type) {
                    "bool" -> props[key] = value.toString()
                    "int" -> props[key] = "int:$value"
                    "str" -> props[key] = value.toString()
                    "strset" -> props[key] = "set:" + (value as? Set<*> ?: emptySet<String>()).joinToString(",")
                }
                f.outputStream().use { props.store(it, null) }
                relaxMediaFilePerm(f)
            } catch (t: Throwable) { Logger.d("prefs remote media write fail: $t") }
        }
    }

    fun bool(key: String, def: Boolean): Boolean {
        if (remote) return cache?.get(key) as? Boolean ?: def
        return sp?.getBoolean(key, def) ?: def
    }
    fun str(key: String, def: String): String {
        if (remote) return cache?.get(key) as? String ?: def
        return sp?.getString(key, def) ?: def
    }
    fun int(key: String, def: Int): Int {
        if (remote) return cache?.get(key) as? Int ?: def
        return sp?.getInt(key, def) ?: def
    }
    fun strSet(key: String): Set<String> {
        if (remote) return cache?.get(key) as? Set<String> ?: emptySet()
        return sp?.getStringSet(key, emptySet()) ?: emptySet()
    }

    /**
     * 周期性后台同步（性能修复 审阅 2026-09 · M2）。
     *
     * **原实现的问题**：四个 getter 每个都调 `schedulePull()`，而它内部要
     * `System.currentTimeMillis()`（syscall）+ 2 秒限频判断。`CfhDecide.feedRules`
     * 单次判定就调 8 次 getter、`ImmersiveHook` 单轮 17 次 —— 这些全在 hook
     * 热路径上，"每次只多一点"但每秒数千次累积成实打实的主线程税。
     *
     * **改法**：getter 回归纯内存读（零 syscall）；后台同步改由本函数在
     * **明确的时机**驱动（Activity onResume / 菜单打开 / 配置变更后），
     * 而不是挂在每个 getter 上。语义不变——同步频率上限仍是 PULL_INTERVAL_MS。
     */
    fun tickSync() {
        if (!remote) return
        schedulePull()
    }

    private fun ed(): SharedPreferences.Editor? = sp?.edit()

    fun setBool(key: String, v: Boolean) { ed()?.putBoolean(key, v)?.apply(); if (!remote) writeMediaSnapshot() else cache?.put(key, v) }
    fun setStr(key: String, v: String) { ed()?.putString(key, v)?.apply(); if (!remote) writeMediaSnapshot() else cache?.put(key, v) }
    fun setInt(key: String, v: Int) { ed()?.putInt(key, v)?.apply(); if (!remote) writeMediaSnapshot() else cache?.put(key, v) }
    fun setStrSet(key: String, v: Set<String>) { ed()?.putStringSet(key, v)?.apply(); if (!remote) writeMediaSnapshot() else cache?.put(key, v) }

    // 快手进程收到模块 app 的值广播：更新内存缓存 + 持久化到快手本地 SP
    fun applyRemote(key: String, v: Any) {
        if (cache == null) cache = HashMap()
        cache?.put(key, v)
        persistRemoteLocal(key, v)
        Logger.d("prefs applyRemote $key")
    }

    private fun persistRemoteLocal(key: String, v: Any) {
        try {
            val e = rsp?.edit() ?: return
            when (v) {
                is Boolean -> e.putBoolean(key, v)
                is Int -> e.putInt(key, v)
                is Set<*> -> e.putStringSet(key, v as? Set<String> ?: emptySet())
                is String -> e.putString(key, v)
                else -> return
            }
            e.apply()
        } catch (_: Throwable) {}
    }

    /** 模块 app 打开时拉取：把快手进程当前 cache 全量回发（防旧值回滚用户的快手侧修改） */
    fun replyPull(ctx: Context) {
        val c = cache ?: return
        var n = 0
        for ((k, v) in c) {
            when (v) {
                is Boolean -> { sendWriteBroadcast(ctx, "bool", k, v); n++ }
                is Int -> { sendWriteBroadcast(ctx, "int", k, v); n++ }
                is Set<*> -> { sendWriteBroadcast(ctx, "strset", k, v); n++ }
                is String -> { sendWriteBroadcast(ctx, "str", k, v); n++ }
            }
        }
        Logger.d("prefs replyPull keys=$n")
    }

    private fun sendWriteBroadcast(ctx: Context, type: String, key: String, value: Any?) {
        // ★ XposedService 收编：广播之外加性写共享配置（目标进程内 libxposed 托管）
        try { SyncService.push(type, key, value) } catch (_: Throwable) {}
        try {
            val i = Intent(ACTION_WRITE).setPackage(OWN_PKG)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("type", type).putExtra("key", key)
            when (value) {
                is Boolean -> i.putExtra("value", value)
                is Int -> i.putExtra("value", value)
                is String -> i.putExtra("value", value)
                is Array<*> -> i.putExtra("value", value as Array<String>)
            }
            ctx.sendBroadcast(i)
        } catch (_: Throwable) {}
    }

    fun sendUpdateBroadcast(ctx: Context, type: String, key: String, value: Any?) {
        // ★ XposedService 收编：广播之外加性写共享配置（模块 App 进程写，目标进程
        // 的变更监听直接收；service 未绑定时广播仍兜底）
        try { SyncService.push(type, key, value) } catch (_: Throwable) {}
        try {
            val i = Intent(ACTION_UPDATE)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("type", type).putExtra("key", key)
            when (value) {
                is Boolean -> i.putExtra("value", value)
                is Int -> i.putExtra("value", value)
                is String -> i.putExtra("value", value)
                is Set<*> -> {
                    // toTypedArray() 在 Set<*> 上静态类型为 Array<out Any?>，会落到
                    // putExtra(Serializable) 重载，接收端 getStringArrayExtra 恒为 null，
                    // strset 广播全丢。必须显式转 Array<String> 走 String[] 重载
                    val arr = value.map { it.toString() }.toTypedArray()
                    i.putExtra("value", arr)
                }
            }
            // ★ 限定只投递给两个快手包：UPDATE 广播携带用户全部偏好，隐式发送可被
            // 任意第三方 app 注册同名 action 监听（配置嗅探）。显式 setPackage 后
            // 仅快手/极速版进程可达；快手侧接收器另有 signature 权限防伪造注入
            for (pkg in arrayOf(KsClass.PKG, KsClass.PKG_NEBULA)) {
                try { ctx.sendBroadcast(Intent(i).setPackage(pkg)) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun setBoolSync(ctx: Context, key: String, v: Boolean) {
        if (remote) { if (cache == null) cache = HashMap(); cache?.put(key, v); persistRemoteLocal(key, v); remoteWriteMedia("bool", key, v); sendWriteBroadcast(ctx, "bool", key, v) }
        else { setBool(key, v); sendUpdateBroadcast(ctx, "bool", key, v) }
    }
    fun setStrSetSync(ctx: Context, key: String, v: Set<String>) {
        if (remote) { if (cache == null) cache = HashMap(); cache?.put(key, v); persistRemoteLocal(key, v); remoteWriteMedia("strset", key, v); sendWriteBroadcast(ctx, "strset", key, v.toTypedArray()) }
        else { setStrSet(key, v); sendUpdateBroadcast(ctx, "strset", key, v) }
    }
    fun setIntSync(ctx: Context, key: String, v: Int) {
        if (remote) { if (cache == null) cache = HashMap(); cache?.put(key, v); persistRemoteLocal(key, v); remoteWriteMedia("int", key, v); sendWriteBroadcast(ctx, "int", key, v) }
        else { setInt(key, v); sendUpdateBroadcast(ctx, "int", key, v) }
    }
    fun setStrSync(ctx: Context, key: String, v: String) {
        if (remote) { if (cache == null) cache = HashMap(); cache?.put(key, v); persistRemoteLocal(key, v); remoteWriteMedia("str", key, v); sendWriteBroadcast(ctx, "str", key, v) }
        else { setStr(key, v); sendUpdateBroadcast(ctx, "str", key, v) }
    }

    fun toggleBool(ctx: Context, key: String, def: Boolean): Boolean {
        val nv = !bool(key, def)
        setBoolSync(ctx, key, nv)
        return nv
    }
}
