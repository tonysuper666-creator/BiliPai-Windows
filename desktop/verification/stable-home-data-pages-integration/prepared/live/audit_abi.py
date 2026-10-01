from pathlib import Path
import hashlib,json,zipfile,struct
LANE=Path(__file__).resolve().parent
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def parse(data):
 offset=8
 def u1():
  nonlocal offset
  n=data[offset];offset+=1;return n
 def u2():
  nonlocal offset
  n=struct.unpack_from('>H',data,offset)[0];offset+=2;return n
 def u4():
  nonlocal offset
  n=struct.unpack_from('>I',data,offset)[0];offset+=4;return n
 assert data[:4]==b'\xca\xfe\xba\xbe'
 pool={};count=u2();index=1
 while index<count:
  tag=u1()
  if tag==1:
   n=u2();pool[index]=data[offset:offset+n].decode('utf-8',errors='replace');offset+=n
  elif tag in [3,4]:offset+=4
  elif tag in [5,6]:offset+=8;index+=1
  elif tag in [7,8,16,19,20]:offset+=2
  elif tag in [9,10,11,12,17,18]:offset+=4
  elif tag==15:offset+=3
  else:raise AssertionError(tag)
  index+=1
 offset+=6
 interfaces=u2();offset+=interfaces*2
 def members():
  result=[]
  for _ in range(u2()):
   access=u2();name=pool[u2()];descriptor=pool[u2()]
   for _ in range(u2()):
    u2();offsetSkip=u4()
    nonlocal offset
    offset+=offsetSkip
   result.append((access,name,descriptor))
  return result
 members();return members()
aud=json.loads(read(LANE/'input-audit.json'));candidate=json.loads(read(LANE/'compile-02/compile-result.json'))
actualclasses=set();actualmethods={}
for row in aud['verifiedDependencyPins']:
 with zipfile.ZipFile(safe(row['path'])) as jar:
  for name in jar.namelist():
   if not name.endswith('.class'):continue
   actualclasses.add(name)
   if name.startswith('com/android/purebilibili/') and name.endswith('Kt.class'):
    for access,method,desc in parse(jar.read(name)):
     if access&9==9:actualmethods.setdefault((name.rsplit('/',1)[0],method,desc),[]).append(name)
rawclassoverlap=[]
with zipfile.ZipFile(safe(aud['stable-home-request-ports-parity']['jar'])) as jar:
 rawclassoverlap=[n for n in jar.namelist() if n.endswith('.class') and n in actualclasses]
 allowed=['com/bilipai/desktop/data/DesktopRepository','com/bilipai/desktop/data/DesktopSessionStore','com/bilipai/desktop/data/DesktopSessionEpoch']
 assert all(any(n.startswith(a) for a in allowed) for n in rawclassoverlap),rawclassoverlap
invalid=[];methodoverlap=[];methodcount=0;classcount=0
with zipfile.ZipFile(safe(candidate['jar'])) as jar:
 for name in jar.namelist():
  if not name.endswith('.class'):continue
  classcount+=1;assert name not in actualclasses,name
  methods=parse(jar.read(name));methodcount+=len(methods)
  for access,method,desc in methods:
   if any(c in method for c in '.;/[') or ('<' in method and method not in ['<init>','<clinit>']):invalid.append([name,method])
   if name.endswith('Kt.class') and access&9==9:
    key=(name.rsplit('/',1)[0],method,desc)
    if key in actualmethods:methodoverlap.append([name,method,desc,actualmethods[key]])
assert not invalid,invalid
assert not methodoverlap,methodoverlap
out={'actualProductEntries':97,'candidateClasses':classcount,'candidateMethods':methodcount,'classFqnOverlap':[],'samePackagePublicStaticMethodOverlap':methodoverlap,'invalidJvmMethodNames':invalid,'prospectiveRaw694OverrideClassCount':len(rawclassoverlap),'prospectiveRaw694OverrideClasses':rawclassoverlap,'prospectiveRaw694OverrideFamilies':allowed,'actualRootRuntime':False}
safe(LANE/'abi-audit.json').write_text(json.dumps(out,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in out.items() if k!='prospectiveRaw694OverrideClasses'},indent=2))
