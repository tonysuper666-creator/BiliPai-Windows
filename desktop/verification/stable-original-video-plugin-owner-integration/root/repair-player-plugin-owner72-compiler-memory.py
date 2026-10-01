from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'player-plugin-owner-install72/repairs/compiler-memory01'
assert not OUT.exists()
name = 'desktop/gradle.properties'
before = (REPO / name).read_bytes()
normalized = before.replace(b'\r\n', b'\n')
anchor = b'org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8\n'
assert normalized.count(anchor) == 1 and b'kotlin.daemon.jvmargs' not in normalized
after = normalized.replace(anchor, anchor + b'kotlin.daemon.jvmargs=-Xmx5g\n', 1)
success = (REPO / 'desktop/.local/stable-build-repair/classes-72.log').read_bytes()
assert b'BUILD SUCCESSFUL' in success and b'BUILD FAILED' not in success
failure = (HERE / 'player-plugin-owner-install72/repairs/classes-72-failed02-compiler-memory.log').read_bytes()
assert b'OutOfMemoryError' in failure and b'BUILD FAILED' in failure
sha = lambda value: hashlib.sha256(value).hexdigest()
OUT.mkdir(parents=True)
(OUT / 'before.properties').write_bytes(before)
(OUT / 'after.properties').write_bytes(after)
(OUT / 'classes-72-successful-explicit5g.log').write_bytes(success)
(OUT / 'repair.json').write_text(json.dumps(dict(path=name, beforeSha256Bytes=sha(before),
    afterSha256Bytes=sha(after), exactHunks=1, compilerMaxHeap='5g', runtimeMemoryChanged=False,
    reason='Full original Compose backend exhausted the inherited 3g compiler heap; explicit 5g retry passed',
    initialFailureLogSha256Bytes=sha(failure), successfulRetryLogSha256Bytes=sha(success)), indent=2) + '\n', encoding='utf-8')
(REPO / name).write_bytes(after)
print(json.dumps(dict(compilerSettingPersisted=True, afterSha256Bytes=sha(after))))
