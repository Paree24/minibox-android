// Projects (.minibox, the desktop's format: they move between phone and desktop), export (WAV, MIDI, DAWproject),
// the master effects chain, and samples brought in from the phone's files.
#include "MiniboxHost.h"
#include "model/Serializer.h"
#include "model/MidiFiles.h"
#include "model/DawProject.h"
#include "devices/sampler/SamplerDevice.h"
#include "devices/drumsampler/DrumSamplerDevice.h"
#include "efx/EffectRegistry.h"

namespace mb::android
{
namespace
{
juce::var obj() { return juce::var (new juce::DynamicObject()); }
void put (juce::var& o, const juce::Identifier& k, const juce::var& v) { o.getDynamicObject()->setProperty (k, v); }
int geti (const juce::var& a, const char* k, int def = 0) { return a.hasProperty (k) ? (int) a[k] : def; }

juce::var result (bool ok, const juce::String& error = {})
{
    auto o = obj();
    put (o, "ok", ok); put (o, "error", error);
    return o;
}
} // namespace

juce::File MiniboxHost::projectsDir() const { return storage.getChildFile ("Projects"); }

juce::var MiniboxHost::projectCall (const juce::String& op, const juce::var& a, bool& handled)
{
    handled = true;
    auto& p = engine.project();
    auto fileFor = [&] (const juce::String& name) { return projectsDir().getChildFile (juce::File::createLegalFileName (name) + ".minibox"); };

    if (op == "projects")
    {
        projectsDir().createDirectory();
        juce::Array<juce::var> list;
        auto files = projectsDir().findChildFiles (juce::File::findFiles, false, "*.minibox");
        std::sort (files.begin(), files.end(), [] (const juce::File& x, const juce::File& y) { return x.getLastModificationTime() > y.getLastModificationTime(); });
        for (auto& f : files)
        {
            auto o = obj();
            put (o, "name", f.getFileNameWithoutExtension());
            put (o, "modified", f.getLastModificationTime().toMilliseconds());
            list.add (o);
        }
        return list;
    }
    if (op == "saveProject")
    {
        const auto name = a["name"].toString().trim();
        if (name.isEmpty()) return result (false, "No name");
        projectsDir().createDirectory();
        p.name = name;
        const auto r = serializer::save (engine, fileFor (name));
        return result (r.ok, r.error);
    }
    if (op == "openProject")
    {
        const auto r = serializer::load (engine, fileFor (a["name"].toString()));
        if (r.ok) afterProjectChange();
        return result (r.ok, r.error);
    }
    if (op == "openFile")   // a .minibox the phone's files handed us (Storage Access Framework, copied into cache)
    {
        const auto r = serializer::load (engine, juce::File (a["path"].toString()));
        if (r.ok) afterProjectChange();
        return result (r.ok, r.error);
    }
    if (op == "newProject")
    {
        engine.stop(); engine.stop();
        engine.newProject();
        afterProjectChange();
        return result (true);
    }
    if (op == "deleteProject") { return result (fileFor (a["name"].toString()).deleteFile()); }
    if (op == "renameProject")
    {
        const auto to = a["to"].toString().trim();
        const bool ok = to.isNotEmpty() && fileFor (a["name"].toString()).moveFileTo (fileFor (to));
        if (ok && p.name == a["name"].toString()) p.name = to;
        return result (ok);
    }
    if (op == "export")   // into the given file (the app's cache); the UI shares it
    {
        const auto kind = a["kind"].toString();
        const juce::File f (a["path"].toString());
        f.deleteFile();
        if (kind == "wav") { const auto r = serializer::exportWav (engine, f, std::max (1, geti (a, "loops", 2)), engine.getSampleRate()); return result (r.ok, r.error); }
        if (kind == "mid") { const auto r = midifile::exportPattern (engine, engine.activePattern(), f); return result (r.ok, r.error); }
        if (kind == "dawproject") { const auto r = dawproject::exportProject (engine, f); return result (r.ok, r.error); }
        if (kind == "minibox") { const auto r = serializer::save (engine, f); return result (r.ok, r.error); }
        return result (false, "Unknown format");
    }

    // ---- the master effects chain (MasterFx; the 8 performance pads are on Perform)
    if (op == "masterFx")
    {
        auto o = obj();
        juce::Array<juce::var> slots;
        for (int i = 0; i < kMasterFxSlots; ++i)
        {
            auto s = obj();
            const auto& fx = p.masterFx[(size_t) i];
            put (s, "effect", fx.effect); put (s, "mix", fx.mix); put (s, "bypass", fx.bypass);
            slots.add (s);
        }
        put (o, "slots", slots);
        juce::Array<juce::var> names;
        for (int id : MasterFx::choosableEffects()) if (const auto* d = efx::defForId (id)) names.add (juce::String (d->name));
        put (o, "choices", names);
        return o;
    }
    if (op == "setMasterFx") { masterFx.setSlotEffect (false, std::clamp (geti (a, "index"), 0, kMasterFxSlots - 1), a["effect"].toString()); return {}; }
    if (op == "removeMasterFx") { masterFx.removeSlot (std::clamp (geti (a, "index"), 0, kMasterFxSlots - 1)); return {}; }
    if (op == "setMasterFxValue")
    {
        auto& fx = p.masterFx[(size_t) std::clamp (geti (a, "index"), 0, kMasterFxSlots - 1)];
        if (a["key"].toString() == "mix") fx.mix = juce::jlimit (0.0f, 1.0f, (float) a["value"]);
        else if (a["key"].toString() == "bypass") fx.bypass = (bool) a["value"];
        return {};
    }

    // ---- a track's 3 effect slots (Sound → FX)
    if (op == "trackFx")
    {
        const int pat = engine.focusPattern.load(), t = std::clamp (geti (a, "track"), 0, kTracks - 1);
        auto o = obj();
        juce::Array<juce::var> slots;
        for (int i = 0; i < kTrackFx; ++i)
        {
            const auto& fx = p.patterns[(size_t) pat].slots[(size_t) t].fx[(size_t) i];
            auto s = obj();
            put (s, "effect", fx.effect); put (s, "mix", fx.mix); put (s, "bypass", fx.bypass); put (s, "mode", fx.mode);
            juce::Array<juce::var> params, modes;
            if (const auto* d = efx::defForId (efx::idForName (fx.effect)))
            {
                for (int m = 0; m < d->numModes; ++m) modes.add (juce::String (d->modes[m]));
                for (int k = 0; k < d->numParams; ++k)
                {
                    if (! efx::paramVisible (*d, k, fx.mode, fx.norm.data())) continue;
                    const auto& ps = d->p[k];
                    auto pv = obj();
                    put (pv, "index", k); put (pv, "name", juce::String (ps.name)); put (pv, "value", fx.norm[(size_t) k]);
                    put (pv, "text", efx::formatParam (ps, fx.norm[(size_t) k])); put (pv, "default", ps.def);
                    juce::Array<juce::var> ch;
                    for (int c = 0; c < ps.numChoices; ++c) ch.add (juce::String (ps.choices[c]));
                    put (pv, "choices", ch);
                    params.add (pv);
                }
            }
            put (s, "modes", modes); put (s, "params", params);
            slots.add (s);
        }
        put (o, "slots", slots);
        juce::Array<juce::var> names;
        for (int id : MasterFx::choosableEffects()) if (const auto* d = efx::defForId (id)) names.add (juce::String (d->name));
        put (o, "choices", names);
        return o;
    }
    if (op == "setTrackFx")
    {
        masterFx.setTrackEffect (engine.focusPattern.load(), std::clamp (geti (a, "track"), 0, kTracks - 1),
                                 std::clamp (geti (a, "slot"), 0, kTrackFx - 1), a["effect"].toString());
        return {};
    }
    if (op == "setTrackFxValue")
    {
        auto& fx = p.patterns[(size_t) engine.focusPattern.load()].slots[(size_t) std::clamp (geti (a, "track"), 0, kTracks - 1)]
                    .fx[(size_t) std::clamp (geti (a, "slot"), 0, kTrackFx - 1)];
        const auto k = a["key"].toString();
        const auto v = a["value"];
        if (k == "mix") fx.mix = juce::jlimit (0.0f, 1.0f, (float) v);
        else if (k == "bypass") fx.bypass = (bool) v;
        else if (k == "mode") fx.mode = std::max (0, (int) v);
        else if (k == "param") { const int i = geti (a, "index", -1); if (i >= 0 && i < FxSlot::kParams) fx.norm[(size_t) i] = juce::jlimit (0.0f, 1.0f, (float) v); }
        return {};
    }

    // ---- samples: a file from the phone (copied into app storage) onto the Sampler or a Drum Sampler pad
    if (op == "loadSample")
    {
        const int pat = engine.focusPattern.load();
        auto* d = engine.device (pat, std::clamp (geti (a, "track"), 0, kTracks - 1));
        const juce::File f (a["path"].toString());
        if (d == nullptr || ! f.existsAsFile()) return result (false, "No file");
        const auto ref = refForFile (f);
        if (auto* s = dynamic_cast<SamplerDevice*> (d)) { s->loadZone (0, ref); return result (true); }
        if (auto* ds = dynamic_cast<DrumSamplerDevice*> (d))
        {
            if (a["slice"]) ds->sliceLoop (ref);
            else ds->loadPad (std::clamp (geti (a, "pad"), 0, 7), ref);
            return result (true);
        }
        return result (false, "This device takes no samples");
    }
    handled = false;
    return {};
}

void MiniboxHost::afterProjectChange()
{
    masterFx.sync();
    undoStack.clear();
    redoStack.clear();
}
} // namespace mb::android
