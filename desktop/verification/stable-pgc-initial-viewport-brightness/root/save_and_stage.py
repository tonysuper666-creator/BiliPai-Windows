from pathlib import Path
import hashlib,json,os,subprocess
root=Path(__file__).resolve().parent;main=root.parents[2];repo=main.parent/'BiliPai-v023'
relative='desktop/verification/stable-pgc-initial-viewport-brightness';target=repo/relative
def wide(p):
 s=os.fspath(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def git(*a):return subprocess.check_output(['git','-c','core.longpaths=true',*a],cwd=repo)
installation=json.loads(read(root/'installation.json'));build=json.loads(read(root/'actual-build01.json'))
assert git('rev-parse','HEAD').decode().strip()==installation['baseCommit']
assert not git('diff','--cached','--name-only','-z').strip() and not wide(target).exists()
assert build['exitCode']==0 and build['mainClassesCompiled'] and build['testClassesCompiled'] and build['actualProductOverrides']==0
assert sha(read(root/'actual-build01.log'))==build['logSha256Bytes']
source=installation['sourceTargets'][0]
assert sha(read(repo/source['path']))==source['afterSha256Bytes']
rows=[]
def save(name,b):
 p=wide(target/name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
 rows.append(dict(path=name,bytes=len(b),sha256Bytes=sha(b)))
for name in ['prepare.py','save_and_stage.py','installation.json','brightness-exact-delta.json','generated-verification.json','actual-build01.log','actual-build01.json','actual-native-producer-receipt.json','actual-native-producer-input-graph.json']:
 save('root/'+name,read(root/name))
save('root/build_actual.py',read(main/'desktop/.local/root-integration-tools/build_actual.py'))
before=git('show',installation['baseCommit']+':'+source['path']);assert sha(before)==source['beforeSha256Bytes']
save('root/before/'+source['path'],before)
fixture=main/'desktop/.local/stable-pgc-root-integration-acceptance'
for number in ['01','02','03']:
 run=fixture/('runs/actual92-'+number)
 for name in ['RootPgcFixture.kt','compile.log','compile-result.json','compiler.args','pins-before.json','fixture-jar-pin.json','acceptance-result.json','runtime.log','pins-after.json']:
  if wide(run/name).exists():save('prior-actual92-'+number+'/'+name,read(run/name))
 for p in wide(run/'runtime-evidence').glob('*'):
  if p.is_file():save('prior-actual92-'+number+'/runtime-evidence/'+p.name,read(p))
save('prior-fixture/run.py',read(fixture/'run.py'))
readme='''The complete original PGC Loading/Error player view reads viewport brightness before a media publication exists. Actual92-03 over the unchanged production classpath captured a composition CancellationException from the Windows adapter, preventing the original error/retry UI from mounting. The sole Section read now returns the existing full-brightness viewport state until the same native owner has an admitted source; an owned admitted source still reads its source-owned overlay dimmer. All write/admission/capture gates remain unchanged.

Normal production main and test compilation passed without product overlays. No additional tests executed for this one-method repair. The prior actual92-01 fixture compile failure and actual92-02 fixture wait-order failure are preserved separately; actual92-03 is the actual product failure. This record does not claim repaired-window acceptance, native decoded media, signed business transport, default Direct3D startup, v025 complete migration or a newly deployed EXE.
'''
save('README.md',readme.encode())
index=(json.dumps(dict(files=rows),indent=2)+'\n').encode();wide(target/'artifact-index.json').write_bytes(index)
attrs=repo/'.gitattributes';raw=read(attrs);line=(relative+'/** -text\n').encode();assert line not in raw
wide(attrs).write_bytes(raw+(b''if raw.endswith(b'\n')else b'\n')+line)
paths=['.gitattributes',source['path'],relative+'/artifact-index.json']+[relative+'/'+r['path']for r in rows]
for i in range(0,len(paths),20):git('add','-f','--',*paths[i:i+20])
assert set(x.decode()for x in git('diff','--cached','--name-only','-z').split(b'\0')if x)==set(paths)
for row in rows:assert sha(git('show',':'+relative+'/'+row['path']))==row['sha256Bytes']
assert sha(git('show',':'+relative+'/artifact-index.json'))==sha(index)
assert sha(git('show',':'+source['path']))==sha(read(repo/source['path']).replace(b'\r\n',b'\n'))
git('diff','--cached','--check','--','.gitattributes',source['path'])
print(json.dumps(dict(productTargets=1,stagedBlobs=len(paths),rawProofFiles=len(rows),normalCompilePassed=True,repairedRootWindowAccepted=False)))
