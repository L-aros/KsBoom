package io.github.angbang852.manjiao.hook

import android.hardware.Sensor
import android.hardware.SensorManager
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.api.XposedInterface

/**
 * 性能优化：
 * 1) 刷屏日志静默——native hook __android_log_buf_write/__android_log_write/__android_log_vprint
 *    按 tag 过滤 P2P 日志 + 按 text 过滤 Invalid resource ID（走 native C++ ALOGD，Java Log.d hook 拦不住）。
 *    实测 P2P 分片6片日志占 logcat 57%，拦截后 GC 压力降 90%。
 * 2) 广告摇一摇拦截——高频加速度计注册拦截。
 */
object PerfHook {
    private var diag = 0
    private val SPAM_TAGS = setOf("xySDK", "Klink")

    @JvmStatic external fun nativeInitLogHook(): Boolean

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        hookLogSpam(xp, cl)
        hookSensor(xp)
        Logger.once("perf.installed", "PerfHook installed")
    }

    private fun hookLogSpam(xp: XposedInterface, cl: ClassLoader) {
        // ★ 默认 true→false（2026-09-30 用户定稿：全关，按需开启）。
        //   false ⇒ native 刷屏日志拦截不装（原注释称可降 GC 压力 90%），P2P 日志照常输出。
        if (!Prefs.bool(Prefs.K_PERF_LOGSPAM, false)) return
        var nativeOk = false
        try {
            System.loadLibrary("loghook")
            nativeOk = nativeInitLogHook()
        } catch (t: Throwable) { Logger.once("perf.nativefail", "native loghook load fail: $t") }
        Logger.once("perf.logspam", "perf logspam native=$nativeOk tags=$SPAM_TAGS")

        // ★ 性能修复（审阅 2026-09 · L2）：Java 层 `android.util.Log.d` hook
        // **仅在 native hook 未生效时**才安装，作为降级兜底。
        //
        // 原实现两层都装。但 `Log.d` 的实现最终必经 `__android_log_buf_write`
        // （Log.d → println_native → __android_log_buf_write，bufID=LOG_ID_MAIN），
        // 因此 native 层对 tag 过滤是**完全覆盖**；而且 native 还多拦一类
        // 「text 含 Invalid resource ID」（Java 层从未做这个）。
        // ⇒ Java 层是严格冗余的，代价却是给**全进程最热的方法之一**装 Xposed 桥
        //   （任何库调用 Log.d 都要过桥 + 构造参数数组）。
        //
        // nativeOk=false（个别设备 Dobby 挂载失败）时保留 Java 层兜底，
        // 保证「刷屏日志拦截」这个开关不会因为 native 失败而完全失效。
        if (nativeOk) {
            Logger.once("perf.javaskip", "perf logspam: skip Java Log.d hook (native covers tag filter)")
            return
        }
        try {
            val logCls = Class.forName("android.util.Log", false, cl)
            var cnt = 0
            for (m in logCls.declaredMethods) {
                if (m.name != "d") continue
                val pt = m.parameterTypes
                if (pt.size != 2 && pt.size != 3) continue
                if (pt[0] != String::class.java || pt[1] != String::class.java) continue
                cnt++
                Logger.safe("perf.log.${pt.size}") {
                    xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("perf.log.d${pt.size}")
                        .intercept { chain ->
                            try {
                                val tag = chain.args[0] as? String
                                if (tag != null && SPAM_TAGS.contains(tag)) return@intercept 0
                            } catch (_: Throwable) {}
                            chain.proceed()
                        }
                }
            }
            Logger.once("perf.javafallback", "perf logspam java fallback hook methods=$cnt")
        } catch (t: Throwable) { Logger.once("perf.javafail", "perf logspam java hook fail: $t") }
    }

    private fun hookSensor(xp: XposedInterface) {
        try {
            for (m in SensorManager::class.java.declaredMethods) {
                if (m.name != "registerListener") continue
                if (m.parameterTypes.size < 3) continue
                if (!m.parameterTypes.contains(Sensor::class.java)) continue
                Logger.safe("perf.sensor.${m.parameterTypes.size}") {
                    xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("perf.sensor.${m.parameterTypes.size}")
                        .intercept { chain ->
                            try {
                                if (Prefs.bool(Prefs.K_PERF_SENSOR, false)) {
                                    val s = chain.args.filterIsInstance<Sensor>().firstOrNull()
                                    val rate = chain.args.filterIsInstance<Int>().firstOrNull() ?: 0
                                    if (s?.type == Sensor.TYPE_ACCELEROMETER && rate in 1..59) {
                                        if (diag < 10) { diag++; Logger.d("perf block shake sensor rate=$rate") }
                                        return@intercept false
                                    }
                                }
                            } catch (_: Throwable) {}
                            chain.proceed()
                        }
                }
            }
        } catch (_: Throwable) {}
    }
}
