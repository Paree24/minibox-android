// Sound: the device editors, built from the desktop's EditorSpecs (one section per tab, mockups 07–08), and the
// preset browser.
#include "MiniboxHost.h"
#include "app/presets/PresetBank.h"
#include "ui/EditorSpecs.h"
#include "devices/fm/FmDevice.h"
#include "devices/fm/Algorithms.h"
#include "devices/sampler/SamplerDevice.h"
#include "devices/drumsampler/DrumSamplerDevice.h"
#include "devices/wavetable/WavetableDevice.h"
#include "dsp/Wavetables.h"

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

namespace
{
juce::Array<juce::var> peaksOf (const SamplePtr& data)
{
    juce::Array<juce::var> out;
    if (data) for (float v : data->peaks) out.add (v);
    return out;
}

/** The name of the sample a pad or zone is set to (its file), not of the one loaded: a kit loads in the background. */
juce::String sampleName (const juce::String& ref, const SamplePtr& data)
{
    if (ref.isEmpty()) return data ? data->name : juce::String();
    auto n = ref.fromLastOccurrenceOf ("/", false, false).fromLastOccurrenceOf ("factory:", false, false);
    return n.containsChar ('.') ? n.upToLastOccurrenceOf (".", false, false) : n;
}

/** The loaded sample when it is the one set (else null: still loading, the previous one is in its place). */
SamplePtr current (const juce::String& ref, const SamplePtr& data)
{
    return data && (ref.isEmpty() || data->name == sampleName (ref, data)) ? data : SamplePtr();
}
} // namespace

/** What a visual draws beyond its parameters: the FM algorithm's graph and operators, sample waveforms with their
    markers and zones, a loop's slices, the Acid stack's intervals. */
juce::var MiniboxHost::visualExtra (const DeviceProcessor& dev, const ui::VisualSpec& vs, int sel)
{
    auto o = obj();
    using VK = ui::VisualKind;
    auto& d = const_cast<DeviceProcessor&> (dev);
    if (vs.kind == VK::Algo)
    {
        const int alg = std::clamp ((int) std::lround (d.get ("algorithm")), 1, 32);
        const auto& A = fm::algorithms()[(size_t) alg - 1];
        const auto L = fm::layoutFor (alg);
        juce::Array<juce::var> stacks, mods;
        for (int k = 0; k < L.numStacks; ++k)
        {
            juce::Array<juce::var> st;
            for (int j = 0; j < L.stackLen[k]; ++j) st.add (L.stacks[k][j]);
            stacks.add (st);
        }
        for (auto m : A.mods) mods.add ((int) m);
        put (o, "stacks", stacks); put (o, "mods", mods); put (o, "carriers", (int) A.carriers);
        put (o, "feedbackOp", A.feedbackOp); put (o, "selected", sel);
    }
    else if (vs.kind == VK::Ops)
    {
        if (auto* fm = dynamic_cast<FmDevice*> (&d))
        {
            juce::Array<juce::var> ops;
            for (int op = 0; op < 6; ++op)
            {
                auto x = obj();
                put (x, "carrier", fm->isCarrier (op));
                put (x, "muted", fm->get (fm->opParam (op, FmDevice::Mute)) > 0.5f);
                put (x, "fixed", fm->get (fm->opParam (op, FmDevice::Fixed)) > 0.5f);
                put (x, "freq", fm->get (fm->opParam (op, FmDevice::Freq)));
                put (x, "ratio", fm->get (fm->opParam (op, FmDevice::Ratio)));
                put (x, "level", fm->get (fm->opParam (op, FmDevice::Level)));
                put (x, "wave", (int) fm->get (fm->opParam (op, FmDevice::Wave)));
                put (x, "l1", fm->get (fm->opParam (op, FmDevice::L1))); put (x, "l3", fm->get (fm->opParam (op, FmDevice::L3)));
                put (x, "r1", fm->get (fm->opParam (op, FmDevice::R1)));
                ops.add (x);
            }
            put (o, "ops", ops); put (o, "selected", sel);
        }
    }
    else if (vs.kind == VK::Sample)
    {
        if (auto* sd = dynamic_cast<SamplerDevice*> (&d))
        {
            const auto data = sd->zone[std::clamp (sel, 0, SamplerDevice::kZones - 1)].get();
            const auto reg = sd->region (0);
            const bool loop = (int) sd->get (SamplerDevice::playMode) == 2;
            put (o, "peaks", peaksOf (current (sd->zoneRef (sel), data))); put (o, "name", sampleName (sd->zoneRef (sel), data));
            put (o, "s", reg.s); put (o, "e", reg.e); put (o, "ls", loop ? reg.ls : -1.0); put (o, "le", loop ? reg.le : -1.0);
            juce::Array<juce::var> zones;
            for (int z = 0; z < SamplerDevice::kZones; ++z)
            {
                if (sd->zoneRef (z).isEmpty()) continue;
                auto zv = obj();
                put (zv, "lo", sd->get (sd->zoneLo (z))); put (zv, "hi", sd->get (sd->zoneHi (z))); put (zv, "root", sd->get (sd->zoneRoot (z)));
                put (zv, "sel", z == sel);
                zones.add (zv);
            }
            put (o, "zones", zones);
        }
        else if (auto* ds = dynamic_cast<DrumSamplerDevice*> (&d))
        {
            const int p = std::clamp (sel, 0, 7);
            const auto data = ds->pad[p].get();
            const float st = ds->get (ds->padParam (p, DrumSamplerDevice::Start));
            put (o, "peaks", peaksOf (current (ds->padRef (p), data))); put (o, "name", sampleName (ds->padRef (p), data));
            put (o, "s", st); put (o, "e", st + ds->get (ds->padParam (p, DrumSamplerDevice::Length)) * (1.0f - st));
            put (o, "ls", -1.0); put (o, "le", -1.0);
        }
    }
    else if (vs.kind == VK::Slices)
    {
        if (auto* ds = dynamic_cast<DrumSamplerDevice*> (&d))
        {
            const auto data = ds->pad[0].get();
            bool shared = data != nullptr;
            for (int p = 1; p < 8 && shared; ++p) shared = ds->padRef (p) == ds->padRef (0);
            put (o, "peaks", shared ? peaksOf (current (ds->padRef (0), data)) : juce::Array<juce::var>());
            put (o, "name", shared ? sampleName (ds->padRef (0), data) : juce::String());
            juce::Array<juce::var> starts, ends;
            for (int p = 0; p < 8; ++p)
            {
                const float st = ds->get (ds->padParam (p, DrumSamplerDevice::Start));
                starts.add (st);
                ends.add (st + ds->get (ds->padParam (p, DrumSamplerDevice::Length)) * (1.0f - st));
            }
            put (o, "starts", starts); put (o, "ends", ends); put (o, "selected", sel);
        }
    }
    else if (vs.kind == VK::Wavetable && vs.ids.size() >= 2)
    {
        // the table's frames, a few points each (the phone draws the stack from them)
        const auto& bank = dsp::wt::Bank::get();
        const int table = (int) d.get (vs.ids[0]);
        juce::Array<juce::var> frames;
        for (int k = 0; k < 9; ++k)
        {
            juce::Array<juce::var> pts;
            for (int i = 0; i <= 64; ++i) pts.add (bank.sample (table, 3, k / 8.0f, std::min (i / 64.0f, 0.9999f)));
            frames.add (pts);
        }
        juce::Array<juce::var> lit;   // the frame at the position itself
        const float pos = d.pv (d.params().indexOf (vs.ids[1]));
        for (int i = 0; i <= 64; ++i) lit.add (bank.sample (table, 3, pos, std::min (i / 64.0f, 0.9999f)));
        put (o, "frames", frames); put (o, "lit", lit); put (o, "pos", pos);
        put (o, "posIndex", d.params().indexOf (vs.ids[1]));
    }
    else if (vs.kind == VK::Matrix)
    {
        juce::Array<juce::var> slots, srcs, dsts;
        for (int s = 0; s < WavetableDevice::kSlots; ++s)
        {
            auto o2 = obj();
            put (o2, "src", (int) d.get (WavetableDevice::slotParam (s, 0))); put (o2, "dst", (int) d.get (WavetableDevice::slotParam (s, 1)));
            put (o2, "amt", d.pv (WavetableDevice::slotParam (s, 2)));
            slots.add (o2);
        }
        for (auto& n : WavetableDevice::sourceNames()) srcs.add (n);
        for (auto& n : WavetableDevice::destNames()) dsts.add (n);
        put (o, "slots", slots); put (o, "sources", srcs); put (o, "dests", dsts); put (o, "base", (int) WavetableDevice::mod1Src);
    }
    else if (vs.kind == VK::Voices && vs.variant == 3 && d.params().indexOf ("stackMode") >= 0)
    {
        put (o, "stackMode", (int) d.get ("stackMode")); put (o, "stacked", d.get ("stack") > 0.5f);
    }
    return o;
}

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
                    if (const int m = ui::macroIndexOf (k.id); m >= 0)   // a macro: its own value, set with setMacro
                    {
                        auto kv = obj();
                        put (kv, "id", k.id); put (kv, "index", -1); put (kv, "macro", m); put (kv, "label", k.label);
                        put (kv, "value", d->shownMacro (m)); put (kv, "text", juce::String (juce::roundToInt (d->shownMacro (m) * 100)) + "%");
                        put (kv, "default", 0.5); put (kv, "bipolar", false);
                        knobs.add (kv);
                        continue;
                    }
                    juce::String id = k.id;
                    if (k.altWhen.isNotEmpty() && d->get (k.altWhen) != 0.0f) id = k.altId;
                    const int i = d->params().indexOf (id);
                    if (i < 0) continue;
                    auto kv = param (*d, i);
                    put (kv, "label", k.label);
                    knobs.add (kv);
                }
                put (o, "knobs", knobs);
                if (const int bit = ui::uiBit (s.choiceId); bit != 0)   // which envelope / LFO: a bit of the selection
                {
                    auto c = obj();
                    juce::Array<juce::var> opts;
                    for (auto& x : ui::uiChoices (s.choiceId)) opts.add (x);
                    put (c, "index", -1); put (c, "ui", bit); put (c, "options", opts); put (c, "selected", (sel & bit) != 0 ? 1 : 0);
                    put (o, "choice", c);
                }
                if (const int mi = d->params().indexOf (s.menuId); mi >= 0)   // a wavetable: a menu, by family
                {
                    auto c = obj();
                    juce::Array<juce::var> opts, fams;
                    for (auto& x : d->params()[mi].choices) opts.add (x);
                    for (auto& x : dsp::wt::Bank::families()) fams.add (x);
                    put (c, "index", mi); put (c, "options", opts); put (c, "families", fams); put (c, "selected", (int) d->get (mi));
                    put (o, "menu", c);
                }
                put (o, "band", s.band);
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
                // raw values, as the desktop's drawings use them (Hz, seconds, choice indices …): the playing value
                juce::Array<juce::var> vals;
                for (auto& id : s.visual.ids) { const int i = d->params().indexOf (id); vals.add (i >= 0 ? d->pv (i) : 0.0f); }
                put (vis, "values", vals);
                put (vis, "extra", visualExtra (*d, s.visual, sel));
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
    if (op == "randomize")   // the Wavetable: new settings at random (scope: WavetableDevice::randomizeScopes)
    {
        if (auto* w = dynamic_cast<WavetableDevice*> (d)) w->randomize (geti (a, "scope"), (uint32_t) juce::Random::getSystemRandom().nextInt());
        return {};
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
