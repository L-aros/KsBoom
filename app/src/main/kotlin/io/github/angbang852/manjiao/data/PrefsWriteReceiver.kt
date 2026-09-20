package io.github.angbang852.manjiao.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.angbang852.manjiao.util.Logger

class PrefsWriteReceiver : BroadcastReceiver() {
    // ★ QUERY 应答限频：receiver 是 exported（快手进程是无权限的对端，signature 级
    // permission 会直接切断同步链路，只能靠运行时自律），应答会连发 N 条全量广播，
    // 任何本地 app 都能发 PREFS_QUERY 放大刷屏。3 秒限频不影响正常同步（正常周期 ≥30s）
    private var lastQueryAt = 0L

    // ★ WRITE 鉴权加固（receiver 无法要求 signature 权限：快手进程拿不到本模块证书的
    // permission，挂上属性会切断同步链路）——退而求其次收紧可写面：
    //  1) key 白名单：只接受 Prefs 已声明的配置键，堵死任意 key 注入（旧值回滚/垃圾键污染）
    //  2) 写入限频：30 次/5 秒窗口，防恶意高频写广播烧 CPU（正常用户点开关远低于此）
    //  3) 字符串长度上限：防超大 value 分配
    //  4) dl_path 约束：下载目录必须位于公共外部存储（/sdcard/ 或 /storage/），
    //     防被指向快手进程私有目录后借下载功能向任意路径写文件
    private val allowedKeys: Set<String> = setOf(
        Prefs.K_ANTI, Prefs.K_DL_PATH,
        Prefs.K_IMM_ON, Prefs.K_IMM_CUSTOM, Prefs.K_IMM_HIDE,
        Prefs.K_IMM_TOPBAR_ON, Prefs.K_IMM_TOPBAR, Prefs.K_IMM_RIGHT_ON, Prefs.K_IMM_RIGHT_ITEMS,
        Prefs.K_IMM_NICKNAME, Prefs.K_IMM_COLLECTION, Prefs.K_IMM_BOTTOM_BAR, Prefs.K_IMM_GOLD,
        Prefs.K_FLT_ADS, Prefs.K_FLT_ADVIDEO, Prefs.K_FLT_DRAMA, Prefs.K_FLT_IMAGE,
        Prefs.K_FLT_LIVE, Prefs.K_FLT_AI, Prefs.K_FLT_EC,
        Prefs.K_FLT_LIKE_ON, Prefs.K_FLT_LIKE_TH, Prefs.K_FLT_KEYWORDS, Prefs.K_FLT_KW_ON, Prefs.K_FLT_NOMORE,
        Prefs.K_PERF_MAINPROC, Prefs.K_PERF_FCACHE, Prefs.K_PERF_LOWFREQ,
        Prefs.K_PERF_QUIET, Prefs.K_PERF_SENSOR, Prefs.K_PERF_LOGSPAM,
        Prefs.K_GS_NO_DBL_LIKE, Prefs.K_GS_OPEN_COMMENT, Prefs.K_GS_OPEN_COMMENT_TAPS,
        Prefs.K_GS_OPEN_MENU, Prefs.K_GS_OPEN_MENU_TAPS,
        Prefs.K_PB_NO_LOOP, Prefs.K_PB_BG_PAUSE,
        Prefs.K_PURIFY_PUSH, Prefs.K_PURIFY_LOG, Prefs.K_PURIFY_WEBVIEW
    )
    private var writeWinStart = 0L
    private var writeCount = 0

    private fun allowWrite(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - writeWinStart > 5000) { writeWinStart = now; writeCount = 0 }
        writeCount++
        return writeCount <= 30
    }

    private fun validValue(type: String, i: Intent): Boolean = when (type) {
        "bool" -> true
        "int" -> true
        "str" -> (i.getStringExtra("value") ?: "").length <= 1000
        "strset" -> {
            val arr = i.getStringArrayExtra("value")
            arr == null || (arr.size <= 64 && arr.all { it.length <= 200 })
        }
        else -> false
    }

    override fun onReceive(ctx: Context, i: Intent) {
        // ★ API 34+ 来源校验（审阅 2026-09 补强）：白名单/限频只能限制「可写什么」，
        // 这里直接限制「谁可写」——只接受模块自身与快手两包。API <34 无公开的
        // 发送方查询 API，降级为仅白名单自律
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val from = try { getSentFromPackage() } catch (_: Throwable) { null }
            if (from != null && from != Prefs.OWN_PKG &&
                from != "com.smile.gifmaker" && from != "com.kuaishou.nebula") {
                Logger.d("prefs write/query rejected sender=$from")
                return
            }
        }
        Prefs.initLocal(ctx)
        when (i.action) {
            Prefs.ACTION_QUERY -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastQueryAt < 3000) return
                lastQueryAt = now
                Prefs.broadcastAll(ctx)
            }
            Prefs.ACTION_WRITE -> {
                val type = i.getStringExtra("type") ?: return
                val key = i.getStringExtra("key") ?: return
                // ★ key 白名单：不在清单里的键直接丢弃（防第三方注入任意配置键）
                if (key !in allowedKeys) { Logger.d("prefs write reject key=$key"); return }
                if (!allowWrite()) { Logger.d("prefs write flood rejected $key"); return }
                if (!validValue(type, i)) { Logger.d("prefs write invalid value $key"); return }
                // 写入后回发 ACTION_UPDATE：快手侧 mediaDead（媒体文件 EACCES）时
                // 拉不到媒体文件新值，不回广播则 adb/跨端写入永远同步不到快手
                when (type) {
                    "bool" -> { val v = i.getBooleanExtra("value", false); Prefs.setBool(key, v); Prefs.sendUpdateBroadcast(ctx, "bool", key, v) }
                    "strset" -> { val v = (i.getStringArrayExtra("value") ?: emptyArray()).toSet(); Prefs.setStrSet(key, v); Prefs.sendUpdateBroadcast(ctx, "strset", key, v) }
                    "int" -> { val v = i.getIntExtra("value", 0); Prefs.setInt(key, v); Prefs.sendUpdateBroadcast(ctx, "int", key, v) }
                    "str" -> {
                        val v = i.getStringExtra("value") ?: ""
                        // ★ 下载目录只允许公共外部存储：模块下载运行在快手进程（借用宿主
                        // 存储权限），路径被指到 /data/* 等私有目录时可变成任意位置写文件
                        if (key == Prefs.K_DL_PATH && v.isNotEmpty() &&
                            !(v.startsWith("/sdcard/") || v.startsWith("/storage/"))) {
                            Logger.d("prefs write reject dl_path=$v")
                            return
                        }
                        Prefs.setStr(key, v); Prefs.sendUpdateBroadcast(ctx, "str", key, v)
                    }
                }
                Logger.d("prefs write $type $key")
            }
        }
    }
}
