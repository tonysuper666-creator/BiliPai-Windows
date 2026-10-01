from pathlib import Path
import hashlib,json,os,shutil
H=Path(__file__).resolve().parent;MAIN=H.parents[2]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def row(p):return dict(path=p.relative_to(H).as_posix(),sha256Bytes=sha(p),sizeBytes=p.stat().st_size)
assert json.loads((H/'compile-06/inputs.json').read_text(encoding='utf-8'))['exit']==0
assert json.loads((H/'proof-04/proof.json').read_text(encoding='utf-8'))['runExit']==0
assert 'RESULT assertions=16 groups=1 pointerPairs=1' in (H/'proof-04/run.log').read_text(encoding='utf-8')
assert json.loads((H/'audit-04/result.json').read_text(encoding='utf-8'))['passed']
copies=[]
for p in sorted((H/'prepared/manual').rglob('*.kt')):
 copies.append(dict(source=p.relative_to(H).as_posix(),target='desktop/src/main/kotlin/'+p.relative_to(H/'prepared/manual').as_posix(),sha256Bytes=sha(p),sha256LfUtf8=sha(p)))
p=H/'prepared/tools/extract-upstream-following.py';copies.append(dict(source=p.relative_to(H).as_posix(),target='desktop/tools/'+p.name,sha256Bytes=sha(p),sha256LfUtf8=sha(p)))
assert len(copies)==3
whitelist=dict(copies=copies,patches=[dict(path=n,sha256Bytes=sha(H/n),bases='consumer-bases.json' if n=='consumer.patch' else 'favorite-actions-bases.json') for n in ['favorite-actions-producer.patch','consumer.patch']],
 registryUnion='registry-delta.json',gradleSnippet='gradle-snippet.kts',requiredPriorPackets=[dict(lane='stable-personal-queue-start-parity',manifest='4db0037aa5066bf15bece6588681c91fdf66d9061b835f8585aadff19feed1f9'),dict(lane='stable-personal-watchlater-parity',manifest='2d5d6ba3e5830dc07e9716b6b21833c07f4153608a4d0c34c55f1a1182e891ac')],
 notInstall=['prepared/existing whole files','prepared/selected','prepared/actions','auxiliary-favorites-output','task compiled classes/jars','temporary Stores/caches','audit replays'])
(H/'install-whitelist.json').write_text(json.dumps(whitelist,indent=2)+'\n',encoding='utf-8')
p=H/'evidence/original-following-special-group.png';p.parent.mkdir(exist_ok=True);shutil.copyfile(H/'proof-04/temporary/original-following-special-group.png',p)
excluded=[row(p) for p in H.rglob('*') if p.is_file() and (p.suffix in ['.class','.jar','.dll'] or '/temporary/' in p.as_posix())]
(H/'excluded-runtime-artifacts.json').write_text(json.dumps(dict(doNotInstallOrCommit=True,artifacts=excluded),indent=2)+'\n',encoding='utf-8')
paths=[]
for p in H.rglob('*'):
 if not p.is_file() or p.name=='frozen-handoff.json':continue
 rel=p.relative_to(H).as_posix()
 if p.suffix in ['.class','.jar','.dll','.pyc'] or '/temporary/' in '/'+rel or '/__pycache__/' in '/'+rel:continue
 if rel.startswith('auxiliary-favorites-output/') or any(t in rel for t in ['/old-favorites/','/new-favorites/','/standalone/','/production/','/watchlater-base/']):continue
 paths.append(p)
artifacts=[row(p) for p in sorted(paths)]
frozen=dict(scope='prepared full original Following source/UI/VM/cache/actions integration',mainIntegration=False,targetCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',actualBase=dict(manifest='7db2b7bc2816c9b7f10521c1ad1678196f4446a8bcde5cc5a837d678ac675a35',orderedCp='569b953e5def0dcaba76d9a6a6d96fea9f3572ad910e6edf13f23b88d91ee821',entries=97),
 gates=dict(compile='compile-06',proof='proof-04',assertions=16,groups=1,pointerPairs=1,audit='audit-04',sourceChecks=json.loads((H/'audit-04/result.json').read_text())['count']),
 installWhitelistSha=sha(H/'install-whitelist.json'),recipeSha=sha(H/'ROOT-INTEGRATION.md'),artifacts=artifacts,artifactsCount=len(artifacts),outsideScope=['Main/EXE acceptance','real account mutation/HTTP','Root physical route/native popup/HWND','remaining personal pages'])
(H/'frozen-handoff.json').write_text(json.dumps(frozen,indent=2)+'\n',encoding='utf-8')
for r in artifacts:assert sha(H/r['path'])==r['sha256Bytes']
print('FROZEN',len(artifacts),'SHA',sha(H/'frozen-handoff.json'),'whitelist',sha(H/'install-whitelist.json'),'recipe',sha(H/'ROOT-INTEGRATION.md'))
