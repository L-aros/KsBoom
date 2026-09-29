package io.github.angbang852.manjiao.hook

import android.view.View
import android.view.ViewGroup
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

// 鈽?ContentFilterHook 娣辨媶绗簲姝ワ細娓叉煋灞傞挬锛?026-09 S3锛夈€?
// pager/adapter/provider 娓叉煋閾捐矾銆乺erank 鐩存挱閲嶆帓銆並RN 鐢靛晢鍗℃嫤鎴€?
object CfhViewHook {
    // 鐩存挱閲嶆帓妯″潡锛歝om.kuaishou.live.rerank 鍦?VerticalViewPager 婊氬姩鏃舵妸
    // LiveStreamFeed 鐩存帴濉炶繘棣栭〉淇℃伅娴併€傚畠鐨勭被琚贩娣嗭紙e$b.onPageScrolled 鍥炶皟 +
    // d.t / e$d.E 鍐呴儴鏂规硶锛夛紝浣嗘暟鎹竴瀹氫互 List / 鍗曢」瀹炰綋鐨勫舰寮忚法鏂规硶銆?
    // 绛栫暐锛氭寜銆屽洖璋冪鍚嶃€峢ook onPageScrolled锛屽苟鎵弿 rerank 鍖呯殑 List 杩斿洖鏂规硶杩囨护銆?

    // 鈽?鍘婚噸閿惈 classloader 韬唤锛氬揩鎵嬫彃浠跺寲浼氭妸鍚屽悕绫昏杩涚浜屼釜 loader锛?
    // 鎸夌被鍚嶅幓閲嶄細璁╂柊 Class 琚鍒ゃ€屽凡 hook銆嶈€岄潤榛樻紡瑁咃紙fragSeqHookedClasses
    // :1592 鏃╁凡鐢ㄦ鍐欐硶锛屾澶勭粺涓€锛?


    internal fun hookLiveRerank(xp: XposedInterface, cl: ClassLoader) {
        val pkg = "com.kuaishou.live.rerank"
        val tryNames = listOf("e", "e\$b", "d", "e\$d", "c", "b")
        var hookedAny = false
        for (tn in tryNames) {
            val cn = "$pkg.$tn"
            val cc = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
            if (!CfhState.hookedRerankCls.add(CfhUtil.hookKey(cc))) continue
            hookedAny = true
            Logger.d("rerank cls: $cn methodCount=${cc.declaredMethods.size}")
            for (m in cc.declaredMethods) {
                val nm = m.name
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection") }
                if (!hasListParam && (m.returnType.isPrimitive || m.returnType == Void.TYPE || m.returnType == java.lang.String::class.java)) continue
                if (nm.startsWith("getCurrent") || nm == "getPhoto" || nm == "getItem") continue
                Logger.safe("rerank.${cn}.${nm}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.${cn}.${nm}").intercept { chain ->
                        if (Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                            // ★ 白名单替代黑名单（2026-09-26 用户定稿「只留白名单，黑名单清理掉」）
                        try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) {}
                        }
                        val r = chain.proceed()
                        try {
                            if (r is List<*>) {
                                val before = r.size
                                // ★ 白名单替代黑名单（2026-09-26）
                            try { CfhClean.filterWhitelist(r) } catch (_: Throwable) {}
                                if (r.size != before && CfhState.rerankListDiag < 20) {
                                    CfhState.rerankListDiag++
                                    Logger.d("rerank list ${nm} filtered: $before -> ${r.size}")
                                }
                            } else if (r != null) {
                                val q = CfhProbe.findQpInObject(r)
                                if (q != null && CfhDecide.shouldFilterFeed(q)) {
                                    if (CfhState.rerankSingleDiag < 20) {
                                        CfhState.rerankSingleDiag++
                                        Logger.d("rerank single ${nm}: ${CfhUtil.readCaption(q)?.take(20)}")
                                    }
                                    return@intercept null
                                }
                            }
                        } catch (_: Throwable) {}
                        r
                    }
                }
            }
        }
        for (tn in listOf("e\$b")) {
            val cn = "$pkg.$tn"
            val cc = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
            for (m in cc.declaredMethods) {
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    Logger.safe("rerank.sel.${cn}.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.sel.${cn}.${m.name}").intercept { chain ->
                            // 鈽呪槄鈽呪槄 鍏抽敭淇锛坧robe24 瀹炶瘉锛夛細鍘熷厛鍙湪 proceed() **涔嬪悗**璋?
                            // laFind 鈥斺€?閭ｆ椂椤甸潰宸查€変腑骞跺畬鎴愮粦瀹氾紙55.414 宸蹭笂灞忥紝55.607 鎵嶅垽鑴忥紝
                            // 杩?0.19s锛岀敤鎴风湅鍒扮殑灏辨槸杩欎釜绐楀彛锛夈€?
                            // onPageSelected 鏄€岄€変腑鍗冲皢鍙戠敓銆嶇殑閫氱煡锛屽洜姝ゆ竻娲楀繀椤绘斁鍦?
                            // proceed() **涔嬪墠**锛氬厛鎶?VM/adapter 閾捐〃娓呭共鍑€锛屽啀璁╅€変腑/缁戝畾鍙戠敓锛?
                            // 鑴忛」灏辨病鏈夋満浼氳缁戝畾鍒?Fragment 涓娿€?
                            try {
                                val pos0 = chain.args.getOrNull(0) as? Int ?: -1
                                if (CfhState.rerankScrollDiag < 10) { CfhState.rerankScrollDiag++; Logger.d("rerank selected #$pos0 (pre-clean)") }
                                try { CfhWatch.laFind(force = true) } catch (_: Throwable) {}
                            } catch (_: Throwable) {}
                            val r = chain.proceed()
                            r
                        }
                    }
                } else if (m.parameterTypes.size == 3 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    Logger.safe("rerank.scroll.${cn}.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.scroll.${cn}.${m.name}").intercept { chain ->
                            // 鍚?selected锛氭竻娲楁斁鍒?proceed() 涔嬪墠锛堟粴鍔ㄥ嵆灏嗘敼鍙橀〉闈㈡椂鐨勫墠缃竻娲楋級
                            try {
                                if (CfhState.rerankScrollDiag < 10) { CfhState.rerankScrollDiag++; val pos = chain.args.getOrNull(0) as? Int ?: -1; Logger.d("rerank scroll #$pos (pre-clean)") }
                                try { CfhWatch.laFind(force = true) } catch (_: Throwable) {}
                            } catch (_: Throwable) {}
                            val r = chain.proceed()
                            r
                        }
                    }
                }
            }
        }
        try {
            val jd = Class.forName("com.kuaishou.live.rerank.d", false, cl)
            for (m in jd.declaredMethods) {
                if (m.name == "j" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType && m.returnType == java.lang.Boolean.TYPE) {
                    Logger.safe("rerank.d.j") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.d.j").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                if (r == true && !CfhState.liveTop) {
                                    val iN = chain.args.getOrNull(0) as? Int ?: -1
                                    if (CfhState.rerankJDiag < 20) { CfhState.rerankJDiag++; Logger.always("RERANKJ hit iN=" + iN + " (live 2 ahead) -> force laFind") }
                                    if (!Logger.quiet && CfhState.rerankJDiag < 20) {
                                        try {
                                            val dumpAdp = CfhState.adpRef ?: CfhState.adpRefs.firstOrNull()
                                            if (dumpAdp != null) {
                                                var dc: Class<*>? = dumpAdp.javaClass
                                                var dlvl = 0
                                                while (dc != null && dc != Any::class.java && dlvl < 3) {
                                                    for (df in dc!!.declaredFields) {
                                                        if (java.lang.reflect.Modifier.isStatic(df.modifiers)) continue
                                                        try {
                                                            df.isAccessible = true
                                                            val dv = df.get(dumpAdp) ?: continue
                                                            if (dv is List<*>) {
                                                                for ((didx, del) in dv.withIndex()) {
                                                                    if (didx > 6) break
                                                                    val dqp = del ?: continue
                                                                    val dent = Reflect.readAny(dqp, "mEntity") ?: dqp
                                                                    val dpm = Reflect.readAny(dent, "mPhotoMeta")
                                                                    val dlm = Reflect.readAny(dent, "mLivePlaybackMeta")
                                                                    val dcm = Reflect.readAny(dent, "mCommonMeta")
                                                                    val dcap = (dcm?.let { Reflect.readString(it, "mCaption") } ?: "").take(30)
                                                                    val dun = CfhUtil.readUserName(dqp, dent).take(20)
                                                                    Logger.always("RJDUMP adp[" + didx + "] ent=" + dent.javaClass.simpleName + " lm=" + (dlm != null) + " liveSid=" + (dlm?.let { Reflect.readAny(it, "mLiveStreamId") != null }) + " state=" + (dpm?.let { Reflect.readBool(it, "mCurrentLivingState") }) + " useLive=" + (dpm?.let { Reflect.readBool(it, "mUseLive") }) + " merch=" + (dpm?.let { Reflect.readAny(it, "mMerchantLiveInfo") != null }) + " btn=" + (dpm?.let { Reflect.readAny(it, "mInteractionLiveCardButton") != null }) + " clip=" + (dpm?.let { Reflect.readAny(it, "mLiveStreamClipInfo") != null }) + " cap=" + dcap + " user=" + dun)
                                                                }
                                                                break
                                                            }
                                                        } catch (_: Throwable) {}
                                                    }
                                                    dc = dc.superclass; dlvl++
                                                }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                    try { CfhWatch.laFind(true) } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                            r
                        }
                    }
                    Logger.d("hooked rerank.d.j(int)")
                }
            }
        } catch (_: Throwable) {}
        // 鈽呪槄鈽?rerank 娣辨寲鎺㈤拡锛?026-09 鐢ㄦ埛鎶ャ€岀洿鎾竴鐩村仠鍦ㄧ浜屾潯涓嶅姩銆嶏紝鏂规 C锛夛細
        // 瀹炶瘉 probe17 鈥斺€?rerank 鍒楄〃宸茶鎴戜滑杩囨护锛坮erank list E filtered: 6 -> 1锛夛紝
        // 浣嗙洿鎾粛鍦?5 绉掑悗涓婂睆锛坕mm LiveTextView t=@浼氱悊绐佸凹鏂蒋绫界煶姒达級锛屼笖
        // rerank selected/scroll 鐨勪笅鏍囨槸 #500000锛堣繙瓒呭垪琛ㄩ暱搴︼紝鏄彃鍏ュ摠鍏碉級銆?
        // 缁撹锛歳erank 涓嶆槸浠庨偅涓垪琛ㄥ彇鏁版嵁娓叉煋鐩存挱 鈥斺€?蹇呴』鐪嬫竻瀹?22 涓柟娉曠殑鐪熻韩
        // 涓?e$b 鍥炶皟閾撅紝鎵惧埌鐪熸鐨勬敞鍏ョ偣銆傛澶勫彧 dump 涓嶆敼鍙樿涓恒€?
        // 鈽?鎬ц兘淇锛堝闃?2026-09 路 M1锛夛細鏁存鏄€屽彇璇佽緭鍑恒€嶁€斺€斿洓灞傜被鐨勬柟娉?瀛楁鍏ㄩ噺
        // 鏋氫妇锛屾瘡娆¤閽╀骇鐢熷嚑鍗佽鏃ュ織銆傚師鍏堢敤 always锛堜笉鍙?quiet 闂ㄦ帶锛夆噿 鍗充娇榛樿
        // 闈欓粯妯″紡姣忔鍐峰惎鍔ㄤ篃瑕佷粯鍑犲崄娆″瓧绗︿覆鎷兼帴 + 鍑犲崄娆¤法杩涚▼ mod.log銆?
        // 鐜扮撼鍏?diag 寮€鍏筹細鎺掗殰鏃舵墦寮€銆岃瘖鏂棩蹇椼€嶅嵆瀹屾暣澶嶇幇锛岄粯璁ら浂鎴愭湰銆?
        if (Logger.diag) try {
            val jd = Class.forName("com.kuaishou.live.rerank.d", false, cl)
            Logger.always("RERANK-DUMP d methods=${jd.declaredMethods.size}")
            for (m in jd.declaredMethods) {
                Logger.always("RERANK-DUMP d.${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName} mod=${java.lang.reflect.Modifier.toString(m.modifiers)}")
            }
            val jdB = Class.forName("com.kuaishou.live.rerank.e\$b", false, cl)
            Logger.always("RERANK-DUMP e\$b methods=${jdB.declaredMethods.size} fields=${jdB.declaredFields.size}")
            for (m in jdB.declaredMethods) {
                Logger.always("RERANK-DUMP e\$b.${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName}")
            }
            for (f in jdB.declaredFields) {
                Logger.always("RERANK-DUMP e\$b fld ${f.name}:${f.type.simpleName} static=${java.lang.reflect.Modifier.isStatic(f.modifiers)}")
            }
            val jdE = Class.forName("com.kuaishou.live.rerank.e", false, cl)
            Logger.always("RERANK-DUMP e methods=${jdE.declaredMethods.size} fields=${jdE.declaredFields.size}")
            for (m in jdE.declaredMethods) {
                Logger.always("RERANK-DUMP e.${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName}")
            }
            val jdD2 = Class.forName("com.kuaishou.live.rerank.e\$d", false, cl)
            Logger.always("RERANK-DUMP e\$d methods=${jdD2.declaredMethods.size} fields=${jdD2.declaredFields.size}")
            for (m in jdD2.declaredMethods) {
                Logger.always("RERANK-DUMP e\$d.${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName}")
            }
        } catch (t: Throwable) { Logger.always("RERANK-DUMP err: ${t.message}") }
        // 鈽呪槄鈽呪槄 rerank 婧愬ご鎷︽埅锛堟柟妗?C锛?026-09锛夛細鐢?RERANK-DUMP 鎷垮埌鐪熻韩绛惧悕鍚庣‘瀹氥€?
        // 瀹炶瘉 probe17/18锛歳erank 鍒楄〃宸茶杩囨护锛? -> 1锛変絾鐩存挱浠嶄笂灞忥紝selected 涓嬫爣鏄?
        // 鍝ㄥ叺鍊?#500000 鈥斺€?鐩存挱涓嶇粡閭ｄ釜鍒楄〃銆傜湡姝ｅ叆鍙ｆ槸涓変釜鐩村悆 LiveStreamFeed 鐨勬柟娉曪細
        //   e$d.G(int, LiveStreamFeed)  鈫?甯︿綅缃紝鏈€鍍忔彃鍏ョ偣
        //   d.m(LiveStreamFeed)         鈫?瀹炰綋鍏ュ彛
        //   e.doInject()                鈫?鎵ц娉ㄥ叆鐨勫姩浣?
        // 涓夊閮藉湪銆宖eed 瀹炰綋杩涘叆 rerank 绠＄嚎銆嶉樁娈碉紙婧愬ご渚э紝闈炴覆鏌撴秷璐逛晶锛夛紝
        // 绗﹀悎鏈ā鍧椼€屽彧鍋氭簮澶存嫤鎴€嶅師鍒欍€傚懡涓嵆鎷掔粷锛屼娇鐩存挱鍗℃牴鏈繘涓嶄簡 rerank銆?
        try {
            // ★ 版本自适应（2026-09）：`d` / `e$d` / `e` 是**混淆短名**，每版重新分配。
            // 走 KsResolve.resolveInPackage —— 先试既有短名（行为与改前一致），
            // 全部落空时按「包 + 方法形状」结构发现（DexKit 扫包），找到即用。
            // 仍未命中则返回 null → 本段整体跳过，**不抛异常、不影响其余 hook**。
            val dCls = io.github.angbang852.manjiao.adapt.KsResolve.resolveInPackage(
                "rerank.d", cl, "com.kuaishou.live.rerank", listOf("d"),
                listOf(
                    io.github.angbang852.manjiao.adapt.KsResolve.Feature(name = "m", paramCount = 1),
                    io.github.angbang852.manjiao.adapt.KsResolve.Feature(name = "r", paramCount = 3),
                    io.github.angbang852.manjiao.adapt.KsResolve.Feature(name = "g", paramCount = 2),
                )
            ) ?: return
            val eDCls = io.github.angbang852.manjiao.adapt.KsResolve.resolveInPackage(
                "rerank.eD", cl, "com.kuaishou.live.rerank", listOf("e\$d", "e\$b"),
                listOf(
                    io.github.angbang852.manjiao.adapt.KsResolve.Feature(name = "G", paramCount = 2),
                )
            ) ?: return
            val eCls = io.github.angbang852.manjiao.adapt.KsResolve.resolveInPackage(
                "rerank.e", cl, "com.kuaishou.live.rerank", listOf("e"),
                listOf(
                    io.github.angbang852.manjiao.adapt.KsResolve.Feature(name = "doInject", paramCount = 0),
                )
            ) ?: return
            var nRr = 0
            fun rrHook(cls: Class<*>, m: java.lang.reflect.Method, tag: String) {
                Logger.safe(tag) {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId(tag).intercept { chain ->
                        // 鍏ュ弬鐩存帴甯?LiveStreamFeed 鐨勶紙G/m锛夛細婧愬ご鎷掔粷锛屼笉璁╁畠杩涚绾?
                        try {
                            if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhState.liveTop) {
                                for (a in chain.args) {
                                    if (a == null) continue
                                    val an = a.javaClass.name
                                    if (an.contains("LiveStreamFeed")) {
                                        CfhState.rerankInjectBlocked++
                                        if (CfhState.rerankInjectBlocked <= 20) {
                                            Logger.always("RERANK-BLOCK $tag arg=${an.substringAfterLast('.')} cap=${CfhUtil.readCaption(a)?.take(22)}")
                                        }
                                        return@intercept null
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        val r = chain.proceed()
                        // doInject锛氭敞鍏ュ姩浣滃畬鎴愬悗绔嬪嵆娓呮壂锛堝畠鍙兘浠庡埆澶勫彇鏁版嵁娉ㄥ叆锛?
                        try {
                            if (tag.endsWith("doInject") && Prefs.bool(Prefs.K_FLT_LIVE, false) && !CfhState.liveTop) {
                                if (CfhState.rerankInjectBlocked <= 20) Logger.always("RERANK doInject -> force laFind")
                                CfhWatch.laFind(force = true)
                            }
                        } catch (_: Throwable) {}
                        r
                    }
                }
                nRr++
            }
            for (m in eDCls.declaredMethods) {
                if (m.name == "G" && m.parameterTypes.size == 2 && m.parameterTypes[0] == Int::class.javaPrimitiveType) rrHook(eDCls, m, "rerank.eD.G")
            }
            for (m in dCls.declaredMethods) {
                if (m.name == "m" && m.parameterTypes.size == 1 && m.parameterTypes[0].name.contains("LiveStreamFeed")) rrHook(dCls, m, "rerank.d.m")
            }
            for (m in eCls.declaredMethods) {
                if (m.name == "doInject") rrHook(eCls, m, "rerank.e.doInject")
            }
            // 鈽呪槄鈽呪槄 鐪熸鐨勯噸鐏屽叆鍙ｏ紙probe24 璋冪敤鏍堝疄璇侊級锛?
            //   fltCaller: p.h <- ... <- Hiesh.g <- d.v <- d.u <- n.run
            // 鐩存挱 rerank 鐢?Runnable(n.run) 椹卞姩锛岀粡 d.u()/d.v(boolean) 瑙﹀彂锛?
            // 鏈€缁堢敱 d.r(Map,List,boolean) / d.g(List,List) 鎶婃暟鎹亴鍥?feed 鈥斺€?瀹冧滑
            // 鐩存帴鎺ユ敹瑕佺亴鍏ョ殑 List锛岃繖閲岃繃婊ゅ嵆婧愬ご鎷︽埅锛堟鍓嶉挬鐨?eD.G/d.m/doInject
            // 閮戒笉鍦ㄨ繖鏉¤矾寰勪笂锛屾晠 RERANK-BLOCK 鎭掍负 0锛夈€?
            // 璇ヨ矾寰勬瘡绉掗噸鐏屼竴娆★紝鎶婂凡鍒犻櫎鐨勮剰椤瑰甫鍥烇紝姝ｆ槸銆屽垹浜嗗張鍥炴潵銆嶇殑鏍瑰洜銆?
            fun rrListHook(m: java.lang.reflect.Method, tag: String) {
                Logger.safe(tag) {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId(tag).intercept { chain ->
                        // 鍙傛暟閲岀殑 List 灏卞湴杩囨护锛堢亴鍏ュ墠鍓旀帀鑴忛」锛?
                        try {
                            if (!CfhState.liveTop) {
                                var n = 0
                                for (a in chain.args) {
                                    if (a is MutableList<*>) {
                                        @Suppress("UNCHECKED_CAST")
                                        val removed = try { CfhClean.filterWhitelist(listOf(a as MutableList<Any?>)) } catch (_: Throwable) { 0 } // ★ 白名单替代黑名单
                                        n += removed
                                        if (removed > 0) {
                                            CfhState.rerankInjectBlocked++
                                            if (CfhState.rerankInjectBlocked <= 20) Logger.always("RERANK-FILTER $tag argList removed=$removed left=${a.size}")
                                        }
                                    }
                                }
                                if (n > 0) { /* 宸插氨鍦拌繃婊?*/ }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
                nRr++
            }
            for (m in dCls.declaredMethods) {
                if (m.name == "r" && m.parameterTypes.size == 3 && m.parameterTypes.any { it.name.contains("List") }) rrListHook(m, "rerank.d.r")
                if (m.name == "g" && m.parameterTypes.size == 2 && m.parameterTypes.all { it.name.contains("List") }) rrListHook(m, "rerank.d.g")
                if (m.name == "d" || m.name == "e") {
                    if (m.parameterTypes.size == 4 && m.parameterTypes.any { it.name.contains("List") }) rrListHook(m, "rerank.d.$m")
                }
            }
            // 鈽呪槄鈽呪槄 鏂规 A锛氭嫤閲嶇亴閾句腑娈碉紙probe24 鏍堝疄璇?d.v <- d.u <- n.run锛夛細
            // d.u()/d.v(boolean) 鏄€宯.run 鍙戣捣 鈫?鐪熸閲嶇亴銆嶇殑蹇呯粡涓銆傛澶勪笉鏀硅涓猴紝
            // 鍏堟妸瀹冧滑瀹為檯鎼哄甫/瑙︾鐨?List 鍏ㄩ儴 dump 鍑烘潵 鈥斺€?涔嬪墠閽?d.r/d.g 闆惰Е鍙戯紝
            // 璇存槑閲嶇亴璧扮殑涓嶆槸閭ｄ袱涓柟娉曪紝蹇呴』鍏堢湅娓?d.u/d.v 鍒板簳鍔ㄤ簡鍝簺瀹瑰櫒銆?
            fun rrPeekHook(m: java.lang.reflect.Method, tag: String) {
                Logger.safe(tag) {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId(tag).intercept { chain ->
                        val r = chain.proceed()
                        try {
                            // 鈽?鎬ц兘淇锛堝闃?2026-09 路 M1锛夛細rerankPeek<24 鍙檺娆′笉闄愭垚鏈€斺€?
                            // 姣忔杩涘叆瑕佸 d 瀵硅薄鍋?4 灞傚叏瀛楁鍙嶅皠 + Map/Set 鍐呭 dump锛?
                            // 涓旇繖娈佃窇鍦?hook 鍥炶皟锛堜富绾跨▼閲嶇亴璺緞锛夐噷銆傜撼鍏?diag 闂ㄦ帶鍚?
                            // 榛樿闆舵垚鏈紝鎺掗殰鎵撳紑銆岃瘖鏂棩蹇椼€嶅嵆瀹屾暣澶嶇幇銆?
                            if (Logger.diag && !CfhState.liveTop && CfhState.rerankPeek < 24) {
                                CfhState.rerankPeek++
                                // 鍏ㄥ瓧娈?dump锛堝悕+绫诲瀷+鍊肩被鍚嶏級锛氫笂涓€鐗堝彧鎵?List 瀛楁鍗存墦涓嶅嚭
                                // 浠讳綍涓滆タ锛岃鏄?d 鑷韩涓嶆寔 List 鈥斺€?瀹冪粡鎴愬憳瀵硅薄闂存帴鎸佸鍣ㄣ€?
                                val self = chain.thisObject
                                val sb = StringBuilder("RERANK-PEEK $tag this=")
                                sb.append(self?.javaClass?.name ?: "null")
                                var cc: Class<*>? = self?.javaClass
                                var lv = 0
                                while (cc != null && cc != Any::class.java && lv < 4) {
                                    for (f in cc!!.declaredFields) {
                                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                        try {
                                            f.isAccessible = true
                                            val v = f.get(self)
                                            sb.append(" | ${f.name}:${f.type.simpleName}=${v?.javaClass?.simpleName ?: "null"}")
                                            if (v is List<*>) {
                                                sb.append("(n=${v.size},el=${v.firstOrNull()?.javaClass?.simpleName ?: "-"})")
                                                val fq = v.firstOrNull()?.let { CfhProbe.findQpInObject(it) }
                                                if (fq != null && (try { CfhDecide.shouldFilterFeed(fq) } catch (_: Throwable) { false })) sb.append("DIRTY")
                                            } else if (v != null && v.javaClass.isArray) {
                                                val len = java.lang.reflect.Array.getLength(v)
                                                val fe = if (len > 0) java.lang.reflect.Array.get(v, 0) else null
                                                sb.append("(len=$len,el=${fe?.javaClass?.simpleName ?: "-"})")
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                    cc = cc.superclass; lv++
                                }
                                Logger.always(sb.toString())
                                // 鈽呪槄 Map/Set 鍐呭 dump锛堢敱涓婁竴鐗堝瓧娈电粨鏋勫緱鍒帮細a:Map=HashMap銆?
                                // h:Set=HashSet 鎵嶆槸 d 鐪熸鎸佹湁鐨勫鍣紱c/d 鏄?long 璁℃椂鎴筹紝
                                // e/f 涓鸿鏁?鈥斺€?鍗炽€屽仠鐣欒鏃跺櫒銆? 鍊欓€夋睜 + 鍘婚噸闆嗭級銆?
                                // 鐩存挱椤规瀬鍙兘韬哄湪杩欎釜 Map 閲岋紝鐪嬫竻瀹冪殑閿€兼墠鑳藉畾浣嶆敞鍏ョ偣銆?
                                try {
                                    val fb = self?.javaClass?.getDeclaredField("a")
                                    fb?.isAccessible = true
                                    val mv = fb?.get(self)
                                    if (mv is Map<*, *>) {
                                        val msb = StringBuilder("RERANK-MAP a size=${mv.size}")
                                        var mi = 0
                                        for ((k, v) in mv) {
                                            if (mi >= 6) break
                                            val kq = if (k != null) CfhProbe.findQpInObject(k) else null
                                            val vq = if (v != null) CfhProbe.findQpInObject(v) else null
                                            msb.append(" | [${k?.javaClass?.simpleName}:${kq?.let { CfhUtil.readCaption(it)?.take(14) } ?: k}]->")
                                            msb.append("[${v?.javaClass?.simpleName}:${vq?.let { CfhUtil.readCaption(it)?.take(14) } ?: v}]")
                                            mi++
                                        }
                                        Logger.always(msb.toString())
                                    }
                                    val fh = self?.javaClass?.getDeclaredField("h")
                                    fh?.isAccessible = true
                                    val sv = fh?.get(self)
                                    if (sv is Set<*>) {
                                        val ssb = StringBuilder("RERANK-SET h size=${sv.size}")
                                        var si = 0
                                        for (e in sv) {
                                            if (si >= 6) break
                                            ssb.append(" | ${e?.javaClass?.simpleName}:${(e as? String)?.take(16) ?: e}")
                                            si++
                                        }
                                        Logger.always(ssb.toString())
                                    }
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                        r
                    }
                }
                nRr++
            }
            for (m in dCls.declaredMethods) {
                if (m.name == "u" && m.parameterTypes.isEmpty()) rrPeekHook(m, "rerank.d.u")
                if (m.name == "v" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType) rrPeekHook(m, "rerank.d.v")
            }
            Logger.once("rerank.armed", "RERANK source hooks installed=$nRr")
        } catch (t: Throwable) { Logger.always("RERANK source hook err: ${t.message}") }
        if (hookedAny) Logger.d("hookLiveRerank done pkg=$pkg")
    }

    // 鈽?鐩存挱甯﹁揣棰勮鍗★紙RN CombinedCard锛夊彇璇侊細hook KRN 瀹瑰櫒 KrnFragment 鐢熷懡鍛ㄦ湡锛?
    // 鎵撳嵃鍙傛暟(bundleId绛? + 鍒涘缓璋冪敤鏍堬紝鍙嶆煡 feed 閲岃皝鍦ㄥ垱寤哄畠锛屾壘鍒?Java 灞傛暟鎹簮澶淬€?

    internal fun hookKrnProbe(xp: XposedInterface, cl: ClassLoader) {
        if (CfhState.krnProbeHooked) return
        CfhState.krnProbeHooked = true
        // 鈽?绾帰閽堬紙鍙墦鏃ュ織涓嶆敼鍙樿涓猴級锛氶潤榛樻ā寮忥紙榛樿锛変笉瀹夎锛岀渷鎺?KrnFragment
        // 5 涓敓鍛藉懆鏈?hook 鐨勫父椹诲紑閿€锛涙帓鏌?KRN 闂鏃舵妸 鏃ュ織闈欓粯 鍏虫帀閲嶅惎鍗虫仮澶?
        if (Logger.quiet) return
        val probeCl = cl
        val retry = object : Runnable {
            override fun run() {
                var done = false
                for (cn in listOf("com.kuaishou.krn.page.KrnFragment", "KrnFragment")) {
                    val c = Reflect.findClass(cn, probeCl) ?: continue
                    var hooked = 0
                    for (mn in listOf("onViewCreated", "onCreateView", "onAttach", "setArguments", "onResume")) {
                        val m = c.declaredMethods.firstOrNull { it.name == mn } ?: continue
                        try {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnProbe.$cn.$mn").intercept { chain ->
                                try {
                                    if (CfhState.krnProbeCount < 6) {
                                        CfhState.krnProbeCount++
                                        val args = chain.args.joinToString(",") { a ->
                                            when (a) {
                                                null -> "null"
                                                is android.os.Bundle -> {
                                                    val sb = StringBuilder()
                                                    try {
                                                        for (key in a.keySet()) {
                                                            val v = a.get(key)
                                                            sb.append("$key=${if (v is String) v.take(80) else v?.javaClass?.simpleName ?: "null"}; ")
                                                        }
                                                    } catch (_: Throwable) {}
                                                    "Bundle[$sb]"
                                                }
                                                else -> "${a.javaClass.simpleName}"
                                            }
                                        }
                                        Logger.always("KRNPROBE $mn #${CfhState.krnProbeCount} args=[$args]\n" +
                                            Thread.currentThread().stackTrace.drop(1).take(22).joinToString("\n"))
                                    }
                                } catch (_: Throwable) {}
                                chain.proceed()
                            }
                            hooked++
                        } catch (t: Throwable) {
                            Logger.always("krnProbe hook $mn exc: ${t.message}")
                        }
                    }
                    Logger.once("krnprobe.$cn", "krnProbe hooked $cn methods=$hooked")
                    if (hooked > 0) { done = true; break }
                }
                if (!done && CfhState.krnProbeRetries < 40) {
                    CfhState.krnProbeRetries++
                    if (CfhState.krnProbeRetries == 1 || CfhState.krnProbeRetries % 10 == 0) Logger.always("krnProbe retry #${CfhState.krnProbeRetries}: KrnFragment not loaded yet")
                    CfhState.handler.postDelayed(this, 2000)
                } else if (!done) {
                    Logger.once("krnprobe.giveup", "krnProbe GIVE UP after ${CfhState.krnProbeRetries} retries: KrnFragment never loaded")
                }
            }
        }
        CfhState.handler.postDelayed(retry, 2000)
    }

    // 鈽?KRN 鐢靛晢/鐩存挱甯﹁揣鍗℃覆鏌撴簮澶存嫤鎴紙妯℃嫙鍣ㄥ凡楠岃瘉鍒ゅ畾閿氱偣锛夛細
    // KrnReactContainerView.getLaunchModel 杩斿洖鐨?LaunchModel.f Bundle 鍚?
    // bundleId=Kwaishop*锛堝疄璇?KwaishopRNCPrecisionMarketing/KwaishopCLivePreviewCommodityCard
    // 鍚屾棌锛夈€傚懡涓洿鎾甫璐?bundle 鏃舵竻绌鸿 Bundle 閿€斺€斿崱鐗囨嬁涓嶅埌鏁版嵁鍗充笉娓叉煋
    // 锛堝垹闄ゅ紡鎷︽埅锛岄潪鏇挎崲锛夈€侹RNLM 鏃ュ織淇濈暀闄愭瀹¤銆?


    internal fun hookKrnReactContainerView(xp: XposedInterface, cl: ClassLoader) {
        if (CfhState.krnRcvHooked) return
        CfhState.krnRcvHooked = true
        val probeCl = cl
        val retry = object : Runnable {
            override fun run() {
                val c = Reflect.findClass("com.kuaishou.krn.page.KrnReactContainerView", probeCl)
                if (c == null) {
                    if (CfhState.krnRcvRetries < 40) {
                        CfhState.krnRcvRetries++
                        if (CfhState.krnRcvRetries == 1 || CfhState.krnRcvRetries % 10 == 0) Logger.always("krnRcv retry #${CfhState.krnRcvRetries}")
                        CfhState.handler.postDelayed(this, 2000)
                    } else Logger.once("krnrcv.giveup", "krnRcv GIVE UP")
                    return
                }
                var hooked = 0
                val gm = c.declaredMethods.firstOrNull { it.name == "getLaunchModel" }
                if (gm != null) {
                    try {
                        xp.hook(gm).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnRcv.getLaunchModel").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                if (r != null) {
                                    var fBundle: android.os.Bundle? = null
                                    var fc: Class<*>? = r.javaClass
                                    var lvl = 0
                                    while (fc != null && fc != Any::class.java && lvl < 3 && fBundle == null) {
                                        for (f in fc!!.declaredFields) {
                                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                            if (f.type == android.os.Bundle::class.java) {
                                                try { f.isAccessible = true; fBundle = f.get(r) as? android.os.Bundle } catch (_: Throwable) {}
                                                if (fBundle != null) break
                                            }
                                        }
                                        fc = fc.superclass; lvl++
                                    }
                                    val bid = fBundle?.get("bundleId") as? String
                                    val isShop = bid != null && bid.startsWith("Kwaishop")
                                    if (isShop && Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                                        val keys = fBundle!!.keySet().toList()
                                        for (k in keys) fBundle!!.remove(k)
                                        Logger.always("KRNZAP #${CfhState.krnLmDiag} bundleId=$bid keysCleared=${keys.size}")
                                        CfhState.krnLmDiag++
                                    } else if (CfhState.krnLmDiag < 3 && bid != null) {
                                        Logger.always("KRNLM pass bundleId=$bid")
                                        CfhState.krnLmDiag++
                                    }
                                }
                            } catch (_: Throwable) {}
                            r
                        }
                        hooked++
                    } catch (t: Throwable) { Logger.always("krnRcv gm hook exc: ${t.message}") }
                }
                // 鈽?绗簩鏉℃覆鏌撹矾寰勫厹搴曪細KrnReactRootView.setBundleId 鏄墍鏈?KRN root
                // view 娉ㄥ叆 bundle 鐨勭粺涓€鍏ュ彛锛圕ombinedCard 骞抽摵璺緞涓嶈蛋 getLaunchModel锛夈€?
                // 鍛戒腑 Kwaishop* 涓旂洿鎾紑鍏冲紑 鈫?娓?ReactStyleProps 闃绘柇娓叉煋锛堝垹闄ゅ紡锛夈€?
                val rv = Reflect.findClass("com.kuaishou.krn.widget.react.KrnReactRootView", probeCl)
                if (rv != null) {
                    val sb = rv.declaredMethods.firstOrNull { it.name == "setBundleId" }
                    val gp = rv.declaredMethods.firstOrNull { it.name == "getReactStyleProps" }
                    if (sb != null) {
                        try {
                            xp.hook(sb).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnRv.setBundleId").intercept { chain ->
                                val bid = chain.args.firstOrNull() as? String
                                if (bid != null && bid.startsWith("Kwaishop") && Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                                    Logger.always("KRNZAP-ROOT bundleId=$bid")
                                    try {
                                        // attach 鏃?getBundleId 浠嶄负 null锛堝疄璇?bid=null锛夛紝
                                        // setBundleId 鏄?bundleId 棣栨鍙鏃舵満锛屾澶勭疆 GONE
                                        // 淇濈暀鍒颁笂灞忥紙鏄剧ず灞傚垹闄ゅ紡鎷︽埅锛?
                                        (chain.thisObject as? android.view.View)?.visibility = android.view.View.GONE
                                        Logger.always("KRNZAP-GONE bundleId=$bid")
                                    } catch (_: Throwable) {}
                                    try {
                                        val root = chain.thisObject
                                        if (gp != null) {
                                            val style = gp.invoke(root)
                                            if (style != null) {
                                                var sf: Class<*>? = style.javaClass
                                                while (sf != null && sf != Any::class.java) {
                                                    for (f in sf!!.declaredFields) {
                                                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                                        try {
                                                            f.isAccessible = true
                                                            if (!f.type.isPrimitive) f.set(style, null)
                                                        } catch (_: Throwable) {}
                                                    }
                                                    sf = sf.superclass
                                                }
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }
                                chain.proceed()
                            }
                        } catch (t: Throwable) { Logger.always("krnRv sb hook exc: ${t.message}") }
                    }
                }
                // 鈽?鏄剧ず灞傛嫤鎴紙缁堥槻绾匡級锛歛ttach 鏃?bundleId 宸叉敞鍏ワ紙setBundleId
                // 鍏堜簬 attach 瀹炶瘉锛夛紝鍙屼繚闄┿€傚懡涓?Kwaishop* 鈫?GONE銆?
                val rv2 = Reflect.findClass("com.kuaishou.krn.widget.react.KrnReactRootView", probeCl)
                if (rv2 != null) {
                    val att = rv2.declaredMethods.firstOrNull { it.name == "onAttachedToWindow" }
                    val gb = rv2.declaredMethods.firstOrNull { it.name == "getBundleId" }
                    if (att != null && gb != null) {
                        try {
                            xp.hook(att).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnRv.attach").intercept { chain ->
                                val proceedResult = chain.proceed()
                                try {
                                    val root = chain.thisObject
                                    val bid = gb.invoke(root) as? String
                                    if (bid != null && bid.startsWith("Kwaishop") && Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                                        (root as? android.view.View)?.visibility = android.view.View.GONE
                                        Logger.always("KRNZAP-GONE-ATT bundleId=$bid")
                                    }
                                } catch (_: Throwable) {}
                                proceedResult
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        // 鈽?琛ユ帴绾匡細retry Runnable 鏋勯€犲悗浠庢湭琚皟搴︼紙postDelayed 鍙瓨鍦ㄤ簬
        // hookKrnProbe锛夛紝鏁存潯 KRN 鎷︽埅閾捐矾瀹為檯浠庢湭瀹夎
        CfhState.handler.postDelayed(retry, 2000)
    }





    internal fun findPager(v: View) {
        val n = v.javaClass.name
        val isPager = n.contains("CustomAnimationViewPager") || n.contains("ScrollStrategyViewPager") ||
            n.contains("VerticalViewPager") || n.contains("LiveSlideViewPager") || n.contains("LiveSafeViewPager") ||
            (v.id != View.NO_ID && try { v.resources.getResourceEntryName(v.id) == "nasa_groot_view_pager" } catch (_: Throwable) { false }) ||
            (v.id != View.NO_ID && try { v.resources.getResourceEntryName(v.id) == "milano_container_layout" } catch (_: Throwable) { false })
        if (isPager) {
            // 鈽?闄堟棫寮曠敤澶辨晥锛堝闃?2026-09锛夛細Activity 閲嶅缓鍚庢棫 pager 宸?detach锛屼絾
            // pagerCache 姝ゅ墠鍙湪 null 鏃舵洿鏂扳€斺€旀棫 Activity 瑙嗗浘閾捐闈欐€佸己寮曠敤鑷宠繘绋?
            // 缁撴潫锛屼笖 check/laFind 鎸佺画瀵规瀵硅薄鍋氬姛銆傜紦瀛?detached 鏃跺厑璁歌鐩栧埛鏂?
            val stale = (CfhState.pagerCache as? View)?.isAttachedToWindow == false
            if (CfhState.pagerCache == null || stale) CfhState.pagerCache = v
            try { hookPagerClass(v.javaClass) } catch (_: Throwable) {}
            val adp = try { Reflect.callMethod(v, "getAdapter") } catch (_: Throwable) { null }
            if (adp == null) {
                if (CfhState.feedPagerFound && !stale) return
                Logger.safe("feedPagerNoAdp") { Logger.d("feedPager found but no adapter yet: ${v.javaClass.name}") }
                return
            }
            val isFirst = !CfhState.feedPagerFound || stale
            CfhState.feedPagerFound = true
            if (isFirst) {
                CfhState.adpRef = adp
                CfhState.pagerCache = v
            }
            if (CfhState.feedPagerLogCount < 20) {
                CfhState.feedPagerLogCount++
                Logger.safe("feedPagerLog") { Logger.d("feedPager: ${v.javaClass.name} adp=${adp.javaClass.name} first=$isFirst") }
            }
            try {
                if (CfhState.adpRefs.none { it === adp }) {
                    // 鈽?涓婇檺闃叉硠婕忥紙瀹￠槄 2026-09锛夛細adpRefs 鍘熷厛鍙涓嶅噺锛屾瘡娆?Activity
                    // 閲嶅缓鏂板涓€涓?adapter 寮哄紩鐢ㄣ€傝秴 6 涓厛娓呯┖鍐嶇暀褰撳墠浠ｉ檯锛坙aFind 鍙?
                    // 鎶婅繖閲屽綋鍊欓€夋牴闆嗭紝鏃т唬闄呮棤浠峰€硷級
                    if (CfhState.adpRefs.size >= 6) CfhState.adpRefs.clear()
                    CfhState.adpRefs.add(adp)
                }
            } catch (_: Throwable) {}
            if (adp.javaClass.name.startsWith("l3c")) Logger.d("feedPager DETAIL-adp: ${adp.javaClass.name} pager=${v.javaClass.simpleName}")
            // 鈽?浠?adapter 鍙嶅悜鎵?VM锛坒ragSeq aq 鏈皟鐢ㄦ椂鐨勬浛浠ｈ矾寰勶級锛?
            // 鎵?adapter 瀛楁鎵?SlidePlayViewModel锛岃 vmRef + hookViewModel + filterVmLists
            if (CfhState.vmRef == null) {
                if (CfhState.adpDumpCount < 3) { CfhState.adpDumpCount++; val sb = StringBuilder("ADPDUMP ${adp.javaClass.name}:"); var dc: Class<*>? = adp.javaClass; var dl = 0; while (dc != null && dc != Any::class.java && dl < 4) { for (df in dc!!.declaredFields) { if (java.lang.reflect.Modifier.isStatic(df.modifiers)) continue; try { df.isAccessible = true; val dv = df.get(adp); sb.append(" ${df.name}=${dv?.javaClass?.simpleName ?: "null"}") } catch (_: Throwable) {} }; dc = dc.superclass; dl++ }; Logger.evidence("ADPDUMP", sb.toString()) }
                var c2: Class<*>? = adp.javaClass
                var lvl2 = 0
                while (c2 != null && c2 != Any::class.java && lvl2 < 4) {
                    for (f2 in c2!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f2.modifiers)) continue
                        try {
                            f2.isAccessible = true
                            val fv = f2.get(adp) ?: continue
                            if (fv.javaClass.name.contains("SlidePlay") || fv.javaClass.name.contains("ViewModel")) {
                                CfhState.vmRef = fv
                                Logger.once("vmfromadp", "vmFromAdp: ${fv.javaClass.name} via ${f2.name}")
                                try { CfhFeedHook.hookViewModel(fv) } catch (_: Throwable) {}
                                try { CfhWash.filterVmLists(fv) } catch (_: Throwable) {}
                                break
                            }
                        } catch (_: Throwable) {}
                    }
                    if (CfhState.vmRef != null) break
                    c2 = c2.superclass; lvl2++
                }
            }

            // 鈽?鎸佺画娓呮礂锛歷mFromAdp 棣栨璁?vmRef 鍚?filterVmLists 鍙皟浜嗕竴娆★紙姝ゆ椂 i 鍙兘绌猴級銆?
            // 鍚庣画 feed 鏁版嵁鍔犺浇鍚?i 琚～鍏咃紝浣?fragSeq aq 涓嶈皟鐢?鈫?filterVmLists 涓嶅啀瑙﹀彂銆?
            // findPager 姣?~3s 鐢?check() 瑙﹀彂锛屾澶勮ˉ璋?filterVmLists锛?00ms 鑺傛祦鑷槻杩囧害锛?
        if (CfhState.vmRef != null) { try { CfhWash.filterVmLists(CfhState.vmRef!!) } catch (_: Throwable) {} }
        // LAFIND锛氳剰鍏冪礌韬唤鍙嶆煡鐪熸簮瀛楁锛堣瘖鏂敤锛?
        try { CfhWatch.laFind() } catch (_: Throwable) {}

            try { hookPagerAdapter(adp.javaClass) } catch (t: Throwable) { Logger.always("hookPagerAdapter exc: ${t.message}") }
            if (isFirst) {
                for (provName in listOf("G", "H", "getProvider")) {
                    val provider = try { Reflect.callMethod(adp, provName) } catch (_: Throwable) { null }
                    if (provider != null) {
                        Logger.safe("feedPagerProv") { Logger.d("feedPager provider via $provName: ${provider.javaClass.name}") }
                        hookDataProvider(provider.javaClass)
                        break
                    }
                }
            }
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { findPager(it) }
            }
        }
    }
    private fun hookPagerClass(startCls: Class<*>) {
        try {
            val xp = CfhState.xpRef ?: return
            var cc: Class<*>? = startCls
            var lvl = 0
            while (cc != null && cc != Any::class.java && lvl < 6) {
                val clsNow = cc
                if (!CfhState.hookedPagerCls.add(CfhUtil.hookKey(clsNow))) { cc = cc.superclass; lvl++; continue }
                for (m in clsNow.declaredMethods) {
                    val nm = m.name
                    if (!m.returnType.isPrimitive && m.returnType != Void.TYPE &&
                        (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType ||
                         m.parameterTypes.size == 2 && m.parameterTypes[1] == Int::class.javaPrimitiveType)) {
                        Logger.safe("hookPager.${clsNow.simpleName}.${nm}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("pager.${clsNow.name}.${nm}").intercept { chain ->
                                val result = chain.proceed()
                                try {
                                    val pos = chain.args.lastOrNull() as? Int ?: -1
                                    // ★ 翻页即拉当前条（2026-09-24）：不等兜底定时器
                                    //   （最长 800ms 延迟），滑动瞬间就更新，避免
                                    //   「画面已换、跟踪还停在上一条」的观感。
                                    //   廉价操作（一次反射调用），且内部按 photoId 判重。
                                    try { CurrentPhotoHook.pokeNow() } catch (_: Throwable) {}
                                    if (result != null) {
                                        val qp = CfhProbe.findQpInObject(result)
                                        val hit = if (qp != null) CfhDecide.shouldFilterFeed(qp) else false
                                        if (CfhState.pagerDiag < 25) {
                                            CfhState.pagerDiag++
                                            Logger.probe { "pager i ${nm}(#$pos) -> ${result.javaClass.simpleName} hit=$hit qp=${qp != null}" }
                                        }
                                        if (qp != null && hit) {
                                            // ★★★ 替换动作已停用（2026-09-24）。
                                            //
                                            //   用户实测症状（这一条直接指向本处）：
                                            //     「出现两个文案一样、昵称不一样、视频不一样的内容。
                                            //       其中一个划出去划回来视频会变。」
                                            //
                                            //   机制（代码依据）：
                                            //   `CfhSwap.writeQpInto` 会把目标对象里的 QPhoto 字段
                                            //   **整个指向另一个 cleanQp**：
                                            //   ```kotlin
                                            //   if (qpClass.isAssignableFrom(f.type)) {
                                            //       f.set(o, cleanQp); swapped++    // ← 对象级替换
                                            //   }
                                            //   ```
                                            //   于是同一条 QPhoto 被写到**两个位置** ——
                                            //   两处的「文案」字段相同（同一个对象），
                                            //   但播放器/昵称视图各自缓存了不同状态 ⇒
                                            //   「文案一样、昵称不一样、视频不一样」。
                                            //   划出去再划回来，位置重绑 → 视频又变。
                                            //
                                            //   而且用户此前明确要求过：**「尽量直接删，不要替换」**。
                                            //   本处违背了该要求，现停用替换，只保留删除链路
                                            //   （CfhClean/CfhPurge 的 sanitize 会直接移除脏项）。
                                            if (false) {
                                            val clean = if (CfhProbe.allowSwap(pos)) CfhSwap.pickFromQueue() else null
                                            if (clean != null) {
                                                val sw = CfhSwap.writeQpInto(result, clean)
                                                CfhState.pagerSwapCount++
                                                CfhProbe.noteSwap(pos, sw)
                                            }
                                            }
                                        } else if (qp != null && !hit && CfhState.liveWindowDiag < 8) {
                                            // 鈽?瑙嗛鍗℃槸鍚﹀甫"鐩存挱娴獥/杩涘叆鐩存挱闂村紩瀵?锛氭壘 QP 鏍戦噷鐨?live 鐘舵€佸瓧娈?
                                            val liveInfo = CfhProbe.findLiveWindowField(qp)
                                            if (liveInfo != null) {
                                                CfhState.liveWindowDiag++
                                                Logger.d("LIVEWIN ${nm}(#$pos) $liveInfo cap=${CfhUtil.readCaption(qp)?.take(16)}")
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {}
                                result
                            }
                        }
                    }
                }
                cc = cc.superclass; lvl++
            }
        } catch (_: Throwable) {}
    }
    private fun hookDataProvider(c: Class<*>) {
        val xp = CfhState.xpRef ?: return
        synchronized(CfhState.hookedProvClasses) { if (!CfhState.hookedProvClasses.add(c.name)) return }
        Logger.d("hookDataProvider: ${c.name}")
        for (m in c.declaredMethods) {
            Logger.d("  prov m: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
            val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
            if (isRetList) {
                Logger.safe("hookProvList.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("provL.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (!CfhState.provDiag && result is MutableList<*> && result.isNotEmpty()) {
                                CfhState.provDiag = true
                                val elem = result[0]
                                Logger.d("provList diag: size=${result.size} elemCls=${elem?.javaClass?.name}")
                                val qp = elem?.let { CfhProbe.findQpInObject(it) }
                                Logger.d("provList qp: ${qp != null}")
                            }
                            if (result is MutableList<*>) {
                                val hits = result.filter { it != null && (try { CfhDecide.shouldFilterFeed(it) } catch (_: Throwable) { false }) }
                                if (hits.isEmpty()) {
                                    val wrapHits = result.filter { it != null && CfhProbe.findQpInObject(it)?.let { qp -> CfhDecide.shouldFilterFeed(qp) } == true }
                                    if (wrapHits.isNotEmpty()) {
                                        @Suppress("UNCHECKED_CAST")
                                        (result as MutableList<Any?>).removeAll(wrapHits)
                                        Logger.d("provWrap filtered ${wrapHits.size} via ${m.name}")
                                    }
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    (result as MutableList<Any?>).removeAll(hits)
                                    Logger.d("prov filtered ${hits.size} via ${m.name}")
                                }
                            }
                        } catch (_: Throwable) {}
                        result
                    }
                }
            }
            if (m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == Int::class.javaPrimitiveType && !m.returnType.isPrimitive && m.returnType != Void.TYPE) {
                Logger.safe("hookProvGet.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("provG.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (result != null) {
                                val qp = CfhProbe.findQpInObject(result)
                                if (qp != null && CfhDecide.shouldFilterFeed(qp)) {
                                    Logger.d("provGet blocked pos=${chain.args[0]} ${CfhUtil.readCaption(qp)?.take(25)}")
                                }
                            }
                        } catch (_: Throwable) {}
                        result
                    }
                }
            }
        }
    }
    private fun hookPagerAdapter(c: Class<*>) {
        val xp = CfhState.xpRef ?: run { Logger.once("adp.noxp", "hookPagerAdapter skip: xpRef null"); return }
        val added = synchronized(CfhState.hookedAdpClasses) { CfhState.hookedAdpClasses.add(CfhUtil.hookKey(c)) }
        if (!added) { Logger.d("hookPagerAdapter dup: ${c.name}"); return }
        Logger.d("hookPagerAdapter: ${c.name}")
        // 璇婃柇锛歞ump 绫诲眰娆″叏閮ㄦ柟娉曪紝鎵炬暟鎹緵缁欐柟娉?
        var dcls: Class<*>? = c
        var dlvl = 0
        while (dcls != null && dcls != Any::class.java && dlvl < 5) {
            val dcn = dcls!!
            for (m in dcn.declaredMethods) {
                val pDesc = m.parameterTypes.joinToString(",") { it.simpleName }
                if (m.parameterTypes.size <= 3) {
                    Logger.d("  adpM[${dcn.simpleName}]: ${m.name}($pDesc) -> ${m.returnType.simpleName}")
                }
            }
            dcls = dcn.superclass; dlvl++
        }
        // 鍐冲畾鎬э細hook D(int)/p(ViewGroup,int) dump 杩斿洖缁撴瀯 + 鎵€鏈?List 鍐欏叆鏂规硶婧愬ご杩囨护
        var qcls: Class<*>? = c
        var qlvl = 0
        while (qcls != null && qcls != Any::class.java && qlvl < 5) {
            val qcn = qcls!!
            for (m in qcn.declaredMethods) {
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                val isDMethod = m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.name.length <= 2
                val isInst = m.parameterTypes.size == 2 && m.parameterTypes[0].name.contains("ViewGroup") &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType && !m.returnType.isPrimitive && m.returnType != Void.TYPE
                if (hasListParam || isDMethod || isInst) installAdpX(xp, qcn, m)
            }
            qcls = qcn.superclass; qlvl++
        }
        var cls: Class<*>? = c
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 4) {
            for (m in cls!!.declaredMethods) {
                // 鈽?installAdpF 娲惧彂宸茬Щ闄わ紙2026-09-21锛夛細娑堣垂绔弬鏁版浛鎹紝闈炴暟鎹簮鎷︽埅涓斾粠鏈敓鏁?
                // adapter 鐨?set/add/addAll(List) 鏂规硶锛氱洿鎾粠杩欏杩涗俊鎭祦锛屽湪鍙傛暟闃舵灏卞墧鎺?
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection") }
                if (hasListParam && m.declaringClass == cls) installAdpList(xp, c, m)
                if (m.parameterTypes.size <= 3 && m.declaringClass == cls) {
                    val p1 = m.parameterTypes.firstOrNull()
                    val isIntP = p1 == Int::class.javaPrimitiveType
                    val nonPrimRet = !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != java.lang.String::class.java
                    val retFrag = m.returnType.name.contains("Fragment")
                    val retList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    // 渚涚粰鏂规硶锛氬崟 int 鍙傝繑鍥炲璞★紙getItem/D锛夛紱(ViewGroup,int) 杩斿洖 View/Fragment锛坕nstantiateItem锛夛紱
                    // 鎴栬繑鍥?Fragment/List 鐨勪换鎰忕煭鍙傛柟娉?
                    val isSupply = (m.parameterTypes.size == 1 && isIntP && nonPrimRet) ||
                        (m.parameterTypes.size == 2 && m.parameterTypes[0].name.contains("ViewGroup") && m.parameterTypes[1] == Int::class.javaPrimitiveType && (retFrag || nonPrimRet)) ||
                        (retFrag && m.parameterTypes.size <= 2) ||
                        (retList && m.parameterTypes.size <= 2 && isIntP)
                    if (isSupply) installAdpGet(xp, c, m)
                    else if (m.parameterTypes.isEmpty() && nonPrimRet) installAdpProv(xp, c, m)
                    else if (m.parameterTypes.size >= 1 && m.parameterTypes.any { it.name.contains("QPhoto") }) installAdpQp(xp, c, m)
                }
            }
            cls = cls.superclass; lvl++
        }
    }
    private fun installAdpX(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.d("  hookAdpX[${c.simpleName}]: ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName}")
        Logger.safe("hookAdpX.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpX.${c.name}.${m.name}").intercept { chain ->
                // ★ 白名单替代黑名单（2026-09-26 用户定稿「只留白名单，黑名单清理掉」）
                        try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) {}
                // 鈽?姝讳唬鐮佺Щ闄わ紙2026-09-21锛夛細姝ゅ鍘熸湁銆岃剰椤?p() 浣嶇疆閲嶅畾鍚戙€嶁€斺€?
                // 鎺㈡祴 p(ViewGroup,int) 鐨勮剰椤碉紝鎶婂弬鏁版敼鎴愰偦杩戝共鍑€椤电殑浣嶇疆
                // 锛坈hain.args[1] = np + triggerRefresh锛夈€傜粡 libxposed API 鏍稿锛?
                // Chain.getArgs() 杩斿洖**鍙 List**锛孡ist.set() 蹇呮姏
                // UnsupportedOperationException 骞惰鏈潡 catch 鍚炴帀 鈥斺€?
                // 璇ュ姛鑳借嚜鍐欎笅璧蜂粠鏈敓鏁堬紙瀹炴祴 adpX p REDIRECT 鏃ュ織鎭掍负 0锛?
                // 瑙?2026-09-21 鎶撳寘锛夈€傛棦浠庢湭鐢熸晥銆佸張闇€鏂板鐘舵€佷笌鍒锋柊鍓綔鐢紝
                // 鏁呮暣浣撳垹闄よ€岄潪鏀归€狅紱鑻ユ棩鍚庤鎭㈠"鑴忛〉鎹㈤偦椤?锛屽繀椤荤敤
                // chain.proceed(newArgs) 鎼哄甫鏂板弬锛屽苟鍚屾璇勪及浣嶇疆鈫斿唴瀹归敊閰?
                // 瀵硅鍙ｆ瘮渚嬬殑褰卞搷锛堜細寮曞彂涓婁笅鍘嬬缉鐣稿彉锛夈€?
                val r = chain.proceed()

                try {
                    val pos = chain.args.lastOrNull() as? Int ?: -1
                    val isPD = m.name == "p" || m.name == "D"
                    val shouldDump = if (isPD) CfhState.adpXDump < 40 && CfhState.adpXDumped.add("pd_" + pos) else CfhState.adpXDumped.add(c.name + "." + m.name) && CfhState.adpXDump < 25
                    if (shouldDump) {
                        CfhState.adpXDump++
                        Logger.d("adpX ${m.name} #$pos ret=${r?.javaClass?.name ?: "null"}")
                        if (r != null && isPD) {
                            var rc: Class<*>? = r.javaClass
                            var rl = 0
                            while (rc != null && rc != Any::class.java && rl < 3) {
                                for (rf in rc!!.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(rf.modifiers)) continue
                                    try { rf.isAccessible = true; val rv = rf.get(r); Logger.d("  adpXfld ${rf.name}:${rf.type.simpleName}=${rv?.javaClass?.name ?: "null"}") } catch (_: Throwable) {}
                                }
                                rc = rc.superclass; rl++
                            }
                        }
                    }
                } catch (_: Throwable) {}

                r
            }
        }
    }

    // 鈽?installAdpF 鏁翠綋绉婚櫎锛?026-09-21锛夛細璇ュ嚱鏁?hook銆岃繑鍥?Fragment 鐨?(Any) 鏂规硶銆嶏紝
    // 鎶?pager 璇锋眰鐨勮剰 QPhoto 鍙傛暟鏇挎崲鎴?vm.T0()/U0() 閲岀殑骞插噣椤广€?
    // 鍒犻櫎鐞嗙敱锛堟灦鏋?+ 浜嬪疄鍙岄噸锛夛細
    //   1) 鏋舵瀯锛氭湰妯″潡璺嚎鏄?*鏁版嵁婧愭嫤鎴?*锛堝湪 VM/鍒楄〃/鐪熸簮灞傚垹鎺夎剰椤?鈫?鑴忓唴瀹?鍒蜂笉鍒?锛夈€?
    //      鍦?pager 鐨勫弬鏁颁笂浜嬪悗鎹㈠璞″睘**娑堣垂绔ˉ涓?*鈥斺€斾笉鏄偅鏉¤矾绾匡紝涔熸嫤涓嶄綇锛?
    //      Fragment 鍙傛暟鍙湪杩欎竴娆¤皟鐢ㄩ噷鐢熸晥锛屽璞′笌瑙嗗浘闅忓悗琚噸寤?閲嶈銆?
    //   2) 浜嬪疄锛氬叾鏍稿績鍔ㄤ綔 `chain.args[0] = qp` 鏄?libxposed 鍙 List 璇敤
    //      锛圕hain.getArgs() 杩斿洖鍙 List锛孡ist.set() 蹇呮姏锛岃 catch 鍚炴帀锛夆噿 鏇挎崲
    //      浠庢湭鍙戠敓锛屽嚱鏁板彧鍓╀笅涓€鏉?"adp F blocked" 鏃ュ織涓?vm.T0/U0 绌鸿浆鎺㈡祴銆?
    // 鑻ユ棩鍚庤鍦ㄨ鍥惧眰鎹㈡暟鎹紝蹇呴』鐢?chain.proceed(newArgs)锛屽苟鍏堣瘉鏄庡畠鑳?绮樹綇"銆?

    private fun installAdpList(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
// adapter 鐨?set/add/addAll(List) 鏂规硶锛氱洿鎾粠杩欏杩涗俊鎭祦锛屽湪鍙傛暟闃舵灏卞墧鎺?
        Logger.safe("hookAdpList.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpList.${c.name}.${m.name}").intercept { chain ->
                // ★ 白名单替代黑名单（2026-09-26 用户定稿「只留白名单，黑名单清理掉」）
                        try { CfhClean.filterWhitelist(chain.args) } catch (_: Throwable) {}
                chain.proceed()
            }
        }
    }

    private fun installAdpGet(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookAdpGet.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpGet.${c.name}.${m.name}").intercept { chain ->
                if (CfhState.adpRef == null) CfhState.adpRef = chain.thisObject
                val result = chain.proceed()
                try {
                    if (result != null && !CfhState.adpGetSwapIn) {
                        val qp = CfhProbe.findQpInObject(result)
                        if (qp != null) CfhCapture.captureFeedItem(qp)
                        // 鈽?浣嶇疆鈫旀潯鐩潈濞佹槧灏勶紙涓嬭浇鎹曡幏 2026-09锛夛細D(pos) 杩斿洖浠€涔堬紝
                        // 閫傞厤鍣ㄨ嚜宸辨渶娓呮鈥斺€旇褰?pos鈫扱Photo锛屼笅杞芥椂鐢?ViewPager 鐨?
                        // mCurrentItem 鏌ヨ〃锛堣凯浠?6 鐗堢殑鍙鎬ф帹鏂叏閮ㄦ窐姹帮級
                        try {
                            val dpos = (chain.args.getOrNull(0) as? Int) ?: -1
                            if (dpos >= 0) {
                                val store = qp ?: CfhCapture.scanFragmentPhoto(result)
                                if (store != null) synchronized(CfhState.posPhotoMap) {
                                    CfhState.posPhotoMap.remove(dpos)
                                    CfhState.posPhotoMap[dpos] = java.lang.ref.WeakReference(store)
                                    while (CfhState.posPhotoMap.size > 16) {
                                        val first = CfhState.posPhotoMap.keys.firstOrNull() ?: break
                                        CfhState.posPhotoMap.remove(first)
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        val pos = (chain.args.getOrNull(0) as? Int) ?: -1
                        var clsQp = qp
                        // 绌哄３瀹炰緥鍏滃簳锛氱敤鍚屼綅锟?vm 绐楀彛鐨勫瘜 qp 鍒嗙被锛堟樉绀烘簮=qm 鏈夊畬鏁存暟鎹級
                        if (clsQp == null || CfhUtil.readUserName(clsQp, Reflect.readAny(clsQp, "mEntity") ?: clsQp).isEmpty()) {
                            clsQp = CfhCapture.findWindowQp(pos) ?: clsQp
                        }
                        // 鈽?QPhoto 鎻愪笉鍒版椂鐢?holder 鐨?Fragment 绫诲瀷鍒ゅ畾锛坓3c.a 鐨?b 瀛楁鍗抽〉闈?Fragment锛夛細
                        // 鐩存挱 holder 鐨?Fragment 绫诲悕鍚?Live
                        val holderLive = CfhProbe.findFragInHolder(result)?.javaClass?.name?.let { fn -> fn.contains("Live") || fn.contains("Ad") } == true
                        // 鈽呪槄 鍐?BFS 鍏ㄥ浘鎵句换浣?Live/Ad 瀹炰綋锛堢洿鎾崱鍙兘娓叉煋鍦?NasaPhotoDetailFragment 閲岋紝
                        // Fragment 绫诲悕涓嶅惈 Live锛孮Photo 涔熸彁涓嶅埌锛屽彧鑳藉叏鍥炬壘瀹炰綋绫诲悕锛?
                        val holderDirtyEnt = if (!holderLive) CfhProbe.findDirtyEntityInHolder(result) else null
                        // 鈽呪槄鈽?鎹㈤〉鏈哄埗鏁翠綋鎷嗛櫎锛堢湡鏈轰笁娆″疄璇?01:18/22:34 闂€€锛夛細KMP groot
                        // 妗嗘灦鎸?fragment 鍒涘缓鏃剁殑浣嶇疆鐧昏 KmpSlideContext/渚濊禆瀛楁锛堝
                        // PhotoDetailLogger锛夛紝杩斿洖鐩搁偦浣?fragment 椤跺寘 = 妗嗘灦鐘舵€侀敊閰嶏紝
                        // 鏃犺寮哄急淇″彿閮戒細鍦?onCreatedView/onActivityCreated 绌烘寚閽堥棯閫€銆?
                        // 鑴忛〉鏀逛负銆屽厛娓叉煋銆佸悗鍙版绉掔骇娓呮礂鎽橀櫎銆嶏細骞稿瓨鑰呭叆姹?+
                        // fixAdapterSelfAlways + filterVmLists/laFind + fragSeq Vp 鎷︾粦瀹?
                        // 鍏滃簳鈥斺€旂ǔ瀹氭€т紭鍏堬紝浠ｄ环鏄剰鍗′笂灞忓悗闆剁偣鍑犵鍐呮秷澶?
                        if ((clsQp != null && CfhDecide.shouldFilterFeed(clsQp)) || holderLive || holderDirtyEnt != null) {
                            // ★★★ 上屏前最后一关（2026-09-25 主人报「上屏后才删，删晚了」）：
                            //   D(pos)/F() 是 pager 供给口——返回后宿主立即绑 Fragment 并上屏。
                            //   命中脏项（黑名单/判定/持有 Live/Ad 实体）时**当场**：
                            //   ① 同步触发一轮 VM 全量清洗（把数据源里的它删干净，包括未消费副本）；
                            //   ② scrub 脏项展示字段（caption/声明清空 → 订阅 View 刷新为空，
                            //      即使画面已启动，广告文案与 AI 标也不显示）。
                            //   原「defer-clean」升级为「立即执行」，拦截窗口前移到绑定前。
                            // ★ 异步化（2026-09-25 修正）：hook 回调在主线程分页路径上，
                            //   同步 filterVmLists 的 removeAt 会让列表位移、宿主旧下标错位。
                            //   投递主线程队列异步执行，本轮绑定不受影响。
                            try {
                                val scrubA = clsQp ?: qp
                                val scrubB = holderDirtyEnt
                                CfhState.handler.post {
                                    try {
                                        val vmNow = CfhState.vmRef
                                        if (vmNow != null) CfhWash.filterVmLists(vmNow)
                                        // [已移除 2026-09-26] scrubShownDirty：功能早已删除，调用点清理
                                        // [已移除 2026-09-26] scrubShownDirty：功能早已删除，调用点清理
                                    } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                            if (CfhState.adpGetLiveSkipDiag < 40) {
                                CfhState.adpGetLiveSkipDiag++
                                Logger.d { "adpGet dirty #$pos pre-bind clean qp=${clsQp != null && CfhDecide.shouldFilterFeed(clsQp)} live=$holderLive ent=${holderDirtyEnt != null}" }
                            }
                        }
                        // 骞插噣椤瑰叆姹狅細D 姣忓彇涓€涓綅缃紝鏅€氳棰戝氨鏄睜瀛愮殑椋熺伯
                        if (qp != null && !CfhDecide.shouldFilterFeed(qp)) {
                            try { CfhSwap.offerClean(qp) } catch (_: Throwable) {}
                        }
                        // 鈽呪槄 鍚姩绐椾綅缃帰閽堬紙2026-09 鐢ㄦ埛鎶ャ€岀浜屾潯 90鍚庨浂椋熴€嶇被婕忕綉锛夛細
                        // D(pos) 鏄?pager 鐨勬潈濞佷緵缁欏彛锛屾澶勬寜浣嶇疆鎵撳嵃銆岃繖涓€椤靛埌搴曟槸浠€涔?+
                        // 鍒ゆ病鍒よ剰 + 鍏抽敭鍒ゆ嵁瀛楁銆嶏紝鐢ㄤ簬瀹氫綅銆岀 N 鏉′负浣曟紡缃戙€嶃€?
                        // 鍐峰惎 20s 鍐呫€佸墠 12 娆′緵缁欐墦鍗帮紙鍙?diag 闂ㄦ帶锛夈€?
                        // 鈽?鎬ц兘淇锛堝闃?2026-09 路 M1锛夛細鍘熷厛涓?always锛堜笉鍙椾换浣曢棬鎺э級锛?
                        // 涓斿瓧绗︿覆鍐呭惈 8+ 娆″弽灏勮 + 涓€娆?shouldFilterFeed鈥斺€斿叏閮ㄥ湪
                        // Logger 鍐崇瓥涔嬪墠姹傚€笺€傛娈典綅浜?adapter D(pos) 渚涚粰鐑矾寰勩€?
                        // 鏀逛负 diag 闂ㄦ帶 + 鎯版€ф眰鍊硷細榛樿闆舵垚鏈紝鎺掗殰寮€璇婃柇鍗冲畬鏁村鐜般€?
                        try {
                            if (Logger.diag && CfhState.processStartAt > 0L && System.currentTimeMillis() - CfhState.processStartAt < 20_000L && CfhState.adpPosProbe < 12) {
                                CfhState.adpPosProbe++
                                Logger.d {
                                    val p0 = (chain.args.getOrNull(0) as? Int) ?: -1
                                    val q1 = qp ?: clsQp
                                    val e1 = q1?.let { Reflect.readAny(it, "mEntity") }
                                    val pm1 = e1?.let { Reflect.readAny(it, "mPhotoMeta") } ?: q1?.let { Reflect.readAny(it, "mPhotoMeta") }
                                    val dis1 = pm1?.let { try { Reflect.readAny(it, "mDisclaimergeMessageV2") } catch (_: Throwable) { null } }
                                    val disC1 = dis1?.let { try { Reflect.readAny(it, "content") } catch (_: Throwable) { null } }
                                    val lm1 = e1?.let { Reflect.readAny(it, "mLivePlaybackMeta") }
                                    "POSPROBE #$p0 cap=\"${CfhUtil.readCaption(q1 ?: result)?.take(26)}\" " +
                                        "qp=${q1 != null} dirty=${if (q1 != null) CfhDecide.shouldFilterFeed(q1) else false} " +
                                        "ent=${e1?.javaClass?.simpleName ?: "-"} dis=${dis1 != null} disC=${disC1?.toString()?.take(20) ?: "-"} " +
                                        "liveMeta=${lm1 != null} liveStart=${lm1?.let { CfhUtil.safeNextLong(it, "mLiveStartTime") } ?: 0} " +
                                        "entCls=${e1?.javaClass?.name?.substringAfterLast('.') ?: "-"}"
                                }
                            }
                        } catch (_: Throwable) {}
                        // ===== 鍘熻瘖鏂紙鑺傛祦锟?=====
                        if (CfhState.adpGetDiag < 10) {
                            CfhState.adpGetDiag++
                            qp?.let { q0 ->
                                Logger.d("adpGet ${m.name}(#${chain.args[0]}) ret=${result.javaClass.name} qp hit=${CfhDecide.shouldFilterFeed(q0)} cap=${CfhUtil.readCaption(q0)?.take(20)}")
                            }
                            if (!CfhState.adpSelfDumped) {
                                CfhState.adpSelfDumped = true
                                CfhDiag.dumpAdapterSelf(chain.thisObject)
                            }
                        }
                        // 鈽呪槄鈽?adapter 鑷寔鍒楄〃姣忔 D() 閮戒慨锛堝幓鎺変竴娆￠棬鎺э級锛歰 鍒楄〃鏄疄闄呮樉绀烘簮锛?
                        // rerank 姣忔鎹㈤〉閮戒細寰€ o 閲屽鏂扮殑鐩存挱椤癸紝蹇呴』鎸佺画娓呯悊銆?
                        try { CfhSwap.fixAdapterSelfAlways(chain.thisObject) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
                result
            }
        }
    }

    private fun installAdpProv(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookAdpProv.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpProv.${c.name}.${m.name}").intercept { chain ->
                val result = chain.proceed()
                try {
                    if (result != null && CfhState.adpProvDiag < 10) {
                        CfhState.adpProvDiag++
                        Logger.d("adpProv ${m.name}() ret=${result.javaClass.name}")
                        if (result.javaClass.name != "com.yxcorp.gifshow.entity.QPhoto") {
                            CfhDiag.dumpProvider(result)
                        }
                    }
                } catch (_: Throwable) {}
                result
            }
        }
    }

    private fun installAdpQp(xp: XposedInterface, c: Class<*>, m: java.lang.reflect.Method) {
        Logger.safe("hookAdpQp.${m.name}") {
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpQp.${c.name}.${m.name}").intercept { chain ->
                try {
                    val qp = chain.args.firstOrNull { it != null && CfhState.qpClassRef?.isAssignableFrom(it.javaClass) == true }
                    if (qp != null && CfhState.adpQpDiag < 15) {
                        CfhState.adpQpDiag++
                        Logger.d("adpQp ${m.name}(${qp.javaClass.simpleName}) ret=${m.returnType.simpleName} hit=${CfhDecide.shouldFilterFeed(qp)} cap=${CfhUtil.readCaption(qp)?.take(20)}")
                    }
                } catch (_: Throwable) {}
                chain.proceed()
            }
        }
    }

    private fun isDescendantOf(v: View, root: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === root) return true; x = x.parent as? View }
        return false
    }
}

