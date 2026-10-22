package com.example.antigravity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.antigravity.server.LocalServer
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var localServer: LocalServer
    private lateinit var webView: WebView
    private lateinit var webAppInterface: WebAppInterface

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize local server for RPCs
        localServer = LocalServer(this)
        localServer.start()

        // Setup WebAppInterface bridge
        webAppInterface = WebAppInterface(this)
        intent?.data?.let { uri ->
            webAppInterface.setPendingDeepLink(uri.toString())
        }

        // Setup WebView
        WebView.setWebContentsDebuggingEnabled(true)
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#131313"))

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            addJavascriptInterface(webAppInterface, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                // Intercept static assets to guarantee instant, 100% reliable zero-copy loading without TCP socket issues
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url ?: return null
                    val path = url.path ?: ""

                    try {
                        when {
                            path == "/" || path == "/index.html" -> {
                                return WebResourceResponse("text/html", "UTF-8", assets.open("web/index.html"))
                            }
                            path == "/main.js" -> {
                                return WebResourceResponse("application/javascript", "UTF-8", assets.open("web/main.js"))
                            }
                            path == "/jetbox.css" -> {
                                return WebResourceResponse("text/css", "UTF-8", assets.open("web/jetbox.css"))
                            }
                            path == "/compiled_tailwind.css" -> {
                                return WebResourceResponse("text/css", "UTF-8", assets.open("web/compiled_tailwind.css"))
                            }
                            path == "/prism_bundle.js" -> {
                                return WebResourceResponse("application/javascript", "UTF-8", assets.open("web/prism_bundle.js"))
                            }
                            path == "/audio_processor.js" -> {
                                return WebResourceResponse("application/javascript", "UTF-8", assets.open("web/audio_processor.js"))
                            }
                            path == "/favicon.ico" -> {
                                return WebResourceResponse("image/x-icon", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error intercepting asset $path", e)
                    }

                    // RPC calls and others pass through to LocalServer or network
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
                        return false
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    return true
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    Log.e(TAG, "WebView error on ${request?.url}: ${error?.description}")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.i("WebConsole", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
                    return true
                }
            }
        }

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#131313"))
            addView(webView)
        }
        setContentView(rootLayout)

        // Load the local server
        val targetUrl = "${localServer.baseUrl}/index.html"
        Log.i(TAG, "Loading URL: $targetUrl")
        webView.loadUrl(targetUrl)

        // Back button navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Request storage permissions asynchronously after UI has initiated
        requestStoragePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            webAppInterface.setPendingDeepLink(uri.toString())
            webView.evaluateJavascript("window.deepLink && window.deepLink.__trigger && window.deepLink.__trigger('${uri}');", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        localServer.stop()
        webView.destroy()
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
            }
        }
    }
}
