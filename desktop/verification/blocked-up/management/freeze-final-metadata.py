from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def ext(p):
    name=str(p.absolute());return Path(name if name.startswith('\\\\?\\') else '\\\\?\\'+name)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(n,v):(HERE/n).write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
priorContract=sha(HERE/'contract.json');priorManifest=sha(HERE/'artifact-manifest.json')
assert priorContract=='4f5168e5e1774abe1cb8d913badc2afaf55e9d9ce037ae026176effabc8b584f'
assert priorManifest=='375072286a998278001c8d9a9bc9797e10d5661983595259805b150c78a7721c'
prior=json.loads((HERE/'artifact-manifest.json').read_text())
for row in prior['files']:assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
contract=json.loads((HERE/'contract.json').read_text())
owned=[row for row in contract['ownedFiles'] if '__pycache__' not in row['path'] and not row['path'].endswith('.pyc')]
assert len(owned)==20
write('owned-files-final.json',owned)
files=prior['files']+[dict(path=p,sha256Bytes=sha(HERE/p)) for p in ['owned-files-final.json','FINAL-HANDOFF.md','freeze-final-metadata.py']]
write('stage2-artifact-manifest.json',dict(frozen=True,extendedWin32Paths=True,files=files,
    metadataCorrectionOnly=True,sourceAndRuntimeEvidenceUnchanged=True,priorMetadataManifestSha256=priorManifest))
contract.update(ownedFiles=owned,ownedFilesManifest='owned-files-final.json',artifactManifest='stage2-artifact-manifest.json',
    artifactManifestSha256=sha(HERE/'stage2-artifact-manifest.json'),supersedesMetadataOnlyContractSha256=priorContract,
    compiledOutput='classes-foundation-product',productSnapshot='desktop/.local/blocked-up-foundation-product-snapshot/manifest.json',
    productSnapshotSha256='74165f272e012c1a13e9171e963bf76127dbd71e7003da9e2576b7af2a25e2e6',
    foundationRuntimeAndRestoreFenceNotOverridden=True,stage1FrozenArtifactsUnchanged=True)
write('stage2-contract.json',contract)
for row in files:assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
print('Final frozen handoff: 20 owned,',len(files),'raw artifacts; contractSHA',sha(HERE/'stage2-contract.json'))
print('ArtifactManifestSHA',sha(HERE/'stage2-artifact-manifest.json'))
