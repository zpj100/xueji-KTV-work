package com.local.ktv

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 离线语音（vosk）。
 *
 * 关键：所有 org.vosk.* 类型都被隔离在 [VoskBridge] 内部，OfflineVoice 本身
 * 不引用任何 vosk 类型。这样在 Android 4.x（API < 21）上，vosk 的类和原生库
 * 永远不会被加载/初始化，避免 UnsatisfiedLinkError 导致启动闪退。
 */
class OfflineVoice(context: Context) {
    fun interface Callback {
        fun onText(text: String)
    }

    private val context = context.applicationContext
    private var model: Any? = null
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "Android 5.0 以下不支持离线语音，已跳过")
            return false
        }
        if (model != null) return true
        return try {
            VoskBridge.setLogLevelWarnings()
            val dir = File(context.filesDir, MODEL_NAME)
            if (!File(dir, READY_MARK).isFile) unpack(dir)
            model = VoskBridge.loadModel(dir.absolutePath)
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
            var recognizer: Any? = null
            var recorder: AudioRecord? = null
            var ver = -1
            try {
                recorder = openRecorder()
                val buf = ByteArray(READ_BYTES)
                recorder.startRecording()
                while (listenRunning) {
                    if (recognizer == null || ver != grammarVer) {
                        VoskBridge.closeRecognizer(recognizer)
                        ver = grammarVer
                        recognizer = makeRecognizer()
                    }
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val uttered = VoskBridge.acceptWaveForm(recognizer, buf, n)
                    if (!uttered) continue
                    val text = parseText(VoskBridge.getResult(recognizer), true)
                    if (text.isNotEmpty()) cb?.onText(text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "常驻听写结束: $t")
            } finally {
                recorder?.runCatching { stop() }
                recorder?.runCatching { release() }
                VoskBridge.closeRecognizer(recognizer)
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

    private fun makeRecognizer(): Any? {
        val g = grammarJson
        if (g != null && g.length > 8) {
            try {
                return VoskBridge.createRecognizer(model, SAMPLE_RATE, g)
            } catch (t: Throwable) {
                Log.w(TAG, "grammar 不可用，改自由听写: $t")
            }
        }
        return VoskBridge.createRecognizer(model, SAMPLE_RATE, null)
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
        VoskBridge.closeModel(model)
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

    /**
     * vosk 桥接层。所有 org.vosk.* 引用都集中在此对象内，
     * 仅在 prepare() 通过 SDK 检查后才会被加载。
     */
    private object VoskBridge {
        fun setLogLevelWarnings() {
            org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
        }

        fun loadModel(path: String): Any = org.vosk.Model(path)

        fun closeModel(model: Any?) {
            (model as? org.vosk.Model)?.runCatching { close() }
        }

        fun createRecognizer(model: Any?, sampleRate: Int, grammar: String?): Any? =
            if (grammar != null) org.vosk.Recognizer(model as org.vosk.Model, sampleRate.toFloat(), grammar)
            else org.vosk.Recognizer(model as org.vosk.Model, sampleRate.toFloat())

        fun closeRecognizer(recognizer: Any?) {
            (recognizer as? org.vosk.Recognizer)?.runCatching { close() }
        }

        fun acceptWaveForm(recognizer: Any?, buf: ByteArray, n: Int): Boolean =
            (recognizer as? org.vosk.Recognizer)?.acceptWaveForm(buf, n) == true

        fun getResult(recognizer: Any?): String? =
            (recognizer as? org.vosk.Recognizer)?.result
    }
}
