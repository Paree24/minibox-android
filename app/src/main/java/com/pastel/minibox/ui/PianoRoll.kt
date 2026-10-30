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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pastel.minibox.engine.Native
import com.pastel.minibox.state.App
import com.pastel.minibox.state.ClipNote
import com.pastel.minibox.state.Seq
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val SNAPS = listOf("1/32" to 0.5f, "1/16" to 1f, "1/8" to 2f, "1/4" to 4f, "Off" to 0f)
private val ZOOMS = listOf(1, 2, 4, 0)   // bars in view; 0 = the whole pattern

/** A note being drawn, moved or resized, before the finger lifts (one undo step per gesture). */
private data class Preview(val id: Int, val start: Int, val length: Int, val note: Int, val velocity: Int)

/** Writes several clip notes in one undo step. */
private fun editMany(notes: List<Preview>) {
    val arr = JSONArray()
    notes.forEach { arr.put(JSONObject().put("id", it.id).put("start", it.start).put("length", it.length).put("note", it.note)) }
    Seq.edit("clipEditMany", "notes" to arr)
}

/**
 * The piano roll panel (mockups 15–16): keys, a ruler, free-length notes across bar lines, Draw / Select, snap,
 * zoom (1, 2, 4 bars or the whole pattern), a velocity lane. Scale on (the default): the rows are the project scale.
 * Draw: tap an empty place for a note (drag to set its length, across bars), tap a note to delete it, drag a note to
 * move it. Select: tap notes to add or remove them, drag on empty space to box them, tap empty space to clear; drag a
 * selected note to move every selected one, drag its end to resize them. Drag the keys to scroll; tap a key to hear it.
 */
@Composable
fun PianoRoll(bar: Int, color: Color) {
    val seq by Seq.data
    val fast by App.fast
    var tool by remember { mutableIntStateOf(0) }
    var snap by remember { mutableIntStateOf(1) }
    var zoom by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var previews by remember { mutableStateOf<List<Preview>>(emptyList()) }
    var box by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var lastLength by remember { mutableIntStateOf(0) }
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
    // the window: from the bar in view, `zoom` bars wide (the whole pattern at 0), never past the pattern's end
    val zoomBars = ZOOMS[zoom].let { if (it == 0) seq.bars else min(it, seq.bars) }
    val viewStart = (if (ZOOMS[zoom] == 0) 0 else bar.coerceAtMost(max(0, seq.bars - zoomBars))) * barTicks
    val viewTicks = zoomBars * barTicks
    val keyLabel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Tokens.BG; textAlign = android.graphics.Paint.Align.RIGHT; typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    val rulerLabel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Tokens.FAINT; typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    fun snapTicks() = (SNAPS[snap].second * stepT).roundToInt().coerceAtLeast(1)
    fun snapTo(t: Int) = if (SNAPS[snap].second == 0f) t else (t.toFloat() / snapTicks()).roundToInt() * snapTicks()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("Draw", "Select"), tool, C.text, { tool = it }, Modifier.width(124.dp), height = 32.dp)
            Spacer(Modifier.width(6.dp))
            Pill(SNAPS[snap].first, caret = true) { snap = (snap + 1) % SNAPS.size }
            Spacer(Modifier.width(6.dp))
            Pill(if (ZOOMS[zoom] == 0) "All" else "${ZOOMS[zoom]} bar", caret = true) { zoom = (zoom + 1) % ZOOMS.size }
            Spacer(Modifier.width(6.dp))
            Pill("Scale", on = scaleOn, color = color) {
                val keep = rowsNow.getOrElse(low) { 60 }
                scaleOn = !scaleOn
                low = -2 - keep   // re-found when drawn, on the same note, once the rows change
            }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                // keyed only on the geometry: scrolling (low), the tool, the snap and the selection are read live
                .pointerInput(stepT, viewStart, viewTicks) {
                    val keysW = 40.dp.toPx()
                    val rulerH = 16.dp.toPx()
                    val rowH = 24.dp.toPx()
                    val rows = ((size.height - rulerH) / rowH).toInt().coerceAtLeast(1)
                    val tickW = (size.width - keysW) / viewTicks
                    fun rowAtY(y: Float) = low + rows - 1 - ((y - rulerH) / rowH).toInt()
                    fun noteAtY(y: Float) = rowsNow[rowAtY(y).coerceIn(0, rowsNow.size - 1)]
                    fun tickAtX(x: Float) = viewStart + ((x - keysW) / tickW).toInt()
                    fun hit(notes: List<ClipNote>, x: Float, y: Float): Pair<ClipNote, Boolean>? {
                        val n = noteAtY(y)
                        val t = tickAtX(x)
                        val found = notes.lastOrNull { rowOf(it.note) == rowOf(n) && t >= it.start && t < it.start + it.length } ?: return null
                        val right = keysW + (found.start + found.length - viewStart) * tickW
                        // the end handle: the note's last third, at most 14 dp (a short note is mostly for moving)
                        return found to (right - x < min(14.dp.toPx(), found.length * tickW / 3f))
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
                                low = (startLow + (moved / rowH).roundToInt()).coerceIn(0, max(0, rowsNow.size - rows))
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
                        var dragged = false
                        // follows the finger until it lifts; true when it moved past the touch slop
                        suspend fun AwaitPointerEventScope.follow(onMove: (Offset) -> Unit): Boolean {
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.first()
                                if (!c.pressed) break
                                c.consume()
                                if (abs(c.position.x - x0) > 10f || abs(c.position.y - y0) > 10f) dragged = true
                                if (dragged) onMove(c.position)
                            }
                            return dragged
                        }
                        if (h == null) {
                            if (tool == 1) {   // Select on empty space: a box, or a tap that clears
                                if (follow { box = Offset(x0, y0) to it }) {
                                    val (a, b) = box!!
                                    val t0 = tickAtX(min(a.x, b.x)); val t1 = tickAtX(max(a.x, b.x))
                                    val r0 = rowAtY(max(a.y, b.y)); val r1 = rowAtY(min(a.y, b.y))
                                    selected = Seq.data.value.clip.filter { it.start < t1 && it.start + it.length > t0 && rowOf(it.note) in r0..r1 }
                                        .map { it.id }.toSet()
                                } else selected = emptySet()
                                box = null
                                return@awaitEachGesture
                            }
                            // Draw: a new note; dragging sets its length, across bar lines
                            val start = snapTo(tickAtX(x0)).coerceIn(viewStart, viewStart + viewTicks - 1)
                            val len0 = if (lastLength > 0) lastLength else snapTicks().coerceAtLeast(stepT)
                            val note = noteAtY(y0).coerceIn(0, 127)
                            previews = listOf(Preview(-1, start, len0, note, 100))
                            Native.noteOn(track, note, 0.8f)
                            follow { p ->
                                val end = snapTo(tickAtX(p.x))
                                previews = listOf(Preview(-1, start, max(snapTicks(), end - start), note, 100))
                            }
                            val p = previews.first()
                            previews = emptyList()
                            Native.noteOff(track, p.note)
                            val id = Seq.edit("clipAdd", "start" to p.start, "length" to p.length, "note" to p.note, "velocity" to p.velocity)
                            selected = id.trim().toIntOrNull()?.let { setOf(it) } ?: emptySet()
                            lastLength = p.length
                            return@awaitEachGesture
                        }
                        val (n, edge) = h
                        // the notes this gesture changes: the selection when the note is in it (Select), else the note
                        val group = if (tool == 1 && n.id in selected) Seq.data.value.clip.filter { it.id in selected } else listOf(n)
                        val moved = follow { p ->
                            val dt = ((p.x - x0) / tickW).roundToInt()
                            val dr = rowAtY(p.y) - rowAtY(y0)
                            previews = group.map { g ->
                                if (edge) Preview(g.id, g.start, max(snapTicks(), snapTo(g.length + dt)), g.note, g.velocity)
                                else Preview(g.id, (g.start + snapTo(n.start + dt) - n.start).coerceAtLeast(0), g.length,
                                    rowsNow[(rowOf(g.note) + dr).coerceIn(0, rowsNow.size - 1)], g.velocity)
                            }
                        }
                        val out = previews
                        previews = emptyList()
                        when {
                            moved -> {
                                editMany(out)
                                if (edge) lastLength = out.first().length
                                if (tool == 0) selected = setOf(n.id)
                            }
                            tool == 0 -> { Seq.edit("clipDelete", "id" to n.id); selected = selected - n.id }
                            else -> selected = if (n.id in selected) selected - n.id else selected + n.id   // multi-tap
                        }
                    }
                },
        ) {
            val keysW = 40.dp.toPx()
            val rulerH = 16.dp.toPx()
            val rowH = 24.dp.toPx()
            val rows = ((size.height - rulerH) / rowH).toInt().coerceAtLeast(1)
            val tickW = (size.width - keysW) / viewTicks
            keyLabel.textSize = 10.dp.toPx(); rulerLabel.textSize = 10.dp.toPx()
            val beatTicks = stepT * 4
            for (t in 0 until viewTicks step beatTicks) {   // the ruler: bar.beat
                val at = viewStart + t
                val b = at / barTicks
                val beat = (at % barTicks) / beatTicks
                if (zoomBars <= 2 || beat == 0)
                    drawContext.canvas.nativeCanvas.drawText("${b + 1}.${beat + 1}", keysW + t * tickW + 3.dp.toPx(), 11.dp.toPx(), rulerLabel)
            }
            drawRect(C.surface, Offset(keysW, rulerH), Size(size.width - keysW, rows * rowH))
            if (low <= -2) low = rowOf(-2 - low)   // the Scale toggle: the same note stays in view
            low = low.coerceIn(0, max(0, rowNotes.size - rows))
            for (r in 0 until rows) {
                val idx = low + rows - 1 - r
                if (idx >= rowNotes.size) continue
                val n = rowNotes[idx]
                val y = rulerH + r * rowH
                val root = ((n - project.scaleRoot) % 12 + 12) % 12 == 0
                if (scaleOn) {
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
            // grid: bars bright, beats faint, steps only when they are wide enough to see
            val stepLines = stepT * tickW >= 6.dp.toPx()
            for (t in 0..viewTicks step stepT) {
                val at = viewStart + t
                val x = keysW + t * tickW
                val isBar = at % barTicks == 0
                val isBeat = at % beatTicks == 0
                if (!isBar && !isBeat && !stepLines) continue
                drawLine(if (isBar) C.quiet else if (isBeat) C.faint else C.divider, Offset(x, rulerH), Offset(x, rulerH + rows * rowH),
                    if (isBar) 1.6f else if (isBeat) 1.0f else 0.6f, alpha = if (isBar) 0.7f else 1f)
            }
            val moving = previews.map { it.id }.toSet()
            fun drawNote(start: Int, length: Int, note: Int, vel: Int, flags: Int, sel: Boolean) {
                val r = low + rows - 1 - rowOf(note)
                if (r < 0 || r >= rows) return
                val x = keysW + (start - viewStart) * tickW
                val w = length * tickW
                if (x + w < keysW || x > size.width) return
                val y = rulerH + r * rowH
                val offScale = rowNotes.getOrNull(rowOf(note)) != note
                val left = max(x, keysW)
                drawRoundRect(color.copy(alpha = if (sel) 1f else if (offScale) 0.3f else 0.45f + 0.45f * vel / 127f), Offset(left + 1f, y + 2f),
                    Size(min(x + w, size.width) - left - 2f, rowH - 4f), CornerRadius(4.dp.toPx()))
                if (sel) drawRoundRect(C.text, Offset(left - 1f, y + 0.5f), Size(min(x + w, size.width) - left + 2f, rowH - 1f),
                    CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
                if (flags and 1 != 0) drawCircle(C.bg, 2.5.dp.toPx(), Offset(left + 6.dp.toPx(), y + rowH / 2))   // accent
                if (flags and 2 != 0) drawLine(C.bg, Offset(x + w - 9.dp.toPx(), y + rowH - 6.dp.toPx()), Offset(x + w - 3.dp.toPx(), y + 6.dp.toPx()), 2.dp.toPx())   // slide
            }
            seq.rollNotes.filter { it.id < 0 || it.id !in moving }
                .forEach { drawNote(it.start, it.length, it.note, it.velocity, it.flags, it.id >= 0 && it.id in selected) }
            previews.forEach { drawNote(it.start, it.length, it.note, it.velocity, 0, true) }
            box?.let { (a, b) ->
                drawRect(C.text.copy(alpha = 0.08f), Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)))
                drawRect(C.text, Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)), style = Stroke(1.dp.toPx()))
            }
            if (fast.playing && fast.activePattern == fast.focusPattern) {
                val x = keysW + (fast.step * stepT - viewStart) * tickW
                if (x >= keysW && x <= size.width) drawLine(C.pattern, Offset(x, 0f), Offset(x, rulerH + rows * rowH), 2.dp.toPx())
            }
        }
        Spacer(Modifier.height(8.dp))
        // the selection: accent, slide (the Acid glides from it into the next note), delete, on every selected note
        val sel = seq.clip.filter { it.id in selected }
        if (sel.isNotEmpty()) {
            val acc = sel.all { it.flags and 1 != 0 }
            val sld = sel.all { it.flags and 2 != 0 }
            fun setFlag(bit: Int, on: Boolean) {
                val arr = JSONArray()
                sel.forEach { arr.put(JSONObject().put("id", it.id).put("flags", if (on) it.flags or bit else it.flags and bit.inv())) }
                Seq.edit("clipEditMany", "notes" to arr)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (sel.size == 1) noteName(sel.first().note) else "${sel.size} notes", color = color, fontSize = 13.sp,
                    fontWeight = FontWeight.Black, modifier = Modifier.width(64.dp))
                Pill("Accent", on = acc, color = color) { setFlag(1, !acc) }
                Spacer(Modifier.width(6.dp))
                Pill("Slide", on = sld, color = color) { setFlag(2, !sld) }
                Spacer(Modifier.weight(1f))
                RoundIcon("✕") {
                    val arr = JSONArray(); sel.forEach { arr.put(JSONObject().put("id", it.id)) }
                    Seq.edit("clipDeleteMany", "notes" to arr)
                    selected = emptySet()
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        VelocityLane(viewStart, viewTicks, color, selected)
    }
}

/** The notes' velocities as stalks; drag one up or down. A selected note moves every selected note's velocity. */
@Composable
private fun VelocityLane(viewStart: Int, viewTicks: Int, color: Color, selected: Set<Int>) {
    val seq by Seq.data
    val sel by rememberUpdatedState(selected)
    var drag by remember { mutableStateOf<Pair<Set<Int>, Int>?>(null) }   // ids → velocity while dragging
    Canvas(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(C.surface)
            .pointerInput(viewStart, viewTicks) {
                val keysW = 40.dp.toPx()
                val tickW = (size.width - keysW) / viewTicks
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val notes = Seq.data.value.clip.filter { it.start >= viewStart && it.start < viewStart + viewTicks }
                    val nearest = notes.minByOrNull { abs(keysW + (it.start - viewStart) * tickW - down.position.x) } ?: return@awaitEachGesture
                    val ids = if (nearest.id in sel) sel else setOf(nearest.id)
                    fun velAt(y: Float) = (127 * (1 - (y - 6f) / (size.height - 12f))).roundToInt().coerceIn(1, 127)
                    drag = ids to velAt(down.position.y)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.first()
                        if (!c.pressed) break
                        c.consume()
                        drag = ids to velAt(c.position.y)
                    }
                    val d = drag
                    drag = null
                    if (d != null) {
                        val arr = JSONArray()
                        d.first.forEach { arr.put(JSONObject().put("id", it).put("velocity", d.second)) }
                        Seq.edit("clipEditMany", "notes" to arr)
                    }
                }
            },
    ) {
        val keysW = 40.dp.toPx()
        val tickW = (size.width - keysW) / viewTicks
        seq.rollNotes.filter { it.start >= viewStart && it.start < viewStart + viewTicks }.forEach { n ->
            val v = if (drag?.first?.contains(n.id) == true) drag!!.second else n.velocity
            val x = keysW + (n.start - viewStart) * tickW + 2f
            val top = 6f + (size.height - 12f) * (1 - v / 127f)
            val on = n.id in selected || drag?.first?.contains(n.id) == true
            drawLine(if (on) color else C.quiet.copy(alpha = 0.6f), Offset(x, size.height - 6f), Offset(x, top), 2.dp.toPx())
            drawCircle(if (on) color else C.quiet, 3.5.dp.toPx(), Offset(x, top))
        }
    }
}

@Composable
fun RoundIcon(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(C.control).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) C.quiet else C.faint, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}
