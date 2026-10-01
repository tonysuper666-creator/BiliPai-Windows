from pathlib import Path
import hashlib,json,subprocess,difflib,importlib.util,re,collections
LANE=Path(__file__).resolve().parent;REPO=LANE.parents[2];COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
g=load(LANE/'prepared/tools/extract-upstream-profile-main.py','profile_audit_producer')
parser=load(REPO/'desktop/tools/sync-upstream.py','profile_audit_parser');media=load(REPO/'desktop/tools/extract-upstream-media.py','profile_audit_selector')
checks=[]
def check(label,value):assert value,label;checks.append(label)
for p,h in g.SOURCE_PINS.items():
 s=read(LANE/'original-stable'/p)
 check('fixed stable Git blob '+p,sha(s)==h and subprocess.run(['git','show',COMMIT+':'+p],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\r\n','\n')==s)
g.generate(REPO,LANE/'replay-production',False);g.generate(REPO,LANE/'replay-standalone',True)
stand={p.relative_to(safe(LANE/'replay-standalone')).as_posix():read(p) for p in safe(LANE/'replay-standalone').rglob('*.kt')}
prod={p.relative_to(safe(LANE/'replay-production')).as_posix():read(p) for p in safe(LANE/'replay-production').rglob('*.kt')}
check('whole standalone original22 / production selected10',len(stand)==22 and len(prod)==10)
for p,s in stand.items():check('standalone replay exact '+p,s==read(LANE/'prepared/generated'/p))
for p,s in prod.items():check('production replay exact '+p,s==stand[p])
selection=json.loads(read(LANE/'prepared/generated/profile-selection-proof.json'))
inventory=[];methodInventory=[]
def names(s):return collections.Counter(re.findall(r'\bfun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(',s))
for record in selection:
 p=record['source'];original=read(LANE/'original-stable'/p);prepared=stand[record['output']]
 inventory.append({'path':p,'originalSha256LF':sha(original),'preparedSha256LF':sha(prepared),'output':record['output'],'wholeOriginalFile':original==prepared,'preparedProductionSelected':record['output'] in prod})
 diff=''.join(difflib.unified_diff(original.splitlines(True),prepared.splitlines(True),fromfile=p,tofile=record['output']))
 write(LANE/'adaptation-diffs'/Path(record['output']).with_suffix('.diff'),diff)
 # Complete original UI/VM files are retained; invert each exact adapter patch over the
 # full file, so Android body retention remains independently reviewable byte-for-byte.
 if p.endswith(('/ProfileScreen.kt','/ProfileViewModel.kt','/WallpaperAdjustmentSheet.kt','/OfficialWallpaperSheet.kt','/ProfileLoadingSkeleton.kt','/WallpaperMedia.kt','/SplashRepository.kt')):
  patches=[]
  ol=original.splitlines(True);pl=prepared.splitlines(True);opos=[0];ppos=[0]
  for line in ol:opos.append(opos[-1]+len(line))
  for line in pl:ppos.append(ppos[-1]+len(line))
  for op,a,b,c,d in difflib.SequenceMatcher(None,ol,pl,autojunk=False).get_opcodes():
   if op!='equal':
    a,b,c,d=opos[a],opos[b],ppos[c],ppos[d]
    patches.append({'originalStart':a,'originalEnd':b,'preparedStart':c,'preparedEnd':d,'original':original[a:b],'prepared':prepared[c:d]})
  restored=prepared
  for part in reversed(patches):
   check('reverse exact local patch '+record['output']+':'+str(part['preparedStart']),restored[part['preparedStart']:part['preparedEnd']]==part['prepared'])
   restored=restored[:part['preparedStart']]+part['original']+restored[part['preparedEnd']:]
  check('complete original file recovered '+p,restored==original)
  write(LANE/'reverse-adapters'/Path(record['output']).with_suffix('.json'),json.dumps(patches,ensure_ascii=False,indent=2))
 if '/feature/profile/' in p and not p.endswith('/SplashWallpaperRandomPoolPolicy.kt'):
  a=names(original);b=names(prepared);dropped=a-b;added=b-a
  if p.endswith('/ProfileScreen.kt'):check('sole existing original skin repeat helper reused',dropped=={'resolveProfileSkinVideoRepeatMode':1})
  else:check('original full function inventory retained '+p,not dropped)
  unchanged=[];changed=[]
  for name,n in a.items():
   if n!=1 or b[name]!=1:continue
   try:
    x=media.function(original,name,parser);y=media.function(prepared,name,parser)
   except (ValueError,IndexError):continue
   same=[t[0] for t in parser.kotlin_tokens(x)]==[t[0] for t in parser.kotlin_tokens(y)]
   (unchanged if same else changed).append(name)
  methodInventory.append({'source':p,'originalFunctions':sum(a.values()),'preparedFunctions':sum(b.values()),'droppedReusedUniqueHelper':dict(dropped),'addedWindowsMethods':dict(added),'tokenUnchangedFunctions':unchanged,'adaptedFunctions':changed})
for path,namesSelected in [('feature/video/ui/section/VideoActionSection.kt',['TripleProgressIcon']),('feature/video/ui/components/CelebrationAnimations.kt',['TripleSuccessAnimation','TripleActionIcon','phaseProgress','iconActivationProgress','lerp'])]:
 p=g.BASE+path;row=next(r for r in selection if r['source']==p);original=read(LANE/'original-stable'/p);prepared=stand[row['output']]
 for name in namesSelected:check('full selected original renderer/helper tokens '+name,[t[0] for t in parser.kotlin_tokens(media.function(original,name,parser))]==[t[0] for t in parser.kotlin_tokens(media.function(prepared,name,parser))])
vm=stand['com/android/purebilibili/feature/profile/ProfileViewModel.kt'];ui=stand['com/android/purebilibili/feature/profile/ProfileScreen.kt']
manual=read(LANE/'prepared/manual/com/bilipai/desktop/ui/DesktopProfileBindings.kt')
for bad in ['getApplication','android.widget','AndroidViewModel','NetworkModule','AccountSessionStore.','TokenManager.','FileOutputStream','copyTo(']:check('VM no Android/new actor/raw file bypass '+bad,bad not in vm)
check('owned response use on all three downloads',vm.count('platform.openOwnedDownload(request).use { response ->')==3)
check('single same owner/atomic publication for original transient state','DesktopOwnedProfileState(initial, environment)' in vm and 'environment.publishCallback { flow.value = next }' in manual)
check('all 17 navigation fields required / no Root default callback','val onBangumiMoreClick: () -> Unit' in manual and '= {}' not in manual)
check('no old user Space replacement','SpaceScreen' not in ui and 'ProfileScreen(binding.viewModel' in manual)
check('upstream unfinished wallpaper search retained', '// TODO' in vm and 'searchApi.searchAll(params)' in vm)
check('no second client/Store/persistent schema',all(b not in '\n'.join(stand.values())+manual for b in ['OkHttpClient.Builder','Retrofit.Builder','class DesktopSessionStore','object TokenManager']))
write(LANE/'source-inventory.json',json.dumps({'pinnedCommit':COMMIT,'originalInputs':len(g.SOURCE_PINS),'generatedSources':inventory},ensure_ascii=False,indent=2))
write(LANE/'method-inventory.json',json.dumps(methodInventory,ensure_ascii=False,indent=2))
write(LANE/'source-audit.json',json.dumps({'pinnedCommit':COMMIT,'checks':len(checks),'names':checks,'wholeOriginalUIVMFilesReverseExact':7,'unchangedWholeDirectFiles':12,'productionSelectedSources':10,'standaloneOriginalSources':22,'rootManualSources':2,'actualRoot':False},ensure_ascii=False,indent=2))
print('PASS '+str(len(checks))+' source/replay/reverse checks')
