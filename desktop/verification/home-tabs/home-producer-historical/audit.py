from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def ext(p):
 p=str(Path(p).absolute());return Path(p if p.startswith('\\\\?\\') else '\\\\?\\'+p)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(p,value):ext(p).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
prior=HERE.parent/'settings-home-dynamic-parity/field-audit.json';allFields=json.loads(prior.read_text(encoding='utf-8'))['allHomeSettingsMappingFields']
selected=['gridColumnCount','gridColumnCountCompact','homeFeedCardWidthPreset','homeFeedCardStyle']
rows=[dict(row,scope='prepared actual Discovery consumer') for row in allFields if row['field'] in selected]
assert len(rows)==4
audit=dict(originalSourceAudit=dict(path=str(prior),sha256Bytes=sha(prior)),closedConsumerFields=rows,
 originalConstructorVsPersistedRead=dict(selectedFour='Defaults coincide: 0,0,AUTO,BILIPAI; actual read expressions still emitted directly',
 homeHeroCarouselEnabled='constructor true / actual persisted read false: not installed',
 crashTrackingConsentShown='constructor true / actual persisted read false: outside card slice',
 homeDurationStyle='legacy home_duration_badge fallback to OUTSIDE_COVER or HIDDEN: outside card slice',
 frostedGlass='new separate key falls back to old combined tint: outside card slice'),
 bindingDifferences=[
 'Existing native grid displayMode=0 only. Original HomeSettings displayMode is not surfaced until real StoryVideoCard exists.',
 'Original WindowDp class thresholds use actual desktop LocalWindowInfo; width cap uses the true remaining feed viewport because desktop sidebar consumes pixels.',
 'Android fold/hinge/adaptive posture is not synthesized. Actual normal-window center cap is1280dp; gap/padding6dp.',
 'DataStore edit/snapshot is bound to existing Root shared global PluginStore settings namespace, no guest/account fallback or duplicated writer.',
 'One absent Android bulleted-list drawable is bound to the already real SettingsIconRole.HOME_FEED semantic renderer.',
 'Original unsupported fold/pinch/help and all-search-related scope strings are narrowed explicitly to current Discovery consumers.',
 'Compact remembered count is read/applied and setter verified; no desktop pinch gesture is claimed or fake switch displayed.',
 'Existing desktop M3 Card/metadata/actions remain. Actual policy title min/max and cover ratio consumed; compactMetadata/compactStatsOnCover/storyPadding are not yet renderer consumers.',
 'The existing three footer actions wrap with FlowRow and explicit48dp minimum measured size; original BV/CID/MID and enabled conditions retained.'],
 remainingActiveGaps=[
 'Home original ElegantVideoCard/StoryVideoCard full composition, follow badges, UP avatar, cover stats, overlay duration, data saver/cover-request policy, motion/transition/glass visual details.',
 'Pinch-to-change-column gesture, fold hinge spacing, display mode1 renderer.',
 'Home title full-content toggle, duration legacy migration consumer, publish visibility, compact stats-on-cover and other HomeSettings fields outside four selected keys.',
 'Original hero carousel/autoplay, refresh-tip, Home navigation/tab/bar/search/collapse behavior are separate tasks.',
 'Search/related/Personal card grids still use prior independent consumers; global original cover style is not claimed for those routes.',
 'Prepared slice is not yet Root integrated or packaged native UI accepted.'],
 referenceSources=[dict(path=path,sha256Bytes=sha(REPO/path)) for path in [
  'app/src/main/java/com/android/purebilibili/feature/home/HomeScreen.kt',
  'app/src/main/java/com/android/purebilibili/feature/home/HomeCategoryPage.kt',
  'app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCard.kt',
  'app/src/main/java/com/android/purebilibili/feature/home/HomeFeedPinchZoomPolicy.kt']])
write(HERE/'field-audit.json',audit)
originalInventory=json.loads((REPO/'desktop/upstream-sources.json').read_text())['sources'];known={row['path']:row for row in originalInventory}
selectedInventory=json.loads((HERE/'source-inventory.json').read_text());delta=[]
for row in selectedInventory:
 old=known.get(row['path'])
 if old:assert old['sha256']==row['sha256'],row['path']
 delta.append(dict(**row,operation='append feature to existing identity' if old else 'add unique identity',existingMode=old.get('mode') if old else None))
write(HERE/'source-registration-delta.json',delta)
print('Audited4 actual persisted fields and7 unique original identities:',sum(row['operation']=='add unique identity' for row in delta),'new')
