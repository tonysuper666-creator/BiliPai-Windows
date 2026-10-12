#!/usr/bin/env python3
"""Advisory worktree coordination. Root runs init/configure; participants honor scopes.
No file-write sandbox or process-liveness assertion; only read-only Git inspection.
"""
import argparse, contextlib, datetime as dt, json, os, re, subprocess, tempfile, time, uuid
from pathlib import Path
from urllib.parse import urlsplit
MIN_BASE = "a969341e22d42e965e03861cc275df2bb9fa38f0"
OWN_REPO = "tonysuper666-creator/BiliPai-Windows"
CONFIG_KEYS = {"rootRepo", "commonDir", "origin", "minimumBase", "acceptedHead", "reserved"}
RECORD_KEYS = {"id", "module", "agent", "thread", "worktree", "branch", "baseSha", "scopes", "readOnly", "createdAt", "expiresAt", "state", "delivery", "releasedAt", "releaseReason"}
def need(ok, message):
    if not ok: raise ValueError(message)
def now(): return dt.datetime.now(dt.timezone.utc)
def stamp(): return now().isoformat(timespec="seconds")
def date(value):
    need(isinstance(value, str), "timestamp must be text")
    result = dt.datetime.fromisoformat(value.replace("Z", "+00:00")); need(result.tzinfo is not None, "timestamp requires timezone"); return result

def full_sha(value): return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) is not None

def canonical(path): return os.path.normcase(str(Path(path).resolve()))
def inside(child, parent): return Path(canonical(child)).is_relative_to(Path(canonical(parent)))
def git(repo, *args, allow_one=False):
    result = subprocess.run(["git", "--no-optional-locks", "-c", "core.fsmonitor=false", "-C", str(repo), *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    need(result.returncode == 0 or allow_one and result.returncode == 1, "Git inspection failed: " + " ".join(args[:2]))
    return result.returncode if allow_one else result.stdout.decode("utf-8")
def ancestor(repo, older, newer): return git(repo, "merge-base", "--is-ancestor", older, newer, allow_one=True) == 0

def own_origin(raw):
    text = raw.strip()
    if text.startswith("git@github.com:"): path = text[len("git@github.com:"):]
    else:
        url = urlsplit(text)
        need(url.hostname == "github.com" and not url.query and not url.fragment, "origin is not the user's own GitHub repository")
        need((url.scheme == "https" and url.username is None and url.port in (None, 443)) or
             (url.scheme == "ssh" and url.username == "git" and url.password is None and url.port in (None, 22)), "unsupported origin transport")
        path = url.path.lstrip("/")
    if path.endswith(".git"): path = path[:-4]
    need(path.casefold() == OWN_REPO.casefold(), "origin must be the user's own BiliPai-Windows repository")
    return OWN_REPO

def inspect(repo):
    repo = Path(repo).resolve()
    need(canonical(git(repo, "rev-parse", "--show-toplevel").strip()) == canonical(repo), "use the exact worktree root")
    common = canonical(git(repo, "rev-parse", "--path-format=absolute", "--git-common-dir").strip())
    origin = own_origin(git(repo, "remote", "get-url", "origin"))
    branch = git(repo, "symbolic-ref", "--quiet", "--short", "HEAD").strip()
    need(branch and branch != "desktop/windows/alpha9" and not branch.startswith("desktop/windows/alpha9/"), "detached or legacy alpha9 branch is not admitted")
    return common, origin, branch, git(repo, "rev-parse", "HEAD").strip()

def parse_scope(text):
    need(isinstance(text, str), "scope must be text")
    kind, sep, path = text.partition(":")
    need(sep and kind in ("file", "prefix"), "scope must be file:path or prefix:directory")
    path = path.rstrip("/") if kind == "prefix" else path
    parts = path.split("/")
    need(path and not path.startswith("/") and "\\" not in path and ":" not in path and all(ord(c) >= 32 for c in path), "scope must be a portable repo-relative path")
    need(all(x and x not in (".", "..") and x.rstrip(" .") == x and x.casefold() != ".git" for x in parts), "unsafe scope component")
    return {"kind": kind, "path": path}

def scope_matches(scope, path):
    key, target = scope["path"].casefold(), path.casefold()
    return target == key or scope["kind"] == "prefix" and target.startswith(key + "/")

def scopes_overlap(left, right):
    a, b = left["path"].casefold(), right["path"].casefold()
    # A future file also conflicts with a future directory at the same path.
    return a == b or a.startswith(b + "/") or b.startswith(a + "/")

def scope_list(values):
    need(isinstance(values, list) and values, "at least one explicit scope is required")
    result = []
    for value in values:
        need(isinstance(value, dict) and set(value) == {"kind", "path"} and isinstance(value["kind"], str) and isinstance(value["path"], str), "malformed scope")
        need(parse_scope(value["kind"] + ":" + value["path"]) == value, "scope must be canonical")
        need(not any(scopes_overlap(value, old) for old in result), "redundant scopes overlap")
        result.append(value)
    return result

def conflicts(records, reserved, proposed):
    if proposed["readOnly"]: return []
    problems = ["Root reserved scope" for scope in proposed["scopes"] for root in reserved if scopes_overlap(scope, root)]
    for record in records.values():
        if record["id"] == proposed["id"] or record["state"] == "RELEASED" or record["readOnly"]: continue
        if any(scopes_overlap(a, b) for a in record["scopes"] for b in proposed["scopes"]): problems.append(record["module"] + ":" + record["id"])
    return sorted(set(problems))

def validate_state(value):
    need(isinstance(value, dict) and set(value) == {"schema", "generation", "config", "records"} and type(value["schema"]) is int and value["schema"] == 1, "unknown registry schema; manual repair required")
    need(type(value["generation"]) is int and value["generation"] >= 0, "invalid generation")
    config, records = value["config"], value["records"]
    need(isinstance(config, dict) and set(config) == CONFIG_KEYS and isinstance(records, dict), "malformed registry")
    need(config["origin"] == OWN_REPO and config["minimumBase"] == MIN_BASE and full_sha(config["acceptedHead"]), "invalid repository/baseline config")
    need(all(isinstance(config[k], str) and Path(config[k]).is_absolute() for k in ("rootRepo", "commonDir")), "invalid repository paths")
    config["reserved"] = scope_list(config["reserved"])
    modules, worktrees = set(), set()
    for key, record in records.items():
        need(isinstance(record, dict) and set(record) == RECORD_KEYS and key == record["id"] and str(uuid.UUID(key)) == key, "unknown/malformed record; manual repair required")
        need(isinstance(record["module"], str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", record["module"]), "invalid module")
        need(all(record[k] is None or isinstance(record[k], str) and 0 < len(record[k]) <= 256 for k in ("agent", "thread")), "invalid optional agent/thread")
        need(isinstance(record["worktree"], str) and Path(record["worktree"]).is_absolute() and isinstance(record["branch"], str) and record["branch"], "invalid worktree/branch")
        need(full_sha(record["baseSha"]) and type(record["readOnly"]) is bool and record["state"] in ("ACTIVE", "DELIVERED", "RELEASED"), "invalid record state")
        record["scopes"] = scope_list(record["scopes"]); date(record["createdAt"])
        if record["expiresAt"] is not None: date(record["expiresAt"])
        if record["state"] == "RELEASED":
            date(record["releasedAt"]); need(isinstance(record["releaseReason"], str) and record["releaseReason"].strip(), "release requires reason")
        else:
            need(record["releasedAt"] is None and record["releaseReason"] is None, "unreleased record has release metadata")
            worktree = canonical(record["worktree"])
            need(record["module"] not in modules and worktree not in worktrees, "active module/worktree is registered twice")
            modules.add(record["module"]); worktrees.add(worktree)
        if record["delivery"] is not None:
            delivery = record["delivery"]
            need(isinstance(delivery, dict) and set(delivery) == {"headSha", "baseSha", "changedPaths", "checkedAt", "qualification"}, "invalid delivery")
            need(full_sha(delivery["headSha"]) and delivery["baseSha"] == record["baseSha"] and delivery["qualification"] == "NOT_ASSESSED", "invalid delivery evidence")
            need(isinstance(delivery["changedPaths"], list) and all(isinstance(x, str) for x in delivery["changedPaths"]), "invalid delivery paths"); date(delivery["checkedAt"])
        need(record["state"] != "DELIVERED" or record["delivery"] is not None, "delivery evidence absent")
    for record in records.values():
        if record["state"] != "RELEASED": need(not conflicts(records, config["reserved"], record), "registry contains overlapping write scopes; manual repair required")
    return value

def unique(pairs):
    result = {}
    for key, value in pairs: need(key not in result, "duplicate JSON key"); result[key] = value
    return result

def load(file):
    need(file.is_file() and not file.is_symlink() and file.stat().st_size <= 4 * 1024 * 1024, "unknown registry custody")
    return validate_state(json.loads(file.read_text(encoding="utf-8"), object_pairs_hook=unique))

@contextlib.contextmanager
def locked(directory):
    path = directory / "registry.lock"; need(not path.is_symlink(), "lock cannot be a link")
    try:
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError: pass
    else:
        with os.fdopen(descriptor, "wb") as created:
            created.write(b"\0"); created.flush(); os.fsync(created.fileno())
    with path.open("r+b") as handle:
        need(handle.seek(0, os.SEEK_END) == 1, "unknown lock file custody; explicit Root repair required")
        deadline = time.monotonic() + 10
        while True:
            try:
                handle.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except (BlockingIOError, OSError):
                need(time.monotonic() < deadline, "registry lock busy; no state changed"); time.sleep(.05)
        try:
            handle.seek(0); need(handle.read(1) == b"\0", "unknown lock file contents")
            yield
        finally:
            handle.seek(0)
            if os.name == "nt": msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

def save(file, value):
    validate_state(value); value["generation"] += 1
    temporary = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="\n", dir=file.parent, prefix="registry-", suffix=".tmp", delete=False) as output:
            temporary = Path(output.name); json.dump(value, output, ensure_ascii=False, indent=2); output.write("\n"); output.flush(); os.fsync(output.fileno())
        os.replace(temporary, file); temporary = None
    finally:
        if temporary is not None: temporary.unlink(missing_ok=True)

def root_current(config):
    common, origin, _, _ = inspect(config["rootRepo"])
    need(common == config["commonDir"] and origin == config["origin"], "Root repository identity changed")
    need(ancestor(config["rootRepo"], MIN_BASE, config["acceptedHead"]), "accepted head predates minimum baseline")

def check_record(config, record):
    worktree = Path(record["worktree"]); common, origin, branch, head = inspect(worktree)
    need(common == config["commonDir"] and origin == config["origin"] and branch == record["branch"], "worktree identity/branch changed")
    need(ancestor(worktree, MIN_BASE, record["baseSha"]) and ancestor(worktree, record["baseSha"], config["acceptedHead"]) and ancestor(worktree, record["baseSha"], head), "baseline ancestry rejected")
    paths = sorted(set(git(worktree, "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only", "-z", record["baseSha"]).split("\0")[:-1] + git(worktree, "diff", "--cached", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only", "-z", record["baseSha"]).split("\0")[:-1] + git(worktree, "ls-files", "--others", "--exclude-standard", "-z").split("\0")[:-1]))
    deviations = [path for path in paths if record["readOnly"] or not any(scope_matches(scope, path) for scope in record["scopes"])]
    dirty = bool(git(worktree, "status", "--porcelain=v1", "-z"))
    expired = record["expiresAt"] is not None and date(record["expiresAt"]) <= now()
    return {"ok": not deviations and not expired and record["state"] != "RELEASED", "recordId": record["id"], "headSha": head, "dirty": dirty, "changedPaths": paths, "scopeDeviations": deviations, "expired": expired, "processLiveness": "NOT_INSPECTED", "qualification": "NOT_ASSESSED"}

def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--state-dir", type=Path, required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init"); init.add_argument("--root-repo", type=Path, required=True); init.add_argument("--accepted-head", default=MIN_BASE); init.add_argument("--reserved", action="append", required=True)
    configure = commands.add_parser("configure"); configure.add_argument("--accepted-head"); configure.add_argument("--reserved", action="append")
    register = commands.add_parser("register"); register.add_argument("--module", required=True); register.add_argument("--agent"); register.add_argument("--thread"); register.add_argument("--worktree", type=Path, required=True); register.add_argument("--base-sha"); register.add_argument("--scope", action="append", required=True); register.add_argument("--read-only", action="store_true"); register.add_argument("--expires-at")
    register.add_argument("--adopt-committed", action="store_true", help="Root adopts existing clean commits after validating their complete scope; does not change their baseline")
    commands.add_parser("status")
    for name in ("check", "deliver", "release"):
        command = commands.add_parser(name); command.add_argument("--record-id", required=True)
        if name == "release": command.add_argument("--reason", required=True)
    args = parser.parse_args(); directory = args.state_dir.resolve(); file = directory / "registry.json"
    if args.command == "init":
        repo = args.root_repo.resolve(); common, origin, _, _ = inspect(repo)
        need(not inside(directory, repo) and not inside(directory, common), "state-dir must be outside source worktrees and Git metadata")
        need(full_sha(args.accepted_head) and ancestor(repo, MIN_BASE, args.accepted_head), "Root accepted head must descend from minimum baseline")
        directory.mkdir(parents=True, exist_ok=True)
    else: need(directory.is_dir(), "Root must initialize state-dir first")
    with locked(directory):
        if args.command == "init":
            need(not file.exists() and not file.is_symlink(), "registry exists or custody unknown; configure explicitly without dropping records")
            state = {"schema": 1, "generation": 0, "config": {"rootRepo": str(repo), "commonDir": common, "origin": origin, "minimumBase": MIN_BASE, "acceptedHead": args.accepted_head, "reserved": [parse_scope(x) for x in args.reserved]}, "records": {}}
            save(file, state); result = {"initialized": True, "generation": state["generation"]}
        else:
            state = load(file); config = state["config"]; root_current(config)
            need(not inside(directory, config["rootRepo"]) and not inside(directory, config["commonDir"]), "state directory entered source/Git paths")
            if args.command == "configure":
                if args.accepted_head:
                    need(full_sha(args.accepted_head) and ancestor(config["rootRepo"], config["acceptedHead"], args.accepted_head), "accepted head may only advance")
                    config["acceptedHead"] = args.accepted_head
                if args.reserved is not None: config["reserved"] = [parse_scope(x) for x in args.reserved]
                save(file, state); result = {"configured": True, "generation": state["generation"]}
            elif args.command == "register":
                worktree = args.worktree.resolve(); common, origin, branch, head = inspect(worktree); base = args.base_sha or config["acceptedHead"]
                need(common == config["commonDir"] and origin == config["origin"] and canonical(worktree) != canonical(config["rootRepo"]), "require independent worktree of the same own repository")
                gitdir = canonical(git(worktree, "rev-parse", "--absolute-git-dir").strip()); need(gitdir != common, "require a linked Git worktree")
                need(not inside(directory, worktree) and not any(inside(directory, r["worktree"]) for r in state["records"].values()), "state-dir cannot live in any registered source tree")
                need(full_sha(base) and not git(worktree, "status", "--porcelain=v1", "-z") and (head == base or args.adopt_committed and ancestor(worktree, base, head)), "register a clean baseline or explicitly adopt clean descendant commits")
                need(ancestor(worktree, MIN_BASE, base) and ancestor(worktree, base, config["acceptedHead"]), "old or unaccepted baseline rejected")
                if args.expires_at: need(date(args.expires_at) > now(), "new expiry must be in the future")
                record = {"id": str(uuid.uuid4()), "module": args.module, "agent": args.agent, "thread": args.thread, "worktree": str(worktree), "branch": branch, "baseSha": base, "scopes": [parse_scope(x) for x in args.scope], "readOnly": args.read_only, "createdAt": stamp(), "expiresAt": args.expires_at, "state": "ACTIVE", "delivery": None, "releasedAt": None, "releaseReason": None}
                need(not conflicts(state["records"], config["reserved"], record), "write scope conflicts with Root or another unreleased module")
                if args.adopt_committed:
                    need(check_record(config, record)["ok"], "existing commits exceed the adopted scope or baseline")
                state["records"][record["id"]] = record; save(file, state); result = record
            elif args.command == "status":
                result = {"generation": state["generation"], "config": config, "records": list(state["records"].values()), "expiredRecordIds": [r["id"] for r in state["records"].values() if r["state"] != "RELEASED" and r["expiresAt"] and date(r["expiresAt"]) <= now()], "processLiveness": "NOT_INSPECTED", "expiredScopesRemainReserved": True}
            else:
                need(args.record_id in state["records"], "unknown record; no automatic recovery"); record = state["records"][args.record_id]
                if args.command == "release":
                    need(record["state"] != "RELEASED" and args.reason.strip(), "explicit unreleased record and reason required")
                    record.update(state="RELEASED", releasedAt=stamp(), releaseReason=args.reason); save(file, state); result = {"released": record["id"], "filesOrProcessesChanged": False}
                else:
                    result = check_record(config, record)
                    if args.command == "deliver":
                        need(result["ok"] and not result["dirty"], "delivery requires current, unexpired, clean, in-scope worktree")
                        record.update(state="DELIVERED", delivery={"headSha": result["headSha"], "baseSha": record["baseSha"], "changedPaths": result["changedPaths"], "checkedAt": stamp(), "qualification": "NOT_ASSESSED"})
                        save(file, state); result["scopeRemainsReservedUntilRelease"] = True
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if result.get("ok") is False: return 1
    return 0
if __name__ == "__main__":
    try: raise SystemExit(main())
    except (ValueError, OSError, json.JSONDecodeError) as error: raise SystemExit("Coordination rejected: " + str(error))
