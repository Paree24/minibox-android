// The UI's commands and queries (main thread): `call (op, args)` with JSON in and out. The hot paths (pads, rolls,
// FX holds, the playhead and meters) bypass this: see Bridge.cpp.
#include "MiniboxHost.h"
#include "app/presets/PresetBank.h"
#include "efx/MasterFx.h"
#include "seq/Scales.h"
#include "efx/EffectRegistry.h"

namespace mb::android
{
namespace
{
juce::var obj() { return juce::var (new juce::DynamicObject()); }
void put (juce::var& o, const juce::Identifier& k, const juce::var& v) { o.getDynamicObject()->setProperty (k, v); }
int geti (const juce::var& a, const char* k, int def = 0) { return a.hasProperty (k) ? (int) a[k] : def; }
double getd (const juce::var& a, const char* k, double def = 0) { return a.hasProperty (k) ? (double) a[k] : def; }
bool getb (const juce::var& a, const char* k) { return a.hasProperty (k) && (bool) a[k]; }

const char* kTypeIds[] = { "mono", "poly", "acid", "fm", "sampler", "drums", "drumsampler" };
} // namespace

/** The two parameters a performance effect's XY pad plays (indices into the effect's controls): X then Y. */
std::pair<int, int> MiniboxHost::performAxes (int pad)
{
    static const std::pair<const char*, std::pair<int, int>> axes[] = {
        { "Stutter", { 0, 9 } },            // Div × Filter
        { "Glitch", { 0, 2 } },             // Density × Repeat
        { "Buffer Shuffler", { 0, 1 } },    // Div × Chance
        { "Slicer", { 0, 7 } },             // Div × Depth
        { "Bitcrusher", { 1, 0 } },         // Rate × Bits
        { "Multimode Filter", { 0, 1 } },   // Cutoff × Reso
        { "Soft Clip Drive", { 0, 3 } },    // Drive × Tone
        { "Reverse", { 0, 2 } },            // Window × Width
    };
    const auto& name = engine.project().performFx[(size_t) std::clamp (pad, 0, kPerformFx - 1)].effect;
    for (auto& [n, xy] : axes) if (name == n) return xy;
    return { 0, 1 };
}

juce::var MiniboxHost::call (const juce::String& op, const juce::var& a)
{
    auto& p = engine.project();
    const int pat = engine.focusPattern.load();
    auto slot = [&] (int t) -> SlotModel& { return p.patterns[(size_t) pat].slots[(size_t) std::clamp (t, 0, kTracks - 1)]; };

    // ---------------------------------------------------------------- queries
    if (op == "state")
    {
        auto o = obj();
        put (o, "name", p.name);
        put (o, "bpm", p.bpm);
        put (o, "beats", p.beats); put (o, "unit", p.unit);
        put (o, "swing", p.swing);
        put (o, "metronome", p.metronome); put (o, "countIn", p.countIn);
        put (o, "recordMode", p.recordMode); put (o, "overdub", p.overdub); put (o, "recordQuantize", p.recordQuantize);
        put (o, "switchQuantize", p.switchQuantize); put (o, "perform", p.perform);
        put (o, "channelPerTrack", p.channelPerTrack);
        put (o, "focusPattern", pat); put (o, "focusTrack", engine.focusTrack.load());
        put (o, "scaleRoot", p.scale.root); put (o, "scaleIndex", p.scale.scale);
        juce::Array<juce::var> iv;
        for (int i : Scales::factoryScales()[(size_t) std::clamp (p.scale.scale, 0, (int) Scales::factoryScales().size() - 1)].intervals) iv.add (i);
        put (o, "scaleIntervals", iv);
        put (o, "scaleName", juce::String (Scales::factoryScales()[(size_t) std::clamp (p.scale.scale, 0, (int) Scales::factoryScales().size() - 1)].name));
        auto m = obj();
        put (m, "volume", p.master.volume); put (m, "delayReturn", p.master.delayReturn); put (m, "reverbReturn", p.master.reverbReturn);
        put (m, "glue", p.master.glue); put (m, "limitDb", p.master.limitDb);
        put (o, "master", m);
        return o;
    }
    if (op == "scales")
    {
        juce::Array<juce::var> names;
        for (auto& s : Scales::factoryScales()) names.add (juce::String (s.name));
        return names;
    }
    if (op == "rack")
    {
        juce::Array<juce::var> tracks;
        for (int t = 0; t < kTracks; ++t)
        {
            auto o = obj();
            const auto* d = engine.device (pat, t);
            const auto& s = slot (t);
            put (o, "type", d != nullptr ? juce::String (kTypeIds[(int) d->type()]) : juce::String());
            put (o, "name", d != nullptr ? deviceTypeName (d->type()) : juce::String());
            put (o, "preset", d != nullptr ? d->presetName : juce::String());
            auto mx = obj();
            put (mx, "level", s.mixer.level); put (mx, "pan", s.mixer.pan); put (mx, "mute", s.mixer.mute);
            put (mx, "solo", s.mixer.solo); put (mx, "sendA", s.mixer.sendA); put (mx, "sendB", s.mixer.sendB);
            put (o, "mixer", mx);
            auto pl = obj();
            put (pl, "chordOn", s.play.chordOn); put (pl, "arpOn", s.play.arpOn); put (pl, "arpLatch", s.play.arpLatch);
            put (pl, "arpMode", s.play.arpMode); put (pl, "arpRate", s.play.arpRate); put (pl, "arpOct", s.play.arpOct);
            put (pl, "arpGate", s.play.arpGate); put (pl, "arpSwing", s.play.arpSwing);
            put (pl, "inversion", s.play.inversion); put (pl, "invLower", s.play.invLower); put (pl, "bassBelow", s.play.bassBelow);
            put (o, "play", pl);
            juce::Array<juce::var> lanes;
            if (d != nullptr)
                for (int l = 0; l < d->laneCount(); ++l)
                {
                    auto ln = obj();
                    put (ln, "note", d->laneNote (l)); put (ln, "name", d->laneName (l));
                    lanes.add (ln);
                }
            put (o, "lanes", lanes);
            tracks.add (o);
        }
        return tracks;
    }
    if (op == "patterns")
    {
        juce::Array<juce::var> list;
        for (int i = 0; i < kPatterns; ++i)
        {
            auto o = obj();
            juce::Array<juce::var> used;
            bool any = false;
            for (int t = 0; t < kTracks; ++t) { const bool u = engine.device (i, t) != nullptr; used.add (u); any = any || u; }
            put (o, "used", used); put (o, "any", any);
            put (o, "bars", p.patterns[(size_t) i].lengthBars);
            list.add (o);
        }
        return list;
    }
    if (op == "macros")
    {
        juce::Array<juce::var> list;
        if (auto* d = engine.device (pat, geti (a, "track")))
            for (int m = 0; m < 8; ++m)
            {
                auto o = obj();
                const auto& mm = d->macros()[(size_t) m];
                put (o, "name", mm.name);
                put (o, "value", d->shownMacro (m));
                put (o, "text", mm.count > 0 ? d->params()[mm.targets[0].param].format (d->pv (mm.targets[0].param)) : juce::String());
                list.add (o);
            }
        return list;
    }
    if (op == "performXY")   // the 8 performance effects and the two parameters each XY pad plays (Perform)
    {
        juce::Array<juce::var> list;
        for (int i = 0; i < kPerformFx; ++i)
        {
            const auto [xi, yi] = performAxes (i);
            const auto* d = efx::defForId (efx::idForName (p.performFx[(size_t) i].effect));
            auto o = obj();
            put (o, "name", juce::String (MasterFx::padName (i)));
            put (o, "x", xi); put (o, "y", yi);
            put (o, "xName", d != nullptr ? juce::String (d->p[xi].name) : juce::String());
            put (o, "yName", d != nullptr ? juce::String (d->p[yi].name) : juce::String());
            list.add (o);
        }
        return list;
    }
    if (op == "setPerformXY")   // the finger on an XY pad: the effect's two parameters (0–1 knob positions)
    {
        const int pad = std::clamp (geti (a, "pad"), 0, kPerformFx - 1);
        const auto [xi, yi] = performAxes (pad);
        auto& fx = p.performFx[(size_t) pad];
        fx.norm[(size_t) xi] = juce::jlimit (0.0f, 1.0f, (float) getd (a, "x"));
        fx.norm[(size_t) yi] = juce::jlimit (0.0f, 1.0f, (float) getd (a, "y"));
        return {};
    }
    if (op == "fxPads")
    {
        juce::Array<juce::var> names;
        for (int i = 0; i < kPerformFx; ++i) names.add (juce::String (MasterFx::padName (i)));
        return names;
    }

    // ---------------------------------------------------------------- commands
    if (op == "play") { engine.play(); return {}; }
    if (op == "stop") { engine.stop(); return {}; }
    if (op == "setRecord")   // the phone records live only (a project saved on the desktop in Step mode too)
    {
        if (getb (a, "on")) engine.project().recordMode = 0;
        engine.setRecord (getb (a, "on"));
        return {};
    }
    if (op == "setTimeSig") { engine.setTimeSignature (geti (a, "beats", 4), geti (a, "unit", 4)); return {}; }
    if (op == "queuePattern") { engine.queuePattern (geti (a, "pattern")); return {}; }
    if (op == "copyPattern") { engine.copyPattern (geti (a, "from"), geti (a, "to")); masterFx.sync(); return {}; }   // its track effects too
    if (op == "clearPattern") { engine.clearPattern (geti (a, "pattern")); masterFx.sync(); return {}; }
    if (op == "focusPattern") { engine.focusPattern = std::clamp (geti (a, "pattern"), 0, kPatterns - 1); return {}; }
    if (op == "setMacro")
    {
        if (auto* d = engine.device (pat, geti (a, "track")))
        {
            const int m = std::clamp (geti (a, "macro"), 0, 7);
            const float v = (float) getd (a, "value");
            d->setMacro (m, v);
            engine.recordMacro (d, m, v);   // a take writes it (recording and playing only)
        }
        return {};
    }
    if (op == "setDevice")
    {
        const int t = std::clamp (geti (a, "track"), 0, kTracks - 1);
        const auto id = a["type"].toString();
        std::unique_ptr<DeviceProcessor> d;
        if (id.isNotEmpty())
        {
            const auto type = deviceTypeFromId (id);
            if (type == DeviceType::Count) return {};
            d = createDevice (type);
            presets::loadDefault (*d);
        }
        engine.setDevice (pat, t, std::move (d)).reset();   // the old one is freed here, outside the lock
        if (engine.device (pat, t) != nullptr) engine.focusTrack = t;
        return {};
    }
    if (op == "setMixer")
    {
        auto& m = slot (geti (a, "track")).mixer;
        const auto k = a["key"].toString();
        const auto v = a["value"];
        if (k == "level") m.level = (float) v; else if (k == "pan") m.pan = (float) v;
        else if (k == "sendA") m.sendA = (float) v; else if (k == "sendB") m.sendB = (float) v;
        else if (k == "mute") m.mute = (bool) v; else if (k == "solo") m.solo = (bool) v;
        return {};
    }
    if (op == "setPlay")
    {
        auto& s = slot (geti (a, "track")).play;
        const auto k = a["key"].toString();
        const auto v = a["value"];
        if (k == "chordOn") s.chordOn = (bool) v; else if (k == "arpOn") s.arpOn = (bool) v;
        else if (k == "arpLatch") s.arpLatch = (bool) v; else if (k == "arpMode") s.arpMode = std::clamp ((int) v, 0, 5);
        else if (k == "arpRate") s.arpRate = std::clamp ((int) v, 0, 14); else if (k == "arpOct") s.arpOct = std::clamp ((int) v, 0, 3);
        else if (k == "arpGate") s.arpGate = juce::jlimit (10.0f, 100.0f, (float) v);   // %, as the desktop's Play drawer
        else if (k == "arpSwing") s.arpSwing = juce::jlimit (0.0f, 60.0f, (float) v);
        else if (k == "inversion") s.inversion = std::clamp ((int) v, 0, 2);
        else if (k == "invLower") s.invLower = (bool) v; else if (k == "bassBelow") s.bassBelow = (bool) v;
        return {};
    }
    if (op == "set")
    {
        const auto k = a["key"].toString();
        const auto v = a["value"];
        if (k == "bpm") p.bpm = juce::jlimit (30.0, 300.0, std::round ((double) v * 10.0) / 10.0);
        else if (k == "swing") p.swing = juce::jlimit (0.5f, 0.75f, (float) v);
        else if (k == "metronome") p.metronome = (bool) v;
        else if (k == "countIn") p.countIn = (bool) v;
        else if (k == "recordMode") p.recordMode = 0;   // live only on the phone
        else if (k == "overdub") p.overdub = (bool) v;
        else if (k == "recordQuantize") p.recordQuantize = juce::jlimit (0.0f, 1.0f, (float) v);
        else if (k == "switchQuantize") p.switchQuantize = std::clamp ((int) v, 0, 2);
        else if (k == "perform") p.perform = (bool) v;
        else if (k == "channelPerTrack") p.channelPerTrack = (bool) v;   // MIDI channel n plays track n
        else if (k == "scaleRoot") p.scale.root = ((int) v % 12 + 12) % 12;
        else if (k == "scaleIndex") p.scale.scale = std::clamp ((int) v, 0, (int) Scales::factoryScales().size() - 1);
        else if (k == "volume") p.master.volume = juce::jlimit (0.0f, 1.0f, (float) v);
        else if (k == "delayReturn") p.master.delayReturn = juce::jlimit (0.0f, 1.0f, (float) v);
        else if (k == "reverbReturn") p.master.reverbReturn = juce::jlimit (0.0f, 1.0f, (float) v);
        else if (k == "glue") p.master.glue = juce::jlimit (0.0f, 1.0f, (float) v);
        else if (k == "quality") qualitySetting() = (int) v == 1 ? (int) Quality::Eco : (int) Quality::Full;
        return {};
    }
    bool handled = false;
    auto r = seqCall (op, a, handled);
    if (handled) return r;
    r = soundCall (op, a, handled);
    if (handled) return r;
    return projectCall (op, a, handled);
}
} // namespace mb::android
