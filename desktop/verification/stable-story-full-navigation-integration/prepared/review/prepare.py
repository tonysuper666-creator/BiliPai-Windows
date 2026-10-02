from pathlib import Path
import ast, hashlib, importlib.util, json, os, sys, textwrap
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023';B=P.parent/'stable-original-story-pager-root-parity'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def read(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf8',newline='\n')
H=[];families=[]
def change(rel,base,edits):
 t=base
 for name,before,after in edits:
  assert t.count(before)==1,(rel,name,t.count(before));t=t.replace(before,after)
  H.append(dict(path=rel,name=name,before=before,after=after,beforeSha256LF=sha(before),afterSha256LF=sha(after)))
 write(P/'prepared/existing'/rel,t)
 families.append(dict(path=rel,beforeSha256LF=sha(base),afterSha256LF=sha(t)))
 return t

vmrel='desktop/build/generated/original-video-full-owner/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
vm=read(B/'prepared/existing'/vmrel)
a=vm.index('    internal fun adoptDesktopPortraitLoad(');z=vm.index('    override fun close()',a)
old=vm[a:z]
new=old.replace('            //  CDN 线路\n','').replace('            // [New] Codec/Audio info\n','').replace('            // [New] AI Audio\n','')
new=new.replace('        // Original non-blocking operations stay outside the short admission.\n        // Their original requestToken/subject guards reject a subsequent swipe.\n        if (!isAcceptedCurrent()) return false\n        environment.mini.syncCurrentVideoInfo(readyState)', '''        // Only synchronous in-memory/enqueue effects enter this captured gate.
        // Mini enqueues a typed Root event; Playlist enqueues LAZY IO; analytics
        // enqueues on the existing writer. No file/native wait happens here.
        fun publishCurrent(action: () -> Unit): Boolean {
            var accepted = false
            return admit {
                if (isAcceptedCurrent() && shouldApplyVideoLoadResult(currentLoadRequestToken,
                        requestToken, result.info.bvid, currentBvid)) {
                    action(); accepted = true
                }
            } && accepted
        }
        if (!publishCurrent { environment.mini.syncCurrentVideoInfo(readyState) }) return false''')
new=new.replace('        updatePlaylist(result.info, result.related)\n        environment.analytics.logVideoPlay(result.info.bvid, result.info.title, result.info.owner.name)', '''        if (!publishCurrent { updatePlaylist(result.info, result.related) }) return false
        if (!publishCurrent {
                environment.analytics.logVideoPlay(result.info.bvid, result.info.title, result.info.owner.name)
            }) return false''')
vm=vm.replace(old,new);write(P/'reference'/vmrel,vm)
prodrel='desktop/tools/extract-upstream-video-full-owner.py'
prod=read(B/'prepared/existing'/prodrel)
tree=ast.parse(prod);fn=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='story_portrait_adoption_delta')
statement=next(n for n in ast.walk(fn) if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='after' for t in n.targets))
literal=ast.get_source_segment(prod,statement.value);value=ast.literal_eval(statement.value)
assert value.count(old)==1
change(prodrel,prod,[('final-captured-effect-admission',literal,repr(value.replace(old,new)))])

storyrel='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalStoryRoot.kt'
story=read(B/'prepared/manual'/storyrel)
story=change(storyrel,story,[('exact-active-route-dialog-owner',
'    val feeds = platforms.storyFeeds\n',
'''    val routes = commands as DesktopOriginalRootRouteAssembly
    fun currentRoute() = active && routes.owns() && routes.currentKey == key &&
        shell.slot.currentAssembly() === assembly && assembly.owns()
    val feeds = platforms.storyFeeds
'''),('captured-dialog-final-admission',
'''                    CoinDialog(visible=engagement.coinDialogVisible, currentCoinCount=engagement.coinCount,
                        userBalance=engagement.userCoinBalance,
                        onDismiss={ assembly.domains.engagement.setCoinDialogVisible(false) },
                        onConfirm=assembly.domains.engagement::doCoin)
                    val favoriteVisible by assembly.playback.favoriteFolderDialogVisible.collectAsState()
                    VideoDetailFavoriteFolderOverlayAdapter(favoriteVisible, assembly.playback)
                    VideoDetailFollowGroupDialog(assembly.playback)''',
'''                    val favoriteVisible by assembly.playback.favoriteFolderDialogVisible.collectAsState()
                    val dialogSource = assembly.native.current()
                    fun admitDialog(action: () -> Unit): Boolean {
                        val expected = dialogSource ?: return false
                        var applied = false
                        return assembly.environment.commit {
                            if (currentRoute() && assembly.native.isCurrent(expected)) {
                                action(); applied = true
                            }
                        } && applied
                    }
                    if (currentRoute() && dialogSource != null) {
                        CoinDialog(visible=engagement.coinDialogVisible, currentCoinCount=engagement.coinCount,
                            userBalance=engagement.userCoinBalance,
                            onDismiss={ admitDialog { assembly.domains.engagement.setCoinDialogVisible(false) } },
                            onConfirm={ count,alsoLike -> admitDialog { assembly.domains.engagement.doCoin(count,alsoLike) }; Unit })
                        VideoDetailFavoriteFolderOverlayAdapter(favoriteVisible, assembly.playback, ::admitDialog)
                        VideoDetailFollowGroupDialog(assembly.playback, ::admitDialog)
                    }''')])

holder='desktop/tools/extract-upstream-video-detail-holder.py';holderbase=read(C/holder)
root=C/'desktop/build/generated/original-video-detail-holder-full'
folder='com/android/purebilibili/feature/video/screen/VideoDetailFavoriteFolderOverlayAdapter.kt'
overlay='com/android/purebilibili/feature/video/screen/VideoDetailOverlayHost.kt'
edits={folder:[('    viewModel: VideoPlaybackViewModel,\n','    viewModel: VideoPlaybackViewModel,\n    admitAction: ((() -> Unit) -> Boolean) = { action -> action(); true },\n'),
('onFolderToggle = viewModel::toggleFavoriteFolderSelection','onFolderToggle = { id -> admitAction { viewModel.toggleFavoriteFolderSelection(id) }; Unit }'),
('onSaveClick = viewModel::saveFavoriteFolderSelection','onSaveClick = { admitAction { viewModel.saveFavoriteFolderSelection() }; Unit }'),
('onDismissRequest = viewModel::dismissFavoriteFolderDialog','onDismissRequest = { admitAction { viewModel.dismissFavoriteFolderDialog() }; Unit }'),
('onCreateFolder = viewModel::createFavoriteFolder','onCreateFolder = { title, intro, privacy -> admitAction { viewModel.createFavoriteFolder(title, intro, privacy) }; Unit }')],
overlay:[('    viewModel: VideoPlaybackViewModel\n) {\n    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current\n    val followGroupDialogVisible',
'    viewModel: VideoPlaybackViewModel,\n    admitAction: ((() -> Unit) -> Boolean) = { action -> action(); true },\n) {\n    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current\n    val followGroupDialogVisible'),
('if (!isSavingFollowGroups) viewModel.dismissFollowGroupDialog()','if (!isSavingFollowGroups) admitAction { viewModel.dismissFollowGroupDialog() }'),
('.clickable { viewModel.toggleFollowGroupSelection(tag.tagid) }','.clickable { admitAction { viewModel.toggleFollowGroupSelection(tag.tagid) } }'),
('onCheckedChange = { viewModel.toggleFollowGroupSelection(tag.tagid) }','onCheckedChange = { admitAction { viewModel.toggleFollowGroupSelection(tag.tagid) } }'),
('onClick = { viewModel.saveFollowGroupSelection() }','onClick = { admitAction { viewModel.saveFollowGroupSelection() } }'),
('onClick = { viewModel.dismissFollowGroupDialog() }','onClick = { admitAction { viewModel.dismissFollowGroupDialog() } }')]}
helper='\ndef story_dialog_admission_delta(path,text):\n'
for path,changes in edits.items():
 text=read(root/path);base=text
 helper+=' if path == '+repr(path)+':\n'
 for before,after in changes:
  assert text.count(before)==1,(path,before,text.count(before));text=text.replace(before,after)
  helper+='  before='+repr(before)+'\n  after='+repr(after)+'\n  assert text.count(before)==1\n  text=text.replace(before,after)\n'
 write(P/'reference'/path,text)
 for before,after in reversed(changes):text=text.replace(after,before)
 assert text==base
helper+=' return text\n\n'
change(holder,holderbase,[('existing-sole-dialog-admission-hook','def generate(repo, output, standalone=False):',helper+'def generate(repo, output, standalone=False):'),('emit-guarded-original-dialogs',"        text=holder_subtitle_registration_delta(s['output'],text)\n","        text=holder_subtitle_registration_delta(s['output'],text)\n        text=story_dialog_admission_delta(s['output'],text)\n")])

# Exact initializer shape: inverse both the pre-existing Windows adaptation and
# the seven captured CDN expression substitutions back to the pinned original.
original=read(C/'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt')
oa=original.index('                        val readyState = VideoPlaybackUiState.Success(');ob=original.index('                        _uiState.value = readyState',oa)
rawinit=textwrap.dedent(original[oa:ob]);write(P/'initializer-original.kt.txt',rawinit)
baseinit=textwrap.dedent(old[old.index('        val readyState = VideoPlaybackUiState.Success('):old.index('\n        var applied = false')]).strip()+'\n'
# Original only quality default function gained a canonical Windows helper name
# in the actual full-owner source. Read actual source selection recipe mappings.
actual=read(C/vmrel);aa=actual.index('                        val readyState = VideoPlaybackUiState.Success(');ab=actual.index('                        _uiState.value = readyState',aa)
selected=textwrap.dedent(actual[aa:ab]);mapping=[('cdnSelection.playUrl','result.playUrl'),('cdnSelection.audioUrl','result.audioUrl'),('cdnSelection.adaptiveDashSource','result.adaptiveDashSource'),('cdnSelection.allVideoUrls','allVideoUrls'),('cdnSelection.allAudioUrls','allAudioUrls'),('cdnSelection.candidateSources','candidateSources'),('cdnSelection.lineDiagnostics','lineDiagnostics')]
adapted=selected
for before,after in mapping:adapted=adapted.replace(before,after)
assert adapted.strip()==baseinit.strip()
inverse=baseinit
for before,after in reversed(mapping):inverse=inverse.replace('= '+after+',','= '+before+',')
assert inverse.strip()==selected.strip()
write(P/'initializer-receipt.json',json.dumps(dict(passed=True,originalPath='app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt',originalFileSha256LF=sha(original),originalSelectionSha256LF=sha(rawinit),actualCanonicalSelectionSha256LF=sha(selected),sameOriginalConstructorFields=rawinit==selected,expressionMappings=mapping,newHelperCommentsRemoved=True,successSeeded=False),ensure_ascii=False,indent=2)+'\n')

replays=[]
for rel,changed in [(prodrel,{'com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'}),(holder,{folder,overlay})]:
 spec=importlib.util.spec_from_file_location('p'+str(len(replays)),wide(P/'prepared/existing'/rel));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
 out=wide(P/'replay'/str(len(replays)));mod.generate(C,out)
 for f in out.rglob('*.kt'):
  path=str(f.relative_to(out)).replace('\\','/')
  if path in changed:
   expected=vm if path.endswith('/VideoPlaybackViewModel.kt') else read(P/'reference'/path)
   assert read(f)==expected,path
  else:
   actualroot=C/('desktop/build/generated/original-video-full-owner' if rel==prodrel else 'desktop/build/generated/original-video-detail-holder-full')
   assert read(f)==read(actualroot/path),path
 replays.append(dict(producer=rel,changed=sorted(changed),otherOutputsByteUnchanged=True))
write(P/'replay.json',json.dumps(dict(passed=True,producers=replays,originalDialogInverse=True),indent=2)+'\n')
write(P/'exact-hunks.json',json.dumps(H,ensure_ascii=False,indent=2)+'\n')
write(P/'families.json',json.dumps(families,indent=2)+'\n')
print('Prepared',len(H),'exact edits; 3 production families; 0 Candidate writes')
