package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第二步：捕获职责（2026-09 S3）。
// 下载捕获锚点（currentFeedPhoto/posPhotoMap/visRing/findPhotoById）与数据取证捕获。
// 不持有状态——一律读 CfhState。
object CfhCapture {
    data class VisEntry(val photo: Any, val frag: Any?, val caption: String, val user: String)

    fun ringPush(photo: Any, frag: Any?) {
        try {
            synchronized(CfhState.visRing) {
                val cap = CfhUtil.readCaption(photo) ?: ""
                val user = readVisibleUserName(photo)
                // ★ 按「文案+作者」去重：同一视频的不同对象实例（M 字段/窗口匹配）
                // 身份不同但内容相同，身份去重会留重复项
                CfhState.visRing.removeAll { (it.caption == cap && it.user == user) }
                CfhState.visRing.addLast(VisEntry(photo, frag, cap, user))
                while (CfhState.visRing.size > 6) CfhState.visRing.removeFirst()
            }
        } catch (_: Throwable) {}
    }

    fun visibleEntries(): List<VisEntry> {
        try { currentFeedPhoto() } catch (_: Throwable) {}
        return synchronized(CfhState.visRing) { CfhState.visRing.toList().reversed() }
    }

    fun currentFeedPhoto(): Any? {
        // ★ 主源：Fragment 自身页号 × 适配器供给映射。实证 Fragment 的 M 字段在播放
        // 开始时就被预绑定为下一视频（视图可见性再准也读不到正在播的），而 D(pos)
        // 供给映射记录的是「该页本来的视频」。Fragment 的页号 = 其 Int 字段中能命中
        // 供给映射键的那个
        var best: Any? = null
        var bestFrag: Any? = null
        var bestScore = -1
        var bestVia = "none"
        try {
            val snapshot = synchronized(CfhState.liveSlideFragments) { CfhState.liveSlideFragments.toList() }
            for (o in snapshot) {
                val frag = o as? androidx.fragment.app.Fragment ?: continue
                val mPhoto = scanFragmentPhoto(frag)
                // 优先用 Fragment 自身 photoId 精确匹配（M 可能已被预绑定为下一视频）
                val ownPhoto = fragmentOwnPhoto(frag) ?: mPhoto
                var score = 0
                if (frag.isResumed) score += 1
                val v = frag.view
                if (v != null && v.isAttachedToWindow) score += 2
                if (v != null) {
                    val r = android.graphics.Rect()
                    v.getLocalVisibleRect(r)
                    // 只要有任何可见（设备内缩/状态栏差异会让全等判定误杀——实证
                    // via=none 全被卡掉）。分数加成区分完整可见的当前页
                    if (r.width() > 0 && r.height() > 0) {
                        score += 4
                        if (r.width() >= v.width * 9 / 10 && r.height() >= v.height * 9 / 10) score += 2
                    }
                }
                // 捕获优先级：① Fragment 自身 photoId 精确匹配（不受预绑定影响）
                // ② 供给映射页号探测 ③ M 字段兜底
                var viaPos = "own"
                var ph: Any? = ownPhoto
                if (ph == null) {
                    viaPos = "M"
                    ph = mPhoto
                    if (ph == null || CfhState.posPhotoMap.isNotEmpty()) {
                        var c: Class<*>? = frag.javaClass
                        var lvl = 0
                        loop@ while (c != null && c != Any::class.java && lvl < 4) {
                            for (f in c!!.declaredFields) {
                                if (f.type != Int::class.javaPrimitiveType) continue
                                try {
                                    f.isAccessible = true
                                    val iv = f.getInt(frag)
                                    val cand = synchronized(CfhState.posPhotoMap) {
                                        CfhState.posPhotoMap[iv]?.get() ?: CfhState.posPhotoMap[iv + 1]?.get()
                                    } ?: continue
                                    if (cand !== mPhoto) { ph = cand; viaPos = "pos$iv"; break@loop }
                                } catch (_: Throwable) {}
                            }
                            c = c.superclass; lvl++
                        }
                    }
                }
                ph = ph ?: mPhoto ?: continue
                if (!CfhUtil.readCaption(ph).isNullOrBlank()) score += 8
                if (score > bestScore) { bestScore = score; best = ph; bestFrag = frag; bestVia = viaPos }
            }
        } catch (_: Throwable) {}
        if (best == null) best = CfhState.visiblePhotoRef?.get()
        // ★ vm 信任标记：ownpid 精确匹配的照片（VM 批次原生）信任其 mVideoModel；
        // M 字段/兜底照片的 mVideoModel 可能被预填下一视频，下载时必须排除
        try {
            CfhState.lastCaptureTrusted = (bestVia == "own" && best != null)
            best?.let { CfhState.visiblePhotoRef = java.lang.ref.WeakReference(it) }
            bestFrag?.let {
                CfhState.lastVisibleFragRef = java.lang.ref.WeakReference(it)
                ringPush(best!!, it)
            }
        } catch (_: Throwable) {}
        try {
            val keys = synchronized(CfhState.posPhotoMap) { CfhState.posPhotoMap.keys.toList().takeLast(6) }
            Logger.always("DLCAP via=$bestVia keys=$keys cap=${best?.let { CfhUtil.readCaption(it)?.take(16) }}")
        } catch (_: Throwable) {}
        return best
    }

    fun currentFeedFragment(): Any? = CfhState.lastVisibleFragRef?.get()

    fun isCaptureTrusted(): Boolean = CfhState.lastCaptureTrusted

    fun findPhotoById(pid: String): Any? {
        if (pid.isBlank()) return null
        try {
            val snapshot = synchronized(CfhState.liveSlideFragments) { CfhState.liveSlideFragments.toList() }
            for (f in snapshot) {
                val p = scanFragmentPhoto(f) ?: continue
                if (CfhClean.readPhotoId(p) == pid) return p
            }
            val vm = CfhState.vmRef ?: return null
            val i = Reflect.readAny(vm, "i") as? List<*> ?: return null
            for (el in i) {
                val q = el?.let { CfhClean.findQpInObject(it) } ?: continue
                if (CfhClean.readPhotoId(q) == pid) return q
            }
        } catch (_: Throwable) {}
        return null
    }

    fun readVisibleUserName(qp: Any): String = try {
        CfhUtil.readUserName(qp, Reflect.readAny(qp, "mEntity") ?: qp)
    } catch (_: Throwable) { "" }

    fun scanFragmentPhoto(frag: Any): Any? {
        val qpClass = CfhState.qpClassRef ?: return null
        var c: Class<*>? = frag.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 5) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(frag) ?: continue
                    if (qpClass.isAssignableFrom(v.javaClass)) return v
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        return null
    }

    fun fragmentOwnPhoto(frag: Any): Any? {
        val ids = LinkedHashSet<String>()
        fun collect(o: Any?, depth: Int) {
            if (o == null || depth > 2 || ids.size > 6) return
            if (o is String) {
                if (o.length in 10..40 && o.matches(Regex("[0-9a-zA-Z_-]+")) && o.any { it.isDigit() }) ids.add(o)
                return
            }
            if (o is Long) { if (o > 100000000L) ids.add(o.toString()); return }
            if (o is Number || o is Boolean || o is Char) return
            val cn = o.javaClass.name
            if (cn.startsWith("java.") || cn.startsWith("android.") || cn.startsWith("kotlin.") ||
                o is android.view.View) return
            var c: Class<*>? = o.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 2) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val t = f.type
                    if (t.isPrimitive && t != Long::class.javaPrimitiveType) continue
                    try { f.isAccessible = true; collect(f.get(o), depth + 1) } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
        try { collect(frag, 0) } catch (_: Throwable) {}
        for (id in ids) {
            val p = findPhotoById(id)
            if (p != null) {
                Logger.always("DL ownpid: $id matched cap=${CfhUtil.readCaption(p)?.take(14)}")
                return p
            }
        }
        return null
    }

    fun findWindowQp(pos: Int): Any? {
        try {
            val vm = CfhState.vmRef ?: return null
            val i = Reflect.readAny(vm, "i") as? List<*> ?: return null
            if (i.isEmpty()) return null
            val idx = if (pos < 0) 0 else pos % i.size
            val el = i.getOrNull(idx) ?: return null
            val q = CfhClean.findQpInObject(el)
            if (q != null) return q
            return el
        } catch (_: Throwable) { return null }
    }

    fun captureFeedItem(qp: Any) {
        try {
            val id = System.identityHashCode(qp)
            if (CfhState.capturedIds.contains(id)) return
            if (CfhState.capturedIds.size >= 400) CfhState.capturedIds.clear()
            CfhState.capturedIds.add(id)
            if (CfhState.capturedLines >= 1500) return
            CfhState.capturedLines++
            val ent = Reflect.readAny(qp, "mEntity") ?: run { CfhClean.dataSwallow("noEnt"); return }
            val cm = Reflect.readAny(ent, "mPhotoMeta")
            val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
            val user = cm?.let { Reflect.readString(it, "mUserName") } ?: ""
            val type = cm?.let { Reflect.readLong(it, "mType") } ?: -1L
            val like = cm?.let { Reflect.readLong(it, "mLikeCount") } ?: -1L
            val cmt = cm?.let { Reflect.readLong(it, "mCommentCount") } ?: -1L
            val ai = cm?.let { Reflect.readBool(it, "photoAiAnalyze") } ?: false
            val live = Reflect.readAny(ent, "mLivePlaybackMeta")
            val sid = live?.let { Reflect.readAny(it, "mLiveStreamId")?.toString() } ?: ""
            val mAd = Reflect.readAny(ent, "mAd")
            val nativeD = Reflect.readAny(ent, "mKwAppNativeDrama")
            val novel = Reflect.readAny(ent, "mNovelDrama")
            val ltos = Reflect.readAny(ent, "mLongToShortDrama")
            val serial = Reflect.readAny(ent, "mStandardSerialMeta")
            val column = Reflect.readAny(ent, "mColumnMeta")
            val adNovel = Reflect.readAny(ent, "mAdNovelVideoMeta")
            val tube = Reflect.readAny(ent, "mTubeModel")
            val tubeInfo = tube?.let { Reflect.readAny(it, "mTubeInfo") } != null
            val tubeTag = tube?.let { Reflect.readBool(it, "mHasTubeTag") } ?: false
            val vm = Reflect.readAny(ent, "mVideoModel")
            val vid = vm?.let { Reflect.readAny(it, "mVideoUrl") } != null
            val longVid = vm?.let { Reflect.readBool(it, "mIsLongVideo") } ?: false
            val idx = vm?.let { Reflect.readAny(it, "mIndex") } != null
            val comm = cm?.let { Reflect.readAny(it, "mCommodityJumpUrl") } != null
            val kwApp = Reflect.readAny(ent, "mKwAppMeta") != null
            val atlasT = cm?.let { Reflect.readAny(it, "mAtlasDetailTitle") } != null
            val atlasText = cm?.let { Reflect.readBool(it, "mHasAtlasText") } ?: false
            val living = cm?.let { Reflect.readBool(it, "mCurrentLivingState") } ?: false
            val eid = ent.javaClass.name.substringAfterLast('.')
            val entType = CfhUtil.safeNextLong(ent, "mFeedType")
            val entDisp = CfhUtil.safeNextLong(ent, "mDisplayType")
            val qpType = CfhUtil.safeNextLong(qp, "mType")
            val pmCls = cm?.javaClass?.simpleName ?: "null"
            val adCls = mAd?.javaClass?.simpleName ?: ""
            val capEsc = cap.replace('\n', ' ').take(38)
            Logger.d("DATA ent=$eid pm=$pmCls cap=\"$capEsc\" user=$user type=$type like=$like cmt=$cmt ai=$ai live=${live != null} sid=$sid living=$living adCls=$adCls nativeD=${nativeD != null} novel=${novel != null} ltos=${ltos != null} serial=${serial != null} column=${column != null} adNovel=${adNovel != null} tubeI=$tubeInfo tubeT=$tubeTag vid=$vid long=$longVid idx=$idx comm=$comm kwApp=$kwApp atlasT=$atlasT atlasText=$atlasText entType=$entType entDisp=$entDisp qpType=$qpType")
            // 直播卡：dump 全部字段找特�?
            if (ent.javaClass.name.contains("LiveStreamFeed")) {
                val ik = System.identityHashCode(ent)
                if (CfhState.liveDumped.add(ik)) {
                    if (CfhState.liveDumpCount < 60) {
                        CfhState.liveDumpCount++
                        Logger.d("LIVEDUMP ${CfhDiag.dumpKV(ent)}")
                    }
                }
            }
            // 广告/影视卡：dump 内层标题元数�?
            if (serial != null || column != null || adNovel != null || nativeD != null || ltos != null) {
                val ik = System.identityHashCode(ent)
                if (CfhState.dramaDumped.add(ik)) {
                    if (CfhState.dramaDumpCount < 100) {
                        CfhState.dramaDumpCount++
                        val sb = StringBuilder("DRAMADUMP cap=\"$capEsc\"")
                        if (serial != null) {
                            val dm = Reflect.readAny(serial, "dataMap")
                            val sId = CfhUtil.safeNextLong(serial, "mSerialId")
                            val dId = CfhUtil.safeNextLong(serial, "mDramaId")
                            val sTitle = Reflect.readString(serial, "mTitle")
                            val ep = CfhUtil.safeNextLong(serial, "mEpisodeCount")
                            val play = CfhUtil.safeNextLong(serial, "mPlayCount")
                            val dmSize = if (dm is Map<*, *>) dm.size else if (dm is Collection<*>) dm.size else -1
                            sb.append(" serial{dataMapSize=$dmSize;mSerialId=$sId;mDramaId=$dId;mTitle=$sTitle;ep=$ep;play=$play;vars=").append(CfhDiag.dumpKV(serial)).append("}")
                        }
                        if (column != null) {
                            val cId = CfhUtil.safeNextLong(column, "mColumnId")
                            val cTitle = Reflect.readString(column, "mColumnTitle")
                            sb.append(" column{mColumnId=$cId;mColumnTitle=$cTitle;vars=").append(CfhDiag.dumpKV(column)).append("}")
                        }
                        if (adNovel != null) {
                            val nId = CfhUtil.safeNextLong(adNovel, "mNovelId")
                            val nTitle = Reflect.readString(adNovel, "mTitle")
                            val nType = CfhUtil.safeNextLong(adNovel, "mAdType")
                            sb.append(" adNovel{mNovelId=$nId;mTitle=$nTitle;mAdType=$nType;vars=").append(CfhDiag.dumpKV(adNovel)).append("}")
                        }
                        if (nativeD != null) sb.append(" nativeD=").append(CfhDiag.dumpKV(nativeD))
                        if (ltos != null) sb.append(" ltos=").append(CfhDiag.dumpKV(ltos))
                        if (mAd != null) sb.append(" ad=").append(CfhDiag.dumpKV(mAd))
                        Logger.d(sb.toString())
                    }
                }
            }
        } catch (t: Throwable) { CfhClean.dataSwallow("err ${t.javaClass.simpleName}") }
    }

}
