package io.github.angbang852.manjiao.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.util.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 拦截审计的**跨进程桥**：让 app 设置页能拿到快手进程里的审计数据。
 *
 * ## 为什么不是文件
 * 最初用「快手写文件、app 读文件」，实测在本机**必败**：`/sdcard` 下所有文件都是
 * 属主专属（`-rw-rw---- u0_a224 media_rw`，660），普通 app 不在 `media_rw` 组，
 * `setReadable` 也改不动 —— FUSE 层权限由 MediaProvider 仲裁。既有配置同步文件
 * `slowkick.properties` 的 `prefs media dead (EACCES)` 就是同一原因。
 *
 * ## 两条通道（用户 2026-09 要求「a 和 b 都用」）
 * - **B（主）**：[SyncService.prefs] 的 `getRemotePreferences` —— binder 直连的
 *   跨进程 SharedPreferences，不过文件系统，天然无权限问题。数据以 JSON 字符串
 *   存在一个键里，快手侧写、app 侧读。
 * - **A（备）**：广播请求/应答。app 发 [ACTION_REQ]，快手侧收到后用
 *   [ACTION_RSP] 回一份 JSON。B 不可用（service 未绑定）时兜底。
 *
 * 两者写入的内容格式完全一致（[AuditSnapshot.KEY] / [AuditSnapshot.RSP_EXTRA]），
 * 读取端无需区分来源。
 */
object AuditBridge {

    /** 共享配置里存放审计快照 JSON 的键 */
    const val KEY_SNAPSHOT = "audit_snapshot_json"

    /** app → 快手：请求回传一份审计快照 */
    const val ACTION_REQ = "io.github.angbang852.manjiao.AUDIT_SNAPSHOT_REQ"

    /** 快手 → app：回传审计快照（JSON 放 [EXTRA_SNAPSHOT]） */
    const val ACTION_RSP = "io.github.angbang852.manjiao.AUDIT_SNAPSHOT_RSP"

    const val EXTRA_SNAPSHOT = "snapshot"

    /** 快照最大接受长度（防御：损坏或恶意超长串撑爆内存）；正常几十 KB */
    private const val MAX_JSON = 4 * 1024 * 1024

    // ==================== 写侧（快手进程） ====================

    /**
     * 把审计状态发布到两条通道（**快手进程调用**）。
     *
     * 由 [io.github.angbang852.manjiao.hook.CfhState.mirrorSoon] 节流后异步调用。
     */
    fun publish(json: String) {
        if (json.length > MAX_JSON) return
        // B：写共享配置（binder，最可靠）
        try {
            val p = SyncService.prefs()
            if (p != null) {
                p.edit().putString(KEY_SNAPSHOT, json).apply()
            }
        } catch (_: Throwable) {}
        // A 不需要主动推送 —— 请求/应答模式，app 要时才发广播
    }

    /** 应答一次请求（快手进程收到 [ACTION_REQ] 时调用） */
    fun reply(ctx: Context, json: String, toPackage: String?) {
        try {
            val i = Intent(ACTION_RSP).putExtra(EXTRA_SNAPSHOT, json)
            if (toPackage.isNullOrBlank()) {
                i.setPackage(Prefs.OWN_PKG)
            } else {
                i.setPackage(toPackage)
            }
            ctx.sendBroadcast(i)
        } catch (_: Throwable) {}
    }

    // ==================== 读侧（app 进程） ====================

    /**
     * 请求并读取一份快照。**会阻塞最多 [timeoutMs]**（等广播应答）。
     *
     * 调用方必须在**非主线程**使用 —— 设置页渲染时若在主线程等待，
     * 会把界面卡住几百毫秒（正是本项目要消灭的那类 jank）。
     *
     * @return 解析好的快照；两条通道都拿不到时返回 [AuditSnapshot.EMPTY]
     */
    fun fetch(ctx: Context, timeoutMs: Long = 800L): AuditSnapshot {
        // 1) B 通道（主）：共享配置直读，不走广播、无等待
        var bReason = ""
        try {
            // ★ 绑定可能尚未就绪（首次进设置页时 service 正在绑定）：
            // 主动 init 一次并稍等，避免「第一次打开没数据、第二次才有」的观感问题
            var p = SyncService.prefs()
            if (p == null) {
                try { SyncService.init() } catch (_: Throwable) {}
                for (i in 0 until 6) {
                    p = SyncService.prefs()
                    if (p != null) break
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }
            if (p == null) {
                bReason = "B: service 未绑定"
            } else {
                val s = p.getString(KEY_SNAPSHOT, null)
                if (s.isNullOrBlank()) {
                    bReason = "B: 键 $KEY_SNAPSHOT 不存在（快手侧未发布过）"
                } else if (s.length > MAX_JSON) {
                    bReason = "B: 快照超长 ${s.length}"
                } else {
                    val parsed = AuditSnapshot.parse(s)
                    if (parsed.fresh) {
                        lastDiag = "B 通道成功 · 记录 ${parsed.records.size} 条"
                        return parsed
                    }
                    bReason = "B: 解析后不 fresh（长度 ${s.length}）"
                }
            }
        } catch (t: Throwable) {
            bReason = "B: 异常 ${t.message}"
        }

        // 2) A 通道（备）：广播请求 + 等应答
        val a = try { fetchByBroadcast(ctx, timeoutMs) } catch (_: Throwable) { AuditSnapshot.EMPTY }
        lastDiag = if (a.fresh) "A 通道成功 · 记录 ${a.records.size} 条" else "$bReason；A: 无应答"
        return a
    }

    /**
     * 广播请求/应答（A 通道）。
     *
     * 用 [java.util.concurrent.ArrayBlockingQueue] 而不是 CountDownLatch：
     * 应答可能先于等待到达（广播是异步的），队列天生能接住这种时序。
     */
    private fun fetchByBroadcast(ctx: Context, timeoutMs: Long): AuditSnapshot {
        val q = java.util.concurrent.ArrayBlockingQueue<String>(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val s = i.getStringExtra(EXTRA_SNAPSHOT) ?: return
                if (s.length <= MAX_JSON) q.offer(s)
            }
        }
        var registered = false
        return try {
            // ★★ A 通道此前**必然超时**（2026-09 实测修复）：
            // 原用 `RECEIVER_NOT_EXPORTED` —— 该标志的含义是「只接收**同一应用**发出的
            // 广播」。而回包来自**快手进程**（另一个应用），会被系统直接丢弃，
            // 于是 poll 永远超时、A 通道形同虚设。
            // 跨应用接收必须用 `RECEIVER_EXPORTED`。
            //
            // 安全权衡：EXPORTED 后第三方也能伪造 ACTION_RSP 回包注入假数据。
            // 缓解：① 只解析 JSON、字段有长度上限；② 假数据最坏是「统计数字不准」，
            // 不会执行任何动作（误拦标记等写操作走的是另一条带权限校验的广播）。
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, IntentFilter(ACTION_RSP), Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, IntentFilter(ACTION_RSP))
            }
            registered = true
            for (pkg in arrayOf(KsClass.PKG, KsClass.PKG_NEBULA)) {
                try {
                    ctx.sendBroadcast(
                        Intent(ACTION_REQ).setPackage(pkg)
                            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    )
                } catch (_: Throwable) {}
            }
            val json = q.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (json.isNullOrBlank()) AuditSnapshot.EMPTY else AuditSnapshot.parse(json)
        } finally {
            if (registered) { try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {} }
        }
    }

    /** 缓存（避免每次渲染都走一遍通道；设置页可见期内数据变化不频繁） */
    @Volatile private var cache: AuditSnapshot? = null
    @Volatile private var cacheAt = 0L
    private const val CACHE_MS = 5000L

    /**
     * 最近一次取数的诊断信息（**给 UI 显示**）。
     *
     * 为什么不用日志：`Logger` 只在快手进程 init，app 进程写不进 logcat，
     * 读侧排障日志实际是丢的。直接把结论摆到页面上最省事。
     */
    @Volatile var lastDiag: String = "尚未取数"
        private set

    /** 带缓存的读取（UI 线程可调用 —— 命中缓存时零等待） */
    fun fetchCached(ctx: Context): AuditSnapshot {
        val c = cache
        if (c != null && System.currentTimeMillis() - cacheAt < CACHE_MS) return c
        return AuditSnapshot.EMPTY
    }

    /** 刷新缓存（在后台线程调用，随后请自行切回主线程重绘） */
    fun refresh(ctx: Context): AuditSnapshot {
        val s = fetch(ctx)
        cache = s
        cacheAt = System.currentTimeMillis()
        return s
    }

    fun invalidate() {
        cache = null
        cacheAt = 0L
    }
}

/**
 * 一份审计快照的纯数据表示（两端共用的 JSON schema）。
 *
 * 字段名保持短，减少跨 binder 传输体积。
 */
class AuditSnapshot {
    var writtenAt = 0L
    var total = 0
    var baselineAt = 0L
    var allowCount = 0
    /** 逐条记录 */
    var records: List<Rec> = emptyList()
    /** 规则名 → 次数（累计快照用） */
    var stats: List<Pair<String, Int>> = emptyList()
    /** 原始小时桶表：key = "bucket|ruleHead" */
    var rawRuleBuckets: Map<String, Int> = emptyMap()
    /** 是否拿到过真实数据 */
    var fresh = false

    class Rec {
        var t = 0L
        var rule = ""
        var photoId = ""
        var caption = ""
        var user = ""
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("at", writtenAt)
        root.put("total", total)
        root.put("baseline", baselineAt)
        root.put("allow", allowCount)
        root.put("recN", records.size)
        val ra = JSONArray()
        for (r in records) {
            ra.put(JSONObject().apply {
                put("t", r.t); put("rule", r.rule); put("pid", r.photoId)
                put("cap", r.caption); put("usr", r.user)
            })
        }
        root.put("rec", ra)
        val sa = JSONArray()
        for ((k, v) in stats) sa.put(JSONArray().put(k).put(v))
        root.put("stat", sa)
        val ha = JSONArray()
        for ((k, v) in rawRuleBuckets) ha.put(JSONArray().put(k).put(v))
        root.put("hourRule", ha)
        return root.toString()
    }

    companion object {
        val EMPTY = AuditSnapshot()

        fun parse(json: String): AuditSnapshot {
            val s = AuditSnapshot()
            try {
                val root = JSONObject(json)
                if (root.optInt("v", 0) != 1) return s
                s.writtenAt = root.optLong("at", 0L)
                s.total = root.optInt("total", 0)
                s.baselineAt = root.optLong("baseline", 0L)
                s.allowCount = root.optInt("allow", 0)

                val ra = root.optJSONArray("rec")
                if (ra != null) {
                    val list = ArrayList<Rec>(ra.length())
                    for (i in 0 until ra.length()) {
                        val o = ra.optJSONObject(i) ?: continue
                        list.add(Rec().apply {
                            t = o.optLong("t", 0L)
                            rule = o.optString("rule", "")
                            photoId = o.optString("pid", "")
                            caption = o.optString("cap", "")
                            user = o.optString("usr", "")
                        })
                    }
                    s.records = list
                }

                val sa = root.optJSONArray("stat")
                if (sa != null) {
                    val list = ArrayList<Pair<String, Int>>(sa.length())
                    for (i in 0 until sa.length()) {
                        val p = sa.optJSONArray(i) ?: continue
                        val k = p.optString(0, "")
                        if (k.isNotEmpty()) list.add(k to p.optInt(1, 0))
                    }
                    s.stats = list
                }

                val ha = root.optJSONArray("hourRule")
                if (ha != null) {
                    val m = HashMap<String, Int>(ha.length() * 2)
                    for (i in 0 until ha.length()) {
                        val p = ha.optJSONArray(i) ?: continue
                        val k = p.optString(0, "")
                        if (k.isNotEmpty()) m[k] = p.optInt(1, 0)
                    }
                    s.rawRuleBuckets = m
                }

                s.fresh = s.writtenAt > 0L
            } catch (t: Throwable) {
                Logger.once("auditsnapshot.parse.fail") { "audit snapshot parse failed: ${t.message}" }
            }
            return s
        }

        /** 按与 CfhState 相同的算法聚合规则计数 */
        fun ruleTotalsInWindow(raw: Map<String, Int>, hours: Int): List<Pair<String, Int>> {
            val cutoff = (System.currentTimeMillis() / 3_600_000L * 3_600_000L) - (hours - 1) * 3_600_000L
            val agg = HashMap<String, Int>()
            for ((k, v) in raw) {
                val sep = k.indexOf('|')
                if (sep <= 0) continue
                val bucket = k.substring(0, sep).toLongOrNull() ?: continue
                if (bucket < cutoff) continue
                agg.merge(k.substring(sep + 1), v, Int::plus)
            }
            return agg.entries.sortedByDescending { it.value }.map { it.key to it.value }
        }

        /** 按与 CfhState 相同的算法生成分时序列 */
        fun hourlySeries(raw: Map<String, Int>, hours: Int): List<Pair<Long, Int>> {
            val perBucket = HashMap<Long, Int>()
            for ((k, v) in raw) {
                val sep = k.indexOf('|')
                if (sep <= 0) continue
                val bucket = k.substring(0, sep).toLongOrNull() ?: continue
                perBucket.merge(bucket, v, Int::plus)
            }
            val endBucket = System.currentTimeMillis() / 3_600_000L * 3_600_000L
            val out = ArrayList<Pair<Long, Int>>(hours)
            for (h in hours - 1 downTo 0) out.add((endBucket - h * 3_600_000L) to (perBucket[endBucket - h * 3_600_000L] ?: 0))
            return out
        }
    }
}
