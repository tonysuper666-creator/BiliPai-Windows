"""Manual hosted acceptance for the existing original Main/guest replay task.
No player, VM, account or GUI implementation is created by this runner.
"""
from pathlib import Path
import argparse, ctypes, hashlib, json, os, re, struct, subprocess, sys, tempfile, time, uuid
from ctypes import wintypes as W
sys.dont_write_bytecode = True

CAPTURES = [
    '178-comment-search-loading-cancel', '179-comment-search-retry-error',
    '180-comment-search-page-progress', '181-comment-search-all-hot',
    '182-comment-search-up-only', '183-comment-search-charged',
    '184-comment-search-all-latest', '185-comment-search-original-subreply',
]
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

def verify(report, local, health, token, process):
    if process['exitCode'] != 0 or process['forcedCleanup'] or not process['cleanupCompleted']:
        raise ValueError('Original UI task did not finish naturally')
    if read(no_links(health)).decode() != token or not read(no_links(health.with_name('startup-version.txt'))).decode().strip():
        raise ValueError('Actual Main startup identity missing')
    receipt = load(no_links(report / 'observations.json'))
    for key in ('allPreExitAssertionsPassed','sameLiveRootAndWindow','apiReplayInjected','loopbackMediaInjected',
                'commentSearchProofRequested','commentSearchInputProofCompleted','commentSearchReadResponsesAreSynthetic',
                'commentSearchPhysicalTextHumanReviewRequired'):
        if receipt.get(key) is not True: raise ValueError('Missing actual Main assertion: ' + key)
    if receipt.get('actualMainInvocations') != 1 or receipt.get('defaultRenderer') != 'DIRECT3D':
        raise ValueError('Unexpected Main invocation/default renderer policy marker')
    for key in ('realAccountUsed','guestRealApi','ordinaryFullscreenResizeRegressionExecuted','commentSearchFourKTested'):
        if receipt.get(key) is not False: raise ValueError('Unexpected acceptance scope: ' + key)
    rows = receipt['observations']; ids = [row['id'] for row in rows]
    if len(ids) != len(set(ids)) or len({row['actualWindowIdentity'] for row in rows}) != 1:
        raise ValueError('Root/window observations changed identity')
    if not all(row['sameRootAndRouteAssembly'] is True for row in rows): raise ValueError('Root source retired')
    by = {row['id']: row for row in rows}
    proof = by['186-original-comment-search-completed']
    for key in ('sameOriginalCommentVm','sameAcceptedPublicationIdentity','samePausedNativeSourceAndPreferences',
                'mainCommentsUnchanged','actualOriginalCloseRetryScopeSortAndSubreplyConsumed','physicalOwnedDialogCapturesCollected'):
        if proof.get(key) is not True: raise ValueError('Missing original comment assertion: ' + key)
    if by['160-original-back-home'].get('physicalStack') != ['MainHost']: raise ValueError('Actual Back did not return Home')
    transport = load(no_links(report / 'local-replay-receipt.json'))
    if transport.get('realAccountUsed') is not False or transport.get('commentSearchResponsesAreSynthetic') is not True:
        raise ValueError('Unexpected account/transport')
    detail = transport['commentSearch']
    for key in ('actualOptionalCallCancellationObserved','grpcAndRestErrorStageObserved','originalTwoPageLoadCompleted'):
        if detail.get(key) is not True: raise ValueError('Original read cycle not completed')
    if detail.get('chargedControlProtobufField') != 31 or detail.get('subReplyOriginalRootRequested') != 91001:
        raise ValueError('Wrong original protobuf/root identity')
    for request in transport['apiRequests']:
        if request['method'] == 'POST' and (request['host'] != 'app.bilibili.com' or request['path'] not in (
            '/bilibili.main.community.reply.v1.Reply/MainList','/bilibili.main.community.reply.v1.Reply/DetailList')):
            raise ValueError('Unexpected guest mutation POST')
    captures = []
    for name in CAPTURES:
        image = no_links(report / (name + '-screen.png')); data = read(image)
        if not data.startswith(b'\x89PNG\r\n\x1a\n') or data[12:16] != b'IHDR': raise ValueError('Physical capture is not PNG')
        width, height = struct.unpack('>II', data[16:24])
        if min(width, height) <= 0: raise ValueError('Empty physical capture')
        no_links(report / (name + '-accessibility.tsv'))
        captures.append(dict(path=image.name, sha256=sha(data), width=width, height=height))
    return captures

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('repo','java-home','jdk-archive','runtime','output'): parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--source-sha', required=True); parser.add_argument('--run', action='store_true')
    args = parser.parse_args()
    if not args.run or os.name != 'nt' or ctypes.sizeof(ctypes.c_void_p) != 8:
        parser.error('Explicit manual 64-bit Windows execution is required')
    repo = no_links(args.repo)
    if not re.fullmatch('[0-9a-f]{40}', args.source_sha) or git(repo,'rev-parse','HEAD').decode().strip() != args.source_sha:
        raise ValueError('Fixed source HEAD mismatch')
    if git(repo,'status','--porcelain','--untracked-files=all').strip(): raise ValueError('Source checkout is dirty')
    output = Path(os.path.abspath(args.output)); parent = no_links(output.parent)
    if parent != no_links(Path(os.environ['RUNNER_TEMP'])) or output.exists(): raise ValueError('Output must be a fresh direct runner-temp child')
    output.mkdir(); report = output / 'actual-ui'; report.mkdir()
    runtime_path = no_links(args.runtime); runtime = load(runtime_path)
    if runtime.get('mainClass') != 'com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture': raise ValueError('Wrong original Main fixture')
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
               '-PcommentSearchPhase=run', '-PcommentSearchRuntime=' + str(runtime_path), 'windowsVideoLocalReplayUiSmoke',
               '-ProotValidationReport=' + str(report), '-ProotValidationHealth=' + str(health),
               '-ProotValidationToken=' + token, '-ProotValidationVideo=BV1xx411c7mD']
    save(output / 'intent.json', dict(schema=1, sourceSha=args.source_sha, task='windowsVideoLocalReplayUiSmoke',
        privateLocalAppData=str(local), health=str(health), token=token, runtimeSha256=sha(read(runtime_path)),
        selectedJdk=jdk, rendererOverride=None, noDaemon=True, guiTimeoutSeconds=180, fullNativeScreenGateExecuted=False, releaseGatePassed=False))
    process = {}; captures = None; failure = None; additional_failures = []
    try:
        process = run_owned(command, repo, env, output / 'gradle.log', 180, process)
        captures = verify(report, local, health, token, process)
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
    result = dict(schema=1, passed=passed, functionalAssertionsPassed=passed, sourceSha=args.source_sha,
        sourcePinsUnchanged=before == after, preparedRuntimeUnchanged=compiled_before == compiled_after,
        sourceInventorySha256=sha(json.dumps(before).encode()), compiledInventorySha256=sha(json.dumps(compiled_before).encode()),
        selectedJdk=jdk, process=process, failure=failure, additionalFailures=additional_failures, captures=captures, logSha256=sha(log),
        fixtureDefaultRendererPolicyMarker="DIRECT3D", actualMainSkikoRenderApiMeasured=False,
        physicalTextHumanReviewRequired=True, physicalVisibilityReviewed=False,
        fullNativeScreenGateExecuted=False, fullNativeScreenGatePassed=False, releaseGatePassed=False,
        portablePackageAccepted=False, newExeDeployed=False, realAccountUsed=False, fourKTested=False)
    save(output / 'result.json', result)
    print(json.dumps(result, ensure_ascii=False)); return 0 if passed else 1
if __name__ == '__main__': sys.exit(main())
