from pathlib import Path
import hashlib,json,difflib,os
H=Path(__file__).resolve().parent
R=H.parents[4]/'work/BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def digest(t):return hashlib.sha256(t.encode()).hexdigest()
rows=[];patch=[]
def transform(path,edit):
 base=safe(R/path).read_text(encoding='utf-8').replace('\r\n','\n');desired=edit(base)
 assert desired!=base
 target=H/'prepared/existing'/path;safe(target.parent).mkdir(parents=True,exist_ok=True)
 safe(target).write_text(desired,encoding='utf-8')
 rows.append({'path':path,'baseSha256LF':digest(base),'desiredSha256LF':digest(desired)})
 patch.extend(difflib.unified_diff(base.splitlines(True),desired.splitlines(True),'a/'+path,'b/'+path))
def factory(text):
 a='val imageWallpaper: @Composable (String, Any, Boolean, Modifier) -> Unit,'
 b='val imageWallpaper: @Composable (DesktopHomeMediaLifetime, String, Any, Boolean, Modifier) -> Unit,'
 assert text.count(a)==1;text=text.replace(a,b)
 a='val mediaPorts = DesktopHomeActualMediaPorts(mediaOwner, window.isVideoWallpaper,\n                window.imageWallpaper, window.musicOverlayVisible,'
 b='val mediaPorts = DesktopHomeActualMediaPorts(mediaOwner, window.isVideoWallpaper,\n                { uri, model, playing, modifier -> window.imageWallpaper(mediaOwner, uri, model, playing, modifier) }, window.musicOverlayVisible,'
 assert text.count(a)==1;return text.replace(a,b)
transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeRootFactory.kt',factory)
def gallery(text):
 anchor='    override val emotes get() = session.emotes\n'
 assert text.count(anchor)==1
 return text.replace(anchor,anchor+'    /** Read-only access to the SAME assets actor for original Profile gallery saves. */\n    internal val imageAssets: DesktopDynamicImageAssets get() = assets\n')
transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeGalleryBindings.kt',gallery)
def stack(text):
 text=text.replace('import androidx.compose.runtime.saveable.rememberSaveableStateHolder','import androidx.compose.runtime.saveable.SaveableStateHolder')
 anchor='    val transitionBackground: VideoCardTransitionBackgroundState,\n';assert text.count(anchor)==1
 text=text.replace(anchor,'')
 anchor='    chrome: DesktopOriginalRootChromeBindings,\n';assert text.count(anchor)==1
 text=text.replace(anchor,anchor+'    saveableState: SaveableStateHolder,\n')
 anchor='    chrome: DesktopOriginalRootChromeBindings,\n'
 text=text.replace(anchor,anchor+'    onActiveDestination: (BiliPaiNavKey) -> Unit,\n')
 anchor='        if (routes.owns()) CompositionLocalProvider(\n'
 assert text.count(anchor)==1
 text=text.replace(anchor,'        if (active && routes.owns()) SideEffect { onActiveDestination(key) }\n'+anchor)
 text=text.replace('    val saveableState = rememberSaveableStateHolder()\n','')
 anchor='            LocalVideoCardTransitionBackgroundState provides pages.transitionBackground,\n';assert text.count(anchor)==1
 text=text.replace(anchor,'')
 # NavDisplay creates this actual per-route transition state. Do not mask it with a new default.
 text=text.replace('pages.transitionBackground, pages.transitionClock, pages.window.globalHaze(),','LocalVideoCardTransitionBackgroundState.current, pages.transitionClock, pages.window.globalHaze(),')
 return text
transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalRootStack.kt',stack)
def return_owner(text):
 for before,after in [
  ('        if (bvid.isBlank()) return false\n', '        if (!owns() || bvid.isBlank()) return false\n'),
  ('        var returned = false\n', '        if (!owns()) return false\n        var returned = false\n'),
  ('        mutate {\n            admitRootNavigation {\n                if (!owns()) return@admitRootNavigation\n',
   '        admitRootNavigation {\n            mutate {\n                if (!owns()) return@mutate\n'),
  (' * Lock order is existing SessionStore/entry admission -> this projection. close never obtains',
   ' * Navigation checkpoint runs before SessionStore/entry admission; then this projection. close never obtains')]:
  assert text.count(before)==(2 if before.startswith('        mutate') else 1)
  text=text.replace(before,after)
 anchor='    fun consumeReturning(): Boolean = mutate {\n'
 assert text.count(anchor)==1
 addition="""    /** Original AppNavigation's markNavigation3VideoReturnBeforeBackAction: invoked at
     * the actual NavDisplay return commit, before the physical back pop. */
    fun prepareReturnBeforeBack(currentKey: BiliPaiNavKey, targetKey: BiliPaiNavKey?): Boolean {
        mutate {
            if (isVideoDetailRoute(currentKey.toLegacyRoute()) &&
                isVideoCardReturnTargetRoute(targetKey?.toLegacyRoute()))
                mutableSession.value = mutableSession.value.markReturning(monotonicMillis())
        }
        return owns() && mutableSession.value.isQuickReturnFromDetail
    }

"""
 return text.replace(anchor,addition+anchor)
transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeReturnNavigationOwner.kt',return_owner)

def retainer(text):
 anchor='    override fun close() {\n'
 assert text.count(anchor)==1
 return text.replace(anchor,'    /** Synchronous admission retirement only; the actual owner remains available for drain. */\n    fun retire() { closed.set(true) }\n'+anchor)
transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeRootRetainer.kt',retainer)
def entry(text):
 anchor='    val epoch: Long get() = capturedOwner.epoch\n'
 assert text.count(anchor)==1
 return text.replace(anchor,anchor+'    val mid: Long? get() = capturedOwner.mid.takeIf { it > 0L }\n')
transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeRetainedEntry.kt',entry)

def timeline(text):
 anchor='    val isAllTimeline: Boolean get() = type == "all"\n'
 assert text.count(anchor)==1
 text=text.replace(anchor,anchor+'    internal fun currentUpdateBaseline(): String = original.currentUpdateBaseline(type = type)\n')
 text=text.replace('    val scope=rememberCoroutineScope()\n    val layout by', '    val scope=rememberCoroutineScope()\n    val rootScroll = LocalDesktopRootDynamicScroll.current\n    val layout by',1)
 a='    fun fetch(refresh:Boolean) {if(!state.busy)scope.launch{state.fetch(refresh,incremental)}}\n'
 assert text.count(a)==1
 b=a+"""    LaunchedEffect(rootScroll, state) {
        rootScroll?.receiveAsFlow()?.collectLatest { request ->
            applyDesktopRootDynamicScroll(request, state.scroll) { state.fetch(true, incremental) }
        }
    }
"""
 text=text.replace(a,b)
 return text.replace('import kotlinx.coroutines.*','import kotlinx.coroutines.*\nimport kotlinx.coroutines.flow.receiveAsFlow\nimport kotlinx.coroutines.flow.collectLatest')
transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicTimeline.kt',timeline)

def tabs(text):
 a='    val grid=remember(state.selectedUid){LazyStaggeredGridState()}\n'
 assert text.count(a)==1
 b=a+"""    val rootScroll = LocalDesktopRootDynamicScroll.current
    LaunchedEffect(rootScroll, state, state.selectedUid) {
        rootScroll?.receiveAsFlow()?.collectLatest { request ->
            applyDesktopRootDynamicScroll(request, grid) { state.refreshUser() }
        }
    }
"""
 text=text.replace(a,b)
 return text.replace('import kotlinx.coroutines.flow.first','import kotlinx.coroutines.flow.first\nimport kotlinx.coroutines.flow.receiveAsFlow\nimport kotlinx.coroutines.flow.collectLatest')
transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicTabsHost.kt',tabs)

def registry(text):
 anchor='    fun isCurrentAll(model: DesktopDynamicTimelineState): Boolean = synchronized(models) {\n'
 assert text.count(anchor)==1
 return text.replace(anchor,'    /** Read only: the same registered All timeline remains the sole pagination authority. */\n    internal fun currentAllUpdateBaseline(): String = synchronized(models) {\n        if (alive.get() && stillOwned()) currentAll?.get()?.currentUpdateBaseline().orEmpty() else ""\n    }\n'+anchor)
transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicCardStateRegistry.kt',registry)

def repository(text):
 anchor='    fun logout() {\n        resetAuthentication()\n        sessions.logout()\n    }\n'
 assert text.count(anchor)==1
 addition='''
    /** AUTH Home nav invalidation: account mutation under the SAME Store admission only.
     * The caller drains its event outside the Store callback; HTTP cancellation occurs after
     * admission releases the Store monitor. Retired/foreign events never log out an account. */
    internal fun logoutHomeAuthenticationInvalidated(expectedEpoch: Long, expectedMid: Long,
        stillOwned: () -> Boolean): Boolean {
        val applied = try {
            sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) {
                if (expectedMid <= 0L || sessions.account.value?.mid != expectedMid) false
                else { sessions.logout(); true }
            }
        } catch (failure: BiliApiException) {
            if (failure.apiCode == -101) return false
            throw failure
        }
        if (applied) resetAuthentication()
        return applied
    }
'''
 return text.replace(anchor,anchor+addition)
transform('desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt',repository)

safe(H/'existing-hunks.patch').write_text(''.join(patch),encoding='utf-8')
safe(H/'existing-hunks-pins.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
print('Prepared',len(rows),'narrow A46 deltas')
