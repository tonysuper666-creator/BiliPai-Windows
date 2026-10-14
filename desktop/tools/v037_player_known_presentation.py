"""Root presentation-only projection of the actual original interaction knowledge."""
from pathlib import Path
import importlib.util,json
from v029_comment_search import whole_proof

def apply(path,text,repo,output):
    name=Path(path).name
    supported={'LargeScreenVideoLayout.kt','VideoDetailScreenStateHolder.kt','VideoDetailPlayerTransitionHost.kt','TabletVideoLayout.kt','TabletCinemaLayout.kt'}
    if name not in supported:return text
    before=text
    def exact(old,new,label):
        nonlocal text
        assert text.count(old)==1,(name,label,text.count(old))
        text=text.replace(old,new,1)
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

