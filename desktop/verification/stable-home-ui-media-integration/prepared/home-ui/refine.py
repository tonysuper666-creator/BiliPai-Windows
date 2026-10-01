from pathlib import Path
p=Path(__file__).parent/'audit.py'
s=p.read_text(encoding='utf-8-sig')
anchor="paths=['app/src/main/java/com/android/purebilibili/feature/home/'+n+'.kt'"
insert="""registry=json.loads(text(REPO/'desktop/upstream-sources.json'))
for row in registry['sources']:
 if row['mode']=='direct' and row['path'] in originals:
  _,pkg,ds=originals[row['path']]
  for n,d in ds:existing.setdefault(pkg+'.'+n,[]).append('direct:'+row['path'])
for f in ['DissolveAnimationPreset','MaybeDissolvableVideoCard','DissolvableVideoCard','jiggleOnDissolve']:
 existing['com.android.purebilibili.core.ui.animation.'+f]=['adapter: sole DesktopReplyDissolvableContainer / FavoriteJiggle']
"""
s=s.replace(anchor,insert+anchor)
s=s.replace("'SettingsManager','WallpaperPaletteStore'","'SettingsManager','VideoShareSheet','DownloadManager','SubscriptionFeedPage','PartitionScreen','SystemBarCompat','VideoRepository','WebViewScreen','NetworkUtils','VideoShareSheetMotion','VideoShareCoverService','VideoShareMoreTargetsSheet','VideoShareToFollowingDialog','WallpaperMedia','WallpaperPaletteStore'")
p.write_text(s,encoding='utf-8',newline='\n')
