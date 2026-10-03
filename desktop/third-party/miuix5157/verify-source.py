from pathlib import Path
import argparse, hashlib, json, os
ap=argparse.ArgumentParser();ap.add_argument("--root",type=Path,required=True);args=ap.parse_args()
root=args.root.resolve()
if os.name=="nt" and not str(root).startswith("\\\\?\\"):root=Path("\\\\?\\"+str(root))
source=root/"upstream"
provenance=json.loads((root/"upstream-provenance.json").read_text(encoding="utf-8"))
assert provenance["commit"]=="5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca"
declared=set()
for entry in provenance["files"]:
 path=source/entry.get("storagePath",entry["path"])
 assert path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(source.resolve()),entry["path"]
 digest=hashlib.sha256(path.read_bytes()).hexdigest()
 assert digest==entry["sha256"],entry["path"]
 if path.suffix==".kt" and any("/src/"+s+"/" in entry["path"] for s in provenance["compiledSourceSets"]):declared.add(path.resolve())
actual={p.resolve() for p in source.rglob("*.kt") if any("/src/"+s+"/" in p.as_posix() for s in provenance["compiledSourceSets"])}
assert actual==declared,"Unexpected unreviewed compiled source"
patch=provenance["bilipaiNavigationPatch"]
assert patch["repository"]=="https://github.com/jay3-yy/BiliPai"
assert patch["commit"]=="79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40"
assert patch["sourceRoot"]=="bilipai-v025-nav/src/commonMain/kotlin"
selected={"core/NavDisplay.kt","gesture/PredictiveBackHandler.kt","gesture/NavPredictiveBackDriver.kt","core/NavBackCompletionPolicy.kt"}
originalPrefix="miuix-navigation/src/main/java/top/yukonga/miuix/kmp/nav/"
storagePrefix="bilipai-v025-nav/src/commonMain/kotlin/top/yukonga/miuix/kmp/nav/"
assert {entry["originalPath"] for entry in patch["files"]}=={originalPrefix+p for p in selected}
assert {entry["storagePath"] for entry in patch["files"]}=={storagePrefix+p for p in selected}
excluded={"nav/"+p for p in selected if p!="core/NavBackCompletionPolicy.kt"}
assert set(patch["excludedPinnedRelativePaths"])==excluded
patched=root/patch["sourceRoot"]
patchedDeclared=set()
for entry in patch["files"]:
 path=root/entry["storagePath"]
 assert path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(patched.resolve()),entry["storagePath"]
 payload=path.read_bytes()
 assert hashlib.sha256(payload).hexdigest()==entry["sha256Bytes"] and len(payload)==entry["bytes"],entry["storagePath"]
 assert hashlib.sha256(payload.replace(b"\r\n",b"\n")).hexdigest()==entry["sha256LF"]
 original=root.parents[2]/entry["originalPath"]
 assert original.is_file() and not original.is_symlink() and original.resolve().is_relative_to(root.parents[2].resolve())
 assert original.read_bytes().replace(b"\r\n",b"\n")==payload.replace(b"\r\n",b"\n"),entry["originalPath"]
 assert entry["wholeOriginal"] is True and entry["adaptations"]==0 and entry["inversePassed"] is True
 patchedDeclared.add(path.resolve())
patchedActual={p.resolve() for p in patched.rglob("*") if p.is_file()}
assert patchedActual==patchedDeclared,"Unexpected unreviewed BiliPai navigation source"
excludedFiles={(source/"miuix-nav/src/commonMain/kotlin"/p).resolve() for p in excluded}
assert excludedFiles.issubset(declared)
compiled=(declared-excludedFiles)|patchedDeclared
build=(root/"build.gradle.kts").read_text(encoding="utf8")
expectedExclude='kotlin.exclude("nav/core/NavDisplay.kt", "nav/gesture/PredictiveBackHandler.kt", "nav/gesture/NavPredictiveBackDriver.kt")'
assert build.count(expectedExclude)==1
assert build.count('kotlin.srcDir("bilipai-v025-nav/src/commonMain/kotlin")')==1
print("Exact Miuix5c91 pinned source + whole BiliPai79 navigation patch PASS:",len(provenance["files"]),"pinned files;",len(compiled),"compiled source files;",len(patchedDeclared),"whole original patch files")
