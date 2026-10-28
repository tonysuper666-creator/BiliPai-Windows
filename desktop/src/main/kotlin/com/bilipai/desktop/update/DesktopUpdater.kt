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

/** A build-configured Windows ZIP updater. It never downloads or installs Android APKs. */
class DesktopUpdater private constructor(
    private val config: UpdateConfig?,
    private val updateRoot: Path,
    private val client: OkHttpClient,
    private val childLocalAppData: Path?,
    private val onProcessStarted: (Process) -> Unit,
    private val catalogLookupForTest: (suspend (WindowsUpdate) -> VerifiedVeyraCompatibleOffer?)? = null,
    private val processFactoryForTest: ((ProcessBuilder) -> Process)? = null,
    private val knownRunningStagesForTest: (() -> Set<Path>)? = null,
    private val signedPublicKeyForTest: String? = null,
) : AutoCloseable {
    constructor() : this(loadBuildConfig(), defaultUpdateRoot(), defaultClient(), null, {})

    private val json = Json { ignoreUnknownKeys = true }
    private val repository = config?.windowsReleaseRepository?.takeIf(::validRepository)
    private val installedVersion = config?.version?.let(DesktopVersion::parse)
    private val disabledReason = when {
        config == null -> "此构建缺少 Windows 更新配置"
        repository == null -> "尚未配置 Windows 发布仓库；启用发布流水线后可检查桌面更新"
        config.source !in setOf("signed", "github-legacy") -> "此构建的更新源无效"
        config.source == "signed" && (!DistributionTrust.validBase(config.distributionBase) || config.channel !in setOf("dev", "stable")) -> "签名更新源或频道无效"
        installedVersion == null -> "此构建的 Windows 版本标识无效"
        config.channel !in setOf("stable", "prerelease", "dev") -> "此构建的更新渠道无效"
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) && System.getenv("LOCALAPPDATA").isNullOrBlank() -> "Windows 本地应用数据目录不可用"
        else -> null
    }
    private val mutableState = MutableStateFlow<UpdateState>(disabledReason?.let(UpdateState::Disabled) ?: UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val signedSource get() = config?.source == "signed"
    private val distributionState by lazy { DistributionState(updateRoot, requireNotNull(config).channel) }
    private val mutableRemotePolicy = MutableStateFlow(DistributionRemotePolicy())
    internal val remotePolicy: StateFlow<DistributionRemotePolicy> = mutableRemotePolicy.asStateFlow()

    private fun signedRelease(update: WindowsUpdate): DistributionRelease {
        val settings = requireNotNull(config)
        val raw = requireNotNull(update.signedManifest) { "此更新缺少签名 manifest" }
        val manifest = SignedDistributionFeed.manifest(raw, settings.channel, settings.distributionBase, publicKey = signedPublicKeyForTest ?: DistributionTrust.PUBLIC_KEY)
        val release = requireNotNull(manifest.release)
        require(release.update(raw) == update && release.version !in manifest.withdrawn) { "签名与更新目标不符，或版本已撤回" }
        return release
    }

    private suspend fun fetchDistributionManifest(): Pair<String, DistributionManifest> {
        val settings = requireNotNull(config)
        val id = distributionState.installationId()
        val base = settings.distributionBase
        val url = settings.manifestUrl ?: "$base/updates/${settings.channel}.json"
        require(url == "$base/updates/${settings.channel}.json" || trustedAssetUrl(url, requireNotNull(repository))) {
            "签名 manifest 必须来自配置服务器或自己的 GitHub 发布仓库"
        }
        // No Bilibili client, cookie jar or account identifiers are shared with this client.
        val query = DistributionTrust.manifestRequestUrl(url, base, settings.version, settings.channel, id)
        val raw = fetchSignedText(query, 32 * 1024)
        val manifest = SignedDistributionFeed.manifest(raw, settings.channel, base, publicKey = signedPublicKeyForTest ?: DistributionTrust.PUBLIC_KEY)
        distributionState.accept("manifest", manifest.sequence, raw)
        return raw to manifest
    }

    private suspend fun refreshRemotePolicy() {
        val settings = requireNotNull(config)
        val ownUrl = "${settings.distributionBase}/config/${settings.channel}.json"
        val url = settings.configUrl ?: ownUrl
        require(url == ownUrl || trustedAssetUrl(url, requireNotNull(repository))) { "远程配置必须来自配置服务器或自己的 GitHub 发布仓库" }
        val rawConfig = fetchSignedText(url, 16 * 1024)
        val remote = SignedDistributionFeed.config(rawConfig, settings.channel, publicKey = signedPublicKeyForTest ?: DistributionTrust.PUBLIC_KEY)
        distributionState.accept("config", remote.sequence, rawConfig)
        mutableRemotePolicy.value = remote.policy
    }

    private suspend fun checkDistribution(): UpdateState {
        refreshRemotePolicy()
        val settings = requireNotNull(config)
        val (raw, manifest) = fetchDistributionManifest()
        val release = manifest.release ?: return UpdateState.UpToDate(settings.version)
        if (release.version in manifest.withdrawn ||
            !SignedDistributionFeed.inRollout(distributionState.installationId(), release.version, manifest.rolloutPercent))
            return UpdateState.UpToDate(settings.version)
        return if (requireNotNull(DesktopVersion.parse(release.version)) > requireNotNull(installedVersion))
            UpdateState.Available(release.update(raw)) else UpdateState.UpToDate(settings.version)
    }

    private suspend fun requireStillPublished(update: WindowsUpdate) {
        if (!signedSource) return
        val expected = signedRelease(update)
        val (_, current) = fetchDistributionManifest()
        require(current.release == expected && expected.version !in current.withdrawn &&
            SignedDistributionFeed.inRollout(distributionState.installationId(), expected.version, current.rolloutPercent)) {
            "更新已撤回或不再向此安装放量，当前版本继续运行"
        }
    }

    private suspend fun fetchSignedText(url: String, limit: Int): String {
        val scope = VeyraCatalogHttpScope(url, asset = false)
        val request = Request.Builder().url(url).tag(VeyraCatalogHttpScope::class.java, scope)
            .header("User-Agent", "BiliPai-Windows-Updater").build()
        scope.requireAllowed(request)
        return executeRequest(request) { response ->
            scope.requireAllowed(response.request)
            require(response.isSuccessful) { "签名更新服务器返回 HTTP ${response.code}" }
            val bytes = response.body.byteStream().use { it.readNBytes(limit + 1) }
            require(bytes.size <= limit) { "签名更新元数据过大" }
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }
    private var retainedPrepared: PreparedUpdate? = null
    private var retainedArchiveSha256: String? = null
    private var retainedReceipt: DesktopPreparedUpdateStorage.Receipt? = null
    private var retainedLease: UpdateStorage.StageLease? = null
    private val lifetime = Any()
    @Volatile private var closed = false
    // Only one launch is possible under mutex. Unknown child retirement or queue
    // rollback preserves its receipt/stage lease even if this owner is disposed.
    @Volatile private var childRetirementPending = false

    /** Disposal marks the owner closed without waiting on a coroutine mutex or an IO operation. */
    override fun close() {
        synchronized(lifetime) { closed = true }
        releaseClosedOwnerIfIdle()
    }

    private fun discardRetainedPrepared() {
        if (childRetirementPending) return
        retainedPrepared = null
        retainedArchiveSha256 = null
        retainedReceipt = null
        retainedLease?.close()
        retainedLease = null
    }

    private fun releaseClosedOwnerIfIdle() {
        if (!closed || childRetirementPending || !mutex.tryLock()) return
        try { discardRetainedPrepared() } finally { mutex.unlock() }
    }

    private fun finishOperation() {
        mutex.unlock()
        // A close that observed this operation busy must not miss the unlock/release boundary.
        releaseClosedOwnerIfIdle()
    }

    private fun ensureOpen() {
        if (closed) throw CancellationException("Windows updater owner is closed")
    }

    /** The exact healthy child may finish its registry commit once admitted. Close before this gate cancels;
     * close afterwards permits that commit to finish. No IO or await holds the lifecycle monitor. */
    private fun beginInstallationCommit() {
        synchronized(lifetime) { ensureOpen() }
    }

    private suspend fun ensureOpenAndActive() {
        currentCoroutineContext().ensureActive()
        ensureOpen()
    }
    private val veyraCatalog by lazy { DesktopVeyraCatalogResolver(::fetchCatalogText) }

    internal suspend fun resolveVeyraCatalog(update: WindowsUpdate): VerifiedVeyraCompatibleOffer? = withContext(Dispatchers.IO) {
        ensureOpenAndActive()
        validateUpdate(update)
        if (signedSource) return@withContext null
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
            ensureOpenAndActive()
            if ((state.value as? UpdateState.Available)?.update == update)
                mutableState.value = UpdateState.Failed(failure.message ?: "Windows 兼容目录验证失败，当前版本继续运行")
        } finally { finishOperation() }
    }

    /** Local READY recovery is independent of network/automatic-check preferences.
     * The same owner mutex/OS lease is required; CLAIMED never grants recovery. */
    internal suspend fun restorePreparedUpdate(): PreparedUpdate? = withContext(Dispatchers.IO) {
        if (closed || disabledReason != null || !mutex.tryLock()) return@withContext null
        try {
            ensureOpenAndActive()
            if (childRetirementPending) return@withContext null
            retainedPrepared?.takeIf { retainedReceipt?.phase == DesktopPreparedUpdateStorage.Phase.READY }
                ?.let { prepared ->
                    mutableState.value = UpdateState.Prepared(prepared)
                    return@withContext prepared
                }
            resumeReadyPrepared()
        } finally { finishOperation() }
    }

    private class ReadyCandidate(val stage: Path, val receipt: DesktopPreparedUpdateStorage.Receipt,
        val version: DesktopVersion)

    /** The existing registry CAS cannot commit at/below its recorded version.
     * Check that same numeric rule before rebuilding/downloading/launching; the
     * later exact-byte CAS remains required for concurrent registry changes. */
    private fun registryAllowsPreparedUpgrade(update: WindowsUpdate,
        observed: ActiveInstallationSnapshot = readActiveInstallationSnapshot()): Boolean {
        val registered = observed.installation ?: return true
        val registeredVersion = requireNotNull(DesktopVersion.parse(registered.version)) {
            "当前安装记录的版本无法确认"
        }
        return requireNotNull(DesktopVersion.parse(update.version)) > registeredVersion
    }

    private fun readyReceiptMatchesPolicy(receipt: DesktopPreparedUpdateStorage.Receipt,
        desired: WindowsUpdate?, signedArchiveSha256: String?): Boolean = runCatching {
        val settings = requireNotNull(config)
        require(receipt.phase == DesktopPreparedUpdateStorage.Phase.READY &&
            receipt.repository == repository && receipt.channel == settings.channel &&
            receipt.executableName == settings.executable)
        validateUpdate(receipt.update)
        if (signedSource) verifyChecksum(receipt.archiveSha256, signedRelease(receipt.update).sha256)
        require(desired == null || receipt.update == desired)
        signedArchiveSha256?.let { verifyChecksum(receipt.archiveSha256, it) }
        true
    }.getOrDefault(false)

    /** No HTTP: select bounded, same-repository/current-channel READY facts,
     * then reacquire their actual lease and reverify/rebuild only an unregistered,
     * non-running stage. Unknown receipt/registry custody is left untouched. */
    private suspend fun resumeReadyPrepared(desired: WindowsUpdate? = null,
        signedArchiveSha256: String? = null): PreparedUpdate? {
        ensureOpenAndActive()
        val trusted = requireNotNull(repository)
        val settings = requireNotNull(config)
        val preserved = try { preservedInstallationStages() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ensureOpenAndActive(); return null }
        val children = try {
            val root = UpdateStorage.verifiedRoot(updateRoot)
            Files.list(root).use { it.limit(MAX_PREPARED_STAGE_ENTRIES + 1L).toList() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ensureOpenAndActive(); return null }
        ensureOpenAndActive()
        if (children.size > MAX_PREPARED_STAGE_ENTRIES) return null
        val candidates = mutableListOf<ReadyCandidate>()
        for (stage in children) {
            ensureOpenAndActive()
            if (stage in preserved) continue
            val lease = UpdateStorage.tryLeaseStage(updateRoot, stage, trusted) ?: continue
            try {
                val receipt = DesktopPreparedUpdateStorage.read(updateRoot, lease, trusted) ?: continue
                if (!readyReceiptMatchesPolicy(receipt, desired, signedArchiveSha256)) continue
                candidates += ReadyCandidate(lease.stage, receipt, requireNotNull(DesktopVersion.parse(receipt.update.version)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ensureOpenAndActive() /* malformed/unknown record: preserve */ }
            finally { lease.close() }
        }
        val ordered = candidates.sortedWith(compareByDescending<ReadyCandidate> { it.version }
            .thenByDescending { it.receipt.update.releaseId }.thenByDescending { it.receipt.update.assetId }
            .thenBy { it.stage.fileName.toString() })
        for (candidate in ordered) {
            ensureOpenAndActive()
            val lease = UpdateStorage.tryLeaseStage(updateRoot, candidate.stage, trusted) ?: continue
            var receiptToInvalidate: DesktopPreparedUpdateStorage.Receipt? = null
            var retained = false
            try {
                val ready = DesktopPreparedUpdateStorage.read(updateRoot, lease, trusted) ?: continue
                if (!candidate.receipt.matches(ready) || !readyReceiptMatchesPolicy(ready, desired, signedArchiveSha256)) continue
                if (lease.stage in preservedInstallationStages() || !registryAllowsPreparedUpgrade(ready.update)) continue
                receiptToInvalidate = ready
                val context = currentCoroutineContext()
                val checkCancelled = { context.ensureActive(); ensureOpen() }
                val archive = DesktopPreparedUpdateStorage.verifyArchive(updateRoot, lease, trusted, ready, checkCancelled)
                val app = DesktopPreparedUpdateStorage.recreateAppDirectory(updateRoot, lease, trusted, ready, checkCancelled)
                val executable = SafeUpdateZip.extract(archive, app, settings.executable, checkCancelled)
                // The extracted tree is fresh, but recheck the retained ZIP and
                // exact receipt again before publishing this owner's memory fact.
                DesktopPreparedUpdateStorage.verifyArchive(updateRoot, lease, trusted, ready, checkCancelled)
                receiptToInvalidate = null
                require(DesktopPreparedUpdateStorage.read(updateRoot, lease, trusted)?.let(ready::matches) == true) {
                    "更新准备记录在恢复期间已改变"
                }
                if (lease.stage in preservedInstallationStages() || !registryAllowsPreparedUpgrade(ready.update)) continue
                val prepared = PreparedUpdate(ready.update, lease.stage, executable)
                ensureOpenAndActive()
                synchronized(lifetime) {
                    context.ensureActive(); ensureOpen()
                    retainedPrepared = prepared
                    retainedArchiveSha256 = ready.archiveSha256
                    retainedReceipt = ready
                    retainedLease = lease
                    retained = true
                    mutableState.value = UpdateState.Prepared(prepared)
                }
                return prepared
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                ensureOpenAndActive()
                // Only a fully decoded, policy-qualified READY may lose recovery
                // eligibility after bad ZIP/extraction. Never delete its tree here.
                val failed = receiptToInvalidate
                if (failed != null) {
                    val stillProtected = runCatching { preservedInstallationStages() }.getOrNull()
                    if (stillProtected != null && lease.stage !in stillProtected)
                        runCatching { DesktopPreparedUpdateStorage.retire(updateRoot, lease, trusted, failed) }
                }
            } finally { if (!retained) lease.close() }
        }
        ensureOpenAndActive()
        return null
    }

    /** Explicit UI checks bypass the six-hour background debounce. A prepared update is retained. */
    suspend fun check(force: Boolean = true): UpdateState = withContext(Dispatchers.IO) {
        if (closed || disabledReason != null || !mutex.tryLock()) return@withContext state.value
        val previousState = state.value
        try {
            ensureOpenAndActive()
            if (childRetirementPending) return@withContext state.value
            retainedPrepared?.takeIf { retainedReceipt?.phase == DesktopPreparedUpdateStorage.Phase.READY }
                ?.let { return@withContext UpdateState.Prepared(it).also { result -> mutableState.value = result } }
            resumeReadyPrepared()?.let { return@withContext UpdateState.Prepared(it) }
            if (!force && !shouldCheck(lastCheckTime(), System.currentTimeMillis(), mutableRemotePolicy.value.checkIntervalHours * 3600_000L)) return@withContext state.value
            mutableState.value = UpdateState.Checking
            val result = if (signedSource) checkDistribution() else
                evaluateReleases(fetchReleases(requireNotNull(repository)), requireNotNull(config), requireNotNull(installedVersion))
            ensureOpenAndActive()
            if (result !is UpdateState.Failed) {
                UpdateStorage.verifiedRoot(updateRoot)
                Files.writeString(updateRoot.resolve("last-check.txt"), System.currentTimeMillis().toString())
                pruneInstallations()
            }
            // Explicit checks (force) always report the newest release; only the automatic path honours the back-off.
            val published = if (!force && result is UpdateState.Available) automaticRetryHold(result.update) ?: result else result
            mutableState.value = published
            published
        } catch (cancelled: CancellationException) {
            if (!closed) mutableState.value = previousState
            throw cancelled
        } catch (error: Exception) {
            // A decode/validation error can arrive together with cancellation.
            try { ensureOpenAndActive() }
            catch (cancelled: CancellationException) {
                if (!closed) mutableState.value = previousState
                throw cancelled
            }
            UpdateState.Failed(error.message ?: "Windows 更新检查失败").also { mutableState.value = it }
        } finally {
            finishOperation()
        }
    }

    suspend fun autoCheck(): UpdateState = check(force = false)

    /** Sleep only the unelapsed durable interval after a skipped startup check.
     * Failure waits a full interval; busy/due owners retry with a bounded delay. */
    internal fun automaticCheckDelayMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (state.value is UpdateState.Disabled || state.value is UpdateState.Failed ||
            state.value is UpdateState.Prepared || state.value is UpdateState.Launched)
            return AUTO_CHECK_INTERVAL_MS
        val interval = mutableRemotePolicy.value.checkIntervalHours * 60 * 60 * 1000L
        val previous = lastCheckTime()
        if (previous <= 0L || nowMs < previous) return 60_000L
        val elapsed = nowMs - previous
        return if (elapsed >= interval) 60_000L else interval - elapsed
    }

    /** Downloads, verifies and extracts a side-by-side installation while the current app keeps running. */
    suspend fun prepareUpdate(update: WindowsUpdate): PreparedUpdate? = prepareUpdateInternal(update, null)

    /** Own signed-compatible full app bundles use the same stage, idle activation and rollback. */
    internal suspend fun prepareVeyraUpdate(offer: VerifiedVeyraCompatibleOffer): PreparedUpdate? =
        prepareUpdateInternal(offer.update, offer)

    private suspend fun prepareUpdateInternal(update: WindowsUpdate, compatibleOffer: VerifiedVeyraCompatibleOffer?): PreparedUpdate? = withContext(Dispatchers.IO) {
        if (closed || disabledReason != null || !mutex.tryLock()) return@withContext null
        var lease: UpdateStorage.StageLease? = null
        var complete = false
        var durableReady = false
        try {
            ensureOpenAndActive()
            require(!childRetirementPending) { "之前更新子进程或下载回退的退役状态无法确认" }
            retainedPrepared?.let {
                require(retainedReceipt?.phase == DesktopPreparedUpdateStorage.Phase.READY) { "更新准备记录已失效" }
                require(compatibleOffer == null || compatibleOffer.update == update &&
                    retainedArchiveSha256.equals(compatibleOffer.zipSha256, ignoreCase = true)) {
                    "Prepared archive differs from the signed compatibility catalog"
                }
                return@withContext it.takeIf { prepared -> prepared.update == update }
            }
            validateUpdate(update)
            requireStillPublished(update)
            require(registryAllowsPreparedUpgrade(update)) { "已有相同或更高的已注册 Windows 版本，此更新无法提交" }
            require((state.value as? UpdateState.Available)?.update == update) { "Windows 更新目标已改变，请重新检查" }
            // Generic callers also resolve before any stage or Downloading state. Only absent means ordinary.
            val offer = compatibleOffer ?: resolveVeyraCatalog(update)
            require(offer == null || offer.update == update) { "Windows 兼容目录目标已改变" }
            val compatibleSha256 = offer?.zipSha256
            ensureOpenAndActive()
            resumeReadyPrepared(update, compatibleSha256)?.let { return@withContext it }
            val settings = requireNotNull(config)
            val acquired = UpdateStorage.createLeasedStage(updateRoot, requireNotNull(repository), update.assetId)
            lease = acquired
            val staging = acquired.stage
            val archive = staging.resolve("download.zip")
            mutableState.value = UpdateState.Downloading(0, update.size)
            val expectedHash = if (signedSource) signedRelease(update).sha256 else
                parseChecksum(fetchText(update.checksumUrl, 128 * 1024), update.assetName)
            compatibleSha256?.let { verifyChecksum(expectedHash, it) }
            val digest = download(update, archive)
            ensureOpenAndActive()
            mutableState.value = UpdateState.Verifying
            verifyChecksum(digest, expectedHash)
            val context = currentCoroutineContext()
            val executable = SafeUpdateZip.extract(archive, Files.createDirectory(staging.resolve("app")), settings.executable) {
                context.ensureActive(); ensureOpen()
            }
            val receipt = DesktopPreparedUpdateStorage.writeReady(updateRoot, acquired, requireNotNull(repository),
                settings.channel, update, digest, executable, settings.executable) {
                context.ensureActive(); ensureOpen()
            }
            // writeReady returning means durable completion even if the owner
            // cancels/closes before publishing this memory projection.
            durableReady = true
            val prepared = PreparedUpdate(update, staging, executable)
            ensureOpenAndActive()
            synchronized(lifetime) {
                context.ensureActive(); ensureOpen()
                retainedPrepared = prepared
                retainedArchiveSha256 = digest.lowercase()
                retainedReceipt = receipt
                retainedLease = acquired
                complete = true
                mutableState.value = UpdateState.Prepared(prepared)
            }
            pruneInstallations()
            prepared
        } catch (cancelled: CancellationException) {
            if (!closed) mutableState.value = UpdateState.Available(update)
            throw cancelled
        } catch (error: Exception) {
            ensureOpenAndActive()
            mutableState.value = UpdateState.Failed(error.message ?: "Windows 更新准备失败，当前版本继续运行")
            null
        } finally {
            try {
                if (!complete) lease?.let {
                    if (durableReady) it.close()
                    else UpdateStorage.deleteLeasedStage(updateRoot, it, requireNotNull(repository))
                }
            } finally { finishOperation() }
        }
    }

    /** The UI calls this only after its atomic playback/task idle check. True permits the old app to exit. */
    suspend fun activatePreparedUpdate(
        prepared: PreparedUpdate,
        beforeLaunch: suspend (String) -> Unit = {},
        onCommitted: () -> Unit = {},
        afterFailedLaunch: suspend () -> Unit = {},
        automatic: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (closed || disabledReason != null || !mutex.tryLock()) return@withContext false
        var attempt: LaunchAttempt? = null
        var committed = false
        val ownsPrepared = prepared === retainedPrepared
        var discard = false
        var cancelledOperation = false
        var processStartEntered = false
        var healthFailed = false
        var claimed: DesktopPreparedUpdateStorage.Receipt? = null
        try {
            ensureOpenAndActive()
            require(!childRetirementPending) { "之前更新子进程或下载回退的退役状态无法确认" }
            require(ownsPrepared) { "更新准备状态已失效，请重新检查更新" }
            val lease = requireNotNull(retainedLease) { "更新安装目录租约已失效" }
            require(lease.holds(prepared.stagingDirectory)) { "更新安装目录租约已失效" }
            val ready = requireNotNull(retainedReceipt) { "更新准备记录已失效" }
            require(ready.phase == DesktopPreparedUpdateStorage.Phase.READY &&
                ready.update == prepared.update && readyReceiptMatchesPolicy(ready, prepared.update, retainedArchiveSha256)) {
                "更新准备记录与当前目标不一致"
            }
            validateUpdate(prepared.update)
            requireStillPublished(prepared.update)
            if (signedSource) {
                refreshRemotePolicy()
                require(!automatic || mutableRemotePolicy.value.automaticUpdatesEnabled) { "自动更新已暂停，当前版本继续运行" }
            }
            require(UpdateStorage.ownedStage(updateRoot, prepared.stagingDirectory, requireNotNull(repository)) != null &&
                validStoredExecutable(prepared.executable.toString()) == prepared.executable) { "更新安装目录校验失败" }
            val observed = readActiveInstallationSnapshot()
            require(registryAllowsPreparedUpgrade(prepared.update, observed)) { "已有相同或更高的已注册 Windows 版本，此更新无法提交" }
            // Real Root freeze first. Cancellation/no-start recovery does not
            // infer child or queue retirement from a stale UI idle observation.
            val launchToken = UUID.randomUUID().toString()
            beforeLaunch(launchToken)
            ensureOpenAndActive()
            claimed = DesktopPreparedUpdateStorage.claim(updateRoot, lease, requireNotNull(repository), ready, launchToken)
            retainedReceipt = claimed
            mutableState.value = UpdateState.Launching
            attempt = launchInstallation(prepared.executable, emptyList(), launchToken) {
                // Exactly before the real process factory/start call. A throw
                // after entry without a returned Process is unknown custody.
                processStartEntered = true
                childRetirementPending = true
            }
            val healthy = StartupHealth.await(attempt.process, attempt.healthFile, attempt.token, expectedVersion = prepared.update.version, observation = attempt.descendants)
            healthFailed = !healthy
            require(healthy) { "新版本未通过版本、窗口和播放组件启动检查，当前版本继续运行" }
            ensureOpenAndActive()
            val previous = previousInstallation(observed.installation)
            ensureOpenAndActive()
            beginInstallationCommit()
            require(commitActiveInstallation(observed,
                ActiveInstallation(requireNotNull(repository), prepared.update.version, prepared.executable.toString(),
                    previous?.first, previous?.second?.toString(), launchToken), upgrade = true)) {
                "更新注册状态已改变、已有相同或更高版本，或注册事务暂不可用；当前版本继续运行"
            }
            committed = true
            childRetirementPending = false
            discard = true
            forgetFailedActivation() // A healthy activation proves this machine can run the new build.
            // Registry commit is authoritative. Receipt cleanup cannot turn a
            // healthy committed installation back into a failed update.
            runCatching { DesktopPreparedUpdateStorage.retire(updateRoot, lease, requireNotNull(repository), requireNotNull(claimed)) }
            onCommitted() // Contract: synchronous, nonthrowing memory retirement only.
            mutableState.value = UpdateState.Launched
            pruneInstallations()
            true
        } catch (cancelled: CancellationException) {
            cancelledOperation = true
            discard = false
            throw cancelled
        } catch (error: Exception) {
            try { ensureOpenAndActive() }
            catch (cancelled: CancellationException) {
                cancelledOperation = true
                discard = false
                throw cancelled
            }
            // Only an unhealthy or unstartable child is remembered; registry races and IO errors stay retryable.
            if (healthFailed || (processStartEntered && attempt == null)) rememberFailedActivation(prepared.update)
            discard = ownsPrepared
            mutableState.value = UpdateState.Failed(error.message ?: "Windows 更新启动失败，当前版本继续运行")
            false
        } finally {
            try {
                if (!committed) withContext(kotlinx.coroutines.NonCancellable) {
                    if (attempt != null) {
                        StartupHealth.terminate(checkNotNull(attempt).process, checkNotNull(attempt).descendants)
                    } else if (processStartEntered) {
                        childRetirementPending = true
                        error("更新进程启动已进入但未返回句柄；退役状态无法确认，不能恢复下载队列")
                    } else if (childRetirementPending && claimed == null) {
                        error("之前更新子进程退役状态无法确认，不能恢复下载队列")
                    }
                    // No-start or actual successful termination is known, but
                    // unknown queue rollback still preserves receipt and lease.
                    childRetirementPending = true
                    afterFailedLaunch()
                    if (ownsPrepared) {
                        val lease = requireNotNull(retainedLease)
                        val receipt = requireNotNull(retainedReceipt)
                        if (cancelledOperation) {
                            val restored = if (claimed != null)
                                DesktopPreparedUpdateStorage.restoreReady(updateRoot, lease, requireNotNull(repository), checkNotNull(claimed))
                            else {
                                require(receipt.phase == DesktopPreparedUpdateStorage.Phase.READY &&
                                    DesktopPreparedUpdateStorage.read(updateRoot, lease, requireNotNull(repository))?.let(receipt::matches) == true) {
                                    "更新准备记录在取消期间已改变"
                                }
                                receipt
                            }
                            retainedReceipt = restored
                            // Closed owners may safely restore disk READY after
                            // confirmed rollback, but publish no new memory state.
                            if (!closed) mutableState.value = UpdateState.Prepared(prepared)
                        } else if (discard) {
                            DesktopPreparedUpdateStorage.retire(updateRoot, lease, requireNotNull(repository), receipt)
                        }
                    }
                    childRetirementPending = false
                }
            } finally {
                try { if (discard && !childRetirementPending) discardRetainedPrepared() }
                finally { finishOperation() }
            }
        }
    }

    private fun validateUpdate(update: WindowsUpdate) {
        require(DesktopVersion.parse(update.version)?.let { it > requireNotNull(installedVersion) } == true) { "更新版本必须高于当前桌面版本" }
        require(update.releaseId > 0 && update.assetId > 0 && isWindowsZip(update.assetName) && update.size in 1..MAX_DOWNLOAD_BYTES) { "Windows 更新包名称、标识或大小异常" }
        if (signedSource) signedRelease(update)
        else require(trustedAssetUrl(update.downloadUrl, requireNotNull(repository)) && trustedAssetUrl(update.checksumUrl, repository)) { "更新地址与构建配置的发布仓库不一致" }
    }

    private suspend fun fetchReleases(repository: String): List<GitHubRelease> {
        val releases = mutableListOf<GitHubRelease>()
        val seenReleaseIds = mutableSetOf<Long>()
        for (page in 1..MAX_RELEASE_PAGES) {
            ensureOpenAndActive()
            val response = fetchText("https://api.github.com/repos/$repository/releases?per_page=$RELEASE_PAGE_SIZE&page=$page", 8 * 1024 * 1024)
            ensureOpenAndActive()
            val batch = json.decodeFromString<List<GitHubRelease>>(response)
            ensureOpenAndActive()
            require(batch.size <= RELEASE_PAGE_SIZE) { "Windows 发布列表第${page}页大小异常，无法确认更新" }
            batch.forEach { release ->
                require(release.id > 0L && seenReleaseIds.add(release.id)) {
                    "Windows 发布列表出现重复或无效版本标识，无法确认完整更新列表"
                }
            }
            releases += batch
            if (batch.size < RELEASE_PAGE_SIZE) return releases
        }
        error("Windows 发布列表超过${MAX_RELEASE_PAGES}页，无法确认完整更新列表")
    }

    /** A cancellation watcher closes the socket even while the IO thread is reading a large body. */
    private suspend fun <T> executeRequest(request: Request, consume: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            ensureOpenAndActive()
            call.execute().use(consume)
        } catch (error: Exception) {
            ensureOpenAndActive()
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
        val builder = Request.Builder().url(update.downloadUrl).header("User-Agent", "BiliPai-Windows-Updater")
        val scope = if (signedSource) VeyraCatalogHttpScope(update.downloadUrl, asset = true) else null
        if (scope != null) { builder.tag(VeyraCatalogHttpScope::class.java, scope); scope.requireAllowed(builder.build()) }
        return executeRequest(builder.build()) { response ->
            scope?.requireAllowed(response.request)
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
                        context.ensureActive(); ensureOpen()
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

    private suspend fun launchInstallation(executable: Path, args: List<String>, healthToken: String? = null,
        beforeStart: () -> Unit = {}): LaunchAttempt {
        ensureOpenAndActive()
        check(!childRetirementPending) { "之前更新子进程的退役状态无法确认" }
        val stage = stageForExecutable(executable) ?: error("更新启动路径无效")
        val launchDirectory = Files.createDirectory(stage.resolve("launch-${UUID.randomUUID()}"))
        val token = healthToken ?: UUID.randomUUID().toString()
        require(UUID.fromString(token).toString() == token)
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
        ensureOpenAndActive()
        beforeStart()
        val process = processFactoryForTest?.invoke(builder) ?: builder.start()
        childRetirementPending = true
        val observation = StartupHealth.DescendantObservation(process)
        try { onProcessStarted(process) }
        catch (error: Throwable) {
            try { StartupHealth.terminate(process, observation); childRetirementPending = false }
            catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
        return LaunchAttempt(process, healthFile, token, observation)
    }

    private fun lastCheckTime(): Long = runCatching { Files.readString(updateRoot.resolve("last-check.txt")).trim().toLong() }.getOrDefault(0)

    private class ActiveInstallationSnapshot(val installation: ActiveInstallation?, val bytes: ByteArray?) {
        fun matches(other: ActiveInstallationSnapshot): Boolean =
            if (bytes == null) other.bytes == null else other.bytes?.contentEquals(bytes) == true
    }

    /** Absence is a snapshot; invalid/unreadable data is unknown, never an absent registry. */
    private fun readActiveInstallationSnapshot(): ActiveInstallationSnapshot =
        readActiveInstallationSnapshot(updateRoot, requireNotNull(repository), json)

    internal fun downloadHandoffTarget(prepared: PreparedUpdate): com.bilipai.desktop.download.DesktopDownloadQueueAuthority.CommitTarget {
        kotlin.check(prepared === retainedPrepared)
        return com.bilipai.desktop.download.DesktopDownloadQueueAuthority.CommitTarget(requireNotNull(repository),
            prepared.update.version, prepared.executable.toString(), updateRoot.resolve("active-install.json").toString())
    }

    private fun readActiveInstallation(): ActiveInstallation? = runCatching {
        readActiveInstallationSnapshot().installation
    }.getOrNull()

    /** Only healthy selected children reach this short commit. No startup await or prune holds its lock.
     * Exact old bytes protect both forward upgrades and the specifically selected previous fallback. */
    private fun commitActiveInstallation(
        observed: ActiveInstallationSnapshot, installation: ActiveInstallation, upgrade: Boolean,
        registeredExecutable: Path? = null,
    ): Boolean {
        val lease = UpdateStorage.tryLeaseActiveRegistry(updateRoot) ?: return false
        return lease.use {
            require(it.holds(UpdateStorage.verifiedRoot(updateRoot))) { "更新注册事务租约已失效" }
            val fresh = readActiveInstallationSnapshot()
            if (!observed.matches(fresh)) return@use false
            require(installation.repository == repository)
            if (upgrade) {
                val target = requireNotNull(DesktopVersion.parse(installation.version))
                val current = fresh.installation?.let { active -> requireNotNull(DesktopVersion.parse(active.version)) }
                if (current != null && target <= current) return@use false
            } else {
                val active = requireNotNull(fresh.installation)
                val sameActive = installation.version == active.version && installation.executablePath == active.executablePath
                val selectedPrevious = installation.version == active.previousVersion && installation.executablePath == active.previousExecutablePath
                require(sameActive || selectedPrevious) { "更新回退目标不属于捕获的安装记录" }
                // Match original record fields first, then the exact canonical child that passed health.
                require(validStoredExecutable(installation.executablePath) == requireNotNull(registeredExecutable)) {
                    "更新启动目标与捕获的安装记录不一致"
                }
                if (sameActive) return@use true // Preserve that exact record and its previous/unknown fields.
            }
            writeActiveInstallation(installation)
            true
        }
    }

    private fun writeActiveInstallation(installation: ActiveInstallation) {
        UpdateStorage.verifiedRoot(updateRoot)
        val temporary = Files.createTempFile(updateRoot, "active-install-", ".tmp")
        try {
            // Same durability rule as the prepared receipt: the contents reach the disk before the rename publishes them.
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(json.encodeToString(ActiveInstallation.serializer(), installation).toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, updateRoot.resolve("active-install.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, updateRoot.resolve("active-install.json"), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    /** A new build that never became healthy fails identically on the next identical download, and the failed
     * activation retires its receipt and stage. Remember the exact asset so the six-hour automatic check does not
     * download, extract and launch it again. This is a retry back-off only, never a trust or health decision. */
    private fun rememberFailedActivation(update: WindowsUpdate) {
        runCatching {
            UpdateStorage.verifiedRoot(updateRoot)
            val temporary = Files.createTempFile(updateRoot, "failed-activation-", ".tmp")
            try {
                Files.writeString(temporary, json.encodeToString(FailedActivation.serializer(),
                    FailedActivation(requireNotNull(repository), update.version, update.releaseId, update.assetId, System.currentTimeMillis())))
                Files.move(temporary, updateRoot.resolve(FAILED_ACTIVATION_FILE),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
    }

    private fun forgetFailedActivation() {
        runCatching { Files.deleteIfExists(updateRoot.resolve(FAILED_ACTIVATION_FILE)) }
    }

    /** Unreadable, foreign or expired memory never blocks an update. Only an automatic check consults it. */
    private fun automaticRetryHold(update: WindowsUpdate, nowMs: Long = System.currentTimeMillis()): UpdateState.Failed? = runCatching {
        val file = updateRoot.resolve(FAILED_ACTIVATION_FILE)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 4096) return@runCatching null
        val record = json.decodeFromString<FailedActivation>(Files.readString(file))
        if (record.repository != repository || record.releaseId != update.releaseId || record.assetId != update.assetId)
            return@runCatching null
        if (nowMs - record.failedAtMs !in 0..AUTOMATIC_RETRY_HOLD_MS) return@runCatching null
        UpdateState.Failed("Windows 版本 ${update.version} 上次启动检查未通过，已暂停自动重试；可手动检查更新后重试")
    }.getOrNull()

    private fun stageForExecutable(executable: Path): Path? = config?.executable?.let {
        UpdateStorage.executableStage(updateRoot, executable, it)
    }

    private fun validStoredExecutable(raw: String): Path? = runCatching {
        val executable = UpdateStorage.existingPathWithoutLinks(Path.of(raw))
        executable.takeIf { stageForExecutable(it) != null }
    }.getOrNull()

    private fun previousInstallation(active: ActiveInstallation?): Pair<String, Path>? {
        val currentCommand = ProcessHandle.current().info().command().orElse(null)?.let(::validStoredExecutable)
        if (currentCommand != null) return requireNotNull(config).version to currentCommand
        active ?: return null
        return validStoredExecutable(active.executablePath)?.let { active.version to it }
    }

    private fun preservedInstallationStages(): Set<Path> {
        val trusted = requireNotNull(repository)
        val activePath = updateRoot.resolve("active-install.json")
        val recordPresent = try {
            Files.readAttributes(activePath, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            true
        } catch (_: java.nio.file.NoSuchFileException) { false }
        val active = readActiveInstallation()
        require(!recordPresent || active != null) { "当前安装记录无法确认，跳过更新目录清理" }
        val preserved = mutableSetOf<Path>()
        listOfNotNull(active?.executablePath, active?.previousExecutablePath).forEach { raw ->
            validStoredExecutable(raw)?.let { stageForExecutable(it) }?.let(preserved::add)
        }
        retainedPrepared?.stagingDirectory?.let(preserved::add)
        val knownRunning = knownRunningStagesForTest
        if (knownRunning != null) {
            val observed = knownRunning()
            require(observed.size <= MAX_PREPARED_STAGE_ENTRIES) { "测试运行安装目录列表超限" }
            observed.forEach { stage -> UpdateStorage.ownedStage(updateRoot, stage, trusted)?.let(preserved::add) }
        } else {
            // Production retains the existing observable running-command policy.
            ProcessHandle.allProcesses().use { processes ->
                processes.forEach { handle -> handle.info().command().orElse(null)?.let { raw ->
                    runCatching { UpdateStorage.stageContaining(updateRoot, Path.of(raw), trusted) }.getOrNull()?.let(preserved::add)
                } }
            }
        }
        return preserved
    }

    private fun pruneInstallations() {
        // Cleanup is best effort and never changes an otherwise successful preparation or activation.
        runCatching {
            val trusted = requireNotNull(repository)
            val preserved = preservedInstallationStages()
            UpdateStorage.prune(updateRoot, trusted, preserved) { stage ->
                // The candidate OS lease is held here. A successful activation writes its registry
                // before releasing that same lease, so this fresh admission sees the completed commit.
                stage in preservedInstallationStages()
            }
        }
    }

    internal suspend fun launchRegisteredInstall(args: Array<String>): Boolean = withContext(Dispatchers.IO) {
        if (closed || disabledReason != null || args.any { it in setOf("--update-health-file", "--update-health-token", "--backend-smoke", "--player-self-test") } || !mutex.tryLock()) return@withContext false
        try {
            ensureOpenAndActive()
            val observed = runCatching { readActiveInstallationSnapshot() }.getOrNull() ?: return@withContext false
            val active = observed.installation ?: return@withContext false
            val current = requireNotNull(installedVersion)
            if (DesktopVersion.parse(active.version)?.let { it > current } != true) return@withContext false
            val candidates = listOfNotNull(
                validStoredExecutable(active.executablePath)?.let { Triple(active.version, active.executablePath, it) },
                active.previousVersion?.let { version ->
                    if (DesktopVersion.parse(version)?.let { it > current } != true) null
                    else active.previousExecutablePath?.let { recordedPath ->
                        validStoredExecutable(recordedPath)?.let { Triple(version, recordedPath, it) }
                    }
                },
            )
            for ((version, recordedPath, executable) in candidates) {
                val currentCommand = ProcessHandle.current().info().command().orElse(null)
                if (currentCommand?.let { runCatching { UpdateStorage.existingPathWithoutLinks(Path.of(it)) == executable }.getOrDefault(false) } == true) continue
                var lease: UpdateStorage.StageLease? = null
                var attempt: LaunchAttempt? = null
                var committed = false
                var retirementUnconfirmed = false
                try {
                    ensureOpenAndActive()
                    val stage = stageForExecutable(executable) ?: continue
                    lease = UpdateStorage.tryLeaseStage(updateRoot, stage, requireNotNull(repository)) ?: continue
                    require(validStoredExecutable(executable.toString()) == executable) { "更新启动目录已改变" }
                    attempt = launchInstallation(executable, args.toList())
                    if (StartupHealth.await(attempt.process, attempt.healthFile, attempt.token, expectedVersion = version, observation = attempt.descendants)) {
                        ensureOpenAndActive()
                        beginInstallationCommit()
                        val selected = if (version == active.version && recordedPath == active.executablePath) active
                            else ActiveInstallation(requireNotNull(repository), version, recordedPath)
                        if (!commitActiveInstallation(observed, selected, upgrade = false, registeredExecutable = executable))
                            return@withContext false
                        committed = true
                        childRetirementPending = false
                        pruneInstallations()
                        return@withContext true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (childRetirementPending && attempt == null) throw error
                    ensureOpenAndActive()
                    // A damaged active update falls back to its preceding healthy version or original launcher.
                } finally {
                    try {
                        val launched = attempt
                        if (!committed && launched != null) {
                            // Startup owns no download queue. An unconfirmed child keeps its stage lease, but its failed
                            // retirement must not escape as an uncaught exception from Main: fall back to this build.
                            try { StartupHealth.terminate(launched.process, launched.descendants); childRetirementPending = false }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: IllegalStateException) { retirementUnconfirmed = true }
                        }
                    } finally {
                        if (childRetirementPending) { retainedLease = lease; lease = null }
                        else lease?.close()
                    }
                }
                // That child may still own the stage; never stack another candidate on top of it.
                if (retirementUnconfirmed) return@withContext false
            }
            false
        } finally { finishOperation() }
    }

    companion object {
        internal fun downloadHandoffCommitMatches(target: com.bilipai.desktop.download.DesktopDownloadQueueAuthority.CommitTarget,
            token: String): Boolean = runCatching {
            require(UUID.fromString(token).toString() == token && validRepository(target.repository))
            val registry = Path.of(target.registryFile).toAbsolutePath().normalize()
            require(registry.fileName.toString() == "active-install.json")
            val snapshot = readActiveInstallationSnapshot(requireNotNull(registry.parent), target.repository,
                Json { ignoreUnknownKeys = true })
            val active = snapshot.installation ?: return@runCatching false
            active.downloadHandoffToken == token && active.repository == target.repository &&
                active.version == target.version && active.executablePath == target.executablePath
        }.getOrDefault(false)

        private fun readActiveInstallationSnapshot(updateRoot: Path, repository: String, json: Json): ActiveInstallationSnapshot {
            val file = updateRoot.resolve("active-install.json")
            val attributes = try {
                Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: java.nio.file.NoSuchFileException) {
                return ActiveInstallationSnapshot(null, null)
            }
            require(attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther && attributes.size() <= 64 * 1024) {
                "当前安装记录不是可确认的普通文件"
            }
            val canonical = UpdateStorage.existingPathWithoutLinks(file)
            val bytes = Files.newInputStream(canonical, NOFOLLOW_LINKS).use { it.readNBytes(64 * 1024 + 1) }
            require(bytes.size <= 64 * 1024) { "当前安装记录超过大小限制" }
            // Preserve Files.readString's strict UTF-8 behavior while retaining exactly the bytes decoded.
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val installation = json.decodeFromString<ActiveInstallation>(text)
            require(installation.repository == repository) { "当前安装记录不属于此 Windows 发布仓库" }
            return ActiveInstallationSnapshot(installation, bytes)
        }


        const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
        const val STARTUP_TIMEOUT_MS = 30_000L
        internal const val STARTUP_STABILITY_MS = 1_500L
        internal const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        /** An exact release asset whose new build never became healthy is not offered to AUTOMATIC checks for this long. */
        internal const val AUTOMATIC_RETRY_HOLD_MS = 7L * 24 * 60 * 60 * 1000L
        private const val FAILED_ACTIVATION_FILE = "failed-activation.json"
        private const val MAX_PREPARED_STAGE_ENTRIES = 256
        private const val RELEASE_PAGE_SIZE = 100
        internal const val MAX_RELEASE_PAGES = 100
        private fun loadBuildConfig(): UpdateConfig? = runCatching {
            DesktopUpdater::class.java.getResourceAsStream("/windows-update.json")?.use {
                Json { ignoreUnknownKeys = true }.decodeFromString<UpdateConfig>(it.readBytes().toString(Charsets.UTF_8)).also { settings -> require(settings.source == "signed") }
            }
        }.getOrNull()
        internal fun packagedVersion(): String = requireNotNull(loadBuildConfig()?.version?.takeIf { DesktopVersion.parse(it) != null }) {
            "Windows build version is unavailable"
        }
        private fun defaultUpdateRoot(): Path = Path.of(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".local", "share").toString(), "BiliPai", "updates").toAbsolutePath().normalize()
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).followSslRedirects(false)
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
            processFactory: ((ProcessBuilder) -> Process)? = null,
            channel: String = "prerelease",
            knownRunningStages: () -> Set<Path> = { emptySet() },
            distributionBase: String? = null,
            signedPublicKey: String? = null,
            manifestUrl: String? = null,
            configUrl: String? = null,
        ): DesktopUpdater {
            require(DesktopVersion.parse(currentVersion) != null && validRepository(repository))
            val childData = childLocalAppData.toAbsolutePath().normalize()
            require(updateRoot.toAbsolutePath().normalize() == childData.resolve("BiliPai/updates")) {
                "Integration update root must belong to the isolated child LOCALAPPDATA"
            }
            require(channel in setOf("stable", "prerelease", "dev"))
            return DesktopUpdater(UpdateConfig(currentVersion, windowsReleaseRepository = repository, channel = channel, executable = executable,
                source = if (distributionBase == null) "github-legacy" else "signed",
                distributionBase = distributionBase ?: DistributionTrust.DEFAULT_BASE, manifestUrl = manifestUrl, configUrl = configUrl),
                updateRoot, client, childData, onProcessStarted, compatibleCatalog, processFactory, knownRunningStages, signedPublicKey)
        }
        /** Call before creating the UI; true means a previously verified newer window is running. */
        fun launchInstalledUpdateIfNewer(args: Array<String>): Boolean = runBlocking {
            withContext(Dispatchers.IO) { DesktopUpdater().use { it.launchRegisteredInstall(args) } }
        }
        internal fun shouldCheck(previous: Long, now: Long, intervalMs: Long = AUTO_CHECK_INTERVAL_MS): Boolean = previous <= 0 || now < previous || now - previous >= intervalMs
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

    private data class LaunchAttempt(val process: Process, val healthFile: Path, val token: String,
        val descendants: StartupHealth.DescendantObservation)
    @Serializable private data class UpdateConfig(
        val version: String,
        val upstreamTag: String = "",
        val upstreamCommit: String = "",
        val windowsReleaseRepository: String? = null,
        val channel: String = "prerelease",
        val executable: String = "BiliPai Windows.exe",
        val source: String = "github-legacy",
        val distributionBase: String = DistributionTrust.DEFAULT_BASE,
        val manifestUrl: String? = null,
        val configUrl: String? = null,
    )
    @Serializable private data class ActiveInstallation(
        val repository: String,
        val version: String,
        val executablePath: String,
        val previousVersion: String? = null,
        val previousExecutablePath: String? = null,
        val downloadHandoffToken: String? = null,
    )
    @Serializable private data class FailedActivation(
        val repository: String,
        val version: String,
        val releaseId: Long,
        val assetId: Long,
        val failedAtMs: Long,
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
