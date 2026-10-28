#!/usr/bin/env bash
# Merge an upstream BiliPai tag into the current branch with the fixed desktop rules.
#
#   tools/upstream-merge.sh <tag> [--no-commit]
#
# The upstream repository is only fetched (read-only). Rules applied to every merge:
#   1. Upstream workflows never run in this repository: anything upstream ships under
#      .github/workflows/ lands in .github/upstream-workflows/ instead; workflows that
#      upstream deleted are deleted there too.
# All four rules apply on every merge, not only when git reports a conflict.
#   2. README.md stays ours (the Windows README).
#   3. .gitattributes is the union of both sides (ours first, then new upstream lines).
#   4. .gitignore stays ours; only upstream negation rules that keep tracked files
#      trackable are added. Upstream ignore rules are never added (upstream ignores
#      /AGENTS.md and other files this repository tracks).
# Any other conflict stops the script with the merge left in place for a human.
set -euo pipefail

UPSTREAM_URL=${BILIPAI_UPSTREAM_URL:-https://github.com/jay3-yy/BiliPai.git}
tag=${1:?usage: tools/upstream-merge.sh <tag> [--no-commit]}
commit_result=1
[ "${2:-}" = "--no-commit" ] && commit_result=0

root=$(git rev-parse --show-toplevel)
cd "$root"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "upstream-merge: working tree has uncommitted changes" >&2
    exit 1
fi

started=$(date +%s)
ref=refs/upstream/tags/$tag
if ! git rev-parse -q --verify "$ref^{commit}" >/dev/null; then
    git fetch --no-tags "$UPSTREAM_URL" "refs/tags/$tag:$ref"
fi
upstream=$(git rev-parse "$ref^{commit}")
echo "upstream-merge: $tag = $upstream into $(git rev-parse --abbrev-ref HEAD) @ $(git rev-parse --short HEAD)"

set +e
log=$(git rev-parse --git-dir)/upstream-merge.log
git merge --no-ff --no-commit --no-edit "$upstream" >"$log" 2>&1
merge_status=$?
set -e
cat "$log"
raw_conflicts=$(grep -c '^CONFLICT' "$log" || true)
if [ $merge_status -ne 0 ] && ! git rev-parse -q --verify MERGE_HEAD >/dev/null; then
    echo "upstream-merge: git merge failed before producing a merge state" >&2
    exit 1
fi

python3 -I - "$upstream" <<'PY'
import os, subprocess, sys

upstream = sys.argv[1]

def git(*args, input=None, check=True):
    return subprocess.run(["git", *args], input=input, capture_output=True, text=True, check=check).stdout

def upstream_lines(path):
    result = subprocess.run(["git", "show", f"{upstream}:{path}"], capture_output=True, text=True)
    return result.stdout.splitlines() if result.returncode == 0 else []

def ours_lines(path):
    return git("show", f"HEAD:{path}").splitlines()

def write(path, lines):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines).rstrip("\n") + "\n")
    git("add", "--", path)

def unmerged():
    return sorted({line.split("\t", 1)[1] for line in git("ls-files", "-u").splitlines()})

# Every rule runs on every merge, conflicted or not: a clean merge can still carry an upstream
# README edit, a deleted attribute line or a new workflow.

# Rule 1: upstream workflows are parked, never active. The parked set is exactly upstream's
# current .github/workflows/ (new ones added, changed ones refreshed, deleted ones removed).
upstream_workflows = git("ls-tree", "-r", "--name-only", upstream, "--", ".github/workflows/").splitlines()
parked_wanted = {".github/upstream-workflows/" + os.path.basename(p): p for p in upstream_workflows}
parked_now = set(git("ls-files", "--", ".github/upstream-workflows/").splitlines())
for path in sorted(parked_now - parked_wanted.keys()):
    git("rm", "-q", "-f", "--cached", "--ignore-unmatch", "--", path)
    if os.path.exists(path):
        os.remove(path)
    print(f"rule 1: {path} removed (deleted upstream)")
for parked, path in sorted(parked_wanted.items()):
    content = git("show", f"{upstream}:{path}")
    os.makedirs(os.path.dirname(parked), exist_ok=True)
    with open(parked, "w", encoding="utf-8", newline="") as handle:
        handle.write(content)
    git("add", "--", parked)
    git("rm", "-q", "-f", "--cached", "--ignore-unmatch", "--", path)
    if os.path.exists(path):
        os.remove(path)
    print(f"rule 1: {path} -> {parked}")

# Rule 2: the Windows README stays ours, whatever upstream did to README.md.
git("checkout", "HEAD", "--", "README.md")
print("rule 2: README.md kept ours")

# Rule 3: .gitattributes is the union: every line of ours (even if upstream deleted it), then
# upstream lines we do not have yet.
ours = ours_lines(".gitattributes")
write(".gitattributes", ours + [line for line in upstream_lines(".gitattributes") if line.strip() and line not in ours])
print("rule 3: .gitattributes union")

# Rule 4: .gitignore stays ours plus the upstream negations that tracked files need.
ours = ours_lines(".gitignore")
header = ["", "# Upstream merge: keep upstream-tracked files trackable."]
def ignored_tracked():
    return set(git("ls-files", "-ci", "--exclude-standard").splitlines())
write(".gitignore", ours)
baseline = ignored_tracked()
kept = []
for line in upstream_lines(".gitignore"):
    if not line.startswith("!") or line in ours or line in kept:
        continue
    write(".gitignore", ours + header + kept + [line])
    now = ignored_tracked()
    if now < baseline:
        kept.append(line)
        baseline = now
write(".gitignore", ours + (header + kept if kept else []))
check = subprocess.run(["git", "check-ignore", "-q", "--no-index", "AGENTS.md"])
if check.returncode == 0:
    sys.exit("rule 4 violated: AGENTS.md would be ignored")
print(f"rule 4: .gitignore kept ours, {len(kept)} upstream negation(s) added")

left = unmerged()
if left:
    print("upstream-merge: conflicts outside the fixed rules need a human:", file=sys.stderr)
    for path in left:
        print("  " + path, file=sys.stderr)
    sys.exit(2)
PY

elapsed=$(( $(date +%s) - started ))
echo "upstream-merge: git conflicts=$raw_conflicts (all resolved by rules), seconds=$elapsed"
if [ $commit_result -eq 1 ]; then
    # Repository commit hooks may append agent trailers; merge commits carry none (project rule).
    git -c core.hooksPath=/dev/null commit -q -m "Merge upstream $tag" -m "Upstream $UPSTREAM_URL $tag ($upstream), merged with tools/upstream-merge.sh."
    echo "upstream-merge: committed $(git rev-parse --short HEAD)"
fi
