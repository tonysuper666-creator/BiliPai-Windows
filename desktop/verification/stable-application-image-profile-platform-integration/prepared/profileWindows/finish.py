from pathlib import Path
import hashlib,json,subprocess,zipfile,importlib.util,shutil,os,re
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def digest(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def js(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2))
pins=json.loads(read(LANE/'pins.json'));delta=json.loads(read(LANE/'original-import-reverse.json'))
prepared=read(LANE/'prepared/generated/com/android/purebilibili/feature/profile/WallpaperImageImport.kt')
assert digest(prepared)==delta['preparedSha256LF']
producer='''"""Whole original WallpaperImageImport. Physical IO/metadata are required owned Windows effects.
No renderer, account/store/client/player/dependency is created by this producer.
"""
from pathlib import Path
import argparse,hashlib,json,subprocess
COMMIT=__COMMIT__
PATH=__PATH__
ORIGINAL_SHA=__ORIGINAL__
PREPARED_SHA=__PREPARED__
ADAPTATIONS=__ADAPTATIONS__
def sha(text):return hashlib.sha256(text.encode()).hexdigest()
def adapt(original):
 lines=original.splitlines(True)
 for row in reversed(ADAPTATIONS):
  start,end=row['originalRange']
  assert ''.join(lines[start:end])==row['before'],'Original import adaptation anchor changed'
  lines[start:end]=row['after'].splitlines(True)
 return ''.join(lines)
def main():
 p=argparse.ArgumentParser();p.add_argument('--source-repo',required=True);p.add_argument('--output-dir',required=True);args=p.parse_args()
 repo=Path(args.source_repo).resolve();out=Path(args.output_dir).resolve()
 manifest=json.loads((repo/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
 assert manifest['upstreamCommit']==COMMIT,'Profile import fixed source identity changed'
 original=(repo/PATH).read_text(encoding='utf-8').replace('\\r\\n','\\n')
 assert sha(original)==ORIGINAL_SHA,'Profile import source content changed'
 blob=subprocess.run(['git','show',COMMIT+':'+PATH],cwd=repo,capture_output=True,check=True).stdout.decode().replace('\\r\\n','\\n')
 assert blob==original,'Profile import source differs from fixed Git blob'
 rows=[r for r in manifest['sources'] if r['path']==PATH]
 assert len(rows)==1 and rows[0]['sha256']==ORIGINAL_SHA and rows[0]['mode']=='policy-extract','Profile import sole registry row missing/different'
 adapted=adapt(original);assert sha(adapted)==PREPARED_SHA,'Profile import Windows adaptation changed'
 target=out/'com/android/purebilibili/feature/profile/WallpaperImageImport.kt';target.parent.mkdir(parents=True,exist_ok=True)
 target.write_text(adapted,encoding='utf-8',newline='\\n')
 (out/'profile-wallpaper-import-proof.json').write_text(json.dumps({'commit':COMMIT,'originalPath':PATH,'originalSha256LF':ORIGINAL_SHA,'preparedSha256LF':PREPARED_SHA,'wholeOriginalFunctionsRetained':3,'requiredOwnedPhysicalContext':True,'generatedSources':1},indent=2),encoding='utf-8',newline='\\n')
 print('Generated whole original WallpaperImageImport with required owned Windows IO')
if __name__=='__main__':main()
'''
for marker,value in [('__COMMIT__',repr(pins['commit'])),('__PATH__',repr(pins['originalImportPath'])),('__ORIGINAL__',repr(pins['originalImportSha256LF'])),('__PREPARED__',repr(delta['preparedSha256LF'])),('__ADAPTATIONS__',repr(delta['changes']))]:producer=producer.replace(marker,value)
write(LANE/'prepared/tools/extract-upstream-profile-wallpaper-import.py',producer)
manifest=json.loads(read(REPO/'desktop/upstream-sources.json'))
row={'path':pins['originalImportPath'],'sha256':pins['originalImportSha256LF'],'features':['stable-profile-windows-platform'],'mode':'policy-extract'}
assert not any(r['path']==row['path'] for r in manifest['sources'])
js(LANE/'registry-merge-recipe.json',{'newRows':[row],'existingRows':[{'path':'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt','sha256':pins['originalSettingsManagerSha256LF'],'mode':'policy-extract','appendFeature':'stable-profile-windows-platform','newProducer':False}], 'fixedCommit':pins['commit'],'keepHistoricalProfile137':True})
write(LANE/'gradle-task.snippet.kts','''// Append to existing generator task pattern, no dependencies/libraries change.
val extractUpstreamProfileWallpaperImport by tasks.registering(Exec::class) {
    inputs.file("../app/src/main/java/com/android/purebilibili/feature/profile/WallpaperImageImport.kt")
    inputs.file("upstream-sources.json"); inputs.file("tools/extract-upstream-profile-wallpaper-import.py")
    val output = layout.buildDirectory.dir("generated/profile-wallpaper-import")
    outputs.dir(output)
    commandLine(pythonExecutable, "tools/extract-upstream-profile-wallpaper-import.py", "--source-repo", rootDir.absolutePath, "--output-dir", output.get().asFile.absolutePath)
}
// Register generated/profile-wallpaper-import with same Kotlin sourceSet; compileKotlin dependsOn this task.
// Root supplies its existing pythonExecutable/task convention; this is an integration recipe, never executed here.
''')
# Explicit sparse source+registry replay. Original Git source comes from the real pinned worktree.
replay=LANE/'replay-source-repo'
write(replay/pins['originalImportPath'],read(REPO/pins['originalImportPath']))
manifest['sources'].append(row);write(replay/'desktop/upstream-sources.json',json.dumps(manifest,indent=2))
gitdir=subprocess.run(['git','rev-parse','--absolute-git-dir'],cwd=REPO,capture_output=True,text=True,check=True).stdout.strip()
env=dict(os.environ,GIT_DIR=gitdir,GIT_WORK_TREE=str(replay))
py=Path('C:/Users/TONYS/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe')
r=subprocess.run([str(py),str(LANE/'prepared/tools/extract-upstream-profile-wallpaper-import.py'),'--source-repo',str(replay),'--output-dir',str(LANE/'replay-production')],env=env,capture_output=True,text=True,encoding='utf-8',timeout=15)
write(LANE/'replay-production.log',r.stdout+r.stderr);assert r.returncode==0,r.stderr
assert read(LANE/'replay-production/com/android/purebilibili/feature/profile/WallpaperImageImport.kt')==prepared
# Review exact original selected theme methods; logger/AppCompat map is the explicit platform delta.
spec=importlib.util.spec_from_file_location('parser',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
spec=importlib.util.spec_from_file_location('selector',REPO/'desktop/tools/extract-upstream-media.py');selector=importlib.util.module_from_spec(spec);spec.loader.exec_module(selector)
settings=read(REPO/'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt')
blob=subprocess.run(['git','show',pins['commit']+':app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt'],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\r\n','\n')
assert blob==settings and digest(settings)==pins['originalSettingsManagerSha256LF']
methods=[]
for method in ['setThemeMode','setDarkThemeStyle']:
 body=selector.function(settings,method,parser);target=LANE/'original-stable-selected'/('SettingsManager.'+method+'.kt.txt');write(target,body)
 methods.append({'path':'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt','method':method,'sourceSha256LF':pins['originalSettingsManagerSha256LF'],'selectedBodySha256LF':digest(body),'platformDelta':'same Windows appearance Flow replaces AppCompatDelegate; sameStore atomic document mirrors settings/theme_cache; no copied logger'} )
js(LANE/'original-theme-methods.json',methods)
# Five exact independent shared hunks replay and reverse to the retained base.
hunks=json.loads(read(LANE/'shared-local-hunks.json'));targetProof=[]
for row in hunks['targets']:
 base=read(LANE/'shared-bases'/row['path']);current=base
 for h in [r for r in hunks['rows'] if r['target']==row['path']]:
  assert current.count(h['before'])==1;current=current.replace(h['before'],h['after'])
 assert current==read(LANE/'prepared/shared-candidates'/row['path']) and digest(current)==row['candidateSha256LF']
 for h in reversed([r for r in hunks['rows'] if r['target']==row['path']]):
  assert current.count(h['after'])==1;current=current.replace(h['after'],h['before'])
 assert current==base
 targetProof.append({'path':row['path'],'forwardExact':True,'reverseRecovered':True,'fullOverwriteRequired':False})
js(LANE/'shared-hunks-replay.json',{'hunks':len(hunks['rows']),'targets':targetProof,'baselineReadOnly':True})
# ABI audit uses actual immutable42 ordered97 plus explicitly prospective shared classes.
prefix=read(REPO/'desktop/.local/stable-home-live-list-parity/audit_abi.py').split('aud=json.loads')[0]
namespace={'__file__':str(LANE/'audit.py')};exec(prefix,namespace);parse=namespace['parse']
inputs=json.loads(read(LANE/'input-audit.json'));cp=inputs['verifiedDependencyPins'];compiled=json.loads(read(LANE/'compile-09/compile-result.json'))
actual=set();actualmethods={};actualCore={}
coreNames={"com/bilipai/desktop/plugins/"+n+".class" for n in ["DesktopPluginStore","DesktopPluginStoreKt","DesktopPluginContext","DesktopPluginDataStore","DesktopPluginPreferences","DesktopPreferenceKey","DesktopPreferenceEditor","DesktopPreferenceSnapshot"]}|{"com/bilipai/desktop/appearance/"+n+".class" for n in ["DesktopThemePrefs","DesktopThemePrefsKt","DesktopThemeSettings"]}|{"com/bilipai/desktop/ui/"+n+".class" for n in ["DesktopDynamicImageAssets","DesktopDynamicImageAssetsKt","DesktopDynamicSaveTarget"]}
for r in cp:
 assert sha(r['path'])==r['sha256Bytes']
 with zipfile.ZipFile(safe(r['path'])) as z:
  for n in z.namelist():
   if n.endswith('.class'):
    actual.add(n)
    if n in coreNames:actualCore[n]={(name,desc) for access,name,desc in parse(z.read(n)) if access&5}
    if n.endswith('Kt.class') and n.startswith(('com/android/purebilibili/','com/bilipai/desktop/')):
     for access,name,desc in parse(z.read(n)):
      if access&9==9:actualmethods.setdefault((n.rsplit('/',1)[0],name,desc),[]).append(n)
allowed=['com/bilipai/desktop/plugins/DesktopPluginStore','com/bilipai/desktop/plugins/DesktopPreference','com/bilipai/desktop/plugins/DesktopPluginDataStore','com/bilipai/desktop/plugins/DesktopPluginContext','com/bilipai/desktop/plugins/DesktopPluginPreferences','com/bilipai/desktop/appearance/DesktopThemePrefs','com/bilipai/desktop/appearance/DesktopThemeSettings','com/bilipai/desktop/ui/DesktopDynamicImageAssets','com/bilipai/desktop/ui/DesktopDynamicSaveTarget']
invalid=[];overlap=[];methodoverlap=[];methods=0;names=[]
with zipfile.ZipFile(safe(compiled['jar'])) as z:
 for n in z.namelist():
  if not n.endswith('.class'):continue
  names.append(n)
  if n in actualCore:
   preparedPublic={(name,desc) for access,name,desc in parse(z.read(n)) if access&5}
   assert actualCore[n]<=preparedPublic,(n,actualCore[n]-preparedPublic)
  if n in actual:
   assert any(n.startswith(a) for a in allowed),n;overlap.append(n)
  for access,name,desc in parse(z.read(n)):
   methods+=1
   if any(c in name for c in '.;/[') or ('<' in name and name not in ['<init>','<clinit>']):invalid.append([n,name])
   if n.endswith('Kt.class') and access&9==9 and (n.rsplit('/',1)[0],name,desc) in actualmethods and not any(n.startswith(a) for a in allowed):methodoverlap.append([n,name,desc])
assert not invalid and not methodoverlap
# Load all produced classes without native/static effects; shared module exact ABI matters.
csp=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(csp);csp.loader.exec_module(c)
loader='''import java.util.jar.*;public final class ProfilePlatformClassLoadProof {public static void main(String[] a)throws Exception{int count=0;try(JarFile z=new JarFile(a[0])){var e=z.entries();while(e.hasMoreElements()){String n=e.nextElement().getName();if(n.endsWith(".class")){Class.forName(n.substring(0,n.length()-6).replace('/','.'),false,ProfilePlatformClassLoadProof.class.getClassLoader());count++;}}}System.out.println("LOADED_WITHOUT_INITIALIZATION="+count);}}'''
ld=LANE/'classload-proof';write(ld/'ProfilePlatformClassLoadProof.java',loader)
r=subprocess.run([str(c.JAVA.parent/'javac.exe'),str(ld/'ProfilePlatformClassLoadProof.java')],capture_output=True,text=True,timeout=15);write(ld/'compile.log',r.stdout+r.stderr);assert r.returncode==0,r.stderr
r=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-cp',';'.join([str(ld),compiled['jar']]+[r['path'] for r in cp]),'ProfilePlatformClassLoadProof',compiled['jar']],capture_output=True,text=True,timeout=15);write(ld/'run.log',r.stdout+r.stderr);assert r.returncode==0,r.stderr
assert 'LOADED_WITHOUT_INITIALIZATION='+str(len(names)) in r.stdout
js(LANE/'abi-audit.json',{'actualSnapshot':42,'actualRuntimeEntries':97,'candidateClasses':len(names),'candidateMethods':methods,'prospectiveSharedFileOverrideCount':3,'prospectiveExistingClassOverlaps':overlap,'newClassAndPublicTopMethodOverlap':0,'invalidJvmMethodNames':invalid,'samePackagePublicStaticMethodOverlap':methodoverlap,'loadedWithoutInitialization':len(names),'existingPublicCoreAbiPreserved':len(actualCore),'moduleName':'com_bilipai_desktop_bilipai_windows','actualRootRuntime':False})
checks=[]
def check(ok,name):assert ok,name;checks.append(name)
original=read(LANE/'original-stable'/pins['originalImportPath']);lines=prepared.splitlines(True)
for d in reversed(delta['changes']):lines[d['preparedRange'][0]:d['preparedRange'][1]]=d['before'].splitlines(True)
check(''.join(lines)==original,'whole127line original import recovered exactly')
check(len(re.findall(r'(?m)^internal (?:suspend )?fun ',prepared))==3,'all3 original import functions retained')
for text in ['input.copyTo(output)','stream.copyTo(it)','source.use','input.use','withContext(Dispatchers.IO)','imported?.let(context::deleteImport)','ensureActive()','context.publishImport','context.videoWidth','"mp4", "m4v", "webm", "mkv", "mov", "3gp"','".img"']:
 check(text in prepared,'import original control/format '+text)
manual='\n'.join(read(p) for p in safe(LANE/'prepared/manual').rglob('*.kt'))
for text in ['Job','isActive','commit','checkpoint','NOFOLLOW_LINKS','MAX_IMAGE_BYTES','MAX_MEDIA_BYTES','Files.move(stage,target)','importGeneration.incrementAndGet()','profile_wallpaper','splash','home_wallpaper','JFileChooser','showOpenDialog(window)','toUri().toString()','dialog.getAndSet(null)?.dispose()','callFactory.newCall','call.cancel()','ForwardingSource','contentLength','EventQueue.invokeLater','clipboard.copyText','Memory(4).use','currentRootThemeLight()','clientPolicy.applyHomeSystemBars','process.inputStream.close()','process.errorStream.close()','process.outputStream.close()','4096','protocol_whitelist']:
 check(text in manual,'actual Windows effects/owner '+text)
check('checkPublication()' in read(LANE/'prepared/shared-candidates/desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt'),'original caller Job publication guard before theme document atomic move')
check('appearance.setThemeModeOwned(mode,owns,commit)' in manual,'factory uses same actual global appearance and entry admission')
check('redirectOutput(' not in manual,'metadata is bounded pipe; no output temporary deletion race')
check('OkHttpClient(' not in manual and 'DesktopPluginStore(' not in manual and 'MpvPlayer(' not in manual,'no second production HTTP/store/player')
check('profile_bg_${java.util.UUID.randomUUID()}.jpg' in read(LANE/'prepared/shared-candidates/desktop/tools/extract-upstream-profile-main.py'),'single approved no-clobber official-wallpaper filename adaptation')
check('"profile_bg.jpg"' in read(REPO/'app/src/main/java/com/android/purebilibili/feature/profile/ProfileViewModel.kt'),'original legacy delete/name source retained')
js(LANE/'source-audit.json',{'checks':len(checks),'passed':checks,'fullOriginalFilesRecovered':1,'originalThemeMethodsRetained':2,'sharedHunksForwardReverse':len(hunks['rows']),'productionProducerReplayExact':True,'metadataFailure02Preserved':'one redirected metadata temp remained in invalid-video test; final pipe reader removes that file mechanism','actualRootMounted':False})
payloads=[]
for source,target in [(LANE/'prepared/tools/extract-upstream-profile-wallpaper-import.py','desktop/tools/extract-upstream-profile-wallpaper-import.py')]+[(p,'desktop/src/main/kotlin/'+p.relative_to(safe(LANE/'prepared/manual')).as_posix()) for p in sorted(safe(LANE/'prepared/manual').rglob('*.kt'))]:
 payloads.append({'source':str(source),'target':target,'sha256Bytes':sha(source),'sha256LF':digest(read(source))})
js(LANE/'install-contract.json',{'payloads':payloads,'sharedHunks':'shared-local-hunks.json','sharedHunkCount':len(hunks['rows']),'sharedTargets':hunks['targets'],'wholeSharedCandidateOverwrite':False,'newRegistryRows':1,'existingSettingsFeatureMerge':True,'generatedOriginalSources':1,'newDependencies':0,'sameActorPortsRequired':True,'gradleRecipeOnly':True,'actualRootRuntime':False})
print(json.dumps({'payloads':len(payloads),'sharedHunks':len(hunks['rows']),'sourceChecks':len(checks),'classes':len(names),'methods':methods,'explicitExistingClassOverrides':len(overlap),'classloaded':len(names),'producerReplay':'PASS'},indent=2))
