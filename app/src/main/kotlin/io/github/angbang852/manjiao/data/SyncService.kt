package io.github.angbang852.manjiao.data

import android.content.SharedPreferences
import io.github.angbang852.manjiao.hook.ContentFilterHook
import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

// ★ XposedService 收编广播同步（2026-09 S3，加性设计）：
// 两个进程都经 XposedServiceHelper 拿到同一个 XposedService（模块 App 绑定到目标
// 进程的 XposedProvider；目标进程由 libxposed 投递本地 service），
// getRemotePreferences("slowkick_remote") 即跨进程共享配置。
// 广播/媒体文件链路全部保留作兜底——service 不可用时（目标未运行/未激活）回退旧链路。
object SyncService {
    private const val PREFS_NAME = "slowkick_remote"

    @Volatile private var service: XposedService? = null
    @Volatile private var rp: SharedPreferences? = null
    private val inited = java.util.concurrent.atomic.AtomicBoolean(false)
    private val targetSyncArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun init() {
        if (!inited.compareAndSet(false, true)) return
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    try { rp = s.getRemotePreferences(PREFS_NAME) } catch (_: Throwable) { rp = null }
                    Logger.always("SyncService bound: rp=${rp != null}")
                    // 目标进程绑定完成 → 立即把共享配置灌进缓存
                    if (targetSyncArmed.get()) refreshFromService()
                }
                override fun onServiceDied(s: XposedService) {
                    service = null
                    rp = null
                    Logger.always("SyncService died")
                }
            })
        } catch (t: Throwable) { Logger.d("SyncService init fail: ${t.message}") }
    }

    fun prefs(): SharedPreferences? = rp

    // ★ 目标进程专用：注册共享配置变更监听（替代 ACTION_UPDATE 广播推送到主路径）
    fun armTargetSync() {
        targetSyncArmed.set(true)
        val p = rp ?: return
        try {
            p.unregisterOnSharedPreferenceChangeListener(targetListener)
            p.registerOnSharedPreferenceChangeListener(targetListener)
            refreshFromService()
        } catch (_: Throwable) {}
    }

    private val targetListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
        try { refreshFromService() } catch (_: Throwable) {}
    }

    // 把共享配置全量灌入 Prefs 缓存（幂等，类型按值判定）
    fun refreshFromService() {
        val p = rp ?: return
        val all = try { p.all } catch (_: Throwable) { return }
        for ((k, v) in all) {
            try {
                when (v) {
                    is Boolean -> Prefs.applyRemote(k, v)
                    is Int -> Prefs.applyRemote(k, v)
                    is Long -> Prefs.applyRemote(k, v.toInt())
                    is String -> Prefs.applyRemote(k, v)
                    is Set<*> -> Prefs.applyRemote(k, v.filterIsInstance<String>().toSet())
                }
            } catch (_: Throwable) {}
        }
        ContentFilterHook.invalidateFilterCache()
        Logger.quiet = Prefs.bool(Prefs.K_PERF_QUIET, true)
        Logger.diag = Prefs.bool(Prefs.K_DIAG, false)
    }

    // ★ 写路径加性推送：任何进程的 setXxxSync 都顺带写一份共享配置（best-effort）
    fun push(type: String, key: String, value: Any?) {
        val p = rp ?: return
        try {
            val e = p.edit()
            when (type) {
                "bool" -> if (value is Boolean) e.putBoolean(key, value)
                "int" -> if (value is Int) e.putInt(key, value)
                "str" -> if (value is String) e.putString(key, value)
                "strset" -> if (value is Set<*>) e.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            e.apply()
        } catch (_: Throwable) {}
    }
}
