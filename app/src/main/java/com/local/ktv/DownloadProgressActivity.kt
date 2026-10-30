package com.local.ktv

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * 歌曲下载进度「悬浮」面板：占屏幕右侧 50% 宽、50% 不透明度，覆盖在主页之上。
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#80000000")) // 50% 不透明度
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(20), dp(14), dp(20), dp(18))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "歌曲下载进度"
            setTextColor(Color.WHITE)
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isFocusable = true
            isClickable = true
            setOnClickListener { finish() }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(14).toFloat()
            }
        })
        panel.addView(titleRow)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        listContainer = list
        val scroll = ScrollView(this)
        scroll.addView(list, ViewGroup.LayoutParams(-1, -1))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val root = FrameLayout(this).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // 悬浮：仅占屏幕右侧 50%
        val width = (resources.displayMetrics.widthPixels * 0.5f).toInt()
        window.setLayout(width, WindowManager.LayoutParams.MATCH_PARENT)
        window.setGravity(Gravity.END)

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
            list.addView(TextView(this).apply {
                text = "当前没有正在下载的歌曲"
                setTextColor(Color.LTGRAY)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            })
            return
        }
        for (song in songs) {
            val status = SongOkDownloadManager.transferStatus(song)
            val percent = status?.first ?: SongOkDownloadManager.getDownloadProgress(song)
            val speed = status?.second ?: 0L
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(this).apply {
                text = "${song.title ?: "未知歌曲"} - ${song.singer ?: ""}"
                setTextColor(Color.WHITE)
                textSize = 16f
            })
            row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = percent
                layoutParams = ViewGroup.LayoutParams(-1, dp(12))
            })
            row.addView(TextView(this).apply {
                text = "进度 $percent%    速度 ${formatSpeed(speed)}"
                setTextColor(Color.LTGRAY)
                textSize = 13f
            })
            list.addView(row)
        }
    }

    private fun formatSpeed(bytes: Long): String {
        if (bytes <= 0) return "--"
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(Locale.ROOT, "%.0f KB/s", kb)
        else String.format(Locale.ROOT, "%.1f MB/s", kb / 1024)
    }
}
