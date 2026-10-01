from pathlib import Path
import hashlib,json
H=Path(__file__).resolve().parent;MAIN=H.parents[2];CAND=MAIN.with_name('BiliPai-v023')
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def digest(b):return hashlib.sha256(b).hexdigest()
def row(p):b=read(p);return dict(path=str(p),sha256Bytes=digest(b),size=len(b))
def dump(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
hunks=[];families=[]
def family(relative,edits):
 raw=read(CAND/relative);before=raw.decode().replace('\r\n','\n');after=before
 for i,(old,new)in enumerate(edits):
  assert after.count(old)==1,(relative,i,after.count(old))
  hunks.append(dict(path=relative,index=i,before=old,after=new,beforeSnippetSha256=digest(old.encode()),afterSnippetSha256=digest(new.encode())))
  after=after.replace(old,new,1)
 write(H/'baseline'/relative,raw);write(H/'prepared/existing'/relative,after)
 families.append(dict(path=relative,wholeBytesSha256=digest(raw),wholeLfSha256=digest(before.encode()),candidateLfSha256=digest(after.encode()),baseline=row(H/'baseline'/relative),prepared=row(H/'prepared/existing'/relative)))

failure='''package com.bilipai.desktop.player.cache

import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.player.DesktopNativePlaybackPublication

internal enum class DesktopNativeByteFailureStage { METADATA, BODY_READ }

/** Opaque, captured native read frame. A prefetch/probe cannot create a native
 * failure merely because its best-effort request failed. No address or headers. */
internal class DesktopNativeByteReadStamp internal constructor(
    internal val lease: DesktopMediaByteLease,
    internal val frameIdentity: Any,
    val sourceVersion: Long,
    internal val publication: DesktopNativePlaybackPublication,
    internal val receipt: DesktopPlaybackAuthorizationReceipt,
) {
    override fun toString() = "DesktopNativeByteReadStamp(sourceVersion=$sourceVersion)"
}

/** Capacity ONE per actual lease frame; a real failed native read publishes it
 * under the same Store -> entry admission. Throwable text/URL/Cookie are absent.
 * Constructors do not grant recovery: the lease checks exact event/frame identity. */
internal class DesktopNativeByteFailure internal constructor(
    val sequence: Long,
    internal val stamp: DesktopNativeByteReadStamp,
    val stage: DesktopNativeByteFailureStage,
) {
    val sourceVersion: Long get() = stamp.sourceVersion
    override fun toString() = "DesktopNativeByteFailure(sequence=$sequence, sourceVersion=$sourceVersion, stage=$stage)"
}
'''
write(H/'prepared/manual/desktop/src/main/kotlin/com/bilipai/desktop/player/cache/DesktopNativeByteFailure.kt',failure)

cache_methods='''    // Only the real loopback native metadata/body paths call this seam.
    // Prefetch/probe stay best effort. Every read fixes its frame BEFORE IO.
    internal fun captureNativeReadStamp(): DesktopNativeByteReadStamp? {
        val value = frame.get()
        if (closed.get() || value.retired || !leaseJob.isActive || value.version == null || value.publication == null) return null
        return DesktopNativeByteReadStamp(this, value, value.version, value.publication, value.admission.receipt)
    }
    private fun matchesNativeStamp(stamp: DesktopNativeByteReadStamp): Boolean {
        val value = frame.get()
        return stamp.lease === this && stamp.frameIdentity === value && !closed.get() && !value.retired &&
            leaseJob.isActive && value.admission.ownerJob.isActive && value.version == stamp.sourceVersion &&
            value.publication === stamp.publication && value.admission.receipt == stamp.receipt
    }
    internal fun reportNativeReadFailure(stamp: DesktopNativeByteReadStamp?, callerJob: Job,
        stage: DesktopNativeByteFailureStage) {
        if (stamp == null || !callerJob.isActive || !matchesNativeStamp(stamp)) return
        val value = frame.get()
        try {
            value.admission.commit {
                if (!callerJob.isActive || !matchesNativeStamp(stamp)) return@commit
                // First failure wins until this exact publication retires/adopts.
                // No unbounded queue, IO, Throwable or callbacks under the gate.
                if (nativeFailure.get()?.stamp?.frameIdentity !== value) {
                    nativeFailure.set(DesktopNativeByteFailure(failureSequence.incrementAndGet(), stamp, stage))
                }
            }
        } catch (_: CancellationException) { /* retired IO is not a transport failure */ }
    }
    internal fun ownsNativeFailure(value: DesktopNativeByteFailure): Boolean =
        nativeFailure.get() === value && matchesNativeStamp(value.stamp)
    internal fun latestNativeFailure(): DesktopNativeByteFailure? = nativeFailure.get()?.takeIf(::ownsNativeFailure)

'''
family('desktop/src/main/kotlin/com/bilipai/desktop/player/cache/DesktopMediaByteCache.kt',[
 ('            var bodyOwnsJob = false\n','            var bodyOwnsJob = false\n            var nativeReadStamp: DesktopNativeByteReadStamp? = null\n'),
 ('                val metadata = runBlocking(job + Dispatchers.IO) { lease.metadata(track) }','                nativeReadStamp = lease.captureNativeReadStamp()\n                val metadata = runBlocking(job + Dispatchers.IO) { lease.metadata(track) }'),
 ('            catch (_: Exception) { job.cancel(); return fail(Response.Status.SERVICE_UNAVAILABLE) }','''            catch (failure: Exception) {
                if (failure is IOException) lease.reportNativeReadFailure(nativeReadStamp, job, DesktopNativeByteFailureStage.METADATA)
                job.cancel(); return fail(Response.Status.SERVICE_UNAVAILABLE)
            }'''),
 ('    private val frame = AtomicReference(Frame(initial, null, null))','    private val frame = AtomicReference(Frame(initial, null, null))\n    private val nativeFailure = AtomicReference<DesktopNativeByteFailure?>()\n    private val failureSequence = AtomicLong()'),
 ('    internal fun probeAdmission(): DesktopMediaByteAdmission { check(); return frame.get().admission }',cache_methods+'    internal fun probeAdmission(): DesktopMediaByteAdmission { check(); return frame.get().admission }'),
 ('check(frame.compareAndSet(old, next)) { "Media byte lease changed" }; watchOwner(next)','check(frame.compareAndSet(old, next)) { "Media byte lease changed" }; nativeFailure.set(null); watchOwner(next)'),
 ('        watchOwner(next); return true','        nativeFailure.set(null); watchOwner(next); return true'),
 ('    internal fun markRetired() { closed.set(true);cache.unregister(this) }','    internal fun markRetired() { closed.set(true);nativeFailure.set(null);cache.unregister(this) }'),
 ('''            requestJob.ensureActive(); check(); if(position>=end)return -1
            reader.get()?.takeIf''','''            requestJob.ensureActive(); check(); if(position>=end)return -1
            val nativeReadStamp = captureNativeReadStamp()
            try {
            reader.get()?.takeIf'''),
 ('''            position+=n;return n
        }
        override fun close()''','''            position+=n;return n
            } catch (failure: IOException) {
                if (!done.get()) reportNativeReadFailure(nativeReadStamp, requestJob, DesktopNativeByteFailureStage.BODY_READ)
                throw failure
            }
        }
        override fun close()'''),
])

owner_method='''    /** The real native loopback may buffer forever after an origin IOException,
     * without MPV generating a PlayerFailure attempt. Consume its fixed lease
     * event through the existing full-owner observer, including loading/paused.
     * No fake native failure, latest-account lookup or second recovery poller. */
    fun observeByteCacheFailure(): Boolean {
        val expected = current() ?: return false
        val transport = expected.nativeSource.source.nativeTransport ?: return false
        val failure = transport.lease.latestNativeFailure() ?: return false
        return recoverDirectAfterByteFailure(expected, failure)
    }

    internal fun recoverDirectAfterByteFailure(expected: DesktopOriginalVideoAcceptedPublication,
        failure: com.bilipai.desktop.player.cache.DesktopNativeByteFailure): Boolean {
        val transport = expected.nativeSource.source.nativeTransport ?: return false
        fun currentFailure(): Boolean = owns(expected) && transport.lease.ownsNativeFailure(failure) &&
            failure.sourceVersion == expected.sourceVersion &&
            failure.stamp.publication === expected.nativeSource.source.nativePublication &&
            failure.stamp.receipt == expected.nativeSource.source.authorizationReceipt
        if (!currentFailure()) return false
        var recovered = false
        try {
            publication.admit(expected.nativeSource.source, ::currentFailure) {
                withEntryAdmission {
                    if (!currentFailure()) return@withEntryAdmission
                    player.admitSourceSnapshot(expected.nativeSource) {
                        if (!currentFailure()) return@admitSourceSnapshot
                        val readback = player.state.value
                        val position = readback.positionSeconds
                        if (!position.isFinite() || position < 0.0) return@admitSourceSnapshot
                        // Requested pause remains real even when buffering has no
                        // nativePaused readback. Never infer autoplay from null.
                        val paused = readback.paused
                        lateinit var next: DesktopOriginalVideoAcceptedPublication
                        val direct = retainedSource(expected.nativeSource.source.copy(nativeTransport = null,
                            startPositionSeconds = position, startPaused = paused)) { owns(next) }
                        if (!player.recoverSource(expected.sourceVersion, direct, position, paused)) return@admitSourceSnapshot
                        next = DesktopOriginalVideoAcceptedPublication(expected.request,
                            checkNotNull(player.currentSourceSnapshot()).also { check(it.sourceVersion == expected.sourceVersion) })
                        accepted.set(next)
                        inheritedMute.get()?.takeIf { it.lease === expected }?.let { previous ->
                            inheritedMute.compareAndSet(previous, InheritedMute(next, previous.interval))
                        }
                        onAccepted(next); recovered = true
                    }
                }
            }
        } catch (_: CancellationException) { return false }
        return recovered
    }

'''
family('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.kt',[
 ('    /** Called by the existing full-owner position observer, with no new poller.',owner_method+'    /** Called by the existing full-owner position observer, with no new poller.')])
family('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerPluginBridge.kt',[
 ('    override fun observeInheritedPluginMute() { native.observeInheritedPluginMute() }','''    override fun observeInheritedPluginMute() {
        // This original observer runs before plugin/isPlaying/loading filters.
        native.observeByteCacheFailure()
        native.observeInheritedPluginMute()
    }''')])
dump(H/'exact-hunks.json',hunks);dump(H/'baseline-families.json',families)
dump(H/'install-contract.json',dict(copyWhitelist=[str(Path('desktop/src/main/kotlin/com/bilipai/desktop/player/cache/DesktopNativeByteFailure.kt'))],exactHunks='exact-hunks.json',wholeFamilyPins='baseline-families.json',originalSourceRegistryDelta=0,dependencyDelta=0,cacheActors=1,storeActors=1,playerActors=1,capacityPerLease=1,proofLabel='prospective overrides against immutable actual73; not installed Root'))
print(json.dumps(dict(families=len(families),hunks=len(hunks),newManual=1)))
