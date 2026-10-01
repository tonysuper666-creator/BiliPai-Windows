from pathlib import Path
import ast,hashlib,json,sys,zipfile,struct
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
helper=MAIN/'desktop/.local/dynamic-editor-detail-parity/protocol-review/top-level-uniqueness-review/review.py'
t=helper.read_text(encoding='utf-8');tree=ast.parse(t);node=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='methods');exec(ast.get_source_segment(t,node))
actualJars=[MAIN/'desktop/.local/stable-product-snapshot-47/main-kotlin.jar',MAIN/'desktop/.local/stable-video-detail-full-ui-parity/runs/12/candidate.jar',MAIN/'desktop/.local/stable-video-player-full-controls-parity/runs/11/candidate.jar']
candidate=LANE/('content-runs/'+sys.argv[1]+'/candidate.jar');index={};classes={};overlap=[];intersections=[];illegal=[]
for jar in actualJars:
 with zipfile.ZipFile(jar)as z:
  for e in z.namelist():
   if not e.endswith('.class'):continue
   classes.setdefault(e,[]).append(str(jar))
   if e.endswith('Kt.class')and '$'not in e:
    for m in methods(z.read(e),e):index.setdefault(m['key'],[]).append(dict(**m,jar=str(jar)))
with zipfile.ZipFile(candidate)as z:
 own=[e for e in z.namelist()if e.endswith('.class')]
 for e in own:
  b=z.read(e)
  if b'NON_LOCAL_RETURN'in b:illegal.append(e)
  if e in classes:overlap.append(dict(classEntry=e,actualOwners=classes[e]))
  if e.endswith('Kt.class')and '$'not in e:
   for m in methods(b,e):
    for other in index.get(m['key'],[]):intersections.append(dict(package=m['package'],name=m['name'],parameters=m['parameters'],candidateOwner=e,actualOwner=other['owner'],actualJar=other['jar']))
result=dict(passed=not overlap and not intersections and not illegal,scope='actual47 plus explicitly prospective frozen stage1 and stage2 foundations; not runtime acceptance',candidateJarSha256Bytes=hashlib.sha256(candidate.read_bytes()).hexdigest(),candidateClasses=len(own),referenceJars=[dict(path=str(j),sha256Bytes=hashlib.sha256(j.read_bytes()).hexdigest())for j in actualJars],classOverlaps=overlap,topLevelMethodIntersections=intersections,illegalNonLocalReturnClasses=illegal)
out=LANE/('content-runs/'+sys.argv[1]+'/symbol-audit.json');out.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result));assert result['passed']
