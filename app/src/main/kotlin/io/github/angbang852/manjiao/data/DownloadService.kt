package io.github.angbang852.manjiao.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.github.angbang852.manjiao.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.concurrent.thread

object DownloadService {
    private const val CH = "slowkick_dl"
    // ★ 并发去重：快速双击会触发两次同名下载，互踩 tmp/输出导致文件损坏
    private val active = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    // ★ 通知 id 原子递增：图集/视频/音频并发触发时 seq++ 非原子会撞 id
    private val seq = java.util.concurrent.atomic.AtomicInteger(100)
    private const val MAX_IMG_BYTES = 30L * 1024 * 1024

    // ==================== 下载队列（功能 7，2026-09） ====================

    /** 队列项：一个待执行/进行中的下载任务 */
    class DlTask(
        val id: Int,
        val title: String,
        val run: (Context) -> Unit
    ) {
        @Volatile var state: Int = STATE_QUEUED   // 0=排队 1=下载中 2=完成 3=失败
    }

    const val STATE_QUEUED = 0
    const val STATE_RUNNING = 1
    const val STATE_DONE = 2
    const val STATE_FAILED = 3

    /**
     * 串行下载队列。
     *
     * **为什么需要**：原实现每个下载各自起线程并发跑（`thread(name="MJ-DL")`），
     * 同时下多条会争抢带宽、并且多线程同时写同一目录时的进度通知会互相覆盖。
     * 改为**串行执行**：一次只下一个，其余排队，通知里显示队列位置。
     *
     * 用单线程执行器而非"起线程 + 自己锁"，是因为它天然保证串行且无需手写锁；
     * 任务本身内部仍会起工作线程做 IO，这里只控制**调度顺序**。
     */
    private val queueExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "MJ-DLQueue").apply { isDaemon = true }
    }
    private val pending = java.util.concurrent.CopyOnWriteArrayList<DlTask>()
    private val queueSeq = java.util.concurrent.atomic.AtomicInteger(1)

    /** 当前是否有下载在跑 / 排队中（供 UI 显示） */
    val isBusy: Boolean get() = pending.isNotEmpty()

    /** 队列快照（供「下载队列」页展示）：[标题, 状态] */
    fun queueSnapshot(): List<Pair<String, Int>> = pending.map { it.title to it.state }

    /** 取消一个仍在排队（未开始）的任务 */
    fun cancelQueued(id: Int): Boolean {
        val t = pending.firstOrNull { it.id == id && it.state == STATE_QUEUED } ?: return false
        pending.remove(t)
        return true
    }

    /** 清空仍在排队的任务（不影响正在下载的） */
    fun clearQueued(): Int {
        val victims = pending.filter { it.state == STATE_QUEUED }
        pending.removeAll(victims)
        return victims.size
    }

    /**
     * 入队并调度。任务按提交顺序串行执行。
     *
     * @param title 队列中显示的名称（通常为文件名）
     * @param body  实际下载动作 —— 在**轮到它时**才执行
     */
    private fun enqueue(ctx: Context, title: String, body: (Context) -> Unit) {
        val task = DlTask(queueSeq.getAndIncrement(), title) { c -> body(c) }
        pending.add(task)
        val pos = pending.count { it.state == STATE_QUEUED }
        if (pos > 1) toast(ctx, "已加入队列（第 $pos 位）: $title")
        queueExecutor.execute {
            try {
                task.state = STATE_RUNNING
                showQueueNotification(ctx)
                body(ctx)
                task.state = STATE_DONE
            } catch (t: Throwable) {
                task.state = STATE_FAILED
                Logger.d("DL queue task fail: ${t.message}")
            } finally {
                pending.remove(task)
                showQueueNotification(ctx, done = true)
            }
        }
    }

    /**
     * 队列总览通知（固定 id）：显示「正在下载 X，还有 N 个排队」。
     *
     * 与每条下载自己的进度通知（id 由 [seq] 分配）并存 —— 前者是总览，后者是明细。
     */
    private const val QUEUE_NOTI_ID = 90
    private fun showQueueNotification(ctx: Context, done: Boolean = false) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CH, "下载", NotificationManager.IMPORTANCE_LOW))
            }
            if (done && pending.isEmpty()) {
                nm.cancel(QUEUE_NOTI_ID)
                return
            }
            val running = pending.firstOrNull { it.state == STATE_RUNNING }
            val queued = pending.count { it.state == STATE_QUEUED }
            val text = buildString {
                if (running != null) append("正在下载: ").append(running.title)
                if (queued > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append("排队 ").append(queued).append(" 个")
                }
            }
            val n = NotificationCompat.Builder(ctx, CH)
                .setContentTitle("ManJiao 下载队列")
                .setContentText(text.ifBlank { "空闲" })
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(pending.isNotEmpty())
                .setProgress(0, 0, false)
                .build()
            nm.notify(QUEUE_NOTI_ID, n)
        } catch (_: Throwable) {}
    }

    fun downloadVideo(ctx: Context, info: VideoInfo, dir: String) {
        val candidates = mutableListOf<String>()
        info.bestRepUrl()?.let { candidates.add(it); Logger.d("DL use bestRep: $it") }
        info.url?.let { if (it !in candidates) candidates.add(it) }
        info.domainUrl?.let { if (it !in candidates) candidates.add(it) }
        // ★ 分类落盘（2026-09 用户要求）：视频进 <专属目录>/视频/
        val outDir = StorageDirs.ensure(StorageDirs.videoDir(dir)).absolutePath
        // ★ always 级（排障）：下载是低频用户操作，整条链路必须不受日志静默影响
        Logger.always("DLREQ video dir=$outDir urls=${candidates.size} url=${info.url?.take(50)} rep=${info.repUrls.size} img=${info.imageUrls.size}")
        download(ctx, candidates, info.videoFileName(), outDir, info, false)
    }

    fun downloadAudio(ctx: Context, info: VideoInfo, dir: String) {
        val candidates = mutableListOf<String>()
        (info.audioUrl ?: info.url)?.let { candidates.add(it) }
        info.domainUrl?.let { if (it !in candidates) candidates.add(it) }
        // ★ 分类落盘：音频进 <专属目录>/音频/
        val outDir = StorageDirs.ensure(StorageDirs.audioDir(dir)).absolutePath
        Logger.always("DLREQ audio dir=$outDir urls=${candidates.size} audioUrl=${info.audioUrl?.take(50)}")
        download(ctx, candidates, info.audioFileName(), outDir, info, true)
    }

    fun downloadImages(ctx: Context, info: VideoInfo, dir: String) {
        val urls = info.imageUrls
        // ★ 分类落盘：图集进 <专属目录>/图集/<作品名>/
        val imgRoot = StorageDirs.ensure(StorageDirs.imageDir(dir)).absolutePath
        Logger.always("DLREQ images dir=$imgRoot urls=${urls.size}")
        if (urls.isEmpty()) { toast(ctx, "未捕获到图集图片"); return }
        val base = info.baseName()
        val guardKey = File(imgRoot, base).absolutePath
        if (!active.add(guardKey)) { toast(ctx, "该图集已在下载中"); return }
        toast(ctx, "开始下载图集: $base (${urls.size}张)")
        val nid = seq.incrementAndGet()
        notify(ctx, nid, "准备下载图集: $base", -1)
        thread(name = "MJ-DL-IMG", isDaemon = true) {
            try {
                val outDir = StorageDirs.ensure(File(imgRoot, base))
                outDir.mkdirs()
                var done = 0
                var failed = 0
                for ((idx, url) in urls.withIndex()) {
                    val name = "${base}_${idx + 1}.jpg"
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000; readTimeout = 30000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) kwai")
                        setRequestProperty("Referer", "https://www.kuaishou.com/")
                    }
                    try {
                        conn.connect()
                        if (conn.responseCode != 200) { failed++; Logger.d("DL img fail HTTP ${conn.responseCode}"); continue }
                        // ★ 大响应预检：readBytes() 无上限，异常大响应会 OOM（下载线程崩死）
                        val len = conn.contentLengthLong
                        if (len > MAX_IMG_BYTES) { failed++; Logger.d("DL img[$idx] too large: $len"); continue }
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (saveImg(bytes, File(outDir, name))) done++ else failed++
                        notify(ctx, nid, "下载图集 ${done}/${urls.size}", done * 100 / urls.size)
                    } catch (t: Throwable) { failed++; Logger.d("DL img[$idx] error: ${t.message}") } finally {
                        try { conn.disconnect() } catch (_: Throwable) {}
                    }
                }
                if (done == 0) { notify(ctx, nid, "图集下载失败", -2); toast(ctx, "图集下载失败"); return@thread }
                // ★ 图集元信息写进**该作品自己的子目录**（不是图集根目录）：
                // 图片在 outDir 里，元信息跟着图片放才找得到
                saveMeta(outDir.absolutePath, base, info)
                val suffix = if (failed > 0) "，失败$failed 张" else ""
                notify(ctx, nid, "图集完成: $base ($done/${urls.size}张$suffix)", 100)
                toast(ctx, "图集下载完成: $base ($done/${urls.size}张$suffix)")
            } catch (t: Throwable) {
                Logger.d("DL images error: ${t.message}")
                notify(ctx, nid, "图集下载失败: ${t.message}", -2)
                toast(ctx, "图集下载失败: ${t.message}")
            } finally {
                active.remove(guardKey)
            }
        }
    }

    private fun saveImg(bytes: ByteArray, out: File): Boolean {
        try {
            if (bytes.size > 12) {
                val head4 = String(bytes, 0, 4, Charsets.US_ASCII)
                if (head4 == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") { out.writeBytes(bytes); return true }
                val b0 = bytes[0].toInt() and 0xFF
                if (b0 == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8) { out.writeBytes(bytes); return true }
                if (b0 == 0x89 && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG") { out.writeBytes(bytes); return true }
                if (String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") {
                    if (Build.VERSION.SDK_INT >= 28) {
                        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                        val bmp = ImageDecoder.decodeBitmap(src) { dec, _, _ -> dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
                        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        bmp.recycle()
                        Logger.d("saveImg kvif->jpg ok: ${out.name} ${bytes.size}B")
                        return true
                    }
                }
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                bmp.recycle()
                return true
            }
            out.writeBytes(bytes)
            return true
        } catch (t: Throwable) {
            Logger.d("saveImg err: ${t.message}")
            try { out.writeBytes(bytes) } catch (_: Throwable) {}
            return false
        }
    }

    private fun download(ctx: Context, urls: List<String>, name: String, dir: String, info: VideoInfo, audio: Boolean) {
        if (urls.isEmpty()) { Logger.always("DL ABORT no-urls (audio=$audio)"); toast(ctx, "无可用下载链接"); return }
        // ★ 同名不静默覆盖：目标已存在时追加序号（搬运号同昵称+同前 40 字描述极常见，
        // 旧实现 delete 后 rename，前一次下载成果无提示丢失）
        var outName = name
        if (File(dir, outName).exists()) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 1
            while (File(dir, "$stem($n)$ext").exists() && n < 100) n++
            outName = "$stem($n)$ext"
        }
        // ★ 队列化（功能 7，2026-09）：并入串行队列，轮到时才真正开始下载。
        // 文件名/去重键在**入队时**就定下来（而非轮到时），保证队列里显示的名字
        // 与实际落盘名一致；active 去重同样在入队时占位，防同名重复入队。
        val finalName = outName
        val guardKey = File(dir, finalName).absolutePath
        if (!active.add(guardKey)) { toast(ctx, "该文件已在下载中"); return }
        enqueue(ctx, finalName) { c -> runDownload(c, urls, finalName, dir, info, audio, guardKey) }
    }

    /** 实际的下载执行体（由队列在轮到该任务时调用） */
    private fun runDownload(
        ctx: Context, urls: List<String>, nameIn: String, dir: String,
        info: VideoInfo, audio: Boolean, guardKey: String
    ) {
        val nid = seq.incrementAndGet()
        toast(ctx, "开始下载: $nameIn")
        notify(ctx, nid, "准备下载: $nameIn", -1)
        try {
                File(dir).mkdirs()
                var outName = nameIn
                var out = File(dir, outName)
                val tmp = File(dir, "$outName.tmp")
                var success = false
                var lastErr: String? = null
                for ((idx, url) in urls.withIndex()) {
                    if (success) break
                    Logger.always("DL try[${idx + 1}/${urls.size}]: ${url.take(90)}")
                    // ★ 断点续传（审阅 2026-09 P2）：仅首个 URL 按已落盘 tmp 长度续传
                    //（换 URL 语义不同须从零开始，非 append 模式打开会自动截断旧 tmp）
                    val resumeFrom = if (idx == 0 && tmp.exists()) tmp.length() else 0L
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000; readTimeout = 30000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) kwai")
                        setRequestProperty("Referer", "https://www.kuaishou.com/")
                        if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
                    }
                    try {
                        conn.connect()
                        val code = conn.responseCode
                        if (code != 200 && code != 206) { lastErr = "HTTP $code"; Logger.always("DL fail $lastErr"); continue }
                        // 206=服务器支持续传，从 tmp 末尾追加；200=不支持，从头重下
                        val appending = resumeFrom > 0 && code == 206
                        if (resumeFrom > 0 && !appending) Logger.d("DL resume unsupported (HTTP 200), restart")
                        val start = if (appending) resumeFrom else 0L
                        val total = if (conn.contentLengthLong > 0) start + conn.contentLengthLong else -1L
                        Logger.always("DL start: size=$total resume=$start audio=$audio")
                        conn.inputStream.use { input ->
                            FileOutputStream(tmp, appending).use { fos ->
                                val buf = ByteArray(64 * 1024); var done = start; var last = start
                                val step = if (total > 0) total / 100 else 200 * 1024L
                                while (true) {
                                    val n = input.read(buf); if (n <= 0) break
                                    fos.write(buf, 0, n); done += n
                                    if (done - last > step) { last = done; val p = if (total > 0) (done * 100 / total).toInt() else -1; notify(ctx, nid, "下载中 ${done / 1024}KB/${if (total > 0) "${total / 1024}KB" else "?"}", p) }
                                }
                            }
                        }
                        success = true
                    } catch (t: Throwable) {
                        lastErr = "${t.javaClass.simpleName}: ${t.message}"
                        Logger.always("DL try[${idx + 1}] error: $lastErr")
                    } finally {
                        try { conn.disconnect() } catch (_: Throwable) {}
                    }
                }
                if (!success) {
                    // ★ 失败保留 tmp 供下次续传（原直接删除=大文件 99% 失败从头再来）；
                    // 0 字节残留清掉防堆积
                    if (tmp.exists() && tmp.length() == 0L) tmp.delete()
                    Logger.always("DL ALLFAIL out=$outName lastErr=$lastErr")
                    notify(ctx, nid, "失败: $lastErr", -2); toast(ctx, "下载失败: $lastErr"); return
                }

                // ★ MP4 魔数校验（2026-09「播放不了」）：无签名源链常返回 403/HTML 页面，
                // 会存成打不开的 .mp4——落盘前验 ftyp box，坏文件直接报错并清理
                var magicOk = true
                try {
                    val fis = java.io.FileInputStream(tmp)
                    val magic = ByteArray(12)
                    val n = try { fis.read(magic) } finally { fis.close() }
                    magicOk = n >= 12 && magic[4].toInt() == 'f'.code && magic[5].toInt() == 't'.code &&
                        magic[6].toInt() == 'y'.code && magic[7].toInt() == 'p'.code
                } catch (_: Throwable) {}
                if (!magicOk) {
                    tmp.delete()
                    Logger.always("DL BADMAGIC out=$outName")
                    notify(ctx, nid, "失败: 链接无效或需签名", -2)
                    toast(ctx, "下载失败：链接失效或需签名")
                    return
                }

                if (audio && info.audioUrl == null) {
                    val vidTmp = File(dir, "$outName.vid.tmp")
                    tmp.renameTo(vidTmp)
                    extractAudio(vidTmp, out)
                    vidTmp.delete()
                    // ★ 提取失败判定：无音轨/写样失败时 extractAudio 静默返回，旧实现仍报
                    // 「下载完成」但落盘无文件——补输出存在性校验
                    if (!out.exists() || out.length() == 0L) {
                        notify(ctx, nid, "失败: 未能提取音轨", -2)
                        toast(ctx, "音频提取失败（未找到音轨）")
                        return
                    }
                } else {
                    // ★ renameTo 在目标已存在时静默失败（返回 false 不抛异常），
                    // 会报「完成」但落盘的是上一次的旧文件
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
                }
                // ★ 真实分辨率探测（2026-09 用户要求）：MediaMetadataRetriever 不可靠时
                // 手写 tkhd 解析兜底，保证标注生效
                if (!audio) {
                    val h = probeVideoHeight(out)
                    Logger.always("DL res probe h=$h")
                    val label = when {
                        h >= 2000 -> "蓝光"
                        h >= 1080 -> "超清"
                        h >= 720 -> "高清"
                        h >= 480 -> "标清"
                        h > 0 -> "流畅"
                        else -> null
                    }
                    if (label != null && !outName.contains("_$label")) {
                        val dot = outName.lastIndexOf('.')
                        val stem = if (dot > 0) outName.substring(0, dot) else outName
                        val ext = if (dot > 0) outName.substring(dot) else ""
                        val labeled = "${stem}_$label$ext"
                        val labeledFile = File(dir, labeled)
                        if (!labeledFile.exists() && out.renameTo(labeledFile)) {
                            outName = labeled
                            out = labeledFile
                            Logger.always("DL relabel -> $outName (h=$h)")
                        }
                    }
                }
                Logger.always("DL ok: ${out.absolutePath}")
                notify(ctx, nid, "完成: $outName (${out.length() / 1024}KB)", 100)
                toast(ctx, "下载完成: $outName")
                saveMeta(dir, outName, info)
            } catch (t: Throwable) {
                Logger.always("DL error: ${t.javaClass.name}: ${t.message}")
                notify(ctx, nid, "失败: ${t.message}", -2)
                toast(ctx, "下载失败: ${t.message}")
            } finally {
                active.remove(guardKey)
            }
    }

    private fun toast(ctx: Context, msg: String) {
        try { Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } } catch (_: Throwable) {}
    }

    // ★ 视频高度探测（2026-09）：MediaMetadataRetriever 失败时手写 MP4 box 解析兜底
    private fun probeVideoHeight(f: File): Int {
        try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(f.absolutePath)
            val hv = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            mmr.release()
            if (hv > 0) return hv
        } catch (_: Throwable) {}
        // 手写解析：moov→trak→tkhd 的 width/height（16.16 定点，v0 偏移 8+20+52）
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val buf = ByteArray(4)
                fun walk(start: Long, depth: Int): Long {
                    if (depth > 6) return -1L
                    var p = start
                    while (p + 8 <= raf.length()) {
                        raf.seek(p)
                        raf.readFully(buf)
                        val sz = ((buf[0].toLong() and 0xff) shl 24) or ((buf[1].toLong() and 0xff) shl 16) or
                            ((buf[2].toLong() and 0xff) shl 8) or (buf[3].toLong() and 0xff)
                        raf.readFully(buf)
                        val ts = String(buf, Charsets.ISO_8859_1)
                        if (sz < 8 || sz > raf.length() - p) return -1L
                        if (ts == "tkhd") {
                            raf.seek(p + 8)
                            val ver = raf.read()
                            val fixed = if (ver == 1) 32 else 20
                            raf.seek(p + 8 + fixed + 52)
                            val w = Integer.reverseBytes(raf.readInt())
                            val h = Integer.reverseBytes(raf.readInt())
                            val hpx = h shr 16
                            val wpx = w shr 16
                            if (hpx > 0 && wpx > 0) return hpx.toLong()
                            return -1L
                        }
                        if (ts == "moov" || ts == "trak" || ts == "mdia" || ts == "minf") {
                            walk(p + 8, depth + 1).let { if (it > 0) return it }
                        }
                        p += sz
                    }
                    return -1L
                }
                val r = walk(0, 0)
                if (r > 0) r.toInt() else 0
            }
        } catch (_: Throwable) { 0 }
    }

    private fun extractAudio(src: File, dst: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        // ★ try/finally 兜底释放：原实现中途抛异常（无音轨/写样本失败）时
        // extractor/muxer 全泄漏，dst 留下半截文件
        try {
            extractor.setDataSource(src.absolutePath)
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrack = i; break }
            }
            if (audioTrack < 0) { Logger.d("no audio track"); return }
            extractor.selectTrack(audioTrack)
            val outFmt = extractor.getTrackFormat(audioTrack)
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(outFmt)
            muxer.start()
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0; info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                buf.limit(size)
                muxer.writeSampleData(outTrack, buf, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            try { muxer?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun saveMeta(dir: String, name: String, info: VideoInfo) {
        try {
            val cap = info.caption ?: ""
            val user = info.userName ?: ""
            if (cap.isBlank() && user.isBlank()) return
            File(dir, "$name.txt").writeText("作者: $user\n描述: $cap\n来源: 快手(无水印)\n")
        } catch (_: Throwable) {}
    }

    private fun notify(ctx: Context, id: Int, text: String, prog: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CH, "下载", NotificationManager.IMPORTANCE_LOW))
            }
            val indet = prog == -1
            val ongoing = prog == -1 || prog in 1..99
            val builder = NotificationCompat.Builder(ctx, CH)
                .setContentTitle("ManJiao").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(ongoing)
            if (indet) builder.setProgress(0, 0, true)
            else if (prog in 0..100) builder.setProgress(100, prog, false)
            nm.notify(id, builder.build())
        } catch (_: Throwable) {}
    }
}
