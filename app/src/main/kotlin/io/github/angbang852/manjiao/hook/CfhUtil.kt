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

}
