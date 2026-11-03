#!/usr/bin/env python3
"""Run actual JUnit tests against unchanged source, plus isolated counterexamples."""
from pathlib import Path
import glob, os, re, shutil, subprocess, sys, xml.etree.ElementTree as ET
repo=Path(__file__).resolve().parents[3]
evidence=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path('settings-resource-evidence').resolve()
if evidence.exists(): raise SystemExit(f'Refusing to overwrite {evidence}')
evidence.mkdir(parents=True)
names=['SettingsRootCategoryContentStructureTest','SettingsMiuixSimplificationStructureTest']
tests=['app/src/test/java/com/android/purebilibili/feature/settings/'+n+'.kt' for n in names]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,artifact,version):
 files=glob.glob(str(cache/group/artifact/version/'*'/(artifact+'-'+version+'.jar')))
 if len(files)!=1: raise RuntimeError((artifact,version,files))
 return files[0]
compiler=[jar('org.jetbrains.kotlin',x,'2.4.0') for x in ('kotlin-compiler-embeddable','kotlin-stdlib','kotlin-script-runtime','kotlin-daemon-embeddable')]+[jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'),jar('org.jetbrains','annotations','23.0.0'),jar('org.jetbrains.kotlin','kotlin-reflect','2.3.20')]
testcp=[jar('org.jetbrains.kotlin',x,'2.4.0') for x in ('kotlin-stdlib','kotlin-test','kotlin-test-junit5')]+[jar('org.junit.jupiter',x,'5.13.4') for x in ('junit-jupiter-api','junit-jupiter-engine')]+[jar('org.junit.platform',x,'1.13.4') for x in ('junit-platform-commons','junit-platform-engine','junit-platform-launcher')]+[jar('org.opentest4j','opentest4j','1.3.0'),jar('org.apiguardian','apiguardian-api','1.1.2')]
subprocess.run(['javac','-cp',os.pathsep.join(testcp),'-d',str(evidence),str(Path(__file__).with_name('JUnitXmlRunner.java'))],check=True)
paths=set(tests+['app/src/main/res/values/strings.xml','app/src/main/java/com/android/purebilibili/feature/settings/SettingsRootCategoryCopy.kt'])
for test in tests:
 paths.update(re.findall(r'app/src/main/[\w/.-]+\.(?:kt|xml)',(repo/test).read_text()))
for variant in ('original','corrected'):
 d=evidence/variant
 for path in paths:
  if (repo/path).is_file():
   (d/path).parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(repo/path,d/path)
 if variant=='corrected':
  for test in tests: subprocess.run(['patch','--batch','-p1','-i',str(repo/('desktop/upstream-core/patches/'+test+'.patch'))],cwd=d,check=True)
 classes=d/'classes';classes.mkdir()
 subprocess.run(['java','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',os.pathsep.join(testcp),'-d',str(classes)]+[str(d/test) for test in tests],check=True)
def run(variant,index,expected=None):
 d=evidence/variant; n=names[index];classes=evidence/('original' if variant=='original' else 'corrected')/'classes'
 command=['java','-cp',os.pathsep.join(map(str,[evidence,classes]+testcp)),'JUnitXmlRunner','com.android.purebilibili.feature.settings.'+n,str(evidence/(variant+'-'+n+'.xml'))]
 p=subprocess.run(command,cwd=d,text=True,capture_output=True)
 (evidence/(variant+'-'+n+'.log')).write_text('command: '+repr(command)+'\nexit: '+str(p.returncode)+'\n'+p.stdout+p.stderr)
 print(variant,n,'exit',p.returncode,p.stdout.strip(),flush=True)
 if p.returncode!=1:raise RuntimeError((variant,n,p.returncode,p.stderr))
 xml=ET.parse(evidence/(variant+'-'+n+'.xml')).getroot()
 failures={case.attrib['name'] for case in xml.findall('testcase') if case.find('failure') is not None}
 if expected is not None and failures!=expected:raise RuntimeError((variant,n,failures,expected))
 return failures
old=[run('original',i) for i in range(2)]
fixed=[{'tabletSettingsCategoryDrawer_showsSubtitle()'}, {'animation settings keeps tuning but no independent glass toggles()', 'animation liquid glass section keeps tuning without independent switches()'}]
assert [len(x) for x in old]==[9,5] and all(fixed[i]<=old[i] for i in range(2)),old
for i in range(2):run('corrected',i,old[i]-fixed[i])
shell='app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsTabletShell.kt'
copy='app/src/main/java/com/android/purebilibili/feature/settings/SettingsRootCategoryCopy.kt'
animation='app/src/main/java/com/android/purebilibili/feature/settings/screen/AnimationSettingsScreen.kt'
strings='app/src/main/res/values/strings.xml'
cases=[
 ('negative-category-binding',0,shell,'text = stringResource(category.subtitleResource())','text = "broken"','tabletSettingsCategoryDrawer_showsSubtitle()'),
 ('negative-category-map',0,copy,'SettingsRootCategory.APPEARANCE_THEME -> R.string.settings_category_appearance_summary','SettingsRootCategory.APPEARANCE_THEME -> R.string.settings_category_home_summary','tabletSettingsCategoryDrawer_showsSubtitle()'),
 ('negative-blur-copy',1,strings,'<string name="animation_title_transition_blur">转场时模糊背景</string>','<string name="animation_title_transition_blur">错误</string>','animation settings keeps tuning but no independent glass toggles()'),
 ('negative-blur-binding',1,animation,'title = localizedAnimationSettingTitle("animation.video_transition_realtime_blur_enabled")','title = localizedAnimationSettingTitle("wrong.key")','animation settings keeps tuning but no independent glass toggles()'),
 ('negative-import-copy',1,strings,'<string name="animation_import_unchanged">预览图片和其他应用设置不会改变。</string>','<string name="animation_import_unchanged">错误</string>','animation liquid glass section keeps tuning without independent switches()'),
 ('negative-import-binding',1,animation,'text = stringResource(R.string.animation_import_unchanged)','text = stringResource(R.string.animation_import_details)','animation liquid glass section keeps tuning without independent switches()')]
for variant,index,path,before,after,identity in cases:
 shutil.copytree(evidence/'corrected',evidence/variant,ignore=shutil.ignore_patterns('classes'))
 f=evidence/variant/path;text=f.read_text();assert text.count(before)==1,(variant,text.count(before));f.write_text(text.replace(before,after))
 run(variant,index,(old[index]-fixed[index])|{identity})
print('verified original -> corrected exact failure sets and six negative mutations; raw XML/logs:',evidence)
