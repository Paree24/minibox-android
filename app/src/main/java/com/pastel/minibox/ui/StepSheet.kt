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
        BarSlider((st.length / 128f).coerceAtMost(1f), color, "Gate", "${(st.length * 100 / 128)}%",
            { Seq.edit("stepSet", "step" to step, "key" to "gate", "value" to it.coerceAtLeast(0.05f)) })
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
