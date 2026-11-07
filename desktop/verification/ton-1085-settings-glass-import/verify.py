#!/usr/bin/env python3
"""Compare the same JUnit class at the recorded PR head and the working patch."""
from pathlib import Path
import glob, hashlib, os, re, shutil, subprocess, sys, xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[3]
evidence = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path('settings-icon-evidence').resolve()
if evidence.exists(): raise SystemExit(f'Refusing to overwrite {evidence}')
evidence.mkdir(parents=True)
base = 'b4ebdcebf65ee141b203e144561fb381e315416c'
name = 'SettingsMiuixSimplificationStructureTest'
test = f'app/src/test/java/com/android/purebilibili/feature/settings/{name}.kt'
patch = f'desktop/upstream-core/patches/{test}.patch'
cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group, artifact, version):
    matches = glob.glob(str(cache/group/artifact/version/'*'/(artifact+'-'+version+'.jar')))
    if len(matches) != 1: raise RuntimeError((artifact, version, matches))
    return matches[0]
compiler = [jar('org.jetbrains.kotlin', x, '2.4.0') for x in ('kotlin-compiler-embeddable','kotlin-stdlib','kotlin-script-runtime','kotlin-daemon-embeddable')] + [jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'),jar('org.jetbrains','annotations','23.0.0'),jar('org.jetbrains.kotlin','kotlin-reflect','2.3.20')]
testcp = [jar('org.jetbrains.kotlin', x, '2.4.0') for x in ('kotlin-stdlib','kotlin-test','kotlin-test-junit5')] + [jar('org.junit.jupiter', x, '5.13.4') for x in ('junit-jupiter-api','junit-jupiter-engine')] + [jar('org.junit.platform', x, '1.13.4') for x in ('junit-platform-commons','junit-platform-engine','junit-platform-launcher')] + [jar('org.opentest4j','opentest4j','1.3.0'),jar('org.apiguardian','apiguardian-api','1.1.2')]
subprocess.run(['javac','-cp',os.pathsep.join(testcp),'-d',str(evidence),str(Path(__file__).with_name('JUnitXmlRunner.java'))],check=True)
paths = set([test,'app/src/main/res/values/strings.xml','app/src/main/java/com/android/purebilibili/feature/settings/SettingsAdditionalDirectory.kt'])
paths.update(re.findall(r'app/src/main/[\w/.-]+\.(?:kt|xml)',(repo/test).read_text()))
for path in paths:
    if (repo/path).is_file():
        original = subprocess.check_output(['git','show',f'{base}:{path}'],cwd=repo)
        assert original == (repo/path).read_bytes(), f'fixture differs from baseline: {path}'
base_patch = subprocess.check_output(['git','show',f'{base}:{patch}'],cwd=repo)
(evidence/'baseline.patch').write_bytes(base_patch)
(evidence/'corrected.patch').write_bytes((repo/patch).read_bytes())
for variant in ('baseline','corrected'):
    d = evidence/variant
    for path in paths:
        if (repo/path).is_file():
            (d/path).parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(repo/path,d/path)
    patch_file = evidence/(variant+'.patch')
    expected = re.search(r'Upstream-source-sha256: ([0-9a-f]+)',patch_file.read_text()).group(1)
    assert hashlib.sha256((d/test).read_bytes()).hexdigest() == expected
    subprocess.run(['patch','--batch','-p1','-i',str(patch_file)],cwd=d,check=True)
    # Mirror the build's unique-context matcher, independently of patch(1).
    lines = (repo/test).read_text().replace('\r\n','\n').split('\n')
    body = patch_file.read_text().replace('\r\n','\n').split('\n')
    body = body[next(i for i,line in enumerate(body) if line.startswith('--- '))+2:]
    index = 0
    while index < len(body):
        header = body[index]; index += 1
        if not header: continue
        assert header.startswith('@@ '), header
        before=[]; after=[]
        while index < len(body) and not body[index].startswith('@@ '):
            line=body[index]; index += 1
            if not line: continue
            assert line[0] in ' +- ', line
            if line[0] in ' -': before.append(line[1:])
            if line[0] in ' +': after.append(line[1:])
        matches=[i for i in range(len(lines)-len(before)+1) if lines[i:i+len(before)]==before]
        assert len(matches)==1, (header,len(matches))
        pos=matches[0]; lines=lines[:pos]+after+lines[pos+len(before):]
    assert '\n'.join(lines)==(d/test).read_text(), variant
    print(variant, 'unique-context matcher agrees with patch(1)', flush=True)
    classes = d/'classes'; classes.mkdir()
    subprocess.run(['java','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',os.pathsep.join(testcp),'-d',str(classes),str(d/test)],check=True)

def run(variant, expected):
    d = evidence/variant
    classes = evidence/('baseline' if variant == 'baseline' else 'corrected')/'classes'
    command = ['java','-cp',os.pathsep.join(map(str,[evidence,classes]+testcp)),'JUnitXmlRunner','com.android.purebilibili.feature.settings.'+name,str(evidence/(variant+'.xml'))]
    proc = subprocess.run(command,cwd=d,text=True,capture_output=True)
    (evidence/(variant+'.log')).write_text('command: '+repr(command)+'\nexit: '+str(proc.returncode)+'\n'+proc.stdout+proc.stderr)
    print(variant,'exit',proc.returncode,proc.stdout.strip(),flush=True)
    if proc.returncode != 1: raise RuntimeError((variant,proc.returncode,proc.stderr))
    root = ET.parse(evidence/(variant+'.xml')).getroot()
    failures = {case.attrib['name'] for case in root.findall('testcase') if case.find('failure') is not None}
    if failures != expected: raise RuntimeError((variant,failures,expected))
    return root

target = 'liquid glass adjustment uses live miuix backdrop and commits on release()'
remaining = {'appearance settings expose one ui style selection while keeping miuix scaffold()'}
all_names = None
def checked(variant, expected):
    global all_names
    root = run(variant, expected)
    names = [case.attrib['name'] for case in root.findall('testcase')]
    assert len(names)==5 and len(set(names))==5 and root.attrib.get('tests')=='5' and root.attrib.get('skipped')=='0', (variant,root.attrib,names)
    if all_names is None: all_names=set(names)
    assert set(names)==all_names, variant
checked('baseline',remaining | {target})
checked('corrected',remaining)
source = 'app/src/main/java/com/android/purebilibili/feature/settings/LiquidGlassLivePreview.kt'
cases = [
 ('negative-importing-label', 'text = if (isImportingSettings) "正在读取" else "导入他人设置",', 'text = if (isImportingSettings) "导入他人设置" else "正在读取",', True),
 ('negative-import-action', 'onClick = onImportSettings,', 'onClick = onShareSettings,', True),
 ('negative-enabled', 'enabled = !isImportingSettings,', 'enabled = isImportingSettings,', True),
 ('positive-layout', 'text = if (isImportingSettings) "正在读取" else "导入他人设置",', 'text=if(isImportingSettings) "正在读取" else "导入他人设置",', False),
]
for variant,before,after,negative in cases:
    shutil.copytree(evidence/'corrected',evidence/variant,ignore=shutil.ignore_patterns('classes'))
    f=evidence/variant/source; content=f.read_text()
    if variant == 'negative-enabled':
        start=content.index('onClick = onImportSettings,'); idx=content.index(before,start); content=content[:idx]+content[idx:].replace(before,after,1)
    else:
        assert content.count(before)==1,(variant,content.count(before)); content=content.replace(before,after,1)
    f.write_text(content)
    checked(variant,remaining | {target} if negative else remaining)
# Verify that a wrong visible directory title and missing pager image are caught too.
for variant, file, before, after in [
    ('negative-directory-title', 'app/src/main/java/com/android/purebilibili/feature/settings/SettingsAdditionalDirectory.kt', 'PlaybackSettingItem("glass.content_readability", "文字对比度",', 'PlaybackSettingItem("glass.content_readability", "错误标题",'),
    ('negative-pager-image', source, 'model = LiquidGlassPreviewArtwork.entries[page].drawableResId', 'model = null'),
]:
    shutil.copytree(evidence/'corrected',evidence/variant,ignore=shutil.ignore_patterns('classes'))
    f=evidence/variant/file; content=f.read_text(); assert content.count(before)==1
    f.write_text(content.replace(before,after,1)); checked(variant,remaining | {target})
print('Verified exact 2 -> 1 failure identities, no missing or skipped tests, five counterexamples and one formatting positive.')
