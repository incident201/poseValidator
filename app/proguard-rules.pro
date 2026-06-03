# MediaPipe Tasks / TFLite / generated metadata
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.flatbuffers.** { *; }
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }

# JNI/native methods often rely on stable member names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep annotations/signatures that reflective/native code may inspect
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.flatbuffers.**
-dontwarn org.tensorflow.**
-dontwarn org.tensorflow.lite.**
