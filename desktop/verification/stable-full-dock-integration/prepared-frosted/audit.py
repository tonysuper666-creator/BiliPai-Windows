from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];PRIMARY=REPO.parent/'BiliPai'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def save(p,x):write(p,json.dumps(x,ensure_ascii=False,indent=2)+'\n')
def mod(p,n):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
producer=mod(HERE/'prepared/desktop/tools/extract-upstream-frosted-audio-renderer.py','frosted_audit_producer');parser=mod(REPO/'desktop/tools/sync-upstream.py','frosted_audit_tokens')
inv=json.loads(read(HERE/'generated/source-inventory.json'));checks=[];bodies=[]
def prove(v,n,**details):assert v,n;checks.append(dict(name=n,status='PASS',**details))
def adapt(body,path):
 if path.endswith('BottomBar.kt'):
  body=body.replace('SystemClock.elapsedRealtime()','(System.nanoTime() / 1_000_000L)')
  body=body.replace('com.android.purebilibili.core.store.HomeSettings = com.android.purebilibili.core.store.HomeSettings()','com.bilipai.desktop.settings.DesktopOriginalFrostedHomePreferences').replace('com.android.purebilibili.core.store.HomeSettings','com.bilipai.desktop.settings.DesktopOriginalFrostedHomePreferences')
  body=re.sub(r'stringResource\(R\.string\.(\w+)\)',lambda m:'com.bilipai.desktop.appearance.LocalDesktopStrings.current["'+m.group(1)+'"]',body)
 if path.endswith('AudioNowPlayingBar.kt'):
  body=body.replace('configuration.screenWidthDp','configuration.widthDp.value').replace('configuration.screenHeightDp','configuration.heightDp.value')
  body=body.replace('    state: AudioNowPlayingBarState,','    state: AudioNowPlayingBarState,\n    sourceIsOwned: () -> Boolean,',1)
  body=body.replace('val handleExpand = {','val handleExpand = ownedExpand@{\n        if (!sourceIsOwned()) return@ownedExpand',1)
  body=body.replace('        CardPositionManager.invalidateVideoSourceIfWindowChanged(screenWidthPx, screenHeightPx)','        if (sourceIsOwned()) CardPositionManager.invalidateVideoSourceIfWindowChanged(screenWidthPx, screenHeightPx)',1)
 return body
for row in inv['sources']:
 p=row['source'];original=read(REPO/p);blob=subprocess.check_output(['git','show',producer.PIN+':'+p],cwd=REPO).decode().replace('\r\n','\n')
 prove(original==blob and sha(original)==row['sourceSHA256LF'] and read(HERE/'generated/original-retained'/(p+'.txt'))==blob,'Pinned complete retained source '+p)
 if row.get('output') is None:continue
 generated=read(HERE/'generated'/row['output']);prove(sha(generated)==row['outputSHA256LF'],'Generated LF identity '+row['output'])
 source=original
 if row['output'].endswith('DesktopOriginalFrostedSettings.kt'):source=source[source.index('object SettingsManager {')+len('object SettingsManager {'):source.rfind('}')]
 decls=producer.declarations(parser,source)
 selected=row.get('selected')
 if selected:
  wanted={(r['name'],r['sha256LF']) for r in selected};decls=[(n,b) for n,b in decls if (n,sha(b)) in wanted]
  prove(len(decls)==len(selected),'All complete selections matched '+row['output'])
 for n,b in decls:
  expected=adapt(b,p)
  # Settings keys remain exact; 9 fields/local expressions are separately audited below.
  prove(expected.strip() in generated,'Full original declaration '+row['output']+'#'+n,sourceSHA256LF=sha(b),platformBodySHA256LF=sha(expected))
  bodies.append(dict(source=p,output=row['output'],name=n,sourceSHA256LF=sha(b),platformBodySHA256LF=sha(expected),tokenIdentical=[t[0] for t in parser.kotlin_tokens(b)]==[t[0] for t in parser.kotlin_tokens(expected)]))
 if not selected:
  expected=original
  expected=expected.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.android.purebilibili.core.util.LocalWindowSizeClass as LocalConfiguration').replace('import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion','import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion')
  expected=adapt(expected,p)
  prove(generated==expected,'Whole file equality minus explicit Windows imports/owner boundary '+row['output'])
fresh=HERE/('production-byte-proof-'+sys.argv[1]+('-'+sys.argv[2] if len(sys.argv)>2 else ''));assert not fresh.exists();producer.generate(HERE/'source-shadow',fresh)
paths=[p.relative_to(safe(HERE/'generated')) for p in safe(HERE/'generated/com').rglob('*.kt')]
for p in paths:prove(read(HERE/'generated'/p)==read(fresh/p),'Fresh production byte equality '+str(p))
code=read(HERE/'prepared/desktop/tools/extract-upstream-frosted-audio-renderer.py')
prove('.local' not in code and 'snapshot' not in code and 'fixture' not in code,'Production producer no proof/JAR/local dependency')
settings=read(HERE/'generated/com/bilipai/desktop/settings/DesktopOriginalFrostedSettings.kt')
original=read(REPO/(producer.BASE+'core/store/SettingsManager.kt'));prefix=original[original.index('        val liquidGlassProgress =',original.index('val legacyLiquidGlassEnabled')):original.index('        return HomeSettings(')]
prove(prefix.replace('preferences[liquidGlassReadabilityModePreferencesKey]','preferences[intPreferencesKey("liquid_glass_readability_mode")]') in settings,'Exact complete original liquid local definitions and typed same key')
prove('intPreferencesKey("liquid_glass_readability_mode")' in read(REPO/(producer.BASE+'core/store/home/LiquidGlassSettingsStore.kt')),'Readability key exact original source')
prove('val bottomBarLiquidGlassPreset: BottomBarLiquidGlassPreset =\n        BottomBarLiquidGlassPreset.BILIPAI_TUNED,' in original,'Tuned preset exact original HomeSettings default')
prove('context.settingsDataStore.data' in settings and 'DesktopPluginStore(' not in settings and 'typealias' not in settings,'Same actual Store only readonly view')
root=read(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalFrostedAudioRoot.kt')
prove('Channel(' not in root and 'SendChannel<String>?' in root,'No new list receiver/channel authority')
prove('CoroutineScope(' not in root and 'ListenAudioSession(' not in root and 'MpvPlayer(' not in root,'No new application scope/player/actor')
prove('repository.sessionEpoch == epoch' in root and 'rootScope.isActive' in root and 'ownerIsCurrent()' in root and 'it.bvid == item.bvid && it.cid == item.cid' in root,'Actual epoch/page/root/current queue control gates')
prove('sourceIsOwned = { owner.isOwned() && audio.ownsCurrent(item) }' in root,'Audio bounds gate before original source mutation')
prove('homeSettings = actualHome' in root and 'FrostedBottomBar(' in root and 'AudioNowPlayingBar(' in root and 'surfaceMergeProgress = surface' in root,'Complete actual original navigation/audio consumer')
prove('hasActiveAudioPlayback' in root and 'nowPlayingVisibility.sessionActive' in root and 'handoff = nowPlayingNavigation.handoff' in root and 'sourceRoute = nowPlayingNavigation.sourceRoute' in root,'Required actual session visibility/morph/source values')
prove('modifier.excludeFromLiquidBackground()' in root and 'audioModifier.excludeFromLiquidBackground()' in root,'Same real Root background capture exclusion')
proof=json.loads(read(HERE/'runs'/sys.argv[1]/'compile-evidence.json'));prove(proof['passed'],'Actual17 exact92CP full compile')
cp=proof['orderedRuntimeCP'];jar=HERE/'runs'/sys.argv[1]/'candidate.jar';main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
allowed={'com/android/purebilibili/feature/home/components/BottomBarKt.class','com/android/purebilibili/feature/home/components/AndroidNativeBottomBarTuning.class','com/android/purebilibili/feature/home/components/BottomBarItemMotionVisual.class'}
allExisting=set()
for r in cp:
 prove(hashlib.sha256(safe(r['path']).read_bytes()).hexdigest()==r['sha256Bytes'],'Actual CP unchanged '+r['path'])
 with zipfile.ZipFile(safe(r['path'])) as z:allExisting.update(n for n in z.namelist() if n.endswith('.class'))
with zipfile.ZipFile(safe(jar)) as z:classes={n for n in z.namelist() if n.endswith('.class')}
prove(classes&allExisting==allowed,'Only declared full19 existing shared producer visibility overlay class overlap',overlap=sorted(allowed))
def wrappers(p):
 with zipfile.ZipFile(safe(p)) as z:return [n[:-6].replace('/','.') for n in z.namelist() if n.endswith('Kt.class') and '$' not in n]
own=[n for n in wrappers(jar) if '.frostedAudioProof.' not in n];packages={n.rsplit('.',1)[0] for n in own};actual=[n for n in wrappers(main) if n.rsplit('.',1)[0] in packages]
compiler=mod(PRIMARY/'desktop/.local/source9-appearance/compile-miuix.py','frosted_audit_compiler')
def topmethods(label,p,classes):
 r=subprocess.run([str(compiler.JAVA.parent/'javap.exe'),'-p','-s','-classpath',str(p)]+classes,capture_output=True,text=True,encoding='utf-8',timeout=60);assert r.returncode==0;write(HERE/(label+'-top-methods.javap.txt'),r.stdout)
 records=[];owner=None;pending=None
 for line in r.stdout.splitlines():
  m=re.search(r'public (?:final )?class (\S+)',line)
  if m:owner=m.group(1);pending=None
  elif line.strip().startswith('public static ') and '(' in line:pending=(owner,line.split('(',1)[0].split()[-1])
  elif pending and 'descriptor:' in line:
   d=line.split('descriptor:',1)[1].strip();records.append(dict(owner=pending[0],package=pending[0].rsplit('.',1)[0],jvmName=pending[1],parameterDescriptor=d[1:d.index(')')],descriptor=d));pending=None
 return records
a=topmethods('candidate',jar,own);b=topmethods('actual17',main,actual);key=lambda r:(r['package'],r['jvmName'],r['parameterDescriptor']);overlay='com.android.purebilibili.feature.home.components.BottomBarKt'
intersection=sorted(set(map(key,[r for r in a if r['owner']!=overlay]))&set(map(key,b)))
if intersection:print('UNEXPECTED_OVERLAP',json.dumps(intersection))
prove(not intersection,'New producer top-level JVM function parameter overlap zero',candidateMethods=len(a),actualMethods=len(b),intersection=intersection)
save(HERE/'source-and-symbol-audit.json',dict(status='PASS',checkCount=len(checks),checks=checks,completeBodies=bodies,tokenIdenticalCount=sum(r['tokenIdentical'] for r in bodies),platformChangedBodyCount=sum(not r['tokenIdentical'] for r in bodies),generatedOriginalKtCount=len(paths),sourceInputCount=len(producer.SOURCES),actualSnapshot=proof['snapshotManifest'],actualCP=proof['snapshotCP'],declaredProspectiveExistingClassOverlap=sorted(allowed),newClassOverlap=[],newTopMethodIntersection=intersection,actualTopMethods=b,candidateTopMethods=a,noLiveSourceWritten=True,noGradle=True,noHTTP=True,noHWND=True))
print('PASS',len(checks),'source/ABI checks;',len(bodies),'complete original bodies')
