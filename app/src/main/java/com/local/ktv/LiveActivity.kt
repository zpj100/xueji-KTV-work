package com.local.ktv

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.local.ktv.player.KtvPlaybackEngine
import com.local.ktv.player.KtvVideoView
import org.json.JSONArray
import org.json.JSONObject

class LiveActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private data class Channel(val name: String, val url: String, val altList: List<String>, val category: String)

    private val channels = ArrayList<Channel>()
    private val tried = ArrayList<String>()
    private var videoView: KtvVideoView? = null
    private var engine: KtvPlaybackEngine? = null
    private var root: FrameLayout? = null
    private var mark: TextView? = null
    private var exitBtn: TextView? = null

    private var selectorRoot: LinearLayout? = null
    private var stationColumn: LinearLayout? = null
    private var stationInner: LinearLayout? = null
    private val categoryViews = LinkedHashMap<String, TextView>()
    private val stationItems = ArrayList<TextView>()
    private val stationIndex = ArrayList<Int>()
    private var activeCategory = ""

    private var controls: LinearLayout? = null

    private var chIndex = 0
    private var sources = ArrayList<String>()
    private var srcIndex = 0
    private var failStreak = 0
    private val timeout = Runnable { autoSwitch("起播超时10s") }

    private val hideSelectorTask = Runnable { hideSelector() }
    private val hideControlsTask = Runnable { hideControls() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val rootView = FrameLayout(this).also { it.setBackgroundColor(Color.BLACK) }
        root = rootView

        val video = KtvVideoView(this)
        videoView = video
        rootView.addView(video, FrameLayout.LayoutParams(MATCH, MATCH))

        val clickCatcher = View(this).apply {
            isClickable = true
            setOnClickListener {
                if (selectorRoot?.visibility == View.VISIBLE) hideSelector() else toggleControls()
            }
        }
        rootView.addView(clickCatcher, FrameLayout.LayoutParams(MATCH, MATCH))

        val markView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = 28; topMargin = 24 }
        }
        mark = markView
        rootView.addView(markView)

        val exitView = TextView(this).apply {
            text = "✕ 退出"
            setTextColor(Color.WHITE)
            textSize = 16f
            alpha = 0.5f
            gravity = Gravity.CENTER
            setPadding(24, 10, 24, 10)
            setOnClickListener { finish() }
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = 24
                marginEnd = 28
                gravity = Gravity.TOP or Gravity.END
            }
        }
        exitBtn = exitView
        rootView.addView(exitView)

        setContentView(rootView)

        parseChannels(intent.getStringExtra("channels"))
        val url = intent.getStringExtra("url").orEmpty()
        val name = intent.getStringExtra("name").orEmpty()
        val alts = splitJsonArray(intent.getStringExtra("alts"))
        if (channels.isEmpty() && url.isNotEmpty()) {
            val nm = name.ifEmpty { "直播" }
            channels += Channel(nm, url, alts, deriveCategory(nm))
        }
        chIndex = intent.getIntExtra("index", 0).coerceAtLeast(0)
        if (chIndex >= channels.size) chIndex = 0
        if (channels.isEmpty()) {
            Toast.makeText(this, "没有可播放的频道", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        activeCategory = channels[chIndex].category
        buildSelector()
        buildControls()
        buildToggleButton()
        buildPlayer(video)
        loadChannel(chIndex)
    }

    // ===================== 选台 =====================

    private fun orderedCategories(): List<String> {
        val order = listOf("央视", "卫视", "国际", "港澳台", "专题", "地方", "未分组")
        val present = LinkedHashSet<String>()
        channels.forEach { present.add(it.category.ifBlank { "未分组" }) }
        val out = ArrayList<String>()
        order.forEach { if (present.contains(it)) out.add(it) }
        present.forEach { if (!out.contains(it)) out.add(it) }
        return out
    }

    private fun buildSelector() {
        val host = root ?: return
        val rootSel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(WRAP, MATCH).apply {
                gravity = Gravity.START or Gravity.TOP
                leftMargin = 0
            }
            visibility = View.GONE
        }
        val catCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundCompat(GradientDrawable().apply { setColor(Color.parseColor("#E6000000")) })
            setPadding(dp(8), dp(22), dp(8), dp(22))
            layoutParams = LinearLayout.LayoutParams(dp(150), MATCH)
        }
        catCol.addView(sectionTitle("分类"))
        val catScroll = ScrollView(this)
        val catInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catScroll.addView(catInner, ViewGroup.LayoutParams(MATCH, WRAP))
        catCol.addView(catScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        rootSel.addView(catCol)

        val stCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundCompat(GradientDrawable().apply { setColor(Color.parseColor("#CC000000")) })
            setPadding(dp(8), dp(22), dp(8), dp(22))
            layoutParams = LinearLayout.LayoutParams(dp(260), MATCH)
            visibility = View.GONE
        }
        stCol.addView(sectionTitle("电视台"))
        val stScroll = ScrollView(this)
        val stInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stScroll.addView(stInner, ViewGroup.LayoutParams(MATCH, WRAP))
        stCol.addView(stScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        rootSel.addView(stCol)

        categoryViews.clear()
        for (cat in orderedCategories()) {
            val item = makeItem(cat) { showCategory(cat) }
            item.setOnFocusChangeListener { v, hasFocus -> onItemFocus(v, hasFocus) }
            catInner.addView(item, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) })
            categoryViews[cat] = item
        }

        selectorRoot = rootSel
        stationColumn = stCol
        stationInner = stInner
        host.addView(rootSel)
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#FFDDA0"))
        textSize = 16f
        setPadding(dp(10), 0, dp(10), dp(8))
    }

    private fun makeItem(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(dp(12), dp(9), dp(12), dp(9))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun onItemFocus(v: View, hasFocus: Boolean) {
        v.setBackgroundColor(if (hasFocus) Color.parseColor("#66FFA500") else Color.TRANSPARENT)
        resetSelectorTimer()
    }

    private fun refreshHighlights() {
        categoryViews.forEach { (cat, tv) ->
            if (!tv.hasFocus()) {
                tv.setBackgroundColor(if (cat == activeCategory) Color.parseColor("#55FF8C00") else Color.TRANSPARENT)
            }
        }
        stationItems.forEachIndexed { i, tv ->
            if (!tv.hasFocus()) {
                val idx = stationIndex.getOrElse(i) { -1 }
                tv.setBackgroundColor(if (idx == chIndex) Color.parseColor("#55FF8C00") else Color.TRANSPARENT)
            }
        }
    }

    private fun showCategory(cat: String) {
        activeCategory = cat
        val inner = stationInner ?: return
        stationColumn?.visibility = View.VISIBLE
        inner.removeAllViews()
        stationItems.clear()
        stationIndex.clear()
        channels.forEachIndexed { idx, ch ->
            if (ch.category.ifBlank { "未分组" } != cat) return@forEachIndexed
            val item = makeItem(ch.name) { selectChannel(idx) }
            item.setOnFocusChangeListener { v, hasFocus -> onItemFocus(v, hasFocus) }
            inner.addView(item, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) })
            stationItems.add(item)
            stationIndex.add(idx)
        }
        refreshHighlights()
        resetSelectorTimer()
        stationItems.firstOrNull()?.requestFocus()
    }

    private fun showSelector() {
        val sel = selectorRoot ?: return
        sel.visibility = View.VISIBLE
        val cat = channels.getOrNull(chIndex)?.category?.ifBlank { "未分组" } ?: activeCategory
        showCategory(cat)
        categoryViews[cat]?.requestFocus()
        resetSelectorTimer()
    }

    private fun hideSelector() {
        handler.removeCallbacks(hideSelectorTask)
        selectorRoot?.visibility = View.GONE
        stationColumn?.visibility = View.GONE
    }

    private fun toggleSelector() {
        if (selectorRoot?.visibility == View.VISIBLE) hideSelector() else showSelector()
    }

    private fun resetSelectorTimer() {
        if (selectorRoot?.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideSelectorTask)
            handler.postDelayed(hideSelectorTask, 10_000)
        }
    }

    private fun selectChannel(index: Int) {
        if (index !in channels.indices) return
        loadChannel(index)
        hideSelector()
        Toast.makeText(this, "已切到 ${channels[index].name}", Toast.LENGTH_SHORT).show()
    }

    // ===================== 点按控制条 =====================

    private fun buildControls() {
        val host = root ?: return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(40)
            }
            visibility = View.GONE
        }
        val prev = pill("⏮ 上一个") { switchChannel(-1); resetControlsTimer() }
        val next = pill("下一个 ⏭") { switchChannel(1); resetControlsTimer() }
        bar.addView(prev, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(16) })
        bar.addView(next)
        controls = bar
        host.addView(bar)
    }

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(10), dp(22), dp(10))
        setBackgroundCompat(GradientDrawable().apply {
            setColor(Color.parseColor("#B3000000"))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.parseColor("#66FFFFFF"))
        })
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun showControls() { controls?.visibility = View.VISIBLE; resetControlsTimer() }
    private fun hideControls() { handler.removeCallbacks(hideControlsTask); controls?.visibility = View.GONE }
    private fun toggleControls() {
        if (controls?.visibility == View.VISIBLE) hideControls() else showControls()
    }
    private fun resetControlsTimer() {
        handler.removeCallbacks(hideControlsTask)
        handler.postDelayed(hideControlsTask, 5_000)
    }

    private fun buildToggleButton() {
        val host = root ?: return
        val toggle = TextView(this).apply {
            text = "☰ 选台"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setBackgroundCompat(GradientDrawable().apply {
                setColor(Color.parseColor("#99000000"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            })
            isFocusable = false
            isClickable = true
            setOnClickListener { toggleSelector() }
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(20)
                bottomMargin = dp(20)
            }
        }
        host.addView(toggle)
    }

    // ===================== 播放（IJK） =====================

    private fun buildPlayer(view: KtvVideoView) {
        val playback = KtvPlaybackEngine(this)
        engine = playback
        playback.attach(view)
        playback.setOnPreparedListener {
            failStreak = 0
            handler.removeCallbacks(timeout)
        }
        playback.setOnErrorListener { _, what, extra ->
            autoSwitch("what=$what extra=$extra")
            true
        }
    }

    private fun loadChannel(index: Int) {
        chIndex = index
        val ch = channels[chIndex]
        activeCategory = ch.category.ifBlank { "未分组" }
        sources = ArrayList(ch.altList)
        if (sources.isEmpty()) sources += ch.url
        srcIndex = sources.indexOf(ch.url).coerceAtLeast(0)
        failStreak = 0
        tried.clear()
        refreshHighlights()
        playSource(srcIndex)
    }

    private fun playSource(index: Int) {
        val playback = engine ?: return
        if (sources.isEmpty() || index !in sources.indices) return
        srcIndex = index
        val url = sources[index]
        tried += url
        val name = channels[chIndex].name
        mark?.text = name + if (sources.size > 1) "  ·  ${srcIndex + 1}/${sources.size}" else ""
        playback.setVideoUri(Uri.parse(url))
        playback.start()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 10000)
    }

    private fun autoSwitch(reason: String) {
        failStreak++
        if (failStreak >= 5) {
            Toast.makeText(this, "${channels[chIndex].name} 多条源均不可用（$reason）", Toast.LENGTH_SHORT).show()
            return
        }
        for (i in sources.indices) {
            if (sources[i] !in tried) {
                Toast.makeText(this, "切到备源 ${i + 1}/${sources.size}", Toast.LENGTH_SHORT).show()
                playSource(i)
                return
            }
        }
        Toast.makeText(this, "${channels[chIndex].name} 打不开", Toast.LENGTH_SHORT).show()
    }

    private fun manualNextSource() {
        if (sources.size < 2) {
            Toast.makeText(this, "该频道只有一路源", Toast.LENGTH_SHORT).show()
            return
        }
        failStreak = 0
        tried.clear()
        playSource((srcIndex + 1) % sources.size)
    }

    private fun switchChannel(delta: Int) {
        if (channels.size < 2) {
            Toast.makeText(this, "没有其他频道", Toast.LENGTH_SHORT).show()
            return
        }
        val n = channels.size
        chIndex = ((chIndex + delta) % n + n) % n
        Toast.makeText(this, "已切到 ${channels[chIndex].name}", Toast.LENGTH_SHORT).show()
        loadChannel(chIndex)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val selVisible = selectorRoot?.visibility == View.VISIBLE
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    when {
                        selVisible -> hideSelector()
                        controls?.visibility == View.VISIBLE -> hideControls()
                        else -> finish()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { toggleSelector(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!selVisible) { toggleControls(); return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!selVisible) { switchChannel(1); return true }
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!selVisible) { switchChannel(-1); return true }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (!selVisible) { manualNextSource(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        engine?.release()
        engine = null
        super.onStop()
    }

    private fun parseChannels(json: String?) {
        runCatching {
            val arr = JSONArray(json.orEmpty())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isEmpty()) continue
                val nm = o.optString("name", "频道")
                val alts = ArrayList<String>()
                val a = o.optJSONArray("alts")
                if (a != null) {
                    for (j in 0 until a.length()) {
                        val item = a.optJSONObject(j)
                        val u = item?.optString("url").orEmpty().ifBlank { a.optString(j) }
                        if (u.isNotEmpty()) alts += u
                    }
                }
                val bucket = o.optString("bucket").ifBlank {
                    o.optString("group").ifBlank { deriveCategory(nm) }
                }
                channels += Channel(nm, url, alts, bucket)
            }
        }
    }

    private fun deriveCategory(name: String): String {
        val low = name.lowercase()
        return when {
            name.contains("央视") || low.contains("cctv") || low.contains("cgtn") -> "央视"
            name.contains("卫视") -> "卫视"
            name.contains("国际") -> "国际"
            name.contains("港") || name.contains("澳") || name.contains("台湾") || low.contains("tvb") -> "港澳台"
            name.contains("电影") || name.contains("纪录") || name.contains("体育") || name.contains("音乐") ||
                name.contains("儿童") || name.contains("剧场") || name.contains("教育") ||
                name.contains("购物") || name.contains("赛事") -> "专题"
            else -> "地方"
        }
    }

    companion object {
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private fun splitJsonArray(raw: String?): List<String> {
            val out = ArrayList<String>()
            runCatching {
                val arr = JSONArray(raw.orEmpty())
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i)
                    if (u.isNotEmpty()) out += u
                }
            }
            return out
        }
    }
}
