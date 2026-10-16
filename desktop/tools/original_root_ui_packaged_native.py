"""Bind package-only native resources to immutable source pins and package evidence.

The compile-only UI export does not fetch libmpv/FFmpeg. Their exact binary pins,
notices and generated provenance are checked separately. No native code is loaded.
"""
from pathlib import Path
import hashlib
import re
import subprocess

import original_root_ui_runtime_binding as binding
import original_root_ui_runtime_image as image

NATIVE = 'native/windows-x64/'
MPV = 'desktop/third-party/libmpv/'
FFMPEG = 'desktop/third-party/ffmpeg/'
FIXED = MPV + 'build/fixed-inputs.json'


def relative(name):
    binding.relative(name)
    binding.require(re.fullmatch(r'[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+)*', name) is not None,
                    'Unsupported native source path')
    return name


def git_blobs(repo, source, names):
    binding.require(re.fullmatch('[0-9a-f]{40}', source) is not None, 'Exact native source commit required')
    names = list(dict.fromkeys(relative(name) for name in names))
    binding.require(0 < len(names) <= 2048, 'Native source inventory exceeds bound')
    request = ''.join(source + ':' + name + '\n' for name in names).encode()
    headers = subprocess.run(['git', '-C', str(repo), 'cat-file', '--batch-check=%(objecttype) %(objectsize)'],
        input=request, capture_output=True, check=True, timeout=30).stdout.splitlines()
    binding.require(len(headers) == len(names), 'Missing fixed native source blob')
    sizes = []
    for row in headers:
        fields = row.split()
        binding.require(len(fields) == 2 and fields[0] == b'blob' and fields[1].isdigit(),
                        'Missing fixed native source blob')
        size = int(fields[1]); binding.require(size <= 8 * 1024 * 1024, 'Native source blob exceeds bound')
        sizes.append(size)
    binding.require(sum(sizes) <= 64 * 1024 * 1024, 'Native source blob batch exceeds bound')
    # Immutable Git objects supply these bytes, never the possibly CRLF checkout.
    result = subprocess.run(['git', '-C', str(repo), 'cat-file', '--batch'],
        input=request,
        capture_output=True, check=True, timeout=30).stdout
    binding.require(len(result) <= 64 * 1024 * 1024, 'Native source blob batch exceeds bound')
    offset = 0; output = {}
    for name in names:
        end = result.index(b'\n', offset); fields = result[offset:end].split()
        binding.require(len(fields) == 3 and fields[1] == b'blob', 'Missing fixed native source blob')
        size = int(fields[2]); binding.require(0 <= size <= 8 * 1024 * 1024, 'Native source blob exceeds bound')
        output[name] = result[end + 1:end + 1 + size]
        binding.require(result[end + 1 + size:end + 2 + size] == b'\n', 'Truncated native source blob')
        offset = end + 2 + size
    binding.require(offset == len(result), 'Unexpected native source batch tail')
    return output


def pin(raw): return dict(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())


def source_inventory(repo, source):
    names = [MPV+'SOURCES.json', MPV+'NOTICES.md', FFMPEG+'SOURCES.json', FFMPEG+'README.md',
             FIXED, 'desktop/tools/fetch-mpv.ps1']
    blobs = git_blobs(repo, source, names)
    mpv = binding.json_bytes(blobs[MPV+'SOURCES.json'])
    ffmpeg = binding.json_bytes(blobs[FFMPEG+'SOURCES.json'])
    fixed = binding.json_bytes(blobs[FIXED])
    rows = [(MPV, 'licenses/', mpv), (FFMPEG, 'licenses/ffmpeg/', ffmpeg)]
    license_names = [prefix + relative(row['path']) for prefix, _, catalog in rows for row in catalog['licenseFiles']]
    blobs.update(git_blobs(repo, source, license_names))
    resources = {}; destinations = set()
    def add(name, value):
        relative(name)
        binding.require(name.casefold() not in destinations, 'Native notice destination collision')
        destinations.add(name.casefold()); resources[name] = value
    for prefix, target, catalog in rows:
        for row in catalog['licenseFiles']:
            name = prefix + relative(row['path']); value = pin(blobs[name])
            binding.require(value['sha256'] == row['sha256'], 'Fixed source notice checksum differs')
            add(NATIVE + target + Path(row['path']).name, value)
    for name, source_path in [(NATIVE+'licenses/SOURCES.json', MPV+'SOURCES.json'),
        (NATIVE+'licenses/NOTICES.md', MPV+'NOTICES.md'),
        (NATIVE+'licenses/ffmpeg/SOURCES.json', FFMPEG+'SOURCES.json'),
        (NATIVE+'licenses/ffmpeg/README.md', FFMPEG+'README.md')]: add(name, pin(blobs[source_path]))
    match = re.search(r'\$downloadUrls\s*=\s*@\((.*?)\)', blobs['desktop/tools/fetch-mpv.ps1'].decode('utf-8-sig'), re.S)
    binding.require(match is not None, 'Fixed MPV download recipe changed')
    mirrors = re.findall(r"'([^']+)'", match[1])
    binding.require(mpv['binary']['originalArchive'] in mirrors, 'Fixed MPV mirrors omit the source archive')
    binary_hashes = {'libmpv-2.dll': fixed['originalDllSha256'],
        'ffmpeg.exe': ffmpeg['binary']['ffmpegSha256'], 'ffprobe.exe': ffmpeg['binary']['ffprobeSha256']}
    binding.require(all(re.fullmatch('[0-9a-f]{64}', value) is not None for value in binary_hashes.values()),
                    'Invalid fixed native binary hash')
    return dict(resources=resources, binaryHashes=binary_hashes, mpv=mpv, ffmpeg=ffmpeg, mirrors=mirrors,
        sourceBlobPins={name: pin(raw) for name, raw in blobs.items()})


def verify_provenance(mpv_raw, ffmpeg_raw, inventory):
    mpv = binding.json_bytes(mpv_raw); ffmpeg = binding.json_bytes(ffmpeg_raw)
    mc = inventory['mpv']; fc = inventory['ffmpeg']; binary = fc['binary']
    expected_mpv = dict(sourceUrl=mc['binary']['originalArchive'], mirrors=inventory['mirrors'],
        sourceProject='https://github.com/shinchiro/mpv-winbuild-cmake',
        archiveSha256=mc['binary']['archiveSha256'], dllSha256=inventory['binaryHashes']['libmpv-2.dll'],
        architecture='windows-x64', upstreamSource='https://github.com/' + mc['components']['mpv']['repository'])
    binding.require(set(mpv) == set(expected_mpv) | {'licenseFiles'} and
                    all(mpv.get(key) == value for key, value in expected_mpv.items()),
                    'Generated MPV provenance differs from fixed source inputs')
    expected_notices = {Path(name).name for name in inventory['resources'] if name.startswith(NATIVE+'licenses/') and
                        not name.startswith(NATIVE+'licenses/ffmpeg/')}
    binding.require(isinstance(mpv['licenseFiles'], list) and len(mpv['licenseFiles']) == len(expected_notices) and
                    set(mpv['licenseFiles']) == expected_notices, 'Generated MPV notice inventory differs')
    expected_ffmpeg = dict(schemaVersion=1, architecture='windows-x64', variant=binary['variant'],
        sourceUrl=binary['archiveUrl'], archiveSha256=binary['archiveSha256'],
        ffmpegSha256=binary['ffmpegSha256'], ffprobeSha256=binary['ffprobeSha256'],
        ffmpegSourceCommit=fc['ffmpegSource']['commit'], ffmpegSourceArchiveSha256=fc['ffmpegSource']['archiveSha256'],
        buildRecipesCommit=fc['buildRecipes']['commit'], buildRecipesArchiveSha256=fc['buildRecipes']['archiveSha256'],
        licenseFiles=[row['path'] for row in fc['licenseFiles']], dependencyLicenseInventory=fc['dependencyLicenseInventory'])
    binding.require(set(ffmpeg) == set(expected_ffmpeg) | {'version'} and
                    all(ffmpeg.get(key) == value for key, value in expected_ffmpeg.items()),
                    'Generated FFmpeg provenance differs from fixed source inputs')
    def normalize(text): return text.replace('\\r\\n','\n').replace('\\n','\n').replace('\r\n','\n').strip()
    binding.require(isinstance(ffmpeg['version'], str) and normalize(ffmpeg['version']) == normalize(binary['versionOutput']),
                    'Generated FFmpeg version differs from the fixed binary catalog')


def bind_packaged_native(repo, source, version, package, source_resources=None):
    toolchain, authority = image.verify_package_evidence(package['run'], package['jobs'], package['log'],
        package['toolchainRaw'], source, version)
    inventory = source_inventory(repo, source)
    expected = dict(inventory['resources']); prefix = image.APP + 'app/resources/'
    with image.archive_snapshot(package['zip'], {key:toolchain['candidateZip'][key] for key in ('bytes','sha256')}) as (archive, rows):
        actual = {row.filename[len(prefix):]:row for row in rows if not row.is_dir() and row.filename.startswith(prefix+NATIVE)}
        compiled_native = {name:pin for name,pin in (source_resources or {}).items() if name.startswith(NATIVE)}
        generated = {NATIVE+'provenance.json', NATIVE+'ffmpeg-provenance.json'}
        names = set(expected) | {NATIVE+name for name in inventory['binaryHashes']} | generated
        binding.require(not (names & set(compiled_native)) and set(actual) == names | set(compiled_native),
                        'Packaged native inventory differs from fixed source staging')
        for name,pin in compiled_native.items():
            binding.verify_pin(binding.member_digest(archive,actual[name].filename), pin,
                               'Source-compiled native resource differs from the qualified ledger')
            del actual[name]
        for name, row in actual.items():
            measured = binding.member_digest(archive,row.filename)
            if name in generated: expected[name] = measured
            elif name[len(NATIVE):] in inventory['binaryHashes']:
                binding.require(measured['sha256'] == inventory['binaryHashes'][name[len(NATIVE):]],
                                'Packaged native binary differs from its fixed source hash')
                expected[name] = measured
            else: binding.verify_pin(measured, expected[name], 'Packaged native notice differs from its fixed source blob')
        payloads = []
        for name in (NATIVE+'provenance.json', NATIVE+'ffmpeg-provenance.json'):
            row = actual[name]; binding.require(row.file_size <= binding.MAX_JSON, 'Native provenance exceeds bound')
            with archive.open(row) as stream: payloads.append(stream.read(binding.MAX_JSON+1))
        verify_provenance(*payloads,inventory)
    return dict(sourceSha=source, package=authority, packageZipSHA256=toolchain['candidateZip']['sha256'],
        resources=expected, sourceBlobPins=inventory['sourceBlobPins'], nativeBinaryHashes=inventory['binaryHashes'],
        nativeCodeLoaded=False, applicationExecuted=False, hardwareVerified=False)
