from pathlib import Path
import ast,hashlib,json,sys,zipfile,struct
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-55'
helper=MAIN/'desktop/.local/dynamic-editor-detail-parity/protocol-review/top-level-uniqueness-review/review.py'
t=helper.read_text(encoding='utf-8');tree=ast.parse(t);node=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='methods');exec(ast.get_source_segment(t,node))
jar=P/('runs/'+sys.argv[1]+'/candidate.jar');actual={};index={};classRows=[];methodRows=[];illegal=[]
for j in [SNAP/'main-kotlin.jar',SNAP/'main-java.jar']:
 with zipfile.ZipFile(j)as z:
  for e in z.namelist():
   if not e.endswith('.class'):continue
   actual[e]=hashlib.sha256(z.read(e)).hexdigest()
   if e.endswith('Kt.class')and '$'not in e:
    for m in methods(z.read(e),e):index.setdefault(m['key'],[]).append(m)
with zipfile.ZipFile(jar)as z:
 entries=[e for e in z.namelist()if e.endswith('.class')]
 for e in entries:
  data=z.read(e)
  if b'NON_LOCAL_RETURN'in data:illegal.append(e)
  if e in actual:classRows.append(dict(classEntry=e,candidateSHA=hashlib.sha256(data).hexdigest(),actualSHA=actual[e]))
  if e.endswith('Kt.class')and '$'not in e:
   for m in methods(data,e):
    for other in index.get(m['key'],[]):methodRows.append(dict(package=m['package'],name=m['name'],parameters=m['parameters'],candidateOwner=e,actualOwner=other['owner']))
allowed='com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol'
unexpected=[r for r in classRows if not r['classEntry'].startswith(allowed)]
result=dict(passed=not unexpected and not methodRows and not illegal,scope='prospective full renderer against actual55; Core-only two-member append proof override',candidateSHA=hashlib.sha256(jar.read_bytes()).hexdigest(),actualKotlinSHA=hashlib.sha256((SNAP/'main-kotlin.jar').read_bytes()).hexdigest(),candidateClasses=len(entries),actualClasses=len(actual),declaredExistingOverrideFamily=allowed,classOverlaps=classRows,unexpectedClasses=unexpected,topLevelMethodIntersections=methodRows,illegalNonLocalReturnClasses=illegal)
(P/('runs/'+sys.argv[1]+'/symbol-audit.json')).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in result.items()if k!='classOverlaps'}));assert result['passed']
