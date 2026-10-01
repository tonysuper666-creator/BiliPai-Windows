from pathlib import Path
import hashlib,json,subprocess
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
PATHS=[
 'app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuConfig.kt',
 'danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/DanmakuModels.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuManager.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/danmaku/FaceOcclusionPolicy.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuViewportPolicy.kt',
 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/LiveDanmakuOverlay.kt',
 'danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/ByteDanceDanmakuEngine.kt',
 'app/src/main/java/com/android/purebilibili/feature/live/LiveSuperChatExpiryPolicy.kt',
]
def sha(b):return hashlib.sha256(b).hexdigest()
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
registry=json.loads((ROOT/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
rows=registry['sources'] if 'sources' in registry else registry['files'];index={r['path']:r for r in rows}
records=[]
for p in PATHS:
 blob=subprocess.check_output(['git','-C',str(ROOT),'rev-parse',COMMIT+':'+p],text=True).strip()
 data=subprocess.check_output(['git','-C',str(ROOT),'show',COMMIT+':'+p])
 checkout=safe(ROOT/p).read_bytes()
 assert data.replace(b'\r\n',b'\n')==checkout.replace(b'\r\n',b'\n'),p
 target=safe((L/'original-retained'/Path(p)).with_suffix('.kt.txt'));target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
 if p in index:assert index[p]['sha256']==sha(data),p
 records.append(dict(path=p,commit=COMMIT,gitBlob=blob,sha256Bytes=sha(data),checkoutSHA256Bytes=sha(checkout),checkoutLFEqual=True,fullSourceRetained=str(target),existingRegistry=index.get(p),productionSelected=False))
(L/'source-inventory.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
for p in ['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuScheduler.kt','desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt','desktop/tools/extract-upstream-danmaku-list-menu.py','desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopOverlayNativeSmoke.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DownloadScreens.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/MediaScreens.kt']:
 target=safe(L/'baseline'/p);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(safe(ROOT/p).read_bytes())
print('RETAINED',len(records),'fixed original sources; no production source modified')
