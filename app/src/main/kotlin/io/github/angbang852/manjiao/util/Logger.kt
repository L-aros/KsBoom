package io.github.angbang852.manjiao.util

import android.util.Log
import io.github.libxposed.api.XposedModule

object Logger {
    private const val TAG = "SlowKick"
    private var mod: XposedModule? = null
    // 性能优化-日志静默：刷屏级诊断日志（feed diag/VIEWDIAG 等）每条都是主线程
    // 字符串拼接 + JNI 写 logcat，快手滑动时每秒几十条是实打实的开销
    @Volatile var quiet: Boolean = false
    // ★ 诊断日志独立开关（2026-09 S2）：quiet 只压「刷屏功能日志」的性能税；
    // diag 单独控制重反射诊断块（feed diag/ENTSCAN/VIEWDIAG 等）。此前两套共用一个
    // 开关——排障时打开 quiet 会连重诊断一起放开、拖垮性能；解耦后可只开诊断
    @Volatile var diag: Boolean = false

    fun init(m: XposedModule) {
        mod = m
        // ★ 环形缓冲落盘（E1 崩溃存档）：后台线程每 8s 把最近日志刷到媒体目录，
        // 进程崩溃后文件仍在（≤8s 延迟窗口可接受）
        Thread {
            while (true) {
                try { Thread.sleep(8000) } catch (_: Throwable) { break }
                try { flushToFile() } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.name = "MJ-LogFlush" }.start()
    }

    private val ring = java.util.ArrayDeque<String>()
    private val ringLock = Any()
    private fun ringAdd(l: String) {
        synchronized(ringLock) {
            ring.addLast(l)
            while (ring.size > 4000) ring.removeFirst()
        }
    }

    fun flushToFile() {
        try {
            val sb = StringBuilder()
            synchronized(ringLock) {
                for (s in ring) sb.append(s).append('\n')
            }
            val f = java.io.File("/sdcard/Android/media/io.github.angbang852.manjiao", "slowkick.log")
            f.parentFile?.mkdirs()
            f.writeText(sb.toString())
        } catch (_: Throwable) {}
    }

    fun d(msg: String) { if (quiet) return; val line = "D $msg"; ringAdd(line); try { mod?.log(Log.INFO, TAG, msg) } catch (_: Throwable) {}; try { Log.d(TAG, msg) } catch (_: Throwable) {} }
    // 惰性求值版：quiet 时不进 lambda——带反射/拼接的调用点用它可做到静默期零成本
    inline fun d(msg: () -> String) { if (quiet) return; d(msg()) }
    // 关键诊断/生命周期日志：不受 quiet 静默影响（频率极低，无性能开销）
    fun always(msg: String) { val line = "A $msg"; ringAdd(line); try { mod?.log(Log.INFO, TAG, msg) } catch (_: Throwable) {}; try { Log.d(TAG, msg) } catch (_: Throwable) {} }
    fun d(t: Throwable) { try { mod?.log(Log.ERROR, TAG, "", t) } catch (_: Throwable) {} }
    fun d(msg: String, t: Throwable) { d(msg); d(t) }

    inline fun safe(tag: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { d("$tag: ${t.javaClass.simpleName}: ${t.message}") }
    }
}
