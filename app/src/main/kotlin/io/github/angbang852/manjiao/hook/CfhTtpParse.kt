package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * ★★★ 响应解析层拦截（2026-09-26 用户定稿「查 TTP 响应解析拦截」）。
 *
 * ## 用户原话
 *
 * > 「你现在给我查 TTP 响应解析拦截有没有方法」
 * > 「实现」
 *
 * ## 为什么这一层才是**真正的唯一源头**
 *
 * 此前三条路线全部失败 —— 原因都是「在下游副本上操作」：
 *
 * | 路线 | 失败原因 |
 * |---|---|
 * | 删列表元素 | 模块拿到的永远是副本（实测 `同一对象=true` **0 次**） |
 * | 打断渲染（VMSHOW） | 22 个 hook 装上，但 QPhoto 参数**恒为 null** |
 * | 接入快手校验器 | 注册成功，但 `recognizeAsInvalidData` **从未被调用** |
 *
 * ## 逆向确认的确切位置
 *
 * ```
 * 快手服务器（JSON 响应体）
 *     ↓
 * Gson 反序列化
 *     ↓
 * HomeFeedResponse$TypeAdapter.read()          （classes17.dex）
 *     ↓  case "feeds":                          ← JSON 字段名
 *     ↓  mQPhotos = this.f.read(aVar)           ← TypeAdapter:180
 *     ↓
 * KnownTypeAdapters$ListTypeAdapter.read()     ← ★★ 本文件的 hook 目标
 *     ├─ collection = this.b.a()                （新建空列表）
 *     ├─ while (jsonReader.hasNext()) {
 *     └─     collection.add(this.a.read(...))   ← ★ 每个 QPhoto 在此诞生（:80）
 *     ↓
 * 返回 Collection → mQPhotos
 *     ↓
 * 【所有下游都从这一份派生】
 *     ├─ ViewModel  vm.f.g.g.mQPhotos
 *     ├─ 卡片容器   TangramComponentManager / KmpSlideInformationComponent
 *     ├─ 返回值快照 H / V / F
 *     └─ 真源       l.a / h.j
 * ```
 *
 * **`collection.add(this.a.read(aVar))` 是每个 QPhoto 的第一次出现** ——
 * 此时**副本还没产生**，所以「副本问题」在这里不存在。
 *
 * ## 泛型共享类的区分问题（关键设计点）
 *
 * `ListTypeAdapter<V, T extends Collection<V>>` 是**泛型共享**的 ——
 * `List<QPhoto>` / `List<DynamicTabConfig>` / `List<其他>` 都用这一个类。
 *
 * **区分方式：`proceed()` 之后看返回的 `Collection` 的元素类型。**
 *   · 首元素是 QPhoto ⇒ 这就是解析 QPhoto 列表的调用 ⇒ 清洗
 *   · 否则 ⇒ 原样返回，不碰
 *
 * 为什么用这种方式：
 *   · `proceed()` 本来就要执行，返回值**白拿**
 *   · 不依赖实例字段反射（`a` 字段是 `TypeAdapter<V>`，需再判断元素类型，绕一层）
 *   · 空列表时无法判断元素类型 —— **但空列表也无需清洗**，天然安全
 *
 * ## 清洗语义（与其他层一致）
 *
 * | 判定 | 动作 |
 * |---|---|
 * | `WHITE` | 保留 |
 * | `DIRTY` | 从集合移除 |
 * | `PENDING` | **也移除**（没判明就不放行） |
 *
 * 与 `CfhClean.filterWhitelist` 完全一致 —— 复用 `CfhDecide.judgeWhitelist`。
 *
 * ## 安全设计
 *
 * · **默认不启用**：由 `CfhState.whiteListEnabled` 控制（与白名单同一开关）
 * · **移除而非替换**：不在列表里插入任何东西（遵守用户「别换条」的要求）
 * · **失败静默**：任何异常都不影响解析流程（解析失败会导致整条 feed 挂掉）
 * · **不改返回类型**：返回的还是原来那个 `Collection` 实例（原地移除）
 *
 * ## 不适用 / 未验证
 *
 * · 仅 14.8.20.50218 逆向确认（`classes10.dex` 定义、`classes17.dex` 调用方）
 * · **未验证** hook 是否真的命中（上线后用 `TTPPARSE` 证据观察）
 * · **未验证** 在此处移除是否会影响快手的响应完整性校验（如 `mQPhotos.size` 与
 *   其他字段不一致）—— 若有问题，`TTPPARSE-ERR` 会指出
 * · 仅覆盖 `HomeFeedResponse`（feeds 字段）这条链；
 *   其它响应类（`PhotoResponse` / `CoronaDetailFeedResponse` 等）需另接
 */
object CfhTtpParse {

    /** 目标类名（逆向确认） */
    private const val CLS_LIST_TYPE_ADAPTER = "com.vimeo.stag.KnownTypeAdapters\$ListTypeAdapter"

    /** 是否已安装 */
    @Volatile
    private var installed = false

    /** 解析链被调用的次数 */
    @Volatile
    var parseCount = 0

    /** 命中「QPhoto 列表」的次数（= 真正处理的次数） */
    @Volatile
    var qpListCount = 0

    /** 清洗掉的总条数 */
    @Volatile
    var removedTotal = 0

    /**
     * 安装响应解析层拦截。
     *
     * hook `KnownTypeAdapters$ListTypeAdapter.read(JsonReader)`：
     * 在它返回前，若返回值是「QPhoto 的集合」，就地按白名单清洗。
     *
     * @return 是否安装成功
     */
    fun install(xp: XposedInterface, cl: ClassLoader): Boolean {
        if (installed) return true
        var ok = false
        // ── ① 旧目标：ListTypeAdapter.read（实测只解析资源小列表，保留无害）──
        ok = tryInstallListTypeAdapter(xp, cl) || ok
        // ── ② feed 容器反序列化器 ──
        ok = tryInstallDeserializer(xp, cl) || ok
        // ── ③ ★★ QPhoto 专用反序列化器（2026-09-27 终极定位）──
        //   `QP-BIRTH` 实证：feed 主体的 QPhoto 由
        //   `com.yxcorp.gifshow.entity.QPhotoDeserializer.deserialize` 创建 ——
        //   它绕过 ListTypeAdapter 和 ObservableAndSyncableContainerDeserializer，
        //   这就是此前两个解析点都看不到详情页/首页部分内容的根本原因。
        //   QPhoto 诞生即判定：判脏 ⇒ 此刻它还没进入任何列表 ⇒ 完美的前置拦截。
        ok = tryInstallQPhotoDeserializer(xp, cl) || ok
        // ── ④ ★★★ Gson 原生列表容器（2026-09-28「思优短剧/重生赶海」根因修复）──
        //   详情页推荐流的 QPhoto 列表由 **Gson 原生 CollectionTypeAdapter** 解析
        //   （实证：「思优短剧」诞生链 `deserialize > TreeTypeAdapter.read >
        //   TypeAdapterRuntimeTypeWrapper.read`，TypeAdapterRuntimeTypeWrapper 与
        //   CollectionTypeAdapterFactory$Adapter 均为 Gson 原生类名、未混淆）。
        //   此前的网络层清洗只挂 stag `KnownTypeAdapters$ListTypeAdapter`（首页），
        //   详情页列表容器从未被 hook ⇒ 脏项从网络层一路传到 ViewModel → M。
        //   现在按用户方案「网络层拦截后，下一个不把空消息继续往下传」：
        //   hook Gson 容器 read 返回后对列表判脏移除（remove 而非传脏/null）。
        ok = tryInstallGsonCollectionAdapter(xp, cl) || ok
        installed = ok
        return ok
    }

    /**
     * ★★★ Gson 原生列表容器拦截（2026-09-28 详情页推荐流根因修复）。
     *
     * hook `com.google.gson.internal.bind.CollectionTypeAdapterFactory$Adapter.read`，
     * 在它返回 Collection 后执行 [scrubIfQpList]：命中 QPhoto 列表即判脏移除
     * （DIRTY/PENDING 都 `remove()` —— 不把空消息/脏消息继续往下传，符合用户
     * 「网络层拦截后，下一个不把空消息继续往下传」的定稿语义）。
     *
     * 与 stag `KnownTypeAdapters$ListTypeAdapter`（首页）互为补充：
     * 首页走 stag、详情页推荐流走 Gson 原生 —— 两者都 hook 才算网络层全覆盖。
     */
    private fun tryInstallGsonCollectionAdapter(xp: XposedInterface, cl: ClassLoader): Boolean {
        return try {
            val cn = "com.google.gson.internal.bind.CollectionTypeAdapterFactory\$Adapter"
            val cls = Reflect.findClass(cn, cl)
            if (cls == null) {
                Logger.evidence("GSCOLL", "★类不存在(可能被混淆) $cn")
                return false
            }
            val readM = cls.declaredMethods.firstOrNull { it.name == "read" && it.parameterCount == 1 }
            if (readM == null) {
                Logger.evidence(
                    "GSCOLL",
                    "★read 方法不存在，实际方法=" +
                        cls.declaredMethods.joinToString(",") { "${it.name}(${it.parameterCount})" }
                )
                return false
            }
            readM.isAccessible = true
            xp.hook(readM)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("ttp.gson.collection")
                .intercept { chain ->
                    val r = chain.proceed()
                    try { scrubIfQpList(r) } catch (_: Throwable) {}
                    if (CfhState.gsCollLog < 40) {
                        CfhState.gsCollLog++
                        val desc = when (r) {
                            null -> "null"
                            is List<*> -> "List<${r.firstOrNull { it != null }?.javaClass?.simpleName ?: "?"}> sz=${r.size}"
                            is MutableCollection<*> -> "Coll<...> sz=${r.size}"
                            else -> r.javaClass.name
                        }
                        Logger.evidence("GSCOLL", "Gson列表 read 返回: $desc")
                    }
                    r
                }
            installed = true
            Logger.evidence("GSCOLL", "★Gson CollectionTypeAdapter.read 已安装")
            CfhState.noteHookStatus("网络列表容器(Gson原生)", true, "CollectionTypeAdapter.read")
            true
        } catch (t: Throwable) {
            Logger.evidence("GSCOLL", "★安装失败 ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    /** ③ QPhoto 专用反序列化器拦截（诞生即判定） */
    private fun tryInstallQPhotoDeserializer(xp: XposedInterface, cl: ClassLoader): Boolean {
        return try {
            val cn = "com.yxcorp.gifshow.entity.QPhotoDeserializer"
            val cls = Reflect.findClass(cn, cl)
            if (cls == null) {
                Logger.evidence("TTPPARSE3", "★类不存在: $cn")
                return false
            }
            val m = cls.declaredMethods.firstOrNull { it.name == "deserialize" }
            if (m == null) {
                Logger.evidence("TTPPARSE3", "★deserialize 不存在")
                return false
            }
            m.isAccessible = true
            xp.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("ttp.parse.qphoto")
                .intercept { chain ->
                    // ★★★ v13.79 顺手捕获**快手自己的 Gson 实例**（池持久化的命脉）。
                    //   `deserialize(JsonElement, Type, Gson)` 的第 3 个参数就是 Gson 本体。
                    //   必须用它的原因见 CfhState.appGson 的注释（默认 Gson 构造不了
                    //   快手的模型：BaseFeed() 无参构造一 invoke 就抛异常）。
                    try { CfhState.noteAppGson(chain.args.getOrNull(2)) } catch (_: Throwable) {}
                    val r = chain.proceed()
                    try {
                        val qc = CfhState.qpClassRef
                        if (r != null && qc != null && qc.isInstance(r) && CfhState.whiteListEnabled) {
                            val v = try { CfhDecide.judgeWhitelist(r) }
                                    catch (_: Throwable) { CfhDecide.WhitelistVerdict.PENDING }
                            val pid = try { CfhProbe.readPhotoId(r) } catch (_: Throwable) { null }
                            // 声明映射写入（诞生时字段最全）
                            if (!pid.isNullOrBlank()) {
                                try {
                                    val entM = Reflect.readAny(r, "mEntity") ?: r
                                    val pmM = Reflect.readAny(entM, "mPhotoMeta")
                                    val dis = try { CfhUtil.readDisclaimer(r, entM, pmM) } catch (_: Throwable) { null }
                                    if (!dis.isNullOrBlank()) CfhState.declByPhotoId.put(pid, dis)
                                } catch (_: Throwable) {}
                            }
                            if (v != CfhDecide.WhitelistVerdict.WHITE) {
                                // ★★★ 2026-09-28 二次实测修正（第五版）：判脏 → 写黑名单 + 放行原对象。
                                //
                                // ## 血泪史（四版全失败，逐版实证）
                                //
                                // 第一版 返回 null  ⇒ 详情页 FEED_ITEM_VIEW_PARAM 缺失崩溃
                                // 第二版 返回空壳   ⇒ 首页空壳占位 ⇒ 固定几条循环 + 下拉刷新崩溃
                                // 第三版 返回 null  ⇒ 仍崩（a6i.g.doInject「must not be null」）
                                // 第四版 放行       ⇒ 首页脏项全回来（deserialize 遍历拖垮解析，
                                //                      声明回填延迟 ⇒ ListTypeAdapter 判白 ⇒ 无源头拦截）
                                // 第五版（本轮）：判脏写黑名单 + 放行原对象 —— **不产生 null**（不崩），
                                //                 由三层兜底拦截：
                                //   ① ListTypeAdapter.read 清洗（TTPPARSE-BLOCK，deserialize 降级后
                                //      第二轮实证有效：判脏9清9/判脏7清7，剩0）
                                //   ② 黑名单 dirtyPhotoMap（本 hook 写入）→ 下游 DLFEED-BLOCK /
                                //      PSCAN 巡检 / PresenterBindHook 查黑名单补拦
                                //   ③ 声明映射 declByPhotoId（上方已写）→ 副本声明丢失也判脏
                                // ★★★ 2026-09-28 补充（「精选页刷不出」修复）：
                                //   **PENDING 不写黑名单** —— PENDING 只是「字段未回填、暂时判不了」，
                                //   不是判脏事实。若 PENDING 也 noteDirty，则「第N集」正常内容
                                //   （新闻连载/动画番剧，诞生瞬间声明未回填）会被黑名单短路
                                //   反复删除 30 分钟（BLOCK 判据=? 实证 29/60 误删正常内容）。
                                //   ⇒ 只 DIRTY 入黑名单；PENDING 由 scrubIfQpList 二次判定
                                //   （字段已回填时判 WHITE 放行 / 判 DIRTY 删除）。
                                if (!pid.isNullOrBlank() && v == CfhDecide.WhitelistVerdict.DIRTY) {
                                    CfhState.noteDirty(pid, CfhDecide.lastHitReason ?: "qphoto")
                                }
                                if (CfhState.qphotoBirthLog < 60) {
                                    CfhState.qphotoBirthLog++
                                    val cap = try { CfhUtil.readCaption(r) } catch (_: Throwable) { null }
                                    val rsn = try { CfhDecide.lastHitReason } catch (_: Throwable) { null }
                                    Logger.evidence(
                                        "QPHOTO-BLOCK",
                                        "★QPhoto 判脏(${v.name}) 写黑名单放行 id=$pid " +
                                            "判据=${rsn ?: "?"} cap=\"${cap?.take(22) ?: "-"}\""
                                    )
                                }
                                // 放行原对象（不 return）—— 拦截交给黑名单 + ListTypeAdapter 清洗
                            }
                        }
                    } catch (_: Throwable) {}
                    r
                }
            Logger.evidence("TTPPARSE3", "★QPhoto 诞生拦截已安装: $cn.deserialize")
            CfhState.noteHookStatus("QPhoto诞生拦截", true, "QPhotoDeserializer.deserialize")
            true
        } catch (t: Throwable) {
            Logger.evidence("TTPPARSE3", "★安装失败 ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    /** 旧拦截点保留（无害）：`KnownTypeAdapters$ListTypeAdapter.read` */
    private fun tryInstallListTypeAdapter(xp: XposedInterface, cl: ClassLoader): Boolean {
        return try {
            val cls = Reflect.findClass(CLS_LIST_TYPE_ADAPTER, cl)
            if (cls == null) {
                Logger.evidence("TTPPARSE", "★ListTypeAdapter 类不存在，跳过")
                return false
            }
            // 找 read 方法（参数是 JsonReader，返回 Object/Collection）
            val readM = cls.declaredMethods.firstOrNull {
                it.name == "read" && it.parameterCount == 1
            }
            if (readM == null) {
                Logger.evidence(
                    "TTPPARSE",
                    "★read 方法不存在，实际方法=" +
                        cls.declaredMethods.joinToString(",") { "${it.name}(${it.parameterCount})" }
                )
                return false
            }
            readM.isAccessible = true
            Logger.evidence(
                "TTPPARSE",
                "已找到 ${cls.simpleName}.${readM.name}(${readM.parameterTypes.firstOrNull()?.simpleName})"
            )
            xp.hook(readM)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("ttp.parse.list")
                .intercept { chain ->
                    val r = chain.proceed()
                    try { scrubIfQpList(r) } catch (_: Throwable) {}
                    // ★★★ 无过滤探针（2026-09-27）：read 的每一次返回都记录类型。
                    //
                    //   ## 为什么必须（用户纠正「你喜欢猜」）
                    //
                    //   「离柜概不负责」漏拦且零 TTPPARSE 记录 —— 有两种可能：
                    //     (a) 它的解析不经过本方法
                    //     (b) 经过本方法，但被 scrubIfQpList 的过滤条件排除（无日志）
                    //   此前我一直按 (a) 处理，从未验证。
                    //
                    //   本探针记录**每次调用**的返回类型分布：
                    //   若刷详情页时出现「非 MutableCollection」「非QPhoto列表」的类型，
                    //   则 (b) 成立 ⇒ 修过滤条件即可；若无 ⇒ (a) 成立 ⇒ 换 hook 目标。
                    try {
                        if (CfhState.readProbeLog < 80) {
                            CfhState.readProbeLog++
                            val desc = when (r) {
                                null -> "null"
                                is List<*> -> "List<${r.firstOrNull { it != null }?.javaClass?.simpleName ?: "?"}> sz=${r.size}"
                                is MutableCollection<*> -> "MutableColl<${r.firstOrNull { it != null }?.javaClass?.simpleName ?: "?"}> sz=${r.size}"
                                else -> r.javaClass.name
                            }
                            Logger.evidence(
                                "READ-PROBE",
                                "read 返回: $desc 线程=${Thread.currentThread().name}"
                            )
                        }
                    } catch (_: Throwable) {}
                    r
                }
            installed = true
            Logger.evidence("TTPPARSE", "★已安装响应解析层拦截（ListTypeAdapter.read）")
            CfhState.noteHookStatus("响应解析层拦截(旧)", true, "ListTypeAdapter.read")
            true
        } catch (t: Throwable) {
            Logger.evidence("TTPPARSE", "★安装失败 ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    /**
     * ★★★ 主拦截点（2026-09-27）：feed 主体反序列化器。
     *
     * ## 为什么换到这里（证据）
     *
     * `READ-PROBE` 实测 `ListTypeAdapter.read` 的返回类型分布：
     * ```
     * List<?> sz=0 ×22 / List<BeautifyItem> / List<Integer> / List<CDNUrl> / List<String>
     * 线程全是 ela-cpu-dredge / ela-io（资源预载）
     * ⇒ feed 主体（QPhoto 列表）根本不经过它
     * ```
     * `DSTRACE` 实证 `DisclaimergeMessage` 的创建栈：
     * ```
     * #4 ReflectiveTypeAdapterFactory$Adapter.read
     * #8 ObservableAndSyncableContainerDeserializer.deserialize   ← ★ feed 主体在这
     * ```
     *
     * ## 拦截逻辑
     *
     * `deserialize(JsonElement, Type, Gson) -> Object` 的返回值是**反序列化完成的响应对象**
     * （如 HomeFeedResponse）。它内部的 QPhoto 列表此刻**声明字段完整**。
     * ⇒ 对返回对象做深度清洗（原有 scrubIfQpList 已能处理其内部列表，
     *    另加映射写入）。
     */
    private fun tryInstallDeserializer(xp: XposedInterface, cl: ClassLoader): Boolean {
        return try {
            val cn = "com.kwai.framework.model.decompose.internal.ObservableAndSyncableContainerDeserializer"
            val cls = Reflect.findClass(cn, cl)
            if (cls == null) {
                Logger.evidence("TTPPARSE2", "★类不存在: $cn")
                return false
            }
            val m = cls.declaredMethods.firstOrNull { it.name == "deserialize" }
            if (m == null) {
                Logger.evidence("TTPPARSE2", "★deserialize 方法不存在")
                return false
            }
            m.isAccessible = true
            xp.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("ttp.parse.deser")
                .intercept { chain ->
                    // ★★★ v13.79 捕获**快手自己的 Gson**（池持久化的命脉）。
                    //   这条路是**确定在跑**的（TTPPARSE2-CALL 一直在刷），
                    //   而 QPhotoDeserializer 那条不一定触发 —— 上一轮就吃了这个亏：
                    //   `★恢复前等待 appGson=false 等待=10000ms`。
                    //   同时把 args[2] 的**真实类名**打出来，不再猜它到底是不是 Gson。
                    try {
                        val a2 = chain.args.getOrNull(2)
                        if (a2 != null && CfhState.appGson == null) {
                            CfhState.noteAppGson(a2)
                            if (CfhState.appGson == null && CfhState.gsonProbeLog < 3) {
                                CfhState.gsonProbeLog++
                                Logger.evidence(
                                    "POOLGSON",
                                    "★args[2] 不是 Gson：${a2.javaClass.name} " +
                                        "（args 数=${chain.args.size}）"
                                )
                            }
                        }
                    } catch (_: Throwable) {}
                    val r = chain.proceed()
                    try {
                        // ★★★ 2026-09-28 用户抓「小满的乡居时光」：详情页推荐流数据形态
                        //   是 **PhotoMeta**（非 QPhoto，不经 QPhotoDeserializer）——
                        //   零记录、零拦截。本段恢复 **PhotoMeta 声明判定 → 写黑名单**。
                        //
                        // ## 为什么这样安全（不重蹈「精选页无网络」）
                        //
                        // 上一版整批丢弃（countDirtyIn 2000 节点遍历 + writeDeclMap）在
                        // Gson 解析线程上拖垮精选页 ⇒ 无网络。本段**只做两件事**：
                        //   · 反射读 PhotoMeta 的 AI 声明字段（mDisclaimerMessage /
                        //     mDisclaimergeMessageV2，微秒级）
                        //   · 判出 AI 声明 ⇒ 写 dirtyPhotoMap 黑名单（**不丢对象**、
                        //     **不动列表结构**）
                        // 黑名单由下游短路（isBlacklisted / DLFEED-BLOCK / PV2-BLOCK）
                        // 拦截同 id 的 QPhoto 版本 ⇒ 详情页推荐流与首页都能兜住。
                        if (CfhState.whiteListEnabled) {
                            try {
                                val clsName = r?.javaClass?.name ?: ""
                                if (clsName.contains("PhotoMeta") || clsName.contains("PhotoMetaModel")) {
                                    val dis = try {
                                        Reflect.readAny(r, "mDisclaimerMessage")
                                            ?: Reflect.readAny(r, "mDisclaimergeMessageV2")
                                    } catch (_: Throwable) { null }
                                    val disStr = dis as? String
                                    if (disStr != null && CfhUtil.isAiDisclaimerText(disStr)) {
                                        val pidPm = try { CfhProbe.readPhotoId(r) } catch (_: Throwable) { null }
                                        if (!pidPm.isNullOrBlank()) {
                                            CfhState.noteDirty(pidPm, "ai:photoMeta")
                                            if (CfhState.pmBlockLog < 60) {
                                                CfhState.pmBlockLog++
                                                Logger.evidence(
                                                    "PMETA-BLOCK",
                                                    "★PhotoMeta AI声明→写黑名单 id=$pidPm " +
                                                        "声明=\"$disStr\""
                                                )
                                            }
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                        if (CfhState.deserProbeLog < 60) {
                            CfhState.deserProbeLog++
                            val desc = try { r?.javaClass?.simpleName ?: "null" } catch (_: Throwable) { "?" }
                            Logger.evidence(
                                "TTPPARSE2-CALL",
                                "★deserialize 被调用 返回=${desc} 线程=${Thread.currentThread().name}"
                            )
                        }
                    } catch (_: Throwable) {}
                    r
                }
            Logger.evidence("TTPPARSE2", "★主解析拦截已安装: $cn.deserialize")
            CfhState.noteHookStatus("主解析拦截", true, "ObservableAndSyncableContainerDeserializer")
            true
        } catch (t: Throwable) {
            Logger.evidence("TTPPARSE2", "★安装失败 ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    /** 已处理对象（防环/防重复清洗） */
    private val scrubbed = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    )

    /**
     * 深度清洗响应对象：遍历其字段图，对「元素直接是 QPhoto 的可变列表」
     * 执行白名单清洗 + 声明映射写入。
     *
     * 深度 4 / 防环，仅由 [tryInstallDeserializer] 在每次 deserialize 后调用。
     */
    /**
     * ★ 统计响应对象图里的脏 QPhoto 数量（只读，不修改）。
     * 深度 4 / 防环。供 deserialize 整段拦截决策用。
     */
    private fun countDirtyIn(root: Any): Int {
        val qc = CfhState.qpClassRef ?: return 0
        var dirty = 0
        var vis = 0
        val seen = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
        )
        val q = ArrayDeque<Any>()
        q.add(root)
        seen.add(root)
        try {
            while (q.isNotEmpty() && vis < 2000) {
                val o = q.removeFirst()
                vis++
                if (qc.isInstance(o)) {
                    // 注意：judgeWhitelist 返回枚举，catch 分支必须也是可空枚举（不能 false）
                    val d = try { CfhDecide.judgeWhitelist(o) } catch (_: Throwable) { null }
                    if (d == CfhDecide.WhitelistVerdict.DIRTY) dirty++
                    continue
                }
                if (o is List<*>) {
                    for (e in o) {
                        if (e != null && seen.add(e)) q.add(e)
                    }
                    continue
                }
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cur: Class<*> = c
                    for (f in Reflect.nonStaticFields(cur)) {
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            val vn = v.javaClass.name
                            if (vn.startsWith("android.") || v is android.view.View) continue
                            if (seen.add(v)) q.add(v)
                        } catch (_: Throwable) {}
                    }
                    c = cur.superclass; lvl++
                }
            }
        } catch (_: Throwable) {}
        return dirty
    }

    /**
     * ★ 把响应对象图里所有 QPhoto 的声明写入映射（副本兜底，2026-09-27）。
     * 解析期原始实例字段最全 —— 下游拦截点查映射即可命中。
     */
    private fun writeDeclMap(root: Any) {
        try {
            val qc = CfhState.qpClassRef ?: return
            var vis = 0
            val seen = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
            )
            val q = ArrayDeque<Any>()
            q.add(root)
            seen.add(root)
            while (q.isNotEmpty() && vis < 1500) {
                val o = q.removeFirst()
                vis++
                if (qc.isInstance(o)) {
                    try {
                        val pid = CfhProbe.readPhotoId(o)
                        if (!pid.isNullOrBlank()) {
                            val entM = Reflect.readAny(o, "mEntity") ?: o
                            val pmM = Reflect.readAny(entM, "mPhotoMeta")
                            val dis = try { CfhUtil.readDisclaimer(o, entM, pmM) } catch (_: Throwable) { null }
                            if (!dis.isNullOrBlank()) CfhState.declByPhotoId.put(pid, dis)
                        }
                    } catch (_: Throwable) {}
                    continue
                }
                if (o is List<*>) {
                    for (e in o) { if (e != null && seen.add(e)) q.add(e) }
                    continue
                }
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    val cur: Class<*> = c
                    for (f in Reflect.nonStaticFields(cur)) {
                        try {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            val vn = v.javaClass.name
                            if (vn.startsWith("android.") || v is android.view.View) continue
                            if (seen.add(v)) q.add(v)
                        } catch (_: Throwable) {}
                    }
                    c = cur.superclass; lvl++
                }
            }
        } catch (_: Throwable) {}
    }

    private fun scrubNestedLists(root: Any, depth: Int = 0) {
        if (depth > 4) return
        val qc = CfhState.qpClassRef ?: return
        val key = System.identityHashCode(root)
        if (!scrubbed.add(key)) return
        if (scrubbed.size > 2048) scrubbed.clear()
        try {
            if (root is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val ml = root as MutableList<Any?>
                if (ml.isNotEmpty()) {
                    val first = ml.firstOrNull { it != null }
                    if (first != null && qc.isInstance(first)) {
                        // 元素直接是 QPhoto ⇒ 原清洗逻辑（含映射写入）
                        try { scrubIfQpList(ml) } catch (_: Throwable) {}
                        return
                    }
                }
            }
            var c: Class<*>? = root.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                val cur: Class<*> = c
                for (f in Reflect.nonStaticFields(cur)) {
                    try {
                        f.isAccessible = true
                        val v = f.get(root) ?: continue
                        val vn = v.javaClass.name
                        if (vn.startsWith("android.") || v is android.view.View) continue
                        if (v is List<*> && v.isNotEmpty()) {
                            val first = v.firstOrNull { it != null }
                            if (first != null && qc.isInstance(first)) {
                                // ★ 找到 QPhoto 列表：清洗 + 映射写入
                                if (v is MutableList<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    try { scrubIfQpList(v as MutableList<Any?>) } catch (_: Throwable) {}
                                }
                                // 无论可变与否，都把声明写入映射（副本兜底用）
                                for (e in v) {
                                    if (e == null) continue
                                    if (!qc.isInstance(e)) continue
                                    try {
                                        val pid = CfhProbe.readPhotoId(e)
                                        if (pid.isNullOrBlank()) continue
                                        val entM = Reflect.readAny(e, "mEntity") ?: e
                                        val pmM = Reflect.readAny(entM, "mPhotoMeta")
                                        val dis = try { CfhUtil.readDisclaimer(e, entM, pmM) } catch (_: Throwable) { null }
                                        if (!dis.isNullOrBlank()) CfhState.declByPhotoId.put(pid, dis)
                                    } catch (_: Throwable) {}
                                }
                            } else if (depth < 3) {
                                scrubNestedLists(v, depth + 1)
                            }
                        } else {
                            val vn2 = v.javaClass.name
                            if (!vn2.startsWith("java.") && !vn2.startsWith("kotlin.") &&
                                depth < 3
                            ) {
                                scrubNestedLists(v, depth + 1)
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c = cur.superclass; lvl++
            }
        } catch (_: Throwable) {}
    }

    /**
     * 若返回值是「QPhoto 的集合」，就地按白名单清洗。
     *
     * 判定：**看集合的首个非空元素的类型**。
     * 空集合无法判断 ⇒ 直接返回（空集合本来也无需清洗）。
     */
    private fun scrubIfQpList(r: Any?): Int {
        if (r !is MutableCollection<*>) return 0
        parseCount++
        if (r.isEmpty()) return 0
        // 取首个非空元素判类型
        val first = r.firstOrNull { it != null } ?: return 0
        val qpCls = CfhState.qpClassRef
        val isQp = when {
            qpCls != null -> qpCls.isInstance(first)
            // qpClassRef 尚未建立时按类名兜底
            else -> first.javaClass.name == "com.yxcorp.gifshow.entity.QPhoto"
        }
        // ★★★ 放宽（2026-09-27「橘猫小九」）：不再要求「首元素直接是 QPhoto」。
        //
        // ## 为什么放宽
        //
        // 实测详情页推荐流解析出的是**包装列表**（VideoFeed/分页包装），
        // 首元素不是 QPhoto ⇒ 原条件直接 return 0 ⇒
        // **网络层对详情页推荐流完全不生效**（这正是「网络层为什么拦截不到」的答案）。
        //
        // 新条件：列表里【任一元素能提取出 QPhoto】即处理（清洗时逐元素深挖）。
        // 成本：仅对首元素做一次 findQpInObject（深度2），命中即进入清洗。
        val hasQp = isQp || (try { CfhProbe.findQpInObject(first) } catch (_: Throwable) { null } != null)
        if (!hasQp) return 0

        qpListCount++
        if (CfhState.ttpLog < 60) {
            CfhState.ttpLog++
            Logger.evidence("TTPPARSE", "★命中 QPhoto 列表 size=${r.size}（解析期）")
        }
        // ★★★ 取证埋点（2026-09-26 用户要求完整取证）。
        //
        // 用户要的四项数据：
        //   ① 多长时间接收一次服务器数据   → 用 `TTPPARSE-RECV` 的时间戳算间隔
        //   ② 拦截点前有没有数据积压       → 用列表 size 与「同一时刻排队数」判断
        //   ③ 多少判脏、多少放行           → `TTPPARSE-BLOCK` vs 放行计数
        //   ④ 多长时间放行一个             → 用 `TTPPARSE-PASS` 的时间戳算间隔
        //
        // 全部记在**同一个证据文件**里，时间戳即真机毫秒，可直接做统计。
        val now = System.currentTimeMillis()
        // ② 积压判定：距上次接收超过 1 秒仍有数据进来 ⇒ 可能积压
        val gap = if (CfhState.ttpLastRecvAt > 0) now - CfhState.ttpLastRecvAt else -1
        if (CfhState.ttpRecvLog < 200) {
            CfhState.ttpRecvLog++
            CfhState.ttpLastRecvAt = now
            Logger.evidence(
                "TTPPARSE-RECV",
                "① 收到数据 批次=#${CfhState.ttpRecvLog} size=${r.size} " +
                    "距上次=${if (gap < 0) "首次" else "${gap}ms"}"
            )
        }
        if (r.size > CfhState.ttpMaxBatch) CfhState.ttpMaxBatch = r.size
        // 白名单未开启 ⇒ 不干预（只观察）
        if (!CfhState.whiteListEnabled) return 0

        @Suppress("UNCHECKED_CAST")
        val col = r as MutableCollection<Any?>
        var removed = 0
        var blockedTotal = 0; var dirtyTotal = 0; var pendingTotal = 0
        try {
            val it = col.iterator()
            while (it.hasNext()) {
                val el = it.next() ?: continue
                // ★★★ 元素解包（2026-09-27「橘猫小九」）：列表元素可能是包装类
                //   （VideoFeed 等），QPhoto 在其内部 —— 直接判包装类会因字段路径
                //   不同而判白。先解包出 QPhoto，映射/判定都针对 QPhoto 本体。
                val qc0 = CfhState.qpClassRef
                val q = if (qc0 != null && qc0.isInstance(el)) el
                        else try { CfhProbe.findQpInObject(el) } catch (_: Throwable) { null }
                        ?: continue
                // ★★★ photoId→声明 映射写入（2026-09-27 用户定稿「映射」）。
                //
                // ## 为什么必须在这里写
                //
                // 解析期的 QPhoto 是**原始实例**（Gson 刚反序列化，
                // 声明字段完整）。快手后续会把这条数据**浅拷贝**进
                // SlidePlayViewModel / 详情页 —— 副本的声明字段丢失。
                //
                // 实测：详情页取数（x4）返回的副本判定时声明为空，
                // 而同 id 的原始实例在解析期就带「含AI生成内容」。
                //
                // ⇒ 解析期把 photoId → 声明内容 记下，
                //   下游拦截点（PresenterBind/DetailFeed）判定时加查此映射，
                //   副本字段缺失也能命中 AI 声明。
                try {
                    val pidM = CfhProbe.readPhotoId(q)
                    if (!pidM.isNullOrBlank()) {
                        val entM = Reflect.readAny(q, "mEntity") ?: q
                        val pmM = Reflect.readAny(entM, "mPhotoMeta")
                        val disM = try { CfhUtil.readDisclaimer(q, entM, pmM) } catch (_: Throwable) { null }
                        if (!disM.isNullOrBlank()) {
                            CfhState.declByPhotoId.put(pidM, disM)
                        }
                    }
                } catch (_: Throwable) {}
                // ★ 单条耗时埋点（2026-09-26 用户要求「加快识别过滤速度」）。
                //   定位尖峰来源：是「判定慢」还是「单条某个环节慢」。
                val t0 = System.currentTimeMillis()
                val verdict = try { CfhDecide.judgeWhitelist(q) }
                catch (_: Throwable) { CfhDecide.WhitelistVerdict.PENDING }
                // ★★★ v13.40 网络层 WHITE 直接登记进池（2026-09-30 用户定案根因）：
                //   ## 问题（用户实证：「主页一刷视频框框出来，精选页就是刷不出来」）
                //   池是共享的（cleanPool 唯一），但首页那些「框框出来」的干净内容
                //   **从未进池** —— 登记只装在 hookFeedResponse 分支，而那里
                //   每批只拿到 1 条（HOMEPOOL 实测 批size=1 成功=0）。
                //   用户刷首页 = 一大把内容（ENTRYTRACE 实证：同毫秒 5+ 条
                //   @是苏不是输 / #光合计划 / 这次相亲排第一 / 土豆新吃法…），
                //   全部走**本函数**（每个 QPhoto 反序列化必经），却只判不放池。
                //   ⇒ 精选页（内容全脏，删空）需要补位时池是空的 ⇒ 刷不出来。
                //
                //   ## 修复
                //   判定 WHITE ⇒ 立刻 noteClean 登记进池。这是**采集最全**的点：
                //   用户刷什么，什么都从这里过。脏内容不会混入（只登 WHITE），
                //   noteClean 自带类型校验 + pid 去重 + 池上限 100。
                //   补位仍只发生在精选页链路（refillFromCleanPool）—— 首页不补位。
                // ★★★ v13.45 拉取窗口内登记进池（2026-09-30 用户方案落地）：
                //   ## 演进
                //   v13.40 无条件登记 ⇒ 关注页/同城页/详情页的内容也进池
                //     （用户质问「为什么要拉同城和关注的数据？」）—— 污染。
                //   v13.44 完全撤销 ⇒ 主页拉回来的干净批次也进不去（实证
                //     `HOMEPOOL 批size=0`，而网络层明明 `收=6 放行=6 挡下=0`）。
                //   v13.45 折中：**只在主页拉取后的时间窗内登记**。
                //   triggerHomeLoad() 发起后开 3s 窗，窗口内到达并判 WHITE 的
                //   内容即本次主页拉取的响应 ⇒ 进池；窗口外的自然流量（用户
                //   在关注/同城/精选页滑动）**不登记** ⇒ 池只装主页数据。
                //   这同时满足用户两条要求：「从主页拉数据进池」+「不要同城关注」。
                if (verdict == CfhDecide.WhitelistVerdict.WHITE &&
                    System.currentTimeMillis() < CfhState.homePullWindowUntil
                ) {
                    try { CfhState.noteClean(q) } catch (_: Throwable) {}
                }
                val dt = System.currentTimeMillis() - t0
                if (dt >= 20 && CfhState.ttpSlowLog < 60) {
                    CfhState.ttpSlowLog++
                    val cap = try { CfhUtil.readCaption(q) } catch (_: Throwable) { null }
                    // ★ 细分耗时（2026-09-26）：判定慢在哪一步
                    val tA = System.currentTimeMillis()
                    val qpClassOk = CfhState.qpClassRef != null
                    val tB = System.currentTimeMillis()
                    val ent = try { Reflect.readAny(q, "mEntity") } catch (_: Throwable) { null }
                    val tC = System.currentTimeMillis()
                    val cached = try { CfhState.feedFilterCache.containsKey(q) } catch (_: Throwable) { false }
                    val tD = System.currentTimeMillis()
                    Logger.evidence(
                        "TTPPARSE-SLOW",
                        "★单条耗时 ${dt}ms ${verdict.name} cap=\"${cap?.take(18) ?: "-"}\" " +
                            "qpCls=${if (qpClassOk) "有" else "无"}(${tB - tA}ms) " +
                            "读ent=${ent != null}(${tC - tB}ms) " +
                            "缓存=${cached}(${tD - tC}ms)"
                    )
                }
                // ★ 白名单语义：只有 WHITE 才留（DIRTY / PENDING 都移除）
                // ★★★ 2026-09-28 用户定稿（第七版）：**脏就删，不需要保留 1 条**。
                //
                // ## 为什么恢复移除（用户明确要求「精选页必须网络层拦截」）
                //
                // 第六版「判脏写黑名单 + 放行原对象」把网络层拦截**变相取消了**：
                // 精选页列表里的脏项原样进 UI ⇒ 用户实测「精选页脏项全部回来」。
                //
                // 无网络的历史根因是 **deserialize 遍历拖垮解析线程**（第一轮）
                // 与 **QPHOTO-BLOCK 返回 null**（第二轮）—— 两者都已修复：
                //   · deserialize 已降级为轻量探针（TTPPARSE2-CALL，只打日志）
                //   · QPHOTO-BLOCK 判脏写黑名单后**放行原对象**（不产生 null）
                // ⇒ 现在恢复本层移除，精选页能播 + 脏项被源头移除。
                //
                // 删除动作与第六版黑名单写入并存（双保险）：
                //   · 本层：DIRTY/PENDING 直接从列表移除（不进 UI）
                //   · 黑名单：isBlacklisted 短路兜底（详情页 DLFEED / PSCAN 复用）
                if (verdict != CfhDecide.WhitelistVerdict.WHITE) {
                    it.remove()
                    removed++
                    blockedTotal++
                    if (verdict == CfhDecide.WhitelistVerdict.DIRTY) dirtyTotal++ else pendingTotal++
                    try {
                        val pid = CfhProbe.readPhotoId(q)
                        if (!pid.isNullOrBlank()) {
                            CfhState.noteDirty(pid, CfhDecide.lastHitReason ?: "ttp")
                        }
                    } catch (_: Throwable) {}
                    // ★★★ 记入「解析期已清」集合（2026-09-26）。
                    //
                    //   用途：数据层再遇到同一 photoId 时**跳过重复判定**。
                    //
                    //   实测冗余（用户问「网络解析层有拦截了，怎么数据层还有这么多拦截记录」）：
                    //   ```
                    //   TTPPARSE-BLOCK : 80      ← 解析期清除
                    //   DEL            : 22      ← 数据层仍反复删同一批
                    //   [DEL] 首项="妈妈带大的孩子..." 真源Hc=106783069 恒定（被删 12 次）
                    //   ```
                    //   原因：解析期清的是那一刻的集合，快手**预取/缓存**里还有旧副本，
                    //   数据层反复碰到就反复删 —— 而它们**永远上不了屏**（源头已清）。
                    //
                    //   详见 `CfhClean.filterResult` 的 SHORTCIRCUIT 说明。
                    try {
                        val pid = CfhProbe.readPhotoId(q)
                        if (!pid.isNullOrBlank()) {
                            synchronized(CfhState.ttpClearedIds) {
                                if (CfhState.ttpClearedIds.size >= 256) CfhState.ttpClearedIds.clear()
                                CfhState.ttpClearedIds.add(pid)
                            }
                        }
                    } catch (_: Throwable) {}
                    // ★ 改为滑动窗口限流（2026-09-26 用户报「拦截记录不全」）
                    //   原为「整进程 80 次」⇒ 打满后静默，用户看到记录不完整。
                    //   现为「每分钟 120 条」⇒ 持续有记录且不淹没日志。
                    if (io.github.angbang852.manjiao.util.RateLimiter.allow("TTPPARSE-BLOCK", 120)) {
                        val cap = try { CfhUtil.readCaption(q) } catch (_: Throwable) { null }
                        // ★★ 必须记「判据」与「昵称」—— 首版只记判定名，
                        //   导致无法判断是否误清（清除率 64%，必须能核验）。
                        val reason = if (verdict == CfhDecide.WhitelistVerdict.DIRTY)
                            (CfhDecide.lastHitReason ?: "?") else "-"
                        val un = try {
                            CfhUtil.readUserName(q, Reflect.readAny(q, "mEntity") ?: q)
                        } catch (_: Throwable) { "" }
                        Logger.evidence(
                            "TTPPARSE-BLOCK",
                            "★解析期判脏写黑名单(${verdict.name}) 判据=$reason " +
                                "昵称=\"${un.take(16)}\" cap=\"${cap?.take(24) ?: "-"}\""
                        )                    }
                } else {
                    // ★★★ TTPPARSE-PASS 单条放行埋点（2026-09-28 修复）：
                    //   原实现只在注释里写了「④ 多长时间放行一个」却没实现，
                    //   导致无法回答用户「多久才能通过一个干净视频」。
                    //   现在 WHITE 放行也打点（含时间戳+昵称），配合 RECV
                    //   间隔即可算出「放行频率」与「干净内容占比」。
                    // ★★★ 2026-09-28 移除了此处的 noteClean 全局登记：
                    //   干净池登记已收窄到「发现页 = HomeFeedResponse」响应
                    //   （hookFeedResponse 拦截内），避免同城/精选等其它页面
                    //   的 WHITE 内容进池（用户实测补位接回了同城数据）。
                    // ★★★ 2026-09-29 v13.19 曾在此恢复全局登记，随后回滚：
                    //   这里是 GSCOLL（Gson CollectionTypeAdapter）= **详情页推荐流**
                    //   通道，不是首页（首页走 HomeFeedResponse.getItems / stag
                    //   ListTypeAdapter）。全局登记会把同城/关注/详情页的 WHITE
                    //   全灌进干净池（用户实测「是不是又把关注和同城一起接上了」）。
                    //   ⇒ 回滚：此处不登记，首页登记仍在 HomeFeedResponse 分支。
                    try {
                        if (io.github.angbang852.manjiao.util.RateLimiter.allow("TTPPARSE-PASS", 120)) {
                            val capP = try { CfhUtil.readCaption(q) } catch (_: Throwable) { null }
                            val unP = try {
                                CfhUtil.readUserName(q, Reflect.readAny(q, "mEntity") ?: q)
                            } catch (_: Throwable) { "" }
                            Logger.evidence(
                                "TTPPARSE-PASS",
                                "★解析期放行(WHITE) 昵称=\"${unP.take(14)}\" cap=\"${capP?.take(22) ?: "-"}\""
                            )
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            // 迭代器不支持 remove 等 —— 记录但不影响解析
            if (CfhState.ttpLog < 80) {
                CfhState.ttpLog++
                Logger.evidence("TTPPARSE-ERR", "清洗异常 ${t.javaClass.name}: ${t.message}")
            }
        }
        if (removed > 0) {
            removedTotal += removed
            if (CfhState.ttpLog < 80) {
                CfhState.ttpLog++
                Logger.evidence(
                    "TTPPARSE",
                    "解析期清除 $removed 条（判脏=$dirtyTotal 待判=$pendingTotal）剩 ${col.size}"
                )
            }
        }
        // ★★★ 各点完整取证（2026-09-26 用户要求）。
        //
        // ## 用户要的四项
        //
        //   ① 多长时间接收一次服务器数据
        //      → `TTPPARSE-RECV` 每条带「距上次」毫秒
        //   ② 拦截点前有没有数据积压（验不验得过来）
        //      → 本批 `size` + 处理耗时；耗时远小于间隔 ⇒ 验得过来
        //   ③ 多少判脏、多少放行
        //      → 本批 + 累计：判脏 / 待判 / 放行
        //   ④ 多长时间放行一个
        //      → 均摊耗时（处理一条要多久）
        try {
            val cost = System.currentTimeMillis() - now
            val recv = r.size + blockedTotal
            CfhState.ttpPassTotal += r.size
            CfhState.ttpDirtyTotal += dirtyTotal
            CfhState.ttpPendingTotal += pendingTotal
            CfhState.ttpBatchTotal += 1
            CfhState.ttpCostTotalMs += cost
            if (CfhState.ttpStatLog < 120) {
                CfhState.ttpStatLog++
                val perItem = if (recv > 0) cost.toDouble() / recv else 0.0
                Logger.evidence(
                    "TTPPARSE-STAT",
                    "③ 本批 收=$recv 放行=${r.size} 挡下=$blockedTotal" +
                        "(判脏=$dirtyTotal 待判=$pendingTotal) " +
                        "④ 耗时=${cost}ms 均摊=${"%.2f".format(perItem)}ms/条 " +
                        "累计: 批次=${CfhState.ttpBatchTotal} " +
                        "档下=${CfhState.ttpDirtyTotal + CfhState.ttpPendingTotal} " +
                        "放行=${CfhState.ttpPassTotal} " +
                        "总耗时=${CfhState.ttpCostTotalMs}ms"
                )
            }
        } catch (_: Throwable) {}
        // ★★★ v13.43 触发点②：网络层每批解析后检查池水位（用户方案
        //   「池子少于50就一直拉取」）。用户滑动时每批内容都从这里过 ⇒
        //   池被消耗后立刻回补；节流在 triggerHomeLoad 内部（1.2s）。
        try { CfhSupply.ensurePoolSupply() } catch (_: Throwable) {}
        // ★★★ 池空自动续拉 + 干净池补位（2026-09-28「精选页内容池空 → 自动续上」用户方案）。
        //
        // ## 触发条件：本批几乎全被拦（剩余 ≤2 且拦掉 ≥4）
        //
        // 实测精选页每批 6-9 条、放行率 9.6% ⇒ 清洗后列表常空/近空，
        // 快手要等自己节奏（RECV 平均 3.1s、峰值 16-40s）才拉下一批 ⇒ 转圈。
        //
        // ## 两套手段（v13.4 实测选型）
        //
        // ① triggerSafeLoadMore 续拉 —— v13.4 实测仅 5 次成功，且拉回来的
        //    下一批**还是脏池**（REFILL-DIAG 全是 isLoading=true 挡下 + 续拉
        //    后 STAT 仍 放行=0 挡下=9）⇒ 续拉无济于事（池子本身是 AI 短剧池）。
        // ② 干净池补位（本版新增，用户原话「把首页发现的内容池接到精选页上」）——
        //    从 [CfhState.cleanPool] 借**近期判 WHITE 的干净内容** add 回本批，
        //    直接填满空池 ⇒ 用户立刻能刷到干净内容，不等快手下一批。
        //    · 只操作网络层 Gson 列表（本函数内 add），不碰 VM/ViewPager 数据源
        //      ⇒ 无「Expected=1000000」崩溃风险。
        //    · 类型严格兼容：池元素与 r 首元素同类才补（避免 ClassCastException）。
        //    · photoId 去重：跳过本批已有元素 ⇒ 不重复灌。
        //    · 池满 100 删最旧；池没货时不补（保底走 ① 续拉）。
        // ★ 开关（2026-09-28「首页池接精选页」用户要求做成开关）：关闭
        //   flt_homerefill 时不补位、不触发首页请求器 —— 整条链路停用，
        //   精选页回到快手原生行为（转圈或拉到脏内容）。
        // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）：
        //   false ⇒ 精选页不再原地补位/不触发首页请求器，回到快手原生行为（可能转圈或拉到脏内容）。
        if (removed >= 4 && r.size <= 2 && Prefs.bool(Prefs.K_FLT_HOMEREFILL, false)) {
            // ★★★ v13.18「自产自销」修复（2026-09-28，用户「拉一轮后又滑不出来」根因）。
            //
            // ## 逆向实证（49980 classes14/26 dex）
            //   · 精选页请求器 dnh.q1 extends kik.f<HomeFeedResponse,QPhoto>
            //     （请求路径 /rest/n/feed/selection），**不 extends hx0.b**
            //   · kik.o0.P1() = `this instanceof hx0.b`（hx0.b extends
            //     kik.f<KSTemplateFeedListResponse,...> 是个**类**）
            //   · kik.o0.load() → R1()：R1() 开头无条件 `this.e = true`，
            //     P1()=false 时**不发请求且 e 永不复位** ⇒ load() 一调就
            //     isLoading=true 永久卡死
            //   ⇒ **所有主动 load() 触发（REFILL/triggerHomeLoadMore/
            //     triggerSafeLoadMore）都是在把精选页请求器弄坏** ——
            //     「服务请求异常」的直接诱因。v13.4-13.16 的「15+ 次
            //     load() 成功」全是假成功+副作用。
            //   ⇒ v13.11「好像可以」根本不是 load() 触发的：证据该时段
            //     REFILL=0/HOMEQ-LOAD=0，池有货全靠 **RESPRET=44 次自然
            //     到达**（用户刷首页时 HomeFeedResponse 源源不断）。
            //
            // ## v13.18 方案：不触发任何 load()，改为「自产自销」
            //   精选页自己的数据（GSCOLL）每批也会有 0-N 条 WHITE 干净内容
            //   被放行（TTPPARSE-PASS 打点）。以前这些 WHITE 只在首页响应
            //   分支登记池。现在把**本批清洗后剩余的 WHITE 元素**也登记进池
            //   —— 精选页刷着刷着，自己的干净内容就积累成池，下一批补位
            //   用它。彻底摆脱「依赖首页 tab 拉数据」的死结。
            //   · 只在「本批几乎全拦需补位」时登记（本函数触发条件），
            //     不是全局登记 ⇒ 不会把同城/其它页面的 WHITE 灌进来。
            //   · noteClean 自带 QPhoto 类型校验 + pid 去重 + 池上限 100。
            // ★★★ v13.22 恢复「池空 → 触发首页 load()」（2026-09-29 用户方案回归）：
            //   v13.18 的「绝不 load()」依据是 49980 逆向（dnh.q1 非 hx0.b 子类，
            //   P1() 门锁死请求器）—— **50388 上失效**：kik.o0/rmk.o0/hx0.b 全部
            //   NOT FOUND（HOMEQ-DIAG 实证），50388 请求器基类是 smk.f（无 P1 门）。
            //   ⇒ 精选页池空时**触发首页请求器 load()** 拉首页数据：
            //     首页数据到达（MimpBripGlilt VmList/DataSource）→ noteClean 登记
            //     → 干净池 → 下一批精选页补位。这就是用户定稿方案
            //     「精选页内容池为空时自动用首页发现的内容池续上」。
            //   同时保留自产自销（本批 WHITE 入池作种子），双保险。
            // ★★★ v13.62 停用网络层补位（2026-09-30 用户实测「滑几下就滑不出来」根治）：
            //   ## 两套补位在抢同一个池
            //   · 网络层 `refillFromCleanPool`：**破坏性消费**（removeAt 取走不归还）
            //   · 渲染出口补位（CfhFeedHook.installVmListRet / RENDERFILL）：
            //     **非破坏性**（只读快照），而且**真的能改变上屏数据**
            //   实测池被前者吃空：`POOLSTAT 当前池=24` → 6 分钟后
            //   `VMLIST-DIAG … 池=0`，随后 `RENDERFILL` 因无货可补**彻底停摆**
            //   ⇒ 用户「滑几下就滑不出来了」。
            //   ## 结论
            //   网络层补位补的是「原始批次」，快手不采用（补了也白补），却把
            //   池吃光；渲染出口补位才是唯一有效的那套。⇒ 停用网络层这套，
            //   把池完整留给渲染出口。池的来源仍是首页（用户滑首页即入池）。
            val refillPool = 0
            if (refillPool <= 0) {
                // 池没货：本批剩余 WHITE 直接登记进池（自产自销种子），
                // 同时触发首页请求器 load() 拉首页数据续池（v13.22 恢复）。
                try {
                    for (e in r) {
                        if (e == null) continue
                        try { CfhState.noteClean(e) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
                if (CfhState.cleanPoolRefillLog < 60) {
                    Logger.evidence(
                        "REPOOL-SEED",
                        "★池空：本批 WHITE 自产入池 种=${r.size} 池=${synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }}"
                    )
                }
                // ★★★ v13.38 删除「主动拉首页数据」（2026-09-30 用户定案）：
                //   池是**共享的**（cleanPool 唯一）—— 首页滑到的干净内容本来就
                //   已经进池，不存在「首页池没接到精选页」的问题。「主动拉首页
                //   数据」（triggerHomeLoadMore/HOMEQ/dnh.q1/arh.q1 那套）是早期
                //   「两池不同源」错误假设的产物，纯多余，且带副作用
                //   （触发 load 可能弄坏请求器、拉回精选页 AI 短剧）。
                //   已删除：此处调用 + installVmList 的池水位自动拉（v13.36）。
                //   保留函数本体供诊断，但**不再有任何调用点**。
            }
        }
        return removed
    }

    /**
     * 从干净池借近期 WHITE 内容补位本批（「首页池接精选页」核心实现）。
     *
     * @return 实际补入条数；0 表示池没货或类型不匹配（调用方走续拉保底）。
     */
    private fun refillFromCleanPool(r: MutableCollection<*>, qpCls: Class<*>?): Int {
        // ★★★ 2026-09-28 实测 REPOOL=0 根因修复：
        //   原实现 `val first = r.firstOrNull { it != null } ?: return 0` ——
        //   本批 9 条全被删 ⇒ r 是**空列表** ⇒ first=null ⇒ 直接 return 0，
        //   补位从未执行，只能走 REFILL 续拉（又被 isLoading 挡）⇒ 死路。
        //   修复：**空列表不 return 0** —— 空列表没有类型锚，但池里只存
        //   直接 QPhoto 实例（noteClean 已按 qpClassRef 过滤），而精选页
        //   网络容器 List<QPhoto> 元素类型就是 QPhoto ⇒ 直接用 qpCls 做锚，
        //   池元素只要是 QPhoto 即可安全 add。非空列表仍按首元素类型匹配。
        val isQpAnchor = qpCls != null && (r.isEmpty() || qpCls.isInstance(r.firstOrNull { it != null }))
        if (r.isNotEmpty() && !isQpAnchor) {
            val first = r.firstOrNull { it != null } ?: return 0
            // 首元素非 QPhoto（包装列表）→ 只能按同包装类匹配
            if (qpCls != null) {
                // 首元素是非 QPhoto 包装 ⇒ 池里只有 QPhoto ⇒ 无法安全补位
                if (!qpCls.isInstance(first)) return 0
            }
        }
        // ★★★ v13.41 补位量按池水位自适应（2026-09-30 用户「精选页刷不出来」）：
        //   ## 问题
        //   原实现每批固定补 (6-r.size) = 最多 6 条。实测（REPOOL 证据）：
        //     池=15 → 补6 → 池=9 → 补6 → 池=4 → 补5 → 池=2 → 补2 → 池=1 → 池=0
        //   14 秒内把 15 条储备全部抽干 —— 精选页内容 100% 判脏（删空），
        //   池只出不进（用户不在首页滑就没有补给），于是滑几屏后池=0、
        //   精选页彻底刷不出来。
        //   ## 修复（只改消耗节奏，不动判定分层）
        //   池是有限储备 ⇒ 池越小越省着用，让「有货」的时间尽量长：
        //     池 ≥ 20：补 6（储备充足，照常供满一屏）
        //     池 10-19：补 4
        //     池 5-9：补 2
        //     池 1-4：补 1（保命，留种）
        //   注意：这里**不是**「池小就不补」—— 池只要 ≥1 就继续补，
        //   保证精选页永远有内容可刷；只是抽得慢些，等首页补给回血。
        val poolSizeNow = synchronized(CfhState.cleanPool) { CfhState.cleanPool.size }
        // ★★★ v13.43 触发点①：池不足 50 就主动拉主页数据（用户方案
        //   「池子少于50就一直拉取」）。节流在 triggerHomeLoad 内部（1.2s），
        //   所以这里每次补位都调用是安全的 —— 池被抽的瞬间就开始回补。
        if (poolSizeNow < CfhSupply.POOL_TARGET) {
            try { CfhSupply.ensurePoolSupply() } catch (_: Throwable) {}
        }
        // ★★★ v13.48 撤销 v13.41 的自适应上限（2026-09-30 用户实证致命 bug）：
        //   用户原话：「你说池子进了25条，可是精选页半天可只有1条视频」
        //   v13.41 的「池小就少补」（池<5 只补 1 条）造成了**恶性循环**：
        //     池小 → 只补 1 条 → 精选页只有 1 条视频 → 用户滑不出 →
        //     池被抽干后更小 → 补得更少（REPOOL 实证「本批剩 1」）
        //   ⇒ 改为「有多少补多少」：池 ≥6 补满一屏（6 条），池 <6 就把
        //   池里的**全部**补上（至少 1）。池是给人用的，屯着不补等于没有。
        val capByPool = if (poolSizeNow >= 6) 6 else maxOf(poolSizeNow, 1)
        val need = minOf((6 - r.size).coerceIn(1, 6), capByPool)
        var added = 0
        val existing = HashSet<String>()
        // ★ v13.33 补位作者分散集合（同批已补/已存在的作者，每作者最多 1 条）
        val refilledKeys = HashSet<String>()
        try {
            for (e in r) {
                val pid = try { CfhProbe.readPhotoId(e) } catch (_: Throwable) { null }
                if (!pid.isNullOrBlank()) existing.add(pid)
                // 已有元素作者也计入分散（与快手「列表不出现同作者紧挨」一致）
                try {
                    if (e != null) {
                        val eEnt = Reflect.readAny(e, "mEntity") ?: e
                        val eKey = io.github.angbang852.manjiao.hook.CfhUtil.readUserKey(e, eEnt)
                        if (eKey.isNotBlank()) refilledKeys.add(eKey)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        synchronized(CfhState.cleanPool) {
            // 从尾部取（最近 WHITE 优先）
            for (i in CfhState.cleanPool.size - 1 downTo 0) {
                if (added >= need) break
                val cand = CfhState.cleanPool[i] ?: continue
                // 类型兼容：池元素必须 QPhoto（noteClean 已过滤），
                // 且与列表类型锚匹配（空列表锚=qpCls，非空列表锚=首元素）
                val typeOk = when {
                    qpCls != null && qpCls.isInstance(cand) -> true
                    else -> false
                }
                if (!typeOk) continue
                val cpid = try { CfhProbe.readPhotoId(cand) } catch (_: Throwable) { null }
                if (cpid.isNullOrBlank() || existing.contains(cpid)) continue
                // ★★★ v13.33 补位按作者分散（2026-09-30 用户「视频又开始循环」）：
                //   池被少数头部作者占满 ⇒ 补位全是一个人的视频（REPOOL 实证
                //   「补=[肛肠科张浩医|肛肠科张浩医|…]」6 条同作者）⇒ 用户滑几屏
                //   就重复。同一批补位里，**每个作者最多补 1 条** —— 跳过
                //   本批已补位过的作者，让不同作者的内容穿插出现。
                val candEnt = try { Reflect.readAny(cand, "mEntity") ?: cand } catch (_: Throwable) { cand }
                val candKey = try { io.github.angbang852.manjiao.hook.CfhUtil.readUserKey(cand, candEnt) } catch (_: Throwable) { "" }
                if (candKey.isNotBlank() && refilledKeys.contains(candKey)) continue
                // ★★★ v13.25 补位重验（2026-09-30 用户质问「带了标签为什么还进池」）：
                //   池里可能存在「判定门开启前」放行的残留（当时 flt_ai=false，
                //   带 AI 声明的也判白进池）。现在用户开了 flt_ai=true（AI 总门）
                //   + flt_ai_suspect=true（疑似门），这些残留内容字段已回填，
                //   补位出去会被渲染层再拦 ⇒ 永远上不了屏 ⇒ 「滑不出」。
                //   补位前**重新判定**：非 WHITE 的候选直接清出池（不补位），
                //   保证补位内容渲染层一定放行 —— 与进池判定同口径，杜绝
                //   「网络层放行进池 → 渲染层再拦」的不一致。
                val vAgain = try {
                    io.github.angbang852.manjiao.hook.CfhDecide.judgeWhitelist(cand)
                } catch (_: Throwable) { io.github.angbang852.manjiao.hook.CfhDecide.WhitelistVerdict.PENDING }
                if (vAgain != io.github.angbang852.manjiao.hook.CfhDecide.WhitelistVerdict.WHITE) {
                    // 残留脏内容：清出池（池只保留真干净），继续找下一个
                    try { CfhState.cleanPool.removeAt(i) } catch (_: Throwable) {}
                    // ★★★ v13.81 **严格档硬修**（真机症状：同一段视频「隔了几屏又出现」）。
                                                            //   原来这里把 `cleanPoolIds` 的永久去重标记也抹掉了
                                                            //   ⇒ 被取走的条目过一会儿能被**首页重新拉回池**
                                                            //   ⇒ 再次上屏 ⇒ 用户看到的「隔几屏又出现」。
                                                            //
                                                            //   模块自己的设计笔记（CfhState.kt:327-333）**早就写明**
                                                            //   「循环根因 = refill 补位取走内容时 cleanPoolIds.remove(cpid)
                                                            //     抹掉了去重标记；② 补位成功不再 remove，保留永久去重标记」
                                                            //   —— 结论写下了，这两处代码却一直没改。
                                                            //   保留标记 = 看过的永久不再进池，与用户定案
                                                            //   「绝不重复 > 不断流」完全一致。
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                (r as MutableCollection<Any?>).add(cand)
                existing.add(cpid)
                if (candKey.isNotBlank()) refilledKeys.add(candKey)
                // ★★★ 2026-09-28 实测「又是关注页的视频」根因修复：
                //   补位后**必须从池移除该元素**。
                //   原实现只 add 不移除 ⇒ 同一批内容被反复补位到每一批
                //   （证据：REPOOL 连续 8+ 次「当前池=6 最新入库=大冰」，
                //   池纹丝不动）⇒ 用户刷一批看到大冰、下一批还是大冰，
                //   误以为「又是关注页的视频」。移除后每批补位都是**新内容**，
                //   池被消费光才触发首页请求器续拉。
                // ★★★ 2026-09-28 v13.15「进了池子就固定了」修复：
                //   REPOOL 显示「当前池=11」恒不变 ⇒ 移除没生效，但 added 照样
                //   +6（add 成功、removeAt 抛异常被 catch 吞掉）⇒ 池只进不出，
                //   每批补位都是同一批 ⇒ 滑一轮后面全是重复 ⇒ 用户「拉了一轮
                //   后面又滑不出来」。修复：移除失败**不算入 added**，并记录
                //   异常证据（此前静默，证据里根本看不到移除失败）。
                // ★★★ v13.29 反循环（2026-09-30）：补位取走**只 removeAt 元素**，
                //   **不再 remove cleanPoolIds** —— 保留永久去重标记。否则首页
                //   同一列表每次被访问 noteCleanAll 看到同样内容又能重新入池，
                //   池永远被同样几条填满 ⇒ 循环刷同一批。保留标记 + noteClean
                //   时间窗双保险 ⇒ 池只收真正的新内容，循环从根上断掉。
                val remOk = try {
                    CfhState.cleanPool.removeAt(i)
                    true
                } catch (re: Throwable) {
                    if (CfhState.cleanPoolRefillLog < 60) {
                        Logger.evidence("REPOOL-REMFAIL", "补位移除失败 i=$i 池size=${CfhState.cleanPool.size} err=${re.javaClass.simpleName}")
                    }
                    false
                }
                if (remOk) added++
            }
        }
        if (added > 0 && CfhState.cleanPoolRefillLog < 60) {
            CfhState.cleanPoolRefillLog++
            val un = try {
                val last = CfhState.cleanPool.lastOrNull()
                if (last != null) CfhUtil.readUserName(last, Reflect.readAny(last, "mEntity") ?: last) else ""
            } catch (_: Throwable) { "" }
            // ★ v13.15 诊断：补位内容指纹（本次 add 进 r 的昵称列表）。
            //   直接回答「每批补的是不是同一批」—— 若每轮 REPOOL 的
            //   指纹相同 ⇒ 池没消耗/登记重复，用户看到的就是同一批。
            val addedNames = try {
                r.filter { it != null }.takeLast(added).joinToString("|") { e ->
                    val nm = try { CfhUtil.readUserName(e!!, Reflect.readAny(e, "mEntity") ?: e) } catch (_: Throwable) { "?" }
                    nm.take(6)
                }
            } catch (_: Throwable) { "?" }
            Logger.evidence(
                "REPOOL",
                "★干净池补位 +$added 条 当前池=${CfhState.cleanPool.size} 本批剩 $need " +
                    "最新入库=\"${un.take(12)}\" 补=[$addedNames]"
            )
        }
        return added
    }

    /**
     * ★★★ v13.25 渲染层补位（2026-09-30 用户「三指标实测」断点修复）：
     * REPOOL 补位 add 进的是 Gson 解析层列表 r（网络层容器），但 UI 渲染
     * 用的是 SlidePlayViewModel 方法参数里的 ArrayList（VMSHOW-CALL 实证
     * 「调用 Q0 参数=ArrayList」）—— 两个容器不是同一个，所以干净内容
     * 进了池、补进了 r，却永远到不了屏幕（REPOOL 循环补位 8 次实证）。
     * 本函数供 VMSHOW 拦截点调用：对**渲染列表**（方法参数 ArrayList）
     * 从干净池补位 —— 补位元素 add 进渲染列表后由 UI 直接消费。
     * 复用 refillFromCleanPool 的「尾部取 + WHITE 重验 + 移除」逻辑，
     * 但锚类型放宽（渲染列表元素类型就是 QPhoto）。
     * @return 实际补进渲染列表的条数
     */
    fun refillRenderList(r: MutableList<*>, qpCls: Class<*>?): Int {
        return try {
            synchronized(CfhState.cleanPool) {
                val need = 3 // 渲染列表按需少量补位，避免一次灌太多
                var added = 0
                val existing = HashSet<String>()
                val refilledKeys = HashSet<String>()
                for (e in r) {
                    val pid = try { CfhProbe.readPhotoId(e) } catch (_: Throwable) { null }
                    if (!pid.isNullOrBlank()) existing.add(pid)
                    // ★ v13.33 已有元素的作者也计入分散（跟快手一致：列表里
                    //   不会出现同一个作者两条紧挨）
                    try {
                        val eEnt = Reflect.readAny(e!!, "mEntity") ?: e
                        val eKey = io.github.angbang852.manjiao.hook.CfhUtil.readUserKey(e, eEnt)
                        if (eKey.isNotBlank()) refilledKeys.add(eKey)
                    } catch (_: Throwable) {}
                }
                for (i in CfhState.cleanPool.size - 1 downTo 0) {
                    if (added >= need) break
                    val cand = CfhState.cleanPool[i] ?: continue
                    // 渲染列表锚：QPhoto 直接匹配；qpCls 未知时跳过
                    if (qpCls == null) break
                    if (!qpCls.isInstance(cand)) continue
                    val cpid = try { CfhProbe.readPhotoId(cand) } catch (_: Throwable) { null }
                    if (cpid.isNullOrBlank() || existing.contains(cpid)) continue
                    // ★★★ v13.33 作者分散（用户「发现页自己刷没发现紧跟着同一个作者」）：
                    //   快手源本身作者分散；我们补位从池尾部取，池被少数作者占满
                    //   ⇒ 一批补位全是一个作者（REPOOL 实证「补=[肛肠科张浩医|肛肠科张浩医|…]」）。
                    //   与快手同规则：**本批已出现过的作者不再补位**（每个作者最多 1 条/批）。
                    val candEnt = try { Reflect.readAny(cand, "mEntity") ?: cand } catch (_: Throwable) { cand }
                    val candKey = try { io.github.angbang852.manjiao.hook.CfhUtil.readUserKey(cand, candEnt) } catch (_: Throwable) { "" }
                    if (candKey.isNotBlank() && refilledKeys.contains(candKey)) continue
                    // ★ 与 refillFromCleanPool 同口径：补位前重验 WHITE（残留脏清出池）
                    val vAgain = try { io.github.angbang852.manjiao.hook.CfhDecide.judgeWhitelist(cand) }
                        catch (_: Throwable) { io.github.angbang852.manjiao.hook.CfhDecide.WhitelistVerdict.PENDING }
                    if (vAgain != io.github.angbang852.manjiao.hook.CfhDecide.WhitelistVerdict.WHITE) {
                        try { CfhState.cleanPool.removeAt(i) } catch (_: Throwable) {}
                        // ★★★ v13.81 **严格档硬修**（真机症状：同一段视频「隔了几屏又出现」）。
                                                            //   原来这里把 `cleanPoolIds` 的永久去重标记也抹掉了
                                                            //   ⇒ 被取走的条目过一会儿能被**首页重新拉回池**
                                                            //   ⇒ 再次上屏 ⇒ 用户看到的「隔几屏又出现」。
                                                            //
                                                            //   模块自己的设计笔记（CfhState.kt:327-333）**早就写明**
                                                            //   「循环根因 = refill 补位取走内容时 cleanPoolIds.remove(cpid)
                                                            //     抹掉了去重标记；② 补位成功不再 remove，保留永久去重标记」
                                                            //   —— 结论写下了，这两处代码却一直没改。
                                                            //   保留标记 = 看过的永久不再进池，与用户定案
                                                            //   「绝不重复 > 不断流」完全一致。
                        continue
                    }
                    @Suppress("UNCHECKED_CAST")
                    (r as MutableList<Any?>).add(cand)
                    existing.add(cpid)
                    if (candKey.isNotBlank()) refilledKeys.add(candKey)
                    // ★ v13.29 同 refillFromCleanPool：只 removeAt 元素，不 remove
                    //   cleanPoolIds（保留永久去重标记，反循环）
                    val remOk = try {
                        CfhState.cleanPool.removeAt(i)
                        true
                    } catch (_: Throwable) { false }
                    if (remOk) added++
                }
                added
            }
        } catch (_: Throwable) { 0 }
    }
}
