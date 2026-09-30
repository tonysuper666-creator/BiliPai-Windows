"""Read-only product review; stage exact changes under this isolated lane only."""
from pathlib import Path
import argparse, copy, difflib, hashlib, importlib.util, json, re, sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
FROZEN=HERE.parent/'settings-network-proxy-parity'
PIN='f7352d7499f02891077c228238a2e6b6853db4efc46e676af9ad9785c8602a67'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def digest(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def dump(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
def load(p,name):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def once(s,before,after):
 if s.count(before)!=1:raise ValueError('Expected unique exact source seam: '+before[:100])
 return s.replace(before,after)
def check_unique(entries):
 paths=[v['path'] for v in entries]
 assert len(paths)==len(set(paths)),'Duplicate manifest source/resource identities'
def merge_entries(old,new,kind):
 check_unique(old);result=copy.deepcopy(old);by={v['path']:v for v in result}
 operations=[]
 for item in new:
  existing=by.get(item['path'])
  if existing:
   assert existing['sha256']==item['sha256'],item['path']
   if kind=='sources' and existing.get('mode','direct')!=item['mode']:
    # ApiClient already has the canonical api-extract identity: add the feature,
    # retain that mode, and let this independent extractor select its two pure bodies.
    assert item['path']=='app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt'
    assert existing['mode']=='api-extract' and item['mode']=='policy-extract'
   existing['features']=list(dict.fromkeys(existing.get('features',[])+item.get('features',[])))
   operations.append(dict(path=item['path'],operation='merge-features-only',mode=existing.get('mode')))
  else:
   result.append(copy.deepcopy(item));by[item['path']]=result[-1]
   operations.append(dict(path=item['path'],operation='append-unique',mode=item.get('mode')))
 check_unique(result);return result,operations

def prepare():
 assert digest(FROZEN/'verified-artifacts.json')==PIN,'Frozen artifact manifest changed'
 frozen=json.loads(read(FROZEN/'verified-artifacts.json'))
 assert frozen['artifactCount']==281 and len(frozen['artifacts'])==281
 for item in frozen['artifacts']:assert digest(FROZEN/item['path'])==item['sha256Bytes'],item['path']
 deps=json.loads(read(FROZEN/'dependency-identities.json'))
 for item in deps:assert digest(item['path'])==item['sha256Bytes'],item['path']
 originals=json.loads(read(FROZEN/'source-inventory.json'))
 resources=json.loads(read(FROZEN/'resource-inventory.json'))
 for item in originals+resources:
  assert hashlib.sha256(read(REPO/item['path']).encode()).hexdigest()==item['sha256'],item['path']
 platform=json.loads(read(FROZEN/'existing-platform-provenance.json'))
 for item in platform:assert digest(REPO/item['path'])==item['sha256Bytes'],item['path']
 previous_baselines=[json.loads(read(FROZEN/'repository-baseline.json'))]
 previous_baselines+=json.loads(read(FROZEN/'media-caller-baselines.json'))
 for item in previous_baselines:assert digest(REPO/item['path'])==item['baselineSha256Bytes'],item['path']
 startup=json.loads(read(FROZEN/'startup-baselines.json'))
 startup_comparison=[]
 for item in startup:
  actual=digest(REPO/item['path'])
  startup_comparison.append(dict(**item,currentSha256Bytes=actual,baselineStillMatches=actual==item['sha256Bytes']))
  if item['path'].endswith('/DesktopShell.kt'):continue # Exact current Tree/restore/backup integration is reviewed below.
  assert actual==item['sha256Bytes'],item['path']
 before_paths=[item['path'] for item in previous_baselines]+[item['path'] for item in startup]
 before_paths+=['desktop/build.gradle.kts','desktop/upstream-sources.json','desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsTree.kt']
 baseline={p:digest(REPO/p) for p in before_paths}
 tool=load(FROZEN/'extract-upstream-network-proxy.py','review_proxy_extractor')
 assert tool.inventory(REPO)==originals and tool.resources(REPO)==resources
 generated=tool.generate(REPO,HERE/'generated-review',False)
 assert len(generated)==4,'Product mode must not emit duplicate direct policy'
 expected={p.relative_to(FROZEN/'product-generated').as_posix():p for p in (FROZEN/'product-generated').rglob('*.kt')}
 generated_entries=[]
 for p in generated:
  rel=p.relative_to(HERE/'generated-review').as_posix()
  assert rel in expected and digest(p)==digest(expected[rel]),rel
  generated_entries.append(dict(path=rel,sha256Bytes=digest(p)))
 assert {i['path'] for i in generated_entries}==set(expected)
 # Do not copy helper fixtures, old Repository override, reference-only Diagnostics or compiled classes.
 payloads={}
 new_files={
  'desktop/tools/extract-upstream-network-proxy.py':'extract-upstream-network-proxy.py',
  'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopNetworkProxyBindings.kt':'DesktopNetworkProxyBindings.kt',
  'desktop/src/main/kotlin/com/bilipai/desktop/network/DesktopNetworkProxyFailure.kt':'DesktopNetworkProxyFailure.kt'}
 for dest,origin in new_files.items():
  assert not safe(REPO/dest).exists(),dest
  payloads[dest]=safe(FROZEN/origin).read_bytes()
 repo='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt'
 s=read(REPO/repo)
 s=once(s,'private val client = OkHttpClient.Builder()',
  'private val client = OkHttpClient.Builder()\n        .proxySelector(com.bilipai.desktop.network.DesktopNetworkProxyPlatform.buildAppProxySelector())')
 seam='    internal val httpClient: OkHttpClient get() = client'
 s=once(s,seam,seam+'\n    private val playbackClient by lazy { com.bilipai.desktop.network.DesktopNetworkProxyPlatform.buildPlaybackOkHttpClient(client) }\n    internal val playbackHttpClient: OkHttpClient get() = playbackClient')
 assert hashlib.sha256(s.encode()).hexdigest()==previous_baselines[0]['desiredSha256Lf']
 payloads[repo]=s.encode()
 media_changes=[
  ('desktop/src/main/kotlin/com/bilipai/desktop/download/DesktopDownloadManager.kt','this(repository.httpClient, defaultStateFile()', 'this(repository.playbackHttpClient, defaultStateFile()'),
  ('desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginServices.kt','val playbackClient: OkHttpClient get() = active().repository.httpClient','val playbackClient: OkHttpClient get() = active().repository.playbackHttpClient')]
 for dest,a,b in media_changes:
  s=once(read(REPO/dest),a,b);assert hashlib.sha256(s.encode()).hexdigest()==next(i['desiredSha256Lf'] for i in previous_baselines if i['path']==dest)
  payloads[dest]=s.encode()
 main='desktop/src/main/kotlin/com/bilipai/desktop/Main.kt'
 s=read(REPO/main)
 s=once(s,'    application {\n        val repository = remember { DesktopRepository() }',
  '    application {\n        val applicationPluginStore = remember {\n            com.bilipai.desktop.plugins.DesktopPluginStore(DesktopLibrary.directoryForAccount(null)).also { store ->\n                com.android.purebilibili.core.store.NetworkProxyStore.init(com.bilipai.desktop.plugins.DesktopPluginContext(store))\n            }\n        }\n        val repository = remember { DesktopRepository() }')
 s=once(s,'hostWindow = window, registerShutdown = shutdown::set, onRestart = { closeApp(restart = true) })',
  'hostWindow = window, registerShutdown = shutdown::set, onRestart = { closeApp(restart = true) },\n                applicationPluginStore = applicationPluginStore)')
 payloads[main]=s.encode()
 shell='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
 s=read(REPO/shell)
 s=once(s,'registerShutdown: ((suspend () -> Unit) -> Unit)? = null, onRestart: (() -> Unit)? = null) {',
  'registerShutdown: ((suspend () -> Unit) -> Unit)? = null, onRestart: (() -> Unit)? = null,\n    applicationPluginStore: DesktopPluginStore? = null) {')
 s=once(s,'    val pluginStore = remember { DesktopPluginStore(DesktopLibrary.directoryForAccount(null)) }',
  '    val pluginStore = remember(applicationPluginStore) {\n        (applicationPluginStore ?: DesktopPluginStore(DesktopLibrary.directoryForAccount(null))).also { store ->\n            com.android.purebilibili.core.store.NetworkProxyStore.init(com.bilipai.desktop.plugins.DesktopPluginContext(store))\n        }\n    }')
 seam='                                systemContent = {\n'
 s=once(s,seam,seam+'                                    com.bilipai.desktop.settings.DesktopNetworkProxySettings(globalPluginContext, repository.httpClient,\n                                        onFailure = { error = it.message ?: "代理设置保存失败" })\n')
 # Preserve every current Tree/restore/search/combined backup change outside the three narrow seams.
 payloads[shell]=s.encode()
 build='desktop/build.gradle.kts';s=read(REPO/build)
 task='''val extractUpstreamNetworkProxy by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-network-proxy.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/network-proxy").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-network-proxy.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-upstream-api.py",
        "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-network-proxy-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    inputs.files(originalResources.filter { "settings-network-proxy-symbols" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/network-proxy"))
}

'''
 assert 'extractUpstreamNetworkProxy' not in s and 'generated/network-proxy' not in s
 s=once(s,'val extractNativeMusicRoot by tasks.registering(Exec::class) {',task+'val extractNativeMusicRoot by tasks.registering(Exec::class) {')
 s=once(s,'    kotlin.srcDir(layout.buildDirectory.dir("generated/native-music-root"))',
  '    kotlin.srcDir(layout.buildDirectory.dir("generated/native-music-root"))\n    kotlin.srcDir(layout.buildDirectory.dir("generated/network-proxy"))')
 seam='tasks.named("compileKotlin") { dependsOn(extractUpstreamComponents, extractUpstreamPreferences) }'
 s=once(s,seam,seam+'\ntasks.named("compileKotlin") { dependsOn(extractUpstreamNetworkProxy) }')
 payloads[build]=s.encode()
 manifest_path='desktop/upstream-sources.json';manifest=json.loads(read(REPO/manifest_path))
 assert manifest['hashNormalization']=='lf'
 merged=copy.deepcopy(manifest)
 merged['sources'],source_merges=merge_entries(manifest['sources'],originals,'sources')
 merged['resources'],resource_merges=merge_entries(manifest['resources'],resources,'resources')
 assert len(merged['sources'])==len(manifest['sources'])+2
 assert len(merged['resources'])==len(manifest['resources'])+2
 payloads[manifest_path]=(json.dumps(merged,ensure_ascii=False,indent=2)+'\n').encode()
 # Current generated graph cannot already own these FQNs; ignore source path copies of unrelated direct types.
 current_declarations=[]
 for top in [REPO/'desktop/src/main/kotlin',REPO/'desktop/build/generated']:
  for p in top.rglob('*.kt'):
   body=read(p)
   if re.search(r'\b(object|class|fun)\s+(NetworkProxyStore|AppHttpProxySettings|DesktopNetworkProxyFields|DesktopProxySettingsVectors|DesktopNetworkProxyPlatform)\b',body):
    current_declarations.append(str(p.relative_to(REPO)))
 assert not current_declarations,current_declarations
 stage=HERE/'payload';stage.mkdir(exist_ok=True)
 entries=[];patch=[]
 for dest,blob in payloads.items():
  before=safe(REPO/dest).read_bytes() if safe(REPO/dest).exists() else None
  if before and b'\r\n' in before:blob=blob.decode().replace('\n','\r\n').encode()
  out=stage/dest;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(blob)
  entries.append(dict(path=dest,payloadSha256Bytes=hashlib.sha256(blob).hexdigest(),
                     baselineSha256Bytes=hashlib.sha256(before).hexdigest() if before is not None else None))
  patch+=list(difflib.unified_diff((before or b'').decode().replace('\r\n','\n').splitlines(True),
     blob.decode().replace('\r\n','\n').splitlines(True),fromfile='a/'+dest if before else '/dev/null',tofile='b/'+dest))
 (HERE/'integration.patch').write_text(''.join(patch),encoding='utf-8',newline='\n')
 frozen_checks=[dict(path=str(FROZEN/'verified-artifacts.json'),sha256Bytes=PIN)]
 frozen_checks+=[dict(path=str(FROZEN/i['path']),sha256Bytes=i['sha256Bytes']) for i in frozen['artifacts']]
 dump(HERE/'install-plan.json',dict(schemaVersion=1,frozenArtifactManifestSha256=PIN,
  files=entries,frozenChecks=frozen_checks,dependencies=deps,
  originalInputs=originals+resources,platformInputs=platform,
  generatedProductOutputs=generated_entries,
  sourceMerge=source_merges,resourceMerge=resource_merges,
  futureRequiresRootProductCompile=True,preparationModifiedMain=False))
 assert baseline=={p:digest(REPO/p) for p in before_paths},'Main changed during read-only preparation'
 dump(HERE/'review-evidence.json',dict(passed=True,frozenArtifactsVerified=281,dependenciesVerified=len(deps),
  originalSourceInputsVerified=4,originalResourceInputsVerified=2,generatedProductOutputsExactFrozenMatch=4,
  currentProductDeclarationCollisionCount=0,stageFileCount=len(entries),startupBaselineComparison=startup_comparison,
  currentMainBaselines=baseline,sourceMerge=source_merges,resourceMerge=resource_merges,
  mainModified=False,sharedGradleInvoked=False,nativeWindowCreated=False,networkRequested=False))
 print(json.dumps(dict(passed=True,frozen=281,dependencyCount=len(deps),stagedFiles=len(entries),
  newOriginalSources=2,newResourceIdentities=2,noMainEdits=True)))
if __name__=='__main__':prepare()
