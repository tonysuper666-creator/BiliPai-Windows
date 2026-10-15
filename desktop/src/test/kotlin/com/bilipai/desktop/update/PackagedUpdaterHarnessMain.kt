package com.bilipai.desktop.update

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exported test entry only. It calls the existing real EXE fixture; it does not
 * add an application command, alter updater admission, or compile anything. */
object PackagedUpdaterHarnessMain {
    private data class Input(val path: Path, val sha256: String) {
        fun verify() {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "Reviewed updater package changed" }
        }
    }

    private fun required(name: String): String =
        requireNotNull(System.getProperty(name)?.takeIf { it.isNotBlank() }) { "Missing opt-in property: $name" }

    private fun input(name: String, hashName: String): Input {
        val path = UpdateStorage.existingPathWithoutLinks(Path.of(required(name)))
        require(Files.isRegularFile(path) && path.fileName.toString().endsWith(".zip", ignoreCase = true))
        val hash = required(hashName)
        require(Regex("[0-9a-f]{64}").matches(hash)) { "A full reviewed package SHA-256 is required" }
        return Input(path, hash).also { it.verify() }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "Use explicit updater fixture properties" }
        require(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val current = input("bilipai.updateTestPackage", "bilipai.updateTestPackageSha256")
        val previous = input("bilipai.updatePreviousPackage", "bilipai.updatePreviousPackageSha256")
        require(current.path != previous.path)
        val temporary = UpdateStorage.existingPathWithoutLinks(Path.of(System.getProperty("java.io.tmpdir")))
        val output = UpdateStorage.existingPathWithoutLinks(Path.of(required("bilipai.updateTestReport")))
        require(output.startsWith(temporary) && output != temporary) { "Use a dedicated temporary report directory" }
        val report = output.resolve("updater-smoke.json")
        require(!Files.exists(report)) { "A prior updater result must not be overwritten" }

        DesktopUpdaterIntegrationTest().packagedUpdaterEndToEnd()

        current.verify(); previous.verify()
        val verified = UpdateStorage.existingPathWithoutLinks(report)
        require(Files.size(verified) <= 4 * 1024 * 1024) { "Unexpected updater result size" }
        val result = Json.parseToJsonElement(Files.readString(verified)).jsonObject
        check(result.getValue("passed").jsonPrimitive.boolean) { "Actual updater fixture did not pass" }
        check(result.getValue("portableZipSha256").jsonPrimitive.content == current.sha256)
        println("BILIPAI_PACKAGED_UPDATER_FIXTURE_PASSED")
    }
}
