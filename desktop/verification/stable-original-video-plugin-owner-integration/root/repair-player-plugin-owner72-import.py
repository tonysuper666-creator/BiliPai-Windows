from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'player-plugin-owner-install72/repairs/import01'
assert not OUT.exists()
name = 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerPluginBridge.kt'
path = REPO / name
before = path.read_bytes()
installed = json.loads((HERE / 'player-plugin-owner-install72/installed.json').read_bytes())
target = next(row for row in installed['targets'] if row['path'] == name)
sha = lambda value: hashlib.sha256(value).hexdigest()
assert sha(before) == target['sha256Bytes']
anchor = b'import com.android.purebilibili.core.plugin.SkipAction\n'
assert before.count(anchor) == 1
after = before.replace(anchor, anchor + b'import com.android.purebilibili.data.model.response.SponsorSegment\n', 1)
log = (REPO / 'desktop/.local/stable-build-repair/classes-72.log').read_bytes()
assert b'BUILD FAILED' in log and b"Unresolved reference 'SponsorSegment'" in log
OUT.mkdir(parents=True)
(OUT / 'before.kt').write_bytes(before)
(OUT / 'after.kt').write_bytes(after)
(OUT / 'classes-72-failed01.log').write_bytes(log)
(OUT / 'repair.json').write_text(json.dumps(dict(path=name, beforeSha256Bytes=sha(before),
    afterSha256Bytes=sha(after), exactHunks=1, reason='Root bridge missed canonical original response.SponsorSegment import',
    originalInstalledRecordPreserved=True, originalBusinessLogicChanged=False), indent=2) + '\n', encoding='utf-8')
path.write_bytes(after)
prepared = MAIN / 'desktop/.local/stable-video-plugin-owner-bridge-parity/prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerPluginBridge.kt'
assert prepared.read_bytes() == before
prepared.write_bytes(after)
print(json.dumps(dict(repairApplied=True, afterSha256Bytes=sha(after))))
