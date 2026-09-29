package io.github.angbang852.manjiao.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.Prefs
import java.io.File

/**
 * ★★★ 钥匙管理（2026-09-30 用户规格 ②）
 *
 * ## 为什么钥匙必须由 **hook 侧**生成、且落在**快手私有目录**
 *
 * 这些文件永远是**快手进程**在读写（模块代码注入在快手进程里跑）。模块自己的
 * app（`io.github.angbang852.manjiao`）**很少被打开**，部分 ROM 上还会被广播拉不活。
 * 若把钥匙交给模块 app 生成/持有，就会得出「用户必须先打开一次 app，过滤器的
 * 配置镜像才写得出来」这种荒谬依赖 —— 首次安装、清数据后都会静默退化。
 * 所以在**快手进程首次运行时**就地生成，存快手私有位置：
 *
 * ```
 * /data/data/com.smile.gifmaker/files/mj_key_v1        （0600，宿主私有）
 * ```
 *
 * 该目录**第三方 App 读不到**（Linux uid 隔离 + SELinux app_data_file），
 * 与公共目录 `/sdcard/Download/ManJiao/.sys/` 的 world-writable 形成对照 ——
 * 钥匙放这里，密文放那里，攻击者拿不到密文的钥匙。
 *
 * ## 为什么用现有 PERM_SYNC 通道，而不发明新通道
 *
 * [Prefs.PERM_SYNC] 是 `protectionLevel=signature` 的自定义权限：
 *  - **第三方 App 发不了**：快手进程内的接收器以 PERM_SYNC 注册（发送方必须持权），
 *    而只有同证书的本模块在 manifest 里 `uses-permission` 了它
 *    （见 AndroidManifest.xml「必须同时声明 uses-permission」那段血泪注释）；
 *  - **第三方 App 收不了**：回包用 `setPackage(OWN_PKG)` 显式定向。
 *
 * 所以钥匙索取直接复用 [io.github.angbang852.manjiao.data.AuditBridge] 已经跑通的
 * 同一套「请求/应答广播」形状，只是换两个 action 名。
 * ⚠️ 本机实测 SDK=36，回包侧另加 `getSentFromPackage()` 来源校验（API 34+ 才有）。
 *
 * ## 多进程竞态（真机有 4 个快手进程！）
 *
 * 实测日志里同时出现 `com.smile.gifmaker` / `:messagesdk` / `:push_v3` /
 * `:kwv_sandboxed_p0` 四个进程，**每个都会 `Logger.init`**。若各自生成一把钥匙，
 * 就会出现「A 进程写的密文 B 进程解不开」——那会表现为随机的「存档损坏」，
 * 极难排查。故：
 *  1. **热路径永不生成**：[hookKey] 只读取，读不到返回 null（fail-closed，
 *     调用方会稍后重试），因此不会在主线程上做重活；
 *  2. 生成只发生在 [ensureAsync] 起的后台线程，且写入流程是
 *     「写 `.tmp.<pid>` → `renameTo`（`/data` 是真实文件系统，rename 原子）
 *     → **睡 120ms 后回读**」，后到者覆盖、先到者采纳，最终所有进程收敛到同一把。
 *
 * ## 缓存
 *
 * 两侧**各缓存内存**（[hookCached] / [appCached]）—— 每次落盘都读文件会在热路径上
 * 多一次 syscall，正是本项目一直在消灭的那类开销。
 *
 * ## app 侧的持久化备份（2026-09-30 A①「收到就存」）
 *
 * 模块 app 取到钥匙后**再留一份到它自己的私有目录**（[persistAppKey]）。
 * 起因是**单点风险**：快手那份是唯一权威副本，`清除快手数据 / 卸载重装` 就抹掉了，
 * 而 `pool.json` / `pool_ids.json` 已是 AES-GCM 密文 ⇒ **去重表全废**。
 * app 是另一个包（[Prefs.OWN_PKG]），清快手数据动不到它，因此这份备份是唯一救援点。
 * 两侧**共用同一套相对布局**（`filesDir/mj_key_v1`），只靠进程 uid 决定落在谁家目录。
 *
 * ## 反向取回（2026-09-30 A②「本地丢了就向 app 要」）—— 补上 A① 的闭环
 *
 * A① 只解决了「备份存下来了」，但**没有取回路径**：真到了清快手数据那一刻，快手侧依然会
 * 走「本地没有 ⇒ 当场新生成一把」，于是备份白白躺着、旧密文照样解不开（用户规格原文：
 * 「把加密钥匙备份到模块 app，并**支持丢失后取回**」）。所以：
 *
 * ```
 * hook 启动装载钥匙：① 本地有 ⇒ 用本地（来源=local）
 *                    ② 本地没有 ⇒ 向 app 索取备份，拿到就**写回本地**（来源=app）
 *                    ③ 两边都没有 ⇒ 新生成 + [lostKeyAlarm] 响亮告警
 * ```
 *
 * 新增的只有**方向**（两个 action：快手发 [ACTION_KEY_PULL] / app 回 [ACTION_KEY_BACK]），
 * 通道形状、鉴权手段、原子落盘与 0600 纪律全部沿用既有那套，没有发明新机制。
 * 收包侧比 A① 那条更硬：快手用 [Prefs.PERM_SYNC]（发送方必须持有）注册 ⇒ 第三方伪造
 * ACTION_KEY_BACK 进不来；app 侧那条只能靠 `getSentFromPackage()`，而它在本机是失效的
 * （实测 `来源=?`），详见 [replyKeyBack] 与 [pullKeyFromApp] 的注释。
 */
object KeyVault {

    /** app → 快手：索取钥匙（受 PERM_SYNC 保护，第三方发不出去） */
    const val ACTION_KEY_REQ = "io.github.angbang852.manjiao.KEY_REQ"

    /** 快手 → app：回传钥匙（Base64），显式 setPackage(OWN_PKG) 定向 */
    const val ACTION_KEY_RSP = "io.github.angbang852.manjiao.KEY_RSP"

    /**
     * 快手 → app：**反向**索取 app 侧那份备份（2026-09-30 A②）。
     *
     * 与 [ACTION_KEY_REQ] 是**反方向**的同一件事：那条是 app 要解密显示时向快手要钥匙，
     * 这条是快手本地钥匙文件没了（用户清了快手数据）时向 app 要**备份**。
     * 发起方是快手进程，而快手**不持有** [Prefs.PERM_SYNC]（那是本模块的 signature 权限，
     * 宿主进程拿不到），所以 app 侧接收器**不能**挂 PERM_SYNC —— 沿用
     * [io.github.angbang852.manjiao.data.PrefsWriteReceiver] 已有的「exported + 运行时查发送方」
     * 那一套（它存在的理由就是这个：快手进程是无权限对端）。
     */
    const val ACTION_KEY_PULL = "io.github.angbang852.manjiao.KEY_PULL"

    /**
     * app → 快手：把**备份**钥匙交还给快手（Base64）。
     *
     * ★ 与 [ACTION_KEY_RSP] 方向相反、但**收包侧的护栏更强**：快手侧用
     *   `registerReceiver(..., Prefs.PERM_SYNC, ...)` 注册 —— 语义是「**发送方**必须持有
     *   PERM_SYNC」。只有同证书的本模块持有它，所以第三方 app 就算伪造 ACTION_KEY_BACK
     *   也会被系统在投递层直接丢弃（与 app 侧那条只能靠 `getSentFromPackage` 兜的情况不同）。
     */
    const val ACTION_KEY_BACK = "io.github.angbang852.manjiao.KEY_BACK"

    /** 回包里的钥匙字段（Base64 文本） */
    const val EXTRA_KEY = "k"

    /** 钥匙长度：AES-256 */
    const val KEY_LEN = 32

    /** 快手私有目录下的钥匙文件名（app 侧备份**刻意同名**，见 [appBackupFile]） */
    private const val KEY_FILE = "mj_key_v1"

    /** app 侧自检日志文件名（沿用 [io.github.angbang852.manjiao.ui.SettingsActivity] 那份） */
    private const val KEY_DIAG_FILE = "keydiag.txt"

    /** 多进程收敛等待：让其它进程有机会把同一份 key rename 进来 */
    private const val CONVERGE_MS = 120L

    /**
     * 向 app 索取备份的单次等待上限（A②）。
     *
     * 取值理由：模块 app 常常**没在运行**，第一次广播要先把它冷启起来。
     * 真机冷启一个轻量 app 的量级是「几十 ~ 几百 ms」，1.5s 留了充足余量；
     * 而本流程只在「本地钥匙文件不存在」时才走（正常启动**根本不进**这条分支），
     * 且始终跑在 [ensureAsync] 的后台线程上 ⇒ 这点等待不影响任何用户可见路径。
     */
    private const val PULL_TIMEOUT_MS = 1500L

    /** 去重表存档文件名（[lostKeyAlarm] 判断「是否已有解不开的密文」只看这两个） */
    private val ARCHIVE_NAMES = arrayOf("pool.json", "pool_ids.json")

    /** 判断密文只需 MjCrypto 的 4 字节 MAGIC，读这么多就够（不要去读 1MB 的整文件） */
    private const val MAGIC_PROBE = 8

    /**
     * ★★ 仅用于验证的开关（**不进最终产物**，2026-09-30 A② 验证用）。
     *
     * 作用：让 [readKeyFile] 一律返回 null，即把「本地钥匙文件」在**逻辑上**当成不存在。
     *
     * 为什么需要它：快手是**非 debuggable** 包，`run-as` / root 都不可用
     * （实测 `adb shell ls /data/data/com.smile.gifmaker/files/` ⇒ Permission denied、
     * 设备无 `su`），所以无法用「改名/删除真实钥匙文件」来模拟**用户清了快手数据**。
     * 这也正是不许硬删设备文件时唯一可行的替身手段。
     *
     * 为什么安全：指纹已核对 —— 快手本地那把与应用备份那把**同为 `53c70a91`**
     * （见 evidence.txt 的 `★钥匙已载入` 与 app 的 `files/keydiag.txt`），因此
     * 「从 app 取回并回写本地」写进去的是**字节相同**的钥匙，不会改坏任何东西。
     *
     * ⚠️ 验证完必须置回 `false` 并**重新构建**；带 true 的包只用于这一次验证。
     */
    private const val TEST_FORCE_MISSING_LOCAL = false

    // ---------------------------------------------------------------- 公共入口

    /**
     * 取当前进程该用的钥匙（**内存缓存**）。
     *
     * - 快手进程 ⇒ [hookKey]（本地读取，读不到就异步生成）
     * - 模块 app ⇒ [appKey]（向快手进程索取；快手没在跑时为 null）
     *
     * 返回 null **不是错误**，是「这一刻拿不到钥匙」—— 调用方必须按
     * fail-closed 处理（见 [SecureStore]），绝不能因此放行。
     */
    fun currentKey(): ByteArray? = if (isHookSide()) hookKey() else appKey()

    /**
     * 本进程是不是「快手侧」（被注入的宿主进程）。
     *
     * 判据：`ActivityThread.currentApplication().packageName` 是否等于模块自己的包名。
     * 取不到 Context 时**保守当快手侧** —— 那是本地生成、永远能出钥匙的一侧；
     * 判错顶多让模块 app 走一次本地生成（会成为读不到存档的孤立钥匙，不会泄密）。
     */
    fun isHookSide(): Boolean {
        val pkg = try { appContext()?.packageName } catch (_: Throwable) { null }
        return pkg == null || pkg != Prefs.OWN_PKG
    }

    /**
     * 取当前进程的 Context。
     *
     * 复用项目既有的取法（见 `KsCache.signatureOf` / `CfhPoolStore.appClassLoader`）：
     * 在快手进程里拿到的是**快手自己的 Application** ⇒ 其 `filesDir` 就是
     * `/data/data/com.smile.gifmaker/files`（正是我们要的私有位置）；
     * 在模块 app 进程里则是模块 app 的 Application。
     */
    fun appContext(): Context? {
        cachedCtx?.let { return it }
        val c = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (_: Throwable) { null }
        if (c != null) cachedCtx = c
        return c
    }

    @Volatile private var cachedCtx: Context? = null

    /** 钥匙指纹（SHA-256 前 4 字节 hex）—— 只用于日志/界面，**不含钥匙本身** */
    fun fingerprint(key: ByteArray?): String {
        if (key == null) return "无"
        return try {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(key)
            val sb = StringBuilder()
            for (i in 0 until 4) sb.append(String.format("%02x", d[i].toInt() and 0xFF))
            sb.toString()
        } catch (_: Throwable) { "?" }
    }

    // ---------------------------------------------------------------- 快手侧

    @Volatile private var hookCached: ByteArray? = null
    private val ensureOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 快手侧取钥匙。**只读**，读不到就触发一次异步生成并返回 null。
     *
     * ★ 刻意**不在本方法里生成**：它可能被任何热路径调用（存盘、判定），
     *   生成要走「写 tmp + rename + 睡 120ms 回读收敛」，绝不能挂在主线程上。
     */
    fun hookKey(): ByteArray? {
        hookCached?.let { return it }
        val k = readKeyFile()
        if (k != null) {
            hookCached = k
            return k
        }
        ensureAsync()
        return null
    }

    /**
     * 确保快手侧钥匙已就绪（幂等、异步、非阻塞）。
     *
     * 由 [Logger.init] 在注入早期调用；[hookKey] 读不到时也会兜底触发，
     * 保证「任何入口最终都能把钥匙生出来」。
     */
    fun ensureAsync() {
        if (!ensureOnce.compareAndSet(false, true)) return
        Thread {
            try { initHookSide() } catch (_: Throwable) {}
        }.apply { name = "MJ-KeyInit"; isDaemon = true }.start()
    }

    /**
     * 生成/装载钥匙，并把多进程收敛做完（**只在后台线程调用**）。
     *
     * 装载优先级（顺序即安全性，不可调换）：
     * ```
     * ① 本地快手私有目录那把（authoritative，正常路径，永远最先）
     * ② 模块 app 私有目录的备份（A②：本地丢了才走；这是「清快手数据」后的唯一救援点）
     * ③ 都没有 ⇒ 新生成一把（首装/彻底丢钥匙；见 [lostKeyAlarm] 的告警）
     * ```
     */
    private fun initHookSide() {
        val f = waitForKeyFile() ?: return
        // ① 已有合法钥匙 ⇒ 直接用（绝大多数情况）
        readKeyFile()?.let {
            hookCached = it
            // ★ 日志纪律：只打**指纹**，来源只打 `local` / `app` 两个词
            //   （绝不打印钥匙内容；旧实现这里打的是路径，与 app 侧那条「来源=」语义不一致）
            Logger.evidence(
                "KEYVAULT",
                "★钥匙已载入 指纹=${fingerprint(it)} 来源=local 位置=${f.absolutePath}"
            )
            return
        }
        // ② ★★ A②（2026-09-30）：本地没有 ⇒ **先向模块 app 索取备份，再考虑生成新的**。
        //
        //   顺序不可颠倒，这是本功能的全部要害：`清除快手数据 / 卸载重装快手` 会抹掉
        //   快手私有目录里那把**唯一权威**钥匙，而 `pool.json` / `pool_ids.json` 已是
        //   AES-GCM 密文 ⇒ 一旦在这里抢先 `MjCrypto.newKey()`，就等于**当场作废旧钥匙**、
        //   把去重表永久变成一堆解不开的字节（用户第一硬规则「内容不重复上屏」随之破防）。
        //   模块 app 是**另一个包**，清快手数据动不到它 ⇒ 它那份备份是这条链路上
        //   唯一的救援点（威胁：单点风险）。
        recoverFromApp(f)?.let { return }
        // ③ 两边都拿不到 ⇒ 只能新生成（全新安装走的就是这条正常路径）。
        generateAndStore(f)
    }

    /**
     * ★★ A②：本地钥匙缺失时向模块 app 取回备份并**写回本地**（2026-09-30）。
     *
     * 只在后台线程调用（广播往返最长 ~2×[PULL_TIMEOUT_MS]，绝不能挂在主线程/热路径上）。
     *
     * @return 拿到并（尽力）写回的钥匙；app 没有备份/不可达时返回 null ⇒ 调用方转为新生成
     */
    private fun recoverFromApp(f: File): ByteArray? {
        val k = pullKeyFromApp()
        if (k == null) {
            Logger.evidence(
                "KEYVAULT",
                "★本地无钥匙且 app 未交还备份 ⇒ 转为本机新生成（若磁盘上已有密文，旧存档将不可解）"
            )
            return null
        }
        val wrote = writeKeyFile(f, k)
        // ★ 回读校验：只有**读回来就是这把**才算「写回成功」。
        //   否则只能证明「我调用过写」，证明不了「盘上真有钥匙」——那下次启动又会走一遍
        //   恢复流程，而使用者以为已经修好了（静默的假成功）。
        val back = readKeyFileRaw()
        Logger.evidence(
            "KEYVAULT",
            "★从 app 取回钥匙 指纹=${fingerprint(k)} 来源=app 回写=$wrote " +
                "回读指纹=${fingerprint(back)} 位置=${f.absolutePath}"
        )
        // 回读成功就用回读到的（四进程并发时可能是别的进程刚写进去的同一把）；
        // 落盘失败（回写=false）时至少把内存里这把用上，本进程能正常加解密。
        val use = back ?: k
        hookCached = use
        return use
    }

    /**
     * 向模块 app 发 [ACTION_KEY_PULL]，等它用 [ACTION_KEY_BACK] 把**备份**交回来。
     *
     * ## 为什么这条方向的收包侧能硬挡第三方（与 app 侧那条通道的关键差别）
     *
     * `registerReceiver(recv, filter, Prefs.PERM_SYNC, ...)` 里那个权限参数的语义是
     * 「**发送方**必须持有该权限，广播才会被投递到本接收器」。`PERM_SYNC` 是
     * `protectionLevel=signature`、且只有**同证书的本模块** `uses-permission` 了它
     * ⇒ **只有模块 app 发得进来**；第三方伪造 ACTION_KEY_BACK 会被系统在投递层丢弃。
     * 这是硬护栏，**刻意不依赖** `getSentFromPackage()`：真机实测 app 侧那条回包拿到的是
     * `来源=?`（该 API 在此设备上返回 null ⇒ 那道校验实际上是**失效**的），
     * 所以不能把「挡不挡得住伪造」压在它身上（威胁：第三方伪造钥匙注入）。
     *
     * ## 为什么发两次
     *
     * 模块 app 常常**没在运行**（它只在用户打开设置页时活着）。第一次广播要先把它的进程
     * 拉起来（几十~几百 ms），冷启期间它可能来不及应答；第二次落在它已起来之后，成功率高
     * 得多。两次共用**同一个动态接收器**，不会漏包。
     */
    private fun pullKeyFromApp(timeoutMs: Long = PULL_TIMEOUT_MS): ByteArray? {
        val ctx = appContext() ?: return null
        val q = java.util.concurrent.ArrayBlockingQueue<ByteArray>(1)
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != ACTION_KEY_BACK) return
                val b64 = i.getStringExtra(EXTRA_KEY) ?: return
                val k = decodeB64(b64) ?: return
                // 长度不对的一律不认：宁可当作「app 没有备份」，也绝不用一把
                // 「不是钥匙的钥匙」去覆盖本地权威文件或拿去解存档
                if (k.size != KEY_LEN) return
                q.offer(k)
            }
        }
        var reg = false
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.registerReceiver(
                    recv, IntentFilter(ACTION_KEY_BACK), Prefs.PERM_SYNC, null,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                // API 24/25 没有带 flags 的重载，但四参 (receiver, filter, permission, scheduler)
                // 自 API 1 就存在 —— 与 Module.kt 里 prefsReceiver 的写法保持一致
                ctx.registerReceiver(recv, IntentFilter(ACTION_KEY_BACK), Prefs.PERM_SYNC, null)
            }
            reg = true
            var got: ByteArray? = null
            for (attempt in 0 until 2) {
                try {
                    ctx.sendBroadcast(
                        Intent(ACTION_KEY_PULL).setPackage(Prefs.OWN_PKG)
                            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    )
                } catch (_: Throwable) {}
                val k = try {
                    q.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: Throwable) { null }
                if (k != null) { got = k; break }
            }
            got
        } catch (t: Throwable) {
            Logger.evidence("KEYVAULT", "★索取 app 备份异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            if (reg) { try { ctx.unregisterReceiver(recv) } catch (_: Throwable) {} }
        }
    }

    /**
     * 把钥匙**原子地**写进快手私有目录（`写 .tmp.<pid>` → `renameTo` → 收紧 0600）。
     *
     * 与 [generateAndStore] 共用同一条纪律：
     *  - **原子替换**：`/data` 是真实文件系统，rename 是原子的 ⇒ 任何时刻磁盘上要么是
     *    旧钥匙、要么是新钥匙，不存在「半个钥匙文件」（读取端只认正好 32 字节，半写会被
     *    判成「没有钥匙」⇒ 又走一遍恢复流程，来回震荡）；
     *  - **0600**：第三方 App 因 Linux uid 隔离 + SELinux `app_data_file` 本来就读不到，
     *    这一步是纵深防御（防同 uid 的其它进程、防设备备份导出）；
     *  - **绝不写公共目录**：`/sdcard/...` 是 world-readable，钥匙一旦落到那里，
     *    整套 AES-GCM 就等于没做（密文与钥匙同处一地，威胁：本地越权读取）。
     *
     * @return 是否确实落盘（false 时调用方退化为「只用内存里这把、下次启动再试」）
     */
    private fun writeKeyFile(f: File, k: ByteArray): Boolean {
        val tmp = File(f.parentFile, f.name + ".tmp." + android.os.Process.myPid())
        return try {
            f.parentFile?.mkdirs()
            tmp.writeText(encodeB64(k))
            var ok = tmp.renameTo(f)
            if (!ok) {
                // rename 失败（少见：目标被占/文件系统异常）⇒ 退化为直写，再不行就放弃
                try { f.writeText(encodeB64(k)); ok = true } catch (_: Throwable) {}
                try { tmp.delete() } catch (_: Throwable) {}
            }
            if (ok) {
                try {
                    f.setReadable(true, true)
                    f.setWritable(true, true)
                    f.setExecutable(false, false)
                } catch (_: Throwable) {}
            }
            ok
        } catch (_: Throwable) {
            try { tmp.delete() } catch (_: Throwable) {}
            false
        }
    }

    /** 新生成一把并落盘（原 [initHookSide] 的生成分支，行为不变，另加一道不可逆保护） */
    private fun generateAndStore(f: File) {
        // ★★ 纵深防御：**绝不覆盖一把已经存在且可读的钥匙**。
        //
        //   走到这里理论上就意味着「本地确实没有钥匙」，但这一步是**全项目最不可逆**的操作：
        //   一旦判错（例如验证时用 [TEST_FORCE_MISSING_LOCAL] 强制「当作没有」，而 app 那份
        //   又没送达），写进去的新钥匙会**当场把用户仅存的那把抹掉**，旧密文从此永久解不开。
        //   所以落盘前再真读一次（[readKeyFileRaw] 不受验证开关影响）：只要盘上有一把能用的，
        //   就采纳它、绝不生成。正常首装这里读到 null，行为不变；四进程竞态下还能顺手收敛，
        //   避免「后到者把先到者的钥匙盖掉」。
        readKeyFileRaw()?.let {
            hookCached = it
            Logger.evidence(
                "KEYVAULT",
                "★本地其实已有钥匙 ⇒ 取消重新生成 指纹=${fingerprint(it)} 来源=local"
            )
            return
        }
        // 确实要生成了。若磁盘上**已经有解不开的密文**，这次生成等于给旧存档判了死刑
        // ⇒ 响亮告警（fail-closed，不许静默；判据见 [lostKeyAlarm]）。
        lostKeyAlarm()
        val mine = MjCrypto.newKey()
        if (!writeKeyFile(f, mine)) {
            Logger.evidence("KEYVAULT", "★★钥匙落盘失败 ⇒ 本次不生成（文件通道暂不可用）")
            ensureOnce.set(false)               // 允许下次重试
            return
        }
        // ★ 多进程收敛：四个快手进程可能同时首次运行。后写者覆盖，先写者必须采纳。
        try { Thread.sleep(CONVERGE_MS) } catch (_: Throwable) {}
        val finalKey = readKeyFileRaw() ?: mine
        hookCached = finalKey
        Logger.evidence(
            "KEYVAULT",
            "★首次生成钥匙 指纹=${fingerprint(finalKey)} 来源=local 长度=${finalKey.size} " +
                "位置=${f.absolutePath}（快手私有目录，第三方 App 读不到）"
        )
    }

    /**
     * 钥匙彻底丢失时的**显眼告警**（规格：fail-closed，**不许静默**）。
     *
     * 判据刻意收紧成「**磁盘上已经有解不开的密文**」，而不是「本地钥匙文件不在」：
     *  - 全新安装同样「哪里都没有钥匙」，但那是**正常首启**、不是丢失。不加这个判据的话，
     *    每个新装用户第一次开机都会吃到一条「钥匙丢失」，真正出事时这条告警就没人信了
     *    （狼来了）——告警的价值全在于「响了就是真出事」；
     *  - 反过来，只有「密文在、钥匙没了」才说明**旧存档已经解不开**，正是用户点名的那件事：
     *    去重表本轮不可用 ⇒ 内容会重复上屏。
     *
     * ⚠️ 判不出来时**按最坏情况告警**（fail-loud）：本告警存在的全部意义就是不许静默，
     *    宁可多响一次，不可漏报一次。
     */
    private fun lostKeyAlarm() {
        val sealed = hasSealedArchive()
        if (sealed == false) return
        Logger.evidence(
            "KEYVAULT",
            "★★★钥匙丢失，去重表本轮不可用（本地无钥匙、app 也无备份" +
                (if (sealed == true) "，且磁盘上已有解不开的密文"
                 else "，且无法确认磁盘上是否已有密文（按最坏情况处理）") +
                "）⇒ 本进程将新生成一把；旧密文要等旧钥匙回来才解得开"
        )
    }

    /**
     * 磁盘上是否已经存在**解不开的密文**（只查去重表那两个存档）。
     *
     * 用 [MjCrypto.isSealed]（只看 4 字节 MAGIC）而不是解密：此刻我们**没有**可用钥匙，
     * 想解密也做不到；有没有钥匙与「是不是密文」是两件事，只看头就能判。
     *
     * @return true=有密文 / false=没有（全新安装）/ null=查不出来（**按有处理**，fail-loud）
     */
    private fun hasSealedArchive(): Boolean? = try {
        val dir = io.github.angbang852.manjiao.data.StorageDirs.sysDirFixed()
        var found = false
        for (n in ARCHIVE_NAMES) {
            val f = File(dir, n)
            if (!f.exists() || f.length() <= 0L) continue
            // 只读文件头这几个字节：pool.json 有 1.2MB，为了判个 MAGIC 去整读是浪费
            val head = ByteArray(MAGIC_PROBE)
            val n2 = try {
                java.io.RandomAccessFile(f, "r").use { it.read(head) }
            } catch (_: Throwable) { -1 }
            if (n2 < 4) continue
            if (MjCrypto.isSealed(if (n2 == head.size) head else head.copyOf(n2))) {
                found = true
                break
            }
        }
        found
    } catch (_: Throwable) { null }

    /** 等私有目录可用（Application 可能在注入极早期还没创建），最多 ~10 秒 */
    private fun waitForKeyFile(): File? {
        for (i in 0 until 40) {
            val f = keyFile()
            if (f != null) return f
            try { Thread.sleep(250) } catch (_: Throwable) { break }
        }
        Logger.evidence("KEYVAULT", "★★始终拿不到快手私有目录 ⇒ 本次不生成钥匙")
        ensureOnce.set(false)
        return null
    }

    /**
     * 读本地（快手私有目录）那把钥匙。
     *
     * [TEST_FORCE_MISSING_LOCAL] 打开时**一律当作不存在**（仅验证用，见该常量注释）。
     */
    private fun readKeyFile(): ByteArray? =
        if (TEST_FORCE_MISSING_LOCAL) null else readKeyFileRaw()

    /**
     * 真读本地钥匙文件（**不受** [TEST_FORCE_MISSING_LOCAL] 影响）。
     *
     * 与 [readKeyFile] 分开的原因：验证开关只该骗过「**加载路径**」（模拟钥匙丢了），
     * 不该骗过「**回读校验**」—— 否则「回写到底成没成」就永远测不出来，
     * 只能证明「我调用过写」。
     */
    private fun readKeyFileRaw(): ByteArray? {
        val f = try { keyFile() } catch (_: Throwable) { null } ?: return null
        return try {
            if (!f.exists() || f.length() <= 0L) return null
            val k = decodeB64(f.readText().trim())
            if (k != null && k.size == KEY_LEN) k else null
        } catch (_: Throwable) { null }
    }

    /**
     * 快手侧：应答一次钥匙索取（由 [io.github.angbang852.manjiao.Module] 的
     * PERM_SYNC 接收器转发进来）。
     *
     * 回包**只发给模块自己的包**（`setPackage(OWN_PKG)`）⇒ 第三方收不到。
     */
    fun replyKeyRequest(ctx: Context) {
        try {
            val k = hookKey()
            val i = Intent(ACTION_KEY_RSP).setPackage(Prefs.OWN_PKG)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            if (k != null) i.putExtra(EXTRA_KEY, encodeB64(k))
            ctx.sendBroadcast(i)
            Logger.evidence(
                "KEYVAULT",
                "★已应答钥匙索取 有=${k != null} 指纹=${fingerprint(k)} 目标=${Prefs.OWN_PKG}"
            )
        } catch (t: Throwable) {
            Logger.evidence("KEYVAULT", "★★应答钥匙索取失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 快手私有目录下的钥匙文件 */
    private fun keyFile(): File? = appContext()?.let { File(it.filesDir, KEY_FILE) }

    // ---------------------------------------------------------------- 模块 app 侧

    /**
     * ★★ app 侧：应答 hook 的**备份索取**，把备份里那把钥匙交还给快手（2026-09-30 A②）。
     *
     * 由 [io.github.angbang852.manjiao.data.PrefsWriteReceiver] 转进来。那条接收器是
     * `exported` 且**不能**挂 [Prefs.PERM_SYNC] —— 发起方是快手进程，而快手**不持有**本模块
     * 的 signature 权限，挂了权限就整条链路断掉（与 PrefsWriteReceiver 那段注释同源，
     * 也是它当初只能「exported + 运行时查发送方」的原因）。
     *
     * ## 为什么只有这条方向可以安全地对任意调用者开放
     *
     * 接收器谁都能触发（第三方 app 也能发 [ACTION_KEY_PULL]），但**回包去向是写死的**
     * 快手两个包（[KsClass.PKG] / [KsClass.PKG_NEBULA]），**不接受调用方指定回包地址**
     * —— 显式定向广播只投递给该包，第三方 app 领不到，因此骗不出钥匙。
     * （★ 这是硬要求：一旦允许请求方自带「回包目标包」，这条通道立刻退化成
     *   「任意 app 可索取主密钥」的越权接口。威胁：第三方 app 越权索取。）
     *
     * ## 幂等与副作用
     *
     * 本方法**只读** [appBackupFile]：不索取、不回读、不写任何文件，也不动内存缓存。
     * 所以即便被恶意高频触发，最坏也只是多几次广播 —— 不存在「被刷一下就改坏数据」的面。
     *
     * 日志纪律：只打**指纹**与去向，**绝不打印钥匙内容**（走 [keyDiag]，落在
     * app 私有目录 `files/keydiag.txt`；公共目录那份由 [Logger] 管，这里不碰）。
     */
    fun replyKeyBack(ctx: Context) {
        try {
            // 只该在模块 app 进程里应答：在快手进程里 appBackupFile() 会指向快手自己的
            // 私有目录（那是权威钥匙，不是备份），交还出去等于把权威那份送错地方。
            if (isHookSide()) return
            val f = appBackupFile() ?: return
            val k = try {
                if (f.exists() && f.length() > 0L) decodeB64(f.readText().trim()) else null
            } catch (_: Throwable) { null }
            if (k == null || k.size != KEY_LEN) {
                keyDiag("★被索取备份，但 app 侧没有可用备份（本次未交还）")
                return
            }
            for (pkg in arrayOf(KsClass.PKG, KsClass.PKG_NEBULA)) {
                try {
                    ctx.sendBroadcast(
                        Intent(ACTION_KEY_BACK).setPackage(pkg).putExtra(EXTRA_KEY, encodeB64(k))
                    )
                } catch (_: Throwable) {}
            }
            keyDiag("★已交还备份钥匙 指纹=${fingerprint(k)} 目标=快手")
        } catch (_: Throwable) {}
    }

    @Volatile private var appCached: ByteArray? = null

    /**
     * 模块 app 侧取钥匙（**内存缓存**；未命中则异步发起一次索取并返回 null）。
     *
     * 为什么默认不阻塞：本方法可能被 UI 线程调用；广播问答有往返延迟，
     * 阻塞 UI 不是本项目的作风。需要「这一刻就要」的调用方用 [appKeyBlocking]。
     */
    fun appKey(): ByteArray? {
        appCached?.let { return it }
        requestAsync()
        return null
    }

    /**
     * 同上，但**最多等 timeoutMs**（调用方必须在非主线程）。
     *
     * 注：模块 app 侧取到的钥匙除**内存缓存**（[appCached]）外，还会**落一份备份到
     * app 自己的私有目录**（[persistAppKey]，2026-09-30 A①「收到就存」）。
     * 快手进程没有运行时 app 仍然取不到钥匙 —— 备份的**读回与交还**是另一半
     * （[replyKeyBack] / [recoverFromApp]，2026-09-30 A②「本地丢了就向 app 要」）。
     * 但 A② **只在 [initHookSide] 里走**（后台线程、且本地钥匙文件缺失时），
     * 本方法**依旧只读不取**，所以这里不存在「本地有备份就能自行解密」的路径，
     * fail-closed 语义不变。
     */
    fun appKeyBlocking(timeoutMs: Long = 800L): ByteArray? {
        appCached?.let { return it }
        val k = requestKey(timeoutMs)
        if (k != null) {
            appCached = k
            // ★ A①：收到就存。32 字节的小写，且本方法按约定只在非主线程被调用。
            persistAppKey(k)
        }
        return k
    }

    /**
     * ★★★ 把 app 侧刚收到的钥匙**备份到模块 app 自己的私有目录**（2026-09-30 A①）。
     *
     * ## 为什么需要（威胁：单点风险）
     *
     * 钥匙的**唯一权威副本**在快手私有目录
     * （`/data/data/com.smile.gifmaker/files/mj_key_v1`，见 [initHookSide]）。
     * **清除快手数据 / 卸载重装快手**会连同它一起抹掉，而 `pool.json` / `pool_ids.json`
     * 已按 AES-GCM 加密 ⇒ 密文**永远解不开** ⇒ 去重表报废 ⇒ 重复上屏
     * （违反用户第一硬规则）。模块 app 是**另一个包**，清快手数据动不到它，
     * 因此在这里留一份副本是这条链路上唯一的救援点。
     *
     * ## 威胁与取舍
     *
     * - **多一份副本 = 多一个可被窃取的目标**（这是原设计只肯放内存的理由，风险真实存在）。
     *   缓解：只写 app 自己的 [android.content.Context.getFilesDir]
     *   （`/data/data/io.github.angbang852.manjiao/files`，uid 隔离 + SELinux app_data_file，
     *   第三方 App 与普通文件管理器都读不到），写入后收紧成 0600 —— 与快手那份同一纪律。
     *   **绝不写公共目录**（`/sdcard/...` 是 world-writable，正是钥匙不能去的地方）。
     * - **钥匙会换**：快手数据被清后会**重新生成一把新钥匙**，而**旧密文只能用旧钥匙**解。
     *   所以发现内容变了**不直接盖掉**：先把上一把挪成 `mj_key_v1.bak`
     *   （沿用 [SecureStore.bakOf] 的 `.bak` 约定），否则换钥匙那一刻就把旧钥匙永久丢弃，
     *   备份反而成了新的单点。
     * - **只打指纹与路径**（沿用既有 `指纹=xxxx` 格式），**绝不打钥匙内容**。
     * - 任何异常都**静默吞掉**：备份失败最多是「没拿到救援副本」，
     *   绝不能让 app 侧取钥匙的主流程跟着失败。
     * - **[Synchronized]：同一进程里会有两个线程同时索取**
     *   （[io.github.angbang852.manjiao.ui.SettingsActivity] 的 `encStatusText` 自己起一个线程，
     *   同一次调用里 [appKey] 也会起一个）。若放任并发，两者会争同一个 `.tmp`，
     *   后到者可能走进「先 delete 再直写」的兜底分支 —— 那一瞬间磁盘上**没有备份文件**，
     *   此刻被杀就等于白做。加锁后这段窗口不存在；两者内容本就相同，不存在语义变化。
     */
    @Synchronized
    private fun persistAppKey(k: ByteArray) {
        try {
            // 只在**模块 app 进程**里落盘：在快手进程里 [appContext] 指向快手，
            // 那样就会去动快手那份权威钥匙文件 —— 不是本方法该干的事（也避免误伤）。
            if (isHookSide()) return
            // 通道侧已校验过长度（[requestKey]），这里再挡一道：
            // 宁可没有备份，也绝不写进去一把长度不对的「钥匙」。
            if (k.size != KEY_LEN) return
            val f = appBackupFile() ?: return

            // ① 同一把就不重复写：每次进设置页都会索取一次（SettingsActivity.encStatusText），
            //    没必要每次都产生一次磁盘写。
            val cur = try {
                if (f.exists() && f.length() > 0L) decodeB64(f.readText().trim()) else null
            } catch (_: Throwable) { null }
            if (cur != null && cur.contentEquals(k)) return

            // ② 真的换钥匙了 ⇒ 先把上一把留成 .bak（旧密文还要靠它）
            if (cur != null) {
                try { f.renameTo(File(f.parentFile, f.name + ".bak")) } catch (_: Throwable) {}
            }

            // ③ 原子落盘：tmp → rename（沿用 [SecureStore.seal] 的写法，rename 失败再退化为直写）
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(encodeB64(k))
            var ok: Boolean
            try { ok = tmp.renameTo(f) } catch (_: Throwable) { ok = false }
            if (!ok) {
                try { f.delete() } catch (_: Throwable) {}
                try { ok = tmp.renameTo(f) } catch (_: Throwable) { ok = false }
                if (!ok) {
                    try { f.writeText(encodeB64(k)); ok = true } catch (_: Throwable) {}
                    try { tmp.delete() } catch (_: Throwable) {}
                }
            }
            if (!ok) {
                keyDiag("★★钥匙备份落盘失败（本次未备份，下次索取重试）")
                return
            }

            // ④ 收紧到 0600 —— 与 [initHookSide] 同一纪律（备份是攻击面，不是方便）
            try {
                f.setReadable(true, true)
                f.setWritable(true, true)
                f.setExecutable(false, false)
            } catch (_: Throwable) {}

            keyDiag("★钥匙已备份到 app 私有目录 指纹=${fingerprint(k)} 位置=${f.absolutePath}")
        } catch (_: Throwable) {}
    }

    /**
     * 模块 app 私有目录下的钥匙备份文件。
     *
     * 刻意与快手侧 [keyFile] **同名同布局**（`filesDir/mj_key_v1`）——
     * 两侧共用一套相对布局、不新造目录与命名约定，只靠**进程 uid** 决定落在谁家：
     * 在 app 进程里 [appContext] 拿到的是模块 app 的 Application ⇒ 其 `filesDir` 即
     * `/data/data/io.github.angbang852.manjiao/files`。
     */
    private fun appBackupFile(): File? = appContext()?.let { File(it.filesDir, KEY_FILE) }

    /**
     * app 侧自检日志：写在 **app 私有目录** `files/keydiag.txt`
     * （沿用 [io.github.angbang852.manjiao.ui.SettingsActivity] 既有的那份自检文件）。
     *
     * 为什么不用 [Logger.evidence]：那条路写 `/sdcard/Download/ManJiao/.sys/`，
     * 而模块 app 是 targetSdk 35 的普通应用，对公共目录**没有保证可写的权限**；
     * filesDir 一定可写，且 `adb shell run-as io.github.angbang852.manjiao cat files/keydiag.txt`
     * 可直接读出验证。
     * ★ 只记指纹与路径，**绝不记钥匙内容**。
     */
    private fun keyDiag(msg: String) {
        try {
            val f = appContext()?.let { File(it.filesDir, KEY_DIAG_FILE) } ?: return
            f.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Throwable) {}
    }

    /** app 侧是否已拿到钥匙（供界面显示） */
    fun appKeyReady(): Boolean = appCached != null

    /** 最近一次索取的结果（供界面显示；**不含钥匙**） */
    @Volatile var lastDiag: String = "尚未索取"
        private set

    private val reqOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun requestAsync() {
        if (!reqOnce.compareAndSet(false, true)) return
        Thread {
            try { appKeyBlocking(1500L) } catch (_: Throwable) {}
        }.apply { name = "MJ-KeyReq"; isDaemon = true }.start()
    }

    /**
     * 发一次索取广播并等回包。
     *
     * 接收器必须 `RECEIVER_EXPORTED` —— 回包来自**另一个应用**（快手），
     * `RECEIVER_NOT_EXPORTED` 会被系统直接丢弃（AuditBridge 已用真机踩过这个坑，
     * 见其 2026-09 注释）。换来的是「第三方也能伪造回包」，故加两道护栏：
     *  ① 只接受 Base64 解出来**正好 32 字节**的 AES 钥匙；
     *  ② SDK≥34 时用 `getSentFromPackage()` 校验来源必须是快手两包之一。
     * 万一被骗进一把**假钥匙**，后果只是「解不开我们自己的密文」⇒ fail-closed
     * 报「损坏/被篡改」，**不产生任何放行、不泄露任何数据**。
     */
    private fun requestKey(timeoutMs: Long): ByteArray? {
        val ctx = appContext() ?: run { lastDiag = "拿不到 Context"; return null }
        val q = java.util.concurrent.ArrayBlockingQueue<ByteArray>(1)
        var from = "?"
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    val s = try { getSentFromPackage() } catch (_: Throwable) { null }
                    from = s ?: "?"
                    if (s != null && s != KsClass.PKG && s != KsClass.PKG_NEBULA) return
                }
                val b64 = i.getStringExtra(EXTRA_KEY) ?: return
                val k = decodeB64(b64) ?: return
                if (k.size != KEY_LEN) return
                q.offer(k)
            }
        }
        var reg = false
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(recv, IntentFilter(ACTION_KEY_RSP), Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(recv, IntentFilter(ACTION_KEY_RSP))
            }
            reg = true
            // 两个快手包都发：普通版与极速版
            for (pkg in arrayOf(KsClass.PKG, KsClass.PKG_NEBULA)) {
                try {
                    ctx.sendBroadcast(
                        Intent(ACTION_KEY_REQ).setPackage(pkg)
                            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    )
                } catch (_: Throwable) {}
            }
            val k = q.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            lastDiag = if (k == null) "无应答（快手未运行？）" else "已取得 指纹=${fingerprint(k)} 来源=$from"
            k
        } catch (t: Throwable) {
            lastDiag = "异常 ${t.javaClass.simpleName}"
            null
        } finally {
            if (reg) { try { ctx.unregisterReceiver(recv) } catch (_: Throwable) {} }
        }
    }

    // ---------------------------------------------------------------- Base64（minSdk 24，不能用 java.util.Base64）

    private fun encodeB64(b: ByteArray): String =
        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)

    private fun decodeB64(s: String): ByteArray? = try {
        android.util.Base64.decode(s.trim(), android.util.Base64.NO_WRAP)
    } catch (_: Throwable) { null }
}
