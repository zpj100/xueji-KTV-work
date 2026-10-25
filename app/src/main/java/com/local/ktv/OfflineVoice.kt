package com.local.ktv

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class OfflineVoice(context: Context) {
    fun interface Callback {
        fun onText(text: String)
    }

    private val context = context.applicationContext
    private var model: Model? = null
    @Volatile private var listenRunning = false
    private var listenThread: Thread? = null
    @Volatile private var grammarJson: String? = null
    @Volatile private var grammarVer = 0

    fun isReady(): Boolean = model != null

    @Synchronized fun setPhrases(phrases: JSONArray?) {
        val g = JSONArray()
        val seen = HashSet<String>()
        if (phrases != null) {
            for (i in 0 until phrases.length()) {
                val p = phrases.optString(i, "").trim()
                if (p.isEmpty() || !seen.add(p)) continue
                g.put(p)
            }
        }
        g.put("[unk]")
        grammarJson = g.toString()
        grammarVer++
    }

    fun prepare(): Boolean {
        if (model != null) return true
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val dir = File(context.filesDir, MODEL_NAME)
            if (!File(dir, READY_MARK).isFile) unpack(dir)
            model = Model(dir.absolutePath)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "离线语音不可用: $t")
            model = null
            false
        }
    }

    @Synchronized fun startListen(cb: Callback?) {
        if (model == null || listenRunning) return
        listenRunning = true
        listenThread = Thread({
            var recognizer: Recognizer? = null
            var recorder: AudioRecord? = null
            var ver = -1
            try {
                recorder = openRecorder()
                val buf = ByteArray(READ_BYTES)
                recorder.startRecording()
                while (listenRunning) {
                    if (recognizer == null || ver != grammarVer) {
                        recognizer?.runCatching { close() }
                        ver = grammarVer
                        recognizer = makeRecognizer()
                    }
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val uttered = recognizer?.acceptWaveForm(buf, n) == true
                    if (!uttered) continue
                    val text = parseText(recognizer?.result, true)
                    if (text.isNotEmpty()) cb?.onText(text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "常驻听写结束: $t")
            } finally {
                recorder?.runCatching { stop() }
                recorder?.runCatching { release() }
                recognizer?.runCatching { close() }
            }
        }, "ktv-listen")
        listenThread?.start()
    }

    @Synchronized fun stopListen() {
        listenRunning = false
        val t = listenThread
        listenThread = null
        t?.runCatching { join(800) }
    }

    private fun makeRecognizer(): Recognizer {
        val g = grammarJson
        if (g != null && g.length > 8) {
            try {
                return Recognizer(model, SAMPLE_RATE.toFloat(), g)
            } catch (t: Throwable) {
                Log.w(TAG, "grammar 不可用，改自由听写: $t")
            }
        }
        return Recognizer(model, SAMPLE_RATE.toFloat())
    }

    private fun openRecorder(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recBuf = maxOf(min, 16000)
        val sources = if (Build.VERSION.SDK_INT >= 11) intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        ) else intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)
        for (source in sources) {
            val rec = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf)
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            rec.runCatching { release() }
        }
        throw IllegalStateException("麦克风初始化失败")
    }

    fun close() {
        stopListen()
        model?.runCatching { close() }
        model = null
    }

    private fun unpack(dir: File) {
        deleteContents(dir)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建模型目录")
        val zip = ZipInputStream(BufferedInputStream(context.assets.open(ASSET_ZIP), 1 shl 16))
        zip.use {
            val buf = ByteArray(1 shl 16)
            val prefix = "$MODEL_NAME/"
            while (true) {
                val entry = it.nextEntry ?: break
                var name = entry.name.replace('\\', '/')
                if (name.contains("..")) throw IOException("非法模型路径")
                if (name.startsWith(prefix)) name = name.substring(prefix.length)
                if (name.isEmpty()) continue
                val out = File(dir, name)
                val rootPath = dir.canonicalPath
                val outPath = out.canonicalPath
                if (!outPath.startsWith(rootPath + File.separator) && outPath != rootPath) {
                    throw IOException("非法模型路径")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { bos ->
                    while (true) {
                        val n = it.read(buf)
                        if (n <= 0) break
                        bos.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun deleteContents(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) deleteContents(f)
            f.delete()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TAG = "KtvOfflineVoice"
        private const val MODEL_NAME = "vosk-model-small-cn-0.22"
        private const val ASSET_ZIP = "$MODEL_NAME.zip"
        private const val READY_MARK = "am/final.mdl"
        private const val READ_BYTES = 4000

        private fun parseText(raw: String?, finalResult: Boolean): String = try {
            val json = JSONObject(raw ?: "{}")
            json.optString(if (finalResult) "text" else "partial", "")
                .replace(" ", "")
                .replace("[unk]", "")
                .replace("unk", "")
        } catch (_: Exception) {
            ""
        }
    }
}
