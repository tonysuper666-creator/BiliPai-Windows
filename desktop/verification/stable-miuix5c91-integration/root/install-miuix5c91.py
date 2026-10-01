from pathlib import Path
import hashlib,json,sys,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-miuix5c91-source-audit';FORK=REPO/'desktop/third-party/miuix5157'
PREP=LANE/'prepared/desktop/third-party/miuix5157';OUT=HERE/'miuix5c91-install';OUT.mkdir(exist_ok=True)
apply='--apply' in sys.argv
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
manifest=pin(LANE/'frozen-handoff.json','023eccf2d42011cd34357017e5a84b812fadf6c1e44be58c4b0b6b13f4ef6c30')
for row in json.loads(manifest)['artifacts']:
    b=pin(LANE/row['path'],row['sha256Bytes']);assert len(b)==row['bytes']
actual=json.loads(read(LANE/'actual-fork-source-pins.json'));old=json.loads(read(FORK/'upstream-provenance.json'))
assert old['commit']==actual['currentCommit']=='5157b503e86e2bfc2db61db00fff5df41326394a'
for row in actual['files']:
    if row['path']!='upstream-provenance.json':pin(FORK/row['path'],row['sha256Bytes'])
new=json.loads(read(PREP/'upstream-provenance.json'))
assert new['commit']=='5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca'
archive=LANE/'official/miuix-5c91-5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca.zip'
pin(archive,new['archiveSha256']);extra=[];rows=[];planned={}
with zipfile.ZipFile(wide(archive)) as z:
    prefix=z.namelist()[0].split('/')[0]+'/'
    canonical={r['path']:r for r in new['files']}
    for r in old['files']:
        if r['path'] not in canonical:
            data=z.read(prefix+r['path']);assert sha(data)==r['sha256']
            pin(FORK/'upstream'/r.get('storagePath',r['path']),r['sha256'])
            canonical[r['path']]=r;extra.append(r)
    for r in canonical.values():
        data=z.read(prefix+r['path']);assert sha(data)==r['sha256']
        storage=r.get('storagePath',r['path']);target='upstream/'+storage
        if r in extra:assert read(FORK/target)==data
        else:assert read(PREP/target)==data
        planned[target]=data
    if extra:new['files'].extend(extra)
for name in ('build.gradle.kts','verify-source.py','dependency-pins.json'):
    planned[name]=read(PREP/name)
planned['upstream-provenance.json']=read(PREP/'upstream-provenance.json') if not extra else (json.dumps(new,indent=2)+'\n').encode()
for relative,data in planned.items():
    p=FORK/relative;before=read(p) if wide(p).is_file() else None
    if before==data:continue
    rows.append(dict(path='desktop/third-party/miuix5157/'+relative,beforeSha256Bytes=sha(before) if before is not None else None,afterSha256Bytes=sha(data),bytes=len(data)))
    if apply:
        if before is not None:
            backup=wide(OUT/'baseline'/relative);backup.parent.mkdir(parents=True,exist_ok=True);assert not backup.exists();backup.write_bytes(before)
        wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(data)
assert new['compiledSourceSets']==old['compiledSourceSets']
report=dict(applied=apply,soleSourceFork=':miuix5157',oldCommit=old['commit'],targetCommit=new['commit'],targetVersion='0.9.4-5c91d5e5-windows-source1',selectedCanonicalFiles=len(new['files']),compiledSources=sum(r['path'].endswith('.kt') and any('/src/'+s+'/' in r['path'] for s in new['compiledSourceSets']) for r in new['files']),additionalCurrentCanonicalRowsMerged=extra,changedFiles=rows,newBinaryDependencyIntroduced=False,actualProductRuntimeAccepted=False)
(OUT/('install-report.json' if apply else 'dry-run-report.json')).write_bytes((json.dumps(report,indent=2)+'\n').encode())
print(json.dumps(dict(applied=apply,changedFiles=len(rows),selectedCanonicalFiles=report['selectedCanonicalFiles'],compiledSources=report['compiledSources'],additionalMergedRows=len(extra))))
