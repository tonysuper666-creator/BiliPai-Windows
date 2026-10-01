from pathlib import Path
import difflib,hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=HERE/'danmaku-compose-return-correction';OUT.mkdir(exist_ok=True)
def sha(b):return hashlib.sha256(b).hexdigest()
host='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDanmakuSettingsHost.kt'
root='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDanmakuRoot.kt'
rows=[]
for relative in (host,root):
    path=REPO/relative;raw=path.read_bytes();before=raw.decode().replace('\r\n','\n')
    if relative==host:
        assert sha(before.encode())=='6676ec420e8cfe104fc7dceacacdb1025a28f00054ae68e28ecc63f41a547e7f'
        after=before.replace('        if(!platform.isOwned())return@key\n','        if(platform.isOwned()) {\n')
        assert after!=before
        tail='        }\n    }\n}\n';assert after.endswith(tail)
        after=after[:-len(tail)]+'        }\n        }\n    }\n}\n'
        assert sha(after.encode())=='e52165bea8256acbfc8e417a326fedf8122cfed83dc0d7a5d38479c82dccb20c'
    else:
        begin='        val syncEnabled=syncSnapshot?:return@key\n'
        end='            syncEnabled,account!=null,cloudSync,{if(owned())onShowPool()},{if(owned())onDismissSettings()})\n'
        assert before.count(begin)==1 and before.count(end)==1
        start=before.index(begin);stop=before.index(end,start)+len(end)
        body=before[start+len(begin):stop]
        assert body.count('        if(!owned())return@key\n')==1
        split=body.index('        if(!owned())return@key\n')
        effects=body[:split];content=body[split+len('        if(!owned())return@key\n'):]
        indent=lambda text:''.join('    '+line if line.strip() else line for line in text.splitlines(keepends=True))
        replacement='        val syncEnabled=syncSnapshot\n        if(syncEnabled!=null) {\n'+indent(effects)+'            if(owned()) {\n'+indent(indent(content))+'            }\n        }\n'
        after=before[:start]+replacement+before[stop:]
    assert 'return@key' not in after
    (OUT/(path.name+'.before')).write_bytes(raw)
    result=after.replace('\n','\r\n').encode() if b'\r\n' in raw else after.encode()
    path.write_bytes(result);(OUT/(path.name+'.after')).write_bytes(result)
    patch=''.join(difflib.unified_diff(before.splitlines(keepends=True),after.splitlines(keepends=True),fromfile='before/'+relative,tofile='after/'+relative))
    (OUT/(path.name+'.patch')).write_bytes(patch.encode())
    rows.append(dict(path=relative,beforeSha256Bytes=sha(raw),afterSha256Bytes=sha(result),beforeLfSha256=sha(before.encode()),afterLfSha256=sha(after.encode())))
report=dict(reason='Compose inline key labeled returns emitted illegal JVM NON_LOCAL_RETURN.<anonymous> references in actual26; use structured content guards',files=rows,frozenHistoricalEvidenceModified=False,preferenceCloudLifetimePreserved=True,actualProductRuntimeAccepted=False)
(OUT/'correction-report.json').write_bytes((json.dumps(report,indent=2)+'\n').encode())
print(json.dumps(report))
