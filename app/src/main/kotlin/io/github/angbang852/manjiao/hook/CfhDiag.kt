package io.github.angbang852.manjiao.hook

import android.view.ViewGroup
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect

// ★ ContentFilterHook 深拆第二步：诊断职责（2026-09 S3）。
// 对象图/字段/实体 dump 与 diagFragment 排查。纯只读诊断，不改变过滤行为。
object CfhDiag {
    fun dumpAdapterSelf(adp: Any?) {
        if (adp == null) return
        Logger.safe("dumpAdpSelf") {
            val sb = StringBuilder()
            var c: Class<*>? = adp.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(adp)
                        val desc = if (v is List<*>) "List(size=${v.size})" else v?.javaClass?.simpleName ?: "null"
                        sb.append("[${c.simpleName}]${f.name}:${desc} ")
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            Logger.d("adpSelf ${adp.javaClass.name}: $sb")
            CfhState.adpRef = adp
            var c2: Class<*>? = adp.javaClass
            var lvl2 = 0
            while (c2 != null && c2 != Any::class.java && lvl2 < 4) {
                for (f in c2!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(adp)
                        if (v is MutableList<*> && v.size > 0) {
                            val qp = v[0]?.let { CfhProbe.findQpInObject(it) }
                            val hits = v.filter { it != null && CfhProbe.findQpInObject(it)?.let { q -> CfhDecide.shouldFilterFeed(q) } == true }.size
                            Logger.d("adpSelfList ${f.name} size=${v.size} elem=${v[0]?.javaClass?.name} qpFound=${qp != null} hits=$hits")
                            CfhSwap.fixAdapterSelfAlways(adp)
                            if (f.name == "M" && !CfhState.elemDumped) {
                                CfhState.elemDumped = true
                                val e0 = v[0]
                                if (e0 != null) {
                                    val esb = StringBuilder()
                                    var ec: Class<*>? = e0.javaClass
                                    var elvl = 0
                                    while (ec != null && ec != Any::class.java && elvl < 3) {
                                        for (ef in ec!!.declaredFields) {
                                            if (java.lang.reflect.Modifier.isStatic(ef.modifiers)) continue
                                            try {
                                                ef.isAccessible = true
                                                val ev = ef.get(e0)
                                                val edesc = if (ev is List<*>) "List(${ev.size})" else ev?.javaClass?.simpleName ?: "null"
                                                esb.append("[${ec.simpleName}]${ef.name}:${edesc} ")
                                            } catch (_: Throwable) {}
                                        }
                                        ec = ec.superclass; elvl++
                                    }
                                    Logger.d("adpElem ${e0.javaClass.name}: $esb")
                                    val af = try { e0.javaClass.getDeclaredField("a").apply { isAccessible = true } } catch (_: Throwable) { null }
                                    val av = try { af?.get(e0) } catch (_: Throwable) { null }
                                    if (av != null) {
                                        val asb = StringBuilder()
                                        var ac: Class<*>? = av.javaClass
                                        var alvl = 0
                                        while (ac != null && ac != Any::class.java && alvl < 3) {
                                            for (af2 in ac!!.declaredFields) {
                                                if (java.lang.reflect.Modifier.isStatic(af2.modifiers)) continue
                                                try {
                                                    af2.isAccessible = true
                                                    val av2 = af2.get(av)
                                                    val adesc = if (av2 is List<*>) "List(${av2.size})" else av2?.javaClass?.simpleName ?: "null"
                                                    asb.append("[${ac.simpleName}]${af2.name}:${adesc} ")
                                                } catch (_: Throwable) {}
                                            }
                                            ac = ac.superclass; alvl++
                                        }
                                        val aqp = CfhProbe.findQpInObject(av)
                                        Logger.d("adpElemA ${av.javaClass.name}: $asb qpIn=${aqp != null} qpHit=${aqp?.let { CfhDecide.shouldFilterFeed(it) }}")
                                    }
                                    var mm: Class<*>? = e0.javaClass
                                    var mlvl2 = 0
                                    while (mm != null && mm != Any::class.java && mlvl2 < 3) {
                                        for (mf in mm!!.declaredMethods) {
                                            if (java.lang.reflect.Modifier.isStatic(mf.modifiers)) continue
                                            if (mf.parameterTypes.size <= 2) {
                                                Logger.d("  elem m: ${mf.name}(${mf.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${mf.returnType.simpleName}")
                                            }
                                        }
                                        mm = mm.superclass; mlvl2++
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        }
    }

    fun dumpProvider(obj: Any) {
        Logger.safe("dumpProv") {
            val sb = StringBuilder()
            var c: Class<*>? = obj.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        val desc = if (v is List<*>) "List(size=${v.size})" else v?.javaClass?.simpleName ?: "null"
                        sb.append("${f.name}:${desc} ")
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            Logger.d("provDump ${obj.javaClass.name} fields: $sb")
            var c2: Class<*>? = obj.javaClass
            var lvl2 = 0
            while (c2 != null && c2 != Any::class.java && lvl2 < 3) {
                for (f in c2!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v is MutableList<*> && v.size > 0) {
                            val elem = v[0]
                            val qp = elem?.let { CfhProbe.findQpInObject(it) }
                            Logger.d("provList ${f.name} size=${v.size} elem=${elem?.javaClass?.name} qpFound=${qp != null} qpHit=${qp?.let { CfhDecide.shouldFilterFeed(it) }}")
                            val hits = v.filter { it != null && CfhProbe.findQpInObject(it)?.let { q -> CfhDecide.shouldFilterFeed(q) } == true }.size
                            Logger.d("provList ${f.name} hits=$hits/${v.size}")
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        }
    }

    fun dumpAdapterLists(adp: Any) {
        if (CfhState.adpListDumped) return
        CfhState.adpListDumped = true
        val qpClass = CfhState.qpClassRef
        fun scan(obj: Any, prefix: String, depth: Int, seen: MutableSet<Int>) {
            if (depth > 2) return
            if (!seen.add(System.identityHashCode(obj))) return
            var c: Class<*>? = obj.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v is List<*>) {
                            val first = v.firstOrNull()
                            val isQp = qpClass?.let { q -> first != null && q.isAssignableFrom(first.javaClass) } == true
                            Logger.always("adpList $prefix${c.simpleName}.${f.name}: size=${v.size} elem=${first?.javaClass?.name ?: "null"}${if (isQp) " <== QP" else ""}")
                        } else if (v != null && depth < 2 && !v.javaClass.name.startsWith("java.")) {
                            scan(v, "$prefix${c.simpleName}.${f.name}>", depth + 1, seen)
                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
        scan(adp, "", 0, mutableSetOf())
        CfhState.vmRef?.let { scan(it, "VM>", 0, mutableSetOf()) }
    }




    fun diagFragment(frag: Any) {
        Logger.safe("diagFrag") {
            val qpClass = CfhState.qpClassRef ?: return@safe
            val vm = CfhState.vmRef
            // ★ 只记录真正可见的页（下载捕获锚点，2026-09）：slide 播放器会预加载
            // 邻页，邻页同样走 onResume——用 localVisibleRect 判定，离屏页视口的
            // 可见矩形为空直接跳过
            val fv = (frag as? androidx.fragment.app.Fragment)?.view
            if (fv != null) {
                val vr = android.graphics.Rect()
                fv.getLocalVisibleRect(vr)
                if (vr.width() <= 0 || vr.height() <= 0) return@safe
            }
            // 字段级排查：Fragment 持有视频数据的字段（类型�?Photo/QPhoto 或值含文案�?
            if (!CfhState.fragFieldsDiag) {
                CfhState.fragFieldsDiag = true
                var mc: Class<*>? = frag.javaClass
                var mlvl = 0
                var printed = 0
                while (mc != null && mc != Any::class.java && mlvl < 5) {
                    for (f in mc!!.declaredFields) {
                        val ft = f.type.name
                        if (ft.contains("Photo", true) || ft.contains("QPhoto", true) || ft.contains("Feed", true)) {
                            try {
                                f.isAccessible = true
                                val v = f.get(frag)
                                if (v != null && printed < 8) {
                                    val vcap = try { CfhUtil.readCaption(v) } catch (_: Throwable) { null }
                                    Logger.d("fragF ${f.name} type=$ft cap=${vcap?.take(16) ?: "?"}")
                                    printed++
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    mc = mc.superclass; mlvl++
                }
            }
            if (!CfhState.fragMethodsDiag) {
                CfhState.fragMethodsDiag = true
                var mc: Class<*>? = frag.javaClass
                var mlvl = 0
                while (mc != null && mc != Any::class.java && mlvl < 5) {
                    for (m in mc!!.declaredMethods) {
                        if (m.parameterTypes.isEmpty() && m.returnType == Void.TYPE) {
                            Logger.d("frag void: ${m.name}")
                        }
                    }
                    mc = mc.superclass; mlvl++
                }
            }
            if (!CfhState.feedPagerFound) {
                try {
                    val act = CfhState.tracked
                    val decor = act?.window?.decorView as? ViewGroup
                    if (decor != null) {
                        CfhViewHook.findPager(decor)
                    }
                } catch (_: Throwable) {}
            }
            var c: Class<*>? = frag.javaClass
            var lvl = 0
            var visibleStored = false
            while (c != null && c != Any::class.java && lvl < 5) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(frag) ?: continue
                        if (qpClass.isAssignableFrom(v.javaClass)) {
                            val hit = CfhDecide.shouldFilterFeed(v)
                            val cap = CfhUtil.readCaption(v)
                            CfhState.fragDiagCount++
                            if (CfhState.fragDiagCount <= 5 || CfhState.fragDiagCount % 100 == 0) {
                                Logger.d("frag M #${CfhState.fragDiagCount}: hit=$hit cap=${cap?.take(25)}")
                            }
                            if (hit && vm != null) {
                                var clean: Any? = null
                                for (i in 0 until 10) {
                                    val qp = try { Reflect.callMethod(vm, "T0", i) } catch (_: Throwable) { null }
                                    if (qp != null && qpClass.isAssignableFrom(qp.javaClass) && !CfhDecide.shouldFilterFeed(qp)) {
                                        clean = qp; break
                                    }
                                }
                                if (clean != null) {
                                    f.set(frag, clean)
                                    try {
                                        CfhProbe.cachedMethod(vm.javaClass, "J1", qpClass, Boolean::class.javaPrimitiveType!!)?.invoke(vm, clean, true)
                                    } catch (_: Throwable) {}
                                    Logger.d("frag M replaced: ${cap?.take(20)} -> ${CfhUtil.readCaption(clean)?.take(20)}")
                                }
                            }
                            // ★ 记录「当前可见页」的 QPhoto（下载捕获数据源，2026-09）：
                            // 判定路径的 lastViewQp 会指向预取批次/快照里的屏外条目，
                            // 换条≠在屏——resume 后 500ms 绑定已完成，当前 Fragment 字段
                            // 里的 QPhoto 才是用户眼前这条（替换后的干净项优先）
                            if (!visibleStored) {
                                visibleStored = true
                                try {
                                    val ph = f.get(frag) ?: v
                                    CfhState.visiblePhotoRef = java.lang.ref.WeakReference(ph)
                                    // ★ 入候选环（实证可靠路径）：diagFragment 每次可见页
                                    // 扫描都能出 DL vis——这是唯一被证明稳定的来源
                                    CfhCapture.ringPush(ph, frag)
                                    Logger.always("DL vis: cap=${CfhUtil.readCaption(v)?.take(24)} user=${CfhUtil.readUserName(v, Reflect.readAny(v, "mEntity") ?: v).take(16)}")
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
    }

}
