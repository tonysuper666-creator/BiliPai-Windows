from pathlib import Path
import difflib, hashlib, importlib.util, json, os, subprocess, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    p=str(p);return p if p.startswith(PREFIX) else PREFIX+p
def sha(p):
    with open(safe(p),'rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def read(p):
    with open(safe(p),encoding='utf-8-sig') as f:return f.read()
def write(p,s):
    with open(safe(p),'w',encoding='utf-8',newline='\n') as f:f.write(s)
def dump(p,s):write(p,json.dumps(s,indent=2,ensure_ascii=False)+'\n')

tool=HERE/'prepared/desktop/tools/extract-image-save-settings-ui.py'
spec=importlib.util.spec_from_file_location('image_settings',tool);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
inventory=mod.inventory(REPO)
commit=subprocess.run(['git','rev-parse','v0.2.3-alpha.9^{commit}'],cwd=REPO,capture_output=True,text=True,encoding='utf-8',check=True).stdout.strip()
assert commit=='fcf84853b287662e8a9129ea0d38576c36522a34'
for row in inventory:
    original=subprocess.run(['git','show',commit+':'+row['path']],cwd=REPO,capture_output=True,text=True,encoding='utf-8',check=True).stdout.replace('\r\n','\n')
    assert mod.read(REPO,row['path'])==original,row['path']
    row['originalCommit']=commit;row['originalBodyLfSha256']=hashlib.sha256(original.encode()).hexdigest()
dump(HERE/'original-source-inventory.json',inventory)

relative='desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsTree.kt'
base=read(REPO/relative).replace('\r\n','\n')
desired=base.replace('    systemContent: @Composable () -> Unit,\n','''    systemContent: @Composable () -> Unit,
    imageSavePathContent: @Composable (openInitially: Boolean) -> Unit = {
        AppText("图片保存位置设置仍在移植中。", Modifier.padding(12.dp))
    },
''')
assert desired!=base
anchor='''                                            onWebDavBackupClick = { navigator.openDetail(SettingsSearchTarget.WEBDAV_BACKUP, null) })
'''
assert desired.count(anchor)==1
desired=desired.replace(anchor,anchor+'                                        imageSavePathContent(false)\n')
anchor='''                                    SettingsSearchTarget.BLOCKED_LIST -> blockedListContent()
'''
assert desired.count(anchor)==1
desired=desired.replace(anchor,anchor+'                                    SettingsSearchTarget.IMAGE_SAVE_PATH -> imageSavePathContent(true)\n')
patch=''.join(difflib.unified_diff(base.splitlines(True),desired.splitlines(True),fromfile='a/'+relative,tofile='b/'+relative))
write(HERE/'consumer.patch',patch)
dump(HERE/'consumer-baseline.json',{'target':relative,'baseSha256BytesAtAudit':sha(REPO/relative),'baseLfSha256':hashlib.sha256(base.encode()).hexdigest(),'desiredLfSha256':hashlib.sha256(desired.encode()).hexdigest(),'patchSha256Bytes':sha(HERE/'consumer.patch'),'fullFileReplacementProvided':False,'requiresRootShellManualHook':True})

refs=[
 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsTree.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsNavigator.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDynamicCardHost.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSpaceOverviewScreens.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSpaceScreens.kt',
]
dump(HERE/'current-consumer-read-only-pins.json',[{'path':p,'sha256BytesAtAudit':sha(REPO/p)} for p in refs])
dump(HERE/'install-payload.json',{
 'sourceOnlyTargets':[
  {'prepared':'prepared/desktop/tools/extract-image-save-settings-ui.py','target':'desktop/tools/extract-image-save-settings-ui.py','status':'new','sha256Bytes':sha(tool)},
  {'prepared':'prepared/desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSavePathSettings.kt','target':'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSavePathSettings.kt','status':'new','sha256Bytes':sha(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSavePathSettings.kt')}],
 'existingOriginalIdentitiesMerged':2,'newOriginalIdentities':0,'resourcesAdded':[],
 'sharedPreferenceProducer':'static-save-location-next/evidence-manifest.json 34a233ab8d023a65e1e1f4851dc192f66f2f8728fb4fbc865fe332fd55780cbf',
 'generatedCount':2,'consumerPatch':'consumer.patch','shellPatch':'manual snippet ROOT-INTEGRATION.txt only',
 'producerDuplicatesMainFqn':False,'actualMainIntegrated':False,
})

artifacts=[];runtime=[]
for folder,_,names in os.walk(safe(HERE)):
 for name in names:
    p=Path(str(Path(folder)/name)[len(PREFIX):]);rel=str(p.relative_to(HERE)).replace('\\','/')
    if rel=='evidence-manifest.json':continue
    row={'path':rel,'sizeBytes':os.stat(safe(p)).st_size,'sha256Bytes':sha(p)}
    if p.suffix.lower() in ('.jar','.dll','.class') or rel.startswith('task-store/'):
        row['reason']='task compiled classes/store only, do not install or commit';runtime.append(row)
    else:artifacts.append(row)
artifacts.sort(key=lambda p:p['path']);runtime.sort(key=lambda p:p['path'])
dump(HERE/'evidence-manifest.json',{'schema':'image-save-settings-source-only-candidate-v1','frozen':True,'artifactCount':len(artifacts),'artifacts':artifacts,'evidenceOnlyRuntimeCount':len(runtime),'evidenceOnlyRuntimeArtifacts':runtime,'proof':{'main03Immutable92CpVerifiedBeforeAfter':True,'sourceCompileCount':5,'productFqnOverlap':0,'actualProductStoreActionCases':5,'realChooser':False,'uiPointerOrComposition':False,'actualMainIntegrated':False},'pending':['actual Main install/consumer combine','UI pointer and real chooser','global preference/default/custom target consumer integration','Windows avatar preview/save','all original storage settings'],'mainEdited':False,'sharedGradle':False,'accountRequests':False,'userDirectoryFilesWritten':False})
for p in artifacts+runtime:assert sha(HERE/p['path'])==p['sha256Bytes']
print(json.dumps({'rawArtifacts':len(artifacts),'runtimeArtifactsExcluded':len(runtime),'manifestSha256':sha(HERE/'evidence-manifest.json'),'payload':json.loads(read(HERE/'install-payload.json'))['sourceOnlyTargets']}))
