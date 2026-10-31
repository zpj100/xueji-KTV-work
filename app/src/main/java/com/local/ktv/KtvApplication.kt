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

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        instanceRef = this
        PersonalMediaStore.init(this)
        CatalogAssets.init(this)
        // 4.x 上若账号存储/加密不可用，不应阻塞启动。
        runCatching { IgebaAccount.initialize(this) }.onFailure {
            Log.w(TAG, "i歌霸账号存储初始化失败，已跳过", it)
        }
        Log.i(TAG, "KTV application initialized without native services")
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
