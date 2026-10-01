from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;LANE=HERE.parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists());REPO=MAIN.parent/'BiliPai-v023'
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p):
    b=p.read_bytes();s=b.decode('utf-8').replace('\r\n','\n');return dict(path=str(p),sha256Bytes=sha(b),sha256LF=sha(s.encode()))
paths=[LANE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopFavoriteQueueBridge.kt',LANE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopFavoritesRootEntry.kt',LANE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/audio/ListenAudioSession.kt']
original='app/src/main/java/com/android/purebilibili/feature/video/player/PlaylistManager.kt';commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
blob=subprocess.check_output(['git','show',commit+':'+original],cwd=REPO).replace(b'\r\n',b'\n')
assert blob==(REPO/original).read_bytes().replace(b'\r\n',b'\n')
review=dict(status='NO_BLOCKER_FOUND_IN_NARROW_SOURCE_SCOPE',preparedOnly=True,inputs=[pin(p) for p in paths],originalSource=dict(path=original,commit=commit,sha256LF=sha(blob)),
 evidence=[dict(source=original,anchor='395 addAllToCurrentPlaylist',finding='Original existingBvids set and items.filter{bvid !in existing} retained. Neither branch overwrites existing BVID metadata/CID/order/index; Listen applies its already-existing normalization boundary.'),
 dict(source=str(paths[0]),anchor='25 openQueue / 44 revealIfOwned / 53 appendQueue / 67 owns',finding='Per-open lease, one-use reveal ticket and selected audio/video target gate. Revealing does not issue normal open again. Append checks same actual Controller/Listen owner; close clears bridge tickets without modifying queue authority.'),
 dict(source=str(paths[2]),anchor='124 ordinary start / 128 owner start / 145 playAt / 205 pause / 235 ownsQueue',finding='Ordinary start passes null owner and retires previous lease. Internal next/previous use playAt, carrying lease into the new playGeneration and native baseline. Loaded pause retains current owner; pending pause retires it. Admission while preparing requires unchanged stopped native baseline AND no foreign current snapshot.'),
 dict(source=str(paths[2]),anchor='244 appendQueueForOwner / 485 stopOwnedSource',finding='Append operates actual existing state and changeQueue; preserves loaded native source and current pause/position. Stop/close clears owner and stops only the owned native version.'),
 dict(source=str(paths[1]),anchor='27 child SupervisorJob / 46 isOwned / 54 close / 60 shutdownForRestore',finding='Entry ownership checks alive, own job, epoch and Root lifetime. Covering is not a close action. Only explicit close cancels its child job/channels; restore waits for it. Root caller must retain this same entry while covered; this source review does not verify mounted navigation lifetime.')],
 blockers=[],limitations=['Read-only source audit. No Gradle, runtime native actor/window, account or HTTP.', 'Prepared queue helpers are not claimed installed Main17 or mounted Root consumer.', 'Normal Root video/audio callbacks and queue-token policy must remain the sole native playback authority.'])
(HERE/'findings.json').write_text(json.dumps(review,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(status=review['status'],sha256Bytes=sha((HERE/'findings.json').read_bytes()))))
