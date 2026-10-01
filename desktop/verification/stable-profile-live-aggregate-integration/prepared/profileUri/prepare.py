from pathlib import Path
import hashlib,json
LANE=Path(__file__).resolve().parent;P=LANE.parent/'stable-profile-main-parity'
def sha(b):return hashlib.sha256(b).hexdigest()
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
manifest=safe(P/'frozen-handoff.json').read_bytes();assert sha(manifest)=='5d4ce31834b9bb34c09fca2bd22ea092f24f504a1ff74f49cfb1aaa6ab22ebe7'
target='desktop/tools/extract-upstream-profile-main.py';file=P/'prepared/tools/extract-upstream-profile-main.py'
base=safe(file).read_text(encoding='utf-8');expected=next(r['sha256Bytes'] for r in json.loads(manifest)['artifacts'] if r['path']=='prepared/tools/extract-upstream-profile-main.py');assert sha(safe(file).read_bytes())==expected
hunks=[]
for before,after in [('java.net.URI.create(customBgUri).path.orEmpty()','java.nio.file.Paths.get(java.net.URI.create(customBgUri)).toString()'),
 ('wallpaper.toURI().toString()','wallpaper.toPath().toUri().toString()'),('destFile.toURI().toString()','destFile.toPath().toUri().toString()')]:
 assert base.count(before)==1,(before,base.count(before))
 hunks.append({'target':target,'before':before,'after':after,'expectedCount':1})
 candidate=base if 'candidate' not in locals() else candidate;candidate=candidate.replace(before,after)
out=LANE/'prepared/extract-upstream-profile-main.py';safe(out).parent.mkdir(parents=True,exist_ok=True);safe(out).write_text(candidate,encoding='utf-8',newline='\n')
payload={'baseManifestSha256Bytes':sha(manifest),'target':target,'baseProducerSha256Bytes':expected,'candidateProducerSha256Bytes':sha(safe(out).read_bytes()),'hunks':hunks,
 'intent':'Keep original file existence/fallback semantics with canonical Windows file:/// URI and real drive conversion. Android original and historic Profile137 remain unchanged.',
 'sharedChanged':False,'wholeFileInstall':False,'newDependency':False,'actualRootAccepted':False}
safe(LANE/'local-hunks.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'hunks':len(hunks),'payload':str(LANE/'local-hunks.json'),'sha256Bytes':sha(safe(LANE/'local-hunks.json').read_bytes())},indent=2))
