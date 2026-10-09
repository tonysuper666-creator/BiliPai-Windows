#!/usr/bin/env python3
"""Exact, source-only libvpl MinGW compatibility patch and install witness.

Called by the fixed recipe. No compiler, conversion filter, network or QSV
runtime is invoked here. The receipt proves this bounded header relation and
its recipe-owned install observation; it is not a GPU or QSV runtime test.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess

COMMIT = '674d015bcb294bc39fa276e99a652ea045423e82'
TREE = '7f4e8143a3960b3dfd2f525842a69953c1ff5ca5'
TARGET = 'libvpl/src/windows/mfx_dispatcher_defs.h'
BEFORE = base64.b64decode('LyojIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjCiAgIyBDb3B5cmlnaHQgKEMpIEludGVsIENvcnBvcmF0aW9uCiAgIwogICMgU1BEWC1MaWNlbnNlLUlkZW50aWZpZXI6IE1JVAogICMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMqLwoKI3ByYWdtYSBvbmNlCiNpbmNsdWRlIDxjc3RkaW8+CiNpbmNsdWRlIDxjc3RyaW5nPgojaW5jbHVkZSAidnBsL21meGRlZnMuaCIKCiNpZiBkZWZpbmVkKE1GWF9ESVNQQVRDSEVSX0xPRykKICAgICNpbmNsdWRlIDxzdHJpbmcuaD4KICAgICNpbmNsdWRlIDxzdHJpbmc+CiNlbmRpZgoKI2RlZmluZSBNQVhfUExVR0lOX1BBVEggNDA5NgojZGVmaW5lIE1BWF9QTFVHSU5fTkFNRSA0MDk2CgojaWYgX01TQ19WRVIgPCAxNDAwCiAgICAjZGVmaW5lIHdjc2NweV9zKHRvLCB0b19zaXplLCBmcm9tKSBcCiAgICAgICAgKHZvaWQpKHRvX3NpemUpOyAgICAgICAgICAgICAgICBcCiAgICAgICAgd2NzY3B5KHRvLCBmcm9tKQogICAgI2RlZmluZSB3Y3NjYXRfcyh0bywgdG9fc2l6ZSwgZnJvbSkgXAogICAgICAgICh2b2lkKSh0b19zaXplKTsgICAgICAgICAgICAgICAgXAogICAgICAgIHdjc2NhdCh0bywgZnJvbSkKI2VuZGlmCgovLyBkZWNsYXJlIGxpYnJhcnkgbW9kdWxlJ3MgaGFuZGxlCnR5cGVkZWYgdm9pZCAqbWZ4TW9kdWxlSGFuZGxlOwoKdHlwZWRlZiB2b2lkKE1GWF9DREVDTCAqbWZ4RnVuY3Rpb25Qb2ludGVyKSh2b2lkKTsKCi8vIFRyYWNlciB1c2VzIGxpYiBsb2FkaW5nIGZyb20gUHJvZ3JhbSBGaWxlcyBsb2dpYyAodmlhIERpc3BhdGNoIHJlZyBrZXkpIHRvIG1ha2UgZGlzcGF0Y2hlciBsb2FkIHRyYWNlciBkbGwuCi8vIFdpdGggRHJpdmVyU3RvcmUgbG9hZGluZyBwdXQgYXQgMXN0IHBsYWNlLCBkaXNwYXRjaGVyIGxvYWRzIHJlYWwgbGliIGJlZm9yZSBpdCBmaW5kcyB0cmFjZXIgZGxsLgovLyBUaGlzIHdvcmthcm91bmQgZXhwbGljaXRseSBjaGVja3MgdHJhY2VyIHByZXNlbmNlIGluIERpc3BhdGNoIHJlZyBrZXkgYW5kIGxvYWRzIHRyYWNlciBkbGwgYmVmb3JlIHRoZSBzZWFyY2ggZm9yIGxpYiBpbiBhbGwgb3RoZXIgcGxhY2VzLgojZGVmaW5lIE1GWF9UUkFDRVJfV0FfRk9SX0RTIDEK')
OLD = b'#if _MSC_VER < 1400\n'
NEW = b'#if defined(_MSC_VER) && _MSC_VER < 1400\n'
AFTER = BEFORE.replace(OLD, NEW)
BEFORE_SHA256 = 'bb1913bed7c1d6ab7cd3fcc12e3d53fa679d6ffca7425186da5c742b1d82e433'
AFTER_SHA256 = 'f49130c9a394c16395788c5133fab49e2f2d95973460aa41d1208f1146fe475b'
MAX_HEADER = 65536


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def directory(value):
    p = Path(value)
    if not p.is_absolute() or any(x in ('.', '..') for x in p.parts):
        raise RuntimeError('Expected an absolute public source/material directory')
    current = Path(p.anchor)
    for part in p.parts[1:]:
        current = current / part
        s = current.lstat()
        if not stat.S_ISDIR(s.st_mode) or stat.S_ISLNK(s.st_mode):
            raise RuntimeError('Source/material path is not a real directory')
    return p


def read_file(path, maximum=MAX_HEADER):
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= maximum:
            raise RuntimeError('Source/material file type or extent changed')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            raw = stream.read(maximum + 1)
        after = os.fstat(fd)
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            raise RuntimeError('Source/material file changed during read')
        if len(raw) != before.st_size:
            raise RuntimeError('Source/material read is incomplete')
        return raw
    finally:
        os.close(fd)


def write_new(path, raw):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        with os.fdopen(fd, 'wb', closefd=False) as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(fd)
    finally:
        os.close(fd)


def git(source, *args):
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_SYSTEM=os.devnull,
               GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0')
    result = subprocess.run(['git', '-c', 'core.hooksPath=' + os.devnull,
                             '-c', 'core.attributesFile=' + os.devnull,
                             '-c', 'core.autocrlf=false', '-C', str(source), *args],
                            env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode != 0 or len(result.stdout) > MAX_HEADER:
        raise RuntimeError('Exact libvpl source identity check failed')
    return result.stdout


def check_source(source, patched):
    directory(source / 'libvpl/src/windows')
    if git(source, 'rev-parse', 'HEAD').strip().decode('ascii') != COMMIT:
        raise RuntimeError('libvpl checkout is not the selected immutable commit')
    if git(source, 'rev-parse', 'HEAD^{tree}').strip().decode('ascii') != TREE:
        raise RuntimeError('libvpl commit tree changed')
    canonical = git(source, 'show', COMMIT + ':' + TARGET)
    if canonical != BEFORE or sha(canonical) != BEFORE_SHA256:
        raise RuntimeError('Complete canonical libvpl header changed')
    expected = AFTER if patched else BEFORE
    if read_file(source / TARGET) != expected:
        raise RuntimeError('Complete actual libvpl header does not match')
    changed = git(source, 'diff', '--no-ext-diff', '--no-textconv', '--name-only', '-z', 'HEAD', '--').split(b'\0')
    changed = [x for x in changed if x]
    allowed = [TARGET.encode()] if patched else []
    if sorted(changed) != allowed:
        raise RuntimeError('Unexpected tracked libvpl source modifications')


def material_receipt(state):
    return {'schema': 1, 'kind': 'BILIPAI_LIBVPL_MINGW_COMPAT_HEADER_SOURCE',
            'state': state, 'sourceCommit': COMMIT, 'sourceTree': TREE,
            'targetPath': TARGET, 'beforeSha256': BEFORE_SHA256,
            'afterSha256': AFTER_SHA256, 'helperSha256': sha(read_file(Path(__file__))),
            'patchCount': 1, 'qsvRuntimeTested': False, 'gpuExecuted': False}


def encode(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=('patch', 'after-install'))
    p.add_argument('source')
    p.add_argument('materials')
    args = p.parse_args()
    source = directory(args.source)
    materials_path = Path(args.materials)
    if not materials_path.is_absolute() or materials_path == source or source in materials_path.parents:
        raise RuntimeError('Install materials must be outside libvpl source cleanup')
    directory(materials_path.parent)
    if (sha(BEFORE) != BEFORE_SHA256 or sha(AFTER) != AFTER_SHA256
            or BEFORE.count(OLD) != 1 or AFTER.count(NEW) != 1
            or AFTER.replace(NEW, OLD) != BEFORE):
        raise RuntimeError('Reviewed exact compatibility relation changed')
    if args.mode == 'patch':
        if materials_path.exists():
            raise RuntimeError('Use a fresh source build material directory')
        check_source(source, False)
        materials_path.mkdir(mode=0o700)
        materials = directory(materials_path)
        write_new(materials / 'mfx_dispatcher_defs.before.h', BEFORE)
        write_new(materials / 'mfx_dispatcher_defs.after.h', AFTER)
        fd = os.open(source / TARGET, os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0))
        try:
            s = os.fstat(fd)
            if not stat.S_ISREG(s.st_mode) or s.st_size != len(BEFORE):
                raise RuntimeError('libvpl patch target extent changed')
            if os.read(fd, MAX_HEADER + 1) != BEFORE:
                raise RuntimeError('libvpl target bytes changed before patch')
            os.lseek(fd, 0, os.SEEK_SET)
            with os.fdopen(fd, 'wb', closefd=False) as stream:
                stream.write(AFTER)
                stream.truncate()
                stream.flush()
                os.fsync(fd)
        finally:
            os.close(fd)
        check_source(source, True)
        write_new(materials / 'patch-receipt.json', encode(material_receipt('PATCH_APPLIED_BEFORE_CONFIGURE')))
    else:
        materials = directory(materials_path)
        check_source(source, True)
        if read_file(materials / 'mfx_dispatcher_defs.before.h') != BEFORE or read_file(materials / 'mfx_dispatcher_defs.after.h') != AFTER:
            raise RuntimeError('Retained complete source materials changed')
        if read_file(materials / 'patch-receipt.json') != encode(material_receipt('PATCH_APPLIED_BEFORE_CONFIGURE')):
            raise RuntimeError('Exact patch witness changed')
        write_new(materials / 'install-receipt.json', encode(material_receipt('AFTER_REAL_LIBVPL_INSTALL_BEFORE_RECIPE_CLEANUP')))


if __name__ == '__main__':
    main()
