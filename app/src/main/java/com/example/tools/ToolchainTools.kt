// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.data.ToolchainManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Lets the agent record runtimes it installed (JDK, Node, Android SDK...) so later Bash calls can
 * find them without rediscovering paths. Also the single place to inspect/remove them.
 */
class ToolchainTool(
    private val manager: ToolchainManager
) : Tool {
    override val name = "toolchain"
    override val description =
        "List, register, or remove external toolchains (JDK, Node, SDKs). Registered toolchains are bind-mounted into Linux, added to PATH and export their env vars for every Bash call."
    override val risk = Risk.EXEC
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "'list' (default), 'register', or 'remove'")
            }
            putJsonObject("id") { put("type", "string"); put("description", "Toolchain id for 'remove'") }
            putJsonObject("name") { put("type", "string"); put("description", "Display name, e.g. 'JDK 17'") }
            putJsonObject("path") {
                put("type", "string")
                put("description", "Host path of the toolchain root (e.g. /sdcard/yscode/jdk17 or /root/.yscode/toolchains/node)")
            }
            putJsonObject("guest_path") {
                put("type", "string")
                put("description", "Optional path inside Linux; defaults to /opt/yscode/<name> (or the rootfs path if it already lives there)")
            }
            putJsonObject("env") {
                put("type", "object")
                put("description", "Env vars as strings, e.g. {\"JAVA_HOME\":\"/opt/yscode/jdk17\",\"PATH\":\"/opt/yscode/jdk17/bin\"}")
            }
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        when (args.str("action")?.trim()?.lowercase() ?: "list") {
            "register" -> {
                val name = args.str("name")?.trim().orEmpty()
                val path = args.str("path")?.trim().orEmpty()
                if (path.isBlank()) return@withContext ToolResult.error("Missing 'path'")
                val env = (args["env"] as? JsonObject)?.mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.content?.let { key to it }
                }?.toMap().orEmpty()
                manager.register(
                    name = name.ifBlank { "toolchain" },
                    hostPath = path,
                    guestPath = args.str("guest_path"),
                    envVars = env
                ).fold(
                    onSuccess = { tc ->
                        val where = if (tc.hostPath.startsWith(ctx.env.rootfsDir.path)) "inside rootfs" else "bind-mounted"
                        ToolResult.ok("Registered '${tc.name}' ($where at ${tc.guestPath}).\nRun `command -v java` / `node -v` to verify.", summary = "toolchain ok")
                    },
                    onFailure = { ToolResult.error("Register failed: ${it.message}") }
                )
            }
            "remove" -> {
                val id = args.str("id")?.trim().orEmpty()
                if (id.isBlank()) return@withContext ToolResult.error("Missing 'id'")
                manager.remove(id).fold(
                    onSuccess = { ToolResult.ok("Removed toolchain $id.", summary = "toolchain removed") },
                    onFailure = { ToolResult.error("Remove failed: ${it.message}") }
                )
            }
            else -> {
                val all = manager.list()
                if (all.isEmpty()) {
                    ToolResult.ok("No toolchains registered. Install one (e.g. `apk add openjdk17`) then register its path.", summary = "0 toolchains")
                } else {
                    val text = buildString {
                        appendLine("${all.size} toolchain(s):")
                        all.forEach { tc ->
                            appendLine("- ${tc.name} [${tc.id}] host=${tc.hostPath} guest=${tc.guestPath} env=${tc.envJson}")
                        }
                    }
                    ToolResult.ok(text.trimEnd(), summary = "${all.size} toolchains")
                }
            }
        }
    }
}
