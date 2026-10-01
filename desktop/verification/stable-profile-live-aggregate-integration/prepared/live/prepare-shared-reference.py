from pathlib import Path
import importlib.util,json,hashlib,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def load(p,n):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def sha(b):return hashlib.sha256(b).hexdigest()
parser=load(REPO/'desktop/tools/sync-upstream.py','live_reference_parser')
media=load(REPO/'desktop/tools/extract-upstream-media.py','live_reference_selector')
p='app/src/main/java/com/android/purebilibili/core/ui/skeleton/ContentLoadingSkeletons.kt';s=(REPO/p).read_text(encoding='utf-8').replace('\r\n','\n')
assert sha(s.encode())=='26bae6dfaa2f92b4418f37012821ca1a6c0bf5a7fcb82a47cd0369c86dc4b5f2'
names=['ContentVideoGridSkeletonFixedColumns','ContentCategoryGridSkeleton']
bodies=['@Composable\n'+media.function(s,n,parser) for n in names]
out=HERE/'reference-only/com/android/purebilibili/core/ui/skeleton/DesktopLiveSkeletonReference.kt';out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(s[:s.index('/**')]+'\n\n'.join(bodies)+'\n',encoding='utf-8',newline='\n')
tool=REPO/'desktop/tools/extract-upstream-home-partition.py';raw=tool.read_bytes();text=raw.decode().replace('\r\n','\n')
old="('MediaListRowSkeleton','UserListRowSkeleton','ContentMediaListSkeleton','ContentVideoGridSkeleton','ContentVideoGridSkeletonFixedColumns')"
assert text.count(old)==1
new=old[:-1]+",'ContentCategoryGridSkeleton')"
(HERE/'sole-shared-helper-hunk.json').write_text(json.dumps(dict(target='desktop/tools/extract-upstream-home-partition.py',baseSha256LF=sha(text.encode()),baseSha256Bytes=sha(raw),before=old,after=new,count=1,originalSource=p,originalSha256LF=sha(s.encode()),originalDeclarations=[dict(name=n,originalBodySha256LF=sha(media.function(s,n,parser).encode())) for n in names],referenceOnlyCompilerFiles=[str(out)],existingCandidateFixedColumns=True,actual39FixedColumns=False,productionNewDeclarations=['ContentCategoryGridSkeleton'],rootWholeCompileRequired=True),ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
