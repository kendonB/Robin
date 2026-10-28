# R8 / ProGuard — release minify is on (Play DEX obfuscation threshold).

# WebView JS bridges are invoked by name from injected scripts.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep useful local crash locations without collecting remote telemetry.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Additional safeguard against shipping diagnostic logs.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
