// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.linux

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

data class ProotMode(val seccomp: Boolean, val fakeRoot: Boolean, val l2s: Boolean = true) {
    fun id(): String = "seccomp=${if (seccomp) 1 else 0},fakeroot=${if (fakeRoot) 1 else 0},l2s=${if (l2s) 1 else 0}"
}

/** One `proot -b host:guest` mapping registered at runtime (local projects, toolchains, shared storage). */
data class BindMount(val host: File, val guest: String)

class LinuxEnvironment(private val context: Context) {

    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    val rootHomeDir: File
        get() = File(rootfsDir, "root")

    val projectsDir: File
        get() = File(rootHomeDir, "projects")

    val globalSkillsDir: File
        get() = File(rootHomeDir, ".yscode/skills")

    val globalCommandsDir: File
        get() = File(rootHomeDir, ".yscode/commands")

    val checkpointsDir: File
        get() = File(context.filesDir, "checkpoints")

    val tmpDir: File
        get() = File(rootfsDir, "tmp")

    val etcDir: File
        get() = File(rootfsDir, "etc")

    val binDir: File
        get() = File(rootfsDir, "usr/local/bin")

    private val binds = java.util.concurrent.CopyOnWriteArrayList<BindMount>()
    private val guestPathEntries = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val guestEnv = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** All runtime bind mounts, in registration order (later entries win for the same guest path). */
    fun registeredBinds(): List<BindMount> = binds.toList()

    /**
     * Exposes [host] inside PRoot at [guest] (defaults to the same absolute path). Same-path binds are
     * what let a real on-device folder behave identically for host-side tools and inside the shell.
     */
    fun registerBind(host: File, guest: String = host.absolutePath): BindMount? {
        val canonical = runCatching { host.canonicalFile }.getOrDefault(host.absoluteFile)
        val normalized = normalizeGuestPath(guest) ?: return null
        val bind = BindMount(canonical, normalized)
        synchronized(binds) {
            binds.removeIf { it.guest == normalized }
            binds.add(bind)
        }
        return bind
    }

    fun unregisterBind(guest: String) {
        val normalized = normalizeGuestPath(guest) ?: return
        synchronized(binds) { binds.removeIf { it.guest == normalized } }
    }

    /** Registers a real project folder so `pwd`, Bash cwd and JVM/git inside PRoot all resolve it. */
    fun registerProjectDir(dir: File): BindMount? {
        if (!dir.isDirectory) return null
        return registerBind(dir, dir.absolutePath)
    }

    /** Makes any host directory outside the rootfs reachable inside PRoot (same absolute path). */
    fun ensureHostPathAccessible(dir: File) {
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return
        if (!canonical.isDirectory) return
        val root = runCatching { rootfsDir.canonicalPath }.getOrNull() ?: return
        if (canonical.path == root || canonical.path.startsWith(root + File.separator)) return
        registerBind(canonical, canonical.path)
    }

    fun addGuestPathEntry(dir: String) {
        val normalized = normalizeGuestPath(dir) ?: return
        if (guestPathEntries.none { it == normalized }) guestPathEntries += normalized
    }

    fun removeGuestPathEntry(dir: String) {
        val normalized = normalizeGuestPath(dir) ?: return
        guestPathEntries.removeIf { it == normalized }
    }

    /** Env applied to every PRoot shell (toolchain JAVA_HOME, ANDROID_HOME, custom PATH entries...). */
    fun setGuestEnv(key: String, value: String) {
        if (key.isBlank()) return
        guestEnv[key] = value
    }

    fun removeGuestEnv(key: String) {
        guestEnv.remove(key)
    }

    fun guestEnvOverrides(): Map<String, String> = guestEnv.toMap()

    fun guestPathDirs(): List<String> = guestPathEntries.toList()

    /** True when the app can read the whole shared storage (All files access or legacy grants). */
    @Suppress("DEPRECATION")
    fun hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Binds shared storage at its real path plus the familiar Termux/MT Manager aliases. Safe to call
     * repeatedly; only takes effect while All files access is granted.
     */
    fun syncSharedStorageBinds(): Boolean {
        val shared = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return false
        val allowed = hasAllFilesAccess()
        val aliases = listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary")
        if (!allowed || !shared.exists()) {
            aliases.forEach { unregisterBind(it) }
            unregisterBind(shared.absolutePath)
            return false
        }
        registerBind(shared, shared.absolutePath)
        aliases.forEach { registerBind(shared, it) }
        return true
    }

    /** Binds the app's own external directory (always readable) and refreshes shared-storage aliases. */
    private fun ensureDefaultBinds() {
        runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { registerBind(it) }
        syncSharedStorageBinds()
    }

    private fun normalizeGuestPath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return null
        val withoutTrailing = if (trimmed.length > 1) trimmed.trimEnd('/') else trimmed
        return withoutTrailing.ifEmpty { "/" }
    }

    val nativeLibDir: File
        get() = context.applicationInfo?.nativeLibraryDir?.let { File(it) }
            ?: File(context.filesDir, "native_libs")

    val prootBinary: File
        get() = File(nativeLibDir, "libproot.so")

    val prootLoaderBinary: File
        get() = File(nativeLibDir, "libproot-loader.so")

    /** True for regular files AND symlinks (even absolute ones like /bin/sh -> /bin/busybox that
     *  do not resolve on the host filesystem, which File.exists() wrongly reports as missing). */
    fun existsInRootfs(rel: String): Boolean {
        val f = File(rootfsDir, rel)
        return f.exists() || runCatching {
            java.nio.file.Files.isSymbolicLink(f.toPath())
        }.getOrDefault(false)
    }

    /** PRoot binary + rootfs exist (regardless of whether PRoot actually works on this device). */
    fun rawProotAndRootfs(): Boolean {
        val bashOrSh = existsInRootfs("bin/bash") || existsInRootfs("bin/sh")
        return prootBinary.exists() && prootBinary.canExecute() && bashOrSh
    }

    /** True when the on-device self-test found no working PRoot mode (device/kernel incompatibility). */
    val prootBroken: Boolean
        get() = runCatching { prootModeFile.readText().trim() == "broken" }.getOrDefault(false)

    /** Mode file written by an older build (no hardlink info): re-probe once so real hardlinks can be used. */
    val prootModeStale: Boolean
        get() = runCatching {
            val t = prootModeFile.readText()
            prootModeFile.isFile && !t.contains("broken") && !t.contains("l2s=")
        }.getOrDefault(false)

    fun markProotBroken() { runCatching { prootModeFile.writeText("broken") } }

    private val probeLogFile: File get() = File(rootfsDir.parentFile, "proot_probe.log")
    fun saveProbeLog(text: String) { runCatching { probeLogFile.writeText(text) } }
    fun probeLog(): String? = runCatching { probeLogFile.takeIf { it.isFile }?.readText() }.getOrNull()

    /** Usable Linux: PRoot present, rootfs present, and the self-test did not mark it broken. */
    fun hasProotAndRootfs(): Boolean = rawProotAndRootfs() && !prootBroken

    /** Full PRoot argv shared by the runner, terminal and package bootstrap. */
    private val prootModeFile: File get() = File(rootfsDir.parentFile, "proot_mode")

    /** PRoot runtime mode chosen by the on-device probe (persisted). Default = safest guess. */
    var prootMode: ProotMode
        get() = runCatching {
            val t = prootModeFile.readText()
            if (t.contains("broken")) ProotMode(seccomp = false, fakeRoot = true)
            else ProotMode(seccomp = t.contains("seccomp=1"), fakeRoot = t.contains("fakeroot=1"), l2s = !t.contains("l2s=0"))
        }.getOrDefault(ProotMode(seccomp = false, fakeRoot = true))
        set(v) { runCatching { prootModeFile.writeText(v.id()) } }

    fun prootArgv(cmd: String, cwdVirtual: String, mode: ProotMode = prootMode): List<String> {
        ensureDefaultBinds()
        val shell = if (existsInRootfs("bin/bash")) "/bin/bash" else "/bin/sh"
        val args = mutableListOf(
            prootBinary.absolutePath,
            "-r", rootfsDir.absolutePath,
            *(if (mode.fakeRoot) arrayOf("-0") else emptyArray()),
            *(if (mode.l2s) arrayOf("--link2symlink") else emptyArray()),
            "--kill-on-exit",
            "-b", "/dev",
            "-b", "/proc"
        )
        if (File("/sys").canRead()) { args += listOf("-b", "/sys") }
        args += listOf("-b", "${projectsDir.absolutePath}:/root/projects")
        val seenGuests = HashSet<String>()
        for (bind in binds.toList()) {
            if (!bind.host.exists() || !seenGuests.add(bind.guest)) continue
            args += listOf("-b", "${bind.host.absolutePath}:${bind.guest}")
        }
        val guestPath = buildString {
            append("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            guestPathEntries.forEach { append(':').append(it) }
        }
        args += listOf(
            "-w", cwdVirtual,
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=$guestPath",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "TMPDIR=/tmp"
        )
        guestEnv.forEach { (key, value) ->
            if (key != "HOME" && key != "PATH" && key != "TMPDIR") args += "$key=$value"
        }
        args += listOf(shell, "-lc", cmd)
        return args
    }

    /** Environment for the host-side proot process itself. */
    fun prootHostEnv(mode: ProotMode = prootMode): Map<String, String> = linkedMapOf<String, String>(
        "PATH" to "/system/bin:/system/xbin",
        "ANDROID_ROOT" to "/system",
        "ANDROID_DATA" to "/data",
        "HOME" to rootHomeDir.absolutePath,
        "PROOT_LOADER" to prootLoaderBinary.absolutePath,
        "PROOT_TMP_DIR" to tmpDir.apply { mkdirs() }.absolutePath,
    ).also { m -> if (!mode.seccomp) m["PROOT_NO_SECCOMP"] = "1" }

    fun resolveProjectPath(rawPath: String, projectDir: File): File {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty() || trimmed == ".") return projectDir
        val sharedMapped = mapSharedStorageAlias(trimmed)
        val expanded = when {
            trimmed == "~" -> rootHomeDir.absolutePath
            trimmed.startsWith("~/") -> File(rootHomeDir, trimmed.removePrefix("~/")).absolutePath
            sharedMapped != null -> sharedMapped
            trimmed.startsWith("/root/projects/") -> File(projectsDir, trimmed.removePrefix("/root/projects/")).absolutePath
            trimmed.startsWith("/root/") -> File(rootHomeDir, trimmed.removePrefix("/root/")).absolutePath
            trimmed.startsWith("/") -> {
                val direct = File(trimmed)
                if (direct.exists() || trimmed.startsWith(context.filesDir.absolutePath)) {
                    trimmed
                } else {
                    File(rootfsDir, trimmed.removePrefix("/")).absolutePath
                }
            }
            else -> File(projectDir, trimmed).absolutePath
        }
        return File(expanded).canonicalFile
    }

    /** Maps `/sdcard`, `/mnt/sdcard`, `/storage/self/primary` and the real shared path to the actual folder. */
    private fun mapSharedStorageAlias(path: String): String? {
        val shared = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return null
        val sharedPath = shared.absolutePath
        val rel = when {
            path == sharedPath -> ""
            path.startsWith("$sharedPath/") -> path.removePrefix("$sharedPath/")
            path == "/sdcard" -> ""
            path.startsWith("/sdcard/") -> path.removePrefix("/sdcard/")
            path == "/mnt/sdcard" -> ""
            path.startsWith("/mnt/sdcard/") -> path.removePrefix("/mnt/sdcard/")
            path == "/storage/self/primary" -> ""
            path.startsWith("/storage/self/primary/") -> path.removePrefix("/storage/self/primary/")
            else -> return null
        }
        return if (rel.isEmpty()) sharedPath else File(shared, rel).absolutePath
    }

    fun toVirtualPath(file: File): String {
        val canonical = file.canonicalPath
        val homePath = rootHomeDir.canonicalPath
        val rootPath = rootfsDir.canonicalPath
        return when {
            canonical == homePath -> "/root"
            canonical.startsWith("$homePath/") -> "/root/" + canonical.removePrefix("$homePath/")
            canonical.startsWith("$rootPath/") -> "/" + canonical.removePrefix("$rootPath/")
            else -> canonical
        }
    }

    fun buildEnv(customEnv: Map<String, String> = emptyMap(), extraPath: String = ""): Map<String, String> {
        val defaultPath = buildString {
            append(File(rootfsDir, "usr/local/bin").absolutePath).append(':')
            append(File(rootfsDir, "usr/bin").absolutePath).append(':')
            append(File(rootfsDir, "bin").absolutePath).append(':')
            if (extraPath.isNotBlank()) append(extraPath).append(':')
            append("/system/bin:/system/xbin")
        }
        val env = linkedMapOf(
            "HOME" to rootHomeDir.absolutePath,
            "PATH" to defaultPath,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to tmpDir.absolutePath,
            "YSCODE_ROOTFS" to rootfsDir.absolutePath,
            "YSCODE_PROJECTS" to projectsDir.absolutePath
        )
        customEnv.forEach { (k, v) -> if (k.isNotBlank()) env[k] = v }
        return env
    }

    /** `pkg` / `apt` / `apt-get` / `pip3`-friendly shims so users and the agent can use familiar
     *  commands inside Alpine (apk). Safe to call repeatedly. */
    fun installPackageShims() {
        if (!existsInRootfs("sbin/apk")) return
        val bin = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
        val shim = File(bin, "pkg")
        shim.writeText(
            """
            #!/bin/sh
            # YSCode shim: pkg/apt/apt-get -> apk
            cmd="${'$'}1"; [ ${'$'}# -gt 0 ] && shift
            names=""
            for a in "${'$'}@"; do
              case "${'$'}a" in
                -*) ;;
                python|python3-pip) [ "${'$'}a" = python ] && names="${'$'}names python3" || names="${'$'}names py3-pip" ;;
                nodejs-lts|node) names="${'$'}names nodejs" ;;
                build-essential) names="${'$'}names build-base" ;;
                *) names="${'$'}names ${'$'}a" ;;
              esac
            done
            case "${'$'}cmd" in
              install|add|i) exec apk add --no-cache ${'$'}names ;;
              update) exec apk update ;;
              upgrade|full-upgrade) apk update && exec apk upgrade ;;
              remove|uninstall|rm|purge) exec apk del ${'$'}names ;;
              search) exec apk search ${'$'}names ;;
              list|status|"") exec apk info ;;
              *) exec apk "${'$'}cmd" "${'$'}@" ;;
            esac
            """.trimIndent() + "\n"
        )
        shim.setExecutable(true, false)
        // apk wrapper: under PRoot, apk often installs everything and then fails only on "failed to write database:
        // Operation not permitted". Treat that single known error as success so scripts and the agent do not stop.
        val apkShim = File(bin, "apk")
        apkShim.writeText(
            """
            #!/bin/sh
            # YSCode shim: real apk, but the harmless PRoot database-write error does not fail the command
            out="/tmp/.yscode-apk.${'$'}${'$'}"
            mkdir -p /tmp 2>/dev/null
            { /sbin/apk "${'$'}@" 2>&1; echo ${'$'}? > "${'$'}out.rc"; } | tee "${'$'}out"
            rc=${'$'}(cat "${'$'}out.rc" 2>/dev/null || echo 1)
            if [ "${'$'}rc" != 0 ] && grep -q "failed to write database: Operation not permitted" "${'$'}out"; then
              other=${'$'}(grep '^ERROR' "${'$'}out" | grep -v 'failed to write database' | grep -v 'System state may be inconsistent' | wc -l)
              if [ "${'$'}other" -eq 0 ]; then
                echo "(YSCode: ignored the harmless PRoot database-write warning; packages were installed)"
                rc=0
              fi
            fi
            rm -f "${'$'}out" "${'$'}out.rc"
            exit ${'$'}rc
            """.trimIndent() + "\n"
        )
        apkShim.setExecutable(true, false)
        for (alias in listOf("apt", "apt-get")) {
            val f = File(bin, alias)
            runCatching { if (f.exists()) f.delete(); android.system.Os.symlink("pkg", f.absolutePath) }
        }
    }

    val bootstrapPackages = "bash git curl ca-certificates coreutils grep sed findutils python3 py3-pip nodejs npm ripgrep openssh-client procps-ng unzip jq"
}
