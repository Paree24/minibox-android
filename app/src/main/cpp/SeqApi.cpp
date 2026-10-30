// Seq: the step editor, drum steps and the piano roll (mockups 04–06, 15–16). Every edit takes a pattern snapshot
// for undo (as the desktop's AppContext does) and changes the model under the engine's edit lock.
#include "MiniboxHost.h"

namespace mb::android
{
namespace
{
juce::var obj() { return juce::var (new juce::DynamicObject()); }
void put (juce::var& o, const juce::Identifier& k, const juce::var& v) { o.getDynamicObject()->setProperty (k, v); }
int geti (const juce::var& a, const char* k, int def = 0) { return a.hasProperty (k) ? (int) a[k] : def; }
double getd (const juce::var& a, const char* k, double def = 0) { return a.hasProperty (k) ? (double) a[k] : def; }
bool getb (const juce::var& a, const char* k) { return a.hasProperty (k) && (bool) a[k]; }
} // namespace

MiniboxHost::PatternSnap MiniboxHost::capture (int pattern)
{
    const auto& pt = engine.project().patterns[(size_t) pattern];
    PatternSnap s;
    s.pattern = pattern; s.lengthBars = pt.lengthBars; s.stepRes = pt.stepRes;
    for (int t = 0; t < kTracks; ++t) s.tracks[(size_t) t] = pt.slots[(size_t) t].track;
    return s;
}

void MiniboxHost::restore (const PatternSnap& s)
{
    const Engine::ScopedEdit edit (engine);
    auto& pt = engine.project().patterns[(size_t) s.pattern];
    pt.lengthBars = s.lengthBars; pt.stepRes = s.stepRes;
    for (int t = 0; t < kTracks; ++t) pt.slots[(size_t) t].track = s.tracks[(size_t) t];
}

void MiniboxHost::snapshot (int pattern)
{
    undoStack.push_back (capture (pattern));
    if (undoStack.size() > 64) undoStack.erase (undoStack.begin());
    redoStack.clear();
}

bool MiniboxHost::swapUndo (std::vector<PatternSnap>& from, std::vector<PatternSnap>& to)
{
    if (from.empty()) return false;
    to.push_back (capture (from.back().pattern));
    restore (from.back());
    from.pop_back();
    return true;
}

juce::var MiniboxHost::seqCall (const juce::String& op, const juce::var& a, bool& handled)
{
    handled = true;
    auto& p = engine.project();
    const int pat = engine.focusPattern.load();
    auto& pt = p.patterns[(size_t) pat];
    const int t = std::clamp (geti (a, "track", engine.focusTrack.load()), 0, kTracks - 1);
    auto& tr = pt.slots[(size_t) t].track;
    auto* dev = engine.device (pat, t);
    const int total = p.patternSteps (pat);
    const int stepT = p.stepTicks (pat);

    if (op == "seq")
    {
        auto o = obj();
        put (o, "bars", pt.lengthBars); put (o, "maxBars", p.maxBarsFor (pat));
        put (o, "stepsPerBar", p.stepsPerBar (pat)); put (o, "steps", total);
        put (o, "stepTicks", stepT); put (o, "pianoRoll", tr.pianoRoll);
        put (o, "canUndo", ! undoStack.empty()); put (o, "canRedo", ! redoStack.empty());
        const bool drum = dev != nullptr && dev->laneCount() > 0;
        put (o, "drum", drum);
        if (drum)
        {
            juce::Array<juce::var> lanes;
            for (int l = 0; l < kLanes; ++l)
            {
                juce::Array<juce::var> cells;   // [step, velocity, probability, ratchet, micro] of the steps that play
                for (int s = 0; s < total; ++s)
                {
                    const auto& d = tr.lanes[(size_t) l][(size_t) s];
                    if (d.on) cells.add (juce::Array<juce::var> { s, d.velocity, d.probability, d.ratchet, d.microTiming });
                }
                lanes.add (cells);
            }
            put (o, "lanes", lanes);
        }
        else if (tr.pianoRoll)
        {
            juce::Array<juce::var> notes;   // [id, start, length, note, velocity, flags]
            for (auto& n : tr.clip) notes.add (juce::Array<juce::var> { (int) n.id, n.start, n.length, n.note, n.vel, n.flags });
            put (o, "clip", notes);
        }
        else
        {
            juce::Array<juce::var> steps;   // [step, notes[], velocity, length (1/128 step), probability, ratchet, flags, micro, locked]
            for (int s = 0; s < total; ++s)
            {
                const auto& st = tr.steps[(size_t) s];
                if (st.noteCount == 0) continue;
                juce::Array<juce::var> ns;
                for (int i = 0; i < st.noteCount; ++i) ns.add (st.notes[i]);
                bool locked = false;
                for (auto& lk : tr.locks) locked = locked || ! std::isnan (lk.values[(size_t) s]);
                steps.add (juce::Array<juce::var> { s, ns, st.velocity(), st.maxLen(), st.probability, st.ratchet, st.flags, st.microTiming, locked });
            }
            put (o, "stepList", steps);
        }
        return o;
    }
    if (op == "stepLocks")   // the parameter locks on one step: the macros' values there
    {
        juce::Array<juce::var> list;
        const int s = std::clamp (geti (a, "step"), 0, kMaxSteps - 1);
        if (dev != nullptr)
            for (int m = 0; m < 8; ++m)
            {
                const auto& mm = dev->macros()[(size_t) m];
                if (mm.count == 0) continue;
                const int param = mm.targets[0].param;
                auto o = obj();
                put (o, "macro", m); put (o, "name", mm.name);
                const auto* lk = tr.lockFor (param, false);
                const bool has = lk != nullptr && ! std::isnan (lk->values[(size_t) s]);
                const float real = has ? lk->values[(size_t) s] : dev->get (param);
                const float n = dev->params()[param].toNorm (real);
                const auto& tg = mm.targets[0];
                put (o, "value", std::abs (tg.hi - tg.lo) > 1.0e-6f ? juce::jlimit (0.0f, 1.0f, (n - tg.lo) / (tg.hi - tg.lo)) : 0.0f);
                put (o, "text", dev->params()[param].format (real));
                put (o, "locked", has);
                list.add (o);
            }
        return list;
    }

    // ---- edits
    if (op == "undo") { swapUndo (undoStack, redoStack); return {}; }
    if (op == "redo") { swapUndo (redoStack, undoStack); return {}; }
    if (op == "setLength")
    {
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        p.setLengthBars (pat, geti (a, "bars", 1));
        return {};
    }
    if (op == "stepToggle")   // a synth step: add the note, or remove it (note −1: clear the step)
    {
        const int s = geti (a, "step");
        if (s < 0 || s >= total || tr.pianoRoll) return {};
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        auto& st = tr.steps[(size_t) s];
        const int note = geti (a, "note", -1);
        if (note < 0) st.clear();
        else if (st.hasNote (note)) st.removeNote (note);
        else
        {
            if (! getb (a, "chord")) st.clear();   // a plain tap replaces the step's note
            st.addNote (note, juce::roundToInt (getd (a, "velocity", 0.8) * 127.0));
        }
        return {};
    }
    if (op == "stepSet")
    {
        const int s = geti (a, "step");
        if (s < 0 || s >= total) return {};
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        auto& st = tr.steps[(size_t) s];
        const auto k = a["key"].toString();
        const auto v = a["value"];
        if (k == "velocity") st.setVelocity (juce::roundToInt ((double) v * 127.0));
        else if (k == "gate") st.setLength (juce::jlimit (1, kMaxNoteLen, juce::roundToInt ((double) v * 128.0)));
        else if (k == "probability") st.probability = (uint8_t) std::clamp ((int) v, 0, 100);
        else if (k == "ratchet") st.ratchet = (uint8_t) std::clamp ((int) v, 1, 4);
        else if (k == "micro") st.microTiming = (int8_t) std::clamp ((int) v, -24, 24);
        else if (k == "accent") st.flags = (uint8_t) ((bool) v ? (st.flags | kAccentFlag) : (st.flags & ~kAccentFlag));
        else if (k == "slide") st.flags = (uint8_t) ((bool) v ? (st.flags | kSlideFlag) : (st.flags & ~kSlideFlag));
        else if (k == "note" && st.noteCount > 0)
        {
            const int n = std::clamp ((int) v, 0, 127);
            if (! st.hasNote (n)) st.notes[0] = (uint8_t) n;
        }
        return {};
    }
    if (op == "laneToggle" || op == "laneSet")
    {
        const int l = std::clamp (geti (a, "lane"), 0, kLanes - 1), s = geti (a, "step");
        if (s < 0 || s >= total) return {};
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        auto& d = tr.lanes[(size_t) l][(size_t) s];
        if (op == "laneToggle")
        {
            const bool on = a.hasProperty ("on") ? (bool) a["on"] : ! d.on;
            if (on && ! d.on) { d.clear(); d.on = true; d.velocity = (uint8_t) juce::roundToInt (getd (a, "velocity", 100.0 / 127.0) * 127.0); }
            else if (! on) d.clear();
            return {};
        }
        const auto k = a["key"].toString();
        const auto v = a["value"];
        if (k == "velocity") d.velocity = (uint8_t) juce::jlimit (1, 127, juce::roundToInt ((double) v * 127.0));
        else if (k == "probability") d.probability = (uint8_t) std::clamp ((int) v, 0, 100);
        else if (k == "ratchet") d.ratchet = (uint8_t) std::clamp ((int) v, 1, 4);
        else if (k == "micro") d.microTiming = (int8_t) std::clamp ((int) v, -24, 24);
        return {};
    }
    if (op == "stepLock")   // a macro's value on one step (value < 0 clears it)
    {
        const int s = geti (a, "step"), m = std::clamp (geti (a, "macro"), 0, 7);
        if (dev == nullptr || s < 0 || s >= total) return {};
        const auto& mm = dev->macros()[(size_t) m];
        const float v = (float) getd (a, "value", -1.0);
        const Engine::ScopedEdit edit (engine);
        for (int i = 0; i < mm.count; ++i)
        {
            const auto& tg = mm.targets[(size_t) i];
            if (auto* lk = tr.lockFor (tg.param, v >= 0.0f))
                lk->values[(size_t) s] = v < 0.0f ? std::numeric_limits<float>::quiet_NaN()
                                                  : dev->params()[tg.param].fromNorm (tg.lo + (tg.hi - tg.lo) * juce::jlimit (0.0f, 1.0f, v));
        }
        return {};
    }

    if (op == "toRoll")   // steps → clip, before the piano roll edits a step track's notes
    {
        if (tr.pianoRoll || (dev != nullptr && dev->laneCount() > 0)) return {};
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        tr.convertToPianoRoll (stepT, total);
        return {};
    }
    // several clip notes at once (the piano roll's selection): one undo step
    if (op == "clipEditMany" || op == "clipDeleteMany")
    {
        if (! tr.pianoRoll) return {};
        auto* list = a["notes"].getArray();
        if (list == nullptr) return {};
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        for (auto& v : *list)
        {
            const auto id = (uint32_t) (int) v["id"];
            if (op == "clipDeleteMany") { tr.removeClipNote (id); continue; }
            if (auto* n = tr.clipNote (id))
            {
                if (v.hasProperty ("start")) n->start = std::clamp ((int) v["start"], 0, p.patternTicks (pat) - 1);
                if (v.hasProperty ("length")) n->length = std::max (1, (int) v["length"]);
                if (v.hasProperty ("note")) n->note = (uint8_t) std::clamp ((int) v["note"], 0, 127);
                if (v.hasProperty ("velocity")) n->vel = (uint8_t) std::clamp ((int) v["velocity"], 1, 127);
                if (v.hasProperty ("flags")) n->flags = (uint8_t) ((int) v["flags"] & (kAccentFlag | kSlideFlag | kTieFlag));
            }
        }
        return {};
    }

    // ---- the piano roll: the first edit of a step track turns it into a clip (one way, as on the desktop)
    if (op == "clipAdd" || op == "clipEdit" || op == "clipDelete")
    {
        if (dev != nullptr && dev->laneCount() > 0) return {};
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        if (! tr.pianoRoll) tr.convertToPianoRoll (stepT, total);
        if (op == "clipAdd")
        {
            ClipNote n;
            n.start = std::clamp (geti (a, "start"), 0, p.patternTicks (pat) - 1);
            n.length = std::max (1, geti (a, "length", stepT));
            n.note = (uint8_t) std::clamp (geti (a, "note", 60), 0, 127);
            n.vel = (uint8_t) std::clamp (geti (a, "velocity", 100), 1, 127);
            return (int) tr.addClipNote (n);
        }
        const auto id = (uint32_t) geti (a, "id");
        if (op == "clipDelete") { tr.removeClipNote (id); return {}; }
        if (auto* n = tr.clipNote (id))
        {
            if (a.hasProperty ("start")) n->start = std::clamp (geti (a, "start"), 0, p.patternTicks (pat) - 1);
            if (a.hasProperty ("length")) n->length = std::max (1, geti (a, "length"));
            if (a.hasProperty ("note")) n->note = (uint8_t) std::clamp (geti (a, "note"), 0, 127);
            if (a.hasProperty ("velocity")) n->vel = (uint8_t) std::clamp (geti (a, "velocity"), 1, 127);
            if (a.hasProperty ("flags")) n->flags = (uint8_t) (geti (a, "flags") & (kAccentFlag | kSlideFlag | kTieFlag));   // accent / slide
        }
        return {};
    }
    handled = false;
    return {};
}
} // namespace mb::android
