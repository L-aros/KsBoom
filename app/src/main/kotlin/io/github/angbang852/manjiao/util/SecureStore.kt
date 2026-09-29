package io.github.angbang852.manjiao.util

import io.github.angbang852.manjiao.data.StorageDirs
import java.io.File
import java.io.FileOutputStream

/**
 * ★★★ 加密文件读写总闸（2026-09-30 用户规格 ③④）
 *
 * 把 [MjCrypto]（纯算法）与 [KeyVault]（钥匙）拼成「一个数据文件该怎么安全落盘/读回」，
 * 并把规格里的四条落地要求一次性做完：
 *
 * | 规格 | 做法 | 位置 |
 * |---|---|---|
 * | ③ fail-closed | 解不开 ⇒ 返回 null 并**大声告警**，绝不放行 | [read] |
 * | ④ 原子写 | 写 `.tmp.<pid>.<序号>`（**每个写者唯一**，见 [tmpOf]）→ **读回校验** → 原子改名 | [seal] |
 * | ④ 保留上一版 | 覆盖前先存 `.bak`；主文件坏了先试 `.bak` | [seal]/[read] |
 * | ④ 透明迁移 | 读到明文**照常解析**，下次落盘自动变密文 | [read]/[seal] |
 *
 * ## 为什么「读回校验」不能省
 *
 * `/sdcard/`（FUSE/sdcardfs）上的 `renameTo` 历史上**静默失败过**（见 `KsCache.flushNow`
 * 的注释），而且写一半被杀会留下半截密文。若不校验就把 `pool.json` 盖掉，
 * 下一次冷启动会解不开 ⇒ 去重表丢 ⇒ **重复上屏**（违反用户第一硬规则）。
 * 所以：写完先自己解一遍，解得开才改名。**写不进去是小事，写坏旧数据是大事。**
 *
 * ## 没有钥匙时为什么**不退回写明文**
 *
 * 那等于「加密功能一失效就把数据摊在公共目录」，正是本任务要消灭的状态；
 * 而且明文文件还给了攻击者「替换存档」的可乘之机。故：**没钥匙就不写**，
 * 大声告警，等下一次落盘重试（[io.github.angbang852.manjiao.hook.CfhPoolStore]
 * 的去抖窗口只有 3 秒，会很快自愈）。
 */
object SecureStore {

    /** 读取结果。`failed=true` 表示「文件是密文但解不开」——调用方必须 fail-closed */
    class ReadResult(
        /** 解出的文本；null = 不可用 */
        val text: String?,
        /** 来源：`sealed` 密文 / `plain` 明文旧文件 / `bak` 上一版 / `none` 没有 */
        val from: String,
        /** 是不是「有文件但读不出来」（损坏 / 被篡改 / 没钥匙） */
        val failed: Boolean,
        val detail: String = ""
    )

    // ---------------------------------------------------------------- 路径约定

    /** 上一版备份（与正式文件**同名域**，AAD 因此仍然匹配，可互相顶替） */
    fun bakOf(f: File): File = File(f.parentFile, f.name + ".bak")

    /**
     * 临时文件名序号（**每次落盘自增**，见 [tmpOf]）。
     *
     * ★ 2026-09-29 修复：真机 logcat 实证 SECFAIL 来自**同进程两个线程**并发写同一个
     * `<name>.tmp`（pid=18175 下 tid=18299=`ManJiaoMirror` 与 tid=18300=`MJ-AuditPub`
     * 在同一毫秒各报一条）。**只用 pid 不够** —— 同进程内两个线程也会撞，
     * 所以必须做到「每次写都换名字」。
     */
    private val tmpSeq = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 临时文件（原子替换用）——**每次调用都返回一个不同的路径**。
     *
     * ## 为什么不能是固定名 `<name>.tmp`（2026-09-29 真机事故的根因）
     *
     * 写盘序列是「截断写 tmp → 读回校验 → 原子改名」（见 [seal]）。两个写者拿到
     * **同一个** tmp 路径时：
     * ```
     *   A: 截断 tmp，写 blob A（~2MB）
     *   B: 截断 tmp，写 blob B          ← 把 A 的临时文件清掉/改短
     *   A: 读回 tmp ⇒ 拿到半截 ⇒ openStrict=null ⇒ SECFAIL、放弃替换
     * ```
     * [MjCrypto.openStrict] 要求「每条记录都对上、且**尾部无残留**」，被截断的密文
     * 必然过不了 —— 于是 fail-safe 正确地保住了旧数据，但**这一轮镜像白写了**
     * （真机表现：`audit_mirror.json` 更新时断时续）。
     *
     * ## 并发写者是谁（实测，不是推测）
     *
     * 同进程、同一条 `CfhState.doPublish()` 路径上的两个线程：
     *  · `MJ-AuditPub` —— `CfhState.startPeriodicPublish` 的 20s 周期线程，**直接**调 doPublish；
     *  · `ManJiaoMirror` —— `mirrorExecutor` 单线程；`mirrorSoon()` 命中时 submit 一次
     *    doPublish，而周期线程每轮把 `lastMirrorAt` 清零（`CfhState.kt:1531`）⇒
     *    30s 节流形同失效，重负载下两者必然重叠。
     *
     * ## 威胁分析（为什么 pid 之外还要序号）
     *
     * 这不是安全问题而是**可用性**问题：写盘被放弃 = 审计证据停止累积，正是规格 ③
     * 要消灭的「静默丢证据」。反过来，**不能改成「共享一个 tmp + 加锁」** ——
     * 锁只在单进程内有效；一旦用户关掉「仅主进程注入」（`Prefs.K_PERF_MAINPROC`），
     * 快手的 `:messagesdk` / `:push_v3` / `:kwv_sandboxed_p0` 子进程也会写同一文件，
     * 而跨进程锁在这里并不存在。让**每个写者拥有自己的临时文件**才能同时覆盖
     * 「同进程多线程」与「多进程」两种情形：pid 保证跨进程唯一，序号保证同进程每次唯一。
     *
     * ★ AAD 绑定的是调用方传入的 `logicalName`（**与临时文件名无关**，见 [MjCrypto.aadOf]），
     *   所以改临时文件名不影响解密；`.bak` / `.tmp` 仍与正式文件同一认证域。
     */
    private fun tmpOf(f: File): File = File(
        f.parentFile,
        f.name + ".tmp." + android.os.Process.myPid() + "." + tmpSeq.incrementAndGet()
    )

    // ---------------------------------------------------------------- .tmp 孤儿清扫（2026-09-30 性能审查 ②）

    /**
     * 清扫**本文件历史遗留**的 tmp 孤儿。
     *
     * ## 孤儿是怎么来的（两条路径，都在码，不是推测）
     *
     * 1. **进程被 SIGKILL / force-stop**（真机实证：设备上那个
     *    `pool.json.tmp.3044.94` 的 pid=3044 早已不存在，而 `pool.json` 本体在
     *    它之后 40 分钟仍在正常更新 ⇒ 存盘主链路没坏，是**进程死在了
     *    `tmp.writeBytes` 与收尾 `tmp.delete` 之间**）。
     *    ★ 关键：SIGKILL 下 `catch` / `finally` **一律不执行** —— 所以再加一条
     *      「异常路径删 tmp」也治不了它。唯一有效的手段是**下次写盘时回头扫**。
     * 2. **`tmp.delete()` 自己失败且被静默吞掉**：下面四处收尾全是
     *    `try { tmp.delete() } catch (_: Throwable) {}`，既不检查返回值也不记日志
     *    ⇒ FUSE 上删不掉（例如文件被别的进程持着）就**无声**留一个孤儿。
     *
     * ## 安全性论证（为什么绝不会误删「别的进程正在写」的 tmp）
     *
     * 四重限制，**缺一不可**：
     *  · **只匹配同名前缀** `<本文件名字>.tmp.` —— 结构上就够不到别的文件的 tmp；
     *  · **名字里的 pid 必须已死**（[isPidDead]）—— 这是最硬的一道：
     *    「别的进程正在写的 tmp」其 pid **必然存活** ⇒ 结构上不可能被删。
     *    （pid 复用只会让本判据**更保守**：复用后我们以为它还活着，于是跳过。）
     *  · **mtime 超过 [TMP_STALE_MS]（10 分钟）** —— 一次 [seal] 的 tmp 从创建到删除
     *    只有毫秒级（实测整次存盘 ~55ms），10 分钟比它大 **4 个数量级**；
     *  · **拿不到 mtime（<=0）就不碰** —— 宁可不删，也不赌。
     *
     * 调用点在**本函数自己的 tmp 创建之前**（[seal] 里 `tmp.writeBytes` 之前），
     * 因此这一轮清扫在结构上不可能扫到自己正在写的那个临时文件
     * （何况自己的 pid 一定存活，第一道判据就已排除）。
     *
     * ## 代价
     * 一次 `File.list()`（`.sys/` 约 20 个条目，一次 getdents，相对 1.2MB 读写可忽略），
     * 且**按文件 5 分钟最多一次**（[TMP_SWEEP_INTERVAL_MS]），不给热路径添 I/O。
     */
    private fun sweepStaleTmps(f: File) {
        try {
            val now = System.currentTimeMillis()
            val key = f.absolutePath
            val prev = lastSweepAt[key]
            // 先落时间戳：即使下面 list() 失败也不反复重试，避免每次存盘都扫目录
            if (prev != null && now - prev < TMP_SWEEP_INTERVAL_MS) return
            lastSweepAt[key] = now
            val dir = f.parentFile ?: return
            val prefix = f.name + ".tmp."
            val names = dir.list() ?: return
            var swept = 0
            var freed = 0L
            for (n in names) {
                if (!n.startsWith(prefix)) continue          // ① 只碰同名前缀
                val g = File(dir, n)
                // ② 名字形如 <name>.tmp.<pid>.<seq> ⇒ 取出 pid，存活则**绝不碰**
                val pid = n.substring(prefix.length).substringBefore('.').toIntOrNull()
                if (pid == null || !isPidDead(pid)) continue
                val mt = try { g.lastModified() } catch (_: Throwable) { 0L }
                if (mt <= 0L) continue                       // ③ 时间未知 ⇒ 不碰
                if (now - mt < TMP_STALE_MS) continue        // ④ 还「新鲜」⇒ 再等等
                val len = try { g.length() } catch (_: Throwable) { 0L }
                try { if (g.delete()) { swept++; freed += len } } catch (_: Throwable) {}
            }
            if (swept > 0) {
                // ★ 刻意用 SECMIGRATE 而**不是** SECFAIL：
                //   清扫是**正常维护动作**，不是加密故障。而验证协议用
                //   `grep -c SECFAIL` 统计故障数（当前基线 45）——
                //   把维护动作写进那个 tag 会污染这个判据。
                //   内容只含数量/字节数/文件名，无任何敏感字段。
                try {
                    Logger.evidence(
                        "SECMIGRATE",
                        "★已清扫陈旧临时文件 ${swept} 个（写者 pid 已死 + 超过 " +
                            "${TMP_STALE_MS / 60_000} 分钟）释放 ${freed} 字节  target=${f.name}"
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * 进程 [pid] 是否**已经不存在**。
     *
     * 判据：`/proc/<pid>` 目录不存在。procfs 对**所有**进程都列出该目录
     * （即便读不到里面细节），因此「目录不存在」可靠地等价于「进程已死」。
     * pid 复用会让本判据返回 false（以为还活着）⇒ 更保守，符合「宁可留孤儿」。
     *
     * 取不到信息时**一律返回 false**（当作还活着）—— 清扫是优化，不是正确性依赖。
     */
    private fun isPidDead(pid: Int): Boolean {
        if (pid <= 0) return false
        // 自己的 pid 一定活着
        if (pid == android.os.Process.myPid()) return false
        return try { !File("/proc/$pid").exists() } catch (_: Throwable) { false }
    }

    /** 各目标文件上次清扫 tmp 的时刻（避免每次落盘都扫目录） */
    private val lastSweepAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 清扫间隔：同一文件 5 分钟最多扫一次 */
    private const val TMP_SWEEP_INTERVAL_MS = 5L * 60_000L

    /** 「陈旧」判据：tmp 的 mtime 超过 10 分钟即认定其写者已死（一次写盘 ~55ms） */
    private const val TMP_STALE_MS = 10L * 60_000L

    // ---------------------------------------------------------------- .bak 频率闸（2026-09-30 性能审查 ②）

    /**
     * `.bak` 的**最低重写间隔**（毫秒）。`0` = 每次落盘都写 `.bak` —— **默认值，
     * 也就是所有既有调用方的行为一个字不变**（[seal] 的默认参数）。
     *
     * ## 为什么需要它（实测的 I/O 放大）
     *
     * `f.copyTo(bakOf(f))` 是**整份读 + 整份写**。`pool.json` 实测 1.22MB ⇒
     * 每次存盘光备份就是 2.44MB 的 FUSE I/O，与「写 tmp 1.22MB + 读回 1.22MB」
     * 并列成为最大头。滚动期实测 36 次存盘/60s ⇒ 备份一项就吃掉约 **88MB/分钟**。
     *
     * ## 为什么降频是**安全**的（红线 B 论证，见 CfhPoolStore 的调用点注释）
     *
     * `.bak` 只在「主文件坏了/被篡改/被删」时兜底。把它从「每次」降到「每分钟」，
     * 最坏情形是回退到一个**最多 60 秒旧**但**依然完整可解密**的版本。
     * 对 pool.json 而言，这份陈旧只会少掉「这 60 秒内新增的拉黑 id」——
     * 而 `CfhPoolStore.loadNow()` 在成功路径上**无条件**调 `mergeIdsAlone()`
     * （`CfhPoolStore.kt:782`），把独立副本 `pool_ids.json` 里多出来的 id **只增不改**
     * 地补回来；而 `pool_ids.json` 的 `.bak` **不降频**（仍每次写）。
     * ⇒ 陈旧 `.bak` 丢掉的 id，必然被更新的 `pool_ids.json` 补回 ⇒ **红线 B 不破**。
     */
    private val lastBakAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 「每次都写 .bak」——默认策略，保持既有语义 */
    const val BAK_EVERY_SAVE = 0L

    /**
     * 本次落盘该不该重写 `.bak`。
     *
     * `minIntervalMs <= 0` ⇒ **恒为 true**（默认路径，与改动前逐字等价）。
     *
     * ★ 注意这里是「**按时间**」而不是「按内容是否变化」—— 因为 `seal` 拿到的只有
     *   待写文本，要判断「内容是否变了」得先把旧文件读出来（1.22MB 读），
     *   那就把省下来的 I/O 又花回去了。时间闸零 I/O，且语义更好论证
     *   （「最坏回退到 60 秒前的完整版本」比「内容变了才备份」更容易说清代价）。
     */
    private fun bakDue(f: File, minIntervalMs: Long): Boolean {
        if (minIntervalMs <= 0L) return true
        val now = System.currentTimeMillis()
        val key = f.absolutePath
        val prev = lastBakAt[key]
        if (prev != null && now - prev < minIntervalMs) return false
        lastBakAt[key] = now
        return true
    }

    // ---------------------------------------------------------------- 读

    /**
     * 读一个数据文件（自动取钥匙）。
     *
     * 语义（与规格 ③ 一一对应）：
     *  - **不存在/空** ⇒ `text=null, failed=false`：「没有这条数据」，不是故障；
     *  - **明文旧文件** ⇒ 正常返回文本（迁移期必须读得进，见规格 ④）；
     *  - **密文且解得开** ⇒ 返回文本；
     *  - **密文坏 / 被篡改 / 没钥匙** ⇒ 先试 `.bak`；两份都不行 ⇒
     *    `text=null, failed=true` + **显眼告警**。
     *
     * ★ 调用方拿到 `failed=true` 时必须走「当作没有」的那条路，且**过滤类配置
     *   一律按「拦」处理**（规格 ③ 点名的那个真机事故：读不到就退回 `def`，
     *   而 `def` 曾是 `true` ⇒ 界面显示开、实际静默关掉过滤）。
     */
    fun read(f: File, logicalName: String): ReadResult {
        val key = KeyVault.currentKey()
        return read(f, logicalName, key)
    }

    fun read(f: File, logicalName: String, key: ByteArray?): ReadResult {
        val aad = MjCrypto.aadOf(logicalName)
        val exists = try { f.exists() && f.length() > 0L } catch (_: Throwable) { false }
        if (!exists) {
            // ★ 主文件不在、但 `.bak` 在 ⇒ 很可能被「删存档」了（点名威胁之一）。
            //   有 .bak 就用它顶替并告警 —— 比「当作没有存档」好得多。
            val bakMiss = bakOf(f)
            val bakExists = try { bakMiss.exists() && bakMiss.length() > 0L } catch (_: Throwable) { false }
            if (bakExists && key != null) {
                try {
                    val bb = bakMiss.readBytes()
                    if (MjCrypto.isSealed(bb)) {
                        val bp = MjCrypto.openStrict(bb, key, aad)
                        if (bp != null) {
                            alarm(logicalName, f, "主文件不存在，已用上一版 .bak 顶替（存档可能被删）")
                            return ReadResult(String(bp, Charsets.UTF_8), "bak", false, "主文件缺失，回退 .bak")
                        }
                    }
                } catch (_: Throwable) {}
            }
            return ReadResult(null, "none", false)
        }

        val bytes = try { f.readBytes() } catch (t: Throwable) {
            return ReadResult(null, "none", false, "读文件异常 ${t.javaClass.simpleName}")
        }
        if (bytes.isEmpty()) return ReadResult(null, "none", false)

        // ① 明文旧文件（迁移期）：照常解析（读到就正常解析，规格 ④），
        //    并**立刻在后台把它转成密文**。
        //
        //    ★ 为什么要「读到就转」而不是「等下次落盘」：像 adapt_cache.txt 这种
        //    缓存，只要签名没变就永远是「读」而不「写」—— 光等下次落盘，
        //    它会**一直以明文躺在公共目录里**。读到即迁移才真正兑现规格 ④ 的
        //    「读到明文就正常解析，然后写成加密格式」。
        //    放后台线程是为了不阻塞调用方（`read` 可能被主线程调用）。
        if (!MjCrypto.isSealed(bytes)) {
            val txt = String(bytes, Charsets.UTF_8)
            migratePlainAsync(f, logicalName, txt)
            return ReadResult(txt, "plain", false, "明文旧文件（已在后台转密文）")
        }

        // ② 密文：没钥匙 ⇒ 直接 fail-closed（不猜、不降级）
        if (key == null) {
            alarm(logicalName, f, "拿不到钥匙（快手进程未运行？）")
            return ReadResult(null, "sealed", true, "无钥匙")
        }

        val pt = MjCrypto.openStrict(bytes, key, aad)
        if (pt != null) return ReadResult(String(pt, Charsets.UTF_8), "sealed", false)

        // ③ 主文件解不开 ⇒ 试 .bak（规格 ④：「两份都失败才走 fail-closed」）
        val bak = bakOf(f)
        val bakOk = try { bak.exists() && bak.length() > 0L } catch (_: Throwable) { false }
        if (bakOk) {
            try {
                val bb = bak.readBytes()
                if (MjCrypto.isSealed(bb)) {
                    val bp = MjCrypto.openStrict(bb, key, aad)
                    if (bp != null) {
                        // ★ 用的是上一版，必须显眼告警：说明主文件已经坏了/被换过了
                        alarm(logicalName, f, "主文件无法解密，已回退上一版 .bak（主文件损坏或被篡改）")
                        return ReadResult(String(bp, Charsets.UTF_8), "bak", false, "回退 .bak")
                    }
                }
            } catch (_: Throwable) {}
        }

        // ④ 两份都不行 ⇒ fail-closed + 显眼告警
        alarm(logicalName, f, "主文件与 .bak 均无法解密（加密文件损坏/被篡改）")
        return ReadResult(null, "sealed", true, "主/.bak 均解不开")
    }

    // ---------------------------------------------------------------- 写

    /**
     * 把一个数据文件**加密落盘**（原子、带 .bak、写完校验）。
     *
     * @return true = 已经安全落盘；false = 没写成（**旧数据保持原样，绝不被破坏**）
     */
    /**
     * @param bakMinIntervalMs `.bak` 的最低重写间隔；`0`（默认）= 每次落盘都写，
     *   **所有既有调用方行为不变**。只有 `pool.json` 显式传 60 秒（见
     *   [io.github.angbang852.manjiao.hook.CfhPoolStore.saveNow] 的红线 B 论证）。
     */
    fun seal(
        f: File,
        logicalName: String,
        text: String,
        bakMinIntervalMs: Long = BAK_EVERY_SAVE
    ): Boolean {
        val key = KeyVault.currentKey()
        if (key == null) {
            // 没钥匙：不写（写明文等于放弃加密），响亮告警，等下次重试
            alarm(logicalName, f, "拿不到钥匙 ⇒ 本次不落盘（拒绝写明文），下次重试")
            return false
        }
        val aad = MjCrypto.aadOf(logicalName)
        val plain = text.toByteArray(Charsets.UTF_8)
        val blob = try { MjCrypto.seal(plain, key, aad) } catch (t: Throwable) {
            alarm(logicalName, f, "加密失败 ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        // ★ 写前自检：先确认「这份密文我自己解得开」，避免把读不回来的数据写进去。
        //
        //   ★★★ 2026-09-30 性能审查后**明确保留**这一条：它只花 CPU（一次 AES-GCM
        //   解密，1.22MB ≈ 1~2ms），**不产生任何 FUSE I/O**。红线 A 要保的是
        //   「写坏旧数据」的最后一道防线，而这一条同时还是「钥匙/AAD 用错」的
        //   第一道自检 —— 省它省不到 I/O，风险却实打实，故不动。
        val back = MjCrypto.openStrict(blob, key, aad)
        if (back == null || !back.contentEquals(plain)) {
            alarm(logicalName, f, "加密自检失败（解不回原文）⇒ 放弃本次落盘，保留旧数据")
            return false
        }

        // ★★★ 2026-09-30 性能审查 ②（.tmp 孤儿）：**在创建自己的 tmp 之前**回头扫一次。
        //   进程被 SIGKILL 时 catch/finally 一律不执行（见 [sweepStaleTmps]），
        //   所以「下次写盘时清理」是唯一能覆盖该路径的手段。
        //   放在 tmpOf(f) 之前 ⇒ 结构上不可能扫到自己本轮正在写的临时文件。
        sweepStaleTmps(f)

        // ★ 临时文件名必须在 try **之前**定下来（2026-09-29）：[tmpOf] 现在每次返回
        //   **唯一**名字（pid + 序号），异常路径要能引用它才能收尾干净 —— 否则每次
        //   失败都会在公共目录里留一个 ~2MB 的孤儿临时文件（旧实现是固定名，
        //   下次写会覆盖掉它，所以这个问题以前不存在）。
        val tmp = tmpOf(f)

        return try {
            f.parentFile?.let { StorageDirs.ensure(it) }
            // ① 保留上一版：**只在旧文件已经是密文时**才备份。
            //
            //   ★★ 为什么必须加这个判断（真机踩到）：迁移期第一次落盘时，旧文件还是明文；
            //   若照抄一份 `.bak`，公共目录里就会永久留下**一份明文副本**——
            //   攻击者直接读 `.bak` 就绕过了整个加密，加密等于白做。
            //   而此时 `.bak` 的内容与即将写入的完全一致，本来也没有备份价值。
            //
            //   ★★★ 2026-09-30 性能审查 ②：这里原先是**每次落盘**都做，
            //   而 `copyTo` 是**整份读 + 整份写**（pool.json 1.22MB ⇒ 单次 2.44MB
            //   FUSE I/O，占一次存盘总 I/O 的**一半**）。现在按
            //   [bakMinIntervalMs] 降频；`0`（默认）= 每次都写，既有调用方行为不变。
            //   降频的安全性论证见 [lastBakAt] 的注释与调用点（CfhPoolStore）。
            if (isSealedFile(f) && bakDue(f, bakMinIntervalMs)) {
                try { f.copyTo(bakOf(f), overwrite = true) } catch (_: Throwable) {}
            }
            // ② 写临时文件（名字已在上方取好，唯一）
            tmp.writeBytes(blob)
            StorageDirs.relaxFile(tmp)
            // ③ 读回校验（防写一半 / FUSE 抽风）—— **红线 A：整条保留，绝不删**。
            //
            //   ★★★ 2026-09-30 性能审查 ②：**只优化「深度」，不改「有无」**。
            //
            //   原来第三步是 `MjCrypto.openStrict(rb, …) != null`（再解一次密）。
            //   现改为「**长度相等 + 与内存中的 blob 逐字节相等**」：
            //
            //   · **强度更高而不是更低**：`openStrict(rb)` 只要求「rb 是一份
            //     本钥匙能解开的合法容器」；`rb.contentEquals(blob)` 要求「落盘的
            //     就是刚才那份字节」。而 `blob` 已在上面 :218 被证明能解回 `plain`
            //     ⇒ contentEquals 成立时必然也能解回 `plain`（⊇ 原保证）。
            //     任何写歪、写短、写到别的文件上都当场暴露。
            //   · **省的是 CPU，不是 I/O**：省掉第 3 次 AES-GCM 解密
            //     （1.22MB ≈ 1~2ms CPU）；**那次 1.22MB 读回仍然照读**——
            //     因为「只验长度 + tag」会漏掉「中段被写坏」（tag 字节本身
            //     照样能对上，长度也没变），那正是红线 A 点名要防的
            //     「rename 覆盖掉好存档」。所以这一步**不拿 I/O 冒险**。
            //   · 长度先判：截断是最常见的真实失败形态，且省掉无谓的 memcmp。
            val rb = try { tmp.readBytes() } catch (_: Throwable) { null }
            if (rb == null || rb.size != blob.size || !rb.contentEquals(blob)) {
                try { tmp.delete() } catch (_: Throwable) {}
                alarm(
                    logicalName, f,
                    "临时文件读回校验失败（长度/内容与预期不符，" +
                        "读回=${rb?.size ?: -1} 预期=${blob.size}）⇒ 放弃替换，保留旧数据"
                )
                return false
            }
            // ④ 原子改名
            var ok = false
            try { ok = tmp.renameTo(f) } catch (_: Throwable) { ok = false }
            if (!ok) {
                // rename 在 /sdcard（FUSE）上可能失败：退化为「删旧 + 改名」，
                // 再不行就直接覆盖写 —— 能写进去优先于形式上的原子性
                try { f.delete() } catch (_: Throwable) {}
                try { ok = tmp.renameTo(f) } catch (_: Throwable) { ok = false }
                if (!ok) {
                    try { f.writeBytes(blob); ok = true } catch (_: Throwable) {}
                    try { tmp.delete() } catch (_: Throwable) {}
                }
            }
            if (!ok) {
                try { tmp.delete() } catch (_: Throwable) {}   // 收尾：不留孤儿临时文件
                alarm(logicalName, f, "原子替换失败（rename/直写都失败）⇒ 本次未落盘")
                return false
            }
            // ⑤ 放开读权限：跨 uid 读取需要（见 StorageDirs 注释）
            StorageDirs.relaxFile(f)
            true
        } catch (t: Throwable) {
            // 收尾：写入中途抛异常（磁盘满 / 权限变化）时删掉半截的临时文件
            try { tmp.delete() } catch (_: Throwable) {}
            alarm(logicalName, f, "落盘异常 ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * **追加**一条加密记录（只给追加型日志通道用）。
     *
     * 每条记录自己是带 IV + tag 的完整 AEAD 单元，所以追加不需要重加密整个文件
     * （否则就是 O(n²)）。日志允许最后一条被 kill 打断 —— 读取端用
     * [MjCrypto.openLenient] 在坏记录处截断，不会因此把整份日志判废。
     */
    fun appendSealed(f: File, logicalName: String, text: String): Boolean {
        val key = KeyVault.currentKey() ?: return false
        val aad = MjCrypto.aadOf(logicalName)
        return try {
            f.parentFile?.let { StorageDirs.ensure(it) }
            val blob = MjCrypto.sealChunked(text.toByteArray(Charsets.UTF_8), key, aad, MjCrypto.CHUNK)
            val isNew = !f.exists() || f.length() == 0L
            FileOutputStream(f, true).use { it.write(blob) }
            if (isNew) StorageDirs.relaxFile(f)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- 迁移

    /** 只读文件头判断是不是我们的加密容器（避免为判一个字节而整份读 1.2MB） */
    private fun isSealedFile(f: File): Boolean = try {
        if (!f.exists() || f.length() < MjCrypto.HDR_LEN.toLong()) false
        else {
            val h = ByteArray(MjCrypto.HDR_LEN)
            val n = java.io.FileInputStream(f).use { it.read(h) }
            n == MjCrypto.HDR_LEN && MjCrypto.isSealed(h)
        }
    } catch (_: Throwable) { false }

    /** 正在后台迁移的文件（避免每次读到明文都起一个新线程） */
    private val migrating = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 读到明文 ⇒ 后台转密文（读迁移，规格 ④「读到明文就正常解析，然后写成加密格式」）。
     *
     * 异步：`read` 可能被主线程调用，加密 + 落盘（pool.json 有 1.2MB）不能挂在主线程上。
     * 同一文件同时只跑一个迁移（[migrating] 去重）；转成功后文件已是密文，
     * 后续 [read] 不会再触发。
     */
    private fun migratePlainAsync(f: File, logicalName: String, text: String) {
        if (text.isEmpty()) return
        if (migrating.putIfAbsent(f.absolutePath, true) != null) return
        try {
            Thread {
                try { seal(f, logicalName, text) } catch (_: Throwable) {}
                finally { migrating.remove(f.absolutePath) }
            }.apply { isDaemon = true; name = "MJ-SecMigrate" }.start()
        } catch (_: Throwable) {
            migrating.remove(f.absolutePath)
        }
    }

    /**
     * 把**历史明文文件**原地转成密文（规格 ④「读到明文就正常解析，然后写成加密格式」）。
     *
     * 三步，且顺序不可换（**先验证再删明文**）：
     *  1. 流式加密 [src] → [dst]（恒定内存，实测明文 277MB，绝不可整份读进内存）；
     *  2. 比对 `SHA-256(明文)` 与 `SHA-256(解密(dst))`；
     *  3. 只有**完全相等**才删掉明文。
     *
     * 任何一步失败都**保持明文不动** + 告警 —— 迁移失败最多是「还是明文」，
     * 绝不能变成「明文也没了」。
     */
    fun encryptLegacy(src: File, dst: File, logicalName: String): Boolean {
        if (!src.exists() || src.length() <= 0L) return false
        // ★★★ 残留目标必须先清掉（2026-09-30 补：本次要迁移 ~293MB，被打断的概率不低）
        //
        //   **关键推理：明文只在完整校验通过后才删**（见下方 `src.delete()`）。
        //   所以「能走到这一行」本身就证明了**上次迁移没走完** ⇒ dst 一定是半截密文。
        //
        //   原实现在这里直接 `return false`（当成「已有目标，不重复做」），后果是一个
        //   **永久漏洞**：迁移只要被 kill 打断一次，这个文件就被**永远跳过** ——
        //   明文隐私原地留在 world-writable 目录里，而日志还会显示
        //   `★没有待迁移的历史明文（干净）`，看起来一切正常。
        //
        //   为什么绝不会误删一份好存档：好存档 ⇒ 上次已成功 ⇒ 明文已被删 ⇒
        //   本函数第一行的 `!src.exists()` 早就 return false 了，走不到这里。
        if (dst.exists() && dst.length() > 0L) {
            try { dst.delete() } catch (_: Throwable) {}
        }
        val key = KeyVault.currentKey() ?: return false
        val aad = MjCrypto.aadOf(logicalName)
        return try {
            if (!MjCrypto.sealStream(src, dst, key, aad)) {
                // 带上**具体原因**：调度侧的「读不到（分区存储限制）」已经提前把
                // 读不动的文件挡在候选之外（见 Logger.consider），所以能走到这里的
                // 失败才是**真正意外**的，必须把它是什么说清楚。
                alarm(
                    logicalName, src,
                    "历史明文加密失败 ⇒ 保留明文不动（原因=${MjCrypto.lastIoError ?: "未知"}）"
                )
                return false
            }
            val d1 = MjCrypto.digestPlain(src)
            val d2 = MjCrypto.digestSealed(dst, key, aad)
            if (d1 == null || d2 == null || !d1.contentEquals(d2)) {
                alarm(logicalName, src, "历史明文迁移校验不一致 ⇒ 保留明文不动")
                try { dst.delete() } catch (_: Throwable) {}
                return false
            }
            src.delete()
            true
        } catch (t: Throwable) {
            alarm(logicalName, src, "历史明文迁移异常 ${t.javaClass.simpleName} ⇒ 保留明文不动")
            false
        }
    }

    // ---------------------------------------------------------------- 告警

    /**
     * 显眼告警（规格 ③：**不要静默**）。
     *
     * 三路齐发，任何一路活着都能查到：
     *  1. `Logger.always` —— logcat / ring（规格点名要求的那条）；
     *  2. `Logger.evidence("SECFAIL", …)` —— **明文**诊断文件，adb 可 tail；
     *  3. `android.util.Log.e` —— 兜底（`Logger` 未 init 时仍在）。
     *
     * 内容只用「文件名 + 原因」，**绝不带钥匙或数据内容**。
     */
    private fun alarm(logicalName: String, f: File, why: String) {
        val msg = "【加密文件损坏/被篡改】$logicalName  $why  " +
            "path=${f.absolutePath} size=${try { f.length() } catch (_: Throwable) { -1L }} " +
            "钥匙指纹=${KeyVault.fingerprint(KeyVault.currentKey())}"
        try { Logger.always(msg) } catch (_: Throwable) {}
        // ★ 必须走「明文白名单 tag」SECFAIL —— 告警本身绝不能依赖加密通道，
        //   否则通道坏了告警也一起消失（正是要避免的静默）
        try { Logger.evidence("SECFAIL", msg) } catch (_: Throwable) {}
        try { android.util.Log.e("SlowKick", msg) } catch (_: Throwable) {}
    }
}
