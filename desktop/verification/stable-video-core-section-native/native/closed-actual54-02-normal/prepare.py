"""Prepare exact fixture-only caller changes; never compile or run a prospective product graph."""
from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8')
lane=Path(__file__).resolve().parent
old=lane.parent/'stable-offline-native-integration-proof'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
base=old/'runs/actual50-03-normal/OfflineNativeFixture.kt'
proofpins=json.loads((base.parent/'pins-before.json').read_text(encoding='utf-8'))
assert sha(base)==proofpins['source']['sha256Bytes']
body=base.read_text(encoding='utf-8')
hunks=[
('remember actual sole Root controller',
 '                val presentation=rememberDesktopWindowsDanmakuPresentation(actualWindow,state)\n',
 '                val presentation=rememberDesktopWindowsDanmakuPresentation(actualWindow,state)\n                val fullscreenControl=rememberDesktopWindowsFullscreenControl(actualWindow,state)\n'),
('delegate original callback to the same Root controller',
 '                                val target=if(fullscreen)WindowPlacement.Fullscreen else WindowPlacement.Floating\n                                if(rootWindowState.placement!=target){rootWindowState.placement=target;fullscreenCalls++}\n',
 '                                val beforePlacement=rootWindowState.placement\n                                fullscreenControl.setFullscreen(fullscreen)\n                                if(rootWindowState.placement!=beforePlacement)fullscreenCalls++\n'),
('record actual new controller class origin',
 '                    for(name in listOf("com.bilipai.desktop.ui.DesktopOriginalOfflineRootHostKt",',
 '                    for(name in listOf("com.bilipai.desktop.ui.DesktopWindowsFullscreenControlKt","com.bilipai.desktop.ui.DesktopWindowsFullscreenControl","com.bilipai.desktop.ui.DesktopOriginalOfflineRootHostKt",'),
]
changes=[]
for name,before,after in hunks:
    assert body.count(before)==1,(name,body.count(before))
    body=body.replace(before,after,1)
    changes.append({'name':name,'expectedCount':1,'before':before,'after':after})
assert 'rootWindowState.placement=' not in body.replace('rootWindowState.placement==','')
(lane/'OfflineNativeFixture.kt').write_text(body,encoding='utf-8',newline='\n')
runner=(old/'run.py').read_text(encoding='utf-8')
before="runtime_sources=['com/bilipai/desktop/ui/DesktopOriginalOfflineRootHostKt.class'"
after="runtime_sources=['com/bilipai/desktop/ui/DesktopWindowsFullscreenControlKt.class','com/bilipai/desktop/ui/DesktopWindowsFullscreenControl.class','com/bilipai/desktop/ui/DesktopOriginalOfflineRootHostKt.class'"
runnerHunks=[('require actual Root controller classes',before,after),
 ('Root-delivered exact runtime count','assert args.entries==97','assert args.snapshot>=51 and args.entries>0'),
 ('preserve exact-count receipt without historical97 label',"'all97PinsBeforeAfter':True","'allExpectedRuntimePinsBeforeAfter':True"),
 ('separate controls from external visual acceptance',"'fullRequestedScopeAccepted':proof['status']=='PASS'","'fullRequestedScopeAccepted':False,'logicAndNativeControlScopeCompleted':proof['status']=='PASS','visualAcceptanceRequiresRootSkyReceipt':True")]
runnerChanges=[]
for name,before,after in runnerHunks:
    assert runner.count(before)==1,(name,runner.count(before))
    runner=runner.replace(before,after,1)
    runnerChanges.append({'name':name,'expectedCount':1,'before':before,'after':after})
(lane/'run.py').write_text(runner,encoding='utf-8',newline='\n')
(lane/'fixture-delta.json').write_text(json.dumps({'fixtureOnly':True,'productionOverrides':0,'baseSource':{'path':str(base),'sha256Bytes':sha(base)},'hunks':changes,'runnerHunks':runnerChanges},ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
data={'status':'PREPARED_NOT_COMPILED_NOT_RUN','expectedActualSnapshot':51,'requiredRuntimeEntries':'Root-delivered exact count, matched to every ordered CP row','productionOverrides':0,'needsRootPinsAndWindowSlot':True,'sameActualMainFullscreenControlRequired':True,'historicalReceiptsModified':False,'artifacts':[{'path':str(p),'sha256Bytes':sha(p),'size':p.stat().st_size} for p in [lane/'OfflineNativeFixture.kt',lane/'run.py',lane/'fixture-delta.json',Path(__file__)]]}
(lane/'prepared-manifest.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({'path':str(lane/'prepared-manifest.json'),'sha256Bytes':sha(lane/'prepared-manifest.json'),'fixtureSourceSHA':sha(lane/'OfflineNativeFixture.kt')},ensure_ascii=False,indent=2))
