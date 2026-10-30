package com.pastel.minibox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pastel.minibox.engine.Native
import com.pastel.minibox.state.App
import com.pastel.minibox.state.ClipNote
import com.pastel.minibox.state.Seq
import kotlin.math.abs
import kotlin.math.roundToInt

private val SNAPS = listOf("1/32" to 0.5f, "1/16" to 1f, "1/8" to 2f, "1/4" to 4f, "Off" to 0f)

/** A note being drawn, moved or resized, before the finger lifts (one undo step per gesture). */
private data class Preview(val id: Int, val start: Int, val length: Int, val note: Int, val velocity: Int)

/** The piano roll panel (mockup 15): keys, a ruler, free-length notes, Draw / Select, snap, undo, a velocity lane.
    Draw: tap an empty place for a note (drag to set its length), tap a note to delete it. Select: tap to select,
    drag a note to move it, drag its end to resize it. Drag the keys to scroll; tap a key to hear it. */
@Composable
fun PianoRoll(bar: Int, color: Color) {
    val seq by Seq.data
    val fast by App.fast
    var tool by remember { mutableIntStateOf(0) }
    var snap by remember { mutableIntStateOf(1) }
    var selected by remember { mutableIntStateOf(-1) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var lastLength by remember { mutableIntStateOf(0) }
    // the rows: the project scale's notes (Scale on, the default: every note drawn or moved stays in key), or all 128
    val project by App.project
    var scaleOn by remember { mutableStateOf(true) }
    val rowNotes = remember(scaleOn, project.scaleRoot, project.scaleIntervals) {
        if (!scaleOn) (0..127).toList()
        else (0..127).filter { ((it - project.scaleRoot) % 12 + 12) % 12 in project.scaleIntervals }
    }
    val rowsNow by rememberUpdatedState(rowNotes)
    /** A note's row: its own, or the row below it when it is off the scale. */
    fun rowOf(note: Int): Int = rowsNow.indexOfLast { it <= note }.coerceAtLeast(0)
    var low by remember { mutableIntStateOf(-1) }   // the bottom row in view (an index into the rows)
    if (low == -1) low = rowOf(seq.rollNotes.minOfOrNull { it.note }?.minus(2) ?: (App.lastNote.intValue - 4))
    val track = App.focusTrack.intValue
    val stepT = seq.stepTicks
    val barTicks = seq.stepsPerBar * stepT
    val barStart = bar * barTicks
    val keyLabel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Tokens.BG; textAlign = android.graphics.Paint.Align.RIGHT; typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    val rulerLabel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Tokens.FAINT; typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    fun snapTicks() = (SNAPS[snap].second * stepT).roundToInt().coerceAtLeast(1)
    fun snapTo(t: Int) = if (SNAPS[snap].second == 0f) t else (t.toFloat() / snapTicks()).roundToInt() * snapTicks()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("Draw", "Select"), tool, C.text, { tool = it }, Modifier.width(140.dp), height = 32.dp)
            Spacer(Modifier.width(8.dp))
            Pill(SNAPS[snap].first, caret = true) { snap = (snap + 1) % SNAPS.size }
            Spacer(Modifier.width(6.dp))
            Pill("Scale", on = scaleOn, color = color) {
                val keep = rowsNow.getOrElse(low) { 60 }
                scaleOn = !scaleOn
                low = -2 - keep   // re-found when drawn, on the same note, once the rows change
            }
            Spacer(Modifier.weight(1f))
            if (selected >= 0) {
                RoundIcon("✕") { Seq.edit("clipDelete", "id" to selected); selected = -1 }
                Spacer(Modifier.width(6.dp))
            }
            RoundIcon("↶", seq.canUndo) { Seq.edit("undo") }
            Spacer(Modifier.width(6.dp))
            RoundIcon("↷", seq.canRedo) { Seq.edit("redo") }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                // keyed only on the geometry: scrolling (low), the tool and the snap are read live, so a scroll never restarts the gesture
                .pointerInput(stepT, barTicks, bar) {
                    val keysW = 40.dp.toPx()
                    val rulerH = 16.dp.toPx()
                    val rowH = 24.dp.toPx()
                    val rows = ((size.height - rulerH) / rowH).toInt().coerceAtLeast(1)
                    val tickW = (size.width - keysW) / barTicks
                    fun rowAtY(y: Float) = low + rows - 1 - ((y - rulerH) / rowH).toInt()
                    fun noteAtY(y: Float) = rowsNow[rowAtY(y).coerceIn(0, rowsNow.size - 1)]
                    fun tickAtX(x: Float) = barStart + ((x - keysW) / tickW).toInt()
                    fun hit(notes: List<ClipNote>, x: Float, y: Float): Pair<ClipNote, Boolean>? {
                        val n = noteAtY(y)
                        val t = tickAtX(x)
                        val found = notes.lastOrNull { rowOf(it.note) == rowOf(n) && t >= it.start && t < it.start + it.length } ?: return null
                        val right = keysW + (found.start + found.length - barStart) * tickW
                        // the end handle: the note's last third, at most 14 dp (a short note is mostly for moving)
                        return found to (right - x < minOf(14.dp.toPx(), found.length * tickW / 3f))
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val x0 = down.position.x
                        val y0 = down.position.y
                        if (x0 < keysW) {   // the keys: scroll, or audition on a tap
                            var moved = 0f
                            val startLow = low
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.first()
                                if (!c.pressed) break
                                c.consume()
                                moved = c.position.y - y0
                                low = (startLow + (moved / rowH).roundToInt()).coerceIn(0, maxOf(0, rowsNow.size - rows))
                            }
                            if (abs(moved) < 8f) {
                                val n = noteAtY(y0)
                                Native.noteOn(track, n, 0.8f); Native.noteOff(track, n)
                            }
                            return@awaitEachGesture
                        }
                        var notes = Seq.data.value.rollNotes
                        var h = hit(notes, x0, y0)
                        if (h != null && !Seq.data.value.pianoRoll) {   // a step track: becomes a clip before its notes change
                            Seq.edit("toRoll")
                            notes = Seq.data.value.rollNotes
                            h = hit(notes, x0, y0)
                        }
                        var last = down.position
                        var dragged = false
                        if (h == null) {
                            if (tool == 1) { selected = -1; return@awaitEachGesture }
                            val start = snapTo(tickAtX(x0)).coerceIn(barStart, barStart + barTicks - 1)
                            val len0 = if (lastLength > 0) lastLength else snapTicks().coerceAtLeast(stepT)
                            preview = Preview(-1, start, len0, noteAtY(y0).coerceIn(0, 127), 100)
                            Native.noteOn(track, noteAtY(y0), 0.8f)
                        } else {
                            val (n, edge) = h
                            preview = Preview(n.id, n.start, n.length, n.note, n.velocity)
                            selected = n.id
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.first()
                                if (!c.pressed) break
                                c.consume()
                                last = c.position
                                if (abs(last.x - x0) > 10f || abs(last.y - y0) > 10f) dragged = true
                                if (!dragged) continue
                                val dt = ((last.x - x0) / tickW).toInt()
                                preview = if (edge) Preview(n.id, n.start, maxOf(snapTicks(), snapTo(n.length + dt)), n.note, n.velocity)
                                else Preview(n.id, snapTo(n.start + dt).coerceAtLeast(0), n.length,
                                    rowsNow[(rowOf(n.note) + rowAtY(last.y) - rowAtY(y0)).coerceIn(0, rowsNow.size - 1)], n.velocity)
                            }
                            val p = preview!!
                            preview = null
                            when {
                                dragged -> {
                                    Seq.edit("clipEdit", "id" to p.id, "start" to p.start, "length" to p.length, "note" to p.note)
                                    if (edge) lastLength = p.length
                                }
                                tool == 0 -> { Seq.edit("clipDelete", "id" to n.id); selected = -1 }
                            }
                            return@awaitEachGesture
                        }
                        // drawing a new note: drag sets its length
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.first()
                            if (!c.pressed) break
                            c.consume()
                            val p = preview!!
                            val end = snapTo(tickAtX(c.position.x))
                            preview = p.copy(length = maxOf(if (lastLength > 0 && end <= p.start) lastLength else snapTicks(), end - p.start))
                        }
                        val p = preview!!
                        preview = null
                        Native.noteOff(track, p.note)
                        val id = Seq.edit("clipAdd", "start" to p.start, "length" to p.length, "note" to p.note, "velocity" to p.velocity)
                        selected = id.trim().toIntOrNull() ?: -1
                        lastLength = p.length
                    }
                },
        ) {
            val keysW = 40.dp.toPx()
            val rulerH = 16.dp.toPx()
            val rowH = 24.dp.toPx()
            val rows = ((size.height - rulerH) / rowH).toInt().coerceAtLeast(1)
            val tickW = (size.width - keysW) / barTicks
            keyLabel.textSize = 10.dp.toPx(); rulerLabel.textSize = 10.dp.toPx()
            for (s in 0 until seq.stepsPerBar step 4)   // the ruler: bar.beat
                drawContext.canvas.nativeCanvas.drawText("${bar + 1}.${s / 4 + 1}", keysW + s * stepT * tickW + 3.dp.toPx(), 11.dp.toPx(), rulerLabel)
            drawRect(C.surface, Offset(keysW, rulerH), Size(size.width - keysW, rows * rowH))
            if (low <= -2) low = rowOf(-2 - low)   // the Scale toggle: the same note stays in view
            low = low.coerceIn(0, maxOf(0, rowNotes.size - rows))
            for (r in 0 until rows) {
                val idx = low + rows - 1 - r
                if (idx >= rowNotes.size) continue
                val n = rowNotes[idx]
                val y = rulerH + r * rowH
                val root = ((n - project.scaleRoot) % 12 + 12) % 12 == 0
                if (scaleOn) {
                    // in key: every row named, the roots in the track colour
                    if (root) drawRect(color.copy(alpha = 0.08f), Offset(keysW, y), Size(size.width - keysW, rowH))
                    drawRoundRect(if (root) color else C.control, Offset(0f, y + 0.5f), Size(keysW - 2f, rowH - 1f), CornerRadius(3.dp.toPx()))
                    keyLabel.color = if (root) Tokens.BG else Tokens.QUIET
                    drawContext.canvas.nativeCanvas.drawText(noteName(n), keysW - 5.dp.toPx(), y + rowH / 2 + 4.dp.toPx(), keyLabel)
                } else {
                    val black = (n % 12) in listOf(1, 3, 6, 8, 10)
                    if (black) drawRect(Color(0xFF1B1D22), Offset(keysW, y), Size(size.width - keysW, rowH))
                    drawRoundRect(if (black) Color(0xFF2E323A) else Color(0xFFC9C4BE), Offset(0f, y + 0.5f), Size(keysW - 2f, rowH - 1f), CornerRadius(3.dp.toPx()))
                    keyLabel.color = Tokens.BG
                    if (n % 12 == 0) drawContext.canvas.nativeCanvas.drawText(noteName(n), keysW - 5.dp.toPx(), y + rowH / 2 + 4.dp.toPx(), keyLabel)
                }
                drawLine(C.divider, Offset(keysW, y + rowH), Offset(size.width, y + rowH), 0.6f)
            }
            for (s in 0..seq.stepsPerBar) {
                val x = keysW + s * stepT * tickW
                drawLine(if (s % 4 == 0) C.faint else C.divider, Offset(x, rulerH), Offset(x, rulerH + rows * rowH), if (s % 4 == 0) 1.2f else 0.6f)
            }
            val notes = seq.rollNotes.filter { (preview == null || it.id != preview!!.id || it.id < 0) }
            fun drawNote(start: Int, length: Int, note: Int, vel: Int, sel: Boolean) {
                val r = low + rows - 1 - rowOf(note)
                if (r < 0 || r >= rows) return
                val offScale = rowNotes.getOrNull(rowOf(note)) != note   // kept as it was, shown on the row below
                val x = keysW + (start - barStart) * tickW
                val w = length * tickW
                if (x + w < keysW || x > size.width) return
                val y = rulerH + r * rowH
                drawRoundRect(color.copy(alpha = if (sel) 1f else if (offScale) 0.3f else 0.45f + 0.45f * vel / 127f), Offset(maxOf(x, keysW) + 1f, y + 2f),
                    Size(minOf(w, size.width - x) - 2f, rowH - 4f), CornerRadius(4.dp.toPx()))
                if (sel) drawRoundRect(C.text, Offset(x - 1f, y + 0.5f), Size(w + 2f, rowH - 1f), CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
            notes.forEach { drawNote(it.start, it.length, it.note, it.velocity, it.id == selected && it.id >= 0) }
            preview?.let { drawNote(it.start, it.length, it.note, it.velocity, true) }
            if (fast.playing && fast.activePattern == fast.focusPattern) {
                val x = keysW + ((fast.step - bar * seq.stepsPerBar) * stepT) * tickW
                if (x >= keysW && x <= size.width) drawLine(C.pattern, Offset(x, 0f), Offset(x, rulerH + rows * rowH), 2.dp.toPx())
            }
        }
        Spacer(Modifier.height(8.dp))
        VelocityLane(bar, barStart, barTicks, color, selected)
    }
}

/** The notes' velocities as stalks; drag one up or down (the selected note, else the nearest). */
@Composable
private fun VelocityLane(bar: Int, barStart: Int, barTicks: Int, color: Color, selected: Int) {
    val seq by Seq.data
    var drag by remember { mutableStateOf<Pair<Int, Int>?>(null) }   // id → velocity while dragging
    Canvas(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(C.surface)
            .pointerInput(seq, bar, selected) {
                val keysW = 40.dp.toPx()
                val tickW = (size.width - keysW) / barTicks
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val notes = Seq.data.value.clip.filter { it.start >= barStart && it.start < barStart + barTicks }
                    val target = notes.firstOrNull { it.id == selected }
                        ?: notes.minByOrNull { abs(keysW + (it.start - barStart) * tickW - down.position.x) } ?: return@awaitEachGesture
                    fun velAt(y: Float) = (127 * (1 - (y - 6f) / (size.height - 12f))).roundToInt().coerceIn(1, 127)
                    drag = target.id to velAt(down.position.y)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.first()
                        if (!c.pressed) break
                        c.consume()
                        drag = target.id to velAt(c.position.y)
                    }
                    val d = drag
                    drag = null
                    if (d != null) Seq.edit("clipEdit", "id" to d.first, "velocity" to d.second)
                }
            },
    ) {
        val keysW = 40.dp.toPx()
        val tickW = (size.width - keysW) / barTicks
        seq.rollNotes.filter { it.start >= barStart && it.start < barStart + barTicks }.forEach { n ->
            val v = if (drag?.first == n.id) drag!!.second else n.velocity
            val x = keysW + (n.start - barStart) * tickW + 2f
            val top = 6f + (size.height - 12f) * (1 - v / 127f)
            val on = n.id == selected || drag?.first == n.id
            drawLine(if (on) color else C.quiet.copy(alpha = 0.6f), Offset(x, size.height - 6f), Offset(x, top), 2.dp.toPx())
            drawCircle(if (on) color else C.quiet, 3.5.dp.toPx(), Offset(x, top))
        }
    }
}

@Composable
private fun RoundIcon(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(C.control).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) C.quiet else C.faint, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}
