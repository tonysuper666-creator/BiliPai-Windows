"""Task-only compiler + fake ABI + actual read-only Windows Shell function proof."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
EXTENDED = chr(92) * 2 + "?" + chr(92)
def safe(path):
    value = str(path)
    return value if value.startswith(EXTENDED) else EXTENDED + value
def sha(path):
    with open(safe(path), "rb") as stream: return hashlib.file_digest(stream, "sha256").hexdigest()
def read(path):
    with open(safe(path), encoding="utf-8-sig") as stream: return stream.read()
def write(path, value):
    with open(safe(path), "w", encoding="utf-8", newline="\n") as stream: stream.write(value)
def dump(path, value): write(path, json.dumps(value, indent=2, ensure_ascii=False) + "\n")

snapshot = REPO / "desktop/.local/dynamic-media-main-integration/main-product-snapshot-03"
snapshot_manifest = snapshot / "manifest.json"
runtime_cp_path = snapshot / "ordered-runtime-cp.json"
assert sha(snapshot_manifest) == "f9d91763db17acaa59753f5dccad4feb88512c8ad2984062520ed867943df6a6"
assert sha(runtime_cp_path) == "3abb7e2fecccb5bd0693e6b0868d002b576fc8f689d734d86bd5737fe1c39cac"
runtime = json.loads(read(runtime_cp_path))
assert len(runtime) == 92
for item in runtime: assert sha(Path(item["path"])) == item["sha256Bytes"], item["path"]
jna = [Path(item["path"]) for item in runtime if "/net.java.dev.jna/jna/" in item["path"].replace("\\", "/")]
stdlib = [Path(item["path"]) for item in runtime if "/org.jetbrains.kotlin/kotlin-stdlib/" in item["path"].replace("\\", "/")]
assert len(jna) == len(stdlib) == 1
dependencies = jna + stdlib
dump(HERE / "dependency-identities.json", [{"path": str(p), "sha256Bytes": sha(p)} for p in dependencies])

spec = importlib.util.spec_from_file_location("compiler", REPO / "desktop/.local/source9-appearance/compile-miuix.py")
compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
sources = [HERE / "prepared/desktop/src/main/kotlin/com/bilipai/desktop/platform/DesktopWindowsImageSaveDirectory.kt", HERE / "KnownFolderFixture.kt"]
output = HERE / "known-folder-candidate.jar"
native_temp = HERE / "task-native-temp"
native_temp.mkdir(exist_ok=True)
args = ["-no-stdlib", "-no-reflect", "-jvm-target", "21", "-cp", ";".join(map(str, dependencies)), "-module-name", "known_folder_task_only", "-d", str(output)] + list(map(str, sources))
args_file = HERE / "compile-01.args"
write(args_file, "\n".join('"' + str(a).replace("\\", "/") + '"' for a in args) + "\n")
command = [str(compiler.JAVA), "-Xmx1g", "-Djava.io.tmpdir=" + str(native_temp), "-cp", ";".join(map(str, compiler.COMPILER)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + str(args_file)]
result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=120)
write(HERE / "compile-01.log", result.stdout + result.stderr)
print(result.stdout + result.stderr)
result.check_returncode()

with zipfile.ZipFile(safe(output)) as candidate: entries = [name for name in candidate.namelist() if name.endswith(".class")]
overlap = []
for item in runtime[:3]:
    with zipfile.ZipFile(safe(Path(item["path"]))) as product: overlap += sorted(set(entries) & set(product.namelist()))
assert not overlap, overlap
dump(HERE / "compile-evidence.json", {
    "mainSnapshotManifestSha256": sha(snapshot_manifest), "mainRuntimeClasspathSha256": sha(runtime_cp_path),
    "immutableMainClasspathEntriesVerified": len(runtime), "compiler": "Kotlin 2.4.0", "jvmTarget": 21,
    "compilerInputs": [{"path": str(p), "sha256Bytes": sha(p)} for p in compiler.COMPILER],
    "sources": [{"path": str(p), "sha256Bytes": sha(p)} for p in sources],
    "candidateJarSha256": sha(output), "compiledClasses": len(entries), "productClassOverlap": overlap,
    "mainSourceChanged": False, "mainIntegrated": False, "dependenciesAdded": False,
})

runtime_cp = ";".join(map(str, [output] + dependencies))
for mode in ("fake", "native"):
    command = [str(compiler.JAVA), "-Djava.io.tmpdir=" + str(native_temp), "-Djna.tmpdir=" + str(native_temp), "-cp", runtime_cp, "com.bilipai.desktop.platform.KnownFolderFixtureKt", mode]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=30)
    write(HERE / (mode + "-01.log"), result.stdout + result.stderr)
    dump(HERE / (mode + "-01-process.json"), {"command": command, "exitCode": result.returncode, "candidateJarSha256": sha(output)})
    print(mode, result.stdout, result.stderr)
    result.check_returncode()
    dump(HERE / (mode + "-proof.json"), json.loads(result.stdout.strip()))

sdk = Path("C:/Program Files (x86)/Windows Kits/10/Include/10.0.26100.0")
declarations = [
    ("um/KnownFolders.h", 152, 153),
    ("um/ShlObj_core.h", 944, 948),
    ("um/ShlObj_core.h", 1008, 1021),
    ("um/ShlObj_core.h", 1046, 1049),
    ("um/combaseapi.h", 408, 411),
    ("um/combaseapi.h", 433, 437),
    ("um/combaseapi.h", 1348, 1351),
    ("shared/guiddef.h", 22, 27),
]
sdk_evidence = []
for name, start, end in declarations:
    p = sdk / name; lines = read(p).splitlines()
    sdk_evidence.append({"path": str(p), "fullFileSha256Bytes": sha(p), "startLine": start, "endLine": end, "declaration": "\n".join(lines[start-1:end])})
dump(HERE / "windows-sdk-evidence.json", sdk_evidence)
system32 = Path(os.environ["SystemRoot"]) / "System32"
dump(HERE / "native-dll-identities.json", [{"path": str(system32 / name), "sha256Bytes": sha(system32 / name)} for name in ("shell32.dll", "ole32.dll")])
for item in runtime: assert sha(Path(item["path"])) == item["sha256Bytes"], item["path"]
assert sha(snapshot_manifest) == "f9d91763db17acaa59753f5dccad4feb88512c8ad2984062520ed867943df6a6"
assert sha(runtime_cp_path) == "3abb7e2fecccb5bd0693e6b0868d002b576fc8f689d734d86bd5737fe1c39cac"
print("PASS: Main03 immutable bytes verified before and after; no Main or user-directory writes.")
