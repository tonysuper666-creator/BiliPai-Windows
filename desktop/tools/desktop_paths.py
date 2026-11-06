"""Windows extended-length paths for the desktop producers.

The ``\\\\?\\`` namespace prefix exists only on Windows. Applied on any other
platform it turns the whole string into one literal file name, so the prefix is
added only when ``os.name == 'nt'`` (the same rule as
``v046_bangumi_hub_pages.wide``) and every other platform keeps the plain path.
Generated trees below the checkout still exceed the Windows limit, so Windows
builds keep the prefix exactly as before.
"""
from __future__ import annotations

import os
from pathlib import Path

LONG_PREFIX = '\\\\?\\'
UNC_LONG_PREFIX = '\\\\?\\UNC\\'


def long_path(value) -> Path:
    """Return ``value`` in the Windows extended-length form; unchanged elsewhere."""
    text = os.fspath(value)
    if os.name != 'nt' or text.startswith(LONG_PREFIX):
        return Path(text)
    if text.startswith('\\\\'):
        return Path(UNC_LONG_PREFIX + text[2:])
    return Path(LONG_PREFIX + text)


def read_point_tool(repo, name: str) -> Path:
    """The sibling producer ``name`` that a producer loads or runs against the read point ``repo``.

    Windows keeps the read point's own copy, ``<repo>/desktop/tools/<name>``, exactly as before.
    The legacy read point is materialized from the pre-merge commit, and its copies of these
    producers prepend the long-path prefix unconditionally, so on any other platform they cannot
    open a single file. There the current copy beside this module is used instead; the build
    already runs every top-level producer from this directory against the same read point. Data
    files are never redirected: callers keep reading manifests, catalogs and originals from ``repo``.
    """
    if os.name == 'nt':
        return Path(repo) / 'desktop/tools' / name
    return Path(__file__).resolve().with_name(name)
