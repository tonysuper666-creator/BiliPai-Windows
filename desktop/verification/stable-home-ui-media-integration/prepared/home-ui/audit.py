from pathlib import Path
import re,json,hashlib,subprocess,zipfile,importlib.util
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def text(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def digest(s):return hashlib.sha256(s.encode()).hexdigest()
def load(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
parser=load('home_parser',REPO/'desktop/tools/sync-upstream.py')
def declarations(s):
 t=parser.kotlin_tokens(s);depth=parens=brackets=0;starts=[]
 for i,(v,a,b) in enumerate(t):
  if depth==0 and parens==0 and brackets==0 and v in ['fun','val','var','class','object','interface','typealias']:
   if v=='fun':
    j=i+1
    while j<len(t) and t[j][0]!='(':j+=1
    name=t[j-1][0] if j<len(t) else '?'
   else:name=t[i+1][0]
   line=s.rfind('\n',0,a)+1
   while line>0:
    prev=s.rfind('\n',0,line-1)+1
    if s[prev:line].strip().startswith('@'):line=prev
    else:break
   starts.append((name,line))
  depth+=(v=='{')-(v=='}');parens+=(v=='(')-(v==')');brackets+=(v=='[')-(v==']')
 return [(name,s[begin:starts[i+1][1] if i+1<len(starts) else len(s)].rstrip()) for i,(name,begin) in enumerate(starts)]
originals={};symbols={}
for root in [REPO/'app/src/main/java',REPO/'design-system/src/main/java']:
 for p in root.rglob('*.kt'):
  s=text(p);pkg=re.search(r'(?m)^package\s+([\w.]+)',s)
  if not pkg:continue
  path=p.relative_to(REPO).as_posix();ds=declarations(s);originals[path]=(s,pkg[1],ds)
  for n,_ in ds:symbols.setdefault(pkg[1]+'.'+n,[]).append(path)
existing={};existingpaths=[]
for root in [REPO/'desktop/build/generated',REPO/'desktop/src/main/kotlin']:
 for p in root.rglob('*.kt'):
  s=text(p);pkg=re.search(r'(?m)^package\s+([\w.]+)',s)
  if pkg:
   for n,_ in declarations(s):existing.setdefault(pkg[1]+'.'+n,[]).append(str(p.relative_to(REPO)))
registry=json.loads(text(REPO/'desktop/upstream-sources.json'))
for row in registry['sources']:
 if row['mode']=='direct' and row['path'] in originals:
  _,pkg,ds=originals[row['path']]
  for n,d in ds:existing.setdefault(pkg+'.'+n,[]).append('direct:'+row['path'])
for f in ['DissolveAnimationPreset','MaybeDissolvableVideoCard','DissolvableVideoCard','jiggleOnDissolve']:
 existing['com.android.purebilibili.core.ui.animation.'+f]=['adapter: sole DesktopReplyDissolvableContainer / FavoriteJiggle']
paths=['app/src/main/java/com/android/purebilibili/feature/home/'+n+'.kt' for n in ['HomeScreen','HomeCategoryPage','HomeUiState']]
paths += ['app/src/main/java/com/android/purebilibili/feature/home/components/'+n+'.kt' for n in ['HomeHeader','HomeTopControls','HomeTopTabChrome','HomeTopTabFloatingDock','HomeTopTabMotionSpec','MineSideDrawer','MineSideDrawerLayoutPolicy','MineSideDrawerVisualPolicy','SideBar','HomeHeroCarousel','VideoPreviewDialog','HomeNotInterestedReasonSheet','HomeRefreshIndicator','HomeRefreshMotionSpec']]
paths += ['app/src/main/java/com/android/purebilibili/feature/home/components/cards/StoryVideoCard.kt']
# Public dependencies recursively owned only by the full Home page.
excluded=['HomeViewModel','FrostedBottomBar','FloatingBottomBar','AudioNowPlayingBar','BottomBar','LiveListScreen','HomeBangumiTabPage','PartitionContent','CrashTrackingConsentDialog','SettingsManager','HomeSettingsStore','HomeTopTabSettingsStore','AppNavigationSettingsStore','WallpaperPaletteStore']
queue=list(paths);unresolved={};references={}
while queue:
 path=queue.pop(0)
 if path not in originals:continue
 s,pkg,ds=originals[path]
 imports=re.findall(r'(?m)^import\s+(com\.android\.purebilibili\.[\w.]+)',s)
 candidates=imports+[pkg+'.'+n for n in re.findall(r'\b[A-Za-z_][\w]*\b',s)]
 for symbol in set(candidates):
  if symbol in existing:references[symbol]=existing[symbol];continue
  ps=symbols.get(symbol,[])
  if len(ps)==1:
   p=ps[0]
   if any(Path(p).stem==n for n in excluded):unresolved[symbol]=p;continue
   if p not in paths:paths.append(p);queue.append(p)
  elif symbol in imports and not ps:unresolved[symbol]='no source declaration'
rows=[]
for p in paths:
 s,pkg,ds=originals[p]
 names=[n for n,d in ds if pkg+'.'+n not in existing or n=='HomeScreen']
 old=[n for n,d in ds if pkg+'.'+n in existing and n!='HomeScreen']
 rows.append(dict(path=p,sha256LF=digest(s),allDeclarations=[n for n,_ in ds],selectedMissingDeclarations=names,alreadyOwnedDeclarations=old))
write(HERE/'closure-audit.json',json.dumps(dict(targetCommit=COMMIT,paths=rows,references=references,requiredAdapters=unresolved),ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(paths=len(rows),references=len(references),requiredAdapters=unresolved),ensure_ascii=False,indent=2))
# Refine dependency graph per selected declaration; unused members of huge Android files are not copied.
root_paths=paths[:18]
selected={p:set(n for n,d in originals[p][2] if originals[p][1]+'.'+n not in existing or n=='HomeScreen' or d.startswith('private ')) for p in root_paths}
selected['app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt']={'HomeSettings','HomeTopTabSettings','AppNavigationSettings','HomeHeaderCollapseMode','HomeBarHideType','HomeTopRightAction','HomeTopLayoutOrder'}
blockedFiles={'HomeViewModel','SettingsManager','VideoShareSheet','DownloadManager','SubscriptionFeedPage','PartitionScreen','SystemBarCompat','VideoRepository','WebViewScreen','NetworkUtils','VideoShareSheetMotion','VideoShareCoverService','VideoShareMoreTargetsSheet','VideoShareToFollowingDialog','WallpaperMedia','WallpaperPaletteStore','UiSkinSettingsStore','AnalyticsHelper','ParticleDissolveEffect','JankTracking','RecoverableVisualEffects','BackgroundManager','BottomBarUiSkin','SideBarRendererPolicy','SideBarMotionSpec','BottomBarTypographySpec','VideoCardTransitionBackgroundPolicy'}
blockedNames={'HomeScreen' if False else '_none','BottomNavItem','FrostedBottomBar','LiveListScreen','HomeBangumiTabPage','PartitionContent','CrashTrackingConsentDialog','SettingsManager','DesktopHomeCardPlatform','LocalHomeScrollOffset','LocalHomeFeedScrollInProgress'}
refs={};ports={};changed=True
while changed:
 changed=False
 for path,ns in list(selected.items()):
  s,pkg,ds=originals[path];body='\n'.join(d for n,d in ds if n in ns);words=set(re.findall(r'\b[A-Za-z_][\w]*\b',body))
  imports=re.findall(r'(?m)^import\s+(com\.android\.purebilibili\.[\w.]+)',s)
  imports=[x for x in imports if x.rsplit('.',1)[-1] in words]
  candidates=imports+[pkg+'.'+n for n in words]
  candidates+=re.findall(r'\b(com\.android\.purebilibili\.[\w.]+)',body)
  for symbol in set(candidates):
   if symbol in existing:refs[symbol]=existing[symbol];continue
   ps=symbols.get(symbol,[])
   if len(ps)!=1:continue
   p=ps[0];n=symbol.rsplit('.',1)[-1]
   if n in blockedNames or Path(p).stem in blockedFiles:
    if n not in selected.get(p,set()):ports[symbol]=p
    continue
   if n not in selected.setdefault(p,set()):selected[p].add(n);changed=True
rows=[]
for p,ns in selected.items():
 s,pkg,ds=originals[p];names=[n for n,_ in ds if n in ns];old=[n for n,_ in ds if pkg+'.'+n in existing and n not in ns]
 rows.append(dict(path=p,sha256LF=digest(s),allDeclarations=[n for n,_ in ds],selectedDeclarations=names,alreadyOwnedDeclarations=old))
write(HERE/'selected-closure-audit.json',json.dumps(dict(targetCommit=COMMIT,paths=rows,references=refs,requiredAdapters=ports),ensure_ascii=False,indent=2)+'\n')
print('REFINED',len(rows),sum(len(r['selectedDeclarations'])for r in rows),'declarations; ports',json.dumps(ports,ensure_ascii=False,indent=2))
