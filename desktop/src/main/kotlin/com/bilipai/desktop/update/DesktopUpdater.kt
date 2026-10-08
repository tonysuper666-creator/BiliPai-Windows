package com.bilipai.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A build-configured GitHub Windows ZIP updater. It never downloads or installs Android APKs. */
class DesktopUpdater private constructor(
    private val config: UpdateConfig?,
    private val updateRoot: Path,
    private val client: OkHttpClient,
    private val childLocalAppData: Path?,
    private val onProcessStarted: (Process) -> Unit,
    private val catalogLookupForTest: (suspend (WindowsUpdate) -> VerifiedVeyraCompatibleOffer?)? = null,
) {
    constructor() : this(loadBuildConfig(), defaultUpdateRoot(), defaultClient(), null, {})

    private val json = Json { ignoreUnknownKeys = true }
    private val repository = config?.windowsReleaseRepository?.takeIf(::validRepository)
    private val installedVersion = config?.version?.let(DesktopVersion::parse)
    private val disabledReason = when {
        config == null -> "此构建缺少 Windows 更新配置"
        repository == null -> "尚未配置 Windows 发布仓库；启用发布流水线后可检查桌面更新"
        installedVersion == null -> "此构建的 Windows 版本标识无效"
        config.channel !in setOf("stable", "prerelease") -> "此构建的更新渠道无效"
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) && System.getenv("LOCALAPPDATA").isNullOrBlank() -> "Windows 本地应用数据目录不可用"
        else -> null
    }
    private val mutableState = MutableStateFlow<UpdateState>(disabledReason?.let(UpdateState::Disabled) ?: UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var retainedPrepared: PreparedUpdate? = null
    private var retainedArchiveSha256: String? = null
    private val veyraCatalog by lazy { DesktopVeyraCatalogResolver(::fetchCatalogText) }

    internal suspend fun resolveVeyraCatalog(update: WindowsUpdate): VerifiedVeyraCompatibleOffer? = withContext(Dispatchers.IO) {
        validateUpdate(update)
        val testLookup = catalogLookupForTest
        if (testLookup != null) testLookup(update)
        else {
            require(repository == DesktopVeyraCatalogTrust.REPOSITORY) { "兼容目录必须来自本应用的 Windows 发布仓库" }
            veyraCatalog.resolve(update)
        }
    }

    /** A failed selected catalog cannot leave Available to trigger an automatic retry loop. */
    internal suspend fun rejectVeyraCatalog(update: WindowsUpdate, failure: Exception) = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            currentCoroutineContext().ensureActive()
            if ((state.value as? UpdateState.Available)?.update == update)
                mutableState.value = UpdateState.Failed(failure.message ?: "Windows 兼容目录验证失败，当前版本继续运行")
        } finally { mutex.unlock() }
    }

    /** Explicit UI checks bypass the six-hour background debounce. A prepared update is retained. */
    suspend fun check(force: Boolean = true): UpdateState = withContext(Dispatchers.IO) {
        if (disabledReason != null || !mutex.tryLock()) return@withContext state.value
        val previousState = state.value
        try {
            retainedPrepared?.let { return@withContext UpdateState.Prepared(it).also { result -> mutableState.value = result } }
            if (!force && !shouldCheck(lastCheckTime(), System.currentTimeMillis())) return@withContext state.value
            mutableState.value = UpdateState.Checking
            val releases = fetchReleases(requireNotNull(repository))
            val result = evaluateReleases(releases, requireNotNull(config), requireNotNull(installedVersion))
            if (result !is UpdateState.Failed) {
                UpdateStorage.verifiedRoot(updateRoot)
                Files.writeString(updateRoot.resolve("last-check.txt"), System.currentTimeMillis().toString())
                pruneInstallations()
            }
            mutableState.value = result
            result
        } catch (cancelled: CancellationException) {
            mutableState.value = previousState
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            UpdateState.Failed(error.message ?: "Windows 更新检查失败").also { mutableState.value = it }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun autoCheck(): UpdateState = check(force = false)

    /** Downloads, verifies and extracts a side-by-side installation while the current app keeps running. */
    suspend fun prepareUpdate(update: WindowsUpdate): PreparedUpdate? = prepareUpdateInternal(update, null)

    /** Own signed-compatible full app bundles use the same stage, idle activation and rollback. */
    internal suspend fun prepareVeyraUpdate(offer: VerifiedVeyraCompatibleOffer): PreparedUpdate? =
        prepareUpdateInternal(offer.update, offer)

    private suspend fun prepareUpdateInternal(update: WindowsUpdate, compatibleOffer: VerifiedVeyraCompatibleOffer?): PreparedUpdate? = withContext(Dispatchers.IO) {
        if (disabledReason != null || !mutex.tryLock()) return@withContext null
        var staging: Path? = null
        var complete = false
        try {
            retainedPrepared?.let {
                require(compatibleOffer == null || compatibleOffer.update == update &&
                    retainedArchiveSha256.equals(compatibleOffer.zipSha256, ignoreCase = true)) {
                    "Prepared archive differs from the signed compatibility catalog"
                }
                return@withContext it.takeIf { prepared -> prepared.update == update }
            }
            validateUpdate(update)
            require((state.value as? UpdateState.Available)?.update == update) { "Windows 更新目标已改变，请重新检查" }
            // Generic callers also resolve before any stage or Downloading state. Only absent means ordinary.
            val offer = compatibleOffer ?: resolveVeyraCatalog(update)
            require(offer == null || offer.update == update) { "Windows 兼容目录目标已改变" }
            val compatibleSha256 = offer?.zipSha256
            currentCoroutineContext().ensureActive()
            val settings = requireNotNull(config)
            staging = UpdateStorage.createStage(updateRoot, requireNotNull(repository), update.assetId)
            val archive = staging.resolve("download.zip")
            mutableState.value = UpdateState.Downloading(0, update.size)
            val expectedHash = parseChecksum(fetchText(update.checksumUrl, 128 * 1024), update.assetName)
            compatibleSha256?.let { verifyChecksum(expectedHash, it) }
            val digest = download(update, archive)
            currentCoroutineContext().ensureActive()
            mutableState.value = UpdateState.Verifying
            verifyChecksum(digest, expectedHash)
            val context = currentCoroutineContext()
            val executable = SafeUpdateZip.extract(archive, Files.createDirectory(staging.resolve("app")), settings.executable) {
                context.ensureActive()
            }
            Files.delete(archive)
            val prepared = PreparedUpdate(update, staging, executable)
            retainedPrepared = prepared
            retainedArchiveSha256 = digest.lowercase()
            complete = true
            mutableState.value = UpdateState.Prepared(prepared)
            pruneInstallations()
            prepared
        } catch (cancelled: CancellationException) {
            mutableState.value = UpdateState.Available(update)
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            mutableState.value = UpdateState.Failed(error.message ?: "Windows 更新准备失败，当前版本继续运行")
            null
        } finally {
            if (!complete) staging?.let { UpdateStorage.deleteOwnedStage(updateRoot, it, requireNotNull(repository)) }
            mutex.unlock()
        }
    }

    /** The UI calls this only after its atomic playback/task idle check. True permits the old app to exit. */
    suspend fun activatePreparedUpdate(prepared: PreparedUpdate): Boolean = withContext(Dispatchers.IO) {
        if (disabledReason != null || !mutex.tryLock()) return@withContext false
        var attempt: LaunchAttempt? = null
        var committed = false
        try {
            require(prepared === retainedPrepared) { "更新准备状态已失效，请重新检查更新" }
            validateUpdate(prepared.update)
            require(UpdateStorage.ownedStage(updateRoot, prepared.stagingDirectory, requireNotNull(repository)) != null &&
                validStoredExecutable(prepared.executable.toString()) == prepared.executable) { "更新安装目录校验失败" }
            mutableState.value = UpdateState.Launching
            attempt = launchInstallation(prepared.executable, emptyList())
            require(StartupHealth.await(attempt.process, attempt.healthFile, attempt.token, expectedVersion = prepared.update.version)) { "新版本未通过版本、窗口和播放组件启动检查，当前版本继续运行" }
            currentCoroutineContext().ensureActive()
            val previous = previousInstallation()
            writeActiveInstallation(ActiveInstallation(requireNotNull(repository), prepared.update.version, prepared.executable.toString(),
                previous?.first, previous?.second?.toString()))
            committed = true
            retainedPrepared = null
            mutableState.value = UpdateState.Launched
            pruneInstallations()
            true
        } catch (cancelled: CancellationException) {
            mutableState.value = UpdateState.Prepared(prepared)
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            retainedPrepared = null
            mutableState.value = UpdateState.Failed(error.message ?: "Windows 更新启动失败，当前版本继续运行")
            false
        } finally {
            if (!committed) attempt?.let { StartupHealth.terminate(it.process) }
            mutex.unlock()
        }
    }

    private fun validateUpdate(update: WindowsUpdate) {
        require(DesktopVersion.parse(update.version)?.let { it > requireNotNull(installedVersion) } == true) { "更新版本必须高于当前桌面版本" }
        require(update.releaseId > 0 && update.assetId > 0 && isWindowsZip(update.assetName) && update.size in 1..MAX_DOWNLOAD_BYTES) { "Windows 更新包名称、标识或大小异常" }
        require(trustedAssetUrl(update.downloadUrl, requireNotNull(repository)) && trustedAssetUrl(update.checksumUrl, repository)) { "更新地址与构建配置的发布仓库不一致" }
    }

    private suspend fun fetchReleases(repository: String): List<GitHubRelease> {
        val releases = mutableListOf<GitHubRelease>()
        for (page in 1..3) {
            val response = fetchText("https://api.github.com/repos/$repository/releases?per_page=100&page=$page", 8 * 1024 * 1024)
            val batch = json.decodeFromString<List<GitHubRelease>>(response)
            releases += batch
            if (batch.size < 100) break
        }
        return releases
    }

    /** A cancellation watcher closes the socket even while the IO thread is reading a large body. */
    private suspend fun <T> executeRequest(request: Request, consume: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use(consume)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            cancellation.cancel()
        }
    }

    private suspend fun fetchText(url: String, limit: Int): String = executeRequest(Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json").header("User-Agent", "BiliPai-Windows-Updater").build()) { response ->
        require(response.isSuccessful) { "更新服务器返回 HTTP ${response.code}" }
        val body = response.body
        require(body.contentLength() <= limit) { "更新元数据超过大小限制" }
        body.byteStream().use { input ->
            val bytes = input.readNBytes(limit + 1)
            require(bytes.size <= limit) { "更新元数据超过大小限制" }
            bytes.toString(Charsets.UTF_8)
        }
    }

    private suspend fun fetchCatalogText(url: String, limit: Int, asset: Boolean): String {
        val scope = VeyraCatalogHttpScope(url, asset)
        val request = Request.Builder().url(url).tag(VeyraCatalogHttpScope::class.java, scope)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "BiliPai-Windows-Updater").build()
        scope.requireAllowed(request)
        return executeRequest(request) { response ->
            scope.requireAllowed(response.request)
            require(response.isSuccessful) { "Windows 兼容目录服务器返回 HTTP ${response.code}" }
            val body = response.body
            require(body.contentLength() <= limit) { "Windows 兼容目录超过大小限制" }
            val bytes = body.byteStream().use { it.readNBytes(limit + 1) }
            require(bytes.size <= limit) { "Windows 兼容目录超过大小限制" }
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }

    private suspend fun download(update: WindowsUpdate, destination: Path): String {
        val context = currentCoroutineContext()
        return executeRequest(Request.Builder().url(update.downloadUrl).header("User-Agent", "BiliPai-Windows-Updater").build()) { response ->
            require(response.isSuccessful) { "更新下载返回 HTTP ${response.code}" }
            val body = response.body
            require(body.contentLength() < 0 || body.contentLength() == update.size) { "更新包声明的文件大小不一致" }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastNotification = 0L
            body.byteStream().use { input ->
                Files.newOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= MAX_DOWNLOAD_BYTES && received <= update.size) { "更新下载大小超限" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val now = System.currentTimeMillis()
                        if (now - lastNotification >= 100 || received == update.size) {
                            mutableState.value = UpdateState.Downloading(received, update.size)
                            lastNotification = now
                        }
                    }
                }
            }
            require(received == update.size) { "更新下载不完整" }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private suspend fun launchInstallation(executable: Path, args: List<String>): LaunchAttempt {
        val stage = stageForExecutable(executable) ?: error("更新启动路径无效")
        val launchDirectory = Files.createDirectory(stage.resolve("launch-${UUID.randomUUID()}"))
        val token = UUID.randomUUID().toString()
        val healthFile = launchDirectory.resolve("startup-health.txt")
        val builder = ProcessBuilder(listOf(executable.toString()) + args + listOf("--update-health-file", healthFile.toString(), "--update-health-token", token))
            .directory(executable.parent.toFile())
            .redirectOutput(launchDirectory.resolve("startup-output.log").toFile())
            .redirectError(launchDirectory.resolve("startup-error.log").toFile())
        childLocalAppData?.let { isolated ->
            builder.environment().apply {
                this["LOCALAPPDATA"] = isolated.toString()
                // Harness children must use the bundled native library and their own data directory.
                listOf("BILIPAI_MPV_PATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach(::remove)
            }
        }
        val process = builder.start()
        try { onProcessStarted(process) }
        catch (error: Throwable) { StartupHealth.terminate(process); throw error }
        return LaunchAttempt(process, healthFile, token)
    }

    private fun lastCheckTime(): Long = runCatching { Files.readString(updateRoot.resolve("last-check.txt")).trim().toLong() }.getOrDefault(0)

    private fun readActiveInstallation(): ActiveInstallation? = runCatching {
        val file = updateRoot.resolve("active-install.json")
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file) > 64 * 1024) return@runCatching null
        json.decodeFromString<ActiveInstallation>(Files.readString(file)).takeIf { it.repository == repository }
    }.getOrNull()

    private fun writeActiveInstallation(installation: ActiveInstallation) {
        UpdateStorage.verifiedRoot(updateRoot)
        val temporary = Files.createTempFile(updateRoot, "active-install-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(ActiveInstallation.serializer(), installation))
            try {
                Files.move(temporary, updateRoot.resolve("active-install.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, updateRoot.resolve("active-install.json"), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun stageForExecutable(executable: Path): Path? = config?.executable?.let {
        UpdateStorage.executableStage(updateRoot, executable, it)
    }

    private fun validStoredExecutable(raw: String): Path? = runCatching {
        val executable = UpdateStorage.existingPathWithoutLinks(Path.of(raw))
        executable.takeIf { stageForExecutable(it) != null }
    }.getOrNull()

    private fun previousInstallation(): Pair<String, Path>? {
        val currentCommand = ProcessHandle.current().info().command().orElse(null)?.let(::validStoredExecutable)
        if (currentCommand != null) return requireNotNull(config).version to currentCommand
        val active = readActiveInstallation() ?: return null
        return validStoredExecutable(active.executablePath)?.let { active.version to it }
    }

    private fun pruneInstallations() {
        // Cleanup is best effort and never changes an otherwise successful preparation or activation.
        runCatching {
            val trusted = requireNotNull(repository)
            val active = readActiveInstallation()
            val preserved = mutableSetOf<Path>()
            listOfNotNull(active?.executablePath, active?.previousExecutablePath).forEach { raw ->
                validStoredExecutable(raw)?.let { stageForExecutable(it) }?.let(preserved::add)
            }
            retainedPrepared?.stagingDirectory?.let(preserved::add)
            // Retain every installation with an observable running command, including an older window.
            ProcessHandle.allProcesses().use { processes ->
                processes.forEach { handle -> handle.info().command().orElse(null)?.let { raw ->
                    runCatching { UpdateStorage.stageContaining(updateRoot, Path.of(raw), trusted) }.getOrNull()?.let(preserved::add)
                } }
            }
            UpdateStorage.prune(updateRoot, trusted, preserved)
        }
    }

    internal suspend fun launchRegisteredInstall(args: Array<String>): Boolean {
        if (disabledReason != null || args.any { it in setOf("--update-health-file", "--update-health-token", "--backend-smoke", "--player-self-test") }) return false
        val active = readActiveInstallation() ?: return false
        val current = requireNotNull(installedVersion)
        if (DesktopVersion.parse(active.version)?.let { it > current } != true) return false
        val candidates = listOfNotNull(
            validStoredExecutable(active.executablePath)?.let { active.version to it },
            active.previousVersion?.let { version ->
                if (DesktopVersion.parse(version)?.let { it > current } != true) null
                else active.previousExecutablePath?.let(::validStoredExecutable)?.let { version to it }
            },
        )
        for ((version, executable) in candidates) {
            val currentCommand = ProcessHandle.current().info().command().orElse(null)
            if (currentCommand?.let { runCatching { UpdateStorage.existingPathWithoutLinks(Path.of(it)) == executable }.getOrDefault(false) } == true) continue
            var attempt: LaunchAttempt? = null
            var committed = false
            try {
                attempt = launchInstallation(executable, args.toList())
                if (StartupHealth.await(attempt.process, attempt.healthFile, attempt.token, expectedVersion = version)) {
                    currentCoroutineContext().ensureActive()
                    if (version != active.version || executable.toString() != active.executablePath) {
                        writeActiveInstallation(ActiveInstallation(requireNotNull(repository), version, executable.toString()))
                    }
                    committed = true
                    pruneInstallations()
                    return true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                // A damaged active update falls back to its preceding healthy version or original launcher.
            } finally { if (!committed) attempt?.let { StartupHealth.terminate(it.process) } }
        }
        return false
    }

    companion object {
        const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
        const val STARTUP_TIMEOUT_MS = 30_000L
        internal const val STARTUP_STABILITY_MS = 1_500L
        internal const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private fun loadBuildConfig(): UpdateConfig? = runCatching {
            DesktopUpdater::class.java.getResourceAsStream("/windows-update.json")?.use {
                Json { ignoreUnknownKeys = true }.decodeFromString<UpdateConfig>(it.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrNull()
        internal fun packagedVersion(): String = requireNotNull(loadBuildConfig()?.version?.takeIf { DesktopVersion.parse(it) != null }) {
            "Windows build version is unavailable"
        }
        private fun defaultUpdateRoot(): Path = Path.of(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".local", "share").toString(), "BiliPai", "updates").toAbsolutePath().normalize()
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS).followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                chain.request().tag(VeyraCatalogHttpScope::class.java)?.requireAllowed(chain.request())
                chain.proceed(chain.request())
            }.build()

        /** Internal opt-in harness only; the public constructor always uses embedded production settings. */
        internal fun forIntegrationTest(
            currentVersion: String,
            repository: String,
            updateRoot: Path,
            client: OkHttpClient,
            childLocalAppData: Path,
            onProcessStarted: (Process) -> Unit,
            executable: String = "BiliPai Windows.exe",
            compatibleCatalog: suspend (WindowsUpdate) -> VerifiedVeyraCompatibleOffer? = { null },
        ): DesktopUpdater {
            require(DesktopVersion.parse(currentVersion) != null && validRepository(repository))
            val childData = childLocalAppData.toAbsolutePath().normalize()
            require(updateRoot.toAbsolutePath().normalize() == childData.resolve("BiliPai/updates")) {
                "Integration update root must belong to the isolated child LOCALAPPDATA"
            }
            return DesktopUpdater(UpdateConfig(currentVersion, windowsReleaseRepository = repository, executable = executable),
                updateRoot, client, childData, onProcessStarted, compatibleCatalog)
        }
        /** Call before creating the UI; true means a previously verified newer window is running. */
        fun launchInstalledUpdateIfNewer(args: Array<String>): Boolean = runBlocking {
            withContext(Dispatchers.IO) { DesktopUpdater().launchRegisteredInstall(args) }
        }
        internal fun shouldCheck(previous: Long, now: Long): Boolean = previous <= 0 || now < previous || now - previous >= AUTO_CHECK_INTERVAL_MS
        internal fun validRepository(value: String): Boolean = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$").matches(value)
        internal fun isWindowsZip(name: String): Boolean = name.startsWith("BiliPai-Windows", ignoreCase = true) && name.endsWith(".zip", ignoreCase = true) && '/' !in name && '\\' !in name
        internal fun trustedAssetUrl(url: String, repository: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
                uri.path.startsWith("/$repository/releases/download/") && uri.path.split('/').none { it == "." || it == ".." } &&
                uri.query == null && uri.fragment == null
        }.getOrDefault(false)
        internal fun verifyChecksum(actual: String, expected: String) {
            require(Regex("^[0-9a-fA-F]{64}$").matches(expected) && actual.equals(expected, ignoreCase = true)) { "更新包 SHA-256 校验失败，已保留当前版本" }
        }
        internal fun parseChecksum(raw: String, assetName: String): String {
            val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.size == 1 && Regex("^[0-9a-fA-F]{64}$").matches(lines.single())) return lines.single().lowercase()
            val matching = lines.mapNotNull { line ->
                Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").matchEntire(line)?.takeIf { it.groupValues[2] == assetName }?.groupValues?.get(1)
            }
            require(matching.size == 1) { "更新校验文件缺少唯一对应的 SHA-256" }
            return matching.single().lowercase()
        }

        internal fun evaluateReleaseMetadata(raw: String, currentVersion: String, repository: String, channel: String = "prerelease"): UpdateState =
            evaluateReleases(Json { ignoreUnknownKeys = true }.decodeFromString(raw), UpdateConfig(currentVersion, windowsReleaseRepository = repository, channel = channel), requireNotNull(DesktopVersion.parse(currentVersion)))

        private fun evaluateReleases(releases: List<GitHubRelease>, config: UpdateConfig, current: DesktopVersion): UpdateState {
            val repository = requireNotNull(config.windowsReleaseRepository)
            val newest = releases.filter { !it.draft && (config.channel == "prerelease" || !it.prerelease) }
                .mapNotNull { release -> DesktopVersion.parse(release.tag)?.let { it to release } }.maxByOrNull { it.first }
                ?: return UpdateState.Failed("发布仓库尚无适用于此渠道的 Windows 版本，暂时无法确认更新")
            val (version, release) = newest
            val zip = release.assets.filter { isWindowsZip(it.name) && it.size in 1..MAX_DOWNLOAD_BYTES && it.id > 0 }.singleOrNull()
                ?: return UpdateState.Failed("Windows 版本 ${release.tag} 的更新包尚未就绪，请稍后检查")
            val checksum = release.assets.singleOrNull { it.name == zip.name + ".sha256" && it.size in 1L..128L * 1024 }
                ?: return UpdateState.Failed("Windows 版本 ${release.tag} 缺少校验文件，请稍后检查")
            if (release.id <= 0 || !trustedAssetUrl(zip.downloadUrl, repository) || !trustedAssetUrl(checksum.downloadUrl, repository)) {
                return UpdateState.Failed("Windows 发布资产地址校验失败")
            }
            return if (version > current) UpdateState.Available(WindowsUpdate(release.tag, release.id, zip.id, zip.name, zip.size, zip.downloadUrl, checksum.downloadUrl, release.url))
            else UpdateState.UpToDate(config.version)
        }
    }

    private data class LaunchAttempt(val process: Process, val healthFile: Path, val token: String)
    @Serializable private data class UpdateConfig(
        val version: String,
        val upstreamTag: String = "",
        val upstreamCommit: String = "",
        val windowsReleaseRepository: String? = null,
        val channel: String = "prerelease",
        val executable: String = "BiliPai Windows.exe",
    )
    @Serializable private data class ActiveInstallation(
        val repository: String,
        val version: String,
        val executablePath: String,
        val previousVersion: String? = null,
        val previousExecutablePath: String? = null,
    )
    @Serializable private data class GitHubRelease(
        val id: Long,
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val url: String,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )
    @Serializable private data class GitHubAsset(
        val id: Long,
        val name: String,
        val size: Long,
        @SerialName("browser_download_url") val downloadUrl: String,
    )
}
