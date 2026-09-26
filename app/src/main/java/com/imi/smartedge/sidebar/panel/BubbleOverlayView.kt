package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Docked edge bubble (Phase B of the dock-to-bubble spec).
 *
 * A lightweight single-View custom draw: a white rounded tab holding the app
 * icon, drawn flush against the left or right screen edge.  A long press
 * opens a small in-view menu (Close / Fullscreen) drawn next to the tab.
 *
 * The view never moves its own window — every gesture outcome is reported to
 * [Listener] and DockManager owns the WindowManager layout updates.
 */
class BubbleOverlayView(context: Context) : View(context) {

    enum class MenuAction { CLOSE, FULLSCREEN }

    interface Listener {
        /** Single tap on the bubble (no menu open): restore the window. */
        fun onBubbleTap()

        /** Long press: the view opens its menu; listener adds haptics. */
        fun onBubbleLongPress()

        /** Incremental drag delta in px since the previous call. */
        fun onBubbleDrag(dx: Float, dy: Float)

        /** Finger lifted after a drag: snap to the nearest edge. */
        fun onBubbleDragRelease()

        /** Menu item chosen. */
        fun onMenuAction(action: MenuAction)

        /** Menu opened/closed — the window width must be resized to match. */
        fun onMenuVisibilityChanged(open: Boolean)

        /** Any touch (used to reset idle timers). */
        fun onBubbleTouch()
    }

    var listener: Listener? = null

    /** App icon drawn centered inside the tab. */
    var appIcon: Drawable? = null
        set(value) { field = value; invalidate() }

    /** Which screen edge the tab is docked against. */
    var edgeSide: Int = Gravity.RIGHT
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Long-press menu visibility. Window width is adjusted by DockManager. */
    var menuOpen: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private val bubbleW = dp(48)
    private val bubbleH = dp(72)
    private val menuW = dp(104)
    private val radius = dp(16).toFloat()
    private val menuRadius = dp(12).toFloat()
    private val gap = dp(6)
    private val iconSize = dp(28)
    private val textSize = 13f * resources.displayMetrics.scaledDensity
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 242
        style = Paint.Style.FILL
    }
    private val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F01F1F1F")
        style = Paint.Style.FILL
    }
    private val menuTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = this@BubbleOverlayView.textSize
    }

    private val detector = android.view.GestureDetector(context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!menuOpen) listener?.onBubbleTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!menuOpen) {
                    setMenuOpen(true)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    listener?.onBubbleLongPress()
                }
            }
        })

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                val x0 = if (edgeSide == Gravity.RIGHT) width - bubbleW else 0
                outline.setRoundRect(x0, 0, x0 + bubbleW, height, radius)
            }
        }
        setWillNotDraw(false)
    }

    /** Open or close the long-press menu (also notifies [Listener]). */
    fun setMenuOpen(open: Boolean) {
        if (menuOpen == open) return
        menuOpen = open
        listener?.onMenuVisibilityChanged(open)
        invalidate()
    }

    /** Width of the tab portion inside the current window. */
    fun bubbleWidthPx(): Int = bubbleW

    /** X offset of the tab inside the current window. */
    fun bubbleX0(): Int = if (edgeSide == Gravity.RIGHT) width - bubbleW else 0

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun bubbleRect(): RectF {
        val x0 = bubbleX0().toFloat()
        return RectF(x0, 0f, x0 + bubbleW, bubbleH.toFloat())
    }

    private fun menuRects(): Pair<RectF, RectF> {
        val cw = width
        val itemH = (bubbleH - gap) / 2
        val baseX = if (edgeSide == Gravity.RIGHT) (cw - bubbleW - menuW + dp(4)).toFloat()
                    else (bubbleW + dp(4)).toFloat()
        val w = (menuW - dp(8)).toFloat()
        val close = RectF(baseX, 0f, baseX + w, itemH.toFloat())
        val expand = RectF(baseX, (itemH + gap).toFloat(), baseX + w, (itemH + gap + itemH).toFloat())
        return close to expand
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cw = width

        if (menuOpen && cw > bubbleW) {
            val (closeRect, expandRect) = menuRects()
            canvas.drawRoundRect(closeRect, menuRadius, menuRadius, menuPaint)
            canvas.drawRoundRect(expandRect, menuRadius, menuRadius, menuPaint)
            val closeCy = closeRect.centerY()
            val expandCy = expandRect.centerY()
            canvas.drawText("Close", closeRect.centerX(), closeCy - (menuTextPaint.descent() + menuTextPaint.ascent()) / 2f, menuTextPaint)
            canvas.drawText("Fullscreen", expandRect.centerX(), expandCy - (menuTextPaint.descent() + menuTextPaint.ascent()) / 2f, menuTextPaint)
        }

        val bubble = bubbleRect()
        canvas.drawRoundRect(bubble, radius, radius, bubblePaint)

        val icon = appIcon
        if (icon != null) {
            val cx = bubble.centerX()
            val cy = bubble.centerY()
            icon.setBounds(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2)
            icon.draw(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!menuOpen) detector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                listener?.onBubbleTouch()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        dragging = true
                        if (menuOpen) setMenuOpen(false)
                    }
                }
                if (dragging) {
                    listener?.onBubbleDrag(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    listener?.onBubbleDragRelease()
                } else if (menuOpen) {
                    handleMenuTap(event.x, event.y)
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    private fun handleMenuTap(x: Float, y: Float) {
        val (closeRect, expandRect) = menuRects()
        when {
            closeRect.contains(x, y) -> {
                setMenuOpen(false)
                listener?.onMenuAction(MenuAction.CLOSE)
            }
            expandRect.contains(x, y) -> {
                setMenuOpen(false)
                listener?.onMenuAction(MenuAction.FULLSCREEN)
            }
            else -> setMenuOpen(false)
        }
    }

    companion object {
        /** Width of the visible tab (matches the private dp(48) geometry). */
        fun bubbleWidth(context: Context): Int = context.dpToPx(48)

        /** Height of the tab / bubble window (matches dp(72)). */
        fun bubbleHeight(context: Context): Int = context.dpToPx(72)

        /** Width of the long-press menu strip (matches dp(104)). */
        fun menuWidth(context: Context): Int = context.dpToPx(104)

        /** Total window width: bubble alone, or bubble + menu when open. */
        fun windowWidth(context: Context, menuOpen: Boolean): Int =
            bubbleWidth(context) + if (menuOpen) menuWidth(context) else 0
    }
}
