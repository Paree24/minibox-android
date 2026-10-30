package com.pastel.minibox.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.pastel.minibox.engine.Native

/** What the 16 pads play; index 0 is bottom-left. A roll pad repeats its note every `rollBeats` while held;
    `subs` is a second, quieter label (a chord's degree, a roll's rate). */
class PadLayout(
    val track: Int, val color: Int, val notes: IntArray, val labels: Array<String>, val roots: Set<Int>,
    val subs: Array<String> = Array(16) { "" }, val rolls: BooleanArray = BooleanArray(16), val rollBeats: Float = 0.25f,
    val bigLabels: Boolean = false,
) {
    /** Changes only when the pads mean other notes (a redraw hands an equal layout, which must not cut notes). */
    val signature: String = "$track|${notes.joinToString()}|${rolls.joinToString()}|$rollBeats"
}

/**
 * The 4×4 square pads (PLAN.md §3.3), in Compose so every finger is tracked on its own: pads and knobs (or two pads)
 * play at the same time whichever went down first. A note fires on finger-down, never on release, through no gesture
 * detector; sliding onto another pad retriggers; lifting sends the note-off. Velocity is the touch height within the
 * pad (top = loud). Touches arrive unbuffered (MainActivity asks for it, Android 11+).
 */
@Composable
fun PadGrid(layout: PadLayout, onPress: (note: Int, down: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val lit = remember { mutableStateListOf(*Array(16) { 0 }) }
    val held = remember { HashMap<Long, Int>() }       // pointer → pad
    val current = remember { arrayOf(layout) }

    fun press(pointer: Long, pad: Int, vel: Float) {
        val l = current[0]
        held[pointer] = pad
        if (lit[pad]++ == 0) {
            if (l.rolls[pad]) Native.roll(l.track, l.notes[pad], vel, l.rollBeats, true)
            else Native.noteOn(l.track, l.notes[pad], vel)
            onPress(l.notes[pad], true)
        }
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)   // after the note, never before it
    }
    fun release(pointer: Long, l: PadLayout = current[0]) {
        val pad = held.remove(pointer) ?: return
        if (--lit[pad] == 0) {
            if (l.rolls[pad]) Native.roll(l.track, l.notes[pad], 0f, l.rollBeats, false)
            else Native.noteOff(l.track, l.notes[pad])
            onPress(l.notes[pad], false)
        }
    }
    // the pads now mean other notes (another track, mode, scale, octave): let go of the old ones, then switch
    DisposableEffect(layout.signature) {
        val mine = layout
        current[0] = mine
        onDispose { held.keys.toList().forEach { release(it, mine) } }   // the notes these pads were playing
    }

    val text = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
            val gap = 8.dp.toPx()
            fun padAt(o: Offset): Int {
                val s = (size.width - 3 * gap) / 4f
                if (o.x < 0 || o.y < 0) return -1
                val col = (o.x / (s + gap)).toInt()
                val row = (o.y / (s + gap)).toInt()
                if (col !in 0..3 || row !in 0..3) return -1
                if (o.x - col * (s + gap) > s || o.y - row * (s + gap) > s) return -1   // in a gap
                return (3 - row) * 4 + col
            }
            // a finger already on a pad keeps it until it is clearly inside another one (6 dp past its edge): a
            // resting finger wobbling over a gap or an edge must not re-strike the note (it chopped held notes up)
            fun clearlyIn(pad: Int, o: Offset): Boolean {
                val s = (size.width - 3 * gap) / 4f
                val m = 6.dp.toPx()
                val left = (pad % 4) * (s + gap); val top = (3 - pad / 4) * (s + gap)
                return o.x > left + m && o.x < left + s - m && o.y > top + m && o.y < top + s - m
            }
            fun velocityAt(pad: Int, y: Float): Float {
                val s = (size.width - 3 * gap) / 4f
                val top = (3 - pad / 4) * (s + gap)
                return 1f - 0.7f * ((y - top) / s).coerceIn(0f, 1f)   // top edge 1.0, bottom edge 0.3
            }
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    for (c in e.changes) {
                        val id = c.id.value
                        when {
                            c.changedToDownIgnoreConsumed() -> { val p = padAt(c.position); if (p >= 0) press(id, p, velocityAt(p, c.position.y)) }
                            !c.pressed -> release(id)
                            else -> {
                                val p = padAt(c.position)
                                val cur = held[id] ?: -1
                                if (p >= 0 && p != cur && (cur < 0 || clearlyIn(p, c.position))) { release(id); press(id, p, velocityAt(p, c.position.y)) }
                            }
                        }
                        c.consume()
                    }
                }
            }
        },
    ) {
        val gap = 8.dp.toPx()
        val s = (size.width - 3 * gap) / 4f
        val r = CornerRadius(14.dp.toPx())
        val l = layout
        val accent = Color(l.color)
        val c = drawContext.canvas.nativeCanvas
        for (pad in 0 until 16) {
            val x = (pad % 4) * (s + gap)
            val y = (3 - pad / 4) * (s + gap)
            val on = lit[pad] > 0
            drawRoundRect(if (on) accent else C.control, Offset(x, y), Size(s, s), r)
            if (pad in l.roots && !on) drawRoundRect(accent.copy(alpha = 0.28f), Offset(x, y), Size(s, s), r)
            val fg = if (on) Tokens.BG else Tokens.TEXT
            if (l.bigLabels) {
                text.textAlign = Paint.Align.CENTER
                text.textSize = 20.dp.toPx(); text.color = fg
                c.drawText(l.labels[pad], x + s / 2, y + s / 2 + 4.dp.toPx(), text)
                text.textSize = 11.dp.toPx(); text.color = if (on) Tokens.BG else if (pad in l.roots) l.color else Tokens.FAINT
                c.drawText(l.subs[pad], x + s / 2, y + s / 2 + 22.dp.toPx(), text)
            } else {
                text.textAlign = Paint.Align.LEFT
                text.textSize = 13.dp.toPx(); text.color = fg
                c.drawText(l.labels[pad], x + 12.dp.toPx(), y + 22.dp.toPx(), text)
                text.textSize = 11.dp.toPx(); text.color = if (on) Tokens.BG else Tokens.FAINT
                c.drawText(l.subs[pad], x + 12.dp.toPx(), y + s - 12.dp.toPx(), text)
            }
            if (l.rolls[pad]) for (k in 0 until 3) {   // three bars: this pad repeats
                drawRoundRect(if (on) C.bg else accent, Offset(x + s - 26.dp.toPx() + k * 6.dp.toPx(), y + 12.dp.toPx()),
                    Size(3.dp.toPx(), 12.dp.toPx()), CornerRadius(1.5.dp.toPx()))
            }
        }
    }
}
