"""Complete original owner-follow methods on the sole Windows engagement VM.

Source requests keep the existing captured presentation. Only transient
submission cleanup uses the data-entry permit after a presentation retires.
"""
from pathlib import Path
import hashlib
import json
import re

COMMIT = '5c34a82418b33bf88acea01784bb3c6b179d6491'
ARCHIVE = 'desktop/upstream-slices/v042-owner-follow'
MANIFEST_SHA256 = '3254fb262d038dac0443c0ba7bd2f31319d5071b4ffaa603734cd2f670c2d061'
VM = 'com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt'
POLICY = 'com/android/purebilibili/feature/video/ui/feedback/DesktopOriginalFollowActionPolicy.kt'


def sha(text):
    return hashlib.sha256(text.encode() if isinstance(text, str) else text).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('Unreviewed owner follow manifest')
    manifest = json.loads(raw)
    if manifest['fixedUpstreamCommit'] != COMMIT or {r['filename'] for r in manifest['files']} != {
            'VideoEngagementViewModel.kt', 'VideoActionFeedbackPolicy.kt',
            'UpPreviewSheet.kt', 'PortraitVideoPager.kt'}:
        raise ValueError('Unreviewed owner follow source set')
    result = {}
    for row in manifest['files']:
        raw = (root / row['filename']).read_bytes()
        oid = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if len(raw) != row['bytes'] or sha(raw) != row['sha256Bytes'] or oid != row['gitBlob']:
            raise ValueError('Changed original owner follow source')
        result[row['filename']] = raw.decode('utf-8')
    return result


def apply_ui(repo, path, text, output, spec):
    """Same original preview and pager callers, through their sole producer."""
    sheet = 'com/android/purebilibili/feature/video/ui/components/UpPreviewSheet.kt'
    pager = 'com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt'
    if path not in {sheet, pager}:
        return text
    sources = fixed_sources(repo)
    previous = text
    edits = []
    if path == sheet:
        text = sources['UpPreviewSheet.kt']
        original = text
        for edit in spec['edits'] + [dict(
            before='import com.android.purebilibili.core.ui.components.AppLazyVerticalGrid as LazyVerticalGrid',
            after='import androidx.compose.foundation.lazy.grid.LazyVerticalGrid',
            label='Existing Windows Compose grid owns desktop scrolling')]:
            count = edit.get('count', 1)
            if text.count(edit['before']) != count:
                raise ValueError('Original UP preview Windows port changed: ' + edit['label'])
            for _ in range(count):
                edits.append(dict(edit, count=1, offset=text.index(edit['before'])))
                text = text.replace(edit['before'], edit['after'], 1)
        inverse = text
        for edit in reversed(edits):
            at = edit['offset']
            if inverse[at:at + len(edit['after'])] != edit['after']:
                raise ValueError('UP preview inverse changed: ' + edit['label'])
            inverse = inverse[:at] + edit['before'] + inverse[at + len(edit['after']):]
        if inverse != original:
            raise ValueError('Whole original UP preview not recovered')
        proof = dict(wholeOriginalSheetInverseRecovered=True,
            originalSHA256=sha(original), outputSHA256=sha(text), adaptations=edits,
            wholePreviousInputSHA256=sha(previous), mobileGridMappedToExistingWindowsGrid=True)
    else:
        raw = sources['PortraitVideoPager.kt']
        # Exact original status comes from the current video's confirmed owner.
        a = raw.index('    val interactionSuccess = currentSuccess?.takeIf {')
        b = raw.index('    val resolvedInteractionState =', a)
        owner = raw[a:b]
        a = raw.index('    val followStatusKnown = interactionSuccess?.followStatusKnown == true')
        b = raw.index('    val detailVideoList =', a)
        known = raw[a:b]
        old = '    val isFollowing = (currentUiState as? VideoPlaybackUiState.Success)?.followingMids?.contains(authorMid) == true\n'
        callback = '                onFollowClick = {\n                    if (canHandlePortraitInteraction && authorMid > 0L) onToggleFollow(authorMid, isFollowing)\n                },'
        if callback not in raw:
            raise ValueError('Original portrait follow callback changed')
        for before, after, label in [
            (old, owner + known, 'Original current video and owner status'),
            ('                owner = info.owner,\n                isFollowing = isFollowing,\n',
             '                owner = info.owner,\n                isFollowing = isFollowing,\n                followStatusKnown = followStatusKnown,\n',
             'Original status passed to original UP preview'),
            ('                onFollowClick = { onToggleFollow(authorMid, isFollowing) },', callback,
             'Original active-page and valid-owner follow callback'),
        ]:
            if text.count(before) != 1:
                raise ValueError('Existing portrait owner boundary changed: ' + label)
            edits.append(dict(before=before, after=after, label=label, offset=text.index(before)))
            text = text.replace(before, after, 1)
        inverse = text
        for edit in reversed(edits):
            at = edit['offset']
            if inverse[at:at + len(edit['after'])] != edit['after']:
                raise ValueError('Portrait follow caller inverse changed')
            inverse = inverse[:at] + edit['before'] + inverse[at + len(edit['after']):]
        if inverse != previous:
            raise ValueError('Previous complete Windows portrait pager not recovered')
        proof = dict(wholePreviousInputInverseRecovered=True, inputSHA256=sha(previous),
            outputSHA256=sha(text), selectedOriginalExpressionsSHA256=sha(owner + known + callback),
            originalPagerSHA256=sha(raw), edits=edits, fullOriginalPagerParityClaimed=False)
    proof.update(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        unknownStatusRetryInteractive=True, sameWindowsAccountAndEntryPorts=True,
        mainCompiled=False, applicationExecuted=False)
    target = Path(output) / (Path(path).name + '.v042-owner-follow-ui-proof.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return text


def apply(path, text, g):
    if path != VM:
        return text, None
    sources = fixed_sources(g['REPO'])
    raw = sources['VideoEngagementViewModel.kt']
    raw_vm = g['selected'](raw, ['VideoEngagementViewModel'])
    original = text
    edits = []
    method_edits = []

    def replace(before, after, label):
        nonlocal text
        if text.count(before) != 1:
            raise ValueError('Owner follow projection boundary changed: ' + label)
        at = text.index(before)
        edits.append(dict(offset=at, before=before, after=after, label=label))
        text = text[:at] + after + text[at + len(before):]

    state = g['selected'](text, ['VideoEngagementUiState'])
    field = '    val followStatusKnown: Boolean = false,\n'
    submitting = '    val isFollowSubmitting: Boolean = false,\n'
    if state.count(field) != 1 or submitting not in raw:
        raise ValueError('Unknown original follow submission field')
    replace(state, state.replace(field, field + submitting, 1), 'Original submitting field on the same original state')
    match = re.search(r'(?m)^    private var followRevision[^\n]*\n', text)
    if match is None:
        raise ValueError('Missing original follow revision')
    replace(match.group(0), match.group(0) + '    private var ownerFollowRequests = 0\n'
        '    private var desktopFollowGeneration: Any = Any()\n', 'Original owner counter and Windows subject cleanup generation')
    bind = g['func'](g['selected'](text, ['VideoEngagementViewModel']), 'bindSubject', False)
    marker = '            coinDialogGeneration = Any()\n'
    if bind.count(marker) != 1:
        raise ValueError('Original subject reset no longer uses its existing atomic admission')
    replace(bind, bind.replace(marker, marker + '            ownerFollowRequests = 0\n'
        '            desktopFollowGeneration = Any()\n', 1), 'Reset pending counter with the same subject publication')

    method = g['func'](raw_vm, 'changeFollow', False)
    original_method = method

    def swap(before, after, label, count=1):
        nonlocal method
        if method.count(before) != count:
            raise ValueError('Original follow method boundary changed: ' + label)
        method_edits.append(dict(before=before, after=after, count=count, label=label))
        method = method.replace(before, after, count)

    swap('private fun changeFollow(mid: Long?, followOnly: Boolean)',
         'private fun changeFollow(mid: Long?, followOnly: Boolean, presentation: DesktopOriginalVideoEngagementPresentation? = null)',
         'Existing captured Windows presentation parameter')
    swap('actions.queryFollowStatus(targetMid)', 'environment.queryFollowStatus(targetMid)',
         'Same source/account owned query port', 2)
    swap('com.android.purebilibili.data.repository.ActionRepository.cachedFollowStatus(targetMid)',
         'environment.cachedFollowStatus(targetMid)', 'Same shared account cache port')
    swap('''                        val following = queryVideoInteractionStatusWithRetry(
                            isCurrent = { isCurrent(state, session) },
                            query = { environment.queryFollowStatus(targetMid) },
                        ).getOrElse {''', '''                        val following = kotlinx.coroutines.withContext(
                            presentation?.context ?: EmptyCoroutineContext
                        ) {
                            queryVideoInteractionStatusWithRetry(
                                isCurrent = { isCurrent(state, session) },
                                query = { environment.queryFollowStatus(targetMid) },
                            )
                        }.getOrElse {''',
         'Restore the same captured caller frame after original nested timeout resumes')
    before = '''        if (isOwner && ownerFollowRequests++ == 0) {
            _uiState.update { it.copy(isFollowSubmitting = true) }
        }
'''
    after = '''        val submitGeneration = desktopFollowGeneration
        var admitted = false
        val begin: () -> Unit = {
            environment.commit {
                if (isCurrent(state, session) && desktopFollowGeneration === submitGeneration &&
                    !(isOwner && !state.followStatusKnown && ownerFollowRequests > 0)) {
                    if (isOwner && ownerFollowRequests++ == 0) {
                        _uiState.update { it.copy(isFollowSubmitting = true) }
                    }
                    admitted = true
                }
            }
        }
        if (presentation == null) begin() else presentation.admit(begin)
        if (!admitted) return
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun completeSubmission() {
            if (!isOwner || !finished.compareAndSet(false, true)) return
            environment.completeFollowSubmission(_uiState, state.subject) {
                if (desktopFollowGeneration !== submitGeneration || ownerFollowRequests <= 0) false
                else --ownerFollowRequests == 0
            }
        }
'''
    swap(before, after, 'Only counter and transient state enter the synchronous source permit')
    swap('        viewModelScope.launch {\n',
         '        val followJob = try { viewModelScope.launch(presentation?.context ?: EmptyCoroutineContext) {\n'
         '            currentCoroutineContext().ensureActive(); environment.assertOwned()\n',
         'Launch original I/O only after synchronous admission returns')
    before = '''                if (isOwner && isCurrent(state, session) && --ownerFollowRequests == 0) {
                    _uiState.update { it.copy(isFollowSubmitting = false) }
                }'''
    swap(before, '                completeSubmission()', 'Only own transient submission can finish after source retirement')
    ending = '        }\n    }'
    if not method.endswith(ending):
        raise ValueError('Unknown original follow launch end')
    method = method[:-len(ending)] + '''        } } catch (failure: Throwable) {
            completeSubmission()
            throw failure
        }
        followJob.invokeOnCompletion { completeSubmission() }
    }'''
    method_edits.append(dict(label='Completion fallback also covers a cancelled-before-start original Job',
        suffixBefore=ending, suffixAfter=method[method.rfind('        } } catch'):]))
    # Keep both complete original result publications, under the existing
    # captured source/account/entry permit, with no HTTP inside that permit.
    for marker, stop, label in [
        ('                        // 查询恢复后即使切换失败', '\n                    }\n                    val wasFollowing', 'Confirmed query publication'),
        ('                            locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FOLLOWING_MIDS',
         '                            emitMessage(if (following)', 'Confirmed follow mutation publication'),
    ]:
        a = method.index(marker)
        if label == 'Confirmed query publication':
            a = method.index('                        locallyModifiedFields =', a)
        b = method.index(stop, a)
        block = method[a:b]
        indent = re.match(r' *', block).group(0)
        wrapped = indent + 'environment.commit {\n' + indent + '    if (!isCurrent(state, session)) throw CancellationException("Follow subject retired before publication")\n'
        wrapped += ''.join('    ' + line if line.strip() else line for line in block.splitlines(True))
        if not wrapped.endswith('\n'):
            wrapped += '\n'
        wrapped += indent + '}\n'
        swap(block, wrapped, label)
    swap('                    val revision = ++followRevision',
         '                    var revision = 0L\n                    environment.commit {\n'
         '                        if (!isCurrent(state, session)) throw CancellationException("Follow subject retired before mutation")\n'
         '                        revision = ++followRevision\n                    }', 'Original mutation revision under the same owner admission')
    swap('_uiState.update {', 'updateOwned {', 'Existing owned state publication', method.count('_uiState.update {'))
    swap('_events.send(VideoEngagementEvent.OpenFollowGroups(targetMid))',
         'sendOwned(VideoEngagementEvent.OpenFollowGroups(targetMid))', 'Existing generation and presentation stamped event channel')
    swap('                        .onSuccess { following ->\n',
         '                        .onSuccess { following ->\n                            currentCoroutineContext().ensureActive(); environment.assertOwned()\n',
         'Existing cancellation and account checkpoint before result publication')
    method_inverse = method
    for edit in reversed(method_edits):
        if 'suffixAfter' in edit:
            if not method_inverse.endswith(edit['suffixAfter']):
                raise ValueError('Original follow completion inverse changed')
            method_inverse = method_inverse[:-len(edit['suffixAfter'])] + edit['suffixBefore']
        else:
            if method_inverse.count(edit['after']) != edit['count']:
                raise ValueError('Original complete follow inverse changed: ' + edit['label'])
            method_inverse = method_inverse.replace(edit['after'], edit['before'], edit['count'])
    if method_inverse != original_method:
        raise ValueError('Complete original follow algorithm was not recovered')
    toggle = g['func'](raw_vm, 'toggleFollow', False)
    toggle = toggle.replace('currentlyFollowing: Boolean? = null)',
        'currentlyFollowing: Boolean? = null, presentation: DesktopOriginalVideoEngagementPresentation? = null)', 1)
    toggle = toggle.replace('changeFollow(mid, followOnly = false)', 'changeFollow(mid, followOnly = false, presentation = presentation)', 1)
    follow = g['func'](raw_vm, 'followOwner', False)
    follow = follow.replace('fun followOwner()', 'fun followOwner(presentation: DesktopOriginalVideoEngagementPresentation? = null)', 1)
    follow = follow.replace('changeFollow(mid = null, followOnly = true)',
        'changeFollow(mid = null, followOnly = true, presentation = presentation)', 1)
    old = g['func'](g['selected'](text, ['VideoEngagementViewModel']), 'toggleFollow', False)
    replace(old, toggle + '\n\n' + follow + '\n\n' + method,
        'Complete original toggle, follow-only and confirmation workflow')
    import_line = 'import com.android.purebilibili.feature.video.ui.feedback.canSubmitVideoFollowAction\n'
    if import_line not in text:
        package = 'package com.android.purebilibili.feature.video.viewmodel\n'
        replace(package, package + import_line, 'Original follow-only policy import')
    inverse = text
    for edit in reversed(edits):
        at = edit['offset']
        if inverse[at:at + len(edit['after'])] != edit['after']:
            raise ValueError('Owner follow whole-input inverse boundary changed')
        inverse = inverse[:at] + edit['before'] + inverse[at + len(edit['after']):]
    if inverse != original:
        raise ValueError('Previous complete Windows engagement VM was not retained')
    policy = g['selected'](sources['VideoActionFeedbackPolicy.kt'], ['canSubmitVideoFollowAction'])
    target = Path(g['OUTPUT']) / POLICY
    if any(row['path'] == POLICY for row in g['OUTPUTS']):
        raise ValueError('Follow-only policy has more than one producer')
    body = 'package com.android.purebilibili.feature.video.ui.feedback\n\n' + policy + '\n'
    g['write'](target, body)
    g['OUTPUTS'].append(dict(path=POLICY, origin='app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/VideoActionFeedbackPolicy.kt',
        fixedCommit=COMMIT, mode='complete-original-follow-only-policy', sha256LF=sha(body), generated=True))
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        originalMethodSHA256=sha(original_method), originalPolicySHA256=sha(policy),
        wholeOriginalChangeFollowSelected=True, allOriginalQueryRetryAndFollowOnlyBranchesRetained=True,
        wholeOriginalMethodInverseRecovered=True, projectedMethodSHA256=sha(method),
        wholePreviousInputInverseRecovered=True, inputSHA256=sha(original), outputSHA256=sha(text),
        edits=edits, methodAdaptations=method_edits, noIoInsideBeginAdmission=True,
        submittingCleanupRestrictedToCurrentDataSubject=True, cancelledBeforeStartCleanupImplemented=True,
        originalRetryAndTimeoutsRetained=True, capturedCallerContextRestoredAfterRetry=True,
        mainCompiled=False, testsExecuted=False, applicationExecuted=False)
    g['save'](Path(g['OUTPUT']) / 'v042-owner-follow-projection-proof.json', proof)
    return text, proof
