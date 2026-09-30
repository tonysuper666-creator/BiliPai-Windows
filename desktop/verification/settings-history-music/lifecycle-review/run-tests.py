"""Independent actual controller/store lifecycle tests; no shared Gradle/output."""
from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p / "AGENTS.md").is_file() and (p / "desktop/tools").is_dir())
spec = importlib.util.spec_from_file_location("lifecycle_compiler", REPO / "desktop/.local/source9-appearance/compile-miuix.py")
compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
snapshot = HERE / "current-main-snapshot.jar"
if not snapshot.exists():
    root = REPO / "desktop/build/classes/kotlin/main"
    before = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.rglob("*")) if p.is_file()}
    with zipfile.ZipFile(snapshot, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in before:
            archive.writestr(name, (root / name).read_bytes())
    after = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.rglob("*")) if p.is_file()}
    assert before == after, "Root classes changed during isolated snapshot"
cp = [str(snapshot)] + (REPO / "desktop/.local/settings-search-parity/classpath.txt").read_text(encoding="utf-8").strip().split(";")[1:]
paths = ["desktop/src/main/kotlin/com/bilipai/desktop/DesktopLibrary.kt",
    "desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSearchPreferences.kt",
    "desktop/src/main/kotlin/com/bilipai/desktop/DesktopPlaybackController.kt",
    "desktop/src/test/kotlin/com/bilipai/desktop/DesktopCheckpointFailureTest.kt",
    "desktop/src/test/kotlin/com/bilipai/desktop/data/DesktopSearchRestoreLifecycleTest.kt"]
sources = [REPO / p for p in paths] + [HERE / "TestLauncher.kt"]
output = HERE / "classes"; output.mkdir(exist_ok=True)
serialization = compiler.jar("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.4.0")
def argfile(name, values):
    path = HERE / name
    path.write_text("\n".join('"' + value.replace("\\", "/") + '"' for value in values), encoding="utf-8")
    return path
args = ["-no-stdlib", "-no-reflect", "-jvm-target", "21", "-cp", ";".join(cp), "-Xplugin=" + str(serialization),
    "-Xfriend-paths=" + str(snapshot), "-module-name", "privacy_lifecycle_fixtures", "-d", str(output), *map(str, sources)]
file = argfile("compiler.args", args)
result = subprocess.run([str(compiler.JAVA), "-Dfile.encoding=UTF-8", "-Xmx2g", "-cp", ";".join(map(str, compiler.COMPILER)),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + str(file)], capture_output=True, text=True,
    encoding="utf-8", errors="replace", timeout=90)
(HERE / "compile.log").write_text(result.stdout + result.stderr, encoding="utf-8")
print((result.stdout + result.stderr)[-7000:]); result.check_returncode()
file = argfile("runtime.args", ["-Dfile.encoding=UTF-8", "-cp", str(output) + ";" + ";".join(cp),
    "com.bilipai.desktop.privacylifecyclefixture.TestLauncherKt", str(HERE / "junit-evidence.json")])
result = subprocess.run([str(compiler.JAVA), "@" + str(file)], capture_output=True, text=True,
    encoding="utf-8", errors="replace", timeout=60)
(HERE / "runtime.log").write_text(result.stdout + result.stderr, encoding="utf-8")
print((result.stdout + result.stderr)[-9000:]); result.check_returncode()
(HERE / "compile-evidence.json").write_text(json.dumps(dict(passed=True, sources=len(sources),
    productSnapshotSha256=hashlib.sha256(snapshot.read_bytes()).hexdigest(), sharedGradleInvoked=False,
    compiler="Kotlin 2.4.0", inputs=[dict(path=p, sha256Lf=hashlib.sha256((REPO / p).read_text(encoding="utf-8").encode()).hexdigest()) for p in paths]), indent=2) + "\n", encoding="utf-8")
