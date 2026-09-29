package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.data.VideoInfo
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第二步：判定职责（2026-09 S3）。
// shouldFilter*/decide*/feedRules/quickOfficialDirty/内容签名缓存/命中审计 全在此。
// 不持有状态——可变状态一律读 CfhState 单例，纯读取器归 CfhUtil。
object CfhDecide {
    val AD_TEXTS = arrayOf("广告", "赞助", "sponsored", "推广")

    /**
     * 最近一次命中的判据串（如 `ai:disclaimer "疑似含AI生成内容"`）。
     *
     * ★ 用途：删除证据落盘时带上判据，才能区分「正常拦截」与「误判」——
     *   只记「删了 6 条」无法判断对错。
     */
    @Volatile var lastHitReason: String? = null

    val AI_META_REGEX = Regex("\\bAI\\b|AI[生成制作绘画]|:AI|AI：")

    val DRAMA_TEXTS = arrayOf("看全集", "文娱榜", "选集", "上集", "下集", "全剧", "剧集", "正片")

    val MOVIE_HINT = arrayOf("电影", "电视剧", "影视", "解说", "剪辑", "全集", "第", "集", "剧")

    // ★ 性能修复（审阅 2026-09 · S1）：集数标记正则原先在 feedRules 函数体内
    // 每次现构造——K_FLT_DRAMA 默认 true，该行每帧都在编译 Pattern。
    // 与同文件 AI_META_REGEX 一致改为预编译常量，判定语义完全不变。
    private val DRAMA_EPISODE_REGEX = Regex("^\\s*第\\s*[0-9一二三四五六七八九十百]+\\s*[集话]")

    fun shouldFilterFeed(qp: Any): Boolean {
        if (CfhState.liveTop) return false
        // ★★★ 入口追踪（2026-09-26 用户要求「定位入口」）。
        //
        // ## 要回答的问题
        //
        // 实测矛盾：`WHITE`（白名单挡住）只有 30 次，而 `VISDEL`（删除动作）有 438 次，
        // 且被追的脏内容**全程无 WHITE 记录** —— 说明**白名单挂的 10 个点不是主入口**。
        //
        // 本探针对**每一次进入判定**记录：
        //   photoId（首见时）+ 调用者（栈回溯识别）+ 文案
        //
        // ⇒ 拿已知漏拦的 id 比对「它第一次出现在哪个调用者下」，
        //   即可确定**真正的入口**，不必再猜挂载点。
        //
        // ## 为什么记「首次」
        //
        // 同一条内容会被反复判定（周期任务/多路径），只记**首次**才能定位**入口**；
        // 后续都是重复发现，无助于定位。
        //
        // ## 成本
        //
        // · 仅首见时才做栈回溯（有成本），已见过的直接走缓存判断（O(1)）
        // · 每进程限次落盘（`entryTraceCount < 120`）
        try {
            if (CfhState.entryTraceCount < 120) {
                val pidT = try { CfhProbe.readPhotoId(qp) } catch (_: Throwable) { null }
                if (!pidT.isNullOrBlank()) {
                    val first = synchronized(CfhState.entryTraceSeen) {
                        CfhState.entryTraceSeen.add(pidT)
                    }
                    if (first) {
                        CfhState.entryTraceCount++
                        // 栈回溯识别调用者（取前若干个非模块内部帧）
                        val who = try {
                            Thread.currentThread().stackTrace
                                .drop(2)
                                .take(10)
                                .joinToString(" > ") { it.className.substringAfterLast('.') + "." + it.methodName }
                        } catch (_: Throwable) { "-" }
                        val capT = try { CfhUtil.readCaption(qp) } catch (_: Throwable) { null }
                        Logger.evidence(
                            "ENTRYTRACE",
                            "首见 id=$pidT cap=\"${capT?.take(18) ?: "-"}\" 调用链=$who"
                        )
                    }
                }
            }
        } catch (_: Throwable) {}
        // ★★ 进入判定即留痕（2026-09-24，查「直播漏拦」）。
        //
        //   用户实测：当前页是直播间（屏幕上有「点击进入直播间」「回头客1309人」
        //   文案「儿童秋款休闲卫裤」），而日志里**该条零记录** ——
        //   连 `nonVF`/`decideFeedRaw` 都没进。
        //
        //   故在此（**所有早退之前**）留一条痕迹：只要能走到 shouldFilterFeed，
        //   就必然留下「谁进来了、判成什么」。若连这条都没有，
        //   说明该条**根本不经过本函数** —— 那是更上游的缺口。
        //
        //   只读、限次落盘（不受 logcat 环形缓冲影响）。
        try {
            if (CfhState.enterProbeCount < 200) {
                val cap0 = try { CfhUtil.readCaption(qp) } catch (_: Throwable) { null }
                // 只记录「可能相关」的（有文案、或含 Live 语义），避免全量刷屏
                val ent = try { Reflect.readAny(qp, "mEntity") } catch (_: Throwable) { null } ?: qp
                val entCls0 = ent.javaClass.name
                val interesting = entCls0.contains("Live", true) ||
                    (!cap0.isNullOrBlank() && (cap0.contains("直播") || cap0.contains("卫裤") ||
                        cap0.contains("儿童") || cap0.contains("进入直播")))
                if (interesting) {
                    CfhState.enterProbeCount++
                    Logger.evidence(
                        "ENTER",
                        "ent=${entCls0} qp=${qp.javaClass.simpleName} 文案=\"${cap0?.take(20) ?: "-"}\""
                    )
                }
            }
        } catch (_: Throwable) {}
        // ★★ 定点追踪（2026-09-24）：用户报「删了但屏幕上还在」的条目。
        //
        //   实测样本「你的芒果上有这种小黑点吗？」：
        //     10:37:44  fltHit → feed filtered del=1   （判定+删除都执行）
        //     10:37:53  DL vis + VISJUDGE              （最后一帧）
        //     之后 DL vis 完全停止 —— 但**屏幕仍是它**
        //
        //   要查的是：这条是否**还在数据链路里**（反复经过判定 = 删不掉），
        //   还是**已离开所有列表**（= 屏幕上的残留视图没人清理）。
        //   两者的修法完全不同。
        try {
            // ★ 性能修复（2026-09-30）：配额判断提到**最外层**，与 :58 / :94 两块同类护栏写法对齐。
            //   原写法把 readCaption（多次反射读字段）放在 `mangoProbeCount < 60` 之前 ⇒
            //   探针配额早就打满，但每次 shouldFilterFeed 仍然白付一次反射开销
            //   （shouldFilterFeed 是全模块判定的公共入口，这属于纯乘数浪费）。
            //   触发次数**一字不变**：仍是「计数<60 且文案含芒果」时才 +1，只是不再白付代价。
            if (CfhState.mangoProbeCount < 60) {
                val capNow = try { CfhUtil.readCaption(qp) } catch (_: Throwable) { null }
                if (capNow != null && capNow.contains("芒果")) {
                    CfhState.mangoProbeCount++
                    val pid = try { CfhProbe.readPhotoId(qp) } catch (_: Throwable) { null }
                    val dirty = try { CfhDecide.shouldFilterContent(qp) } catch (_: Throwable) { false }
                    Logger.evidence(
                        "MANGO",
                        "★经过判定 photoId=$pid 判脏=$dirty 文案=\"${capNow.take(18)}\" " +
                            "调用者=${Thread.currentThread().stackTrace.drop(2).take(3).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }}"
                    )
                }
            }
        } catch (_: Throwable) {}
        // ★★ 短剧卡片追踪（2026-09-24，查「漏的怎么来的」）。
        //
        //   用户实测缺失样本「一针绣清欢」的屏幕结构：
        //     @短剧一针绣清欢 / 免费《热血逆袭》播放量187万 /
        //     精彩片段全43集 / [看全集] / 作者声明：含AI生成内容
        //   —— 这是**短剧卡片**形态（带「看全集」按钮、剧集数），
        //   与普通视频条目结构不同。
        //
        //   本探针专门抓「短剧/剧集」类条目：把它的实体类名、
        //   承载字段、是否经过判定全部记下 —— 用于回答
        //   「它到底走哪条路径进来的」。
        try {
            // ★ 性能修复（2026-09-30）：配额判断提到**最外层**，与 :58 / :94 两块同类护栏写法对齐。
            //   原写法把 readCaption（多次反射读字段）放在 `dramaProbeCount < 80` 之前 ⇒
            //   探针配额早就打满，但每次 shouldFilterFeed 仍然白付一次反射开销。
            //   触发次数**一字不变**：仍是「计数<80 且文案命中短剧关键词」时才 +1，
            //   只是不再白付代价。
            if (CfhState.dramaProbeCount < 80) {
                val capS = try { CfhUtil.readCaption(qp) } catch (_: Throwable) { null }
                if (capS != null &&
                    (capS.contains("短剧") || capS.contains("看全集") || capS.contains("集") ||
                        capS.contains("一针绣") || capS.contains("影视"))
                ) {
                    CfhState.dramaProbeCount++
                    val entS = try { Reflect.readAny(qp, "mEntity") } catch (_: Throwable) { null } ?: qp
                    val dirtyS = try { shouldFilterContent(qp) } catch (_: Throwable) { false }
                    // 关键：这条是否带剧集专属字段（判断它是不是「卡片」而非普通视频）
                    val hasDrama = try {
                        Reflect.readAny(entS, "mKwAppNativeDrama") != null ||
                            Reflect.readAny(entS, "mNovelDrama") != null ||
                            Reflect.readAny(entS, "mLongToShortDrama") != null ||
                            Reflect.readAny(entS, "mAdNovelVideoMeta") != null ||
                            Reflect.readAny(entS, "mSerialInfo") != null ||
                            Reflect.readAny(entS, "mDramaInfo") != null
                    } catch (_: Throwable) { false }
                    val pidS = try { CfhProbe.readPhotoId(qp) } catch (_: Throwable) { null }
                    Logger.evidence(
                        "DRAMA",
                        "短剧类条目 ent=${entS.javaClass.name} photoId=$pidS 判脏=$dirtyS " +
                            "带剧集字段=$hasDrama 文案=\"${capS.take(20)}\""
                    )
                }
            }
        } catch (_: Throwable) {}
        // ★ 精选页「当前条」跟踪 —— 登记环节（2026-09-23）。
        //
        //   ★★ 位置修正记录：最初把登记放在本函数**末尾**（任何早退都绕过它），
        //      真机实测 `FEATTRACK` 零输出 —— 因为精选页那条路径上
        //      `anyFilterOn()` 等早退会先把函数返回掉，登记根本不执行。
        //      现提到**任何早退之前**：登记是纯粹的「记账」，与是否要过滤无关，
        //      放在最前面才能保证「每见到一条就记一条」。
        //
        //   为什么登记点选这里：`shouldFilterFeed` 是全模块 45 处判定调用的
        //   唯一汇聚点（Clean/ViewHook/FeedHook/Purge/Swap/Diag 全走它），
        //   放这里等于覆盖了所有「模块能见到条目」的时机。
        try { FeaturedTrack.register(qp) } catch (_: Throwable) {}
        if (!anyFilterOn()) return false
        // ★ 解包 WeakReference：精选 tab 列表元素是 WeakReference 包装（实证 r15d #9 elemCls=WeakReference entCls=null），
        // WeakReference 没 mEntity 字段 → ent=qp 自身 → entCls=WeakReference 不含 Live → 漏判。先 .get() 解包再判定
        val realQp = if (qp.javaClass.name == "java.lang.ref.WeakReference") {
            val inner = try { (qp as java.lang.ref.WeakReference<*>).get() } catch (_: Throwable) { null }
            if (inner == null) return false
            if (CfhState.weakUnwrapDiag < 30) { CfhState.weakUnwrapDiag++; Logger.d("weakUnwrap: ${inner.javaClass.name}") }
            // ★ 精选页结构探测（2026-09-23）：用户报「精选页一条条刷」时出现
            //   「文案不变、画面在变」。已查明精选页 Fragment
            //   （HomeFeaturedMilanoContainerFragment）**完全没有被 hook**
            //   （全项目无任何处理代码），且列表元素是 WeakReference 包装
            //   —— 结构与详情页不同，不能照搬详情页那套。
            //   这里借「解包后已拿到真实对象」的位置顺带 dump 结构，
            //   摸清「当前条」的字段路径后再写跟踪。只读，不改变判定。
            try { FeaturedProbe.dumpElement(inner) } catch (_: Throwable) {}
            // 解包后若非 QPhoto（如 HomeFeaturedMilanoContainerFragment 容器），从内部找 QPhoto 再判定
            val qpClass = CfhState.qpClassRef
            if (qpClass != null && !qpClass.isAssignableFrom(inner.javaClass)) {
                CfhProbe.findQpInObject(inner) ?: inner
            } else inner
        } else qp
        // ★ 精选页「当前条」跟踪 —— 解包后再登记一次（2026-09-23）。
        //   上面那次登记拿到的可能是 WeakReference 本身或容器对象，
        //   `readPhotoId` 对它取不到 photoId（登记会静默失败）。
        //   这里在**已解包出真实对象**之后再登记一次 —— 两条都登记、
        //   由 `register` 自己按「能否读到 photoId」筛掉无效的那条。
        //   代价：多一次 `readPhotoId`（自带 IdentityHashMap 缓存，命中后近零成本）。
        try { FeaturedTrack.register(realQp) } catch (_: Throwable) {}
        // ★★★ 2026-09-29 v13.20 移除黑名单短路（用户定稿「网络层白名单拦截，还要什么黑名单？」）：
        //   黑名单 dirtyPhotoMap 把「一次误判」变成「30 分钟永久删除」——
        //   50388 实测：粤菜黄师傅(名菜教程)/雪下紫禁城(可乐测评)等正常内容
        //   被误判一次后每批 blacklist 短路删除 ⇒ 精选页每批拦 8-9 条 ⇒ 列表空 ⇒ 「无网络」。
        //   网络层 GSCOLL/TTPPARSE 本来就是**每批逐条新鲜判定**（白名单制），
        //   DIRTY/PENDING 当场 remove、WHITE 留下 —— 误判的下批还能重新判 WHITE。
        //   ⇒ 黑名单的记忆是纯负资产，判定主路径不再查它。
        //   noteDirty 写入保留但无读取方（无副作用，30 分钟自然过期清理）。
        //   if (isBlacklisted(realQp)) return true
        return decideBySig(CfhState.feedSigCache, realQp) { cachedDecide(CfhState.feedFilterCache, realQp) { decideFeedRaw(realQp) } }
    }

    /**
     * 脏项黑名单快查（判脏即入，见 [hit]）。
     * 单独做一个入口而不是塞进 shouldFilterFeed：后者有签名缓存，黑名单是
     * 「已判定的事实」，应当无条件短路，不能受缓存/开关组合变化影响。
     */
    fun isBlacklisted(qp: Any?): Boolean {
        if (qp == null) return false
        if (CfhState.dirtyPhotoMap.isEmpty()) return false
        val id = try { CfhProbe.readPhotoId(qp) } catch (_: Throwable) { null } ?: return false
        if (id.isBlank()) return false
        val entry = CfhState.dirtyPhotoMap[id] ?: return false
        // ① 过期检查：30 分钟前登记的记录失效（防一次误拦永久化）
        if (System.currentTimeMillis() - entry.second > 30 * 60_000L) {
            CfhState.dirtyPhotoMap.remove(id)
            return false
        }
        // ② 开关有效性：登记时的判据若属于已关闭的开关 → 失效
        //    （用户关掉 AI 拦截后，按 AI 判据登记的黑名单不应继续拦）
        val on = reasonStillEnabled(entry.first)
        if (on) {
            // ★★★ 短路留痕（2026-09-28「精选页刷不出」修复）：
            //   命中黑名单时必须写 lastHitReason，否则证据全是判据=?，
            //   无法核验这条到底是真脏还是误删（BLOCK 判据=? 29/60 实证）。
            //   记 `blacklist:<原判据>`，证据可回溯到当初判脏的判据。
            lastHitReason = "blacklist:${entry.first}"
        }
        return on
    }

    /** 判据前缀 → 开关映射：关掉的开关对应的黑名单记录自动失效 */
    private fun reasonStillEnabled(reason: String): Boolean {
        return when {
            reason.startsWith("ai:") -> Prefs.bool(Prefs.K_FLT_AI, false)
            reason.startsWith("live:") -> Prefs.bool(Prefs.K_FLT_LIVE, false)
            // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：
            //   「从未设置过」的键不再视为仍开启 ⇒ 与其对应判据的黑名单条目在键未设置时即失效，
            //   与 anyFilterOn()（CfhDecide.kt:1220-1224 全 false）及 UI 行定义口径统一。
            reason.startsWith("advideo:") || reason.startsWith("ads:") -> Prefs.bool(Prefs.K_FLT_ADS, false)
            reason.startsWith("drama:") || reason.startsWith("image:") -> Prefs.bool(Prefs.K_FLT_DRAMA, false)
            reason.startsWith("ec:") -> Prefs.bool(Prefs.K_FLT_EC, false)
            else -> true // 未知判据保守放行拦截有效性
        }
    }

    fun decideFeedRaw(qp: Any): Boolean {
        // ★ 裸实体兜底：同 quickOfficialDirty，无 mEntity 时判 qp 自身类名
        val ent = Reflect.readAny(qp, "mEntity") ?: qp
        val entCls = ent.javaClass.name
        if (!entCls.contains("feed.VideoFeed")) {
            if (CfhState.nonVfDiagCount < 20 || CfhState.nonVfDiagCount % 100 == 0) {
                CfhState.nonVfDiagCount++
                Logger.d("nonVF ent: $entCls")
            } else CfhState.nonVfDiagCount++
            // ★ 缺口收口（2026-09-25）：此前本分支在直播类名/直播入口文案之外
            //   一律 return false —— [feedRules]（AI/短剧/广告）对非 VideoFeed
            //   实体完全不执行（原「保留缺口记录」注释）。规则统一收敛到
            //   [nonVfRules]（与 decideContentRaw 共用），不再无条件放行。
            return nonVfRules(qp, ent, entCls)
        }
        val cm = Reflect.readAny(ent, "mCommonMeta")
        val pm = Reflect.readAny(ent, "mPhotoMeta")
        val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
        // ★★ 按文案关键词定向抓取完整档案（2026-09-24）。
        //
        //   背景：用户报「有 AI 角标却漏拦」，给出一条具体样本
        //   （cap=遭不住了。别限了好嘛 #澪溪xi #澪冬栀，作者 澪冬栀，
        //    左下角「疑似含AI生成内容」）。该条此前只被 `DL vis` 记录，
        //   **从未进入本函数**，因此没有任何 AI 判定痕迹。
        //
        //   与其猜「它是什么类型 / 走哪条路径」，不如：**只要它再经过一次判定，
        //   就把完整结构打出来**。这里按用户给的文案关键词匹配，命中即 dump：
        //   实体类名、声明三源、photoId —— 一次拿到全部事实。
        //
        //   只读；命中才打，不刷屏。
        try {
            if (cap.contains("遭不住", true) || cap.contains("澪冬", true) || cap.contains("澪溪", true)) {
                val pid = CfhProbe.readPhotoId(qp)
                val dis = CfhUtil.readDisclaimer(qp, ent, pm)
                Logger.always(
                    "XTARGET 命中样本! ent=${ent.javaClass.name} photoId=$pid " +
                        "dis=\"${dis ?: "<null>"}\" 判AI=${CfhUtil.isAiDisclaimerText(dis)} cap=\"${cap.take(30)}\""
                )
            }
        } catch (_: Throwable) {}
        // ★★★ 视频作者直播浮窗：普通 VideoFeed 里驱动 "直播中/进入直播间/直播小窗" 的字段
        // （欠编译版本 mCurrentLivingState 仅是其一；这里全量找 live 相关字段名）
        if (CfhState.liveFieldDiag < 6) {
            CfhState.liveFieldDiag++
            val hits = mutableListOf<String>()
            fun scanForLive(obj: Any, prefix: String) {
                var c: Class<*>? = obj.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cc0: Class<*>? = c
                    for (f in (cc0 ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        val fnl = f.name.lowercase()
                        if (fnl.contains("live") || fnl.contains("living") || fnl.contains("streaming") || fnl.contains("livingwindow")) {
                            try {
                                f.isAccessible = true
                                val v = f.get(obj)
                                hits.add("$prefix[${cc0?.simpleName}]${f.name}=${v?.javaClass?.simpleName ?: "null"}")
                            } catch (_: Throwable) {}
                        }
                    }
                    c = cc0?.superclass; lvl++
                }
            }
            scanForLive(ent, "ent ")
            if (pm != null) scanForLive(pm, "pm ")
            if (cm != null) scanForLive(cm, "cm ")
            scanForLive(qp, "qp ")
            if (hits.isNotEmpty()) Logger.d("LIVEFIELD $hits cap=${cap.take(16)}")
        }
        // ★ 直播判据排查（2026-09-23）：用户报「键尘团播」偶发漏判，
        //   且**无法按需复现**（"现在没刷出来了，偶尔漏的不见得什么时候能复现"）。
        //
        //   这类偶发问题不能靠"用户复现→我去抓"，必须让模块**自己在可疑条目上留证据**。
        //   因此这里**不做 diag 门控**（原先依赖 diag_debug，而 release 包上改不动配置，
        //   等于探针永远不输出）：
        //   - 只在**实体确实带直播迹象**时输出（`ent` 类名含 Live 或有 LivePlaybackMeta）
        //   - 每进程上限 [liveValDiag] 条，避免刷屏
        //   - 用 once 级日志，不受 quiet 门控
        //
        //   这样下次漏判发生时，日志里自然就有那条的**字段实际值** ——
        //   不必等用户复现，也不必开开关。
        if (CfhState.liveValDiag < 15) {
            val entName = ent.javaClass.simpleName
            val hasLiveField = try {
                Reflect.readAny(ent, "mLivePlaybackMeta") != null
            } catch (_: Throwable) { false }
            if (hasLiveField || ent.javaClass.name.contains("Live", true)) {
                CfhState.liveValDiag++
                try { CfhProbe.dumpLiveValues(qp, ent) } catch (_: Throwable) {}
            }
        }
        CfhState.feedDiagCount++
        // ★ 性能：整段诊断反射（20+ 次）只为拼日志，quiet 时全跳过；但
        // lastViewQp 换条更新是 AIFULL 兜底锚点，quiet 时也必须维护
        val un = CfhUtil.readUserName(qp, ent)
        val like = pm?.let { Reflect.readLong(it, "mLikeCount") } ?: -1L
        val sig = cap.take(22) + "|" + like + "|" + un
        if (sig != CfhState.lastViewSig) { CfhState.lastViewSig = sig; CfhState.lastViewQp = qp }
        // ★ 诊断限流（S2 2026-09）：重反射诊断块只随独立 diag 开关开——与 quiet 解耦，
        // 排障开诊断不再拖垮性能；每秒最多 1 次全量诊断
        if (Logger.diag && (CfhState.feedDiagCount <= 10 || (sig != CfhState.lastFeedDiagSig && System.currentTimeMillis() - CfhState.lastFeedDiagAt > 1000))) {
            CfhState.lastFeedDiagAt = System.currentTimeMillis()
            CfhState.lastFeedDiagSig = sig
            val cmt = pm?.let { Reflect.readLong(it, "mCommentCount") } ?: -1L
            val liveMeta = Reflect.readAny(ent, "mLivePlaybackMeta")
            val liveSid = liveMeta?.let { Reflect.readAny(it, "mLiveStreamId") }
            val drama1 = Reflect.readAny(ent, "mKwAppNativeDrama")
            val drama2 = Reflect.readAny(ent, "mNovelDrama")
            val drama3 = Reflect.readAny(ent, "mLongToShortDrama")
            val tube = Reflect.readAny(ent, "mTubeModel")
            val tubeInfo = tube?.let { Reflect.readAny(it, "mTubeInfo") }
            val tubeTag = tube?.let { Reflect.readBool(it, "mHasTubeTag") }
            val vm = Reflect.readAny(ent, "mVideoModel")
            val longVid = vm?.let { Reflect.readBool(it, "mIsLongVideo") }
            val pmCls = pm?.javaClass?.simpleName ?: "null"
            // 抖音式类型判别：抓快手的 awemeType 等价 int 字段。?
            val entType = CfhUtil.safeNextLong(ent, "mFeedType")
            val entDisp = CfhUtil.safeNextLong(ent, "mDisplayType")
            val qpType = CfhUtil.safeNextLong(qp, "mType")
            val cmType = cm?.let { Reflect.readLong(it, "mType") } ?: -1L
            val pmType = pm?.let { Reflect.readLong(it, "mType") } ?: -1L
            val cor = Reflect.readAny(ent, "mCoronaInfo")
            val cardStyle = cor?.let { Reflect.readInt(it, "mCardStyleType") } ?: -1
            val cardPlay = cor?.let { Reflect.readInt(it, "mCardPlayType") } ?: -1
            // 审计：全特征（含作者名/类名/mAd），换条才打，便于抓漏网广告
            val mAdAny = Reflect.readAny(ent, "mAd")
            if (sig != CfhState.lastViewSig || CfhState.lastViewQp === qp) {
                Logger.d("VIEWDIAG like=$like cmt=$cmt user=\"$un\" ent=${ent.javaClass.simpleName} ad=${mAdAny != null} aiFields=" + CfhUtil.dumpAiFields(qp, ent, cm) + " liveSid=${liveSid != null} d1=${drama1 != null} capLen=${cap.length} cap=\"${cap.take(80)}\"")
            }
            Logger.d("feed diag #${CfhState.feedDiagCount} like=$like liveSid=${liveSid != null} d1=${drama1 != null} d2=${drama2 != null} d3=${drama3 != null} tubeInfo=${tubeInfo != null} tubeTag=$tubeTag longVid=$longVid pmCls=$pmCls entType=$entType entDisp=$entDisp qpType=$qpType cmType=$cmType pmType=$pmType cardStyle=$cardStyle cardPlay=$cardPlay cap=${cap.take(18)}")
            if (CfhState.entFullProbeCount < 2) { CfhState.entFullProbeCount++; Logger.always("ENTPROBE hit: quiet=${Logger.quiet} longVid=$longVid d1=${drama1 != null} tube=${tube != null}") }
            // ★ ENTFULL 一次性诊断：长视频且 drama 三字段全空的样本（如电视剧剪辑
            // 「从海底出击」）。实证 tubeTag=false 打印值证明 mTubeModel 非空——
            // dump ent + tube 两层全字段找真正的 TV/剧集结构化标记来源
            if (CfhState.entFullDumpCount < 3 && longVid == true && drama1 == null && drama2 == null && drama3 == null) {
                CfhState.entFullDumpCount++
                fun dumpAll(o: Any, prefix: String, sb: StringBuilder) {
                    var cf: Class<*>? = o.javaClass
                    var lf = 0
                    while (cf != null && cf != Any::class.java && lf < 4) {
                        for (ff in cf!!.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(ff.modifiers)) continue
                            try {
                                ff.isAccessible = true
                                val fv = ff.get(o)
                                val fvStr = when {
                                    fv == null -> "null"
                                    fv is List<*> -> if (fv.isEmpty()) "[]" else "list[${fv.size}]"
                                    fv is String -> if ((fv as String).length > 24) (fv as String).take(24) + "…" else fv
                                    else -> fv.toString().take(24)
                                }
                                sb.append(" $prefix${ff.name}=$fvStr")
                            } catch (_: Throwable) {}
                        }
                        val sup = cf!!.superclass; cf = sup; lf++
                    }
                }
                val sb = StringBuilder("ENTFULL #${CfhState.entFullDumpCount} like=$like cap=\"${cap.take(12)}\" ${ent.javaClass.name}:")
                dumpAll(ent, "", sb)
                if (tube != null) { sb.append(" ||TUBE ${tube.javaClass.name}:"); dumpAll(tube, "t.", sb) }
                try { Reflect.readAny(ent, "mStandardSerialMeta")?.let { s -> sb.append(" ||SERIAL ${s.javaClass.name}:"); dumpAll(s, "s.", sb) } } catch (_: Throwable) {}
                try { Reflect.readAny(ent, "mColumnMeta")?.let { c2 -> sb.append(" ||COL ${c2.javaClass.name}:"); dumpAll(c2, "c.", sb) } } catch (_: Throwable) {}
                Logger.d(sb.toString())
            }
            // ★ ENTSCAN 深扫：找直播带货/电商 KRN 挂件在 VideoFeed 数据层的宿主字段。
            // 证据：KrnReactContainerView 的 LaunchModel.f Bundle 含 bundleId(Kwaishop*)，
            // CombinedCard cardHeight=80 横条挂件——疑似 VideoFeed 内部 meta 字段驱动。
            // BFS ent 字段树（深 4 层），凡 String 值含 krn/Kwaishop/bundleId 即报告路径。
            if (CfhState.entScanDiag < 10) {
                CfhState.entScanDiag++
                val scanHits = mutableListOf<String>()
                fun entScan(obj: Any?, prefix: String, depth: Int, seen: MutableSet<Int>) {
                    if (obj == null || depth > 4 || scanHits.size > 12) return
                    if (!seen.add(System.identityHashCode(obj))) return
                    var cf: Class<*>? = obj.javaClass
                    var lf = 0
                    while (cf != null && cf != Any::class.java && lf < 2) {
                        for (ff in cf!!.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(ff.modifiers)) continue
                            try {
                                ff.isAccessible = true
                                val fv = ff.get(obj)
                                if (fv == null) continue
                                if (fv is String) {
                                    if (fv.contains("krn", true) || fv.contains("Kwaishop") || fv.contains("bundleId") || fv.contains("renderUrl") || fv.contains("kwaipreviewlive")) {
                                        scanHits.add("$prefix${ff.name}='${fv.take(80)}'")
                                    }
                                } else if (fv is List<*>) {
                                    if (fv.isNotEmpty() && fv[0] != null && fv[0] !is String) entScan(fv[0], "$prefix${ff.name}[0].", depth + 1, seen)
                                } else if (fv.javaClass.name.startsWith("com.kuaishou") || fv.javaClass.name.startsWith("com.yxcorp") || fv is android.os.Bundle) {
                                    if (fv is android.os.Bundle) {
                                        for (k in fv.keySet()) {
                                            val bv = fv.get(k)
                                            if (bv is String && (bv.contains("krn", true) || bv.contains("Kwaishop") || bv.contains("bundleId"))) scanHits.add("$prefix${ff.name}.$k='${bv.take(80)}'")
                                        }
                                    } else entScan(fv, "$prefix${ff.name}.", depth + 1, seen)
                                }
                            } catch (_: Throwable) {}
                        }
                        val sup = cf!!.superclass; cf = sup; lf++
                    }
                }
                entScan(ent, "ent.", 0, java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>()))
                entScan(qp, "qp.", 0, java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>()))
                if (scanHits.isNotEmpty()) Logger.always("ENTSCAN #${CfhState.entScanDiag} cap=\"${cap.take(14)}\" hits=$scanHits")
            }
            if (CfhState.feedDiagCount <= 5 && pm != null) Logger.d("PMDUMP #${CfhState.feedDiagCount} pm=${CfhUtil.dumpKV(pm)}")
            if (pm != null) {
                val dis = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null }
                // ★ 启动窗（前 6 条）用 always 打：diag 关着也要看到声明原文，
                // 否则永远无法确认「对象存在即脏」是否误伤（dumpKV 打印全部字段值）
                if (dis != null) {
                    val line = "DISDUMP #${CfhState.feedDiagCount} dis=${CfhUtil.dumpKV(dis)} content=${try { Reflect.readAny(dis, "content") } catch (_: Throwable) { null }}"
                    if (CfhState.feedDiagCount <= 6) Logger.always(line) else Logger.d(line)
                }
            }
        }
        if (CfhState.movieDiagCount < 8) {
            val tube = Reflect.readAny(ent, "mTubeModel")
            val serial = Reflect.readAny(ent, "mStandardSerialMeta")
            val adNovel = Reflect.readAny(ent, "mAdNovelVideoMeta")
            val column = Reflect.readAny(ent, "mColumnMeta")
            val liveMeta = Reflect.readAny(ent, "mLivePlaybackMeta")
            val isMovie = cap.isNotEmpty() && MOVIE_HINT.any { cap.contains(it) }
            val isLiveMeta = liveMeta != null
            val pmAi = pm?.let { Reflect.readBool(it, "photoAiAnalyze") }
            val isAiCap = cap.contains("AI", true) || cap.contains("疑似") || cap.contains("生成")
            if (isMovie || serial != null || adNovel != null || tube != null || isLiveMeta || isAiCap || pmAi == true) {
                CfhState.movieDiagCount++
                val sb = StringBuilder()
                sb.append("cap=${cap.take(25)}")
                if (tube != null) sb.append(" | tube").append(CfhUtil.dumpKV(tube))
                if (serial != null) sb.append(" | serial").append(CfhUtil.dumpKV(serial))
                if (adNovel != null) sb.append(" | adNovel").append(CfhUtil.dumpKV(adNovel))
                if (column != null) sb.append(" | column").append(CfhUtil.dumpKV(column))
                if (liveMeta != null) sb.append(" | liveMeta").append(CfhUtil.dumpKV(liveMeta))
                if (pm != null) {
                    val aiKeys = CfhUtil.dumpKVFilter(pm, "ai")
                    if (aiKeys.isNotEmpty()) sb.append(" | pmAi=").append(aiKeys)
                    // ★★ 放行项也必须留下声明原文（2026-09 排查「开头漏网一直在」）：
                    // dumpKVFilter 只打字段名+toString（恒为类名 DisclaimergeMessage），
                    // 看不到声明文字。此处补打 content 值与 isAi 判定 —— 由此可判
                    // 某条放行项是「AI 声明漏判」还是「虚构演绎声明正确放行」，
                    // 这是区分「真漏网」与「正常放行」的唯一证据。
                    // ★ 三层声明统一读出（2026-09-24）：与判定口径完全一致，
                    //   这样日志里看到的值 = 判定实际用的值，不会再出现
                    //   「日志看着有声明、判定却说没有」的错位。
                    try {
                        val cd = CfhUtil.readDisclaimer(qp, ent, pm)
                        sb.append(" | dis=\"").append(cd?.take(28) ?: "<null>")
                            .append("\" disIsAi=").append(CfhUtil.isAiDisclaimerText(cd))
                    } catch (_: Throwable) {}
                    // ★★ 声明字段名自证（2026-09-24，修「AI 视频漏拦」）。
                    //
                    //   逆向证据显示声明字段在**三个不同位置**，且**名字不同**：
                    //   · QPhoto.getKDisclaimerMessageV2() → w.L(mEntity)「实体层」
                    //   · QPhoto.getDisclaimerMessage() 末句 →
                    //       ((PhotoMeta) o).mDisclaimerMessage  ← 注意：不是 V2！
                    //   · w.q0(this) 为真时走硬编码资源串
                    //
                    //   而模块此前只读 `pm.mDisclaimergeMessageV2` —— 字段名对不上，
                    //   读取**永远返回 null**，于是「有 AI 声明却漏拦」。
                    //
                    //   这里把 PhotoMeta 上**所有含 disclaim 的字段名**打出来自证，
                    //   同时在实体层找 DisclaimergeMessage。只读，不改判定。
                    val hits = ArrayList<String>()
                    try {
                        var c: Class<*>? = pm.javaClass
                        var lvl = 0
                        while (c != null && c != Any::class.java && lvl < 5) {
                            val cc: Class<*>? = c
                            for (fl in (cc ?: break).declaredFields) {
                                if (fl.name.contains("isclaim", true) || fl.name.contains("Ai", false)) {
                                    val v = try {
                                        fl.isAccessible = true
                                        val raw = fl.get(pm)
                                        when (raw) {
                                            null -> "null"
                                            is String -> "\"${raw.take(24)}\""
                                            else -> raw.javaClass.simpleName
                                        }
                                    } catch (_: Throwable) { "E" }
                                    hits.add("${fl.name}=$v")
                                }
                            }
                            c = cc?.superclass; lvl++
                        }
                    } catch (_: Throwable) {}
                    if (hits.isNotEmpty()) sb.append(" | pmDis{").append(hits.joinToString(";")).append('}')
                }
                val vmIdx = Reflect.readAny(ent, "mVideoModel")
                if (vmIdx != null) sb.append(" | vm").append(CfhUtil.dumpKV(vmIdx))
                Logger.d("deep #${CfhState.movieDiagCount}: $sb")
            }
        }
        return feedRules(qp, ent, cm, pm, cap, allowLikeRule = true)
    }

    fun feedRules(qp: Any, ent: Any, cm: Any?, pm: Any?, cap: String, allowLikeRule: Boolean): Boolean {
        // ★ 性能修复（审阅 2026-09 · S1）：开关一次性快照。
        // 原实现在各分支内直接调 Prefs.bool()，单次 feedRules 最多 8 次
        // （每次 = @Volatile 读 + HashMap.get + 装箱类型判断 + schedulePull 的
        //  System.currentTimeMillis()）。这些开关在一次判定内不可能变化，
        // 提到入口快照后语义完全等价，判定路径的 Prefs 读取从 ≤8 次降到 8 次
        // 一次完成（且后续分支零读取）。默认值与各分支原默认值逐一对应。
        // ★★★ 默认值统一为 false（2026-09-30 用户定稿，修「UI 显示开 / 实际不拦」）。
        //
        //   用户设计意图（原话）：
        //     「模块安装后默认就应该是**全关**啊。但我自己用肯定是要开的。」
        //   即「关」才是正确默认语义 —— 全新安装什么都不拦，用户自己去开他要的。
        //
        //   本块此前对 advideo/ads/drama/like_on 传 def=true（显示成「开」），
        //   与 anyFilterOn()（CfhDecide.kt:1207-1211，全部 def=false）**不一致**：
        //   干净安装时界面显示「开」、实际一条都不拦 —— 误导用户以为在过滤。
        //   现统一为 false，与 anyFilterOn() 及各分支口径一致。
        //
        //   ★ 只改默认值，不改判定逻辑：以下分支的判据、命中规则、语义全部原样保留。
        //   ★ 用户已显式保存过的键不受影响 —— Prefs.bool 读到存值即忽略 def。
        val advideoOn = Prefs.bool(Prefs.K_FLT_ADVIDEO, false)
        val adsOn = Prefs.bool(Prefs.K_FLT_ADS, false)
        val liveOn = Prefs.bool(Prefs.K_FLT_LIVE, false)
        val imageOn = Prefs.bool(Prefs.K_FLT_IMAGE, false)
        val aiOn = Prefs.bool(Prefs.K_FLT_AI, false)
        val dramaOn = Prefs.bool(Prefs.K_FLT_DRAMA, false)
        val ecOn = Prefs.bool(Prefs.K_FLT_EC, false)
        val likeOn = Prefs.bool(Prefs.K_FLT_LIKE_ON, false)
        if (advideoOn) {
            if (Reflect.readAny(ent, "mAd") != null) { hit("advideo:mAd", qp); return true }
            // ★ 官方有效标记：mNovelId>0 / 内部 mTitle 非空（空壳 AdNovelVideoMeta 普通视频
            // 也挂，仅类名判定曾误杀一片；旧 GC 风暴实为 Reflect 无缓存异常风暴，已修缓存）
            val adNovelObj = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovelObj != null && (CfhUtil.safeNextLong(adNovelObj, "mNovelId") > 0 || !Reflect.readString(adNovelObj, "mTitle").isNullOrBlank())) { hit("advideo:adNovel", qp); return true }
            if (ent.javaClass.name.contains("Ad")) { hit("advideo:entCls", qp); return true }
            val adTag = Reflect.readAny(ent, "mAdLabelDescription") ?: Reflect.readAny(ent, "mTitle")
            if (adTag != null && adTag.toString().isNotBlank() && adTag.toString() != "null") {
                val src = if (Reflect.readAny(ent, "mAdLabelDescription") != null) "adLabel" else "mTitle"
                hit("advideo:$src", qp); return true
            }
        }
        if (adsOn) {
            val capLen = cap.length
            if (capLen <= 30 && AD_TEXTS.any { cap.contains(it, true) }) { hit("ads:capText", qp); return true }
            if (cap.contains("开屏广告") || cap.contains("广告位") || cap.contains("广告时间") || cap.contains("广告：")) { hit("ads:capHard", qp); return true }
            val un = CfhUtil.readUserName(qp, ent)
            if (un.contains("开屏广告") || un.contains("广告") || un.contains("推广") || un.contains("游戏广告")) { hit("ads:userAd", qp); return true }
            // 游戏任务/签到引流广告："极速活跃够开宝箱" "签到+双倍" "60秒升级活跃"
            if (un.contains("活跃") || un.contains("宝箱") || un.contains("极速") || un.contains("签到") || un.contains("开宝箱") || un.contains("金币")) { hit("ads:userTask", qp); return true }
            if (cap.contains("签到") && (cap.contains("活跃") || cap.contains("宝箱") || cap.contains("双倍") || cap.contains("极速") || cap.contains("升级") || cap.contains("开宝箱"))) { hit("ads:capTask", qp); return true }
        }
        if (liveOn) {
            if (!CfhUtil.isStructClsName(ent.javaClass.name) && ent.javaClass.name.contains("Live", true)) { hit("live:entCls", qp); return true }
            // ★ 实证纠正：mLivePlaybackMeta 在 24/24 条普通 feed 上均非空 —— 「对象存在」
            // 毫无判别力，旧 live:meta 靠内层 mLiveStreamId 也常为 null 而漏。
            // liveMeta 内层真正有判别力的是：mLiveStartTime>0 / mStartTime>0 / mShopLive=true
            // （实测普通视频三项恒为 0/0/false）。
            val lm = Reflect.readAny(ent, "mLivePlaybackMeta")
            if (lm != null) {
                if (Reflect.readAny(lm, "mLiveStreamId") != null) { hit("live:meta", qp); return true }
                if (CfhUtil.safeNextLong(lm, "mLiveStartTime") > 0L || CfhUtil.safeNextLong(lm, "mStartTime") > 0L) { hit("live:startTime", qp); return true }
                if (Reflect.readBool(lm, "mShopLive") == true) { hit("live:shopLive", qp); return true }
            }
            if (pm != null && Reflect.readBool(pm, "mCurrentLivingState") == true) { hit("live:state", qp); return true }
// 视频广告 + 进入直播间入口（普通 VideoFeed，无 Live 类）：靠入口文案识别。
            if (cap.contains("进入直播间") || cap.contains("点击进入直播") || cap.contains("直播中") || cap.contains("提现") || cap.contains("入账") || cap.contains("任务奖励")) { hit("live:capText", qp); return true }
            val unLive = CfhUtil.readUserName(qp, ent)
            if (unLive.contains("虚拟") || unLive.contains("直播")) { hit("live:userName", qp); return true }
        }
        if (imageOn) {
            // 正向精准：实体类名 ImageFeed/Atlas（图集数据链路实测）或 mImageModel 存在
            val entClsName = ent.javaClass.name
            if (entClsName.contains("ImageFeed") || entClsName.contains("Atlas")) { hit("image:entCls", qp); return true }
            if (Reflect.readAny(ent, "mImageModel") != null) { hit("image:mImageModel", qp); return true }
            val type = cm?.let { Reflect.readLong(it, "mType") } ?: 0L
            if (type == 2L) { hit("image:cmType2", qp); return true }
            if (pm != null && (Reflect.readBool(pm, "mHasAtlasText") == true || Reflect.readAny(pm, "mAtlasDetailTitle") != null)) { hit("image:atlasMeta", qp); return true }
            // 兜底：无视频模型 → 图文
            // ★ 护栏（2026-09-25）：该兜底只对 VideoFeed 成立 —— 图集实体的
            //   mVideoModel 语义已在 ImageFeed/Atlas 正向分支覆盖；对其它实体
            //   「没有 mVideoModel」可能是结构差异而非图文信号，误拦不可控。
            if (entClsName.contains("feed.VideoFeed")) {
                val vm = Reflect.readAny(ent, "mVideoModel")
                if (vm == null || Reflect.readAny(vm, "mVideoUrl") == null) { hit("image:noVideoUrl", qp); return true }
            }
        }
        if (aiOn) {
            if (pm != null && Reflect.readBool(pm, "photoAiAnalyze") == true) { hit("ai:analyzeFlag", qp); return true }
            if (Reflect.readBool(ent, "mAiTagForAuthor") == true) { hit("ai:tagBool", qp); return true }
            val aiTagStr = try { Reflect.readAny(ent, "mAiTagForAuthor")?.toString() } catch (_: Throwable) { null }
            if (aiTagStr != null && aiTagStr.isNotBlank() && aiTagStr != "false" && aiTagStr != "0") { hit("ai:tagStr", qp); return true }
            // ★★ `mAiTagForAuthor` 真实类型/取值自证（2026-09-24，修「AI 视频漏拦」）。
            //
            //   用户报「AI 生成文字的视频漏拦」，而上面两行**只认两种形态**：
            //   ① `readBool(...) == true` —— 仅当字段是 boolean/Boolean 时成立；
            //   ② `.toString()` 非 "false"/"0"。
            //
            //   风险：若该字段实际是 **Integer(0/1)** 或 **String("0")** 之外的形态
            //   （如枚举、或 Boolean 包装类的 toString 为 "true" 之外的值），
            //   两个判据都会静默放过 —— 表现为「有 AI 标记却不拦」。
            //
            //   这里把**字段的声明类型 + 实际值 + 运行时类名**一并打出（只读），
            //   一次判定该字段该怎么读，不再靠猜。
            if (aiOn) {
                // ★★ 声明原文**无条件**全量打点（2026-09-24，修「AI 视频漏拦」终局手段）。
                //
                //   为什么必须无条件：此前探针只在「判定为放行」的路径上打印，
                //   而 AI 声明一旦被正确识别就**直接被拦掉** —— 被拦的条目不会进
                //   deep dump，于是日志里**永远看不到 AI 声明原文**，
                //   我翻了 27 条历史样本全是「虚构演绎/转载/危险动作」，一条 AI 都没有。
                //
                //   反过来，用户报的「漏拦」条目恰恰是**判定没命中**的，
                //   它们会进 dump —— 只要把原文打出来，一眼就能看出
                //   是「文字不含关键词」还是「字段没读到」。
                //
                //   真机坐标（已确认渲染位置）：左下角文案下方、
                //   控件 id=`slide_play_photo_disclaimer_text`，与「作者声明」共用。
                if (CfhState.aiTagProbeCount < 60) {
                    CfhState.aiTagProbeCount++
                    val raw = CfhUtil.readDisclaimer(qp, ent, pm)
                    val isAi = CfhUtil.isAiDisclaimerText(raw)
                    // 只打「有声明」或「有 AI 痕迹」的，避免无声明条目刷屏
                    if (!raw.isNullOrBlank() || CfhUtil.hasAnyAiTrace(ent)) {
                        Logger.always(
                            "AIDIS \"" + (raw ?: "<null>") + "\" 判为AI=" + isAi +
                                " cap=\"" + cap.take(18) + "\""
                        )
                    }
                }
            }            // "AI" 大小写敏感匹配：ignoreCase 会命中英文单词里的 ai（wait/rain/main）误伤正常视频
            // "ai生成"/"AI创作" 忽略大小写安全：ai/AI 后紧跟中文，英文单词不可能出现该组合
            // ★★★ v13.24 移除 cap.contains("疑似")（2026-09-29，50388 误拦修复）：
            //   「疑似」是中文常见词（「网红狗疑似中毒」「疑似感染」），不能当 AI 判据。
            //   50388 误拦实证：马上资讯「网红狗彪哥疑似急性中毒」= 正常资讯被 cap 判拦。
            if (cap.contains("ai生成", true) || cap.contains("AI创作") || cap.contains("AIGC") || cap.contains("人工智能") || AI_META_REGEX.containsMatchIn(cap)) { hit("ai:capText", qp); return true }
            // ★ 官方作者声明 AI 标记（数据层铁证路径：caption 无标签也能拦，「刷不到」关键）
            // ★★★ v13.24「疑似AI」独立开关（2026-09-29，50388 适配）：
            //   50388 对大量普通内容填「疑似含AI生成内容」（V2PROBE 实证），
            //   「疑似」是普适合规标记 ⇒ 由独立开关 flt_ai_suspect 控制。
            //   「确定」AI（含AI生成/属于AI/AIGC 等）仍无条件拦（硬规则）。
            val disC = CfhUtil.aiDisclaimerContent(pm)
            if (disC != null) {
                val isSuspect = disC.contains("疑似")
                if (!isSuspect) {
                    // 确定 AI 声明：无条件拦
                    hit("ai:disclaimer \"${disC.take(18)}\"", qp); return true
                }
                // 疑似 AI 声明：由 flt_ai_suspect 开关控制
                // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：与 UI 行定义（SettingsActivity/MainMenuDialog）及 CfhUtil:231 口径统一
                if (io.github.angbang852.manjiao.data.Prefs.bool(io.github.angbang852.manjiao.data.Prefs.K_FLT_AI_SUSPECT, false)) {
                    hit("ai:suspect \"${disC.take(18)}\"", qp); return true
                }
            }
            // ★ 声明对象「存在 + 内容未填充 → 判脏」兜底（2026-09-21 冷启动实测）：
            // 首批插入时 mDisclaimergeMessageV2 对象已在、但 content 尚未填充（重拉后才填，
            // 实证：同一条 00.91 判干净、04.28 才命中 ai:disclaimer）⇒ 纯内容匹配在启动窗漏判。
            // 仅当**内容为空/读不到**时才按"存在"判脏；内容非空但不含 AI 关键词的仍放行
            // （保留原有语义，避免误伤非 AI 类声明）。对照同批 6 条：仅 2 条带该字段，其余
            // 连字段都没有 ⇒ 「存在」本身即有判别力。
            if (pm != null || ent != null) {
                // ★★ 声明读取改为「三层全读」（2026-09-24，修「AI 视频漏拦」）。
                //
                //   原实现只读 `pm.mDisclaimergeMessageV2`。真机实测该字段**恒为 null**：
                //   `pmDis{mDisclaimerMessage=null; mDisclaimergeMessageV2=null; ...}`，
                //   而快手自身走**实体层**（逆向 QPhoto.getDisclaimerMessage 第 1591 行
                //   `w.L(this.mEntity)` 取 DisclaimergeMessage）。读错字段 ⇒ 漏拦。
                val c = CfhUtil.readDisclaimer(qp, ent, pm)
                val anyObj = Reflect.readAny(ent, "mDisclaimergeMessage") != null ||
                    Reflect.readAny(pm, "mDisclaimerMessage") != null ||
                    Reflect.readAny(pm, "mDisclaimergeMessageV2") != null
                if (c.isNullOrBlank()) {
                    // 对象在、内容未回填 → 按待定拦（原语义保留）
                    if (anyObj) { hit("ai:disclaimerPending", qp); return true }
                } else if (CfhUtil.isAiDisclaimerText(c)) {
                    hit("ai:disclaimer \"${c.take(18)}\"", qp); return true
                }
            }
        }
        if (dramaOn) {
            if (Reflect.readAny(ent, "mKwAppNativeDrama") != null) { hit("drama:kwAppNative", qp); return true }
            if (Reflect.readAny(ent, "mNovelDrama") != null) { hit("drama:novel", qp); return true }
            if (Reflect.readAny(ent, "mLongToShortDrama") != null) { hit("drama:long2short", qp); return true }
            val adNovelObj2 = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovelObj2 != null && (CfhUtil.safeNextLong(adNovelObj2, "mNovelId") > 0 || !Reflect.readString(adNovelObj2, "mTitle").isNullOrBlank())) { hit("drama:adNovel", qp); return true }
            if (CfhUtil.dramaShellReal(ent)) { hit("drama:shell", qp); return true }
            val tube = Reflect.readAny(ent, "mTubeModel")
            if (tube != null && (Reflect.readAny(tube, "mTubeInfo") != null || Reflect.readBool(tube, "mHasTubeTag") == true)) { hit("drama:tube", qp); return true }
            // ★ 电视剧标签修复：ENTFULL 实证漏拦样本（电视剧·从海底出击/哪吒电影剪辑）
            // mStandardSerialMeta 非空可能是普通视频的空壳（美食/工业美学/央视新闻全被误伤实证），
            // 内层真实内容校验已由上方 dramaShellReal 覆盖，此处不再非空即拦。
            // ColumnMeta 的 mCoverMainTitle/mInnerMainTitle/mDetailTitle 是「电视剧 · xxx」UI 标签文字来源，
            // 读标题文字做关键词判定（非空即拦会误伤普通栏目推荐视频）
            val colM = Reflect.readAny(ent, "mColumnMeta")
            if (colM != null) {
                val colTitle = listOf("mCoverMainTitle", "mInnerMainTitle", "mDetailTitle", "mCoverSubTitle", "mInnerSubTitle", "mCoverDesc")
                    .firstNotNullOfOrNull { f -> Reflect.readString(colM, f)?.takeIf { it.isNotBlank() } }
                if (colTitle != null && (DRAMA_TEXTS.any { colTitle.contains(it) } || colTitle.contains("电影") || colTitle.contains("电视剧") || colTitle.contains("影视"))) {
                    hit("drama:colTitle \"$colTitle\"", qp); return true
                }
            }
            if (DRAMA_TEXTS.any { cap.contains(it) }) { hit("drama:capText", qp); return true }
            if (cap.contains("完整版", true) || cap.contains("整部剧", true) || cap.contains("追剧", true)) { hit("drama:capFull", qp); return true }
        }
        // ★★★ 「第 N 集/话」集数标记 —— **必须在 `dramaOn` 块外**（2026-09-26 修正）。
        //
        // ## 修订史
        //
        // 用户报「山藏万象」「黑车司机加价后」等（`第1集｜...`，**带 AI 声明**）在详情页上屏。
        //
        // **第一版修法（错误）**：写了「dramaOn 开启按原样拦 / 关闭时按 AI 特征拦」的逻辑，
        // 但**位置仍在 `if (dramaOn) { ... }` 块内** ⇒ `flt_drama=false` 时整块不执行
        // ⇒ **修复根本没跑到**（实测 `DRAMA 判脏=false` 证实）。
        //
        // **第二版修法（当前）**：整段**移出 `dramaOn` 块**。
        //
        // ## 语义
        //
        // ```
        // dramaOn = true   ⇒ 分集内容全拦（原有语义）
        // dramaOn = false  ⇒ 只拦「分集 + AI 特征」的
        // ```
        //
        // ## 为什么这个区分是对的
        //
        // 用户关掉 `flt_drama` 的意图是「**不过滤普通短剧**」，
        // 而不是「放过 AI 生成的分集」—— 后者正是他要拦的核心目标。
        // 两条判据同时成立 ⇒ 几乎必然是「AI 生成的分集短剧」。
        //
        // ## 安全
        //
        // ★ **锚定文案开头**且只用「集/话」（不含「期」）：
        //   非锚定版本实测会误伤 `2024年第3期最值得买的十件好物` 这类普通视频。
        // ★ AI 特征判据复用已有函数（`aiDisclaimerContent` / `hasAnyAiTrace` / `mAiTagForAuthor`）。
        val isEpisode = DRAMA_EPISODE_REGEX.containsMatchIn(cap)
        if (isEpisode) {
            if (dramaOn) { hit("drama:episodeNo", qp); return true }
            if (hasAiFeature(ent, pm)) { hit("ai:episode", qp); return true }
        }
        // ── 以下为「电影/电视剧」关键词判定，仍在 dramaOn 控制下 ──
        if (dramaOn) {
            if (cap.contains("电影", true) || cap.contains("电视剧", true)) { hit("drama:capMovie", qp); return true }
        }
        if (ecOn) {
            if (cm != null && Reflect.readAny(cm, "mCommodityJumpUrl") != null) return true
            if (Reflect.readAny(ent, "mKwAppMeta") != null) return true
            if (cap.contains("购物") || cap.contains("小黄车")) return true
        }
        if (allowLikeRule && likeOn) {
            val th = Prefs.int(Prefs.K_FLT_LIKE_TH, 1000).toLong()
            if (th > 0) {
                val like = pm?.let { Reflect.readLong(it, "mLikeCount") } ?: 0L
                if (like in 1 until th) return true
            }
        }
        val kws = if (Prefs.bool(Prefs.K_FLT_KW_ON, false)) Prefs.str(Prefs.K_FLT_KEYWORDS, "").split(',', '，', ' ').filter { it.isNotBlank() } else emptyList()
        if (kws.isNotEmpty() && cap.isNotBlank() && kws.any { cap.contains(it, true) }) return true
        return false
    }

    fun shouldFilterContent(qp: Any): Boolean {
        if (!anyFilterOn()) return false
        return decideBySig(CfhState.contentSigCache, qp) { cachedDecide(CfhState.contentFilterCache, qp) { decideContentRaw(qp) } }
    }

    fun decideContentRaw(qp: Any): Boolean {
        // ★ 裸实体兜底：同 quickOfficialDirty，无 mEntity 时判 qp 自身类名
        val ent = Reflect.readAny(qp, "mEntity") ?: qp
        val entCls = ent.javaClass.name
        if (!entCls.contains("feed.VideoFeed")) {
            // ★ 缺口收口（2026-09-25）：与 decideFeedRaw 共用 nonVfRules，
            //   AI/短剧/广告判据不再对非 VideoFeed 实体缺席（原实现仅直播类名判）。
            return nonVfRules(qp, ent, entCls)
        }
        val cm = Reflect.readAny(ent, "mCommonMeta")
        val pm = Reflect.readAny(ent, "mPhotoMeta")
        val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
        return feedRules(qp, ent, cm, pm, cap, allowLikeRule = false)
    }

    /**
     * 非 VideoFeed 实体的统一判定子集（2026-09-25 缺口收口）。
     *
     * ## 背景（真机证据）
     *
     * decideFeedRaw/decideContentRaw 对非 `feed.VideoFeed` 实体原本只做
     * 直播类名与直播入口文案两类判定，其余规则（AI/短剧/广告文案）一律缺席，
     * 实体命中即放行。本次收敛：
     *   ① 原直播类名 / 游戏广告卡 / 直播入口文案判定**原样搬入**（语义不变）；
     *   ② 补**内容级**判据（声明文字 / photoAiAnalyze / 短剧-电影文案）——
     *     只搬 feedRules 里已验证语义、不依赖 VideoFeed 专属结构字段
     *     （mKwAppNativeDrama 等元数据字段仅 VideoFeed 有，此处不搬，避免无谓反射）；
     *   ③ 明确**不搬**：like 阈值 / 用户关键词（走allowLikeRule 与 KW 开关，
     *     非实体级判据）、mAd 等结构字段（非 VideoFeed 上语义未验证）。
     *
     * @param qp    QPhoto（或裸实体）
     * @param ent   实体（qp.mEntity，可能即 qp 自身）
     * @param entCls 实体类名
     */
    private fun nonVfRules(qp: Any, ent: Any, entCls: String): Boolean {
        // —— ① 直播（原 decideFeedRaw 分支语义原样保留）——
        if (Prefs.bool(Prefs.K_FLT_LIVE, false)) {
            if (!CfhUtil.isStructClsName(entCls) && entCls.contains("Live", true)) {
                CfhState.liveDiagCount++
                // 抓栈是高成本操作（填栈+分配），quiet 时不做
                if (!Logger.quiet && CfhState.liveDiagCount % 100 == 1) {
                    Logger.d("live stack #${CfhState.liveDiagCount}:\n" + Thread.currentThread().stackTrace.drop(1).take(16).joinToString("\n"))
                }
                if (CfhState.liveDiagCount <= 3 || CfhState.liveDiagCount % 100 == 0) Logger.d("live feed hit: cls=$entCls")
                try { CfhFeedHook.ensureLiveFeedConstructHooked(ent) } catch (_: Throwable) {}
                return true
            }
            // 游戏广告直播卡：类名不含 Live，靠类名族 + 入口文案识别（原语义）
            val capx = (CfhUtil.readCaption(qp) ?: Reflect.readString(ent, "mCaption"))?.take(60) ?: ""
            if (entCls.contains("AdNovel") || entCls.contains("NovelVideo") || entCls.contains("GameAd")) {
                if (CfhState.liveDiagCount % 100 == 0) Logger.d("live game-ad hit: cls=$entCls cap=${capx.take(16)}")
                return true
            }
            val unx = CfhUtil.readUserName(qp, ent)
            if (capx.contains("进入直播间") || capx.contains("点击进入直播") || capx.contains("立即参与") ||
                (capx.contains("端游") && capx.contains("上线")) ||
                unx.contains("直播") || unx.contains("弹幕游戏")) return true
        }
        // —— ② 内容级判据（与 feedRules 同源语义，只读不依赖 VideoFeed 专属字段）——
        val pm = Reflect.readAny(ent, "mPhotoMeta")
        if (Prefs.bool(Prefs.K_FLT_AI, false)) {
            if (pm != null && Reflect.readBool(pm, "photoAiAnalyze") == true) { hit("ai:analyzeFlag", qp); return true }
            val c = CfhUtil.readDisclaimer(qp, ent, pm)
            if (c.isNullOrBlank()) {
                // 声明对象在、内容未回填 → 待定拦（与 feedRules 同语义）
                val anyObj = Reflect.readAny(ent, "mDisclaimergeMessage") != null ||
                    Reflect.readAny(pm, "mDisclaimerMessage") != null ||
                    Reflect.readAny(pm, "mDisclaimergeMessageV2") != null
                if (anyObj) { hit("ai:disclaimerPending", qp); return true }
            } else if (CfhUtil.isAiDisclaimerText(c)) {
                hit("ai:disclaimer \"${c.take(18)}\"", qp); return true
            }
        }
        // ★ 默认值 true→false（2026-09-30）：与 anyFilterOn() 的 flt_drama 口径一致。
        //   此前 def=true ⇒「只要别的过滤开关开着、flt_drama 从未设置过」本分支就会拦短剧，
        //   而 UI 已改为显示「关」—— 会变成「显示关、实际拦」的新误导。统一为关。
        if (Prefs.bool(Prefs.K_FLT_DRAMA, false)) {
            val cap2 = (CfhUtil.readCaption(qp) ?: Reflect.readString(ent, "mCaption")) ?: ""
            if (DRAMA_EPISODE_REGEX.containsMatchIn(cap2)) { hit("drama:episodeNo", qp); return true }
            if (DRAMA_TEXTS.any { cap2.contains(it) }) { hit("drama:capText", qp); return true }
            if (cap2.contains("完整版", true) || cap2.contains("整部剧", true) || cap2.contains("追剧", true)) { hit("drama:capFull", qp); return true }
            if (cap2.contains("电影", true) || cap2.contains("电视剧", true)) { hit("drama:capMovie", qp); return true }
        }
        return false
    }

    fun shouldFilterMeta(v: io.github.angbang852.manjiao.data.VideoInfo): Boolean {
        // ★ 默认值 true→false（2026-09-30）：与 anyFilterOn() 的 flt_ads 口径一致（全链路默认统一为关）
        if (Prefs.bool(Prefs.K_FLT_ADS, false) && v.isAd) return true
        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && v.isLive) return true
        if (Prefs.bool(Prefs.K_FLT_AI, false) && (v.isAi || v.caption.orEmpty().contains("ai生成", true) || v.caption.orEmpty().contains("AI创作") || v.caption.orEmpty().contains("AIGC") || v.caption.orEmpty().contains("人工智能") || AI_META_REGEX.containsMatchIn(v.caption.orEmpty()))) return true
        if (Prefs.bool(Prefs.K_FLT_EC, false) && v.isEcommerce) return true
        val kws = if (Prefs.bool(Prefs.K_FLT_KW_ON, false)) Prefs.str(Prefs.K_FLT_KEYWORDS, "").split(',', '，', ' ').filter { it.isNotBlank() } else emptyList()
        if (kws.isNotEmpty()) {
            val cap = v.caption.orEmpty()
            if (cap.isNotBlank() && kws.any { cap.contains(it, true) }) return true
        }
        return false
    }

    fun quickOfficialDirty(qp: Any): Boolean {
        // ★ 裸实体兜底：LiveStreamFeed 直接作列表元素时无 mEntity 字段（实测 03:45 24批次
        // 全放行直通上屏），ent 取 qp 自身让 live:entCls 类名判定照常工作
        val ent = Reflect.readAny(qp, "mEntity") ?: qp
        // ★ 启动窗全量探针（2026-09 用户报「一直在，一般在前几条」）：
        // 原为 quickDebugCount<10 限次，导致首批 9 条之后的判定真值完全不可见 ——
        // 用户看到的漏网项恰好落在盲区里，无法确认「是声明缺字段」还是「判据没覆盖」。
        // 改为冷启 20s 内全打（always 级），并补齐定位漏网所需的关键指纹：
        // 类名/声明文字/直播内层真信号/pager 类名，一条日志即可判断该走哪条规则。
        run {
            val inBootWin = CfhState.processStartAt > 0L && System.currentTimeMillis() - CfhState.processStartAt < 20_000L
            // ★ 性能修复（审阅 2026-09 · L3）：原为 always（不受任何门控）⇒ 冷启动
            // 20 秒内每次 quickOfficialDirty 都拼一条含 6 次反射读的长字符串并跨进程
            // 输出。本函数位于 decideBySig 的 miss 路径（即每个新内容都会走到），
            // 属于判定热路径。改为 diag 门控 + 惰性求值：默认零成本，
            // 排障打开「诊断日志」即完整复现原取证能力。
            if (Logger.diag && (inBootWin || CfhState.quickDebugCount < 10)) {
                CfhState.quickDebugCount++
                Logger.d {
                    val aiOn = Prefs.bool(Prefs.K_FLT_AI, false)
                    val pm = Reflect.readAny(ent, "mPhotoMeta")
                    val dis = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null }
                    val disC = if (dis != null) try { Reflect.readAny(dis, "content") as? String } catch (_: Throwable) { null } else null
                    val lm = try { Reflect.readAny(ent, "mLivePlaybackMeta") } catch (_: Throwable) { null }
                    "QDBG aiOn=$aiOn ent=${ent.javaClass.simpleName} hasDis=${dis != null} disC=$disC " +
                        "liveMeta=${lm != null} liveStart=${lm?.let { CfhUtil.safeNextLong(it, "mLiveStartTime") } ?: 0} " +
                        "shopLive=${lm?.let { Reflect.readBool(it, "mShopLive") } ?: false} " +
                        "living=${pm?.let { Reflect.readBool(it, "mCurrentLivingState") } ?: false} " +
                        "cap=\"${CfhUtil.readCaption(qp)?.take(22)}\""
                }
            }
        }
        // ★ 直播：ent 类名含 Live 即拦（纯类名检查微秒级，与 advideo:mAd 同级）。
        // 实证 02:36 LADUMP {LiveStreamFeed=3} 批次 del 只带走 AI/广告、3 条直播全部放行
        // ——直播此前不在 quick 路径，后台补剔又晚于 pager 构造，致精选tab直播上屏
        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhUtil.isStructClsName(ent.javaClass.name) && ent.javaClass.name.contains("Live", true)) {
            hit("live:entCls", qp); return true
        }
        // ★ 默认值 true→false（2026-09-30）：与 anyFilterOn() 的 flt_advideo 口径一致（全链路默认统一为关）。
        //   不改判定逻辑：mAd / mAdNovelVideoMeta 两条判据原样保留，只改「从未设置过」时的取值。
        if (Prefs.bool(Prefs.K_FLT_ADVIDEO, false)) {
            if (Reflect.readAny(ent, "mAd") != null) { hit("advideo:mAd", qp); return true }
            val adNovelObj = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovelObj != null && (CfhUtil.safeNextLong(adNovelObj, "mNovelId") > 0 || !Reflect.readString(adNovelObj, "mTitle").isNullOrBlank())) { hit("advideo:adNovel", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_AI, false)) {
            val pm = Reflect.readAny(ent, "mPhotoMeta")
            if (pm != null && Reflect.readBool(pm, "photoAiAnalyze") == true) { hit("ai:analyzeFlag", qp); return true }
            // ★ quick 路径：首屏走这里，deep 兜底太晚（实测首屏 L7 批次到 deep 仅 6ms，
            // UI 已上屏）。与 deep 同语义：AI 声明文字拦，「虚构演绎」类放行。
            //
            // ★★ 声明读取改为「三层全读」（2026-09-24，修「AI 视频漏拦」）。
            //   原实现只读 `pm.mDisclaimergeMessageV2` —— 真机实测该字段**恒为 null**
            //   （`pmDis{mDisclaimerMessage=null; mDisclaimergeMessageV2=null}`），
            //   而快手自身走的是**实体层** `w.L(mEntity)` 取 DisclaimergeMessage
            //   （见逆向 QPhoto.getDisclaimerMessage 第 1591 行）。
            //   读错字段 ⇒ 有 AI 声明的条目被判为「无声明」 ⇒ 漏拦。
            //   现统一走 CfhUtil.readDisclaimer（与快手口径一致）。
            val cq = CfhUtil.readDisclaimer(qp, ent, pm)
            if (cq.isNullOrBlank()) {
                // 声明字段存在但内容为空 = 声明尚未拉取到 → 保守拦（原行为）
                val anyObj = Reflect.readAny(ent, "mDisclaimergeMessage") != null ||
                    Reflect.readAny(pm, "mDisclaimerMessage") != null ||
                    Reflect.readAny(pm, "mDisclaimergeMessageV2") != null
                if (anyObj) { hit("ai:disclaimerPending", qp); return true }
            } else if (CfhUtil.isAiDisclaimerText(cq)) {
                hit("ai:disclaimer \"${cq.take(18)}\"", qp); return true
            }
            val cm = Reflect.readAny(ent, "mCommonMeta")
            val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
            if (cap.contains("ai生成", true) || cap.contains("AI创作") || cap.contains("疑似") || cap.contains("AIGC") || cap.contains("人工智能")) { hit("ai:capText", qp); return true }
        } else {
            if (CfhState.quickDebugCount < 10) { CfhState.quickDebugCount++; Logger.d("QDBG aiOff pm.hasDis=${try { Reflect.readAny(Reflect.readAny(ent, "mPhotoMeta"), "mDisclaimergeMessageV2") != null } catch (_: Throwable) { false }}") }
        }
        // ★ 默认值 true→false（2026-09-30）：与 anyFilterOn() 的 flt_drama 口径一致（全链路默认统一为关）。
        //   不改判定逻辑：kwApp/novel/longShort + 四条文案判据原样保留，只改「从未设置过」时的取值。
        if (Prefs.bool(Prefs.K_FLT_DRAMA, false)) {
            if (Reflect.readAny(ent, "mKwAppNativeDrama") != null) { hit("drama:kwApp", qp); return true }
            if (Reflect.readAny(ent, "mNovelDrama") != null) { hit("drama:novel", qp); return true }
            if (Reflect.readAny(ent, "mLongToShortDrama") != null) { hit("drama:longShort", qp); return true }
            // ★ 首屏窗口补判据（2026-09-25）：deep 路径（feedRules）里的短剧/电影**文案**
            //   判据（drama:capText/episodeNo/capFull/capMovie）此前只在异步判定到达后生效，
            //   而首屏 L7 批次从 quick 到 deep 仅 6ms、UI 已上屏 —— 文案型短剧首屏必漏。
            //   这里补 feedRules 同源的四条纯文案判据（字段级判据如 mTubeModel 仍留在 deep，
            //   其非空即拦的语义误伤风险高，不前移）。
            val cmQ = Reflect.readAny(ent, "mCommonMeta")
            val capQ = cmQ?.let { Reflect.readString(it, "mCaption") } ?: ""
            if (capQ.isNotEmpty()) {
                if (DRAMA_EPISODE_REGEX.containsMatchIn(capQ)) { hit("drama:episodeNo", qp); return true }
                if (DRAMA_TEXTS.any { capQ.contains(it) }) { hit("drama:capText", qp); return true }
                if (capQ.contains("完整版", true) || capQ.contains("整部剧", true) || capQ.contains("追剧", true)) { hit("drama:capFull", qp); return true }
                if (capQ.contains("电影", true) || capQ.contains("电视剧", true)) { hit("drama:capMovie", qp); return true }
            }
        }
        return false
    }

    fun decideBySig(cache: java.util.concurrent.ConcurrentHashMap<String, Boolean>, qp: Any, decide: () -> Boolean): Boolean {
        // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：判定缓存只在显式开启后才启用
        if (!Prefs.bool(Prefs.K_PERF_FCACHE, false)) return decide()
        // ★★ 正确性修复（审阅 2026-09 · M5）：**删除 sigIdCache 这一层**。
        //
        // 原实现另有一层 `sigIdCache: ConcurrentHashMap<Int, Boolean>`，键是
        // `System.identityHashCode(qp)` —— 一个 Int，**不含对象引用**，且命中时
        // （原第 549 行）直接 return，**完全不校验该 hash 是否还对应同一个对象**。
        //
        // 对象被 GC 后 identityHashCode 可被新对象复用 ⇒ 新对象命中旧条目 ⇒
        // 直接返回**上一个对象**的判定结果。后果静默且随机：
        //   · 旧对象 true(脏) → 新对象(干净) 被判脏 → **误拦**（正常视频消失）
        //   · 旧对象 false(净) → 新对象(脏)  被判放行 → **漏拦**（广告/AI 上屏）
        // 原代码用「size > 8000 就 clear()」降低概率，但**不能消除**——
        // 8000 条窗口内两个对象复用同一 hash 完全可能。
        //
        // 删除代价极小：sigIdCache 本来只是省掉「算 sig」这一步（3 次反射读），
        // 而 sig 缓存本身（feedSigCache/contentSigCache）**按内容签名**缓存，
        // 语义正确、覆盖了绝大多数重复判定。少一层缓存换来判定结果可靠，值得。
        val sig = try {
            val ent = Reflect.readAny(qp, "mEntity")
            val pm = ent?.let { Reflect.readAny(it, "mPhotoMeta") }
            // ★ 类名兜底用 qp 自身：裸实体（无 mEntity）若退化成空串公共 sig，
            // 首个判定结果会污染全部后续裸实体的缓存（false 放行直通上屏）
            // ★ sig 混入 photoId（审阅 2026-09 P2）：空文案+同赞数的不同视频
            // 原先共用缓存条目（误拦/漏拦）
            (ent?.javaClass?.name ?: qp.javaClass.name) + "|" +
                (ent?.let { e -> Reflect.readAny(e, "mCommonMeta")?.let { Reflect.readString(it, "mCaption") } } ?: "") + "|" +
                (pm?.let { Reflect.readLong(it, "mLikeCount") } ?: -1L) + "|" +
                (CfhProbe.readPhotoId(qp) ?: "")
        } catch (_: Throwable) { null }
        if (sig != null) {
            val cached = cache[sig]
            if (cached != null) {
                // ★★★ 缓存命中也要执行删除（2026-09-24）——
                //   修「判脏了但没删」的最后一处缺口。
                //
                //   ## 实测（主人报「龚老师讲牛羊」「青椒本娇」「七妹」）
                //
                //   三条全部判脏正确，但**没有任何 HITDEL 记录**，
                //   而同期 HITDEL 共触发 27 次 —— 说明这几条的判定
                //   **根本没经过 `hit()`**。
                //
                //   ## 根因
                //
                //   `hit()` 是删除的触发点，而它只在**判定真正执行**时被调用。
                //   当 `decideBySig` 命中签名缓存（`cache[sig]` 已有结果）时，
                //   这里直接 `return it` —— **跳过了 hit()**，
                //   于是：判脏结论正确返回给了调用方（所以日志显示判脏=true），
                //   但**删除动作从未被触发**。
                //
                //   这解释了为什么「复检全绿（0 次仍存在）」却仍有漏拦：
                //   复检检查的是「删过的有没有删净」，
                //   而这些条目**压根没进过删除流程**。
                //
                //   ## 修法
                //
                //   缓存命中 `true` 时，补一次删除动作（与 hit() 内同款逻辑）。
                //   为保证命中 `hit()` 的条目不被重复删除，
                //   用与 hit() 同一个 20ms 节流闸 `lastHitDelAt`。
                if (cached) {
                    try {
                        val nowMs0 = System.currentTimeMillis()
                        if (nowMs0 - CfhState.lastHitDelAt >= 20L) {
                            CfhState.lastHitDelAt = nowMs0
                            val pid0 = CfhProbe.readPhotoId(qp)
                            if (!pid0.isNullOrBlank()) {
                                CfhState.noteDirty(pid0, CfhDecide.lastHitReason ?: "cache")
                                val r0 = CfhDiag.removeFromFragContainers(CfhState.tracked, pid0)
                                if (r0 > 0 && CfhState.hitDelCount < 200) {
                                    CfhState.hitDelCount++
                                    Logger.evidence(
                                        "HITDEL",
                                        "★缓存命中移除 $r0 处 id=$pid0 " +
                                            "cap=\"${try { CfhUtil.readCaption(qp)?.take(18) } catch (_: Throwable) { null } ?: "-"}\""
                                    )
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                return cached
            }
            // ★ 缓存淘汰改为「分批淘汰」（M5）：原为 size>3000 直接 clear()，
            // 触发瞬间所有已判定结果一起失效，下一批内容全部走 miss
            // （quickOfficialDirty + 异步全量判定 + 对象图遍历），
            // 形成锯齿型性能曲线。改为淘汰约一半，保留另一半热度。
            if (cache.size > 3000) {
                val it = cache.keys.iterator()
                var drop = cache.size / 2
                while (drop-- > 0 && it.hasNext()) { it.next(); it.remove() }
            }
            // miss：主线程先跑 quickOfficialDirty（微秒级官方标记），命中即拦；
            // 未命中入后台线程跑全量判定，先放行
            if (quickOfficialDirty(qp)) {
                cache[sig] = true
                return true
            }
            if (CfhState.asyncDecidePending.add(sig)) {
                try {
                    CfhState.cleanExecutor.execute {
                        try {
                            val r = decide()
                            cache[sig] = r
                            CfhState.asyncDecidePending.remove(sig)
                        } catch (_: Throwable) { CfhState.asyncDecidePending.remove(sig) }
                    }
                } catch (_: Throwable) {
                    // ★★★ 修复「永久漏拦」（2026-09-29 代码审核，①号高危）：
                    //   原实现只把 remove 放在**任务内部**。若 execute() 本身抛异常
                    //   （RejectedExecutionException：线程池已 shutdown / 饱和被拒），
                    //   sig 会**永久留在 asyncDecidePending 里**；此后同内容再来时
                    //   add(sig) 返回 false ⇒ 直接 return false（放行）⇒ **永远不再判定**。
                    //   后果：该内容永久漏拦（违反白名单制「判不了必须扣下」）
                    //        + 集合无界增长。
                    //   修法：入队失败就地摘除，下次遇到会重新走异步判定。
                    CfhState.asyncDecidePending.remove(sig)
                }
            }
            return false
        }
        return decide()
    }

    fun cachedDecide(cache: MutableMap<Any, Boolean>, qp: Any, decide: () -> Boolean): Boolean {
        // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：判定缓存只在显式开启后才启用
        if (!Prefs.bool(Prefs.K_PERF_FCACHE, false)) return decide()
        // ★★★ 性能优化（2026-09-26 用户要求「加快识别过滤速度」）。
        //
        // 原实现是 `synchronizedMap(WeakHashMap)`，两个问题（实测定位）：
        //   ① **`WeakHashMap` 被 GC 清空** —— 快手进程 GC 频繁
        //      （实测 `freed 43MB, total 231ms`），弱引用键随即消失
        //      ⇒ 缓存几乎不命中 ⇒ 每次走完整判定（25~182ms）
        //   ② **`synchronizedMap` 全局锁** —— 判定来自多线程，锁竞争拖慢
        //
        // 现已换成 `ConcurrentHashMap`（强引用 + 分段锁），并**每批告警限次**。
        //
        // 淘汰策略：仍是「淘汰一半」而非 `clear()` ——
        // `clear()` 会让全部已判定结果一起失效，下一批全走 miss 的锯齿。
        if (cache.size > 3000) {
            try {
                val it = cache.keys.iterator()
                var drop = cache.size / 2
                while (drop-- > 0 && it.hasNext()) { it.next(); it.remove() }
            } catch (_: Throwable) {
                // ConcurrentHashMap 的迭代器支持 remove；异常时保守清空
                try { cache.clear() } catch (_: Throwable) {}
            }
        }
        val hit = cache[qp]
        if (hit != null) {
            if (CfhState.fcacheDiag < 5) { CfhState.fcacheDiag++; Logger.d("fcache hit (${cache.size})") }
            return hit
        }
        val r = decide()
        try { if (cache.size >= 4096) cache.clear(); cache[qp] = r } catch (_: Throwable) {}
        return r
    }

    /**
     * ★ AI 特征判定（供「分集 + AI」规则复用，2026-09-26）。
     *
     * ## 用途
     *
     * `第N集｜` 分集内容在 `flt_drama=false` 时**放行**，
     * 但**同时带 AI 特征**的仍要拦（用户要拦的是「AI 生成的分集」，不是普通短剧）。
     *
     * ## 判据（全部复用已有函数，不新增猜测性规则）
     *
     * ```
     * ① CfhUtil.aiDisclaimerContent(pm)  → 声明内容含 AI 关键词
     * ② CfhUtil.hasAnyAiTrace(ent)       → 实体上有 AiTag/Aigc/AiDeclar 类字段
     * ③ ent.mAiTagForAuthor == true      → 官方 AI 作者标记（布尔）
     * ④ ent.mAiTagForAuthor.toString()   → 同上（非 false/0 即视为有）
     * ```
     *
     * ## 为什么这四条足够
     *
     * 它们覆盖了**快手标记 AI 内容的全部已知形态**：
     * 声明文案（`作者声明：含AI生成内容`）、AI 作者标签、AIGC 字段。
     * **四者任一成立**即认为"这条内容带 AI 特征"。
     *
     * ## 与 `shouldFilterFeed` 的关系
     *
     * 本函数是 `shouldFilterFeed` 里 AI 判定的**子集** ——
     * 但它**不要求"容器存在"**，因此能在「声明字段尚未回填」时也识别出 AI 特征
     * （如 `hasAnyAiTrace` 查的是**字段名**而非值）。
     */
    private fun hasAiFeature(ent: Any?, pm: Any?): Boolean {
        try {
            // ① 声明内容含 AI 关键词
            if (CfhUtil.aiDisclaimerContent(pm) != null) return true
            // ② 实体上有 AI 相关字段（查字段名，不要求值）
            if (CfhUtil.hasAnyAiTrace(ent)) return true
            // ③ 官方 AI 作者标记（布尔）
            if (Reflect.readBool(ent, "mAiTagForAuthor") == true) return true
            // ④ 同一字段的字符串形态（非 false/0 即视为有）
            val s = try { Reflect.readAny(ent, "mAiTagForAuthor")?.toString() } catch (_: Throwable) { null }
            if (s != null && s != "false" && s != "0") return true
        } catch (_: Throwable) {}
        return false
    }

    fun anyFilterOn(): Boolean {
        val now = System.currentTimeMillis()
        if (now - CfhState.anyOnAt > 2000) {
            CfhState.anyOnAt = now
            CfhState.anyOnCache = Prefs.bool(Prefs.K_FLT_ADS, false) || Prefs.bool(Prefs.K_FLT_ADVIDEO, false) ||
                Prefs.bool(Prefs.K_FLT_IMAGE, false) || Prefs.bool(Prefs.K_FLT_LIVE, false) ||
                Prefs.bool(Prefs.K_FLT_AI, false) || Prefs.bool(Prefs.K_FLT_EC, false) ||
                Prefs.bool(Prefs.K_FLT_DRAMA, false) || Prefs.bool(Prefs.K_FLT_LIKE_ON, false) ||
                Prefs.bool(Prefs.K_FLT_KW_ON, false)
        }
        return CfhState.anyOnCache
    }

    /**
     * ★★ 带判据返回的判定（2026-09-24）—— 修「lastHitReason 残留」。
     *
     * ## 问题
     *
     * `lastHitReason` 只在 `hit()` 里**赋值**，从不清理。于是：
     * ```
     * 上一次判定命中 ai:disclaimer  → lastHitReason = "ai:disclaimer ..."
     * 本次判定未命中（返回 false）  → lastHitReason 仍是上一次的值
     * ```
     * 探针把它打出来就得到自相矛盾的一行：
     *   `VISDUMP 判脏=false 判据=ai:disclaimer "疑似含AI生成内容" …`
     *
     * ## 危害（不只是日志难看）
     *
     * 任何「先看返回值、再看 lastHitReason」的代码，
     * 在返回 false 时会把**上一次的判据**当成本次的 ——
     * 据此做的决策（记录、上报、甚至删除）都会张冠李戴。
     *
     * ## 修法
     *
     * 提供本函数：判定前把 `lastHitReason` 清空，
     * 返回「是否脏」与「本次判据」的配对，调用方不必再读全局残留值。
     *
     * 原 `shouldFilterFeed` 保持不动（避免影响既有调用方），
     * 新代码请优先用本函数。
     */
    fun judgeFeed(qp: Any): Pair<Boolean, String?> {
        lastHitReason = null
        val dirty = shouldFilterFeed(qp)
        return dirty to (if (dirty) lastHitReason else null)
    }

    /**
     * ★★★ 白名单判定（2026-09-26 用户定稿）—— **判正常才放行**。
     *
     * ## 用户原话
     *
     * > 「还是要改成白名单，要判正常的才能放行，不然前几个就算判脏了也删不到。
     * >   必须文案、标识、昵称全部干净才能放行，文案、标识、昵称还没到位
     * >   识别不到的也要先等着齐了判正常了才能进。」
     *
     * ## 与旧模型（黑名单）的区别
     *
     * | | 旧（黑名单） | 新（白名单） |
     * |---|---|---|
     * | 默认 | **放行** | **不放行** |
     * | 放行条件 | 没判出脏 | **明确判为干净** |
     * | 判据未命中 | 放行（= 漏洞） | 不放行（= 安全） |
     * | 字段未就绪 | 放行（误判为"干净"） | **等齐再判** |
     *
     * ## 为什么必须改成白名单（实测依据）
     *
     * 旧模型下「前几条必漏」的根因是**删除追不上渲染**：
     *   · 返回值快照 `H/V/F` 每次重建 ⇒ 删了 391 次仍有 99.6% 无效
     *   · 真源 `l.a/h.j` 是包装类 ⇒ 提不到 QPhoto
     *   · 卡片容器 `Tangram/Kmp` ⇒ 无清洗路径
     * ⇒ **判脏正确也删不到**。白名单从源头不放行，这三条通路全部失效。
     *
     * ## 三态语义
     *
     * | 返回 | 含义 | 白名单下的动作 |
     * |---|---|---|
     * | `WHITE` | 明确判为干净 | **放行** |
     * | `DIRTY` | 判出脏 | 丢弃 |
     * | `PENDING` | 关键字段未就绪，判不了 | **再等一等**（不放行、不丢弃） |
     *
     * ## 「字段齐了」的判据（对应用户说的「文案、标识、昵称」）
     *
     * 三类信息都必须**存在**（非 null 即可，空串也算"到位了"）：
     *   ① **文案** `mEntity.mCommonMeta.mCaption`
     *   ② **昵称** `mEntity.mPhotoMeta.mUserName` / `mEntity.mUser`
     *   ③ **标识**：至少一个判定载体非 null ——
     *      `mPhotoMeta`（AI 声明所在）/ `mKwAppNativeDrama` / `mAd` / `mAdNovelVideoMeta`
     *
     * **为什么"非 null 即可"而不是"非空"**：
     * 快手的字段**先分配对象、后填内容**（实测 `ai:disclaimerPending` 这个判据
     * 就是专门为「声明对象在、内容未回填」设计的）。所以：
     *   · 对象为 null ⇒ 还没到 ⇒ `PENDING`
     *   · 对象非 null（内容可能空）⇒ 已到位 ⇒ 可判
     *
     * ## 超时兜底（关键安全阀）
     *
     * 字段可能**永远不齐**（比如该类内容本就没有某字段）。
     * 故 `PENDING` 有个**时限**：超过 [WHITE_MAX_WAIT_MS] 仍未齐 ⇒ 放行。
     *
     * **为什么超时放行而不是超时丢弃**：
     * 丢弃会造成「无更多作品」（项目已栽过两次）。
     * 宁可偶尔漏一条，也不能让内容供应断掉 —— 这是用户明确的历史约束。
     *
     * @return 见上表
     */
    fun judgeWhitelist(qp: Any?): WhitelistVerdict {
        if (qp == null) return WhitelistVerdict.PENDING
        return try {
            val ent = Reflect.readAny(qp, "mEntity") ?: qp
            val cm = Reflect.readAny(ent, "mCommonMeta")
            val pm = Reflect.readAny(ent, "mPhotoMeta")
            val userObj = Reflect.readAny(ent, "mUser") ?: Reflect.readAny(qp, "mUser")
            // ★★★ 判据修正（2026-09-26 追「青春磕颜家」漏拦）。
            //
            // ## 缺陷原状
            //
            // 原判据是「容器对象存在 ⇒ 字段齐了」：
            // ```kotlin
            // val cm = mCommonMeta;  val pm = mPhotoMeta
            // val hasAnyMarker = pm != null || mKwAppNativeDrama != null || mAd != null ...
            // if (cm == null || (pm == null && userObj == null) || !hasAnyMarker) return PENDING
            // ```
            //
            // ## 为什么错（实测链路）
            //
            // 用户报「青春磕颜家」（id=5259078674300789601，声明「疑似含AI生成内容」）上屏。
            // 探针实证：
            // ```
            // [RESPRET] HomeFeedResponse.getItems size=1 类=ArrayList 可变=true   ← 白名单确实被调用
            // [WHITE]   白名单挡下 14 次                                          ← 白名单在工作
            // [WHITEPEND] 0 次                                                    ← 从未判过 PENDING
            // ```
            // ⇒ 它在 `getItems()` 那一刻被判成 **WHITE（放行）**。
            //
            // 原因：那一刻 `mPhotoMeta` 容器**在**（`hasAnyMarker=true`），
            // 但声明字段 `mDisclaimergeMessage` **容器还没建**
            // ⇒ `decideFeedRaw:687-692` 的 `anyObj=false`
            // ⇒ 既不触发 `ai:disclaimer`、也不触发 `ai:disclaimerPending`
            // ⇒ 判为"干净" ⇒ **放行**。
            //
            // 而稍后 `PSCAN` 时声明已回填 ⇒ 判 `DIRTY`（正确）。**时序差导致漏拦**。
            //
            // ## 修正
            //
            // **与判定函数保持同一判据** —— `decideFeedRaw:679` 写的是：
            // ```kotlin
            // if (pm != null || ent != null) {   // ← 只要容器在就进判定
            //     val c = readDisclaimer(qp, ent, pm)
            //     val anyObj = mDisclaimergeMessage != null || ...   // 声明容器在？
            //     if (c.isNullOrBlank()) {
            //         if (anyObj) hit("ai:disclaimerPending")        // ★ 对象在、内容空 ⇒ 拦
            //     } else if (isAiDisclaimerText(c)) hit("ai:disclaimer")
            // }
            // ```
            //
            // ⇒ 所以「字段待定」**不该由白名单提前判 PENDING**，
            //    而应由判定函数的 `ai:disclaimerPending` 处理 —— 它**也是拦**。
            //
            // 本函数现在只做「**最基础的可用性检查**」：
            //   · 连 `mCommonMeta` 和 `mPhotoMeta` 都读不到的（对象本身没成型）⇒ PENDING
            //   · 其余一律交给 `shouldFilterFeed` —— 它内部已有完整的"待定"处理
            //
            // 这样 `ai:disclaimerPending`（= 你说的「还没到位就先等着」）
            // 才能真正生效。
            if (cm == null && pm == null && userObj == null) {
                return WhitelistVerdict.PENDING
            }
            // ★★★ 2026-09-28「重生赶海」漏拦根因修复（用户「没有验证的也不能放行」）：
            //   「第N集/话」形态短剧 + AI 特征**无法确认**（声明未回填/无 AI 痕迹）时，
            //   **不判 WHITE 放行** ⇒ 判 PENDING（等字段回填后再判）。
            //   flt_drama=false 的语义是「放真人短剧」，但 AI 短剧必须拦；
            //   无法区分时宁可 PENDING —— 由黑名单短路（QPhotoDeserializer 段
            //   `v != WHITE` 写黑名单）+ 事后 PSCAN 判定兜底，不允许「无验证放行」。
            val capPend = try { CfhUtil.readCaption(qp) ?: "" } catch (_: Throwable) { "" }
            val isEpPend = DRAMA_EPISODE_REGEX.containsMatchIn(capPend)
            if (isEpPend && !hasAiFeature(ent, pm)) {
                // ★ 显式设 lastHitReason（ai: 前缀 ⇒ isBlacklisted 的 reasonStillEnabled
                //   按 K_FLT_AI=true 生效；若残留 drama: 前缀会被 K_FLT_DRAMA=false 判失效）
                lastHitReason = "ai:episode-pending"
                if (CfhState.episodePendLog < 40) {
                    CfhState.episodePendLog++
                    val pidP = try { CfhProbe.readPhotoId(qp) } catch (_: Throwable) { null }
                    Logger.evidence(
                        "EPISODE-PEND",
                        "★第N集形态 AI未确认→PENDING id=$pidP cap=\"${capPend.take(18)}\""
                    )
                }
                return WhitelistVerdict.PENDING
            }
            // 字段基本可用 ⇒ 交给判定函数（其内部含 disclaimerPending 兜底）
            if (shouldFilterFeed(qp)) WhitelistVerdict.DIRTY else WhitelistVerdict.WHITE
        } catch (_: Throwable) {
            // 判定异常 ⇒ 保守不放行（白名单语义）
            WhitelistVerdict.PENDING
        }
    }

    /**
     * 白名单「字段未齐」的**告警**阈值（毫秒）—— **不再用于放行**。
     *
     * ## ★ 语义变更（2026-09-26 用户定稿方案 A）
     *
     * 原为「超时 ⇒ 放行」（`WHITE_MAX_WAIT_MS = 3000`）。**实测这是漏洞**：
     * 用户报的「咪咪 / #回响计划」带 `作者声明：含AI生成内容`，
     * 却因判成 `PENDING` ⇒ 3 秒后被放行 ⇒ 上屏（后续 `PDEL-MISS` 也删不掉）。
     *
     * 证据：
     * ```
     * [WHITEDIAG] 放行(WHITE)=0 判脏(DIRTY)=0 待判(PENDING)=1
     * ```
     *
     * **现在只用于"告警"**：超过此阈值仍 `PENDING` ⇒ 记 `WHITESTUCK` 证据
     * （**仍然不放行**），让人知道是哪条卡住、卡了多久。
     *
     * 用户原话：「还没到位识别不到的也要先等着齐了判正常了才能进」
     * ⇒ 「等着」是真等，不是等一会儿就放。
     */
    const val WHITE_PEND_ALERT_MS = 10_000L

    /** @deprecated 超时放行是漏洞（方案 A 已废弃），改用 [WHITE_PEND_ALERT_MS] 仅作告警 */
    @Deprecated("超时放行是漏洞，见 WHITE_PEND_ALERT_MS 说明")
    const val WHITE_MAX_WAIT_MS = 3000L

    /** 白名单判定结果 */
    enum class WhitelistVerdict { WHITE, DIRTY, PENDING }

    fun hit(reason: String, qp: Any) {
        try {
            // ★ 记录最近一次命中的判据（2026-09-24）：
            //   删除证据落盘时需要知道「这条为什么被判脏」——
            //   只记「删了 6 条」无法判断是正常拦截还是误判。
            CfhDecide.lastHitReason = reason
            CfhState.filterHitStats.merge(reason, 1, Int::plus)
            CfhState.hitTotal++
            // ★ 分时统计（2026-09 用户要求 24h/48h/7d）：按整小时桶聚合。
            // 无条件记录（一次 HashMap 自增，成本可忽略），与 auditEnabled 解耦 ——
            // 时间维度统计即使不逐条留证据也应可用。
            CfhState.recordHourly(System.currentTimeMillis(), reason)
            // ★★★ 判脏即入黑名单（2026-09 用户报「开头第一/二条仍是 AI」）：
            // 实证 probe11 —— T0 在 44.748 已把「炸薯条」从数据源删掉，但屏幕在
            // 46.665 就画出了作者名（imm ... t=@欣欣特效），frag M 直到 47.073 才
            // 再次判脏并替换 —— 晚了 0.4 秒，用户看到的就是这个窗口。
            // 说明该条被 rerank/pager 的**另一条引用**重新供给（数据源删除删不到它）。
            // 这里按 photoId 记黑名单：判过一次脏的 id 永久为脏，后续无论从哪条路径
            // 回来（frag M / pager i / T0(i) 替换源 / 真源重建）都在毫秒级判脏，
            // 不再依赖「哪条路径先看到它」。
            val pid = CfhProbe.readPhotoId(qp)
            if (pid != null && pid.isNotBlank()) {
                CfhState.noteDirty(pid, reason)
                // ★★★ 剧集矩阵登记（2026-09-25）：广告剧集同 mNovelId 的兄弟集一次全拦，
                //   治「狗蛋-安全科普/城也萧何/嫣然讲故事」类矩阵 60+ 集删一灌一。
                // ★★★ 同步删除（2026-09-24）—— 把删除延迟压到「判定当刻」。
                //
                //   ## 为什么需要（重启后漏拦的直接原因）
                //
                //   实测主人重启后报的三条：
                //     `笑天影视`      4226807 PDEL 移除2处 → 4280963 VISDUMP 仍可见
                //     `搬家不通知儿子` 4311747 VISJUDGE 触发删除
                //                     4311805 VISDUMP 仍可见（**58 毫秒后**）
                //     `这也太可以了吧` 4236898 判脏 → 删除未覆盖屏幕那份
                //
                //   三条都是**判脏正确、但用户已经看到了**。
                //
                //   ## 根因：延迟 > 停留时间
                //
                //   原先删除靠周期任务（1-2 秒一轮），而用户滑过一条内容
                //   也只要 1 秒左右。于是出现：
                //     内容上屏 → 用户看到 → 周期任务才判脏 → 才删除
                //   用户看到的就是这个窗口里的内容。
                //
                //   ## 本版做法
                //
                //   在 `hit()` —— **判定命中的那一瞬间** —— 直接尝试移除。
                //   不再等周期任务。`hit()` 已经是模块内所有判定的汇聚点
                //   （45 处判定调用最终都会走到这里），挂在这里覆盖最全。
                //
                //   ## 成本与安全
                //
                //   · 用 `CfhState.quickDelGuard` 做 20ms 去重 ——
                //     同一波判定会对同一条调多次 hit()，不必重复遍历。
                //   · 移除遍历有「只动列表 / 按 photoId 匹配 / 保留至少 1 项 /
                //     跳过 o 与 m* 字段」四条护栏（见 removeFromFragContainers）。
                //   · 整个块 try 包裹，任何异常都不影响判定结果。
                try {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - CfhState.lastHitDelAt >= 20L) {
                        CfhState.lastHitDelAt = nowMs
                        val r1 = CfhDiag.removeFromFragContainers(CfhState.tracked, pid)
                        if (r1 > 0 && CfhState.hitDelCount < 80) {
                            CfhState.hitDelCount++
                            Logger.evidence(
                                "HITDEL",
                                "★判定当刻移除 $r1 处 id=$pid " +
                                    "判据=$reason " +
                                    "cap=\"${try { CfhUtil.readCaption(qp)?.take(18) } catch (_: Throwable) { null } ?: "-"}\""
                            )
                        }
                    }
                    // ★★ 拦截当刻「清展示信息」的调用**已移除**（2026-09-26 用户定稿）。
                    //
                    //   用户原话：「昵称、文案、标识这些拦截功能……应该删掉这个功能，
                    //   完全没用还干扰正常使用」
                    //
                    //   实测副作用（浩轩轮）：清字段后脏项**承载卡片仍占位**，
                    //   正常内容 `判脏=false` 从 97 掉到 35（↓64%）。
                    //   详见 `CfhWash.scrubShownDirty` 头部的完整证据。
                } catch (_: Throwable) {}
            }
            // ★ 拦截审计（功能 4/5/6，2026-09）：开关关闭时零开销（一次 volatile 读）
            if (CfhState.auditEnabled) {
                try {
                    val ent = Reflect.readAny(qp, "mEntity") ?: qp
                    val un = try { CfhUtil.readUserName(qp, ent) } catch (_: Throwable) { "" }
                    val cap = CfhUtil.readCaption(qp) ?: ""
                    synchronized(CfhState.hitRecords) {
                        CfhState.hitRecords.addLast(
                            CfhState.HitRecord(
                                at = System.currentTimeMillis(),
                                reason = reason,
                                caption = cap.take(60),
                                user = un.take(24),
                                photoId = pid ?: "",
                                entCls = ent.javaClass.simpleName
                            )
                        )
                        // ★ 保留策略（2026-09 用户定稿）：记录保留 **7 天**，
                        //   与统计窗口（最高 168h）对齐 —— 更早的记录看了也没意义。
                        //   条数上限仍作兜底（防异常刷屏撑爆内存），取 7 天容量的宽松值。
                        val cutoff = System.currentTimeMillis() - CfhState.RECORD_KEEP_MS
                        while (CfhState.hitRecords.isNotEmpty() && CfhState.hitRecords.peekFirst().at < cutoff) {
                            CfhState.hitRecords.removeFirst()
                        }
                        // 条数兜底上限：与默认值一致（10000）。原 coerceIn(20,1000)
                        // 会把新默认值压回 1000，重度使用下半天打满 —— 一并放宽。
                        val lim = CfhState.auditLimit.coerceIn(20, 10000)
                        while (CfhState.hitRecords.size > lim) CfhState.hitRecords.removeFirst()
                    }
                } catch (_: Throwable) {}
            }
            // ★ 跨进程镜像（2026-09 修复「app 里拦截统计/记录没有信息」）：审计状态只活在
            // 快手进程，设置页在模块进程读不到，这里把状态节流镜像到磁盘供 app 侧读取。
            // 必须无条件调用（不能塞进 auditEnabled 分支）—— 时间维度统计与命中总数
            // 即使不开「记录证据」也要能在 app 里看到。内部自带 30s 节流 + 异步落盘。
            CfhState.mirrorSoon()
            if (CfhState.hitLogDiag < 60) {
                CfhState.hitLogDiag++
                val un = try { CfhUtil.readUserName(qp, Reflect.readAny(qp, "mEntity") ?: qp) } catch (_: Throwable) { "" }
                Logger.d("fltHit [$reason] user=$un cap=${CfhUtil.readCaption(qp)?.take(28)}")
            }
            if (CfhState.hitTotal % 60 == 0) {
                Logger.d("fltHitStats total=${CfhState.hitTotal} " + CfhState.filterHitStats.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}=${it.value}" })
            }
        } catch (_: Throwable) {}
    }

    fun invalidateCaches() {
        CfhState.feedFilterCache.clear()
        CfhState.contentFilterCache.clear()
        // ★ 配置变化必须连带清签名缓存：此前只清 identity 弱缓存，sig 缓存里
        // 旧开关组合的判定结果残留（如 AI 开着时的 true），用户关开关后旧项
        // 仍被过滤直到缓存超限 clear——开关「关不掉」的根因之一
        CfhState.feedSigCache.clear()
        CfhState.contentSigCache.clear()
        // sigIdCache 已删除（M5）——原此处连带清空，现已无该缓存
        CfhState.asyncDecidePending.clear()
        // ★ 黑名单同样必须随开关变化清空：它是「按旧开关组合判出的脏」，
        // 不清会导致用户关掉某类过滤后，已入黑名单的项仍被过滤（开关「关不掉」）。
        // （剧集矩阵黑名单同随开关变化清空：按旧开关组合判出的脏不应残留）
        CfhState.dirtyPhotoMap.clear()
    }

}
