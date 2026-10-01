from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];PRIMARY=REPO.parent/'BiliPai';EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(EXT) else EXT+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def mod(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
producer=mod(HERE/'prepared/desktop/tools/extract-upstream-linked-dock.py','dock_audit_producer');parser=mod(REPO/'desktop/tools/sync-upstream.py','dock_audit_tokens');decl=mod(REPO/'desktop/tools/extract-appearance-platform.py','dock_audit_decl')
identity=json.loads(read(HERE/'generated/source-identities.json'));checks=[];methods=[]
def prove(ok,name,**fields):assert ok,name;checks.append(dict(status='PASS',name=name,**fields))
for row in identity['sources']:
 original=read(REPO/row['path']);blob=subprocess.check_output(['git','show',producer.PIN+':'+row['path']],cwd=REPO).decode().replace('\r\n','\n')
 prove(original==blob and read(HERE/'generated/original-retained'/(row['path']+'.txt'))==blob,'retained pinned Git blob '+row['path'])
for row in identity['outputs']:
 if 'path' not in row:continue
 original=read(REPO/row['source']);generated=read(HERE/'generated/com/android/purebilibili'/row['path'])
 prove(sha(generated)==row['sha256LF'],'output LF identity '+row['path'])
 selected=row.get('selected')
 if not selected:
  expected=original
  if row['path'].endswith('LinkedBottomDock.kt'):
   expected=expected.replace('import androidx.activity.compose.BackHandler','import com.android.purebilibili.core.ui.LocalNavigationBackHandler as BackHandler').replace('import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion','import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion')
  elif row['path'].endswith('HomeNavigationIconPolicy.kt'):
   expected=expected.replace('import androidx.compose.ui.res.vectorResource\n','').replace('import com.android.purebilibili.R','import com.bilipai.desktop.settings.DesktopLinkedDockSymbols\nimport com.bilipai.desktop.settings.DesktopLinkedDockVectors').replace('R.drawable.','DesktopLinkedDockSymbols.').replace('ImageVector.vectorResource(','DesktopLinkedDockVectors.vector(')
  prove(generated==expected,'whole original file minus declared platform imports/lookups '+row['path'],originalSha256LF=sha(original))
  # Explicit method token bodies, independent of imports.
  names=re.findall(r'(?m)^(?:internal |private |public |suspend |inline )*fun (\w+)\(',original)
  for name in names:
   orig=decl.declarations(parser,original,[name]);changed=orig
   if row['path'].endswith('HomeNavigationIconPolicy.kt'):changed=changed.replace('R.drawable.','DesktopLinkedDockSymbols.').replace('ImageVector.vectorResource(','DesktopLinkedDockVectors.vector(')
   prove(changed.strip() in generated,'complete method '+row['path']+'#'+name)
   methods.append(dict(source=row['source'],name=name,sourceSha256LF=sha(orig),selectedBodySha256LF=sha(changed),tokenIdentical=[t[0] for t in parser.kotlin_tokens(orig)]==[t[0] for t in parser.kotlin_tokens(changed)]))
 else:
  source=original
  if row['path'].endswith('DesktopOriginalLinkedDockSettings.kt'):source=source[source.index('object SettingsManager {')+len('object SettingsManager {'):source.rfind('}')]
  for name in selected:
   orig=producer.local_callback(parser,source,name) if row['path'].endswith('DesktopOriginalBottomSearchSubmit.kt') else decl.declarations(parser,source,[name]);expected=orig
   if row['path'].endswith('DesktopOriginalLinkedDockControls.kt'):
    expected=expected.replace('@StringRes val labelRes: Int','val labelRes: String').replace('@StringRes val contentDescriptionRes: Int','val contentDescriptionRes: String')
    expected=re.sub(r'R\.string\.(\w+)',lambda m:'"'+m.group(1)+'"',expected)
    expected=expected.replace('stringResource(item.labelRes)','com.bilipai.desktop.appearance.LocalDesktopStrings.current[item.labelRes]').replace('stringResource(item.contentDescriptionRes)','com.bilipai.desktop.appearance.LocalDesktopStrings.current[item.contentDescriptionRes]').replace('private fun resolveSharedBottomBarIcon','internal fun resolveSharedBottomBarIcon')
   if row['path'].endswith('DesktopOriginalBottomSearchSubmit.kt'):
    for before,after in [('effectiveHomeSettings.isBottomBarSearchEnabled','bottomBarSearchEnabled'),('effectiveHomeSettings.listScopedSearchEnabled','listScopedSearchEnabled'),('currentNavigation3Key == BiliPaiNavKey.MainHost','isMainHost'),('pushSearchRouteInNavigation3(action.keyword)','onOpenSearch(action.keyword)'),('openBilibiliNativeTargetInNavigation3(action.target)','onOpenNativeTarget(action.target)')]:expected=expected.replace(before,after)
   prove(expected.strip() in generated,'complete selected body '+row['path']+'#'+name)
   methods.append(dict(source=row['source'],name=name,sourceSha256LF=sha(orig),selectedBodySha256LF=sha(expected),tokenIdentical=[t[0] for t in parser.kotlin_tokens(orig)]==[t[0] for t in parser.kotlin_tokens(expected)]))
fresh=HERE/('production-byte-proof-'+sys.argv[1]);assert not fresh.exists();producer.generate(HERE/'source-shadow',fresh)
paths=[p.relative_to(safe(HERE/'generated')) for p in safe(HERE/'generated/com').rglob('*.kt')]
for path in paths:prove(read(HERE/'generated'/path)==read(fresh/path),'production source byte equality '+str(path))
code=read(HERE/'prepared/desktop/tools/extract-upstream-linked-dock.py')
prove('.local' not in code and 'snapshot' not in code and 'fixture' not in code,'production producer no local proof dependency')
icons=json.loads(read(HERE/'miuix-icons-source-evidence.json'))
for row in icons['selectedFullSources']:prove(hashlib.sha256(safe(row['payload']).read_bytes()).hexdigest()==row['sha256'],'unchanged whole original Miuix source '+row['path'])
proof=json.loads(read(HERE/'runs'/sys.argv[1]/'compile-evidence.json'));prove(proof['passed'] and not proof['classOverlap'],'actual Snapshot15 compile and FQN intersection zero')
jar=HERE/'runs'/sys.argv[1]/'candidate.jar';cp=proof['orderedRuntimeCP'];main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
def wrappers(p):
 with zipfile.ZipFile(safe(p)) as z:return [n[:-6].replace('/','.') for n in z.namelist() if n.endswith('Kt.class') and '$' not in n]
own=[n for n in wrappers(jar) if '.linkedDockProof.' not in n];packages={n.rsplit('.',1)[0] for n in own}
actual=[n for n in wrappers(main) if n.rsplit('.',1)[0] in packages]
compiler=mod(PRIMARY/'desktop/.local/source9-appearance/compile-miuix.py','dock_audit_compiler')
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
a=topmethods('candidate',jar,own);b=topmethods('actual-snapshot15',main,actual);key=lambda r:(r['package'],r['jvmName'],r['parameterDescriptor']);intersection=sorted(set(map(key,a))&set(map(key,b)))
prove(not intersection,'samepackage JVM method parameters zero overlap',candidateMethods=len(a),actualMethods=len(b),intersection=intersection)
with zipfile.ZipFile(safe(jar)) as z:classes={n for n in z.namelist() if n.endswith('.class')}
allExisting=set()
for r in cp:
 with zipfile.ZipFile(safe(r['path'])) as z:allExisting.update(n for n in z.namelist() if n.endswith('.class'))
prove(not classes&allExisting,'class FQN zero overlap with all92 actual CP entries')
body=read(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalLinkedDockRoot.kt')
prove('= false' not in body and 'mutableFloatStateOf' not in body and 'mutableStateOf' not in body,'required Root actual scroll, phase, playback and navigation inputs; no mock defaults')
prove('SendChannel<String>?' in body and 'Channel(' not in body,'Root same three list channels required; no new channel authority')
prove('LocalHomeScrollOffset provides owner.scrollOffset' in body and 'LocalHomeFeedScrollInProgress provides owner.feedScrollInProgress' in body,'original locals receive actual same owner state')
prove('modifier.excludeFromLiquidBackground()' in body and 'backdrop = backdrop' in body,'same real Root backdrop and original adaptive capture exclusion')
save(HERE/'source-and-symbol-audit.json',dict(status='PASS',checks=checks,checkCount=len(checks),selectedBodies=methods,tokenIdenticalCount=sum(r['tokenIdentical'] for r in methods),platformChangedBodyCount=sum(not r['tokenIdentical'] for r in methods),generatedSourceCount=len(paths),sourceInputCount=len(identity['sources']),actualTopMethods=b,candidateTopMethods=a,classIntersection=[],methodIntersection=intersection,ListScopedSearchPolicySoleOwner='Root/player_parity Favorites direct row; compile-only exact original source copy here',MiuixVersionAlignment='5157 extension only; stable5c91 alignment pending',noMainWrites=True,noGradle=True,noHTTP=True,noHWND=True))
print('PASS',len(checks),'source/ABI checks;',len(methods),'complete bodies;',len(paths),'generated original/platform-vector sources')
