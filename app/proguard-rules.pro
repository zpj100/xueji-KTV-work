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

# 保留注解、泛型签名与内部类信息（部分库在运行期读取，缺失可能导致 4.x 上的初始化异常）
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Android 4.x 多 dex 支持：MultiDex 通过 ClassLoader 反射加载 classes2.dex，保留其入口
-keep class androidx.multidex.** { *; }
-dontwarn androidx.multidex.**

# 4.4 兼容：这些类里引用了 API 21+ 的 android.net.* 类型。
# 必须保持独立类且禁止 R8 内联/合并，否则类型引用会被合并进 MainActivity，
# 在 Android 4.4 的 Dalvik 校验阶段触发 NoClassDefFoundError/VerifyError。
-keep class com.local.ktv.LanAddressResolver { *; }
-keep class com.local.ktv.LanAddressResolverApi21 { *; }
-keep class com.local.ktv.ViewOutlineCompat { *; }

# 4.4 兼容：KtvApplication 用反射跳过 AppCompat 的矢量图自检
# （minSdk<21 时 vector 被光栅化为 PNG，自检必然失败并抛 IllegalStateException）。
# 必须保留类名、get() 与 mHasCheckedVectorDrawableSetup 字段名。
-keep class androidx.appcompat.widget.ResourceManagerInternal { *; }
