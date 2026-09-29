package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.api.XposedInterface

/**
 * ★★★ inject 崩溃护栏（2026-09-27）。
 *
 * ## 崩溃链（实测）
 *
 * ```
 * QPHOTO-BLOCK 拦掉脏 QPhoto（deserialize 返回 null）
 *     ↓
 * 该 JSON 对象的兄弟字段（FEED_ITEM_VIEW_PARAM 等）解析中断/缺失
 *     ↓
 * Presenter 子类 doInject() 里 inject("FEED_ITEM_VIEW_PARAM")  ← ★ 非_optional
 *     ↓ 抛 IllegalArgumentException: 未提供数据：FEED_ITEM_VIEW_PARAM
 * injectManual → bindInternal → bind   ⇒ FATAL
 * ```
 *
 * ## 本护栏的做法
 *
 * hook `PresenterV2.inject(String)` / `inject(Class)` /
 * `injectManual(...)`：用 try-catch **包住原方法**——
 *   · 抛「未提供数据」类异常 ⇒ 返回 null（该 Presenter 拿到空数据，
 *     渲染空卡片或跳过，**不崩**）
 *   · 正常 ⇒ 原样返回
 *
 * ## 为什么安全
 *
 * · `inject` 失败原本就该是“数据缺了”的降级场景，
 *   快手自己的 `injectOptional` 就是这个语义 —— 我们只是把
 *   **必需版**的失败从“崩进程”降级为“返回 null”
 * · 只影响被模块拦截了数据的场景（正常使用不触发）
 * · 不修改任何快手数据
 */
object InjectCrashGuard {

    fun install(xp: XposedInterface, cl: ClassLoader) {
        // ★★★ 详情页销毁崩溃兜底（2026-09-28 实测崩溃栈）：
        //   `photoDecisionMaker` lateinit 未初始化（g5c.b.m ← GrootSlidePlayDetailBaseContainerFragment
        //   .onDestroyView ← PhotoDetailActivity.onDestroy）。根因：DetailFeedHook 返回 null ⇒
        //   NasaPhotoDetailFragment.M 不被赋值 ⇒ 详情页组件未初始化 ⇒ 退出详情页销毁时崩。
        //   hook 容器 Fragment 的 onDestroyView，吞 UninitializedPropertyAccessException。
        try {
            val gsd = Class.forName(
                "com.kwai.component.photo.detail.slide.container.groot.GrootSlidePlayDetailBaseContainerFragment",
                false, cl
            )
            for (m in gsd.declaredMethods) {
                if (m.name != "onDestroyView") continue
                try {
                    m.isAccessible = true
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("iguard.groot.onDestroyView").intercept { chain ->
                            try {
                                return@intercept chain.proceed()
                            } catch (t: Throwable) {
                                if (t is kotlin.UninitializedPropertyAccessException ||
                                    (t.message ?: "").contains("has not been initialized")
                                ) {
                                    if (CfhState.injectGuardLog < 40) {
                                        CfhState.injectGuardLog++
                                        Logger.evidence(
                                            "IGUARD",
                                            "★详情页销毁 lateinit 未初始化已吞: " +
                                                "${t.javaClass.simpleName} ${t.message?.take(40) ?: ""}"
                                        )
                                    }
                                    return@intercept null
                                }
                                throw t
                            }
                        }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // ★★★ 第三个 Presenter 崩溃点（2026-09-28 补齐，交接 §2.3 未验证项）：
        //   `a6i.g.doInject` 对 null QPhoto 调 `inject(...)` ⇒
        //   `inject(...) must not be null`（Kotlin 判空断言）⇒ FATAL。
        //   IGUARD 的 PV2.inject 兜底返回 null 后，`a6i.g.doInject` 内部
        //   还会用「!!」断言 —— hook 它本身，吞断言 NPE。
        try {
            val ag = Class.forName("a6i.g", false, cl)
            for (m in ag.declaredMethods) {
                if (m.name != "doInject") continue
                try {
                    m.isAccessible = true
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("iguard.a6ig.doInject").intercept { chain ->
                            try {
                                return@intercept chain.proceed()
                            } catch (t: Throwable) {
                                // Kotlin `!!` 断言抛 NPE/ISNE（"must not be null"）—— 数据缺失场景
                                if (t is NullPointerException ||
                                    t is IllegalStateException ||
                                    (t.message ?: "").contains("must not be null")
                                ) {
                                    if (CfhState.injectGuardLog < 40) {
                                        CfhState.injectGuardLog++
                                        Logger.evidence(
                                            "IGUARD",
                                            "★a6i.g.doInject 判空断言已吞: " +
                                                "${t.javaClass.simpleName} ${t.message?.take(30) ?: ""}"
                                        )
                                    }
                                    return@intercept null
                                }
                                throw t
                            }
                        }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // ★★★ 直接命中崩溃点（2026-09-27 实测崩溃栈）：
        //   `ml8.j.onBind` 对 null QPhoto 调 getEntity() ⇒ NPE ⇒ FATAL。
        //   hook 它本身，吞「数据缺失」NPE —— 该卡片空渲染，不崩。
        try {
            val mj = Class.forName("ml8.j", false, cl)
            for (m in mj.declaredMethods) {
                if (m.name != "onBind") continue
                try {
                    m.isAccessible = true
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("iguard.ml8j.onBind").intercept { chain ->
                            try {
                                return@intercept chain.proceed()
                            } catch (t: NullPointerException) {
                                if (CfhState.injectGuardLog < 40) {
                                    CfhState.injectGuardLog++
                                    Logger.evidence("IGUARD", "★ml8.j.onBind NPE 已吞（脏数据缺失）")
                                }
                                return@intercept null
                            }
                        }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // ② ★ Presenter 绑定保护（2026-09-27）：inject 降级返回 null 后，
        //    子 Presenter 的 onBind 可能对 null 数据调方法 ⇒ NPE ⇒ 崩。
        //    hook 所有 Presenter 的 onBind/onCreate，吞「数据缺失类」NPE。
        try {
            val pv2 = Class.forName("com.smile.gifmaker.mvps.presenter.PresenterV2", false, cl)
            for (m in pv2.declaredMethods) {
                if (m.name != "onBind" && m.name != "onCreate") continue
                if (m.parameterCount != 0) continue
                try {
                    m.isAccessible = true
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("iguard.pv2.${m.name}").intercept { chain ->
                            try {
                                return@intercept chain.proceed()
                            } catch (t: Throwable) {
                                // 数据缺失类 NPE：吞掉（该卡片空渲染，不崩）
                                if (t is NullPointerException ||
                                    (t.message ?: "").contains("FEED_ITEM_VIEW_PARAM")
                                ) {
                                    if (CfhState.injectGuardLog < 40) {
                                        CfhState.injectGuardLog++
                                        Logger.evidence(
                                            "IGUARD",
                                            "★Presenter ${m.name} 数据缺失异常已吞: " +
                                                "${t.javaClass.simpleName}"
                                        )
                                    }
                                    return@intercept null
                                }
                                throw t
                            }
                        }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // ① inject 缺数据降级
        val cn = "com.smile.gifmaker.mvps.presenter.PresenterV2"
        val cls = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null }
        if (cls == null) {
            Logger.evidence("IGUARD", "★类不存在: $cn")
            return
        }
        var n = 0
        for (m in cls.declaredMethods) {
            // 只护必需版 inject（optional 版本本来就不抛）
            if (m.name != "inject" && m.name != "injectManual") continue
            try {
                m.isAccessible = true
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("iguard.${m.name}.${m.parameterTypes.size}").intercept { chain ->
                        try {
                            return@intercept chain.proceed()
                        } catch (t: Throwable) {
                            // ★ 只接「数据未提供」类异常；其他异常继续冒泡
                            val msg = t.message ?: ""
                            if (t is IllegalArgumentException ||
                                msg.contains("未提供数据") ||
                                msg.contains("not provided", true)
                            ) {
                                if (CfhState.injectGuardLog < 40) {
                                    CfhState.injectGuardLog++
                                    Logger.evidence(
                                        "IGUARD",
                                        "★inject 缺数据已拦截(返回null) ${t.message?.take(50) ?: ""}"
                                    )
                                }
                                // 返回类型是基本类型时给默认值，否则 null
                                val rt = m.returnType
                                return@intercept when (rt) {
                                    java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE
                                    java.lang.Integer.TYPE -> 0
                                    java.lang.Long.TYPE -> 0L
                                    else -> null
                                }
                            }
                            throw t
                        }
                    }
                n++
            } catch (_: Throwable) {}
        }
        Logger.evidence("IGUARD", "inject 崩溃护栏已安装: 挂载 $n 个方法")
        CfhState.noteHookStatus("inject 崩溃护栏", n > 0, "PresenterV2.inject 缺数据降级")
    }
}
