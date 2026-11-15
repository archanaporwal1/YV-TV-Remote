# Keep Protobuf JavaLite generated messages and enums
-keep class com.example.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }

# BouncyCastle optional desktop/JVM references
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
