from pathlib import Path
import hashlib,json,os,subprocess
root=Path(__file__).resolve().parent;main=root.parents[2];repo=main.parent/'BiliPai-v023'
relative='desktop/verification/stable-pgc-root-window-actual94';target=repo/relative
def wide(p):
 s=os.fspath(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def git(*a):return subprocess.check_output(['git','-c','core.longpaths=true',*a],cwd=repo)
assert not git('status','--porcelain','-z').strip() and not wide(target).exists()
assert git('rev-parse','HEAD').decode().strip()=='0695c5690fe3b2b5cd18288b5fb370d13b2b8c56'
run=root/'runs/actual94-01';result=json.loads(read(run/'acceptance-result.json'))
assert result['status']=='PASS_ACTUAL_ROOT_PGC_UI' and result['assertions']==17 and result['actualOwnedCanvasPointerEvents']==10
assert result['productSourceOverrides']==0 and result['loadedOriginsBytesAllActual'] and not result['unhandledProductErrorMarkers']
rows=[]
def save(name,b):
 p=wide(target/name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b);rows.append(dict(path=name,bytes=len(b),sha256Bytes=sha(b)))
for name in ['RootPgcFixture.kt','run.py','save_actual94.py']:save('fixture/'+name,read(root/name))
for name in ['RootPgcFixture.kt','compile-result.json','compile.log','compiler.args','fixture-jar-pin.json','acceptance-result.json','runtime.log','pins-before.json','pins-after.json']:
 save('actual94-01/'+name,read(run/name))
for p in sorted(wide(run/'runtime-evidence').glob('*')):
 if p.is_file():save('actual94-01/runtime-evidence/'+p.name,read(p))
for name in ['manifest.json','ordered-runtime-cp.json']:save('actual94-snapshot/'+name,read(main/'desktop/.local/stable-product-snapshot-94'/name))
readme='''The actual DesktopApp/Root/full original PGC player screens ran over the unchanged 105-entry actual94 product classpath. Seventeen checks and ten pointer events on the actual owned Compose foreground dialog passed. Original Bangumi and PUGV view models rendered real transport errors, and original retry buttons issued requests through the same Root repository. PGC retained the same native Section and existing typed comment VM; covered Settings/back retained the same PGC VM, pop retired it and rejected retry, actual isolated guest epoch replacement retired PGC/PUGV, and close drained the owners. No product sources/classes were overlaid.

All app/image traffic was denied by the task-owned proxy (25 denied connections). The runtime used isolated guest-only user/profile/cache paths and no global OS input. Classpath and loaded class bytes/origins were verified before and after. The fixture now measures and sends events to actual owned Compose dialogs as well as the main native surface, and rejects zero-bounds offscreen nodes.

This accepts the tested original PGC/PUGV guest/error/retry/navigation/retirement UI in SOFTWARE rendering after both recorded platform repairs. It does not accept successful signed business transport, PUGV permissions, real accounts, decoded media, default Direct3D startup, long native paths, v025 migration or a deployed EXE. Earlier fixture and actual product failures remain in the two prior verification commits.
'''
save('README.md',readme.encode());index=(json.dumps(dict(files=rows),indent=2)+'\n').encode();wide(target/'artifact-index.json').write_bytes(index)
attrs=repo/'.gitattributes';raw=read(attrs);line=(relative+'/** -text\n').encode();assert line not in raw;wide(attrs).write_bytes(raw+(b''if raw.endswith(b'\n')else b'\n')+line)
paths=['.gitattributes',relative+'/artifact-index.json']+[relative+'/'+r['path']for r in rows]
for i in range(0,len(paths),20):git('add','-f','--',*paths[i:i+20])
assert set(x.decode()for x in git('diff','--cached','--name-only','-z').split(b'\0')if x)==set(paths)
for r in rows:assert sha(git('show',':'+relative+'/'+r['path']))==r['sha256Bytes']
assert sha(git('show',':'+relative+'/artifact-index.json'))==sha(index)
git('diff','--cached','--check','--','.gitattributes')
print(json.dumps(dict(actualPgcUiAccepted=True,assertions=17,ownedPointerEvents=10,stagedFiles=len(paths),productCodeWrites=0)))
