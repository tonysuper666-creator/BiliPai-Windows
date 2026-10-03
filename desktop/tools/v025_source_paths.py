"""Strict build-time original-source locator. Not a runtime/store/network owner."""
from pathlib import Path
import hashlib,json
from functools import lru_cache

@lru_cache(maxsize=1)
def _catalog():
    file=Path(__file__).with_name("v025-canonical-sources.json")
    raw=file.read_bytes()
    if hashlib.sha256(raw).hexdigest()!="2fa53aa78cc27c76a3923cc44750128b3657c340dbcfe44ec13810cbc48becbc":
        raise ValueError("v025 canonical source catalog digest mismatch")
    return json.loads(raw)

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
    if target is None:raise ValueError("Upstream removed this original source: "+name)
    if target not in catalog["paths"]:raise ValueError("Unregistered canonical upstream source: "+target)
    return target

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
    if hashlib.sha256(normalized).hexdigest()!=pin["sha256"]:
        raise ValueError("Canonical original source digest mismatch: "+target)
    return destination
