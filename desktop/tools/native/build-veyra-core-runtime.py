"""Stage the pinned public NGX SDK and link the existing Windows shared core.

Default operation prepares inputs only. Linking is limited to the own public
repository's standard hosted Windows job; it never loads the resulting DLL.
"""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import sys
import time
import urllib.request

REPOSITORY = "tonysuper666-creator/BiliPai-Windows"
SDK_COMMIT = "374959484e79a640feaba44c93ac8cfb0a03f5b5"
SDK_MANIFEST_SHA = "5fb7a798b0a753f9933fba3b9bec539d592fa7322f9f59b718bdd44fb1c5f812"
CLOSURE_SHA = "c33283f4f26ac0fa8117b341848cc2aa34749cd12b47c6d18b75765e2da7e12d"
EXPORTS = {"bv_create_v1", "bv_process_v1", "bv_reset_v1", "bv_destroy_v1",
           "bvd_create_v2", "bvd_process_v2", "bvd_reset_v2", "bvd_destroy_v2"}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def relative_path(raw):
    p = PurePosixPath(raw)
    if not raw or p.is_absolute() or any(x in raw for x in ("\\", ":")) or any(x in ("", ".", "..") for x in raw.split("/")):
        raise ValueError("Invalid fixed input path")
    return Path(*p.parts)


def fixed_json(path, digest):
    raw = path.read_bytes()
    if sha(raw) != digest:
        raise ValueError("Fixed native build manifest changed")
    return json.loads(raw)


def checked_file(path, row):
    if path.is_symlink():
        raise ValueError("Linked native input rejected")
    raw = path.read_bytes()
    if sha(raw) != row["sha256"] or "bytes" in row and len(raw) != row["bytes"]:
        raise ValueError("Fixed native input bytes changed: " + path.name)
    blob = row.get("gitBlobSha1")
    if blob and hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() != blob:
        raise ValueError("Fixed SDK Git blob changed")
    return raw


def inputs(repo):
    native = repo / "desktop/native"
    core = native / "veyra-core"
    closure = fixed_json(core / "shared-build-closure.json", CLOSURE_SHA)
    sdk = fixed_json(core / "shared-ngx-sdk-pins.json", SDK_MANIFEST_SHA)
    if sdk["sourceCommit"] != SDK_COMMIT or sdk["sourceRepository"] != "https://github.com/NVIDIA/DLSS" or len(sdk["headers"]) != 6:
        raise ValueError("Unknown official SDK closure")
    if closure["sdkManifestSha256"] != SDK_MANIFEST_SHA or closure["staticShim"] != sdk["staticShim"]:
        raise ValueError("Native and official SDK closure differ")
    pins = {}
    for row in closure["ownedSources"] + closure["cmakeSources"]:
        path = native / relative_path(row["path"])
        pins[path.relative_to(repo).as_posix()] = sha(checked_file(path, row))
    for row in closure["fixedUniqueSources"]:
        if row["sourceClosure"] not in ("veyra-core", "veyra-dlss-sr"):
            raise ValueError("Unknown original source owner")
        path = native / row["sourceClosure"] / "upstream" / relative_path(row["path"])
        pins[path.relative_to(repo).as_posix()] = sha(checked_file(path, row))
    return closure, sdk, pins


def sdk_entries(sdk):
    rows = []
    for row in sdk["headers"]:
        name = relative_path(row["relativePath"])
        if len(name.parts) != 1 or not name.name.startswith("nvsdk_ngx") or name.suffix != ".h":
            raise ValueError("Unexpected official header")
        rows.append(("include/" + name.as_posix(), Path("include") / name, row))
    rows += [(sdk["staticShim"]["upstreamPath"], Path("nvsdk_ngx_s.lib"), sdk["staticShim"]),
             (sdk["license"]["upstreamPath"], Path("LICENSE.txt"), sdk["license"])]
    if len({str(local).casefold() for _, local, _ in rows}) != len(rows):
        raise ValueError("Duplicate SDK destination")
    for remote, _, _ in rows:
        relative_path(remote)
    return rows


def stage_sdk(sdk, directory):
    directory.mkdir(parents=True, exist_ok=True)
    for remote, local, pin in sdk_entries(sdk):
        target = directory / local
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            checked_file(target, pin)
            continue
        part = target.with_name(target.name + ".part")
        if part.exists() or part.is_symlink():
            raise ValueError("Unfinished SDK download requires inspection")
        url = "https://raw.githubusercontent.com/NVIDIA/DLSS/" + SDK_COMMIT + "/" + remote
        size = 0
        with urllib.request.urlopen(url, timeout=45) as response, part.open("xb") as out:
            while chunk := response.read(65536):
                size += len(chunk)
                if size > 10 * 1024 * 1024:
                    raise ValueError("SDK file exceeds its bounded download")
                out.write(chunk)
        checked_file(part, pin)
        part.replace(target)


def pe_exports(raw):
    """Inspect real x64 export code addresses without loading or executing it."""
    if len(raw) < 256 or raw[:2] != b"MZ":
        raise ValueError("Native core is not PE")
    pe = struct.unpack_from("<I", raw, 60)[0]
    if pe + 24 > len(raw) or raw[pe:pe+4] != b"PE\0\0":
        raise ValueError("Invalid native PE header")
    machine, count = struct.unpack_from("<HH", raw, pe+4)
    optional_size, flags = struct.unpack_from("<HH", raw, pe+20)
    optional = pe + 24
    if machine != 0x8664 or not flags & 0x2000 or optional_size < 120 or not 1 <= count <= 96 or optional + optional_size + count * 40 > len(raw):
        raise ValueError("Native core is not a bounded x64 DLL")
    if struct.unpack_from("<H", raw, optional)[0] != 0x20b or struct.unpack_from("<I", raw, optional+108)[0] < 1:
        raise ValueError("Native core has no PE32+ export directory")
    sections = []
    for i in range(count):
        at = optional + optional_size + i*40
        virtual_size, address, size, offset = struct.unpack_from("<IIII", raw, at+8)
        sections.append((address, virtual_size, size, offset, struct.unpack_from("<I", raw, at+36)[0]))

    def offset(rva, size=1):
        for address, _, length, at, _ in sections:
            delta = rva - address
            if 0 <= delta and delta + size <= length and at + delta + size <= len(raw):
                return at + delta
        raise ValueError("Native export has no bounded file backing")

    export_rva, export_size = struct.unpack_from("<II", raw, optional+112)
    at = offset(export_rva, 40)
    functions, names, function_rva, names_rva, ordinals_rva = struct.unpack_from("<IIIII", raw, at+20)
    if not 1 <= names <= functions <= 4096:
        raise ValueError("Unexpected native export count")
    function_at, name_at, ordinal_at = offset(function_rva, functions*4), offset(names_rva, names*4), offset(ordinals_rva, names*2)
    result = {}
    for i in range(names):
        start = offset(struct.unpack_from("<I", raw, name_at+i*4)[0])
        end = raw.find(b"\0", start, min(start+256, len(raw)))
        if end < 0:
            raise ValueError("Unterminated native export name")
        name = raw[start:end].decode("ascii")
        ordinal = struct.unpack_from("<H", raw, ordinal_at+i*2)[0]
        if ordinal >= functions or name in result:
            raise ValueError("Ambiguous native export")
        rva = struct.unpack_from("<I", raw, function_at+ordinal*4)[0]
        offset(rva)
        executable = any(address <= rva < address + length and flags & 0x20000000 for address, _, length, _, flags in sections)
        if name in EXPORTS and (not executable or export_rva <= rva < export_rva+export_size):
            raise ValueError("Required ABI export is not local executable code")
        result[name] = rva
    if not EXPORTS <= result.keys():
        raise ValueError("Shared core ABI exports are incomplete")
    return sorted(result)


def cloud_build_allowed(environment):
    return os.name == "nt" and environment.get("GITHUB_ACTIONS") == "true" and environment.get("GITHUB_REPOSITORY") == REPOSITORY and environment.get("RUNNER_ENVIRONMENT") == "github-hosted" and environment.get("RUNNER_OS") == "Windows"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--execute-build", action="store_true")
    args = parser.parse_args()
    if args.execute_build and not cloud_build_allowed(os.environ):
        raise ValueError("Native linking is reserved for the own standard hosted Windows job")
    repo, workspace, output = args.repo.resolve(), args.workspace.resolve(), args.output.resolve()
    if workspace == repo or output == repo or workspace == output:
        raise ValueError("Isolated SDK/build and output directories are required")
    if args.execute_build and output.exists() and any(output.iterdir()):
        raise ValueError("Preserve existing native output for inspection; a fresh output directory is required")
    closure, sdk, source_before = inputs(repo)
    stage_sdk(sdk, workspace / "sdk")
    sdk_before = {local.as_posix():sha(checked_file(workspace / "sdk" / local, pin)) for _,local,pin in sdk_entries(sdk)}
    core, build = repo / "desktop/native/veyra-core", workspace / "build"
    configure = ["cmake", "-S", str(core), "-B", str(build), "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release",
                 "-DBILIVEYRA_COMPILE_ONLY=OFF", "-DVEYRA_NGX_INCLUDE="+str(workspace / "sdk/include"),
                 "-DVEYRA_NGX_STATIC_LIBRARY="+str(workspace / "sdk/nvsdk_ngx_s.lib")]
    compile_command = ["cmake", "--build", str(build), "--parallel", "1"]
    output.mkdir(parents=True, exist_ok=True)
    record = dict(schemaVersion=1,sourceRepository=REPOSITORY, sourceSha=subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip(),
                  sourceBefore=source_before, sdkBefore=sdk_before, sdkManifestSha256=SDK_MANIFEST_SHA, sdkCommit=SDK_COMMIT,
                  configureCommand=configure, buildCommand=compile_command, nativeCoreLinked=False,
                  mpvFrameBridgeLinked=False, nvidiaFeatureRuntimeStaged=False, hardwareExecuted=False,
                  releaseQualified=False, desktopReplaced=False, workerMaximum=1,
                  runtimeInstallerReceiptProduced=False)
    if args.execute_build:
        if build.exists() and any(build.iterdir()):
            raise ValueError("Preserve nonempty native build directory for inspection; a fresh build is required")
        tools = {}
        for name in ("cl", "link", "cmake", "ninja"):
            actual = shutil.which(name)
            if not actual:
                raise RuntimeError("Required native build tool unavailable: " + name)
            tools[name] = dict(path=actual, sha256=sha(Path(actual).read_bytes()))
        record['toolchain'] = tools
        record['targetArchitecture'] = os.environ.get('VSCMD_ARG_TGT_ARCH')
        if record['targetArchitecture'] != 'x64':
            raise ValueError('Actual MSVC environment must target x64')
        # Preserve the reviewed commands and inputs even if configure/link fails.
        (output / "native-core-build-receipt.json").write_bytes((json.dumps(record, indent=2)+"\n").encode())
        build_started = time.time()
        for label, command in [("configure", configure), ("build", compile_command)]:
            with (output / (label+".log")).open("wb") as log:
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
            record["actualConfigureExit" if label == "configure" else "actualBuildExit"] = result.returncode
            (output / "native-core-build-receipt.json").write_bytes((json.dumps(record, indent=2)+"\n").encode())
            if result.returncode:
                print((output / (label+".log")).read_text(encoding="utf-8", errors="replace")[-16000:])
                raise RuntimeError("Native core " + label + " failed")
        dll = build / closure["moduleRelativeName"]
        raw = dll.read_bytes()
        exports = pe_exports(raw)
        spec = importlib.util.spec_from_file_location("mpv_native_imports", repo / "desktop/tools/native/build-mpv-rtx-core-runtime.py")
        module = importlib.util.module_from_spec(spec); sys.modules[spec.name] = module; spec.loader.exec_module(module)
        imports = module.pe_imports(raw)
        if any(row["name"].startswith(("msvcp", "vcruntime")) for row in imports):
            raise ValueError("Shared core requires the original static MSVC runtime")
        source_after = inputs(repo)[2]
        sdk_after = {local.as_posix():sha(checked_file(workspace / "sdk" / local, pin)) for _,local,pin in sdk_entries(sdk)}
        if source_before != source_after or sdk_before != sdk_after:
            raise ValueError("Native inputs changed during linking")
        shutil.copyfile(dll, output / dll.name)
        shutil.copyfile(workspace / "sdk/LICENSE.txt", output / "NVIDIA-NGX-SDK-LICENSE.txt")
        shutil.copyfile(core / "upstream/LICENSE", output / "Veyra-GPL-3.0-LICENSE.txt")
        record.update(nativeCoreLinked=True,moduleFile=dll.name,moduleSha256=sha(raw),moduleBytes=len(raw),exports=exports,imports=imports,
                      sourceAfter=source_after,sdkAfter=sdk_after,sourceBeforeAfterUnchanged=True,sdkBeforeAfterUnchanged=True)
        # Preserve compiler facts even if receipt qualification fails; never turn
        # a linked DLL alone into an installer-compatible successful receipt.
        (output / "native-core-build-receipt.json").write_bytes((json.dumps(record, indent=2)+"\n").encode())
        evidence_spec = importlib.util.spec_from_file_location("veyra_core_evidence", repo / "desktop/tools/native/veyra-core-build-evidence.py")
        evidence_module = importlib.util.module_from_spec(evidence_spec); evidence_spec.loader.exec_module(evidence_module)
        for target in evidence_module.TARGETS:
            retained = output / "compiler-evidence" / target
            retained.mkdir(parents=True, exist_ok=True)
            originals = list((build / "CMakeFiles" / (target+".dir")).rglob("*.obj")) + list((build / "dependencies" / target).glob("*.json"))
            if len({p.name.casefold() for p in originals}) != len(originals):
                raise ValueError("Ambiguous compiler evidence archive names")
            for path in originals:
                evidence_module.bounded_file(path)
                shutil.copyfile(path, retained / path.name)
        system_roots = sorted({str(Path(os.environ[name]).resolve()) for name in
            ("VCToolsInstallDir", "WindowsSdkDir", "UniversalCRTSdkDir", "VCINSTALLDIR") if os.environ.get(name)})
        receipt, proof = evidence_module.collect(repo, build, workspace / "sdk", record, closure, sdk,
                                                build_started, [Path(p) for p in system_roots])
        proof_bytes = (json.dumps(proof, indent=2)+"\n").encode()
        (output / "native-core-object-evidence.json").write_bytes(proof_bytes)
        receipt['buildProvenance']['compilerEvidenceSha256'] = sha(proof_bytes)
        receipt_bytes = (json.dumps(receipt, indent=2)+"\n").encode()
        (output / "veyra-native-build-receipt.json").write_bytes(receipt_bytes)
        record.update(runtimeInstallerReceiptProduced=True, runtimeInstallerReceiptSha256=sha(receipt_bytes),
                      actualCompilerEvidenceSha256=sha(proof_bytes), actualObjectCount=proof['actualObjectCount'])
    (output / "native-core-build-receipt.json").write_bytes((json.dumps(record, indent=2)+"\n").encode())
    print(json.dumps({key:record[key] for key in ("sourceSha","nativeCoreLinked","mpvFrameBridgeLinked","hardwareExecuted","workerMaximum")}))


if __name__ == "__main__":
    main()
