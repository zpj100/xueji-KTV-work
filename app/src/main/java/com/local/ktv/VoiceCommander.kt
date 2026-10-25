package com.local.ktv

import org.json.JSONArray
import java.util.Locale

object VoiceCommander {
    private val COMMANDS = arrayOf(
        "来一首", "来一首歌", "唱一首", "唱一首歌", "点一首", "点一首歌", "来一曲",
        "小微小微", "小美小美", "我想听", "点歌",
        "切歌", "重唱", "暂停", "停止", "播放", "继续", "伴唱", "伴奏", "原唱",
    )
    // 唤醒/前缀：优先使用「唱一首 / 来一首」等点歌前缀，降低对设备唤醒词（小微小微等）
    // 的依赖——离线小模型对这类词汇识别率不稳定，导致“语音识别不到”。
    private val WAKES = arrayOf(
        "唱一首", "来一首", "点一首", "来一曲", "唱首", "来首",
        "我想听", "点歌", "唱一首歌", "来一首歌", "点一首歌",
        "小微小微", "小美小美", "小薇小薇", "小为小为",
    )

    data class Match(val hit: Boolean, val query: String, val songs: List<Song>)

    fun compact(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val b = StringBuilder()
        for (c in text) {
            if (c.code <= 32) continue
            if (c in ".,-_/\\，。、！？!?·~～") continue
            b.append(c)
        }
        return b.toString()
    }

    fun stripWake(text: String?): String {
        var t = compact(text)
        for (w in WAKES) t = t.replace(w, "")
        return t
    }

    fun hasWake(text: String?): Boolean {
        val t = compact(text)
        return WAKES.any { t.contains(it) }
    }

    fun phrases(titles: List<String>): JSONArray {
        val arr = JSONArray()
        for (c in COMMANDS) arr.put(c)
        titles.distinct().take(400).forEach { raw ->
            val t = compact(raw)
            if (t.isEmpty()) return@forEach
            arr.put(t)
            arr.put("来一首$t")
            arr.put("唱一首$t")
        }
        return arr
    }

    fun match(spoken: String?, songs: List<Song>): Match {
        val raw = compact(spoken)
        val q = stripWake(raw).ifEmpty { raw }
        if (q.isEmpty()) return Match(false, raw, emptyList())
        var bestTitle = q
        var best = 0
        for (song in songs.take(3000)) {
            val title = compact(song.title)
            val artist = compact(song.singer)
            val py = compact(song.pinyin).lowercase(Locale.ROOT)
            var s = score(q, title)
            val sa = score(q, artist)
            if (sa > s) s = sa
            val ql = q.lowercase(Locale.ROOT)
            if (py.isNotEmpty() && (py.contains(ql) || ql.contains(py))) {
                val ps = 600 + minOf(q.length, py.length) * 8
                if (ps > s) s = ps
            }
            if (s > best) {
                best = s
                bestTitle = song.title.orEmpty()
            }
        }
        val hit = best >= 120 && bestTitle.isNotEmpty()
        val query = if (hit) bestTitle else q
        // 本地（已下载/本机文件）优先排列，网络曲库结果紧随其后。
        val matched = songs.filter { SongSearch.matches(it, query) }
            .sortedByDescending { it.hasLocalFile() }
            .take(20)
        return Match(hit, query, matched)
    }

    private fun score(spoken: String, title: String): Int {
        if (spoken.isEmpty() || title.isEmpty()) return 0
        if (spoken == title) return 1000
        if (title.contains(spoken)) return 800 + spoken.length * 10
        if (spoken.contains(title) && title.length >= 2) return 700 + title.length * 10
        var best = 0
        val n = spoken.length
        val m = title.length
        if (n < 2 || m < 2) return 0
        for (i in 0 until n) {
            for (j in i + 2..n) {
                val part = spoken.substring(i, j)
                if (title.contains(part)) best = maxOf(best, 80 + part.length * 12)
            }
        }
        return best
    }
}
