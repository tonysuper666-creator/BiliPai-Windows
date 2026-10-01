from pathlib import Path
import hashlib,json
LANE=Path(__file__).resolve().parent;sha=lambda b:hashlib.sha256(b).hexdigest()
receipt=json.loads((LANE/'source-receipt.json').read_bytes());pins={r['path']:r['sha256']for r in receipt['originalRegistryRows']}
source=(LANE/'prepare.py').read_text(encoding='utf-8')
source=source.replace('import hashlib,importlib.util,json,re,subprocess,zipfile','import argparse,hashlib,importlib.util,json,re,subprocess')
source=source.replace("LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023';TAG='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'",'''arguments=argparse.ArgumentParser();arguments.add_argument('--repo',required=True);arguments.add_argument('--output',required=True);args=arguments.parse_args()
REPO=Path(args.repo);LANE=Path(args.output);TAG='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
LANE.mkdir(parents=True,exist_ok=True)
SOURCE_PINS='''+repr(pins))
source=source.replace("p=wide(LANE/'original-stable'/path);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(blob);sources[name]=blob.decode()","assert sha(blob)==SOURCE_PINS[path];sources[name]=blob.decode()",1)
source=source.replace("LANE/'prepared/generated/","LANE/'",2)
source=source[:source.index("jar=LANE/'coil-network-cache-control")]+'''\n(LANE/'producer-receipt.json').write_text(json.dumps(dict(stableCommit=TAG,originalRegistryRows=rows,exactMethodReplacements=deltas,newClients=0,fullAndroidApplicationPorted=False),ensure_ascii=False,indent=2)+'\\n',encoding='utf-8')
print('Complete original application ImageLoader body and original cache/trim policies generated')
'''
tool=LANE/'prepared/tools/extract-upstream-application-image-loader.py';tool.parent.mkdir(exist_ok=True);tool.write_text(source,encoding='utf-8',newline='\n')
contract=[]
for p,target in [(tool,'desktop/tools/extract-upstream-application-image-loader.py')]+[(p,'desktop/src/main/kotlin/'+p.relative_to(LANE/'prepared/manual').as_posix())for p in(LANE/'prepared/manual').rglob('*.kt')]+[(p,'desktop/third-party/coil-cache-control/upstream/'+p.relative_to(LANE/'prepared/coil-upstream').as_posix())for p in(LANE/'prepared/coil-upstream').rglob('*.kt')]:
 contract.append(dict(source=p.relative_to(LANE).as_posix(),target=target,sha256Bytes=sha(p.read_bytes())))
assert len(contract)==6
(LANE/'install-contract.json').write_text(json.dumps(dict(payloads=contract,newOriginalRows=receipt['originalRegistryRows'],newRuntimeArtifacts=0,rootAppLifetimeRequiresMainAndShellHunks=True,windowTrimRequiresSameRootBackgroundAndPalette=True,unmodifiedExternalSources=receipt['externalSources']),indent=2)+'\n',encoding='utf-8')
print('Prepared six install payloads; original generated output stays build-only')
