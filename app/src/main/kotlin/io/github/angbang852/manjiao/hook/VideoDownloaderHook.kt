package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.content.Context
import android.net.Uri
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.CurrentVideo
import io.github.angbang852.manjiao.data.RepUrl
import io.github.angbang852.manjiao.data.VideoInfo
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL

object VideoDownloaderHook {

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        // ★ 安装标记（排障 2026-09 01:42）：捕获全空但无任何失败痕迹——
        // 静默模式吞掉了全部安装日志。下载链路每个子钩子的就绪状态必须可见。
        // ★ 性能修复（审阅 2026-09 · M1）：always → once——既保留「静默模式下也可见」
        // 的排障能力，又保证每进程只打一行（原先 these 每次 hook() 都全打一遍，
        // 而 hook() 在重复初始化路径上可能被多次调用）
        try { hookPlayer(xp, cl); Logger.once("dl.player", "DLHOOK player ok") } catch (t: Throwable) { Logger.once("dl.player", "DLHOOK player FAIL: ${t.javaClass.simpleName}: ${t.message}") }
        try { hookDetail(xp, cl); Logger.once("dl.detail", "DLHOOK detail ok") } catch (t: Throwable) { Logger.once("dl.detail", "DLHOOK detail FAIL: ${t.javaClass.simpleName}: ${t.message}") }
        try { hookOkHttp(xp, cl); Logger.once("dl.okhttp", "DLHOOK okhttp ok") } catch (t: Throwable) { Logger.once("dl.okhttp", "DLHOOK okhttp FAIL: ${t.javaClass.simpleName}: ${t.message}") }
        try { hookAllOnResume(xp, cl); Logger.once("dl.allonresume", "DLHOOK allonresume ok") } catch (t: Throwable) { Logger.once("dl.allonresume", "DLHOOK allonresume FAIL: ${t.javaClass.simpleName}: ${t.message}") }
        try { hookUrlConstructor(xp, cl); Logger.once("dl.urlctor", "DLHOOK urlctor ok") } catch (t: Throwable) { Logger.once("dl.urlctor", "DLHOOK urlctor FAIL: ${t.javaClass.simpleName}: ${t.message}") }
        // ★ dex 全量枚举后台化（流畅度）：hookRepresentations 遍历 dexElements 的全部
        // 类名（几十万 entry 的字符串枚举）是模块注入期主线程最大开销之一——冷启动
        // 掉帧/ANR 风险点。挪到后台 daemon 线程，rep 捕获晚几百毫秒就绪无感知
        Thread {
            try { hookRepresentations(xp, cl) } catch (t: Throwable) { Logger.once("dl.rep", "DLHOOK rep FAIL: ${t.javaClass.simpleName}: ${t.message}") }
        }.apply { name = "MJ-RepScan"; isDaemon = true }.start()
        Logger.once("dl.installed", "DLHOOK installed")
    }

    private fun hookRepresentations(xp: XposedInterface, cl: ClassLoader) {
        Logger.safe("hookRep") {
            val repClasses = mutableSetOf<String>()
            val dexCl = Class.forName("dalvik.system.BaseDexClassLoader", false, cl)
            val pathListField = dexCl.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList: Any = pathListField.get(cl) ?: return@safe
            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
            val elements = dexElementsField.get(pathList) as Array<*>
            for (e in elements) {
                val elem: Any = e ?: continue
                val dexFileField = elem.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                val dexFile = dexFileField.get(elem) ?: continue
                val entriesMethod = dexFile.javaClass.getMethod("entries")
                @Suppress("UNCHECKED_CAST")
                val entries = entriesMethod.invoke(dexFile) as java.util.Enumeration<String>
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement()
                    if (name.contains("Representation") && !name.contains("$")
                        && !name.contains("Activity") && !name.contains("Fragment")
                        && !name.contains("Adapter") && !name.contains("View")) {
                        repClasses.add(name)
                    }
                }
            }
            Logger.d("Rep classes: ${repClasses.size}")
            for (cn in repClasses) {
                val c = Reflect.findClass(cn, cl) ?: continue
                for (ctor in c.declaredConstructors) {
                    if (ctor.parameterTypes.size > 5) continue
                    Logger.safe("hookRep.$cn") {
                        xp.hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rep.$cn").intercept { chain ->
                            chain.proceed()
                            try { captureRep(chain.thisObject, cn) } catch (_: Throwable) {}
                            null
                        }
                    }
                }
                if (cn.contains("KwaiRepresentation") && !cn.contains("$")) {
                    for (m in c.declaredMethods) {
                        if (m.name.startsWith("set") && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                            Logger.safe("hookRepSet.$cn.${m.name}") {
                                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("repset.${m.name}").intercept { chain ->
                                    chain.proceed()
                                    try { captureRep(chain.thisObject, cn) } catch (_: Throwable) {}
                                    null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun captureRep(obj: Any, cn: String) {
        val url = Reflect.readString(obj, "url", "playUrl", "cdnUrl", "mUrl", "videoUrl")
        if (url == null || url.length < 8) {
            if (cn.contains("KwaiRep")) Logger.d("Rep ctor no url: $cn fields=${obj.javaClass.declaredFields.size}")
            return
        }
        if (!url.contains(".mp4") && !url.contains(".flv")) return
        if (!KsClass.VIDEO_HOST_HINTS.any { url.contains(it) }) return
        val h = Reflect.readLong(obj, "height", "videoHeight", "mHeight")
        val br = Reflect.readLong(obj, "avgBitreate", "avgBitrate", "bitRate", "bitrate", "mBitRate")
        val w = Reflect.readLong(obj, "width", "videoWidth", "mWidth")
        CurrentVideo.update {
            repUrls.removeAll { it.url == url }
            repUrls.add(RepUrl(url, h, br))
            if (repUrls.size > 20) repUrls.subList(0, repUrls.size - 20).clear()
        }
        Logger.d("Rep captured: ${w}x${h} br=$br url=$url")
    }

    private fun hookAllOnResume(xp: XposedInterface, cl: ClassLoader) {
        Logger.safe("hookAllOnResume") {
            val m = Activity::class.java.getDeclaredMethod("onResume")
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("act.onResume").intercept { chain ->
                chain.proceed()
                val name = chain.thisObject.javaClass.name
                if (name.contains("Detail") || name.contains("Photo") || name.contains("Video") || name.contains("Play") || name.contains("Home")) {
                    Logger.d("onResume: $name")
                    try { extractMeta(chain.thisObject as Activity) } catch (t: Throwable) { Logger.d("extractMeta2 err: $t") }
                }
                null
            }
        }
    }

    private fun hookUrlConstructor(xp: XposedInterface, cl: ClassLoader) {
        Logger.safe("hookUrlConstructor") {
            val c = URL::class.java.getConstructor(String::class.java)
            xp.hook(c).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("url.ctor").intercept { chain ->
                val url = chain.args[0] as? String ?: ""
                // ★ 廉价预筛：所有 VIDEO_HOST_HINTS 均含 kwai/yximgs 其一，两次子串
                // 扫描代替 11 次（URL 构造是全进程热路径）；contains(ignoreCase) 走
                // regionMatches 零分配（原 lowercase() 每 URL 一次全量拷贝）
                if (url.length > 8) {
                    if (url.contains("kwai", true) || url.contains("yximgs", true)) tryCapture(url, "URL")
                    else if (urlProbed.get() < 8 && url.startsWith("http") && urlProbed.incrementAndGet() <= 8) { Logger.always("URLCTOR miss: ${url.take(90)}") }
                }
                chain.proceed()
                null
            }
        }
    }

    // 探针：首 N 条未预筛命中的 http URL（排障 2026-09 捕获全空）
    private val urlProbed = java.util.concurrent.atomic.AtomicInteger(0)
    private val rejProbed = java.util.concurrent.atomic.AtomicInteger(0)

    private fun hookPlayer(xp: XposedInterface, cl: ClassLoader) {
        Logger.safe("MediaPlayer") {
            val mp = Class.forName("android.media.MediaPlayer", false, cl)
            for (m in mp.declaredMethods) {
                if (m.name != "setDataSource") continue
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                    hookUrl(xp, m, 0)
                }
                if (m.parameterTypes.size == 2 && m.parameterTypes[0] == Context::class.java && m.parameterTypes[1] == Uri::class.java) {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("mp.ds.uri").intercept { chain ->
                        tryCapture((chain.args[1] as? Uri)?.toString() ?: "", "MediaPlayer"); chain.proceed()
                    }
                }
            }
        }
        // ★ 版本自适应（2026-09）：播放器类候选。
        // 原写法是三个硬编码候选名（`com.kwai.player.KwaiPlayer` 等），
        // 而《快手版本适配文档》2.2 节实测三候选里两个 MISS——快手已把播放器
        // 统一到 `com.kwai.player` 命名空间并重构过类名，候选数组不可持续。
        //
        // 现走 KsResolve：先在**已知命名空间**内按「有 setDataSource(String/Uri) 的类」
        // 结构发现，再回退到既有候选名顺序（保证旧版本行为完全一致）。
        val playerCandidates = io.github.angbang852.manjiao.adapt.KsResolve.resolvePlayerClasses(cl)
        for (c in playerCandidates) {
            val cn = c.name
            for (m in c.declaredMethods) {
                if (m.parameterTypes.size != 1) continue
                val pt = m.parameterTypes[0]
                if (pt != String::class.java && pt != Uri::class.java) continue
                if (m.name !in setOf("setDataSource", "setUrl", "setVideoPath", "openVideo")) continue
                Logger.safe("hook ${cn}.${m.name}") { hookUrl(xp, m, 0) }
            }
        }
    }

    private fun hookUrl(xp: XposedInterface, m: Method, argIdx: Int) {
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("url.${m.name}").intercept { chain ->
            tryCapture(chain.args[argIdx]?.toString() ?: "", "player"); chain.proceed()
        }
    }

    // ★ IP 直链判定正则预编译（审阅 2026-09 P2）：原在 tryCapture 热路径每次
    // 现场编译 Pattern
    private val IP_URL_REGEX = Regex("^https?://\\d+\\.\\d+")
    private fun tryCapture(url: String, tag: String) {
        if (url.length < 8) return
        val low = url.lowercase()
        val hostOk = KsClass.VIDEO_HOST_HINTS.any { low.contains(it) }
        val extOk = low.contains(".mp4") || low.contains(".flv")
        if (!hostOk || !extOk) {
            // ★ 拒绝原因探针（首 10 条，排障 2026-09 捕获全空）
            if (rejProbed.get() < 10 && url.startsWith("http") && rejProbed.incrementAndGet() <= 10) {
                Logger.always("tryCapture REJECT[$tag] host=$hostOk ext=$extOk: ${url.take(90)}")
            }
            return
        }
        val isIp = IP_URL_REGEX.containsMatchIn(low)
        CurrentVideo.update {
            if (isIp) {
                this.url = url
            } else {
                this.domainUrl = url
                if (this.url == null) this.url = url
            }
        }
        // ★ 成功捕获 always 级：命中是低频事件（每个视频/图片少数几次），不受静默影响
        Logger.always("capture[$tag${if (isIp) "/IP" else "/DOM"}]: ${url.take(90)}")
    }

    private fun hookDetail(xp: XposedInterface, cl: ClassLoader) {
        for (a in arrayOf(KsClass.PHOTO_DETAIL_ACTIVITY, KsClass.PHOTO_DETAIL_ACTIVITY_TABLET)) {
            val c = Reflect.findClass(a, cl) ?: continue
            val m = Reflect.findMethod(c, "onResume", 0) ?: continue
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("detail.$a").intercept { chain ->
                chain.proceed(); Logger.d("detail onResume: $a"); try { extractMeta(chain.thisObject as Activity) } catch (t: Throwable) { Logger.d("extractMeta err: $t") }; null
            }
            Logger.d("hook $a")
        }
    }

    // ★ 新视频判定：photo 对象身份变化即换视频——重建 VideoInfo，清掉 repUrls/
    // url 等跨视频残留（否则 bestRepUrl 可能在上一条视频的历史里挑旧 URL，
    // 下载内容错配）
    private var lastPhotoRef: java.lang.ref.WeakReference<Any>? = null

    private fun extractMeta(act: Activity) {
        Logger.safe("extractMeta") {
            val photo = findPhoto(act)
            if (photo == null) { Logger.d("findPhoto null on ${act.javaClass.name}"); return@safe }
            fillFromPhoto(photo, null, src = "resume")
        }
    }

    // ★ 下载捕获的数据层直供入口（2026-09 排障结论）：网络钩子（URL ctor/okhttp/
    // 播放器 setDataSource）在 API 36 ART + 插件化播放器下安装成功但从不命中——
    // 菜单下载时 CurrentVideo 恒空。菜单直接用判定路径维护的当前 QPhoto 填充
    @Volatile private var dlUrlSrc: String = "-"
    @Volatile private var lastScannedPhoto: java.lang.ref.WeakReference<Any>? = null
    fun extractFromPhoto(rawPhoto: Any) {
        Logger.safe("extractFromPhoto") { fillFromPhoto(rawPhoto, null, src = "feed") }
    }

    // ★ 带 Fragment 的入口：URL 深扫需要当前可见 Fragment 的对象图（播放数据源在里面）
    fun extractFromPhoto(rawPhoto: Any, frag: Any?) {
        Logger.safe("extractFromPhoto") { fillFromPhoto(rawPhoto, frag, src = "feed") }
    }

    // ★ 从照片对象构建 VideoInfo（可复用：菜单列表/分享链接/详情页 resume 共用）
    // frag != null = Fragment 捕获路径：其 mVideoModel 被框架预填为下一视频
    //（「下到下一个」实证），vm 直链与 yunwillcdn 候选一律排除；
    // frag == null = VM 窗口/详情页照片：数据是批次原生的，vm 可信
    fun extractToInfo(rawPhoto: Any, frag: Any?, vmTrusted: Boolean): VideoInfo {
        val info = VideoInfo()
        try {
            var photo = rawPhoto
            if (photo.javaClass.name == "com.yxcorp.gifshow.entity.QPhoto") {
                Reflect.readAny(photo, "mEntity")?.let { photo = it }
            }
            val imgIs = isImage(photo)
            val imgs = if (imgIs) extractImageUrls(photo) else emptyList()
            val cm = Reflect.readAny(photo, "mCommonMeta")
            val pm = Reflect.readAny(photo, "mPhotoMeta")
            // ★ mVideoModel 仅当照片来源可信时使用（ownpid/分享链接匹配的批次原生
            // 照片）；Fragment M 字段/兜底照片的 mVideoModel 被框架预填下一视频
            val vm = if (vmTrusted) Reflect.readAny(photo, "mVideoModel") else null
            info.coverUrl = cm?.let { Reflect.readString(it, *KsClass.COVER_FIELDS) }
                ?: Reflect.readString(photo, *KsClass.COVER_FIELDS)
            info.caption = cm?.let { Reflect.readString(it, *KsClass.CAPTION_FIELDS) }
                ?: Reflect.readString(photo, *KsClass.CAPTION_FIELDS)
            info.userName = cm?.let { Reflect.readString(it, *KsClass.USER_NAME_FIELDS) }
                ?: Reflect.readString(photo, *KsClass.USER_NAME_FIELDS)
            if (info.userName.isNullOrBlank()) info.userName = ContentFilterHook.readVisibleUserName(rawPhoto)
            info.userId = cm?.let { Reflect.readString(it, *KsClass.USER_ID_FIELDS) }
                ?: Reflect.readString(photo, *KsClass.USER_ID_FIELDS)
            info.duration = vm?.let { Reflect.readLong(it, *KsClass.DURATION_FIELDS) }
                ?: Reflect.readLong(photo, *KsClass.DURATION_FIELDS)
            info.likeCount = pm?.let { Reflect.readLong(it, *KsClass.LIKE_COUNT_FIELDS) }
                ?: Reflect.readLong(photo, *KsClass.LIKE_COUNT_FIELDS)
            info.viewCount = pm?.let { Reflect.readLong(it, *KsClass.VIEW_COUNT_FIELDS) }
                ?: Reflect.readLong(photo, *KsClass.VIEW_COUNT_FIELDS)
            info.photoType = Reflect.readString(photo, *KsClass.PHOTO_TYPE_FIELDS)
            info.audioUrl = pm?.let { Reflect.readString(it, *KsClass.AUDIO_URL_FIELDS) }
                ?: Reflect.readString(photo, *KsClass.AUDIO_URL_FIELDS)
            info.isAd = isAd(photo); info.isLive = isLive(photo); info.isImage = imgIs
            info.isAi = isAi(photo, info.caption); info.isEcommerce = isEc(photo, info.caption)
            // 图集图片列表：与视频链无关，必须无条件填充（图集帖不走视频链）
            if (imgs.isNotEmpty()) info.imageUrls.addAll(imgs)
            // ★ 图文/图集帖不走视频链（2026-09 回归修复）：imgIs 时 url 保持 null，
            // 菜单走 downloadImages（imageUrls 已由 extractImageUrls 填充）
            if (!imgIs) {
            // ★ 结构化清晰度收集（移植 userscript kuaishou-interceptor 的数据模型）：
            // photoUrl=默认原画；videoResource/manifest.adaptationSet[].representation[]
            // =各清晰度（url 带签名可直下，含 height/bitrate/qualityLabel）
            val plays = mutableListOf<Triple<String, String, Long>>()
            val seenKs = java.util.HashSet<Any>()
            val skipVm = if (vmTrusted) null else vm
            collectKsPlayUrls(photo, 0, plays, seenKs, skipVm)
            if (frag != null) collectKsPlayUrls(frag, 0, plays, seenKs, skipVm)
            val photoUrl = Reflect.readString(photo, "photoUrl", "mPhotoUrl")
                ?: cm?.let { Reflect.readString(it, "photoUrl", "mPhotoUrl") }
            if (photoUrl != null && photoUrl.startsWith("http") && (photoUrl.contains(".mp4") || photoUrl.contains(".flv"))) {
                plays.add(Triple(photoUrl, "原画", Long.MAX_VALUE / 2))
            }
            // 去重 + 按码率降序 + 污染源过滤（vm 不信任时排除 yunwill）
            val dedup = LinkedHashMap<String, Triple<String, String, Long>>()
            for (p in plays.sortedByDescending { it.third }) {
                if (!vmTrusted && p.first.contains("yunwillcdn")) continue
                if (!dedup.containsKey(p.first)) dedup[p.first] = p
            }
            val best = dedup.values.firstOrNull()
            if (best != null) {
                info.url = best.first
                info.qualityLabel = best.second
                dlUrlSrc = "ks"
            }
            if (info.url == null) {
                val vmUrl = vm?.let { Reflect.readString(it, "mVideoUrl", "mUrl", "url", "playUrl") }
                val entUrl = Reflect.readString(photo, *KsClass.VIDEO_URL_FIELDS)
                val cmUrl = cm?.let { Reflect.readString(it, *KsClass.VIDEO_URL_FIELDS) }
                val pmUrl = pm?.let { Reflect.readString(it, *KsClass.VIDEO_URL_FIELDS) }
                when {
                    vmUrl != null -> { info.url = vmUrl; dlUrlSrc = "vm" }
                    entUrl != null -> { info.url = entUrl; dlUrlSrc = "ent" }
                    cmUrl != null -> { info.url = cmUrl; dlUrlSrc = "cm" }
                    pmUrl != null -> { info.url = pmUrl; dlUrlSrc = "pm" }
                }
            }
            // URL 深扫兜底：Fragment 路径排除 vm 子树与 yunwillcdn 污染源；
            // 优先带签名的可播真链（sign=/kwaicdn/yximgs）
            if (info.url == null && !imgIs) {
                val candidates = linkedSetOf<String>()
                candidates.addAll(collectUrlCandidates(photo, 3, skipVm))
                if (frag != null) candidates.addAll(collectUrlCandidates(frag, 6, vm))
                // ★ 偏好反转（2026-09 实证）：yunwill = 当前视频的 photoUrl（可播，
                // 且与 zj66 候选同 BMj 前缀=同一条）；zj66 源链返回 403/HTML 不可播；
                // 日期不同的另一条 zj66 = 预载的下一视频。故顺序：yunwill > 签名链 > 其余
                val usable = candidates.toList()
                val yunwill = usable.filter { it.contains("yunwillcdn") }
                val signed = usable.filter { it.contains("sign=") || it.contains("kwaicdn") || it.contains("yximgs") }
                val pool = when {
                    yunwill.isNotEmpty() -> yunwill
                    signed.isNotEmpty() -> signed
                    else -> usable
                }
                val chosen = when {
                    pool.isEmpty() -> null
                    pool.size == 1 -> pool[0]
                    else -> pool.firstOrNull { it.contains(".mp4") || it.contains(".flv") } ?: pool.maxByOrNull { it.length }
                }
                if (chosen != null) { info.url = chosen; dlUrlSrc = "scan" }
                Logger.always("DL urlscan: cands=${candidates.size} pool=${pool.size} pick=${chosen?.take(60)} all=${candidates.take(4).map { it.take(70) }}")
            }
            } // !imgIs：图文帖不选视频链
            Logger.always("DL meta: urlSrc=$dlUrlSrc url=${info.url?.take(50)} user=${info.userName} cap=${info.caption?.take(20)}")
        } catch (t: Throwable) { Logger.always("extractToInfo err: ${t.message}") }
        return info
    }

    private fun fillFromPhoto(rawPhoto: Any, frag: Any?, src: String) {
        try {
            var photo = rawPhoto
            if (photo.javaClass.name == "com.yxcorp.gifshow.entity.QPhoto") {
                Reflect.readAny(photo, "mEntity")?.let { photo = it }
            }
            if (lastPhotoRef?.get() !== photo) {
                lastPhotoRef = java.lang.ref.WeakReference(photo)
                CurrentVideo.reset()
            }
            val info = extractToInfo(rawPhoto, frag, ContentFilterHook.isCaptureTrusted())
            CurrentVideo.update {
                url = info.url; domainUrl = info.domainUrl; audioUrl = info.audioUrl; coverUrl = info.coverUrl
                caption = info.caption; userName = info.userName; userId = info.userId
                duration = info.duration; likeCount = info.likeCount; viewCount = info.viewCount
                photoType = info.photoType
                isAd = info.isAd; isLive = info.isLive; isImage = info.isImage
                isAi = info.isAi; isEcommerce = info.isEcommerce
                repUrls.clear(); repUrls.addAll(info.repUrls)
                imageUrls.clear(); imageUrls.addAll(info.imageUrls)
            }
        } catch (_: Throwable) {}
    }

    // 供下载菜单预判类型：图文帖走图集下载，视频帖走视频/音频
    fun isImagePhoto(photo: Any): Boolean = try { isImage(photo) } catch (_: Throwable) { false }

    // ★ 结构化清晰度收集（移植 userscript kuaishou-interceptor 的数据模型）：
    // 深度遍历对象图找 representation/videoResource/manifest 节点——按字段特征
    // 识别（有 url + 有 height/bitrate/qualityLabel/backupUrl），防混淆改名
    private fun collectKsPlayUrls(
        root: Any?,
        depth: Int,
        out: MutableList<Triple<String, String, Long>>,
        seen: MutableSet<Any>,
        skip: Any?
    ) {
        if (root == null || depth > 5 || out.size > 30) return
        if (skip != null && root === skip) return
        if (root is String || root is Number || root is Boolean || root is Char) return
        if (root is Collection<*>) { for (x in root) collectKsPlayUrls(x, depth + 1, out, seen, skip); return }
        if (root is Array<*>) { for (x in root) collectKsPlayUrls(x, depth + 1, out, seen, skip); return }
        if (root is Map<*, *>) { for (x in root.values) collectKsPlayUrls(x, depth + 1, out, seen, skip); return }
        if (root is android.view.View || root is android.os.Bundle || root is android.graphics.Bitmap) return
        if (!seen.add(root)) return
        val cn = root.javaClass.name
        if (cn.startsWith("java.") || cn.startsWith("kotlin.") || cn.startsWith("android.")) return
        try {
            // 节点字段特征判定：可播 url + 清晰度/码率/备链任一
            val u = Reflect.readString(root, "url", "mUrl", "playUrl", "mPlayUrl")
            val hasH = Reflect.readAny(root, "height", "mHeight", "videoHeight", "mVideoHeight") != null
            val hasB = Reflect.readAny(root, "avgBitrate", "mAvgBitrate", "bitrate", "mBitRate", "maxBitrate") != null
            val hasL = Reflect.readAny(root, "qualityLabel", "mQualityLabel", "shortName", "name") != null
            val hasBak = Reflect.readAny(root, "backupUrl", "mBackupUrl", "backupUrls") != null
            if (u != null && u.startsWith("http") && (u.contains(".mp4") || u.contains(".flv")) && (hasH || hasB || hasL || hasBak)) {
                val h = Reflect.readLong(root, "height", "mHeight", "videoHeight", "mVideoHeight")
                val br = Reflect.readLong(root, "avgBitrate", "mAvgBitrate", "bitrate", "mBitRate", "maxBitrate")
                val ql = Reflect.readString(root, "qualityLabel", "mQualityLabel", "shortName", "name")
                val label = when {
                    !ql.isNullOrBlank() && (ql.contains("清") || ql.contains("流畅") || ql.contains("蓝光") || ql.contains("原画") || ql.contains("高清")) -> ql
                    h > 0 -> labelFor(h) ?: "${h}p"
                    br > 0 -> "${br / 1000}kbps"
                    else -> "原画"
                }
                out.add(Triple(u, label, br))
                // backupUrl 备链
                try {
                    val bak = Reflect.readAny(root, "backupUrl", "mBackupUrl", "backupUrls")
                    if (bak is String && bak.startsWith("http")) out.add(Triple(bak, label, br))
                    if (bak is Collection<*>) for (b in bak) if (b is String && b.startsWith("http")) out.add(Triple(b, label, br))
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        var c: Class<*>? = root.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 2) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try { f.isAccessible = true; collectKsPlayUrls(f.get(root), depth + 1, out, seen, skip) } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
    }

    private fun labelFor(h: Long): String? = when {
        h >= 2000 -> "蓝光"
        h >= 1080 -> "超清"
        h >= 720 -> "高清"
        h >= 480 -> "标清"
        h > 0 -> "流畅"
        else -> null
    }

    // 对象图 URL 收集（depth 有限、跳过 View/Bundle/系统类型与被预填的污染对象）
    private fun collectUrlCandidates(root: Any, maxDepth: Int, skip: Any?): List<String> {
        val out = linkedSetOf<String>()
        val seen = java.util.HashSet<Any>()
        fun walk(o: Any?, depth: Int) {
            if (o == null || depth > maxDepth || out.size > 24) return
            if (skip != null && o === skip) return
            if (o is String) {
                if (o.startsWith("http") && (o.contains(".mp4") || o.contains(".flv") || o.contains("/upic/"))) out.add(o)
                return
            }
            if (o is Collection<*>) { for (x in o) walk(x, depth + 1); return }
            if (o is Array<*>) { for (x in o) walk(x, depth + 1); return }
            if (o is Map<*, *>) { for (x in o.values) walk(x, depth + 1); return }
            if (o is android.view.View || o is android.os.Bundle || o is android.graphics.Bitmap) return
            if (!seen.add(o)) return
            if (o.javaClass.name.startsWith("java.") || o.javaClass.name.startsWith("android.") ||
                o.javaClass.name.startsWith("kotlin.")) return
            var c: Class<*>? = o.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 2) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try { f.isAccessible = true; walk(f.get(o), depth + 1) } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
        walk(root, 0)
        return out.toList()
    }

    // ★ 分享链接路线（用户方案 2026-09）：读剪贴板 → 提取快手链接 → 解析 photoId →
    // 精确匹配照片对象 → 提取直链。分享链接是快手自己认定的「这条视频」，零歧义
    fun shareVideoInfo(ctx: Context): VideoInfo? {
        val text = try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (_: Throwable) { null } ?: return null
        if (!text.contains("kuaishou.com") && !text.contains("chenzhongtech.com")) return null
        Logger.always("SHARE text: ${text.take(90)}")
        var pid = Regex("(?:short-video|photo)/(\\d{10,})").find(text)?.groupValues?.get(1)
        if (pid == null) {
            // 短链（v.kuaishou.com/token）：跟进重定向拿最终地址
            val link = Regex("https?://[\\w.]*kuaishou\\.com[/\\w.?=&%-]*").find(text)?.value
                ?: Regex("https?://[\\w.]*chenzhongtech\\.com[/\\w.?=&%-]*").find(text)?.value
                ?: return null
            val final = followRedirects(link) ?: return null
            Logger.always("SHARE resolved: $final")
            pid = Regex("(?:short-video|photo)/(\\d{10,})").find(final)?.groupValues?.get(1)
        }
        pid ?: return null
        val photo = ContentFilterHook.findPhotoById(pid) ?: run {
            Logger.always("SHARE pid=$pid photo not in window")
            return null
        }
        Logger.always("SHARE pid=$pid matched")
        return extractToInfo(photo, null, true)
    }

    private fun followRedirects(link: String): String? = try {
        var u = link
        repeat(3) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) kwai")
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            c.disconnect()
            if (code in 300..399 && !loc.isNullOrBlank()) {
                u = if (loc.startsWith("http")) loc else "https://www.kuaishou.com$loc"
            } else {
                return u
            }
        }
        u
    } catch (_: Throwable) { null }

    private fun extractImageUrls(photo: Any): List<String> {
        val urls = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        try {
            val im = Reflect.readAny(photo, "mImageModel")
            val atlas = im?.let { Reflect.readAny(it, "mAtlas") }
            if (atlas != null) {
                val cdns = (Reflect.readAny(atlas, "mCdn") as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
                val arr = Reflect.readAny(atlas, "mList") as? Array<*> ?: emptyArray<Any?>()
                for (u in arr) {
                    if (u !is String || u.length < 8 || !seen.add(u)) continue
                    var full = if (u.startsWith("http")) u
                    else {
                        val host = cdns.firstOrNull() ?: "p2aoc.app1104756551.qqopenapp.com"
                        "https://$host$u"
                    }
                    if (full.endsWith(".kvif")) full = full.removeSuffix(".kvif") + ".jpg"
                    urls.add(full)
                }
                Logger.d("atlas mList: ${urls.size} imgs cdn=${cdns.firstOrNull()} first=${urls.firstOrNull()?.take(100)}")
            }
            if (urls.isEmpty()) {
                val single = im?.let { Reflect.readAny(it, "mSinglePicture") }
                val u = single?.let { Reflect.readString(it, "mUrl", "url", "mCdn", "cdn") }
                if (u != null && u.length > 8) { urls.add(u); Logger.d("singlePic: 1 img") }
            }
        } catch (t: Throwable) { Logger.d("atlas extract err: ${t.message}") }
        if (urls.isNotEmpty()) return urls
        var c: Class<*>? = photo.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c!!.declaredFields) {
                if (!java.util.List::class.java.isAssignableFrom(f.type)) continue
                try {
                    f.isAccessible = true
                    val list = f.get(photo) as? List<*> ?: continue
                    var cnt = 0
                    for (item in list) {
                        if (item == null) continue
                        val u: String? = if (item is String) item
                        else Reflect.readString(item, "url", "cdnUrl", "imageUrl", "webpUrl", "mUrl", "picUrl", "originUrl")
                        if (u != null && u.length > 8 && seen.add(u)) {
                            urls.add(u); cnt++
                        }
                    }
                    if (cnt > 0) Logger.d("imgUrls from ${f.name}: $cnt")
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return urls
    }

    private fun pickBestUrl(photo: Any): String? {
        for (fn in KsClass.REP_LIST_FIELDS) {
            val list = Reflect.readAny(photo, fn) ?: continue
            if (list !is List<*>) continue
            var best: String? = null
            var bestScore = -1L
            for (rep in list) {
                if (rep == null) continue
                val u = Reflect.readString(rep, *KsClass.REP_URL_FIELDS) ?: continue
                if (!KsClass.VIDEO_HOST_HINTS.any { u.contains(it) }) continue
                val h = Reflect.readLong(rep, *KsClass.REP_HEIGHT_FIELDS)
                val br = Reflect.readLong(rep, *KsClass.REP_BITRATE_FIELDS)
                val score = h * 10000 + br
                if (score > bestScore) { bestScore = score; best = u }
            }
            if (best != null) return best
        }
        return null
    }

    private fun findPhoto(act: Activity): Any? {
        var c: Class<*>? = act.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c!!.declaredFields) {
                if (f.type.isPrimitive || f.type == String::class.java || f.type.name.startsWith("android.")) continue
                try {
                    f.isAccessible = true; val v = f.get(act) ?: continue
                    val n = v.javaClass.name
                    if (n.contains("Photo") && !n.contains("Adapter") && !n.contains("Fragment") && !n.contains("View") && !n.contains("Activity")) {
                        if (n.contains("Param") || n.contains("Router")) {
                            val inner = findPhotoInObj(v)
                            if (inner != null) return inner
                            Logger.d("findPhoto Param fields: ${v.javaClass.declaredFields.map { it.name + ":" + it.type.simpleName }}")
                        }
                        return v
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return null
    }

    private fun findPhotoInObj(obj: Any): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c!!.declaredFields) {
                if (f.type.isPrimitive || f.type == String::class.java || f.type.name.startsWith("android.")) continue
                try {
                    f.isAccessible = true; val v = f.get(obj) ?: continue
                    val n = v.javaClass.name
                    if (n.contains("Photo") && !n.contains("Adapter") && !n.contains("Fragment") && !n.contains("View") && !n.contains("Activity") && !n.contains("Param") && !n.contains("Router")) return v
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return null
    }

    // ★ 类名 Ad 匹配排除表（审阅 2026-09 P1）：contains("Ad") 会命中 Adapter/
    // Advanced/Admin 等正常类名。不能用词边界正则——广告实体类名本就是 AdFeed/
    // AdNovelVideoMeta 这种 Ad 前缀复合词，\bAd\b 反而全部漏判；只排除已知
    // 误伤源
    private fun isAd(p: Any): Boolean {
        val n = p.javaClass.name
        if (n.contains("Adapter") || n.contains("Advanced") || n.contains("Admin") || n.contains("Radiance")) return false
        return n.contains("Advertise") || n.contains("Ad") || Reflect.readBool(p, "isAd", "mIsAd", "ad")
    }
    private fun isLive(p: Any): Boolean {
        val pt = Reflect.readString(p, *KsClass.PHOTO_TYPE_FIELDS) ?: ""
        return pt.contains("live", true) || Reflect.readBool(p, "isLive", "mIsLive")
    }
    private fun isImage(p: Any): Boolean {
        // ★ 先解包 QPhoto（菜单环里存的是 QPhoto 包装，实体级信号要打在 mEntity 上）
        var obj = p
        if (obj.javaClass.name == "com.yxcorp.gifshow.entity.QPhoto") {
            Reflect.readAny(obj, "mEntity")?.let { obj = it }
        }
        // 与判定路径 feedRules 同款的实证信号
        val n = obj.javaClass.name
        if (n.contains("ImageFeed") || n.contains("Atlas")) return true
        if (Reflect.readAny(obj, "mImageModel") != null) return true
        val cm = Reflect.readAny(obj, "mCommonMeta")
        val type = cm?.let { Reflect.readLong(it, "mType") } ?: 0L
        if (type == 2L) return true
        val pm = Reflect.readAny(obj, "mPhotoMeta")
        if (pm != null && (Reflect.readBool(pm, "mHasAtlasText") == true || Reflect.readAny(pm, "mAtlasDetailTitle") != null)) return true
        val pt = Reflect.readString(obj, "photoType", "mPhotoType", "photoTypeStr")
        if (pt != null && (pt.contains("atlas", true) || pt.contains("图集", true))) return true
        // 无视频模型 → 图文（判定路径实证：图集实体没有 mVideoModel/mVideoUrl）
        val vm = Reflect.readAny(obj, "mVideoModel")
        if (vm == null || Reflect.readAny(vm, "mVideoUrl") == null) return true
        return false
    }
    // ★ 词边界匹配：原 contains("AI", true) 会把英文文案里的 email/detail/said/
    // again/rain 全部命中（忽略大小写子串），开启 flt_ai 后大面积误杀普通视频
    private val AI_CAPTION_REGEX = Regex("\\bAI\\b|AI[生成制作绘画]|AIGC|人工智能", RegexOption.IGNORE_CASE)
    private fun isAi(p: Any, cap: String?): Boolean {
        val pt = Reflect.readString(p, *KsClass.PHOTO_TYPE_FIELDS) ?: ""
        return pt.startsWith("ai", true) || (cap?.let { AI_CAPTION_REGEX.containsMatchIn(it) } == true) || Reflect.readBool(p, "isAi", "mIsAi")
    }
    private fun isEc(p: Any, cap: String?): Boolean {
        val n = p.javaClass.name
        return n.contains("ecommerce", true) || n.contains("merchant", true) ||
            (cap?.contains("购物", true) == true) || (cap?.contains("小黄车", true) == true) ||
            Reflect.readBool(p, "isEcommerce", "mIsEcommerce")
    }

    private fun hookOkHttp(xp: XposedInterface, cl: ClassLoader) {
        val client = Reflect.findClass("okhttp3.OkHttpClient", cl) ?: return
        Logger.safe("okhttp") {
            val m = Reflect.findMethod(client, "newCall", 1) ?: return@safe
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("okhttp.newCall").intercept { chain ->
                val u = Reflect.callMethod(chain.args[0], "url")?.toString() ?: ""
                // ★ 廉价预筛：newCall 是全进程最高频路径之一，同 url.ctor（零分配）
                if (u.length > 8) {
                    if (u.contains("kwai", true) || u.contains("yximgs", true)) tryCapture(u, "okhttp")
                }
                chain.proceed()
            }
        }
    }
}
