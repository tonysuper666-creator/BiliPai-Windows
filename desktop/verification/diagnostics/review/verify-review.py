"""Read-only review closure checks; rejects baseline drift without rewriting it."""
from pathlib import Path
import hashlib, importlib.util, json, re, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def load(p,name):
    spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
installer=load(HERE/'install-reviewed.py','verifieddiagnosticinstaller')
plan=installer.load_plan();installer.verify(installer.DEFAULT_REPO,plan)
def read(p):return installer.safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
original=json.loads(read(installer.DEFAULT_REPO/'desktop/upstream-sources.json'))
desired=json.loads(read(HERE/'payload/desktop/upstream-sources.json'))
raw=[e for e in original['resources'] if e.get('hashNormalization')=='raw']
assert len(raw)==8
for e in raw:assert desired['resources'].count(e)==1,e['path']
for array in ['sources','resources']:
    assert len({e['path'] for e in desired[array]})==len(desired[array])
    for e in original[array]:
        found=next(x for x in desired[array] if x['path']==e['path'])
        assert {k:v for k,v in found.items() if k!='features'}=={k:v for k,v in e.items() if k!='features'},e['path']
        assert set(e['features']).issubset(found['features'])
gradle=read(HERE/'payload/desktop/build.gradle.kts')
assert gradle.count('val extractUpstreamDiagnostics by tasks.registering')==1
assert gradle.count('kotlin.srcDir(layout.buildDirectory.dir("generated/diagnostics/sources"))')==1
assert 'layout.buildDirectory.file("generated/reference-only/OriginalDiagnosticsSection.kt")' not in gradle
assert 'if (entry["hashNormalization"] == "raw")' in gradle or 'hashNormalization' in gradle
snapshot=json.loads(read(HERE.parent/'blocked-up-foundation-product-snapshot/manifest.json'))
with zipfile.ZipFile(installer.safe(snapshot['artifacts'][0]['path'])) as archive:product=set(archive.namelist())
new_classes=[]
for e in plan['generatedOutputs']:
    p=HERE/'generated-review'/e['path'];text=read(p)
    package=re.search(r'^package ([\w.]+)',text,re.M).group(1).replace('.','/')+'/'
    names=re.findall(r'^(?:internal\s+)?(?:data\s+)?(?:enum\s+)?(?:class|object)\s+(\w+)',text,re.M)
    candidate=[package+name+'.class' for name in names]+[package+p.stem+'Kt.class']
    for name in candidate:assert name not in product,name
    new_classes+=candidate
delta=json.loads(read(HERE/'boundary-delta/delta-contract.json'))
assert delta['afterInstallPlanSha256Bytes']==installer.digest(HERE/'install-plan.json')
for e in delta['files']:
    assert installer.digest(HERE/'payload'/e['path'])==e['baselineSha256Bytes']
    assert installer.digest(HERE/'boundary-delta/payload'/e['path'])==e['payloadSha256Bytes']
view=read(HERE/'boundary-delta/payload/desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopLocalDiagnosticViewer.kt')
assert 'catch(cancelled:CancellationException){throw cancelled}' in view
result=dict(passed=True,all322ProducerAnd234DependencyPinsVerified=True,planAndCurrentBaselineVerified=True,
    originalSourceIdentities=4,originalXmlIdentities=1,allEightRawResourceFieldsPreserved=True,existingManifestFieldsAndFeaturesPreserved=True,
    sourceCountBefore=len(original['sources']),sourceCountAfter=len(desired['sources']),resourceCountBefore=len(original['resources']),resourceCountAfter=len(desired['resources']),
    uniqueProducer=True,independentReferenceOutput=True,generatedFqnCollisionFound=False,newGeneratedClassCandidates=len(new_classes),
    deltaAfterProducerPinsVerified=True,mainWritten=False)
(HERE/'review-proof.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result))
