package io.github.angbang852.manjiao.data

import java.util.concurrent.CopyOnWriteArrayList

data class RepUrl(val url: String, val height: Long, val bitrate: Long)

data class VideoInfo(
    var url: String? = null,
    var domainUrl: String? = null,
    var audioUrl: String? = null,
    var coverUrl: String? = null,
    var caption: String? = null,
    var userName: String? = null,
    var userId: String? = null,
    var duration: Long = 0L,
    var likeCount: Long = 0L,
    var viewCount: Long = 0L,
    var isAd: Boolean = false,
    var isLive: Boolean = false,
    var isImage: Boolean = false,
    var isAi: Boolean = false,
    var isEcommerce: Boolean = false,
    var photoType: String? = null,
    // ★ 分辨率标签（用户要求 2026-09）：蓝光/超清/高清/标清/流畅，写入文件名
    var qualityLabel: String? = null,
    // ★ COW 列表：写侧（播放器/网络线程经 synchronized(CurrentVideo)）与读侧（下载
    // 线程遍历 bestRepUrl/imageUrls）并发——普通 ArrayList 下载中预加载下一条视频会
    // CME 直接炸掉下载线程。COW 代价是每次写复制，但列表 ≤20 项、写频率低
    val repUrls: MutableList<RepUrl> = CopyOnWriteArrayList(),
    val imageUrls: MutableList<String> = CopyOnWriteArrayList()
) {
    fun valid() = !url.isNullOrEmpty() || imageUrls.isNotEmpty()

    // ★ pid 提不出（url 为空或无 15 位数字的直链/IP 直链）时不放行任何候选：
    // 否则会在 repUrls 历史里跨视频挑清晰度最高的一条（内容错配）；
    // 此场景由 downloadVideo 的候选回退（info.url/domainUrl）兜底
    fun bestRepUrl(): String? {
        val pid = url?.let { PID_REGEX.find(it)?.value } ?: return null
        return repUrls
            .filter { it.url.contains(".mp4") || it.url.contains(".flv") }
            .filter { it.url.contains(pid) }
            .maxByOrNull { it.height * 10000 + it.bitrate }
            ?.url
    }

    fun baseName(): String {
        val sb = StringBuilder()
        userName?.takeIf { it.isNotBlank() }?.let {
            sb.append(it.replace(FILENAME_UNSAFE, "_")).append("_")
        }
        caption?.takeIf { it.isNotBlank() }?.let {
            sb.append(it.take(40).replace(FILENAME_UNSAFE, "_"))
        }
        if (sb.isEmpty()) {
            val id = url?.let { PID_REGEX.find(it)?.value }
            if (id != null) sb.append("ks_").append(id)
            else sb.append("ks_").append(System.currentTimeMillis() / 1000)
        }
        // ★ ext4 单文件名分量上限 255 字节（注释原话），take(80) 是字符数：80 个
        // emoji=320 字节照样 ENAMETOOLONG——按 UTF-8 字节截断到 200（留 .mp4/.tmp 余量）
        var name = byteTruncate(sb.toString(), 200)
        // 控制字符/首尾点号：首字符为点会产生隐藏文件，尾点在部分文件系统被吞
        name = name.trim('_', '.')
        return if (name.isEmpty()) "ks_${System.currentTimeMillis() / 1000}" else name
    }

    fun videoFileName(): String {
        val q = qualityLabel
        return baseName() + (if (q.isNullOrBlank()) "" else "_$q") + ".mp4"
    }
    fun audioFileName() = baseName() + ".m4a"

    companion object {
        // 不可文件名字符 = 路径符 + Windows 保留符 + 控制字符（\u0000-\u001F）+ 空白符
        // （换行/制表进文件名合法但恶心，统一替换为下划线）
        private val FILENAME_UNSAFE = Regex("[\\\\/:*?\"<>|\\s\u0000-\u001F]")
        private val PID_REGEX = Regex("\\d{15,}")

        private fun byteTruncate(s: String, maxBytes: Int): String {
            var b = s
            while (b.isNotEmpty() && b.toByteArray(Charsets.UTF_8).size > maxBytes) b = b.dropLast(1)
            return b
        }
    }
}

object CurrentVideo {
    @Volatile var current: VideoInfo = VideoInfo()
    fun reset() { synchronized(this) { current = VideoInfo() } }
    fun update(block: VideoInfo.() -> Unit) {
        synchronized(this) {
            if (!current.valid()) current = VideoInfo()
            current.block()
        }
    }
}
