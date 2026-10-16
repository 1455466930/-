# Xposed
-keep class de.robv.android.xposed.** { *; }
-keep class com.junge.aidepro.hook.** { *; }
-keepnames class com.junge.aidepro.hook.XpEntry

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }

# Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class com.junge.aidepro.** { *** Companion; }
-keepclasseswithmembers class com.junge.aidepro.** { kotlinx.serialization.KSerializer serializer(...); }

# AIDL
-keep class com.junge.aidepro.ipc.** { *; }
