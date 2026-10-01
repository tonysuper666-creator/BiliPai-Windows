from pathlib import Path
import json,importlib.util,inspect,hashlib
P=Path(__file__).resolve().parent
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_text(encoding='utf-8')
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf-8',newline='\n')
def one(t,a,b):assert t.count(a)==1,(a,t.count(a));return t.replace(a,b,1)
inv=json.loads(read(P/'content-source-selection.json'));t=read(P/'prepare-content.py')
t=one(t,"LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'","REPO=None;OUTPUT=None;STANDALONE=False")
a=t.index("parser=module('content_tokens'");b=t.index('SOURCES={}',a)
spec=importlib.util.spec_from_file_location('content_original_helpers',P.parent/'stable-video-player-full-controls-parity/prepare.py');helpers=importlib.util.module_from_spec(spec);spec.loader.exec_module(helpers)
token_helper=inspect.getsource(helpers.full.function_range)
closure_helper=inspect.getsource(helpers.member_closure)
t=t[:a]+'SOURCE_PINS = '+repr({r['path']:{k:r[k]for k in ['sha256LF','gitBlob']}for r in inv['sources']})+'\n'+token_helper+'\n'+closure_helper+'\n'+t[b:]
a=t.index('def read(rel):');b=t.index('def adapt(',a)
t=t[:a]+'''def read(rel):
 path=BASE+rel
 if path not in SOURCES:
  p=REPO/path;assert not p.is_symlink() and p.resolve().is_relative_to(REPO.resolve()),path
  raw=wide(p).read_bytes().replace(b'\\r\\n',b'\\n');pin=SOURCE_PINS[path];assert sha(raw)==pin['sha256LF'],path+' differs from fixed stable source'
  blob=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip()
  current=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+path,path],cwd=REPO,text=True).strip()
  assert current==blob==pin['gitBlob'],path+' Git identity differs'
  SOURCES[path]=dict(text=raw.decode(),**pin)
 return SOURCES[path]['text']
def emit(rel,t,origin,mode):
 direct=mode=='direct-complete-original'
 if STANDALONE or not direct:write(OUTPUT/'com/android/purebilibili'/rel,t)
 OUTPUTS.append(dict(path='com/android/purebilibili/'+rel,origin=BASE+origin,sha256LF=sha(t),mode=mode,generated=STANDALONE or not direct))
'''+t[b:]
t=t.replace('helpers.full.function_range(', 'function_range(').replace('helpers.member_closure(', 'member_closure(')
a=t.index(" save(LANE/'content-source-selection.json'")
t=t[:a]+'''def generate(repo,output,standalone=False):
 global REPO,OUTPUT,STANDALONE,parser,selector,SOURCES,OUTPUTS,ADAPT,REFERENCES
 REPO=Path(repo).resolve();OUTPUT=Path(output).resolve();STANDALONE=standalone
 SOURCES={};OUTPUTS=[];ADAPT=[];REFERENCES=[]
 manifest=json.loads(wide(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
 assert manifest['upstreamCommit']==COMMIT
 records={r['path']:r['sha256']for r in manifest['sources']}
 for path,pin in SOURCE_PINS.items():
  if path in records:assert records[path]==pin['sha256LF'],path+' existing canonical identity differs'
 parser=module('content_tokens',REPO/'desktop/tools/sync-upstream.py')
 selector=module('content_decls',REPO/'desktop/tools/extract-appearance-platform.py')
 main()
if __name__=='__main__':generate(Path(sys.argv[1]),Path(sys.argv[2]),'--standalone' in sys.argv[3:])
'''
tool=P/'prepared/desktop/tools/extract-upstream-video-content-full.py';write(tool,t)
verify='''from pathlib import Path
import importlib.util,tempfile
def verify(repo,generated):
 p=Path(__file__).with_name('extract-upstream-video-content-full.py')
 s=importlib.util.spec_from_file_location('verify_complete_video_content',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
 with tempfile.TemporaryDirectory(prefix='bilipai-full-video-content-')as d:
  m.generate(Path(repo),Path(d))
  for row in m.OUTPUTS:
   if not row['generated']:continue
   expected=Path(d)/row['path'];actual=Path(generated)/row['path']
   assert m.wide(actual).read_bytes()==m.wide(expected).read_bytes(),str(actual)+' differs from source-only producer'
if __name__=='__main__':
 import sys
 verify(Path(sys.argv[1]),Path(sys.argv[2]))
'''
write(P/'prepared/desktop/tools/verify-upstream-video-content-full.py',verify)
print(json.dumps(dict(producerSha256LF=hashlib.sha256(t.encode()).hexdigest(),sourcePins=len(inv['sources']),outputs=len(inv['outputs']))))
