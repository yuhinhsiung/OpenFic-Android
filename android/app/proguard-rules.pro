# Keep default Android optimizations; the WebView bridge is reached by name from JS,
# so nothing here needs to survive shrinking yet (minify is disabled for now).
-keepclassmembers class com.openfic.android.** {
    @android.webkit.JavascriptInterface <methods>;
}
