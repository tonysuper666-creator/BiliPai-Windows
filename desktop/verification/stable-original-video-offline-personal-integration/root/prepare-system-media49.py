from pathlib import Path
import hashlib,json,difflib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=HERE/'system-media49-prepared'
assert not OUT.exists();path='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
base=(REPO/path).read_text(encoding='utf-8').replace('\r\n','\n');desired=base;changes=[]
def edit(before,after):
 global desired
 assert desired.count(before)==1,before;desired=desired.replace(before,after,1);changes.append(dict(before=before,after=after))
edit('    var mediaPrevious by remember { mutableStateOf<(() -> Unit)?>(null) }\n    var mediaNext by remember { mutableStateOf<(() -> Unit)?>(null) }\n','')
edit('    }, onPrevious = { mediaPrevious?.invoke() ?: playback.previous() }, onNext = { mediaNext?.invoke() ?: playback.next() },',
 '''    }, onPrevious = { val owner = retainedMedia.current; if (owner != null) owner.previous?.invoke() else playback.previous() },
        onNext = { val owner = retainedMedia.current; if (owner != null) owner.next?.invoke() else playback.next() },''')
edit('        mediaPrevious = owner?.previous; mediaNext = owner?.next\n','')
edit('    val systemMedia = remember(hostWindow, hostDisplayable, player, playback, listen, retainedMedia) {',
 '''    fun seekCurrentSystemMedia(seconds: Double, relative: Boolean = false) {
        if (!seconds.isFinite() || isClosing() || activatingUpdate) return
        val retained = if (systemTargetAudio) null else retainedMedia.current
        when {
            systemTargetAudio -> audioPlayer?.let { if (relative) it.seekBy(seconds) else it.seekTo(seconds) }
            retained != null -> player?.let {
                // A retained Offline/Live/PGC source has priority over stale ordinary detail metadata.
                if (retained.ownsNativeSource) { if (relative) it.seekBy(seconds) else it.seekTo(seconds) }
            }
            playback.state.value.details != null -> if (relative) playback.seekBy(seconds) else playback.seekTo(seconds)
            else -> player?.let { if (relative) it.seekBy(seconds) else it.seekTo(seconds) }
        }
    }
    val systemMedia = remember(hostWindow, hostDisplayable, player, playback, listen, retainedMedia) {''')
edit('                WindowsMediaCommand.NEXT -> if (systemTargetAudio) listen?.next() else retainedMedia.current?.next?.invoke() ?: playback.next()',
 '                WindowsMediaCommand.NEXT -> if (systemTargetAudio) listen?.next() else { val owner = retainedMedia.current; if (owner != null) owner.next?.invoke() else playback.next() }')
edit('                WindowsMediaCommand.PREVIOUS -> if (systemTargetAudio) listen?.previous() else retainedMedia.current?.previous?.invoke() ?: playback.previous()',
 '                WindowsMediaCommand.PREVIOUS -> if (systemTargetAudio) listen?.previous() else { val owner = retainedMedia.current; if (owner != null) owner.previous?.invoke() else playback.previous() }')
edit('                WindowsMediaCommand.FAST_FORWARD -> if (!systemTargetAudio && playback.state.value.details != null) playback.seekBy(10.0) else target?.seekBy(10.0)\n                WindowsMediaCommand.REWIND -> if (!systemTargetAudio && playback.state.value.details != null) playback.seekBy(-10.0) else target?.seekBy(-10.0)',
 '                WindowsMediaCommand.FAST_FORWARD -> seekCurrentSystemMedia(10.0, relative = true)\n                WindowsMediaCommand.REWIND -> seekCurrentSystemMedia(-10.0, relative = true)')
edit('        }, onSeek = { seconds -> if (!systemTargetAudio && playback.state.value.details != null) playback.seekTo(seconds)\n            else (if (systemTargetAudio) audioPlayer else player)?.seekTo(seconds) }) }',
 '        }, onSeek = { seconds -> seekCurrentSystemMedia(seconds) }) }')
OUT.mkdir();(OUT/'DesktopShell.kt').write_text(desired,encoding='utf-8',newline='\n')
(OUT/'hunks.patch').write_text(''.join(difflib.unified_diff(base.splitlines(keepends=True),desired.splitlines(keepends=True),fromfile='a/'+path,tofile='b/'+path)),encoding='utf-8',newline='\n')
(OUT/'edits.json').write_text(json.dumps(dict(path=path,baseSha256LF=hashlib.sha256(base.encode()).hexdigest(),desiredSha256LF=hashlib.sha256(desired.encode()).hexdigest(),edits=changes,candidateMutated=False,newRuntimeDependencies=0,actualSmtcOrPipInputAcceptance=False),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(prepared=True,sourceEdits=len(changes),candidateMutated=False)))
