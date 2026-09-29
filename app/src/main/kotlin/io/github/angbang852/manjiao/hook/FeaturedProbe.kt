package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

/**
 * 精选页结构探测（2026-09-23）。
 *
 * ## 要解决的问题
 *
 * 用户报：「精选页一条条刷」时出现**文案不变、画面在变**。
 *
 * 已查明的现状：
 * - 详情页有 `NasaPhotoDetailFragment.onResume` 钩子做 View 层跟踪
 * - **精选页的 `HomeFeaturedMilanoContainerFragment` 完全没有被 hook**
 *   （全项目搜不到任何处理代码，只有一条注释提到它）
 *
 * 因此模块不知道精选页「当前在屏是哪条」 → 认知与屏幕脱节。
 *
 * ## 为什么要先探测
 *
 * 已有线索显示精选页结构与详情页**不同**：
 * ```
 * // 精选 tab 列表元素是 WeakReference 包装（实证 r15d #9 elemCls=WeakReference entCls=null）
 * // 解包后若非 QPhoto（如 HomeFeaturedMilanoContainerFragment 容器），从内部找 QPhoto 再判定
 * ```
 * 即：列表元素可能不是 QPhoto，而是 `WeakReference(Fragment)` ——
 * 那就不能照搬详情页「从 Fragment 字段里找 QPhoto」那套。
 *
 * 本探针负责摸清：
 * 1. 列表元素到底是什么（WeakReference？里面装什么？）
 * 2. `HomeFeaturedMilanoContainerFragment` 持有 QPhoto 的字段路径
 * 3. 滑动换条时，哪个对象的什么字段会变（= 可用的「当前条」锚点）
 *
 * ## 只读保证
 *
 * 只读字段值、只做类型判断，**不修改任何状态**。
 */
object FeaturedProbe {

    private const val FEATURED_FRAG =
        "com.yxcorp.gifshow.featured.detail.featured.milano.HomeFeaturedMilanoContainerFragment"

    /** 已 dump 过的对象身份，避免重复输出 */
    private val dumped = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    )

    /**
     * dump 一个「精选页列表元素」的结构。
     *
     * 由 `CfhDecide.shouldFilterFeed` 在遇到 WeakReference 元素时调用
     * （那里已经知道 `inner` 的真实类型）。
     *
     * @param inner WeakReference 解包后的真实对象
     */
    fun dumpElement(inner: Any?) {
        if (inner == null) return
        val key = System.identityHashCode(inner)
        if (!dumped.add(key) || dumped.size > 5) return
        try {
            val cn = inner.javaClass.name
            val sb = StringBuilder("FEATPROBE 元素类=${inner.javaClass.simpleName} ($cn)")
            // ① 它是 Fragment 吗？是容器还是直接 QPhoto？
            val qpClass = CfhState.qpClassRef
            sb.append(" isQPhoto=").append(qpClass?.isAssignableFrom(inner.javaClass) == true)
            sb.append(" isFragment=").append(cn.contains("Fragment"))
            // ② 若从内部能找到 QPhoto，记录路径
            if (qpClass != null && !qpClass.isAssignableFrom(inner.javaClass)) {
                val path = findQpPath(inner, qpClass, 4)
                sb.append(" QPhoto路径=").append(path ?: "未找到")
            }
            // ③ dump 非静态字段（名+类型），找出「当前条」候选
            sb.append("\n  字段:")
            var c: Class<*>? = inner.javaClass
            var lvl = 0
            var n = 0
            while (c != null && c != Any::class.java && lvl < 3 && n < 25) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val tn = f.type.name
                    // 只关心可能承载数据的字段
                    val interesting = tn.contains("Photo") || tn.contains("Feed") ||
                        tn.contains("Model") || tn.contains("ViewModel") ||
                        tn.contains("Adapter") || tn.contains("Pager") ||
                        tn.contains("List") || tn.contains("Meta")
                    if (!interesting) continue
                    val v = try { f.isAccessible = true; f.get(inner) } catch (_: Throwable) { null }
                    sb.append("\n    ${f.name}:${f.type.simpleName}")
                    // List 要看元素类型（精选页列表元素是 WeakReference）
                    if (v is List<*>) {
                        val e0 = v.firstOrNull()
                        sb.append(" size=${v.size} el0=${e0?.javaClass?.simpleName ?: "-"}")
                        if (e0 != null) {
                            val u = try { (e0 as? java.lang.ref.WeakReference<*>)?.get() } catch (_: Throwable) { null }
                            if (u != null) sb.append(" el0解包=${u.javaClass.simpleName}")
                        }
                    } else if (v != null) {
                        sb.append(" =${v.javaClass.simpleName}")
                    }
                    n++
                }
                c = c.superclass; lvl++
            }
            Logger.once("featprobe:$key") { sb.toString() }
        } catch (_: Throwable) {}
    }

    /** BFS 找 QPhoto 字段路径（返回形如 `f1->f2->mPhoto`） */
    private fun findQpPath(root: Any, qpClass: Class<*>, maxDepth: Int): String? {
        try {
            val visited = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
            val queue = ArrayDeque<Pair<Any, String>>()
            queue.add(root to "")
            visited.add(root)
            var depth = 0
            while (queue.isNotEmpty() && depth < maxDepth) {
                val sz = queue.size
                for (i in 0 until sz) {
                    val (obj, path) = queue.removeFirst()
                    var c: Class<*>? = obj.javaClass
                    var lvl = 0
                    while (c != null && c != Any::class.java && lvl < 2) {
                        for (f in c.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            val v = try { f.isAccessible = true; f.get(obj) } catch (_: Throwable) { continue } ?: continue
                            val np = if (path.isEmpty()) f.name else "$path->${f.name}"
                            if (qpClass.isAssignableFrom(v.javaClass)) return np
                            // 只下钻非 JDK 对象，避免爆炸
                            if (!v.javaClass.name.startsWith("java.") && !v.javaClass.isPrimitive &&
                                visited.add(v)
                            ) queue.add(v to np)
                        }
                        c = c.superclass; lvl++
                    }
                }
                depth++
            }
        } catch (_: Throwable) {}
        return null
    }

    /** 供排障读取 */
    fun dumpCount(): Int = try { dumped.size } catch (_: Throwable) { 0 }

    /** 精选页 Fragment 类名（供 hook 站点引用，避免字符串散落） */
    const val FEATURED_FRAGMENT = FEATURED_FRAG

    // ==================== 精选页 View 树探测（2026-09-23）====================
    //
    // 上面 dumpElement 走的是「判定路径碰到 WeakReference 元素」的时机，
    // 但实测精选页多次刷动**没触发该分支**（列表元素这次直接是 QPhoto，
    // 不是 WeakReference）—— 说明那个包装只在特定时机出现，不能作为唯一入口。
    //
    // 因此补一条独立入口：直接从 **View 树**里找精选页播放容器，
    // 看它挂着哪些「当前条」相关的 id/子 View。这是不依赖判定路径的观测。

    /** 精选页特征 id（来自真机 XML dump 实测） */
    private val FEATURED_IDS = listOf(
        "featured_milano_front_view",
        "nasa_featured_default_search_view",
        "milano_player_seekbar",
        "featured_god_comment_tip",
        "featured_left_hamburger",
    )

    /**
     * 从当前 Activity 的 View 树里，dump 精选页相关的容器结构。
     *
     * 输出：命中的特征 id、对应 View 的类名、尺寸、子 View 数，
     * 以及**容器上挂的、可能承载数据的子 View id 清单** ——
     * 目标是找到「哪条在屏」的观测锚点。
     */
    fun dumpFeaturedViewTree() {
        try {
            val act = CfhState.tracked ?: run {
                Logger.once("featview.noact", "FEATVIEW: 无前台 Activity")
                return
            }
            val root = act.window?.decorView as? android.view.ViewGroup ?: return
            val hits = ArrayList<android.view.View>(8)
            val queue = ArrayDeque<android.view.View>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 5000) {
                val v = queue.removeFirst(); visited++
                val id = try { v.resources.getResourceEntryName(v.id) } catch (_: Throwable) { "" }
                if (id.isNotEmpty() && FEATURED_IDS.any { it == id }) hits.add(v)
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
                }
            }
            if (hits.isEmpty()) {
                Logger.once("featview.nohit", "FEATVIEW: 当前 View 树无精选页特征 id（visited=$visited）—— 可能不在精选页")
                return
            }
            val sb = StringBuilder("FEATVIEW 命中 ${hits.size} 个精选页容器（visited=$visited）")
            for (v in hits.take(4)) {
                val id = try { v.resources.getResourceEntryName(v.id) } catch (_: Throwable) { "?" }
                sb.append("\n  #$id cls=${v.javaClass.simpleName} [${v.width}x${v.height}] " +
                    "vis=${v.visibility} kids=${(v as? android.view.ViewGroup)?.childCount ?: 0}")
                // 往下两层，列出子 View 的 id（找数据锚点）
                if (v is android.view.ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 8)) {
                        val c = v.getChildAt(i) ?: continue
                        val cid = try { c.resources.getResourceEntryName(c.id) } catch (_: Throwable) { "" }
                        sb.append("\n     └[${i}] ${c.javaClass.simpleName} id=${cid.ifEmpty { "-" }} " +
                            "[${c.width}x${c.height}]")
                    }
                }
            }
            Logger.once("featview.dump") { sb.toString() }
        } catch (_: Throwable) {}
    }
}
