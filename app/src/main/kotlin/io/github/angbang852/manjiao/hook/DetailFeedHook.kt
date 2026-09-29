package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.api.XposedInterface

/**
 * ★★★ 详情页取数拦截（2026-09-26 用户定稿方案 C）。
 *
 * ## 完整链路（全部实证）
 *
 * ```
 * 详情页（PhotoDetailActivity / 上下滑）
 *     ↓
 * NasaPhotoDetailFragment.getPageParams()
 *     ↓
 * SlidePlayViewModel.x4(int)          ← ███ 拦在这里（按位置取条）
 *     ↓
 * NasaPhotoDetailFragment.M (QPhoto)  ← 渲染持有者
 *     ↓
 * 屏幕显示
 * ```
 *
 * ## 证据
 *
 * 1. `ENTRYTRACE` 实证详情页取数走 `Doreas.x4`（= SlidePlayViewModel.x4）：
 * ```
 * [ENTRYTRACE] 调用链=...installVmGet ← Doreas.x4 ← a_f$c_f.v ← l.a ← u.k
 * ```
 * 2. `FRAGFLD` 实证详情页每条都挂在 `NasaPhotoDetailFragment.M`：
 * ```
 * [FRAGFLD] ★持有可见项的字段: M 类型=QPhoto 在类=NasaPhotoDetailFragment
 * ```
 * 3. `PDEL-MISS` 实证判定正确但「找不到移除点」—— 因为它只在 `M` 上，
 *    而列表级移除碰不到单值字段。
 *
 * ## 为什么「返回 null」这次有效（而之前 SCREEN-BLOCK 无效）
 *
 * 之前 `SCREEN-BLOCK` 失败的场景是**首页信息流卡片**（feedstaggercard 体系）——
 * 那里 RecyclerView 的 adapter 有兜底（位置上没数据就从别的源补）。
 *
 * 详情页不同：`M` 是**单值字段**，赋值来源就是 `x4(idx)` 的返回值 ——
 * **返回 null ⇒ `M` = null ⇒ 无内容可渲染**（快手自己会处理空位，翻到下一条）。
 *
 * ## 安全
 *
 * · 只处理「返回值确认是 QPhoto」的情况
 * · 判 WHITE ⇒ 原样返回
 * · 白名单未开启 ⇒ 完全不干预
 * · 异常全吞
 * · 不修改任何对象状态（只影响本次返回值）
 */
object DetailFeedHook {

    private var installed = false

    fun install(xp: XposedInterface, cl: ClassLoader) {
        if (installed) return
        installed = true
        val cn = "com.kwai.library.groot.api.viewmodel.SlidePlayViewModel"
        val cls = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null }
        if (cls == null) {
            Logger.evidence("DLHOOK2-INST", "★类不存在: $cn")
            return
        }
        var n = 0
        for (m in cls.declaredMethods) {
            // 特征：单 int 参数 + 返回 QPhoto（= 「按下标取条」方法族）
            if (m.parameterCount != 1) continue
            if (m.parameterTypes[0] != Int::class.javaPrimitiveType) continue
            val qc = CfhState.qpClassRef
            if (qc != null && !qc.isAssignableFrom(m.returnType)) continue
            try {
                m.isAccessible = true
                val mName = m.name
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("dlfeed.$mName").intercept { chain ->
                        val r = chain.proceed()
                        try {
                            val qcNow = CfhState.qpClassRef
                            if (r != null && qcNow != null && qcNow.isInstance(r) &&
                                CfhState.whiteListEnabled
                            ) {
                                // ★★★ ADPROBE（2026-10-01）：ad-detail NPE 归因探针。
                                //
                                // ## 要回答的问题
                                //   历史崩溃 `NullPointerException: mLogListener must not be null`
                                //   发生在 `AdDetailVMFragment` 相关路径（当天 2 次），
                                //   崩溃栈零模块帧、全仓 grep 零命中、之后两次冷启未复现
                                //   ⇒ 既不能确认是快手自身，也不能排除是模块副作用。
                                //   本行把「此刻模块**正在**对详情页取数结果做白名单净化判定」
                                //   落成带毫秒时间戳的事实；事后拿崩溃时刻做时间窗比对即可切开。
                                //
                                // ## 门控与频率
                                //   RateLimiter 滑动窗口 30 次/分钟（与 PresenterBindHook 那个
                                //   调用点各自独立计数）⇒ 本条上限 **30 行/分钟**。
                                //   为什么不用项目里常见的 `xxxLog < N` 进程级限次：
                                //   那个形态会在 N 次后**永久静默**（RateLimiter 类注释里的
                                //   `TTPPARSE-BLOCK=80`/`PV2-BLOCK=60` 就是这个坑），
                                //   而本探针最不能接受「崩溃前那段静默」。
                                //
                                // ## 记录纪律
                                //   只记 动作类型 + 类名 + 方法名 + 计数（毫秒时间戳由
                                //   Logger.writePlain 的行前缀给出）。**不记**昵称/文案/photoId/URL。
                                //   tag 已加入 Logger.PLAIN_TAGS，故可在 evidence.txt 直接 grep。
                                //
                                // ## 行为不变
                                //   纯观测：不参与判定、不改返回值、不吞异常。
                                if (io.github.angbang852.manjiao.util.RateLimiter.allow("ADPROBE", 30)) {
                                    CfhState.adProbeCount++
                                    Logger.evidence(
                                        "ADPROBE",
                                        "★动作=DLFEED_JUDGE(详情页取数白名单判定) 类=$cn 方法=$mName " +
                                            "次数=${CfhState.adProbeCount}"
                                    )
                                }
                                val v = try { CfhDecide.judgeWhitelist(r) }
                                        catch (_: Throwable) { CfhDecide.WhitelistVerdict.PENDING }
                                // ★★★ 黑名单补拦（2026-09-26「咔咔剪」）。
                                //
                                // ## 时序差问题（实测）
                                //
                                // `x4` 取数那一刻**声明字段未回填** ⇒ `judgeWhitelist` 判白 ⇒ 放行；
                                // 稍后 `PSCAN`（周期扫描）时字段已到 ⇒ 判脏 ⇒ 写入
                                // `CfhState.dirtyPhotoMap`（黑名单）。
                                // 但该条已在详情页渲染 —— 事后判脏**必须补拦**。
                                //
                                // ## 为什么这里补拦有效
                                //
                                // `x4` 被高频反复调用（实测 60 次/40 秒、同 id 反复出现）：
                                // **下一轮 `x4` 再返回它时，黑名单里已有它** ⇒ 此处拦下，
                                // 返回 null ⇒ 该位置内容消失。
                                //
                                // ## 为什么不会误拦
                                //
                                // 黑名单只由 `PSCAN`（字段已到齐时的完整判定）写入 ——
                                // 命中即「已被确认脏」，无时序风险。
                                val pid0 = try { CfhProbe.readPhotoId(r) } catch (_: Throwable) { null }
                                // ★★★ 2026-09-29 v13.20 移除黑名单兜底（用户定稿）：
                                //   网络层 GSCOLL/TTPPARSE 每批逐条白名单新鲜判定，
                                //   DIRTY/PENDING 当场删除 —— 脏项根本到不了详情页。
                                //   黑名单兜底把「一次误判」变成「30 分钟永久删除」
                                //   （50388 实测粤菜黄师傅/可乐测评被永久拉黑 ⇒ 精选页空 ⇒ 无网络）。
                                //   ⇒ 此处不再查黑名单，判定交给新鲜判定 v。
                                val inBlacklist = false
                                // ★★★ 声明映射查询（2026-09-27）：本实例声明为空，
                                //   但解析期原始实例带 AI 声明 ⇒ 副本字段丢失，按映射判脏。
                                val declFromMap = if (v == CfhDecide.WhitelistVerdict.WHITE)
                                    CfhState.lookupDecl(pid0) else null
                                val vFinal = when {
                                    inBlacklist -> CfhDecide.WhitelistVerdict.DIRTY
                                    declFromMap != null -> CfhDecide.WhitelistVerdict.DIRTY
                                    else -> v
                                }
                                if (vFinal != CfhDecide.WhitelistVerdict.WHITE) {
                                    // ★ 详情页取数挡下：返回 null ⇒ M 不被赋脏值
                                    if (CfhState.dlFeedLog < 60) {
                                        CfhState.dlFeedLog++
                                        val cap = try { CfhUtil.readCaption(r) } catch (_: Throwable) { null }
                                        val rsn = when {
                                            inBlacklist -> "黑名单补拦(${v.name}判白)"
                                            declFromMap != null -> "ai:declMap \"$declFromMap\""
                                            else -> CfhDecide.lastHitReason
                                        }
                                        Logger.evidence(
                                            "DLFEED-BLOCK",
                                            "★详情页取数挡下(${vFinal.name}) $mName " +
                                                "id=$pid0 判据=${rsn ?: "?"} cap=\"${cap?.take(22) ?: "-"}\""
                                        )
                                    }
                                    return@intercept null
                                }
                            }
                        } catch (_: Throwable) {}
                        r
                    }
                n++
            } catch (_: Throwable) {}
        }
        Logger.evidence("DLHOOK2-INST", "详情页取数拦截已安装: 挂载 $n 个取数方法")
        CfhState.noteHookStatus("详情页取数拦截", n > 0, "SlidePlayViewModel 取数 · $n 个方法")
    }
}
