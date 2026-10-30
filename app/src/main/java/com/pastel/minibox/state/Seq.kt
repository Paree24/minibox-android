package com.pastel.minibox.state

import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

data class StepData(val step: Int, val notes: List<Int>, val velocity: Int, val length: Int, val probability: Int,
                    val ratchet: Int, val flags: Int, val micro: Int, val locked: Boolean) {
    val accent get() = flags and 1 != 0
    val slide get() = flags and 2 != 0
}
data class Cell(val step: Int, val velocity: Int, val probability: Int, val ratchet: Int, val micro: Int)
data class ClipNote(val id: Int, val start: Int, val length: Int, val note: Int, val velocity: Int, val flags: Int,
                    val muted: Boolean = false, val locked: Boolean = false)
data class SeqData(
    val bars: Int = 1, val maxBars: Int = 16, val stepsPerBar: Int = 16, val steps: Int = 16, val stepTicks: Int = 480,
    val pianoRoll: Boolean = false, val drum: Boolean = false, val canUndo: Boolean = false, val canRedo: Boolean = false,
    val stepRes: Int = 1, val loopOn: Boolean = false, val loopStart: Int = 0, val loopEnd: Int = 0,
    val stepList: Map<Int, StepData> = emptyMap(), val lanes: List<Map<Int, Cell>> = List(8) { emptyMap() },
    val clip: List<ClipNote> = emptyList(),
) {
    /** The notes as the piano roll shows them: the clip, or a step track's notes at their positions. */
    val rollNotes: List<ClipNote>
        get() = if (pianoRoll) clip else stepList.values.flatMap { s ->
            s.notes.map { n -> ClipNote(-1, s.step * stepTicks + s.micro * stepTicks / 48, maxOf(1, s.length * stepTicks / 128), n, s.velocity, s.flags) }
        }
}

/** The sequence of the selected track in the pattern being edited (cpp/SeqApi.cpp). */
object Seq {
    val data = mutableStateOf(SeqData())

    fun refresh() {
        val o = JSONObject(App.call("seq", "track" to App.focusTrack.intValue))
        data.value = SeqData(
            o.getInt("bars"), o.getInt("maxBars"), o.getInt("stepsPerBar"), o.getInt("steps"), o.getInt("stepTicks"),
            o.getBoolean("pianoRoll"), o.getBoolean("drum"), o.getBoolean("canUndo"), o.getBoolean("canRedo"), o.optInt("stepRes", 1), o.optBoolean("loopOn"), o.optInt("loopStart"), o.optInt("loopEnd"),
            o.optJSONArray("stepList")?.let { a ->
                List(a.length()) { a.getJSONArray(it) }.associate { r ->
                    val s = r.getInt(0)
                    s to StepData(s, r.getJSONArray(1).let { n -> List(n.length()) { n.getInt(it) } }, r.getInt(2), r.getInt(3),
                        r.getInt(4), r.getInt(5), r.getInt(6), r.getInt(7), r.getBoolean(8))
                }
            } ?: emptyMap(),
            o.optJSONArray("lanes")?.let { a ->
                List(a.length()) { l ->
                    val cells = a.getJSONArray(l)
                    List(cells.length()) { cells.getJSONArray(it) }.associate { r ->
                        r.getInt(0) to Cell(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4))
                    }
                }
            } ?: List(8) { emptyMap() },
            o.optJSONArray("clip")?.let { a ->
                List(a.length()) { a.getJSONArray(it) }.map { r ->
                    ClipNote(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5), r.optBoolean(6), r.optBoolean(7))
                }
            } ?: emptyList(),
        )
    }

    /** An edit of the selected track, then the sequence read back. */
    fun edit(op: String, vararg args: Pair<String, Any?>): String {
        val r = App.call(op, "track" to App.focusTrack.intValue, *args)
        refresh()
        return r
    }

    data class Lock(val macro: Int, val name: String, val value: Float, val text: String, val locked: Boolean)

    fun locks(step: Int): List<Lock> = JSONArray(App.call("stepLocks", "track" to App.focusTrack.intValue, "step" to step))
        .objects().map { Lock(it.getInt("macro"), it.getString("name"), it.getDouble("value").toFloat(), it.getString("text"), it.getBoolean("locked")) }
}
