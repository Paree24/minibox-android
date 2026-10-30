cmake_minimum_required(VERSION 3.22)
project(minibox_native C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MINIBOX_CORE_DIR "" CACHE PATH "pastel-minibox checkout (the shared C++ core)")
set(MINIBOX_JUCE_DIR "" CACHE PATH "JUCE 8.0.6 checkout")
if(NOT EXISTS "${MINIBOX_CORE_DIR}/Source/engine/Engine.h")
  message(FATAL_ERROR "MINIBOX_CORE_DIR does not point at pastel-minibox: ${MINIBOX_CORE_DIR}")
endif()
if(NOT EXISTS "${MINIBOX_JUCE_DIR}/modules/juce_core/juce_core.h")
  message(FATAL_ERROR "MINIBOX_JUCE_DIR does not point at JUCE: ${MINIBOX_JUCE_DIR}")
endif()

# ---- JUCE: only the non-GUI modules the core needs
set(JM "${MINIBOX_JUCE_DIR}/modules")
set(JUCE_MODULES juce_core juce_events juce_data_structures juce_audio_basics juce_audio_formats juce_dsp)
add_library(juce_min STATIC
  ${JM}/juce_core/juce_core.cpp
  ${JM}/juce_core/juce_core_CompilationTime.cpp
  ${JM}/juce_events/juce_events.cpp
  ${JM}/juce_data_structures/juce_data_structures.cpp
  ${JM}/juce_audio_basics/juce_audio_basics.cpp
  ${JM}/juce_audio_formats/juce_audio_formats.cpp
  ${JM}/juce_dsp/juce_dsp.cpp
  ${ANDROID_NDK}/sources/android/cpufeatures/cpu-features.c)   # JUCE reads the CPU count and features with it
set(JUCE_DEFS JUCE_ANDROID=1 JUCE_GLOBAL_MODULE_SETTINGS_INCLUDED=1 JUCE_STANDALONE_APPLICATION=0 JUCE_USE_CURL=0
    JUCE_WEB_BROWSER=0 JUCE_DISPLAY_SPLASH_SCREEN=0 JUCE_MODAL_LOOPS_PERMITTED=0 JUCE_USE_ANDROID_OBOE=0
    $<$<CONFIG:Debug>:DEBUG=1 _DEBUG=1> $<$<NOT:$<CONFIG:Debug>>:NDEBUG=1>)
foreach(m ${JUCE_MODULES})
  list(APPEND JUCE_DEFS JUCE_MODULE_AVAILABLE_${m}=1)
endforeach()
target_compile_definitions(juce_min PUBLIC ${JUCE_DEFS})
target_include_directories(juce_min PUBLIC ${JM} ${CMAKE_CURRENT_SOURCE_DIR}/compat ${ANDROID_NDK}/sources/android/cpufeatures)
target_compile_options(juce_min PRIVATE -w)   # third-party: no warnings
target_link_libraries(juce_min PUBLIC android log)

# ---- the shared core (engine, sequencer, devices, DSP, model, effects, preset bank): ../pastel-minibox/Source
set(CS "${MINIBOX_CORE_DIR}/Source")
file(GLOB_RECURSE MINIBOX_CORE CONFIGURE_DEPENDS
  ${CS}/engine/*.cpp ${CS}/model/*.cpp ${CS}/seq/*.cpp ${CS}/dsp/*.cpp ${CS}/devices/*.cpp ${CS}/efx/*.cpp
  ${CS}/app/presets/*.cpp ${CS}/ui/EditorSpecs.cpp)   # EditorSpecs: the Sound tabs come from the same specs
add_library(minibox_core STATIC ${MINIBOX_CORE})
target_include_directories(minibox_core PUBLIC ${CS} ${CS}/efx)
target_link_libraries(minibox_core PUBLIC juce_min)

# ---- the app's native library: Oboe audio, the engine host and the JNI bridge
find_package(oboe REQUIRED CONFIG)
add_library(minibox SHARED Bridge.cpp MiniboxHost.cpp Api.cpp SeqApi.cpp SoundApi.cpp)
target_link_libraries(minibox PRIVATE minibox_core oboe::oboe)
target_compile_options(minibox PRIVATE -Wall -Wextra)

foreach(t juce_min minibox_core minibox)
  target_compile_options(${t} PRIVATE $<$<NOT:$<CONFIG:Debug>>:-O3>)   # same numerics as the desktop build (lock lanes use NaN)
endforeach()
