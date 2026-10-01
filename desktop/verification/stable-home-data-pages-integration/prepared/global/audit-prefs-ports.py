from pathlib import Path
import hashlib,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
CANDIDATE=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def pin(data):
 return dict(sha256Bytes=hashlib.sha256(data).hexdigest(),sha256LF=hashlib.sha256(data.replace(b'\r\n',b'\n')).hexdigest())
def main():
 originals=[]
 specs=[('core/store/SettingsManager.kt',[(1262,1262),(7600,7603)]),('core/util/FoldableDisplayPolicy.kt',[(227,237)]),('core/util/NetworkUtils.kt',[(38,42)])]
 for suffix,ranges in specs:
  path='app/src/main/java/com/android/purebilibili/'+suffix
  data=subprocess.run(['git','-C',str(CANDIDATE),'show',COMMIT+':'+path],capture_output=True,check=True).stdout
  target=HERE/'original-prefs-source'/path
  target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
  lines=data.decode('utf-8').splitlines()
  originals.append(dict(originalPath=path,gitCommit=COMMIT,**pin(data),anchors=[dict(start=a,end=b,source='\n'.join(lines[a-1:b])) for a,b in ranges]))
 scan=CANDIDATE/'desktop/src/main/kotlin'
 needles=['GetInternetConnectionProfile','IsWwanConnectionProfile','TRANSPORT_CELLULAR','isMobileNetwork','isMobileData','wwan']
 hits=[]
 for path in sorted(scan.rglob('*.kt')):
  for number,line in enumerate(path.read_text(encoding='utf-8').splitlines(),1):
   if any(word in line for word in needles):hits.append(dict(path=str(path.relative_to(CANDIDATE)).replace('\\','/'),line=number,source=line.strip()))
 current=[]
 for name in ['DesktopHomeWindowPlatform.kt','DesktopOriginalHomePreferences.kt']:
  path=scan/'com/bilipai/desktop/ui'/name
  current.append(dict(path=str(path),**pin(path.read_bytes())))
 result=dict(status='SOURCE_ONLY_REQUIRED_PLATFORM_PORTS_PENDING',originalSources=originals,currentSources=current,scanRoot=str(scan),caseSensitiveScanTokens=needles,scanMatches=hits,requiredPorts=[dict(name='defaultTabletUseSidebar',type='Boolean',original='smallestScreenWidthDp >= 600 || hasHingeAngleSensor; saved KEY_TABLET_NAVIGATION_MODE overrides default',windowsBinding='Required actual device/display classification with explicit Windows mapping; not supplied by current clientPolicy'),dict(name='isMobileNetwork',type='() -> Boolean',original='activeNetwork TRANSPORT_CELLULAR; no active network/capabilities -> false',windowsBinding='Required actual active cellular transport getter; not metered cost, no current implementation found in scanned source')],preferencesStore='Existing single DesktopPluginStore/settings, app lifetime, no MID or popup binding',networkOrWindowExecuted=False,MainOrSharedModified=False)
 target=HERE/'prefs-platform-port-review.json'
 target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps(dict(path=str(target),sha256Bytes=hashlib.sha256(target.read_bytes()).hexdigest(),originalFiles=len(originals),scanMatches=len(hits)),indent=2))
if __name__=='__main__':main()
