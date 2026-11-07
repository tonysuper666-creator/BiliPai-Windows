#!/usr/bin/env python3
"""Run the JUnit structure suite against a fixed baseline and isolated mutants."""
from pathlib import Path
import glob, os, re, shutil, subprocess, sys, xml.etree.ElementTree as ET
repo = Path(__file__).resolve().parents[3]
out = Path(sys.argv[1]).resolve()
if out.exists(): raise SystemExit(f'refusing to overwrite {out}')
out.mkdir(parents=True)
base = '39730a3b595e27c65365526eb2ae7226f44b8078'
name = 'SettingsRootCategoryContentStructureTest'
test = 'app/src/test/java/com/android/purebilibili/feature/settings/' + name + '.kt'
patch = 'desktop/upstream-core/patches/' + test + '.patch'
source = 'app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsSearchScreen.kt'
cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group, artifact, version):
    found = glob.glob(str(cache/group/artifact/version/'*'/(artifact+'-'+version+'.jar')))
    if len(found) != 1: raise RuntimeError((artifact, version, found))
    return found[0]
compiler = [jar('org.jetbrains.kotlin', x, '2.4.0') for x in ('kotlin-compiler-embeddable','kotlin-stdlib','kotlin-script-runtime','kotlin-daemon-embeddable')] + [jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'), jar('org.jetbrains','annotations','23.0.0'), jar('org.jetbrains.kotlin','kotlin-reflect','2.3.20')]
testcp = [jar('org.jetbrains.kotlin', x, '2.4.0') for x in ('kotlin-stdlib','kotlin-test','kotlin-test-junit5')] + [jar('org.junit.jupiter', x, '5.13.4') for x in ('junit-jupiter-api','junit-jupiter-engine')] + [jar('org.junit.platform', x, '1.13.4') for x in ('junit-platform-commons','junit-platform-engine','junit-platform-launcher')] + [jar('org.opentest4j','opentest4j','1.3.0'),jar('org.apiguardian','apiguardian-api','1.1.2')]
def gitfile(path): return subprocess.check_output(['git','show',base+':'+path],cwd=repo)
subprocess.run(['javac','-cp',os.pathsep.join(testcp),'-d',str(out),str(Path(__file__).with_name('JUnitXmlRunner.java'))],check=True)
original = gitfile(test).decode()
paths = set([test,'app/src/main/res/values/strings.xml','app/src/main/java/com/android/purebilibili/feature/settings/SettingsRootCategoryCopy.kt'])
paths.update(re.findall(r'app/src/main/[\w/.-]+\.(?:kt|xml)', original))
for variant in ('baseline','corrected'):
    root = out/variant
    for path in paths:
        try: content = gitfile(path)
        except subprocess.CalledProcessError: continue
        f = root/path; f.parent.mkdir(parents=True,exist_ok=True); f.write_bytes(content)
    patchfile = out/(variant+'.patch')
    patchfile.write_bytes(gitfile(patch) if variant=='baseline' else (repo/patch).read_bytes())
    subprocess.run(['patch','--batch','-p1','-i',str(patchfile)],cwd=root,check=True)
    classes = root/'classes'; classes.mkdir()
    subprocess.run(['java','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',os.pathsep.join(testcp),'-d',str(classes),str(root/test)],check=True)
def run(variant, expected):
    classes = out/('baseline' if variant=='baseline' else 'corrected')/'classes'
    cmd = ['java','-cp',os.pathsep.join(map(str,[out,classes]+testcp)),'JUnitXmlRunner','com.android.purebilibili.feature.settings.'+name,str(out/(variant+'.xml'))]
    result = subprocess.run(cmd,cwd=out/variant,text=True,capture_output=True)
    (out/(variant+'.log')).write_text('command: '+repr(cmd)+'\nexit: '+str(result.returncode)+'\n'+result.stdout+result.stderr)
    print(variant,'exit',result.returncode,result.stdout.strip(),flush=True)
    if result.returncode != 1: raise RuntimeError((variant,result.returncode,result.stderr))
    xml=ET.parse(out/(variant+'.xml')).getroot()
    failures={c.attrib['name'] for c in xml.findall('testcase') if c.find('failure') is not None}
    if xml.attrib.get('tests')!='25' or xml.attrib.get('skipped')!='0' or failures!=expected: raise RuntimeError((variant,xml.attrib,failures,expected))
identity='settingsSearchSubmitsResultFocusBeforeOpeningDetailPage()'
remaining={'aboutDevelopers_useGithubProfilesAndClickableAvatars()', 'tabletSettingsShell_usesNavDrivenCategoryRail()', 'feedApiSection_usesNativeSwitchAndSliderItems()', 'aboutProjectOverview_usesRasterLauncherAssetForComposePainter()', 'aboutSectionShowsProjectOverviewAndStaticDevelopersBeforeRows()', 'settingsSubpages_useNagramStyleSectionNames()', 'broadSettingsEntriesOpenAtPageStartWhileSubtopicEntriesUsePreciseFocus()'}
run('baseline',remaining|{identity})
run('corrected',remaining)
submit='SettingsSearchFocusController.submit(result.target, result.focusId, result.settingId.takeUnless { it.startsWith("legacy.") }, query = searchQuery)'
branch='if (result.target == com.android.purebilibili.feature.settings.SettingsSearchTarget.NEARBY_HANDOFF) {\n                                nearbyHandoff?.openDevices?.invoke()\n                            } else onSearchResultClick(result)'
cases=[
 ('negative-focus',submit,submit.replace('result.focusId','null')),
 ('negative-query',submit,submit.replace('query = searchQuery','query = ""')),
 ('negative-legacy',submit,submit.replace('result.settingId.takeUnless { it.startsWith("legacy.") }','result.settingId')),
 ('negative-order',submit+'\n                            '+branch,branch+'\n                            '+submit),
 ('negative-route',branch,branch.replace('SettingsSearchTarget.NEARBY_HANDOFF','SettingsSearchTarget.ANIMATION')),
]
for variant,before,after in cases:
    shutil.copytree(out/'corrected',out/variant,ignore=shutil.ignore_patterns('classes'))
    f=out/variant/source; text=f.read_text(); assert text.count(before)==1,(variant,text.count(before)); f.write_text(text.replace(before,after))
    run(variant,remaining|{identity})
print('Verified exact 8 -> 7 failure identities, no skips, and five negative mutations.')
