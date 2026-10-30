// Sound: the device editors, built from the desktop's EditorSpecs (one section per tab, mockups 07–08), and the
// preset browser.
#include "MiniboxHost.h"
#include "app/presets/PresetBank.h"
#include "ui/EditorSpecs.h"

namespace mb::android
{
namespace
{
juce::var obj() { return juce::var (new juce::DynamicObject()); }
void put (juce::var& o, const juce::Identifier& k, const juce::var& v) { o.getDynamicObject()->setProperty (k, v); }
int geti (const juce::var& a, const char* k, int def = 0) { return a.hasProperty (k) ? (int) a[k] : def; }

juce::var param (const DeviceProcessor& d, int i)
{
    auto o = obj();
    const auto& info = d.params()[i];
    put (o, "id", info.id); put (o, "index", i);
    put (o, "value", d.shownNorm (i)); put (o, "text", info.format (d.pv (i)));
    put (o, "default", info.toNorm (info.def)); put (o, "bipolar", info.bipolar);
    return o;
}
} // namespace

juce::var MiniboxHost::soundCall (const juce::String& op, const juce::var& a, bool& handled)
{
    handled = true;
    const int pat = engine.focusPattern.load();
    const int t = std::clamp (geti (a, "track", engine.focusTrack.load()), 0, kTracks - 1);
    auto* d = engine.device (pat, t);

    if (op == "editor")
    {
        if (d == nullptr) return {};
        const int sel = geti (a, "selection");
        const auto spec = ui::editorSpecFor (*d, sel);
        juce::Array<juce::var> sections;
        for (auto& row : spec.rows)
            for (auto& s : row)
            {
                auto o = obj();
                put (o, "title", s.title);
                juce::Array<juce::var> knobs;
                for (auto& k : s.knobs)
                {
                    juce::String id = k.id;
                    if (k.altWhen.isNotEmpty() && d->get (k.altWhen) != 0.0f) id = k.altId;
                    const int i = d->params().indexOf (id);
                    if (i < 0) continue;
                    auto kv = param (*d, i);
                    put (kv, "label", k.label);
                    knobs.add (kv);
                }
                put (o, "knobs", knobs);
                const int ci = s.choiceId.startsWith ("ui:") ? -1 : d->params().indexOf (s.choiceId);
                if (ci >= 0)
                {
                    auto c = param (*d, ci);
                    juce::Array<juce::var> opts;
                    for (auto& x : d->params()[ci].choices) opts.add (x);
                    if (opts.isEmpty())   // an integer choice: its values as text
                        for (int v = (int) d->params()[ci].min; v <= (int) d->params()[ci].max; ++v) opts.add (d->params()[ci].format ((float) v));
                    put (c, "options", opts);
                    put (c, "selected", (int) std::lround (d->pv (ci) - d->params()[ci].min));
                    put (o, "choice", c);
                }
                const int ti = d->params().indexOf (s.toggleId);
                if (ti >= 0)
                {
                    auto tg = param (*d, ti);
                    put (tg, "label", s.toggleLabel);
                    put (o, "toggle", tg);
                }
                auto vis = obj();
                put (vis, "kind", (int) s.visual.kind);
                put (vis, "variant", s.visual.variant);
                juce::Array<juce::var> vals;
                for (auto& id : s.visual.ids) { const int i = d->params().indexOf (id); vals.add (i >= 0 ? d->shownNorm (i) : 0.0f); }
                put (vis, "values", vals);
                put (o, "visual", vis);
                sections.add (o);
            }
        auto out = obj();
        put (out, "sections", sections);
        put (out, "preset", d->presetName);
        put (out, "engine", d->type() == DeviceType::Poly ? (int) d->get ("engine") : -1);   // VA: 0 DCO, 1 VCO
        put (out, "selections", d->type() == DeviceType::FM ? 6 : (d->laneCount() > 0 ? 8 : 0));
        juce::Array<juce::var> selNames;
        for (int l = 0; l < d->laneCount(); ++l) selNames.add (d->laneName (l));
        if (d->type() == DeviceType::FM) for (int op = 1; op <= 6; ++op) selNames.add ("Op " + juce::String (op));
        put (out, "selectionNames", selNames);
        return out;
    }
    if (op == "setParamId")   // a parameter by id, in its own units (the VA's engine switch)
    {
        if (d != nullptr && d->params().indexOf (a["id"].toString()) >= 0) d->set (a["id"].toString(), (float) (double) a["value"]);
        return {};
    }
    if (op == "setParam")
    {
        if (d == nullptr) return {};
        const int i = geti (a, "index", -1);
        if (i < 0 || i >= d->params().size()) return {};
        d->setNorm (i, (float) (double) a["value"]);
        engine.touchParam (pat, t, i, d->get (i));   // motion recording while recording
        return param (*d, i);
    }
    if (op == "presets")
    {
        juce::Array<juce::var> list;
        if (d != nullptr)
            for (auto& pi : presets::list (d->type()))
            {
                auto o = obj();
                put (o, "name", pi.name); put (o, "family", pi.family); put (o, "factory", pi.factory);
                list.add (o);
            }
        return list;
    }
    if (op == "applyPreset")
    {
        if (d == nullptr) return {};
        const auto all = presets::list (d->type());
        const int i = geti (a, "index", -1);
        if (i >= 0 && i < (int) all.size()) presets::apply (*d, all[(size_t) i]);
        return {};
    }
    if (op == "savePreset")
    {
        if (d == nullptr) return false;
        const auto name = presets::sanitiseName (a["name"].toString());
        if (name.isEmpty()) return false;
        const bool ok = presets::save (*d, name);
        presets::rescan();
        if (ok) d->presetName = name;
        return ok;
    }
    handled = false;
    return {};
}
} // namespace mb::android
