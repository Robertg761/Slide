cmake_minimum_required(VERSION 3.22.1)
project(slide_asr CXX)

set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

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

# With GGML_NATIVE off and no arch named, ggml emits no -march at all and the whole library is
# built for baseline armv8-a. That silently compiles out every dotprod and fp16 kernel path, which
# is most of the arithmetic in a quantised model, and leaves the scalar fallbacks to do the work.
#
# armv8.2-a+dotprod+fp16 is the floor rather than this phone's ceiling: it covers essentially every
# arm64 Android device since 2018, and the flags select code paths at compile time with no runtime
# check, so anything named here is a hard requirement on the devices Slide runs on. i8mm and sve are
# deliberately left out for that reason -- they are recent-flagship-only, and the model is q5_1,
# which has no i8mm kernel to reach anyway.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

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
