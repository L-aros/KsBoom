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

    val AI_META_REGEX = Regex("\\bAI\\b|AI[生成制作绘画]|:AI|AI：")

    val DRAMA_TEXTS = arrayOf("看全集", "文娱榜", "选集", "上集", "下集", "全剧", "剧集", "正片")

    val MOVIE_HINT = arrayOf("电影", "电视剧", "影视", "解说", "剪辑", "全集", "第", "集", "剧")

    fun shouldFilterFeed(qp: Any): Boolean {
        if (CfhState.liveTop) return false
        if (!anyFilterOn()) return false
        // ★ 解包 WeakReference：精选 tab 列表元素是 WeakReference 包装（实证 r15d #9 elemCls=WeakReference entCls=null），
        // WeakReference 没 mEntity 字段 → ent=qp 自身 → entCls=WeakReference 不含 Live → 漏判。先 .get() 解包再判定
        val realQp = if (qp.javaClass.name == "java.lang.ref.WeakReference") {
            val inner = try { (qp as java.lang.ref.WeakReference<*>).get() } catch (_: Throwable) { null }
            if (inner == null) return false
            if (CfhState.weakUnwrapDiag < 30) { CfhState.weakUnwrapDiag++; Logger.d("weakUnwrap: ${inner.javaClass.name}") }
            // 解包后若非 QPhoto（如 HomeFeaturedMilanoContainerFragment 容器），从内部找 QPhoto 再判定
            val qpClass = CfhState.qpClassRef
            if (qpClass != null && !qpClass.isAssignableFrom(inner.javaClass)) {
                CfhProbe.findQpInObject(inner) ?: inner
            } else inner
        } else qp
        // ★★★ 黑名单短路：判过一次脏的 photoId 永久为脏，绕开签名缓存与解包开销。
        // 目的：消灭「数据源已删、另一条引用又把它送回屏幕」的 0.4 秒窗口
        // （实证 probe11：T0 44.748 删除 → 屏幕 46.665 已渲染 → frag M 47.073 才判脏）。
        if (isBlacklisted(realQp)) return true
        return decideBySig(CfhState.feedSigCache, realQp) { cachedDecide(CfhState.feedFilterCache, realQp) { decideFeedRaw(realQp) } }
    }

    /**
     * 脏项黑名单快查（判脏即入，见 [hit]）。
     * 单独做一个入口而不是塞进 shouldFilterFeed：后者有签名缓存，黑名单是
     * 「已判定的事实」，应当无条件短路，不能受缓存/开关组合变化影响。
     */
    fun isBlacklisted(qp: Any?): Boolean {
        if (qp == null) return false
        if (CfhState.dirtyPhotoIds.isEmpty()) return false
        val id = try { CfhProbe.readPhotoId(qp) } catch (_: Throwable) { null } ?: return false
        return id.isNotBlank() && CfhState.dirtyPhotoIds.contains(id)
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
            if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhUtil.isStructClsName(entCls) && entCls.contains("Live", true)) {
                CfhState.liveDiagCount++
                // 抓栈是高成本操作（填栈+分配），quiet 时不做
                if (!Logger.quiet && CfhState.liveDiagCount % 100 == 1) {
                    Logger.d("live stack #${CfhState.liveDiagCount}:\n" + Thread.currentThread().stackTrace.drop(1).take(16).joinToString("\n"))
                }
                if (CfhState.liveDiagCount <= 3 || CfhState.liveDiagCount % 100 == 0) Logger.d("live feed hit: cls=$entCls")
                try { CfhFeedHook.ensureLiveFeedConstructHooked(ent) } catch (_: Throwable) {}
                return true
            }
            // ★ 游戏广告直播卡：AdNovelVideoMeta（"天龙八部"类"点击进入直播间"卡）类名不含 Live。
            // 用户定义：任何带 "进入直播间/立即参与/玩端游" 入口的卡片都算"直播"，必须刷不出来。
            if (Prefs.bool(Prefs.K_FLT_LIVE, false)) {
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
            return false
        }
        val cm = Reflect.readAny(ent, "mCommonMeta")
        val pm = Reflect.readAny(ent, "mPhotoMeta")
        val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
        // ★★★ 视频作者直播浮窗：普通 VideoFeed 里驱动 "直播中/进入直播间/直播小窗" 的字段
        // （欠编译版本 mCurrentLivingState 仅是其一；这里全量找 live 相关字段名）
        if (CfhState.liveFieldDiag < 6) {
            CfhState.liveFieldDiag++
            val hits = mutableListOf<String>()
            fun scanForLive(obj: Any, prefix: String) {
                var c: Class<*>? = obj.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        val fnl = f.name.lowercase()
                        if (fnl.contains("live") || fnl.contains("living") || fnl.contains("streaming") || fnl.contains("livingwindow")) {
                            try {
                                f.isAccessible = true
                                val v = f.get(obj)
                                hits.add("$prefix[${c.simpleName}]${f.name}=${v?.javaClass?.simpleName ?: "null"}")
                            } catch (_: Throwable) {}
                        }
                    }
                    c = c.superclass; lvl++
                }
            }
            scanForLive(ent, "ent ")
            if (pm != null) scanForLive(pm, "pm ")
            if (cm != null) scanForLive(cm, "cm ")
            scanForLive(qp, "qp ")
            if (hits.isNotEmpty()) Logger.d("LIVEFIELD $hits cap=${cap.take(16)}")
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
            // 抖鸡式类型判别：抓快手的 awemeType 等价 int 字段�?
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
                }
                val vmIdx = Reflect.readAny(ent, "mVideoModel")
                if (vmIdx != null) sb.append(" | vm").append(CfhUtil.dumpKV(vmIdx))
                Logger.d("deep #${CfhState.movieDiagCount}: $sb")
            }
        }
        return feedRules(qp, ent, cm, pm, cap, allowLikeRule = true)
    }

    fun feedRules(qp: Any, ent: Any, cm: Any?, pm: Any?, cap: String, allowLikeRule: Boolean): Boolean {
        if (Prefs.bool(Prefs.K_FLT_ADVIDEO, true)) {
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
        if (Prefs.bool(Prefs.K_FLT_ADS, true)) {
            val capLen = cap.length
            if (capLen <= 30 && AD_TEXTS.any { cap.contains(it, true) }) { hit("ads:capText", qp); return true }
            if (cap.contains("开屏广告") || cap.contains("广告位") || cap.contains("广告时间") || cap.contains("广告：")) { hit("ads:capHard", qp); return true }
            val un = CfhUtil.readUserName(qp, ent)
            if (un.contains("开屏广告") || un.contains("广告") || un.contains("推广") || un.contains("游戏广告")) { hit("ads:userAd", qp); return true }
            // 游戏任务/签到引流广告："极速活跃够开宝箱" "签到+双倍" "60秒升级活跃"
            if (un.contains("活跃") || un.contains("宝箱") || un.contains("极速") || un.contains("签到") || un.contains("开宝箱") || un.contains("金币")) { hit("ads:userTask", qp); return true }
            if (cap.contains("签到") && (cap.contains("活跃") || cap.contains("宝箱") || cap.contains("双倍") || cap.contains("极速") || cap.contains("升级") || cap.contains("开宝箱"))) { hit("ads:capTask", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_LIVE, false)) {
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
            // 视频广告 + 进入直播间入口（普�?VideoFeed，无 Live 类）：靠入口文案识别
            if (cap.contains("进入直播间") || cap.contains("点击进入直播") || cap.contains("直播中") || cap.contains("提现") || cap.contains("入账") || cap.contains("任务奖励")) { hit("live:capText", qp); return true }
            val unLive = CfhUtil.readUserName(qp, ent)
            if (unLive.contains("虚拟") || unLive.contains("直播")) { hit("live:userName", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_IMAGE, false)) {
            // 正向精准：实体类名 ImageFeed/Atlas（图集数据链路实测）或 mImageModel 存在
            val entClsName = ent.javaClass.name
            if (entClsName.contains("ImageFeed") || entClsName.contains("Atlas")) { hit("image:entCls", qp); return true }
            if (Reflect.readAny(ent, "mImageModel") != null) { hit("image:mImageModel", qp); return true }
            val type = cm?.let { Reflect.readLong(it, "mType") } ?: 0L
            if (type == 2L) { hit("image:cmType2", qp); return true }
            if (pm != null && (Reflect.readBool(pm, "mHasAtlasText") == true || Reflect.readAny(pm, "mAtlasDetailTitle") != null)) { hit("image:atlasMeta", qp); return true }
            // 兜底：无视频模型 → 图文
            val vm = Reflect.readAny(ent, "mVideoModel")
            if (vm == null || Reflect.readAny(vm, "mVideoUrl") == null) { hit("image:noVideoUrl", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_AI, false)) {
            if (pm != null && Reflect.readBool(pm, "photoAiAnalyze") == true) { hit("ai:analyzeFlag", qp); return true }
            if (Reflect.readBool(ent, "mAiTagForAuthor") == true) { hit("ai:tagBool", qp); return true }
            val aiTagStr = try { Reflect.readAny(ent, "mAiTagForAuthor")?.toString() } catch (_: Throwable) { null }
            if (aiTagStr != null && aiTagStr.isNotBlank() && aiTagStr != "false" && aiTagStr != "0") { hit("ai:tagStr", qp); return true }
            // "AI" 大小写敏感匹配：ignoreCase 会命中英文单词里的 ai（wait/rain/main）误伤正常视频
            // "ai生成"/"AI创作" 忽略大小写安全：ai/AI 后紧跟中文，英文单词不可能出现该组合
            if (cap.contains("ai生成", true) || cap.contains("AI创作") || cap.contains("疑似") || cap.contains("AIGC") || cap.contains("人工智能") || AI_META_REGEX.containsMatchIn(cap)) { hit("ai:capText", qp); return true }
            // ★ 官方作者声明 AI 标记（数据层铁证路径：caption 无标签也能拦，「刷不到」关键）
            val disC = CfhUtil.aiDisclaimerContent(pm)
            if (disC != null) { hit("ai:disclaimer \"${disC.take(18)}\"", qp); return true }
            // ★ 声明对象「存在 + 内容未填充 → 判脏」兜底（2026-09-21 冷启动实测）：
            // 首批插入时 mDisclaimergeMessageV2 对象已在、但 content 尚未填充（重拉后才填，
            // 实证：同一条 00.91 判干净、04.28 才命中 ai:disclaimer）⇒ 纯内容匹配在启动窗漏判。
            // 仅当**内容为空/读不到**时才按"存在"判脏；内容非空但不含 AI 关键词的仍放行
            // （保留原有语义，避免误伤非 AI 类声明）。对照同批 6 条：仅 2 条带该字段，其余
            // 连字段都没有 ⇒ 「存在」本身即有判别力。
            if (pm != null) {
                val disOb = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null }
                if (disOb != null) {
                    // ★ 语义判别（实证 probe 19:35 QDBG）：该字段同时承载 AI 声明与
                    // 「含虚构演绎内容，仅供娱乐」（真人剧情/配音）。按文字区分，AI 类拦；
                    // 文字为空时按待定处理（真实 AI 声明偶发 content 未回填）。
                    val c = try { Reflect.readAny(disOb, "content") as? String } catch (_: Throwable) { null }
                    if (c.isNullOrBlank()) { hit("ai:disclaimerPending", qp); return true }
                    if (CfhUtil.isAiDisclaimerText(c)) { hit("ai:disclaimer \"${c.take(18)}\"", qp); return true }
                }
            }
        }
        if (Prefs.bool(Prefs.K_FLT_DRAMA, true)) {
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
            // ★ 「第 N 集/话」集数标记（2026-09-21 冷启动实测）：实证漏拦样本
            // `第1集｜当学校空降了个新主任` —— DRAMA_TEXTS 只有上集/下集/选集/全剧/剧集/正片，
            // 不含「第1集」形态。
            // ★ 必须**锚定文案开头**且只用「集/话」（不含「期」）：本规则所在开关默认开启，
            // 非锚定版本实测会误伤 `2024年第3期最值得买的十件好物` 这类普通视频；分集标题
            // 的实际形态是「第N集｜...」「第十二话：...」——标记必在开头。
            if (Regex("^\\s*第\\s*[0-9一二三四五六七八九十百]+\\s*[集话]").containsMatchIn(cap)) { hit("drama:episodeNo", qp); return true }
            if (cap.contains("完整版", true) || cap.contains("整部剧", true) || cap.contains("追剧", true)) { hit("drama:capFull", qp); return true }
            if (cap.contains("电影", true) || cap.contains("电视剧", true)) { hit("drama:capMovie", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_EC, false)) {
            if (cm != null && Reflect.readAny(cm, "mCommodityJumpUrl") != null) return true
            if (Reflect.readAny(ent, "mKwAppMeta") != null) return true
            if (cap.contains("购物") || cap.contains("小黄车")) return true
        }
        if (allowLikeRule && Prefs.bool(Prefs.K_FLT_LIKE_ON, true)) {
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
            if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhUtil.isStructClsName(entCls) && entCls.contains("Live", true)) return true
            return false
        }
        val cm = Reflect.readAny(ent, "mCommonMeta")
        val pm = Reflect.readAny(ent, "mPhotoMeta")
        val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
        return feedRules(qp, ent, cm, pm, cap, allowLikeRule = false)
    }

    fun shouldFilterMeta(v: io.github.angbang852.manjiao.data.VideoInfo): Boolean {
        if (Prefs.bool(Prefs.K_FLT_ADS, true) && v.isAd) return true
        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && v.isLive) return true
        if (Prefs.bool(Prefs.K_FLT_AI, false) && (v.isAi || v.caption.orEmpty().contains("ai生成", true) || v.caption.orEmpty().contains("AI创作") || v.caption.orEmpty().contains("疑似") || v.caption.orEmpty().contains("AIGC") || v.caption.orEmpty().contains("人工智能") || AI_META_REGEX.containsMatchIn(v.caption.orEmpty()))) return true
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
        if (CfhState.quickDebugCount < 10) { CfhState.quickDebugCount++; val aiOn = Prefs.bool(Prefs.K_FLT_AI, false); val pm = Reflect.readAny(ent, "mPhotoMeta"); val dis = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null }; val disC = if (dis != null) try { Reflect.readAny(dis, "content") as? String } catch (_: Throwable) { null } else null; Logger.d("QDBG aiOn=$aiOn hasDis=${dis != null} disC=$disC") }
        // ★ 直播：ent 类名含 Live 即拦（纯类名检查微秒级，与 advideo:mAd 同级）。
        // 实证 02:36 LADUMP {LiveStreamFeed=3} 批次 del 只带走 AI/广告、3 条直播全部放行
        // ——直播此前不在 quick 路径，后台补剔又晚于 pager 构造，致精选tab直播上屏
        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhUtil.isStructClsName(ent.javaClass.name) && ent.javaClass.name.contains("Live", true)) {
            hit("live:entCls", qp); return true
        }
        if (Prefs.bool(Prefs.K_FLT_ADVIDEO, true)) {
            if (Reflect.readAny(ent, "mAd") != null) { hit("advideo:mAd", qp); return true }
            val adNovelObj = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovelObj != null && (CfhUtil.safeNextLong(adNovelObj, "mNovelId") > 0 || !Reflect.readString(adNovelObj, "mTitle").isNullOrBlank())) { hit("advideo:adNovel", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_AI, false)) {
            val pm = Reflect.readAny(ent, "mPhotoMeta")
            if (pm != null && Reflect.readBool(pm, "photoAiAnalyze") == true) { hit("ai:analyzeFlag", qp); return true }
            // ★ quick 路径：首屏走这里，deep 兜底太晚（实测首屏 L7 批次到 deep 仅 6ms，
            // UI 已上屏）。与 deep 同语义：AI 声明文字拦，「虚构演绎」类放行。
            val disObQ = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null }
            if (disObQ != null) {
                val cq = try { Reflect.readAny(disObQ, "content") as? String } catch (_: Throwable) { null }
                if (cq.isNullOrBlank()) { hit("ai:disclaimerPending", qp); return true }
                if (CfhUtil.isAiDisclaimerText(cq)) { hit("ai:disclaimer \"${cq.take(18)}\"", qp); return true }
            }
            val cm = Reflect.readAny(ent, "mCommonMeta")
            val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
            if (cap.contains("ai生成", true) || cap.contains("AI创作") || cap.contains("疑似") || cap.contains("AIGC") || cap.contains("人工智能")) { hit("ai:capText", qp); return true }
        } else {
            if (CfhState.quickDebugCount < 10) { CfhState.quickDebugCount++; Logger.d("QDBG aiOff pm.hasDis=${try { Reflect.readAny(Reflect.readAny(ent, "mPhotoMeta"), "mDisclaimergeMessageV2") != null } catch (_: Throwable) { false }}") }
        }
        if (Prefs.bool(Prefs.K_FLT_DRAMA, true)) {
            if (Reflect.readAny(ent, "mKwAppNativeDrama") != null) { hit("drama:kwApp", qp); return true }
            if (Reflect.readAny(ent, "mNovelDrama") != null) { hit("drama:novel", qp); return true }
            if (Reflect.readAny(ent, "mLongToShortDrama") != null) { hit("drama:longShort", qp); return true }
        }
        return false
    }

    fun decideBySig(cache: java.util.concurrent.ConcurrentHashMap<String, Boolean>, qp: Any, decide: () -> Boolean): Boolean {
        if (!Prefs.bool(Prefs.K_PERF_FCACHE, true)) return decide()
        // ★ sigIdCache 以 System.identityHashCode 为键：GC 后 hash 可被新对象复用，
        // 无限增长的旧条目既泄漏内存又可能串判（新对象命中旧 hash 的结果）。
        // 定期清空 + 清 sig 缓存时连带清，代价只是一次重判定
        if (CfhState.sigIdCache.size > 8000) CfhState.sigIdCache.clear()
        CfhState.sigIdCache[System.identityHashCode(qp)]?.let { return it }
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
            cache[sig]?.let { CfhState.sigIdCache[System.identityHashCode(qp)] = it; return it }
            if (cache.size > 3000) { cache.clear(); CfhState.sigIdCache.clear() }
            // miss：主线程先跑 quickOfficialDirty（微秒级官方标记），命中即拦；
            // 未命中入后台线程跑全量判定，先放行
            if (quickOfficialDirty(qp)) {
                cache[sig] = true
                return true
            }
            if (CfhState.asyncDecidePending.add(sig)) {
                CfhState.cleanExecutor.execute {
                    try {
                        val r = decide()
                        cache[sig] = r
                        CfhState.sigIdCache[System.identityHashCode(qp)] = r
                        CfhState.asyncDecidePending.remove(sig)
                    } catch (_: Throwable) { CfhState.asyncDecidePending.remove(sig) }
                }
            }
            return false
        }
        return decide()
    }

    fun cachedDecide(cache: MutableMap<Any, Boolean>, qp: Any, decide: () -> Boolean): Boolean {
        if (!Prefs.bool(Prefs.K_PERF_FCACHE, true)) return decide()
        if (cache.size > 3000) cache.clear()
        val hit = cache[qp]
        if (hit != null) {
            if (CfhState.fcacheDiag < 5) { CfhState.fcacheDiag++; Logger.d("fcache hit (${cache.size})") }
            return hit
        }
        val r = decide()
        cache[qp] = r
        return r
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

    fun hit(reason: String, qp: Any) {
        try {
            CfhState.filterHitStats.merge(reason, 1, Int::plus)
            CfhState.hitTotal++
            // ★★★ 判脏即入黑名单（2026-09 用户报「开头第一/二条仍是 AI」）：
            // 实证 probe11 —— T0 在 44.748 已把「炸薯条」从数据源删掉，但屏幕在
            // 46.665 就画出了作者名（imm ... t=@欣欣特效），frag M 直到 47.073 才
            // 再次判脏并替换 —— 晚了 0.4 秒，用户看到的就是这个窗口。
            // 说明该条被 rerank/pager 的**另一条引用**重新供给（数据源删除删不到它）。
            // 这里按 photoId 记黑名单：判过一次脏的 id 永久为脏，后续无论从哪条路径
            // 回来（frag M / pager i / T0(i) 替换源 / 真源重建）都在毫秒级判脏，
            // 不再依赖「哪条路径先看到它」。
            CfhProbe.readPhotoId(qp)?.let { id ->
                if (id.isNotBlank()) {
                    if (CfhState.dirtyPhotoIds.size >= 512) CfhState.dirtyPhotoIds.clear()
                    CfhState.dirtyPhotoIds.add(id)
                }
            }
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
        CfhState.sigIdCache.clear()
        CfhState.asyncDecidePending.clear()
        // ★ 黑名单同样必须随开关变化清空：它是「按旧开关组合判出的脏」，
        // 不清会导致用户关掉某类过滤后，已入黑名单的项仍被过滤（开关「关不掉」）。
        CfhState.dirtyPhotoIds.clear()
    }

}
