package com.local.ktv

import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

/** 表单协议直连，不加载旧 JS、设备模拟或旧 CDN 地址。 */
object IgebaApiClient {
    const val CLOUD_URL = "http://app.ige8.net/cloud.php"
    const val ADDRESS_URL = "http://app.ige8.net/telnet.php"
    /** i歌霸服务端为 Indy(Delphi) HTTP 服务，缺少该 User-Agent 时会直接拒绝连接 / 返回 502，表现为「failed connect to app.ige8.net」。 */
    const val USER_AGENT = "Mozilla/3.0 (compatible; Indy Library)"

    /** 只读取用户自己正常会话提供的身份；缺省为官方匿名值。 */
    fun userId(): Long {
        return Random.nextLong(100_000L, 1_000_000L)
    }

    fun post(command: String, params: JSONObject, address: Boolean = false): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "目录及地址请求必须在后台线程执行" }
        val form = linkedMapOf("VER" to if (address) "10.0" else "6",
            "CMD" to command, "PARAMS" to params.toString())
        val body = form.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
        val connection = URL(if (address) ADDRESS_URL else CLOUD_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 40_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            IgebaAccount.attach(connection)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) throw httpError(connection, address)
            IgebaAccount.receive(connection)
            val result = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (result.has("Result") && result.optInt("Result") < 0) {
                throw IOException(result.optString("Description", "歌源拒绝请求"))
            }
            return result
        } finally { connection.disconnect() }
    }

    /**
     * 把非 2xx 响应转成可诊断的异常。
     * 账号服务对未登录/会话失效的地址请求返回 403，原来的“目录接口 HTTP 403”提示会误导用户。
     */
    private fun httpError(connection: HttpURLConnection, address: Boolean): IOException {
        val code = connection.responseCode
        val detail = runCatching {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.trim()?.take(120)
        }.getOrNull().orEmpty()
        val hint = when {
            code != 403 && code != 401 -> ""
            !address -> "：曲库接口已关闭或需要登录"
            IgebaAccount.session == null -> "：i歌霸账号未登录，请先在设置中登录账号"
            else -> "：账号会话已失效或该曲目无下载权限，请重新登录后再试"
        }
        return IOException((if (address) "歌曲地址接口" else "目录接口") + " HTTP $code" + hint +
            if (detail.isNotEmpty()) "（服务端：$detail）" else "")
    }

    data class MediaAddress(val songNumber: String, val url: String, val voiceChannel: Int,
        val rightsCode: Int, val reason: String)

    fun address(songNumber: String, accountUserId: Long = IgebaAccount.session?.userId ?: IgebaApiClient.userId()): MediaAddress {
        require(songNumber.matches(Regex("\\d{1,16}"))) { "缺少有效的新歌源歌曲编号" }
        val result = post("GET_SONGADDR", JSONObject()
            .put("UserId", accountUserId).put("SongNumber", songNumber), true)
        val rights = result.optJSONObject("Rights") ?: throw IOException("歌源未返回权限状态")
        val code = rights.optInt("Code", Int.MIN_VALUE)
        if (code != 0 && code != 1) throw IOException(rights.optString("Reason", "歌源未提供播放权限"))
        val url = result.optString("FileUrl")
        require(url.startsWith("http://") || url.startsWith("https://")) { "歌源未返回播放地址" }
        return MediaAddress(songNumber, url, result.optInt("VoiceChannel", 0),
            code, rights.optString("Reason"))
    }

    fun songs(page: Int, pageSize: Int, filter: JSONObject = JSONObject()): JSONObject {
        val params = JSONObject().put("UserId", 0).put("Filter", filter)
            .put("Page", JSONObject().put("PageNo", page).put("PageSize", pageSize))
        // 默认发布时间排序存在大量并列值，会在全库跨页同步时重复/漏项。
        if (filter.length() == 0 && pageSize == 1000) {
            params.put("Order", org.json.JSONArray().put(JSONObject().put("SongName", 0)))
        }
        return post("GET_SONGS", params)
    }

    fun packages(type: Int, page: Int, pageSize: Int): JSONObject =
        post("GET_PACGS", JSONObject().put("PackageType", type).put("Filter", "")
            .put("Page", JSONObject().put("PageNo", page).put("PageSize", pageSize)))
}
