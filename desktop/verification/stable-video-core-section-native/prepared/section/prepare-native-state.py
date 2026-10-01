from pathlib import Path
import hashlib,json,re,subprocess,importlib.util
P=Path(__file__).resolve().parent;REPO=P.parents[2].parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n')
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def sha(b):return hashlib.sha256(b).hexdigest()
sourcePath='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMpvOverlayControl.kt';base=read(REPO/sourcePath).decode();candidate=base;edits=[]
for before,after in [('class DesktopOriginalMpvOverlayControl internal constructor(', 'open class DesktopOriginalMpvOverlayControl internal constructor('),('private fun snapshot(): PlayerState','protected open fun snapshot(): PlayerState'),('private fun write(block: () -> Unit)','protected fun write(block: () -> Unit)'),('        const val STATE_IDLE = 1','        const val REPEAT_MODE_OFF = 0\n        const val REPEAT_MODE_ONE = 1\n        const val STATE_IDLE = 1')]:
 assert candidate.count(before)==1,before;candidate=candidate.replace(before,after,1);edits.append(dict(before=before,after=after))
before='    val diagnosticLoggingEnabled get() = diagnosticLoggingEnabledPort()'
after='''    /** Events from the same native StateFlow; only the extended control with the
     * required entry scope implements admission. This is not a Media3 decoder. */
    interface Listener {
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
        fun onPlaybackParametersChanged(parameters: DesktopOriginalPlaybackRate) {}
        fun onRenderedFirstFrame() {}
        fun onTracksChanged(tracks: List<com.bilipai.desktop.player.PlayerTrack>) {}
        fun onSourceTransition(sourceVersion: Long) {}
        fun onPlayerError(error: DesktopOriginalNativePlaybackError) {}
    }
    open fun addListener(listener: Listener) {
        error("The same-entry native event scope is required")
    }
    open fun removeListener(listener: Listener) {
        error("The same-entry native event scope is required")
    }
    val diagnosticLoggingEnabled get() = diagnosticLoggingEnabledPort()'''
assert candidate.count(before)==1;candidate=candidate.replace(before,after,1);edits.append(dict(before=before,after=after))
write(P/'proof-only/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMpvOverlayControl.kt',candidate)
write(P/'overlay-control-local-hunks.json',json.dumps(dict(path=sourcePath,baseLF=sha(base.encode()),candidateLF=sha(candidate.encode()),hunks=edits,applyOnlyExactHunks=True,neverCopyProofWholeSource=True),indent=2)+'\n')
path='app/src/main/java/com/android/purebilibili/feature/video/state/VideoPlayerState.kt';source=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n').decode()
s=importlib.util.spec_from_file_location('token_source',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
masked=m.masked(source);start=source.index('internal fun resolveApiDimensionIsVertical(');end=m.balanced(masked,masked.index('{',start),'{','}');decl=source[start:end]
write(P/'generated/com/android/purebilibili/feature/video/state/DesktopOriginalApiDimensionPolicy.kt','package com.android.purebilibili.feature.video.state\n'+decl+'\n')
write(P/'native-state-source-selection.json',json.dumps(dict(commit=COMMIT,source=path,sourceLF=sha(source.encode()),selectedDeclaration='resolveApiDimensionIsVertical',declarationLF=sha(decl.encode()),rendererStateScope='Same page projection of installed native StateFlow; no new player/controller/client/store/poll',originalClassNotProduced=True),indent=2)+'\n')
print(json.dumps(dict(base=sha(base.encode()),candidate=sha(candidate.encode()),hunks=len(edits))))
