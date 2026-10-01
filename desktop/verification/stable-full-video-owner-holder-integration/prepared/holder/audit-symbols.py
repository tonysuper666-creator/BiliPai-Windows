from pathlib import Path
import collections, hashlib, json, struct, zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-65'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def parse(data,entry):
 p=8
 def u1():
  nonlocal p;x=data[p];p+=1;return x
 def u2():
  nonlocal p;x=struct.unpack_from('>H',data,p)[0];p+=2;return x
 def u4():
  nonlocal p;x=struct.unpack_from('>I',data,p)[0];p+=4;return x
 cp=[None]*u2();i=1
 while i<len(cp):
  tag=u1()
  if tag==1:
   n=u2();cp[i]=(tag,data[p:p+n].decode('utf8',errors='replace'));p+=n
  elif tag in(3,4):p+=4
  elif tag in(5,6):p+=8;i+=1
  elif tag in(7,8,16,19,20):cp[i]=(tag,u2())
  elif tag in(9,10,11,12,17,18):cp[i]=(tag,u2(),u2())
  elif tag==15:cp[i]=(tag,u1(),u2())
  else:raise ValueError((entry,tag))
  i+=1
 def bad(n):return any(c in n for c in '.;[/<>')and n not in('<init>','<clinit>')
 invalid=[]
 for row in cp:
  if row and row[0]in(10,11):
   nat=cp[row[2]];n=cp[nat[1]][1]
   if bad(n):invalid.append(dict(kind='method-reference',name=n,descriptor=cp[nat[2]][1]))
 p+=6;ninterfaces=u2();p+=2*ninterfaces;methods=[];fields=[]
 def attrs():
  nonlocal p
  for _ in range(u2()):
   u2();size=u4();p+=size
 def member(method):
  flags=u2();n=cp[u2()][1];d=cp[u2()][1];attrs()
  if method and bad(n):invalid.append(dict(kind='method-declaration',name=n,descriptor=d))
  if flags&1 and flags&8 and not n.startswith('access$'):
   (methods if method else fields).append(dict(owner=entry,package=entry.rsplit('/',1)[0].replace('/','.'),name=n,descriptor=d,parameters=d[:d.index(')')+1]if method else None))
 for _ in range(u2()):member(False)
 for _ in range(u2()):member(True)
 return methods,fields,invalid
jar=P/'runs/holder-05/candidate.jar';expected='5d685f776192b040a2323143d2375823e6346ffe50c92fd71463f82a5c5a0d33';assert sha(jar)==expected
ref=MAIN/'desktop/.local/stable-video-full-owner-parity/whole-compile-10-with-kotlin-module-02/candidate.jar';assert sha(ref)=='e310cb71a3ccd2ae2fdc81f69fa8da8dbf0b06c4e28e11209bdf65765b1c7f67'
cp=json.loads(wide(SNAP/'ordered-runtime-cp.json').read_bytes());assert len(cp)==101
actualClasses=set()
for r in cp:
 assert sha(r['path'])==r['sha256Bytes']
 with zipfile.ZipFile(wide(r['path']))as z:actualClasses|={e for e in z.namelist()if e.endswith('.class')}
allrows={};issues=[];candidateClasses=[]
for label,path in [('candidate',jar),('actual65',SNAP/'main-kotlin.jar'),('prospectiveVM10',ref)]:
 methods=[];fields=[]
 with zipfile.ZipFile(wide(path))as z:
  for entry in z.namelist():
   if not entry.endswith('.class'):continue
   if label=='candidate':candidateClasses.append(entry)
   m,f,bad=parse(z.read(entry),entry)
   if label=='candidate' and bad:issues.append(dict(classFile=entry,invalid=bad))
   if entry.endswith('Kt.class')and'$'not in entry:methods+=m;fields+=f
 allrows[label]=(methods,fields)
contextPrefix='com/bilipai/desktop/ui/DesktopOriginalPlayer'
contextNames={e for e in candidateClasses if e.startswith(contextPrefix) and (e in actualClasses or e in zipfile.ZipFile(wide(ref)).namelist())}
overlaps=sorted(set(candidateClasses)&actualClasses);refOverlap=sorted(set(candidateClasses)&set(zipfile.ZipFile(wide(ref)).namelist()))
assert set(overlaps)<=contextNames and set(refOverlap)<=contextNames,(overlaps,refOverlap)
def key(r,field=False):return(r['package'],r['name'])if field else(r['package'],r['name'],r['parameters'])
collisions=[];internal=[]
for field in[False,True]:
 index=1 if field else 0;candidate=allrows['candidate'][index];by=collections.defaultdict(list)
 for r in candidate:by[key(r,field)].append(r)
 for k,v in by.items():
  if len(v)>1:internal.append(dict(kind='field'if field else'method',key=k,rows=v))
 for label in['actual65','prospectiveVM10']:
  b=collections.defaultdict(list)
  for r in allrows[label][index]:b[key(r,field)].append(r)
  for r in candidate:
   if r['owner']in contextNames:continue
   for other in b.get(key(r,field),[]):collisions.append(dict(kind='field'if field else'method',compared=label,candidate=r,existing=other))
result=dict(passed=not(issues or collisions or internal),candidateJar=dict(path=str(jar),sha256Bytes=sha(jar)),actual65=dict(manifestSHA256=sha(SNAP/'manifest.json'),orderedRuntimeSHA256=sha(SNAP/'ordered-runtime-cp.json'),mainKotlinSHA256=sha(SNAP/'main-kotlin.jar'),runtimeEntries=101),explicitProspectiveVM=dict(path=str(ref),sha256Bytes=sha(ref)),classes=len(candidateClasses),candidatePublicTopLevelJvmMethods=len(allrows['candidate'][0]),candidatePublicTopLevelJvmFields=len(allrows['candidate'][1]),actualPublicTopLevelJvmMethods=len(allrows['actual65'][0]),prospectivePublicTopLevelJvmMethods=len(allrows['prospectiveVM10'][0]),productionClassFqnOverlapExcludedCompileOnlyContext=len(set(overlaps)-contextNames),declaredCompileOnlyContextClassOverlaps=overlaps,prospectiveVMClassOverlaps=refOverlap,topLevelCollisions=collisions,internalTopLevelCollisions=internal,invalidJvmMethodNames=issues,staticClassfileOnly=True,runtimeLoadedAllClasses=False,productAcceptance=False)
wide(P/'symbol-audit.json').write_bytes((json.dumps(result,indent=2)+'\n').encode())
print(json.dumps({k:v for k,v in result.items()if not isinstance(v,(dict,list))}));assert result['passed'],(issues,collisions,internal)
