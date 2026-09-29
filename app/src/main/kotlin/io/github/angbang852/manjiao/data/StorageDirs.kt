package io.github.angbang852.manjiao.data

import java.io.File

/**
 * 模块在公共存储里的**统一目录布局**（2026-09 用户要求）。
 *
 * 用户诉求原文：「Download 里建一个专属文件夹，然后下载等其他内容都在里面，
 * 要子文件夹分类放好。」——在此之前下载直接摊在 `/sdcard/Download/` 根下，
 * 视频/音频/图集/元信息/跨进程镜像全都混在一起，用户没法一眼分清哪些是模块产物。
 *
 * 布局：
 * ```
 * /sdcard/Download/ManJiao/
 * ├── 视频/        mp4（无水印）
 * ├── 音频/        mp3/m4a（音频提取）
 * ├── 图集/        <作品名>/xxx_1.jpg ...（每条图集一个子目录）
 * ├── 封面/        封面图
 * ├── 其他/        未分类/兜底
 * └── .sys/        模块内部文件：配置镜像、审计镜像（对用户隐藏，不参与浏览）
 * ```
 *
 * 设计取舍：
 * - 顶层目录名用**中文**：这是给用户看的，中文比 `Video/` 更直白。
 * - `.sys/` 用点号开头：文件管理器默认不显示，避免用户误删内部文件。
 * - 全部路径由本对象**集中产出**，不再各处手拼字符串 —— 目录调整只改这里。
 */
object StorageDirs {

    /** 公共下载根目录（用户可配置的 [Prefs.K_DL_PATH] 就指向这里） */
    const val DEFAULT_ROOT = "/sdcard/Download"

    /** 模块专属文件夹名（中文，便于用户识别） */
    const val APP_DIR_NAME = "ManJiao"

    const val SUB_VIDEO = "视频"
    const val SUB_AUDIO = "音频"
    const val SUB_IMAGE = "图集"
    const val SUB_COVER = "封面"
    const val SUB_OTHER = "其他"

    /** 模块内部文件目录（配置镜像、审计镜像），以点号开头对文件管理器隐藏 */
    const val SUB_SYS = ".sys"

    /**
     * 模块专属根目录。
     *
     * @param base 用户在设置里配置的下载根（[Prefs.K_DL_PATH]）。为空时用 [DEFAULT_ROOT]。
     *   若用户配置的路径**本身**已经是模块专属目录（老版本用户手工指过去），
     *   不重复拼接，避免出现 `ManJiao/ManJiao`。
     */
    fun appRoot(base: String?): File {
        val b = (base ?: "").ifBlank { DEFAULT_ROOT }.trimEnd('/')
        return if (b.endsWith("/$APP_DIR_NAME") || b == APP_DIR_NAME) {
            File(b)
        } else {
            File(b, APP_DIR_NAME)
        }
    }

    /** 视频目录（无水印 mp4） */
    fun videoDir(base: String?): File = File(appRoot(base), SUB_VIDEO)

    /** 音频目录（音频提取产物） */
    fun audioDir(base: String?): File = File(appRoot(base), SUB_AUDIO)

    /** 图集目录（其下每条作品再建一层子目录） */
    fun imageDir(base: String?): File = File(appRoot(base), SUB_IMAGE)

    /** 封面目录 */
    fun coverDir(base: String?): File = File(appRoot(base), SUB_COVER)

    /** 兜底目录 */
    fun otherDir(base: String?): File = File(appRoot(base), SUB_OTHER)

    /** 模块内部目录（不参与用户浏览） */
    fun sysDir(base: String?): File = File(appRoot(base), SUB_SYS)

    /**
     * 内部文件的稳定位置 —— **不依赖用户配置**。
     *
     * ★ 跨进程镜像必须用这个固定路径，不能用 [sysDir]：
     * 镜像的读写发生在两个进程，而两侧读到的 `Prefs.K_DL_PATH` 可能不同步
     * （用户改了路径、或某一侧还没拉到新值）——一旦不一致就永远对不上。
     * 固定路径保证两端指的是同一个文件。
     */
    fun sysDirFixed(): File = File(File(DEFAULT_ROOT, APP_DIR_NAME), SUB_SYS)

    /**
     * 确保目录存在且**另一个进程/uid 也能访问**。
     *
     * ★ 这一步不是可选项，实测踩过两次坑：
     * - `/sdcard/Download` 属主是 `u0_a224 media_rw`、权限 `drwxrws---`（2770），
     *   谁先创建谁当属主，另一侧直接 EACCES。
     * - 更隐蔽的是**文件级**权限：写出来的文件默认 `-rw-rw---- u0_a224 media_rw`（660），
     *   而普通 app 不属于 `media_rw` 组 ⇒ 快手进程写得进去、模块 app 读不出来，
     *   表现就是「文件明明有几千字节，app 里却显示 0 条」。
     *
     * 所以目录与文件都要放宽到「所有人可读」。
     */
    fun ensure(dir: File): File {
        try {
            if (!dir.exists()) dir.mkdirs()
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
            dir.setWritable(true, false)
        } catch (_: Throwable) {}
        return dir
    }

    /**
     * 放宽**文件**权限到「所有人可读」。
     *
     * 写侧每次落盘后必须调 —— 否则跨 uid 读取会静默失败（见 [ensure] 注释）。
     * 只读开放，不开放写：避免别的 app 篡改模块数据。
     */
    fun relaxFile(f: File) {
        try { f.setReadable(true, false) } catch (_: Throwable) {}
    }

    /** 建齐整套目录（用户改下载路径后调用一次即可） */
    fun ensureAll(base: String?) {
        ensure(appRoot(base))
        ensure(videoDir(base))
        ensure(audioDir(base))
        ensure(imageDir(base))
        ensure(coverDir(base))
        ensure(otherDir(base))
        ensure(sysDir(base))
        ensure(sysDirFixed())
    }

    /** 目录布局摘要（用于设置页展示 / 日志自检） */
    fun describe(base: String?): String {
        val r = appRoot(base)
        return buildString {
            append(r.absolutePath).append('\n')
            for (n in listOf(SUB_VIDEO, SUB_AUDIO, SUB_IMAGE, SUB_COVER, SUB_OTHER)) {
                append("  ├ ").append(n).append("  ").append(File(r, n).absolutePath).append('\n')
            }
            append("  └ ").append(SUB_SYS).append("  (模块内部文件，可隐藏)")
        }
    }
}
