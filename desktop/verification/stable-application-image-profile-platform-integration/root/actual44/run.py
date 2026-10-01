from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys, zipfile

sys.dont_write_bytecode = True
LANE = Path(__file__).resolve().parent
MAIN = LANE.parents[2]
SNAP = MAIN / 'desktop/.local/stable-product-snapshot-44'
sha = lambda data: hashlib.sha256(data).hexdigest()
def wide(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def read(path): return wide(path).read_bytes()
def save(path, value):
    wide(path).parent.mkdir(parents=True, exist_ok=True)
    wide(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

assert sha(read(SNAP / 'manifest.json')) == 'e5ba1d8de17cd400dde006ab3a75a1d5facdd68b8dba4bb52c9c51db52e5f31a'
assert sha(read(SNAP / 'ordered-runtime-cp.json')) == 'b4d72f39a19b3704e657a9edb8c8e8d18c10a1a352481c34a96f0ad208e17141'
CP = json.loads(read(SNAP / 'ordered-runtime-cp.json'))
assert len(CP) == 97
def pins():
    result = []
    for row in CP:
        digest = sha(read(row['path']))
        assert digest == row['sha256Bytes'], row['path']
        result.append({'path': row['path'], 'sha256Bytes': digest})
    return result
spec = importlib.util.spec_from_file_location('compiler', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)
OUT = LANE / ('run-' + (sys.argv[1] if len(sys.argv) > 1 else '01'))
assert not OUT.exists(), OUT
OUT.mkdir()
save(OUT / 'cp-pins-before.json', pins())
wide(OUT / 'input-manifest.json').write_bytes(read(SNAP / 'manifest.json'))
wide(OUT / 'input-ordered-runtime-cp.json').write_bytes(read(SNAP / 'ordered-runtime-cp.json'))
save(OUT / 'compiler-tool-pins.json', [{'path': str(p), 'sha256Bytes': sha(read(p))} for p in [compiler.JAVA, compiler.PLUGIN, *compiler.COMPILER]])

CORE = ['com.bilipai.desktop.data.DesktopSessionStore', 'com.bilipai.desktop.data.DesktopRepository']
GROUPS = [
    {'name': 'image', 'lane': 'stable-application-image-loader-parity', 'file': 'ImageLoaderFixture.kt',
     'frozen': '0740783057cdccbcc5b5d65ee09b74ec9c8e017a9e9fb6b940888d78dcaf293d',
     'fixtureSha': '7788fbcbefde825813d8c76dee25898ea4282ccf711f9eeb412d410809521511',
     'assertions': 22, 'classes': CORE + ['com.bilipai.desktop.ui.DesktopApplicationImageLoader',
       'com.bilipai.desktop.ui.DesktopApplicationImageCacheTrim',
       'com.android.purebilibili.app.DesktopOriginalApplicationImageLoaderKt',
       'com.android.purebilibili.core.lifecycle.DesktopOriginalBackgroundImageTrimPolicyKt',
       'coil3.network.cachecontrol.CacheControlCacheStrategy', 'coil3.network.cachecontrol.internal.CacheControl']},
    {'name': 'profile', 'lane': 'stable-profile-windows-platform-parity', 'file': 'ProfilePlatformFixture.kt',
     'frozen': '15aa4e1197a7b804f8bdb9f6942972761c7864a8d552dbbdef72a33930f03db6',
     'fixtureSha': '5373933f573c720776216bef6c18b3c2899fdecccbae2408b99b4e9397cd45d7',
     'assertions': 55, 'classes': CORE + ['com.bilipai.desktop.ui.DesktopProfileOwnedFiles',
       'com.bilipai.desktop.ui.DesktopProfileFfprobeWidth', 'com.bilipai.desktop.ui.DesktopOriginalProfilePreferences',
       'com.bilipai.desktop.appearance.DesktopThemePrefs', 'com.bilipai.desktop.plugins.DesktopPluginStore',
       'com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences',
       'com.bilipai.desktop.ui.DesktopImageSaveLocations', 'com.bilipai.desktop.ui.DesktopDynamicImageAssets',
       'com.android.purebilibili.feature.profile.WallpaperImageImportKt']}
]
ORIGINS = r'''package com.bilipai.desktop.ui

import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

internal fun assertActual44Origins() {
    val expected = Path.of(System.getProperty("fixture.actualJar")).toRealPath()
    val names = System.getProperty("fixture.origins").split(',')
    ZipFile(expected.toFile()).use { jar ->
        names.forEach { name ->
            val type = Class.forName(name, false, Thread.currentThread().contextClassLoader)
            val actual = Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
            check(actual == expected) { "Unexpected actual origin: $name -> $actual" }
            val resource = name.replace('.', '/') + ".class"
            val bytes = type.getResourceAsStream("/" + resource)!!.use { it.readBytes() }
            val expectedBytes = jar.getInputStream(jar.getEntry(resource)).use { it.readBytes() }
            check(bytes.contentEquals(expectedBytes)) { "Loaded class byte mismatch: $name" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            println("ACTUAL_ORIGIN " + buildJsonObject {
                put("class", name); put("codeSource", actual.toString()); put("classSha256Bytes", digest)
            })
        }
    }
}
'''
results = []
for group in GROUPS:
    frozenLane = MAIN / 'desktop/.local' / group['lane']
    assert sha(read(frozenLane / 'frozen-handoff.json')) == group['frozen']
    original = read(frozenLane / group['file'])
    assert sha(original) == group['fixtureSha']
    text = original.decode('utf-8').replace('\r\n', '\n')
    anchor = ' = runBlocking {' if group['name'] == 'image' else '=runBlocking {'
    assert text.count(anchor) == 1
    text = text.replace(anchor, anchor + '\n    assertActual44Origins()', 1)
    if group['name'] == 'profile':
        # Only output provenance metadata changes; all 55 business claims are unchanged.
        assert text.count('prospectiveOverrideFiles\\\":3') == 1
        text = text.replace('prospectiveOverrideFiles\\\":3', 'prospectiveOverrideFiles\\\":0')
    directory = OUT / group['name']
    directory.mkdir()
    fixtureSource = directory / group['file']
    fixtureSource.write_text(text, encoding='utf-8')
    (directory / 'Actual44Origins.kt').write_text(ORIGINS, encoding='utf-8')
    recovered = text.replace(anchor + '\n    assertActual44Origins()', anchor, 1)
    if group['name'] == 'profile': recovered = recovered.replace('prospectiveOverrideFiles\\\":0', 'prospectiveOverrideFiles\\\":3')
    assert recovered == original.decode('utf-8').replace('\r\n', '\n')
    save(directory / 'fixture-adaptation.json', {'originalFixture': str(frozenLane / group['file']),
        'originalFixtureSha256Bytes': sha(original), 'frozenManifestSha256Bytes': group['frozen'],
        'adaptedFixtureSha256Bytes': sha(read(fixtureSource)), 'inverseLfByteEqual': True,
        'businessAssertionsUnchanged': group['assertions'],
        'changes': ['Add actual Main44 code-source and class-byte verification before business assertions'] +
            (['Update printed prospectiveOverrideFiles metadata from 3 to actual 0'] if group['name'] == 'profile' else [])})
    fixture = directory / 'fixture.jar'
    args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', ';'.join(r['path'] for r in CP),
        '-Xfriend-paths=' + CP[1]['path'], '-Xplugin=' + str(compiler.PLUGIN),
        '-module-name', 'com_bilipai_desktop_bilipai_windows', '-d', str(fixture), str(fixtureSource), str(directory / 'Actual44Origins.kt')]
    argfile = directory / 'compiler.args'
    argfile.write_text('\n'.join('"' + str(a).replace('\\', '/') + '"' for a in args) + '\n', encoding='utf-8')
    run = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-cp', ';'.join(map(str, compiler.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(argfile)], capture_output=True, text=True, encoding='utf-8', timeout=180)
    (directory / 'compile.log').write_text(run.stdout + run.stderr, encoding='utf-8')
    assert run.returncode == 0, directory / 'compile.log'
    with zipfile.ZipFile(wide(fixture)) as z: fixtureClasses = sorted(n for n in z.namelist() if n.endswith('.class'))
    overlaps = []
    for row in CP:
        with zipfile.ZipFile(wide(row['path'])) as z:
            intersection = sorted(set(fixtureClasses).intersection(z.namelist()))
            if intersection: overlaps.append({'path': row['path'], 'classes': intersection})
    save(directory / 'class-intersection.json', {'fixtureClasses': fixtureClasses, 'overlaps': overlaps, 'productionOverrides': 0})
    assert not overlaps
    extra = [str(directory / 'private-local-fixture')]
    if group['name'] == 'profile':
        probe = MAIN / 'desktop/resources/common/native/windows-x64/ffprobe.exe'
        ffmpeg = probe.with_name('ffmpeg.exe')
        assert sha(read(probe)) == '67176fa62f89f94c3bcd379fd05677a25651569a2eb8880ec2194e62c82be412'
        assert sha(read(ffmpeg)) == '9c60da6c0b083110d59084ea39f60ae149aa3e031c3b4bb4f573fafa1c1e7cea'
        media = directory / 'private-synthetic-local.mp4'
        command = [str(ffmpeg), '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'color=c=blue:s=160x90:r=15', '-t', '0.4', '-an', '-c:v', 'mpeg4', '-y', str(media)]
        generated = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=15)
        (directory / 'media-generator.log').write_text(generated.stdout + generated.stderr, encoding='utf-8')
        assert generated.returncode == 0
        save(directory / 'media-pins.json', {'ffprobe': str(probe), 'ffprobeSha256Bytes': sha(read(probe)),
            'ffmpeg': str(ffmpeg), 'ffmpegSha256Bytes': sha(read(ffmpeg)), 'privateSyntheticMediaSha256Bytes': sha(read(media)), 'command': command})
        extra.extend([str(probe), str(media)])
    command = [str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
        '-Dfixture.actualJar=' + CP[1]['path'], '-Dfixture.origins=' + ','.join(group['classes']),
        '-cp', ';'.join([str(fixture)] + [row['path'] for row in CP]),
        'com.bilipai.desktop.ui.' + group['file'].removesuffix('.kt') + 'Kt', *extra]
    save(directory / 'runtime-command.json', command)
    run = subprocess.run(command, capture_output=True, timeout=60)
    (directory / 'runtime-stdout.raw.log').write_bytes(run.stdout)
    (directory / 'runtime-stderr.raw.log').write_bytes(run.stderr)
    stdout = run.stdout.decode('utf-8', errors='replace')
    stderr = run.stderr.decode('utf-8', errors='replace')
    (directory / 'runtime.log').write_text(stdout + stderr, encoding='utf-8')
    origins = [json.loads(line.removeprefix('ACTUAL_ORIGIN ')) for line in stdout.splitlines() if line.startswith('ACTUAL_ORIGIN ')]
    save(directory / 'actual-origins.json', origins)
    assert run.returncode == 0, directory / 'runtime.log'
    assert len(origins) == len(group['classes'])
    if group['name'] == 'image':
        assert 'PASS application image loader / 3 groups / 22 assertions' in stdout
        business = {'groups': 3, 'assertions': 22}
    else:
        match = re.search(r'PROFILE_PLATFORM_PROOF (\{[^\n]+\})', stdout)
        assert match
        business = json.loads(match.group(1))
        assert business['assertions'] == 55 and business['prospectiveOverrideFiles'] == 0
    result = {'name': group['name'], 'passed': True, 'business': business, 'actualOriginCount': len(origins),
        'fixtureClassCount': len(fixtureClasses), 'fixtureJarSha256Bytes': sha(read(fixture)),
        'productionOverrides': 0, 'businessAssertionsUnchanged': True}
    save(directory / 'result.json', result)
    results.append(result)
    print(json.dumps(result), flush=True)
save(OUT / 'cp-pins-after.json', pins())
assert read(OUT / 'cp-pins-before.json') == read(OUT / 'cp-pins-after.json')
save(OUT / 'result.json', {'passed': True, 'actualSnapshot': 44, 'manifestSha256Bytes': sha(read(SNAP / 'manifest.json')),
    'orderedRuntimeCpSha256Bytes': sha(read(SNAP / 'ordered-runtime-cp.json')), 'runtimeEntries': 97,
    'productionOverrides': 0, 'totalBusinessAssertions': 77, 'groups': results, 'cpPrePostByteEqual': True,
    'scope': 'Same installed image and Profile platform helpers, temporary fixture Stores and synthetic local media only',
    'notClaimed': ['Root mounted UI', 'user account or user files', 'chooser/HWND/DWM', 'HTTP', 'Profile navigation end-to-end']})
