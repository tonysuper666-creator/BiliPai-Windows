"""Whole canonical CommentFraudHistoryScreen; only owned Windows platform leaves."""
from __future__ import annotations
import argparse, hashlib, importlib.util, json, os, re, sys
from pathlib import Path
sys.dont_write_bytecode = True
UPSTREAM = "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40"
CATALOG_SHA = "2fa53aa78cc27c76a3923cc44750128b3657c340dbcfe44ec13810cbc48becbc"
FEATURE = "desktop-whole-comment-fraud-history"
SCREEN = "app/src/main/java/com/android/purebilibili/feature/settings/screen/CommentFraudHistoryScreen.kt"
POLICIES = ["app/src/main/java/com/android/purebilibili/data/repository/CommentFraudRepository.kt", "app/src/main/java/com/android/purebilibili/core/database/entity/CommentFraudRecord.kt", "app/src/main/java/com/android/purebilibili/data/model/CommentFraudStatus.kt", "app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt", "app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt", "app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsPageScaffold.kt"]
SYMBOLS = ["ms_file_download_24", "ms_file_upload_24", "ms_delete_sweep_24", "ms_shield_24", "ms_keyboard_arrow_up_24", "ms_keyboard_arrow_down_24"]

def wide(path):
    value = str(Path(path).absolute())
    return Path("\\\\?\\" + value) if os.name == "nt" and not value.startswith("\\\\?\\") else Path(value)
def sha(raw): return hashlib.sha256(raw).hexdigest()
def emit(path, raw):
    wide(path.parent).mkdir(parents=True, exist_ok=True)
    wide(path).write_bytes(raw if isinstance(raw, bytes) else raw.encode("utf-8"))

class Original:
    def __init__(self, repo):
        self.repo = repo
        raw = wide(repo / "desktop/tools/v025-canonical-sources.json").read_bytes()
        if sha(raw) != CATALOG_SHA: raise ValueError("Canonical catalog digest changed")
        self.catalog = json.loads(raw)
        if self.catalog["upstreamCommit"] != UPSTREAM: raise ValueError("Canonical commit changed")
    def raw(self, path):
        if path not in self.catalog["paths"]: raise ValueError("Unknown canonical original: " + path)
        file = wide(self.repo / path)
        if file.is_symlink(): raise ValueError("Original is a link: " + path)
        raw = file.read_bytes()
        row = self.catalog["paths"][path]
        normalized = raw.replace(b"\r\n", b"\n") if row["hashNormalization"] == "lf" else raw
        if sha(normalized) != row["sha256"]: raise ValueError("Original digest mismatch: " + path)
        return raw
    def text(self, path): return self.raw(path).decode("utf-8").replace("\r\n", "\n")
    def row(self, path, mode=None):
        raw = self.raw(path)
        result = dict(path=path, sha256=sha(raw.replace(b"\r\n", b"\n")), features=[FEATURE])
        if mode is not None: result["mode"] = mode
        return result

def adapt(original):
    source = original
    changes = []
    def replace(before, after, count=1):
        nonlocal source
        if source.count(before) != count: raise ValueError("Leaf occurrence changed: " + before)
        start = 0
        for _ in range(count):
            offset = source.index(before, start)
            changes.append(dict(offset=offset, before=before, after=after))
            source = source[:offset] + after + source[offset+len(before):]
            start = offset + len(after)
    for line in ["import android.content.ClipData\n", "import android.content.ClipboardManager\n", "import android.content.Context\n", "import android.widget.Toast\n", "import androidx.activity.compose.rememberLauncherForActivityResult\n", "import androidx.activity.result.contract.ActivityResultContracts\n", "import androidx.compose.ui.platform.LocalContext\n", "import com.android.purebilibili.data.repository.CommentFraudRepository\n"]:
        replace(line, "")
    replace("package com.android.purebilibili.feature.settings.screen\n", "package com.android.purebilibili.feature.settings.screen\nimport com.bilipai.desktop.settings.*\nimport kotlinx.coroutines.CancellationException\n")
    replace("fun CommentFraudHistoryScreen(\n    onBack: () -> Unit", "internal fun DesktopOriginalCommentFraudHistoryScreen(\n    context: DesktopCommentFraudHistoryBindings,\n    onBack: () -> Unit")
    replace("    val context = LocalContext.current\n    val scope = rememberCoroutineScope()", "    DisposableEffect(context) { onDispose { context.close() } }\n    val scope = context.scope")
    replace("CommentFraudRepository.", "context.records.", source.count("CommentFraudRepository."))
    replace("rememberLauncherForActivityResult(ActivityResultContracts.GetContent())", "rememberDesktopCommentFraudImportLauncher(context)")
    replace('rememberLauncherForActivityResult(\n        ActivityResultContracts.CreateDocument("application/json")\n    )', "rememberDesktopCommentFraudExportLauncher(context)")
    replace("context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }", "context.files.readUtf8(uri)")
    replace("context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }", "context.files.writeUtf8(uri, json)")
    replace("} catch (e: Exception) {", "} catch (cancelled: CancellationException) { throw cancelled\n                } catch (e: Exception) {", 2)
    toasts = re.findall(r'Toast\.makeText\(context, (.*?), Toast\.LENGTH_SHORT\)\.show\(\)', source)
    for expression in dict.fromkeys(toasts):
        old = f"Toast.makeText(context, {expression}, Toast.LENGTH_SHORT).show()"
        replace(old, f"context.notice({expression})", source.count(old))
    for name in SYMBOLS:
        old = "com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable." + name + ")"
        replace(old, 'DesktopCommentFraudHistoryVectors.vector("' + name + '")', source.count(old))
    replace("private fun copyText(context: Context, text: String) {\n    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager\n    clipboard.setPrimaryClip(ClipData.newPlainText(\"text\", text))\n}", "private fun copyText(context: DesktopCommentFraudHistoryBindings, text: String) {\n    context.copyText(text)\n}")
    replace("onCopyMessage = {\n                                    copyText(context, record.message)\n                                    context.notice(\"✅ 文案已复制！\")\n                                },", "onCopyMessage = { context.uiAction {\n                                    copyText(context, record.message)\n                                    context.notice(\"✅ 文案已复制！\")\n                                } },")
    replace("onCopyScheme = {\n                                    val scheme = generateBiliUrlScheme(record)\n                                    copyText(context, scheme)\n                                    context.notice(\"✅ Scheme 已复制！\")\n                                },", "onCopyScheme = { context.uiAction {\n                                    val scheme = generateBiliUrlScheme(record)\n                                    copyText(context, scheme)\n                                    context.notice(\"✅ Scheme 已复制！\")\n                                } },")
    for forbidden in ["Toast.", "android.content.", "android.widget.", "androidx.activity.", "contentResolver", "LocalContext", "R.drawable"]:
        if forbidden in source: raise ValueError("Unadapted Android leaf: " + forbidden)
    rebuilt = source
    for change in reversed(changes):
        offset, before, after = change["offset"], change["before"], change["after"]
        if rebuilt[offset:offset+len(after)] != after: raise ValueError("Inverse hunk mismatch")
        rebuilt = rebuilt[:offset] + before + rebuilt[offset+len(after):]
    if rebuilt != original: raise ValueError("Whole-screen inverse failed")
    return source, dict(originalLfSha256=sha(original.encode()), adaptedLfSha256=sha(source.encode()), fullSourceInverseVerified=True, selectedProtocolMembers=[], changes=changes)

def source_inventory(original):
    return [original.row(path, "extracted") for path in [SCREEN] + POLICIES]
def resource_inventory(original):
    return [original.row("app/src/main/res/drawable/"+name+".xml") for name in SYMBOLS]
def generate(repo, output, proof_output):
    sys.path.insert(0, str(repo / "desktop/tools"))
    original = Original(repo)
    source, proof = adapt(original.text(SCREEN))
    emit(output / "com/android/purebilibili/feature/settings/screen/DesktopOriginalCommentFraudHistoryScreen.kt", source)
    spec = importlib.util.spec_from_file_location("fraud_vectors", repo / "desktop/tools/extract-upstream-settings-search.py")
    converter = importlib.util.module_from_spec(spec); spec.loader.exec_module(converter)
    converter.symbol_names = lambda _: SYMBOLS
    vectors = converter.vectors(repo).replace("DesktopSettingsSymbols", "DesktopCommentFraudHistorySymbols").replace("DesktopSettingsVectors", "DesktopCommentFraudHistoryVectors")
    emit(output / "com/bilipai/desktop/settings/DesktopCommentFraudHistoryVectors.kt", vectors)
    proof.update(upstreamCommit=UPSTREAM, sourcePath=SCREEN, adapterCompiled=False, actualRootRuntimeVerified=False, actualImportExportVerified=False, candidateModified=False)
    if proof_output: emit(proof_output, json.dumps(proof, ensure_ascii=False, indent=2)+"\n")
    return proof

if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path)
    cli.add_argument("--proof", type=Path)
    cli.add_argument("--inventory", action="store_true")
    cli.add_argument("--resource-inventory", action="store_true")
    args = cli.parse_args(); repo = args.repo.resolve(); original = Original(repo)
    if args.inventory: print(json.dumps(source_inventory(original), ensure_ascii=False, indent=2))
    if args.resource_inventory: print(json.dumps(resource_inventory(original), ensure_ascii=False, indent=2))
    if args.output:
        proof = generate(repo, args.output.resolve(), args.proof)
        print(json.dumps(dict(fullSourceInverseVerified=proof["fullSourceInverseVerified"], adaptedLfSha256=proof["adaptedLfSha256"])))
