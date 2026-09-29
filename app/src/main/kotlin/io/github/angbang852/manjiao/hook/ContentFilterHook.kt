package io.github.angbang852.manjiao.hook

import android.app.Activity
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// ★ ContentFilterHook 深拆第五步：收口为入口协调器 + 对外 API 转发（2026-09 S3）。
// 装钩按三层外移：CfhLcHook（生命周期）/CfhFeedHook（数据源）/CfhViewHook（渲染层）。
object ContentFilterHook {
    // shouldFilterMeta 用（每 10s 一次）：Regex 预编译，不随调用重建


    // ★ 直播页上下文放行：精选 tab 的过滤/删除链路（filterListArgs/laFind/TRUEDEL/zap）
    // 对直播页（LiveSlideActivity 等 com.kuaishou.live.* 页面）是灾难——直播页与精选容器
    // 共享数据引用（Ip() 从 slideplay 容器取 items），删共享列表/zap 直播实体字段会把
    // 直播页 pager 掏空或打成空壳 → 直播间黑屏、滑不动、底栏切换失效（实证 07:51 del=1 left=0 后卡死）。
    // 直播页在前台时：所有内容判定放行（shouldFilterFeed=false）、构造 zap 跳过。
    // 非快手主包 Activity（系统弹窗等）不改变状态，防弹窗期间误恢复过滤。

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        CfhState.xpRef = xp
        CfhState.clRef = cl
        // ★ 进程起点（供启动窗判定用）：CfhSupply.triggerLoadMore 的 in-flight 等待阈值
        // 在启动窗内收紧（5s→1.2s），依据就是「now - processStartAt」。
        CfhState.processStartAt = System.currentTimeMillis()
        // ★ 启动期身份前置（2026-09-21 实测修复）：qpClassRef 原先**只**由 hookViewModel
        // 赋值（约启动后 8 秒、全屏详情页起来时），而首批数据约 6 秒就进数据源 —— 那 1.1~2 秒
        // 窗口内 qpClassRef 为 null，判脏链路的 QPhoto 解包（findQpInObject）与实体类型判定
        // 整体失灵 → 开头几条被判"干净"放行（实测时间轴：32.8s 首批 6 条 del=0，
        // 33.5s hookViewModel 才赋值，34.8s 才首次命中）。
        // 此处用 hook() 已有的 app 类加载器就地解析，把身份建立提前到装钩时刻。
        try {
            CfhState.qpClassRef = Class.forName("com.yxcorp.gifshow.entity.QPhoto", false, cl)
            Logger.once("boot.qphoto", "QPHOTO identity preloaded (boot window covered)")
        } catch (t: Throwable) { Logger.d("QPHOTO preload fail: ${t.message}") }
        CfhLcHook.hookActivityLifecycle(xp)
        // ★ 禁止自动进入直播间（播放控制开关，默认关）：装在跳转发起处
        try { CfhLcHook.hookBlockAutoLive(xp) } catch (_: Throwable) {}
        Logger.d("VER=rerank-v2 hook() cl=$cl")
        val targets = setOf(
            KsClass.PHOTO_DETAIL_ACTIVITY, KsClass.PHOTO_DETAIL_ACTIVITY_TABLET,
            "com.yxcorp.gifshow.HomeActivity", "com.yxcorp.gifshow.HomeActivityTablet"
        )
        for (a in targets) {
            val c = Reflect.findClass(a, cl) ?: continue
            val mOn = Reflect.findMethod(c, "onResume", 0)
            if (mOn != null) xp.hook(mOn).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("filter.on.$a").intercept { chain ->
                chain.proceed()
                try { CfhLcHook.startTrack(chain.thisObject as Activity) } catch (_: Throwable) {}
                null
            }
            val mOff = Reflect.findMethod(c, "onPause", 0)
            if (mOff != null) xp.hook(mOff).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("filter.off.$a").intercept { chain ->
                chain.proceed()
                try { CfhLcHook.stopTrack(chain.thisObject as Activity) } catch (_: Throwable) {}
                null
            }
            Logger.d("FilterHook on $a")
        }
        CfhLcHook.hookNasaFragment(xp, cl)
        // ★ 精选页「当前条」直取（2026-09-24）：hook 快手自己的
        //   SlidePlayViewModel.getCurrentPhoto()。这是「文案不变、画面在变」
        //   的正解 —— 前两版（读列表字段 / 自行登记反查）都失败了，
        //   详见 CurrentPhotoHook 的类注释。
        CurrentPhotoHook.install(xp, cl)
        // ★ 屏幕对象溯源（2026-09-24）：自顶向下排查「AI 视频拦不住」。
        // ──────────────────────────────────────────────────────────────
        // 【首页信息流拦截 2026-09-26】—— **实测有效、零崩溃**
        //
        // ## 链路（全部实证）
        // ```
        // 网络响应
        //     ↓
        // ★ PresenterV2.bind(Object[])        ← ███ 拦在这里
        //     · data[10] = QPhoto（直接）
        //     · data[7]  = 类 c（含同一 QPhoto）
        //     ↓
        // updateData 分发
        //     ↓
        // sni.o.doInject() → inject(QPhoto.class) → this.e = QPhoto
        //     ↓
        // sni.o.onBind() → 渲染首页卡片
        // ```
        // 拦住 `bind` ⇒ QPhoto 进不了注入容器 ⇒ `sni.o.e` 为 null
        // ⇒ **卡片根本不创建**（不是空壳）。
        //
        // ## 怎么找到的
        // 用用户亲眼所见的「Ai不释手」作为关键词，hook `TextView.setText` 过滤
        // ⇒ 命中后打印**父 View 链** ⇒ 暴露 `feedstaggercard` + `mortise` 体系
        // ⇒ dex 反编译 `sni.o` 见 `this.e = (QPhoto) inject(QPhoto.class)`
        // ⇒ 追 `PresenterV2.bind` 见 `data[10]` 就是 QPhoto。
        // 详见 `docs/交互图/报告-首页信息流拦截-最终方案.md`。
        //
        // ## 为什么之前的尝试全部失败
        // 模块此前挂的 `HomeFeedResponse` / `SlidePlayViewModel` / `CfhWash`
        // 抓到的内容**和屏幕显示完全无关**（用户报的 13 个名字全部 0 命中，
        // 而模块同时抓到 30 余个别的昵称）——
        // **首页信息流走 `feedstaggercard` + `mortise` 体系，是独立链路。**
        // ──────────────────────────────────────────────────────────────
        try { PresenterBindHook.install(xp, cl) } catch (_: Throwable) {}
        // ★ 详情页取数拦截（2026-09-26 用户定稿方案 C）
        try { DetailFeedHook.install(xp, cl) } catch (_: Throwable) {}
        // ★ inject 崩溃护栏（2026-09-27）：接住「未提供数据」异常
        try { InjectCrashGuard.install(xp, cl) } catch (_: Throwable) {}
        // ★ AI 声明字段生命周期探针（2026-09-27，一次性诊断）
        // [已完成使命] DisclaimerTrace —— 已证明 content 由 Gson 反射注入（无 setContent）
        // try { DisclaimerTrace.install(xp, cl) } catch (_: Throwable) {}
        // ★ 角标数据源终极定位（2026-09-27）：hook QPhoto.getDisclaimerMessage + w.L
        // [已清理 2026-09-27] BadgeSourceTrace.install(xp, cl)
        // ★ 声明字段真实位置定位（2026-09-27）
        // [已清理 2026-09-27] DisclaimerLocator.install(xp, cl)
        // ★ 全量 Gson 解析探针（2026-09-27，一次性诊断：找详情页推荐流的真实解析点）
        // [已清理 2026-09-27] GsonTrace.install(xp, cl)
        // ★ QPhoto 诞生全量探针（2026-09-27 终极定位）
        // [已清理 2026-09-27] QPhotoBirthTrace.install(xp, cl)
        // ── 以下为本轮已停用的项（保留说明，便于日后复核）──────────────
        // [已停用] ScreenTrace.install —— noteRendered 每帧跑 findHolders（遍历 VM 全部字段）
        //           + findQpInObject ⇒ 开销极大，用户报「很卡」
        // [已停用] ListAddInterceptor —— 挂 ArrayList.add（全局最热方法）⇒ native crash
        // [已停用] HomeFeedHook —— 挂的是精选页链路（zqh.q1 / zqh.p1 / rmk.f），首页抓不到
        // [已停用] FeedCheckProbe —— 同上
        // [已停用] HomeFeedKrHook —— CoronaPlayerReactViewGroupManager 未被调用
        // [已停用] StaggerCardHook —— 拦 sni.o.onBind 生效（14 条），
        //           但卡片已创建 ⇒ 只剩空壳；改由 PresenterBindHook 在更上游拦
        // [已停用] KeywordLocateProbe —— 定位完成后移除
        // [已停用] RenderTrace / HomeRootProbe / ListAddProbe / 各诊断探针 —— 已删文件
        CfhFeedHook.hookFeedResponse(xp, cl)
        // ★★★ v13.38 删除 hookHomeRequester 调用（2026-09-30 用户定案）：
        //   池是共享的（cleanPool 唯一），首页滑到的干净内容本就自动进池 ⇒
        //   「池空主动拉首页数据」整条链路（请求器捕获→load()→配对晋升）多余，
        //   已全删。函数本体保留注释留痕，不再调用。
        // [已移除 2026-09-26] CfhQPhotoChecker：注册成功但 recognizeAsInvalidData 从未被调用
        // ★★★ 响应解析层拦截（2026-09-26）—— **所有下游的源头**。
        //
        //   hook `KnownTypeAdapters$ListTypeAdapter.read()`，
        //   在 QPhoto 刚被反序列化创建、**副本还没产生**时按白名单清洗。
        //
        //   逆向确认链路：
        //     HomeFeedResponse$TypeAdapter.read()  case "feeds"
        //       → this.f.read()  → ListTypeAdapter.read()  ← 本 hook
        //         → collection.add(this.a.read(...))        ← QPhoto 诞生
        //
        //   详见 `CfhTtpParse` 的完整说明。
        try { CfhTtpParse.install(xp, cl) } catch (_: Throwable) {}
        CfhFeedHook.hookCacheClasses(xp, cl)
        CfhFeedHook.hookMilanoContainers(xp, cl)
        CfhFeedHook.hookPageLists(xp, cl)
        CfhViewHook.hookLiveRerank(xp, cl)
        CfhFeedHook.hookKnhbT0(xp, cl)
        CfhFeedHook.hookLiveFeedConstruct(xp, cl)
        CfhViewHook.hookKrnProbe(xp, cl)
        CfhViewHook.hookKrnReactContainerView(xp, cl)
        CfhLcHook.hookFragmentCrashGuard(xp, cl)
        // ★ 启动期配置转储（2026-09-21）：排除"开关状态靠猜"——每次冷启动把生效的
        // 过滤/播放/装饰开关一次性打进日志。
        // ★ 调用级门控（性能修复 2026-09 · M1）：改用 Logger.once——不受 quiet 影响
        // 但每进程只打一行，排障时无需关闭静默即可看到；且**拼接动作本身也延迟到
        // quiet 判断之后**（原先无论 quiet 与否都会先拼好整条字符串并读 20 次 Prefs）。
        try {
            Logger.once("boot.fltcfg") {
                fun b(k: String, d: Boolean = false) = if (Prefs.bool(k, d)) "1" else "0"
                "FLTCFG ads=${b(Prefs.K_FLT_ADS)} advideo=${b(Prefs.K_FLT_ADVIDEO)} image=${b(Prefs.K_FLT_IMAGE)} " +
                    "live=${b(Prefs.K_FLT_LIVE)} ai=${b(Prefs.K_FLT_AI)} ec=${b(Prefs.K_FLT_EC)} " +
                    // ★ 默认值 true→false（2026-09-30）：与 anyFilterOn() 的 flt_drama 口径一致
                    //   （全链路默认统一为关）。本行只是启动期配置转储，改默认值只为日志如实反映。
                    "drama=${b(Prefs.K_FLT_DRAMA, false)} like_on=${b(Prefs.K_FLT_LIKE_ON)} " +
                    "kw_on=${b(Prefs.K_FLT_KW_ON)} kw=[${Prefs.str(Prefs.K_FLT_KEYWORDS, "").take(40)}] " +
                    // ★ 默认 true→false（2026-09-30）：bootflush/homerefill 与各自消费点口径统一
                    "bootflush=${b(Prefs.K_FLT_BOOTFLUSH, false)} homerefill=${b(Prefs.K_FLT_HOMEREFILL, false)} | pb_noLoop=${b(Prefs.K_PB_NO_LOOP)} " +
                    "pb_bgPause=${b(Prefs.K_PB_BG_PAUSE)} noAutoLive=${b(Prefs.K_PB_NO_AUTO_LIVE)} gold=${b(Prefs.K_IMM_GOLD)} diag=${b(Prefs.K_DIAG)} " +
                    // ★ 默认 true→false（2026-09-30）：与 Module.kt / SyncService.kt 的 K_PERF_QUIET 口径统一
                    "quiet=${b(Prefs.K_PERF_QUIET, false)}"
            }
        } catch (_: Throwable) {}
    }


    // ==================== 对外 API 转发（职责拆分后调用点不变） ====================
    fun invalidateFilterCache() = CfhDecide.invalidateCaches()
    fun refreshContent(): Boolean = CfhSupply.refreshContent()
    fun currentFeedPhoto(): Any? = CfhCapture.currentFeedPhoto()
    fun currentFeedFragment(): Any? = CfhCapture.currentFeedFragment()
    fun isCaptureTrusted(): Boolean = CfhCapture.isCaptureTrusted()
    fun findPhotoById(pid: String): Any? = CfhCapture.findPhotoById(pid)
    fun visibleEntries(): List<CfhCapture.VisEntry> = CfhCapture.visibleEntries()
    fun readVisibleUserName(qp: Any): String = CfhCapture.readVisibleUserName(qp)

    // ==================== 拦截审计 API（功能 4/5/6，2026-09） ====================

    /** 拦截记录快照（最新在前）。返回副本，避免 UI 遍历时与写入并发 */
    fun auditRecords(): List<CfhState.HitRecord> =
        synchronized(CfhState.hitRecords) { CfhState.hitRecords.toList().reversed() }

    /** 命中统计快照（规则名 → 次数），按次数降序 */
    fun auditStats(): List<Pair<String, Int>> =
        CfhState.filterHitStats.entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** 累计拦截总数 */
    fun auditTotal(): Int = CfhState.hitTotal

    /**
     * ★「标为误拦」整组已移除（2026-09 用户判断该功能无意义）：
     *   信息流场景下被拦视频不会再次出现 ⇒ 白名单几乎不可能命中；
     *   误拦的正确出口是调整/关闭误伤的规则，不是逐条放行。
     *   连带移除：markFalsePositive / unmarkFalsePositive / allowedCount /
     *   allowedIds / CfhState.allowPhotoIds / CfhDecide.isUserAllowed 短路 /
     *   Module.ACTION_AUDIT_ALLOW 广播分支。
     */

    /** 清空拦截记录（保留统计） */
    fun clearAuditRecords() {
        synchronized(CfhState.hitRecords) { CfhState.hitRecords.clear() }
    }

    /** 重置观测基线：清统计并把此刻记为「近期」窗口起点 */
    fun resetStatsBaseline() {
        CfhState.statsBaselineAt = System.currentTimeMillis()
        CfhState.filterHitStats.clear()
        CfhState.hitTotal = 0
    }

    /** 统计观测窗口起点（用于「统计时长」显示） */
    fun statsBaselineAt(): Long = CfhState.statsBaselineAt

    // ==================== 分时统计 API（24h / 48h / 7d，2026-09） ====================

    /**
     * 指定时间窗内的命中序列（按小时升序，缺失小时补 0）。
     * @param hours 24 / 48 / 168
     */
    fun hourlySeries(hours: Int): List<Pair<Long, Int>> = CfhState.hourlySeries(hours)

    /** 指定时间窗内按规则聚合的命中（降序）—— 图表数据源 */
    fun statsInWindow(hours: Int): List<Pair<String, Int>> = CfhState.ruleTotalsInWindow(hours)

    /** 指定时间窗内的命中总数 */
    fun totalInWindow(hours: Int): Int = CfhState.ruleTotalsInWindow(hours).sumOf { it.second }

    /** 分时数据是否为空（用于空状态提示） */
    fun hasHourlyData(): Boolean = CfhState.hourlyHits.isNotEmpty()

    /** 清空分时数据 */
    fun clearHourly() {
        CfhState.hourlyHits.clear()
        CfhState.hourlyByRule.clear()
    }

    // ==================== 模块状态（2026-09-26 用户要求）====================

    /**
     * 钩子状态：`(名称, 是否成功, 详情)`
     *
     * 数据来源：各 hook 安装时写入 `CfhState.hookStatus` 的记录。
     * **未记录 = 未安装**（这也是用户要看的「有没有钩子没钩上」）。
     */
    fun hookStatus(): List<Triple<String, Boolean, String>> =
        CfhState.hookStatusList()

    /**
     * 解析状态：`(名称, 是否成功, 详情)`
     *
     * 关键类/方法是否找到（如 `sni.o` / `PresenterV2` / `ListTypeAdapter`）。
     * **失败项即「解析失败」**。
     */
    fun parseStatus(): List<Triple<String, Boolean, String>> =
        CfhState.parseStatusList()

    /**
     * 功能开关状态：`(名称, 是否开启, 详情含本次运行命中数)`
     */
    fun switchStatus(): List<Triple<String, Boolean, String>> {
        val out = ArrayList<Triple<String, Boolean, String>>()
        fun add(name: String, key: String, def: Boolean) {
            val on = Prefs.bool(key, def)
            out.add(Triple(name, on, if (on) "已开启" else "已关闭"))
        }
        // ★★★ 默认值统一为「关」（2026-09-30 用户定稿）：
        //   用户设计意图（原话）：「模块安装后默认就应该是**全关**啊。但我自己用肯定是要开的。」
        //   本列表供「模块状态 → 功能开关」页显示，此前 ads/advideo/drama/ai/like_on 传 true，
        //   与 anyFilterOn()（CfhDecide.kt:1207-1211，全 false）不一致 —— 干净安装会显示
        //   「已开启」而实际一条都不拦。现全部对齐为 false。只改默认值，不改任何判定逻辑。
        add("过滤广告", Prefs.K_FLT_ADS, false)
        add("过滤广告视频", Prefs.K_FLT_ADVIDEO, false)
        add("过滤图文", Prefs.K_FLT_IMAGE, false)
        add("过滤直播", Prefs.K_FLT_LIVE, false)
        add("过滤电商", Prefs.K_FLT_EC, false)
        add("过滤影视", Prefs.K_FLT_DRAMA, false)
        add("过滤AI", Prefs.K_FLT_AI, false)
        add("按点赞数过滤", Prefs.K_FLT_LIKE_ON, false)
        add("按字段过滤", Prefs.K_FLT_KW_ON, false)
        // 白名单是核心语义开关，单独标注
        out.add(
            Triple(
                "白名单模式",
                CfhState.whiteListEnabled,
                if (CfhState.whiteListEnabled) "判正常才放行" else "未启用"
            )
        )
        return out
    }

    /** 记录一个钩子的安装结果（供各 hook 安装处调用） */
    fun noteHook(name: String, ok: Boolean, detail: String = "") =
        CfhState.noteHookStatus(name, ok, detail)

    /** 记录一个解析结果（供解析处调用） */
    fun noteParse(name: String, ok: Boolean, detail: String = "") =
        CfhState.noteParseStatus(name, ok, detail)
}
