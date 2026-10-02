"""Pin final Root spans that message hunks must preserve byte-for-byte (LF)."""
from pathlib import Path
import hashlib, json, os, sys
sys.dont_write_bytecode = True
P = Path(__file__).resolve().parent
def wide(path):
    return Path("\\\\?\\" + os.path.abspath(path))
def text(path):
    return wide(path).read_bytes().replace(b"\r\n", b"\n").decode("utf8")
def digest(value):
    return hashlib.sha256(value.encode("utf8")).hexdigest()
target = "desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt"
before = text(P / "baseline" / target)
after = text(P / "prospective" / target)
start = "                    { environment, content ->\n"
end = "                    }, discovery,community,pluginRuntime,dynamicCardSession,\n"
assert before.count(start) == after.count(start) == 1
assert before.count(end) == after.count(end) == 1
def span(source):
    return source[source.index(start):source.index(end) + len(end)]
original = span(before)
assert span(after) == original
assert "LocalDesktopImagePreviewShareBindings provides environment.gallery.imageShare" in original
assert original.index("LocalDesktopImagePreviewShareBindings") < original.index("DesktopOriginalCommentRootBindings")
begin = "                            entryKey is BiliPaiNavKey.Bangumi || entryKey is BiliPaiNavKey.BangumiDetail || entryKey is BiliPaiNavKey.BangumiReview ->\n"
finish = "                                    replaceSeason = { current, season -> messageRoutes.replaceBangumiDetail(current, season) }) }\n"
def bangumi(source):
    assert source.count(begin) == source.count(finish) == 1
    return source[source.index(begin):source.index(finish) + len(finish)]
assert bangumi(after) == bangumi(before)
contract = json.loads(text(P / "root-install-contract.json"))
report = dict(passed=True, candidateBase=contract["candidateBase"],
              target=target, normalized="LF", wholeVideoWindowContentSpanSha256=digest(original),
              bangumiOriginalRootLeafSpanSha256=digest(bangumi(before)),
              galleryProviderBeforeCommentWrapper=True, productionWrites=0,
              currentRootWindowAcceptance=False)
wide(P / "protected-consumers.json").write_text(json.dumps(report, indent=2), encoding="utf8")
print(json.dumps(report, indent=2))
