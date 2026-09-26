package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.concurrent.Executors

/**
 * Phase B controller for the dock-to-edge-bubble overlays.
 *
 * Singleton owned by [FloatingPanelService]: after an app is launched from
 * the panel it tracks the resulting freeform task, draws a header overlay on
 * top of it (expand / drag / minimize-dock / close), and — when the user
 * taps dock or drags the window into the edge zone — shrinks the task to the
 * bubble target and shows the always-on-top bubble overlay (tap = restore,
 * long-press = Close/Fullscreen menu, drag = vertical move + edge snap).
 *
 * Threading: all session/view/WindowManager work runs on the main thread.
 * Blocking Shizuku shell calls run on a single background executor and post
 * their results back through [mainHandler].
 */
object DockManager {

    private const val TAG = "DockManager"
    private const val FIND_RETRY_MS = 400L
    private const val FIND_MAX_TRIES = 25       // ~10 s to find the new task
    private const val HEADER_POLL_MS = 1000L
    private const val MISS_BEFORE_GONE = 3      // consecutive null dumps → task closed
    private const val EDGE_ZONE_DP = 32         // drag into this zone triggers dock
    private const val HEADER_HEIGHT_DP = 36
    private const val MIN_HEADER_W_DP = 180

    private class Session(
        val taskId: Int,
        val pkg: String,
        val label: String,
        val icon: Drawable
    ) {
        var originalBounds = Rect()
        var header: HeaderOverlayView? = null
        var headerParams: WindowManager.LayoutParams? = null
        var bubble: BubbleOverlayView? = null
        var bubbleParams: WindowManager.LayoutParams? = null
        var bubbleSide = Gravity.RIGHT
        var docked = false
        var docking = false
        var dragging = false
        var dragBase: Rect? = null
        var dragAccX = 0f
        var dragAccY = 0f
        var misses = 0
    }

    /** Lerp holder for the bubble snap SpringAnimation (no per-frame allocs). */
    private class AnimBox(var x0: Float, var y0: Float, var x1: Float, var y1: Float) {
        var f: Float = 0f
    }

    private val ANIM_BOX_PROP = object : FloatPropertyCompat<AnimBox>("f") {
        override fun getValue(box: AnimBox): Float = box.f
        override fun setValue(box: AnimBox, value: Float) { box.f = value }
    }

    private val sessions = LinkedHashMap<Int, Session>()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bubbleAnims = HashMap<Int, SpringAnimation>()

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    @Volatile private var destroyed = false

    // ── lifecycle ─────────────────────────────────────────────────────────

    /** Called from [FloatingPanelService.onCreate]. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        destroyed = false
        Log.d(TAG, "attached")
    }

    /** Called from [FloatingPanelService.onDestroy]: drop every overlay. */
    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        for (anim in bubbleAnims.values) {
            try { anim.cancel() } catch (e: Exception) {}
        }
        bubbleAnims.clear()
        for (s in sessions.values) {
            hideHeader(s)
            hideBubble(s)
        }
        sessions.clear()
        Log.d(TAG, "destroyed — all overlays removed")
    }

    // ── launch hook ───────────────────────────────────────────────────────

    /**
     * Called by [PanelAppsAdapter] right after a freeform launch.  The task
     * id is unknown at that point, so it is looked up in the background
     * (dumpsys every 400 ms for up to ~10 s) and a tracked session — with
     * its header overlay — starts once the task appears.
     */
    fun onFreeformLaunched(context: Context, targetPackage: String) {
        if (appContext == null) attach(context)
        val c = appContext ?: return
        if (!Settings.canDrawOverlays(c)) {
            Log.w(TAG, "overlay permission missing — header overlay skipped")
            return
        }
        attemptFind(targetPackage, 0)
    }

    private fun attemptFind(targetPackage: String, attempt: Int) {
        if (destroyed) return
        val c = appContext ?: return
        executor.execute {
            val task: DockTestHelper.DockTask? = try {
                DockTestHelper.findTaskByPackage(c, targetPackage)
            } catch (e: Exception) {
                Log.e(TAG, "findTaskByPackage failed: ${e.message}")
                null
            }
            mainHandler.post {
                if (destroyed) return@post
                if (task != null) {
                    startSession(task, targetPackage)
                } else if (attempt < FIND_MAX_TRIES) {
                    mainHandler.postDelayed(
                        { attemptFind(targetPackage, attempt + 1) }, FIND_RETRY_MS
                    )
                } else {
                    Log.d(TAG, "no freeform task found for $targetPackage")
                }
            }
        }
    }

    private fun startSession(task: DockTestHelper.DockTask, targetPackage: String) {
        if (destroyed) return
        if (sessions.containsKey(task.taskId)) return
        val c = appContext ?: return
        val pm = c.packageManager
        val icon = try { pm.getApplicationIcon(targetPackage) }
                   catch (e: Exception) { pm.defaultActivityIcon }
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(targetPackage, 0)).toString()
        } catch (e: Exception) { targetPackage }

        val session = Session(task.taskId, targetPackage, label, icon)
        if (!task.bounds.isEmpty) session.originalBounds = Rect(task.bounds)
        sessions[task.taskId] = session
        Log.d(TAG, "session started: task=${task.taskId} pkg=$targetPackage bounds=${task.bounds}")

        if (!session.originalBounds.isEmpty) showHeader(session)
        ensurePolling()
    }

    // ── follow loop: header position + task-alive detection ───────────────

    private fun ensurePolling() {
        mainHandler.removeCallbacks(pollRunnable)
        if (!destroyed && sessions.isNotEmpty()) {
            mainHandler.postDelayed(pollRunnable, HEADER_POLL_MS)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (destroyed || sessions.isEmpty()) return
            for (s in sessions.values.toList()) {
                executor.execute {
                    val bounds = try {
                        DockTestHelper.queryTaskBounds(s.taskId)
                    } catch (e: Exception) {
                        null
                    }
                    mainHandler.post { handleQuery(s, bounds) }
                }
            }
            mainHandler.postDelayed(this, HEADER_POLL_MS)
        }
    }

    private fun handleQuery(session: Session, bounds: Rect?) {
        if (destroyed) return
        if (sessions[session.taskId] !== session) return

        if (bounds == null) {
            session.misses++
            if (session.misses >= MISS_BEFORE_GONE) {
                removeSession(session, "task no longer exists")
            }
            return
        }
        session.misses = 0
        if (bounds.isEmpty) return
        if (session.docked) return          // while docked, saved bounds must stay untouched

        session.originalBounds = Rect(bounds)
        if (session.dragging) return        // user is mid-drag; don't fight the header
        if (session.header == null) showHeader(session) else positionHeader(session, bounds)
    }

    // ── header overlay ────────────────────────────────────────────────────

    private fun showHeader(session: Session) {
        val c = appContext ?: return
        val wm = windowManager ?: return
        if (session.header != null) return
        if (!Settings.canDrawOverlays(c)) return
        if (session.originalBounds.isEmpty) return   // poll will retry once bounds are known

        val view = HeaderOverlayView(c).apply {
            setHeaderInfo(session.icon, session.label)
            listener = headerListener(session)
        }
        val lp = newOverlayParams(c, c.dpToPx(HEADER_HEIGHT_DP), c.dpToPx(MIN_HEADER_W_DP))
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView header failed: ${e.message}")
            return
        }
        session.header = view
        session.headerParams = lp
        positionHeader(session, session.originalBounds)
        Log.d(TAG, "header shown for task=${session.taskId}")
    }

    private fun positionHeader(session: Session, bounds: Rect) {
        val lp = session.headerParams ?: return
        val view = session.header ?: return
        val wm = windowManager ?: return
        val c = appContext ?: return
        if (bounds.isEmpty) return

        val dm = c.resources.displayMetrics
        val headerH = c.dpToPx(HEADER_HEIGHT_DP)
        lp.width = bounds.width().coerceIn(c.dpToPx(MIN_HEADER_W_DP), dm.widthPixels)
        lp.height = headerH
        lp.x = bounds.left.coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
        lp.y = if (bounds.top >= headerH + c.dpToPx(6)) {
            bounds.top - headerH - c.dpToPx(6)
        } else {
            (bounds.top + c.dpToPx(6)).coerceAtMost((dm.heightPixels - headerH).coerceAtLeast(0))
        }
        try { wm.updateViewLayout(view, lp) } catch (e: Exception) {}
    }

    private fun hideHeader(session: Session) {
        val view = session.header ?: return
        session.header = null
        session.headerParams = null
        val wm = windowManager ?: return
        try {
            if (view.isAttachedToWindow) wm.removeViewImmediate(view) else wm.removeView(view)
        } catch (e: Exception) {}
    }

    private fun headerListener(session: Session) = object : HeaderOverlayView.Listener {
        override fun onExpand() = expandSession(session)

        override fun onDock() = dockSession(session)

        override fun onClose() = closeSession(session)

        override fun onHeaderTouch() {
            // Idle-timer hooks go here later.
        }

        override fun onHeaderDrag(dx: Float, dy: Float) {
            if (!session.dragging) {
                session.dragging = true
                session.dragBase = Rect(session.originalBounds)
                session.dragAccX = 0f
                session.dragAccY = 0f
            }
            session.dragAccX += dx
            session.dragAccY += dy

            val lp = session.headerParams ?: return
            val view = session.header ?: return
            val wm = windowManager ?: return
            val base = session.dragBase ?: return
            if (base.isEmpty) return
            val metrics = dm() ?: return
            lp.x = (base.left + session.dragAccX).toInt()
                .coerceIn(0, (metrics.widthPixels - lp.width).coerceAtLeast(0))
            lp.y = (base.top + session.dragAccY).toInt()
                .coerceIn(0, (metrics.heightPixels - lp.height).coerceAtLeast(0))
            try { wm.updateViewLayout(view, lp) } catch (e: Exception) {}
        }

        override fun onHeaderDragRelease() {
            session.dragging = false
            val base = session.dragBase ?: return
            session.dragBase = null
            if (base.isEmpty) return
            val metrics = dm() ?: return
            val tx = (base.left + session.dragAccX).toInt()
                .coerceIn(0, (metrics.widthPixels - base.width()).coerceAtLeast(0))
            val ty = (base.top + session.dragAccY).toInt()
                .coerceIn(0, (metrics.heightPixels - base.height()).coerceAtLeast(0))
            val target = Rect(tx, ty, tx + base.width(), ty + base.height())

            executor.execute {
                val log = StringBuilder()
                val summary = DockTestHelper.moveTask(session.taskId, Rect(target), log)
                dumpLog(log)
                mainHandler.post {
                    if (destroyed || sessions[session.taskId] !== session) return@post
                    if (isFailure(summary)) {
                        toast(summary)
                        return@post
                    }
                    session.originalBounds = Rect(target)
                    if (session.header != null) positionHeader(session, target)
                    if (overlapsEdge(target)) dockSession(session)
                }
            }
        }
    }

    // ── dock / restore / expand / close ───────────────────────────────────

    private fun dockSession(session: Session) {
        if (session.docking || session.docked) return
        session.docking = true
        val c = appContext ?: run { session.docking = false; return }

        Log.d(TAG, "dock START: task=${session.taskId} pkg=${session.pkg} label=${session.label}")
        toast("Docking ${session.label}…")

        executor.execute {
            // Exact proven Dock Test path: shrink() = find task by package /
            // highest taskId → cmd activity task resize → am task focus.
            val log = StringBuilder()
            val summary = DockTestHelper.shrink(c, log, session.pkg)
            dumpLog(log)
            mainHandler.post {
                session.docking = false
                if (destroyed || sessions[session.taskId] !== session) return@post
                if (isFailure(summary)) {
                    Log.e(TAG, "dock FAILED: task=${session.taskId} reason=$summary")
                    toast("Dock failed: $summary")
                } else {
                    session.docked = true
                    val bubble = DockTestHelper.computeBubbleBounds(c)
                    Log.d(TAG, "dock OK: task=${session.taskId} summary=$summary bubble=$bubble")
                    hideHeader(session)
                    if (showBubble(session, bubble)) {
                        toast("Docked: ${session.label} → bubble")
                        haptic()
                    }
                }
            }
        }
    }

    private fun onTapBubble(session: Session) {
        val c = appContext ?: return
        Log.d(TAG, "restore START: task=${session.taskId} bounds=${session.originalBounds}")
        toast("Restoring ${session.label}…")
        executor.execute {
            val log = StringBuilder()
            val summary = DockTestHelper.restoreTo(
                c, session.taskId, Rect(session.originalBounds), log
            )
            dumpLog(log)
            mainHandler.post {
                if (destroyed || sessions[session.taskId] !== session) return@post
                if (isFailure(summary)) {
                    Log.e(TAG, "restore FAILED: task=${session.taskId} reason=$summary")
                    toast("Restore failed: $summary")
                    return@post
                }
                session.docked = false
                hideBubble(session)
                showHeader(session)
                Log.d(TAG, "restore OK: task=${session.taskId} summary=$summary")
                toast("Restored: ${session.label}")
            }
        }
    }

    private fun expandSession(session: Session) {
        val c = appContext ?: return
        executor.execute {
            val log = StringBuilder()
            val summary = DockTestHelper.expandTask(c, session.taskId, log)
            dumpLog(log)
            mainHandler.post {
                if (destroyed || sessions[session.taskId] !== session) return@post
                if (isFailure(summary)) {
                    toast(summary)
                    return@post
                }
                session.docked = false
                hideBubble(session)
                val metrics = dm()
                if (metrics != null) {
                    session.originalBounds = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                }
                showHeader(session)
                positionHeader(session, session.originalBounds)
                Log.d(TAG, "expanded task=${session.taskId}")
            }
        }
    }

    private fun closeSession(session: Session) {
        Log.d(TAG, "close START: task=${session.taskId} pkg=${session.pkg}")
        toast("Closing ${session.label}…")
        executor.execute {
            val log = StringBuilder()
            val summary = DockTestHelper.closeTask(session.taskId, log)
            dumpLog(log)
            mainHandler.post {
                if (destroyed || sessions[session.taskId] !== session) return@post
                if (isFailure(summary)) {
                    Log.e(TAG, "close FAILED: task=${session.taskId} reason=$summary")
                    toast("Close failed: $summary")
                } else {
                    Log.d(TAG, "close OK: task=${session.taskId} summary=$summary")
                    toast("Closed: ${session.label}")
                    removeSession(session, "closed by user")
                }
            }
        }
    }

    private fun removeSession(session: Session, reason: String) {
        Log.d(TAG, "removeSession task=${session.taskId}: $reason")
        hideHeader(session)
        hideBubble(session)
        sessions.remove(session.taskId)
        ensurePolling()
    }

    // ── bubble overlay ────────────────────────────────────────────────────

    /** Shows the bubble overlay. Returns true when it is actually visible. */
    private fun showBubble(session: Session, bubbleBounds: Rect): Boolean {
        val c = appContext ?: return false
        val wm = windowManager ?: return false
        if (session.bubble != null) return true
        if (!Settings.canDrawOverlays(c)) {
            Log.e(TAG, "bubble NOT shown: overlay permission missing (task=${session.taskId})")
            toast("Bubble failed: overlay permission missing")
            return false
        }

        val view = BubbleOverlayView(c).apply {
            appIcon = session.icon
            edgeSide = Gravity.RIGHT
            listener = bubbleListener(session)
        }
        val winW = BubbleOverlayView.windowWidth(c, false)
        val winH = BubbleOverlayView.bubbleHeight(c)
        val metrics = c.resources.displayMetrics
        val inset = c.dpToPx(4)
        val lp = newOverlayParams(c, winH, winW).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = metrics.widthPixels - winW
            val maxY = (metrics.heightPixels - winH - inset).coerceAtLeast(inset)
            y = (((bubbleBounds.top + bubbleBounds.bottom) / 2) - winH / 2).coerceIn(inset, maxY)
        }
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "bubble addView FAILED: task=${session.taskId} ${e.message}", e)
            toast("Bubble failed: ${e.message}")
            return false
        }
        session.bubble = view
        session.bubbleParams = lp
        session.bubbleSide = Gravity.RIGHT
        Log.d(TAG, "bubble shown for task=${session.taskId} x=${lp.x} y=${lp.y} ${winW}x$winH")
        return true
    }

    private fun hideBubble(session: Session) {
        bubbleAnims.remove(session.taskId)?.let { anim ->
            try { anim.cancel() } catch (e: Exception) {}
        }
        val view = session.bubble ?: return
        session.bubble = null
        session.bubbleParams = null
        val wm = windowManager ?: return
        try {
            if (view.isAttachedToWindow) wm.removeViewImmediate(view) else wm.removeView(view)
        } catch (e: Exception) {}
    }

    private fun bubbleListener(session: Session) = object : BubbleOverlayView.Listener {
        override fun onBubbleTap() = onTapBubble(session)

        override fun onBubbleLongPress() {
            haptic()
        }

        override fun onBubbleDrag(dx: Float, dy: Float) {
            val lp = session.bubbleParams ?: return
            val view = session.bubble ?: return
            val wm = windowManager ?: return
            val metrics = dm() ?: return
            bubbleAnims.remove(session.taskId)?.let { anim ->
                try { anim.cancel() } catch (e: Exception) {}
            }

            lp.x = (lp.x + dx).toInt()
            lp.y = (lp.y + dy).toInt()

            // Keep at least ~75% of the tab on screen while dragging.
            val bw = view.bubbleWidthPx()
            val b0 = view.bubbleX0()
            val centerX = lp.x + b0 + bw / 2f
            val minCenter = bw / 4f
            val maxCenter = metrics.widthPixels - bw / 4f
            if (centerX < minCenter) lp.x += (minCenter - centerX).toInt()
            if (centerX > maxCenter) lp.x += (maxCenter - centerX).toInt()

            lp.y = lp.y.coerceIn(0, (metrics.heightPixels - lp.height).coerceAtLeast(0))
            try { wm.updateViewLayout(view, lp) } catch (e: Exception) {}
        }

        override fun onBubbleDragRelease() {
            snapBubble(session)
            haptic()
        }

        override fun onMenuAction(action: BubbleOverlayView.MenuAction) {
            when (action) {
                BubbleOverlayView.MenuAction.CLOSE -> closeSession(session)
                BubbleOverlayView.MenuAction.FULLSCREEN -> expandSession(session)
            }
        }

        override fun onMenuVisibilityChanged(open: Boolean) {
            val lp = session.bubbleParams ?: return
            val view = session.bubble ?: return
            val wm = windowManager ?: return
            val c = appContext ?: return
            val newW = BubbleOverlayView.windowWidth(c, open)
            if (newW == lp.width) return
            lp.width = newW
            val metrics = dm()
            if (metrics != null) {
                lp.x = if (session.bubbleSide == Gravity.RIGHT) metrics.widthPixels - newW else 0
            }
            try { wm.updateViewLayout(view, lp) } catch (e: Exception) {}
        }

        override fun onBubbleTouch() {
            // Idle-timer hooks go here later.
        }
    }

    /** Snap the dragged bubble to the nearest edge and restack. */
    private fun snapBubble(session: Session) {
        val lp = session.bubbleParams ?: return
        val view = session.bubble ?: return
        val metrics = dm() ?: return

        val bw = view.bubbleWidthPx()
        val centerX = lp.x + view.bubbleX0() + bw / 2f
        val side = if (centerX < metrics.widthPixels / 2f) Gravity.LEFT else Gravity.RIGHT
        view.edgeSide = side
        session.bubbleSide = side

        val targetX = if (side == Gravity.RIGHT) metrics.widthPixels - lp.width else 0
        val targetY = stackedYFor(session)
        springAnimateBubble(session, targetX, targetY)
    }

    /**
     * Recompute the vertical stack (8 dp gaps, compressed to a minimum of
     * 2 dp when the screen is too short) for every bubble, applying the new
     * positions instantly to all sessions except [skip].  Returns the final
     * Y for [skip] so the caller can animate it there.
     */
    private fun stackedYFor(skip: Session): Int {
        val c = appContext ?: return skip.bubbleParams?.y ?: 0
        val metrics = c.resources.displayMetrics
        val list = sessions.values.filter { it.bubbleParams != null }
        if (list.isEmpty()) return 0

        fun compute(spacing: Int): List<Pair<Session, Int>> {
            var prevBottom = Int.MIN_VALUE
            return list.sortedBy { it.bubbleParams!!.y }.map { s ->
                val bLp = s.bubbleParams!!
                var y = bLp.y
                if (prevBottom != Int.MIN_VALUE) y = maxOf(y, prevBottom + spacing)
                val maxTop = metrics.heightPixels - bLp.height - c.dpToPx(4)
                y = y.coerceIn(c.dpToPx(4), maxTop.coerceAtLeast(c.dpToPx(4)))
                prevBottom = y + bLp.height
                s to y
            }
        }

        var result = compute(c.dpToPx(8))
        val lowest = result.maxOf { it.second + it.first.bubbleParams!!.height }
        if (lowest > metrics.heightPixels - c.dpToPx(4)) {
            result = compute(c.dpToPx(2))
        }

        for ((other, y) in result) {
            if (other === skip) continue
            val bLp = other.bubbleParams ?: continue
            if (bLp.y == y) continue
            bLp.y = y
            try { windowManager?.updateViewLayout(other.bubble, bLp) } catch (e: Exception) {}
        }
        return result.firstOrNull { it.first === skip }?.second ?: (skip.bubbleParams?.y ?: 0)
    }

    /** Spring-animate the bubble window to (tx, ty) — spec 11.3 snap feel. */
    private fun springAnimateBubble(session: Session, tx: Int, ty: Int) {
        val lp = session.bubbleParams ?: return
        val view = session.bubble ?: return
        val wm = windowManager ?: return
        bubbleAnims.remove(session.taskId)?.let { anim ->
            try { anim.cancel() } catch (e: Exception) {}
        }
        if (lp.x == tx && lp.y == ty) return

        val box = AnimBox(lp.x.toFloat(), lp.y.toFloat(), tx.toFloat(), ty.toFloat())
        val anim = SpringAnimation(box, ANIM_BOX_PROP, 0f).apply {
            spring = SpringForce(1f).setStiffness(600f).setDampingRatio(0.7f)
            addUpdateListener { _, value, _ ->
                lp.x = (box.x0 + (box.x1 - box.x0) * value).toInt()
                lp.y = (box.y0 + (box.y1 - box.y0) * value).toInt()
                try { wm.updateViewLayout(view, lp) } catch (e: Exception) {}
            }
            start()
        }
        bubbleAnims[session.taskId] = anim
    }

    // ── shared helpers ────────────────────────────────────────────────────

    private fun newOverlayParams(
        context: Context,
        height: Int,
        width: Int
    ): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        }

    private fun overlapsEdge(bounds: Rect): Boolean {
        val c = appContext ?: return false
        val zone = c.dpToPx(EDGE_ZONE_DP)
        val screenW = c.resources.displayMetrics.widthPixels
        return bounds.left < zone || bounds.right > screenW - zone
    }

    private fun isFailure(summary: String): Boolean =
        summary.contains("FAILED") || summary == "Shizuku not ready"

    private fun dm() = appContext?.resources?.displayMetrics

    private fun dumpLog(log: StringBuilder) {
        val text = log.toString()
        if (text.isNotEmpty()) Log.d(TAG, text.take(3000))
    }

    private fun toast(message: String) {
        val c = appContext ?: return
        mainHandler.post {
            if (!destroyed) Toast.makeText(c, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun haptic() {
        try {
            val c = appContext ?: return
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {}
    }
}
