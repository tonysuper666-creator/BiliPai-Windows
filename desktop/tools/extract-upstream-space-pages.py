"""Full v023 Space VM/Screen bodies with declared Windows owner seams.
Existing SpaceUiState, SpaceSubTab, original policies, cards and image owners stay unique.
Every transform has an exact reversible source receipt; no route or DTO invention.
"""
from pathlib import Path
import argparse, hashlib, importlib.util, json, re, subprocess, sys
sys.dont_write_bytecode = True
PIN = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE = 'app/src/main/java/com/android/purebilibili/'
PATHS = [BASE+'feature/space/'+name+'.kt' for name in
         ['SpaceViewModel','SpaceScreen','SpaceUpowerRankViewModel','SpaceMemberGuardViewModel','SpaceSupporterScreens','SpaceLoadingSkeleton']]
PATHS += [BASE+'data/repository/BangumiRepository.kt']
PATHS += [BASE+'feature/space/SpaceLoadPolicy.kt',BASE+'feature/space/SpaceDynamicLoadPolicy.kt']
PATHS += [BASE+'feature/space/SpaceTabChromePolicy.kt',BASE+'feature/space/SpaceSelectionChipColorPolicy.kt']

def digest(value): return hashlib.sha256(value.encode('utf8')).hexdigest()
def module(name, path):
    spec = importlib.util.spec_from_file_location(name,path)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result); return result

def generate(repo, out, include_screen=True):
    parser = module('space_parser',repo/'desktop/tools/sync-upstream.py')
    helper = module('space_graph',repo/'desktop/tools/extract-upstream-tablet-owner-space.py')
    receipts = []
    def source(path):
        original = subprocess.check_output(['git','-C',str(repo),'show',PIN+':'+path]).decode('utf8').replace('\r\n','\n')
        actual = (repo/path).read_text(encoding='utf8').replace('\r\n','\n')
        assert original == actual, 'Pinned source changed: '+path
        return original
    originals = {path:source(path) for path in PATHS}
    def produce(path, output_name, adapt):
        original = originals[path]; steps = []
        def edit(value, before, after, count=None):
            assert before != after
            positions = [m.start() for m in re.finditer(re.escape(before),value)]
            assert positions and (count is None or len(positions)==count), (path,before[:100],len(positions),count)
            result = value.replace(before,after)
            steps.append(dict(beforeSha256=digest(value),afterSha256=digest(result),original=before,adapted=after,
                positionsAfter=[pos+i*(len(after)-len(before)) for i,pos in enumerate(positions)]))
            return result
        def discard_logs(value):
            while True:
                match=re.search(r'(?:android\.util\.Log|com\.android\.purebilibili\.core\.util\.Logger)\.[deiw]\s*\(',value)
                if match is None:return value
                tokens=parser.kotlin_tokens(value);begin=next(i for i,t in enumerate(tokens)if t[1]>=match.start()and t[0]=='(')
                end=begin;level=1
                while level:end+=1;level+=(tokens[end][0]=='(')-(tokens[end][0]==')')
                value=edit(value,value[match.start():tokens[end][2]],'Unit',1)
        adapted = adapt(original,edit,discard_logs)
        reversed_source = adapted
        for step in reversed(steps):
            assert digest(reversed_source)==step['afterSha256']
            for pos in reversed(step['positionsAfter']):
                assert reversed_source[pos:pos+len(step['adapted'])]==step['adapted']
                reversed_source=reversed_source[:pos]+step['original']+reversed_source[pos+len(step['adapted']):]
            assert digest(reversed_source)==step['beforeSha256']
        assert reversed_source==original
        destination=out/output_name;destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_text(adapted,encoding='utf8',newline='\n')
        receipts.append(dict(source=path,sha256LF=digest(original),generated=output_name,generatedSha256LF=digest(adapted),
            completeOriginalFileReconstructed=True,transforms=steps))
    pkg='com/android/purebilibili/feature/space/'
    def vm(s,edit,logs):
        prefix=s[:s.index('class SpaceViewModel(')]
        imports=prefix[:prefix.index('enum class SpaceSubTab')]
        # Original state/model declarations are already wholly emitted by the sole existing producers.
        s=edit(s,prefix,imports,1)
        for line in ['import androidx.lifecycle.ViewModel\n','import androidx.lifecycle.viewModelScope\n',
                     'import com.android.purebilibili.core.network.NetworkModule\n',
                     'import com.android.purebilibili.core.network.getSpaceAggregate\n',
                     'import com.android.purebilibili.data.repository.BangumiRepository\n',
                     'import com.android.purebilibili.data.repository.ActionRepository\n',
                     'import com.android.purebilibili.data.repository.FavoriteRepository\n',
                     'import com.android.purebilibili.data.repository.HistoryRepository\n']:
            s=edit(s,line,'',1)
        s=edit(s,'class SpaceViewModel(\n    private val savedStateHandle: SavedStateHandle = SavedStateHandle()\n) : ViewModel() {',
            'internal class SpaceViewModel(\n    private val environment: com.bilipai.desktop.ui.DesktopOriginalSpaceEnvironment,\n    private val savedStateHandle: SavedStateHandle = SavedStateHandle()\n) {\n    private val viewModelScope get() = environment.scope',1)
        for before,after in [('private val spaceApi = NetworkModule.spaceApi','private val spaceApi get() = environment.spaceApi'),
                             ('MutableStateFlow(','environment.mutableStateFlow('),
                             ('ActionRepository.followStateChanges','environment.followStateChanges'),
                             ('ActionRepository','environment.actions'),('HistoryRepository','environment.history'),
                             ('FavoriteRepository','environment.favorites'),('BangumiRepository','environment.bangumi'),
                             ('NetworkModule.api','environment.api'),
                             ('spaceApi.getSpaceAggregate(mid = mid)','environment.getSpaceAggregate(mid)'),
                             ('fun loadSpaceInfo(mid: Long) {','fun loadSpaceInfo(mid: Long) {\n        environment.requireMid(mid)')]:
            s=edit(s,before,after)
        s=logs(s)
        # Ensure a cancelled caller is never downgraded to original nullable fallback/UI Error.
        if 'catch (_: Exception)' in s:s=edit(s,'catch (_: Exception)','catch (ignoredSpaceFailure: Exception)')
        pattern=re.compile(r'catch \((\w+): Exception\) \{')
        for name in dict.fromkeys(match.group(1) for match in pattern.finditer(s)):
            old='catch ('+name+': Exception) {'
            # One declared transform per spelling; repeated handlers must never
            # multiply the inserted cancellation checkpoint on subsequent scans.
            s=edit(s,old,old+'\n            if ('+name+' is CancellationException) throw '+name)
        if 'runCatching {' in s:
            s=edit(s,'runCatching {','ownedSpaceRunCatching {')
            if 'return@runCatching' in s:s=edit(s,'return@runCatching','return@ownedSpaceRunCatching')
        # The same global Repository WBI cache remains authoritative, not another retained key owner.
        allmembers=helper.members(parser,helper.classbody(parser,s,'SpaceViewModel'))
        old=allmembers['fetchWbiKeys'][0]['text']
        s=edit(s,old,'    private suspend fun fetchWbiKeys(): Pair<String,String>? = environment.wbiKeys().getOrNull()',1)
        for name in ['toggleFollow','saveFollowGroupSelection']:
            body=helper.members(parser,helper.classbody(parser,s,'SpaceViewModel'))[name][0]['text']
            new=body.replace('viewModelScope.launch {','environment.launchMutation("'+name+'") {',1)
            at=new.index('{')+1
            new=new[:at]+'\n        environment.requireVisibleAction()'+new[at:]
            s=edit(s,body,new,1)
        # The original dynamic search cancels its earlier actual Job. An owned
        # mutable flow correctly rejects cancelled response publication, including
        # the old catch block's cleanup. Reset that loading flag in the admitted
        # NEW query action so the successor does not wait forever for a rejected
        # stale cleanup. This is a declared Windows owner adaptation, not a new
        # search endpoint or changed matching/prefetch policy.
        declaration=helper.members(parser,helper.classbody(parser,s,'SpaceViewModel'))['updateSearchQuery'][0]['text']
        updated=declaration.replace('searchQuery = query','searchQuery = query,\n            isLoadingDynamics = if (scope == SpaceSearchScope.DYNAMIC) false else current.isLoadingDynamics',1)
        s=edit(s,declaration,updated,1)
        # Original supplemental IP hydration suspends after taking a whole-state
        # copy. Rebase only its supplemental fields on the latest admitted state
        # after that response, preserving newer sort/search/list selections.
        s=edit(s,'val updatedSupplementalState = applySpaceSupplementalData(','var updatedSupplementalState = applySpaceSupplementalData(',1)
        old='val dynamicResp = ownedSpaceRunCatching { spaceApi.getSpaceDynamic(hostMid = mid) }.getOrNull()'
        new=old+'''\n                    val latestState = _uiState.value as? SpaceUiState.Success ?: return@launch
                    updatedSupplementalState = applySpaceSupplementalData(
                        state = latestState,
                        seasons = seasons,
                        series = series,
                        createdFavoriteFolders = createdFavoriteFolders,
                        collectedFavoriteFolders = collectedFavoriteFolders,
                        seasonArchives = seasonArchives,
                        seriesArchives = seriesArchives
                    )
                    val currentInfo = updatedSupplementalState.userInfo'''
        s=edit(s,old,new,1)
        return s
    produce(BASE+'feature/space/SpaceViewModel.kt',pkg+'SpaceViewModel.kt',vm)
    for name in ['SpaceUpowerRankViewModel','SpaceMemberGuardViewModel']:
        def support(s,edit,logs,name=name):
            for line in ['import androidx.lifecycle.ViewModel\n','import androidx.lifecycle.viewModelScope\n','import com.android.purebilibili.core.network.NetworkModule\n']:
                s=edit(s,line,'',1)
            s=edit(s,'class '+name+'(','internal class '+name+'(\n    private val environment: com.bilipai.desktop.ui.DesktopOriginalSpaceEnvironment,',1)
            s=edit(s,') : ViewModel() {',') {\n    private val viewModelScope get() = environment.scope',1)
            s=edit(s,'private val spaceApi = NetworkModule.spaceApi','private val spaceApi get() = environment.spaceApi',1)
            s=edit(s,'MutableStateFlow(','environment.mutableStateFlow(')
            if 'NetworkModule.spaceApi' in s:s=edit(s,'NetworkModule.spaceApi','environment.spaceApi')
            s=logs(s)
            if 'runCatching {' in s:s=edit(s,'runCatching {','ownedSpaceRunCatching {')
            if 'return@runCatching' in s:s=edit(s,'return@runCatching','return@ownedSpaceRunCatching')
            if name=='SpaceUpowerRankViewModel':
                s=edit(s,'viewModelScope.launch {','environment.launchRead("rank") {',1)
                s=edit(s,'return@launch','return@launchRead')
            else:
                s=edit(s,'viewModelScope.launch {','environment.launchRead("guard") {',1)
                s=edit(s,'return@launch','return@launchRead')
            return s
        produce(BASE+'feature/space/'+name+'.kt',pkg+name+'.kt',support)
    def bangumi(s,edit,logs):
        method=s[s.index('    suspend fun getMyFollowBangumi('):s.rfind('}')].rstrip()
        prefix='''package com.bilipai.desktop.ui
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.data.model.response.MyFollowBangumiData
import kotlinx.coroutines.*
internal class DesktopOriginalSpaceBangumiRequests(private val api:BangumiApi,private val environment:DesktopOriginalSpaceEnvironment) {
'''
        s=edit(s,s,prefix+method+'\n}\n',1)
        s=edit(s,'TokenManager.midCache','environment.accountMid',1)
        s=logs(s)
        s=edit(s,'} catch (e: Exception) {','} catch (cancelled:CancellationException) { throw cancelled\n        } catch (e: Exception) {',1)
        return s
    produce(BASE+'data/repository/BangumiRepository.kt','com/bilipai/desktop/ui/DesktopOriginalSpaceBangumiRequests.kt',bangumi)
    # Existing selected Space policy bodies keep their one original producer.
    # Their shared generated outputs are explicit inputs; integration depends on
    # those existing tasks before this one. Only previously uncompiled declarations
    # from the complete original pure file enter the new source set.
    policyOwners=[repo/'desktop/build/generated/space-overview/com/android/purebilibili/feature/space/DesktopOriginalSpaceOverview.kt',
        repo/'desktop/build/generated/space-contributions/com/android/purebilibili/feature/space/DesktopUpstreamSpaceContributionDeclarations.kt']
    names=set()
    for owner in policyOwners:
        existing=owner.read_text(encoding='utf8').replace('\r\n','\n')
        names.update(helper.members(parser,existing))
    archiveOwner=repo/'desktop/build/generated/original-favorites/com/android/purebilibili/feature/space/DesktopFavoriteArchiveMappings.kt'
    archiveDeclarations=helper.members(parser,archiveOwner.read_text(encoding='utf8'))
    def verify_sole_declaration(original,existing):
        # These sole producers change visibility only, and already own the full
        # original body. Reject a changed mapping instead of dropping it by name.
        normalize=lambda value: re.sub(r'^\s*(?:private|internal)\s+', '', value).strip()
        assert normalize(original)==normalize(existing), 'Existing sole declaration drifted'
    def remaining(s,edit,logs):
        declarations=helper.members(parser,s)
        for name in ['mapSeasonArchiveToVideoItem','mapSeriesArchiveToVideoItem']:
            assert len(declarations[name])==len(archiveDeclarations[name])==1
            declaration=declarations[name][0]['text']
            verify_sole_declaration(declaration,archiveDeclarations[name][0]['text'])
            s=edit(s,declaration,'',1)
        for name in names:
            if name in declarations:
                for declaration in declarations[name]:s=edit(s,declaration['text'],'',1)
        return s
    produce(BASE+'feature/space/SpaceLoadPolicy.kt',pkg+'DesktopOriginalSpaceRemainingLoadPolicy.kt',remaining)
    # No existing producer or class is present for this complete pure file in actual88.
    # The shared task will emit it once; its original source remains a policy-extract
    # identity, avoiding a second Sync direct owner.
    produce(BASE+'feature/space/SpaceDynamicLoadPolicy.kt',pkg+'SpaceDynamicLoadPolicy.kt',lambda s,*_:s)
    for name in ['SpaceTabChromePolicy','SpaceSelectionChipColorPolicy']:
        produce(BASE+'feature/space/'+name+'.kt',pkg+name+'.kt',lambda s,*_:s)
    if include_screen:
        def screen(s,edit,logs):
            for line in ['import android.os.Build\n','import android.content.Intent\n',
                         'import androidx.lifecycle.viewmodel.compose.viewModel\n',
                         'import coil3.imageLoader\n','import com.android.purebilibili.R\n',
                         'import com.android.purebilibili.core.store.SettingsManager\n',
                         'import com.android.purebilibili.feature.dynamic.DynamicViewModel\n',
                         'import com.android.purebilibili.feature.dynamic.components.DynamicCommentOverlayHost\n',
                         'import com.android.purebilibili.feature.video.controller.PlaybackProgressManager\n']:
                s=edit(s,line,'',1)
            s=edit(s,'import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect\n','',1)
            for before,after in [
                ('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState'),
                ('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext'),
                ('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopSpaceWindowConfiguration as LocalConfiguration'),
                ('import androidx.compose.ui.res.stringResource','import com.bilipai.desktop.ui.desktopHomeStringResource as stringResource\nimport com.bilipai.desktop.ui.LocalDesktopOriginalSpacePlatform'),
                ('fun SpaceScreen(','internal fun SpaceScreen('),
                ('viewModel: SpaceViewModel = viewModel()','viewModel: SpaceViewModel = LocalDesktopOriginalSpacePlatform.current.viewModel'),
                ('val dynamicInteractionViewModel: DynamicViewModel = viewModel()','val dynamicInteractionViewModel = LocalDesktopOriginalSpacePlatform.current.dynamic'),
                ('collectAsStateWithLifecycle','collectAsState'),('initialValue =','initial ='),
                ('shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)','LocalDesktopOriginalSpacePlatform.current.supportsRenderEffects'),
                ('com.android.purebilibili.core.store.TokenManager.midCache','LocalDesktopOriginalSpacePlatform.current.environment.accountMid'),
                ('context.imageLoader','coil3.SingletonImageLoader.get(context)'),
                ('SettingsManager.setGridColumnCountCompact(context, finalColumns)','LocalDesktopOriginalSpacePlatform.current.home.settings.setGridColumnCountCompact(finalColumns)'),
                ('SettingsManager.setGridColumnCount(context, finalColumns)','LocalDesktopOriginalSpacePlatform.current.home.settings.setGridColumnCount(finalColumns)')]:
                if before in s:s=edit(s,before,after)
            s=edit(s,'val playbackProgressManager = remember(context) {\n        PlaybackProgressManager.getInstance(context)\n    }','val playbackProgressManager = LocalDesktopOriginalSpacePlatform.current',1)
            s=edit(s,'playbackProgressManager.getCachedPosition(bvid)','playbackProgressManager.cachedPosition(bvid)',1)
            s=edit(s,'val blockedUpRepository = remember { com.android.purebilibili.data.repository.BlockedUpRepository(context) }','val blockedUpRepository = LocalDesktopOriginalSpacePlatform.current.blockedUps',1)
            s=edit(s,'com.android.purebilibili.core.store.SettingsManager\n        .getSpacePlayedVideoLocatePromptEnabled(context)','LocalDesktopOriginalSpacePlatform.current.locatePromptEnabled',1)
            s=edit(s,'com.android.purebilibili.core.store.SettingsManager\n        .getHomeSettings(context)','LocalDesktopOriginalSpacePlatform.current.home.settings.homeSettings',1)
            s=edit(s,'com.android.purebilibili.feature.video.player.PlaylistManager','LocalDesktopOriginalSpacePlatform.current.playlist')
            # Android ACTION_SEND delegates to the same Root native share actor.
            before='''val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, "https://space.bilibili.com/$mid")
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "分享空间"))'''
            s=edit(s,before,'LocalDesktopOriginalSpacePlatform.current.shareSpace(mid)',1)
            # SpaceContent's actual original comment callback routes to dynamic detail;
            # its dormant openCommentSheet callback has no calling card in v023.
            # Preserve the callsite as a port, using the sole Root comment dispatcher.
            before='''DynamicCommentOverlayHost(
                            viewModel = dynamicInteractionViewModel,
                            primaryItems = dynamicCardItems,
                            toastContext = context,
                            onUserClick = onUserClick,
                        )'''
            s=edit(s,before,'/* Space v023 sends every card comment click to onDynamicDetailClick; the sole Root full comment/detail route is retained. */',1)
            while True:
                match=re.search(r'android\.widget\.Toast\.makeText\(',s)
                if match is None:break
                tokens=parser.kotlin_tokens(s);start=next(i for i,t in enumerate(tokens)if t[1]>=match.start()and t[0]=='(')
                end=start;level=1
                while level:end+=1;level+=(tokens[end][0]=='(')-(tokens[end][0]==')')
                raw=s[tokens[start][2]:tokens[end][1]]
                args=raw.strip().split(',')
                assert args[0].strip()=='context' and args[-1].strip()=='android.widget.Toast.LENGTH_SHORT',raw
                text=raw[raw.index(',')+1:raw.rfind(',')].strip()
                tail=s[tokens[end][2]:];assert tail.startswith('.show()')
                s=edit(s,s[match.start():tokens[end][2]+len('.show()')],
                    'LocalDesktopOriginalSpacePlatform.current.feedback('+text+')')
            for match in list(re.finditer(r'R\.string\.(\w+)',s))[::-1]:
                if match.group(0) in s:s=edit(s,match.group(0),'"'+match.group(1)+'"')
            s=edit(s,'LocalDesktopOriginalSpacePlatform.current','desktopSpacePlatform')
            s=edit(s,'viewModel: SpaceViewModel = desktopSpacePlatform.viewModel',
                'viewModel: SpaceViewModel = LocalDesktopOriginalSpacePlatform.current.viewModel',1)
            s=edit(s,'val context = LocalContext.current',
                'val context = LocalContext.current\n    val desktopSpacePlatform = LocalDesktopOriginalSpacePlatform.current')
            # Whole original handler already belongs to extract-space-overview.
            declaration=helper.members(parser,s)['handleAggregateArchiveClick'][0]['text']
            s=edit(s,declaration,'',1)
            return logs(s)
        produce(BASE+'feature/space/SpaceScreen.kt',pkg+'SpaceScreen.kt',screen)
        def supporter_screen(s,edit,logs):
            for line in ['import androidx.lifecycle.viewmodel.initializer\n','import androidx.lifecycle.viewmodel.viewModelFactory\n','import androidx.lifecycle.viewmodel.compose.viewModel\n']:
                s=edit(s,line,'',1)
            s=edit(s,'import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState\nimport com.bilipai.desktop.ui.LocalDesktopOriginalSpacePlatform',1)
            s=edit(s,'collectAsStateWithLifecycle','collectAsState')
            s=edit(s,'androidx.compose.ui.platform.LocalContext.current','coil3.compose.LocalPlatformContext.current')
            for name,method in [('SpaceUpowerRankViewModel','rank'),('SpaceMemberGuardViewModel','guard')]:
                start=s.index('    val viewModel: '+name+' = viewModel(');end=s.index('    val state by viewModel.uiState',start)
                s=edit(s,s[start:end],'    val viewModel = LocalDesktopOriginalSpacePlatform.current.'+method+'(mid, name, count)\n',1)
            originalGuardLabel=helper.members(parser,s)['resolveSpaceGuardLevelLabel']
            guardOwner=repo/'desktop/build/generated/space/com/android/purebilibili/feature/space/DesktopUpstreamSpaceDeclarations.kt'
            soleGuardLabel=helper.members(parser,guardOwner.read_text(encoding='utf8'))['resolveSpaceGuardLevelLabel']
            assert len(originalGuardLabel)==len(soleGuardLabel)==1
            verify_sole_declaration(originalGuardLabel[0]['text'],soleGuardLabel[0]['text'])
            s=edit(s,originalGuardLabel[0]['text'],'',1)
            return logs(s)
        produce(BASE+'feature/space/SpaceSupporterScreens.kt',pkg+'SpaceSupporterScreens.kt',supporter_screen)
        def skeleton(s,edit,logs):
            for line in ['import androidx.compose.ui.platform.LocalContext\n','import com.android.purebilibili.core.store.SettingsManager\n']:
                s=edit(s,line,'',1)
            s=edit(s,'import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState\nimport com.bilipai.desktop.ui.LocalDesktopOriginalSpacePlatform',1)
            s=edit(s,'    val context = LocalContext.current\n','',1)
            s=edit(s,'SettingsManager.getHomeSettings(context)','LocalDesktopOriginalSpacePlatform.current.home.settings.homeSettings',1)
            s=edit(s,'collectAsStateWithLifecycle','collectAsState');s=edit(s,'initialValue =','initial =')
            return logs(s)
        produce(BASE+'feature/space/SpaceLoadingSkeleton.kt',pkg+'SpaceLoadingSkeleton.kt',skeleton)
    (out/pkg/'DesktopOriginalSpaceRunCatching.kt').write_text('''package com.android.purebilibili.feature.space
import kotlinx.coroutines.CancellationException
internal inline fun <T> ownedSpaceRunCatching(block:()->T):Result<T> = try { Result.success(block()) }
catch(cancelled:CancellationException) { throw cancelled }
catch(failure:Throwable) { Result.failure(failure) }
''',encoding='utf8')
    (out/'source-transform-replay.json').write_text(json.dumps(dict(pin=PIN,files=receipts),ensure_ascii=False,indent=2),encoding='utf8')
    return receipts

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    args=p.parse_args();print(json.dumps(generate(args.repo,args.out),ensure_ascii=False,default=str)[:1200])
