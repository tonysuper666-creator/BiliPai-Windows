"""Isolated current-review producer. No Gradle, main-file writes, HWND or ShareUI.

Root may adapt this argv-based producer into its controlled Gradle task. Reusing an
output or its metadata is intentionally unsupported here: this script always builds.
"""
from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = Path(__file__).resolve().parent
p = argparse.ArgumentParser()
p.add_argument("--source", type=Path, default=HERE / "stable-source-a/native/DesktopDiagnosticShare.cpp")
p.add_argument("--output", type=Path, required=True)
p.add_argument("--vc-root", type=Path, default=Path("C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207"))
p.add_argument("--sdk-root", type=Path, default=Path("C:/Program Files (x86)/Windows Kits/10"))
p.add_argument("--sdk-version", default="10.0.26100.0")
o = p.parse_args()
source = o.source.resolve(strict=True)
out = o.output.absolute()
assert not out.exists(), "Do not overwrite or trust existing producer output"
out.mkdir(parents=True)
vc = o.vc_root.resolve(strict=True)
sdk = o.sdk_root.resolve(strict=True)
binroot = vc / "bin/Hostx64/x64"
includes = [vc / "include"] + [sdk / "Include" / o.sdk_version / name for name in ["ucrt", "shared", "um", "winrt", "cppwinrt"]]
libraries = [vc / "lib/x64", sdk / "Lib" / o.sdk_version / "ucrt/x64", sdk / "Lib" / o.sdk_version / "um/x64"]
for root in includes + libraries:
    assert root.is_dir(), str(root)
env = os.environ.copy()
env.update(VSLANG="1033", INCLUDE=";".join(map(str, includes)), LIB=";".join(map(str, libraries)))
# Prevent CL's implicit environment flags from invalidating the declared argv.
for key in ("CL", "_CL_", "LINK", "_LINK_"):
    env.pop(key, None)
deps = out / "source-dependencies.json"
command = [str(binroot / "cl.exe"), "/nologo", "/std:c++20", "/EHsc", "/W4", "/WX", "/MT", "/O2", "/LD", "/utf-8",
           "/sourceDependencies", str(deps), str(source), "/Fo" + str(out / "DesktopDiagnosticShare.obj"),
           "/Fe" + str(out / "bilipai-diagnostic-share.dll"), "/link", "/NOLOGO", "/Brepro", "/VERBOSE:LIB",
           "runtimeobject.lib", "ole32.lib", "oleaut32.lib", "user32.lib",
           "/IMPLIB:" + str(out / "bilipai-diagnostic-share.lib"), "/PDB:" + str(out / "bilipai-diagnostic-share.pdb")]
r = subprocess.run(command, cwd=out, env=env, capture_output=True, timeout=90)
raw = r.stdout + r.stderr
(out / "compile.raw.log").write_bytes(raw)
log = raw.decode("utf-8", errors="replace")
(out / "compile.log").write_text(log, encoding="utf-8")
r.check_returncode()

def pin(path):
    path = Path(path).resolve(strict=True)
    return dict(path=str(path), sha256Bytes=hashlib.sha256(path.read_bytes()).hexdigest(), bytes=path.stat().st_size)

data = json.loads(deps.read_text(encoding="utf-8-sig"))["Data"]
assert Path(data["Source"]).resolve() == source
headers = sorted({str(Path(s).resolve(strict=True)) for s in data["Includes"]}, key=str.casefold)
assert len(headers) > 100, "Not a complete transitive SDK/STL header graph"
searched = []
for line in log.splitlines():
    # LINK's localized message prefix is deliberately not parsed. VSLANG=1033
    # does not force an uninstalled English message pack on this machine.
    match = re.search(r"([A-Za-z]:\\.+\.lib):\s*$", line)
    if match:
        current = Path(match.group(1)).resolve(strict=True)
        if current not in searched:
            searched.append(current)
assert len(searched) >= 8, "LINK /VERBOSE:LIB did not capture import + static CRT archives"
tools = sorted([f for f in binroot.iterdir() if f.is_file() and f.suffix.casefold() in (".exe", ".dll")], key=lambda f: f.name.casefold())
assert all((binroot / name) in tools for name in ("cl.exe", "c1xx.dll", "c2.dll", "link.exe"))
evidence = dict(passed=True, source=pin(source), dll=pin(out / "bilipai-diagnostic-share.dll"),
                command=command, includeSearchOrder=list(map(str, includes)), librarySearchOrder=list(map(str, libraries)),
                explicitEnvironment=dict(INCLUDE=env["INCLUDE"], LIB=env["LIB"], VSLANG=env["VSLANG"]),
                clearedImplicitFlagVariables=["CL", "_CL_", "LINK", "_LINK_"],
                sourceDependencies=pin(deps), transitiveHeaders=[pin(f) for f in headers],
                searchedLibrariesConservativePins=[pin(f) for f in searched], actualMemberSelectionClaimed=False,
                compilerBinDirectoryConservativePins=[pin(f) for f in tools],
                runtimeExpectedShaMayComeFromThisFreshControlledBuild=True,
                cacheMetadataAloneIsNotTrust=True, publicReleaseAuthorizationVerified=False,
                sharedGradle=False, mainWritten=False, HWND=False, ShareUI=False, externalReceiver=False)
(out / "producer-input-graph.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
print(json.dumps(dict(passed=True, headers=len(headers), searchedLibraries=len(searched), compilerFiles=len(tools), dllSha256Bytes=evidence["dll"]["sha256Bytes"])))
