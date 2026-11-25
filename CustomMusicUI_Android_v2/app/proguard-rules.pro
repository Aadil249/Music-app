# Keep app classes, services, activities, and WebView JS bridge
-keep class com.custommusicui.app.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*,JavascriptInterface
