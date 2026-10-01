from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
snap=MAIN/'desktop/.local/stable-product-snapshot-17';cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text())
assert hashlib.sha256(safe(snap/'manifest.json').read_bytes()).hexdigest()=='3b56a4a47fbd1d06b1d3b4be274bc3f69597dea669f04358eb7282292ab51e44'
assert hashlib.sha256(safe(snap/'ordered-runtime-cp.json').read_bytes()).hexdigest()=='25533b03785344f3a4a360a6db292775ad370824449c7a9c3a5b98702e3812d6'
for r in cp:assert hashlib.sha256(safe(r['path']).read_bytes()).hexdigest()==r['sha256Bytes'],r['path']
excluded={'HomeNavigationIconPolicy','HomeScreen','SettingsManager','HomeHeader','HomeTopTabChrome','HomeTopTabFloatingDock','HomeTopControls','MineSideDrawer','MineSideDrawerVisualPolicy','MineSideDrawerLayoutPolicy','SideBar','TopBar','HomeSettingsUiPresetPolicy','AppNavigationAppearancePolicy','HomeScrollCoordinator','HomeBottomBarModePolicy','HomeBottomBarScrollPolicy','NavigationUiPolicy','VideoCardTransitionClock','TodayWatchStartupRevealPolicy','HomeDrawerLogoutPolicy','HomeTopContentSpacingPolicy','HomeAvatarActionPolicy','HomeSystemBarsPolicy','HomeTopTabRevealPolicy','HomeFeedSkeletonCard','HomeFeedSkeletonVisualSpec'}
overlay=MAIN.parent/'BiliPai-v023/desktop/.local/stable-frosted-audio-renderer-parity/runs/07/candidate.jar'
assert hashlib.sha256(safe(overlay).read_bytes()).hexdigest()=='c7b98796bd68b7a5f019b09c5fe4c526872dcdd968842740338ab4eaa4e84bae'
files=[p for p in (HERE/'prepared/generated').rglob('*.kt') if p.stem not in excluded]+list((HERE/'prepared/manual').rglob('*.kt'))
ccspec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(ccspec);ccspec.loader.exec_module(cc)
number=1+len(list(HERE.glob('compile-category-??.log')))
args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(cc.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+str(snap/'main-kotlin.jar')+','+str(overlay),'-cp',str(overlay)+';'+';'.join(r['path'] for r in cp),'-d',str(HERE/'classes-category')]+list(map(str,files))
argfile=HERE/f'compile-category-{number:02}.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
safe(HERE/f'compile-category-{number:02}.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr[-20000:]).encode('ascii','backslashreplace').decode());print('exit',r.returncode,'sources',len(files));sys.exit(r.returncode)
