from pathlib import Path
import hashlib,json,os,subprocess
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def data(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(data(p)).hexdigest()
def text(p):return data(p).decode('utf8').replace('\r\n','\n')
def lf(p):return hashlib.sha256(text(p).encode()).hexdigest()
def write(p,v):wide(p).write_text(v,encoding='utf8',newline='\n')
result=json.loads(text(P/'runs/05/result.json'));assert result['passed'] and result['sourceCount']==11
for source in result['explicitProspectiveSources']:assert sha(source['path'])==source['sha256']
assert json.loads(text(P/'payload-proof/02/result.json'))['passed']
assert json.loads(text(P/'source-producer-proof.json'))['passed']
hunks=json.loads(text(P/'install-hunks.json')); assert len(hunks)==24
families=[]
for path in dict.fromkeys(h['path'] for h in hunks):
 before=text(C/path);after=before
 for h in [h for h in hunks if h['path']==path]:
  assert after.count(h['before'])==1,(path,h['name'])
  after=after.replace(h['before'],h['after'])
 inverse=after
 for h in reversed([h for h in hunks if h['path']==path]):
  assert inverse.count(h['after'])==1;inverse=inverse.replace(h['after'],h['before'])
 assert inverse==before
 expected=P/'prepared/existing'/path
 if expected.exists():assert text(expected)==after
 families.append(dict(path=path,beforeSha256LF=hashlib.sha256(before.encode()).hexdigest(),afterSha256LF=hashlib.sha256(after.encode()).hexdigest(),exactForwardInverse=True))
manual=list((P/'prepared/manual').rglob('*.kt'));assert len(manual)==2
copy=[dict(source=str(f.relative_to(P)).replace('\\','/'),target=str(f.relative_to(P/'prepared/manual')).replace('\\','/'),sha256Bytes=sha(f),sha256LF=lf(f))for f in manual]
originals=[]
for path in ['app/src/main/java/com/android/purebilibili/feature/story/StoryScreen.kt','app/src/main/java/com/android/purebilibili/feature/story/StoryViewModel.kt']:
 git=subprocess.run(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+path],cwd=C,capture_output=True,check=True).stdout
 assert git.decode('utf8').replace('\r\n','\n')==text(C/path)
 originals.append(dict(path=path,gitCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',sha256LF=lf(C/path),blobSha1=subprocess.run(['git','rev-parse','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+path],cwd=C,capture_output=True,check=True).stdout.decode().strip()))
contract=dict(schemaVersion=1,copyWhitelist=copy,exactHunks='install-hunks.json',familyPins=families,registryDelta='registry-delta.json',registryOperation='union features; preserve existing modes; add one StoryScreen policy-extract row',expectedNewIdentityCount=1,gradleChanges=0,generatedCopiesAllowed=False,jarCopiesAllowed=False,originalSources=originals,actualCompileSnapshot=83,compileResult='runs/05/result.json',dtoSessionProof='payload-proof/02/result.json',sourceReplay='source-producer-proof.json',nativeAckAcceptance=False,rootRuntimeAcceptance=False,networkAcceptance=False)
write(P/'install-contract.json',json.dumps(contract,ensure_ascii=False,indent=2)+'\n')
write(P/'ROOT-INTEGRATION.md','''The only install payloads are the two manual Kotlin sources listed in install-contract.json. Apply the 24 ordered local edits from install-hunks.json; never copy prepared/existing whole families or any generated files/classes/JAR. Merge registry-delta.json by original path, preserve existing mode/features, and add StoryScreen as one policy-extract identity. The existing StoryTopic task/sourceDir already owns these outputs and its feature-filtered inputs include the added story-topic row; there is no new Gradle/dependency task.

Production targets and before/after LF hashes are in install-contract.json. Parent reported HEAD 2055ce3b5 with untouched target families; exact forward/inverse verifies the current target bytes. The compile baseline remains immutable actual83/101, explicitly including eleven prospective source inputs. The Main DesktopShell exact edit replaces the old DesktopStoryScreen consumer and its StoryHost queue/native callbacks with DesktopOriginalStoryPhysicalLeaf; no fallback to that legacy UI is retained in the Story branch.

Story uses complete original Screen and complete original feed ViewModel class; the existing sole StoryUiState remains in its original StoryTopic output. RootWindowPlatforms owns StoryFeedOwners using the existing Assembly scope. RootStack reconciles actual retained Story keys; a covered entry retains its original feed VM. Removed stack keys cancel their feed scopes, and platform close after Assembly drain cancels the remaining scopes. Inactive outgoing content cannot create a missing feed owner or attach the movable native carrier. All current/active Story surface, player, presentation, settings, coin, folders, follow groups, comments/composer and share delegates are existing canonical actors/domains.

The same Portrait platform is used by Story and Holder fullscreen. Only the main page operation calls capturePageRequest; prefetch calls keep capturePlaybackRequest without changing Session. beginDesktopPortraitLoad invokes the original old-media progress/heartbeat/checkpoint sequence before clearPlaybackForReplacement, cancels pending original jobs, and creates the original SessionStore request token under the actual environment admission. capturePageRequest checks that precise token after the real suspension/capture. Captures retain true caller Job, original token and native baseline.

prepareSource carries actual ViewInfo/PlayUrlData/selected stream plus the actual related list and admitted account projection into the existing VideoLoadResult.Success payload schema. Resolved CID must be positive; it is never inferred from the seed. CDN rewritten Source URLs are preserved, while quality and codec derive from the actual selected original raw track. This Pager path publishes a legacy video/audio pair, so it does not claim adaptive MPD. Native publish checks captured token/BVID, caller cancellation, actual binding/epoch/receipt, entry and local Pager generation BEFORE native acceptance and again in its queued initial publication; the canonical one-shot ACK/accepted-source lifetime is unchanged.

Only after that same native accepted publication, adoptDesktopPortraitLoad rechecks the exact token/caller/epoch/entry/current accepted object in captured Binding admission and uses the complete original VM Success constructor. It updates resolved Session CID, UI and subject, then performs the original Mini/post-load/notes/playlist/analytics steps outside that short gate. It never calls loadVideo/fetch/prepare again. Interaction gates also require the current VM/engagement subject to match BVID/AID/CID. The original comments/composer and share bodies are retained. Story rotate-to-detail preserves snapshot CID/cover and sourceRoute; its no-snapshot fallback invokes the actual Windows presentation port.

Evidence: runs/05 is an eleven-input prospective narrow compile; source-producer-proof verifies three sole producer replays, complete Story Screen and VM class inverse, and unchanged other producer outputs; payload-proof/02 runs 18 original DTO/Session/receipt assertions without any VM/UI Success/native source/API proxy/HTTP/Window. It verifies the actual original token policy, resolved CID, selected quality/backup URL, codecs, duration, tracks, and retirement of the original account receipt. No new ACK/runtime test was performed here; actual NativeOwner/ACK guard behavior remains the previously installed canonical implementation.

Root whole compilation and physical Story/swipe/coin/favorite/comment/share acceptance are pending after installation. Ordinary guest real-media HTTP 412 remains the failed actual83 evidence; this packet does not fabricate or seed UI Success to bypass it. Authorized ExternalMedia acceptance is a different branch. No production edits, real account, new client/cache/Store/player or additional dependency are in this packet.
''')
excluded_suffix={'.class','.jar','.pyc'}
files=[]
for f in sorted(P.rglob('*')):
 if not f.is_file() or f.name=='frozen-handoff.json' or f.suffix in excluded_suffix or '__pycache__' in f.parts:continue
 files.append(dict(path=str(f.relative_to(P)).replace('\\','/'),sha256Bytes=sha(f),bytes=f.stat().st_size))
write(P/'frozen-handoff.json',json.dumps(dict(schemaVersion=1,scope='Full original Story and same captured Portrait page adoption source-only',artifacts=files,artifactCount=len(files),installContract='install-contract.json',copyWhitelistCount=2,orderedHunkCount=24,productionFamilyCount=len(families),newOriginalIdentityCount=1,compilePassed=True,dtoAssertions=18,rootRuntimeAccepted=False,http412StillUnresolved=True),ensure_ascii=False,indent=2)+'\n')
print('FROZEN',len(files),'raw; manifest',sha(P/'frozen-handoff.json'));print('contract',sha(P/'install-contract.json'));print(json.dumps(families,indent=2))
