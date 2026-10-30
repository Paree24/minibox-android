# Pastel Minibox for Android

The Android port of [Pastel Minibox](../pastel-minibox): the groovebox on a phone, portrait only, with 16 square
pads where the keyboard was and low-latency audio. See [PLAN.md](PLAN.md) for the port plan and
[mockups/](mockups/README.md) for the screens.

> Work in progress: phases 0–2 and 4–7 of PLAN.md are in (see "Status" there). Phase 3 (the test suite on a
> phone) and phase 8 (quality tiers for low-end phones) are still to do.

## Layout

- `app/src/main/cpp/`: the native library. `CMakeLists.txt` builds JUCE's non-GUI modules and the shared core
  straight from `../pastel-minibox/Source` (never forked); `MiniboxHost` runs the engine from an Oboe stream;
  `Bridge.cpp` is the JNI surface (`@CriticalNative` for the pads, rolls and FX holds); `Api.cpp`, `SeqApi.cpp`,
  `SoundApi.cpp`, `ProjectApi.cpp` answer the UI's JSON commands and queries; `MidiIn.cpp` reads controllers.
- `app/src/main/java/com/pastel/minibox/`: Kotlin + Compose UI. `state/` mirrors the engine (`App`, `Seq`, `Sound`);
  `ui/` has one file per tab and sheet; `ui/PadView.kt` is the raw-touch pad grid; `engine/` wraps the native
  library, MIDI, the audio device and preferences; `PlaybackService` keeps playing in the background.
- Factory content (presets, samples) is copied from `../pastel-minibox/Factory` into the APK's assets at build time
  and unpacked into app storage on first run.

## Building

Needs JDK 17–21, the Android SDK (platform 37, NDK 29.0.14206865, CMake 3.31.6), and two sibling checkouts:
`../pastel-minibox` and `../JUCE` (8.0.6). Change the paths in `gradle.properties` if they live elsewhere.

```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Native code is built optimised (`RelWithDebInfo`) even in debug builds: the audio path is unusable without it.

JUCE 8.0.6 predates NDK r29, which dropped `<sys/dir.h>` and `<sys/timeb.h>`; `app/src/main/cpp/compat/` holds
the two one-line shims.

## License

GPL-3.0, like Pastel Minibox.
