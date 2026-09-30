from pathlib import Path
import subprocess,sys
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if(p/'.git').exists())
LANE=REPO/'desktop/.local/dynamic-follow-observer-parity'
source=(LANE/'source-contract.py').read_text(encoding='utf-8')
source=source.replace("HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]","HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if(p/'.git').exists());LANE=REPO/'desktop/.local/dynamic-follow-observer-parity'")
source=source.replace("app=HERE/'original/app/src/main/java/com/android/purebilibili'","app=REPO/'app/src/main/java/com/android/purebilibili'")
source=source.replace("generated=HERE/'candidate/generated/com/android/purebilibili'","generated=REPO/'desktop/build/generated/dynamic-follow/com/android/purebilibili'")
source=source.replace("before=read(HERE/'baseline/desktop/build/generated'","before=read(LANE/'baseline/desktop/build/generated'")
source=source.replace("after=read(generated/'data/repository'/filename)","after=read(REPO/'desktop/build/generated'/folder/'com/android/purebilibili/data/repository'/filename)")
source=source.replace("candidate=HERE/'candidate/desktop/src/main/kotlin/com/bilipai/desktop'","candidate=REPO/'desktop/src/main/kotlin/com/bilipai/desktop'")
source=source.replace("HERE/'baseline/desktop/src/main/kotlin/","LANE/'baseline/desktop/src/main/kotlin/")
source=source.replace("sha(HERE.parent/'dynamic-editor-main-product-snapshot-01/manifest.json')","sha(HERE/'main-product-snapshot-01/manifest.json')")
source=source.replace('MainIntegrated=False','MainIntegrated=True')
anchor='output=dict(passed=True,checks=checks,'
extra='''shell=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt')
check('Root actual Compose epoch keyed collector has no IO dispatcher',shell.count('LaunchedEffect(dynamicCardSession, dynamicCardRegistry) {\\n        dynamicCardSession.observeFollowStateChanges(dynamicCardRegistry)\\n    }')==1)
community=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt')
check('Root currentAll sole cache binding retained','onAllTimelineChanged={rows->if(cardRegistry.isCurrentAll(model))cache.saveTimeline(rows)}'in community and 'cardRegistry.register(users)'in community and '.also(cardRegistry::register)'in community)
registrySource=__import__('json').loads(read(REPO/'desktop/upstream-sources.json'))
check('three original identities use the existing registry without duplicate paths',len({r['path']for r in registrySource['sources']})==len(registrySource['sources']) and sum('dynamic-follow-observer-parity'in r.get('features',[])for r in registrySource['sources'])==3)
'''
assert source.count(anchor)==1
source=source.replace(anchor,extra+anchor)
target=HERE/'source-contract-main.py';target.write_text(source,encoding='utf-8',newline='\n')
subprocess.run([sys.executable,str(target)],cwd=REPO,check=True)
