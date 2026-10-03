#!/usr/bin/env python3
"""Original search contracts and policies, with only Room storage bound to Windows."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source
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
    BASE + "feature/search/SearchVideoFilterPolicy.kt": "direct",
    BASE + "data/model/entity/SearchHistory.kt": "platform-rewrite",
    BASE + "core/database/dao/SearchHistoryDao.kt": "platform-rewrite",
}


def inventory(repo: Path) -> list[dict]:
    return [{"path": path, "mode": mode, "features": ["search"],
             "sha256": hashlib.sha256((_desktop_canonical_source(repo, path)).read_text(encoding="utf-8").encode("utf-8")).hexdigest()}
            for path, mode in SOURCES.items()]


def generate(repo: Path, output: Path) -> None:
    spec = importlib.util.spec_from_file_location("search_parser", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    relative = BASE + "data/repository/SearchRepository.kt"
    source = (_desktop_canonical_source(repo, relative)).read_text(encoding="utf-8")
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
    end = video.index("            com.android.purebilibili.core.network.CoreDataLog.d(", begin)
    params = textwrap.dedent(video[begin:end]).rstrip()
    pieces.append("internal fun desktopVideoSearchParams(keyword: String, order: SearchOrder, duration: SearchDuration, tids: Int, page: Int, pubBegin: Long?, pubEnd: Long?): Map<String, String> {\n" + textwrap.indent(params, "    ") + "\n    return params\n}")
    target = output / "com/android/purebilibili/data/repository/DesktopSearchDeclarations.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n\n".join(pieces) + "\n", encoding="utf-8", newline="\n")
    # The import matcher consumes the complete original video-search protocol.
    # Its APIs are REQUIRED views of one captured Root request, never globals.
    names = ("search", "signWithWbi", "createSearchError")
    selected = "\n\n".join(declaration("fun", name) for name in names)
    original_selected = selected
    adaptations = []

    def guard(before: str, after: str) -> None:
        nonlocal selected
        assert selected.count(before) == 1, before
        index = selected.index(before)
        adaptations.append({"index": index, "before": before, "after": after})
        selected = selected[:index] + after + selected[index + len(before):]

    guard("    try {\n        val params = mutableMapOf(",
          "    try {\n        checkCurrent()\n        val params = mutableMapOf(")
    guard("        val response = api.search(signedParams)",
          "        checkCurrent()\n        val response = api.search(signedParams)\n        checkCurrent()")
    guard("        val navResp = navApi.getNavInfo()",
          "        checkCurrent()\n        val navResp = navApi.getNavInfo()\n        checkCurrent()")
    guard('        com.android.purebilibili.core.network.CoreDataLog.e("SearchRepo", "search(video) failed", e)',
          '        checkCurrent()\n        com.android.purebilibili.core.network.CoreDataLog.e("SearchRepo", "search(video) failed", e)')
    # Missing/failing nav keeps the original unsigned fallback for a live request.
    guard("        com.android.purebilibili.core.network.CoreDataLog.e(\n",
          "        checkCurrent()\n        com.android.purebilibili.core.network.CoreDataLog.e(\n")
    # The original shared-data logger is the existing desktop JVM log leaf.
    log_before = "com.android.purebilibili.core.network.CoreDataLog."
    log_after = "com.android.purebilibili.core.util.Logger."
    log_count = selected.count(log_before)
    assert log_count > 0 and log_after not in selected
    for _ in range(log_count):
        index = selected.index(log_before)
        adaptations.append({"index": index, "before": log_before, "after": log_after})
        selected = selected[:index] + log_after + selected[index + len(log_before):]
    restored = selected
    for change in reversed(adaptations):
        index = change["index"]
        assert restored[index:index + len(change["after"])] == change["after"]
        restored = restored[:index] + change["before"] + restored[index + len(change["after"]):]
    assert restored == original_selected
    header = """package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.repository.SearchRepository.SearchPageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
internal class DesktopOriginalCapturedVideoSearch(
    private val api: SearchApi,
    private val navApi: BilibiliApi,
    private val checkCurrent: () -> Unit,
) {
"""
    search_target = target.parent / "DesktopOriginalCapturedVideoSearch.kt"
    search_target.write_text(header + textwrap.indent(selected, "    ") + "\n}\n", encoding="utf-8", newline="\n")
    (output / "captured-video-search-selection.json").write_text(json.dumps({
        "original": relative, "sourceSHA256LF": hashlib.sha256(source.encode()).hexdigest(),
        "declarations": names, "originalSelectedSHA256LF": hashlib.sha256(original_selected.encode()).hexdigest(),
        "generatedSelectedSHA256LF": hashlib.sha256(selected.encode()).hexdigest(),
        "adaptations": adaptations, "exactSelectedBodyInverse": True,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    for path in (BASE + "data/model/entity/SearchHistory.kt", BASE + "core/database/dao/SearchHistoryDao.kt"):
        original = (_desktop_canonical_source(repo, path)).read_text(encoding="utf-8")
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
