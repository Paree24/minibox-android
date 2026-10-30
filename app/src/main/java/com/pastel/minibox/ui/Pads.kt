package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pastel.minibox.state.App

/** The selected track's 16 square pads. */
@Composable
fun Pads(modifier: Modifier = Modifier) {
    val track = App.focusTrack.intValue
    val rack by App.rack
    val project by App.project
    val octaves by App.octave
    val rollBeats by App.rollBeats
    val layout = currentLayout(track, rack[track], project, octaves[track], rollBeats) ?: return
    AndroidView(
        factory = { PadView(it).apply { listener = App::padPressed } },
        update = { it.layout = layout },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** Editors keep the pads folded into this handle; tapping it pulls the 4×4 up (mockups 15–16). */
@Composable
fun PadsHandle(open: Boolean, onToggle: () -> Unit) {
    val color = C.track(App.focusTrack.intValue)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp).clip(RoundedCornerShape(14.dp)).background(C.control)
            .clickable(onClick = onToggle).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (k in 0 until 2) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Spacer(Modifier.width(2.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("Pads", color = C.quiet, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        Text(if (open) "▾" else "▴", color = C.quiet, fontSize = 14.sp)
    }
}
