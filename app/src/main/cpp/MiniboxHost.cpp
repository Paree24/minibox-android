#include "MiniboxHost.h"
#include "app/presets/PresetBank.h"
#include "devices/SampleSlot.h"
#include <android/log.h>
#include <thread>

#define MBLOG(...) __android_log_print (ANDROID_LOG_INFO, "Minibox", __VA_ARGS__)

namespace mb::android
{
MiniboxHost& MiniboxHost::get()
{
    static MiniboxHost h;
    return h;
}

void MiniboxHost::init (const juce::String& factoryDir, const juce::String& storageDir)
{
    storage = juce::File (storageDir);
    oversampleVoices() = false;   // the VA and Bassline voices at 1x: chords cost far less on a phone
    setFactoryDir (juce::File (factoryDir));
    midiBlock.ensureSize (4096);   // the audio thread fills it without allocating
    presets::rescan();
    engine.prepare (48000.0, kMaxBlock);   // re-prepared at the stream's own rate when it opens
    engine.setMasterInsert (&masterFx);    // master effects chain and the 8 performance pads
    masterFx.sync();

    // the starter rack on pattern A: a drum kit and three synths
    const DeviceType starter[] = { DeviceType::Drums, DeviceType::Mono, DeviceType::Acid, DeviceType::Poly };
    for (int t = 0; t < (int) std::size (starter); ++t)
    {
        auto d = createDevice (starter[t]);
        presets::loadDefault (*d, true);
        engine.setDevice (0, t, std::move (d)).reset();
    }
    engine.focusTrack = 1;
    engine.project().overdub = true;   // the phone records in loops: a take plays back on the next pass
}

bool MiniboxHost::openStream()
{
    oboe::AudioStreamBuilder b;
    b.setDirection (oboe::Direction::Output)
        ->setPerformanceMode (oboe::PerformanceMode::LowLatency)
        ->setSharingMode (oboe::SharingMode::Exclusive)              // MMAP where the device allows it
        ->setFormat (oboe::AudioFormat::Float)
        ->setChannelCount (oboe::ChannelCount::Stereo)
        ->setUsage (oboe::Usage::Media)
        ->setContentType (oboe::ContentType::Music)
        ->setSampleRateConversionQuality (oboe::SampleRateConversionQuality::None)   // the device's own rate
        ->setDataCallback (this)
        ->setBufferCapacityInFrames (latencyMode > 0 ? 8192 : 0)   // 0: the device's own (smallest) for Low
        ->setErrorCallback (this);

    std::shared_ptr<oboe::AudioStream> s;
    if (b.openStream (s) != oboe::Result::OK)
    {
        MBLOG ("openStream failed");
        return false;
    }
    if (std::abs (engine.getSampleRate() - s->getSampleRate()) > 0.5)
    {
        engine.prepare ((double) s->getSampleRate(), kMaxBlock);   // also prepares the master insert
        masterFx.sync();
    }
    s->setPerformanceHintEnabled (true);   // ADPF: clocks ramp before an underrun, not after
    bigCapacity = latencyMode > 0;
    if (latencyMode == 0) s->setBufferSizeInFrames (s->getFramesPerBurst() * lowStartBursts);   // Low: where it settled before
    stream = s;
    lastXruns = 0;
    tuneBuffer();
    MBLOG ("stream: %d Hz, burst %d, buffer %d, %s, mmap %d", s->getSampleRate(), s->getFramesPerBurst(),
           s->getBufferSizeInFrames(), s->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared",
           (int) oboe::OboeExtensions::isMMapUsed (s.get()));
    return stream->requestStart() == oboe::Result::OK;
}

bool MiniboxHost::startAudio()
{
    std::lock_guard<std::mutex> l (streamLock);
    wantRunning = true;
    if (stream != nullptr) return stream->requestStart() == oboe::Result::OK;
    return openStream();
}

void MiniboxHost::stopAudio()
{
    std::lock_guard<std::mutex> l (streamLock);
    wantRunning = false;
    if (stream != nullptr)
    {
        stream->stop();
        stream->close();
        stream.reset();
    }
}

void MiniboxHost::setLatencyMode (int mode, int lowBursts)
{
    std::lock_guard<std::mutex> l (streamLock);
    mode = std::clamp (mode, 0, 3);
    lowStartBursts = std::clamp (lowBursts, 1, 8);
    if (mode == latencyMode && stream != nullptr) return;
    latencyMode = mode;
    lastXruns = stream != nullptr && stream->getXRunCount() ? stream->getXRunCount().value() : 0;
    // the higher modes need a stream with room for a long buffer: reopen it when that changes
    if (stream != nullptr && (latencyMode > 0) != bigCapacity)
    {
        stream->stop(); stream->close(); stream.reset();
        if (wantRunning) openStream();
        return;
    }
    if (stream != nullptr) stream->setBufferSizeInFrames (stream->getFramesPerBurst() * (latencyMode == 0 ? lowStartBursts : 1));
    tuneBuffer();
}

void MiniboxHost::tuneBuffer()
{
    if (stream == nullptr) return;
    const int burst = std::max (1, stream->getFramesPerBurst());
    const auto x = stream->getXRunCount();
    const int xr = x ? x.value() : 0;
    int size = std::max (stream->getBufferSizeInFrames(), burst);
    if (latencyMode == 0)
    {
        // Low: as small as plays cleanly; each new underrun adds a burst (up to eight)
        if (xr > lastXruns) size = std::min (size + burst, burst * 8);
    }
    else
    {
        // the target is the whole output latency: the buffer gets what the device's own path leaves of it
        const double sr = stream->getSampleRate();
        const double bufferMs = stream->getBufferSizeInFrames() * 1000.0 / sr;
        const auto lat = stream->calculateLatencyMillis();
        const double fixedMs = lat ? std::max (0.0, lat.value() - bufferMs) : burst * 1000.0 / sr;
        const double allowedMs = kLatencyTargetMs[latencyMode] - fixedMs;
        size = std::max (burst, (int) std::floor (allowedMs * sr / 1000.0 / burst) * burst);
        if (xr > lastXruns) size = std::min (size + burst, stream->getBufferCapacityInFrames());   // underruns still win
    }
    lastXruns = xr;
    if (size != stream->getBufferSizeInFrames()) stream->setBufferSizeInFrames (size);
}

void MiniboxHost::readFast (float* out)
{
    engine.applyRecorded();
    engine.recordTouched();
    collectRetiredSamples();
    const auto& p = engine.project();
    const int act = engine.activePattern();
    out[0] = engine.isPlaying() ? 1.0f : 0.0f;
    out[1] = engine.isRecording() ? 1.0f : 0.0f;
    out[2] = engine.inCountIn() ? 1.0f : 0.0f;
    out[3] = (float) act;
    out[4] = (float) engine.queuedPattern();
    out[5] = (float) engine.currentStep();
    out[6] = (float) p.patternSteps (act);
    out[7] = engine.patternProgress();
    out[8] = (float) p.bpm;
    out[9] = (float) p.beats;
    out[10] = (float) p.unit;
    out[11] = (float) p.stepsPerBar (act);
    out[12] = std::max (engine.master().peakL.load(), engine.master().peakR.load());
    for (int t = 0; t < kTracks; ++t) out[13 + t] = engine.slotPeak[act][t].load();
    out[21] = (float) engine.focusPattern.load();
    out[22] = (float) engine.stepRecordCursor.load();
    out[23] = 0.0f;
}

MiniboxHost::Stats MiniboxHost::stats()
{
    std::lock_guard<std::mutex> l (streamLock);
    Stats st;
    st.cpu = engine.cpuLoad.load();
    st.lastCallbackFrames = (float) lastFrames.load();
    st.peak = std::max (engine.master().peakL.load(), engine.master().peakR.load());
    if (stream == nullptr) return st;
    tuneBuffer();   // the UI polls this a few times a second
    st.running = stream->getState() == oboe::StreamState::Started ? 1.0f : 0.0f;
    st.sampleRate = (float) stream->getSampleRate();
    st.framesPerBurst = (float) stream->getFramesPerBurst();
    st.bufferFrames = (float) stream->getBufferSizeInFrames();
    if (auto x = stream->getXRunCount()) st.xruns = (float) x.value();
    if (auto lat = stream->calculateLatencyMillis()) st.latencyMs = (float) lat.value();
    st.exclusive = stream->getSharingMode() == oboe::SharingMode::Exclusive ? 1.0f : 0.0f;
    st.mmap = oboe::OboeExtensions::isMMapUsed (stream.get()) ? 1.0f : 0.0f;
    return st;
}

oboe::DataCallbackResult MiniboxHost::onAudioReady (oboe::AudioStream*, void* audioData, int32_t numFrames)
{
    const juce::ScopedNoDenormals noDenormals;   // flush-to-zero on arm64
    auto* out = static_cast<float*> (audioData);
    lastFrames.store (numFrames, std::memory_order_relaxed);
    for (int pos = 0; pos < numFrames; pos += kMaxBlock)
    {
        const int n = std::min (kMaxBlock, numFrames - pos);
        engine.process (left.data(), right.data(), n, pos == 0 ? takeMidi() : nullptr);
        for (int i = 0; i < n; ++i)
        {
            out[(pos + i) * 2] = left[(size_t) i];
            out[(pos + i) * 2 + 1] = right[(size_t) i];
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void MiniboxHost::onErrorAfterClose (oboe::AudioStream*, oboe::Result r)
{
    // the device went away (headphones, USB, Bluetooth): reopen on the new one; the transport keeps its place
    MBLOG ("stream closed (%s), reopening", oboe::convertToText (r));
    std::lock_guard<std::mutex> l (streamLock);
    stream.reset();
    if (wantRunning) openStream();
}
} // namespace mb::android
