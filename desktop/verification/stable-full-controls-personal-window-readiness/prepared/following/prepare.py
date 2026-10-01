from pathlib import Path
import hashlib,importlib.util,json,os,re,difflib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];R=MAIN.parent/'BiliPai-v023'
def read(p):return Path(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):p=Path(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
spec=importlib.util.spec_from_file_location('fav',R/'desktop/tools/extract-upstream-favorites.py');fav=importlib.util.module_from_spec(spec);spec.loader.exec_module(fav)
spec=importlib.util.spec_from_file_location('parser',R/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser);fav.parser=parser
tool=read(R/'desktop/tools/extract-upstream-favorites.py')
old="for name in ['createFavFolder','favoriteVideo','getDefaultFolderId','toggleWatchLater']:"
new="for name in ['createFavFolder','favoriteVideo','getDefaultFolderId','toggleWatchLater',\n     'normalizeRelationTagIds','normalizeRelationTags','chunkFollowGroupTargetMids',\n     'isFollowGroupRetryableError','addUsersToRelationTagsWithRetry','followUser',\n     'getFollowGroupTags','getUserFollowGroupIds','getFollowGroupMemberMids',\n     'getAllFollowGroupUsers','getFollowGroupUsers','overwriteFollowGroupIds']:"
assert tool.count(old)==1;tool=tool.replace(old,new)
anchor=" imports='\\n'.join(l for l in s.splitlines() if l.startswith('import ') and 'NetworkModule'not in l and 'TokenManager'not in l)"
assert tool.count(anchor)==1
tool=tool.replace(anchor," members.insert(0, 'private companion object {\\n'+'\\n'.join(l for l in s.splitlines() if l.strip().startswith('private const val ') and any(k in l for k in ['FOLLOW_GROUP_', 'SPECIAL_FOLLOW_TAG_ID', 'ALL_FOLLOW_TAG_ID']))+'\\n}')\n"+anchor)
anchor=" body=drop_logs(body);output('data/repository/DesktopOriginalFavoriteActions',body,"
assert tool.count(anchor)==1;tool=tool.replace(anchor," body=body.replace('_followStateChanges.tryEmit(FollowStateChange(mid = mid, isFollowing = follow))','environment.confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))')\n"+anchor)
# Only this existing sole producer changes; other output bodies must remain equal.
write(HERE/'prepared/existing/desktop/tools/extract-upstream-favorites.py',tool)
write(HERE/'favorite-actions-producer.patch',''.join(difflib.unified_diff(read(R/'desktop/tools/extract-upstream-favorites.py').splitlines(True),tool.splitlines(True),fromfile='a/desktop/tools/extract-upstream-favorites.py',tofile='b/desktop/tools/extract-upstream-favorites.py')))
spec=importlib.util.spec_from_file_location('newfav',HERE/'prepared/existing/desktop/tools/extract-upstream-favorites.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
module.generate(R,HERE/'auxiliary-favorites-output',False)
for f in (HERE/'auxiliary-favorites-output').rglob('*.kt'):
 if f.name=='DesktopOriginalFavoriteActions.kt':write(HERE/'prepared/actions'/f.relative_to(HERE/'auxiliary-favorites-output'),read(f))
print('prepared sole actions producer')
spec=importlib.util.spec_from_file_location('following',HERE/'prepared/tools/extract-upstream-following.py');following=importlib.util.module_from_spec(spec);spec.loader.exec_module(following)
inventory=following.generate(R,HERE/'prepared/selected',True)
write(HERE/'source-inventory.json',json.dumps(inventory,ensure_ascii=False,indent=2)+'\n')
watch=MAIN/'desktop/.local/stable-personal-watchlater-parity'
rootbase=read(watch/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopPersonalListsRoot.kt')
s=rootbase.replace('    globalStore: DesktopPluginStore,','    private val globalStore: DesktopPluginStore,')
s=s.replace('    private val entriesLock = Any()','    private val following = linkedMapOf<BiliPaiNavKey.Following,DesktopFollowingEntry>()\n    private val entriesLock = Any()')
s=s.replace('        return DesktopFavoriteEnvironment(entryScope,', '        val capturedFollowOwner=repository.dynamicCacheSessionGuard.dynamicCacheOwner()?.takeIf {\n            it.epoch==gate.epoch && it.mid==(gate.mid ?: 0L)\n        } ?: throw CancellationException("Personal account owner retired")\n        return DesktopFavoriteEnvironment(entryScope,')
s=s.replace('{ repository.withPrimaryPlaybackAdmission(gate.epoch, owned) { repository.accessTokenCredentials().second } })', '{ repository.withPrimaryPlaybackAdmission(gate.epoch, owned) { repository.accessTokenCredentials().second } },\n            { change -> check(); repository.followStateEvents.confirm(capturedFollowOwner,change) })')
anchor='    fun history(key: BiliPaiNavKey): DesktopPersonalListEntry {'
assert s.count(anchor)==1
s=s.replace(anchor,'''    fun following(key: BiliPaiNavKey.Following): DesktopFollowingEntry {
        assertOwned()
        synchronized(entriesLock) {following[key]}?.let {return it}
        val created=DesktopFollowingEntry(this,key).also {entry ->
            val favorites=environment(entry.scope,entry::owns,entry::assertOwned,entry::commit)
            val cache=com.android.purebilibili.feature.following.DesktopFollowingCacheContext(globalStore,entry::owns,entry::commit)
            entry.install(com.android.purebilibili.feature.following.DesktopFollowingEnvironment(favorites,cache))
        }
        val selected=synchronized(entriesLock) {if(!owns())null else following.getOrPut(key) {created}}
        if(selected !== created)created.close()
        return selected ?: throw CancellationException("Personal root retired during Following creation")
    }

'''+anchor)
s=s.replace('        oldWatch.forEach(DesktopWatchLaterEntry::close)\n','        oldWatch.forEach(DesktopWatchLaterEntry::close)\n        val oldFollowing=synchronized(entriesLock) {following.keys.filterNot(keep::contains).mapNotNull(following::remove)}\n        oldFollowing.forEach(DesktopFollowingEntry::close)\n')
s=s.replace('        oldWatch.forEach(DesktopWatchLaterEntry::close); job.cancel()','        oldWatch.forEach(DesktopWatchLaterEntry::close)\n        val oldFollowing=synchronized(entriesLock) {following.values.toList().also {following.clear()}}\n        oldFollowing.forEach(DesktopFollowingEntry::close); job.cancel()')
rootrel='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopPersonalListsRoot.kt'
write(HERE/'prepared/existing'/rootrel,s)
consumer=''.join(difflib.unified_diff(rootbase.splitlines(True),s.splitlines(True),fromfile='a/'+rootrel,tofile='b/'+rootrel))
shellbase=read(watch/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt');shell=shellbase
anchor='                            entryKey == BiliPaiNavKey.WatchLater || entryKey is BiliPaiNavKey.WatchLaterSearch -> {'
assert shell.count(anchor)==1
shell=shell.replace(anchor,'''                            entryKey is BiliPaiNavKey.Following -> {
                                val entry=remember(personalLists,entryKey) {personalLists.following(entryKey)}
                                DesktopDetailWindow {
                                    DesktopOriginalFollowingHost(entry,onBack={commands.back()},
                                        onUserClick={mid->openUser(mid)},isCurrentPage=active && !activatingUpdate)
                                }
                            }
'''+anchor)
old='''                            section == DesktopSection.FOLLOWINGS ->
                                PersonalContentScreen(when(section) {
                                    DesktopSection.CLOUD_HISTORY -> PersonalSection.HISTORY
                                    DesktopSection.WATCH_LATER -> PersonalSection.WATCH_LATER
                                    DesktopSection.LIKED -> PersonalSection.LIKED
                                    else -> PersonalSection.FOLLOWINGS
                                }, repository, social, community, ::openVideo, ::openUser, { loginDialog = true }, ::openResource, ::openCollection)
'''
assert shell.count(old)==1;shell=shell.replace(old,'')
shellrel='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
write(HERE/'prepared/existing'/shellrel,shell)
consumer+=''.join(difflib.unified_diff(shellbase.splitlines(True),shell.splitlines(True),fromfile='a/'+shellrel,tofile='b/'+shellrel))
envrel='desktop/src/main/kotlin/com/android/purebilibili/feature/list/DesktopFavoriteEnvironment.kt'
envbase=read(R/envrel);env=envbase.replace('class DesktopFavoriteEnvironment(', 'class DesktopFavoriteEnvironment @JvmOverloads constructor(')
env=env.replace('    private val readAccessTokenPlatform: (() -> String)?,','    private val readAccessTokenPlatform: (() -> String)?,\n    private val followStateChanged: ((FollowStateChange) -> Unit)? = null,')
env=env.replace('    fun showFeedback(message: String)', '    fun confirmFollow(change:FollowStateChange) {\n        assertOwned(); requireNotNull(followStateChanged) { "Follow action notification is not mounted for this owner" }(change)\n    }\n    fun showFeedback(message: String)')
write(HERE/'prepared/existing'/envrel,env)
consumer+=''.join(difflib.unified_diff(envbase.splitlines(True),env.splitlines(True),fromfile='a/'+envrel,tofile='b/'+envrel))
write(HERE/'consumer.patch',consumer)
write(HERE/'consumer-bases.json',json.dumps([dict(path=rootrel,base=sha(rootbase),desired=sha(s)),dict(path=shellrel,base=sha(shellbase),desired=sha(shell)),dict(path=envrel,base=sha(envbase),desired=sha(env))],indent=2)+'\n')
