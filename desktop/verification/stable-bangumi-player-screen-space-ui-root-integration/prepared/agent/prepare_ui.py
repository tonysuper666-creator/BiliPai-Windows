from pathlib import Path
import difflib,hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023';UP='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf8',newline='\n')
def change(body,before,after,count=1):
 assert body.count(before)==count,(before,body.count(before));return body.replace(before,after)
recipes=[]
for name in ['BangumiPlayerContent','BangumiPlayerOverlayHost','BangumiCollapsedPlayerBar']:
 path='app/src/main/java/com/android/purebilibili/feature/bangumi/ui/player/'+name+'.kt'
 raw=subprocess.check_output(['git','-C',str(C),'show',UP+':'+path]).decode('utf8').replace('\r\n','\n');body=raw;boundaries=[]
 if name=='BangumiPlayerContent':
  body=change(body,'fun BangumiPlayerContent(', 'internal fun BangumiPlayerContent(')
  boundaries=['Internal visibility for actual original desktop comment VM ABI; body unchanged']
 if name=='BangumiPlayerOverlayHost':
  body=change(body,'import androidx.compose.ui.platform.LocalContext\n','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext\n')
  body=change(body,'import androidx.media3.exoplayer.ExoPlayer\n','import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer\n')
  body=change(body,'import com.android.purebilibili.core.util.ShareUtils\n','import com.bilipai.desktop.ui.LocalDesktopOriginalBangumiPlayerShare\n')
  body=change(body,'    val context = LocalContext.current\n','    val context = LocalContext.current\n    val shareUtils = LocalDesktopOriginalBangumiPlayerShare.current\n')
  body=change(body,'ShareUtils.shareBangumi(', 'shareUtils.shareBangumi(')
  boundaries=['LocalContext/settings borrow same Root','Media3 API view same native Section','ShareUtils delegates required same Root share owner']
 edits=[];a=raw.splitlines(keepends=True);b=body.splitlines(keepends=True)
 for tag,i,j,k,l in difflib.SequenceMatcher(None,a,b,autojunk=False).get_opcodes():
  if tag=='equal':continue
  before=''.join(a[i:j]);edits.append(dict(startLine=i,endLineExclusive=j,before=before,after=''.join(b[k:l]),beforeSha256LF=sha(before)))
 output='com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginal'+name+'.kt'
 write(P/'generated'/output,'// Complete original v0.2.3 UI body; explicit desktop platform boundaries only.\n'+body)
 recipes.append(dict(originalPath=path,originalSha256LF=sha(raw),output=output,adaptedSha256LF=sha(body),edits=edits,platformBoundaries=boundaries,fullOriginalBody=True,actualRootConsumer=False))
write(P/'ui-recipes.json',json.dumps(dict(upstreamCommit=UP,recipes=recipes),ensure_ascii=False,indent=2)+'\n')
print('Prepared 3 complete original UI bodies; actual Root/Components/Screen remain pending')
