#!/usr/bin/env python3
"""Fixed-source curl/OpenSSL ASN.1 compatibility; recipe-owned source witnesses.

The real OpenSSL install must expose the exact legacy declaration selected by
the fixed template. Its complete generated source and installed header must
match, and both are retained before curl is patched. No version-wide inference,
network, compiler, TLS runtime or GPU action is performed here.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess

CURL_COMMIT = '098d3a0d4044d8a3f0a8617a5a5a30cde90fba26'
CURL_TREE = '7fa155649a35598bc9952c59e0a9964f7b4f9590'
OPENSSL_BASE = 'd8bf6cdd4849925c30e4f1911c7acb49cb34b702'
TARGET = 'lib/vtls/openssl.c'
BEFORE_SHA256 = 'a1cb83d9e2be60d96d7c3e9b60337dcaa9cf4bec4075dd15cb51438f3e78278a'
AFTER_SHA256 = '7978fa5870d545cfb8072f17d5429c9a5b5fdb8204f00ff048c64bcb9af0f033'
BEFORE_BYTES = 172763
AFTER_BYTES = 172755
ASN1_TEMPLATE_SHA256 = 'b92fdb5214505c17ea3f582c615a391705185563ecef3bcc52ed66ba14895d88'
VERSION_SHA256 = 'ddcd76798d2650c9808815cd7c2bfc991056cd8471bfbf156068dfac52f319dd'
SCP_TARGET = 'lib/vssh/ssh.h'
SCP_BEFORE_SHA256 = 'b83762900d9930c20d80c2fc610a990304e805b9b151e5e28578eef8138626d9'
SCP_AFTER_SHA256 = '2ca85b8d650cbc7fcaf9ee759d27ff0e9a82707360827db0159b5f9bcc374007'
MAX_FILE = 262144
LEGACY_DECLARATION = b'int ASN1_STRING_length(const ASN1_STRING *x);'
EDITS = [
    (b'#if OPENSSL_VERSION_NUMBER < 0x40100000L\n'
     b'static size_t ASN1_STRING_get_length(const ASN1_STRING *str)\n'
     b'{\n'
     b'   const int length = ASN1_STRING_length(str);\n'
     b'   return length >= 0 ? (size_t)length : 0;\n'
     b'}\n'
     b'#endif\n',
     b'static size_t bilipai_ASN1_STRING_get_length(const ASN1_STRING *str)\n'
     b'{\n'
     b'   const int length = ASN1_STRING_length(str);\n'
     b'   return length >= 0 ? (size_t)length : 0;\n'
     b'}\n'),
    (b'      const unsigned char *numdata = ASN1_STRING_get0_data(num);\n'
     b'      const size_t numlen = ASN1_STRING_get_length(num);\n',
     b'      const unsigned char *numdata = ASN1_STRING_get0_data(num);\n'
     b'      const size_t numlen = bilipai_ASN1_STRING_get_length(num);\n'),
    (b'      const unsigned char *psigdata = ASN1_STRING_get0_data(psig);\n'
     b'      const size_t psiglen = ASN1_STRING_get_length(psig);\n',
     b'      const unsigned char *psigdata = ASN1_STRING_get0_data(psig);\n'
     b'      const size_t psiglen = bilipai_ASN1_STRING_get_length(psig);\n'),
    (b'        const char *altptr = (const char *)ASN1_STRING_get0_data(check->d.ia5);\n'
     b'        size_t altlen = ASN1_STRING_get_length(check->d.ia5);\n',
     b'        const char *altptr = (const char *)ASN1_STRING_get0_data(check->d.ia5);\n'
     b'        size_t altlen = bilipai_ASN1_STRING_get_length(check->d.ia5);\n'),
    (b'          cnlen = ASN1_STRING_get_length(tmp);\n'
     b'          cn = (unsigned char *)CURL_UNCONST(ASN1_STRING_get0_data(tmp));\n',
     b'          cnlen = bilipai_ASN1_STRING_get_length(tmp);\n'
     b'          cn = (unsigned char *)CURL_UNCONST(ASN1_STRING_get0_data(tmp));\n')
]
OPENSSL_NAMES = {'openssl-asn1.h.in', 'openssl-VERSION.dat',
                 'openssl-generated-asn1.h', 'openssl-installed-asn1.h',
                 'openssl-applied-source.diff', 'openssl-install-receipt.json'}
PATCH_NAMES = OPENSSL_NAMES | {'curl-openssl.before.c', 'curl-openssl.after.c',
                              'curl-openssl-patch-receipt.json'}
FINAL_NAMES = PATCH_NAMES | {'curl-openssl-install-receipt.json'}

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def encode(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()

def directory(value):
    p = Path(value)
    if not p.is_absolute() or any(x in ('.', '..') for x in p.parts):
        raise RuntimeError('Expected an absolute source/install/material directory')
    current = Path(p.anchor)
    for part in p.parts[1:]:
        current = current / part
        s = current.lstat()
        if not stat.S_ISDIR(s.st_mode) or stat.S_ISLNK(s.st_mode):
            raise RuntimeError('Source/install/material directory is not real')
    return p

def read_file(path):
    directory(path.parent)
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or stat.S_ISLNK(before.st_mode) or not 0 < before.st_size <= MAX_FILE:
        raise RuntimeError('Source/material file type or extent changed')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    try:
        first = os.fstat(fd)
        if (not stat.S_ISREG(first.st_mode)
                or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
                != (first.st_dev, first.st_ino, first.st_size, first.st_mtime_ns)):
            raise RuntimeError('Source/material identity changed before read')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            raw = stream.read(MAX_FILE + 1)
        last = os.fstat(fd)
        if (first.st_dev, first.st_ino, first.st_size, first.st_mtime_ns) != (last.st_dev, last.st_ino, last.st_size, last.st_mtime_ns) or len(raw) != first.st_size:
            raise RuntimeError('Source/material bytes changed during read')
        return raw
    finally:
        os.close(fd)

def write_new(path, raw):
    directory(path.parent)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        with os.fdopen(fd, 'wb', closefd=False) as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(fd)
    finally:
        os.close(fd)

def replace_actual(path, before, after):
    fd = os.open(path, os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0))
    try:
        first = os.fstat(fd)
        if not stat.S_ISREG(first.st_mode) or first.st_size != len(before) or os.read(fd, MAX_FILE + 1) != before:
            raise RuntimeError('Actual curl source changed before write')
        os.lseek(fd, 0, os.SEEK_SET)
        with os.fdopen(fd, 'wb', closefd=False) as stream:
            stream.write(after)
            stream.truncate()
            stream.flush()
            os.fsync(fd)
    finally:
        os.close(fd)

def git(source, *args):
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_SYSTEM=os.devnull,
               GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0')
    result = subprocess.run(['git', '-c', 'core.hooksPath=' + os.devnull,
        '-c', 'core.attributesFile=' + os.devnull, '-c', 'core.autocrlf=false',
        '-C', str(source), *args], env=env, stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode != 0 or len(result.stdout) > MAX_FILE:
        raise RuntimeError('Fixed curl/OpenSSL source identity check failed')
    return result.stdout

def patch_relation(before):
    if len(before) != BEFORE_BYTES or sha(before) != BEFORE_SHA256:
        raise RuntimeError('Unknown complete curl OpenSSL source identity')
    after = before
    for old, new in EDITS:
        if after.count(old) != 1:
            raise RuntimeError('Fixed curl OpenSSL edit context changed')
        after = after.replace(old, new)
    if len(after) != AFTER_BYTES or sha(after) != AFTER_SHA256:
        raise RuntimeError('Patched complete curl OpenSSL source identity changed')
    inverse = after
    for old, new in reversed(EDITS):
        if inverse.count(new) != 1:
            raise RuntimeError('Fixed curl OpenSSL inverse context changed')
        inverse = inverse.replace(new, old)
    if inverse != before:
        raise RuntimeError('Complete curl OpenSSL source inverse differs')
    return after

def check_curl(source, patched):
    if git(source, 'rev-parse', 'HEAD').strip().decode('ascii') != CURL_COMMIT or git(source, 'rev-parse', 'HEAD^{tree}').strip().decode('ascii') != CURL_TREE:
        raise RuntimeError('curl is not the selected immutable commit/tree')
    changed = [x for x in git(source, 'diff', '--no-ext-diff', '--no-textconv', '--name-only', '-z', 'HEAD', '--').split(b'\0') if x]
    expected = [SCP_TARGET.encode()] + ([TARGET.encode()] if patched else [])
    if sorted(changed) != sorted(expected):
        raise RuntimeError('Unexpected tracked curl source modifications')
    if sha(git(source, 'show', CURL_COMMIT + ':' + SCP_TARGET)) != SCP_BEFORE_SHA256 or sha(read_file(source / SCP_TARGET)) != SCP_AFTER_SHA256:
        raise RuntimeError('Reviewed independent SCP source patch changed')
    before = git(source, 'show', CURL_COMMIT + ':' + TARGET)
    after = patch_relation(before)
    if read_file(source / TARGET) != (after if patched else before):
        raise RuntimeError('Actual complete curl OpenSSL source differs')
    return before, after

def check_api_header(raw):
    if (raw.count(LEGACY_DECLARATION) != 1
            or b'ASN1_STRING_get_length' in raw
            or b'{-' in raw or b'-}' in raw
            or raw.count(b'#ifndef OPENSSL_ASN1_H\n') != 1
            or raw.count(b'#define OPENSSL_ASN1_H\n') != 1):
        raise RuntimeError('Unknown actual generated OpenSSL ASN.1 API identity')

def check_template_generation(template, generated):
    # Preserve every static byte of the exact fixed template in generated order.
    # Perl expansion regions are observed in full rather than guessed here.
    chunks = re.split(rb'\{\-.*?\-\}', template, flags=re.S)
    if ((chunks[0] and not generated.startswith(chunks[0]))
            or (chunks[-1] and not generated.endswith(chunks[-1]))):
        raise RuntimeError('Actual generated OpenSSL header boundary differs from fixed template')
    position = 0
    for chunk in chunks:
        if not chunk:
            continue
        found = generated.find(chunk, position)
        if found < 0:
            raise RuntimeError('Actual generated OpenSSL header differs from fixed template')
        position = found + len(chunk)
    if chunks[-1] and position != len(generated):
        raise RuntimeError('Actual generated OpenSSL header boundary differs from fixed template')
    check_api_header(generated)

def checked_installed(prefix, materials):
    raw = read_file(prefix / 'include/openssl/asn1.h')
    check_api_header(raw)
    if raw != read_file(materials / 'openssl-installed-asn1.h'):
        raise RuntimeError('Actual installed OpenSSL header changed after observation')
    return raw

def common_receipt():
    return {'schema': 1, 'kind': 'BILIPAI_CURL_OPENSSL_ASN1_SOURCE',
        'curlCommit': CURL_COMMIT, 'curlTree': CURL_TREE,
        'opensslBaseCommit': OPENSSL_BASE, 'targetPath': TARGET,
        'beforeSha256': BEFORE_SHA256, 'afterSha256': AFTER_SHA256,
        'asn1TemplateSha256': ASN1_TEMPLATE_SHA256, 'versionSha256': VERSION_SHA256,
        'helperSha256': sha(read_file(Path(__file__))), 'reviewedPatchCount': 5,
        'generatedHeaderOrigin': 'ACTUAL_OPENSSL_BUILD_DIRECTORY',
        'warningsSuppressed': False, 'tlsDisabled': False, 'runtimeTested': False,
        'gpuExecuted': False}

def validate_materials(value, final=True):
    materials = directory(value)
    names = FINAL_NAMES if final else OPENSSL_NAMES
    if {p.name for p in materials.iterdir()} != names:
        raise RuntimeError('Retained curl/OpenSSL material inventory changed')
    raw = {name: read_file(materials / name) for name in names}
    if sha(raw['openssl-asn1.h.in']) != ASN1_TEMPLATE_SHA256 or sha(raw['openssl-VERSION.dat']) != VERSION_SHA256:
        raise RuntimeError('Retained fixed OpenSSL source identity changed')
    header = raw['openssl-generated-asn1.h']
    check_template_generation(raw['openssl-asn1.h.in'], header)
    if header != raw['openssl-installed-asn1.h']:
        raise RuntimeError('Retained actual generated and installed OpenSSL headers differ')
    install = json.loads(raw['openssl-install-receipt.json'])
    expected = dict(common_receipt(),
        state='AFTER_REAL_OPENSSL_INSTALL_BEFORE_RECIPE_CLEANUP',
        generatedHeaderSha256=sha(header), installedHeaderSha256=sha(header),
        generatedHeaderBytes=len(header), installedHeaderBytes=len(header),
        appliedSourceDiffSha256=sha(raw['openssl-applied-source.diff']),
        actualOpenSslCommit=install.get('actualOpenSslCommit'),
        actualOpenSslTree=install.get('actualOpenSslTree'))
    if (not re.fullmatch('[0-9a-f]{40}', str(expected['actualOpenSslCommit']))
            or not re.fullmatch('[0-9a-f]{40}', str(expected['actualOpenSslTree']))
            or raw['openssl-install-receipt.json'] != encode(expected)):
        raise RuntimeError('Retained actual OpenSSL install/source receipt changed')
    if final:
        if patch_relation(raw['curl-openssl.before.c']) != raw['curl-openssl.after.c']:
            raise RuntimeError('Retained complete curl OpenSSL source relation changed')
        for name, state in [
            ('curl-openssl-patch-receipt.json', 'CURL_PATCH_APPLIED_BEFORE_CONFIGURE'),
            ('curl-openssl-install-receipt.json', 'AFTER_REAL_CURL_INSTALL_BEFORE_OWN_SOURCE_RESTORE_AND_RECIPE_CLEANUP')]:
            if raw[name] != encode(dict(common_receipt(), state=state,
                    opensslInstallReceiptSha256=sha(raw['openssl-install-receipt.json']))):
                raise RuntimeError('Retained curl/OpenSSL recipe observation changed')
    return ({'opensslInstall': install, 'curlInstall': json.loads(raw['curl-openssl-install-receipt.json'])} if final else install), raw

def main():
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=('openssl-after-install', 'patch-curl', 'curl-after-install'))
    p.add_argument('source')
    p.add_argument('prefix')
    p.add_argument('materials')
    p.add_argument('--build-dir')
    args = p.parse_args()
    source, prefix = directory(args.source), directory(args.prefix)
    material_path = Path(args.materials)
    if (not material_path.is_absolute() or material_path == source or source in material_path.parents
            or material_path == prefix or prefix in material_path.parents):
        raise RuntimeError('Retained materials must be outside dependency cleanup and install tree')
    directory(material_path.parent)
    if args.mode == 'openssl-after-install':
        if material_path.exists():
            raise RuntimeError('Use a fresh OpenSSL source material directory')
        git(source, 'merge-base', '--is-ancestor', OPENSSL_BASE, 'HEAD')
        if git(source, 'diff', '--no-ext-diff', '--no-textconv', '--name-only', '-z', 'HEAD', '--'):
            raise RuntimeError('Actual OpenSSL source has unknown tracked modifications')
        if git(source, 'config', '--get', 'remote.origin.url').strip() != b'https://github.com/openssl/openssl.git':
            raise RuntimeError('Actual OpenSSL source remote differs from fixed recipe')
        template = read_file(source / 'include/openssl/asn1.h.in')
        version = read_file(source / 'VERSION.dat')
        if (sha(template) != ASN1_TEMPLATE_SHA256 or sha(version) != VERSION_SHA256
                or git(source, 'show', OPENSSL_BASE + ':include/openssl/asn1.h.in') != template
                or git(source, 'show', OPENSSL_BASE + ':VERSION.dat') != version):
            raise RuntimeError('Unknown actual fixed OpenSSL source identity')
        if args.build_dir is None:
            raise RuntimeError('Actual OpenSSL build directory is required')
        generated = read_file(directory(args.build_dir) / 'include/openssl/asn1.h')
        installed = read_file(prefix / 'include/openssl/asn1.h')
        check_template_generation(template, generated)
        if generated != installed:
            raise RuntimeError('Actual installed OpenSSL header differs from generated source')
        applied = git(source, 'diff', '--no-ext-diff', '--no-textconv', OPENSSL_BASE, 'HEAD', '--')
        if not applied:
            applied = b'# No tracked recipe patch difference from the selected base.\n'
        material_path.mkdir(mode=0o700)
        materials = directory(material_path)
        values = {'openssl-asn1.h.in': template, 'openssl-VERSION.dat': version,
            'openssl-generated-asn1.h': generated, 'openssl-installed-asn1.h': installed,
            'openssl-applied-source.diff': applied}
        for name, raw in values.items():
            write_new(materials / name, raw)
        observed = dict(common_receipt(),
            state='AFTER_REAL_OPENSSL_INSTALL_BEFORE_RECIPE_CLEANUP',
            actualOpenSslCommit=git(source, 'rev-parse', 'HEAD').strip().decode('ascii'),
            actualOpenSslTree=git(source, 'rev-parse', 'HEAD^{tree}').strip().decode('ascii'),
            generatedHeaderSha256=sha(generated), installedHeaderSha256=sha(installed),
            generatedHeaderBytes=len(generated), installedHeaderBytes=len(installed),
            appliedSourceDiffSha256=sha(applied))
        write_new(materials / 'openssl-install-receipt.json', encode(observed))
        validate_materials(materials, final=False)
    else:
        materials = directory(material_path)
        if args.mode == 'patch-curl':
            validate_materials(materials, final=False)
        else:
            if {p.name for p in materials.iterdir()} != PATCH_NAMES:
                raise RuntimeError('Retained patch material inventory changed')
        checked_installed(prefix, materials)
        before, after = check_curl(source, args.mode != 'patch-curl')
        install_raw = read_file(materials / 'openssl-install-receipt.json')
        if args.mode == 'patch-curl':
            write_new(materials / 'curl-openssl.before.c', before)
            write_new(materials / 'curl-openssl.after.c', after)
            replace_actual(source / TARGET, before, after)
            check_curl(source, True)
            write_new(materials / 'curl-openssl-patch-receipt.json',
                encode(dict(common_receipt(), state='CURL_PATCH_APPLIED_BEFORE_CONFIGURE',
                            opensslInstallReceiptSha256=sha(install_raw))))
        else:
            if read_file(materials / 'curl-openssl.before.c') != before or read_file(materials / 'curl-openssl.after.c') != after:
                raise RuntimeError('Retained actual curl build source differs')
            write_new(materials / 'curl-openssl-install-receipt.json',
                encode(dict(common_receipt(),
                    state='AFTER_REAL_CURL_INSTALL_BEFORE_OWN_SOURCE_RESTORE_AND_RECIPE_CLEANUP',
                    opensslInstallReceiptSha256=sha(install_raw))))
            validate_materials(materials)
            # Keep the existing SCP check strict; its exact source diff remains ssh.h.
            replace_actual(source / TARGET, after, before)
            check_curl(source, False)

if __name__ == '__main__':
    main()
