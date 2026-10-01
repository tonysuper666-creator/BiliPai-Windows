from pathlib import Path
import hashlib,json,difflib
HERE=Path(__file__).resolve().parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists());STABLE=MAIN.parent/'BiliPai-v023'
def sha(b):return hashlib.sha256(b).hexdigest()
def main():
 snap=MAIN/'desktop/.local/stable-product-snapshot-20';manifest=json.loads((snap/'manifest.json').read_text(encoding='utf-8'))
 rel='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuSettings.kt';src=STABLE/rel;raw=src.read_bytes();s=raw.decode().replace('\r\n','\n')
 pin=next(x for x in manifest['inputs'] if x['path']==rel);assert pin['sha256Bytes']==sha(raw)
 changes=[
 ('fontScale = finite(fontScale, 1f, 0.5f, 2f),','fontScale = finite(fontScale, 1f, 0.3f, 2f),'),
 ('speedFactor = finite(speedFactor, 1f, 0.25f, 4f),','speedFactor = finite(speedFactor, 1f, 0.5f, 3f),'),
 ('displayAreaRatio = finite(displayAreaRatio, 0.5f, 0.25f, 1f),','displayAreaRatio = com.android.purebilibili.core.store.normalizeDanmakuDisplayArea(finite(displayAreaRatio, 0.5f, 0.25f, 1f)),') ,
 ('scrollDurationSeconds = finite(scrollDurationSeconds, 7f, 2f, 20f),','scrollDurationSeconds = finite(scrollDurationSeconds, 7f, 1f, 50f),'),
 ('staticDurationSeconds = finite(staticDurationSeconds, 4f, 1f, 20f),','staticDurationSeconds = finite(staticDurationSeconds, 4f, 1f, 50f),'),
 ('duplicateMergeWindowMs = duplicateMergeWindowMs.coerceIn(50, 5_000),','duplicateMergeWindowMs = duplicateMergeWindowMs.coerceIn(100, 3000),'),
 ('duplicateMergeCountThreshold = duplicateMergeCountThreshold.coerceIn(2, 100),','duplicateMergeCountThreshold = duplicateMergeCountThreshold.coerceIn(2, 10),'),
 ('blockedRules = blockedRules.map { it.trim().take(500) }.filter(String::isNotEmpty).distinct().take(300),','blockedRules = com.android.purebilibili.feature.video.danmaku.parseDanmakuBlockRules(blockedRules.joinToString("\\n")),')]
 c=s
 for a,b in changes:assert c.count(a)==1;c=c.replace(a,b)
 root=HERE/'selected-files';dest=root/'DesktopDanmakuSettings.kt';root.mkdir(exist_ok=True);dest.write_text(c,encoding='utf-8',newline='\n')
 (root/'DanmakuSettings.normalization.patch').write_text(''.join(difflib.unified_diff(s.splitlines(True),c.splitlines(True),fromfile=rel,tofile=rel)),encoding='utf-8',newline='\n')
 reverse=c
 for a,b in reversed(changes):assert reverse.count(b)==1;reverse=reverse.replace(b,a)
 assert reverse==s
 baseline=HERE/'source-inputs/actual20-DanmakuSettings.kt';baseline.parent.mkdir(exist_ok=True);baseline.write_bytes(raw)
 evidence=dict(source=rel,actualMain20Sha256Bytes=pin['sha256Bytes'],actualMain20Sha256LF=sha(s.encode()),candidateSha256LF=sha(c.encode()),reverseNormalizedActualSourceByteEqual=True,whitelist=[dict(before=a,after=b) for a,b in changes],originalAnchors=['SettingsManager.kt normalizeDanmakuFontScale .3..2; mapDanmakuSettingsFromPreferences uses full parseDanmakuBlockRules(blockRulesRaw).'],constructorOrModelFieldDelta=False,noSharedSourceEdits=True)
 (root/'source-delta.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(evidence))
if __name__=='__main__':main()
