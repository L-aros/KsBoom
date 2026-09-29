package io.github.angbang852.manjiao.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.widget.FrameLayout
import io.github.angbang852.manjiao.util.Logger
import kotlin.math.max

/**
 * 液态玻璃面板 —— 采用 WeChat-LiquidGlass（liuran001/mmliquidglass）的配方结构。
 *
 * 原版 KernelSU 效果栈：vibrancy(sat 1.5) → blur(4dp) → lens(边缘 24dp SDF 折射)
 * → surface wash(surfaceContainer 40%) → rim highlight(1dp)。
 *
 * backdrop 差异：微信列表是普通 View，page.draw() 可采样，全链 AGSL 在位图上跑；
 * 快手视频是 SurfaceView（独立 compositor layer，View 体系采不到样），故 blur
 * 交给 SurfaceFlinger 跨窗模糊（FLAG_BLUR_BEHIND + LayoutParams.blurBehindRadius，
 * API 31+）对视频 layer 实时执行——零采样、天然同步、零开销。SF 模糊区域 =
 * window 边界，因此承载本面板的 Dialog window 缩到面板大小（attachOverlay）。
 * vibrancy / SDF 折射需要读到背景像素，SF 模糊结果采不到：折射以 bevel 边缘
 * 渐变近似，vibrancy 舍弃。
 *
 * 表面层参数照抄 LiquidGlassPanel / LiquidGlassHostLayout：
 *   - dropShadow(10dp, offset 0/2dp, alpha 亮 0.1 / 暗 0.2)，fill 挖除只留外溢影
 *   - wash = surfaceContainer.copy(0.4f)
 *   - gloss = 顶部 45% 高度渐变 0x30FFFFFF → 透明
 *   - rim = 1dp 描边 0x2EFFFFFF（亮）/ 0x1FFFFFFF（暗）
 *
 * 结构照抄 HostLayout.setupShadow：四周 reserve 14dp 投影 padding，子内容
 * shrink 到内框，投影画在 padding 区，不被 window 边界裁掉。
 */
class GlassPanel(
    ctx: Context,
    private val cornerRadiusPx: Float,
    /** wash 覆盖色（含 alpha）；null=用默认 surfaceContainer.copy(0.4f)。
     *  app 菜单背景为静态图片（无 SF 跨窗模糊加持）时传更高 alpha 弥补雾面感。 */
    private val washOverride: Int? = null
) : FrameLayout(ctx) {

    private val density = resources.displayMetrics.density
    private val dark = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** 投影外溢 reserve（照抄 HostLayout.setupShadow 的 14dp）。 */
    val shadowPad = Math.round(density * 14f)

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }


    private val bounds = RectF()
    private val shape = Path()

    init {
        setWillNotDraw(false)
        // ★ 性能专项（2026-09-23）：**不再对容器设 LAYER_TYPE_SOFTWARE**。
        //
        // 原实现（审阅 2026-09 P2）为了让 Paint.setShadowLayer 画在 Path 上的投影
        // 可见，给 GlassPanel 整体设了软件层。但软件层在 Android 里是**整棵子树的
        // 合成画布**——面板里的 header、10 行菜单、每个带 setShadowLayer 的 TextView
        // 全部退化为 CPU 绘制。实测菜单打开：8 秒内只出 7 帧且 100% janky、
        // 90th=300ms（≈2 秒级冻结窗口），其中构建仅 130ms，其余是软件绘制的持续代价。
        //
        // 现改为：**投影预渲染成一张 Bitmap**（在软件 Canvas 上画一次，仅尺寸变化时重画），
        // 容器回到硬件加速。每帧只剩 drawBitmap(投影) + 三笔 path，子树走 GPU。
        // 观感与原实现一致（同一套 setShadowLayer 参数、同一 shadowPad 预留）。
        setPadding(shadowPad, shadowPad, shadowPad, shadowPad)
        // KernelSU: dropShadow(radius = 10.dp, alpha = dark ? 0.2f : 0.1f)
        shadowPaint.color = 0xFF000000.toInt()
        shadowPaint.setShadowLayer(
            density * 10f, 0f, density * 2f,
            if (dark) 0x33000000 else 0x1A000000
        )
        // surfaceContainer.copy(0.4f)
        washPaint.color = washOverride ?: (if (dark) 0x662F3036 else 0x66EFF1F7)
        // rim highlight 1dp
        rimPaint.strokeWidth = max(density, 0.75f)
        rimPaint.color = if (dark) 0x1FFFFFFF else 0x2EFFFFFF
    }

    /** 预渲染的投影位图（尺寸变化时重画；平时每帧只 drawBitmap） */
    private var shadowBitmap: android.graphics.Bitmap? = null

    /**
     * A/B 对照用：把容器切回软件层（旧行为）或硬件层（新行为）。
     *
     * 尺寸变化与 `Logger.diag` 翻转时都会重新应用；`diag=true` 时不生成位图，
     * 走每帧 drawPath + 软件画布（= 改动前的绘制路径），用于同一时间窗内对照。
     */
    private fun applyLayerMode(w: Int, h: Int) {
        if (Logger.diag) {
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            shadowBitmap?.recycle()
            shadowBitmap = null
        } else {
            setLayerType(android.view.View.LAYER_TYPE_NONE, null)
            if (w > 0 && h > 0) rebuildShadowBitmap(w, h)
        }
    }

    /**
     * 把投影画进一张位图。
     *
     * 为什么需要：`Paint.setShadowLayer` 对 `Path` 的模糊只在**软件画布**生效
     * （硬件加速下不渲染）。原实现整个容器开软件层换取投影，代价是子树全部 CPU 绘制；
     * 现在只在一张内存位上用软件画布跑一次模糊，之后硬件合成 —— 两全。
     */
    private fun rebuildShadowBitmap(w: Int, h: Int) {
        try {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val save = c.save()
            c.clipOutPath(shape)          // pill 区域挖除防黑边（同原 drawDropShadow）
            c.drawPath(shape, shadowPaint)
            c.restoreToCount(save)
            shadowBitmap?.recycle()
            shadowBitmap = bmp
        } catch (_: Throwable) {
            shadowBitmap = null
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // 内框（投影 padding 之内）——HostLayout：children shrink into the inner box
        bounds.set(
            shadowPad.toFloat(), shadowPad.toFloat(),
            (w - shadowPad).toFloat(), (h - shadowPad).toFloat()
        )
        shape.reset()
        shape.addRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        // gloss：顶部 45% 高度渐变（HostLayout 参数）
        glossPaint.shader = LinearGradient(
            0f, bounds.top, 0f, bounds.top + bounds.height() * 0.45f,
            if (dark) 0x1FFFFFFF else 0x30FFFFFF,
            0x00FFFFFF, Shader.TileMode.CLAMP
        )
        // 绘制模式（硬件层 + 预渲染投影位图；diag=true 时退回旧软件层路径）
        applyLayerMode(w, h)
    }

    /** 上一次应用的模式（diag 翻转时重应用，供 A/B 对照） */
    private var lastDiagMode = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastDiagMode = Logger.diag
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        // diag 在挂载后被翻转（A/B 对照）→ 重应用图层模式与位图
        if (lastDiagMode != Logger.diag) {
            lastDiagMode = Logger.diag
            applyLayerMode(width, height)
            invalidate()
        }
        drawDropShadow(canvas)
        // 原版表面层只有三笔：wash → gloss → rim(1dp)。bevel/glint 已删
        // （直透模式的假光感，有真模糊后是多余的粗边框）
        canvas.drawPath(shape, washPaint)
        canvas.drawPath(shape, glossPaint)
        val half = rimPaint.strokeWidth / 2f
        canvas.save()
        canvas.translate(half, half)
        canvas.drawPath(shape, rimPaint)
        canvas.restore()
    }

    /**
     * HostLayout.drawPillShadow：不透明圆角矩形只为投影，pill 区域挖除防黑边。
     *
     * ★ 性能专项（2026-09-23）：改为贴预先渲染好的投影位图（硬件加速下可正常合成）。
     * 位图未就绪（尺寸为 0 或分配失败）时退回软件路径绘制，保证观感不丢。
     */
    private fun drawDropShadow(canvas: Canvas) {
        if (Build.VERSION.SDK_INT < 26) return
        // ★ A/B 对照开关（性能专项 2026-09-23）：diag=true 时走**旧路径**
        //（容器软件层 + 每帧 drawPath 画投影），便于同一时间窗内对照两种绘制方式的
        // 菜单打开耗时；diag=false（默认）走预渲染位图路径。
        if (Logger.diag) {
            val save = canvas.save()
            canvas.clipOutPath(shape)
            canvas.drawPath(shape, shadowPaint)
            canvas.restoreToCount(save)
            return
        }
        val bmp = shadowBitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
            return
        }
        val save = canvas.save()
        canvas.clipOutPath(shape)
        canvas.drawPath(shape, shadowPaint)
        canvas.restoreToCount(save)
    }
}
