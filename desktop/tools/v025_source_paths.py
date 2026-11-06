"""Strict build-time original-source locator. Not a runtime/store/network owner."""
from pathlib import Path
import hashlib,json,subprocess
from functools import lru_cache

@lru_cache(maxsize=1)
def _catalog():
    file=Path(__file__).with_name("v025-canonical-sources.json")
    raw=file.read_bytes()
    if hashlib.sha256(raw).hexdigest()!="2600739eef5521cfef71d6af16762ccc3b9df076dad7ccb79902944f1634130f":
        raise ValueError("v025 canonical source catalog digest mismatch")
    return json.loads(raw)

def _legacy_snapshot(root):
    # Gradle materializes the pre-merge tree at <repo>/.legacy-root. Extractors
    # still patch that snapshot; the updater inventory pins the direct-compile tree.
    return root.name==".legacy-root"

def canonical_relative(repo, relative):
    """Translate one identity without reading it; unknown/removed upstream fails."""
    root=Path(repo).resolve()
    path=Path(relative)
    if path.is_absolute():
        try:path=path.relative_to(root)
        except ValueError:raise ValueError("Source path escapes the declared repository") from None
    if path.drive or ".." in path.parts:raise ValueError("Source path contains traversal or a drive")
    name=path.as_posix()
    catalog=_catalog()
    if not name.startswith(tuple(catalog["sourceRoots"])):
        return name  # existing desktop metadata remains at its original owner
    target=catalog["previousPaths"].get(name,name)
    # The snapshot still contains the pre-move file. Keep that identity so the
    # legacy pin is compared to the bytes the patch was reviewed against.
    if _legacy_snapshot(root) and name in catalog["paths"] and (root/name).is_file():
        target=name
    if target is None:raise ValueError("Upstream removed this original source: "+name)
    if target not in catalog["paths"]:raise ValueError("Unregistered canonical upstream source: "+target)
    return target

def reviewed_sha256(repo, pin):
    """Exact pin for the tree being read. Live and legacy hashes are not interchangeable."""
    if _legacy_snapshot(Path(repo).resolve()) and "legacySha256" in pin:
        return pin["legacySha256"]
    return pin["sha256"]

def registered_source_sha256(repo, row):
    """Authenticate an inventory row, then select this read point's exact pin.

    Gradle's archived inventory and the Python gate's live inventory describe
    the same reviewed source lineage. Neither inventory may repin the original.
    """
    pin = _catalog()["paths"].get(row["path"])
    if pin is None:
        return row["sha256"]
    allowed = {pin["sha256"]}
    if _legacy_snapshot(Path(repo).resolve()):
        allowed.add(reviewed_sha256(repo, pin))
    if row["sha256"] not in allowed:
        raise ValueError("Registered original source digest mismatch: " + row["path"])
    return reviewed_sha256(repo, pin)

def registered_source_row(repo, manifest, identity):
    """Resolve a historical row only through its authenticated live replacement.

    The Python read point carries the current desktop inventory. A replaced
    original can still be consumed by a historical producer, but its old feature
    declaration must come from the exact archived inventory, never a fabricated row.
    """
    rows = [row for row in manifest['sources'] if row['path'] == identity]
    if rows:
        if len(rows) != 1:
            raise ValueError('Duplicate original source registration: ' + identity)
        return rows[0]
    root = Path(repo).resolve()
    replacement = _catalog()['previousPaths'].get(identity)
    if not _legacy_snapshot(root) or not replacement or replacement == identity:
        raise ValueError('Missing original source registration: ' + identity)
    successors = [row for row in manifest['sources'] if row['path'] == replacement
                  and identity in row.get('replacesPaths', [])]
    if len(successors) != 1:
        raise ValueError('Missing original source replacement: ' + identity)
    registered_source_sha256(root, successors[0])
    raw = subprocess.check_output(['git', 'show',
        'd9595dc75cfe8d5c7128cb76352a607953b8bab6:desktop/upstream-sources.json'], cwd=root)
    if hashlib.sha256(raw).hexdigest() != '9689644ce92a15288b9ceff7211e9ba5046bfd5765e40d053c41e414f9ef536d':
        raise ValueError('Historical source inventory digest mismatch')
    rows = [row for row in json.loads(raw)['sources'] if row['path'] == identity]
    if len(rows) != 1 or not set(rows[0]['features']).issubset(successors[0]['features']):
        raise ValueError('Historical source feature lineage mismatch: ' + identity)
    registered_source_sha256(root, rows[0])
    return rows[0]

def canonical_source(repo, relative):
    root=Path(repo).resolve()
    target=canonical_relative(root,relative)
    destination=root/target
    if destination.is_symlink() or not destination.resolve().is_relative_to(root):
        raise ValueError("Canonical source escapes its repository or is a symbolic link")
    catalog=_catalog()
    if not target.startswith(tuple(catalog["sourceRoots"])):
        return destination
    pin=catalog["paths"][target]
    raw=destination.read_bytes()
    normalized=raw.replace(b"\r\n",b"\n") if pin["hashNormalization"]=="lf" else raw
    if hashlib.sha256(normalized).hexdigest()!=reviewed_sha256(root, pin):
        raise ValueError("Canonical original source digest mismatch: "+target)
    return destination
