from pathlib import Path
import hashlib,json,shutil,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):assert not p.exists();p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
config=json.loads((HERE/"accepted-snapshot-input.json").read_bytes())
accepted=json.loads((HERE/"runs/01/accepted-integration-evidence.json").read_bytes())
assert accepted["passed"] and accepted["productionClassOverlap"]==[]
assert accepted["actualMainConfig"]==config
assert sum(v["assertions"] for v in accepted["cohorts"].values())==135
assert sum(v["cases"] for v in accepted["cohorts"].values())==24
for name,expected in (("manifest.json",config["manifestSha256Bytes"]),("ordered-runtime-cp.json",config["orderedCpSha256Bytes"])):
    src=REPO/config["snapshotRepoRelativePath"]/name;assert sha(src)==expected
    dest=HERE/"accepted-main-snapshot"/name;dest.parent.mkdir(parents=True,exist_ok=True);assert not dest.exists();shutil.copyfile(src,dest)
rows=[];binaries=[]
for p in sorted(HERE.rglob("*")):
    if not p.is_file() or "__pycache__" in p.parts:continue
    rel=str(p.relative_to(HERE)).replace("\\","/")
    if rel in ("frozen-handoff.json","binary-artifact-inventory.json"):continue
    row={"path":rel,"sha256Bytes":sha(p),"size":p.stat().st_size}
    (binaries if p.suffix.lower() in (".jar",".class",".pyc") else rows).append(row)
save(HERE/"binary-artifact-inventory.json",{"files":binaries,"notCopiedAsRaw":True,"actualMainArtifactPins":"accepted-main-snapshot/manifest.json"})
p=HERE/"binary-artifact-inventory.json";rows.append({"path":p.name,"sha256Bytes":sha(p),"size":p.stat().st_size});rows.sort(key=lambda r:r["path"])
save(HERE/"frozen-handoff.json",{
    "frozen":True,"phase":"actual compiled Main03 media save/gallery/QR integration with zero production overrides",
    "actualMainConfig":config,"acceptedCohort":"runs/01","assertions":135,"cases":24,
    "allProductClassesActualMainZeroOverride":True,"productionClassOverlap":[],
    "actualCompiledMainSourceAndOperationsInstalled":True,
    "legacyDownloadMainInstalledIntegrationFlagCorrection":"metadata-correction.json",
    "rawLegacyMetadataPreserved":True,"RootShellAndRealChooserExecuted":False,
    "HWND":False,"packagedRuntime":False,"deployedEXEAccepted":False,"systemSHARE":False,
    "PhotosRecognition":False,"outsideSocket":False,"actualLoopbackHttp":True,
    "files":rows,"fileCount":len(rows),"totalRawBytes":sum(r["size"] for r in rows),
})
for r in rows:assert sha(HERE/r["path"])==r["sha256Bytes"]
print(json.dumps({"frozenHandoffSha256Bytes":sha(HERE/"frozen-handoff.json"),"fileCount":len(rows),
    "totalRawBytes":sum(r["size"] for r in rows),"aggregateAcceptedSha256Bytes":sha(HERE/"runs/01/accepted-integration-evidence.json"),
    "metadataCorrectionSha256Bytes":sha(HERE/"metadata-correction.json"),"rootReceiptSha256Bytes":sha(HERE/"ROOT-RECEIPT.txt")},indent=2))
