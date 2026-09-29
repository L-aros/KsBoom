package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * ★★★ 首页信息流拦截器（2026-09-26 用户定稿，**实测有效、零崩溃**）。
 *
 * ## 完整链路（全部实证）
 *
 * ```
 * 网络响应
 *     ↓
 * ★ PresenterV2.bind(Object[])        ← ███ 本 hook
 *     · data[10] = QPhoto（直接）
 *     · data[7]  = 类 c（内部含同一 QPhoto）
 *     ↓
 * PresenterV2.updateData(Object[])    分发到子 Presenter
 *     ↓
 * sni.o.doInject() → inject(QPhoto.class) → this.e = QPhoto
 *     ↓
 * sni.o.onBind() → 渲染首页卡片
 * ```
 *
 * **拦住 `bind` ⇒ QPhoto 进不了注入容器 ⇒ `sni.o.e` 为 null ⇒ 卡片根本不创建。**
 *
 * ## 证据链（怎么找到的）
 *
 * ### ① 关键词精确定位（突破点）
 *
 * 用户报「Ai爱不释手」—— 用一次性探针 hook `TextView.setText`，
 * **只记录含该关键词的文案**，命中后打印**父 View 链**：
 * ```
 * ↑3 com.kwai.component.feedstaggercard.widget.HomeFeedCornerCardBackgroundView
 * ↑4 com.yxcorp.gifshow.mortise.widget.GeminiMortiseRecyclerView   ← 首页列表
 * ↑5 com.yxcorp.gifshow.mortise.component.recyclerview.MortiseRefreshLayout
 * ```
 * **父 View 链直接暴露了首页信息流的组件体系。**
 *
 * ### ② dex 反编译 `sni.o`（首页卡片 Presenter）
 *
 * ```java
 * public class sni.o {
 *     public QPhoto e;                  // ★ 卡片持有的 QPhoto
 *     public TextView j;                // 文案
 *
 *     public void doInject() {
 *         this.e = (QPhoto) inject(QPhoto.class);      // ★★ 从注入容器取
 *         this.o = (BaseFragment) inject("FRAGMENT");
 *         this.t = (PhotoItemViewParam) injectOptional("FEED_ITEM_VIEW_PARAM");
 *     }
 *     public void onBind() -> V          // 渲染入口
 * }
 * ```
 *
 * ### ③ `PresenterV2.bind` 的调用者（含首页）
 * ```
 * com.yxcorp.gifshow.HomeActivity.onCreate
 * ```
 *
 * ### ④ 探针实测（`PV2DATA`）
 * ```
 * [PV2DATA] ★bind[10] 直接QP 参数类=QPhoto id=5230086751382468295 判定=WHITE
 * [PV2DATA] ★bind[7]  挖出QP 参数类=c      id=5230086751382468295 判定=WHITE
 * ```
 *
 * ## 拦截策略（用户定稿「无差别 + 白名单制」）
 *
 * ```
 * 遍历 bind(Object[]) 的所有参数：
 *   · 参数直接是 QPhoto，或能挖出 QPhoto
 *   · 判非 WHITE（DIRTY / PENDING）⇒ 整个 bind 不执行
 *   · 判 WHITE ⇒ 正常放行
 * ```
 *
 * **为什么无差别可行**：白名单语义是「判正常才放行」——
 * 任何携带脏 QPhoto 的 bind 都该被拦，无论它属于哪个页面。
 *
 * ## 为什么之前的尝试全部失败
 *
 * 模块此前挂的是 `HomeFeedResponse` / `SlidePlayViewModel` / `CfhWash` ——
 * 实测那些抓到的内容**和屏幕显示完全无关**：
 * ```
 * 用户报的 13 个名字（琪琪追剧/东平短剧/娇娇吖/六六安全科普/老王ai剧场…）
 *   ⇒ 证据里全部 0 命中
 * 而模块同时抓到的
 *   ⇒ 罗罗汤马西 / 创意工匠 / 南辞短剧 …（30 余个，屏幕上没有）
 * ```
 * **⇒ 首页信息流走 `feedstaggercard` + `mortise` 体系，是独立链路。**
 *
 * ## 安全（吸取三次崩溃的教训）
 *
 * | 教训 | 本模块的应对 |
 * |---|---|
 * | `WASHSEL` 清 adapter 数据 ⇒ ViewPager 崩 | **不改任何列表/对象** |
 * | `DS-BLOCK` 清页面依赖数据 ⇒ 详情页崩 | **保持对象状态不变** |
 * | `ListAddInterceptor` 挂高频方法 ⇒ native crash | `bind` 频率低（每卡片一次） |
 *
 * **关键：不修改数据，只是不执行 bind ⇒ 卡片不创建 ⇒ 无空卡片。**
 *
 * ## 实测结果
 *
 * ```
 * PV2-BLOCK = 60 条（连续刷 12 次压力测试）
 * 崩溃      = 0
 * 屏幕      = 干净内容
 * ```
 */
object PresenterBindHook {

    private var installed = false

    fun install(xp: XposedInterface, cl: ClassLoader) {
        if (installed) return
        installed = true
        val cn = "com.smile.gifmaker.mvps.presenter.PresenterV2"
        val cls = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null }
        if (cls == null) {
            Logger.evidence("PV2BIND-INST", "★类不存在: $cn")
            return
        }
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "bind" && m.name != "updateData") continue
            if (m.parameterCount != 1) continue
            if (m.parameterTypes[0] != Array<Any>::class.java) continue
            try {
                m.isAccessible = true
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("pv2bind.${m.name}").intercept { chain ->
                        var blocked = false
                        try {
                            if (CfhState.whiteListEnabled) {
                                // ★★★ ADPROBE（2026-10-01）：ad-detail NPE 归因探针。
                                //
                                // ## 要回答的问题
                                //   历史崩溃 `NullPointerException: mLogListener must not be null`
                                //   发生在 `AdDetailVMFragment` 相关路径（当天 2 次），
                                //   崩溃栈零模块帧、全仓 grep 零命中、之后两次冷启未复现
                                //   ⇒ 既不能确认是快手自身，也不能排除是模块副作用。
                                //   本行把「此刻模块**正在**对卡片绑定数据做白名单净化判定」
                                //   落成带毫秒时间戳的事实；事后拿崩溃时刻做时间窗比对即可切开。
                                //
                                // ## 门控与频率
                                //   RateLimiter 滑动窗口 30 次/分钟（与 DetailFeedHook 那个
                                //   调用点各自独立计数）⇒ 本条上限 **30 行/分钟**。
                                //   不用 `xxxLog < N` 进程级限次：那个形态会在 N 次后永久静默
                                //   （见 RateLimiter 类注释），而崩溃前最不能缺记录。
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
                                        "★动作=PV2BIND_JUDGE(卡片绑定白名单判定) 类=$cn 方法=${m.name} " +
                                            "次数=${CfhState.adProbeCount}"
                                    )
                                }
                                val arr = chain.args.getOrNull(0) as? Array<*> ?: return@intercept null
                                val qpCls = CfhState.qpClassRef
                                if (qpCls != null) {
                                    outer@ for (a in arr) {
                                        if (a == null) continue
                                        val q = if (qpCls.isInstance(a)) a
                                                else try { CfhProbe.findQpInObject(a) }
                                                     catch (_: Throwable) { null }
                                                ?: continue
                                        val v = try { CfhDecide.judgeWhitelist(q) }
                                                catch (_: Throwable) { CfhDecide.WhitelistVerdict.PENDING }
                                        // ★ 统一延迟复判：判白且声明未回填 ⇒ 入队
                                        val pidW = try { CfhProbe.readPhotoId(q) } catch (_: Throwable) { null }
                                        // ★★★ 声明映射查询（2026-09-27）：副本声明丢失时按解析期映射判脏
                                        val blockedByMap = v == CfhDecide.WhitelistVerdict.WHITE &&
                                            CfhState.lookupDecl(pidW) != null
                                        // ★★★ 判白诊断（2026-09-26 用户报「有 AI 标识但放行」）。
                                        //
                                        // ## 要回答的问题
                                        //
                                        // 用户报多条「有 AI 标识但没拦住」的内容（如山藏万象等）。
                                        // `ENTRYTRACE` 显示它们**进了判定但没被拦** ⇒ 判白。
                                        //
                                        // 可能原因：
                                        //   ① `ai:disclaimer` 声明字段**未回填**（时序问题）
                                        //   ② `anyObj=false`（容器都不存在）⇒ 连 pending 都不触发
                                        //   ③ 判定函数的其他早退路径
                                        //
                                        // 本探针把**判定那一刻的字段实况**打出来，
                                        // 让「为什么判白」变成事实而非猜测。**只读**，限次 40。
                                        if (v == CfhDecide.WhitelistVerdict.WHITE &&
                                            CfhState.whiteWhyLog < 40
                                        ) {
                                            CfhState.whiteWhyLog++
                                            try {
                                                val ent = Reflect.readAny(q, "mEntity") ?: q
                                                val pm = Reflect.readAny(ent, "mPhotoMeta")
                                                val d1 = Reflect.readAny(ent, "mDisclaimergeMessage")
                                                val d2 = Reflect.readAny(pm, "mDisclaimerMessage")
                                                val d3 = Reflect.readAny(pm, "mDisclaimergeMessageV2")
                                                val c0 = try { CfhUtil.readDisclaimer(q, ent, pm) } catch (_: Throwable) { null }
                                                val aiTag = try { Reflect.readAny(ent, "mAiTagForAuthor") } catch (_: Throwable) { null }
                                                val aiTrace = try { CfhUtil.hasAnyAiTrace(ent) } catch (_: Throwable) { false }
                                                val cap = try { CfhUtil.readCaption(q) } catch (_: Throwable) { null }
                                                val pid = try { CfhProbe.readPhotoId(q) } catch (_: Throwable) { null }
                                                Logger.evidence(
                                                    "WHYWHITE",
                                                    "判白 id=$pid cap=\"${cap?.take(20) ?: "-"}\" " +
                                                        "声明=${if (c0.isNullOrBlank()) "空" else "\"${c0.take(20)}\""} " +
                                                        "容器[ent=${d1 != null} pm=${d2 != null} v2=${d3 != null}] " +
                                                        "aiTag=$aiTag aiTrace=$aiTrace " +
                                                        "pm=${if (pm != null) "有" else "无"} " +
                                                        "ent=${ent.javaClass.simpleName}"
                                                )
                                            } catch (_: Throwable) {}
                                        }
                                        // ★★★ 声明映射补拦（2026-09-27）：副本声明丢失，
                                        //   但解析期映射显示该 photoId 带 AI 声明 ⇒ 拦。
                                        if (blockedByMap) {
                                            blocked = true
                                            if (io.github.angbang852.manjiao.util.RateLimiter.allow("PV2-BLOCK", 120)) {
                                                val capM = try { CfhUtil.readCaption(q) } catch (_: Throwable) { null }
                                                val dM = CfhState.lookupDecl(pidW)
                                                Logger.evidence(
                                                    "PV2-BLOCK",
                                                    "★Presenter 数据挡下(DIRTY) ${m.name} id=$pidW " +
                                                        "判据=ai:declMap \"${dM?.take(16) ?: "-"}\" " +
                                                        "cap=\"${capM?.take(22) ?: "-"}\""
                                                )
                                            }
                                        }
                                        if (v != CfhDecide.WhitelistVerdict.WHITE || blockedByMap) {
                                            blocked = true
                                            // ★ 写黑名单（供下游 DLFEED 等查）—— bind 照常执行
                                            if (!pidW.isNullOrBlank()) {
                                                CfhState.noteDirty(pidW, CfhDecide.lastHitReason ?: "bind")
                                            }
                                            // ★ 滑动窗口限流（2026-09-26）
                                            if (io.github.angbang852.manjiao.util.RateLimiter.allow("PV2-BLOCK", 120)) {
                                                val cap = try { CfhUtil.readCaption(q) } catch (_: Throwable) { null }
                                                val pid = try { CfhProbe.readPhotoId(q) } catch (_: Throwable) { null }
                                                val rsn = try { CfhDecide.lastHitReason } catch (_: Throwable) { null }
                                                Logger.evidence(
                                                    "PV2-BLOCK",
                                                    "★Presenter 数据挡下(${v.name}) ${m.name} id=$pid " +
                                                        "判据=${rsn ?: "?"} cap=\"${cap?.take(22) ?: "-"}\""
                                                )
                                            }
                                            break@outer
                                        }
                                        // ★★★ v13.63 判白即入池（2026-09-30 自动入池）
                                        //   ## 用户质问
                                        //   「为什么还要手动在首页滑动养池？为什么不能自动入池？」
                                        //   —— 问得对：本 hook 判白之后只做了「放行」，
                                        //   **从来没把这条干净内容收进池**。
                                        //   ## 为什么这里是最好的入池点
                                        //   · 参数**直接携带 QPhoto**（PV2DATA 实证
                                        //     `bind[10] 直接QP 参数类=QPhoto`），
                                        //     不像 knhb 批次那样是包装对象，无需任何提取
                                        //   · 它正是**首页信息流的渲染入口**
                                        //     （实证链路 bind → updateData → doInject → onBind）
                                        //     ⇒ 这里放行的每一条都是首页真实上屏的内容
                                        //   · 已经判过白，noteClean 只做类型校验+去重+入池
                                        try {
                                            if (CfhState.noteClean(q) && CfhState.pv2PoolLog < 40) {
                                                CfhState.pv2PoolLog++
                                                Logger.evidence(
                                                    "PV2-POOL",
                                                    "★首页渲染入池 id=$pidW 池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                                                )
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        // ★★★ 2026-09-28 实测修正（第六版）：判脏 → **拦 bind**。
                        //
                        // ## 为什么恢复拦 bind（证据链闭环）
                        //
                        // 第五版（写黑名单 + bind 照常执行）⇒ 用户实测「首页有脏项」：
                        //   `PV2-BLOCK` 283 条判脏记录都在，但 bind 照常执行 ⇒
                        //   QPhoto 进注入容器 ⇒ `sni.o.e` 被赋值 ⇒ 卡片照常渲染脏项。
                        //   黑名单下游（DLFEED / QPHOTO-BLOCK）兜不住首页卡片 ——
                        //   首页卡片渲染前**没有**查黑名单的 hook（StaggerCardHook 已停用）。
                        //
                        // ## 为什么现在安全（IGUARD 兜底）
                        //
                        // 此前拦 bind 会崩：
                        //   `inject("FEED_ITEM_VIEW_PARAM")` 抛「未提供数据」⇒ FATAL。
                        // 现在 `InjectCrashGuard` 已装（inject 缺数据降级返回 null +
                        //   ml8.j.onBind NPE 吞 + a6i.g.doInject 判空断言吞）——
                        //   **拦 bind 的崩溃链已被 IGUARD 全覆盖**（交接 §2.3 实测 8 次接住）。
                        //
                        // ## 语义（与 §2.1 完全一致）
                        //
                        // 拦住 bind ⇒ QPhoto 进不了注入容器 ⇒ `sni.o.e` 为 null ⇒
                        // **卡片根本不创建**（不渲染、不留空壳、无脏项）。
                        if (blocked) {
                            return@intercept null
                        }
                        chain.proceed()
                        null
                    }
                n++
                Logger.evidence("PV2BIND-INST", "挂载 PresenterV2.${m.name}(Object[])")
            } catch (_: Throwable) {}
        }
        Logger.evidence("PV2BIND-INST", "Presenter 数据拦截已安装: 挂载 $n 个方法")
        CfhState.noteHookStatus("首页信息流拦截", n > 0, "PresenterV2.bind · $n 个方法")
    }
}
