# Xposed
-keep class de.robv.android.xposed.** { *; }
-keep class com.sfzsgybb.ai.hook.** { *; }
-keepnames class com.sfzsgybb.ai.hook.XpEntry

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }

# Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class com.sfzsgybb.ai.** { *** Companion; }
-keepclasseswithmembers class com.sfzsgybb.ai.** { kotlinx.serialization.KSerializer serializer(...); }

# AIDL
-keep class com.sfzsgybb.ai.ipc.** { *; }
