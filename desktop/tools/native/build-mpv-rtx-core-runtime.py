#!/usr/bin/env python3
"""Explicit RTX core MPV bridge source build. No SDK/runtime, release, UI or GPU run.

mpv, FFmpeg, recipes and container are fixed; present-v1 also pins libvpl, curl, libssh and libaribcaption.
Other historical recipe dependencies
remain floating and are recorded from the actual build; this is not reproducible.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import stat
import struct
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

VARIANT = 'bilipai-veyra-rtx-core-v1'
IMAGE = 'ghcr.io/tonysuper666-creator/bilipai-windows-builder@sha256:c7dffe77b57d98b10e327dde12d3977faf4cb90aa7cb4f5eeac4e9d68d724239'
ROOT = Path(__file__).resolve().parents[3]
INPUTS = ROOT / 'desktop/third-party/libmpv/build/rtx-core-v1'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def file_sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def write_json(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')

def checked_libvpl_materials(directory, spec):
    # Exact public source/material bytes, not QSV/GPU execution evidence.
    if not stat.S_ISDIR(directory.lstat().st_mode) or directory.is_symlink():
        raise RuntimeError('Retained libvpl material directory changed')
    names = {'mfx_dispatcher_defs.before.h', 'mfx_dispatcher_defs.after.h',
             'patch-receipt.json', 'install-receipt.json'}
    if {p.name for p in directory.iterdir()} != names:
        raise RuntimeError('Retained libvpl material inventory changed')
    materials = {}
    for name in sorted(names):
        fd = os.open(directory / name, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        try:
            first = os.fstat(fd)
            if not stat.S_ISREG(first.st_mode) or not 0 < first.st_size <= 65536:
                raise RuntimeError('Retained libvpl material extent changed')
            with os.fdopen(fd, 'rb', closefd=False) as source:
                raw = source.read(65537)
            last = os.fstat(fd)
            if (first.st_dev, first.st_ino, first.st_size, first.st_mtime_ns) != (last.st_dev, last.st_ino, last.st_size, last.st_mtime_ns) or len(raw) != first.st_size:
                raise RuntimeError('Retained libvpl material changed during read')
            materials[name] = raw
        finally:
            os.close(fd)
    before, after = materials['mfx_dispatcher_defs.before.h'], materials['mfx_dispatcher_defs.after.h']
    old, new = b'#if _MSC_VER < 1400\n', b'#if defined(_MSC_VER) && _MSC_VER < 1400\n'
    if (len(before) != spec['beforeBytes'] or len(after) != spec['afterBytes']
            or sha(before) != spec['beforeSha256'] or sha(after) != spec['afterSha256']
            or before.count(old) != 1 or after.count(new) != 1
            or before.replace(old, new) != after or after.replace(new, old) != before):
        raise RuntimeError('Retained complete libvpl header relation changed')
    expected = {'schema': 1, 'kind': 'BILIPAI_LIBVPL_MINGW_COMPAT_HEADER_SOURCE',
                'sourceCommit': spec['sourceCommit'], 'sourceTree': spec['sourceTree'],
                'targetPath': spec['targetPath'], 'beforeSha256': spec['beforeSha256'],
                'afterSha256': spec['afterSha256'], 'helperSha256': spec['helperSha256'],
                'patchCount': 1, 'qsvRuntimeTested': False, 'gpuExecuted': False}
    for name, state in [('patch-receipt.json', 'PATCH_APPLIED_BEFORE_CONFIGURE'),
                        ('install-receipt.json', 'AFTER_REAL_LIBVPL_INSTALL_BEFORE_RECIPE_CLEANUP')]:
        canonical = (json.dumps(dict(expected, state=state), sort_keys=True, indent=2) + '\n').encode()
        if materials[name] != canonical:
            raise RuntimeError('Retained libvpl install/source witness changed')
    return json.loads(materials['install-receipt.json']), materials

def checked_curl_libssh_materials(directory, spec):
    # Complete public headers and canonical recipe observations; not runtime proof.
    if not stat.S_ISDIR(directory.lstat().st_mode) or directory.is_symlink():
        raise RuntimeError('Retained curl/libssh material directory changed')
    names = {'libssh.h', 'scp.h', 'libssh-install-receipt.json',
             'curl-ssh.before.h', 'curl-ssh.after.h', 'curl-patch-receipt.json',
             'curl-install-receipt.json'}
    if {p.name for p in directory.iterdir()} != names:
        raise RuntimeError('Retained curl/libssh material inventory changed')
    materials = {}
    for name in sorted(names):
        fd = os.open(directory / name, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        try:
            first = os.fstat(fd)
            if not stat.S_ISREG(first.st_mode) or not 0 < first.st_size <= 65536:
                raise RuntimeError('Retained curl/libssh material extent changed')
            with os.fdopen(fd, 'rb', closefd=False) as source:
                raw = source.read(65537)
            last = os.fstat(fd)
            if (first.st_dev, first.st_ino, first.st_size, first.st_mtime_ns) != (last.st_dev, last.st_ino, last.st_size, last.st_mtime_ns) or len(raw) != first.st_size:
                raise RuntimeError('Retained curl/libssh material changed during read')
            materials[name] = raw
        finally:
            os.close(fd)
    before, after = materials['curl-ssh.before.h'], materials['curl-ssh.after.h']
    old, new = b'#include <libssh/libssh.h>\n', b'#include <libssh/libssh.h>\n#include <libssh/scp.h>\n'
    if (len(before) != spec['beforeBytes'] or len(after) != spec['afterBytes']
            or sha(before) != spec['beforeSha256'] or sha(after) != spec['afterSha256']
            or before.count(old) != 1 or after.count(new) != 1
            or before.replace(old, new) != after or after.replace(new, old) != before):
        raise RuntimeError('Retained complete curl public-header relation changed')
    for name, pin in spec['libsshHeaders'].items():
        if len(materials[name]) != pin['bytes'] or sha(materials[name]) != pin['sha256']:
            raise RuntimeError('Retained complete libssh public header changed')
    expected = {'schema': 1, 'kind': 'BILIPAI_CURL_LIBSSH_SCP_HEADER_SOURCE',
                'curlCommit': spec['curlCommit'], 'curlTree': spec['curlTree'],
                'libsshCommit': spec['libsshCommit'], 'libsshTree': spec['libsshTree'],
                'targetPath': spec['targetPath'], 'beforeSha256': spec['beforeSha256'],
                'afterSha256': spec['afterSha256'], 'libsshHeaders': spec['libsshHeaders'],
                'helperSha256': spec['helperSha256'], 'reviewedPatchCount': 1,
                'protocolsDisabled': False, 'runtimeTested': False, 'gpuExecuted': False}
    for name, state in [('libssh-install-receipt.json', 'AFTER_REAL_LIBSSH_INSTALL_BEFORE_RECIPE_CLEANUP'),
                        ('curl-patch-receipt.json', 'CURL_PATCH_APPLIED_BEFORE_CONFIGURE'),
                        ('curl-install-receipt.json', 'AFTER_REAL_CURL_INSTALL_BEFORE_RECIPE_CLEANUP')]:
        canonical = (json.dumps(dict(expected, state=state), sort_keys=True, indent=2) + '\n').encode()
        if materials[name] != canonical:
            raise RuntimeError('Retained curl/libssh recipe observation changed')
    return json.loads(materials['curl-install-receipt.json']), materials


def download(record, directory):
    destination = directory / record['fileName']
    with urllib.request.urlopen(record['url'], timeout=180) as response, destination.open('wb') as output:
        shutil.copyfileobj(response, output)
    if sha(destination.read_bytes()) != record['sha256']:
        raise RuntimeError('Fixed source archive checksum mismatch: ' + record['fileName'])
    return destination

def extract_fixed_recipe(archive, directory, prefix):
    with tarfile.open(archive) as source:
        members = source.getmembers()
        for member in members:
            parts = Path(member.name).parts
            if not parts or parts[0] != prefix or '..' in parts or member.name.startswith('/'):
                raise RuntimeError('Unsafe fixed recipe archive path')
            if not (member.isfile() or member.isdir()):
                raise RuntimeError('Unexpected link/device in fixed recipe archive')
        source.extractall(directory, members=members, filter='data')
    return directory / prefix

def apply_recipes(recipes, records):
    for row in records:
        target = recipes / row['targetPath'].removeprefix('build-recipes/')
        data = target.read_bytes()
        if sha(data) != row['beforeSha256Bytes']:
            raise RuntimeError('Wrong complete recipe bytes: ' + row['targetPath'])
        original = data
        for edit in row['rawEdits']:
            old, new = edit['old'].encode(), edit['new'].encode()
            at = edit['offsetBytesAtSequentialStage']
            if data.count(old) != 1 or data[at:at + len(old)] != old:
                raise RuntimeError('Recipe anchor mismatch')
            data = data[:at] + new + data[at + len(old):]
        if sha(data) != row['afterSha256Bytes']:
            raise RuntimeError('Wrong patched recipe bytes')
        inverse = data
        for edit in reversed(row['rawEdits']):
            old, new = edit['old'].encode(), edit['new'].encode()
            at = edit['offsetBytesAtSequentialStage']
            if inverse[at:at + len(new)] != new:
                raise RuntimeError('Recipe inverse mismatch')
            inverse = inverse[:at] + old + inverse[at + len(new):]
        if inverse != original:
            raise RuntimeError('Recipe inverse does not recover the complete fixed original')
        target.write_bytes(data)

def run(command, log):
    command = [str(x) for x in command]
    # Only build children inherit this identity; git am keeps each patch author.
    environment = os.environ.copy()
    environment['GIT_COMMITTER_NAME'] = 'BiliPai Native Builder'
    environment['GIT_COMMITTER_EMAIL'] = 'native-builder@bilipai.invalid'
    with log.open('ab') as output:
        marker = ('\nCOMMAND ' + json.dumps(command) + '\n').encode()
        output.write(marker)
        output.flush()
        sys.stdout.buffer.write(marker)
        sys.stdout.buffer.flush()
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=environment)
        try:
            while block := process.stdout.read1(65536):
                output.write(block)
                output.flush()
                sys.stdout.buffer.write(block)
                sys.stdout.buffer.flush()
            code = process.wait()
            if code:
                raise subprocess.CalledProcessError(code, command)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait()
            process.stdout.close()

def pe_imports(data):
    """Read actual PE x64 normal/delay imports without loading the DLL."""
    if data[:2] != b'MZ':
        raise RuntimeError('Output is not a PE DLL')
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    if data[pe:pe + 4] != b'PE\0\0':
        raise RuntimeError('Invalid PE signature')
    machine, sections = struct.unpack_from('<HH', data, pe + 4)
    optional_size = struct.unpack_from('<H', data, pe + 20)[0]
    optional = pe + 24
    if machine != 0x8664 or struct.unpack_from('<H', data, optional)[0] != 0x20b:
        raise RuntimeError('Output is not Windows AMD64 PE32+')
    image_base = struct.unpack_from('<Q', data, optional + 24)[0]
    directory_count = struct.unpack_from('<I', data, optional + 108)[0]
    headers_size = struct.unpack_from('<I', data, optional + 60)[0]
    section_table = optional + optional_size
    section_rows = [struct.unpack_from('<IIII', data, section_table + n * 40 + 8) for n in range(sections)]
    def offset(rva):
        if 0 <= rva < headers_size:
            return rva
        for virtual_size, virtual_address, raw_size, raw_pointer in section_rows:
            if virtual_address <= rva < virtual_address + max(virtual_size, raw_size):
                result = raw_pointer + rva - virtual_address
                if rva - virtual_address >= raw_size or result >= len(data):
                    break
                return result
        raise RuntimeError('PE import RVA has no file backing')
    def name(rva):
        start = offset(rva)
        end = data.find(b'\0', start, min(start + 261, len(data)))
        if end < 0:
            raise RuntimeError('Invalid DLL import name')
        value = data[start:end].decode('ascii').lower()
        if not re.fullmatch(r'[a-z0-9_.-]+\.dll', value):
            raise RuntimeError('Unsafe DLL import name')
        return value
    result = []
    for index, stride, delayed in [(1, 20, False), (13, 32, True)]:
        if directory_count <= index:
            continue
        rva, size = struct.unpack_from('<II', data, optional + 112 + index * 8)
        if not rva:
            continue
        start = offset(rva)
        terminated = False
        for at in range(start, start + size, stride):
            row = struct.unpack_from('<' + 'I' * (stride // 4), data, at)
            if not any(row):
                terminated = True
                break
            name_rva = row[1] if delayed else row[3]
            if delayed and not (row[0] & 1):
                name_rva -= image_base
            result.append({'name': name(name_rva), 'delayLoaded': delayed})
        if not terminated:
            raise RuntimeError('Unterminated PE import directory')
    return result

SYSTEM_DLLS = set(('advapi32 avrt bcrypt bcryptprimitives crypt32 d3d11 d3d9 d3dcompiler_47 '
                  'd3d12 dcomp dwrite dwmapi dxgi gdi32 imm32 iphlpapi kernel32 msvcrt normaliz ntdll '
                  'ole32 oleaut32 opengl32 powrprof propsys psapi secur32 setupapi shell32 shlwapi '
                  'ucrtbase user32 version winhttp wininet winmm winspool ws2_32').split())


def print_nested_build_failure_logs(workspace, workspace_identity, output, output_identity):
    """Read bounded CMake logs from this invocation's real workspace only."""
    file_limit, total_limit, file_count = 64 * 1024, 256 * 1024, 8
    remaining, printed, scanned = total_limit, 0, 0
    # The source producer runs in Linux. No weaker pathname fallback on hosts
    # without descriptor-relative no-follow opens (including Windows).
    if (os.open not in os.supports_dir_fd or not hasattr(os, 'O_NOFOLLOW')
            or not hasattr(os, 'O_DIRECTORY') or not hasattr(os, 'O_NONBLOCK')):
        return {'state': 'UNAVAILABLE_SAFE_OPEN', 'files': 0, 'bytes': 0}
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    root_fd = output_fd = log_fd = None
    secrets = [piece for name, value in os.environ.items() if value and re.search(
        r'TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|PRIVATE_KEY|AUTHORIZATION|COOKIE|API_KEY|ACCESS_KEY', name, re.I)
        for piece in value.splitlines() if piece]
    sensitive_line = re.compile(
        r'authorization|cookie|password|passwd|credential|private[ _-]?key|'
        r"(?:token|secret|api[ _-]?key|access[ _-]?key)['\"]?\s*[:=]|"
        r'gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|'
        r'\w+://[^\s/]*@', re.I)
    def filtered(raw, tail_truncated):
        text = raw.decode('utf-8', 'replace').replace('\r', '\n')
        text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', text)
        lines = []
        first_private_marker = re.search(r'-----(BEGIN|END) [^-]*PRIVATE KEY-----', text)
        private_block = bool(tail_truncated and first_private_marker and first_private_marker.group(1) == 'END')
        for line in text.split('\n'):
            original_line = line
            if re.search(r'-----BEGIN .*PRIVATE KEY-----', line):
                private_block = True
            hidden = (private_block or sensitive_line.search(line)
                      or re.fullmatch(r'[A-Za-z0-9+/=]{40,}', line.strip())
                      or any(value in line for value in secrets))
            if hidden:
                line = '[sensitive diagnostic line omitted]'
            else:
                # Queries/fragments are not useful compiler diagnostics.
                line = re.sub(r'https?://[^\s]*[?#][^\s]*', '[URL parameters omitted]', line)
                line = ''.join(ch for ch in line if ch == '\t' or ord(ch) >= 32)
            if re.search(r'-----END .*PRIVATE KEY-----', original_line):
                private_block = False
            # Prefix every line: nested logs cannot issue Actions :: commands.
            lines.append('[nested] ' + line)
        return ('\n'.join(lines) + '\n').encode('utf-8')
    def emit(data):
        nonlocal remaining
        data = data[:remaining]
        if not data:
            return
        pending = memoryview(data)
        while pending:
            written = os.write(log_fd, pending)
            if written <= 0:
                raise OSError('Diagnostic log write failed')
            pending = pending[written:]
        sys.stdout.buffer.write(data)
        sys.stdout.buffer.flush()
        remaining -= len(data)
    def absolute_directory(path):
        # Walk from the filesystem anchor, never a pathname-opened ancestor.
        # Main supplies an already resolved absolute directory from this run.
        if not path.is_absolute() or any(part in ('', '.', '..') for part in path.parts[1:]):
            raise OSError('Unsafe diagnostic directory')
        fd = os.open(path.anchor, directory_flags)
        try:
            for part in path.parts[1:]:
                next_fd = os.open(part, directory_flags, dir_fd=fd)
                os.close(fd)
                fd = next_fd
            return fd
        except BaseException:
            os.close(fd)
            raise
    def directory(parts):
        fd = os.dup(root_fd)
        try:
            for part in parts:
                next_fd = os.open(part, directory_flags, dir_fd=fd)
                os.close(fd)
                fd = next_fd
            return fd
        except BaseException:
            os.close(fd)
            raise
    def add_candidate(parts, name, kind):
        nonlocal scanned, partial
        if scanned >= 512:
            partial = True
            return
        fd = directory(parts[:-1])
        try:
            meta = os.stat(parts[-1], dir_fd=fd, follow_symlinks=False)
            if stat.S_ISREG(meta.st_mode) and meta.st_nlink == 1 and meta.st_size:
                candidates.append((parts, name, kind, meta.st_mtime_ns, meta.st_dev, meta.st_ino))
                scanned += 1
        finally:
            os.close(fd)
    try:
        root_fd = absolute_directory(workspace)
        root_stat = os.fstat(root_fd)
        if (root_stat.st_dev, root_stat.st_ino) != workspace_identity:
            return {'state': 'RETIRED_WORKSPACE', 'files': 0, 'bytes': 0}
        # This existing output log supplies only a package identifier, not a
        # path to open or content to print. A latest failed EP ranks first.
        output_fd = absolute_directory(output)
        output_stat = os.fstat(output_fd)
        if (output_stat.st_dev, output_stat.st_ino) != output_identity:
            return {'state': 'RETIRED_OUTPUT', 'files': 0, 'bytes': 0}
        log_fd = os.open('native-build.log', os.O_RDWR | os.O_APPEND | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=output_fd)
        log_stat = os.fstat(log_fd)
        if not stat.S_ISREG(log_stat.st_mode) or log_stat.st_nlink != 1:
            return {'state': 'UNSAFE_DIAGNOSTIC_LOG', 'files': 0, 'bytes': 0}
        os.lseek(log_fd, max(0, log_stat.st_size - file_limit), os.SEEK_SET)
        markers = re.findall(rb'([A-Za-z0-9][A-Za-z0-9._-]{0,63})-prefix/src/\1-stamp/\1-(?:build|configure|install|bilipai-capture-host-(?:prebuild|config))', os.read(log_fd, file_limit))
        failed_name = markers[-1].decode('ascii') if markers else None
        candidates, seen, partial = [], set(), False
        for location in (('build-x64', 'packages'), ('build-x64', 'toolchain'), ('build-x64', 'toolchain', 'llvm')):
            try:
                parent_fd = directory(location)
            except OSError:
                continue
            try:
                names = []
                with os.scandir(parent_fd) as entries:
                    for index, entry in enumerate(entries):
                        if index >= 512:
                            partial = True
                            break
                        if re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}-prefix', entry.name):
                            names.append(entry.name)
                for prefix in sorted(names):
                    name = prefix[:-7]
                    stamp_parts = (*location, prefix, 'src', name + '-stamp')
                    try:
                        stamp_fd = directory(stamp_parts)
                    except OSError:
                        continue
                    try:
                        with os.scandir(stamp_fd) as entries:
                            for index, entry in enumerate(entries):
                                if index >= 256 or scanned >= 512:
                                    partial = True
                                    break
                                if re.fullmatch(re.escape(name) + r'-(?:build|configure|install|bilipai-capture-host-(?:prebuild|config))-[A-Za-z0-9._-]{1,64}\.log', entry.name):
                                    kind = 0 if entry.name.endswith('-err.log') else 1
                                    add_candidate((*stamp_parts, entry.name), name, kind)
                    finally:
                        os.close(stamp_fd)
                    if scanned < 512:
                        for leaf in ('CMakeError.log', 'CMakeOutput.log'):
                            try:
                                add_candidate((*location, prefix, 'src', name + '-build', 'CMakeFiles', leaf), name, 2)
                            except OSError:
                                pass
            finally:
                os.close(parent_fd)
        candidates.sort(key=lambda row: (row[1] != failed_name,
            row[2] if row[1] == failed_name else 0, -row[3], row[0]))
        emit(b'\n[nested] BEGIN bounded CMake failure diagnostics\n')
        for parts, name, kind, mtime, device, inode in candidates:
            if printed >= file_count or remaining <= 1024:
                break
            if (device, inode) in seen:
                continue
            seen.add((device, inode))
            parent_fd = file_fd = None
            try:
                parent_fd = directory(parts[:-1])
                file_fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent_fd)
                before = os.fstat(file_fd)
                if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or (before.st_dev, before.st_ino) != (device, inode):
                    continue
                start = max(0, before.st_size - file_limit)
                os.lseek(file_fd, start, os.SEEK_SET)
                raw = b''
                while len(raw) < file_limit:
                    block = os.read(file_fd, file_limit - len(raw))
                    if not block:
                        break
                    raw += block
                after = os.fstat(file_fd)
                if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                    continue
                if start:
                    # Tail may start inside a secret value. Never emit that
                    # first possibly incomplete physical line.
                    newline_at = raw.find(b'\n')
                    discarded = len(raw) if newline_at < 0 else newline_at + 1
                    raw = raw[discarded:]
                    start += discarded
                body = filtered(raw, bool(start))
                body_budget = min(file_limit - 512, remaining - 512)
                kept = body[:body_budget].decode('utf-8', 'ignore').encode('utf-8')
                truncated = len(body) - len(kept)
                body = kept
                header = ('[nested] FILE ' + '/'.join(parts) + ' sizeBytes=' + str(before.st_size)
                    + ' skippedHeadBytes=' + str(start) + ' outputTruncatedBytes=' + str(truncated) + '\n').encode('ascii')
                emit(header + body + b'\n[nested] END FILE\n')
                printed += 1
            except OSError:
                continue
            finally:
                if file_fd is not None:
                    os.close(file_fd)
                if parent_fd is not None:
                    os.close(parent_fd)
        emit(('[nested] END diagnostics files=' + str(printed) + ' scanBoundReached=' + str(partial) + '\n').encode('ascii'))
        return {'state': 'BOUNDED_DIAGNOSTICS', 'files': printed, 'bytes': total_limit - remaining,
                'scanBoundReached': partial, 'fileLimit': file_limit, 'totalLimit': total_limit}
    except Exception:
        # Never print an exception containing raw nested content and never mask
        # the original failing build exception/exit status with diagnostics.
        return {'state': 'DIAGNOSTICS_UNAVAILABLE', 'files': printed, 'bytes': total_limit - remaining}
    finally:
        if log_fd is not None:
            os.close(log_fd)
        if output_fd is not None:
            os.close(output_fd)
        if root_fd is not None:
            os.close(root_fd)

def dependency_inventory(sources):
    result = []
    for directory in sorted(sources.iterdir()):
        if not directory.is_dir() or not (directory / '.git').exists():
            continue
        head = subprocess.check_output(['git', '-C', str(directory), 'rev-parse', 'HEAD'], text=True).strip()
        remote = subprocess.check_output(['git', '-C', str(directory), 'remote', 'get-url', 'origin'], text=True).strip()
        if not remote.startswith('https://') or '@' in remote.split('://', 1)[1].split('/', 1)[0]:
            raise RuntimeError('Dependency source remote must be public HTTPS without credentials')
        dirty = subprocess.check_output(['git', '-C', str(directory), 'status', '--porcelain'], text=True)
        result.append({'directory': directory.name, 'commit': head, 'remote': remote, 'postBuildWorkingTreeStatus': dirty})
    return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--variant', choices=(VARIANT, 'bilipai-veyra-rtx-present-v1'), default='bilipai-veyra-rtx-present-v1',
                        help='Build the current presentation source by default; core-v1 requires the original immutable source bytes')
    parser.add_argument('--host-llvm-snapshot-dir', type=Path,
                        help='Explicit independently pinned local own-source snapshot; missing/untrusted material fails closed')
    args = parser.parse_args()
    variant = args.variant
    presentation = variant == 'bilipai-veyra-rtx-present-v1'
    inputs = ROOT / 'desktop/third-party/libmpv/build' / ('rtx-present-v1' if presentation else 'rtx-core-v1')
    manifest_leaf = 'bilipai-rtx-presentation-source-manifest.json' if presentation else 'bilipai-rtx-source-manifest.json'
    helper_leaf = 'apply-rtx-presentation-source.py' if presentation else 'bilipai-veyra-rtx-core-patch.py'
    # Reject an incompatible legacy/current source selection before creating output,
    # downloading source or invoking any build tool. Never relabel new source as 9c0.
    selected_manifest_raw = (inputs / manifest_leaf).read_bytes()
    selected_manifest_sha = ('a1cda53ef043749e006c88a84abee129bccc7bfc649b011976cd421e75e5c607'
                             if presentation else '9c0f19de87da2398f15d09dd27ebca911ba292e5689d53bf7f62ea1742c3359f')
    if sha(selected_manifest_raw) != selected_manifest_sha:
        raise RuntimeError('The selected complete source manifest differs from its reviewed identity')
    selected_manifest = json.loads(selected_manifest_raw)
    if selected_manifest.get('variant') != variant or selected_manifest.get('sourceCommit') != '69e63f425a531f814431fba12750bdb3721357f2':
        raise RuntimeError('The selected source manifest variant/MPV commit differs')
    if len(selected_manifest.get('sourceFiles', [])) != (21 if presentation else 4):
        raise RuntimeError('The selected private source inventory is incomplete')
    for row in selected_manifest['sourceFiles']:
        selected_source = (ROOT / row['sourcePath']).resolve(strict=True)
        if ROOT not in selected_source.parents or not selected_source.is_file():
            raise RuntimeError('Selected private source escaped the owned checkout')
        data = selected_source.read_bytes()
        if sha(data) != row['sha256'] or len(data) != row['bytes']:
            if not presentation:
                raise RuntimeError('Legacy core-v1 source is not available in this checkout; use its original immutable source tag. Current source uses present-v1')
            raise RuntimeError('Current presentation private source/header bytes differ from the reviewed inventory')
    workspace, output = args.workspace.resolve(), args.output.resolve()
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        if (os.environ.get('GITHUB_REPOSITORY') != 'tonysuper666-creator/BiliPai-Windows'
                or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
                or os.environ.get('GITHUB_REF_TYPE') != 'tag'
                or not re.fullmatch(r'rtx-source-[A-Za-z0-9][A-Za-z0-9._-]{0,100}',
                                    os.environ.get('GITHUB_REF_NAME', ''))
                or subprocess.check_output(['git', '-c', 'safe.directory=' + str(ROOT),
                                            'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
                   != os.environ.get('GITHUB_SHA')):
            raise RuntimeError('CI source builds require the exact pre-existing own source tag')
    # Optional absent parameter retains the original complete cold path.
    # Explicit material is fully verified before outputs/download/build.
    fixed_raw = (inputs / 'fixed-inputs.json').read_bytes()
    if presentation and sha(fixed_raw) != '14d9e9ae6e40a81b3f7af47883c5658fa7907821f66d16f0907ed26ddc921cd0':
        raise RuntimeError('Fixed presentation producer inputs changed')
    fixed = json.loads(fixed_raw)
    if fixed.get('variant') != variant:
        raise RuntimeError('Selected producer and fixed input variants differ')
    import_helper_path = ROOT / 'desktop/tools/native/import-host-llvm-source-snapshot.py'
    import_helper_raw = import_helper_path.read_bytes()
    if sha(import_helper_raw) != 'c8c72cffb482464b8fcca0abc481b1dad8d3a0e2a6c005dc7fdcc0d3ee9e8718':
        raise RuntimeError('Reviewed shared importer/collector source changed')
    import_spec = importlib.util.spec_from_file_location('bilipai_host_import', import_helper_path)
    if import_spec is None or import_spec.loader is None:
        raise RuntimeError('Reviewed import source unavailable')
    import_module = importlib.util.module_from_spec(import_spec)
    import_spec.loader.exec_module(import_module)
    sources, build, clang = workspace / 'sources', workspace / 'build-x64', workspace / 'clang-root'
    original_command = ['cmake', '-DTARGET_ARCH=x86_64-w64-mingw32', '-DCOMPILER_TOOLCHAIN=clang',
                        '-DGCC_ARCH=x86-64', '-DMAKEJOBS=2', '-DCLANG_PACKAGES_LTO=ON',
                        '-DCMAKE_INSTALL_PREFIX=' + str(clang),
                        '-DMINGW_INSTALL_PREFIX=' + str(build / 'x86_64-w64-mingw32'),
                        '-DSINGLE_SOURCE_LOCATION=' + str(sources),
                        '-DRUSTUP_LOCATION=' + str(workspace / 'rustup'),
                        '-G', 'Ninja', '--fresh', '-B', str(build),
                        '-S', str(workspace / fixed['recipeArchivePrefix'])]
    import_plan = import_module.inspect_optional(args.host_llvm_snapshot_dir, ROOT, workspace, fixed, IMAGE, original_command)
    if workspace.exists():
        raise RuntimeError('Use a new task-owned workspace; old source/build caches are not accepted')
    if workspace == ROOT or ROOT in workspace.parents and workspace == ROOT / 'desktop':
        raise RuntimeError('The build workspace cannot replace repository sources')
    workspace.mkdir(parents=True)
    workspace_stat = workspace.stat()
    workspace_identity = (workspace_stat.st_dev, workspace_stat.st_ino)
    output.mkdir(parents=True, exist_ok=True)
    if list(output.iterdir()):
        raise RuntimeError('Use an empty output directory')
    output_stat = output.stat()
    output_identity = (output_stat.st_dev, output_stat.st_ino)
    log = output / 'native-build.log'
    status = {'schema': 1, 'variant': variant, 'success': False, 'binaryProduced': False,
              'reproducible': False, 'gpuOrDriverTested': False}
    try:
        archives = workspace / 'archives'
        archives.mkdir()
        downloaded = {row['kind']: download(row, archives) for row in fixed['archives']}
        recipes = extract_fixed_recipe(downloaded['recipes'], workspace, fixed['recipeArchivePrefix'])
        recipe_raw = (inputs / 'recipe-edits.json').read_bytes()
        if presentation and sha(recipe_raw) != '4a794257cc8881f3859405148cf59c8f69ce692b8fdbcb3d844b26604c10363e':
            raise RuntimeError('Complete presentation build recipe edits changed')
        edits = json.loads(recipe_raw)
        apply_recipes(recipes, edits['targets'])
        libvpl_spec = None
        if presentation:
            libvpl_spec = fixed['libvplCompatibility']
            if libvpl_spec != {'afterBytes': 1224, 'afterSha256': 'f49130c9a394c16395788c5133fab49e2f2d95973460aa41d1208f1146fe475b', 'beforeBytes': 1203, 'beforeSha256': 'bb1913bed7c1d6ab7cd3fcc12e3d53fa679d6ffca7425186da5c742b1d82e433', 'helperSha256': '0e045699089718612dd48feec9e92a9039aac8377458e21f0fc2aac6cc287d11', 'helperSourcePath': 'desktop/tools/native/patch-libvpl-mingw-compat.py', 'sourceCommit': '674d015bcb294bc39fa276e99a652ea045423e82', 'sourceTree': '7f4e8143a3960b3dfd2f525842a69953c1ff5ca5', 'targetPath': 'libvpl/src/windows/mfx_dispatcher_defs.h'}:
                raise RuntimeError('Reviewed libvpl compatibility source contract changed')
            libvpl_helper = (ROOT / libvpl_spec['helperSourcePath']).read_bytes()
            if sha(libvpl_helper) != libvpl_spec['helperSha256']:
                raise RuntimeError('Reviewed libvpl compatibility helper changed')
            (recipes / 'packages/bilipai-libvpl-mingw-compat.py').write_bytes(libvpl_helper)
        curl_libssh_spec = None
        if presentation:
            curl_libssh_spec = fixed['curlLibsshCompatibility']
            if curl_libssh_spec != {'curlCommit': '098d3a0d4044d8a3f0a8617a5a5a30cde90fba26', 'curlTree': '7fa155649a35598bc9952c59e0a9964f7b4f9590', 'libsshCommit': '7b3ba877209ae2355f12f4a7ab56022337caaaf5', 'libsshTree': '965f5cee2228e3d65ed66e6777741babbd286dab', 'targetPath': 'lib/vssh/ssh.h', 'beforeBytes': 8905, 'beforeSha256': 'b83762900d9930c20d80c2fc610a990304e805b9b151e5e28578eef8138626d9', 'afterBytes': 8929, 'afterSha256': '2ca85b8d650cbc7fcaf9ee759d27ff0e9a82707360827db0159b5f9bcc374007', 'libsshHeaders': {'libssh.h': {'sourcePath': 'include/libssh/libssh.h', 'bytes': 39665, 'sha256': 'e62f0421e365e50062c95dd07282aab4ee7746433fe8b82927786b6b2d7c5728'}, 'scp.h': {'sourcePath': 'include/libssh/scp.h', 'bytes': 3388, 'sha256': '482ba2f1d6a3249f6a165ee0b4b720ce98815a7ce5111f29b8265ae4616b8083'}}, 'helperSourcePath': 'desktop/tools/native/patch-curl-libssh-scp-compat.py', 'helperSha256': '752fd825bc3ecfac485ba28819e610a6bc912c2e8061c9ee4bedca5050bcd441'}:
                raise RuntimeError('Reviewed curl/libssh compatibility source contract changed')
            curl_libssh_helper = (ROOT / curl_libssh_spec['helperSourcePath']).read_bytes()
            if sha(curl_libssh_helper) != curl_libssh_spec['helperSha256']:
                raise RuntimeError('Reviewed curl/libssh compatibility helper changed')
            (recipes / 'packages/bilipai-curl-libssh-scp-compat.py').write_bytes(curl_libssh_helper)
            if {row['targetPath']: row['afterSha256Bytes'] for row in edits['targets']} != fixed['expectedPatchedRecipeSha256']:
                raise RuntimeError('Complete selected dependency recipe inventory changed')
        curl_openssl_module = None
        if presentation:
            curl_openssl_spec = fixed['curlOpenSslCompatibility']
            if curl_openssl_spec != {'curlCommit': '098d3a0d4044d8a3f0a8617a5a5a30cde90fba26', 'curlTree': '7fa155649a35598bc9952c59e0a9964f7b4f9590', 'opensslBaseCommit': 'd8bf6cdd4849925c30e4f1911c7acb49cb34b702', 'targetPath': 'lib/vtls/openssl.c', 'beforeBytes': 172763, 'beforeSha256': 'a1cb83d9e2be60d96d7c3e9b60337dcaa9cf4bec4075dd15cb51438f3e78278a', 'afterBytes': 172755, 'afterSha256': '7978fa5870d545cfb8072f17d5429c9a5b5fdb8204f00ff048c64bcb9af0f033', 'asn1TemplateSha256': 'b92fdb5214505c17ea3f582c615a391705185563ecef3bcc52ed66ba14895d88', 'versionSha256': 'ddcd76798d2650c9808815cd7c2bfc991056cd8471bfbf156068dfac52f319dd', 'helperSourcePath': 'desktop/tools/native/patch-curl-openssl-asn1-compat.py', 'helperSha256': 'f3038f7aaba6ed47442ebff88e4c9bad77d7f2612af834a245145bfaa68719ac'}:
                raise RuntimeError('Reviewed curl/OpenSSL source contract changed')
            curl_openssl_helper_path = ROOT / curl_openssl_spec['helperSourcePath']
            curl_openssl_helper = curl_openssl_helper_path.read_bytes()
            if sha(curl_openssl_helper) != curl_openssl_spec['helperSha256']:
                raise RuntimeError('Reviewed curl/OpenSSL helper changed')
            (recipes / 'packages/bilipai-curl-openssl-asn1-compat.py').write_bytes(curl_openssl_helper)
            openssl_module_spec = importlib.util.spec_from_file_location('bilipai_curl_openssl', curl_openssl_helper_path)
            if openssl_module_spec is None or openssl_module_spec.loader is None:
                raise RuntimeError('Reviewed curl/OpenSSL source validator unavailable')
            curl_openssl_module = importlib.util.module_from_spec(openssl_module_spec)
            openssl_module_spec.loader.exec_module(curl_openssl_module)
        # HOST LLVM is one common recipe slice, independent of the MPV filter variant.
        # Its source identity is not rewritten to pretend it is presentation MPV.
        snapshot_inputs_path = ROOT / 'desktop/third-party/libmpv/build/rtx-core-v1/host-llvm-snapshot-inputs.json'
        snapshot_inputs_raw = snapshot_inputs_path.read_bytes()
        if sha(snapshot_inputs_raw) != '2d3e0d23fbea6c03e5cc06adee97479995bbef7932cd8d51ceb99aedb0c7a448':
            raise RuntimeError('Shared host LLVM lifecycle inputs changed')
        snapshot_inputs = json.loads(snapshot_inputs_raw)
        if (snapshot_inputs.get('schema') != 2 or snapshot_inputs.get('scope') != 'HOST_LLVM_EXPORT_ONLY_NO_IMPORT'
                or snapshot_inputs.get('sourceBindingSchema') != 1
                or snapshot_inputs.get('sourceBindingState') != 'PREBUILD_INSTALL_POSTCLEANUP_SOURCE_AND_CONFIG_BOUND'
                or snapshot_inputs.get('sourceCaptureStages') != ['AFTER_CONFIGURATION_BEFORE_REAL_LLVM_BUILD',
                    'AFTER_REAL_LLVM_INSTALL_BEFORE_RECIPE_CLEANUP']
                or snapshot_inputs.get('variant') != VARIANT or snapshot_inputs.get('containerImage') != IMAGE
                or snapshot_inputs.get('recipeCommit') != fixed['recipeCommit']
                or snapshot_inputs.get('helperSourcePath') != 'desktop/tools/native/export-host-llvm-source-snapshot.py'
                or len(snapshot_inputs.get('recipeEdits', [])) != 1
                or snapshot_inputs['recipeEdits'][0].get('targetPath') != 'build-recipes/toolchain/llvm/llvm.cmake'):
            raise RuntimeError('Wrong reviewed host LLVM export inputs')
        snapshot_helper_path = ROOT / snapshot_inputs['helperSourcePath']
        snapshot_helper = snapshot_helper_path.read_bytes()
        if sha(snapshot_helper) != snapshot_inputs['helperSha256']:
            raise RuntimeError('Reviewed host LLVM exporter bytes changed')
        apply_recipes(recipes, snapshot_inputs['recipeEdits'])
        (recipes / 'packages/bilipai-host-llvm-source-snapshot.py').write_bytes(snapshot_helper)
        snapshot_spec = importlib.util.spec_from_file_location('bilipai_host_llvm_snapshot', snapshot_helper_path)
        if snapshot_spec is None or snapshot_spec.loader is None:
            raise RuntimeError('Reviewed host LLVM export module cannot be loaded')
        snapshot_module = importlib.util.module_from_spec(snapshot_spec)
        snapshot_spec.loader.exec_module(snapshot_module)
        (recipes / 'packages/bilipai-host-llvm-import.py').write_bytes(import_helper_raw)
        (recipes / 'packages/host-llvm-import-trust.json').write_bytes((ROOT / import_module.CATALOG_PATH).read_bytes())
        import_control_sha = None
        if import_plan is not None:
            llvm_recipe = recipes / 'toolchain/llvm/llvm.cmake'
            cold_recipe = llvm_recipe.read_bytes()
            if sha(cold_recipe) != import_module.LLVM_RECIPE_SHA256:
                raise RuntimeError('Complete shared cold LLVM recipe changed')
            # Fresh real validation receipt output; no original build stamps.
            # Each subsequent Windows/static target keeps normal dependency semantics.
            validation_recipe = (
                'if(DEFINED BILIPAI_HOST_LLVM_IMPORT_CONTROL)\n'
                '    set(clang_version "22")\n'
                '    add_custom_command(OUTPUT "${BILIPAI_HOST_LLVM_IMPORT_VALIDATION_RECEIPT}"\n'
                '        COMMAND python3 ${PROJECT_SOURCE_DIR}/packages/bilipai-host-llvm-import.py\n'
                '            validate-target --control "${BILIPAI_HOST_LLVM_IMPORT_CONTROL}"\n'
                '            --control-sha256 "${BILIPAI_HOST_LLVM_IMPORT_CONTROL_SHA256}"\n'
                '            --workspace "${CMAKE_BINARY_DIR}/.." --source-dir "${SOURCE_LOCATION}"\n'
                '            --install-dir "${CMAKE_INSTALL_PREFIX}"\n'
                '            --repository-root "${BILIPAI_HOST_LLVM_IMPORT_REPOSITORY_ROOT}"\n'
                '        DEPENDS "${BILIPAI_HOST_LLVM_IMPORT_CONTROL}"\n'
                '            "${PROJECT_SOURCE_DIR}/packages/bilipai-host-llvm-import.py"\n'
                '        VERBATIM)\n'
                '    add_custom_target(llvm DEPENDS "${BILIPAI_HOST_LLVM_IMPORT_VALIDATION_RECEIPT}")\n'
                '    set_property(TARGET llvm PROPERTY _EP_SOURCE_DIR "${SOURCE_LOCATION}")\n'
                '    get_property(LLVM_SRC TARGET llvm PROPERTY _EP_SOURCE_DIR)\n'
                '    return()\n'
                'endif()\n').encode()
            llvm_recipe.write_bytes(validation_recipe + cold_recipe)
        helper = (inputs / helper_leaf).read_bytes()
        native_patch_path = ((inputs / 'bilipai-nvidia-native-69e63f.patch') if presentation else
                             (ROOT / 'desktop/third-party/libmpv/patches/nvidia-native-resolution-69e63f.patch'))
        native_patch = native_patch_path.read_bytes()
        if sha(helper) != fixed['buildPatchHelperSha256'] or sha(native_patch) != fixed['nativePatchSha256']:
            raise RuntimeError('Ownrepo patch/helper identity mismatch')
        (recipes / 'packages/bilipai-veyra-rtx-core-patch.py').write_bytes(helper)
        manifest_raw = (inputs / manifest_leaf).read_bytes()
        if sha(manifest_raw) != fixed['filterSourceManifestSha256']:
            raise RuntimeError('Fixed bridge source manifest changed')
        manifest = json.loads(manifest_raw)
        if manifest['variant'] != variant or manifest['sourceCommit'] != fixed['sourceCommit']:
            raise RuntimeError('Wrong bridge source manifest variant/source')
        registration_raw = (inputs / 'filter-registration-edits.json').read_bytes()
        if sha(registration_raw) != manifest['registrationEditsSha256']:
            raise RuntimeError('Fixed bridge registration bytes changed')
        (recipes / 'packages' / manifest_leaf).write_bytes(manifest_raw)
        upstream_rows = []
        upstream_raw = b''
        if presentation:
            if manifest.get('schema') != 2 or type(manifest.get('tokenProtocol')) is not int or manifest.get('tokenProtocol') != 2 or manifest.get('presentationProperty') != 'bilipai-rtx-presentation' or len(manifest['sourceFiles']) != 21:
                raise RuntimeError('Presentation source protocol/private inventory differs')
            upstream_raw = (inputs / 'presentation-edits.json').read_bytes()
            if sha(upstream_raw) != manifest['upstreamEditsSha256'] or sha(upstream_raw) != 'e84fd26d22eb7ac012747960d72ae384191e93dc5ab68a0d1e81fa9dfaf3d34f':
                raise RuntimeError('Presentation complete upstream edit graph changed')
            upstream_rows = json.loads(upstream_raw)
            if len(upstream_rows) != 24:
                raise RuntimeError('Presentation needs all twenty-four reviewed upstream complete files')
            (recipes / 'packages/presentation-edits.json').write_bytes(upstream_raw)
        (recipes / 'packages/filter-registration-edits.json').write_bytes(registration_raw)
        bridge_sources = recipes / 'packages/bridge-source'
        bridge_sources.mkdir()
        for row in manifest['sourceFiles']:
            actual_source = (ROOT / row['sourcePath']).resolve()
            if ROOT not in actual_source.parents:
                raise RuntimeError('Bridge source escaped owned repository')
            data = actual_source.read_bytes()
            if sha(data) != row['sha256'] or len(data) != row['bytes']:
                raise RuntimeError('Reviewed bridge source/header changed')
            (bridge_sources / row['fileName']).write_bytes(data)
        (recipes / 'packages/bilipai-nvidia-native-69e63f.patch').write_bytes(native_patch)
        sources.mkdir()
        command = list(original_command)
        if import_plan is not None:
            import_control, import_control_sha = import_module.prepare_import(
                import_plan, workspace, output, ROOT, fixed, IMAGE, original_command)
            command[1:1] = ['-DBILIPAI_HOST_LLVM_IMPORT_CONTROL=' + str(import_control),
                            '-DBILIPAI_HOST_LLVM_IMPORT_CONTROL_SHA256=' + import_control_sha,
                            '-DBILIPAI_HOST_LLVM_IMPORT_REPOSITORY_ROOT=' + str(ROOT),
                            '-DBILIPAI_HOST_LLVM_IMPORT_VALIDATION_RECEIPT=' + str(output / 'host-llvm-import-target-validation.json')]
        run(command, log)
        # Actual cold-build targets from the fixed cd1 README and toolchain recipes.
        # Never call the vendor 'update' target, which moves dependency HEADs.
        for target in ['llvm', 'rustup', 'llvm-clang', 'mpv']:
            print('DISK before ' + target + ': freeBytes=' + str(shutil.disk_usage(workspace).free), flush=True)
            pending_environment = None
            if target == 'llvm' and import_plan is None:
                pending_environment = import_module.capture_cold_environment()
            target_command = ['ninja', '-C', str(build), '-j2', target]
            run(target_command, log)
            if target == 'llvm':
                if import_plan is None:
                    # Genuine cold target exit 0; original e23/d349 binding.
                    host_snapshot_descriptor = snapshot_module.export_snapshot(
                        workspace=workspace, output=output, repository_root=ROOT,
                        fixed=fixed, snapshot_inputs=snapshot_inputs, container_image=IMAGE,
                        build_command=original_command, log=log)
                    original_environment = import_module.finish_cold_environment(
                        output, host_snapshot_descriptor, pending_environment)
                    import_receipt = None
                else:
                    # Real validation target succeeded; retain original source
                    # commit/tag/run and all original snapshot bytes unchanged.
                    host_snapshot_descriptor = output / 'host-llvm-source-snapshot-descriptor.json'
                    original_environment = output / 'host-llvm-original-environment.json'
                    import_receipt = import_module.finish_import(import_plan, output, import_control_sha, target_command)
                host_snapshot_source = json.loads(host_snapshot_descriptor.read_text(encoding='utf-8'))
                if (host_snapshot_source.get('schema') != 2 or host_snapshot_source.get('sourceBindingSchema') != 1
                        or host_snapshot_source.get('actualSourceBuildBinding', {}).get('state')
                            != 'PREBUILD_INSTALL_POSTCLEANUP_SOURCE_AND_CONFIG_BOUND'):
                    raise RuntimeError('Actual host build source lifecycle is not bound')
                # A completed HOST target survives a later native dependency failure.
                # This receipt is local evidence, never an import trust record or MPV success.
                host_receipt = {'schema': 1, 'kind': 'BILIPAI_HOST_LLVM_BUILD_RECEIPT',
                    'state': 'LOCAL_HOST_TARGET_SUCCEEDED_NATIVE_BUILD_NOT_ASSERTED',
                    'variant': variant, 'ownrepoSourceCommit': os.environ.get('GITHUB_SHA'),
                    'ownrepoSourceTag': os.environ.get('GITHUB_REF_NAME'),
                    'workflowRunId': os.environ.get('GITHUB_RUN_ID'),
                    'workflowRunAttempt': os.environ.get('GITHUB_RUN_ATTEMPT'),
                    'producerSourceSha256': file_sha(Path(__file__)),
                    'snapshotInputsSha256': sha(snapshot_inputs_raw),
                    'importCollectorSourceSha256': sha(import_helper_raw),
                    'containerImage': IMAGE, 'recipeCommit': fixed['recipeCommit'],
                    'recipeArchiveSha256': sha(downloaded['recipes'].read_bytes()),
                    'hostLlvmSnapshotDescriptorSha256': file_sha(host_snapshot_descriptor),
                    'hostLlvmSnapshotSourceBindingSha256': host_snapshot_source['actualSourceBuildBindingSha256'],
                    'hostLlvmSnapshotStatus': import_module.IMPORT_STATUS if import_plan is not None else 'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY',
                    'hostLlvmToolchainImported': import_plan is not None,
                    'hostLlvmFreshCompileExecuted': import_plan is None,
                    'hostLlvmOriginalEnvironmentSha256': file_sha(original_environment),
                    'hostLlvmImportReceiptSha256': file_sha(import_receipt) if import_receipt is not None else None,
                    'hostLlvmActualRecipeSha256': file_sha(recipes / 'toolchain/llvm/llvm.cmake'),
                    'hostLlvmAccelerationMeasured': False, 'reuseReady': False,
                    'nativeBuildSucceeded': False, 'binaryProduced': False,
                    'gpuOrDriverTested': False, 'closedSdkOrRuntimeIncluded': False}
                with (output / 'host-llvm-build-receipt.json').open('x', encoding='utf-8') as stream:
                    stream.write(json.dumps(host_receipt, sort_keys=True, indent=2) + '\n')
        candidates = list(build.glob('mpv-dev-x86_64-*-git-*/libmpv-2.dll'))
        if len(candidates) != 1:
            raise RuntimeError('Expected exactly one actual mpv copy-package-dir DLL output')
        dll = candidates[0].read_bytes()
        imports = pe_imports(dll)
        unsupported = [row for row in imports if row['name'][:-4] not in SYSTEM_DLLS
                       and not row['name'].startswith(('api-ms-win-', 'ext-ms-win-'))
                       and not (row['delayLoaded'] and row['name'] in ('vapoursynth.dll', 'vsscript.dll'))]
        if unsupported:
            # Existing desktop staging is one libmpv DLL. Do not silently ship a new,
            # unproven companion dependency or download an SDK to satisfy it.
            raise RuntimeError('Native output requires unsupported companion DLLs: ' + json.dumps(unsupported))
        source_receipt = json.loads(candidates[0].with_name('bilipai-native-patch-receipt.json').read_text())
        expected = {'patchId': variant, 'sourceCommit': fixed['sourceCommit'],
                    'filterName': fixed['filterName'], 'filterSourceManifestSha256': fixed['filterSourceManifestSha256'],
                    'coreAbiHeaderSha256': fixed['coreAbiHeaderSha256']}
        if any(source_receipt.get(key) != value for key, value in expected.items()):
            raise RuntimeError('Source receipt beside actual DLL does not match the selected patch')
        if source_receipt.get('schema') != (3 if presentation else 2) or source_receipt.get('filterSourceFiles') != manifest['sourceFiles']:
            raise RuntimeError('Actual bridge source receipt does not match complete source/header inventory')
        registration_rows = json.loads(registration_raw)
        expected_registration = [{'path': r['path'], 'beforeSha256': r['beforeSHA256'], 'afterSha256': r['afterSHA256']} for r in registration_rows]
        if presentation:
            nvidia = manifest['originalNvidiaPatch']
            expected_graph = [{'path': nvidia['sourcePath'], 'beforeSha256': nvidia['originalSha256'], 'afterSha256': nvidia['patchedSha256']}] + expected_registration + [{'path': row['path'], 'beforeSha256': row['beforeSha256'], 'afterSha256': row['afterSha256']} for row in upstream_rows]
            if len(expected_graph) != 28 or len({row['path'] for row in expected_graph}) != 28 or source_receipt.get('sourceGraph') != expected_graph:
                raise RuntimeError('Actual presentation complete-file graph does not match all twenty-eight fixed targets')
            if type(source_receipt.get('tokenProtocol')) is not int or source_receipt.get('tokenProtocol') != 2 or source_receipt.get('presentationProperty') != 'bilipai-rtx-presentation' or source_receipt.get('sourcePatchHelperSha256') != '748199ed370c169b17337154a3f7d0fede10a1c9420b1a8b02b3844aa6e6bebf' or source_receipt.get('gpuExecuted') is not False or source_receipt.get('displayProofRuntimeVerified') is not False:
                raise RuntimeError('Actual presentation source receipt protocol differs')
            if b'bilipai-rtx-presentation' not in dll:
                raise RuntimeError('Actual PE lacks the registered presentation property identity')
        elif source_receipt.get('registrations') != expected_registration or source_receipt.get('patchedSourceSha256') != fixed['patchedNativeSourceSha256'] or source_receipt.get('patchSha256') != fixed['nativePatchSha256']:
            raise RuntimeError('Actual original RTX registration/NVIDIA receipt differs from reviewed complete file hashes')
        if not any(row['name'] == 'd3d12.dll' for row in imports) or b'bilipai-rtx' not in dll:
            raise RuntimeError('Actual PE lacks the expected D3D12 import or registered filter identity string')
        dll_sha = sha(dll)
        if dll_sha == fixed['originalDllSha256']:
            raise RuntimeError('The original unpatched DLL is not a patched candidate')
        libvpl_install_receipt = None
        libvpl_materials = None
        if presentation:
            libvpl_material_directory = build / 'bilipai-libvpl-compat-source'
            libvpl_install_receipt, libvpl_materials = checked_libvpl_materials(libvpl_material_directory, libvpl_spec)
        curl_libssh_install_receipt = None
        curl_libssh_materials = None
        if presentation:
            curl_libssh_material_directory = build / 'bilipai-curl-libssh-compat-source'
            curl_libssh_install_receipt, curl_libssh_materials = checked_curl_libssh_materials(curl_libssh_material_directory, curl_libssh_spec)
        curl_openssl_install_receipt = None
        curl_openssl_materials = None
        if presentation:
            curl_openssl_install_receipt, curl_openssl_materials = curl_openssl_module.validate_materials(
                build / 'bilipai-curl-openssl-compat-source')
        inventory = dependency_inventory(sources)
        if presentation:
            for dependency, commit in [('curl', curl_libssh_spec['curlCommit']), ('libssh', curl_libssh_spec['libsshCommit']),
                                       ('libaribcaption', fixed['libaribcaptionCommit'])]:
                selected = [row for row in inventory if row['directory'] == dependency]
                if len(selected) != 1 or selected[0]['commit'] != commit:
                    raise RuntimeError('Actual retained dependency commit differs from selected presentation input')
        if import_plan is not None:
            original_source = import_plan['manifest']['actualSourceBuildBinding']['prebuild']
            inventory.append({'directory': 'llvm', 'commit': original_source['sourceCommit'],
                              'tree': original_source['sourceTree'], 'remote': original_source['sourceRemote'],
                              'origin': 'VERIFIED_ORIGINAL_SOURCE_SNAPSHOT_NO_GIT_CHECKOUT_OR_FRESH_LLVM_COMPILE',
                              'originalSnapshotSourceCommit': import_plan['record']['sourceCommit'],
                              'originalSnapshotSourceTag': import_plan['record']['sourceTag'],
                              'actualRestoredSourceTreeManifestSha256': original_source['actualSourceWorktreeManifestSha256']})
        source_bundle = output / (variant + '-source-materials.tar.gz')
        def without_git(info):
            return None if '.git' in Path(info.name).parts else info
        with tarfile.open(source_bundle, 'w:gz', dereference=False) as source_tar:
            source_tar.add(archives, arcname='fixed-archives')
            source_tar.add(recipes, arcname='modified-build-recipes', filter=without_git)
            source_tar.add(sources, arcname='actual-dependency-worktrees', filter=without_git)
            if libvpl_materials is not None:
                # Cleanup restores the checkout; these complete patched header
                # materials were separately verified after the real install.
                for material_name, material_raw in sorted(libvpl_materials.items()):
                    info = tarfile.TarInfo('actual-libvpl-patched-source/' + material_name)
                    info.size = len(material_raw)
                    info.mode = 0o600
                    import io
                    source_tar.addfile(info, io.BytesIO(material_raw))
            if curl_libssh_materials is not None:
                # Both source/install observations precede each recipe cleanup.
                for material_name, material_raw in sorted(curl_libssh_materials.items()):
                    info = tarfile.TarInfo('actual-curl-libssh-compat-source/' + material_name)
                    info.size = len(material_raw)
                    info.mode = 0o600
                    import io
                    source_tar.addfile(info, io.BytesIO(material_raw))
            if curl_openssl_materials is not None:
                # Actual installed generated OpenSSL header and curl build source survive cleanup.
                for material_name, material_raw in sorted(curl_openssl_materials.items()):
                    info = tarfile.TarInfo('actual-curl-openssl-compat-source/' + material_name)
                    info.size = len(material_raw)
                    info.mode = 0o600
                    import io
                    source_tar.addfile(info, io.BytesIO(material_raw))
            source_tar.add(inputs, arcname='ownrepo-build-inputs')
            source_tar.add(ROOT / 'desktop/third-party/libmpv/build/rtx-core-v1/host-llvm-snapshot-inputs.json', arcname='shared-host-llvm-snapshot-inputs.json')
            source_tar.add(Path(__file__), arcname='ownrepo-build-mpv-runtime.py')
            source_tar.add(ROOT / import_module.CATALOG_PATH, arcname='shared-host-llvm-import-trust.json')
            if import_plan is not None:
                # Original complete canonical LLVM source remains an unchanged
                # companion draft asset, in addition to this new MPV source bundle.
                source_tar.add(output / 'host-llvm-import-receipt.json', arcname='original-host-import-receipt.json')
            source_tar.add(ROOT / 'desktop/third-party/libmpv/patches', arcname='ownrepo-native-patches')
        bundle_sha = file_sha(source_bundle)
        tools = {}
        for label, executable in [('cmake', 'cmake'), ('ninja', 'ninja'), ('python', 'python3'),
                                  ('crossClang', str(clang / 'bin/clang'))]:
            tools[label] = subprocess.check_output([executable, '--version'], text=True).strip()
        receipt = {'schema': 2, 'variant': variant, 'sourceCommit': fixed['sourceCommit'],
                   'nativePatchSha256': fixed['nativePatchSha256'],
                   'filterName': fixed['filterName'], 'filterSourceManifestSha256': fixed['filterSourceManifestSha256'],
                   'coreAbiHeaderSha256': fixed['coreAbiHeaderSha256'], 'filterSourceFiles': manifest['sourceFiles'],
                   'originalNativeSourceSha256': fixed['originalNativeSourceSha256'],
                   'patchedNativeSourceSha256': fixed['patchedNativeSourceSha256'],
                   'recipeCommit': fixed['recipeCommit'], 'ffmpegCommit': fixed['ffmpegCommit'],
                   'recipeArchiveSha256': next(row['sha256'] for row in fixed['archives'] if row['kind'] == 'recipes'),
                   'patchedRecipeSha256': {row['targetPath']: row['afterSha256Bytes'] for row in edits['targets']},
                   'ownrepoSourceCommit': os.environ.get('GITHUB_SHA'), 'containerImage': IMAGE,
                   'buildCommand': command, 'actualDependencySources': inventory, 'actualTools': tools,
                   'dllSha256': dll_sha, 'peImports': imports, 'sourceBundleSha256': bundle_sha,
                   'sourceMaterialScope': 'Fixed archives, altered recipes, active dependency source worktrees excluding .git',
                   'floatingDependencies': True, 'reproducible': False,
                   'gpuOrDriverTested': False, 'nativeResolutionPpeVerified': False, 'rtxCoreBridgeVerified': False,
                   'closedSdkOrRuntimeIncluded': False, 'vfgImplemented': False,
                   'hostLlvmSnapshotDescriptorSha256': file_sha(host_snapshot_descriptor),
                   'hostLlvmSnapshotSourceBindingSha256': host_snapshot_source['actualSourceBuildBindingSha256'],
                   'hostLlvmSnapshotStatus': import_module.IMPORT_STATUS if import_plan is not None else 'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY',
                   'hostLlvmToolchainImported': import_plan is not None,
                   'hostLlvmFreshCompileExecuted': import_plan is None,
                   'hostLlvmOriginalEnvironmentSha256': file_sha(original_environment),
                   'hostLlvmImportReceiptSha256': file_sha(import_receipt) if import_receipt is not None else None,
                   'hostLlvmActualRecipeSha256': file_sha(recipes / 'toolchain/llvm/llvm.cmake'),
                   'hostLlvmAccelerationMeasured': False}
        presentation_identity = {}
        if presentation:
            presentation_identity = {'presentationProtocolVersion': 2, 'presentationProperty': 'bilipai-rtx-presentation', 'upstreamEditsSha256': sha(upstream_raw), 'sourcePatchHelperSha256': sha(helper)}
            receipt.update(presentation_identity)
            receipt['sourceGraph'] = source_receipt['sourceGraph']
            receipt['sourceMaterialScope'] = 'Fixed archives, altered recipes, dependency worktrees after cleanup, and separately retained patched libvpl, curl/libssh and curl/OpenSSL generated-header/source/install witnesses; excluding .git'
            receipt['libvplCompatibilitySource'] = libvpl_install_receipt
            receipt['curlLibsshCompatibilitySource'] = curl_libssh_install_receipt
            receipt['curlOpenSslCompatibilitySource'] = curl_openssl_install_receipt
        receipt_bytes = (json.dumps(receipt, sort_keys=True, indent=2) + '\n').encode()
        catalog_path = ROOT / 'desktop/third-party/libmpv/SOURCES.json'
        catalog = json.loads(catalog_path.read_text(encoding='utf-8-sig'))
        catalog['originalBinaryBaseline'] = catalog.pop('binary')
        catalog['binary'] = {'variant': variant, 'dllSha256': dll_sha, 'sourceBundleSha256': bundle_sha,
                             'artifactIdentity': 'See the independently generated runtime-descriptor.json'}
        entries = {'libmpv-2.dll': dll, 'licenses/build-receipt.json': receipt_bytes,
                   'licenses/native-patch-receipt.json': (json.dumps(source_receipt, sort_keys=True, indent=2) + '\n').encode(),
                   'licenses/SOURCES.json': (json.dumps(catalog, sort_keys=True, indent=2) + '\n').encode(),
                   'licenses/NOTICES.md': ('This is a modified libmpv candidate: ' + variant + '\n'
                       'mpv ' + fixed['sourceCommit'] + ', native patch ' + fixed['nativePatchSha256'] + '\n'
                       'License inventory retained from the fixed baseline. Actual source/build identity is in build-receipt.json.\n'
                       'Corresponding source materials SHA256: ' + bundle_sha + '\n'
                       'RTX core frame bridge and visible effect are unverified; no NVIDIA SDK/runtime is included.\n').encode()}
        gpl3 = ROOT / 'desktop/native/veyra-core/upstream/LICENSE'
        gpl3_raw = gpl3.read_bytes()
        if sha(gpl3_raw) != fixed['bridgeGpl3LicenseSha256']:
            raise RuntimeError('Reviewed GPL3 source notice changed')
        entries['licenses/bilipai-veyra-core-GPL3.txt'] = gpl3_raw
        entries['licenses/rtx-filter-source-manifest.json'] = manifest_raw
        if presentation:
            entries['licenses/rtx-presentation-edits.json'] = upstream_raw
            entries['licenses/rtx-registration-edits.json'] = registration_raw
        license_root = catalog_path.parent.resolve()
        for record in catalog['licenseFiles']:
            license_path = (license_root / record['path']).resolve()
            if license_root not in license_path.parents:
                raise RuntimeError('License path escaped the existing inventory')
            content = license_path.read_bytes()
            if sha(content) != record['sha256']:
                raise RuntimeError('Existing native license bytes changed')
            entries['licenses/' + record['path']] = content
        artifact = output / (variant + '-x64.zip')
        with zipfile.ZipFile(artifact, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(entries.items()):
                archive.writestr(name, data)
        descriptor = {'schema': 2, 'variant': variant, 'architecture': 'windows-x64',
                      'sourceCommit': receipt['sourceCommit'], 'nativePatchSha256': receipt['nativePatchSha256'],
                      'filterName': fixed['filterName'], 'filterSourceManifestSha256': fixed['filterSourceManifestSha256'],
                      'coreAbiHeaderSha256': fixed['coreAbiHeaderSha256'],
                      'originalNativeSourceSha256': receipt['originalNativeSourceSha256'],
                      'patchedNativeSourceSha256': receipt['patchedNativeSourceSha256'],
                      'recipeCommit': receipt['recipeCommit'], 'recipeArchiveSha256': receipt['recipeArchiveSha256'],
                      'containerImage': IMAGE, 'artifact': {'fileName': artifact.name,
                          'archiveSha256': file_sha(artifact), 'dllSha256': dll_sha,
                          'downloadUrls': [], 'entries': [{'path': name, 'bytes': len(data), 'sha256': sha(data)}
                                                         for name, data in sorted(entries.items())]},
                      'buildReceiptSha256': sha(receipt_bytes),
                      'sourceBundle': {'fileName': source_bundle.name, 'sha256': bundle_sha, 'downloadUrl': None},
                      'deliveryStatus': 'LOCAL_ARTIFACT_ONLY_NOT_RELEASED', 'nativeResolutionPpeVerified': False,
                      'rtxCoreBridgeVerified': False, 'closedSdkOrRuntimeIncluded': False, 'vfgImplemented': False}
        descriptor.update(presentation_identity)
        write_json(output / 'runtime-descriptor.json', descriptor)
        write_json(output / 'build-receipt.json', receipt)
        status.update(success=True, binaryProduced=True, artifact=artifact.name,
                      descriptorSha256=sha((output / 'runtime-descriptor.json').read_bytes()))
    except BaseException as error:
        if isinstance(error, subprocess.CalledProcessError):
            try:
                status['nestedBuildDiagnostics'] = print_nested_build_failure_logs(workspace, workspace_identity, output, output_identity)
            except BaseException:
                # Cleanup/IO in best-effort diagnostics must never replace the
                # original build error, including failures from helper finally.
                status['nestedBuildDiagnostics'] = {'state': 'DIAGNOSTICS_UNAVAILABLE'}
        status['errorType'] = type(error).__name__
        status['error'] = str(error)
        raise
    finally:
        write_json(output / 'build-status.json', status)

if __name__ == '__main__':
    main()
