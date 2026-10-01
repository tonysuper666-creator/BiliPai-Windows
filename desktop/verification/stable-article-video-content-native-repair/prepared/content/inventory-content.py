from pathlib import Path
import hashlib,json,subprocess,zipfile,re
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
paths=['feature/video/screen/VideoContentSection.kt','feature/video/ui/section/AiSummarySection.kt','feature/video/viewmodel/AiSummaryUiPolicy.kt','data/repository/AiSummaryFetchPolicy.kt','feature/video/ui/section/VideoSupplementSection.kt','feature/video/ui/section/VideoNoteSection.kt','feature/video/ui/components/RelatedVideoItem.kt','core/store/SettingsManager.kt','feature/video/note/VideoNoteModels.kt','feature/video/ui/VideoDetailShapes.kt','feature/video/ui/components/RelatedVideoActionSheet.kt','feature/video/ui/components/RelatedVideoActionPolicy.kt','feature/video/ui/components/RelatedVideoCardLayoutPolicy.kt','feature/video/note/VideoNoteEditorStatePolicy.kt','feature/video/note/VideoNoteEmotionPolicy.kt','feature/video/note/VideoNoteVisibilityPolicy.kt']
rows=[]
for rel in paths:
 p=BASE+rel;raw=subprocess.check_output(['git','show',COMMIT+':'+p],cwd=REPO).replace(b'\r\n',b'\n');out=LANE/'original-stable'/p;wide(out).parent.mkdir(parents=True,exist_ok=True);wide(out).write_bytes(raw)
 rows.append(dict(path=p,sha256LF=hashlib.sha256(raw).hexdigest(),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+p],cwd=REPO,text=True).strip(),lines=len(raw.splitlines())))
save(LANE/'content-source-inventory.json',dict(commit=COMMIT,sources=rows))
cp=json.loads(wide(MAIN/'desktop/.local/stable-product-snapshot-47/ordered-runtime-cp.json').read_text());jars=[Path(r['path'])for r in cp]+[MAIN/'desktop/.local/stable-video-detail-full-ui-parity/runs/12/candidate.jar',MAIN/'desktop/.local/stable-video-player-full-controls-parity/runs/11/candidate.jar']
names=['VideoContent','AiSummary','VideoNote','RelatedVideo','CollectionRow','CollectionSheet','VideoDetailShapes','VideoNavigation','TodayWatchFeedback','HomeFeedCardStyle']
found=[]
for jar in jars:
 with zipfile.ZipFile(wide(jar))as z:
  hits=[n for n in z.namelist()if n.endswith('.class')and any(x in n for x in names)]
  if hits:found.append(dict(jar=str(jar),classes=hits))
save(LANE/'content-existing-class-inventory.json',found)
print(json.dumps(dict(sourceCount=len(rows),classes=sum(len(r['classes'])for r in found)),indent=2))
