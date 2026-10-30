#pragma once
#include "engine/Engine.h"
#include "efx/MasterFx.h"
#include <oboe/Oboe.h>
#include <oboe/OboeExtensions.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

namespace mb::android
{
/** The Android side of the engine: owns the Engine, runs it from an Oboe (AAudio) stream in exclusive low-latency
    mode, and is what the JNI bridge talks to. Notes from the pads go through the engine's lock-free UI FIFO and
    play at the start of the next audio block (PLAN.md §3.3). */
class MiniboxHost final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback
{
public:
    static MiniboxHost& get();

    /** Message thread, once: factory content folder, app storage (projects), the starter rack (pattern A). */
    void init (const juce::String& factoryDir, const juce::String& storageDir);

    /** MIDI controllers (MidiIn.cpp): open an android.media.midi.MidiDevice's output port; close them all. */
    bool openMidi (void* jniEnv, void* midiDevice, int port);
    void closeMidi();
    bool startAudio();
    void stopAudio();

    // pads (main thread only: the engine's UI FIFO has a single producer)
    void noteOn (int track, int note, float velocity) { engine.injectNote (track, note, velocity, true); }
    void noteOff (int track, int note) { engine.injectNote (track, note, 0.0f, false); }
    void selectTrack (int track) { engine.focusTrack = track; }
    void roll (int track, int note, float velocity, double beats, bool on) { engine.setRoll (track, note, velocity, beats, on); }
    void fxHold (int pad, bool on) { masterFx.setHeld (pad, on); }

    /** UI commands and queries, JSON in and out (Api.cpp). Main thread. */
    juce::var call (const juce::String& op, const juce::var& args);

    /** The playhead, patterns and meters for the UI's 60 Hz poll; also runs the main-thread housekeeping the
        engine needs (recorded notes into clips, motion recording, freeing replaced samples). */
    static constexpr int kFastSize = 24;
    void readFast (float* out);

    // transport
    void play() { engine.play(); }
    void stop() { engine.stop(); }

    /** Latency (Settings): 0 Low, the smallest buffer the phone sustains (one burst, a burst more on each underrun);
        1 Medium, 2 High, 3 Very high: the largest buffer whose output latency stays within 30, 50 or 80 ms. */
    void setLatencyMode (int mode, int lowBursts = 1);   // lowBursts: where Low settled last time on this phone
    static constexpr float kLatencyTargetMs[4] = { 0.0f, 30.0f, 50.0f, 80.0f };

    struct Stats
    {
        float sampleRate = 0, framesPerBurst = 0, bufferFrames = 0, latencyMs = -1, xruns = 0;
        float mmap = 0, exclusive = 0, cpu = 0, lastCallbackFrames = 0, running = 0, peak = 0;
    };
    Stats stats();

    Engine& getEngine() { return engine; }

    // oboe
    oboe::DataCallbackResult onAudioReady (oboe::AudioStream*, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose (oboe::AudioStream*, oboe::Result) override;

private:
    MiniboxHost();
    ~MiniboxHost() override;   // MidiIn.cpp, where MidiPort is complete
    bool openStream();
    void tuneBuffer();

    juce::var seqCall (const juce::String& op, const juce::var& args, bool& handled);     // SeqApi.cpp
    juce::var projectCall (const juce::String& op, const juce::var& args, bool& handled); // ProjectApi.cpp
    void afterProjectChange();
    juce::File projectsDir() const;
    juce::File storage;

    struct MidiPort;
    std::mutex midiPortsLock;
    std::vector<std::unique_ptr<MidiPort>> midiPorts;
    struct MidiMsg { uint8_t status = 0, d1 = 0, d2 = 0, size = 0; };
    static constexpr int kMidiQueue = 1024;
    juce::AbstractFifo midiFifo { kMidiQueue };    // one producer at a time in practice (a controller); see pushMidiBytes
    std::array<MidiMsg, kMidiQueue> midiQueue {};
    uint8_t midiStatus = 0, midiData[2] {};
    int midiHave = 0;
    juce::MidiBuffer midiBlock;
    void pushMidiBytes (const uint8_t*, int);
    const juce::MidiBuffer* takeMidi();
    juce::var soundCall (const juce::String& op, const juce::var& args, bool& handled);   // SoundApi.cpp

    // undo / redo of sequence edits: a snapshot of the pattern's tracks before each edit (as the desktop)
    struct PatternSnap { int pattern = 0; std::array<Track, kTracks> tracks; int lengthBars = 1, stepRes = 1; };
    std::vector<PatternSnap> undoStack, redoStack;
    PatternSnap capture (int pattern);
    void restore (const PatternSnap&);
    void snapshot (int pattern);
    bool swapUndo (std::vector<PatternSnap>& from, std::vector<PatternSnap>& to);

    Engine engine { Engine::Mode::Groovebox };
    MasterFx masterFx { engine };
    std::mutex streamLock;                         // open / close / reconfigure (never the audio thread)
    std::shared_ptr<oboe::AudioStream> stream;
    std::atomic<bool> wantRunning { false };
    int latencyMode = 0, lowStartBursts = 1;
    bool bigCapacity = false;   // the open stream was asked for room for the higher modes
    int32_t lastXruns = 0;

    static constexpr int kMaxBlock = 1024;          // the engine renders longer callbacks in pieces
    std::vector<float> left = std::vector<float> (kMaxBlock), right = std::vector<float> (kMaxBlock);
    std::atomic<int> lastFrames { 0 };
};
} // namespace mb::android
