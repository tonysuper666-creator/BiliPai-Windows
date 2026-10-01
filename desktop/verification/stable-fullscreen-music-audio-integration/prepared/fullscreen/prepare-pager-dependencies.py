from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';BASE='app/src/main/java/com/android/purebilibili/';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n')
def sha(b):return hashlib.sha256(b.encode()if isinstance(b,str)else b).hexdigest()
def src(rel):
 b=subprocess.check_output(['git','show',COMMIT+':'+BASE+rel],cwd=REPO).replace(b'\r\n',b'\n');write(P/'original-stable'/BASE/rel,b);return b.decode()
def module(name,p):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
controls=module('pager_source_tokens',MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py')
def method(t,n):
 tokens=controls.parser.kotlin_tokens(t);return controls.member_closure(t,tokens,[n])[0] if False else next(d['text']for d in controls.parser.declarations(t)if d.get('name')==n)
if __name__=='__main__':
 actual=set(zipfile.ZipFile(MAIN/'desktop/.local/stable-product-snapshot-55/main-kotlin.jar').namelist());rows=[]
 for n in ['PortraitAudioPlaybackController','PortraitCollectionPolicy','PortraitCommentPresentationPolicy','PortraitCommentSheet','PortraitDetailSheet','PortraitDetailVideoListPolicy','PortraitMainPlayerSyncPolicy','PortraitPlaybackAdvancePolicy','PortraitProgressSyncPolicy','PortraitSharePolicy','PortraitVideoLoadPolicy']:
  rel='feature/video/ui/pager/'+n+'.kt';t=src(rel);out=t
  if 'com/android/purebilibili/feature/video/ui/pager/'+n+'Kt.class'in actual:
   rows.append(dict(path=BASE+rel,sha256LF=sha(t),mode='reference',reason='already actual55'));continue
  # Platform/native sections are adapted by prepare-pager.py; preserve originals here.
  if n not in ['PortraitAudioPlaybackController','PortraitVideoLoadPolicy']:
   out=out.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
   out=out.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext')
   if n=='PortraitCommentSheet':out=out.replace('fun PortraitCommentSheet(','internal fun PortraitCommentSheet(')
   if n=='PortraitDetailSheet':
    out=out.replace('val blockedUpRepository = remember { com.android.purebilibili.data.repository.BlockedUpRepository(context) }','val platform = com.bilipai.desktop.ui.LocalDesktopOriginalPortraitPlatform.current\n                            val blockedUpRepository = platform.blockedUps')
    out=out.replace('android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()','platform.showFeedback(result.message)')
   write(P/'prepared/generated/com/android/purebilibili'/rel,out)
  rows.append(dict(path=BASE+rel,sha256LF=sha(t),outputSHA256LF=sha(out)if n not in ['PortraitAudioPlaybackController','PortraitVideoLoadPolicy']else None,mode='selected'if out!=t or n in ['PortraitAudioPlaybackController','PortraitVideoLoadPolicy']else'direct',wholeFile=True))
 for rel in ['feature/video/viewmodel/VideoPlaybackViewModel.kt','core/store/SettingsManager.kt','core/store/player/PlayerSettingsStore.kt']:
  t=src(rel);rows.append(dict(path=BASE+rel,sha256LF=sha(t),mode='source-reference',wholeFile=False))
 write(P/'pager-dependency-source-inventory.json',json.dumps(rows,indent=2)+'\n')
 print(len(rows),'pinned dependency identities')
