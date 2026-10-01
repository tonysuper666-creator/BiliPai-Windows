"""Adopt the unmodified stable chapter bar and wire the existing playback owner."""
from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;BASE=HERE.parents[2];REPO=BASE.parent/'BiliPai-v023'
def sha(raw):return hashlib.sha256(raw).hexdigest()
def lf(path):return Path(path).read_bytes().replace(b'\r\n',b'\n')
def text(path):return lf(REPO/path).decode('utf-8')
def write(path,value):return (REPO/path).write_text(value,encoding='utf-8',newline='\n')
registry_path='desktop/upstream-sources.json';registry=json.loads(text(registry_path))
adopted=[]
for name in ['ViewPointSegmentBar.kt','ViewPointSegmentBarPolicy.kt']:
    path='app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/'+name
    raw=subprocess.check_output(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+path],cwd=REPO)
    assert raw==lf(REPO/path),path
    assert not any(row['path']==path for row in registry['sources'])
    row=dict(path=path,sha256=sha(raw),features=['chapter-segment-bar','playback'],mode='direct')
    registry['sources'].append(row);adopted.append(row)
panels_path='desktop/src/main/kotlin/com/bilipai/desktop/ui/PlayerPanels.kt'
panels=text(panels_path);panel_base=sha(panels.encode())
anchor='    surfaceOnly: Boolean = false,\n'
assert panels.count(anchor)==1
panels=panels.replace(anchor,anchor+'    viewPoints: List<com.android.purebilibili.data.model.response.ViewPoint> = emptyList(),\n')
anchor='            }, dragging = seeking != null, onError = ::message)\n'
assert panels.count(anchor)==1
panels=panels.replace(anchor,anchor+'''        if (state.ready && state.error == null && state.durationSeconds.isFinite() && state.durationSeconds > 0) {
            com.android.purebilibili.feature.video.ui.overlay.ViewPointSegmentBar(
                viewPoints = viewPoints,
                durationMs = (state.durationSeconds * 1000.0).toLong(),
                currentPositionMs = ((seeking?.toDouble() ?: state.positionSeconds)
                    .takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0).times(1000.0).toLong(),
                onSeek = { seekTo(it / 1000.0) },
            )
        }
''')
shell_path='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
shell=text(shell_path);shell_base=sha(shell.encode())
anchor='    val playing by playback.state.collectAsState()\n'
assert shell.count(anchor)==1
shell=shell.replace(anchor,anchor+'''    val chapterIdentity = playing.details?.let { info ->
        info.pages.getOrNull(playing.currentPart)?.cid?.takeIf { it > 0 }?.let { info.bvid to it }
    }
    var chapterViewPoints by remember(chapterIdentity, sessionEpoch) {
        mutableStateOf<List<com.android.purebilibili.data.model.response.ViewPoint>>(emptyList())
    }
    LaunchedEffect(community, chapterIdentity, sessionEpoch) {
        val identity = chapterIdentity ?: return@LaunchedEffect
        try {
            val points = community.playerMetadata(identity.first, identity.second).viewPoints
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (repository.sessionEpoch == sessionEpoch) chapterViewPoints = points
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional player metadata must not interrupt playback. */ }
    }
''')
assert 'import kotlinx.coroutines.ensureActive' in shell or 'import kotlinx.coroutines.*' in shell
anchor='                            surfaceOnly = section == DesktopSection.STORY,\n'
assert shell.count(anchor)==1
shell=shell.replace(anchor,anchor+'''                            viewPoints = if (playback.currentCastSource(initialized.currentSourceVersion) != null)
                                chapterViewPoints else emptyList(),
''')
write(registry_path,json.dumps(registry,ensure_ascii=False,indent=2)+'\n')
write(panels_path,panels);write(shell_path,shell)
receipt=dict(schema='stable-chapter-bar-candidate-install-v1',targetCommit=registry['upstreamCommit'],
    adoptedUnmodified=adopted,registryCount=len(registry['sources']),
    platformAdapters=[dict(path=panels_path,baseSha256Lf=panel_base,sha256Lf=sha(panels.encode())),
        dict(path=shell_path,baseSha256Lf=shell_base,sha256Lf=sha(shell.encode()))],
    oneExistingRepository=True,existingPlaybackSeekOwner=True,chapterStateKeyedBy=['bvid','cid','sessionEpoch'],
    mainInstalled=False,wholeStableCompiled=False,stableRuntimeAccepted=False)
(HERE/'chapter-bar-install.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(directSources=len(adopted),registryCount=len(registry['sources']))))
