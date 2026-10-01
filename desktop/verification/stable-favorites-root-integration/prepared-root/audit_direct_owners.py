from pathlib import Path
import json,hashlib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def lf(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
delta=json.loads(safe(MAIN/'desktop/.local/stable-favorites-parity/prepared/registry-delta.json').read_text())
raw=json.loads(safe(CANDIDATE/'desktop/upstream-sources.json').read_text()); registry=raw['sources'] if isinstance(raw,dict) else raw
by_path={r['path']:r for r in registry}
generated=list((CANDIDATE/'desktop/build/generated').rglob('*.kt'))
rows=[]
for r in delta:
 for o in r['outputs']:
  if o['mode']!='direct':continue
  owner=by_path.get(r['path'])
  matches=[]
  for p in generated:
   if p.name!=Path(o['path']).name:continue
   digest=hashlib.sha256(lf(p).encode()).hexdigest()
   if digest==o['sha256LfUtf8']:matches.append(str(p.relative_to(CANDIDATE)))
  rows.append(dict(source=r['path'],output=o['path'],expectedLF=o['sha256LfUtf8'],registryMode=owner.get('mode') if owner else None,exactGeneratedOwners=matches,passed=len(matches)==1))
report=dict(scope='Read-only current candidate generated body producer audit, not compile acceptance',outputs=len(rows),allPassed=all(r['passed'] for r in rows),rows=rows)
safe(HERE/'direct-owner-audit.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(dict(outputs=len(rows),allPassed=report['allPassed'],notPassed=[r for r in rows if not r['passed']]),indent=2))
