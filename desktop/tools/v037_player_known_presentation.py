"""Root presentation-only projection of the actual original interaction knowledge."""
from pathlib import Path
import importlib.util,json,hashlib
from v029_comment_search import whole_proof


COMMENT_CLOSE_MANIFEST_SHA = 'cf71fe8dd2b3b6f025f5d2bc207a27ada2df662e5f1c6741311a32e59bdcd92e'
COMMENT_CLOSE_PINS = [{'path': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailScreenStateHolder.kt', 'filename': 'VideoDetailScreenStateHolder.kt', 'bytes': 324800, 'sha256Bytes': 'ee5850a23af66dcd531b95cb7099e15bc69e12f62e49cfc332c11f003661a68d', 'gitBlob': '110f8d7e1d525f6f7a95e31c81a7e32051bd3372'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailPlayerTransitionHost.kt', 'filename': 'VideoDetailPlayerTransitionHost.kt', 'bytes': 20257, 'sha256Bytes': '3c55cc4b1ea3251c9bba506499d54bdc7f4fa1e78ca3aec9af72c37984c1d8a0', 'gitBlob': 'ce81fa4211bc20632b13b114caedfe4df077b9ac'}]

def original_comment_close_fragments():
    root=Path(__file__).resolve().parent.parent/'upstream-slices/v037-holder-comment-close'
    manifest=(root/'manifest.json').read_bytes()
    assert hashlib.sha256(manifest).hexdigest()==COMMENT_CLOSE_MANIFEST_SHA
    metadata=json.loads(manifest)
    assert metadata['fixedUpstreamCommit']=='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c' and metadata['files']==COMMENT_CLOSE_PINS
    texts={}
    for row in COMMENT_CLOSE_PINS:
        raw=(root/row['filename']).read_bytes()
        assert len(raw)==row['bytes'] and hashlib.sha256(raw).hexdigest()==row['sha256Bytes']
        assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==row['gitBlob']
        texts[row['filename']]=raw.decode('utf-8')
    holder=texts['VideoDetailScreenStateHolder.kt'];transition=texts['VideoDetailPlayerTransitionHost.kt']
    state='    val landscapeCommentCloseProgress = remember(currentBvid) { mutableFloatStateOf(1f) }'
    declaration='    val landscapeCommentCloseProgressProvider: () -> Float = { 0f },'
    projection='                landscapeCommentCloseProgressProvider = fullscreenExtras?.landscapeCommentCloseProgressProvider ?: { 0f },'
    assert holder.count(state)==1 and transition.count(declaration)==1 and transition.count(projection)==1
    assert holder.count('closeProgress = landscapeCommentCloseProgress,')==2
    assert holder.count('landscapeCommentCloseProgressProvider = { landscapeCommentCloseProgress.floatValue },')==2
    return state,declaration,projection

def apply(path,text,repo,output):
    name=Path(path).name
    supported={'LargeScreenVideoLayout.kt','VideoDetailScreenStateHolder.kt','VideoDetailPlayerTransitionHost.kt','TabletVideoLayout.kt','TabletCinemaLayout.kt'}
    if name not in supported:return text
    before=text
    def exact(old,new,label):
        nonlocal text
        assert text.count(old)==1,(name,label,text.count(old))
        text=text.replace(old,new,1)
    if name=='LargeScreenVideoLayout.kt':
        exact('    uiState: VideoPlaybackUiState,\n    bvid: String,',
              '    uiState: VideoPlaybackUiState,\n    engagementState: VideoEngagementUiState,\n    bvid: String,',
              'Required original state at the actual nested player host')
        exact('                uiState = uiState,\n                bvid = bvid,',
              '                uiState = uiState,\n                engagementState = engagementState,\n                bvid = bvid,',
              'Forward the same engagement owner from the outer large-screen layout')
    if name in {'VideoDetailScreenStateHolder.kt','VideoDetailPlayerTransitionHost.kt'}:
        closeState,closeDeclaration,closeProjection=original_comment_close_fragments()
        if name=='VideoDetailScreenStateHolder.kt':
            stateAnchor='    var landscapeCommentPanelOnLeft by rememberSaveable(currentBvid) { mutableStateOf(false) }'
            exact(stateAnchor,stateAnchor+'\n'+closeState,'Actual original close state shared by drawer and player')
            panelAnchor='                            isOnLeft = landscapeCommentPanelOnLeft,'
            assert text.count(panelAnchor)==2
            text=text.replace(panelAnchor,panelAnchor+'\n                            closeProgress = landscapeCommentCloseProgress,')
            import re
            pattern=r'(?m)^([ ]*)landscapeCommentPanelOnLeft = landscapeCommentPanelOnLeft,$'
            assert len(re.findall(pattern,text))==2
            text=re.sub(pattern,lambda m:m.group(0)+'\n'+m.group(1)+'landscapeCommentCloseProgressProvider = { landscapeCommentCloseProgress.floatValue },',text)
        else:
            declarationAnchor='    val landscapeCommentPanelOnLeft: Boolean,'
            exact(declarationAnchor,declarationAnchor+'\n'+closeDeclaration,'Original optional handoff provider')
            projectionAnchor='                landscapeCommentPanelOnLeft = fullscreenExtras?.landscapeCommentPanelOnLeft ?: true,'
            exact(projectionAnchor,projectionAnchor+'\n'+closeProjection,'Pass the actual shared close state through transition host')
    if name=='VideoDetailPlayerTransitionHost.kt':
        exact('    val isFollowed: Boolean,','    val isFollowed: Boolean,\n    val followStatusKnown: Boolean,','Required knowledge in the existing fullscreen handoff')
        exact('    val isLiked: Boolean,','    val isLiked: Boolean,\n    val likeStatusKnown: Boolean,','Required like knowledge in the existing fullscreen handoff')
        exact('isFollowed = fullscreenExtras?.isFollowed == true,','isFollowed = fullscreenExtras?.isFollowed == true,\n                followStatusKnown = fullscreenExtras?.followStatusKnown == true,','Nullable fullscreen handoff remains unknown before publication')
        exact('isLiked = fullscreenExtras?.isLiked == true,','isLiked = fullscreenExtras?.isLiked == true,\n                likeStatusKnown = fullscreenExtras?.likeStatusKnown == true,','Actual fullscreen handoff like knowledge')
    else:
        if name=='VideoDetailScreenStateHolder.kt':
            for old,new in [
                ('isFollowed = engagementState.isFollowing,','isFollowed = engagementState.isFollowing,\n                followStatusKnown = engagementState.followStatusKnown,'),
                ('isLiked = engagementState.isLiked,','isLiked = engagementState.isLiked,\n                likeStatusKnown = engagementState.likeStatusKnown,'),
            ]:
                assert text.count(old)==2,(name,'Actual fullscreen handoff and fullscreen player',text.count(old))
                text=text.replace(old,new)
        spec=importlib.util.spec_from_file_location('presentation_known_brackets',Path(repo)/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
        parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
        mask=parser.masked(text);needle='VideoPlayerSectionState('
        starts=[];position=0
        while True:
            start=mask.find(needle,position)
            if start<0:break
            opening=start+len(needle)-1;end=parser.balanced(mask,opening,'(',')')
            starts.append((opening+1,end-1));position=end
        assert len(starts)==1,(name,'Expected one actual fallback player state',len(starts))
        fields={'isFollowed':'engagementState.isFollowing','followStatusKnown':'engagementState.followStatusKnown',
                'isLiked':'engagementState.isLiked','likeStatusKnown':'engagementState.likeStatusKnown'}
        a,b=starts[0];body=text[a:b]
        import re
        additions=[key+' = '+value+',' for key,value in fields.items() if re.search(r'^\s*'+key+r'\s*=',body,re.M) is None]
        assert len(additions)>=2 or name=='VideoDetailScreenStateHolder.kt' and not additions,(name,'Known fields already projected')
        indent=re.search(r'\n([ ]+)playerState\s*=',body).group(1)
        text=text[:a]+'\n'+''.join(indent+line+'\n' for line in additions)+text[a:].lstrip('\n')
    proof=whole_proof(before,text)
    proof.update(path=path,actualOriginalEngagementKnowledgeForwarded=True,defaultKnownStatusIntroduced=False,sourceQualified=False)
    target=Path(output)/(name+'.v037-known-presentation-proof.json')
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode())
    return text

