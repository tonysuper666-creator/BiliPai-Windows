from pathlib import Path
import hashlib,json,os,subprocess
root=Path(__file__).resolve().parent;main=root.parents[2];repo=main.parent/'BiliPai-v023'
relative='desktop/verification/stable-pgc-inline-window-geometry';target=repo/relative
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
rows=[]
def save(name,b):
 p=wide(target/name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b);rows.append(dict(path=name,bytes=len(b),sha256Bytes=sha(b)))
for name in ['prepare.py','save_and_stage.py','installation.json','screen-height-required-seam.json','generated-verification.json','actual-build01.log','actual-build01.json','actual-native-producer-receipt.json','actual-native-producer-input-graph.json']:
 save('root/'+name,read(root/name))
save('root/build_actual.py',read(main/'desktop/.local/root-integration-tools/build_actual.py'))
for source in installation['sourceTargets']:
 assert sha(read(repo/source['path']))==source['afterSha256Bytes']
 before=git('show',installation['baseCommit']+':'+source['path']);assert sha(before)==source['beforeSha256Bytes']
 save('root/before/'+source['path'],before)
fixture=main/'desktop/.local/stable-pgc-root-integration-acceptance'
for number in ['01','02']:
 run=fixture/('runs/actual93-'+number)
 for name in ['RootPgcFixture.kt','compile.log','compile-result.json','compiler.args','pins-before.json','fixture-jar-pin.json','acceptance-result.json','runtime.log','pins-after.json']:
  if wide(run/name).exists():save('prior-actual93-'+number+'/'+name,read(run/name))
 for p in wide(run/'runtime-evidence').glob('*'):
  if p.is_file():save('prior-actual93-'+number+'/runtime-evidence/'+p.name,read(p))
save('prior-fixture/run.py',read(fixture/'run.py'))
readme='''The original phone PGC page sets inline player height to two thirds of screen width. In an actual 1100 by 900 Windows owned window that left too little measured space for its original error/retry content: the retry node had zero bounds. Actual93-01 originally inspected only the main native surface and is a fixture limitation; actual93-02 inspects actual owned Compose dialogs, renders the complete original PGC error and records the genuine zero-bounds retry. No successful API was manufactured.

The same existing original LargeScreenVideoLayoutPolicy now supplies Windows inline height when its own large-window condition holds, capped by the original portrait height. Original PGC collapse/progress/actions/content bodies and original narrow phone height are retained; there is no second layout/player owner. One exact original Screen platform line and the existing ScreenPlatform helper/imports are the entire change. Complete inverse reconstruction of the original Screen passed before the sole producer ran.

Normal production main and test compilation passed, with zero product overlays. This record does not yet accept repaired-window interaction, default Direct3D, actual signed transport or media, v025 completion, or a new EXE.
'''
save('README.md',readme.encode());index=(json.dumps(dict(files=rows),indent=2)+'\n').encode();wide(target/'artifact-index.json').write_bytes(index)
attrs=repo/'.gitattributes';raw=read(attrs);line=(relative+'/** -text\n').encode();assert line not in raw;wide(attrs).write_bytes(raw+(b''if raw.endswith(b'\n')else b'\n')+line)
source_paths=['.gitattributes']+[s['path']for s in installation['sourceTargets']]
paths=source_paths+[relative+'/artifact-index.json']+[relative+'/'+r['path']for r in rows]
for i in range(0,len(paths),20):git('add','-f','--',*paths[i:i+20])
assert set(x.decode()for x in git('diff','--cached','--name-only','-z').split(b'\0')if x)==set(paths)
for row in rows:assert sha(git('show',':'+relative+'/'+row['path']))==row['sha256Bytes']
assert sha(git('show',':'+relative+'/artifact-index.json'))==sha(index)
for path in source_paths:assert sha(git('show',':'+path))==sha(read(repo/path).replace(b'\r\n',b'\n'))
git('diff','--cached','--check','--',*source_paths)
print(json.dumps(dict(productTargets=2,stagedBlobs=len(paths),rawProofFiles=len(rows),normalCompilePassed=True,repairedRootWindowAccepted=False)))
