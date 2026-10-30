package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pastel.minibox.state.App
import kotlinx.coroutines.delay

private val ARP_RATES = listOf(3 to "1/8", 6 to "1/16", 9 to "1/32", 5 to "1/8T", 8 to "1/16T")
private val ARP_MODES = listOf("Up", "Down", "Up-Dn", "Dn-Up", "Rand", "Played")
val ROLL_RATES = listOf(0.5f, 0.25f, 0.125f, 1f / 3f, 1f / 6f)

/** Play (mockups 01–03): mode, scale and octave on top, the 8 macros, the 16 square pads on the nav. */
@Composable
fun PlayTab(openScale: () -> Unit) {
    val track = App.focusTrack.intValue
    val rack by App.rack
    val project by App.project
    val octaves by App.octave
    val rollBeats by App.rollBeats
    val info = rack[track]
    val color = C.track(track)

    LaunchedEffect(track) {   // macros show the playing value (locks, automation)
        while (true) { App.refreshMacros(); delay(200) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            when {
                info.empty -> {}
                info.drums -> Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Roll", Modifier.width(48.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        ROLL_RATES.forEach { r ->
                            Pill(rateName(r), Modifier.weight(1f), on = r == rollBeats) { App.rollBeats.value = r }
                        }
                    }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val mode = if (info.play.arpOn) 2 else if (info.play.chordOn) 1 else 0
                        Segmented(listOf("Notes", "Chords", "Arp"), mode, color, { m ->
                            App.call("setPlay", "track" to track, "key" to "chordOn", "value" to (m == 1))
                            App.cmd("setPlay", "track" to track, "key" to "arpOn", "value" to (m == 2))
                        }, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Pill("${keyName(project.scaleRoot)} ${project.scaleName}".take(14), caret = true, onClick = openScale)
                        Spacer(Modifier.width(8.dp))
                        OctaveStepper(octaves[track]) { App.setOctave(track, it) }
                    }
                    if (info.play.arpOn) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ARP_RATES.forEach { (id, name) ->
                                Pill(name, Modifier.weight(1f), on = info.play.arpRate == id, color = color) {
                                    App.cmd("setPlay", "track" to track, "key" to "arpRate", "value" to id)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(ARP_MODES[info.play.arpMode], Modifier.weight(1f), caret = true) {
                                App.cmd("setPlay", "track" to track, "key" to "arpMode", "value" to (info.play.arpMode + 1) % 6)
                            }
                            Pill("${info.play.arpOct + 1} oct", Modifier.weight(1f), caret = true) {
                                App.cmd("setPlay", "track" to track, "key" to "arpOct", "value" to (info.play.arpOct + 1) % 4)
                            }
                            Pill("Latch", Modifier.weight(1f), on = info.play.arpLatch, color = color) {
                                App.cmd("setPlay", "track" to track, "key" to "arpLatch", "value" to !info.play.arpLatch)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            MacroCard(track, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        val layout = when {
            info.empty -> null
            info.drums -> drumsLayout(track, info, rollBeats)
            info.play.chordOn && !info.play.arpOn -> chordsLayout(track, info, project, octaves[track])
            else -> notesLayout(track, info, project, octaves[track])
        }
        if (layout != null) {
            AndroidView(factory = { PadView(it) }, update = { it.layout = layout },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        } else {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(200.dp), contentAlignment = Alignment.Center) {
                Text("+", color = C.faint, fontSize = 40.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun OctaveStepper(oct: Int, onChange: (Int) -> Unit) {
    Row(Modifier.height(30.dp).clip(CircleShape).background(C.control), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clickable { onChange(oct - 1) }, contentAlignment = Alignment.Center) { Text("‹", color = C.quiet, fontSize = 16.sp) }
        Text(if (oct == 0) "0" else if (oct > 0) "+$oct" else "$oct", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Box(Modifier.size(30.dp).clickable { onChange(oct + 1) }, contentAlignment = Alignment.Center) { Text("›", color = C.quiet, fontSize = 16.sp) }
    }
}

/** The 8 macros of the selected track, two rows of four; when the header leaves too little room (Arp's extra
    rows) the knobs shrink, and below that only the first four show. */
@Composable
fun MacroCard(track: Int, modifier: Modifier = Modifier) {
    val macros by App.macros
    val color = C.track(track)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(16.dp)).background(C.surface)) {
        val rows = if (maxHeight < 150.dp) 1 else 2
        val rowH = maxHeight / rows
        val knob = (rowH - 46.dp).coerceIn(24.dp, 44.dp)
        Column(Modifier.fillMaxSize().padding(vertical = 4.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            for (row in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 4) {
                        val m = row * 4 + c
                        val info = macros.getOrNull(m)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (info != null && info.name.isNotEmpty())
                                Knob(info.value, color, info.name, info.text, { App.setMacro(m, it) }, size = knob)
                        }
                    }
                }
            }
        }
    }
}
