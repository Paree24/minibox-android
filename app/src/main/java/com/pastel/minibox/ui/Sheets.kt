package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import com.pastel.minibox.engine.AudioStats
import com.pastel.minibox.engine.Native
import com.pastel.minibox.state.App
import kotlinx.coroutines.delay

@Composable
fun SheetTitle(title: String, sub: String = "") {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Black)
        if (sub.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(sub, color = C.quiet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** Tempo (mockup 12): −/+, TAP, time signature, swing, click, count-in. */
@Composable
fun TempoSheet() {
    val p by App.project
    var taps by remember { mutableStateOf(listOf<Long>()) }
    Column {
        SheetTitle("Tempo")
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareButton("−") { App.cmd("set", "key" to "bpm", "value" to p.bpm - 1) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%.1f".format(p.bpm), color = C.text, fontSize = 44.sp, fontWeight = FontWeight.Black)
                SectionLabel("BPM")
            }
            SquareButton("+") { App.cmd("set", "key" to "bpm", "value" to p.bpm + 1) }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(18.dp)).background(C.pattern.copy(alpha = 0.18f))
                .border(1.5.dp, C.pattern, RoundedCornerShape(18.dp))
                .clickable {
                    val now = System.currentTimeMillis()
                    taps = (taps.filter { now - it < 2500 } + now).takeLast(5)
                    if (taps.size >= 2) {
                        val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
                        App.cmd("set", "key" to "bpm", "value" to 60000.0 / avg)
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("TAP", color = C.pattern, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp) }
        Spacer(Modifier.height(20.dp))
        SectionLabel("Time")
        Spacer(Modifier.height(8.dp))
        val sigs = listOf(3 to 4, 4 to 4, 5 to 4, 6 to 8, 7 to 8)
        ChoicePads(sigs.map { "${it.first}/${it.second}" }, sigs.indexOf(p.beats to p.unit), C.text,
            { App.cmd("setTimeSig", "beats" to sigs[it].first, "unit" to sigs[it].second) })
        Spacer(Modifier.height(14.dp))
        BarSlider((p.swing - 0.5f) / 0.25f, C.text, "Swing", "${(p.swing * 100).toInt()}%",
            { App.cmd("set", "key" to "swing", "value" to 0.5f + it * 0.25f) })
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleCard("Click", p.metronome, Modifier.weight(1f)) { App.cmd("set", "key" to "metronome", "value" to it) }
            ToggleCard("Count-in", p.countIn, Modifier.weight(1f)) { App.cmd("set", "key" to "countIn", "value" to it) }
        }
    }
}

/** Record (mockup 13): Live / Step, Extend / Overdub, quantize, count-in. */
@Composable
fun RecordSheet() {
    val p by App.project
    val fast by App.fast
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SheetTitle("Record") }
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (fast.recording) C.record else C.control)
                    .clickable { App.cmd("setRecord", "on" to !fast.recording) },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(16.dp).clip(CircleShape).background(if (fast.recording) C.bg else C.record)) }
        }
        ChoicePads(listOf("Live", "Step"), p.recordMode, C.record, { App.cmd("set", "key" to "recordMode", "value" to it) }, height = 52.dp)
        Spacer(Modifier.height(18.dp))
        if (p.recordMode == 0) {
            SectionLabel("Takes")
            Spacer(Modifier.height(8.dp))
            ChoicePads(listOf("Extend", "Overdub"), if (p.overdub) 1 else 0, C.text, { App.cmd("set", "key" to "overdub", "value" to (it == 1)) })
            Spacer(Modifier.height(18.dp))
            SectionLabel("Quantize")
            Spacer(Modifier.height(8.dp))
            val q = listOf(0f, 0.5f, 0.75f, 1f)
            ChoicePads(listOf("Off", "50", "75", "100"), q.indexOfFirst { kotlin.math.abs(it - p.recordQuantize) < 0.01f }, C.text,
                { App.cmd("set", "key" to "recordQuantize", "value" to q[it]) })
            Spacer(Modifier.height(18.dp))
        }
        ToggleCard("Count-in", p.countIn, Modifier.fillMaxWidth()) { App.cmd("set", "key" to "countIn", "value" to it) }
    }
}

private val DEVICES = listOf(
    Triple("mono", "Bassline", "Mono"), Triple("poly", "VA", "Poly · 6 voices"), Triple("acid", "Acid", "Bass line"),
    Triple("fm", "FM", "6 operators"), Triple("sampler", "Sampler", "Chromatic"), Triple("drums", "Drums", "Drum synth"),
    Triple("drumsampler", "Drum Sampler", "8 pads · slicer"), Triple("", "Empty", ""),
)

/** Device picker (mockup 11): the track's device in the pattern being edited. */
@Composable
fun DevicePickerSheet(track: Int, done: () -> Unit) {
    val rack by App.rack
    val fast by App.fast
    val color = C.track(track)
    Column {
        SheetTitle("Track ${track + 1}", "Pattern ${"ABCDEFGH"[fast.focusPattern]}")
        for (row in 0 until 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0 until 2) {
                    val (id, name, sub) = DEVICES[row * 2 + c]
                    val on = rack[track].type == id
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.weight(1f).height(84.dp).clip(shape)
                            .then(if (id.isEmpty()) Modifier.border(1.5.dp, C.faint, shape) else Modifier.background(C.control))
                            .then(if (on) Modifier.border(2.dp, color, shape) else Modifier)
                            .clickable {
                                App.cmd("setDevice", "track" to track, "type" to id)
                                if (id.isNotEmpty()) App.selectTrack(track)
                                done()
                            }
                            .padding(14.dp),
                        contentAlignment = if (id.isEmpty()) Alignment.Center else Alignment.CenterStart,
                    ) {
                        Column {
                            Text(name, color = if (id.isEmpty()) C.faint else C.text, fontSize = 15.sp, fontWeight = FontWeight.Black)
                            if (sub.isNotEmpty()) Text(sub, color = C.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The project's key: root and scale (63 scales, as the desktop's Play drawer). */
@Composable
fun ScaleSheet() {
    val p by App.project
    Column {
        SheetTitle("Scale", "${keyName(p.scaleRoot)} ${p.scaleName}")
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 6) {
                    val pc = row * 6 + c
                    val on = pc == p.scaleRoot
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.text else C.control)
                            .clickable { App.cmd("set", "key" to "scaleRoot", "value" to pc) },
                        contentAlignment = Alignment.Center,
                    ) { Text(keyName(pc), color = if (on) C.bg else C.quiet, fontSize = 13.sp, fontWeight = FontWeight.Black) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            itemsIndexed(App.scaleNames) { i, name ->
                val on = i == p.scaleIndex
                Text(name, color = if (on) C.bg else C.text, fontSize = 14.sp, fontWeight = if (on) FontWeight.Black else FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) C.text else C.bg.copy(alpha = 0f))
                        .clickable { App.cmd("set", "key" to "scaleIndex", "value" to i) }.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

/** Long-press on a pattern pad: edit it, copy the playing one into it, clear it. */
@Composable
fun PatternMenuSheet(pattern: Int, done: () -> Unit) {
    val fast by App.fast
    val letter = "ABCDEFGH"[pattern]
    Column {
        SheetTitle("Pattern $letter")
        MenuRow("Edit $letter") { App.cmd("focusPattern", "pattern" to pattern); done() }
        if (pattern != fast.activePattern)
            MenuRow("Copy ${"ABCDEFGH"[fast.activePattern]} here") { App.cmd("copyPattern", "from" to fast.activePattern, "to" to pattern); done() }
        MenuRow("Clear") { App.cmd("clearPattern", "pattern" to pattern); done() }
    }
}

@Composable
fun MenuRow(text: String, onClick: () -> Unit) {
    Text(text, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.control)
            .clickable(onClick = onClick).padding(16.dp))
}

@Composable
private fun SquareButton(text: String, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(C.control).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(text, color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Black) }
}

@Composable
fun ToggleCard(name: String, on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).background(C.control).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = if (on) C.text else C.quiet, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        Toggle(on) { onChange(it) }
    }
}
