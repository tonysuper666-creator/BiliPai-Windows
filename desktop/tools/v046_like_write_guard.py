"""Windows-owned: a like toggle that was already sent may finish after the detail owner retires.

Upstream 78de11a2b047c837c4512f4f615e489e3f8bdfc3 (0.3.0) wrapped the like request in a NonCancellable block so that
leaving the detail page did not cut an in-flight request and leave the server on the previous state. The 0.3.6 unknown
state rewrite a614d2eba6712456e1256fd0697ed5c51e98f067 replaced that function and the block did not come back; v0.4.6
(d6bf8b1655ae910999860bf13e65c8fcea5b345b) has no NonCancellable in the engagement view model. This is therefore a
deliberate Windows deviation from the pin, not an upstream hunk, and it stays one counted edit on the generated view
model: the request call goes through desktopCompleteAdmittedWrite, which is entered only while the owner is current,
lets the request finish when the caller is cancelled, and hands the outcome back only while the owner is still current
(see DesktopAdmittedWrite.kt). Every publication that follows already re-checks the owner.
"""
from pathlib import Path
import hashlib
import json

ADDED_BY = '78de11a2b047c837c4512f4f615e489e3f8bdfc3'
REMOVED_BY = 'a614d2eba6712456e1256fd0697ed5c51e98f067'
UPSTREAM_COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
UPSTREAM_PATH = 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt'
OUTPUT_RELATIVE = 'com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt'
PROOF_DIRECTORY = 'v046-like-write-guard-proofs'
GUARD = 'com.bilipai.desktop.ui.desktopCompleteAdmittedWrite'
# What 0.3.0 had at this spot and v0.4.6 does not (its block was inside likeToggleMutex.withLock).
ANDROID_030_BLOCK = ('                withContext(kotlinx.coroutines.NonCancellable) {\n'
                     '                    actions.toggleLike(targetAid, wasLiked, targetBvid)\n'
                     '                }\n')
CALL_BEFORE = ('                actions.toggleLike(targetAid, wasLiked, targetBvid)\n'
               '                    .onSuccess { liked ->\n')
CALL_AFTER = ('                ' + GUARD + '({ environment.assertOwned() }) {\n'
              '                    actions.toggleLike(targetAid, wasLiked, targetBvid)\n'
              '                }\n'
              '                    .onSuccess { liked ->\n')


def sha(text):
    return hashlib.sha256(text.encode('utf8')).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def apply_and_record(_repo, relative, text, output):
    """One counted replacement in the Windows engagement view model; every other output is returned unchanged."""
    if relative != OUTPUT_RELATIVE:
        return text
    need(GUARD not in text, 'Like write guard is already applied')
    need('NonCancellable' not in text, 'Engagement view model already carries a NonCancellable block')
    need(text.count(CALL_BEFORE) == 1, 'Like write guard anchor changed')
    need(text.count('actions.toggleLike(') == 1, 'Like write guard found another like request')
    at = text.index(CALL_BEFORE)
    result = text[:at] + CALL_AFTER + text[at + len(CALL_BEFORE):]
    need(result[at:at + len(CALL_AFTER)] == CALL_AFTER, 'Like write guard edit boundary changed')
    need(result[:at] + CALL_BEFORE + result[at + len(CALL_AFTER):] == text, 'Like write guard input was not preserved')
    proof = dict(
        deviationFromPin=True, upstreamCommit=UPSTREAM_COMMIT, upstreamPath=UPSTREAM_PATH,
        addedUpstreamBy=ADDED_BY, removedUpstreamBy=REMOVED_BY, upstreamBlockAt030SHA256=sha(ANDROID_030_BLOCK),
        guard=GUARD, offset=at, before=CALL_BEFORE, after=CALL_AFTER, count=1,
        inputSHA256=sha(text), outputSHA256=sha(result), fullInputInverseRecovered=True,
        ownerCheckedBeforeEntry=True, ownerCheckedBeforeOutcomeIsReturned=True,
        mainCompiled=False, applicationExecuted=False)
    target = Path(output) / PROOF_DIRECTORY / (Path(relative).name + '.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return result
