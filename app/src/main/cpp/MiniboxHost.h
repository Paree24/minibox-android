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

    /** Message thread, once: factory content folder, the starter rack (pattern A). */
    void init (const juce::String& factoryDir);
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

    /** 1–4 bursts of buffering (PLAN.md §3.1); 0 = automatic (2 bursts, growing on underruns). */
    void setBufferBursts (int bursts);

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
    MiniboxHost() = default;
    bool openStream();
    void tuneBuffer();

    Engine engine { Engine::Mode::Groovebox };
    MasterFx masterFx { engine };
    std::mutex streamLock;                         // open / close / reconfigure (never the audio thread)
    std::shared_ptr<oboe::AudioStream> stream;
    std::atomic<bool> wantRunning { false };
    int bufferBursts = 0;
    int32_t lastXruns = 0;

    static constexpr int kMaxBlock = 1024;          // the engine renders longer callbacks in pieces
    std::vector<float> left = std::vector<float> (kMaxBlock), right = std::vector<float> (kMaxBlock);
    std::atomic<int> lastFrames { 0 };
};
} // namespace mb::android
