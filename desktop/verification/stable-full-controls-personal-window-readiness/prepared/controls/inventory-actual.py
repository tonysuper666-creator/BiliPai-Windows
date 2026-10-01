from pathlib import Path
import zipfile
MAIN=Path(__file__).resolve().parents[3]
with zipfile.ZipFile(MAIN/'desktop/.local/stable-product-snapshot-47/main-kotlin.jar')as z:
 names=set(z.namelist())
 for t in ['VideoAspectRatio','PlayerProgressPlacement','VideoEnhancementAlgorithm','Anime4KPreset','PbpRidgeSample','SubtitleTrackOption','SubtitleControlUiState','PlayerProgress','NativeDanmakuToggleButtonKt','SeekPreviewBubbleKt','TopControlBarLayoutPolicyKt','BottomControlBarLayoutPolicyKt','PlayMode']:
  print(t+': '+str([n for n in names if n.rsplit('/',1)[-1]==t+'.class']))
