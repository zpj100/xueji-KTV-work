package com.local.ktv

import java.io.File

/** A provider switch clears old downloads once, before copying the APK's new catalog. */
object LegacyStorageUpgrade {
    private fun markerFile(): File = File(AppPaths.root, ".igeba-cache-migration-v1")

    @Synchronized
    internal fun clearOldDownloads(state: KtvStateDatabase): OwnedSongCacheCleaner.Result? {
        if (AtomicFileCompat.readText(markerFile())?.trim() == "1") return null
        val result = OwnedSongCacheCleaner.clear(AppPaths.cloudSongsDir)
        state.clearDownloads()
        runCatching { AtomicFileCompat.writeText(markerFile(), "1\n") }
        return result
    }
}
