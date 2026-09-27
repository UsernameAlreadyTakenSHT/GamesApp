# R8 keep rules for the release build.

# ONNX Runtime (Maia 3) is driven through JNI: its Java side is looked up by name from native code.
-keep class ai.onnxruntime.** { *; }

# OpenTafl's engine core is compiled from source (opentafl/java); keep it whole, it is only
# ~1 MB and some of it is reached through its own rules loading.
-keep class com.manywords.softworks.tafl.** { *; }
-dontwarn com.manywords.softworks.tafl.**

# Release builds carry no verbose engine I/O logging.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
