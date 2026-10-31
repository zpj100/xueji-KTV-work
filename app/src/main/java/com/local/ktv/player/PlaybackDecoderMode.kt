package com.local.ktv.player

/** 解码策略。IJK 内核下 SYSTEM=MediaCodec，SOFTWARE=FFmpeg，AUTO=FFmpeg 优先。 */
enum class PlaybackDecoderMode(val title: String) {
    AUTO("自动（FFmpeg 优先，兼容性最好）"),
    SYSTEM("系统解码（MediaCodec）"),
    SOFTWARE("兼容解码（FFmpeg）");

    companion object {
        fun fromStored(value: String?): PlaybackDecoderMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class DecoderFailureTrack { AUDIO, VIDEO, UNKNOWN }

/** 兼容旧调用：判断错误码是否属于解码类失败（沿用 Media3 的 4001-4005 取值）。 */
object DecoderRecoveryState {
    private val DECODER_ERRORS = setOf(4001, 4002, 4003, 4004, 4005)
    fun isDecoderFailure(errorCode: Int): Boolean = errorCode in DECODER_ERRORS
}
