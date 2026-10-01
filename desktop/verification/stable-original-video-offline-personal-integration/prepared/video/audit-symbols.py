from pathlib import Path
import ast,hashlib,json,struct,sys,zipfile
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-47'
helper=MAIN/'desktop/.local/dynamic-editor-detail-parity/protocol-review/top-level-uniqueness-review/review.py'
text=helper.read_text(encoding='utf-8');tree=ast.parse(text);node=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='methods');exec(ast.get_source_segment(text,node))
candidate=LANE/('runs/'+sys.argv[1]+'/candidate.jar')
actual={};own={};index={};intersection=[];classOverlaps=[];illegal=[]
with zipfile.ZipFile(SNAP/'main-kotlin.jar')as z:
 for e in z.namelist():
  if not e.endswith('.class'):continue
  actual[e]=hashlib.sha256(z.read(e)).hexdigest()
  if e.endswith('Kt.class')and '$'not in e:
   for m in methods(z.read(e),e):index.setdefault(m['key'],[]).append(m)
with zipfile.ZipFile(candidate)as z:
 for e in z.namelist():
  if not e.endswith('.class'):continue
  data=z.read(e);own[e]=hashlib.sha256(data).hexdigest()
  if b'NON_LOCAL_RETURN'in data:illegal.append(e)
  if e in actual:classOverlaps.append(dict(classEntry=e,candidateSha256Bytes=own[e],actualSha256Bytes=actual[e]))
  if e.endswith('Kt.class')and '$'not in e:
   for m in methods(data,e):
    for other in index.get(m['key'],[]):intersection.append(dict(package=m['package'],name=m['name'],parameters=m['parameters'],candidateOwner=e,actualOwner=other['owner']))
allowed=('com/bilipai/desktop/data/DesktopDynamicCardOperations','com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoMetadataKt')
unexpectedClasses=[r for r in classOverlaps if not r['classEntry'].startswith(allowed)]
unexpectedMethods=[r for r in intersection if not r['candidateOwner'].startswith(allowed)]
result=dict(passed=not unexpectedClasses and not unexpectedMethods and not illegal,actual47MainKotlinSha256Bytes=hashlib.sha256((SNAP/'main-kotlin.jar').read_bytes()).hexdigest(),candidateJarSha256Bytes=hashlib.sha256(candidate.read_bytes()).hexdigest(),candidateClasses=len(own),actualClasses=len(actual),declaredExistingOverrideFamilies=list(allowed),existingClassOverlapCount=len(classOverlaps),existingMethodIntersectionCount=len(intersection),unexpectedClasses=unexpectedClasses,unexpectedTopLevelMethods=unexpectedMethods,illegalNonLocalReturnClasses=illegal,classOverlaps=classOverlaps,topLevelMethodIntersections=intersection)
(LANE/'install-audit-02/symbol-audit.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in result.items()if k not in ('classOverlaps','topLevelMethodIntersections')}));assert result['passed']
