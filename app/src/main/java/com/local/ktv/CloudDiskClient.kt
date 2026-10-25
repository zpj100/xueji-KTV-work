package com.local.ktv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

class CloudDiskClient(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud_disk", Context.MODE_PRIVATE)

    fun kv(key: String, fallback: String = ""): String = prefs.getString(key, fallback).orEmpty()

    fun setKv(key: String, value: String?) {
        prefs.edit().putString(key, value.orEmpty()).apply()
    }

    fun status(): JSONObject = JSONObject()
        .put("baidu", kv("cloud_baidu_token").isNotEmpty())
        .put("aliyun", kv("cloud_aliyun_token").isNotEmpty())
        .put("quark", kv("cloud_quark_share").isNotEmpty())
        .put("quark_cookie", kv("cloud_quark_cookie").isNotEmpty())
        .put("baidu_login", kv("cloud_baidu_cookie").isNotEmpty())
        .put("aliyun_login", kv("cloud_aliyun_refresh").isNotEmpty() || kv("cloud_aliyun_token").isNotEmpty())
        .put("quark_login", kv("cloud_quark_cookie").isNotEmpty())

    fun loginLabel(provider: String): String = when (provider) {
        "quark" -> if (kv("cloud_quark_cookie").isNotEmpty()) "已登录" else "未登录"
        "baidu" -> if (kv("cloud_baidu_cookie").isNotEmpty() || kv("cloud_baidu_token").isNotEmpty()) "已登录" else "未登录"
        "aliyun" -> if (kv("cloud_aliyun_token").isNotEmpty() || kv("cloud_aliyun_refresh").isNotEmpty()) "已登录" else "未登录"
        else -> "未登录"
    }

    fun applyLogin(raw: String): String {
        val obj = JSONObject(raw)
        val provider = obj.optString("provider")
        val cookie = obj.optJSONObject("cookie")
        val storage = obj.optJSONObject("storage")
        return when (provider) {
            "quark" -> {
                val ck = joinCookies(cookie)
                if (ck.isEmpty()) return "未抓到夸克登录信息，请登录后再点完成"
                if (!ck.contains("__pus") && !ck.contains("b-user-id")) {
                    return "还没登录成功：请先在页面里登录夸克，看到文件列表后再点完成登录"
                }
                setKv("cloud_quark_cookie", ck)
                "夸克已登录"
            }
            "baidu" -> {
                val ck = joinCookies(cookie)
                if (ck.isEmpty()) return "未抓到百度登录信息，请登录后再点完成"
                if (!ck.contains("BDUSS")) {
                    return "还没登录成功：请先在页面里登录百度，看到网盘文件列表后再点完成登录"
                }
                setKv("cloud_baidu_cookie", ck)
                "百度已登录"
            }
            "aliyun" -> {
                var access = ""
                var refresh = ""
                if (storage != null) {
                    val keys = storage.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = storage.optString(k, "")
                        val parsed = tryJson(v)
                        if (parsed is JSONObject) {
                            if (access.isEmpty()) access = parsed.optString("access_token", "")
                            if (refresh.isEmpty()) refresh = parsed.optString("refresh_token", "")
                        } else if (v.length > 20) {
                            val low = k.lowercase(Locale.ROOT)
                            if (low.contains("refresh") && refresh.isEmpty()) refresh = v
                            else if (low.contains("access") && access.isEmpty()) access = v
                        }
                    }
                }
                if (access.isEmpty() && refresh.isEmpty()) return "未抓到阿里云盘令牌，请登录后再点完成"
                if (access.isNotEmpty()) setKv("cloud_aliyun_token", access)
                if (refresh.isNotEmpty()) setKv("cloud_aliyun_refresh", refresh)
                setKv("cloud_aliyun_drive", "")
                "阿里云盘已登录"
            }
            else -> "已获取登录信息"
        }
    }

    fun saveQuarkShare(raw: String): JSONObject {
        val parsed = parseQuarkShare(raw)
        setKv("cloud_quark_share", parsed.optString("url"))
        setKv("cloud_quark_pwd_id", parsed.optString("pwd_id"))
        setKv("cloud_quark_passcode", parsed.optString("passcode"))
        setKv("cloud_quark_pdir", parsed.optString("pdir", "0"))
        setKv("cloud_quark_stoken", "")
        return parsed
    }

    fun list(provider: String, folder: String?): JSONArray = when (provider) {
        "baidu" -> baiduList(folder)
        "aliyun" -> aliyunList(folder)
        "quark" -> quarkList(folder)
        else -> throw RuntimeException("未知网盘")
    }

    fun download(provider: String, id: String, name: String): File = when (provider) {
        "baidu" -> baiduDownload(id, name)
        "aliyun" -> aliyunDownload(id, name)
        "quark" -> quarkDownload(id, name)
        else -> throw RuntimeException("未知网盘")
    }

    private fun aliyunList(folder: String?): JSONArray {
        val token = aliyunToken()
        if (token.isEmpty()) throw RuntimeException("尚未登录阿里云盘，请在设置里登录")
        var drive = kv("cloud_aliyun_drive")
        if (drive.isEmpty()) {
            val info = aliyunPost("https://openapi.alipan.com/adrive/v1.0/user/getDriveInfo", "{}", token)
            drive = info.optString("default_drive_id").ifEmpty { info.optString("resource_drive_id") }
            if (drive.isEmpty()) throw RuntimeException("阿里云盘授权失败")
            setKv("cloud_aliyun_drive", drive)
        }
        val parent = if (folder.isNullOrEmpty()) "root" else folder
        val req = JSONObject().put("drive_id", drive).put("parent_file_id", parent).put("limit", 100)
        val data = aliyunPost("https://openapi.alipan.com/adrive/v1.0/openFile/list", req.toString(), token)
        val items = data.optJSONArray("items") ?: return JSONArray()
        val rows = JSONArray()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val isdir = it.optString("type") == "folder"
            val name = it.optString("name")
            if (!isdir && !isMediaName(name)) continue
            rows.put(
                JSONObject()
                    .put("id", it.optString("file_id"))
                    .put("name", name)
                    .put("open", it.optString("file_id"))
                    .put("isdir", isdir)
                    .put("provider", "aliyun"),
            )
        }
        return rows
    }

    private fun baiduList(folder: String?): JSONArray {
        val cookie = kv("cloud_baidu_cookie")
        val token = kv("cloud_baidu_token")
        val dir = if (folder.isNullOrEmpty()) "/" else folder
        val data = when {
            cookie.isNotEmpty() -> {
                val url = "https://pan.baidu.com/api/list?web=1&app_id=250528&channel=chunlei&clienttype=0" +
                    "&order=name&desc=0&showempty=0&page=1&num=1000&dir=" + enc(dir)
                try {
                    httpGetJsonCookie(url, "pan.baidu.com", cookie, "https://pan.baidu.com/disk/home")
                } catch (e: RuntimeException) {
                    if (e.message?.contains("登录已失效") != true) throw e
                    setKv("cloud_baidu_cookie", "")
                    throw RuntimeException("百度网盘登录已失效，请在设置里重新登录")
                }
            }
            token.isNotEmpty() -> {
                val url = "https://pan.baidu.com/rest/2.0/xpan/file?method=list&access_token=" +
                    enc(token) + "&dir=" + enc(dir) + "&limit=200"
                httpGetJson(url, "pan.baidu.com")
            }
            else -> throw RuntimeException("尚未登录百度网盘，请在设置里登录")
        }
        if (data.optInt("errno", 0) != 0) throw RuntimeException(data.optString("errmsg", "百度网盘目录不可用"))
        val list = data.optJSONArray("list") ?: return JSONArray()
        val rows = JSONArray()
        for (i in 0 until list.length()) {
            val it = list.getJSONObject(i)
            val isdir = it.optInt("isdir") == 1
            var name = it.optString("server_filename")
            if (name.isEmpty()) name = it.optString("filename")
            if (!isdir && !isMediaName(name)) continue
            rows.put(
                JSONObject()
                    .put("id", it.optLong("fs_id").toString())
                    .put("name", name)
                    .put("path", it.optString("path"))
                    .put("open", it.optString("path"))
                    .put("isdir", isdir)
                    .put("provider", "baidu"),
            )
        }
        return rows
    }

    private fun baiduDownload(id: String, name: String): File {
        val token = kv("cloud_baidu_token")
        if (token.isNotEmpty()) {
            val url = "https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas&access_token=" +
                enc(token) + "&fsids=[$id]&dlink=1"
            val data = httpGetJson(url, "pan.baidu.com")
            val list = data.optJSONArray("list") ?: throw RuntimeException("该文件没有下载地址")
            if (list.length() == 0) throw RuntimeException("该文件没有下载地址")
            var dlink = list.getJSONObject(0).optString("dlink")
            if (!dlink.contains("access_token=")) dlink += (if (dlink.contains('?')) "&" else "?") + "access_token=$token"
            val filename = name.ifEmpty { list.getJSONObject(0).optString("filename", "baidu.bin") }
            return downloadTo(dlink, filename, "pan.baidu.com")
        }
        val cookie = kv("cloud_baidu_cookie")
        if (cookie.isEmpty()) throw RuntimeException("尚未登录百度网盘，请在设置里登录")
        val url = "https://pan.baidu.com/api/download?channel=chunlei&web=1&app_id=250528" +
            "&type=dlink&fidlist=" + enc("[$id]")
        val data = try {
            httpGetJsonCookie(url, "pan.baidu.com", cookie, "https://pan.baidu.com/disk/home")
        } catch (e: RuntimeException) {
            if (e.message?.contains("登录已失效") == true) {
                setKv("cloud_baidu_cookie", "")
                throw RuntimeException("百度网盘登录已失效，请在设置里重新登录")
            }
            throw e
        }
        if (data.optInt("errno", 0) != 0) throw RuntimeException("百度网页接口未能取到下载地址，请在电脑端下载该文件")
        val dl = data.optJSONArray("dlink")
        var dlink = if (dl != null && dl.length() > 0) dl.optString(0, "") else ""
        if (dlink.isEmpty()) dlink = data.optString("dlink")
        if (dlink.isEmpty()) throw RuntimeException("百度网页接口未返回下载地址，请在电脑端下载该文件")
        return downloadToCookie(dlink, name.ifEmpty { "baidu-$id" }, "pan.baidu.com", cookie)
    }

    private fun aliyunDownload(id: String, name: String): File {
        val token = aliyunToken()
        if (token.isEmpty()) throw RuntimeException("尚未登录阿里云盘，请在设置里登录")
        val drive = kv("cloud_aliyun_drive")
        val req = JSONObject().put("drive_id", drive).put("file_id", id).put("expire_sec", 900)
        val data = aliyunPost("https://openapi.alipan.com/adrive/v1.0/openFile/getDownloadUrl", req.toString(), token)
        val url = data.optString("url").ifEmpty { data.optString("download_url") }
        if (url.isEmpty()) throw RuntimeException("阿里云盘无法获取下载地址")
        return downloadTo(url, name.ifEmpty { "aliyun-$id" }, "MaidongKTV/2.1")
    }

    private fun quarkDownload(id: String, name: String): File {
        val cookie = kv("cloud_quark_cookie")
        if (cookie.isEmpty()) throw RuntimeException("下载夸克分享请先登录夸克网盘")
        val sess = quarkSession()
        var fid = id
        var shareTok = ""
        val bar = fid.indexOf('|')
        if (bar >= 0) {
            shareTok = fid.substring(bar + 1)
            fid = fid.substring(0, bar)
        }
        val req = JSONObject()
        req.put("fids", JSONArray().put(fid))
        req.put("pwd_id", sess[0])
        req.put("stoken", sess[1])
        if (shareTok.isNotEmpty()) {
            req.put("fid_token_list", JSONArray().put(shareTok))
            req.put("share_fid_token", shareTok)
        }
        val data = quarkPost("https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc", req.toString())
        val list = data.optJSONArray("data")
        val info = if (list != null && list.length() > 0) list.optJSONObject(0) ?: JSONObject() else JSONObject()
        val url = info.optString("download_url").ifEmpty { info.optString("md5_download_url") }
        if (url.isEmpty()) throw RuntimeException(data.optString("message", "夸克无法获取下载地址，请检查 Cookie 是否有效"))
        val filename = name.ifEmpty { info.optString("file_name", "quark.bin") }
        return downloadToCookie(url, filename, QUARK_UA, cookie)
    }

    private fun aliyunToken(): String {
        val token = kv("cloud_aliyun_token")
        if (token.isNotEmpty()) return token
        val refresh = kv("cloud_aliyun_refresh")
        if (refresh.isEmpty()) return ""
        val req = JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh)
        val data = httpPostJson("https://auth.aliyundrive.com/v2/account/token", req.toString(), null)
        val access = data.optString("access_token")
        val newRefresh = data.optString("refresh_token")
        if (access.isEmpty()) return ""
        setKv("cloud_aliyun_token", access)
        if (newRefresh.isNotEmpty()) setKv("cloud_aliyun_refresh", newRefresh)
        setKv("cloud_aliyun_drive", "")
        return access
    }

    private fun clearAliyun() {
        setKv("cloud_aliyun_token", "")
        setKv("cloud_aliyun_refresh", "")
        setKv("cloud_aliyun_drive", "")
    }

    private fun aliyunPost(url: String, body: String, token: String): JSONObject {
        val data = try {
            httpPostJson(url, body, token)
        } catch (e: RuntimeException) {
            val m = e.message
            if (m?.contains("登录页") == true) {
                clearAliyun()
                throw RuntimeException("阿里云盘登录已失效，请在设置里重新登录")
            }
            throw RuntimeException(m ?: "阿里云盘网络异常，请稍后重试")
        }
        val code = data.optString("code", "")
        if (code.isNotEmpty()) {
            val low = code.lowercase(Locale.ROOT)
            val bad = listOf("token", "login", "auth", "forbidden", "expire").any { low.contains(it) }
            if (bad) {
                clearAliyun()
                throw RuntimeException("阿里云盘登录已失效，请在设置里重新登录")
            }
            if (code != "200" && low != "success") {
                throw RuntimeException(data.optString("message", "阿里云盘接口错误"))
            }
        }
        return data
    }

    private fun parseQuarkShare(raw: String): JSONObject {
        val text = raw.trim()
        if (text.isEmpty()) throw RuntimeException("请粘贴夸克分享链接")
        var pwd = ""
        val pm = RE_QUARK_PWD.matcher(text)
        if (pm.find()) pwd = pm.group(1)
        val sm = RE_QUARK_SHARE.matcher(text)
        if (!sm.find()) throw RuntimeException("不是夸克分享链接")
        val pwdId = sm.group(1)
        val url = "https://pan.quark.cn/s/$pwdId"
        var pdir = "0"
        val hashAt = text.indexOf('#')
        if (hashAt >= 0) {
            val dm = RE_QUARK_PDIR.matcher(text.substring(hashAt + 1))
            if (dm.find()) pdir = dm.group(1)
        }
        val qAt = text.indexOf('?')
        val hashCut = if (hashAt >= 0) hashAt else text.length
        if (qAt in 0 until hashCut) {
            text.substring(qAt + 1, hashCut).split('&').forEach { part ->
                val i = part.indexOf('=')
                if (i > 0 && part.substring(0, i) == "pwd") pwd = part.substring(i + 1)
            }
        }
        return JSONObject().put("url", url).put("pwd_id", pwdId).put("pdir", pdir).put("passcode", pwd)
    }

    private fun quarkSession(): Array<String> {
        var pwdId = kv("cloud_quark_pwd_id")
        val share = kv("cloud_quark_share")
        if (pwdId.isEmpty()) {
            if (share.isEmpty()) throw RuntimeException("请先粘贴夸克分享链接")
            val parsed = parseQuarkShare(share)
            pwdId = parsed.optString("pwd_id")
            setKv("cloud_quark_pwd_id", pwdId)
            if (parsed.optString("passcode").isNotEmpty()) setKv("cloud_quark_passcode", parsed.optString("passcode"))
        }
        val stoken = kv("cloud_quark_stoken")
        if (stoken.isNotEmpty()) return arrayOf(pwdId, stoken)
        val req = JSONObject().put("pwd_id", pwdId).put("passcode", kv("cloud_quark_passcode"))
        val data = quarkPost("$QUARK_HOST/share/sharepage/token?pr=ucpro&fr=pc", req.toString())
        val inner = data.optJSONObject("data")
        val next = inner?.optString("stoken").orEmpty()
        if (next.isEmpty()) throw RuntimeException(data.optString("message", "夸克分享不可用"))
        setKv("cloud_quark_stoken", next)
        return arrayOf(pwdId, next)
    }

    private fun quarkList(folder: String?): JSONArray {
        val sess = quarkSession()
        var parent = folder.orEmpty().trim()
        if (parent.isEmpty() || parent == "/" || parent == "root") {
            parent = kv("cloud_quark_pdir").ifEmpty { "0" }
        }
        val url = "$QUARK_HOST/share/sharepage/detail?pr=ucpro&fr=pc" +
            "&pwd_id=" + enc(sess[0]) +
            "&stoken=" + enc(sess[1]) +
            "&pdir_fid=" + enc(parent) +
            "&force=0&_page=1&_size=100&_fetch_total=1&_sort=" + enc("file_type:asc,file_name:asc")
        val data = quarkGet(url)
        if (data.optInt("status", 0) != 200 && data.optInt("code", -1) != 0) {
            setKv("cloud_quark_stoken", "")
            throw RuntimeException(data.optString("message", "夸克分享目录不可用"))
        }
        val inner = data.optJSONObject("data")
        val list = inner?.optJSONArray("list") ?: return JSONArray()
        val rows = JSONArray()
        for (i in 0 until list.length()) {
            val it = list.getJSONObject(i)
            val isdir = it.optBoolean("dir") || it.optInt("dir") == 1 || it.optInt("file_type", 1) == 0
            val name = it.optString("file_name")
            if (!isdir && !isMediaName(name)) continue
            val fid = it.optString("fid")
            val token = it.optString("share_fid_token")
            val ident = if (token.isEmpty()) fid else "$fid|$token"
            rows.put(
                JSONObject()
                    .put("id", ident)
                    .put("name", name)
                    .put("path", parent)
                    .put("open", fid)
                    .put("isdir", isdir)
                    .put("size", it.optLong("size"))
                    .put("provider", "quark"),
            )
        }
        return rows
    }

    private fun quarkGet(url: String): JSONObject = try {
        httpGetJsonHeaders(url, QUARK_UA, quarkExtraHeaders(true))
    } catch (e: RuntimeException) {
        if (e.message?.contains("登录已失效") != true) throw e
        val retry = httpGetJsonHeaders(url, QUARK_UA, quarkExtraHeaders(false))
        setKv("cloud_quark_cookie", "")
        retry
    }

    private fun quarkPost(url: String, body: String): JSONObject = try {
        quarkPostOnce(url, body, true)
    } catch (e: RuntimeException) {
        if (e.message?.contains("登录已失效") != true) throw e
        val retry = quarkPostOnce(url, body, false)
        setKv("cloud_quark_cookie", "")
        retry
    }

    private fun quarkPostOnce(url: String, body: String, withCookie: Boolean): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", QUARK_UA)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Referer", "https://pan.quark.cn/")
            setRequestProperty("Origin", "https://pan.quark.cn")
            val cookie = if (withCookie) kv("cloud_quark_cookie") else ""
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
        }
        return try {
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                ?: throw RuntimeException("夸克接口无响应")
            parseJsonBody(stream.use { it.readBytes().toString(Charsets.UTF_8) }, "夸克")
        } finally {
            conn.disconnect()
        }
    }

    private fun quarkExtraHeaders(withCookie: Boolean): Map<String, String> {
        val extra = HashMap<String, String>()
        extra["Accept"] = "application/json, text/plain, */*"
        extra["Referer"] = "https://pan.quark.cn/"
        extra["Origin"] = "https://pan.quark.cn"
        val cookie = if (withCookie) kv("cloud_quark_cookie") else ""
        if (cookie.isNotEmpty()) extra["Cookie"] = cookie
        return extra
    }

    private fun downloadTo(url: String, filename: String, ua: String): File {
        val dest = File(AppPaths.cloudSongsDir.also { it.mkdirs() }, safeName(filename))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 120000
            setRequestProperty("User-Agent", ua)
        }
        return try {
            BufferedInputStream(conn.inputStream).use { inn ->
                FileOutputStream(dest).use { fos ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = inn.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                }
            }
            dest
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadToCookie(url: String, filename: String, ua: String, cookie: String?): File {
        val dest = File(AppPaths.cloudSongsDir.also { it.mkdirs() }, safeName(filename))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 120000
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Referer", "https://pan.quark.cn/")
            if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
        }
        return try {
            BufferedInputStream(conn.inputStream).use { inn ->
                FileOutputStream(dest).use { fos ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = inn.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                }
            }
            dest
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val QUARK_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val QUARK_HOST = "https://drive-pc.quark.cn/1/clouddrive"
        private val RE_QUARK_SHARE = Pattern.compile("https?://pan\\.quark\\.cn/s/([A-Za-z0-9]+)")
        private val RE_QUARK_PDIR = Pattern.compile("/list/share/([A-Za-z0-9]+)")
        private val RE_QUARK_PWD = Pattern.compile("(?:提取码|密码|pwd)[:：=\\s]*([a-zA-Z0-9]{3,8})", Pattern.CASE_INSENSITIVE)

        fun loginIntentExtras(provider: String): Triple<String, String, Pair<String, String>>? = when (provider) {
            "quark" -> Triple(
                "登录夸克网盘",
                "https://pan.quark.cn/",
                "[\"pan.quark.cn\",\"drive-pc.quark.cn\",\"drive.quark.cn\",\"passport.quark.cn\"]" to
                    "[\"token\",\"__token\",\"access_token\",\"refresh_token\",\"user\"]",
            )
            "baidu" -> Triple(
                "登录百度网盘",
                "https://pan.baidu.com/disk/main",
                "[\"pan.baidu.com\",\"pcs.baidu.com\",\"d.pcs.baidu.com\",\"baidupcs.com\",\"passport.baidu.com\"]" to
                    "[\"BDUSS\",\"STOKEN\",\"PTOKEN\"]",
            )
            "aliyun" -> Triple(
                "登录阿里云盘",
                "https://www.alipan.com/drive",
                "[\"www.alipan.com\",\"alipan.com\",\"api.alipan.com\",\"passport.alipan.com\",\"www.aliyundrive.com\",\"api.aliyundrive.com\",\"auth.aliyundrive.com\",\"passport.aliyundrive.com\"]" to
                    "[\"token\",\"aDriveToken\",\"access_token\",\"refresh_token\",\"user\"]",
            )
            else -> null
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun isMediaName(name: String): Boolean {
            val n = name.lowercase(Locale.ROOT)
            return OpenListPaths.media(n) || n.endsWith(".rmvb") || n.endsWith(".rm") ||
                n.endsWith(".flv") || n.endsWith(".3gp") || n.endsWith(".mts") || n.endsWith(".m4v")
        }

        private fun safeName(name: String): String =
            File(name).name.replace(Regex("[^\\w\\u4e00-\\u9fff.\\-]+"), "_")

        private fun joinCookies(cookie: JSONObject?): String {
            if (cookie == null) return ""
            val sb = StringBuilder()
            val keys = cookie.keys()
            while (keys.hasNext()) {
                val v = cookie.optString(keys.next(), "")
                if (v.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append(v)
            }
            return sb.toString()
        }

        private fun tryJson(v: String?): Any? {
            if (v.isNullOrEmpty() || v.length < 2) return null
            return try { JSONTokener(v).nextValue() } catch (_: Throwable) { null }
        }

        private fun looksLikeHtml(s: String?): Boolean {
            if (s.isNullOrBlank()) return true
            val t = s.trim()
            if (t.isEmpty() || (t[0] != '{' && t[0] != '[')) return true
            val low = t.take(200).lowercase(Locale.ROOT)
            return low.contains("<!doctype") || low.contains("<html")
        }

        private fun parseJsonBody(s: String, label: String): JSONObject {
            if (looksLikeHtml(s)) throw RuntimeException(label + "返回了登录页，登录已失效，请重新登录")
            return try {
                JSONObject(s)
            } catch (_: Throwable) {
                throw RuntimeException(label + "接口返回异常，请稍后重试")
            }
        }

        private fun httpPostJson(url: String, body: String, token: String?): JSONObject {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "MaidongKTV/2.1")
                if (!token.isNullOrEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
            return try {
                conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
                val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                    ?: throw RuntimeException("网盘接口无响应")
                parseJsonBody(stream.use { it.readBytes().toString(Charsets.UTF_8) }, "网盘")
            } finally {
                conn.disconnect()
            }
        }

        private fun httpGetJson(url: String, ua: String): JSONObject = httpGetJsonCookie(url, ua, null, null)

        private fun httpGetJsonCookie(url: String, ua: String, cookie: String?, referer: String?): JSONObject {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", ua)
                if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
                if (!referer.isNullOrEmpty()) setRequestProperty("Referer", referer)
            }
            return try {
                val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                    ?: throw RuntimeException("网盘接口无响应")
                parseJsonBody(stream.use { it.readBytes().toString(Charsets.UTF_8) }, "网盘")
            } finally {
                conn.disconnect()
            }
        }

        private fun httpGetJsonHeaders(url: String, ua: String, extra: Map<String, String>?): JSONObject {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", ua)
                extra?.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            return try {
                val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
                    ?: throw RuntimeException("接口无响应")
                parseJsonBody(stream.use { it.readBytes().toString(Charsets.UTF_8) }, "夸克")
            } finally {
                conn.disconnect()
            }
        }
    }
}
