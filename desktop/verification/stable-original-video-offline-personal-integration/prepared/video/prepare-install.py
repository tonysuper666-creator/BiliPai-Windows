from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def sha(b):return hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
inventory=json.loads((LANE/'source-inventory.json').read_text(encoding='utf-8'))
source=(LANE/'prepare.py').read_text(encoding='utf-8')
start=source.index('LANE=Path(__file__).resolve().parent');end=source.index('def wide(p):')
source=source[:start]+'''LANE=None
REPO=None
OUTPUT=None
STANDALONE=False
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
'''+source[end:]
start=source.index("parser=module('video_full_tokens'");end=source.index('def adapt(',start)
source=source[:start]+'''SOURCE_PINS = '''+repr({r['path']:dict(sha256LF=r['sha256LF'],gitBlob=r['gitBlob'])for r in inventory['sourceIdentities']})+'''
SOURCES={};OUTPUTS=[];ADAPTATIONS=[]
def read(path):
    if path not in SOURCES:
        selected=REPO/path
        assert not selected.is_symlink() and selected.resolve().is_relative_to(REPO.resolve()),path
        raw=wide(selected).read_bytes().replace(b'\\r\\n',b'\\n')
        pin=SOURCE_PINS[path]
        assert sha(raw)==pin['sha256LF'],path+' source differs from pinned v0.2.3'
        blob=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip()
        current=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+path,path],cwd=REPO,text=True).strip()
        assert blob==current==pin['gitBlob'],path+' Git identity differs'
        SOURCES[path]=dict(text=raw.decode('utf-8'),**pin)
    return SOURCES[path]['text']
'''+source[end:]
start=source.index('def emit(');end=source.index('def function_range(',start)
source=source[:start]+'''def emit(path,t,origin,mode):
    if STANDALONE or mode!='direct':write(OUTPUT/path,t)
    OUTPUTS.append(dict(path=path,origin=origin,mode=mode,sha256LF=sha(t),physicalLines=len(t.splitlines()),generated=STANDALONE or mode!='direct'))
'''+source[end:]
source=source.replace("save(LANE/'source-inventory.json'","save(OUTPUT/'source-bindings.json'")
source=source.replace("write(LANE/'prepared/DesktopDynamicCardOperations.video-members.kt.fragment',fragment)","write(OUTPUT/'video-operations-members.fragment',fragment)")
source=source.replace("if __name__=='__main__':main()",'''def generate(repo,output,standalone=False):
    global REPO,OUTPUT,STANDALONE,parser,media,selector,SOURCES,OUTPUTS,ADAPTATIONS
    REPO=Path(repo).resolve();OUTPUT=Path(output).resolve();STANDALONE=standalone
    SOURCES={};OUTPUTS=[];ADAPTATIONS=[]
    manifest=json.loads(wide(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
    assert manifest['upstreamCommit']==COMMIT
    registry={r['path']:r['sha256']for r in manifest['sources']}
    for path,pin in SOURCE_PINS.items():
        if path in registry:assert registry[path]==pin['sha256LF'],path+' existing registry identity differs'
    parser=module('video_full_tokens',REPO/'desktop/tools/sync-upstream.py')
    media=module('video_full_functions',REPO/'desktop/tools/extract-upstream-media.py')
    selector=module('video_full_decls',REPO/'desktop/tools/extract-appearance-platform.py')
    main()
if __name__=='__main__':generate(Path(sys.argv[1]),Path(sys.argv[2]),'--standalone' in sys.argv[3:])''')
tool=LANE/'prepared/desktop/tools/extract-upstream-video-detail-full-units.py';write(tool,source)
spec=importlib.util.spec_from_file_location('install_video_full',tool);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
audit=LANE/'install-audit-02'
out=audit/'standalone-generated';prod=audit/'production-generated'
m.generate(REPO,out,True);m.generate(REPO,prod,False)
rows=[]
for row in inventory['outputs']:
 relative=row['path'];old=wide(LANE/'generated'/relative).read_bytes();stand=wide(out/relative).read_bytes()
 assert old==stand,relative
 direct=row['mode']=='direct';target=prod/relative
 assert wide(target).exists()!=direct,relative
 if wide(target).exists():assert wide(target).read_bytes()==old,relative
 rows.append(dict(path=relative,sha256LF=sha(old),standaloneByteEqual=True,productionGenerated=not direct,directCopiedOnce=direct))
assert (out/'video-operations-members.fragment').read_bytes()==(LANE/'prepared/DesktopDynamicCardOperations.video-members.kt.fragment').read_bytes()
assert (prod/'video-operations-members.fragment').read_bytes()==(out/'video-operations-members.fragment').read_bytes()
meta=REPO/'desktop/tools/extract-stable-video-metadata.py';base=meta.read_bytes().replace(b'\r\n',b'\n').decode()
before="        body=media.function(original,name,parser)\n"
after=before+"        if name in ('VideoDetailBadgeChip','VideoArgueMsgRow','VideoHonorChip'):\n            body=body.replace('private fun '+name,'internal fun '+name,1)\n"
assert base.count(before)==1
write(LANE/'prepared/metadata-visibility-hunk.json',json.dumps(dict(path='desktop/tools/extract-stable-video-metadata.py',baseSha256LF=sha(base.encode()),hunks=[dict(before=before,after=after,expectedCount=1)],bodiesUnchanged=True),indent=2)+'\n')
write(audit/'byte-equality.json',json.dumps(dict(passed=True,totalOutputs=len(rows),productionSelectedOutputs=sum(r['productionGenerated']for r in rows),directOutputsCopiedOnce=sum(r['directCopiedOnce']for r in rows),rows=rows,soleProducerSha256LF=sha(source.encode()),memberFragmentByteEqual=True),indent=2)+'\n')
print(json.dumps(dict(passed=True,outputs=len(rows),selected=sum(r['productionGenerated']for r in rows),direct=sum(r['directCopiedOnce']for r in rows))))
