# Release builds are shipped unminified (isMinifyEnabled=false) for reliability.
# Keep rules kept minimal & future-proofed in case minification is enabled later.

-keep class com.googlecode.tesseract.android.** { *; }
-keep class org.opencv.** { *; }
-dontwarn com.tom_roush.**
-keep class com.tom_roush.** { *; }
-dontwarn javax.imageio.**
-dontwarn java.awt.**
