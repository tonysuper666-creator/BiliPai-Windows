#!/usr/bin/env bash
# Regression test for tools/upstream-merge.sh on a throwaway repository: every rule must hold
# on clean merges too, and conflicts outside the rules must stop the merge.
#   tools/upstream-merge-test.sh
set -euo pipefail
script=$(cd "$(dirname "$0")" && pwd)/upstream-merge.sh
work=$(mktemp -d "${TMPDIR:-/tmp}/upstream-merge-test.XXXXXX")
trap 'rm -rf "$work"' EXIT
cd "$work"
g() { git -c user.name=test -c user.email=test@example.invalid -c core.hooksPath=/dev/null "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

g init -q -b main repo && cd repo
mkdir -p .github/workflows src
printf 'upstream readme\n' > README.md
printf '*.kt text eol=lf\n*.png binary\n' > .gitattributes
printf '/build\n' > .gitignore
printf 'agents\n' > AGENTS.md
printf 'name: Build\n' > .github/workflows/Build.yml
printf 'name: Old\n' > .github/workflows/Old.yml
printf 'one\n' > src/a.txt
g add -A && g commit -q -m base

# Desktop side: parks upstream workflows, adds its own workflow and attribute, keeps README untouched.
g checkout -q -b desktop
mkdir -p .github/upstream-workflows
g mv .github/workflows/Build.yml .github/upstream-workflows/Build.yml
g mv .github/workflows/Old.yml .github/upstream-workflows/Old.yml
printf 'name: windows\n' > .github/workflows/windows.yml
printf 'desktop/** -text\n' >> .gitattributes
g add -A && g commit -q -m desktop

# Upstream release: no conflicting edit, but every rule is exercised.
g checkout -q main
printf 'upstream readme v2\n' > README.md                   # rule 2: must not land
printf '*.png binary\n*.svg text\n' > .gitattributes         # rule 3: drops *.kt line, adds *.svg
printf '/build\n/AGENTS.md\n' > .gitignore                   # rule 4: must not land
printf 'name: Build v2\n' > .github/workflows/Build.yml      # rule 1: refreshed in parking
g rm -q .github/workflows/Old.yml                            # rule 1: removed from parking
printf 'name: New\n' > .github/workflows/New.yml             # rule 1: parked, never active
printf 'two\n' > src/a.txt                                   # ordinary upstream change
g add -A && g commit -q -m release
g update-ref refs/upstream/tags/v2 HEAD

g checkout -q desktop
GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=user.name GIT_CONFIG_VALUE_0=test GIT_CONFIG_KEY_1=user.email GIT_CONFIG_VALUE_1=test@example.invalid \
    bash "$script" v2 > ../clean.log 2>&1 || { cat ../clean.log; fail "clean merge exited non-zero"; }
[ "$(git show HEAD:README.md)" = "upstream readme" ] || fail "rule 2: upstream README landed"
git show HEAD:.gitattributes | grep -qx '\*.kt text eol=lf' || fail "rule 3: our attribute line was dropped"
git show HEAD:.gitattributes | grep -qx 'desktop/\*\* -text' || fail "rule 3: desktop attribute lost"
git show HEAD:.gitattributes | grep -qx '\*.svg text' || fail "rule 3: upstream attribute missing"
git show HEAD:.gitignore | grep -q 'AGENTS' && fail "rule 4: upstream ignore rule landed"
[ "$(git ls-tree --name-only HEAD .github/workflows/)" = ".github/workflows/windows.yml" ] || fail "rule 1: upstream workflow is active"
[ "$(git ls-tree --name-only HEAD .github/upstream-workflows/ | tr '\n' ' ')" = ".github/upstream-workflows/Build.yml .github/upstream-workflows/New.yml " ] || fail "rule 1: parked set differs from upstream"
[ "$(git show HEAD:.github/upstream-workflows/Build.yml)" = "name: Build v2" ] || fail "rule 1: parked workflow not refreshed"
[ "$(git show HEAD:src/a.txt)" = "two" ] || fail "upstream source change missing"
git log -1 --format=%B | grep -qi 'co-authored-by' && fail "merge commit carries a trailer"
echo "ok: clean merge applies all four rules"

# A conflict outside the rules stops for a human.
g checkout -q -b conflict desktop~1
printf 'desktop edit\n' > src/a.txt
g commit -q -am conflict
set +e
GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=user.name GIT_CONFIG_VALUE_0=test GIT_CONFIG_KEY_1=user.email GIT_CONFIG_VALUE_1=test@example.invalid \
    bash "$script" v2 > ../conflict.log 2>&1
status=$?
set -e
[ $status -eq 2 ] || { cat ../conflict.log; fail "source conflict exited $status, expected 2"; }
grep -q 'src/a.txt' ../conflict.log || fail "source conflict not reported"
echo "ok: source conflict stops with exit 2"
