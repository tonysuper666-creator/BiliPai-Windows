"""Whole original known-state refresh functions on the retained Windows owner."""
from pathlib import Path
import hashlib,json,importlib.util
from v038_player_interaction_retry import fixed_sources,COMMIT,emit as emit_retry

def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def apply_owner(path,text,repo,output):
    target='com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
    if path!=target:return text,None
    from v029_comment_search import whole_proof
    raw=fixed_sources(repo)['VideoPlaybackViewModel.kt']
    parser=load('known_state_original_tokens',Path(repo)/'desktop/tools/sync-upstream.py')
    selector=load('known_state_original_methods',Path(repo)/'desktop/tools/extract-upstream-video-detail-full-units.py')
    selector.parser=parser
    before=text;methods=[]
    for name in ['refreshDeferredPlaybackSignals','ensureFollowStatus']:
        original=selector.func(raw,name,False);ported=original
        ported=ported.replace('com.android.purebilibili.data.repository.ActionRepository','environment.actions')
        ported=ported.replace('com.android.purebilibili.data.repository.VideoRepository.','VideoRepository.')
        ported=ported.replace('com.android.purebilibili.core.store.TokenManager.isVipCache = true','environment.account.updatePrimaryVip(true)')
        ported=ported.replace('com.android.purebilibili.core.store.TokenManager.sessDataCache.isNullOrEmpty()','!environment.account.hasSession')
        ported=ported.replace('viewModelScope.launch','environment.invocations.launch')
        if name=='ensureFollowStatus':
            launch='environment.invocations.launch {'
            assert ported.count(launch)==1
            ported=ported.replace(launch,'''environment.invocations.launch(desktopFinally = {
            synchronized(followStatusCheckInFlight) { followStatusCheckInFlight.remove(mid) }
        }) {''',1)
        assert 'TokenManager' not in ported and 'ActionRepository' not in ported
        start,end=selector.function_range(text,name)
        text=text[:start]+ported+text[end:]
        proof=whole_proof(original,ported)
        proof.update(originalMethod=name,completeOriginalSelected=True,fixedCommit=COMMIT,
                     capturedWindowsInvocationScopeRetained=True)
        methods.append(proof)
    before_call='''                            PlaybackPostLoadTask.REFRESH_DEFERRED_SIGNALS -> refreshDeferredPlaybackSignals(
                                bvid = loadedBvid,
                                aid = loadedAid,
                                ownerMid = loadedOwnerMid
                            )'''
    assert text.count(before_call)==1
    text=text.replace(before_call,before_call.replace('ownerMid = loadedOwnerMid','ownerMid = loadedOwnerMid,\n                                requestToken = requestToken'),1)
    tokens=parser.kotlin_tokens(text);insertions=[]
    for i in range(len(tokens)-4):
        if [t[0] for t in tokens[i:i+5]]==['isLiked','=','result','.','isLiked']:
            pos=tokens[i+4][2]
            line=text.rfind('\n',0,tokens[i][1])+1;indent=text[line:tokens[i][1]]
            assert indent.isspace()
            # Keep the existing comma as the final comma after both additional
            # original fields, preserving constructor/copy syntax.
            insertions.append((pos,',\n'+indent+'likeStatusKnown = result.likeStatusKnown,\n'+indent+'followStatusKnown = result.followStatusKnown'))
    assert len(insertions)==2,len(insertions)
    for pos,addition in reversed(insertions):text=text[:pos]+addition+text[pos:]
    proof=whole_proof(before,text)
    proof.update(fixedCommit=COMMIT,wholeOriginalMethods=methods,
                 originalLoadResultKnownFlagsForwardedAtBothExistingPublications=True,
                 originalPostLoadRequestTokenForwarded=True,sourceQualified=False,mainCompiled=False)
    folder=Path(output);folder.mkdir(parents=True,exist_ok=True)
    emit_retry(repo,folder)
    (folder/'v037-complete-known-state-owner-proof.json').write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode())
    return text,proof
