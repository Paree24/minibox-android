package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pastel.minibox.engine.AudioStats
import com.pastel.minibox.engine.Native
import kotlinx.coroutines.delay

/** The starter rack the engine builds on pattern A (MiniboxHost::init). */
private data class Slot(val name: String, val drums: Boolean = false, val baseNote: Int = 36)

private val RACK = listOf(Slot("Drums", drums = true), Slot("Bassline"), Slot("Acid"), Slot("VA", baseNote = 48), null, null, null, null)
/** Drum pads 0–7 (0 = bottom-left) by General MIDI note: bottom row Kick, Snare | C Hat, O Hat; above it
    Tom, Clap | Perc, Cymbal. Left thumb on kick and snare, right thumb on the hats and cymbal. */
private val THUMB_ORDER = intArrayOf(36, 38, 42, 46, 45, 39, 37, 49)
private val MINOR = intArrayOf(0, 2, 3, 5, 7, 8, 10)
private val NOTE_NAMES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

private fun noteName(n: Int) = NOTE_NAMES[n % 12] + (n / 12 - 1)

private fun padLayout(track: Int): PadView.Layout? {
    val slot = RACK[track] ?: return null
    val color = Tokens.TRACK[track]
    if (slot.drums) {
        // the device says which note plays each part and its name (Drums and the Drum Sampler order them differently)
        val lanes = Native.laneNotes(track)
        if (lanes.isEmpty()) return null
        // place the parts by their General MIDI note, for two thumbs: kick and snare under the left,
        // hats and cymbal under the right; parts with other notes fill the places left over
        val order = THUMB_ORDER.map { gm -> lanes.indexOf(gm) }.toMutableList()
        val unused = lanes.indices.filter { it !in order }.toMutableList()
        for (i in order.indices) if (order[i] < 0) order[i] = if (unused.isNotEmpty()) unused.removeAt(0) else i % lanes.size
        val names = Array(lanes.size) { Native.laneName(track, it) }
        // lower 8 hit the parts; the upper 8 repeat them in the same columns (the rolls, phase 4; they hit for now)
        val lane = IntArray(16) { order[it % 8] }
        return PadView.Layout(track, color, IntArray(16) { lanes[lane[it]] }, Array(16) { names[lane[it]] }, emptySet())
    }
    // in scale (C minor), rows a fourth apart: 3 scale degrees per row
    val notes = IntArray(16) { pad ->
        val deg = (pad / 4) * 3 + pad % 4
        slot.baseNote + 12 * (deg / 7) + MINOR[deg % 7]
    }
    val roots = (0 until 16).filter { notes[it] % 12 == 0 }.toSet()
    return PadView.Layout(track, color, notes, Array(16) { noteName(notes[it]) }, roots)
}

@Composable
fun PlayScreen() {
    var track by remember { mutableIntStateOf(1) }
    var playing by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(AudioStats()) }
    var bursts by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val buf = FloatArray(11)
        while (true) {
            Native.readStats(buf)
            stats = AudioStats.from(buf)
            delay(100)
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
        TopBar(playing) { playing = it; if (it) Native.play() else Native.stop() }
        TrackRow(track) { track = it; Native.selectTrack(it) }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            AudioCard(stats, bursts) { bursts = it; Native.setBufferBursts(it) }
        }
        AndroidView(
            factory = { PadView(it) },
            update = { it.layout = padLayout(track) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        BottomNav()
    }
}

@Composable
private fun TopBar(playing: Boolean, onPlay: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(C.control) { Box(Modifier.size(12.dp).clip(CircleShape).background(C.record)) }
        Spacer(Modifier.width(10.dp))
        RoundButton(C.control, ring = if (playing) C.pattern else null, onClick = { onPlay(true) }) {
            Text("▶", color = C.pattern, fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        RoundButton(C.control, onClick = { onPlay(false) }) { Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(C.text)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("124.0", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("1.1.1 · 4/4", color = C.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(44.dp, 40.dp).clip(RoundedCornerShape(10.dp)).background(C.pattern.copy(alpha = 0.18f))
                .border(1.5.dp, C.pattern, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("A", color = C.pattern, fontSize = 16.sp, fontWeight = FontWeight.Black) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
}

@Composable
private fun RoundButton(fill: Color, ring: Color? = null, onClick: () -> Unit = {}, content: @Composable () -> Unit) {
    var m = Modifier.size(40.dp).clip(CircleShape).background(fill).clickable(onClick = onClick)
    if (ring != null) m = m.border(2.5.dp, ring, CircleShape)
    Box(m, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun TrackRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RACK.forEachIndexed { i, slot ->
            val color = C.track(i)
            if (i == selected && slot != null) {
                Row(Modifier.weight(1f).height(36.dp).clip(CircleShape).background(color).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(C.bg.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = C.bg, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(slot.name, color = C.bg, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
            } else {
                val m = Modifier.size(32.dp).clip(CircleShape)
                Box(
                    if (slot == null) m.border(1.5.dp, C.faint, CircleShape)
                    else m.background(C.control).border(2.dp, color.copy(alpha = 0.55f), CircleShape).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (slot == null) "+" else "${i + 1}", color = if (slot == null) C.faint else color,
                        fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

/** Phase 2 lives here, where the macros will go: the latency readout and buffer choice (PLAN.md §2, §6). */
@Composable
private fun AudioCard(s: AudioStats, bursts: Int, onBursts: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(C.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AUDIO", color = C.faint, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Spacer(Modifier.weight(1f))
            val low = s.mmap && s.exclusive
            Text(if (!s.running) "Stopped" else if (low) "Low latency" else "Standard latency",
                color = if (low) C.track(4) else C.quiet, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (s.latencyMs >= 0) "%.1f".format(s.latencyMs) else "–", color = C.text, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(6.dp))
            Text("ms", color = C.quiet, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(
            "AAudio · ${if (s.exclusive) "exclusive" else "shared"}${if (s.mmap) " · MMAP" else ""} · ${s.sampleRate / 1000f} kHz · ${s.bufferFrames} frames",
            color = C.quiet, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        Text("burst ${s.burst} · callback ${s.callbackFrames} · xruns ${s.xruns} · cpu ${(s.cpu * 100).toInt()}%",
            color = C.faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        // output level: shows the pads reach the engine even where nothing can be heard (an emulator, a muted phone)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(C.control)) {
            val db = 20f * kotlin.math.log10(s.peak.coerceAtLeast(1e-6f))
            Box(Modifier.fillMaxWidth(((db + 60f) / 60f).coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(C.pattern))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Auto", "1×", "2×", "3×", "4×").forEachIndexed { i, label ->
                val on = i == bursts
                Box(
                    Modifier.weight(1f).height(32.dp).clip(CircleShape).background(if (on) C.text else C.control).clickable { onBursts(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (on) C.bg else C.quiet, fontSize = 12.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun BottomNav() {
    Row(Modifier.fillMaxWidth().height(72.dp).background(C.surface), verticalAlignment = Alignment.CenterVertically) {
        listOf("Play", "Seq", "Sound", "Mix", "Perform").forEachIndexed { i, name ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(56.dp, 30.dp).clip(CircleShape).background(if (i == 0) C.control else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(if (i == 0) C.text else C.faint)) }
                Spacer(Modifier.height(4.dp))
                Text(name, color = if (i == 0) C.text else C.faint, fontSize = 11.sp, fontWeight = if (i == 0) FontWeight.Black else FontWeight.Bold)
            }
        }
    }
}
