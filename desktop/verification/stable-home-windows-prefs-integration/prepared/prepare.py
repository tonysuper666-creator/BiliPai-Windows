from pathlib import Path
import difflib,hashlib,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def lf(data):return data.replace(b'\r\n',b'\n')
def sha(data):return hashlib.sha256(data).hexdigest()
def safe(path):
 value=str(path.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def main():
 path='app/src/main/java/com/android/purebilibili/core/util/FoldableDisplayPolicy.kt'
 data=subprocess.run(['git','-C',str(CANDIDATE),'show',COMMIT+':'+path],capture_output=True,check=True).stdout
 original=HERE/'original-source'/path;original.parent.mkdir(parents=True,exist_ok=True);original.write_bytes(data)
 source=lf(data).decode('utf-8');constant='internal const val LARGE_SCREEN_SMALLEST_WIDTH_DP = 600'
 start=source.index('internal fun resolveLargeScreenOrFoldableConfiguration(')
 end=source.index('\n',source.index('): Boolean = smallestScreenWidthDp >= LARGE_SCREEN_SMALLEST_WIDTH_DP || hasHingeAngleSensor',start))
 selected=constant+'\n\n'+source[start:end]
 assert source.count(constant)==1
 output=HERE/'prepared/desktop/generated/home-windows-prefs/com/android/purebilibili/core/util/DesktopHomeOriginalDeviceClassification.kt'
 safe(output.parent).mkdir(parents=True,exist_ok=True);safe(output).write_text('package com.android.purebilibili.core.util\n\n'+selected+'\n',encoding='utf-8',newline='\n')
 current=CANDIDATE/'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp';base=current.read_bytes();text=lf(base).decode('utf-8')
 assert 'BilipaiHomeNetworkSnapshot' not in text
 before='#include <winrt/Windows.ApplicationModel.DataTransfer.h>\n'
 after=before+'#include <winrt/Windows.Networking.Connectivity.h>\n'
 assert text.count(before)==1
 fragment=(HERE/'network-export.inc').read_text(encoding='utf-8')
 candidate=text.replace(before,after)+'\n'+fragment
 out=HERE/'native-candidate';out.mkdir(exist_ok=True)
 (out/'DesktopDiagnosticShare-base.cpp').write_bytes(base)
 (out/'DesktopDiagnosticShare.cpp').write_text(candidate,encoding='utf-8',newline='\n')
 (HERE/'native-local.patch').write_text(''.join(difflib.unified_diff(text.splitlines(True),candidate.splitlines(True),fromfile='a/desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp',tofile='b/desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp')),encoding='utf-8',newline='\n')
 value=dict(upstreamCommit=COMMIT,originalClassification=dict(path=path,sha256Bytes=sha(data),sha256LF=sha(lf(data)),selectedBodySha256LF=sha(selected.encode()),outputSha256Bytes=sha(safe(output).read_bytes())),nativeExactHunks=dict(basePath=str(current),baseSha256Bytes=sha(base),baseSha256LF=sha(lf(base)),candidateSha256LF=sha(candidate.encode()),headerBefore=before,headerAfter=after,append=fragment,allOriginalNativeBytesReverseNormalizedEqual=candidate[:-len('\n'+fragment)].replace(after,before)==text),newManualSource=[dict(path=str(p.relative_to(HERE)).replace('\\','/'),sha256Bytes=sha(safe(p).read_bytes())) for p in sorted((HERE/'prepared').rglob('*.kt'))],MainSharedModified=False)
 assert value['nativeExactHunks']['allOriginalNativeBytesReverseNormalizedEqual']
 (HERE/'source-inventory.json').write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps(dict(manualAndSelectedFiles=len(value['newManualSource']),nativeBase=value['nativeExactHunks']['baseSha256LF'],nativeCandidate=value['nativeExactHunks']['candidateSha256LF']),indent=2))
if __name__=='__main__':main()
