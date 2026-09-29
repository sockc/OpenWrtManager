package com.openwrtmanager.mobile.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openwrtmanager.mobile.model.AppPanelState

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppPanelScreen(
    panel: AppPanelState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember(panel.title) { mutableStateOf(panel.title) }
    var progress by remember(panel.localPort) { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageError by remember(panel.localPort) { mutableStateOf("") }
    var blockedUrl by remember { mutableStateOf("") }
    var retryIssued by remember(panel.localPort) { mutableStateOf(false) }

    fun refreshNavState(view: WebView?) {
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) {
            view.goBack()
            refreshNavState(view)
        } else {
            onClose()
        }
    }

    Column(modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(onClick = {
                        val view = webView
                        if (view?.canGoBack() == true) {
                            view.goBack()
                            refreshNavState(view)
                        } else {
                            onClose()
                        }
                    }) { Text("返回") }

                    TextButton(
                        onClick = {
                            webView?.goForward()
                            refreshNavState(webView)
                        },
                        enabled = canGoForward
                    ) { Text("前进") }

                    Column(Modifier.weight(1f)) {
                        Text(pageTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            if (panel.remoteScheme.equals("https", true)) {
                                "SSH 隧道 · HTTPS"
                            } else {
                                "SSH 隧道 · HTTP"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    TextButton(onClick = { webView?.reload() }) { Text("刷新") }
                    TextButton(onClick = onClose) { Text("关闭") }
                }

                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (pageError.isNotBlank()) {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("面板连接异常", style = MaterialTheme.typography.titleSmall)
                    Text(pageError, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            pageError = ""
                            onRetry()
                        }) { Text("重建 SSH 隧道") }
                        TextButton(onClick = { webView?.reload() }) { Text("重新加载") }
                    }
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).also { view ->
                    webView = view
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.settings.databaseEnabled = true
                    view.settings.allowFileAccess = false
                    view.settings.allowContentAccess = false
                    view.settings.setSupportZoom(true)
                    view.settings.builtInZoomControls = true
                    view.settings.displayZoomControls = false
                    view.settings.javaScriptCanOpenWindowsAutomatically = false
                    view.settings.setSupportMultipleWindows(false)

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(view, true)
                    }

                    view.webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) pageTitle = title
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress.coerceIn(0, 100)
                            refreshNavState(view)
                        }
                    }

                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val uri = request.url
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            if (scheme != "http" && scheme != "https") {
                                blockedUrl = uri.toString()
                                return true
                            }

                            val isLocal = uri.host == "127.0.0.1" || uri.host == "localhost"
                            if (!isLocal) {
                                blockedUrl = uri.toString()
                                return true
                            }

                            if (uri.port != panel.localPort) {
                                val rewritten = uri.buildUpon()
                                    .scheme(panel.remoteScheme)
                                    .encodedAuthority("127.0.0.1:${panel.localPort}")
                                    .build()
                                view.loadUrl(rewritten.toString())
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            progress = 100
                            pageError = ""
                            refreshNavState(view)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame != true) return
                            pageError = error?.description?.toString().orEmpty().ifBlank {
                                "管理面板无法加载"
                            }
                            if (!retryIssued) {
                                retryIssued = true
                                onRetry()
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            val host = runCatching {
                                Uri.parse(error?.url.orEmpty()).host
                            }.getOrNull()
                            if (host == "127.0.0.1" || host == "localhost") {
                                handler?.proceed()
                            } else {
                                handler?.cancel()
                            }
                        }
                    }

                    view.loadUrl(panel.url)
                }
            },
            update = { view ->
                webView = view
                refreshNavState(view)
                if (view.url.isNullOrBlank()) view.loadUrl(panel.url)
            }
        )
    }

    if (blockedUrl.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { blockedUrl = "" },
            title = { Text("已阻止外部跳转") },
            text = {
                Text(
                    "管理面板尝试打开 SSH 隧道之外的地址。为避免插件页面把管理会话带到未知公网地址，APP 已阻止该跳转。"
                )
            },
            confirmButton = {
                TextButton(onClick = { blockedUrl = "" }) { Text("知道了") }
            }
        )
    }

    DisposableEffect(panel.localPort) {
        onDispose {
            CookieManager.getInstance().flush()
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }
}
