package com.local.ktv

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class WebLoginActivity : Activity() {
    private var webView: WebView? = null
    private var provider = ""
    private var domains = JSONArray()
    private var storageKeys = ""
    private var urlBox: EditText? = null
    private var desktopUA = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        provider = intent.getStringExtra("provider").orEmpty()
        domains = runCatching { JSONArray(intent.getStringExtra("domains")) }.getOrDefault(JSONArray())
        storageKeys = intent.getStringExtra("storage").orEmpty()
        var url = intent.getStringExtra("url").orEmpty()
        if (url.isEmpty()) { finish(); return }
        val title = intent.getStringExtra("title") ?: "登录"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0b1230"))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#111a3d"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        bar.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val uaBtn = Button(this).apply { text = "手机版"; textSize = 12f }
        uaBtn.setOnClickListener {
            desktopUA = !desktopUA
            uaBtn.text = if (desktopUA) "手机版" else "电脑版"
            applyUA()
            webView?.reload()
        }
        bar.addView(uaBtn)
        bar.addView(Button(this).apply {
            text = "完成登录"
            textSize = 13f
            setOnClickListener { captureAndFinish() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val addrBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0e1633"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        urlBox = EditText(this).apply {
            setText(url)
            setTextColor(Color.WHITE)
            textSize = 12f
            isSingleLine = true
            setBackgroundColor(Color.parseColor("#1b2547"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addrBar.addView(urlBox)
        addrBar.addView(Button(this).apply {
            text = "打开"
            textSize = 12f
            setOnClickListener {
                var u = urlBox?.text?.toString()?.trim().orEmpty()
                if (u.isEmpty()) return@setOnClickListener
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                webView?.loadUrl(u)
            }
        })
        root.addView(addrBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "若只看到下载页，点右上角「手机版 / 电脑版」切换，或在网址栏直接打开网盘页面。登录后用双指放大可看清二维码。"
            setTextColor(Color.parseColor("#8fa3d6"))
            textSize = 11f
            setPadding(dp(12), dp(4), dp(12), dp(6))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val holder = FrameLayout(this)
        val view = WebView(this)
        webView = view
        holder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val s = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.allowContentAccess = true
        runCatching { s.allowFileAccess = true }
        if (Build.VERSION.SDK_INT >= 17) s.mediaPlaybackRequiresUserGesture = false
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        applyUA()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, u: String): Boolean {
                urlBox?.setText(u)
                return false
            }
        }
        view.webChromeClient = WebChromeClient()
        view.loadUrl(url)
    }

    private fun applyUA() {
        val s = webView?.settings ?: return
        s.userAgentString = if (desktopUA) UA_DESKTOP else null
        if (desktopUA) {
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        }
    }

    private fun captureAndFinish() {
        runCatching { CookieManager.getInstance().flush() }
        val cookie = JSONObject()
        runCatching {
            val cm = CookieManager.getInstance()
            for (i in 0 until domains.length()) {
                val d = domains.optString(i, "")
                if (d.isEmpty()) continue
                val c = cm.getCookie("https://$d")
                if (!c.isNullOrEmpty()) cookie.put(d, c)
            }
        }
        if (Build.VERSION.SDK_INT < 19 || webView == null) {
            deliver(cookie, JSONObject())
            return
        }
        val keys = if (storageKeys.isEmpty()) "[]" else storageKeys
        val js = "(function(){try{var o={};var ks=$keys;" +
            "for(var i=0;i<ks.length;i++){var v=localStorage.getItem(ks[i]);if(v)o[ks[i]]=v;}" +
            "for(var j=0;j<localStorage.length;j++){var k=localStorage.key(j);var val=localStorage.getItem(k);" +
            "if(val&&val.length<40000)o[k]=val;}" +
            "return JSON.stringify(o);}catch(e){return '{}';}})()"
        try {
            webView!!.evaluateJavascript(js) { value ->
                var storage = JSONObject()
                runCatching {
                    if (!value.isNullOrEmpty() && value != "null" && value.length > 2) {
                        val inner = JSONTokener(value).nextValue()
                        if (inner is String) storage = JSONObject(inner)
                    }
                }
                deliver(cookie, storage)
            }
        } catch (_: Throwable) {
            deliver(cookie, JSONObject())
        }
    }

    private fun deliver(cookie: JSONObject, storage: JSONObject) {
        val out = JSONObject()
            .put("provider", provider)
            .put("cookie", cookie)
            .put("storage", storage)
        intent.putExtra("result", out.toString())
        setResult(RESULT_LOGIN, intent)
        val tip = if (cookie.length() == 0 && storage.length() == 0) {
            "没抓到登录信息，请确认已登录后再点完成"
        } else {
            "已获取登录信息"
        }
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
            return
        }
        captureAndFinish()
    }

    override fun onDestroy() {
        webView?.runCatching { destroy() }
        webView = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val RESULT_LOGIN = 4242
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
