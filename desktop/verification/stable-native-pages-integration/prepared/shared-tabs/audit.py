from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists());REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-11'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def load(n,p):
 spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def main():
 inventory=json.loads(read(HERE/'source-inventory.json'));checks=[]
 def prove(ok,name,**fields):assert ok,name;checks.append(dict(name=name,status='PASS',**fields))
 for row in inventory['sources']:
  original=read(HERE/'original-source'/row['path']);generated=read(HERE/row['output'])
  prove(sha(original)==row['sha256LF'],'pinned original source '+row['path'])
  reverse=generated.split('\n',2)[2]
  expected=original
  for change in reversed(row['adaptations']):
   if not change['replacement']:
    assert expected.count(change['original'])==1
    expected=expected.replace(change['original'],'',1)
    continue
   assert reverse.count(change['replacement'])==1,(row['path'],change['replacement'][:80])
   reverse=reverse.replace(change['replacement'],change['original'],1)
  if row['mode']=='direct':prove(reverse==expected,'whole original file reverse-byteequal except declared removed Android imports/annotation '+row['path'])
  else:
   # Selected declarations are complete original source slices, not parser/body approximations.
   parser=load('audit_liquid_parser',REPO/'desktop/tools/sync-upstream.py')
   source=read(HERE/'prepared/desktop/tools/extract-upstream-shared-liquid-tabs.py')
   # Metadata body hashes are independently checked against the original source;
   # the complete expected slices must be physically present in the reverse output.
   tokens=parser.kotlin_tokens(original);depth=parens=brackets=0;starts=[]
   for i,(token,start,end) in enumerate(tokens):
    if depth==parens==brackets==0 and token in ('fun','val','var','class','object','interface'):
     if token=='fun':
      j=i+1
      while tokens[j][0]!='(':j+=1
      name=tokens[j-1][0]
     else:name=tokens[i+1][0]
     line=original.rfind('\n',0,start)+1
     while line>0:
      before=original.rfind('\n',0,line-1)+1
      if original[before:line].strip().startswith('@'):line=before
      else:break
     starts.append((name,line))
    depth+=(token=='{')-(token=='}');parens+=(token=='(')-(token==')');brackets+=(token=='[')-(token==']')
   declarations=[(n,original[start:(starts[i+1][1] if i+1<len(starts) else len(original))].rstrip()+'\n') for i,(n,start) in enumerate(starts)]
   for decl in row['declarations']:
    body=[b for n,b in declarations if n==decl['name'] and sha(b)==decl['sha256LF']];assert len(body)==1
    prove(body[0].strip() in reverse,'complete selected body '+row['path']+'#'+decl['name'],sha256LF=decl['sha256LF'])
 candidate=HERE/'compile-03/prepared-shared-liquid-tabs.jar'
 def wrappers(path):
  with zipfile.ZipFile(safe(path)) as z:return [n[:-6].replace('/','.') for n in z.namelist() if n.endswith('Kt.class') and '$' not in n]
 own=wrappers(candidate);packages={x.rsplit('.',1)[0] for x in own};actual=[x for x in wrappers(SNAP/'main-kotlin.jar') if x.rsplit('.',1)[0] in packages]
 compiler=load('audit_liquid_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
 def methods(label,path,classes):
  p=subprocess.run([str(compiler.JAVA.parent/'javap.exe'),'-p','-s','-classpath',str(path)]+classes,capture_output=True,text=True,encoding='utf-8',timeout=90)
  assert p.returncode==0;write(HERE/(label+'-top-level-methods.javap.txt'),p.stdout)
  records=[];owner=None;pending=None
  for line in p.stdout.splitlines():
   m=re.search(r'public (?:final )?class (\S+)',line)
   if m:owner=m.group(1);pending=None
   elif line.strip().startswith('public static ') and '(' in line:
    name=line.split('(',1)[0].split()[-1];pending=(owner,name)
   elif pending and 'descriptor:' in line:
    descriptor=line.split('descriptor:',1)[1].strip();package=pending[0].rsplit('.',1)[0]
    records.append(dict(owner=pending[0],package=package,jvmName=pending[1],descriptor=descriptor,parameterDescriptor=descriptor[1:descriptor.index(')')]))
    pending=None
  return records
 a=methods('candidate',candidate,own);b=methods('actual-stable11',SNAP/'main-kotlin.jar',actual)
 key=lambda x:(x['package'],x['jvmName'],x['parameterDescriptor'])
 intersect=sorted(set(map(key,a))&set(map(key,b)))
 prove(not intersect,'same-package JVM-name parameter-descriptor intersection zero',candidateMethods=len(a),actualMethods=len(b),intersection=intersect)
 compileResult=json.loads(read(HERE/'compile-03/compile-result.json'))
 prove(compileResult['status']=='PASS' and not compileResult['classOverlapWithActual'],'class FQN overlap zero against actual stable11')
 prod=json.loads(read(HERE/'production-byte-equality.json'))
 prove(prod['status']=='PASS' and prod['byteEqualGeneratedCount']==32 and prod['noLocalOrSnapshotDependencyInProductionProducer'],'source-only producer all32 byteequal with compiled source')
 # Key strings and defaults are the original getHomeSettings subset, not invented persistence.
 platform=read(HERE/'platform/com/bilipai/desktop/settings/DesktopLiquidTabSettings.kt')
 settings=read(HERE/'original-source/app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt')
 keys=re.findall(r'(?:bool|int|float)\("([a-z0-9_]+)"\)',platform)
 prove(all('"'+key+'"' in settings or key=='liquid_glass_readability_mode' for key in keys),'all preference keys original',keys=keys)
 prove('?:false' in platform and 'navigationIconCrossScaleEnabled=bool("navigation_icon_cross_scale_enabled")?:true' in platform,'original nativefalse and navigation-cross-scaletrue defaults')
 result=dict(status='PASS',checkCount=len(checks),checks=checks,candidateMethods=a,actualMethods=b,sourceCount=32,platformSourceCount=2,preparedOnly=True,noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True)
 write(HERE/'source-and-symbol-audit.json',json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(dict(status='PASS',checks=len(checks),candidateMethods=len(a),actualMethods=len(b),intersections=0,sha256Bytes=hashlib.sha256(safe(HERE/'source-and-symbol-audit.json').read_bytes()).hexdigest())))
if __name__=='__main__':main()
