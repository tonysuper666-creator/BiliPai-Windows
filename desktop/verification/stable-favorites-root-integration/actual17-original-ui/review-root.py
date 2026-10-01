from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
STABLE=MAIN.parent/'BiliPai-v023'
SNAP=MAIN/'desktop/.local/stable-product-snapshot-17'
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.replace(b'\r\n',b'\n').replace(b'\r',b'\n')
def save(p,data):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def line(text,needle):return text[:text.index(needle)].count('\n')+1
def main():
 manifest=json.loads((SNAP/'manifest.json').read_text(encoding='utf-8'))
 rel='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoFavoriteRoot.kt'
 src=STABLE/rel;raw=src.read_bytes();text=lf(raw).decode('utf-8')
 pinned=next(x for x in manifest['inputs'] if x['path']==rel)
 assert 'VideoFavoriteAction.ToggleFavorite -> {\n                    quickSaving = true\n                    scope.launch {' in text
 copied=HERE/'source-review/current-root-fixed/DesktopVideoFavoriteRoot.kt';copied.parent.mkdir(parents=True,exist_ok=True);copied.write_bytes(raw)
 # Reconstruct ONLY the acknowledged two-line relocation and require exact manifest bytes.
 before=raw.replace(b'VideoFavoriteAction.ToggleFavorite -> {\r\n                    quickSaving = true\r\n                    scope.launch {',b'VideoFavoriteAction.ToggleFavorite -> {\r\n                    scope.launch {\r\n                    quickSaving = true')
 if before==raw:before=raw.replace(b'VideoFavoriteAction.ToggleFavorite -> {\n                    quickSaving = true\n                    scope.launch {',b'VideoFavoriteAction.ToggleFavorite -> {\n                    scope.launch {\n                    quickSaving = true')
 if sha(before)!=pinned['sha256Bytes']:
  alternative=text.replace('VideoFavoriteAction.ToggleFavorite -> {\n                    quickSaving = true\n                    scope.launch {','VideoFavoriteAction.ToggleFavorite -> scope.launch {\n                    quickSaving = true').replace('} finally { if (owned()) quickSaving = false }\n                    }\n                }','} finally { if (owned()) quickSaving = false }\n                }').encode('utf-8')
  if sha(alternative)==pinned['sha256Bytes']:before=alternative
 historicalExact=sha(before)==pinned['sha256Bytes']
 if historicalExact:
  history=HERE/'source-review/historical-main17/DesktopVideoFavoriteRoot.kt';history.parent.mkdir(parents=True,exist_ok=True);history.write_bytes(before)
 sources=[dict(path=str(src),copy=str(copied),sha256Bytes=sha(raw),sha256LF=sha(lf(raw)),cohort='Source-only current Root fix; not executed by Main17 UI fixture')]
 if historicalExact:sources.append(dict(path=str(history),sha256Bytes=sha(before),sha256LF=sha(lf(before)),cohort='Main17 input reconstructed by exact two-line inverse; byte hash equals immutable manifest input'))
 origin='app/src/main/java/com/android/purebilibili/feature/video/ui/components/FavoriteFolderSheet.kt'
 original=subprocess.run(['git','-C',str(STABLE),'show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+origin],capture_output=True,check=True).stdout
 (HERE/'source-review/original-FavoriteFolderSheet.kt').write_bytes(original)
 rows=[
  dict(id='owner-key',sourceLine=line(text,'key(aid, epoch)'),finding='Aid and account epoch replace the keyed drawer scope/session; callbacks read rememberUpdatedState projections.',status='PASS_SOURCE'),
  dict(id='epoch-and-page-admission',sourceLine=line(text,'fun owned()'),finding='Alive, child scope active, caller stillOwned, and captured sessionEpoch all gate mutations/receipts.',status='PASS_SOURCE'),
  dict(id='store-serialized-receipt',sourceLine=line(text,'guard.withCurrentDynamicCacheOwner(owner)'),finding='Receipt runs under the same Repository current-owner guard and rechecks page ownership before UI callback.',status='PASS_SOURCE'),
  dict(id='immutable-aid-callbacks',sourceLine=line(text,'DesktopFavoriteFolderEnvironment(scope'),finding='Folder operations bind keyed immutable aid; current count/loaded/saved/feedback callbacks read latest refs.',status='PASS_SOURCE'),
  dict(id='child-retirement',sourceLine=line(text,'onDispose { alive.set(false)'),finding='Disposal first denies admission, closes sole session, then cancels only child scope.',status='PASS_SOURCE'),
  dict(id='quick-saving-admission',sourceLine=line(text,'quickSaving = true'),finding='Current source marks quickSaving synchronously before scope.launch; finally clears only while the same keyed owner remains current.',status='FIXED_SOURCE_ONLY'),
 ]
 review=dict(status='NO_REMAINING_BLOCKER_IN_NARROW_CURRENT_ROOT_SOURCE',actualMain17ManifestSha256Bytes=sha((SNAP/'manifest.json').read_bytes()),sourceRows=sources,findings=rows,
  history=dict(issue='Main17 quickSaving was set inside launch, admitting two queued activations before the coroutine starts.',rootAcknowledged=True,currentFix='Synchronously mark quickSaving before launch; retain owned finally guard.',main17SourceInput=pinned,historicalInverseByteExact=historicalExact,Main18RuntimeValidatedByThisReview=False),
  original=dict(commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',path=origin,sha256Bytes=sha(original),sha256LF=sha(lf(original)),createCallbackContract='onConfirm(title,intro,isPrivate), without trimming title/intro; original create dialog clears after callback.',sheetCallbackContract='Folder item toggles supplied selection; Save invokes once per button click; cancel dismisses create dialog only.'),
  boundaries=['Source review only for Root callbacks/epoch/aid/scope; actual Main17 UI proof does not execute Root favorite entry or Session transport.','No Main/shared sources, Gradle, external HTTP/account, HWND/native windows or external applications operated.'])
 save(HERE/'source-review/review.json',review);print(json.dumps(dict(path=str(HERE/'source-review/review.json'),sha256Bytes=sha((HERE/'source-review/review.json').read_bytes()),historicalInverseByteExact=historicalExact),ensure_ascii=False))
if __name__=='__main__':main()
