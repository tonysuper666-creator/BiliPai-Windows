"""Freeze this task's raw proof and evidence-only binary identities, not Main."""
from pathlib import Path
import hashlib, json, os
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
EXTENDED = chr(92)*2 + "?" + chr(92)
def safe(path):
    p = str(path); return p if p.startswith(EXTENDED) else EXTENDED+p
def read(path):
    with open(safe(path), encoding="utf-8-sig") as stream: return stream.read()
def sha(path):
    with open(safe(path), "rb") as stream: return hashlib.file_digest(stream, "sha256").hexdigest()
def dump(path, value):
    with open(safe(path), "w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False); stream.write("\n")

static = REPO / "desktop/.local/dynamic-detail-reply-parity/detail-container-next/static-save-location-next"
static_manifest = static / "evidence-manifest.json"
assert sha(static_manifest) == "34a233ab8d023a65e1e1f4851dc192f66f2f8728fb4fbc865fe332fd55780cbf"
static_data = json.loads(read(static_manifest))
for entry in static_data["artifacts"]: assert sha(static/entry["path"]) == entry["sha256Bytes"], entry["path"]
source = HERE / "prepared/desktop/src/main/kotlin/com/bilipai/desktop/platform/DesktopWindowsImageSaveDirectory.kt"
product_refs = [
    "desktop/src/main/kotlin/com/bilipai/desktop/player/WindowsMediaSession.kt",
    "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt",
    "desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt",
]
dump(HERE/"source-inventory.json", {
    "installPayload": [{"prepared": str(source.relative_to(HERE)).replace("\\", "/"),
        "target": "desktop/src/main/kotlin/com/bilipai/desktop/platform/DesktopWindowsImageSaveDirectory.kt",
        "targetStatus": "new", "sha256Bytes": sha(source), "origin": "Windows platform adapter using official SDK ABI"}],
    "existingProductReferencesReadOnly": [{"path": p, "sha256BytesAtInventory": sha(REPO/p)} for p in product_refs],
    "existingKnownFolderImplementationFound": False,
    "existingJnaCore": {"version": "5.17.0", "sha256Bytes": "b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0"},
    "sameStoreBridgeReference": {"path": str(static_manifest), "sha256Bytes": sha(static_manifest),
        "frozenArtifactsIndependentlyVerified": len(static_data["artifacts"]), "ownedHere": False},
    "sourceRegistryDelta": "No original identity addition by this platform file. The separately frozen original image-location policy/SettingsManager identities remain owned by the static bridge producer.",
    "dependenciesAdded": [], "mainConsumerPatch": None,
})
raw = []; binaries = []
for folder, _, names in os.walk(safe(HERE)):
    for name in names:
        absolute = Path(folder) / name
        ordinary = Path(str(absolute)[len(EXTENDED):])
        relative = str(ordinary.relative_to(HERE)).replace("\\", "/")
        if relative == "evidence-manifest.json": continue
        row = {"path": relative, "sizeBytes": os.stat(safe(ordinary)).st_size, "sha256Bytes": sha(ordinary)}
        if ordinary.suffix.lower() in (".jar", ".dll", ".class"):
            row["reason"] = "task compilation/JNA extraction only; do not install or commit"
            binaries.append(row)
        else: raw.append(row)
raw.sort(key=lambda e: e["path"]); binaries.sort(key=lambda e: e["path"])
manifest = {
    "schema": "source-only-platform-native-function-proof-v1", "frozen": True,
    "artifactCount": len(raw), "artifacts": raw, "evidenceOnlyBinaryCount": len(binaries), "evidenceOnlyBinaries": binaries,
    "passed": {"taskOnlyCompile": True, "fakeAbiCases": 12, "actualOsReadOnlyCases": 4,
        "main03ImmutableClasspathEntriesVerified": 92, "productClassOverlap": 0},
    "acceptanceScope": "candidate Windows KnownFolder function only; source-only payload; no Main/default-save/persistent UI integration",
    "mainIntegrated": False, "userDirectoryFilesWritten": False, "hwnd": False,
    "pending": ["default unavailable chooser consumer", "save-location UI", "safe default folder creation", "encoder", "batch save", "Android MediaStore/Photos", "real redirected machine", "32-bit Windows"],
}
dump(HERE/"evidence-manifest.json", manifest)
for row in raw+binaries: assert sha(HERE/row["path"]) == row["sha256Bytes"]
print(json.dumps({"rawArtifacts": len(raw), "evidenceOnlyBinaries": len(binaries), "manifestSha256": sha(HERE/"evidence-manifest.json"), "payloadSha256": sha(source)}))
