package com.pastel.minibox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pastel.minibox.state.App
import com.pastel.minibox.state.Seq
import com.pastel.minibox.state.SeqData

/** The synth step grid's modes (mockups/png/synthseq_option_a.png): the drum sequencer's shape for synths. */
val GRID_MODES = listOf("Notes", "Length", "Octave", "Accent", "Glide", "Vel")
private const val OCTAVES = 9   // 0 … 8, counted from the scale's root (seq/StepGrid.h)

/** A note's place on the grid (as seq/StepGrid.h): its scale degree (the one below, off the scale) and octave. */
private data class Place(val degree: Int, val octave: Int, val exact: Boolean = true)

private fun placeOf(note: Int, root: Int, iv: List<Int>): Place {
    val rel = note - root
    val oct = (if (rel >= 0) rel / 12 else (rel - 11) / 12) - 1
    val pc = rel - 12 * (oct + 1)
    var d = 0
    for (i in iv.indices) if (iv[i] <= pc) d = i
    return Place(d, oct, iv[d] == pc)
}

private fun lengthSteps(len: Int) = maxOf(1, (len + 127) / 128)

/** The octave a new note on an empty step takes: the line's nearest note before it (else after), else the track's. */
private fun defaultOctave(seq: SeqData, step: Int, root: Int, iv: List<Int>): Int {
    val before = seq.stepList.keys.filter { it < step }.maxOrNull()
    val after = seq.stepList.keys.filter { it > step }.minOrNull()
    val s = before ?: after
    if (s != null) seq.stepList[s]?.notes?.minOrNull()?.let { return placeOf(it, root, iv).octave }
    val track = App.focusTrack.intValue
    val type = App.rack.value.getOrNull(track)?.type ?: ""
    return ((if (type == "mono" || type == "acid") 2 else 3) + (App.octave.value.getOrNull(track) ?: 0)).coerceIn(0, OCTAVES - 1)
}

/**
 * Steps down, the scale's notes across (or, in Octave, the octaves 0-8 across). One gesture for the whole grid, as
 * the drums: a tap edits a cell, a slide paints the cells it passes (in the column it started in), a long-press
 * opens the step's sheet. Notes: tap a note on / off. Length: drag a note's end (or tap below it). Octave: the
 * step's octave. Accent, Glide: tap or slide over the notes.
 */
@Composable
fun SynthGrid(bar: Int, spb: Int, color: Color, mode: Int, openStep: (Int) -> Unit) {
    val seq by Seq.data
    val fast by App.fast
    val project by App.project
    val root = project.scaleRoot
    val iv = project.scaleIntervals.ifEmpty { listOf(0, 2, 4, 5, 7, 9, 11) }
    val octaveMode = mode == 2
    val cols = if (octaveMode) OCTAVES else iv.size
    val seqNow by rememberUpdatedState(seq)
    val openNow by rememberUpdatedState(openStep)
    val scaleNow by rememberUpdatedState(root to iv)
    val names = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    val first = bar * spb
    // what each step in view holds: its places, its octave, its length; the steps a longer note still sounds over
    val placesAt = (first until first + spb).associateWith { s -> seq.stepList[s]?.notes?.map { placeOf(it, root, iv) } ?: emptyList() }
    fun heldBy(s: Int): Int? = seq.stepList.keys.filter { it < s }.firstOrNull { t -> t + lengthSteps(seq.stepList[t]!!.length) > s }

    Column(Modifier.fillMaxSize()) {
        if (octaveMode) {
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("OCTAVE", color = C.faint, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                // every step an octave down / up
                Pill("‹ All", height = 26.dp) { Seq.edit("gridShift", "dir" to -1) }
                Spacer(Modifier.width(6.dp))
                Pill("All ›", height = 26.dp) { Seq.edit("gridShift", "dir" to 1) }
            }
            Spacer(Modifier.height(4.dp))
        }
        // column heads: the scale's notes (the root tinted), or the octaves (those in use tinted)
        val used = if (octaveMode) seq.stepList.values.mapNotNull { st -> st.notes.minOrNull()?.let { placeOf(it, root, iv).octave } }.toSet() else emptySet()
        Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 30.dp)) {
            for (c in 0 until cols) {
                val tint = if (octaveMode) c in used else c == 0
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (tint) color.copy(alpha = 0.22f) else Color.Transparent), contentAlignment = Alignment.Center) {
                    Text(if (octaveMode) "$c" else names[(root + iv[c]) % 12], color = if (tint) color else C.quiet,
                        fontSize = if (octaveMode) 11.sp else 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.surface).padding(vertical = 2.dp)
            .pointerInput(bar, spb, mode) {
                val labelW = 30.dp.toPx()
                fun rowAt(y: Float) = (y / (size.height / spb.toFloat())).toInt().coerceIn(0, spb - 1)
                fun colAt(x: Float) = ((x - labelW) / ((size.width - labelW) / cols)).toInt()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (down.position.x < labelW) return@awaitEachGesture
                    val c0 = colAt(down.position.x).coerceIn(0, cols - 1)
                    val s0 = first + rowAt(down.position.y)
                    if (s0 >= seqNow.steps) return@awaitEachGesture
                    val (rt, scale) = scaleNow
                    var moved = false
                    val longPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true; break }
                        }
                    } == null
                    if (longPress && !moved) {   // held still: the step's sheet (velocity, chance, ratchet, locks …)
                        if (seqNow.stepList[s0] != null) openNow(s0)
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    // what the first cell does decides the stroke (one undo step for all of it)
                    var count = 0
                    fun more() = count++ > 0
                    val st0 = seqNow.stepList[s0]
                    val strokeOn: Boolean
                    var lengthFrom = -1
                    when (mode) {
                        0 -> {
                            val there = st0?.notes?.any { placeOf(it, rt, scale).degree == c0 } == true
                            strokeOn = !there
                        }
                        1 -> {   // the note this column's stroke stretches: at or above the finger
                            strokeOn = true
                            lengthFrom = seqNow.stepList.keys.filter { it <= s0 && it >= first }
                                .filter { t -> seqNow.stepList[t]!!.notes.any { placeOf(it, rt, scale).degree == c0 } }.maxOrNull() ?: -1
                        }
                        3 -> strokeOn = !(st0?.accent ?: false)
                        4 -> strokeOn = !(st0?.slide ?: false)
                        else -> strokeOn = true
                    }
                    val done = mutableSetOf<Int>()
                    fun apply(s: Int) {
                        if (s >= seqNow.steps) return
                        val st = seqNow.stepList[s]
                        when (mode) {
                            0 -> { if (done.add(s)) Seq.edit("grid", "step" to s, "degree" to c0, "on" to strokeOn,
                                "octave" to defaultOctave(seqNow, s, rt, scale), "more" to more()) }
                            1 -> if (lengthFrom >= 0) Seq.edit("gridLength", "step" to lengthFrom, "steps" to (s - lengthFrom + 1).coerceAtLeast(1), "more" to more())
                            2 -> if (st != null && done.add(s)) Seq.edit("gridOctave", "step" to s, "octave" to c0, "more" to more())
                            3 -> if (st != null && done.add(s)) Seq.edit("gridFlag", "step" to s, "key" to "accent", "value" to strokeOn, "more" to more())
                            4 -> if (st != null && done.add(s)) Seq.edit("gridFlag", "step" to s, "key" to "glide", "value" to strokeOn, "more" to more())
                        }
                    }
                    var lastRow = s0
                    apply(s0)
                    if (!moved) return@awaitEachGesture
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        val s = first + rowAt(ch.position.y)   // the column stays the one the stroke started in
                        if (s != lastRow) { lastRow = s; apply(s) }
                    }
                }
            }) {
            Column(Modifier.fillMaxSize()) {
                for (i in 0 until spb) {
                    val s = first + i
                    if (s >= seq.steps) { Spacer(Modifier.weight(1f)); continue }
                    val st = seq.stepList[s]
                    val places = placesAt[s] ?: emptyList()
                    val held = if (st == null) heldBy(s) else null
                    val heldPlaces = held?.let { t -> seq.stepList[t]!!.notes.map { placeOf(it, root, iv) } } ?: emptyList()
                    val playing = fast.playing && fast.step == s && fast.activePattern == fast.focusPattern
                    Row(Modifier.weight(1f).fillMaxWidth().background(if ((i / 4) % 2 == 1) Color(0xFF23262D) else Color.Transparent)
                        .then(if (playing) Modifier.border(1.5.dp, C.pattern, RoundedCornerShape(8.dp)) else Modifier),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${s + 1}", color = if (i % 4 == 0) C.quiet else C.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(30.dp).padding(start = 6.dp))
                        for (c in 0 until cols) {
                            val lit: Float = when {
                                octaveMode && st != null -> if (places.minByOrNull { it.octave * 100 + it.degree }?.octave == c) 1f else 0f
                                octaveMode -> if (heldPlaces.minByOrNull { it.octave * 100 + it.degree }?.octave == c) 0.3f else 0f
                                st != null && places.any { it.degree == c } -> when (mode) {
                                    3 -> if (st.accent) 1f else 0.3f
                                    4 -> if (st.slide) 1f else 0.3f
                                    else -> if (st.accent) 1f else 0.75f
                                }
                                heldPlaces.any { it.degree == c } -> if (mode == 3 || mode == 4) 0.15f else 0.45f
                                else -> 0f
                            }
                            val empty = if (octaveMode && st == null) 0.18f else 0.55f
                            // a note off the scale sits on the degree below it, outlined (its name says what it is)
                            val off = !octaveMode && st != null && places.any { it.degree == c && !it.exact }
                            Box(Modifier.weight(1f).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(7.dp))
                                .background(if (lit > 0f) color.copy(alpha = lit) else C.control.copy(alpha = empty))
                                .then(if (off) Modifier.border(1.5.dp, C.text.copy(alpha = 0.7f), RoundedCornerShape(7.dp)) else Modifier),
                                contentAlignment = Alignment.CenterStart) {
                                // the note itself, on its cell (Notes, Length); A on accented ones (Accent)
                                if (!octaveMode && st != null && lit > 0f && mode <= 1) {
                                    val n = st.notes.firstOrNull { placeOf(it, root, iv).degree == c }
                                    if (n != null) Text(noteName(n), color = C.bg, fontSize = 10.sp, fontWeight = FontWeight.Black,
                                        maxLines = 1, modifier = Modifier.padding(start = 5.dp))
                                }
                                if (mode == 3 && st != null && st.accent && lit > 0f)
                                    Text("A", color = C.bg, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
            // over the cells: accent dots and glides (Notes, Glide), the length of each note (Length)
            if (!octaveMode && mode != 5) Canvas(Modifier.fillMaxSize()) {
                val labelW = 30.dp.toPx()
                val rh = size.height / spb
                val cw = (size.width - labelW) / cols
                val inset = 3.dp.toPx()
                fun cellX(c: Int) = labelW + c * cw + inset
                fun cellY(i: Int) = i * rh + inset
                val keys = seq.stepList.keys.sorted()
                for (s in keys) {
                    val i = s - first
                    if (i < 0 || i >= spb) continue
                    val st = seq.stepList[s]!!
                    val ps = st.notes.map { placeOf(it, root, iv) }
                    val lowest = ps.minByOrNull { it.octave * 100 + it.degree } ?: continue
                    val len = lengthSteps(st.length)
                    val x = cellX(lowest.degree)
                    val w = cw - 2 * inset
                    val bottom = (i + len).coerceAtMost(spb) * rh - inset
                    if (mode == 1) {   // the grip at the note's end, and its length in steps
                        drawLine(C.bg.copy(alpha = 0.8f), Offset(x + w / 2 - 8.dp.toPx(), bottom - 6.dp.toPx()), Offset(x + w / 2 + 8.dp.toPx(), bottom - 6.dp.toPx()),
                            3.dp.toPx(), StrokeCap.Round)
                    }
                    if (mode == 0 && st.accent) drawCircle(C.bg, 3.dp.toPx(), Offset(x + w - 7.dp.toPx(), cellY(i) + 7.dp.toPx()))
                    if ((mode == 0 || mode == 4) && st.slide) {
                        // the glide mark, and the line into the next note
                        val gx = x + w - 18.dp.toPx(); val gy = cellY(i) + rh - 2 * inset - 6.dp.toPx()
                        val mark = Path().apply { moveTo(gx, gy + 4.dp.toPx()); quadraticTo(gx + 5.dp.toPx(), gy - 5.dp.toPx(), gx + 11.dp.toPx(), gy - 5.dp.toPx()) }
                        drawPath(mark, C.bg, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
                        val nxt = keys.firstOrNull { it > s }
                        if (nxt != null && nxt - first < spb) {
                            val np = seq.stepList[nxt]!!.notes.map { placeOf(it, root, iv) }.minByOrNull { it.octave * 100 + it.degree }
                            if (np != null) {
                                val x1 = x + w / 2; val y1 = bottom
                                val x2 = cellX(np.degree) + w / 2; val y2 = cellY(nxt - first)
                                val p = Path().apply { moveTo(x1, y1); cubicTo(x1, (y1 + y2) / 2, x2, (y1 + y2) / 2, x2, y2) }
                                drawPath(p, color.copy(alpha = 0.9f), style = Stroke((if (mode == 4) 2.4f else 2f) * density, cap = StrokeCap.Round))
                            }
                        }
                    }
                }
            }
        }
    }
}
