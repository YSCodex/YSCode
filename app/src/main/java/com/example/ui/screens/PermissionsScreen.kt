// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.MainViewModel

private fun notificationsGranted(c: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun batteryUnrestricted(c: Context): Boolean =
    (c.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(c.packageName) == true

/** Explains and requests everything YSCode needs: file access, notifications, background running. */
@Composable
fun PermissionsScreen(mainViewModel: MainViewModel, onDone: () -> Unit) {
    val colors = LocalYSCodeColors.current
    val context = LocalContext.current
    val allFiles by mainViewModel.allFilesAccess.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        mainViewModel.refreshLinuxDiagnostics()
        tick++
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mainViewModel.refreshLinuxDiagnostics(); tick++
    }

    val notif = remember(tick) { notificationsGranted(context) }
    val battery = remember(tick) { batteryUnrestricted(context) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Permissions", style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Text(
            "YSCode works like a real terminal on your phone. These make it work with your real folders and keep long tasks running.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textDim
        )

        PermissionCard(
            icon = Icons.Outlined.Storage,
            title = "All files access",
            why = "Open /sdcard folders in place, run git/builds on real files, use /sdcard inside Linux.",
            granted = allFiles,
            action = "Grant"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(i) }.onFailure {
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            } else {
                legacyStorage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }

        PermissionCard(
            icon = Icons.Outlined.Notifications,
            title = "Notifications",
            why = "Know when the agent finishes, needs approval, or an update is ready.",
            granted = notif,
            action = "Allow"
        ) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        PermissionCard(
            icon = Icons.Outlined.Speed,
            title = "Run in background",
            why = "Stops the system from killing long builds and agent runs when the screen is off.",
            granted = battery,
            action = "Allow"
        ) {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(i) }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(if (allFiles && notif) "Continue" else "Continue (you can change these later in Settings)")
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    why: String,
    granted: Boolean,
    action: String,
    onClick: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.weight(1f))
                if (granted) Icon(Icons.Outlined.CheckCircle, "Granted", tint = colors.accent, modifier = Modifier.size(22.dp))
            }
            Text(why, style = MaterialTheme.typography.bodySmall, color = colors.textDim)
            if (!granted) {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
            }
        }
    }
}
