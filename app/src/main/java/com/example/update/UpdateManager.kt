// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.llm.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val LATEST_URL = "https://raw.githubusercontent.com/YSCodex/YSCode/main/latest.json"
const val CONFIG_URL = "https://raw.githubusercontent.com/YSCodex/YSCode/main/config.json"
const val REPO_URL = "https://github.com/YSCodex/YSCode"

fun utcDayString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

/** In-app updates without a store: reads a public manifest, downloads the APK, verifies SHA-256, hands it to Android's installer. */
class UpdateManager(private val context: Context) {
    data class Release(
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val notes: String,
        val minVersionCode: Long
    )

    sealed class CheckResult {
        object UpToDate : CheckResult()
        object NotReleased : CheckResult()
        data class Available(val release: Release, val mandatory: Boolean) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    private val prefs = context.getSharedPreferences("yscode_meta", Context.MODE_PRIVATE)

    fun installedVersionCode(): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }.getOrDefault(0L)

    private fun parse(json: String): Release? = runCatching {
        val o = JSONObject(json)
        if (!o.optBoolean("released", false)) return null
        Release(
            versionName = o.getString("versionName"),
            versionCode = o.getLong("versionCode"),
            apkUrl = o.getString("apkUrl"),
            sha256 = o.optString("sha256", ""),
            size = o.optLong("size", 0L),
            notes = o.optString("notes", ""),
            minVersionCode = o.optLong("minVersionCode", 0L)
        )
    }.getOrNull()

    /** Last known newer release (from the cache), unless the user dismissed it. */
    fun cachedAvailable(): Release? {
        val r = parse(prefs.getString("update_json", "") ?: "") ?: return null
        if (r.versionCode <= installedVersionCode()) return null
        if (prefs.getLong("dismissed_code", 0L) == r.versionCode) return null
        return r
    }

    fun dismiss(r: Release) { prefs.edit().putLong("dismissed_code", r.versionCode).apply() }

    suspend fun checkIfDue() {
        if (prefs.getString("last_check_day", "") == utcDayString()) return
        check()
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(LATEST_URL).header("Cache-Control", "no-cache").build()
            HttpClients.streamingClient.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext CheckResult.NotReleased
                if (!resp.isSuccessful) return@withContext CheckResult.Error("Update server returned HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                prefs.edit().putString("update_json", body).putString("last_check_day", utcDayString()).apply()
                val r = parse(body) ?: return@withContext CheckResult.NotReleased
                if (r.versionCode > installedVersionCode()) {
                    CheckResult.Available(r, mandatory = installedVersionCode() < r.minVersionCode)
                } else CheckResult.UpToDate
            }
        } catch (t: Throwable) {
            CheckResult.Error("Could not check for updates: ${t.message ?: "network error"}")
        }
    }

    /** Returns an error message, or null when the system installer was launched. */
    suspend fun downloadAndInstall(r: Release, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "YSCode-${r.versionName}.apk")
            val md = MessageDigest.getInstance("SHA-256")
            HttpClients.streamingClient.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "Download failed (HTTP ${resp.code})"
                val body = resp.body ?: return@withContext "Empty download"
                val total = body.contentLength().takeIf { it > 0 } ?: r.size
                var read = 0L
                body.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            md.update(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256.isNotBlank() && !hex.equals(r.sha256, ignoreCase = true)) {
                out.delete()
                return@withContext "Checksum mismatch: the file is corrupted or was tampered with. It was NOT installed."
            }
            launchInstaller(out)
        } catch (t: Throwable) {
            "Update failed: ${t.message ?: "unknown error"}"
        }
    }

    private fun launchInstaller(file: File): String? {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Allow \"Install unknown apps\" for YSCode on the screen that just opened, then tap Update again."
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }
}
