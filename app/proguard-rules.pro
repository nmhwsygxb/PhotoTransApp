# PhotoTrans ProGuard 规则

# Keep JSON
-keep class org.json.** { *; }

# Keep Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep ViewBinding
-keep class com.phototrans.databinding.** { *; }

# Keep BuildConfig
-keep class com.phototrans.BuildConfig { *; }

# Keep model classes
-keep class com.phototrans.model.** { *; }
-keep class com.phototrans.format.** { *; }
-keep class com.phototrans.transport.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}