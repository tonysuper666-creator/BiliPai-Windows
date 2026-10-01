from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]; REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'fullscreen-music-audio-production65.json'
assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
def digest(b): return hashlib.sha256(b.replace(b'\r\n',b'\n')).hexdigest()
registry=json.loads(read(REPO/'desktop/upstream-sources.json'))
assert len(registry['sources'])==1115 and len(registry['resources'])==213
by_path={r['path']:r for r in registry['sources']}
rows=[]; direct=[]
fullscreen=MAIN/'desktop/.local/stable-video-fullscreen-pager-parity'
expected=json.loads(read(fullscreen/'producer-proof-02/production/source-inventory.json'))
actual=json.loads(read(REPO/'desktop/build/generated/original-video-fullscreen-pager/source-inventory.json'))
assert expected==actual
for row in expected['outputs']:
    p=REPO/'desktop/build/generated/original-video-fullscreen-pager'/row['path']
    if row['generated']:
        assert digest(read(p))==row['sha256LF'],row['path']
        rows.append(dict(family='fullscreen',path=row['path'],sha256LF=row['sha256LF']))
    else:
        assert not wide(p).exists() and by_path[row['origin']]['mode']=='direct',row['path']
        sync=REPO/'desktop/build/generated/upstream'/row['origin']
        assert digest(read(sync))==row['sha256LF'],row['origin']
        direct.append(dict(family='fullscreen',source=row['origin'],sha256LF=row['sha256LF']))
assert len(rows)==19 and len(direct)==15

for name, directory in [('music','music-player-full'),('audio','video-audio-full')]:
    lane=MAIN/('desktop/.local/stable-video-tablet-audio-parity/frozen-'+name+'-slice')
    receipt=json.loads(read(lane/'generation-receipt.json'))
    production=REPO/'desktop/build/generated'/directory
    selected=0
    for row in receipt['outputs']:
        origin=row.get('source',row.get('original')); p=production/row['output']
        if by_path[origin]['mode']=='direct':
            assert not wide(p).exists(),row['output']
            sync=REPO/'desktop/build/generated/upstream'/origin
            assert digest(read(sync))==row['sha256LF']
            direct.append(dict(family=name,source=origin,sha256LF=row['sha256LF']))
        else:
            assert digest(read(p))==row['sha256LF'],row['output']
            rows.append(dict(family=name,path=row['output'],sha256LF=row['sha256LF']))
            selected+=1
    assert selected==({'music':18,'audio':2}[name])
    assert len(list(wide(production/'com').rglob('*.kt')))==selected
assert len(rows)==39 and len(direct)==19
assert len({(r['family'],r['path'])for r in rows})==39
report=dict(passed=True,selectedProductionBodiesCompared=39,directPoliciesSyncOnly=19,
    exactFrozenLFSourceMatches=rows,directSourceChecks=direct,
    sourceIdentityCount=1115,resourceCount=213,
    sourceInventoryIsNotFunctionalOrReusePercentage=True,wholeRootMounted=False)
OUT.write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
print(json.dumps(dict(passed=True,selectedProductionBodiesCompared=39,directPoliciesSyncOnly=19)))
