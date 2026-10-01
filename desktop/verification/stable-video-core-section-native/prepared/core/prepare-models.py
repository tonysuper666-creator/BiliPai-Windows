from pathlib import Path
import hashlib,importlib.util,json,os,re,subprocess
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023';PREFIX=chr(92)*2+'?'+chr(92);COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return wide(p).read_text(encoding='utf-8').replace('\r\n','\n')
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf-8',newline='\n')
def digest(t):return hashlib.sha256(t.encode()).hexdigest()
sp=importlib.util.spec_from_file_location('videoStateLexer',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');lex=importlib.util.module_from_spec(sp);sp.loader.exec_module(lex)
identities=[];declarations=[]
def original(path):
 t=read(REPO/path);fixed=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).decode().replace('\r\n','\n');assert t==fixed,path
 identities.append(dict(path=path,sha256LF=digest(t),commit=COMMIT,lines=len(t.splitlines())));return t
def declaration(t,name,body=False):
 mask=lex.masked(t);m=re.search(r'(?m)^(?:(?:internal|private|public|sealed|data|enum)\s+)*(?:class|interface)\s+'+re.escape(name)+r'\b',mask);assert m,name
 start=m.start()
 if body:end=lex.balanced(mask,mask.index('{',m.end()),'{','}')
 else:end=lex.balanced(mask,mask.index('(',m.end()))
 block=t[start:end];declarations.append(dict(name=name,sha256LF=digest(block),firstLine=t[:start].count('\n')+1,lastLine=t[:end].count('\n')+1));return block
path=BASE+'feature/video/viewmodel/VideoPlaybackViewModel.kt';vm=original(path)
blocks=[declaration(vm,'VideoPlaybackUiState',True),declaration(vm,'SponsorSkipUiState'),declaration(vm,'SponsorContributionPhase',True),declaration(vm,'SponsorContributionUiState',True),declaration(vm,'QualitySwitchFailureDialogState')]
header='''package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.audio.*
import com.android.purebilibili.feature.video.playback.policy.PlaybackQualityMode
import com.android.purebilibili.feature.video.subtitle.*
import com.android.purebilibili.feature.video.note.VideoNoteUiState
import com.android.purebilibili.feature.plugin.CdnLineDiagnostic
'''
put(H/'prepared/selected/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoPlaybackUiState.kt',header+'\n\n'.join(blocks)+'\n')
for path in [BASE+'data/model/VideoLoadError.kt',BASE+'feature/video/playback/dash/AdaptiveDashPlaybackSource.kt']:
 put(H/'prepared/direct'/path.removeprefix(BASE),original(path))
# Existing subject schema, Engagement VM/model/actions stay owned by their sole installed producer.
subjectPath=BASE+'feature/video/viewmodel/VideoSubjectSnapshot.kt';subject=original(subjectPath)
engagementPath=BASE+'feature/video/viewmodel/VideoEngagementViewModel.kt';engagement=original(engagementPath)
supplementPath=BASE+'feature/video/viewmodel/VideoSupplementViewModel.kt';supplement=original(supplementPath)
extensions=subject[subject.index('internal fun VideoPlaybackUiState.Success.toSubjectSnapshot'):].rstrip()+'\n'+engagement[engagement.index('internal fun VideoPlaybackUiState.Success.toEngagementSeed'):].rstrip()+'\n'+supplement[supplement.index('internal fun VideoPlaybackUiState.Success.toSupplementSeed'):].rstrip()+'\n'
supplementModels=declaration(supplement,'VideoSupplementSeed')+'\n'+declaration(supplement,'VideoSupplementUiState')
put(H/'prepared/selected/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoSuccessExtensions.kt','''package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.AiSummaryData
import com.android.purebilibili.data.model.response.VideoTag
import com.android.purebilibili.feature.video.note.VideoNoteUiState
'''+supplementModels+'\n'+extensions)
holderPath=BASE+'feature/video/screen/VideoDetailScreenStateHolder.kt';holder=original(holderPath)
put(H/'original-stable/VideoDetailScreenStateHolder.kt',holder)
holders={}
for owner in ['viewModel','engagementViewModel','commentViewModel','composerViewModel','supplementViewModel','playerState','miniPlayerManager','danmakuManager','PlaylistManager']:
 holders[owner]=sorted(set(re.findall(r'\b'+owner+r'(?:::|\.)\s*([A-Za-z_]\w*)',lex.masked(holder))))
androidImports=[line for line in holder.splitlines() if line.startswith('import android.') or any(x in line for x in ['androidx.media3.','androidx.core.view','LocalContext','LocalView','LocalConfiguration']) and line.startswith('import ')]
put(H/'state-holder-closure-inventory.json',json.dumps(dict(original5464Lines=True,identity=identities[-1],retainedFullSource='original-stable/VideoDetailScreenStateHolder.kt',requiredExistingAuthorities=holders,androidPlatformImports=androidImports,missingClosure=['Full VideoPlaybackVM operations/usecases and owned raw repository selected methods; no placeholder VM allowed','Whole original screen/window/orientation/media effect mapping to Root sameWindow/native player','Child sole PlayerSection state/native facade, original Tablet/Cinema/Audio-mode renderer required','Original settings same-global port getters, existing Composer/Comment/Engagement owners and event adapters','Real typed route/shared return/PiP/miniplayer/playlist and source version guards'],excludedReproduction=['existing VideoSubjectSnapshot model','existing Engagement model/VM/actions','existing Comment model/VM/composer','child PlayerSection/Mpv facade/Stage3 Content/AI/Note','existing native Controller, SessionStore/API/client'],implementedYet=False),indent=2)+'\n')
put(H/'source-inventory.json',json.dumps(identities,indent=2)+'\n');put(H/'selected-declarations.json',json.dumps(declarations,indent=2)+'\n')
print(json.dumps(dict(preparedCanonicalModels=True,holderSourceLines=len(holder.splitlines()),ownerMethodCounts={k:len(v) for k,v in holders.items()},fullHolderCompiled=False)))
