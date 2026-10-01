from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
def raw(path):return path.read_bytes()
def sha(path):return hashlib.sha256(raw(path)).hexdigest()
def save(path,value):
    assert not path.exists();path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
accepted=json.loads(raw(HERE/"runs/05/accepted-evidence.json"))
assert accepted["passed"] and accepted["assertions"]==29 and accepted["cases"]==9
assert accepted["productionClassOverlap"]==[] and not accepted["actualPNGQRReadback"]
source=json.loads(raw(HERE/"runs/05/source-extraction.json"))
assert source["extractionNoBodyChanges"] and source["newCaptureExpressionSourceVerified"]
save(HERE/"capture-race-review.json",{
    "actualMainSnapshot02ManifestSha256Bytes":accepted["actualMainManifestSha256Bytes"],
    "currentCommunitySourceLfPin":source["actualSourcePins"][0]["sha256Lf"],
    "oldExpression":"checkNotNull(exportGuard.dynamicCacheOwner()).also{check(it.epoch==capturedEpoch)}",
    "interleaving":["actual cardSession.matches(repository,E) is true","actual same-MID Store saveAccount changes epoch E+1","old owner capture gets E+1 and throws IllegalStateException"],
    "oldExpressionReproducedAgainstActualStoreAndCardSession":True,
    "fixedCapture":"checkNotNull(exportGuard.dynamicCacheOwner())",
    "fixedOwnedExpression":source["extractedOwnershipExpression"],
    "newExpressionRejectsStaleOwnerWithoutThrow":True,
    "currentSourceMatchesActualSnapshot02":True,"CommunityCompositionExecuted":False,
    "finalGateBodySha256Utf8":hashlib.sha256(source["extractedFinalGateExpression"].encode()).hexdigest(),
    "disposeBodySha256Utf8":hashlib.sha256(source["extractedDisposeExpression"].encode()).hexdigest(),
    "writerFinalCommitAccepted":True,"longRouteQRDecoded":False,
    "QRCodeLimitationIsInheritedSourceLayout":True,"MainModifiedByThisLane":False,
})
rows=[];binaries=[]
for path in sorted(HERE.rglob("*")):
    if not path.is_file() or "__pycache__" in path.parts:continue
    rel=str(path.relative_to(HERE)).replace("\\","/")
    if rel in ("frozen-handoff.json","binary-artifact-inventory.json"):continue
    item={"path":rel,"sha256Bytes":sha(path),"size":path.stat().st_size}
    (binaries if path.suffix.lower() in (".jar",".class",".pyc") else rows).append(item)
save(HERE/"binary-artifact-inventory.json",{"files":binaries,"notCopiedAsRaw":True})
inventory=HERE/"binary-artifact-inventory.json"
rows.append({"path":inventory.name,"sha256Bytes":sha(inventory),"size":inventory.stat().st_size});rows.sort(key=lambda r:r["path"])
save(HERE/"frozen-handoff.json",{
    "frozen":True,"phase":"actual Main original PNG renderer and final file commit focused proof",
    "acceptedCohort":"runs/05","assertions":29,"cases":9,
    "actualMainManifestSha256Bytes":accepted["actualMainManifestSha256Bytes"],
    "actualOrdered89CpSha256Bytes":accepted["actualOrdered89CpSha256Bytes"],
    "productionOverrides":False,"originalPNGWriterFinalCommitAccepted":True,
    "longRouteQrDecodeFailedInheritedLayout":True,"QRCodeAccepted":False,
    "RootScreenExecuted":False,"RootGateExtractionNoBodyChanges":True,
    "HTTP":False,"HWND":False,"actualNativePicker":False,"systemSHARE":False,
    "historyRetained":["runs/01","runs/02","runs/03","runs/04"],
    "files":rows,"fileCount":len(rows),"totalRawBytes":sum(r["size"] for r in rows),
})
for row in rows:assert sha(HERE/row["path"])==row["sha256Bytes"]
print(json.dumps({"frozenHandoffSha256Bytes":sha(HERE/"frozen-handoff.json"),"fileCount":len(rows),
    "totalRawBytes":sum(r["size"] for r in rows),"acceptedEvidenceSha256Bytes":sha(HERE/"runs/05/accepted-evidence.json"),
    "captureRaceReviewSha256Bytes":sha(HERE/"capture-race-review.json"),"rootReceiptSha256Bytes":sha(HERE/"ROOT-RECEIPT.txt")},indent=2))
