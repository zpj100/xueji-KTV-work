package com.local.ktv.player

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.misc.ITrackInfo

/**
 * 播放显示结构：FrameLayout + ResizeSurfaceView；播放由共享的 [KtvPlaybackEngine]（IJK/FFmpeg）承担，
 * 页面与全屏切换只需重定向解码输出 surface。IJK 内核支持 Android 4.0+。
 */
class KtvVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private var surfaceView = KtvSurfaceView(context)
    private var mediaOverlay = false
    private var engine: KtvPlaybackEngine? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null

    init {
        setBackgroundColor(Color.BLACK)
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
    }

    fun bind(engine: KtvPlaybackEngine) {
        this.engine = engine
    }

    fun setMediaOverlay(enabled: Boolean) {
        mediaOverlay = enabled
        surfaceView.setZOrderMediaOverlay(enabled)
    }

    fun setVideoURI(uri: Uri) = engine?.setVideoUri(uri) ?: Unit
    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) = engine?.setOnPreparedListener(listener) ?: Unit
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) = engine?.setOnCompletionListener(listener) ?: Unit
    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) = engine?.setOnErrorListener(listener) ?: Unit
    fun start() = engine?.start() ?: Unit
    fun pause() = engine?.pause() ?: Unit
    fun seekTo(positionMs: Int) = engine?.seekTo(positionMs) ?: Unit
    fun stopPlayback() = engine?.stop() ?: Unit
    fun setPlaybackVolume(left: Float, right: Float) = engine?.setVolume(left, right) ?: Unit
    fun setTone(step: Int) = engine?.setTone(step) ?: Unit
    fun selectAudioTrack(original: Boolean): Boolean = engine?.selectAudioTrack(original) == true
    fun audioTrackCount(): Int = engine?.audioTrackCount() ?: 0
    fun selectAudioChannel(channel: Int, volume: Float) = engine?.selectAudioChannel(channel, volume) ?: Unit
    val isPlaying: Boolean get() = engine?.isPlaying == true
    val currentPosition: Int get() = engine?.currentPosition ?: 0
    val duration: Int get() = engine?.duration ?: 0

    internal fun updateVideoSize(width: Int, height: Int) {
        surfaceView.setVideoSize(width, height)
    }

    internal fun installSurfaceCallback(callback: SurfaceHolder.Callback) {
        surfaceCallback?.let(surfaceView.holder::removeCallback)
        surfaceCallback = callback
        surfaceView.holder.addCallback(callback)
    }

    internal fun currentHolder(): SurfaceHolder = surfaceView.holder

    internal fun recreateDecoderSurface() {
        surfaceCallback?.let(surfaceView.holder::removeCallback)
        removeView(surfaceView)
        surfaceView = KtvSurfaceView(context).also { it.setZOrderMediaOverlay(mediaOverlay) }
        addView(surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        surfaceCallback?.let(surfaceView.holder::addCallback)
    }
}

/** Resize behavior ported from the original ResizeSurfaceView, scale mode 1. */
private class KtvSurfaceView(context: Context) : SurfaceView(context) {
    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        if (width > 0 && height > 0) holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = getDefaultSize(videoWidth, widthMeasureSpec).coerceAtLeast(1)
        val maxHeight = getDefaultSize(videoHeight, heightMeasureSpec).coerceAtLeast(1)
        var measuredWidth = maxWidth
        var measuredHeight = measuredWidth * 9 / 16
        if (measuredHeight > maxHeight) {
            measuredHeight = maxHeight
            measuredWidth = measuredHeight * 16 / 9
        }
        setMeasuredDimension(measuredWidth.coerceAtLeast(1), measuredHeight.coerceAtLeast(1))
    }
}

/** Values are internal to the shared player and its existing controls. */
object AudioChannels {
    const val AUDIO_CHANNEL_LEFT = 0
    const val AUDIO_CHANNEL_RIGHT = 1
    const val AUDIO_CHANNEL_STEREO = 2
}

/**
 * IJK/FFmpeg 播放引擎（适配 Android 4.0+），对外 API 与原 Media3 引擎保持一致，
 * 保留调音（pitch）、原唱/伴唱轨道切换、左右声道选择等能力。
 */
class KtvPlaybackEngine(context: Context, private val audioOnly: Boolean = false) {
    private companion object {
        const val TAG = "KtvPlaybackEngine"
        const val FFP_PROP_FLOAT_PLAYBACK_PITCH = 10008
        const val MEDIA_INFO_VIDEO_RENDERING_START = 3
    }

    private val appContext = context.applicationContext
    private var ijkPlayer: IjkMediaPlayer? = null
    private var targetView: KtvVideoView? = null
    private var prepared = false
    private var released = false
    private var startWhenPrepared = false
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null
    private var recoveryListener: ((Boolean) -> Unit)? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var toneStep = 0
    private var lastUri: Uri? = null

    var decoderMode: PlaybackDecoderMode = PlaybackDecoderSettings.load(appContext)
        private set
    var hasRenderedFirstFrame = false
        private set

    val decoderSummary: String
        get() = "视频：IJK/FFmpeg\n音频：IJK/FFmpeg"

    internal val decodedNonSilentSamples: Long get() = 0L

    fun isSoftwareDecoderAvailable(): Boolean = true

    val isPlaying: Boolean get() = runCatching { ijkPlayer?.isPlaying == true }.getOrDefault(false)
    val currentPosition: Int get() = runCatching {
        (ijkPlayer?.currentPosition ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }.getOrDefault(0)
    val duration: Int get() = runCatching {
        (ijkPlayer?.duration ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }.getOrDefault(0)

    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) { preparedListener = listener }
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) { completionListener = listener }
    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) { errorListener = listener }
    fun setOnDecoderRecoveryListener(listener: ((Boolean) -> Unit)?) { recoveryListener = listener }

    fun setDecoderMode(mode: PlaybackDecoderMode) {
        if (released || decoderMode == mode) return
        decoderMode = mode
        val uri = lastUri ?: return
        // 重新按新解码策略准备当前媒体
        val position = currentPosition
        releaseIjk()
        prepared = false
        setVideoUri(uri)
        if (position > 0) seekTo(position)
    }

    fun attach(view: KtvVideoView) {
        targetView = view
        view.bind(this)
        view.keepScreenOn = true
        view.updateVideoSize(videoWidth, videoHeight)
        view.installSurfaceCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (targetView === view && !released) setOutputHolder(holder)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (targetView === view) runCatching { ijkPlayer?.setDisplay(null) }
            }
        })
        view.currentHolder().takeIf { it.surface?.isValid == true }?.let(::setOutputHolder)
    }

    fun setVideoUri(uri: Uri) {
        if (released) return
        lastUri = uri
        prepared = false
        startWhenPrepared = false
        hasRenderedFirstFrame = false
        val player = ensureIjkPlayer()
        if (player == null) {
            errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
            return
        }
        val first = runCatching { prepare(player, uri) }
        if (first.isFailure) {
            Log.e(TAG, "IJK prepare failed; recreate", first.exceptionOrNull())
            releaseIjk()
            val retry = ensureIjkPlayer()
            val second = retry?.let { runCatching { prepare(it, uri) } }
            if (second == null || second.isFailure) {
                prepared = false
                errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
            }
        }
    }

    private fun prepare(player: IjkMediaPlayer, uri: Uri) {
        player.reset()
        configure(player)
        targetView?.currentHolder()?.takeIf { it.surface?.isValid == true }?.let(player::setDisplay)
        val source = if (uri.scheme.equals("file", true)) uri.path.orEmpty() else uri.toString()
        player.setDataSource(source)
        player.prepareAsync()
    }

    fun start() {
        if (!prepared) { startWhenPrepared = true; return }
        runCatching { ijkPlayer?.start() }
    }

    fun pause() {
        startWhenPrepared = false
        runCatching { if (ijkPlayer?.isPlaying == true) ijkPlayer?.pause() }
    }

    fun seekTo(positionMs: Int) = runCatching { ijkPlayer?.seekTo(positionMs.coerceAtLeast(0).toLong()) }

    fun stop() {
        startWhenPrepared = false
        prepared = false
        runCatching { ijkPlayer?.stop() }
    }

    fun setVolume(left: Float, right: Float) {
        val l = left.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        runCatching { ijkPlayer?.setVolume(l, r) }
    }

    /** -5..5 步进，每步 8% 音调（与原 32 位后端一致）。 */
    fun setTone(step: Int) {
        toneStep = step.coerceIn(-5, 5)
        ijkPlayer?.let(::applyTone)
    }

    private fun applyTone(player: IjkMediaPlayer) {
        val pitch = 1.0f + toneStep * 0.08f
        runCatching {
            val method = IjkMediaPlayer::class.java.getDeclaredMethod(
                "_setPropertyFloat", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(player, FFP_PROP_FLOAT_PLAYBACK_PITCH, pitch)
        }.onFailure { Log.e(TAG, "applyTone step=$toneStep failed", it) }
    }

    fun selectAudioTrack(original: Boolean): Boolean = runCatching {
        val ijk = ijkPlayer ?: return@runCatching false
        val audioTracks = ijk.trackInfo.indices.filter {
            ijk.trackInfo[it].trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO
        }
        if (audioTracks.size < 2) return@runCatching false
        val target = if (original) audioTracks[0] else audioTracks[1]
        if (ijk.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) != target) ijk.selectTrack(target)
        true
    }.getOrDefault(false)

    fun audioTrackCount(): Int = runCatching {
        ijkPlayer?.trackInfo?.count { it.trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO } ?: 0
    }.getOrDefault(0)

    fun selectAudioChannel(channel: Int, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        val ijk = ijkPlayer
        if (ijk != null) {
            runCatching {
                ijk.setVolume(v, v)
                ijk.seletcAudioChannel(channel)
            }.onFailure { Log.e(TAG, "selectAudioChannel=$channel failed", it) }
            return
        }
        when (channel) {
            AudioChannels.AUDIO_CHANNEL_LEFT -> setVolume(v, 0f)
            AudioChannels.AUDIO_CHANNEL_RIGHT -> setVolume(0f, v)
            else -> setVolume(v, v)
        }
    }

    fun release() {
        if (released) return
        released = true
        startWhenPrepared = false
        prepared = false
        releaseIjk()
        targetView = null
        preparedListener = null
        completionListener = null
        errorListener = null
        recoveryListener = null
    }

    private fun ensureIjkPlayer(): IjkMediaPlayer? {
        ijkPlayer?.let { return it }
        return runCatching {
            IjkMediaPlayer().also { ijkPlayer = it }
        }.onFailure { Log.e(TAG, "Cannot create IJK player", it) }.getOrNull()
    }

    private fun configure(player: IjkMediaPlayer) {
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-type", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV16.toLong())
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 6_291_456L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1_048_576L)
        val useMediaCodec = decoderMode == PlaybackDecoderMode.SYSTEM
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (useMediaCodec) 1L else 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", if (useMediaCodec) 1L else 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0L)
        if (audioOnly) player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1L)
        player.setAudioStreamType(AudioManager.STREAM_MUSIC)
        player.setScreenOnWhilePlaying(true)
        applyTone(player)
        player.setOnPreparedListener { mp: IMediaPlayer ->
            prepared = true
            videoWidth = mp.videoWidth
            videoHeight = mp.videoHeight
            targetView?.updateVideoSize(videoWidth, videoHeight)
            preparedListener?.onPrepared(null)
            if (startWhenPrepared && !mp.isPlaying) runCatching { mp.start() }
        }
        player.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            videoWidth = width
            videoHeight = height
            targetView?.updateVideoSize(width, height)
        }
        player.setOnCompletionListener { completionListener?.onCompletion(null) }
        player.setOnErrorListener { _, what, extra ->
            prepared = false
            Log.e(TAG, "onError what=$what extra=$extra")
            errorListener?.onError(null, what, extra) ?: true
        }
        player.setOnInfoListener { _, what, _ ->
            if (what == MEDIA_INFO_VIDEO_RENDERING_START) hasRenderedFirstFrame = true
            false
        }
    }

    private fun setOutputHolder(holder: SurfaceHolder) {
        runCatching { ijkPlayer?.setDisplay(holder) }
    }

    private fun releaseIjk() {
        runCatching { ijkPlayer?.setDisplay(null) }
        runCatching { ijkPlayer?.release() }
        ijkPlayer = null
    }
}
