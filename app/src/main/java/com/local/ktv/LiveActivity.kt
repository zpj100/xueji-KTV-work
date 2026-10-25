package com.local.ktv

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONArray
import org.json.JSONObject

class LiveActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val channels = ArrayList<Array<String>>()
    private val tried = ArrayList<String>()
    private var player: ExoPlayer? = null
    private var surface: SurfaceView? = null
    private var root: FrameLayout? = null
    private var mark: TextView? = null
    private var logo: TextView? = null
    private var exitBtn: TextView? = null
    private var chIndex = 0
    private var sources = ArrayList<String>()
    private var srcIndex = 0
    private var failStreak = 0
    private var videoW = 0
    private var videoH = 0
    private val timeout = Runnable { autoSwitch("起播超时10s") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val rootView = FrameLayout(this).also { it.setBackgroundColor(Color.BLACK) }
        root = rootView
        val surfaceView = SurfaceView(this)
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER,
        )
        surface = surfaceView
        rootView.addView(surfaceView)
        val markView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = 28; topMargin = 24 }
        }
        mark = markView
        rootView.addView(markView)

        // 左上角台标（频道/电台标识）
        val logoView = TextView(this).apply {
            text = "学记KTV"
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = 28
                topMargin = 24
                gravity = Gravity.TOP or Gravity.START
                marginStart = 28
            }
        }
        logo = logoView
        rootView.addView(logoView)

        // 右上角透明「退出全屏」按钮
        val exitView = TextView(this).apply {
            text = "✕ 退出"
            setTextColor(Color.WHITE)
            textSize = 16f
            alpha = 0.5f
            gravity = Gravity.CENTER
            setPadding(24, 10, 24, 10)
            setOnClickListener { finish() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 24
                marginEnd = 28
                gravity = Gravity.TOP or Gravity.END
                marginRight = 28
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
            channels += arrayOf(name.ifEmpty { "直播" }, url, joinAlts(alts))
        }
        chIndex = intent.getIntExtra("index", 0).coerceAtLeast(0)
        if (chIndex >= channels.size) chIndex = 0
        if (channels.isEmpty()) {
            Toast.makeText(this, "没有可播放的频道", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        buildPlayer()
        loadChannel(chIndex)
    }

    private fun buildPlayer() {
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
        player = exo
        surface?.holder?.setKeepScreenOn(true)
        exo.setVideoSurfaceView(surface)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                autoSwitch(error.errorCodeName)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    failStreak = 0
                    handler.removeCallbacks(timeout)
                }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoW = size.width
                videoH = size.height
                fitSurface()
            }
        })
        root?.post { fitSurface() }
    }

    private fun fitSurface() {
        val surfaceView = surface ?: return
        val host = root ?: return
        val rw = host.width
        val rh = host.height
        if (rw <= 0 || rh <= 0) return
        if (videoW <= 0 || videoH <= 0) {
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            return
        }
        val scale = minOf(rw.toDouble() / videoW, rh.toDouble() / videoH)
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            (videoW * scale).toInt(), (videoH * scale).toInt(), Gravity.CENTER,
        )
    }

    private fun loadChannel(index: Int) {
        chIndex = index
        val ch = channels[chIndex]
        sources = ArrayList(splitAlts(ch[2]))
        if (sources.isEmpty()) sources += ch[1]
        srcIndex = sources.indexOf(ch[1]).coerceAtLeast(0)
        failStreak = 0
        tried.clear()
        playSource(srcIndex)
    }

    private fun playSource(index: Int) {
        val exo = player ?: return
        if (sources.isEmpty() || index !in sources.indices) return
        srcIndex = index
        val url = sources[index]
        tried += url
        val name = channels[chIndex][0]
        mark?.text = name + if (sources.size > 1) "  ·  ${srcIndex + 1}/${sources.size}" else ""
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        exo.prepare()
        exo.playWhenReady = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 10000)
    }

    private fun autoSwitch(reason: String) {
        failStreak++
        if (failStreak >= 5) {
            Toast.makeText(this, "${channels[chIndex][0]} 多条源均不可用（$reason）", Toast.LENGTH_SHORT).show()
            return
        }
        for (i in sources.indices) {
            if (sources[i] !in tried) {
                Toast.makeText(this, "切到备源 ${i + 1}/${sources.size}", Toast.LENGTH_SHORT).show()
                playSource(i)
                return
            }
        }
        Toast.makeText(this, "${channels[chIndex][0]} 打不开", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "已切到 ${channels[chIndex][0]}", Toast.LENGTH_SHORT).show()
        loadChannel(chIndex)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    finish(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    switchChannel(1); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_UP -> {
                    switchChannel(-1); return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    manualNextSource(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onStop()
    }

    private fun parseChannels(json: String?) {
        runCatching {
            val arr = JSONArray(json.orEmpty())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isEmpty()) continue
                val alts = ArrayList<String>()
                val a = o.optJSONArray("alts")
                if (a != null) {
                    for (j in 0 until a.length()) {
                        val item = a.optJSONObject(j)
                        val u = item?.optString("url").orEmpty().ifBlank { a.optString(j) }
                        if (u.isNotEmpty()) alts += u
                    }
                }
                channels += arrayOf(o.optString("name", "频道"), url, joinAlts(alts))
            }
        }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 4.4) MaidongKTV/2.1.1"

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

        private fun splitAlts(raw: String?): List<String> =
            raw.orEmpty().split('\n').filter { it.isNotEmpty() }

        private fun joinAlts(alts: List<String>): String = alts.joinToString("\n")
    }
}
