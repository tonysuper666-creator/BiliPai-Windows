package su.litvak.chromecast.api.v2

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Real loopback TLS, protobuf, receiver/media messages and original JmDNS discovery. */
@Execution(ExecutionMode.SAME_THREAD)
class DesktopGoogleCastProtocolTest {
    @TempDir
    lateinit var fixtures: Path

    @TestFactory
    fun actualProtocolFixtures(): List<DynamicTest> {
        val base = "/google-cast-v2-fixture/"
        val inventory = requireNotNull(javaClass.getResourceAsStream(base + "fixture-files.json"))
            .use { ObjectMapper().readTree(it) }
        inventory.forEach { item ->
            val name = item.path("name").asText()
            require(Path.of(name).nameCount == 1 && name != "." && name != "..")
            val bytes = requireNotNull(javaClass.getResourceAsStream(base + name)).use { it.readAllBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(actual == item.path("sha256").asText()) { "Synthetic fixture digest mismatch" }
            Files.write(fixtures.resolve(name), bytes)
        }
        return GoogleCastFixtureTest.cases(fixtures).map { item ->
            DynamicTest.dynamicTest(item.name()) { item.body().run() }
        }
    }
}
