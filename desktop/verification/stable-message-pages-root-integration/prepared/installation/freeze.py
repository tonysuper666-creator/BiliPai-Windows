"""Freeze only this supplement after final committed Root anchors are prepared.
No Candidate mutation, build, network or account operation. Long paths are explicit.
"""
from pathlib import Path
import hashlib, json, os, re, subprocess, sys
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding="utf8")
P = Path(__file__).resolve().parent
M = P.parents[2]
C = M.parent / "BiliPai-v023"

def wide(path):
    value = str(path)
    return Path(value if value.startswith("\\\\?\\") else "\\\\?\\" + os.path.abspath(value))

def raw(path):
    return wide(path).read_bytes()

def sha(path):
    return hashlib.sha256(raw(path)).hexdigest()

contract = json.loads(raw(P / "root-install-contract.json"))
F = Path(contract["frozenPacket"])
assert sha(F / "install-contract.json") == contract["frozenContractSha256"]
assert sha(F / "frozen-handoff.json") == contract["frozenHandoffSha256"]
old = json.loads(raw(F / "frozen-handoff.json"))
for row in old["files"]:
    assert sha(F / row["path"]) == row["sha256Bytes"], row["path"]
for row in contract["exactHunkTargets"]:
    original = subprocess.check_output(["git", "-C", str(C), "show", contract["candidateBase"] + ":" + row["target"]])
    assert hashlib.sha256(original).hexdigest() == row["baseRawSha256"], row["target"]
    assert sha(P / "baseline" / row["target"]) == row["baseRawSha256"]
    assert sha(P / "prospective" / row["target"]) == row["desiredRawSha256"]
    assert sha(P / row["patch"]) == row["patchSha256"]
proof = json.loads(raw(P / "baseline-install-proof.json"))
assert proof["passed"] and not proof["applied"] and proof["productionWrites"] == 0
assert proof["contractSha256"] == sha(P / "root-install-contract.json")
assert proof["exactHunks"] == 5 and proof["newCopies"] == 4
assert proof["registryAppends"] == 13 and proof["registryFeatureUnions"] == 4
compile_proof = json.loads(raw(P / "compile-runs/04/result.json"))
assert compile_proof["passed"] and compile_proof["snapshot"] == 87
assert compile_proof["explicitProspectiveProductionInputs"] == 23
assert compile_proof["productionWrites"] == compile_proof["sharedGradleRuns"] == 0
for row in compile_proof["prospectiveRootInputs"]:
    assert sha(row["path"]) == row["sha256Bytes"], row["path"]
protected = json.loads(raw(P / "protected-consumers.json"))
assert protected["passed"] and protected["candidateBase"] == contract["candidateBase"]
test_source = F / "prepared/desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopOriginalMessagePagesTest.kt"
methods = re.findall(r"@Test\s+fun\s+(\w+)\s*\(\)\s*:\s*Unit", raw(test_source).decode("utf8"))
assert len(methods) == len(set(methods)) == 14

excluded = ["classes/** (compiler intermediates; emitted JARs and immutable inputs remain pinned)",
            "__pycache__/**", "frozen-handoff.json (self)"]
files = []
for path in sorted(wide(P).rglob("*"), key=lambda p: p.as_posix()):
    relative = path.relative_to(wide(P))
    if not path.is_file() or any(part in ("classes", "__pycache__") for part in relative.parts):
        continue
    if relative.as_posix() == "frozen-handoff.json":
        continue
    files.append(dict(path=relative.as_posix(), lengthBytes=path.stat().st_size,
                      sha256Bytes=hashlib.sha256(path.read_bytes()).hexdigest()))
report = dict(schemaVersion=1, candidateBase=contract["candidateBase"],
              rootContractSha256=sha(P / "root-install-contract.json"),
              frozenPacketContractSha256=contract["frozenContractSha256"],
              frozenPacketHandoffSha256=contract["frozenHandoffSha256"],
              exactHunkTargets=5, copyNewTargets=4, registryAppends=13, registryFeatureUnions=4,
              existingFilesInstallMode="exact hunks only; full prospective files are compile overlays",
              inventoryTraversal="Win32 extended absolute paths", exclusions=excluded, files=files,
              productionWrites=0, sharedGradleRuns=0, rootRuntimeAccepted=False,
              currentProspectiveCompile=dict(snapshot=87, inputs=23, result="compile-runs/04/result.json", passed=True),
              protectedConsumers="protected-consumers.json",
              discoveredJUnitMethodsExpected=methods,
              proofBoundary="Prepared install/source/compile-policy evidence only. Root normal product build, JUnit discovery and message UI acceptance remain separate.")
destination = P / "frozen-handoff.json"
assert not wide(destination).exists(), "Frozen supplement already exists; preserve it and prepare a new sibling lane"
wide(destination).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
print(json.dumps(dict(candidateBase=contract["candidateBase"], files=len(files),
                      contractSha256=report["rootContractSha256"], handoffSha256=sha(destination)), indent=2))
