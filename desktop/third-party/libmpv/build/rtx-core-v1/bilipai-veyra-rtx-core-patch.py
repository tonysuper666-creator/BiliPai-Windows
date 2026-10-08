#!/usr/bin/env python3
"""Only fixed mpv source mutations. No SDK/runtime, binary, GPU or UI here."""
import hashlib,json,subprocess,sys
from pathlib import Path
EXPECTED_MANIFEST_SHA256 = "f9a4eb124583756ed90cd55d941a3dc2bf38d94de4805f4b3afcf9217ed83bbd"
def sha(data): return hashlib.sha256(data).hexdigest()
def main():
    if len(sys.argv)!=2: raise SystemExit('One isolated fixed mpv source directory required')
    root=Path(sys.argv[1]).resolve(strict=True);here=Path(__file__).resolve().parent
    manifest_raw=here.joinpath('bilipai-rtx-source-manifest.json').read_bytes()
    if sha(manifest_raw)!=EXPECTED_MANIFEST_SHA256: raise SystemExit('Bridge source manifest mismatch')
    manifest=json.loads(manifest_raw)
    head=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
    if head!=manifest['sourceCommit']: raise SystemExit('Wrong mpv source commit')
    registrations_raw=here.joinpath(manifest['registrationEditsFileName']).read_bytes()
    if sha(registrations_raw)!=manifest['registrationEditsSha256']: raise SystemExit('Registration recipe mismatch')
    nvidia=manifest['originalNvidiaPatch'];patch=here/'bilipai-nvidia-native-69e63f.patch'
    if sha(patch.read_bytes())!=nvidia['patchSha256']: raise SystemExit('Original Nvidia patch changed')
    pending=[];proof=[]
    target=root/nvidia['sourcePath'];raw=target.read_bytes();old=nvidia['old'].encode();new=nvidia['new'].encode()
    if sha(raw)==nvidia['originalSha256']:
        if raw.count(old)!=1: raise SystemExit('Native source anchor not unique')
        after=raw.replace(old,new,1)
        if sha(after)!=nvidia['patchedSha256'] or after.replace(new,old,1)!=raw: raise SystemExit('Native source inverse failed')
    elif sha(raw)==nvidia['patchedSha256']: after=raw
    else: raise SystemExit('Unreviewed native source')
    pending.append((target,after))
    for row in json.loads(registrations_raw):
        target=root/row['path'];raw=target.read_bytes();old=row['before'].encode();new=row['after'].encode()
        if sha(raw)==row['beforeSHA256']:
            if raw.count(old)!=1: raise SystemExit('Registration anchor not unique')
            after=raw.replace(old,new,1)
            if sha(after)!=row['afterSHA256'] or after.replace(new,old,1)!=raw: raise SystemExit('Registration inverse failed')
        elif sha(raw)==row['afterSHA256']: after=raw
        else: raise SystemExit('Unreviewed complete registration source')
        pending.append((target,after));proof.append({'path':row['path'],'beforeSha256':row['beforeSHA256'],'afterSha256':row['afterSHA256']})
    for row in manifest['sourceFiles']:
        data=(here/'bridge-source'/row['fileName']).read_bytes();target=root/row['targetPath']
        if sha(data)!=row['sha256'] or len(data)!=row['bytes']: raise SystemExit('Actual bridge source changed')
        if target.exists() and target.read_bytes()!=data: raise SystemExit('Existing unrelated filter source')
        pending.append((target,data))
    for target,data in pending:
        if root not in target.resolve().parents: raise SystemExit('Source target escaped original root')
    # All source identity and full forward/inverse checks precede first mutation.
    for target,data in pending: target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    for target,data in pending:
        if target.read_bytes()!=data: raise SystemExit('Native source readback differs')
    receipt={'schema':2,'patchId':manifest['variant'],'sourceCommit':manifest['sourceCommit'],
        'sourcePath':nvidia['sourcePath'],'originalSourceSha256':nvidia['originalSha256'],
        'patchedSourceSha256':nvidia['patchedSha256'],'patchSha256':nvidia['patchSha256'],
        'filterName':manifest['filterName'],'filterSourceManifestSha256':EXPECTED_MANIFEST_SHA256,
        'coreAbiHeaderSha256':manifest['coreAbiHeaderSha256'],'filterSourceFiles':manifest['sourceFiles'],
        'registrations':proof,'nativeBinaryBuiltByThisProgram':False,'gpuExecuted':False}
    root.joinpath('.bilipai-native-patch-receipt.json').write_text(json.dumps(receipt,sort_keys=True,indent=2)+'\n')
if __name__=='__main__': main()
