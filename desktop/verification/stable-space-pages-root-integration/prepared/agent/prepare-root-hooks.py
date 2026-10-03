from pathlib import Path
import hashlib,json,subprocess,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent
C=P.parents[2].parent/'BiliPai-v023'
HEAD='5f5f29a00609ec215b06553b59b3d3669ef0335b'
def sha(b):return hashlib.sha256(b).hexdigest()
assert subprocess.check_output(['git','-C',str(C),'rev-parse','HEAD']).decode().strip()==HEAD
records=[]
def target(path,edits):
    raw=(C/path).read_bytes();text=raw.decode('utf8').replace('\r\n','\n')
    expected=subprocess.check_output(['git','-C',str(C),'show',HEAD+':'+path]).decode('utf8').replace('\r\n','\n')
    assert text==expected,path
    steps=[];new=text
    for before,after in edits:
        assert new.count(before)==1,(path,before[:120],new.count(before))
        steps.append(dict(before=before,after=after,offsetAfter=new.index(before)))
        new=new.replace(before,after,1)
    reverse=new
    for step in reversed(steps):
        at=step['offsetAfter'];after=step['after']
        assert reverse[at:at+len(after)]==after
        reverse=reverse[:at]+step['before']+reverse[at+len(after):]
    assert reverse==text
    desired=new.replace('\n','\r\n').encode() if b'\r\n' in raw else new.encode()
    out=P/'root-hooks/compiler-targets'/path;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(desired)
    records.append(dict(target=path,baseRawSha256=sha(raw),baseLfSha256=sha(text.encode()),
        desiredRawSha256=sha(desired),desiredLfSha256=sha(new.encode()),compilerOverlay=str(out.relative_to(P)),hunks=steps))

target('desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopCommunityRepository.kt',[
 ('    private val client = repository.httpClient.newBuilder().retryOnConnectionFailure(false).build()',
  '    private val client = repository.httpClient.newBuilder().retryOnConnectionFailure(false).build()\n    internal val originalSpaceTransport: okhttp3.OkHttpClient get() = client')])
target('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPlaybackProgressStorage.kt',[
 ('    suspend fun closeAndJoin(): Boolean = writer.closeAndJoin()',
  '    internal fun cachedPositionForSpace(bvid: String, owned: () -> Boolean): Long {\n        if (!owned()) throw CancellationException("Space progress entry retired")\n        return manager.getCachedPosition(bvid)\n    }\n    suspend fun closeAndJoin(): Boolean = writer.closeAndJoin()')])
mount='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopReadyOriginalRootMount.kt'
target(mount,[
 ('    val nowPlayingPositionMs: (DesktopHomeRetainedRoot, DesktopOriginalNowPlayingSnapshot) -> Long?,',
  '    val nowPlayingPositionMs: (DesktopHomeRetainedRoot, DesktopOriginalNowPlayingSnapshot) -> Long?,\n    val originalSpacePlaylist: DesktopOriginalVideoPlaylistBinding,\n    val originalSpaceCachedPosition: (String) -> Long,'),
 ('    val messagePages = AtomicReference<DesktopOriginalMessagePagesRoot?>()',
  '    val messagePages = AtomicReference<DesktopOriginalMessagePagesRoot?>()\n    val spacePages = AtomicReference<DesktopOriginalSpacePagesRoot?>()'),
 ('        messagePages.getAndSet(null)?.closeAndJoin()\n        personalLists.getAndSet(null)?.closeAndJoin()',
  '        messagePages.getAndSet(null)?.closeAndJoin()\n        spacePages.getAndSet(null)?.closeAndJoin()\n        personalLists.getAndSet(null)?.closeAndJoin()'),
 ('DesktopHomeSettingsPort, DesktopOriginalMessagePagesRoot) -> Unit,',
  'DesktopHomeSettingsPort, DesktopOriginalMessagePagesRoot, DesktopOriginalSpacePagesRoot) -> Unit,'),
 ('handle.route.getAndSet(null)?.close(); handle.messagePages.get()?.close(); handle.retainer.retire()',
  'handle.route.getAndSet(null)?.close(); handle.messagePages.get()?.close(); handle.spacePages.get()?.close(); handle.retainer.retire()'),
 ('            handle.messagePages.getAndSet(null)?.closeAndJoin()\n            physicalStack.clear();',
  '            handle.messagePages.getAndSet(null)?.closeAndJoin()\n            handle.spacePages.getAndSet(null)?.closeAndJoin()\n            physicalStack.clear();'),
 ('        val personalLists = remember(root, services.library) {',
  '''        val spacePages = remember(services.repository, services.community, routes) {
            DesktopOriginalSpacePagesRoot(services.repository, services.community, routes,
                services.community.originalSpaceTransport)
        }
        SideEffect { if (handle.isActive()) handle.spacePages.set(spacePages) else spacePages.close() }
        LaunchedEffect(spacePages, routes) { snapshotFlow { physicalStack.toList() }.collect { spacePages.prune() } }
        DisposableEffect(spacePages) { onDispose {
            // Leave the last owner in the handle until its existing account/
            // restore/shutdown drain has awaited it. Retirement cancels now.
            spacePages.close()
        } }
        val personalLists = remember(root, services.library) {'''),
 ('leaf(key, commands, active, hosted, personalLists, root.environment.settings, messagePages)',
  'leaf(key, commands, active, hosted, personalLists, root.environment.settings, messagePages, spacePages)')])
shell='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
olduser='''                            section == DesktopSection.USER -> {
                                val progress = history.associate { card -> card.bvid to SpaceWatchProgress(card.bvid, card.preferredCid, card.title,
                                    (card.progressSeconds ?: 0.0).toLong(), card.cover) }
                                DesktopCompleteSpaceScreen(userId, repository, social, community, space, spaceContributions,
                                    onVideo = ::openVideoCard, onUser = ::openUser, onArticle = ::openArticle, onLogin = {loginDialog = true},
                                    onLive = ::openLive, onBangumi = ::openBangumi, onCollection = ::openCollection,
                                    onWatchAll = ::watchAllSpaceVideos, onListenAll = ::listenAllSpaceVideos, onAudio = ::openMusic,
                                    onCourse = { id -> openBangumi(id, true) }, onLikedVideos = ::openLikedVideos,
                                    onFollowers = ::openSpaceFollowers, onFollowing = ::openSpaceFollowings,
                                    onChargeRank = ::openSpaceChargeRank, onCaptains = ::openSpaceCaptains,
                                    onDynamic = ::openDynamic, onMessage = ::openChat, onBack = { commands.back() },
                                    watchProgress = progress,
                                    locateBvid = (entryKey as? BiliPaiNavKey.Space)?.targetBvid?.takeIf{it.isNotBlank()} ?: playing.details?.takeIf { it.authorMid == userId }?.bvid ?: history.firstOrNull()?.bvid, onTopic = ::openTopic, onTopicKeyword = ::openTopicKeyword, onImagePreviewFeedback = { error = it })
                            }
'''
actual=(C/shell).read_text(encoding='utf8')
start=actual.index('                            section == DesktopSection.USER -> {')
end=actual.index('                            entryKey is BiliPaiNavKey.ArticleDetail ->',start)
olduser=actual[start:end]
assert 'DesktopCompleteSpaceScreen' in olduser and olduser.rstrip().endswith('}')
target(shell,[
 ('{ root, expected -> originalNowPlayingFor(root,listen).positionMs(expected) })',
  '''{ root, expected -> originalNowPlayingFor(root,listen).positionMs(expected) },
                    ordinaryVideo.playlist,
                    { bvid -> ordinaryVideoResources?.progress?.cachedPositionForSpace(bvid) { !isClosing() } ?: 0L })'''),
 ('entryKey,commands,active,pagerHosted,personalLists,originalHomePreferences,messagePages ->',
  'entryKey,commands,active,pagerHosted,personalLists,originalHomePreferences,messagePages,spacePages ->'),
 ('                        when {\n                            entryKey is BiliPaiNavKey.Bangumi',
  '''                        when {
                            entryKey is BiliPaiNavKey.Space || entryKey is BiliPaiNavKey.UpowerRank || entryKey is BiliPaiNavKey.MemberGuard ->
                                DesktopDetailWindow { DesktopOriginalSpacePageRootHost(entryKey, spacePages,
                                    services.originalSpacePlaylist, services.originalSpaceCachedPosition,
                                    { title, text, owned -> requestDesktopTextShare(services.textShare,
                                        spacePages.entry(entryKey).environment.scope, title, text,
                                        { owned() && active && messageRoutes.currentKey == entryKey && !isClosing() }, services.feedback) },
                                    desktopDetailRenderEffectsSupported(), active) }
                            entryKey is BiliPaiNavKey.Bangumi'''),
 (olduser,'')])

# Single producer, reusing existing complete state/model/selected policy owners.
gradle='''
// Full original retained Space page, supporter leaves and business ViewModels.
// Existing SpaceUiState/SubTab/selected policies, dynamic cards and gallery stay sole-owned.
val extractOriginalSpacePages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSpaceOverview, extractUpstreamSpaceContributions,
        extractOriginalVideoTabletFull, extractOriginalFavorites)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-space-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--out", layout.buildDirectory.dir("generated/original-space-pages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-space-pages.py", "tools/extract-upstream-tablet-owner-space.py", "tools/sync-upstream.py", sourceManifest)
    inputs.files(layout.buildDirectory.file("generated/space-overview/com/android/purebilibili/feature/space/DesktopOriginalSpaceOverview.kt"),
        layout.buildDirectory.file("generated/space-contributions/com/android/purebilibili/feature/space/DesktopUpstreamSpaceContributionDeclarations.kt"))
    inputs.files(sources.filter { "stable-original-space-pages-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-space-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-space-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSpacePages) }
'''
assert 'val extractUpstreamSpaceContributions' in (C/'desktop/build.gradle.kts').read_text(encoding='utf8')
(P/'root-hooks/gradle-fragment.kts').write_text(gradle,encoding='utf8')
(P/'root-hooks/exact-hunks.json').write_text(json.dumps(dict(candidateHead=HEAD,targets=records),ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(dict(head=HEAD,targetCount=len(records),hunks=sum(len(r['hunks'])for r in records))))
