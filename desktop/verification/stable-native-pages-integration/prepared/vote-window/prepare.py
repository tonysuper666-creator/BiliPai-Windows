from pathlib import Path
import hashlib,importlib.util,json,difflib,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
source='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoVotes.kt'
before=read(CANDIDATE/source)
original='''    if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return
    val density = LocalDensity.current
    Popup(alignment = Alignment.TopStart, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        Box(Modifier.size(with(density) { surfaceSize.width.toDp() }, with(density) { surfaceSize.height.toDp() })) { content() }
    }'''
assert before.count(original)==1
after=before.replace(original,'    DesktopShapedVideoCommandPopup(surfaceSize, anchorComponent, content)',1)
after=after.replace('internal fun DesktopVideoCommandPopup(surfaceSize: IntSize, content: @Composable () -> Unit)', 'internal fun DesktopVideoCommandPopup(surfaceSize: IntSize, content: @Composable () -> Unit, anchorComponent: java.awt.Component)', 1)
write(HERE/'references/DesktopVideoVotes.kt',after)
write(HERE/'patches/video-popup.patch',''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile='a/'+source,tofile='b/'+source)))
tool='desktop/tools/extract-stable-video-votes.py'
generator=read(CANDIDATE/tool)
anchor=' emit(p,"com/android/purebilibili/feature/video/ui/overlay/DesktopOriginalCommandDanmakuOverlay.kt",s)'
assert generator.count(anchor)==1
binding=''' # Windows-only measured native hit region. Original timing/card/dialog body stays intact.
 s=s.replace("import androidx.compose.ui.Modifier\\n", "import androidx.compose.ui.Modifier\\nimport com.bilipai.desktop.ui.desktopCommandHitRegion\\nimport com.bilipai.desktop.ui.DesktopCommandModalRegion\\n", 1)
 measured="            .onSizeChanged { measuredCardHeightPx = it.height }\\n"
 assert s.count(measured)==1
 s=s.replace(measured, measured+"            .desktopCommandHitRegion(item.id)\\n", 1)
 modal="    var votePanelInitialOptionIndex by remember { mutableIntStateOf(-1) }\\n"
 assert s.count(modal)==1
 s=s.replace(modal,modal+"    DesktopCommandModalRegion(votePanelVoteId != null)\\n",1)
'''
nextGenerator=generator.replace(anchor,binding+anchor,1)
write(HERE/'prepared/desktop/tools/extract-stable-video-votes.py',nextGenerator)
write(HERE/'patches/sole-renderer-generator.patch',''.join(difflib.unified_diff(generator.splitlines(True),nextGenerator.splitlines(True),fromfile='a/'+tool,tofile='b/'+tool)))
spec=importlib.util.spec_from_file_location('shaped_vote_generator',HERE/'prepared/desktop/tools/extract-stable-video-votes.py')
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g);g.generate(CANDIDATE,HERE/'generated')
panels='desktop/src/main/kotlin/com/bilipai/desktop/ui/PlayerPanels.kt'
panelsBefore=read(CANDIDATE/panels)
call='DesktopVideoCommandPopup(videoSurfaceSize, commandOverlay)'
assert panelsBefore.count(call)==2
panelsAfter=panelsBefore.replace(call,'DesktopVideoCommandPopup(videoSurfaceSize, commandOverlay, player.surface)')
write(HERE/'references/PlayerPanels.kt',panelsAfter)
write(HERE/'patches/player-surface-anchor.patch',''.join(difflib.unified_diff(panelsBefore.splitlines(True),panelsAfter.splitlines(True),fromfile='a/'+panels,tofile='b/'+panels)))
write(HERE/'baselines.json',json.dumps({source:dict(baseLfSha256=sha(before),desiredLfSha256=sha(after)),
 tool:dict(baseLfSha256=sha(generator),desiredLfSha256=sha(nextGenerator)),
 panels:dict(baseLfSha256=sha(panelsBefore),desiredLfSha256=sha(panelsAfter))},indent=2))
write(HERE/'fixture/LegacyPopup.kt','''package com.bilipai.desktop.popupfixture
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
@Composable
internal fun legacyPopup(surfaceSize: IntSize, content: @Composable () -> Unit) {
'''+original+'\n}\n')
print('prepared three minimal patches and sole original renderer card/modal region bindings')
