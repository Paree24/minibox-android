package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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

/** Seq (mockups 04, 05, 15, 16): the step editor or the piano roll, full height, with the pads in a drawer. */
@Composable
fun SeqTab(openStep: (step: Int, lane: Int) -> Unit) {
    val track = App.focusTrack.intValue
    val fast by App.fast
    val seq by Seq.data
    val rack by App.rack
    var view by remember { mutableIntStateOf(0) }          // 0 Steps, 1 Roll
    var pickedBar by remember { mutableIntStateOf(0) }
    var drawer by remember { mutableStateOf(false) }
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
    // the view follows the playhead only while recording a take: when editing by hand it stays where it was put,
    // so a tap lands where the finger is (a following roll turned deletes into new notes)
    val bar = if (fast.playing && fast.recording && fast.activePattern == fast.focusPattern) (fast.step / perPage).coerceIn(0, pageCount - 1) else pickedBar.coerceIn(0, pageCount - 1)
    var lengthMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!seq.drum) {
                Segmented(listOf("Steps", "Roll"), if (roll) 1 else 0, C.text, { if (!seq.pianoRoll) view = it }, Modifier.width(116.dp))
                Spacer(Modifier.width(8.dp))
            }
            // the bar in view: ‹ 2 / 4 ›
            Row(Modifier.height(34.dp).clip(CircleShape).background(C.control), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp, 34.dp).clickable(enabled = bar > 0) { pickedBar = bar - 1 }, contentAlignment = Alignment.Center) {
                    Text("‹", color = if (bar > 0) C.text else C.faint, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
                // 1/32 steps: "1a" and "1b" are the halves of bar 1
                val half = !roll && barSteps > spb
                Text(if (half) "${bar / 2 + 1}${"ab"[bar % 2]}/${seq.bars}" else "${bar + 1}/$pageCount", color = C.pattern, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Box(Modifier.size(30.dp, 34.dp).clickable(enabled = bar < pageCount - 1) { pickedBar = bar + 1 }, contentAlignment = Alignment.Center) {
                    Text("›", color = if (bar < pageCount - 1) C.text else C.faint, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.weight(1f))
            val lengths = listOf(1, 2, 4, 8, 16).filter { it <= seq.maxBars }
            // the length and the step resolution, in one dropdown
            Box {
                Pill("${seq.bars} bar${if (seq.bars > 1) "s" else ""} · ${RES_NAMES[seq.stepRes.coerceIn(0, 2)]}", caret = true) { lengthMenu = true }
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
                }
            }
            // undo / redo every sequence edit (steps, drum cells, the piano roll, the length)
            Spacer(Modifier.width(6.dp))
            RoundIcon("↶", seq.canUndo) { Seq.edit("undo") }
            Spacer(Modifier.width(4.dp))
            RoundIcon("↷", seq.canRedo) { Seq.edit("redo") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            when {
                seq.drum -> DrumEditor(bar, spb, part, color, { part = it }, { s -> openStep(s, part) })
                roll -> PianoRoll(bar, color)
                else -> StepEditor(bar, spb, color) { s -> openStep(s, -1) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (drawer) {
            Box(Modifier.fillMaxWidth().clickable { drawer = false }.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 4.dp).clip(CircleShape).background(C.faint))
            }
            Pads()
        } else PadsHandle(false) { drawer = true }
        Spacer(Modifier.height(12.dp))
    }
}

private val RES_NAMES = listOf("1/8", "1/16", "1/32")

/** The note a tap on an empty step writes: a held pad's, else the last pad played. */
private fun inputNote(): Int {
    val n = App.heldNotes.value.firstOrNull() ?: App.lastNote.intValue
    val p = App.project.value
    // in key, as everything else in Seq: a note off the scale moves down onto it
    return (n downTo maxOf(0, n - 11)).firstOrNull { ((it - p.scaleRoot) % 12 + 12) % 12 in p.scaleIntervals } ?: n
}

/** Synth steps as rows (time runs down): on, note, velocity, gate, chance, ratchet, accent, slide. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepEditor(bar: Int, spb: Int, color: Color, openStep: (Int) -> Unit) {
    val seq by Seq.data
    val fast by App.fast
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(C.surface)) {
        Row(Modifier.fillMaxWidth().height(22.dp).padding(start = 70.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Head("Note", 54.dp); Head("Vel", 0.dp, Modifier.weight(1.2f)); Head("Gate", 0.dp, Modifier.weight(1f))
            Head("%", 34.dp); Head("×", 22.dp); Head("A", 22.dp); Head("S", 22.dp)
        }
        for (i in 0 until spb) {
            val s = bar * spb + i
            if (s >= seq.steps) { Spacer(Modifier.weight(1f)); continue }   // an odd meter's last half bar
            val st = seq.stepList[s]
            val playing = fast.playing && fast.step == s && fast.activePattern == fast.focusPattern
            Row(
                Modifier.weight(1f).fillMaxWidth()
                    .background(if ((i / 4) % 2 == 1) Color(0xFF23262D) else Color.Transparent)
                    .then(if (playing) Modifier.border(1.5.dp, C.pattern, RoundedCornerShape(8.dp)) else Modifier)
                    .combinedClickable(onClick = {}, onLongClick = { if (st != null) openStep(s) })
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${s + 1}", color = if (i % 4 == 0) C.quiet else C.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(26.dp))
                Box(Modifier.size(26.dp, 24.dp).clip(RoundedCornerShape(7.dp)).background(if (st != null) color else C.control)
                    .clickable { Seq.edit("stepToggle", "step" to s, "note" to if (st != null) -1 else inputNote()) })
                Spacer(Modifier.width(10.dp))
                if (st == null) {
                    Text("–", color = C.faint, fontSize = 12.sp, modifier = Modifier.width(54.dp))
                    Spacer(Modifier.weight(2.2f))
                } else {
                    NotePill(st, color)
                    DragBar(st.velocity / 127f, color, Modifier.weight(1.2f)) { Seq.edit("stepSet", "step" to s, "key" to "velocity", "value" to it) }
                    Spacer(Modifier.width(6.dp))
                    DragBar((st.length / 128f).coerceAtMost(1f), color, Modifier.weight(1f)) {
                        Seq.edit("stepSet", "step" to s, "key" to "gate", "value" to it.coerceAtLeast(0.05f))
                    }
                }
                if (st != null) {
                    TapText("${st.probability}", st.probability < 100, 34.dp) {
                        val next = listOf(100, 75, 50, 25).let { l -> l[(l.indexOf(st.probability).coerceAtLeast(0) + 1) % l.size] }
                        Seq.edit("stepSet", "step" to s, "key" to "probability", "value" to next)
                    }
                    TapText("${st.ratchet}", st.ratchet > 1, 22.dp) { Seq.edit("stepSet", "step" to s, "key" to "ratchet", "value" to st.ratchet % 4 + 1) }
                    Box(Modifier.size(22.dp).clickable { Seq.edit("stepSet", "step" to s, "key" to "accent", "value" to !st.accent) },
                        contentAlignment = Alignment.Center) { Box(Modifier.size(10.dp).clip(CircleShape).background(if (st.accent) color else C.control)) }
                    Box(Modifier.size(22.dp).clickable { Seq.edit("stepSet", "step" to s, "key" to "slide", "value" to !st.slide) },
                        contentAlignment = Alignment.Center) { Text("↗", color = if (st.slide) color else C.faint, fontSize = 13.sp, fontWeight = FontWeight.Black) }
                } else Spacer(Modifier.width(100.dp))
            }
        }
    }
}

@Composable
private fun Head(text: String, width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = C.faint, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
        modifier = if (width > 0.dp) modifier.width(width) else modifier)
}

@Composable
private fun TapText(text: String, strong: Boolean, width: androidx.compose.ui.unit.Dp, onTap: () -> Unit) {
    Box(Modifier.width(width).fillMaxHeight().clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Text(text, color = if (strong) C.text else C.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The step's note: drag up or down to change it, a note of the project scale per notch (in key, as the pads and the
    piano roll); a note off the scale moves onto it. A chord shows its size. */
@Composable
private fun NotePill(st: StepData, color: Color) {
    val project by App.project
    val inKey = remember(project.scaleRoot, project.scaleIntervals) {
        (0..127).filter { ((it - project.scaleRoot) % 12 + 12) % 12 in project.scaleIntervals }
    }
    var drag by remember(st) { mutableFloatStateOf(0f) }
    fun moved(d: Float): Int {
        val first = st.notes.first()
        val row = inKey.indexOfLast { it <= first }.coerceAtLeast(0)
        val steps = (d / 28f).toInt()
        return if (steps == 0) first else inKey[(row + steps).coerceIn(0, inKey.size - 1)]
    }
    val shown = moved(drag)
    Box(
        Modifier.width(54.dp).height(24.dp).padding(end = 6.dp).clip(CircleShape).background(C.bg)
            .pointerInput(st, inKey) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        val n = moved(drag)
                        drag = 0f
                        if (n != st.notes.first()) Seq.edit("stepSet", "step" to st.step, "key" to "note", "value" to n)
                    },
                ) { c, dy -> c.consume(); drag -= dy }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(noteName(shown) + if (st.notes.size > 1) "+${st.notes.size - 1}" else "", color = color, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}

/** A small horizontal bar dragged sideways; the edit is written when the finger lifts (one undo step). */
@Composable
private fun DragBar(value: Float, color: Color, modifier: Modifier, onCommit: (Float) -> Unit) {
    var drag by remember { mutableFloatStateOf(-1f) }
    val shown = if (drag >= 0f) drag else value
    Box(
        modifier.height(24.dp).pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { val v = drag; drag = -1f; if (v >= 0f) onCommit(v) },
                onDragCancel = { drag = -1f },
            ) { c, _ -> c.consume(); drag = (c.position.x / size.width).coerceIn(0f, 1f) }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(C.control))
        Box(Modifier.fillMaxWidth(shown.coerceIn(0.02f, 1f)).height(8.dp).clip(CircleShape).background(color.copy(alpha = 0.9f)))
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
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.surface).padding(vertical = 2.dp)) {
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
                                .background(if (cell != null) color.copy(alpha = 0.45f + 0.55f * cell.velocity / 127f) else C.control.copy(alpha = 0.6f))
                                .combinedClickable(
                                    onClick = { onPart(c); Seq.edit("laneToggle", "lane" to c, "step" to s) },
                                    onLongClick = { if (cell != null) { onPart(c); openStep(s) } },
                                ),
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
