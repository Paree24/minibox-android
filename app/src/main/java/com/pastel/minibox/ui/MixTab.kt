package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import kotlin.math.abs
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pastel.minibox.state.App
import kotlin.math.log10

/** Mix (mockup 09): 8 fader columns (drag anywhere on one; the meter runs inside it), mute / solo, the master.
    Tap a track's badge for its pan and sends. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MixTab(openTrack: (Int) -> Unit, openEffects: () -> Unit) {
    val rack by App.rack
    val fast by App.fast
    val project by App.project
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rack.forEachIndexed { t, info ->
                val color = C.track(t)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (info.empty) {
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.5.dp, C.faint, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) { Text("+", color = C.faint, fontSize = 16.sp) }
                    } else {
                        Fader(t, info.mixer.level, fast.trackPeaks.getOrElse(t) { 0f }, color, info.mixer.mute, "${t + 1}", { openTrack(t) },
                            Modifier.weight(1f).fillMaxWidth()) { App.cmd("setMixer", "track" to t, "key" to "level", "value" to it) }
                    }
                    Spacer(Modifier.height(8.dp))
                    MsPad("M", info.mixer.mute, C.mute) { App.cmd("setMixer", "track" to t, "key" to "mute", "value" to !info.mixer.mute) }
                    Spacer(Modifier.height(6.dp))
                    MsPad("S", info.mixer.solo, C.text) { App.cmd("setMixer", "track" to t, "key" to "solo", "value" to !info.mixer.solo) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Master", Modifier.weight(1f))
                Pill("Effects", caret = true, onClick = openEffects)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(Triple("Delay", "delayReturn", project.master.delayReturn), Triple("Reverb", "reverbReturn", project.master.reverbReturn),
                    Triple("Glue", "glue", project.master.glue)).forEach { (name, key, v) ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Knob(v, C.pattern, name, "${(v * 100).toInt()}%", { App.call("set", "key" to key, "value" to it) }, size = 38.dp,
                            onEnd = { App.refresh() })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val db = if (fast.masterPeak > 1e-5f) "%.1f dB".format(20 * log10(fast.masterPeak)) else "–"
            BarSlider(project.master.volume, C.pattern, "Volume", db, { App.call("set", "key" to "volume", "value" to it) },
                onEnd = { App.refresh() })
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Fader(track: Int, level: Float, peak: Float, color: androidx.compose.ui.graphics.Color, muted: Boolean, badge: String,
                  onBadge: () -> Unit, modifier: Modifier, onLevel: (Float) -> Unit) {
    var drag by remember { mutableFloatStateOf(-1f) }
    val current by rememberUpdatedState(level)          // the live level, not the one of the first composition
    val setLevel by rememberUpdatedState(onLevel)
    val shown = if (drag >= 0f) drag else level
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.control)
            .pointerInput(Unit) {
                // touch anywhere: the fader moves with the finger from where it is (no jump), tap sets the level there
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var v = current
                    var y = down.position.y
                    var moved = false
                    drag = v
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        val dy = c.position.y - y
                        if (abs(dy) > 2f) moved = true
                        y = c.position.y
                        v = (v - dy / size.height).coerceIn(0f, 1f)
                        drag = v
                        App.call("setMixer", "track" to track, "key" to "level", "value" to v)
                    }
                    if (!moved) v = (1f - down.position.y / size.height).coerceIn(0f, 1f)
                    drag = -1f
                    setLevel(v)   // the one write that refreshes the screen
                }
            },
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(shown).align(Alignment.BottomCenter).background(color.copy(alpha = if (muted) 0.15f else 0.32f)))
        // the meter, in dB over 60 dB
        val m = if (peak > 1e-6f) ((20 * log10(peak) + 60) / 60).coerceIn(0f, 1f) else 0f
        Box(Modifier.width(4.dp).fillMaxHeight(m).align(Alignment.BottomCenter).padding(bottom = 6.dp).clip(CircleShape)
            .background(if (muted) C.faint else color))
        Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(26.dp).clip(CircleShape).background(color).clickable(onClick = onBadge),
            contentAlignment = Alignment.Center) { Text(badge, color = C.bg, fontSize = 12.sp, fontWeight = FontWeight.Black) }
    }
}

@Composable
private fun MsPad(text: String, on: Boolean, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) color else C.control).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(text, color = if (on) C.bg else C.quiet, fontSize = 13.sp, fontWeight = FontWeight.Black) }
}

/** A track's pan and sends (tap its badge on Mix). */
@Composable
fun TrackMixSheet(track: Int) {
    val rack by App.rack
    val m = rack[track].mixer
    val color = C.track(track)
    Column {
        SheetTitle("Track ${track + 1}", rack[track].name)
        BarSlider((m.pan + 1f) / 2f, color, "Pan", if (kotlin.math.abs(m.pan) < 0.02f) "C" else if (m.pan < 0) "L${(-m.pan * 100).toInt()}" else "R${(m.pan * 100).toInt()}",
            { App.call("setMixer", "track" to track, "key" to "pan", "value" to it * 2f - 1f) }, onEnd = { App.refresh() })
        Spacer(Modifier.height(8.dp))
        BarSlider(m.sendA, color, "Delay send", "${(m.sendA * 100).toInt()}%", { App.call("setMixer", "track" to track, "key" to "sendA", "value" to it) }, onEnd = { App.refresh() })
        Spacer(Modifier.height(8.dp))
        BarSlider(m.sendB, color, "Reverb send", "${(m.sendB * 100).toInt()}%", { App.call("setMixer", "track" to track, "key" to "sendB", "value" to it) }, onEnd = { App.refresh() })
        Spacer(Modifier.height(8.dp))
        BarSlider(m.level, color, "Level", "${(m.level * 100).toInt()}%", { App.call("setMixer", "track" to track, "key" to "level", "value" to it) }, onEnd = { App.refresh() })
    }
}
