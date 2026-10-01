from pathlib import Path
import difflib, hashlib, importlib.util, json, re, subprocess, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
ALPHA='fcf84853b287662e8a9129ea0d38576c36522a34'
STABLE='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
paths=[BASE+'data/repository/'+name+'.kt' for name in ['DynamicRepository','ArticleRepository','DynamicDetailFallbackPolicy','CommentRepository','CommentReadAccessPolicy','CommentGrpcRepository','DynamicCreateRepository']]
paths += [BASE+'feature/article/ArticleContentLoadPolicy.kt',BASE+'core/network/ApiClient.kt',BASE+'data/model/response/ResponseModels.kt',BASE+'feature/dynamic/DynamicDetailScreen.kt']
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def dump(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
def git(*args):return subprocess.check_output(['git','-c','core.longpaths=true',*args],cwd=REPO).decode('utf-8').replace('\r\n','\n')
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
lexer=module('stable_protocol_kotlin_lexer',REPO/'desktop/tools/sync-upstream.py')
span_parser=module('stable_protocol_span_parser',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
def functions(text):
 mask=span_parser.masked(text);result={};seen={}
 for m in re.finditer(r'(?m)^(?P<indent>[ \t]*)(?:(?:private|internal|suspend|inline|override|public)\s+)*fun\s+(?P<name>[\w.]+)\s*\(',mask):
  name=m.group('name');seen[name]=seen.get(name,0)+1;key=name+'#'+str(seen[name])
  opening=mask.index('(',m.start());end_params=span_parser.balanced(mask,opening)
  boundary=re.search(r'(?m)^'+re.escape(m.group('indent'))+r'(?:\}|(?:private|internal|suspend|inline|override|public|fun|class|object|@)\b)',mask[end_params:])
  limit=end_params+boundary.start() if boundary else len(mask)
  body=mask.find('{',end_params,limit)
  if body>=0:end=span_parser.balanced(mask,body,'{','}')
  else:
   # Reviewed abstract Retrofit declarations and single-line expression members.
   end=mask.find('\n',end_params);end=len(mask) if end<0 else end
  block=text[m.start():end]
  result[key]={'name':name,'overload':seen[name],'startLine':text[:m.start()].count('\n')+1,'endLine':text[:end].count('\n')+1,
               'sha256Lf':sha(block),'tokenSha256':sha(json.dumps([t[0] for t in lexer.kotlin_tokens(block)],ensure_ascii=False,separators=(',',':'))),'originalText':block}
 return result
manifest=json.loads(read(REPO/'desktop/upstream-sources.json'));assert manifest['upstreamCommit']==STABLE and manifest['upstreamTag']=='v0.2.3'
pins={r['path']:r['sha256'] for r in manifest['sources']}
source_rows=[];method_rows=[]
for path in paths:
 alpha=git('show',ALPHA+':'+path);stable=git('show',STABLE+':'+path);current=read(REPO/path)
 assert current==stable and sha(stable)==pins[path],path
 blob=git('rev-parse',STABLE+':'+path).strip();current_blob=git('hash-object','--path='+path,path).strip();assert blob==current_blob
 write(HERE/'original-alpha9'/path,alpha);write(HERE/'original-stable'/path,stable)
 a=functions(alpha);b=functions(stable)
 changes=[]
 for name in sorted(set(a)|set(b)):
  before=a.get(name);after=b.get(name)
  status='added' if before is None else 'removed' if after is None else 'unchanged' if before['tokenSha256']==after['tokenSha256'] else 'changed'
  row={'path':path,'method':name,'status':status,'alpha9':before,'stable':after}
  method_rows.append(row)
  if status!='unchanged':
   changes.append(name)
   write(HERE/'method-diffs'/Path(path).stem/(name.replace('.','_')+'.diff'),''.join(difflib.unified_diff((before or {}).get('originalText','').splitlines(True),(after or {}).get('originalText','').splitlines(True),fromfile='alpha9:'+path+':'+name,tofile='v023:'+path+':'+name)))
 source_rows.append({'path':path,'alpha9LfSha256':sha(alpha),'stableLfSha256':sha(stable),'pinnedGitBlob':blob,'currentGitBlob':current_blob,
                     'fullFileTokenEqual':sha(json.dumps([t[0] for t in lexer.kotlin_tokens(alpha)]))==sha(json.dumps([t[0] for t in lexer.kotlin_tokens(stable)])),
                     'stableOriginalFunctions':len(b),'changedOrAddedFunctions':changes})
dump(HERE/'source-delta.json',{'alpha9Commit':ALPHA,'stableCommit':STABLE,'allSelectedSourceFilesPinned':True,'sources':source_rows})
dump(HERE/'method-token-original-diff.json',{'alpha9Commit':ALPHA,'stableCommit':STABLE,'methodCount':len(method_rows),
 'unchanged':sum(r['status']=='unchanged' for r in method_rows),'changed':sum(r['status']=='changed' for r in method_rows),
 'added':sum(r['status']=='added' for r in method_rows),'removed':sum(r['status']=='removed' for r in method_rows),'methods':method_rows})
print(json.dumps({'sourceCount':len(source_rows),'methodCount':len(method_rows),'changedFiles':[r for r in source_rows if not r['fullFileTokenEqual']]},indent=2,ensure_ascii=False))
