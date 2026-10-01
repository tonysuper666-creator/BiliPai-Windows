"""Read pinned inputs, then freeze task-owned raw only. Never writes Main."""
from pathlib import Path
import hashlib, json, shutil, sys
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SNAP = REPO / "desktop/.local/dynamic-detail-reply-main-integration/main-product-snapshot-01"
OLD = REPO / "desktop/.local/dynamic-gallery-motion-photo-parity"

def ext(path):
    value = str(Path(path).resolve())
    return Path(value if value.startswith("\\\\?\\") else "\\\\?\\" + value)

def raw(path): return ext(path).read_bytes()
def sha(path): return hashlib.sha256(raw(path)).hexdigest()
def lfsha(path): return hashlib.sha256(raw(path).replace(b"\r\n", b"\n")).hexdigest()
def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    assert not path.exists(), "Never overwrite an existing receipt"
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")

manifest_sha = "8286563186e5d5be2495a598e1e32fb15091b7a459a5bbbc3d7e65243557e290"
cp_sha = "06316248361f2fae9d8eb53976ba1dc6936c5bd6416f98d2b6026c207ef9a217"
old_sha = "08da9ef3efd871724d1cc58482dc5945e18c9312229e4749f75e0dc405826c18"
assert sha(SNAP / "manifest.json") == manifest_sha
assert sha(SNAP / "ordered-runtime-cp.json") == cp_sha
assert sha(OLD / "frozen-handoff.json") == old_sha
snapshot = json.loads(raw(SNAP / "manifest.json"))
ordered = json.loads(raw(SNAP / "ordered-runtime-cp.json"))
assert len(ordered) == 89
for row in ordered: assert sha(row["path"]) == row["sha256Bytes"]
sourcepins = {row["path"]: row["sha256Lf"] for row in snapshot["sourceFiles"]}
oldpins = {row["path"]: row["sha256Bytes"] for row in json.loads(raw(OLD / "frozen-handoff.json"))["files"]}
candidate = HERE / "prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt"
original = HERE / "original/DesktopDynamicImageAssets.kt"
assetrel = "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt"
assert lfsha(original) == sourcepins[assetrel]
accepted = json.loads(raw(HERE / "runs/05/accepted-evidence.json"))
assert accepted["passed"] and accepted["assertions"] == 51 and accepted["cases"] == 11
assert sha(candidate) == accepted["installSourceSha256Bytes"]
for attempt in ("03", "04", "05"):
    assert sha(HERE / "runs" / attempt / "install-source.kt") == sha(candidate)

if sys.argv[1] == "audit":
    selected = [
        "desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSessionStore.kt",
        "desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt",
        "desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt",
        "desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCache.kt",
        "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicCardSession.kt",
    ]
    actual_sources = []
    for rel in selected:
        assert lfsha(REPO / rel) == sourcepins[rel], rel
        destination = HERE / "actual-main-owner-sources" / Path(rel).name
        destination.parent.mkdir(parents=True, exist_ok=True)
        assert not destination.exists()
        shutil.copyfile(REPO / rel, destination)
        actual_sources.append({"path": rel, "snapshotSha256Lf": sourcepins[rel],
                               "copyPath": str(destination.relative_to(HERE)).replace("\\", "/"),
                               "copySha256Bytes": sha(destination), "currentSourceLfMatchesSnapshot": True})
    reused = [
        "prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles.kt",
        "prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoExif.kt",
        "generated-acceptance-final/com/android/purebilibili/feature/dynamic/components/DesktopOriginalMotionPhotoPacking.kt",
    ]
    dependencies = []
    for rel in reused:
        assert sha(OLD / rel) == oldpins[rel]
        destination = HERE / "dependencies-from-gallery" / Path(rel).name
        destination.parent.mkdir(parents=True, exist_ok=True)
        assert not destination.exists()
        shutil.copyfile(OLD / rel, destination)
        assert sha(destination) == oldpins[rel]
        dependencies.append({"galleryPath": rel, "sha256Bytes": oldpins[rel],
                             "copyPath": str(destination.relative_to(HERE)).replace("\\", "/"),
                             "fresh05InputBytesEqual": sha(HERE / "runs/05/frozen-sources" / Path(rel).name) == oldpins[rel]})
    oldtext = raw(original).decode("utf-8").replace("\r\n", "\n")
    newtext = raw(candidate).decode("utf-8").replace("\r\n", "\n")
    helper_old = oldtext[oldtext.index("private suspend fun selectDynamicSaveTarget"):]
    helper_new = newtext[newtext.index("internal suspend fun selectDynamicSaveTarget"):]
    assert helper_new == helper_old.replace("private suspend fun selectDynamicSaveTarget", "internal suspend fun selectDynamicSaveTarget", 1)
    save(HERE / "source-audit.json", {
        "passed": True, "writesOnlyTaskLane": True, "actualMainSnapshotManifestSha256Bytes": manifest_sha,
        "actualMainOrdered89CpSha256Bytes": cp_sha, "actualMainCpAll89BinaryPinsVerified": True,
        "originalAssetSha256Bytes": sha(original), "originalAssetSha256Lf": lfsha(original),
        "snapshotOriginalAssetLfEqual": True, "candidateAssetSha256Bytes": sha(candidate),
        "candidateSameBytesAcrossRuns03_04_05": True, "actualOwnerSourcePins": actual_sources,
        "reusedGalleryHandoffSha256Bytes": old_sha, "reusedImmutableFilesExifPacking": dependencies,
        "soleTargetChooserBodyUnchanged": True, "soleDirectoryChooserBodyUnchanged": True,
        "targetChooserOnlyVisibilityChange": "private -> internal",
        "taskCompilerOnlyRemovesUnchangedSaveTargetDeclaration": True,
        "actualMainRepoOpsSessionStoreSaveTargetCodeSourceVerifiedInFresh05": True,
        "actualStoreMonitorFinalMoveAccountEpochStrong": True, "actualStoreGuestOwnerMid": 0,
        "assetsOwnCloseLockFinalMoveStrong": True, "externalPageBoolCloseStrong": False,
        "lockOrder": ["actual SessionStore monitor", "Assets own lock", "MotionPhotoFiles own gate"],
        "closeDoesNotHoldAssetsLockWhenClosingFilesOrCancelingCalls": True,
        "closeIsNonblocking": True, "JoinedOperationDrainsActualCallbackBeforeTempDeletion": True,
        "currentMainInstalledIntegration": False, "nativeChooser": False, "PhotosReceiver": False,
        "systemSHARE": False, "optionalLivePhotoCompiledOnlyNotAccepted": True,
        "commentSaveFragmentExecuted": False, "independentAssetsReview": False,
    })
    print("PASS source audit: actual owner source pins, original chooser, same candidate, old Gallery inputs")
elif sys.argv[1] == "freeze":
    assert json.loads(raw(HERE / "source-audit.json"))["passed"]
    binary = []
    rows = []
    for path in sorted(HERE.rglob("*")):
        if not path.is_file() or "__pycache__" in path.parts: continue
        rel = str(path.relative_to(HERE)).replace("\\", "/")
        if rel in ("frozen-handoff.json", "binary-artifact-inventory.json"): continue
        row = {"path": rel, "sha256Bytes": sha(path), "size": path.stat().st_size}
        if path.suffix.lower() in (".jar", ".class", ".pyc"):
            binary.append(row)
        else: rows.append(row)
    save(HERE / "binary-artifact-inventory.json", {"files": binary, "notCopiedAsRaw": True,
          "runtimeDependencyGraph": "runs/05/compile-evidence.json:ordered92Classpath",
          "externalOfficialDependencyProvenance": "immutable Gallery handoff " + old_sha})
    inventory = HERE / "binary-artifact-inventory.json"
    rows.append({"path": inventory.name, "sha256Bytes": sha(inventory), "size": inventory.stat().st_size})
    rows.sort(key=lambda row: row["path"])
    save(HERE / "frozen-handoff.json", {
        "frozen": True, "phase": "Assets anonymous streaming and atomic actual-Store owner candidate",
        "acceptedCohort": "runs/05", "acceptedAssertions": 51, "acceptedCases": 11,
        "actualMainSnapshotManifestSha256Bytes": manifest_sha, "actualOrdered89CpSha256Bytes": cp_sha,
        "preparedCandidateOnly": True, "MainInstalledIntegration": False,
        "declaredOverride": "Assets family only; no Ops/Session/Repo/SaveTarget replacement",
        "externalSocket": False, "HWND": False, "actualChooser": False,
        "PhotosRecognition": False, "systemSHARE": False, "optionalLivePhotoSourceOnly": True,
        "commentSaveFragmentExecuted": False, "historyRetained": ["runs/01", "runs/02", "runs/03", "runs/04", "livephoto-source-only/compile01"],
        "files": rows, "fileCount": len(rows), "totalRawBytes": sum(row["size"] for row in rows),
    })
    print(json.dumps({"frozenHandoffSha256Bytes": sha(HERE / "frozen-handoff.json"),
                      "fileCount": len(rows), "totalRawBytes": sum(row["size"] for row in rows),
                      "candidateSourceSha256Bytes": sha(candidate),
                      "acceptedEvidenceSha256Bytes": sha(HERE / "runs/05/accepted-evidence.json"),
                      "sourceAuditSha256Bytes": sha(HERE / "source-audit.json")}, indent=2))
else: raise ValueError(sys.argv[1])
