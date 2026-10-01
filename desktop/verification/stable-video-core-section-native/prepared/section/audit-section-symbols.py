from pathlib import Path
import ast,hashlib,json,sys,zipfile,struct
P=Path(__file__).resolve().parent;MAIN=P.parents[2]
helper=MAIN/'desktop/.local/dynamic-editor-detail-parity/protocol-review/top-level-uniqueness-review/review.py'
t=helper.read_text(encoding='utf-8');tree=ast.parse(t);node=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='methods');exec(ast.get_source_segment(t,node))
refs=[MAIN/'desktop/.local/stable-product-snapshot-50/main-kotlin.jar',MAIN/'desktop/.local/stable-video-state-holder-parity/core-compile-03/candidate.jar',MAIN/'desktop/.local/stable-video-player-page-parity/content-runs/06/candidate.jar']
candidate=P/('section-runs/'+sys.argv[1]+'/candidate.jar');index={};classes={};overlap=[];intersections=[];illegal=[];declaredClasses=[];declaredMethods=[]
allowedPrefixes=['com/bilipai/desktop/ui/DesktopOriginalMpvOverlayControl','com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext','com/bilipai/desktop/ui/DesktopOriginalPlayerPreferenceValues','com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsDataStore','com/bilipai/desktop/ui/DesktopOriginalPlayerMirrorPreferences','com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContextKt']
def allowed(e):return any(e==a+'.class'or e.startswith(a+'$')for a in allowedPrefixes)
for jar in refs:
 with zipfile.ZipFile(jar)as z:
  for e in z.namelist():
   if not e.endswith('.class'):continue
   classes.setdefault(e,[]).append(str(jar))
   if e.endswith('Kt.class')and'$'not in e:
    for m in methods(z.read(e),e):index.setdefault(m['key'],[]).append(dict(**m,jar=str(jar)))
with zipfile.ZipFile(candidate)as z:
 own=[e for e in z.namelist()if e.endswith('.class')]
 for e in own:
  b=z.read(e)
  if b'NON_LOCAL_RETURN'in b:illegal.append(e)
  if e in classes:
   row=dict(classEntry=e,referenceOwners=classes[e]);(declaredClasses if allowed(e)else overlap).append(row)
  if e.endswith('Kt.class')and'$'not in e:
   for m in methods(b,e):
    for other in index.get(m['key'],[]):
     row=dict(package=m['package'],name=m['name'],parameters=m['parameters'],candidateOwner=e,referenceOwner=other['owner'],referenceJar=other['jar']);(declaredMethods if allowed(e)and allowed(other['owner'])else intersections).append(row)
result=dict(passed=not overlap and not intersections and not illegal,scope='actual50 + explicit parent core03 + frozen Stage3. Only two exact whole proof families are declared overrides; installation applies 5+2 hunks, never copies their whole proof files.',candidateJarSha256Bytes=hashlib.sha256(candidate.read_bytes()).hexdigest(),candidateClasses=len(own),referenceJars=[dict(path=str(j),sha256Bytes=hashlib.sha256(j.read_bytes()).hexdigest())for j in refs],classOverlaps=overlap,topLevelMethodIntersections=intersections,declaredProofFamilyClassOverlaps=declaredClasses,declaredProofFamilyMethodIntersections=declaredMethods,illegalNonLocalReturnClasses=illegal,productAcceptance=False)
(P/('section-runs/'+sys.argv[1]+'/symbol-audit.json')).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(passed=result['passed'],classes=len(own),unexpectedClasses=overlap,unexpectedMethods=intersections,illegalClasses=illegal,declaredProofClasses=len(declaredClasses),declaredProofMethods=len(declaredMethods))));assert result['passed']
