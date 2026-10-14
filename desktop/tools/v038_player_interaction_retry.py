"""Complete v038 retry function and VM methods, emitted by the existing owner."""
from pathlib import Path
import hashlib
import json

COMMIT = "b69ce4c21bd1bc2f33c5a4f3076b571f9b6d0f14"
ARCHIVE = Path("desktop/upstream-slices/v038-player-interaction-retry")
MANIFEST_SHA = "32358962a03ee93b3e2815f2e95476e34a7afb440d4ceb64ab2bd10bd0c8d70e"
PINS = {
    "VideoInteractionStatusRetry.kt": ("410d5611cfa6a43d606a615c477c2f2193425ead32a949886a374f691b591edf", 982, "3aa6430d123ea787debf94f0a7a9c8af30f387b7"),
    "VideoInteractionStatusRetryTest.kt": ("b317bfbf2c67b50ca42302cbb818e3f803f7da63241477c49ec63220730fe7b7", 2206, "50d5679c1ecfda65cd41315da869825e0b8acbb3"),
    "VideoPlaybackViewModel.kt": ("e90d0c24fab903ec25aafa9dc0b4ad04e3a2ddf91384696b5239409bba8a9ba6", 419262, "02b18fb56d1dbd6dbb75584d9fbff877c1bbaa43"),
}


def sha(raw):
    return hashlib.sha256(raw.encode("utf-8") if isinstance(raw, str) else raw).hexdigest()


def fixed_sources(repo=None):
    repo = Path(repo) if repo is not None else Path(__file__).resolve().parents[2]
    root = repo / ARCHIVE
    if root.is_symlink() or (root/"manifest.json").is_symlink():
        raise ValueError("Linked interaction source rejected")
    raw = (root/"manifest.json").read_bytes()
    if sha(raw) != MANIFEST_SHA:
        raise ValueError("Fixed interaction retry manifest changed")
    manifest = json.loads(raw)
    if manifest["schemaVersion"] != 1 or manifest["fixedUpstreamCommit"] != COMMIT:
        raise ValueError("Unknown interaction retry source identity")
    if {p.name for p in root.iterdir()} != set(PINS) | {"manifest.json"}:
        raise ValueError("Unexpected retry archive file set")
    rows = {r["filename"]: r for r in manifest["files"]}
    if len(rows) != len(manifest["files"]) or set(rows) != set(PINS):
        raise ValueError("Unexpected retry source set")
    result = {}
    for name, (digest, size, blob) in PINS.items():
        path = root/name
        if path.is_symlink():
            raise ValueError("Linked retry input rejected")
        raw = path.read_bytes()
        if (rows[name]["sha256Bytes"], rows[name]["bytes"], rows[name]["gitBlob"]) != (digest, size, blob):
            raise ValueError("Retry source pin changed")
        if len(raw) != size or sha(raw) != digest or hashlib.sha1(b"blob "+str(size).encode()+b"\0"+raw).hexdigest() != blob:
            raise ValueError("Retry source bytes changed")
        result[name] = raw.replace(b"\r\n", b"\n").decode("utf-8")
    return result


def emit(repo, output):
    sources = fixed_sources(repo)
    output = Path(output)
    body = sources["VideoInteractionStatusRetry.kt"]
    target = output/"com/android/purebilibili/feature/video/viewmodel/VideoInteractionStatusRetry.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(body.encode("utf-8"))
    original = sources["VideoInteractionStatusRetryTest.kt"]
    tests = original
    edits = []
    for before, after in [("import org.junit.Assert.assertEquals", "import kotlin.test.assertEquals"),
                          ("import org.junit.Assert.assertTrue", "import kotlin.test.assertTrue"),
                          ("import org.junit.Test", "import kotlin.test.Test")]:
        if tests.count(before) != 1:
            raise ValueError("Original retry test framework anchor changed")
        tests = tests.replace(before, after, 1)
        edits.append(dict(before=before, after=after))
    inverse = tests
    for edit in reversed(edits):
        if inverse.count(edit["after"]) != 1:
            raise ValueError("Retry test inverse anchor changed")
        inverse = inverse.replace(edit["after"], edit["before"], 1)
    if inverse != original:
        raise ValueError("Complete retry test inverse differs")
    # Separate sibling test root: these five original tests never enter main.
    test_root = output.parent/"original-video-interaction-retry-tests"
    test_path = test_root/"com/android/purebilibili/feature/video/viewmodel/VideoInteractionStatusRetryTest.kt"
    test_path.parent.mkdir(parents=True, exist_ok=True)
    test_path.write_bytes(tests.encode("utf-8"))
    proof = dict(upstreamCommit=COMMIT, completeOriginalRetryCopied=True,
                 originalRetrySha256LF=sha(body), originalTestSha256LF=sha(original),
                 generatedTestSha256LF=sha(tests), testAdaptations=edits,
                 completeOriginalTestInverse=True, originalCases=tests.count("@Test"),
                 emissionOwner="extract-upstream-video-full-owner.py", mainCompiled=False, runtimeExecuted=False)
    (output/"v038-interaction-retry-source-proof.json").write_bytes((json.dumps(proof,indent=2)+"\n").encode())
    return proof
