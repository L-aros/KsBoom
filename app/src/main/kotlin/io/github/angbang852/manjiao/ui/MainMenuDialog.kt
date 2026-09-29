package io.github.angbang852.manjiao.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

import android.widget.TextView
import android.widget.Toast
import java.io.File
import io.github.angbang852.manjiao.KsClass

import io.github.angbang852.manjiao.data.CurrentVideo
import io.github.angbang852.manjiao.data.DownloadService
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.data.StorageDirs
import io.github.angbang852.manjiao.hook.CfhCapture
import io.github.angbang852.manjiao.hook.CfhState
import io.github.angbang852.manjiao.hook.ContentFilterHook
import io.github.angbang852.manjiao.hook.VideoDownloaderHook
import io.github.angbang852.manjiao.util.Logger


object MainMenuDialog {

    private val C_PRIMARY = 0xFFFF6600.toInt()

    private var currentOverlay: View? = null

    /** 统计页图表模式：0=趋势 1~5=规则图表 6=列表（2026-09 用户要求可切换） */
    private var statsChartMode = 0

    /** 统计页时间窗（2026-09 用户要求 24h/48h/7d）：24 / 48 / 168 小时 */
    private var statsWindowHours = 24
    fun isOverlay(v: View): Boolean = v === currentOverlay

    private class Item(val icon: String, val title: String, val hasSub: Boolean = true, val action: () -> Unit)

    private fun isDark(ctx: Context): Boolean {
        return (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun cTitle(ctx: Context) = Color.WHITE
    // ★ 恒深面板（2026-09 用户定稿方案 A）：分割线用淡白 —— 面板 wash 固定深灰后，
    //   原黑色分割线（0x33000000）在深玻璃上几乎不可见（用户实测反馈）
    private fun cDiv(ctx: Context) = 0x33FFFFFF.toInt()
    private fun cArrow(ctx: Context) = Color.WHITE
    private fun cRipple(ctx: Context) = 0x1A000000.toInt()
    private fun cInputBg(ctx: Context) = 0x99FFFFFF.toInt()

    private fun outline(tv: TextView) {
        tv.setTextColor(Color.WHITE)
        tv.setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private fun jellyEnter(view: View) {
        // ★ 性能修复（审阅 2026-09 · M3）：原用 OvershootInterpolator(2.6f) + 440ms。
        // 过冲插值器会让 scale 中途越过 1.0 再回落，配合玻璃面板的 backdrop 采样
        // 会产生额外的缩放态重绘；440ms 也偏长。改为 Decelerate 收尾、240ms。
        // 视觉上仍是「弹出后落定」，但不再有过冲带来的反向修正帧。
        view.scaleX = 0.82f
        view.scaleY = 0.82f
        view.alpha = 0f
        view.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(240)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    fun show(ctx: Context) {
        // ★ 延迟构建（审阅 2026-09 P2）：本方法常在触摸分发（GestureHook.dispatchTouchEvent）
        // 内被调用——同步建 UI + measure + Dialog.show 是 jank/ANR 面。整体投递到
        // 主线程队列，等分发结束后再构建（仍在主线程，View 操作安全）
        android.os.Handler(android.os.Looper.getMainLooper()).post { showNow(ctx) }
    }

    private fun showNow(ctx: Context) {
        // ★ 性能专项（2026-09-23）：菜单打开实测 7 帧/100% jank/90th=300ms（≈2s 冻结窗口）。
        //   分段计时定位主线程大头（几毫秒级，一次输出，不影响常态）。
        val t0 = android.os.SystemClock.uptimeMillis()
        var tMark = t0
        val timingSeq = ++menuOpenSeq
        // ★ 计时只在 diag 打开时输出（避免常态日志开销；排障时 `diag_debug=true` 即可）
        val timing = Logger.diag
        fun mark(label: String) {
            if (!timing) return
            val t = android.os.SystemClock.uptimeMillis()
            Logger.d("menuTiming#$timingSeq $label=${t - tMark}ms total=${t - t0}ms")
            tMark = t
        }
        try {
        // ★ 防双开（抖鸡对齐）：活动级 + decor 兜底双路径可能同时命中
        if (currentOverlay != null) { Logger.d("menu already showing, skip"); return }
        Logger.d("MainMenuDialog.show called")
        // ★ 异步刷新（流畅度）：广播链路已即时同步内存缓存，菜单先显示缓存值；
        // 文件兜底拉取后台化（原同步 FUSE 读 5-50ms 是点开菜单的掉帧源）
        Prefs.reloadAsync()
        val items = listOf(
            Item("", "刷新内容", hasSub = false) { ContentFilterHook.refreshContent() },
            Item("", "下载") { showDownload(ctx) },
            Item("", "沉浸式页面") { showImmersive(ctx) },
            Item("", "手势功能") { showGesture(ctx) },
            Item("", "播放控制") { showPlayback(ctx) },
            Item("", "内容过滤") { showFilter(ctx) },
            // ★ 拦截审计（功能 4/5，2026-09）：与 app 菜单同步
            Item("", "拦截记录") { showAuditRecords(ctx) },
            Item("", "拦截统计") { showAuditStats(ctx) },
            // ★★★ 模块状态（2026-09-26 用户要求）—— 集中查看各功能可用性
            Item("", "模块状态") { showModuleStatus(ctx) },
            Item("", "性能优化") { showPerf(ctx) },
            Item("", "快手净化") { showPurify(ctx) },
        )
        mark("buildItems")
        showSheet(ctx, "ManJiao", items, null)
        mark("showSheetTotal")
        } finally { }
    }

    // ==================== 模块状态（2026-09-26 用户要求）====================

    /**
     * 模块状态 → 功能状态
     *
     * ## 用户需求
     *
     * > 「模块菜单增加一项"模块状态"，子项："功能状态"
     * >   点开显示各个功能是不是能正常使用，有没有钩子没钩上的和解析失败的」
     *
     * ## 展示内容
     *
     * 分三块：
     *  1. **钩子状态** —— 每个 hook 是否安装成功（含失败原因）
     *  2. **解析状态** —— 关键类/方法是否找到（如 `sni.o` / `PresenterV2`）
     *  3. **功能开关** —— 各过滤开关的当前值 + 是否在本次运行中命中过
     */
    private fun showModuleStatus(ctx: Context) {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(dirRow(ctx, "功能状态", "各项是否正常挂载 / 是否工作过"))
        container.addView(divider(ctx))

        // ① 钩子状态
        container.addView(sectTitle(ctx, "钩子状态"))
        val hooks = try { ContentFilterHook.hookStatus() } catch (_: Throwable) { emptyList<Triple<String, Boolean, String>>() }
        if (hooks.isEmpty()) {
            container.addView(noteRow(ctx, "暂无钩子记录", "模块可能未注入快手进程"))
        } else {
            for ((name, ok, detail) in hooks) {
                container.addView(statusRow(ctx, name, ok, detail))
            }
        }
        container.addView(divider(ctx))

        // ② 解析状态（关键类是否找到）
        container.addView(sectTitle(ctx, "解析状态"))
        val parses = try { ContentFilterHook.parseStatus() } catch (_: Throwable) { emptyList<Triple<String, Boolean, String>>() }
        if (parses.isEmpty()) {
            container.addView(noteRow(ctx, "暂无解析记录", "等待首个内容通过"))
        } else {
            for ((name, ok, detail) in parses) {
                container.addView(statusRow(ctx, name, ok, detail))
            }
        }
        container.addView(divider(ctx))

        // ③ 功能开关 + 本次运行命中情况
        container.addView(sectTitle(ctx, "功能开关（本次运行命中数）"))
        val switches = try { ContentFilterHook.switchStatus() } catch (_: Throwable) { emptyList<Triple<String, Boolean, String>>() }
        for ((name, on, detail) in switches) {
            container.addView(statusRow(ctx, name, on, detail))
        }
        container.addView(divider(ctx))

        // ③.5 ★ 解析缓存（2026-09-26 用户要求：从 App 设置迁移到此处）
        //
        // 原在 `SettingsActivity` 的「关于」区：
        // ```
        // Triple("解析缓存", KsCache.statusText(), false)
        // ```
        // 用户要求移到模块菜单「模块状态 → 功能状态」（模块运行时才能看到真实状态）。
        container.addView(sectTitle(ctx, "解析缓存"))
        try {
            val cacheText = io.github.angbang852.manjiao.adapt.KsCache.statusText()
            // 判定：能读到条目数 = 正常；"未载入" 或 "?" = 异常
            val ok = cacheText != "未载入" && cacheText != "?"
            val detail = when {
                cacheText == "未载入" -> "尚未载入（首次解析后生成）"
                cacheText == "?" -> "读取失败"
                else -> "正常"
            }
            container.addView(statusRow(ctx, "结构缓存", ok, "$cacheText · $detail"))
            container.addView(noteRow(
                ctx,
                "缓存说明",
                "首次解析后固定复用；快手版本或模块版本变化时自动失效"
            ))
        } catch (t: Throwable) {
            container.addView(statusRow(ctx, "结构缓存", false, "异常: ${t.javaClass.simpleName}"))
        }
        container.addView(divider(ctx))

        // ==================== ⑥ 运行真值（实时） ====================
        //
        // 2026-09-29 用户要求：
        //  > 「模块状态里是不是要更新下，另外显示的状态是不是能真实的显示状态，还有在美化一下」
        //
        // 原状态页只有「钩子状态 / 解析状态 / 功能开关」三块，全是**静态注册标记**
        // （钩子装上没有、类找到没有）：既没有本轮新增的能力（首页自动预热、
        // 池存档恢复、两条注入路），也**不反映运行真值**（池里到底有几条）。
        // 这一块直读运行时计数器 —— 每次打开弹窗重新取值，看到的就是此刻真相。
        val ST = io.github.angbang852.manjiao.hook.CfhState
        val PR = io.github.angbang852.manjiao.data.Prefs
        container.addView(sectTitle(ctx, "运行真值（实时）"))
        try {
            val poolNow = synchronized(ST.cleanPool) { ST.cleanPool.size }
            val blackNow = try { ST.cleanPoolIds.size } catch (_: Throwable) { 0 }
            val servedNow = try { ST.servedPids.size } catch (_: Throwable) { 0 }
            val restoredNow = try { ST.restoredPids.size } catch (_: Throwable) { 0 }
            val skipNow = try { ST.renderFillSkip } catch (_: Throwable) { 0 }
            container.addView(
                statusRow(
                    ctx, "精选内容池", poolNow > 0,
                    "$poolNow 条 · " + if (poolNow > 0) "可供精选页取用"
                    else "空池 ⇒ 精选页空响应时会自动预热首页"
                )
            )
            // ★ v13.93 标签改名：原叫「已拉黑（永久）」会让人误以为记的是
            //   **被白名单拦下的脏内容** —— 不是。被拦的脏内容在网络层就删了，
            //   进不了这张表。这张表记的是「**进过干净池的 id**」，
            //   作用是永久去重（同一条绝不再上屏），所以改叫「永久去重（已用过）」。
            container.addView(
                statusRow(
                    ctx, "永久去重（已用过）", true,
                    "$blackNow 条 · 进过池就永久记住，同一条绝不再上屏（跨重启/崩溃都生效）"
                )
            )
            container.addView(statusRow(ctx, "本次已投放", true, "$servedNow 条 · 交给精选页的条数"))
            container.addView(
                statusRow(
                    ctx, "存档恢复", restoredNow > 0,
                    "$restoredNow 条 · 跨会话恢复进池的（0 表示本轮没用上存档）"
                )
            )
            container.addView(
                statusRow(
                    ctx, "补位跳过", true,
                    "$skipNow 拍 · 被 1.2 秒窗口挡下的重复注入（越大越防重）"
                )
            )
            val warmTxt = try {
                io.github.angbang852.manjiao.hook.CfhHomeWarm.statsText()
            } catch (_: Throwable) { "不可用" }
            container.addView(statusRow(ctx, "首页自动预热", true, warmTxt))
        } catch (t: Throwable) {
            container.addView(statusRow(ctx, "运行真值", false, "异常: ${t.javaClass.simpleName}"))
        }
        container.addView(divider(ctx))

        // ==================== ⑦ 「首页内容池接精选页」开关实况 ====================
        //
        // 用户要求「显示的状态能真实反映」。这个开关现在控制**四条路**：
        //   旧链路（首页内容登记 + 池空续拉/干净池补位 + CfhState 一道闸）
        //   + 本轮新接的三处（RESPFILL / RENDERFILL / 自动预热）
        // 直接把当前值显示出来，省得用户去设置里翻。
        container.addView(sectTitle(ctx, "首页内容池接精选页"))
        try {
            // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）
            val hr = PR.bool(PR.K_FLT_HOMEREFILL, false)
            container.addView(
                statusRow(
                    ctx, "接精选页总开关", hr,
                    if (hr) "开 · 首页池供精选页（旧链路 + 两条注入路 + 自动预热）"
                    else "关 · 首页池完全不接精选页，精选页只走白名单"
                )
            )
        } catch (t: Throwable) {
            container.addView(statusRow(ctx, "接精选页总开关", false, "读取异常: ${t.javaClass.simpleName}"))
        }
        container.addView(divider(ctx))

        // ==================== ⑧ 重置（2026-09-29 用户要求） ====================
        //
        // 「永久去重表」只增不减，池子被旧 id 堵死时唯一出路就是重置。
        // ⚠️ 重置 = **放弃「绝不重复」**（同一条可能再次上屏），所以：
        //   1. 不做单击生效 —— 用**两段式确认**（第一下只改按钮文字）。
        //      不依赖 AlertDialog：模块跑在快手进程里，ctx 未必是 Activity context，
        //      `AlertDialog.Builder(applicationContext)` 有崩的风险，两段式零依赖最稳。
        //   2. 说明写清楚到底清什么、代价是什么。
        container.addView(sectTitle(ctx, "重置"))
        container.addView(
            noteRow(
                ctx, "重置说明",
                "清空永久去重表 + 内容池 + 存档。清空后同一条**可能再次上屏**，" +
                    "只在池子被旧 id 堵死、拉不到新货时才用"
            )
        )
        val resetBtn = android.widget.Button(ctx).apply { text = "重置去重表 + 清空池" }
        var resetArmed = false
        resetBtn.setOnClickListener {
            if (!resetArmed) {
                resetArmed = true
                resetBtn.text = "再点一次确认重置（会清空去重表）"
                return@setOnClickListener
            }
            try {
                val before = try { ST.cleanPoolIds.size } catch (_: Throwable) { -1 }
                synchronized(ST.cleanPool) { ST.cleanPool.clear() }
                try { ST.cleanPoolIds.clear() } catch (_: Throwable) {}
                try { ST.servedPids.clear() } catch (_: Throwable) {}
                try { ST.restoredPids.clear() } catch (_: Throwable) {}
                // 落盘：markDirty 会触发防抖存盘（3 秒），写出的就是"空池 + 空去重表"
                try { io.github.angbang852.manjiao.hook.CfhPoolStore.markDirty() } catch (_: Throwable) {}
                android.widget.Toast.makeText(
                    ctx, "已重置（清空去重记录 $before 条）", android.widget.Toast.LENGTH_LONG
                ).show()
                showModuleStatus(ctx)
            } catch (t: Throwable) {
                android.widget.Toast.makeText(
                    ctx, "重置失败: ${t.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        container.addView(
            resetBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 44)
            ).apply { setMargins(dp(ctx, 20), dp(ctx, 6), dp(ctx, 20), dp(ctx, 6)) }
        )
        container.addView(divider(ctx))

        // ④ 刷新按钮
        container.addView(android.widget.Button(ctx).apply {
            text = "刷新"
            setOnClickListener { showModuleStatus(ctx) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 44)
        ).apply { setMargins(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10)) })

        showSheetScroll(ctx, "模块状态", { show(ctx) }, container)
    }

    /** 小标题（深色主题：用半透明白，与其他菜单项一致） */
    private fun sectTitle(ctx: Context, t: String): View =
        android.widget.TextView(ctx).apply {
            text = t
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
            setTextColor(0x80FFFFFF.toInt())
            textSize = 12f
        }

    /**
     * 状态行：[名称] + [✓/✗ 或 ●/○] + [详情]
     *
     * @param ok true=绿色✓，false=红色✗
     */
    private fun statusRow(ctx: Context, name: String, ok: Boolean, detail: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 20), dp(ctx, 7), dp(ctx, 20), dp(ctx, 7))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(android.widget.TextView(ctx).apply {
            text = if (ok) "✓" else "✗"
            setTextColor(if (ok) 0xFF34A853.toInt() else 0xFFEA4335.toInt())
            textSize = 15f
        }, LinearLayout.LayoutParams(dp(ctx, 24), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(android.widget.TextView(ctx).apply {
            text = name
            setTextColor(0xFFFFFFFF.toInt())   // 深色主题：白色
            textSize = 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (detail.isNotEmpty()) {
            row.addView(android.widget.TextView(ctx).apply {
                text = detail
                setTextColor(0x80FFFFFF.toInt())   // 深色主题：半透明白
                textSize = 11f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(ctx, 140), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return row
    }

    /** 两行说明行（标题 + 副标题），与 noteRow 同源 */
    private fun dirRow(ctx: Context, title: String, sub: String): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
        }
        col.addView(android.widget.TextView(ctx).apply {
            text = title; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
        })
        col.addView(android.widget.TextView(ctx).apply {
            text = sub; setTextColor(0xB3FFFFFF.toInt()); textSize = 12f
        })
        return col
    }

    // ==================== 拦截审计（功能 4/5/6，2026-09） ====================
    // 与 SettingsActivity 同源（都读 ContentFilterHook 的审计 API），
    // 差别仅在本菜单用紧凑行样式。

    private fun showAuditRecords(ctx: Context) {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(itemSwitchRow(ctx, "", "记录拦截证据", Prefs.bool(Prefs.K_AUDIT_ON, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_AUDIT_ON, nv)
            toast(ctx, if (nv) "已开始记录拦截证据" else "已停止记录")
        })
        container.addView(divider(ctx))

        val records = try { ContentFilterHook.auditRecords() } catch (_: Throwable) { emptyList<CfhState.HitRecord>() }
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

        if (!Prefs.bool(Prefs.K_AUDIT_ON, false)) {
            container.addView(noteRow(ctx, "记录未开启", "打开上面的开关后，被拦内容会出现在这里"))
        } else if (records.isEmpty()) {
            container.addView(noteRow(ctx, "暂无记录", "滑动信息流后，被拦内容会出现在这里"))
        } else {
            container.addView(noteRow(ctx, "共 ${records.size} 条", "保留最近 7 天"))
            for (r in records.take(50)) {
                container.addView(hitRow(ctx, r, fmt))
                container.addView(divider(ctx))
            }
            if (records.size > 50) container.addView(noteRow(ctx, "仅显示最近 50 条", "共 ${records.size} 条记录"))
        }
        container.addView(space(ctx, 6))
        // ★ 清空记录（2026-09）：模块菜单此前没有这个入口，只能去 app 清。
        // 清完立刻 mirrorNow()，否则 app 页还会显示 30 秒前的旧数据。
        if (records.isNotEmpty()) {
            container.addView(buttonRow(ctx, "清空记录") {
                try { ContentFilterHook.clearAuditRecords() } catch (_: Throwable) {}
                try { CfhState.mirrorNow() } catch (_: Throwable) {}
                toast(ctx, "已清空记录")
            })
            container.addView(space(ctx, 6))
        }
        showSheetScroll(ctx, "拦截记录", { show(ctx) }, container)
    }

    private fun hitRow(ctx: Context, r: CfhState.HitRecord, fmt: java.text.SimpleDateFormat): View {
        // ★「标为误拦」已移除（2026-09 用户判断该功能无意义）：
        //   ① 被拦视频当时已移出信息流，放行只在"再次刷到"时生效，界面无任何反馈
        //   ② 信息流几乎不会重复推同一条 ⇒ 白名单几乎不会命中
        //   ③ 误拦的正确解法是调规则（关掉误伤的规则），不是逐条救
        //   ④ 白名单只活在快手进程内存，重启即清空
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12)) }
        // ★ 拦截原因（功能 1）：可读中文标签优先显示，原始判据串放副行备查
        val reasonLine = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        reasonLine.addView(TextView(ctx).apply {
            text = "拦截原因：${AuditViz.ruleLabel(r.reason)}"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFD48A.toInt())
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
            background = roundRect(ctx, 6f, 0x1FFFD48A)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { outline(it) })
        reasonLine.addView(TextView(ctx).apply {
            text = fmt.format(java.util.Date(r.at)); textSize = 11f; setTextColor(0x99FFFFFF.toInt())
        })
        box.addView(reasonLine)
        box.addView(TextView(ctx).apply {
            text = r.reason; textSize = 10f; setTextColor(0x80A0C4FF.toInt()); maxLines = 1
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 4))
        })
        box.addView(TextView(ctx).apply {
            text = if (r.caption.isBlank()) "（无文案）" else r.caption
            textSize = 15f; maxLines = 2
        }.also { outline(it) })
        box.addView(TextView(ctx).apply {
            text = "@${if (r.user.isBlank()) "未知" else r.user}  ·  ${r.entCls}"
            textSize = 11f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, dp(ctx, 3), 0, dp(ctx, 6))
        }.also { outline(it) })
        return box
    }

    /**
     * 拦截统计页（功能 2/5）。
     *
     * ★ 修正（2026-09-22，用户反馈「模块和 app 菜单都没有切换按钮」）：
     * 原实现在 `stats.isEmpty()` 时直接跳过图表与切换器 —— 无数据时永远看不到入口。
     * 现改为：**时间窗（24h/48h/7d）与图表类型（7 种）两组切换器无条件渲染**，
     * 空数据只影响图表区内容。
     */
    private fun showAuditStats(ctx: Context) {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val winHours = statsWindowHours
        val stats = try { ContentFilterHook.statsInWindow(winHours) } catch (_: Throwable) { emptyList<Pair<String, Int>>() }
        val winTotal = stats.sumOf { it.second }
        val maxV = stats.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

        val winLabel = when (winHours) {
            24 -> "近 24 小时"; 48 -> "近 48 小时"; else -> "近 7 天"
        }
        val avg = if (winHours > 0) winTotal.toFloat() / winHours else 0f
        container.addView(noteRow(ctx, "$winTotal 条", "$winLabel · 平均 %.1f 条/小时".format(avg)))
        container.addView(divider(ctx))

        // ---- 切换器：两个下拉按钮（与设置页一致：一个按钮 + 弹出小菜单）----
        val winArr = intArrayOf(24, 48, 168)
        val winNames = arrayOf("24 小时", "48 小时", "7 天")
        val winIdx = winArr.indexOf(winHours).coerceAtLeast(0)
        val modeNames = arrayOf("趋势（面积图）", "条形图", "环形图", "柱状图", "雷达图", "热力图", "列表")
        val pickers = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        pickers.addView(AuditViz.dropdownButton(ctx, "统计范围", winNames[winIdx]) { anchor ->
            AuditViz.showDropdown(ctx, anchor, winNames, winIdx) { picked ->
                statsWindowHours = winArr[picked]
                showAuditStats(ctx)
            }
        })
        pickers.addView(space(ctx, 8))
        pickers.addView(AuditViz.dropdownButton(ctx, "图表类型", modeNames[statsChartMode.coerceIn(0, modeNames.size - 1)]) { anchor ->
            AuditViz.showDropdown(ctx, anchor, modeNames, statsChartMode) { picked ->
                statsChartMode = picked
                showAuditStats(ctx)
            }
        })
        container.addView(pickers)
        container.addView(divider(ctx))

        // ---- 图表区 ----
        // ★ 六种图表统一走 AuditViz.chart（2026-09 修复「切换无变化」）：
        // 此前除趋势图外全部落到 appendRuleBars，而它只把 mode 当作**标题文字**，
        // 六种类型画出来一模一样 ⇒ 用户切换图表看着「没反应」。
        // 现在真正的绘制实现在公共模块，两端共用。
        if (stats.isEmpty()) {
            container.addView(noteRow(ctx, "该时段暂无命中", "可切到更长时间范围，或开启「内容过滤」后滑动"))
        } else if (statsChartMode == 0) {
            appendTrendChart(ctx, container, winHours)
        } else {
            // compact=true：浮层盖在视频上，高度有限，图表比设置页矮一档、条目更少
            container.addView(AuditViz.chart(ctx, statsChartMode, stats, compact = true))
        }
        // 重置统计（清空分时数据与基线）—— 与 app 页同一语义；
        // 清完立刻重新镜像，避免 app 页读到旧数据。
        container.addView(buttonRow(ctx, "重置统计") {
            try { ContentFilterHook.resetStatsBaseline() } catch (_: Throwable) {}
            try { ContentFilterHook.clearHourly() } catch (_: Throwable) {}
            try { CfhState.mirrorNow() } catch (_: Throwable) {}
            toast(ctx, "统计已重置")
        })
        container.addView(space(ctx, 6))
        showSheetScroll(ctx, "拦截统计", { show(ctx) }, container)
    }

    /** 小节标题 */
    private fun noteRow(ctx: Context, title: String, sub: String): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12)) }
        box.addView(TextView(ctx).apply { text = title; textSize = 16f }.also { outline(it) })
        box.addView(TextView(ctx).apply { text = sub; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()) }.also { outline(it) })
        return box
    }

    // ★ 每次点下载都强制用「当前可见页」刷新（2026-09 修复下错视频）：旧逻辑只在
    // CurrentVideo 无效时捕获——下载过一次后恒有效，划到新视频也复用旧 URL
    private fun ensureCapture(v: io.github.angbang852.manjiao.data.VideoInfo): io.github.angbang852.manjiao.data.VideoInfo {
        val ph = try { ContentFilterHook.currentFeedPhoto() } catch (_: Throwable) { null }
        if (ph != null) {
            try { VideoDownloaderHook.extractFromPhoto(ph, ContentFilterHook.currentFeedFragment()) } catch (_: Throwable) {}
            return CurrentVideo.current
        }
        return v
    }

    private fun showDownload(ctx: Context) {
        val entries = try { ContentFilterHook.visibleEntries() } catch (_: Throwable) { emptyList<CfhCapture.VisEntry>() }
        val items = ArrayList<Item>()
        // ★ 分类落盘说明（2026-09 用户要求）：下载产物按类型进子目录，
        // 用户得知道去哪儿找 —— 否则下完在 Download 根目录翻不到会以为失败。
        val basePath = Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH)
        // ★ 性能修复（第二批 · 2026-09）：原先在主线程**同步**执行 ensureAll ——
        //   8 个目录 ×(exists + mkdirs + 3×chmod) ≈ 32 次文件系统调用，且走
        //   /sdcard 的 FUSE（每次 stat/chmod 都要过一次内核↔用户态往返，慢），
        //   点「下载」时这段全压在 UI 线程 ⇒ 菜单弹出可见卡顿。
        //
        //   为什么挪到后台**不改变任何行为**：
        //   · 紧接着的 StorageDirs.appRoot() 只做字符串拼接 + File 构造，不碰磁盘；
        //   · 真正落盘的 DownloadService 在写之前自己 ensure 目标目录
        //     （DownloadService.kt:154/165/173/184），不依赖这里先建好；
        //   · StorageDirs.ensure 是幂等的，重复调用无副作用；
        //   · 模块启动时 Module.kt:230 已 ensureAll 过一次，此处只是兜底刷新。
        //
        //   收益：菜单弹出不再等待 32 次 FUSE syscall。
        //   复用本文件下游 `MJ-ShareDL` 那段已有的「一次性后台线程」写法，
        //   不新建线程池。
        Thread {
            try { StorageDirs.ensureAll(basePath) } catch (_: Throwable) {}
        }.apply { name = "MJ-EnsureDirs"; isDaemon = true }.start()
        val root = StorageDirs.appRoot(basePath)
        items.add(Item("", "保存位置：${root.name}/ 内（视频/音频/图集分开放）", hasSub = false) {
            showStorageLayout(ctx)
        })
        // ★ 首选：分享链接路线（用户方案）——分享→复制链接后，链接即快手认定的
        // 「这条视频」，photoId 零歧义。解析走后台线程
        items.add(Item("", "分享链接的视频（先分享→复制链接）", hasSub = false) {
            toast(ctx, "正在解析分享链接…")
            Thread {
                val info = try { VideoDownloaderHook.shareVideoInfo(ctx) } catch (_: Throwable) { null }
                if (info == null || !info.valid()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        toast(ctx, "解析失败：请先在视频页分享→复制链接")
                    }
                    return@Thread
                }
                DownloadService.downloadVideo(ctx, info, Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH))
            }.apply { name = "MJ-ShareDL"; isDaemon = true }.start()
        })
        // ★ 队列入口（功能 7，2026-09）：有多任务时显示，可查看/取消排队项
        if (DownloadService.isBusy) {
            val snap = try { DownloadService.queueSnapshot() } catch (_: Throwable) { emptyList() }
            val running = snap.count { it.second == DownloadService.STATE_RUNNING }
            val queued = snap.count { it.second == DownloadService.STATE_QUEUED }
            items.add(Item("", "下载队列（进行 $running · 排队 $queued）") { showDownloadQueue(ctx) })
        }
        // 最近看过的条目：图集直接下图片；视频进二级菜单选 视频/音频
        // ★ 元信息展示（功能 3，2026-09）：VideoInfo 里已采集的字段（时长/播放量/
        // 清晰度/类型）此前只用于文件名，UI 上完全没露脸。这里在条目副标题补齐。
        for (e in entries) {
            val isImg = try { VideoDownloaderHook.isImagePhoto(e.photo) } catch (_: Throwable) { false }
            // 轻量元信息：不触发完整 extract（那会做深扫）——只读已捕获的当前视频对象
            val meta = describeEntry(e)
            val label = (if (e.caption.isNotBlank()) e.caption.take(14) else "无文案") + " · " + e.user.take(8)
            val fullLabel = if (meta.isBlank()) label else "$label   [$meta]"
            if (isImg) {
                items.add(Item("", "图集：$fullLabel", hasSub = false) {
                    val info = try { VideoDownloaderHook.extractToInfo(e.photo, e.frag, false) } catch (_: Throwable) { null }
                    if (info == null || info.imageUrls.isEmpty()) { toast(ctx, "该图集未捕获到图片"); return@Item }
                    DownloadService.downloadImages(ctx, info, Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH))
                })
            } else {
                items.add(Item("", fullLabel, hasSub = false) {
                    showDownloadDetail(ctx, e, label)
                })
            }
        }
        if (items.size == 1) { toast(ctx, "未捕获到视频，请先播放视频"); return }
        showSheet(ctx, "下载", items, { show(ctx) })
    }

    /**
     * 保存位置说明页（2026-09）：把分类目录结构摊开给用户看。
     *
     * 用独立页面而不是 toast —— 目录结构是多行内容，toast 一闪而过看不清，
     * 而且用户想对照着去文件管理器找。
     */
    private fun showStorageLayout(ctx: Context) {
        val basePath = Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH)
        val root = StorageDirs.appRoot(basePath)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        box.addView(noteRow(ctx, root.absolutePath, "模块所有产物都收在这个文件夹里"))
        box.addView(divider(ctx))

        val rows = listOf(
            StorageDirs.SUB_VIDEO to "无水印视频（mp4）",
            StorageDirs.SUB_AUDIO to "音频提取（mp3/m4a）",
            StorageDirs.SUB_IMAGE to "图集，每条作品一个子文件夹",
            StorageDirs.SUB_COVER to "封面图",
            StorageDirs.SUB_OTHER to "未分类兜底",
            StorageDirs.SUB_SYS to "模块内部文件（建议勿动）"
        )
        // ★ 性能修复（第二批 · 2026-09）：原实现对 6 个子目录逐个 File.exists()
        //   ＝ 6 次 stat syscall（走 /sdcard FUSE）。改为**只列一次目录内容**
        //   （1 次 getdents），用名字是否出现来判断存在性。
        //
        //   为什么判定结果**完全一致**：
        //   · list() 返回目录下**所有**条目名（文件与子目录都算），而 File(root,name)
        //     .exists() 也是「文件或目录存在即为 true」⇒ `name in listed` 等价；
        //   · 唯一边界：list() 可能返回 **null**（root 不存在 / 不是目录 / 无读权限）。
        //     此时**退回逐个 exists()**（与改前逐字一致），绝不把「null」当成「空目录」
        //     —— 两者语义不同，混用会让无权限时错判成「子目录都不存在」。
        val listed: Array<String>? = try { root.list() } catch (_: Throwable) { null }
        for ((name, desc) in rows) {
            val f = File(root, name)
            val mark = if (listed != null) {
                if (listed.contains(name)) "✓" else "·"
            } else {
                if (f.exists()) "✓" else "·"
            }
            box.addView(noteRow(ctx, "$mark $name/", desc))
            box.addView(divider(ctx))
        }

        // 旧版本下载物若散落在 Download 根目录，提示用户可自行归类；
        // 模块不做自动搬运 —— 用户可能已有自己的整理习惯，擅自动文件更招骂
        box.addView(noteRow(
            ctx,
            "关于以前下载的文件",
            "早期版本直接放在 Download 根目录，本模块不会自动搬动；" +
                "如需归拢可自行移入 ${root.name}/${StorageDirs.SUB_VIDEO}/"
        ))
        box.addView(space(ctx, 6))
        showSheetScroll(ctx, "保存位置", { showDownload(ctx) }, box)
    }

    /**
     * 条目的元信息（功能 3，2026-09）。
     *
     * ★ 修正（2026-09）：原实现只在「条目文案 == 当前视频文案前 14 字」时才显示，
     * 而 `visRing` 里存的是**历史浏览记录**（最多 6 条，可能都已在屏外），
     * 与 `CurrentVideo.current` 极少匹配 ⇒ 元信息几乎永远是空的，
     * 用户看到的就是「没加」。现改为直接读该条目**自己的** QPhoto，
     * 从它身上取时长/播放量等字段（不依赖 CurrentVideo 的时序）。
     *
     * 仍不触发 `extractToInfo` 深扫（那会做对象图 BFS，对每条都跑会拖慢菜单弹出）——
     * 这里只做几次直接字段读，取不到就省略该项。
     */
    private fun describeEntry(e: CfhCapture.VisEntry): String {
        return try {
            val parts = ArrayList<String>(5)
            val ent = try {
                io.github.angbang852.manjiao.util.Reflect.readAny(e.photo, "mEntity")
            } catch (_: Throwable) { null }
            val pm = try {
                io.github.angbang852.manjiao.util.Reflect.readAny(ent ?: e.photo, "mPhotoMeta")
            } catch (_: Throwable) { null }

            // 时长（ms）
            val dur = try {
                io.github.angbang852.manjiao.util.Reflect.readLong(pm ?: e.photo, "mDuration")
            } catch (_: Throwable) { 0L }
            if (dur > 0) {
                val s = dur / 1000
                parts.add(if (s >= 60) "${s / 60}:${"%02d".format(s % 60)}" else "${s}s")
            }
            // 播放量 / 点赞
            val view = try {
                io.github.angbang852.manjiao.util.Reflect.readLong(pm ?: e.photo, "mViewCount")
            } catch (_: Throwable) { 0L }
            if (view > 0) parts.add("播放 ${formatCount(view)}")
            val like = try {
                io.github.angbang852.manjiao.util.Reflect.readLong(pm ?: e.photo, "mLikeCount")
            } catch (_: Throwable) { 0L }
            if (like > 0) parts.add("赞 ${formatCount(like)}")
            // 清晰度 / 类型（字段缺失时静默跳过）
            val label = try {
                io.github.angbang852.manjiao.util.Reflect.readAny(pm ?: e.photo, "mQualityLabel") as? String
            } catch (_: Throwable) { null }
            label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            val type = try {
                io.github.angbang852.manjiao.util.Reflect.readAny(ent ?: e.photo, "mPhotoType") as? String
            } catch (_: Throwable) { null }
            type?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            parts.joinToString(" · ")
        } catch (_: Throwable) { "" }
    }

    /** 把 VideoInfo 里已采集的字段拼成一行可读元信息 */
    private fun buildMetaString(v: io.github.angbang852.manjiao.data.VideoInfo): String {
        val parts = ArrayList<String>(6)
        if (v.duration > 0) {
            val s = v.duration / 1000
            parts.add(if (s >= 60) "${s / 60}:${"%02d".format(s % 60)}" else "${s}s")
        }
        if (v.viewCount > 0) parts.add("播放 ${formatCount(v.viewCount)}")
        if (v.likeCount > 0) parts.add("赞 ${formatCount(v.likeCount)}")
        v.qualityLabel?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        v.photoType?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (v.repUrls.isNotEmpty()) parts.add("${v.repUrls.size} 个清晰度源")
        return parts.joinToString(" · ")
    }

    /** 大数字缩写：12345 → 1.2万，1234567 → 123.5万，过亿 → 亿 */
    private fun formatCount(n: Long): String = when {
        n >= 100_000_000L -> "%.1f亿".format(n / 100_000_000.0)
        n >= 10_000L -> "%.1f万".format(n / 10_000.0)
        else -> n.toString()
    }

    /**
     * 下载详情页（功能 3）：显示元信息 + 下载/音频选项。
     *
     * 元信息在这里读取是安全的 —— 用户已经点了这一条，多花一次反射可接受，
     * 而列表页（上面 describeEntry）必须保持轻量。
     */
    private fun showDownloadDetail(ctx: Context, e: CfhCapture.VisEntry, label: String) {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 元信息卡（可能为空：未捕获到 VideoInfo 时不显示）
        try {
            val info = VideoDownloaderHook.extractToInfo(e.photo, e.frag, false)
            run {
                val meta = buildMetaString(info)
                if (meta.isNotBlank()) {
                    container.addView(noteRow(ctx, "视频信息", meta))
                    container.addView(divider(ctx))
                }
                // 清晰度源明细（repUrls 已收集但从未展示）
                if (info.repUrls.isNotEmpty()) {
                    val sources = info.repUrls
                        .sortedByDescending { it.height * 10000 + it.bitrate }
                        .take(6)
                        .joinToString("  ") { r ->
                            val h = if (r.height > 0) "${r.height}p" else "?"
                            val br = if (r.bitrate > 0) " ${r.bitrate / 1000}kbps" else ""
                            "$h$br"
                        }
                    container.addView(noteRow(ctx, "可用清晰度（自动取最高）", sources))
                    container.addView(divider(ctx))
                }
            }
        } catch (_: Throwable) {}

        container.addView(ItemRowView(ctx, "视频下载") {
            val info = try { VideoDownloaderHook.extractToInfo(e.photo, e.frag, false) } catch (_: Throwable) { null }
            if (info == null || !info.valid()) { toast(ctx, "该条未捕获到链接"); return@ItemRowView }
            DownloadService.downloadVideo(ctx, info, Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH))
        })
        container.addView(divider(ctx))
        container.addView(ItemRowView(ctx, "音频提取") {
            val info = try { VideoDownloaderHook.extractToInfo(e.photo, e.frag, false) } catch (_: Throwable) { null }
            if (info == null || !info.valid()) { toast(ctx, "该条未捕获到链接"); return@ItemRowView }
            DownloadService.downloadAudio(ctx, info, Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH))
        })
        container.addView(space(ctx, 6))
        showSheetScroll(ctx, label, { showDownload(ctx) }, container)
    }

    /** 简单的可点击文本行（用于 showDownloadDetail 的自定义容器） */
    private fun ItemRowView(ctx: Context, title: String, action: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 16))
            isClickable = true; isFocusable = true
        }
        row.addView(TextView(ctx).apply {
            text = title; textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { outline(it) })
        row.addView(TextView(ctx).apply { text = "›"; textSize = 22f }.also { outline(it) })
        // 用 Item 的既有行样式（含涟漪/分隔）会更统一，但此处需内联以支持 lambda 标签返回
        row.setOnClickListener { action() }
        return row
    }

    /**
     * 下载队列页（功能 7，2026-09）：查看进行中/排队中的任务，可取消未开始的。
     *
     * 由于队列是串行的（一次只跑一个），这里的状态天然简单：
     * 至多 1 个 RUNNING，其余都是 QUEUED。
     */
    private fun showDownloadQueue(ctx: Context) {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val snap = try { DownloadService.queueSnapshot() } catch (_: Throwable) { emptyList() }
        val queued = snap.count { it.second == DownloadService.STATE_QUEUED }
        val running = snap.count { it.second == DownloadService.STATE_RUNNING }

        container.addView(noteRow(ctx, "进行中 $running · 排队 $queued", "下载按顺序执行，一次只下一个"))
        container.addView(divider(ctx))

        if (snap.isEmpty()) {
            container.addView(noteRow(ctx, "队列为空", "从下载菜单发起多条即可进入队列"))
        } else {
            for ((idx, e) in snap.withIndex()) {
                val (title, state) = e
                val stateText = when (state) {
                    DownloadService.STATE_RUNNING -> "下载中"
                    DownloadService.STATE_QUEUED -> "排队中"
                    DownloadService.STATE_DONE -> "已完成"
                    else -> "失败"
                }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12))
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(TextView(ctx).apply { text = title; textSize = 15f; maxLines = 1 }.also { outline(it) })
                col.addView(TextView(ctx).apply {
                    text = stateText; textSize = 12f
                    setTextColor(if (state == DownloadService.STATE_RUNNING) 0xFF7BE38C.toInt() else 0xB3FFFFFF.toInt())
                }.also { outline(it) })
                row.addView(col)
                container.addView(row)
                container.addView(divider(ctx))
            }
            if (queued > 0) {
                container.addView(space(ctx, 6))
                container.addView(ItemRowView(ctx, "清空排队中的 $queued 个任务") {
                    val n = DownloadService.clearQueued()
                    toast(ctx, "已取消 $n 个排队任务")
                    showDownloadQueue(ctx)
                })
            }
        }
        container.addView(space(ctx, 6))
        showSheetScroll(ctx, "下载队列", { showDownload(ctx) }, container)
    }

    private fun showGesture(ctx: Context) {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(itemSwitchRow(ctx, "", "禁止双击点赞", Prefs.bool(Prefs.K_GS_NO_DBL_LIKE, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_GS_NO_DBL_LIKE, nv)
            toast(ctx, if (nv) "已禁止双击点赞" else "已恢复双击点赞")
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "双击打开评论区", Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_GS_OPEN_COMMENT, nv)
            if (nv && Prefs.bool(Prefs.K_GS_OPEN_MENU, false)) {
                Prefs.setBoolSync(ctx, Prefs.K_GS_OPEN_MENU, false)
                toast(ctx, "已自动关闭「双击打开模块菜单」")
                showGesture(ctx)
            }
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "双击打开模块菜单", Prefs.bool(Prefs.K_GS_OPEN_MENU, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_GS_OPEN_MENU, nv)
            if (nv && Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false)) {
                Prefs.setBoolSync(ctx, Prefs.K_GS_OPEN_COMMENT, false)
                toast(ctx, "已自动关闭「双击打开评论区」")
                showGesture(ctx)
            }
        })
        showSheetScroll(ctx, "手势功能", { show(ctx) }, container)
    }

    // showGestureTaps(ctx, key, title) 已删除（2026-09）：三击功能废弃。
    // 该函数**从无任何调用点**（grep 证实），且它设置的 key
    // （gs_open_comment_taps / gs_open_menu_taps）在 GestureHook 中的 `== 3`
    // 分支已随三击逻辑一并移除 —— 保留会是"改了不生效"的死 UI。

    private fun showPlayback(ctx: Context) {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(itemSwitchRow(ctx, "", "停止循环播放", Prefs.bool(Prefs.K_PB_NO_LOOP, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_PB_NO_LOOP, nv)
            toast(ctx, if (nv) "已停止循环播放" else "已恢复循环播放")
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "后台暂停播放", Prefs.bool(Prefs.K_PB_BG_PAUSE, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_PB_BG_PAUSE, nv)
            toast(ctx, if (nv) "已开启后台暂停" else "已关闭后台暂停")
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "禁止自动进入直播间", Prefs.bool(Prefs.K_PB_NO_AUTO_LIVE, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_PB_NO_AUTO_LIVE, nv)
            toast(ctx, if (nv) "已禁止自动进入直播间" else "已允许自动进入直播间")
        })
        showSheetScroll(ctx, "播放控制", { show(ctx) }, container)
    }

    private fun showImmersive(ctx: Context) {
        val sw = itemSwitchRow(ctx, "", "一键沉浸", Prefs.bool(Prefs.K_IMM_ON, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_ON, nv)
            toast(ctx, if (nv) "一键沉浸已开启" else "一键沉浸已关闭")
        }
        showSheet(ctx, "沉浸式页面", listOf(Item("", "自定义隐藏") { showHideCustom(ctx) }), { show(ctx) }, sw)
    }


    private fun showHideCustom(ctx: Context) {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(itemSwitchRow(ctx, "", "顶栏", Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false), hasSub = true, subAction = { showTopBarItems(ctx) }) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_TOPBAR_ON, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "右侧按钮", Prefs.bool(Prefs.K_IMM_RIGHT_ON, false), hasSub = true, subAction = { showRightBtnItems(ctx) }) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_RIGHT_ON, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "底栏", Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_BOTTOM_BAR, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "昵称/文案", Prefs.bool(Prefs.K_IMM_NICKNAME, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_NICKNAME, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "合集", Prefs.bool(Prefs.K_IMM_COLLECTION, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_COLLECTION, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "金币红包", Prefs.bool(Prefs.K_IMM_GOLD, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_GOLD, nv)
        })
        showSheetScroll(ctx, "自定义隐藏", { showImmersive(ctx) }, container)
    }

    private val TOPBAR_ITEMS = arrayOf("左上角按钮", "王者送福利", "游戏", "玩游戏", "短剧", "同城", "关注", "发现", "精选", "直播", "搜索")
    private val RIGHT_ITEMS = arrayOf("关注", "喜欢", "评论", "收藏", "转发", "音乐封面")

    private fun showTopBarItems(ctx: Context) {
        val selected = Prefs.strSet(Prefs.K_IMM_TOPBAR).toMutableSet()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (s in TOPBAR_ITEMS) {
            container.addView(itemSwitchRow(ctx, "", s, s in selected) { nv ->
                if (nv) selected.add(s) else selected.remove(s)
                Prefs.setStrSetSync(ctx, Prefs.K_IMM_TOPBAR, selected)
            })
            container.addView(divider(ctx))
        }
        showSheetScroll(ctx, "顶栏隐藏项", { showHideCustom(ctx) }, container)
    }

    private fun showRightBtnItems(ctx: Context) {
        val selected = Prefs.strSet(Prefs.K_IMM_RIGHT_ITEMS).toMutableSet()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (s in RIGHT_ITEMS) {
            container.addView(itemSwitchRow(ctx, "", s, s in selected) { nv ->
                if (nv) selected.add(s) else selected.remove(s)
                Prefs.setStrSetSync(ctx, Prefs.K_IMM_RIGHT_ITEMS, selected)
            })
            container.addView(divider(ctx))
        }
        showSheetScroll(ctx, "右侧按钮隐藏项", { showHideCustom(ctx) }, container)
    }

    private fun showFilter(ctx: Context) {
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
                "说明：开启后池子见底时会自动去首页拉货 —— 有时会精选页跳到首页再跳回。"),
        )
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        var thInput: EditText? = null
        var kwInput: EditText? = null
        for (r in rows) {
            @Suppress("UNCHECKED_CAST")
            val key = r[1] as String
            val def = r[2] as Boolean
            container.addView(itemSwitchRow(ctx, "", r[0] as String, Prefs.bool(key, def)) { nv ->
                Prefs.setBoolSync(ctx, key, nv)
            })
            // ★ v13.94 副作用说明（仅带第 5 项的行渲染）
            if (r.size > 4) {
                val note = TextView(ctx).apply {
                    text = r[4] as String
                    textSize = 12f
                    setTextColor(0xB3FFFFFF.toInt())
                    setPadding(dp(ctx, 28), dp(ctx, 4), dp(ctx, 28), dp(ctx, 2))
                }
                outline(note)
                container.addView(note)
            }
            if (r[3] == true) {
                val isLike = key == Prefs.K_FLT_LIKE_ON
                val label = TextView(ctx).apply {
                    text = if (isLike) "点赞数阈值（低于此值过滤，0=不限制）" else "按字段过滤关键词（逗号分隔）"; textSize = 13f
                    setPadding(dp(ctx, 28), dp(ctx, 6), dp(ctx, 28), dp(ctx, 2))
                }
                outline(label)
                container.addView(label)
                val et = EditText(ctx).apply {
                    if (isLike) {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setText(Prefs.int(Prefs.K_FLT_LIKE_TH, 1000).toString())
                    } else {
                        setText(Prefs.str(Prefs.K_FLT_KEYWORDS, ""))
                    }
                    textSize = 15f
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                    background = roundRect(ctx, 8f, cInputBg(ctx))
                    setHintTextColor(-1426063361)
                }
                outline(et)
                container.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                et.setOnFocusChangeListener { _, hf ->
                    if (!hf) {
                        if (isLike) Prefs.setIntSync(ctx, Prefs.K_FLT_LIKE_TH, et.text.toString().toIntOrNull() ?: 0)
                        else Prefs.setStrSync(ctx, Prefs.K_FLT_KEYWORDS, et.text.toString().trim())
                    }
                }
                if (isLike) thInput = et else kwInput = et
                container.addView(space(ctx, 8))
            }
            container.addView(divider(ctx))
        }
        // ★ 不再自己套 ScrollView（2026-09 修复「菜单里都滑动不了」）：
        // 原来这里包一层 ScrollView 再交给 showSheetCustom，而 showSheetCustom 又会
        // 套一层外层 ScrollView ⇒ **ScrollView 套 ScrollView**。内层高度无上界，
        // 测量时高度按内容撑开、自身永远不滚动；外层又因为量到内层不高而**根本不套**
        // ⇒ 两边都不滚，页面就卡死不动。
        // 现在统一由 attachPanelAdaptive 在最外层做一次性滚动容器。
        showSheetCustom(ctx, "内容过滤", container, "", {}, { show(ctx) })
    }

    private fun showPerf(ctx: Context) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
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
            container.addView(purifySwitchRow(ctx, title, desc, Prefs.bool(key, def)) { nv ->
                Prefs.setBoolSync(ctx, key, nv)
            })
            container.addView(divider(ctx))
        }
        // ★ 同上：不自己套 ScrollView，避免与外层嵌套
        showSheetCustom(ctx, "性能优化", container, "", {}, { show(ctx) })
    }

    private fun showPurify(ctx: Context) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        val rows = arrayOf(
            Triple("拦截推送服务", Prefs.K_PURIFY_PUSH, "阻止 MatrixPushV3Service 启动，减少后台推送唤醒"),
            Triple("拦截日志上报", Prefs.K_PURIFY_LOG, "阻断 ConanLogContentProvider 数据上报，减少隐私采集"),
            Triple("拦截 WebView 沙盒", Prefs.K_PURIFY_WEBVIEW, "阻止 SandboxedProcessService0 启动，减少 WebView 子进程开销"),
        )
        for ((title, key, desc) in rows) {
            // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）
            container.addView(purifySwitchRow(ctx, title, desc, Prefs.bool(key, false)) { nv ->
                Prefs.setBoolSync(ctx, key, nv)
            })
            container.addView(divider(ctx))
        }
        // ★ 同上：不自己套 ScrollView，避免与外层嵌套
        showSheetCustom(ctx, "快手净化", container, "", {}, { show(ctx) })
    }

    private fun purifySwitchRow(ctx: Context, title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 12), dp(ctx, 24), dp(ctx, 12))
            background = ripple(ctx)
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(ctx).apply {
            text = title; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { outline(it) })
        textCol.addView(TextView(ctx).apply {
            text = desc; textSize = 12f; setPadding(0, dp(ctx, 3), 0, 0)
            setTextColor(0xB3FFFFFF.toInt())
        }.also { outline(it) })
        row.addView(textCol)
        val (sw, toggle) = createIosSwitch(ctx, checked) { nv -> onToggle(nv) }
        sw.isClickable = true; sw.isFocusable = true
        sw.setOnClickListener { toggle() }
        row.addView(sw, LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 32)))
        row.setOnClickListener { toggle() }
        return row
    }


    private fun unwrapActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c != null) {
            if (c is Activity) return c
            c = if (c is ContextWrapper) c.baseContext else break
        }
        return null
    }

    private var currentDialog: android.app.Dialog? = null

    /**
     * ★ 性能/泄漏修复（2026-09-30）：挂在宿主 `decorView` 上的 detach 监听器。
     *
     *   原实现每次 attachOverlay 都 `addOnAttachStateChangeListener(匿名对象)`，
     *   而 detachOverlay **从不摘除** ⇒ 每开一次菜单就往宿主 View 上累积一个匿名
     *   监听器（宿主 decorView 的 mOnAttachStateChangeListeners 是 CopyOnWriteArrayList），
     *   永不释放 —— 这是**真泄漏**，且宿主 Activity 存活的整个生命周期内累积。
     *
     *   修法：把监听器与它挂载的 View 都记成字段，在 detachOverlay 里摘除。
     */
    private var overlayAttachListener: View.OnAttachStateChangeListener? = null
    private var overlayAttachHost: View? = null

    /** 菜单打开序号（性能专项计时用；也为「这次点开到底弹没弹」提供可观测证据） */
    private var menuOpenSeq = 0

    private fun attachOverlay(ctx: Context, glass: GlassPanel) {
        // ★ 性能专项（2026-09-23）：attachOverlay 占菜单打开耗时 60%+（35–77ms）。
        //   细分计时定位大头（仅在 diag 打开时输出）。
        val tt0 = android.os.SystemClock.uptimeMillis()
        var tt = tt0
        val seq = menuOpenSeq
        fun sub(label: String) {
            if (!Logger.diag) return
            val n = android.os.SystemClock.uptimeMillis()
            Logger.d("menuTiming#$seq attach.$label=${n - tt}ms")
            tt = n
        }
        val activity = unwrapActivity(ctx) ?: run { Logger.d("attach: no activity"); return }
        if (activity.isDestroyed || activity.isFinishing) { Logger.d("attach: activity gone"); return }
        detachOverlay()
        sub("detach")
        // crossBlur 跨窗实时模糊（既定方案）：window 缩到面板大小 + FLAG_BLUR_BEHIND +
        // blurBehindRadius（SF 实时模糊 SurfaceView 视频，零采样天然同步）。
        // 需手机端 KernelSU root shell 执行 settings put global pms_settings_blur_enabled 1
        // 启用 crossBlur（One UI 默认禁用）；未启用时 fallback dimAmount 暗化。
        val dlg = android.app.Dialog(activity)
        sub("newDialog")
        // ★ 泄漏接线：菜单开着时宿主 Activity 被销毁（旋转/深色切换/回收），
        // Dialog 属独立 window 不会自动 dismiss——Activity decor 一 detach 就拆
        // 菜单，静态 currentDialog 不再钉住已销毁的 Activity 整棵视图树
        // ★ 性能/泄漏修复（2026-09-30）：监听器**必须记成字段**，否则 detachOverlay
        //   拿不到引用、永远摘不掉 ⇒ 每开一次菜单累积一个（见字段声明处的说明）。
        try {
            val host = activity.window?.decorView
            if (host != null) {
                val lis = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) { detachOverlay() }
                }
                host.addOnAttachStateChangeListener(lis)
                overlayAttachHost = host
                overlayAttachListener = lis
            }
        } catch (_: Throwable) {}
        sub("attachListener")
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setCanceledOnTouchOutside(true)
        dlg.setCancelable(true)
        dlg.setOnCancelListener { detachOverlay() }
        sub("dlgConfig")
        // ★ setContentView 的高度参数与后面 w.setLayout 必须一致：
        // 原实现这里固定 WRAP_CONTENT，随后 setLayout 限了高，两个约束打架，
        // 实际以 WRAP_CONTENT 为准 ⇒ 内容多高窗口就多高 ⇒ 顶满屏幕。
        val contentH = if (pendingPanelMaxH > 0) pendingPanelMaxH else ViewGroup.LayoutParams.WRAP_CONTENT
        dlg.setContentView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentH))
        sub("setContentView")
        val w = dlg.window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = ctx.resources.displayMetrics
        w.setWindowAnimations(0)
        val winW = dm.widthPixels - dp(ctx, 80)
        // ★ 高度上限（2026-09 用户要求「不能占满整个屏幕」）：
        // 原来给 window 设 WRAP_CONTENT，面板内容多高窗口就多高 ⇒ 顶满屏幕。
        // 现在由 pendingPanelMaxH 给出上限（attachPanelAdaptive 按内容决定），
        // 未设上限时才退回 WRAP_CONTENT。
        val winH = if (pendingPanelMaxH > 0) pendingPanelMaxH else ViewGroup.LayoutParams.WRAP_CONTENT
        w.setLayout(winW, winH)
        pendingPanelMaxH = 0
        w.setGravity(Gravity.CENTER)
        w.setDimAmount(0f)
        // ★ 让 window 可获取焦点，ScrollView 才能正常接收滑动（2026-09 修复「记录页滑不动」）：
        // 默认 Dialog window 若不可聚焦，部分 ROM 上子 View 的触摸序列会被吞掉。
        w.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        // 返回键 = 关闭菜单
        dlg.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) {
                detachOverlay(); true
            } else false
        }
        sub("windowConfig")
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                val crossOk = activity.windowManager.isCrossWindowBlurEnabled
                if (crossOk) {
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    val attrs = w.attributes
                    attrs.blurBehindRadius = dp(activity, 12)
                    w.attributes = attrs
                } else {
                    w.setDimAmount(0.5f)
                }
                Logger.always("attach: sfBlur=12dp crossBlurOk=$crossOk sdk=${android.os.Build.VERSION.SDK_INT} winW=${winW}")
            } catch (t: Throwable) { Logger.d("attach blur fail: ${t.message}") }
        } else {
            w.setDimAmount(0.5f)
        }
        sub("blurSetup")
        currentOverlay = glass
        currentDialog = dlg
        try {
            dlg.show()
        } catch (t: Throwable) {
            Logger.d("attach show fail: ${t.message}")
            currentDialog = null
        }
        sub("dlg.show")
        if (Logger.diag) Logger.d("menuTiming#$seq attach.total=${android.os.SystemClock.uptimeMillis() - tt0}ms")
    }

    private fun detachOverlay() {
        // ★ 性能/泄漏修复（2026-09-30）：先摘除挂在宿主 decorView 上的 detach 监听器。
        //   **摘除点必须在清空字段之前** —— 否则拿不到引用就永远摘不掉
        //   （原实现从不摘除 ⇒ 每开一次菜单累积一个，真泄漏）。
        //   在 onViewDetachedFromWindow 回调内部调用本函数时摘除同样是安全的：
        //   View 内部用 CopyOnWriteArrayList 存放监听器，遍历期间摘除不会出问题。
        try {
            val host = overlayAttachHost
            val lis = overlayAttachListener
            if (host != null && lis != null) host.removeOnAttachStateChangeListener(lis)
        } catch (_: Throwable) {}
        overlayAttachListener = null
        overlayAttachHost = null
        // ★ 统一 flush：过滤输入只在失焦时保存，而 ✕/返回键/点外部关闭都不抢
        // 焦点（✕ 未设 focusableInTouchMode）——拆窗前强制 clearFocus 触发保存
        // 回调，避免「输完直接关」静默丢输入
        try { currentDialog?.currentFocus?.clearFocus() } catch (_: Throwable) {}
        currentDialog?.let { d ->
            try { d.dismiss() } catch (_: Throwable) {}
        }
        currentDialog = null
        currentOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentOverlay = null
    }

    private fun buildGlass(ctx: Context): GlassPanel {
        // crossBlur 跨窗实时模糊配方：SF blur(12dp) 实时透视频（attachOverlay 设置）
        // + 本类自绘表面层（wash/gloss/rim/投影照抄 LiquidGlassPanel/HostLayout）
        // ★ 浮层恒深（2026-09 用户定稿方案 A）：wash 固定深灰、不再跟随系统浅色变白。
        //   理由：菜单浮在**快手视频画面**上，底下明暗不可控 —— 浅色模式下淡白玻璃
        //   （0x66EFF1F7，仅 40% 不透明度）透出亮画面时，白色文字对比度不足
        //   （实测截图：白衣服/面包区域白字几乎融入背景）。深玻璃+白字是
        //   视频播放器控制条/菜单的通用做法，任何画面下都可读。
        //   深色模式下观感与之前完全一致（0xB3≈70% vs 原 0x66≈40%，更实一点更稳）。
        return GlassPanel(ctx, dp(ctx, 36).toFloat(), washOverride = 0xB32F3036.toInt())
    }

    private fun showSheet(ctx: Context, title: String, items: List<Item>, onBack: (() -> Unit)?, switchRow: View? = null) {
        val tA = android.os.SystemClock.uptimeMillis()
        val glass = buildGlass(ctx)
        // ★ 标题栏与分隔线**不随内容滚动**（2026-09 用户反馈「上面没设边界」）：
        // 原先 header 是 panel 的第一个子 View，一起塞进滚动容器 —— 往下滑时
        // header 连着它 18dp 的上边距被一起滚走，内容就顶到面板上沿，
        // 看起来「下面有边界、上面没有」。现在 header 固定在滚动区之外。
        val fixedTop = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fixedTop.addView(header(ctx, title, onBack != null, { onBack?.invoke() }, { detachOverlay() }))
        fixedTop.addView(divider(ctx))
        if (switchRow != null) {
            fixedTop.addView(switchRow)
            fixedTop.addView(divider(ctx))
        }
        // 可滚动部分
        val scrollable = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for ((index, item) in items.withIndex()) {
            scrollable.addView(itemRow(ctx, item))
            if (index < items.size - 1) scrollable.addView(divider(ctx))
        }
        val tB = android.os.SystemClock.uptimeMillis()
        if (Logger.diag) Logger.d("menuTiming#$menuOpenSeq rows(buildGlass+header+${items.size}rows)=${tB - tA}ms")
        attachPanelAdaptive(ctx, glass, fixedTop, scrollable)
    }

    /**
     * 面板高度自适应 + 超高转滚动（三个 showSheet* 共用）。
     *
     * 用户诉求（2026-09）：「高度要限制一下，不能占满整个屏幕」「拦截记录页滑动不了」。
     *
     * ★ 高度上限为什么不能用 `heightPixels * 3/4`：
     * 3/4 屏在 3088px 的机器上是 2316px，仍几乎顶满；而且设备还有状态栏/导航栏/
     * 挖孔区，实际可视高度比 heightPixels 小。改为取 **heightPixels 的 62%**
     * 与「可视窗口高度 − 两侧留白」的较小值。
     *
     * ★ 记录页「滑不动」的真正原因（原实现的结构性错误）：
     * [GlassPanel] 四周有 14dp 投影 padding，子 View 装在**内框**里。
     * 原代码给 ScrollView 设了固定高 `maxH`，于是「内框高 = maxH」，
     * 面板整体高 = maxH + 2×14dp ⇒ 超出窗口高度 ⇒ 底部被裁掉，
     * 而被裁的正是滚动手柄能到达的区域，表现就是「怎么划都到不了下面」。
     * 正确做法：ScrollView 用 **MATCH_PARENT**（即等于内框），让上限由外层窗口决定，
     * 窗口高度上限已经扣掉了 padding，不会再溢出。
     */
    private fun attachPanelAdaptive(
        ctx: Context,
        glass: GlassPanel,
        fixedTop: LinearLayout,
        scrollable: LinearLayout
    ) {
        val tB0 = android.os.SystemClock.uptimeMillis()
        val dm = ctx.resources.displayMetrics
        // 高度上限：取**可视区域**（已扣掉状态栏/导航栏）的 60%，再兜物理屏 60%
        val visibleH = visibleHeight(ctx)
        val maxH = ((visibleH * 0.60f).coerceAtMost(dm.heightPixels * 0.60f)).toInt()
            .coerceAtLeast(dp(ctx, 240))
        // 内容区的**四周**边界留白（2026-09 用户要求「上下左右都要有边界」）：
        // 上下由 fixedTop / scrollable 的 padding 承担；
        // ★ 左右同样要留：面板本身是贴满窗口的，而各行自带 20dp 侧边距，
        // 一旦某行忘设或用了更小的值（如右侧 12dp），那一侧就会显得「挨着边」。
        // 在面板层统一加左右留白后，任何子行的内边距都叠加在这个基准之上。
        val topPad = dp(ctx, 16)
        val bottomPad = dp(ctx, 16)
        val sidePad = dp(ctx, 8)
        val maxContentH = (maxH - glass.shadowPad * 2).coerceAtLeast(dp(ctx, 200))

        // 可用宽度同步扣掉左右留白，否则内容会按「窗口宽」排版后被 padding 挤出去
        val availW = dm.widthPixels - dp(ctx, 88) - sidePad * 2
        val wSpec = View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.AT_MOST)

        // ★ 标题栏固定不滚动：给顶部留白，内容往下滑时标题栏始终在
        fixedTop.setPadding(sidePad, topPad, sidePad, 0)
        // ★ 实测 fixedTop 高度，不估算（2026-09 修复「下面又没边界」）：
        // 之前写死「16dp + 52dp + 1dp」估算，而标题栏实际高度随字号/内边距变化，
        // 估小的部分会让「标题栏 + 滚动区」总高超过上限 ⇒ 底部被窗口裁掉 ⇒
        // 用户看到「下面直接挨着边」。这里实测后按真实值分配滚动区高度。
        fixedTop.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val fixedTopH = fixedTop.measuredHeight.coerceAtLeast(1)
        val tM1 = android.os.SystemClock.uptimeMillis()

        // 滚动内容：底部留白 + 测量。
        // ★ 底部留白同时给两处：scrollable 的 padding（滚到底时露出留白）
        // 与 bodyAvailH 的预留（不依赖 padding 也算得对）。两处都留是刻意的 ——
        // 只靠 padding 时，若 ScrollView 高度算大一点点，padding 就被挤出可视区。
        scrollable.setPadding(sidePad, 0, sidePad, bottomPad)
        neutralizeNestedScroll(scrollable)
        scrollable.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        var bodyH = scrollable.measuredHeight
        if (bodyH <= 0) bodyH = maxContentH
        val tM2 = android.os.SystemClock.uptimeMillis()
        if (Logger.diag) Logger.d("menuTiming#$menuOpenSeq measure(fixedTop=${tM1 - tB0}ms scrollable=${tM2 - tM1}ms h=$bodyH)")

        // 滚动区可用高度 = 上限 − 标题栏（实测值）− 安全余量。
        // ★ 安全余量（2026-09）：固定标题栏后，底部留白曾被窗口裁掉导致
        // 「下面挨着边」。根因是「标题栏高 + 滚动区高」顶到窗口上限时，
        // 任何测量误差都会把底部留白挤出去。这里主动少给 8dp，
        // 宁可面板略矮，也保证底线留白一定可见。
        val slack = dp(ctx, 8)
        val bodyAvailH = (maxContentH - fixedTopH - slack).coerceAtLeast(dp(ctx, 120))
        val needScroll = bodyH > bodyAvailH

        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(fixedTop, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // ★ 面板总高**显式给定**，不用 WRAP_CONTENT（2026-09 修复底部挨边）：
        // WRAP_CONTENT 下总高 = 实测标题栏 + 内容，一旦内容略高就把底边顶出窗口、
        // 底部留白被裁。这里直接算 total，并夹到 maxContentH 以内，
        // 超出的部分必然由滚动区吸收 ⇒ 底边永远在窗口内。
        val totalH: Int
        if (needScroll) {
            totalH = (fixedTopH + bodyAvailH).coerceAtMost(maxContentH)
            val scroll = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                // 关掉边缘回弹：允许把内容拖出边界再弹回，观感上就是「内容滑出去了」
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = true
                isClickable = true
                isFocusable = true
                // 内容不得画出留白区
                clipToPadding = true
                addView(scrollable, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            panel.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (totalH - fixedTopH).coerceAtLeast(dp(ctx, 120))
            ))
        } else {
            // 内容不超高：面板高 = 标题栏 + 实际内容高（含底部留白 padding）
            totalH = (fixedTopH + bodyH).coerceAtMost(maxContentH)
            panel.addView(scrollable, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        // 面板本身也限高，作为最后一道保险：越界内容由滚动区处理，不会截断底边留白
        panel.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, totalH
        )
        glass.addView(panel)
        pendingPanelMaxH = maxH
        val tA1 = android.os.SystemClock.uptimeMillis()
        attachOverlay(ctx, glass)
        val tA2 = android.os.SystemClock.uptimeMillis()
        jellyEnter(panel)
        if (Logger.diag) Logger.d("menuTiming#$menuOpenSeq attachOverlay=${tA2 - tA1}ms totalSinceAdaptiveStart=${tA2 - tB0}ms")
    }

    /**
     * 让面板内**自带滚动**的子 View 交出滚动权（2026-09）。
     *
     * 嵌套滚动容器是这一类「滑不动」问题的标准成因：内层高度无上界 → 自己永远
     * 不滚；外层量到内层不高 → 不套滚动容器。两边都不动。
     *
     * 做法：把内层 ScrollView 的触摸拦截关掉，让它退化成普通 ViewGroup，
     * 由外层统一滚动。递归处理，深度 8 层足够。
     */
    private fun neutralizeNestedScroll(v: View, depth: Int = 0) {
        if (depth > 8) return
        try {
            if (v is ScrollView && depth > 0) {
                v.isNestedScrollingEnabled = false
                v.setOnTouchListener { _, _ -> false }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    neutralizeNestedScroll(v.getChildAt(i), depth + 1)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 当前**可视**高度（扣掉状态栏/导航栏）。
     *
     * `displayMetrics.heightPixels` 是物理屏（含系统栏），按它算的「3/4 屏」在
     * 手势导航机型上会顶到底。这里优先用 Activity 的 window visible frame，
     * 拿不到再退回 displayMetrics。
     */
    private fun visibleHeight(ctx: Context): Int {
        try {
            val act = unwrapActivity(ctx)
            if (act != null) {
                val r = android.graphics.Rect()
                act.window?.decorView?.getWindowVisibleDisplayFrame(r)
                if (r.height() > 0) return r.height()
            }
        } catch (_: Throwable) {}
        val dm = ctx.resources.displayMetrics
        return dm.heightPixels
    }

    /** 本次待挂载面板的高度上限（0 = 不限，按内容自适应）。attachOverlay 消费后清零 */
    private var pendingPanelMaxH = 0

    /** 浮层规则条最多显示多少行（超过去 app 看，避免把面板撑爆） */
    private const val MAX_RULE_ROWS = 10

    private fun showSheetScroll(ctx: Context, title: String, onBack: (() -> Unit)?, body: View) {
        val glass = buildGlass(ctx)
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // 标题栏固定不滚动，body 独立滚动
        val fixedTop = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fixedTop.addView(header(ctx, title, onBack != null, { onBack?.invoke() }, { detachOverlay() }))
        fixedTop.addView(divider(ctx))
        val scrollable = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scrollable.addView(body)
        attachPanelAdaptive(ctx, glass, fixedTop, scrollable)
    }

    private fun showSheetCustom(ctx: Context, title: String, body: View, positiveBtn: String, onPositive: () -> Unit, onBack: () -> Unit) {
        val glass = buildGlass(ctx)
        val fixedTop = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fixedTop.addView(header(ctx, title, true, { onBack() }, { detachOverlay() }))
        if (positiveBtn.isNotEmpty()) {
            fixedTop.addView(divider(ctx))
            fixedTop.addView(buttonRow(ctx, positiveBtn, onPositive))
        }
        val scrollable = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bodyWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        bodyWrap.addView(body)
        scrollable.addView(bodyWrap)
        attachPanelAdaptive(ctx, glass, fixedTop, scrollable)
    }

    private fun header(ctx: Context, title: String, hasBack: Boolean, onBack: () -> Unit, onClose: () -> Unit): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ★ 左右改对称（2026-09 用户反馈「左右边界一样要留」）：
            // 原为 (20, 18, 12, 14)，右侧只有 12dp —— 关闭按钮因此更贴近右边，
            // 视觉上「右边比左边窄」。统一为 20dp，与各内容行对齐。
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 14))
        }
        if (hasBack) {
            val back = android.view.View(ctx).apply {
                // ★ 返回箭头改为自绘（2026-09 用户反馈「返回按钮没在圆的正中间」）：
                //   原用字符 "‹"（U+2039），Gravity.CENTER 只能居中字形的**包围盒**，
                //   而该字形在多数字体里墨迹偏左偏上 —— 视觉上不在圆心。
                //   自绘 chevron 的顶点坐标按 40dp 圆的几何中心计算，绝对居中。
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
                background = RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF),
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x59000000) },
                    null
                )
            }
            // 用 Canvas 画：在 onDraw 里按当前尺寸取中心，不依赖字体度量
            val chevron = object : android.view.View(ctx) {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = dp(ctx, 2.2f)
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                override fun onDraw(c: android.graphics.Canvas) {
                    super.onDraw(c)
                    val w = width.toFloat(); val h = height.toFloat()
                    // 箭头横向宽度约 9dp、纵向高约 14dp，围绕几何中心 (w/2, h/2)
                    val halfW = dp(ctx, 4.5f)
                    val halfH = dp(ctx, 7f)
                    val cx = w / 2f; val cy = h / 2f
                    // ‹ 形：从右上 → 左中 → 右下
                    val path = android.graphics.Path().apply {
                        moveTo(cx + halfW, cy - halfH)
                        lineTo(cx - halfW, cy)
                        lineTo(cx + halfW, cy + halfH)
                    }
                    c.drawPath(path, paint)
                }
            }
            chevron.layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
            val holder = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
                addView(back)
                addView(chevron)
            }
            holder.isClickable = true; holder.isFocusable = true
            holder.setOnClickListener {
                val before = currentOverlay
                onBack()
                if (currentOverlay === before) detachOverlay()
            }
            bar.addView(holder)
            bar.addView(space(ctx, 0, 10))
        }
        if (title == "ManJiao") {
            val stroke = TextView(ctx).apply {
                text = title; textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.12f
                setTextColor(Color.WHITE)
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 7f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val fill = TextView(ctx).apply {
                text = title; textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.12f
                setTextColor(0xFFA0C4FF.toInt())
                post {
                    try {
                        val w = paint.measureText(text.toString())
                        paint.shader = LinearGradient(0f, 0f, w, 0f, 0xFFA0C4FF.toInt(), 0xFFC9A0FF.toInt(), Shader.TileMode.CLAMP)
                        invalidate()
                    } catch (_: Throwable) {}
                }
            }
            val wrap = FrameLayout(ctx)
            wrap.addView(stroke, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            wrap.addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bar.addView(wrap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            bar.addView(TextView(ctx).apply {
                text = title; setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 20f
                setTextColor(Color.WHITE)
                setShadowLayer(11f, 0f, 0f, Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        val close = TextView(ctx).apply {
            text = "✕"; textSize = 22f; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x59000000) },
                null
            )
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
        }
        outline(close)
        close.setOnClickListener { onClose() }
        bar.addView(close)
        return bar
    }

    private fun itemRow(ctx: Context, item: Item): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 10), dp(ctx, 24), dp(ctx, 10))
            isClickable = true; isFocusable = true
            background = ripple(ctx)
        }
        if (item.icon.isNotEmpty()) {
            row.addView(TextView(ctx).apply {
                text = item.icon; textSize = 20f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
            })
            row.addView(space(ctx, 0, 14))
        }
        row.addView(TextView(ctx).apply {
            text = item.title; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { outline(it) })
        if (item.hasSub) {
            row.addView(TextView(ctx).apply {
                text = "›"; textSize = 22f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24))
            }.also { outline(it) })
        }
        row.setOnClickListener {
            val before = currentOverlay
            item.action()
            if (currentOverlay === before) detachOverlay()
        }
        return row
    }

    private fun itemSwitchRow(ctx: Context, icon: String, title: String, checked: Boolean, hasSub: Boolean = false, subAction: (() -> Unit)? = null, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 10), dp(ctx, 24), dp(ctx, 10))
            background = ripple(ctx)
        }
        if (icon.isNotEmpty()) {
            row.addView(TextView(ctx).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
            })
            row.addView(space(ctx, 0, 14))
        }
        row.addView(TextView(ctx).apply {
            text = title; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { outline(it) })
        val (sw, toggle) = createIosSwitch(ctx, checked) { nv -> onToggle(nv) }
        sw.isClickable = true; sw.isFocusable = true
        sw.setOnClickListener { toggle() }
        row.addView(sw, LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 32)))
        val arrowW = dp(ctx, 40)
        row.addView(space(ctx, 0, 10))
        if (hasSub) {
            row.addView(TextView(ctx).apply {
                text = "›"; textSize = 22f; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(arrowW, dp(ctx, 44))
            }.also { outline(it) }.also { tv -> tv.setOnClickListener {
                val before = currentOverlay
                subAction?.invoke()
                if (currentOverlay === before) detachOverlay()
            } })
            row.setOnClickListener {
                val before = currentOverlay
                subAction?.invoke()
                if (currentOverlay === before) detachOverlay()
            }
        } else {
            row.addView(space(ctx, 0, arrowW))
            row.setOnClickListener { toggle() }
        }
        return row
    }

    private fun createIosSwitch(ctx: Context, checked: Boolean, onToggle: (Boolean) -> Unit): Pair<View, () -> Unit> {
        val trackW = dp(ctx, 56)
        val trackH = dp(ctx, 32)
        val knobSize = dp(ctx, 26)
        val pad = (trackH - knobSize) / 2
        val colorOn = 0xCC4CAF50.toInt()
        val colorOff = 0xCC757575.toInt()

        val container = FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(trackW, trackH) }
        val trackBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = trackH / 2f
            setColor(if (checked) colorOn else colorOff)
        }
        val track = View(ctx).apply { background = trackBg; elevation = dp(ctx, 1).toFloat() }
        container.addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val knob = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(ctx, 4).toFloat()
        }
        val knobLp = FrameLayout.LayoutParams(knobSize, knobSize, Gravity.CENTER_VERTICAL)
        knobLp.leftMargin = if (checked) trackW - knobSize - pad else pad
        container.addView(knob, knobLp)

        var isOn = checked
        val toggle: () -> Unit = {
            isOn = !isOn
            trackBg.setColor(if (isOn) colorOn else colorOff)
            track.background = trackBg
            val fromLeft = knobLp.leftMargin
            val targetLeft = if (isOn) trackW - knobSize - pad else pad
            val dx = (targetLeft - fromLeft).toFloat()
            knob.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction {
                knob.animate().translationX(dx).scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(OvershootInterpolator(2.4f)).withEndAction {
                        knobLp.leftMargin = targetLeft
                        knob.translationX = 0f
                        knob.layoutParams = knobLp
                    }.start()
            }.start()
            onToggle(isOn)
        }
        return Pair(container, toggle)
    }

    private fun buttonRow(ctx: Context, label: String, onAction: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 16))
        }
        val btn = TextView(ctx).apply {
            text = label; textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundRect(ctx, 12f, C_PRIMARY)
            setPadding(dp(ctx, 40), dp(ctx, 14), dp(ctx, 40), dp(ctx, 14))
            isClickable = true; isFocusable = true
        }
        outline(btn)
        btn.setOnClickListener {
            val before = currentOverlay
            onAction()
            if (currentOverlay === before) detachOverlay()
        }
        row.addView(btn)
        return row
    }

    private fun roundRect(ctx: Context, radius: Float, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius * ctx.resources.displayMetrics.density
        }
    }

    private fun ripple(ctx: Context): RippleDrawable {
        val content = ColorDrawable(Color.TRANSPARENT)
        val mask = roundRect(ctx, 0f, cDiv(ctx))
        return RippleDrawable(android.content.res.ColorStateList.valueOf(cRipple(ctx)), content, mask)
    }

    private fun divider(ctx: Context): View {
        return View(ctx).apply {
            setBackgroundColor(cDiv(ctx))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0)
            }
        }
    }

    private fun space(ctx: Context, h: Int, w: Int = ViewGroup.LayoutParams.MATCH_PARENT): View {
        return View(ctx).apply { layoutParams = LinearLayout.LayoutParams(w, dp(ctx, h)) }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    /** Float 重载：曲线绘制需要小数 dp（如 2.2f 线宽、4.5f 半宽），取整到像素即可 */
    private fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density)
    private fun toast(ctx: Context, msg: String) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }

    /**
     * 时间趋势（模块菜单版）：**平滑曲线面积图**（2026-09 用户定稿，替代小时柱状）。
     *
     * 用户诉求（2026-09）：「趋势图不要柱状的要改成平滑曲线的面积图」。
     * 绘制：
     * - Catmull-Rom → 三次贝塞尔：相邻数据点的切线由前后邻居决定，曲线平滑且**过点**
     *   （纯贝塞尔拟合不过点，峰值会被削平；Catmull-Rom 不会）
     * - 面积填充：同一条曲线闭合到底边，用垂直渐变（顶部实 → 底部透明）
     * - 数据点少（<2）时退化为直线；有峰值点标注
     *
     * 保留：宽度过放不下时横向滚动（与原柱状版一致）。
     */
    private fun appendTrendChart(ctx: Context, container: LinearLayout, hours: Int) {
        // ★ 数据源：模块菜单**跑在快手进程内**，直接读实时数据最准（零延迟、无跨进程开销）
        //   粒度 = 5 分钟桶（2026-09 用户定稿）：24h=288 点 / 48h=576 / 7d=2016
        val full = try { ContentFilterHook.hourlySeries(hours) } catch (_: Throwable) { emptyList<Pair<Long, Int>>() }
        if (full.isEmpty() || full.all { it.second <= 0 }) {
            container.addView(noteRow(ctx, "暂无分时数据", "开始使用后按 5 分钟累积"))
            return
        }
        // ★ 自适应缩放（2026-09 用户定稿）：
        //   「数据不多时放大拉平缓显示，数据量多了才缩小挤压显示」
        //   ① 先裁剪到**非零数据的时间范围**（首尾各留 2 桶边距）——
        //      只刷了 1 小时 → 只画那 1 小时附近的 12+4 桶并放大铺满全宽（拉平缓），
        //      而不是把 24h 的空白时间轴挤进来；
        //   ② 裁剪后 ≤ 点容量 → 全宽显示，点越少每点越宽、曲线越平缓；
        //   ③ 超容量 → 每点压到最小间距（挤压显示），再不够才横向滚动。
        // ★ 自适应缩放（2026-09 用户定稿）：
        //   「数据不多时放大拉平缓显示，数据量多了才缩小挤压显示」
        //   ① 先裁剪到**非零数据的时间范围**（首尾各留 2 桶边距）——
        //      只刷了 1 小时 → 只画那 1 小时附近的 12+4 桶并放大铺满全宽（拉平缓），
        //      而不是把 24h 的空白时间轴挤进来；
        //   ② 裁剪后 ≤ 点容量 → 全宽显示，点越少每点越宽、曲线越平缓；
        //   ③ 超容量 → 每点压到最小间距（挤压显示），再不够才横向滚动。
        //
        // ★★★ 修正（2026-09-26 用户报「左右时间显示不全被截断」）：
        //   原 `availW = 屏宽 - dp(88 + 40)` 是**写死的预留边距** ——
        //   而时间标签宽度**随格式/语言/字号变化**：
        //   ```
        //   hours <= 48 → "HH:mm"    ⇒ "23:45"      （窄）
        //   hours >  48 → "MM-dd"    ⇒ "09-26"      （窄）
        //   但中文环境下可能渲染成 "09-26 23:45"（宽）
        //   ```
        //   写死 88px 在标签变宽时**必然截断**。
        //   ⇒ 改为**按时间标签实测宽度动态留边**（`TrendChart.measureLabelWidth`）。
        var series = full
        var needScroll = false
        run {
            val first = full.indexOfFirst { it.second > 0 }.coerceAtLeast(0)
            val last = full.indexOfLast { it.second > 0 }
            val a = (first - 2).coerceAtLeast(0)
            val b = (last + 2).coerceAtMost(full.size - 1)
            series = full.subList(a, b + 1)
        }
        val maxFit = 240                      // 一屏可读的最多点数（再挤就看不清了）
        // ★ 动态留边：由 TrendChart 实测时间标签宽度后返回可绘制宽度
        val timeFmt = if (hours <= 48) "HH:mm" else "MM-dd"
        val availW = TrendChart.availWidthFor(
            ctx,
            labelSample = if (hours <= 48) "23:45" else "09-26",
        )
        val minSlot = dp(ctx, 5)              // 挤压模式下每点最小间距
        if (series.size > maxFit) {
            // 降采样：相邻 agg 个桶求和，时间取组内首桶（保持总量语义不变）
            val agg = (series.size + maxFit - 1) / maxFit
            val ds = ArrayList<Pair<Long, Int>>((series.size + agg - 1) / agg)
            var i = 0
            while (i < series.size) {
                val j = (i + agg).coerceAtMost(series.size)
                var s = 0
                for (k in i until j) s += series[k].second
                ds.add(series[i].first to s)
                i = j
            }
            series = ds
        }
        // ★ 网站式渲染（2026-09-23 对齐拼车站 New API / VChart）：声明式 spec +
        //   通用渲染器拆到 TrendChart.kt，monotone 保形曲线直接穿过真实数据点；
        //   移除 [1,2,1] 显示级预平滑 —— 图形与数字同源（数据诚实）。
        //   面积填充仍用深藏蓝实感（亮玻璃上 8% 透明会不可见，见 TrendChart 注释）。
        needScroll = TrendChart.mount(
            container, ctx,
            TrendChart.Spec(
                values = series,
                timeFormat = if (hours <= 48) "HH:mm" else "MM-dd",
            ),
            availWPx = availW,
            minSlotPx = minSlot,
        )
        val peakAt = series.indices.maxByOrNull { series[it].second } ?: 0
        val fmtFull = java.text.SimpleDateFormat(
            if (hours <= 48) "MM-dd HH:mm" else "MM-dd HH:mm", java.util.Locale.getDefault()
        )
        container.addView(noteRow(
            ctx,
            "峰值 ${series[peakAt].second} 条/小时",
            "出现在 ${fmtFull.format(java.util.Date(series[peakAt].first))}" +
                if (needScroll) "（可左右滑动看全部）" else ""
        ))
    }

    /**
     * 规则维度图表 —— **已删除**（2026-09）。
     *
     * 原先这里有一套「紧凑比例条」，把六种图表类型全画成同一个样子（mode 只用来
     * 选标题文字），导致用户切换图表「没变化」。现统一由 [AuditViz.chart] 提供
     * 六种真实实现（条形/环形/柱状/雷达/热力/列表），两端共用同一份代码，
     * 不会再出现「一边修了另一边没修」。
     */
}
