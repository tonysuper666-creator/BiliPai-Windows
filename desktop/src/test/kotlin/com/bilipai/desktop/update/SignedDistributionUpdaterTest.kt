package com.bilipai.desktop.update

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class SignedDistributionUpdaterTest {
    private class Fixture : AutoCloseable {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val home = Files.createTempDirectory("signed-updater").toRealPath()
        val root = home.resolve("BiliPai/updates")
        val base = DistributionTrust.DEFAULT_BASE
        val version = "0.2.427.202"
        val requests = mutableListOf<Request>()
        val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("BiliPai Windows/BiliPai Windows.exe")); it.write("fixture-never-launched".toByteArray()); it.closeEntry()
        } }.toByteArray()
        var servedZip = zip
        var sequence = 1L
        var withdrawn = false
        var corruptManifest = false
        var launches = 0
        val owners = mutableListOf<DesktopUpdater>()
        fun sign(kind: String, bytes: ByteArray): String = Signature.getInstance("Ed25519").run {
            initSign(pair.private); update("BiliPai-Windows/$kind/v1\n".toByteArray()); update(bytes)
            Base64.getEncoder().encodeToString(sign())
        }
        fun document(kind: String): ByteArray {
            // Fixed timestamp per fixture so same sequence means exactly the same signed bytes.
            val value = buildJsonObject {
                put("schema",1); put("kind",kind); put("channel","dev"); put("sequence",sequence)
                put("issuedAt",time); put("expiresAt",time+3600)
                if (kind == "config") put("policy", buildJsonObject {
                    put("automaticUpdatesEnabled",true); put("checkIntervalHours",2); put("announcement","测试公告")
                }) else {
                    put("rolloutPercent",100)
                    put("withdrawn",JsonArray(if(withdrawn) listOf(JsonPrimitive(version)) else emptyList()))
                    put("release",buildJsonObject {
                        put("version",version); put("releaseId",1); put("assetId",2)
                        put("assetName","BiliPai-Windows-$version-x64.zip"); put("size",zip.size)
                        put("downloadUrl","$base/artifacts/$version/BiliPai-Windows-$version-x64.zip")
                        val hash = MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) }
                        put("sha256",hash); put("archiveSignature",sign("archive",hash.toByteArray()))
                    })
                }
            }.toString().toByteArray()
            val signature = sign(kind,value).let { if(corruptManifest && kind == "manifest") "A".repeat(86)+"==" else it }
            return buildJsonObject { put("payload",Base64.getEncoder().encodeToString(value)); put("signature",signature) }.toString().toByteArray()
        }
        val time = System.currentTimeMillis()/1000
        fun updater(mirrorMetadata: Boolean = false): DesktopUpdater = DesktopUpdater.forIntegrationTest("0.2.427.201",DistributionTrust.REPOSITORY,root,
            OkHttpClient.Builder().addInterceptor { chain ->
                val request=chain.request(); requests += request
                assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
                if (request.url.encodedPath.endsWith(".json")) {
                    val scope = assertNotNull(request.tag(VeyraCatalogHttpScope::class.java))
                    scope.requireAllowed(request)
                    // Exercise the actual request's redirect policy, including all
                    // CDN hosts that the ZIP downloader separately permits.
                    for (host in listOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) {
                        assertFails { scope.requireAllowed(request.newBuilder().url("https://$host/redirected.json").build()) }
                    }
                }
                val bytes = when {
                    (request.url.encodedPath.endsWith("/updates/dev.json") || request.url.host == "github.com" && request.url.encodedPath.endsWith("/dev.json")) -> {
                        assertEquals(if (request.url.host == "github.com") emptySet() else setOf("version","channel","installationId"),request.url.queryParameterNames)
                        document("manifest")
                    }
                    request.url.encodedPath.endsWith("/config-dev.json") -> {
                        assertNull(request.url.query)
                        document("config")
                    }
                    request.url.encodedPath.endsWith("/config/dev.json") -> document("config")
                    request.url.encodedPath.endsWith(".zip") -> servedZip
                    else -> error("Unexpected network request: ${request.url}")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()
            }.build(),home,{},channel="dev",distributionBase=base,
            signedPublicKey=Base64.getEncoder().encodeToString(pair.public.encoded),
            manifestUrl=if(mirrorMetadata) "https://github.com/${DistributionTrust.REPOSITORY}/releases/download/test/dev.json" else null,
            configUrl=if(mirrorMetadata) "https://github.com/${DistributionTrust.REPOSITORY}/releases/download/test/config-dev.json" else null,
            processFactory={ launches++; error("Fixture must not launch a process") }).also(owners::add)
        override fun close() { owners.forEach { it.close() }; home.toFile().deleteRecursively() }
    }

    @Test fun `signed feed uses real downloader and retained stage recovery`() = runBlocking {
        Fixture().use { f ->
            val updater=f.updater()
            val update=assertIs<UpdateState.Available>(updater.check()).update
            assertEquals("测试公告",updater.remotePolicy.value.announcement)
            val prepared=assertNotNull(updater.prepareUpdate(update))
            assertTrue(Files.isRegularFile(prepared.executable))
            updater.close()
            val recovered=assertNotNull(f.updater().restorePreparedUpdate())
            assertEquals(update,recovered.update)
            assertEquals(0,f.launches)
        }
    }

    @Test fun `one byte changed in downloaded package is rejected before extraction`() = runBlocking {
        Fixture().use { f ->
            val updater=f.updater()
            val update=assertIs<UpdateState.Available>(updater.check()).update
            f.servedZip=f.zip.copyOf().also { it[it.size/2]=(it[it.size/2].toInt() xor 1).toByte() }
            assertNull(updater.prepareUpdate(update))
            assertIs<UpdateState.Failed>(updater.state.value)
            assertEquals(0,f.launches)
            assertFalse(Files.exists(f.root.resolve("active-install.json")))
        }
    }

    @Test fun `withdrawal after download rejects activation without starting any child`() = runBlocking {
        Fixture().use { f ->
            val updater=f.updater()
            val update=assertIs<UpdateState.Available>(updater.check()).update
            val prepared=assertNotNull(updater.prepareUpdate(update))
            f.withdrawn=true; f.sequence++
            assertFalse(updater.activatePreparedUpdate(prepared))
            assertEquals(0,f.launches)
            assertFalse(Files.exists(f.root.resolve("active-install.json")))
        }
    }

    @Test fun `invalid manifest never falls back to unsigned GitHub`() = runBlocking {
        Fixture().use { f ->
            f.corruptManifest=true
            assertIs<UpdateState.Failed>(f.updater().check())
            assertTrue(f.requests.none { it.url.host == "api.github.com" || it.url.encodedPath.endsWith(".zip") })
        }
    }

    @Test fun `GitHub metadata never receives installation ID and cannot redirect to asset CDNs`() = runBlocking {
        Fixture().use { f ->
            assertIs<UpdateState.Available>(f.updater(mirrorMetadata = true).check())
            assertEquals(2, f.requests.size)
            assertTrue(f.requests.all { it.url.host == "github.com" && it.url.query == null })
        }
    }
}
