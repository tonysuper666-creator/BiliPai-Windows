"""Build the one portable source-only producer from the audited task producer.
No shared output, registry or source is touched.
"""
from pathlib import Path
import hashlib, importlib.util, json, re
P=Path(__file__).resolve().parent
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def rd(p):return wide(p).read_text(encoding='utf-8')
def wr(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf-8',newline='\n')
def one(s,a,b):assert s.count(a)==1,(a,s.count(a));return s.replace(a,b,1)
inv=json.loads(rd(P/'source-inventory.json'))
source=rd(P/'prepare.py')
source=one(source,"LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'","REPO=None;OUTPUT=None;STANDALONE=False")
a=source.index("parser=module('controls_tokens'");b=source.index('\ndef read(rel):',a)
pins={r['path']:{k:r[k]for k in ['sha256LF','gitBlob']}for r in inv['sources']}
source=source[:a]+"SOURCE_PINS = "+repr(pins)+"\nSOURCES={};OUTPUTS=[];ADAPT=[];REFERENCES=[]\nACTUAL={'com/android/purebilibili/feature/anime4k/gl/Anime4KDisplayScaleMode.class'}\nEXISTING_DIRECT={'feature/video/subtitle/BiliSubtitlePolicy.kt','feature/anime4k/Anime4KConfig.kt'}\n"+source[b:]
a=source.index('def read(rel):');b=source.index('\ndef adapt(',a)
source=source[:a]+'''def read(rel):
 path=BASE+rel
 if path not in SOURCES:
  selected=REPO/path
  assert not selected.is_symlink() and selected.resolve().is_relative_to(REPO.resolve()),path
  raw=wide(selected).read_bytes().replace(b'\\r\\n',b'\\n');pin=SOURCE_PINS[path]
  assert sha(raw)==pin['sha256LF'],path+' differs from pinned stable source'
  blob=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip()
  actual=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+path,path],cwd=REPO,text=True).strip()
  assert blob==actual==pin['gitBlob'],path+' Git identity differs'
  SOURCES[path]=dict(text=raw.decode(),**pin)
 return SOURCES[path]['text']
def emit(rel,t,origin,mode):
 direct=mode=='direct-complete-original'
 if STANDALONE or not direct:write(OUTPUT/'com/android/purebilibili'/rel,t)
 OUTPUTS.append(dict(path='com/android/purebilibili/'+rel,origin=BASE+origin,sha256LF=sha(t),mode=mode,lines=len(t.splitlines()),generated=STANDALONE or not direct))
'''+source[b:]
source=one(source,"if className and any(e.rsplit('/',1)[-1]==className+'.class'for e in ACTUAL):","if rel in EXISTING_DIRECT:")
source=source.replace('full.function_range(', 'function_range(')
spec=importlib.util.spec_from_file_location('frozen_helpers',P.parent/'stable-video-detail-full-ui-parity/prepare.py');f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
import inspect
helper=inspect.getsource(f.function_range)
source=one(source,'def member_closure(source,seeds):',helper+'\ndef member_closure(source,seeds):')
# Keep the existing canonical enum as a reference rather than scanning unrelated source files.
a=source.index(" paths=subprocess.check_output(['git','ls-tree'");b=source.index(" if 'com/android/purebilibili/feature/anime4k/gl/Anime4KDisplayScaleMode.class'",a)
enumSource=next(r['path'][len('app/src/main/java/com/android/purebilibili/'):]for r in inv['sources']if r['path'].endswith('/Anime4KDisplayPolicy.kt'))
source=source[:a]+" source="+repr(enumSource)+";t=read(source)\n"+source[b:]
a=source.index(" save(LANE/'source-inventory.json'")
source=source[:a]+'''def generate(repo,output,standalone=False):
 global REPO,OUTPUT,STANDALONE,parser,selector,SOURCES,OUTPUTS,ADAPT,REFERENCES
 REPO=Path(repo).resolve();OUTPUT=Path(output).resolve();STANDALONE=standalone
 SOURCES={};OUTPUTS=[];ADAPT=[];REFERENCES=[]
 manifest=json.loads(wide(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
 assert manifest['upstreamCommit']==COMMIT
 registry={r['path']:r['sha256']for r in manifest['sources']}
 for path,pin in SOURCE_PINS.items():
  if path in registry:assert registry[path]==pin['sha256LF'],path+' existing registry identity differs'
 parser=module('controls_tokens',REPO/'desktop/tools/sync-upstream.py')
 selector=module('controls_decls',REPO/'desktop/tools/extract-appearance-platform.py')
 main()
if __name__=='__main__':generate(Path(sys.argv[1]),Path(sys.argv[2]),'--standalone' in sys.argv[3:])
'''
wr(P/'prepared/desktop/tools/extract-upstream-video-player-full-controls.py',source)
print(json.dumps({'producerSha256LF':hashlib.sha256(source.encode()).hexdigest(),'sourcePins':len(pins),'outputs':len(inv['outputs']),'direct':sum(r['mode']=='direct-complete-original'for r in inv['outputs'])}))
