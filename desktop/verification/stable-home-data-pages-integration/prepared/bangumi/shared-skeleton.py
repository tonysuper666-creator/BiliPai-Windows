from pathlib import Path
import hashlib,json,subprocess,sys,difflib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
BASE=MAIN/'desktop/.local/stable-home-partition-parity/prepared/tools/extract-upstream-home-partition.py'
base=BASE.read_text(encoding='utf-8')
old="('MediaListRowSkeleton','UserListRowSkeleton','ContentMediaListSkeleton')"
new="('MediaListRowSkeleton','UserListRowSkeleton','ContentMediaListSkeleton','ContentVideoGridSkeleton','ContentVideoGridItemSkeleton')"
assert base.count(old)==1
desired=base.replace(old,new)
prepared=HERE/'shared-reference-only/extract-upstream-home-partition.py';prepared.parent.mkdir(parents=True,exist_ok=True)
prepared.write_text(desired,encoding='utf-8',newline='\n')
(HERE/'shared-skeleton-grid.patch').write_text(''.join(difflib.unified_diff(base.splitlines(keepends=True),desired.splitlines(keepends=True),fromfile='a/desktop/tools/extract-upstream-home-partition.py',tofile='b/desktop/tools/extract-upstream-home-partition.py')),encoding='utf-8',newline='\n')
subprocess.run([sys.executable,str(prepared),'--repo',str(REPO),'--output',str(HERE/'shared-reference-only/generated')],check=True)
# Keep one source-only compiler reference. The live product installs only the exact hunk in the
# existing Partition producer after that lane is installed; no second skeleton producer or facade.
reference=HERE/'reference-only/DesktopOriginalMediaListSkeleton.kt';reference.parent.mkdir(exist_ok=True)
source=HERE/'shared-reference-only/generated/com/android/purebilibili/core/ui/skeleton/DesktopOriginalMediaListSkeleton.kt'
reference.write_bytes(source.read_bytes())
sha=lambda b:hashlib.sha256(b).hexdigest()
(HERE/'shared-skeleton-base.json').write_text(json.dumps(dict(baseProducer=str(BASE),baseSHA256LF=sha(base.encode()),desiredSHA256LF=sha(desired.encode()),patchSHA256=sha((HERE/'shared-skeleton-grid.patch').read_bytes()),consumerFunctions=['ContentVideoGridSkeleton','ContentVideoGridItemSkeleton'],oneExistingProducer=True,referenceOnlyNotInstall=True),indent=2)+'\n',encoding='utf-8')
stale=HERE/'generated/com/android/purebilibili/feature/bangumi/BangumiUiPolicy.kt'
if stale.exists():stale.unlink()
print('Shared original grid skeleton appended to the sole planned Partition producer')
