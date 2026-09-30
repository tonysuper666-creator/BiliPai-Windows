"""Isolated Library transaction compilation against previously frozen local classes."""
from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess

HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p / "AGENTS.md").is_file() and (p / "desktop/tools").is_dir())
spec = importlib.util.spec_from_file_location("transaction_compiler", REPO / "desktop/.local/source9-appearance/compile-miuix.py")
compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
lifecycle = HERE.parent / "lifecycle-review"
snapshot = lifecycle / "current-main-snapshot.jar"
cp = [str(lifecycle / "classes"), str(snapshot)] + (REPO / "desktop/.local/settings-search-parity/classpath.txt").read_text(encoding="utf-8").strip().split(";")[1:]
paths = ["desktop/src/main/kotlin/com/bilipai/desktop/DesktopLibrary.kt",
    "desktop/src/test/kotlin/com/bilipai/desktop/DesktopLibraryTransactionTest.kt"]
sources = [REPO / path for path in paths] + [HERE / "TestLauncher.kt"]
output = HERE / "classes"; output.mkdir(exist_ok=True)
def argfile(name, values):
    path = HERE / name
    path.write_text("\n".join('"' + value.replace("\\", "/") + '"' for value in values), encoding="utf-8")
    return path
serialization = compiler.jar("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.4.0")
file = argfile("compiler.args", ["-no-stdlib", "-no-reflect", "-jvm-target", "21", "-cp", ";".join(cp),
    "-Xplugin=" + str(serialization), "-Xfriend-paths=" + ",".join(cp[:2]), "-module-name", "library_transaction_fixtures",
    "-d", str(output), *map(str, sources)])
result = subprocess.run([str(compiler.JAVA), "-Dfile.encoding=UTF-8", "-Xmx1g", "-cp", ";".join(map(str, compiler.COMPILER)),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + str(file)], capture_output=True, text=True,
    encoding="utf-8", errors="replace", timeout=60)
(HERE / "compile.log").write_text(result.stdout + result.stderr, encoding="utf-8")
print((result.stdout + result.stderr)[-5000:]); result.check_returncode()
file = argfile("runtime.args", ["-Dfile.encoding=UTF-8", "-cp", str(output) + ";" + ";".join(cp),
    "com.bilipai.desktop.librarytransactionfixture.TestLauncherKt", str(HERE / "junit-evidence.json")])
result = subprocess.run([str(compiler.JAVA), "@" + str(file)], capture_output=True, text=True,
    encoding="utf-8", errors="replace", timeout=30)
(HERE / "runtime.log").write_text(result.stdout + result.stderr, encoding="utf-8")
print((result.stdout + result.stderr)[-6000:]); result.check_returncode()
(HERE / "compile-evidence.json").write_text(json.dumps(dict(passed=True, compiler="Kotlin/serialization 2.4.0",
    sources=3, sharedGradleInvoked=False, sharedClassesRead=False,
    existingSnapshotSha256=hashlib.sha256(snapshot.read_bytes()).hexdigest(),
    inputs=[dict(path=p, sha256Lf=hashlib.sha256((REPO / p).read_text(encoding="utf-8").encode()).hexdigest()) for p in paths]), indent=2) + "\n", encoding="utf-8")
