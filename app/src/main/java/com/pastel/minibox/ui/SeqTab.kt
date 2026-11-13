package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.systemGestureExclusion
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pastel.minibox.state.App
import com.pastel.minibox.state.Seq
import com.pastel.minibox.state.StepData
import kotlinx.coroutines.delay

/** Seq (mockups 04, 05, 15, 16): the step editor or the piano roll, the whole page (no pads). */
@Composable
fun SeqTab(openStep: (step: Int, lane: Int) -> Unit, openArp: () -> Unit = {}) {
    val track = App.focusTrack.intValue
    val fast by App.fast
    val seq by Seq.data
    val rack by App.rack
    var view by remember { mutableIntStateOf(0) }          // 0 Steps, 1 Roll
    var pickedBar by remember { mutableIntStateOf(0) }
    var part by remember { mutableIntStateOf(0) }
    val color = C.track(track)

    LaunchedEffect(track, fast.focusPattern) { Seq.refresh() }
    LaunchedEffect(fast.playing, fast.recording) {   // a take writes notes while it plays: show them
        while (fast.playing && fast.recording) { delay(300); Seq.refresh() }
    }
    if (rack[track].empty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("+", color = C.faint, fontSize = 40.sp) }
        return
    }
    val barSteps = seq.stepsPerBar.coerceAtLeast(1)
    // the step editors show up to 16 steps (1/32: half a bar a page, so nothing scrolls); the roll shows bars
    val spb = minOf(barSteps, 16)
    val pages = ((seq.bars * barSteps + spb - 1) / spb).coerceAtLeast(1)
    val roll = !seq.drum && (view == 1 || seq.pianoRoll)
    val perPage = if (roll) barSteps else spb
    val pageCount = if (roll) seq.bars else pages
    // the view never follows the playhead: it stays where it was put, so a tap lands where the finger is
    val bar = pickedBar.coerceIn(0, pageCount - 1)
    var lengthMenu by remember { mutableStateOf(false) }
    var velPage by remember { mutableStateOf(false) }
    var gridMode by remember { mutableIntStateOf(0) }   // synth steps: Notes, Length, Octave, Accent, Glide, Vel

    // layout C on trial: the roll over everything, with its own slim bar
    if (roll && RollUi.focus.value) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
            PianoRoll(color, openArp = openArp) {
                Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoundIcon("‹") { RollUi.focus.value = false }
                    RoundIcon(if (fast.playing) "■" else "▶") { App.cmd(if (fast.playing) "stop" else "play") }
                    Box(Modifier.height(32.dp).clip(CircleShape).background(color).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text("${track + 1} · ${rack[track].name}", color = C.bg, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    RoundIcon("↶", seq.canUndo) { Seq.edit("undo") }
                    RoundIcon("↷", seq.canRedo) { Seq.edit("redo") }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // the header: undo / redo keep their size; on a narrow phone the length pill drops the step resolution (it is
        // in its menu) rather than pushing redo off the edge
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = !seq.drum && !roll && maxWidth < 400.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!seq.drum) {
                val context = androidx.compose.ui.platform.LocalContext.current
                Segmented(listOf("Steps", "Roll"), if (roll) 1 else 0, C.text, {
                    // Steps on a clip: its notes go back to steps (each to its nearest); one steps can't hold says why
                    if (it == 0 && seq.pianoRoll) {
                        val r = org.json.JSONObject(Seq.edit("toSteps").ifEmpty { "{}" })
                        if (r.optBoolean("ok")) view = 0
                        else android.widget.Toast.makeText(context, "Can't show as steps: " + r.optString("why"), android.widget.Toast.LENGTH_LONG).show()
                    } else view = it
                }, Modifier.width(96.dp))
                Spacer(Modifier.width(6.dp))
            }
            // the bar in view: ‹ 2 / 4 › (the roll scrolls and zooms by itself)
            if (!roll) Row(Modifier.height(34.dp).clip(CircleShape).background(C.control), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp, 34.dp).clickable(enabled = bar > 0) { pickedBar = bar - 1 }, contentAlignment = Alignment.Center) {
                    Text("‹", color = if (bar > 0) C.text else C.faint, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
                // 1/32 steps: "1a" and "1b" are the halves of bar 1
                val half = !roll && barSteps > spb
                Text(if (half) "${bar / 2 + 1}${"ab"[bar % 2]}/${seq.bars}" else "${bar + 1}/$pageCount", color = C.pattern, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Box(Modifier.size(26.dp, 34.dp).clickable(enabled = bar < pageCount - 1) { pickedBar = bar + 1 }, contentAlignment = Alignment.Center) {
                    Text("›", color = if (bar < pageCount - 1) C.text else C.faint, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
            }
            val lengths = listOf(1, 2, 4, 8, 16).filter { it <= seq.maxBars }
            // the length and the step resolution, in one dropdown (it takes the room the others leave, at the right)
            Box(Modifier.weight(1f).padding(start = 4.dp), contentAlignment = Alignment.CenterEnd) { Box {
                val barsText = "${seq.bars} bar${if (seq.bars > 1) "s" else ""}"
                Pill(if (narrow) barsText else "$barsText · ${RES_NAMES[seq.stepRes.coerceIn(0, 2)]}", caret = true, padding = 10.dp) { lengthMenu = true }
                DropdownMenu(lengthMenu, { lengthMenu = false }, containerColor = C.surface) {
                    lengths.forEach { n ->
                        DropdownMenuItem(text = { Text(if (n == 1) "1 bar" else "$n bars", color = if (n == seq.bars) C.pattern else C.text, fontWeight = FontWeight.Bold) },
                            onClick = { lengthMenu = false; Seq.edit("setLength", "bars" to n) })
                    }
                    HorizontalDivider(color = C.divider)
                    RES_NAMES.forEachIndexed { i, r ->
                        DropdownMenuItem(text = { Text("Steps $r", color = if (i == seq.stepRes) C.pattern else C.text, fontWeight = FontWeight.Bold) },
                            onClick = { lengthMenu = false; pickedBar = 0; Seq.edit("setRes", "res" to i) })
                    }
                    HorizontalDivider(color = C.divider)
                    // this track's notes in this pattern, gone (undo brings them back)
                    DropdownMenuItem(text = { Text("Clear this track", color = C.record, fontWeight = FontWeight.Bold) },
                        onClick = { lengthMenu = false; Seq.edit("clearTrack") })
                }
            } }
            // undo / redo every sequence edit (steps, drum cells, the piano roll, the length)
            Spacer(Modifier.width(6.dp))
            RoundIcon("↶", seq.canUndo) { Seq.edit("undo") }
            Spacer(Modifier.width(4.dp))
            RoundIcon("↷", seq.canRedo) { Seq.edit("redo") }
        }
        }
        // the step editors: the steps, or their velocities (the roll has its own Velocity tab)
        if (!roll && seq.drum) {
            Segmented(listOf("Hits", "Velocity"), if (velPage) 1 else 0, C.text, { velPage = it == 1 },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), height = 32.dp)
            Spacer(Modifier.height(6.dp))
        } else if (!roll) {
            // the synth step grid's modes (as the drums' Hits / Velocity)
            Segmented(GRID_MODES, gridMode, C.text, { gridMode = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), height = 32.dp)
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            when {
                roll -> PianoRoll(color, openArp = openArp)
                seq.drum && velPage -> StepVelocity(bar, spb, color, true, part) { part = it }
                seq.drum -> DrumEditor(bar, spb, part, color, { part = it }, { s -> openStep(s, part) })
                gridMode == 5 -> StepVelocity(bar, spb, color, false, part) { part = it }
                else -> SynthGrid(bar, spb, color, gridMode) { s -> openStep(s, -1) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private val RES_NAMES = listOf("1/8", "1/16", "1/32")

/** The drum parts as chips (the one in view, or being edited, lit). */
@Composable
private fun PartChips(part: Int, color: Color, onPart: (Int) -> Unit) {
    val rack by App.rack
    val lanes = rack[App.focusTrack.intValue].lanes
    Row(Modifier.fillMaxWidth().height(26.dp).padding(start = 30.dp)) {
        for (c in 0 until 8) {
            val on = c == part
            Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp).clip(RoundedCornerShape(8.dp))
                .background(if (on) color else Color.Transparent).clickable { onPart(c) }, contentAlignment = Alignment.Center) {
                Text(lanes.getOrNull(c)?.name?.replace("Cymbal", "Cym")?.replace("C Hat", "CH")?.replace("O Hat", "OH") ?: "",
                    color = if (on) C.bg else C.quiet, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
        }
    }
}

/** Velocity, a page of steps (time runs down, as the editors): each step that plays a bar across the row; tap or
    drag along it to set the velocity. Drums: the part chosen above. Randomize ±N % moves every step of the pattern
    (that part's, for drums) up to that much up or down, as one undo step. */
@Composable
private fun StepVelocity(bar: Int, spb: Int, color: Color, drum: Boolean, part: Int, onPart: (Int) -> Unit) {
    val seq by Seq.data
    val fast by App.fast
    var amount by remember { mutableIntStateOf(1) }
    val amounts = listOf(10, 20, 30, 50)
    fun velOf(s: Int): Int? = if (drum) seq.lanes.getOrNull(part)?.get(s)?.velocity else seq.stepList[s]?.takeIf { it.notes.isNotEmpty() }?.velocity
    fun set(s: Int, v: Float) {
        if (drum) Seq.edit("laneSet", "lane" to part, "step" to s, "key" to "velocity", "value" to v)
        else Seq.edit("stepSet", "step" to s, "key" to "velocity", "value" to v)
    }
    Column(Modifier.fillMaxSize()) {
        if (drum) { PartChips(part, color, onPart); Spacer(Modifier.height(4.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Randomize", color = C.quiet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Segmented(amounts.map { "±$it%" }, amount, C.text, { amount = it }, Modifier.weight(1f), height = 32.dp)
            Spacer(Modifier.width(6.dp))
            Pill("Apply", on = true, color = color, height = 32.dp) {
                val r = java.util.Random()
                val items = org.json.JSONArray()
                for (s in 0 until seq.steps) velOf(s)?.let { v ->
                    val nv = (v * (1.0 + amounts[amount] / 100.0 * (2.0 * r.nextDouble() - 1.0))).roundToInt().coerceIn(1, 127)
                    items.put(org.json.JSONObject().put("step", s).put("velocity", nv))
                }
                Seq.edit("velocityMany", "lane" to (if (drum) part else -1), "items" to items)
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.surface).padding(vertical = 2.dp)) {
            for (i in 0 until spb) {
                val s = bar * spb + i
                if (s >= seq.steps) { Spacer(Modifier.weight(1f)); continue }
                val v = velOf(s)
                val playing = fast.playing && fast.step == s && fast.activePattern == fast.focusPattern
                Row(Modifier.weight(1f).fillMaxWidth().background(if ((i / 4) % 2 == 1) Color(0xFF23262D) else Color.Transparent)
                    .then(if (playing) Modifier.border(1.5.dp, C.pattern, RoundedCornerShape(8.dp)) else Modifier).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${s + 1}", color = if (i % 4 == 0) C.quiet else C.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                    if (v != null) {
                        VelBar(v / 127f, color, Modifier.weight(1f).fillMaxHeight()) { set(s, it) }
                        Text("$v", color = C.quiet, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    } else {
                        Text("–", color = C.faint, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** A velocity bar the height of its row: tap or drag along it; the value is written when the finger lifts. */
@Composable
private fun VelBar(value: Float, color: Color, modifier: Modifier, onCommit: (Float) -> Unit) {
    var drag by remember { mutableFloatStateOf(-1f) }
    val shown = if (drag >= 0f) drag else value
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown()
                drag = (d.position.x / size.width).coerceIn(0.01f, 1f)
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == d.id } ?: break
                    if (!c.pressed) break
                    c.consume()
                    drag = (c.position.x / size.width).coerceIn(0.01f, 1f)
                }
                val v = drag; drag = -1f
                if (v >= 0f) onCommit(v)
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(C.control))
        Box(Modifier.fillMaxWidth(shown.coerceIn(0.02f, 1f)).height(10.dp).clip(CircleShape).background(color.copy(alpha = 0.9f)))
    }
}

/** Drum steps as rows, the 8 parts as columns (mockup 05). Tap a cell, long-press for its settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrumEditor(bar: Int, spb: Int, part: Int, color: Color, onPart: (Int) -> Unit, openStep: (Int) -> Unit) {
    val seq by Seq.data
    val fast by App.fast
    val rack by App.rack
    val lanes = rack[App.focusTrack.intValue].lanes
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(26.dp).padding(start = 30.dp)) {
            for (c in 0 until 8) {
                val on = c == part
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) color else Color.Transparent).clickable { onPart(c) }, contentAlignment = Alignment.Center) {
                    Text(lanes.getOrNull(c)?.name?.replace("Cymbal", "Cym")?.replace("C Hat", "CH")?.replace("O Hat", "OH") ?: "",
                        color = if (on) C.bg else C.quiet, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val seqNow by rememberUpdatedState(seq)
        val partNow by rememberUpdatedState(onPart)
        val openNow by rememberUpdatedState(openStep)
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.surface).padding(vertical = 2.dp)
            // one gesture for the whole grid: a tap toggles a cell, a long-press opens its step, a slide paints the cells
            // it passes (from an empty one) or erases them (from a filled one), in the part it started on
            .pointerInput(bar, spb) {
                val labelW = 30.dp.toPx()
                fun cellAt(o: Offset): Pair<Int, Int>? {
                    val row = (o.y / (size.height / spb.toFloat())).toInt()
                    val col = ((o.x - labelW) / ((size.width - labelW) / 8f)).toInt()
                    if (o.x < labelW || row !in 0 until spb || col !in 0..7) return null
                    val s = bar * spb + row
                    return if (s < seqNow.steps) s to col else null
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val first = cellAt(down.position) ?: return@awaitEachGesture
                    val (s0, c0) = first
                    val paint = seqNow.lanes.getOrNull(c0)?.get(s0) == null   // the first cell decides: paint or erase
                    var moved = false
                    val done = mutableSetOf<Int>()
                    fun apply(s: Int) {
                        if (!done.add(s)) return
                        Seq.edit("laneToggle", "lane" to c0, "step" to s, "on" to paint, "more" to (done.size > 1))
                    }
                    val longPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true; break }
                        }
                    } == null
                    if (longPress && !moved) {   // held still: the step's sheet (a filled cell)
                        partNow(c0)
                        if (seqNow.lanes.getOrNull(c0)?.get(s0) != null) openNow(s0)
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    partNow(c0)
                    apply(s0)   // the tap, or the stroke's first cell
                    if (!moved) return@awaitEachGesture
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        // the column stays the one the stroke started in; the row follows the finger
                        val row = (ch.position.y / (size.height / spb.toFloat())).toInt().coerceIn(0, spb - 1)
                        val s = bar * spb + row
                        if (s < seqNow.steps) apply(s)
                    }
                }
            }) {
            for (i in 0 until spb) {
                val s = bar * spb + i
                if (s >= seq.steps) { Spacer(Modifier.weight(1f)); continue }
                val playing = fast.playing && fast.step == s && fast.activePattern == fast.focusPattern
                Row(
                    Modifier.weight(1f).fillMaxWidth().background(if ((i / 4) % 2 == 1) Color(0xFF23262D) else Color.Transparent)
                        .then(if (playing) Modifier.border(1.5.dp, C.pattern, RoundedCornerShape(8.dp)) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${s + 1}", color = if (i % 4 == 0) C.quiet else C.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(30.dp).padding(start = 6.dp))
                    for (c in 0 until 8) {
                        val cell = seq.lanes.getOrNull(c)?.get(s)
                        Box(
                            Modifier.weight(1f).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(7.dp))
                                .background(if (cell != null) color.copy(alpha = 0.45f + 0.55f * cell.velocity / 127f) else C.control.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            if (cell != null && cell.ratchet > 1) Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                repeat(cell.ratchet) { Box(Modifier.size(3.dp).clip(CircleShape).background(C.bg)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
