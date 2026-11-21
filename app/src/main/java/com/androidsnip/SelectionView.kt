package com.androidsnip

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen overlay showing the frozen screenshot. Drag a rectangle with finger
 * or S Pen. Auto Copy: lifting the pointer finishes. Adjust: corners/edges/inside
 * can be dragged afterwards; confirm with the copy button or a double tap inside.
 * Back key or a tap without dragging cancels.
 *
 * Coordinates: the view is laid out over the whole display; the screenshot is drawn
 * so that bitmap pixel (0,0) sits at screen (0,0). screenBounds maps screen px to
 * bitmap px (normally 1:1).
 */
@SuppressLint("ViewConstructor")
class SelectionView(
    context: Context,
    private val shot: Bitmap,
    private val screenBounds: Rect,
    private val adjust: Boolean,
    private val onFinish: (Rect?) -> Unit,
) : View(context) {

    private val dp = resources.displayMetrics.density
    private val startOrientation = resources.configuration.orientation
    private val loc = IntArray(2)
    private val sel = RectF()
    private var hasSel = false
    private var finished = false

    private enum class Drag { NONE, DRAW, MOVE, RESIZE, BUTTON }
    private var drag = Drag.NONE
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private val startSel = RectF()
    private var edges = 0 // bit flags: 1=L 2=T 4=R 8=B
    private var lastTapTime = 0L

    private val btnCancel = RectF()
    private val btnCopy = RectF()
    private var pressedBtn: RectF? = null

    private val scrim = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val outline = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = Color.argb(160, 0, 0, 0) }
    private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = Color.WHITE }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.rgb(30, 136, 229) }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 33, 33, 33) }
    private val pillAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 136, 229) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * dp; textAlign = Paint.Align.CENTER }
    private val dimText = Paint(text).apply { textSize = 12 * dp }

    private val minSize = 8 * dp
    private val touchSlop = 24 * dp

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        // Hide the (live) system bars while selecting. Otherwise SystemUI steals drags
        // that start on the status bar (notification shade) or the bottom gesture area.
        // The frozen screenshot still shows the bars, so nothing visibly changes.
        windowInsetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        canvas.scale(screenBounds.width().toFloat() / shot.width, screenBounds.height().toFloat() / shot.height)
        canvas.drawBitmap(shot, 0f, 0f, null)
        canvas.restore()

        val w = width.toFloat(); val h = height.toFloat()
        if (!hasSel) {
            canvas.drawRect(0f, 0f, w, h, scrim)
            val label = if (adjust) "드래그하여 영역 선택 · 조정 후 복사" else "드래그하여 영역 선택 · 놓으면 복사"
            drawPill(canvas, w / 2, 40 * dp + statusInset(), label, "뒤로가기 또는 탭하여 취소")
            return
        }
        canvas.drawRect(0f, 0f, w, sel.top, scrim)
        canvas.drawRect(0f, sel.bottom, w, h, scrim)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, scrim)
        canvas.drawRect(sel.right, sel.top, w, sel.bottom, scrim)
        canvas.drawRect(sel, outline)
        canvas.drawRect(sel, border)

        val pw = (sel.width() * shot.width / screenBounds.width()).toInt()
        val ph = (sel.height() * shot.height / screenBounds.height()).toInt()
        val sizeY = if (sel.top > 30 * dp) sel.top - 8 * dp else sel.bottom + 20 * dp
        canvas.drawText("$pw × $ph", sel.centerX(), sizeY, dimText)

        if (adjust && drag != Drag.DRAW) {
            for ((x, y) in handlePoints()) {
                canvas.drawCircle(x, y, 7 * dp, handle)
                canvas.drawCircle(x, y, 7 * dp, handleRing)
            }
            layoutButtons()
            canvas.drawRoundRect(btnCancel, 20 * dp, 20 * dp, pill)
            canvas.drawRoundRect(btnCopy, 20 * dp, 20 * dp, pillAccent)
            val ty = (text.descent() + text.ascent()) / 2
            canvas.drawText("취소", btnCancel.centerX(), btnCancel.centerY() - ty, text)
            canvas.drawText("복사", btnCopy.centerX(), btnCopy.centerY() - ty, text)
        }
    }

    private fun statusInset(): Float =
        rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top?.toFloat() ?: 0f

    private fun drawPill(c: Canvas, cx: Float, cy: Float, line1: String, line2: String) {
        val tw = max(text.measureText(line1), dimText.measureText(line2)) + 40 * dp
        val r = RectF(cx - tw / 2, cy - 26 * dp, cx + tw / 2, cy + 26 * dp)
        c.drawRoundRect(r, 26 * dp, 26 * dp, pill)
        c.drawText(line1, cx, cy - 2 * dp, text)
        c.drawText(line2, cx, cy + 17 * dp, dimText)
    }

    private fun handlePoints() = listOf(
        sel.left to sel.top, sel.right to sel.top, sel.left to sel.bottom, sel.right to sel.bottom,
        sel.centerX() to sel.top, sel.centerX() to sel.bottom, sel.left to sel.centerY(), sel.right to sel.centerY(),
    )

    private fun layoutButtons() {
        val bw = 84 * dp; val bh = 40 * dp; val gap = 12 * dp
        var top = sel.bottom + 16 * dp
        if (top + bh > height - 24 * dp) top = sel.top - 16 * dp - bh
        if (top < 24 * dp) top = sel.bottom - bh - 16 * dp // no room outside: put inside
        val cx = sel.centerX().coerceIn(bw + gap, width - bw - gap)
        btnCancel.set(cx - gap / 2 - bw, top, cx - gap / 2, top + bh)
        btnCopy.set(cx + gap / 2, top, cx + gap / 2 + bw, top + bh)
    }

    // ---- input ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (finished) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0)
                downX = e.x; downY = e.y
                startSel.set(sel)
                drag = pickDrag(e.x, e.y)
                if (drag == Drag.DRAW) { sel.set(e.x, e.y, e.x, e.y); hasSel = false }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i < 0) return true
                for (h in 0 until e.historySize) track(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                track(e.getX(i), e.getY(i))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val i = e.findPointerIndex(pointerId)
                if (i < 0) return true
                track(e.getX(i), e.getY(i)) // the lift point is part of the selection
                onUp(e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (drag == Drag.DRAW) hasSel = false
                if (drag == Drag.MOVE || drag == Drag.RESIZE) sel.set(startSel)
                drag = Drag.NONE
                invalidate()
            }
        }
        return true
    }

    private fun track(rawX: Float, rawY: Float) {
        val x = rawX.coerceIn(0f, width.toFloat())
        val y = rawY.coerceIn(0f, height.toFloat())
        when (drag) {
            Drag.DRAW -> {
                sel.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                hasSel = sel.width() >= minSize || sel.height() >= minSize
            }
            Drag.MOVE -> {
                val dx = (x - downX).coerceIn(-startSel.left, width - startSel.right)
                val dy = (y - downY).coerceIn(-startSel.top, height - startSel.bottom)
                sel.set(startSel); sel.offset(dx, dy)
            }
            Drag.RESIZE -> resize(x, y)
            else -> {}
        }
    }

    private fun pickDrag(x: Float, y: Float): Drag {
        if (!adjust || !hasSel) return Drag.DRAW
        if (btnCopy.contains(x, y)) { pressedBtn = btnCopy; return Drag.BUTTON }
        if (btnCancel.contains(x, y)) { pressedBtn = btnCancel; return Drag.BUTTON }
        val r = touchSlop
        val inY = y > sel.top - r && y < sel.bottom + r
        val inX = x > sel.left - r && x < sel.right + r
        edges = 0
        if (inY && abs(x - sel.left) < r) edges = edges or 1
        if (inY && abs(x - sel.right) < r) edges = edges or 4
        if (inX && abs(y - sel.top) < r) edges = edges or 2
        if (inX && abs(y - sel.bottom) < r) edges = edges or 8
        // For tiny selections prefer the nearer of two opposite edges.
        if ((edges and 5) == 5) edges = (edges and 10) or (if (abs(x - sel.left) < abs(x - sel.right)) 1 else 4)
        if ((edges and 10) == 10) edges = (edges and 5) or (if (abs(y - sel.top) < abs(y - sel.bottom)) 2 else 8)
        if (edges != 0) return Drag.RESIZE
        if (sel.contains(x, y)) return Drag.MOVE
        return Drag.DRAW
    }

    private fun resize(x: Float, y: Float) {
        sel.set(startSel)
        if ((edges and 1) != 0) sel.left = min(x, sel.right - minSize)
        if ((edges and 4) != 0) sel.right = max(x, sel.left + minSize)
        if ((edges and 2) != 0) sel.top = min(y, sel.bottom - minSize)
        if ((edges and 8) != 0) sel.bottom = max(y, sel.top + minSize)
    }

    private fun onUp(x: Float, y: Float, time: Long) {
        val was = drag
        drag = Drag.NONE
        when (was) {
            Drag.BUTTON -> {
                val b = pressedBtn; pressedBtn = null
                if (b != null && b.contains(x, y)) finish(if (b === btnCopy) bitmapRect() else null)
            }
            Drag.DRAW -> {
                if (sel.width() < minSize || sel.height() < minSize) {
                    // A tap (or a line) is not a selection: cancel, like pressing Back.
                    hasSel = false
                    finish(null)
                } else if (!adjust) {
                    finish(bitmapRect())
                }
            }
            Drag.MOVE -> {
                val tap = abs(x - downX) < minSize && abs(y - downY) < minSize
                if (tap && time - lastTapTime < 350) finish(bitmapRect())
                lastTapTime = if (tap) time else 0L
            }
            else -> {}
        }
        invalidate()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) finish(null)
            return true
        }
        if (adjust && hasSel && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
            finish(bitmapRect()); return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Rotation while selecting: the frozen frame no longer matches the screen.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != startOrientation) finish(null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 && (w != oldw || h != oldh)) finish(null)
    }

    /** Selection in screenshot pixels, clamped to the bitmap. */
    private fun bitmapRect(): Rect {
        getLocationOnScreen(loc)
        val sx = shot.width.toFloat() / screenBounds.width()
        val sy = shot.height.toFloat() / screenBounds.height()
        val l = ((sel.left + loc[0]) * sx).toInt().coerceIn(0, shot.width - 1)
        val t = ((sel.top + loc[1]) * sy).toInt().coerceIn(0, shot.height - 1)
        val r = ((sel.right + loc[0]) * sx).toInt().coerceIn(l + 1, shot.width)
        val b = ((sel.bottom + loc[1]) * sy).toInt().coerceIn(t + 1, shot.height)
        return Rect(l, t, r, b)
    }

    private fun finish(rect: Rect?) {
        if (finished) return
        finished = true
        onFinish(rect)
    }
}
