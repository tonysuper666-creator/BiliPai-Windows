"""v0.4.6 unfollow confirmation on the generated Windows follow buttons.

Upstream c4124a062327c168fbd36da53302277be6b41e44 (2026-10-17) added the shared
rememberFollowActionWithConfirmation and put it in front of eight follow callbacks. The file is carried whole from
the v046-follow-confirmation archive and emitted by the existing fullscreen pager producer with exactly one
hash-pinned adaptation: its AppAlertDialog import becomes an alias of the desktop-owned
DesktopWindowsFollowConfirmationDialog, which presents the same alert in the owned native player dialog while the Main
window shows (a plain in-canvas alert can sit under the heavyweight MPV canvas). The call sites are counted,
hash-pinned replacements on the generated Windows text of the producer that already owns each file; the vendored
originals and every other archive stay untouched.

Update (vid-info-section-repin): the owner row of the video detail is now emitted from the whole v0.4.6
VideoInfoSection.kt, which already contains this call, so that site is retired here: seven of the eight upstream call
sites are hunks of this module before the re-pin, six after it (UpPreviewSheet, PortraitFullscreenOverlay x2, the
creator team chip, the landscape end drawer and the space header), and the owner row is upstream text.

Audit of the six roadmap entries this slice covers (vid-engagement-state-safety), done / partial / deferred:
- v030-like-toggle-survives-detail-exit: done as a deliberate Windows deviation (v046_like_write_guard.py).
- v036-unknown-state-phone-audio-story-leftovers: partial. Done: the phone-width detail content forwards both known
  flags (v037_player_known_presentation.py). Deferred: the audio, story and input-bar unknown-state consumers.
- v036-merge-pr905-integration: bookkeeping only, nothing to compile.
- v038-interaction-status-retry: deferred. The retry helper already exists (v038-player-interaction-retry archive);
  the retry UI half and the Bangumi hunks are not taken here.
- v042-follow-pending-recovery: deferred. The PortraitVideoInfo onClickLabel/followStatusKnown hunk needs the
  parameter that the pinned base lacks, so it belongs to the whole-file pager re-pin.
- v046-unfollow-confirmation: done for seven of the eight upstream call sites plus every desktop-owned follow button.
  VideoPlayerComponents.UpInfoSection is not compiled: its vendored file is not in the source registry and the live
  detail button is VideoInfoSection.UpInfoSection. The SpaceScreen card clip reorder of the same commit is a different
  feature. The live-room follow toggle (LivePlayerScreen onFollowClick, LivePlayerViewModel.toggleFollow) is not
  guarded either, upstream v0.4.6 does not guard it, so it is a proposed follow-up slice rather than a hunk of c4124a06.
"""
from pathlib import Path
import hashlib
import json
import os
import re

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
INTRODUCED_BY = 'c4124a062327c168fbd36da53302277be6b41e44'
ARCHIVE = 'desktop/upstream-slices/v046-follow-confirmation'
MANIFEST_SHA256 = 'fcd52ecc839fea1eba25cc63459a6dfa5ff9ec9e28694ead05a5ef10067fb9f1'
LEAF = 'FollowActionConfirmation.kt'
LEAF_PATH = 'app/src/main/java/com/android/purebilibili/core/ui/components/FollowActionConfirmation.kt'
LEAF_BYTES = 2410
LEAF_SHA256 = '25b1fe5fb1305bd9c75d85a3bb4fb19c6b4bdffad0589f1d79746d2cd45033a3'
LEAF_BLOB = 'd97634cd76883a0b2cc1cb2b4326c8d2a45ed00c'
LICENSE_SHA256 = '3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986'
OUTPUT_RELATIVE = 'com/android/purebilibili/core/ui/components/FollowActionConfirmation.kt'
PROOF_DIRECTORY = 'v046-follow-confirmation-proofs'
FUNCTION = 'rememberFollowActionWithConfirmation'
IMPORT_LINE = 'import com.android.purebilibili.core.ui.components.' + FUNCTION + '\n'
OUTPUT_MODE = 'adapted-complete-original-v046-follow-confirmation'
# The one adaptation of the carried file: the alert goes through the desktop-owned owned-window adapter.
ADAPTER = 'DesktopWindowsFollowConfirmationDialog'
ADAPTER_PATH = 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsFollowConfirmationDialog.kt'
ALERT_IMPORT = 'import com.android.purebilibili.core.ui.AppAlertDialog\n'
ADAPTER_IMPORT = 'import com.bilipai.desktop.ui.' + ADAPTER + ' as AppAlertDialog\n'
EMITTED_BYTES = 2442
EMITTED_SHA256 = '5445ad6c7a9814594aa42953ee442fd1e84f297cba05cd89ab6d11222812b35c'

APP = 'app/src/main/java/com/android/purebilibili/'
# c4124a06 is the last upstream change to each of these files, so the v0.4.6 blob is the commit's own result.
UPSTREAM = {
    'UpPreviewSheet.kt': (APP + 'feature/video/ui/components/UpPreviewSheet.kt',
                          '79205b0c7dd6274519827b5bee1429642b6ff90e'),
    'PortraitFullscreenOverlay.kt': (APP + 'feature/video/ui/overlay/PortraitFullscreenOverlay.kt',
                                     '788e1059de76d9cbf7ba9997ca276187c84c1a8f'),
    'VideoInfoSection.kt': (APP + 'feature/video/ui/section/VideoInfoSection.kt',
                            '23108f5f17a2ba7a3bf89ecc400492441cb65666'),
    'VideoPlayerOverlay.kt': (APP + 'feature/video/ui/overlay/VideoPlayerOverlay.kt',
                              'f2255e332befc2437daa3c5ae1ed8a78ce04dbc3'),
    'SpaceScreen.kt': (APP + 'feature/space/SpaceScreen.kt',
                       '384e8345c35a264bae6f15631eea1153a4b8b98d'),
}

RECHECK_LABEL = '重新确认状态并关注'
OWNER_EDIT_TOAST = '可在「我的」页面编辑个人资料'

PKG = 'com/android/purebilibili/'
UP_PREVIEW = PKG + 'feature/video/ui/components/UpPreviewSheet.kt'
PORTRAIT = PKG + 'feature/video/ui/overlay/PortraitFullscreenOverlay.kt'
# The video detail owner row (DesktopOriginalVideoInfoSection.kt) is no longer a site of this module: since the
# v046-video-info-section re-pin (vid-info-section-repin) the emitted UpInfoSection is the v0.4.6 text, which already
# calls rememberFollowActionWithConfirmation. The creator team chip below still comes from the v0.2.5 file.
METADATA =PKG + 'feature/video/ui/section/DesktopOriginalVideoMetadata.kt'
OVERLAY = PKG + 'feature/video/ui/overlay/VideoPlayerOverlay.kt'
SPACE = PKG + 'feature/space/SpaceScreen.kt'

SITES = {
    UP_PREVIEW: [dict(
        label='UP preview header: unfollow asks first', upstream='UpPreviewSheet.kt',
        args=('owner.mid', 'owner.name', 'isFollowing'),
        before=('                    onClickLabel = if (followStatusKnown) null else "' + RECHECK_LABEL + '",\n'
                '                    onClick = onFollowClick,\n'),
        after=('                    onClickLabel = if (followStatusKnown) null else "' + RECHECK_LABEL + '",\n'
               '                    onClick = rememberFollowActionWithConfirmation(\n'
               '                        targetKey = owner.mid,\n'
               '                        userName = owner.name,\n'
               '                        isFollowing = isFollowing,\n'
               '                        onFollowClick = onFollowClick,\n'
               '                    ),\n'))],
    PORTRAIT: [dict(
        label='Portrait info row follow button: unfollow asks first', upstream='PortraitFullscreenOverlay.kt',
        args=('ownerMid', 'authorName', 'isFollowing'),
        before='                    .clickable { onFollowClick() }\n',
        after=('                    .clickable(\n'
               '                        onClick = rememberFollowActionWithConfirmation(\n'
               '                            targetKey = ownerMid,\n'
               '                            userName = authorName,\n'
               '                            isFollowing = isFollowing,\n'
               '                            onFollowClick = onFollowClick,\n'
               '                        ),\n'
               '                    )\n')), dict(
        label='Co-creator sheet follow button: unfollow asks first', upstream='PortraitFullscreenOverlay.kt',
        args=('member.mid', 'member.name', 'isFollowing'),
        before=('                        modifier = Modifier.clickable {\n'
                '                            scope.launch {\n'
                '                                val target = !isFollowing\n'
                '                                val ok = creatorTeam\n'
                '                                    .followUser(member.mid, target)\n'
                '                                    .getOrDefault(false)\n'
                '                                if (ok) followStates[member.mid] = target\n'
                '                            }\n'
                '                        }\n'),
        after=('                        modifier = Modifier.clickable(\n'
               '                            onClick = rememberFollowActionWithConfirmation(\n'
               '                                targetKey = member.mid,\n'
               '                                userName = member.name,\n'
               '                                isFollowing = isFollowing,\n'
               '                                onFollowClick = {\n'
               '                                    scope.launch {\n'
               '                                        val target = !isFollowing\n'
               '                                        val ok = creatorTeam\n'
               '                                            .followUser(member.mid, target)\n'
               '                                            .getOrDefault(false)\n'
               '                                        if (ok) followStates[member.mid] = target\n'
               '                                    }\n'
               '                                },\n'
               '                            ),\n'
               '                        )\n'))],
    METADATA: [dict(
        label='Creator team member chip: unfollow asks first', upstream='VideoInfoSection.kt',
        args=('member.mid', 'member.name', 'isFollowing'),
        before='                    .clickable(role = Role.Button, onClick = onFollowToggle),\n',
        after=('                    .clickable(\n'
               '                        role = Role.Button,\n'
               '                        onClick = rememberFollowActionWithConfirmation(\n'
               '                            targetKey = member.mid,\n'
               '                            userName = member.name,\n'
               '                            isFollowing = isFollowing,\n'
               '                            onFollowClick = onFollowToggle,\n'
               '                        ),\n'
               '                    ),\n'))],
    OVERLAY: [dict(
        label='Landscape end drawer follow button: unfollow asks first', upstream='VideoPlayerOverlay.kt',
        args=('currentBvid', 'ownerName', 'isFollowed'),
        before=('                                onClick = onToggleFollow,\n'
                '                                enabled = followStatusKnown,\n'),
        after=('                                onClick = rememberFollowActionWithConfirmation(\n'
               '                                    targetKey = currentBvid,\n'
               '                                    userName = ownerName,\n'
               '                                    isFollowing = isFollowed,\n'
               '                                    onFollowClick = onToggleFollow,\n'
               '                                ),\n'
               '                                enabled = followStatusKnown,\n'))],
    SPACE: [dict(
        label='Space header follow button: unfollow asks first', upstream='SpaceScreen.kt',
        args=('userInfo.mid', 'userInfo.name', 'userInfo.isFollowed && !isOwner'),
        before=('                        onFollowClick = {\n'
                '                            if (isOwner) {\n'
                '                                desktopSpacePlatform.feedback("' + OWNER_EDIT_TOAST + '")\n'
                '                            } else {\n'
                '                                onFollowClick()\n'
                '                            }\n'
                '                        },\n'),
        after=('                        onFollowClick = rememberFollowActionWithConfirmation(\n'
               '                            targetKey = userInfo.mid,\n'
               '                            userName = userInfo.name,\n'
               '                            isFollowing = userInfo.isFollowed && !isOwner,\n'
               '                            onFollowClick = {\n'
               '                                if (isOwner) {\n'
               '                                    desktopSpacePlatform.feedback("' + OWNER_EDIT_TOAST + '")\n'
               '                                } else {\n'
               '                                    onFollowClick()\n'
               '                                }\n'
               '                            },\n'
               '                        ),\n'))],
}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def wide(path):
    """Extended-length form on Windows, so a deep Gradle output path cannot reach MAX_PATH; unchanged elsewhere."""
    path = Path(path).absolute()
    if os.name != 'nt':
        return path
    text = str(path)
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(text if text.startswith(prefix) else prefix + text)


def fixed(repo):
    """Verified bytes of the carried upstream file. Raises when the archive differs from its pins in any way."""
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing v046 follow confirmation archive')
    need({p.name for p in root.iterdir()} == {LEAF, 'manifest.json', 'LICENSE'}, 'v046 follow confirmation leaves changed')
    raw = (root / 'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'v046 follow confirmation manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'v046 follow confirmation source identity changed')
    need(len(manifest['files']) == 1, 'v046 follow confirmation source set changed')
    row = manifest['files'][0]
    need((row['path'], row['filename'], row['bytes'], row['sha256'], row['gitBlob']) ==
         (LEAF_PATH, LEAF, LEAF_BYTES, LEAF_SHA256, LEAF_BLOB), 'v046 follow confirmation manifest row changed')
    leaf = root / LEAF
    need(leaf.is_file() and not leaf.is_symlink(), 'Linked v046 follow confirmation source')
    body = leaf.read_bytes()
    need(len(body) == LEAF_BYTES and sha(body) == LEAF_SHA256, 'v046 follow confirmation bytes changed')
    need(hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest() == LEAF_BLOB,
         'v046 follow confirmation Git blob changed')
    need(b'\r' not in body, 'v046 follow confirmation source is not LF only')
    licence = root / 'LICENSE'
    need(licence.is_file() and not licence.is_symlink() and sha(licence.read_bytes()) == LICENSE_SHA256 ==
         manifest['license']['sha256'], 'v046 follow confirmation license changed')
    return body


def validate_outputs(repo, *outputs):
    """Call first in every producer that hooks this slice: archive intact and no output inside or around it."""
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps v046 follow confirmation inputs')


def adapter_declared(repo):
    """The desktop-owned declaration the import alias points at; fails closed when it is missing or declared twice."""
    path = Path(repo) / ADAPTER_PATH
    need(path.is_file() and not path.is_symlink(), 'Windows follow confirmation adapter is missing')
    text = path.read_bytes().decode('utf-8')
    need(len(re.findall(r'(?m)^internal fun ' + ADAPTER + r'\(', text)) == 1,
         'Windows follow confirmation adapter declaration changed')
    need(len(re.findall(r'(?m)^package com\.bilipai\.desktop\.ui\r?$', text)) == 1,
         'Windows follow confirmation adapter package changed')


def adapt_confirmation(body):
    """The one counted, hash-pinned adaptation of the carried file. Returns (adapted bytes, edit record)."""
    text = body.decode('utf-8')
    need(text.count(ALERT_IMPORT) == 1, 'Follow confirmation alert import anchor changed')
    need(text.count('AppAlertDialog') == 2 and text.count('AppAlertDialog(') == 1,
         'Follow confirmation no longer has exactly one alert call')
    at = text.index(ALERT_IMPORT)
    adapted = text[:at] + ADAPTER_IMPORT + text[at + len(ALERT_IMPORT):]
    data = adapted.encode('utf-8')
    need(len(data) == EMITTED_BYTES and sha(data) == EMITTED_SHA256, 'Follow confirmation adapted bytes changed')
    need(adapted[at:at + len(ADAPTER_IMPORT)] == ADAPTER_IMPORT, 'Follow confirmation alias boundary changed')
    need(adapted[:at] + ALERT_IMPORT + adapted[at + len(ADAPTER_IMPORT):] == text, 'Follow confirmation alias is not recoverable')
    edit = dict(offset=at, before=ALERT_IMPORT, after=ADAPTER_IMPORT, count=1,
                label='The unfollow alert is presented through the Windows owned-window adapter')
    return data, edit


def emit_confirmation(repo, output):
    """Write the upstream file, with its one alias adaptation, under the producer output and return its inventory row."""
    body = fixed(repo)
    adapter_declared(repo)
    data, edit = adapt_confirmation(body)
    target = wide(Path(output) / OUTPUT_RELATIVE)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    proof = dict(upstreamCommit=COMMIT, introducedBy=INTRODUCED_BY, archive=ARCHIVE, sourceManifestSHA256=MANIFEST_SHA256,
                 sourcePath=LEAF_PATH, upstreamBlob=LEAF_BLOB, inputBytes=LEAF_BYTES, inputSHA256=LEAF_SHA256,
                 outputBytes=EMITTED_BYTES, outputSHA256=EMITTED_SHA256, wholeOriginalEmittedByteForByte=False,
                 adapter=ADAPTER, adapterPath=ADAPTER_PATH, edits=[edit], fullInputInverseRecovered=True,
                 emittedSHA256=sha(target.read_bytes()), mainCompiled=False, applicationExecuted=False)
    proof_path = wide(Path(output) / PROOF_DIRECTORY / (LEAF + '.json'))
    proof_path.parent.mkdir(parents=True, exist_ok=True)
    proof_path.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return dict(path=OUTPUT_RELATIVE, origin=LEAF_PATH, mode=OUTPUT_MODE, sha256LF=EMITTED_SHA256, generated=True)


def import_edit(text):
    """Counted import insertion in front of the file's first import, where upstream put it."""
    match = re.search(r'(?m)^import [^\n]+\n', text)
    need(match is not None and match.start() > 0, 'Follow confirmation import anchor missing')
    line = match.group(0)
    need(IMPORT_LINE not in text, 'Follow confirmation import is already present')
    return dict(label='Import the shared unfollow confirmation', before='\n' + line, after='\n' + IMPORT_LINE + line)


def site_edits(relative, text):
    """Counted before/after replacements for one generated output; [] when it has no follow button to guard."""
    sites = SITES.get(relative)
    if not sites:
        return []
    need(FUNCTION not in text, 'Follow confirmation is already applied or foreign: ' + relative)
    edits = [import_edit(text)] + [dict(label=site['label'], before=site['before'], after=site['after']) for site in sites]
    for edit in edits:
        need(text.count(edit['before']) == 1, 'Follow confirmation anchor changed: ' + edit['label'])
        need(edit['before'] != edit['after'], 'Follow confirmation edit is empty: ' + edit['label'])
    return edits


def apply_and_record(repo, relative, text, output):
    """Apply every pinned edit of `relative` once, prove the whole input is recoverable and write the receipt."""
    edits = site_edits(relative, text)
    if not edits:
        return text
    validate_outputs(repo, output)
    original = text
    applied = []
    for edit in edits:
        need(text.count(edit['before']) == 1, 'Follow confirmation anchor moved: ' + edit['label'])
        at = text.index(edit['before'])
        applied.append(dict(offset=at, before=edit['before'], after=edit['after'], count=1, label=edit['label']))
        text = text[:at] + edit['after'] + text[at + len(edit['before']):]
    need(text.count(FUNCTION + '(') == len(SITES[relative]), 'Follow confirmation call count changed: ' + relative)
    restored = text
    for edit in reversed(applied):
        at = edit['offset']
        need(restored[at:at + len(edit['after'])] == edit['after'], 'Follow confirmation inverse boundary changed')
        restored = restored[:at] + edit['before'] + restored[at + len(edit['after']):]
    need(restored == original, 'Follow confirmation input was not preserved')
    proof = dict(
        upstreamCommit=COMMIT, introducedBy=INTRODUCED_BY, archive=ARCHIVE, sourceManifestSHA256=MANIFEST_SHA256,
        output=relative,
        sites=[dict(label=site['label'], upstreamPath=UPSTREAM[site['upstream']][0],
                    upstreamBlob=UPSTREAM[site['upstream']][1], arguments=list(site['args']))
               for site in SITES[relative]],
        importLineSHA256=sha(IMPORT_LINE), inputSHA256=sha(original), outputSHA256=sha(text),
        fullInputInverseRecovered=True, edits=applied, mainCompiled=False, applicationExecuted=False)
    target = wide(Path(output) / PROOF_DIRECTORY / (Path(relative).name + '.json'))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return text
