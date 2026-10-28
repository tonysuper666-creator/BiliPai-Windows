package com.bilipai.desktop.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.*

class SignedDistributionFeedTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val base = "https://learn.tonysuper666.com/bilipai"
    private val hash = "ab".repeat(32)
    private fun sign(bytes: ByteArray, kind: String): String = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update("BiliPai-Windows/$kind/v1\n".toByteArray())
        update(bytes)
        Base64.getEncoder().encodeToString(sign())
    }
    private fun envelope(value: JsonObject, kind: String = "manifest"): String {
        val bytes = value.toString().toByteArray()
        return buildJsonObject { put("payload", Base64.getEncoder().encodeToString(bytes)); put("signature", sign(bytes, kind)) }.toString()
    }
    private fun manifest(vararg fields: Pair<String, JsonElement>) = buildJsonObject {
        put("schema", 1); put("kind", "manifest"); put("channel", "dev"); put("sequence", 2)
        put("issuedAt", 1000); put("expiresAt", 2000); put("rolloutPercent", 100)
        put("release", buildJsonObject {
            put("version", "0.2.427.202"); put("releaseId", 2); put("assetId", 3)
            put("assetName", "BiliPai-Windows-0.2.427.202-x64.zip"); put("size", 100)
            put("downloadUrl", "$base/artifacts/0.2.427.202/BiliPai-Windows-0.2.427.202-x64.zip")
            put("sha256", hash); put("archiveSignature", sign(hash.toByteArray(), "archive"))
        })
        fields.forEach { (key, value) -> put(key, value) }
    }
    private fun verify(raw: String) = SignedDistributionFeed.manifest(raw, "dev", base, 1500, publicKey)

    @Test fun `Node signer interoperates with JVM pinned production public key`() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/distribution/node-signed-expired-manifest.json"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val manifest = SignedDistributionFeed.manifest(raw, "dev", base, now = 1500)
        assertEquals("0.0.2", manifest.release?.version)
        // This public fixture must never be usable as a live update.
        assertFails { SignedDistributionFeed.manifest(raw, "dev", base) }
    }

    @Test fun `valid release binds package hash URL and exact offer`() {
        val raw = envelope(manifest())
        val release = assertNotNull(verify(raw).release)
        assertEquals(hash, release.sha256)
        assertEquals(raw, release.update(raw).signedManifest)
    }

    @Test fun `one changed manifest byte and wrong key are rejected`() {
        val raw = envelope(manifest())
        val parsed = Json.parseToJsonElement(raw).jsonObject
        val bytes = Base64.getDecoder().decode(parsed.getValue("payload").jsonPrimitive.content)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        val changed = JsonObject(parsed + ("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes))))
        assertFails { verify(changed.toString()) }
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertFails { SignedDistributionFeed.manifest(raw, "dev", base, 1500, Base64.getEncoder().encodeToString(other.public.encoded)) }
    }

    @Test fun `a changed archive cannot match signed digest`() {
        val m = verify(envelope(manifest()))
        val release = assertNotNull(m.release)
        DistributionTrust.verify(release.sha256.toByteArray(), release.archiveSignature, "archive", publicKey)
        assertFails { DistributionTrust.verify(("cd".repeat(32)).toByteArray(), release.archiveSignature, "archive", publicKey) }
        assertFails { DesktopUpdater.verifyChecksum("cd".repeat(32), release.sha256) }
    }

    @Test fun `expired future wrong channel and config substitution fail closed`() {
        assertFails { verify(envelope(manifest("expiresAt" to JsonPrimitive(1500)))) }
        assertFails { verify(envelope(manifest("issuedAt" to JsonPrimitive(1900)))) }
        assertFails { verify(envelope(manifest("channel" to JsonPrimitive("stable")))) }
        assertFails { verify(envelope(manifest(), "config")) }
    }

    @Test fun `signed foreign URLs and escaped paths are refused`() {
        val original = manifest()
        val release = original.getValue("release").jsonObject
        for (url in listOf("https://evil.example/payload.zip", "$base/artifacts/../secret.zip", "$base/artifacts/%2e%2e/x.zip",
            "http://learn.tonysuper666.com/bilipai/artifacts/a.zip", "https://learn.tonysuper666.com.evil.example/bilipai/artifacts/a.zip")) {
            assertFails { verify(envelope(JsonObject(original + ("release" to JsonObject(release + ("downloadUrl" to JsonPrimitive(url))))))) }
        }
    }

    @Test fun `withdrawal and rollout are signed and deterministic`() {
        val m = verify(envelope(manifest("withdrawn" to JsonArray(listOf(JsonPrimitive("0.2.427.202"))), "rolloutPercent" to JsonPrimitive(0))))
        assertTrue("0.2.427.202" in m.withdrawn)
        assertFalse(SignedDistributionFeed.inRollout("id", "version", m.rolloutPercent))
        assertTrue(SignedDistributionFeed.inRollout("id", "version", 100))
        assertEquals(SignedDistributionFeed.inRollout("id", "version", 50), SignedDistributionFeed.inRollout("id", "version", 50))
        assertFails { verify(envelope(manifest("rolloutPercent" to JsonPrimitive(101)))) }
    }

    @Test fun `signed remote policy cannot contain executable or unbounded fields`() {
        val value = buildJsonObject {
            put("schema", 1); put("kind", "config"); put("channel", "dev"); put("sequence", 1)
            put("issuedAt", 1000); put("expiresAt", 2000)
            put("policy", Json.parseToJsonElement(Json.encodeToString(DistributionRemotePolicy(false, 2, "正在维护"))))
        }
        val result = SignedDistributionFeed.config(envelope(value, "config"), "dev", 1500, publicKey)
        assertFalse(result.policy.automaticUpdatesEnabled)
        assertEquals(2, result.policy.checkIntervalHours)
        assertFails { SignedDistributionFeed.config(envelope(JsonObject(value + ("script" to JsonPrimitive("exec"))), "config"), "dev", 1500, publicKey) }
        assertFails { DistributionRemotePolicy(checkIntervalHours = 0).validate() }
    }

    @Test fun `installation identity persists and manifest rollback or equivocation is refused`() {
        val root = Files.createTempDirectory("distribution-state").toRealPath()
        val first = DistributionState(root, "dev")
        val id = first.installationId()
        assertEquals(id, DistributionState(root, "stable").installationId())
        first.accept("manifest", 5, "original")
        DistributionState(root, "dev").accept("manifest", 5, "original")
        assertFails { first.accept("manifest", 4, "older") }
        assertFails { first.accept("manifest", 5, "replaced") }
        first.accept("manifest", 6, "withdrawal")
        DistributionState(root, "stable").accept("manifest", 1, "separate channel")
    }
}
