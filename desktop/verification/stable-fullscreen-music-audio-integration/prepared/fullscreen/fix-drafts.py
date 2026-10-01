from pathlib import Path
P=Path(__file__).resolve().parent
def patch(n,a,b):
 p=P/n;t=p.read_text(encoding='utf-8');assert a in t,(n,a);p.write_text(t.replace(a,b,1),newline='\n',encoding='utf-8')
patch('prepare-pager.py','val favoriteQuickSaveDefaultFolder by com.android.purebilibili.core.store.FavoriteInteractionSettingsStore\n        .getQuickSaveDefaultFolder(context.pluginContext)','val favoriteQuickSaveDefaultFolder by platform.favoriteQuickSaveDefaultFolder')
patch('prepare-pager.py',"['getAutoPlay','getExternalPlaylistAutoContinue','getPrefetchVideo','setAudioQuality']", "['getAutoPlay','getExternalPlaylistAutoContinue','getPrefetchVideo','setAudioQuality','getPortraitLetterboxAmbientHazeSync']")
patch('prepare-pager.py'," emit(rel,t);audit(rel,original,t,f.EDITS)",''' t=f.exact(t,'import androidx.compose.foundation.layout.statusBarsIgnoringVisibility\\n','','Actual status-bar inset supplied by Section')
 t=f.exact(t,'WindowInsets.statusBarsIgnoringVisibility.getTop(this)','section.statusBarInsetPixels','Actual desktop viewport inset')
 t=f.exact(t,'import com.android.purebilibili.danmaku.engine.DanmakuRenderView\\n','','Required sole document/carrier already mapped')
 for match in reversed(list(re.finditer(r'filterPortraitOnlyVerticalRecommendations\\(',t))):
  m=f.parser.masked(t);end=f.parser.balanced(m,match.end()-1,'(',')');before=t[match.start():end]
  after=before[:before.rfind(')')]+'isVerticalVideo = platform.requests::isVerticalVideo,\\n'+before[before.rfind(')'):]
  t=f.exact(t,before,after,'Actual selected filter policy requires the same owned vertical-details request')
 emit(rel,t);audit(rel,original,t,f.EDITS)''')
print('Pager draft corrections written once')
