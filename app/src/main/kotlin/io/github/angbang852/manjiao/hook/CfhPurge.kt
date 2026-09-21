package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger

// ★ ContentFilterHook 深拆第四步：统一删除器（2026-09 S3）。
// sanitizeList（身份判脏 + 后台闸门 + all-dirty 兜底刷新）与主线程删除器。
// 下沉为共享底层，切断 CfhClean<->CfhWash 互引环——清洗管线各层统一在此删除。
object CfhPurge {
    /** 可见脏项豁免上限：超过后不再豁免（约 1.5s @250ms 调用间隔），避免首屏脏项永久在屏 */
    internal const val MAX_VISIBLE_SKIPS = 6


    fun sanitizeList(list: MutableList<Any?>, tag: String, allowEmpty: Boolean = false) {
        // ★ 上下双视频修复（2026-09）：正在显示的那条不得删除（原地删→分页器位置
        // 错位→当前页叠出两个视频），等它滑出视野再清
        val visibleNow = try { CfhCapture.currentFeedPhoto() } catch (_: Throwable) { null }
        val dirtyIdx = arrayListOf<Int>()
        val originalSize = list.size
        val now = System.currentTimeMillis()
        for (i in list.indices) {
            val it = list[i] ?: continue
            val dirty = try {
                val q = CfhProbe.findQpInObject(it) ?: it
                // 兼容裸实体（LiveStreamFeed/广告实体无 mEntity 包装）：按类名兜底（受对应开关控制）；
                // ★ 宽匹配"Live"前先过结构类名黑名单（LiveConfig/LiveXxxPresenter 误删教训）
                val rawCls = it.javaClass.name
                CfhDecide.shouldFilterFeed(q) ||
                    (Prefs.bool(Prefs.K_FLT_LIVE, false) && rawCls.contains("LiveStreamFeed")) ||
                    (Prefs.bool(Prefs.K_FLT_ADS, false) && rawCls.contains("AdFeed")) ||
                    (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhUtil.isStructClsName(rawCls) && rawCls.contains("Live", true))
            } catch (_: Throwable) { false }
            if (it === visibleNow) {
                // ★★ 首屏可见项豁免上限（实证 2026-09 用户报「开头的 AI 内容拦不住」）：
                // 该条 === visibleNow → 旧代码无条件 continue，62 次 sanitize 全跳过它，
                // 真源 s0$b 又因 hasQp=false 从未清洗 ⇒ 它永久在屏，用户看到的就是它。
                // 保留原保护本意（原地删可见项→分页器错位→叠出两个视频），但设容忍上限：
                // 同一脏项被豁免超过 MAX_VISIBLE_SKIPS 次后按常规删除，由 triggerRefresh 补位。
                if (!dirty) { CfhState.visibleSkipOwner = null; CfhState.visibleSkipCount = 0; continue }
                if (CfhState.visibleSkipOwner !== it) {
                    CfhState.visibleSkipOwner = it
                    CfhState.visibleSkipCount = 1
                    continue
                }
                CfhState.visibleSkipCount++
                if (CfhState.visibleSkipCount <= MAX_VISIBLE_SKIPS) continue
                Logger.always("visible-dirty unshielded tag=$tag skips=${CfhState.visibleSkipCount}")
            }
            if (dirty) dirtyIdx.add(i)
        }
        if (dirtyIdx.isEmpty()) return
        // ★ 后台闸门：非线程安全列表的删除投回主线程按身份执行
        if (!CfhUtil.isBgMutationSafe(list) && Looper.myLooper() != Looper.getMainLooper()) {
            val dirtyId = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            for (i in dirtyIdx) list.getOrNull(i)?.let { dirtyId.add(it) }
            removeByIdentityOnMain(list, dirtyId, tag, allowEmpty)
            return
        }
        var removed = 0
        for (i in dirtyIdx.sortedDescending()) {
            // 直播位置替换成 VideoFeed 会触发 onMeasure ClassCastException，优先直接删除。
            // allowEmpty（真源存储列表，非 pager 直接数据结构）删到 0 也不崩
            val isLive = try { val rawCls = list[i]?.javaClass?.name ?: ""; rawCls.contains("LiveStreamFeed") || rawCls.contains("Live", true) } catch (_: Throwable) { false }
            if (isLive && (allowEmpty || list.size > 1)) {
                list.removeAt(i); removed++
            } else if (allowEmpty || list.size > 1) {
                list.removeAt(i); removed++
            }
        }
        if (removed > 0) {
            Logger.d("sanitize $tag removed/replaced $removed (left ${list.size})")
            // ★ 不 triggerRefresh（防滑动动画被打断，同 filterListArgs）
            // 全脏批次兜底：过滤后仍剩脏项（无干净替换可用、最后1项无法移除）→ 功能性刷新
            // 拉新批次，直到有干净视频进来（"开屏前几个全广告"场景的唯一出路）
            // 受「优化无更多视频」开关控制（与 prefetch 同一功能语义）
            if (Prefs.bool(Prefs.K_FLT_NOMORE, true) && list.isNotEmpty()) {
                val leftoverDirty = list.any { el ->
                    el != null && try {
                        val q = CfhProbe.findQpInObject(el) ?: el
                        CfhDecide.shouldFilterFeed(q)
                    } catch (_: Throwable) { false }
                }
                if (leftoverDirty && now - CfhState.lastAllDirtyRefreshAt > 3000) {
                    CfhState.lastAllDirtyRefreshAt = now
                    Logger.always("sanitize $tag all-dirty batch -> triggerRefresh (left ${list.size})")
                    CfhSupply.triggerRefresh()
                }
            }
        }
    }
    fun removeByIdentityOnMain(list: MutableList<Any?>, dirtyId: MutableSet<Any>, tag: String, allowEmpty: Boolean) {
        CfhState.handler.post {
            try {
                var removed = 0
                for (i in list.indices.reversed()) {
                    val el = list[i]
                    if (el != null && dirtyId.contains(el) && (allowEmpty || list.size > 1)) {
                        list.removeAt(i); removed++
                    }
                }
                if (removed > 0) Logger.d("sanitize-main $tag removed $removed (left ${list.size})")
            } catch (_: Throwable) {}
        }
    }
}
