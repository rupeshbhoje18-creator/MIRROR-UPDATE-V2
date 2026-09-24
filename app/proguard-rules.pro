# ==== ROOM (annotations/reflective) ====
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# ==== GSON (serialize/deserialize) ====
-keep class com.google.gson.stream.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.example.tallycustomerapp.data.** { *; }  

# Keep default constructors for Gson
-keepclassmembers class * {
    public <init>(...);
}

# ==== COROUTINES ====
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ==== Kotlin/Reflection/Annotations ====
-keepattributes Signature,*Annotation*
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.** { *; }

# ==== (Optional: for ViewBinding/SafeArgs) ====
-keep class **.databinding.*Binding { *; }

# ==== AndroidX lifecycle/ViewModel (for completeness) ====
-keep class androidx.lifecycle.** { *; }
-keep class androidx.arch.core.** { *; }