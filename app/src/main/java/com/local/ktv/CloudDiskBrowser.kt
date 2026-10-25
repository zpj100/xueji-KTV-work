package com.local.ktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService

class CloudDiskBrowser(
    private val activity: Activity,
    private val executor: ExecutorService,
    private val onChanged: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = CloudDiskClient(activity)
    private val dialogs = mutableListOf<AlertDialog>()

    fun client(): CloudDiskClient = client

    fun close() {
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
        main.removeCallbacksAndMessages(null)
    }

    fun showHub() {
        val status = client.status()
        val items = arrayOf(
            "夸克网盘  ${loginHint("quark", status)}",
            "百度网盘  ${loginHint("baidu", status)}",
            "阿里云盘  ${loginHint("aliyun", status)}",
        )
        present(
            AlertDialog.Builder(activity)
                .setTitle("网盘登录与下载")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> showProvider("quark")
                        1 -> showProvider("baidu")
                        2 -> showProvider("aliyun")
                    }
                }
                .setNegativeButton("关闭", null)
                .create(),
        )
    }

    fun openLogin(provider: String) {
        val extras = CloudDiskClient.loginIntentExtras(provider) ?: run {
            toast("暂不支持该网盘登录")
            return
        }
        val intent = Intent(activity, WebLoginActivity::class.java)
            .putExtra("provider", provider)
            .putExtra("title", extras.first)
            .putExtra("url", extras.second)
            .putExtra("domains", extras.third.first)
            .putExtra("storage", extras.third.second)
        runCatching { activity.startActivityForResult(intent, REQ_LOGIN) }
            .onFailure { toast("无法打开登录窗口") }
    }

    fun applyLogin(raw: String): String = client.applyLogin(raw)

    private fun showProvider(provider: String) {
        val title = providerTitle(provider)
        val logged = client.loginLabel(provider) == "已登录"
        val actions = mutableListOf<String>()
        if (logged) {
            actions += "浏览文件"
            if (provider == "quark") actions += "打开分享链接"
            actions += "重新登录"
        } else {
            actions += "登录"
            if (provider == "quark") actions += "打开分享链接"
        }
        present(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(actions.toTypedArray()) { _, which ->
                    when (actions[which]) {
                        "浏览文件" -> browse(provider, null)
                        "打开分享链接" -> askQuarkShare()
                        "登录", "重新登录" -> openLogin(provider)
                    }
                }
                .setNegativeButton("返回") { _, _ -> showHub() }
                .create(),
        )
    }

    private fun askQuarkShare() {
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = "粘贴夸克分享链接"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(client.kv("cloud_quark_share"))
        }
        present(
            AlertDialog.Builder(activity)
                .setTitle("夸克分享")
                .setView(wrap(input))
                .setPositiveButton("打开") { _, _ ->
                    val raw = input.text.toString().trim()
                    if (raw.isEmpty()) {
                        toast("先粘贴夸克分享链接")
                        return@setPositiveButton
                    }
                    busy("正在打开分享…")
                    executor.execute {
                        val result = runCatching {
                            val parsed = client.saveQuarkShare(raw)
                            parsed to client.list("quark", parsed.optString("pdir", "0"))
                        }
                        main.post {
                            result.onSuccess { (parsed, rows) ->
                                toast("已打开夸克分享")
                                showRows("quark", parsed.optString("pdir", "0"), rows)
                            }.onFailure { toast(it.message ?: "打开分享失败") }
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .create(),
        )
    }

    private fun browse(provider: String, folder: String?) {
        busy("正在读取${providerTitle(provider)}…")
        executor.execute {
            val result = runCatching { client.list(provider, folder) }
            main.post {
                result.onSuccess { showRows(provider, folder, it) }
                    .onFailure { toast(it.message ?: "读取网盘失败") }
            }
        }
    }

    private fun showRows(provider: String, folder: String?, rows: JSONArray) {
        if (rows.length() == 0) {
            toast("该目录没有音视频")
            return
        }
        val labels = Array(rows.length()) { i ->
            val it = rows.getJSONObject(i)
            val mark = if (it.optBoolean("isdir")) "文件夹" else "歌曲"
            "${it.optString("name")}  ·  $mark"
        }
        present(
            AlertDialog.Builder(activity)
                .setTitle(providerTitle(provider))
                .setItems(labels) { _, which ->
                    val item = rows.getJSONObject(which)
                    if (item.optBoolean("isdir")) {
                        browse(provider, item.optString("open").ifEmpty { item.optString("id") })
                    } else {
                        download(provider, item)
                    }
                }
                .setNegativeButton("返回") { _, _ ->
                    if (folder.isNullOrEmpty() || folder == "/" || folder == "root" || folder == "0") showProvider(provider)
                    else showProvider(provider)
                }
                .create(),
        )
    }

    private fun download(provider: String, item: JSONObject) {
        val name = item.optString("name")
        busy("正在下载 $name …")
        executor.execute {
            val result = runCatching { client.download(provider, item.optString("id"), name) }
            main.post {
                result.onSuccess {
                    toast("已下载到 ${it.name}")
                    onChanged()
                }.onFailure { toast(it.message ?: "下载失败") }
            }
        }
    }

    private fun loginHint(provider: String, status: JSONObject): String {
        val key = when (provider) {
            "quark" -> "quark_login"
            "baidu" -> "baidu_login"
            "aliyun" -> "aliyun_login"
            else -> provider
        }
        return if (status.optBoolean(key) || status.optBoolean(provider)) "已登录" else "未登录"
    }

    private fun providerTitle(provider: String): String = when (provider) {
        "quark" -> "夸克网盘"
        "baidu" -> "百度网盘"
        "aliyun" -> "阿里云盘"
        else -> "网盘"
    }

    private fun busy(message: String) = toast(message)

    private fun wrap(view: EditText) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 12)
        addView(TextView(activity).apply { text = "登录后可浏览并下载歌曲到本机曲库" })
        addView(view)
    }

    private fun present(dialog: AlertDialog) {
        dialogs.removeAll { !it.isShowing }
        dialogs.add(dialog)
        dialog.show()
        dialog.window?.decorView?.let(TvFocusStyler::installTree)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .mapNotNull { dialog.getButton(it) }.forEach(TvFocusStyler::installAction)
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_LOGIN = 74
    }
}
