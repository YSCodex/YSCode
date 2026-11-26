// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import com.example.linux.LinuxEnvironment
import com.example.util.JsonHelper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

/**
 * Owns external toolchains (JDK, Node, SDKs...) that live either inside the rootfs or on shared
 * storage. Applying a toolchain bind-mounts it into PRoot, appends its `bin` to PATH and exports its
 * environment variables so `java -version` / `node -v` work from any shell.
 */
class ToolchainManager(
    private val env: LinuxEnvironment,
    private val dao: ToolchainDao
) {
    private data class Applied(
        val guestPath: String,
        val pathEntry: String?,
        val envKeys: List<String>
    )

    private val applied = HashMap<String, Applied>()

    suspend fun rescan() {
        dao.getAllEnabled().forEach { runCatching { apply(it) } }
        autoDiscover()
    }

    /** Picks up runtimes a user/agent installed but never registered (JDK under /usr/lib/jvm, ~/.yscode/toolchains). */
    private suspend fun autoDiscover() {
        val known = runCatching {
            dao.getAllEnabled().map { File(it.hostPath).canonicalPath }.toSet()
        }.getOrDefault(emptySet())
        val candidates = LinkedHashMap<String, File>()
        File(env.rootHomeDir, ".yscode/toolchains").listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { candidates[it.name] = it }
        File(env.rootfsDir, "usr/lib/jvm").listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { candidates[it.name] = it }
        for ((name, dir) in candidates) {
            if (!File(dir, "bin/java").isFile && !File(dir, "bin/node").isFile) continue
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: continue
            if (canonical in known) continue
            register(name = name, hostPath = canonical)
        }
    }

    suspend fun list(): List<ToolchainEntity> = dao.getAllEnabled()

    fun apply(toolchain: ToolchainEntity) {
        val host = runCatching { File(toolchain.hostPath).canonicalFile }.getOrNull() ?: return
        if (!host.isDirectory) return
        revoke(toolchain)

        val insideRootfs = runCatching {
            host.path == env.rootfsDir.canonicalPath ||
                host.path.startsWith(env.rootfsDir.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!insideRootfs) env.registerBind(host, toolchain.guestPath)

        val envMap = parseEnv(toolchain.envJson)
        val binGuest = "${toolchain.guestPath.trimEnd('/')}/bin"
        val pathEntry = when {
            envMap["PATH"] != null -> envMap["PATH"]
            File(host, "bin").isDirectory -> binGuest
            else -> null
        }
        pathEntry?.split(':')?.filter { it.isNotBlank() }?.forEach { env.addGuestPathEntry(it) }
        val envKeys = mutableListOf<String>()
        envMap.forEach { (key, value) ->
            if (key == "PATH") return@forEach
            env.setGuestEnv(key, value)
            envKeys += key
        }
        if (envMap["JAVA_HOME"] == null && File(host, "bin/java").isFile) {
            env.setGuestEnv("JAVA_HOME", toolchain.guestPath)
            envKeys += "JAVA_HOME"
        }
        applied[toolchain.id] = Applied(toolchain.guestPath, pathEntry, envKeys)
    }

    fun revoke(toolchain: ToolchainEntity) {
        val previous = applied.remove(toolchain.id) ?: return
        env.unregisterBind(previous.guestPath)
        previous.pathEntry?.split(':')?.filter { it.isNotBlank() }?.forEach { env.removeGuestPathEntry(it) }
        previous.envKeys.forEach { env.removeGuestEnv(it) }
    }

    suspend fun register(
        name: String,
        hostPath: String,
        guestPath: String? = null,
        envVars: Map<String, String> = emptyMap(),
        readOnly: Boolean = false
    ): Result<ToolchainEntity> = runCatching {
        val host = File(hostPath).canonicalFile
        if (!host.isDirectory) error("Toolchain path ${host.absolutePath} is not a directory")
        val guest = guestPath?.trim()?.takeIf { it.startsWith("/") } ?: defaultGuestPath(host, name)
        val envJson = JsonObject(envVars.mapValues { JsonPrimitive(it.value) }).toString()
        val entity = ToolchainEntity(
            id = "tc_" + UUID.randomUUID().toString().take(8),
            name = name.trim().ifBlank { host.name }.ifBlank { "toolchain" },
            hostPath = host.path,
            guestPath = guest,
            readOnly = readOnly,
            envJson = envJson
        )
        dao.upsert(entity)
        apply(entity)
        entity
    }

    suspend fun remove(id: String): Result<Unit> = runCatching {
        dao.getById(id)?.let { revoke(it) }
        dao.deleteById(id)
        Unit
    }

    private fun defaultGuestPath(host: File, name: String): String {
        val root = runCatching { env.rootfsDir.canonicalPath }.getOrNull()
        if (root != null && host.path.startsWith(root + File.separator)) {
            return "/" + host.path.removePrefix(root + File.separator)
        }
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').ifBlank { "toolchain" }
        return "/opt/yscode/$slug"
    }

    private fun parseEnv(raw: String): Map<String, String> = runCatching {
        val obj = JsonHelper.json.parseToJsonElement(raw).jsonObject
        obj.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.content?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())
}
