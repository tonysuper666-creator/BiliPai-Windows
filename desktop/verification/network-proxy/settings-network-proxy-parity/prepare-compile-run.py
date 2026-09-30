from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import sys
import textwrap

sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
CAT=HERE.parent/'settings-category-ui-parity'
SNAPSHOT=HERE.parent/'fsr-manager-guard-proof'

def load(path,name):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
def safe(path):return Path('\\\\?\\'+str(path.absolute()))
def write(path,text):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8',newline='\n')

tool=load(HERE/'extract-upstream-network-proxy.py','proxy')
sources=tool.generate(REPO,HERE/'generated',True)
tool.generate(REPO,HERE/'product-generated',False)
write(HERE/'source-inventory.json',json.dumps(tool.inventory(REPO),indent=2))
write(HERE/'resource-inventory.json',json.dumps(tool.resources(REPO),indent=2))
# Exact current production Repository source with only the proposed selector/media-client seams.
repository_path='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt'
original_repository=(REPO/repository_path).read_text(encoding='utf-8').replace('\r\n','\n')
repository=original_repository
assert repository.count('private val client = OkHttpClient.Builder()')==1
repository=repository.replace('private val client = OkHttpClient.Builder()',
    'private val client = OkHttpClient.Builder()\n        .proxySelector(com.bilipai.desktop.network.DesktopNetworkProxyPlatform.buildAppProxySelector())')
needle='    internal val httpClient: OkHttpClient get() = client'
assert repository.count(needle)==1
repository=repository.replace(needle,needle+'\n    private val playbackClient by lazy { com.bilipai.desktop.network.DesktopNetworkProxyPlatform.buildPlaybackOkHttpClient(client) }\n    internal val playbackHttpClient: OkHttpClient get() = playbackClient')
file=HERE/'repository-draft'/Path(repository_path).name;write(file,repository);sources.append(file)
import difflib
write(HERE/'repository-integration.patch',''.join(difflib.unified_diff(original_repository.splitlines(True),repository.splitlines(True),fromfile='a/'+repository_path,tofile='b/'+repository_path)))
write(HERE/'repository-baseline.json',json.dumps(dict(path=repository_path,baselineSha256Bytes=hashlib.sha256((REPO/repository_path).read_bytes()).hexdigest(),baselineSha256Lf=hashlib.sha256(original_repository.encode()).hexdigest(),desiredSha256Lf=hashlib.sha256(repository.encode()).hexdigest(),sourceOverrideNotMainEdit=True),indent=2))
dependencies=json.loads((SNAPSHOT/'dependency-identities.json').read_text(encoding='utf-8'))
for item in dependencies:
    assert hashlib.sha256(safe(Path(item['path'])).read_bytes()).hexdigest()==item['sha256Bytes'],item['path']
cp=[item['path'] for item in dependencies]
write(HERE/'dependency-identities.json',json.dumps(dependencies,indent=2))
# Reuse only the already-frozen original category source/helper graph, never its fixture classes.
for name in ['SettingsRootCategoryList.kt','SettingsCategoryHeader.kt','SettingsVisualSpec.kt','DesktopSettingsCategoryVectors.kt']:
    target=next(path for path in (CAT/'generated').rglob('*.kt') if path.name==name)
    desired=target.read_text(encoding='utf-8')
    if name=='SettingsRootCategoryList.kt':
        assert desired.count('private fun SettingsAdaptiveDivider()')==1
        desired=desired.replace('private fun SettingsAdaptiveDivider()','internal fun SettingsAdaptiveDivider()')
    file=HERE/'fixture-category-helpers'/name;write(file,desired);sources.append(file)
names={'SettingsSearchPolicy.kt','SettingsSearchFocusPolicy.kt','SettingsRootCategoryPolicy.kt','SettingsDestinationCopy.kt',
    'SettingsSiblingIconPalettePolicy.kt','SettingsSemanticIconPolicy.kt','SettingsEntryVisualPolicy.kt','DesktopSettingsVectors.kt','PinyinUtils.kt'}
for original_helper in (CAT/'fixture-helper-generation').rglob('*.kt'):
    if original_helper.name not in names:continue
    relative=original_helper.relative_to(CAT/'fixture-helper-generation')
    selected=HERE/'fixture-search-helpers'/relative
    write(selected,original_helper.read_text(encoding='utf-8'))
    sources.append(selected)
sources += [HERE/'DesktopNetworkProxyBindings.kt',HERE/'DesktopNetworkProxyFailure.kt',HERE/'ProxyFixture.kt',HERE/'ProxyUiFixture.kt']
compiler=load(HERE.parent/'source9-appearance/compile-miuix.py','compiler')
output=HERE/'classes-cohort';output.mkdir(exist_ok=True)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(compiler.PLUGIN),
    '-Xfriend-paths='+cp[0],'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(output),*map(str,sources)]
file=HERE/'compiler.args';write(file,'\n'.join('"'+str(arg).replace('\\','/')+'"' for arg in args))
run=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(HERE/'compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-5000:]);run.check_returncode()
runtime=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false','-cp',str(output)+';'+';'.join(cp),
    'com.bilipai.desktop.network.ProxyFixtureKt',str(HERE/'proof')]
file=HERE/'runtime.args';write(file,'\n'.join('"'+arg.replace('\\','/')+'"' for arg in runtime))
try:
 run=subprocess.run([str(compiler.JAVA),'@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
except subprocess.TimeoutExpired as error:
 write(HERE/'runtime-failure.log',(error.stdout or b'').decode('utf-8','replace') if isinstance(error.stdout,bytes) else str(error.stdout or ''))
 raise
write(HERE/'runtime.log',run.stdout+run.stderr);print(run.stdout+run.stderr);run.check_returncode()
ui_runtime=runtime.copy()
ui_runtime[ui_runtime.index('com.bilipai.desktop.network.ProxyFixtureKt')]='com.bilipai.desktop.settings.ProxyUiFixtureKt'
ui_runtime[-1]=str(HERE/'ui-proof')
ui_file=HERE/'ui-runtime.args';write(ui_file,'\n'.join('"'+arg.replace('\\','/')+'"' for arg in ui_runtime))
ui=subprocess.run([str(compiler.JAVA),'@'+str(ui_file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
write(HERE/'ui-runtime.log',ui.stdout+ui.stderr);print(ui.stdout+ui.stderr);ui.check_returncode()
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,compiledSourceCount=len(sources),
    originalProxyFieldsUiCompiled=True,actualCurrentRepositorySourceOverride=True,activeClassDirectory='classes-cohort',priorAttemptClassDirectory='classes',actualPointerSwitchesTestedBothStyles=True,addressDialogClicked=False,
    productSnapshotSha256=dependencies[0]['sha256Bytes'],sharedGradleInvoked=False,nativeWindowCreated=False),indent=2))
