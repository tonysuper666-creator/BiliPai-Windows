#!/bin/sh
# Writes the two Kotlin files whose producer tasks are excluded on Linux.
# usage: make-stubs.sh <repo-root>   (re-run after every `rm -rf desktop/build`)
set -eu
REPO=$(CDPATH= cd -- "${1:?repo root}" && pwd)
D="$REPO/desktop/build/generated"
# 1) Native diagnostic DLL hash: the reviewed source pin from the repository, never computed from a DLL.
PIN=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['dllSha256Bytes'])" \
  "$REPO/desktop/native/diagnostic-share/approved-development-build.json")
echo "$PIN" | grep -Eq '^[0-9a-f]{64}$' || { echo "bad pin: $PIN" >&2; exit 1; }
mkdir -p "$D/native-diagnostic-share/kotlin/com/bilipai/desktop/diagnostics"
cat > "$D/native-diagnostic-share/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeDiagnosticShareAssetHash.kt" <<K
package com.bilipai.desktop.diagnostics

/** Trusted reviewed source pin; never calculated from an installed DLL. */
internal object DesktopNativeDiagnosticShareAssetHash {
    const val sha256: String = "$PIN"
}
K
# 2) JS worker catalog hash: ALL ZEROS, ONLY so that Kotlin compiles on Linux. The real value can only be
#    produced on Windows by prepare-js-worker.py. JS-worker-dependent tests fail with
#    "JS worker resource path is invalid" on Linux; that is expected.
mkdir -p "$D/js-worker/com/bilipai/desktop/plugins/js"
cat > "$D/js-worker/com/bilipai/desktop/plugins/js/DesktopJsWorkerAssetHash.kt" <<K
// LINUX COMPILE-ONLY STUB; real value needs Windows prepare-js-worker.py.
package com.bilipai.desktop.plugins.js
internal const val DESKTOP_JS_WORKER_CATALOG_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
K
echo "stubs written (native pin $PIN, js-worker zero stub)"
