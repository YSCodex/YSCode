// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.util

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.example.linux.LinuxEnvironment
import java.io.File

/** Local-only crash capture + diagnostic report (no telemetry, nothing leaves the device unless the user shares it). */
object CrashReporter {
    private const val MAX_TRACE = 6000

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = File(app.filesDir, "crash").apply { mkdirs() }
                File(dir, "last_crash.txt").writeText(
                    "time=${java.util.Date()}\nthread=${thread.name}\nversion=${versionName(app)}\n" +
                        android.util.Log.getStackTraceString(error).take(MAX_TRACE)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun versionCode(ctx: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    }.getOrDefault(0L)

    fun lastCrash(ctx: Context): String? =
        runCatching { File(File(ctx.filesDir, "crash"), "last_crash.txt").takeIf { it.isFile }?.readText() }.getOrNull()

    fun clearCrash(ctx: Context) {
        runCatching { File(File(ctx.filesDir, "crash"), "last_crash.txt").delete() }
    }

    fun buildReport(ctx: Context, env: LinuxEnvironment): String = buildString {
        appendLine("YSCode diagnostic report")
        appendLine("app=${versionName(ctx)} (${versionCode(ctx)})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} | android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("proot_present=${env.prootBinary.exists()} proot_exec=${env.prootBinary.canExecute()}")
        appendLine("linux_ready=${env.hasProotAndRootfs()} rootfs_present=${env.rawProotAndRootfs()} proot_broken=${env.prootBroken} proot_mode=${env.prootMode.id()}")
        val pageSize = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)
        appendLine("hardlinks=${if (env.prootMode.l2s) "emulated (link2symlink)" else "real"}")
        appendLine("kernel=${System.getProperty("os.version")} page_size=$pageSize")
        appendLine("native_lib_dir=${ctx.applicationInfo.nativeLibraryDir}")
        val nativeFiles = runCatching { File(ctx.applicationInfo.nativeLibraryDir).listFiles()?.joinToString { "${it.name}(${it.length()},x=${it.canExecute()})" } }.getOrNull()
        appendLine("native_libs=$nativeFiles")
        val probe = env.probeLog()
        if (probe != null) {
            appendLine("--- proot probe ---")
            appendLine(probe.take(3000))
        }
        appendLine("free_storage_mb=${ctx.filesDir.usableSpace / (1024 * 1024)}")
        val crash = lastCrash(ctx)
        if (crash != null) {
            appendLine()
            appendLine("--- last crash ---")
            appendLine(crash.take(MAX_TRACE))
        } else {
            appendLine("last_crash=none")
        }
    }
}
