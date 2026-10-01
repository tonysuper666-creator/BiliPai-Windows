from pathlib import Path
import hashlib,json,struct,sys,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8')
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def staticMethods(raw,owner):
 index=8
 def u1():
  nonlocal index;value=raw[index];index+=1;return value
 def u2():
  nonlocal index;value=struct.unpack_from('>H',raw,index)[0];index+=2;return value
 def u4():
  nonlocal index;value=struct.unpack_from('>I',raw,index)[0];index+=4;return value
 count=u2();pool={};position=1
 while position<count:
  tag=u1()
  if tag==1:
   length=u2();pool[position]=raw[index:index+length].decode('utf-8',errors='replace');index+=length
  elif tag in [3,4,9,10,11,12,17,18]:index+=4
  elif tag in [5,6]:index+=8;position+=1
  elif tag in [7,8,16,19,20]:index+=2
  elif tag==15:index+=3
  else:raise AssertionError(tag)
  position+=1
 index+=6;interfaceCount=u2();index+=interfaceCount*2
 def skipAttributes():
  nonlocal index
  for _ in range(u2()):u2();length=u4();index+=length
 for _ in range(u2()):index+=6;skipAttributes()
 methods=[]
 for _ in range(u2()):
  flags=u2();name=pool[u2()];descriptor=pool[u2()];skipAttributes()
  if flags&9==9 and not flags&0x1000:
   methods.append({'owner':owner,'package':owner.rsplit('.',1)[0],'name':name,'parameters':descriptor[:descriptor.index(')')+1],'descriptor':descriptor})
 return methods
def wrappers(jar,packages=None):
 records=[]
 with zipfile.ZipFile(safe(jar))as z:
  for name in sorted(z.namelist()):
   if name.endswith('Kt.class')and'$'not in name:
    owner=name[:-6].replace('/','.');package=owner.rsplit('.',1)[0]
    if packages is None or package in packages:records+=staticMethods(z.read(name),owner)
 return records
number=sys.argv[1];compiled=LANE/('compile-audio-'+number)
result=json.loads(safe(compiled/'compile-result.json').read_text());assert result['status']=='PASS'
jar=Path(result['candidateJar']['path']);assert sha(jar)==result['candidateJar']['sha256Bytes']
snap=MAIN/'desktop/.local/stable-product-snapshot-61';assert sha(snap/'manifest.json')=='6b5777dbe6855ddb9529f612580c11aa24dfcd23c771828e51369d610e4a9445'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text());assert len(cp)==101
own=wrappers(jar);product=wrappers(cp[1]['path'],{r['package']for r in own})
key=lambda r:(r['package'],r['name'],r['parameters'])
intersection=sorted(set(map(key,own))&set(map(key,product)));assert not intersection,intersection
changes=json.loads(safe(LANE/'audio-adaptations.json').read_text());pins=json.loads(safe(LANE/'audio-source-pins.json').read_text());bodies=[]
for path,pin in pins.items():
 original=safe(LANE/'original'/path).read_text(encoding='utf-8');assert hashlib.sha256(original.encode()).hexdigest()==pin['sha256LF']
 adapted=safe(LANE/'prepared/generated'/path.removeprefix('app/src/main/java/')).read_text(encoding='utf-8')
 for change in changes[path.removeprefix('app/src/main/java/com/android/purebilibili/')][::-1]:
  i=change['index'];assert adapted[i:i+len(change['after'])]==change['after'];adapted=adapted[:i]+change['before']+adapted[i+len(change['after']):]
 assert adapted==original
 bodies.append({'original':path,'sha256LF':pin['sha256LF'],'wholeBodyExactInverse':True,'declaredAdaptations':len(changes[path.removeprefix('app/src/main/java/com/android/purebilibili/')])})
for row in cp:assert sha(row['path'])==row['sha256Bytes']
audit={'status':'PASS','actualSnapshot':61,'runtimeEntries':101,'wholeBodies':bodies,'existingClassOverlap':result['existingClassOverlap'],'samePackageTopMethodIntersection':intersection,'candidatePublicStaticMethods':len(own),'actualPublicStaticMethods':len(product),'candidateMethods':own,'actualMethods':product,'sourceOnly':True,'actualRootMounted':False}
safe(compiled/'source-symbol-audit.json').write_text(json.dumps(audit,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({k:v for k,v in audit.items()if k not in ['candidateMethods','actualMethods']},indent=2))
