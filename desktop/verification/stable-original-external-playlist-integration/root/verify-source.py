from pathlib import Path
import hashlib,json,subprocess,textwrap,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def raw(p):return Path(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(p):return raw(p).replace(b'\r\n',b'\n')
registry=json.loads(raw(REPO/'desktop/upstream-sources.json'))
assert registry['upstreamCommit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert len(registry['sources'])==1170 and len(registry['resources'])==213
searchReceipt=json.loads(raw(REPO/'desktop/build/generated/search/captured-video-search-selection.json'))
searchPath=REPO/'desktop/build/generated/search/com/android/purebilibili/data/repository/DesktopOriginalCapturedVideoSearch.kt'
selected=textwrap.dedent(lf(searchPath).decode().split(') {\n',1)[1].rsplit('\n}\n',1)[0])
assert sha(selected.encode())==searchReceipt['generatedSelectedSHA256LF']
for change in reversed(searchReceipt['adaptations']):
 i=change['index'];assert selected[i:i+len(change['after'])]==change['after']
 selected=selected[:i]+change['before']+selected[i+len(change['after']):]
assert sha(selected.encode())==searchReceipt['originalSelectedSHA256LF']
original=subprocess.check_output(['git','show',registry['upstreamCommit']+':'+searchReceipt['original']],cwd=REPO)
assert original==lf(REPO/searchReceipt['original'])
assert sha(original)==searchReceipt['sourceSHA256LF']
row=next(r for r in registry['sources']if r['path']==searchReceipt['original']);assert row['sha256']==sha(original)
outputs=json.loads(raw(REPO/'desktop/build/generated/music-player-full/music-outputs.json'))
domainRow=next(r for r in outputs if Path(r['path']).name=='DesktopOriginalExternalPlaylistDomain.kt')
domain=lf(domainRow['path']).decode().split(') {\n',1)[1].split('\n    suspend fun searchVideo',1)[0]
changes=domainRow['selected']['adaptations']
for change in reversed(changes):
 i=change['index'];assert domain[i:i+len(change['after'])]==change['after']
 domain=domain[:i]+change['before']+domain[i+len(change['after']):]
assert sha(domain.encode())==domainRow['selected']['originalSelectedSHA256LF']
original=subprocess.check_output(['git','show',registry['upstreamCommit']+':'+domainRow['original']],cwd=REPO)
assert original==lf(REPO/domainRow['original'])
row=next(r for r in registry['sources']if r['path']==domainRow['original']);assert row['sha256']==sha(original)
adaptations=json.loads(raw(REPO/'desktop/build/generated/music-player-full/music-adaptations.json'))
relative='feature/audio/screen/ExternalPlaylistImportDialog.kt'
dialogRow=next(r for r in outputs if r['original'].endswith(relative))
dialog=lf(dialogRow['path']).decode()
for change in reversed(adaptations[relative]):
 i=change['index'];assert dialog[i:i+len(change['after'])]==change['after']
 dialog=dialog[:i]+change['before']+dialog[i+len(change['after']):]
original=subprocess.check_output(['git','show',registry['upstreamCommit']+':app/src/main/java/com/android/purebilibili/'+relative],cwd=REPO)
assert dialog.encode()==original
alias=lf(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/audio/DesktopAudioRepository.kt').decode()
assert 'val externalPlaylistCalls: Call.Factory get() = lyricsClient' in alias
binding=lf(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalExternalPlaylistBinding.kt').decode()
assert 'OkHttpClient' not in binding and 'DesktopPluginStore(' not in binding
report=dict(passed=True,sourceRegistryCount=1170,resources=213,upstreamCommit=registry['upstreamCommit'],
 searchSelectedBodyInverse=True,searchGuards=len(searchReceipt['adaptations']),
 externalSelectedBodyInverse=True,externalPlatformEdits=len(changes),
 fullOriginalDialogInverse=True,dialogPlatformEdits=len(adaptations[relative]),
 samePublicAudioClientAlias=True,newClientOrStoreInBinding=False,
 bindingRuntimeAccepted=False,completeRootMounted=False)
(H/'source-verification.json').write_bytes((json.dumps(report,indent=2)+'\n').encode())
print(json.dumps(report))
