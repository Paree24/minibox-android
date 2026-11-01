# Minibox for Android — Groovebox (Phone)

> **Disclaimer:** this project is vibe-coded for personal use. It is provided
> as-is, without warranty of any kind. The author is not responsible for
> anything — Use at your own risk.

Minibox for Android is the [Pastel Minibox](https://github.com/Paree24/pastel-minibox)
groovebox on a phone: portrait only, pads first, with low-latency audio. It runs
the desktop app's engine and devices unchanged (built straight from the
pastel-minibox sources), with a touch interface of its own:

- **Seven instruments:** Bassline, VA (DCO and VCO engines), Acid, FM, Sampler,
  Drums and Drum Sampler, with the desktop's factory presets and kits.
- **Play:** square pads where the keyboard was: 16 (or 24 for synths) in-scale
  notes, diatonic chords or an arpeggiator, drum pads laid out for two thumbs,
  with note repeat. Eight macros per instrument.
- **Seq:** a step sequencer (per-step velocity, gate, chance, ratchets, accent,
  slide, parameter locks) with velocity pages, and a vertical piano roll with
  full screen, pinch zoom, paste cursor and velocity tab. Live recording into
  loops or growing patterns, undo / redo.
- **Sound, Mix, Perform:** every device's editor with its visuals, three effect
  slots per track, a master effects chain, a mixer, and eight performance
  effects on two XY pads (the timed ones quantized to musical divisions).
- **Files:** projects saved to `Documents/Minibox`, export of a pattern or the
  whole song as WAV, FLAC or OGG, MIDI and DAWproject; MIDI controllers (the
  ones Android's MIDI service lists).

Audio runs on Oboe (AAudio, exclusive MMAP where the phone allows it) with
latency modes from the smallest the device sustains to 80 ms, and an Eco
quality tier for low-end phones.

## License

GPL version 3 — see [LICENSE](LICENSE). This also satisfies the JUCE
framework's licensing terms for this project.

The shared engine, devices and factory content come from
[Pastel Minibox](https://github.com/Paree24/pastel-minibox) (same author, same
license). Audio I/O uses [Oboe](https://github.com/google/oboe) (Apache License
2.0).

## Installing a release

Each release carries the APK. On the phone (Android 9 or later):

1. Download `Minibox-<version>.apk` from the release page.
2. Open it and allow installing from your browser or file manager when asked.

Alpha builds are signed with a development key: a later build signed with a
release key needs the earlier one uninstalled first (projects saved to
`Documents/Minibox` stay).

## Building

Requirements: JDK 17–21, the Android SDK (platform 37, NDK 29.0.14206865,
CMake 3.31.6), and two sibling checkouts: `../pastel-minibox` (the shared core
and factory content) and `../JUCE` (8.0.6). If they live elsewhere, change
`minibox.coreDir` and `minibox.juceDir` in `gradle.properties`.

```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

`assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`. Native
code is built optimised (`RelWithDebInfo`) in debug builds too: the audio path
is unusable without it. Debug builds also accept engine calls from `adb` (a
`com.pastel.minibox.CAPTURE` broadcast) to record the output or drive the app
in tests.

The engine's own test suite runs on the desktop: build pastel-minibox and run
its `MiniboxTests`.

### Notes

- JUCE 8.0.6 predates NDK r29, which dropped `<sys/dir.h>` and `<sys/timeb.h>`;
  `app/src/main/cpp/compat/` holds the two one-line shims.
- Factory content (presets, samples, MIDI maps) is copied from
  `../pastel-minibox/Factory` into the APK's assets at build time and unpacked
  into app storage on first run.

## Repository layout

- `app/src/main/cpp/` — the native library: `CMakeLists.txt` builds JUCE's
  non-GUI modules and the shared core from `../pastel-minibox/Source` (never
  forked); `MiniboxHost` runs the engine from an Oboe stream; `Bridge.cpp` is
  the JNI surface (`@CriticalNative` for pads, rolls and effect holds);
  `Api.cpp`, `SeqApi.cpp`, `SoundApi.cpp` and `ProjectApi.cpp` answer the UI's
  JSON commands and queries; `MidiIn.cpp` reads controllers
- `app/src/main/java/com/pastel/minibox/` — Kotlin + Jetpack Compose UI:
  `state/` mirrors the engine (`App`, `Seq`, `Sound`); `ui/` has one file per
  tab and sheet, `PadGrid.kt` the raw-touch pads and `PianoRoll.kt` the roll;
  `engine/` wraps the native library, MIDI, the audio device, files and
  preferences; `PlaybackService` keeps playing in the background
- `mockups/` — the screen mockups (`png/`) and their generators (`tools/`),
  including the piano roll layout and logo options
- `PLAN.md` — the port plan, status and parked work
