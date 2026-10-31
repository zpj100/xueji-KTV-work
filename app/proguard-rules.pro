# 保留应用入口。
-keep class com.local.ktv.KtvApplication { *; }
-keep class com.local.ktv.MainActivity { *; }
-keep class com.local.ktv.BootReceiver { *; }
-keep class com.local.ktv.LiveActivity { *; }
-keep class com.local.ktv.WebLoginActivity { *; }
-keep class com.local.ktv.OfflineVoice { *; }
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn org.vosk.**
-dontwarn com.sun.jna.**
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings

# IJK 播放内核（JNI 回调 + 反射调用 _setPropertyFloat，必须保留类名/方法名）
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep class tv.danmaku.ijk.media.player.misc.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn tv.danmaku.ijk.**
