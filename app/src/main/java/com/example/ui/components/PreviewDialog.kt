// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.preview.PreviewServer
import com.example.ui.theme.LocalYSCodeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen browser for the app the agent built. If nothing listens on the port, the project folder is served as a static site. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewDialog(initialUrl: String, projectDir: File?, onDismiss: () -> Unit) {
    val colors = LocalYSCodeColors.current
    val context = LocalContext.current
    var typed by remember { mutableStateOf(initialUrl) }
    var target by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }

    suspend fun resolve(input: String): String {
        val url = if (input.startsWith("http://") || input.startsWith("https://")) input else "http://$input"
        val uri = Uri.parse(url)
        val host = uri.host ?: return url
        if (host != "localhost" && host != "127.0.0.1" && host != "0.0.0.0") return url
        val port = if (uri.port > 0) uri.port else 80
        return withContext(Dispatchers.IO) {
            if (PreviewServer.isReachable(port)) {
                note = null
                "http://127.0.0.1:$port" + (uri.encodedPath ?: "/")
            } else if (projectDir != null && projectDir.isDirectory) {
                val used = PreviewServer.serve(projectDir, port)
                note = "Nothing was running on port $port, so your project folder is shown as a static site."
                "http://127.0.0.1:$used" + (uri.encodedPath ?: "/")
            } else {
                note = "Nothing is running on port $port."
                url
            }
        }
    }

    LaunchedEffect(typed, reloadTick) {
        error = null
        target = runCatching { resolve(typed) }.getOrElse { error = "Could not open: ${it.message}"; null }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = androidx.compose.ui.graphics.Color(0xFF0E0F11), modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close", tint = colors.text) }
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { reloadTick++ }),
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { reloadTick++; web?.reload() }) { Icon(Icons.Outlined.Refresh, "Reload", tint = colors.text) }
                    IconButton(onClick = {
                        target?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    }) { Icon(Icons.Outlined.Language, "Open in browser", tint = colors.text) }
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textDim, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) }
                error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.danger, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.parseColor("#0E0F11"))
                            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                    error = "The page crashed. Tap the globe icon to open it in your browser instead."
                                    web = null
                                    return true
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                    if (request.isForMainFrame) error = "Could not load the page: ${err.description}"
                                }
                            }
                            web = this
                        }
                    },
                    update = { v ->
                        val t = target
                        if (t != null && v.tag != t + reloadTick) { v.tag = t + reloadTick; v.loadUrl(t) }
                    }
                )
            }
        }
    }
}
