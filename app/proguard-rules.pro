# whisper_jni.cpp uses name-based JNI entry points. The Android default rules preserve native
# method names, but keeping this boundary explicitly makes that release-critical contract visible.
-keep class com.slide.asr.WhisperNative { *; }

# Keep useful source locations in native/process crash reports without retaining local variable
# names or disabling R8 optimisation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
