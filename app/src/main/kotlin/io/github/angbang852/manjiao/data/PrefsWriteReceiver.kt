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
        Prefs.K_FLT_LIVE, Prefs.K_FLT_AI, Prefs.K_FLT_AI_SUSPECT, Prefs.K_FLT_EC,
        Prefs.K_FLT_LIKE_ON, Prefs.K_FLT_LIKE_TH, Prefs.K_FLT_KEYWORDS, Prefs.K_FLT_KW_ON,
        Prefs.K_FLT_BOOTFLUSH,
        // ★ 白名单模式（2026-09-26 用户定稿「判正常才放行」）
        //   高风险开关，默认 false；需 adb 显式开启后真机观察
        Prefs.K_FLT_WHITELIST,
        // ★ QPCHK 机制自证（2026-09-26）：临时强制校验器返回 true，用于一次回答
        //   「快手无效内容过滤器机制能否使用」。⚠️ 会让快手把所有内容当无效
        //   ⇒ 可能「无更多作品」，**验证完必须关闭**。
        "qpchk_force",
        // ★ 屏幕溯源探针（2026-09-26）：定位「模块零记录但屏幕有内容」的缺口
        "screen_trace",
        Prefs.K_PERF_MAINPROC, Prefs.K_PERF_FCACHE, Prefs.K_PERF_LOWFREQ,
        Prefs.K_PERF_QUIET, Prefs.K_PERF_SENSOR, Prefs.K_PERF_LOGSPAM,
        // ★ 补漏（审阅 2026-09）：诊断/取证开关原先不在白名单 —— 从 adb 无法打开
        // 排障日志（这是最常用的排障入口）。补入后：
        //   adb shell am broadcast -a io.github.angbang852.manjiao.PREFS_WRITE \
        //     -p io.github.angbang852.manjiao --es key diag_debug --ez value true
        Prefs.K_DIAG, Prefs.K_PERF_LAWATCH,
        // ★ 拦截审计（功能 4/5/6，2026-09）：允许 adb 开关，便于无人值守排查
        Prefs.K_AUDIT_ON, Prefs.K_AUDIT_LIMIT, Prefs.K_AUDIT_FEEDBACK,
        Prefs.K_GS_NO_DBL_LIKE, Prefs.K_GS_OPEN_COMMENT,
        Prefs.K_GS_OPEN_MENU,
        Prefs.K_PB_NO_LOOP, Prefs.K_PB_BG_PAUSE, Prefs.K_PB_NO_AUTO_LIVE,
        Prefs.K_PURIFY_PUSH, Prefs.K_PURIFY_LOG, Prefs.K_PURIFY_WEBVIEW
    )
    private var writeWinStart = 0L
    private var writeCount = 0

    // ★ 钥匙交还限频（2026-09-30 A②）：接收器是 exported，任何本地 app 都能发
    // ACTION_KEY_PULL，所以需要一道上限。
    //
    // ★★ 窗口内**用计数而不是「掐时间」**（真机实测踩到的坑，必须留痕）：
    //   最初写成「1 秒内只回 1 次」，结果快手**四个进程会在冷启瞬间同时索取**
    //   （每个还会重试一次，合计 8 次），其中 3 个被自家限流挡掉、拿不到钥匙。
    //   那 3 个随后会走到「两边都拿不到 ⇒ 生成新钥匙」，只靠 KeyVault 里那道
    //   「本地其实已有钥匙就不生成」的兜底才没把真钥匙覆盖掉 —— 差一点就酿成
    //   「自家人把自家钥匙换掉、去重表全废」。所以这里的上限必须**大于**
    //   4 进程 × 2 次重试 = 8，留出余量；防的是恶意刷屏，不是防自己人。
    private var keyPullWinStart = 0L
    private var keyPullCount = 0

    /** 2 秒窗口内最多回 16 次（≫ 4 进程 × 2 次重试，见上） */
    private fun allowKeyPull(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - keyPullWinStart > 2000) { keyPullWinStart = now; keyPullCount = 0 }
        keyPullCount++
        return keyPullCount <= 16
    }

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
            // ★★ 钥匙备份交还（2026-09-30 A②）：快手侧本地钥匙文件丢了（用户清了快手数据），
            //   来取 app 这份备份，好把旧密文（pool.json / pool_ids.json）继续解得开。
            //
            //   为什么借用这条接收器而不新开一个：本接收器存在的全部理由就是
            //   「**快手进程是无权限对端**」——本模块的 PREFS_SYNC 是 signature 级，
            //   快手进程不持有它，所以这里**挂不了** PERM_SYNC（挂了快手就发不进来，
            //   整个救命路径失效）。鉴权因此是两层：
            //     ① 上面那段 API 34+ 发送方校验：只放行本模块与快手两包；
            //     ② 回包**写死**只发快手两个包（见 KeyVault.replyKeyBack），
            //        请求方**不能指定**回包地址 ⇒ 第三方 app 即便触发本接收器也领不到回包。
            //   威胁：第三方 app 伪造 ACTION_KEY_PULL 越权索取主密钥 —— ② 是挡这件事的硬护栏。
            io.github.angbang852.manjiao.util.KeyVault.ACTION_KEY_PULL -> {
                if (!allowKeyPull()) {
                    Logger.d("key pull flood rejected")
                    return
                }
                io.github.angbang852.manjiao.util.KeyVault.replyKeyBack(ctx)
            }
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
