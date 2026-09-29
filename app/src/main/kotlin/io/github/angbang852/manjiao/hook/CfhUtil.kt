package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 拆分第一步（2026-09 S3）：无状态纯函数外移，
// 缩小主文件；调用点由 CfhUtil.xxx 指向
object CfhUtil {
    fun hookKey(c: Class<*>): String = c.name + "@" + System.identityHashCode(c.classLoader)

    fun safeNextLong(o: Any?, name: String): Long {
        return try { o?.let { Reflect.readLong(it, name) } ?: -1L } catch (_: Throwable) { -1L }
    }

    fun readUserName(qp: Any, ent: Any): String {
        try {
            val cm = Reflect.readAny(ent, "mPhotoMeta")
            cm?.let { c ->
                Reflect.readString(c, "mUserName")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val u = Reflect.readAny(ent, "mUser")
            u?.let { obj ->
                Reflect.readString(obj, "mName")?.takeIf { it.isNotBlank() }?.let { return it }
                Reflect.readString(obj, "mUserName")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val uq = Reflect.readAny(qp, "mUser")
            uq?.let { obj ->
                Reflect.readString(obj, "mName")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            Reflect.readString(qp, "mUserName")?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Throwable) {}
        return ""
    }

    /**
     * ★★★ v13.33 读取作者唯一 id（2026-09-30 用户「视频又开始循环」根治）：
     * 用户看到的循环 = **同一作者刷屏**（REPOOL 实证补位 6 条全是「肛肠科张浩医」）
     * —— 首页推荐流给同一批头部作者大量推流，RENDERFEED 全收进池，
     * 池被少数作者占满 ⇒ 补位全是一个人的视频 ⇒ 用户滑几屏就重复。
     * 读作者 id（mUserKey / mUserId / mUserIdStr），供入池按作者去重 +
     * 补位按作者分散使用。读不到时回退昵称（至少能按名字分散）。
     */
    fun readUserKey(qp: Any, ent: Any): String {
        try {
            val u = Reflect.readAny(ent, "mUser")
            u?.let { obj ->
                Reflect.readString(obj, "mUserKey")?.takeIf { it.isNotBlank() }?.let { return it }
                Reflect.readString(obj, "mUserIdStr")?.takeIf { it.isNotBlank() }?.let { return it }
                val id = try { Reflect.readLong(obj, "mUserId") } catch (_: Throwable) { -1L }
                if (id > 0) return "u$id"
            }
            val uq = Reflect.readAny(qp, "mUser")
            uq?.let { obj ->
                Reflect.readString(obj, "mUserKey")?.takeIf { it.isNotBlank() }?.let { return it }
                val id = try { Reflect.readLong(obj, "mUserId") } catch (_: Throwable) { -1L }
                if (id > 0) return "u$id"
            }
            val cm = Reflect.readAny(ent, "mPhotoMeta")
            cm?.let { c ->
                val id = try { Reflect.readLong(c, "mUserId") } catch (_: Throwable) { -1L }
                if (id > 0) return "u$id"
            }
        } catch (_: Throwable) {}
        // 兜底：昵称（至少能按名字分散，避免同作者刷屏）
        return try { readUserName(qp, ent) } catch (_: Throwable) { "" }
    }

    fun dramaShellReal(ent: Any): Boolean {
        try {
            val serial = Reflect.readAny(ent, "mStandardSerialMeta")
            if (serial != null) {
                if (safeNextLong(serial, "mSerialId") > 0) return true
                if (safeNextLong(serial, "mDramaId") > 0) return true
                val t = Reflect.readString(serial, "mTitle")
                if (t != null && t.isNotBlank()) return true
                val dm = Reflect.readAny(serial, "dataMap")
                if (dm is Map<*, *> && dm.isNotEmpty()) return true
                // ★ 嵌套 serial 字段（mStandardSerialInfo / mStandardSerialFromStandard）**刻意不查**：
                // 2026-09-21 实测样本 `第1集｜当学校空降了个新主任` 的违规信号确实藏在这两个
                // 嵌套字段里，但本函数历史上有过「serial 非空即拦」导致美食/工业美学/央视新闻
                // 批量误伤的记录（见 CfhDecide 剧集规则处的注释）。该样本已由 CfhDecide 里
                // 「文案以 第N集/话 开头」的精确规则覆盖，无需在此放松判据。
            }
            val column = Reflect.readAny(ent, "mColumnMeta")
            if (column != null) {
                if (safeNextLong(column, "mColumnId") > 0) return true
                val t = Reflect.readString(column, "mColumnTitle")
                if (t != null && t.isNotBlank()) return true
            }
            val adNovel = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovel != null) {
                if (safeNextLong(adNovel, "mNovelId") > 0) return true
                val t = Reflect.readString(adNovel, "mTitle")
                if (t != null && t.isNotBlank()) return true
            }
        } catch (_: Throwable) {}
        return false
    }

    fun isBgMutationSafe(list: Any): Boolean {
        val cn = list.javaClass.name
        return cn.contains("CopyOnWriteArrayList") || cn.contains("Synchronized")
    }

    /**
     * ★★★ `CopyOnWriteArrayList` 删除后的「快照提交」补偿（2026-09-25 抽出共用）。
     *
     * ## 解决什么问题
     *
     * `CopyOnWriteArrayList` 的语义是：**每次写操作复制整个底层数组**，
     * 而**已开始的迭代/读取继续用旧数组快照**。
     *
     * 所以对它做 `removeAt()` / `removeAll()` **确实会成功**（列表本身变了），
     * 但**别处已经拿到的旧快照仍含那些元素** —— 宿主拿旧快照继续渲染/播放，
     * 屏幕上看不出任何变化。模块下次读到的还是同一份内容（hc 恒定），
     * 于是形成「删了又回来」的死循环。
     *
     * ## 补偿做法
     *
     * 删除成功后，对末位元素做一次**原地写回**（`set(last, last)`）——
     * 内容不变，但 `set` 同样会复制底层数组并替换内部引用，
     * 相当于「提交」这次修改，让后续读取拿到新快照。
     *
     * ## 实测依据
     *
     * - `CfhDiag.kt` 的 `removeFromFragContainers` 早已实现该补偿（原 `:192-203`），
     *   本次将其抽为本函数共用。
     * - 但 `CfhPurge.sanitizeList`（`deep:l.a` / `ret` / `fla` 路径）
     *   **从未有过补偿** —— 而真源 `vm.l.a` 实测确认是 COW：
     *   ```
     *   ★命中 hc=91926624 a@u size=5 列表类=java.util.concurrent.CopyOnWriteArrayList
     *   ```
     *   实测后果：同一批 AI 声明内容「删 11 条剩 1 → 删 20 条剩 23」，
     *   删除量递增而剩余量增得更快 —— 一半操作是**无效删除**。
     *
     * ## 幂等与安全
     *
     * - **幂等**：不改内容、只提交引用，重复调用无副作用
     * - **空表保护**：取不到下标即跳过（必须保留，否则 `size-1 = -1` 越界）
     * - **必须放在删除完成之后**：补偿本身会触发一次写
     * - **异常全吞**：补偿失败不得影响已完成的删除
     * - **成本**：一次数组复制。COW 列表通常很小（`l.a` 实测 size 5–6），
     *   但**大列表需评估** —— 调用方应自知列表规模
     *
     * ## 不适用
     *
     * - 非 `CopyOnWriteArrayList`：无快照语义，直接返回（零成本）
     * - 空列表：无末位可写，跳过
     * - **不解决「有人持续灌入」** —— 本函数只保证「删掉的那份对后续读取不可见」，
     *   若宿主仍在往列表里塞新副本，仍需在灌入口另做拦截
     *
     * @param list 刚执行过删除的列表（调用方保证删除已完成）
     * @return true 表示执行了补偿写入；false 表示无需补偿（非 COW / 空表 / 异常）
     */
    fun commitCowWrite(list: Any?): Boolean {
        if (list == null) return false
        return try {
            val cn = list.javaClass.name
            if (!cn.contains("CopyOnWriteArrayList")) return false
            if (list !is MutableList<*>) return false
            if (list.isEmpty()) return false
            @Suppress("UNCHECKED_CAST")
            val mw = list as MutableList<Any?>
            val last = mw.size - 1
            if (last < 0) return false
            // 原地写回末位元素：内容不变，只「提交」新数组
            mw[last] = mw[last]
            true
        } catch (_: Throwable) {
            // 补偿失败不得影响已完成的删除
            false
        }
    }

    fun readCaption(qp: Any?): String? {
        if (qp == null) return null
        val ent = Reflect.readAny(qp, "mEntity") ?: return null
        val cm = Reflect.readAny(ent, "mCommonMeta") ?: return null
        return Reflect.readString(cm, "mCaption")
    }

    fun aiDisclaimerContent(pm: Any?): String? {
        if (pm == null) return null
        val dis = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null } ?: return null
        val c = try { Reflect.readAny(dis, "content") as? String } catch (_: Throwable) { null }
        // ★★★ v13.23 探针（50388 误拦诊断）：mDisclaimergeMessageV2.content
        //   在 50388 上疑似对所有内容填了「疑似含AI生成内容」默认值 ⇒ 全拦。
        //   打出实际值确认（含对象存在但 content 为空的情况）。
        if (io.github.angbang852.manjiao.hook.CfhState.v2ProbeLog < 40) {
            io.github.angbang852.manjiao.hook.CfhState.v2ProbeLog++
            val cls = dis.javaClass.name
            val cap2 = try {
                val ent2 = Reflect.readAny(pm, "mEntity") ?: pm
                val cm2 = Reflect.readAny(ent2, "mCommonMeta")
                Reflect.readString(cm2, "mCaption")
            } catch (_: Throwable) { null }
            io.github.angbang852.manjiao.util.Logger.evidence(
                "V2PROBE",
                "★V2字段 存在=${dis != null} content=\"${c?.take(20) ?: "空"}\" cls=$cls cap=\"${cap2?.take(14) ?: "-"}\""
            )
        }
        if (c.isNullOrBlank()) return null
        // ★ 实证（probe 19:35 冷启，QDBG 打印声明原文）：同一字段承载两类声明，必须区分——
        //   AI 类 ：「该内容属于AI生成」「疑似含AI生成内容」「作者声明：含AI生成内容」
        //   非AI类：「作者声明：含虚构演绎内容，仅供娱乐」（真人配音/剧情演绎，不得误伤）
        // 因此按「对象存在即脏」会误杀虚构演绎项（实测搞笑配音、光合计划被误拦），
        // 必须回到文字语义判别，并把 AI 判定收敛到 aiGen 关键词族。
        if (AI_DISCLAIMER_REGEX.containsMatchIn(c)) return c
        return null
    }

    /** AI 声明文字判别：覆盖「AI生成/制作/创作/合成/AIGC/人工智能/疑似含AI」等官方措辞变体。
     * ★★★ v13.24（2026-09-29）：「疑似含AI」保留在正则里 —— 命中后由
     *   判定处 `ai:suspect` 独立开关控制（flt_ai_suspect），不再是硬拦。
     *   「确定」措辞（含AI生成/属于AI/AIGC 等）仍无条件拦。 */
    private val AI_DISCLAIMER_REGEX = Regex(
        "AI生成|AI制作|AI创作|AI合成|疑似含AI|含AI生成|属于AI|AIGC|人工智能|由AI",
        RegexOption.IGNORE_CASE
    )

    /** 声明文字是否属 AI 类（供 quick/deep 两条决策路径共用，避免两处口径漂移）。
     * ★★★ v13.24（2026-09-29）：「疑似含AI」由独立开关 flt_ai_suspect 控制 ——
     *   命中疑似措辞时，开关开=true、关=false；「确定」措辞恒 true。 */
    fun isAiDisclaimerText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (!AI_DISCLAIMER_REGEX.containsMatchIn(text)) return false
        // 疑似措辞 → 受开关控制
        if (text.contains("疑似")) {
            // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：与 CfhDecide.kt:711 及两个 UI 行定义口径统一。
            //   仅当 flt_ai 打开时本函数才被调用，故干净安装下无实际影响。
            return io.github.angbang852.manjiao.data.Prefs.bool(
                io.github.angbang852.manjiao.data.Prefs.K_FLT_AI_SUSPECT, false
            )
        }
        return true
    }

    /**
     * ★★ AI 内容指纹普查（2026-09-24）—— 查「AI 视频漏拦」的真凭据。
     *
     * ## 动机
     *
     * 用户反复报漏拦（「绷不住小姐」「元气成长计划」「三角洲行动」
     * 「喵小喵短剧」「一针绣清欢」…），但我在清洗可见的列表里
     * dump 到的全是**正常内容**（`王者荣耀`、`花150万包下深山水库`），
     * 判「否」全部正确 —— 即「漏」的条目**根本没进清洗视野**。
     *
     * 与其继续猜哪个列表承载它，不如**反过来做**：
     * 对每个被遍历到的元素，检查它身上是否带有任何「AI 相关指纹」
     * （字段名含 Ai / 值含 AI 声明文字 / 类名含 Ai），
     * 命中就整条 dump。这样只要漏拦条目**曾经**进过遍历路径，
     * 就一定会被抓到并留下完整字段快照。
     *
     * ## 返回值
     *   命中指纹的人类可读描述；无指纹返回 null（调用方据此决定是否 dump）
     */
    fun aiFingerprint(o: Any?, depth: Int = 3): String? {
        if (o == null || depth <= 0) return null
        try {
            var c: Class<*>? = o.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                val cc: Class<*>? = c
                for (f in (cc ?: break).declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(o) ?: continue
                        // ① 字段名含 Ai 且为真值
                        if (f.name.contains("Ai", false) || f.name.contains("AIGC", false)) {
                            val s = v.toString()
                            if (v is Boolean && v) return "字段${f.name}=true"
                            if (v is Number && (v as Number).toInt() != 0) return "字段${f.name}=$s"
                            if (v is String && s.isNotBlank() && s != "false" && s != "0" && !s.equals("null")) {
                                return "字段${f.name}=\"${s.take(24)}\""
                            }
                        }
                        // ② 字符串值含 AI 声明文字
                        if (v is String && isAiDisclaimerText(v)) {
                            return "字段${f.name}=\"${v.take(24)}\""
                        }
                        // ③ 内层对象递归（限深，防爆）
                        if (depth > 1 && v !is String && v !is Number && v !is Boolean &&
                            !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("android.")
                        ) {
                            val sub = aiFingerprint(v, depth - 1)
                            if (sub != null) return "${f.name}→$sub"
                        }
                    } catch (_: Throwable) {}
                }
                c = cc?.superclass; lvl++
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * 取一条内容的**声明文字** —— 严格对齐快手的三层来源（2026-09-24）。
     *
     * ## 为什么必须三层都读（真机实测，非推测）
     *
     * 逆向 `QPhoto.getDisclaimerMessage()`：
     * ```java
     * public String getDisclaimerMessage() {
     *     if (w.q0(this)) return getString(2131832000);   // ① 特殊类型 → 硬编码资源串
     *     DisclaimergeMessage d = w.L(this.mEntity);       // ② 实体层
     *     if (d != null && !TextUtils.x(d.getContent())) return d.getContent();
     *     return e.h(mEntity, PhotoMeta.class, o -> o.mDisclaimerMessage); // ③ PhotoMeta 字段
     * }
     * ```
     *
     * **模块此前的错误**：只读 `PhotoMeta.mDisclaimergeMessageV2` 这一个字段。
     * 真机实测该字段**恒为 null** ——
     * `pmDis{mDisclaimerMessage=null; mDisclaimergeMessageV2=null; ...}`，
     * 而快手实际走的是 ② 实体层 → 于是「有 AI 声明却漏拦」。
     *
     * 本函数按 ①②③ 顺序取第一个非空值，与快手自身口径一致。
     *
     * @param qp  QPhoto（或实体）对象
     * @param ent 实体层对象（`qp.mEntity`），可为 null
     * @param pm  PhotoMeta（`ent.mPhotoMeta`），可为 null
     * @return 声明文字；三层都无则 null
     */
    fun readDisclaimer(qp: Any?, ent: Any?, pm: Any?): String? {
        // ① 实体层：DisclaimergeMessage（快手主用路径）
        try {
            val d = Reflect.readAny(ent, "mDisclaimergeMessage")
                ?: Reflect.readAny(ent, "mDisclaimerMessage")
                ?: Reflect.readAny(qp, "mDisclaimergeMessage")
            if (d != null) {
                val c = when {
                    d is String -> d
                    else -> (try { Reflect.readAny(d, "content") as? String } catch (_: Throwable) { null })
                        ?: (try { Reflect.callMethod(d, "getContent") as? String } catch (_: Throwable) { null })
                }
                if (!c.isNullOrBlank()) return c
            }
        } catch (_: Throwable) {}
        // ② PhotoMeta 字段：mDisclaimerMessage（**注意不是 V2**）
        try {
            val c = Reflect.readString(pm, "mDisclaimerMessage")
            if (!c.isNullOrBlank()) return c
        } catch (_: Throwable) {}
        // ③ V2 变体（旧实现唯一读取的那个，实测恒空；保留作兜底）
        try {
            val v2 = Reflect.readAny(pm, "mDisclaimergeMessageV2")
            if (v2 != null) {
                val c = when {
                    v2 is String -> v2
                    else -> try { Reflect.readAny(v2, "content") as? String } catch (_: Throwable) { null }
                }
                if (!c.isNullOrBlank()) return c
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * 自证 `mAiTagForAuthor` 的**声明类型 + 实际值 + 运行时类名**（2026-09-24）。
     *
     * ★ 为什么需要：AI 判定里对该字段有两条判据 ——
     *   `readBool(...) == true` 和 `.toString() != "false"/"0"`。
     *   两者都**只覆盖特定形态**。用户报「AI 视频漏拦」时，
     *   无法区分是「字段真的没标记」还是「字段有值但形态没被覆盖」。
     *
     * 本函数把字段元信息打出，让这个区分变成事实而非猜测。
     * 只读，且最多调用 40 次（由调用方限次）。
     *
     * @return 形如 `type=boolean val=true cls=Boolean`；字段不存在返回 null
     */
    fun dumpAiTagField(ent: Any?): String? {
        if (ent == null) return null
        return try {
            var c: Class<*>? = ent.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                val cc: Class<*>? = c
                for (f in (cc ?: break).declaredFields) {
                    if (f.name != "mAiTagForAuthor") continue
                    f.isAccessible = true
                    val v = try { f.get(ent) } catch (_: Throwable) { null }
                    return "type=${f.type.simpleName} val=${v ?: "null"} cls=${v?.javaClass?.simpleName ?: "-"}"
                }
                c = cc?.superclass; lvl++
            }
            null
        } catch (_: Throwable) { null }
    }

    /**
     * 列出实体上**所有名字含 `Ai` 的字段**（名 + 类型 + 实际取值）。
     *
     * ★ 用途（2026-09-24）：AI 判定里用的 `mAiTagForAuthor` 探针实测**零输出**
     *   —— 说明该字段名在本版本上不存在，判据恒不成立（这正是「AI 视频漏拦」
     *   的可能原因）。与其继续猜字段名，直接把 ent 上的真实字段列出来。
     *
     * 只读；调用方限次（每进程 ≤12 次）。
     */
    /**
     * ★★ 全字段普查（2026-09-24）—— 查「AI 视频漏拦」的字段级真相。
     *
     * ## 为什么需要
     *
     * 实测到一个**确定漏拦**的样本：
     *   `#喜爱度激励计划 #暑你最有料`，ent=VideoFeed，
     *   而 `shouldFilterContent` 与 `quickOfficialDirty` **双双判 false**。
     *
     * 说明现有全部 AI 判据读的字段（`mDisclaimergeMessage` /
     * `mDisclaimergeMessageV2` / `photoAiAnalyze` / `mAiTagForAuthor` /
     * `mCaption` 关键词）在这条内容上**都没命中**。
     *
     * 继续猜字段名是低效的 —— 直接把 ent / PhotoMeta / CommonMeta 的
     * **全部字段名 + 值形态** 打出来，一次看清哪个字段能区分它。
     *
     * ## 输出形态
     *   `字段名=值`（字符串截 30 字符，其余显示类名；null 显式写 null）
     *
     * 只读；调用方限次（每进程 ≤3 组）。
     */
    fun dumpAllFields(o: Any?): String {
        if (o == null) return "(null)"
        val sb = StringBuilder()
        try {
            var c: Class<*>? = o.javaClass
            var lvl = 0
            // ★★ 深度修复（2026-09-24）—— 原为 lvl<3，实测**完全不够**。
            //
            //   实测输出只看到 `VideoFeed.mAd` / `PhotoMeta.activityLike` /
            //   `CommonMeta.canShowPersonalizedRecommendations` 三个字段，
            //   而 `mCaption` / `mPhotoMeta` / `mCommonMeta` 一个都没出现 ——
            //   但 `Reflect.readAny(ent, "mPhotoMeta")` 明明读到了值。
            //
            //   结论：这些字段定义在**更深的父类**里（快手模型层继承链很长），
            //   原深度 3 层只够看到最外层几个「本类新增字段」。
            //   遍历不到 ⇒ 拿不到字段全貌 ⇒ 无法据此判断哪个字段能区分 AI。
            //
            //   放宽到 12 层（对象层级不可能这么深，等于不限）。
            while (c != null && c != Any::class.java && lvl < 12) {
                val cc: Class<*>? = c
                for (f in (cc ?: break).declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(o)
                        val s = when {
                            v == null -> "null"
                            v is String -> "\"${v.take(30)}\""
                            v is Number || v is Boolean -> v.toString()
                            else -> "(${v.javaClass.simpleName})"
                        }
                        sb.append("  ").append(f.name).append('=').append(s).append('\n')
                    } catch (_: Throwable) {}
                }
                c = cc?.superclass; lvl++
            }
        } catch (_: Throwable) {}
        return if (sb.isEmpty()) "(no fields)" else sb.toString()
    }

    /**
     * ★★★ 广告指纹探测（2026-09-24）—— 查「品牌广告漏拦」的字段真相。
     *
     * ## 动机
     *
     * 主人现场报「schiff旭福官方海外」（品牌官方号广告），
     * 而周期巡检记录的判脏条目**全是内容型**（AI 声明 / 短剧），
     * 一条广告都没有 ⇒ 广告走的是**另一套数据结构**，
     * 现有判据（`mAd` / `mAdNovelVideoMeta` / `mAdPlaylet*`）没覆盖它。
     *
     * ## 做法
     *
     * 不猜字段名，而是对 ent / PhotoMeta / CommonMeta 逐字段扫描，
     * 凡字段名含 Ad / Advert / Commerce / Shop / Brand / Sponsor /
     * Promote / Marketing 且**值为真或非空**的，全部记下来。
     * 一次扫描即可看出这条广告带的是哪组标记。
     *
     * @return 形如 `字段名=值` 的拼接；无任何广告特征时返回 null
     */
    fun adFingerprint(qp: Any?, ent: Any?): String? {
        if (qp == null) return null
        val keys = arrayOf("Ad", "Advert", "Commerce", "Shop", "Brand", "Sponsor", "Promote", "Marketing", "Ec", "Mall")
        val sb = StringBuilder()
        val targets = listOfNotNull(
            ent?.let { "ent" to it },
            try { Reflect.readAny(ent, "mPhotoMeta")?.let { "pm" to it } } catch (_: Throwable) { null },
            try { Reflect.readAny(ent, "mCommonMeta")?.let { "cm" to it } } catch (_: Throwable) { null }
        )
        for ((tag, o) in targets) {
            try {
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 4) {
                    val cc: Class<*>? = c
                    for (f in (cc ?: break).declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        val hitKey = keys.any { f.name.contains(it, false) }
                        if (!hitKey) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            val s = when {
                                v is Boolean -> if (v) "true" else continue
                                v is Number -> if ((v as Number).toInt() != 0) v.toString() else continue
                                v is String -> if (v.isNotBlank() && v != "0" && !v.equals("false", true)) "\"${v.take(20)}\"" else continue
                                else -> "(${v.javaClass.simpleName})"
                            }
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(tag).append('.').append(f.name).append('=').append(s)
                        } catch (_: Throwable) {}
                    }
                    c = cc?.superclass; lvl++
                }
            } catch (_: Throwable) {}
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    fun dumpAiFields(ent: Any?): String {
        if (ent == null) return ""
        val sb = StringBuilder()
        try {
            var c: Class<*>? = ent.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                val cc: Class<*>? = c
                for (f in (cc ?: break).declaredFields) {
                    if (!f.name.contains("Ai", false)) continue
                    val v = try {
                        f.isAccessible = true
                        val raw = f.get(ent)
                        when (raw) {
                            null -> "null"
                            is Boolean, is Number -> raw.toString()
                            is String -> "\"${raw.take(20)}\""
                            else -> raw.javaClass.simpleName
                        }
                    } catch (_: Throwable) { "E" }
                    if (sb.isNotEmpty()) sb.append(',')
                    sb.append(f.name).append('=').append(v)
                }
                c = cc?.superclass; lvl++
            }
        } catch (_: Throwable) {}
        return sb.toString()
    }

    /**
     * 实体上是否存在**任何** AI 痕迹字段（用于决定是否值得打点）。
     *
     * 真机实测 `VideoFeed` 上的 Ai 字段只有 UI 开关：
     * `mAiChatGuideShowed / mConversationalAi / mEnablePaidQuestion /
     *  mPhotoAiColorAdjustInfo / mShowedForAiChatGuide` ——
     * **没有 `mAiTagForAuthor`**（判定代码里那条判据在本版本恒不成立）。
     * 本函数保守返回：只要出现「Tag/Author/Aigc/Generated」这类语义名就算有痕。
     */
    fun hasAnyAiTrace(ent: Any?): Boolean {
        if (ent == null) return false
        return try {
            var c: Class<*>? = ent.javaClass
            var lvl = 0
            var found = false
            while (c != null && c != Any::class.java && lvl < 4 && !found) {
                val cc: Class<*>? = c
                for (f in (cc ?: break).declaredFields) {
                    val n = f.name
                    if (n.contains("AiTag", true) || n.contains("Aigc", true) ||
                        n.contains("AiAuthor", true) || n.contains("AiGenerated", true) ||
                        n.contains("AiDeclar", true)
                    ) { found = true; break }
                }
                c = cc?.superclass; lvl++
            }
            found
        } catch (_: Throwable) { false }
    }

    /**
     * 结构类名判定 —— 用于把「框架内部对象」从内容判定里排除。
     *
     * ## 用途（4 个调用点，语义一致）
     * `CfhDecide:543/744/838`、`CfhPurge:72` 的写法都是：
     * ```
     * !isStructClsName(cls) && cls.contains("Live", true)   // → 判为直播，拦
     * ```
     * 即：**先排除框架对象，再按类名猜语义**。本函数的唯一职责就是那个「排除」。
     *
     * ## ★ 补 `Manager`（2026-09-25 真机实证）
     *
     * 冷启动实测（SM_S9180 / 14.8.20.50218）`LISTAUDIT` 显示：
     * ```
     * vm.f.h.c sz=42 直=0 提=10 有文案=0 脏=10
     *   元素类=,g,LiveWeakNetworkRecordInfoManager,
     *          LiveWeakNetworkRemoveAndInsertManagerV2,LiveReduceShowManager,h,d,e
     * ```
     * —— 这三个 `*Manager` 都是**直播网络状态管理器**（框架对象），
     * 却因类名含 `Live` 且本函数当时**不含 `Manager`** ⇒ 穿透到
     * `contains("Live")` ⇒ **全部被判为直播脏项**。
     *
     * 后果：该列表报 `脏=10`（A/B 对照：**热启动同一列表为 `脏=0`**），
     * 审计读数被污染；所幸被 `CfhWash` 的回调表保护（`cb*2>n`）兜住，
     * **未造成实际误删**。
     *
     * 补 `Manager` 后，`Live*Manager` 会被正确排除。
     *
     * ## 为什么安全
     * 本函数只影响「是否把某对象当直播拦」，且是**收紧**方向
     * （把原本误拦的框架对象放回）。与历史上因**扩大**删除范围导致的
     * 「无更多作品」事故方向相反（见 `CfhWash.kt:2720-2740` 的检讨）。
     *
     * ## 不适用 / 待观察
     * - 若快手存在「类名含 Manager 但确实是内容实体」的对象，会被此规则漏放
     *   （当前未观察到；`feed.VideoFeed` 等真内容类名不含 Manager）
     * - 仅对 14.8.20.50218 实证，其它版本未验证
     */
    fun isStructClsName(cn: String): Boolean =
        cn.contains("Presenter") || cn.contains("Callback") || cn.contains("Fragment") ||
            cn.contains("Interceptor") || cn.contains("Executer") || cn.contains("Executor") ||
            // ★ 2026-09-25 真机实证补入：直播网络状态管理器族
            //   （LiveWeakNetworkRecordInfoManager / LiveWeakNetworkRemoveAndInsertManagerV2
            //    / LiveReduceShowManager）此前穿透到 contains("Live") 判据。
            cn.contains("Manager") || cn.contains("Session") || cn.contains("Controller")
    fun dumpKV(obj: Any?): String {
        if (obj == null) return "null"
        val sb = StringBuilder("{")
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 2) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (v != null) {
                        val vs = when (v) {
                            is String -> "\"${v.take(20)}\""
                            is Number, is Boolean -> "$v"
                            is List<*> -> "List(${v.size})"
                            else -> v.javaClass.simpleName
                        }
                        sb.append(f.name).append('=').append(vs).append(';')
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        sb.append('}')
        return sb.toString()
    }
    fun dumpKVFilter(obj: Any?, kw: String): String {
        if (obj == null) return ""
        val sb = StringBuilder()
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 2) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (!f.name.contains(kw, true)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (v != null) {
                        val vs = when (v) {
                            is String -> "\"${v.take(20)}\""
                            is Number, is Boolean -> "$v"
                            else -> v.javaClass.simpleName
                        }
                        sb.append(f.name).append('=').append(vs).append(';')
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        return sb.toString()
    }
    fun dumpAiFields(qp: Any, ent: Any, cm: Any?): String {
        val sb = StringBuilder()
        var n = 0
        fun put(tag: String, fn: String, v: Any?) {
            if (v != null && n < 22) { sb.append(",").append(tag).append(".").append(fn).append("=").append(v.toString().take(12)); n++ }
        }
        // ★★ mDisclaimergeMessageV2 打印 content 原文而非对象 toString（2026-09 排查「开头漏网」）：
        // 该字段是判定 AI 的核心证据，但其 toString 恒为类名（DisclaimergeMessage），
        // 看不到实际声明文字 —— 无法判断某条放行项究竟是「AI 声明漏判」还是
        // 「虚构演绎声明正确放行」。probe13 有 5 条带声明却被放行，正是卡在这里。
        // 这段把 content 值与 isAi 判定一并拼进 dump，deep 行即可直接读出结论。
        try {
            val dis = Reflect.readAny(ent, "mDisclaimergeMessageV2")
                ?: Reflect.readAny(qp, "mDisclaimergeMessageV2")
                ?: cm?.let { Reflect.readAny(it, "mDisclaimergeMessageV2") }
            if (dis != null) {
                val c = try { Reflect.readAny(dis, "content") as? String } catch (_: Throwable) { null }
                sb.append(",disContent=\"").append(c?.take(26) ?: "<null>").append('"')
                sb.append(",disIsAi=").append(AI_DISCLAIMER_REGEX.containsMatchIn(c ?: ""))
            }
        } catch (_: Throwable) {}
        // 实体 + PhotoMeta + QPhoto
        for (o in listOf(ent, cm, qp)) {
            if (o == null) continue
            var c: Class<*>? = o.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    val fn = f.name
                    if ((fn.contains("ai", true) || fn.contains("aigc") || fn.contains("gen", true)) && !fn.contains("gain") && !fn.contains("again")) {
                        try { f.isAccessible = true; put("e", fn, f.get(o)) } catch (_: Throwable) {}
                    }
                }
                c = c.superclass; lvl++
            }
        }
        // mVideoModel
        val vm = try { Reflect.readAny(ent, "mVideoModel") } catch (_: Throwable) { null }
        if (vm != null) {
            var c: Class<*>? = vm.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    if ((f.name.contains("ai", true) || f.name.contains("aigc") || f.name.contains("gen", true)) && !f.name.contains("gain")) {
                        try { f.isAccessible = true; put("vm", f.name, f.get(vm)) } catch (_: Throwable) {}
                    }
                }
                c = c.superclass; lvl++
            }
        }
// mCoronaInfo 内部（快手 AI 生成内容标识体系）。
        val cor = try { Reflect.readAny(ent, "mCoronaInfo") } catch (_: Throwable) { null }
        if (cor != null) {
            var c: Class<*>? = cor.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(cor)
                        if (v != null) put("cor", f.name, v)
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
// ExtendableModelMap / 动态 map 类。
        for (tag in listOf("metaExtContainer", "mExtraMap", "mExtData")) {
            val em = try { Reflect.readAny(ent, tag) } catch (_: Throwable) { null } ?: continue
            if (em is Map<*, *>) {
                for ((k, v) in em.entries) {
                    val ks = k.toString()
                    if (ks.contains("ai", true) || ks.contains("gen", true)) put("map[" + tag + "]", ks, v)
                }
            } else {
// 非 Map：枚举其字段里值非空的小写 key。
                var c: Class<*>? = em.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c!!.declaredFields) {
                        if (f.name.contains("ai", true) && !f.name.contains("gain")) {
                            try { f.isAccessible = true; put("em", f.name, f.get(em)) } catch (_: Throwable) {}
                        }
                    }
                    c = c.superclass; lvl++
                }
            }
        }
        return if (sb.isEmpty()) "none" else sb.toString().removePrefix(",")
    }
}
