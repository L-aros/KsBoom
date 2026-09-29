package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第三步：CfhClean 收口为「批量过滤核心」（2026-09 S3）。
// 列表/快照/响应字段清洗 + 统一删除器。真源深清洗→CfhWash，替换料→CfhSwap，
// 对象图定位→CfhProbe，供给恢复→CfhSupply。不持有状态——一律读 CfhState。
object CfhClean {
    /**
     * ★★★ 白名单过滤（2026-09-26 用户定稿）—— **判正常才放行**。
     *
     * 与 [filterListArgs]（黑名单：判脏才删）并列，但语义相反：
     *   · `filterListArgs` —— 保住干净的，删掉脏的（默认放行）
     *   · `filterWhitelist` —— **只放行明确干净的，其余一律挡下**（默认不放行）
     *
     * ## 为什么放在 L1（入参层 / 响应层）
     *
     * 这是**唯一能"不放行"的关口**。实测三条删除通路全部够不到真源：
     *   · 返回值快照 `H/V/F` —— 每次重建，删 391 次 99.6% 无效
     *   · 真源 `l.a/h.j` —— 包装类，提不到 QPhoto
     *   · 卡片容器 `Tangram/Kmp` —— 无清洗路径
     * ⇒ 在下游删是徒劳；只有在**进内存之前**挡，才真正有效。
     *
     * ## 三态处置
     *
     * | 判定 | 动作 |
     * |---|---|
     * | `WHITE`（明确干净） | **放行**（保留在列表） |
     * | `DIRTY`（判出脏） | **丢弃**（从列表移除） |
     * | `PENDING`（字段未齐） | **暂扣**，记录等待起点；超时后放行 |
     *
     * ## 与用户要求的对应
     *
     * > 「必须文案、标识、昵称全部干净才能放行，……还没到位识别不到的
     * >   也要先等着齐了判正常了才能进」
     *
     * ⇒ `PENDING` 即"先等着"；字段齐了三类都干净才 `WHITE`。
     *
     * ## 安全阀
     *
     * `PENDING` 超 [CfhDecide.WHITE_MAX_WAIT_MS]（3 秒）⇒ 放行。
     * **宁可偶尔漏一条，也不能造成「无更多作品」** —— 项目已两次栽在这上面
     * （`CfhWash.kt:2302-2320` 的二分排除记录）。
     *
     * ## 灰度开关
     *
     * 由 `CfhState.whiteListEnabled` 控制，**默认 false**（不改变既有行为）。
     * 白名单是**拦截模型反转**，风险高（可能造成内容供应中断），
     * 必须显式开启并观察后再考虑默认开。
     *
     * @return 实际移除的元素数（= DIRTY 数量；PENDING 不在此列）
     */
    fun filterWhitelist(args: List<Any?>): Int {
        // ★★ 调用者溯源探针（2026-09-26 追「getItems() 路径为什么没生效」）。
        //
        // ## 要回答的问题
        //
        // 用户报「青春磕颜家」（id=5259078674300789601，声明「疑似含AI生成内容」）
        // 上屏。它的 ENTRYTRACE 显示只穿过 `filterResult`（黑名单），
        // **没穿过 `filterWhitelist`（白名单）**，尽管它走的是 hookFeedResponse
        // → `Lourong.getItems`，而那里 `:112-115` 明明挂了白名单。
        //
        // ## 三种可能，本探针区分
        //
        //   A. `filterWhitelist` **根本没被调到**（那条路径没走到）
        //   B. 被调到了，但 `whiteListEnabled` / `liveTop` 早退
        //   C. 被调到了、也处理了，但判成 `WHITE`（放行）
        //
        // ## 为什么用栈回溯
        //
        // 该函数的调用点有 10+ 处，只记「被调用了」无法定位是哪条路径。
        // 取调用栈前几帧即可区分 `getItems` / `hookDataSource` / `VmList` 等。
        //
        // ## 成本
        //
        // 只在**首次**若干次做栈回溯（`whiteCallerLog < 40`），之后零开销。
        try {
            if (CfhState.whiteCallerLog < 40) {
                val hasList = args.any { it is MutableList<*> }
                // 只看「有列表」的调用 —— 空调用的调用者无意义
                if (hasList) {
                    CfhState.whiteCallerLog++
                    val who = try {
                        Thread.currentThread().stackTrace
                            .drop(2).take(8)
                            .joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }
                    } catch (_: Throwable) { "-" }
                    Logger.evidence(
                        "WHITECALLER",
                        "开关=${CfhState.whiteListEnabled} liveTop=${CfhState.liveTop} " +
                            "列表数=${args.count { it is MutableList<*> }} 链=$who"
                    )
                }
            }
        } catch (_: Throwable) {}
        // ★ 入口留痕（2026-09-26 修正）：确认白名单被调到的**是有内容的**调用。
        //   此前只知 WHITEENTRY 全是「列表数=0」—— 那是挂在入参上的空调用；
        //   定位后改挂**响应返回值**（ENTRYTRACE 实证 68/69 条内容的真实入口）。
        //   本探针只记「真的有非空 MutableList」的调用，用于确认挂载正确。
        try {
            val nonEmpty = args.filterIsInstance<MutableList<*>>().filter { it.isNotEmpty() }
            if (nonEmpty.isNotEmpty() && CfhState.whiteEntryCount < 40) {
                CfhState.whiteEntryCount++
                Logger.evidence(
                    "WHITEENTRY",
                    "白名单命中有效列表 个数=${nonEmpty.size} 各size=" +
                        nonEmpty.joinToString(",") { it.size.toString() }
                )
            }
        } catch (_: Throwable) {}
        if (!CfhState.whiteListEnabled) return 0
        if (CfhState.liveTop) return 0
        // ★ 诊断（2026-09-26）：本通道首次上线时 `WHITE=0`，需区分
        //   「没被调用」/「调了全判 WHITE」/「调了全判 PENDING」。
        //   前 30 次落盘三态计数。
        if (CfhState.whiteDiagCount < 30) {
            CfhState.whiteDiagCount++
        }
        var removed = 0
        var seenWhite = 0
        var seenPending = 0
        var seenDirty = 0
        for (a in args) {
            if (a !is MutableList<*>) continue
            try {
                @Suppress("UNCHECKED_CAST")
                val la = a as MutableList<Any?>
                val n = la.size
                if (n == 0) continue
                var i = 0
                while (i < la.size) {
                    val el = la.getOrNull(i)
                    if (el == null) { i++; continue }
                    // 元素可能是包装类 ⇒ 提取 QPhoto 再判
                    val q = try { CfhProbe.findQpInObject(el) } catch (_: Throwable) { null } ?: el
                    when (CfhDecide.judgeWhitelist(q)) {
                        CfhDecide.WhitelistVerdict.WHITE -> {
                            seenWhite++
                            // ★ 放行留痕（2026-09-26）：白名单「判干净 ⇒ 放行」的**具体对象**。
                            //   此前只记「挡下 N 条」，无法回答「为什么这一条没被挡」——
                            //   用户报「Vlog.嘟嘟第一次坐飞机」反复漏拦，正是缺这个对照。
                            if (CfhState.whitePassLog < 60) {
                                CfhState.whitePassLog++
                                val pid = try { CfhProbe.readPhotoId(q) } catch (_: Throwable) { null }
                                val cap = try { CfhUtil.readCaption(q) } catch (_: Throwable) { null }
                                Logger.evidence(
                                    "WHITEPASS",
                                    "白名单放行 id=$pid cap=\"${cap?.take(22) ?: "-"}\""
                                )
                            }
                            i++
                        }
                        CfhDecide.WhitelistVerdict.DIRTY -> {
                            seenDirty++
                            // ★ 滑动窗口限流（2026-09-26 用户报「拦截记录不全」）
                            //   原为「整进程 60 次」⇒ 打满后静默，用户看不到后续拦截。
                            if (io.github.angbang852.manjiao.util.RateLimiter.allow("WHITEBLOCK", 120)) {
                                val pid = try { CfhProbe.readPhotoId(q) } catch (_: Throwable) { null }
                                // ★★ 必须记「判据」与「昵称/文案」（2026-09-26 用户要求验证无误拦）。
                                //   只记 id 无法回答「这条是不是真脏」——
                                //   用户明确要求「确认没有误拦截」，判据是唯一的核验依据。
                                val reason = try { CfhDecide.lastHitReason } catch (_: Throwable) { null }
                                val un = try {
                                    CfhUtil.readUserName(q, Reflect.readAny(q, "mEntity") ?: q)
                                } catch (_: Throwable) { "" }
                                val cap = try { CfhUtil.readCaption(q) } catch (_: Throwable) { null }
                                Logger.evidence(
                                    "WHITEBLOCK",
                                    "白名单挡下(DIRTY) id=$pid 判据=${reason ?: "?"} " +
                                        "昵称=\"${un.take(14)}\" cap=\"${cap?.take(20) ?: "-"}\""
                                )
                            }
                            try { la.removeAt(i); removed++ } catch (_: Throwable) { i++ }
                        }
                        CfhDecide.WhitelistVerdict.PENDING -> {
                            // ★★★ 严格白名单（2026-09-26 用户定稿方案 A）。
                            //
                            // ## 用户原话
                            //
                            // > 「文案、标识、昵称还没到位识别不到的也要先等着齐了
                            // >   判正常了才能进」
                            // > （选 A：「严格白名单：PENDING 时不放行也不超时，一直等」）
                            //
                            // ## 为什么取消超时放行（实测漏洞）
                            //
                            // 原实现是「PENDING ⇒ 暂扣，3 秒超时后**放行**」。
                            // 实测「咪咪 / #回响计划」正是从这个后门漏的：
                            // ```
                            // [WHITEDIAG] 放行(WHITE)=0 判脏(DIRTY)=0 待判(PENDING)=1
                            // ```
                            // 它带 `作者声明：含AI生成内容`，却因判成 PENDING
                            // ⇒ 3 秒后被放行 ⇒ 上屏（后续 `PDEL-MISS` 也删不掉）。
                            //
                            // ## 现在的语义
                            //
                            // `PENDING` ⇒ **从列表移除，且不放行**：
                            //   · 快手若继续喂，下一轮字段可能已齐 ⇒ 那时才可能判 WHITE 放行
                            //   · 字段永远不齐 ⇒ 该条永远不进 ⇒ **按用户要求（宁可挡）**
                            //
                            // ## 保底与可观测（取代超时放行）
                            //
                            // 不再静默放行，但**记录**：
                            //   · 首次 PENDING 时记 `started`（用于算等待时长）
                            //   · 每次 PENDING 记 `WHITEPEND` 证据（限次）
                            //   · 等待超 `WHITE_PEND_ALERT_MS`（10 秒）仍 PENDING
                            //     ⇒ 记 `WHITESTUCK` 告警（**仍然不放行**，只是让人知道卡住了）
                            //
                            // ## 风险与后果（已知并接受）
                            //
                            // 严格模式下**可能造成内容变少**，最坏是「无更多作品」。
                            // 这是用户在知情下选的（方案 A）——
                            // 项目历史上两次「无更多作品」都是"拦太多"，
                            // 但用户明确要求「宁可挡」。
                            // 若真出现内容枯竭，`WHITESTUCK` 证据会指出是哪条卡住。
                            seenPending++
                            val key = try { CfhProbe.readPhotoId(q) } catch (_: Throwable) { null }
                            val nowMs = System.currentTimeMillis()
                            val started = if (key.isNullOrBlank()) nowMs else {
                                synchronized(CfhState.whitePendingSince) {
                                    val s = CfhState.whitePendingSince[key]
                                    if (s == null) {
                                        if (CfhState.whitePendingSince.size >= 512) CfhState.whitePendingSince.clear()
                                        CfhState.whitePendingSince[key] = nowMs
                                        nowMs
                                    } else s
                                }
                            }
                            // 记录等待（首次落盘）
                            if (key != null && CfhState.whitePendLog < 60 && started == nowMs) {
                                CfhState.whitePendLog++
                                val capP = try { CfhUtil.readCaption(q) } catch (_: Throwable) { null }
                                Logger.evidence(
                                    "WHITEPEND",
                                    "字段未齐→扣下等待 id=$key cap=\"${capP?.take(18) ?: "-"}\""
                                )
                            }
                            // 卡住告警（10 秒仍 PENDING）—— 仍然不放行，只提示
                            if (nowMs - started >= CfhDecide.WHITE_PEND_ALERT_MS &&
                                CfhState.whiteStuckLog < 40
                            ) {
                                CfhState.whiteStuckLog++
                                Logger.evidence(
                                    "WHITESTUCK",
                                    "★字段长期未齐（${(nowMs - started) / 1000}秒）仍不放行 id=$key"
                                )
                            }
                            // ★ 严格：无条件从列表移除，不等超时、不放行
                            try { la.removeAt(i); removed++ } catch (_: Throwable) { i++ }
                        }
                    }
                }
                if (removed > 0 && CfhState.whiteLogCount < 60) {
                    CfhState.whiteLogCount++
                    Logger.evidence(
                        "WHITE",
                        "白名单过滤 入参size=$n 挡下=$removed 放行=${la.size}"
                    )
                }
            } catch (_: Throwable) {}
        }
        // ★ 三态计数落盘（诊断，前 30 次）—— 区分「没调用」/「全 WHITE」/「全 PENDING」
        if (CfhState.whiteDiagCount <= 30 &&
            (seenWhite + seenPending + seenDirty) > 0
        ) {
            Logger.evidence(
                "WHITEDIAG",
                "三态统计 放行(WHITE)=$seenWhite 判脏(DIRTY)=$seenDirty 待判(PENDING)=$seenPending"
            )
            CfhState.whiteDiagCount++
        }
        return removed
    }


    // ============ 黑名单拦截（filterListArgs / filterResult / filterResponseFields）============
    //
    // 【已全部移除】(2026-09-26 用户定稿「只留白名单，黑名单清理掉」)
    //
    // 移除的三个函数：
    //   · filterListArgs(args)        —— 入参列表清洗（判脏才删）
    //   · filterResult(result)        —— 返回值列表清洗（判脏才删）
    //   · filterResponseFields(obj)   —— 响应对象字段清洗
    //
    // 移除理由：
    //   ① 白名单（filterWhitelist）已覆盖同一批挂载点，且判据更严（只放行明确干净的）
    //   ② 实测黑名单是「判脏才删」，而删的是副本（`同一对象=true` 0 次）——
    //      反复删同一内容造成死循环（实测同一条被删 120 次、剩量恒为 1）
    //   ③ 白名单在源头挡下后，黑名单已无脏可删（纯冗余）
    //
    // 保留的两处拦截（用户指定）：
    //   · 网络层：`CfhTtpParse`（Gson 解析期，QPhoto 创建那一刻）
    //   · 网络层：`hookFeedResponse` 返回值上的 `filterWhitelist`
    //   · 数据层：`hookDataSource` 返回值上的 `filterWhitelist`
    //
    // 备份：`.backup-blacklist-20260926/CfhClean.kt.bak`
}
