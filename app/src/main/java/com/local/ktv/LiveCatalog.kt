package com.local.ktv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern

object LiveCatalog {
    private const val UA = "Mozilla/5.0 (Linux; Android 4.4) MaidongKTV/2.1.1"
    private const val KEEP = 3
    private val ATTR = Pattern.compile("([A-Za-z0-9\\-]+)=\"([^\"]*)\"")
    private val STRIP = Pattern.compile("（[^）]*）|\\([^)]*\\)|【[^】]*】|\\[[^]]*\\]")
    private val QUAL = Pattern.compile("1080p|720p|480p|4k|8k|fhd|uhd|hdr|超高清|超清|高清|蓝光|标清", Pattern.CASE_INSENSITIVE)
    private val KEEP_CHARS = Pattern.compile("[^a-z0-9\\u4e00-\\u9fa5]+")
    private val TAIWAN = Pattern.compile("[{(]台[)}]|·台|台·")
    private val SOURCES = arrayOf(
        arrayOf("live-snap", "预过滤快照·即开", "asset://live/live_filtered.m3u"),
        arrayOf("live-fm6", "央视卫视·范明明", "https://live.fanmingming.com/tv/m3u/ipv6.m3u"),
        arrayOf("live-zbefine", "开源·zbefine", "https://cdn.jsdelivr.net/gh/zbefine/iptv/iptv.m3u"),
        arrayOf("live-yuechan", "开源·YueChan", "https://cdn.jsdelivr.net/gh/YueChan/Live@main/IPTV.m3u"),
        arrayOf("live-wwb", "开源·wwb521", "https://gh-proxy.com/https://raw.githubusercontent.com/wwb521/live/main/tv.m3u"),
        arrayOf("live-aptv", "开源·aptv", "https://gh-proxy.com/https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u"),
        arrayOf("live-vbsky", "开源·vbskycn", "https://live.zbds.top/tv/iptv4.m3u"),
        arrayOf("live-vbsky-gh", "开源·vbskycn镜像", "https://gh-proxy.com/https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u"),
        arrayOf("live-cn", "开源·iptv-org中国", "https://iptv-org.github.io/iptv/countries/cn.m3u"),
        arrayOf("live-gather", "国内综合·YanG", "https://gh-proxy.com/https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u"),
        arrayOf("live-zho", "中文·iptv-org", "https://iptv-org.github.io/iptv/languages/zho.m3u"),
        arrayOf("live-free", "全球·Free-TV", "https://gh-proxy.com/https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"),
        arrayOf("live-edu", "演示·教育", "https://iptv-org.github.io/iptv/categories/education.m3u"),
    )

    fun payload(ctx: Context, sourceId: String?): JSONObject {
        var src = SOURCES[0]
        val sid = sourceId?.trim().orEmpty().ifBlank { "live-snap" }
        for (row in SOURCES) if (row[0] == sid) src = row
        val parsed: List<JSONObject>
        val kind: String
        if (src[0] == "live-snap" || src[2].startsWith("asset://")) {
            parsed = readAssetChannels(ctx, "live/live_filtered.m3u") +
                readAssetChannels(ctx, "live/live_intl.m3u")
            kind = "snap"
        } else {
            parsed = parseM3u(httpGet(src[2]))
            kind = "remote"
        }
        val channels = onePerChannel(parsed)
        val sources = JSONArray()
        for (row in SOURCES) {
            sources.put(JSONObject().put("id", row[0]).put("name", row[1]).put("url", row[2]))
        }
        val source = JSONObject().put("id", src[0]).put("name", src[1]).put("url", src[2])
        var alts = 0
        for (i in 0 until channels.length()) {
            val arr = channels.getJSONObject(i).optJSONArray("alts")
            if (arr != null && arr.length() > 0) alts++
        }
        return JSONObject()
            .put("source", source)
            .put("sources", sources)
            .put("kind", kind)
            .put("channels", channels)
            .put("count", channels.length())
            .put("alts", alts)
    }

    fun playableChannels(payload: JSONObject): JSONArray {
        val out = JSONArray()
        val channels = payload.optJSONArray("channels") ?: return out
        for (i in 0 until channels.length()) {
            val ch = channels.optJSONObject(i) ?: continue
            val url = ch.optString("url")
            if (url.isBlank()) continue
            val alts = JSONArray()
            val raw = ch.optJSONArray("alts")
            if (raw != null) {
                for (j in 0 until raw.length()) {
                    val obj = raw.optJSONObject(j)
                    val u = obj?.optString("url").orEmpty().ifBlank { raw.optString(j) }
                    if (u.isNotBlank() && u != url) alts.put(u)
                }
            }
            out.put(
                JSONObject()
                    .put("name", ch.optString("name", "频道"))
                    .put("url", url)
                    .put("group", ch.optString("group", "未分组"))
                    .put("bucket", ch.optString("bucket", "地方"))
                    .put("alts", alts),
            )
        }
        return out
    }

    private fun readAssetChannels(ctx: Context, name: String): List<JSONObject> =
        runCatching { parseM3u(ctx.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }) }
            .getOrDefault(emptyList())

    private fun httpGet(url: String): String = try {
        httpGetOnce(url)
    } catch (e: Exception) {
        if (url.startsWith("https://")) httpGetOnce("http://" + url.substring("https://".length)) else throw e
    }

    private fun httpGetOnce(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            runCatching {
                val u = URL(url)
                setRequestProperty("Referer", "${u.protocol}://${u.host}/")
            }
        }
        return try {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun extract(line: String, key: String): String {
        val m = ATTR.matcher(line)
        while (m.find()) if (m.group(1).equals(key, true)) return m.group(2)
        return ""
    }

    private fun parseM3u(raw: String?): List<JSONObject> {
        var text = raw.orEmpty()
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<JSONObject>()
        var name = ""
        var group = ""
        var logo = ""
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val upper = line.uppercase(Locale.ROOT)
            when {
                upper.startsWith("#EXTINF") -> {
                    extract(line, "group-title").takeIf { it.isNotEmpty() }?.let { group = it }
                    extract(line, "tvg-logo").takeIf { it.isNotEmpty() }?.let { logo = it }
                    val comma = line.lastIndexOf(',')
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    if (name.isEmpty()) name = extract(line, "tvg-name")
                }
                upper.startsWith("#EXTGRP") -> {
                    val c = line.indexOf(':')
                    group = if (c >= 0) line.substring(c + 1).trim() else ""
                }
                line.startsWith("#") -> Unit
                else -> {
                    if (name.isEmpty()) name = extract(line, "tvg-name").ifEmpty { "频道" }
                    out += JSONObject()
                        .put("name", name)
                        .put("url", line)
                        .put("group", group.ifEmpty { "未分组" })
                        .put("logo", logo)
                        .put("alts", JSONArray())
                    name = ""; group = ""; logo = ""
                }
            }
        }
        return out
    }

    private fun normalizeKey(name: String?): String {
        var key = (name ?: "").lowercase(Locale.ROOT)
        key = STRIP.matcher(key).replaceAll("")
        key = QUAL.matcher(key).replaceAll("")
        key = KEEP_CHARS.matcher(key).replaceAll("")
        return key.ifEmpty { name ?: "" }
    }

    private fun bucketOf(ch: JSONObject): String {
        val t = ch.optString("group") + " " + ch.optString("name")
        val low = t.lowercase(Locale.ROOT)
        return when {
            t.contains("央视") || low.contains("cctv") || low.contains("cgtn") -> "央视"
            t.contains("卫视") -> "卫视"
            t.contains("国际") -> "国际"
            t.contains("港") || t.contains("澳") || t.contains("台湾") || t.contains("TVB") || TAIWAN.matcher(t).find() -> "港澳台"
            t.contains("电影") || t.contains("纪录") || t.contains("体育") || t.contains("音乐") ||
                t.contains("儿童") || t.contains("剧场") || t.contains("春晚") || t.contains("教育") ||
                t.contains("购物") || t.contains("赛事") -> "专题"
            else -> "地方"
        }
    }

    private fun onePerChannel(rows: List<JSONObject>): JSONArray {
        val order = ArrayList<String>()
        val mapped = LinkedHashMap<String, JSONObject>()
        val seen = HashSet<String>()
        for (ch in rows) {
            val url = ch.optString("url").trim()
            if (url.isEmpty() || !seen.add(url)) continue
            val key = normalizeKey(ch.optString("name"))
            if (key.matches(Regex("\\d+"))) continue
            val item = JSONObject()
                .put("name", ch.optString("name", "频道"))
                .put("url", url)
                .put("group", ch.optString("group", "未分组"))
                .put("logo", ch.optString("logo"))
                .put("alts", JSONArray())
            val primary = mapped[key]
            if (primary == null) {
                mapped[key] = item
                order += key
            } else if (primary.getJSONArray("alts").length() < KEEP - 1) {
                primary.getJSONArray("alts").put(item)
            }
        }
        val out = JSONArray()
        for (key in order) {
            val ch = mapped[key] ?: continue
            ch.put("bucket", bucketOf(ch))
            out.put(ch)
        }
        return out
    }
}
