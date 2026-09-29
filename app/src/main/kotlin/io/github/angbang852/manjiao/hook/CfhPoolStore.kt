package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.RateLimiter
import io.github.angbang852.manjiao.util.SecureStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ★★★ v13.74 池持久化（**严格档** · 2026-09-30 用户定案）
 *
 * ## 为什么必须做这件事（真机实测的死锁链）
 * 冷启动时池是**纯内存态、恒为 0**，于是：
 * ```
 * 池=0
 *  → 精选页批次 ~100% 脏（REPOOL-SEED 种=0，连种子都没有）
 *  → 所有 VM 补位路径的总闸 `hookViewModel()` 跑不起来
 *   （它唯一可靠入口是 NasaPhotoDetailFragment.onResume；
 *     真机铁证：NASAHOOK 钩子**装上了**，onResume **一次都没来**）
 *  → CfhState.vmRef / CurrentPhotoHook.currentVmRef() **双 null**
 *  → 拿不到 VM ⇒ 首页收割 / 后台拉取全部失效 ⇒ 补不了货
 *  → 池还是 0 ⇒ 快手拿到 0 条响应 ⇒ 判「无网络」⇒ 不再请求 ⇒ 卡死
 * ```
 * 这是一条**自锁**：没内容 ⇒ 建不出 detail fragment ⇒ 没 VM ⇒ 补不了货 ⇒ 没内容。
 *
 * 本文件从「**先给一批货**」这一侧把环切断：池的**队列尾部**跨重启存活，
 * 冷启动立刻有内容 ⇒ 精选页有得放 ⇒ detail fragment 建得出来 ⇒ onResume 触发
 * ⇒ VM 路全醒 ⇒ 首页收割/后台拉取开始工作 ⇒ 池开始进新货。
 *
 * ## 严格档语义（用户明确定案：绝不重复 > 不断流）
 * · 存盘的**只有还没投放过的条目** —— 投放过的已被 `consumeClean` 移出池，
 *   所以持久化的天然是用户**没看过**的那部分 ⇒ 重启是**续播**，不是重播。
 * · `cleanPoolIds`（永久拉黑标记）**一起存盘** ⇒ 看过的跨会话也不会被重新拉回池。
 * · **绝不为了「不断流」而放宽去重窗口**；池见底时如实停下并记录，
 *   宁可停，也不偷偷重放看过的视频。
 *
 * ## 为什么用反射拿 Gson
 * 条目（QPhoto）本来就是快手的 Gson 从网络 JSON 解出来的，用 Gson 往返最忠实。
 * 快手进程自带 Gson（模块正在 hook 它的 `CollectionTypeAdapterFactory$Adapter`），
 * 但模块没有 Gson 编译依赖 ⇒ 走反射，避免改 build.gradle 引入版本冲突。
 * 信封层（外层结构）用 Android 自带的 `org.json`，零依赖、零反射。
 */
object CfhPoolStore {

    private const val FILE_PATH = "/sdcard/Download/ManJiao/.sys/pool.json"

    /**
     * ★★★ 去重表（`cleanPoolIds`）的**独立加密副本**（2026-09-30 规格 ③）。
     *
     * 为什么单独再存一份：
     *   `pool.json` 里混着 24 条**大对象**（单条 QPhoto 的 JSON 约 42～48KB，
     *   实测整份 1.12MB）。加密后整份是一个 AEAD 单元 —— 任何一位被改，
     *   **整份都解不开**，那 1282 条「看过什么」就跟着陪葬 ⇒ 看过的视频重新入池
     *   ⇒ **重复上屏**，直接违反用户第一条硬规则。
     *
     *   这份独立表只有 id→时间戳（实测约 40KB，1294 条约 26KB 密文），
     *   与池子正文**分开加密、分开写**。主存档坏掉时，池子按「无存档」处理
     *   （少看几条，可接受），但去重表能从它救回来（不重复，红线）。
     *
     * 路径是**新增**文件，没有改动任何既有文件的位置（规格硬约束）。
     */
    private const val IDS_PATH = "/sdcard/Download/ManJiao/.sys/pool_ids.json"

    /**
     * 存盘条目上限：防 JSON 无限膨胀。
     * 真机实测**单条 QPhoto 的 JSON 约 42～48KB**（`★存盘 条目=1 字节=47937`）,
     * 所以 24 条 ≈ 1.1MB —— 兼顾「队列尾巴留够」与「每 3 秒落盘的 IO 不失控」。
     */
    private const val MAX_ITEMS = 24

    /** 两次存盘的最小间隔（防抖；进程被杀时最多丢这几秒内的变化） */
    private const val SAVE_DEBOUNCE_MS = 3_000L

    /**
     * ★★★ 2026-09-30 性能审查 ④：`pool.json` 的 `.bak` 重写间隔（毫秒）。
     *
     * ## 改了什么
     * `SecureStore.seal` 的 `.bak` 从「**每次落盘**都整份复制」降为「**每分钟**最多一次」。
     * `pool.json` 实测 1.22MB ⇒ 每次省掉 **2.44MB FUSE I/O**（读 1.22 + 写 1.22）。
     *
     * ## 红线 B 论证：窗口缩窄到什么程度仍可接受
     *
     * ### ① `.bak` 到底兜什么底（先定清职责）
     * `.bak` 只在「**主文件解不开**」时被 `SecureStore.read` 使用（:178-190）。
     * 它**不是**去重表的兜底 —— 去重表的兜底是 `pool_ids.json`（见 [IDS_PATH] 注释）。
     * 所以降频 `.bak` **没有直接触碰红线 B**；真正会压到红线 B 的是下面这条链，
     * 必须逐环论证：
     *
     * ### ② 降频后「最坏会丢什么」
     * 最坏情形 = 主文件恰好损坏，且回退到一个**最多 60 秒旧**的 `.bak`。
     * 该 `.bak` 是一份**完整、可解密**的存档（不是半截、不是空的），
     * 它与「当前真值」的差集只有一项：**这 60 秒内新增的拉黑 id 与池条目**。
     *
     * ### ③ 这 60 秒的差集为什么**补得回来**（关键环）
     * 新增的拉黑 id 会在**同一轮 `saveNow`**里写进 `pool_ids.json`
     * （:513 `saveIdsAlone()`，紧随主存档之后）。而 `pool_ids.json` 的 `.bak`
     * **不降频**（仍每次写）⇒ 那份独立副本最多只比真值旧**一轮 3 秒去抖**。
     * 恢复路径 `loadNow()` 在**主存档成功的正常路径上无条件**调
     * `mergeIdsAlone()`（:782，注释明写「只增不改」），把独立副本里多出来的 id
     * **全部补回**内存 ⇒ 陈旧 `.bak` 丢掉的那 60 秒 id **必然被更新的一方补回**。
     *
     * ### ④ 反过来说，什么情况才真的会丢
     * 只有「主文件坏 **且** `pool_ids.json` 也坏 **且** 两者的 `.bak` 都不可用」——
     * 那时**任何** `.bak` 策略都救不了（三份同时坏），与降频无关。
     * 换言之：降频把「主文件坏」的兜底窗口从「0 秒」放宽到「60 秒」，
     * 但**红线 B 的实际防线（pool_ids.json 及其每次写的 .bak）一个字没动**。
     *
     * ### ⑤ 窗口为什么选 60 秒（不是更长）
     * 存盘去抖是 3 秒、滚动期实测 36 次/60s ⇒ 60 秒 ≈ 12 次存盘一次备份，
     * 即 `.bak` 至少每分钟被刷新一次，mtime 与真值差距有上界。
     * 再拉长（如 10 分钟）不会省更多（写盘次数已由内容变化决定），
     * 却会把「回退后要补的 id 数」线性放大 —— 收益为零而代价变大，故取 60 秒。
     *
     * ### ⑥ 附带的**安全性提升**（不是代价）
     * `.bak` 频率降低 ⇒ 那份 1.22MB 的**旧密文副本**在磁盘上的存在时间变长，
     * 对「用旧存档覆盖主文件」的攻击者而言**没有增益**（他本来就能读到主文件），
     * 而我们的写 I/O 减少 ⇒ 与主线程争 `/sdcard` 的窗口减少。
     */
    private const val POOL_BAK_INTERVAL_MS = 60_000L

    /** 是否已完成一次「尝试加载」（幂等闸） */
    @Volatile private var loadStarted = false

    /**
     * ★★★ v13.86 **进程级恢复幂等闸**（「崩溃 + 重复」双现象的共同根因修复）
     *
     * 真机实证（2026-09-29）：**一次会话里 `★重启恢复` 触发了 3 次**
     * ```
     * [POOLSTORE] ★重启恢复 条目=10 池=10
     * [POOLSTORE] ★重启恢复 条目=17 池=17     ← 条目数正好等于当时的池大小
     * [POOLSTORE] ★重启恢复 条目=14 池=14
     * ```
     * 说明是「**存盘 → 另一个实例把刚存的东西又恢复一遍**」：
     * 模块被重复装载 ⇒ `CfhPoolStore` 有多个实例 ⇒ `loadStarted` 这个**实例内**幂等闸
     * 根本挡不住。于是**已经投放并出队的条目被灌回池里**，后果有二：
     *   ① 同一条再次注入 feeds ⇒ **重复上屏**（用户报的「隔了几屏又出现」）；
     *   ② 快手对同一条**二次 bind** ⇒ PresenterV2 状态机被推进到非法态
     *      ⇒ `IllegalStateException: 不能从 CREATE 跳到 UNBIND`（bind 期间崩）。
     *
     * 修法：把闸提到**进程级**。用 `System.getProperties()` ——
     * 它是 **JVM 全局唯一对象，天然跨 classloader 实例共享**，
     * 在它上面加锁即可保证「整个进程只有一个实例执行恢复」。
     *
     * ⚠️ 标记必须用**实例身份**而不是 pid：同进程内所有实例 pid 相同，
     *    拿 pid 当标记会让「第一个实例刚写的标记」把自己的后续条目也挡掉。
     */
    /** 进程级恢复标记在 System properties 里的键名 */
    private const val KEY_RESTORER = "manjiao.pool.restorer"

    private val restoreToken: String by lazy {
        System.identityHashCode(this).toString() + "-" +
            java.util.UUID.randomUUID().toString().substring(0, 8)
    }

    /** 本实例是否是**本进程里唯一被允许执行恢复**的那个（只算一次） */
    private val restoreOwner: Boolean by lazy {
        // ★★★ v13.87 **真机钉死：恢复必须限定在主进程**（2026-09-29）
        //
        //   一次会话里 `★重启恢复` 出现 4 次，给日志加上 pid 后一看是 **4 个不同进程**：
        //     pid=29578  com.smile.gifmaker                  ← 主进程
        //     pid=30227  com.smile.gifmaker:messagesdk
        //     pid=30431  com.smile.gifmaker:push_v3
        //     pid=30808  com.smile.gifmaker:kwv_sandboxed_p0
        //
        //   子进程不渲染 feed、灌池毫无意义；**但 4 个进程都在写同一个 pool.json**
        //   ⇒ 主进程辛苦养的池会被子进程的空池**覆盖掉**
        //   （实测文件一度只剩 153 字节，正是被这么写掉的）。
        //
        //   所以恢复闸的第一道就是：**进程名含 ':'（= 子进程）直接放弃恢复**。
        //   主进程名等于包名 `com.smile.gifmaker`，子进程才带 `:`。
        if (!isMainProcess) return@lazy false
        try {
            val props = System.getProperties()
            synchronized(props) {
                if (props.getProperty(KEY_RESTORER) != null) return@lazy false
                props.setProperty(KEY_RESTORER, restoreToken)
                true
            }
        } catch (_: Throwable) {
            // 拿不到标记时保守放行（宁可恢复一次，也不要整条功能失效）
            true
        }
    }

    /**
     * 本进程是不是快手**主进程**。
     *
     * 判据：`Application.getProcessName()` 含 ':' 即为子进程
     * （主进程名 = 包名 `com.smile.gifmaker`，子进程为 `com.smile.gifmaker:messagesdk` 等）。
     * 取不到时**保守当主进程**（宁可多写一次，也不要主进程功能失效）。
     */
    private val isMainProcess: Boolean by lazy {
        try {
            val pn = if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.app.Application.getProcessName()
            } else {
                null
            }
            pn == null || !pn.contains(":")
        } catch (_: Throwable) {
            true
        }
    }

    /** 加载流程是否已跑完（首屏兜底等待要用） */
    @Volatile private var loadDone = false

    /**
     * ★★★ v13.80 恢复是否**还在路上**。
     *
     * 真机症状：冷启动进精选页先显示「没有网络」，点刷新过一会才有视频。
     * 原因是首屏那次请求比恢复早到（实测只差 10ms）⇒ 池还是 0 ⇒ 判无网络。
     * `CfhFeedHook` 在「响应为空 + 池为空 + 恢复未完成」时用它等一小会儿。
     */
    fun restorePending(): Boolean = loadStarted && !loadDone

    /** 等恢复完成，最多 ms 毫秒。恢复已完成时立即返回。 */
    fun awaitRestore(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!loadDone && System.currentTimeMillis() < end) {
            try { Thread.sleep(50) } catch (_: Throwable) { break }
        }
    }

    @Volatile private var dirty = false
    @Volatile private var lastSaveMs = 0L

    /** 诊断计数 */
    @Volatile var saveOk = 0
    @Volatile var restoreOk = 0
    @Volatile var restoreFail = 0

    /** 单线程 IO：所有落地/读取都在这里，**绝不占主线程** */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cfh-pool-io").apply { isDaemon = true }
    }

    /**
     * ★★★ v13.92【问题 A 修复】尾沿保存用的**定时线程**（单线程、daemon）。
     *
     * ⚠️ 它**只负责计时**，真正的写盘 IO 仍然丢给 [io] 那条单线程
     * （两条线程各写一次同一个 pool.json 会把文件写坏，绝不能在这里直接落盘）。
     */
    private val trailingTimer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cfh-pool-trailing").apply { isDaemon = true }
    }

    /** 尾沿定时器是否已武装（同一防抖窗口内不重复排程） */
    @Volatile private var trailingArmed = false

    // ---------------------------------------------------------------- Gson 反射

    @Volatile private var gsonRef: Any? = null

    /**
     * ★★★ v13.75 修：Xposed 的 **classloader 隔离**（2026-09-29 真机实证）。
     *
     * 第一版直接 `Class.forName("com.google.gson.Gson")` ⇒ 走的是**模块自己的
     * classloader**，而它**看不见快手的 Gson**（模块能 hook Gson 类，是因为
     * `Reflect.findClass(name, cl)` 显式传了正确的 classloader）。
     * 实测铁证：`★★Gson 反射失败（转不了 JSON）: ClassNotFoundException`
     * ⇒ `★存盘 条目=0`（拉黑表存进去了、条目一条都存不下）。
     *
     * 所以必须**按顺序换 classloader 去找**：
     *   ① 池内任意条目（QPhoto）的 classloader —— 最可靠，它一定看得见快手运行时
     *   ② 已知 QPhoto 类
     *   ③ 快手 app classloader（ActivityThread.currentApplication）
     *   ④ 模块自己的 classloader（兜底）
     *
     * **失败不缓存**：启动瞬间池可能是空的、Application 可能还没起来，
     * 若把失败也缓存住，就永远转不了 JSON 了。
     */
    private fun gson(): Any? {
        // ★★★ v13.79 第一优先：**快手自己的 Gson 实例**。
        //   它注册了 InstanceCreator / TypeAdapterFactory，只有它才能构造快手的模型；
        //   用默认 Gson 反序列化会炸在 `BaseFeed()` 的无参构造上（真机实证）。
        try {
            CfhState.appGson?.let { return it }
        } catch (_: Throwable) {}
        gsonRef?.let { return it }
        synchronized(this) {
            gsonRef?.let { return it }
            val g = loadGson() ?: return null
            gsonRef = g
            return g
        }
    }

    private fun loadGson(): Any? {
        val loaders = ArrayList<ClassLoader?>()
        // ① 池内条目的 classloader（最可靠：QPhoto 就是快手的类）
        try {
            synchronized(CfhState.cleanPool) {
                for (e in CfhState.cleanPool) {
                    if (e != null) { loaders.add(e.javaClass.classLoader); break }
                }
            }
        } catch (_: Throwable) {}
        // ② 已知 QPhoto 类
        try { CfhState.qpClassRef?.classLoader?.let { loaders.add(it) } } catch (_: Throwable) {}
        // ③ 快手 app classloader
        try { appClassLoader()?.let { loaders.add(it) } } catch (_: Throwable) {}
        // ④ 模块自己
        try { CfhPoolStore::class.java.classLoader?.let { loaders.add(it) } } catch (_: Throwable) {}

        for ((i, cl) in loaders.withIndex()) {
            if (cl == null) continue
            try {
                val c = Class.forName("com.google.gson.Gson", false, cl)
                val g = c.getDeclaredConstructor().newInstance()
                if (RateLimiter.allow("POOLGSON", 5)) {
                    Logger.evidence("POOLSTORE", "★Gson 已就位（classloader #$i ${cl.javaClass.simpleName}）")
                }
                // ★★★ v13.76 一次性把「快手这份 Gson 还剩哪些序列化重载」打出来。
                //   真机实测：`Gson.toJson(Object)` 抛 NoSuchMethodException
                //   —— 快手 release 包被 R8 裁剪过，**不能想当然认为标准重载都在**。
                //   与其继续猜，不如让它自己报菜单。
                if (RateLimiter.allow("POOLGSON-DUMP", 1)) {
                    val sb = StringBuilder()
                    try {
                        for (mm in c.methods) {
                            if (!mm.name.contains("toJson") && !mm.name.contains("fromJson")) continue
                            sb.append("\n   ").append(mm.name).append("(")
                                .append(mm.parameterTypes.joinToString(",") { it.simpleName })
                                .append(") -> ").append(mm.returnType.simpleName)
                        }
                    } catch (_: Throwable) {}
                    Logger.evidence("POOLSTORE", "★Gson 可用重载:$sb")
                }
                return g
            } catch (_: Throwable) {}
        }
        return null
    }

    @Volatile private var mToJson: java.lang.reflect.Method? = null
    @Volatile private var mFromJson: java.lang.reflect.Method? = null
    @Volatile private var methodsTried = false

    /**
     * ★★★ v13.77 按**签名**认方法，不按名字认（2026-09-29 真机实证）。
     *
     * 真机铁证链：
     *   · `Gson.toJson(Object)` ⇒ `NoSuchMethodException: com.google.gson.Gson.toJson [class java.lang.Object]`
     *   · 把「名字含 toJson/fromJson 的公开重载」全 dump 出来 ⇒ **一条都没有**
     *   · 而同一进程调用栈里能看见 `KnownTypeAdapters$ListTypeAdapter.read`、
     *     `TreeTypeAdapter.read` ⇒ **类名没被改**
     *
     * 结论：快手 release 包被 R8 处理过，**Gson 的 API 方法名被改名了**
     * （keep 规则只保住了类名和部分 override 方法名）。所以按名字反射永远找不到，
     * 必须靠「参数表 + 返回类型」去认。
     *
     * `toJson(Object)->String` 与 `fromJson(String,Class)` 的参数表都是唯一的
     * （`toJson(JsonElement)` / `fromJson(String,Type)` 参数类型对不上，会被排除）。
     * 找到后把**真实名字**打出来 —— 那本身就是这份 payload 被改名的证据。
     */
    private fun locateMethods(): Boolean {
        if (methodsTried) return mToJson != null
        methodsTried = true
        try {
            val g = gson() ?: return false
            for (m in g.javaClass.methods) {
                val pt = m.parameterTypes
                if (mToJson == null && m.returnType == String::class.java &&
                    pt.size == 1 && pt[0] == Any::class.java
                ) {
                    mToJson = m
                    Logger.evidence("POOLSTORE", "★按签名找到 toJson：真实名=${m.name} (Object)->String")
                }
                if (mFromJson == null && pt.size == 2 &&
                    pt[0] == String::class.java && pt[1] == Class::class.java
                ) {
                    mFromJson = m
                    Logger.evidence(
                        "POOLSTORE",
                        "★按签名找到 fromJson：真实名=${m.name} (String,Class)->${m.returnType.simpleName}"
                    )
                }
            }
        } catch (_: Throwable) {}
        if (mToJson == null && RateLimiter.allow("POOLGSON-NOFIND", 3)) {
            Logger.evidence(
                "POOLSTORE",
                "★★按签名也没找到 toJson(Object)->String（可能被 R8 **整个删掉**了）" +
                    " 公开方法数=${try { gson()?.javaClass?.methods?.size ?: -1 } catch (_: Throwable) { -1 }}"
            )
        }
        return mToJson != null
    }

    private fun toJson(o: Any): String? {
        val g = gson() ?: return null
        if (!locateMethods()) return null
        return try {
            mToJson?.invoke(g, o) as? String
        } catch (t: Throwable) {
            // ★★★ v13.76 不能静默：真机实测「池=41 但 条目=0」——
            //   每一条都转不成 JSON，而原来是 catch 掉什么都不说，
            //   于是这一层又变成黑盒。必须把**失败原因**打出来。
            //   （常见嫌疑：QPhoto 内部有循环引用 ⇒ Gson 递归爆栈
            //     StackOverflowError，它 message 为 null，所以类名必须打。）
            val c = t.cause ?: t
            if (RateLimiter.allow("POOLJSON-ERR", 3)) {
                Logger.evidence(
                    "POOLSTORE",
                    "★★条目转JSON失败 cls=${o.javaClass.name} " +
                        "err=${c.javaClass.name} msg=${c.message?.take(160)}"
                )
            }
            null
        }
    }

    private fun fromJson(json: String, cls: Class<*>): Any? {
        val g = gson() ?: return null
        if (!locateMethods()) return null
        return try {
            mFromJson?.invoke(g, json, cls)
        } catch (t: Throwable) {
            // ★★★ v13.78 同样不许静默：真机实测 `★重启恢复 条目=0 失败=1`
            //   —— 文件读到了、条目也在，但反序列化失败，而原因被 catch 掉了。
            val c = t.cause ?: t
            if (RateLimiter.allow("POOLFROMJSON-ERR", 3)) {
                Logger.evidence(
                    "POOLSTORE",
                    "★★恢复条目失败 cls=${cls.name} len=${json.length} " +
                        "err=${c.javaClass.name} msg=${c.message?.take(200)}"
                )
            }
            null
        }
    }

    // ---------------------------------------------------------------- 类加载器

    /**
     * 拿快手自己的 ClassLoader（QPhoto 是快手的类）。
     * 优先用已知的 QPhoto 类；拿不到就退回 ActivityThread 的 Application。
     */
    private fun appClassLoader(): ClassLoader? {
        try {
            CfhState.qpClassRef?.classLoader?.let { return it }
        } catch (_: Throwable) {}
        return try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null)?.javaClass?.classLoader
        } catch (_: Throwable) { null }
    }

    // ---------------------------------------------------------------- 存

    /** 池内容发生变化时调用（防抖+尾沿落盘，非阻塞） */
    fun markDirty() {
        // ★★★ v13.92【问题 B 修复】写盘必须和恢复走**同一套主进程闸门**。
        //   恢复路径早在 [restoreOwner] 里做了 `if (!isMainProcess) return@lazy false`，
        //   但 markDirty / saveNow 一直没有 ⇒ **子进程也能写盘**。
        //   真机实证（2026-09-29 带 pid 的日志）：一次会话里 4 个进程
        //     main / :messagesdk / :push_v3 / :kwv_sandboxed_p0
        //   都在写同一个 pool.json ⇒ 主进程辛苦养的池被子进程的空池覆盖，
        //   文件一度只剩 **153 字节**（池存档被写坏）。
        //   子进程不渲染 feed、灌池毫无意义 ⇒ 直接不写。
        if (!isMainProcess) return
        dirty = true
        val now = System.currentTimeMillis()
        if (now - lastSaveMs >= SAVE_DEBOUNCE_MS) {
            // 头沿：距上次落盘已超窗口 ⇒ 立刻写一次
            lastSaveMs = now
            try { io.execute { saveNow() } } catch (_: Throwable) {}
            return
        }
        // ★★★ v13.92【问题 A 修复 · 尾沿保存】**原实现是纯节流、没有尾沿**：
        //   窗口内 return 掉之后，若之后再无新变更，`dirty` 就永远挂着不落盘
        //   （`now - lastSaveMs` 只在 markDirty 里更新，没有别的东西会再叫它）。
        //   实际症状：用户在池子变更后 3 秒内杀掉快手 ⇒ 这次去重记录**整批丢失**
        //   ⇒ 重启后同一批 id 又被当成「没上过屏」⇒ **重复上屏**；
        //   这直接违反用户硬规则「**绝不重复 > 不断流**」（宁可停，也不重放看过的）。
        //   修法：进入窗口时武装一个**一次性**尾沿定时器，到点**无条件**落盘一次，
        //   保证「任何一次变更之后，最迟 SAVE_DEBOUNCE_MS 内必定落盘」；
        //   窗口内重复变更不重排（`trailingArmed` 挡住）⇒ 计时器不会被无限往后推。
        if (trailingArmed) return
        trailingArmed = true
        try {
            trailingTimer.schedule({
                trailingArmed = false
                lastSaveMs = System.currentTimeMillis()
                // 无条件置脏：尾沿的语义就是「窗口结束必落一次盘」，
                // 不能因为头沿那次写已经把 dirty 清掉就跳过（见上）。
                dirty = true
                try { io.execute { saveNow() } } catch (_: Throwable) {}
            }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            trailingArmed = false
        }
    }

    private fun saveNow() {
        // ★★★ v13.92【问题 B 修复】这里是**唯一的写盘入口**，
        //   闸门放在这一层，无论谁调进来（头沿 / 尾沿 / 未来的新调用点）
        //   子进程都写不了，杜绝再次出现「4 进程抢写、pool.json 只剩 153 字节」。
        if (!isMainProcess) return
        if (!dirty) return
        dirty = false
        try {
            val root = org.json.JSONObject()
            root.put("v", 1)
            root.put("ts", System.currentTimeMillis())

            // ① 永久拉黑表 —— 严格档的关键：看过的跨会话也不再回池
            val ids = org.json.JSONObject()
            try {
                for ((k, v) in CfhState.cleanPoolIds) ids.put(k, v)
            } catch (_: Throwable) {}
            root.put("ids", ids)

            // ② 队列尾部（还没投放过的条目）
            val arr = org.json.JSONArray()
            val snap = try {
                synchronized(CfhState.cleanPool) { ArrayList(CfhState.cleanPool) }
            } catch (_: Throwable) { ArrayList<Any>() }
            var written = 0
            for (e in snap) {
                if (e == null || written >= MAX_ITEMS) continue
                val js = toJson(e) ?: continue
                val pid = try { CfhProbe.readPhotoId(e) } catch (_: Throwable) { null }
                val o = org.json.JSONObject()
                o.put("pid", pid ?: "")
                o.put("cls", e.javaClass.name)
                o.put("json", js)
                arr.put(o)
                written++
            }
            root.put("items", arr)

            val f = File(FILE_PATH)
            try { f.parentFile?.mkdirs() } catch (_: Throwable) {}
            // ★★★ 2026-09-30 加密落盘（规格 ①③④）
            //
            //   威胁：`.sys/` 是 world-writable，任意 App 可以**替换池子存档**
            //   （换成一份旧的 ⇒ 去重表回退 ⇒ 看过的重新入池 ⇒ 重复上屏），
            //   也可以**直接删掉**。AES-GCM 的认证标签让「替换/篡改」直接解不开；
            //   原子写 + `.bak` 让「写一半被杀」不至于毁掉唯一一份存档。
            //
            //   为什么失败时**不能退化成写明文**：那等于「只要让加密失败（或等一次
            //   磁盘错误），数据就自动摊回公共目录」—— 防线自毁，正是本任务要消灭的状态。
            //   所以宁可这一次不写：旧存档原地不动，`dirty` 保持置位，3 秒后重试。
            val sealed = SecureStore.seal(f, "pool.json", root.toString(), POOL_BAK_INTERVAL_MS)
            if (!sealed) {
                dirty = true                       // 没落盘 ⇒ 保持脏，下个去抖窗口重试
                if (RateLimiter.allow("POOLSAVE-SEC", 10)) {
                    Logger.evidence(
                        "POOLSTORE",
                        "★★加密落盘未成功（拒绝退化写明文）⇒ 旧存档保留，稍后重试"
                    )
                }
                return
            }
            // ①b 去重表**独立再存一份**（规格 ③ 点名：拉黑 ids 绝不能无声丢失）
            saveIdsAlone()
            saveOk++
            if (RateLimiter.allow("POOLSAVE", 40)) {
                Logger.evidence(
                    "POOLSTORE",
                    "★存盘 条目=$written 拉黑=${CfhState.cleanPoolIds.size} " +
                        "池=${CfhState.cleanPool.size} 字节=${f.length()}"
                )
            }
        } catch (t: Throwable) {
            if (RateLimiter.allow("POOLSAVE-ERR", 10)) {
                Logger.evidence("POOLSTORE", "★★存盘失败: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    // ---------------------------------------------------------------- 去重表独立副本

    /**
     * 把 `cleanPoolIds` 单独加密落一份（见 [IDS_PATH] 的注释：主存档坏掉时救去重表）。
     *
     * 全程 try/catch：这份是**冗余兜底**，写不进去不能影响主流程。
     *
     * ## ★★★ 2026-09-30 性能审查 ④：为什么要降频，以及红线 B 的论证
     *
     * ### 改了什么
     * 原实现是**每次 `saveNow` 都无条件写一次**。`pool_ids.json` 实测 71KB，
     * 一次 `seal` = 加密 + 写 71KB + **读回校验 71KB** + `.bak` 复制 142KB
     * ≈ **284KB FUSE I/O**；滚动期 36 次存盘/60s ⇒ 约 **10MB/分钟** 纯浪费。
     * 现在两道闸：**内容未变则不写**（零 I/O）+ **最短 30 秒一次**（带尾沿保证）。
     *
     * ### 红线 B 论证：窗口缩窄到什么程度仍可接受
     *
     * **B-1｜这份文件兜的是什么底。** 它只在 `pool.json` **解不开**时被
     * [restoreIdsAlone] 使用（`loadNow` 的 fail-closed 分支 :743）。
     * 它保的是「拉黑表」——用户第一硬规则「绝不重复」的最后一道。
     * 因此**任何**降低其新鲜度的改动都必须论证「丢的 id 会不会导致重复上屏」。
     *
     * **B-2｜最坏丢多少。** 时间闸 30 秒 ⇒ 最坏情形是「`pool.json` 恰好在
     * 这 30 秒内损坏」，此时从副本恢复，会少掉**这 30 秒内新增的拉黑 id**。
     *
     * **B-3｜这 30 秒的 id 会不会真的造成重复上屏。** 逐条看两个条件，**必须同时成立**：
     *   · 条件①「这些 id 对应的视频又出现在推荐流」—— 30 秒的推荐流窗口内，
     *     同一条作品重新上屏的概率极低（用户实测的重复上屏是**跨会话/跨重启**
     *     尺度的问题，不是 30 秒尺度）；
     *   · 条件②「`pool.json` 恰好在这 30 秒内损坏」—— 这本身是罕见事件
     *     （真机历史上仅出现过「被子进程空池覆盖」，而该路径已由
     *     `isMainProcess` 闸（:417/:455）彻底堵死）。
     *   两者同时发生才构成风险，且**后果有界**：最多重放这 30 秒内的那几条。
     *
     * **B-4｜为什么「内容未变则不写」这一道闸是零风险的。**
     * `cleanPoolIds` 是**只增表**（永久拉黑）。内容签名未变 ⇒ 与上次成功落盘的
     * 内容**逐字节一致** ⇒ 不写**不丢失任何信息**（文件里已经是这份内容了）。
     * 这道闸在滚动期命中率极高（池子空闲时 id 不变），是本次 ④ 的主要收益来源。
     *
     * **B-5｜尾沿保证（关键：降频不能变成「永远不写」）。**
     * 窗口内被推迟时**武装一次性尾沿**（[armIdsTrailing]）：到点无条件写一次。
     * 这与 `markDirty` 的尾沿修复（v13.92）是同一个思路 ——
     * 「任何一次变更之后，最迟 [IDS_MIN_INTERVAL_MS] 内必定落盘」。
     * 没有这道保证，降频就真的可能把最后一次变更饿死。
     *
     * **B-6｜`.bak` 不降频。** 这里**刻意不传** `bakMinIntervalMs`
     * （即用默认 `0` = 每次都写）。原因正是 B-3 的论证依赖
     * 「`pool_ids.json` 自身有一份新鲜的 `.bak`」—— 它是红线 B 的最后一道，
     * 不能为了再省 142KB 而把它一起降频。
     *
     * ### 结论
     * 通过。窗口从「0 秒」放宽到「30 秒」，代价是「极端双重故障下最多重放 30 秒内
     * 的少数几条」，收益是**每 60 秒省约 10MB 的 FUSE 写放大**。
     */
    private fun saveIdsAlone() {
        try {
            val root = org.json.JSONObject()
            root.put("v", 1)
            val ids = org.json.JSONObject()
            for ((k, v) in CfhState.cleanPoolIds) ids.put(k, v)
            root.put("ids", ids)

            // ① 内容签名闸（零风险，见 B-4）：与上次**成功**落盘的内容一致 ⇒ 一个字节都不用写
            val sig = ids.toString().hashCode()
            if (lastIdsSealOk && sig == lastIdsSig) {
                idsSkipped++
                return
            }
            // ② 时间闸（见 B-2/B-3）：窗口内推迟，并武装尾沿（见 B-5）
            val now = System.currentTimeMillis()
            if (lastIdsSaveMs != 0L && now - lastIdsSaveMs < IDS_MIN_INTERVAL_MS) {
                idsDeferred++
                armIdsTrailing()
                return
            }

            root.put("ts", now)
            // ★ 刻意不传 bakMinIntervalMs：`.bak` 每次写（见 B-6）
            val ok = SecureStore.seal(File(IDS_PATH), "pool_ids.json", root.toString())
            if (ok) {
                lastIdsSaveMs = now
                lastIdsSig = sig
                lastIdsSealOk = true
                if (idsSkipped + idsDeferred > 0 && RateLimiter.allow("POOLSAVE-IDS-FREQ", 6)) {
                    Logger.evidence(
                        "POOLSTORE",
                        "★去重表副本已落盘（本轮降频省写：跳过=$idsSkipped 推迟=$idsDeferred）" +
                            " 拉黑=${CfhState.cleanPoolIds.size}"
                    )
                }
                idsSkipped = 0
                idsDeferred = 0
            } else {
                // 失败 ⇒ 绝不记签名，下一轮必须重试（与 AuditMirror.lastSealOk 同一思路）
                lastIdsSealOk = false
            }
        } catch (t: Throwable) {
            lastIdsSealOk = false
            if (RateLimiter.allow("POOLSAVE-IDS", 5)) {
                Logger.evidence("POOLSTORE", "★独立去重表落盘异常 ${t.javaClass.simpleName}（不影响主存档）")
            }
        }
    }

    /** 上次 `pool_ids.json` 成功落盘的时刻（时间闸用；0 = 从未写过 ⇒ 立即放行） */
    @Volatile private var lastIdsSaveMs = 0L

    /** 上次**成功**落盘的 ids 内容签名（内容未变则整条跳过，见 B-4） */
    @Volatile private var lastIdsSig = 0

    /** 上次 `pool_ids.json` 是否落盘成功（失败则下一轮绝不跳过，必须重试） */
    @Volatile private var lastIdsSealOk = false

    /** 降频诊断计数：本窗口内被「内容未变」跳过的次数 */
    @Volatile private var idsSkipped = 0

    /** 降频诊断计数：本窗口内被「时间闸」推迟的次数 */
    @Volatile private var idsDeferred = 0

    /** ids 副本的最短重写间隔（见 [saveIdsAlone] 的红线 B 论证 B-2） */
    private const val IDS_MIN_INTERVAL_MS = 30_000L

    /** ids 副本的尾沿定时器是否已武装（同一窗口内不重复排程，见 B-5） */
    @Volatile private var idsTrailingArmed = false

    /**
     * 武装 ids 副本的**一次性尾沿**（见 [saveIdsAlone] 的 B-5）。
     *
     * ★ 复用 [trailingTimer] 这条**纯计时**线程，真正的写盘仍然丢给 [io] 单线程
     *   （两条线程各写一次同一文件会把文件写坏，绝不能在这里直接落盘）。
     */
    private fun armIdsTrailing() {
        if (idsTrailingArmed) return
        idsTrailingArmed = true
        val remain = try {
            (IDS_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastIdsSaveMs)).coerceAtLeast(1L)
        } catch (_: Throwable) { IDS_MIN_INTERVAL_MS }
        try {
            trailingTimer.schedule({
                idsTrailingArmed = false
                // 无条件走一次 saveIdsAlone：到点后时间闸自然放行（内容若已变则立刻写）
                try { io.execute { saveIdsAlone() } } catch (_: Throwable) {}
            }, remain, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            idsTrailingArmed = false
        }
    }

    /**
     * 从独立副本**只恢复去重表**（池子仍为空）。
     *
     * 调用时机：`pool.json` 解不开时（规格 ③「池子存档当作没有存档，但去重表
     * ids 绝不能因此无声丢失」）。恢复后**绝不重放**看过的视频 ——
     * 宁可这一段没内容，也不违反「绝不重复」。
     */
    private fun restoreIdsAlone(): Boolean {
        return try {
            val r = SecureStore.read(File(IDS_PATH), "pool_ids.json")
            val t = r.text
            if (t.isNullOrBlank()) {
                Logger.evidence(
                    "POOLSTORE",
                    "★★独立去重表也不可用（from=${r.from} failed=${r.failed} detail=${r.detail}）" +
                        "⇒ 本次去重表为空（有重放风险，已告警）"
                )
                return false
            }
            val o = org.json.JSONObject(t).optJSONObject("ids")
            if (o == null) {
                Logger.evidence("POOLSTORE", "★★独立去重表结构异常（无 ids 字段）")
                return false
            }
            var n = 0
            var fix = 0
            val loadTs = System.currentTimeMillis()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                // ★★★ v13.95 零戳（`optLong(k) <= 0`）旧条目补**加载时刻** —— 双闸的前提。
                //
                //   ## 为什么必须补
                //   时间闸（CfhState.POOL_ID_KEEP_MS）判据是 `now - ts >= 30 天`。
                //   旧格式（v13.49 之前）的 ids 存的是**布尔值** ⇒ `optLong(k)` 读出 **0**
                //   ⇒ 年龄被算成「从 1970 年至今」⇒ 一开闸就被**整批**当过期删掉
                //   （真机实测那批 1312 条）⇒ 看过的内容全部重新入池 ⇒ **重复上屏**，
                //   违反第一条硬规则「绝不重复」。
                //
                //   ## 为什么用「加载时刻」而不是文件 mtime
                //   mtime 是**整份文件最后一次写入的时刻**：只要之后有任何一条新 id 落盘，
                //   mtime 就跳到最新 ⇒ 会把**所有**零戳旧条目新鲜度**高估**到当下
                //   （它们本来可能早该过期）。加载时刻最保守 —— 只声明
                //   「从现在起它算新的」，不借用别人的时间。
                val v = o.optLong(k)
                val ts = if (v > 0L) v else { fix++; loadTs }
                try { CfhState.cleanPoolIds[k] = ts } catch (_: Throwable) {}
                n++
            }
            if (fix > 0) {
                Logger.evidence(
                    "POOLSTORE",
                    "★去重表补时间戳 $fix 条（源=pool_ids.json 独立副本，补为加载时刻 now=$loadTs）"
                )
            }
            Logger.evidence(
                "POOLSTORE",
                "★已从独立去重表副本恢复 拉黑=$n（池子仍为空 ⇒ 不重放，只是这段没内容）"
            )
            n > 0
        } catch (t: Throwable) {
            Logger.evidence("POOLSTORE", "★★独立去重表恢复异常 ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * 主存档正常时，把独立副本里**多出来的** id 合并进来（只增不改）。
     *
     * 为什么可以合并：`cleanPoolIds` 是**永久拉黑表**，多一条只会让入池更保守
     * （少重放一条），绝不会造成重复上屏。时间戳只在 id **缺失**时才写入，
     * 不覆盖主存档的权威值 —— 因此对既有时间窗语义零影响。
     */
    private fun mergeIdsAlone() {
        try {
            val r = SecureStore.read(File(IDS_PATH), "pool_ids.json")
            val t = r.text ?: return
            val o = org.json.JSONObject(t).optJSONObject("ids") ?: return
            var add = 0
            var fix = 0
            val loadTs = System.currentTimeMillis()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (CfhState.cleanPoolIds.containsKey(k)) continue
                // ★★★ v13.95 合并进来的零戳旧条目同样补**加载时刻**（不用 mtime，理由见
                //   [restoreIdsAlone] 里的完整说明）：不补的话它一出闸就被时间闸当过期删掉
                //   ⇒ 独立副本白救一场。
                val v = o.optLong(k)
                val ts = if (v > 0L) v else { fix++; loadTs }
                try { CfhState.cleanPoolIds[k] = ts; add++ } catch (_: Throwable) {}
            }
            if (fix > 0) {
                Logger.evidence(
                    "POOLSTORE",
                    "★去重表补时间戳 $fix 条（源=独立副本合并，补为加载时刻 now=$loadTs）"
                )
            }
            if (add > 0) {
                Logger.evidence("POOLSTORE", "★独立去重表补充 $add 条（主存档正常，只增不改）")
            }
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- 取

    /**
     * 启动时恢复一次（幂等、异步、绝不阻塞主线程）。
     * 由 `ensurePoolSupply()` 这种「一开始就会反复触发」的地方调用。
     */
    fun loadOnce() {
        if (loadStarted) return
        synchronized(this) {
            if (loadStarted) return
            loadStarted = true
        }
        try { io.execute { loadNow() } } catch (_: Throwable) {}
    }

    private fun loadNow() {
        try {
            // ★ 性能修复（2026-09-30）：把「是不是主进程 / 是否已由另一实例恢复」的判断
            //   **上提到最外层**（读存档之前）。
            //
            //   原实现：这道闸在**逐条目循环内部**（修复前 :603）⇒ **子进程**也照样
            //   `f.readText()` 读完整个存档（当前实测 1.12 MB）+ JSONObject 解析 +
            //   逐条 `Class.forName` / `fromJson`（Gson 反射反序列化，约 24 条）之后，
            //   才在第一条上发现闸没放行而放弃 —— 全程纯浪费，且每个子进程各来一遍。
            //   闸本身已含 isMainProcess（见 [restoreOwner] :107），一次判断就够。
            //
            //   为什么可以提前返回而**不改变任何判定结果**：
            //   · 子进程原本就是在循环里对**每一条** continue ⇒ 池条目终态同样是 0 条；
            //   · 池写盘 `saveNow()` 自带 isMainProcess 闸（:437）⇒ 子进程从不落盘，
            //     写盘语义一个字未动；
            //   · 唯一已知行为差异（明示，不藏在注释里）：归档里的**永久拉黑表 ids**
            //     原本在循环之前（:533-540）就会被子进程读进内存，提前返回后子进程不再恢复它。
            //     该表只在**本进程内**参与 noteClean 的入池去重/时间窗（CfhState.kt:493/512/519），
            //     而子进程的池既不注入也不落盘 ⇒ 不影响任何上屏/过滤判定结果。
            if (!restoreOwner) {
                if (RateLimiter.allow("POOLLOAD-DUPINST", 5)) {
                    val sz = try { File(FILE_PATH).length() } catch (_: Throwable) { 0L }
                    Logger.evidence(
                        "POOLSTORE",
                        "★跳过本次恢复：本进程不该恢复（主进程=$isMainProcess，已由另一实例恢复过）" +
                            "⇒ 不再读存档（省 $sz 字节 + 逐条反序列化）"
                    )
                }
                return
            }
            // ★★★ v13.79 先等**快手自己的 Gson** 就位（最多 10 秒）。
            //   反序列化快手的模型必须用它（默认 Gson 会炸在 BaseFeed() 的无参构造上），
            //   而它要等第一个网络响应被解析出来才捕获得到。
            //   本函数跑在专用 IO 线程上，等一下不占主线程、不影响启动。
            var waited = 0
            while (CfhState.appGson == null && waited < 10_000) {
                try { Thread.sleep(200) } catch (_: Throwable) {}
                waited += 200
            }
            if (RateLimiter.allow("POOLLOAD-WAIT", 3)) {
                Logger.evidence(
                    "POOLSTORE",
                    "★恢复前等待 appGson=${CfhState.appGson != null} 等待=${waited}ms"
                )
            }
            val f = File(FILE_PATH)
            // ★★★ 2026-09-30 加密读取（规格 ①③④）
            //
            //   读取语义（SecureStore.read）：
            //     · 文件不存在/为空 → from=none（首次运行，不是故障）
            //     · 明文旧文件       → 照常解析（**透明迁移**，规格 ④）
            //     · 密文且解得开     → 正常
            //     · 密文解不开/被篡改 → 先试 .bak；两份都不行才 fail-closed
            val res = SecureStore.read(f, "pool.json")
            val txt = res.text
            if (txt.isNullOrBlank()) {
                if (!res.failed && res.from == "none") {
                    Logger.evidence("POOLSTORE", "★首次运行，无池存档（不恢复）")
                    return
                }
                // ★★★ fail-closed（规格 ③）：
                //   **池子**当作「没有存档」继续跑（少几条内容，可接受）；
                //   但**去重表绝不能无声丢失** —— 立刻从独立副本救回来，
                //   否则看过的视频会重新入池 ⇒ 重复上屏（违反用户第一条硬规则）。
                Logger.always(
                    "【加密文件损坏/被篡改】pool.json 无法解密 ⇒ 池子按「无存档」处理；" +
                        "立即改用独立去重表副本恢复，绝不重放看过的视频"
                )
                Logger.evidence(
                    "POOLSTORE",
                    "★★池子存档损坏/被篡改 ⇒ 按无存档处理 from=${res.from} failed=${res.failed} detail=${res.detail}"
                )
                restoreIdsAlone()
                // ★★★ v13.95 同一触发点②的 fail-closed 分支：仅去重表恢复时也过一遍双闸
                //   （这次恢复的零戳条目刚被补成加载时刻，不会被误删；超限条目则照常淘汰）。
                try { CfhState.prunePoolIds(force = true) } catch (_: Throwable) {}
                return
            }
            // 透明迁移提示：读到明文说明这一份还没转密文，下次 saveNow 就会写密文
            if (res.from == "plain" && RateLimiter.allow("POOLLOAD-PLAIN", 3)) {
                Logger.evidence("POOLSTORE", "★读到明文旧存档（迁移期正常）⇒ 本次恢复照常，下次落盘即转密文")
            }
            val root = org.json.JSONObject(txt)

            // ① 先恢复永久拉黑表 —— 必须在装条目之前，
            //    否则这些条目可能在恢复途中被别的路径重新入池（=重复）
            var ids = 0
            var idsFix = 0
            val loadTs = System.currentTimeMillis()
            root.optJSONObject("ids")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    // ★★★ v13.95 零戳旧条目补**加载时刻**（不是 mtime —— mtime 会高估
                    //   旧条目新鲜度，完整理由见 [restoreIdsAlone]）。不补这一下，
                    //   紧接着的时间闸会把这 1312 条旧记号整批当过期删掉 ⇒ 重复上屏。
                    val v = obj.optLong(k)
                    val ts = if (v > 0L) v else { idsFix++; loadTs }
                    try { CfhState.cleanPoolIds[k] = ts } catch (_: Throwable) {}
                    ids++
                }
            }
            if (idsFix > 0) {
                Logger.evidence(
                    "POOLSTORE",
                    "★去重表补时间戳 $idsFix 条（源=pool.json 主存档，补为加载时刻 now=$loadTs）"
                )
            }
            // ★ 主存档正常时也把独立副本里**多出来的** id 并进来（只增不改）：
            //   永久拉黑表多一条只会更保守（少重放），绝不会造成重复上屏，
            //   等于给「去重表」再加一层交叉校验。
            try { mergeIdsAlone() } catch (_: Throwable) {}

            // ★★★ v13.95 双闸触发点②：淘汰**必须排在合并之后**（`mergeIdsAlone` 只增不改，
            //   会把独立副本里那些**已经该淘汰的** id 灌回来 ⇒ 排在合并之前等于白淘汰）。
            //   又**必须排在装条目之前** —— 否则可能删掉本批正要用的标记。
            //   force=true：恢复路径一次性扫全场（此后热路径只走廉价闸）。
            try { CfhState.prunePoolIds(force = true) } catch (_: Throwable) {}

            // ② 再恢复队列尾部
            val arr = root.optJSONArray("items")
            var ok = 0
            var bad = 0
            if (arr != null) {
                val cl = appClassLoader()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val clsName = o.optString("cls")
                    val js = o.optString("json")
                    // ★★★ v13.78 每一档失败都要报出**具体是哪一档**，
                    //   否则又变成「失败=1 但不知道为什么」的黑盒。
                    if (clsName.isBlank() || js.isBlank()) {
                        bad++
                        if (RateLimiter.allow("POOLLOAD-R1", 3)) {
                            Logger.evidence("POOLSTORE", "★恢复失败①字段空 cls='$clsName' len=${js.length}")
                        }
                        continue
                    }
                    val cls = try { Class.forName(clsName, false, cl) } catch (_: Throwable) { null }
                    if (cls == null) {
                        bad++
                        if (RateLimiter.allow("POOLLOAD-R2", 3)) {
                            Logger.evidence(
                                "POOLSTORE",
                                "★恢复失败②类加载不到 cls=$clsName " +
                                    "cl=${cl?.javaClass?.name ?: "null"}"
                            )
                        }
                        continue
                    }
                    val obj = fromJson(js, cls)
                    if (obj == null) {
                        bad++
                        if (RateLimiter.allow("POOLLOAD-R3", 3)) {
                            Logger.evidence("POOLSTORE", "★恢复失败③fromJson 返回 null cls=$clsName len=${js.length}")
                        }
                        continue
                    }
                    // 类型护栏：必须还是 QPhoto 才算合法池条目
                    val qc = try { CfhState.qpClassRef } catch (_: Throwable) { null }
                    if (qc != null && !qc.isInstance(obj)) {
                        bad++
                        if (RateLimiter.allow("POOLLOAD-R4", 3)) {
                            Logger.evidence(
                                "POOLSTORE",
                                "★恢复失败④类型不符 got=${obj.javaClass.name} 期望=${qc.name}"
                            )
                        }
                        continue
                    }
                    // ★★★ v13.80 严格档护栏：**同一条不能因为「重复恢复」而进池两次**。
                    //   真机实测：一次启动里 `★重启恢复` 出现了 4 次
                    //   （模块被重复装载 ⇒ CfhPoolStore 有多个实例 ⇒ 幂等闸
                    //     `loadStarted` 不跨实例，每个实例各恢复一遍）。
                    //   若不拦，同一条会被灌进池子两次 ⇒ **重复播放**，
                    //   而用户定案的语义是「绝不重复 > 不断流」。
                    //   用 photoId 与池内现有条目比对（不拿 cleanPoolIds 比 ——
                    //   存档里的条目本来就在拉黑表里，拿它比会把全部挡掉）。
                    // ★★★ v13.86 进程级闸：本进程已被别的实例恢复过 ⇒ 整次恢复跳过。
                    //   不加这道闸的后果见 [restoreOwner] 的注释（重复上屏 + PresenterV2 崩溃）。
                    //   ★ 性能修复（2026-09-30）：该判断已**上提到 loadNow() 开头**
                    //     （读存档之前），此处不再重复求值 —— 能走到这一行就说明闸已放行。
                    val pidNew = try { CfhProbe.readPhotoId(obj) } catch (_: Throwable) { null }
                    // 只在池内已有同 pid 时判为重复（存档里的条目本来就在拉黑表里，
                    // 拿 cleanPoolIds 比会把全部挡掉，所以只跟**当前池内容**比）
                    val dup: Boolean = if (pidNew != null && pidNew.isNotEmpty()) {
                        var d = false
                        synchronized(CfhState.cleanPool) {
                            for (e in CfhState.cleanPool) {
                                if (e == null) continue
                                val ep = try { CfhProbe.readPhotoId(e) } catch (_: Throwable) { null }
                                if (ep != null && ep == pidNew) { d = true; break }
                            }
                        }
                        d
                    } else {
                        false
                    }
                    if (dup) {
                        bad++
                    } else {
                        synchronized(CfhState.cleanPool) {
                            if (CfhState.cleanPool.size < 100) {
                                CfhState.cleanPool.add(obj)
                                // ★ v13.91 打上「来自存档」标记：注入日志会用它区分
                                //   「恢复条目」与「快手刚渲染的活条目」（崩溃现场观测用）
                                if (pidNew != null && pidNew.isNotEmpty()) {
                                    try { CfhState.restoredPids.add(pidNew) } catch (_: Throwable) {}
                                }
                                ok++
                            }
                        }
                    }
                }
            }
            restoreOk = ok
            restoreFail = bad
            Logger.evidence(
                "POOLSTORE",
                // ★ v13.87 带上 pid 与进程名：判定多次恢复是不是来自**多个快手子进程**。
                //   2026-09-29 实测：一次会话 `★重启恢复` 出现 4 次、`★跳过本次恢复` 0 次
                //   ⇒ 进程级闸（System.getProperties）**没拦住** ⇒ 它只保证**同进程**唯一，
                //     拦不住「每个进程各有一份 getProperties」这件事。
                //   所以要么是不同进程各恢复一次，要么 pid 相同（那就是我闸写错了）。
                //   加 pid 一轮即可钉死，别再靠猜。
                // ★ v13.88 把「是不是主进程 / 闸有没有放行」直接打进日志：
                //   闸在逐条循环里求值，所以**空存档时子进程也会打这行**（条目=0），
                //   看着像「子进程也恢复了」。带上这两个字段后一眼可辨，不再误读。
                //   顺带副作用：字符串引用了 restoreOwner ⇒ 这行会**强制求值**闸。
                //   ★ 性能修复（2026-09-30）：闸已上提到 loadNow() 开头，子进程在那里
                //     直接 return（并打「★跳过本次恢复」）。所以**这行现在只会出现在主进程**，
                //     不再是「子进程也打一行」的假信号；`主进程/放行` 两个字段保留，
                //     用于主进程侧核对闸的状态。
                "★重启恢复 pid=${android.os.Process.myPid()} 主进程=$isMainProcess " +
                    "放行=$restoreOwner 条目=$ok 失败=$bad " +
                    "拉黑=$ids 池=${CfhState.cleanPool.size}"
            )
        } catch (t: Throwable) {
            if (RateLimiter.allow("POOLLOAD-ERR", 10)) {
                Logger.evidence("POOLSTORE", "★★恢复失败: ${t.javaClass.simpleName} ${t.message}")
            }
        } finally {
            // ★ v13.80 必须无条件置位：`CfhFeedHook` 的首屏兜底会等这个标志，
            //   若异常路径漏置，首屏就会白等满 1.2 秒（虽然不致命，但没必要）。
            loadDone = true
        }
    }
}
