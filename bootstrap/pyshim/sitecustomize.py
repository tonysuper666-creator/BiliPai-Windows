# Out-of-repo Linux shim: strips Windows long-path prefixes (\\?\ , \\?\UNC\) that repo
# generators prepend unconditionally. Loaded via PYTHONPATH so sys.executable children inherit it.
import pathlib
_P = "\\\\?\\"
def _strip(a):
    if isinstance(a, str) and a.startswith(_P):
        a = a[len(_P):]
        if a.startswith("UNC\\"): a = "//" + a[4:]
    return a
def _wrap(cls):
    orig = cls.__dict__["__init__"]
    def _init(self, *args, **kw):
        orig(self, *[_strip(a) for a in args], **kw)
    cls.__init__ = _init
for _c in (pathlib.PurePath, pathlib.Path):
    if "__init__" in _c.__dict__: _wrap(_c)
