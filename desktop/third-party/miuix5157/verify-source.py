from pathlib import Path
import argparse, hashlib, json, os
ap=argparse.ArgumentParser();ap.add_argument("--root",type=Path,required=True);args=ap.parse_args()
root=args.root.resolve()
if os.name=="nt" and not str(root).startswith("\\\\?\\"):root=Path("\\\\?\\"+str(root))
source=root/"upstream"
provenance=json.loads((root/"upstream-provenance.json").read_text(encoding="utf-8"))
assert provenance["commit"]=="5157b503e86e2bfc2db61db00fff5df41326394a"
declared=set()
for entry in provenance["files"]:
 path=source/entry.get("storagePath",entry["path"])
 assert path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(source.resolve()),entry["path"]
 digest=hashlib.sha256(path.read_bytes()).hexdigest()
 assert digest==entry["sha256"],entry["path"]
 if path.suffix==".kt" and any("/src/"+s+"/" in entry["path"] for s in provenance["compiledSourceSets"]):declared.add(path.resolve())
actual={p.resolve() for p in source.rglob("*.kt") if any("/src/"+s+"/" in p.as_posix() for s in provenance["compiledSourceSets"])}
assert actual==declared,"Unexpected unreviewed compiled source"
print("Exact Miuix5157 pinned source PASS:",len(provenance["files"]),"files;",len(declared),"compiled source files")
