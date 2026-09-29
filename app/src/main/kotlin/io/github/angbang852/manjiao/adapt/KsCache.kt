package io.github.angbang852.manjiao.adapt

import io.github.angbang852.manjiao.data.StorageDirs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.SecureStore
import java.io.File

/**
 * 解析结果的**跨进程持久化缓存**（2026-09-23，用户提出）。
 *
 * ## 为什么需要它
 *
 * 原先 [KsResolve] 的缓存是进程内 `ConcurrentHashMap`，而快手进程一退出就全丢。
 * 实测后果（14.8.20.50218，两次独立冷启动）：
 *
 * ```
 * 第 1 次冷启动：RESOLVE knhb: FAILED ... took=1170ms
 * 第 2 次冷启动：RESOLVE knhb: FAILED ... took=1102ms
 * ```
 *
 * **每次都重算，每次都在主线程上花 1 秒**。而绝大多数情况下结果根本不会变
 * —— 同一个版本、同一个模块，类名与结构是固定的。
 *
 * 用户原话：「首次应该都解析明白了，后续就应该固定不要重复操作了，
 * 直到快手更新或模块更新了才需要再次解析。」——这正是本类实现的语义。
 *
 * ## 失效策略
 *
 * 缓存整体绑定一个 **[签名]**（signature），由三部分合成：
 *
 * ```
 * 快手版本名 | 模块 versionCode | 缓存格式版本
 * ```
 *
 * 任一变化 → 整份缓存作废、重新解析。这覆盖了用户说的两个场景：
 * - **快手更新** → 版本名变 → 失效
 * - **模块更新** → versionCode 变 → 失效
 *
 * 第三项（[FORMAT_VERSION]）是给未来用的：若缓存结构本身升级，
 * 改这个常量即可让所有旧缓存作废，不需要用户清数据。
 *
 * ## 为什么不用 Prefs
 *
 * [io.github.angbang852.manjiao.data.Prefs] 在 remote 模式下 `setStr` 只写内存
 * cache、不落盘（见 Prefs.kt:412），拿不到跨进程重启的持久性。因此这里直接
 * 读写文件，路径复用项目已验证可写的 `StorageDirs.sysDirFixed()`
 * （AuditMirror 的权限自检 `canRead=true canWrite=true` 已验证）。
 *
 * ## 失败即降级
 *
 * 本类的任何异常都被吞掉并视为「无缓存」——**缓存永远是加速手段，不是必需品**。
 * 读不到就重新解析，写不进就下次再写，绝不因为缓存问题影响解析本身。
 */
object KsCache {

    /** 缓存文件（与 audit_mirror.json 同目录：`/sdcard/Download/ManJiao/.sys/`） */
    private const val FILE_NAME = "adapt_cache.txt"

    /** 缓存格式版本 —— 解析结果的结构变化时递增，可让全部旧缓存作废 */
    private const val FORMAT_VERSION = 1

    /** 缓存条目上限（防止异常情况下文件无限增长） */
    private const val MAX_ENTRIES = 200

    private fun fileOf(): File = File(StorageDirs.sysDirFixed(), FILE_NAME)

    /**
     * 当前签名 —— 缓存有效性的唯一依据。
     *
     * 合成顺序：快手版本 | 模块版本 | 格式版本。
     * 版本名取 [KsVersion.raw]（探测成功时才有值）；探测失败则为空串，
     * 此时签名退化为「模块版本 + 格式版本」——**仍然有效**：
     * 同一模块在同一台设备上的解析结果依旧可复用，只是快手升级时无法自动失效。
     * 这是有意的取舍：宁可偶尔多算一次，也不要因为探测失败就完全放弃缓存。
     *
     * ★★ 写入闸门（2026-09-23，避免缓存被「半成品签名」污染）：
     *   [KsVersion.probe] 在 `onPackageLoaded` 早期执行，而版本号可能要到
     *   `Application.onCreate` 的 `refine()` 才补上。若在补上**之前**就把解析结果
     *   连同「版本为空」的签名写进文件，那么：
     *   - 本次进程：后续读到的签名与写入时一致，看似正常
     *   - 下次进程：refine() 后版本号有了 → 签名不同 → **整份缓存作废**
     *   结果是「写了却永远用不上」。
     *
     *   因此只在签名**已含版本号**时才写盘（见 [isWritable]）；
     *   版本未知时仍可读、可用内存缓存，只是不落盘。
     */
    private fun signature(moduleVersionCode: Int): String =
        listOf(KsVersion.raw ?: "", moduleVersionCode.toString(), FORMAT_VERSION.toString())
            .joinToString("|")

    /**
     * 当前签名是否**够格写盘**（即版本号已知）。
     *
     * 版本未知时写入的缓存下次必然因签名变化而失效，等于白写 —— 故不写。
     * 读取不受此限制（读到什么算什么）。
     */
    fun isWritable(): Boolean = !KsVersion.raw.isNullOrBlank()

    /**
     * 供 [KsResolve] 调用的签名入口。
     */
    @Volatile
    private var cachedSig: String? = null

    fun signatureOf(): String {
        cachedSig?.let { return it }
        val code = try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
            app?.packageManager?.getPackageInfo(app.packageName, 0)?.let {
                @Suppress("DEPRECATION")
                it.versionCode
            } ?: -1
        } catch (_: Throwable) { -1 }
        val s = signature(code)
        cachedSig = s
        return s
    }

    /**
     * 内存镜像 —— 避免每次解析都读文件。
     *
     * 文件只在**进程首次访问**时读一次，之后全走这个 map；
     * 写入是异步的（见 [put]），不阻塞调用方。
     */
    @Volatile
    private var mem: MutableMap<String, String>? = null

    /** 本次进程加载时使用的签名（用于写入时的一致性校验） */
    @Volatile
    private var loadedSig: String? = null

    /** 缓存文件里记录的签名（用于判断是否需要整份作废） */
    private const val SIG_LINE_PREFIX = "#sig="

    /**
     * 读取某目标的缓存结果。
     *
     * 注：不需要 `id` 参数 —— [key] 已含目标标识与 classloader 身份
     * （`id@loaderHash`，见 `KsResolve.cacheKey`），再加一个会形成两份真相。
     *
     * @param key      完整缓存键（含 classloader 身份）
     * @param sig      当前签名
     * @return 缓存的类名（**空串表示「已确认找不到」**），或 null（无缓存/失效/读取失败）
     */
    fun get(key: String, sig: String): String? {
        val m = ensureLoaded(sig) ?: return null
        // 空串是**「确认找不到」的负缓存**，同样有效 —— 它能省下重扫的 1 秒
        return m[key]
    }

    /**
     * 写入某目标的解析结果（异步落盘）。
     *
     * 同样不需要 `id`（见 [get]）。
     *
     * @param value 命中的类名；**null 表示「已确认找不到」**（负缓存）
     */
    fun put(key: String, sig: String, value: String?) {
        // ★ 写入闸门：版本号未知时不落盘（否则下次签名变化必失效，白写）。
        //   仍写内存 map，让本次进程后续查询受益。
        val writable = isWritable()
        val m = ensureLoaded(sig) ?: return
        val v = value ?: ""
        if (m[key] == v) return          // 无变化不写盘
        m[key] = v
        if (writable) scheduleFlush(sig)
    }

    /**
     * 载入缓存（进程内只执行一次）。
     *
     * 签名不匹配时**不载入**（返回空 map 但非 null），下次写盘会覆盖成新签名。
     */
    private fun ensureLoaded(sig: String): MutableMap<String, String>? {
        mem?.let { return it }
        synchronized(this) {
            mem?.let { return it }
            val out = java.util.concurrent.ConcurrentHashMap<String, String>()
            try {
                val f = fileOf()
                // ★ 读前先放宽权限（2026-09-23 实测踩到）：
                //   文件一旦被**其它 uid** 写过（如 adb shell 的 sed/echo、
                //   文件管理器另存），属主与 mode 会变，快手进程再读就是
                //   `EACCES (Permission denied)` —— 于是**永久读不到缓存**，
                //   每次冷启动都重新解析，且毫无提示。
                //   这与 AuditMirror 的处理一致（见 StorageDirs.relaxFile 的注释）。
                if (f.exists()) {
                    try { StorageDirs.relaxFile(f) } catch (_: Throwable) {}
                }
                // ★★★ 2026-09-30 加密读取（规格 ①③④）：不再直接 readLines，
                //   统一走 SecureStore —— 明文旧文件照常解析（透明迁移，规格 ④），
                //   密文用 AES-GCM 解开（顺带校验完整性，改一位就读不出来）。
                val cr = SecureStore.read(f, FILE_NAME)
                val ctxt = cr.text
                if (!ctxt.isNullOrBlank()) {
                    var fileSig: String? = null
                    for (line in ctxt.lineSequence()) {
                        if (line.startsWith(SIG_LINE_PREFIX)) { fileSig = line.substring(SIG_LINE_PREFIX.length); continue }
                        val eq = line.indexOf('=')
                        if (eq <= 0) continue
                        out[line.substring(0, eq)] = line.substring(eq + 1)
                    }
                    if (fileSig == sig) {
                        loadedSig = sig
                        mem = out
                        Logger.once("adapt.cache.hit", "ADCACHE loaded ${out.size} entries (sig=$sig)")
                        return out
                    }
                    // 签名变化 → 整份作废：这正是「快手更新 / 模块更新」的生效点
                    Logger.once("adapt.cache.stale", "ADCACHE stale (file=$fileSig now=$sig) → 重新解析")
                    out.clear()
                } else if (cr.failed) {
                    // ★ fail-closed（规格 ③）：解不开 ⇒「当作没有这条配置」，内存重建即可
                    //   （缓存本身可丢弃，功能不受影响，**过滤判定不受它影响**）。
                    // ★★ 刻意**不删文件**（旧实现在这里 delete）：加密之后删掉它，
                    //   正好替攻击者完成「删证据」；留着还能靠 .bak 或人工恢复。
                    Logger.once("adapt.cache.secfail", "ADCACHE 解密失败(${cr.detail}) → 内存重建，文件保留")
                } else {
                    Logger.once("adapt.cache.miss", "ADCACHE no file yet → 首次解析")
                }
            } catch (t: Throwable) {
                // ★ 2026-09-30 改为**不删文件**：读失败（EACCES / 被篡改）时删掉，
                //   等于把「删证据」这件事替攻击者做了。只在内存里重建。
                Logger.once("adapt.cache.err", "ADCACHE load fail: ${t.message} → 内存重建（文件保留）")
            }
            loadedSig = sig
            mem = out
            return out
        }
    }

    /** 异步落盘（去抖：多个目标解析完后合并成一次写） */
    private val flushPending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun scheduleFlush(sig: String) {
        if (!flushPending.compareAndSet(false, true)) return
        Thread {
            try {
                // 轻微延迟：一次装钩会连续写多个目标，合并成一次写盘
                Thread.sleep(500)
                flushNow(sig)
            } catch (_: Throwable) {
            } finally {
                flushPending.set(false)
            }
        }.apply { name = "MJ-AdaptCache"; isDaemon = true }.start()
    }

    /** 立即落盘（也供测试/排障调用） */
    fun flushNow(sig: String) {
        try {
            val m = mem ?: return
            val dir = StorageDirs.sysDirFixed()
            StorageDirs.ensure(dir)
            val f = fileOf()
            val sb = StringBuilder()
            sb.append(SIG_LINE_PREFIX).append(sig).append('\n')
            var n = 0
            for ((k, v) in m) {
                if (n++ >= MAX_ENTRIES) break
                sb.append(k).append('=').append(v).append('\n')
            }
            val text = sb.toString()

            // ★★★ 2026-09-30 加密落盘（规格 ①③④）
            //
            //   改用 SecureStore.seal —— 它内部就是原本这套「写 .tmp → 原子改名 →
            //   失败退化直写」的分层退化（2026-09-23 在 `/sdcard`(FUSE) 上实测
            //   `renameTo` 会**静默失败**，所以退化路径必须保留），
            //   并且额外做了三件事：**AES-GCM 加密**、写完**读回校验**、保留 `.bak`。
            //   `StorageDirs.relaxFile(f)` 也由它内部处理，此处不再重复调用。
            val ok = SecureStore.seal(f, FILE_NAME, text)
            if (ok) {
                Logger.probe { "ADCACHE flushed ${m.size} entries (sig=$sig)" }
            } else {
                Logger.once("adapt.cache.wfail", "ADCACHE 写盘失败（缓存不可用，功能不受影响）")
            }
        } catch (t: Throwable) {
            Logger.d("ADCACHE flush fail: ${t.message}")
        }
    }

    /** 供设置页/排障展示：缓存是否已载入、条目数 */
    fun statusText(): String = try {
        val m = mem
        when {
            m == null -> "未载入"
            else -> "${m.size} 条 (sig=$loadedSig)"
        }
    } catch (_: Throwable) { "?" }

    /**
     * 清空缓存（怀疑缓存有问题时可用）。
     *
     * ★ 2026-09-30：连 `.bak` 一起删 —— 加密后读取端有「主文件坏 → 回退 .bak」的
     *   兜底（规格 ④），只删主文件会让「清空」在下一次读取时被备份复活。
     */
    fun clear() {
        try { fileOf().delete() } catch (_: Throwable) {}
        try { SecureStore.bakOf(fileOf()).delete() } catch (_: Throwable) {}
        mem = null
        loadedSig = null
    }

    // ==================== 供 [AdaptVerify] 使用的纯逻辑出口 ====================
    // 让验证台断言**真实签名算法**，而不是复制一份（副本会与实现漂移）。

    /** 按给定快手版本计算签名（不读运行时状态） */
    internal fun sigForTest(ksVersion: String, moduleCode: Int): String =
        listOf(ksVersion, moduleCode.toString(), FORMAT_VERSION.toString()).joinToString("|")

    /** 按给定快手版本判断是否可写盘（版本非空即可） */
    internal fun writableForTest(ksVersion: String): Boolean = ksVersion.isNotBlank()
}
