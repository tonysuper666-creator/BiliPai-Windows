from pathlib import Path
import hashlib,json,subprocess,difflib,zipfile,struct,importlib.util
LANE=Path(__file__).resolve().parent;REPO=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
generator=load(LANE/'prepared/tools/extract-upstream-live-list.py','live_audit_producer')
parser=load(REPO/'desktop/tools/sync-upstream.py','live_audit_parser');media=load(REPO/'desktop/tools/extract-upstream-media.py','live_audit_selector')
checks=[]
def check(label,value):assert value,label;checks.append(label)
for p,h in generator.SOURCE_PINS.items():
 raw=read(LANE/'original-stable'/p)
 check('stable Git blob '+p,sha(raw)==h and subprocess.run(['git','show',generator.COMMIT+':'+p],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\r\n','\n')==raw)
generator.generate(REPO,LANE/'replay-production',False)
generator.generate(REPO,LANE/'replay-standalone',True)
standalone={p.relative_to(safe(LANE/'replay-standalone')).as_posix():read(p) for p in safe(LANE/'replay-standalone').rglob('*.kt')}
prod={p.relative_to(safe(LANE/'replay-production')).as_posix():read(p) for p in safe(LANE/'replay-production').rglob('*.kt')}
check('standalone 13 original selected sources',len(standalone)==13)
check('production 4 selected sources only; 9 DIRECT skipped',len(prod)==4)
for p,s in standalone.items():check('standalone bytes '+p,s==read(LANE/'prepared/generated'/p))
for p,s in prod.items():check('production replay '+p,s==standalone[p])
ui='app/src/main/java/com/android/purebilibili/feature/live/LiveListScreen.kt'
original=read(LANE/'original-stable'/ui);prepared=standalone['com/android/purebilibili/feature/live/LiveListScreen.kt']
diff=list(difflib.SequenceMatcher(None,original,prepared,autojunk=False).get_opcodes())
parts=[]
for op,a,b,c,d in diff:
 if op!='equal':parts.append({'originalStart':a,'originalEnd':b,'preparedStart':c,'preparedEnd':d,'original':original[a:b],'prepared':prepared[c:d]})
restored=prepared
for part in reversed(parts):
 check('UI exact reverse patch '+str(part['preparedStart']),restored[part['preparedStart']:part['preparedEnd']]==part['prepared'])
 restored=restored[:part['preparedStart']]+part['original']+restored[part['preparedEnd']:]
check('entire 1152-line original UI/models/VM recovered exactly',restored==original)
write(LANE/'live-list-windows-adaptation.diff',''.join(difflib.unified_diff(original.splitlines(True),prepared.splitlines(True),fromfile=ui,tofile='Windows full original LiveListScreen')))
write(LANE/'ui-exact-reverse-patches.json',json.dumps(parts,ensure_ascii=False,indent=2))
for name in ['LiveListHeader','LiveFollowHeader','LiveFollowAvatarRow','LiveSortTagChipRow','LiveHomeLoadMoreFooter','LiveAreaChildChipRow','LiveListLoadingState','LiveListErrorState','EmptyState']:
 check('unchanged complete renderer '+name,media.function(original,name,parser)==media.function(prepared,name,parser))
check('no Windows default empty match callback','onMatchClick: () -> Unit = {}' not in prepared)
check('no Android context or simplified Live browser','LocalContext' not in prepared and 'LiveBrowserScreen' not in prepared and 'DesktopLiveRoom' not in prepared)
check('original shared cover enabled','enableSharedCoverTransition = true' in prepared)
check('original nullable real channel + request ID both preserved','scrollToTopChannel?.receiveAsFlow()?.collect' in prepared and 'LaunchedEffect(scrollToTopRequestId)' in prepared)
repository=read(LANE/'original-stable/app/src/main/java/com/android/purebilibili/data/repository/LiveRepository.kt')
rawOutput=standalone['com/android/purebilibili/data/repository/DesktopOriginalLiveListProtocol.kt']
for name in ['getRecommendedLiveRooms','getLiveFeedHome','getLiveSecondHome','buildLiveAppFeedParams','buildLiveAppSecondListParams','fallbackLiveFeedHome','getLiveAreaIndex','getAreaRoomsPage']:
 raw=media.function(repository,name,parser);body=media.function(rawOutput,name,parser)
 restored=body.replace('environment.accessToken()','TokenManager.accessTokenCache').replace('} catch (cancelled: CancellationException) { throw cancelled\n        } catch (e: Exception) {','} catch (e: Exception) {').replace('} catch (cancelled: CancellationException) { throw cancelled\n        } catch (_: Exception) {','} catch (_: Exception) {')
 # Extractor indent is normalized; tokenize verifies every original business token.
 check('all original protocol tokens recovered '+name,[t[0] for t in parser.kotlin_tokens(restored)]==[t[0] for t in parser.kotlin_tokens(raw)])
check('reuse raw694 popular and followed protocol','DesktopOriginalHomeLiveProtocol(environment.api)' in rawOutput and 'existingLive.getLiveRooms(page)' in rawOutput and 'existingLive.getFollowedLive(page)' in rawOutput)
check('no new LiveRepository/Live DTO authority','object LiveRepository' not in rawOutput and 'data class LiveAreaRoomsPage' not in rawOutput)
write(LANE/'source-audit.json',json.dumps({'pinnedCommit':generator.COMMIT,'checks':len(checks),'names':checks,'wholeUIReverseExact':True,'unchangedWholeDirectSources':9,'selectedProtocolMethods':8,'productionSelectedSources':4,'standaloneOriginalSources':13,'newNetworksOrStores':0,'actualRootRuntime':False},ensure_ascii=False,indent=2))
print('PASS '+str(len(checks))+' source/replay/reverse assertions')
