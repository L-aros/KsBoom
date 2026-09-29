package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.angbang852.manjiao.data.CurrentVideo
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// 鈽?ContentFilterHook 娣辨媶绗簲姝ワ細鐢熷懡鍛ㄦ湡/鍏ュ彛閽╋紙2026-09 S3锛夈€?
// Activity/Fragment 鐢熷懡鍛ㄦ湡璺熻釜锛坙iveTop/鍓嶅彴杩借釜锛夈€乧rashGuard 闃叉姢缃戙€?
// 杞妫€鏌ワ紙check 姣?1.5s 鍏滃簳锛変笌 fragment 閽╁瓙瀹夎銆?
object CfhLcHook {
    // shouldFilterMeta 鐢紙姣?10s 涓€娆★級锛歊egex 棰勭紪璇戯紝涓嶉殢璋冪敤閲嶅缓


    // 鈽?鐩存挱椤典笂涓嬫枃鏀捐锛氱簿閫?tab 鐨勮繃婊?鍒犻櫎閾捐矾锛坒ilterListArgs/laFind/TRUEDEL/zap锛?
    // 瀵圭洿鎾〉锛圠iveSlideActivity 绛?com.kuaishou.live.* 椤甸潰锛夋槸鐏鹃毦鈥斺€旂洿鎾〉涓庣簿閫夊鍣?
    // 鍏变韩鏁版嵁寮曠敤锛圛p() 浠?slideplay 瀹瑰櫒鍙?items锛夛紝鍒犲叡浜垪琛?zap 鐩存挱瀹炰綋瀛楁浼氭妸
    // 鐩存挱椤?pager 鎺忕┖鎴栨墦鎴愮┖澹?鈫?鐩存挱闂撮粦灞忋€佹粦涓嶅姩銆佸簳鏍忓垏鎹㈠け鏁堬紙瀹炶瘉 07:51 del=1 left=0 鍚庡崱姝伙級銆?
    // 鐩存挱椤靛湪鍓嶅彴鏃讹細鎵€鏈夊唴瀹瑰垽瀹氭斁琛岋紙shouldFilterFeed=false锛夈€佹瀯閫?zap 璺宠繃銆?
    // 闈炲揩鎵嬩富鍖?Activity锛堢郴缁熷脊绐楃瓑锛変笉鏀瑰彉鐘舵€侊紝闃插脊绐楁湡闂磋鎭㈠杩囨护銆?

    internal fun hookActivityLifecycle(xp: XposedInterface) {
        try {
            val actCls = Class.forName("android.app.Activity", false, null)
            val mOn = actCls.getDeclaredMethod("onResume")
            xp.hook(mOn).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("liveTop.onResume").intercept { chain ->
                val r = chain.proceed()
                try {
                    val act = chain.thisObject as? Activity
                    if (act != null) {
                        val cn = act.javaClass.name
                        // 鈽?璁板綍椤甸潰鍒囨崲鏃跺埢锛堜緵 isLikelyUserTapByTime 鍏滃簳鍒ゆ嵁鐢級
                        CfhState.lastPageSwitchAt = System.currentTimeMillis()
                        if (cn.startsWith("com.kuaishou.live.")) {
                            if (!CfhState.liveTop) Logger.always("LIVETOP on: $cn")
                            CfhState.liveTop = true
                        } else if (cn.startsWith("com.smile.gifmaker") || cn.startsWith("com.yxcorp.") || cn.startsWith("com.kwai.")) {
                            if (CfhState.liveTop) Logger.always("LIVETOP off: $cn")
                            CfhState.liveTop = false
                        }
                    }
                } catch (_: Throwable) {}
                r
            }
            Logger.d("hookActivityLifecycle done")
        } catch (t: Throwable) { Logger.d("hookActivityLifecycle fail: ${t.message}") }
    }

    // 鈽呪槄鈽?绂佹鑷姩杩涘叆鐩存挱闂达紙2026-09 鐢ㄦ埛闇€姹傦級锛氬湪鐩存挱棰勮椤靛仠鐣欐椂闂撮暱浜嗭紝蹇墜浼氳嚜鍔?
    // 鎷夎捣鐩存挱闂?Activity锛堣澶囦簨浠舵棩蹇楀疄璇侊細
    //   21:27:06 ACTIVITY_RESUMED class=com.kuaishou.live.core.basic.activity.LiveSlideActivity
    //   21:29:59 ACTIVITY_PAUSED  锛堝仠鐣?2m53s锛夛級銆?
    // 鎷︽埅鐐归€?Activity.startActivity*锛堣烦杞彂璧峰锛夛紝灞炪€屾簮澶翠晶鎷掔粷銆嶈€岄潪娓叉煋灞傝ˉ涓侊細
    // 鍛戒腑鐩存挱闂?Activity 鏃朵笉鎵ц璺宠浆锛岀洿鎺ヨ繑鍥烇紝鐩存挱闂存牴鏈笉浼氳鍒涘缓銆?
    // 鍙?K_PB_NO_AUTO_LIVE 寮€鍏虫帶鍒讹紙榛樿鍏?淇濇寔鍘熻涓猴級锛涚敤鎴锋墜鍔ㄧ偣杩涚洿鎾棿涓嶅彈褰卞搷
    //锛堟墜鍔ㄥ叆鍙ｈ蛋鍚屼竴 startActivity锛屼絾甯?FLAG_ACTIVITY_NEW_TASK 涓庤嚜鍔ㄨ烦杞毦浠ュ尯鍒嗭紝
    //  鏁呴粯璁ゅ叧闂紝鐢辩敤鎴疯嚜琛屽紑鍚苟鎸変綋鎰熺‘璁わ級銆?
    private val AUTO_LIVE_ACT = arrayOf(
        "com.kuaishou.live.core.basic.activity.LiveSlideActivity",
        "com.kuaishou.live.core.basic.activity.LivePlayActivity"
    )

    /**
     * 鍒ゆ柇鏈 startActivity 鏄惁鐢辩敤鎴风偣鍑昏Е鍙戯紙鑰岄潪 App 鑷姩璺宠浆锛夈€?
     *
     * 鈽?鍙湅鏍堥《鑻ュ共甯э細涓嶈兘鍏ㄦ爤鎵弿 鈥斺€?涓荤嚎绋嬩换浣曡皟鐢紙鍚?Handler 瀹氭椂璺宠浆锛?
     * 搴曞眰閮芥寕鍦?Looper/ViewRootImpl 涔嬩笅锛屽叏鏍堟壘 "ViewRootImpl" 浼氭妸鑷姩璺宠浆
     * 涔熷垽鎴愭墜鍔紝杩囨护鍣ㄥ舰鍚岃櫄璁俱€傜湡姝ｆ湁鍒ゅ埆鍔涚殑鏄€岃捣璺冲墠鍑犲抚銆嶏細
     *   鎵嬪姩锛歏iew.performClick 鈫?AdapterView$PerformClick 鈫?dispatchTouchEvent 鈥?
     *   鑷姩锛欻andler.dispatchMessage 鈫?xxx$Runnable.run / Timer* / CountDownTimer 鈥?
     */
    /**
     * 鍋滅暀鏃堕暱鍏滃簳鍒ゆ嵁锛堜笌璋冪敤鏍堝垽鍒彇銆屾垨銆嶏級锛?
     * 瀹炶瘉璁惧浜嬩欢鏃ュ織 鈥斺€?鑷姩杩涘叆鐩存挱闂村彂鐢熷湪銆屽仠鐣?40s ~ 2m53s銆嶄箣鍚庯紱鎵嬪姩鐐瑰嚮鍒欐槸
     * 鐢ㄦ埛鐪嬪埌鐩存挱棰勮鍚庣珛鍗冲彂鐢熺殑銆傛晠鑻ヨ窛涓婃椤甸潰鍒囨崲 < 25s锛屾洿鍙兘鏄墜鍔ㄧ偣鍑伙紝鏀捐銆?
     * 璇ュ垽鎹笉渚濊禆鏍堝舰鐘讹紝浣滀负鏍堝垽鍒け鏁堟椂鐨勫畨鍏ㄧ綉锛堥伩鍏嶈鎷︽墜鍔ㄨ繘鍏ワ級銆?
     */
    private fun isLikelyUserTapByTime(): Boolean {
        val last = CfhState.lastPageSwitchAt
        if (last <= 0L) return false
        return System.currentTimeMillis() - last < 25_000L
    }

    private fun isUserInitiatedStack(): Boolean {
        return try {
            val st = Thread.currentThread().stackTrace
            // 鈽?鏀惧鍒?24 甯э細瀹炴祴鎵嬪姩鐐瑰嚮璧?Fragment/Context 璺緞锛岃緭鍏ヤ簨浠跺抚鍙兘
            // 涓嶅湪鏈€椤剁锛?/8 鎵嬪姩鐐瑰嚮琚垽 byUser=false锛岃鏄?12 甯х獥鍙ｅお娴咃級銆?
            val top = st.drop(2).take(24)
            for (f in top) {
                val c = f.className
                val m = f.methodName
                if (m == "performClick" || m == "onClick" ||
                    m == "dispatchTouchEvent" || m == "onTouchEvent" || m == "onTouch" ||
                    m == "onSingleTapUp" || m == "onSingleTapConfirmed" ||
                    m == "onItemClick" || m == "onItemSelected" ||
                    c.contains("InputEventReceiver") || c.contains("MotionEvent") ||
                    c.contains("GestureDetector") || c.contains("TouchListener") ||
                    c.contains("ItemClickListener")
                ) return true
            }
            // 鏍堥《鍑虹幇瀹氭椂鍣?娑堟伅娲惧彂鐗瑰緛 鈫?鏄庣‘鍒や负鑷姩
            for (f in top) {
                val m = f.methodName
                if (m == "dispatchMessage" || m.contains("handleMessage") ||
                    f.className.contains("Timer") || f.className.contains("CountDownTimer") ||
                    f.className.contains("ScheduledExecutor") ||
                    (m == "run" && f.className.contains("$"))
                ) return false
            }
            false
        } catch (_: Throwable) { false }
    }

    internal fun hookBlockAutoLive(xp: XposedInterface) {
        try {
            val actCls = Class.forName("android.app.Activity", false, null)
            val hooked = java.util.concurrent.atomic.AtomicInteger(0)
            for (m in actCls.declaredMethods) {
                val mn = m.name
                if (mn != "startActivity" && mn != "startActivityForResult" && mn != "startActivityIfNeeded" && mn != "startNextMatchingActivity") continue
                Logger.safe("blkAutoLive.$mn") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("blkAutoLive.$mn").intercept { chain ->
                        try {
                            if (Prefs.bool(Prefs.K_PB_NO_AUTO_LIVE, false)) {
                                // Intent.getComponent().getClassName()锛氱敤鐩存帴鍙嶅皠鍙栵紝涓嶄緷璧?Reflect.callMethod 鐨勯摼寮忚繑鍥?
                                val it0 = chain.args.getOrNull(0)
                                var cn: String? = null
                                if (it0 != null && it0.javaClass.name.contains("Intent")) {
                                    val comp = try { it0.javaClass.getMethod("getComponent").invoke(it0) } catch (_: Throwable) { null }
                                    if (comp != null) {
                                        cn = try { comp.javaClass.getMethod("getClassName").invoke(comp) as? String } catch (_: Throwable) { null }
                                    }
                                }
                                if (cn != null && AUTO_LIVE_ACT.any { it == cn }) {
                                    CfhState.autoLiveBlocked++
                                    // 鈽呪槄 璋冪敤鏍堝垽鍒紙2026-09 鐢ㄦ埛鍙嶉銆屾墜鍔ㄧ偣鍑讳篃琚嫤銆嶏級锛?
                                    // 鎵嬪姩鐐瑰嚮鐨勬爤椤跺惈杈撳叆浜嬩欢閾撅紝鑷姩璺宠浆鏉ヨ嚜瀹氭椂鍣?Handler銆?
                                    // 鍓?8 娆℃棤璁烘嫤涓嶆嫤閮芥墦鏍堬紝渚夸簬鏍稿鍒ゆ嵁鏄惁绗﹀悎瀹為檯銆?
                                    val byUser = isUserInitiatedStack() || isLikelyUserTapByTime()
                                    if (CfhState.autoLiveBlocked <= 8) {
                                        // 鎵撳叏鏍堬紙涓嶆埅鏂級鈥斺€斾笂涓€鐗堝彧鎵?12 甯т笖鐢?\n 鎷兼帴锛?
                                        // 鏃ュ織閲屾病鑳界暀涓嬪彲璇绘爤锛屾棤娉曞垽瀹氭墜鍔ㄨ矾寰勫埌搴曢暱浠€涔堟牱銆?
                                        // 鏀逛负銆屼竴甯т竴琛屻€嶇殑 always 杈撳嚭锛岀‘淇?logcat 瀹屾暣淇濈暀銆?
                                        val st = Thread.currentThread().stackTrace
                                        Logger.always("AUTOLIVE probe #${CfhState.autoLiveBlocked} byUser=$byUser frames=${st.size} -> $cn")
                                        for ((fi, fr) in st.withIndex()) {
                                            if (fi > 26) break
                                            Logger.always("AUTOLIVE   [$fi] ${fr.className}.${fr.methodName}:${fr.lineNumber}")
                                        }
                                    }
                                    if (byUser) {
                                        if (CfhState.autoLiveBlocked <= 20) Logger.always("AUTOLIVE pass (user tap) -> $cn")
                                        return@intercept chain.proceed()
                                    }
                                    if (CfhState.autoLiveBlocked <= 20) Logger.always("AUTOLIVE blocked (auto) -> $cn")
                                    return@intercept null
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
                hooked.incrementAndGet()
            }
            Logger.once("autolive.installed", "hookBlockAutoLive installed=$hooked (switch=${Prefs.bool(Prefs.K_PB_NO_AUTO_LIVE, false)})")
        } catch (t: Throwable) { Logger.once("autolive.fail", "hookBlockAutoLive fail: ${t.message}") }
    }


    internal fun hookFragmentCrashGuard(xp: XposedInterface, cl: ClassLoader) {
        try {
            // 鈽?闃叉姢缃戞墿灞曪紙鐪熸満鍥炲綊 2026-09 01:18 闂€€锛夛細NasaPhotoDetailFragment
            // 锛坥nCreate/onCreateView 閲?KmpSlideContext 绌烘寚閽堢殑涓や釜瀹炶瘉宕╂簝鐐癸級涓?
            // BaseSlideItemFragment 涓€骞剁撼鍏?NPE 鎹曡幏鈥斺€旀崲椤?context 閿欓厤绫诲穿婧?
            // 鍏滄垚绌哄抚鑰岄潪闂€€
            val classNames = arrayOf(
                "com.kwai.component.photo.detail.slide.groot.DetailSlidePlayFragment",
                "com.yxcorp.gifshow.detail.slideplay.nasa.groot.vm.NasaPhotoDetailFragment",
                "com.kwai.kmp.component.photo.detail.slide.groot.BaseSlideItemFragment",
                "com.kwai.component.photo.detail.slide.groot.BaseSlideItemFragment"
            )
            var hooked = 0
            for (cn in classNames) {
                val cls = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
                for (m in cls.declaredMethods) {
                    if (m.name != "onCreate" && m.name != "onCreateView" && m.name != "onResume" && m.name != "onActivityCreated" && m.name != "onPause" && m.name != "onDestroy") continue
                    try {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("crashGuard.${cn.substringAfterLast('.')}#${m.name}").intercept { chain ->
                            try {
                                chain.proceed()
                            } catch (e: Throwable) {
                                // 鈽?S1锛?026-09锛夛細瀹氬悜鍚炴帀宸茬煡鍗遍櫓鐨勫涓绘鏋跺紓甯革紙NPE/
                                // 瓒婄晫/绫诲瀷閿?鐘舵€侀敊锛夛紝鍏朵綑鍘熸牱鎶涘嚭閬垮厤鎺╃洊鐪熷疄缂洪櫡
                                if (e is NullPointerException || e is IndexOutOfBoundsException ||
                                    e is ClassCastException || e is IllegalStateException) {
                                    Logger.always("crashGuard ${cls.simpleName} ${m.name} ${e.javaClass.simpleName}: ${e.message}")
                                    null
                                } else throw e
                            }
                        }
                        hooked++
                    } catch (_: Throwable) {}
                }
            }
            if (hooked > 0) Logger.d("hookFragmentCrashGuard hooked " + hooked + " methods")
        } catch (t: Throwable) { Logger.d("hookFragmentCrashGuard fail: " + t.message) }
    }


    // 浠?View 鍚戜笂鐖紙鍚弽灏勫瓧娈碉級鐨?findQpUpFromView 宸插垹闄わ細闆惰皟鐢ㄦ浠ｇ爜
    // 锛坓rep 璇佸疄锛夛紝R8 release 浜︿細鍓ョ

    // 鈽?涓嬭浇鎹曡幏鐨勬暟鎹眰鐩翠緵锛?026-09 鎺掗殰锛夛細缃戠粶閽╁瓙锛圲RL ctor/okhttp/鎾斁鍣級
    // 鍦?API 36 ART + 鎻掍欢鍖栨挱鏀惧櫒涓嬪叏閮ㄤ笉鍙潬锛堝畨瑁?ok 浣嗘案涓嶅懡涓級锛屼笅杞借彍鍗?
    // 鎷夸笉鍒拌棰戙€傚彧璁ゃ€屽綋鍓嶅彲瑙佸垎椤?Fragment銆嶆寔鏈夌殑 QPhoto銆?
    // currentFeedPhoto 鍦ㄣ€岀偣涓嬭浇鐨勭灛闂淬€嶇幇鍦鸿В鏋愶細閬嶅巻娲荤潃鐨?slide Fragment锛?
    // 鍙栨鍒?localVisibleRect 闈炵┖鐨勯〉璇诲瓧娈碘€斺€?00ms 琚姩鎵弿瀛樺湪绔炴€侊紙蹇€熷垝椤?
    // 鍚庣珛鍒荤偣涓嬭浇锛屾壂鎻忚繕娌¤窇鍒版柊椤碉級
    // 鈽?pos 鈫?QPhoto/holder锛圖(pos) 渚涚粰鏄犲皠锛孡RU 16锛夛細浣嶇疆鈫旀潯鐩殑鏉冨▉鏉ユ簮

    // 鈽?Fragment 鑷韩鐨?photoId锛堣嚜鍔ㄩ攣瀹氥€岃繖鏉¤棰戙€?026-09锛夛細Fragment 鍒涘缓鏃?
    // 鍙傛暟閲岀粦瀹氱殑鏄畠鑷繁閭ｄ竴鏉★紙涓庝細琚缁戝畾涓轰笅涓€瑙嗛鐨?M 瀛楁涓嶅悓锛夆€斺€旀嬁杩欎釜
    // ID 鍘?VM 鎵规鏁版嵁閲岀簿纭尮閰嶏紝寰楀埌鐨勫氨鏄鍦ㄧ湅鐨勮繖鏉★紝涓?URL 鏄壒娆″師鐢熺湡閾?


    // 渚涗笅杞?URL 娣辨壂鐢細涓?currentFeedPhoto 閰嶅鐨勫彲瑙?Fragment


    // 鈽?鍒嗕韩閾炬帴璺嚎锛堢敤鎴锋柟妗?2026-09锛夛細photoId 绮剧‘鍖归厤 VM 绐楀彛/娲?Fragment 鐨?
    // 鐓х墖瀵硅薄鈥斺€斿垎浜摼鎺ユ槸蹇墜鑷繁璁ゅ畾鐨勩€岃繖鏉¤棰戙€嶏紝闆舵涔?

    // 鈽?涓嬭浇鍊欓€夌幆锛?026-09 缁堢増锛夛細鑷姩鍒ゅ畾銆屽摢涓槸姝ｅ湪鐪嬬殑銆嶅湪蹇€熷垝椤典笅姘歌繙鏈?
    // 姝т箟鈥斺€旀妸鏈€杩戝垝杩囩殑鍑犳潯锛堝彲瑙侀〉+棰勮浇椤碉級鍏ㄩ噺鍒楀嚭锛岀敤鎴峰湪涓嬭浇鑿滃崟閲岃嚜宸辩偣




    // 渚涗笅杞藉～鍏呯敤锛氬垽瀹氳矾寰勫凡楠岃瘉鍙鍒颁綔鑰呭悕鐨勮鍙栧櫒
    // dumpAiAllFields 宸插垹闄わ細grep 璇佸疄闆惰皟鐢ㄦ浠ｇ爜锛圓IFULL 涓€娆℃€ц瘖鏂殑鏃у疄鐜帮級



    // ==================== 鏁版嵁灞傛嫤锟?====================


    internal fun hookNasaFragment(xp: XposedInterface, cl: ClassLoader) {
        // ★★★ v13.73 诊断升级（2026-09-30 真机实证的断点）：
        //   **整条 fragSeq 路（aq → vmRef + hookViewModel）的唯一入口就在这里**，
        //   而它挂在 `NasaPhotoDetailFragment.onResume` 上 —— 也就是**详情页
        //   （全屏播放页）**。原来两行 `?: return` 是**静默**的，所以「这条路
        //   到底有没有装上」一直是黑盒。
        //
        //   真机现场（冷启动直接进精选页）：`CfhState.vmRef` 与
        //   `CurrentPhotoHook.currentVmRef()` **双 null** ⇒ 补货链路整体失效
        //   ⇒ 池恒 0 ⇒ 精选页空响应 ⇒ 快手判「无网络」⇒ 卡死。
        //
        //   并且这里存在**自锁**：池空 ⇒ 精选页没内容 ⇒ 建不出 detail fragment
        //   ⇒ 收不到 onResume ⇒ 拿不到 VM ⇒ 补不了货 ⇒ 池还是空。
        //   所以必须把三个关口都变成可见的。
        val c = Reflect.findClass(
            "com.yxcorp.gifshow.detail.slideplay.nasa.groot.vm.NasaPhotoDetailFragment", cl
        )
        if (c == null) {
            if (io.github.angbang852.manjiao.util.RateLimiter.allow("NASAHOOK-NOCLS", 5)) {
                Logger.evidence("NASAHOOK", "★★NasaPhotoDetailFragment 找不到 ⇒ 整条 fragSeq 路死（vmRef 永远 null）")
            }
            return
        }
        val m = Reflect.findMethod(c, "onResume", 0)
        if (m == null) {
            if (io.github.angbang852.manjiao.util.RateLimiter.allow("NASAHOOK-NOM", 5)) {
                Logger.evidence("NASAHOOK", "★★onResume 找不到（${c.name}）")
            }
            return
        }
        if (io.github.angbang852.manjiao.util.RateLimiter.allow("NASAHOOK-OK", 5)) {
            Logger.evidence("NASAHOOK", "★hookNasaFragment 已装 onResume@${m.declaringClass.name}")
        }
        Logger.safe("hookNasa") {
            Logger.probe { "nasaCls: ${c.name} loader=${c.classLoader} methodCls=${m.declaringClass.name}" }
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("nasa.onResume").intercept { chain ->
                chain.proceed()
                try {
                    Logger.probe { "nasaCls real: ${chain.thisObject.javaClass.name} loader=${chain.thisObject.javaClass.classLoader}" }
                    CfhState.realFragClass = chain.thisObject.javaClass
                    // ★★★ v13.73 onResume 到达诊断（落盘）：只有这一行打出来，
                    //   才说明「fragSeq 路有机会装」。如果整轮都没有它，就证明
                    //   精选页根本没有 detail fragment 被 resume（自锁成立）。
                    if (io.github.angbang852.manjiao.util.RateLimiter.allow("NASA-RESUME", 20)) {
                        Logger.evidence(
                            "NASAHOOK",
                            "★detail.onResume real=${chain.thisObject.javaClass.name}"
                        )
                    }
                    hookFragCallSeq(xp, CfhState.realFragClass!!)
                } catch (_: Throwable) {}
                try { CfhFeedHook.findDataSource(chain.thisObject) } catch (_: Throwable) {}
                try { CfhState.liveSlideFragments.add(chain.thisObject) } catch (_: Throwable) {}
                CfhState.handler.postDelayed({ try { CfhDiag.diagFragment(chain.thisObject) } catch (_: Throwable) {} }, 500)
                null
            }
        }
        hookFragQpSetters(xp, c)
        // ★★★ 替换式换条整体废弃（2026-09-25 用户定稿「就删脏项就行了」）。
        //   hookNasaWillAppear 的「脏项→换干净项」制造了文字/标签与视频错配
        //   （文案 View 订阅旧脏项，数据引用换了它不跟）。拦截一律走删除链路，
        //   残留的展示字段由 scrubShownDirty 清洗（见 CfhWash）。
    }


    private fun hookFragCallSeq(xp: XposedInterface, fragClass: Class<*>) {
        val clsKey = fragClass.name + "@" + System.identityHashCode(fragClass.classLoader)
        val isNew = synchronized(CfhState.fragSeqHookedClasses) { CfhState.fragSeqHookedClasses.add(clsKey) }
        if (!isNew) return
        var hookOk = 0
        Logger.d("fragSeqInstall start: ${fragClass.name}")
        // ★★★ v13.73 诊断升级（2026-09-30）：本条原来是 Logger.d ⇒ **不落盘**
        //   （Logger.d 只进内存 ring + logcat，没有 emit），所以「aq 钩子到底
        //   装上没有」一直是黑盒。真机实证：冷启动直接进精选页时
        //   `CfhState.vmRef` 与 `CurrentPhotoHook.currentVmRef()` **双 null**
        //   ⇒ 补货链路整体失效 ⇒ 池恒 0 ⇒ 精选页空响应卡死。
        //   要修就必须先看见「这一层到底有没有装上、装到哪个类」。
        if (io.github.angbang852.manjiao.util.RateLimiter.allow("FRAGSEQ-START", 20)) {
            Logger.evidence("FRAGSEQ", "★install start ${fragClass.name}")
        }
        var cls: Class<*>? = fragClass
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 4) {
            for (m in cls!!.declaredMethods) {
                if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                if (m.parameterTypes.isEmpty()) continue
                if (m.name == "onResume" || m.name == "onPause") continue
                Logger.safe("hookFragSeq.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("fragSeq.${cls!!.name}.${m.name}").intercept { chain ->
                        try {
                            val n = CfhState.fragSeqCount.incrementAndGet()
                            if (n <= 50) {
                                val argsDesc = chain.args.joinToString(",") { a -> a?.javaClass?.simpleName ?: "null" }.take(120)
                                Logger.probe { "fragSeq #$n ${m.name}($argsDesc) in ${cls!!.simpleName}" }
                            }
                            if (m.name == "gq" || m.name == "aq" || m.name == "Vp") {
                                val a0 = chain.args.firstOrNull()
                                if (a0 != null) {
                                    if (m.name == "aq") {
                                        try {
                                            CfhState.vmRef = a0
                                            // ★★★ v13.69 补齐接线（2026-09-30 真机实证的「只登记不接线」）
                                            //   ## 这条路径原来少了一半
                                            //   `aq`（fragSeq）是**真机上最早稳定拿到
                                            //   SlidePlayViewModel 的地方**（精选页尤其如此，
                                            //   因为它不经过首页 Fragment 扫描）。但这里原来只做了
                                            //     CfhState.vmRef = a0      ← 强引用登记
                                            //     CfhWash.filterVmLists(a0)
                                            //   **没有**调 hookViewModel —— 而下面三样
                                            //   **全部**只在 CfhFeedHook.hookViewModel() 里做：
                                            //     ① CurrentPhotoHook.registerVm(vm)
                                            //     ② installVmListRet(...)  ← 渲染出口补位挂载点
                                            //     ③ CfhState.qpClassRef = qpClass
                                            //   ⇒ VM「找到了却没接线」。
                                            //   ## 为什么它伪装得很好（这才是最坑的地方）
                                            //   WASH-INNER 走的是 CfhState.vmRef（强引用），
                                            //   所以日志照常刷 `进入内层 vm=SlidePlayViewModel`，
                                            //   看起来「VM 明明认到了」；而真正吃 registerVm /
                                            //   qpClassRef 的那几条链全是 0。
                                            //   真机铁证（v13.68 会话，精选页）：
                                            //     池=27（PV2-POOL 在涨）、WASH-INNER 有、GSCOLL 有
                                            //     而 IDXHARVEST=0 / RENDERFILL=0 / lretDIAG=0
                                            //   ⇒ 精选页完全没有补位 ⇒ 一批全脏就滑不出。
                                            //   ## 幂等
                                            //   hookViewModel 内部按**类**去重
                                            //   （hookedVmClasses），重复调用安全。
                                            try { CfhFeedHook.hookViewModel(a0) } catch (_: Throwable) {}
                                            CfhWash.filterVmLists(a0)
                                        } catch (_: Throwable) {}
                                    }
                                    val qpFound = CfhProbe.findQpInObject(a0)
                                    val qpHit = qpFound?.let { CfhDecide.shouldFilterFeed(it) } == true
                                    if (CfhState.gqDumpCount < 8) {
                                        CfhState.gqDumpCount++
                                        Logger.d("fragArg ${m.name}: cls=${a0.javaClass.name} qpIn=${qpFound != null} qpHit=$qpHit")
                                    }
                                    // 鈽?Vp 鎷︾粦瀹氬凡鎷嗛櫎锛堢湡鏈?22:40 闂€€瀹炶瘉锛夛細闃绘柇缁戝畾浼氶€犲嚭
                                    // 銆屽凡鍒涘缓鏈垵濮嬪寲銆嶇殑鍍靛案 Fragment鈥斺€旀鏋朵緷璧栧瓧娈碉紙濡?
                                    // PhotoDetailLogger锛夋案涓嶆敞鍏?鈫?涓嬩竴涓敓鍛藉懆鏈?onPause 绌?
                                    // 鎸囬拡闂€€銆傛覆鏌撳眰鎷︽埅鍦ㄨ繖涓鏋剁増鏈笂涓嶅畨鍏紝鑴忔暟鎹叏閮?
                                    // 浜ょ粰鏁版嵁灞傛竻娲楋紙filterVmLists/laFind/sanitize 姣绾ф憳闄わ級
                                    if (m.name == "Vp" && qpHit && CfhState.vpBlockDiag < 20) {
                                        CfhState.vpBlockDiag++
                                        Logger.always("Vp dirty-pass #${CfhState.vpBlockDiag}: ${a0.javaClass.name}")
                                    }

                                }
                            }
                            // ★★★ 通用运行时参数判脏（2026-09-28「更更动漫」决定性修复）：
                            //   FRAGSET-DIAG 实证 fragment 176 方法 paramHit=0 retHit=0 ——
                            //   M 赋值走**泛型擦除**（参数类型是 Object/基类，编译期签名匹配
                            //   永远找不到）。改为**运行时**对每个参数解包判脏：命中置 null
                            //   ⇒ M 不接收脏值（渲染前拦截，同 x4 返回 null 语义，不崩）。
                            try {
                                if (CfhState.whiteListEnabled) {
                                    val newArgs = chain.args.toTypedArray()
                                    var anyDirty = false
                                    for (i in newArgs.indices) {
                                        val a = newArgs[i] ?: continue
                                        // 跳过基础类型，只解包可能的 Photo/容器
                                        if (a is String || a is Number || a is Boolean || a is Char) continue
                                        val qp = if (CfhState.qpClassRef?.isInstance(a) == true) a
                                            else try { CfhProbe.findQpInObject(a) } catch (_: Throwable) { null }
                                        if (qp != null && CfhState.qpClassRef?.isInstance(qp) == true) {
                                            val dirty = try { CfhDecide.shouldFilterFeed(qp) } catch (_: Throwable) { false }
                                            if (dirty) { newArgs[i] = null; anyDirty = true }
                                        }
                                    }
                                    if (anyDirty) {
                                        if (CfhState.fragSeqBlockLog < 60) {
                                            CfhState.fragSeqBlockLog++
                                            var pidG: String? = null
                                            try {
                                                for (x in newArgs) {
                                                    if (x != null && CfhState.qpClassRef?.isInstance(x) == true) {
                                                        pidG = CfhProbe.readPhotoId(x); if (!pidG.isNullOrBlank()) break
                                                    }
                                                }
                                            } catch (_: Throwable) {}
                                            Logger.evidence(
                                                "FRAGSEQ-BLOCK",
                                                "★fragment 参数脏QPhoto→null ${m.name} id=$pidG"
                                            )
                                        }
                                        return@intercept chain.proceed(newArgs)
                                    }
                                }
                            } catch (_: Throwable) {}
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
                hookOk++
            }
            cls = cls.superclass; lvl++
        }
        Logger.d("fragSeqInstall done: ok=$hookOk candidates=$hookOk")
        // ★★★ v13.73 诊断升级：同上（原来不落盘）。
        //   ok=0 ⇒ 这个 fragment 类上一个候选方法都没挂上 ⇒ fragSeq 整条路死。
        if (io.github.angbang852.manjiao.util.RateLimiter.allow("FRAGSEQ-DONE", 20)) {
            Logger.evidence("FRAGSEQ", "★install done ok=$hookOk ${fragClass.name}")
        }
    }



    private fun hookFragQpSetters(xp: XposedInterface, fragClass: Class<*>) {
        var cls: Class<*>? = fragClass
        var lvl = 0
        // ★★★ 诊断（2026-09-28「更更动漫」）：确认 M 赋值方法为何 0 匹配。
        //   isAssignableFrom 也未命中 ⇒ 怀疑 QPhoto 类是接口/基类，或 fragment
        //   方法参数/返回值根本不是 QPhoto 而是包装（ObservableField/List/同级实体）。
        val diagQc = CfhState.qpClassRef
        var diagTotal = 0
        var diagParamHit = 0
        var diagRetHit = 0
        while (cls != null && cls != Any::class.java && lvl < 5) {
            for (m in cls!!.declaredMethods) {
                diagTotal++
                val qcD = CfhState.qpClassRef
                if (qcD != null && m.parameterTypes.any { qcD.isAssignableFrom(it) }) diagParamHit++
                if (qcD != null && m.returnType != Void.TYPE && qcD.isAssignableFrom(m.returnType)) diagRetHit++
            }
            cls = cls.superclass; lvl++
        }
        Logger.always(
            "FRAGSET-DIAG scan qp=${diagQc?.name ?: "null"} frag=${fragClass.name} " +
                "total=$diagTotal paramHit=$diagParamHit retHit=$diagRetHit"
        )
        cls = fragClass; lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 5) {
            for (m in cls!!.declaredMethods) {
                // ★★★ 2026-09-28 修复（「夭夭剧场」「更更动漫」漏网根因）：
                //   M 赋值路径是 Fragment 方法参数/返回值。原条件用
                //   `it.name.contains("QPhoto")` 匹配**类型名字符串** —— 快手 APK
                //   混淆后参数类型类是 a/b/c，字符串永不命中 ⇒ 0 个方法挂上 ⇒
                //   FRAGSET 从未触发（logcat 实证无 "hook frag qp setter"）。
                //   正确判定：`qpClassRef.isAssignableFrom(参数类型)` 比**类型本身**，
                //   混淆不影响类型判断。返回值同理用 isAssignableFrom。
                val qcRefF = CfhState.qpClassRef
                val isQpParam = qcRefF != null && m.parameterTypes.any { qcRefF.isAssignableFrom(it) }
                val isQpReturn = qcRefF != null && m.returnType != Void.TYPE &&
                    qcRefF.isAssignableFrom(m.returnType)
                if (!isQpParam && !isQpReturn) continue
                // 鈽?鍘婚噸閿惈 classloader 韬唤锛坔ookKey锛夛細鎻掍欢鍖栦簩 loader 鍚屽悕绫讳笉婕忚
                val key = CfhUtil.hookKey(cls!!) + "." + m.name
                val shouldHook = synchronized(CfhState.fragSetterHooked) { CfhState.fragSetterHooked.add(key) }
                if (!shouldHook) continue
                Logger.d("hook frag qp setter: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) in ${cls.name}")
                Logger.safe("hookFragSet.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("fragSet.${key}").intercept { chain ->
                        // 鈽呪槄鈽?涓ゅ淇锛?026-09 鐢ㄦ埛鎸囧嚭銆屽墠鍑犳潯浠€涔堢被鍨嬮兘鍙兘婕忋€嶏紝鎺掓煡纭涓?
                        // 閫氱敤婕忕綉鐐癸紝涓庡唴瀹圭被鍨嬫棤鍏筹級锛?
                        // 1) 鍘熺敤 chain.args[i] = clean 鈥斺€?libxposed 鐨?getArgs() 鏄彧璇诲垪琛紝
                        //    璇ヨ祴鍊兼姏 UnsupportedOperationException 涓旇 catch 鍚炴帀锛?*浠庢湭鐢熸晥**
                        //    锛堟湰妯″潡姝ゅ墠宸插洜鍚岀被鍐欐硶瀵艰嚧銆岄噾甯佺孩鍖呭畬鍏ㄤ笉闅愯棌銆嶏紝鍏ㄩ儴鏀逛负
                        //    chain.proceed(newArgs)锛夈€傝繖閲屽悓鏍锋敼涓烘瀯閫犳柊鍙傛暟鏁扮粍鍚?proceed銆?
                        // 2) 鍘熴€屽彇涓嶅埌鏇胯韩鍙墦鏃ュ織銆佽剰椤圭収浼犮€嶁€斺€?鍚屾牱鏀逛负鍏堣ˉ绗簩鏉ユ簮
                        //    锛坒indCleanQp 宸插惈闃熷垪+VM 绐楀彛鏌ヨ锛夛紝浠嶆棤鎵嶆斁寮冦€?
                        // ★★★ 参数替换废弃（2026-09-25 用户定稿「就删脏项就行了」）：
                        //   命中脏 QPhoto 参数 → 清其展示字段（caption/声明 → 订阅 View
                        //   刷新为空）并触发一轮数据源清洗，**原样传参**（不替换）。
                        //   替换会制造「净视频+脏文字」错配。
                        try {
                            var scrubbed = 0
                            // ★★★ 实拦（2026-09-28 用户抓「倒霉蛋空空」）：x4 返回 null 拦不住 M ——
                            //   M 的赋值路径是 Fragment 方法参数。脏 QPhoto 参数替换为 null ⇒
                            //   M=null ⇒ 不渲染（同 x4 返回 null 语义，IGUARD+PROTECTIVE 已证明不崩）。
                            var replaced = false
                            val newArgs = chain.args.toTypedArray()
                            for (i in newArgs.indices) {
                                val a = newArgs[i]
                                if (a != null && CfhState.qpClassRef?.isAssignableFrom(a.javaClass) == true &&
                                    CfhDecide.shouldFilterFeed(a)
                                ) {
                                    newArgs[i] = null
                                    replaced = true
                                    scrubbed++
                                }
                            }
                            if (scrubbed > 0) {
                                val vmNow = CfhState.vmRef
                                if (vmNow != null) CfhWash.filterVmLists(vmNow)
                                if (CfhState.fragSetBlockLog < 40) {
                                    CfhState.fragSetBlockLog++
                                    Logger.evidence(
                                        "FRAGSET-BLOCK",
                                        "★Fragment setter 脏QPhoto参数置null ${m.name} 替换=$scrubbed"
                                    )
                                }
                                return@intercept chain.proceed(newArgs)
                            }
                            // ★★★ 返回值拦截（2026-09-28「夭夭剧场」）：方法返回脏 QPhoto
                            //   ⇒ 返回 null（M 不接收），干净原样返回。
                            if (isQpReturn) {
                                val r = chain.proceed()
                                try {
                                    if (r != null && CfhState.qpClassRef?.isAssignableFrom(r.javaClass) == true &&
                                        CfhState.whiteListEnabled && CfhDecide.shouldFilterFeed(r)
                                    ) {
                                        if (CfhState.fragSetBlockLog < 40) {
                                            CfhState.fragSetBlockLog++
                                            val pidF = try { CfhProbe.readPhotoId(r) } catch (_: Throwable) { null }
                                            Logger.evidence(
                                                "FRAGSET-BLOCK",
                                                "★Fragment 返回值脏QPhoto→null ${m.name} id=$pidF"
                                            )
                                        }
                                        return@intercept null
                                    }
                                } catch (_: Throwable) {}
                                return@intercept r
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }   // intercept lambda
                }       // Logger.safe
                }       // for (m)
                cls = cls.superclass; lvl++
        }
    }


    internal fun startTrack(act: Activity) {
        CfhState.tracked = act
        CfhState.handler.removeCallbacks(checkTask)
        CfhState.handler.postDelayed(checkTask, 300)
    }


    internal fun stopTrack(act: Activity) {
        if (CfhState.tracked === act) {
            CfhState.tracked = null
            CfhState.handler.removeCallbacks(checkTask)
        }
    }
    private val checkTask = object : Runnable {
        override fun run() {
            val act = CfhState.tracked ?: return
            check(act)
            // 1500ms锛歝heck 鍐呴儴鑷甫 5s/10s 鑺傛祦锛岃疆璇㈡湰韬彧闇€鍏滃簳閱掓潵锛?
            // 350ms 鐨勭┖杞敜閱掔函灞炴氮璐癸紙鏀瑰姩鍓嶆瘡绉掕繎 3 娆′富绾跨▼璋冨害锛?
            if (CfhState.tracked != null) CfhState.handler.postDelayed(this, 1500)
        }
    }


    private fun check(act: Activity) {
        Logger.safe("findPagerInCheck") {
            // 鈽?pager 宸插畾浣嶄笖浠嶆寕鍦ㄧ獥鍙ｄ笂锛氭暣涓悳绱㈠潡鐩存帴璺宠繃锛堟鍓嶇紦瀛樻湁鏁堟椂
            // 姣?5 绉掍粛鐧借窇涓€娆?getIdentifier + findViewById锛夈€備粎鍦ㄧ紦瀛樼己澶辨垨
            // 鑴辩绐楀彛鏃舵寜 5 绉掕妭娴侀噸鏂版悳绱?
            val pc = CfhState.pagerCache
            if (pc != null && (pc as? android.view.View)?.isAttachedToWindow == true) return@safe
            val now = System.currentTimeMillis()
            if (now - CfhState.lastPagerSearch > 5000) {
                CfhState.lastPagerSearch = now
                try {
                    val id = act.resources.getIdentifier("nasa_groot_view_pager", "id", "com.smile.gifmaker")
                    if (id != 0) {
                        val v: View? = act.findViewById(id)
                        if (v != null) CfhViewHook.findPager(v)
                    }
                } catch (_: Throwable) {}
                val pc2 = CfhState.pagerCache
                if (pc2 == null || (pc2 as? android.view.View)?.isAttachedToWindow != true) {
                    val decor = act.window.decorView as? ViewGroup
                    if (decor != null) CfhViewHook.findPager(decor)
                }
            }
        }

        Logger.safe("filterMetaCheck") {

            val now = System.currentTimeMillis()
            if (now - CfhState.lastSkipTime < 10000) return@safe
            CfhState.lastSkipTime = now
            val v = CurrentVideo.current
            if (v.valid() && CfhDecide.shouldFilterMeta(v) && !Logger.quiet) Logger.d("filter meta diag: ${CfhUtil.readCaption(v)?.take(20)}")
        }
    }
}
