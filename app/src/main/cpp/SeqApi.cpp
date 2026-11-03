// Seq: the step editor, drum steps and the piano roll (mockups 04–06, 15–16). Every edit takes a pattern snapshot
// for undo (as the desktop's AppContext does) and changes the model under the engine's edit lock.
#include "MiniboxHost.h"
#include "seq/Scales.h"
#include "seq/Arpeggiate.h"
#include <map>

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
        put (o, "stepsPerBar", p.stepsPerBar (pat)); put (o, "steps", total); put (o, "stepRes", pt.stepRes);
        put (o, "loopOn", pt.loopOn); put (o, "loopStart", pt.loopStart); put (o, "loopEnd", pt.loopEnd);
        put (o, "stepTicks", stepT); put (o, "pianoRoll", tr.pianoRoll); put (o, "arp", tr.arp);
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
            for (auto& n : tr.clip) notes.add (juce::Array<juce::var> { (int) n.id, n.start, n.length, n.note, n.vel, n.flags, n.muted, n.locked });
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
    if (op == "velocityMany")   // several steps' velocities (a drum lane's, or the synth steps' with lane -1), one undo step
    {
        auto* items = a["items"].getArray();
        if (items == nullptr) return {};
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        const int lane = geti (a, "lane", -1);
        for (auto& v : *items)
        {
            const int s = std::clamp ((int) v["step"], 0, total - 1);
            const int vel = std::clamp ((int) v["velocity"], 1, 127);
            if (lane >= 0 && lane < kLanes) { auto& d = tr.lanes[(size_t) lane][(size_t) s]; if (d.on) d.velocity = (uint8_t) vel; }
            else if (tr.steps[(size_t) s].noteCount > 0) tr.steps[(size_t) s].setVelocity (vel);
        }
        return {};
    }
    if (op == "clearTrack")   // everything the track plays in this pattern (undoable)
    {
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        tr.clearAll();
        return {};
    }
    if (op == "setRes")   // the pattern's step resolution: 0 1/8, 1 1/16, 2 1/32 (as the desktop's Seq bar strip)
    {
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        pt.stepRes = std::clamp (geti (a, "res", 1), 0, 2);
        pt.lengthBars = std::min (pt.lengthBars, p.maxBarsFor (pat));
        return {};
    }
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
        if (auto* list = a["notes"].getArray(); list != nullptr && ! list->isEmpty())   // a chord: the step becomes it
        {
            st.clear();
            for (auto& v : *list) st.addNote (std::clamp ((int) v, 0, 127), juce::roundToInt (getd (a, "velocity", 0.8) * 127.0));
            return {};
        }
        const int note = geti (a, "note", -1);
        if (note < 0) st.clear();
        else if (st.hasNote (note)) st.removeNote (note);
        else
        {
            if (! getb (a, "chord")) st.clear();   // a plain tap replaces the step's note
            // a note added to a chord takes the step's velocity and length (addNote copies them)
            const bool own = ! getb (a, "chord") || a.hasProperty ("velocity");
            st.addNote (note, own ? juce::roundToInt (getd (a, "velocity", 0.8) * 127.0) : -1);
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
        else if (k == "chord" && st.noteCount > 0)   // the step becomes a chord on its lowest note (semitones up from it)
        {
            if (auto* iv = v.getArray(); iv != nullptr && ! iv->isEmpty())
            {
                int root = 127;
                for (int i = 0; i < st.noteCount; ++i) root = std::min (root, (int) st.notes[i]);
                const auto vel = st.vel[0]; const auto len = st.len[0]; const auto off = st.off[0];
                const auto flags = st.flags, prob = st.probability, rat = st.ratchet, cond = st.cond;
                const auto micro = st.microTiming;
                st.clear();
                for (auto& x : *iv) st.addNote (std::clamp (root + (int) x, 0, 127), vel, len);
                for (int i = 0; i < st.noteCount; ++i) st.off[i] = off;
                st.flags = flags; st.probability = prob; st.ratchet = rat; st.cond = cond; st.microTiming = micro;
            }
        }
        else if (k == "shift" && st.noteCount > 0)   // the whole step (a chord) up or down by notes of the scale
        {
            const auto& facts = Scales::factoryScales();
            const auto& iv = facts[(size_t) std::clamp (p.scale.scale, 0, (int) facts.size() - 1)].intervals;
            const auto pcs = Scales::pcSet (p.scale.root, iv);
            const int by = (int) v, dir = by > 0 ? 1 : -1;
            for (int i = 0; i < st.noteCount; ++i)
            {
                int m = st.notes[i];
                for (int k2 = 0; k2 < std::abs (by); ++k2) do m += dir; while (m > 0 && m < 127 && ! pcs[(size_t) (m % 12)]);
                st.notes[i] = (uint8_t) std::clamp (m, 0, 127);
            }
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

    if (op == "clipOp")   // the piano roll's functions (the desktop's Edit, Notes, Chords and Velocity menus)
    {
        if (dev != nullptr && dev->laneCount() > 0) return {};
        return clipOp (a["fn"].toString(), a, pat, t);
    }
    if (op == "loop")   // the pattern's loop: on / off, or a range of steps
    {
        const Engine::ScopedEdit edit (engine);
        if (a.hasProperty ("on")) pt.loopOn = (bool) a["on"];
        if (a.hasProperty ("start")) { pt.loopStart = std::clamp (geti (a, "start"), 0, total - 1); pt.loopEnd = std::clamp (geti (a, "end"), pt.loopStart + 1, total); }
        return {};
    }
    if (op == "lastNotes")   // what the track's last key played (its chord in Chords mode)
    {
        juce::Array<juce::var> out;
        const int c = engine.lastKeyCount[(size_t) t].load (std::memory_order_acquire);
        for (int i = 0; i < c; ++i) out.add (engine.lastKeyNotes[(size_t) t][(size_t) i].load (std::memory_order_relaxed));
        return out;
    }
    if (op == "toSteps")   // a clip back to steps (the Steps view): {ok} or {ok: false, why} and nothing changes
    {
        auto* o = new juce::DynamicObject();
        juce::var res (o);
        if (! tr.pianoRoll) { o->setProperty ("ok", true); return res; }
        auto copy = tr;
        const auto why = copy.convertToSteps (stepT, total);
        o->setProperty ("ok", why.isEmpty());
        if (why.isNotEmpty()) { o->setProperty ("why", why); return res; }
        snapshot (pat);
        const Engine::ScopedEdit edit (engine);
        tr.convertToSteps (stepT, total);
        return res;
    }
    if (op == "setArp")   // the track's notes play through its arpeggiator (its Arp settings, live); the notes stay
    {
        const Engine::ScopedEdit edit (engine);
        tr.arp = getb (a, "on");
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
            if (auto* n = tr.clipNote (id); n != nullptr && n->locked) continue;   // locked: kept from edits
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
        if (auto* n = tr.clipNote (id); n != nullptr && n->locked) return {};
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

namespace mb::android
{
/** The piano roll's functions, as the desktop's (ui/PianoRoll.cpp): on the selected notes (`ids`; none: every
    note), one undo step each. Locked notes are left alone. Returns the notes to select afterwards. */
juce::var MiniboxHost::clipOp (const juce::String& fn, const juce::var& a, int pat, int track)
{
    auto& p = engine.project();
    auto& pt = p.patterns[(size_t) pat];
    auto& tr = pt.slots[(size_t) track].track;
    const int stepT = p.stepTicks (pat), total = p.patternSteps (pat), totalT = p.patternTicks (pat);
    const int g = std::max (1, a.hasProperty ("grid") ? (int) a["grid"] : stepT);
    const int tol = std::max (1, stepT / 16);
    std::vector<uint32_t> ids;
    if (auto* l = a["ids"].getArray()) for (auto& v : *l) ids.push_back ((uint32_t) (int) v);
    auto chosen = [&] (const ClipNote& n) { return ids.empty() || std::find (ids.begin(), ids.end(), n.id) != ids.end(); };
    auto result = [] (const std::vector<uint32_t>& v) { juce::Array<juce::var> r; for (auto id : v) r.add ((int) id); return juce::var (r); };

    if (fn == "copy")   // nothing changes: the selection goes to the clipboard, from 0
    {
        clipboard.clear();
        for (auto& n : tr.clip) if (! ids.empty() && chosen (n)) clipboard.push_back (n);
        int lo = INT_MAX;
        for (auto& c : clipboard) lo = std::min (lo, c.start);
        for (auto& c : clipboard) c.start -= lo;
        return result (ids);
    }
    if (fn == "loopSelection")
    {
        int lo = INT_MAX, hi = 0;
        for (auto& n : tr.clip) if (! ids.empty() && chosen (n)) { lo = std::min (lo, n.start); hi = std::max (hi, n.end()); }
        if (lo > hi) return result (ids);
        const Engine::ScopedEdit edit (engine);
        pt.loopStart = std::clamp (lo / stepT, 0, total - 1);
        pt.loopEnd = std::min (total, std::max (pt.loopStart + 1, (hi + stepT - 1) / stepT));
        pt.loopOn = true;
        return result (ids);
    }

    snapshot (pat);
    const Engine::ScopedEdit edit (engine);
    if (! tr.pianoRoll) tr.convertToPianoRoll (stepT, total);
    // the notes to change come out of the clip, sorted by time; put() writes them back and selects them
    auto take = [&]
    {
        std::vector<ClipNote> out;
        for (auto& n : tr.clip) if (chosen (n) && ! n.locked) out.push_back (n);
        tr.clip.erase (std::remove_if (tr.clip.begin(), tr.clip.end(), [&] (const ClipNote& n) { return chosen (n) && ! n.locked; }), tr.clip.end());
        std::sort (out.begin(), out.end(), [] (const ClipNote& x, const ClipNote& y) { return x.start != y.start ? x.start < y.start : x.note < y.note; });
        return out;
    };
    auto put = [&] (const std::vector<ClipNote>& notes)
    {
        std::vector<uint32_t> sel;
        for (auto n : notes)
        {
            if (n.start < 0 || n.start >= totalT || n.note > 127) continue;
            n.length = std::max (1, n.length);
            sel.push_back (tr.addClipNote (n));
        }
        return result (sel);
    };
    auto chords = [&] (const std::vector<ClipNote>& n)
    {
        std::vector<std::vector<size_t>> groups;
        for (size_t i = 0; i < n.size(); ++i)
        {
            bool placed = false;
            for (auto& gr : groups) if (std::abs (n[gr.front()].start - n[i].start) <= tol) { gr.push_back (i); placed = true; break; }
            if (! placed) groups.push_back ({ i });
        }
        for (auto& gr : groups) std::sort (gr.begin(), gr.end(), [&] (size_t x, size_t y) { return n[x].note < n[y].note; });
        return groups;
    };
    const auto& facts = Scales::factoryScales();
    const auto& iv = facts[(size_t) std::clamp (p.scale.scale, 0, (int) facts.size() - 1)].intervals;
    const int dir = (int) a.getProperty ("dir", 1);

    if (fn == "cut")
    {
        clipboard = take();
        int lo = INT_MAX;
        for (auto& c : clipboard) lo = std::min (lo, c.start);
        for (auto& c : clipboard) c.start -= lo;
        return result ({});
    }
    if (fn == "paste")   // after the selection (its end, up to the grid), or at `at`
    {
        int at = (int) a.getProperty ("at", 0);
        if (! ids.empty())
        {
            int end = 0;
            for (auto& n : tr.clip) if (chosen (n)) end = std::max (end, n.end());
            at = (end + g - 1) / g * g;
        }
        std::vector<ClipNote> notes;
        for (auto c : clipboard) { c.start += at; notes.push_back (c); }
        return put (notes);
    }
    if (fn == "duplicate")   // a copy right after the selection's span (rounded up to the grid)
    {
        int lo = INT_MAX, hi = 0;
        std::vector<ClipNote> notes;
        for (auto& n : tr.clip) if (! ids.empty() && chosen (n)) { lo = std::min (lo, n.start); hi = std::max (hi, n.end()); notes.push_back (n); }
        const int span = std::max (g, (hi - lo + g - 1) / g * g);
        for (auto& n : notes) n.start += span;
        return put (notes);
    }
    auto n = take();
    if (fn == "transpose")   // semitones, octaves (dir ±12), or in key (scale: the next note of the scale)
    {
        const bool inKey = (bool) a.getProperty ("scale", false) && std::abs (dir) == 1;
        const auto pcs = Scales::pcSet (p.scale.root, iv);
        for (auto& x : n)
        {
            int m = x.note;
            if (inKey) do m += dir; while (m > 0 && m < 127 && ! pcs[(size_t) (m % 12)]);
            else m += dir;
            x.note = (uint8_t) std::clamp (m, 0, 127);
        }
    }
    else if (fn == "length") for (auto& x : n) x.length = std::max (std::max (1, g / 4), x.length + dir * g);
    else if (fn == "velocity") for (auto& x : n) x.vel = (uint8_t) std::clamp (x.vel + dir * 8, 1, 127);
    else if (fn == "velocitySet") for (auto& x : n) x.vel = (uint8_t) std::clamp ((int) a.getProperty ("value", 100), 1, 127);
    else if (fn == "velocityRandom")   // amount: each velocity moves up to that fraction up or down (0.1: ±10 %)
    {
        const double amt = std::clamp ((double) a.getProperty ("amount", 0.16), 0.0, 1.0);
        for (auto& x : n) x.vel = (uint8_t) std::clamp ((int) std::lround (x.vel * (1.0 + amt * (2.0 * rnd.nextDouble() - 1.0))), 1, 127);
    }
    else if (fn == "velocityRamp" && ! n.empty())
    {
        const int lo = n.front().start, hi = std::max (lo + 1, n.back().start);
        for (auto& x : n) { const float k = (x.start - lo) / (float) (hi - lo); x.vel = (uint8_t) juce::roundToInt (40 + 87 * (dir > 0 ? k : 1.0f - k)); }
    }
    else if (fn == "mute") { bool anyOn = false; for (auto& x : n) anyOn = anyOn || ! x.muted; for (auto& x : n) x.muted = anyOn; }
    else if (fn == "lock")
    {
        // locking takes every chosen note (locked ones too); the notes stay where they are
        for (auto& c : tr.clip) if (chosen (c)) n.push_back (c);
        tr.clip.erase (std::remove_if (tr.clip.begin(), tr.clip.end(), [&] (const ClipNote& c) { return chosen (c); }), tr.clip.end());
        bool anyOpen = false;
        for (auto& x : n) anyOpen = anyOpen || ! x.locked;
        for (auto& x : n) x.locked = anyOpen;
    }
    else if (fn == "quantize")
    {
        const float amount = (float) (double) a.getProperty ("amount", 1.0);
        for (auto& x : n)
        {
            const int target = (int) std::lround (x.start / (double) g) * g;
            x.start = (int) std::lround (x.start + (target - x.start) * amount);
            if ((bool) a.getProperty ("lengths", false)) x.length = std::max (g, (int) std::lround ((x.start + x.length) / (double) g) * g - x.start);
        }
    }
    else if (fn == "humanize") for (auto& x : n) { x.start = std::max (0, x.start + rnd.nextInt (7) - 3); x.vel = (uint8_t) std::clamp (x.vel + rnd.nextInt (21) - 10, 1, 127); }
    else if (fn == "legato")
        for (auto& x : n)
        {
            int next = -1;
            for (auto& y : n) if (y.start > x.start && (next < 0 || y.start < next)) next = y.start;
            if (next > x.start) x.length = next - x.start;
        }
    else if (fn == "stretch" && ! n.empty())   // factor 2: twice as long, 0.5: half
    {
        const double f = (double) a.getProperty ("factor", 2.0);
        const int first = n.front().start;
        for (auto& x : n) { x.start = first + (int) std::lround ((x.start - first) * f); x.length = std::max (1, (int) std::lround (x.length * f)); }
    }
    else if (fn == "reverse" && ! n.empty())
    {
        int lo = INT_MAX, hi = 0;
        for (auto& x : n) { lo = std::min (lo, x.start); hi = std::max (hi, x.end()); }
        for (auto& x : n) x.start = lo + hi - x.end();
    }
    else if (fn == "invertPitch")
    {
        int lo = 127, hi = 0;
        for (auto& x : n) { lo = std::min (lo, (int) x.note); hi = std::max (hi, (int) x.note); }
        for (auto& x : n) x.note = (uint8_t) (lo + hi - x.note);
    }
    else if (fn == "fitToScale") for (auto& x : n) x.note = (uint8_t) Scales::snapNote (x.note, p.scale.root, iv, 0);
    else if (fn == "chop")   // longer notes become a run of grid-length notes
    {
        std::vector<ClipNote> out;
        for (auto& x : n)
        {
            if (x.length <= g) { out.push_back (x); continue; }
            for (int k = 0; k * g < x.length; ++k) { auto piece = x; piece.start = x.start + k * g; piece.length = std::min (g, x.length - k * g); out.push_back (piece); }
        }
        n = out;
    }
    else if (fn == "glue")   // a note that ends where the next of the same pitch starts swallows it
    {
        std::vector<ClipNote> out;
        for (auto& x : n)
        {
            bool merged = false;
            for (auto& o : out) if (o.note == x.note && std::abs (o.end() - x.start) <= 1) { o.length = x.end() - o.start; merged = true; break; }
            if (! merged) out.push_back (x);
        }
        n = out;
    }
    else if (fn == "strum")
    {
        std::map<int, int> rank;
        for (auto& x : n) { const int k = rank[x.start]++; x.start += k * 3; x.vel = (uint8_t) std::max (1, x.vel - k * 6); }
    }
    else if (fn == "arpeggiate")   // each chord in turn with the track's Arp settings (as the live arpeggiator)
    {
        std::vector<ClipNote> in, keep;
        for (auto& x : n) (x.muted ? keep : in).push_back (x);
        for (auto& x : arp::arpeggiate (in, pt.slots[(size_t) track].play, kPPQ, stepT / 4)) keep.push_back (x);
        n = keep;
    }
    else if (fn == "chordInvert")   // up: each chord's lowest note an octave up; down: its highest an octave down
    {
        for (auto& gr : chords (n))
            if (gr.size() >= 2) { auto& x = n[dir > 0 ? gr.front() : gr.back()]; x.note = (uint8_t) std::clamp (x.note + (dir > 0 ? 12 : -12), 0, 127); }
    }
    else if (fn == "chordSpread")
    {
        for (auto& gr : chords (n)) if (gr.size() >= 3) { auto& x = n[gr[gr.size() - 2]]; x.note = (uint8_t) std::max (0, x.note - 12); }
    }
    else if (fn == "chordClose")
    {
        for (auto& gr : chords (n))
        {
            const int root = n[gr.front()].note;
            for (size_t k = 1; k < gr.size(); ++k) { int m = n[gr[k]].note; while (m - root >= 12) m -= 12; while (m < root) m += 12; n[gr[k]].note = (uint8_t) m; }
        }
    }
    else if (fn == "chordAddBass")
    {
        std::vector<ClipNote> extra;
        for (auto& gr : chords (n)) { auto b = n[gr.front()]; if (b.note >= 12) { b.note = (uint8_t) (b.note - 12); extra.push_back (b); } }
        n.insert (n.end(), extra.begin(), extra.end());
    }
    else if (fn == "rotate")   // every note a step later / earlier, wrapping round the pattern
    {
        for (auto& x : n) x.start = ((x.start + dir * stepT) % totalT + totalT) % totalT;
    }
    else if (fn == "insertStep")   // at the first chosen note's step: later notes a step right (dir 1) or left (-1, its own notes go)
    {
        int at = INT_MAX;
        for (auto& x : n) at = std::min (at, x.start / stepT);
        if (at == INT_MAX) at = 0;
        const int a0 = at * stepT;
        for (auto& c : tr.clip) n.push_back (c);   // every note moves, not only the chosen
        tr.clip.clear();
        if (dir < 0) n.erase (std::remove_if (n.begin(), n.end(), [&] (const ClipNote& x) { return x.start >= a0 && x.start < a0 + stepT; }), n.end());
        for (auto& x : n) if (x.start >= a0) x.start += dir > 0 ? stepT : -stepT;
        put (n);
        return result ({});
    }
    else if (fn == "delete") return result ({});
    return put (n);
}
} // namespace mb::android
