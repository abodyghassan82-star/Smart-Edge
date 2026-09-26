package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Floating window header (Phase B of the dock-to-bubble spec).
 *
 * A lightweight single-View custom draw attached above/below a freeform
 * window launched from the panel: expand button (left), app icon + label,
 * a drag-handle pill (centre) that moves the window, then the minimize/dock
 * and close buttons (right).  The window itself is positioned and moved by
 * DockManager; this view only reports gestures through [Listener].
 */
class HeaderOverlayView(context: Context) : View(context) {

    interface Listener {
        /** Expand button: window goes fullscreen. */
        fun onExpand()

        /** Minimize/dock button: shrink the window into an edge bubble. */
        fun onDock()

        /** Close button: remove the task. */
        fun onClose()

        /** Incremental drag delta in px from the drag pill. */
        fun onHeaderDrag(dx: Float, dy: Float)

        /** Finger lifted after a pill drag. */
        fun onHeaderDragRelease()

        /** Any touch on the header (e.g. to reset timers). */
        fun onHeaderTouch()
    }

    private enum class Target { NONE, EXPAND, DOCK, CLOSE, DRAG }

    var listener: Listener? = null

    var appIcon: Drawable? = null
        set(value) { field = value; invalidate() }

    private var label: String = ""
    private var drawLabel: String = ""

    fun setHeaderInfo(icon: Drawable?, text: String) {
        appIcon = icon
        label = text
        // Spec 11.1: ellipsis after 12 characters
        drawLabel = if (text.length > 12) text.take(12) + "…" else text
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val padL = dp(6)
    private val btnSize = dp(26)
    private val iconSize = dp(20)
    private val pillW = dp(28)
    private val pillH = dp(4)
    private val radius = dp(18).toFloat()
    private val stroke = dp(2).toFloat()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1F1F1F")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9FFFFFF")
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.FILL
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }

    private var pressed: Target = Target.NONE
    private var downTarget: Target = Target.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private fun dp(v: Int): Int = (v * density).toInt()

    // ── layout (recomputed on demand; cheap) ──────────────────────────────

    private fun expandRect(): RectF =
        RectF(padL.toFloat(), ((height - btnSize) / 2).toFloat(),
             (padL + btnSize).toFloat(), ((height + btnSize) / 2).toFloat())

    private fun closeRect(): RectF =
        RectF((width - padL - btnSize).toFloat(), ((height - btnSize) / 2).toFloat(),
              (width - padL).toFloat(), ((height + btnSize) / 2).toFloat())

    private fun dockRect(): RectF =
        RectF((width - padL - btnSize * 2 - dp(4)).toFloat(), ((height - btnSize) / 2).toFloat(),
              (width - padL - btnSize - dp(4)).toFloat(), ((height + btnSize) / 2).toFloat())

    private fun pillRect(): RectF {
        val cx = width / 2f
        return RectF(cx - pillW / 2f, height / 2f - pillH / 2f,
                     cx + pillW / 2f, height / 2f + pillH / 2f)
    }

    private fun iconRect(): RectF {
        val left = padL + btnSize + dp(4)
        return RectF(left.toFloat(), ((height - iconSize) / 2).toFloat(),
                     (left + iconSize).toFloat(), ((height + iconSize) / 2).toFloat())
    }

    private fun labelMaxWidth(): Int {
        val start = (padL + btnSize + dp(4) + iconSize + dp(6))
        val end = pillRect().left.toInt() - dp(8)
        return (end - start).coerceAtLeast(dp(20))
    }

    private fun hitTest(x: Float, y: Float): Target = when {
        expandRect().contains(x, y) -> Target.EXPAND
        closeRect().contains(x, y) -> Target.CLOSE
        dockRect().contains(x, y) -> Target.DOCK
        pillRect().contains(x, y) -> Target.DRAG
        else -> Target.NONE
    }

    // ── drawing ───────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f

        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, bgPaint)

        val expand = expandRect()
        val dock = dockRect()
        val close = closeRect()

        if (pressed == Target.EXPAND) canvas.drawRoundRect(expand, radius / 2, radius / 2, pressPaint)
        if (pressed == Target.DOCK) canvas.drawRoundRect(dock, radius / 2, radius / 2, pressPaint)
        if (pressed == Target.CLOSE) canvas.drawRoundRect(close, radius / 2, radius / 2, pressPaint)

        // expand glyph: four outward corners (fullscreen)
        drawExpandGlyph(canvas, expand)

        // app icon + label
        val icon = appIcon
        if (icon != null) {
            val r = iconRect()
            icon.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
            icon.draw(canvas)
        }
        if (drawLabel.isNotEmpty()) {
            val textLeft = padL + btnSize + dp(4) + iconSize + dp(6)
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.save()
            canvas.clipRect(textLeft, 0, textLeft + labelMaxWidth(), height)
            canvas.drawText(drawLabel, textLeft.toFloat(), baseline, textPaint)
            canvas.restore()
        }

        // drag pill
        canvas.drawRoundRect(pillRect(), pillH / 2f, pillH / 2f, pillPaint)

        // minimize/dock glyph: underscore (minimize)
        canvas.drawLine(dock.centerX() - dp(7), cy + dp(6),
                        dock.centerX() + dp(7), cy + dp(6), glyphPaint)

        // close glyph: X
        canvas.drawLine(close.centerX() - dp(6), cy - dp(6),
                        close.centerX() + dp(6), cy + dp(6), glyphPaint)
        canvas.drawLine(close.centerX() + dp(6), cy - dp(6),
                        close.centerX() - dp(6), cy + dp(6), glyphPaint)
    }

    private fun drawExpandGlyph(canvas: Canvas, r: RectF) {
        val m = dp(7).toFloat()
        val arm = dp(5).toFloat()
        val path = Path()
        // top-left corner
        path.moveTo(r.left + m, r.top + m + arm)
        path.lineTo(r.left + m, r.top + m)
        path.lineTo(r.left + m + arm, r.top + m)
        // top-right corner
        path.moveTo(r.right - m - arm, r.top + m)
        path.lineTo(r.right - m, r.top + m)
        path.lineTo(r.right - m, r.top + m + arm)
        // bottom-left corner
        path.moveTo(r.left + m, r.bottom - m - arm)
        path.lineTo(r.left + m, r.bottom - m)
        path.lineTo(r.left + m + arm, r.bottom - m)
        // bottom-right corner
        path.moveTo(r.right - m - arm, r.bottom - m)
        path.lineTo(r.right - m, r.bottom - m)
        path.lineTo(r.right - m, r.bottom - m - arm)
        canvas.drawPath(path, glyphPaint)
    }

    // ── touch ─────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                downTarget = hitTest(event.x, event.y)
                pressed = downTarget
                listener?.onHeaderTouch()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (downTarget == Target.DRAG && !dragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        dragging = true
                        pressed = Target.NONE
                        invalidate()
                    }
                }
                if (dragging) {
                    listener?.onHeaderDrag(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    listener?.onHeaderDragRelease()
                } else {
                    val up = hitTest(event.x, event.y)
                    if (up != Target.NONE && up == downTarget) {
                        when (up) {
                            Target.EXPAND -> listener?.onExpand()
                            Target.DOCK -> listener?.onDock()
                            Target.CLOSE -> listener?.onClose()
                            else -> {}
                        }
                        if (up != Target.DRAG) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
                pressed = Target.NONE
                downTarget = Target.NONE
                dragging = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = Target.NONE
                downTarget = Target.NONE
                dragging = false
                invalidate()
                return true
            }
        }
        return true
    }
}
