cmake_minimum_required(VERSION 3.22.1)
project(slide_asr CXX)

file(REAL_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." SLIDE_REPOSITORY_ROOT)
set(WHISPER_ROOT ${SLIDE_REPOSITORY_ROOT}/third_party/whisper.cpp)

# The vendored library is a source snapshot, not a nested Git checkout. Its own fallback to
# `git rev-parse` would therefore identify the enclosing Slide checkout (or "unknown" in a source
# archive), making otherwise identical builds carry different and incorrect provenance.
file(READ "${WHISPER_ROOT}/VENDORED_COMMIT" WHISPER_VENDORED_COMMIT)
string(STRIP "${WHISPER_VENDORED_COMMIT}" WHISPER_VENDORED_COMMIT)
string(LENGTH "${WHISPER_VENDORED_COMMIT}" WHISPER_VENDORED_COMMIT_LENGTH)
if(NOT WHISPER_VENDORED_COMMIT_LENGTH EQUAL 40 OR
   NOT WHISPER_VENDORED_COMMIT MATCHES "^[0-9a-f]+$")
    message(FATAL_ERROR "Invalid whisper.cpp VENDORED_COMMIT: ${WHISPER_VENDORED_COMMIT}")
endif()
set(GGML_BUILD_COMMIT_OVERRIDE "${WHISPER_VENDORED_COMMIT}")

# Native debug/symbol metadata otherwise records the absolute checkout directory, making the four
# packaged libslide_asr.so files differ between a Git checkout and the release's source-export
# rebuild. Map both Slide's source and CMake build roots to stable virtual locations before any
# vendored target is created so the setting applies to whisper/ggml as well as the JNI bridge.
add_compile_options(
    "-ffile-prefix-map=${SLIDE_REPOSITORY_ROOT}=/src/slide"
    "-fdebug-prefix-map=${SLIDE_REPOSITORY_ROOT}=/src/slide"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=/build/slide"
    "-fdebug-prefix-map=${CMAKE_BINARY_DIR}=/build/slide"
)

# Everything whisper.cpp builds by default that Slide does not need. Left on, the examples pull in
# SDL2 and a CLI, none of which can link on Android.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)

# CPU only for now. The NPU and GPU paths (Hexagon, OpenCL, Vulkan) are vendored and worth trying,
# but a wrong answer fast is worse than a right answer in a second, and CPU is the path that is
# known to be correct on every device.
set(GGML_CPU     ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)  # the NDK ships no libomp to link against
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# GGML_NATIVE means "-march=native", which asks the cross compiler about the build machine's CPU.
# It has to be off, or the emitted code targets this laptop rather than the phone.
set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)

# Do not set GGML_CPU_ARM_ARCH. Android's arm64-v8a ABI guarantees Armv8.0, while dotprod and fp16
# vector arithmetic are optional CPU features. The static ggml backend cannot safely select its
# GGML_CPU_ALL_VARIANTS modules from an APK, so compile its portable baseline instead of making an
# optional instruction a process-crashing install requirement. Other Android ABIs likewise retain
# their compiler-defined ABI baseline.
set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)

add_subdirectory(${WHISPER_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL)

add_library(slide_asr SHARED whisper_jni.cpp)

target_include_directories(slide_asr PRIVATE ${WHISPER_ROOT}/include ${WHISPER_ROOT}/ggml/include)

target_link_libraries(slide_asr PRIVATE
    whisper
    android   # AAssetManager, for reading the model straight out of the APK
    log
)

# The static whisper/ggml archives are compiled without -fPIC by default in some configurations;
# linking them into a shared object requires it.
set_target_properties(whisper ggml ggml-base ggml-cpu PROPERTIES POSITION_INDEPENDENT_CODE ON)

target_compile_options(slide_asr PRIVATE -fvisibility=hidden -Wall -Wextra)
