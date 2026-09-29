package io.github.angbang852.manjiao.ui

import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.InputType
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.Module
import io.github.angbang852.manjiao.R
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.data.StorageDirs
import io.github.angbang852.manjiao.data.SyncService

class SettingsActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var bgImg: ImageView
    private lateinit var glass: GlassPanel
    private lateinit var panel: LinearLayout

    // ★ 面板恢复器（审阅 2026-09 P1）：onResume 原先无条件 showMain——冷启动双重
    // 构建、旋转/回前台丢子页状态。每个 showX 注册自身，onResume 重建当前页
    // （重建时重读 Prefs，ACTION_PULL 回包前的旧值由本地持久层兜底）
    private var panelRestorer: (() -> Unit)? = null

    /**
     * 当前是否停在主页面（**不能用 `panelRestorer === ::showMain` 判断**）。
     *
     * ★ 2026-09 修复「重进还是显示未激活」——根因就在这里。
     *
     * Kotlin 的 bound callable reference（`::showMain`）**不保证引用同一性**：
     * 每次求值都可能产生新的函数对象。所以
     *
     * ```kotlin
     * if (panelRestorer === ::showMain)   // ← 恒为 false！
     * ```
     *
     * 永远不成立，导致 `onBindStateChanged` 与状态轮询**全部空转**：
     * service 绑定完成、`isActive()` 已返回 true，界面却停在第一次渲染的
     * 「未激活」不再更新。实测日志（01:49）铁证：
     *
     * ```
     * onBindStateChanged: isActive=true  restorerIsShowMain=false
     * pollTick:           svc=true       restorer=false
     * ```
     *
     * 改用显式布尔标记，语义清晰且不依赖任何引用比较细节。
     */
    private var onMainPage = false

    /** 统计页图表模式（功能 5）：0=趋势 1=条形 2=环形 3=柱状 4=雷达 5=热力 6=列表 */
    private var statsChartMode = 0

    /** 统计页时间窗（2026-09 用户要求）：24 / 48 / 168 小时 */
    private var statsWindowHours = 24

    override fun onPause() {
        super.onPause()
        // ★ 注销绑定回调：Activity 不可见时不该再收通知（避免持有已暂停的界面）
        try { SyncService.setStateListener(null) } catch (_: Throwable) {}
        // ★ 输入落盘：EditText 只在失焦时保存，切后台/返回时主动 clearFocus
        // 触发保存回调，防静默丢输入（审阅 2026-09 P2）
        try { currentFocus?.clearFocus() } catch (_: Throwable) {}
        // 离开时全量推送（对齐快手进程可能错过的配置；快手收不到也无害）
        try { Prefs.broadcastAll(this) } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        Prefs.reload()
        // 拉取快手侧当前配置（用户可能在快手悬浮菜单里改过，防止这里的旧值显示/回滚）。
        // ★ 显式 setPackage 投递两个快手包（审阅 2026-09 P3）：隐式广播可被任意
        // 第三方注册同名 action 嗅探
        for (pkg in arrayOf(KsClass.PKG, KsClass.PKG_NEBULA)) {
            try {
                sendBroadcast(
                    android.content.Intent(Prefs.ACTION_PULL).setPackage(pkg)
                        .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                )
            } catch (_: Throwable) {}
        }
        // ★ 绑定状态回调（2026-09 修复「都关闭后打开 app 显示未激活」/「一直显示正在检查」）：
        // XposedServiceHelper 绑定是**异步**的，绑定完成时 Activity 往往已经渲染完。
        // 这里注册回调，绑定完成/断开时自动重绘状态卡。
        // ★ 状态刷新（2026-09 心跳法）：不再依赖框架绑定回调。
        // 直接重绘一次 + 短暂轮询几次即可 —— 心跳的有无是确定的，读一次就知道，
        // 不存在「需要等某个事件才能知道」的情况。轮询只是为了覆盖
        // 「打开 app 的瞬间快手刚好在写心跳」这种极短窗口。
        try {
            SyncService.setStateListener { onBindStateChanged() }
        } catch (_: Throwable) {}
        // ★ 面板恢复（2026-09 加入闪烁修复）：
        // 停在子页 → 调对应 showX() 重建子页（内容确实不同，必须重建）；
        // 已在主页 → 调 showMain()，**交给指纹守卫**判定是否需要重建，
        // 避免与 onCreate 末尾那次重复渲染造成闪烁。
        //
        // 判据用 onMainPage 而不是比较恢复器引用 —— 理由见 onMainPage 的注释：
        // 函数引用的同一性在 Kotlin 里不可靠，这里绝不能再踩一次。
        if (onMainPage) {
            showMain()                              // 已在主页：由指纹守卫决定是否重建
        } else {
            // 停在子页 → 恢复子页（必须重建）；
            // 恢复器为 null（首次进入）→ 建主页
            (panelRestorer ?: { showMain(force = true) }).invoke()
        }
    }

    /** 状态变化时的界面响应（由 SyncService 回调，已在主线程） */
    private fun onBindStateChanged() {
        if (isFinishing || isDestroyed) return
        // ★ 修复（2026-09）：原判据 `panelRestorer === ::showMain` 恒为 false
        // （bound callable reference 不保证引用同一性），导致回调空转、
        // 界面停在初始的「未激活」。详见 onMainPage 的注释。
        if (onMainPage) {
            try { showMain() } catch (_: Throwable) {}
        }
    }

    /**
     * ★ 状态轮询已移除（2026-09 修复「菜单要闪好几下」）。
     *
     * 原实现：`onResume` 后 1.5 秒内每 500ms 调一次 `showMain()`，共 3 次。
     *
     * 为什么可以删：
     * - 它的设计前提是**心跳法** —— 心跳由快手进程定时写入，会随时间刷新，
     *   所以需要在打开页面后的一小段时间内反复读取，才能覆盖「快手恰好正在写心跳」的窗口。
     * - 而判据现已改为 `service.scope`（见 `SyncService.isActive`）。scope 由框架下发，
     *   **在页面生命周期内恒定不变**，反复读只会得到同一个值 ⇒ 每次轮询都是无谓的全量重绘。
     * - 真正会变的两个信号都已有更准确的触发源：
     *   · 「绑定完成/断开」→ `SyncService.setStateListener` 回调（见 [onBindStateChanged]）
     *   · 「切回前台/快手启停」→ `onResume` 重跑
     *
     * 另外 [showMain] 现有**内容指纹守卫**兜底：即使将来又加了新的重绘触发点，
     * 只要内容没变就不会重建视图树，不会重新引入闪烁。
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ XposedService 收编：模块 App 侧初始化（绑定目标进程 provider 拿共享配置）
        try { io.github.angbang852.manjiao.data.SyncService.init() } catch (_: Throwable) {}
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        Prefs.init(this)
        Prefs.reload()
        // 建齐模块专属目录（2026-09）：用户改下载路径后也要立刻生效，
        // 所以这里按当前配置再建一次（幂等）
        try { StorageDirs.ensureAll(Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH)) } catch (_: Throwable) {}
        buildRoot()
        showMain()
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun buildRoot() {
        root = FrameLayout(this)
        // 背景=清晰背景图（全屏，不模糊）
        bgImg = ImageView(this).apply {
            setImageResource(R.drawable.bg_main)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(bgImg)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ★ 左右留白收紧（2026-09 用户要求「菜单宽度加一些」）：
            // 原 24dp 两侧，在 1440px 屏上白占掉约 168px，信息行显得挤。
            // 收到 14dp —— 玻璃面板自身还有 14dp 投影边距 + 16dp 内边距，
            // 视觉留白依然充足，但可用宽度多出约 70px。
            setPadding(dp(14), dp(56), dp(14), dp(24))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        container.addView(titleView("ManJiao"))
        container.addView(space(2))
        // app 侧背景无 SF 跨窗模糊加持：wash 在模块默认 40% 基础上调 50%
        //（60% 偏实被要求降）；模块菜单不传该参数保持原值
        // ★ 恒深（2026-09 用户定稿方案 A 同源）：不再跟随系统浅色变淡白 ——
        //   浅色模式下 0x80EFF1F7（50% 淡白）叠在深色花卉背景图上会把背景整体
        //   洗白，白色文字随之失去对比（用户实测「app 里的字怎么变成白色的了 /
        //   面板也变白了」）。背景图与文字体系本就是深底白字设计，wash 固定深灰
        //   与模块菜单（0xB32F3036）保持一致，深浅模式下均恒定可读。
        val washBoost = 0x802F3036.toInt()
        glass = GlassPanel(this, dp(36).toFloat(), washBoost)
        // ★ 菜单背景模糊层：仅菜单面板区域内叠一份 RenderEffect 模糊的背景图副本
        //（全屏背景图保持清晰）。放 GlassPanel 之下、与其内框精确对齐（margin=shadowPad），
        // 绘制栈 = 模糊背景 → wash/gloss/rim 玻璃表面 → 菜单内容
        val glassWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val blurBg = ImageView(this).apply {
            setImageResource(R.drawable.bg_main)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    val r = dp(10).toFloat()
                    setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
                } catch (_: Throwable) {}
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(36).toFloat())
                }
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(glass.shadowPad, glass.shadowPad, glass.shadowPad, glass.shadowPad)
            }
        }
        glassWrap.addView(blurBg)
        glass.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        glassWrap.addView(glass)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ★ 上下边界留白（2026-09 用户要求「上下要设个边界」）：
            // 12dp 在圆角玻璃上仍显贴边，加大到 16dp；左右同步，避免文字压到圆角
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // 关掉边缘回弹：允许把内容拖出边界再弹回，观感上就是「内容滑出去了」
            overScrollMode = View.OVER_SCROLL_NEVER
            // 内容不得画出留白区（默认 true，显式写出防误改）
            clipToPadding = true
        }
        scroll.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        glass.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(glassWrap)
        root.addView(container)
        setContentView(root)
    }

    private fun clearPanel() { panel.removeAllViews() }

    // ==================== 首页：模块信息面板（2026-09 重设计） ====================
    //
    // 用户要求：app 端**不再显示拦截数据**，改为展示模块自身信息 ——
    // 版本 / 支持范围 / 激活状态 / 仓库与反馈入口 / 打开模块菜单。
    //
    // 设计取舍：此前首页是一列纯文字条目，看不出「模块到底生效没有」。
    // 现在顶部做成一张**状态卡**：用醒目的色块直接回答「能不能用」，
    // 下面才是功能入口。用户打开 app 第一眼就知道状态，不用去猜。

    /**
     * 已适配的快手版本清单 —— 「支持的版本」那一行的**唯一数据源**。
     *
     * ★★★ 2026-09-30 修正（用户指出「支持的版本没显示全，适配了 4 个版本」）：
     *
     * **根因不是渲染截断，是这张清单本身只写了 1 条**。上一版这里是
     * `listOf("14.7.40.49980" to "已实测")`，所以折叠态显示「已实测 1 个版本」、
     * 展开也只有 1 行。渲染侧 `supportedVersionsRow()` 老老实实遍历整张表
     * （没有 `first` / `take` / 去重 / 截断），表里有几条它就画几条 ——
     * 也就是说「显示不全」与渲染无关，是数据源缺项。
     *
     * 历史注释（2026-09，用户问「支持的版本里不就目前自测了这一个版本么」）当时把
     * 《快手版本适配文档》里那几个版本判为「待验证的目标」而删掉了。但项目文档此后
     * 已把这四版**定案为适配覆盖范围**：
     *   - `docs/现状与决策.md`「模块与目标版本」：
     *     「当前实测版本 14.8.30.50465（实机全通）。适配覆盖四版：
     *       14.7.40.49980 / 14.8.20.50218 / 14.8.20.50388 / 14.8.30.50465」
     *   - `docs/版本适配.md` 正是这四版的 dex 符号复验表（49980 / 50218 / 50388 / 50465）。
     * 所以这里补齐为 4 条。
     *
     * ★ 标注按**实际验证程度**分别写，不统一写「已实测」：
     * `docs/版本适配.md` 的「不适用条件」明确记着「14.7.40.49980 / 14.8.20.50388
     * 只做过静态，没做真机验证」。给它们标「已实测」就是给出没有依据的承诺 ——
     * 这正是上一版注释里警惕过的那个错误，不能反过来再犯一次。
     *
     * ★★★ 2026-09-30 更正（用户确认）：`14.8.20.50388` 由「仅静态复验」改为「已实测」。
     *   用户是该版本真机验证历史的唯一知情人，明确指示 50388 已做过真机实测；
     *   `docs/版本适配.md` 的「不适用条件」那句只反映文档编写时的状态，已过期。
     *   据此把该条标注改为「已实测」，并在文档该处补一句确认说明（不删原文）。
     *   其余 3 条标注不变。
     *
     * ★★★ 2026-09-30 更正（用户确认）：`14.7.40.49980` 同属「已实测」，上一条的
     *   「其余 3 条标注不变」已过期。用户是该版本真机验证历史的唯一知情人，明确指示
     *   49980 也已做过真机实测；`docs/版本适配.md` 的「不适用条件」那句（把 49980 与
     *   50388 并列判为「只做过静态」）至此**两个版本均已实测、整体作废**，已在文档该处
     *   补第二句确认说明（不删原文）。清单 4 条标注本身无需改动 —— 本文件一直写着
     *   「已实测」，与文档更正后一致。
     *   日期经三方核对（本机系统时间 / 设备时间 / 模块 evidence 日志时间戳）均为 2026-09-30。
     */
    private val SUPPORTED_KS_VERSIONS = listOf(
        "14.7.40.49980" to "已实测",
        "14.8.20.50218" to "已实测",
        "14.8.20.50388" to "已实测",   // 2026-09-30 用户确认已真机验证（原「仅静态复验」）
        "14.8.30.50465" to "已实测 · 当前设备",
    )

    /** 折叠态概要 */
    private fun supportedSummary(): String {
        val n = SUPPORTED_KS_VERSIONS.size
        // ★ 2026-09-30：措辞由「已实测 N 个版本」改为「适配 N 个版本」。
        //   清单里混有「仅静态复验」的版本（14.8.20.50388），折叠态统一说「已实测」
        //   会与展开后的逐条标注自相矛盾。
        //   ★ 2026-09-30 补：50388 经用户确认已真机验证，四版标注现已全部是「已实测」；
        //   但折叠态措辞仍保持「适配 N 个版本」（描述范围，不承诺验证程度，无需改）。
        return "适配 $n 个版本"
    }

    companion object {
        /** 项目仓库（与「检查更新」用的 API 同源，避免两处地址不一致） */
        const val REPO_URL = "https://github.com/ManJiao-App/io.github.angbang852.manjiao"

        /** 问题反馈：直接落到 Issues 新建页 */
        const val FEEDBACK_URL = "$REPO_URL/issues/new"
    }

    /**
     * 反馈链接（**预填环境信息**）。
     *
     * 用户要求「问题反馈要预填信息」：模块类问题十有八九卡在版本不匹配，
     * 而「你用的哪个版本」是每次都要问的第一句。这里把能自动拿到的都填进去，
     * 用户只需补一句现象描述，省掉一轮来回。
     *
     * 用 GitHub 的 `body` 查询参数预填正文（`title` 也预填一个模板，便于归类）。
     *
     * ★ 2026-09 段落顺序调整（用户反馈「参数肯定要放前面啊，问题要放后面」）：
     *   环境信息（自动填好的「参数」）在前，问题描述（需要用户手写的）在后。
     *   理由：自动填充的部分用户不需要动，放前面不影响输入；
     *   而把待填写的「问题描述」放在末尾，打开页面即可直接滚到底开始写，
     *   不必先跨过一大段自己看不懂的表格。
     */
    private fun feedbackUrl(): String {
        val fi = SyncService.frameworkInfo()
        val body = buildString {
            appendLine("### 环境信息（自动填充，请勿删除）")
            appendLine()
            appendLine("| 项 | 值 |")
            appendLine("|---|---|")
            appendLine("| 模块版本 | ${appVersion()} (${appVersionCode()}) |")
            appendLine("| 快手版本 | ${ksVersionText()} |")
            appendLine("| 激活状态 | ${if (SyncService.isActive()) "已激活" else "未激活"} |")
            appendLine("| 框架 | ${if (fi == null) "未连接" else "${fi.first} ${fi.second}"} |")
            appendLine("| 框架 API | ${SyncService.apiVersion()} |")
            appendLine("| 系统版本 | Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) |")
            appendLine("| 设备 | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} |")
            appendLine("| ABI | ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")} |")
            appendLine()
            appendLine("### 问题描述")
            appendLine()
            appendLine("<!-- 请在此描述你遇到的问题、复现步骤、期望结果 -->")
        }
        val title = "[Bug] "
        return "$FEEDBACK_URL?title=${enc(title)}&body=${enc(body)}&labels=bug"
    }

    /** URL 编码（GitHub 的查询参数不接受原始换行与特殊字符） */
    private fun enc(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { "" }

    /**
     * 主页面内容指纹 —— 用于**避免无意义的重绘**（2026-09 修复「菜单要闪好几下」）。
     *
     * ★ 问题：一次进入页面，`showMain()` 实测被调用 **7 次**：
     * ```
     * #1  onCreate 末尾 buildRoot() 后调用（首次构建，必要）
     * #2  onResume (panelRestorer ?: ::showMain).invoke()   ← 与 #1 显示内容完全相同
     * #3  setStateListener 注册即回调一次                    ← 同上
     * #4~#7  startStatusPolling 的 1.5s/500ms 轮询          ← 同上
     * ```
     * 每次都会 `clearPanel()` + 全量重建视图树，用户看到的就是「闪好几下」。
     *
     * ★ 为什么这些重绘**全都没必要**：刷新机制的判据已经从「心跳」改成了
     * `service.scope`（见 SyncService.isActive）。而 scope 由框架下发，
     * **在页面生命周期内恒定不变** —— 所以为「心跳会随时间刷新」设计的轮询，
     * 现在读的是一个永远不变的值，纯属白刷。
     *
     * ★ 方案：不删除重绘触发点（它们各自有正当职责，比如 onResume 要从子页返回首页），
     * 而是在 [showMain] 入口加**内容指纹守卫** —— 指纹相同就跳过重建。
     * 这样既消除闪烁，又不改变任何状态语义：
     * 状态**真的变了**（例如打开/关闭快手导致 scope 或 targetState 变化）时仍会重建。
     *
     * 指纹构成 = 状态卡与信息卡的**全部输入**，任一变化都会让指纹不同。
     */
    private fun mainContentFingerprint(): String {
        val st = SyncService.targetState()
        val fi = SyncService.frameworkInfo()
        return buildString {
            append("act=").append(SyncService.isActive())
            append("|run=").append(SyncService.runningTargets().isNotEmpty())
            append("|st=").append(st)
            append("|fw=").append(fi?.first).append('/').append(fi?.second)
            append("|api=").append(SyncService.apiVersion())
            append("|ks=").append(ksVersionText())
        }
    }

    /** 上一次已渲染的主页面指纹（null = 尚未渲染过） */
    private var lastMainFingerprint: String? = null

    /**
     * 重建主页面。
     *
     * @param force true = 忽略指纹强制重建（用于 `onResume` 从子页返回首页等
     *              需要真正重画的场景；注意返回首页时 `onMainPage` 本是 false，
     *              不 force 会被指纹判定为「内容相同」而拒绝重画）
     */
    private fun showMain(force: Boolean = false) {
        val fp = mainContentFingerprint()
        // ★ 指纹守卫：内容与上次渲染完全一致 → 不重建（消除闪烁）
        if (!force && onMainPage && fp == lastMainFingerprint) return
        lastMainFingerprint = fp
        onMainPage = true
        // 用 lambda 而非 `::showMain`：带默认参数的函数引用类型是 (Boolean) -> Unit，
        // 不能赋给 () -> Unit；且函数引用的同一性不可靠（见 onMainPage 注释）
        panelRestorer = { showMain(force = true) }
        clearPanel()

        // ---- 状态卡 ----
        panel.addView(statusCard())
        panel.addView(space(10))

        // ---- 信息区 ----
        panel.addView(sectionLabel("模块信息"))
        panel.addView(infoCard())
        panel.addView(space(10))

        // ---- 操作区 ----
        panel.addView(sectionLabel("快捷操作"))
        val actions = listOf(
            Item("🪟", "打开快手", "分享按钮 → 模块入口") { openModuleMenuHint() },
            Item("🌐", "项目仓库", "查看源码与更新日志") { openUrl(REPO_URL) },
            Item("💬", "问题反馈", "自动带上版本信息，无需手填") { openUrl(feedbackUrl()) },
            Item("⬆️", "检查更新", "从仓库获取最新版本") { checkUpdate(false) },
        )
        for ((i, item) in actions.withIndex()) {
            panel.addView(itemRow(item))
            if (i < actions.size - 1) panel.addView(divider())
        }
        panel.addView(space(12))

        // ★ 不再放设置项（2026-09 用户反馈「怎么下面还有设置项」）：
        // 模块菜单（快手内双击唤出）里已有一模一样的设置入口，
        // app 端既然定位为「信息面板 + 快捷入口」，重复一份只会让人困惑
        // 「到底该在哪儿改」。需要改设置时引导用户去模块菜单即可。

        // 自动检查更新（限频 5 分钟，见 checkUpdate）
        checkUpdate(true)
        jellyEnter(panel)
    }

    /** 小节标题 */
    private fun sectionLabel(text: String): View {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(0x80FFFFFF.toInt())
            // 左对齐与卡片/行保持一致（卡片左右各多 4dp，这里跟着对齐）
            setPadding(rowPadH() + dp(4), dp(10), rowPadH(), dp(6))
        }
    }

    /**
     * 状态卡：一眼看出「模块现在到底有没有在起作用」。
     *
     * ★ 2026-09 第 5 版（**照搬 WeKit 的判据**，见 SyncService.isActive 注释）。
     *
     * 前四版全部失败，根因是**判据选错了信号源**，而不是刷新时机：
     *
     * | 版本 | 判据 | 为什么错 |
     * |---|---|---|
     * | v1 | `service != null` | 只代表 binder 绑定成功，快手没跑时同样为真 |
     * | v2 | `runningTargets().isNotEmpty()` | 只说明进程在跑，不说明注入成功 |
     * | v3 | `HookedTarget.getState()` + 轮询 | 方向对，但依赖我自造的 settle 轮询 |
     * | v4 | **心跳**（快手进程写 pid+时间戳） | **两个通道都写不进去**（见下） |
     *
     * v4 的心跳法为什么必然失效 —— 已实测定位：
     * - 通道 2（`/sdcard/Android/media/<模块包名>/`）：目录属主是**模块自己**
     *   （`u0_a224`，组 `media_rw`，other 无权限），而快手以 `u0_a1305` 运行、
     *   不在该组 ⇒ 写入 EACCES。模块日志里早有同一现象：
     *   `prefs media dead (EACCES), switch to broadcast-only`。
     * - 通道 1（`SyncService.rp`）：`rp` 的**唯一赋值点在 app 进程的 `onServiceBind`**，
     *   快手进程里恒为 `null` ⇒ `rp?.edit()?...` 静默空转。
     *   两处 `catch (_: Throwable) {}` 把失败吞干净，所以既不显示数据也不显示错误。
     *
     * 现在照 WeKit 的做法：**不写任何东西，直接读框架下发的 service 状态**。
     *
     * | 状态 | 判据 | 含义 | 用户该做什么 |
     * |---|---|---|---|
     * | 未激活 | `scope` 不含快手 | 框架没把快手纳入本模块作用域 | 去 LSPosed 勾选「快手」 |
     * | 已激活 | `scope` 含快手，快手未运行 | 框架已授权，等快手启动 | 打开快手 |
     * | 生效中 | 且 `getState() == UP_TO_DATE` | 已注入且版本最新 | 无需操作 |
     * | **需重启** | 且 `getState() == STALE` | 注入的是**旧版模块** | **重启快手** |
     * | 注入失败 | 且 `getState() == FAILED` | 框架尝试注入但失败 | 看框架日志反馈 |
     *
     * 注意：`getState()` **只在快手运行时才有意义**，所以「是否运行」这条
     * 靠 `runningTargets()` 判断（进程是否被模块作用且存活），不再依赖心跳。
     */
    private fun statusCard(): View {
        val activated = SyncService.isActive()          // 判据 = service.scope ⊇ 快手包名
        val targets = SyncService.runningTargets()      // 正在运行且被作用的目标（主进程）
        val running = targets.isNotEmpty()
        val st = if (running) SyncService.targetState() else null

        val green = 0xFF7BE38C.toInt()
        val red = 0xFFFF8A80.toInt()
        val amber = 0xFFFFB74D.toInt()
        val idle = 0xFF9E9E9E.toInt()

        val (title, sub, accent) = when {
            // ① 框架没把快手纳入作用域 —— 唯一真正的「未激活」
            !activated -> Triple(
                "未激活",
                "LSPosed 中启用本模块，并在「作用域」里勾选「快手」",
                red
            )
            // ② 已授权，但快手没在跑
            !running -> Triple(
                "已激活",
                "模块已就绪；打开快手后即生效",
                green
            )
            // ③ 快手在跑，看框架给出的逐进程状态
            st == SyncService.TargetState.STALE -> Triple(
                "已激活 · 需重启快手",
                "快手内运行的是旧版模块，强行停止快手后重新打开即可生效",
                amber
            )
            st == SyncService.TargetState.FAILED -> Triple(
                "已激活 · 注入失败",
                "框架尝试注入但失败了，请查看 LSPosed 日志",
                red
            )
            st == SyncService.TargetState.RELOADING -> Triple(
                "已激活 · 正在重载",
                "框架正在重新注入模块，稍候片刻",
                amber
            )
            st == SyncService.TargetState.UP_TO_DATE -> Triple(
                "已激活 · 生效中",
                "模块正在快手内运行",
                green
            )
            // ④ 快手进程在跑，但框架没报它被注入 —— 多半是刚启动、状态还没上报
            else -> Triple(
                "已激活 · 等待生效",
                "检测到快手在运行；若长时间如此，请强行停止快手后重新打开",
                idle
            )
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 卡片自身有圆角边框，内边距比普通行略大一点，内容才不贴边框
            setPadding(rowPadH() + dp(4), dp(18), rowPadH() + dp(4), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(0x1AFFFFFF)
                setStroke(dp(1), (accent and 0x00FFFFFF) or 0x66000000)
            }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        // 状态圆点
        titleRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
                .also { it.setMargins(0, 0, dp(10), 0) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(accent)
            }
        })
        titleRow.addView(TextView(this).apply {
            text = title; textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(accent)
        })
        card.addView(titleRow)
        card.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, dp(8), 0, 0)
        }.also { outline(it) })
        return card
    }

    /**
     * 信息卡里**不显示**的行（2026-09-30 用户要求：「适配状态」和它下面的「获取中」
     * 这两项不需要显示）。
     *
     * ★ 只影响**显示**：下方 `rows` 仍照原样构造（所有取值表达式都会照常求值），
     *   只是渲染前把这两行滤掉。这样做的理由见 [infoCard] 里的逐条说明。
     */
    private val HIDDEN_INFO_ROWS = setOf("适配状态", "数据加密")

    /** 信息卡：模块版本 / 快手版本 / 支持版本（可展开） / 框架 */
    private fun infoCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(0x14FFFFFF)
            }
        }
        val fi = SyncService.frameworkInfo()
        val allRows = listOf(
            Triple("模块版本", "${appVersion()}  (${appVersionCode()})", false),
            Triple("快手版本", ksVersionText(), false),
            // 「支持版本」用可展开行（用户要求「点开显示明确支持哪些精确版本号」）
            Triple("支持版本", "", true),
            // ★ 版本适配状态（2026-09 自适应适配）：把「本模块在你这个版本上实际
            // 识别到了什么、哪些 hook 装配失败」直接摆给用户看。
            // 排障价值：用户报「某功能无效」时，不必先问版本、再猜是哪一层断的。
            //
            // ★ 2026-09-30 用户要求不再显示这一行 ⇒ 由 HIDDEN_INFO_ROWS 滤掉。
            //   **保留取值调用**：`adaptationText()` 只是读宿主经共享配置回传的探测
            //   快照（KsHookKit.readPublished()），无副作用；但下面「数据加密」那行的
            //   取值**有**副作用，见该行注释 —— 所以两行统一走「照常构造、渲染前过滤」，
            //   不写成「直接从 listOf 里删掉」，免得以后有人顺手把副作用也删了。
            Triple("适配状态", adaptationText(), false),
            // ★★★ 数据加密状态（2026-09-30 用户规格 ②）
            //   本行同时是「钥匙通道」在 **app 侧的真实使用者**：进设置页即向快手进程
            //   索取解密钥匙（走既有 Prefs.PERM_SYNC 广播通道，不新增任何机制）。
            //   排障价值：一张截图就能看出「加密到底生效了没有」「钥匙通道通不通」。
            //
            // ★★★ 2026-09-30 用户要求不再显示这一行。
            //   用户看到的「获取中…」就是**这一行的值**（`encStatusText()` 在内存里
            //   还没有钥匙时的返回文案），不是一条独立条目 —— 所以「隐藏获取中」
            //   等于「隐藏整行」，不能只换文案。
            //   **取值调用必须保留**：`encStatusText()` 不是纯函数，它每次都会
            //   ① 读一次内存钥匙缓存、② 未命中就起后台线程向快手进程索取钥匙、
            //   ③ 落一行 `files/keydiag.txt` 自检记录。删掉这一行 = 顺手关掉钥匙通道
            //   在 app 侧的**唯一使用者**（KeyVault.kt 的注释里点名了这里），
            //   属于「把背后的功能一起关掉」，明确越界。
            //   所以这里是**只藏显示、照常执行**。
            Triple("数据加密", encStatusText(), false),
            // [已迁移 2026-09-26 用户要求] 「解析缓存」移到模块菜单「模块状态 → 功能状态」
            //   原行：Triple("解析缓存", KsCache.statusText(), false)
            Triple("框架", if (fi == null) "未连接" else "${fi.first} ${fi.second}", false),
            Triple("框架 API", if (SyncService.apiVersion() == 0) "—" else SyncService.apiVersion().toString(), false),
        )
        // ★ 先滤掉不显示的行，**再**按可见行逐行补分隔线。
        //   顺序不能反：若在循环里 `continue` 跳过隐藏行，分隔线仍按原下标
        //   （`i < rows.size - 1`）判断，末行会多出一条悬空细线、被跳过的那一行位置
        //   也会留下错位的分隔线 —— 正是本次要避免的「空白占位 / 错位分隔线」。
        val rows = allRows.filter { it.first !in HIDDEN_INFO_ROWS }
        for ((i, r) in rows.withIndex()) {
            if (r.third) {
                card.addView(supportedVersionsRow())
            } else {
                card.addView(kvRow(r.first, r.second))
            }
            if (i < rows.size - 1) card.addView(hairline())
        }
        return card
    }

    /**
     * ★★★ 数据加密状态文案 + **钥匙通道自检**（2026-09-30 用户规格 ②）
     *
     * 为什么要有它：规格 ② 要求「模块 app 需要解密时，通过既有签名权限通道向
     * 快手进程索取钥匙」。通道光实现不跑等于没做，所以这里给它一个**真实使用者**：
     * 每次进设置页就索取一次，并把结果落一份自检记录。
     *
     * 自检记录写在 **app 自己的 filesDir**（不是 `.sys/`）：模块 app 是 targetSdk 35
     * 的普通应用，对 `/sdcard/Download/...` 没有保证可写的权限；而 filesDir 一定可写，
     * 且 debug 包可以用 `adb shell run-as io.github.angbang852.manjiao cat files/keydiag.txt`
     * 直接读出来验证。记录里**只有指纹与状态，绝无钥匙本身**。
     *
     * 刻意不 `recreate()` 刷新界面：快手没在跑时拿不到钥匙，recreate 会变成
     * 「每次重建又发起一次索取」的死循环。第二次进页面时内存里已有缓存，正常显示。
     */
    private fun encStatusText(): String {
        val kv = io.github.angbang852.manjiao.util.KeyVault
        kv.appKey()?.let { return "AES-GCM 已启用 · 钥匙指纹 ${kv.fingerprint(it)}" }
        try {
            Thread {
                try {
                    val k = kv.appKeyBlocking(1500L)
                    val line = "${System.currentTimeMillis()} 钥匙通道自检 有=${k != null} " +
                        "指纹=${kv.fingerprint(k)} diag=${kv.lastDiag}\n"
                    try { java.io.File(filesDir, "keydiag.txt").appendText(line) } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; name = "MJ-KeyDiag" }.start()
        } catch (_: Throwable) {}
        return "获取中…（需快手进程在跑；自检见 app 私有 files/keydiag.txt）"
    }

    private fun hairline(): View = View(this).apply {
        setBackgroundColor(0x1AFFFFFF)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        ).also { it.setMargins(dp(18), 0, dp(18), 0) }
    }

    /**
     * 「支持版本」可展开行。
     *
     * 折叠态显示适配数量；展开后逐条列出**精确版本号**及对应的验证程度标注，
     * 并说明「不在列表里不等于不能用」—— 模块靠反射匹配，同系列小版本通常也可用。
     *
     * ★ 2026-09-30 用户要求「支持的版本没显示全」⇒ 改为**默认展开**。
     *   此前默认 `GONE`，进页面只能看到折叠态的「已实测 N 个版本」，
     *   4 个版本号要**点一下**才出现。用户要的是「一眼看全」，所以默认摊开；
     *   折叠能力保留（点标题仍可收起），不删交互。
     *
     * ★★★ 2026-09-30 再改（用户要求「改回默认收起」）：默认可见性由 `VISIBLE`
     *   改回 `GONE`，箭头由 `▴` 改回 `▾`，即回到折叠态；上一段「默认展开」的
     *   记录保留在此仅作沿革，**不再是当前行为**。点击展开/折叠的 listener 原样
     *   保留（点一下标题仍能展开看到 4 条版本号），只是初始不再摊开。
     *   折叠态文案不变，仍是「适配 N 个版本」（见 supportedSummary()）。
     */
    private fun supportedVersionsRow(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(rowPadH(), dp(13), rowPadH(), dp(13))
            isClickable = true; isFocusable = true; background = ripple()
        }
        head.addView(TextView(this).apply {
            text = "支持版本"; textSize = 14f; setTextColor(0x99FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val summary = TextView(this).apply {
            // ★ 2026-09-30 再改（用户要求）：改回**默认收起** ⇒ 箭头用 ▾，
            //   与下方 detail 的初始可见性（GONE）保持一致。
            text = "${supportedSummary()}  ▾"
            textSize = 14f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        }
        head.addView(summary)
        box.addView(head)

        // 展开区（★ 2026-09-30 再改：改回默认收起 GONE，见上方注释；点击可展开）
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(rowPadH(), 0, rowPadH(), dp(12))
        }
        for ((v, tag) in SUPPORTED_KS_VERSIONS) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            line.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(5), dp(5))
                    .also { it.setMargins(0, 0, dp(10), 0) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(0xFF7BE38C.toInt())
                }
            })
            line.addView(TextView(this).apply {
                text = v; textSize = 14f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            line.addView(TextView(this).apply {
                text = tag; textSize = 12f; setTextColor(0x80FFFFFF.toInt())
            })
            detail.addView(line)
        }
        detail.addView(TextView(this).apply {
            // ★ 措辞必须诚实（2026-09）：上一版写「同系列其它小版本通常也可用」，
            // 那是**没有实测依据的推断**。模块靠反射匹配宿主类，版本变化确实
            // 「可能」能用，但把推断写成结论会误导用户。这里改为如实说明：
            // 只保证列表内的，其它版本欢迎反馈结果。
            text = "只保证以上版本经过实测。模块靠反射匹配宿主类，" +
                "其它版本可能可用也可能失效 —— 若你在其它版本上使用，欢迎反馈结果，便于扩大支持范围。"
            textSize = 11.5f; setTextColor(0x80FFFFFF.toInt())
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        box.addView(detail)

        head.setOnClickListener {
            val show = detail.visibility != View.VISIBLE
            detail.visibility = if (show) View.VISIBLE else View.GONE
            summary.text = "${supportedSummary()}  ${if (show) "▴" else "▾"}"
        }
        return box
    }

    private fun kvRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(rowPadH(), dp(13), rowPadH(), dp(13))
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 14f; setTextColor(0x99FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value; textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        })
        return row
    }

    private fun appVersionCode(): Int = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        pi.versionCode
    } catch (_: Throwable) { 0 }

    /**
     * 已安装的快手版本号。
     *
     * 快手可能是普通版或极速版，两个包名都查一遍。
     *
     * ★ 查询可能被**隐私/隐藏模块拦截**（用户环境里有 HMA-OSS 这类应用隐藏工具，
     * 它会让 `getPackageInfo` 对未授权包抛 NameNotFoundException）。此时不能笼统
     * 说「未安装」—— 那会误导用户。区分三种结果：
     *   查到版本 / 包存在但版本读不到 / 确实没装
     */
    private fun ksVersionText(): String {
        var seenAny = false
        for (pkg in arrayOf(KsClass.PKG, KsClass.PKG_NEBULA)) {
            try {
                val pi = packageManager.getPackageInfo(pkg, 0)
                seenAny = true
                val name = if (pkg == KsClass.PKG) "快手" else "极速版"
                return "$name ${pi.versionName ?: "?"}"
            } catch (_: Throwable) {}
        }
        // 用 LSPosed 暴露的「正在运行的目标」反查 —— 它不经 PackageManager，
        // 不受应用隐藏模块影响，只要模块已注入就一定能看到进程名
        val running = try { SyncService.runningTargets() } catch (_: Throwable) { emptyList() }
        if (running.any { it == KsClass.PKG || it == KsClass.PKG_NEBULA }) {
            return "运行中（版本被隐藏工具拦截）"
        }
        return if (seenAny) "版本读不到" else "未检测到（可能被应用隐藏工具拦截）"
    }

    /**
     * 适配状态一行文案（2026-09 自适应适配）。
     *
     * ★ 为什么不能在**模块 app 进程**里直接读 KsVersion：
     *   [io.github.angbang852.manjiao.adapt.KsVersion] 的探测跑在**快手宿主进程**，
     *   而这里是模块自己的进程 —— 两个进程不共享静态状态。
     *
     *   所以改为读宿主**经共享配置回传**的探测结果（`KsHookKit.publish()` 写入）。
     *   取不到时显示「未连接」而不是编一个值 —— 宁可显示不知道，也不显示错的。
     *
     * 文案设计（对齐任务要求「探测失败时有明确降级路径与可读提示」）：
     * - 成功：`已识别 14.7.40.49980 · mid 档位`
     * - 探测失败但结构可推断：`版本未知 · 按 modern 结构推断`
     * - 有 hook 降级：追加 ` · 2 项降级`
     * - 宿主未连接：`未连接（打开快手后刷新）`
     */
    private fun adaptationText(): String {
        return try {
            val info = io.github.angbang852.manjiao.adapt.KsHookKit.readPublished()
            when {
                info == null -> "未连接（打开快手后刷新）"
                info.version.isNullOrBlank() -> "版本未知 · 按 ${info.tier} 结构推断"
                else -> "已识别 ${info.version} · ${info.tier} 档位" +
                    if (info.failedHooks > 0) " · ${info.failedHooks} 项降级" else ""
            }
        } catch (_: Throwable) { "读取失败" }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Throwable) { toast("无法打开浏览器") }
    }

    /**
     * 「打开快手」（2026-09 简化，用户定调）。
     *
     * ★ 原实现（第 21 轮）试图「从 app 请求快手进程弹菜单」：
     *   广播 + 阶梯重试 + 前台 Activity 等待。实测不稳定 —— 跨进程异步请求
     *   把正确性押在另一个进程的瞬时状态上，时序窗口无法根除。
     *   参考 WeKit 与抖鸡（逆向）后确认：两家成熟模块都没有「从外部打开宿主菜单」，
     *   它们的入口都长在宿主自己的界面里。
     *
     * ★ 现在的语义（用户定调）：打开快手，副标题告诉用户入口在哪 ——
     *   **分享按钮 → 模块入口**。快手内点分享，分享面板里的入口即是菜单。
     */
    private fun openModuleMenuHint() {
        val pkg = if (isInstalled(KsClass.PKG)) KsClass.PKG
        else if (isInstalled(KsClass.PKG_NEBULA)) KsClass.PKG_NEBULA else null
        if (pkg == null) { toast("未检测到快手，请先安装"); return }
        val launched = try {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) { i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); true }
            else false
        } catch (_: Throwable) { false }
        if (!launched) toast("请手动打开快手")
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Throwable) { false }

    /** 说明性信息行（标题 + 副标题）：开关说明、提示语等 */
    private fun infoRowNote(title: String, sub: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        box.addView(TextView(this).apply {
            text = title; textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { outline(it) })
        box.addView(TextView(this).apply {
            text = sub; textSize = 12f; setTextColor(0xB3FFFFFF.toInt())
            setPadding(0, dp(4), 0, 0)
        }.also { outline(it) })
        return box
    }



    /**
     * 规则名转可读中文 —— 委托到 [AuditViz.ruleLabel]。
     *
     * 2026-09：该映射表原先在 SettingsActivity 与 MainMenuDialog 各有一份，
     * 导致「一处修了另一处没修」。现统一收敛到 AuditViz（与类别色板同源）。
     */
    private fun ruleLabel(reason: String): String = AuditViz.ruleLabel(reason)

    private fun showGesture() {
        onMainPage = false
        panelRestorer = ::showGesture
        clearPanel()
        panel.addView(header("手势功能", true))
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "禁止双击点赞", Prefs.bool(Prefs.K_GS_NO_DBL_LIKE, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_GS_NO_DBL_LIKE, nv)
            toast(if (nv) "已禁止双击点赞" else "已恢复双击点赞")
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "双击打开评论区", Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_GS_OPEN_COMMENT, nv)
            if (nv && Prefs.bool(Prefs.K_GS_OPEN_MENU, false)) {
                Prefs.setBoolSync(this, Prefs.K_GS_OPEN_MENU, false)
                toast("已自动关闭「双击打开模块菜单」")
                showGesture()
            }
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "双击打开模块菜单", Prefs.bool(Prefs.K_GS_OPEN_MENU, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_GS_OPEN_MENU, nv)
            if (nv && Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false)) {
                Prefs.setBoolSync(this, Prefs.K_GS_OPEN_COMMENT, false)
                toast("已自动关闭「双击打开评论区」")
                showGesture()
            }
        })
        jellyEnter(panel)
    }

    private fun showPlayback() {
        onMainPage = false
        panelRestorer = ::showPlayback
        clearPanel()
        panel.addView(header("播放控制", true))
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "停止循环播放", Prefs.bool(Prefs.K_PB_NO_LOOP, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_PB_NO_LOOP, nv)
            toast(if (nv) "已停止循环播放" else "已恢复循环播放")
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "后台暂停播放", Prefs.bool(Prefs.K_PB_BG_PAUSE, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_PB_BG_PAUSE, nv)
            toast(if (nv) "已开启后台暂停" else "已关闭后台暂停")
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "禁止自动进入直播间", Prefs.bool(Prefs.K_PB_NO_AUTO_LIVE, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_PB_NO_AUTO_LIVE, nv)
            toast(if (nv) "已禁止自动进入直播间" else "已允许自动进入直播间")
        })
        jellyEnter(panel)
    }

    private fun showImmersive() {
        onMainPage = false
        panelRestorer = ::showImmersive
        clearPanel()
        panel.addView(header("沉浸式页面", true))
        panel.addView(divider())
        panel.addView(itemSwitchRow("🚀", "一键沉浸", Prefs.bool(Prefs.K_IMM_ON, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_ON, nv)
            toast(if (nv) "一键沉浸已开启" else "一键沉浸已关闭")
        })
        panel.addView(divider())
        panel.addView(itemRow(Item("🫥", "自定义隐藏", "") { showHideCustom() }))
        jellyEnter(panel)
    }

    private fun showHideCustom() {
        onMainPage = false
        panelRestorer = ::showHideCustom
        clearPanel()
        panel.addView(header("自定义隐藏", true) { showImmersive() })
        panel.addView(divider())
        panel.addView(itemSwitchRow("📊", "顶栏", Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false), hasSub = true, subAction = { showTopBarItems() }) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_TOPBAR_ON, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("👉", "右侧按钮", Prefs.bool(Prefs.K_IMM_RIGHT_ON, false), hasSub = true, subAction = { showRightBtnItems() }) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_RIGHT_ON, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("📋", "底栏", Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_BOTTOM_BAR, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("✍️", "昵称/文案", Prefs.bool(Prefs.K_IMM_NICKNAME, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_NICKNAME, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("📂", "合集", Prefs.bool(Prefs.K_IMM_COLLECTION, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_COLLECTION, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("🪙", "金币红包", Prefs.bool(Prefs.K_IMM_GOLD, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_GOLD, nv)
        })
        jellyEnter(panel)
    }

    private val TOPBAR_ITEMS = arrayOf("左上角按钮", "王者送福利", "游戏", "玩游戏", "短剧", "同城", "关注", "发现", "精选", "直播", "搜索")
    private val RIGHT_ITEMS = arrayOf("关注", "喜欢", "评论", "收藏", "转发", "音乐封面")

    private fun showTopBarItems() {
        onMainPage = false
        panelRestorer = ::showTopBarItems
        clearPanel()
        panel.addView(header("顶栏隐藏项", true) { showHideCustom() })
        panel.addView(divider())
        val selected = Prefs.strSet(Prefs.K_IMM_TOPBAR).toMutableSet()
        for (s in TOPBAR_ITEMS) {
            panel.addView(itemSwitchRow("", s, s in selected) { nv ->
                if (nv) selected.add(s) else selected.remove(s)
                Prefs.setStrSetSync(this, Prefs.K_IMM_TOPBAR, selected)
            })
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun showRightBtnItems() {
        onMainPage = false
        panelRestorer = ::showRightBtnItems
        clearPanel()
        panel.addView(header("右侧按钮隐藏项", true) { showHideCustom() })
        panel.addView(divider())
        val selected = Prefs.strSet(Prefs.K_IMM_RIGHT_ITEMS).toMutableSet()
        for (s in RIGHT_ITEMS) {
            panel.addView(itemSwitchRow("", s, s in selected) { nv ->
                if (nv) selected.add(s) else selected.remove(s)
                Prefs.setStrSetSync(this, Prefs.K_IMM_RIGHT_ITEMS, selected)
            })
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun showFilter() {
        onMainPage = false
        panelRestorer = ::showFilter
        clearPanel()
        panel.addView(header("内容过滤", true))
        panel.addView(divider())

        // ★★★ 行定义默认值统一为「关」（2026-09-30 用户定稿）。
        //   用户设计意图（原话）：「模块安装后默认就应该是**全关**啊。但我自己用肯定是要开的。」
        //   缺陷：此前 ads/advideo/drama/like_on 4 行传 def=true ⇒ 干净安装时界面显示「开」，
        //   而 anyFilterOn()（CfhDecide.kt:1207-1211）对同 9 键全传 def=false ⇒ 实际一条都不拦。
        //   「显示开、实际关」误导用户。现与 anyFilterOn() 口径统一为 false。
        //   ★ 只改默认值：已显式保存过的键仍按存值显示（Prefs.bool 读到值即忽略 def）。
        //   ★ 疑似AI(flt_ai_suspect) 行保持 true 不动 —— 见报告「待确认」。
        val rows = arrayOf(
            arrayOf("过滤广告内容", Prefs.K_FLT_ADS, false, false),
            arrayOf("过滤广告视频", Prefs.K_FLT_ADVIDEO, false, false),
            arrayOf("过滤图文内容", Prefs.K_FLT_IMAGE, false, false),
            arrayOf("过滤直播内容", Prefs.K_FLT_LIVE, false, false),
            arrayOf("过滤AI生成内容", Prefs.K_FLT_AI, false, false),
            arrayOf("过滤疑似AI内容", Prefs.K_FLT_AI_SUSPECT, false, false),
            arrayOf("过滤电商内容", Prefs.K_FLT_EC, false, false),
            arrayOf("过滤影视内容", Prefs.K_FLT_DRAMA, false, false),
            arrayOf("按点赞数过滤", Prefs.K_FLT_LIKE_ON, false, true),
            arrayOf("按字段过滤", Prefs.K_FLT_KW_ON, false, true),
            
            // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）
            arrayOf("首次进主页刷新", Prefs.K_FLT_BOOTFLUSH, false, false),
            arrayOf("首页内容池接精选页", Prefs.K_FLT_HOMEREFILL, false, false,
                "说明：开启后池子见底时会自动去首页拉货 —— 有时会精选页跳到首页再跳回。\n" +
                "关掉则精选页不再补位、不再续拉，池子耗尽后刷不出新内容。"),
        )
        // ★ 移除「近期未命中」标注（2026-09）：该标注依赖跨进程读取命中统计，
        // 而 app 进程拿不到快手进程的数据（详见 AuditBridge 注释），标注恒为「未命中」，
        // 反而误导用户以为开关没生效。拦截数据现在只在模块菜单里查看。
        for (r in rows) {
            @Suppress("UNCHECKED_CAST")
            val key = r[1] as String
            val def = r[2] as Boolean
            val on = Prefs.bool(key, def)
            panel.addView(itemSwitchRow("", r[0] as String, on) { nv ->
                Prefs.setBoolSync(this, key, nv)
                showFilter()
            })
            if (r[3] == true) {
                val isLike = key == Prefs.K_FLT_LIKE_ON
                panel.addView(inputRow(
                    if (isLike) "点赞数阈值（低于此值过滤，0=不限制）" else "按字段过滤关键词（逗号分隔）",
                    if (isLike) Prefs.int(Prefs.K_FLT_LIKE_TH, 1000).toString() else Prefs.str(Prefs.K_FLT_KEYWORDS, ""),
                    if (isLike) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                ) { v ->
                    if (isLike) Prefs.setIntSync(this, Prefs.K_FLT_LIKE_TH, v.toIntOrNull() ?: 0)
                    else Prefs.setStrSync(this, Prefs.K_FLT_KEYWORDS, v.trim())
                })
                panel.addView(space(8))
            }
            // ★ v13.94 副作用说明（仅带第 5 项的行渲染）：复用「性能优化」页的说明样式
            if (r.size > 4) {
                panel.addView(TextView(this).apply {
                    text = r[4] as String
                    textSize = 12f
                    setTextColor(0xB3FFFFFF.toInt())
                    setPadding(dp(20), 0, dp(20), dp(10))
                }.also { outline(it) })
            }
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    /** 带副标注的开关行（功能 6）：在标题下方显示「近期未命中」等提示 */
    private fun itemSwitchRowWithNote(icon: String, title: String, checked: Boolean, note: String, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16)); isClickable = true; isFocusable = true; background = ripple()
        }
        if (icon.isNotEmpty()) {
            row.addView(TextView(this).apply { text = icon; textSize = 22f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) })
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(14), 0) })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(TextView(this).apply { text = title; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) }.also { outline(it) })
        col.addView(TextView(this).apply {
            text = note; textSize = 11f; setTextColor(0xFFFFD48A.toInt()); setPadding(0, dp(3), 0, 0)
        }.also { outline(it) })
        row.addView(col)
        val (sw, toggle) = createIosSwitch(checked) { onToggle(it) }
        sw.isClickable = true; sw.isFocusable = true
        sw.setOnClickListener { toggle() }
        row.addView(sw, LinearLayout.LayoutParams(dp(56), dp(32)))
        row.setOnClickListener { toggle() }
        return row
    }

    private fun showPerf() {
        onMainPage = false
        panelRestorer = ::showPerf
        clearPanel()
        panel.addView(header("性能优化", true))
        panel.addView(divider())
        val rows = arrayOf(
            Triple("仅主进程注入", Prefs.K_PERF_MAINPROC, "跳过消息/推送/沙盒等子进程，加速启动、防无响应（重启快手生效）"),
            Triple("过滤判定缓存", Prefs.K_PERF_FCACHE, "同一视频只判定一次，减少滑动卡顿"),
            Triple("低频配置同步", Prefs.K_PERF_LOWFREQ, "配置广播从30秒降至5分钟一次，消除后台风暴"),
            Triple("模块日志静默", Prefs.K_PERF_QUIET, "停止日志logcat输出"),
            Triple("诊断日志", Prefs.K_DIAG, "打印深度判定/探针日志用于排障。开销大，排障完请关闭（可独立于「日志静默」单独开启）"),
            Triple("刷屏日志拦截", Prefs.K_PERF_LOGSPAM, "native层拦截P2P日志刷屏+Invalid resource ID错误（降GC压力90%）"),
            Triple("拦截摇一摇广告", Prefs.K_PERF_SENSOR, "阻断加速度计高频监听，减少耗电发热（可能影响重力类功能）"),
        )
        for ((title, key, desc) in rows) {
            // ★ 默认值统一为 false（2026-09-30 用户定稿：全关，按需开启）：
            //   原为 `key != Prefs.K_PERF_SENSOR`（即除 SENSOR 外全 true）。
            val def = false
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply {
                text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }.also { outline(it) })
            val (sw, toggle) = createIosSwitch(Prefs.bool(key, def)) { nv -> Prefs.setBoolSync(this, key, nv) }
            sw.isClickable = true; sw.isFocusable = true
            sw.setOnClickListener { toggle() }
            line.addView(sw, LinearLayout.LayoutParams(dp(56), dp(32)))
            row.addView(line)
            row.addView(TextView(this).apply {
                text = desc; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(dp(2), dp(3), 0, 0)
            }.also { outline(it) })
            row.setOnClickListener { toggle() }
            panel.addView(row)
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun showPurify() {
        onMainPage = false
        panelRestorer = ::showPurify
        clearPanel()
        panel.addView(header("快手净化", true))
        panel.addView(divider())
        val rows = arrayOf(
            Triple("拦截推送服务", Prefs.K_PURIFY_PUSH, "阻止 MatrixPushV3Service 启动，减少后台推送唤醒"),
            Triple("拦截日志上报", Prefs.K_PURIFY_LOG, "阻断 ConanLogContentProvider 数据上报，减少隐私采集"),
            Triple("拦截 WebView 沙盒", Prefs.K_PURIFY_WEBVIEW, "阻止 SandboxedProcessService0 启动，减少 WebView 子进程开销"),
        )
        for ((title, key, desc) in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply {
                text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }.also { outline(it) })
            // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）
            val (sw, toggle) = createIosSwitch(Prefs.bool(key, false)) { nv -> Prefs.setBoolSync(this, key, nv) }
            sw.isClickable = true; sw.isFocusable = true
            sw.setOnClickListener { toggle() }
            line.addView(sw, LinearLayout.LayoutParams(dp(56), dp(32)))
            row.addView(line)
            row.addView(TextView(this).apply {
                text = desc; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(dp(2), dp(3), 0, 0)
            }.also { outline(it) })
            row.setOnClickListener { toggle() }
            panel.addView(row)
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun inputRow(label: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT, onSave: (String) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
        box.addView(TextView(this).apply { text = label; textSize = 14f }.also { outline(it) })
        box.addView(space(6))
        val et = EditText(this).apply {
            setText(value); this.inputType = inputType; textSize = 15f
            setTextColor(Color.WHITE); setHintTextColor(0x88FFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(8).toFloat(); setColor(0x33FFFFFF.toInt()) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onSave(et.text.toString()) }
        et.setOnEditorActionListener { _, _, _ -> onSave(et.text.toString()); et.clearFocus(); false }
        box.addView(et)
        return box
    }

    /**
     * 条目。
     *
     * ★ 2026-09 首页重设计：新增 [desc] 副标题 —— 信息型入口（仓库/反馈/打开菜单）
     * 一句话说明点进去会怎样，比只有标题更好懂；设置类入口 desc 传空串即可。
     */
    private class Item(
        val icon: String,
        val title: String,
        val desc: String = "",
        val hasSub: Boolean = true,
        val action: () -> Unit
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * 内容行左右内边距（**2026-09 用户要求「菜单宽度加一些」**）。
     *
     * 原先各行写死 20dp，与外层容器留白叠加后，内容可用宽度被吃掉一大截。
     * 现在统一降到 14dp —— 与标题栏/信息行保持一致，视觉上左右对齐，
     * 同时把宽度让给内容。要调整行内留白只改这一处。
     */
    private fun rowPadH() = dp(14)

    private fun outline(tv: TextView) { tv.setTextColor(Color.WHITE); tv.setShadowLayer(4f, 0f, 0f, Color.BLACK) }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)) }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x33000000.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(20), 0, dp(20), 0) }
    }

    private fun titleView(text: String): View {
        val stroke = TextView(this).apply {
            this.text = text; textSize = 40f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setTextColor(Color.WHITE)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 9f
        }
        val fill = TextView(this).apply {
            this.text = text; textSize = 40f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setTextColor(0xFFA0C4FF.toInt())
            post {
                try {
                    val w = paint.measureText(text.toString())
                    val left = (width - w) / 2f
                    paint.shader = LinearGradient(left, 0f, left + w, 0f, 0xFFA0C4FF.toInt(), 0xFFC9A0FF.toInt(), Shader.TileMode.CLAMP)
                    invalidate()
                } catch (_: Throwable) {}
            }
        }
        return FrameLayout(this).apply {
            addView(stroke, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
    }


    private fun header(text: String, hasBack: Boolean, onBack: () -> Unit = { showMain() }): View {
        // 右内边距与左对齐，右侧箭头/图标才不会贴边（原右侧仅 12dp）
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(rowPadH() + dp(4), dp(16), rowPadH() + dp(4), dp(14))
        }
        if (hasBack) {
            val back = TextView(this).apply {
                this.text = "‹"; textSize = 24f; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF),
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x59000000) },
                    null
                )
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            }
            outline(back); back.setOnClickListener { onBack() }; bar.addView(back)
            bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 0) })
        }
        bar.addView(TextView(this).apply {
            this.text = text; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }.also { outline(it) })
        return bar
    }

    private fun itemRow(item: Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(rowPadH(), dp(14), rowPadH(), dp(14))
            isClickable = true; isFocusable = true; background = ripple()
        }
        // 图标：带底色的圆角方块，比裸 emoji 更整齐（不同 emoji 宽度差异很大）
        row.addView(TextView(this).apply {
            text = item.icon
            textSize = 17f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0x1FFFFFFF)
            }
        })
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(14), 0) })
        // 标题 + 副标题
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = item.title; textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { outline(it) })
        if (item.desc.isNotEmpty()) {
            textCol.addView(TextView(this).apply {
                text = item.desc; textSize = 12f
                setTextColor(0x99FFFFFF.toInt())
                setPadding(0, dp(3), 0, 0)
            }.also { outline(it) })
        }
        row.addView(textCol)
        if (item.hasSub) row.addView(TextView(this).apply {
            text = "›"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(0x80FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        row.setOnClickListener { item.action() }
        return row
    }

    private fun itemSwitchRow(icon: String, title: String, checked: Boolean, hasSub: Boolean = false, subAction: (() -> Unit)? = null, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16)); isClickable = true; isFocusable = true; background = ripple()
        }
        if (icon.isNotEmpty()) {
            row.addView(TextView(this).apply { text = icon; textSize = 22f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) })
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(14), 0) })
        }
        row.addView(TextView(this).apply {
            text = title; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { outline(it) })
        val (sw, toggle) = createIosSwitch(checked) { onToggle(it) }
        sw.isClickable = true; sw.isFocusable = true
        sw.setOnClickListener { toggle() }
        row.addView(sw, LinearLayout.LayoutParams(dp(56), dp(32)))
        val arrowW = dp(40)
        if (hasSub) {
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 0) })
            row.addView(TextView(this).apply {
                text = "›"; textSize = 24f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(arrowW, dp(44))
                isClickable = true; isFocusable = true
            }.also { outline(it) }.also { tv -> tv.setOnClickListener { subAction?.invoke() } })
            row.setOnClickListener { subAction?.invoke() }
        } else {
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 0) })
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(arrowW, 0) })
            row.setOnClickListener { toggle() }
        }
        return row
    }

    private fun createIosSwitch(checked: Boolean, onToggle: (Boolean) -> Unit): Pair<View, () -> Unit> {
        val trackW = dp(56); val trackH = dp(32); val knobSize = dp(26); val pad = (trackH - knobSize) / 2
        val colorOn = 0xCC4CAF50.toInt(); val colorOff = 0xCC757575.toInt()
        val container = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(trackW, trackH) }
        val trackBg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = trackH / 2f; setColor(if (checked) colorOn else colorOff) }
        val track = View(this).apply { background = trackBg; elevation = dp(1).toFloat() }
        container.addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val knob = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }; elevation = dp(4).toFloat() }
        val knobLp = FrameLayout.LayoutParams(knobSize, knobSize, Gravity.CENTER_VERTICAL)
        knobLp.leftMargin = if (checked) trackW - knobSize - pad else pad
        container.addView(knob, knobLp)
        var isOn = checked
        val toggle: () -> Unit = {
            isOn = !isOn; trackBg.setColor(if (isOn) colorOn else colorOff); track.background = trackBg
            val fromLeft = knobLp.leftMargin; val targetLeft = if (isOn) trackW - knobSize - pad else pad; val dx = (targetLeft - fromLeft).toFloat()
            knob.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction {
                knob.animate().translationX(dx).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(OvershootInterpolator(2.4f)).withEndAction {
                    knobLp.leftMargin = targetLeft; knob.translationX = 0f; knob.layoutParams = knobLp
                }.start()
            }.start()
            onToggle(isOn)
        }
        return Pair(container, toggle)
    }

    private fun buttonRow(label: String, onAction: () -> Unit): View {
        val p40 = dp(40); val p14 = dp(14); val p12 = dp(12)
        val btn = TextView(this).apply {
            text = label; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(p40, p14, p40, p14); isClickable = true; isFocusable = true
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(0xFFFF6600.toInt()); cornerRadius = p12.toFloat() }
        }
        outline(btn); btn.setOnClickListener { onAction() }
        return btn
    }

    private fun ripple(): RippleDrawable = RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), ColorDrawable(Color.TRANSPARENT), null)

    private fun jellyEnter(view: View) {
        view.scaleX = 0.92f; view.scaleY = 0.92f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun appVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (_: Throwable) { "1.0" }

    // ★ 自动检查 5 分钟限频（审阅 2026-09 P3）：原实现每次进入关于页都发一次
    // GitHub API 请求。首页重设计后「检查更新」是普通入口，同样需要限频。
    private var lastAutoCheckAt = 0L

    private fun checkUpdate(auto: Boolean) {
        if (auto) {
            val now = System.currentTimeMillis()
            if (now - lastAutoCheckAt < 300_000L) return
            lastAutoCheckAt = now
        } else toast("检查更新中…")
        Thread {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL("https://api.github.com/repos/ManJiao-App/io.github.angbang852.manjiao/releases/latest").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "ManJiao")
                // ★ 响应码校验：429/5xx 时 body 是错误 JSON，原实现照样解析（tag 恒 null
                // 静默退出，无感知；限流期反复打 API 还会加重限流）
                val code = conn.responseCode
                if (code != 200) { runOnUiThread { if (!auto) toast("检查更新失败 (HTTP $code)") }; return@Thread }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                // release tag 格式为 VersionCode-VersionName（LSPosed 仓库规范），取 VersionName 段比较
                val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return@Thread
                val latest = tag.substringAfterLast('-')
                val dlUrl = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "https://github.com/ManJiao-App/io.github.angbang852.manjiao/releases"
                val cur = appVersion()
                runOnUiThread {
                    if (latest != cur) {
                        toast("发现新版本 $latest")
                        if (!auto) { try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(dlUrl))) } catch (_: Throwable) {} }
                    } else { if (!auto) toast("已是最新版本") }
                }
            } catch (t: Throwable) {
                runOnUiThread { if (!auto) toast("检查更新失败") }
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }.start()
    }
}
