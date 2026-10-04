from pathlib import Path
import argparse, hashlib, json, os
WINDOWS_NUMERIC_PATH="miuix-blur/src/commonMain/kotlin/top/yukonga/miuix/kmp/blur/internal/Shaders.kt"
WINDOWS_NUMERIC_STORAGE="miuix-blur/src/commonMain/kotlin/blur/internal/Shaders.kt"
WINDOWS_NUMERIC_ORIGINAL_SHA="f787566fb38ddd47676de91cef1016f3b8cc9b8cdadec0ed6fea8b8a0a7a7f08"
WINDOWS_NUMERIC_BEFORE=b"    float2 dir = normalize(coord.xy - corner.xy);\n"
WINDOWS_NUMERIC_AFTER=b"""    // Windows Skia: the inner corner boundary can have zero XY displacement.
    // Its flat normal must remain finite; all non-zero directions stay unchanged.
    if (coord.x == corner.x && coord.y == corner.y) {
        return float3(0.0, 0.0, -1.0);
    }
    float2 dir = normalize(coord.xy - corner.xy);
"""

def verify_windows_numeric_patch(entry,patch,payload):
 assert entry["path"]==patch["path"]==WINDOWS_NUMERIC_PATH
 assert entry["storagePath"]==patch["storagePath"]==WINDOWS_NUMERIC_STORAGE
 assert patch["id"]=="bloom-stroke-zero-vector-normal"
 assert patch["upstreamCommit"]=="5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca"
 assert entry["sha256"]==patch["originalSha256"]==WINDOWS_NUMERIC_ORIGINAL_SHA
 assert patch["before"].encode("utf-8")==WINDOWS_NUMERIC_BEFORE
 assert patch["after"].encode("utf-8")==WINDOWS_NUMERIC_AFTER
 assert hashlib.sha256(payload).hexdigest()==patch["patchedSha256"]
 assert payload.count(WINDOWS_NUMERIC_AFTER)==1,"Windows numeric patch must occur exactly once"
 restored=payload.replace(WINDOWS_NUMERIC_AFTER,WINDOWS_NUMERIC_BEFORE,1)
 assert restored.count(WINDOWS_NUMERIC_BEFORE)==1
 assert hashlib.sha256(restored).hexdigest()==entry["sha256"],"Windows numeric patch inverse differs from pinned original bytes"

ap=argparse.ArgumentParser();ap.add_argument("--root",type=Path,required=True);args=ap.parse_args()
root=args.root.resolve()
if os.name=="nt" and not str(root).startswith("\\\\?\\"):root=Path("\\\\?\\"+str(root))
source=root/"upstream"
provenance=json.loads((root/"upstream-provenance.json").read_text(encoding="utf-8"))
assert provenance["commit"]=="5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca"
windowsPatches=provenance["windowsNumericPatches"]
assert isinstance(windowsPatches,list) and len(windowsPatches)==1,"Only the reviewed Windows BloomStroke numeric patch is allowed"
windowsPatch=windowsPatches[0]
assert windowsPatch["path"]==WINDOWS_NUMERIC_PATH
assert sum(entry["path"]==WINDOWS_NUMERIC_PATH for entry in provenance["files"])==1
declared=set()
for entry in provenance["files"]:
 path=source/entry.get("storagePath",entry["path"])
 assert path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(source.resolve()),entry["path"]
 payload=path.read_bytes()
 digest=hashlib.sha256(payload).hexdigest()
 if entry["path"]==WINDOWS_NUMERIC_PATH:
  verify_windows_numeric_patch(entry,windowsPatch,payload)
 else:
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
print("Miuix5c91 pinned + Windows numeric patch (exact inverse verified) + whole BiliPai79 navigation patch PASS:",len(provenance["files"]),"pinned files;",len(compiled),"compiled source files;",len(patchedDeclared),"whole original patch files")
