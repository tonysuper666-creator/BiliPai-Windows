from pathlib import Path
import hashlib, json, os
HERE=Path(__file__).resolve().parent
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def relative(p):
 v=str(p);v=v[len(EXT):] if v.startswith(EXT) else v
 return Path(v).relative_to(HERE).as_posix()
roots=['original-alpha9','original-stable','original-tools','method-diffs','prepared','generated']
files=[HERE/n for n in ['audit.py','prepare-detail-reply.py','prove-identity-gate.py','freeze-detail-reply.py','source-delta.json',
 'method-token-original-diff.json','detail-reply.patch','detail-reply-members-verification.json','verify-detail-reply.log',
 'detail-reply-source-review.json','detail-reply-handoff.json','identity-gate-proof.json']]
for root in roots:
 for base,dirs,names in os.walk(safe(HERE/root)):
  dirs[:]=[d for d in dirs if d!='__pycache__']
  files += [Path(base)/name for name in names if not name.endswith('.pyc')]
rows=[{'path':relative(p),'sha256Bytes':sha(p),'sizeBytes':safe(p).stat().st_size} for p in sorted(files,key=relative)]
manifest={'scope':'stable detail/reply source and generator proofs only; no Main Kotlin/runtime/EXE','artifacts':rows,
 'fixedStableCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','artifactCount':len(rows),
 'MainChanged':False,'sharedGradle':False,'editorStreamingStillSeparate':True}
safe(HERE/'detail-reply-evidence-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({'manifestSha256':sha(HERE/'detail-reply-evidence-manifest.json'),'handoffSha256':sha(HERE/'detail-reply-handoff.json'),
 'artifactCount':len(rows)},indent=2))
