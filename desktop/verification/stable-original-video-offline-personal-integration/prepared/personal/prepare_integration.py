from pathlib import Path
import difflib,hashlib,json,os
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
patch='';receipts=[]
def transform(rel,replacements):
 global patch
 before=read(REPO/rel);after=before
 for a,b in replacements:
  assert after.count(a)==1,(rel,a[:100],after.count(a));after=after.replace(a,b)
 write(HERE/'prepared/existing'/rel,after)
 receipts.append(dict(path=rel,baseSha256LfUtf8=hashlib.sha256(before.encode()).hexdigest(),desiredSha256LfUtf8=hashlib.sha256(after.encode()).hexdigest()))
 patch+=''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel,n=3))

transform('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopReadyOriginalRootMount.kt',[
 ('    val ffprobe: Path,\n)', '    val ffprobe: Path,\n    val library: com.bilipai.desktop.DesktopLibrary,\n)'),
 ('    val route = AtomicReference<DesktopOriginalRootRouteAssembly?>()', '    val route = AtomicReference<DesktopOriginalRootRouteAssembly?>()\n    val personalLists = AtomicReference<DesktopPersonalListsRoot?>()'),
 ('        route.getAndSet(null)?.close()\n        retainer.closeAndJoin()', '        route.getAndSet(null)?.close()\n        personalLists.getAndSet(null)?.closeAndJoin()\n        retainer.closeAndJoin()'),
 ('    leaf: @Composable (BiliPaiNavKey, DesktopOriginalRootRouteCommands, Boolean, Boolean) -> Unit,', '    leaf: @Composable (BiliPaiNavKey, DesktopOriginalRootRouteCommands, Boolean, Boolean, DesktopPersonalListsRoot) -> Unit,'),
 ('        val profile = remember(root, windowBinding) { windowBinding.profile(root, services.profileAccounts(root.entry.gate)) }', '''        val personalLists = remember(root, services.library) { DesktopPersonalListsRoot(root.entry.gate,
            services.repository, services.runtime.store, services.library,
            services.community.searchPreferences::isPrivacyModeEnabledSync, services.feedback, haze) }
        SideEffect { handle.personalLists.set(personalLists); personalLists.prune(physicalStack.toList()) }
        DisposableEffect(personalLists) { onDispose {
            handle.personalLists.compareAndSet(personalLists, null); personalLists.close()
        } }
        val profile = remember(root, windowBinding) { windowBinding.profile(root, services.profileAccounts(root.entry.gate)) }'''),
 ('            services.historySearch, services.favoriteSearch, services.watchLaterSearch)', '            personalLists.historySearchChannel, services.favoriteSearch, services.watchLaterSearch)'),
 ('Modifier.fillMaxSize(), chromeBindings, { active -> activeDestination = active; services.activeDestinationChanged(active) }, saveable, leaf)', '''Modifier.fillMaxSize(), chromeBindings, { active -> activeDestination = active; services.activeDestinationChanged(active) }, saveable,
                { key, commands, active, hosted -> leaf(key, commands, active, hosted, personalLists) })'''),
])

branch='''                            section in listOf(DesktopSection.CLOUD_HISTORY, DesktopSection.WATCH_LATER, DesktopSection.FOLLOWINGS, DesktopSection.LIKED) ->'''
new='''                            entryKey == BiliPaiNavKey.History || entryKey is BiliPaiNavKey.HistorySearch || entryKey is BiliPaiNavKey.LikedVideos -> {
                                val entry = if (entryKey is BiliPaiNavKey.LikedVideos) personalLists.liked(entryKey)
                                    else personalLists.history(entryKey)
                                val queue = entry.queueBridge {
                                    DesktopFavoriteQueueBridge(playback, listen, entry::owns,
                                        { audio -> systemTargetAudio == audio },
                                        beforeOpen = { audio ->
                                            if (!entry.owns() || isClosing() || activatingUpdate ||
                                                !checkpointForNavigation() || (audio && listen == null)) false
                                            else {
                                                changePreferences(preferences.copy(playbackMode = com.bilipai.desktop.player.PlaybackMode.SEQUENTIAL))
                                                storyHost.retire(); retainedMedia.stop()
                                                if (audio) { playback.pause(); systemTargetAudio = true }
                                                else { listen?.pause(); systemTargetAudio = false }
                                                true
                                            }
                                        },
                                        revealVideo = {
                                            playback.state.value.queue.getOrNull(playback.state.value.queueIndex)?.let { card ->
                                                if (entry.owns()) commands.video(BiliPaiNavKey.VideoDetail(card.bvid,
                                                    card.preferredCid, card.cover,
                                                    initialVertical = entry.viewModel.uiState.value.items.firstOrNull { it.bvid == card.bvid }?.isVertical == true,
                                                    sourceRoute = entry.key.toLegacyRoute()))
                                            }
                                        }, revealAudio = {
                                            listen?.state?.value?.current?.let { item -> if (entry.owns()) commands.push(
                                                BiliPaiNavKey.AudioMode(item.bvid, item.cid,
                                                    ((listen.player.state.value.positionSeconds) * 1000L).toLong())) }
                                        })
                                }
                                val prefs = personalLists.preferences
                                val bindings = remember(entry, queue, rootTextShareBindings) {
                                    DesktopFavoriteBindings(prefs.showOnlineCount, prefs.homeSettings, prefs.initialHomeSettings(),
                                        prefs.navigationSettings, prefs.initialNavigationSettings(), entry.categories,
                                        queue::openQueue, queue::appendQueue,
                                        { subject, text, _ -> requestDesktopTextShare(rootTextShareBindings, entry.scope,
                                            subject, text, entry::owns, { error = it }) }, prefs.homeFeedCardStyle,
                                        desktopDetailRenderEffectsSupported(), prefs.backToTopEnabled, prefs.initialBackToTopEnabled(),
                                        prefs.backToTopOffset, prefs.initialBackToTopOffset(),
                                        { x,y -> if(entry.owns()) prefs.setBackToTopOffset(x,y) },
                                        { x,y -> if(entry.owns()) prefs.updateBackToTopOffset(x,y) })
                                }
                                val personalNavigation = remember(entry, commands) {
                                    val article = DesktopPersonalArticleResolver(repository.ownedHomeCallFactory(
                                        personalLists.gate.epoch, entry::owns), entry::owns)
                                    DesktopPersonalListNavigation(
                                        { key -> if(entry.owns()) commands.push(key) },
                                        { route -> if(entry.owns()) commands.push(com.android.purebilibili.navigation3.legacyRouteToBiliPaiNavKey(route)) },
                                        article::resolve, { key -> if(entry.owns()) commands.video(key) })
                                }
                                DesktopDetailWindow {
                                    DesktopOriginalPersonalListHost(entry, bindings, personalNavigation,
                                        onBack = { commands.back() }, onUp = { commands.push(BiliPaiNavKey.Space(it)) },
                                        onOpenHistorySearch = { commands.push(BiliPaiNavKey.HistorySearch(it)) },
                                        revealQueue = queue::revealIfOwned,
                                        onPlayAllAudio = { bvid,cid -> if(!queue.revealIfOwned(bvid,cid,true)) error="音频播放器当前不可用" },
                                        historySearchChannel = personalLists.historySearchChannel,
                                        historyScrollToTopChannel = personalLists.historyScrollToTopChannel,
                                        globalHazeState = personalLists.globalHazeState,
                                        isCurrentPage = active && !activatingUpdate)
                                }
                            }
                            section in listOf(DesktopSection.WATCH_LATER, DesktopSection.FOLLOWINGS) ->'''
transform('desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',[
 ('physicalDestination is BiliPaiNavKey.VideoDetail || retainedMedia.current!=null) },ffprobe)', 'physicalDestination is BiliPaiNavKey.VideoDetail || retainedMedia.current!=null) },ffprobe,library)'),
 ('{ entryKey,commands,active,pagerHosted ->', '{ entryKey,commands,active,pagerHosted,personalLists ->'),
 (branch,new),
])
write(HERE/'consumer.patch',patch)
write(HERE/'consumer-bases.json',json.dumps(receipts,indent=2)+'\n')
print('two existing exact Root family hunks ready; only History/Liked legacy leaves replaced')

