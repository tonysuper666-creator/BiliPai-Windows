"""Keep latest complete feedback algorithms; add only existing Root dispatch APIs."""
from v029_video_feedback import function,OUTPUT_VM
from v029_comment_search import whole_proof

def apply_latest_feedback(path,text,parser):
    if path!=OUTPUT_VM:return text,None
    before=text
    for operation in ['cachedFollowStatus', 'queryFollowStatus']:
        original='com.android.purebilibili.data.repository.ActionRepository.'+operation
        if text.count(original)!=1:raise ValueError('Unknown latest captured follow lookup: '+operation)
        text=text.replace(original,'environment.'+operation,1)
    for required in ['val likeStatusKnown: Boolean','val followStatusKnown: Boolean',
                     'enum class VideoMaidAction','val likeBurstId: Long','val tripleCelebrationId: Long']:
        if required not in text:raise ValueError('Incomplete latest original feedback contract: '+required)
    # Admission owns the sequence increment; the following completion wait needs
    # the same accepted id outside that bounded publication block.
    triple=function(text,'doTripleAction',parser)
    anchor='                    environment.commit {\n                        val visual = resolveTripleActionVisualState('
    if triple.count(anchor)!=1 or triple.count('val celebrationId = ++tripleCelebrationSequence')!=1:
        raise ValueError('Unknown latest triple publication/completion boundary')
    repaired=triple.replace(anchor,'                    var celebrationId = 0L\n'+anchor,1)
    repaired=repaired.replace('val celebrationId = ++tripleCelebrationSequence','celebrationId = ++tripleCelebrationSequence',1)
    if text.count(triple)!=1:raise ValueError('Triple method ownership mismatch')
    text=text.replace(triple,repaired,1)
    for name,parameters,arguments in [
        ('toggleLike','aid: Long? = null, bvid: String? = null, currentlyLiked: Boolean? = null, onResult: ((Boolean) -> Unit)? = null','aid, bvid, currentlyLiked, onResult'),
        ('toggleDislike','aid: Long? = null, bvid: String? = null, currentlyDisliked: Boolean? = null','aid, bvid, currentlyDisliked'),
        ('doCoin','count: Int, alsoLike: Boolean','count, alsoLike'),
    ]:
        body=function(text,name,parser)
        owned_name=name+'WithDesktopPresentation'
        old='    fun '+name+'('
        if body.count(old)!=1:raise ValueError('Unknown latest feedback method')
        owned=body.replace(old,'    internal fun '+owned_name+'(',1)
        boundary=owned.index(') {')
        owned=owned[:boundary].rstrip()+',\n        presentation: DesktopOriginalVideoEngagementPresentation? = null\n    '+owned[boundary:]
        if owned.count('viewModelScope.launch {')!=1:raise ValueError('Unknown latest feedback launch closure')
        owned=owned.replace('viewModelScope.launch {','viewModelScope.launch(presentation?.context ?: EmptyCoroutineContext) {',1)
        if name=='doCoin':
            old='        setCoinDialogVisible(false)'
            if owned.count(old)!=1:raise ValueError('Unknown original coin dialog acknowledgement')
            owned=owned.replace(old,'        if (presentation == null) setCoinDialogVisible(false)\n        else if (!presentation.admit { setCoinDialogVisible(false) }) return',1)
        wrapper='    fun '+name+'('+parameters+') {\n        '+owned_name+'('+arguments+', presentation = null)\n    }\n\n'
        if text.count(body)!=1:raise ValueError('Feedback complete method ownership mismatch')
        text=text.replace(body,wrapper+owned,1)
    proof=whole_proof(before,text)
    proof.update(fixedCommit='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c',
                 latestOriginalStateAndFeedbackAlgorithmsRetained=True,
                 previousSemanticDeltaNotReapplied=True,sourceQualified=False)
    return text,proof
