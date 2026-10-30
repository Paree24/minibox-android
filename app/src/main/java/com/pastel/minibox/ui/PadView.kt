package com.pastel.minibox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.pastel.minibox.engine.Native

/**
 * The 4×4 square pads (PLAN.md §3.3). A plain View rather than Compose so touches arrive unbatched
 * (requestUnbufferedDispatch) and a note fires on finger-down, never on release, with no gesture detector.
 * Every pointer owns the pad under it: sliding onto another pad retriggers, lifting sends the note-off.
 * Velocity is the touch height within the pad (top = loud).
 */
class PadView(context: Context) : View(context) {

    /** What the 16 pads play; index 0 is bottom-left. A roll pad repeats its note every `rollBeats` while held;
        `subs` is a second, quieter label (a chord's degree, a roll's rate). */
    class Layout(
        val track: Int, val color: Int, val notes: IntArray, val labels: Array<String>, val roots: Set<Int>,
        val subs: Array<String> = Array(16) { "" }, val rolls: BooleanArray = BooleanArray(16), val rollBeats: Float = 0.25f,
        val bigLabels: Boolean = false,
    )

    var layout: Layout? = null
        set(value) {
            releaseAll()
            field = value
            invalidate()
        }

    /** Told about every press and release (after the note): the Seq tab places a held pad's note into steps. */
    var listener: ((note: Int, down: Boolean) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val gap = 8 * dp
    private val radius = 14 * dp
    private val held = HashMap<Int, Int>()          // pointer id → pad
    private val lit = IntArray(16)                   // how many pointers hold each pad
    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 13 * dp
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 11 * dp
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 20 * dp
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)   // square: four square pads a side
    }

    private fun padSize() = (width - 3 * gap) / 4f

    private fun padAt(x: Float, y: Float): Int {
        val s = padSize()
        val col = (x / (s + gap)).toInt()
        val rowFromTop = (y / (s + gap)).toInt()
        if (col !in 0..3 || rowFromTop !in 0..3) return -1
        if (x - col * (s + gap) > s || y - rowFromTop * (s + gap) > s) return -1   // in a gap
        return (3 - rowFromTop) * 4 + col
    }

    private fun velocityAt(pad: Int, y: Float): Float {
        val s = padSize()
        val top = (3 - pad / 4) * (s + gap)
        val frac = ((y - top) / s).coerceIn(0f, 1f)
        return 1f - 0.7f * frac          // top edge 1.0, bottom edge 0.3
    }

    private fun press(pointer: Int, pad: Int, y: Float) {
        val l = layout ?: return
        held[pointer] = pad
        if (lit[pad]++ == 0) {
            if (l.rolls[pad]) Native.roll(l.track, l.notes[pad], velocityAt(pad, y), l.rollBeats, true)
            else Native.noteOn(l.track, l.notes[pad], velocityAt(pad, y))
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)   // after the note, never before it
        listener?.invoke(l.notes[pad], true)
    }

    private fun release(pointer: Int) {
        val l = layout ?: return
        val pad = held.remove(pointer) ?: return
        if (--lit[pad] == 0) {
            if (l.rolls[pad]) Native.roll(l.track, l.notes[pad], 0f, l.rollBeats, false)
            else Native.noteOff(l.track, l.notes[pad])
            listener?.invoke(l.notes[pad], false)
        }
    }

    private fun releaseAll() {
        for (p in held.keys.toList()) release(p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e)
                val i = e.actionIndex
                val pad = padAt(e.getX(i), e.getY(i))
                if (pad >= 0) press(e.getPointerId(i), pad, e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                val pad = padAt(e.getX(i), e.getY(i))
                if (pad != held[id]) {
                    release(id)
                    if (pad >= 0) press(id, pad, e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val s = padSize()
        for (pad in 0 until 16) {
            val col = pad % 4
            val rowFromTop = 3 - pad / 4
            val x = col * (s + gap)
            val y = rowFromTop * (s + gap)
            rect.set(x, y, x + s, y + s)
            val on = lit[pad] > 0
            val root = pad in l.roots
            padPaint.color = when {
                on -> l.color
                else -> Tokens.CONTROL
            }
            canvas.drawRoundRect(rect, radius, radius, padPaint)
            if (root && !on) {
                padPaint.color = (l.color and 0x00FFFFFF) or (0x47 shl 24)
                canvas.drawRoundRect(rect, radius, radius, padPaint)
            }
            if (l.bigLabels) {
                bigPaint.color = if (on) Tokens.BG else Tokens.TEXT
                canvas.drawText(l.labels[pad], x + s / 2, y + s / 2 + 4 * dp, bigPaint)
                subPaint.color = if (on) Tokens.BG else if (root) l.color else Tokens.FAINT
                subPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(l.subs[pad], x + s / 2, y + s / 2 + 22 * dp, subPaint)
            } else {
                textPaint.color = if (on) Tokens.BG else Tokens.TEXT
                canvas.drawText(l.labels[pad], x + 12 * dp, y + 22 * dp, textPaint)
                subPaint.color = if (on) Tokens.BG else Tokens.FAINT
                subPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(l.subs[pad], x + 12 * dp, y + s - 12 * dp, subPaint)
            }
            if (l.rolls[pad]) {   // three bars: this pad repeats
                padPaint.color = if (on) Tokens.BG else l.color
                for (k in 0 until 3) {
                    val bx = x + s - 26 * dp + k * 6 * dp
                    canvas.drawRoundRect(bx, y + 12 * dp, bx + 3 * dp, y + 24 * dp, 1.5f * dp, 1.5f * dp, padPaint)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }
}
