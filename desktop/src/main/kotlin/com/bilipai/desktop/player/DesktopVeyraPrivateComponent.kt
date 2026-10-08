package com.bilipai.desktop.player

import com.sun.nio.file.ExtendedOpenOption
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Explicit private component selection. A release-monitor result never enters this path.
 * The expected profile digest is supplied separately from the selected JSON. */
private val applicationVeyraSelectionLock = Any()
private var applicationVeyraComponent: DesktopVeyraPrivateComponent? = null

internal fun desktopVeyraComponentFromExplicitConfiguration(): DesktopVeyraPrivateComponent? = synchronized(applicationVeyraSelectionLock) {
    applicationVeyraComponent?.let { return@synchronized it }
    val profile = System.getProperty("bilipai.veyra.profile")?.takeIf(String::isNotBlank) ?: return@synchronized null
    val digest = System.getProperty("bilipai.veyra.profile.sha256")?.takeIf(String::isNotBlank) ?: return@synchronized null
    val resources = System.getProperty("compose.application.resources.dir")
    val verifier = System.getProperty("bilipai.veyra.verifier")?.takeIf(String::isNotBlank)
        ?: resources?.let { File(it, "native/veyra-core/verify-veyra-runtime.ps1").path } ?: return@synchronized null
    if (!digest.matches(Regex("[0-9a-fA-F]{64}"))) return@synchronized null
    DesktopVeyraPrivateComponent(Path.of(profile), digest.lowercase(Locale.ROOT), Path.of(verifier)).also { applicationVeyraComponent = it }
}

/** One application-owned component, supplied to the primary player. Verification runs
 * on its native worker before Native.load, outside playback/source/account gates.
 * Immutable file handles remain until process exit, including native quarantine.
 * Profile/engine switching therefore requires the existing application restart. */
internal class DesktopVeyraPrivateComponent(
    private val profilePath: Path,
    private val trustedProfileSha256: String,
    private val verifierPath: Path,
) {
    private val leases = mutableListOf<FileChannel>()
    private var attempted = false
    private var verified: DesktopVeyraVerifiedBinding? = null
    private var failure = "画质增强暂不可用，继续播放原画"

    @Synchronized
    fun prepareForNativeLoad(dll: File, previouslyLoadedWithoutLease: Boolean): DesktopVeyraVerifiedBinding? {
        if (attempted) return verified?.takeIf { it.mpvPath == dll.toPath().toRealPath() }
        attempted = true
        if (previouslyLoadedWithoutLease) { failure = "重新启动后可使用已选择的画质增强，当前继续播放原画"; return null }
        try {
            require(trustedProfileSha256.matches(Regex("[0-9a-f]{64}")))
            val profile = profilePath.toRealPath()
            val root = requireNotNull(profile.parent)
            val profileChannel = locked(profile)
            val profileBytes = readBounded(profileChannel, 65536)
            require(hash(profileBytes) == trustedProfileSha256)
            val json = Json.parseToJsonElement(profileBytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")) as JsonObject
            require(json["schema"]?.jsonPrimitive?.intOrNull == 1)
            require(json.text("variant") == "bilipai-veyra-core-v1")
            require(json.text("architecture") == "windows-x64")
            require(json.text("producerVariant") == "bilipai-veyra-rtx-core-v1")
            require(json.text("filterName") == "bilipai-rtx")
            require(json.digest("filterSourceManifestSha256") == FILTER_SOURCE_SHA256)
            require(json.text("veyraSourceCommit") == VEYRA_SOURCE_COMMIT)
            require(json.digest("coreSourceSha256") == CORE_SOURCE_SHA256)
            require(json.digest("headerSha256") == CORE_HEADER_SHA256)
            require(json["coreAbi"]?.jsonPrimitive?.intOrNull == 1 && json["coreAbiWire"]?.jsonPrimitive?.intOrNull == 65536)
            require(json.text("featureDirectory") == "runtime/experimental" && json.text("runtimeRoot") == ".")
            require(json.text("coreModuleRelativePath") == "core/bilipai_veyra_core.dll")
            require(json.text("mpvModuleRelativePath") == "mpv/libmpv-2.dll")
            val core = relative(root, "core/bilipai_veyra_core.dll")
            val mpv = relative(root, "mpv/libmpv-2.dll")
            require(mpv == dll.toPath().toRealPath())
            val coreHash = json.digest("moduleBuildSha256")
            val mpvHash = json.digest("mpvDllSha256")
            require(hash(locked(core)) == coreHash && hash(locked(mpv)) == mpvHash)
            val nativeProvenance = relative(root, "mpv/provenance.json")
            val provenance = Json.parseToJsonElement(readBounded(locked(nativeProvenance), 65536).toString(Charsets.UTF_8).removePrefix("\uFEFF")) as JsonObject
            require(provenance["schema"]?.jsonPrimitive?.intOrNull == 2)
            require(provenance.text("variant") == "bilipai-veyra-rtx-core-v1")
            require(provenance.text("filterName") == "bilipai-rtx" && provenance.text("architecture") == "windows-x64")
            require(provenance.text("sourceCommit") == MPV_SOURCE_COMMIT)
            require(provenance.digest("filterSourceManifestSha256") == FILTER_SOURCE_SHA256)
            require(provenance.digest("coreAbiHeaderSha256") == CORE_HEADER_SHA256 && provenance.digest("dllSha256") == mpvHash)
            // Require the actual producer's artifact/source/build receipts; a source-only
            // proposal with null binary identity never qualifies as an installed engine.
            listOf("archiveSha256", "runtimeDescriptorSha256", "buildReceiptSha256", "sourceBundleSha256").forEach { provenance.digest(it) }
            val projectId = requireNotNull(json.text("projectId"))
            require(projectId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            require(json.text("engineVersion") == "BiliPai-Veyra-Core-1")
            val runtime = relative(root, "runtime/experimental", directory = true)
            val runtimeFiles = json["runtimeFiles"] as? JsonArray ?: error("Runtime file identities absent")
            require(runtimeFiles.size == 2)
            val expectedRuntime = runtimeFiles.map { element ->
                val item = element as JsonObject
                val name = requireNotNull(item.text("relativePath"))
                require(name.matches(Regex("runtime/experimental/[A-Za-z0-9_.-]+\\.dll")))
                val expected = item.digest("sha256")
                require(hash(locked(relative(root, name))) == expected)
                name to expected
            }.toMap()
            require(expectedRuntime.size == runtimeFiles.size &&
                expectedRuntime.keys.containsAll(listOf("runtime/experimental/nvngx_vsr.dll", "runtime/experimental/nvngx_truehdr.dll")))
            val verifier = verifierPath.toRealPath()
            require(hash(locked(verifier)) == VERIFIER_SOURCE_SHA256)
            val receipt = verify(verifier, profile, root, core, mpv)
            require(receipt["schema"]?.jsonPrimitive?.intOrNull == 1 && receipt.text("status") == "VERIFIED" && receipt.text("engineStatus") == "AVAILABLE")
            val checked = receipt["checked"] as? JsonObject ?: error("Verification proof absent")
            require(checked.digest("profileSha256") == trustedProfileSha256 && checked.digest("moduleBuildSha256") == coreHash && checked.digest("mpvDllSha256") == mpvHash)
            require(checked.digest("coreSourceSha256") == CORE_SOURCE_SHA256 && checked.digest("headerSha256") == CORE_HEADER_SHA256)
            require(checked["coreAbi"]?.jsonPrimitive?.intOrNull == 1 && checked["coreAbiWire"]?.jsonPrimitive?.intOrNull == 65536)
            val actualRuntime = checked["runtimeFiles"] as? JsonArray ?: error("Runtime signature results absent")
            require(actualRuntime.size == expectedRuntime.size)
            val actualNames = mutableSetOf<String>()
            actualRuntime.forEach { element ->
                val item = element as JsonObject
                val name = requireNotNull(item.text("relativePath"))
                require(actualNames.add(name) && item.digest("sha256") == expectedRuntime[name] && item.text("authenticodeStatus") == "Valid")
                require(!item.text("signerThumbprint").isNullOrBlank() && !item.text("signerSubject").isNullOrBlank())
            }
            // The hash-pinned verifier checks the fixed NVIDIA leaf identity, while the
            // application locks all selected file identities before that verification.
            verified = DesktopVeyraVerifiedBinding(mpv, core, runtime, projectId, trustedProfileSha256)
            failure = ""
            return verified
        } catch (failureCause: Exception) {
            if (failureCause is InterruptedException) Thread.currentThread().interrupt()
            failure = "画质增强暂不可用，继续播放原画"
            // No core was loaded on this rejection path; release only unaccepted files.
            leases.asReversed().forEach { runCatching { it.close() } }; leases.clear()
            return null
        }
    }

    @Synchronized fun unavailableReason(): String = failure

    private fun locked(path: Path): FileChannel = FileChannel.open(path, StandardOpenOption.READ,
        ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE).also(leases::add)

    private fun relative(root: Path, name: String, directory: Boolean = false): Path {
        val part = Path.of(name)
        require(!part.isAbsolute && part.none { it.toString() in setOf(".", "..") })
        val real = root.resolve(part).toRealPath()
        require(real.startsWith(root) && (if (directory) Files.isDirectory(real) else Files.isRegularFile(real)))
        return real
    }

    private fun verify(script: Path, profile: Path, root: Path, core: Path, mpv: Path): JsonObject {
        val windows = Path.of(System.getenv("SystemRoot") ?: error("Windows directory unavailable"))
        val shell = windows.resolve("System32/WindowsPowerShell/v1.0/powershell.exe")
        val output = Files.createTempFile("bilipai-veyra-verify-", ".json")
        var process: Process? = null
        try {
            process = ProcessBuilder(shell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-ProfilePath", profile.toString(), "-TrustedProfileSha256", trustedProfileSha256,
                "-RuntimeRoot", root.toString(), "-CoreModulePath", core.toString(), "-MpvModulePath", mpv.toString())
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            require(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
            require(Files.size(output) in 1..65536)
            return Json.parseToJsonElement(Files.readString(output).removePrefix("\uFEFF")) as JsonObject
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
            runCatching { Files.deleteIfExists(output) }
        }
    }

    private fun readBounded(channel: FileChannel, maximum: Int): ByteArray {
        channel.position(0)
        val buffer = ByteBuffer.allocate(maximum + 1)
        while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
        require(buffer.position() <= maximum)
        return buffer.array().copyOf(buffer.position())
    }

    private fun hash(channel: FileChannel): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(65536); channel.position(0)
        while (true) { buffer.clear(); val n = channel.read(buffer); if (n < 0) break; if (n == 0) continue; buffer.flip(); digest.update(buffer) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.digest(key: String): String = requireNotNull(text(key))
        .also { require(it.matches(Regex("[0-9a-fA-F]{64}"))) }.lowercase(Locale.ROOT)

    companion object {
        private const val VEYRA_SOURCE_COMMIT = "96a7c8de36bc195240161de6814739ad810722f1"
        private const val MPV_SOURCE_COMMIT = "69e63f425a531f814431fba12750bdb3721357f2"
        private const val CORE_SOURCE_SHA256 = "1dc853de084ef333a762f4fc4ca7eadba3a76fc70bad0cadc0ad5529b5b84fe8"
        private const val CORE_HEADER_SHA256 = "0b9521abd2725e5da969a1dad81bff51619847a989a07563dcf4b1df4a64e569"
        private const val FILTER_SOURCE_SHA256 = "9c0f19de87da2398f15d09dd27ebca911ba292e5689d53bf7f62ea1742c3359f"
        private const val VERIFIER_SOURCE_SHA256 = "ca725540d846e2957df1d505770d729be3e6e50a354e4e8ca5450a347c22e86d"
    }
}

/** Passive identity/arguments; the existing actor remains the only native owner. */
internal class DesktopVeyraVerifiedBinding internal constructor(
    val mpvPath: Path, private val corePath: Path, private val runtimePath: Path,
    private val projectId: String, val profileSha256: String,
) {
    fun filterArguments(options: NvidiaVideoOptions, actualSourceVersion: Long, configurationVersion: Long): String {
        require(actualSourceVersion > 0 && configurationVersion > 0 && configurationVersion < Long.MAX_VALUE)
        fun quoted(value: String) = "%${value.toByteArray(Charsets.UTF_8).size}%$value"
        return "bilipai-rtx=dll=${quoted(corePath.toString())}:runtime=${quoted(runtimePath.toString())}:project=${quoted(projectId)}" +
            ":session=$actualSourceVersion:generation=$configurationVersion:scale=${options.scale}:quality=4:hdr=${if(options.hdr) "yes" else "no"}:peak=1000:timeout=1000"
    }
}
