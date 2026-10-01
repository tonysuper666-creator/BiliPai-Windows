"""Only tags the existing command list with its admitted CID, under its lock."""
from pathlib import Path
import difflib,hashlib,json
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];CANDIDATE=REPO.parent/'BiliPai-v023'
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt'
base=(CANDIDATE/path).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
s=base
anchor='    val commandItems: StateFlow<List<CommandDanmakuItem>> = mutableCommands.asStateFlow()\n'
assert s.count(anchor)==1
s=s.replace(anchor,anchor+'''    // This is transport ownership only; the command list remains the sole list.
    private var commandCid: Long? = null
    fun commandItemsFor(cid: Long): List<CommandDanmakuItem> = synchronized(requestLock) {
        if (!closed.get() && cid > 0 && commandCid == cid) mutableCommands.value else emptyList()
    }
''',1)
anchor='            generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); liveMode = true; pendingLive.clear() }\n'
assert s.count(anchor)==1
s=s.replace(anchor,anchor+'''                .also { commandCid = null; mutableCommands.value = emptyList() }
''',1)
s=s.replace('        mutableError.value = null; mutableCommands.value = emptyList(); mutableFormat.value = null\n',
            '        mutableError.value = null; mutableFormat.value = null\n',1)
anchor='''        val version = synchronized(requestLock) {
            generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); liveMode = false; pendingLive.clear() }
        }
        mutableError.value = null
        mutableCommands.value = emptyList()
        mutableFormat.value = null
        installDocument(DanmakuDocument(), version)
'''
assert s.count(anchor)==1
s=s.replace(anchor,'''        val version = synchronized(requestLock) {
            generation.incrementAndGet().also {
                loadJob?.cancel(); windowJob?.cancel(); liveMode = false; pendingLive.clear()
                commandCid = cid; mutableCommands.value = emptyList()
                mutableError.value = null; mutableFormat.value = null
            }
        }
        installDocument(DanmakuDocument(), version)
''',1)
anchor='''    private fun publish(result: DanmakuWindowResult, version: Long) {
        if (version != generation.get() || closed.get()) return
        mutableError.value = result.warning
        mutableCommands.value = result.commands
        mutableFormat.value = result.format
        installDocument(result.document, version)
    }
'''
assert s.count(anchor)==1
s=s.replace(anchor,'''    private fun publish(result: DanmakuWindowResult, version: Long) {
        synchronized(requestLock) {
            if (version != generation.get() || closed.get()) return
            mutableError.value = result.warning
            mutableCommands.value = result.commands
            mutableFormat.value = result.format
            installDocument(result.document, version)
        }
    }
''',1)
anchor='''    fun setDocument(document: DanmakuDocument) {
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); liveMode = false; pendingLive.clear() }
        }
        mutableError.value = null
        mutableCommands.value = emptyList()
'''
assert s.count(anchor)==1
s=s.replace(anchor,'''    fun setDocument(document: DanmakuDocument) {
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also {
                loadJob?.cancel(); windowJob?.cancel(); liveMode = false; pendingLive.clear()
                commandCid = null; mutableCommands.value = emptyList()
            }
        }
        mutableError.value = null
''',1)
def write(p,text):
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8',newline='\n')
write(HERE/'baseline/DanmakuOverlay.kt.reference.txt',base)
write(HERE/'prepared/consumer-reference/DanmakuOverlay.kt',s)
write(HERE/'patches/command-owner.patch',''.join(difflib.unified_diff(base.splitlines(True),s.splitlines(True),fromfile='a/'+path,tofile='b/'+path)))
write(HERE/'command-owner-contract.json',json.dumps(dict(path=path,
 baseSha256Lf=hashlib.sha256(base.encode()).hexdigest(), desiredSha256Lf=hashlib.sha256(s.encode()).hexdigest(),
 parserChanged=False, schedulingChanged=False, listAuthority='existing mutableCommands only',
 commandItemsFor='synchronized(requestLock) read, same admitted CID, closed guard',
 publication='same requestLock, existing request generation',MainChanged=False,candidateChanged=False),indent=2))
print('Prepared existing command list CID ownership / locked admission-publication hunk.')
