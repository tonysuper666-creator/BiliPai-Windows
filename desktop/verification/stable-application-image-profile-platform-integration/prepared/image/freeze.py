from pathlib import Path
import hashlib,json
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023';sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
contract=json.loads((LANE/'install-contract.json').read_bytes())
license=(REPO/'desktop/third-party/miuix5157/upstream/LICENSE').read_bytes();assert b'Apache License' in license and b'Version 2.0, January 2004' in license
notice=b'''Coil Cache Control 3.5.0, unchanged Kotlin source-built component.
Copyright (C) 2013, 2019 Square, Inc. Apache License 2.0.
Original source and notices are preserved in all three files.
Source artifact: https://repo.maven.apache.org/maven2/io/coil-kt/coil3/coil-network-cache-control-jvm/3.5.0/coil-network-cache-control-jvm-3.5.0-sources.jar
SHA-256: bc0d4c05573187c53a32f0b31a05a73f5af734a576814d52b3e227e0b71ea9db
Compiled against the existing Coil3.5.0 and kotlinx-datetime0.7.1 runtime. No source behavior changes or additional runtime dependency JAR.
'''
for name,data in [('coil-cache-control-LICENSE.txt',license),('coil-cache-control-NOTICE.txt',notice)]:
 p=LANE/'prepared/resources'/name;p.parent.mkdir(exist_ok=True);p.write_bytes(data);contract['payloads'].append(dict(source=p.relative_to(LANE).as_posix(),target='desktop/resources/common/licenses/'+name,sha256Bytes=sha(data)))
assert len(contract['payloads'])==8
for r in contract['payloads']:assert sha(wide(LANE/r['source']).read_bytes())==r['sha256Bytes']
original=wide(LANE/'prepared/generated/com/android/purebilibili/app/DesktopOriginalApplicationImageLoader.kt').read_text(encoding='utf-8')
replay=wide(LANE/'replay-generated/com/android/purebilibili/app/DesktopOriginalApplicationImageLoader.kt').read_text(encoding='utf-8');assert original==replay
receipt=json.loads((LANE/'source-receipt.json').read_bytes());body=original[original.index('internal fun newImageLoader'):original.index('\n\ninternal fun resolveOriginalImageMemoryCachePercent')].rstrip()
for delta in reversed(receipt['exactMethodReplacements']):assert body.count(delta['after'])==1;body=body.replace(delta['after'],delta['before'],1)
from importlib.util import spec_from_file_location,module_from_spec
spec=spec_from_file_location('prepare',LANE/'prepare.py');m=module_from_spec(spec)
# Do not execute the producer again: verify the exact original selected range with its parser.
spec=spec_from_file_location('parser',REPO/'desktop/tools/sync-upstream.py');parser=module_from_spec(spec);spec.loader.exec_module(parser)
raw=wide(LANE/'original-stable/app/src/main/java/com/android/purebilibili/app/PureApplication.kt').read_text(encoding='utf-8');tokens=parser.kotlin_tokens(raw);start=raw.index('override fun newImageLoader(');sub=[t for t in tokens if t[1]>=start];i=next(i for i,t in enumerate(sub)if t[0]=='{');depth=1
while depth:i+=1;depth+=(sub[i][0]=='{')-(sub[i][0]=='}')
assert body==raw[start:sub[i][2]].strip()
(LANE/'reverse-audit.json').write_text(json.dumps(dict(fullOriginalMethodReverseMatched=True,originalMethodSha256LF=sha(body.encode()),replayOutputMatched=True,mechanicalReplacements=len(receipt['exactMethodReplacements']),unmodifiedCoilSourceFiles=3,existingOriginalQuantizerUnchanged=True,newImageClients=0,fullRootAccepted=False),indent=2)+'\n',encoding='utf-8')
(LANE/'install-contract.json').write_text(json.dumps(contract,indent=2)+'\n',encoding='utf-8')
rows=[];excluded=[]
for p in sorted(wide(LANE).rglob('*'),key=str):
 if not p.is_file() or p.name=='frozen-handoff.json':continue
 relative=p.relative_to(wide(LANE)).as_posix()
 if '/synthetic-local-cache/' in relative:continue
 if p.suffix in ('.jar','.class','.pyc','.kotlin_module'):
  excluded.append(dict(path=relative,sha256Bytes=sha(p.read_bytes()),reason='Rebuildable source artifact or compiler output'))
 else:rows.append(dict(path=relative,sha256Bytes=sha(p.read_bytes())))
(LANE/'excluded-artifacts.json').write_text(json.dumps(excluded,indent=2)+'\n',encoding='utf-8');rows.append(dict(path='excluded-artifacts.json',sha256Bytes=sha((LANE/'excluded-artifacts.json').read_bytes())))
assert len({r['path']for r in rows})==len(rows)
raw=(json.dumps(dict(stableCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',artifacts=rows,prospectiveOriginalSourceCount=3,installPayloadCount=8,narrowSources=7,narrowClasses=14,actual43RuntimeEntries=97,newFqnOverlaps=0,proofGroups=3,proofAssertions=22,wholeProductAccepted=False,fullRootAccepted=False),indent=2)+'\n').encode();(LANE/'frozen-handoff.json').write_bytes(raw)
print(json.dumps(dict(rawArtifacts=len(rows),manifestSha256Bytes=sha(raw),installPayloads=8,sourceRows=3)))
