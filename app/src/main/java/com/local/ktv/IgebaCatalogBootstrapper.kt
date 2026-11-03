package com.local.ktv

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * 曲库更新：跟随上游项目（源项目 / 更新项目）的内置离线曲库。
 *
 * 上游：https://gitee.com/yangyachao-X/maidong-ktv （分支 feature/igeba-media）
 * 背景：i歌霸在线曲库接口（cloud.php 的 GET_SONGS）已被服务端关闭，返回「请使用鸿蒙客户端」，
 *      因此改为比对上游仓库中 igeba_catalog.db 的 Git blob SHA-1，有变化才下载整份数据库。
 * 注意：Gitee raw 对 20MB 以上的文件要求登录，故下载优先走 raw、失败回退到本仓库 Release 附件。
 */
object IgebaCatalogBootstrapper {
    /** 上游项目（源项目 / 更新项目） */
    const val UPSTREAM_OWNER = "yangyachao-X"
    const val UPSTREAM_REPO = "maidong-ktv"
    const val UPSTREAM_BRANCH = "feature/igeba-media"
    private const val DB_REPO_PATH = "app/src/main/assets/igeba_catalog.db"

    /** 备用下载源：本仓库 Release 附件（匿名可下载）。 */
    private const val FALLBACK_URL =
        "https://gitee.com/zpj100/xueji-KTV-work/releases/download/catalog-latest/igeba_catalog.db"

    private val chars by lazy { JSONObject(CatalogAssets.pinyinChars()) }

    private fun upstreamApiUrl(): String =
        "https://gitee.com/api/v5/repos/$UPSTREAM_OWNER/$UPSTREAM_REPO/contents/$DB_REPO_PATH?ref=$UPSTREAM_BRANCH"

    private fun upstreamRawUrl(): String =
        "https://gitee.com/$UPSTREAM_OWNER/$UPSTREAM_REPO/raw/$UPSTREAM_BRANCH/$DB_REPO_PATH"

    private fun versionFile(): File = File(AppPaths.databaseFile.parentFile, "igeba_catalog.sha")

    /** 远程版本 = 上游 igeba_catalog.db 的 Git blob SHA-1。 */
    fun fetchRemoteVersion(): String? = runCatching {
        val connection = URL(upstreamApiUrl()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) XuejiKTV")
            if (connection.responseCode !in 200..299) return@runCatching null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body).optString("sha").takeIf { it.length == 40 }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** 本地版本：优先读上次记录的 SHA，否则现场计算当前曲库的 Git blob SHA-1。 */
    fun getLocalDbVersion(): String? {
        CatalogAssets.install()
        versionFile().takeIf { it.isFile }
            ?.readText()?.trim()?.takeIf { it.length == 40 }
            ?.let { return it }
        val sha = gitBlobSha(AppPaths.databaseFile) ?: return null
        runCatching { versionFile().writeText(sha) }
        return sha
    }

    /** 下载并替换曲库；校验 SHA 与上游一致后才安装，失败时保留原曲库。 */
    fun download(onProgress: (Int) -> Unit): Result<File> = runCatching {
        val version = fetchRemoteVersion() ?: error("无法连接上游曲库（Gitee），请检查网络")
        val target = AppPaths.databaseFile
        val temporary = File(target.parentFile, "igeba_catalog.sync")
        temporary.delete()
        var lastError: Throwable? = null
        for (url in listOf(upstreamRawUrl(), FALLBACK_URL)) {
            try {
                downloadTo(url, temporary, onProgress)
                check(gitBlobSha(temporary) == version) { "曲库校验失败（与上游版本不一致）" }
                val backup = File(target.parentFile, "igeba_catalog.previous")
                backup.delete()
                if (target.isFile && !target.renameTo(backup)) error("无法暂存上一份曲库")
                if (!temporary.renameTo(target)) {
                    backup.renameTo(target)
                    error("无法安装新曲库")
                }
                backup.delete()
                runCatching { versionFile().writeText(version) }
                onProgress(100)
                return@runCatching target
            } catch (error: Throwable) {
                lastError = error
                temporary.delete()
            }
        }
        throw lastError ?: IOException("所有曲库下载通道均不可用")
    }

    private fun downloadTo(url: String, target: File, onProgress: (Int) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) XuejiKTV")
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLength.toLong()
            var read = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        out.write(buffer, 0, count)
                        read += count
                        if (total > 0) onProgress((read * 92 / total).toInt().coerceIn(0, 92))
                    }
                }
            }
            check(target.length() > 0) { "下载内容为空" }
        } finally {
            connection.disconnect()
        }
    }

    /** 计算 Git blob SHA-1，与 Gitee/GitHub contents API 返回的 sha 算法一致。 */
    private fun gitBlobSha(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrNull()

    internal fun fullPinyin(name: String): String =
        name.map { chars.optString(it.toString(), it.toString()) }.joinToString("").uppercase(Locale.ROOT)

    internal fun initials(name: String): String =
        name.map { chars.optString(it.toString(), it.toString()).take(1) }.joinToString("").uppercase(Locale.ROOT)
}
