package com.pastel.minibox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pastel.minibox.state.App
import com.pastel.minibox.state.Seq

/** A step note's length on one bar: its first half 5–100 % of a step (a gate), the rest whole steps, 2 to 64 (a note
    ringing over the steps after it, up to 4 bars at 1/16). Lengths are in 1/128 step. */
private val LONG_STEPS = listOf(2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64)
fun lengthToBar(len: Int): Float =
    if (len <= 128) len / 256f
    else 0.5f + 0.5f * (LONG_STEPS.indexOfFirst { it * 128 >= len }.let { if (it < 0) LONG_STEPS.size - 1 else it } + 1) / LONG_STEPS.size
fun barToLength(v: Float): Int =
    if (v <= 0.5f) (v * 256f).toInt().coerceIn(6, 128)
    else LONG_STEPS[(kotlin.math.ceil((v - 0.5f) * 2f * LONG_STEPS.size).toInt() - 1).coerceIn(0, LONG_STEPS.size - 1)] * 128
fun lengthText(len: Int): String = if (len <= 128) "${len * 100 / 128}%" else {
    val st = len / 128f
    if (st == st.toInt().toFloat()) "${st.toInt()} steps" else "%.1f steps".format(st)
}

/** Long-press a step (mockup 06): its velocity, gate, timing, chance, ratchet, accent / slide, and the macros' locks
    on this step (turn one to lock it, double-tap to clear). `lane` ≥ 0: a drum part's step. */
@Composable
fun StepSheet(step: Int, lane: Int, done: () -> Unit) {
    val seq by Seq.data
    val color = C.track(App.focusTrack.intValue)
    var locks by remember(step) { mutableStateOf(if (lane < 0) Seq.locks(step) else emptyList()) }
    Column {
        if (lane >= 0) {
            val cell = seq.lanes.getOrNull(lane)?.get(step) ?: run { done(); return }
            val name = App.rack.value[App.focusTrack.intValue].lanes.getOrNull(lane)?.name ?: ""
            SheetTitle("Step ${step + 1}", name)
            BarSlider(cell.velocity / 127f, color, "Velocity", "${cell.velocity}",
                { Seq.edit("laneSet", "lane" to lane, "step" to step, "key" to "velocity", "value" to it) })
            Spacer(Modifier.height(8.dp))
            BarSlider((cell.micro + 24) / 48f, color, "Micro", "${cell.micro}",
                { Seq.edit("laneSet", "lane" to lane, "step" to step, "key" to "micro", "value" to (it * 48 - 24).toInt()) })
            Spacer(Modifier.height(16.dp))
            Chance(cell.probability, color) { Seq.edit("laneSet", "lane" to lane, "step" to step, "key" to "probability", "value" to it) }
            Ratchet(cell.ratchet, color) { Seq.edit("laneSet", "lane" to lane, "step" to step, "key" to "ratchet", "value" to it) }
            Spacer(Modifier.height(8.dp))
            MenuRow("Clear step") { Seq.edit("laneToggle", "lane" to lane, "step" to step, "on" to false); done() }
            return@Column
        }
        val st = seq.stepList[step] ?: run { done(); return }
        SheetTitle("Step ${step + 1}", st.notes.joinToString(" ") { noteName(it) })
        BarSlider(st.velocity / 127f, color, "Velocity", "${st.velocity}", { Seq.edit("stepSet", "step" to step, "key" to "velocity", "value" to it) })
        Spacer(Modifier.height(8.dp))
        ChordRow(st, color)
        Spacer(Modifier.height(12.dp))
        BarSlider(lengthToBar(st.length), color, "Length", lengthText(st.length),
            { Seq.edit("stepSet", "step" to step, "key" to "gate", "value" to barToLength(it) / 128f) })
        Spacer(Modifier.height(8.dp))
        BarSlider((st.micro + 24) / 48f, color, "Micro", "${st.micro}",
            { Seq.edit("stepSet", "step" to step, "key" to "micro", "value" to (it * 48 - 24).toInt()) })
        Spacer(Modifier.height(16.dp))
        Chance(st.probability, color) { Seq.edit("stepSet", "step" to step, "key" to "probability", "value" to it) }
        Ratchet(st.ratchet, color) { Seq.edit("stepSet", "step" to step, "key" to "ratchet", "value" to it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { ChoicePads(listOf("Accent"), if (st.accent) 0 else -1, color, { Seq.edit("stepSet", "step" to step, "key" to "accent", "value" to !st.accent) }) }
            Box(Modifier.weight(1f)) { ChoicePads(listOf("Slide"), if (st.slide) 0 else -1, color, { Seq.edit("stepSet", "step" to step, "key" to "slide", "value" to !st.slide) }) }
        }
        if (locks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("Locks")
            Spacer(Modifier.height(6.dp))
            for (row in locks.chunked(4)) {
                Row(Modifier.fillMaxWidth()) {
                    for (lk in row) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Knob(lk.value, if (lk.locked) C.pattern else C.faint, lk.name, if (lk.locked) lk.text else "–", { v ->
                            App.call("stepLock", "track" to App.focusTrack.intValue, "step" to step, "macro" to lk.macro, "value" to v)
                            locks = Seq.locks(step)
                        }, size = 36.dp, default = -1f)
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MenuRow("Clear step") { Seq.edit("stepToggle", "step" to step, "note" to -1); done() }
    }
}

@Composable
private fun Chance(value: Int, color: androidx.compose.ui.graphics.Color, onSet: (Int) -> Unit) {
    SectionLabel("Chance")
    Spacer(Modifier.height(6.dp))
    val opts = listOf(100, 75, 50, 25)
    ChoicePads(opts.map { "$it" }, opts.indexOf(value), color, { onSet(opts[it]) })
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Ratchet(value: Int, color: androidx.compose.ui.graphics.Color, onSet: (Int) -> Unit) {
    SectionLabel("Ratchet")
    Spacer(Modifier.height(6.dp))
    ChoicePads(listOf("1", "2", "3", "4"), value - 1, color, { onSet(it + 1) })
    Spacer(Modifier.height(14.dp))
}

/** Chord shapes for a step, in semitones from its lowest note. */
private val CHORDS = listOf(
    "Single" to listOf(0), "Maj" to listOf(0, 4, 7), "Min" to listOf(0, 3, 7), "Dim" to listOf(0, 3, 6),
    "Aug" to listOf(0, 4, 8), "Sus2" to listOf(0, 2, 7), "Sus4" to listOf(0, 5, 7),
    "Maj7" to listOf(0, 4, 7, 11), "7" to listOf(0, 4, 7, 10), "m7" to listOf(0, 3, 7, 10), "mMaj7" to listOf(0, 3, 7, 11),
    "dim7" to listOf(0, 3, 6, 9), "m7♭5" to listOf(0, 3, 6, 10), "Aug7" to listOf(0, 4, 8, 10), "AugMaj7" to listOf(0, 4, 8, 11),
    "7sus2" to listOf(0, 2, 7, 10), "7sus4" to listOf(0, 5, 7, 10),
    "Power" to listOf(0, 7), "Octave" to listOf(0, 12),
)

/** The step's chord: one dropdown (its shape on the lowest note; velocity and length stay). */
@Composable
private fun ChordRow(st: com.pastel.minibox.state.StepData, color: androidx.compose.ui.graphics.Color) {
    val root = st.notes.minOrNull() ?: return
    val shape = st.notes.map { it - root }.sorted()
    val name = CHORDS.firstOrNull { it.second == shape }?.first ?: "${st.notes.size} notes"
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("Chord · ${noteName(root)} $name", Modifier.fillMaxWidth(), caret = true, height = 40.dp) { open = true }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = C.surface) {
            CHORDS.forEach { (n, iv) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text(n, color = if (n == name) color else C.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                    onClick = { open = false; Seq.edit("stepSet", "step" to st.step, "key" to "chord", "value" to org.json.JSONArray(iv)) })
            }
        }
    }
}
