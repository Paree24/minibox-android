# Minibox for Android: port plan

The goal is to port the Pastel Minibox standalone groovebox (8 tracks, patterns A–H, 7 devices, sequencer, master FX and
perform pads) to Android phones. The app is portrait only and low latency. 16 square pads (4×4) replace the keyboard
and sit at the bottom of the screen. The Seq tab has dedicated step editor and piano roll panels.

The source is `../pastel-minibox` (JUCE 8, C++20). Mockups are in `mockups/` (see `mockups/README.md`).

---

## Status (2026-10-30)

| Phase | State |
|---|---|
| 0 Core prep | Done in pastel-minibox: priority-inheriting audio lock (instead of a full lock-free rewrite), main-thread poster, factory folder override, headless effect registry and editor specs, note repeat (`Engine::setRoll`) |
| 1 Skeleton | Done |
| 2 Latency | Done: the user measured good latency on a phone. Latency modes: Low (minimum, self-tuning, remembered), Medium ≤ 30 ms, High ≤ 50 ms, Very high ≤ 80 ms. The in-app tap-to-sound test (§6) is not built |
| 3 Engine on device | Open: MiniboxTests not yet run on a phone (it links the VST3 host and GUI modules; needs a headless subset) |
| 4 Play + Perform | Done |
| 5 Seq + Sound | Done. Visuals ported: filter, HPF, envelopes, pitch envelope, wave, LFO, ring, delay, drive, reverb; the others (algorithm, operators, sample, slices, accent, slide, chorus, voices, arp, bits) show their values as bars |
| 6 Mix, files, MIDI | Done, except MIDI learn and `.mbmidi` maps, clip controller lanes, sample waveform / zone editing |
| 7 Platform | Done (background playback, focus, Bluetooth, thermal badge, tablets); accessibility labels to do |
| 8 Low-end | Open: quality tiers (voice caps, oversampling) need engine work in pastel-minibox |
| 9 Release | Open |

## 1. Decisions

| Topic | Decision | Why |
|---|---|---|
| Engine | The **shared C++ core**, unchanged: `engine/`, `seq/`, `dsp/`, `devices/`, `model/`, `efx/` (~21 k lines). It is built with the NDK through CMake. | The core was designed for this: no allocation on the audio thread, sub-block rendering and a declarative editor spec. It only uses JUCE's non-GUI modules (`String`, `ValueTree`, `File`, `MidiBuffer`, `juce_dsp`), and those build on Android. |
| Audio I/O | **Oboe** (AAudio, with OpenSL ES below API 27), driven directly and not through JUCE's `AudioDeviceManager`. | Taking the AAudio stream directly gives full control over MMAP/exclusive mode, buffer tuning, xrun counting, device-change restarts and the performance-hint session. JUCE's wrapper hides most of these. |
| UI | **Kotlin + Jetpack Compose**, over a thin JNI bridge (commands in, snapshots out). Pads use a custom touch layer (see §3.3). | The phone UI is a new layout (pads, tabs, sheets), so little of the 10.6 k-line desktop UI carries over. That UI is mouse and keyboard-heavy. Compose gives native sheets, haptics, edge-to-edge, accessibility, file pickers and the share sheet. The design tokens and `EditorSpec` stay single-source (see §3.4). |
| Fallback UI | If Compose touch latency can't meet the target (§2), the pad surface becomes a native `SurfaceView`, and only the pads use it. | This keeps the escape hatch narrow. |
| MIDI | **AMidi** (NDK, API 29+) for USB and BLE controllers. Its events go straight into the engine's input FIFO. | It avoids a Java hop for every MIDI message. |
| Files | The same `.minibox` project format as the desktop, so projects move between phone and desktop in both directions. Projects live in app storage. Import and export go through the Storage Access Framework and the share sheet. | It's one format with no converter, and it keeps desktop parity. |
| Minimum SDK | `minSdk 28` (AAudio MMAP is available), `targetSdk` latest. The performance-hint API is used when present (API 33+). | MMAP exclusive mode is where low latency comes from. |
| ABIs | `arm64-v8a` for release; `x86_64` for emulator debug only. | 32-bit phones can't run the device set in real time anyway. |

> This revises `ARCHITECTURE.md §15.2`, which preferred a JUCE UI. The pad-first phone design shares almost no layout
> code with the desktop, so a JUCE UI would only reuse the painting. The cost would be weaker platform integration and touch handling.

---

## 2. Latency targets

| Measure | Target (Pixel 7-class) | Floor (mid-range, 2022+) | How it's measured |
|---|---|---|---|
| Audio output latency | **≤ 10 ms** | ≤ 20 ms | `AudioStream::calculateLatencyMillis()`, shown in Settings |
| Round trip (for reference) | ≤ 20 ms | ≤ 35 ms | OboeTester round-trip test |
| **Touch → sound** | **≤ 30 ms** | ≤ 45 ms | Mic test: tap the pad over the speaker and measure the click-to-tone onset (an in-app tool, §6) |
| Pad jitter | ≤ 2 ms | ≤ 4 ms | The same mic test, 100 taps |
| Xruns | 0 per 10 min of a full pattern | ≤ 1 | Oboe `getXRunCount()` in the debug overlay |

Touch → sound is the number that matters for pads. Touchscreen scanning already costs 10–20 ms, so the app must add as
little as possible on top of it.

---

## 3. Architecture

```
 ┌────────────── Kotlin / Compose ──────────────┐        ┌─────────────── C++ (NDK) ───────────────────┐
 │ Screens: Play · Seq · Sound · Mix · Perform   │        │                                             │
 │ Sheets: Tempo · Record · Step · Device · …    │        │  Bridge (JNI)                               │
 │                                               │ cmds   │   ├─ CommandQueue  (SPSC, lock-free) ──┐    │
 │ PadSurface (raw MotionEvents) ────────────────┼───────►│   ├─ NoteQueue     (SPSC, timestamped) ┤    │
 │                                               │        │   └─ Snapshot      (triple buffer) ◄──┐│    │
 │ ViewModels ◄── 60 Hz snapshot poll ───────────┼◄───────│                                      ││    │
 └───────────────────────────────────────────────┘        │  Engine · Sequencer · Devices · MasterFx ◄┘ │
                                                          │        ▲ render(sub-blocks)                 │
 ┌── Android services ──┐                                 │  Oboe AAudio stream (MMAP, exclusive)       │
 │ MediaSession / FG svc │                                │  AMidi input ──► NoteQueue                  │
 │ AudioFocus, devices   │                                └─────────────────────────────────────────────┘
 └───────────────────────┘
```

### 3.1 Audio stream (Oboe)

```cpp
oboe::AudioStreamBuilder b;
b.setDirection(oboe::Direction::Output)
 ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
 ->setSharingMode(oboe::SharingMode::Exclusive)          // MMAP when the device allows it
 ->setFormat(oboe::AudioFormat::Float)
 ->setChannelCount(2)
 ->setUsage(oboe::Usage::Media)
 ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None) // run at the device's native rate
 ->setDataCallback(&engineCallback)
 ->setErrorCallback(&restartOnDisconnect);
```

- **Native rate.** Leave the sample rate unset and `prepare()` the engine at whatever the stream reports (usually 48 kHz).
  Resampling adds latency and CPU.
- **Buffer.** Start at `2 × framesPerBurst`. An `oboe::LatencyTuner` grows the buffer one burst at a time on xruns,
  and only up to a cap. Settings shows the size and lets the user pick 1×–4× bursts (mockup `14_project`).
- **Variable callback sizes.** AAudio may call back with any frame count. The engine already renders in sub-blocks, so
  it is prepared for a max block of 2048 and loops over whatever it is given. Don't set `framesPerDataCallback`.
- **Real-time thread.** AAudio's callback thread is already SCHED_FIFO. On API 33+, open an
  `APerformanceHintSession` for that thread. Its target is the burst period, and the callback reports its actual work
  time, so the CPU governor ramps clocks before an xrun happens instead of after.
- **Denormals.** Enable flush-to-zero on arm64 (`juce::ScopedNoDenormals` sets FPCR.FZ) at the top of the callback.
- **Device changes** (headphones, USB interface, Bluetooth): `onErrorAfterClose` reopens the stream on the new device and
  re-`prepare()`s only if the rate changed. The transport keeps its position.
- **Report.** `isMMapUsed()`, the burst size, the buffer size and the latency appear in Settings as one line
  ("AAudio · exclusive · 48 kHz · 192 frames"). The status pill reads "Low latency" only when MMAP is in use.

### 3.2 Engine changes needed first (desktop repo, keeps all tests)

1. **Lock-free structural edits.** Today the UI takes `Engine::audioLock` for structural edits while the callback holds it
   for each block. With 2–4 ms buffers on a phone, a UI thread pre-empted while it holds that lock means an xrun. Replace
   it with the lock-free command queue that `ARCHITECTURE.md §2` already lists as a refinement:
   - Build the new object off-thread.
   - Post a pointer swap.
   - Have the audio thread apply it at the block start.
   - Free the old object on the message thread.

   As an interim step, the callback could `tryEnter()` and render the previous state if the lock is busy.
2. **No `MessageManager` in the core.** `SampleSlot` and the samplers call `MessageManager::callAsync`. Inject a
   `postToMain(std::function<void()>)` instead: the desktop maps it to `MessageManager`, and Android to a `Looper`
   callback through JNI.
3. **`minibox_core` static library.** Split CMake so the core links only `juce_core`, `juce_audio_basics`,
   `juce_audio_formats`, `juce_dsp` and `juce_data_structures`. Android calls `juce::Thread::initialiseJUCE(env, context)`
   once, which is how JUCE runs inside a non-JUCE Android app.
4. **Engine façade.** One header, `MiniboxHost.h`, with plain functions the bridge calls: `noteOn/Off(track, note, vel)`,
   `setParam(track, id, v)`, `step edits`, `transport`, `queuePattern`, `fxHold`, `load/save`, `snapshot()`.
   The desktop UI can move onto it gradually, and the Android build uses only this header.
5. **Snapshot struct.** A POD that the audio thread writes into a triple buffer after each block. It holds the transport
   position, the playing step per track, meters, the queued pattern with its countdown, held FX and the record state.

### 3.3 Touch → sound path (the pads)

- `PadSurface` is a Compose `Modifier.pointerInteropFilter` on the pad grid. Its backing View calls
  **`requestUnbufferedDispatch(event)`** on `ACTION_DOWN`, so touches arrive as soon as they are read rather than
  batched to the next vsync, which saves up to one frame (~8–16 ms).
- Note-on fires on `ACTION_DOWN` / `ACTION_POINTER_DOWN`, never on tap-up, and goes through no gesture detector. Each
  pointer id maps to its pad, so multitouch chords and finger drumming work. Sliding to another pad retriggers it (a
  setting), and lifting a finger sends note-off.
- The JNI entry point is a `@CriticalNative` static (no `JNIEnv`, no objects) that pushes `{track, note, vel, t}` into the
  SPSC `NoteQueue`. The audio callback drains that queue at block start and plays each note at offset 0. The lowest
  latency wins over sample-exact placement, as it does on hardware grooveboxes. When recording, the timestamp is kept so
  the take quantizes from the true touch time.
- **Velocity.** Android pressure values are unreliable, so velocity comes from **touch height in the pad** (top = loud).
  "Fixed" is an option. Both are set in Settings.
- Haptic tick (`HapticFeedbackConstants.KEYBOARD_TAP`) *after* the JNI call, so it never delays the note.
- Long-press is used only in the editors (step rows, roll notes), which don't make sound on touch. Pads never wait.
- The UI preview of a pressed pad lights immediately from local state, not from the engine snapshot.

### 3.4 One source of truth for specs and theme

- `EditorSpec` (sections, knobs, choices, visual type) is exported from C++ as JSON through the bridge. Compose builds each
  Sound tab from it: one section per tab, ≤ 6 knobs, one choice, one visual. Adding a parameter on the desktop adds it
  on Android too.
- Device visuals (filter curve, envelope, waveform, algorithm, operators, sample, slices, ring, chorus, delay, drive,
  voices, arp, bits) are ported to Compose `Canvas`. They are pure functions of parameter values (no audio analysis),
  so each one is a small drawing function. The mockup generator (`svgkit.py`) is the reference geometry.
- Theme tokens (`bg`, `surface`, `control`, the 8 track colours, `pattern`, `record`, `mute`) go in one `MiniboxTheme.kt`,
  generated from `Source/ui/Theme.h` by a small script so the two can't drift.

### 3.5 Threads

| Thread | Does | Never does |
|---|---|---|
| AAudio callback (FIFO) | drain queues, sequencer, render, write snapshot | allocate, lock, log, JNI |
| Main (UI) | Compose, touch → NoteQueue, read snapshot at 60 Hz | file I/O, decoding |
| IO worker | project load/save, sample decode, WAV export, factory unpack | touch the engine except through commands |
| AMidi reader | read MIDI, push to NoteQueue / MidiMap | allocate |

---

## 4. UX mapping: desktop → phone

Bottom nav: **Play · Seq · Sound · Mix · Perform**. The app bar always holds record, play, stop, tempo/position, the
current and queued pattern, and ⋯ (project). The track row (8 chips) sits under it on Play, Seq and Sound. The 4×4
square pads always sit on the nav; on Seq and Sound they fold into a Pads handle that pulls them back up.

| Desktop | Phone | Mockup |
|---|---|---|
| Computer keys / MIDI input, Play drawer (Scale, Chord, Arp) | **Play** tab: 4×4 square in-scale pads at the bottom (rows a fourth apart); Notes / Chords / Arp, scale, octave and 8 macros above | 01, 02 |
| Drum pads | **Play** tab, drum track: 4×4, lower 8 hit the parts, upper 8 roll them at the rate (velocity by touch height) | 03 |
| Seq page: steps | **Seq → Steps**: step editor panel, one row per step with all its settings (synth) or steps × parts (drums) | 04, 05 |
| Seq page: piano roll / clips | **Seq → Roll**: piano roll panel with keys, free-length notes, Draw/Select, snap, undo, velocity lane; pads pull up beneath it | 15, 16 |
| Step locks, probability, ratchet, micro | Long-press a step row → step sheet | 06 |
| Device editors (03–10) | **Sound** tab: preset bar, section tabs, one visual + ≤ 6 knobs, audition pads | 07, 08 |
| Rack strips (level, pan, sends, M/S), master row | **Mix** tab: 8 fader columns (drag anywhere), M/S pads, master | 09 |
| A–H switcher, performance mode, PERFORM pads | **Perform** tab: 8 pattern pads (queued = dashed + countdown), quantize, 8 hold FX pads | 10 |
| Slot device picker | Long-press a track chip (or tap an empty one) → sheet | 11 |
| BPM, Tap, time signature, swing, click | Tap the tempo → sheet | 12 |
| Recording popover | Long-press record → sheet | 13 |
| New/Open/Save/Export, settings | ⋯ → Project | 14 |
| Rack macros | 4 macros under the pads (swipe for 5–8); hold a step and turn one to lock it | 01–03 |
| MIDI learn, controller maps | ⋯ → MIDI → Learn (phase 6) | — |
| Keyboard shortcuts | Not on phone (a hardware keyboard gets the desktop map later) | — |
| Master effects chain | Mix → Effects sheet | — |
| DAWproject / MIDI / WAV export | Project → Export | — |

Rules carried over from the desktop: one hero per screen, the track colour is the only accent, the pattern colour
appears only on pattern things, red means record only, and there's no instructional text. Every feature is kept, but
anything that isn't playing music sits behind a long-press or ⋯.

---

## 5. Phases

Each phase ends with something playable on a real phone.

| # | Phase | Contents | Done when |
|---|---|---|---|
| 0 | **Core prep** (desktop repo) | §3.2 items 1–5. The desktop and plugin keep passing `MiniboxTests` (no-allocation, golden render, sequencer). | Desktop unchanged for users; the core builds as `minibox_core` |
| 1 | **Android skeleton** | Gradle + AGP, NDK r27+, `externalNativeBuild` CMake, Oboe via Prefab, JUCE as a submodule pinned to 8.0.6. Edge-to-edge single Activity, portrait lock. CI job builds the APK. | An empty app opens on a phone; CI produces an APK |
| 2 | **Latency spike** ⟵ go / no-go | Oboe stream + Bassline device + a 4×4 `PadSurface`. Settings latency line, xrun counter, the in-app tap-to-sound test (§6). Measure on 3 phones (one flagship, one mid-range, one 3–4-year-old). | §2 targets met. If touch → sound misses, move the pads to a `SurfaceView` before continuing. |
| 3 | **Engine on device** | All 7 devices, master FX, sequencer, patterns. Run `MiniboxTests` on the phone with `adb shell` (it's a console executable) for the no-allocation, CPU and golden checks on ARM. | Tests pass on arm64; a factory demo plays without xruns |
| 4 | **Play + Perform** | App bar, track row, Play (notes / chords / arp / drums / roll), Perform (patterns, FX pads), Tempo and Record sheets. Live recording. | A beat can be jammed and recorded without Seq |
| 5 | **Seq + Sound** | Step editor panel (synth rows, drum steps × parts), piano roll panel (draw/select, resize, snap, undo, velocity lane), pads drawer for step input, step sheet, locks (hold step + macro). Spec-driven Sound tabs with all visuals. Device picker, preset browser (search, families, user save). Clip tracks shown as a roll. | Everything in the desktop sequencer and editors is reachable |
| 6 | **Mix, files, MIDI** | Mix tab, master FX sheet. Projects (save, open, autosave on `onPause`), SAF sample import, WAV/MIDI/DAWproject export + share. AMidi input, MIDI learn, `.mbmidi` maps. | Projects round-trip phone ↔ desktop |
| 7 | **Platform polish** | Foreground service (`mediaPlayback`) + MediaSession notification, audio focus, Bluetooth warning + offset, thermal headroom watcher, tablet centring (max 600 dp), accessibility labels. | Survives backgrounding, calls, unplugging and 30 min of play while warm |
| 8 | **Low-end pass** | Profile on a 4-core / 3 GB phone. Add the quality tiers `ARCHITECTURE.md §15.4` mentions but never built: a voice cap per device, FM/drive oversampling off, reverb quality. Auto-step them down on thermal *moderate*. | A full 8-track pattern plays on the low-end phone |
| 9 | **Release** | Signed APK on GitHub releases (like the desktop builds), optional Play internal track. | — |

The order is deliberate: latency is proven in phase 2, before any UI investment.

---

## 6. Measuring latency in the app

Settings → Audio → *Test* opens a full-screen pad. Hold the phone's mic near its speaker and tap the pad 20 times. The
app plays a click on each touch and records the mic through Oboe input. It then reports, for each tap, the time from
`MotionEvent.getEventTimeNanos()` to the detected onset, with the median and spread. The same screen shows `isMMapUsed`,
the burst size, the buffer and the xrun count. This gives one number to compare phones and builds, and it catches
regressions such as a UI change that reintroduced vsync batching.

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| Some phones refuse MMAP / exclusive mode (shared mode adds ~10–20 ms) | Detect it and show "Standard latency" honestly. Recommend wired or USB audio. Nothing else can be done in software. |
| Bluetooth A2DP output adds 150–250 ms | Detect `TYPE_BLUETOOTH_A2DP` and show a badge. Offer a latency offset that only aligns the click and recording; live pads can't be fixed. LE Audio devices are better. |
| CPU on mid/low-end phones (FM 8 voices, VA 6 voices, reverbs, 8 tracks) | Performance hint session, quality tiers, sleeping devices (already built), CPU meter in the debug overlay, phase 8 |
| Thermal throttling during long sessions | `PowerManager.getThermalHeadroom()` → step the quality tier down before throttling starts |
| Priority inversion through the engine lock | Phase 0 removes the lock from the audio path |
| Factory content is 61 MB | Ship it in the APK's assets and unpack it to app storage on first run or on version change (IO worker, with progress) |
| Compose recomposition jank at 60 Hz snapshots | Read the snapshot into `State` objects per widget, and draw playheads and meters in `Canvas` with `drawWithCache`. Never recompose the whole grid. |
| Two UI codebases (desktop JUCE, phone Compose) | Specs and theme are generated from C++ (§3.4). Behaviour lives in the core behind `MiniboxHost.h`. The UIs differ only in layout. |

---

## 8. Repository layout (proposed)

```
minibox-android/
├─ PLAN.md                     this file
├─ mockups/                    phone mockups (tools/build.py → svg/, png/)
├─ app/                        Android app module (Kotlin, Compose)
│  ├─ src/main/java/…/minibox/ ui/ (screens, sheets, PadSurface), bridge/ (JNI), audio/ (service, focus)
│  └─ src/main/cpp/            CMakeLists.txt, bridge.cpp, OboeHost.cpp, MidiIn.cpp
├─ core → ../pastel-minibox    the shared C++ core (git submodule or CMake path), never forked
└─ tools/                      theme/spec generators, latency analysis
```
