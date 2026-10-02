from pathlib import Path
import hashlib,json,subprocess
root=Path(__file__).resolve().parent;main=root.parents[2];candidate=main.parent/'BiliPai-v023'
relative='desktop/verification/stable-space-root-window-actual92';target=candidate/relative
def sha(b):return hashlib.sha256(b).hexdigest()
def git(*a):return subprocess.check_output(['git','-c','core.longpaths=true',*a],cwd=candidate)
assert not git('status','--porcelain','-z').strip() and not target.exists()
assert git('rev-parse','HEAD').decode().strip()=='ef29ad182d7ecc9c75a793a87bc78dcdfd83224d'
run=root/'runs/actual92-01';result=json.loads((run/'acceptance-result.json').read_bytes())
assert result['status']=='PASS_ACTUAL_ROOT_SPACE_UI' and result['assertions']==24 and result['actualOwnedCanvasPointerEvents']==15
assert result['productSourceOverrides']==0 and result['loadedOriginsBytesAllActual'] and not result['unhandledProductErrorMarkers']
rows=[]
def save(name,b):
 p=target/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b);rows.append(dict(path=name,bytes=len(b),sha256Bytes=sha(b)))
for name in ['RootSpaceFixture.kt','run.py','save_actual92.py']:save('fixture/'+name,(root/name).read_bytes())
for name in ['RootSpaceFixture.kt','compile-result.json','compile.log','compiler.args','fixture-jar-pin.json','acceptance-result.json','runtime.log','pins-before.json','pins-after.json']:
 save('actual92-01/'+name,(run/name).read_bytes())
for p in sorted((run/'runtime-evidence').rglob('*')):
 if p.is_file():save('actual92-01/runtime-evidence/'+p.relative_to(run/'runtime-evidence').as_posix(),p.read_bytes())
for name in ['manifest.json','ordered-runtime-cp.json']:
 save('actual92-snapshot/'+name,(main/'desktop/.local/stable-product-snapshot-92'/name).read_bytes())
readme='''The actual unmodified DesktopApp/Root/SpaceScreen ran over the immutable 105-entry actual92 product classpath. Twenty-four checks and fifteen owned Compose Canvas pointer events passed. Space, UpowerRank and MemberGuard used their full original screens, actual retained view models and the same Root transport; actual retry increased denied requests. Covered pages retained their original VM and refused new visible mutation, popped entries rejected late state, and logout replaced the guest epoch and retired all previous owners. Window disposal drained the route owners.

Application and image HTTP were denied by one task-owned fail-closed proxy; no successful API response or image was synthesized. The fixture had isolated guest-only HOME, user data was not copied, and no global OS input was injected. All loaded class origins and classpath bytes remained the actual product before and after.

This accepts the tested original Space guest/error/navigation/retirement UI in SOFTWARE rendering. Successful real business requests, account permissions, default Direct3D startup, long native paths, decoded media, complete PGC playback, v025 and a deployed new EXE remain unaccepted. Earlier actual90/91 failures and their repairs remain in prior verification commits.
'''
save('README.md',readme.encode());index=(json.dumps(dict(files=rows),indent=2)+'\n').encode();(target/'artifact-index.json').write_bytes(index)
a=candidate/'.gitattributes';raw=a.read_bytes();line=(relative+'/** -text\n').encode();assert line not in raw;a.write_bytes(raw+(b'' if raw.endswith(b'\n') else b'\n')+line)
paths=['.gitattributes',relative+'/artifact-index.json']+[relative+'/'+r['path']for r in rows]
for i in range(0,len(paths),20):git('add','-f','--',*paths[i:i+20])
assert set(x.decode()for x in git('diff','--cached','--name-only','-z').split(b'\0')if x)==set(paths)
for r in rows:assert sha(git('show',':'+relative+'/'+r['path']))==r['sha256Bytes']
assert sha(git('show',':'+relative+'/artifact-index.json'))==sha(index)
git('diff','--cached','--check','--','.gitattributes')
print(json.dumps(dict(actualSpaceUiPassed=True,assertions=24,ownedPointerEvents=15,stagedFiles=len(paths),productionCodeWrites=0)))
