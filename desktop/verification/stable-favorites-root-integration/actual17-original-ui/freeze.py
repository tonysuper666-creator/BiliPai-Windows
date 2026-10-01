from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
STABLE=MAIN.parent/'BiliPai-v023'
def sha(b):return hashlib.sha256(b).hexdigest()
def save(p,obj):p.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def main():
 accepted=HERE/'runs/05/accepted-comparison.json';data=json.loads(accepted.read_text(encoding='utf-8'));assert data['status']=='PASS' and data['cells']==2 and data['productionClassOverrides']==0
 original='design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPrimitiveComponents.kt'
 raw=subprocess.run(['git','-C',str(STABLE),'show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+original],capture_output=True,check=True).stdout
 copy=HERE/'source-review/original-AppPrimitiveComponents.kt';copy.write_bytes(raw)
 source=raw.decode('utf-8');line=source[:source.index('val resolvedLabel = labelText ?: placeholderText.orEmpty()')].count('\n')+1
 save(HERE/'ui-boundaries.json',dict(
  originalMiuixFieldLabels=dict(commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',path=original,sha256Bytes=sha(raw),sha256LF=sha(raw.replace(b'\r\n',b'\n')),sourceLine=line,finding='Stable original AppOutlinedTextField MIUIX branch reads labelText/placeholderText, ignoring composable label slots. Original Sheet supplies composable labels, so two actual MIUIX editable fields have no title/intro label semantics. Preserved upstream behavior; no replacement labels/UI emitted.'),
  modalDismiss=dict(accepted='Both styles original Create Cancel and actual AWT-converted Escape close successfully.',outsidePointer='MATERIAL3 centered full-size Dialog did not dismiss after an outside pointer in run02. Actual result retained; no OS HWND/clickOutside guarantee claimed.'),
  failedAttempts=[dict(run='01',reason='Runner incorrectly decoded JVM stderr strictly as UTF-8; failed runner, not an acceptance. Compiler jar/input and partial artifacts retained.'),dict(run='02',reason='Fixture assumed centered Dialog outside-pointer dismissal; actual original full-size modal remained open. Raw stdout/stderr/log retained.'),dict(run='03',reason='MATERIAL3 all13 assertions passed; fixture incorrectly expected MIUIX composable field label slots. Raw result/error retained.'),dict(run='04',reason='Waiting does not produce original MIUIX composable labels; source identified original facade contract. Raw result/error retained.')],
  evidenceScope='Only original Main17 Sheet/CreateDialog pointer/text/rendered semantics were exercised. Session/protocol/Root classes are actual loaded-byte identities, not executed transport/button proof. No production override, native window/chooser/external app/HTTP/account operation.'))
 target=HERE/'frozen-handoff.json';assert not target.exists()
 rows=[]
 for p in sorted(HERE.rglob('*')):
  if p.is_file() and '__pycache__' not in p.parts:
   b=p.read_bytes();rows.append(dict(path=p.relative_to(HERE).as_posix(),bytes=len(b),sha256Bytes=sha(b)))
 save(target,dict(frozen=True,scope='actual immutable Main17 original favorite sheet/create dialog minimal offscreen UI proof plus separate Root source-only review',actualMain17ManifestSha256Bytes='3b56a4a47fbd1d06b1d3b4be274bc3f69597dea669f04358eb7282292ab51e44',orderedRuntimeCp92Sha256Bytes='25533b03785344f3a4a360a6db292775ad370824449c7a9c3a5b98702e3812d6',accepted=dict(path=accepted.relative_to(HERE).as_posix(),sha256Bytes=sha(accepted.read_bytes()),cells=2,assertions=26,pointerPairs=20,actualEditableTextActions=4,actualCodeSourceAndClassBytePinsPerCell=8,productionClassOverrides=0),RootSourceReview=dict(path='source-review/review.json',sha256Bytes=sha((HERE/'source-review/review.json').read_bytes()),currentRootFixedSourceSha256LF='8540b7e3ce7af141d84bc4bf48babea590477206f103ff7089d043c3b3411e8b',historicalMain17SourceSha256LF='8cd048a02735b9682416d8048288dcad2bca183de3c7c1f47eb87ab1668af796',currentRootSourceOnlyNotMain17Execution=True),artifactCount=len(rows),artifacts=rows,noSharedSourceEdits=True,noGradle=True,noNativeWindowsOrExternalApps=True,noAccountOrHttp=True))
 print(json.dumps(dict(path=str(target),sha256Bytes=sha(target.read_bytes()),artifacts=len(rows),acceptedSha256Bytes=sha(accepted.read_bytes())),ensure_ascii=False))
if __name__=='__main__':main()
