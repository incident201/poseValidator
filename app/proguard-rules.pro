-dontwarn javax.naming.**
-dontwarn org.eclipse.jetty.xml.XmlConfiguration
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
-dontobfuscate

# MediaPipe Tasks / Framework / native bridge
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.flatbuffers.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Keep native method names/classes reachable from JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * {
    native <methods>;
}

# Keep metadata often used by generated/reflective code
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations
-keepattributes AnnotationDefault
