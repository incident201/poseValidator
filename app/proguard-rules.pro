-dontrepackage

-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.flatbuffers.** { *; }
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.flatbuffers.**
-dontwarn org.tensorflow.**
-dontwarn org.tensorflow.lite.**
