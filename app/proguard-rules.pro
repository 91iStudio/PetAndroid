# Nothing special: the app uses only Android itself.

# The game page calls these by name (MainActivity.Bridge): keep them as they are
-keepclassmembers class ru.i91.pushishi.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
