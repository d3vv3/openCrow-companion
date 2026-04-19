# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }

# Gson
-keep class org.opencrow.app.data.remote.dto.** { *; }
-keep class org.opencrow.app.data.local.entity.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
