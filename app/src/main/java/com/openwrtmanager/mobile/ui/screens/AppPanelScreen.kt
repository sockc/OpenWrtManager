package com.openwrtmanager.mobile.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.openwrtmanager.mobile.model.AppPanelState

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppPanelScreen(
    panel: AppPanelState,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember(panel.title) { mutableStateOf(panel.title) }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onClose()
    }

    Column(modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = {
                    val view = webView
                    if (view?.canGoBack() == true) view.goBack() else onClose()
                }) { Text("返回") }

                Column(Modifier.weight(1f)) {
                    Text(pageTitle, style = MaterialTheme.typography.titleMedium)
                    Text("SSH 隧道管理面板", style = MaterialTheme.typography.bodySmall)
                }

                TextButton(onClick = { webView?.reload() }) { Text("刷新") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false

                    CookieManager.getInstance().setAcceptCookie(true)

                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) pageTitle = title
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val uri = request.url
                            if ((uri.host == "127.0.0.1" || uri.host == "localhost") &&
                                uri.port != panel.localPort
                            ) {
                                val rewritten = uri.buildUpon()
                                    .encodedAuthority("127.0.0.1:${panel.localPort}")
                                    .build()
                                view.loadUrl(rewritten.toString())
                                return true
                            }
                            return false
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            val host = runCatching { Uri.parse(error?.url.orEmpty()).host }.getOrNull()
                            if (host == "127.0.0.1" || host == "localhost") {
                                handler?.proceed()
                            } else {
                                handler?.cancel()
                            }
                        }
                    }

                    loadUrl(panel.url)
                }
            },
            update = { view ->
                webView = view
                if (view.url.isNullOrBlank()) view.loadUrl(panel.url)
            }
        )
    }

    DisposableEffect(panel.localPort) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }
}
