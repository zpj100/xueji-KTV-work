package com.local.ktv

import android.os.Environment
import android.util.Log
import java.io.File

/** The only external-storage namespace used by the fresh Maidong KTV app. */
object AppPaths {
    /**
     * 外置存储根目录。Android 4.4 盒子并不统一：可能是 /storage/emulated/0，
     * 也可能是 /mnt/sdcard、/storage/sdcard0、/storage/sdcard（写死会导致启动时
     * 创建目录失败从而闪退）。这里按“可写”优先依次探测。
     */
    private val externalRoot: File by lazy {
        val candidates = mutableListOf<File>()
        Environment.getExternalStorageDirectory()?.let { candidates += it }
        candidates += File("/storage/emulated/0")
        candidates += File("/mnt/sdcard")
        candidates += File("/storage/sdcard0")
        candidates += File("/storage/sdcard")
        candidates += File("/sdcard")
        candidates.firstOrNull { it.isDirectory && it.canWrite() }
            ?: candidates.firstOrNull { it.isDirectory }
            ?: File("/sdcard")
    }

    val ROOT_PATH: String by lazy { File(externalRoot, "MaidongKTV").absolutePath }
    val VIDEO_PATH: String by lazy { "$ROOT_PATH/video" }

    val root = File(ROOT_PATH)
    val stateFile = File(root, "state.json")
    val songsDir = File(root, "songs")
    val importDir = File(root, "import")
    val catalogFile = File(root, "catalog.json")
    val recordsDir = File(root, "records")
    val moviesDir = File(root, "movies")
    val appsDir = File(root, "apps")
    val backupDir = File(root, "backup")
    val databaseDir = File(root, "database")
    val databaseFile = File(databaseDir, "igeba_catalog.db")
    val singerPortraitFile = File(databaseDir, "singer_portraits.db")
    val igebaSessionFile = File(root, "igeba_session.json")
    val videoDir = File(VIDEO_PATH)
    val cloudSongsDir = File(videoDir, "cloud-song")

    /**
     * 创建应用所需目录。
     * 外置存储只读/未挂载（部分 4.4 盒子挂载较慢）时只记录日志，不能因此让启动闪退。
     */
    fun ensureDirectories() {
        listOf(root, songsDir, importDir, recordsDir, moviesDir, appsDir, backupDir,
            databaseDir, videoDir, cloudSongsDir).forEach {
            if (!it.exists() && !it.mkdirs() && !it.exists()) {
                Log.w(TAG, "无法创建目录 ${it.absolutePath}（存储只读或未挂载）")
            }
        }
    }

    private const val TAG = "AppPaths"

}
