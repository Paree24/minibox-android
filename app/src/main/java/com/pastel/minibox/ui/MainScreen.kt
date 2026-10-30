package com.pastel.minibox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pastel.minibox.state.App

enum class Tab(val title: String) { Play("Play"), Seq("Seq"), Sound("Sound"), Mix("Mix"), Perform("Perform") }

sealed interface Sheet {
    data object Tempo : Sheet
    data object Record : Sheet
    data object Scale : Sheet
    data object Project : Sheet
    data object MasterFx : Sheet
    data class TrackMix(val track: Int) : Sheet
    data class Device(val track: Int) : Sheet
    data class PatternMenu(val pattern: Int) : Sheet
    data class Step(val step: Int, val lane: Int) : Sheet
    data object Presets : Sheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var tab by remember { mutableStateOf(Tab.Play) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val fast by App.fast

    LaunchedEffect(Unit) { while (true) withFrameNanos { App.poll() } }
    // a pattern switch (or editing another one) changes the devices the screens show
    LaunchedEffect(fast.focusPattern, fast.activePattern) { App.refresh() }

    // tablets: the phone layout, centred, at most 600 dp wide (PLAN.md §15.1)
    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 600.dp).fillMaxSize().background(C.bg).statusBarsPadding()) {
        TopBar(
            onTempo = { sheet = Sheet.Tempo }, onRecordMenu = { sheet = Sheet.Record },
            onPatterns = { tab = Tab.Perform }, onMenu = { sheet = Sheet.Project },
        )
        if (tab != Tab.Mix && tab != Tab.Perform) TrackRow { sheet = Sheet.Device(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Play -> PlayTab(openScale = { sheet = Sheet.Scale })
                Tab.Perform -> PerformTab(onPatternMenu = { sheet = Sheet.PatternMenu(it) })
                Tab.Seq -> SeqTab(openStep = { s, l -> sheet = Sheet.Step(s, l) })
                Tab.Sound -> SoundTab(openPresets = { sheet = Sheet.Presets })
                Tab.Mix -> MixTab(openTrack = { sheet = Sheet.TrackMix(it) }, openEffects = { sheet = Sheet.MasterFx })
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(tab.title, color = C.faint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        BottomNav(tab) { tab = it }
    }
    }

    val current = sheet
    if (current != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = C.surface,
            dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(CircleShape).background(C.faint)) },
        ) {
            Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                when (current) {
                    Sheet.Tempo -> TempoSheet()
                    Sheet.Record -> RecordSheet()
                    Sheet.Scale -> ScaleSheet()
                    Sheet.Project -> ProjectSheet { sheet = null }
                    Sheet.MasterFx -> MasterFxSheet()
                    is Sheet.TrackMix -> TrackMixSheet(current.track)
                    is Sheet.Device -> DevicePickerSheet(current.track) { sheet = null }
                    is Sheet.PatternMenu -> PatternMenuSheet(current.pattern) { sheet = null }
                    is Sheet.Step -> StepSheet(current.step, current.lane) { sheet = null }
                    Sheet.Presets -> PresetSheet { sheet = null }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(onTempo: () -> Unit, onRecordMenu: () -> Unit, onPatterns: () -> Unit, onMenu: () -> Unit) {
    val fast by App.fast
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        // record: tap arms, long-press opens the record settings
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (fast.recording) C.record else C.control)
                .combinedClickable(onClick = { App.cmd("setRecord", "on" to !fast.recording) }, onLongClick = onRecordMenu),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(12.dp).clip(CircleShape).background(if (fast.recording) C.bg else C.record)) }
        Spacer(Modifier.width(10.dp))
        val context = androidx.compose.ui.platform.LocalContext.current
        Box(Modifier.size(44.dp).clickable {
            askNotificationsOnce(context)   // only for the background-playback notification; denying it is fine
            com.pastel.minibox.engine.Device.requestFocus(context)
            App.cmd("play")
        }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(44.dp)) {
                drawCircle(C.control, size.minDimension / 2 - 3.dp.toPx())
                drawCircle(C.divider, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.5.dp.toPx()))
                if (fast.playing) drawArc(C.pattern, -90f, 360f * fast.progress, false,
                    topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
                    style = Stroke(2.5.dp.toPx()))
                val c = center
                val s = 7.dp.toPx()
                drawPath(Path().apply { moveTo(c.x - s * 0.6f, c.y - s); lineTo(c.x + s, c.y); lineTo(c.x - s * 0.6f, c.y + s); close() }, C.pattern)
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(40.dp).clip(CircleShape).background(C.control).clickable { App.cmd("stop") }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(C.text))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.clickable(onClick = onTempo)) {
            Text("%.1f".format(fast.bpm), color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("${fast.position} · ${fast.beats}/${fast.unit}", color = if (fast.recording || fast.countIn) C.record else C.faint,
                fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        if (com.pastel.minibox.engine.Device.hot.value) {   // throttling soon: suggest a higher latency (Project sheet)
            Text("HOT", color = C.record, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(end = 8.dp))
        }
        Row(Modifier.clickable(onClick = onPatterns), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PatternTile("ABCDEFGH"[fast.activePattern.coerceIn(0, 7)].toString(), current = true)
            if (fast.queuedPattern in 0..7) PatternTile("ABCDEFGH"[fast.queuedPattern].toString(), current = false)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(40.dp).clip(CircleShape).background(C.control).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
            Text("⋯", color = C.quiet, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
}

@Composable
private fun PatternTile(letter: String, current: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(if (current) 44.dp else 36.dp, 40.dp).clip(shape)
            .background(if (current) C.pattern.copy(alpha = 0.18f) else C.control)
            .border(1.5.dp, if (current) C.pattern else C.pattern.copy(alpha = 0.6f), shape),
        contentAlignment = Alignment.Center,
    ) { Text(letter, color = C.pattern, fontSize = if (current) 16.sp else 13.sp, fontWeight = FontWeight.Black) }
}

/** The 8 track chips; the selected one opens into a pill with its device. Tap selects, long-press (or an empty
    chip) picks the device. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(onPick: (Int) -> Unit) {
    val rack by App.rack
    val sel = App.focusTrack.intValue
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        rack.forEachIndexed { i, t ->
            val color = C.track(i)
            if (i == sel && !t.empty) {
                Row(Modifier.weight(1f).height(36.dp).clip(CircleShape).background(color)
                    .combinedClickable(onClick = { onPick(i) }, onLongClick = { onPick(i) }).padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(C.bg.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = C.bg, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(t.name, color = C.bg, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("▾", color = C.bg, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                }
            } else {
                val m = Modifier.size(32.dp).clip(CircleShape)
                Box(
                    (if (t.empty) m.border(1.5.dp, C.faint, CircleShape) else m.background(C.control).border(2.dp, color.copy(alpha = 0.55f), CircleShape))
                        .combinedClickable(onClick = { if (t.empty) onPick(i) else App.selectTrack(i) }, onLongClick = { onPick(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (t.empty) "+" else "${i + 1}", color = if (t.empty) C.faint else color, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(C.surface).navigationBarsPadding().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Column(Modifier.weight(1f).clickable { onTab(t) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp, 30.dp).clip(CircleShape).background(if (on) C.control else Color.Transparent),
                    contentAlignment = Alignment.Center) { NavIcon(t, if (on) C.text else C.faint) }
                Spacer(Modifier.height(4.dp))
                Text(t.title, color = if (on) C.text else C.faint, fontSize = 11.sp, fontWeight = if (on) FontWeight.Black else FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NavIcon(t: Tab, c: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        when (t) {
            Tab.Play -> for (r in 0..1) for (k in 0..1)
                drawRoundRect(c, Offset(k * w * 0.55f, r * w * 0.55f), androidx.compose.ui.geometry.Size(w * 0.45f, w * 0.45f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.12f))
            Tab.Seq -> listOf(0.5f, 0.8f, 0.35f, 0.65f).forEachIndexed { i, h ->
                drawRoundRect(c, Offset(i * w * 0.27f, w * (1 - h)), androidx.compose.ui.geometry.Size(w * 0.18f, w * h),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
            }
            Tab.Sound -> {
                drawCircle(c, w * 0.36f, style = Stroke(w * 0.1f))
                drawLine(c, center, Offset(center.x + w * 0.2f, center.y - w * 0.25f), w * 0.1f)
            }
            Tab.Mix -> listOf(0.3f, 0.7f, 0.45f).forEachIndexed { i, k ->
                val x = w * (0.17f + i * 0.33f)
                drawLine(c, Offset(x, 0f), Offset(x, w), w * 0.08f)
                drawRoundRect(c, Offset(x - w * 0.12f, w * k - w * 0.08f), androidx.compose.ui.geometry.Size(w * 0.24f, w * 0.16f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.05f))
            }
            Tab.Perform -> drawPath(Path().apply {
                moveTo(w * 0.6f, 0f); lineTo(w * 0.2f, w * 0.55f); lineTo(w * 0.48f, w * 0.55f); lineTo(w * 0.38f, w)
                lineTo(w * 0.8f, w * 0.42f); lineTo(w * 0.52f, w * 0.42f); close()
            }, c)
        }
    }
}

/** The first Play asks once whether Minibox may show its "Playing · Stop" notification while in the background
    (Android 13+). Background playback works either way; without it there is no Stop outside the app. */
private fun askNotificationsOnce(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val prefs = context.getSharedPreferences("minibox", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean("askedNotifications", false)) return
    prefs.edit().putBoolean("askedNotifications", true).apply()
    val activity = context as? android.app.Activity ?: return
    if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
}
