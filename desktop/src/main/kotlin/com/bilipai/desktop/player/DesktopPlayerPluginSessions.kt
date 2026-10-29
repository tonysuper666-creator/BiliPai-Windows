package com.bilipai.desktop.player

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.plugin.sdk.*
import com.android.purebilibili.data.model.response.SponsorSegment
import kotlinx.coroutines.*
import java.util.IdentityHashMap

/** Definition metadata stays application-wide; commands always target this exact host entry. */
internal open class DesktopPlayerPluginSessionHandle internal constructor(
    val definition: BuiltinPlugin,
    internal val entry: PlayerPluginSessionEntry,
) : BuiltinPlugin by definition, PlayerPluginSession by entry.session

internal class DesktopSponsorPlayerSession internal constructor(definition: BuiltinPlugin, entry: PlayerPluginSessionEntry) :
    DesktopPlayerPluginSessionHandle(definition, entry), SponsorBlockSessionApi {
    private val sponsor get() = (entry.session as DesktopHostedPlayerSession).raw as SponsorBlockSessionApi
    override fun markAsSkipped(segmentId: String) = sponsor.markAsSkipped(segmentId)
    override fun getProgressMarkers() = sponsor.getProgressMarkers()
    override fun getSegments() = sponsor.getSegments()
    override fun getActiveSegment() = sponsor.getActiveSegment()
    override fun isCommunityContributionEnabled() = sponsor.isCommunityContributionEnabled()
    override fun getCommunityServerBaseUrl() = sponsor.getCommunityServerBaseUrl()
    override suspend fun uploadViewedSegmentIfEnabled(segmentId: String) = sponsor.uploadViewedSegmentIfEnabled(segmentId)
    override suspend fun voteOnCommunitySegment(segmentId: String, voteType: Int) = sponsor.voteOnCommunitySegment(segmentId, voteType)
    override suspend fun submitCommunitySegment(bvid: String, cid: Long, videoDurationSeconds: Float,
        startMs: Long, endMs: Long, category: String, actionType: String): Result<List<SponsorSegment>> =
        sponsor.submitCommunitySegment(bvid, cid, videoDurationSeconds, startMs, endMs, category, actionType)
}

internal class DesktopHostedPlayerSession(
    val raw: PlayerPluginSession,
    private val load: suspend (PlayerPluginSession, String, Long) -> Unit,
) : PlayerPluginSession by raw {
    var completion = CompletableDeferred<Unit>()
    override suspend fun onVideoLoad(bvid: String, cid: Long) {
        try { load(raw, bvid, cid) }
        catch (cancelled: CancellationException) { completion.cancel(cancelled); throw cancelled }
    }
}

/** One video/player's upstream host. Runtime supplies its existing serialized load admission.
 * Must be used on the same Main lane as PlayerPluginSessionHost. No plugin definition is a session.
 */
internal class DesktopPlayerPluginSessions(
    scope: CoroutineScope,
    private val bvid: String,
    private val cid: Long,
    private val load: suspend (BuiltinPlugin, PlayerPluginSession, String, Long) -> Unit,
    private val failed: (String, Exception) -> Unit,
) : AutoCloseable {
    private val factories = IdentityHashMap<BuiltinPlugin, PlayerPluginApi>()
    private val handles = IdentityHashMap<PlayerPluginSessionEntry, DesktopPlayerPluginSessionHandle>()
    private var definitions = emptyList<BuiltinPlugin>()
    private var lastSponsor: DesktopSponsorPlayerSession? = null
    private val host = PlayerPluginSessionHost(scope,
        onLoaded = { (it.session as DesktopHostedPlayerSession).completion.complete(Unit) },
        onLoadFailed = { entry, error ->
            failed(entry.definition.id, error)
            (entry.session as DesktopHostedPlayerSession).completion.complete(Unit)
        },
        onEnded = { (it.session as DesktopHostedPlayerSession).completion.cancel() })

    private fun handle(entry: PlayerPluginSessionEntry): DesktopPlayerPluginSessionHandle = handles.getOrPut(entry) {
        val definition = definitions.firstOrNull { factories[it] === entry.definition }
            ?: error("Session definition retired")
        if ((entry.session as DesktopHostedPlayerSession).raw is SponsorBlockSessionApi)
            DesktopSponsorPlayerSession(definition, entry).also { lastSponsor = it }
        else DesktopPlayerPluginSessionHandle(definition, entry)
    }

    fun update(enabled: List<BuiltinPlugin>) {
        definitions = enabled.filterIsInstance<PlayerPluginApi>().map { it as BuiltinPlugin }
        val selected = definitions.map { definition -> factories.getOrPut(definition) {
            val api = definition as PlayerPluginApi
            object : PlayerPluginApi by api {
                override fun createSession(): PlayerPluginSession = DesktopHostedPlayerSession(api.createSession()) { raw, bvid, cid ->
                    load(definition, raw, bvid, cid)
                }
            }
        } }
        host.update(selected, PluginVideoKey(bvid, cid))
        current() // Preserve exact handle identity before a later disable/end.
    }
    fun ownsRaw(session: PlayerPluginSession): Boolean = host.currentSessions().any {
        (it.session as DesktopHostedPlayerSession).raw === session && host.isCurrent(it)
    }
    fun current(): List<DesktopPlayerPluginSessionHandle> = host.currentSessions().map(::handle)
    fun ready(): List<DesktopPlayerPluginSessionHandle> = host.readySessions(PluginVideoKey(bvid, cid)).map(::handle)
    fun isCurrent(handle: DesktopPlayerPluginSessionHandle): Boolean = host.isCurrent(handle.entry)
    fun sponsor(): DesktopSponsorPlayerSession? = current().filterIsInstance<DesktopSponsorPlayerSession>().firstOrNull() ?: lastSponsor
    suspend fun awaitLoads() {
        val captured = host.currentSessions()
        for (entry in captured) (entry.session as DesktopHostedPlayerSession).completion.await()
        currentCoroutineContext().ensureActive()
    }
    suspend fun ensureLoaded(handle: DesktopPlayerPluginSessionHandle) {
        if (!host.isCurrent(handle.entry)) throw CancellationException("Player plugin session retired")
        if (handle !in ready()) {
            val session = handle.entry.session as DesktopHostedPlayerSession
            if (session.completion.isCompleted) session.completion = CompletableDeferred()
            host.retryLoad(handle.id)
            session.completion.await()
        }
        if (!host.isCurrent(handle.entry)) throw CancellationException("Player plugin session retired")
        check(handle in ready()) { "Player plugin session failed to load: ${handle.id}" }
    }
    suspend fun reload(id: String) {
        val retained = definitions.toList()
        update(retained.filterNot { it.id == id })
        update(retained)
        awaitLoads()
    }
    override fun close() { host.close(); handles.clear(); factories.clear(); definitions = emptyList(); lastSponsor = null }
}
