from pathlib import Path
import hashlib,json,re,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[3];REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def pin(p,s):return dict(path=p,sha256LF=hashlib.sha256(s.encode()).hexdigest(),lines=len(s.splitlines()))
original={}
for p in ['app/src/main/java/com/android/purebilibili/feature/home/HomeScreen.kt','app/src/main/java/com/android/purebilibili/feature/profile/ProfileScreen.kt','app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt']:
 s=subprocess.check_output(['git','show',COMMIT+':'+p],cwd=REPO).decode().replace('\r\n','\n');assert read(REPO/p)==s;original[p]=s
home=original[next(p for p in original if p.endswith('HomeScreen.kt'))].splitlines()
profile=original[next(p for p in original if p.endswith('ProfileScreen.kt'))].splitlines()
def callbacks(lines,a,b):
 return [dict(name=m.group(1),signature=m.group(2).strip(),line=i+1) for i,line in enumerate(lines) if a-1<=i<b and (m:=re.search(r'\b(on[A-Z]\w+):\s*(.+)',line))]
homeCallbacks=callbacks(home,238,294);profileCallbacks=callbacks(profile,316,344)
assert len(homeCallbacks)==31 and len(profileCallbacks)==17
maps={
 'onVideoClick':('required-original-dispatch','Shell openVideo(card):705; HomeNavigationTarget/intent→Return38→VideoDetail/Story; do not flatten request'),
 'onAvatarClick':('existing-handler','loginDialog=true; AdvancedLoginDialog:1724; successful login must rebuild/refresh current Home epoch owner'),
 'onProfileClick':('missing-root-route','No PROFILE enum or DesktopOriginalProfileHost in Shell; USER:1670 is UP Space, not Profile'),
 'onLogout':('required-owner-action','repository.logout exists; route actor/analytics/current Home owner lifecycle must be consumed; raw nullable allowed only when legitimately hidden'),
 'onAccountSwitchClick':('missing-root-dialog','Original nullable if sidebar setting disabled; enabled requires saved-account dialog and sameStore switch, not loginDialog'),
 'onSettingsClick':('existing-handler','navigate(SETTINGS); preserve same settingsNavigator/Root owner'),
 'onSearchClick':('existing-handler','navigate(SEARCH); original scoped keyword routes and actual query/submitted distinction'),
 'onDynamicClick':('existing-handler','navigate(DYNAMIC); Root retained Home owner remains alive when covered'),
 'onHistoryClick':('existing-handler','navigate(CLOUD_HISTORY), not local HISTORY'),
 'onPartitionClick':('required-typed-host','Original BiliPaiNavKey.Partition/full Partition; current REGION branch:1594 is Discovery flat feed'),
 'onWeeklySeriesClick':('existing-handler-needs-return','WEEKLY with weeklyReturnSection/weeklyReturnVideo/weeklyInitialNumber:719-734; capture current source before navigating'),
 'onPartitionVideoClick':('required-original-dispatch','Preserve VideoItem.bvid/cid/pic/isVertical and explicit partition sourceRoute:2359-2367'),
 'onLiveClick':('existing-handler-needs-metadata','openLive(roomId):834; preserve title/uname typed Live key; current roomID-only fetch not all initial metadata'),
 'onBangumiClick':('existing-handler-needs-selection','seasonType=initialType then showSeason(0); full HomeBangumi delegate, not losing initialType'),
 'onCategoryClick':('required-typed-host','Category53 route TID/name/full original VM; current REGION does not store immutable category route'),
 'onFavoriteClick':('existing-handler','navigate(CLOUD_FAVORITES)/current Favorites entry; not local FAVORITES'),
 'onLikedVideosClick':('existing-handler','navigate(LIKED), actual PersonalSection.LIKED consumer:1542-1548'),
 'onLiveListClick':('required-typed-host','Full original LiveList/Home delegate; current LIVE room/browser alone is not original full list route'),
 'onLiveSearchClick':('required-typed-host','Live100 LiveSearch immutable entry + original Search API; no plain video SEARCH substitution'),
 'onLiveAreaClick':('required-typed-host','Live100 LiveArea immutable entry and original all-area page'),
 'onLiveFollowingClick':('required-typed-host','Live100 LiveFollowing, not PersonalSection.FOLLOWINGS'),
 'onLiveAreaDetailClick':('required-typed-host','Live100 LiveAreaDetail(parentAreaId,areaId,title); preserve all three and page/sort owner'),
 'onBangumiSeasonClick':('existing-handler-needs-detail','Original BangumiDetail season key:2393-2395; showSeason(id) currently browser entry, Root full-detail delegate needed'),
 'onBangumiEpisodeClick':('existing-handler-needs-detail','Original Home BangumiDetail(seasonId,epId); keep both, unlike Profile epId>0 direct BangumiPlayer'),
 'onWatchLaterClick':('existing-handler','navigate(WATCH_LATER), real same-account owner'),
 'onDownloadClick':('existing-handler','navigate(DOWNLOADS), same DesktopDownloadManager'),
 'onInboxClick':('existing-handler','navigate(MESSAGES), real Messages root, not browser/external/noop'),
 'onStoryClick':('existing-handler','openStory() with empty original seed permitted:752; Home video vertical dispatch still independent'),
 'onPluginsClick':('existing-handler','navigate(PLUGINS), same pluginRuntime/settings ownership'),
 'onSpaceClick':('existing-handler','openUser(mid):736 is correct Space route; this is distinct from onProfileClick'),
 'onVideoDetailReturnAnimationConsumed':('required-return-effect','Return38.consumeReturning; actual source/returnSession/clock exposure, not a constant false flag'),
}
for row in homeCallbacks:row.update(status=maps[row['name']][0],candidate=maps[row['name']][1])
profileMaps={
 'onBack':'Current retained Home top-level key; no openUser fallback',
 'onGoToLogin':'loginDialog=true, actual account epoch completion',
 'onLogoutSuccess':'Refresh/reconstruct CURRENT Home owner after sameStore logout; do not call retired VM',
 'onAccountSwitchSuccess':'Refresh/reconstruct CURRENT Home owner after epoch switch; preserve primary/planner retirement ordering',
 'onSettingsClick':'SETTINGS', 'onSearchClick':'SEARCH', 'onHistoryClick':'CLOUD_HISTORY', 'onFavoriteClick':'CLOUD_FAVORITES',
 'onSubscriptionClick':'Original FavoriteSubscribed key/category on same Favorite owner; no current Shell dedicated route',
 'onFavoriteFolderClick':'SeasonSeriesDetail(type=favorite,id=mediaId,mid=ownerMid,title); Shell CommunityNavigation folder handler:1172-1173 preserves title; openCollection helper:841 loses title',
 'onFollowingClick':'Original Following(mid); current Personal FOLLOWINGS is current-account list and cannot silently discard supplied MID',
 'onDownloadClick':'DOWNLOADS','onWatchLaterClick':'WATCH_LATER','onInboxClick':'MESSAGES',
 'onVideoClick':'Full video route BVID with original CID=0 default and return source from actual Profile host',
 'onBangumiClick':'Original epId>0→BangumiPlayer(seasonId,epId), otherwise BangumiDetail; do not conflate with Home BangumiDetail',
 'onBangumiMoreClick':'HomeBangumi/initialType=1',
}
for row in profileCallbacks:row.update(requiredCandidate=profileMaps[row['name']])
blockers=[
 ('full-profile-route','Shell enum104-112 and USER1670','Mount DesktopOriginalProfileHost/retained original VM; USER cannot satisfy Profile callback'),
 ('full-home-video-request','Home240/AppNav1275-1300/Shell705','Preserve cid/vertical/sourceRoute and dynamic redirect; current VideoCard flatten cannot carry original intent'),
 ('story-original-policy','AppNav1002-1272/Shell752','Respect actual directPortraitStoryEntry/authenticated orientation lookup; Home button empty Story is different from vertical video dispatch'),
 ('return-geometry-clock','AppNav548,795-845,1173-1295/Return38.enterVideo','Required actual currentKey/ancestor/visible routes/root origin/CardPosition snapshot and one real clock; capture before destination/new playback'),
 ('return-effect-and-top-active','AppNav2409-2415','Retained data owner survives cover; isTopLevelActive/returning/quick and consume use real route state, no constant false'),
 ('account-switch-and-epoch','AppNav2335-2353/Profile2856-2857','Nullable switch only original disabled setting; sameStore dialog/primary account actions must retire old VM before new planner factory'),
 ('partition-category-route','AppNav2358-2376/Shell1594','Full Partition and immutable Category(TID,name) route entry; REGION/Discovery cannot substitute'),
 ('live-four-subroutes','AppNav2380-2391','Distinct LiveSearch/Area/Following/AreaDetail entries preserving IDs/title, same Root epoch and real back route'),
 ('bangumi-detail-versus-player','AppNav2393-2399,2883-2900','Home detail season+episode differs from Profile direct episode player; type selection cannot be dropped'),
 ('favorite-subscribed-folder','Profile2865-2876/Shell1172','Same Favorite VM category versus collection/folder route with mediaID/ownerMID/title; do not reuse local Favorites'),
 ('following-mid','Profile2878/Shell1542-1548','Arbitrary supplied MID needs original Following consumer; personal current-account list does not prove that route'),
 ('cloud-personal-route-map','Home2378-2403/Profile2860-2881','Cloud History/Favorites/WatchLater/Liked/Inbox actual handlers; local History/Favorites are distinct'),
 ('profile-active-budget-and-refresh','Profile316-343/AppNav2851-2906','Real isCurrentPage/account refresh generation/showHistory policy/skin paths/defer budget/profile reselect channel required'),
 ('navigation-admission-and-planner','Shell685-703/Parent retained owner contract','Checkpoint acceptance precedes Root commit; commitIfCurrent wraps every nav; hide/cover does not close retained Home/planner'),
 ('embedded-pages-and-global-effects','Home347-349/UI524 Root integration','Same Subscription articleOpen/list state/plugin settings; global Home platform/media/metric locals above all consumers; no unconsumed placeholder port'),
]
candidatePaths=['desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt','desktop/src/main/kotlin/com/bilipai/desktop/DesktopPlaybackController.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopProfileBindings.kt']
report=dict(scope='read-only source audit; no producer/compile/runtime acceptance',stableCommit=COMMIT,
 originalSources=[pin(p,s) for p,s in original.items()],observedCandidateSources=[pin(p,read(REPO/p)) for p in candidatePaths],
 homeCallbackCount=31,profileCallbackCount=17,homeCallbacks=homeCallbacks,profileCallbacks=profileCallbacks,
 priorityBlockers=[dict(id=i,sourceAnchor=a,required=r) for i,a,r in blockers],
 fullProfileIsUserSpace=False,noMainEdits=True,noGradle=True,noHTTP=True,noGUI=True,
 note='Observed mutable sibling sources pinned as read; installing raw renderer identities does not prove mounted routes. Parent owns all Home Root factory/Nav wiring; Playback84 stays frozen.')
safe(HERE/'nav-map.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print('31 Home +17 Profile contracts;15 blockers;SHA '+hashlib.sha256(safe(HERE/'nav-map.json').read_bytes()).hexdigest())
