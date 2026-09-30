from pathlib import Path
import hashlib,json,subprocess,sys
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
LANE=REPO/'desktop/.local/native-share-owner-thread-dispatch-parity'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
handoff=json.loads((LANE/'frozen-handoff.json').read_bytes())
frozen='bind-proof08/frozen-sources/OwnerBindFixture.kt'
row=next(r for r in handoff['files']if r['path']==frozen);assert sha(LANE/frozen)==row['sha256Bytes']
(HERE/'OwnerBindFixture.kt').write_bytes((LANE/frozen).read_bytes())
source=(LANE/'bind-proof08/frozen-sources/run-bind-proof.py').read_text(encoding='utf-8')
source=source.replace("BASE=HERE.parent/'native-share-main-product-snapshot-01'","BASE=HERE/'main-product-snapshot-01'")
source=source.replace('4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f','9f58369713e6738bea2a6cde03c586768fa414188a82f127b0b0771ca8e50151')
source=source.replace('a733a3435e18c5dac83ff155023c36e6d0d1997822b0a6b77ba7d4efeff63fcc','334d1947e200a659f06ab4002bf4b94870bb6ffa369a72a994bd1afbe69d88a2')
old="source=HERE/'OwnerBindFixture.kt';dll=HERE/'build06/bilipai-diagnostic-share.dll';graph=HERE/'build06/producer-input-graph.json'"
new="source=HERE/'OwnerBindFixture.kt';snapshot=json.loads((BASE/'manifest.json').read_bytes());dll=Path(snapshot['nativeAsset']['stagedPath']);graph=Path(snapshot['nativeProducer']['actualBuildGraph'])"
assert old in source;source=source.replace(old,new)
source=source.replace("HERE/'candidate06/DesktopDiagnosticShare.cpp'","REPO/'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp'")
source=source.replace("HERE=Path(__file__).resolve().parent;BASE=", "HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists());BASE=")
source=source.replace("out=HERE/'bind-proof08'","out=HERE/'bind-proof01'")
source=source.replace("HERE/'run-bind-proof.py'","HERE/'run-bind-main.py'")
source=source.replace("'MainIntegrated':False","'MainIntegratedNativeSource':True,'MainShellExecuted':False")
source=source.replace("'sourceControlledApprovalChanged':False","'sourceControlledApprovalChangedByFixture':False,'preparedCandidateOnly':False")
target=HERE/'run-bind-main.py';target.write_text(source,encoding='utf-8',newline='\n')
subprocess.run([sys.executable,str(target)],cwd=REPO,check=True)
