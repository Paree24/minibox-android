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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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

/** `focus`: the roll is open full screen (its ⤢ button; MainScreen then hides the app bar, the tracks and the nav). */
object RollUi {
    val focus = mutableStateOf(false)
}

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

/** What the roll shows: from `start` (ticks), `ticks` long; rows of the scale from `low` (a row index, may be
    fractional while scrolling), `rows` of them. Shared by the Notes and Velocity tabs. */
private class RollView {
    var start by mutableFloatStateOf(0f)
    var ticks by mutableFloatStateOf(0f)
    var low by mutableFloatStateOf(-1f)
    var rows by mutableFloatStateOf(8f)   // one octave of the scale
}

/**
 * The roll's geometry, lying either way: time across with the keys on the left (horizontal), or time running down
 * with the keys across the top (vertical, as the step editor). Everything is in "time" and "pitch" coordinates: the
 * time axis starts after the keys strip and ends END_PAD short of the edge; the pitch axis starts after the ruler.
 * Horizontal rows count up from the bottom; vertical ones from the left (low notes left, as on a keyboard).
 */
private class Geo(val vertical: Boolean, val w: Float, val h: Float, val view: RollView, val keys: Float, val ruler: Float, endPad: Float) {
    val timeStart = keys
    val timeEnd = (if (vertical) h else w) - endPad
    val pitchStart = ruler
    val pitchEnd = if (vertical) w else h
    val tickW = (timeEnd - timeStart) / view.ticks
    val rowSize = (pitchEnd - pitchStart) / view.rows
    /** +1: rows grow with the pixel (vertical: rightwards); −1: against it (horizontal: upwards). */
    val sign = if (vertical) 1f else -1f
    val origin = if (vertical) pitchStart else pitchEnd
    fun time(o: Offset) = if (vertical) o.y else o.x
    fun pitch(o: Offset) = if (vertical) o.x else o.y
    fun tPix(t: Float) = timeStart + (t - view.start) * tickW
    fun tickAt(pix: Float) = (view.start + (pix - timeStart) / tickW).toInt()
    fun rowF(pix: Float) = view.low + sign * (pix - origin) / rowSize
    fun rowAt(pix: Float) = floor(rowF(pix)).toInt()
    /** A row's span on the pitch axis (low, high pixel). */
    fun rowSpan(r: Int): Pair<Float, Float> {
        val a = origin + sign * (r - view.low) * rowSize
        return if (vertical) a to a + rowSize else a - rowSize to a
    }
    fun rect(t0: Float, t1: Float, p0: Float, p1: Float) = if (vertical) Rect(p0, t0, p1, t1) else Rect(t0, p0, t1, p1)
    fun inKeys(o: Offset) = time(o) < keys
    fun inRuler(o: Offset) = pitch(o) < ruler
    /** The grid's area (notes are clipped to it). */
    val grid get() = rect(timeStart, timeEnd, pitchStart, pitchEnd)
}

/**
 * The piano roll (mockups 15–16 and roll_options), the whole Seq page. Notes and Velocity (with accent and slide)
 * tabs; rows are the project scale, an octave in view. The view never moves by itself.
 * Touch: tap empty space: a note; double tap a note: delete; hold a note: select it (keep dragging sideways: length);
 * hold empty space and drag: box select; drag a note: move it, a selected one: length (sideways) or transpose (up and
 * down); drag the keys or the ruler to scroll; two fingers pinch (each axis on its own) and pan.
 */
@Composable
fun PianoRoll(color: Color, focusBar: (@Composable () -> Unit)? = null) {
    val seq by Seq.data
    val fast by App.fast
    val project by App.project
    val track = App.focusTrack.intValue
    // vertical, as the step editor (roll_options B, chosen 2026-11-01): time runs down, the scale across the top
    val vertical = true
    var tab by remember { mutableIntStateOf(0) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var snap by remember { mutableIntStateOf(1) }
    var selected by remember(track) { mutableStateOf(setOf<Int>()) }
    var previews by remember { mutableStateOf<List<Preview>>(emptyList()) }
    var box by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var lastLength by remember { mutableIntStateOf(0) }
    var cursor by remember(track) { mutableIntStateOf(0) }   // where Paste puts the notes: tap the time ruler
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
    val totalNow by rememberUpdatedState(totalTicks)
    val stepNow by rememberUpdatedState(stepT)
    val selNow by rememberUpdatedState(selected)
    fun snapTicks() = (SNAPS[snap].second * stepT).roundToInt().coerceAtLeast(1)
    fun snapTo(t: Int) = if (SNAPS[snap].second == 0f) t else (t.toFloat() / snapTicks()).roundToInt() * snapTicks()
    val grid = if (SNAPS[snap].second == 0f) stepT else snapTicks()
    val keyLabel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    val rulerLabel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Tokens.FAINT; typeface = android.graphics.Typeface.DEFAULT_BOLD } }

    // the edits, on the selection (or every note): in key, octaves, the clipboard, quantize; and delete
    val edits = listOf<Pair<String, () -> Unit>>(
        "Sel all" to {
            if (!Seq.data.value.pianoRoll) Seq.edit("toRoll")
            selected = Seq.data.value.clip.map { it.id }.toSet()
        },
        // pitch runs across the vertical roll: down is left, up is right
        "←" to { rollOp("transpose", selected, "dir" to -1, "scale" to true)?.let { selected = it } },
        "→" to { rollOp("transpose", selected, "dir" to 1, "scale" to true)?.let { selected = it } },
        "8←" to { rollOp("transpose", selected, "dir" to -12)?.let { selected = it } },
        "8→" to { rollOp("transpose", selected, "dir" to 12)?.let { selected = it } },
        "Cut" to { if (selected.isNotEmpty()) { rollOp("cut", selected); selected = emptySet() } },
        "Copy" to { if (selected.isNotEmpty()) rollOp("copy", selected) },
        // paste at the cursor (tap the time ruler to place it); after Paste and Q nothing stays selected, so the notes
        // drag as moves again (a selected note drags as length / transpose)
        "Paste" to { rollOp("paste", emptySet(), "grid" to grid, "at" to cursor); selected = emptySet() },
        "Q" to { rollOp("quantize", selected, "grid" to grid); selected = emptySet() },
        // each chord played in turn, with the track's Arp settings (Play → Arp: rate, mode, octaves, gate)
        "Arp" to { rollOp("arpeggiate", selected)?.let { selected = it } },
    )
    val selNotes = seq.clip.filter { it.id in selected }
    val selName = when { selNotes.isEmpty() -> "All"; selNotes.size == 1 -> noteName(selNotes.first().note); else -> "${selNotes.size} notes" }
    @Composable fun EditButton(label: String, modifier: Modifier, action: () -> Unit) {
        Box(modifier.clip(RoundedCornerShape(9.dp)).background(C.control).clickable(onClick = action), contentAlignment = Alignment.Center) {
            Text(label, color = C.text, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
    @Composable fun DeleteButton(modifier: Modifier) {
        val can = selected.isNotEmpty()
        Box(modifier.clip(RoundedCornerShape(16.dp)).background(C.control).clickable(enabled = can) { deleteMany(selected); selected = emptySet() },
            contentAlignment = Alignment.Center) {
            Text("Delete", color = if (can) C.record else C.faint, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
    @Composable fun RollCanvas(modifier: Modifier) {
        Canvas(
            modifier.clip(RoundedCornerShape(10.dp))
                // one handler for every gesture: the view state is read live, so nothing restarts mid-touch
                .pointerInput(vertical) {
                    val keysPx = (if (vertical) 36.dp else 40.dp).toPx()
                    val rulerPx = (if (vertical) 30.dp else 18.dp).toPx()
                    val slop = 10.dp.toPx()
                    val reach = 12.dp.toPx()   // a tap this close to a note counts as on it
                    fun geo() = Geo(vertical, size.width.toFloat(), size.height.toFloat(), view, keysPx, rulerPx, END_PAD.toPx())
                    fun rowIdx(n: Int) = rowsNow.indexOfLast { it <= n }.coerceAtLeast(0)
                    fun noteAt(g: Geo, o: Offset) = rowsNow[g.rowAt(g.pitch(o)).coerceIn(0, rowsNow.size - 1)]
                    /** The note under `o` or nearest to it in that row (within `reach`); and whether on its end handle. */
                    fun hit(g: Geo, notes: List<ClipNote>, o: Offset): Pair<ClipNote, Boolean>? {
                        val r = g.rowAt(g.pitch(o))
                        val t = g.time(o)
                        val found = notes.filter { rowIdx(it.note) == r }.map { n ->
                            val a = g.tPix(n.start.toFloat()); val b = g.tPix((n.start + n.length).toFloat())
                            n to (if (t < a) a - t else if (t > b) t - b else 0f)
                        }.filter { it.second <= reach }.minByOrNull { it.second }?.first ?: return null
                        val end = g.tPix((found.start + found.length).toFloat())
                        return found to (t <= end + reach && end - t < min(14.dp.toPx(), found.length * g.tickW / 3f))
                    }
                    /** The notes `dt` ticks longer (shorter when negative), snapped, never under one snap. */
                    fun resized(group: List<ClipNote>, dt: Int) = group.map { n -> Preview(n.id, n.start, max(snapTicks(), snapTo(n.length + dt)), n.note, n.velocity) }
                    fun ensureClip(): List<ClipNote> {
                        if (!Seq.data.value.pianoRoll) Seq.edit("toRoll")
                        return Seq.data.value.clip
                    }
                    var lastTapId = -1          // double tap on a note deletes it
                    var lastTapAt = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val g0 = geo()
                        val p0 = down.position
                        var moved = false
                        var pinched = false
                        // two fingers: pinch and pan, each axis on its own; called with every event while two are down
                        var last: List<Offset>? = null
                        fun pinch(e: PointerEvent): Boolean {
                            val pts = e.changes.filter { it.pressed }.map { it.position }
                            if (pts.size < 2) { last = null; return false }
                            pinched = true
                            previews = emptyList(); box = null
                            val a = pts[0]; val b = pts[1]
                            val l = last
                            if (l != null) {
                                var g = geo()
                                val ct = (g.time(a) + g.time(b)) / 2; val pt = (g.time(l[0]) + g.time(l[1])) / 2
                                val st = abs(g.time(a) - g.time(b)); val pst = abs(g.time(l[0]) - g.time(l[1]))
                                val tAt = view.start + (ct - g.timeStart) / g.tickW
                                if (st > 60f && pst > 60f) view.ticks = (view.ticks * pst / st).coerceIn(min(stepNow * 2, totalNow).toFloat(), totalNow.toFloat())
                                g = geo()
                                view.start = (tAt - (ct - g.timeStart) / g.tickW - (ct - pt) / g.tickW).coerceIn(0f, max(0f, totalNow - view.ticks))
                                val cp = (g.pitch(a) + g.pitch(b)) / 2; val pp = (g.pitch(l[0]) + g.pitch(l[1])) / 2
                                val sp = abs(g.pitch(a) - g.pitch(b)); val psp = abs(g.pitch(l[0]) - g.pitch(l[1]))
                                val rAt = g.rowF(cp)
                                if (sp > 60f && psp > 60f) view.rows = (view.rows * psp / sp).coerceIn(4f, 36f)
                                g = geo()
                                view.low = (rAt - g.sign * (cp - g.origin) / g.rowSize - g.sign * (cp - pp) / g.rowSize).coerceIn(0f, max(0f, rowsNow.size - view.rows))
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
                                if (abs(c.position.x - p0.x) > slop || abs(c.position.y - p0.y) > slop) moved = true
                                if (moved) onMove(c.position)
                            }
                            return !pinched
                        }
                        if (g0.inKeys(p0)) {   // the keys: scroll the rows, or hear a note on a tap
                            val startLow = view.low
                            follow { p -> view.low = (startLow - g0.sign * (g0.pitch(p) - g0.pitch(p0)) / g0.rowSize).coerceIn(0f, max(0f, rowsNow.size - view.rows)) }
                            if (!moved && !pinched) audition(track, noteAt(g0, p0))
                            return@awaitEachGesture
                        }
                        if (g0.inRuler(p0)) {   // the ruler: drag to scroll time, tap to place the paste cursor
                            val startT = view.start
                            follow { p -> view.start = (startT - (g0.time(p) - g0.time(p0)) / g0.tickW).coerceIn(0f, max(0f, totalNow - view.ticks)) }
                            if (!moved && !pinched) cursor = snapTo(g0.tickAt(g0.time(p0))).coerceIn(0, totalNow - 1)
                            return@awaitEachGesture
                        }
                        var notes0 = Seq.data.value.rollNotes
                        var h = hit(g0, notes0, p0)
                        if (h != null && h.first.id < 0) {   // a step track: becomes a clip before its notes change
                            notes0 = ensureClip()
                            h = hit(g0, notes0, p0)
                        }
                        // what this touch is: 0 a tap, 1 a drag, 2 a hold, 3 two fingers
                        val kind = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var k = -1
                            while (k < 0) {
                                val e = awaitPointerEvent()
                                if (pinch(e)) { k = 3; break }
                                val c = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
                                if (e.changes.none { it.pressed }) { k = 0; break }
                                c.consume()
                                if (abs(c.position.x - p0.x) > slop || abs(c.position.y - p0.y) > slop) { moved = true; k = 1 }
                            }
                            k
                        } ?: 2
                        fun dTicks(p: Offset) = ((g0.time(p) - g0.time(p0)) / g0.tickW).roundToInt()
                        fun dRows(p: Offset) = g0.rowAt(g0.pitch(p)) - g0.rowAt(g0.pitch(p0))
                        fun transposed(group: List<ClipNote>, dr: Int) =
                            group.map { n -> Preview(n.id, n.start, n.length, rowsNow[(rowIdx(n.note) + dr).coerceIn(0, rowsNow.size - 1)], n.velocity) }
                        when {
                            kind == 3 -> follow {}   // zoom / pan until the fingers lift
                            kind == 0 && h != null -> {   // a tap on a note: hear it; a second tap soon after deletes it
                                val id = h.first.id
                                if (id == lastTapId && down.uptimeMillis - lastTapAt < 300) {
                                    deleteMany(listOf(id))
                                    selected = selected - id
                                    lastTapId = -1
                                } else {
                                    audition(track, h.first.note)
                                    lastTapId = id; lastTapAt = down.uptimeMillis
                                }
                            }
                            kind == 0 -> {   // a tap on empty space: a note there
                                val start = snapTo(g0.tickAt(g0.time(p0))).coerceIn(0, totalNow - 1)
                                val len = if (lastLength > 0) lastLength else snapTicks().coerceAtLeast(stepNow)
                                val note = noteAt(g0, p0).coerceIn(0, 127)
                                audition(track, note)
                                val id = Seq.edit("clipAdd", "start" to start, "length" to len, "note" to note, "velocity" to 100).trim().toIntOrNull()
                                if (id != null) { lastTapId = id; lastTapAt = down.uptimeMillis }
                            }
                            kind == 2 && h != null -> {   // hold a note: it becomes the selection; keep dragging sideways to resize
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val id = h.first.id
                                val wasSelected = id in selected
                                val ids = if (wasSelected) selected else setOf(id)   // held inside a selection: that selection
                                selected = ids
                                val group = Seq.data.value.clip.filter { it.id in ids }
                                val ok = follow { p -> previews = resized(group, dTicks(p)) }
                                val out = previews
                                previews = emptyList()
                                if (!ok) return@awaitEachGesture
                                if (moved && out.isNotEmpty()) { editMany(out); lastLength = out.first().length }
                                else if (wasSelected) selected = if (ids.size == 1) emptySet() else setOf(id)   // held again: unselected, or alone
                            }
                            kind == 2 -> {   // hold empty space: drag a box to select; lift without moving to clear
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val ok = follow { box = p0 to it }
                                val b = box
                                box = null
                                if (!ok) return@awaitEachGesture
                                if (moved && b != null) {
                                    val t0 = g0.tickAt(min(g0.time(b.first), g0.time(b.second))); val t1 = g0.tickAt(max(g0.time(b.first), g0.time(b.second)))
                                    val ra = g0.rowAt(g0.pitch(b.first)); val rb = g0.rowAt(g0.pitch(b.second))
                                    selected = ensureClip().filter { it.start < t1 && it.start + it.length > t0 && rowIdx(it.note) in min(ra, rb)..max(ra, rb) }.map { it.id }.toSet()
                                } else selected = emptySet()
                            }
                            h == null -> {   // drag on empty space: a note as long as the drag (a pinch cancels it)
                                val start = snapTo(g0.tickAt(g0.time(p0))).coerceIn(0, totalNow - 1)
                                val note = noteAt(g0, p0).coerceIn(0, 127)
                                val ok = follow { p -> previews = listOf(Preview(-1, start, max(snapTicks(), snapTo(g0.tickAt(g0.time(p))) - start), note, 100)) }
                                val p = previews.firstOrNull()
                                previews = emptyList()
                                if (!ok || p == null) return@awaitEachGesture
                                audition(track, p.note)
                                Seq.edit("clipAdd", "start" to p.start, "length" to p.length, "note" to p.note, "velocity" to p.velocity)
                                lastLength = p.length
                            }
                            else -> {   // drag a note. Selected: along time its length, across it a transpose (the first clear direction
                                        // decides); unselected: move it (from its end: resize)
                                val (n0, edge) = h
                                val inSel = n0.id in selNow
                                val group = if (inSel) Seq.data.value.clip.filter { it.id in selNow } else listOf(n0)
                                var axis = if (edge) 1 else 0   // 0 undecided, 1 length, 2 pitch
                                val ok = follow { p ->
                                    if (!inSel || edge) {
                                        previews = if (edge) resized(group, dTicks(p)) else group.map { n ->
                                            Preview(n.id, (n.start + snapTo(n0.start + dTicks(p)) - n0.start).coerceIn(0, totalNow - 1), n.length,
                                                rowsNow[(rowIdx(n.note) + dRows(p)).coerceIn(0, rowsNow.size - 1)], n.velocity)
                                        }
                                    } else {
                                        if (axis == 0) axis = if (abs(g0.time(p) - g0.time(p0)) >= abs(g0.pitch(p) - g0.pitch(p0))) 1 else 2
                                        previews = if (axis == 1) resized(group, dTicks(p)) else transposed(group, dRows(p))
                                    }
                                }
                                val out = previews
                                previews = emptyList()
                                if (!ok || out.isEmpty()) return@awaitEachGesture
                                editMany(out)
                                if (axis == 1) lastLength = out.first().length
                                if (axis == 2) audition(track, out.first().note)
                            }
                        }
                    }
                },
        ) {
            val g = Geo(vertical, size.width, size.height, view, (if (vertical) 36.dp else 40.dp).toPx(), (if (vertical) 30.dp else 18.dp).toPx(), END_PAD.toPx())
            keyLabel.textSize = min(11.dp.toPx(), g.rowSize * 0.5f); rulerLabel.textSize = 10.dp.toPx()
            drawRect(C.surface, g.grid.topLeft, g.grid.size)
            val beatTicks = stepT * 4
            // the ruler: bar.beat where there is room; the loop's range
            val labelEvery = if (beatTicks * g.tickW >= 30.dp.toPx()) beatTicks else barTicks
            var t = (view.start.toInt() / labelEvery) * labelEvery
            while (t <= view.start + view.ticks) {
                val tp = g.tPix(t.toFloat())
                val label = "${t / barTicks + 1}.${(t % barTicks) / beatTicks + 1}"
                if (tp >= g.timeStart) {
                    if (vertical) { rulerLabel.textAlign = android.graphics.Paint.Align.RIGHT; drawContext.canvas.nativeCanvas.drawText(label, g.ruler - 4.dp.toPx(), tp + 11.dp.toPx(), rulerLabel) }
                    else { rulerLabel.textAlign = android.graphics.Paint.Align.LEFT; drawContext.canvas.nativeCanvas.drawText(label, tp + 3.dp.toPx(), 12.dp.toPx(), rulerLabel) }
                }
                t += labelEvery
            }
            if (seq.loopOn && seq.loopEnd > seq.loopStart) {
                val a = max(g.tPix((seq.loopStart * stepT).toFloat()), g.timeStart); val b = min(g.tPix((seq.loopEnd * stepT).toFloat()), g.timeEnd)
                if (b > a) { val r = g.rect(a, b, g.ruler - 4.dp.toPx(), g.ruler - 1.dp.toPx()); drawRect(C.pattern.copy(alpha = 0.35f), r.topLeft, r.size) }
            }
            // rows: the keys and a tint on the roots
            val first = floor(view.low).toInt()
            for (idx in first..(first + view.rows.toInt() + 1)) {
                if (idx >= rowNotes.size) break
                val n = rowNotes[idx]
                val (pa, pb) = g.rowSpan(idx)
                val a = max(pa, g.pitchStart); val b = min(pb, g.pitchEnd)
                if (b <= a) continue
                val root = ((n - project.scaleRoot) % 12 + 12) % 12 == 0
                if (root) { val r = g.rect(g.timeStart, g.timeEnd + END_PAD.toPx(), a, b); drawRect(color.copy(alpha = 0.08f), r.topLeft, r.size) }
                val key = g.rect(0f, g.keys - 2f, a + 0.5f, b - 0.5f)
                drawRoundRect(if (root) color else C.control, key.topLeft, key.size, CornerRadius(3.dp.toPx()))
                keyLabel.color = if (root) Tokens.BG else Tokens.QUIET
                if (pa >= g.pitchStart - 1f) {
                    if (vertical) { keyLabel.textAlign = android.graphics.Paint.Align.CENTER; drawContext.canvas.nativeCanvas.drawText(noteName(n), (pa + pb) / 2, g.keys / 2 + keyLabel.textSize / 3, keyLabel) }
                    else { keyLabel.textAlign = android.graphics.Paint.Align.RIGHT; drawContext.canvas.nativeCanvas.drawText(noteName(n), g.keys - 5.dp.toPx(), (pa + pb) / 2 + keyLabel.textSize / 3, keyLabel) }
                }
                val edge = if (vertical) pb else pa   // the line between this row and the next
                val l = g.rect(g.timeStart, g.timeEnd + END_PAD.toPx(), edge, edge + 0.6f)
                drawRect(C.divider, l.topLeft, l.size)
            }
            // grid: bars bright, beats faint, steps when wide enough to see
            val stepLines = stepT * g.tickW >= 6.dp.toPx()
            var s = (view.start.toInt() / stepT) * stepT
            while (s <= view.start + view.ticks) {
                val tp = g.tPix(s.toFloat())
                val isBar = s % barTicks == 0
                val isBeat = s % beatTicks == 0
                if (tp >= g.timeStart && (isBar || isBeat || stepLines)) {
                    val wdt = if (isBar) 1.6f else if (isBeat) 1.2f else 1f
                    val r = g.rect(tp, tp + wdt, g.pitchStart, g.pitchEnd)
                    drawRect(if (isBar) C.quiet else C.faint, r.topLeft, r.size, alpha = if (isBar) 0.7f else if (isBeat) 0.8f else 0.3f)
                }
                s += stepT
            }
            val moving = previews.map { it.id }.toSet()
            clipRect(g.grid.left, g.grid.top, g.grid.right + (if (vertical) 0f else END_PAD.toPx()), g.grid.bottom + (if (vertical) END_PAD.toPx() else 0f)) {
                fun drawNote(start: Int, length: Int, note: Int, vel: Int, flags: Int, sel: Boolean, muted: Boolean = false, locked: Boolean = false) {
                    val idx = rowNotes.indexOfLast { it <= note }.coerceAtLeast(0)
                    val (pa, pb) = g.rowSpan(idx)
                    val r = g.rect(g.tPix(start.toFloat()) + 1f, g.tPix((start + length).toFloat()) - 1f, pa + 2f, pb - 2f)
                    val offScale = rowNotes[idx] != note
                    drawRoundRect(color.copy(alpha = if (muted) 0.15f else if (sel) 1f else if (offScale) 0.3f else 0.45f + 0.45f * vel / 127f),
                        r.topLeft, r.size, CornerRadius(4.dp.toPx()))
                    if (sel) drawRoundRect(C.text, Offset(r.left - 1.5f, r.top - 1.5f), Size(r.width + 3f, r.height + 3f), CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
                    val mark = if (vertical) Offset(r.center.x, r.top + 6.dp.toPx()) else Offset(r.left + 6.dp.toPx(), r.center.y)
                    if (flags and 1 != 0) drawCircle(C.bg, 2.5.dp.toPx(), mark)   // accent
                    if (flags and 2 != 0) drawCircle(C.bg, 1.5.dp.toPx(), if (vertical) Offset(r.center.x, r.bottom - 6.dp.toPx()) else Offset(r.right - 6.dp.toPx(), r.center.y))   // slide
                    if (locked) drawRect(C.bg, Offset(r.right - 8.dp.toPx(), r.top + 4.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()))
                }
                seq.rollNotes.filter { it.id < 0 || it.id !in moving }
                    .forEach { drawNote(it.start, it.length, it.note, it.velocity, it.flags, it.id >= 0 && it.id in selected, it.muted, it.locked) }
                previews.forEach { drawNote(it.start, it.length, it.note, it.velocity, 0, true) }
            }
            box?.let { (a, b) ->
                drawRect(C.text.copy(alpha = 0.08f), Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)))
                drawRect(C.text, Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)), style = Stroke(1.dp.toPx()))
            }
            run {   // the paste cursor: a line across, with a mark in the ruler
                val tp = g.tPix(cursor.toFloat())
                if (tp >= g.timeStart && tp <= g.timeEnd + END_PAD.toPx()) {
                    val r = g.rect(tp, tp + 1.5f, 0f, g.pitchEnd); drawRect(C.text.copy(alpha = 0.55f), r.topLeft, r.size)
                    val m = g.rect(tp - 3.dp.toPx(), tp + 3.dp.toPx(), 2.dp.toPx(), g.ruler - 2.dp.toPx()); drawRoundRect(C.text, m.topLeft, m.size, CornerRadius(2.dp.toPx()))
                }
            }
            if (fast.playing && fast.activePattern == fast.focusPattern) {   // the playhead (the view stays put)
                val tp = g.tPix((fast.step * stepT).toFloat())
                if (tp >= g.timeStart && tp <= g.timeEnd + END_PAD.toPx()) { val r = g.rect(tp, tp + 2.dp.toPx(), 0f, g.pitchEnd); drawRect(C.pattern, r.topLeft, r.size) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        focusBar?.invoke()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("Notes", "Velocity"), tab, C.text, { tab = it }, Modifier.weight(1f), height = 32.dp)
            Spacer(Modifier.width(6.dp))
            Pill(SNAPS[snap].first, caret = true) { snap = (snap + 1) % SNAPS.size }
            if (focusBar == null) {   // full screen: the roll over everything (the bar above takes it back)
                Spacer(Modifier.width(6.dp))
                ExpandButton { RollUi.focus.value = true }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (tab == 1) {
            VelocityPanel(Modifier.weight(1f).fillMaxWidth(), view, color, selected) { selected = it }
            return@Column
        }
        // the roll, and the edits stacked down its right edge (the height is the roll's)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            RollCanvas(Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(6.dp))
            Column(Modifier.width(48.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(selName, color = if (selNotes.isEmpty()) C.faint else color, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().height(18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                edits.forEach { (l, a) -> EditButton(l, Modifier.fillMaxWidth().weight(1f), a) }
                DeleteButton(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** Velocity, lying as the roll does (time runs down, in line with it): each note a bar whose length is its
    velocity (a chord's notes a little apart, so each can be taken; drag one sideways, a selected one moves the whole
    selection), Randomize ±N % on top, and Accent and Slide columns on the right (tap a note's dot: the chord at that
    time, or the selection, toggles). */
@Composable
private fun VelocityPanel(modifier: Modifier, view: RollView, color: Color, selected: Set<Int>, onSelect: (Set<Int>) -> Unit) {
    val seq by Seq.data
    val sel by rememberUpdatedState(selected)
    var drag by remember { mutableStateOf<Pair<Set<Int>, Int>?>(null) }   // ids → velocity while dragging
    var amount by remember { mutableIntStateOf(1) }
    val amounts = listOf(10, 20, 30, 50)
    val keysH = 36.dp; val rulerW = 30.dp
    fun inView(n: ClipNote) = n.start >= view.start && n.start < view.start + view.ticks
    fun ensureClip() { if (!Seq.data.value.pianoRoll) Seq.edit("toRoll") }
    /** Each note's place along time (y), a chord's notes `apart` from each other. */
    fun rows(notes: List<ClipNote>, y0: Float, tickH: Float, apart: Float): List<Pair<ClipNote, Float>> {
        val tol = maxOf(1, seq.stepTicks / 16)
        val out = mutableListOf<Pair<ClipNote, Float>>()
        notes.sortedBy { it.start }.fold(mutableListOf<MutableList<ClipNote>>()) { g, n ->
            if (g.isNotEmpty() && abs(g.last().first().start - n.start) <= tol) g.last().add(n) else g.add(mutableListOf(n)); g
        }.forEach { g -> g.sortedBy { it.note }.forEachIndexed { i, n -> out += n to (y0 + (g.first().start - view.start) * tickH + 6f + i * apart) } }
        return out
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Randomize", color = C.quiet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Segmented(amounts.map { "±$it%" }, amount, C.text, { amount = it }, Modifier.weight(1f), height = 32.dp)
            Spacer(Modifier.width(6.dp))
            Pill("Apply", on = true, color = color, height = 32.dp) {
                rollOp("velocityRandom", selected, "amount" to amounts[amount] / 100.0)?.let { onSelect(if (selected.isEmpty()) emptySet() else it) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(C.surface)
                    .pointerInput(Unit) {
                        val y0 = keysH.toPx(); val x0 = rulerW.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            ensureClip()
                            val tickH = (size.height - y0 - END_PAD.toPx()) / view.ticks
                            val notes = Seq.data.value.clip.filter { inView(it) }
                            fun barEnd(v: Int) = x0 + (size.width - x0 - 8f) * v / 127f
                            // the bar whose end is nearest the finger (rows count more than the bar's length)
                            val nearest = rows(notes, y0, tickH, 14.dp.toPx()).minByOrNull { (n, y) ->
                                val dy = y - down.position.y; val dx = (barEnd(n.velocity) - down.position.x) * 0.3f
                                dx * dx + dy * dy
                            }?.first ?: return@awaitEachGesture
                            val ids = if (nearest.id in sel) sel else setOf(nearest.id)
                            fun velAt(x: Float) = (127 * (x - x0) / (size.width - x0 - 8f)).roundToInt().coerceIn(1, 127)
                            drag = ids to velAt(down.position.x)
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.first()
                                if (!c.pressed) break
                                c.consume()
                                drag = ids to velAt(c.position.x)
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
                val y0 = keysH.toPx(); val x0 = rulerW.toPx()
                val tickH = (size.height - y0 - END_PAD.toPx()) / view.ticks
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Tokens.FAINT; textSize = 11.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD }
                drawContext.canvas.nativeCanvas.drawText("Velocity", x0, y0 - 12.dp.toPx(), paint)
                for (q in listOf(0.25f, 0.5f, 0.75f, 1f)) {   // 25 / 50 / 75 / 100 %
                    val x = x0 + (size.width - x0 - 8f) * q
                    drawLine(C.divider, Offset(x, y0), Offset(x, size.height), 1f)
                }
                rows(seq.rollNotes.filter { inView(it) }, y0, tickH, 14.dp.toPx()).forEach { (n, y) ->
                    val v = if (drag?.first?.contains(n.id) == true) drag!!.second else n.velocity
                    val end = x0 + (size.width - x0 - 8f) * v / 127f
                    val on = n.id in selected || drag?.first?.contains(n.id) == true
                    drawLine(if (on) color else C.quiet.copy(alpha = 0.6f), Offset(x0, y), Offset(end, y), 3.dp.toPx())
                    drawCircle(if (on) color else C.quiet, 5.dp.toPx(), Offset(end, y))
                }
            }
            Spacer(Modifier.width(4.dp))
            FlagColumn("A", 1, view, color, selected)
            Spacer(Modifier.width(4.dp))
            FlagColumn("S", 2, view, color, selected)
        }
    }
}

/** A column of dots, one per note in view (in line with the velocity bars): lit when the note has the flag; a tap
    toggles it for the chord at that time (or the selection). A = accent, S = slide. */
@Composable
private fun FlagColumn(label: String, bit: Int, view: RollView, color: Color, selected: Set<Int>) {
    val seq by Seq.data
    val sel by rememberUpdatedState(selected)
    val keysH = 36.dp
    Canvas(
        Modifier.width(40.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(C.surface)
            .pointerInput(Unit) {
                val y0 = keysH.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!Seq.data.value.pianoRoll) Seq.edit("toRoll")
                    val tickH = (size.height - y0 - END_PAD.toPx()) / view.ticks
                    val notes = Seq.data.value.clip.filter { it.start >= view.start && it.start < view.start + view.ticks }
                    val nearest = notes.minByOrNull { abs(y0 + (it.start - view.start) * tickH - down.position.y) } ?: return@awaitEachGesture
                    if (abs(y0 + (nearest.start - view.start) * tickH - down.position.y) > 24.dp.toPx()) return@awaitEachGesture
                    val tol = maxOf(1, Seq.data.value.stepTicks / 16)
                    val group = if (nearest.id in sel) notes.filter { it.id in sel } else notes.filter { abs(it.start - nearest.start) <= tol }
                    val on = nearest.flags and bit == 0
                    val arr = JSONArray()
                    group.forEach { arr.put(JSONObject().put("id", it.id).put("flags", if (on) it.flags or bit else it.flags and bit.inv())) }
                    Seq.edit("clipEditMany", "notes" to arr)
                }
            },
    ) {
        val y0 = keysH.toPx()
        val tickH = (size.height - y0 - END_PAD.toPx()) / view.ticks
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Tokens.FAINT; textSize = 12.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER }
        drawContext.canvas.nativeCanvas.drawText(label, size.width / 2, y0 - 12.dp.toPx(), paint)
        seq.rollNotes.filter { it.start >= view.start && it.start < view.start + view.ticks }.forEach { n ->
            val c = Offset(size.width / 2, y0 + (n.start - view.start) * tickH + 6f)
            drawCircle(if (n.flags and bit != 0) color else C.control, 7.dp.toPx(), c)
            if (n.id in selected) drawCircle(C.text, 8.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** The full-screen button: four corners, drawn (a glyph sat off centre). */
@Composable
private fun ExpandButton(onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(C.control).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) {
            val w = size.width; val k = w * 0.38f; val sw = 1.8.dp.toPx()
            for ((cx, cy) in listOf(0f to 0f, w to 0f, 0f to w, w to w)) {
                val dx = if (cx == 0f) k else -k; val dy = if (cy == 0f) k else -k
                drawLine(C.quiet, Offset(cx, cy), Offset(cx + dx, cy), sw)
                drawLine(C.quiet, Offset(cx, cy), Offset(cx, cy + dy), sw)
            }
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
