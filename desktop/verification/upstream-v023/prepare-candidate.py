"""Import a verified upstream delta into an isolated Windows candidate."""
from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent
BASE=HERE.parents[2]
CANDIDATE=BASE.parent/'BiliPai-v023'
AUDIT=json.loads((HERE/'source-delta.json').read_bytes())
COMMIT=AUDIT['candidateCommit']
def git(*args):
    return subprocess.check_output(['git','-c','core.longpaths=true',*args],cwd=CANDIDATE)
def sha(raw): return hashlib.sha256(raw).hexdigest()
assert git('rev-parse','HEAD').decode().strip()==AUDIT['currentProductHead']
assert git('branch','--show-current').decode().strip()=='desktop/v023-parity'
assert not git('status','--porcelain'), 'Candidate contains edits before import'
paths=git('diff','--name-only','--no-renames',AUDIT['currentCommit'],COMMIT).decode().splitlines()
assert len(paths)==269
preserved=[p for p in paths if p.startswith(('.github/workflows/','.github/upstream-workflows/')) or p=='AGENTS.md']
imported=[p for p in paths if p not in preserved]
assert all(not p.startswith('desktop/') and not p.startswith('../') and not Path(p).is_absolute() for p in imported)
spec=HERE/'candidate-pathspec.nul'
spec.write_bytes(b'\0'.join(p.encode() for p in imported)+b'\0')
git('restore','--source',COMMIT,'--staged','--worktree','--pathspec-from-file='+str(spec),'--pathspec-file-nul')
meta_path=CANDIDATE/'desktop/upstream-sources.json'
meta=json.loads(meta_path.read_bytes())
rows={r['path']:r for r in AUDIT['registeredIdentityReview']}
for item in meta['sources']+meta.get('resources',[]):
    row=rows[item['path']]
    assert row['candidateSha256'] is not None
    raw=(CANDIDATE/item['path']).read_bytes()
    normalized=raw if row['hashNormalization']=='raw' else raw.replace(b'\r\n',b'\n')
    assert sha(normalized)==row['candidateSha256'],item['path']
    item['sha256']=row['candidateSha256']
meta.update(upstreamTag='v0.2.3',upstreamCommit=COMMIT,windowsRevision=1)
meta['lastSourceReview']=dict(status='platformAdaptationPending',
    changedReusedSources=[r['path'] for r in rows.values() if r['status']!='unchanged'],
    featuresNeedingReview=AUDIT['affectedFeatureTags'],
    note='Immutable source import only; existing extractors and new features still require alignment. No release acceptance.')
meta_path.write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
record=dict(schema='v023-source-only-candidate-rebase-v1',baseHead=AUDIT['currentProductHead'],
    candidatePath=str(CANDIDATE),candidateBranch='desktop/v023-parity',upstreamTag='v0.2.3',upstreamCommit=COMMIT,
    ancestryNote='The personal Windows repository starts from a source snapshot and shares no Git ancestor with upstream. Plain merge refused unrelated histories; exact pinned path delta import is used instead.',
    importedPaths=imported,preservedOwnedPaths=preserved,
    all832CandidateRegistryPinsVerified=True,sourceOnly=True,compiled=False,runtimeAccepted=False,
    packaged=False,released=False,mainCheckoutChanged=False,
    candidateRegistrySha256Bytes=sha(meta_path.read_bytes()))
(HERE/'candidate-rebase.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(candidatePath=str(CANDIDATE),importedPaths=len(imported),preservedOwnedPaths=preserved,
    verifiedSourceAndResourcePins=832,registryCount=len(meta['sources']),compiled=False)))
