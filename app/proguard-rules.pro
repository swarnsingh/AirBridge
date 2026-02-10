# ProGuard rules for AirBridge

# Keep model classes for serialization
-keep class com.swaran.airbridge.domain.model.** { *; }
-keep class com.swaran.airbridge.core.storage.model.** { *; }

# Keep Hilt annotations
-keepattributes *Annotation*
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep Gson serialized names
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Remove logging in release
-assumenosideeffects class android.util.Log { *; }
-assumenosideeffects class kotlin.jvm.internal.Intrinsics { *; }
