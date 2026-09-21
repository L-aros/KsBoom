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

    fun readCaption(qp: Any?): String? {
        if (qp == null) return null
        val ent = Reflect.readAny(qp, "mEntity") ?: return null
        val cm = Reflect.readAny(ent, "mCommonMeta") ?: return null
        return Reflect.readString(cm, "mCaption")
    }

    fun aiDisclaimerContent(pm: Any?): String? {
        if (pm == null) return null
        val dis = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null } ?: return null
        val c = try { Reflect.readAny(dis, "content") as? String } catch (_: Throwable) { null } ?: return null
        if (c.isBlank()) return null
        if (c.contains("AI") || c.contains("ai生成", true) || c.contains("AIGC") || c.contains("人工智能")) return c
        return null
    }

    fun isStructClsName(cn: String): Boolean =
        cn.contains("Presenter") || cn.contains("Callback") || cn.contains("Fragment") ||
            cn.contains("Interceptor") || cn.contains("Executer") || cn.contains("Executor")
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
        // mCoronaInfo 内部（快�?AI 生成内容标识体系�?
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
        // ExtendableModelMap / 动�?map �?
        for (tag in listOf("metaExtContainer", "mExtraMap", "mExtData")) {
            val em = try { Reflect.readAny(ent, tag) } catch (_: Throwable) { null } ?: continue
            if (em is Map<*, *>) {
                for ((k, v) in em.entries) {
                    val ks = k.toString()
                    if (ks.contains("ai", true) || ks.contains("gen", true)) put("map[" + tag + "]", ks, v)
                }
            } else {
                // �?Map：枚举其字段里值非空的小写�?
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
