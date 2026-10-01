from pathlib import Path
import hashlib, json

H = Path(__file__).resolve().parent
MAIN = H.parents[2]
CANDIDATE = MAIN.parent / 'BiliPai-v023'
REL = 'desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt'

def sha(b): return hashlib.sha256(b).hexdigest()
def dump(p, v):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(v, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

original = (CANDIDATE / REL).read_bytes()
before = original.decode('utf-8').replace('\r\n', '\n')
edits = [
    ('capability-flow',
     '    val state: StateFlow<PlayerState> = mutableState.asStateFlow()\n',
     '    val state: StateFlow<PlayerState> = mutableState.asStateFlow()\n'
     '    private val mutableDecoderCapabilities = MutableStateFlow<MpvDecoderCapabilities?>(null)\n'
     '    internal val decoderCapabilities: StateFlow<MpvDecoderCapabilities?> = mutableDecoderCapabilities.asStateFlow()\n'),
    ('session-start',
     '    private fun startSession(windowId: Long) {\n        mutableVideoOutput.value',
     '    private fun startSession(windowId: Long) {\n        mutableDecoderCapabilities.value = null\n        mutableVideoOutput.value'),
    ('detach',
     '            session.also { session = null; it?.closing?.set(true) }',
     '            mutableDecoderCapabilities.value = null\n            session.also { session = null; it?.closing?.set(true) }'),
    ('same-session-initialize',
     '                val nativeVersion = property(native, handle, "mpv-version")\n'
     '                synchronized(lock) {\n'
     '                    if (session === this && !closing.get()) mutableState.update { it.copy(ready = true, error = null, failure = null, nativeVersion = nativeVersion) }\n'
     '                }',
     '                val nativeVersion = property(native, handle, "mpv-version")\n'
     '                val decoders = readMpvDecoderCapabilities(native, handle)\n'
     '                synchronized(lock) {\n'
     '                    if (session === this && !closing.get()) {\n'
     '                        mutableDecoderCapabilities.value = decoders\n'
     '                        mutableState.update { it.copy(ready = true, error = null, failure = null, nativeVersion = nativeVersion) }\n'
     '                    }\n'
     '                }'),
    ('session-retire',
     '                    if (session === this) {\n                        session = null\n',
     '                    if (session === this) {\n                        session = null\n                        mutableDecoderCapabilities.value = null\n'),
]
after = before
for name, old, new in edits:
    assert after.count(old) == 1, (name, after.count(old))
    after = after.replace(old, new)

baseline = H / 'baselines' / REL
baseline.parent.mkdir(parents=True, exist_ok=True)
if baseline.exists(): assert baseline.read_bytes() == original
else: baseline.write_bytes(original)
target = H / 'after' / REL
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(after, encoding='utf-8', newline='\n')
dump(H / 'mpv-exact-hunks.json', {
    'family': REL, 'beforeSha256Bytes': sha(original), 'beforeSha256LF': sha(before.encode()),
    'afterSha256LF': sha(after.encode()),
    'hunks': [{'name': name, 'before': old, 'after': new} for name, old, new in edits],
})
print(json.dumps({'family': REL, 'hunks': len(edits), 'beforeLF': sha(before.encode()), 'afterLF': sha(after.encode())}))
