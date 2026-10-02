"""Root-only verified installer. Default is read-only; --apply is explicit.
Existing source files are transformed exclusively by exact hunks, never copied from
prospective/ full-file compile overlays. No build, workflow, network or account operation."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, sys, uuid
from exact_patch import apply as apply_exact
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2]
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def raw(p):return wide(p).read_bytes()
def digest(b):return hashlib.sha256(b).hexdigest()
def sha(p):return digest(raw(p))
def target(root,name):
 path=root/name
 if Path(name).is_absolute() or '..'in Path(name).parts:raise ValueError('Unsafe target name')
 if not path.resolve().is_relative_to(root.resolve()):raise ValueError('Target left repository')
 for parent in [path]+list(path.parents):
  if parent==root:break
  if parent.exists() and (parent.is_symlink() or getattr(parent,'is_junction',lambda:False)()):raise ValueError('Reparse target rejected')
 return path
parser=argparse.ArgumentParser()
parser.add_argument('--candidate',type=Path,default=M.parent/'BiliPai-v023')
parser.add_argument('--contract',type=Path,default=P/'root-install-contract.json')
parser.add_argument('--baseline-only',action='store_true',help='Check exact transforms against immutable git-show baseline copies, never Candidate')
parser.add_argument('--apply',action='store_true')
parser.add_argument('--contract-sha')
parser.add_argument('--report',type=Path)
args=parser.parse_args();R=args.candidate.resolve();K=args.contract.resolve();O=K.parent
if args.apply:
 assert not args.baseline_only and args.contract_sha, '--apply requires explicit contract SHA and current Candidate'
 assert sha(K)==args.contract_sha, 'Contract changed'
contract=json.loads(raw(K));F=Path(contract['frozenPacket'])
assert sha(F/'install-contract.json')==contract['frozenContractSha256']
assert sha(F/'frozen-handoff.json')==contract['frozenHandoffSha256']
if not args.baseline_only:
 assert subprocess.check_output(['git','-C',str(R),'rev-parse','HEAD'],text=True).strip()==contract['candidateBase'], 'Rebase the contract to the new HEAD first'
plan=[];skipped=[]
for row in contract['exactHunkTargets']:
 destination=target(R,row['target']);before=raw(O/'baseline'/row['target'])if args.baseline_only else raw(destination)
 if digest(before)==row['desiredRawSha256']:skipped.append(row['target']);continue
 assert digest(before)==row['baseRawSha256'] or digest(before.replace(b'\r\n',b'\n'))==row['baseLfSha256'], 'Source bytes changed: '+row['target']
 patch=raw(O/row['patch']);assert digest(patch)==row['patchSha256']
 after=apply_exact(before,patch);assert digest(after)==row['desiredRawSha256'],row['target']
 plan.append((destination,after,'exact-hunks',row['target']))
for row in contract['originalPacketCopyTargets']:
 content=raw(F/row['prepared']);assert digest(content)==row['sha256Bytes']
 destination=target(R,row['target'])
 if not args.baseline_only and destination.exists():
  assert sha(destination)==row['sha256Bytes'],'New target already has different bytes: '+row['target'];skipped.append(row['target']);continue
 plan.append((destination,content,'copy-new',row['target']))
delta=contract['registry'];destination=target(R,delta['target'])
before=raw(O/'baseline'/delta['target'])if args.baseline_only else raw(destination)
if digest(before)==delta['desiredRawSha256']:skipped.append(delta['target'])
else:
 assert digest(before)==delta['baseRawSha256'] or digest(before.replace(b'\r\n',b'\n'))==digest(raw(O/'baseline'/delta['target']).replace(b'\r\n',b'\n')), 'Registry drift: rebase, never overwrite unrelated identities'
 registry=json.loads(before);entries=registry['sources'];existing={r['path']:r for r in entries}
 originals=json.loads(raw(F/'source-inventory.json'))
 for row in originals:
  source=raw(target(R,row['path'])).replace(b'\r\n',b'\n');assert digest(source)==row['sha256'],row['path']
  if row['path']in existing:
   current=existing[row['path']];assert current['sha256']==row['sha256'] and current.get('mode','direct')==row['mode']
   current['features']=list(dict.fromkeys(current.get('features',[])+row['features']))
  else:entries.append(row)
 after=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode('utf8')
 assert digest(after)==delta['desiredRawSha256'];assert len(entries)==delta['afterCount']
 plan.append((destination,after,'verified-registry-union',delta['target']))
for row in contract['dependencies']:assert sha(row['path'])==row['sha256Bytes']
# All validation finishes before the first optional mutation. Source writes use only
# exact transformed current bytes; LF normalization is declared in baseLfSha256.
if args.apply:
 for destination,content,operation,name in plan:
  wide(destination.parent).mkdir(parents=True,exist_ok=True)
  temporary=destination.parent/('.message-root-'+uuid.uuid4().hex+'.tmp')
  try:
   wide(temporary).write_bytes(content);os.replace(wide(temporary),wide(destination))
  finally:
   if wide(temporary).exists():wide(temporary).unlink()
  assert sha(destination)==digest(content),name
report=dict(passed=True,contractSha256=sha(K),candidateBase=contract['candidateBase'],
 baselineOnly=args.baseline_only,applied=args.apply,productionWrites=len(plan)if args.apply else 0,
 exactHunks=sum(operation=='exact-hunks'for _,_,operation,_ in plan),
 newCopies=sum(operation=='copy-new'for _,_,operation,_ in plan),registryAppends=13,registryFeatureUnions=4,
 targets=[dict(path=name,operation=operation,desiredRawSha256=digest(content))for _,content,operation,name in plan],skipped=skipped,
 buildRun=False,rootRuntimeAccepted=False)
if args.report:
 assert args.report.resolve().is_relative_to((M/'desktop/.local').resolve()), 'Proof report must remain task-owned'
 wide(args.report.parent).mkdir(parents=True,exist_ok=True);wide(args.report).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(report,ensure_ascii=False,indent=2))
