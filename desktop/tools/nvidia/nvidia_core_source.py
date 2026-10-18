"""Shared read-only helpers for the NVIDIA auto-tier source checks.

The checks read Kotlin source and the shared golden vectors as data. They never re-implement the
Kotlin classification or budget logic: a drift between source and vectors is caught by the JVM
tests, and these helpers only make the data and the source shape inspectable."""
import json
import re
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


def read(path):
    data = Path(path).read_bytes()
    if b"\r" in data:
        raise AssertionError("%s contains a carriage return" % path)
    return data.decode("utf-8")


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
