from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,value):
    assert not p.exists();p.write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
comparison=json.loads((HERE/"runs/01/accepted-comparison.json").read_text())
assert comparison["passed"] and comparison["candidateAssertions"]==37 and comparison["routes"]==8
assert comparison["baselineWindowsDecodeFailures"]==5 and comparison["candidateWindowsDecodeFailures"]==0
audit=json.loads((HERE/"source-audit.json").read_text())
assert audit["rendererAllOtherBytesIdentical"] and audit["GitTagOriginalSaverByteEqual"]
rows=[];binaries=[]
for path in sorted(HERE.rglob("*")):
    if not path.is_file() or "__pycache__" in path.parts:continue
    rel=str(path.relative_to(HERE)).replace("\\","/")
    if rel in ("frozen-handoff.json","binary-artifact-inventory.json"):continue
    item={"path":rel,"sha256Bytes":sha(path),"size":path.stat().st_size}
    (binaries if path.suffix.lower() in (".jar",".class",".pyc") else rows).append(item)
save(HERE/"binary-artifact-inventory.json",{"files":binaries,"excludedFromRaw":True})
inventory=HERE/"binary-artifact-inventory.json"
rows.append({"path":inventory.name,"sha256Bytes":sha(inventory),"size":inventory.stat().st_size});rows.sort(key=lambda r:r["path"])
review=HERE/"independent-readonly-review.json"
review_sha=sha(review) if review.exists() else None
save(HERE/"frozen-handoff.json",{
    "frozen":True,"phase":"minimal Windows printed footer URL clip candidate, original full QR unchanged",
    "baseline": "runs/01/baseline","candidate":"runs/01/candidate",
    "candidateAssertions":37,"routes":8,"baselineDecodeFailures":5,"candidateDecodeFailures":0,
    "candidateAllOriginalQrMatrixPixelsExact":True,
    "actualMainManifestSha256Bytes":comparison["actualMainManifestSha256Bytes"],
    "actualOrdered89CpSha256Bytes":comparison["actualOrdered89CpSha256Bytes"],
    "candidateOnlyDeclaredOverrides":"Canvas + renderer families; actual spec/Paint/bitmapfactory/writer",
    "candidateCanvasSha256Bytes":audit["candidateCanvasSha256Bytes"],
    "candidateProducerSha256Bytes":audit["candidateProducerSha256Bytes"],
    "candidateRendererSha256Bytes":audit["candidateRendererSha256Bytes"],
    "independentReadOnlyReviewSha256Bytes":review_sha,"AndroidActualRender":False,
    "printedFooterUrlMayBeClipped":True,"fullOriginalQrPayloadPreserved":True,
    "MainInstalled":False,"HTTP":False,"HWND":False,"realPicker":False,"systemSHARE":False,
    "historyRetained":["prepare01-observer-failure.json","runs/01/baseline"],
    "files":rows,"fileCount":len(rows),"totalRawBytes":sum(r["size"] for r in rows),
})
for r in rows:assert sha(HERE/r["path"])==r["sha256Bytes"]
print(json.dumps({"frozenHandoffSha256Bytes":sha(HERE/"frozen-handoff.json"),"fileCount":len(rows),
    "totalRawBytes":sum(r["size"] for r in rows),"comparisonSha256Bytes":sha(HERE/"runs/01/accepted-comparison.json"),
    "sourceAuditSha256Bytes":sha(HERE/"source-audit.json"),"rootIntegrationSha256Bytes":sha(HERE/"ROOT-INTEGRATION.txt"),
    "reviewSha256Bytes":review_sha},indent=2))
