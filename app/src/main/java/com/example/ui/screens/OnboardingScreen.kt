// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.llm.ProviderPreset
import com.example.llm.ProviderPresets
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.ConfigViewModel
import com.example.ui.viewmodel.MainViewModel

@Composable
fun OnboardingScreen(
    mainViewModel: MainViewModel,
    configViewModel: ConfigViewModel,
    onFinish: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    var step by remember { mutableIntStateOf(0) }
    val setupState by mainViewModel.setupState.collectAsStateWithLifecycle()
    val testResult by configViewModel.testResult.collectAsStateWithLifecycle()
    val isTesting by configViewModel.isTesting.collectAsStateWithLifecycle()

    val rootfsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) mainViewModel.importRootfsTarGz(uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Step Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "YSCode Setup (${step + 1}/3)",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accent
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { idx ->
                        Box(
                            modifier = Modifier
                                .size(width = if (idx == step) 24.dp else 8.dp, height = 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (idx <= step) colors.accent else colors.border)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.img_yscode_hero_1794571916149),
                                contentDescription = "YSCode workspace banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            Text(
                                text = "YSCode",
                                style = MaterialTheme.typography.headlineLarge,
                                color = colors.text
                            )
                            Text(
                                text = "Fully local. No account. No backend.",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.accent
                            )
                            Text(
                                text = "An all-in-one agentic coding studio on your phone — embedded shell, multi-provider streaming LLM loop, file & diff reviewer, git helper, skills, and MCP servers.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textDim
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = colors.surface,
                                border = BorderStroke(1.dp, colors.border)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("What stays 100% on-device:", style = MaterialTheme.typography.titleSmall, color = colors.text)
                                    Text("• All projects, git repos, sessions, and checkpoints", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                                    Text("• API keys & tokens encrypted via Android Keystore", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                                    Text("• Direct connections only to your chosen LLM or MCP URLs", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                                }
                            }
                        }
                    }

                    1 -> {
                        LaunchedEffect(Unit) {
                            if (!setupState.completed && !setupState.isRunning) {
                                mainViewModel.initializeLinuxEnvironment(installPackages = true)
                            }
                        }
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.Terminal, null, tint = colors.accent)
                                Text(
                                    text = "Setting up Linux Environment",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.text
                                )
                            }
                            var lastLogCount by remember { mutableStateOf(0) }
                            var lastChange by remember { mutableStateOf(0L) }
                            var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
                            LaunchedEffect(setupState.logs.size, setupState.logs.lastOrNull()) {
                                if (setupState.logs.size != lastLogCount || lastChange == 0L) { lastLogCount = setupState.logs.size; lastChange = System.currentTimeMillis() }
                            }
                            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); nowMs = System.currentTimeMillis() } }
                            val setupBusy = setupState.isRunning || (nowMs - lastChange < 6000 && setupState.error == null)
                            Text(
                                text = if (setupBusy && setupState.completed) "Installing packages. Keep the app open, this takes 2 to 4 minutes."
                                else setupState.stepTitle.ifBlank { "Preparing local Linux directory tree..." },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textDim
                            )
                            if (setupBusy && (setupState.completed || setupState.progress >= 0.99f)) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = colors.accent,
                                    trackColor = colors.surface2
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { setupState.progress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = colors.accent,
                                    trackColor = colors.surface2
                                )
                            }
                            if (setupState.error != null) {
                                Text("Error: ${setupState.error}", color = colors.danger, style = MaterialTheme.typography.bodySmall)
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = colors.surface,
                                border = BorderStroke(1.dp, colors.border),
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            ) {
                                LazyColumn(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(setupState.logs.filterNot { it.contains("[seccomp=") || it.contains("Permission denied") && it.contains("/tmp/.yshl_") }) { log ->
                                        Text(
                                            text = log,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = colors.textDim
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { rootfsPicker.launch(arrayOf("application/gzip", "application/x-gzip", "*/*")) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.UploadFile, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Import rootfs.tar.gz")
                                }
                                OutlinedButton(
                                    onClick = { mainViewModel.initializeLinuxEnvironment(installPackages = false) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Skip packages")
                                }
                            }
                        }
                    }

                    2 -> {
                        var selectedPreset by remember { mutableStateOf<ProviderPreset>(ProviderPresets.all.first()) }
                        var baseUrl by remember(selectedPreset) { mutableStateOf(selectedPreset.baseUrl) }
                        var apiKey by remember { mutableStateOf("") }
                        var showKey by remember { mutableStateOf(false) }

                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Connect an LLM Provider", style = MaterialTheme.typography.titleLarge, color = colors.text)
                            Text("Choose a preset or configure any OpenAI-compatible / Anthropic / Gemini endpoint.", style = MaterialTheme.typography.bodySmall, color = colors.textDim)

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ProviderPresets.all.chunked(2).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        row.forEach { preset ->
                                            val selected = preset.id == selectedPreset.id
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (selected) colors.accent.copy(alpha = 0.16f) else colors.surface,
                                                border = BorderStroke(1.dp, if (selected) colors.accent else colors.border),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        selectedPreset = preset
                                                        configViewModel.clearTestResult()
                                                    }
                                            ) {
                                                Text(
                                                    text = preset.name,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = if (selected) colors.accent else colors.text,
                                                    modifier = Modifier.padding(12.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("Base URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("API Key (${selectedPreset.hint})") },
                                singleLine = true,
                                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showKey = !showKey }) {
                                        Icon(
                                            if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = "Toggle key visibility"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("onboarding_api_key_input")
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = {
                                        configViewModel.testProviderConnection(
                                            type = selectedPreset.type,
                                            baseUrl = baseUrl,
                                            apiKeyPlain = apiKey,
                                            existingSecretAlias = null,
                                            headers = emptyMap(),
                                            modelId = selectedPreset.defaultModels.firstOrNull()?.id
                                        )
                                    },
                                    enabled = !isTesting
                                ) {
                                    if (isTesting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Text("Test Connection")
                                }
                                Button(
                                    onClick = {
                                        configViewModel.savePresetQuick(selectedPreset, apiKey, baseUrl)
                                        mainViewModel.completeOnboarding()
                                        onFinish()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    enabled = apiKey.isNotBlank() || !selectedPreset.requiresApiKey,
                                    modifier = Modifier.weight(1f).testTag("onboarding_save_provider_btn")
                                ) {
                                    Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save & Done")
                                }
                            }

                            testResult?.let { res ->
                                Text(
                                    text = res.message,
                                    color = if (res.success) colors.success else colors.danger,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Footer Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) { Text("Back") }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                when (step) {
                    0 -> Button(
                        onClick = { step = 1 },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.testTag("onboarding_start_btn")
                    ) {
                        Text("Start Setup")
                    }
                    1 -> Button(
                        onClick = { step = 2 },
                        enabled = !setupState.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.testTag("onboarding_next_provider_btn")
                    ) {
                        Text(if (setupState.completed) "Continue" else "Continue Anyway")
                    }
                    2 -> TextButton(
                        onClick = {
                            mainViewModel.completeOnboarding()
                            onFinish()
                        },
                        modifier = Modifier.testTag("onboarding_skip_provider_btn")
                    ) {
                        Text("Skip for now")
                    }
                }
            }
        }
    }
}
