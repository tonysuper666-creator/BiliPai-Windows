#!/usr/bin/env python3
"""Extract upstream login declarations; only Android platform bindings are replaced."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source
import argparse
import importlib.util
import textwrap
from pathlib import Path


def generate(repo: Path, output: Path) -> None:
    spec = importlib.util.spec_from_file_location("login_kotlin_parser", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    declarations = [
        ("app/src/main/java/com/android/purebilibili/feature/login/CaptchaManager.kt", "com.android.purebilibili.feature.login",
         [("object", "RsaEncryption", False)],
         "import com.bilipai.desktop.data.DesktopLoginBase64 as Base64\nimport java.security.KeyFactory\nimport java.security.spec.X509EncodedKeySpec\nimport javax.crypto.Cipher"),
        ('core-data/src/main/java/com/android/purebilibili/core/store/AccountSessionStore.kt', "com.android.purebilibili.core.store",
         [("class", "StoredAccountSession", True), ("class", "AccountSessionSnapshot", True)],
         "import kotlinx.serialization.Serializable\nimport com.android.purebilibili.core.network.DesktopTokenPlatform as TokenManager"),
    ]
    for relative, package, selections, imports in declarations:
        source = (_desktop_canonical_source(repo, relative)).read_text(encoding="utf-8")
        tokens = parser.kotlin_tokens(source)
        pieces = ["// GENERATED from " + relative + "; declaration body unchanged.", "package " + package, imports]
        for kind, name, constructor_only in selections:
            start, end = parser.kotlin_structure(tokens, kind, name, constructor_only=constructor_only)
            begin = source.rfind("\n", 0, tokens[start][1]) + 1
            declaration = source[begin:tokens[end][2]]
            if name == "StoredAccountSession":
                declaration = "@Serializable\n" + declaration
            pieces.append(declaration)
        target = output / (package.replace(".", "/") + "/DesktopUpstreamLoginDeclarations.kt")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n\n".join(pieces) + "\n", encoding="utf-8")
    api_path = 'core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt'
    source = (_desktop_canonical_source(repo, api_path)).read_text(encoding="utf-8")
    tokens = parser.kotlin_tokens(source)
    positions = [i for i, token in enumerate(tokens[:-1])
                 if token[0] == "fun" and tokens[i + 1][0] == "resolveAndroidHdLoginAppKeyHeader"]
    if len(positions) != 1:
        raise ValueError("Missing or duplicate upstream Passport header policy")
    start = positions[0]
    end = start + 2
    while tokens[end][0] != "(":
        end += 1
    depth = 1
    while depth:
        end += 1
        depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
    while tokens[end][0] != "{":
        end += 1
    depth = 1
    while depth:
        end += 1
        depth += (tokens[end][0] == "{") - (tokens[end][0] == "}")
    begin = source.rfind("\n", 0, tokens[start][1]) + 1
    target = output / "com/android/purebilibili/core/network/DesktopPassportRequestPolicy.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("// GENERATED verbatim from " + api_path + "\npackage com.android.purebilibili.core.network\n\n" +
        textwrap.dedent(source[begin:tokens[end][2]]) + "\n", encoding="utf-8")


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path, required=True)
    args = cli.parse_args()
    generate(args.repo.resolve(), args.output.resolve())
