# Keep Cronet's JNI entry points and provider classes.
-keep class org.chromium.net.** { *; }
-keep class org.chromium.base.** { *; }
-keep class org.chromium.build.** { *; }
-dontwarn org.chromium.net.**
-dontwarn org.chromium.base.**

# Media3 uses reflection for some optional extensions.
-dontwarn androidx.media3.**
