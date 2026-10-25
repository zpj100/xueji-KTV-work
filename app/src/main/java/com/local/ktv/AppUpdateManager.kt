package com.local.ktv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新器：检查「个人公开仓库」(gitee / github) 的最新 Release，
 * 发现更高版本时下载并启动系统安装器。
 *
 * 发布约定：每个 Release 打标签（如 2.1.2），并附带一个 .apk。
 * 只保留最新版本的 APK（旧版本 Release 可由发布者手动清理）。
 */
object AppUpdateManager {
    /** 个人 gitee 仓库（owner/repo）。 */
    private const val GITEE_REPO = "zpj100/xueji-KTV-work"

    /** 个人 github 仓库（owner/repo）。 */
    private const val GITHUB_REPO = "zpj100/xueji-KTV-work"

    private const val GITEE_API =
        "https://gitee.com/api/v5/repos/$GITEE_REPO/releases/latest"

    private const val GITHUB_API =
        "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    data class Release(
        val version: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val source: String,
    )

    /**
     * 依次检查 gitee 与 github 个人仓库，返回较新的可用 Release（若都没有更高版本则返回 null）。
     */
    fun check(currentVersion: String, result: (Result<Release?>) -> Unit) {
        Thread({
            result(runCatching {
                val gitee = runCatching { fetchRelease(GITEE_API, "gitee") }.getOrNull()
                val github = runCatching { fetchRelease(GITHUB_API, "github") }.getOrNull()
                listOfNotNull(gitee, github)
                    .filter { compareVersions(it.version, currentVersion) > 0 }
                    .maxByOrNull { compareVersions(it.version, currentVersion) }
            })
        }, "xueji-update-check").start()
    }

    private fun fetchRelease(api: String, source: String): Release {
        val json = getText(api)
        val root = JSONObject(json)
        val version = root.optString("tag_name").trim().removePrefix("v")
        val assets = root.optJSONArray("assets")
        var apkUrl = ""
        var apkName = ""
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url").ifBlank {
                    asset.optString("url")
                }
                if (name.endsWith(".apk", ignoreCase = true) || url.contains(".apk", ignoreCase = true)) {
                    apkName = name.ifBlank { "xueji-ktv-$version.apk" }
                    apkUrl = url
                    break
                }
            }
        }
        // github 的 assets 用 "browser_download_url"；gitee 同名字段。
        if (apkUrl.isBlank()) {
            val browser = root.optString("browser_download_url")
            if (browser.contains(".apk", ignoreCase = true)) apkUrl = browser
        }
        require(version.isNotBlank() && apkUrl.isNotBlank()) { "未找到可用的更新包" }
        return Release(
            version = version,
            title = root.optString("name").ifBlank { "学记KTV $version" },
            notes = root.optString("body"),
            apkUrl = apkUrl,
            apkName = apkName,
            source = source,
        )
    }

    fun download(
        activity: Activity,
        release: Release,
        progress: (Int) -> Unit,
        result: (Result<File>) -> Unit,
    ) {
        Thread({
            result(runCatching {
                val directory = File(activity.externalCacheDir ?: activity.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "xueji-ktv-${release.version}.apk")
                val partial = File(directory, "${target.name}.download")
                partial.delete()
                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "XuejiKTV/${BuildConfig.VERSION_NAME}")
                }
                try {
                    require(connection.responseCode in 200..299) { "下载失败：HTTP ${connection.responseCode}" }
                    val total = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var downloaded = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (total > 0) progress((downloaded * 100 / total).toInt().coerceIn(0, 100))
                            }
                            output.fd.sync()
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                require(partial.length() > 1_000_000L) { "下载的安装包不完整" }
                partial.inputStream().use { input ->
                    require(input.read() == 'P'.code && input.read() == 'K'.code) { "下载内容不是 APK" }
                }
                val info = activity.packageManager.getPackageArchiveInfo(partial.absolutePath, 0)
                require(info?.packageName == activity.packageName) { "安装包应用标识不匹配" }
                target.delete()
                require(partial.renameTo(target)) { "无法保存安装包" }
                target
            })
        }, "xueji-update-download").start()
    }

    /**
     * 直接启动系统 Package Installer 安装 APK。
     */
    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        activity.startActivity(intent)
    }

    internal fun compareVersions(left: String, right: String): Int {
        fun parts(value: String) = value.removePrefix("v").split(Regex("[^0-9]+"))
            .filter(String::isNotEmpty).map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val compared = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
            if (compared != 0) return compared
        }
        return 0
    }

    private fun getText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "XuejiKTV/${BuildConfig.VERSION_NAME}")
        }
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "检查更新失败：HTTP ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
