package io.github.angbang852.manjiao.util

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.io.File

object Logger {
    private const val TAG = "SlowKick"
    private var mod: XposedModule? = null
    // 性能优化-日志静默：刷屏级诊断日志（feed diag/VIEWDIAG 等）每条都是主线程
    // 字符串拼接 + JNI 写 logcat，快手滑动时每秒几十条是实打实的开销
    @Volatile var quiet: Boolean = false
    // ★ 诊断日志独立开关（2026-09 S2）：quiet 只压「刷屏功能日志」的性能税；
    // diag 单独控制重反射诊断块（feed diag/ENTSCAN/VIEWDIAG 等）。此前两套共用一个
    // 开关——排障时打开 quiet 会连重诊断一起放开、拖垮性能；解耦后可只开诊断
    @Volatile var diag: Boolean = false

    fun init(m: XposedModule) {
        mod = m
        // ★★★ 第一件事：把**历史明文**的 evidence.txt 挪出主路径（规格 ④ / ⑤）。
        //
        //   必须**同步、且早于任何 writePlain**：真机踩到的坑是——
        //   writePlain 的「超限滚动」先跑，把 277MB 历史明文改名成了 evidence.1.txt
        //   （一个「我们自己的滚动桶」名字），后台迁移线程随后只挪到了新文件（178 字节），
        //   结果 277MB 明文**原地不动**、永远没人再管它。
        //   这里有它自己写的日志为证：
        //     [SECMIGRATE] ★历史明文日志已挪到 evidence.legacy.txt（178 字节）moved=true
        //   现在把「挪走」提到最前面（只改目录项、不读内容，很快），
        //   「加密」仍留在后台（要等钥匙）。
        try { splitLegacyPlain() } catch (_: Throwable) {}
        // ★ 环形缓冲落盘（E1 崩溃存档）：后台线程每 8s 把最近日志刷到媒体目录，
        // 进程崩溃后文件仍在（≤8s 延迟窗口可接受）
        Thread {
            while (true) {
                try { Thread.sleep(8000) } catch (_: Throwable) { break }
                try { flushToFile() } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.name = "MJ-LogFlush" }.start()
        // ★★ 加密通道初始化（2026-09-30 规格 ②④）：先把钥匙生出来，再做一次
        //    「历史明文 → 密文」的迁移。两条都放后台线程，不占启动关键路径。
        try { KeyVault.ensureAsync() } catch (_: Throwable) {}
        startSecretFlusher()
        try {
            Thread {
                try { migrateLegacyOnce() } catch (_: Throwable) {}
            }.also { it.isDaemon = true; it.name = "MJ-LogMigrate" }.start()
        } catch (_: Throwable) {}
    }

    private val ring = java.util.ArrayDeque<String>()
    private val ringLock = Any()
    private fun ringAdd(l: String) {
        synchronized(ringLock) {
            ring.addLast(l)
            while (ring.size > 4000) ring.removeFirst()
        }
    }

    fun flushToFile() {
        try {
            val sb = StringBuilder()
            synchronized(ringLock) {
                for (s in ring) sb.append(s).append('\n')
            }
            val f = File("/sdcard/Android/media/io.github.angbang852.manjiao", "slowkick.log")
            f.parentFile?.mkdirs()
            f.writeText(sb.toString())
        } catch (_: Throwable) {}
    }

    fun d(msg: String) { if (quiet) return; val line = "D $msg"; ringAdd(line); try { mod?.log(Log.INFO, TAG, msg) } catch (_: Throwable) {}; try { Log.d(TAG, msg) } catch (_: Throwable) {} }
    // 惰性求值版：quiet 时不进 lambda——带反射/拼接的调用点用它可做到静默期零成本
    inline fun d(msg: () -> String) { if (quiet) return; d(msg()) }

    /**
     * 探针日志（性能专项 2026-09 实测新增）：**双重门控 quiet && diag**。
     *
     * 实测（SM_S9180，20 次信息流滑动）：LAFIND 741 行 + sanitize 194 + feed/vm/pager/NASA
     * 等反射探针合计 ~1600 行（≈80 行/秒），全部只受 quiet 门控——用户关掉静默
     * （quiet=false，排障常态）时这些行把主线程与 ring/logd 打满，gfxinfo jank
     * 14.45% → 7.45%（90th 34ms→18ms）。按项目既有的 S2 约定（重反射诊断块由
     * diag_debug 独立控制），这类调用点统一迁到本通道：
     * - quiet=true（默认）或 diag=false：连 lambda 都不进，零成本；
     * - 排障重反射诊断：quiet=false 且 diag=true 才输出。
     * 与 [d] 的区别：[d] 是「功能日志」（开关状态/命中摘要），本方法是「反射探针」。
     */
    inline fun probe(msg: () -> String) { if (quiet || !diag) return; d(msg()) }

    /** 一次性关键日志的已打印标记（进程级，按 tag 去重） */
    @PublishedApi
    internal val onceTags: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // ★ 性能修复（审阅 2026-09 · M1）：always 原先**完全不受 quiet 门控**，
    // 而全项目有 146 处调用点，其中一部分位于 hook 热路径（如 CfhCapture 的
    // DLCAP 每轮清洗都打一次）。每次调用含：字符串拼接（调用方已付）+
    // ringAdd 的 synchronized + mod.log 跨进程 IPC + Log.d。
    // 现改为：
    //   1) 纳入 quiet 门控——默认 perf_quiet=true 时零开销；
    //   2) 新增 once()，供「每进程只需打印一次」的安装报告/配置转储使用，
    //      即使 quiet=true 也保证留下证据（一次），排障不受影响。
    fun always(msg: String) { if (quiet) return; emit("A", msg) }

    /**
     * 每进程只输出一次的日志（安装摘要 / 配置转储 / 一次性探针）。
     *
     * 与 [always] 的区别：**不受 quiet 影响**，但同一 tag 在进程生命周期内
     * 只输出一次。用于「必须留下证据、又不能刷屏」的场景——排障时无需
     * 关掉 quiet 就能看到这些行。
     *
     * @param tag 去重键，同一 tag 只生效一次
     */
    fun once(tag: String, msg: String) {
        if (!onceTags.add(tag)) return
        emit("O", msg)
    }

    /**
     * [once] 的惰性求值版：**拼接与反射成本延迟到判定之后**。
     *
     * 已在 [onceTags] 里登记过 tag 时**完全不调用** [msg]——因此调用方可以放心
     * 把昂贵的诊断拼接（多次 Prefs 读 / 反射取值）写进 lambda，进程内只付一次。
     * 恰与项目既有的 `Logger.d { ... }` 惰性约定一致。
     */
    inline fun once(tag: String, msg: () -> String) {
        if (!onceTags.add(tag)) return
        emit("O", msg())
    }

    // 注：@PublishedApi——public inline 函数体不可调用 private 成员，
    // emit/onceTags 因此需要对外开放到「编译期可见、使用方不可见」的级别
    @PublishedApi
    internal fun emit(level: String, msg: String) {
        ringAdd("$level $msg")
        try { mod?.log(Log.INFO, TAG, msg) } catch (_: Throwable) {}
        try { Log.d(TAG, msg) } catch (_: Throwable) {}
    }

    fun d(t: Throwable) { try { mod?.log(Log.ERROR, TAG, "", t) } catch (_: Throwable) {} }
    fun d(msg: String, t: Throwable) { d(msg); d(t) }

    inline fun safe(tag: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { d("$tag: ${t.javaClass.simpleName}: ${t.message}") }
    }

    // ==================== 落盘证据通道（2026-09-24；2026-09-30 拆明密两路）====================
    //
    // ★★ 为什么需要它（2026-09-24 那轮排障吃到的大亏）：
    //   logcat 是 **5 MiB 环形缓冲**，而快手自身的刷屏日志极快（实测当前缓冲 63 万行）。
    //   任何几秒前写的诊断行都会被冲掉 —— 多次出现「刚才明明打出来了、现在搜不到」。
    //   本通道把关键证据**追加写入文件**，不受 logcat 缓冲影响。
    //
    // ★★★ 2026-09-30 拆成两路（用户规格 ⑤，**红线**）：
    //
    //   背景威胁：`/sdcard/Download/ManJiao/.sys/` 是 world-writable，
    //   任意 App 可**删证据**、可**读走昵称/文案**。但排障时用户和我一直用
    //   `adb tail .../evidence.txt` 看日志 —— 这条路不能断。
    //
    //   所以：
    //   · **明文通道** `evidence.txt`：只放**确定不敏感**的诊断行（计数/耗时/标签/池大小）；
    //   · **密文通道** `evidence.<进程>.sec`：昵称、文案、photoId、完整对象 dump 全进这里，
    //     AES-GCM 认证加密（见 [MjCrypto]），路径不变、内容不可读不可改。
    //
    //   ## 分流判据 = 「白名单 + 净化」双重 fail-closed
    //
    //   ① **tag 白名单**（[PLAIN_TAGS]）：**不在名单里一律进密文**。
    //      这是 fail-closed 的关键 —— 新增调用点忘了归类时，默认是「保密」而不是「泄露」。
    //   ② **内容净化**（[scrubsToPlain]）：即便 tag 在白名单里，只要正文出现
    //      双引号 / ≥15 位连续数字 / 换行 / 超长，也**改送密文**。
    //
    //   白名单不是拍脑袋定的，是从真机 277MB 历史日志里**统计出来**的：对每个 tag
    //   统计「含双引号的行数 / 总行数」，双引号是本项目打印敏感字段的统一形状
    //   （`昵称="西奥pro" 文案="#抗日" id=5215…`）。实测结果非常干净——要么 0 引号、
    //   要么几乎 100% 引号：
    //   ```
    //    0/1992  TTPPARSE-STAT      0/32155 WASH-INNER     0/1482 RENDERFILL
    //    0/340   RESPFILL           0/6806  READ-PROBE     0/2755 SRCDIRTY
    //  91005/91005 PSCAN      12593/12593 TTPPARSE-BLOCK  8374/8374 ENTRYTRACE
    //  3017/3017   ADTAG       4100/4100   QPHOTO-BLOCK    3151/3151 TTPPARSE-PASS
    //      1/1811  POOLSTORE   ← 唯一「几乎全干净、但有一条带引号」的 tag
    //   ```
    //   `POOLSTORE` 正是验证协议要看的那条（`★存盘 条目=N` / `★重启恢复 … 拉黑=M`），
    //   所以必须留在白名单；它那 1/1811 的引号行（Gson 报错里带出的 JSON 片段）
    //   恰好被判据 ② 自动挡到密文去 —— 白名单与净化配合才既保通道又保不出血。

    /** 明文诊断 tag 白名单（判据见上方统计表；**新增 tag 默认进密文**） */
    private val PLAIN_TAGS: Set<String> = setOf(
        // —— 规格 ⑤ 点名要求保留的四条 ——
        "TTPPARSE-STAT",   // 本批 收=/放行=/挡下=/耗时=（纯计数）
        "WASH-INNER",      // 进入内层 vm=X
        "RESPFILL",        // 空响应补池 数量/池大小
        "RENDERFILL",      // 渲染层补池 数量/池大小
        // —— 真机实测 0 引号的既有诊断 tag ——
        "SUPPLY", "SRCDIRTY", "HOMEPULL", "AUTOPULL", "IDXHARVEST",
        "IDXHARVEST-HOME", "HARVEST", "REFILL-DIAG", "REPOOL-SEED", "POOLALL",
        "POOLDRAIN", "POOLSTAT", "PV2-POOL", "VMSHOW-INST", "VMSHOW-CALL",
        "VMLIST-DIAG", "VMSURVEY", "RENDER-DIAG", "WASH-SKIP", "WASH-DROP",
        "NETCOST", "RESPRET", "HOMEREF", "HOMEPOOL", "READ-PROBE",
        "TTPPARSE", "TTPPARSE2", "TTPPARSE2-CALL", "TTPPARSE3", "TTPPARSE-RECV",
        "TTPPARSE-ERR", "TTPPARSE-SLOW", "SRCELEM", "GSCOLL", "FRAGFLD",
        "FRAGSEQ", "NASAHOOK", "PERIODIC", "PLIST-MISS", "WHITECALLER",
        "WHITEENTRY", "DRAMASRC", "MAININFO", "TRIPLE", "IGUARD", "NAMED",
        "ADPDUMP", "POOLDUMP", "HOMEWARM", "DLHOOK2-INST", "PV2BIND-INST",
        // 存盘/恢复统计（验证协议要看；那条罕见的带引号行由净化判据兜住）
        "POOLSTORE",
        // —— 本次新增的加密设施自身的诊断（内容只含文件名/长度/指纹，无敏感字段）——
        "KEYVAULT", "SECFAIL", "SECMIGRATE", "SECLOG",
        // —— ADPROBE：ad-detail NPE 归因探针（2026-10-01）——
        //   进白名单是**验证协议要求**：探针必须能被
        //     `adb shell grep -F 'ADPROBE' /sdcard/Download/ManJiao/.sys/evidence.txt`
        //   直接捞到，否则「崩溃时间窗 vs 模块动作」的对齐就无从谈起
        //   （fail-closed 白名单下，不在名单里的 tag 只写密文 evidence.sec，
        //    在 evidence.txt 里永远 0 行 —— 今天已被实测证伪过两次）。
        //   ★ 无敏感字段确认：该 tag 的正文只由「动作类型 + 类名 + 方法名 + 计数」拼成，
        //     类名/方法名来自 Class.getSimpleName()/Method.getName()（编译期固定标识符，
        //     不含用户数据）。判据 ② 的净化仍生效：一旦有调用点误把正文写成
        //     `昵称="…"` 形状（双引号）、含 ≥15 位连续数字（photoId）、换行或 >512 字符，
        //     该行会自动改送密文通道。
        "ADPROBE"
    )

    /** 明文单个文件上限：超出就滚动（历史明文一度涨到 **277MB**，靠行数计数是拦不住的） */
    private const val PLAIN_MAX_BYTES = 8L * 1024 * 1024

    /**
     * 密文单个文件上限（滚动保留一代）。
     *
     * 由 32MiB 降到 8MiB —— 2026-09-29 真机实测依据：
     * `evidence.sec` 实测增长 **462 B/s ≈ 39.9 MB/天**，是 `.sys` 目录里最大的增长源。
     * 该速率下 32MiB 上限**约 0.8 天**就轮转一次；而本模块有 **5 个进程**同时跑
     * （主进程 / `:messagesdk` / `:push_v3` / `:kwv_sandboxed_p0` / `:mini0`），各自持有独立的
     * `evidence[.<子进程>].sec`（见 [secretBaseName]），轮转又保留一代
     * ⇒ 理论峰值 4 × 2 × 32MiB = **256 MiB**。
     * 降到 8MiB 后稳态为 4 × 2 × 8MiB = **64 MiB**（每进程 16MiB），
     * 轮转周期约 4.8 小时，仍远大于真实排障所需的时间窗。
     *
     * ⚠️ 进程数实测为 5（含 `:mini0`），原按 4 推算，该稳态值未证实
     * （256MiB / 64MiB 均为按 4 进程的上限推算，非实测占用；
     *  `:mini0` 是否跑模块代码亦未复核）。
     * 依据：`docs\实测-广告字段只读与日志增长速率.md` §3.5。
     */
    private const val SECRET_MAX_BYTES = 8L * 1024 * 1024

    /** 密文待写缓冲上限（行）。超限说明落盘长期失败，丢最旧的以免吃内存 */
    private const val SECRET_BUFFER_MAX_LINES = 20000

    /** 密文批量落盘间隔 */
    private const val SECRET_FLUSH_MS = 2000L

    private const val SYS_DIR = "/sdcard/Download/ManJiao/.sys"

    /** 明文诊断文件（**adb tail 看的就是它**） */
    private fun plainFile(): File = File(SYS_DIR, "evidence.txt")

    private val plainLines = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var plainRotated = false

    /**
     * ★★ 密文通道的**每进程一个文件**。
     *
     * 为什么不能共用一个：真机实测快手有 **5 个进程**同时跑模块代码
     * （`com.smile.gifmaker` / `:messagesdk` / `:push_v3` / `:kwv_sandboxed_p0` / `:mini0`），
     * 它们都在追加写同一个文件。明文日志按行追加，交错写顶多是行序乱；
     * 而密文是**记录**（HDR+IV+密文+tag），两个进程交错写会把记录劈开 ⇒
     * 认证失败 ⇒ 读取端在坏记录处截断 ⇒ **把后面的日志全丢掉**，
     * 正好帮攻击者完成了「删证据」。每进程一个文件从根上消除交错。
     */
    private fun secretFile(): File = File(SYS_DIR, secretBaseName() + ".sec")

    /** 密文文件的逻辑名（同时作为 AAD，与 [secretFile] 同名域） */
    private fun secretBaseName(): String {
        val pn = procName() ?: return "evidence"
        if (!pn.contains(":")) return "evidence"
        val sub = pn.substringAfter(':').replace(Regex("[^A-Za-z0-9_]"), "_").take(24)
        return "evidence.$sub"
    }

    @Volatile private var procNameCached: String? = null

    /** 当前进程名（子进程带 `:`）。用 /proc/self/cmdline，全版本可用 */
    private fun procName(): String? {
        procNameCached?.let { return it }
        val n = try {
            val b = File("/proc/self/cmdline").readBytes()
            var e = 0
            while (e < b.size && b[e].toInt() != 0) e++
            String(b, 0, e, Charsets.UTF_8).ifBlank { null }
        } catch (_: Throwable) {
            try { android.app.Application.getProcessName() } catch (_: Throwable) { null }
        }
        if (n != null) procNameCached = n
        return n
    }

    private val secretBuf = java.util.ArrayDeque<String>()
    private val secretLock = Any()
    @Volatile private var secretFlusherStarted = false
    private val secretFail = java.util.concurrent.atomic.AtomicInteger(0)

    private fun startSecretFlusher() {
        if (secretFlusherStarted) return
        synchronized(this) {
            if (secretFlusherStarted) return
            secretFlusherStarted = true
        }
        Thread {
            while (true) {
                try { Thread.sleep(SECRET_FLUSH_MS) } catch (_: Throwable) { break }
                try { flushSecret() } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.name = "MJ-SecFlush" }.start()
    }

    /** 把缓冲的敏感行批量封成**一条记录**追加到密文文件 */
    private fun flushSecret() {
        val batch: String = synchronized(secretLock) {
            if (secretBuf.isEmpty()) return
            val sb = StringBuilder()
            while (secretBuf.isNotEmpty()) sb.append(secretBuf.removeFirst()).append('\n')
            sb.toString()
        }
        if (batch.isEmpty()) return
        val f = secretFile()
        // 尺寸滚动：保留一代（.sec → .1.sec → 丢弃）
        try {
            if (f.exists() && f.length() > SECRET_MAX_BYTES) {
                val old = File(f.parentFile, f.name + ".1")
                try { old.delete() } catch (_: Throwable) {}
                f.renameTo(old)
            }
        } catch (_: Throwable) {}
        val ok = SecureStore.appendSealed(f, secretBaseName(), batch)
        if (!ok) {
            // 落盘失败要能看见，但**绝不能反过来写明文**（那就白加密了）。
            // 这里走明文通道报一句「计数」，不带任何正文。
            val n = secretFail.incrementAndGet()
            if (n == 1 || n % 50 == 0) {
                writePlain("SECLOG", "★★敏感日志落盘失败 ${n} 次（文件=${f.name}，内容未写入明文通道）")
            }
        }
    }

    /**
     * 写一条证据（**对外唯一入口，签名未变，215 处调用点无需改动**）。
     *
     * ① logcat 照旧；
     * ② 按「白名单 + 净化」分流到明文 / 密文通道。
     *
     * 不受 quiet 门控——它的定位就是「排障时必须留下」。
     */
    fun evidence(tag: String, msg: String) {
        try {
            if (scrubsToPlain(tag, msg)) writePlain(tag, msg) else enqueueSecret(tag, msg)
        } catch (_: Throwable) {}
        // 同时打 logcat（方便实时看），但证据以文件为准
        try { emit("E", "$tag $msg") } catch (_: Throwable) {}
    }

    /**
     * 能不能进明文通道（**fail-closed：任何一条不满足都进密文**）。
     */
    private fun scrubsToPlain(tag: String, msg: String): Boolean {
        if (tag !in PLAIN_TAGS) return false
        if (msg.isEmpty()) return true
        if (msg.length > 512) return false          // 长 dump 一律保密
        if (msg.indexOf('"') >= 0) return false     // 本项目敏感字段统一形状 昵称="…" 文案="…"
        if (msg.indexOf('\n') >= 0 || msg.indexOf('\r') >= 0) return false  // 多行可夹带任意内容
        if (hasLongDigitRun(msg)) return false      // ≥15 位连续数字 ≈ photoId
        return true
    }

    /** 是否含 ≥15 位连续数字（photoId 形状；时间戳 13 位、字节数 7 位都不会误伤） */
    private fun hasLongDigitRun(s: String): Boolean {
        var run = 0
        for (c in s) {
            if (c in '0'..'9') {
                run++
                if (run >= 15) return true
            } else {
                run = 0
            }
        }
        return false
    }

    // ---------------------------------------------------------------- 明文通道

    @PublishedApi
    internal fun writePlain(tag: String, msg: String) {
        try {
            val f = plainFile()
            if (!plainRotated) {
                plainRotated = true
                try { f.parentFile?.mkdirs() } catch (_: Throwable) {}
            }
            try {
                if (f.exists() && f.length() > PLAIN_MAX_BYTES) rotatePlain(f)
            } catch (_: Throwable) {}
            f.appendText("${System.currentTimeMillis()} [$tag] $msg\n")
        } catch (_: Throwable) {}
    }

    /**
     * 明文日志超限滚动。
     *
     * ★★★ 必须按**内容**决定往哪儿滚（真机事故的直接修复）：
     *   · 是**我们自己写的**（首行形如 `<十几位时间戳> [TAG] …`）⇒ 它本来就只含
     *     非敏感诊断，滚到 `evidence.1.txt` 覆盖上一代即可；
     *   · **不是我们写的**（历史明文，全是昵称/文案）⇒ **绝不能当成可丢弃的滚动桶**！
     *     必须挪去 `evidence.legacy.txt` 交给加密器。
     *
     *   原先无条件滚到 `evidence.1.txt`，结果把 277MB 历史明文改成了一个
     *   **看起来像我们自己滚动日志**的名字 ⇒ 迁移器按名字找 `evidence.legacy.txt`
     *   找不到它，加密器也永远不会碰它：277MB 隐私数据就那样留在公共目录里。
     */
    private fun rotatePlain(f: File) {
        if (isOurLogFile(f)) {
            val old = File(f.parentFile, "evidence.1.txt")
            try { old.delete() } catch (_: Throwable) {}
            f.renameTo(old)
        } else {
            val dst = File(f.parentFile, "evidence.legacy.txt")
            if (dst.exists()) return          // 已有历史文件：宁可不滚，也绝不覆盖
            f.renameTo(dst)
        }
        plainLines.set(0)
    }

    // ------------------------------------------------- 模块自建诊断文件的「带上限追加」

    /**
     * **带上限的追加**（2026-10 收尾）：给「模块自己创建的诊断文件」补上体积上限 + 轮转。
     *
     * ## 为什么要有它
     *
     * 审计实测（2026-09-29）发现三个文件是**纯 append、无上限、无轮转**：
     *   · `boot_diag.txt`（[io.github.angbang852.manjiao.Module] 的 `diagWrite`）
     *   · `probe_noloop.txt` / `probe_playurl.txt`（[io.github.angbang852.manjiao.hook.NoLoopGuard]）
     * 它们都落在 `/sdcard/Download/ManJiao/.sys/`（world-writable 公共目录）——
     * 无上限 ⇒ 随冷启/播放次数**单调增长**，既吃存储又扩大可被任意 App 读走的证据面。
     *
     * ## 为什么是复用而不是新造
     *
     * 轮转语义**完全照抄本文件既有的 [rotatePlain] / [flushSecret] 约定**：
     * 「当前文件 = 最新一代，`.1` = 上一代，只保留一代」。因此**读最近记录永远读当前文件**
     * —— 轮转不会把最近的记录挤走（诊断价值在最近，这是本方法的设计前提）。
     *
     * ## 与 [rotatePlain] 的差别（为什么不照抄它的内容判据）
     *
     * [rotatePlain] 必须按**内容**判归属，是因为 `evidence.txt` 这个名字上曾经躺着
     * 277MB **历史明文隐私**（详见 [rotatePlain] 的真机事故注释）。
     * 而本方法的三个文件名（`boot_diag.txt` / `probe_*.txt`）是**模块私有名**：
     * 历史明文只用过 `evidence*`（见 [LEGACY_NAMES]），这三个名字上**不可能**躺着别人的数据。
     * ⇒ 这里可以无条件按体积轮转，不会重演那次事故。
     *
     * ## 多进程并发
     *
     * 快手有多个进程会调到这里（主进程 / 子进程）。两进程同时触发轮转时，
     * 后一个 `renameTo` 会**静默失败**（源已被搬走），随后照常追加到新文件 ——
     * 最坏结果是**少留一代**，不会写坏数据，也不会丢「当前一代」。
     *
     * @param f 目标文件（调用方负责 `parentFile.mkdirs()`）
     * @param text 要追加的**完整一行**（含换行）
     * @param maxBytes 该文件的上限；取值依据写在**每个调用点**（各文件速率不同）
     */
    fun appendCapped(f: File, text: String, maxBytes: Long) {
        try {
            try {
                if (f.exists() && f.length() > maxBytes) rotateCapped(f)
            } catch (_: Throwable) {}
            f.appendText(text)
        } catch (_: Throwable) {}
    }

    /**
     * [appendCapped] 的轮转：当前文件 → `<名字>.1`（先删旧 `.1`，只保留一代）。
     *
     * 与 [flushSecret] 的 `.1` 后缀写法一致 —— 本项目所有滚动桶都用「原文件名 + .1」，
     * 不引入第二套命名。全部操作容错：`/sdcard/`（FUSE/sdcardfs）上 `renameTo`
     * 历史上**静默失败过**（见 `SecureStore` 类注释），失败时**宁可不轮转也不丢行**。
     */
    private fun rotateCapped(f: File) {
        try {
            val old = File(f.parentFile, f.name + ".1")
            try { old.delete() } catch (_: Throwable) {}
            f.renameTo(old)
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- 密文通道

    @PublishedApi
    internal fun enqueueSecret(tag: String, msg: String) {
        if (msg.indexOf('\u0000') >= 0) return      // 极端防御：控制字符不入库
        synchronized(secretLock) {
            if (secretBuf.size >= SECRET_BUFFER_MAX_LINES) secretBuf.removeFirst()
            secretBuf.addLast("${System.currentTimeMillis()} [$tag] $msg")
        }
        startSecretFlusher()
    }

    // ---------------------------------------------------------------- 历史明文迁移

    /**
     * 把**历史明文** evidence.txt 挪出主路径（只改目录项，毫秒级，不需要钥匙）。
     *
     * 为什么必须「挪走」而不是只把新行分流：历史文件实测 **277MB**，里面**全是**
     * 昵称/文案/photoId（`PSCAN`、`TTPPARSE-PASS`、`ADTAG` 一条不落）。
     * 老文件留在原路径上，攻击者照样读得到 —— 只保护新写入的行等于没保护。
     *
     * ★★★ 真机事故（本函数存在的直接原因）：
     *   原实现把「挪走」放在**后台线程、且要等钥匙**（最多 30s）。而
     *   [writePlain] 的「超限滚动」立刻就会跑 —— 它先看到 277MB 的 evidence.txt，
     *   按老逻辑把它改名成了 `evidence.1.txt`（一个**看起来像我们自己滚动桶**的名字）。
     *   后台迁移随后只挪到了新建的小文件，日志为证：
     *     [SECMIGRATE] ★历史明文日志已挪到 evidence.legacy.txt（178 字节）moved=true
     *   ⇒ 277MB 明文原地不动，而且**再也不会有人管它**。
     *   所以现在：① 挪走改成同步、在 [init] 最前面执行；② 滚动按**内容**判断归属
     *   （见 [rotatePlain]），历史明文永远不会被当成可丢弃的滚动桶。
     */
    private fun splitLegacyPlain() {
        try {
            val plain = plainFile()
            if (!plain.exists() || plain.length() <= PLAIN_MAX_BYTES) return
            if (isOurLogFile(plain)) return                     // 已经是我们自己的日志
            val dst = File(plain.parentFile, "evidence.legacy.txt")
            if (dst.exists()) return                            // 已有历史文件，绝不覆盖
            plain.renameTo(dst)
            plainLines.set(0)
        } catch (_: Throwable) {}
    }

    /** 首行是不是「我们自己的诊断日志」格式：`<十几位时间戳> [TAG] …` */
    private fun isOurLogFile(f: File): Boolean = try {
        isOurLine(f.bufferedReader().use { it.readLine() } ?: "")
    } catch (_: Throwable) { false }

    private fun isOurLine(s: String): Boolean {
        var i = 0
        while (i < s.length && s[i] in '0'..'9') i++
        return i >= 10 && i < s.length && s[i] == ' '
    }

    private fun migrateLegacyOnce() {
        // 子进程不做历史明文加密：5 个进程（含 `:mini0`）抢同一批文件会互相覆盖/半写。
        // 由主进程统一处理（数据文件本身也只在主进程读写）。
        val pn = procName()
        if (pn != null && pn.contains(":")) return
        // 等钥匙就绪（最多 ~30s）——没有钥匙就没法加密
        var waited = 0
        while (KeyVault.currentKey() == null && waited < 30_000) {
            try { Thread.sleep(500) } catch (_: Throwable) { break }
            waited += 500
        }
        if (KeyVault.currentKey() == null) {
            writePlain("SECMIGRATE", "★钥匙未就绪，历史明文迁移跳过（明文暂留，下次启动重试）")
            return
        }
        try {
            val list = legacyCandidates()
            if (list.isEmpty()) {
                writePlain("SECMIGRATE", "★没有待迁移的历史明文（干净）")
                return
            }
            writePlain(
                "SECMIGRATE",
                "★待迁移历史明文 ${list.size} 个：" + list.joinToString(",") { it.name }
            )
            for (src in list) {
                val dst = File(src.parentFile, src.name + ".sec")
                val ok = SecureStore.encryptLegacy(src, dst, src.name)
                writePlain(
                    "SECMIGRATE",
                    if (ok) "★历史明文 ${src.name} 已加密并删除明文（→ ${dst.name}）"
                    else "★★历史明文 ${src.name} 加密未完成 ⇒ 明文保留原样（不删除，避免丢证据）"
                )
            }
        } catch (t: Throwable) {
            writePlain("SECMIGRATE", "★★历史明文迁移异常 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 找出所有需要迁移的**历史明文**日志文件。
     *
     * 判据 = 名字 + **内容**，**不依赖任何标记文件**（标记文件会被多进程/异常打断搞乱）：
     *   · 名字以 `evidence` 开头、以 `.txt` 结尾（`evidence.txt.gz` 这类跳过）
     *   · 不是当前正在写的 `evidence.txt`
     *   · 体积 ≥ 1KB（太小的不值得起线程）
     *   · **需要加密** = 「不是我们的格式」（历史遗留，内容不可知 ⇒ 保守加密）
     *     或「采样判定含敏感行」（见 [hasSensitiveSample]）
     *
     * 这条规则一并覆盖了历史遗留的一堆备份（`evidence.prev.txt` / `evidence.bak.txt` /
     * `evidence.r1.txt` / `evidence.precow.txt` …）以及本次事故里被误滚成
     * `evidence.1.txt` 的 277MB —— 它们全都是**躺在 world-writable 公共目录里的
     * 明文隐私**，必须一并收进密文（规格 ④）。
     */
    private fun legacyCandidates(): List<File> {
        val out = ArrayList<File>()
        val live = plainFile().name
        // ★★★ 列目录**之前**必须先用 [StorageDirs.ensure] 放宽目录权限（真机实证的根因）
        //
        //   2026-09-30 真机实测：`.sys` 是 `drwxrws--- u0_a224 media_rw`（**2770**）——
        //   **没有 other 位**，而快手进程不在 `media_rw` 组。于是同一份权限下出现
        //   两种截然不同的行为，极具欺骗性：
        //     · 按**绝对路径**读写文件 → 正常（只需要目录的 x 位）
        //     · `listFiles()` 列目录     → 需要目录的 r 位 ⇒ **静默返回空/null**
        //   后果：迁移器报 `★没有待迁移的历史明文（干净）`，而一整个目录的明文隐私
        //   一条都扫不到 —— 「加密做了等于没做」，且日志上完全看不出来。
        //   真机两次冷启的对照正是这么来的：
        //     · 17:07 那次：别的组件（AuditMirror/KsCache 都会调 ensure）刚好把目录
        //       放宽过 ⇒ 列目录成功 ⇒ 扫到 1 个并加密（evidence.1.txt）；
        //     · 17:14 那次：目录又回到 2770 ⇒ 扫到 **0** 个，报「干净」。
        //   所以这里照抄本项目既有惯用法先 ensure（[io.github.angbang852.manjiao.data.AuditMirror]
        //   与 [io.github.angbang852.manjiao.adapt.KsCache] 都是这么做的）。
        val dir = try {
            io.github.angbang852.manjiao.data.StorageDirs.ensure(File(SYS_DIR))
        } catch (_: Throwable) { File(SYS_DIR) }
        val fs = try { dir.listFiles() } catch (_: Throwable) { null }
        if (fs == null) {
            // ★ 列不出来必须**大声说**，不能退化成「干净」（那正是本次 bug 的形态）
            skipNote(SYS_DIR, "★★目录无法列举（listFiles=null）⇒ 本次扫不到历史明文，下次重试")
            return out
        }
        val seen = HashSet<String>()
        for (f in fs) {
            val n = f.name
            seen.add(n)
            if (!n.startsWith("evidence") || !n.endsWith(".txt")) continue
            if (n == live) continue                     // 正在写的那个
            consider(f, n, out)
        }
        // ★★★ 枚举之外的**按名兜底通道**（2026-09-30 真机实证的必需项）
        //
        //   真机实测：`.sys` 里 `ls` 有 **119 项**，`listFiles()` 只回来 **15 项**
        //   （全是本进程近期自己写的）；09-25/09-26 那批历史明文
        //   （evidence.prev.txt / evidence.bak.txt / …）**一个都不在返回里**。
        //
        //   根因（已定案，非猜测）——**分区存储的过滤视图**：
        //     快手 `MANAGE_EXTERNAL_STORAGE: granted=false`（没有 All-Files 访问），
        //     只有 legacy `READ/WRITE_EXTERNAL_STORAGE: granted=true`。
        //     Android 11+ 在这种组合下给 App 的是**过滤视图**：它只能**看到/读写
        //     自己创建的**文件；别的包（旧 uid / 旧版本 / 模块 app）创建的文件
        //     既**不出现在 readdir 里**，`open()` 也会被**直接拒绝**。
        //   这**一条规则同时解释了三件事**（都不需要再假设别的机制）：
        //     · 119 项只列出 15 项；
        //     · 这 8 个文件 `length()` 能 stat 出来（13497377 等真实长度），
        //       但 `FileInputStream` 立刻抛异常 ⇒ 加密 21ms 就失败；
        //     · 17:07 那次 `evidence.1.txt` 能迁移成功 —— 它是**快手自己**
        //       由 [rotatePlain] 滚出来的文件，属于「自己创建的」，因此**读得动**。
        //
        //   ⇒ 所以这批文件**模块侧永远迁移不了**，代码修不好（见 [consider] 的
        //     「读不到」分支：记一条非告警说明后跳过，不再拿它去撞加密刷假告警）。
        //   仍然保留名字探测的理由有两个，都是**如实报告**而非指望成功：
        //     1. 万一用户给快手开了 All-Files 访问，它们就能被正常收进密文；
        //     2. 迁移器必须**说出**「还有 N 个明文文件我动不了」，不能像本次事故
        //        那样用一句「干净」把一整目录的明文隐私盖过去。
        for (n in LEGACY_NAMES) {
            if (n == live || seen.contains(n)) continue
            val f = File(dir, n)
            consider(f, n, out)
        }
        // 汇总：让「扫到几个」永远可见（本次 bug 的另一半就是这个数字说不出话）
        skipNote(SYS_DIR, "列举 ${fs.size} 项 ⇒ 命中待加密 ${
            out.size
        } 个：${if (out.isEmpty()) "(无)" else out.joinToString(",") { it.name }}")
        return out
    }

    /**
     * 本项目**自己产生过**的历史明文日志名（枚举不可达时的按名兜底，见 [legacyCandidates]）。
     *
     * 全部来自本文件的滚动/迁移逻辑（[rotatePlain] / [splitLegacyPlain] /
     * [migrateLegacyOnce]）与真机实测目录，不是对外部文件名的猜测。
     * 每一项都在真机上被抽样确认过**含敏感行**（昵称/文案/photoId）才列进来。
     */
    private val LEGACY_NAMES = arrayOf(
        "evidence.1.txt", "evidence.2.txt", "evidence.legacy.txt",
        "evidence.prev.txt", "evidence.precow.txt",
        "evidence.bak.txt", "evidence.bak2.txt", "evidence.3x.bak.txt",
        "evidence.r1.txt", "evidence.r2prefix.txt", "evidence.r3.txt",
    )

    /**
     * 对**单个**候选文件做判定并（必要时）纳入迁移 —— 枚举路径与按名兜底共用。
     *
     * 每一档跳过都必须留下**可读的原因**：本次修的那个 bug（~293MB 明文被当
     * 「干净」跳过）之所以能在真机上潜伏，就是因为**跳过是静默的** ——
     * 迁移器只说一句「没有待迁移的历史明文（干净）」，看日志的人无法分辨
     * 「真的干净」还是「判据错了」。
     */
    private fun consider(f: File, n: String, out: MutableList<File>) {
        if (!f.exists()) return                         // 探测路径上「不存在」是常态，不记
        val len = try { f.length() } catch (_: Throwable) { -1L }
        if (!f.isFile) {
            skipNote(n, "跳过：不是普通文件 len=$len")
            return
        }
        if (len < 1024L) {
            skipNote(n, "跳过：体积过小或 stat 拿不到长度 len=$len < 1024")
            return
        }
        val ours = isOurLogFile(f)
        val sample = readSample(f)
        if (sample == null) {
            // ★★★ 「读不到内容」= **分区存储的平台边界**，不是篡改，也不是判据问题。
            //
            //   真机实证：这 8 个历史明文由**别的包/旧 uid** 创建，而快手
            //   `MANAGE_EXTERNAL_STORAGE: granted=false`（无 All-Files 访问）⇒
            //   平台直接拒绝 `open()`。它们能 stat（len 是真实值）、能 `listFiles`
            //   之外按名点到，却**读不出一个字节**。
            //
            //   为什么这里**跳过**而不是像原来那样「读失败就当成敏感、硬试加密」：
            //   加密**必须读同一份源文件**，读不动 ⇒ 加密必然失败。硬试的唯一下场
            //   就是真机实测到的那一幕：**8 个文件 × 每个进程 × 每次冷启**
            //   刷 24 条 `SECFAIL【加密文件损坏/被篡改】` —— 把「平台不给我读」
            //   谎报成「文件被篡改」，既误导排查，也淹掉真正的告警。
            //
            //   fail-closed 不受影响：**绝不删、绝不假装成功**，只是如实记下
            //   「这个明文我动不了」，并保留在候选之外。
            skipNote(
                n,
                "跳过：读不到内容（分区存储限制/无 All-Files 访问，非篡改）⇒ " +
                    "明文保留，模块侧无法迁移 len=$len 我们格式=$ours"
            )
            return
        }
        val sens = hasSensitiveSample(sample)
        if (ours && !sens) {
            skipNote(n, "跳过：判定为「我们自己的非敏感诊断」 len=$len 我们格式=$ours 敏感=$sens")
            return
        }
        skipNote(n, "⇒ 纳入迁移 len=$len 我们格式=$ours 敏感=$sens")
        out.add(f)
    }

    /** 迁移扫描的逐文件判定记录（明文通道；只含文件名/长度/布尔，无任何正文） */
    private fun skipNote(name: String, why: String) {
        try { writePlain("SECMIGRATE", "★扫描 $name：$why") } catch (_: Throwable) {}
    }

    /** 敏感行采样长度：迁移本身还要整份流式读一遍，这里再整份读就是白翻一倍 IO */
    private const val SENSITIVE_SAMPLE_BYTES = 256 * 1024

    /**
     * ★★★ 采样判断「这份明文日志里到底有没有敏感行」（2026-09-30 真机事故的直接修复）
     *
     * ## 为什么必须有它（原 `isOurLogFile` 判据的真机反例）
     *
     * 真机实测：`evidence.1.txt` 277MB，**光开头 20MB 就有 74727 行 `昵称="…"`、
     * 75278 行 `cap="…"`**（还有 79227 行含 ≥15 位 photoId），而迁移器的结论是
     * `★没有待迁移的历史明文（干净）`—— **假阴性**。
     *
     * 根因：`isOurLogFile` 判的是**首行格式**（`<ms> [TAG] …`），而「历史明文」
     * 与「现役明文」用的**就是同一个格式**（本模块从来只写这一种）。所以这个判据
     * 在真机上**恒为 true** ⇒ 所有历史明文都被当成「我们自己滚动的、本就非敏感的
     * 诊断」而跳过。加密做了，等于没做。
     *
     * ## 判据为什么直接复用写入侧的 [scrubsToPlain]
     *
     * 语义上「这一行当初会不会进密文通道」**就等于**「它该不该被加密」。
     * 所以这里把每行拆成 `TAG` + 正文后**直接调用同一个 [scrubsToPlain]**，
     * 而不是另抄一份规则 —— 抄一份副本迟早会和写入侧漂移，漂移就意味着再次漏掉。
     * 白名单里新增一个 tag，两边同时生效。
     *
     * ## 采样而不是整份读
     *
     * 只读**开头** [SENSITIVE_SAMPLE_BYTES]（256KB ≈ 上千行）。为什么够：日志是
     * **按时间追加**的，敏感/不敏感的分界是「分流功能上线的那一刻」，跨界的文件
     * 只会在分界处有极短一段混合，上千行足以判定。真机样本实测敏感行占 69%，
     * 判定余量极大。
     *
     * ## 判据为什么不是「读不动就算敏感」（2026-09-30 真机修正）
     *
     * 初版把「读失败」也判成敏感（宁可多加密一次）。真机证明这条是**错的**：
     * 分区存储下这批历史明文**就是读不动**（见 [consider]），于是它每次都进候选、
     * 每次加密都在 21ms 内失败，每个进程每次冷启刷 8 条假 `SECFAIL【被篡改】`。
     * 加密必须读同一份源文件 ⇒ **读不动就等于迁移不了**，硬试只是噪声。
     * 现在：读不动 ⇒ [readSample] 返回 null ⇒ 如实记一条非告警说明并跳过。
     *
     * @return true = 采样里发现了敏感行（**该加密**）。只在**读到了内容**时才有意义。
     */
    private fun readSample(f: File): String? = try {
        java.io.FileInputStream(f).use { ins ->
            val buf = ByteArray(SENSITIVE_SAMPLE_BYTES)
            var n = 0
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null        // ★ 读不到 = 平台不给读（见 [consider]），**不等于**「不敏感」
    }

    /** 采样里有没有敏感行（[readSample] 拿到内容后调用） */
    private fun hasSensitiveSample(text: String): Boolean {
        val n = text.length
        var start = 0
        var i = 0
        // i == n 时也进来一次，处理没有结尾换行的最后一行
        while (i <= n) {
            if (i == n || text[i] == '\n') {
                if (i > start) {
                    val line = text.substring(start, i)
                    if (!scrubsToPlain(tagOfLine(line), msgOfLine(line))) return true
                }
                start = i + 1
            }
            i++
        }
        return false
    }

    /** 从一整行日志里取出 TAG（`<ms> [TAG] msg` → `TAG`）；取不到返回 `""` */
    private fun tagOfLine(line: String): String {
        val a = line.indexOf('[')
        if (a < 0) return ""
        val b = line.indexOf(']', a + 1)
        if (b <= a) return ""
        return line.substring(a + 1, b)
    }

    /**
     * 从一整行日志里取出正文（`<ms> [TAG] msg` → `msg`）；取不到返回整行。
     *
     * ★ 必须把 `<ms> ` 时间戳前缀剥掉再判：否则 13 位毫秒时间戳会进入
     *   [hasLongDigitRun] 的判定视野（虽然 13 < 15 不会误伤，但语义上
     *   「时间戳」本来就不是正文的一部分，混在一起早晚出错）。
     */
    private fun msgOfLine(line: String): String {
        val a = line.indexOf('[')
        if (a < 0) return line
        val b = line.indexOf(']', a + 1)
        if (b <= a) return line
        return line.substring(b + 1).trim()
    }
}
