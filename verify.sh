#!/bin/bash
# Root's one-command verifier for the Grok Bot box. It is a plain shell daemon: while it waits or runs it uses ZERO model tokens.
#
#   start:  nohup bash /workspace/verify.sh > /workspace/v-out/daemon.out 2>&1 &
#   stop:   Root writes a file named STOP into the queue branch, or kill the process.
#
# Every POLL seconds it fetches origin/desktop/grokbot-queue, reads the file TARGET there and, when the commit named in
# TARGET differs from the last one it verified, runs ONE clean verification (stubs, compileKotlin, compileTestKotlin, JVM
# tests, Python tests) and pushes ONE compact report as the new branch grokbot/verify-<sha9>. It also pushes a one-file
# heartbeat to grokbot/status about once an hour. It never pushes anything else, never forces, never deletes branches.
#
# TARGET (queue branch root, plain text, one field per line; '#' lines ignored):
#   branch: desktop/integration-263-claude-main
#   sha: <full commit sha on that branch>
#   jvm: <gradle --tests pattern>         (repeatable; none = the whole JVM test suite)
#   py: <dir under desktop/tools>:<unittest -p pattern>      (repeatable, run from the verified worktree after the build)
set -u
REPO=${REPO:-/workspace/bilipai-g}
BOOT=${BOOT:-/workspace/bootstrap/bootstrap}
WORK=${WORK:-/workspace}
OUT=${OUT:-$WORK/v-out}
QUEUE=desktop/grokbot-queue
ROOT_AUTHOR=${ROOT_AUTHOR:-tonysuper666-creator}
POLL=${POLL:-900}
HEARTBEAT=${HEARTBEAT:-3600}
MAX_HOURS=${MAX_HOURS:-72}
KNOWN_JS='DesktopOriginalVideoCdnSelectionAssemblyTest DesktopOriginalPortraitMetadataPreparationTest DesktopOriginalSponsorPresentationAssemblyTest'

mkdir -p "$OUT"
LOCK=$OUT/.lockdir
if [ "${VERIFY_REEXEC:-}" != 1 ]; then   # a self-update re-exec keeps the lock it already owns (same pid)
  if ! mkdir "$LOCK" 2>/dev/null; then
    if kill -0 "$(cat "$LOCK/pid" 2>/dev/null)" 2>/dev/null; then echo "another verifier is already running"; exit 0; fi
    rm -rf "$LOCK"; mkdir "$LOCK" || exit 2
  fi
  echo $$ > "$LOCK/pid"
fi
trap 'rm -rf "$LOCK"' EXIT
cd "$REPO" || { echo "no repo at $REPO"; exit 2; }
git config core.autocrlf false
git config core.eol lf

log() { echo "$(date -u '+%F %T') $*" >> "$OUT/daemon.log"; }

# ---- git plumbing: push one file as a brand-new orphan commit (reports) or as a fast-forward of grokbot/status (heartbeat)
push_report() {  # <branch> <file-name-in-reports> <local-file> <message>
  local branch=$1 name=$2 file=$3 msg=$4 blob sub top commit n=1 target=$1
  blob=$(git hash-object -w "$file") || return 1
  sub=$(printf '100644 blob %s\t%s\n' "$blob" "$name" | git mktree) || return 1
  top=$(printf '040000 tree %s\tgrokbot-reports\n' "$sub" | git mktree) || return 1
  commit=$(git commit-tree "$top" -m "$msg") || return 1
  while git ls-remote --exit-code --heads origin "refs/heads/$target" >/dev/null 2>&1; do n=$((n + 1)); target="$branch-r$n"; done
  git push -q origin "$commit:refs/heads/$target" && echo "$target"
}
push_status() {  # <local-file>
  local file=$1 blob top parent commit
  git fetch -q origin grokbot/status 2>/dev/null
  parent=$(git rev-parse -q --verify origin/grokbot/status 2>/dev/null || true)
  blob=$(git hash-object -w "$file") || return 1
  top=$(printf '100644 blob %s\tSTATUS.md\n' "$blob" | git mktree) || return 1
  if [ -n "$parent" ]; then commit=$(git commit-tree "$top" -p "$parent" -m "heartbeat") || return 1
  else commit=$(git commit-tree "$top" -m "heartbeat") || return 1; fi
  git push -q origin "$commit:refs/heads/grokbot/status"
}
heartbeat() {  # <state>
  local f="$OUT/STATUS.md"
  { echo "# Grok Bot verifier (shell daemon, no model tokens)"; echo
    echo "- Time (UTC): $(date -u '+%F %T')"; echo "- State: $1"
    echo "- Last verified: $(cat "$OUT/last-sha" 2>/dev/null || echo none)"
    echo "- Disk free: $(df -h "$WORK" 2>/dev/null | awk 'NR==2{print $4}')"; } > "$f"
  push_status "$f" || log "heartbeat push failed"
}

target_field() { git show "origin/$QUEUE:TARGET" 2>/dev/null | grep -v '^#' | sed -n "s/^$1:[[:space:]]*//p"; }

# ---- one verification
summarise_jvm() {  # <results-dir> -> text
  python3 - "$1" "$KNOWN_JS" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
results, known = sys.argv[1], set(sys.argv[2].split())
tests = fail = err = skip = 0
bad = {}
for path in glob.glob(results + '/*.xml'):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    suites = [root] if root.tag == 'testsuite' else root.iter('testsuite')
    for s in suites:
        t, f, e, k = (int(s.get(x, 0)) for x in ('tests', 'failures', 'errors', 'skipped'))
        tests += t; fail += f; err += e; skip += k
        if f + e:
            bad[s.get('name', '?')] = f + e
print('classes: %d  tests: %d  failed: %d  errors: %d  skipped: %d' % (len(glob.glob(results + '/*.xml')), tests, fail, err, skip))
new = {c: n for c, n in bad.items() if c.rsplit('.', 1)[-1] not in known}
old = {c: n for c, n in bad.items() if c.rsplit('.', 1)[-1] in known}
print('known JS-worker classes failing (expected on Linux): %d' % len(old))
print('OTHER failing classes: %d' % len(new))
for c, n in sorted(new.items())[:40]:
    print('  %s (%d)' % (c, n))
PY
}

verify_one() {  # <branch> <sha>
  local branch=$1 sha=$2 short=${2:0:9} W L LOG t0 t1 rc errs shadow build rep line spec dir pat out
  W=$WORK/v-$short; L=$OUT/$short; rep=$L/report.md
  rm -rf "$L"; mkdir -p "$L"
  git fetch -q origin "$branch" || { echo "fetch of $branch failed" > "$L/fail.txt"; }
  if [ -f "$L/fail.txt" ] || ! git merge-base --is-ancestor "$sha" "origin/$branch" 2>/dev/null; then
    { echo "# VERIFY $branch@$sha"; echo; echo "NOT RUN: the commit is not on origin/$branch (or the fetch failed)."; } > "$rep"
    push_report "grokbot/verify-$short" "verify-$short.md" "$rep" "Verify $short: not run" >/dev/null; return 0
  fi
  git worktree remove --force "$W" 2>/dev/null; rm -rf "$W"; git worktree prune
  git worktree add -q --detach "$W" "$sha" || { echo "worktree failed" > "$L/fail.txt"; }
  t0=$(date +%s)
  if [ ! -f "$L/fail.txt" ]; then
    sh "$BOOT/make-stubs.sh" "$W" > "$L/stubs.txt" 2>&1
    local args=()
    while IFS= read -r line; do [ -n "$line" ] && args+=(--tests "$line"); done < <(target_field jvm)
    LOGDIR="$L" timeout 5400 bash "$BOOT/test.sh" "$W" "${args[@]}" > "$L/gradle-wrapper.txt" 2>&1
    rc=$?
  else rc=99; fi
  LOG=$(ls -t "$L"/gradle-2*.log 2>/dev/null | head -1)
  errs=$( [ -n "$LOG" ] && grep -c '^e: ' "$LOG" || true ); errs=${errs:-0}
  shadow=$( [ -n "$LOG" ] && grep -c 'is shadowed by a member' "$LOG" || true ); shadow=${shadow:-0}
  build=$( [ -n "$LOG" ] && grep -E '^BUILD (SUCCESSFUL|FAILED)' "$LOG" | tail -1 || true ); build=${build:-no BUILD line in the log}
  t1=$(date +%s)
  {
    echo "# VERIFY $branch@$sha"; echo
    echo "- Linux, $(java -version 2>&1 | head -1), Python $(python3 -V 2>&1), $(( (t1 - t0) / 60 )) min for the Gradle run (clean worktree, JS-worker and diagnostic stubs, Linux only)"
    echo "- Gradle: $build, exit $rc; kotlin errors (e:) $errs; 'shadowed by a member' warnings $shadow"
    echo "- Evidence: Linux only. Linux pass is not a Windows validation and not a hardware proof."
    echo; echo "## JVM"
    if [ -d "$W/desktop/build/test-results/test" ]; then summarise_jvm "$W/desktop/build/test-results/test"; else echo "no test results (compile failed or tests did not start)"; fi
    if [ "$errs" != "0" ] && [ -n "$LOG" ]; then echo; echo "## first compile errors"; grep '^e: ' "$LOG" | head -25 | cut -c1-260; fi
    if [ "$rc" != "0" ] && [ "$errs" = "0" ] && [ -n "$LOG" ]; then echo; echo "## log tail"; tail -15 "$LOG" | cut -c1-260; fi
    echo; echo "## Python"
  } > "$rep"
  # Python suites run from the built worktree (some read desktop/build/generated); each is capped at 30 minutes.
  if [ ! -f "$L/fail.txt" ]; then
    while IFS= read -r spec; do
      [ -z "$spec" ] && continue
      dir=${spec%%:*}; pat=${spec#*:}
      timeout 1800 bash "$BOOT/python-test.sh" "$W" discover -s "desktop/tools/$dir" -p "$pat" > "$L/py.out" 2>&1
      out=$(tail -4 "$L/py.out" | tr '\n' ' ' | cut -c1-220)
      echo "- $dir/$pat: $out" >> "$rep"
      grep -E '^(FAIL|ERROR): ' "$L/py.out" | head -8 | cut -c1-200 | sed 's/^/    /' >> "$rep"
    done < <(target_field py)
  fi
  local pushed
  pushed=$(push_report "grokbot/verify-$short" "verify-$short.md" "$rep" "Verify $short: $build, e: $errs") || pushed="PUSH FAILED"
  log "verified $short -> $pushed ($build, e: $errs)"
  echo "$sha" > "$OUT/last-sha"
  git worktree remove --force "$W" 2>/dev/null; rm -rf "$W"; git worktree prune
}

# ---- main loop (no model involved)
START=$(date +%s); LASTBEAT=0
log "daemon started (poll ${POLL}s)"
while true; do
  now=$(date +%s)
  [ $(( (now - START) / 3600 )) -ge "$MAX_HOURS" ] && { heartbeat "stopped: ran ${MAX_HOURS}h"; exit 0; }
  if git fetch -q origin "$QUEUE" 2>/dev/null; then
    if git cat-file -e "origin/$QUEUE:STOP" 2>/dev/null; then heartbeat "stopped by Root (STOP file)"; log "STOP"; exit 0; fi
    # self-update: when Root changes verify.sh on the queue branch (and Root's commit authored it), re-exec the new copy
    if [ "$(git log -1 --format=%an "origin/$QUEUE" 2>/dev/null)" = "$ROOT_AUTHOR" ]; then
      newsum=$(git show "origin/$QUEUE:verify.sh" 2>/dev/null | sha256sum | cut -d' ' -f1)
      ownsum=$(sha256sum "$0" | cut -d' ' -f1)
      if [ -n "$newsum" ] && [ "$newsum" != "$ownsum" ] && git show "origin/$QUEUE:verify.sh" 2>/dev/null | head -1 | grep -q '^#!/bin/bash'; then
        git show "origin/$QUEUE:verify.sh" > "$0.new" && mv "$0.new" "$0" && log "self-update to ${newsum:0:12}" && VERIFY_REEXEC=1 exec bash "$0"
      fi
    fi
    author=$(git log -1 --format=%an "origin/$QUEUE" 2>/dev/null)
    branch=$(target_field branch | head -1); sha=$(target_field sha | head -1)
    if [ "$author" = "$ROOT_AUTHOR" ] && [ -n "$branch" ] && [ -n "$sha" ] && [ "$sha" != "$(cat "$OUT/last-sha" 2>/dev/null)" ]; then
      heartbeat "verifying ${sha:0:9}"; LASTBEAT=$now
      verify_one "$branch" "$sha"
    elif [ "$author" != "$ROOT_AUTHOR" ]; then
      log "queue head author is '$author', not $ROOT_AUTHOR: ignoring"
    fi
  else
    log "fetch of $QUEUE failed"
  fi
  now=$(date +%s)
  if [ $(( now - LASTBEAT )) -ge "$HEARTBEAT" ]; then heartbeat "idle, waiting for a new TARGET"; LASTBEAT=$now; fi
  sleep "$POLL"
done
