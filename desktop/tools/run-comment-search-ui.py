"""Manual hosted acceptance for the existing original Main/guest replay task.
No player, VM, account or GUI implementation is created by this runner.
"""
from pathlib import Path
import argparse, ctypes, hashlib, json, math, os, re, struct, subprocess, sys, tempfile, time, uuid
from ctypes import wintypes as W
sys.dont_write_bytecode = True

CAPTURES = [
    '178-comment-search-loading-cancel', '179-comment-search-retry-error',
    '180-comment-search-page-progress', '181-comment-search-all-hot',
    '182-comment-search-up-only', '183-comment-search-charged',
    '184-comment-search-all-latest', '185-comment-search-original-subreply',
]

CAPTURES_BY_CASE = {
    'search': CAPTURES,
    'composer': [
        '210-composer-text-draft', '211-composer-reopened-draft',
        '212-composer-original-emote', '213-composer-original-mention',
        '214-composer-owned-os-chooser', '215-composer-selected-private-image',
        '216-composer-restored-complete-draft', '217-composer-image-removed',
    ],
}

CAPTURES_BY_CASE['feedback'] = CAPTURES_BY_CASE['composer'] + [
    '220-feedback-client-baseline',
    '221-feedback-like-button-anchor',
    '222-feedback-owned-editor-hidden-carrier',
    '223-feedback-modal-dismissed',
    '224-feedback-owned-chooser-hidden-carrier',
    '225-feedback-selected-private-image',
    '226-feedback-minimize-restored',
    '227-feedback-video-fallback',
]

CAPTURES_BY_CASE['video_share'] = [
    '230-share-original-sheet', '231-share-dynamic-draft', '232-share-dynamic-cancelled',
    '233-share-dynamic-retry-error', '234-share-dynamic-success',
]

CAPTURES_BY_CASE['fullscreen'] = [
    '121-fullscreen-idle-hidden', '122-fullscreen-mouse-restored', '123-fullscreen-paused-hold',
]

def validate_ui_case(ui_case):
    if ui_case not in CAPTURES_BY_CASE: raise ValueError('Unknown independent comment UI case')
    return ui_case

def validate_runtime(runtime, ui_case):
    validate_ui_case(ui_case)
    if (runtime.get('schema') != 1 or runtime.get('task') != 'windowsVideoLocalReplayUiSmoke' or
        runtime.get('mainClass') != 'com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture' or
        runtime.get('commentUiCase') != ui_case or
        not isinstance(runtime.get('classpath'), list) or not runtime['classpath'] or
        not all(isinstance(item, str) and item.strip() for item in runtime['classpath']) or
        not isinstance(runtime.get('javaExecutable'), str) or not runtime['javaExecutable'].strip() or
        not isinstance(runtime.get('applicationResources'), str) or not runtime['applicationResources'].strip()):
        raise ValueError('Prepared original Main runtime/case mismatch')

def sha(data): return hashlib.sha256(data).hexdigest()
def read(path): return path.read_bytes()
def load(path): return json.loads(read(path))
def save(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2); stream.write('\n')
def no_links(path):
    path = Path(os.path.abspath(path)); current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        if not current.exists() or current.is_symlink() or current.is_junction():
            raise ValueError('Missing or linked owned path')
    return path
def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.STDOUT)
def sources(repo):
    paths = sorted(git(repo, 'ls-files', '-z').decode().split('\0'))
    return [(name, sha(read(no_links(repo / name)))) for name in paths if name]
def compiled_inputs(runtime):
    inputs = []
    for entry in [*runtime['classpath'], runtime['applicationResources']]:
        path = Path(entry)
        if not path.exists():
            # Java classpaths may contain an absent empty output directory.
            if path.suffix.lower() == '.jar': raise ValueError('Prepared runtime JAR missing')
            inputs.append((str(path), None)); continue
        path = no_links(path)
        if path.is_file(): inputs.append((str(path), sha(read(path))))
        else:
            for child in sorted(path.rglob('*')):
                child = no_links(child)
                if child.is_file(): inputs.append((str(child), sha(read(child))))
    return inputs

def windows_environment_value(env, name):
    values = {value for key, value in env.items() if key.casefold() == name.casefold()}
    if len(values) != 1 or not next(iter(values)):
        raise ValueError('Missing or ambiguous Windows environment key: ' + name)
    return next(iter(values))

def run_owned(command, cwd, env, log, timeout_seconds, state):
    """Suspend -> own private Job -> resume. No PID search or global kill.
    --no-daemon prevents an existing, job-external daemon from launching Main.
    Closing the exact Job kills only our own descendants after any failure.
    """
    k = ctypes.WinDLL('kernel32', use_last_error=True)
    SIZE = ctypes.c_size_t
    class LIMITS(ctypes.Structure):
        _fields_ = [('user', ctypes.c_int64), ('job', ctypes.c_int64), ('flags', W.DWORD),
                    ('minWorking', SIZE), ('maxWorking', SIZE), ('activeLimit', W.DWORD),
                    ('affinity', SIZE), ('priority', W.DWORD), ('scheduling', W.DWORD)]
    class IO(ctypes.Structure):
        _fields_ = [(name, ctypes.c_ulonglong) for name in ('readOps','writeOps','otherOps','readBytes','writeBytes','otherBytes')]
    class EXTENDED(ctypes.Structure):
        _fields_ = [('limits', LIMITS), ('io', IO), ('processMemory', SIZE), ('jobMemory', SIZE), ('peakProcess', SIZE), ('peakJob', SIZE)]
    class ACCOUNTING(ctypes.Structure):
        _fields_ = [('user', ctypes.c_int64), ('kernel', ctypes.c_int64), ('periodUser', ctypes.c_int64),
                    ('periodKernel', ctypes.c_int64), ('pageFaults', W.DWORD), ('total', W.DWORD),
                    ('active', W.DWORD), ('terminated', W.DWORD)]
    class STARTUP(ctypes.Structure):
        _fields_ = [('cb', W.DWORD), ('reserved', W.LPWSTR), ('desktop', W.LPWSTR), ('title', W.LPWSTR),
                    ('x', W.DWORD), ('y', W.DWORD), ('width', W.DWORD), ('height', W.DWORD),
                    ('charsX', W.DWORD), ('charsY', W.DWORD), ('fill', W.DWORD), ('flags', W.DWORD),
                    ('show', W.WORD), ('reservedBytes', W.WORD), ('reserved2', ctypes.POINTER(ctypes.c_byte)),
                    ('stdin', W.HANDLE), ('stdout', W.HANDLE), ('stderr', W.HANDLE)]
    class PROCESS(ctypes.Structure):
        _fields_ = [('process', W.HANDLE), ('thread', W.HANDLE), ('pid', W.DWORD), ('tid', W.DWORD)]
    signatures = {
        'CreateJobObjectW': ([ctypes.c_void_p, W.LPCWSTR], W.HANDLE),
        'SetInformationJobObject': ([W.HANDLE, ctypes.c_int, ctypes.c_void_p, W.DWORD], W.BOOL),
        'QueryInformationJobObject': ([W.HANDLE, ctypes.c_int, ctypes.c_void_p, W.DWORD, ctypes.c_void_p], W.BOOL),
        'SetHandleInformation': ([W.HANDLE, W.DWORD, W.DWORD], W.BOOL),
        'CreateProcessW': ([W.LPCWSTR, W.LPWSTR, ctypes.c_void_p, ctypes.c_void_p, W.BOOL, W.DWORD,
                            ctypes.c_void_p, W.LPCWSTR, ctypes.POINTER(STARTUP), ctypes.POINTER(PROCESS)], W.BOOL),
        'AssignProcessToJobObject': ([W.HANDLE, W.HANDLE], W.BOOL),
        'ResumeThread': ([W.HANDLE], W.DWORD), 'WaitForSingleObject': ([W.HANDLE, W.DWORD], W.DWORD),
        'GetExitCodeProcess': ([W.HANDLE, ctypes.POINTER(W.DWORD)], W.BOOL),
        'TerminateProcess': ([W.HANDLE, W.UINT], W.BOOL),
        'TerminateJobObject': ([W.HANDLE, W.UINT], W.BOOL), 'CloseHandle': ([W.HANDLE], W.BOOL),
    }
    for name, (args, result) in signatures.items():
        fn = getattr(k, name); fn.argtypes = args; fn.restype = result
    def require(ok):
        if not ok: raise ctypes.WinError(ctypes.get_last_error())
    if any(re.search(r'[\x00-\x1f"&|<>^%!]', value) for value in command):
        raise ValueError('Unsafe owned command argument')
    executable = no_links(Path(windows_environment_value(env, 'SystemRoot')) / 'System32' / 'cmd.exe')
    inner = ' '.join('"' + value + '"' for value in command)
    line = ctypes.create_unicode_buffer('"' + str(executable) + '" /d /s /c "' + inner + '"')
    block = ctypes.create_unicode_buffer('\0'.join(name + '=' + value for name, value in sorted(env.items())) + '\0\0')
    job = k.CreateJobObjectW(None, None); require(job)
    process = PROCESS(); assigned = False; resumed = False; forced = False; timed_out = False
    limits = EXTENDED(); limits.limits.flags = 0x2000  # KILL_ON_JOB_CLOSE; no breakaway permissions.
    started = time.monotonic()
    state.update(exitCode=None, forcedCleanup=False, timedOut=False, cleanupCompleted=False,
                 childStartedSuspended=False, assignedBeforeResume=False, noDaemon=True)
    try:
        require(k.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
        import msvcrt
        with log.open('xb') as output, open(os.devnull, 'rb') as stdin:
            out_handle, in_handle = msvcrt.get_osfhandle(output.fileno()), msvcrt.get_osfhandle(stdin.fileno())
            require(k.SetHandleInformation(out_handle, 1, 1)); require(k.SetHandleInformation(in_handle, 1, 1))
            startup = STARTUP(); startup.cb = ctypes.sizeof(startup); startup.flags = 0x101
            startup.show = 0; startup.stdin = in_handle; startup.stdout = startup.stderr = out_handle
            # Only the cmd console is hidden; the actual original Main GUI is unchanged.
            require(k.CreateProcessW(str(executable), line, None, None, True, 0x4 | 0x400,
                                     block, str(cwd), ctypes.byref(startup), ctypes.byref(process)))
            state['childStartedSuspended'] = True
            state['directChildPid'] = process.pid
            require(k.AssignProcessToJobObject(job, process.process)); assigned = True
            require(k.ResumeThread(process.thread) != 0xffffffff); resumed = True
            state['assignedBeforeResume'] = True
            wait = k.WaitForSingleObject(process.process, timeout_seconds * 1000)
            if wait == 258:
                timed_out = True; forced = True; require(k.TerminateJobObject(job, 92))
                require(k.WaitForSingleObject(process.process, 10000) == 0)
            elif wait != 0: raise RuntimeError('Owned process wait failed')
            exit_code = W.DWORD(); require(k.GetExitCodeProcess(process.process, ctypes.byref(exit_code)))
            deadline = time.monotonic() + 5
            while True:
                count = ACCOUNTING(); require(k.QueryInformationJobObject(job, 1, ctypes.byref(count), ctypes.sizeof(count), None))
                if count.active == 0: break
                if time.monotonic() >= deadline:
                    forced = True; require(k.TerminateJobObject(job, 93)); break
                time.sleep(.05)
            if forced:
                deadline = time.monotonic() + 5
                while True:
                    count = ACCOUNTING(); require(k.QueryInformationJobObject(job, 1, ctypes.byref(count), ctypes.sizeof(count), None))
                    if count.active == 0: break
                    if time.monotonic() >= deadline: raise RuntimeError('Owned Job cleanup did not finish')
                    time.sleep(.05)
            state.update(exitCode=exit_code.value, ownedJobActiveProcesses=0, cleanupCompleted=True)
            return state
    finally:
        cleanup_errors = []
        # Each exact-handle cleanup step is attempted independently. Do not
        # replace the original task error with a later cleanup/query failure.
        def cleanup(action):
            try: action()
            except Exception as error: cleanup_errors.append(type(error).__name__ + ': ' + str(error))
        def quiesce():
            nonlocal forced
            if process.process and not assigned:
                forced = True; require(k.TerminateProcess(process.process, 94))
                require(k.WaitForSingleObject(process.process, 10000) == 0)
                state['ownedJobActiveProcesses'] = 0
            elif assigned:
                count = ACCOUNTING()
                require(k.QueryInformationJobObject(job, 1, ctypes.byref(count), ctypes.sizeof(count), None))
                if count.active:
                    forced = True; require(k.TerminateJobObject(job, 94))
                    deadline = time.monotonic() + 10
                    while count.active:
                        if time.monotonic() >= deadline: raise RuntimeError('Exact owned Job did not quiesce')
                        time.sleep(.05)
                        require(k.QueryInformationJobObject(job, 1, ctypes.byref(count), ctypes.sizeof(count), None))
                state['ownedJobActiveProcesses'] = count.active
            else: state['ownedJobActiveProcesses'] = 0
            state['cleanupCompleted'] = True
        cleanup(quiesce)
        if process.thread: cleanup(lambda: require(k.CloseHandle(process.thread)))
        if process.process: cleanup(lambda: require(k.CloseHandle(process.process)))
        # The anonymous Job has no breakaway permission. Its final close still
        # kills only this tree if a previous bounded cleanup step failed.
        cleanup(lambda: require(k.CloseHandle(job)))
        if cleanup_errors: state['cleanupCompleted'] = False
        state.update(forcedCleanup=forced, timedOut=timed_out, cleanupErrors=cleanup_errors,
                     elapsedSeconds=round(time.monotonic() - started, 3))

def is_blocked_composer_heartbeat_observation(request):
    """Replay logs before its composer read-only guard; this admits no response."""
    return (request == dict(stage='requestObserved', scheme='https', host='api.bilibili.com',
        port=443, path='/x/click-interface/web/heartbeat', method='POST', hasQuery=False, hasFragment=False)
        and type(request.get('port')) is int and request.get('hasQuery') is False and request.get('hasFragment') is False)


def verify_feedback_post_records(requests):
    """Five original actions each log an observation then their owned memory response."""
    if len(requests) != 10: raise ValueError('Expected exactly five observed and five fulfilled original Like requests')
    for observed, fulfilled in zip(requests[::2], requests[1::2]):
        if not (observed == dict(stage='requestObserved', scheme='https', host='api.bilibili.com', port=443,
                path='/x/web-interface/archive/like', method='POST', hasQuery=False, hasFragment=False)
                and type(observed.get('port')) is int and observed.get('hasQuery') is False and observed.get('hasFragment') is False):
            raise ValueError('Original Like observation origin, shape or order differs')
        if not (fulfilled == dict(path='/x/web-interface/archive/like', method='POST', host='api.bilibili.com',
                originalLikeProtocolMemoryOnly=True, remoteMutationSent=False)
                and fulfilled.get('originalLikeProtocolMemoryOnly') is True and fulfilled.get('remoteMutationSent') is False):
            raise ValueError('Original Like fulfillment did not terminate in memory after its observation')


def verify_video_share(observations, by, transport, payload_receipt):
    """Fourth explicit case: original protocol and source receipt; no remote success claim."""
    def need(condition, message):
        if not condition: raise ValueError('Video share: ' + message)
    for key in ('videoDynamicShareProofRequested', 'videoDynamicShareProofCompleted',
                'videoDynamicSharePhysicalFramesRequireHumanReview', 'syntheticAccountSeededThroughActualSessionStore'):
        need(observations.get(key) is True, 'missing literal assertion ' + key)
    for key in ('composerInputProofRequested', 'composerInputProofCompleted', 'commentSearchProofRequested',
                'commentSearchInputProofCompleted', 'commentSearchReadResponsesAreSynthetic',
                'commentSearchPhysicalTextHumanReviewRequired', 'commentPublishingAccepted', 'imageUploadAccepted',
                'loginUiAccepted', 'commentsSent', 'physicalStackWrittenByFixture', 'directPhysicalStackListMutation',
                'newNativeActorCreatedByFixture', 'newRootCreatedByFixture', 'nvidiaUiProofRequested', 'nvidiaUiProofCompleted',
                'interactionProofRequested', 'interactionProofCompleted', 'featureInputProofCompleted',
                'hotInputProofRequested', 'hotInputProofCompleted', 'collectionInputProofRequested', 'collectionInputProofCompleted',
                'videoMetadataProofCompleted', 'bgmInputProofRequested', 'bgmInputProofCompleted',
                'pipInputProofRequested', 'pipInputProofCompleted', 'originalInteractionProofRequested', 'originalInteractionProofCompleted'):
        need(observations.get(key) is False, 'mixed acceptance ' + key)
    need('composer-original-input-closed-without-publish' not in by, 'comment proof leaked into video share')
    session = by['composer-synthetic-session-actual-root-generation']
    for key in ('sameActualRepository', 'originalGuestEntryAndRoutesRetired', 'actualRetainedHomeGenerationChanged', 'sameNativeMainWindow'):
        need(session.get(key) is True, 'missing real session transition ' + key)
    epoch = session.get('actualAccountEpoch')
    need(type(epoch) is int and epoch > 0 and session.get('syntheticPrimaryMid') == 990000024 and
         session.get('loginUiAccepted') is False and type(session.get('sameWindowLevelRootHandle')) is bool,
         'synthetic session identity differs')
    proof = by['video-share-original-dynamic-completed']
    for key in ('sameActualEngagementDomain', 'sameAcceptedPublicationIdentity', 'samePausedNativeSourceAndPreferences',
                'actualOriginalSheetAndDynamicDialog', 'cancelProducedZeroPosts', 'originalFailureDraftAndErrorRetained',
                'manualRetryCompletedOriginalProtocol', 'sameSourceHiddenRestoreObserved',
                'openDraftSamePeerHiddenRestore', 'openDraftTextPreserved',
                'currentSourceConfirmedShareReceiptObserved', 'exactOwnedPeersDisposed', 'physicalFramesRequireHumanReview'):
        need(proof.get(key) is True, 'missing current original UI assertion ' + key)
    for key in ('confirmedShareInstanceId', 'confirmedShareSourceVersion'):
        need(type(proof.get(key)) is int and proof[key] > 0, 'invalid confirmed receipt identity')
    need(proof.get('inputMechanism') == 'OS_ROBOT' and proof.get('actualAccountEpoch') == epoch and
         proof.get('syntheticPrimaryMid') == 990000024 and proof.get('remoteMutationSent') is False and
         proof.get('realCredentialsUsed') is False, 'input/session or remote scope differs')
    need(transport.get('videoDynamicShareInput') is True and transport.get('sameActualRepository') is True and
         transport.get('composerInputResponsesAreSynthetic') is True and transport.get('commentSearch') is None,
         'wrong replay case/repository')
    for key in ('realAccountUsed', 'realBilibiliDataAccepted', 'newRootCreated', 'newPlayerCreated', 'newControllerCreated',
                'originalVmStateWritten', 'actualNativeStateWritten', 'physicalStackWritten', 'commentSearchResponsesAreSynthetic',
                'commentsSent', 'creatorFollowMutationSubmitted', 'bgmAccountMutationSubmitted',
                'originalInteractionRemoteMutationSubmitted', 'collectionSubscriptionMutationSubmitted'):
        need(transport.get(key) is False, 'unexpected replay authority ' + key)
    auth = transport['composerInput']
    need(auth.get('syntheticSessionSeededThroughActualStore') is True and auth.get('syntheticPrimaryMid') == 990000024 and
         auth.get('syntheticAccountEpoch') == epoch and auth.get('loginUiAccepted') is False and
         auth.get('realAccountUsed') is False and auth.get('mutationRequestsPermitted') is False,
         'reused actual synthetic Store authentication differs')
    detail = transport['videoDynamicShare']
    need(type(detail) is dict and detail == payload_receipt and type(detail.get('schema')) is int and detail['schema'] == 1,
         'separate payload receipt differs from actual transport')
    for key in ('actualOriginalVideoDynamicProtocolConsumed', 'syntheticResponsesOnly'):
        need(detail.get(key) is True, 'missing original protocol ' + key)
    for key in ('remoteMutationSent', 'otherMutationPermitted', 'realCredentialsUsed'):
        need(detail.get(key) is False, 'unexpected protocol scope ' + key)
    aid = detail.get('expectedAid')
    need(type(aid) is int and aid == 170001, 'wrong fixture video aid')
    payloads = detail.get('payloads')
    need(type(payloads) is list and len(payloads) == 2, 'exactly failure then manual retry required')
    for item, code in zip(payloads, (-1, 0)):
        need(item.get('method') == 'POST' and item.get('host') == 'api.bilibili.com' and
             item.get('path') == '/x/dynamic/feed/create/dyn' and type(item.get('scene')) is int and item['scene'] == 5 and
             type(item.get('dynType')) is int and item['dynType'] == 8 and type(item.get('rid')) is int and item['rid'] == aid and
             item.get('text') == 'LOCAL video share draft' and item.get('csrfIsSynthetic') is True and
             type(item.get('responseCode')) is int and item['responseCode'] == code and
             item.get('terminatedInMemory') is True and item.get('remoteMutationSent') is False and
             isinstance(item.get('payloadSha256'), str) and re.fullmatch('[0-9a-f]{64}', item['payloadSha256']),
             'original payload/body digest/ordered synthetic response differs')
    mutations = [item for item in transport['apiRequests'] if item.get('path') == '/x/dynamic/feed/create/dyn']
    observed = [item for item in mutations if item.get('stage') == 'requestObserved']
    fulfilled = [item for item in mutations if item.get('stage') == 'memoryResponse']
    need(len(mutations) == 4 and len(observed) == 2 and len(fulfilled) == 2 and
         all(item.get('host') == 'api.bilibili.com' and item.get('method') == 'POST' for item in mutations),
         'exactly two observed and two fulfilled original POSTs are required')
    need(all(item.get('scheme') == 'https' and type(item.get('port')) is int and item['port'] == 443 and
             item.get('hasQuery') is True and item.get('hasFragment') is False for item in observed),
         'observed original POST origin differs')
    need(all(item.get('originalVideoDynamicProtocolMemoryOnly') is True and item.get('remoteMutationSent') is False
             for item in fulfilled), 'actual response did not terminate in memory')
    forbidden = {'/x/relation/modify', '/x/web-interface/archive/like', '/x/v2/reply/add', '/x/v2/reply/action',
                 '/x/v2/reply/hate', '/x/v2/reply/del', '/x/v2/reply/report', '/x/dynamic/feed/create/dyn/submit', '/x/v3/fav/resource/deal'}
    need(all(item.get('method') in ('GET', 'POST') and item.get('path') not in forbidden for item in transport['apiRequests']),
         'another mutation was observed')

def verify_fullscreen(receipt, by, transport):
    """Existing Main/Canvas assertions and bounded files; physical pixels require human review."""
    def need(ok, message):
        if not ok: raise ValueError('Fullscreen: ' + message)
    def number(value): return type(value) in (int, float) and math.isfinite(value)
    need(receipt.get('ordinaryFullscreenResizeRegressionExecuted') is True, 'original ordinary baseline did not execute')
    for key in ('composerInputProofRequested', 'composerInputProofCompleted', 'syntheticAccountSeededThroughActualSessionStore',
                'commentSearchProofRequested', 'commentSearchInputProofCompleted', 'commentSearchReadResponsesAreSynthetic',
                'commentSearchPhysicalTextHumanReviewRequired', 'commentPublishingAccepted', 'imageUploadAccepted', 'loginUiAccepted',
                'nvidiaUiProofRequested', 'nvidiaUiProofCompleted', 'interactionProofRequested', 'interactionProofCompleted',
                'featureInputProofCompleted', 'hotInputProofRequested', 'hotInputProofCompleted', 'collectionInputProofRequested',
                'collectionInputProofCompleted', 'videoMetadataProofCompleted', 'bgmInputProofRequested', 'bgmInputProofCompleted',
                'pipInputProofRequested', 'pipInputProofCompleted', 'originalInteractionProofRequested', 'originalInteractionProofCompleted',
                'physicalStackWrittenByFixture', 'directPhysicalStackListMutation', 'newNativeActorCreatedByFixture', 'newRootCreatedByFixture'):
        need(receipt.get(key) is False, 'mixed acceptance ' + key)
    for key in ('sameActualRepository', 'qualityMetadataIsSynthetic', 'codecMetadataIsSynthetic'):
        need(transport.get(key) is True, 'missing actual guest replay ' + key)
    for key in ('realAccountUsed', 'realBilibiliDataAccepted', 'newRootCreated', 'newPlayerCreated', 'newControllerCreated',
                'originalVmStateWritten', 'actualNativeStateWritten', 'physicalStackWritten', 'commentSearchResponsesAreSynthetic',
                'composerInputResponsesAreSynthetic', 'chapterMetadataIsSynthetic', 'collectionMetadataIsSynthetic',
                'videoMetadataIsSynthetic', 'bgmMetadataIsSynthetic', 'bgmDetailAndRecommendResponsesAreSynthetic',
                'singleBgmDetailOnlyScope', 'originalInteractionMetadataIsSynthetic', 'commentsSent', 'creatorFollowMutationSubmitted',
                'bgmAccountMutationSubmitted', 'originalInteractionRemoteMutationSubmitted', 'collectionSubscriptionMutationSubmitted',
                'realDASHCodecAccepted'):
        need(transport.get(key) is False, 'unexpected replay authority ' + key)
    need(transport.get('commentSearch') is None and transport.get('composerInput') is None,
         'comment protocol or synthetic login mixed into fullscreen')
    need(transport.get('container') == 'MJPEG_AVI_PLUS_PCM_WAV', 'different fixture media')
    requests = transport.get('apiRequests'); media = transport.get('loopbackRequests')
    need(type(requests) is list and type(media) is list, 'missing request observations')
    need(any(item.get('path') == '/x/web-interface/view' for item in requests) and
         any(item.get('path') in ('/x/player/wbi/playurl', '/x/player/playurl') for item in requests),
         'original metadata/playurl was not consumed')
    need(all(any(item.get('file') == name and item.get('method') == 'GET' for item in media)
             for name in ('video.avi', 'audio.wav')), 'same owned loopback video/audio was not read')
    baseline_ids = ['110-ordinary-playing', '120-fullscreen-playing', '130-fullscreen-exit-playing',
                    '140-resized-playing', '150-restored-playing']
    need(all(id in by for id in baseline_ids + CAPTURES_BY_CASE['fullscreen'] + ['comment-search-bounded-main']),
         'original bounded Main/fullscreen/resize observation is missing')
    bounded = by['comment-search-bounded-main']
    need(bounded.get('scope') == 'ONLY_ACTUAL_AVAILABLE_RUNNER_VIEWPORT' and bounded.get('fourKTested') is False and
         bounded.get('fullscreenResizeRegressionExecuted') is False and bounded.get('appScaleChangedByFixture') is False and
         all(type(bounded.get(key)) is int for key in ('x', 'y', 'width', 'height')) and
         bounded['width'] > 640 and bounded['height'] > 480, 'initial actual monitor setup/scale scope differs')
    baseline = by['110-ordinary-playing']; version = baseline.get('sameAcceptedSourceVersion')
    need(type(version) is int and version > 0 and baseline.get('fullImmutableSourceStillOwned') is True,
         'missing initial full-source identity')
    need(number(baseline.get('clockBefore')) and number(baseline.get('clockAfter')) and
         baseline['clockAfter'] > baseline['clockBefore'] + .5, 'initial native clock did not advance')
    need(baseline.get('actualNativeScreenshot') == '110-ordinary-playing-native.png' and
         type(baseline.get('sampledNativeColourCount')) is int and baseline['sampledNativeColourCount'] > 1 and
         type(baseline.get('nativeScreenshotWidth')) is int and baseline['nativeScreenshotWidth'] > 0 and
         type(baseline.get('nativeScreenshotHeight')) is int and baseline['nativeScreenshotHeight'] > 0,
         'initial source-bound native decode evidence missing')
    for id in baseline_ids + CAPTURES_BY_CASE['fullscreen']:
        row = by[id]; state = row.get('nativeState')
        need(row.get('sameAcceptedSourceVersion') == version and type(row.get('sameAcceptedSourceVersion')) is int and
             row.get('fullImmutableSourceStillOwned') is True, 'full-source retirement or version change')
        need(type(state) is dict and type(state.get('sourceVersion')) is int and state['sourceVersion'] == version,
             'native state source differs')
        need(state.get('ready') is True and state.get('loading') is False and state.get('ended') is False and
             state.get('firstVideoFrameReady') is True and state.get('hasError') is False and
             state.get('volume') == 0.0 and state.get('muted') is True and
             number(state.get('positionSeconds')) and state['positionSeconds'] >= 0,
             'native readiness/clock/private mute changed')
        need(state.get('nativePaused') is (id == '123-fullscreen-paused-hold'), 'native pause ACK differs')
        if id in CAPTURES_BY_CASE['fullscreen']:
            need(row.get('sameActualCanvasRetained') is True and row.get('physicalVideoPixelsIndependentlyChecked') is False and
                 row.get('physicalScreenHumanReviewRequired') is True and
                 all(row.get(key) is True for key in ('captureStateHeldAcrossRead', 'nativeCanvasBoundsMatched', 'physicalCanvasInputDelivered')),
                 'Canvas capture continuity, native geometry, physical input or review scope differs')
    for id in baseline_ids:
        row = by[id]
        need(number(row.get('clockBefore')) and number(row.get('clockAfter')) and row['clockAfter'] > row['clockBefore'] + .5 and
             row.get('windowPlacement') == ('Fullscreen' if id == '120-fullscreen-playing' else 'Floating'),
             'original ordinary fullscreen/resize clock or placement differs')
    hidden = by['121-fullscreen-idle-hidden']; restored = by['122-fullscreen-mouse-restored']; paused = by['123-fullscreen-paused-hold']
    need(type(hidden.get('idleMillis')) is int and hidden['idleMillis'] >= 4000 and
         number(hidden.get('clockBefore')) and number(hidden.get('clockAfter')) and hidden['clockAfter'] > hidden['clockBefore'] + .5 and
         type(hidden.get('shownCanvasHeight')) is int and hidden['shownCanvasHeight'] > 0 and
         type(hidden.get('hiddenCanvasHeight')) is int and hidden['hiddenCanvasHeight'] > hidden['shownCanvasHeight'] and
         hidden.get('topAndBottomControlsHidden') is True, 'idle time/playing clock/real layout hide missing')
    need(restored.get('inputMechanism') == 'OS_ROBOT_MOUSE_MOVE' and restored.get('topAndBottomControlsRestored') is True,
         'actual owned-Canvas input did not restore chrome')
    need(paused.get('nativePauseAcknowledged') is True and paused.get('controlsStayedVisible') is True and
         paused.get('menuHoldExecuted') is False, 'paused hold or unexecuted menu scope differs')


def verify(report, local, health, token, process, ui_case='search'):
    validate_ui_case(ui_case)
    if process['exitCode'] != 0 or process['forcedCleanup'] or not process['cleanupCompleted']:
        raise ValueError('Original UI task did not finish naturally')
    if read(no_links(health)).decode() != token or not read(no_links(health.with_name('startup-version.txt'))).decode().strip():
        raise ValueError('Actual Main startup identity missing')
    receipt = load(no_links(report / 'observations.json'))
    for key in ('allPreExitAssertionsPassed','sameLiveRootAndWindow','apiReplayInjected','loopbackMediaInjected'):
        if receipt.get(key) is not True: raise ValueError('Missing actual Main assertion: ' + key)
    if receipt.get('actualMainInvocations') != 1 or receipt.get('defaultRenderer') != 'DIRECT3D':
        raise ValueError('Unexpected Main invocation/default renderer policy marker')
    for key in ('realAccountUsed','guestRealApi','commentSearchFourKTested'):
        if receipt.get(key) is not False: raise ValueError('Unexpected acceptance scope: ' + key)
    if receipt.get('ordinaryFullscreenResizeRegressionExecuted') is not (ui_case == 'fullscreen'):
        raise ValueError('Unexpected ordinary fullscreen/resize scope')
    rows = receipt['observations']; ids = [row['id'] for row in rows]
    if len(ids) != len(set(ids)) or len({row['actualWindowIdentity'] for row in rows}) != 1:
        raise ValueError('Root/window observations changed identity')
    if not all(row['sameRootAndRouteAssembly'] is True for row in rows): raise ValueError('Root source retired')
    by = {row['id']: row for row in rows}
    if by['160-original-back-home'].get('physicalStack') != ['MainHost']: raise ValueError('Actual Back did not return Home')
    transport = load(no_links(report / 'local-replay-receipt.json'))
    video_share = ui_case == 'video_share'
    for key in ('videoDynamicShareProofRequested','videoDynamicShareProofCompleted','videoDynamicSharePhysicalFramesRequireHumanReview'):
        if receipt.get(key, False) is not video_share: raise ValueError('Unexpected video share mode: ' + key)
    if transport.get('videoDynamicShareInput', False) is not video_share or (not video_share and transport.get('videoDynamicShare') is not None):
        raise ValueError('Mixed video dynamic share replay mode')
    feedback = ui_case == 'feedback'
    for key in ('brandFeedbackPlacementProofRequested','brandFeedbackPlacementProofCompleted',
                'brandFeedbackPhysicalFramesRequireHumanReview'):
        if receipt.get(key) is not feedback: raise ValueError('Unexpected brand feedback mode: ' + key)
    if transport.get('brandFeedbackPlacementInput') is not feedback:
        raise ValueError('Mixed brand feedback replay mode')
    if not feedback and transport.get('brandFeedbackPlacement') is not None:
        raise ValueError('Unexpected brand feedback replay detail')
    if ui_case == 'search':
        for key in ('commentSearchProofRequested','commentSearchInputProofCompleted','commentSearchReadResponsesAreSynthetic',
                    'commentSearchPhysicalTextHumanReviewRequired'):
            if receipt.get(key) is not True: raise ValueError('Missing actual Main assertion: ' + key)
        for key in ('composerInputProofRequested','composerInputProofCompleted','syntheticAccountSeededThroughActualSessionStore'):
            if receipt.get(key) is not False: raise ValueError('Mixed comment UI proof')
        if transport.get('composerInputResponsesAreSynthetic') is not False or transport.get('composerInput') is not None:
            raise ValueError('Mixed comment replay modes')
        proof = by['186-original-comment-search-completed']
        for key in ('sameOriginalCommentVm','sameAcceptedPublicationIdentity','samePausedNativeSourceAndPreferences',
                    'mainCommentsUnchanged','actualOriginalCloseRetryScopeSortAndSubreplyConsumed','physicalOwnedDialogCapturesCollected'):
            if proof.get(key) is not True: raise ValueError('Missing original comment assertion: ' + key)
        if transport.get('realAccountUsed') is not False or transport.get('commentSearchResponsesAreSynthetic') is not True:
            raise ValueError('Unexpected account/transport')
        detail = transport['commentSearch']
        for key in ('actualOptionalCallCancellationObserved','grpcAndRestErrorStageObserved','originalTwoPageLoadCompleted'):
            if detail.get(key) is not True: raise ValueError('Original read cycle not completed')
        if detail.get('chargedControlProtobufField') != 31 or detail.get('subReplyOriginalRootRequested') != 91001:
            raise ValueError('Wrong original protobuf/root identity')
    elif ui_case == 'fullscreen':
        verify_fullscreen(receipt, by, transport)
    elif video_share:
        verify_video_share(receipt, by, transport, load(no_links(report / 'video-share-payload-receipt.json')))
    else:
        for key in ('composerInputProofRequested','composerInputProofCompleted','syntheticAccountSeededThroughActualSessionStore'):
            if receipt.get(key) is not True: raise ValueError('Missing actual composer assertion: ' + key)
        for key in ('commentSearchProofRequested','commentSearchInputProofCompleted','commentSearchReadResponsesAreSynthetic',
                    'commentSearchPhysicalTextHumanReviewRequired','commentPublishingAccepted','imageUploadAccepted','loginUiAccepted',
                    'commentsSent','nvidiaUiProofRequested','nvidiaUiProofCompleted','interactionProofRequested','interactionProofCompleted',
                    'featureInputProofCompleted','hotInputProofRequested','hotInputProofCompleted','collectionInputProofRequested',
                    'collectionInputProofCompleted','videoMetadataProofCompleted','bgmInputProofRequested','bgmInputProofCompleted',
                    'pipInputProofRequested','pipInputProofCompleted','originalInteractionProofRequested','originalInteractionProofCompleted',
                    'physicalStackWrittenByFixture','directPhysicalStackListMutation','newNativeActorCreatedByFixture','newRootCreatedByFixture'):
            if receipt.get(key) is not False: raise ValueError('Unexpected composer acceptance scope: ' + key)
        session = by['composer-synthetic-session-actual-root-generation']
        for key in ('sameActualRepository','originalGuestEntryAndRoutesRetired','actualRetainedHomeGenerationChanged','sameNativeMainWindow'):
            if session.get(key) is not True: raise ValueError('Missing actual session generation: ' + key)
        epoch = session.get('actualAccountEpoch')
        if (session.get('syntheticPrimaryMid') != 990000024 or type(epoch) is not int or epoch <= 0 or
            session.get('loginUiAccepted') is not False or type(session.get('sameWindowLevelRootHandle')) is not bool):
            raise ValueError('Unexpected actual synthetic account generation')
        proof = by['composer-original-input-closed-without-publish']
        for key in ('sameActualComposerDomain','sourcePausedAndPreferencesPreserved','textDraftRestored',
                    'originalEmoteAndMentionInserted','originalSyncFlagRestored','realOwnedOsChooserPrivatePngSelected','selectedImageRemoved'):
            if proof.get(key) is not True: raise ValueError('Missing original composer assertion: ' + key)
        for key in ('publishClicked','realCredentialsUsed','originalImageUploadAccepted'):
            if proof.get(key) is not False: raise ValueError('Composer mutation was accepted: ' + key)
        if transport.get('sameActualRepository') is not True or transport.get('composerInputResponsesAreSynthetic') is not True:
            raise ValueError('Composer used another repository or non-synthetic transport')
        for key in ('realBilibiliDataAccepted','realAccountUsed','newRootCreated','newPlayerCreated','newControllerCreated',
                    'originalVmStateWritten','actualNativeStateWritten','physicalStackWritten','commentSearchResponsesAreSynthetic',
                    'commentsSent','creatorFollowMutationSubmitted','bgmAccountMutationSubmitted',
                    'originalInteractionRemoteMutationSubmitted','collectionSubscriptionMutationSubmitted'):
            if transport.get(key) is not False: raise ValueError('Unexpected composer transport scope: ' + key)
        if transport.get('commentSearch') is not None: raise ValueError('Mixed comment replay modes')
        detail = transport['composerInput']
        for key in ('syntheticSessionSeededThroughActualStore','imageCreatedByFixture','emoteImagesArePrivateFiles'):
            if detail.get(key) is not True: raise ValueError('Missing original composer input precondition: ' + key)
        for key in ('loginUiAccepted','realAccountUsed','mutationRequestsPermitted'):
            if detail.get(key) is not False: raise ValueError('Unexpected synthetic session permission: ' + key)
        if (detail.get('syntheticPrimaryMid') != 990000024 or type(detail.get('syntheticAccountEpoch')) is not int or
            detail['syntheticAccountEpoch'] != epoch or detail.get('imageFile') != 'composer-private-image.png'):
            raise ValueError('Synthetic session/image identity changed')
        image_bytes = read(no_links(report / 'composer-private-image.png'))
        if not re.fullmatch('[0-9a-f]{64}', detail.get('imageSha256','')) or sha(image_bytes) != detail['imageSha256']:
            raise ValueError('Private chooser image changed')
        reads = detail['reads']
        if (not any(item.get('kind') == 'emote' and item.get('path') in ('/x/emote/user/panel/web','/x/emote/package') for item in reads) or
            not any(item.get('kind') == 'mention' and item.get('query') == '合成' for item in reads)):
            raise ValueError('Original emote/mention APIs were not consumed')
        forbidden_gets = {'/x/relation/modify','/x/web-interface/archive/like','/x/v2/reply/add','/x/v2/reply/action',
                          '/x/v2/reply/hate','/x/v2/reply/del','/x/v2/reply/report','/x/dynamic/feed/create/dyn',
                          '/x/dynamic/feed/create/dyn/submit','/x/v3/fav/resource/deal'}
        if any(item.get('method') not in ('GET','POST') or (item.get('path') in forbidden_gets and not
               (feedback and item.get('method') == 'POST' and item.get('host') == 'api.bilibili.com' and
                item.get('path') == '/x/web-interface/archive/like')) for item in transport['apiRequests']):
            raise ValueError('Composer transport observed a mutation')
    if feedback:
        detail = transport['brandFeedbackPlacement']
        for key in ('actualOriginalLikeProtocolConsumed','syntheticResponsesOnly'):
            if detail.get(key) is not True: raise ValueError('Missing original brand feedback protocol: ' + key)
        for key in ('remoteMutationSent','otherMutationPermitted','realCredentialsUsed'):
            if detail.get(key) is not False: raise ValueError('Unexpected brand feedback mutation scope: ' + key)
        actions = detail.get('actions')
        if type(actions) is not list or actions != [1,2,1,2,1] or any(type(action) is not int for action in actions):
            raise ValueError('Original five Like/unlike actions were not consumed')
        modal = by['feedback-owned-modal-hides-same-peer']
        for key in ('samePeer','actualDialogModal','sameFullSource','liveOwnedModalOverlapObserved','liveOwnedChooserOverlapObserved'):
            if modal.get(key) is not True: raise ValueError('Missing actual owned-modal feedback assertion: ' + key)
        scope = by['feedback-full-client-actual-main-scope']
        for key in ('actualOriginalLikeProtocol','sameActualComposerCommentsAndEngagement','ownedModalAndChooserObserved',
                    'sourcePausePreferencesPreserved','sameFullSource','physicalFramesRequireHumanReview',
                    'liveOwnedModalOverlapObserved','liveOwnedChooserOverlapObserved','liveOwnerMinimizedOverlapObserved'):
            if scope.get(key) is not True: raise ValueError('Missing actual Main feedback assertion: ' + key)
        if scope.get('inputMechanism') != 'OS_ROBOT' or scope.get('remoteMutationSent') is not False:
            raise ValueError('Unexpected feedback input or remote mutation')
        for key in ('samePeerModalRestoreObserved','samePeerMinimizeRestoreObserved','liveVideoFallbackNavigationObserved'):
            if type(scope.get(key)) is not bool: raise ValueError('Missing actual feedback lifecycle observation: ' + key)
    like_requests = []
    for request in transport['apiRequests']:
        if ui_case in ('composer', 'feedback', 'video_share') and is_blocked_composer_heartbeat_observation(request):
            continue  # Exact pre-guard observation only; any fulfilled heartbeat still fails below.
        if video_share and request.get('method') == 'POST' and request.get('host') == 'api.bilibili.com' and request.get('path') == '/x/dynamic/feed/create/dyn':
            continue  # The share proof above still checks exactly two observed and two memory-only original bodies.
        if feedback and request.get('method') == 'POST' and request.get('host') == 'api.bilibili.com' and request.get('path') == '/x/web-interface/archive/like':
            like_requests.append(request)
            continue
        if request['method'] == 'POST' and (request['host'] != 'app.bilibili.com' or request['path'] not in (
            '/bilibili.main.community.reply.v1.Reply/MainList','/bilibili.main.community.reply.v1.Reply/DetailList')):
            raise ValueError('Unexpected guest mutation POST')
    if feedback: verify_feedback_post_records(like_requests)
    captures = []
    for name in CAPTURES_BY_CASE[ui_case]:
        image = no_links(report / (name + '-screen.png')); data = read(image)
        if not data.startswith(b'\x89PNG\r\n\x1a\n') or data[12:16] != b'IHDR': raise ValueError('Physical capture is not PNG')
        width, height = struct.unpack('>II', data[16:24])
        if min(width, height) <= 0: raise ValueError('Empty physical capture')
        no_links(report / (name + '-accessibility.tsv'))
        if ui_case == 'fullscreen':
            row = by[name]; bounds = row.get('screenCaptureClientBounds')
            if (type(bounds) is not dict or set(bounds) != {'x', 'y', 'width', 'height'} or
                not all(type(bounds[key]) is int for key in bounds) or
                row.get('screenCaptureFile') != name + '-screen.png' or
                bounds['width'] != width or bounds['height'] != height):
                raise ValueError('Fullscreen physical capture differs from its guarded Main client bounds')
            for suffix in ('.png', '-frame.txt'): no_links(report / (name + suffix))
        captures.append(dict(path=image.name, sha256=sha(data), width=width, height=height))
    if ui_case == 'fullscreen':
        for name in ('110-ordinary-playing', '160-original-back-home'):
            for suffix in ('.png', '-frame.txt', '-accessibility.tsv'): no_links(report / (name + suffix))
        native = read(no_links(report / '110-ordinary-playing-native.png'))
        if (not native.startswith(b'\x89PNG\r\n\x1a\n') or native[12:16] != b'IHDR' or
            struct.unpack('>II', native[16:24]) != (by['110-ordinary-playing']['nativeScreenshotWidth'], by['110-ordinary-playing']['nativeScreenshotHeight'])):
            raise ValueError('Initial source-bound native PNG differs from the Main receipt')
    return captures

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('repo','java-home','jdk-archive','runtime','output'): parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--source-sha', required=True); parser.add_argument('--run', action='store_true')
    parser.add_argument('--comment-ui-case', choices=tuple(CAPTURES_BY_CASE), default='search')
    args = parser.parse_args()
    if not args.run or os.name != 'nt' or ctypes.sizeof(ctypes.c_void_p) != 8:
        parser.error('Explicit manual 64-bit Windows execution is required')
    repo = no_links(args.repo)
    if not re.fullmatch('[0-9a-f]{40}', args.source_sha) or git(repo,'rev-parse','HEAD').decode().strip() != args.source_sha:
        raise ValueError('Fixed source HEAD mismatch')
    source_changes = git(repo,'status','--porcelain','--untracked-files=all').strip()
    if source_changes:
        # Path-only evidence; preserve the admission failure without printing file contents.
        raise ValueError('Source checkout is dirty: ' + repr(source_changes[:12000].decode('utf-8', errors='replace')))
    output = Path(os.path.abspath(args.output)); parent = no_links(output.parent)
    if parent != no_links(Path(os.environ['RUNNER_TEMP'])) or output.exists(): raise ValueError('Output must be a fresh direct runner-temp child')
    output.mkdir(); report = output / 'actual-ui'; report.mkdir()
    runtime_path = no_links(args.runtime); runtime = load(runtime_path)
    validate_runtime(runtime, args.comment_ui_case)
    java_home = no_links(args.java_home)
    if Path(runtime['javaExecutable']).resolve() != (java_home / 'bin/java.exe').resolve(): raise ValueError('Original task uses another JDK')
    archive = no_links(args.jdk_archive)
    expected_jdk_sha = 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
    if sha(read(archive)) != expected_jdk_sha: raise ValueError('Existing fixed project JDK package differs')
    # Reuse the existing all-member verifier, offline, before the GUI timer.
    subprocess.run([sys.executable, str(no_links(repo / 'desktop/tools/fetch-js-worker-jdk.py')),
                    '--archive', str(archive), '--output', str(java_home), '--offline'],
                   check=True, capture_output=True, timeout=90)
    release = read(no_links(java_home / 'release'))
    if not re.search(rb'(?m)^JAVA_VERSION="21\.0\.12\.1"\r?$', release): raise ValueError('Fixed JDK release differs')
    java_version = subprocess.run([str(no_links(java_home / 'bin/java.exe')), '-version'],
                                  check=True, capture_output=True, timeout=15)
    version_text = (java_version.stdout + java_version.stderr).decode('utf-8', errors='replace')
    if len(version_text) > 4096 or '21.0.12.1+1' not in version_text: raise ValueError('Actual selected Java build differs')
    jdk = dict(archiveSha256=expected_jdk_sha, javaExecutable=runtime['javaExecutable'],
               javaExecutableSha256=sha(read(java_home / 'bin/java.exe')),
               releaseSha256=sha(release), selectedExecutableVersionOutput=version_text,
               fullArchiveMembersVerified=True, taskJavaLauncherMatches=True,
               actualMainSystemPropertiesRecorded=False, previousPackageRuntimeProven=False)
    before = sources(repo); compiled_before = compiled_inputs(runtime)
    local = no_links(Path(tempfile.mkdtemp(prefix='BiliPai-v025-root-routes-')))
    local = no_links(local.resolve(strict=True))
    token = str(uuid.uuid4())
    with (local / '.bilipai-root-validation').open('x', encoding='utf-8') as marker: marker.write(token)
    health = local / 'BiliPai/updates' / ('staged-comment-search-' + uuid.uuid4().hex) / ('launch-' + str(uuid.uuid4())) / 'startup-health.txt'
    health.parent.mkdir(parents=True)
    env = os.environ.copy(); env.update(JAVA_HOME=str(java_home), PYTHON_EXECUTABLE=sys.executable, LOCALAPPDATA=str(local))
    for key in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','BILIPAI_MPV_PATH','SKIKO_RENDER_API'): env.pop(key, None)
    command = [str(no_links(repo / 'gradlew.bat')), '-p', 'desktop', '--no-daemon', '--offline', '--console=plain',
               '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx4g',
               '-Porg.gradle.java.installations.paths=' + str(java_home),
               '-Porg.gradle.java.installations.auto-detect=false', '-Porg.gradle.java.installations.auto-download=false',
               '-I', str(no_links(Path(__file__).with_name('comment-search-ui.init.gradle'))),
               '-PcommentSearchPhase=run', '-PcommentUiCase=' + args.comment_ui_case,
               '-PcommentSearchRuntime=' + str(runtime_path), 'windowsVideoLocalReplayUiSmoke',
               '-ProotValidationReport=' + str(report), '-ProotValidationHealth=' + str(health),
               '-ProotValidationToken=' + token, '-ProotValidationVideo=BV1xx411c7mD']
    save(output / 'intent.json', dict(schema=1, sourceSha=args.source_sha, commentUiCase=args.comment_ui_case, task='windowsVideoLocalReplayUiSmoke',
        privateLocalAppData=str(local), health=str(health), token=token, runtimeSha256=sha(read(runtime_path)),
        selectedJdk=jdk, rendererOverride=None, noDaemon=True, guiTimeoutSeconds=180, fullNativeScreenGateExecuted=False, releaseGatePassed=False))
    process = {}; captures = None; failure = None; additional_failures = []
    try:
        process = run_owned(command, repo, env, output / 'gradle.log', 180, process)
        captures = verify(report, local, health, token, process, args.comment_ui_case)
    except Exception as error: failure = type(error).__name__ + ': ' + str(error)
    def check_after(action):
        nonlocal failure
        try: return action()
        except Exception as error:
            detail = type(error).__name__ + ': ' + str(error)
            if failure is None: failure = detail
            else: additional_failures.append(detail)
            return None
    after = check_after(lambda: sources(repo))
    compiled_after = check_after(lambda: compiled_inputs(runtime))
    log = check_after(lambda: read(output / 'gradle.log') if (output / 'gradle.log').exists() else b'')
    if log is None: log = b''
    bad_log = any(text in log for text in (b'Error was captured in composition', b'layout state is not idle before measure starts', b'Exception in thread'))
    passed = failure is None and captures is not None and before == after and compiled_before == compiled_after and not bad_log
    result = dict(schema=1, passed=passed, functionalAssertionsPassed=passed, sourceSha=args.source_sha, commentUiCase=args.comment_ui_case,
        sourcePinsUnchanged=before == after, preparedRuntimeUnchanged=compiled_before == compiled_after,
        sourceInventorySha256=sha(json.dumps(before).encode()), compiledInventorySha256=sha(json.dumps(compiled_before).encode()),
        selectedJdk=jdk, process=process, failure=failure, additionalFailures=additional_failures, captures=captures, logSha256=sha(log),
        fixtureDefaultRendererPolicyMarker="DIRECT3D", actualMainSkikoRenderApiMeasured=False,
        runnerMainObservationScope="RUNNER_SELF_SAMPLING_ONLY",
        physicalTextHumanReviewRequired=True, physicalVisibilityReviewed=False,
        fullscreenIdleAssertionsPassed=args.comment_ui_case == 'fullscreen' and passed, fullscreenPhysicalVisibilityReviewed=False,
        captureScope=('OWNED_CLIENT_SCREEN_WITH_SCENE_AND_NATIVE_DECODE_AUXILIARY' if args.comment_ui_case == 'fullscreen' else 'OWNED_PHYSICAL_UI'),
        fullNativeScreenGateExecuted=False, fullNativeScreenGatePassed=False, releaseGatePassed=False,
        portablePackageAccepted=False, newExeDeployed=False, realAccountUsed=False, fourKTested=False)
    save(output / 'result.json', result)
    print(json.dumps(result, ensure_ascii=False)); return 0 if passed else 1
if __name__ == '__main__': sys.exit(main())
