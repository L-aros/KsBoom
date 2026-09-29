package io.github.angbang852.manjiao.adapt

import io.github.angbang852.manjiao.util.Logger

/**
 * 统一的 Hook 装配层 —— **版本差异的收敛边界**。
 *
 * ## 设计目标
 *
 * 约束（来自任务要求）：
 * - 新增一个快手版本时，**只扩展适配规则、不改核心逻辑**
 * - 版本探测失败 / 类缺失 / 方法缺失 **不得崩溃**
 * - 保持项目现有代码风格，不引入新依赖
 *
 * ## 做法
 *
 * 把「装一个 hook」抽象成三步，每步都可以失败且都有明确降级：
 *
 * ```
 * 解析目标类 (KsResolve)  →  解析目标方法  →  装钩
 *      ↓ 失败                    ↓ 失败          ↓ 失败
 *   记 miss 日志             记 miss 日志     记 miss 日志
 *   跳过本 hook              跳过本 hook      跳过本 hook
 * ```
 *
 * 关键在于**失败是预期路径而非异常**。三个步骤任一失败都只影响这一个 hook，
 * 其余 hook 照常装载 —— 这正是项目既有「多处容错」思路的显式化与统一化。
 *
 * ## 与既有代码的关系
 *
 * 本层是**增量**的：既有 hook 里那些「裸 findClass + continue」的写法可以逐步
 * 迁移过来（见 `KsHooks`）。未迁移的代码不受影响，行为完全不变 —— 这是
 * 「不破坏现有已支持版本」约束的落地方式。
 */
object KsHookKit {

    /**
     * 自适应层**总开关**（2026-09-23 新增，用于故障隔离）。
     *
     * ★ 为什么必须有这个开关：
     *   一个「提升兼容性」的适配层，本身绝不能变成新的故障源。当线上出现
     *   「装上模块后卡顿/异常，卸掉就好」的现象时，必须能**在不重新打包、
     *   不猜测原因**的前提下，一步退回到「适配层完全不存在」的状态，
     *   把适配层是否存在与故障是否存在直接关联起来。
     *
     * 关闭时的语义（重要）：
     * - [KsVersion.probe] **仍然执行**（只是不再被任何 hook 消费）——它只读不写，
     *   没有副作用，保留它有助于日志对比。
     * - [KsResolve] 的所有解析走**候选名直查**（等价于改动前行为）。
     * - 各 hook 站点回退到原有的硬编码候选名路径（它们本来就保留了这一层）。
     *
     * 打开方式：设置项 `adapt_on`（2026-09-30 起**默认 false**，用户定稿「全关，按需开启」）。
     *   ⚠️ 该键**没有 UI 入口**，只能用 adb 写（见下）。
     * 排障用法：`adb shell` 写 `adapt_on=false` → 重启快手 → 看故障是否消失。
     */
    @Volatile
    var enabled: Boolean = true

    /** 装配结果 —— 用于汇总报告，让「哪些 hook 没装上」一眼可见 */
    data class Outcome(val tag: String, val ok: Boolean, val detail: String)

    private val outcomes = java.util.concurrent.ConcurrentHashMap<String, Outcome>()

    fun outcomeOf(tag: String): Outcome? = outcomes[tag]

    /** 全部装配结果（供设置页/日志展示适配状态） */
    fun allOutcomes(): List<Outcome> = outcomes.values.sortedBy { it.tag }

    /**
     * 装一个**类级 hook**：解析类 → 按特征找方法 → 逐个装钩。
     *
     * 注：本层**不接收 `xp`** —— 装钩动作由调用方的 [install] lambda 完成，
     * 它在闭包里自然持有 `xp`。传进来再不用会让「这层负责装钩」产生误解。
     *
     * @param tag      稳定标识（去重键 + 日志前缀）
     * @param target   [KsResolve.Target] 结构描述
     * @param methodFeatures 要 hook 的方法特征（可多个，全都会装）
     * @param install  装钩回调（拿到解析出的类与方法列表）；异常由本层吞掉并记为失败
     * @return 成功装上的方法数（0 表示该类没有被 hook —— 调用方据此决定回退）
     */
    fun hookClass(
        cl: ClassLoader,
        tag: String,
        target: KsResolve.Target,
        methodFeatures: List<KsResolve.Feature>,
        install: (Class<*>, List<java.lang.reflect.Method>) -> Unit,
    ): Int {
        return try {
            val cls = KsResolve.resolve(target, cl)
            if (cls == null) {
                record(tag, false, "class unresolved")
                return 0
            }
            val methods = KsResolve.findMethods(cls, *methodFeatures.toTypedArray())
            if (methods.isEmpty()) {
                record(tag, false, "no method matched in ${cls.name}")
                Logger.once("hk.$tag.nomethod", "HK $tag: class ${cls.name} found but no method matched — skipped")
                return 0
            }
            install(cls, methods)
            record(tag, true, "${cls.name} (${methods.size} methods)")
            Logger.once("hk.$tag.ok", "HK $tag: hooked ${cls.name} x${methods.size}")
            methods.size
        } catch (t: Throwable) {
            // 任何意外都收敛成「这个 hook 没装上」，绝不上抛——
            // 一个 hook 的问题不能连累其余 hook 与模块本身
            record(tag, false, "${t.javaClass.simpleName}: ${t.message}")
            Logger.once("hk.$tag.err", "HK $tag: FAILED ${t.javaClass.simpleName}: ${t.message}")
            0
        }
    }

    /**
     * 装一个**已知类**的 hook（类名稳定、只有方法可能变）。
     *
     * 这是最常见的场景：`com.yxcorp.gifshow.HomeActivity` 这类语义类名十年不变，
     * 但里面的方法可能被重构/改名 → 用特征找方法而非硬编码方法名。
     *
     * 注：与 [hookClass] 一样**不接收 `xp`** —— 装钩在 [install] 闭包里完成。
     *
     * @return 装上的方法数
     */
    fun hookKnownClass(
        cl: ClassLoader,
        tag: String,
        className: String,
        methodFeatures: List<KsResolve.Feature>,
        install: (Class<*>, List<java.lang.reflect.Method>) -> Unit,
    ): Int {
        return try {
            val cls = io.github.angbang852.manjiao.util.Reflect.findClass(className, cl)
            if (cls == null) {
                record(tag, false, "class $className missing")
                return 0
            }
            val methods = KsResolve.findMethods(cls, *methodFeatures.toTypedArray())
            if (methods.isEmpty()) {
                // 类在但方法特征没中：这是**版本漂移的信号**，必须留下可读线索
                Logger.once("hk.$tag.drift", "HK $tag: $className present but ${methodFeatures.size} feature(s) unmatched — possible API drift")
                record(tag, false, "method drift in $className")
                return 0
            }
            install(cls, methods)
            record(tag, true, "$className (${methods.size})")
            methods.size
        } catch (t: Throwable) {
            record(tag, false, "${t.javaClass.simpleName}: ${t.message}")
            0
        }
    }

    /**
     * 装一个 **native/静态** hook（无类解析，直接装；失败即降级）。
     *
     * 用于 `URL.<init>` / `MediaPlayer.setDataSource` 这类 JDK/Framework 目标——
     * 它们不随快手版本变化，但**可能随 Android 版本变化**，故同样需要容错。
     */
    fun hookDirect(tag: String, action: () -> Unit): Boolean {
        return try {
            action()
            record(tag, true, "direct")
            true
        } catch (t: Throwable) {
            record(tag, false, "${t.javaClass.simpleName}: ${t.message}")
            Logger.once("hk.$tag.err", "HK $tag: direct hook FAILED ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun record(tag: String, ok: Boolean, detail: String) {
        outcomes[tag] = Outcome(tag, ok, detail)
    }

    /**
     * 供 [KsResolve] 登记「某个目标类解析成功/失败」。
     *
     * ★ 为什么需要这个显式入口（2026-09 真机实测踩到）：
     *   迁移后的 hook 站点是**直接调用 `KsResolve`** 拿类，不经过 [hookClass] /
     *   [hookKnownClass]。于是 `outcomes` 从未被写入，[report] 的
     *   `if (all.isEmpty()) return` 让整段装配摘要**静默消失** ——
     *   实测日志里完全看不到 `ADAPT` 行，等于报告机制形同虚设。
     *
     *   因此把「登记」与「装钩」解耦：无论调用方走哪条路径，只要经过
     *   [KsResolve] 解析，结果就会进入报告。
     */
    fun noteResolve(tag: String, ok: Boolean, detail: String) {
        // 只登记首次结果：同一 tag 的重入解析（缓存命中）不覆盖，
        // 保留最先那次的 via（那才是「为什么选中它」的答案）
        if (outcomes.containsKey(tag)) return
        record(tag, ok, detail)
    }

    // ==================== 装配报告 ====================

    /**
     * 输出一次可读的装配摘要（`Logger.once`：不受 quiet 门控，每进程一行/段）。
     *
     * 排障价值：用户报「某功能在 X 版本无效」时，这一段日志直接告诉我们是
     * 「类没找到」「方法漂移」还是「装钩异常」—— 不必再靠猜。
     *
     * ★ 即使 `outcomes` 为空也**必须输出摘要行**：那一行含版本与档位，
     *   是「探测到底成没成」的唯一证据。原先 `all.isEmpty() -> return`
     *   会让「探测成功但无解析目标」和「报告机制坏了」看起来一模一样。
     */
    fun report() {
        val all = allOutcomes()
        val ok = all.count { it.ok }
        Logger.once(
            "adapt.report.head",
            "ADAPT ${KsVersion.display()} tier=${KsVersion.tier.label} basis=${KsVersion.tierBasis} | resolve ok=$ok/${all.size}"
        )
        for (o in all.filter { !it.ok }) {
            Logger.once("adapt.report.${o.tag}", "ADAPT MISS ${o.tag}: ${o.detail}")
        }
        // ★ 发布到共享配置：设置页跑在**模块进程**，读不到宿主进程的 KsVersion
        //   静态状态，必须经 Prefs（跨进程共享）把探测结果带过去。
        //   见 SettingsActivity.adaptationText()
        publish()
    }

    /**
     * 把探测结果写入共享配置，供设置页展示（`adapt_info` 单键，格式 `版本|档位|失败数`）。
     *
     * 用单键 + 竖线分隔而非三个独立键：读取方只需一次 `Prefs.str`，
     * 且不会出现「版本已更新但档位还是旧的」这种半更新状态。
     */
    private fun publish() {
        try {
            val failed = allOutcomes().count { !it.ok }
            val v = (KsVersion.raw ?: "") + "|" + KsVersion.tier.label + "|" + failed
            io.github.angbang852.manjiao.data.Prefs.setStr("adapt_info", v)
        } catch (_: Throwable) {}
    }

    /**
     * 设置页侧读取（跑在**模块进程**）。解析失败一律返回 null = 「未连接」——
     * 宁可显示「不知道」，也不编造一个看起来正确的档位。
     */
    fun readPublished(): AdaptInfo? {
        return try {
            val raw = io.github.angbang852.manjiao.data.Prefs.str("adapt_info", "")
            if (raw.isBlank()) return null
            val p = raw.split('|')
            if (p.size < 3) return null
            AdaptInfo(p[0].takeIf { it.isNotBlank() }, p[1], p[2].toIntOrNull() ?: 0)
        } catch (_: Throwable) { null }
    }

    /** 适配状态快照（跨进程展示用） */
    data class AdaptInfo(val version: String?, val tier: String, val failedHooks: Int)
}
