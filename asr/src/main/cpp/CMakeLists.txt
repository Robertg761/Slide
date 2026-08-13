cmake_minimum_required(VERSION 3.22.1)
project(slide_asr CXX)

# Supplied for every ABI; only ARM64 stages runtime backends below.
message(VERBOSE "Speech CPU backend stage root: ${SLIDE_CPU_BACKEND_STAGE_DIR}")

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
# It has to be off, or a non-variant build targets this laptop rather than the phone.
set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)

# Do not set GGML_CPU_ARM_ARCH. Android's arm64-v8a ABI guarantees Armv8.0, while dotprod and fp16
# vector arithmetic are optional CPU features. Keep the global floor at the ABI baseline; the
# ARM64 block below adds separately loadable, compatibility-scored variants rather than making an
# optional instruction a process-crashing install requirement. Other Android ABIs retain their
# compiler-defined ABI baseline.
set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)

# Arm64 Android spans baseline Armv8 phones and newer cores with dot-product, FP16, i8mm, SVE, and
# SME instructions. A single baseline build leaves most of q5_1 Whisper's arithmetic on slow
# fallback kernels, while a single modern -march crashes older supported phones. ggml's Android
# CPU variants solve both problems: each shared backend advertises its required features and the
# loader selects the fastest one the current CPU can execute. Other ABIs retain the small static
# baseline because ggml's all-variant matrix is not available for 32-bit ARM Android and would add
# little value to the emulator-oriented x86 packages.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(BUILD_SHARED_LIBS      ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL        ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS  ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${WHISPER_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL)

# whisper.cpp currently builds its unrelated Parakeet library whenever shared libraries are on.
# Slide ships no Parakeet model or API, so keep that dead code out of the APK.
if(TARGET parakeet)
    set_property(TARGET parakeet PROPERTY EXCLUDE_FROM_ALL TRUE)
endif()

add_library(slide_asr SHARED whisper_jni.cpp)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    if(NOT DEFINED SLIDE_CPU_BACKEND_STAGE_DIR)
        message(FATAL_ERROR "SLIDE_CPU_BACKEND_STAGE_DIR is required for ARM64 runtime backends")
    endif()
    # The JNI bridge reaches these libraries by SONAME through dlopen, so they are deliberately not
    # ELF link dependencies. Build and stage every candidate; the Gradle Variant API wires a
    # validation/copy task into each variant's generated JNI sources so a clean build cannot omit
    # them or accidentally reuse stale files.
    add_dependencies(slide_asr
        ggml-cpu-android_armv8.0_1
        ggml-cpu-android_armv8.2_1
        ggml-cpu-android_armv8.2_2
        ggml-cpu-android_armv8.6_1
        ggml-cpu-android_armv9.0_1
        ggml-cpu-android_armv9.2_1
        ggml-cpu-android_armv9.2_2
    )
    foreach(cpu_variant
        ggml-cpu-android_armv8.0_1
        ggml-cpu-android_armv8.2_1
        ggml-cpu-android_armv8.2_2
        ggml-cpu-android_armv8.6_1
        ggml-cpu-android_armv9.0_1
        ggml-cpu-android_armv9.2_1
        ggml-cpu-android_armv9.2_2
    )
        add_custom_command(TARGET slide_asr POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory
                ${SLIDE_CPU_BACKEND_STAGE_DIR}/${CMAKE_BUILD_TYPE}/${ANDROID_ABI}
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                $<TARGET_FILE:${cpu_variant}>
                ${SLIDE_CPU_BACKEND_STAGE_DIR}/${CMAKE_BUILD_TYPE}/${ANDROID_ABI}/$<TARGET_FILE_NAME:${cpu_variant}>
        )
    endforeach()
endif()

target_include_directories(slide_asr PRIVATE ${WHISPER_ROOT}/include ${WHISPER_ROOT}/ggml/include)

target_link_libraries(slide_asr PRIVATE
    whisper
    android   # AAssetManager, for reading the model straight out of the APK
    log
)

# The static whisper/ggml archives are compiled without -fPIC by default in some configurations;
# linking them into a shared object requires it.
set_target_properties(whisper ggml ggml-base PROPERTIES POSITION_INDEPENDENT_CODE ON)
if(TARGET ggml-cpu)
    set_target_properties(ggml-cpu PROPERTIES POSITION_INDEPENDENT_CODE ON)
endif()

target_compile_options(slide_asr PRIVATE -fvisibility=hidden -Wall -Wextra)
