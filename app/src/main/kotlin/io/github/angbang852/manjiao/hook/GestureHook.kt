package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.ui.MainMenuDialog
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

object GestureHook {

    // 双击窗口 500ms。2026-09-05 教训一：曾砍到 300ms 修"滑动误判"，直接砍掉了
    // 用户偏慢的双击间隔——双击功能整体失效。教训二：双击消费若放在 UP，
    // 快手在第二击 DOWN 即触发点赞(拦 UP 太晚)，且"有 DOWN 无 UP"会被快手
    // 判成长按弹出分享菜单。故双击必须在 DOWN 时消费(快手收不到第二击任何事件)。
    // 滑动误判由"干净 tap 才计数"治本(见 ACTION_UP)：滑动 UP 位移大不入计数。
    private const val TAP_TIMEOUT = 500L
    // ★ slop 回归系统标准：ViewConfiguration.scaledDoubleTapSlop（通常 50-100px）。
    // 原 200px 硬编码会把「快速连点相邻按钮」的第二次 DOWN 也判成双击吞掉，
    // 破坏点赞→评论等连击操作。初始值取旧值兜底，每次 DOWN 都会被系统标准覆盖
    @Volatile private var slopPx = 200f
    // 0=IDLE, 1=已干净单击, 2=已双击(未拦截,等待三击)
    @Volatile private var tapState = 0
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    // 本次手势起点（DOWN 记录，UP 校验位移：干净 tap 才入双击计数）
    private var downX = 0f
    private var downY = 0f
    // 双击命中后被消费手势的后续事件连带消费（快手绝不能收到 UP，否则长按误触）
    @Volatile private var consumeUp = false
    // ★ 消费链续接（审阅 2026-09）：双击被消费后，窗口内的下一次 DOWN 若放行，
    // 会与快手已看到的第 1 击构成双击窗口 → 误点赞。消费窗口内的 DOWN 一律静默
    // 吞掉（不开新功能、不重置链），直到超出 TAP_TIMEOUT 链自然断开
    @Volatile private var lastConsumeAt = 0L

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        Logger.d("GestureHook: hook() called")
        val actCls = Reflect.findClass("android.app.Activity", cl) ?: run {
            Logger.d("GestureHook: Activity class not found")
            return
        }
        val m = Reflect.findMethod(actCls, "dispatchTouchEvent", 1) ?: run {
            Logger.d("GestureHook: dispatchTouchEvent not found")
            return
        }
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("gs.dispatch").intercept { chain ->
                val ev = chain.args[0] as? MotionEvent
                val act = chain.thisObject as? Activity
                if (ev != null && act != null && isKsActivity(act)) {
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = ev.x; downY = ev.y
                            try { slopPx = android.view.ViewConfiguration.get(act).scaledDoubleTapSlop.toFloat() } catch (_: Throwable) {}
                            // ★ 双路径去重（抖鸡对齐）：同步链内活动级先处理，decor
                            // 兜底监听据此跳过同一事件（多击状态机重复处理会污染）
                            activitySawDownAt = SystemClock.uptimeMillis()
                            if (handleDown(act, ev)) { consumeUp = true; return@intercept true }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            // 位移中途超 slop 即判滑动，提前失效（双击计数作废）
                            if (Math.abs(ev.x - downX) >= slopPx || Math.abs(ev.y - downY) >= slopPx) tapState = 0
                            if (consumeUp) return@intercept true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (consumeUp) { consumeUp = false; return@intercept true }
                            // ★ 干净 tap 才入计数：DOWN→UP 位移超 slop = 滑动手势，
                            // 重置计数（快速连滑无论多密都不会误判双击）
                            val moved = Math.abs(ev.x - downX) >= slopPx || Math.abs(ev.y - downY) >= slopPx
                            if (moved) tapState = 0
                            else {
                                val gap = SystemClock.uptimeMillis() - lastTapTime
                                lastTapTime = SystemClock.uptimeMillis(); lastTapX = ev.x; lastTapY = ev.y
                                // 新 tap 链起点（超窗=旧链已断，重置为单击态）
                                if (tapState == 0 || gap >= TAP_TIMEOUT) tapState = 1
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (consumeUp) { consumeUp = false; return@intercept true }
                            tapState = 0
                        }
                    }
                }
                chain.proceed()
            }

        Logger.d("GestureHook: hooked Activity.dispatchTouchEvent")

        // ★ 抖鸡对齐：decorView 兜底触摸（视频浮窗层级触摸不经 Activity.dispatchTouchEvent
        // 时的第二保险），每次 Activity onResume 时按 decor 去重安装一次
        val mResume = Reflect.findMethod(actCls, "onResume", 0)
        if (mResume != null) {
            xp.hook(mResume).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("gs.decor").intercept { chain ->
                    chain.proceed()
                    try { installDecorFallback(chain.thisObject as Activity) } catch (_: Throwable) {}
                    null
                }
            Logger.d("GestureHook: hooked Activity.onResume (decor fallback)")
        }
    }

    // ★ decor 兜底（抖鸡 GestureController.installFallbackTouchListener 同款）
    private val decorArmed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<android.view.View, Boolean>())
    @Volatile private var activitySawDownAt = 0L

    fun installDecorFallback(act: Activity) {
        try {
            val decor = act.window?.decorView ?: return
            if (!decorArmed.add(decor)) return
            decor.setOnTouchListener { _, ev -> handleDecorTouch(act, ev) }
            Logger.d("gs: decor fallback armed on ${act.javaClass.simpleName}")
        } catch (_: Throwable) {}
    }

    private fun handleDecorTouch(act: Activity, ev: MotionEvent): Boolean {
        if (ev.action != MotionEvent.ACTION_DOWN) return false
        val now = SystemClock.uptimeMillis()
        // 活动级 dispatch 刚处理过的事件（同步链内）不再重复处理
        if (now - activitySawDownAt < 150) return false
        try { return handleDown(act, ev) } catch (_: Throwable) { return false }
    }

    // 快手全系 Activity 均响应双击（用户决定：别的地方能弹就弹）
    private fun isKsActivity(act: Activity): Boolean {
        val cn = act.javaClass.name
        return cn.contains("gifshow") || cn.contains("kuaishou") || cn.contains("yxcorp")
    }

    /** 在 DOWN 时做双击/三击检测。@return true=消费该 DOWN 及后续事件(快手收不到), false=放行 */
    private fun handleDown(act: Activity, ev: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val dx = Math.abs(ev.x - lastTapX)
        val dy = Math.abs(ev.y - lastTapY)
        val inWindow = now - lastTapTime < TAP_TIMEOUT && dx < slopPx && dy < slopPx

        if (!inWindow) return false

        // ★ 消费链续接：上一次消费仍在窗口内且 tapState 已清零——此 DOWN 放行会与
        // 快手已见的上一击构成双击误点赞，静默吞掉并延长消费窗口
        if (tapState == 0 && now - lastConsumeAt < TAP_TIMEOUT) {
            lastConsumeAt = now
            return true
        }

        // ★ 区域门控（用户保留）：菜单双击限屏幕中央 40%；禁赞限视频区；开评论
        // 除顶栏外任意位置——快手的评论按钮在底部操作栏（y≈0.90~0.95h），
        // 上限必须放到 0.98h，否则双击评论按钮被挡在门外
        val decor = try { act.window?.decorView } catch (_: Throwable) { null }
        val dw = decor?.width ?: 0
        val dh = decor?.height ?: 0
        val inMenuArea = dw > 0 && Math.abs(ev.x - dw / 2f) < dw * 0.2f && Math.abs(ev.y - dh / 2f) < dh * 0.2f
        val inVideoArea = dw > 0 && ev.x > 0 && ev.x < dw * 0.86f && ev.y > dh * 0.14f && ev.y < dh * 0.84f
        val inCommentArea = dw > 0 && ev.y > dh * 0.10f && ev.y < dh * 0.98f

        when (tapState) {
            1 -> {
                tapState = 2
                val noDblLike = Prefs.bool(Prefs.K_GS_NO_DBL_LIKE, false) && inVideoArea
                val openComment2 = Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false) &&
                    Prefs.int(Prefs.K_GS_OPEN_COMMENT_TAPS, 2) == 2 && inCommentArea
                val openMenu2 = Prefs.bool(Prefs.K_GS_OPEN_MENU, false) &&
                    Prefs.int(Prefs.K_GS_OPEN_MENU_TAPS, 2) == 2 && inMenuArea
                Logger.d("gs: double-tap detected noDblLike=$noDblLike oc=$openComment2 om=$openMenu2")
                if (noDblLike || openComment2 || openMenu2) {
                    // 消费第二击 DOWN（含后续 MOVE/UP）：快手双击点赞在 DOWN 触发，
                    // 且收不到 UP 会被判长按——必须整段吞掉
                    if (openComment2) openCommentPanel(act)
                    else if (openMenu2) MainMenuDialog.show(act)
                    // ★ 时间/坐标同步为本次事件（原实现不更新：第三击窗口从第二击 UP
                    // 起算漂移；后续 DOWN 与快手已见第 1 击链成双击误点赞）
                    lastTapTime = now; lastTapX = ev.x; lastTapY = ev.y
                    lastConsumeAt = now
                    // ★ 三击功能开启时保持 tapState=2 继续链（原实现清零导致
                    // noDblLike+三击配置下三击永不触发）
                    val tripleArmed = (Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false) &&
                        Prefs.int(Prefs.K_GS_OPEN_COMMENT_TAPS, 2) == 3) ||
                        (Prefs.bool(Prefs.K_GS_OPEN_MENU, false) &&
                            Prefs.int(Prefs.K_GS_OPEN_MENU_TAPS, 2) == 3)
                    if (!tripleArmed) tapState = 0
                    return true
                }
                return false
            }
            2 -> {
                tapState = 0
                val openComment3 = Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false) &&
                    Prefs.int(Prefs.K_GS_OPEN_COMMENT_TAPS, 2) == 3 && inCommentArea
                val openMenu3 = Prefs.bool(Prefs.K_GS_OPEN_MENU, false) &&
                    Prefs.int(Prefs.K_GS_OPEN_MENU_TAPS, 2) == 3 && inMenuArea
                if (openComment3 || openMenu3) {
                    Logger.d("gs: triple-tap oc=$openComment3 om=$openMenu3")
                    lastTapTime = now; lastTapX = ev.x; lastTapY = ev.y
                    lastConsumeAt = now
                    if (openComment3) openCommentPanel(act)
                    else if (openMenu3) MainMenuDialog.show(act)
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    private fun openCommentPanel(act: Activity) {
        try {
            val decor = act.window.decorView
            findAndClickComment(decor)
        } catch (t: Throwable) { Logger.d("gs openComment fail: ${t.message}") }
    }

    // ★ 抖鸡 GestureController.findCommentClickTarget 移植（2026-09）：评分制全树
    // 查找——类名/资源名/文案关键词命中 + 可点击祖先 + 右侧控件区域；命中后
    // performClick 与真实 DOWN→UP 双保险（原实现只匹配 id 名/description，快手
    // 视频页按钮 id 混淆/组件化后匹配不到 → 双击开评论失效）
    private fun findAndClickComment(root: View): Boolean {
        val holder = arrayOfNulls<View>(1)
        val best = intArrayOf(-1)
        val w = root.width
        val h = root.height
        collectCommentTarget(root, root, w, h, holder, best)
        val target = holder[0]
        if (target == null) {
            Logger.always("gs: comment target not found")
            return false
        }
        Logger.always("gs: comment target cls=${target.javaClass.simpleName} clickable=${target.isClickable}")
        try { target.performClick(); Logger.always("gs: comment performClick done") } catch (_: Throwable) {}
        // 真实 touch 兜底（控件自身中心坐标）
        val t0 = SystemClock.uptimeMillis()
        val x = target.width / 2f
        val y = target.height / 2f
        try {
            var ev = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0)
            target.dispatchTouchEvent(ev); ev.recycle()
            ev = MotionEvent.obtain(t0, t0 + 60L, MotionEvent.ACTION_UP, x, y, 0)
            target.dispatchTouchEvent(ev); ev.recycle()
            Logger.always("gs: comment touched")
        } catch (t: Throwable) { Logger.always("gs: comment touch fail: ${t.message}") }
        return true
    }

    private fun collectCommentTarget(v: View, root: View, w: Int, h: Int, holder: Array<View?>, best: IntArray) {
        if (v == null) return
        val score = commentScore(v, w, h)
        if (score > best[0]) {
            val anc = findClickableAncestor(v, root, w, h)
            if (anc != null) { holder[0] = anc; best[0] = score }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectCommentTarget(v.getChildAt(i), root, w, h, holder, best)
        }
    }

    private fun commentScore(v: View, w: Int, h: Int): Int {
        val rect = android.graphics.Rect()
        if (!v.getGlobalVisibleRect(rect) || rect.width() <= 0 || rect.height() <= 0) return -1
        if (!isRightControlBounds(rect, w, h)) return -1
        val cn = v.javaClass.name.lowercase()
        val idName = try {
            val rid = v.id
            if (rid != View.NO_ID) v.resources.getResourceEntryName(rid).lowercase() else ""
        } catch (_: Throwable) { "" }
        val cd = v.contentDescription?.toString() ?: ""
        val tvText = if (v is TextView) v.text?.toString() ?: "" else ""
        val txt = (cd + " " + tvText).lowercase()
        val kwClass = arrayOf("comment", "reply")
        val kwRes = arrayOf("comment", "feedcommentimageview", "commentbuttonaction")
        val kwText = arrayOf("comment", "reply", "评论", "写评论", "查看评论")
        if (!kwClass.any { cn.contains(it) } && !kwRes.any { idName.contains(it) } && !kwText.any { txt.contains(it) }) return -1
        var score = 100
        if (v.isClickable) score += 100
        if (kwClass.any { cn.contains(it) }) score += 6
        if (kwText.any { txt.contains(it) }) score += 4
        return score
    }

    private fun findClickableAncestor(v: View, root: View, w: Int, h: Int): View? {
        var cur: View? = v
        while (cur != null && cur !== root) {
            if (cur.isEnabled && cur.isClickable) {
                val r = android.graphics.Rect()
                if (cur.getGlobalVisibleRect(r) && isRightControlBounds(r, w, h)) return cur
            }
            val p = cur.parent
            cur = if (p is View) p else null
        }
        return null
    }

    private fun isRightControlBounds(r: android.graphics.Rect, w: Int, h: Int): Boolean {
        val cx = r.left + r.width() / 2
        val cy = r.top + r.height() / 2
        if (cx < w * 0.58f) return false
        if (cy < h * 0.10f || cy > h * 0.98f) return false
        if (r.width() > w * 0.45f || r.height() > h * 0.35f) return false
        return true
    }
}
