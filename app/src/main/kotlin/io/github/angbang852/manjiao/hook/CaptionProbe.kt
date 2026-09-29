package io.github.angbang852.manjiao.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.angbang852.manjiao.util.Logger

/**
 * 文案 View 与数据层**一致性**探针（2026-09-23）。
 *
 * ## 要抓的现象
 *
 * 用户报：「『客人丢东西』这个文案位置连续播放多个视频，滑动一次变一次」
 * 追问确认：**画面在变，文案不变**。
 *
 * 即：数据层正常换条（所以视频换了），但承载文案的 View 内容没跟着更新。
 *
 * ## 为什么不能用"现场抓日志"
 *
 * 该现象在**重启后不复现**（用户实测指出）——属累积/状态相关，且偶发。
 * 靠「用户复现 → 我去抓」的窗口极窄。因此改为**模块自己持续比对并记录**：
 * 每次滑动/绑定时，把「数据层认定的当前条文案」与「屏幕上 CaptionTextView
 * 实际显示的文字」放在一起比，**不一致就留证**。
 *
 * ## 记录形式
 *
 * 只在不一致时输出（once 级、带指纹去重），正常情况零输出 ——
 * 避免变成噪声源。输出含：
 * - 数据层文案（`DL vis` 同源）
 * - 各 CaptionTextView 的实际文字 + 可见性 + 位置
 * - 差异结论
 *
 * ## 只读保证
 *
 * 本类**不修改任何 View**，只读 `text` / `visibility` / 布局坐标。
 */
object CaptionProbe {

    /** 已记录过的「不一致」指纹，避免重复刷屏 */
    private val seen = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * 比对「数据层当前条」与「屏幕上的文案 View」。
     *
     * @param dataCap 数据层认定的当前条文案（调用方从 QPhoto 读出）
     */
    fun check(dataCap: String?) {
        try {
            if (dataCap.isNullOrBlank()) return
            val act = CfhState.tracked ?: return
            val root = act.window?.decorView as? ViewGroup ?: return

            // 收集屏幕上所有文案类 TextView（快手已知 id：element_caption_label /
            // global_caption_label；再兜底按类名 CaptionTextView）
            val views = ArrayList<Pair<TextView, String>>(4)
            collectCaptionViews(root, views, 0)
            if (views.isEmpty()) return

            val norm = { s: String -> s.replace(Regex("\\s+"), "").take(12) }
            val want = norm(dataCap)
            // 只要**有一个可见的文案 View 与数据层对得上**，就说明绑定正常
            val anyMatch = views.any { it.first.visibility == View.VISIBLE && norm(it.second).isNotEmpty() && norm(it.second) == want }
            if (anyMatch) return

            // 全都不匹配 → 记录证据
            val shown = views.filter { it.first.visibility == View.VISIBLE && it.second.isNotBlank() }
            if (shown.isEmpty()) return    // 屏上没文案（可能详情页/无文案条），不算异常

            val key = want + "=>" + shown.joinToString("|") { norm(it.second) }
            if (!seen.add(key)) return

            val sb = StringBuilder("CAPMISMATCH 数据层=\"${dataCap.take(20)}\" 屏上=")
            for ((tv, t) in shown.take(3)) {
                sb.append("[${tv.id.toString().let { id -> try { tv.resources.getResourceEntryName(tv.id) } catch (_: Throwable) { id } }}")
                    .append(" vis=${tv.visibility} top=${tv.top} len=${t.length} \"${t.take(20)}\"]")
            }
            Logger.once("capmismatch:$key") { sb.toString() }
        } catch (_: Throwable) {}
    }

    /** 递归找文案 TextView（深度上限 12，避免遍历过深） */
    private fun collectCaptionViews(v: View, out: MutableList<Pair<TextView, String>>, depth: Int) {
        if (depth > 12) return
        try {
            if (v is TextView) {
                val id = try { v.resources.getResourceEntryName(v.id) } catch (_: Throwable) { "" }
                val cn = v.javaClass.simpleName
                val isCaption = id == "element_caption_label" || id == "global_caption_label" ||
                    id.contains("caption", true) || cn.contains("Caption")
                if (isCaption) {
                    out.add(v to (v.text?.toString() ?: ""))
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i) ?: continue
                    collectCaptionViews(c, out, depth + 1)
                }
            }
        } catch (_: Throwable) {}
    }

    /** 供排障读取已记录的不一致条数 */
    fun mismatchCount(): Int = try { seen.size } catch (_: Throwable) { 0 }
}
