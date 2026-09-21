package io.github.angbang852.manjiao.hook

import android.os.Looper
import io.github.angbang852.manjiao.util.Logger

// ★ ContentFilterHook 深拆第三步：供给恢复（2026-09 S3）。
// loadMore/refresh 反射调用链与 hasMore 健康检查——列表被删短后的续拉/重拉兜底。
object CfhSupply {
    fun findLoadTarget(inst: Any): Any? {
        val selfHas = try { inst.javaClass.getMethod("load"); true } catch (_: Throwable) { false }
        if (selfHas) return inst
        var fc: Class<*>? = inst.javaClass
        var flvl = 0
        while (fc != null && fc != Any::class.java && flvl < 6) {
            for (f in fc!!.declaredFields) {
                try {
                    f.isAccessible = true
                    val req = f.get(inst) ?: continue
                    if (req is Collection<*> || req is android.view.View) continue
                    val has = try { req.javaClass.getMethod("load"); true } catch (_: Throwable) { false }
                    if (has) return req
                } catch (_: Throwable) {}
            }
            fc = fc.superclass; flvl++
        }
        return null
    }
    fun triggerLoadMore(): Boolean {
        // ★ 线程闸门：同 triggerRefresh，后台线程调用一律投回主线程执行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            CfhState.handler.post { try { triggerLoadMore() } catch (_: Throwable) {} }
            return true
        }
        val now = System.currentTimeMillis()
        if (now - CfhState.lastLoadMoreTime < 800) return false
        val inst = CfhState.knhbInst?.get()
        if (inst == null) { Logger.d("loadMore SKIP: knhbInst=null -> fallback refresh"); return triggerRefresh() }
        val target = findLoadTarget(inst)
        if (target == null) { Logger.d("loadMore no target -> fallback refresh"); return triggerRefresh() }
        val hasMore = try {
            val hm = target.javaClass.getMethod("hasMore"); hm.isAccessible = true
            hm.invoke(target) as? Boolean ?: true
        } catch (_: Throwable) { true }
        if (!hasMore) {
            Logger.d("loadMore hasMore=false -> refresh recover")
            val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }
            if (rm != null) {
                try { rm.isAccessible = true; rm.invoke(target); CfhState.lastLoadMoreTime = now; return true } catch (_: Throwable) {}
            }
            return triggerRefresh()
        }
        val isLoading = try {
            val il = target.javaClass.getMethod("isLoading"); il.isAccessible = true
            il.invoke(target) as? Boolean ?: false
        } catch (_: Throwable) { false }
        if (isLoading) {

            // ★ 启动窗（冷启后 15s 内）收紧等待阈值（实证 2026-09 probe4 首屏）：
            // 「prefetch short list=2 → loadMore in flight >5s → skip: request in flight
            //   → prefetch short list=0」——首屏 7 条里 5 条脏只剩 2 条，而补位请求卡在
            // 宿主自己的 in-flight 状态里死等 5s，这 2 秒空窗分页器只有 2 条可翻，
            // 用户看到的第一/第二条就是这残存的 2 条（含未回填的空壳项）。
            // 启动窗内改用 1.2s 阈值，尽快走 refresh 兜底把数据补进来。
            val bootWindow = now - CfhState.processStartAt < 15_000L
            val waitMs = if (bootWindow) 1_200L else 5_000L
            if (now - CfhState.lastLoadMoreTime > waitMs) {

                Logger.always("loadMore in flight >${waitMs}ms (boot=$bootWindow) -> hist reset + refresh recover")

                synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.clear() }

                val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }

                if (rm != null) { try { rm.isAccessible = true; rm.invoke(target); CfhState.lastLoadMoreTime = now; return true } catch (_: Throwable) {} }

                return triggerRefresh()

            }

            Logger.d("loadMore skip: request in flight")

            return true

        }
        return try {
            val m = target.javaClass.getMethod("load")
            m.isAccessible = true
            m.invoke(target)
            CfhState.lastLoadMoreTime = now
            Logger.d("loadMore called on ${target.javaClass.name} (hasMore=true)")
            true
        } catch (_: Throwable) { triggerRefresh() }
    }
    fun triggerRefresh(): Boolean {
        // ★ 线程闸门（审阅 2026-09）：本方法会从 cleanExecutor 后台线程（sanitizeList
        // all-dirty 兜底、loadMore 恢复）调用，反射 invoke 宿主 VM 刷新方法必须在主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            CfhState.handler.post { try { triggerRefresh() } catch (_: Throwable) {} }
            return true
        }
        val now = System.currentTimeMillis()
        if (now - CfhState.lastRefreshTime < 800) return false
        CfhState.lastRefreshTime = now
        val vm = CfhState.vmRef
        if (vm == null) { Logger.always("refresh SKIP: vmRef=null (VM not found yet)"); return false }
        for (name in arrayOf("v0", "B1", "C1", "E1", "K1", "W0", "X0", "Y0", "z0", "y0", "refresh", "loadMore")) {
            val m = CfhProbe.cachedMethod(vm.javaClass, name) ?: continue
            try {
                m.invoke(vm)
                Logger.d("refresh called: $name")
                return true
            } catch (_: Throwable) {}
        }
        val sigKeys = arrayOf("refresh", "load", "more", "feed", "page", "fetch", "reload", "request")
        var c: Class<*>? = vm.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (m in c!!.declaredMethods) {
                if (m.parameterTypes.isNotEmpty() || m.returnType != Void.TYPE) continue
                val mn = m.name.lowercase()
                if (!sigKeys.any { mn.contains(it) }) continue
                try {
                    m.isAccessible = true
                    m.invoke(vm)
                    Logger.d("refresh called(sig): ${m.name}")
                    return true
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        Logger.always("refresh no method vm=${vm.javaClass.name}")
        return false
    }
    fun refreshContent(): Boolean {
        synchronized(CfhState.seenPhotoIds) { CfhState.seenPhotoIds.clear() }
        Logger.always("refreshContent: hist cleared")
        val inst = CfhState.knhbInst?.get()
        if (inst == null) { Logger.always("refreshContent: knhbInst=null, fallback loadMore"); return triggerLoadMore() }
        val target = findLoadTarget(inst)
        if (target == null) { Logger.always("refreshContent: no target, fallback loadMore"); return triggerLoadMore() }
        val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }
        if (rm != null) {
            try {
                rm.isAccessible = true; rm.invoke(target)
                CfhState.lastLoadMoreTime = System.currentTimeMillis()
                Logger.always("refreshContent: refresh() called on " + target.javaClass.name)
                return true
            } catch (e: Throwable) { Logger.always("refreshContent: refresh() threw " + e.javaClass.name) }
        }
        Logger.always("refreshContent: no refresh method, fallback loadMore")
        return triggerLoadMore()
    }
}
