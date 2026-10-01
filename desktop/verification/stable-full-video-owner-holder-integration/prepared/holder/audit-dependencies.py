from pathlib import Path
import ast,hashlib,importlib.util,json,re,struct,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-61'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(b):return hashlib.sha256(b).hexdigest()
helper=MAIN/'desktop/.local/dynamic-editor-detail-parity/protocol-review/top-level-uniqueness-review/review.py'
t=helper.read_text(encoding='utf-8');tree=ast.parse(t);node=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='methods');exec(ast.get_source_segment(t,node))
spec=importlib.util.spec_from_file_location('holder_tokens',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
actualClasses={};methodIndex={}
for j in [SNAP/'main-kotlin.jar',SNAP/'main-java.jar']:
 with zipfile.ZipFile(j) as z:
  for e in z.namelist():
   if not e.endswith('.class'):continue
   actualClasses[e]=sha(z.read(e))
   if e.endswith('Kt.class') and '$' not in e:
    for m in methods(z.read(e),e):methodIndex.setdefault((m['package'],m['name']),[]).append(m)
rows=[]
for p in sorted(wide(P/'original-stable/app/src/main/java/com/android/purebilibili/feature/video/screen').glob('*.kt')):
 raw=p.read_bytes().replace(b'\r\n',b'\n').decode();pkg=re.search(r'^package (.+)$',raw,re.M)[1];tokens=parser.kotlin_tokens(raw);depth=pa=br=0;decls=[]
 for i,(word,a,b) in enumerate(tokens):
  if depth==pa==br==0 and word in ['fun','class','object','interface','val','var']:
   name=tokens[i+1][0]
   if word=='fun':
    tail=raw[b:];name=re.search(r'(\w+)\s*\(',tail)[1]
   owners=methodIndex.get((pkg.replace('.','/'),name),[]) if word=='fun' else []
   # Actual parser emits a dotted package. Keep both spellings explicit.
   owners+=methodIndex.get((pkg,name),[]) if word=='fun' else []
   classEntry=pkg.replace('.','/')+'/'+name+'.class'
   decls.append(dict(kind=word,name=name,line=raw.count('\n',0,a)+1,actualFunctionOwners=owners,actualClassEntry=classEntry if classEntry in actualClasses else None))
  depth+=(word=='{')-(word=='}');pa+=(word=='(')-(word==')');br+=(word=='[')-(word==']')
 rows.append(dict(file=p.name,sha256LF=sha(raw.encode()),lines=len(raw.splitlines()),declarations=decls))
out=dict(scope='Source function-name presence inventory only; final parameter-descriptor audit required after compilation.',actualSnapshotManifestSHA256=sha((SNAP/'manifest.json').read_bytes()),actualKotlinSHA256=sha((SNAP/'main-kotlin.jar').read_bytes()),rows=rows)
wide(P/'dependency-presence.json').write_bytes((json.dumps(out,indent=2)+'\n').encode())
for r in rows:
 present=[d['name'] for d in r['declarations'] if d['actualFunctionOwners'] or d['actualClassEntry']]
 missing=[d['name'] for d in r['declarations'] if not d['actualFunctionOwners'] and not d['actualClassEntry']]
 print(r['file'],r['lines'],'PRESENT:',','.join(present),'MISSING:',','.join(missing))
