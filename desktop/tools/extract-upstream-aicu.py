"""Full original Aicu screen/VM/store; exact invertible Windows leaf seams."""
from pathlib import Path
import argparse, hashlib, json, sys
sys.dont_write_bytecode = True
from v025_source_paths import canonical_source
FEATURE = "desktop-full-original-aicu-root-parity"
def digest(raw): return hashlib.sha256(raw).hexdigest()
def emit(out,rel,text):
 p=out/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding="utf8",newline="\n")
def generate(repo,out):
 spec=json.loads((Path(__file__).with_name("upstream-aicu-adaptations.json")).read_text(encoding="utf8"))
 if spec["upstreamCommit"]!="79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40":raise ValueError("Unreviewed Aicu upstream")
 receipts=[]
 for row in spec["recipes"]:
  original=canonical_source(repo,row["path"]).read_bytes().replace(b"\r\n",b"\n").decode("utf8")
  if digest(original.encode())!=row["sha256"]:raise ValueError("Aicu source changed "+row["path"])
  text=original
  for h in row["hunks"]:
   if text.count(h["before"])!=1 or text.index(h["before"])!=h["offset"]:raise ValueError("Aicu seam moved")
   text=text[:h["offset"]]+h["after"]+text[h["offset"]+len(h["before"]):]
  restored=text
  for h in reversed(row["hunks"]):
   i=h["offset"]
   if restored[i:i+len(h["after"])]!=h["after"]:raise ValueError("Aicu inverse moved")
   restored=restored[:i]+h["before"]+restored[i+len(h["after"]):]
  if restored!=original or digest(text.encode())!=row["resultSha256"]:raise ValueError("Aicu inverse failed")
  emit(out,row["output"],text);receipts.append(dict(path=row["path"],output=row["output"],originalSha256=row["sha256"],generatedSha256=row["resultSha256"],inverseExact=True,hunks=len(row["hunks"])))
 for row in spec["extractions"]:
  full=canonical_source(repo,row["path"]).read_bytes().replace(b"\r\n",b"\n").decode("utf8")
  if digest(full.encode())!=row["sourceSha256"] or full.count(row["original"])!=1:raise ValueError("Aicu selected declaration changed")
  adapted=row["original"]
  for before,after in row["changes"]:
   if adapted.count(before)!=1:raise ValueError("Aicu capability seam moved")
   adapted=adapted.replace(before,after,1)
  if adapted!=row["adapted"]:raise ValueError("Aicu selected inverse mismatch")
  emit(out,row["output"],row["prefix"]+adapted+row.get("suffix",""))
  receipts.append(dict(path=row["path"],output=row["output"],sourceSha256=row["sourceSha256"],selectedSha256=digest(row["original"].encode()),inverseExact=True))
 emit(out,"aicu-producer-receipt.json",json.dumps(dict(schemaVersion=1,feature=FEATURE,outputs=receipts,fullFunctionalParityVerified=False),ensure_ascii=False,indent=2)+"\n")
 return receipts
if __name__=="__main__":
 p=argparse.ArgumentParser();p.add_argument("--repo",type=Path,required=True);p.add_argument("--output",type=Path,required=True);a=p.parse_args();rows=generate(a.repo.resolve(),a.output.resolve());print("Generated full original Aicu outputs",len(rows))
