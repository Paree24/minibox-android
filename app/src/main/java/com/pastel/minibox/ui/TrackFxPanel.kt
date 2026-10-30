package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pastel.minibox.state.App
import com.pastel.minibox.state.FxChain
import com.pastel.minibox.state.TrackFx

/** An effect chain, one slot at a time: Sound → FX (the device's 3 slots, in series before its level, pan and
    sends) or Mix → Effects (the master's 8, before the bus's glue and limiter). The slot's effect, on / off, its
    place in the chain, mode, mix and controls (knobs, eight to a page). Nothing scrolls. */
@Composable
fun TrackFxPanel(modifier: Modifier = Modifier, chain: FxChain = TrackFx) {
    val track = App.focusTrack.intValue
    val color = if (chain.isMaster) C.pattern else C.track(track)
    val slots by chain.slots
    var sel by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(if (chain.isMaster) -1 else track) { chain.refresh() }
    val s = slots[sel.coerceIn(0, slots.size - 1)]
    Column(modifier) {
        // the slots: one row of 3, or the master's 8 as two rows of 4 (the chain fills from the left)
        slots.chunked(4).forEachIndexed { r, row ->
            if (r > 0) Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { c, x ->
                    val i = r * 4 + c
                    val label = "${i + 1} " + x.effect.ifEmpty { if (chain.isMaster) "+" else "Empty" }
                    Pill(label, Modifier.weight(1f), on = i == sel, color = if (x.bypass) C.faint else color, height = 36.dp, padding = 4.dp) { sel = i; picking = false }
                }
                if (row.size < 4 && chain.isMaster) repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (picking || s.effect.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { Paged(listOf("") + chain.choices.value, columns = 3, rows = 6, rowHeight = 40.dp, key = sel) { _, name ->
                val on = name == s.effect
                Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) color else C.control)
                    .clickable { sel = chain.setEffect(sel, name); picking = false }.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text(name.ifEmpty { "None" }, color = if (on) C.bg else if (name.isEmpty()) C.faint else C.text, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
            } }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(s.effect, Modifier.weight(1f), caret = true, height = 36.dp) { picking = true }
            Spacer(Modifier.width(6.dp))
            Pill("‹", height = 36.dp) { sel = chain.move(sel, -1) }
            Spacer(Modifier.width(4.dp))
            Pill("›", height = 36.dp) { sel = chain.move(sel, 1) }
            Spacer(Modifier.width(4.dp))
            Pill("✕", height = 36.dp) {
                chain.setEffect(sel, "")
                // the master chain closes the gap: stay on the last effect left
                if (chain.isMaster) sel = sel.coerceAtMost(maxOf(0, chain.slots.value.indexOfLast { it.effect.isNotEmpty() }))
            }
            Spacer(Modifier.width(6.dp))
            Pill(if (s.bypass) "Off" else "On", on = !s.bypass, color = color, height = 36.dp) { chain.set(sel, "bypass", !s.bypass) }
        }
        if (s.modes.size > 1) {
            Spacer(Modifier.height(8.dp))
            if (s.modes.size <= 5) Segmented(s.modes, s.mode, color, { chain.set(sel, "mode", it) }, Modifier.fillMaxWidth())
            else Pill(s.modes.getOrElse(s.mode) { "" }, Modifier.fillMaxWidth(), caret = true, height = 34.dp) { chain.set(sel, "mode", (s.mode + 1) % s.modes.size) }
        }
        Spacer(Modifier.height(10.dp))
        // mix first, then the effect's own controls
        val knobs = listOf(null) + s.params
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Paged(knobs, columns = 4, rows = 2, rowHeight = 96.dp, key = "$sel${s.effect}${s.mode}") { _, p ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (p == null) Knob(s.mix, color, "Mix", "${(s.mix * 100).toInt()}%", { chain.set(sel, "mix", it) }, size = 40.dp, default = 1f)
                    else if (p.choices.isNotEmpty()) {
                        val i = (p.value * (p.choices.size - 1) + 0.5f).toInt()
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Pill(p.choices.getOrElse(i) { "" }, height = 34.dp) {
                                chain.set(sel, "param", ((i + 1) % p.choices.size) / (p.choices.size - 1f).coerceAtLeast(1f), p.index)
                            }
                            Text(p.name, color = C.quiet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else Knob(p.value, color, p.name, p.text, { chain.set(sel, "param", it, p.index) }, size = 40.dp, default = p.default)
                }
            }
        }
    }
}
