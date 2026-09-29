package io.github.angbang852.manjiao.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 拦截统计的可视化公共部件（2026-09）。
 *
 * 从 SettingsActivity / MainMenuDialog 两份重复实现中抽出 —— 两个菜单此前各自
 * 抄了一份图表代码，导致「设置页修了、模块菜单没修」的同步问题反复出现。
 * 现在色板、分类标签、下拉选择器都只有一份实现。
 */
internal object AuditViz {

    /**
     * 类别色板（用户要求「不同内容用不同颜色」）。
     *
     * 键为 `CfhDecide.hit()` 产生的 reason 前缀；取值为该类别在图表中的固定颜色。
     * 固定映射（而非按顺序取色）保证**同一类别在任何图表、任何时刻颜色一致** ——
     * 用户切换图表时看到的「广告」永远是同一个颜色，不会跳来跳去。
     */
    private val CAT_COLORS = linkedMapOf(
        "ads" to 0xFFFF8A80.toInt(),      // 广告 —— 珊瑚红
        "advideo" to 0xFFFF6E40.toInt(),  // 广告视频 —— 深珊瑚
        "ai" to 0xFFB388FF.toInt(),       // AI 内容 —— 紫
        "live" to 0xFFFFD54F.toInt(),     // 直播 —— 琥珀
        "image" to 0xFF80D8FF.toInt(),    // 图文 —— 天蓝
        "drama" to 0xFF7BE38C.toInt(),    // 影视 —— 绿
        "ec" to 0xFFFFAB91.toInt(),       // 电商 —— 浅橙
    )

    /** 兜底色（未知类别按顺序取用） */
    private val FALLBACK = intArrayOf(
        0xFFA0C4FF.toInt(), 0xFFC9A0FF.toInt(), 0xFF8AE0E0.toInt(),
        0xFFE0A0C4.toInt(), 0xFFC4C88A.toInt(), 0xFF9AE6B4.toInt()
    )

    /** 取类别色（输入 reason 全串或前缀均可） */
    fun colorOf(reason: String): Int {
        val head = if (reason.contains(':')) reason.substringBefore(':') else reason
        CAT_COLORS[head]?.let { return it }
        // 兜底：按前缀哈希稳定取色（同一未知类别每次同色）
        val idx = (head.hashCode().let { if (it < 0) -it else it }) % FALLBACK.size
        return FALLBACK[idx]
    }

    /** 半透明版本（用于填充/背景块） */
    fun alpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * 规则名 → 可读中文（类别 · 判据）。
     *
     * 映射表由脚本比对 `CfhDecide` 实际产生的全部 reason 变体后确定（35 种，
     * 含 `advideo:$src` 展开出的 adLabel/mTitle、quick 路径的短名 kwApp/longShort 等）。
     */
    fun ruleLabel(reason: String): String {
        val head = reason.substringBefore(':')
        val tail = reason.substringAfter(':', "").substringBefore(' ')
        val cat = when (head) {
            "ads" -> "广告"; "advideo" -> "广告视频"; "ai" -> "AI 内容"; "live" -> "直播"
            "image" -> "图文"; "drama" -> "影视"; "ec" -> "电商"; else -> head
        }
        val sub = when (tail) {
            "capText" -> "文案命中"; "capHard" -> "硬命中"; "userAd" -> "作者名"
            "userTask" -> "任务引流"; "capTask" -> "任务文案"; "entCls" -> "实体类型"
            "mAd" -> "广告标记"; "adNovel" -> "小说广告"; "adLabel" -> "广告标签字段"
            "mTitle" -> "标题字段"; "analyzeFlag" -> "AI 标记"; "tagBool" -> "AI 标签（布尔）"
            "tagStr" -> "AI 标签（字符串）"; "disclaimer" -> "AI 声明"
            "disclaimerPending" -> "声明待定"; "meta" -> "直播元数据"
            "startTime" -> "开播时间"; "shopLive" -> "直播带货"; "state" -> "直播状态"
            "userName" -> "作者名"; "mImageModel" -> "图集模型"; "cmType2" -> "类型码"
            "atlasMeta" -> "图集元数据"; "noVideoUrl" -> "无视频源"; "colTitle" -> "栏目标题"
            "episodeNo" -> "集数标记"; "capFull" -> "完结文案"; "capMovie" -> "影视文案"
            "shell" -> "空壳影视"; "tube" -> "剧场模型"; "kwAppNative" -> "原生短剧"
            "kwApp" -> "原生短剧"; "novel" -> "小说短剧"; "long2short" -> "长转短"
            "longShort" -> "长转短"; else -> tail
        }
        return if (sub.isBlank()) cat else "$cat · $sub"
    }

    /** 取类别名（不含判据），用于图例/分组 */
    fun categoryOf(reason: String): String = ruleLabel(reason).substringBefore("·").trim()

    // ==================== 下拉选择器（用户要求：一个按钮 → 点开选） ====================

    /**
     * 单个「按钮 + 弹出小菜单」选择器。
     *
     * 用户反馈：两排共 10 个 chip 在浮层里占位太多且不易点。
     * 改为一个按钮显示当前值，点击弹出列表选择。
     *
     * 用 [PopupWindow] 而非 Dialog：不需要新 window token、不受宿主
     * Activity 生命周期影响，且能精确定位在按钮下方。
     *
     * @param anchor  触发按钮
     * @param options 选项文本
     * @param current 当前选中下标
     * @param onPick  选中回调
     */
    fun showDropdown(
        ctx: Context,
        anchor: View,
        options: Array<String>,
        current: Int,
        onPick: (Int) -> Unit
    ) {
        try {
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(0xF01E2226.toInt())
                    setStroke(dp(1), 0x44A0C4FF)
                }
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }

            val pop = PopupWindow(
                content,
                (anchor.width.takeIf { it > dp(120) } ?: dp(160)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true       // focusable：点外部自动关闭
            )
            pop.isOutsideTouchable = true
            pop.elevation = dp(8).toFloat()

            for ((i, label) in options.withIndex()) {
                val selected = i == current
                val row = TextView(ctx).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    setTextColor(if (selected) 0xFFA0C4FF.toInt() else Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(if (selected) 0x33A0C4FF else Color.TRANSPARENT)
                    }
                    isClickable = true
                    setOnClickListener {
                        // ★ 顺序很重要（2026-09 修复「模块里图表切换无反应」）：
                        // 必须先 dismiss 弹窗、**再把重建动作投到消息队列尾部**。
                        // 直接在点击回调里重建界面，会在 PopupWindow 自己还在处理
                        // 这次触摸事件时就把承载它的 Dialog 拆掉（detachOverlay），
                        // 两边同帧争抢窗口 ⇒ 新界面建起来了却被随后的清理带走过，
                        // 表现就是「点了没反应」。
                        try { pop.dismiss() } catch (_: Throwable) {}
                        try {
                            anchor.post { try { onPick(i) } catch (_: Throwable) {} }
                        } catch (_: Throwable) {
                            // post 不可用时同步兜底
                            try { onPick(i) } catch (_: Throwable) {}
                        }
                    }
                }
                content.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }

            // 锚点下方弹出；宽度不足时左对齐
            pop.showAsDropDown(anchor, 0, dp(4))
        } catch (_: Throwable) {
            // 弹窗失败兜底：直接按顺序切到下一项，保证功能不失效
            onPick((current + 1) % options.size)
        }
    }

    /**
     * 下拉触发按钮：显示「标题：当前值 ▾」。
     *
     * @param ctx       上下文
     * @param title     字段名（如「统计范围」）
     * @param value     当前值文本
     * @param onClick   点击回调，参数为**按钮自身**（供 [showDropdown] 作锚点定位）
     */
    fun dropdownButton(ctx: Context, title: String, value: String, onClick: (View) -> Unit): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0x22FFFFFF)
                setStroke(dp(1), 0x44A0C4FF)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { v -> onClick(v) }
        }
        row.addView(TextView(ctx).apply {
            text = title; textSize = 13f; setTextColor(0x99FFFFFF.toInt())
        })
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = "$value  ▾"; textSize = 14f
            setTextColor(0xFFA0C4FF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        return row
    }

    // ==================== 图表构建器（两端共用） ====================
    //
    // ★ 为什么放到这里（2026-09 用户反馈「只有趋势和条形图正常，其他都显示条形图」）：
    // 模块菜单的 `appendRuleBars` 收了 mode 参数却**只用它选标题文字**，六种类型
    // 全都画成同一种紧凑条 —— 用户在浮层里切换图表「没变化」正是这个原因。
    // 而 SettingsActivity 里是六份**真实实现**。这里把它们搬进公共模块，两端共用，
    // 从此不存在「一边修了另一边没修」。

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** 图表统一高度（dp） */
    private const val CHART_H = 200
    private const val COLUMN_H = 220
    private const val RADAR_H = 240

    /**
     * 按图表类型构建图表视图。
     *
     * @param mode 1=条形 2=环形 3=柱状 4=雷达 5=热力 6=列表；其他值回退条形
     * @param compact 紧凑模式（模块浮层用）：行数更少、图表略矮。
     *   浮层盖在视频上、高度有限，不能跟设置页一样铺开。
     */
    fun chart(ctx: Context, mode: Int, stats: List<Pair<String, Int>>, compact: Boolean): View {
        if (stats.isEmpty()) return emptyNote(ctx, compact)
        val maxV = stats.maxOf { it.second }.coerceAtLeast(1)
        return when (mode) {
            2 -> donut(ctx, stats, compact)
            3 -> column(ctx, stats, maxV, compact)
            4 -> radar(ctx, stats, compact)
            5 -> heat(ctx, stats, maxV, compact)
            6 -> list(ctx, stats, maxV, compact)
            else -> bars(ctx, stats, maxV, compact)
        }
    }

    private fun maxRows(compact: Boolean) = if (compact) 8 else 12

    private fun title(ctx: Context, text: String, sub: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        box.addView(TextView(ctx).apply { this.text = text; textSize = 15f; setTextColor(Color.WHITE) })
        if (sub.isNotEmpty()) {
            box.addView(TextView(ctx).apply { this.text = sub; textSize = 12f; setTextColor(0x99FFFFFF.toInt()) })
        }
        return box
    }

    private fun emptyNote(ctx: Context, compact: Boolean): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12))
        }
        box.addView(TextView(ctx).apply { text = "该时段暂无命中"; textSize = 15f; setTextColor(Color.WHITE) })
        box.addView(TextView(ctx).apply {
            text = "可切换更长时间范围，或开启「内容过滤」后滑动信息流"
            textSize = 12f; setTextColor(0x99FFFFFF.toInt())
        })
        return box
    }

    /** 条形图：横向比例条 + 类别色点（浮层紧凑版也用它作为默认） */
    private fun bars(ctx: Context, stats: List<Pair<String, Int>>, maxV: Int, compact: Boolean): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sum = stats.sumOf { it.second }.coerceAtLeast(1)
        val shown = stats.take(maxRows(compact))
        box.addView(title(
            ctx, "条形图（按占比排序）",
            if (stats.size > shown.size) "显示前 ${shown.size} 项（共 ${stats.size}）" else "共 ${stats.size} 项"
        ))
        for ((name, v) in shown) {
            val catColor = colorOf(name)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 20), dp(ctx, 5), dp(ctx, 20), dp(ctx, 5))
            }
            val line1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            line1.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9))
                    .also { it.setMargins(0, 0, dp(ctx, 6), 0) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = dp(ctx, 5).toFloat(); setColor(catColor)
                }
            })
            line1.addView(TextView(ctx).apply {
                text = ruleLabel(name); textSize = 14f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            line1.addView(TextView(ctx).apply {
                text = "$v · ${"%.0f".format(v * 100f / sum)}%"
                textSize = 13f; setTextColor(catColor)
            })
            row.addView(line1)
            val barH = dp(ctx, 5)
            val track = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = dp(ctx, 3).toFloat(); setColor(0x22FFFFFF)
                }
            }
            val wOn = (v.toFloat() / maxV * 1000f).toInt().coerceIn(1, 1000)
            track.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = dp(ctx, 3).toFloat(); setColor(catColor)
                }
                layoutParams = LinearLayout.LayoutParams(0, barH, wOn.toFloat())
            })
            val wOff = 1000 - wOn
            if (wOff > 0) {
                track.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, barH, wOff.toFloat())
                })
            }
            row.addView(track)
            box.addView(row)
        }
        return box
    }

    /** 环形图：手绘圆环 + 中心总数 + 图例 */
    private fun donut(ctx: Context, stats: List<Pair<String, Int>>, compact: Boolean): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = stats.take(maxRows(compact))
        val sum = top.sumOf { it.second }.coerceAtLeast(1)
        val colors = Array(top.size) { i -> colorOf(top[i].first) }
        val donutH = dp(ctx, if (compact) 150 else CHART_H)
        val donut = object : View(ctx) {
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val arcRect = android.graphics.RectF()
            override fun onMeasure(w: Int, hs: Int) {
                setMeasuredDimension(resolveSize(dp(ctx, 200), w), donutH)
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val size = minOf(width, height).toFloat()
                val l = (width - size) / 2f
                val t = (height - size) / 2f
                val inset = size * 0.08f
                arcRect.set(l + inset, t + inset, l + size - inset, t + size - inset)
                p.style = android.graphics.Paint.Style.STROKE
                p.strokeWidth = size * 0.16f
                var start = -90f
                for ((i, e) in top.withIndex()) {
                    val sweep = e.second.toFloat() / sum * 360f
                    p.color = colors[i % colors.size]
                    canvas.drawArc(arcRect, start, sweep - 1.2f, false, p)
                    start += sweep
                }
                p.style = android.graphics.Paint.Style.FILL
                p.color = Color.WHITE
                p.textAlign = android.graphics.Paint.Align.CENTER
                p.textSize = size * 0.16f
                p.isFakeBoldText = true
                canvas.drawText("$sum", width / 2f, height / 2f + p.textSize * 0.34f, p)
                p.isFakeBoldText = false
                p.textSize = size * 0.075f
                p.color = 0xB3FFFFFF.toInt()
                canvas.drawText("次拦截", width / 2f, height / 2f + p.textSize * 2.6f, p)
            }
        }
        box.addView(donut, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, donutH))
        addLegend(ctx, box, top, colors, sum)
        return box
    }

    /** 图例（环形/柱状共用） */
    private fun addLegend(
        ctx: Context,
        box: LinearLayout,
        top: List<Pair<String, Int>>,
        colors: Array<Int>,
        sum: Int
    ) {
        for ((i, e) in top.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 4))
            }
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 12), dp(ctx, 12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(colors[i % colors.size])
                }
            })
            row.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 0)) })
            row.addView(TextView(ctx).apply {
                text = ruleLabel(e.first); textSize = 14f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = "${e.second}  (${"%.0f".format(e.second * 100f / sum)}%)"
                textSize = 13f; setTextColor(0xB3FFFFFF.toInt())
            })
            box.addView(row)
        }
    }

    /** 柱状图：竖向柱 + 柱顶数值 + 底部类别标签 */
    private fun column(ctx: Context, stats: List<Pair<String, Int>>, maxV: Int, compact: Boolean): View {
        val top = stats.take(if (compact) 8 else 10)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val chartH = dp(ctx, if (compact) 170 else COLUMN_H)
        val chart = object : View(ctx) {
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onMeasure(w: Int, hs: Int) {
                setMeasuredDimension(resolveSize(suggestedMinimumWidth.coerceAtLeast(dp(ctx, 200)), w), chartH)
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                if (top.isEmpty()) return
                val n = top.size
                val labelH = dp(ctx, 30).toFloat()
                val valueH = dp(ctx, 16).toFloat()
                val availH = height - labelH - valueH
                if (availH <= 0) return
                val slot = width.toFloat() / n
                val barW = (slot * 0.55f).coerceAtLeast(dp(ctx, 6).toFloat())
                labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                for ((i, e) in top.withIndex()) {
                    val cx = slot * i + slot / 2f
                    val h = (availH * (e.second.toFloat() / maxV)).coerceAtLeast(dp(ctx, 2).toFloat())
                    val topY = valueH + (availH - h)
                    p.color = colorOf(e.first)
                    canvas.drawRoundRect(
                        android.graphics.RectF(cx - barW / 2f, topY, cx + barW / 2f, valueH + availH),
                        dp(ctx, 3).toFloat(), dp(ctx, 3).toFloat(), p
                    )
                    p.color = Color.WHITE
                    p.textSize = dp(ctx, 10).toFloat()
                    canvas.drawText("${e.second}", cx, topY - dp(ctx, 3), p)
                    labelPaint.color = 0xB3FFFFFF.toInt()
                    labelPaint.textSize = dp(ctx, 9).toFloat()
                    val lab = ruleLabel(e.first).substringBefore("·").trim().take(4)
                    canvas.drawText(lab, cx, height - dp(ctx, 8).toFloat(), labelPaint)
                }
            }
        }
        box.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, chartH))
        return box
    }

    /** 雷达图：类别为维度的多边形轮廓；维度 < 3 时退化为提示 */
    private fun radar(ctx: Context, stats: List<Pair<String, Int>>, compact: Boolean): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val byCat = LinkedHashMap<String, Int>()
        for ((k, v) in stats) byCat.merge(ruleLabel(k).substringBefore("·").trim(), v, Int::plus)
        val dims = byCat.entries.sortedByDescending { it.value }.take(if (compact) 6 else 8)
        if (dims.size < 3) {
            box.addView(title(ctx, "维度不足", "至少需要 3 类命中才能画雷达图（当前 ${dims.size} 类）"))
            return box
        }
        val localMax = dims.maxOf { it.value }.coerceAtLeast(1)
        val radarH = dp(ctx, if (compact) 190 else RADAR_H)
        val radar = object : View(ctx) {
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val gridP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 1f; color = 0x33FFFFFF
            }
            private val labelP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
            }
            override fun onMeasure(w: Int, hs: Int) {
                setMeasuredDimension(resolveSize(suggestedMinimumWidth.coerceAtLeast(dp(ctx, 200)), w), radarH)
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val n = dims.size
                val cx = width / 2f
                val cy = height / 2f
                val radius = minOf(cx, cy) - dp(ctx, 34)
                if (radius <= 0) return
                for (ring in 1..4) {
                    val r = radius * ring / 4f
                    val path = android.graphics.Path()
                    for (i in 0 until n) {
                        val a = -Math.PI / 2 + 2 * Math.PI * i / n
                        val x = cx + (r * Math.cos(a)).toFloat()
                        val y = cy + (r * Math.sin(a)).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    canvas.drawPath(path, gridP)
                }
                labelP.textSize = dp(ctx, 10).toFloat()
                for (i in 0 until n) {
                    val a = -Math.PI / 2 + 2 * Math.PI * i / n
                    val x = cx + (radius * Math.cos(a)).toFloat()
                    val y = cy + (radius * Math.sin(a)).toFloat()
                    canvas.drawLine(cx, cy, x, y, gridP)
                    labelP.color = 0xCCFFFFFF.toInt()
                    canvas.drawText(
                        dims[i].key.take(4),
                        cx + ((radius + dp(ctx, 16)) * Math.cos(a)).toFloat(),
                        cy + ((radius + dp(ctx, 16)) * Math.sin(a)).toFloat(),
                        labelP
                    )
                }
                val dpath = android.graphics.Path()
                for (i in 0 until n) {
                    val a = -Math.PI / 2 + 2 * Math.PI * i / n
                    val r = radius * (dims[i].value.toFloat() / localMax)
                    val x = cx + (r * Math.cos(a)).toFloat()
                    val y = cy + (r * Math.sin(a)).toFloat()
                    if (i == 0) dpath.moveTo(x, y) else dpath.lineTo(x, y)
                }
                dpath.close()
                p.style = android.graphics.Paint.Style.FILL
                p.color = 0x55A0C4FF
                canvas.drawPath(dpath, p)
                p.style = android.graphics.Paint.Style.STROKE
                p.strokeWidth = dp(ctx, 2).toFloat()
                p.color = 0xFFA0C4FF.toInt()
                canvas.drawPath(dpath, p)
                p.style = android.graphics.Paint.Style.FILL
                for (i in 0 until n) {
                    val a = -Math.PI / 2 + 2 * Math.PI * i / n
                    val r = radius * (dims[i].value.toFloat() / localMax)
                    p.color = colorOf(dims[i].key)
                    canvas.drawCircle(
                        cx + (r * Math.cos(a)).toFloat(),
                        cy + (r * Math.sin(a)).toFloat(),
                        dp(ctx, 3).toFloat(), p
                    )
                }
            }
        }
        box.addView(radar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, radarH))
        // 图例
        for (e in dims) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 4))
            }
            row.addView(TextView(ctx).apply {
                text = e.key; textSize = 13f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = "${e.value}"; textSize = 13f; setTextColor(colorOf(e.key))
            })
            box.addView(row)
        }
        return box
    }

    /** 热力图：等宽色块网格，类别色 + 透明度表达强度 */
    private fun heat(ctx: Context, stats: List<Pair<String, Int>>, maxV: Int, compact: Boolean): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        }
        val cells = stats.take(if (compact) 9 else 12)
        val cols = if (compact) 2 else 3
        var i = 0
        while (i < cells.size) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                if (i + c < cells.size) {
                    val (name, v) = cells[i + c]
                    val ratio = v.toFloat() / maxV
                    val catColor = colorOf(name)
                    val alpha = (0x5A + (0xFF - 0x5A) * ratio).toInt().coerceIn(0x5A, 0xFF)
                    val cell = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(ctx, 10), dp(ctx, 12), dp(ctx, 10), dp(ctx, 12))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(ctx, 10).toFloat()
                            setColor(alpha(catColor, alpha))
                        }
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            .also { it.setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4)) }
                    }
                    cell.addView(TextView(ctx).apply {
                        text = "$v"; textSize = 22f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(if (ratio > 0.78f) 0xFF1A1A1A.toInt() else Color.WHITE)
                    })
                    cell.addView(TextView(ctx).apply {
                        text = ruleLabel(name).take(10); textSize = 11f
                        setTextColor(if (ratio > 0.78f) 0xCC1A1A1A.toInt() else 0xE6FFFFFF.toInt())
                        maxLines = 1
                    })
                    row.addView(cell)
                } else {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
            }
            box.addView(row)
            i += cols
        }
        return box
    }

    /** 列表：数值 + 比例条，信息最全 */
    private fun list(ctx: Context, stats: List<Pair<String, Int>>, maxV: Int, compact: Boolean): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sum = stats.sumOf { it.second }.coerceAtLeast(1)
        val shown = stats.take(if (compact) 12 else 20)
        box.addView(title(
            ctx, "列表",
            if (stats.size > shown.size) "显示前 ${shown.size} 项（共 ${stats.size}）" else "共 ${stats.size} 项"
        ))
        for ((name, v) in shown) {
            val catColor = colorOf(name)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 20), dp(ctx, 7), dp(ctx, 20), dp(ctx, 7))
            }
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9))
                    .also { it.setMargins(0, 0, dp(ctx, 6), 0) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = dp(ctx, 5).toFloat(); setColor(catColor)
                }
            })
            row.addView(TextView(ctx).apply {
                text = ruleLabel(name); textSize = 14f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
            })
            row.addView(TextView(ctx).apply {
                text = "$v"; textSize = 14f; setTextColor(catColor)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            row.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(ctx, 12), 0) })
            row.addView(TextView(ctx).apply {
                text = "%.0f%%".format(v * 100f / sum)
                textSize = 12f; setTextColor(0x99FFFFFF.toInt())
            })
            box.addView(row)
        }
        return box
    }
}
