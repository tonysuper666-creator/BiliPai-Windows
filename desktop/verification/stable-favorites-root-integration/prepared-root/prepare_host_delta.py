from pathlib import Path
import difflib,hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
rel=Path('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalFavoritesHost.kt')
base=(REPO/rel).read_text(encoding='utf-8').replace('\r\n','\n')
old='    retainedViewModel:BaseListViewModel?=null,\n';assert base.count(old)==1
s=base.replace(old,old+'    loadFavoriteViewModelOnEnter:Boolean=true,\n',1)
old='LaunchedEffect(viewModel) {if(viewModel is FavoriteViewModel) viewModel.loadData()}'
assert s.count(old)==1;s=s.replace(old,'LaunchedEffect(viewModel) {if(loadFavoriteViewModelOnEnter && viewModel is FavoriteViewModel) viewModel.loadData()}',1)
out=HERE/'prepared'/rel;out.parent.mkdir(parents=True,exist_ok=True);out.write_text(s,encoding='utf-8',newline='\n')
(HERE/'host-retained-initialization.patch').write_text(''.join(difflib.unified_diff(base.splitlines(True),s.splitlines(True),fromfile='a/'+rel.as_posix(),tofile='b/'+rel.as_posix())),encoding='utf-8',newline='\n')
(HERE/'host-delta-baseline.json').write_text(json.dumps(dict(file=rel.as_posix(),baseLF=hashlib.sha256(base.encode()).hexdigest(),desiredLF=hashlib.sha256(s.encode()).hexdigest(),change='Compatible Root already-initialized VM tail; old callers unchanged'),indent=2),encoding='utf-8')
