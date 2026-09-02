# Protobuf Lite stores generated field names in message metadata and resolves them
# reflectively at runtime. R8 must preserve those fields and their names.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Flogger discovers callers from runtime stack frames. R8 inlining, outlining, or
# renaming its implementation breaks MediaPipe Graph static initialization.
-keep class com.google.common.flogger.** { *; }

# MediaPipe JNI resolves framework bridge classes, methods, and fields by their
# original Java names (for example ProtoUtil.SerializedMessage.typeName).
-keep class com.google.mediapipe.framework.** { *; }

# Optional framework APIs reference proto types that are not packaged by the
# Tasks Vision AAR and are not used by this application.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

# Jetty's optional desktop-only TLS subject parser and XML client provider are
# not present on Android and are not used by the Intiface WebSocket client.
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn org.eclipse.jetty.xml.XmlConfiguration

# Jetty discovers annotated WebSocket endpoints and their callbacks at runtime.
# Keep the endpoint annotation metadata and any class marked as a WebSocket.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep @interface org.eclipse.jetty.websocket.api.annotations.**
-keep @org.eclipse.jetty.websocket.api.annotations.WebSocket class * { *; }

# Buttplug4J derives protocol message keys and payload fields from class and
# field names. Obfuscating them turns RequestServerInfo into invalid JSON.
-keep class io.github.blackspherefollower.buttplug4j.protocol.** { *; }
