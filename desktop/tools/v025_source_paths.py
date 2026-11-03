"""Strict build-time original-source locator. Not a runtime/store/network owner."""
from pathlib import Path
import hashlib,json
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
