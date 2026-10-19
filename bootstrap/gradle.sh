#!/bin/bash
# usage: gradle.sh <repo-root> <gradle tasks/args...>
# One Gradle at a time. Log: $LOGDIR/gradle-<timestamp>.log (default /workspace/g-logs).
set -u
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=$(CDPATH= cd -- "${1:?repo root}" && pwd); shift
LOGDIR=${LOGDIR:-/workspace/g-logs}; mkdir -p "$LOGDIR"
LOG="$LOGDIR/gradle-$(date +%Y%m%d-%H%M%S).log"
export PYTHON_EXECUTABLE="$HERE/python-strip-winpath.sh" PYTHONDONTWRITEBYTECODE=1 PYTHONIOENCODING=utf-8
export GRADLE_OPTS="-Xmx256m"
cd "$REPO"
./gradlew -p desktop --console=plain --no-daemon --max-workers=1 --no-parallel \
  -Pkotlin.compiler.execution.strategy=in-process \
  "-Dorg.gradle.jvmargs=-Xmx6g -Xss16m -XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8" \
  -x prepareNativeDiagnosticShare -x fetchJsWorkerJdk -x prepareJsWorker \
  "$@" > "$LOG" 2>&1
rc=$?; echo "EXIT=$rc" >> "$LOG"; echo "log: $LOG (exit $rc)"; exit $rc
