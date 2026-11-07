#!/bin/bash
set -u
repo="$(cd "$(dirname "$0")/../../.." && pwd -P)"
probe="$repo/desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopAboutIconCoilTest.kt"
lock="$HOME/dev/.coach-heavy.lock"
if [[ -e "$probe" ]]; then echo "Refusing to overwrite $probe"; exit 2; fi
if ! mkdir "$lock" 2>/dev/null; then echo "Shared build lock busy"; cat "$lock/owner" 2>/dev/null || true; exit 75; fi
printf 'PID=%s TON-1085 about Coil probe\n' "$$" > "$lock/owner"
cleanup() { rm -f "$probe" "$lock/owner"; rmdir "$lock"; }
trap cleanup EXIT
bypass=$(ps -axo pid,ppid,command | grep -E 'GradleDaemon|GradleWrapperMain|KotlinCompileDaemon|kotlinc' | grep -v grep || true)
if [[ -n "$bypass" ]]; then printf 'Other build processes found; stop without killing them:\n%s\n' "$bypass"; exit 75; fi
cp "$repo/desktop/verification/ton-1085-settings-about-icon/DesktopAboutIconCoilTest.kt.txt" "$probe"
cd "$repo"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew -p desktop test --tests com.bilipai.desktop.ui.DesktopAboutIconCoilTest --offline --no-daemon -x upstreamAppCoreSourceTests -x sponsorPresentationTests --rerun
