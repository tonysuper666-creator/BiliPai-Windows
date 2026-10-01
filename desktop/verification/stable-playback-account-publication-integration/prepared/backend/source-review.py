from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,re,textwrap
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
parser=load(REPO/'desktop/tools/sync-upstream.py','playback_original_parser');media=load(REPO/'desktop/tools/extract-upstream-media.py','playback_original_selector')
def selected_function(s,name):
 matches=list(re.finditer(r'(?m)^([ \t]*)(?:(?:internal|private|suspend|override|public)\s+)*fun\s+(?:[\w.]+\.)?'+re.escape(name)+r'\s*\(',s))
 assert len(matches)==1,(name,len(matches))
 m=matches[0];ts=parser.kotlin_tokens(s);i=next(i for i,t in enumerate(ts) if t[1]>=m.start())
 while ts[i][0]!='(':i+=1
 depth=1
 while depth:
  i+=1;depth+=(ts[i][0]=='(')-(ts[i][0]==')')
 while ts[i][0] not in {'=','{'}:i+=1
 if ts[i][0]=='{':
  depth=1
  while depth:
   i+=1;depth+=(ts[i][0]=='{')-(ts[i][0]=='}')
  end=ts[i][2]
 else:
  i+=1;levels={'(':0,'[':0,'{':0};close={')':'(',']':'[','}':'{'};end=ts[i][2]
  while i<len(ts):
   value,begin,finish=ts[i];line=s.rfind('\n',0,begin)+1;prefix=s[line:begin]
   if begin>end and not any(levels.values()) and '\n' in s[end:begin] and prefix.strip()=='' and len(prefix)<=len(m.group(1)):
    break
   if value in levels:levels[value]+=1
   elif value in close:
    if levels[close[value]]==0:break
    levels[close[value]]-=1
   end=finish;i+=1
 return textwrap.dedent(s[m.start():end]),m.start()
sources={};methods=[]
families={'core/store/AccountSessionStore.kt':['getAccounts','readSnapshot','getPlaybackAccountMid','getPlaybackAccount','setPlaybackAccountMid','removeAccount'],
 'core/network/ApiClient.kt':['playbackAccount','playbackApi','playbackBangumiApi','ensurePlaybackAccountClients'],
 'data/repository/VideoRepository.kt':['playbackAccount','isUsingDedicatedPlaybackAccount','hasPlaybackSessionCookie','playbackAccessToken','playbackAccessTokenPlatform','isPlaybackVip','getPlaybackNavInfo','getPlayUrlData','fetchDashWithFallback','fetchPlayUrlWithWbiInternal','fetchPlayUrlWithAccessToken'],
 'data/repository/BangumiRepository.kt':['getBangumiPlayUrl']}
for short,names in families.items():
 p=BASE+short;s=subprocess.check_output(['git','show',COMMIT+':'+p],cwd=REPO).decode().replace('\r\n','\n');assert read(REPO/p)==s
 sources[p]=dict(sha256LF=sha(s),physicalLines=len(s.splitlines()))
 for name in names:
  body,begin=selected_function(s,name)
  methods.append(dict(path=p,name=name,bodySha256LF=sha(body),bodyLines=len(body.splitlines()),anchor=s[:begin].count('\n')+1 if begin is not None else None))
targets=['desktop/src/main/kotlin/com/bilipai/desktop/data/'+n+'.kt' for n in ['DesktopSessionStore','DesktopRepository','DesktopMediaRepository','DesktopPlaybackCache','DesktopModels']]
result=dict(scope='source-only-preparation',stableCommit=COMMIT,originalSources=sources,originalMethods=methods,candidateBaseFiles=[dict(path=p,sha256LF=sha(read(REPO/p)),sha256Bytes=hashlib.sha256(safe(REPO/p).read_bytes()).hexdigest()) for p in targets],
 findings=['Current encrypted SavedSession has no original playback_mid selection','Native video URL and PUGV/PGC use main-account services','URL cache key has primary epoch only and requires independent playback authorization revision','Return-to-native publication requires authorization receipt; pre/post HTTP checks alone leave a late-selection window'],
 required=['Same encrypted DesktopSessionStore field/get/set/remove and original nullable main fallback; no primary MID switch','Independent revision in the same Store for selection or effective selected credential changes','Same Repository Call.Factory/client and epoch tags; original isolated playback CookieJar algorithm as ephemeral selected transport state retained by that same Store','PUGV/PGC and video services only use the selected transport; other original Profile/Home/account traffic stays primary','Cache keys and returned source receipt reject old authorization; final Root source publication guarded without native join under Store'],
 noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True,noAccountRead=True,notRuntimeAcceptance=True)
safe(HERE/'source-contract.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print('source-contract SHA '+hashlib.sha256(safe(HERE/'source-contract.json').read_bytes()).hexdigest())
