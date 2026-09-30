from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def ext(p):
 p=str(Path(p).absolute());return Path(p if p.startswith('\\\\?\\') else '\\\\?\\'+p)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(p,v):ext(p.parent).mkdir(parents=True,exist_ok=True);ext(p).write_text(v,encoding='utf-8',newline='\n')
spec=importlib.util.spec_from_file_location('homecardgen',HERE/'prepared/desktop/tools/extract-upstream-home-cards.py');gen=importlib.util.module_from_spec(spec);spec.loader.exec_module(gen)
gen.generate(REPO,HERE/'generated',True)
write(HERE/'source-inventory.json',json.dumps(gen.inventory(REPO),indent=2)+'\n')
target='desktop/src/main/kotlin/com/bilipai/desktop/ui/DiscoveryScreens.kt'
raw=ext(REPO/target).read_bytes();ext((HERE/'base'/target).parent).mkdir(parents=True,exist_ok=True);ext(HERE/'base'/target).write_bytes(raw)
s=raw.decode('utf-8').replace('\r\n','\n')
s=s.replace('import androidx.compose.foundation.lazy.grid.*','import androidx.compose.foundation.lazy.staggeredgrid.*')
s=s.replace('LazyGridState','LazyStaggeredGridState')
before='''    LazyVerticalGrid(columns = GridCells.Adaptive(260.dp), state = scroll, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {'''
assert s.count(before)==1
s=s.replace(before,'    DesktopHomeCardGrid(state = scroll, modifier = Modifier.fillMaxSize()) { cardLayout ->')
s=s.replace('span = { GridItemSpan(maxLineSpan) }','span = StaggeredGridItemSpan.FullLine')
s=s.replace('DiscoveryVideoTile(card) { onVideo(card) }','DiscoveryVideoTile(card, cardLayout) { onVideo(card) }')
needle='''                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = card.authorMid > 0'''
assert s.count(needle)==1
s=s.replace(needle,'''                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = card.authorMid > 0''')
for before in ['TextButton(enabled = card.authorMid > 0, onClick = { onUser(card.authorMid) })',
               'TextButton(onClick = { onPreview(card) })',
               'TextButton(enabled = !feedbackBusy, onClick = { feedbackVideo = originals.firstOrNull { it.bvid == card.bvid } })']:
 assert s.count(before)==1
 s=s.replace(before,before[:-1]+', modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))')
s=s.replace('private fun DiscoveryVideoTile(card: VideoCard, onClick: () -> Unit)','internal fun DiscoveryVideoTile(card: VideoCard, layout: HomeFeedCardLayout, onClick: () -> Unit)')
assert s.count('aspectRatio(16f / 9f)')==1
s=s.replace('aspectRatio(16f / 9f)','aspectRatio(layout.coverAspectRatio)')
needle='Text(card.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)'
assert s.count(needle)==1
s=s.replace(needle,'Text(card.title, minLines = layout.titleMinLines, maxLines = layout.titleMaxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)')
write(HERE/'prepared'/target,s)
rows=[dict(path=target,baseSha256Bytes=sha(REPO/target),desiredSha256Bytes=sha(HERE/'prepared'/target))]
write(HERE/'target-baselines.json',json.dumps(rows,indent=2)+'\n')
r=subprocess.run(['git','diff','--no-index','--no-ext-diff',str(HERE/'base'/target),str(HERE/'prepared'/target)],capture_output=True,text=True,encoding='utf-8');assert r.returncode==1
patch=r.stdout;patch='diff --git a/'+target+' b/'+target+'\n--- a/'+target+'\n+++ b/'+target+'\n'+patch[patch.index('@@ '):]
write(HERE/'consumer.patch',patch)
print('Prepared one existing consumer, seven original generated files; main unchanged')
