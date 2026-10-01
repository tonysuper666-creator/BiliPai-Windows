"""Select complete original Composer domain once, over the retained entry admission."""
from pathlib import Path
import hashlib, json, subprocess
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
ORIGIN='app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoComposerViewModel.kt'
def wide(p):
    s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def put(p,t):
    p=wide(p); p.parent.mkdir(parents=True,exist_ok=True)
    p.write_bytes(t.encode('utf-8') if isinstance(t,str) else t)
def sha(t): return hashlib.sha256(t.encode('utf-8') if isinstance(t,str) else t).hexdigest()
def main():
    raw=subprocess.check_output(['git','-C',str(REPO),'show',f'{COMMIT}:{ORIGIN}']).replace(b'\r\n',b'\n').decode('utf-8')
    put(P/'original-stable'/ORIGIN,raw)
    t=raw; edits=[]
    def change(a,b,label):
        nonlocal t
        assert t.count(a)==1,(label,t.count(a)); edits.append(dict(before=a,after=b,label=label)); t=t.replace(a,b,1)
    change('import androidx.lifecycle.ViewModel\n','import com.bilipai.desktop.ui.DesktopOriginalVideoComposerEnvironment\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\nimport kotlinx.coroutines.flow.mapNotNull\n','Android lifecycle factory replaced by required same retained entry owner')
    change('class VideoComposerViewModel : ViewModel() {','internal class VideoComposerViewModel(\n    private val environment: DesktopOriginalVideoComposerEnvironment,\n) : AutoCloseable {\n    private fun <T> MutableStateFlow(initial: T): MutableStateFlow<T> =\n        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)\n','Original domain state writes use the same Store-to-entry admission; no second transport/store')
    change('val events = _events.receiveAsFlow()','val events = _events.receiveAsFlow().mapNotNull { event ->\n        event.takeIf { environment.isCurrent() }\n    }','Retired entry events cannot reach a replacement UI')
    for name in ['Comment','Danmaku']:
        change(f'    internal suspend fun notify{name}Sent() {{\n        _events.send',f'    internal suspend fun notify{name}Sent() {{\n        currentCoroutineContext().ensureActive()\n        environment.assertCurrent()\n        _events.send',f'Capture actual caller cancellation and entry lifetime before original {name} event send')
    change('    override fun onCleared() {\n        mentionSearchJob?.cancel()\n        super.onCleared()\n    }','    override fun close() {\n        mentionSearchJob?.cancel()\n        _events.close()\n    }','Root closes only this original domain event/search lifetime; no global/native teardown')
    out='com/android/purebilibili/feature/video/viewmodel/VideoComposerViewModel.kt'
    put(P/'prepared/generated'/out,t)
    # Exact indexed inverse also works when an adaptation deletes an import.
    current=raw; history=[]
    for e in edits:
        at=current.index(e['before']); history.append((at,e)); current=current.replace(e['before'],e['after'],1)
    assert current==t
    for at,e in reversed(history):
        assert current[at:at+len(e['after'])]==e['after']
        current=current[:at]+e['before']+current[at+len(e['after']):]
    assert current==raw
    put(P/'composer-source-audit.json',json.dumps(dict(passed=True,sourceCommit=COMMIT,path=ORIGIN,
        originalSHA256LF=sha(raw),originalLines=len(raw.splitlines()),output=out,outputSHA256LF=sha(t),
        completeOriginalFile=True,originalForwardReverseExact=True,edits=edits,
        publicationBoundary='Channel remains the original buffered event channel. Collector rejects retired owner; no claim that a completed pre-retirement in-flight event can be unsent.',
        noProductRuntimeAcceptance=True),ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__': main()
