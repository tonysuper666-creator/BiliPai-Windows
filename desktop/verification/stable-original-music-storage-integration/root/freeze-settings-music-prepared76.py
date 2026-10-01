from pathlib import Path
import hashlib,json,sys
sys.stdout.reconfigure(encoding='utf-8')
H=Path(__file__).resolve().parent;MAIN=H.parents[2]
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(b):return hashlib.sha256(b).hexdigest()
for name in ['stable-original-settings-io-parity','stable-original-music-storage-parity']:
    lane=MAIN/'desktop/.local'/name
    assert not wide(lane/'frozen-handoff.json').exists()
    rows=[];excluded=[]
    for p in sorted(wide(lane).rglob('*')):
        if not p.is_file():continue
        path=p.relative_to(wide(lane)).as_posix();b=p.read_bytes()
        if p.suffix in ['.jar','.class','.pyc']:
            excluded.append(dict(path=path,sha256Bytes=sha(b),bytes=len(b),reason='Rebuildable verification output; not installation payload.'))
        else:rows.append(dict(path=path,sha256Bytes=sha(b),bytes=len(b)))
    packet=dict(frozen=True,actualBase=75,upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
        artifacts=rows,excludedRebuildableOutputs=excluded,
        installOnlyExactHunksAndNewManual=True,generatedSourcesAreSoleProducerVerificationOnly=True,
        completeRootMounted=False,desktopExeReplaced=False)
    raw=(json.dumps(packet,indent=2)+'\n').encode();wide(lane/'frozen-handoff.json').write_bytes(raw)
    print(json.dumps(dict(lane=name,raw=len(rows),sha256=sha(raw))))
