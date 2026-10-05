"""Complete fixed-v029 Empty/Error declarations for their existing sole producers.

No lifecycle/renderer implementation, duplicate Kotlin producer, or preference authority.
The helpers are emitted only by Home; BGM retains its existing legacy loader declarations.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
ROOT = Path(__file__).resolve().parents[1] / "upstream-slices/v029-brand-consumers"
MANIFEST_SHA256 = "13be214a20d3949d168499c5326aa1e7fbaa9f828dc28323da2794fc5411758e"
BASE = "app/src/main/java/com/android/purebilibili/core/ui/"
LOTTIE = BASE + "LottieComponents.kt"
ILLUSTRATION = BASE + "BlueSnowMaidAnimation.kt"
VIEWPORT = BASE + "MaidStateViewport.kt"
PACKAGE_PATH = "com/android/purebilibili/core/ui/"
HEADER = """// Complete original fixed-v029 declaration; lifecycle/renderer are required platform consumers.
package com.android.purebilibili.core.ui
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

"""


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def wide(path: Path) -> Path:
    value = str(path.absolute())
    return Path("\\\\?\\" + value) if os.name == "nt" and not value.startswith("\\\\?\\") else path


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate manifest JSON key: " + key)
        result[key] = value
    return result


def checked_sources(root: Path | None = None) -> tuple[dict, dict[str, str]]:
    root = root or ROOT
    manifest_bytes = wide(root / "manifest.json").read_bytes()
    if digest(manifest_bytes) != MANIFEST_SHA256:
        raise ValueError("Fixed brand-consumer manifest identity mismatch")
    manifest = json.loads(manifest_bytes, object_pairs_hook=strict_object)
    rows = manifest["files"]
    if manifest["schemaVersion"] != 1 or manifest["fixedUpstreamCommit"] != COMMIT or manifest["hashNormalization"] != "raw" or {row["path"] for row in rows} != {LOTTIE, ILLUSTRATION, VIEWPORT} or len(rows) != 3:
        raise ValueError("Incomplete fixed brand-consumer source contract")
    actual = {Path(directory, name).relative_to(wide(root)).as_posix() for directory, _, names in os.walk(wide(root)) for name in names}
    if actual != {LOTTIE, ILLUSTRATION, VIEWPORT, "manifest.json"}:
        raise ValueError("Unknown/missing brand-consumer source file")
    sources = {}
    for row in rows:
        data = wide(root / row["path"]).read_bytes()
        blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
        if row["commit"] != COMMIT or row["upstreamUrl"] != f'https://raw.githubusercontent.com/jay3-yy/BiliPai/{COMMIT}/{row["path"]}' or row["bytes"] != len(data) or row["sha256Bytes"] != digest(data) or row["gitBlob"] != blob:
            raise ValueError("Fixed brand-consumer raw/blob mismatch: " + row["path"])
        sources[row["path"]] = data.decode("utf-8")
    return manifest, sources


def declaration_bounds(raw: str, name: str) -> tuple[int, int]:
    # Fixed complete original bodies; lexical tokens keep braces in comments/strings harmless.
    matches = list(re.finditer(r"(?m)^@Composable\n(?:internal )?fun " + re.escape(name) + r"\(", raw))
    if len(matches) != 1:
        raise ValueError("Original declaration count mismatch: " + name)
    start = matches[0].start()
    token = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|[(){}]')
    parens = 0
    braces = 0
    body = False
    for match in token.finditer(raw, matches[0].end() - 1):
        value = match.group()
        if len(value) != 1:
            continue
        if not body:
            parens += (value == "(") - (value == ")")
            if value == "{" and parens == 0:
                body = True
                braces = 1
        else:
            braces += (value == "{") - (value == "}")
            if braces == 0:
                return start, match.end()
    raise ValueError("Original declaration is incomplete: " + name)


def adapt(raw: str, name: str, prefix: str, suffix: str) -> tuple[str, dict]:
    start, end = declaration_bounds(raw, name)
    selected = raw[start:end]
    edits = []
    body = raw
    for index, before, after, label in (
        (0, raw[:start], prefix, "platform import/selected declaration prefix"),
        (len(prefix) + len(selected), raw[end:], suffix, "excluded original declarations retained raw; sole output suffix"),
    ):
        if body[index:index + len(before)] != before:
            raise ValueError("Counted brand-consumer adaptation mismatch")
        edits.append(dict(index=index, before=before, after=after, occurrences=1, label=label))
        body = body[:index] + after + body[index + len(before):]
    inverse = body
    for edit in reversed(edits):
        index, after = edit["index"], edit["after"]
        if inverse[index:index + len(after)] != after:
            raise ValueError("Brand-consumer inverse mismatch")
        inverse = inverse[:index] + edit["before"] + inverse[index + len(after):]
    if inverse != raw or body[len(prefix):len(prefix) + len(selected)] != selected:
        raise ValueError("Original consumer body/full source inverse changed")
    return body, dict(declaration=name, completeOriginalBody=True, originalSha256Bytes=digest(raw.encode()),
                     selectedOriginalSha256Bytes=digest(selected.encode()), generatedSha256Bytes=digest(body.encode()),
                     exactFullSourceInverse=True, countedAdaptations=edits)


def error_consumer() -> tuple[str, dict]:
    manifest, originals = checked_sources()
    body, audit = adapt(originals[LOTTIE], "ErrorState", HEADER, "\n")
    audit.update(source=LOTTIE, fixedUpstreamCommit=COMMIT, manifestSha256Bytes=MANIFEST_SHA256, originalFiles=manifest["files"])
    return body, audit


def shared_helpers() -> list[tuple[str, str, dict]]:
    manifest, originals = checked_sources()
    return [
        (PACKAGE_PATH + name, originals[path], dict(source=path, fixedUpstreamCommit=COMMIT,
          originalSha256Bytes=digest(originals[path].encode()), generatedSha256Bytes=digest(originals[path].encode()),
          completeOriginalBody=True, countedAdaptations=[], exactFullSourceInverse=True,
          manifestSha256Bytes=MANIFEST_SHA256, originalFiles=manifest["files"]))
        for path, name in ((VIEWPORT, "MaidStateViewport.kt"), (ILLUSTRATION, "DesktopOriginalMaidStateIllustration.kt"))
    ]


def empty_consumer(legacy: str) -> tuple[str, dict]:
    manifest, originals = checked_sources()
    constants = re.search(r"(?m)^object LottieUrls \{[\s\S]*?\n\}", legacy)
    if constants is None:
        raise ValueError("Original sole legacy LottieUrls object missing")
    start, end = declaration_bounds(legacy, "CutePersonLoadingIndicator")
    cute = legacy[start:end]
    body, audit = adapt(originals[LOTTIE], "EmptyState", HEADER + constants.group() + "\n\n", "\n\n" + cute + "\n")
    audit.update(source=LOTTIE, fixedUpstreamCommit=COMMIT, manifestSha256Bytes=MANIFEST_SHA256,
                 originalFiles=manifest["files"], legacySourceSha256LF=digest(legacy.encode()),
                 retainedLegacyDeclarations={"LottieUrls": digest(constants.group().encode()), "CutePersonLoadingIndicator": digest(cute.encode())})
    return body, audit
