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
import com.pastel.minibox.state.App
import kotlinx.coroutines.delay

/** The arpeggiator's rates, in the engine's order (NoteInput / the desktop's Play drawer). */
val ARP_RATES = listOf("1/4", "1/4 D", "1/4 T", "1/8", "1/8 D", "1/8 T", "1/16", "1/16 D", "1/16 T", "1/32", "1/32 D", "1/32 T", "1/64", "1/64 D", "1/64 T")
val ARP_MODES = listOf("Up", "Down", "Up-Dn", "Dn-Up", "Rand", "Played")
val ROLL_RATES = listOf(1f, 0.5f, 0.25f, 0.125f, 1f / 3f, 1f / 6f)

/** Play (mockups 01–03): mode, scale and octave on top, the 8 macros, the 16 square pads on the nav. */
@Composable
fun PlayTab(openScale: () -> Unit, openArp: () -> Unit) {
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

    // a synth's pads can expand to 24 (6 rows, a wider range); the macros fold away meanwhile, the app bar stays
    val expanded = PadsUi.expanded.value && !info.drums && !info.empty
    Column(Modifier.fillMaxSize()) {
        Column((if (expanded) Modifier else Modifier.weight(1f)).fillMaxWidth().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            when {
                info.empty -> {}
                info.drums -> Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Roll", Modifier.width(40.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        ROLL_RATES.forEach { r ->
                            Pill(rateName(r), Modifier.weight(1f), on = r == rollBeats, padding = 2.dp) { App.rollBeats.value = r }
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
                        Pill("${keyName(project.scaleRoot)} ${project.scaleName}".take(10), caret = true, onClick = openScale)
                        Spacer(Modifier.width(8.dp))
                        OctaveButton("Oct −", octaves[track] > -2) { App.setOctave(track, octaves[track] - 1) }
                        Spacer(Modifier.width(6.dp))
                        OctaveButton("Oct +", octaves[track] < 3) { App.setOctave(track, octaves[track] + 1) }
                    }
                    if (info.play.chordOn) {
                        // the chord's voicing: an inversion, the whole shape an octave down, a bass note below
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Segmented(listOf("Root", "1st", "2nd"), info.play.inversion, color, {
                                App.cmd("setPlay", "track" to track, "key" to "inversion", "value" to it)
                            }, Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Pill("Oct ↓", on = info.play.invLower, color = color, height = 34.dp) {
                                App.cmd("setPlay", "track" to track, "key" to "invLower", "value" to !info.play.invLower)
                            }
                            Spacer(Modifier.width(6.dp))
                            Pill("+ Bass", on = info.play.bassBelow, color = color, height = 34.dp) {
                                App.cmd("setPlay", "track" to track, "key" to "bassBelow", "value" to !info.play.bassBelow)
                            }
                        }
                    }
                    if (info.play.arpOn) {
                        Spacer(Modifier.height(8.dp))
                        // one dropdown: the rate, the mode and the octaves; the Arp sheet holds every setting
                        Pill("Arp  ${ARP_RATES[info.play.arpRate.coerceIn(0, 14)]} · ${ARP_MODES[info.play.arpMode]} · ${info.play.arpOct + 1} oct" +
                            if (info.play.arpLatch) " · Latch" else "", Modifier.fillMaxWidth(), caret = true, height = 34.dp, onClick = openArp)
                    }
                }
            }
            if (!expanded) {
                Spacer(Modifier.height(10.dp))
                MacroCard(track, Modifier.weight(1f).fillMaxWidth())
            }
            Spacer(Modifier.height(if (expanded) 6.dp else 8.dp))
        }
        if (info.empty) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(200.dp), contentAlignment = Alignment.Center) {
            Text("+", color = C.faint, fontSize = 40.sp)
        } else {
            if (!info.drums) PadsExpandHandle(expanded) { PadsUi.expanded.value = !expanded }
            if (expanded) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                // 6 rows in the room left: the pads as square as fits
                val padW = (maxWidth - 32.dp - 24.dp) / 4
                val padH = (maxHeight - 40.dp) / 6
                Pads(rows = 6, aspect = (padH / padW).coerceIn(0.5f, 1f))
            } else Pads()
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** The bar above a synth's pads: 16 pads, or 24 (a wider range). */
@Composable
private fun PadsExpandHandle(expanded: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(26.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(Modifier.size(28.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(C.faint))
        Spacer(Modifier.width(10.dp))
        Text(if (expanded) "16 pads ▾" else "24 pads ▴", color = C.quiet, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(28.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(C.faint))
    }
    Spacer(Modifier.height(4.dp))
}

/** One of the two octave buttons beside the scale (the pads' labels show where they are). */
@Composable
private fun OctaveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(C.control).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) C.text else C.faint, fontSize = 12.sp, fontWeight = FontWeight.Black)
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
