from pathlib import Path
import hashlib,json
P=Path(__file__).resolve().parent
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def rd(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(rd(p)).hexdigest()
def save(p,v):wide(p).write_bytes((json.dumps(v,indent=2,ensure_ascii=False)+'\n').encode())
assert json.loads(rd(P/'source-audit.json'))['passed']
assert json.loads(rd(P/'compile/01/result.json'))['passed']
r=json.loads(rd(P/'runs/01/result.json'));assert r['passed'] and r['assertions']==26
rows=[];excluded=[]
for p in wide(P).rglob('*'):
 if not p.is_file():continue
 rel=str(p.relative_to(wide(P))).replace('\\','/')
 if rel=='frozen-handoff.json':continue
 if p.suffix in ['.jar','.class','.pyc'] or '/runtime/private-store/' in '/'+rel or rel.startswith(('generated/','baseline-generated/')) and not rel.endswith('/SponsorBlockRepository.kt'):
  excluded.append(dict(path=rel,reason='binary/private fixture Store/unchanged existing producer output'));continue
 rows.append(dict(path=rel,sha256Bytes=sha(p),bytes=len(rd(p))))
save(P/'frozen-handoff.json',dict(frozen=True,scope='source-only public Sponsor postJson execution admission supplement',baseFinalWrite120SHA256Bytes='829c748364219b1458151c546bbdae62d8cd053f613d877b94285f9b9143f5cb',actual71ManifestSHA256Bytes='416f1ec5fdf577f13c607d52248ca3c97d39420d363bac78f500d119053e6ad1',actualRuntimeEntries=101,prospectiveExistingFamilies=['frozen120 helper','SponsorBlockRepository'],sourceInputs=2,classes=28,compilePassed=True,memoryCallGuardAssertions=26,fullOriginalRepositoryInverse=True,sourceOnlyInstall='install-exact-hunks.json',wholeFileInstall=False,newIdentityStoreClientActorScope=False,originalRepositoryPublicHttpExecuted=False,httpAccountsNativeGui=False,rawRows=len(rows),artifacts=sorted(rows,key=lambda x:x['path']),excluded=sorted(excluded,key=lambda x:x['path']),boundaries=['Root supplies fixed real native completed ticket and optional upload consent','accepted final request permit is in-flight and cannot recall sent request','Sponsor on-load GET and standalone settings status/user-info unchanged']))
for row in rows:
 assert sha(P/row['path'])==row['sha256Bytes'] and len(rd(P/row['path']))==row['bytes']
print(json.dumps(dict(path=str(P/'frozen-handoff.json'),sha256Bytes=sha(P/'frozen-handoff.json'),rawRows=len(rows)),indent=2))
