"""Copy the complete fixed v029 BAS grammar/timeline and original pure tests.

This is an explicit source slice, not a canonical-baseline update. It supplies no
Windows painter, button routing, or special-file lifetime index.
"""
from pathlib import Path
import argparse
import hashlib
import json

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
ARCHIVE = Path("desktop/upstream-slices/v029-bas-core")
MANIFEST_SHA256 = "eddd842bbd9817f24a557b60dd0a52356098d7d14d25324f44287386cf85aeae"
PINS = {
    "BasEasing.kt": "9ca169cf7d22d3d1f375baab5452b27f7b5dac005ad4c6a407d4bb3619567875",
    "BasLexer.kt": "ef3062ce0429438d107ad764724f18ee68a84eee036ec9c5116cb93fec86e8b4",
    "BasModels.kt": "42adfe867013ac8648e7e52b9d2e19e1275514d367954b24d6a84e05a55ac09d",
    "BasScriptParser.kt": "4938b7f94033191da01d87371f245d601f3371ef039d3a757fe8d04098916357",
    "BasTimeline.kt": "1eb0fb616eaa067e98429293aa610c151baef3ee71ec4b3f79bcb28e98eb269a",
    "BasDurationTest.kt": "5bf2ffad1b73d91f79beec2539cb3a18c7e46513f89eb81a5b7626c040c45f12",
    "BasLexerTest.kt": "367e65e3e7845c225c8bd15dc3eb8c6e28753c0ed8213094d9e4894fc90a0414",
    "BasPathFrameTest.kt": "32b60ad4e1e57ef7a7884bf0755f2171ebef781a1308c598d112841ff4f4df32",
    "BasScriptParserTest.kt": "2c3116f805433a28086e647b7f8fe7da164f953bc369f14ac82f87812129be49",
    "BasTimelineTest.kt": "1d01aa4cc221da4ac8a24ae6397e2a30b8fa94c96933c72a08fa4e51e6be7f3b",
}
PACKAGE = Path("com/android/purebilibili/danmaku/parser/bas")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def checked_inputs(repo):
    root = Path(repo).resolve() / ARCHIVE
    manifest_path = root / "manifest.json"
    if root.is_symlink() or manifest_path.is_symlink():
        raise ValueError("BAS archive must use regular original files")
    manifest_raw = manifest_path.read_bytes()
    if digest(manifest_raw) != MANIFEST_SHA256:
        raise ValueError("Fixed BAS manifest bytes changed")
    manifest = json.loads(manifest_raw)
    rows = manifest["files"]
    if manifest["fixedUpstreamCommit"] != COMMIT or len(rows) != 10 or {r["archiveFile"] for r in rows} != set(PINS):
        raise ValueError("Unknown BAS original-source set")
    originals = {}
    for row in rows:
        name = row["archiveFile"]
        original_path = "danmaku-engine/src/" + ("test" if row["testSource"] else "main") + "/java/" + PACKAGE.as_posix() + "/" + name
        if row["originalPath"] != original_path or row["sha256Bytes"] != PINS[name]:
            raise ValueError("BAS original identity changed: " + name)
        path = root / name
        if path.is_symlink() or not path.is_file() or path.resolve().parent != root.resolve():
            raise ValueError("BAS source must be a regular archive child: " + name)
        raw = path.read_bytes()
        blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        if len(raw) != row["bytes"] or digest(raw) != PINS[name] or blob != row["gitBlob"]:
            raise ValueError("Pinned BAS original bytes changed: " + name)
        raw.decode("utf-8")  # Fail before publishing any output on malformed UTF-8.
        originals[name] = (row, raw)
    return originals


def validate_output(root, expected):
    root = Path(root)
    if root.is_symlink():
        raise ValueError("BAS output root is a symlink")
    for path in root.rglob("*.kt") if root.exists() else ():
        relative = path.relative_to(root).as_posix()
        if relative not in expected or path.is_symlink() or path.read_bytes() != expected[relative]:
            raise ValueError("Unknown or changed BAS output is protected: " + relative)
    for relative in expected:
        path = root / relative
        for parent in (path, *path.parents):
            if parent.is_symlink():
                raise ValueError("BAS output path traverses a symlink")
            if parent == root:
                break


def generate(repo, output, tests_output):
    originals = checked_inputs(repo)
    main, tests = Path(output).absolute(), Path(tests_output).absolute()
    if main.resolve().is_relative_to(tests.resolve()) or tests.resolve().is_relative_to(main.resolve()):
        raise ValueError("BAS main and test output roots must be separate")
    archive = (Path(repo).resolve() / ARCHIVE).resolve()
    for root in (main, tests):
        if root.resolve().is_relative_to(archive) or archive.is_relative_to(root.resolve()):
            raise ValueError("BAS output overlaps original inputs")
    expected = {False: {}, True: {}}
    for name, (row, raw) in originals.items():
        expected[row["testSource"]][(PACKAGE / name).as_posix()] = raw
    validate_output(main, expected[False])
    validate_output(tests, expected[True])
    paths = []
    proof = []
    for name, (row, raw) in originals.items():
        root = tests if row["testSource"] else main
        path = root / PACKAGE / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)  # Zero adaptation: complete original file remains byte-identical.
        paths.append(path)
        proof.append(dict(source=row["originalPath"], originalGitBlob=row["gitBlob"],
            originalRawSha256=PINS[name], generatedSha256Bytes=digest(raw),
            generatedPath=(PACKAGE / name).as_posix(), testSource=row["testSource"], changes=[]))
    receipt = dict(fixedUpstreamCommit=COMMIT, files=proof, originalTests=37,
        wholeOriginalByteIdentity=True, overallCanonicalBaselineAdvanced=False,
        compiled=False, actualMainRendered=False, windowsBasPainterImplemented=False)
    (main / "source-proof.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return paths


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tests-output", type=Path, required=True)
    args = parser.parse_args()
    for path in generate(args.repo, args.output, args.tests_output):
        print(path)
