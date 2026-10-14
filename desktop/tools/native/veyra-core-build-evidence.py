"""Collect actual shared-core object/dependency facts for the unchanged installer.

No DLL is loaded. Seven required checks are emitted only after the successful
build's eleven real objects and MSVC /sourceDependencies reports are inspected.
Formats: https://learn.microsoft.com/en-us/windows/win32/debug/pe-format
https://learn.microsoft.com/en-us/cpp/build/reference/sourcedependencies
"""
from pathlib import Path
import hashlib
import json
import os
import re
import struct

IDENTITY_FILES = {
    "buildClosureManifestSha256": ("veyra-core/shared-build-closure.json", "c33283f4f26ac0fa8117b341848cc2aa34749cd12b47c6d18b75765e2da7e12d"),
    "cmakeSha256": ("veyra-core/CMakeLists.txt", "d184e623af38cf9386a67838aa438c4e1cffbeaf202f81836f68e4ea85922b8a"),
    "officialSdkManifestSha256": ("veyra-core/shared-ngx-sdk-pins.json", "5fb7a798b0a753f9933fba3b9bec539d592fa7322f9f59b718bdd44fb1c5f812"),
    "sharedHostSourceSha256": ("veyra-core/bilipai_ngx_shared_host.cpp", "e21dadb460222ef92c5de38246bb34f0d78e245f3ca798c2116a259899a8a7af"),
    "sourceManifestSha256": ("veyra-core/shared-source-manifest.json", "7320fed4931e22334a3d5a2086e93cdf0cb8b227eda75a216aa7bc8efbb80a5f"),
    "v1CoreSourceSha256": ("veyra-core/bilipai_veyra_core_v1.cpp", "84e0b6d9525944beeba01b2e7d222e4607801a2347fac780b056754025138cc5"),
    "v1HeaderSha256": ("veyra-core/bilipai_veyra_core_v1.h", "0b9521abd2725e5da969a1dad81bff51619847a989a07563dcf4b1df4a64e569"),
    "v2CoreSourceSha256": ("veyra-dlss-sr/bilipai_veyra_dlss_sr_v2.cpp", "d2cbc169cef2a3350111b1dbc9a18012e8b53d897f00f31d6f74fc638fd622d5"),
    "v2HeaderSha256": ("veyra-dlss-sr/bilipai_veyra_dlss_sr_v2.h", "af884cc3d73262dafa19a76a32f0b85c48a2e3bde912bf59885d8de5d4cdd6bc"),
}
TARGETS = ("bilipai_veyra_core", "bilipai_veyra_abi_layout", "bilipai_veyra_dlss_abi_layout")


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def key(path):
    return str(Path(path).resolve()).casefold()


def inside(path, root):
    value, prefix = key(path), key(root)
    return value == prefix or value.startswith(prefix + os.sep)


def bounded_file(path, maximum=128 * 1024 * 1024):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError("Missing or unbounded native build evidence: " + path.name)
    return path.read_bytes()


def coff_symbols(raw):
    if len(raw) < 60 or len(raw) > 128 * 1024 * 1024:
        raise ValueError("Truncated or unbounded COFF object")
    machine, sections, _, table, count, optional, _ = struct.unpack_from("<HHIIIHH", raw)
    if machine != 0x8664 or not 1 <= sections <= 32767 or optional != 0:
        raise ValueError("Expected standard x64 COFF object")
    if not 1 <= count <= 1_000_000 or table < 20 + sections * 40 or table + count * 18 + 4 > len(raw):
        raise ValueError("Unbounded COFF symbol table")
    strings = table + count * 18
    size = struct.unpack_from("<I", raw, strings)[0]
    if size < 4 or strings + size > len(raw):
        raise ValueError("Unbounded COFF string table")
    symbols = []
    i = 0
    while i < count:
        at = table + i * 18
        name = raw[at:at+8]
        if name[:4] == b"\0" * 4:
            offset = struct.unpack_from("<I", name, 4)[0]
            if not 4 <= offset < size:
                raise ValueError("Invalid COFF symbol string offset")
            end = raw.find(b"\0", strings + offset, strings + size)
            if end < 0:
                raise ValueError("Unterminated COFF symbol name")
            name = raw[strings+offset:end]
        else:
            name = name.split(b"\0", 1)[0]
        value, section, _, storage, aux = struct.unpack_from("<IhHBB", raw, at+8)
        if i + aux >= count:
            raise ValueError("Unbounded COFF auxiliary symbols")
        if storage == 2:
            symbols.append(dict(name=name.decode("ascii"), section=section, value=value))
        i += 1 + aux
    return symbols


def checked_shutdown_redirect(symbols):
    undefined = {row["name"] for row in symbols if row["section"] == 0 and row["value"] == 0}
    wanted = {"bp_ngx_checked_shutdown_shared", "__imp_bp_ngx_checked_shutdown_shared"}
    original = {"NVSDK_NGX_D3D12_Shutdown1", "__imp_NVSDK_NGX_D3D12_Shutdown1"}
    if not undefined.intersection(wanted) or undefined.intersection(original):
        raise ValueError("Actual NgxCoreHost object lacks the checked shutdown redirect")
    return sorted(undefined.intersection(wanted))


def dependency_document(raw):
    if len(raw) > 8 * 1024 * 1024:
        raise ValueError("Unbounded MSVC dependency report")
    data = json.loads(raw.decode("utf-8-sig"))
    if data.get("Version") not in ("1.1", "1.2") or not isinstance(data.get("Data"), dict):
        raise ValueError("Unknown MSVC source dependency schema")
    row = data["Data"]
    if row.get("ProvidedModule") or row.get("ImportedModules") or row.get("ImportedHeaderUnits") or row.get("PCH"):
        raise ValueError("Unexpected module or precompiled header in fixed native closure")
    paths = [row.get("Source")] + row.get("Includes", []) if isinstance(row.get("Includes"), list) else []
    if not paths or len(paths) > 8192 or any(not isinstance(p, str) or not Path(p).is_absolute() or any(ord(c) < 32 for c in p) for p in paths):
        raise ValueError("Invalid absolute MSVC source dependency paths")
    return Path(paths[0]), [Path(p) for p in paths[1:]]


def source_identity(repo):
    native = repo / "desktop/native"
    identity = {}
    for field, (relative, expected) in IDENTITY_FILES.items():
        if sha(bounded_file(native / relative)) != expected:
            raise ValueError("Shared runtime identity source changed: " + field)
        identity[field] = expected
    manifest = json.loads((native / "veyra-core/shared-source-manifest.json").read_bytes())
    return identity, manifest


def collect(repo, build, sdk_directory, record, closure, sdk, build_started, system_roots):
    if record.get("nativeCoreLinked") is not True or any(type(record.get(p)) is not int or record[p] != 0 for p in ("actualConfigureExit", "actualBuildExit")):
        raise ValueError("Actual successful native build exits are required")
    if any(not isinstance(record.get(p), dict) or not record[p] for p in ("sourceBefore", "sourceAfter", "sdkBefore", "sdkAfter")) or record["sourceBefore"] != record["sourceAfter"] or record["sdkBefore"] != record["sdkAfter"]:
        raise ValueError("Native build input observations differ")
    if record.get("sourceRepository") != "tonysuper666-creator/BiliPai-Windows" or not re.fullmatch("[0-9a-f]{40}", record.get("sourceSha", "")):
        raise ValueError("Native build repository or source identity differs")
    identity, manifest = source_identity(repo)
    native = repo / "desktop/native"
    expected = {target: {} for target in TARGETS}
    source_pins = {key(repo / p): digest for p, digest in record["sourceBefore"].items()}
    allowed = dict(source_pins)
    original_headers = set()
    for row in closure["ownedSources"]:
        path = native / row["path"]
        if path.suffix != ".cpp":
            continue
        target = TARGETS[1] if row["path"] == "veyra-core/abi_layout_compile.cpp" else TARGETS[2] if row["path"] == "veyra-dlss-sr/abi_layout_compile.cpp" else TARGETS[0]
        expected[target][key(path)] = path
    for row in closure["fixedUniqueSources"]:
        path = native / row["sourceClosure"] / "upstream" / row["path"]
        if path.suffix == ".cpp":
            expected[TARGETS[0]][key(path)] = path
        if row["path"].startswith("include/"):
            original_headers.add(key(path))
            allowed[key(build / "verified-upstream-include" / row["path"][len("include/"):])] = row["sha256"]
    for row in sdk["headers"]:
        allowed[key(sdk_directory / "include" / row["relativePath"])] = row["sha256"]
    if [len(expected[t]) for t in TARGETS] != [9, 1, 1] or not system_roots:
        raise ValueError("Complete eleven-object fixed closure and actual MSVC system roots required")
    checked = {}
    evidence = []
    shutdown = None
    for target in TARGETS:
        object_dir = build / "CMakeFiles" / (target + ".dir")
        objects = list(object_dir.rglob("*.obj"))
        reports = list((build / "dependencies" / target).glob("*.json"))
        if len(objects) != len(expected[target]) or len(reports) != len(expected[target]):
            raise ValueError("Actual object and dependency counts differ: " + target)
        remaining = dict(expected[target])
        for report in sorted(reports):
            report_raw = bounded_file(report, 8 * 1024 * 1024)
            source, includes = dependency_document(report_raw)
            if key(source) not in remaining:
                raise ValueError("Duplicate or foreign compiled source dependency")
            remaining.pop(key(source))
            candidates = [p for p in objects if p.name.casefold() == (source.name + ".obj").casefold()]
            if len(candidates) != 1:
                raise ValueError("Ambiguous actual compiler object for source")
            obj = candidates[0]
            if min(obj.stat().st_mtime, report.stat().st_mtime) < build_started - 2.0:
                raise ValueError("Stale object or source dependency report")
            raw = bounded_file(obj)
            symbols = coff_symbols(raw)
            include_rows = []
            for path in [source] + includes:
                resolved = key(path)
                if resolved in original_headers:
                    raise ValueError("Original Veyra header bypassed the single verified include root")
                if path.name.casefold().startswith("nvsdk_ngx") and resolved not in allowed:
                    raise ValueError("Compiler consumed an NGX header outside the fixed SDK")
                if resolved not in allowed and not any(inside(path, root) for root in system_roots):
                    raise ValueError("Compiler consumed a dependency outside the fixed source, SDK and MSVC roots: " + path.name)
                if resolved not in checked:
                    digest = sha(bounded_file(path))
                    if resolved in allowed and digest != allowed[resolved]:
                        raise ValueError("Actual compiler dependency bytes differ: " + path.name)
                    checked[resolved] = digest
                include_rows.append(dict(path=str(path), sha256=checked[resolved]))
            row = dict(target=target, source=str(source), object=str(obj.relative_to(build)),
                       objectSha256=sha(raw), objectBytes=len(raw), externalSymbolCount=len(symbols),
                       dependencyReport=str(report.relative_to(build)), dependencyReportSha256=sha(report_raw),
                       dependencies=include_rows)
            if source.name == "NgxCoreHost.cpp":
                if shutdown is not None:
                    raise ValueError("More than one original host translation unit")
                shutdown = checked_shutdown_redirect(symbols)
                row["checkedShutdownExternalSymbols"] = shutdown
            evidence.append(row)
        if remaining:
            raise ValueError("Actual source dependency closure is incomplete")
    if shutdown is None or set(record["exports"]) != set(manifest["exports"]) or len(record["exports"]) != 8:
        raise ValueError("Actual shutdown object or shared ABI exports are incomplete")
    receipt = dict(schema=1, variant=manifest["variant"], sourceCommit=manifest["sourceCommit"],
                   moduleRelativeName=manifest["moduleRelativeName"],
                   actualNgxHostEngineVersion=manifest["actualNgxHostEngineVersion"],
                   adapterEngineVersions=manifest["adapterEngineVersions"], **identity,
                   module=dict(sha256=record["moduleSha256"], bytes=record["moduleBytes"],
                               architecture="windows-x64", exports=record["exports"]),
                   checks=dict(actualObjectCompileExit=record["actualBuildExit"], actualDllLinkExit=record["actualBuildExit"],
                               onlyOneHostTranslationUnit=True, singleVerifiedIncludeRoot=True,
                               eightActualExports=True, checkedShutdownRedirectCoff=True,
                               sourceDependenciesExactClosure=True),
                   buildProvenance=dict(repository=record["sourceRepository"], sourceSha=record["sourceSha"],
                                        compilerEvidenceFile="native-core-object-evidence.json"),
                   hardwareExecuted=False, applicationExecuted=False, releaseQualified=False)
    proof = dict(schema=1, objects=evidence, actualObjectCount=len(evidence),
                 systemIncludeRoots=[str(p) for p in system_roots], checkedDependencyCount=len(checked),
                 formatReferences=["https://learn.microsoft.com/en-us/windows/win32/debug/pe-format",
                                   "https://learn.microsoft.com/en-us/cpp/build/reference/sourcedependencies"],
                 hardwareExecuted=False, applicationExecuted=False)
    return receipt, proof
