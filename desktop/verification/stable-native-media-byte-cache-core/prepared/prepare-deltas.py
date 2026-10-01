from pathlib import Path
import hashlib,json,subprocess,sys,difflib
sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;C=H.parents[3]/'BiliPai-v023'
def raw(p):return p.read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def save(p,b):p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b.encode()if isinstance(b,str)else b)
rows=[];states={};originals={}
def change(path,label,before,after):
 if path not in states:
  originals[path]=raw(C/path);states[path]=originals[path].decode().replace('\r\n','\n')
  save(H/'baseline'/path,originals[path])
 text=states[path];assert text.count(before)==1,(path,label,text.count(before))
 result=text.replace(before,after,1)
 rows.append(dict(path=path,label=label,before=before,after=after,beforeSha256LF=sha(text.encode()),afterSha256LF=sha(result.encode())))
 states[path]=result
P='desktop/src/main/kotlin/com/bilipai/desktop/'
change(P+'player/PlaybackSource.kt','native-only carrier at constructor tail',
 '    val nativePublication: DesktopNativePlaybackPublication? = null,\n',
 '    val nativePublication: DesktopNativePlaybackPublication? = null,\n    /** Native-only local byte ingress; original remote fields stay authoritative. */\n    val nativeTransport: com.bilipai.desktop.player.cache.DesktopNativeMediaTransport? = null,\n')
change(P+'player/PlaybackSource.kt','native URI projection only',
 '        get() = if (progressiveSegments.isEmpty()) videoUrl else "edl://" + progressiveSegments.joinToString(";") { segment ->',
 '        get() = nativeTransport?.nativeVideo(this) ?: if (progressiveSegments.isEmpty()) videoUrl else "edl://" + progressiveSegments.joinToString(";") { segment ->')
change(P+'player/PlaybackStreamHeaders.kt','validate immutable carrier semantic identity',
 '''internal fun PlaybackSource.immutableSnapshot(): PlaybackSource = copy(
    progressiveSegments = Collections.unmodifiableList(progressiveSegments.toList()),
    streamHeaders = copyPlaybackStreamHeaders(streamHeaders),
)''',
 '''internal fun PlaybackSource.immutableSnapshot(): PlaybackSource {
    nativeTransport?.validate(this)
    return copy(
        progressiveSegments = Collections.unmodifiableList(progressiveSegments.toList()),
        streamHeaders = copyPlaybackStreamHeaders(streamHeaders),
    )
}''')
change(P+'player/MpvNative.kt','clear origin credentials on native loopback; full MPD has own audio adaptation',
 '''    audioUrl?.takeIf { it.isNotBlank() }?.let { put("audio-files", escapeMpvListItem(it, ';')) }
    val explicit = copyPlaybackStreamHeaders(streamHeaders)''',
 '''    val transport = nativeTransport
    (if (transport == null) audioUrl else transport.audioUri)?.takeIf { it.isNotBlank() }
        ?.let { put("audio-files", escapeMpvListItem(it, ';')) }
    if (transport != null && transport.audioUri == null) put("audio-files", "")
    val explicit = if (transport == null) copyPlaybackStreamHeaders(streamHeaders) else emptyMap()''')
change(P+'player/MpvNative.kt','local input Referrer and UA do not contain origin data',
 '''    put("referrer", if (explicit.playbackHeader("Referer") != null) "" else referer)
    put("user-agent", if (explicit.playbackHeader("User-Agent") != null) "" else userAgent)''',
 '''    put("referrer", if (transport != null || explicit.playbackHeader("Referer") != null) "" else referer)
    put("user-agent", if (transport != null || explicit.playbackHeader("User-Agent") != null) "" else userAgent)''')
change(P+'player/MpvNative.kt','local input Cookie does not contain origin data',
 '        if (explicit.playbackHeader("Cookie") == null && cookieHeader.isNotBlank()) add("Cookie: $cookieHeader")',
 '        if (transport == null && explicit.playbackHeader("Cookie") == null && cookieHeader.isNotBlank()) add("Cookie: $cookieHeader")')
change(P+'data/DesktopRepository.kt','typed final same-client headers after CookieJar and existing guest strips',
 '            val response = chain.proceed(stripDesktopAnonymousHomeFeedCookie(stripDesktopMergedFeedCookie(replacement)))',
 '''            val stripped = stripDesktopAnonymousHomeFeedCookie(stripDesktopMergedFeedCookie(replacement))
            val mediaOrigin = stripped.tag(com.bilipai.desktop.player.cache.DesktopMediaOriginHeaders::class.java)
            val response = chain.proceed(mediaOrigin?.apply(stripped) ?: stripped)''')
change(P+'data/DesktopRepository.kt','same Store effective playback partition getter',
 '    internal fun capturePlaybackAuthorization(expectedEpoch: Long, stillOwned: () -> Boolean) =',
 '''    internal fun capturePlaybackCachePartition(receipt: DesktopPlaybackAuthorizationReceipt, stillOwned: () -> Boolean): String =
        sessions.capturePlaybackCachePartition(receipt, stillOwned)
    internal fun capturePlaybackAuthorization(expectedEpoch: Long, stillOwned: () -> Boolean) =''')
change(P+'data/DesktopSessionStore.kt','digest effective playback credentials inside existing Store only; no extra persisted identity',
 '    internal fun playbackRequestCookies(authorization: DesktopPlaybackAuthorization, url: HttpUrl,',
 '''    internal fun capturePlaybackCachePartition(receipt: DesktopPlaybackAuthorizationReceipt,
        stillOwned: () -> Boolean): String = withPlaybackAuthorizationAdmission(receipt, stillOwned) {
        val account = playbackIdentity.effective
        val identity = buildString {
            append("BiliPai Windows native media byte cache v1\\n")
            listOf(account?.mid?.toString().orEmpty(), account?.sessData.orEmpty(), account?.csrf.orEmpty(),
                account?.accessToken.orEmpty(), account?.refreshToken.orEmpty(), account?.accessTokenPlatform.orEmpty(),
                account?.buvid3.orEmpty(), account?.isVip?.toString().orEmpty()).forEach { value ->
                append(value.length); append(':'); append(value)
            }
        }
        java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    internal fun playbackRequestCookies(authorization: DesktopPlaybackAuthorization, url: HttpUrl,''')
change(P+'player/MpvPlayer.kt','retire replaced byte capability with sourceVersion; IO cancellation asynchronously outside native lock',
 '            val retainedSource = source.immutableSnapshot()\n            if (!preserveSubtitles) {',
 '''            val retainedSource = source.immutableSnapshot()
            val previousTransport = requestedSource?.nativeTransport
            if (!preserveSubtitles) retainedSource.nativeTransport?.lease?.requireUnattached()
            if (!preserveSubtitles || previousTransport !== retainedSource.nativeTransport)
                previousTransport?.retire(sourceVersion, requestedSource?.nativePublication)
            if (!preserveSubtitles) {''')
change(P+'player/MpvPlayer.kt','exact native stop retires its current read capability',
 '            requestedSource = null\n            softwareTarget?.clear()',
 '            requestedSource?.nativeTransport?.retire(sourceVersion, requestedSource?.nativePublication)\n            requestedSource = null\n            softwareTarget?.clear()')
change(P+'player/MpvPlayer.kt','exact native close retires its current read capability',
 '            synchronized(lock) { requestedSource = null; externalSubtitles.clear() }',
 '            synchronized(lock) { requestedSource?.nativeTransport?.retire(sourceVersion, requestedSource?.nativePublication); requestedSource = null; externalSubtitles.clear() }')
change(P+'DesktopPlaybackController.kt','drain compares all original remote fields; exact carrier stays in earlier complete native snapshot check',
 '                snapshot.source.copy(startPositionSeconds = 0.0, startPaused = true, nativePublication = null) == context.source.toNative(0.0, true)',
 '                snapshot.source.copy(startPositionSeconds = 0.0, startPaused = true, nativePublication = null, nativeTransport = null) == context.source.toNative(0.0, true)')
change(P+'ui/DesktopOriginalVideoNativeOwner.kt','same original initial transient guard reused for byte reads until ACK',
 '    override fun onLoadCommandAccepted() { consumed.set(true) }',
 '    internal fun isTransportCurrent(): Boolean = current()\n    override fun onLoadCommandAccepted() { consumed.set(true) }')
change(P+'ui/DesktopOriginalVideoNativeOwner.kt','required same Root source lifetime admission factory',
 '    private val onAccepted: (DesktopOriginalVideoAcceptedPublication) -> Unit,',
 '''    private val onAccepted: (DesktopOriginalVideoAcceptedPublication) -> Unit,
    private val mediaByteAdmission: (DesktopOriginalVideoAcceptedPublication, () -> Boolean) ->
        com.bilipai.desktop.player.cache.DesktopMediaByteAdmission,''')
change(P+'ui/DesktopOriginalVideoNativeOwner.kt','transactional source lease attach/adopt; failed transfer cannot claim native owner',
 '    fun current(): DesktopOriginalVideoAcceptedPublication? = accepted.get()?.takeIf(::owns)',
 '''    private fun bindAcceptedTransport(value: DesktopOriginalVideoAcceptedPublication,
        previous: DesktopOriginalVideoAcceptedPublication?, stillOwned: () -> Boolean) {
        val transport = value.nativeSource.source.nativeTransport ?: return
        try {
            val admission = mediaByteAdmission(value, stillOwned)
            val newPublication = checkNotNull(value.nativeSource.source.nativePublication)
            if (previous?.nativeSource?.source?.nativeTransport === transport) {
                if (!transport.adopt(value.sourceVersion, checkNotNull(previous.nativeSource.source.nativePublication),
                        newPublication, admission)) throw CancellationException("Native byte handoff retired")
            } else transport.attach(value.sourceVersion, newPublication, admission)
        } catch (failure: Throwable) {
            accepted.compareAndSet(value, null)
            transport.retire(value.sourceVersion)
            player.stopIfSourceVersion(value.sourceVersion)
            throw failure
        }
    }

    fun current(): DesktopOriginalVideoAcceptedPublication? = accepted.get()?.takeIf(::owns)''')
change(P+'ui/DesktopOriginalVideoNativeOwner.kt','initial accepts Root source Job; completed resolver is not reader lifetime',
 '                accepted.set(next)\n                inheritedMute.set(null)',
 '                accepted.set(next)\n                bindAcceptedTransport(next, null) { owns(next) && initial.isTransportCurrent() }\n                inheritedMute.set(null)')
change(P+'ui/DesktopOriginalVideoNativeOwner.kt','same-source drain/adopt retains carrier and changes only publication',
 '                    accepted.set(next)\n                    inheritedMute.set(handoff.pluginMute?.let { InheritedMute(next, it) })',
 '''                    accepted.set(next)
                    before.source.nativeTransport?.let { transport ->
                        try {
                            if (!transport.adopt(next.sourceVersion, checkNotNull(before.source.nativePublication),
                                    checkNotNull(next.nativeSource.source.nativePublication), mediaByteAdmission(next) { owns(next) }))
                                throw CancellationException("Native byte handoff retired")
                        } catch (failure: Throwable) {
                            accepted.compareAndSet(next, null); transport.retire(next.sourceVersion)
                            player.stopIfSourceVersion(next.sourceVersion); throw failure
                        }
                    }
                    inheritedMute.set(handoff.pluginMute?.let { InheritedMute(next, it) })''')
change(P+'ui/DesktopOriginalVideoNativeOwner.kt','context-free recovery rebinds current byte frame or attaches new complete source',
 '                        accepted.set(next)\n                        inheritedMute.get()?.takeIf { it.lease === lease }',
 '                        accepted.set(next)\n                        bindAcceptedTransport(next, lease) { owns(next) }\n                        inheritedMute.get()?.takeIf { it.lease === lease }')
change(P+'ui/DesktopOriginalVideoNativeOwner.kt','stale old-owner close cannot retire adopted reader publication',
 '        val retire = { closed.set(true); accepted.set(null); inheritedMute.set(null) }',
 '''        val retire = {
            closed.set(true)
            accepted.getAndSet(null)?.let { value -> value.nativeSource.source.nativeTransport
                ?.retire(value.sourceVersion, value.nativeSource.source.nativePublication) }
            inheritedMute.set(null)
        }''')
build='''
val extractOriginalMediaByteCachePolicy by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-media-byte-cache-policy.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-media-byte-cache-policy").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-media-byte-cache-policy.py", sourceManifest,
        File(repositoryRoot, "app/src/main/java/com/android/purebilibili/core/player/PlaybackMediaCache.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-media-byte-cache-policy"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-media-byte-cache-policy")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalMediaByteCachePolicy) }
'''
change('desktop/build.gradle.kts','sole source producer task, no dependency added',
 'tasks.named("compileKotlin") { dependsOn(extractOriginalVideoAudioFull) }',
 'tasks.named("compileKotlin") { dependsOn(extractOriginalVideoAudioFull) }\n'+build)
for path,result in states.items():
 save(H/'review-only'/path,result)
 # Exact sequential reverse check; historical originals are retained unchanged.
 reverse=result
 for r in reversed([v for v in rows if v['path']==path]):
  assert reverse.count(r['after'])==1,(path,r['label']);reverse=reverse.replace(r['after'],r['before'],1)
 assert reverse==originals[path].decode().replace('\r\n','\n')
 save(H/'diffs'/(Path(path).name+'.diff'),''.join(difflib.unified_diff(originals[path].decode().replace('\r\n','\n').splitlines(True),result.splitlines(True),fromfile=path,tofile=path)))
save(H/'exact-hunks.json',json.dumps(dict(schema=1,baselineCommit=subprocess.run(['git','rev-parse','HEAD'],cwd=C,capture_output=True,text=True,check=True).stdout.strip(),hunks=rows,reverseCheck=True,installation='single-anchor sequential only; review-only full files MUST NOT overwrite product'),ensure_ascii=False,indent=2)+'\n')
registry=json.loads(raw(C/'desktop/upstream-sources.json'))
items=registry['files'] if 'files'in registry else registry['sources']
match=[v for v in items if v['path']=='app/src/main/java/com/android/purebilibili/core/player/PlaybackMediaCache.kt'];assert len(match)==1
save(H/'registry-merge-recipe.json',json.dumps(dict(existing=match[0],requiredFeature='stable-native-media-byte-cache',newMode='policy-extract',preserveAllExistingFeatures=True,identityRowsAdded=0,selected=['resolvePlaybackMediaCacheMaxBytes','shouldUsePlaybackMediaCache(rawUri:String)','buildPlaybackCacheKey(rawUri:String,explicitKey:String?)']),indent=2)+'\n')
print('Prepared',len(rows),'exact hunks across',len(states),'files; all reverse checks PASS.')
