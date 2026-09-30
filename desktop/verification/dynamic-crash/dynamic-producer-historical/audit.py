from pathlib import Path
import hashlib,json,re,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
BASE='app/src/main/java/com/android/purebilibili/'
def read(path):return (REPO/path).read_text(encoding='utf-8').replace('\r\n','\n')
def pin(path):return dict(path=path,sha256LF=hashlib.sha256(read(path).encode()).hexdigest())
source=read(BASE+'core/store/SettingsManager.kt')
keys=dict(re.findall(r'\bval\s+(KEY_\w+)\s*=\s*\w+PreferencesKey\("([^"]+)"\)',source))
begin=source.index('    internal fun mapHomeSettingsFromPreferences(preferences: Preferences): HomeSettings {')
body=source[begin:source.index('\n    fun getHomeSettings(',begin)]
prelude=body[:body.index('        return HomeSettings(')]
locals={};declarations=list(re.finditer(r'^        val (\w+) =[ \t]*',prelude,re.M))
for i,match in enumerate(declarations):
 locals[match[1]]=prelude[match.end():declarations[i+1].start() if i+1<len(declarations) else len(prelude)].strip()
begin=body.index('        return HomeSettings(')+len('        return HomeSettings(')
body=body[begin:body.rindex('\n        )')]
matches=list(re.finditer(r'^            (\w+) =[ \t]*',body,re.M))
home=[]
for i,match in enumerate(matches):
 expression=body[match.end():matches[i+1].start() if i+1<len(matches) else len(body)].strip()
 # Comments before the following field are preserved separately in source, not mistaken for a default.
 expression=re.sub(r'\n\s*//[^\n]*','',expression).rstrip(',').strip()
 field=match[1];derived={name:value for name,value in locals.items() if re.search(r'\b'+re.escape(name)+r'\b',expression)}
 refs=list(dict.fromkeys(re.findall(r'\bKEY_\w+\b',expression+'\n'+'\n'.join(derived.values()))))
 home.append(dict(field=field,originalKeys=[keys.get(k,k) for k in refs],actualSourceReadExpression=expression,derivedSourceBindings=derived,
  consumerStatus='not bound in current Windows Discovery/Home to this original HomeSettings field',
  preparedThisSlice=False))
retired={'smartVisualGuardEnabled','showHomeCoverGlassBadges','showHomeInfoGlassBadges','homeCardBadgeEffectMode','homeCardInfoGlassMode'}
for row in home:
 if row['field'] in retired:row['consumerStatus']='upstream retired/fixed off; not a missing active toggle'
 if row['field']=='runtimeVisualGuardEnabled':row['consumerStatus']='existing DesktopThemePrefs original-key binding to AppThemeConfig; whole-app jank downgrade is outside this slice and not newly validated'
 if row['field']=='crashTrackingConsentShown':row['consumerStatus']='current Root Diagnostics consent slice owns this consumer; not modified or counted again here'
dynamic=[
 ('incrementalTimelineRefreshEnabled','incremental_timeline_refresh',False,'prepared actual consumer','original multi-page baseline fetch + overlap/de-duplicate/sort/divider/tail pagination'),
 ('dynamicFeedLayoutMode','dynamic_feed_layout_mode',0,'prepared actual consumer','0 WATERFALL / 1 LIST; unknown value WATERFALL; original masonry layout and list prepend anchor'),
 ('dynamicImagePreviewTextVisible','dynamic_image_preview_text_visible',True,'missing','image viewer text overlay + temporary eye toggle; current CommunityImageViewer has no matching text consumer'),
 ('dynamicDetailImageLayout','dynamic_detail_image_layout',0,'missing','EXPANDED 0 / THUMBNAIL 1; unknown EXPANDED; Android first-frame cache dynamic_detail_image_layout_cache/layout + memory, set cache before settings write'),
 ('dynamicAllTabHorizontalUserListVisible','dynamic_all_tab_horizontal_user_list_visible',False,'missing','all-tab horizontal following-user rail; UP tab still supports user selection'),
 ('dynamicTopBarCollapseOnScroll','dynamic_top_bar_collapse_on_scroll',False,'missing','tab bar scroll-collapse independently of the horizontal following rail'),
 ('dynamicVisibleTabIds','dynamic_tab_visible_tabs','all,video,pgc,article,up','missing','CSV set; original UI preserves at least one valid tab; current Windows has fixed all/video/pgc'),
 ('dynamicTabOrder','dynamic_tab_order','all,video,pgc,article,up','missing','CSV list; source UI orders by provided index then logical index for absent IDs'),
 ('dynamicTopActionsCollapsed','dynamic_top_actions_collapsed',False,'missing','original publish/layout/fold dock persistent collapsed state; not part of FeedApiSection'),
 ('dynamicLayoutDirection','dynamic_page_layout_direction',0,'missing','LEFT 0 / RIGHT 1, unknown LEFT; original side-user/operation docking, not part of FeedApiSection'),
]
rows=[dict(field=f,key=k,default=d,status=s,semantics=n,storage='global settings DataStore; prepared Windows fields use original key in same Root PluginStore settings namespace')
 for f,k,d,s,n in dynamic]
recommendation=[dict(field='feedApiType',key='feed_api_type',default=0,status='existing effective Windows consumer',
 semantics='WEB 0 / MOBILE 1 / MERGED 2, unknown WEB; Android settings DataStore and sync feed_api/type mirror. Existing Windows consumer authority remains discovery/plugin-settings.json feed_api/type, not rewritten here.'),
 dict(field='homeRefreshCount',key='home_refresh_count',default=20,status='existing effective Windows consumer',
 semantics='normalize 10..30, original slider 19 intermediate steps; Android settings DataStore and feed_api/home_refresh_count mirror. Existing Windows authority remains discovery/feed_api namespace, not rewritten here.')]
other=[
 dict(field='dynamic user pins/hidden users',storage='dynamic_user_prefs',keys=['dynamic_pinned_users','dynamic_hidden_users'],status='missing',default='empty string sets'),
 dict(field='dynamic local not-interested IDs',storage='dynamic_cache',keys=['not_interested_dynamic_ids_v1'],status='missing',default='empty string set; original max500 normalization'),
 dict(field='dynamic cached feed',storage='dynamic_cache',keys=['dynamic_items_cache','dynamic_cache_time'],status='missing',default='no cache; original max100 items'),
 dict(field='dynamic display-mode and selected-tab routing',storage='dynamic_user_prefs',keys=['dynamic_display_mode','dynamic_selected_tab'],status='missing',default='display mode SIDEBAR enum name, invalid SIDEBAR; selected-tab missing null through original resolveDynamicSelectedTab'),
]
auditSources=[BASE+p for p in ['core/store/SettingsManager.kt','core/store/home/HomeSettingsStore.kt',
 'feature/settings/ui/SettingsSections.kt','feature/settings/screen/AppearanceSettingsScreen.kt','feature/home/HomeFeedGridPolicy.kt',
 'feature/home/HomeFeedCardStylePolicy.kt','feature/home/HomeFeedCardDensityPolicy.kt','feature/dynamic/DynamicViewModel.kt',
 'feature/dynamic/DynamicScreen.kt','data/repository/DynamicRepository.kt']]
auditSources=[p for p in auditSources if (REPO/p).exists()]
currentSources=['desktop/src/main/kotlin/com/bilipai/desktop/ui/DiscoveryScreens.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDiscoveryPreferences.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopCommunityRepository.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/appearance/DesktopThemePrefs.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/appearance/DesktopAppearanceTheme.kt']
result=dict(originalNamedSectionsFound=False,actualOriginalSection='SettingsSections.FeedApiSection plus AppearanceSettingsScreen card/layout fields',
 dynamic=rows,recommendation=recommendation,allHomeSettingsMappingFields=home,additionalDynamicState=other,
 originalSources=[pin(p) for p in auditSources],currentConsumers=[pin(p) for p in currentSources],
 scope='Two complete user settings plus original dynamic fetch/page/layout consumers; not full Home/Dynamic settings parity')
(HERE/'field-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
lines=['# Original Home / Dynamic settings and actual consumers','',
 'This is a two-field implementation slice. There is no original function named `SettingsHomeSection` or `SettingsDynamicSection` in the pinned source. The original implementation is `SettingsSections.FeedApiSection`, with additional card and navigation fields in `AppearanceSettingsScreen` and `HomeSettings`. This inventory does not equate two controls with full feature completion.','',
 '## Dynamic and recommendation fields','',
 '| Original field / key | Persisted default | Actual Windows status and source semantics |','|---|---|---|']
for row in rows:
 lines.append('| '+row['field']+' / `'+row['key']+'` | `'+str(row['default']).lower()+'` | '+row['status']+'; '+row['semantics']+' |')
for row in recommendation:
 lines.append('| '+row['field']+' / `'+row['key']+'` | `'+str(row['default'])+'` | '+row['status']+'; '+row['semantics']+' |')
lines+=['','## Home display / layout gaps','',
 'Current `DiscoveryVideoGrid` uses fixed `Adaptive(260.dp)`, 18dp outer padding and 16dp gaps. `DiscoveryVideoTile` uses a fixed 16:9 cover, two title lines, author name, play count and outside duration, and publish time whenever the payload supplies it. Those similarities to original defaults are not configurable consumer bindings. Its visible preview/feedback buttons do not implement the original disabled-by-default long-press setting.','',
 'Original grid policy caps content at 1280dp, uses AUTO 180dp minimum or presets COMPACT160/BALANCED200/WIDE260/ULTRA_WIDE320, separate compact/wide remembered fixed-column keys, and display-mode-dependent column bounds. Original card layout uses 6dp gaps and width-class/density policies: CURRENT16:9, OFFICIAL4:3, BILIPAI16:10 (default), with original single-column / Expanded+ exceptions. None of those Home layout consumers is installed by this Dynamic slice.','',
 'The JSON inventory records **every named field passed by the actual persisted `mapHomeSettingsFromPreferences`** below, including exact source read expressions and legacy migration fallback. Constructor defaults alone are not used as the effective default. In particular, persisted `homeHeroCarouselEnabled` defaults false although the data-class constructor says true; missing `home_duration_style` falls back to OUTSIDE_COVER when the legacy duration-badge key is absent/true and HIDDEN when false; frosted-glass falls back to the old combined tint key only if its separate key is missing. Old card-badge/info glass and smart feed guard fields are retired, fixed off upstream; they are not missing active toggles.','',
 '| HomeSettings field | Original persisted key(s) | Exact source default / migration expression |','|---|---|---|']
for row in home:
 expression=' '.join(row['actualSourceReadExpression'].split()).replace('|','\\|')
 lines.append('| `'+row['field']+'` | '+', '.join('`'+k+'`' for k in row['originalKeys'])+' | `'+expression+'`'+(' (retired)' if row['field'] in retired else '')+' |')
lines+=['','## Filters and additional state','',
 'The current Windows Home already consumes its real feed-source/count preferences, original recommendation feedback, the global full-model blocked-UP store, and plugin feed-filter configuration. These remain unchanged. Original HomeSettings display values above are not silently mirrored into that existing discovery preference authority.','',
 'Dynamic prepared transport retains `visible=false` folded items; display filtering uses existing `desktopVisibleDynamicItems` only for blocked author IDs, and current Windows cards retain their existing manual unfold behavior. Original rich DynamicCard/image-preview/header/user-rail rendering remains a separate missing UI block. Article/UP tabs, pin/hidden users, unread following metadata, local dynamic not-interested IDs, cold-start feed cache, and persisted selected user/tab/display state remain incomplete. Pure `DynamicTabPolicy` is included for shared original identity and labels, not as evidence that tab visibility/order/UP routing is implemented.','',
 '## Fetch / ownership boundary','',
 'Incremental refresh sends the retained `update_baseline` only on its first empty-offset request. The original multi-page fetch uses first-page `update_num`, stops on unchanged/empty cursor, retains folded payloads, and preserves the old tail cursor when the original preservation predicate allows it. Original page policy requires overlap and excludes cache placeholders, preserves old duplicate payload objects, prepends new items, then performs the original stable publish-time sort. Missing overlap causes replacement and the original ViewModel fresh-pagination synchronization. Original append de-duplicates by original key.','',
 'Windows binds those functions to the actual shared CommunityRepository callback and captures immutable MID + epoch for pre/post request ownership checks. Coroutine cancellation propagates and never becomes a fake API error. One request mutex protects page publication. As in the original repository, pagination state is updated per returned page: a later failed/cancelled multi-page request is **not a transactional pagination rollback**. The cancellation fixture proves retained page publication and mutex release, not a broader guarantee that every internal cursor mutation is undone.','',
 'No actual Bilibili account, API socket, native window or shared Gradle was used. The actual original controls and actual consumer geometry were tested in ImageComposeScene; actual product Retrofit/API query fields were tested through an application interceptor which never proceeds to a socket.']
(HERE/'FIELD-AUDIT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8',newline='\n')
print('AUDIT',len(rows),'dynamic keys,',len(recommendation),'existing recommendation fields,',len(home),'exact persisted HomeSettings mapping fields')
