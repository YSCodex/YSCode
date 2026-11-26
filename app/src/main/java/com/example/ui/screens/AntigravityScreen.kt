// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.llm.AgyAccount
import com.example.llm.AgyLogin
import com.example.llm.AgyState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.ConfigViewModel
import kotlinx.coroutines.launch

/** Set up the official Antigravity CLI (agy) in three steps: install, sign in, use it in chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntigravityScreen(configViewModel: ConfigViewModel, onBack: () -> Unit) {
    val colors = LocalYSCodeColors.current
    val agy = configViewModel.container.agyManager
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    val log by agy.log.collectAsStateWithLifecycle()
    val busy by agy.busy.collectAsStateWithLifecycle()

    var state by remember { mutableStateOf<AgyState?>(null) }
    var accounts by remember { mutableStateOf<List<AgyAccount>>(emptyList()) }
    var login by remember { mutableStateOf<AgyLogin?>(null) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var loginOutput by remember { mutableStateOf<List<String>>(emptyList()) }
    var loginResult by remember { mutableStateOf<Boolean?>(null) }
    var pasteCode by remember { mutableStateOf("") }
    var usage by remember { mutableStateOf<String?>(null) }
    var autoApprove by remember { mutableStateOf(agy.autoApprove) }
    var working by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var diag by remember { mutableStateOf<List<String>?>(null) }
    var showAccounts by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showScreen by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            state = agy.check()
            accounts = agy.accounts()
        }
    }

    fun openBrowser(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(login) {
        val l = login ?: return@LaunchedEffect
        launch { l.url.collect { u -> loginUrl = u; if (u != null && u.contains("accounts.google.com")) openBrowser(u) } }
        launch { l.output.collect { loginOutput = it } }
        l.result.collect { r ->
            loginResult = r
            if (r == true) {
                refresh()
                configViewModel.syncAgyProvider()
                info = "Signed in. Pick an Antigravity model in chat."
            }
        }
    }

    val installed = state?.installed == true
    val signedIn = state?.signedIn == true
    val loginRunning = login != null && loginResult == null

    @Composable
    fun Step(n: Int, title: String, done: Boolean, enabled: Boolean = true, content: @Composable () -> Unit) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, if (done) colors.success.copy(alpha = 0.5f) else colors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier.size(24.dp).let { it },
                        contentAlignment = Alignment.Center
                    ) {
                        if (done) Icon(Icons.Outlined.CheckCircle, null, tint = colors.success, modifier = Modifier.size(24.dp))
                        else Surface(shape = CircleShape, color = if (enabled) colors.accent else colors.border, modifier = Modifier.size(24.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("$n", color = colors.bg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.text else colors.textDim)
                }
                if (enabled) content()
            }
        }
    }

    @Composable
    fun Collapsible(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
        Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = colors.textDim)
                }
                if (open) Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
        }
    }

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text) } },
                title = { Text("Antigravity (Google)", color = colors.text) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Use your Google Antigravity plan in YSCode chat. YSCode runs Google's official agy CLI; you sign in with Google yourself and YSCode never sees your login.",
                    style = MaterialTheme.typography.bodySmall, color = colors.textDim
                )
            }

            item {
                Step(1, if (installed) "Installed: agy ${state?.version}" else "Install agy", installed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { scope.launch { state = agy.install(); refresh() } }, enabled = !busy) {
                            Text(if (installed) "Update" else "Install agy")
                        }
                        if (installed) OutlinedButton(onClick = {
                            scope.launch { working = true; info = if (agy.verify()) "Session is valid." else "agy does not accept the login. Sign in again."; working = false }
                        }, enabled = !working && !busy) { Text("Check session") }
                        if (busy || working) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                    }
                    if (busy && log.isNotEmpty()) {
                        Text(log.last().take(120), fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = colors.textDim)
                    }
                    if (!installed && !busy && log.isNotEmpty()) {
                        SelectionContainer {
                            Text(log.takeLast(6).joinToString("\n") { it.take(140) }, fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = colors.textDim)
                        }
                    }
                }
            }

            item {
                var share by remember { mutableStateOf(false) }
                Step(2, if (signedIn) "Signed in with Google" else "Sign in with Google", signedIn, enabled = installed) {
                    if (!loginRunning) {
                        if (!signedIn) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Share usage data with Google", color = colors.text, style = MaterialTheme.typography.bodyMedium)
                                    Text("Optional. Off unless you turn it on.", color = colors.textDim, style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(checked = share, onCheckedChange = { share = it })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                loginUrl = null; loginOutput = emptyList(); loginResult = null; pasteCode = ""; showScreen = false
                                login = agy.startLogin(scope).also { it.shareData = share }
                            }) { Text(if (signedIn) "Sign in again" else "Sign in with Google") }
                            if (signedIn) OutlinedButton(onClick = { scope.launch { agy.signOut(); refresh() } }) { Text("Sign out") }
                        }
                        if (loginResult == false) Text("Sign-in did not finish. Tap Sign in again.", color = colors.danger, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                            Text(
                                when {
                                    loginUrl == null -> "Starting sign-in..."
                                    login?.codeSent != true -> "Sign in in the browser, then paste the code here"
                                    else -> "Finishing setup, about 20 seconds..."
                                },
                                style = MaterialTheme.typography.bodySmall, color = colors.textDim
                            )
                        }
                        loginUrl?.let { u ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { openBrowser(u) }) { Text("Open page") }
                                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(u)) }) { Text("Copy link") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = pasteCode, onValueChange = { pasteCode = it }, singleLine = true,
                                    label = { Text("Code from Google") }, modifier = Modifier.weight(1f)
                                )
                                Button(onClick = { login?.send(pasteCode.trim()); pasteCode = "" }, enabled = pasteCode.isNotBlank()) { Text("Send") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showScreen = !showScreen }) { Text(if (showScreen) "Hide agy screen" else "Show agy screen") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { login?.cancel(); login = null }) { Text("Cancel", color = colors.danger, maxLines = 1, softWrap = false) }
                        }
                        if (showScreen) {
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Enter" to "\r\n", "Up" to "\u001B[A", "Down" to "\u001B[B", "Left" to "\u001B[D", "Right" to "\u001B[C", "Tab" to "\t", "Space" to " ", "Esc" to "\u001B").forEach { (label, key) ->
                                    OutlinedButton(onClick = { login?.sendRaw(key) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label, maxLines = 1, softWrap = false) }
                                }
                            }
                            Surface(shape = RoundedCornerShape(8.dp), color = colors.bg, modifier = Modifier.fillMaxWidth()) {
                                SelectionContainer {
                                    Text(loginOutput.takeLast(12).joinToString("\n") { it.take(140) }, fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = colors.textDim, modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Step(3, "Use it in chat", false, enabled = signedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Let agy edit files and run commands without asking", color = colors.text, style = MaterialTheme.typography.bodyMedium)
                            Text("Recommended: there is nobody to tap Approve while agy runs in the background.", color = colors.textDim, style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(checked = autoApprove, onCheckedChange = { autoApprove = it; agy.autoApprove = it })
                    }
                    Button(onClick = {
                        working = true
                        configViewModel.syncAgyProvider()
                        scope.launch { kotlinx.coroutines.delay(2500); working = false; info = "Done. Pick an Antigravity model in chat (model chip at the top)." }
                    }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Add / refresh models in chat") }
                }
            }

            info?.let { m -> item { Text(m, style = MaterialTheme.typography.bodySmall, color = colors.success) } }

            if (installed) {
                item {
                    Collapsible("Accounts (switch Google account)", showAccounts, { showAccounts = !showAccounts }) {
                        Text("Sign in, tap Save, then sign in with another account and save it too. Switch with Use.", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                        accounts.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(a.name, color = colors.text, style = MaterialTheme.typography.bodyMedium)
                                    if (a.active) Text("in use", color = colors.accent, style = MaterialTheme.typography.labelSmall)
                                }
                                if (!a.active) OutlinedButton(onClick = { scope.launch { agy.useAccount(a.name); refresh(); info = "Switched to ${a.name}" } }) { Text("Use") }
                                TextButton(onClick = { scope.launch { agy.removeAccount(a.name); refresh() } }) { Text("Remove", color = colors.danger) }
                            }
                        }
                        OutlinedButton(
                            onClick = { scope.launch { val n = agy.saveCurrentAccount(); info = if (n != null) "Saved current login as $n" else "Nothing to save. Sign in first."; refresh() } },
                            enabled = signedIn, modifier = Modifier.fillMaxWidth()
                        ) { Text("Save current login") }
                    }
                }
                item {
                    Collapsible("Usage and diagnostics", showAdvanced, { showAdvanced = !showAdvanced }) {
                        OutlinedButton(onClick = { scope.launch { working = true; usage = agy.usage(); working = false } }, enabled = signedIn && !working) { Text("Check usage / quota") }
                        usage?.let { SelectionContainer { Text(it, fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp, color = colors.text) } }
                        OutlinedButton(onClick = { diag = agy.lastRun }) { Text("Show last agy output") }
                        diag?.let { d ->
                            Surface(shape = RoundedCornerShape(8.dp), color = colors.bg, modifier = Modifier.fillMaxWidth()) {
                                SelectionContainer {
                                    Text(
                                        if (d.isEmpty()) "Nothing yet. Send a message with an Antigravity model first." else d.takeLast(40).joinToString("\n") { it.take(300) },
                                        fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = colors.textDim, modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                        Text("YSCode $appVersion", style = MaterialTheme.typography.labelSmall, color = colors.textDim)
                    }
                }
            }

            item {
                Text(
                    "Antigravity is Google's product. Use it with your own account and within Google's terms. YSCode starts the official CLI and does not call Google's servers itself.",
                    style = MaterialTheme.typography.labelSmall, color = colors.textDim
                )
            }
        }
    }
}
