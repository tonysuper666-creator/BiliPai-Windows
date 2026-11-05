#!/bin/bash
set -uo pipefail
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
out=${1:?provide a new output directory}
lock="$HOME/dev/.coach-heavy.lock"
if ! mkdir "$lock" 2>/dev/null; then
  echo "Shared build lock is held:"; cat "$lock/owner" 2>/dev/null || true; exit 75
fi
printf 'TON-1085 about guard pid=%s\n' "$$" > "$lock/owner"
cleanup() { rm -f "$lock/owner"; rmdir "$lock"; }
trap cleanup EXIT
bypass=$(ps -axo pid,ppid,command | grep -E '[G]radleDaemon|[G]radleWrapperMain|[K]otlinCompileDaemon|[k]otlinc' || true)
if [ -n "$bypass" ]; then echo "Build processes found after locking:"; echo "$bypass"; exit 75; fi
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
cd "$repo" || exit 1
python3 desktop/verification/ton-1085-settings-about-icon/guard/verify.py "$out"
code=$?
echo "structure_exit=$code"
if [ "$code" -ne 0 ]; then exit "$code"; fi
./gradlew -p desktop :test --tests com.bilipai.desktop.ui.DesktopAboutIconCoilTest --offline --no-daemon -x upstreamAppCoreSourceTests -x sponsorPresentationTests --rerun
code=$?
echo "coil_gradle_exit=$code"
exit "$code"
