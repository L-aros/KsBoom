package io.github.angbang852.manjiao.data

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 拦截审计的**跨进程镜像**。
 *
 * 背景（2026-09 用户反馈「app 里拦截统计/拦截记录没有信息」）：
 * 模块的审计状态（[io.github.angbang852.manjiao.hook.CfhState]）全部是**快手进程内的静态单例**——
 * 计数发生在宿主进程，压根不在本 app 进程里。而设置页 `SettingsActivity` 跑在
 * `io.github.angbang852.manjiao` 自己的进程，读到的是一份**永远空**的副本
 * （进程表实测：`com.smile.gifmaker` PID 18538 / `io.github.angbang852.manjiao` PID 25751）。
 * 模块浮层（`MainMenuDialog`）在快手进程内，所以那边一直是好的——这也解释了
 * 「模块菜单有、app 里没有」的现象。
 *
 * 说明：快手的 update 广播（[Prefs.sendUpdateBroadcast]）是 app → 快手的**写入**通道，
 * 反方向没有现成通路。这里用**镜像文件**补齐读方向：
 * - 快手进程：命中时（节流）把自己那份状态序列化写到 [FILE_NAME]
 * - app 进程：读该文件，反序列化成同样的结构用于展示
 *
 * 安全与健壮性：
 * - 只写**统计数字与规则名**，不含 photoId 之外的隐私字段；文件落在 app 私有目录，
 *   快手进程借用宿主身份写入时用的是 app 的 files 目录（由 app 侧传路径）
 * - 全部包 try/catch：镜像失败**绝不能**影响过滤主链路
 * - 读取端做大小上限与字段校验，损坏文件按「无数据」处理并删除
 */
object AuditMirror {

    private const val FILE_NAME = "audit_mirror.json"

    /**
     * 镜像目录 —— 走 [StorageDirs.sysDirFixed]（固定路径，**不依赖用户配置**）。
     *
     * ★ 为什么不用 `Android/media/<包名>/`（原方案，实测失败）：
     * 那边目录属主是模块自己（`drwxrws--- u0_a224`），快手进程是另一个 uid，
     * 写入直接 EPERM。`/sdcard/Download/` 两边都能写。
     *
     * ★ 为什么用 fixed 而不是 `sysDir(Prefs.K_DL_PATH)`：
     * 读写发生在两个进程，两侧读到的下载路径未必同步 —— 一旦不一致就永远对不上。
     */
    fun dir(): File = StorageDirs.sysDirFixed()

    private fun fileOf(): File = File(dir(), FILE_NAME)

    /** 准备目录与文件权限（**两端写侧都要调**），见 [StorageDirs.ensure] */
    private fun ensureAccessible(d: File) {
        StorageDirs.ensure(d)
    }

    /** 镜像文件大小上限：10000 条 ≈ 1.8MB，留余量到 4MB（超过视为损坏） */
    private const val MAX_BYTES = 4L * 1024 * 1024

    /**
     * **镜像里**保留的记录条数上限 —— 只留**最新**的 N 条。
     *
     * ============================ 2026-09-30 性能审查 ④ ============================
     *
     * ## 改前 / 改后
     *
     * ```
     * 改前：MAX_RECORDS = 10000   且用 records.take(MAX_RECORDS)  ← 取【最旧】的 10000 条
     * 改后：MAX_RECORDS = 3000    且用 records.takeLast(MAX_RECORDS) ← 取【最新】的 3000 条
     * ```
     *
     * ## 依据（实测，不是估算）
     *
     * 真机 logcat（`AuditMirror[ks]` 一次性自检）实测：
     * ```
     * auditEnabled=true records=10000 stats=10 hitTotal=10277
     * ```
     * ⇒ 快手侧 `hitRecords` **已经打满 auditLimit=10000 的环**，本镜像每次落盘都
     * 把 **10000 条**全量序列化。
     *
     * 镜像体积实测（读 MJSE 容器头 ctLen 反推明文长度，避开解密）：
     * ```
     * 明文 2,085,600 B  ≈ 2.09 MB      其中 records ≈ 98%（字段级占比见 write() 注释）
     * ⇒ 单条 ≈ 204 B；3000 条 ⇒ 明文 ≈ 0.62 MB
     * ```
     *
     * 单次落盘 I/O（`SecureStore.seal` 的四个整份动作）：
     * ```
     * 改前：tmp 写 2.0 + 读回 2.0 + .bak 读 2.0 + .bak 写 2.0 ≈ 8.0 MB / 次
     * 改后：0.62 × 4                                            ≈ 2.5 MB / 次
     * ```
     *
     * ## 为什么「只留最新 N 条」不破红线（用户定稿的原话就是这句）
     *
     * 本任务红线：「**可以少留旧的，不能丢新的**」。
     *  · `takeLast(N)` 取的是 deque **尾部 = 最新**的记录 ⇒ **新记录一条都不丢**；
     *  · 被裁掉的是**最旧的**那些 —— 正是红线允许牺牲的部分。
     *
     * ## ★ 顺带修掉一个潜伏 bug（原 `take` 是取【最旧】的）
     *
     * `ArrayDeque.toList()` 是**按插入顺序**（= 时间升序），所以 `take(10000)`
     * 拿的是**最前面 = 最旧**的 10000 条。当前 `hitRecords.size == 10000 == MAX_RECORDS`
     * 所以两者等价、bug 不显形；但一旦 `auditLimit` 被调大到 10000 以上
     * （`CfhDecide.kt:1578` 的上限已经放宽到 `coerceIn(20, 10000)`，将来还可能再放），
     * 旧写法就会**丢掉最新记录、只保留最旧的** —— 直接违反红线。
     * `takeLast` 在任何 size 下都保证「留新不留旧」。
     *
     * ## 保留 7 天的时间策略**未动**
     *
     * 7 天的**时间**裁剪仍由快手侧写入端（`CfhDecide.kt:1572` 的
     * `RECORD_KEEP_MS`）与恢复端（`CfhState.kt:1390`）执行，两处一个字没改。
     * 本常量只是**镜像文件的容量兜底**，即「跨重启能恢复多少条」。
     *
     * ## 代价（明示，可回滚）
     *
     * 重启后从镜像恢复的记录上限由 10000 降到 3000。重度使用下 3000 条约覆盖
     * 最近 1~2 天（用户实测「200 条不出 1 小时」）。**进程内存里的 hitRecords
     * 仍是 10000 条**（`auditLimit` 未动）⇒ 不重启时「拦截记录」页看到的条数
     * 与改动前**完全一致**；只有「重启后能读回多少历史」变少。
     *
     * ★ 回滚：把本常量改回 10000 即可（`takeLast` 语义在 10000 下与旧行为等价，
     *   因为 size ≤ 10000 时 takeLast(10000) == 全部）。
     */
    private const val MAX_RECORDS = 3000

    /** 分时桶上限：7 天 × 5 分钟粒度 = 2016 桶，留余量（跨重启恢复依赖镜像） */
    private const val MAX_HOURLY = 2200

    // ---------------------------------------------------------------- 内容签名跳过（2026-09-30 性能审查 ③）

    /**
     * 上一次**成功落盘**的内容签名（`at` 字段已剔除 —— 它每次都变，留着就永远不命中）。
     *
     * ## 为什么能跳（实测数据）
     *
     * `CfhState` 每 **20 秒**无条件调一次 `doPublish()`（`CfhState.kt:1570`
     * `PUBLISH_INTERVAL_MS=20_000`），而其中绝大多数轮次的**统计内容完全没变**
     * （推荐流干净、没有命中）。实测镜像 **636KB→830KB**，走
     * [SecureStore.seal] ⇒ 每轮 ≈**1.4MB 写 + 1.4MB 读 + 1.4MB `.bak` 拷贝**
     * ≈ 4MB FUSE I/O，20 秒一次 ⇒ 纯浪费。
     *
     * 跳过之后：内容没变 ⇒ **一次 I/O 都不做**（只算一次字符串哈希）。
     *
     * ## 风险（低，已明示）
     *
     * 唯一被牺牲的是**镜像「新鲜度」**：文件里 `at` 字段会停在最后一次**内容变化**
     * 的时刻。而 `at` 只被用来做两件事，都不受影响：
     *  · `AuditMirror.read()` 的 `fresh = writtenAt > 0`（只判「有没有数据」）；
     *  · `CfhState.restoreFromMirrorIfEmpty` 的「>7 天就不合并」。
     * 且**任何一次内容变化（= 新增命中/统计变化）都会立刻落盘**，
     * 所以「统计丢失」这个用户可见故障的窗口**一个字都没变**。
     *
     * ★ 用 `@Volatile` 而不是加锁：它只是「一个优化提示」，两个线程同时错过一次
     *   跳过去多写一遍盘，结果**完全正确**，只是没省到 —— 无需付出同步代价。
     */
    @Volatile
    private var lastSig: Int = 0

    /** 上一次落盘时的 `seal` 是否成功（失败则下一次绝不跳过，必须重试） */
    @Volatile
    private var lastSealOk: Boolean = false

    /**
     * 计算「内容签名」——**调用时必须尚未写入 `at` 字段**（见 [write] 的顺序）。
     *
     * ★ 为什么不直接拿 `root.toString()` 比字符串：830KB 的字符串常驻内存不划算，
     *   而哈希只有 4 字节且比较是 O(1)。哈希用内容本身的 `hashCode`，
     *   碰撞概率对「同一进程内连续两次快照」这个场景可以忽略
     *   （真碰撞的后果也仅是「少写一次镜像」：下一轮内容再变就会落盘，
     *   而 `at` 陈旧只影响「新鲜度」，不影响任何判定）。
     *
     * ★ 为什么**不能**改成 `root.remove("at")`：那会把 `at` 从**要落盘的内容**里
     *   一起删掉 ⇒ 文件里没有 `writtenAt` ⇒ `read()` 的 `fresh` 恒为 false
     *   ⇒ 跨重启恢复（`restoreFromMirrorIfEmpty`）直接失效。签名只读不改。
     */
    private fun sigOf(root: JSONObject): Int = root.toString().hashCode()

    /** 快照结构（两端共用的纯数据，不含宿主对象引用） */
    class Snapshot {
        var writtenAt = 0L
        var total = 0
        var baselineAt = 0L
        var records: List<Rec> = emptyList()
        var stats: List<Pair<String, Int>> = emptyList()
        var hourly: List<Pair<Long, Int>> = emptyList()
        /** 原始桶表：键 `"$bucket|$head"` → 次数（与快手进程内一致） */
        var rawRuleBuckets: Map<String, Int> = emptyMap()
        var allowCount = 0
        /** 是否来自快手进程的真实镜像（false = 从未同步过） */
        var fresh = false
    }

    /** 单条命中记录 */
    class Rec {
        var t = 0L
        var rule = ""
        var photoId = ""
        var caption = ""
        var user = ""
    }

    /** 文件路径（诊断用） */
    fun path(): String = fileOf().absolutePath

    /**
     * 序列化并落盘（**快手进程侧调用**）。
     *
     * 由 [io.github.angbang852.manjiao.hook.CfhState] 在节流后调用，不在热路径上同步跑：
     * 调用方负责放到后台线程。
     *
     * @param hourlyByRule 原始桶表，键为 `"$bucket|$head"`（与 CfhState 内部一致）。
     *   直接镜像原始键值，规则聚合由 app 侧按同一算法重算 —— 这样镜像无需理解业务语义，
     *   后续加减规则时也不会漏字段。
     */
    fun write(
        total: Int,
        baselineAt: Long,
        records: List<io.github.angbang852.manjiao.hook.CfhState.HitRecord>,
        stats: List<Pair<String, Int>>,
        hourly: List<Pair<Long, Int>>,
        hourlyByRule: Map<String, Int>,
        allowCount: Int
    ) {
        try {
            val root = JSONObject()
            root.put("v", 1)
            root.put("total", total)
            root.put("baseline", baselineAt)
            root.put("allow", allowCount)

            // ★ 自检计数（写入 JSON 本体，不依赖 logcat）：
            // 用于区分「审计开关没开（recN=0）」与「字段读不出来（recN>0 但内容空）」
            root.put("recN", records.size)
            root.put("statN", stats.size)

            val ra = JSONArray()
            // ★★★ 2026-09-30 性能审查 ④：`take` → **`takeLast`**（改前 take，改后 takeLast）。
            //
            //   `records` 来自 `hitRecords.toList()`（ArrayDeque 插入序 = 时间升序），
            //   所以 `take(N)` 取的是**最旧**的 N 条，`takeLast(N)` 取的是**最新**的 N 条。
            //   红线「可以少留旧的，不能丢新的」要求后者。详细论证见 [MAX_RECORDS] 注释。
            for (r in records.takeLast(MAX_RECORDS)) {
                // ★ 直接属性访问，不用反射（2026-09 实测踩坑）：R8 会混淆
                // HitRecord 的私有字段名，`Reflect.readAny(r, "reason")` 恒返回 null
                // ——镜像文件写出来全是空记录（rule=""、t=0）。类型在编译期已知，
                // 直接读既正确又快。
                val o = JSONObject()
                o.put("t", r.at)
                o.put("rule", r.reason)
                o.put("pid", r.photoId)
                o.put("cap", r.caption)
                o.put("usr", r.user)
                ra.put(o)
            }
            root.put("rec", ra)

            val sa = JSONArray()
            for ((k, v) in stats.take(80)) sa.put(JSONArray().put(k).put(v))
            root.put("stat", sa)

            val ha = JSONArray()
            for ((t, v) in hourly.take(MAX_HOURLY)) ha.put(JSONArray().put(t).put(v))
            root.put("hour", ha)

            // ★ 原始桶表：键含 '|'，逐条写；上限保护防异常膨胀
            val hra = JSONArray()
            var n = 0
            for ((k, v) in hourlyByRule) {
                if (n++ >= MAX_HOURLY * 2) break
                hra.put(JSONArray().put(k).put(v))
            }
            root.put("hourRule", hra)

            val d = dir()
            ensureAccessible(d)
            val f = fileOf()

            // ★★★ 2026-09-30 性能审查 ③：**内容签名未变则整条跳过落盘**。
            //
            //   顺序刻意排成「签名 → at → 落盘」：
            //   · 签名必须在写入 `at` **之前**算 —— `at` 每次都变，带上它就永远不命中
            //     （等于白改）；
            //   · 但 `at` 又必须真的写进文件 —— 它承载 `fresh` 与「>7 天不合并」两处
            //     语义（见 [lastSig] 的风险说明）。所以是「先算签名、再补 at、后落盘」。
            //
            //   跳过条件里带 `lastSealOk`：上次 `seal` 失败时**绝不跳**，
            //   必须让下一轮重试（否则一次失败就把镜像永久钉死在旧内容上）。
            val sig = sigOf(root)
            if (lastSealOk && sig == lastSig) {
                // 内容与上次成功落盘的一致 ⇒ 一次 I/O 都不做
                return
            }
            root.put("at", System.currentTimeMillis())

            // ★★★ 2026-09-30 加密落盘（规格 ①③④）
            //
            //   审计镜像里有 photoId / 文案 / 昵称（见上方 `o.put("pid"/"cap"/"usr")`），
            //   是**最敏感**的数据文件。此前明文 JSON 落在 world-writable 目录里，
            //   任意 App 既读得走（隐私泄露），也改得动/删得掉（销毁证据）。
            //
            //   SecureStore.seal 一次做齐：AES-GCM 加密 + 写临时文件 + **读回校验**
            //   + 原子替换 + 保留上一版 `.bak`。
            //   ★ 写不成**绝不退化为写明文** —— 那等于「让加密失败就能把数据摊回公共目录」。
            //
            //   ★★★ 性能审查 ③ 第二处：`.bak` 也**只在内容变化时才写** ——
            //   本函数现在只在签名变化时才会走到这里（上面已 return），
            //   所以这一处**无需再降频**：830KB 的 `.bak` 复制从「每 20 秒一次」
            //   变成「每次内容变化一次」，实测滚动期内容几乎不变 ⇒ 近乎消除。
            val ok = SecureStore.seal(f, FILE_NAME, root.toString())
            // 只有**成功**落盘才记住签名 —— 失败必须重试（见上方 `lastSealOk` 说明）
            lastSealOk = ok
            if (ok) lastSig = sig
        } catch (t: Throwable) {
            Logger.once("auditmirror.write.fail") { "audit mirror write failed: ${t.message}" }
        }
    }

    /**
     * 读取镜像（**app 进程侧调用**）。
     *
     * 任何异常都返回空快照，绝不抛出——设置页宁可显示「暂无数据」也不能崩。
     */
    fun read(): Snapshot {
        val snap = Snapshot()
        try {
            val f = fileOf()
            // ★★★ 2026-09-30 加密读取（规格 ①③④）：与池子存档同一套 fail-closed 语义。
            //
            //   · 明文旧文件 → 照常解析（**透明迁移**，规格 ④：迁移期必须读得进）
            //   · 密文且解得开 → 正常
            //   · 密文解不开/被篡改 → 先试 .bak；两份都不行 ⇒ 返回空快照（界面「暂无数据」）
            //     **但必须显眼告警** —— 否则「被篡改成空」看起来跟「本来没数据」一模一样，
            //     正是 2026-09 出过的那个事故形状（界面显示正常、实际什么都没拦）。
            //
            //   注意：这里刻意**不再**像旧实现那样「文件过大就 delete」。
            //   加密之后，攻击者往文件尾随便追加垃圾就能把它顶过阈值 ⇒ 触发删除 ⇒
            //   正好帮他完成「删证据」。现在改为「不解析 + 告警」，文件留着，
            //   由 .bak 兜底，绝不主动销毁数据。
            val fLen = try { f.length() } catch (_: Throwable) { 0L }
            if (fLen > MAX_BYTES * 3L) {
                Logger.always("【加密文件损坏/被篡改】$FILE_NAME 体积异常（$fLen 字节 > ${MAX_BYTES * 3}）⇒ 不解析以免 OOM，文件保留不删")
                return snap
            }
            val res = SecureStore.read(f, FILE_NAME)
            val txt = res.text
            if (txt.isNullOrBlank()) {
                if (res.failed) {
                    Logger.always(
                        "【加密文件损坏/被篡改】$FILE_NAME 无法解密（detail=${res.detail}）⇒ 审计镜像按空处理"
                    )
                }
                return snap
            }
            if (txt.length > MAX_BYTES) {
                Logger.once("auditmirror.toobig") { "audit mirror too big (${txt.length}), drop" }
                return snap
            }
            val root = JSONObject(txt)
            if (root.optInt("v", 0) != 1) return snap
            snap.writtenAt = root.optLong("at", 0L)
            snap.total = root.optInt("total", 0)
            snap.baselineAt = root.optLong("baseline", 0L)
            snap.allowCount = root.optInt("allow", 0)

            val ra = root.optJSONArray("rec")
            if (ra != null) {
                val list = ArrayList<Rec>(ra.length())
                for (i in 0 until ra.length()) {
                    val o = ra.optJSONObject(i) ?: continue
                    val r = Rec()
                    r.t = o.optLong("t", 0L)
                    r.rule = o.optString("rule", "")
                    r.photoId = o.optString("pid", "")
                    r.caption = o.optString("cap", "")
                    r.user = o.optString("usr", "")
                    list.add(r)
                }
                snap.records = list
            }

            val sa = root.optJSONArray("stat")
            if (sa != null) {
                val list = ArrayList<Pair<String, Int>>(sa.length())
                for (i in 0 until sa.length()) {
                    val p = sa.optJSONArray(i) ?: continue
                    val k = p.optString(0, "")
                    if (k.isEmpty()) continue
                    list.add(k to p.optInt(1, 0))
                }
                snap.stats = list
            }

            val ha = root.optJSONArray("hour")
            if (ha != null) {
                val list = ArrayList<Pair<Long, Int>>(ha.length())
                for (i in 0 until ha.length()) {
                    val p = ha.optJSONArray(i) ?: continue
                    list.add(p.optLong(0, 0L) to p.optInt(1, 0))
                }
                snap.hourly = list
            }

            val hra = root.optJSONArray("hourRule")
            if (hra != null) {
                val map = HashMap<String, Int>(hra.length() * 2)
                for (i in 0 until hra.length()) {
                    val p = hra.optJSONArray(i) ?: continue
                    val k = p.optString(0, "")
                    if (k.isEmpty()) continue
                    map[k] = p.optInt(1, 0)
                }
                snap.rawRuleBuckets = map
            }

            snap.fresh = snap.writtenAt > 0L
        } catch (t: Throwable) {
            Logger.once("auditmirror.read.fail") { "audit mirror read failed: ${t.message}" }
        }
        return snap
    }

    /**
     * 按 [CfhState.ruleTotalsInWindow] 的同一算法，在镜像的原始桶表上重算规则聚合。
     *
     * 放在读取端而不是镜像里，是为了让镜像保持「原始数据」语义：口径变化时
     * 只需重算，不用等快手进程重新落盘。
     */
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

    /**
     * 按 [CfhState.hourlySeries] 的同一算法，在镜像的原始桶表上重算分时序列。
     * 注意：这里对**所有规则**求和，等价于快手侧的 `hourlyHits`。
     */
    fun hourlySeries(raw: Map<String, Int>, hours: Int): List<Pair<Long, Int>> {
        // 汇总每个桶的总数（跨全部规则）
        val perBucket = HashMap<Long, Int>()
        for ((k, v) in raw) {
            val sep = k.indexOf('|')
            if (sep <= 0) continue
            val bucket = k.substring(0, sep).toLongOrNull() ?: continue
            perBucket.merge(bucket, v, Int::plus)
        }
        val endBucket = System.currentTimeMillis() / 3_600_000L * 3_600_000L
        val out = ArrayList<Pair<Long, Int>>(hours)
        for (h in hours - 1 downTo 0) {
            val b = endBucket - h * 3_600_000L
            out.add(b to (perBucket[b] ?: 0))
        }
        return out
    }

    /**
     * 清空镜像（用户在 app 侧点「清空记录」时调用，避免读到旧数据）。
     *
     * ★ 2026-09-30：必须**连 `.bak` 一起删**。加密后新增了「主文件坏 → 回退 .bak」的
     *   兜底（规格 ④），只删主文件会让用户以为已清空、下次却从 `.bak` 把记录读回来
     *   —— 那既是功能 bug，也会让用户误判「清空失败」。
     */
    fun clear() {
        try { fileOf().delete() } catch (_: Throwable) {}
        try { SecureStore.bakOf(fileOf()).delete() } catch (_: Throwable) {}
    }

    /** 镜像文件是否已存在（用于区分「从未同步」与「确实没命中」） */
    fun exists(): Boolean = try { fileOf().let { it.exists() && it.length() > 0 } } catch (_: Throwable) { false }

    /**
     * 读侧自检结果（**给 UI 显示用**，不写日志）。
     *
     * 为什么不用 [Logger]：`Logger` 只在快手进程 `Module.onModuleLoaded` 里 init，
     * app 进程调用它写不进 logcat ⇒ 读侧排障日志实际是丢的。直接把结论摆到页面上，
     * 用户看一眼截图就能定位是「权限进不去」还是「真没数据」。
     */
    fun readDiagnostic(): String {
        return try {
            val d = dir()
            val f = fileOf()
            when {
                !d.exists() -> "目录不存在：${d.absolutePath}（快手侧还没创建）"
                !d.canRead() -> "目录不可读：${d.absolutePath}（权限不足）"
                !f.exists() -> "镜像文件不存在（快手侧还没写入过）"
                !f.canRead() -> "镜像文件不可读（权限不足）：${f.absolutePath}"
                else -> "镜像可读 · ${f.length()} 字节 · 加密=${sealedText(f)} · 更新于 ${
                    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(f.lastModified()))
                }"
            }
        } catch (t: Throwable) {
            "自检异常：${t.message}"
        }
    }

    /**
     * 文件头是不是本模块的加密容器（只读 16 字节，**不泄露内容**）。
     *
     * ★ 2026-09-30 加它是因为：加密之后原来的「镜像可读」这句话会误导排障 ——
     *   文件权限确实可读，但内容已经是密文。这里直接把「是(AES-GCM) / 否(明文!)」
     *   摆到界面上，用户一张截图就能看出「加密到底生效了没有」。
     */
    private fun sealedText(f: File): String = try {
        val h = ByteArray(16)
        val n = java.io.FileInputStream(f).use { it.read(h) }
        when {
            n < 16 -> "太短"
            io.github.angbang852.manjiao.util.MjCrypto.isSealed(h) -> "是(AES-GCM)"
            else -> "否(明文!)"
        }
    } catch (_: Throwable) { "?" }

    /**
     * 一次性自检：两端都调，各自把「我看到的路径/权限/大小」打进日志。
     *
     * 跨进程通道出问题时（app 里没数据），这是唯一能立刻分辨
     * 「没写」「写了但读不到」「读了但解析空」的手段。
     */
    fun diagnose(who: String) {
        try {
            val d = dir()
            val f = fileOf()
            // ★ 必须用 once（不受 quiet 影响）：always 在 quiet=true 时直接返回，
            // 而 quiet 默认就是 true ⇒ 排障日志会静默消失，问题反而更难查
            Logger.once("auditmirror.diag.$who") {
                "AuditMirror[$who] dir=${d.absolutePath} exists=${d.exists()} " +
                    "canRead=${d.canRead()} canWrite=${d.canWrite()} " +
                    "file=${f.exists()} size=${f.length()} canReadFile=${f.canRead()}"
            }
            // ★ 写侧额外自报一次内容规模：区分「审计开关没开（records=0）」与
            // 「字段读不出来（records>0 但内容空）」——没有这个数字只能靠猜
            if (who == "ks") {
                Logger.once("auditmirror.diag.ks2") {
                    "AuditMirror[ks] auditEnabled=${io.github.angbang852.manjiao.hook.CfhState.auditEnabled} " +
                        "records=${io.github.angbang852.manjiao.hook.CfhState.hitRecords.size} " +
                        "stats=${io.github.angbang852.manjiao.hook.CfhState.filterHitStats.size} " +
                        "hitTotal=${io.github.angbang852.manjiao.hook.CfhState.hitTotal}"
                }
                // ★★★ 2026-09-30 性能审查 ④：**字段级体积占比自报**（只打一次）。
                //
                //   为什么要它：本次结论「体积 98% 来自 records」原先只能靠
                //   「明文长度 ÷ 条数」反推（1,752,192 B / 10000 条 ≈ 175 B/条）。
                //   反推能给出**总量**，但分不清「是条数多」还是「单条字段肥」，
                //   也定不了「砍哪个字段最划算」。这里直接按字段实测：
                //   对**抽样条数**逐字段累加 JSON 字节，再外推到全量。
                //
                //   抽样 200 条足够：字段长度分布由宿主内容决定，200 条的标准误
                //   对「哪个字段占大头」这个量级判断完全够（本项目一贯的采样口径，
                //   见 Logger.readSample 的 256KB 采样论证）。
                //
                //   输出全是**计数与字节数**，不含昵称/文案/photoId 任何正文
                //   ⇒ 可安全走明文通道，也不受 Logger 净化判据 ② 影响。
                Logger.once("auditmirror.diag.ks3") {
                    try {
                        val all = synchronized(io.github.angbang852.manjiao.hook.CfhState.hitRecords) {
                            io.github.angbang852.manjiao.hook.CfhState.hitRecords.toList()
                        }
                        if (all.isEmpty()) {
                            "AuditMirror[ks] 体积归因: records=0（无记录可归因）"
                        } else {
                            val n = minOf(all.size, 200)
                            val sample = all.takeLast(n)          // 与落盘取的是同一批（最新）
                            var bT = 0L; var bRule = 0L; var bPid = 0L; var bCap = 0L; var bUsr = 0L
                            for (r in sample) {
                                bT += r.at.toString().length
                                bRule += r.reason.length
                                bPid += r.photoId.length
                                bCap += r.caption.length
                                bUsr += r.user.length
                            }
                            val fieldSum = bT + bRule + bPid + bCap + bUsr
                            val perRec = fieldSum / n                 // 单条字段总字节（未含键名/引号）
                            val total = fieldSum * all.size / n       // 外推到全量
                            fun pct(v: Long) = if (total <= 0) 0 else (v * 100 * all.size / n / total).toInt()
                            "AuditMirror[ks] 体积归因 样本=$n 条 全量=${all.size} 条 " +
                                "单条字段=$perRec B 外推字段总量=$total B " +
                                "t=${pct(bT)}% rule=${pct(bRule)}% pid=${pct(bPid)}% " +
                                "cap=${pct(bCap)}% usr=${pct(bUsr)}%"
                        }
                    } catch (t: Throwable) { "AuditMirror[ks] 体积归因失败: ${t.message}" }
                }
            }
        } catch (t: Throwable) {
            Logger.once("auditmirror.diagfail.$who") { "AuditMirror[$who] diagnose failed: ${t.message}" }
        }
    }
}
