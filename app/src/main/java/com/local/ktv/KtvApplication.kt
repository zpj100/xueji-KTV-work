package com.local.ktv

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KtvApplication : Application() {
    private var defaultCrashHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Android 4.x（API < 21）需要手动安装 multidex：
     * 启用 core library desugaring 后脱糖库会落在 classes2.dex，未安装会导致启动时
     * NoClassDefFoundError。API 21+ 由系统原生支持，install 为空操作。
     */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        runCatching { androidx.multidex.MultiDex.install(this) }
    }

    override fun onCreate() {
        super.onCreate()
        skipAppCompatVectorCheck()
        installCrashLogger()
        instanceRef = this
        // 4.x 上各项初始化若失败，不应阻塞启动（崩溃日志已落盘，便于排查）。
        runCatching { PersonalMediaStore.init(this) }
            .onFailure { Log.w(TAG, "个人媒体存储初始化失败，已跳过", it) }
        runCatching { CatalogAssets.init(this) }
            .onFailure { Log.w(TAG, "目录资源初始化失败，已跳过", it) }
        runCatching { IgebaAccount.initialize(this) }
            .onFailure { Log.w(TAG, "i歌霸账号存储初始化失败，已跳过", it) }
        Log.i(TAG, "KTV application initialized (sdk=${Build.VERSION.SDK_INT})")
    }

    /**
     * 跳过 AppCompat 的矢量图配置自检（仅 4.x）。
     *
     * 本项目 minSdk 14，AGP 会把 res/drawable 下的 vector 光栅化成各密度 PNG 供 4.x 使用，
     * 因此 AppCompat 拿 abc_vector_test 自检时得到的是 BitmapDrawable 而不是
     * VectorDrawableCompat，启动阶段会抛
     * "This app has been built with an incorrect configuration..." 导致闪退。
     * 光栅化 PNG 在 4.x 上绘制完全正常，这里直接标记自检已通过即可。
     */
    private fun skipAppCompatVectorCheck() {
        if (Build.VERSION.SDK_INT >= 21) return
        runCatching {
            val cls = Class.forName("androidx.appcompat.widget.ResourceManagerInternal")
            val instance = cls.getDeclaredMethod("get").invoke(null) ?: return@runCatching
            cls.getDeclaredField("mHasCheckedVectorDrawableSetup").apply {
                isAccessible = true
                setBoolean(instance, true)
            }
        }.onFailure { Log.w(TAG, "跳过 AppCompat 矢量图自检失败", it) }
    }

    /** 崩溃日志落盘，便于 4.x 无 logcat 时排查。 */
    private fun installCrashLogger() {
        defaultCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
                File(dir, "crash-last.log").writeText(
                    "time=$stamp\nsdk=${Build.VERSION.SDK_INT}\nmodel=${Build.MODEL}\n" +
                        Log.getStackTraceString(error),
                )
            }
            defaultCrashHandler?.uncaughtException(thread, error)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        private const val TAG = "KtvApplication"

        lateinit var instanceRef: KtvApplication
            private set

        @JvmStatic
        fun getInstance(): KtvApplication = instanceRef
    }
}
