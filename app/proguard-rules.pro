# whisper_jni.cpp uses name-based JNI entry points. The Android default rules preserve native
# method names, but keeping this boundary explicitly makes that release-critical contract visible.
-keep class com.slide.asr.WhisperNative { *; }

# ExecuTorch's prebuilt libexecutorch.so resolves this Java API by exact JNI class, method, and
# field names. The patched AAR does not carry consumer rules, so preserve the complete boundary;
# otherwise R8 can retain Module while removing EValue/Tensor and release-only neural inference
# fails before the debug instrumentation suites can observe it.
-keep class org.pytorch.executorch.** { *; }

# ExecuTorch's JNI layer is built on fbjni. Version 0.7.0 ships DoNotStrip annotations but no
# consumer rules that make R8 honour them, so preserve its Java/native bridge as well.
-keep class com.facebook.jni.** { *; }
# fbjni 0.7.0 annotates nullable bridge fields with this compile-only annotation, which is absent
# from Android at runtime and has no behavioral role in the JNI contract.
-dontwarn javax.annotation.Nullable

# Keep useful source locations in native/process crash reports without retaining local variable
# names or disabling R8 optimisation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
