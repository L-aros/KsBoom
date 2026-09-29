package io.github.angbang852.manjiao.adapt

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import java.lang.reflect.Method

/**
 * 混淆类/方法的**运行时结构发现**（自适应 Hook 的第二层）。
 *
 * ## 它解决什么问题
 *
 * 现有代码到处是这种写法：
 * ```
 * val tryNames = listOf("knh.b", "knh\$b")        // 混淆名，每版都变
 * val c = Reflect.findClass(cn, cl) ?: continue   // 找不到就静默跳过
 * ```
 * 混淆名是**编译期产物**，快手每次发版重新混淆，候选数组永远追不上
 * （《快手版本适配文档》2.3 节记录的 50218 版本 `knh.b` 丢失就是实例）。
 *
 * 本类用**稳定的结构特征**代替名字穷举：
 * - 包名（`com.kuaishou.live.rerank`）—— 混淆不跨包重命名，十年稳定
 * - 方法签名形状（参数/返回类型）—— 混淆改不了类型
 * - 方法之间的**共存关系**（同一个类里同时有 X 和 Y）
 *
 * ## 三层回退链（这是关键设计）
 *
 * 每个 [Target] 的解析按以下顺序逐级降级，**任一级命中即止**：
 *
 * ```
 * ① 缓存/已解析   → 零成本（同一进程内重复查询直接返回）
 * ② 版本档位已知  → 用 [KsVersion.tier] 选「该档位下最可能的候选集」
 * ③ 全量扫描      → 按结构特征扫 DexKit / 已加载类，与档位无关（抗混淆兜底）
 * ④ 降级返回 null → 调用方跳过该 hook，**不崩溃、不猜**
 * ```
 *
 * ★ 第 ③ 级是「新增版本无需改代码」的关键：即使 ② 的候选集全废，
 *   只要快手还保留同样的**语义结构**（比如「一个类同时有带 List 参数的
 *   方法、返回 List 的方法、以及名字含 refresh 的方法」），就能找到它。
 *
 * ## 与 DexKit 的关系
 *
 * 项目已依赖 DexKit（`PlaybackHook.discoverPlayers` 在用）。本类的 [byStructure]
 * 优先走 **DexKit**（能扫到未加载的类，且抗混淆最强）；DexKit 不可用时
 * （native 库加载失败 / 插件化 APK 路径异常）回退到**已加载类扫描**——
 * 后者覆盖面小，但零依赖、零 native，一定能跑。
 */
object KsResolve {

    /**
     * 解析目标：把「要 hook 什么」描述成**结构特征**而非名字。
     *
     * @param id       稳定标识（日志/去重用，形如 `rerank`）
     * @param pkg      包名前缀（混淆不跨包，这是最稳的锚点）
     * @param require  必须同时满足的方法特征（AND 关系）
     * @param anyOf    至少满足一个的方法特征（OR 关系；为空表示不约束）
     */
    data class Target(
        val id: String,
        val pkg: String,
        val require: List<Feature> = emptyList(),
        val anyOf: List<Feature> = emptyList(),
        /** 该目标归属的最低版本档位；低于此档位直接不解析（避免在旧版上误匹配） */
        val minTier: KsVersion.Tier = KsVersion.Tier.UNKNOWN,
    )

    /**
     * 方法特征 —— 只描述**类型形状**，不描述名字。
     *
     * `name` 是可选的名字提示：混淆类里偶有方法名逃过混淆（如 `T0`/`E1` 这种
     * 短名本来就是混淆后的结果，但**它在某一版里是被硬编码依赖的**）。
     * 提供 name 时按「名字匹配」计；不提供时按「形状匹配」计。
     */
    data class Feature(
        val name: String? = null,
        val paramCount: Int = -1,
        val paramNames: List<String> = emptyList(),
        val returnName: String? = null,
        val returnsList: Boolean = false,
        val paramsHasList: Boolean = false,
    ) {
        fun matches(m: Method): Boolean {
            if (name != null && m.name != name) return false
            if (paramCount >= 0 && m.parameterTypes.size != paramCount) return false
            if (paramNames.isNotEmpty()) {
                val actual = m.parameterTypes.map { it.simpleName }
                if (actual != paramNames) return false
            }
            if (returnName != null && !m.returnType.simpleName.contains(returnName)) return false
            if (returnsList) {
                val rn = m.returnType.name
                if (!rn.contains("List") && !rn.contains("Collection")) return false
            }
            if (paramsHasList) {
                if (!m.parameterTypes.any { it.name.contains("List") || it.name.contains("Collection") }) return false
            }
            return true
        }
    }

    // ==================== 缓存 ====================

    /**
     * 解析结果缓存。键含 **classloader 身份**（沿用项目既有约定：快手插件化会把
     * 同名类装进第二个 loader，只按名字做键会跨 loader 污染）。
     *
     * 用 [Resolved] 包装而非裸 Class：**负结果也要缓存**，否则解析失败的目标
     * 会在每次 hook() 重入时重新全量扫描（DexKit 扫描是秒级开销）。
     */
    private class Resolved(
        val cls: Class<*>?,
        val via: String,
        val methods: Map<String, Method> = emptyMap(),
    ) {
        /** 多类结果（播放器发现用；单类场景为 null） */
        var playerList: List<Class<*>>? = null
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Resolved>()

    private fun cacheKey(id: String, cl: ClassLoader): String =
        id + "@" + System.identityHashCode(cl)

    /** 是否已解析过（含失败） */
    fun isResolved(id: String, cl: ClassLoader): Boolean = cache.containsKey(cacheKey(id, cl))

    // ==================== 解析主入口 ====================

    /**
     * 解析目标类。全链路 try-catch，**任何异常都归为「解析失败」而非抛出**。
     *
     * @return 命中的 Class，或 null（调用方应跳过该 hook）
     */
    fun resolve(t: Target, cl: ClassLoader): Class<*>? {
        val key = cacheKey(t.id, cl)
        cache[key]?.let { return it.cls }

        // ★ 耗时埋点（2026-09-23）：结构发现可能在**主线程**上枚举全量 dex，
        //   必须能直接看到每个目标花了多久 —— 排「装了模块就卡」这类问题时，
        //   「哪一步慢」比「慢多少」更重要。
        val t0 = System.currentTimeMillis()

        // ★ 总开关关闭 → 只走候选名直查（等价于改动前行为），不做任何结构发现。
        //   这是故障隔离路径：见 KsHookKit.enabled 的说明。
        if (!KsHookKit.enabled) {
            val c = scanCandidates(t, cl)
            cache[key] = Resolved(c, "adapt-off")
            Logger.once("res.${t.id}.off", "RESOLVE ${t.id}: adapt layer disabled → candidate-only ${c?.name ?: "MISS"}")
            return c
        }

        // ★★ 持久化缓存（2026-09-23）：结构发现要 1 秒以上，而结果几乎不变。
        //   缓存键含 classloader 身份，签名含「快手版本+模块版本」——
        //   只有版本变化才失效。见 KsCache 的说明。
        //   ★ 关键：这一步在**任何昂贵操作之前**，命中即零成本返回。
        val sig = KsCache.signatureOf()
        KsCache.get(key, sig)?.let { cached ->
            // 空串 = 上次已确认「不存在」，同样有效（省下一次完整重扫）
            val c = if (cached.isEmpty()) null else Reflect.findClass(cached, cl)
            // 缓存的类名在当前 loader 里加载不出来（如缓存来自另一个 loader）
            // → 视为失效，继续走完整解析，不当成命中
            if (cached.isEmpty() || c != null) {
                cache[key] = Resolved(c, "persist-cache")
                Logger.once("res.${t.id}.pcache", "RESOLVE ${t.id}: persistent cache hit → ${cached.ifEmpty { "MISS(负缓存)" }}")
                return c
            }
        }

        // 档位不足：明确跳过（旧版上不该出现的目标，强行匹配只会误钩）
        if (t.minTier != KsVersion.Tier.UNKNOWN && !KsVersion.atLeast(t.minTier)) {
            Logger.once("res.${t.id}.tier", "RESOLVE ${t.id}: skipped (tier ${KsVersion.tier.label} < ${t.minTier.label})")
            cache[key] = Resolved(null, "tier-skip")
            return null
        }

        // ---- ② 档位候选集（廉价：几次 Class.forName）----
        var via = "tier-candidates"
        var hit = scanCandidates(t, cl)

        // ---- ③ 结构发现（昂贵：DexKit 全 APK 解析 / 全量 dex 枚举）----
        //
        // ★★ 绝不在主线程上做（2026-09-23 卡顿修复，用户实测反馈）。
        //
        //   实测证据（14.8.20.50218）：`RESOLVE knhb: FAILED ... took=1348ms`
        //   —— 装钩期主线程被堵 1.3 秒，用户感知为「打开快手就卡住」。
        //   对照实验（改动前版本）无此阻塞，确认是本层引入。
        //
        //   策略：把结构发现丢到**后台线程**，主线程**有界短等**（默认 300ms）。
        //   - 缓存命中时根本不会走到这里（上一段已返回）
        //   - 首次冷启动若后台没在 300ms 内完成，主线程先返回 null（该 hook 暂不装），
        //     后台完成后**补装**（见 installAfterDiscovery）
        //   - 之后所有冷启动都走持久缓存，零成本
        if (hit == null) {
            via = "structural"
            hit = structuralWithBackgroundFallback(t, cl, key, sig)
        }

        if (hit == null) {
            // ---- ④ 降级 ----
            val took = System.currentTimeMillis() - t0
            // ★ 负结果也写缓存：下次冷启动不必再花 1 秒去证明「它还是不存在」
            KsCache.put(key, sig, null)
            Logger.once("res.${t.id}.miss", "RESOLVE ${t.id}: FAILED (pkg=${t.pkg} tier=${KsVersion.tier.label}) took=${took}ms — hook skipped")
            // ★ 失败时把「该包里实际有什么」打出来（**不受 diag 门控**）。
            //
            // 为什么不做门控：这条只在**解析失败**时输出，一个进程内每目标至多一次
            // （外层有 once 去重），开销可忽略；而它的排障价值极高——直接回答
            // 「是不是改名了、改成了什么」，不必再靠猜或让用户去开开关。
            // 沿用项目既有约定：失败证据用 once 级，不受 quiet 影响。
            try {
                val names = listPackageClasses(t.pkg, cl, 60)
                Logger.once(
                    "res.${t.id}.pkgls",
                    "RESOLVE ${t.id}: pkg=${t.pkg} 实有 ${names.size} 类: " +
                        if (names.isEmpty()) "(空——该类不在 APK 主 dex，可能在插件化独立 dex)"
                        else names.joinToString(", ") { it.substringAfterLast('.') }
                )
                // ★ 对每个实有类，dump 其方法形状 —— 直接回答「哪个才是我们要找的」
                //   （2026-09 实测：50218 上 knh. 包只剩 knh.a，需确认它是不是数据源）
                for (cn in names.take(6)) {
                    val c = Reflect.findClass(cn, cl) ?: continue
                    val shape = c.declaredMethods.take(16).joinToString(", ") { m ->
                        "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
                    }
                    Logger.once("res.${t.id}.shape.$cn", "RESOLVE ${t.id}: $cn 方法: $shape")
                }
            } catch (_: Throwable) {}
            cache[key] = Resolved(null, "miss")
            // ★ 同步进装配报告：调用方直接走 KsResolve 时也要让失败可见
            //   （2026-09 实测踩到：迁移站点绕过 KsHookKit 直调 KsResolve，
            //    导致 report() 因 outcomes 为空而整段不输出）
            KsHookKit.noteResolve(t.id, ok = false, detail = "unresolved in pkg=${t.pkg}")
            return null
        }

        Logger.once("res.${t.id}.ok", "RESOLVE ${t.id}: ${hit.name} via=$via tier=${KsVersion.tier.label} took=${System.currentTimeMillis() - t0}ms")
        cache[key] = Resolved(hit, via)
        // ★ 命中结果写持久缓存：下次冷启动零成本拿到
        KsCache.put(key, sig, hit.name)
        KsHookKit.noteResolve(t.id, ok = true, detail = "${hit.name} via=$via")
        return hit
    }

    // ==================== 后台结构发现 ====================

    /** 主线程等待结构发现的上限（毫秒）。超过则先返回 null，稍后补装 */
    @Volatile
    var mainThreadWaitMs: Long = 300

    /**
     * 后台补装回调表：目标 id → 「拿到类之后要做的事」。
     *
     * ★ 为什么需要它：结构发现改到后台后，主线程可能在它完成前就返回了 null。
     *   若就此放弃，hint 的目标就**永远不会被装上**（只是从「装错」变成「不装」）。
     *   回调表让后台线程在发现成功后**主动把 hook 补上** ——
     *   这与项目既有的 `PlaybackHook.discoverPlayers` 是同一套模式
     *   （那里也是 DexKit 在 daemon 线程上跑、找到后现场装钩）。
     */
    private val deferredInstall =
        java.util.concurrent.ConcurrentHashMap<String, (Class<*>) -> Unit>()

    /**
     * 注册「结构发现成功后如何装钩」。
     *
     * 调用方（hook 站点）在调用 [resolve] 前注册；若 [resolve] 同步命中则回调不被使用，
     * 若走后台路径则在发现成功后由后台线程调用。
     */
    fun onDiscovered(id: String, install: (Class<*>) -> Unit) {
        deferredInstall[id] = install
    }

    /**
     * 结构发现（后台化 + 有界等待）。
     *
     * 执行顺序：
     * 1. 后台线程跑 [byStructure]（昂贵）
     * 2. 主线程最多等 [mainThreadWaitMs]；等到了就用（常见：dex 已被系统缓存）
     * 3. 没等到 → 主线程先返回 null，后台完成后写缓存 + 触发 [deferredInstall]
     */
    private fun structuralWithBackgroundFallback(
        t: Target, cl: ClassLoader, key: String, sig: String,
    ): Class<*>? {
        val result = java.util.concurrent.atomic.AtomicReference<Class<*>?>(null)
        val done = java.util.concurrent.CountDownLatch(1)

        Thread {
            try {
                val c = byStructure(t, cl)
                result.set(c)
                // ★ 结果写持久缓存：无论成功失败都写 ——
                //   失败也写（负缓存）才能让**下次冷启动**不再重复这 1 秒的开销。
                //   这正是用户要求的「首次解析明白，后续固定不重复」。
                //   （落盘闸门由 KsCache 内部把关：版本未知时不写，避免白写）
                KsCache.put(key, sig, c?.name)
                if (c != null) {
                    Logger.always("RESOLVE ${t.id}: 后台结构发现命中 ${c.name}（已补装并写入缓存）")
                    // 主线程若已超时返回，这里补装
                    if (cache[key]?.cls == null) {
                        cache[key] = Resolved(c, "structural-bg")
                        try { deferredInstall[t.id]?.invoke(c) } catch (e: Throwable) {
                            Logger.d("deferred install fail ${t.id}: ${e.message}")
                        }
                    }
                } else {
                    Logger.always("RESOLVE ${t.id}: 后台结构发现未命中（已写负缓存，下次冷启动零开销）")
                    if (cache[key] == null) cache[key] = Resolved(null, "structural-bg-miss")
                }
            } catch (e: Throwable) {
                Logger.d("structural bg fail ${t.id}: ${e.message}")
            } finally {
                done.countDown()
            }
        }.apply { name = "MJ-AdaptResolve-${t.id}"; isDaemon = true }.start()

        // 主线程有界等待：等到了就同步返回（hook 正常装载，无延迟）
        return try {
            if (done.await(mainThreadWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                val c = result.get()
                Logger.probe { "RESOLVE ${t.id}: structural done within ${mainThreadWaitMs}ms" }
                c
            } else {
                Logger.once(
                    "res.${t.id}.bg",
                    "RESOLVE ${t.id}: 结构发现超过 ${mainThreadWaitMs}ms，转后台（该 hook 稍后补装，主线程不受阻）"
                )
                null
            }
        } catch (_: Throwable) { null }
    }

    /**
     * 在目标类中找**指定语义的方法**（名字未知时按特征找）。
     *
     * 这是给 hook 站点用的：拿到类之后还要挑方法，而方法名同样是混淆的。
     * 按 [features] 的顺序依次尝试，返回第一个命中 —— 因此调用方应把
     * 「最精确的特征」放前面。
     */
    fun findMethod(cls: Class<*>, vararg features: Feature): Method? {
        val all = collectMethods(cls)
        for (f in features) {
            all.firstOrNull { f.matches(it) }?.let { return it }
        }
        return null
    }

    /** 按特征筛选（返回全部命中，供「同类多方法都 hook」的场景） */
    fun findMethods(cls: Class<*>, vararg features: Feature): List<Method> {
        val all = collectMethods(cls)
        return all.filter { m -> features.any { it.matches(m) } }
    }

    // ==================== 内部：候选集扫描 ====================

    /**
     * 档位候选集：列出该档位下**结构上可能**的类名（含历史混淆名），逐个试。
     *
     * 这些名字来自《快手版本适配文档》的实测记录与项目注释。它们是「提示」而非
     * 「唯一解」——找不到就落 [byStructure]。
     */
    private fun candidateNames(t: Target): List<String> = when (t.id) {
        // knhb.T0：快手插件化的列表数据源实例。
        // ★ 只保留**历史上真实存在过**的名字（原项目的 knh.b / knh$b）。
        //   不预填 `knh.a` / `knh.c` —— 实测证明 50218 上 `knh.a` 是视图描述类
        //   （无 List 参数方法），加进来只会让它参与匹配、浪费一次 satisfies 扫描。
        //   若某版本真的改名为 knh.a，结构发现会命中它（那才是正确的判据）。
        "knhb" -> listOf("knh.b", "knh\$b")
        else -> emptyList()
    }

    private fun scanCandidates(t: Target, cl: ClassLoader): Class<*>? {
        for (cn in candidateNames(t)) {
            val c = Reflect.findClass(cn, cl) ?: continue
            if (satisfies(t, c)) return c
        }
        return null
    }

    /** 结构匹配：require 全中 && (anyOf 为空 || anyOf 至少一中) */
    private fun satisfies(t: Target, c: Class<*>): Boolean {
        val ms = collectMethods(c)
        if (t.require.isNotEmpty() && !t.require.all { f -> ms.any { f.matches(it) } }) return false
        if (t.anyOf.isNotEmpty() && t.anyOf.none { f -> ms.any { f.matches(it) } }) return false
        // 完全无约束的目标不接受「随便一个类」——否则会命中无关类
        return t.require.isNotEmpty() || t.anyOf.isNotEmpty()
    }

    /**
     * 结构发现：与档位和名字都无关，只在**包名前缀**下寻找满足特征形状的类。
     *
     * 两条通道：
     * 1. DexKit：扫 APK 全量类（含未加载），抗混淆最强 —— 复用 PlaybackHook 的用法
     * 2. 已加载类扫描：DexKit 不可用时的回退（零 native 依赖）
     */
    /**
     * DexKit native 库是否可用（**惰性预热**）。
     *
     * ★ 为什么需要预热（2026-09 真机实测）：
     *   `System.loadLibrary("dexkit")` 首次调用有可观开销（加载 .so + 初始化 bridge）。
     *   实测时序（14.8.20.50218）：主线程 `RESOLVE knhb` 在 `20:18:01.292` 同步执行，
     *   而 DexKit 首次就绪在 `20:18:02.263` —— **晚约 1 秒**。
     *   于是主线程上那次结构发现撞上「.so 还没加载完」的窗口，DexKit 通道失败，
     *   退回覆盖更窄的 dexElements 通道，最终 MISS。
     *
     *   预热把这次开销提前到后台线程，主线程上的解析不再等待 native 加载。
     *
     * 用 [dexKitState] 三态而非 Boolean：区分「未试」「可用」「不可用」，
     * 避免 native 加载失败后每次解析都重试（每次失败都要付一次异常+加载尝试的代价）。
     */
    private val dexKitState = java.util.concurrent.atomic.AtomicInteger(0) // 0=未试 1=可用 -1=不可用

    /**
     * 在后台线程预热 DexKit（**不阻塞调用方，且失败绝不影响主流程**）。
     *
     * ★ 为什么需要预热（2026-09 真机实测）：
     *   `System.loadLibrary("dexkit")` 首次调用有可观开销（加载 .so + 初始化 bridge）。
     *   实测时序（14.8.20.50218）：主线程 `RESOLVE knhb` 在 `.292` 执行，
     *   而 DexKit 首次就绪在下一秒的 `.263` —— 正好错过。
     *
     * ★★ 实测教训（2026-09-23，同版本）——**预热绝不能让调用方等待**：
     *   第一版实现让主线程在 `byStructure` 里轮询等待预热完成（上限 3s）。
     *   实测结果：主线程 CPU 冲到 **65.5%** 且停止输出日志（应用卡死），
     *   原因是 native 库在隔离命名空间（`using isolated ns clns-N`）加载时
     *   可能长时间不返回，轮询循环把主线程拖垮。
     *
     *   现改为**纯加速、不等待**：
     *   - 预热线程只负责让 .so 尽早进内存（对**后续**解析有加速作用）
     *   - 解析侧**不再轮询等待**：预热没完成就直接走 dexElements 通道
     *   - 状态机保证不会重复加载，也不会因为预热失败而卡住任何人
     *
     *   语义从「等它好了再用」降级为「它好了就更快，没好就换路」——
     *   这才符合「自适应层绝不能成为故障源」这条底线。
     */
    fun warmUpDexKit() {
        if (!dexKitState.compareAndSet(0, 2)) return   // 2=预热中（避免并发重复加载）
        Thread {
            try {
                System.loadLibrary("dexkit")
                dexKitState.set(1)
                Logger.probe { "RESOLVE dexkit: warmUp ok" }
            } catch (e: Throwable) {
                dexKitState.set(-1)
                Logger.once("res.dexkit.fail", "RESOLVE dexkit: loadLibrary failed (${e.javaClass.simpleName}) — 回落 dexElements")
            }
        }.apply { name = "MJ-DexKitWarm"; isDaemon = true }.start()
    }

    /** DexKit 是否**已确认可用**（预热完成前一律视为不可用，不去碰它） */
    private fun dexKitReady(): Boolean = dexKitState.get() == 1

    private fun byStructure(t: Target, cl: ClassLoader): Class<*>? {
        // 通道 1：DexKit —— 扫 APK 全量类（含**尚未加载**的类），抗混淆最强。
        // 用 searchPackages 限定包前缀 + methods{} 描述方法形状，
        // 让 DexKit 在 native 侧做匹配，不必把成千上万个类拉到 Java 侧再筛。
        //
        // ★ 只在**预热已确认完成**时才走这条通道（2026-09-23 卡死修复）：
        //   绝不在主线程上 loadLibrary 或等待——见 warmUpDexKit 的实测教训。
        //   预热未完成 → 直接跳通道 2（dexElements），功能不受影响，只是覆盖面小些。
        if (!dexKitReady()) {
            Logger.probe { "RESOLVE ${t.id}: dexkit not ready(state=${dexKitState.get()}), use dexElements" }
            return byDexElements(t, cl)
        }
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as android.app.Application
            val apk = app.applicationInfo.sourceDir
            org.luckypray.dexkit.DexKitBridge.create(apk).use { bridge ->
                val found = bridge.findClass {
                    // ★ searchPackages 是 FindClass 上的方法（**不是** matcher 内的）
                    searchPackages(t.pkg)
                    matcher {
                        // ★★ DexKit 侧只做**形状**粗筛，不做名字精确匹配（2026-09 实测修正）。
                        //
                        // 原因：混淆类里的方法名（`E1`/`T0`）本身就是编译期产物，
                        // 换个版本就可能全变。若把 `name = "E1"` 塞进 DexKit 查询条件，
                        // 那么「名字变了但结构没变」的情况会被 DexKit 直接筛掉，
                        // 反射侧的形状复核根本没机会参与 —— 抗混淆退化成按名字查，
                        // 正是本方案要消灭的东西。
                        //
                        // 实测证据（快手 14.8.20.50218）：`RESOLVE knhb: FAILED`
                        // 而同一版本 `knh.b` 确实不在（适配文档 2.3 节），
                        // 但结构发现也没能兜住 —— 因为查询条件里锁死了 `E1` 这个名字。
                        //
                        // 现改为：用**参数形状**（个数）粗筛出一批候选，交给反射侧
                        // `satisfies()` 用完整特征（含类型）精筛。
                        methods {
                            matchType = org.luckypray.dexkit.query.enums.MatchType.Contains
                            for (f in (t.require + t.anyOf)) {
                                // 只保留形状类约束（参数个数 / 返回类型），丢掉名字
                                if (f.paramCount >= 0 || f.returnName != null) {
                                    add(Feature(paramCount = f.paramCount, returnName = f.returnName).let { toMethodMatcher(it) })
                                }
                            }
                        }
                    }
                }
                Logger.probe { "RESOLVE ${t.id}: dexkit structural cands=${found.size}" }
                for (cd in found) {
                    val cn = cd.name
                    // 排除内部类：`$` 后缀多为回调/Builder，不是数据源本体
                    if (cn.contains('$')) continue
                    val c = try { cd.getInstance(cl) } catch (_: Throwable) { continue }
                    // ★ DexKit 侧已筛过一遍，这里再用反射**复核** —— DexKit 匹配的是
                    //   dex 里的静态描述，而我们要 hook 的是运行时真实对象；
                    //   二者不一致时（如插件化把类装进了另一个 loader）以运行时为准。
                    if (satisfies(t, c)) {
                        Logger.always("RESOLVE ${t.id}: DexKit structural hit $cn")
                        return c
                    }
                }
            }
        } catch (e: Throwable) {
            Logger.probe { "RESOLVE ${t.id}: dexkit unavailable (${e.javaClass.simpleName}: ${e.message})" }
        }

        // 通道 2：已加载类扫描
        return byDexElements(t, cl)
    }

    /**
     * 枚举某包下**实际存在、且可被当前 classloader 加载**的类名。
     *
     * 用途：当某个目标解析失败时，把「该包里到底有什么」打出来 ——
     * 排障时不必再靠猜（例如 14.8.20.50218 上 `knh.b` 消失，那它变成了什么？）。
     *
     * 只在 [Logger.diag] 开启时由调用方触发，避免无谓开销。
     */
    fun listPackageClasses(pkg: String, cl: ClassLoader, limit: Int = 40): List<String> {
        val out = ArrayList<String>()
        try {
            val dexCl = Class.forName("dalvik.system.BaseDexClassLoader", false, cl)
            val pathList = dexCl.getDeclaredField("pathList").apply { isAccessible = true }.get(cl) ?: return out
            val elements = pathList.javaClass.getDeclaredField("dexElements")
                .apply { isAccessible = true }.get(pathList) as? Array<*> ?: return out
            for (e in elements) {
                val elem = e ?: continue
                val dexFile = try {
                    elem.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }.get(elem)
                } catch (_: Throwable) { continue } ?: continue
                val entries = try {
                    @Suppress("UNCHECKED_CAST")
                    dexFile.javaClass.getMethod("entries").invoke(dexFile) as? java.util.Enumeration<String>
                } catch (_: Throwable) { null } ?: continue
                while (entries.hasMoreElements() && out.size < limit) {
                    val cn = entries.nextElement() ?: continue
                    if (cn.startsWith(pkg)) out.add(cn)
                }
                if (out.size >= limit) break
            }
        } catch (_: Throwable) {}
        return out
    }

    /**
     * 已加载类扫描（反射 DexClassLoader 的 dexElements 枚举——与
     * VideoDownloaderHook.hookRepresentations 同法，项目内已有先例）。
     *
     * 覆盖面小于 DexKit，但零 native 依赖，DexKit 加载失败时仍能工作。
     */
    private fun byDexElements(t: Target, cl: ClassLoader): Class<*>? {
        try {
            val dexCl = Class.forName("dalvik.system.BaseDexClassLoader", false, cl)
            val pathList = dexCl.getDeclaredField("pathList").apply { isAccessible = true }.get(cl) ?: return null
            val elements = pathList.javaClass.getDeclaredField("dexElements")
                .apply { isAccessible = true }.get(pathList) as? Array<*> ?: return null
            for (e in elements) {
                val elem = e ?: continue
                val dexFile = try {
                    elem.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }.get(elem)
                } catch (_: Throwable) { continue } ?: continue
                val entries = try {
                    @Suppress("UNCHECKED_CAST")
                    dexFile.javaClass.getMethod("entries").invoke(dexFile) as? java.util.Enumeration<String>
                } catch (_: Throwable) { null } ?: continue
                while (entries.hasMoreElements()) {
                    val cn = entries.nextElement() ?: continue
                    if (!cn.startsWith(t.pkg) || cn.contains('$')) continue
                    val c = Reflect.findClass(cn, cl) ?: continue
                    if (satisfies(t, c)) {
                        Logger.always("RESOLVE ${t.id}: dexElements structural hit $cn")
                        return c
                    }
                }
            }
        } catch (e: Throwable) {
            Logger.probe { "RESOLVE ${t.id}: dexElements scan failed (${e.javaClass.simpleName})" }
        }
        return null
    }

    /** 把本类的 [Feature] 翻译成 DexKit 的 MethodMatcher（只映射 DexKit 支持的那几项） */
    private fun toMethodMatcher(f: Feature): org.luckypray.dexkit.query.matchers.MethodMatcher =
        org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
            f.name?.let { name = it }
            if (f.paramCount >= 0) paramCount = f.paramCount
            f.returnName?.let { returnType = it }
        }

    /**
     * 是否**可实例化的具体类**（非 abstract、非 interface）。
     *
     * 用于排除抽象基类：hook 它上面的方法大概率不会被执行（跑的是子类实现），
     * 白占名额。见 [resolvePlayerClasses] 的实测注释。
     */
    private fun isConcrete(c: Class<*>): Boolean = try {
        !java.lang.reflect.Modifier.isAbstract(c.modifiers) && !c.isInterface
    } catch (_: Throwable) { false }

    // ==================== 内部：方法收集 ====================

    /**
     * 收集类及其父类的方法（最多上溯 3 层，与项目既有 hook 遍历深度一致）。
     *
     * ★ 不缓存 Method 数组：混淆类的 declaredMethods 在**不同版本**上形状完全不同，
     *   而本函数只在解析期调用（每目标进程内一次），不是热路径。
     */
    private fun collectMethods(c: Class<*>): List<Method> {
        val out = ArrayList<Method>(32)
        var cur: Class<*>? = c
        var depth = 0
        while (cur != null && cur != Any::class.java && depth < 3) {
            try { out.addAll(cur.declaredMethods) } catch (_: Throwable) {}
            cur = cur.superclass; depth++
        }
        return out
    }

    // ==================== 预定义目标（新增版本时改这里） ====================

    /**
     * 快手插件化的**列表数据源**（历史混淆名 `knh.b`）。
     *
     * 结构特征来自 CfhFeedHook.hookKnhbT0 的既有依赖：
     * - `T0(List, ..., String reason)` —— 6 参、含 List 参数、last 参数是 String
     * - `E1(List)` —— 1 参、含 List 参数
     * 这两个方法名在项目里被**硬编码依赖**（`nm == "T0"` / `nm == "E1"`），
     * 说明它们在该类里是稳定锚点。但类名不稳定 → 用「两个方法共存」定位类。
     *
     * ★★ 实测结论（2026-09，14.8.20.50218）——**此目标在该版本不存在，且不是改名**：
     *
     * ```
     * RESOLVE knhb: pkg=knh. 实有 1 类: a
     * RESOLVE knhb: knh.a 方法: C(), N2(), Y(), d(), getDisplayType(),
     *               getMediaSceneVideoSceneType(), getMeta(), t0(), w4(), z4()
     * ```
     *
     * `knh.` 包里只剩 `knh.a` 一个类，而它**没有任何带 List 参数的方法**，
     * 全是 `getXxx` 语义 → 是视图/场景描述类，不是数据源。
     *
     * 因此《快手版本适配文档》2.3 节「建议扩展为 knh.a / knh.c」的方向**是错的**：
     * 它假设「改名」，实际是**该类被删除或整体重构**。往候选数组里加名字
     * 解决不了这个问题 —— 这也正是本方案改用结构发现的原因（至少能给出
     * 「包里到底有什么」的确定答案，而不是继续猜）。
     *
     * 该版本上 T0/E1 拦截**无目标可挂**，属可接受的降级（其余 hook 不受影响）。
     */
    val KN_HB = Target(
        id = "knhb",
        pkg = "knh.",
        require = listOf(
            Feature(name = "E1", paramCount = 1, paramsHasList = true),
        ),
        anyOf = listOf(
            Feature(name = "T0", paramCount = 6, paramsHasList = true),
            // 名字逃逸时的形状兜底：6 参含 List + 末参 String
            Feature(paramCount = 6, paramsHasList = true, paramNames = listOf("List", "Object", "List", "Object", "Object", "String")),
            Feature(paramCount = 6, paramsHasList = true),
        ),
    )

    /**
     * 直播 rerank 注入点（`com.kuaishou.live.rerank` 包）。
     *
     * 包名稳定（混淆不跨包），故只需在包内找「吃 LiveStreamFeed 的方法」。
     */
    val RERANK_INJECT = Target(
        id = "rerank.inject",
        pkg = "com.kuaishou.live.rerank",
        anyOf = listOf(
            Feature(name = "G", paramCount = 2),
            Feature(name = "m", paramCount = 1),
            Feature(name = "doInject", paramCount = 0),
        ),
    )

    // ==================== 混淆包内的「按名字取类」自适应 ====================

    /**
     * 在混淆包内按**已知名 → 结构**的顺序取类。
     *
     * 这是给 `CfhViewHook.hookLiveRerank` 这类站点的统一入口：它们既有的写法是
     * `Class.forName("com.kuaishou.live.rerank.d", ...)` 直接硬编码短名。
     * 短名在混淆包内是**编译期分配**的，每版都可能变。
     *
     * 回退顺序：
     * 1. [knownNames]（既有硬编码名）—— **保证不改前行为**，旧版本上完全一致
     * 2. 结构发现（包内扫，按方法特征匹配）
     * 3. null（调用方跳过）
     *
     * @param pkg        包名（稳定锚点）
     * @param knownNames 既有硬编码短名列表（保持向后兼容的关键）
     * @param features   结构特征（任一命中即算匹配）
     */
    fun resolveInPackage(
        tag: String,
        cl: ClassLoader,
        pkg: String,
        knownNames: List<String>,
        features: List<Feature>,
    ): Class<*>? {
        val key = "pkg:$tag@${System.identityHashCode(cl)}"
        cache[key]?.let { return it.cls }

        /** ① 既有硬编码名 —— 关闭开关时**只走这一层**，等价改动前行为 */
        fun knownOnly(): Class<*>? {
            for (n in knownNames) {
                val cn = if (n.contains('.')) n else "$pkg.$n"
                Reflect.findClass(cn, cl)?.let { return it }
            }
            return null
        }

        if (!KsHookKit.enabled) {
            val c = knownOnly()
            cache[key] = Resolved(c, "adapt-off")
            Logger.once("res.$tag.off", "RESOLVE $tag: adapt layer disabled → known-name-only ${c?.name ?: "MISS"}")
            return c
        }

        // ---- ① 既有硬编码名（行为与改前完全一致）----
        for (n in knownNames) {
            val cn = if (n.contains('.')) n else "$pkg.$n"
            val c = Reflect.findClass(cn, cl) ?: continue
            cache[key] = Resolved(c, "known-name")
            Logger.probe { "RESOLVE $tag: known name hit $cn" }
            KsHookKit.noteResolve(tag, ok = true, detail = "$cn via=known-name")
            return c
        }

        // ---- ② 结构发现 ----
        val t = Target(id = tag, pkg = pkg, anyOf = features)
        byStructure(t, cl)?.let {
            cache[key] = Resolved(it, "structural")
            Logger.always("RESOLVE $tag: structural hit ${it.name} (known names all missing)")
            KsHookKit.noteResolve(tag, ok = true, detail = "${it.name} via=structural")
            return it
        }

        // ---- ③ 降级 ----
        cache[key] = Resolved(null, "miss")
        Logger.once("res.$tag.miss", "RESOLVE $tag: FAILED in $pkg (known=${knownNames.size} names + structural) — feature degraded")
        KsHookKit.noteResolve(tag, ok = false, detail = "unresolved in $pkg")
        return null
    }

    // ==================== 播放器类发现 ====================

    /**
     * 播放器类（下载捕获用）：**已知命名空间内的结构发现 + 候选名回退**。
     *
     * 背景：《快手版本适配文档》2.2 节实测 `com.kwai.video.player.KwaiMediaPlayerImpl*`
     * 与 `com.kuaishou.player.KwaiPlayer` 全部 MISS，只剩 `com.kwai.player.KwaiPlayer`。
     * 硬编码候选不可持续。
     *
     * 策略：
     * 1. 已知候选名（顺序保持原样 → **旧版本行为零变化**）
     * 2. 结构发现：在 `com.kwai.player` / `com.kwai.video.player` 两个**稳定命名空间**内，
     *    找「有 setDataSource(String 或 Uri) 方法」的类
     *
     * 返回去重后的候选列表（可能为空 = 该 hook 整体降级跳过）。
     */
    fun resolvePlayerClasses(cl: ClassLoader): List<Class<*>> {
        val key = "players@${System.identityHashCode(cl)}"
        cache[key]?.let { return it.playerList ?: emptyList() }

        val out = LinkedHashSet<Class<*>>()

        // ① 已知候选名（**廉价**：三次 Class.forName。原顺序，保证不改变既有行为）
        for (cn in KNOWN_PLAYER_CLASSES) {
            Reflect.findClass(cn, cl)?.let { out.add(it) }
        }
        val knownHit = out.size

        // 结构发现结果并入（可能来自持久缓存，也可能是本次后台发现的）
        for (cn in playerStructuralNames(cl)) {
            Reflect.findClass(cn, cl)?.let { out.add(it) }
        }

        val list = out.toList()
        cache[key] = Resolved(null, "players").apply { playerList = list }
        Logger.once(
            "res.players",
            "RESOLVE players: ${list.size} class(es) [known=$knownHit] " + list.joinToString(",") { it.name }
        )
        KsHookKit.noteResolve(
            "players",
            ok = list.isNotEmpty(),
            detail = "${list.size} class(es) [known=$knownHit]"
        )
        return list
    }

    /**
     * 播放器类的**结构发现**（分离出来以便后台化 + 持久缓存）。
     *
     * 与 [resolve] 同样的策略：主线程只等 [mainThreadWaitMs]，超时转后台，
     * 结果（含**负结果**）写入持久缓存 —— 下次冷启动零开销。
     */
    private fun playerStructuralNames(cl: ClassLoader): List<String> {
        val ckey = "players.struct@${System.identityHashCode(cl)}"
        val sig = KsCache.signatureOf()

        // 持久缓存命中（空串 = 上次确认「结构发现找不到」，同样是有效结果）
        KsCache.get(ckey, sig)?.let { cached ->
            if (!KsHookKit.enabled) return emptyList()
            return if (cached.isEmpty()) emptyList() else cached.split(',')
        }

        // 主线程只做廉价候选名；结构发现整体后台化
        val result = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        val done = java.util.concurrent.CountDownLatch(1)
        Thread {
            try {
                val names = ArrayList<String>()
                for (pkg in PLAYER_PACKAGES) {
                    val found = try {
                        byStructure(
                            Target(
                                id = "player.$pkg", pkg = pkg,
                                anyOf = listOf(
                                    Feature(name = "setDataSource", paramCount = 1),
                                    Feature(name = "setUrl", paramCount = 1),
                                    Feature(name = "openVideo", paramCount = 1),
                                )
                            ), cl
                        )
                    } catch (_: Throwable) { null }
                    // ★ 排除抽象类/接口（2026-09 真机实测修正）：
                    //   实测命中的 `com.kwai.video.player.AbstractMediaPlayer` 是抽象基类，
                    //   hook 它不会被执行（跑的是子类实现），属「看起来装上了、实际无效」。
                    //   宁可少报也不要假报。
                    if (found != null && isConcrete(found)) names.add(found.name)
                    else if (found != null) Logger.probe { "RESOLVE players: skip abstract ${found.name}" }
                }
                result.set(names)
                KsCache.put(ckey, sig, names.joinToString(","))
                if (names.isNotEmpty()) Logger.always("RESOLVE players: 后台结构发现 ${names.size} 个具体类: ${names.joinToString(",")}")
            } catch (_: Throwable) {
            } finally { done.countDown() }
        }.apply { name = "MJ-AdaptPlayers"; isDaemon = true }.start()

        return try {
            if (done.await(mainThreadWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) result.get()
            else {
                Logger.once("res.players.bg", "RESOLVE players: 结构发现转后台（主线程不受阻）")
                emptyList()
            }
        } catch (_: Throwable) { emptyList() }
    }

    /** 播放器候选名（保持项目原有顺序与命名空间） */
    private val KNOWN_PLAYER_CLASSES = listOf(
        "com.kwai.player.KwaiPlayer",
        "com.kwai.player.AemonPlayer",
        "com.kuaishou.player.KwaiPlayer",
    )

    // ==================== 供 [AdaptVerify] 使用的纯逻辑出口 ====================
    // 让验证台断言**真实实现**而非副本 —— 副本会随时间与实现漂移，
    // 那样「验证通过」就不再意味着实现正确。

    internal fun satisfiesForTest(require: List<Feature>, anyOf: List<Feature>, c: Class<*>): Boolean =
        satisfies(Target(id = "verify", pkg = "", require = require, anyOf = anyOf), c)

    internal fun knownPlayerClassesForTest(): List<String> = KNOWN_PLAYER_CLASSES

    /** 播放器所在的**稳定命名空间**（非混淆包名） */
    private val PLAYER_PACKAGES = listOf(
        "com.kwai.player.",
        "com.kwai.video.player.",
    )
}
