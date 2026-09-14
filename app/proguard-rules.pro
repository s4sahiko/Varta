# Keep TensorFlow Lite GPU/NNAPI delegate classes referenced only via reflection.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Vosk and JNA JNI bindings
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keep class net.java.dev.jna.** { *; }
-dontwarn net.java.dev.jna.**

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Do not obfuscate class names to ensure AndroidManifest & JNI bindings resolve cleanly
-dontobfuscate
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep class com.offlinevoicerelay.** { *; }
-dontwarn com.offlinevoicerelay.**
