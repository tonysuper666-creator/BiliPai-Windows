"""Immutable raw handoff plus excluded binary inventory. Lane-only; no Git writes."""
from pathlib import Path
import hashlib, json, os
HERE = Path(__file__).resolve().parent
def ext(path):
    text = str(Path(path).resolve())
    return Path(text if text.startswith('\\\\?\\') else '\\\\?\\'+text)
def sha(path): return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def save(path,value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def inventory(paths): return [{'path':str(p.relative_to(HERE)).replace('\\','/'),'sha256Bytes':sha(p),'bytes':p.stat().st_size} for p in sorted(paths)]
assert not (HERE/'frozen-handoff.json').exists()
accepted_paths = [HERE/'runs/04/accepted-evidence.json',HERE/'exif-runs/04/accepted-evidence.json',HERE/'gallery-runs/01/accepted-evidence.json']
expected = [(42,11),(22,8),(12,4)]
for path, counts in zip(accepted_paths,expected):
    result=json.loads(path.read_text(encoding='utf-8')); assert result['passed']
    assert (result['assertions'],result['cases'])==counts and result['productionOverrides']==0
    assert result['HTTP'] is False and result['HWND'] is False
    if result.get('MainIntegration') is not None: assert result['MainIntegration'] is False
assert json.loads((HERE/'audit03/source-audit.json').read_text(encoding='utf-8'))['passed']
assert json.loads((HERE/'dependencies/transitive-graph-receipt.json').read_text(encoding='utf-8'))['actualTotalClasspathEntries']==92
excluded_suffixes={'.jar','.class','.zip','.pyc'}
files=[p for p in HERE.rglob('*') if p.is_file() and '__pycache__' not in p.parts]
excluded=[p for p in files if p.suffix.lower() in excluded_suffixes]
save(HERE/'binary-artifact-inventory.json', {'reason':'excluded from raw Git copy; exact task candidate/runtime/archive bytes for reproducible review',
    'files':inventory(excluded),'noPrivateAccountOrStore':True,'noInstalledPackage':True})
prepared=[p for p in (HERE/'prepared').rglob('*') if p.is_file()]+[p for p in (HERE/'generated-acceptance-final').rglob('*') if p.is_file()]
save(HERE/'handoff-summary.json', {'preparedSliceAccepted':True,'MainIntegrationAccepted':False,
    'actualBaselineManifestSha256Bytes':'4780b40c15bd0de96f01fe5f60086bd2e67684764d075d8af8f437a27b0887c5',
    'actualBaseline89CpSha256Bytes':'c2f31a97c207af9d501d669897deebfee51f4edf9a12b1280df59b542ec4b6ab',
    'finalCohorts':[{'path':str(p.relative_to(HERE)).replace('\\','/'),'sha256Bytes':sha(p),'assertions':a,'cases':c} for p,(a,c) in zip(accepted_paths,expected)],
    'assertionsTotalAcrossThreeIndependentNarrowCohorts':76,'casesTotal':23,
    'sourceAudit':{'path':'audit03/source-audit.json','sha256Bytes':sha(HERE/'audit03/source-audit.json')},
    'preparedAndGenerated':inventory(prepared),'sourceCommentOnlyAdaptation':{'path':'source-comment-adaptation.json','sha256Bytes':sha(HERE/'source-comment-adaptation.json')},
    'onlyPackingAlgorithmAdaptation':'delete three duplicate GCamera modern attributes, exact whitelist/diff',
    'actualRuntime92RequiredGraph':['org.apache.commons:commons-imaging:1.0.0-alpha6','commons-io:commons-io:2.19.0','org.apache.commons:commons-lang3:3.17.0'],
    'noRealPickerWindow':True,'noMainHTTP':True,'noSystemSHARE':True,'noReceiver':True,'noMpvHWND':True,
    'noWindowsPhotosRecognition':True,'noPackagedRuntime':True,'noDesktopDeployment':True,
    'integrationChecklist':{'path':'ROOT-INTEGRATION.txt','sha256Bytes':sha(HERE/'ROOT-INTEGRATION.txt')}})
raw=[p for p in HERE.rglob('*') if p.is_file() and p.suffix.lower() not in excluded_suffixes and '__pycache__' not in p.parts]
manifest={'formatVersion':1,'scope':'Gallery/MotionPhoto prepared source and real synthetic-media proof; no installed Main feature claim',
    'laneRoot':'desktop/.local/dynamic-gallery-motion-photo-parity','files':inventory(raw),
    'excludedBinaryInventory':{'path':'binary-artifact-inventory.json','sha256Bytes':sha(HERE/'binary-artifact-inventory.json')},
    'preservesAllFailureHistory':True,'copiesNoAccountOrStore':True,'immutableAfterFreeze':True}
save(HERE/'frozen-handoff.json',manifest)
for row in manifest['files']:
    p=HERE/row['path']; assert sha(p)==row['sha256Bytes'] and p.stat().st_size==row['bytes']
print(json.dumps({'rawFiles':len(manifest['files']),'rawBytes':sum(x['bytes'] for x in manifest['files']),
    'frozenHandoffSha256Bytes':sha(HERE/'frozen-handoff.json'),'summarySha256Bytes':sha(HERE/'handoff-summary.json')},indent=2))
