from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-home-windows-prefs-ports-parity';OUT=HERE/'windows-home-prefs-install'
assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
frozen=json.loads((LANE/'frozen-handoff.json').read_text(encoding='utf-8'))
for r in frozen['artifacts']:
 assert sha((LANE/r['path']).read_bytes())==r['sha256Bytes'],r['path']
cpp=REPO/'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp'
assert cpp.read_bytes()==(LANE/'native-candidate/DesktopDiagnosticShare-base.cpp').read_bytes()
subprocess.run(['git','apply','--check',str(LANE/'native-local.patch')],cwd=REPO,check=True)
subprocess.run(['git','apply',str(LANE/'native-local.patch')],cwd=REPO,check=True)
assert cpp.read_bytes().replace(b'\r\n',b'\n')==(LANE/'native-candidate/DesktopDiagnosticShare.cpp').read_bytes().replace(b'\r\n',b'\n')
local=json.loads((LANE/'sole-classification-producer-hunk.json').read_text(encoding='utf-8'))
p=REPO/'desktop/tools/extract-upstream-dynamic-detail-container.py';base=p.read_bytes()
assert sha(base.replace(b'\r\n',b'\n'))==local['baseSha256LF']
s=base.decode().replace('\r\n','\n');assert s.count(local['before'])==1
p.write_text(s.replace(local['before'],local['after']),encoding='utf-8',newline='\n')
destination=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeWindowsPreferencesPlatform.kt'
assert not destination.exists();destination.write_bytes((LANE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeWindowsPreferencesPlatform.kt').read_bytes())
manifest=REPO/'desktop/upstream-sources.json';registry=json.loads(manifest.read_text(encoding='utf-8'))
merge=json.loads((LANE/'registry-merge-recipe.json').read_text(encoding='utf-8'))
rows=[r for r in registry['sources']if r['path']==merge['path']];assert len(rows)==1 and rows[0]['sha256']==merge['sha256LF']
assert 'home-windows-prefs-ports'not in rows[0]['features'];rows[0]['features'].append('home-windows-prefs-ports')
manifest.write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
# This source review supplements the existing same-DLL development admission, including its fresh
# resolved SDK/STL/tool/library graph. The producer still rebuilds and verifies the DLL every run.
approvalPath=REPO/'desktop/native/diagnostic-share/approved-development-build.json';approval=json.loads(approvalPath.read_text(encoding='utf-8'))
(OUT/'approval-before.json').write_bytes(approvalPath.read_bytes())
graph=json.loads((LANE/'runs/native-compile-01/producer-input-graph.json').read_text(encoding='utf-8'))
assert graph['passed']and sha(cpp.read_bytes())==graph['source']['sha256Bytes']
assert sha((REPO/'desktop/tools/compile-native-diagnostic-share.py').read_bytes())==approval['controlledCompilerScriptSha256Bytes']
assert graph['command'][1:10]==approval['flags']
vc=Path(graph['command'][0]).parents[3];sdk=Path('C:/Program Files (x86)/Windows Kits/10')
approval['sourceSha256Bytes']=graph['source']['sha256Bytes'];approval['dllSha256Bytes']=graph['dll']['sha256Bytes']
for key in ['transitiveHeaders','searchedLibrariesConservativePins','compilerBinDirectoryConservativePins']:
 converted=[]
 for r in graph[key]:
  absolute=Path(r['path']);assert sha(absolute.read_bytes())==r['sha256Bytes']
  root,label=(vc,'vc')if absolute.is_relative_to(vc)else(sdk,'sdk')
  assert absolute.is_relative_to(root);converted.append(dict(path=label+'/'+absolute.relative_to(root).as_posix(),sha256Bytes=r['sha256Bytes'],bytes=r['bytes']))
 approval[key]=converted
approvalPath.write_text(json.dumps(approval,indent=2)+'\n',encoding='utf-8')
report=dict(nativeHunks=2,manualSources=1,existingClassificationProducerHunk=1,originalIdentitiesNew=0,originalIdentityFeatureMerge=1,
 nativeSourceSha256=sha(cpp.read_bytes()),expectedFreshSameDllSha256=approval['dllSha256Bytes'],reviewGraphSha256=sha((LANE/'runs/native-compile-01/producer-input-graph.json').read_bytes()),
 actualRootMounted=False,actualQueryExecuted=False,preparedManifestSha256=sha((LANE/'frozen-handoff.json').read_bytes()))
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
