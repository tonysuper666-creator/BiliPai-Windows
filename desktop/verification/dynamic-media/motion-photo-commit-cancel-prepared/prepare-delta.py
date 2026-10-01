"""Prepare only the two-line platform cancellation gate delta in this owned lane."""
from pathlib import Path
import difflib, hashlib, json
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
BASE = ROOT / 'desktop/.local/dynamic-motion-photo-download-owner-parity/dependencies-from-gallery/DesktopDynamicMotionPhotoFiles.kt'
OUTPUT = HERE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles.kt'
def safe(p):
    s = str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
before = '''                checkpoint()
                val committed = withOwnedCommit {
                    synchronized(gate) {
                        assertOwned()
'''
after = '''                checkpoint()
                val commitContext = currentCoroutineContext()
                val committed = withOwnedCommit {
                    synchronized(gate) {
                        commitContext.ensureActive()
                        assertOwned()
'''
def generate():
    assert sha(BASE) == 'fc44e8bbaf636a75358edf499f7c10f72e5107405dee14f1da56aa4c3c0b4aac'
    original = safe(BASE).read_text(encoding='utf-8'); assert original.count(before) == 1
    candidate = original.replace(before, after, 1)
    safe(OUTPUT.parent).mkdir(parents=True, exist_ok=True)
    safe(OUTPUT).write_text(candidate, encoding='utf-8', newline='\n')
    assert candidate.replace('                val commitContext = currentCoroutineContext()\n', '', 1).replace('                        commitContext.ensureActive()\n', '', 1) == original
    return dict(candidateSha256Bytes=sha(OUTPUT), inputSha256Bytes=sha(BASE), addedLines=2)
if __name__ == '__main__': print(json.dumps(generate()))
