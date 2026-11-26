// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.llm.CodexDeviceCode
import com.example.llm.CodexQuota
import com.example.llm.CodexQuotaWindow
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.ConfigViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun windowLabel(w: CodexQuotaWindow, fallback: String): String = when (w.windowMinutes) {
    null -> fallback
    in 290..310 -> "5-hour limit"
    in 10000..10200 -> "Weekly limit"
    in 43000..44000 -> "Monthly limit"
    else -> when {
        w.windowMinutes >= 2880 -> "${w.windowMinutes / 1440}-day limit"
        w.windowMinutes >= 120 -> "${w.windowMinutes / 60}-hour limit"
        else -> "${w.windowMinutes}-minute limit"
    }
}

private fun resetText(sec: Long?): String = when {
    sec == null -> ""
    sec >= 86_400 -> "resets in ${sec / 86_400} d ${(sec % 86_400) / 3600} h"
    sec >= 3600 -> "resets in ${sec / 3600} h ${(sec % 3600) / 60} min"
    else -> "resets in ${(sec / 60).coerceAtLeast(1)} min"
}

/** Sign in with a ChatGPT plan, keep several accounts, switch between them and see the plan limits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexAccountsScreen(configViewModel: ConfigViewModel, onBack: () -> Unit) {
    val colors = LocalYSCodeColors.current
    val auth = configViewModel.container.codexAuth
    val state by auth.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var device by remember { mutableStateOf<CodexDeviceCode?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val quotas = remember { mutableStateMapOf<String, Result<CodexQuota>>() }
    val loading = remember { mutableStateMapOf<String, Boolean>() }

    fun loadQuota(id: String) {
        scope.launch {
            loading[id] = true
            quotas[id] = auth.fetchQuota(id)
            loading[id] = false
        }
    }

    fun openBrowser(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun startLogin() {
        message = null
        loginJob?.cancel()
        loginJob = scope.launch {
            starting = true
            val dc = auth.startDeviceLogin().getOrElse {
                message = it.message
                starting = false
                return@launch
            }
            starting = false
            device = dc
            clipboard.setText(AnnotatedString(dc.userCode))
            openBrowser(dc.verifyUrl)
            auth.completeDeviceLogin(dc)
                .onSuccess { acc ->
                    device = null
                    message = "Signed in as ${acc.email}"
                    configViewModel.syncCodexProvider()
                    loadQuota(acc.id)
                }
                .onFailure {
                    device = null
                    message = it.message
                }
        }
    }

    LaunchedEffect(state.accounts.map { it.id }) {
        state.accounts.forEach { if (quotas[it.id] == null) loadQuota(it.id) }
    }

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text) }
                },
                title = { Text("ChatGPT plan (Codex)", color = colors.text) }
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
                    "Use the models included in your ChatGPT Plus, Pro or Team plan directly in YSCode chat. " +
                        "Messages count against your plan limits, not API credits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim
                )
            }

            device?.let { dc ->
                item {
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, border = BorderStroke(1.dp, colors.accent), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Approve this sign-in", style = MaterialTheme.typography.titleSmall, color = colors.text)
                            Text("The page opened in your browser. Enter this code there (it is also copied):", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                            Text(dc.userCode, fontFamily = JetBrainsMonoFontFamily, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(dc.userCode)) }) { Text("Copy code") }
                                OutlinedButton(onClick = { openBrowser(dc.verifyUrl) }) { Text("Open page") }
                                TextButton(onClick = { loginJob?.cancel(); device = null }) { Text("Cancel") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                                Text("Waiting for approval...", style = MaterialTheme.typography.labelSmall, color = colors.textDim)
                            }
                        }
                    }
                }
            }

            message?.let { m ->
                item { Text(m, style = MaterialTheme.typography.bodySmall, color = if (m.startsWith("Signed in")) colors.success else colors.danger) }
            }

            items(state.accounts, key = { it.id }) { acc ->
                val active = acc.id == (state.activeId ?: state.accounts.firstOrNull()?.id)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, if (active) colors.accent else colors.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (active) Icon(Icons.Outlined.CheckCircle, "Active", tint = colors.accent, modifier = Modifier.size(20.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(acc.email, style = MaterialTheme.typography.titleSmall, color = colors.text)
                                Text(
                                    "${acc.plan.replaceFirstChar { it.uppercase() }} plan" + if (active) " - in use" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textDim
                                )
                            }
                            IconButton(onClick = { loadQuota(acc.id) }) {
                                if (loading[acc.id] == true) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                                else Icon(Icons.Outlined.Refresh, "Refresh usage", tint = colors.textDim)
                            }
                            IconButton(onClick = { auth.remove(acc.id); quotas.remove(acc.id) }) {
                                Icon(Icons.Outlined.Delete, "Remove account", tint = colors.danger)
                            }
                        }
                        val q = quotas[acc.id]
                        q?.fold(
                            onSuccess = { quota ->
                                listOfNotNull(quota.primary, quota.secondary).forEachIndexed { i, w ->
                                    QuotaBar(windowLabel(w, if (i == 0) "Short window" else "Long window"), w.usedPercent, resetText(w.resetsInSec))
                                }
                                if (quota.primary == null && quota.secondary == null) {
                                    Text("No limit information for this plan.", style = MaterialTheme.typography.labelSmall, color = colors.textDim)
                                }
                            },
                            onFailure = { Text(it.message ?: "Usage unavailable", style = MaterialTheme.typography.labelSmall, color = colors.textDim) }
                        )
                        if (!active) {
                            Button(onClick = { auth.setActive(acc.id); message = "Now using ${acc.email}" }) { Text("Use this account") }
                        }
                    }
                }
            }

            item {
                Button(onClick = { startLogin() }, enabled = !starting && device == null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.accounts.isEmpty()) "Sign in with ChatGPT" else "Add another account")
                }
            }

            item {
                Text(
                    "This uses the same sign-in as the Codex CLI. It is not an official OpenAI integration for third-party apps, so it can change or stop working. " +
                        "Pick a model named gpt-5.x in chat; add the ChatGPT Codex provider as default in Settings if you want it everywhere.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textDim
                )
            }
        }
    }
}

@Composable
private fun QuotaBar(label: String, usedPercent: Double, reset: String) {
    val colors = LocalYSCodeColors.current
    val used = usedPercent.coerceIn(0.0, 100.0)
    val left = (100.0 - used).toInt()
    val barColor = when {
        used >= 90 -> colors.danger
        used >= 70 -> colors.warn
        else -> colors.accent
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.text)
            Text("$left% left" + if (reset.isNotBlank()) " - $reset" else "", style = MaterialTheme.typography.labelSmall, color = colors.textDim)
        }
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.border)) {
            Box(modifier = Modifier.fillMaxWidth((used / 100.0).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(barColor))
        }
    }
}
