package com.pastel.minibox.state

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.pastel.minibox.engine.Native
import org.json.JSONArray
import org.json.JSONObject

/** The playhead, patterns and meters, polled at 60 Hz (MiniboxHost::readFast). */
data class Fast(
    val playing: Boolean = false, val recording: Boolean = false, val countIn: Boolean = false,
    val activePattern: Int = 0, val queuedPattern: Int = -1, val step: Int = 0, val patternSteps: Int = 16,
    val progress: Float = 0f, val bpm: Float = 120f, val beats: Int = 4, val unit: Int = 4, val stepsPerBar: Int = 16,
    val masterPeak: Float = 0f, val trackPeaks: List<Float> = List(8) { 0f }, val focusPattern: Int = 0,
) {
    /** "bar.beat.step", as the desktop's position display. */
    val position: String
        get() {
            val spb = stepsPerBar.coerceAtLeast(1)
            val perBeat = (spb / beats.coerceAtLeast(1)).coerceAtLeast(1)
            val inBar = step % spb
            return "${step / spb + 1}.${inBar / perBeat + 1}.${inBar % perBeat + 1}"
        }

    companion object {
        fun from(v: FloatArray) = Fast(
            v[0] > 0, v[1] > 0, v[2] > 0, v[3].toInt(), v[4].toInt(), v[5].toInt(), v[6].toInt(), v[7], v[8],
            v[9].toInt(), v[10].toInt(), v[11].toInt(), v[12], List(8) { v[13 + it] }, v[21].toInt(),
        )
    }
}

data class Lane(val note: Int, val name: String)
data class PlaySettings(val chordOn: Boolean = false, val arpOn: Boolean = false, val arpLatch: Boolean = false,
                        val arpMode: Int = 0, val arpRate: Int = 6, val arpOct: Int = 0, val arpGate: Float = 90f, val arpSwing: Float = 0f,
                        val inversion: Int = 0, val invLower: Boolean = false, val bassBelow: Boolean = false)
data class MixerInfo(val level: Float = 0.8f, val pan: Float = 0f, val mute: Boolean = false, val solo: Boolean = false,
                     val sendA: Float = 0f, val sendB: Float = 0f)
data class TrackInfo(val type: String = "", val name: String = "", val preset: String = "", val mixer: MixerInfo = MixerInfo(),
                     val play: PlaySettings = PlaySettings(), val lanes: List<Lane> = emptyList()) {
    val empty get() = type.isEmpty()
    val drums get() = lanes.isNotEmpty()
}
data class PatternInfo(val used: List<Boolean> = List(8) { false }, val any: Boolean = false, val bars: Int = 1)
data class MacroInfo(val name: String, val value: Float, val text: String)
data class MasterInfo(val volume: Float = 0.8f, val delayReturn: Float = 0.8f, val reverbReturn: Float = 0.8f, val glue: Float = 0.3f)
data class ProjectInfo(
    val name: String = "Untitled", val bpm: Double = 120.0, val beats: Int = 4, val unit: Int = 4, val swing: Float = 0.5f,
    val metronome: Boolean = false, val countIn: Boolean = true, val recordMode: Int = 0, val overdub: Boolean = false,
    val recordQuantize: Float = 1f, val switchQuantize: Int = 0, val perform: Boolean = false,
    val scaleRoot: Int = 0, val scaleIndex: Int = 1, val scaleName: String = "Major", val scaleIntervals: List<Int> = listOf(0, 2, 4, 5, 7, 9, 11),
    val master: MasterInfo = MasterInfo(), val channelPerTrack: Boolean = false,
)

/**
 * The UI's view of the engine. Commands go straight to the engine (cpp/Api.cpp); what they change is read back
 * with refresh(). Main thread only.
 */
object App {
    val fast = mutableStateOf(Fast())
    val project = mutableStateOf(ProjectInfo())
    val rack = mutableStateOf(List(8) { TrackInfo() })
    val patterns = mutableStateOf(List(8) { PatternInfo() })
    val macros = mutableStateOf<List<MacroInfo>>(emptyList())
    val focusTrack = mutableIntStateOf(1)
    var scaleNames: List<String> = emptyList()
        private set
    var fxPadNames: List<String> = emptyList()
        private set

    /** Per track, set on the phone only: the pads' octave and the drum roll rate (beats). */
    val octave = mutableStateOf(IntArray(8) { 0 }.toList())
    val rollBeats = mutableStateOf(0.25f)

    /** The pad notes held right now and the last one played: step input writes them (Seq). */
    val heldNotes = mutableStateOf(listOf<Int>())
    val lastNote = mutableIntStateOf(48)
    fun padPressed(note: Int, down: Boolean) {
        heldNotes.value = if (down) heldNotes.value + note else heldNotes.value - note
        if (down) lastNote.intValue = note
    }

    private val fastBuf = FloatArray(24)

    fun init() {
        scaleNames = JSONArray(Native.call("scales", "{}")).strings()
        fxPadNames = JSONArray(Native.call("fxPads", "{}")).strings()
        val st = JSONObject(Native.call("state", "{}"))
        focusTrack.intValue = st.optInt("focusTrack", 1)
        refresh()
    }

    fun poll() {
        Native.readFast(fastBuf)
        fast.value = Fast.from(fastBuf)
    }

    fun call(op: String, vararg args: Pair<String, Any?>): String {
        val o = JSONObject()
        for ((k, v) in args) o.put(k, v)
        return Native.call(op, o.toString())
    }

    /** A command, then the state it changed. */
    fun cmd(op: String, vararg args: Pair<String, Any?>) {
        call(op, *args)
        refresh()
    }

    fun refresh() {
        project.value = parseProject(JSONObject(call("state")))
        rack.value = JSONArray(call("rack")).objects().map(::parseTrack)
        patterns.value = JSONArray(call("patterns")).objects().map { o ->
            PatternInfo(o.getJSONArray("used").let { a -> List(a.length()) { a.getBoolean(it) } }, o.getBoolean("any"), o.getInt("bars"))
        }
        refreshMacros()
    }

    fun refreshMacros() {
        macros.value = JSONArray(call("macros", "track" to focusTrack.intValue)).objects().map {
            MacroInfo(it.getString("name"), it.getDouble("value").toFloat(), it.getString("text"))
        }
    }

    fun selectTrack(t: Int) {
        focusTrack.intValue = t
        Native.selectTrack(t)
        refreshMacros()
    }

    fun setMacro(m: Int, v: Float) {
        call("setMacro", "track" to focusTrack.intValue, "macro" to m, "value" to v)
        refreshMacros()
    }

    /** Saves the project under its name (an untitled one as "Autosave") and remembers it for the next launch. */
    fun autosave(context: android.content.Context) {
        val name = project.value.name.takeIf { it.isNotBlank() && it != "Untitled" } ?: "Autosave"
        val ok = JSONObject(call("saveProject", "name" to name)).optBoolean("ok")
        if (ok) com.pastel.minibox.engine.Prefs.setLastProject(context, name)
    }

    fun setOctave(track: Int, oct: Int) {
        octave.value = octave.value.toMutableList().also { it[track] = oct.coerceIn(-2, 3) }
    }

    private fun parseProject(o: JSONObject): ProjectInfo {
        val m = o.getJSONObject("master")
        return ProjectInfo(
            o.getString("name"), o.getDouble("bpm"), o.getInt("beats"), o.getInt("unit"), o.getDouble("swing").toFloat(),
            o.getBoolean("metronome"), o.getBoolean("countIn"), o.getInt("recordMode"), o.getBoolean("overdub"),
            o.getDouble("recordQuantize").toFloat(), o.getInt("switchQuantize"), o.getBoolean("perform"),
            o.getInt("scaleRoot"), o.getInt("scaleIndex"), o.getString("scaleName"),
            o.getJSONArray("scaleIntervals").let { a -> List(a.length()) { a.getInt(it) } },
            MasterInfo(m.getDouble("volume").toFloat(), m.getDouble("delayReturn").toFloat(),
                m.getDouble("reverbReturn").toFloat(), m.getDouble("glue").toFloat()),
            o.optBoolean("channelPerTrack"),
        )
    }

    private fun parseTrack(o: JSONObject): TrackInfo {
        val mx = o.getJSONObject("mixer")
        val pl = o.getJSONObject("play")
        return TrackInfo(
            o.getString("type"), o.getString("name"), o.getString("preset"),
            MixerInfo(mx.getDouble("level").toFloat(), mx.getDouble("pan").toFloat(), mx.getBoolean("mute"),
                mx.getBoolean("solo"), mx.getDouble("sendA").toFloat(), mx.getDouble("sendB").toFloat()),
            PlaySettings(pl.getBoolean("chordOn"), pl.getBoolean("arpOn"), pl.getBoolean("arpLatch"),
                pl.getInt("arpMode"), pl.getInt("arpRate"), pl.getInt("arpOct"),
                pl.getDouble("arpGate").toFloat(), pl.getDouble("arpSwing").toFloat(),
                pl.optInt("inversion"), pl.optBoolean("invLower"), pl.optBoolean("bassBelow")),
            o.getJSONArray("lanes").objects().map { Lane(it.getInt("note"), it.getString("name")) },
        )
    }
}

fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
