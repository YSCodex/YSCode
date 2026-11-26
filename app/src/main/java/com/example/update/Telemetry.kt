// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.update

import android.content.Context
import android.os.Build
import com.example.llm.HttpClients
import com.example.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

/**
 * Opt-in anonymous usage ping + feedback sender. Nothing is sent unless the user turned stats on (ping)
 * or pressed Send (feedback). No chats, code, keys or device model are ever included.
 */
class Telemetry(private val context: Context) {
    private val prefs = context.getSharedPreferences("yscode_meta", Context.MODE_PRIVATE)
    private val json = "application/json; charset=utf-8".toMediaType()

    var enabled: Boolean
        get() = prefs.getBoolean("telemetry", false)
        set(v) { prefs.edit().putBoolean("telemetry", v).apply() }

    var consentAsked: Boolean
        get() = prefs.getBoolean("consent_asked", false)
        set(v) { prefs.edit().putBoolean("consent_asked", v).apply() }

    private fun installId(): String =
        prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("install_id", it).apply() }

    /** Backend URL, published by the deploy workflow in config.json; cached for a day. */
    suspend fun apiBase(): String? = withContext(Dispatchers.IO) {
        val cachedAt = prefs.getLong("api_cached_at", 0L)
        val cached = prefs.getString("api_base", "").orEmpty()
        if (cached.isNotBlank() && System.currentTimeMillis() - cachedAt < 86_400_000L) return@withContext cached
        runCatching {
            HttpClients.streamingClient.newCall(Request.Builder().url(CONFIG_URL).header("Cache-Control", "no-cache").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val api = JSONObject(resp.body?.string().orEmpty()).optString("api", "").trimEnd('/')
                if (api.startsWith("https://")) {
                    prefs.edit().putString("api_base", api).putLong("api_cached_at", System.currentTimeMillis()).apply()
                    api
                } else null
            }
        }.getOrNull() ?: cached.ifBlank { null }
    }

    suspend fun websiteUrl(): String = apiBase() ?: REPO_URL

    suspend fun pingIfDue() {
        if (!enabled) return
        val today = utcDayString()
        if (prefs.getString("last_ping_day", "") == today) return
        val base = apiBase() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val code = runCatching {
                    androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
                }.getOrDefault(0L)
                val body = JSONObject()
                    .put("install_id", installId())
                    .put("version", CrashReporter.versionName(context))
                    .put("version_code", code)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
                    .toString()
                HttpClients.streamingClient.newCall(Request.Builder().url("$base/api/ping").post(body.toRequestBody(json)).build()).execute().use { resp ->
                    if (resp.isSuccessful) prefs.edit().putString("last_ping_day", today).apply()
                }
            }
        }
    }

    /** Returns an error message, or null on success. */
    suspend fun sendFeedback(kind: String, message: String, contact: String, diagnostics: String?): String? = withContext(Dispatchers.IO) {
        val base = apiBase() ?: return@withContext "The feedback service is not available yet. Please open an issue at $REPO_URL/issues instead."
        try {
            val body = JSONObject()
                .put("kind", kind)
                .put("message", message)
                .put("contact", contact)
                .put("version", CrashReporter.versionName(context))
                .put("diagnostics", diagnostics ?: JSONObject.NULL)
                .toString()
            HttpClients.streamingClient.newCall(Request.Builder().url("$base/api/feedback").post(body.toRequestBody(json)).build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> null
                    resp.code == 429 -> "Too many messages. Please try again later."
                    resp.code == 400 -> "Please write a bit more (at least 5 characters)."
                    else -> "Could not send (HTTP ${resp.code}). Please try again."
                }
            }
        } catch (t: Throwable) {
            "Network error: ${t.message ?: "could not reach the server"}"
        }
    }
}
