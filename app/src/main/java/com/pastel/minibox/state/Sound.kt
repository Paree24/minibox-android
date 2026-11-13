package com.pastel.minibox.state

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

data class Param(val index: Int, val id: String, val label: String, val value: Float, val text: String, val default: Float, val bipolar: Boolean,
                 val macro: Int = -1)   // a macro knob (the Wavetable's band): set with setMacro
/** A choice: a parameter, or (ui ≠ 0) a bit of the selection (which envelope / LFO a Wavetable card shows). */
data class Choice(val index: Int, val options: List<String>, val selected: Int, val ui: Int = 0, val families: List<String> = emptyList())
data class Visual(val kind: Int, val variant: Int, val values: List<Float>, val extra: JSONObject? = null)
data class Section(val title: String, val knobs: List<Param>, val choice: Choice?, val toggle: Param?, val visual: Visual,
                   val menu: Choice? = null, val band: Boolean = false)   // menu: a wavetable; band: the macro strip
data class Editor(val preset: String = "", val sections: List<Section> = emptyList(), val selectionNames: List<String> = emptyList(),
                  val engine: Int = -1)
data class Preset(val name: String, val family: String, val factory: Boolean)

/** The selected track's device editor, built from the desktop's EditorSpecs (cpp/SoundApi.cpp). */
object Sound {
    val editor = mutableStateOf(Editor())
    val selection = mutableIntStateOf(0)     // drum part / FM operator
    val tab = mutableIntStateOf(0)

    fun refresh() {
        val raw = App.call("editor", "track" to App.focusTrack.intValue, "selection" to selection.intValue)
        if (raw.isBlank() || raw == "null" || raw.startsWith("undefined")) { editor.value = Editor(); return }
        val o = JSONObject(raw)
        editor.value = Editor(
            o.getString("preset"),
            o.getJSONArray("sections").objects().filter { s ->
                // the desktop's part pads and lane views: the phone has part chips and the Seq tab for those
                val kind = s.getJSONObject("visual").getInt("kind")
                !((kind == 14 || kind == 15) && s.getJSONArray("knobs").length() == 0)
            }.map { s ->
                Section(
                    s.getString("title"),
                    s.getJSONArray("knobs").objects().map(::param),
                    s.optJSONObject("choice")?.let { c -> Choice(c.getInt("index"), c.getJSONArray("options").strings(), c.getInt("selected"), c.optInt("ui", 0)) },
                    s.optJSONObject("toggle")?.let(::param),
                    s.getJSONObject("visual").let { v ->
                        Visual(v.getInt("kind"), v.getInt("variant"), v.getJSONArray("values").let { a -> List(a.length()) { a.getDouble(it).toFloat() } },
                            v.optJSONObject("extra"))
                    },
                    s.optJSONObject("menu")?.let { c -> Choice(c.getInt("index"), c.getJSONArray("options").strings(), c.getInt("selected"),
                        families = c.getJSONArray("families").strings()) },
                    s.optBoolean("band", false),
                )
            },
            o.getJSONArray("selectionNames").strings(),
            o.optInt("engine", -1),
        )
    }

    private fun param(p: JSONObject) = Param(p.getInt("index"), p.getString("id"), p.optString("label", ""),
        p.getDouble("value").toFloat(), p.getString("text"), p.getDouble("default").toFloat(), p.getBoolean("bipolar"), p.optInt("macro", -1))

    /** A knob: its parameter, or its macro. */
    fun set(k: Param, value: Float) {
        if (k.macro >= 0) { App.call("setMacro", "track" to App.focusTrack.intValue, "macro" to k.macro, "value" to value); refresh() }
        else set(k.index, value)
    }

    /** A choice: its parameter, or its bit of the selection. */
    fun choose(c: Choice, i: Int) {
        if (c.ui != 0) selection.intValue = (selection.intValue and c.ui.inv()) or (if (i != 0) c.ui else 0)
        else set(c.index, if (c.options.size > 1) i / (c.options.size - 1f) else 0f)
    }

    /** The Wavetable's Randomize (scope: All, Oscillators, Filter & Env, Modulation, Dirt & Width). */
    fun randomize(scope: Int) {
        App.call("randomize", "track" to App.focusTrack.intValue, "scope" to scope)
        refresh()
    }

    fun set(index: Int, value: Float) {
        App.call("setParam", "track" to App.focusTrack.intValue, "index" to index, "value" to value)
        refresh()
    }

    fun presets(): List<Preset> = JSONArray(App.call("presets", "track" to App.focusTrack.intValue)).objects()
        .map { Preset(it.getString("name"), it.getString("family"), it.getBoolean("factory")) }

    fun applyPreset(i: Int) {
        App.call("applyPreset", "track" to App.focusTrack.intValue, "index" to i)
        refresh()
        App.refresh()
    }

    fun step(dir: Int) {
        val list = presets()
        if (list.isEmpty()) return
        val cur = list.indexOfFirst { it.name == editor.value.preset }
        applyPreset(((if (cur < 0) 0 else cur + dir) + list.size) % list.size)
    }

    fun save(name: String): Boolean = App.call("savePreset", "track" to App.focusTrack.intValue, "name" to name).trim() == "true"
}

data class FxParam(val index: Int, val name: String, val value: Float, val text: String, val default: Float, val choices: List<String>)
data class FxSlotInfo(val effect: String, val mix: Float, val bypass: Boolean, val mode: Int, val modes: List<String>, val params: List<FxParam>)

/** An effect chain: the selected track's 3 slots (Sound → FX), or the master's 8 (Mix → Effects). */
class FxChain(private val master: Boolean) {
    val slots = mutableStateOf(List(if (master) 8 else 3) { FxSlotInfo("", 1f, false, 0, emptyList(), emptyList()) })
    val choices = mutableStateOf<List<String>>(emptyList())   // state: the picker redraws when they arrive
    val isMaster get() = master
    private val track get() = if (master) -1 else App.focusTrack.intValue

    fun refresh() {
        val o = JSONObject(App.call("trackFx", "track" to track))
        choices.value = o.getJSONArray("choices").strings()
        slots.value = o.getJSONArray("slots").objects().map { s ->
            FxSlotInfo(s.getString("effect"), s.getDouble("mix").toFloat(), s.getBoolean("bypass"), s.getInt("mode"),
                s.getJSONArray("modes").strings(),
                s.getJSONArray("params").objects().map { p ->
                    FxParam(p.getInt("index"), p.getString("name"), p.getDouble("value").toFloat(), p.getString("text"),
                        p.getDouble("default").toFloat(), p.getJSONArray("choices").strings())
                })
        }
    }

    /** Puts an effect in a slot ("" removes it); returns where it went (the master chain stays packed). */
    fun setEffect(slot: Int, effect: String): Int {
        val at = App.call("setTrackFx", "track" to track, "slot" to slot, "effect" to effect).trim().toIntOrNull() ?: slot
        refresh()
        return at
    }
    /** Moves a slot one place (-1 left, +1 right); returns where it is now. */
    fun move(slot: Int, dir: Int): Int {
        val at = App.call("moveFx", "track" to track, "slot" to slot, "dir" to dir).trim().toIntOrNull() ?: slot
        refresh()
        return at
    }
    fun set(slot: Int, key: String, value: Any, index: Int = -1) {
        App.call("setTrackFxValue", "track" to track, "slot" to slot, "key" to key, "value" to value, "index" to index)
        refresh()
    }
}

val TrackFx = FxChain(false)
val MasterFxChain = FxChain(true)
