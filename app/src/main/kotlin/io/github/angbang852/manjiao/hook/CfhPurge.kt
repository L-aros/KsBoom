package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

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
                // ★★★ 黑名单直判（2026-09-24）—— 唯一能治「反复重建」的手段。
                //
                //   ## 实测（主人报「王大艺」「锋哥看百态」）
                //
                //   同一个 `mQPhotos@HomeFeedResponse` 被反复重建：
                //     5308050  移除点 mQPhotos 剩4
                //     5308082  移除点 mQPhotos 剩2
                //     5308110  移除点 mQPhotos 剩1   ← 一路删到剩 1
                //     5308164  VISDEL-SUM 含目标4 移除4 处  ← 又变回 4 处
                //     5309134  VISDUMP 还在
                //
                //   条目轨迹显示它被删了至少 3 轮（5161950 / 5303623 / 5308164），
                //   每轮 100+ 秒后又回来，`size=6 首id=同一条` 恒定。
                //
                //   ## 为什么「再删一次」治不了
                //
                //   快手从服务端/缓存重建 `HomeFeedResponse`，
                //   `mQPhotos` 又被填成同样那几条。删除是**追着填**，
                //   永远慢一步 —— 用户就会看到。
                //
                //   ## 本判据的作用
                //
                //   在**列表被处理的那一刻**就直接判脏，不再依赖：
                //     · 判定路径是否走到（缓存命中会跳过）
                //     · 判定时机是否够快
                //   只要该 id 曾经被判脏过（`hit()` 或 `PSCAN` 已写入黑名单），
                //   之后**每一次**经过 sanitizeList 都会被立即剔除。
                //
                //   这比「等下一轮周期任务」快一个数量级 ——
                //   重建与剔除在同一轮处理内完成。
                //
                //   安全：按 photoId 精确匹配（`readPhotoId` 不匹配即返回 null，
                //   不会误伤）；名单上限 512 且满则清空，不会无界增长。
                val pidNow = CfhProbe.readPhotoId(q)
                // ★★★ 2026-09-29 v13.20 移除黑名单兜底（用户定稿）：网络层白名单
                //   每批逐条新鲜判定已足够；黑名单把误判变 30 分钟永久删除。
                val byBlacklist = false
                byBlacklist ||
                    // 兼容裸实体（LiveStreamFeed/广告实体无 mEntity 包装）：按类名兜底（受对应开关控制）；
                    // ★ 宽匹配"Live"前先过结构类名黑名单（LiveConfig/LiveXxxPresenter 误删教训）
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
        // ★ 首轮判出的脏项总数（S2 修复）：用于末尾推导「残留脏项」，
        // 避免删除后再跑一遍 findQpInObject BFS 全表扫描
        val totalDirty = dirtyIdx.size
        // ★★ 修正（2026-09-22，用户实测「服务请求异常 / 划不出视频」后定位）：
        // 原实现按**首轮记录的下标** list.removeAt(i) 删除 —— 但本函数是 hook 回调，
        // 跑在宿主线程上，**列表内容由快手控制**：首轮遍历与删除之间宿主可能已改动
        // 列表（插入/删除导致下标位移），此时按旧下标删除会：
        //   ① 删错元素（干净的被删、脏的留下）
        //   ② 下标越界抛 IndexOutOfBoundsException（宿主 shrink 时）
        // 现改为**收集对象引用**，用身份集合做单次过滤 —— 不依赖任何下标稳定性。
        val dirtyRefs = ArrayList<Any>(dirtyIdx.size)
        for (i in dirtyIdx) list.getOrNull(i)?.let { dirtyRefs.add(it) }
        if (dirtyRefs.isEmpty()) return
        // ★ 后台闸门：非线程安全列表的删除投回主线程按身份执行
        if (!CfhUtil.isBgMutationSafe(list) && Looper.myLooper() != Looper.getMainLooper()) {
            val dirtyId = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            dirtyId.addAll(dirtyRefs)
            removeByIdentityOnMain(list, dirtyId, tag, allowEmpty)
            return
        }
        var removed = 0
        try {
            val dirtyId = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            dirtyId.addAll(dirtyRefs)
            // allowEmpty=true（真源存储列表）可删空；false 时至少保留 1 项
            val before = list.size
            if (allowEmpty) {
                list.removeAll { el -> el != null && dirtyId.contains(el) }
            } else {
                // 至少留 1：删到剩 1 项即停（与原 allowEmpty || size > 1 的护栏同语义）
                var i = list.size - 1
                while (i >= 0 && list.size > 1) {
                    val el = list.getOrNull(i)
                    if (el != null && dirtyId.contains(el)) list.removeAt(i)
                    i--
                }
            }
            removed = before - list.size
        } catch (_: Throwable) {
            // 并发修改兜底：逐个身份迭代删除（迭代器 remove 对 CME 更宽容）
            for (h in dirtyRefs) {
                try {
                    val it = list.iterator()
                    while (it.hasNext()) {
                        if (it.next() === h) {
                            if (allowEmpty || list.size > 1) { it.remove(); removed++ }
                            break
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        if (removed > 0) {
            // ★★★ COW 快照提交补偿（2026-09-25 真机实证补入）。
            //
            //   必须在 removed 统计之后、返回之前执行 —— 补偿本身是一次写。
            //   见 CfhUtil.commitCowWrite 的完整说明。
            //
            //   为什么本处此前是缺口：全项目唯一的 COW 补偿原本只在
            //   `CfhDiag.removeFromFragContainers` 里（原 :192-203），
            //   而 `sanitizeList` 这条路径（`deep:l.a` / `ret` / `fla`）**从未有过**。
            //   真源 `vm.l.a` 实测确认是 COW（hc=91926624），
            //   实测后果是「删 11 条剩 1 → 删 20 条剩 23」——
            //   一半删除是无效删除，剩余量反而涨得更快。
            try { CfhUtil.commitCowWrite(list) } catch (_: Throwable) {}
            Logger.probe { "sanitize $tag removed/replaced $removed (left ${list.size})" }
            // ★★ 2026-09-23 回滚说明：这里曾加入 notifyPagerDataChanged()，理由是
            //   「删除后不通知 adapter 导致分页器错位」。真机实测证明**推断错误**：
            //   实测发现真正的错位来源是 CfhViewHook 的 `pager swap` 无限循环
            //   （同一位置被反复替换、sw=13 但粘不住），而本处通知会让分页器重绘
            //   → 重新读该位置 → 又触发替换 → **反而加剧循环**。
            //   因此撤回，保持与原实现一致（删除后不主动通知）。
            //   若日后确认删除路径确实需要通知，应先解决 swap 循环再启用。
            // [已移除 2026-09-26] 「优化无更多视频」（`flt_nomore`）整段删除。
            //
            // 移除理由（用户定稿「删除」）：
            //
            // 该开关的语义是「全脏批次 ⇒ `CfhSupply.triggerRefresh()` 拉新批次」，
            // 目的为**避免「无更多视频」**。但在当前架构下这个目的已不成立：
            //
            // | 旧架构 | 新架构（2026-09-26 起） |
            // |---|---|
            // | 数据进列表后**删除**脏项 | **`PresenterV2.bind` 处拦截，卡片根本不创建** |
            // | 全脏批次 ⇒ 剩不下内容 ⇒ 「无更多视频」 | **不 bind ⇒ 直接跳过，下一条正常显示** |
            //
            // ⇒ **跳过而非删除 ⇒ 不会出现「无更多视频」** ⇒ 该兜底逻辑失去意义。
            //
            // 原代码：
            // ```kotlin
            // if (Prefs.bool(Prefs.K_FLT_NOMORE, true) && list.isNotEmpty()) {
            //     val leftoverDirty = totalDirty > removed
            //     if (leftoverDirty && now - CfhState.lastAllDirtyRefreshAt > 3000) {
            //         CfhState.lastAllDirtyRefreshAt = now
            //         CfhSupply.triggerRefresh()
            //     }
            // }
            // ```
            //
            // **保留的**：`CfhSupply.triggerRefresh()` 本身（其他地方仍在用），
            // 以及 `sanitizeList` 的删除路径（`TTPPARSE` 等仍需要）。
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
                if (removed > 0) {
                    // ★★★ COW 快照提交补偿（2026-09-25 补入）——
                    //   本路径原先同样没有补偿（与 sanitizeList 同一缺口）。
                    //   投递到主线程删除的场景下，旧快照问题同样存在。
                    //   见 CfhUtil.commitCowWrite。
                    try { CfhUtil.commitCowWrite(list) } catch (_: Throwable) {}
                    Logger.probe { "sanitize-main $tag removed $removed (left ${list.size})" }
                }
            } catch (_: Throwable) {}
        }
    }
}
