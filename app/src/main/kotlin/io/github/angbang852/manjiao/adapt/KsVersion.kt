package io.github.angbang852.manjiao.adapt

import android.content.Context
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.util.Logger

/**
 * 快手宿主版本探测（**自适应 Hook 的地基**）。
 *
 * ## 为什么需要它
 *
 * 本模块此前的版本适配方式是「硬编码类名候选数组 + findClass 兜底」——每适配一个
 * 新版本就要在几十处候选名里手动加一项（见《快手版本适配文档》里对 `knh.b` 的建议：
 * 「扩展为 knh.a / knh.c / knh\$b ...」）。这种做法的根本问题不是漏了哪个名字，
 * 而是**候选名是混淆产物，混淆表每版都变**——候选数组永远追不上。
 *
 * 本类把「版本」从「一串猜测的类名」变成**运行时可读的事实**，让后续的
 * [KsResolve] 能据此选择策略，而不是盲目穷举。
 *
 * ## 探测通道（三级回退，任一成功即止）
 *
 * 1. **PackageManager.getPackageInfo** —— 权威版本名。需要 `Context`；
 *    宿主进程里拿 `ActivityThread.currentApplication()`。
 * 2. **versionName 三段解析** —— 形如 `14.7.40.49980`：主版本 / 次版本 / 修订 /
 *    构建号。模块真正关心的**只有主次版本**（`14.7`），因为类名与结构的变更
 *    发生在这一层；构建号（49980）只用于日志与精确定位。
 * 3. **结构指纹回退** —— 拿不到版本号时（Context 未就绪 / PM 被 hook / 版本名被
 *    抹掉），退化为「按特征类是否存在」推出**能力等级**（capability tier）。
 *    这是关键设计：**模块的行为分支依赖能力等级，而不是版本号字符串**。
 *    这样即使版本探测完全失败，只要宿主类还在，功能就还能选对路径。
 *
 * ## 能力等级（Capability Tier）
 *
 * 把「版本号 → 需要走哪条 Hook 路径」的判断收敛成 4 个离散档位。
 * 新增版本时通常**只需要在 [tierOf] 的表里加一行**，不必改任何 Hook 逻辑。
 */
object KsVersion {

    // ==================== 版本号 ====================

    /** 版本名原文，如 `14.7.40.49980`；探测失败为 null */
    @Volatile
    var raw: String? = null
        private set

    /** 主版本，如 `14`；探测失败为 -1 */
    @Volatile
    var major: Int = -1
        private set

    /** 次版本，如 `7`；探测失败为 -1 */
    @Volatile
    var minor: Int = -1
        private set

    /** 修订号，如 `40`；探测失败为 -1 */
    @Volatile
    var patch: Int = -1
        private set

    /** 版本是否已探测（无论成功失败，保证只探一次） */
    @Volatile
    var probed: Boolean = false
        private set

    // ==================== 能力等级 ====================

    /**
     * 宿主能力档位 —— **Hook 选择的唯一依据**。
     *
     * 刻意与版本号解耦：档位由「结构特征」推导，版本号只是它的一个（较强）证据。
     */
    enum class Tier(val id: Int, val label: String) {
        /** 探测完全失败、且特征类也找不到：只装最小可用集，不猜 */
        UNKNOWN(0, "unknown"),

        /** 老结构：PhotoDetailFragment / CommentFragment 时代（≤14.5） */
        LEGACY(1, "legacy"),

        /** 中间结构：detail 包在、但播放器仍是 KwaiMediaPlayerImpl 系列（14.6～14.7） */
        MID(2, "mid"),

        /** 新结构：播放器已重构、QPhoto 走 milano/commonfeedslide（14.8+） */
        MODERN(3, "modern"),

        /** 更新结构：14.9+ 预留档位（当前无额外特征，走与 MODERN 相同的路径） */
        LATEST(4, "latest"),
    }

    /**
     * 当前档位。启动时由 [probe] 设定。
     *
     * ★ 默认 [Tier.MID] 而非 UNKNOWN：模块当前实测通过的版本是 14.7.40（属 MID），
     *   探测失败时选它等于「保持既有行为」——不引入回归是这个项目的硬约束。
     */
    @Volatile
    var tier: Tier = Tier.MID
        private set

    /** 档位推导依据（供日志与 UI 展示，排障时一眼看出为什么走了这条路径） */
    @Volatile
    var tierBasis: String = "default"
        private set

    // ==================== 探测入口 ====================

    /**
     * 执行探测。**幂等**：重复调用只有首次生效。
     *
     * ★ 时机（2026-09 真机实测修正）：本方法在 `onPackageLoaded` 早期被调用，
     *   那时宿主的 `Application` **尚未创建**——`ActivityThread.currentApplication()`
     *   此刻还返回 null。因此**不能依赖它**拿 Context。
     *   改为用 `ActivityThread.currentActivityThread().getSystemContext()`：
     *   系统上下文在进程启动早期就存在，且与宿主 Application 无关。
     *
     * @param cl 宿主类加载器（用于结构指纹回退）
     * @param ctx 可选 Context（拿不到时自动走上面的系统上下文通道）
     */
    fun probe(cl: ClassLoader?, ctx: Context? = null) {
        if (probed) return
        probed = true

        // ---- 通道 1/2：PackageManager 版本名 ----
        val name = readVersionName(ctx ?: systemContext())
        if (name != null) parse(name)

        // ---- 通道 3：结构指纹（**无论是否有版本号都算**，作为交叉校验） ----
        val (t, basis) = detectTier(cl)
        tier = t
        tierBasis = basis

        Logger.once(
            "adapt.version",
            "KSVER raw=${raw ?: "?"} parsed=$major.$minor.$patch tier=${t.label}(${t.id}) basis=$tierBasis"
        )
    }

    /**
     * 尝试补齐版本号（**不改变已定档位**，只补 `raw/major/minor/patch`）。
     *
     * ★ 为什么需要它（2026-09 真机实测）：[probe] 在 `onPackageLoaded` 早期调用，
     *   那时可能连系统 Context 都还没准备好，版本号读到 null、档位退化为
     *   「按结构指纹推断」。虽然档位正确（设计如此），但**用户看到的文案会变成
     *   「版本未知」，且日志里 `raw=?`**——排障时少了一条关键信息。
     *
     *   `Application.onCreate` 钩子里 Context 一定可用，在那里补一次即可。
     *   档位**不重算**：首个判定已经生效，中途改档会让已装好的 hook 与档位不一致。
     *
     * @return 是否成功补到版本号
     */
    fun refine(ctx: Context?): Boolean {
        if (raw != null && major > 0) return true
        val name = readVersionName(ctx ?: systemContext()) ?: return false
        parse(name)
        Logger.once("adapt.version.refined", "KSVER refined raw=$name parsed=$major.$minor.$patch")
        return true
    }

    /**
     * 取系统 Context（不依赖宿主 Application 是否已创建）。
     *
     * 与 `currentApplication()` 的区别：后者在 `onPackageLoaded` 阶段常为 null，
     * **实测确认这是版本号读不到的根因**。
     */
    private fun systemContext(): Context? = try {
        val att = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread").invoke(null)
        att?.javaClass?.getMethod("getSystemContext")?.invoke(att) as? Context
    } catch (_: Throwable) { null }

    /**
     * 读宿主版本名。
     *
     * ★ 两个必须避开的坑（2026-09 真机实测）：
     * 1. **不能用 `c.packageName`** —— 本代码跑在快手进程里，但注入时
     *    Context 的 `packageName` 可能指向模块自身或系统，拿到的版本号就错了。
     *    必须显式用**宿主包名常量**（[KsClass.PKG]）。
     * 2. **要遍历主包与极速版** —— 用户可能装的是 nebula（极速版），
     *    两个包名都要试，否则读不到。
     */
    private fun readVersionName(c: Context?): String? {
        if (c == null) return null
        val pm = try { c.packageManager } catch (_: Throwable) { null } ?: return null
        for (pkg in arrayOf(KsClass.PKG, KsClass.PKG_NEBULA)) {
            val v = try { pm.getPackageInfo(pkg, 0).versionName } catch (_: Throwable) { null }
            if (!v.isNullOrBlank()) {
                Logger.probe { "KSVER: versionName from $pkg = $v" }
                return v
            }
        }
        return null
    }

    /** 解析 `14.7.40.49980` 这类版本名。任何一段缺失都容忍（记 -1） */
    private fun parse(name: String) {
        raw = name
        // 版本名偶尔带后缀（如 `14.7.40.49980-beta`），只取数字段
        val (mj, mn, pt) = parseNameForTest(name)
        major = mj; minor = mn; patch = pt
    }

    /**
     * 结构指纹 → 档位。
     *
     * 判据按**从新到旧**的顺序排列，命中即返回——这样新增更新版本时，
     * 只需在列表**最前面**插一条新特征即可（见文档「扩展点」）。
     *
     * 用到的都是**语义类名**（非混淆）：`com.yxcorp.*` / `com.kwai.*` 这些包名
     * 快手十年没改过，比任何混淆类名都稳。混淆类名（`knh.b` / `rerank.e$b`）
     * 一律不作为版本判据——它们由 [KsResolve] 的运行时结构发现兜底。
     */
    private fun detectTier(cl: ClassLoader?): Pair<Tier, String> {
        if (cl == null) return Tier.MID to "no-classloader"

        fun has(cn: String): Boolean = try {
            // ★ 必须 try/catch 包住：Class.forName 在类不存在时**抛**异常而非返回 null，
            //   因此这里不能写成 `Class.forName(...) != null`（那是恒真判断）
            Class.forName(cn, false, cl)
            true
        } catch (_: Throwable) { false }

        // 版本号优先（最强证据）：主次版本直接映射
        if (major > 0) {
            val t = tierOfImpl(major, minor)
            return t to "versionName=$raw"
        }

        // ---- 无版本号：按结构特征 ----
        // 新结构标志：播放器已重构 + milano 容器在
        if (has(KsClass.MILANO_COMMON_FEED_SLIDE)) return Tier.MODERN to "fingerprint:milano"
        // 中结构标志：detail Activity + 旧播放器
        if (has("com.kwai.video.player.KwaiMediaPlayerImpl")) return Tier.MID to "fingerprint:kwaiMediaPlayerImpl"
        if (has(KsClass.PHOTO_DETAIL_ACTIVITY)) return Tier.MID to "fingerprint:photoDetailActivity"
        // 老结构标志：PhotoDetailFragment 时代
        if (has(KsClass.PHOTO_DETAIL_FRAGMENT)) return Tier.LEGACY to "fingerprint:photoDetailFragment"

        return Tier.UNKNOWN to "fingerprint:none"
    }

    /**
     * 版本号 → 档位映射表（**新增版本时改这里就够了**）。
     *
     * 分界依据（来自《快手版本适配文档》的静态类名核对结论 + 项目实测）：
     * - `14.8+`：播放器 `com.kwai.video.player.KwaiMediaPlayerImpl*` 系列全部消失，
     *   milano/commonfeedslide 成为主容器 → MODERN
     * - `14.6 ~ 14.7`：detail 包结构与旧播放器并存 → MID（当前实测版本 14.7.40 在此档）
     * - `<= 14.5`：PhotoDetailFragment / CommentFragment 尚在 → LEGACY
     *
     * ★ 未列出的版本一律落到区间判断的默认分支，**不会崩溃、不会跳过功能**，
     *   只是档位可能偏保守（走 MID = 既有实测路径）。
     */
    private fun tierOfImpl(major: Int, minor: Int): Tier = when {
        major >= 15 -> Tier.LATEST
        major == 14 && minor >= 9 -> Tier.LATEST
        major == 14 && minor >= 8 -> Tier.MODERN
        major == 14 && minor >= 6 -> Tier.MID
        major == 14 -> Tier.LEGACY
        major in 1..13 -> Tier.LEGACY
        else -> Tier.MID
    }

    // ==================== 对外查询 ====================

    /** 版本号字符串，探测失败时给出可读占位（日志/UI 用，不参与逻辑判断） */
    fun display(): String = raw ?: when (tier) {
        Tier.UNKNOWN -> "未知版本"
        else -> "未知版本（按 ${tier.label} 结构推断）"
    }

    /** `14.7` 形式的短版本，用于日志前缀 */
    fun short(): String = if (major > 0) "$major.$minor" else tier.label

    /**
     * 当前档位是否 **>=** 给定档位。
     *
     * 用于表达「14.8 以后才有的结构」这类判断，避免到处写 `major == 14 && minor >= 8`。
     * UNKNOWN 档位恒返回 false（宁可不装也不猜）。
     */
    fun atLeast(t: Tier): Boolean = tier != Tier.UNKNOWN && tier.id >= t.id

    // ==================== 供 [AdaptVerify] 使用的纯逻辑出口 ====================
    // 这三个函数把内部实现原样暴露给验证台（**不是给运行时用的**），
    // 使「测试调用真实实现」而非「测试复制一份实现」—— 后者会随时间漂移。

    internal fun tierOf(major: Int, minor: Int): Tier = tierOfImpl(major, minor)
    internal fun atLeastOf(t: Tier, target: Tier): Boolean =
        t != Tier.UNKNOWN && t.id >= target.id

    /** 解析版本名 → (major, minor, patch)；与 [parse] 同实现，供验证台断言 */
    internal fun parseNameForTest(name: String): Triple<Int, Int, Int> {
        val parts = name.split('.', '-', '_')
        fun num(i: Int): Int = parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: -1
        return Triple(num(0), num(1), num(2))
    }
}
