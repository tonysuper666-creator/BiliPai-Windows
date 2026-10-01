from pathlib import Path
import hashlib,importlib.util,json,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-39'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def shas(s):return hashlib.sha256(s.encode()).hexdigest()
def save(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
def main():
 m=load(HERE/'prepared/desktop/tools/extract-upstream-live-navigation.py','live_navigation_audit')
 parser=load(REPO/'desktop/tools/sync-upstream.py','live_audit_parser');media=load(REPO/'desktop/tools/extract-upstream-media.py','live_audit_selector')
 for p,h in m.SOURCE_PINS.items():assert shas(read(REPO/p))==h,p
 for name,standalone in [('production',False),('standalone',True)]:m.generate(REPO,HERE/'source-audit'/name,standalone)
 prepared=HERE/'prepared/generated';prod=HERE/'source-audit/production';stand=HERE/'source-audit/standalone'
 preparedKt={p.relative_to(prepared).as_posix():sha(p) for p in prepared.rglob('*.kt')}
 standKt={p.relative_to(stand).as_posix():sha(p) for p in stand.rglob('*.kt')}
 prodKt={p.relative_to(prod).as_posix():sha(p) for p in prod.rglob('*.kt')}
 assert preparedKt==standKt and len(prodKt)==5 and len(standKt)==6
 for p,h in prodKt.items():assert standKt[p]==h
 assert set(standKt)-set(prodKt)=={'com/android/purebilibili/feature/live/LiveAreaScreenPolicy.kt'}
 proof=json.loads(read(prepared/'live-navigation-selection-proof.json'));inverse=[]
 for row in proof['selectedSources']:
  if row.get('mode')!='selected' or not row.get('output','').endswith('Screen.kt'):continue
  p=row['source'];s=read(prepared/row['output']);original=read(REPO/p);changes=[c for c in proof['exactAdaptations'] if c['source']==p]
  for change in reversed(changes):
   for position in reversed(change['positionsAfter']):
    assert s[position:position+len(change['after'])]==change['after'],(p,change,position)
    s=s[:position]+change['before']+s[position+len(change['after']):]
  assert s==original,p
  inverse.append(dict(source=p,originalLines=len(original.splitlines()),originalSha256LF=shas(original),inverseNormalizedByteEqual=True,adaptationCount=len(changes)))
 assert len(inverse)==4
 searchPath=m.BASE+'data/repository/SearchRepository.kt';original=read(REPO/searchPath);new=read(prepared/'com/android/purebilibili/data/repository/DesktopOriginalLiveSearchProtocol.kt');methodsPins=[]
 for name in ['searchTypeParams','createPageInfo','createSearchError','searchLive','signWithWbi']:
  before=media.function(original,name,parser);after=media.function(new,name,parser)
  inverseBody=after.replace('} catch (cancelled: CancellationException) { throw cancelled\n        } catch (e: Exception) {','} catch (e: Exception) {') if name=='searchLive' else after
  if name=='signWithWbi':inverseBody=inverseBody.replace('android.util.Log.w(', 'com.android.purebilibili.core.util.Logger.w(')
  assert inverseBody==before,name
  methodsPins.append(dict(name=name,originalBodySha256LF=shas(before),preparedBodySha256LF=shas(after),inverseNormalizedByteEqual=True))
 settingsPath=m.BASE+'core/store/SettingsManager.kt';settings=read(REPO/settingsPath);manual=read(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopLiveNavigationBindings.kt')
 originalSetter=media.function(settings,'setLiveFavoriteTags',parser)
 originalNormalize=originalSetter[originalSetter.index('val normalized ='):originalSetter.index('context.settingsDataStore.edit')].strip()
 start=manual.index('val normalized =');candidateNormalize=manual[start:manual.index('val value =',start)].strip()
 tokens=lambda text:[t[0] for t in parser.kotlin_tokens(text)]
 assert tokens(originalNormalize)==tokens(candidateNormalize)
 assert 'stringPreferencesKey("live_favorite_tags")' in settings and 'private val key = "live_favorite_tags"' in manual
 assert 'Json.decodeFromString<List<LiveFavoriteTagEntry>>(raw)' in settings and 'Json.decodeFromString<List<LiveFavoriteTagEntry>>(raw)' in manual
 assert 'getOrDefault(emptyList())' in settings and 'getOrDefault(emptyList())' in manual
 assert 'Json.encodeToString(normalized)' in settings and 'Json.encodeToString(normalized)' in manual
 result=json.loads(read(HERE/'runs/compile04/compile-result.json'));assert result['status']=='PASS' and len(result['sourceInputs'])==8 and not result['productionClassIntersection']
 old=read(MAIN/'desktop/.local/dynamic-editor-detail-parity/protocol-review/top-level-uniqueness-review/review.py')
 helper=old[old.index('def methods('):old.index('\nwith zipfile.ZipFile(',old.index('def methods('))];scope={};exec('import struct\n'+helper,scope);methods=scope['methods']
 actual={};count=0;own=[];collisions=[]
 cp=json.loads(read(SNAP/'ordered-runtime-cp.json'))
 for item in cp:
  assert sha(item['path'])==item['sha256Bytes']
  with zipfile.ZipFile(safe(item['path'])) as jar:
   for e in jar.namelist():
    if e.endswith('Kt.class') and '$' not in e:
     for method in methods(jar.read(e),e):count+=1;actual.setdefault(method['key'],[]).append(method)
 with zipfile.ZipFile(safe(HERE/'runs/compile04/prepared-live-navigation.jar')) as jar:
  assert not [e for e in jar.namelist() if e.endswith('.class') and b'NON_LOCAL_RETURN' in jar.read(e)]
  for e in jar.namelist():
   if e.endswith('Kt.class') and '$' not in e:
    for method in methods(jar.read(e),e):
     own.append(method)
     for hit in actual.get(method['key'],[]):collisions.append(dict(candidate=method,actual=hit))
 assert not collisions,collisions
 fixture=json.loads(read(HERE/'runs/fixture-01/result.json'));assert fixture['status']=='PASS' and fixture['assertions']==25
 value=dict(status='PASS',upstreamCommit=m.COMMIT,fullOriginalPages=inverse,originalPagePhysicalLines=sum(row['originalLines'] for row in inverse),
  productionSelectedOutputs=5,standaloneOutputs=6,productionDirectOriginalPolicyCopies=1,manualWindowsBindings=1,sourceProducerByteEquality=True,
  originalSearchMethods=methodsPins,originalLiveFavoriteTags=dict(source=settingsPath,originalSha256LF=shas(settings),key='live_favorite_tags',getterInvalidJsonEmptyFallback=True,setterOriginalTokensEqual=True,setterDistinctTake12=True,originalPolicyToggleTakeLast8=True,sameGlobalStore=True),
  compiledSources=8,compiledClasses=result['classCount'],productionClassIntersections=[],candidateTopLevelMethods=len(own),actualTopLevelMethods=count,topLevelIntersections=[],illegalNonLocalReturnMarkers=[],
  actualSnapshotManifestSha256Bytes=sha(SNAP/'manifest.json'),actualOrdered97CpSha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),candidateJarSha256Bytes=result['candidateJarSha256Bytes'],
  focusedFixture=fixture,sourceOnly=True,actualRootMountedUiAccepted=False,HTTP=False,HWND=False,GUI=False,Gradle=False,
  compileHistory=['compile01: one positional protocol argument + two shared skeleton gaps','compile02: existing Windows Logger missing w API; maps original warning to existing actual android.util.Log.w diagnostic bridge','compile03: initial PASS','compile04: final same Root -> binding -> global preference publication lock; PASS'],
  pending=['Root immutable route/epoch-owned services and CompositionLocal injection; full mounted UI/pointer/native IME/back/scroll acceptance','Root sole ContentLoadingSkeletons producer addition and whole product compile'])
 save(HERE/'source-checks.json',value)
 registry=json.loads(read(REPO/'desktop/upstream-sources.json'));existing={r['path']:r for r in registry['sources']}
 rows=[]
 for p,h in m.SOURCE_PINS.items():
  mode='direct' if p.endswith('LiveAreaScreenPolicy.kt') else 'extracted' if p.endswith('Screen.kt') else 'reference'
  rows.append(dict(path=p,sha256LF=h,proposedMode=mode,existingIdentity=existing.get(p),feature='live-sub-navigation',rule='Merge feature only for existing same-identity rows; do not replace existing mode/producer'))
 save(HERE/'registry-merge-recipe.json',dict(overwrite=False,rows=rows,directCopyOnce=['feature/live/LiveAreaScreenPolicy.kt'],newWholePageIdentities=4,onlyNewPolicyIdentity='feature/live/LiveAreaScreenPolicy.kt',originalSearchRepositoryIdentityAlreadyOwned=True))
 print(json.dumps(dict(status='PASS',classes=result['classCount'],fixtureAssertions=25,topLevelIntersections=0,sourceChecksSha256Bytes=sha(HERE/'source-checks.json')),indent=2))
if __name__=='__main__':main()
