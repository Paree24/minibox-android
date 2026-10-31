package com.pastel.minibox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.PointerEvent
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Room after the end of the view: a note's end (its resize handle) never sits in Android's edge-swipe zone, where
    dragging it would be taken as the system's Back gesture. The velocity lanes use the same so they line up. */
private val END_PAD = 32.dp

private val SNAPS = listOf("1/32" to 0.5f, "1/16" to 1f, "1/8" to 2f, "1/4" to 4f, "Off" to 0f)

/** A note being drawn, moved or resized, before the finger lifts (one undo step per gesture). */
private data class Preview(val id: Int, val start: Int, val length: Int, val note: Int, val velocity: Int)

/** Writes several clip notes in one undo step. */
private fun editMany(notes: List<Preview>) {
    val arr = JSONArray()
    notes.forEach { arr.put(JSONObject().put("id", it.id).put("start", it.start).put("length", it.length).put("note", it.note)) }
    Seq.edit("clipEditMany", "notes" to arr)
}

private val handler = android.os.Handler(android.os.Looper.getMainLooper())

/** A short preview of a note (a key tapped, a note drawn): the release comes a moment later. A note-off in the same
    instant as its note-on is put first by the engine (as at a loop point), which left the note ringing. */
private fun audition(track: Int, note: Int) {
    Native.noteOn(track, note, 0.8f)
    handler.postDelayed({ Native.noteOff(track, note) }, 250)
}

/** One of the roll's edits on the selection (every note when none is selected): clipOp, then the new selection. */
private fun rollOp(fn: String, selected: Set<Int>, vararg args: Pair<String, Any?>): Set<Int>? {
    val ids = JSONArray(); selected.forEach { ids.put(it) }
    val r = Seq.edit("clipOp", "fn" to fn, "ids" to ids, *args)
    return runCatching { JSONArray(r) }.getOrNull()?.let { a -> List(a.length()) { a.getInt(it) }.toSet() }
}

private fun deleteMany(ids: Collection<Int>) {
    if (ids.isEmpty()) return
    val arr = JSONArray(); ids.forEach { arr.put(JSONObject().put("id", it)) }
    Seq.edit("clipDeleteMany", "notes" to arr)
}

/** What the roll shows: from `start` (ticks), `ticks` wide; rows of the scale from `low` (a row index, may be
    fractional while scrolling), `rows` high. Shared by the Notes and Velocity tabs. */
private class RollView {
    var start by mutableFloatStateOf(0f)
    var ticks by mutableFloatStateOf(0f)
    var low by mutableFloatStateOf(-1f)
    var rows by mutableFloatStateOf(8f)   // one octave of the scale
}

/**
 * The piano roll (mockups 15–16), the whole Seq page: Notes, Velocity (with accent and slide) and Tools tabs.
 * Rows are the project scale (an octave in view; drag the keys or use two fingers to see more). The view never
 * moves by itself.
 * Notes: one finger edits, two fingers pinch (horizontally: time, vertically: rows) and pan; drag the ruler to
 * scroll time. Draw: tap empty space for a note (drag for its length), tap a note to select it, drag it to move,
 * drag its end to resize. Select: tap notes to add / remove, drag on empty space to box them, drag selected notes
 * to move them together. Erase: tap or swipe over notes to delete them (it never adds).
 */
@Composable
fun PianoRoll(color: Color) {
    val seq by Seq.data
    val fast by App.fast
    val project by App.project
    val track = App.focusTrack.intValue
    var tab by remember { mutableIntStateOf(0) }
    var tool by remember { mutableIntStateOf(0) }
    var snap by remember { mutableIntStateOf(1) }
    var selected by remember(track) { mutableStateOf(setOf<Int>()) }
    var previews by remember { mutableStateOf<List<Preview>>(emptyList()) }
    var hidden by remember { mutableStateOf(setOf<Int>()) }   // being erased by a swipe
    var box by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var lastLength by remember { mutableIntStateOf(0) }
    val view = remember(track) { RollView() }
    val rowNotes = remember(project.scaleRoot, project.scaleIntervals) {
        (0..127).filter { ((it - project.scaleRoot) % 12 + 12) % 12 in project.scaleIntervals }.ifEmpty { (0..127).toList() }
    }
    val stepT = seq.stepTicks
    val barTicks = seq.stepsPerBar * stepT
    val totalTicks = (seq.bars * barTicks).coerceAtLeast(1)
    fun rowOf(note: Int): Int = rowNotes.indexOfLast { it <= note }.coerceAtLeast(0)
    // the first view: a bar, an octave around the notes (or the last pad played)
    if (view.ticks <= 0f) view.ticks = min(barTicks, totalTicks).toFloat()
    if (view.low < 0f) view.low = (rowOf(seq.rollNotes.minOfOrNull { it.note } ?: App.lastNote.intValue) - 1).coerceAtLeast(0).toFloat()
    view.ticks = view.ticks.coerceIn(min(stepT * 2, totalTicks).toFloat(), totalTicks.toFloat())
    view.start = view.start.coerceIn(0f, max(0f, totalTicks - view.ticks))
    view.rows = view.rows.coerceIn(4f, 36f)
    view.low = view.low.coerceIn(0f, max(0f, rowNotes.size - view.rows))
    val rowsNow by rememberUpdatedState(rowNotes)
    val toolNow by rememberUpdatedState(tool)
    val totalNow by rememberUpdatedState(totalTicks)
    val stepNow by rememberUpdatedState(stepT)
    val selNow by rememberUpdatedState(selected)
    fun snapTicks() = (SNAPS[snap].second * stepT).roundToInt().coerceAtLeast(1)
    fun snapTo(t: Int) = if (SNAPS[snap].second == 0f) t else (t.toFloat() / snapTicks()).roundToInt() * snapTicks()
    val keyLabel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.RIGHT; typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    val rulerLabel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Tokens.FAINT; typeface = android.graphics.Typeface.DEFAULT_BOLD } }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("Notes", "Velocity"), tab, C.text, { tab = it }, Modifier.weight(1f), height = 32.dp)
            Spacer(Modifier.width(6.dp))
            Pill(SNAPS[snap].first, caret = true) { snap = (snap + 1) % SNAPS.size }
        }
        Spacer(Modifier.height(6.dp))
        when (tab) {
            1 -> VelocityPanel(Modifier.weight(1f).fillMaxWidth(), view, color, selected)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // the buttons never move: delete and deselect are always there (dim with nothing selected)
                    Segmented(listOf("Draw", "Select", "Erase"), tool, C.text, { tool = it }, Modifier.weight(1f), height = 32.dp)
                    Spacer(Modifier.width(6.dp))
                    // what the edits below act on
                    val sel = seq.clip.filter { it.id in selected }
                    Text(when { sel.isEmpty() -> "All"; sel.size == 1 -> noteName(sel.first().note); else -> "${sel.size} notes" },
                        color = if (sel.isEmpty()) C.faint else color, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1,
                        modifier = Modifier.width(54.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    RoundIcon("⌫", selected.isNotEmpty()) { deleteMany(selected); selected = emptySet() }
                    Spacer(Modifier.width(4.dp))
                    RoundIcon("✕", selected.isNotEmpty()) { selected = emptySet() }
                }
                Spacer(Modifier.height(6.dp))
                // the everyday edits, on the selection (or every note): in key, octaves, the clipboard, quantize
                val grid = if (SNAPS[snap].second == 0f) stepT else snapTicks()
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf<Pair<String, () -> Unit>>(
                        "↓" to { rollOp("transpose", selected, "dir" to -1, "scale" to true)?.let { selected = it } },
                        "↑" to { rollOp("transpose", selected, "dir" to 1, "scale" to true)?.let { selected = it } },
                        "8↓" to { rollOp("transpose", selected, "dir" to -12)?.let { selected = it } },
                        "8↑" to { rollOp("transpose", selected, "dir" to 12)?.let { selected = it } },
                        "Cut" to { if (selected.isNotEmpty()) { rollOp("cut", selected); selected = emptySet() } },
                        "Copy" to { if (selected.isNotEmpty()) rollOp("copy", selected) },
                        "Paste" to { rollOp("paste", selected, "grid" to grid, "at" to view.start.toInt())?.let { selected = it } },
                        "Q" to { rollOp("quantize", selected, "grid" to grid)?.let { selected = it } },
                    ).forEach { (label, action) ->
                        Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(9.dp)).background(C.control).clickable(onClick = action),
                            contentAlignment = Alignment.Center) {
                            Text(label, color = C.text, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Canvas(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        // one handler for every gesture: the view state is read live, so nothing restarts mid-touch
                        .pointerInput(Unit) {
                            val keysW = 40.dp.toPx()
                            val rulerH = 18.dp.toPx()
                            val slop = 10.dp.toPx()
                            val reach = 12.dp.toPx()   // a tap this close to a note counts as on it
                            fun gridH() = size.height - rulerH
                            fun rowH() = gridH() / view.rows
                            fun tickW() = (size.width - keysW - END_PAD.toPx()) / view.ticks
                            fun rowAtY(y: Float) = floor(view.low + (size.height - y) / rowH()).toInt()
                            fun noteAtY(y: Float) = rowsNow[rowAtY(y).coerceIn(0, rowsNow.size - 1)]
                            fun tickAtX(x: Float) = (view.start + (x - keysW) / tickW()).toInt()
                            fun xOf(t: Int) = keysW + (t - view.start) * tickW()
                            fun rowIdx(n: Int) = rowsNow.indexOfLast { it <= n }.coerceAtLeast(0)
                            /** The note under (x, y) or nearest to it in that row (within `reach`); and whether on its end handle. */
                            fun hit(notes: List<ClipNote>, x: Float, y: Float): Pair<ClipNote, Boolean>? {
                                val r = rowAtY(y)
                                val found = notes.filter { rowIdx(it.note) == r }.map { n ->
                                    val l = xOf(n.start); val rt = xOf(n.start + n.length)
                                    n to (if (x < l) l - x else if (x > rt) x - rt else 0f)
                                }.filter { it.second <= reach }.minByOrNull { it.second }?.first ?: return null
                                val right = xOf(found.start + found.length)
                                return found to (x <= right + reach && right - x < min(14.dp.toPx(), found.length * tickW() / 3f))
                            }
                            fun ensureClip(): List<ClipNote> {
                                if (!Seq.data.value.pianoRoll) Seq.edit("toRoll")
                                return Seq.data.value.clip
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                val x0 = down.position.x
                                val y0 = down.position.y
                                var moved = false
                                var pinched = false
                                // two fingers: pinch and pan; called with every event while two are down
                                var last: List<Offset>? = null
                                fun pinch(e: PointerEvent): Boolean {
                                    val pts = e.changes.filter { it.pressed }.map { it.position }
                                    if (pts.size < 2) { last = null; return false }
                                    pinched = true
                                    previews = emptyList(); box = null; hidden = emptySet()
                                    val a = pts[0]; val b = pts[1]
                                    val l = last
                                    if (l != null) {
                                        val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2
                                        val px = (l[0].x + l[1].x) / 2; val py = (l[0].y + l[1].y) / 2
                                        val sx = abs(a.x - b.x); val psx = abs(l[0].x - l[1].x)
                                        val sy = abs(a.y - b.y); val psy = abs(l[0].y - l[1].y)
                                        // time: zoom around the fingers, then follow them
                                        val tAt = view.start + (cx - keysW) / tickW()
                                        if (sx > 60f && psx > 60f) view.ticks = (view.ticks * psx / sx).coerceIn(min(stepNow * 2, totalNow).toFloat(), totalNow.toFloat())
                                        view.start = (tAt - (cx - keysW) / tickW() - (cx - px) / tickW()).coerceIn(0f, max(0f, totalNow - view.ticks))
                                        // rows: the same, upwards
                                        val rAt = view.low + (size.height - cy) / rowH()
                                        if (sy > 60f && psy > 60f) view.rows = (view.rows * psy / sy).coerceIn(4f, 36f)
                                        view.low = (rAt - (size.height - cy) / rowH() + (cy - py) / rowH()).coerceIn(0f, max(0f, rowsNow.size - view.rows))
                                    }
                                    last = listOf(a, b)
                                    e.changes.forEach { it.consume() }
                                    return true
                                }
                                /** Follows one finger until every finger lifts; false when a second finger made it a pinch. */
                                suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.follow(onMove: (Offset) -> Unit): Boolean {
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        if (pinch(e)) continue
                                        val c = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
                                        if (e.changes.none { it.pressed }) break
                                        if (pinched) continue
                                        c.consume()
                                        if (abs(c.position.x - x0) > slop || abs(c.position.y - y0) > slop) moved = true
                                        if (moved) onMove(c.position)
                                    }
                                    return !pinched
                                }
                                if (x0 < keysW) {   // the keys: scroll the rows, or hear a note on a tap
                                    val startLow = view.low
                                    follow { p -> view.low = (startLow + (p.y - y0) / rowH()).coerceIn(0f, max(0f, rowsNow.size - view.rows)) }
                                    if (!moved && !pinched) audition(track, noteAtY(y0))
                                    return@awaitEachGesture
                                }
                                if (y0 < rulerH) {   // the ruler: scroll time
                                    val startT = view.start
                                    follow { p -> view.start = (startT - (p.x - x0) / tickW()).coerceIn(0f, max(0f, totalNow - view.ticks)) }
                                    return@awaitEachGesture
                                }
                                var notes0 = if (toolNow == 2) ensureClip() else Seq.data.value.rollNotes   // Erase: on the clip's own notes
                                var h = hit(notes0, x0, y0)
                                if (h != null && h.first.id < 0) {   // a step track: becomes a clip before its notes change
                                    notes0 = ensureClip()
                                    h = hit(notes0, x0, y0)
                                }
                                when {
                                    toolNow == 2 -> {   // Erase: every note the finger passes over goes; nothing is ever added
                                        var gone = setOfNotNull(h?.first)
                                        hidden = gone.map { it.id }.toSet()
                                        val ok = follow { p -> hit(notes0, p.x, p.y)?.first?.let { gone = gone + it; hidden = gone.map { g -> g.id }.toSet() } }
                                        hidden = emptySet()
                                        if (ok && gone.isNotEmpty()) {
                                            deleteMany(gone.map { it.id })
                                            selected = selected - gone.map { it.id }.toSet()
                                        }
                                    }
                                    h == null && toolNow == 1 -> {   // Select on empty space: a box, or a tap that clears
                                        val ok = follow { box = Offset(x0, y0) to it }
                                        val b = box
                                        box = null
                                        if (!ok) return@awaitEachGesture
                                        if (moved && b != null) {
                                            val t0 = tickAtX(min(b.first.x, b.second.x)); val t1 = tickAtX(max(b.first.x, b.second.x))
                                            val r0 = rowAtY(max(b.first.y, b.second.y)); val r1 = rowAtY(min(b.first.y, b.second.y))
                                            selected = ensureClip().filter { it.start < t1 && it.start + it.length > t0 && rowIdx(it.note) in r0..r1 }.map { it.id }.toSet()
                                        } else selected = emptySet()
                                    }
                                    h == null -> {   // Draw on empty space: a note when the finger lifts (a pinch cancels it)
                                        val start = snapTo(tickAtX(x0)).coerceIn(0, totalNow - 1)
                                        val len0 = if (lastLength > 0) lastLength else snapTicks().coerceAtLeast(stepNow)
                                        val note = noteAtY(y0).coerceIn(0, 127)
                                        previews = listOf(Preview(-1, start, len0, note, 100))
                                        val ok = follow { p -> previews = listOf(Preview(-1, start, max(snapTicks(), snapTo(tickAtX(p.x)) - start), note, 100)) }
                                        val p = previews.firstOrNull()
                                        previews = emptyList()
                                        if (!ok || p == null) return@awaitEachGesture
                                        audition(track, p.note)
                                        val id = Seq.edit("clipAdd", "start" to p.start, "length" to p.length, "note" to p.note, "velocity" to p.velocity)
                                        selected = id.trim().toIntOrNull()?.let { setOf(it) } ?: emptySet()
                                        lastLength = p.length
                                    }
                                    else -> {   // on a note: move it (or the selection), resize from its end, or a tap selects
                                        val (n0, edge) = h
                                        val inSel = toolNow == 1 && n0.id in selNow
                                        val group = if (inSel) Seq.data.value.clip.filter { it.id in selNow } else listOf(n0)
                                        val ok = follow { p ->
                                            val dt = ((p.x - x0) / tickW()).roundToInt()
                                            val dr = rowAtY(p.y) - rowAtY(y0)
                                            previews = group.map { g ->
                                                if (edge) Preview(g.id, g.start, max(snapTicks(), snapTo(g.length + dt)), g.note, g.velocity)
                                                else Preview(g.id, (g.start + snapTo(n0.start + dt) - n0.start).coerceIn(0, totalNow - 1), g.length,
                                                    rowsNow[(rowIdx(g.note) + dr).coerceIn(0, rowsNow.size - 1)], g.velocity)
                                            }
                                        }
                                        val out = previews
                                        previews = emptyList()
                                        if (!ok) return@awaitEachGesture
                                        if (moved && out.isNotEmpty()) {
                                            editMany(out)
                                            if (edge) lastLength = out.first().length
                                            if (toolNow == 0) selected = out.map { it.id }.toSet()
                                        } else if (!moved) {
                                            val id = n0.id
                                            selected = if (toolNow == 1) (if (id in selected) selected - id else selected + id) else setOf(id)
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    val keysW = 40.dp.toPx()
                    val rulerH = 18.dp.toPx()
                    val rowH = (size.height - rulerH) / view.rows
                    val tickW = (size.width - keysW - END_PAD.toPx()) / view.ticks
                    fun xOf(t: Int) = keysW + (t - view.start) * tickW
                    fun yOfRow(r: Int) = size.height - (r - view.low + 1) * rowH   // the row's top
                    keyLabel.textSize = min(11.dp.toPx(), rowH * 0.5f); rulerLabel.textSize = 10.dp.toPx()
                    drawRect(C.surface, Offset(keysW, rulerH), Size(size.width - keysW, size.height - rulerH))
                    val beatTicks = stepT * 4
                    // the ruler: bar.beat where there is room; the loop's range
                    val labelEvery = if (beatTicks * tickW >= 30.dp.toPx()) beatTicks else barTicks
                    var t = (view.start.toInt() / labelEvery) * labelEvery
                    while (t <= view.start + view.ticks) {
                        val x = xOf(t)
                        if (x >= keysW) drawContext.canvas.nativeCanvas.drawText("${t / barTicks + 1}.${(t % barTicks) / beatTicks + 1}", x + 3.dp.toPx(), 12.dp.toPx(), rulerLabel)
                        t += labelEvery
                    }
                    if (seq.loopOn && seq.loopEnd > seq.loopStart) {
                        val a0 = max(xOf(seq.loopStart * stepT), keysW); val a1 = min(xOf(seq.loopEnd * stepT), size.width)
                        if (a1 > a0) drawRect(C.pattern.copy(alpha = 0.35f), Offset(a0, rulerH - 4.dp.toPx()), Size(a1 - a0, 3.dp.toPx()))
                    }
                    val first = floor(view.low).toInt()
                    for (idx in first..(first + view.rows.toInt() + 1)) {
                        if (idx >= rowNotes.size) break
                        val n = rowNotes[idx]
                        val y = yOfRow(idx)
                        if (y + rowH <= rulerH) continue
                        val root = ((n - project.scaleRoot) % 12 + 12) % 12 == 0
                        val top = max(y, rulerH)
                        if (root) drawRect(color.copy(alpha = 0.08f), Offset(keysW, top), Size(size.width - keysW, y + rowH - top))
                        drawRoundRect(if (root) color else C.control, Offset(0f, top + 0.5f), Size(keysW - 2f, y + rowH - top - 1f), CornerRadius(3.dp.toPx()))
                        keyLabel.color = if (root) Tokens.BG else Tokens.QUIET
                        if (y >= rulerH - 1f) drawContext.canvas.nativeCanvas.drawText(noteName(n), keysW - 5.dp.toPx(), y + rowH / 2 + keyLabel.textSize / 3, keyLabel)
                        drawLine(C.divider, Offset(keysW, y + rowH), Offset(size.width, y + rowH), 0.6f)
                    }
                    // grid: bars bright, beats faint, steps when wide enough to see
                    val stepLines = stepT * tickW >= 6.dp.toPx()
                    var g = (view.start.toInt() / stepT) * stepT
                    while (g <= view.start + view.ticks) {
                        val x = xOf(g)
                        val isBar = g % barTicks == 0
                        val isBeat = g % beatTicks == 0
                        if (x >= keysW && (isBar || isBeat || stepLines))
                            drawLine(if (isBar) C.quiet else C.faint, Offset(x, rulerH), Offset(x, size.height),
                                if (isBar) 1.6f else if (isBeat) 1.2f else 1f, alpha = if (isBar) 0.7f else if (isBeat) 0.8f else 0.3f)
                        g += stepT
                    }
                    val moving = previews.map { it.id }.toSet()
                    fun drawNote(start: Int, length: Int, note: Int, vel: Int, flags: Int, sel: Boolean, muted: Boolean = false, locked: Boolean = false) {
                        val idx = rowNotes.indexOfLast { it <= note }.coerceAtLeast(0)
                        val y = yOfRow(idx)
                        if (y + rowH <= rulerH || y >= size.height) return
                        val x = xOf(start)
                        val w = length * tickW
                        if (x + w < keysW || x > size.width) return
                        val left = max(x, keysW)
                        val top = max(y, rulerH)
                        val right = min(x + w, size.width)
                        val offScale = rowNotes[idx] != note
                        drawRoundRect(color.copy(alpha = if (muted) 0.15f else if (sel) 1f else if (offScale) 0.3f else 0.45f + 0.45f * vel / 127f),
                            Offset(left + 1f, top + 2f), Size(right - left - 2f, y + rowH - top - 4f), CornerRadius(4.dp.toPx()))
                        if (sel) drawRoundRect(C.text, Offset(left - 1f, top + 0.5f), Size(right - left + 2f, y + rowH - top - 1f),
                            CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
                        if (locked) drawRect(C.bg, Offset(right - 8.dp.toPx(), y + 6.dp.toPx()), Size(5.dp.toPx(), 5.dp.toPx()))
                        if (flags and 1 != 0) drawCircle(C.bg, 2.5.dp.toPx(), Offset(left + 6.dp.toPx(), y + rowH / 2))   // accent
                        if (flags and 2 != 0) drawLine(C.bg, Offset(x + w - 9.dp.toPx(), y + rowH - 6.dp.toPx()), Offset(x + w - 3.dp.toPx(), y + 6.dp.toPx()), 2.dp.toPx())
                    }
                    seq.rollNotes.filter { (it.id < 0 || it.id !in moving) && it.id !in hidden }
                        .forEach { drawNote(it.start, it.length, it.note, it.velocity, it.flags, it.id >= 0 && it.id in selected, it.muted, it.locked) }
                    previews.forEach { drawNote(it.start, it.length, it.note, it.velocity, 0, true) }
                    box?.let { (a, b) ->
                        drawRect(C.text.copy(alpha = 0.08f), Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)))
                        drawRect(C.text, Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)), style = Stroke(1.dp.toPx()))
                    }
                    if (fast.playing && fast.activePattern == fast.focusPattern) {   // the playhead (the view stays put)
                        val x = xOf(fast.step * stepT)
                        if (x >= keysW && x <= size.width) drawLine(C.pattern, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    }
                }
            }
        }
    }
}

/** Velocity: the notes in view as stalks (drag one; a selected note moves the whole selection), and lanes for
    accent and slide (tap a note's dot to toggle it, for the selection when it is in it). */
@Composable
private fun VelocityPanel(modifier: Modifier, view: RollView, color: Color, selected: Set<Int>) {
    val seq by Seq.data
    val sel by rememberUpdatedState(selected)
    var drag by remember { mutableStateOf<Pair<Set<Int>, Int>?>(null) }   // ids → velocity while dragging
    val keysW = 40.dp
    fun inView(n: ClipNote) = n.start >= view.start && n.start < view.start + view.ticks
    fun ensureClip() { if (!Seq.data.value.pianoRoll) Seq.edit("toRoll") }
    /** Each note's stalk position: a chord's notes side by side (lowest left), so every one can be seen and taken. */
    fun stalks(notes: List<ClipNote>, kw: Float, tickW: Float, apart: Float): List<Pair<ClipNote, Float>> {
        val tol = maxOf(1, seq.stepTicks / 16)
        val out = mutableListOf<Pair<ClipNote, Float>>()
        val byStart = notes.sortedBy { it.start }.fold(mutableListOf<MutableList<ClipNote>>()) { groups, n ->
            if (groups.isNotEmpty() && abs(groups.last().first().start - n.start) <= tol) groups.last().add(n) else groups.add(mutableListOf(n))
            groups
        }
        for (g in byStart) {
            val sorted = g.sortedBy { it.note }
            sorted.forEachIndexed { i, n -> out += n to (kw + (g.first().start - view.start) * tickW + 6f + i * apart) }
        }
        return out
    }
    Column(modifier) {
        Canvas(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface)
                .pointerInput(Unit) {
                    val kw = keysW.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        ensureClip()
                        val tickW = (size.width - kw - END_PAD.toPx()) / view.ticks
                        val notes = Seq.data.value.clip.filter { inView(it) }
                        fun headY(v: Int) = 8f + (size.height - 16f) * (1 - v / 127f)
                        // the stalk whose head is nearest the finger (a chord's notes stand side by side)
                        val nearest = stalks(notes, kw, tickW, 16.dp.toPx()).minByOrNull { (n, x) ->
                            val dx = x - down.position.x; val dy = (headY(n.velocity) - down.position.y) * 0.3f
                            dx * dx + dy * dy
                        }?.first ?: return@awaitEachGesture
                        val ids = if (nearest.id in sel) sel else setOf(nearest.id)
                        fun velAt(y: Float) = (127 * (1 - (y - 8f) / (size.height - 16f))).roundToInt().coerceIn(1, 127)
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
            val kw = keysW.toPx()
            val tickW = (size.width - kw - END_PAD.toPx()) / view.ticks
            drawContext.canvas.nativeCanvas.drawText("Vel", 6.dp.toPx(), 16.dp.toPx(), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Tokens.FAINT; textSize = 11.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD })
            stalks(seq.rollNotes.filter { inView(it) }, kw, tickW, 16.dp.toPx()).forEach { (n, x) ->
                val v = if (drag?.first?.contains(n.id) == true) drag!!.second else n.velocity
                val top = 8f + (size.height - 16f) * (1 - v / 127f)
                val on = n.id in selected || drag?.first?.contains(n.id) == true
                drawLine(if (on) color else C.quiet.copy(alpha = 0.6f), Offset(x, size.height - 8f), Offset(x, top), 3.dp.toPx())
                drawCircle(if (on) color else C.quiet, 5.dp.toPx(), Offset(x, top))
            }
        }
        Spacer(Modifier.height(6.dp))
        FlagLane("Accent", 1, view, color, selected)
        Spacer(Modifier.height(6.dp))
        FlagLane("Slide", 2, view, color, selected)
    }
}

/** A lane of dots, one per note in view: lit when the note has the flag; a tap toggles it. */
@Composable
private fun FlagLane(label: String, bit: Int, view: RollView, color: Color, selected: Set<Int>) {
    val seq by Seq.data
    val sel by rememberUpdatedState(selected)
    val keysW = 40.dp
    Canvas(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(C.surface)
            .pointerInput(Unit) {
                val kw = keysW.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!Seq.data.value.pianoRoll) Seq.edit("toRoll")
                    val tickW = (size.width - kw - END_PAD.toPx()) / view.ticks
                    val notes = Seq.data.value.clip.filter { it.start >= view.start && it.start < view.start + view.ticks }
                    val nearest = notes.minByOrNull { abs(kw + (it.start - view.start) * tickW - down.position.x) } ?: return@awaitEachGesture
                    if (abs(kw + (nearest.start - view.start) * tickW - down.position.x) > 24.dp.toPx()) return@awaitEachGesture
                    // the selection, or the whole chord at that time (accent and slide belong to the moment)
                    val tol = maxOf(1, Seq.data.value.stepTicks / 16)
                    val group = if (nearest.id in sel) notes.filter { it.id in sel } else notes.filter { abs(it.start - nearest.start) <= tol }
                    val on = nearest.flags and bit == 0
                    val arr = JSONArray()
                    group.forEach { arr.put(JSONObject().put("id", it.id).put("flags", if (on) it.flags or bit else it.flags and bit.inv())) }
                    Seq.edit("clipEditMany", "notes" to arr)
                }
            },
    ) {
        val kw = keysW.toPx()
        val tickW = (size.width - kw - END_PAD.toPx()) / view.ticks
        drawContext.canvas.nativeCanvas.drawText(label, 6.dp.toPx(), size.height / 2 + 4.dp.toPx(), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Tokens.FAINT; textSize = 10.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        seq.rollNotes.filter { it.start >= view.start && it.start < view.start + view.ticks }.forEach { n ->
            val x = kw + (n.start - view.start) * tickW + 2f
            val lit = n.flags and bit != 0
            drawCircle(if (lit) color else C.control, 7.dp.toPx(), Offset(x + 5.dp.toPx(), size.height / 2))
            if (n.id in selected) drawCircle(C.text, 8.dp.toPx(), Offset(x + 5.dp.toPx(), size.height / 2), style = Stroke(1.5.dp.toPx()))
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
