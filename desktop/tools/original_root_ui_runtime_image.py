"""Bind an actual packaged runtime to a private fixture image, without Java.

Package run evidence authenticates the portable ZIP; the original source export
authenticates the production classes and fixed JDK release. The private image
keeps the exact packaged module image and adds only the pinned JDK's java.exe.
File identity does not prove startup, UI behavior or hardware acceptance.
"""
from pathlib import Path
from contextlib import contextmanager
import hashlib
import importlib.util
import os
import re
import stat
import tempfile
import uuid
import zipfile

import original_root_ui_runtime_binding as binding

spec = importlib.util.spec_from_file_location('runtime_image_fixed_worker_jdk',
    Path(__file__).with_name('prepare-js-worker.py'))
worker = importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)
REPOSITORY = 'tonysuper666-creator/BiliPai-Windows'
APP = 'BiliPai Windows/'
MAX_ZIP = 512 * 1024 * 1024
MAX_EXPANDED = 2 * 1024 * 1024 * 1024
MAX_FILE = 256 * 1024 * 1024
TRANSFORMED = {'lib/modules', 'release'}


def java_argument_path(path):
    """Keep filesystem long-path prefixes out of Java classpath/resource URLs."""
    value = str(path)
    if value.startswith('\\\\?\\UNC\\'): return '\\\\' + value[8:]
    if value.startswith('\\\\?\\') and re.match('[a-zA-Z]:\\\\', value[4:]): return value[4:]
    return value


def measure(stream, maximum=MAX_FILE, target=None):
    digest = hashlib.sha256(); size = 0
    while True:
        raw = stream.read(1024 * 1024)
        if not raw: break
        size += len(raw)
        binding.require(size <= maximum, 'Runtime file exceeds its declared bound')
        digest.update(raw)
        if target is not None: target.write(raw)
    return dict(bytes=size, sha256=digest.hexdigest())


@contextmanager
def archive_snapshot(path, expected):
    path = binding.jars.regular_file(path)
    binding.require(type(expected.get('bytes')) is int and 22 <= expected['bytes'] <= MAX_ZIP and
        re.fullmatch('[0-9a-f]{64}', str(expected.get('sha256', ''))) is not None,
        'Invalid authenticated archive identity')
    binding.require(path.stat().st_size == expected['bytes'], 'Archive size differs from its evidence')
    with tempfile.SpooledTemporaryFile(max_size=2 * 1024 * 1024, mode='w+b') as copy:
        with path.open('rb') as stream: actual = measure(stream, MAX_ZIP, copy)
        binding.require(actual == expected, 'Archive bytes differ from their authenticated evidence')
        copy.flush(); copy.seek(0)
        count = binding.jars.zip_directory(copy, actual['bytes'], MAX_ZIP)
        copy.seek(0)
        with zipfile.ZipFile(copy) as archive:
            rows = archive.infolist()
            binding.require(len(rows) == count and len(rows) <= 20_000,
                            'Archive entry inventory exceeds its declared bound')
            names = set(); total = 0
            for row in rows:
                name = row.filename[:-1] if row.is_dir() else row.filename
                binding.relative(name)
                binding.require(name.casefold() not in names, 'Duplicate or case-aliased archive path')
                names.add(name.casefold())
                binding.require(not stat.S_ISLNK(row.external_attr >> 16) and
                    not row.flag_bits & 1 and row.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                    'Unsupported linked, encrypted or compressed archive entry')
                binding.require(0 <= row.file_size <= MAX_FILE, 'Archive member exceeds its declared bound')
                total += row.file_size
            binding.require(total <= MAX_EXPANDED, 'Expanded archive exceeds its declared bound')
            yield archive, rows


def verify_package_evidence(run, jobs, log, toolchain_raw, source_sha, version, *, source_plan_raw=None):
    binding.require(re.fullmatch('[0-9a-f]{40}', source_sha) is not None,
                    'An exact qualified source is required')
    binding.require(run.get('repository', {}).get('full_name') == REPOSITORY and
                    run['repository'].get('private') is False,
                    'Runtime images require the personal public repository')
    binding.require(run.get('status') == 'completed' and run.get('conclusion') == 'success' and
                    run.get('head_sha') == source_sha,
                    'Candidate packaging run is not the exact successful source')
    binding.require(len(log.encode('utf-8')) <= 32 * 1024 * 1024 and len(toolchain_raw) <= 65536,
                    'Candidate packaging evidence exceeds its declared bound')
    path = run.get('path', '').split('@', 1)[0]
    same_workspace = path == '.github/workflows/windows-source-validation.yml'
    qualification = None
    if same_workspace:
        binding.require(run.get('event') == 'push' and
            run.get('head_branch', '').startswith('desktop/source-validation-package-') and
            isinstance(source_plan_raw, bytes) and len(source_plan_raw) <= 4 * 1024 * 1024,
            'Same-workspace packaging requires its opted-in branch and exact source plan')
        plan = binding.json_bytes(source_plan_raw)
        binding.require(plan.get('repository') == REPOSITORY and plan.get('windowsVersion') == version and
            plan.get('exportOriginalRootUiHarness') is True,
            'Same-workspace packaging lacks the matching production export plan')
        gate_spec = importlib.util.spec_from_file_location('same_workspace_terminal_source_gate',
            Path(__file__).with_name('verify-package-source-qualification.py'))
        gate = importlib.util.module_from_spec(gate_spec)
        gate_spec.loader.exec_module(gate)
        # Re-read the complete terminal receipt and every raw XML case. A prior
        # provisional image check or a caller-supplied success flag cannot admit it.
        qualification = gate.verify(run, jobs, log, plan, source_sha)
        binding.require(qualification.get('sourceQualified') is True and len(jobs.get('jobs', [])) == 1,
            'Same-workspace packaging requires the one successful qualified source job')
        selected = [job for job in jobs.get('jobs', []) if job.get('name') == 'source-validation']
    else:
        binding.require(run.get('event') == 'workflow_dispatch' and
            path == '.github/workflows/windows-package-candidate.yml',
            'Candidate packaging run is not the exact successful source')
        selected = [job for job in jobs.get('jobs', []) if job.get('name') == 'package']
    binding.require(len(selected) == 1, 'Candidate package job is missing or ambiguous')
    job = selected[0]
    binding.require(job.get('run_id') == run.get('id') and job.get('head_sha') == source_sha and
                    job.get('status') == 'completed' and job.get('conclusion') == 'success' and
                    job.get('labels') == ['windows-latest'] and job.get('runner_group_name') == 'GitHub Actions',
                    'Candidate package job identity or standard runner differs')
    if same_workspace:
        for name in ('Prepare one provisional candidate from the same tested workspace',
                     'Export provisional same-workspace image for terminal-run admission'):
            steps = [step for step in job.get('steps', []) if step.get('name') == name]
            binding.require(len(steps) == 1 and steps[0].get('status') == 'completed' and
                steps[0].get('conclusion') == 'success',
                'Same-workspace candidate preparation or upload did not complete uniquely')
    lines = [re.sub(r'^\d{4}-\d\d-\d\dT\S+Z ', '', line).strip() for line in log.splitlines()]
    seals = [line.split(' ', 1)[1] for line in lines if line.startswith('BILIPAI_CANDIDATE_TOOLCHAIN_SHA256 ')]
    binding.require(len(seals) == 1 and re.fullmatch('[0-9a-f]{64}', seals[0]) is not None and
                    hashlib.sha256(toolchain_raw).hexdigest() == seals[0],
                    'Candidate toolchain identity is missing, ambiguous or changed')
    record = binding.json_bytes(toolchain_raw)
    binding.require(record.get('schema') == 1 and record.get('sourceSha') == source_sha and
        record.get('windowsVersion') == version and record.get('javaVersion') == '21.0.12.1' and
        record.get('toolchainAutoDetection') is False and record.get('toolchainAutoDownload') is False,
        'Candidate toolchain does not match the qualified source and fixed JDK')
    authority = dict(runId=run['id'], jobId=job['id'], toolchainSHA256=seals[0])
    if qualification is not None:
        authority.update(sameWorkspaceTerminalRun=True,
            sourceReceiptSHA256=qualification['sealedReceiptSHA256'],
            sourcePlanSHA256=hashlib.sha256(source_plan_raw).hexdigest())
    return record, authority


def _runtime_files(candidate, archive, entries):
    binding.require(all(row.filename.startswith(APP) for row in entries), 'Unexpected portable application root')
    runtime = {}
    for row in entries:
        if not row.is_dir() and row.filename.startswith(APP + 'runtime/'):
            relative = row.filename[len(APP + 'runtime/'):]
            binding.relative(relative); runtime[relative] = row
    binding.require({'release', 'lib/modules', 'bin/jli.dll', 'bin/server/jvm.dll'} <= set(runtime) and
                    4 <= len(runtime) <= 4096, 'The actual packaged runtime image is incomplete')
    root = binding.jars.paths.checked_path(candidate / 'runtime')
    actual = set()
    visited = 0
    for path in root.rglob('*'):
        visited += 1
        binding.require(visited <= 8192, 'Actual runtime tree exceeds its declared bound')
        binding.jars.paths.checked_path(path)
        if path.is_file(): actual.add(path.relative_to(root).as_posix())
    binding.require(actual == set(runtime), 'Candidate runtime inventory differs from the packaged ZIP')
    return runtime


def provision_runtime(production, portable_zip, package_run, package_jobs, package_log,
                      toolchain_raw, fixed_jdk_archive, output, *, source_plan_raw=None):
    binding.require(production.get('productionClasspathAndResourcesBound') is True and
        production.get('applicationExecuted') is False, 'A qualified production class/resource binding is required')
    source = production['sourceSha']; version = production['windowsVersion']
    toolchain, package = verify_package_evidence(package_run, package_jobs, package_log, toolchain_raw, source, version,
        source_plan_raw=source_plan_raw)
    binding.require(toolchain.get('javaReleaseSha256') == production.get('qualifiedJdkReleaseSHA256'),
                    'Source compilation and candidate packaging used different JDK releases')
    candidate = binding.jars.paths.checked_path(Path(production['candidateRoot'])).resolve()
    output = binding.jars.paths.absolute_path(Path(output).resolve())
    binding.require(not output.exists() and output.parent.is_dir() and
                    not output.is_relative_to(candidate), 'A fresh private runtime image path is required')
    binding.jars.paths.checked_path(output.parent)
    fixed = binding.jars.regular_file(fixed_jdk_archive)
    expected_jdk = dict(bytes=fixed.stat().st_size, sha256=worker.JDK_SHA256)
    expected_zip = {key: toolchain['candidateZip'][key] for key in ('bytes', 'sha256')}
    with archive_snapshot(portable_zip, expected_zip) as (archive, entries), \
            archive_snapshot(fixed, expected_jdk) as (jdk, jdk_entries):
        roots = {row.filename.split('/')[0] for row in jdk_entries}
        binding.require(len(roots) == 1, 'The fixed publisher JDK has an unexpected root')
        prefix = next(iter(roots)) + '/'
        release = binding.member_digest(jdk, prefix + 'release')
        binding.require(release['sha256'] == production['qualifiedJdkReleaseSHA256'],
                        'The actual fixed publisher JDK differs from source compilation')
        runtime = _runtime_files(candidate, archive, entries)
        pins = {}; publisher_equal = []
        for name, row in runtime.items():
            pin = binding.member_digest(archive, row.filename)
            binding.verify_pin(binding.jars.file_digest(candidate / 'runtime' / name), pin,
                               'Actual candidate runtime bytes differ: ' + name)
            pins[name] = pin
            if name not in TRANSFORMED:
                binding.verify_pin(binding.member_digest(jdk, prefix + name), pin,
                                   'Runtime file differs from the fixed publisher JDK: ' + name)
                publisher_equal.append(name)
        binding.require(pins['release']['sha256'] == toolchain.get('runtimeReleaseSha256'),
                        'Packaged runtime release differs from the logged candidate identity')
        with archive.open(runtime['release']) as stream: release_text = stream.read(65537).decode('utf-8')
        binding.require(len(release_text) <= 65536 and
            re.findall(r'(?m)^JAVA_VERSION="([^"\r\n]+)"\r?$', release_text) == ['21.0.12.1'],
            'Production runtime version is missing or duplicated')
        modules = re.findall(r'(?m)^MODULES="([a-zA-Z0-9_. ]+)"\r?$', release_text)
        binding.require(len(modules) == 1 and {'java.base', 'java.desktop', 'jdk.httpserver'} <= set(modules[0].split()),
                        'Production runtime lacks the modules required by the actual UI fixture')
        launcher = binding.member_digest(jdk, prefix + 'bin/java.exe')
        if 'bin/java.exe' in pins: binding.verify_pin(pins['bin/java.exe'], launcher, 'Packaged Java launcher differs')
        staging = output.with_name(output.name + '.' + uuid.uuid4().hex + '.staging')
        staging.mkdir()
        # Copy only authenticated entries, never a full alternative JDK image.
        # An interrupted staging directory is preserved for Root to inspect.
        for name, row in runtime.items():
            target = staging / name; target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(row) as stream, target.open('xb') as destination:
                pin = measure(stream, MAX_FILE, destination)
                destination.flush(); os.fsync(destination.fileno())
            binding.verify_pin(pin, pins[name], 'Private production runtime copy differs')
        if 'bin/java.exe' not in runtime:
            with jdk.open(prefix + 'bin/java.exe') as stream, (staging / 'bin/java.exe').open('xb') as destination:
                binding.verify_pin(measure(stream, MAX_FILE, destination), launcher, 'Private fixed launcher copy differs')
                destination.flush(); os.fsync(destination.fileno())
        expected = dict(pins); expected['bin/java.exe'] = launcher
        for name, pin in expected.items():
            binding.verify_pin(binding.jars.file_digest(staging / name), pin, 'Private runtime readback differs')
        _runtime_files(candidate, archive, entries)
        for name, pin in pins.items():
            binding.verify_pin(binding.jars.file_digest(candidate / 'runtime' / name), pin,
                               'Candidate runtime changed during private image provisioning')
        binding.require(not output.exists(), 'Private runtime image target appeared during provisioning')
        staging.rename(output)
    return dict(schema=1, sourceSha=source, windowsVersion=version, imageRoot=java_argument_path(output),
        javaExecutable=java_argument_path(output / 'bin/java.exe'), package=package,
        fixedJdkArchiveSHA256=worker.JDK_SHA256, qualifiedJdkReleaseSHA256=release['sha256'],
        productionRuntimeFiles=pins, publisherIdenticalFiles=len(publisher_equal),
        productionModuleImage=pins['lib/modules'], launcher=launcher,
        onlyAddedRuntimeFile='bin/java.exe' if 'bin/java.exe' not in runtime else None,
        productionRuntimeModules=modules[0].split(), completeRuntimeByteBindingVerified=True,
        candidateRuntimeBound=True, actualJdkBound=True, imageLaunchReady=True, executionAuthorized=False,
        javaExecuted=False, applicationExecuted=False, uiRuntimeVerified=False, hardwareVerified=False,
        desktopReplaced=False)
