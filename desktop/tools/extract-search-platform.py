#!/usr/bin/env python3
"""Original search contracts and policies, with only Room storage bound to Windows."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import textwrap

BASE = "app/src/main/java/com/android/purebilibili/"
SOURCES = {
    BASE + "data/repository/SearchRepository.kt": "policy-extract",
    BASE + "data/repository/SearchLoadPolicy.kt": "direct",
    BASE + "data/repository/SearchRecommendPolicy.kt": "direct",
    BASE + "feature/search/SearchVideoFilterPolicy.kt": "direct",
    BASE + "data/model/entity/SearchHistory.kt": "platform-rewrite",
    BASE + "core/database/dao/SearchHistoryDao.kt": "platform-rewrite",
}


def inventory(repo: Path) -> list[dict]:
    return [{"path": path, "mode": mode, "features": ["search"],
             "sha256": hashlib.sha256((repo / path).read_text(encoding="utf-8").encode("utf-8")).hexdigest()}
            for path, mode in SOURCES.items()]


def generate(repo: Path, output: Path) -> None:
    spec = importlib.util.spec_from_file_location("search_parser", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    relative = BASE + "data/repository/SearchRepository.kt"
    source = (repo / relative).read_text(encoding="utf-8")
    tokens = parser.kotlin_tokens(source)

    def declaration(kind: str, name: str) -> str:
        found = [i for i, token in enumerate(tokens[:-1]) if token[0] == kind and tokens[i + 1][0] == name]
        if len(found) != 1:
            raise ValueError("Missing or duplicate original search declaration: " + name)
        start = found[0]
        end = start
        while tokens[end][0] != "(":
            end += 1
        depth = 1
        while depth:
            end += 1
            depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
        if tokens[end + 1][0] == "{" or kind == "fun":
            while tokens[end][0] != "{":
                end += 1
            depth = 1
            while depth:
                end += 1
                depth += (tokens[end][0] == "{") - (tokens[end][0] == "}")
        begin = source.rfind("\n", 0, tokens[start][1]) + 1
        return textwrap.dedent(source[begin:tokens[end][2]])

    pieces = ["// GENERATED verbatim from " + relative,
              "package com.android.purebilibili.data.repository",
              "import com.android.purebilibili.data.model.response.HotItem",
              declaration("class", "SearchTrendingBundle")]
    for name in ("SearchOrder", "SearchArticleCategory", "SearchPhotoCategory", "SearchDuration", "SearchUpOrder", "SearchOrderSort", "SearchUserType", "SearchLiveOrder"):
        pieces.append(declaration("class", name))
    pieces.append("object SearchRepository {\n" + textwrap.indent(declaration("class", "SearchPageInfo"), "    ") + "\n" +
                  textwrap.indent(declaration("fun", "searchTypeParams"), "    ") + "\n" +
                  "    internal fun desktopSearchTypeParams(keyword: String, searchType: String, page: Int, extra: Map<String, String> = emptyMap()): Map<String, String> = searchTypeParams(keyword, searchType, page, extra)\n}")
    pieces.append("internal fun desktopSearchTypeParams(keyword: String, searchType: String, page: Int, extra: Map<String, String> = emptyMap()): Map<String, String> = SearchRepository.desktopSearchTypeParams(keyword, searchType, page, extra)")
    # This platform function is the exact original video parameter block, including omission of unset filters.
    video = source[source.index("    suspend fun search("):source.index("    suspend fun searchWithDurations(")]
    begin = video.index("            val params = mutableMapOf(")
    end = video.index("            com.android.purebilibili.core.util.Logger.d(", begin)
    params = textwrap.dedent(video[begin:end]).rstrip()
    pieces.append("internal fun desktopVideoSearchParams(keyword: String, order: SearchOrder, duration: SearchDuration, tids: Int, page: Int, pubBegin: Long?, pubEnd: Long?): Map<String, String> {\n" + textwrap.indent(params, "    ") + "\n    return params\n}")
    fallback = next(line.strip() for line in source.splitlines() if line.strip().startswith("val fallbackKeywords = listOf("))
    pieces.append("internal fun desktopSearchFallbackKeywords(): List<String> {\n    " + fallback + "\n    return fallbackKeywords\n}")
    target = output / "com/android/purebilibili/data/repository/DesktopSearchDeclarations.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n\n".join(pieces) + "\n", encoding="utf-8", newline="\n")
    for path in (BASE + "data/model/entity/SearchHistory.kt", BASE + "core/database/dao/SearchHistoryDao.kt"):
        original = (repo / path).read_text(encoding="utf-8")
        lines = [line for line in original.splitlines() if not line.startswith("import androidx.room")
                 and not line.strip().startswith(("@Entity", "@PrimaryKey", "@Dao", "@Query", "@Insert", "@Delete"))]
        package = next(line.split(" ", 1)[1] for line in lines if line.startswith("package "))
        target = output / Path(package.replace(".", "/")) / Path(path).name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("// GENERATED from " + path + "; Room annotations only removed.\n" + "\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main() -> None:
    args = argparse.ArgumentParser()
    args.add_argument("--repo", type=Path, required=True)
    args.add_argument("--output", type=Path)
    args.add_argument("--inventory", action="store_true")
    parsed = args.parse_args()
    if parsed.inventory:
        print(json.dumps(inventory(parsed.repo), ensure_ascii=False, indent=2))
    if parsed.output:
        generate(parsed.repo, parsed.output)
    if not parsed.inventory and not parsed.output:
        args.error("--inventory or --output is required")


if __name__ == "__main__":
    main()
