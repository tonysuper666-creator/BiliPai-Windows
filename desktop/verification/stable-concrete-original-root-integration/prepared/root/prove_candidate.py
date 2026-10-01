from pathlib import Path
import hashlib,json,os,subprocess,sys,struct,zipfile,importlib.util
H=Path(__file__).resolve().parent
MAIN=H.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,obj):safe(p).write_text(json.dumps(obj,indent=2)+'\n',encoding='utf-8')
run=H/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '14'))
inputs=json.loads(safe(run/'inputs.json').read_text())
assert inputs['exit']==0
for row in inputs['sources']:assert sha(row['path'])==row['sha256Bytes']
snap=Path(inputs['actualSnapshot'])
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text())
for row in cp:assert sha(row['path'])==row['sha256Bytes']

def parse_class(data):
 pos=8
 def u2():
  nonlocal pos
  n=struct.unpack_from('>H',data,pos)[0];pos+=2;return n
 def u4():
  nonlocal pos
  n=struct.unpack_from('>I',data,pos)[0];pos+=4;return n
 count=u2();pool={};i=1
 while i<count:
  tag=data[pos];pos+=1
  if tag==1:
   n=u2();pool[i]=data[pos:pos+n].decode('utf-8',errors='replace');pos+=n
  elif tag in (3,4):pos+=4
  elif tag in (5,6):pos+=8;i+=1
  elif tag in (7,8,16,19,20):pos+=2
  elif tag in (9,10,11,12,17,18):pos+=4
  elif tag==15:pos+=3
  else:raise AssertionError(tag)
  i+=1
 pos+=6
 n=u2();pos+=2*n
 def attributes():
  nonlocal pos
  found={}
  for _ in range(u2()):
   name=pool[u2()];n=u4();found[name]=data[pos:pos+n];pos+=n
  return found
 for _ in range(u2()):pos+=6;attributes()
 methods=[]
 for _ in range(u2()):
  flags=u2();name=pool[u2()];desc=pool[u2()];attributes();methods.append((flags,name,desc))
 attrs=attributes()
 source=pool[struct.unpack('>H',attrs['SourceFile'])[0]] if 'SourceFile' in attrs else None
 return source,methods

with zipfile.ZipFile(safe(run/'candidate.jar')) as z:
 candidate={n:z.read(n) for n in z.namelist() if n.endswith('.class')}
with zipfile.ZipFile(safe(snap/'main-kotlin.jar')) as z:
 product={n:z.read(n) for n in z.namelist() if n.endswith('.class')}
parsed={n:parse_class(d) for n,d in candidate.items()}
old={n:parse_class(d) for n,d in product.items()}
allowed={Path(r['path']).name for r in inputs['sources'] if '/prepared/existing/' in r['path'].replace('\\','/')}
overlap=sorted(set(candidate)&set(product))
# Kotlin inlined Effect lambdas have the original library's SourceFile rather than caller's.
# Accept only exact outer class families whose ordinary product classes belong to owned files.
allowed_families={n.split('$',1)[0].removesuffix('.class') for n,(source,_) in old.items() if source in allowed}
undeclared=[n for n in overlap if parsed[n][0] not in allowed and n.split('$',1)[0].removesuffix('.class') not in allowed_families]
illegal=[{'class':n,'name':name} for n,(_,ms) in parsed.items() for _,name,_ in ms
 if name not in ('<init>','<clinit>') and any(c in name for c in '.;/[]<>')]
def statics(tree,exclude=()):
 out={}
 for n,(_,ms) in tree.items():
  if n in exclude or not n.endswith('Kt.class'):continue
  pkg=n.rsplit('/',1)[0]
  for flags,name,desc in ms:
   if flags&8 and flags&1 and name not in ('<init>','<clinit>'):
    out.setdefault((pkg,name,desc),[]).append(n)
 return out
new_methods=statics(parsed);old_methods=statics(old,overlap)
collisions=[{'method':list(k),'candidate':new_methods[k],'existing':old_methods[k]} for k in new_methods.keys()&old_methods.keys()]
previous=json.loads(safe(run/'class-audit.json').read_text()) if safe(run/'class-audit.json').exists() else {}
if previous.get('undeclared') and not safe(run/'class-audit-failed-sourcefile-only.json').exists():
 safe(run/'class-audit-failed-sourcefile-only.json').write_bytes(safe(run/'class-audit.json').read_bytes())
elif previous.get('crossFacadePublicOrInternalStaticCollisions') and not safe(run/'class-audit-failed-enum-static-scope.json').exists():
 safe(run/'class-audit-failed-enum-static-scope.json').write_bytes(safe(run/'class-audit.json').read_bytes())
write(run/'class-audit.json',{'candidateClasses':len(candidate),'declaredExistingSourceFamilies':sorted(allowed),
 'declaredInlineOuterFamilies':sorted(allowed_families),
 'exactFamilyOverrides':overlap,'undeclared':undeclared,'illegalJvmMethods':illegal,
 'crossFacadePublicOrInternalStaticCollisions':collisions,
 'preparedOnly':True,'actualRootMounted':False})
assert not undeclared,(undeclared[:5])
assert not illegal,illegal
assert not collisions,collisions[:3]

spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
fixture=H/'fixtures/ReturnAdmissionFixture.kt'
auth_fixture=H/'fixtures/AuthInvalidationFixture.kt'
test=H/('proof-'+(sys.argv[2] if len(sys.argv)>2 else '02'));safe(test).mkdir(parents=True,exist_ok=False)
classpath=[str(run/'candidate.jar')]+[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(run/'candidate.jar')+','+str(snap/'main-kotlin.jar'),
 '-cp',';'.join(classpath),'-d',str(test/'classes'),str(fixture),str(auth_fixture)]
safe(test/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
command=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(test/'compile.args')]
p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(test/'compile.log').write_text(p.stdout+p.stderr,encoding='utf-8');assert p.returncode==0,p.stderr
p=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',str(test/'classes')+';'+';'.join(classpath),
 'com.bilipai.desktop.ui.ReturnAdmissionFixtureKt'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
safe(test/'run.log').write_text(p.stdout+p.stderr,encoding='utf-8');assert p.returncode==0,p.stderr
return_output=p.stdout
p=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',str(test/'classes')+';'+';'.join(classpath),
 'com.bilipai.desktop.data.AuthInvalidationFixtureKt'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
safe(test/'run-auth.log').write_text(p.stdout+p.stderr,encoding='utf-8');assert p.returncode==0,p.stderr
write(test/'proof.json',{'fixtureSha':sha(fixture),'authFixtureSha':sha(auth_fixture),'candidateJarSha':sha(run/'candidate.jar'),
 'productKotlinSha':sha(snap/'main-kotlin.jar'),'manifestSha':inputs['manifestSha'],
 'orderedCpSha':inputs['orderedCpSha'],'prepared':True,'mainMounted':False,
 'realRootStoreLock':False,'syntheticAdmissionOrder':True,'realProductInMemoryStoreAndDispatcher':True,
 'syntheticTerminalInterceptorNoSocket':True,'exit':p.returncode})
for row in cp:assert sha(row['path'])==row['sha256Bytes']
for row in inputs['sources']:assert sha(row['path'])==row['sha256Bytes']
print(return_output+p.stdout);print('AUDIT',len(candidate),'classes, existing-family overrides',len(overlap),'undeclared0, illegal0, static collision0')
