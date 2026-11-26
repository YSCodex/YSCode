// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import android.util.Base64
import com.example.data.SecretStore
import com.example.util.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One signed-in ChatGPT account (Plus / Pro / Team ...) used through the Codex backend. */
@Serializable
data class CodexAccount(
    val id: String,
    val email: String,
    val plan: String,
    val accountId: String,
    val access: String,
    val refresh: String,
    val expiresAt: Long,
    val savedAt: Long = System.currentTimeMillis()
)

@Serializable
data class CodexAccountsState(
    val activeId: String? = null,
    val accounts: List<CodexAccount> = emptyList()
)

data class CodexDeviceCode(val deviceAuthId: String, val userCode: String, val intervalSec: Int, val verifyUrl: String)

data class CodexQuotaWindow(val usedPercent: Double, val windowMinutes: Long?, val resetsInSec: Long?)

data class CodexQuota(val plan: String?, val primary: CodexQuotaWindow?, val secondary: CodexQuotaWindow?)

/**
 * Sign-in with a ChatGPT plan (the same device-code login the Codex CLI uses), several saved accounts with a switcher,
 * automatic token refresh and the plan usage limits. Tokens live only in the encrypted [SecretStore].
 */
class CodexAuth(private val secrets: SecretStore) {

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val ISSUER = "https://auth.openai.com"
        const val BASE_URL = "https://chatgpt.com/backend-api/codex"
        const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        const val CLIENT_VERSION = "0.160.0"
        const val USER_AGENT = "yscode/0.0.1 (Android)"
        private const val ALIAS = "codex_oauth_accounts"
        private const val LOGIN_TIMEOUT_MS = 15 * 60_000L
    }

    private val http get() = HttpClients.streamingClient
    private val jsonType = "application/json".toMediaType()
    private val mutex = Mutex()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<CodexAccountsState> = _state.asStateFlow()

    val isSignedIn: Boolean get() = _state.value.accounts.isNotEmpty()

    private fun load(): CodexAccountsState = runCatching {
        JsonHelper.json.decodeFromString<CodexAccountsState>(secrets.getSecret(ALIAS).orEmpty())
    }.getOrDefault(CodexAccountsState())

    private fun save(s: CodexAccountsState) {
        secrets.putSecret(ALIAS, JsonHelper.json.encodeToString(s))
        _state.value = s
    }

    fun activeAccount(): CodexAccount? {
        val s = _state.value
        return s.accounts.firstOrNull { it.id == s.activeId } ?: s.accounts.firstOrNull()
    }

    fun setActive(id: String) {
        val s = _state.value
        if (s.accounts.any { it.id == id }) save(s.copy(activeId = id))
    }

    fun remove(id: String) {
        val s = _state.value
        val left = s.accounts.filterNot { it.id == id }
        save(CodexAccountsState(activeId = if (s.activeId == id) left.firstOrNull()?.id else s.activeId, accounts = left))
    }

    // ---------------------------------------------------------------- device login

    suspend fun startDeviceLogin(): Result<CodexDeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject { put("client_id", CLIENT_ID) }.toString().toRequestBody(jsonType)
            val req = Request.Builder().url("$ISSUER/api/accounts/deviceauth/usercode").post(body)
                .header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("Login could not start (HTTP ${r.code}). ${txt.take(160)}")
                val o = JsonHelper.parseOrNull(txt)?.jsonObject ?: error("Unexpected login response")
                CodexDeviceCode(
                    deviceAuthId = o["device_auth_id"]?.jsonPrimitive?.contentOrNull ?: error("No device id in response"),
                    userCode = o["user_code"]?.jsonPrimitive?.contentOrNull ?: error("No code in response"),
                    intervalSec = (o["interval"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5).coerceIn(3, 30),
                    verifyUrl = "$ISSUER/codex/device"
                )
            }
        }
    }

    /** Waits until the user approved the code in the browser, then stores the account. Cancel by cancelling the coroutine. */
    suspend fun completeDeviceLogin(dc: CodexDeviceCode): Result<CodexAccount> = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
        while (true) {
            ensureActive()
            if (System.currentTimeMillis() > deadline) return@withContext Result.failure(Exception("The code expired. Start again."))
            val body = buildJsonObject {
                put("device_auth_id", dc.deviceAuthId)
                put("user_code", dc.userCode)
            }.toString().toRequestBody(jsonType)
            val req = Request.Builder().url("$ISSUER/api/accounts/deviceauth/token").post(body)
                .header("User-Agent", USER_AGENT).build()
            val outcome: Result<JsonObject>? = runCatching {
                http.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    when {
                        r.isSuccessful -> Result.success(JsonHelper.parseOrNull(txt)?.jsonObject ?: error("Unexpected response"))
                        r.code == 403 || r.code == 404 -> null // still waiting for approval
                        else -> Result.failure(Exception("Login failed (HTTP ${r.code})"))
                    }
                }
            }.getOrElse { null } // transient network error: keep waiting
            if (outcome != null) {
                val o = outcome.getOrElse { return@withContext Result.failure(it) }
                val code = o["authorization_code"]?.jsonPrimitive?.contentOrNull
                val verifier = o["code_verifier"]?.jsonPrimitive?.contentOrNull
                if (code == null || verifier == null) return@withContext Result.failure(Exception("Login response was incomplete"))
                return@withContext runCatching { exchangeCode(code, verifier) }
            }
            delay(dc.intervalSec * 1000L + 3000L)
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(Exception("Login ended"))
    }

    private fun exchangeCode(code: String, verifier: String): CodexAccount {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", "$ISSUER/deviceauth/callback")
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder().url("$ISSUER/oauth/token").post(form).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("Token exchange failed (HTTP ${r.code})")
            val o = JsonHelper.parseOrNull(txt)?.jsonObject ?: error("Unexpected token response")
            val account = accountFromTokens(o, previous = null)
            val s = _state.value
            val merged = s.accounts.filterNot { it.id == account.id } + account
            save(CodexAccountsState(activeId = account.id, accounts = merged))
            return account
        }
    }

    // ---------------------------------------------------------------- tokens

    /** The active account with a fresh access token (refreshing it when it is about to expire). */
    suspend fun validAccount(forceRefresh: Boolean = false): CodexAccount = mutex.withLock {
        val acc = activeAccount() ?: error("Not signed in to ChatGPT. Open Providers > ChatGPT Codex and sign in.")
        ensureFresh(acc, forceRefresh)
    }

    private suspend fun ensureFresh(acc: CodexAccount, force: Boolean): CodexAccount {
        if (!force && acc.expiresAt - 120_000L > System.currentTimeMillis()) return acc
        return withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", acc.refresh)
                .add("client_id", CLIENT_ID)
                .build()
            val req = Request.Builder().url("$ISSUER/oauth/token").post(form).header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    error(
                        if (r.code == 400 || r.code == 401) "Your ChatGPT session expired. Sign in again in Providers > ChatGPT Codex."
                        else "Could not refresh the ChatGPT session (HTTP ${r.code})."
                    )
                }
                val o = JsonHelper.parseOrNull(txt)?.jsonObject ?: error("Unexpected refresh response")
                val updated = accountFromTokens(o, previous = acc)
                val s = _state.value
                save(s.copy(accounts = s.accounts.map { if (it.id == acc.id) updated else it }))
                updated
            }
        }
    }

    private fun accountFromTokens(o: JsonObject, previous: CodexAccount?): CodexAccount {
        val access = o["access_token"]?.jsonPrimitive?.contentOrNull ?: error("No access token")
        val refresh = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: previous?.refresh ?: error("No refresh token")
        val idToken = o["id_token"]?.jsonPrimitive?.contentOrNull
        val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        val claims = (idToken?.let(::jwtClaims) ?: JsonObject(emptyMap()))
        val accessClaims = jwtClaims(access) ?: JsonObject(emptyMap())
        fun auth(c: JsonObject): JsonObject? = runCatching { c["https://api.openai.com/auth"]?.jsonObject }.getOrNull()
        val accountId = auth(claims)?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: auth(accessClaims)?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: previous?.accountId ?: error("This login has no ChatGPT account (is a plan active?)")
        val plan = auth(claims)?.get("chatgpt_plan_type")?.jsonPrimitive?.contentOrNull
            ?: auth(accessClaims)?.get("chatgpt_plan_type")?.jsonPrimitive?.contentOrNull
            ?: previous?.plan ?: "unknown"
        val email = claims["email"]?.jsonPrimitive?.contentOrNull
            ?: runCatching { claims["https://api.openai.com/profile"]?.jsonObject?.get("email")?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: previous?.email ?: "ChatGPT account"
        return CodexAccount(
            id = previous?.id ?: "$accountId:$email",
            email = email,
            plan = plan,
            accountId = accountId,
            access = access,
            refresh = refresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
            savedAt = previous?.savedAt ?: System.currentTimeMillis()
        )
    }

    private fun jwtClaims(jwt: String): JsonObject? = runCatching {
        val part = jwt.split(".").getOrNull(1) ?: return null
        val bytes = Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        JsonHelper.parseOrNull(String(bytes, Charsets.UTF_8))?.jsonObject
    }.getOrNull()

    fun requestBuilder(url: String, acc: CodexAccount): Request.Builder = Request.Builder().url(url)
        .header("Authorization", "Bearer ${acc.access}")
        .header("chatgpt-account-id", acc.accountId)
        .header("OpenAI-Beta", "responses=experimental")
        .header("originator", "yscode")
        .header("User-Agent", USER_AGENT)

    // ---------------------------------------------------------------- usage limits

    /** Plan limits of one saved account (5-hour and weekly windows), or an error text. */
    suspend fun fetchQuota(accountId: String): Result<CodexQuota> = withContext(Dispatchers.IO) {
        runCatching {
            val acc = mutex.withLock {
                val a = _state.value.accounts.firstOrNull { it.id == accountId } ?: error("Account not found")
                ensureFresh(a, false)
            }
            val req = requestBuilder(USAGE_URL, acc).get().build()
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("Usage is not available right now (HTTP ${r.code}).")
                val o = JsonHelper.parseOrNull(txt)?.jsonObject ?: error("Unexpected usage response")
                val limits = o["rate_limit"]?.jsonObject
                fun window(key: String): CodexQuotaWindow? {
                    val w = runCatching { limits?.get(key)?.jsonObject }.getOrNull() ?: return null
                    val used = w["used_percent"]?.jsonPrimitive?.doubleOrNull ?: return null
                    val secs = w["limit_window_seconds"]?.jsonPrimitive?.longOrNull
                    return CodexQuotaWindow(used, secs?.let { it / 60 }, w["reset_after_seconds"]?.jsonPrimitive?.longOrNull)
                }
                CodexQuota(
                    plan = o["plan_type"]?.jsonPrimitive?.contentOrNull ?: acc.plan,
                    primary = window("primary_window"),
                    secondary = window("secondary_window")
                )
            }
        }
    }
}
