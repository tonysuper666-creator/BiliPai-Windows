"""POSIX hook for replaying Source260 head aad198489.

Loaded only when that class puts this directory on PYTHONPATH for producer
subprocesses. Historical producers prepend the Windows extended-length prefix
to every absolute path and do not check os.name. On Windows the prefix is the
long-path namespace and the file opens. On macOS those four characters make
the path nonexistent, so the producers exit before the historical findings
exist. Strip the prefix when the remainder is a POSIX absolute path. Windows
is unchanged.
"""
import builtins
import os
import pathlib

_PREFIX = '\\\\?\\'
_INSTALLED = False


def _strip(value):
    if isinstance(value, bytes):
        text = os.fsdecode(value)
        stripped = _strip(text)
        return os.fsencode(stripped) if stripped != text else value
    if isinstance(value, str) and value.startswith(_PREFIX):
        rest = value[len(_PREFIX):]
        if rest.startswith('/'):
            return rest
    if isinstance(value, os.PathLike):
        text = os.fspath(value)
        stripped = _strip(text)
        if stripped != text:
            return stripped
    return value


def _wrap(function):
    def inner(path, *args, **kwargs):
        return function(_strip(path), *args, **kwargs)
    inner.__wrapped__ = function
    return inner


def _wrap_pair(function):
    def inner(source, target, *args, **kwargs):
        return function(_strip(source), _strip(target), *args, **kwargs)
    inner.__wrapped__ = function
    return inner


def _install():
    global _INSTALLED
    if _INSTALLED or os.name == 'nt':
        return
    original = pathlib.PurePath.__init__

    def init(self, *args):
        original(self, *(_strip(arg) for arg in args))

    pathlib.PurePath.__init__ = init
    builtins.open = _wrap(builtins.open)
    for name in ('stat', 'lstat', 'open', 'mkdir', 'makedirs', 'listdir', 'scandir',
                 'remove', 'unlink', 'chmod', 'access'):
        if hasattr(os, name):
            setattr(os, name, _wrap(getattr(os, name)))
    for name in ('rename', 'replace'):
        setattr(os, name, _wrap_pair(getattr(os, name)))
    _INSTALLED = True


_install()
