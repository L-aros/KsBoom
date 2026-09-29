package io.github.angbang852.manjiao.hook

import android.view.ViewGroup
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第二步：诊断职责（2026-09 S3）。
// 对象图/字段/实体 dump 与 diagFragment 排查。纯只读诊断，不改变过滤行为。
object CfhDiag {
    /**
     * ★★ 就地移除（2026-09-24）—— 修「判了不删」。
     *
     * ## 为什么需要（实测铁证）
     *
     * 实测（主人报「泡泡追剧」）：
     * ```
     *   17:56:05.021  VISJUDGE 可见页判脏→触发删除 cap="#快来看短剧…"
     * 17:56:05.027  VISDUMP  判脏=true 昵称="泡泡追剧🫧" 声明="疑似含AI生成内容"
     *   同期 DEL：ret 删14条 首项="亚马逊雨林有多狠？…" 同一对象=false 剩=40
     *   同期 MINDEL：删9条 [a@ss0.a] 首项="iPhone18Pro首发就破价"
     * ```
     *   —— 判脏成功、删除动作也触发了，但删的全是别的内容。
     *   屏幕上那条始终没被删到。
     *
     * ## 根因
     *
     *   可见页 QPhoto 取自 **Fragment 字段**，而清洗只遍历 **VM 可达列表**。
     *   这条内容不在 VM 列表上时，清洗再多遍也碰不到它 —— 删除打空了。
     *
     * ## 本函数做什么
     *
     *   从 Fragment 出发（深度6）找到所有**列表容器**，
     *   按 **photoId 精确匹配**移除目标项。三条硬性护栏：
     *
     * ## 安全护栏（每一条都是血泪教训）
     *
     *  1. **只动列表，绝不动单值字段** ——
     *     改 Fragment 的单值字段（如 mCurrentPhoto）等于把它的状态改坏，
     *     快手下次读会拿到 null → 崩溃或白屏。
     *  2. **按 photoId 匹配，不按对象引用** ——
     *     同一内容在快照/真源里是**不同对象实例**（实测「同一对象=false」），
     *     按引用匹配会漏删。
     *  3. **列表保留至少 1 项** ——
     *     清空列表会让快手读到空集合 → `无更多作品` 或空指针。
     *     这条是从「无更多作品」事故里学到的。
     *  4. **跳过 `o` 字段与 `m*` 框架字段**。
      * （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
     *     `o` 是分页状态依赖的引用表（删它 = 无更多作品）。
      * （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
     *
     * @return 实际移除的处数（0 = 没找到目标）
     */
    /**
     * 【已停用】按 photoId 从对象图列表里移除元素。
     *
     * ## 用户定稿（2026-09-26，方案 C）
     *
     * > 「渲染层不应该还有拦截机制，视频上屏了也应该不会删除」
     * > 「还是改成白名单，要判正常的才能放行」
     *
     * ## 为什么整体停用（而不是逐个关调用点）
     *
     * 本函数是「**上屏后再删**」的唯一实现，有 **9 个调用点**：
     * ```
     * CfhDecide.kt:1011 / :1273   —— 判定当刻删除（HITDEL）
     * CfhWash.kt:1099 / :1101 / :1181 / :1184 / :1211 / :1311 —— 数据层清洗后的补删（PDEL）
     * ```
     *
     * 实测证据（7225 行真机证据）：
     * ```
     * VISDUMP 出现的唯一 id : 51
     * 删除动作涉及的唯一 id : 21
     * 两者交集              : 18   ← 86% 的删除作用在「已上屏」内容上
     * ```
     * 追单条 `id=5189835830187634489`：
     * `PDEL 移除2处` → `HITDEL 移除10处` → `VISFDEL 移除78处` → **仍在屏**
     * ⇒ 删了 90 处、一条没删掉，纯浪费；且反复删+补删+整页刷新
     *   造成「文案昵称残留、覆盖其他视频」。
     *
     * ## 方案 C 的取舍
     *
     * | 保留 | 停用 |
     * |---|---|
     * | L1 白名单（响应返回值，判正常才放行） | **本函数的全部 9 个调用点** |
     * | 数据层 `filterResult`（返回值清洗） | 判定驱动的 `HITDEL` |
     * | 数据层 `filterListArgs` / `filterResponseFields` | 数据层清洗后的补删 `PDEL` |
     * | 全部判定与证据落盘 | — |
     *
     * **语义**：拦截只发生在**内容进列表之前**（L1/返回值）；
     * 一旦进了列表（= 已可能上屏），**不再事后删除**。
     *
     * ## 保留函数体的原因
     *
     * · 调用点有 9 处，逐个删易漏、diff 大；此处 return 0 一处生效
     * · 保留历史实现供日后回溯（若日后要恢复，注释掉 `return 0` 即可）
     * · 调用方拿到的返回值恒为 0 ⇒ 它们的 `if (r > 0)` 日志分支自然静默
     *
     * ## 不适用 / 若日后要恢复
     *
     * 恢复的**前提**是解决「删不掉」：容器（Tangram/Kmp）、返回值快照（H/V/F）、
     * 真源包装类（`l.a`/`h.j`）三条通路都持有独立引用，
     * 不解决它们，恢复删除只会重演「反复删+覆盖其他视频」。
     */
    fun removeFromFragContainers(frag: Any?, targetPid: String): Int {
        // ★ 方案 C：整体停用（用户定稿「上屏了就不该删」）
        return 0

        // ===== 以下为原实现，保留供回溯 =====
        @Suppress("UNREACHABLE_CODE")
        if (frag == null || targetPid.isBlank()) return 0
        var done = 0
        try {
            val seen = java.util.HashSet<Any>()
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(frag, 0))
            seen.add(frag)
            var vis = 0
            // ★★★ 遍历全貌统计（2026-09-24）—— 查「移除一处后屏幕仍可见」。
            //
            //   实测：`VISDEL 就地移除 1 处` 后 **7 毫秒**，
            //   `VISDUMP` 又记录同一条为可见（同一 id）。
            //   7ms 不可能是「快手把它填回来」——?只能说明
            //   **该 QPhoto 被多个容器同时持有**，而我只移除了一处。
            //
            //   本统计回答：一次遍历到底看到几个列表、
            //   其中几个其中几个含目标 id、总共移除了几处。
            //   若「含目标」始终为 1，说明真正的可见来源不在本次遍历范围里，
            //   需要扩大搜索（改从这个数据继续追）。
            var listsSeen = 0
            var listsWithTarget = 0
            // ★★★ 全量遍历（2026-09-24）—— 扩范围 + 记录持有者全貌。
            //
            //   ## 为什么要扩（实测依据）
            //
            //   「不惑电竞」的完整轨迹：
            //     1790258405432  VISDEL-SUM 访问600 列表30 含目标2 移除2 处
            //     1790258405454  VISDEL     移除点 c@b 剩53
            //     1790258406178  VISDUMP    判脏=true 昵称="不惑电竞"  ← 0.7 秒后仍可见
            //
            //   判对了、也删了 2 处，但屏幕还在 ⇒ **它还有别的持有者**，
            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
            //
            //   ## 本版改动
            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
            // 便于判断「删了几处才够 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
            // （实测同一内容存在**不同对象实例**，只 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
            // 但也可能是同一实例挂在多处，那时按引用也能删到 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
            val holdPoints = StringBuilder()
            while (q.isNotEmpty() && vis < 4000) {
                val nd = q.removeFirst()
                val o = nd[0]; val d = nd[1] as Int
                vis++
                if (d > 6) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 4) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v is MutableList<*>) {
                                // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                if (f.name == "o") continue
                                val holder = o.javaClass.name
                                if ((holder.contains("Activity") || holder.contains("Fragment")) &&
                                    f.name.startsWith("m")
                                ) continue
                                // 护栏③：保留至少 1 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                if (v.size <= 1) continue
                                listsSeen++
                                @Suppress("UNCHECKED_CAST")
                                val ml = v as MutableList<Any?>
                                var i = ml.size - 1
                                var hitThis = false
                                while (i >= 0) {
                                    if (ml.size <= 1) break   // 护栏③：边删边查
                                    val e = ml[i]
                                    if (e != null) {
                                        // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                        // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                        // （KmpSlideInformationComponent.e 等）的元素是**包装 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                        // QPhoto 藏在字段———?元素本身 getPhotoId() 拿不 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                        // 单跳 readPhotoId ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                        // 但未找到移除点」。补 findQpInObject 深提后再 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                        val ePid = try { CfhProbe.readPhotoId(e) } catch (_: Throwable) { null }
                                            ?: try { CfhProbe.findQpInObject(e)?.let { CfhProbe.readPhotoId(it) } } catch (_: Throwable) { null }
                                        val match = ePid != null && ePid == targetPid
                                        if (match) {
                                            // ★ 删除前先清展示字段（2026-09-25「文案标签还是乱的」）：
                                            //   同一内容存在多实例，被删的这份与屏上绑定的那份可能不同；
                                            //   但 DefaultSyncable 响应式按对象实例通知 —— 每一份都要清。
                                            //   元素即将移出列表，清字段零风险；命中即清（黑名单成员）。
                                            try {
                                                val delQp = CfhState.qpClassRef?.isInstance(e) ?: false
                                                // [已移除 2026-09-26] scrubShownDirty：功能早已删除，调用点清理
                                                // [已移除 2026-09-26] scrubShownDirty：功能早已删除，调用点清理
                                            } catch (_: Throwable) {}
                                            ml.removeAt(i)
                                            done++
                                            hitThis = true
                                        }
                                    }
                                    i--
                                }
                                // ★★★ CopyOnWriteArrayList 快照语义补偿（2026-09-25 抽出共用）。
                                //
                                //   本处是全项目**最早**实现该补偿的地方，
                                //   现已抽为 `CfhUtil.commitCowWrite` 供 `CfhPurge`
                                //   的两条删除路径共用（那两条此前**没有**补偿，
                                //   而真源 vm.l.a 正是 COW —— 实测「删11条剩1 →
                                //   删20条剩23」即无效删除所致）。
                                //
                                //   语义与原实现逐字一致：仅 COW + 非空时，
                                //   原地写回末位元素以「提交」新数组。见 CfhUtil。
                                if (hitThis) {
                                    try { CfhUtil.commitCowWrite(v) } catch (_: Throwable) {}
                                }
                                if (hitThis) {
                                    listsWithTarget++
                                    // ★★ 记录**每一 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    //   需要知道到底有几个独立容器持有它，
                                    // 才能判断「删几处才够」 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    holdPoints.append(
                                        "\n    [${listsWithTarget}] ${f.name}@${holder.substringAfterLast('.')} " +
                                            "\n    [${listsWithTarget}] ${f.name}@${holder.substringAfterLast('.')} 剩${ml.size} 容器类${v.javaClass.simpleName}"
                                    )
                                    Logger.evidence(
                                        "VISDEL",
                                        "  移除点 ${f.name}@${holder.substringAfterLast('.')} " +
                                            "剩${ml.size}"
                                    )
                                } else {
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // 用于判断「真源是否就是这些列表之一」 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 只在包含目标 id 的遍历轮次里打前几个 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    if (ml.isNotEmpty() && listsSeen <= 40) {
                                        val firstPid0 = try {
                                            ml.firstOrNull()?.let { CfhProbe.readPhotoId(it) }
                                        } catch (_: Throwable) { null }
                                        if (firstPid0 != null) {
                                            Logger.evidence(
                                                "VISDEL-L",
                                                "  列表 ${f.name}@${holder.substringAfterLast('.')} " +
                                                    "size=${ml.size} 首id=${firstPid0}"
                                            )
                                        }
                                    }
                                }
                            } else {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && v !is android.view.View &&
                                    seen.add(v)
                                ) {
                                    q.add(arrayOf(v, d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                }
            }
            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
            //   用于回答「为什么移除了还可见」：
            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
            if (done > 0 || listsWithTarget > 0) {
                Logger.evidence(
                    "VISDEL-SUM",
                    "访问$vis 列表$listsSeen 含目标$listsWithTarget 移除$done 处 " +
                        "id=$targetPid$holdPoints"
                )
            }
        } catch (_: Throwable) {}
        return done
    }

    /**
      * ★★★?恒定真源定位 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
     *
      * ## 实测到的死循 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
     *
     * ```
      * （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
      * retHc=253477834  真源Hc=192816217  同一对象=false ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
      * （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
      * retHc=132269412  真源Hc=192816217  同一对象=false ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
     * ```
     *
      * `真源Hc=192816217` 从头到尾没变 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
     * 这说明：
      * （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
      * · 我们每次删的都是 `H()` 等方法返回的**新副 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
      * · 真源里的脏项**从未被真正删 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
     *
      * （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
     *
      * 我一度以为「`真源Hc` 恒定 = 真源没被碰过」是**已经解决 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
      * （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
     *
     * ## 本函数做什么
     *
      * （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
     * 记录 `System.identityHashCode`，用于和日志里的
      * `真源Hc=192816217` 对上———?确认那个恒定列表到底是哪个字段 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
     *
      * 只读，不修改任何数据 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
     */
    fun locateStableSource(vm: Any?, targetHc: Int) {
        if (vm == null) return
        try {
            val seen = java.util.HashSet<Any>()
            val q = ArrayDeque<Array<Any>>()
            q.add(arrayOf(vm, 0))
            seen.add(vm)
            var vis = 0
            val rows = StringBuilder()
            var found = false
            while (q.isNotEmpty() && vis < 1200) {
                val nd = q.removeFirst()
                val o = nd[0]; val d = nd[1] as Int
                vis++
                if (d > 4) continue
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 4) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v is MutableList<*>) {
                                if (v.size < 5) continue
                                val hc = System.identityHashCode(v)
                                if (hc == targetHc) {
                                    found = true
                                    val firstCap = try {
                                        v.firstOrNull()?.let { CfhUtil.readCaption(it) }
                                    } catch (_: Throwable) { null }
                                    rows.append("\n  ★命中 hc=$hc " +
                                        "${f.name}@${o.javaClass.name.substringAfterLast('.')} " +
                                        "size=${v.size} 首文案=\"" + (firstCap?.take(20) ?: "-") + "\" " +
                                        "列表类=${v.javaClass.name}")
                                }
                            } else {
                                val vn = v.javaClass.name
                                if (!vn.startsWith("java.") && !vn.startsWith("android.") &&
                                    !vn.startsWith("kotlin.") && v !is android.view.View &&
                                    seen.add(v)
                                ) {
                                    q.add(arrayOf(v, d + 1))
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                }
            }
            Logger.evidence(
                "STABLEHC",
                if (found) "找到恒定真源 hc=$targetHc: $rows"
                else "未在 VM 找到 hc=$targetHc（访问$vis 对象）"
            )
        } catch (_: Throwable) {}
    }
    // ★★★ replaceVisibleIfDirty（可见项换条）整体废弃（2026-09-25 用户定稿「就删脏项就行了」）。
    //   换条会制造「视频是净项、文案/昵称/AI 角标还是脏项」的错配 —— 文案 View 订阅
    //   的是旧脏项 QPhoto（DefaultSyncable 响应式），数据引用换了它不跟。
    //   「内容在 Fragment 单值字段上」的钉屏场景改由 scrubShownDirty 清洗脏项的
    //   展示字段（caption/声明清空，文案 View 随响应式流自动刷新为空）—— 只删不换。
    //   չʾ…ֶΣ…caption/………………գ……İ… View …………Ӧʽ………Զ…ˢ……Ϊ…գ…………… ֻɾ……………。
    fun dumpAdapterSelf(adp: Any?) {
        if (adp == null) return
        Logger.safe("dumpAdpSelf") {
            val sb = StringBuilder()
            var c: Class<*>? = adp.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(adp)
                        val desc = if (v is List<*>) "List(size=${v.size})" else v?.javaClass?.simpleName ?: "null"
                        sb.append("[${c.simpleName}]${f.name}:${desc} ")
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            Logger.d("adpSelf ${adp.javaClass.name}: $sb")
            CfhState.adpRef = adp
            var c2: Class<*>? = adp.javaClass
            var lvl2 = 0
            while (c2 != null && c2 != Any::class.java && lvl2 < 4) {
                for (f in c2!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(adp)
                        if (v is MutableList<*> && v.size > 0) {
                            val qp = v[0]?.let { CfhProbe.findQpInObject(it) }
                            val hits = v.filter { it != null && CfhProbe.findQpInObject(it)?.let { q -> CfhDecide.shouldFilterFeed(q) } == true }.size
                            Logger.d("adpSelfList ${f.name} size=${v.size} elem=${v[0]?.javaClass?.name} qpFound=${qp != null} hits=$hits")
                            CfhSwap.fixAdapterSelfAlways(adp)
                            if (f.name == "M" && !CfhState.elemDumped) {
                                CfhState.elemDumped = true
                                val e0 = v[0]
                                if (e0 != null) {
                                    val esb = StringBuilder()
                                    var ec: Class<*>? = e0.javaClass
                                    var elvl = 0
                                    while (ec != null && ec != Any::class.java && elvl < 3) {
                                        for (ef in ec!!.declaredFields) {
                                            if (java.lang.reflect.Modifier.isStatic(ef.modifiers)) continue
                                            try {
                                                ef.isAccessible = true
                                                val ev = ef.get(e0)
                                                val edesc = if (ev is List<*>) "List(${ev.size})" else ev?.javaClass?.simpleName ?: "null"
                                                esb.append("[${ec.simpleName}]${ef.name}:${edesc} ")
                                            } catch (_: Throwable) {}
                                        }
                                        ec = ec.superclass; elvl++
                                    }
                                    Logger.d("adpElem ${e0.javaClass.name}: $esb")
                                    val af = try { e0.javaClass.getDeclaredField("a").apply { isAccessible = true } } catch (_: Throwable) { null }
                                    val av = try { af?.get(e0) } catch (_: Throwable) { null }
                                    if (av != null) {
                                        val asb = StringBuilder()
                                        var ac: Class<*>? = av.javaClass
                                        var alvl = 0
                                        while (ac != null && ac != Any::class.java && alvl < 3) {
                                            for (af2 in ac!!.declaredFields) {
                                                if (java.lang.reflect.Modifier.isStatic(af2.modifiers)) continue
                                                try {
                                                    af2.isAccessible = true
                                                    val av2 = af2.get(av)
                                                    val adesc = if (av2 is List<*>) "List(${av2.size})" else av2?.javaClass?.simpleName ?: "null"
                                                    asb.append("[${ac.simpleName}]${af2.name}:${adesc} ")
                                                } catch (_: Throwable) {}
                                            }
                                            ac = ac.superclass; alvl++
                                        }
                                        val aqp = CfhProbe.findQpInObject(av)
                                        Logger.d("adpElemA ${av.javaClass.name}: $asb qpIn=${aqp != null} qpHit=${aqp?.let { CfhDecide.shouldFilterFeed(it) }}")
                                    }
                                    var mm: Class<*>? = e0.javaClass
                                    var mlvl2 = 0
                                    while (mm != null && mm != Any::class.java && mlvl2 < 3) {
                                        for (mf in mm!!.declaredMethods) {
                                            if (java.lang.reflect.Modifier.isStatic(mf.modifiers)) continue
                                            if (mf.parameterTypes.size <= 2) {
                                                Logger.d("  elem m: ${mf.name}(${mf.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${mf.returnType.simpleName}")
                                            }
                                        }
                                        mm = mm.superclass; mlvl2++
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        }
    }

    fun dumpProvider(obj: Any) {
        Logger.safe("dumpProv") {
            val sb = StringBuilder()
            var c: Class<*>? = obj.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        val desc = if (v is List<*>) "List(size=${v.size})" else v?.javaClass?.simpleName ?: "null"
                        sb.append("${f.name}:${desc} ")
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            Logger.d("provDump ${obj.javaClass.name} fields: $sb")
            var c2: Class<*>? = obj.javaClass
            var lvl2 = 0
            while (c2 != null && c2 != Any::class.java && lvl2 < 3) {
                for (f in c2!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v is MutableList<*> && v.size > 0) {
                            val elem = v[0]
                            val qp = elem?.let { CfhProbe.findQpInObject(it) }
                            Logger.d("provList ${f.name} size=${v.size} elem=${elem?.javaClass?.name} qpFound=${qp != null} qpHit=${qp?.let { CfhDecide.shouldFilterFeed(it) }}")
                            val hits = v.filter { it != null && CfhProbe.findQpInObject(it)?.let { q -> CfhDecide.shouldFilterFeed(q) } == true }.size
                            Logger.d("provList ${f.name} hits=$hits/${v.size}")
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        }
    }

    fun dumpAdapterLists(adp: Any) {
        if (CfhState.adpListDumped) return
        CfhState.adpListDumped = true
        val qpClass = CfhState.qpClassRef
        fun scan(obj: Any, prefix: String, depth: Int, seen: MutableSet<Int>) {
            if (depth > 2) return
            if (!seen.add(System.identityHashCode(obj))) return
            var c: Class<*>? = obj.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v is List<*>) {
                            val first = v.firstOrNull()
                            val isQp = qpClass?.let { q -> first != null && q.isAssignableFrom(first.javaClass) } == true
                            Logger.always("adpList $prefix${c.simpleName}.${f.name}: size=${v.size} elem=${first?.javaClass?.name ?: "null"}${if (isQp) " <== QP" else ""}")
                        } else if (v != null && depth < 2 && !v.javaClass.name.startsWith("java.")) {
                            scan(v, "$prefix${c.simpleName}.${f.name}>", depth + 1, seen)
                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
        scan(adp, "", 0, mutableSetOf())
        CfhState.vmRef?.let { scan(it, "VM>", 0, mutableSetOf()) }
    }




    fun diagFragment(frag: Any) {
        Logger.safe("diagFrag") {
            val qpClass = CfhState.qpClassRef ?: return@safe
            // 注：`vm`（CfhState.vmRef）原先被「替换脏 QPhoto」那段使用；该机制已 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
            // 2026-09-23 移除（见下方说明），故此处不再读取，避免 unused 警告 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
            // 邻页，邻页同样走 onResume——用 localVisibleRect 判定，离屏页视口 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
            // 可见矩形为空直接跳过
            val fv = (frag as? androidx.fragment.app.Fragment)?.view
            if (fv != null) {
                val vr = android.graphics.Rect()
                fv.getLocalVisibleRect(vr)
                if (vr.width() <= 0 || vr.height() <= 0) return@safe
            }
            // 字段级排查：Fragment 持有视频数据的字段（类型 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
            if (!CfhState.fragFieldsDiag) {
                CfhState.fragFieldsDiag = true
                var mc: Class<*>? = frag.javaClass
                var mlvl = 0
                var printed = 0
                while (mc != null && mc != Any::class.java && mlvl < 5) {
                    for (f in mc!!.declaredFields) {
                        val ft = f.type.name
                        if (ft.contains("Photo", true) || ft.contains("QPhoto", true) || ft.contains("Feed", true)) {
                            try {
                                f.isAccessible = true
                                val v = f.get(frag)
                                if (v != null && printed < 8) {
                                    val vcap = try { CfhUtil.readCaption(v) } catch (_: Throwable) { null }
                                    Logger.d("fragF ${f.name} type=$ft cap=${vcap?.take(16) ?: "?"}")
                                    printed++
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    mc = mc.superclass; mlvl++
                }
            }
            if (!CfhState.fragMethodsDiag) {
                CfhState.fragMethodsDiag = true
                var mc: Class<*>? = frag.javaClass
                var mlvl = 0
                while (mc != null && mc != Any::class.java && mlvl < 5) {
                    for (m in mc!!.declaredMethods) {
                        if (m.parameterTypes.isEmpty() && m.returnType == Void.TYPE) {
                            Logger.d("frag void: ${m.name}")
                        }
                    }
                    mc = mc.superclass; mlvl++
                }
            }
            if (!CfhState.feedPagerFound) {
                try {
                    val act = CfhState.tracked
                    val decor = act?.window?.decorView as? ViewGroup
                    if (decor != null) {
                        CfhViewHook.findPager(decor)
                    }
                } catch (_: Throwable) {}
            }
            var c: Class<*>? = frag.javaClass
            var lvl = 0
            var visibleStored = false
            while (c != null && c != Any::class.java && lvl < 5) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(frag) ?: continue
                        if (qpClass.isAssignableFrom(v.javaClass)) {
                            val hit = CfhDecide.shouldFilterFeed(v)
                            val cap = CfhUtil.readCaption(v)
                            CfhState.fragDiagCount++
                            if (CfhState.fragDiagCount <= 5 || CfhState.fragDiagCount % 100 == 0) {
                                Logger.d("frag M #${CfhState.fragDiagCount}: hit=$hit cap=${cap?.take(25)}")
                            }
                            // ★★ 「替换」机制整体移除（2026-09-23，用户决 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // 因此 `Reflect.callMethod(vm, "T0", i)`（用 1 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 从设计上就不可能成功 ——?实测两种调用均返 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //       宽松调用T0(0)=null   精确调用T0(int:0)=null
                            // 后果：`hit=true` 出现 N 次 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 即判脏的 Fragment 从未被换 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 播放器却已切到下一 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            //     「尽量直接删，不要替换。也就刚启动时前面几个直接删可能会有
                            //      问题会闪退（实际上前面几个也没拦截掉），后面的其实可以随便删，
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            //
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // · 本处**不再做替 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // · 脏项统一交由删除链路（CfhPurge/CfhClean ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //     · 「启动窗内前几个」的保护由既有机制承担：
                            // sanitizeList 已有「可见脏项豁免」（MAX_VISIBLE_SKIPS ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            //
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // （下载捕获的数据源，见下 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 这一用途与替换无关，删掉会破坏下载功能 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // 换条≠在屏——resume ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 里的 QPhoto 才是用户眼前这条（替换后的干净项优先）
                            // ★★★?观测窗口修复 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            //
                            // `if (!visibleStored)` 决定 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 被找到的 QPhoto 字段，且 `visibleStored` ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //   局部变———?但真正卡住观测的是下面这一层：
                            // 整个 `diagFragment` 只在 Fragment **首次可见**时被调用 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 之后用户在同一页面内滑动，代码不再进来 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            //   ## 实测后果（决定性）
                            //
                            // `VISDUMP` 记录的时间跨度只 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 1790252930764 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 之后就彻底停了，而进程还在运行 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 主人现场看到的漏拦条 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            //
                            //   ## 修法
                            //
                            // 把「是否已记录」的判据 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 只要当前可见项的 photoId 与上次记录的不同，就重新记录 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 这样每次可见页变化都会留下一条，滑多久都能抓 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            // 去重表用 photoId（而非对象引用）——?同一内容可能以不 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                             val curPid = try { CfhProbe.readPhotoId(v) } catch (_: Throwable) { null }
                            val isNewPage = curPid.isNullOrBlank() ||
                                curPid != CfhState.lastVisDumpedPid
                            // ★★★?单值字段定位（2026-09-24）——?查「删不掉」的最后一种形态 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            //   ## 实测（主人报「历史切片」「锋哥看百态」）
                            //
                            //   两条内容的轨迹完全一致：
                            // 5782724  VISDEL-SUM  含目 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 5782732  HITDEL      ★判定当刻移 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 5866221  VISDEL-SUM  含目 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 5866229  HITDEL      ★判定当刻移 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 5866993  VISDUMP     判脏=true ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 5869683  HITDEL      ★缓存命中移 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 5871367  VISDUMP     判脏=true ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            // 删了 3 轮（3处→4处→1处），每次删 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            //   ## 关键推断
                            //
                            // `VISDUMP` ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            //
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // 所有列表级删除都碰不到 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 这正是「删了又回来」的最终形态 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            //   ## 本探针要回答
                            //
                            // 到底是哪个字段持有它？（字段 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 拿到名字才能判断该走哪种修法 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // · 若是「当前项」类字段 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //       而不是直接改字段
                            // · 若是某个可安全清空的缓存字段 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 不确定就动手 = 可能把页面改坏，所以先只读定位 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            if (curPid != null && CfhState.fragFieldProbeCount < 20) {
                                try {
                                    var fc: Class<*>? = frag.javaClass
                                    var fl = 0
                                    while (fc != null && fc != Any::class.java && fl < 5) {
                                        val fcc: Class<*>? = fc
                                        for (ff in (fcc ?: break).declaredFields) {
                                            try {
                                                ff.isAccessible = true
                                                val fv = ff.get(frag) ?: continue
                                                if (fv === v) {
                                                    CfhState.fragFieldProbeCount++
                                                    Logger.evidence(
                                                        "FRAGFLD",
                                                        "★持有可见项的字段: ${ff.name} " +
                                                            "类型=${ff.type.name.substringAfterLast('.')} " +
                                                            "static=${java.lang.reflect.Modifier.isStatic(ff.modifiers)} " +
                                                            "在类=${frag.javaClass.name.substringAfterLast('.')} " +
                                                            "id=$curPid"
                                                    )
                                                    // 【已撤除 2026-09-26】曾在此处对单值字段做白名单+置空。
                                                    //
                                                    // 撤除理由（用户纠正）：
                                                    //   `NasaPhotoDetailFragment.M` 是**渲染层**字段 ——
                                                    //   在那置空等于「上屏后再删」，正是用户明确否掉的方案：
                                                    //   > 「渲染层不应该还有拦截机制，视频上屏了也应该不会删除」
                                                    //
                                                    // 本处恢复为**只读探针**（记录字段名，不修改任何状态）。
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                        fc = fcc?.superclass; fl++
                                    }
                                } catch (_: Throwable) {}
                            }
                            // ★★★?短剧数据源定位（2026-09-24）——?查「B 形态」短剧挂在哪 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            //   主人报的 11 条短剧全部零记录，而主人确认其形态是
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // 与我在扫的「普通全屏视频」是**不同的数据结 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            //   因此需要在 Fragment 上下文里做一次定向普查：
                            // 找出所有带短剧特征字段的对象、以及所有持 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            //
                            // 每进程限 3 次（遍历成本高于普通探针） ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            if (CfhState.dramaSrcSurveyCount < 3) {
                                CfhState.dramaSrcSurveyCount++
                                try { CfhWash.surveyDramaSource(frag) } catch (_: Throwable) {}
                            }
                            // ★★★?可见页强制移除（2026-09-24 收口）——?
                            // 不受 `visibleStored` / `isNewPage` / 限次任何门控 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            //   ## 实测缺口（决定性）
                            //
                            //   「晶晶剧场」：
                            //     0743391  VISDEL-SUM 访问1956 列表72  含目标2 移除2 处
                            // 0809911  VISDEL-SUM 访问3181 列表100 含目 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 0809942  VISDUMP    判脏=true ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            //   31 毫秒不可能是「快手重新填充」——?只能是：
                            // `VISDUMP` 读到的那 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // `VISDEL-SUM` 遍历的那 3181 个对象里 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // 前者从 `frag` 取值，后者从 VM ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            //
                            // ## 为什么之前的移除不生 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //
                            //   移除代码写在 `if (!visibleStored)` 内，
                            // 而本期里 `visibleStored` ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // `isNewPage=false` ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            //
                            //   ## 本版做法
                            //
                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                            // 可见项，就地———?不等换页、不看限次 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                            //   （日志仍限次，避免刷屏；删除动作不限。）
                            try {
                                val pidForDel: String? = curPid
                                val needDel = pidForDel != null && pidForDel.isNotBlank() &&
                                    CfhState.dirtyPhotoMap.containsKey(pidForDel)
                                if (needDel && pidForDel != null) {
                                    // ★★★ 渲染层删除**已停用**（2026-09-26 用户定稿）。
                                    //
                                    // ## 用户原话
                                    //
                                    // > 「第一渲染层不应该还有拦截机制，视频上屏了也应该不会删除」
                                    //
                                    // ## 为什么关掉（实测三证据）
                                    //
                                    // ### ① 违反项目自己的设计原则
                                    //
                                    // README 明写：
                                    // > 渲染层拦截不安全 …… 拦截必须收敛到**数据层清洗**，
                                    // > 渲染层只做零干扰的**可见性观察**
                                    //
                                    // 而本文件（`CfhDiag`）里有 4 个**写动作**
                                    // （`:765` `:869` `:885` `:950`），全是删除 ——
                                    // **违反了自己的原则**，且因文件名像"诊断"而长期未被发现。
                                    //
                                    // ### ② 它根本删不掉（实测）
                                    //
                                    // 真机统计（7225 行证据）：
                                    // ```
                                    // VISDUMP 出现的唯一 id : 51
                                    // 删除动作涉及的唯一 id : 21
                                    // 两者交集              : 18  ← 86% 的删除作用在「已上屏」内容上
                                    // ```
                                    // 追单条「田姐(众鑫宠物店)」id=5189835830187634489：
                                    // `PDEL 移除2处` → `HITDEL 移除10处` → `VISFDEL 移除78处`
                                    // → **仍在屏**。删了 90 处，一条没删掉 —— 纯浪费。
                                    //
                                    // ### ③ 它正是「覆盖其他视频」的元凶
                                    //
                                    // 反复删除已上屏内容 + 补删 + 整页刷新，
                                    // 造成列表内容与渲染引用不一致 ⇒ 用户报
                                    // 「文案昵称还在并覆盖其他视频」。
                                    //
                                    // ## 处置
                                    //
                                    // 本调用点（`VISFDEL` 可见页强制移除）**改为只观察不删除**。
                                    // 同批的另外 3 处（`:869` `:885` `:950`）见各自位置的说明。
                                    //
                                    // **保留**：判脏判定、黑名单登记、证据落盘 ——
                                    //   只是**不再执行删除动作**。数据层清洗链路不受影响。
                                    val rm = 0
                                    // 原删除动作（保留为注释以便回溯）：
                                    // val rm = removeFromFragContainers(frag, pidForDel)
                                    if (rm > 0 && CfhState.visForceDelCount < 60) {
                                        CfhState.visForceDelCount++
                                        Logger.evidence(
                                            "VISFDEL",
                                            "★可见页强制移除 $rm 处 id=$pidForDel " +
                                                "cap=\"${CfhUtil.readCaption(v)?.take(20) ?: "-"}\""
                                        )
                                    }
                                    // ★★ 脏项展示字段清洗（2026-09-25 用户定稿「就删脏项就行了」）：
                                    //   不换条（换条制造文案/角标与视频错配）。
                                    //   对**每一轮**可见页脏项（无论 rm 是否为 0）都清洗其
                                    //   展示字段：caption 置空 + 声明/AI 角标清空。
                                    //   依据（主人报「淘金哥」刷到好几个）：渲染层的视频画面由
                                    //   播放器持有的媒体 URL 驱动，与 QPhoto 对象解耦，数据层
                                    //   删除删不掉画面；但文案 View / 角标 View 订阅的是这个
                                    //   脏项 QPhoto（DefaultSyncable 响应式）—— 清空字段后
                                    //   View 自动刷新为空，广告文案与「AI 生成」标立即从屏上消失。
                                    //   宿主重灌脏项 → 下一轮再清洗（黑名单成员秒判）。
                                    // [已移除 2026-09-26] scrubShownDirty：功能早已删除，调用点清理
                                }
                            } catch (_: Throwable) {}
                            if (!visibleStored) {
                                visibleStored = true
                                try {
                                    val ph = f.get(frag) ?: v
                                    CfhState.visiblePhotoRef = java.lang.ref.WeakReference(ph)
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    CfhCapture.ringPush(ph, frag)
                                    Logger.always("DL vis: cap=${CfhUtil.readCaption(v)?.take(24)} user=${CfhUtil.readUserName(v, Reflect.readAny(v, "mEntity") ?: v).take(16)}")
                                    // ★★ 可见页补判定 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    // 真机实测（用户报「喵小喵短剧」） ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // `DL vis: cap=年轻人摆摊 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // 说明它在屏幕上，却从未进入判定链路 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    // 根因：本函数（diagFragment）此 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // 从不调用 shouldFilterFeed** ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // （详情页滑动、直进详情、某些预加载路径），全都从这里漏掉 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    //   修法：在这里补一次判———?它已经是「被证明稳定」的
                                    // 可见页来源（上方注释自己写着），作为**判定兜底入口**正合适 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    // ★★ 2026-09-24 补充：判定为脏后**必须触发删除** ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //   上版只判不删，实测（用户报「请让衣服回归它本来的价值用途」）
                                    //   出现这样的结果：
                                    // `VISJUDGE 可见页判 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // ——?判定成功，但**没有任何 DEL 记录**，屏幕上仍可见 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    //
                                    //   现补：判脏即把该 QPhoto 交给既有删除链路处理
                                    //   （走 CfhPurge/CfhWash 的真源清洗，不在此处直接改列———?
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    try {
                                        // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                        //   原实现读全局 lastHitReason，在返回 false 时会拿到
                                        // 上一次的判据，使日志与决策都张冠李戴 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                        val (visDirty, visReason) = try { CfhDecide.judgeFeed(v) }
                                            catch (_: Throwable) { false to null }
                                        if (Prefs.bool(Prefs.K_FLT_AI, false) && visDirty) {
                                            Logger.evidence(
                                                "VISJUDGE",
                                                "可见页判脏→触发删除 cap=\"${CfhUtil.readCaption(v)?.take(20) ?: "-"}\" " +
                                                    "判据=${visReason ?: "?"}"
                                            )
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            //
                                            //   ## 为什么原来的做法无效（实测铁证）
                                            //
                                            // 原实现判脏后只做一件事 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            // `CfhWash.filterVmLists(vmRef)`  ——?去清 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            //
                                            // 实测（主人报「泡泡追剧」） ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            // 17:56:05.021  VISJUDGE 判脏→触发删 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            //     17:56:05.027  VISDUMP  判脏=true 昵称="泡泡追剧🫧"
                                            //                  声明="疑似含AI生成内容"
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            //
                                            //   ## 根因
                                            //
                                            //   可见页 QPhoto 是从 **Fragment 字段**取到的，
                                            // 而清洗只遍历 **VM 可达的列 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // 清洗多少遍都碰不到它 ——?删除动作**打空 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            //
                                            //   ## 修法
                                            //
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // 兼容同一内容的不同对象实例） ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            // VM 清洗照旧保留（覆 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            //
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            //       （改单值字段等于把 Fragment 状态改坏）
                                            // · 保留至少 1 项，防止列表被清空导致崩 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            try {
                                                val targetPid = try { CfhProbe.readPhotoId(v) } catch (_: Throwable) { null }
                                                if (!targetPid.isNullOrBlank()) {
                                                    // ★★ 无条件执行（2026-09-26 边界重画）。
                                                    //
                                                    //   本处曾受 `layerRenderDel` 控制，实测后果：
                                                    //   用户报「奶狗不甜」（id=5229805277470590911，
                                                    //   声明「作者声明：含AI生成内容」）——
                                                    //   `VISJUDGE` 触发删除，但 `removed` 恒为 0
                                                    //   ⇒ 无 VISDEL ⇒ **内容上屏**。
                                                    //   表现为「判定正确却零删除记录」。
                                                    //
                                                    //   ⇒ 这是拦截路径，**不是可关的旁路**。
                                                    // ★★★ 渲染层删除**已停用**（2026-09-26 用户定稿
                                                    //   「渲染层不应该还有拦截机制」）。
                                                    //   完整理由见 `VISFDEL` 处（本文件 :753 附近）的三条实测证据。
                                                    //   此处只保留「判定 + 落盘」，不再执行删除。
                                                    val removed = 0
                                                    // 原：val removed = CfhDiag.removeFromFragContainers(frag, targetPid)
                                                    if (removed > 0) {
                                                        Logger.evidence(
                                                            "VISDEL",
                                                            "★就地移除 $removed 处 " +
                                                                "id=$targetPid " +
                                                                "cap=\"${CfhUtil.readCaption(v)?.take(20) ?: "-"}\""
                                                        )
                                                    }
                                                }
                                            } catch (_: Throwable) {}
                                            // ★★★ 已停用（2026-09-26 方案 C 用户定稿）。
                                            //
                                            //   本处原为「可见页判脏 → 触发数据层清洗」。
                                            //   方案 C 的原则：**一旦进了列表（已可能上屏），不再事后删除**。
                                            //   本处正是「看见屏上有脏项才去清」—— 属于事后补救，
                                            //   与用户要求「上屏了就不该删」冲突，故停用。
                                            //
                                            //   数据层清洗本身（`CfhWash.filterVmLists`）**没有被删** ——
                                            //   它的其它调用点（事件触发的那些）继续工作，
                                            //   只是**不再由可见性观察来触发**。
                                        }
                                    } catch (_: Throwable) {}
                                    // ★★★?可见页全量指 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 查「屏幕上明明是漏拦内容，却没有任何判定记录」 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    //   ## 为什么必须做
                                    //
                                    // 用户现场指着屏幕报漏拦（例：「小鱼带你看世界」） ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // 一条相关记录都找不 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // 也没进过任何判定入口 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    // 本函数是「已被证明稳定」的可见页来 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （`DL vis:` 每次都能打出当前条） ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    //     · 昵称 / 文案 / photoId
                                    // · 全部 `mAi*` 字段实测 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //     · 声明三层读取结果
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    //
                                    // 这样无论它是漏拦还是正常内容 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    if (isNewPage && CfhState.visDumpCount < 4000) {
                                        try {
                                            CfhState.visDumpCount++
                                            CfhState.lastVisDumpedPid = curPid
                                            val entV = Reflect.readAny(v, "mEntity") ?: v
                                            val pmV = Reflect.readAny(entV, "mPhotoMeta")
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // 后者读全局 `lastHitReason` 会拿 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            // 的判据（残留），导致「判 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            // 这类自相矛盾的记录。judgeFeed 返回本次配对 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            val (isDirty, hitR) = try { CfhDecide.judgeFeed(v) }
                                                catch (_: Throwable) { false to null }
                                            // ★★★?记录路径也执行移除（2026-09-24）——?
                                            // 修「VISDUMP 看到 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            //
                                            //   ## 实测（主人报「独木桥没有绳索没有机关」）
                                            //
                                            // 1790259506058  VISDEL-SUM 含目标2 移除2 处 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            //     1790259577633  VISDUMP    判脏=true 昵称="旭日东升Pro"
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            //
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // · VISDUMP ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            //
                                            //   ## 修法
                                            //
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            // 护栏逻辑，不必等另一个触发源 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            //
                                            // 幂等安全：`removeFromFragContainers` ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                            // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                            if (isDirty && curPid != null && CfhState.visDumpDelCount < 2000) {
                                                try {
                                                    // ★★★ 渲染层删除**已停用**（2026-09-26 用户定稿
                                                    //   「渲染层不应该还有拦截机制」）。
                                                    //   完整理由见本文件 `VISFDEL` 处（:753 附近）的三条实测证据。
                                                    val rm = 0
                                                    // 原：val rm = removeFromFragContainers(frag, curPid)
                                                    if (rm > 0) {
                                                        CfhState.visDumpDelCount++
                                                        Logger.evidence(
                                                            "VISDEL",
                                                            "★记录路径移除 $rm 处 id=$curPid " +
                                                                "cap=\"${CfhUtil.readCaption(v)?.take(20) ?: "-"}\""
                                                        )
                                                    }
                                                } catch (_: Throwable) {}
                                            }
                                            Logger.evidence(
                                                "VISDUMP",
                                                "判脏=$isDirty 判据=${hitR ?: "-"} " +
                                                    "昵称=\"${CfhUtil.readUserName(v, entV).take(16)}\" " +
                                                    "文案=\"${CfhUtil.readCaption(v)?.take(30) ?: "-"}\" " +
                                                    "id=${CfhProbe.readPhotoId(v) ?: "?"} " +
                                                    "声明=\"${CfhUtil.readDisclaimer(v, entV, pmV)?.take(24) ?: "null"}\" " +
                                                    "styleId=${Reflect.readLong(pmV, "mAiCutPhotoStyleId")} " +
                                                    "aiErr=${Reflect.readBool(pmV, "mAiAnalysisError")} " +
                                                    "subtitle=${Reflect.readAny(pmV, "mAiSubtitleInfo") != null} " +
                                                    "aiRecommend=${Reflect.readAny(pmV, "mAiRecommendMsgList") != null} " +
                                                    "aiFp=${CfhUtil.aiFingerprint(v, 3) ?: "无"}"
                                            )
                                        } catch (_: Throwable) {}
                                    }
                                    // ★★★?漏拦实例定向巡检 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 用户现场指着屏幕报的具体条目，必须能抓到字段全貌 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    // ## 为什么单开这一 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // 于是现场永远抓不到。改为限 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 确保长时间滚动后仍能记录 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    //   另外把「判定结果为空」也保留：`判据=-` 通常意味着
                                    //   判定走了**签名缓存**（`decideBySig` 命中即返回，
                                    // 不会调用 `hit()` ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //   这是正常现象，但必须在日志里可区分，
                                    // 否则会被误读成「判据丢了」 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // ★★★?用户指点条目的即时取证（2026-09-24）——?
                                    // 主人现场报的漏拦实例（「满堂嘲讽，执手良缘 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 「小鱼带你看世界」），必须当场抓到全字段 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    //   ## 为什么必须按「关键词」而不是按账号
                                    //
                                    // 上版只匹配昵称，而主人报的是**文案**里的词 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 两者都匹配才能覆盖现场报障的两种说法 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    // ## 为什么限次这么高 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // 60 次的成本是每条一行日志，可忽略 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //   ## 已登记的点名条目（主人现场报的漏拦）
                                    //
                                    // 2026-09-24 第一次：「小鱼带你看世界 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 2026-09-24 第二次：「满堂嘲讽，执手良缘 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 2026-09-24 第三次：「鼠鼠巴啦啦 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    //
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    //     否则这些词会被长期当作观测目标，
                                    // 既无意义又会把日志带偏 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    val capNow = CfhUtil.readCaption(v).orEmpty()
                                    val userNow = CfhUtil.readUserName(v, Reflect.readAny(v, "mEntity") ?: v)
                                    val namedHit = capNow.contains("满堂嘲讽") ||
                                        capNow.contains("执手良缘") ||
                                        capNow.contains("鼠鼠巴啦啦") ||
                                        userNow.contains("小鱼带你看世界") ||
                                        userNow.contains("鼠鼠巴啦啦") ||
                                        userNow.contains("满堂嘲讽")
                                    if (namedHit && CfhState.namedDumpCount < 60) {
                                        try {
                                            CfhState.namedDumpCount++
                                            val entN2 = Reflect.readAny(v, "mEntity") ?: v
                                            val (dN2, rN2) = try { CfhDecide.judgeFeed(v) }
                                                catch (_: Throwable) { false to null }
                                            val unN2 = CfhUtil.readUserName(v, entN2)
                                            Logger.evidence(
                                                "NAMED",
                                                "=== 点名条目命中 昵称=\"${unN2.take(20)}\" " +
                                                    "判脏=$dN2 判据=${rN2 ?: "-"} ==="
                                            )
                                            Logger.evidence("NAMED", "文案=\"${capNow.take(40)}\"")
                                            Logger.evidence("NAMED", "ent=${entN2.javaClass.name}")
                                            Logger.evidence("NAMED", CfhUtil.dumpAllFields(entN2))
                                            val pmN2 = Reflect.readAny(entN2, "mPhotoMeta")
                                            if (pmN2 != null) {
                                                Logger.evidence("NAMED", "pm=${pmN2.javaClass.name}")
                                                Logger.evidence("NAMED", CfhUtil.dumpAllFields(pmN2))
                                            }
                                            val cmN2 = Reflect.readAny(entN2, "mCommonMeta")
                                            if (cmN2 != null) {
                                                Logger.evidence("NAMED", "cm=${cmN2.javaClass.name}")
                                                Logger.evidence("NAMED", CfhUtil.dumpAllFields(cmN2))
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                    // （注：此段注释因编码事故丢失，见 FIX_PROGRESS.md）
                                    // 该现象重启后不复现（偶发/累积相关），故由模块**持续比对** ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 数据层当前条 vs 屏上 CaptionTextView 实际文字 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    // 不一致时留证（只在异常时输出，正常零噪声）。只读 ……（注：此段注释因编码事故部分丢失，完整记录见 FIX_PROGRESS.md）
                                    try { CaptionProbe.check(CfhUtil.readCaption(v)) } catch (_: Throwable) {}
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
    }

}
