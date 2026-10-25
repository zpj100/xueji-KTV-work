package com.local.ktv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * 歌曲下载进度页：实时列出正在下载的歌曲、进度与速度。
 */
class DownloadProgressActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var listContainer: LinearLayout? = null
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)

        val title = TextView(this).apply {
            text = "歌曲下载进度"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 24)
        }
        listContainer = list

        val scroll = ScrollView(this)
        scroll.addView(list, ViewGroup.LayoutParams(-1, -1))

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(title)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val root = FrameLayout(this)
        root.addView(outer)
        setContentView(root)

        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun refresh() {
        val list = listContainer ?: return
        val songs = SongOkDownloadManager.activeDownloads()
        list.removeAllViews()
        if (songs.isEmpty()) {
            val empty = TextView(this).apply {
                text = "当前没有正在下载的歌曲"
                setTextColor(Color.LTGRAY)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 40)
            }
            list.addView(empty)
            return
        }
        for (song in songs) {
            val status = SongOkDownloadManager.transferStatus(song)
            val percent = status?.first ?: SongOkDownloadManager.getDownloadProgress(song)
            val speed = status?.second ?: 0L
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val name = TextView(this).apply {
                text = "${song.title ?: "未知歌曲"} - ${song.singer ?: ""}"
                setTextColor(Color.WHITE)
                textSize = 16f
            }
            row.addView(name)
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = percent
                layoutParams = ViewGroup.LayoutParams(-1, dp(12))
            }
            row.addView(bar)
            val info = TextView(this).apply {
                text = "进度 $percent%    速度 ${formatSpeed(speed)}"
                setTextColor(Color.LTGRAY)
                textSize = 13f
            }
            row.addView(info)
            list.addView(row)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun formatSpeed(bytes: Long): String {
        if (bytes <= 0) return "--"
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(Locale.ROOT, "%.0f KB/s", kb)
        else String.format(Locale.ROOT, "%.1f MB/s", kb / 1024)
    }
}
