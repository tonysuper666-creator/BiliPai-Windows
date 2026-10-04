"""Stage the exact reviewed MSVC files without installing Visual Studio.

Official VSIX payloads reproduce the existing approval. Provisioning never
executes a compiler; the ordinary producer still checks the complete resolved
input graph and the fresh DLL. No approval, driver, or system tools are changed.
"""
from dataclasses import dataclass
from pathlib import Path
import argparse
import importlib.util
import json
import os
import shutil
import sys
import uuid


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("bilipai_native_sdk_provision", HERE / "provision-native-diagnostic-sdk.py")
SDK = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SDK
SPEC.loader.exec_module(SDK)

MSVC_VERSION = "14.44.35207"
APPROVAL_SHA256 = "0ecca05e8510c14d03d7376d1aba492b9eac5f711bcecb653cd9e10dc33810b7"
ARCHIVE_PREFIX = "Contents/VC/Tools/MSVC/" + MSVC_VERSION + "/"


@dataclass(frozen=True)
class Package:
    name: str
    version: str
    bytes: int
    sha256_hex: str
    sha512_base64: str
    url: str
    cache_folder: str
    kind: str = "vc"
    archive_filename: str | None = None

    @property
    def filename(self):
        return self.archive_filename or (self.name + ".vsix")


PACKAGES = (
    Package('Microsoft.VC.14.44.17.14.ASAN.X64.base',
            '14.44.35226',
            63347389,
            '40ab714821d53f02bc029e4d6002a32f086d7676cbb73be21a6043fd2a2f5999',
            'ZddXb8kvDRzuchax7qcoJrNk8NPJc0bGhj/5k5v4abX1TQWc8ompbZOXY2OYflTv5nJdN+t65N9/PZZfD52BJQ==',
            'https://download.visualstudio.microsoft.com/download/pr/67cf767c-5e71-47c2-a54a-cd5631e28942/40ab714821d53f02bc029e4d6002a32f086d7676cbb73be21a6043fd2a2f5999/Microsoft.VC.14.44.17.14.ASAN.X64.base.vsix',
            'Microsoft.VC.14.44.17.14.ASAN.X64.base,version=14.44.35226'),
    Package('Microsoft.VC.14.44.17.14.CA.Ext.Hostx64.Targetx64.base',
            '14.44.35214',
            3954288,
            '6175bc30e14b5143a5170fa222139bbd4d068bb9449bb1f5148454dbc189bb64',
            'wVUx/eOl7ZAXKC7L4KfXfO1+02fMLWWcBTDbTjiIYYRyKB/jInLQrH7E/ZGP4r6iZr3xfbQO3z3UAl1LlwaNyw==',
            'https://download.visualstudio.microsoft.com/download/pr/3253cfdf-4b6e-4753-bf1c-1e466fcdee53/6175bc30e14b5143a5170fa222139bbd4d068bb9449bb1f5148454dbc189bb64/Microsoft.VC.14.44.17.14.CA.Ext.Hostx64.Targetx64.base.vsix',
            'Microsoft.VC.14.44.17.14.CA.Ext.Hostx64.Targetx64.base,version=14.44.35214,chip=x64'),
    Package('Microsoft.VC.14.44.17.14.CRT.Headers.base',
            '14.44.35220',
            2116223,
            '852382a9aa73502b7849c1bcadfb603ba7175c4e8b60e6aba03c7de711d4ece5',
            'Sg2QDvMNcQeZ6p/kaIHB6q+G7J+jIDjVNCVEY57KbsFFVZAt76XRXVisAN64zLToy770wnpxRA7Zg8ywL1sbdw==',
            'https://download.visualstudio.microsoft.com/download/pr/c610cd8c-801b-44b8-a80a-82cc382aeb43/852382a9aa73502b7849c1bcadfb603ba7175c4e8b60e6aba03c7de711d4ece5/Microsoft.VC.14.44.17.14.CRT.Headers.base.vsix',
            'Microsoft.VC.14.44.17.14.CRT.Headers.base,version=14.44.35220'),
    Package('Microsoft.VC.14.44.17.14.CRT.x64.Desktop.base',
            '14.44.35226',
            50776097,
            'f01f701a7bcd9587a340898c851424f6a52bb913a70c185ff0d5bf0288c5831a',
            'NPBycjzXV3jqezZpJ7sIc7ii3IcA7rh8AG5cEtp91SZixskKohrn2M0NcwyPUNKNZFWcjYbf7hgXjAY01OjgWg==',
            'https://download.visualstudio.microsoft.com/download/pr/67cf767c-5e71-47c2-a54a-cd5631e28942/f01f701a7bcd9587a340898c851424f6a52bb913a70c185ff0d5bf0288c5831a/Microsoft.VC.14.44.17.14.CRT.x64.Desktop.base.vsix',
            'Microsoft.VC.14.44.17.14.CRT.x64.Desktop.base,version=14.44.35226'),
    Package('Microsoft.VC.14.44.17.14.CRT.x64.Store.base',
            '14.44.35226',
            27596908,
            '9135b03c0df53c7a0aa9bef7230a1c2ff4263a0ee7baa7e419d034f484f6bb56',
            'l5Z0x+uDA4Lx5OGcsHFq11TW7YIjSecSBQgicjutXQ7NFDHwRneuqWmmVWxEErRO3u6kuImBNqiysdfkujl7ww==',
            'https://download.visualstudio.microsoft.com/download/pr/67cf767c-5e71-47c2-a54a-cd5631e28942/9135b03c0df53c7a0aa9bef7230a1c2ff4263a0ee7baa7e419d034f484f6bb56/Microsoft.VC.14.44.17.14.CRT.x64.Store.base.vsix',
            'Microsoft.VC.14.44.17.14.CRT.x64.Store.base,version=14.44.35226'),
    Package('Microsoft.VC.14.44.17.14.Premium.Tools.HostX64.TargetX64.base',
            '14.44.35219',
            240657,
            '4988bf20f7d6bb0bd6b78cc7d7131e97c9f8fe5779a8a2ed20b7d5ebc8ff9cb8',
            '8ztBiyHeGO7fnSMR6z2zzB6zQY2btb4xaFphidE42OEk3nMbn7DG6gVX/kGAlMb8Pgdf9ak9vrOof634acJXeQ==',
            'https://download.visualstudio.microsoft.com/download/pr/4d5e22ab-db0c-44ef-b284-9b0fa49ac89d/4988bf20f7d6bb0bd6b78cc7d7131e97c9f8fe5779a8a2ed20b7d5ebc8ff9cb8/Microsoft.VC.14.44.17.14.Premium.Tools.HostX64.TargetX64.base.vsix',
            'Microsoft.VC.14.44.17.14.Premium.Tools.HostX64.TargetX64.base,version=14.44.35219,chip=x64'),
    Package('Microsoft.VC.14.44.17.14.Tools.HostX64.TargetX64.base',
            '14.44.35228',
            26604670,
            'ee0baaa3a112d255f19f6c27dcc0ff6e496949eb9f1f37be0ac908c562a7076c',
            'zNXc5TfIy5YT/Im20QX4jE1Fnv1XBxsvg2GLDb6LIt4umXU4IFCf1yHxiXqvVmVXXPGfLCROmh+dYUfC/l3lkQ==',
            'https://download.visualstudio.microsoft.com/download/pr/bbc72d8e-2acd-4229-8f6a-85e23c5e3456/ee0baaa3a112d255f19f6c27dcc0ff6e496949eb9f1f37be0ac908c562a7076c/Microsoft.VC.14.44.17.14.Tools.HostX64.TargetX64.base.vsix',
            'Microsoft.VC.14.44.17.14.Tools.HostX64.TargetX64.base,version=14.44.35228,chip=x64'),
    Package('Microsoft.VC.14.44.17.14.CA.Ext.Hostx64.Targetx64.Res.base',
            '14.44.35214',
            96669,
            '19f5b4ff39ffae9dfdcc3511c2e021c85f47a3049e1ce7010bdf287808d2e4f8',
            'Tm1frE/+ePEFF+Q55Bv+F7EmTSbiRE75rRyfyAuz+kDLtK4FDvwwqHKgDTFbFy/09lXaCDPcsERTw64ZZ1Rz2w==',
            'https://download.visualstudio.microsoft.com/download/pr/3253cfdf-4b6e-4753-bf1c-1e466fcdee53/19f5b4ff39ffae9dfdcc3511c2e021c85f47a3049e1ce7010bdf287808d2e4f8/Microsoft.VC.14.44.17.14.CA.Ext.Hostx64.Targetx64.Res.base.enu.vsix',
            'Microsoft.VC.14.44.17.14.CA.Ext.Hostx64.Targetx64.Res.base,version=14.44.35214,chip=x64,language=en-US', archive_filename='Microsoft.VC.14.44.17.14.CA.Ext.Hostx64.Targetx64.Res.base.enu.vsix'),
    Package('Microsoft.VC.14.44.17.14.Tools.HostX64.TargetX64.Res.base',
            '14.44.35228',
            231770,
            '6e31f47833bfa585f56d55716a1ef081f1434f93ad77160eab49c6e193765832',
            'sdGOj3t4ymzt8LV8xxGIvsGvb6ED9iFVu6E9XwmeVg3f5G+zumJQqJq1yZDcweVV39hkOjDQuRtQM0sYRSiPJg==',
            'https://download.visualstudio.microsoft.com/download/pr/bbc72d8e-2acd-4229-8f6a-85e23c5e3456/6e31f47833bfa585f56d55716a1ef081f1434f93ad77160eab49c6e193765832/Microsoft.VC.14.44.17.14.Tools.HostX64.TargetX64.Res.base.enu.vsix',
            'Microsoft.VC.14.44.17.14.Tools.HostX64.TargetX64.Res.base,version=14.44.35228,chip=x64,language=en-US', archive_filename='Microsoft.VC.14.44.17.14.Tools.HostX64.TargetX64.Res.base.enu.vsix'),
)

# Compiler message DLLs are outside the original conservative top-level bin
# selection, but CL/LINK must load them. These complete English payload files
# are separately pinned; do not substitute a DLL or change VSLANG/approval.
ENGLISH_RESOURCE_PINS = (
    {'path': 'vc/bin/Hostx64/x64/1033/EnumIndexui.dll', 'bytes': 17464, 'sha256Bytes': 'bec1276abea214ccdfb780094195e701c2415c7a303b004dd5cc61c9bf60f70e'},
    {'path': 'vc/bin/Hostx64/x64/1033/VariantClearui.dll', 'bytes': 20040, 'sha256Bytes': 'd432d864fc7d6a7a79b88dcfee79bcc3573f7eb2a55edebeb488d86de6607067'},
    {'path': 'vc/bin/Hostx64/x64/1033/ConcurrencyCheckui.dll', 'bytes': 33824, 'sha256Bytes': '85c13e039c888b950cfd787c310649da6ae77f364ee9e930b8e020d33fcb2bfa'},
    {'path': 'vc/bin/Hostx64/x64/1033/CppCoreCheckui.dll', 'bytes': 93728, 'sha256Bytes': 'f7f809bfbbe51b2048830f545fb0f6f40a6760dcaaff869dd857f1e963ac3690'},
    {'path': 'vc/bin/Hostx64/x64/1033/SecurityChecksui.dll', 'bytes': 20024, 'sha256Bytes': '26179729be3cd12fa38792f5ada9126626a24452bf87de3e66b43eb3b5f2e6dc'},
    {'path': 'vc/bin/Hostx64/x64/1033/EspxEngineui.dll', 'bytes': 19008, 'sha256Bytes': 'f988b3dbb8fe31acbc939af5895f6d7b21b2e55734103da4c2b8539dcfd824bd'},
    {'path': 'vc/bin/Hostx64/x64/1033/HResultCheckui.dll', 'bytes': 15904, 'sha256Bytes': '965a958ab4b36024acfca0bbe27320b352ac64085a2cdcea4ae7189bfb2a05d4'},
    {'path': 'vc/bin/Hostx64/x64/1033/linkui.dll', 'bytes': 105784, 'sha256Bytes': 'dcc66f76def1feaeb4e95a33033f202cb6d81bb498e8b17e651a8b661c2ce7ab'},
    {'path': 'vc/bin/Hostx64/x64/1033/mspft140ui.dll', 'bytes': 83808, 'sha256Bytes': '8eae065d010f5be95644734e242db75f29e2cf54c57153a557a5d4a45da5c5eb'},
    {'path': 'vc/bin/Hostx64/x64/1033/bscmakeui.dll', 'bytes': 18744, 'sha256Bytes': '1e225cb38cb5e4fda79cf0649bccaf006c8407615a4ce2020e865428f3c09dce'},
    {'path': 'vc/bin/Hostx64/x64/1033/cvtresui.dll', 'bytes': 15672, 'sha256Bytes': '5dbb4d651096cd707ffb48d159d2161f1a35cecfb3a0e4b706a36a5636281448'},
    {'path': 'vc/bin/Hostx64/x64/1033/mspdbcmfui.dll', 'bytes': 17208, 'sha256Bytes': 'e41946b6faf871d89c3f15c1bc9dd91228c65745df7805346fd62f3ea89e1f8d'},
    {'path': 'vc/bin/Hostx64/x64/1033/atlprovui.dll', 'bytes': 35128, 'sha256Bytes': '8efa19aaa58e9cd54974e1f56183d6b541093e28420acae9e191e57e56cfe2ce'},
    {'path': 'vc/bin/Hostx64/x64/1033/clui.dll', 'bytes': 617272, 'sha256Bytes': '05c918973d5b4fd6042a9f7fc5bdcba4554296ee80a009f8877000c430b03b6b'},
    {'path': 'vc/bin/Hostx64/x64/1033/nmakeui.dll', 'bytes': 24416, 'sha256Bytes': 'edf9cd2a1d89101b41eaac390c98fb243b276c98a777105431119302b971a127'},
)


def mapped_path(name, kind):
    if kind != "vc":
        raise ValueError("Unsupported reviewed VC archive kind")
    if not name.startswith(ARCHIVE_PREFIX):
        return None
    relative = name[len(ARCHIVE_PREFIX):]
    # The CA payload spells HostX64 differently. Windows installs both into
    # this same directory; normalize this fixed alias for portable verification.
    if relative.startswith("bin/HostX64/x64/"):
        relative = "bin/Hostx64/x64/" + relative[len("bin/HostX64/x64/"):]
    return relative or None


def vc_pins(approval_path):
    if SDK.sha256(approval_path) != APPROVAL_SHA256:
        raise ValueError("Native approval changed; review the VC provisioner")
    approval = json.loads(approval_path.read_bytes())
    if approval["msvcVersion"] != MSVC_VERSION:
        raise ValueError("Native approval has a different MSVC directory version")
    rows = []
    names = set()
    for category in ("transitiveHeaders", "searchedLibrariesConservativePins", "compilerBinDirectoryConservativePins"):
        for row in approval[category]:
            if not row["path"].startswith("vc/"):
                continue
            path = SDK.checked_path(row["path"][3:])
            key = str(path).casefold()
            if key in names or type(row["bytes"]) is not int or row["bytes"] < 0:
                raise ValueError("Invalid VC approval rows")
            names.add(key)
            rows.append(row)
    bin_rows = approval["compilerBinDirectoryConservativePins"]
    for row in bin_rows:
        pure = SDK.checked_path(row["path"])
        if (pure.parts[:4] != ("vc", "bin", "Hostx64", "x64") or len(pure.parts) != 5
                or pure.suffix.casefold() not in (".exe", ".dll")):
            raise ValueError("Unexpected conservative compiler-bin approval scope")
    if not rows or not bin_rows:
        raise ValueError("Native approval has no VC or compiler inputs")
    return rows, bin_rows


def verify_vc(vc_root, rows, bin_rows):
    vc_root = SDK.file_path(vc_root.resolve(strict=True))
    for row in rows:
        pure = SDK.checked_path(row["path"][3:])
        target = vc_root.joinpath(*pure.parts).resolve(strict=True)
        if (not target.is_relative_to(vc_root.resolve()) or not target.is_file()
                or target.stat().st_size != row["bytes"] or SDK.sha256(target) != row["sha256Bytes"]):
            raise ValueError("Official VC bytes differ from native approval: " + row["path"])
    # Match the controlled compiler's exact conservative selection, including
    # unreferenced direct DLL/EXE files. PDB/config and subdirectory payloads are
    # original archive bytes but are outside that existing compiler selection.
    bin_root = vc_root / "bin/Hostx64/x64"
    actual = {"vc/bin/Hostx64/x64/" + item.name for item in bin_root.iterdir()
              if item.is_file() and item.suffix.casefold() in (".exe", ".dll")}
    expected = {row["path"] for row in bin_rows}
    if {s.casefold() for s in actual} != {s.casefold() for s in expected}:
        raise ValueError("Official VC conservative compiler-bin set differs from approval")


def verify_english_resources(vc_root):
    vc_root = SDK.file_path(vc_root.resolve(strict=True))
    for row in ENGLISH_RESOURCE_PINS:
        pure = SDK.checked_path(row["path"][3:])
        target = vc_root.joinpath(*pure.parts).resolve(strict=True)
        if (not target.is_relative_to(vc_root.resolve()) or not target.is_file()
                or target.stat().st_size != row["bytes"] or SDK.sha256(target) != row["sha256Bytes"]):
            raise ValueError("Official English compiler resource bytes differ: " + row["path"])
    resources = vc_root / "bin/Hostx64/x64/1033"
    actual = {"vc/bin/Hostx64/x64/1033/" + item.name for item in resources.iterdir() if item.is_file()}
    expected = {row["path"] for row in ENGLISH_RESOURCE_PINS}
    if {s.casefold() for s in actual} != {s.casefold() for s in expected}:
        raise ValueError("Official English compiler resource set differs")


def prepare(repo, temp_root, output, *, cache_root=None, resource_cache_root=None):
    repo = repo.resolve(strict=True)
    temp_root = temp_root.resolve(strict=True)
    output = output.resolve()
    if (not temp_root.is_dir() or output.parent != temp_root or output.exists()
            or output.is_relative_to(repo)):
        raise ValueError("VC output must be a new direct child of an external temporary root")
    runner_temp = os.environ.get("RUNNER_TEMP")
    if runner_temp and temp_root != Path(runner_temp).resolve(strict=True):
        raise ValueError("CI VC temporary root differs from RUNNER_TEMP")
    for fixed in ("C:/Windows", "C:/Program Files", "C:/Program Files (x86)"):
        if output.is_relative_to(Path(fixed).resolve()):
            raise ValueError("VC provisioning must not change a system installation")
    approval_path = repo / "desktop/native/diagnostic-share/approved-development-build.json"
    rows, bin_rows = vc_pins(approval_path)
    stage = temp_root / ("." + output.name + "-incomplete-" + str(uuid.uuid4()))
    SDK.file_path(stage).mkdir()
    vc = stage / "vc"
    SDK.file_path(vc).mkdir()
    records = []
    if cache_root is not None:
        cache_root = cache_root.resolve(strict=True)
    if resource_cache_root is not None:
        resource_cache_root = resource_cache_root.resolve(strict=True)
    for package in PACKAGES:
        archive = stage / package.filename
        if cache_root is None or (package.archive_filename is not None and resource_cache_root is None):
            SDK.download_package(package, archive)
        else:
            # Private offline verification may read the installed VS cache,
            # but it has no trust exemption: the complete length, SHA256 and
            # SHA512 are rechecked by the same formal extraction function.
            source_root = resource_cache_root if package.archive_filename is not None else cache_root
            source_path = source_root / package.filename if package.archive_filename is not None else source_root / package.cache_folder / "payload.vsix"
            source = source_path.resolve(strict=True)
            if not source.is_relative_to(source_root) or not source.is_file():
                raise ValueError("VC cached payload escaped its read-only cache root")
            with SDK.file_path(source).open("rb") as reader, SDK.file_path(archive).open("xb") as writer:
                shutil.copyfileobj(reader, writer, 1024 * 1024)
        record = dict(package=package.name, version=package.version, url=package.url,
                      packageBytes=package.bytes, packageSha256=package.sha256_hex,
                      packageSha512Base64=package.sha512_base64, archive=str(archive),
                      **SDK.extract_package(archive, package, vc, path_mapper=mapped_path))
        records.append(record)
    verify_vc(vc, rows, bin_rows)
    verify_english_resources(vc)
    if SDK.sha256(approval_path) != APPROVAL_SHA256:
        raise ValueError("Native approval changed while provisioning VC")
    receipt = dict(passed=True, vcRoot=str(output), msvcVersion=MSVC_VERSION,
                   approvedManifestSha256=APPROVAL_SHA256, approvedVcInputs=len(rows),
                   approvedCompilerBinInputs=len(bin_rows), vcInputsExact=True,
                   compilerBinSetExact=True, englishResourcesExact=True,
                   pinnedEnglishResourceInputs=len(ENGLISH_RESOURCE_PINS), packages=records,
                   payloadOrigin=("officialDownload" if cache_root is None else
                                  "verifiedVsInstallerCacheAndEnglishArchives" if resource_cache_root is not None else
                                  "verifiedVsInstallerPayloadCache"),
                   installedSystemToolchainChanged=False, compilerExecuted=False,
                   freshProducerGraphAndDllVerificationStillRequired=True)
    SDK.file_path(vc / "vc-provision.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    if output.exists():
        raise ValueError("VC output appeared while provisioning")
    SDK.file_path(vc).rename(SDK.file_path(output))
    return receipt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--temp-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.repo, args.temp_root, args.output)))


if __name__ == "__main__":
    main()
