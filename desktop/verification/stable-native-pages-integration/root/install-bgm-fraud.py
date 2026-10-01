from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
BGM=REPO/'desktop/.local/stable-bgm-detail';FRAUD=MAIN/'desktop/.local/stable-comment-fraud-protocol-parity'
def ext(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return ext(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(p):return read(p).decode().replace('\r\n','\n')
def write(p,b):ext(p).parent.mkdir(parents=True,exist_ok=True);ext(p).write_bytes(b)
bgm_raw=read(BGM/'frozen-handoff.json');assert sha(bgm_raw)=='82eec7ad6bb03bb6799647fec4fcbb1dd27cef605f034dd6aeafb84742f143cd'
bgm=json.loads(bgm_raw)
for row in bgm['payloads']:assert sha(read(Path(row['preparedPath'])))==row['sha256Bytes'],row['path']
fraud_raw=read(FRAUD/'frozen-handoff.json');assert sha(fraud_raw)=='8fb09519dcebd0f3d9f43bbcc2b3b15fe28ee35f3f6f6aeb40e2dc26ff74e24f'
for row in json.loads(fraud_raw)['artifacts']:
    data=read(FRAUD/row['path']);assert sha(data)==row['sha256Bytes'] and len(data)==row['byteSize'],row['path']
pending={};inputs=[]
for row in bgm['payloads']:
    path=row['path']
    if not path.startswith('desktop/'):continue
    target=REPO/path
    if row.get('baseLfSha256'):
        assert sha(lf(target).encode())==row['baseLfSha256'],path
    else:assert not ext(target).exists(),path
    if path.endswith('DesktopVideoMusicEntries.kt'):continue # Exact full-file replacement is declared in the frozen hunk recipe.
    pending[path]=read(Path(row['preparedPath']))
for row in json.loads(read(BGM/'root-recipe.json')):
    path=row['path'];before=pending[path].decode() if path in pending else lf(REPO/path)
    assert before.count(row['old'])==1,(path,row['purpose'],before.count(row['old']))
    after=before.replace(row['old'],row['new'],1);pending[path]=after.encode()
    inputs.append(dict(path=path,purpose=row['purpose'],beforeSha256LF=sha(before.encode()),afterSha256LF=sha(after.encode())))
assert sha(pending['desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoMusicEntries.kt'])==next(x['sha256Bytes'] for x in bgm['payloads'] if x['path'].endswith('DesktopVideoMusicEntries.kt'))
# Root already compiles the exact original shape as a sole direct source.
generator='desktop/tools/extract-upstream-bgm-detail.py';body=pending[generator].decode()
old="['core/ui/LocalNavigationBackHandler','feature/video/ui/components/VideoCardSkeleton','feature/video/ui/VideoDetailShapes']"
assert body.count(old)==1
pending[generator]=body.replace(old,"['core/ui/LocalNavigationBackHandler','feature/video/ui/components/VideoCardSkeleton']",1).encode()
for path in ('desktop/tools/extract-upstream-comment-fraud-protocol.py','desktop/src/main/kotlin/com/android/purebilibili/data/repository/DesktopCommentFraudRawTransport.kt'):
    assert not ext(REPO/path).exists();pending[path]=read(FRAUD/'prepared'/path)
operations='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt';body=lf(REPO/operations)
assert body.count('// STABLE_ORIGINAL_BGM_MEMBERS')==0 and body.count('// STABLE_ORIGINAL_COMMENT_FRAUD_MEMBERS')==0
end=body.rfind('\n}');assert end>0 and not body[end+2:].strip()
fraud_fragment='// STABLE_ORIGINAL_COMMENT_FRAUD_MEMBERS\n'+lf(FRAUD/'operations-member.fragment.kt').lstrip('\n').rstrip('\n')
bgm_fragment=lf(BGM/'ops-bgm-members.ktfrag').lstrip('\n').rstrip('\n')
pending[operations]=(body[:end].rstrip('\n')+'\n\n'+fraud_fragment+'\n\n'+bgm_fragment+'\n\n}\n').encode()
registry_path='desktop/upstream-sources.json';registry=json.loads(read(REPO/registry_path));rows={r['path']:r for r in registry['sources']};recipe=json.loads(read(BGM/'registry-recipe.json'));added=[]
for row in recipe['appendRows']:
    if row['path'] in rows:
        assert rows[row['path']]['sha256']==row['sha256'];rows[row['path']]['features']=list(dict.fromkeys(rows[row['path']]['features']+row['features']))
    else:registry['sources'].append(row);rows[row['path']]=row;added.append(row['path'])
for row in recipe['existingFeatureMerge']:
    actual=rows[row['path']];assert actual['sha256']==row['sha256']
    actual['features']=list(dict.fromkeys(actual['features']+row['appendFeatures']))
comment=rows['app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt'];comment['features']=list(dict.fromkeys(comment['features']+['stable-comment-fraud-protocol']))
pending[registry_path]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
for path,data in pending.items():write(REPO/path,data)
report=dict(bgmFrozenManifestSha256Bytes=sha(bgm_raw),fraudFrozenManifestSha256Bytes=sha(fraud_raw),
    preciseHunks=inputs,newSourceRows=added,sourceCount=len(registry['sources']),resourceCount=len(registry['resources']),
    payloads=[dict(path=p,sha256Bytes=sha(b)) for p,b in pending.items()],
    directVideoDetailShapesReused=True,bgmGeneratedSourceCount=30,wholeClassesPassed=False,realAccountAccepted=False)
write(HERE/'bgm-fraud-install.json',(json.dumps(report,indent=2)+'\n').encode())
print(json.dumps(dict(installedPayloads=len(pending),preciseHunks=len(inputs),newSourceRows=len(added),sourceCount=len(registry['sources']))))
