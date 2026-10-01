from pathlib import Path
import hashlib, importlib.util, json, subprocess, difflib, sys
P=Path(__file__).resolve().parent;ROOT=P.parents[2];REPO=ROOT.parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def raw(p):return wide(p).read_bytes().replace(b'\r\n',b'\n')
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
proof=P/('production-proof/'+sys.argv[1]);assert not wide(proof).exists();wide(proof).mkdir(parents=True)
spec=importlib.util.spec_from_file_location('one_controls_producer',P/'prepared/desktop/tools/extract-upstream-video-player-full-controls.py');tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
tool.generate(REPO,proof/'default-generated');default=list(tool.OUTPUTS)
tool.generate(REPO,proof/'standalone-generated',True);standalone=list(tool.OUTPUTS)
inv=json.loads(raw(P/'source-inventory.json'));assert len(default)==len(standalone)==len(inv['outputs'])==46
checks=[];direct=[];selected=[];diffs=[]
for row in inv['outputs']:
 path=row['path'];expected=P/'generated'/path;full=proof/'standalone-generated'/path;prod=proof/'default-generated'/path
 assert sha(full)==sha(expected)==row['sha256LF'],path
 if row['mode']=='direct-complete-original':
  assert not wide(prod).exists();assert sha(expected)==sha(P/'original-stable'/row['origin']);direct.append(path)
 else:
  assert sha(prod)==sha(expected);selected.append(path)
 checks.append(dict(path=path,mode=row['mode'],sha256LF=sha(expected),standaloneByteEqual=True,defaultByteEqual=path in selected,defaultSkipped=path in direct))
 # Complete source outputs have explicit, reversible source deltas. Selected object/declaration
 # output is recorded separately; comparison to a whole file must not imply whole-file copying.
 if path.endswith(row['origin'].removeprefix('app/src/main/java/com/android/purebilibili/')):
  before=raw(P/'original-stable'/row['origin']).decode();after=raw(expected).decode()
  beforeLines=before.splitlines(True);afterLines=after.splitlines(True)
  edits=[];matcher=difflib.SequenceMatcher(None,beforeLines,afterLines,autojunk=False)
  for tag,a,b,c,d in matcher.get_opcodes():
   if tag!='equal':edits.append(dict(originalLineRange=[a,b],generatedLineRange=[c,d],before=beforeLines[a:b],after=afterLines[c:d]))
  restored=list(afterLines)
  for e in reversed(edits):
   c,d=e['generatedLineRange'];assert restored[c:d]==e['after'];restored=restored[:c]+e['before']+restored[d:]
  assert ''.join(restored)==before
  name=path.replace('/','__')
  patch=''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile=row['origin'],tofile=path))
  wide(proof/'source-diffs').mkdir(parents=True,exist_ok=True);wide(proof/'source-diffs'/str(name+'.patch')).write_text(patch,encoding='utf-8')
  save(proof/'source-diffs'/str(name+'.json'),dict(source=row['origin'],output=path,edits=edits,sourceSha256LF=hashlib.sha256(before.encode()).hexdigest(),generatedSha256LF=hashlib.sha256(after.encode()).hexdigest(),reverseExact=True))
  diffs.append(dict(path=path,source=row['origin'],edits=len(edits),reverseExact=True,direct=before==after))
save(proof/'source-checks.json',dict(passed=True,commit=tool.COMMIT,sourceIdentities=len(inv['sources']),standaloneOutputs=46,selectedOutputs=len(selected),directOutputs=len(direct),selected=selected,directSkipped=direct,checks=checks,fullSourceReversibleDiffs=diffs,scope='source-byte and explicit inverse-delta proof only; platform adapters are included in generated outputs, no GUI/runtime acceptance'))
print(json.dumps(dict(passed=True,selected=len(selected),direct=len(direct),fullSourceInverseFiles=len(diffs))))
