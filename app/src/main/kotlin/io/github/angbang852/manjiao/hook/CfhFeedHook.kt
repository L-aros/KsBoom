package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// �?ContentFilterHook 娣辨媶绗簲姝ワ細鏁版嵁�?鍝嶅簲閽╋紙2026-09 S3锛夈�?
// 缃戠粶鍝嶅簲/缂撳�?Milano 瀹瑰�?PageList/knhb.T0/LiveStreamFeed 鏋勯�?DataSource/ViewModel
// 鐨勫彇鏁板叆鍙ｆ嫤鎴?+ 鍐峰惎鍔?BOOTFLUSH銆傝繃婊ゅ姩浣滃叏閮ㄥ�?CfhClean/CfhWash/CfhDecide 绛夈�?
object CfhFeedHook {

    // ==================== LAWATCH锛歭.a 鍐欏叆鐩戞帶 + 鍓嶇疆鍒犻櫎 ====================
    // 鐥涚偣锛氱湡婧愭竻娲楋紙sanitize deep:l.a锛夊凡鐢熸晥浣嗘椂鏈烘櫄鈥斺€旂洿鎾厛�?l.a �?pager
    // 娓叉煋涓婂睆锛堢敤鎴峰厛鐪嬪埌锛夆啋 V0 getter 瑙﹀�?�?蹇収鍛戒腑鎵嶈ˉ鍒犵湡婧愶紙鐢婚潰鍗′綇锛夈�?
    // B(List) hook 瀹炶瘉鏈懡涓悎骞惰矾�?�?蹇墜璧扮鏈夊唴閮ㄦ柟娉曞�?l.a�?
    // l.a �?CopyOnWriteArrayList锛屽閮ㄥ啓鍏ュ繀�?add/addAll 绯绘柟娉?�?鍏ㄥ�?hook 杩欎�?
    // 鏂规�?+ 韬唤姣斿锛坱his === laRef锛夛紝鍗冲彲 100% 瑕嗙洊鎵€鏈夊啓鍏ヨ矾寰勶細
    //  1) addAll/addAllAbsent锛歜efore 闃舵鐩存帴杩囨护鍙傛暟 Collection锛堝墠缃垹闄わ�?
    //     鑴忛」鏍规湰杩涗笉浜?l.a锛屾棭浜庢覆鏌擄�?
    //  2) add/addIfAbsent锛歛fter 闃舵绔嬪嵆绉婚櫎锛堝悓涓€璋冪敤鏍堝唴锛屼粛鏃╀簬娓叉煋�?
    //  3) 姣忔鍛戒腑鎵撳嵃瀹屾暣璋冪敤鏍堬紙LAWATCH stack锛夆�?瀹氫綅绉佹湁鍚堝苟鏂规硶
    // 鎬ц兘锛欳opyOnWriteArrayList 鏄儹鐐圭被锛宑allback 棣栬�?identity 姣斿寮€閿€鍙拷鐣ワ�?
    // 瀹氫綅鍒扮鏈夎矾寰勫悗搴旂Щ闄ゆ湰鎻掓々锛屾敼涓虹簿鍑?hook 鍚堝苟鏂规硶



    // 绮剧‘鍒ゅ畾锛氫�?feed 鏁版嵁瀵硅薄锛圦Photo 鏈�?/ mEntity �?Feed 鐨勫寘瑁咃級鍙備笌鍒よ剰�?
    // 鏁欒�?2026-09-08锛氬娉涘垽瀹氭�?FragmentManager.mAdded銆乴.c Presenter 鍥炶皟琛ㄣ€?
    // mBackPressInterceptors銆乻lideprocess 杩借釜鍣ㄥ叏璇垹锛堝尶鍚嶇被 q$a/d$c 涓嶅�?
    // "Presenter" 瀛楁牱瀹堝崼澶辨晥锛夆€斺€旈�?feed 瀵硅薄涓€寰嬩笉�?

    // ==================== LAWATCH end ====================

    // �?keep-latest 鍘婚噸锛堝�?2026-09锛夛細宸叉帓�?鎵ц涓椂鏂拌Е鍙戠洿鎺ヤ涪寮冿紝闃叉粴鍔ㄦ湡
    // rerank.d.j 楂橀缁曟祦鎶婂叏鍥?BFS 浠诲姟鍦ㄥ崟绾跨�?executor 閲岀Н�?
    // �?韬唤寮曠敤鏁扮粍锛歨ook 鍥炶皟鐑矾寰勭鐢ㄤ换浣曢泦鍚堢被锛圫etFromMap.contains 鍐呴�?
    // 浼氳Е鍙戝叾浠栬 hook 鐨勯泦鍚堟柟�?�?閫掑綊椋庢毚瀹炶瘉锛夛紝鍙敤绾?=== 鏁扮粍閬嶅巻


    // �?TRUEWATCH锛氱湡婧?h.m.p.a 鍐欐柟娉?watch锛堣韩浠芥瘮瀵癸級鈥斺€斿墠缃垹闄よ剰�?+ 鎵撹皟鐢?
    // 鏍堝畾浣嶅揩鎵嬪悎骞剁鏈夋柟娉曘€傚瀹為檯杩愯鏃剁被鎸?add/addAll/set 绯伙紙ArrayList �?
    // add(E)/add(int,E)/addAll(Collection)/addAll(int,Collection)/set(int,E)�?

    // �?LAFIND锛氳�?QPhoto 韬唤鍙嶆煡鐪熸簮瀛楁鈥斺€擝FS 閬嶅�?VM+adapter 瀵硅薄鍥撅紙娣卞�?6锛夛�?
    // 鎵惧嚭銆屽摢浜涘瓧娈电殑 List/鏁扮粍浠ヨ韩浠界浉鍚屽寘鍚鍏冪礌銆嶏紝鍛戒腑�?allowEmpty 娓呯悊銆?
    // 鏍归泦锛歷mRef锛圴M 鏁版嵁婧愶級+ adpRef/adpRefs锛坧ager adapter鈥斺€攔erank 鎶婄洿鎾彃�?
    // adapter 鏁版嵁闆嗭紝涓嶅�?VM 鐪熸簮閲岋紝婕忔嫤瀹炶�?2026-09-08锛夈�?
    // 鍚庡彴绾跨▼璺戯紙ANR 绾㈢嚎锛夛紝2.5 绉掕妭娴?



    internal fun hookFeedResponse(xp: XposedInterface, cl: ClassLoader) {
        val respNames = arrayOf(
            "com.yxcorp.gifshow.detail.slideplay.hotphoto.network.SlideHotPhotoResponse",
            "com.yxcorp.gifshow.feed.response.PhotoResponse",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.feed.CommonFeedSlideResponse",
            // �?补漏�?026-09-25 主人报「第二个源头没堵」）：广�?缓存重灌的实际载体—�?
            //   VISDEL-L 实证广告条目挂在 mQPhotos@HomeFeedResponse、mFeeds@CoronaDetailFeedResponse�?
            //   这两个响应类此前不在 hook 名单，广告引擎从这里回放=「重灌」的入口�?
            "com.yxcorp.gifshow.model.response.feed.HomeFeedResponse",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.CoronaDetailFeedResponse"
        )
        for (rn in respNames) {
            val rc = Reflect.findClass(rn, cl) ?: continue
            Logger.d("hookFeedResp: $rn")
            var c: Class<*>? = rc
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    Logger.d("  resp field: ${f.name} type=${f.type.name}")
                }
                for (m in c.declaredMethods) {
                    val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                    if (isRetList || hasListParam) {
                        Logger.d("  resp method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName} static=${java.lang.reflect.Modifier.isStatic(m.modifiers)}")
                        Logger.safe("hookResp.${rn}.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("resp.${rn}.${m.name}").intercept { chain ->
                                // ★★ 白名单优先（2026-09-26 用户定稿「判正常才放行」）�?
                                //   必须�?filterListArgs（黑名单：判脏才删）之前执行 —�?
                                //   白名单是更强的约束：只放行明确干净的�?
                                //   默认关闭（whiteListEnabled=false），关闭时零开销�?
                                try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) {}
                                // \u2605\u2605 \u767d\u540d\u5355\u4f18\u5148\uff082026-09-26\uff09
                                 try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) {}
                                val result = chain.proceed()
                                // ★★�?白名单（返回值路径）—�?2026-09-26 定位修正�?
                                //
                                //   ## 为什么这里才�?*真正的入�?*
                                //
                                //   实测 ENTRYTRACE 探针（记录每条内容首次进入判定的调用链）�?
                                //   ```
                                //   68/69 条内容的入口都是同一条链�?
                                //   shouldFilterFeed �?CfhClean.filterResult �?hookFeedResponse
                                //   ```
                                //   �?内容是从**响应返回�?*进来的，不是�?`chain.args`（入参）�?
                                //     此前白名单挂在入参上 �?只挡�?30 次，而删除动作有 438 次�?
                                //
                                //   ## 必须在这里、且�?filterResult 之前
                                //
                                //   `filterResult` 是黑名单（判脏才删）；白名单更严（只放行干净的）�?
                                //   所以白名单必须先判。判脏的直接挡下，后�?filterResult 自然无脏可删�?
                                //
                                //   ## 注意 result 可能�?List 的包�?
                                //
                                //   `filterResult` 内部判的�?`result is MutableList<*>`�?
                                //   故此处保持同一判据（`as? List` 覆盖 MutableList）�?
                                //
                                // ★★�?网络层耗时埋点�?026-09-26 用户要求「加快拉取服务器数据速度」）�?
                                //
                                //   要区分两�?�?�?
                                //     · **快手自己�?*（请求往返时间长）⇒ 模块改不�?
                                //     · **模块拖慢**（我们的处理耗时）⇒ 可优�?
                                //
                                //   本埋点分别记录：
                                //     · `tBefore` �?`chain.proceed()` 前的时刻
                                //     · `proceedCost` = 快手自己处理+网络往返的耗时
                                //     · `ourCost` = 我们的白名单处理耗时
                                try {
                                    val tBefore = System.currentTimeMillis()
                                    val result = chain.proceed()
                                    val tAfterProceed = System.currentTimeMillis()
                                    // �?探针：记录返回值类型（用户报「青春磕颜家」走 getItems 需确认�?
                                    if (result is List<*> && CfhState.respRetLog < 60) {
                                        CfhState.respRetLog++
                                        Logger.evidence(
                                            "RESPRET",
                                            "${rn.substringAfterLast('.')}.${m.name} " +
                                                "size=${result.size} 类=${result.javaClass.name} " +
                                                "可变=${result is MutableList<*>}"
                                        )
                                    }
                                    if (result is List<*>) {
                                        @Suppress("UNCHECKED_CAST")
                                        CfhClean.filterWhitelist(result as List<Any?>)
                                        // ★★★ v13.70 空响应原地补池（方案 A，2026-09-30）
                                        //   ## 触发条件：**被清空**
                                        //   一键「全判脏」的批次被 `filterWhitelist` 删干净后
                                        //   长度归 0，快手拿到 0 条响应 ⇒ 报「无网络」⇒ 不再
                                        //   请求下一页 ⇒ 用户滑不动。这里把池里的干净条目原地
                                        //   补回去，让快手始终拿到「有货」的响应。
                                        //   ## 为什么只补「空」这一种情况
                                        //   列表非空时快手的翻页逻辑正常（交接 §3.3 自愈回路），
                                        //   不需要动 ⇒ 改动面越小越安全。只填「归零」这一格。
                                        //   ## 首页零补位（用户硬规则）
                                        //   沿用 RENDERFILL 同一条判据：3 秒内没出现过 knhb
                                        //   T0/E1（`lastHomeChannelMs`）才算「不在首页」。
                                        //   首页即使被清空也**绝不补**，避免污染发现页。
                                        //   ## 为什么用 `rn`（类名）入日志
                                        //   目前只知道 `HomeFeedResponse`（发现页）会走到这；
                                        //   精选页走哪条数据源尚未实测 ⇒ 日志里带上
                                        //   「类名.方法名」，用来确认精选页到底有没有经过本
                                        //   hook，避免再次猜错位置。
                                        try {
                                            if (RESPFILL &&
                                Prefs.bool(Prefs.K_FLT_HOMEREFILL, false)
                            ) {
                                                val mutable = result as? MutableList<Any?>
                                                if (mutable != null && mutable.isEmpty()) {
                                                    val onHomeNow = System.currentTimeMillis() -
                                                        CfhState.lastHomeChannelMs < 3000L
                                                    val qc = CfhState.qpClassRef
                                                    if (!onHomeNow && qc != null) {
                                                        // ★★★ v13.80 首屏「无网络」专治（真机症状 2026-09-29）。
                                                        //   症状：冷启动进精选页先显示「没有网络」，
                                                        //   **点一下刷新**过一会才有视频。
                                                        //   实测时序 ⇒ 首屏那次请求比池恢复**只早 10ms**：
                                                        //     [RESPFILL]  空响应 池=0        ...672016
                                                        //     [POOLSTORE] 重启恢复 条目=1 池=1 ...672026
                                                        //   所以在「池为空 + 恢复还没跑完」时**等它最多 1.2 秒**，
                                                        //   把首屏救回来。只在恢复完成前生效，之后永不再等。
                                                        //   本钩子跑在快手网络线程（rtf-api-high-*），不阻塞主线程。
                                                        if (CfhPoolStore.restorePending() &&
                                                            synchronized(CfhState.cleanPool) { CfhState.cleanPool.isEmpty() }
                                                        ) {
                                                            val t0 = System.currentTimeMillis()
                                                            CfhPoolStore.awaitRestore(1200L)
                                                            if (io.github.angbang852.manjiao.util.RateLimiter.allow("RESPFILL-WAIT", 10)) {
                                                                val n2 = synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }
                                                                Logger.evidence(
                                                                    "RESPFILL",
                                                                    "★首屏等池恢复 ${System.currentTimeMillis() - t0}ms 池=$n2"
                                                                )
                                                            }
                                                        }
                                                        // ★★★ v13.82 方案 C（用户批准）：**池不够就静默预热首页** ——
                                                        //   模块自己把主 pager 切到首页、让快手创建首页 Fragment 并加载，
                                                        //   PV2-POOL 把干净条目灌进池，再切回原来那页。
                                                        //   实测依据：冷启动只停精选页时 PV2-POOL=0 / HOMEPULL
                                                        //   「主页引用未就绪」连刷，而手点一次首页后立刻
                                                        //   `[HOMEPULL] 主页 VM 分页 池=1 → 池=13`。
                                                        //   这就是「自动拉，不用主人手动滑首页」的落点。
                                                        //   内部有低水位 + 冷却期 + 已在首页则跳过三重闸，后台线程执行。
                                                        CfhHomeWarm.maybeWarm(
                                                            synchronized(CfhState.cleanPool) { CfhState.cleanPool.size },
                                                            "首屏空响应"
                                                        )
                                                        val picked = ArrayList<Any?>()
                                                        val snap = synchronized(CfhState.cleanPool) {
                                                            ArrayList(CfhState.cleanPool)
                                                        }
                                                        for (cand in snap) {
                                                            if (picked.size >= RESPFILL_MAX) break
                                                            if (cand == null || !qc.isInstance(cand)) continue
                                                            if (mutable.any { it === cand }) continue
                                                            picked.add(cand)
                                                        }
                                                        if (picked.isNotEmpty()) {
                                                            mutable.addAll(picked)
                                                            // ★★★ v13.71 投放即出队（2026-09-30 用户定案）
                                                            //   这批内容已经塞进「交给快手的那份响应」=
                                                            //   等于上屏了 ⇒ 池里对应的就该消失。
                                                            //   这样池才会掉到 50 以下，`ensurePoolSupply`
                                                            //   的水位门才会真正开始补货（在此之前它
                                                            //   一次都没触发过，因为池永远满 50）。
                                                            //   `cleanPoolIds` 去重标记保留 ⇒ 永不再入池。
                                                            val drained = try {
                                                                CfhState.consumeClean(picked)
                                                            } catch (_: Throwable) { 0 }
                                                            if (io.github.angbang852.manjiao.util
                                                                    .RateLimiter.allow("RESPFILL", 60)
                                                            ) {
                                                                Logger.evidence(
                                                                    "RESPFILL",
                                                                    "★空响应原地补池 +${picked.size} 出队=$drained " +
                                                                        "${rn.substringAfterLast('.')}.${m.name} " +
                                                                        "池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                                                                )
                                                            }
                                                        } else if (io.github.angbang852.manjiao.util
                                                                .RateLimiter.allow("RESPFILL-EMPTYPOOL", 20)
                                                        ) {
                                                            // 池里没有可用条目（池空 / 类型不匹配）
                                                            // —— 这一格必须可见，否则「补了但没补上」
                                                            // 会和「压根没触发」混在一起没法区分
                                                            Logger.evidence(
                                                                "RESPFILL",
                                                                "★空响应但池内无可用条目 " +
                                                                    "${rn.substringAfterLast('.')}.${m.name} " +
                                                                    "池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (_: Throwable) {}
                                        // ★★�?发现页干净内容登记�?026-09-28「首页池接精选页」用户方案）�?
                                        //   只有 HomeFeedResponse（发现页）的 WHITE 内容进干净池�?
                                        //   · filterWhitelist 就地清理�?result 剩的都是 WHITE �?直接遍历登记�?
                                        //   · noteClean 内部要求 QPhoto 直接实例（qpClassRef 判定），
                                        //     �?QPhoto（包�?其它类型）自动拒绝，安全�?
                                        //   · 同城/精�?关注等其它响应不走此分支 �?不进�?
                                        //     （用户实测补位接回了同城数据，来源收窄到发现页）�?
                                        if (rn.contains("HomeFeedResponse")) {
                                            // ★★★ v13.46 发现页身份确认（2026-09-30 用户
                                            //   「你只拉发现页的数据」）：
                                            //   发现页响应到达的**那一刻**，当前 vmRef 就是
                                            //   发现页的 VM ⇒ 用它更新 homeVmRef/homeDsRef。
                                            //   主动拉取（池<50）因此永远只拉发现页，不会被
                                            //   用户切到关注页/同城页时创建的 Fragment 覆盖。
                                            try {
                                                val curVm = CfhState.vmRef
                                                if (curVm != null) {
                                                    CfhState.homeVmRef = curVm
                                                    val ds2 = Reflect.callMethod(curVm, "getDataSource")
                                                    if (ds2 != null) CfhState.homeDsRef = ds2
                                                    // ★★★ v13.72 登记成功诊断（2026-09-30）：
                                                    //   看清「到底登记了哪个 VM」——一直怀疑
                                                    //   homeVmRef 被抓成了精选页/详情页的 VM
                                                    //   （那样首页收割就永远 100% 脏）。
                                                    if (io.github.angbang852.manjiao.util
                                                            .RateLimiter.allow("HOMEREF-OK", 60)
                                                    ) {
                                                        Logger.evidence(
                                                            "HOMEREF",
                                                            "★登记 homeVmRef VM=${curVm.javaClass.simpleName} " +
                                                                "ds=${ds2 != null}"
                                                        )
                                                    }
                                                } else if (io.github.angbang852.manjiao.util
                                                        .RateLimiter.allow("HOMEREF-NULL", 60)
                                                ) {
                                                    // ★★★ v13.72 关键诊断（2026-09-30 真机实证）：
                                                    //   `HomeFeedResponse` 到达了、却登记不上 ——
                                                    //   因为 `CfhState.vmRef`（强引用）是 null。
                                                    //   而 `currentVmRef()` 可能**非** null（它走
                                                    //   CurrentPhotoHook 自己的 WeakReference）⇒
                                                    //   两者不同源，正是「池永远补不上」的真凶。
                                                    val curPhotoVm = try {
                                                        CurrentPhotoHook.currentVmRef()
                                                    } catch (_: Throwable) { null }
                                                    Logger.evidence(
                                                        "HOMEREF",
                                                        "★★HomeFeedResponse 到达但 vmRef=null ⇒ homeVmRef 登记不上 " +
                                                            "homeVmRef=${CfhState.homeVmRef != null} " +
                                                            "currentVmRef=${curPhotoVm != null}" +
                                                            (if (curPhotoVm != null)
                                                                "(${curPhotoVm.javaClass.simpleName})" else "")
                                                    )
                                                }
                                            } catch (_: Throwable) {}
                                            // ★★�?v13.38 删除「首页请求器配对晋升」（2026-09-30 用户定案）：
                                            //   pendingHomeReq �?homeReqRef 晋升链的唯一用途是
                                            //   「池空主动调首页请求�?load() 拉数据」——池共享后该
                                            //   逻辑多余，整条已删（triggerHomeLoadMore 已移除）�?
                                            // �?开关（2026-09-28）：关闭 flt_homerefill �?
                                            //   不登记干净池、不配对晋升 —�?整条补位链路停用�?
                                            if (Prefs.bool(Prefs.K_FLT_HOMEREFILL, false)) {
                                                try {
                                                    var regOk = 0
                                                    for (e in result) {
                                                        if (e == null) continue
                                                        try {
                                                            // ★★★ v13.46 包装对象提取（2026-09-30
                                                            //   POOLREJ[TYPE] 实证根因）：
                                                            //   发现页响应的 items 元素**不是 QPhoto
                                                            //   直接实例**（是包装对象）⇒ noteClean 的
                                                            //   类型校验直接拒 ⇒ 「HOMEPOOL 批size=4
                                                            //   成功=0」、池永远收不到发现页内容。
                                                            //   与 v13.35 渲染层同一处理：先 findQpInObject
                                                            //   提取，提不出来才用原对象（走原校验）。
                                                            val qe = if (CfhState.qpClassRef?.isInstance(e) == true) e
                                                                else (try { CfhProbe.findQpInObject(e) } catch (_: Throwable) { null } ?: e)
                                                            if (CfhState.noteClean(qe)) regOk++
                                                        } catch (_: Throwable) {}
                                                    }
                                                    // ★★�?池水位诊断（2026-09-29 v13.19b）：RESPRET 恢复了但�?0�?
                                                    //   需确认 noteClean 登记是否成功（类型校�?readPhotoId）�?
                                                    if (CfhState.cleanPoolDiagLog < 40) {
                                                        CfhState.cleanPoolDiagLog++
                                                        val pool = synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }
                                                        Logger.evidence(
                                                            "HOMEPOOL",
                                                            "首页登记 批size=${result.size} 成功=$regOk 池=$pool"
                                                        )
                                                    }
                                                } catch (_: Throwable) {}
                                            }
                                        }
                                    }
                                    val tEnd = System.currentTimeMillis()
                                    if (CfhState.netCostLog < 60) {
                                        CfhState.netCostLog++
                                        Logger.evidence(
                                            "NETCOST",
                                            "${rn.substringAfterLast('.')}.${m.name} " +
                                                "快手耗时=${tAfterProceed - tBefore}ms " +
                                                "模块耗时=${tEnd - tAfterProceed}ms " +
                                                "size=${(result as? List<*>)?.size ?: -1}"
                                        )
                                    }
                                    return@intercept result
                                } catch (_: Throwable) {}
                                result
                            }
                        }
                    }
                }
                c = c.superclass; lvl++
            }
        }
    }


    // ★★�?首页信息流请求器捕获�?026-09-28「请求服务器数据的动作」用户方案）�?
    //
    // 用户原话：「快手应该是有个请求服务器数据的一个动作啊�?
    //           「首页发现页往下滑，视频数据源源不断的出来」�?
    //
    // ## 结论�?9980 逆向实证 kik/o0.java）：
    //   kik.o0<PAGE,MODEL> 是快�?*分页请求器基�?*（kik.f extends kik.o0�?
    //   bai.a extends kik.f<HomeFeedResponse, QPhoto> 即首页发现页请求器）�?
    //     · load()      —�?加载下一页（发现页下滑就是这个在持续拉服务器�?
    //     · hasMore()   —�?是否还有下一�?
    //     · isLoading() —�?是否请求在�?
    //     · T1()        —�?实际发起网络请求�?Observable 工厂（子类实现）
    //     · Y1()        —�?响应收数（PAGE �?List<MODEL>�?
    //   首页发现页的具体请求�?= bai.a（泛�?PAGE=HomeFeedResponse）�?
    //
    // ## 为什么之�?REFILL 无效�?
    //   triggerSafeLoadMore �?findLoadTarget(knhbInst) 找到�?dnh.q1 �?
    //   **精选页**数据源，load() 拉回来还是脏�?�?REFILL �?STAT 仍全拦�?
    //   正确做法：捕�?*首页**请求器（bai.a），精选页池空时调�?load() �?
    //   快手向服务器要发现页下一�?�?HomeFeedResponse hook 登记 WHITE �?
    //   干净�?�?补精选页。这�?REFILL（拉精选页自己的脏池）正确得多�?
    //
    // ## 配对晋升（防串线）：
    //   不能�?load() 就存 —�?精�?详情页请求器也继�?kik.o0。这里只�?
    //   load() �?thisObject 暂存 pendingHomeReq；等 HomeFeedResponse 响应
    //   到达（hookFeedResponse 分支）才晋升�?homeReqRef。时序配对保�?
    //   存的必然是「引发首页响应」的那个请求器�?
    // ���� v13.38 ɾ�� hookHomeRequester��2026-09-30 �û���������
    //   ���ǹ����ģ�cleanPool Ψһ������ҳ�����ĸɾ����ݱ����Զ����� ?
    //   ���ؿ���������ҳ���ݡ�������·������������ �� load() �� pendingHomeReq
    //   ��Խ������������࣬�Ҵ������ã����� load ����Ū�������������ؾ�ѡҳ
    //   AI �̾磩�����õ����Ƴ���ContentFilterHook v13.38����
    //   ԭ������ 100 �У�smk.f/kik.o0/rmk.o0 ����̽�� �� load() hook ��
    //   pendingHomeReq �ݴ� �� HOMEQ-DIAG ��� ���� ȫ�����ٴ��ڡ�


    internal fun hookCacheClasses(xp: XposedInterface, cl: ClassLoader) {
        val cacheNames = arrayOf(
            "com.yxcorp.gifshow.feed.cache.home.HomeResponseEvictingQueueCorrector",
            "com.yxcorp.gifshow.feed.cache.home.HomeResponseLiveFilter",
            "com.yxcorp.gifshow.feed.cache.home.HomeResponseCache"
        )
        for (cn in cacheNames) {
            val cc = Reflect.findClass(cn, cl) ?: continue
            Logger.d("hookCache: $cn")
            var c: Class<*>? = cc
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (m in c!!.declaredMethods) {
                    val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                    if (isRetList || hasListParam) {
                        Logger.d("  cache method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                        Logger.safe("hookCache.${cn}.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("cache.${cn}.${m.name}").intercept { chain ->
                                // \u2605\u2605 \u767d\u540d\u5355\u4f18\u5148\uff082026-09-26\uff09
                                 try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) {}
                                val result = chain.proceed()
                                try { if (result is List<*>) CfhClean.filterWhitelist(result) } catch (_: Throwable) {} // ★ 白名单替代黑名单
                                result
                            }
                        }
                    }
                }
                c = c.superclass; lvl++
            }
        }
    }

    // Milano �?feed 鏋舵瀯锛氱洿�?hotphoto)/AI(airecommendslide)/瑙嗛�?commonfeedslide) 鍚勬槸鐙珛瀹瑰櫒锟?
    // 瀹瑰櫒绫昏娣锋穯锟?a锛岃繖閲屾帰娴嬪叾鍙栨暟鏂规硶锟?hook 杩囨护锟?

    internal fun hookMilanoContainers(xp: XposedInterface, cl: ClassLoader) {
        val names = arrayOf(
            "com.yxcorp.gifshow.detail.slideplay.hotphoto.container.a",
            "com.yxcorp.gifshow.detail.slideplay.airecommendslide.a",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.a"
        )
        for (cn in names) {
            val cc = Reflect.findClass(cn, cl) ?: continue
            hookMilanoContainer(xp, cc, cn)
        }
    }
    private fun hookMilanoContainer(xp: XposedInterface, cc: Class<*>, cn: String) {
        synchronized(CfhState.hookedMilanoContainers) { if (!CfhState.hookedMilanoContainers.add(CfhUtil.hookKey(cc))) return }
        Logger.d("hookMilano: $cn")
        var c: Class<*>? = cc
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 4) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val ft = f.type.name
                if (ft.contains("List") || ft.contains("Collection") || ft.contains("QPhoto") || ft.contains("Feed")) {
                    Logger.d("  milano field: ${f.name} type=${ft}")
                }
            }
            for (m in c.declaredMethods) {
                val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                if (isRetList || hasListParam) {
                    Logger.d("  milano method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                    Logger.safe("hookMilano.${cn}.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("milano.${cn}.${m.name}").intercept { chain ->
                            // \u2605\u2605 \u767d\u540d\u5355\u4f18\u5148\uff082026-09-26\uff09
                             try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) {}
                            val result = chain.proceed()
                            try { if (result is List<*>) CfhClean.filterWhitelist(result) } catch (_: Throwable) {} // ★ 白名单替代黑名单
                            // [已移�?2026-09-26] filterResponseFields（字段级清洗，白名单无等价物；脏项由列表级白名单覆盖�?
                            result
                        }
                    }
                }
            }
            c = c.superclass; lvl++
        }
    }

    // Milano 鏁版嵁锟?= �?PageList锛堝弻鍚戝垎椤靛垪琛級銆俬ook 鍏跺彇鏁版柟娉曪�?
    // 鍦ㄦ簮澶存妸鐩存�?AI/骞垮�?鍓ч泦 椤规浛鎹㈡垚骞插噣椤规垨杩囨护鎺夛拷?

    internal fun hookPageLists(xp: XposedInterface, cl: ClassLoader) {
        val names = arrayOf(
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.CommonFeedSlideBidirectionalPageList",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.PostCommonFeedSlidePageList",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.PostLocalFeedSlidePageList",
            "com.yxcorp.gifshow.detail.slideplay.airecommendslide.slide.network.AiRecommendSlidePageList"
        )
        for (cn in names) {
            val cc = Reflect.findClass(cn, cl)
            // �?类存在性留痕（2026-09-26）：此前 `findClass` 返回 null 时静�?continue�?
            //   导致「类名过时」和「hook 失败」无法区分。实�?4 个类**一个都没找�?*�?
            if (cc == null) {
                if (CfhState.pageListMissLog < 20) {
                    CfhState.pageListMissLog++
                    Logger.evidence("PLIST-MISS", "类不存在: $cn")
                }
                continue
            }
            if (CfhState.pageListMissLog < 40) {
                CfhState.pageListMissLog++
                Logger.evidence("PLIST-HIT", "类已找到: $cn")
            }
            val already = synchronized(CfhState.hookedPageLists) { !CfhState.hookedPageLists.add(CfhUtil.hookKey(cc)) }
            if (already) continue
            Logger.d("hookPageList: $cn")
            var c: Class<*>? = cc
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (m in c!!.declaredMethods) {
                    val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    val isIntP = m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
                    val nonPrimRet = !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != java.lang.String::class.java
                    if ((isIntP && nonPrimRet) || isRetList) {
                        Logger.d("  plist method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                        Logger.safe("hookPageList.${cn}.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("plist.${cn}.${m.name}").intercept { chain ->
                                // \u2605\u2605 \u767d\u540d\u5355\u4f18\u5148\uff082026-09-26\uff09
                                 try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) {}
                                val r = chain.proceed()
                                try { if (r is List<*>) CfhClean.filterWhitelist(r) } catch (_: Throwable) {} // ★ 白名单替代黑名单
                                // 婧愬ご鎷︽埅锛氬崟椤硅繑鍥炴槸鐩存挱/骞垮憡鐩存帴杩斿�?null锛岃 adapter 璺宠繃璇ヤ綅缃紙涓嶈繘淇℃伅娴侊級
                                try {
                                    if (r != null) {
                                        val q = CfhProbe.findQpInObject(r)
                                        if (q != null && CfhDecide.shouldFilterFeed(q)) {
                                            if (CfhState.plistSkipDiag < 30) { CfhState.plistSkipDiag++; Logger.d("plist skip ${m.name} (${CfhUtil.readCaption(q)?.take(15)})") }
                                            return@intercept null
                                        }
                                    }
                                } catch (_: Throwable) {}
                                r
                            }
                        }
                    }
                }
                c = c.superclass; lvl++
            }
        }
    }

    // �?闃查噸澶嶈棰戯細refresh锛圔OOTFLUSH/prefetch锛夐噸鎷夌涓€椤靛彲鑳藉甫鍥炲凡渚涚粰杩囩殑瑙嗛�?
    // 宸蹭緵缁?photoId 鍘嗗彶锛圠RU 涓婇�?500�? QPhoto鈫抪hotoId 韬唤缂撳瓨锛堟瘡瀵硅薄鍙弽灏勪竴娆★級�?
    // 鍘婚噸鍙綔鐢ㄤ簬瀹炴祴鐨勬暟鎹浇鑽疯矾寰勶細T0 �?args[2]锛坲pdate 鎵规锛? E1 �?args[0]�?
    // 鍒犳壒娆￠噷 photoId 宸插湪鍘嗗彶涓殑椤癸紱鎶ゆ爮鍚?filterListArgs锛堝垹鍚庤嚦灏戠暀 1 鎴栧師鏈?�?锛夈�?
    // �?getPhotoId Method 缂撳瓨锛堝惈璐熺紦瀛橈級锛氬寘瑁呯被鏃犳鏂规硶鏃跺師鍏堟瘡鍏冪礌姣忔鎶?
    // NoSuchMethodException锛堟爤濉厖鏋佽吹锛夛紝E1 澶ф壒娆′笅绾�?CPU
    // �?楗ラタ璁℃暟鍘熷瓙鍖栵細hook 鍥炶皟璺戝湪浠绘剰绾跨▼锛岄潪鍘熷瓙 ++/娓呴浂涓㈣鏁颁細璁?
    // 銆岃繛缁?4 鎵归ゥ楗库啋娓呭巻鍙茶嚜鎰堛€嶅欢杩熻Е鍙戯紙鍔熻兘鎬ц鏁帮紝闈炶瘖鏂級

    internal fun hookKnhbT0(xp: XposedInterface, cl: ClassLoader) {
        if (CfhState.knhbT0Hooked) return
        // �?版本自适应解析�?026-09）：混淆类名 `knh.b` �?50218 版本丢失
        //（《快手版本适配文档�?.3 节实测记录）。原写法是穷举候选名
        //�?knh.b" / "knh\$b"），�?*混淆名每版都变，候选数组永远追不上**�?
        //
        // 三层回退�?
        //   �?KsResolve 候选集（兼容既�?knh.b/knh$b，行为与改前一致）
        //   �?KsResolve 结构发现（DexKit 扫包 �?方法形状匹配；与名字无关�?
        //   �?全失�?�?�?miss 日志并跳过（**不崩�?*，其�?hook 不受影响�?
        //
        // �?结构发现�?*后台�?*�?026-09-23 卡顿修复）：主线程只�?300ms�?
        //   超时则先返回、由后台发现成功后经 onDiscovered 回调**补装**�?
        //   装钩逻辑因此必须抽成可重复调用的具名函数�?
        io.github.angbang852.manjiao.adapt.KsResolve.onDiscovered("knhb") { c ->
            installKnhbT0(xp, c)
        }
        val cc = io.github.angbang852.manjiao.adapt.KsResolve.resolve(
            io.github.angbang852.manjiao.adapt.KsResolve.KN_HB, cl
        )
        if (cc == null) {
            Logger.once("knhb.miss", "knhb NOT FOUND (knh.b/knh\$b + 结构发现均未命中) ⇒ T0/E1 拦截不可用，其余 hook 不受影响")
            return
        }
        installKnhbT0(xp, cc)
    }

    /**
     * 实际装钩（可被主线程或后台结构发现回调调用）�?
     *
     * `knhbT0Hooked` 保证幂等：两条路径都跑到时只有先到的生效�?
     */
    private fun installKnhbT0(xp: XposedInterface, cc: Class<*>) {
        if (CfhState.knhbT0Hooked) return
        CfhState.knhbT0Hooked = true
        Logger.once("knhb.found", "knhb found cls=${cc.name} methodCount=${cc.declaredMethods.size}")
        run {
            val cn = cc.name
            for (m in cc.declaredMethods) {
                val nm = m.name
                val ptypes = m.parameterTypes
                val hasListParam = ptypes.any { it == java.util.List::class.java }
                if (!hasListParam) continue
                Logger.d("knhb method: $nm(${ptypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                Logger.safe("knhb.$cn.$nm") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("knhb.$cn.$nm").intercept { chain ->
                        try {
                            if (CfhState.knhbCallDiag < 40) {
                                CfhState.knhbCallDiag++
                                val sizes = chain.args.map { if (it is List<*>) "L${it.size}" else it?.javaClass?.simpleName ?: "-" }.joinToString(",")
                                Logger.probe { "knhb call $nm($sizes) hist=${synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.size }}" }
                            }
                            // ★★ 白名单优先（2026-09-26）：knhb.T0/E1 �?*服务端原始批次主战场**
                            //   （`NET` 探针实证此处�?List），白名单挂这里最有效�?
                            // ★ 性能修复（2026-09-30）：此处原本对**同一个 list 连调两次**
                            //   filterWhitelist（上一行的返回值被丢弃，无任何其它用途）。
                            //
                            //   为什么删第一次是**等价**的（已核对 CfhClean.kt:126-274）：
                            //   · filterWhitelist 是**单遍完整扫描**，`while (i < la.size)` 一次删净
                            //     DIRTY(:176) 与 PENDING(:251)，**没有「每次调用处理上限」**；
                            //   · 所以第一次调用后列表内已无 DIRTY/PENDING ⇒ 第二次必然返回 0
                            //     （:612 的 `filtered del=` 日志因此恒为 0，属假信号）；
                            //   · 删除后列表终态与「调两次」完全一致（同一 list、同样的一遍删除），
                            //     而 `removed` 变成**真实条数** ⇒ 诊断日志恢复有效。
                            //
                            //   收益：knhb T0/E1 是首页批次主通道，每条元素都要做
                            //   findQpInObject + 完整 judgeWhitelist；此前每条元素白做一整遍。
                            val removed = try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) { 0 } // ★ 白名单替代黑名单
                            if (removed > 0 && CfhState.knhbT0Diag < 30) {
                                CfhState.knhbT0Diag++
                                Logger.probe { "knhb.$nm filtered del=$removed" }
                            }
                            // ★★�?首页干净内容登记�?026-09-28「池空」根因修复）�?
                            //   实测首页发现页数据主要走 **knhb T0/E1**（WHITEPASS 895 �?
                            //   vs HomeFeedResponse 通道几乎不动，v13.13 时段 RESPRET=0）�?
                            //   而干净池登记只挂在 HomeFeedResponse.getItems 分支 �?
                            //   池永远收不到首页数据 �?精选页补位无货 ⇒「滑不出来」�?
                            //   修复：knhb 清洗�?*剩余元素全是 WHITE**（filterWhitelist
                            //   已删�?DIRTY/PENDING）⇒ 就地登记进干净池�?
                            //   · noteClean 内部要求 QPhoto 直接实例（qpClassRef 判定），
                            //     �?QPhoto（包�?其它类型）自动拒绝，类型安全�?
                            //   · 开�?flt_homerefill 关闭时跳过（�?HomeFeedResponse 分支一致）�?
                            //   · T0/E1 是首页发现页的服务端原始批次；同�?精选走
                            //     Gson CollectionTypeAdapter（GSCOLL）不在此�?�?不进池�?
                            if (Prefs.bool(Prefs.K_FLT_HOMEREFILL, false) && CfhState.whiteListEnabled) {
                                try {
                                    // ★★★ v13.59 包装对象提取（2026-09-30 关键修复）：
                                    //   ## 症状
                                    //   首页判定 收=6 放行=6（干净率 81%，累计 放行21/26），
                                    //   但 `HOMEPOOL 成功=0`、池长期停在 1 —— **干净内容
                                    //   一条都没接住**。
                                    //   ## 根因
                                    //   首页批次里的元素是**包装对象**（SRCELEM 实证
                                    //   `qpWrapped=true`），而 `noteClean()` 内部要求
                                    //   **直接 QPhoto 实例**（qpClassRef 校验）⇒ 这一整段
                                    //   登记一直在静默拒绝。
                                    //   ## 纠正一个错误结论
                                    //   我先前用「88% 判脏」解释池小 —— 那是**精选页**的
                                    //   数据，被错误推广到了首页。实测首页 81% 干净，
                                    //   用户的判断是对的：「拦截率不是借口」。
                                    //   ## 修法
                                    //   与 HomeFeedResponse 分支（v13.46）一致：先用
                                    //   findQpInObject 提取出 QPhoto 再登记。
                                    var homeReg = 0
                                    for (a in chain.args) {
                                        if (a !is MutableList<*>) continue
                                        if (a.isEmpty()) continue
                                        for (e in a) {
                                            if (e == null) continue
                                            val q = try { CfhProbe.findQpInObject(e) ?: e }
                                                catch (_: Throwable) { e }
                                            try { if (CfhState.noteClean(q)) homeReg++ } catch (_: Throwable) {}
                                        }
                                    }
                                    if (homeReg > 0 && CfhState.homeRegLog < 40) {
                                        CfhState.homeRegLog++
                                        Logger.evidence(
                                            "HOMEREG",
                                            "★首页批次入池 +$homeReg 池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                                        )
                                    }
                                } catch (_: Throwable) {}
                            }
                            // �?鍚姩鍏滃簳銆屾棤鏉′欢姝﹁銆嶏紙2026-09-21 瀹炴祴淇锛夛細鍘熷厛
                            // scheduleBootFlush 鍙�?removed>0 鏃惰皟搴?鈥斺�?鏉′欢鎭板ソ鍙嶄簡�?
                            // 瀹冩湰鏄?棣栨壒娌″垹鎺夎剰椤广€佷俊鎭祦娌¤杩囨护"鐨勮ˉ鑽紝鍗村彧�?宸茬�?
                            // 鍒犳帀�?鏃跺彂鏀俱€傚疄娴嬮鎵?del=0 �?BOOTFLUSH 浠庢湭璋冨害 �?
                            // knhbInst �?null锛堟棩蹇楀疄璇?loadMore SKIP: knhbInst=null锛夈�?
                            // 鏀逛负棣栨�?T0/E1 杩涘叆鍗虫瑁咃紙scheduleBootFlush 鑷�?
                            // bootFlushDone/Pending 涓€娆℃€у畧鍗紝涓嶄細閲嶅璋冨害锛夈�?
                            // ★★★ v13.94 通道身份记录（用户定案）：这个 hook 能看到各通道名
                            //   （日志 `★抓住首页数据源强引用 …（通道=$nm）` 即证），
                            //   所以在这里**对每次通道观察**都记一笔「最后一次是不是首页」。
                            //   无时间衰减 ⇒ 停在首页看视频也算在首页。
                            if (nm != null) {
                                CfhState.lastChannelWasHome = (nm == "T0" || nm == "E1")
                            }
                            if (nm == "T0" || nm == "E1") {
                                scheduleBootFlush(chain.thisObject)
                                // v13.61 首页通道心跳（渲染出口补位的页面判据，见 CfhState）
                                CfhState.lastHomeChannelMs = System.currentTimeMillis()
                                // ★★★ v13.58 抓住首页数据源的**强引用**（2026-09-30）：
                                //   用户定调「发现页始终能刷出新视频，精选页刷不出就不对」
                                //   —— 追这个差异追到的断点：首页走 knhb T0/E1、精选页走
                                //   GSCOLL，两条通道分开，而拉取链用的 knhbInst 是
                                //   WeakReference ⇒ 用户离开首页就失效 ⇒ 精选页拉不到。
                                //   这里把**首页数据源本体**（chain.thisObject）存成强引用，
                                //   精选页补位/续拉时用它去拉首页数据。
                                //   只存首次非空，避免被后续实例顶掉。
                                if (CfhState.homeSrcStrong == null && chain.thisObject != null) {
                                    CfhState.homeSrcStrong = chain.thisObject
                                    Logger.evidence(
                                        "HOMESRC",
                                        "★抓住首页数据源强引用 ${chain.thisObject.javaClass.name}（通道=$nm）"
                                    )
                                }
                                armEarlyTrueSourceWash()
                            }
                            // �?闃查噸澶嶏紙鍗曠偣鍘婚噸锛夛細E1 鍏ㄦ椂鍘婚噸锛堟湇鍔＄鍘熷鎵规涓绘垬鍦猴級�?
                            // T0 �?refresh 閲嶆媺璺緞锛坮eason �?firstRequest锛夊幓閲嶁€斺€攍oadMore 缁媺鏃?
                            // T0 �?update 鎵规鏄?E1 鍒氫緵缁欑殑骞稿瓨椤癸紙宸插�?hist锛夛紝鍐嶅垽�?鍙岄噸鍘婚噸璇�?
                            if (nm == "E1" && ptypes.size == 1) {
                                CfhProbe.dedupeInsertBatch(chain.args.getOrNull(0) as? MutableList<Any?>, "E1")
                            } else if (nm == "T0" && ptypes.size == 6) {
                                val reason = chain.args.getOrNull(5) as? String ?: ""
                                if (reason.contains("firstRequest")) {
                                    CfhProbe.dedupeInsertBatch(chain.args.getOrNull(2) as? MutableList<Any?>, "T0fr")
                                }
                            }
                        } catch (_: Throwable) {}
                        val r = chain.proceed()
                        try { if (r is List<*>) CfhClean.filterWhitelist(r) } catch (_: Throwable) {} // ★ 白名单替代黑名单
                        r
                    }
                }
            }
        }
    }
    private fun scheduleBootFlush(src: Any?) {
        // 鈽呪�?khhbInst 鎹曡幏蹇呴』涓庡紑鍏宠В鑰︼�?026-09 鑷祴鍙戠幇鐨勮嚜韬?bug锛夛�?
        // 棣栫増鎶婇棬鎺ф斁鍦ㄥ嚱鏁板紑澶达紝瀵艰嚧鍏虫帀寮€鍏冲�?knhbInst 鎭掍�?null�?
        // 杩炲甫鎵撴琛ヤ綅鍔犺浇 鈥斺�?瀹炴�?probe11 涓夎�?
        // 銆宭oadMore SKIP: knhbInst=null -> fallback refresh銆嶃�?
        // 寮€鍏冲彧搴旀帶鍒躲€屾槸鍚﹀彂璧烽偅娆″埛鏂般€嶏紝涓嶅簲褰卞搷瀹炰緥鎹曡幏锛堜�?loadMore 鐢級銆?
        if (src != null && CfhState.knhbInst?.get() == null) {
            CfhState.knhbInst = java.lang.ref.WeakReference(src)
        }
        // �?寮€鍏冲寲锛?026-09 鐢ㄦ埛瑕佹眰锛夛細棣栨杩涗富椤佃嚜鍔ㄥ埛鏂颁竴娆″師涓烘棤鏉′欢琛屼负銆?
        // 鍏虫帀鍚庝粎璺宠繃杩欐�?refresh锛屽叾浣欓摼璺笉鍙橈紝鐢ㄦ埛鍙?A/B 瀵规�?
        // 銆岄灞忚繖娆″埛鏂版槸鍚﹀弽鑰屾妸鑴忓唴瀹瑰甫杩涙潵銆嶃�?
        // ★ 默认 true→false（2026-09-30 用户定稿：「全关肯定是都关啊，用的人按需开启啊。」）
        //   false ⇒ 首次进主页不再自动刷新一次（原为无条件行为）。其余链路不变。
        if (!Prefs.bool(Prefs.K_FLT_BOOTFLUSH, false)) {
            if (!CfhState.bootFlushDone) { CfhState.bootFlushDone = true; Logger.once("bootflush.off", "BOOTFLUSH disabled by switch (inst captured)") }
            return
        }
        if (CfhState.bootFlushDone || CfhState.bootFlushPending) return
        CfhState.bootFlushPending = true
        Logger.d("BOOTFLUSH scheduled 2s")
        CfhState.handler.postDelayed({
            CfhState.bootFlushPending = false
            try { doBootFlush() } catch (e: Throwable) { Logger.d("BOOTFLUSH err: ${e.message}") }
        }, 2000)
    }

    // �?鐪熸簮娓呮礂鍓嶇Щ�?026-09-21 瀹炴祴淇锛夛細filterVmLists锛堢湡婧愪簩灞傛竻娲楋級鐨勬瑁呭師�?
    // 鍏ㄩ儴渚濊禆 pager/fragment 鐢熷懡鍛ㄦ湡锛坔ookFragCallSeq / findPager / filterResult 琛ラ摼锛夛紝
    // 瀹炴祴绾﹀惎鍔ㄥ悗 9 绉掓�?armed锛岃€岄鎵规暟鎹�?6 绉掑氨杩涗簡鐪熸�?鈥斺�?涓�?1.8~2 绉掔┖鐧芥湡�?
    // 鐪熸簮鏄剰鐨勶紙VMPROBE filterVmLists armed 鏃ュ織鏅氫簬棣栨�?1.8s锛夈�?
    // 鏀逛负棣栨�?T0/E1 杩涘叆鍚庤捣涓€涓湁鐣岄噸璇曪細vmRef 涓€鍑虹幇绔嬪嵆娓呮礂锛?00ms �?12 = 6 绉掔獥鍙ｏ紝
    // 瑕嗙洊鏁翠釜鍚姩鏈燂級锛沠ilterVmLists 鑷�?500ms 鑺傛祦涓?keep-latest 闃熷垪锛屾棤闇€鍘婚噸銆?
    private fun armEarlyTrueSourceWash() {
        if (CfhState.earlyWashArmed) return
        CfhState.earlyWashArmed = true
        Logger.d("earlyWash armed")
        val r = object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                if (tries > 12) return
                val vm = CfhState.vmRef
                if (vm == null) { CfhState.handler.postDelayed(this, 500); return }
                try { CfhWash.filterVmLists(vm) } catch (_: Throwable) {}
                if (tries == 1) Logger.once("earlywash.fired", "earlyWash: bootstrap true-source clean fired")
                CfhState.handler.postDelayed(this, 500)
            }
        }
        CfhState.handler.postDelayed(r, 300)
    }

    private fun doBootFlush() {
        if (CfhState.bootFlushDone) return
        CfhState.bootFlushDone = true
        val inst = CfhState.knhbInst?.get()
        Logger.d("BOOTFLUSH run inst=${inst?.javaClass?.name ?: "null"}")
        // 璺�?锛氭暟鎹簮瀹炰緥锛堝惈鐖剁被锛変笂鍚嶅瓧鍚?refresh/reload/requery 鐨勬棤鍙?void 鏂规�?
        if (inst != null) {
            var c: Class<*>? = inst.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 6) {
                for (m in c!!.declaredMethods) {
                    if (m.parameterTypes.isNotEmpty() || m.returnType != Void.TYPE) continue
                    val mn = m.name.lowercase()
                    if (!(mn.contains("refresh") || mn.contains("reload") || mn.contains("requery"))) continue
                    try {
                        m.isAccessible = true
                        m.invoke(inst)
                        Logger.d("BOOTFLUSH called ${m.name} on ${c!!.name}")
                        return
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            // 璺�?锛氶亶鍘嗗瓧娈靛€兼寜杩愯鏃剁被鍨嬫壘 kik.o0 璇锋眰鍣紙瀛楁澹版槑绫诲瀷鏄?kik.i 鎺ュ彛锛?
            // 闈瀌nh.鍖呭悕锛岄』鎸夊瓧娈靛€肩殑瀹為檯缁ф壙閾炬壘 refresh() 鏃犲弬鏂规硶�?
            var fc: Class<*>? = inst.javaClass
            var flvl = 0
            while (fc != null && fc != Any::class.java && flvl < 6) {
                for (f in fc!!.declaredFields) {
                    try {
                        f.isAccessible = true
                        val req = f.get(inst) ?: continue
                        if (req is Collection<*> || req is android.view.View) continue
                        var oc: Class<*>? = req.javaClass
                        var ol = 0
                        while (oc != null && oc != Any::class.java && ol < 8) {
                            for (rm in oc!!.declaredMethods) {
                                if (rm.name != "refresh" || rm.parameterTypes.isNotEmpty() || rm.returnType != Void.TYPE) continue
                                try {
                                    rm.isAccessible = true
                                    rm.invoke(req)
                                    Logger.d("BOOTFLUSH called refresh on ${req.javaClass.name} (field ${f.name})")
                                    return
                                } catch (_: Throwable) {}
                            }
                            oc = oc.superclass; ol++
                        }
                    } catch (_: Throwable) {}
                }
                fc = fc.superclass; flvl++
            }
        }
        // 璺�?锛氬厹搴曠幇�?vmRef 鍒锋柊閾?
        val ok = try { CfhSupply.triggerRefresh() } catch (_: Throwable) { false }
        Logger.d("BOOTFLUSH fallback triggerRefresh=$ok")
    }
    private fun hookDataSource(c: Class<*>) {
        val xp = CfhState.xpRef ?: return
        synchronized(CfhState.hookedDsClasses) {
            if (!CfhState.hookedDsClasses.add(CfhUtil.hookKey(c))) return
        }
        Logger.d("hookDataSource: ${c.name}")
        val qpClass = try { Class.forName("com.yxcorp.gifshow.entity.QPhoto", false, c.classLoader) } catch (_: Throwable) { null }
        var batchHooked = 0; var singleHooked = 0
        for (m in c.declaredMethods) {
            val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
            val isRetQp = qpClass != null && m.returnType == qpClass
            if ((m.name.startsWith("get") || m.name.startsWith("is")) && !isRetList && !isRetQp) continue
            Logger.d("ds method: ${m.name} ret=${m.returnType.simpleName} params=${m.parameterTypes.map { it.simpleName }}")
            val listCount = m.parameterTypes.count {
                it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection")
            }
            if (listCount >= 2) {
                Logger.safe("hookDSBatch.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.bat.${c.name}.${m.name}").intercept { chain ->
                        // ★★�?v13.38 删除「保存首页数据源实例」（2026-09-30 用户定案）：
                        //   homeDsRef 只服务「池空主动拉数据」，池共享后该逻辑多余，整条已删�?
                        // \u2605\u2605 \u767d\u540d\u5355\u4f18\u5148\uff082026-09-26\uff09
                         try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) {}
                        val result = chain.proceed()
                        try { if (result is List<*>) CfhClean.filterWhitelist(result) } catch (_: Throwable) {} // ★ 白名单替代黑名单
                        // ★★�?v13.21 首页数据源登记（50388 MimpBripGlilt 适配）：
                        //   DataSource �?50388 首页信息流的服务端批次主战场（WHITECALLER
                        //   实证 MimpBripGlilt.S0 < a.O0），清洗后剩�?WHITE 登记进池�?
                        //   noteCleanAll �?flt_homerefill 开�?+ whiteListEnabled 控制�?
                        try {
                            for (a in chain.args) { if (a is List<*>) CfhState.noteCleanAll(a) }
                            if (result is List<*>) CfhState.noteCleanAll(result)
                        } catch (_: Throwable) {}
                        result
                    }
                }
                batchHooked++; continue
            }
            if (m.returnType == java.util.List::class.java || m.returnType.name.contains("List")) {
                Logger.safe("hookDSRet.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.ret.${c.name}.${m.name}").intercept { chain ->
                        // ★★�?v13.38 �?hookDSBatch：删除数据源实例保存（整条拉数据链路已删�?
                        val result = chain.proceed()
                        try { if (result is List<*>) CfhClean.filterWhitelist(result) } catch (_: Throwable) {} // ★ 白名单替代黑名单
                        // ★★�?v13.21 首页数据源登记（�?hookDSBatch�?
                        try { if (result is List<*>) CfhState.noteCleanAll(result) } catch (_: Throwable) {}
                        result
                    }
                }
                batchHooked++; continue
            }
            if (qpClass != null && m.parameterTypes.any { it == qpClass }) {
                Logger.safe("hookDSOne.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.one.${c.name}.${m.name}").intercept { chain ->
                        try {
                            for (a in chain.args) {
                                if (a != null && a.javaClass == qpClass && CfhDecide.shouldFilterFeed(a)) {
                                    Logger.probe { "feed filtered single: ${CfhUtil.readCaption(a)?.take(30)}" }
                                    return@intercept null
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                        null
                    }
                }
                singleHooked++
            }
            if (isRetQp) {
                Logger.safe("hookDSRetQP.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.rqp.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (result != null && CfhDecide.shouldFilterFeed(result)) {
                                Logger.probe { "feed filtered retQP: ${CfhUtil.readCaption(result)?.take(30)}" }
                                return@intercept null
                            }
                        } catch (_: Throwable) {}
                        result
                    }
                }
                singleHooked++
            }
        }
        Logger.d("hookDataSource done: ${c.name} batch=$batchHooked single=$singleHooked")
    }
    internal fun hookViewModel(vm: Any) {
        val xp = CfhState.xpRef ?: return
        val c = vm.javaClass
        synchronized(CfhState.hookedVmClasses) { if (!CfhState.hookedVmClasses.add(CfhUtil.hookKey(c))) return }
        val qpClass = try { Class.forName("com.yxcorp.gifshow.entity.QPhoto", false, c.classLoader) } catch (_: Throwable) { null } ?: return
        Logger.d("hookViewModel: ${c.name}")
        CfhState.qpClassRef = qpClass
        CfhState.vmRef = vm
        // �?精选页「当前条」主动拉取（2026-09-24）：
        //   注册 VM 实例 + 启动兜底轮询。`getCurrentPhoto()` �?*查询接口**
        //   而非事件源（真机实证只在初始化时被调一次），必须由我们主动去调�?
        try { CurrentPhotoHook.registerVm(vm); CurrentPhotoHook.startPolling() } catch (_: Throwable) {}
        // �?三条对账�?026-09-24）：VM 已就绪后启动�?
        //   对账需�?�?播放器URL �?数据层当前条 �?文案 三者同时可取，
        //   而分叉是瞬时事件 —�?必须周期采样才能抓到分叉那一刻的完整现场�?
        try { TripleCheck.start() } catch (_: Throwable) {}
        // �?�?feed 列表搜索�?026-09-24）：此处�?VM 确定可用，直接触发遍历�?
        //   实测模块碰到的列表都是边角（3~10 条），而用户能刷几百条 —�?
        //   主列表从未被接上，这就是「漏」的根源�?
        try { CfhWash.searchMainList(vm) } catch (_: Throwable) {}
        var cls: Class<*>? = c
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 6) {
            for (m in cls!!.declaredMethods) {
                if (CfhState.vmMethodDump < 200) {
                    CfhState.vmMethodDump++
                    Logger.d("vmM ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                }
                if (m.returnType == Void.TYPE && m.parameterTypes.size <= 2) {
                    Logger.probe { "vm void: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")})" }
                }
                if (m.parameterTypes.any { qpClass.isAssignableFrom(it) } && m.returnType == Void.TYPE) installVmShow(xp, c, m, qpClass)
                if (m.parameterTypes.any { java.util.List::class.java.isAssignableFrom(it) || it.name.contains("List") } && m.returnType == Void.TYPE) installVmList(xp, c, m)
                // y0()/B0()/E()/F0()/H()/H0()/V0() 绛夎繑锟?List 鐨勬柟锟?= 鐩存�?鍗＄墖锟?adapter 鐨勬暟鎹簮�?
                // 鐩存帴杩囨护杩斿洖鍊硷紝璁╃洿鎾崱鏍规湰杩涗笉�?adapter�?
                if ((m.returnType == java.util.List::class.java || m.returnType.name.contains("List")) && m.parameterTypes.isEmpty()) installVmListRet(xp, c, m)
                // ★★ 真源持有者结构自证（2026-09-24，追「删了又回来」）�?
                //
                //   逆向证据：`V0() �?aVar.f460927a.c().G0()`�?
                //   �?`aVar.f460927a.c()` �?`p3c.g` 的实例（真源持有者）�?
                //   `p3c.g` 源码不在逆向件里，同名方�?`G0` �?VM 上是 `kik.i`
                //   （实测按 `G0` 挂钩零命中）—�?说明真源持有者是**另一个类**�?
                //
                //   与其猜类名，不如顺着 VM 字段链直接找到那个对象，
                //   �?**类名 + 内部列表身份(size/idHc)** 打出来�?
                //   拿到类名后才能精�?hook 它的取数/写入方法�?
                //   而不是继续在外围删返回值�?
                //
                //   只读，每进程一次�?
                if (!CfhState.srcHolderDumped && m.parameterTypes.isEmpty() &&
                    (m.returnType == java.util.List::class.java || m.returnType.name.contains("List"))
                ) {
                    CfhState.srcHolderDumped = true
                    Logger.safe("srcHolderDump") {
                        val sb = StringBuilder()
                        for (f in Reflect.nonStaticFields(vm.javaClass)) {
                            try {
                                f.isAccessible = true
                                val v = f.get(vm) ?: continue
                                val vn = v.javaClass.name
                                if (vn.startsWith("java.") || vn.startsWith("android.") ||
                                    v is List<*> || v is android.view.View
                                ) continue
                                // 该对象（及其一层子对象）里是否�?List�?
                                val lists = StringBuilder()
                                for (f2 in Reflect.nonStaticFields(v.javaClass)) {
                                    try {
                                        f2.isAccessible = true
                                        val v2 = f2.get(v)
                                        if (v2 is List<*>) {
                                            lists.append(f2.name).append("(sz=").append(v2.size)
                                                .append(",hc=").append(System.identityHashCode(v2)).append(") ")
                                        }
                                    } catch (_: Throwable) {}
                                }
                                if (lists.isNotEmpty()) {
                                    sb.append("${f.name}:${v.javaClass.name}{$lists} ")
                                }
                            } catch (_: Throwable) {}
                        }
                        Logger.evidence("SRCHOLDER", "VM 字段链上的列表持有者= $sb")
                    }
                }
                // �?rerank 鎻掑崱鍞竴鍏ュ�?T1(int,QPhoto,boolean,String)锛圠iveRerankPresenter d.G
                // �?VM.T1 �?data_source_service.q() 鍗曟潯鎻掑叆锛夛細闈?List 鎵规�?filterListArgs
                // 缁撴瀯鎬ф嫤涓嶅埌锛宒ata source 鍐呴儴鍒楄〃涔熶笉�?laFind 鏍归泦鈥斺€�? 鍏ュ彛鏄敮涓€鎷︾偣銆?
                // 鍒ゅ畾鐢?shouldFilterFeed + decideFeedRaw 鍚屾鍏ㄩ噺鍏滃簳锛�? 涓€娆℃€у叆鍙ｄ笉鑳借蛋
                // 寮傛缂撳瓨锛歮iss 鍏堟斁琛?鍗″繀涓婂睆锛夛紱T1CALL 鏃犳潯浠舵墦鏃ュ織鍙栬瘉 T1 鏄惁鐪熻璋冪�?
                if (m.name == "T1" && m.parameterTypes.size == 4 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    qpClass.isAssignableFrom(m.parameterTypes[1]) &&
                    m.parameterTypes[2] == java.lang.Boolean.TYPE &&
                    m.parameterTypes[3] == String::class.java) installVmT1(xp, c, m)
                if (m.returnType == qpClass && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) installVmGet(xp, c, m, qpClass)
                if (m.name == "y" || m.name == "y0") installVmY(xp, c, m)
            }
            cls = cls.superclass; lvl++
        }
    }
    /**
     * ★★�?阻止「把 QPhoto 交给渲染」的调用�?026-09-26 追「没判明就不放行」）�?
     *
     * ## 为什么这个位置才是「不放行」的正解
     *
     * 用户原话�?
     * > 「为什么要记住，本来没判明就不应该放行啊�?
     *
     * 此前所有做法（删列表元素）都是**事后补救** —�?实测�?
     * ```
     * 同一对象=false : 204   同一对象=true : 0
     * ```
     * �?模块拿到的永远是副本，删了它快手照样从别处渲染�?
     *
     * **本函数不�?*：它 hook 的是「参数含 QPhoto 且返�?void」的方法�?
     * 也就�?*快手的渲�?绑定调用本身**。用 `return@intercept null`
     * **让这次调用根本不发生** —�?不是删数据，�?*不让它被�?*�?
     *
     * ## 挂载条件（`CfhFeedHook:575`�?
     *
     * ```kotlin
     * 参数�?QPhoto && 返回 void  �?installVmShow
     * ```
     * 实测匹配�?`A3(QPhoto) -> void`�?
     *
     * ## 白名单接入（2026-09-26�?
     *
     * 原实现只�?`shouldFilterFeed`（黑名单）。现按用户定稿改�?*白名单语�?*�?
     *   · `WHITE`（判为干净�? �?`proceed()` **放行**
     *   · `DIRTY`（判脏）      �?`return null` **不执�?*
     *   · `PENDING`（字段未齐） �?`return null` **不执�?*（没判明就不放行�?
     *
     * **白名单开启时用三态；未开启时保持原黑名单行为**（向后兼容）�?
     */
    private fun installVmShow(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method, qpClass: Class<*>) {
        // �?安装留痕�?026-09-26）：此前�?Logger.safe（只在异常时打印），
        //   导致「没安装」和「装了但没命中」无法区分。改为显式记录安装�?
        if (CfhState.vmShowInstallLog < 60) {
            CfhState.vmShowInstallLog++
            Logger.evidence(
                "VMSHOW-INST",
                "安装 ${c.simpleName}.${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> void"
            )
        }
        Logger.safe("hookVMShow.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.show.${c.name}.${m.name}").intercept { chain ->
                // �?调用留痕�?026-09-26）：首次上线 `VMSHOW-BLOCK=0`�?
                //   需区分「方法没被调用」还是「调用了但都判为干净」�?
                if (CfhState.vmShowCallLog < 60) {
                    CfhState.vmShowCallLog++
                    Logger.evidence(
                        "VMSHOW-CALL",
                        "调用 ${m.name} 参数=${chain.args.joinToString(",") { it?.javaClass?.simpleName ?: "-" }}"
                    )
                }
                try {
                    // �?若白名单开启：�?WHITE 一律挡（没判明就不放行�?
                    if (CfhState.whiteListEnabled) {
                        var blocked = false
                        var lastCap: String? = null
                        var lastVerdict: CfhDecide.WhitelistVerdict? = null
                        var matchedQp = false          // ★ 诊断：参数是否匹配到 QPhoto
                        // ★★�?v13.36 撤销渲染层补位（2026-09-30 用户�?A「首页发现页被污染」）�?
                        //   RENDERFILL 补位装在 installVmShow（Q0 �?= **首页发现�?*渲染点）�?
                        //   每次滑首页都往首页自己的渲染列表塞池内�?�?用户看到
                        //   什�?Ling/手工观天�?等池内容反复出现 �?「循�?重复/一个作者几条」�?
                        //   快手源本身健康分散（WHYWHITE 实证：木匠制�?许嵩结婚/知识播种…）�?
                        //   污染是我们补位造成的。补位只保留 **Gson �?refillFromCleanPool**
                        //   （精选页链路，触发条�?removed>=4 && r.size<=2）—�?那才是用户方�?
                        //   「首页内容池接到精选页上」的正确位置�?*首页发现页零补位�?*
                        for (a in chain.args) {
                            if (a == null) continue
                            // ★★�?v13.30b 列表参数：Q0(ArrayList,...) 传的是整个列�?
                            //   （VMSHOW-DIAG 实证「参数类=java.util.ArrayList」），原逻辑
                            //   只判直接 QPhoto 参数 �?列表内的干净视频从未判定、从未进�?
                            //   �?池空。列表内部的脏内容已�?installVmList（Griedth.q 链）
                            //   filterWhitelist 清洗过，剩的都是 WHITE —�?这里遍历元素
                            //   直接收进干净池（精选页补位的数据源，用户方案真正落地）�?
                            //   只收�?block：列表内部拦截由列表级清洗负责，这里不整表挡�?
                            if (a is List<*>) {
                                // ★★★ v13.56 渲染列表量化诊断（2026-09-30）：
                                //   VMSHOW-CALL 实证 Q0 的参数就是**整个 ArrayList**
                                //   （= 即将上屏的那一屏内容），但 RENDERFEED 只收到
                                //   2 条白名单 ⇒ 必须看清这个列表的真实构成：
                                //   几条元素 / 几条能提出 QPhoto / 几条判白 / 几条入池。
                                //   这是定位「首页内容到底有没有被我们收到」的最后一步。
                                var diagSize = 0
                                var diagQp = 0
                                var diagWhite = 0
                                var diagPool = 0
                                for (el in a) {
                                    if (el == null) continue
                                    diagSize++
                                    // ★★�?v13.35 包装对象提取�?026-09-30 池永�?条根因）�?
                                    //   SRCELEM 实证渲染列表元素�?*包装对象**（qpWrapped=true�?
                                    //   vm.i size=9 元素=[][][g][][f1]�?全不�?QPhoto），原逻辑
                                    //   qpClass.isAssignableFrom(el.javaClass) �?false �?遍历
                                    //   一条都不收 �?渲染层判白再多干净内容也进不了池（POOLDUMP
                                    //   size=1 实证）。必�?findQpInObject 提取 QPhoto 再判再收
                                    //   —�?�?noteCleanAll �?v13.28 处理完全一致�?
                                    val qel = try { CfhProbe.findQpInObject(el) ?: el }
                                        catch (_: Throwable) { el }
                                    if (qpClass.isAssignableFrom(qel.javaClass)) {
                                        diagQp++
                                        try {
                                            val vEl = try { CfhDecide.judgeWhitelist(qel) }
                                                catch (_: Throwable) { CfhDecide.WhitelistVerdict.PENDING }
                                            if (vEl == CfhDecide.WhitelistVerdict.WHITE) {
                                                diagWhite++
                                                if (CfhState.noteClean(qel)) {
                                                    diagPool++
                                                    CfhState.renderFeedRegCount++
                                                    if (CfhState.renderFeedRegLog < 60) {
                                                        CfhState.renderFeedRegLog++
                                                        Logger.evidence(
                                                            "RENDERFEED",
                                                            "★渲染层列表WHITE直收进池 #${CfhState.renderFeedRegCount} " +
                                                                "cap=\"${try { CfhUtil.readCaption(qel) } catch (_: Throwable) { null }?.take(20) ?: "-"}\""
                                                        )
                                                    }
                                                }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                // ★★★ v13.56 诊断落盘：一屏内容到底有多少能被收
                                if (CfhState.renderDiagLog < 60) {
                                    CfhState.renderDiagLog++
                                    Logger.evidence(
                                        "RENDER-DIAG",
                                        "★渲染列表 size=$diagSize 可提QP=$diagQp 判白=$diagWhite " +
                                            "入池=$diagPool 池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                                    )
                                }
                                continue
                            }
                            // �?诊断�?026-09-26）：VMSHOW-BLOCK=0 时区�?
                            //   「参数没匹配�?QPhoto」vs「匹配了但判�?WHITE�?
                            if (CfhState.vmShowDiagLog < 60) {
                                CfhState.vmShowDiagLog++
                                Logger.evidence(
                                    "VMSHOW-DIAG",
                                    "${m.name} 参数类=${a.javaClass.name} " +
                                        "是QPhoto=${qpClass.isAssignableFrom(a.javaClass)}"
                                )
                            }
                            if (!qpClass.isAssignableFrom(a.javaClass)) continue
                            matchedQp = true
                            lastCap = try { CfhUtil.readCaption(a) } catch (_: Throwable) { null }
                            val v = try { CfhDecide.judgeWhitelist(a) }
                                    catch (_: Throwable) { CfhDecide.WhitelistVerdict.PENDING }
                            lastVerdict = v
                            // �?白名单语义：只有 WHITE 才放行（DIRTY / PENDING 都挡�?
                            if (v == CfhDecide.WhitelistVerdict.WHITE) {
                                // ★★�?v13.30 渲染�?WHITE 直收进池�?026-09-30 用户
                                //   「我首页随便一滑多的是干净的视频」）：用户滑首页
                                //   看到的内容就是这里判 WHITE �?—�?直接收进干净池，
                                //   精选页池空自动补这些（用户方案「首页发现的内容�?
                                //   接到精选页上」真正落地）。noteClean 自带类型校验 +
                                //   pid 去重 + v13.29 反循环时间窗，绝不重复入池�?
                                try {
                                    if (CfhState.noteClean(a)) {
                                        CfhState.renderFeedRegCount++
                                        if (CfhState.renderFeedRegLog < 60) {
                                            CfhState.renderFeedRegLog++
                                            Logger.evidence(
                                                "RENDERFEED",
                                                "★渲染层WHITE直收进池 #${CfhState.renderFeedRegCount} " +
                                                    "cap=\"${try { CfhUtil.readCaption(a) } catch (_: Throwable) { null }?.take(20) ?: "-"}\""
                                            )
                                        }
                                    }
                                } catch (_: Throwable) {}
                            } else { blocked = true; break }
                        }
                        if (CfhState.vmShowDiagLog < 60) {
                            CfhState.vmShowDiagLog++
                            Logger.evidence(
                                "VMSHOW-DIAG",
                                "${m.name} 结束 匹配到QP=$matchedQp 判定=${lastVerdict?.name ?: "无"} " +
                                    "cap=\"${lastCap?.take(20) ?: "-"}\""
                            )
                        }
                        if (blocked) {
                            if (CfhState.vmShowBlockLog < 200) {
                                CfhState.vmShowBlockLog++
                                Logger.evidence(
                                    "VMSHOW-BLOCK",
                                    "★挡下渲染(${lastVerdict?.name ?: "?"}) ${m.name} " +
                                        "cap=\"${lastCap?.take(24) ?: "-"}\""
                                )
                            }
                            return@intercept null
                        }
                        // 全部 WHITE �?放行
                        chain.proceed()
                        return@intercept null
                    }
                    // 白名单未开�?�?原黑名单行为
                    for (a in chain.args) {
                        if (a == null || !qpClass.isAssignableFrom(a.javaClass)) continue
                        if (CfhDecide.shouldFilterFeed(a)) {
                            if (CfhState.vmShowBlockLog < 200) {
                                CfhState.vmShowBlockLog++
                                Logger.evidence(
                                    "VMSHOW-BLOCK",
                                    "★黑名单挡下渲染 ${m.name} " +
                                        "cap=\"${try { CfhUtil.readCaption(a) } catch (_: Throwable) { null }?.take(24) ?: "-"}\""
                                )
                            }
                            return@intercept null
                        }
                    }
                } catch (_: Throwable) {}
                chain.proceed()
                null
            }
        }
    }

    private fun installVmList(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookVMList.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.list.${c.name}.${m.name}").intercept { chain ->
                try {
                    // ★★�?v13.38 删除「保存数据源实例」（2026-09-30 用户定案）：
                    //   homeDsRef 只用于「池空直调它 load() 拉首页数据」——池是共享的�?
                    //   首页内容本就自动进池，无需主动拉。整条链路已删（�?
                    //   triggerHomeLoadMore 调用点）�?
                    // ★★ 白名单优先（2026-09-26 用户定稿「判正常才放行」）—�?
                    //   这是**真正�?List 参数**的点（实�?`NET` 探针在此有命中）�?
                    //   必须�?filterListArgs（黑名单）之前：白名单是更强的约束�?
                    //   默认关闭（whiteListEnabled=false）时零开销�?
                    // ★★★ v13.60 修「清洗白跑两遍」+ 整表量化诊断（2026-09-30）：
                    //   ## 修掉的 bug
                    //   下面原本连着两次 `filterWhitelist(chain.args)`，第一次的结果
                    //   被直接丢弃 —— 等于每次遍历都把整表白清洗两遍（每条内容两倍
                    //   判定开销，且第二次可能对已删过的列表重复计数）。
                    //   ## 为什么加诊断
                    //   用户实测「精选页刷不出」，补位日志却显示塞了 +6 条 ⇒ 必须
                    //   看清**数据源列表的真实大小**（一屏几条）和**入池几条**：
                    //   这是「整表到底有没有被我们接住」的唯一直接证据。
                    val removed = try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) { 0 }
                    if (removed > 0) Logger.probe { "vm filtered list: $removed via ${m.name}" }
                    // ★★★ v13.21 首页数据源登记（50388 MimpBripGlilt.q < p0.Aj VmList 路径）
                    var diagLists = 0
                    var diagElems = 0
                    var diagReg = 0
                    try {
                        for (a in chain.args) {
                            if (a !is List<*>) continue
                            diagLists++
                            diagElems += a.size
                            diagReg += try { CfhState.noteCleanAll(a) } catch (_: Throwable) { 0 }
                        }
                    } catch (_: Throwable) {}
                    if (CfhState.vmListDiag < 60) {
                        CfhState.vmListDiag++
                        Logger.evidence(
                            "VMLIST-DIAG",
                            "★数据源列表 ${m.name} 参数列表=$diagLists 元素=$diagElems 入池=$diagReg " +
                                "删=$removed 池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                        )
                    }
                    // ★★�?v13.36 撤销池水位自动拉（用户�?A「首页零补位」）�?
                    //   �?v13.32 在此（首页发现页链路）检查池水位并触发首页数据源
                    //   load() —�?这是**首页自己的链�?*，池空该由精选页链路
                    //   （CfhTtpParse.refillFromCleanPool 触发条件）自行处理�?
                    //   首页发现页零补位、零触发 �?快手源怎么给就怎么显示�?
                } catch (_: Throwable) {}
                chain.proceed()
                null
            }
        }
    }

    /**
     * v13.67 渲染出口是否「只改返回值、返回副本」。
     *
     * 置 false 即整体回退到 v13.66 原行为（直接改快手返回的原列表）——
     * 不动代码就能真机 A/B，因为这一改动的正是**精选页清洗的出口路径**。
     */
    private const val LRET_RETURN_COPY = true

    // ★★★ v13.70 空响应原地补池开关（2026-09-30 用户批准的「方案 A」）
    //   ## 治的是什么症状
    //   真机实证（用户报「精选页滑不出 + 无网络」）：
    //     [TTPPARSE]      解析期清除 9 条（判脏=7 待判=2）剩 0
    //     [RESPRET]       HomeFeedResponse.getItems size=0 类=java.util.ArrayList 可变=true
    //     [NETCOST]       HomeFeedResponse.getItems size=0
    //     [TTPPARSE-RECV] 批次=#11 距上次=217208ms   ← 217 秒才来下一批（正常 3 秒）
    //   ⇒ 我们把整批判脏项删干净，交给快手的是**一份 0 条的 feed 响应**；
    //     快手判定为「网络故障」→ 弹「无网络」→ 停止请求下一页 ⇒ 滑不动。
    //     不是真断网（同批 size=7/9 一直在来）。
    //   ## 做什么
    //   在数据源返回列表被清空（`isEmpty()`）时，把干净池里的条目
    //   **原地 `addAll` 进那个列表本身**（`可变=true` 已由 RESPRET 实证），
    //   保证交给快手的响应 size ≥ 1 ⇒ 快手不再报「无网络」、继续请求下一页。
    //   ## 为什么是「原地改这个实例」而不是「换个新列表」
    //   这个 `result` 就是快手紧接着要去读的容器；原地改它，模块与快手看到的
    //   是同一个对象，不存在「两边不一致 / 下一次读又变回空」的问题。
    //   ## 与失败的旧 REPOOL 的区别
    //   旧 REPOOL 是往**别的列表**追加（交接 §7：打了十几轮屏幕没变）；
    //   这里改的是数据源**返回的那个实例**。
    //   ## 风险与回滚
    //   网络层补位**在本项目没有成功先例**（唯一验证过能上屏的是渲染出口
    //   `RENDERFILL`）⇒ 可能「不报无网络了但内容仍不上屏」，需实测。
    //   置 false 即完全回到 v13.69 行为。
    // ★★★ v13.85 崩溃隔离实验开关（2026-09-29）
    //
    //   目的：判定 `PresenterV2 不能从 CREATE 跳到 UNBIND`（bind 期间崩）是不是
    //   **注入池条目**引起 —— 尤其是不是**跨会话恢复**回来的条目引起。
    //
    //   ⚠️ 实验设计要点（第一版设计被我否掉了，原因记在这里免得再犯）：
    //   一开始想「把两条注入路全关掉看崩不崩」，但那样**精选页就没有内容了**
    //   （白名单下精选页批次 ~100% 脏）⇒ 没有条目被 bind ⇒ **崩溃即便与注入无关
    //   也不会复现** ⇒ 得到的是「因为没内容所以不崩」，得不出任何结论。
    //
    //   所以走「**只关恢复、注入照旧**」：池里只剩快手自己刚渲染过的活条目，
    //   精选页仍有货、仍在 bind，实验才有判别力。
    //   这一条**不用改代码**：删掉 `/sdcard/Download/ManJiao/.sys/pool.json` 即等于关恢复。
    //
    //   结论归属：
    //     · 删档后长时间滑动不再崩 ⇒ 是**恢复条目**（Gson 反序列化丢了 transient
    //       字段 / Presenter 树绑成非法态），修法是恢复后做一次「重新取数」而不是直接用；
    //     · 删档后仍崩 ⇒ 与恢复无关，怀疑预热切页打乱快手 Fragment/Presenter 生命周期。
    private const val RENDERFILL = true

    private const val RESPFILL = true

    /** 单次原地补池最多补几条（对齐 RENDERFILL 的 6 条一屏） */
    private const val RESPFILL_MAX = 6

    private fun installVmListRet(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.d("vmListRet sig: ${m.name}() -> ${m.returnType.simpleName}")
        Logger.safe("hookVMListRet.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.lret.${c.name}.${m.name}").intercept { chain ->
                // ★★★ v13.67 渲染出口改为「只改返回值、返回副本」（学详情页，2026-09-30）
                //   ## 为什么必须用副本
                //   本 hook 返回的列表**直接给卡片 adapter**（见 hookViewModel 里的注册注释）。
                //   原来直接对 `chain.proceed()` 拿到的对象过滤/补位 ⇒ 若它就是快手
                //   内部列表本体，就等于替快手改它的**分页状态**：
                //     列表被删短 ⇒ VM 的 I() 返回空 ⇒ adapter 无可滚动项
                //     ⇒ 快手不再请求下一页 ⇒ **精选页死锁**（交接文档 §3.4）
                //   详情页（SlidePlayViewModel.x4 判脏返 null）为什么能一直刷？
                //   因为它**只改返回值、绝不动源列表**（§3.3，x4 实测 60 次/40 秒自愈）。
                //   ## 做法
                //   先把返回列表复制一份，后续 filterWhitelist / noteCleanAll / addAll
                //   全部落在副本上，最后把副本交给 adapter；快手内部列表保持原样。
                //   视觉结果不变（脏项照样不进 adapter），但分页状态不再被破坏。
                //   ## 回退
                //   把 [LRET_RETURN_COPY] 置 false 即回到 v13.66 原行为。
                val raw = chain.proceed()
                val r: Any? = if (LRET_RETURN_COPY && raw is List<*> && raw.isNotEmpty()) {
                    val cpy = ArrayList<Any?>(raw.size)
                    for (e in raw) cpy.add(e)
                    cpy
                } else raw
                var ret: Any? = r
                try {
                    // ★★★ v13.68 空列表也必须补位（2026-09-30 真机实证的最后一环）
                    //   ## 为什么要放开 isNotEmpty
                    //   精选页一批**全判脏**是常态（本会话真机实测：累计 档下=833
                    //   放行=23 ⇒ 97.6% 判脏，`放行=0` 的批次比比皆是）。网络层把脏项
                    //   全删之后，这个渲染出口拿到的就是**空列表** —— 而原来的门
                    //   `r.isNotEmpty()` 会让整个补位块**直接跳过**，
                    //   恰恰在「列表空了、最该补」的那一刻不补。
                    //   真机铁证：RENDERFILL 只在列表还剩 1~4 条时出现
                    //   （原=1 / 原=3 / 原=4），此后 **330 秒零次**；同期
                    //   TTPPARSE-RECV 的批次间隔飙到 **42~64 秒**
                    //   （快手自己该 3 秒一批）⇒ 卡死，用户看到的就是「刷不出」。
                    //   ## 为什么补上就能自愈
                    //   列表空 ⇒ adapter 无可滚动项 ⇒ 快手不请求下一页（死锁）。
                    //   补满 6 条 ⇒ 用户能滑 ⇒ 快手观察到滚动 ⇒ 主动请求下一页
                    //   —— 这正是详情页能一直刷的自愈回路（交接 §3.3）。
                    //   ## 空列表下各步仍然安全
                    //   before=0；filterWhitelist / noteCleanAll 对空列表返回 0；
                    //   `r.size != before` 为 false（跳过诊断分支）；补位取满后由
                    //   下面的 newList 承载并返回。
                    if (r is List<*>) {
                        val before = r.size
                        // ★★ 溯源标记�?026-09-24）：落盘证据需要知道「谁在反复触发删除」�?
                        //   实测 432 次删除中 318 次删的是同一�?AI 视频�?
                        //   必须区分是哪个方法（V/H/E/F0...）在反复返回它�?
                        CfhState.lastLretMethod = "${c.simpleName}.${m.name}"
                        try { if (r is List<*>) CfhClean.filterWhitelist(r) } catch (_: Throwable) {} // ★ 白名单替代黑名单
                        // ★★�?v13.21 首页数据源登记（�?installVmList�?
                        try { if (r is List<*>) CfhState.noteCleanAll(r) } catch (_: Throwable) {}
                        if (r.size != before) {
                            Logger.probe { "vm lret ${m.name} filtered: $before -> ${r.size}" }
                            // �?蹇収璇婃柇锛氳嫢鍚屼竴鏂规硶鍙嶅鍑虹幇鐩稿悓 before锛堝鍙嶅 7->2锛夛�?
                            // 璇存�?V0() 姣忔杩斿洖鏂板缓蹇収锛屽垹蹇収鏃犳晥锛岀湡婧愬湪鍒銆?
                            CfhState.lretDiagCount++
                            if (CfhState.lretDiagCount <= 6) {
                                val implCls = r.javaClass.name
                                val firstEl = r.firstOrNull()
                                Logger.always("lretDIAG ${m.name}: impl=$implCls idHc=${System.identityHashCode(r)} size=${r.size} firstEl=${firstEl?.javaClass?.name ?: "null"}")
                            }
                        }
                        // ★★★ v13.61 渲染出口补位（2026-09-30 用户实测「精选页刷不出」根治点）：
                        //   ## 为什么补在这里而不是网络层
                        //   本 hook 返回的就是**直接给卡片 adapter 的数据源列表**（见
                        //   hookViewModel 里本 hook 的注册注释），而 refillFromCleanPool
                        //   补的是**网络响应列表** —— 快手只把那个当「原始批次」，
                        //   补进去的内容进不了这个渲染出口。
                        //   铁证：`REPOOL ★干净池补位 +6 条` 打了十几轮，用户屏幕
                        //   上一条都没有 ⇒ 补的位置错了。
                        //   ## 页面判据（用户硬要求「首页发现页零补位」）
                        //   首页走 knhb T0/E1 通道、精选页走 GSCOLL ⇒ 3 秒内没出现过
                        //   T0/E1（lastHomeChannelMs）就说明当前不在首页，可以补。
                        val onHomeNow = CfhState.lastChannelWasHome || System.currentTimeMillis() - CfhState.lastHomeChannelMs < 3000L
                        val qcNow = CfhState.qpClassRef
                        if (RENDERFILL &&
                            Prefs.bool(Prefs.K_FLT_HOMEREFILL, false) &&
                            !onHomeNow && qcNow != null && r.size < 6
                        ) {
                            // ★★★ v13.81 **严格档硬修**（真机症状 2026-09-29：同一段视频
                            //   「隔了几屏又出现」）。
                            //
                            //   病根：原来「注入」与「出队」是**分开的两件事** ——
                            //   出队被 1.2s 限流挡住的那些调用，**照样把池里的条目塞进列表、
                            //   却不从池里删**。这些条目仍留在池中 ⇒ 下一拍又被塞一次
                            //   ⇒ 同一段视频在几屏之后再次上屏。用户看到的正是这个。
                            //
                            //   修法：**注入与出队原子化** —— 这一拍不能出队，就干脆不注入。
                            //   副作用反而是好的：注入频率天然压到 ≈ 一次/1.2s（贴近人手一屏），
                            //   也顺手把「一张卡构建期间被连续调用上百次」的浪费挡掉了。
                            val nowGate = System.currentTimeMillis()
                            if (nowGate - CfhState.lastRenderDrainMs <= 1200L) {
                                CfhState.renderFillSkip++
                            } else {
                            val picked = ArrayList<Any?>()
                            val snapshot = synchronized(CfhState.cleanPool) { ArrayList(CfhState.cleanPool) }
                            for (cand in snapshot) {
                                if (r.size + picked.size >= 6) break
                                if (cand == null || !qcNow.isInstance(cand)) continue
                                if (r.any { it === cand }) continue
                                if (picked.any { it === cand }) continue
                                picked.add(cand)
                            }
                            if (picked.isNotEmpty()) {
                                // ★★★ v13.84 **崩溃修复**：永远返回新列表，绝不原地改活列表。
                                //
                                //   真机崩溃（09-29 02:36:07）：
                                //     java.lang.IllegalStateException: 不能从 CREATE 跳到 UNBIND：
                                //       Class=com.smile.gifmaker.mvps.presenter.PresenterV2
                                //         at PresenterV2.unbind
                                //         at r7l.g.onBindViewHolder(SourceFile:1)
                                //         at RecyclerView$Adapter.bindViewHolder
                                //   机理：原来这里在 `r is MutableList` 时**直接拿原列表 addAll**，
                                //   而 `r` 很可能是**已经交给适配器的活列表** ⇒ 在 bind 进行中
                                //   往它加条目 ⇒ 适配器条目集合与 ViewHolder 失去同步
                                //   ⇒ 正在绑定的 Presenter 被 unbind，而它还停在 CREATE ⇒ 崩。
                                //   一律新建列表返回，适配器拿到的是自洽快照（代价：一次浅拷贝）。
                                val newList: MutableList<Any?> = ArrayList<Any?>(r)
                                newList.addAll(picked)
                                ret = newList
                                CfhState.renderFillCount += picked.size
                                // ★ v13.81 走到这里就已经确定「这一拍允许注入」⇒
                                //   注入与出队**原子化**执行（出队不再有第二个条件，
                                //   否则又会回到「注入了却没出队」的重复老路上）。
                                // ★★★ v13.91 崩溃现场观测：把「这批注入里有多少是**已经投放过**的」
                                //   和「有多少来自**跨会话存档**」打进日志。
                                //   崩溃会杀进程，所以必须在崩溃前落盘（不能只在内存里统计）。
                                //   `重投>0` ⇒ 同一条被二次注入 ⇒ 快手重复 bind ⇒
                                //     PresenterV2 状态机被推到非法态 ⇒ 正是 `不能从 CREATE 跳到 UNBIND`。
                                var resInj = 0
                                var dupInj = 0
                                try {
                                    for (p in picked) {
                                        val pid = CfhProbe.readPhotoId(p)
                                        if (pid.isNullOrEmpty()) continue
                                        if (CfhState.servedPids.contains(pid)) dupInj++
                                        if (CfhState.restoredPids.contains(pid)) resInj++
                                    }
                                } catch (_: Throwable) {}
                                CfhState.lastRenderDrainMs = nowGate
                                val drained2 = try { CfhState.consumeClean(picked) } catch (_: Throwable) { 0 }
                                if (CfhState.renderFillLog < 120) {
                                    CfhState.renderFillLog++
                                    Logger.evidence(
                                        "RENDERFILL",
                                        // ★ v13.68 空列表补位单独打标：这一格才是「刷不出」的
                                        //   正主，必须能与「列表还剩几条时顺手补」在日志里分开数
                                        (if (before == 0) "★空列表补位 " else "★渲染出口补位 ") +
                                            "+${picked.size} 出队=$drained2 原=$before 现=${newList.size} " +
                                            "跳过=${CfhState.renderFillSkip} 重投=$dupInj 恢复=$resInj " +
                                            "方法=${m.name} 池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                                    )
                                }
                                // ★ v13.91 记账：本批已投放（下一批若再出现同 pid 即「重投」）
                                try {
                                    for (p in picked) {
                                        val pid = CfhProbe.readPhotoId(p)
                                        if (!pid.isNullOrEmpty()) CfhState.servedPids.add(pid)
                                    }
                                } catch (_: Throwable) {}
                            }
                            }
                        }
                    }
                } catch (_: Throwable) {}
                ret
            }
        }
    }

    private fun installVmT1(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookVMT1") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.t1.${c.name}").intercept { chain ->
                try {
                    val t1idx = chain.args.getOrNull(0) as? Int ?: -1
                    val t1tag = chain.args.getOrNull(3) as? String ?: ""
                    val t1qp = chain.args.getOrNull(1)
                    val t1dirty = t1qp != null && (CfhDecide.shouldFilterFeed(t1qp) || try { CfhDecide.decideFeedRaw(t1qp) } catch (_: Throwable) { false })
                    // �?性能修复（审�?2026-09 · M1）：限次 + 惰性求值�?
                    // �?hook �?rerank 单条插入的唯一入口，原先无条件 always 输出�?
                    if (CfhState.t1CallDiag < 30) {
                        CfhState.t1CallDiag++
                        Logger.d { "T1CALL idx=$t1idx tag=$t1tag dirty=$t1dirty CfhState.liveTop=${CfhState.liveTop} qp=${t1qp?.javaClass?.simpleName ?: "null"}" }
                    }
                    if (t1dirty) {
                        Logger.d { "T1 SWALLOWED idx=$t1idx tag=$t1tag" }
                        return@intercept null
                    }
                } catch (_: Throwable) {}
                chain.proceed()
                null
            }
        }
    }

    private fun installVmGet(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method, qpClass: Class<*>) {
        Logger.d("vmGet sig: ${m.name}(int) -> ${m.returnType.simpleName}")
        Logger.safe("hookVMGet.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.get.${c.name}.${m.name}").intercept { chain ->
                val r = try { chain.proceed() } catch (_: Throwable) { null }
                try {
                    if (r != null && qpClass.isAssignableFrom(r.javaClass)) {
                        val idx = (chain.args.getOrNull(0) as? Int) ?: -1
                        var clsQ: Any? = r
                        // 绌哄３瀹炰緥鍏滃簳锛氱敤瀵屾暟鎹疄渚嬪垎锟?
                        if (CfhUtil.readUserName(r, Reflect.readAny(r, "mEntity") ?: r).isEmpty()) {
                            clsQ = CfhCapture.findWindowQp(idx) ?: r
                        }
                        if (clsQ != null && CfhDecide.shouldFilterFeed(clsQ)) {
                            // ★★�?替换返回值整体废弃（2026-09-25 用户定稿「就删脏项就行了」）�?
                            //
                            //   原实现命中脏项时 return@intercept clean——把干净项顶替返回�?
                            //   这正是「文字和视频乱配」的机制源头（用户：自从搞替换开始就一团糟）：
                            //   getter 返回净�?�?播放器绑净视频；但昵称/文案/角标组件走其�?
                            //   调用链按同下标再�?�?拿到脏项 �?「净视频 + 脏文字」错配�?
                            //
                            //   现改为只删不清替�?
                            //   �?立即触发 VM 全量清洗（脏项从数据源删掉，含未消费副本）；
                            //   �?scrub 脏项展示字段（caption/声明清空 �?订阅 View 刷新为空）；
                            //   �?原样返回脏项 —�?宿主绑定的就是它，但其文�?角标已被清空�?
                            //      屏幕显示为空内容而非错配�?
                            // �?异步清洗�?026-09-25 修正）：getter 调用栈里绝不能同步删列表—�?
                            //   removeAt 会让下标位移，宿主拿着旧下标继续取/�?�?内容错位
                            //   （这正是「还是乱的」的新来源）。全部投递到主线程异步执行�?
                            try {
                                CfhState.handler.post {
                                    try {
                                        val vmNow = CfhState.vmRef
                                        if (vmNow != null) CfhWash.filterVmLists(vmNow)
                                        // [已移�?2026-09-26] scrubShownDirty：功能早已删除，调用点清�?
                                    } catch (_: Throwable) {}
                                }
                                if (CfhState.vmGetSubCount < 20) {
                                    CfhState.vmGetSubCount++
                                    Logger.always("vm getter ${m.name} dirty -> async delete+scrub (no swap): ${CfhUtil.readCaption(clsQ)?.take(18)}")
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
                r
            }
        }
    }

    private fun installVmY(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.d("vmY hook: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
        Logger.safe("hookVMY.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.y.${c.name}.${m.name}").intercept { chain ->
                try {
                    // ★★ 白名单优先（2026-09-26）：VM 取数方法，带 List 入参�?
                    // ★ 性能修复（第二批 · 2026-09）：此处原本对**同一个 chain.args 连调两次**
                    //   filterWhitelist，且第一行的返回值被丢弃 —— 与 knhb T0/E1 完全同型
                    //   （见本文件 :610-623 已落地的等价性论证）。
                    //
                    //   为什么删掉多余那次是**等价**的（已核对 CfhClean.kt:126-274）：
                    //   · filterWhitelist 对每个 List 参数是**单遍完整扫描**
                    //     （`while (i < la.size)`），一次删净 DIRTY(:176) 与 PENDING(:251)，
                    //     **没有「每次调用处理上限」**；
                    //   · 两次调用之间没有任何代码改动列表（同一 chain.args、同一批 list 实例）
                    //     ⇒ 第一遍之后列表内已无 DIRTY/PENDING ⇒ 第二遍必然返回 0，
                    //     所以原先那行 `if (removed > 0)` 的日志**恒不触发**（假信号）；
                    //   · 列表终态与「调两次」完全一致，而 `removed` 变成**真实条数**
                    //     ⇒ 诊断日志恢复有效。
                    //
                    //   收益：vmY 是 VM 取数通道，每条元素都要做 findQpInObject +
                    //   完整 judgeWhitelist；此前每条元素白做一整遍判定。
                    val removed = try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) { 0 } // ★ 白名单替代黑名单
                    if (removed > 0) Logger.d("vmY filtered list: $removed")
                } catch (_: Throwable) {}
                val r = chain.proceed()
                try {
                    if (CfhState.vmYDiag < 20) {
                        CfhState.vmYDiag++
                        Logger.d("vmY ret: ${m.name} -> ${r?.javaClass?.name ?: "null"}")
                    }
                } catch (_: Throwable) {}
                try { if (r is List<*>) CfhClean.filterWhitelist(r) } catch (_: Throwable) {} // ★ 白名单替代黑名单


                r
            }
        }
    }

    internal fun hookLiveFeedConstruct(xp: XposedInterface, cl: ClassLoader) {
        val cn = "com.kuaishou.android.model.feed.LiveStreamFeed"
        val c = Reflect.findClass(cn, cl) ?: return
        Logger.d("hookLiveFeedConstruct: $cn ctors=${c.declaredConstructors.size}")
        // dump 瀛楁鍚嶏紙浠呬竴娆★級
        if (CfhState.liveCtorDiag == 0) {
            var fc: Class<*>? = c
            while (fc != null && fc != Any::class.java) {
                for (f in fc!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    Logger.d("  LiveStreamFeed fld: ${fc.simpleName}.${f.name}:${f.type.simpleName}")
                }
                fc = fc.superclass
            }
        }
        for (ctor in c.declaredConstructors) {
            Logger.safe("hookLiveCtor.${ctor.parameterTypes.size}") {
                xp.hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("liveCtor.${cn}.${ctor.parameterTypes.size}").intercept { chain ->
                    val r = chain.proceed()
                    try {
                        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhState.liveTop) {
                            var zapped = 0
                            var fc: Class<*>? = r.javaClass
                            while (fc != null && fc != Any::class.java) {
                                val fcNow = fc!!
                                for (f in fcNow.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                    val fnl = f.name.lowercase()
                                    if (fnl.contains("live") || fnl.contains("stream") || fnl.contains("living") || fnl.contains("play")) {
                                        try {
                                            f.isAccessible = true
                                            when {
                                                f.type == Boolean::class.javaPrimitiveType -> { if (f.getBoolean(r)) { f.setBoolean(r, false); zapped++ } }
                                                f.type == Int::class.javaPrimitiveType -> { if (f.getInt(r) != 0) { f.setInt(r, 0); zapped++ } }
                                                f.type == Long::class.javaPrimitiveType -> { if (f.getLong(r) != 0L) { f.setLong(r, 0L); zapped++ } }
                                                else -> { val v = f.get(r); if (v != null) { f.set(r, null); zapped++ } }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                fc = fcNow.superclass
                            }
                            if (zapped > 0 && CfhState.liveCtorDiag < 30) { CfhState.liveCtorDiag++; Logger.d("liveCtor zap zapped=$zapped") }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    // 婧愬ご涔嬬帇锛歨ook LiveStreamFeed 鏋勯€犲嚱鏁帮紝鏋勯€犳椂鎶婃墍鏈夌洿鎾爣璇嗗瓧娈电疆�?�?false�?
    // 璁╁揩鎵嬪垽鍒负闈炵洿鎾紝鏍规湰涓嶅惎鍔ㄧ洿鎾覆鏌撶绾裤€傝繖鏍风洿鎾崱鍙樻垚绌哄３锛屼俊鎭祦鑷姩璺宠繃銆?

    internal fun ensureLiveFeedConstructHooked(ent: Any) {
        val xp = CfhState.xpRef ?: return
        val c = ent.javaClass
        if (!CfhState.liveCtorHookedCls.add(CfhUtil.hookKey(c))) return
        Logger.d("hookLiveCtor late: ${c.name} ctors=${c.declaredConstructors.size}")
        for (ctor in c.declaredConstructors) {
            Logger.safe("hookLiveCtorLate.${ctor.parameterTypes.size}") {
                xp.hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("liveCtorLate.${c.name}.${ctor.parameterTypes.size}").intercept { chain ->
                    val r = chain.proceed()
                    try {
                        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhState.liveTop) {
                            if (CfhState.liveCtorDiag < 1) {
                                CfhState.liveCtorDiag++
                                var dfc: Class<*>? = r.javaClass
                                while (dfc != null && dfc != Any::class.java) {
                                    val dfcNow = dfc!!
                                    for (f in dfcNow.declaredFields) {
                                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                        try { f.isAccessible = true; Logger.d("  LSF fld: ${dfcNow.simpleName}.${f.name}:${f.type.simpleName}=${f.get(r)?.javaClass?.simpleName ?: "null"}") } catch (_: Throwable) {}
                                    }
                                    dfc = dfcNow.superclass
                                }
                            }
                            var zapped = 0
                            var fc: Class<*>? = r.javaClass
                            while (fc != null && fc != Any::class.java) {
                                val fcNow = fc!!
                                for (f in fcNow.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                    val fnl = f.name.lowercase()
                                    if (fnl.contains("live") || fnl.contains("stream") || fnl.contains("living") || fnl.contains("play")) {
                                        try {
                                            f.isAccessible = true
                                            when {
                                                f.type == Boolean::class.javaPrimitiveType -> { if (f.getBoolean(r)) { f.setBoolean(r, false); zapped++ } }
                                                f.type == Int::class.javaPrimitiveType -> { if (f.getInt(r) != 0) { f.setInt(r, 0); zapped++ } }
                                                f.type == Long::class.javaPrimitiveType -> { if (f.getLong(r) != 0L) { f.setLong(r, 0L); zapped++ } }
                                                else -> { val v = f.get(r); if (v != null) { f.set(r, null); zapped++ } }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                fc = fcNow.superclass
                            }
                            if (zapped > 0 && CfhState.liveCtorDiag < 40) { CfhState.liveCtorDiag++; Logger.d("liveCtor zap zapped=$zapped") }
                        }
                    } catch (_: Throwable) {}
                    r
                }
            }
        }
    }
















    // 褰辫�?骞垮憡澹冲瓙瀛楁瀵规櫘閫氳棰戜篃鏄潪 null 绌哄�?�?蹇呴』鏌ュ唴灞傜湡瀹炲唴瀹规墠绠楀懡锟?





    // dump holder �?/ dumpMilanoHolder / forceRebindCurrent / applyWindowClean /
    // findCleanPos / adapterMainList 宸插垹闄わ細grep 璇佸疄闆惰皟鐢ㄦ浠ｇ爜锛圧8 release 浜﹀墺绂伙級

    // �?vm 绐楀彛鍙栧悓浣嶇疆瀵屾暟锟?qp锛堟樉绀烘簮瀹炰緥锛屽甫瀹屾�?user/caption�?

    // 骞插噣瑙嗛鍐欒�?vm 绐楀彛鍚屾Ы鐨?applyWindowClean 宸插垹闄わ細grep 璇佸疄闆惰皟鐢ㄦ浠ｇ爜

    // holder 鍥鹃噷锟?Fragment 瀹炰�?


    internal fun findDataSource(frag: Any) {
        Logger.safe("findDataSource") {
            val qc = CfhState.qpClassRef
            var vm: Any? = null
            var c: Class<*>? = frag.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(frag) ?: continue
                        if (v.javaClass.name.endsWith("SlidePlayViewModel")) {
                            Logger.probe { "NASA vm=${v.javaClass.name}" }
                            vm = v
                            // ★★★ v13.43/v13.46 主页引用**不在本处赋值**（2026-09-30）：
                            //   本分支（NASA Fragment 扫描）会命中**任何**带
                            //   SlidePlayViewModel 的 Fragment —— 用户切到关注页/同城页
                            //   时会把 homeDsRef 覆盖成那些页面的数据源 ⇒ 主动拉取拉回
                            //   关注/同城数据进池（用户质问「为什么要拉关注和同城」）。
                            //   ⇒ 改为只在 **HomeFeedResponse 到达**时确认发现页身份
                            //   （见 hookFeedResponse 的 HomeFeedResponse 分支）。
                            val ds = Reflect.callMethod(v, "getDataSource")
                            if (ds != null) {
                                Logger.probe { "NASA dataSource=${ds.javaClass.name}" }
                                hookDataSource(ds.javaClass)
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            // 娣锋穯鍏滃簳锛氭寜鏂规硶绛惧悕锟?(int)->QPhoto �?ViewModel
            if (vm == null && qc != null) {
                c = frag.javaClass; lvl = 0
                outer@ while (c != null && c != Any::class.java && lvl < 4) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(frag) ?: continue
                            val vn = v.javaClass.name
                            if (vn.startsWith("android.") || vn.startsWith("java.") || vn.contains("Fragment")) continue
                            var n = 0
                            var mc: Class<*>? = v.javaClass
                            var ml = 0
                            while (mc != null && ml < 4) {
                                for (m in mc!!.declaredMethods) {
                                    if (m.returnType == qc && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) n++
                                }
                                mc = mc.superclass; ml++
                            }
                            if (n >= 2) {
                                vm = v
                                Logger.probe { "NASA vm fallback=${vn} sig=$n" }
                                break@outer
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
            }
            if (vm != null) {
                hookViewModel(vm!!)
                val ds = Reflect.callMethod(vm!!, "getDataSource")
                if (ds != null) {
                    Logger.probe { "NASA dataSource=${ds.javaClass.name}" }
                    hookDataSource(ds.javaClass)
                }
            }
        }
    }
}

