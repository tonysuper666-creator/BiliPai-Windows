"""Shared read-only helpers for the NVIDIA auto-tier source checks.

The checks read Kotlin source and the shared golden vectors as data. They never re-implement the
Kotlin classification or budget logic: a drift between source and vectors is caught by the JVM
tests, and these helpers only make the data and the source shape inspectable.

Line endings. read() normalises CRLF to LF, because a checkout with core.autocrlf=true holds CRLF
working copies of LF blobs. The guarantee that the committed blobs are LF is a separate check:
assert_lf_only() asks git for the index line-ending word of each file."""
import json
import re
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
KN = REPO / "desktop/src/main/kotlin/com/bilipai/desktop/nvidia"
ST = REPO / "desktop/src/test"
K = REPO / "desktop/src/main/kotlin/com/bilipai/desktop"

CAPABILITY = KN / "DesktopNvidiaGpuCapability.kt"
TIER_PROFILE = KN / "DesktopNvidiaTierProfile.kt"
ACTIVATION = KN / "DesktopNvidiaActivation.kt"
STATUS_TEXT = KN / "DesktopNvidiaStatusText.kt"
VECTORS = ST / "resources/nvidia-gpu-classification-vectors.json"

# Pure core files of slice S1. Later slices add their own files and their own checks.
S1_CORE_FILES = (CAPABILITY, TIER_PROFILE, ACTIVATION, STATUS_TEXT)


# The shader-list option name is a gate bit of the presentation proof, so no file of this work may spell it out.
# It is assembled here once; every check that needs the name imports this constant.
SHADER_LIST_OPTION = "glsl" + "-shaders"


def read(path):
    """UTF-8 text of a source file with CRLF normalised to LF, so a CRLF working copy reads like the LF blob."""
    return Path(path).read_bytes().replace(b"\r\n", b"\n").decode("utf-8")


def relative_posix(path):
    """Repository-relative path with forward slashes, as git prints it."""
    return Path(path).resolve().relative_to(REPO).as_posix()


def index_eol(paths):
    """{repo-relative path: index line-ending word} for the tracked files among paths, from git ls-files --eol.

    The word is one of lf, crlf, mixed, none or -text. Untracked files are absent from the result. Returns None
    when git cannot answer (no git, not a repository, a timeout), so the caller can fall back. Read-only."""
    names = [relative_posix(path) for path in paths]
    if not names:
        return {}
    try:
        completed = subprocess.run(
            ["git", "-c", "core.autocrlf=false", "ls-files", "--eol", "--"] + names,
            cwd=str(REPO), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    if completed.returncode != 0:
        return None
    words = {}
    for line in completed.stdout.decode("utf-8", "replace").splitlines():
        meta, tab, name = line.partition("\t")
        fields = meta.split()
        if tab and fields and fields[0].startswith("i/"):
            words[name] = fields[0][2:]
    return words


def assert_lf_only(test, *paths):
    """The committed blob of every file is LF (index word lf, or none for a file without any line break).

    A CRLF working copy is fine: that is what core.autocrlf=true makes of an LF blob. For a file that is not
    tracked yet, or when git cannot answer, the working copy must hold no carriage return outside a CRLF pair."""
    paths = [Path(path) for path in paths]
    words = index_eol(paths)
    for path in paths:
        name = relative_posix(path)
        word = None if words is None else words.get(name)
        if word is None:
            test.assertIsNone(re.search(rb"\r(?!\n)", path.read_bytes()), "%s holds a bare carriage return" % name)
        else:
            test.assertIn(word, ("lf", "none"), "%s is committed with %s line endings" % (name, word))


def load_vectors():
    return json.loads(read(VECTORS))


def marked_block(text, name):
    """The text between '// NAME-BEGIN' and '// NAME-END'; each marker must occur exactly once."""
    begin = "// %s-BEGIN" % name
    end = "// %s-END" % name
    if text.count(begin) != 1 or text.count(end) != 1:
        raise AssertionError("marker %s must occur exactly once" % name)
    start = text.index(begin)
    stop = text.index(end)
    if stop < start:
        raise AssertionError("marker %s is out of order" % name)
    return text[start + len(begin):stop]


def kotlin_value(raw):
    raw = raw.strip()
    if raw in ("true", "false"):
        return raw == "true"
    if raw == "null":
        return None
    if re.fullmatch(r"-?[\d_]+L?", raw):
        return int(raw.rstrip("L").replace("_", ""))
    if re.fullmatch(r"-?[\d_]+\.\d+", raw):
        return float(raw.replace("_", ""))
    return raw


def constructor_calls(text, constructor, first_key):
    """Argument dicts of every call 'constructor(first_key = ...' in the text (declarations are skipped)."""
    results = []
    for match in re.finditer(re.escape(constructor) + r"\(\s*" + re.escape(first_key) + r"\s*=", text):
        depth = 1
        index = match.end()
        start = text.index("(", match.start()) + 1
        while depth:
            char = text[index]
            depth += (char == "(") - (char == ")")
            index += 1
        body = text[start:index - 1]
        args = {}
        for key, raw in re.findall(r"(\w+)\s*=\s*([^,\n]+)", body):
            args[key] = kotlin_value(raw)
        results.append(args)
    return results


def tier_profiles():
    """TIER-TABLE rows keyed by tier name."""
    block = marked_block(read(TIER_PROFILE), "TIER-TABLE")
    rows = {}
    for args in constructor_calls(block, "NvidiaTierProfile", "tier"):
        rows[args["tier"].split(".")[-1]] = args
    return rows


def hdr_rate_factor():
    block = marked_block(read(TIER_PROFILE), "TIER-TABLE")
    match = re.search(r"const val HDR_RATE_FACTOR: Double = ([\d.]+)", block)
    if not match:
        raise AssertionError("HDR_RATE_FACTOR missing from the TIER-TABLE block")
    return float(match.group(1))


def sku_rows():
    """SKU rows of the GPU-CLASSIFY-TABLE block as dicts with plain class and generation names."""
    block = marked_block(read(CAPABILITY), "GPU-CLASSIFY-TABLE")
    rows = []
    for args in constructor_calls(block, "NvidiaSkuRow", "model"):
        rows.append({
            "model": args["model"],
            "ti": args["ti"],
            "generation": args["generation"].split(".")[-1],
            "class": args["skuClass"].split(".")[-1],
            "costFactor": args["costFactor"],
            "qualityCeilingOverride": args.get("qualityCeilingOverride"),
        })
    return rows


def pci_models():
    """PCI id table of the GPU-CLASSIFY-TABLE block keyed by device id."""
    block = marked_block(read(CAPABILITY), "GPU-CLASSIFY-TABLE")
    pattern = re.compile(
        r"NvidiaPciModel\(0x([0-9A-Fa-f]{4}), NvidiaGeneration\.(\w+), (\d+), ti = (true|false), mobile = (true|false)\)")
    table = {}
    for match in pattern.finditer(block):
        table[int(match.group(1), 16)] = {
            "generation": match.group(2),
            "model": int(match.group(3)),
            "ti": match.group(4) == "true",
            "mobile": match.group(5) == "true",
        }
    return table


def id_ranges():
    block = marked_block(read(CAPABILITY), "GPU-CLASSIFY-TABLE")
    pattern = re.compile(r"NvidiaIdRange\(0x([0-9A-Fa-f]{4}), 0x([0-9A-Fa-f]{4}), NvidiaGeneration\.(\w+)\)")
    return [(int(a, 16), int(b, 16), g) for a, b, g in pattern.findall(block)]


def lex_kotlin(text):
    """Splits Kotlin source into code (comments removed, string text kept) and string literal contents.

    Handles line and nested block comments, character literals and escaped quotes. Raw strings are
    not used in the checked files."""
    code = []
    literals = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        pair = text[index:index + 2]
        if pair == "//":
            while index < length and text[index] != "\n":
                index += 1
            continue
        if pair == "/*":
            depth = 1
            index += 2
            while index < length and depth:
                if text[index:index + 2] == "/*":
                    depth += 1
                    index += 2
                elif text[index:index + 2] == "*/":
                    depth -= 1
                    index += 2
                else:
                    index += 1
            if depth:
                raise AssertionError("unterminated block comment")
            continue
        if char == '"':
            start = index
            index += 1
            while index < length and text[index] != '"':
                index += 2 if text[index] == "\\" else 1
            if index >= length:
                raise AssertionError("unterminated string literal")
            literals.append(text[start + 1:index])
            code.append(text[start:index + 1])
            index += 1
            continue
        if char == "'":
            start = index
            index += 1
            while index < length and text[index] != "'":
                index += 2 if text[index] == "\\" else 1
            code.append(text[start:index + 1])
            index += 1
            continue
        code.append(char)
        index += 1
    return "".join(code), literals
