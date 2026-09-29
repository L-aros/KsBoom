package io.github.angbang852.manjiao.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * 趋势图 · 网站式实现（New API / VChart spec_model_line 的零依赖移植）。
 *
 * 2026-09-23 对齐拼车站「模型调用趋势」的画法：
 *  - **架构**：声明式 [Spec] 与绘制分离，渲染器只消费 spec —— 等价于
 *    网站 `processChartData 产 spec → <VChart spec>` 的两段式；
 *  - **曲线**：Fritsch–Carlson 单调三次保形插值，与 VChart `curveType:'monotone'`
 *    同族：穿过每个真实数据点、段内绝不过冲；**不再做 [1,2,1] 显示级预平滑**，
 *    图形与数字同源（数据诚实）；
 *  - **面积**：网站用 fillOpacity 0.08 轻填充 → 亮玻璃浮层上该量级近乎不可见
 *    （旧版实测），故把 opacity 语义折叠进 ARGB alpha，保留深藏蓝实感填充；
 *  - **裁剪/降采样/滚动判定**仍在调用方数据管线完成，这里只管「画」。
 *
 * 零依赖：纯 Canvas 手绘，不引任何图表库（Xposed 进程内模块的硬约束）。
 */
object TrendChart {

    /** 声明式规格 —— 字段语义对齐 VChart spec_model_line */
    data class Spec(
        /** data.values：真实桶序列 (epoch ms, count)，不预平滑 */
        val values: List<Pair<Long, Int>>,
        /** line.style.color */
        val lineColor: Int = 0xFF4A9EFF.toInt(),
        /** 面积填充（ARGB，opacity 语义折叠进 alpha）—— 旧版深藏蓝实感 */
        val fillColor: Int = 0x8C1C3E70.toInt(),
        /** line.style.lineWidth(dp) */
        val lineWidthDp: Int = 2,
        /** xField 时间标签格式 */
        val timeFormat: String = "HH:mm",
        /** 容器高度(dp) */
        val heightDp: Int = 150,
    )

    /** 放不下才需要横向滚动（与旧版同式：size × minSlot > availW） */
    fun needsScroll(size: Int, minSlotPx: Int, availWPx: Int): Boolean = size * minSlotPx > availWPx

    /**
     * ★★★ 按**时间标签实测宽度**动态计算可绘制宽度（2026-09-26 用户报「左右时间被截断」）。
     *
     * ## 为什么需要
     *
     * 调用方原先写死预留边距：
     * ```kotlin
     * val availW = ctx.resources.displayMetrics.widthPixels - dp(ctx, 88 + 40)
     * ```
     * 而时间标签宽度**随格式 / 语言 / 系统字号变化**：
     * ```
     * "23:45"        → 窄
     * "09-26"        → 窄
     * "09-26 23:45"  → 宽（长格式）
     * ```
     * ⇒ 写死预留**必然在标签变宽时截断**。
     *
     * ## 做法
     *
     * 用与图表轴标签**同字号的 `Paint`** 实测标签像素宽：
     * ```
     * availW = 屏宽 - 左标签宽 - 右标签宽 - 2×内边距
     * ```
     *
     * @param labelSample 实际会渲染的最长标签样例（如 `"23:45"` / `"09-26"`）
     * @return 可绘制宽度（像素，已 clamp 到合理下限）
     */
    fun availWidthFor(ctx: Context, labelSample: String): Int {
        val screenW = ctx.resources.displayMetrics.widthPixels
        val labelW = try {
            val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 9f * ctx.resources.displayMetrics.density   // 与 ChartView :155 的轴标签同字号
            }
            pt.measureText(labelSample)
        } catch (_: Throwable) {
            dp(ctx, 42).toFloat()                             // 兜底：按 42dp 估算
        }
        // ★ 左右各留**半个标签宽**（因为标签是居中到数据点的，最左/最右各溢出半个）
        //   —— 原为「各留一个完整标签宽」（过于保守，可用宽度被压缩）。
        //   绘制侧已用 `coerceIn` 保证不出界，故此处留半宽即可。
        val pad = dp(ctx, 16)
        val w = screenW - labelW.toInt() - pad * 2
        return w.coerceAtLeast(dp(ctx, 160))                  // 下限：保证图形区可见
    }


    /**
     * 把趋势图挂进容器：放不下才套 HorizontalScrollView（避免与外层纵向
     * ScrollView 抢手势）；返回 needScroll 供调用方写「可左右滑动」提示。
     *
     * @param availWPx  可用画布宽(px)
     * @param minSlotPx 挤压模式下每点最小间距(px)
     */
    fun mount(
        container: LinearLayout,
        ctx: Context,
        spec: Spec,
        availWPx: Int,
        minSlotPx: Int,
    ): Boolean {
        val chart = ChartView(ctx, spec, availWPx, minSlotPx)
        val h = dp(ctx, spec.heightDp)
        val need = needsScroll(spec.values.size, minSlotPx, availWPx)
        if (need) {
            val hs = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(chart, ViewGroup.LayoutParams.WRAP_CONTENT, h)
            }
            container.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        } else {
            container.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        }
        return need
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** spec → Canvas 渲染器（monotone 保形曲线 + 面积 + 网格 + 时间标签） */
    private class ChartView(
        private val ctx: Context,
        private val spec: Spec,
        private val availWPx: Int,
        private val minSlotPx: Int,
    ) : View(ctx) {

        private val density = ctx.resources.displayMetrics.density
        private val n = spec.values.size
        private val peak = spec.values.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        private val needScroll = needsScroll(n, minSlotPx, availWPx)
        private val fmt = java.text.SimpleDateFormat(spec.timeFormat, java.util.Locale.getDefault())

        private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = spec.lineWidthDp * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = spec.lineColor
        }
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = spec.fillColor
        }
        private val gridP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = 0x1AFFFFFF
        }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * density
            color = 0x99FFFFFF.toInt()
        }

        // 自定义 View 必须实现 onMeasure（否则高度返回 0，容器测量失效）
        override fun onMeasure(w: Int, h: Int) {
            val want = if (needScroll) n * minSlotPx + dp(ctx, 12) else availWPx
            setMeasuredDimension(resolveSize(want, w), resolveSize(dp(ctx, spec.heightDp), h))
        }

        override fun onDraw(canvas: Canvas) {
            if (n == 0) return
            val availH = height - dp(ctx, 16)
            if (availH <= 0) return
            val slot = width.toFloat() / n
            val baseY = availH.toFloat()

            // ① 各点坐标：x 取 slot 中点；y 直接用**真实值**（不预平滑）
            val xs = FloatArray(n); val ys = FloatArray(n)
            for (i in 0 until n) {
                xs[i] = slot * i + slot / 2f
                ys[i] = baseY - (availH - dp(ctx, 6)) * spec.values[i].second.toFloat() / peak
            }

            // ② monotone 保形曲线（Fritsch–Carlson → cubic Bézier）
            val line = monotonePath(xs, ys)

            // ③-1 横向网格线（25%/50%/75%，极淡白）
            for (f in 1..3) {
                val gy = baseY - (baseY - dp(ctx, 6)) * f / 4f
                canvas.drawLine(0f, gy, width.toFloat(), gy, gridP)
            }
            // ③-2 面积：闭合到底边，纯色填充 —— ⚠ 勿改 LinearGradient（负 Int 色在
            //      Android 16 framework 版会抛 IllegalArgumentException 崩快手）
            val area = Path(line).apply {
                lineTo(xs[n - 1], baseY); lineTo(xs[0], baseY); close()
            }
            canvas.drawPath(area, fillP)
            canvas.drawPath(line, lineP)

            // ④ 时间标签密度自适应（与旧版一致）
            val step = ((dp(ctx, 40) / minSlotPx) + 1).coerceAtLeast(1)
            var i = 0
            while (i < n) {
                // ★★★ 时间标签绘制修正（2026-09-26 用户报「最左边的时间显示不全」）。
                //
                // ## 原实现的问题
                // ```kotlin
                // canvas.drawText(..., xs[i] - dp(ctx, 12), ...)   // 硬编码左偏移 12dp
                // ```
                // 第一个点的 x = slot/2，减去 12dp 后**可能 < 0** ⇒ 左半部分被切掉。
                // 右侧同理：最后一个点 + 标签宽可能超出画布。
                //
                // ## 修法：**按实测标签宽度居中 + 夹在画布内**
                //   · 先 `measureText` 拿到真实宽度
                //   · 居中到数据点
                //   · `coerceIn` 夹到 [0, width - labelW] ⇒ 永不出界
                val label = fmt.format(java.util.Date(spec.values[i].first))
                val lw = tp.measureText(label)
                var lx = xs[i] - lw / 2f
                lx = lx.coerceIn(0f, (width - lw).coerceAtLeast(0f))
                canvas.drawText(label, lx, height - dp(ctx, 3).toFloat(), tp)
                i += step
            }
        }

        /**
         * 单调三次插值（Fritsch–Carlson）→ 贝塞尔 path：
         * 段内切线取邻差调和加权；差值变号处切线置 0（峰「站住」，无过冲）；
         * Hermite→Bézier 标准换元（控制点偏移 h/3）。曲线穿过每个数据点、全段平滑。
         */
        private fun monotonePath(xs: FloatArray, ys: FloatArray): Path {
            val path = Path()
            path.moveTo(xs[0], ys[0])
            if (n == 1) { path.lineTo(xs[0], ys[0]); return path }
            if (n == 2) { path.lineTo(xs[1], ys[1]); return path }
            val h = FloatArray(n - 1)
            val d = FloatArray(n - 1)
            for (i in 0 until n - 1) {
                h[i] = xs[i + 1] - xs[i]
                d[i] = if (h[i] != 0f) (ys[i + 1] - ys[i]) / h[i] else 0f
            }
            val m = FloatArray(n)
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) {
                m[i] = if (d[i - 1] * d[i] <= 0f) 0f else {
                    val w1 = 2f * h[i] + h[i - 1]
                    val w2 = h[i] + 2f * h[i - 1]
                    (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
                }
            }
            for (i in 0 until n - 1) {
                path.cubicTo(
                    xs[i] + h[i] / 3f, ys[i] + m[i] * h[i] / 3f,
                    xs[i + 1] - h[i] / 3f, ys[i + 1] - m[i + 1] * h[i] / 3f,
                    xs[i + 1], ys[i + 1]
                )
            }
            return path
        }
    }
}
