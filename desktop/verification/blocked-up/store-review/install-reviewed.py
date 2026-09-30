"""Default is read-only verification. --apply is for Root's later explicit integration."""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, os, sys, tempfile
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
DEFAULT_REPO=HERE.parents[2]
PLAN_SHA='19ca03407d64e4c4fce848aac01c7b2b88eb8f884ae49c9795e031c9b8258125'
def safe(p):
 value=str(Path(p).absolute())
 return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def digest_bytes(value):return hashlib.sha256(value).hexdigest()
def digest(p):return digest_bytes(safe(p).read_bytes())
def target_in(root,relative):
 value=PurePosixPath(relative)
 if value.is_absolute() or '..' in value.parts or not value.parts or ':' in relative or '\\' in relative:
  raise ValueError('Invalid reviewed relative path: '+relative)
 target=root.joinpath(*value.parts)
 target.resolve().relative_to(root.resolve())
 cursor=target
 while cursor!=root:
  checked=safe(cursor)
  if checked.is_symlink() or (hasattr(checked,'is_junction') and checked.is_junction()):
   raise ValueError('Link/junction in integration target: '+str(cursor))
  cursor=cursor.parent
 return target
def verify(repo,plan):
 for item in plan['frozenChecks']+plan['dependencies']:
  if digest(item['path'])!=item['sha256Bytes']:raise ValueError('Frozen artifact/dependency changed: '+item['path'])
 for item in plan['originalInputs']:
  text=safe(target_in(repo,item['path'])).read_text(encoding='utf-8').replace('\r\n','\n')
  if digest_bytes(text.encode())!=item['sha256']:raise ValueError('Original source/resource changed: '+item['path'])
 for item in plan['platformInputs']:
  if digest(target_in(repo,item['path']))!=item['sha256Bytes']:raise ValueError('Platform helper changed: '+item['path'])
 checked=[]
 for item in plan['files']:
  target=target_in(repo,item['path']);payload=target_in(HERE/'payload',item['path'])
  content=safe(payload).read_bytes()
  if digest_bytes(content)!=item['payloadSha256Bytes']:raise ValueError('Staged payload changed: '+item['path'])
  old=safe(target).read_bytes() if safe(target).exists() else None
  actual=digest_bytes(old) if old is not None else None
  if actual not in (item['baselineSha256Bytes'],item['payloadSha256Bytes']):
   raise ValueError('Product baseline changed; re-review rather than overwrite: '+item['path'])
  checked.append((target,content,old,actual==item['payloadSha256Bytes']))
 return checked
def atomic_write(target,content):
 safe(target.parent).mkdir(parents=True,exist_ok=True)
 descriptor,temporary=tempfile.mkstemp(prefix='blocked-up-install-',suffix='.tmp',dir=str(safe(target.parent)))
 try:
  with os.fdopen(descriptor,'wb') as stream:stream.write(content);stream.flush();os.fsync(stream.fileno())
  os.replace(temporary,str(safe(target)))
 finally:
  if os.path.exists(temporary):os.unlink(temporary)
def install(repo,plan,apply=False):
 checked=verify(repo,plan) # All frozen/source/dependency/payload/baseline checks complete before first write.
 if not apply:return dict(passed=True,readOnly=True,verifiedFiles=len(checked),changedFiles=0)
 written=[]
 try:
  for target,content,old,already in checked:
   if already:continue
   # Recheck this target immediately before the atomic replacement.
   now=safe(target).read_bytes() if safe(target).exists() else None
   if now!=old:raise ValueError('Product changed after preflight: '+str(target))
   atomic_write(target,content);written.append((target,content,old))
 except BaseException:
  for target,content,old in reversed(written):
   if safe(target).read_bytes()!=content:raise RuntimeError('Cannot roll back a concurrently changed target: '+str(target))
   if old is None:safe(target).unlink()
   else:atomic_write(target,old)
  raise
 return dict(passed=True,readOnly=False,verifiedFiles=len(checked),changedFiles=len(written),
  compilationOrRuntimeExecuted=False)
def load_plan():
 path=HERE/'install-plan.json'
 if digest(path)!=PLAN_SHA:raise ValueError('Reviewed plan bytes changed')
 return json.loads(path.read_text(encoding='utf-8'))
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--repo',type=Path,default=DEFAULT_REPO)
 parser.add_argument('--apply',action='store_true')
 args=parser.parse_args()
 print(json.dumps(install(args.repo.resolve(),load_plan(),args.apply)))
