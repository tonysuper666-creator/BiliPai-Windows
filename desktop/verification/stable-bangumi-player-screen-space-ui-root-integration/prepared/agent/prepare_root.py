from pathlib import Path
import hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023';BASE='cd8317d54fff02c2d920e295397aef502333f69b'
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(s,encoding='utf8',newline='\n')
rows=[]
def prepare(path,changes):
 raw=subprocess.check_output(['git','-C',str(C),'show',BASE+':'+path]).decode('utf8').replace('\r\n','\n');body=raw
 for before,after in changes:
  assert body.count(before)==1,(path,before,body.count(before));body=body.replace(before,after)
  rows.append(dict(path=path,before=before,after=after,beforeCount=1,baseSha256LF=sha(raw)))
 write(P/'prepared'/path,body)
 for r in rows:
  if r['path']==path:r['preparedSha256LF']=sha(body)
prepare('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoHolderRoute.kt',[
 ('    val subtitleMode: DesktopOriginalSubtitleModeBinding\n','    val subtitleMode: DesktopOriginalSubtitleModeBinding\n    val bangumiPlayer: DesktopOriginalBangumiPlayerRootOwner\n')])
prepare('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRootWindowPlatforms.kt',[
 ('    override val subtitleMode: DesktopOriginalSubtitleModeBinding,\n','    override val subtitleMode: DesktopOriginalSubtitleModeBinding,\n    override val bangumiPlayer: DesktopOriginalBangumiPlayerRootOwner,\n'),
 ('        storyFeeds.close()\n','        bangumiPlayer.close()\n        storyFeeds.close()\n')])
prepare('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRootAssembler.kt',[
 ('''        return remember(owner, section, holder, tablet, music, portrait, audio, fullscreen, subtitle) {
            DesktopOriginalVideoRootWindowPlatformsImpl(owner, section, holder, fullscreen, tablet, audio, music,
                content, portrait, subtitle, windows, effect.displays, resources.captureProtection::refresh)
        }
''','''        val textShare = checkNotNull(LocalDesktopTextShareBindings.current) {
            "Original PGC player requires the actual Root text share binding"
        }
        val fullscreenState = remember(owner, effect) {
            snapshotFlow { resources.isFullscreen() }.stateIn(gate.scope, SharingStarted.Eagerly, resources.isFullscreen())
        }
        val bangumiPlayer = remember(owner, section, holder, portrait, windows, textShare, fullscreenState) {
            DesktopOriginalBangumiPlayerRootOwner(owner, shell, window, factory, resources,
                section, holder, portrait, windows, textShare, fullscreenState)
        }
        return remember(owner, section, holder, tablet, music, portrait, audio, fullscreen, subtitle, bangumiPlayer) {
            DesktopOriginalVideoRootWindowPlatformsImpl(owner, section, holder, fullscreen, tablet, audio, music,
                content, portrait, subtitle, bangumiPlayer, windows, effect.displays, resources.captureProtection::refresh)
        }
''')])
prepare('desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',[
 ('''                            entryKey is BiliPaiNavKey.Bangumi || entryKey is BiliPaiNavKey.BangumiDetail || entryKey is BiliPaiNavKey.BangumiReview ->
''','''                            entryKey is BiliPaiNavKey.BangumiPlayer ->
                                DesktopOriginalBangumiPlayerPhysicalLeaf(entryKey, ordinaryVideo, messageRoutes, active,
                                    ::openVideoHonorLink, pendingOwner = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            ordinaryVideoResourceError?.let { Text(it) } ?: CircularProgressIndicator()
                                        }
                                    })
                            entryKey is BiliPaiNavKey.Bangumi || entryKey is BiliPaiNavKey.BangumiDetail || entryKey is BiliPaiNavKey.BangumiReview ->
''')])
prepare('desktop/build.gradle.kts',[
 ('''// Complete original PGC VM/Base/policies and playurl have one producer. This
// installs shared native-owner plumbing; the original Player Screen is pending.
''','''// Complete original PGC VM/Base/policies and playurl have one producer;
// the complete physical Player UI is produced separately below.
'''),
 ('''val extractOriginalSubscriptionPage by tasks.registering(Exec::class) {
''','''val extractOriginalBangumiPlayerUi by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalBangumiPlayer, extractUpstreamMedia)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-bangumi-player-ui.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-bangumi-player-ui").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-bangumi-player-ui.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "original-bangumi-player-ui" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-bangumi-player-ui"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-bangumi-player-ui")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalBangumiPlayerUi) }

val extractOriginalSubscriptionPage by tasks.registering(Exec::class) {
''')])
write(P/'root-exact-hunks.json',json.dumps(dict(base=BASE,hunks=rows),ensure_ascii=False,indent=2)+'\n')
print('Prepared',len(rows),'precise hunks across',len(set(r['path']for r in rows)),'Root/Gradle families; Candidate not written')
