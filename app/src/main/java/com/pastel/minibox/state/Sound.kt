package com.pastel.minibox.state

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

data class Param(val index: Int, val id: String, val label: String, val value: Float, val text: String, val default: Float, val bipolar: Boolean)
data class Choice(val index: Int, val options: List<String>, val selected: Int)
data class Visual(val kind: Int, val variant: Int, val values: List<Float>)
data class Section(val title: String, val knobs: List<Param>, val choice: Choice?, val toggle: Param?, val visual: Visual)
data class Editor(val preset: String = "", val sections: List<Section> = emptyList(), val selectionNames: List<String> = emptyList())
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
                    s.optJSONObject("choice")?.let { c -> Choice(c.getInt("index"), c.getJSONArray("options").strings(), c.getInt("selected")) },
                    s.optJSONObject("toggle")?.let(::param),
                    s.getJSONObject("visual").let { v ->
                        Visual(v.getInt("kind"), v.getInt("variant"), v.getJSONArray("values").let { a -> List(a.length()) { a.getDouble(it).toFloat() } })
                    },
                )
            },
            o.getJSONArray("selectionNames").strings(),
        )
    }

    private fun param(p: JSONObject) = Param(p.getInt("index"), p.getString("id"), p.optString("label", ""),
        p.getDouble("value").toFloat(), p.getString("text"), p.getDouble("default").toFloat(), p.getBoolean("bipolar"))

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
