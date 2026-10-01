from pathlib import Path
import hashlib,json,sys,re
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
BACKEND=MAIN/'desktop/.local/stable-playback-account-protocol-parity'
BASE='desktop/src/main/kotlin/com/bilipai/desktop/'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
changes=[]; bodies={}
def start(rel,backend=False):
 if rel in bodies:return bodies[rel]
 s=read(MAIN/'desktop/.local/upstream-v023-audit/palette-home-retainer-install43/baseline'/rel) if rel==BASE+'DesktopShell.kt' else read(REPO/rel)
 if backend:
  rows=json.loads(read(BACKEND/'local-hunks.json'))
  if isinstance(rows,dict):rows=rows['changes']
  row=next(r for r in rows if r['path']==rel)
  for h in row['hunks']:
   assert s.count(h['before'])==h['occurrences'],(rel,h['before'][:80],s.count(h['before']))
   s=s.replace(h['before'],h['after'])
 return s
def modify(rel,steps,backend=False):
 s=start(rel,backend); original=s; rows=[]
 for old,new,count in steps:
  assert s.count(old)==count,(rel,old[:110],s.count(old),count)
  rows.append(dict(before=old,after=new,occurrences=count,proofOffsetsBefore=[m.start() for m in re.finditer(re.escape(old),s)]));s=s.replace(old,new)
 write(HERE/'proof-only'/rel,s)
 changes.append(dict(path=rel,baseLF=sha(original),candidateLF=sha(s),backend84AppliedFirst=backend,hunks=rows,proofWholeFileNotInstallPayload=True))
 bodies[rel]=s
 return s

# Keep immutable backend84 history. Copy proof inputs here solely for full prospective compilation.
for p in (BACKEND/'prepared').rglob('*.kt'):
 write(HERE/'prepared'/p.relative_to(BACKEND/'prepared'),read(p))
for rel in ['data/DesktopSessionStore.kt','data/DesktopMediaRepository.kt','data/DesktopPlaybackCache.kt','data/DesktopModels.kt']:
 write(HERE/'proof-only'/BASE/rel,start(BASE+rel,True))

repo_members='''    internal fun isPlaybackReceiptCurrent(receipt: DesktopPlaybackAuthorizationReceipt): Boolean =
        sessions.isPlaybackAuthorizationCurrent(receipt)
    internal fun <T> withPlaybackReceiptAdmission(receipt: DesktopPlaybackAuthorizationReceipt,
        stillOwned: () -> Boolean, block: () -> T): T = sessions.withPlaybackAuthorizationAdmission(receipt, stillOwned, block)
    internal fun <T> withPrimaryPlaybackAdmission(epoch: Long, stillOwned: () -> Boolean, block: () -> T): T =
        sessions.withHomeRequestAdmission(epoch, stillOwned, block)
    internal fun primaryPlaybackCalls(epoch: Long, stillOwned: () -> Boolean, delegate: okhttp3.Call.Factory): okhttp3.Call.Factory =
        okhttp3.Call.Factory { request -> sessions.withHomeRequestAdmission(epoch, stillOwned) {
            delegate.newCall(request.newBuilder().tag(DesktopSessionEpoch::class.java, DesktopSessionEpoch(epoch, stillOwned)).build())
        } }
    internal fun playbackReceiptCalls(receipt: DesktopPlaybackAuthorizationReceipt, stillOwned: () -> Boolean, delegate: okhttp3.Call.Factory): okhttp3.Call.Factory {
        val authorization = sessions.withPlaybackAuthorizationAdmission(receipt, stillOwned) {
            sessions.capturePlaybackAuthorization(receipt.accountEpoch, stillOwned).also {
                if (it.receipt != receipt) throw kotlinx.coroutines.CancellationException("Playback authorization retired")
            }
        }
        return okhttp3.Call.Factory { request -> delegate.newCall(request.newBuilder().tag(DesktopSessionEpoch::class.java,
            DesktopSessionEpoch(receipt.accountEpoch, stillOwned, playbackAuthorization = authorization)).build()) }
    }
'''
modify(BASE+'data/DesktopRepository.kt',[(
 '    internal fun <T> withPlaybackSourceAdmission(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {',
 repo_members+'\n    internal fun <T> withPlaybackSourceAdmission(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {',1)],True)

publication='''package com.bilipai.desktop.player

import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.*
import okhttp3.*
import okio.Timeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Final, short publication only. Never perform native joins, close, or network waits in admit. */
internal interface DesktopPlaybackPublication {
    val requiresAccountReceipt: Boolean
    fun isCurrent(source: PlaybackSource): Boolean
    fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T
    fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job? = null): Call.Factory
}

/** Native command admission survives successful request completion, but not cancellation. */
public fun interface DesktopNativePlaybackPublication { public fun admit(command: () -> Unit): Boolean }

internal fun DesktopPlaybackPublication.ownedSource(source: PlaybackSource, stillOwned: () -> Boolean): PlaybackSource =
    source.copy(nativePublication = DesktopNativePlaybackPublication { command ->
        try { admit(source, stillOwned, command); true }
        catch (_: CancellationException) { false }
    })

/** Long-lived native observers reject one retired action without cancelling the observer itself. */
internal fun DesktopPlaybackPublication.tryAdmit(source: PlaybackSource, stillOwned: () -> Boolean, command: () -> Boolean): Boolean =
    try { admit(source, stillOwned, command) } catch (_: CancellationException) { false }

/** Actual transport always uses the one Repository/Store; null receipts are NOT a local bypass. */
internal class DesktopRepositoryPlaybackPublication(private val repository: DesktopRepository,
    private val allowPrimaryAccountSource: Boolean = false) : DesktopPlaybackPublication {
    override val requiresAccountReceipt get() = !allowPrimaryAccountSource
    override fun isCurrent(source: PlaybackSource): Boolean = source.authorizationReceipt?.let(repository::isPlaybackReceiptCurrent)
        ?: (allowPrimaryAccountSource && source.primaryAccountEpoch != null && source.primaryAccountEpoch == repository.sessionEpoch)
    override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
        source.authorizationReceipt?.let { return repository.withPlaybackReceiptAdmission(it, stillOwned, block) }
        val epoch = source.primaryAccountEpoch?.takeIf { allowPrimaryAccountSource }
            ?: throw CancellationException("Missing playback authorization receipt")
        return repository.withPrimaryPlaybackAdmission(epoch, stillOwned, block)
    }
    override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job?): Call.Factory {
        val raw = source.authorizationReceipt?.let { repository.playbackReceiptCalls(it, stillOwned, delegate) }
            ?: source.primaryAccountEpoch?.takeIf { allowPrimaryAccountSource }?.let { repository.primaryPlaybackCalls(it, stillOwned, delegate) }
            ?: throw CancellationException("Missing playback authorization receipt")
        // raw delegates to the existing Repository client, not a new client/dispatcher/cookie jar.
        return admittedPlaybackCalls(raw, this, source, stillOwned, callerJob)
    }
}

/** Synthetic/local transports must explicitly supply their own atomic owner admission. */
internal class DesktopLocalPlaybackPublication(private val isOwned: () -> Boolean,
    private val withOwnedAdmission: ((() -> Unit) -> Boolean)) : DesktopPlaybackPublication {
    override val requiresAccountReceipt = false
    override fun isCurrent(source: PlaybackSource) = source.authorizationReceipt == null && source.primaryAccountEpoch == null && isOwned()
    override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
        if (source.authorizationReceipt != null || source.primaryAccountEpoch != null) throw CancellationException("Local source cannot adopt account authority")
        var outcome: Result<T>? = null
        val accepted = withOwnedAdmission {
            if (!isOwned() || !stillOwned()) throw CancellationException("Local playback owner retired")
            outcome = runCatching(block)
        }
        if (!accepted || outcome == null) throw CancellationException("Local playback owner retired")
        return outcome!!.getOrThrow()
    }
    override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job?) =
        admittedPlaybackCalls(delegate, this, source, stillOwned, callerJob)
}

/** Enqueue is the HTTP publication point. execute waits OUTSIDE the Store monitor. */
@OptIn(InternalCoroutinesApi::class)
internal fun admittedPlaybackCalls(delegate: Call.Factory, publication: DesktopPlaybackPublication,
    source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job? = null): Call.Factory = Call.Factory { request ->
    val call = publication.admit(source, stillOwned) { delegate.newCall(request) }
    object : Call by call {
        override fun request(): Request = call.request()
        override fun cancel() = call.cancel()
        override fun isExecuted() = call.isExecuted()
        override fun isCanceled() = call.isCanceled()
        override fun timeout(): Timeout = call.timeout()
        override fun clone(): Call = admittedPlaybackCalls(delegate, publication, source, stillOwned, callerJob).newCall(request)
        override fun enqueue(callback: Callback) {
            val cancellation = callerJob?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) call.cancel() }
            val guardedCallback = object : Callback {
                override fun onFailure(call: Call, e: IOException) { cancellation?.dispose(); callback.onFailure(call, e) }
                override fun onResponse(call: Call, response: Response) { cancellation?.dispose(); callback.onResponse(call, response) }
            }
            try { publication.admit(source, stillOwned) { call.enqueue(guardedCallback) } }
            catch (failure: Throwable) { cancellation?.dispose(); call.cancel(); throw failure }
        }
        override fun execute(): Response {
            val finished = CountDownLatch(1)
            val abandoned = AtomicBoolean(false)
            val responseGate = Any()
            var response: Response? = null
            var failure: IOException? = null
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failure = e; finished.countDown() }
                override fun onResponse(call: Call, value: Response) {
                    synchronized(responseGate) { if (abandoned.get()) value.close() else response = value }
                    finished.countDown()
                }
            })
            try { finished.await() }
            catch (interrupted: InterruptedException) {
                synchronized(responseGate) { abandoned.set(true); response?.close(); response = null }
                cancel(); Thread.currentThread().interrupt(); throw IOException("Interrupted playback request", interrupted)
            }
            failure?.let { throw it }
            return response ?: throw IOException("Playback request returned no response")
        }
    }
}
'''
write(HERE/'prepared'/BASE/'player/DesktopPlaybackPublication.kt',publication)
modify(BASE+'player/PlaybackSource.kt',[(
 '    val streamHeaders: Map<String, String> = emptyMap(),',
 '    val streamHeaders: Map<String, String> = emptyMap(),\n    val authorizationReceipt: com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt? = null,\n    val primaryAccountEpoch: Long? = null,\n    val nativePublication: DesktopNativePlaybackPublication? = null,',1)])

controller=[
 ('    private val currentDanmakuSettings: () -> com.bilipai.desktop.danmaku.DanmakuSettings,','    private val currentDanmakuSettings: () -> com.bilipai.desktop.danmaku.DanmakuSettings,\n    publication: DesktopPlaybackPublication? = null,',1),
 ('    private val communityReports by lazy', '    private val publication = publication ?: if (dataSource == null) DesktopRepositoryPlaybackPublication(repository)\n        else error("Injected playback transport requires explicit local-source publication admission")\n    private val communityReports by lazy',1),
 ('        val version = native.loadVersioned(resolved.toNative(position, paused))','        val callerJob = currentCoroutineContext()[Job]\n        val retained = publication.ownedSource(resolved.toNative(position, paused), { callerJob?.isCancelled != true &&\n            !closed.get() && expected == generation.get() && playback.sessionEpoch == accountEpoch })\n        val version = publication.admit(retained, { callerJob?.isActive == true && valid(expected, baseline, accountEpoch) }) {\n            native.loadVersioned(retained)\n        }',1),
 ('                if (player?.recoverSource(active.sourceVersion, source.toNative(latest.positionSeconds, latest.paused),\n                        latest.positionSeconds, latest.paused) == true) {','                val callerJob = currentCoroutineContext()[Job]\n                if (publication.admit(source.toNative(latest.positionSeconds, latest.paused),\n                        { callerJob?.isActive == true && owns(active) && expected == generation.get() }) {\n                    player?.recoverSource(active.sourceVersion, source.toNative(latest.positionSeconds, latest.paused),\n                        latest.positionSeconds, latest.paused) == true\n                }) {',1),
 ('                val accepted = player?.recoverSource(context.sourceVersion, source.toNative(latest.positionSeconds, latest.paused),\n                    latest.positionSeconds, latest.paused, forceSoftwareDecoding = software, expectedFailureAttemptId = failure.attemptId) == true','                val callerJob = currentCoroutineContext()[Job]\n                val accepted = publication.admit(source.toNative(latest.positionSeconds, latest.paused),\n                    { callerJob?.isActive == true && recoverable(context, failure) }) {\n                    player?.recoverSource(context.sourceVersion, source.toNative(latest.positionSeconds, latest.paused),\n                        latest.positionSeconds, latest.paused, forceSoftwareDecoding = software, expectedFailureAttemptId = failure.attemptId) == true\n                }',1),
 ('                val accepted = applyDesktopPremiumAudioRecovery(nativePlayer, failure, plan) {\n                    recoverable(context, failure) && context.accountEpoch == playback.sessionEpoch\n                }','                if (!publication.isCurrent(plan.source.toNative(0.0, true))) return true\n                val accepted = publication.admit(plan.source.toNative(0.0, true),\n                    { recoverable(context, failure) && context.accountEpoch == playback.sessionEpoch }) {\n                    applyDesktopPremiumAudioRecovery(nativePlayer, failure, plan) { recoverable(context, failure) }\n                }',1),
 ('        val rewritten = plugins?.rewritePlaybackSource(source) ?: source','        val mapped = plugins?.rewritePlaybackSource(source)\n        val rewritten = if (mapped == null || mapped === source) source else mapped.copy(authorizationReceipt = source.authorizationReceipt)',1),
 ('        if (nativePlayer.recoverSource(context.sourceVersion, source.toNative(native.positionSeconds, native.paused),\n                native.positionSeconds, native.paused)) {','        if (!publication.isCurrent(source.toNative(0.0, true))) return\n        if (publication.admit(source.toNative(native.positionSeconds, native.paused), { owns(context) }) {\n            nativePlayer.recoverSource(context.sourceVersion, source.toNative(native.positionSeconds, native.paused),\n                native.positionSeconds, native.paused)\n        }) {',1),
 ('        owns(it) && it.sourceVersion == expectedSourceVersion && it.accountEpoch == playback.sessionEpoch','        owns(it) && it.sourceVersion == expectedSourceVersion && it.accountEpoch == playback.sessionEpoch &&\n            publication.isCurrent(it.source.toNative(0.0, true))',1),
 ('progressiveSegments = progressiveSegments)','progressiveSegments = progressiveSegments, authorizationReceipt = authorizationReceipt)',1),
 ('handledEnd = false; player?.replay(); current = context.copy(pluginGeneration = null); beginPlugins(current!!); return','if (!publication.isCurrent(context.source.toNative(0.0, false))) return\n            publication.admit(context.source.toNative(0.0, false), { owns(context) }) { player?.replay() }\n            handledEnd = false; current = context.copy(pluginGeneration = null); beginPlugins(current!!); return',1),
]
modify(BASE+'DesktopPlaybackController.kt',controller)
modify(BASE+'DesktopPlaybackController.kt',[(
 '            } catch (cancelled: CancellationException) { throw cancelled }\n            catch (failure: Exception) { failRequest(expected, failure, "无法打开视频") }',
 '            } catch (cancelled: CancellationException) { finishCancelledRequest(expected, accountEpoch); throw cancelled }\n            catch (failure: Exception) { failRequest(expected, failure, "无法打开视频") }',1),(
 '            catch (cancelled: CancellationException) { throw cancelled }\n            catch (failure: Exception) { failRequest(expected, failure, "播放失败") }',
 '            catch (cancelled: CancellationException) { finishCancelledRequest(expected, accountEpoch); throw cancelled }\n            catch (failure: Exception) { failRequest(expected, failure, "播放失败") }',1),(
 '            } catch (cancelled: CancellationException) { throw cancelled }\n            catch (failure: Exception) { failRequest(expected, failure, "切换画质失败") }',
 '            } catch (cancelled: CancellationException) { finishCancelledRequest(expected, context.accountEpoch); throw cancelled }\n            catch (failure: Exception) { failRequest(expected, failure, "切换画质失败") }',1),(
 '    private fun recoverable(context: Current, failure: PlayerFailure): Boolean =',
 '    private fun finishCancelledRequest(expected: Long, accountEpoch: Long) {\n        if (!closed.get() && controllerScope.isActive && expected == generation.get() && accountEpoch == playback.sessionEpoch)\n            mutableState.update { it.copy(opening = false) }\n    }\n    private fun recoverable(context: Current, failure: PlayerFailure): Boolean =',1)])
# Dispatch can wait after loadVersioned; admit again at the actual native loadfile command.
mpv=start(BASE+'player/MpvPlayer.kt')
old=mpv[mpv.index('                    is Action.Load -> {'):mpv.index('                    is Action.HardwareDecoding ->')]
inside=old[len('                    is Action.Load -> {\n'):].rsplit('                    }\n',1)[0]
inside=inside.replace('                        if (!synchronized(lock) { action.version == sourceVersion && action.revision == playbackRevision }) return',
 '                        if (session !== this || closing.get() || action.version != sourceVersion || action.revision != playbackRevision) return@synchronized',1)
new='''                    is Action.Load -> {
                        val command = { synchronized(lock) {
'''+inside+'''                        } }
                        val admission = action.source.nativePublication
                        if (admission != null) admission.admit(command)
                        else if (action.source.authorizationReceipt == null && action.source.primaryAccountEpoch == null) command()
                        // Rejection leaves the active native media untouched.
                    }
'''
modify(BASE+'player/MpvPlayer.kt',[(old,new,1)])
modify(BASE+'DesktopPlaybackController.kt',[(
 'player?.recoverSource(active.sourceVersion, source.toNative(latest.positionSeconds, latest.paused),',
 'player?.recoverSource(active.sourceVersion, publication.ownedSource(source.toNative(latest.positionSeconds, latest.paused),\n                            { callerJob?.isCancelled != true && owns(active) && expected == generation.get() }),',1),(
 'player?.recoverSource(context.sourceVersion, source.toNative(latest.positionSeconds, latest.paused),',
 'player?.recoverSource(context.sourceVersion, publication.ownedSource(source.toNative(latest.positionSeconds, latest.paused),\n                            { callerJob?.isCancelled != true && owns(context) }),',1),(
 'nativePlayer.recoverSource(context.sourceVersion, source.toNative(native.positionSeconds, native.paused),',
 'nativePlayer.recoverSource(context.sourceVersion, publication.ownedSource(source.toNative(native.positionSeconds, native.paused), { owns(context) }),',1),(
 'applyDesktopPremiumAudioRecovery(nativePlayer, failure, plan) { recoverable(context, failure) }',
 'applyDesktopPremiumAudioRecovery(nativePlayer, failure, plan,\n                        nativePublication = publication.ownedSource(plan.source.toNative(0.0, true), { owns(context) }).nativePublication) { recoverable(context, failure) }',1)])
modify(BASE+'player/DesktopPremiumAudioRecovery.kt',[(
 '    accountIsCurrent: () -> Boolean,',
 '    nativePublication: DesktopNativePlaybackPublication? = null,\n    accountIsCurrent: () -> Boolean,',1),(
 'val replacement = owned.source.copy(audioUrl = plan.source.audioUrl)',
 'val replacement = owned.source.copy(audioUrl = plan.source.audioUrl, authorizationReceipt = plan.source.authorizationReceipt,\n        nativePublication = nativePublication ?: owned.source.nativePublication)',1)])
modify(BASE+'audio/DesktopAudioRepository.kt',[(
 'PlaybackSource(address, cookieHeader = cookies, title = actual.title)',
 'PlaybackSource(address, cookieHeader = cookies, title = actual.title, primaryAccountEpoch = epoch)',1),(
 'progressiveSegments = if (media.audioUrl == null) media.progressiveSegments else emptyList()), details.aid)',
 'progressiveSegments = if (media.audioUrl == null) media.progressiveSegments else emptyList(),\n                authorizationReceipt = media.authorizationReceipt), details.aid)',1)])
modify(BASE+'audio/ListenAudioSession.kt',[(
 '    playbackDataSource: ListenPlaybackDataSource? = null,','    playbackDataSource: ListenPlaybackDataSource? = null,\n    publication: com.bilipai.desktop.player.DesktopPlaybackPublication? = null,',1),(
 '    private val playback: ListenPlaybackDataSource = playbackDataSource ?: audio',
 '    private val playback: ListenPlaybackDataSource = playbackDataSource ?: audio\n    private val publication = publication ?: if (playbackDataSource == null)\n        com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository, allowPrimaryAccountSource = true)\n        else error("Injected listen transport requires explicit local-source publication admission")',1),(
 '                ownedSourceVersion = player.loadVersioned(prepared.source.copy(startPositionSeconds = startPosition))',
 '                val callerJob = currentCoroutineContext()[Job]\n                val retained = publication.ownedSource(prepared.source.copy(startPositionSeconds = startPosition),\n                    { callerJob?.isCancelled != true && generation == playGeneration && sessionIsCurrent() })\n                ownedSourceVersion = publication.admit(retained, { callerJob?.isActive == true && generation == playGeneration &&\n                    sessionIsCurrent() && player.currentSourceVersion == pendingSourceVersion && player.currentSourceSnapshot() == null }) {\n                    player.loadVersioned(retained)\n                }',1),(
 '            if (player.state.value.ended) player.replay() else player.setPaused(false)',
 '            if (player.state.value.ended) {\n                val source = player.currentSourceSnapshot()?.source ?: return\n                if (!publication.isCurrent(source)) return\n                publication.admit(source, { sessionIsCurrent() && ownedPlaybackSourceVersion == expectedSource }) { player.replay() }\n            } else player.setPaused(false)',1)])
modify(BASE+'ui/MediaScreens.kt',[(
 '        playJob?.cancel(); stopOwned(); opening = true; error = null; notice = null\n        memory.launchRequest {',
 '        playJob?.cancel(); stopOwned(); opening = true; error = null; notice = null\n        val nativeBaseline = player?.currentSourceVersion\n        memory.launchRequest {',1),(
 '                episode = selected; playback = info; quality = info.source.quality\n                val maskEpoch = repository.sessionEpoch\n                val episodeSourceVersion = initialized.loadVersioned(info.source.toNativePlayback().copy(startPositionSeconds = startPosition))',
 '                val callerJob = currentCoroutineContext()[Job]\n                val maskEpoch = repository.sessionEpoch\n                val episodeSourceVersion = repository.withPlaybackSourceAdmission(info.source, { callerJob?.isActive == true &&\n                    memory.scope.isActive && memory.playJob === callerJob && season === current && initialized.currentSourceVersion == nativeBaseline }) {\n                    initialized.loadVersioned(info.source.toNativePlayback().copy(startPositionSeconds = startPosition))\n                }\n                episode = selected; playback = info; quality = info.source.quality',1),(
 'progressiveSegments = progressiveSegments)','progressiveSegments = progressiveSegments, authorizationReceipt = authorizationReceipt)',1)])
modify(BASE+'audio/ListenAudioSession.kt',[(
 'import com.bilipai.desktop.player.', 'import com.bilipai.desktop.player.',0)]) if False else None
audio=bodies[BASE+'audio/ListenAudioSession.kt']
anchor=audio[audio.index('import com.bilipai.desktop.player.'):].splitlines()[0]
modify(BASE+'audio/ListenAudioSession.kt',[(anchor,anchor+'\nimport com.bilipai.desktop.player.ownedSource',1)])
modify(BASE+'ui/MediaScreens.kt',[(
 'initialized.loadVersioned(info.source.toNativePlayback().copy(startPositionSeconds = startPosition))',
 'initialized.loadVersioned(com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository).ownedSource(\n                        info.source.toNativePlayback().copy(startPositionSeconds = startPosition),\n                        { callerJob?.isCancelled != true && memory.scope.isActive && season === current && repository.sessionEpoch == maskEpoch }))',1)])
media=bodies[BASE+'ui/MediaScreens.kt'];anchor=media[media.index('import com.bilipai.desktop.player.'):].splitlines()[0]
modify(BASE+'ui/MediaScreens.kt',[(anchor,anchor+'\nimport com.bilipai.desktop.player.ownedSource',1)])

# Original downloader needs only a Call.Factory type adapter; all probe/range/chunk math stays verbatim.
raw='app/src/main/java/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt'
s=read(REPO/raw); adapted=s.replace('private val client: OkHttpClient','private val client: okhttp3.Call.Factory',1)
assert adapted!=s
write(HERE/'prepared/generated/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt',adapted)
changes.append(dict(path=raw,producerRequired=True,baseLF=sha(s),candidateLF=sha(adapted),hunks=[dict(before='private val client: OkHttpClient',after='private val client: okhttp3.Call.Factory',occurrences=1)],directBodyTypeAdapterOnly=True))
modify(BASE+'download/DesktopDownloadModels.kt',[(
 '    val progressiveSegments: List<DownloadProgressiveSegment> = emptyList(),',
 '    val progressiveSegments: List<DownloadProgressiveSegment> = emptyList(),\n    @kotlinx.serialization.Transient val authorizationReceipt: com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt? = null,\n    @kotlinx.serialization.Transient val cookieHeader: String = "",\n    @kotlinx.serialization.Transient val streamHeaders: Map<String, String> = emptyMap(),',1),(
 '    val id: String get() = item.id',
 '    override fun toString() = "DownloadTask(id=$id, status=$status, authorization=$authorizationReceipt)"\n    internal fun playbackSource() = com.bilipai.desktop.player.PlaybackSource(item.videoUrl, item.audioUrl.takeIf { it.isNotBlank() },\n        referer, userAgent, cookieHeader, item.title, progressiveSegments = progressiveSegments.map {\n            com.bilipai.desktop.player.PlaybackSegment(it.url, it.durationSeconds) },\n        streamHeaders = streamHeaders, authorizationReceipt = authorizationReceipt)\n    val id: String get() = item.id',1)])
dm=BASE+'download/DesktopDownloadManager.kt'
modify(dm,[(
 '    private val danmakuDownloader: (suspend (DownloadTask, Path, (DownloadAssetState) -> Unit) -> Pair<List<String>, String?>)? = null,',
 '    private val danmakuDownloader: (suspend (DownloadTask, Path, (DownloadAssetState) -> Unit) -> Pair<List<String>, String?>)? = null,\n    private val publication: com.bilipai.desktop.player.DesktopPlaybackPublication,',1),(
 'defaultSourceResolver(repository), defaultDanmakuDownloader(repository))',
 'defaultSourceResolver(repository), defaultDanmakuDownloader(repository), com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository))',1),(
 '    private val downloader = ResumableAssetDownloader(client)\n','',1),(
 'metadata: DownloadMetadata = DownloadMetadata()): String = synchronized(lock) {',
 'metadata: DownloadMetadata = DownloadMetadata(), stillOwned: () -> Boolean = { true }): String =\n        publication.admit(source, stillOwned) { enqueueAdmitted(source, destination, metadata) }\n\n    private fun enqueueAdmitted(source: PlaybackSource, destination: Path, metadata: DownloadMetadata): String = synchronized(lock) {',1),(
 'resume(existing.id, source)','resumeAdmitted(existing.id, source)',1),(
 'metadata.seasonId, metadata.episodeId, metadata.isCourse, segments)',
 'metadata.seasonId, metadata.episodeId, metadata.isCourse, segments, source.authorizationReceipt, source.cookieHeader,\n            com.bilipai.desktop.player.copyPlaybackStreamHeaders(source.streamHeaders))',1),(
 '    fun resume(id: String, refreshedSource: PlaybackSource? = null) = synchronized(lock) {',
 '    fun resume(id: String, refreshedSource: PlaybackSource? = null) {\n        if (refreshedSource != null) publication.admit(refreshedSource, { true }) { resumeAdmitted(id, refreshedSource) }\n        else resumeAdmitted(id, null)\n    }\n    private fun resumeAdmitted(id: String, refreshedSource: PlaybackSource?) = synchronized(lock) {',1),(
 'referer = refreshedSource?.referer ?: it.referer, userAgent = refreshedSource?.userAgent ?: it.userAgent,',
 'referer = refreshedSource?.referer ?: it.referer, userAgent = refreshedSource?.userAgent ?: it.userAgent,\n                authorizationReceipt = refreshedSource?.authorizationReceipt, cookieHeader = refreshedSource?.cookieHeader.orEmpty(),\n                streamHeaders = refreshedSource?.streamHeaders?.let { headers -> com.bilipai.desktop.player.copyPlaybackStreamHeaders(headers) } ?: emptyMap(),',1),(
 '            val task = synchronized(lock) { mutableTasks.value.firstOrNull { it.id == id } } ?: return\n            if (task.status != DownloadStatus.PENDING) return',
 '            var task = synchronized(lock) { mutableTasks.value.firstOrNull { it.id == id } } ?: return\n            if (task.status != DownloadStatus.PENDING) return\n            val pendingJob = currentCoroutineContext()[Job]\n            val owned = { pendingJob?.isActive == true && synchronized(lock) { !closed && jobs[id] === pendingJob &&\n                mutableTasks.value.any { it.id == id && it.status in setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING) } } }\n            if (publication.requiresAccountReceipt && task.authorizationReceipt == null) {\n                val refreshed = sourceResolver?.invoke(task) ?: throw CancellationException("Saved download requires fresh owned authorization")\n                currentCoroutineContext().ensureActive()\n                publication.admit(refreshed, owned) {\n                    update(id, true) { it.copy(item = it.item.copy(videoUrl = requireMediaUrl(refreshed.videoUrl),\n                        audioUrl = refreshed.audioUrl?.let(::requireMediaUrl).orEmpty()),\n                        referer = refreshed.referer, userAgent = refreshed.userAgent, authorizationReceipt = refreshed.authorizationReceipt,\n                        cookieHeader = refreshed.cookieHeader, streamHeaders = com.bilipai.desktop.player.copyPlaybackStreamHeaders(refreshed.streamHeaders),\n                        progressiveSegments = refreshed.progressiveSegments.map { part -> DownloadProgressiveSegment(requireMediaUrl(part.url), part.durationSeconds) }) }\n                }\n                task = synchronized(lock) { mutableTasks.value.first { it.id == id } }\n            }\n            publication.admit(task.playbackSource(), owned) { Unit }',1),(
 '        val headers = mapOf("Referer" to task.referer, "User-Agent" to task.userAgent)',
 '        val source = task.playbackSource()\n        val callerJob = context[Job]\n        val owned = { callerJob?.isActive == true && synchronized(lock) { !closed && jobs[id] === callerJob &&\n            mutableTasks.value.any { it.id == id && it.status == DownloadStatus.DOWNLOADING && it.authorizationReceipt == task.authorizationReceipt } } }\n        publication.admit(source, owned) { Unit }\n        val headers = com.bilipai.desktop.cast.desktopCastStreamHeaders(source)\n        var activeSource = source\n        fun calls() = publication.calls(client, activeSource, owned, callerJob)',1),(
 'suspend fun download(activeUrl: String) = downloader.download(',
 'suspend fun download(activeUrl: String) = ResumableAssetDownloader(calls()).download(',1),(
 '            context.ensureActive()\n            val refreshed = sourceResolver.invoke(task) ?: throw error',
 '            context.ensureActive()\n            // A retired choice is cancellation, never a request under the newly selected account.\n            publication.admit(activeSource, owned) { Unit }\n            val refreshed = sourceResolver.invoke(task) ?: throw error\n            context.ensureActive()\n            publication.admit(refreshed, owned) { Unit }',1),(
 '            update(id, true) { wrapper -> wrapper.copy(item = wrapper.item.copy(videoUrl = refreshed.videoUrl,\n                audioUrl = refreshed.audioUrl.orEmpty()), progressiveSegments = wrapper.progressiveSegments.zip(refreshed.progressiveSegments).map { (old, part) -> old.copy(url = requireMediaUrl(part.url), durationSeconds = part.durationSeconds) }) }',
 '            publication.admit(refreshed, owned) {\n                update(id, true) { wrapper -> wrapper.copy(item = wrapper.item.copy(videoUrl = refreshed.videoUrl,\n                    audioUrl = refreshed.audioUrl.orEmpty()), authorizationReceipt = refreshed.authorizationReceipt,\n                    cookieHeader = refreshed.cookieHeader, streamHeaders = com.bilipai.desktop.player.copyPlaybackStreamHeaders(refreshed.streamHeaders),\n                    progressiveSegments = wrapper.progressiveSegments.zip(refreshed.progressiveSegments).map { (old, part) -> old.copy(url = requireMediaUrl(part.url), durationSeconds = part.durationSeconds) }) }\n            }\n            activeSource = refreshed',1),(
 'progressiveSegments = it.progressiveSegments) }','progressiveSegments = it.progressiveSegments, authorizationReceipt = it.authorizationReceipt) }',1)])
# Capture immutable route and current request Job before the root queue publication.
modify(BASE+'DesktopShell.kt',[(
 '                                        val info = playing.details!!\n                                        val part = info.pages[playing.currentPart]',
 '                                        val downloadJob = kotlinx.coroutines.currentCoroutineContext()[Job]\n                                        val info = playing.details!!\n                                        val part = info.pages[playing.currentPart]',1),(
 '                                            progressiveSegments = source.progressiveSegments), metadata = DownloadMetadata(',
 '                                            progressiveSegments = source.progressiveSegments, authorizationReceipt = source.authorizationReceipt), metadata = DownloadMetadata(',1),(
 'includeDanmaku = options.includeDanmaku, episodeLabel = part.title))',
 'includeDanmaku = options.includeDanmaku, episodeLabel = part.title),\n                                            stillOwned = { !isClosing() && !activatingUpdate && repository.sessionEpoch == expectedEpoch &&\n                                                downloadJob?.isActive == true && playback.state.value.details?.bvid == info.bvid &&\n                                                playback.state.value.details?.pages?.getOrNull(playback.state.value.currentPart)?.cid == part.cid })',1)])

modify(BASE+'DesktopPlaybackController.kt',[(
 'private fun owns(context: Current): Boolean = !closed.get() &&',
 'private fun owns(context: Current): Boolean = !closed.get() && controllerScope.isActive &&',1),(
 'private fun valid(expected: Long, baseline: Long?, accountEpoch: Long): Boolean = !closed.get() &&',
 'private fun valid(expected: Long, baseline: Long?, accountEpoch: Long): Boolean = !closed.get() && controllerScope.isActive &&',1),(
 '!closed.get() && expected == generation.get() && playback.sessionEpoch == accountEpoch })',
 '!closed.get() && controllerScope.isActive && expected == generation.get() && playback.sessionEpoch == accountEpoch })',1)])
modify(BASE+'player/MpvPlayer.kt',[(
 '    /** Reloads the same item for CDN/codec recovery without transferring surface ownership or losing subtitles. */',
 '''    /** Re-admit a retained stream for replay without replacing another owner/source. */
    internal fun replayAuthorized(expectedSourceVersion: Long, replacement: PlaybackSource): Boolean = synchronized(lock) {
        if (closed.get() || sourceVersion != expectedSourceVersion || requestedSource == null) return@synchronized false
        load(replacement.copy(startPositionSeconds = 0.0, startPaused = false), preserveSubtitles = true)
        true
    }
    /** Reloads the same item for CDN/codec recovery without transferring surface ownership or losing subtitles. */''',1)])
modify(BASE+'audio/ListenAudioSession.kt',[(
 'private fun sessionIsCurrent() = !closed && repository.sessionEpoch == sessionEpoch',
 'private fun sessionIsCurrent() = !closed && scope.isActive && repository.sessionEpoch == sessionEpoch',1),(
 'preferences.playbackMode == PlaybackMode.REPEAT_ONE -> player.replay()',
 'preferences.playbackMode == PlaybackMode.REPEAT_ONE -> replayOwnedSource()',1),(
 '                publication.admit(source, { sessionIsCurrent() && ownedPlaybackSourceVersion == expectedSource }) { player.replay() }',
 '                replayOwnedSource()',1),(
 '    fun pause() {',
 '''    private fun replayOwnedSource(): Boolean {
        val expectedSource = ownedPlaybackSourceVersion ?: return false
        val source = player.currentSourceSnapshot()?.takeIf { it.sourceVersion == expectedSource }?.source ?: return false
        if (!publication.isCurrent(source)) return false
        val generation = playGeneration
        val owned = { sessionIsCurrent() && generation == playGeneration && ownedPlaybackSourceVersion == expectedSource }
        val retained = publication.ownedSource(source, owned)
        return publication.admit(retained, owned) { player.replayAuthorized(expectedSource, retained) }
    }

    fun pause() {''',1),(
 '            } catch (failure: Exception) {\n                if (failure is CancellationException) throw failure\n                if (generation == playGeneration && sessionIsCurrent()) mutableState.update { it.copy(loading = false, active = false, error = failure.message ?: "音频加载失败。") }',
 '''            } catch (failure: Exception) {
                if (failure is CancellationException) {
                    if (generation == playGeneration && sessionIsCurrent()) mutableState.update { it.copy(loading = false, active = false) }
                    throw failure
                }
                if (generation == playGeneration && sessionIsCurrent()) mutableState.update { it.copy(loading = false, active = false, error = failure.message ?: "音频加载失败。") }''',1)])
modify(BASE+'ui/MediaScreens.kt',[(
 '                                    episodeSortIndex = current.episodes.indexOf(selected) + 1, episodeCount = current.episodes.size,\n                                ))',
 '''                                    episodeSortIndex = current.episodes.indexOf(selected) + 1, episodeCount = current.episodes.size,
                                ), stillOwned = { memory.scope.isActive && season === current && episode === selected && playback === info })''',1),(
 '                            } catch (failure: Exception) { error = failure.message ?: "添加下载失败" }',
 '                            } catch (cancelled: CancellationException) { throw cancelled }\n                            catch (failure: Exception) { error = failure.message ?: "添加下载失败" }',1)])

exec(read(HERE/'split-download-io.py'),globals())
modify(BASE+'DesktopPlaybackController.kt',[(
 '                val accepted = publication.admit(plan.source.toNative(0.0, true),',
 '                val accepted = publication.tryAdmit(plan.source.toNative(0.0, true),',1),(
 '                } else if (recoverable(context, failure)) {',
 '                } else if (recoverable(context, failure) && publication.isCurrent(plan.source.toNative(0.0, true))) {',1),(
 'if (publication.admit(source.toNative(native.positionSeconds, native.paused), { owns(context) }) {',
 'if (publication.tryAdmit(source.toNative(native.positionSeconds, native.paused), { owns(context) }) {',1),(
 '            publication.admit(context.source.toNative(0.0, false), { owns(context) }) { player?.replay() }',
 '''            val retained = publication.ownedSource(context.source.toNative(0.0, false), { owns(context) })
            if (!publication.tryAdmit(retained, { owns(context) }) { player?.replayAuthorized(context.sourceVersion, retained) == true }) return''',1)])
ctrl=bodies[BASE+'DesktopPlaybackController.kt'];anchor=ctrl[ctrl.index('import com.bilipai.desktop.player.'):].splitlines()[0]
modify(BASE+'DesktopPlaybackController.kt',[(anchor,anchor+'\nimport com.bilipai.desktop.player.tryAdmit',1)])
modify(BASE+'audio/ListenAudioSession.kt',[(
 'return publication.admit(retained, owned) { player.replayAuthorized(expectedSource, retained) }',
 'return publication.tryAdmit(retained, owned) { player.replayAuthorized(expectedSource, retained) }',1),(
 'import com.bilipai.desktop.player.ownedSource',
 'import com.bilipai.desktop.player.ownedSource\nimport com.bilipai.desktop.player.tryAdmit',1)])
exec(read(HERE/'prepare-cast.py'),globals())

# Unchanged ABI support is compile-only: Native PlaybackSource.copy gains receipt fields.
write(HERE/'proof-only'/BASE/'player/PlaybackStreamHeaders.kt',read(REPO/BASE/'player/PlaybackStreamHeaders.kt'))

# Commit verified exact hunks only; full files remain proof-only.
write(HERE/'local-hunks.json',json.dumps(dict(status='PREPARED',backend84FrozenManifest=hashlib.sha256(safe(BACKEND/'frozen-handoff.json').read_bytes()).hexdigest(),changes=changes),ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(changes=len(changes),preparedSources=len(list((HERE/'prepared').rglob('*.kt'))),proofSources=len(list((HERE/'proof-only').rglob('*.kt'))))))
