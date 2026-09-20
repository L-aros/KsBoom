package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第三步：对象图定位与去重（2026-09 S3）。
// 全图找 QP/脏实体/Live 字段/Fragment、photoId 身份读取、批次去重、方法缓存。
// 纯查询叶节点——只依赖 CfhState/CfhUtil，不反向依赖任何清洗对象。
object CfhProbe {
    fun findQpInObject(obj: Any, depth: Int = 0): Any? {
        val qpClass = CfhState.qpClassRef ?: return null
        if (qpClass.isInstance(obj)) return obj
        if (depth >= 2) return null

        if (obj is Collection<*>) {
            for (item in obj) {
                if (item != null) {
                    val r = findQpInObject(item, depth + 1)
                    if (r != null) return r
                }
            }
            return null
        }
        // ★ 反射成本核心优化：字段表按类缓存（Reflect.nonStaticFields），不再每次
        // declaredFields 复制数组；isAssignableFrom→isInstance 少一层类查找
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (f in Reflect.nonStaticFields(c!!)) {
                try {
                    val v = f.get(obj) ?: continue
                    if (qpClass.isInstance(v)) return v
                    if (depth < 1 && v.javaClass.name.contains(".") && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("android.")) {
                        val r = findQpInObject(v, depth + 1)
                        if (r != null) return r
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        return null
    }
    fun findDirtyEntityInHolder(holder: Any, depth: Int = 0): Any? {
        if (depth >= 5) return null
        val name = holder.javaClass.name
        // 直接命中：类名含 live（避开 LiveStreamViewModel 等无害/含 Live 的工具类）
        if ((name.contains("Live") || name.contains("Ad")) && !name.contains("ViewModel") && !name.contains("LiveData")) {
            // ★ 命中即 return（审阅 2026-09）：原 dirtyEntSeen.add 返回值作放行条件——
            // 同名类第一张脏卡 return 后，后续同类脏卡 add 失败落入字段扫描大概率
            // 返回 null → 第二张起全部漏拦上屏。add 结果只用于节流打日志
            if (holder is Collection<*>) { /* 集合本身不判脏，看元素 */ } else {
                if (CfhState.dirtyEntSeen.add(name)) Logger.d("dirtyEnt hit: $name")
                return holder
            }
        }
        if (holder is Collection<*>) {
            for (item in holder) {
                if (item != null) {
                    val r = findDirtyEntityInHolder(item, depth + 1)
                    if (r != null) return r
                }
            }
            return null
        }
        if (depth >= 4) return null
        var c: Class<*>? = holder.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (f in Reflect.nonStaticFields(c!!)) {
                try {
                    val v = f.get(holder) ?: continue
                    if (v === holder) continue
                    val vn = v.javaClass.name
                    // 跳过 JDK/安卓容器实现 与 巨型 View 树，防爆栈
                    if (vn.startsWith("java.") || vn.startsWith("android.") || vn.startsWith("kotlin.")) {
                        if (v is Collection<*>) { val r = findDirtyEntityInHolder(v, depth + 1); if (r != null) return r }
                        continue
                    }
                    if ((vn.contains("Live") || vn.contains("Ad")) && !vn.contains("ViewModel") && !vn.contains("LiveData")) {
                        if (CfhState.dirtyEntSeen.add(vn)) Logger.d("dirtyEnt hit: $vn")
                        return v
                    }
                    val r = findDirtyEntityInHolder(v, depth + 1)
                    if (r != null) return r
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        return null
    }
    fun findLiveWindowField(root: Any?): String? {
        if (root == null) return null
        try {
            val visited = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
            val queue = ArrayDeque<Pair<Any, Int>>()
            queue.addLast(root to 0)
            visited.add(root)
            while (queue.isNotEmpty()) {
                val (obj, depth) = queue.removeFirst()
                if (depth > 4) continue
                var c: Class<*>? = obj.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(obj)
                            if (v == null) continue
                            val fn = f.name.lowercase()
                            val cn = v.javaClass.name
                            val liveHit = cn.contains("Live") && (cn.contains("Info") || cn.contains("Status") || cn.contains("Play") || cn.contains("Feed") || cn.contains("Window") || cn.contains("Guide") || cn.contains("Preview"))
                            if (liveHit || fn.contains("livestatus") || fn.contains("isliving") || fn.contains("living") && (fn.contains("user") || fn.contains("author"))) {
                                return "[${c.simpleName}]${f.name}:${v.javaClass.simpleName}"
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
                if (depth < 3 && !obj.javaClass.name.startsWith("java.")) {
                    var c2: Class<*>? = obj.javaClass
                    var l2 = 0
                    while (c2 != null && c2 != Any::class.java && l2 < 2) {
                        for (f in c2!!.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            try {
                                f.isAccessible = true
                                val v = f.get(obj) ?: continue
                                if (!v.javaClass.isPrimitive && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("[") && visited.add(v)) {
                                    queue.addLast(v to depth + 1)
                                }
                            } catch (_: Throwable) {}
                        }
                        c2 = c2.superclass; l2++
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }
    fun findFragInHolder(holder: Any?): Any? {
        if (holder == null) return null
        try {
            var c: Class<*>? = holder.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    val ft = f.type.name
                    if (ft.contains("Fragment") && !ft.contains("FragmentManager") && !ft.contains("FragmentTransaction")) {
                        try { f.isAccessible = true; f.get(holder)?.let { return it } } catch (_: Throwable) {}
                    }
                }
                c = c.superclass; lvl++
            }
        } catch (_: Throwable) {}
        return null
    }
    fun cachedMethod(cls: Class<*>, name: String, vararg pt: Class<*>): java.lang.reflect.Method? {
        val key = cls.name + "#" + name + "#" + pt.size + "#" + pt.joinToString(",") { it.name }
        CfhState.methodCache[key]?.let { return it }
        val m = try { cls.getDeclaredMethod(name, *pt) } catch (_: Throwable) { null } ?: return null
        m.isAccessible = true
        CfhState.methodCache[key] = m
        return m
    }
    fun dedupeInsertBatch(batch: MutableList<Any?>?, tag: String): Int {
        if (batch == null || batch.isEmpty()) return 0
        if (CfhState.dedupeProbe < 3) {
            CfhState.dedupeProbe++
            val f = batch.firstOrNull()
            Logger.d("knhb dedupe probe $tag size=${batch.size} cls=${f?.javaClass?.name} id=${readPhotoId(f)} hist=${synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.size }}")
        }
        var dup = 0
        val inBatch = HashSet<String>()
        val victims = ArrayList<Any?>()
        for (el in batch) {
            val id = readPhotoId(el) ?: continue
            if (id.isBlank()) continue
            val seenBefore = synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.contains(id) }
            // 历史已供给 或 本批次内重复 → 删
            if (seenBefore || !inBatch.add(id)) victims.add(el)
        }
        // 护栏：删后至少留 1（原本 >1 时），防 replaceAll 收到空列表
        if (victims.isNotEmpty() && batch.size - victims.size < 1) victims.removeAt(victims.size - 1)
        if (victims.isEmpty()) {
            CfhState.dedupeStarve.set(0)
            // 幸存项记入历史
            synchronized(CfhState.seenPhotoIds) {
                for (el in batch) {
                    val id = readPhotoId(el) ?: continue
                    if (id.isNotBlank()) {
                        CfhState.seenPhotoIds.remove(id); CfhState.seenPhotoIds.add(id)
                    }
                }
                if (CfhState.seenPhotoIds.size > 500) {
                    val it = CfhState.seenPhotoIds.iterator()
                    var drop = CfhState.seenPhotoIds.size - 500
                    while (drop-- > 0 && it.hasNext()) { it.next(); it.remove() }
                }
            }
            return 0
        }
        for (v in victims) { try { batch.remove(v); dup++ } catch (_: Throwable) {} }
        // ★ 饥饿自愈（2026-09-08 用户报「长时间无更多滑不出」）：长时间刷后 hist 攒满、服务端推荐池
        // 轮回返回看过的视频 → dedupe 全删（left<=1）→ 列表只剩 1 项轮转 → prefetch/loadMore 无限循环
        // 但供给不涨。对策：连续 4 批 dedupe 删后 left<=1（供给无效）→ 清空 hist 重开一轮
        // （接受一轮重复换供给恢复，不卡死）；left>=2 正常批次计数清零。
        if (batch.size <= 1) {
            val st = CfhState.dedupeStarve.incrementAndGet()
            if (st >= 4) {
                synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.clear() }
                Logger.always("dedupe starved 4 batches (hist reset) -> supply recover")
                CfhState.dedupeStarve.set(0)
            }
        } else CfhState.dedupeStarve.set(0)
        if (CfhState.dedupeDiag < 30) {
            CfhState.dedupeDiag++
            Logger.d("knhb dedupe $tag dup=$dup left=${batch.size} starve=${CfhState.dedupeStarve.get()} hist=${synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.size }}")
        }
        // 幸存项记入历史
        synchronized(CfhState.seenPhotoIds) {
            for (el in batch) {
                val id = readPhotoId(el) ?: continue
                if (id.isNotBlank()) {
                    CfhState.seenPhotoIds.remove(id); CfhState.seenPhotoIds.add(id)
                }
            }
            if (CfhState.seenPhotoIds.size > 500) {
                val it = CfhState.seenPhotoIds.iterator()
                var drop = CfhState.seenPhotoIds.size - 500
                while (drop-- > 0 && it.hasNext()) { it.next(); it.remove() }
            }
        }
        return dup
    }
    fun readPhotoId(qp: Any?): String? {
        if (qp == null) return null
        if (CfhState.photoIdCache.containsKey(qp)) return CfhState.photoIdCache[qp]
        val cls = qp.javaClass
        val m: java.lang.reflect.Method? = when {
            CfhState.pidMNeg.contains(cls) -> null
            CfhState.pidMCache.containsKey(cls) -> CfhState.pidMCache[cls]
            else -> try {
                cls.getMethod("getPhotoId").apply { isAccessible = true }.also { CfhState.pidMCache[cls] = it }
            } catch (_: Throwable) { CfhState.pidMNeg.add(cls); null }
        }
        val id = try { m?.invoke(qp) as? String } catch (_: Throwable) { null }
        // ★ 上限 4000→800：IdentityHashMap 强引用 QPhoto（重对象），800 已覆盖去重
        // 窗口且内存尖峰小 5 倍（审阅 2026-09）
        if (CfhState.photoIdCache.size > 800) CfhState.photoIdCache.clear()
        CfhState.photoIdCache[qp] = id
        return id
    }
}
