package com.local.ktv

import android.os.Build
import android.util.AtomicFile
import java.io.File

/** android.util.AtomicFile 需要 API 17；更低版本退化为普通文件读写。 */
object AtomicFileCompat {
    fun readText(file: File): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            AtomicFile(file).readFully().toString(Charsets.UTF_8)
        } else {
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        }
    }.getOrNull()

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        } else {
            file.writeText(text, Charsets.UTF_8)
        }
    }
}
